# frozen_string_literal: true

# A running provider over the SDK's C ABI, through the urnetwork gem (FFI):
# the network space manager, the provider device with the installation's
# identity, and the listeners that feed its status.
#
# SDK callbacks run on SDK threads, which FFI hands to a Ruby thread. They copy
# what they carry, then either count it under the clients-served lock or hand
# it to the run loop through the events queue; the run loop's thread owns every
# other field and makes every other SDK call. The callback procs stay
# referenced for the life of the process, because the SDK can still answer a
# wallet read after the device closes.
#
# The URnetwork module is passed in rather than required, so that the
# self-test never loads the native runtime and the tests can run the lifecycle
# against a stand-in for the C ABI.

require "ffi"
require "fileutils"
require_relative "state"
require_relative "status"

module Provider
  # One run of the provider. The constructor starts providing; run shows the
  # status until a stop request; close stops providing.
  class Session
    # How often the status is read, and the longest gap between status lines.
    STATUS_POLL_INTERVAL_SECONDS = 1
    STATUS_REPEAT_INTERVAL_SECONDS = 60

    # How often the payout wallet is read again. The wallet is fixed; a reread
    # shows a mapping that the backend completes while the provider runs.
    WALLET_SYNC_INTERVAL_SECONDS = 10 * 60

    # The device description and spec recorded for this installation's device.
    DEVICE_DESCRIPTION = "Ruby provider example"
    DEVICE_SPEC = "urnetwork-examples/ruby-provider"
    APP_VERSION = "1"

    # The provider extender role's two device settings (PROVIDER_CONTRACT.md,
    # "Extender role"), passed explicitly and both on.
    # PROVIDE_EXTENDER_ENABLED is the embedder's hard switch: false means the
    # role never runs. DEFAULT_PROVIDE_EXTENDER is the setting the device uses
    # until the user sets one: false turns the default off.
    PROVIDE_EXTENDER_ENABLED = true
    DEFAULT_PROVIDE_EXTENDER = true

    # the ur.network main network space, as the integration helpers create it
    NETWORK_SPACE_KEY_JSON = '{"host_name":"ur.network","env_name":"main"}'
    NETWORK_SPACE_VALUES_JSON = '{"migration_host_name":"bringyour.com"}'

    # run loop events, each an array that starts with one of these
    EVENT_STOP = :stop
    EVENT_JWT_REFRESHED = :jwt_refreshed
    EVENT_AUTH_LOGOUT = :auth_logout
    EVENT_WALLET_SYNCED = :wallet_synced

    # FFI callback procs that the SDK may still call; one set per session
    CALLBACK_ROOTS = []

    # Keeps the SDK's log files in log_dir, which the SDK bounds (16 MiB files,
    # the newest four kept at each start), instead of the system temp
    # directory. The SDK also copies its log lines to stderr, and the C ABI
    # keeps that copy. sdk is the URnetwork module.
    def self.configure_sdk_logs(sdk, log_dir)
      FileUtils.mkdir_p(log_dir, mode: 0o700)
      error = FFI::MemoryPointer.new(:pointer)
      return if sdk::Raw.urnet_set_log_dir(log_dir, error)

      raise IOError, sdk.take_string(error.read_pointer) || "the sdk refused the log directory"
    end

    # Creates the provider device with the installation's identity and the
    # extender role's settings on, saves a new identity on first run, and
    # starts providing publicly. The SDK declares provide intent on the
    # device's platform connections by itself while the provide mode is public.
    # sdk is the URnetwork module; events is the run loop's queue, which also
    # carries stop requests.
    def initialize(config, sdk, events)
      @config = config
      @sdk = sdk
      @raw = sdk::Raw
      @events = events
      @clients_served = ClientsServed.new(CLIENTS_SERVED_LIMIT)
      # PAYOUT_WALLET_CHECKING, PAYOUT_WALLET_UNAVAILABLE, PAYOUT_WALLET_NOT_SET
      # or the mapped coldkey
      @payout_wallet = PAYOUT_WALLET_CHECKING
      @payout_wallet_scope = ""
      @subs = []
      @closed = false
      # what close undoes, run last first: the subscriptions, the device, then the manager
      @closers = []

      @jwt_refresh_callback = method(:jwt_refreshed).to_proc
      @auth_logout_callback = method(:auth_logout).to_proc
      @ingress_callback = method(:ingress_contract_details_changed).to_proc
      @egress_callback = method(:egress_contract_details_changed).to_proc
      @wallet_callback = method(:wallet_synced).to_proc
      CALLBACK_ROOTS.push(@jwt_refresh_callback, @auth_logout_callback, @ingress_callback, @egress_callback, @wallet_callback)

      begin
        start
      rescue StandardError
        run_closers
        raise
      end
      sync_wallet
    end

    # Reads the status from the device getters and the listener state.
    def status
      device = @device.handle
      # "client_limit_exceeded" with the hold's end in RetryTime while the
      # platform holds this client off for its network's client limit
      client_limit_status, client_limit_retry_time = Provider.parse_client_limit_status(
        @sdk.take_string(@raw.urnet_device_get_client_limit_status(device)),
      )
      # urnet_device_get_provider_ready also waits for processed client key
      # registration, which default device settings do not enable, so the
      # connected carrier is the readiness signal here
      state = Provider.provider_state(
        @raw.urnet_device_get_provide_mode(device),
        client_limit_status,
        @raw.urnet_device_get_provide_paused(device),
        @raw.urnet_device_get_provide_enabled(device),
        @raw.urnet_device_local_get_provider_connected(device),
      )
      data_provided_byte_count = Provider.parse_data_provided_byte_count(
        @sdk.take_string(@raw.urnet_device_get_provider_packet_stats(device)),
      )
      clients_served, clients_served_at_limit = @clients_served.count
      Status.new(
        state: state,
        client_limit_retry_time: client_limit_retry_time,
        clients_served: clients_served,
        clients_served_at_limit: clients_served_at_limit,
        data_provided_byte_count: data_provided_byte_count,
        payout_wallet: @payout_wallet,
        payout_wallet_scope: @payout_wallet_scope,
      )
    end

    # Reads the payout wallet (GET /sn/wallet with the client credential). The
    # app only displays the wallet: the backend maps it, never the app.
    def sync_wallet
      @raw.urnet_device_local_sync_sn_wallet(@device.handle, @wallet_callback, nil)
    end

    # Prints status lines until a stop request. Raises ConfigError when the
    # server rejects the client credential.
    def run
      last_key = nil
      last_print_time = 0.0
      next_wallet_sync_time = monotonic_now + WALLET_SYNC_INTERVAL_SECONDS
      loop do
        snapshot = status
        now = monotonic_now
        if snapshot.key != last_key || STATUS_REPEAT_INTERVAL_SECONDS <= now - last_print_time
          puts snapshot.line
          last_key = snapshot.key
          last_print_time = now
        end
        if next_wallet_sync_time <= now
          sync_wallet
          next_wallet_sync_time = now + WALLET_SYNC_INTERVAL_SECONDS
        end
        return if apply_events_until(now + STATUS_POLL_INTERVAL_SECONDS)
      end
    end

    # Stops providing, closes the subscriptions, the device and then the
    # manager, and saves a refreshed credential that arrived meanwhile.
    def close
      return if @closed

      @closed = true
      @raw.urnet_device_set_provide_mode(@device.handle, PROVIDE_MODE_NONE)
      run_closers
      # keep a refresh that arrived after the run loop's last read
      while (event = @events.pop(timeout: 0))
        save_client_jwt(event[1]) if event[0] == EVENT_JWT_REFRESHED
      end
      puts "status: stopped"
    end

    private

    # Creates the manager, the network space and the device, then adds the
    # listeners and starts providing. Each step registers what undoes it.
    def start
      raw = @raw
      manager = @sdk::Handle.new(raw.urnet_new_network_space_manager_no_storage)
      manager_handle = manager.handle
      @closers << -> { manager.close }
      @closers << -> { raw.urnet_network_space_manager_close(manager_handle) }
      space = @sdk::Handle.new(
        raw.urnet_network_space_manager_update_network_space_values(manager_handle, NETWORK_SPACE_KEY_JSON, NETWORK_SPACE_VALUES_JSON),
      )
      @closers << -> { space.close }
      api = @sdk::Handle.new(raw.urnet_network_space_get_api(space.handle))
      @closers << -> { api.close }
      raw.urnet_api_set_by_jwt(api.handle, @config.client_jwt)
      @device = @sdk::Device.new(new_device(space.handle))
      @closers << -> { @device.close }
      # keep the new identity, so later starts present the same provider
      save_new_identity if @config.identity.nil?
      device = @device.handle
      @subs = [
        raw.urnet_device_add_jwt_refresh_listener(device, @jwt_refresh_callback, nil),
        raw.urnet_device_add_auth_logout_listener(device, @auth_logout_callback, nil),
        raw.urnet_device_add_provider_ingress_contract_details_change_listener(device, @ingress_callback, nil),
        raw.urnet_device_add_provider_egress_contract_details_change_listener(device, @egress_callback, nil),
      ]
      @closers << -> { close_subs }
      raw.urnet_device_set_provide_mode(device, PROVIDE_MODE_PUBLIC)
    end

    # Creates the device with the extender-aware constructor: one call for both
    # runs. On first run there is no identity, the key material is 0 and the
    # device makes a new identity, which the caller saves.
    def new_device(space)
      key_material = new_key_material(State.key_material(@config.identity))
      error = FFI::MemoryPointer.new(:pointer)
      begin
        device = @raw.urnet_new_device_local_with_provide_extender(
          space,
          @config.client_jwt,
          DEVICE_DESCRIPTION,
          DEVICE_SPEC,
          APP_VERSION,
          @config.instance_id,
          false,
          key_material,
          PROVIDE_EXTENDER_ENABLED,
          DEFAULT_PROVIDE_EXTENDER,
          error,
        )
        message = @sdk.take_string(error.read_pointer)
      ensure
        @raw.urnet_release(key_material) unless key_material.zero?
      end
      raise message if message
      raise "the sdk did not create the device" if device.zero?

      device
    end

    # A native key material handle for the device constructor, which the
    # caller releases after the call; 0 without key material (the first run).
    def new_key_material(key_material)
      return 0 if key_material.nil?

      buffer = lambda do |data|
        next [nil, 0] if data.empty?

        memory = FFI::MemoryPointer.new(:uint8, data.bytesize)
        memory.put_bytes(0, data)
        [memory, data.bytesize]
      end
      seed, seed_length = buffer.call(key_material.client_key_seed)
      certificate, certificate_length = buffer.call(key_material.provide_tls_certificate_pem)
      private_key, private_key_length = buffer.call(key_material.provide_tls_private_key_pem)
      handle = @raw.urnet_new_device_local_key_material(
        seed, seed_length, certificate, certificate_length, private_key, private_key_length,
      )
      raise "the sdk did not create the key material" if handle.zero?

      unless key_material.extender_key_seed.empty?
        extender_seed, extender_seed_length = buffer.call(key_material.extender_key_seed)
        @raw.urnet_device_local_key_material_set_extender_key_seed(handle, extender_seed, extender_seed_length)
      end
      handle
    end

    # Bytes from a buffer-out getter of the C ABI: the first call asks for the
    # size, the second copies. "" when the device has none.
    def read_device_bytes(getter, device)
      size = FFI::MemoryPointer.new(:int32)
      size.write_int32(0)
      @raw.public_send(getter, device, nil, size)
      length = size.read_int32
      return "".b if length <= 0

      buffer = FFI::MemoryPointer.new(:uint8, length)
      return "".b unless @raw.public_send(getter, device, buffer, size)

      buffer.read_bytes(size.read_int32)
    end

    # Saves the device's new identity as identity.json, on first run.
    def save_new_identity
      device = @device.handle
      identity = Identity.new(
        client_id: @config.client_id,
        client_key_seed: read_device_bytes(:urnet_device_local_get_client_key_seed, device),
        provide_tls_certificate_pem: read_device_bytes(:urnet_device_local_get_provide_tls_certificate_pem, device),
        provide_tls_private_key_pem: read_device_bytes(:urnet_device_local_get_provide_tls_private_key_pem, device),
        extender_key_seed: read_device_bytes(:urnet_device_local_get_extender_key_seed, device),
      )
      raise "the device has no provider identity to save" if identity.client_key_seed.bytesize != 32

      begin
        State.save_identity(@config.state_dir, identity)
      rescue SystemCallError, IOError => error
        raise "save #{State::IDENTITY_FILE_NAME}: #{error.message}"
      end
    end

    # Applies run loop events as they arrive until the deadline; true when a
    # stop was requested.
    def apply_events_until(deadline)
      loop do
        timeout = deadline - monotonic_now
        return false if timeout <= 0

        event = @events.pop(timeout: timeout)
        return false if event.nil?
        return true if apply_event(event)
      end
    end

    # Applies one run loop event; true for a stop request. Raises ConfigError
    # when the server rejected the client credential.
    def apply_event(event)
      case event[0]
      when EVENT_STOP
        return true
      when EVENT_AUTH_LOGOUT
        raise ConfigError, "the server rejected the client credential; issue a new scoped client JWT from your backend"
      when EVENT_JWT_REFRESHED
        save_client_jwt(event[1])
      when EVENT_WALLET_SYNCED
        record_wallet_read(event[1], event[2])
      end
      false
    end

    # Runs what close undoes, last first, once.
    def run_closers
      @closers.reverse_each(&:call)
      @closers.clear
    end

    # Closes and releases the listener subscriptions.
    def close_subs
      @subs.each do |sub|
        @raw.urnet_sub_close(sub)
        @raw.urnet_release(sub)
      end
      @subs = []
    end

    # Keeps the refreshed credential, so the next start uses a valid token.
    def save_client_jwt(client_jwt)
      return if client_jwt.nil? || client_jwt.empty?

      State.write_private_file(File.join(@config.state_dir, State::CLIENT_JWT_FILE_NAME), "#{client_jwt}\n")
    rescue SystemCallError, IOError => error
      # never print the token itself
      warn "could not save the refreshed client credential: #{error.message}"
    end

    # Records a payout wallet read. A failure keeps the last known wallet.
    def record_wallet_read(result_json, error_text)
      result = Provider.parse_json_object(result_json)
      if !error_text.nil? || result.nil? || !result["error"].nil?
        @payout_wallet = PAYOUT_WALLET_UNAVAILABLE if @payout_wallet == PAYOUT_WALLET_CHECKING
        return
      end
      # the sdk caches the effective wallet before the callback: this client's
      # own consent, else the network consent, else (with hotkey delegations)
      # the network's hotkey entry, else a non-consent wallet
      wallet = Provider.parse_json_object(@sdk.take_string(@raw.urnet_device_local_get_sn_wallet(@device.handle)))
      coldkey = wallet.nil? ? "" : Provider.json_text(wallet["coldkey_ss58"])
      if coldkey.empty?
        @payout_wallet = PAYOUT_WALLET_NOT_SET
        @payout_wallet_scope = ""
        return
      end
      @payout_wallet = coldkey
      @payout_wallet_scope = Provider.payout_wallet_scope(
        Provider.json_text(wallet["consent_scope"]),
        Provider.json_text(wallet["client_id"]),
        @config.client_id,
      )
    end

    # Hands a refreshed client JWT to the run loop, which saves it. Runs on an
    # SDK thread.
    def jwt_refreshed(_user_data, client_jwt)
      @events.push([EVENT_JWT_REFRESHED, client_jwt&.dup])
    end

    # Ends the run: the server no longer accepts this client's credential. Runs
    # on an SDK thread.
    def auth_logout(_user_data)
      @events.push([EVENT_AUTH_LOGOUT])
    end

    # Counts the peer of a receive contract. Runs on an SDK thread.
    def ingress_contract_details_changed(_user_data, details_json)
      @clients_served.add(Provider.parse_json_object(details_json), true)
    end

    # Counts the peer of a send contract. Runs on an SDK thread.
    def egress_contract_details_changed(_user_data, details_json)
      @clients_served.add(Provider.parse_json_object(details_json), false)
    end

    # Hands a payout wallet read to the run loop. Runs on an SDK thread.
    def wallet_synced(_user_data, result_json, error_text)
      @events.push([EVENT_WALLET_SYNCED, result_json&.dup, error_text&.dup])
    end

    # Seconds on a clock that never goes back.
    def monotonic_now
      Process.clock_gettime(Process::CLOCK_MONOTONIC)
    end
  end
end
