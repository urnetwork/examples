# frozen_string_literal: true

# Tests for the provider example: each self-test check as a test, so minitest
# and --self-test cover the same behavior, and the provider lifecycle
# (session.rb) against a stand-in for the SDK's C ABI. The stand-in follows the
# C ABI's documented behavior for handles, returned strings, buffer-out getters
# and callbacks, without a device, network or credentials. The lifecycle tests
# need the ffi gem; when the urnetwork gem and its native library load as well,
# every call is also checked against the gem's FFI signatures.
#
# Run from this directory: ruby provider_test.rb, or bundle exec ruby provider_test.rb

require "minitest/autorun"
require "fileutils"
require "json"
require "tmpdir"
require_relative "commands"
require_relative "selftest"

FFI_LOADED =
  begin
    require "ffi"
    true
  rescue LoadError
    false
  end

NATIVE_SDK =
  if FFI_LOADED
    begin
      require "urnetwork"
      URnetwork
    rescue LoadError, StandardError
      nil
    end
  end

require_relative "session" if FFI_LOADED

# Each self-test check as a test.
class SelfTestChecksTest < Minitest::Test
  Provider::SelfTest::CHECKS.each do |check|
    define_method("test_#{check.to_s.delete_prefix('check_')}") { Provider::SelfTest.public_send(check) }
  end
end

# Sets environment variables for the block and restores them after it.
def with_env(values)
  saved = values.keys.to_h { |name| [name, ENV[name]] }
  values.each { |name, value| ENV[name] = value }
  yield
ensure
  saved.each { |name, value| ENV[name] = value }
end

# The run command's exit codes for configuration problems, before any SDK call.
class ProviderRunExitCodesTest < Minitest::Test
  def test_missing_or_relative_state_dir_exits_78
    ["", File.join("relative", "state")].each do |state_dir|
      with_env("URNETWORK_PROVIDER_STATE_DIR" => state_dir) do
        capture_io { assert_equal Provider::EXIT_CONFIG, Provider.run(["run"]), state_dir }
      end
    end
  end

  def test_missing_client_jwt_exits_78
    Dir.mktmpdir("ur-provider-test-") do |state_dir|
      File.chmod(0o700, state_dir) if Provider::State::POSIX
      with_env("URNETWORK_PROVIDER_STATE_DIR" => state_dir) do
        stdout, = capture_io { assert_equal Provider::EXIT_CONFIG, Provider.run([]) }
        # the disclaimer comes first, even when the configuration is missing
        assert stdout.start_with?("Consent disclaimer: ")
      end
    end
  end
end

unless FFI_LOADED
  # Reports the lifecycle tests as skipped without the ffi gem.
  class ProviderSessionLifecycleTest < Minitest::Test
    def test_lifecycle_needs_the_ffi_gem
      skip "the lifecycle tests need the ffi gem: bundle install, then bundle exec ruby provider_test.rb"
    end
  end
end

