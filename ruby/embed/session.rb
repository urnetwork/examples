# frozen_string_literal: true

# A running embed app over the SDK's C ABI, through the urnetwork gem (FFI):
# the network space manager, the embedded device, the listeners that feed its
# status, and the reader of the client's own data caps (EMBED_CONTRACT.md,
# "App lifecycle").
#
# SDK callbacks run on SDK threads, which FFI hands to a Ruby thread. They
# copy what they carry and hand it to the run loop through the events queue.
# The cap reader makes its HTTP reads on a thread of its own and hands each
# reading over the same queue. The run loop's thread owns every other field
# and makes every other SDK call. The callback procs stay referenced for the
# life of the process.
#
# The URnetwork module is passed in rather than required, so that the
# self-test never loads the native runtime and the tests can run the lifecycle
# against a stand-in for the C ABI.

require "ffi"
require "fileutils"
require_relative "cap_reader"
require_relative "caps"
require_relative "state"
require_relative "status"

module Embed
  # What the session starts with. client_id is the client_id claim of
  # client_jwt; api_origin is the API origin for the cap reads
  # (URNETWORK_API_URL); first_cap_reading is the token server's data_cap, or
  # nil to read the caps at start.
  Config = Struct.new(:state_dir, :client_jwt, :client_id, :instance_id, :api_origin, :first_cap_reading, keyword_init: true)

  # One run of the embedded device. The constructor starts it; run shows the
  # status until a stop request; close stops it.
  class Session
    # The device description and spec recorded for this installation's device.
    DEVICE_DESCRIPTION = "Ruby embed example"
    DEVICE_SPEC = "urnetwork-examples/ruby-embed"
    APP_VERSION = "1"

    # the ur.network main network space, as the integration helpers create it
    NETWORK_SPACE_KEY_JSON = '{"host_name":"ur.network","env_name":"main"}'
    NETWORK_SPACE_VALUES_JSON = '{"migration_host_name":"bringyour.com"}'

    # the destination of the app's own traffic: the best available location
    CONNECT_LOCATION_JSON = '{"connect_location_id":{"best_available":true}}'

    # FFI callback procs that the SDK may still call; one set per session
    CALLBACK_ROOTS = []

    # Keeps the SDK's log files in log_dir, which the SDK bounds, instead of
    # the system temp directory. The SDK also copies its log lines to stderr,
    # and the C ABI keeps that copy. sdk is the URnetwork module.
    def self.configure_sdk_logs(sdk, log_dir)
      FileUtils.mkdir_p(log_dir, mode: 0o700)
      error = FFI::MemoryPointer.new(:pointer)
      return if sdk::Raw.urnet_set_log_dir(log_dir, error)

      raise IOError, sdk.take_string(error.read_pointer) || "the sdk refused the log directory"
    end

    # Creates the manager, the network space and the device with the
    # installation's client JWT and instance ID, adds the listeners, sets the
    # connect location to best available and starts reading the caps. sdk is
    # the URnetwork module; events is the run loop's queue, which also carries
    # stop requests; transport makes the cap reads (transport.rb).
    def initialize(config, sdk, events, transport, cap_reader_class: CapReader)
      @config = config
      @sdk = sdk
      @raw = sdk::Raw
      @events = events
      @transport = transport
      @cap_state = CapState.new(config.first_cap_reading)
      # the latest client JWT, which the SDK refreshes; read by the cap reader's thread
      @client_jwt = config.client_jwt
      @client_jwt_lock = Mutex.new
      @subs = []
      @closed = false
      # what close undoes, run last first: the subscriptions, the device, then the manager
      @closers = []

      @jwt_refresh_callback = method(:jwt_refreshed).to_proc
      @auth_logout_callback = method(:auth_logout).to_proc
      @contract_status_callback = method(:contract_status_changed).to_proc
      CALLBACK_ROOTS.push(@jwt_refresh_callback, @auth_logout_callback, @contract_status_callback)

      begin
        start
      rescue StandardError
        run_closers
        raise
      end
      @cap_reader = cap_reader_class.new(method(:read_caps), events)
      @cap_reader.start(config.first_cap_reading.nil?)
    end

    # Reads the status from the device getters and the latest cap reading.
    def status
      device = @device.handle
      # "client_limit_exceeded" with the hold's end in RetryTime while the
      # platform holds this client off for its network's client limit
      client_limit_status, client_limit_retry_time = Embed.parse_client_limit_status(
        @sdk.take_string(@raw.urnet_device_get_client_limit_status(device)),
      )
      providers_added = Embed.parse_providers_added(@sdk.take_string(@raw.urnet_device_get_window_status(device)))
      Embed.status_snapshot(true, false, client_limit_status, client_limit_retry_time, @cap_state, providers_added)
    end

    # Prints a status line when any field's text changes, and otherwise once a
    # minute, until a stop request. Raises ConfigError when the server rejects
    # the client credential.
    def run
      last_line = nil
      last_print_time = 0.0
      loop do
        line = status.line
        now = monotonic_now
        if line != last_line || STATUS_REPEAT_INTERVAL_SECONDS <= now - last_print_time
          puts line
          last_line = line
          last_print_time = now
        end
        return if apply_events_until(now + STATUS_POLL_INTERVAL_SECONDS)
      end
    end

    # Applies one run loop event; true for a stop request. Raises ConfigError
    # when the server rejected the client credential.
    def apply_event(event)
      case event[0]
      when EVENT_STOP
        return true
      when EVENT_AUTH_LOGOUT
        raise ConfigError, "the server rejected the client credential; sign in again so that your backend reissues the client"
      when EVENT_JWT_REFRESHED
        save_client_jwt(event[1])
      when EVENT_CONTRACT_STATUS_CHANGED
        # a cap may have been reached or lifted
        @cap_reader.wake
      when EVENT_CAPS_READ
        @cap_state.record(event[1])
      end
      false
    end

    # Stops reading the caps, closes the subscriptions, the device and then
    # the manager, and saves a refreshed credential that arrived meanwhile.
    def close
      return if @closed

      @closed = true
      @cap_reader.stop
      run_closers
      # keep a refresh that arrived after the run loop's last read
      while (event = @events.pop(timeout: 0))
        save_client_jwt(event[1]) if event[0] == EVENT_JWT_REFRESHED
      end
    end

    private

    # Creates the manager, the network space and the device, then adds the
    # listeners and sets the connect location. Each step registers what undoes it.
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
      device = @device.handle
      @subs = [
        raw.urnet_device_add_jwt_refresh_listener(device, @jwt_refresh_callback, nil),
        raw.urnet_device_add_auth_logout_listener(device, @auth_logout_callback, nil),
        raw.urnet_device_add_contract_status_change_listener(device, @contract_status_callback, nil),
      ]
      @closers << -> { close_subs }
      raw.urnet_device_set_connect_location(device, CONNECT_LOCATION_JSON)
    end

    # Creates the local device. An embed app leaves the provide mode at its
    # default: it does not provide.
    def new_device(space)
      error = FFI::MemoryPointer.new(:pointer)
      device = @raw.urnet_new_device_local_with_defaults(
        space,
        @config.client_jwt,
        DEVICE_DESCRIPTION,
        DEVICE_SPEC,
        APP_VERSION,
        @config.instance_id,
        false,
        error,
      )
      message = @sdk.take_string(error.read_pointer)
      raise message if message
      raise "the sdk did not create the device" if device.zero?

      device
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

    def current_client_jwt
      @client_jwt_lock.synchronize { @client_jwt }
    end

    # Reads this client's own caps with its latest client JWT. Runs on the cap
    # reader's thread.
    def read_caps
      Caps.read_own_caps(@config.api_origin, current_client_jwt, @transport)
    end

    # Keeps the refreshed credential for the cap reads and the next start.
    def save_client_jwt(client_jwt)
      return if client_jwt.nil? || client_jwt.empty?

      text = State.text(client_jwt).strip
      @client_jwt_lock.synchronize { @client_jwt = text }
      State.save_client_jwt(@config.state_dir, text)
    rescue SystemCallError, IOError => error
      # never print the token itself
      warn "could not save the refreshed client credential: #{error.message}"
    end

    # Hands a refreshed client JWT to the run loop, which saves it. Runs on an
    # SDK thread.
    def jwt_refreshed(_user_data, client_jwt)
      @events.push([EVENT_JWT_REFRESHED, client_jwt&.dup])
    end

    # Ends the run: the server no longer accepts this client's credential.
    # Runs on an SDK thread.
    def auth_logout(_user_data)
      @events.push([EVENT_AUTH_LOGOUT])
    end

    # Asks for a cap read: a contract status change can mean a cap was
    # reached. Runs on an SDK thread.
    def contract_status_changed(_user_data, _contract_status_json)
      @events.push([EVENT_CONTRACT_STATUS_CHANGED])
    end

    # Seconds on a clock that never goes back.
    def monotonic_now
      Process.clock_gettime(Process::CLOCK_MONOTONIC)
    end
  end
end