if FFI_LOADED
  # the identity that the stand-in device makes on first run
  DEVICE_CLIENT_KEY_SEED = (0...32).to_a.pack("C*")
  DEVICE_CERTIFICATE_PEM = "synthetic device certificate".b
  DEVICE_PRIVATE_KEY_PEM = "synthetic device private key".b
  DEVICE_EXTENDER_KEY_SEED = (32...64).to_a.pack("C*")

  TEST_CLIENT_ID = Provider::SelfTest::TEST_PROVIDER_ID
  TEST_CLIENT_JWT = Provider::SelfTest.self_test_jwt(%({"client_id":"#{TEST_CLIENT_ID}"}))

  # The C ABI functions that the provider calls, with the behavior the C ABI
  # header documents, and a log of every call. Tests drive the SDK side through
  # the accessors and trigger; callbacks run on the test thread.
  class FakeCAbi
    attr_reader :calls, :live_handles, :unfreed_strings, :listeners, :wallet_callbacks, :device_arguments
    attr_accessor :device_error, :provide_mode, :provide_paused, :provide_enabled, :provider_connected,
                  :client_limit_status_json, :packet_stats_json, :sn_wallet_json

    # A stand-in with no handles, no device and every getter at its default.
    def initialize
      @calls = []
      @next_handle = 100
      # handle => kind
      @live_handles = {}
      # address => pointer of a returned string that the caller has not freed
      @unfreed_strings = {}
      # subscription handle => [listener name, callback]
      @listeners = {}
      @wallet_callbacks = []
      @key_materials = {}
      @device_arguments = nil
      @device_error = nil
      @provide_mode = Provider::PROVIDE_MODE_NONE
      @provide_paused = false
      @provide_enabled = false
      @provider_connected = false
      @client_limit_status_json = nil
      @packet_stats_json = nil
      @sn_wallet_json = nil
    end

    # The names of the logged calls, in order.
    def call_names
      @calls.map(&:first)
    end

    # Calls every live listener of one kind, as the SDK would.
    def trigger(name, *args)
      @listeners.each_value { |listener_name, callback| callback.call(nil, *args) if listener_name == name }
    end

    def urnet_free_string(pointer)
      record(:urnet_free_string)
      @unfreed_strings.delete(pointer.address)
      nil
    end

    def urnet_release(handle)
      record(:urnet_release, handle)
      !@live_handles.delete(handle).nil?
    end

    def urnet_new_network_space_manager_no_storage
      record(:urnet_new_network_space_manager_no_storage)
      new_handle("manager")
    end

    def urnet_network_space_manager_update_network_space_values(manager, key_json, values_json)
      record(:urnet_network_space_manager_update_network_space_values, manager, key_json, values_json)
      new_handle("space")
    end

    def urnet_network_space_get_api(space)
      record(:urnet_network_space_get_api, space)
      new_handle("api")
    end

    def urnet_api_set_by_jwt(api, by_jwt)
      record(:urnet_api_set_by_jwt, api, by_jwt)
      nil
    end

    def urnet_network_space_manager_close(manager)
      record(:urnet_network_space_manager_close, manager)
      nil
    end

    def urnet_new_device_local_key_material(seed, seed_length, certificate, certificate_length, private_key, private_key_length)
      record(:urnet_new_device_local_key_material)
      handle = new_handle("key material")
      @key_materials[handle] = {
        "client_key_seed" => bytes_at(seed, seed_length),
        "provide_tls_certificate_pem" => bytes_at(certificate, certificate_length),
        "provide_tls_private_key_pem" => bytes_at(private_key, private_key_length),
        "extender_key_seed" => "".b,
      }
      handle
    end

    def urnet_device_local_key_material_set_extender_key_seed(key_material, seed, seed_length)
      record(:urnet_device_local_key_material_set_extender_key_seed, key_material)
      @key_materials[key_material]["extender_key_seed"] = bytes_at(seed, seed_length)
      nil
    end

    def urnet_new_device_local_with_provide_extender(space, by_jwt, device_description, device_spec, app_version, instance_id,
                                                     enable_rpc, key_material, provide_extender_enabled, default_provide_extender,
                                                     out_error)
      record(:urnet_new_device_local_with_provide_extender, key_material)
      @device_arguments = {
        "space" => space,
        "by_jwt" => by_jwt,
        "device_description" => device_description,
        "device_spec" => device_spec,
        "app_version" => app_version,
        "instance_id" => instance_id,
        "enable_rpc" => enable_rpc,
        # the constructor copies the key material; the caller may release it after
        "key_material" => key_material.zero? ? nil : @key_materials[key_material].dup,
        "provide_extender_enabled" => provide_extender_enabled,
        "default_provide_extender" => default_provide_extender,
      }
      unless @device_error.nil?
        out_error.write_pointer(string(@device_error))
        return 0
      end
      new_handle("device")
    end

    def urnet_device_local_get_client_key_seed(device, out, inout_length)
      record(:urnet_device_local_get_client_key_seed, device)
      copy_out(DEVICE_CLIENT_KEY_SEED, out, inout_length)
    end

    def urnet_device_local_get_provide_tls_certificate_pem(device, out, inout_length)
      record(:urnet_device_local_get_provide_tls_certificate_pem, device)
      copy_out(DEVICE_CERTIFICATE_PEM, out, inout_length)
    end

    def urnet_device_local_get_provide_tls_private_key_pem(device, out, inout_length)
      record(:urnet_device_local_get_provide_tls_private_key_pem, device)
      copy_out(DEVICE_PRIVATE_KEY_PEM, out, inout_length)
    end

    def urnet_device_local_get_extender_key_seed(device, out, inout_length)
      record(:urnet_device_local_get_extender_key_seed, device)
      copy_out(DEVICE_EXTENDER_KEY_SEED, out, inout_length)
    end

    def urnet_device_add_jwt_refresh_listener(_device, callback, _user_data)
      add_listener(:urnet_device_add_jwt_refresh_listener, callback)
    end

    def urnet_device_add_auth_logout_listener(_device, callback, _user_data)
      add_listener(:urnet_device_add_auth_logout_listener, callback)
    end

    def urnet_device_add_provider_ingress_contract_details_change_listener(_device, callback, _user_data)
      add_listener(:urnet_device_add_provider_ingress_contract_details_change_listener, callback)
    end

    def urnet_device_add_provider_egress_contract_details_change_listener(_device, callback, _user_data)
      add_listener(:urnet_device_add_provider_egress_contract_details_change_listener, callback)
    end

    def urnet_sub_close(sub)
      record(:urnet_sub_close, sub)
      @listeners.delete(sub)
      nil
    end

    def urnet_device_set_provide_mode(_device, provide_mode)
      record(:urnet_device_set_provide_mode, provide_mode)
      @provide_mode = provide_mode
      nil
    end

    def urnet_device_get_provide_mode(_device)
      record(:urnet_device_get_provide_mode)
      @provide_mode
    end

    def urnet_device_get_provide_paused(_device)
      record(:urnet_device_get_provide_paused)
      @provide_paused
    end

    def urnet_device_get_provide_enabled(_device)
      record(:urnet_device_get_provide_enabled)
      @provide_enabled
    end

    def urnet_device_local_get_provider_connected(_device)
      record(:urnet_device_local_get_provider_connected)
      @provider_connected
    end

    def urnet_device_get_client_limit_status(_device)
      record(:urnet_device_get_client_limit_status)
      string(@client_limit_status_json)
    end

    def urnet_device_get_provider_packet_stats(_device)
      record(:urnet_device_get_provider_packet_stats)
      string(@packet_stats_json)
    end

    def urnet_device_local_sync_sn_wallet(_device, callback, _user_data)
      record(:urnet_device_local_sync_sn_wallet)
      @wallet_callbacks << callback
      nil
    end

    def urnet_device_local_get_sn_wallet(_device)
      record(:urnet_device_local_get_sn_wallet)
      string(@sn_wallet_json)
    end

    def urnet_device_close(device)
      record(:urnet_device_close, device)
      nil
    end

    private

    # Logs one call.
    def record(name, *args)
      @calls << [name, args]
    end

    # A new live handle of a kind.
    def new_handle(kind)
      @next_handle += 1
      @live_handles[@next_handle] = kind
      @next_handle
    end

    # A string the caller owns and frees with urnet_free_string; NULL for nil.
    def string(text)
      return FFI::Pointer::NULL if text.nil?

      pointer = FFI::MemoryPointer.from_string(text)
      @unfreed_strings[pointer.address] = pointer
      pointer
    end

    # The bytes at a pointer argument, "" for NULL.
    def bytes_at(pointer, length)
      pointer.nil? || length.zero? ? "".b : pointer.get_bytes(0, length)
    end

    # The buffer-out pattern: the needed size always, the copy only when out
    # has the capacity.
    def copy_out(data, out, inout_length)
      capacity = inout_length.read_int32
      inout_length.write_int32(data.bytesize)
      return false if out.nil? || capacity < data.bytesize

      out.put_bytes(0, data)
      true
    end

    # A subscription handle for one listener.
    def add_listener(name, callback)
      record(name)
      sub = new_handle("sub")
      @listeners[sub] = [name, callback]
      sub
    end
  end

  # Checks every call of a FakeCAbi against the gem's FFI signature: the
  # argument count, each argument's Ruby type for its parameter type, and the
  # type of the result for the return type.
  class CheckedCAbi
    # Wraps fake with the signatures of the gem's attached functions.
    def initialize(fake, native_functions)
      @fake = fake
      @native_functions = native_functions
    end

    # The checked function name.
    def method_missing(name, *args)
      function = @native_functions.fetch(name) { return super }
      if args.length != function.param_types.length
        raise ArgumentError, "#{name} takes #{function.param_types.length} arguments, called with #{args.length}"
      end

      function.param_types.zip(args).each_with_index do |(type, arg), index|
        raise TypeError, "#{name} argument #{index}: #{arg.inspect} is not a #{type.inspect}" unless fits(type, arg)
      end
      result = @fake.public_send(name, *args)
      raise TypeError, "the stand-in #{name} returned #{result.inspect} for #{function.return_type.inspect}" unless fits(function.return_type, result)

      result
    end

    # Answers for the attached functions.
    def respond_to_missing?(name, include_private = false)
      @native_functions.key?(name) || super
    end

    private

    # Whether a Ruby value fits an FFI type as a parameter or a result.
    def fits(type, value)
      case type
      when FFI::Type::UINT64 then value.is_a?(Integer) && value >= 0 && value < 2**64
      when FFI::Type::INT64, FFI::Type::INT32 then value.is_a?(Integer)
      when FFI::Type::BOOL then [true, false].include?(value)
      when FFI::Type::STRING then value.nil? || value.is_a?(String)
      when FFI::Type::POINTER then value.nil? || value.is_a?(FFI::Pointer)
      when FFI::Type::VOID then value.nil?
      when FFI::FunctionType then value.respond_to?(:call)
      else raise TypeError, "no check for #{type.inspect}"
      end
    end
  end

  # A stand-in for the URnetwork module over fake: Raw, the Handle and Device
  # owners, and take_string.
  def fake_sdk(fake)
    raw = NATIVE_SDK.nil? ? fake : CheckedCAbi.new(fake, NATIVE_SDK::Raw.attached_functions)
    handle_class = Class.new do
      attr_reader :handle

      define_method(:initialize) do |handle, device: false|
        raise ArgumentError, "Nonzero owned handle required" if handle.zero?

        @handle = handle
        @device = device
      end
      define_method(:close) do
        return if @handle.zero?

        raw.urnet_device_close(@handle) if @device
        raw.urnet_release(@handle)
        @handle = 0
      end
    end
    device_class = Class.new(handle_class) do
      define_method(:initialize) { |handle| super(handle, device: true) }
    end
    sdk = Module.new
    sdk.const_set(:Raw, raw)
    sdk.const_set(:Handle, handle_class)
    sdk.const_set(:Device, device_class)
    sdk.define_singleton_method(:take_string) do |pointer|
      next nil if pointer.null?

      begin
        pointer.read_string.force_encoding(Encoding::UTF_8)
      ensure
        raw.urnet_free_string(pointer)
      end
    end
    sdk
  end

  # The calls that need the native library but no device, network or
  # credentials.
  class NativeLibraryTest < Minitest::Test
    def test_configure_sdk_logs_points_the_sdk_at_a_private_log_directory
      skip "the urnetwork gem and its native library do not load" if NATIVE_SDK.nil?

      Dir.mktmpdir("ur-provider-test-") do |state_dir|
        log_dir = File.join(state_dir, "logs")
        Provider::Session.configure_sdk_logs(NATIVE_SDK, log_dir)
        assert File.directory?(log_dir)
        assert_equal 0, File.stat(log_dir).mode & 0o077 if Provider::State::POSIX
      end
    end
  end

  # A provider contract, as the contract details listeners deliver it.
  def contract_details_json(source_id, destination_id)
    Provider::SelfTest.contract_json("55555555-5555-5555-5555-555555555555", source_id, destination_id, nil)
  end

  # The provider lifecycle over the C ABI stand-in.
  class ProviderSessionLifecycleTest < Minitest::Test
    def setup
      @state_dir = Dir.mktmpdir("ur-provider-test-")
      File.chmod(0o700, @state_dir) if Provider::State::POSIX
      Provider::State.write_private_file(File.join(@state_dir, Provider::State::CLIENT_JWT_FILE_NAME), "#{TEST_CLIENT_JWT}\n")
      @fake = FakeCAbi.new
      @events = Queue.new
    end

    def teardown
      FileUtils.rm_rf(@state_dir)
    end

    # A session for the test installation, over the stand-in.
    def new_session
      config = Provider::State.load_config(@state_dir)
      session = nil
      capture_io { session = Provider::Session.new(config, fake_sdk(@fake), @events) }
      session
    end

    # Closes session and returns what it printed.
    def close_session(session)
      capture_io { session.close }.first
    end

    # Runs session until a queued stop and returns what it printed.
    def run_session(session)
      capture_io { session.run }.first
    end

    # The identity stored in the test installation.
    def stored_identity
      Provider::State.load_identity(@state_dir, TEST_CLIENT_ID)
    end

    def test_first_run_creates_the_device_without_key_material_and_saves_its_identity
      session = new_session
      arguments = @fake.device_arguments
      assert_nil arguments["key_material"]
      assert_equal TEST_CLIENT_JWT, arguments["by_jwt"]
      assert_equal Provider::Session::DEVICE_DESCRIPTION, arguments["device_description"]
      assert_equal Provider::Session::DEVICE_SPEC, arguments["device_spec"]
      assert_equal "1", arguments["app_version"]
      assert_equal Provider::State.load_config(@state_dir).instance_id, arguments["instance_id"]
      assert_equal false, arguments["enable_rpc"]
      # the extender role's default stays on: both settings are passed as true
      assert_equal true, arguments["provide_extender_enabled"]
      assert_equal true, arguments["default_provide_extender"]
      expected = Provider::Identity.new(
        client_id: TEST_CLIENT_ID,
        client_key_seed: DEVICE_CLIENT_KEY_SEED,
        provide_tls_certificate_pem: DEVICE_CERTIFICATE_PEM,
        provide_tls_private_key_pem: DEVICE_PRIVATE_KEY_PEM,
        extender_key_seed: DEVICE_EXTENDER_KEY_SEED,
      )
      assert_equal expected, stored_identity
      if Provider::State::POSIX
        assert_equal 0o600, File.stat(File.join(@state_dir, Provider::State::IDENTITY_FILE_NAME)).mode & 0o777
      end
      # the listeners exist before providing starts, then the wallet is read
      names = @fake.call_names
      provide_index = names.index(:urnet_device_set_provide_mode)
      %i[
        urnet_device_add_jwt_refresh_listener
        urnet_device_add_auth_logout_listener
        urnet_device_add_provider_ingress_contract_details_change_listener
        urnet_device_add_provider_egress_contract_details_change_listener
      ].each { |listener_name| assert_operator names.index(listener_name), :<, provide_index, listener_name }
      assert_equal Provider::PROVIDE_MODE_PUBLIC, @fake.provide_mode
      assert_operator provide_index, :<, names.index(:urnet_device_local_sync_sn_wallet)
      close_session(session)
    end

    def test_later_run_passes_the_stored_identity_and_releases_the_key_material
      identity = Provider::Identity.new(
        client_id: TEST_CLIENT_ID,
        client_key_seed: ([7] * 32).pack("C*"),
        provide_tls_certificate_pem: "synthetic stored certificate".b,
        provide_tls_private_key_pem: "synthetic stored private key".b,
        extender_key_seed: ([8] * 32).pack("C*"),
      )
      Provider::State.save_identity(@state_dir, identity)
      session = new_session
      expected_key_material = {
        "client_key_seed" => identity.client_key_seed,
        "provide_tls_certificate_pem" => identity.provide_tls_certificate_pem,
        "provide_tls_private_key_pem" => identity.provide_tls_private_key_pem,
        "extender_key_seed" => identity.extender_key_seed,
      }
      assert_equal expected_key_material, @fake.device_arguments["key_material"]
      refute_includes @fake.live_handles.values, "key material"
      # the stored identity is kept, not replaced by the device's
      refute_includes @fake.call_names, :urnet_device_local_get_client_key_seed
      assert_equal identity, stored_identity
      close_session(session)
    end

    def test_another_clients_identity_is_replaced_on_first_run_of_this_client
      Provider::State.save_identity(
        @state_dir,
        Provider::Identity.new(
          client_id: Provider::SelfTest::TEST_CLIENT_A_ID,
          client_key_seed: ([7] * 32).pack("C*"),
          provide_tls_certificate_pem: "synthetic other certificate".b,
          provide_tls_private_key_pem: "synthetic other private key".b,
          extender_key_seed: "".b,
        ),
      )
      session = new_session
      assert_nil @fake.device_arguments["key_material"]
      assert_equal DEVICE_CLIENT_KEY_SEED, stored_identity.client_key_seed
      close_session(session)
    end

    def test_status_reads_the_device_getters_and_frees_every_string
      session = new_session
      # null client limit status and packet stats: no limit and 0 bytes
      assert_equal "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking", session.status.line
      @fake.provide_enabled = true
      @fake.provider_connected = true
      @fake.packet_stats_json = JSON.generate("RemoteEgressByteCount" => 13_002_342 - 1000, "RemoteIngressByteCount" => 1000)
      client_a = Provider::SelfTest::TEST_CLIENT_A_ID
      client_b = Provider::SelfTest::TEST_CLIENT_B_ID
      @fake.trigger(:urnet_device_add_provider_ingress_contract_details_change_listener, contract_details_json(client_a, TEST_CLIENT_ID))
      @fake.trigger(:urnet_device_add_provider_egress_contract_details_change_listener, contract_details_json(TEST_CLIENT_ID, client_a))
      @fake.trigger(:urnet_device_add_provider_ingress_contract_details_change_listener, contract_details_json(client_b, TEST_CLIENT_ID))
      assert_equal "status: providing | clients served: 2 | data provided: 12.4 MiB | payout wallet: checking", session.status.line
      @fake.client_limit_status_json = JSON.generate("Status" => "client_limit_exceeded", "RetryTime" => 1_791_313_440_001)
      assert_equal "status: client limit, retry at 19:05 UTC | clients served: 2 | data provided: 12.4 MiB | payout wallet: checking",
                   session.status.line
      @fake.provide_mode = Provider::PROVIDE_MODE_NONE
      assert_equal "stopped", session.status.state
      close_session(session)
      assert_empty @fake.unfreed_strings
    end

    def test_wallet_reads_label_the_wallet_and_a_later_failure_keeps_it
      session = new_session
      wallet_callback = @fake.wallet_callbacks.first
      wallet = Provider::SelfTest::TEST_WALLET
      read_wallet = lambda do |result_json, error_text|
        wallet_callback.call(nil, result_json, error_text)
        @events.push([Provider::Session::EVENT_STOP])
        run_session(session)
        [session.status.payout_wallet, session.status.payout_wallet_scope]
      end
      # the first read fails
      assert_equal ["unavailable", ""], read_wallet.call("null", "synthetic network failure")
      # a read with a network consent
      @fake.sn_wallet_json = JSON.generate("coldkey_ss58" => wallet, "set_at_millis" => 1, "consent_scope" => "network")
      assert_equal [wallet, "network"], read_wallet.call(JSON.generate("wallet" => JSON.parse(@fake.sn_wallet_json)), nil)
      assert_includes session.status.line, "payout wallet: #{wallet} (network)"
      # a later failure keeps the wallet, also for an empty error object
      assert_equal [wallet, "network"], read_wallet.call('{"error": {"message": "synthetic refusal"}}', nil)
      assert_equal [wallet, "network"], read_wallet.call('{"error": {}}', nil)
      # a hotkey delegation is labeled by its scope, and this client's own consent wins
      @fake.sn_wallet_json = JSON.generate("coldkey_ss58" => wallet, "set_at_millis" => 1, "consent_scope" => "hotkey")
      assert_equal [wallet, "hotkey"], read_wallet.call("{}", nil)
      @fake.sn_wallet_json = JSON.generate("coldkey_ss58" => wallet, "client_id" => TEST_CLIENT_ID, "set_at_millis" => 1, "consent_scope" => "provider")
      assert_equal [wallet, "this provider"], read_wallet.call("{}", nil)
      # no mapped wallet
      @fake.sn_wallet_json = nil
      assert_equal ["not set", ""], read_wallet.call("{}", nil)
      close_session(session)
    end

    def test_an_empty_error_object_is_a_failed_first_wallet_read
      session = new_session
      @fake.sn_wallet_json = JSON.generate("coldkey_ss58" => Provider::SelfTest::TEST_WALLET, "set_at_millis" => 1)
      @fake.wallet_callbacks.first.call(nil, '{"error": {}}', nil)
      @events.push([Provider::Session::EVENT_STOP])
      run_session(session)
      assert_equal "unavailable", session.status.payout_wallet
      close_session(session)
    end

    def test_run_saves_a_refreshed_jwt_and_prints_the_status
      session = new_session
      @fake.trigger(:urnet_device_add_jwt_refresh_listener, "synthetic.refreshed.jwt")
      @events.push([Provider::Session::EVENT_STOP])
      assert_equal "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking\n", run_session(session)
      client_jwt_path = File.join(@state_dir, Provider::State::CLIENT_JWT_FILE_NAME)
      assert_equal "synthetic.refreshed.jwt\n", File.binread(client_jwt_path)
      assert_equal 0o600, File.stat(client_jwt_path).mode & 0o777 if Provider::State::POSIX
      close_session(session)
    end

    def test_auth_logout_ends_the_run_with_a_credential_error
      session = new_session
      @fake.trigger(:urnet_device_add_auth_logout_listener)
      assert_raises(Provider::ConfigError) { run_session(session) }
      close_session(session)
    end

    def test_close_stops_providing_then_closes_subscriptions_device_and_manager
      session = new_session
      @fake.calls.clear
      # a refresh that arrives after the run loop's last read is still saved
      @fake.trigger(:urnet_device_add_jwt_refresh_listener, "synthetic.late.jwt")
      assert_equal "status: stopped\n", close_session(session)
      assert_equal "synthetic.late.jwt\n", File.binread(File.join(@state_dir, Provider::State::CLIENT_JWT_FILE_NAME))
      names = @fake.call_names
      assert_equal :urnet_device_set_provide_mode, names[0]
      assert_equal Provider::PROVIDE_MODE_NONE, @fake.provide_mode
      assert_equal %i[urnet_sub_close urnet_release] * 4, names[1, 8]
      expected_tail = %i[
        urnet_device_close
        urnet_release
        urnet_release
        urnet_release
        urnet_network_space_manager_close
        urnet_release
      ]
      assert_equal expected_tail, names[9..]
      assert_empty @fake.listeners
      assert_empty @fake.live_handles
      # closing again does nothing
      @fake.calls.clear
      close_session(session)
      assert_empty @fake.calls
    end

    def test_device_error_closes_the_manager_and_releases_every_handle
      @fake.device_error = "synthetic device error"
      error = assert_raises(RuntimeError) { new_session }
      assert_equal "synthetic device error", error.message
      assert_includes @fake.call_names, :urnet_network_space_manager_close
      assert_empty @fake.live_handles
      assert_empty @fake.unfreed_strings
      refute File.exist?(File.join(@state_dir, Provider::State::IDENTITY_FILE_NAME))
    end
  end
end
