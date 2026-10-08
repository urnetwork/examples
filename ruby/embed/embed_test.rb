# frozen_string_literal: true

# Tests for the embed example: each self-test check as a test, so minitest
# and --self-test cover the same behavior; the cap reader thread; the
# Net::HTTP transport against a loopback stand-in for the token server and the
# API; and the device lifecycle (session.rb) against a stand-in for the SDK's
# C ABI. The C ABI stand-in follows the C ABI's documented behavior for
# handles, returned strings and callbacks, without a device, network or
# credentials. With the ffi gem the stand-in returns real FFI pointers;
# without it the tests supply the two FFI pieces session.rb uses. When the
# gem's bindings can be found (the installed urnetwork-sdk gem, or
# URNETWORK_SDK_RUBY_DIR naming an sdk checkout's sdk/ruby), every C ABI call
# is also checked against the gem's attach_function declarations.
#
# Run from this directory: ruby embed_test.rb, or bundle exec ruby embed_test.rb

require "minitest/autorun"
require "fileutils"
require "json"
require "socket"
require "tmpdir"
require_relative "cap_reader"
require_relative "commands"
require_relative "selftest"

FFI_LOADED =
  begin
    require "ffi"
    true
  rescue LoadError
    false
  end

unless FFI_LOADED
  # The two FFI pieces session.rb uses, so the lifecycle tests run without the
  # ffi gem: an out-parameter the stand-in C ABI writes and the session reads.
  module FFI
    class MemoryPointer
      def initialize(_type, _count = 1)
        @value = nil
      end

      def write_pointer(value)
        @value = value
      end

      def read_pointer
        @value
      end
    end
  end
  # session.rb's require "ffi" is satisfied by the pieces above
  $LOADED_FEATURES << "ffi.rb"
end

require_relative "session"

# The gem's C ABI declarations, parsed from its raw.rb: name -> [argument
# types, return type]; nil when the bindings cannot be found.
module Bindings
  ATTACH_PATTERN = /attach_function :(\w+), \[([^\]]*)\], :(\w+)/.freeze

  def self.raw_rb_path
    candidates = []
    dir = ENV.fetch("URNETWORK_SDK_RUBY_DIR", nil)
    candidates << File.join(dir, "lib", "urnetwork", "raw.rb") if dir
    begin
      candidates << File.join(Gem::Specification.find_by_name("urnetwork-sdk").gem_dir, "lib", "urnetwork", "raw.rb")
    rescue Gem::MissingSpecError
      nil
    end
    candidates.find { |path| File.file?(path) }
  end

  def self.load
    path = raw_rb_path
    return nil if path.nil?

    File.read(path).scan(ATTACH_PATTERN).to_h do |name, arguments, result|
      [name.to_sym, [arguments.scan(/:(\w+)/).flatten.map(&:to_sym), result.to_sym]]
    end
  end

  SIGNATURES = load
end

# Strings the stand-in C ABI returns: real FFI pointers with the ffi gem,
# plain Ruby strings without it.
module TestPointers
  KEEP = []

  def self.string(text)
    return (FFI_LOADED ? FFI::Pointer::NULL : nil) if text.nil?
    return text unless FFI_LOADED

    pointer = FFI::MemoryPointer.from_string(text)
    KEEP << pointer
    pointer
  end

  def self.read(pointer)
    return nil if pointer.nil?
    return pointer if pointer.is_a?(String)

    pointer.null? ? nil : pointer.read_string
  end

  def self.pointer?(value)
    value.nil? || value.is_a?(String) || value.is_a?(FFI::MemoryPointer) || (FFI_LOADED && value.is_a?(FFI::Pointer))
  end
end

# The C ABI functions that the embed app calls, with the behavior the C ABI
# header documents, and a log of every call. Tests drive the SDK side through
# the fields and trigger; callbacks run on the test thread.
class FakeCAbi
  attr_reader :calls, :live_handles, :listeners, :unfreed_strings, :device_arguments, :connect_location_json
  attr_accessor :device_error, :client_limit_status_json, :window_status_json

  def initialize
    @calls = []
    @next_handle = 100
    @live_handles = {}
    @listeners = {}
    @unfreed_strings = {}
    @device_arguments = nil
    @device_error = nil
    @connect_location_json = nil
    @client_limit_status_json = nil
    @window_status_json = nil
  end

  def call_names
    @calls.map(&:first)
  end

  # Calls every live listener of one kind, as the SDK would.
  def trigger(name, *args)
    @listeners.values.each { |listener_name, callback| callback.call(nil, *args) if listener_name == name }
  end

  def urnet_free_string(pointer)
    record(:urnet_free_string)
    @unfreed_strings.delete(pointer.object_id)
    nil
  end

  def urnet_release(handle)
    record(:urnet_release, handle)
    !@live_handles.delete(handle).nil?
  end

  def urnet_set_log_dir(log_dir, _out_error)
    record(:urnet_set_log_dir, log_dir)
    true
  end

  def urnet_new_network_space_manager_no_storage
    record(:urnet_new_network_space_manager_no_storage)
    new_handle(:manager)
  end

  def urnet_network_space_manager_update_network_space_values(manager, key_json, values_json)
    record(:urnet_network_space_manager_update_network_space_values, manager, key_json, values_json)
    new_handle(:space)
  end

  def urnet_network_space_get_api(space)
    record(:urnet_network_space_get_api, space)
    new_handle(:api)
  end

  def urnet_api_set_by_jwt(api, by_jwt)
    record(:urnet_api_set_by_jwt, api, by_jwt)
    nil
  end

  def urnet_network_space_manager_close(manager)
    record(:urnet_network_space_manager_close, manager)
    nil
  end

  def urnet_new_device_local_with_defaults(space, by_jwt, device_description, device_spec, app_version, instance_id, enable_rpc, out_error)
    record(:urnet_new_device_local_with_defaults)
    @device_arguments = {
      space: space, by_jwt: by_jwt, device_description: device_description, device_spec: device_spec,
      app_version: app_version, instance_id: instance_id, enable_rpc: enable_rpc,
    }
    unless @device_error.nil?
      out_error.write_pointer(returned_string(@device_error))
      return 0
    end
    new_handle(:device)
  end

  def urnet_device_add_jwt_refresh_listener(_device, callback, _user_data)
    add_listener(:urnet_device_add_jwt_refresh_listener, callback)
  end

  def urnet_device_add_auth_logout_listener(_device, callback, _user_data)
    add_listener(:urnet_device_add_auth_logout_listener, callback)
  end

  def urnet_device_add_contract_status_change_listener(_device, callback, _user_data)
    add_listener(:urnet_device_add_contract_status_change_listener, callback)
  end

  def urnet_sub_close(sub)
    record(:urnet_sub_close, sub)
    @listeners.delete(sub)
    nil
  end

  def urnet_device_set_connect_location(_device, location_json)
    record(:urnet_device_set_connect_location, location_json)
    @connect_location_json = location_json
    nil
  end

  def urnet_device_get_client_limit_status(_device)
    record(:urnet_device_get_client_limit_status)
    returned_string(@client_limit_status_json)
  end

  def urnet_device_get_window_status(_device)
    record(:urnet_device_get_window_status)
    returned_string(@window_status_json)
  end

  def urnet_device_close(device)
    record(:urnet_device_close, device)
    nil
  end

  private

  def record(name, *args)
    @calls << [name, args]
  end

  def new_handle(kind)
    @next_handle += 1
    @live_handles[@next_handle] = kind
    @next_handle
  end

  # A string the caller owns and frees with urnet_free_string; NULL for nil.
  def returned_string(text)
    pointer = TestPointers.string(text)
    @unfreed_strings[pointer.object_id] = pointer unless text.nil?
    pointer
  end

  def add_listener(name, callback)
    record(name)
    sub = new_handle(:sub)
    @listeners[sub] = [name, callback]
    sub
  end
end

# Checks every call of a FakeCAbi against the gem's declaration: the argument
# count, each argument's Ruby type for its FFI type, and the result's type.
class CheckedCAbi
  def initialize(fake, signatures)
    @fake = fake
    @signatures = signatures
  end

  def respond_to_missing?(name, include_private = false)
    @fake.respond_to?(name) || super
  end

  def method_missing(name, *args, &block)
    signature = @signatures[name]
    raise NoMethodError, "the urnetwork gem has no #{name}" if signature.nil?

    argument_types, result_type = signature
    raise ArgumentError, "#{name} takes #{argument_types.length} arguments, called with #{args.length}" if argument_types.length != args.length

    argument_types.zip(args).each_with_index do |(type, arg), index|
      raise TypeError, "#{name} argument #{index} (#{type}) got #{arg.inspect}" unless conforms?(type, arg)
    end
    result = @fake.public_send(name, *args, &block)
    raise TypeError, "the stand-in #{name} returned #{result.inspect} for #{result_type}" unless conforms?(result_type, result)

    result
  end

  private

  def conforms?(type, value)
    case type
    when :void then value.nil?
    when :bool then [true, false].include?(value)
    when :string then value.nil? || value.is_a?(String)
    when :pointer then TestPointers.pointer?(value)
    when :uint8, :uint16, :uint32, :uint64, :int8, :int16, :int32, :int64, :int, :long, :size_t then value.is_a?(Integer)
    else type.to_s.end_with?("_cb") ? value.is_a?(Proc) : true
    end
  end
end

# A stand-in for the URnetwork module over raw: Raw, the Handle and Device
# owners, and take_string.
def fake_sdk(fake)
  raw = Bindings::SIGNATURES.nil? ? fake : CheckedCAbi.new(fake, Bindings::SIGNATURES)
  handle_class = Class.new do
    define_method(:initialize) do |handle, device: false|
      raise ArgumentError, "Nonzero owned handle required" if handle.zero?

      @handle = handle
      @device = device
    end
    define_method(:handle) { @handle }
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
  Module.new.tap do |sdk|
    sdk.const_set(:Raw, raw)
    sdk.const_set(:Handle, handle_class)
    sdk.const_set(:Device, device_class)
    sdk.define_singleton_method(:take_string) do |pointer|
      text = TestPointers.read(pointer)
      raw.urnet_free_string(pointer) unless text.nil?
      text
    end
  end
end

# A cap reader that runs no thread: it records how the session drives it.
class RecordingCapReader
  INSTANCES = []
  attr_reader :read, :started_with, :wakes

  def initialize(read, _events)
    @read = read
    @started_with = nil
    @wakes = 0
    @stopped = false
    INSTANCES << self
  end

  def start(read_now)
    @started_with = read_now
  end

  def wake
    @wakes += 1
  end

  def stop
    @stopped = true
  end

  def stopped?
    @stopped
  end
end

# Each self-test check as a test.
class SelfTestChecks < Minitest::Test
  Embed::SelfTest::CHECKS.each do |check|
    define_method("test_#{check.to_s.delete_prefix('check_')}") do
      Embed::SelfTest.public_send(check)
      pass
    end
  end
end

# The real cap reader: a read at start, then a read when woken.
class CapReaderThreadTest < Minitest::Test
  def test_reads_at_start_and_when_woken
    events = Queue.new
    readings = [Embed::SelfTest.cap_reading(monthly_byte_limit: 1), Embed::SelfTest.cap_reading(monthly_byte_limit: 2)]
    reader = Embed::CapReader.new(-> { readings.shift }, events, 3600)
    reader.start(true)
    first = events.pop(timeout: 5)
    assert_equal [Embed::EVENT_CAPS_READ, 1], [first[0], first[1].monthly_byte_limit]
    reader.wake
    second = events.pop(timeout: 5)
    assert_equal 2, second[1].monthly_byte_limit
  ensure
    reader&.stop
  end

  def test_a_failing_read_reports_a_failure
    events = Queue.new
    reader = Embed::CapReader.new(-> { raise "boom" }, events, 3600)
    reader.start(true)
    assert_equal [Embed::EVENT_CAPS_READ, nil], events.pop(timeout: 5)
  ensure
    reader&.stop
  end
end

# A stand-in token server and API on 127.0.0.1, answering each request from a
# queue of [status, JSON value] answers and logging the requests.
class LoopbackServer
  attr_reader :origin, :requests

  def initialize(*answers)
    @answers = answers
    @requests = []
    @server = TCPServer.new("127.0.0.1", 0)
    @origin = "http://127.0.0.1:#{@server.addr[1]}"
    @thread = Thread.new { serve }
  end

  def close
    @server.close
    @thread.join(5)
  end

  private

  def serve
    loop do
      client = @server.accept
      answer_one(client)
    end
  rescue IOError, SystemCallError
    nil
  end

  def answer_one(client)
    method, path = client.gets.split(" ")
    headers = {}
    while (line = client.gets) && line != "\r\n"
      name, value = line.split(":", 2)
      headers[name.downcase] = value.strip
    end
    body = headers.key?("content-length") ? client.read(headers["content-length"].to_i) : ""
    @requests << {method: method, path: path, authorization: headers["authorization"], body: body}
    status, value = @answers.shift
    data = JSON.generate(value)
    client.write("HTTP/1.1 #{status} Stand-in\r\nContent-Type: application/json\r\nContent-Length: #{data.bytesize}\r\n" \
                 "Cache-Control: no-store\r\nConnection: close\r\n\r\n#{data}")
  ensure
    client.close
  end
end

# The real Net::HTTP transport against a loopback stand-in.
class TransportOverHttpTest < Minitest::Test
  def test_token_fetch_and_cap_read
    server = LoopbackServer.new([200, Embed::SelfTest.token_answer],
                                [200, {"client_id" => Embed::SelfTest::TEST_CLIENT_ID, "total_byte_limit" => 5, "total_used_byte_count" => 2}])
    Embed::SelfTest.private_dir do |state_dir|
      fetched = Embed::ClientToken.fetch_client_jwt(state_dir, Embed::SelfTest::TEST_INSTANCE_ID, server.origin,
                                                     Embed::SelfTest::TEST_SESSION, Embed::Transport::NET_HTTP)
      assert_equal Embed::SelfTest::TEST_CLIENT_ID, fetched.client_id
      reading = Embed::Caps.read_own_caps(server.origin, fetched.client_jwt, Embed::Transport::NET_HTTP)
      assert_equal [5, 2], [reading.total_byte_limit, reading.total_used_byte_count]
    end
    token_request, cap_request = server.requests
    assert_equal ["POST", "/urnetwork/client-token"], [token_request[:method], token_request[:path]]
    assert_equal "Bearer #{Embed::SelfTest::TEST_SESSION}", token_request[:authorization]
    assert_equal({"installation_id" => Embed::SelfTest::TEST_INSTANCE_ID}, JSON.parse(token_request[:body]))
    assert_equal ["GET", "/network/client-data-cap"], [cap_request[:method], cap_request[:path]]
    assert_equal "Bearer #{Embed::SelfTest.test_client_jwt}", cap_request[:authorization]
  ensure
    server&.close
  end

  def test_error_status_keeps_its_answer
    server = LoopbackServer.new([401, {"error" => {"code" => "unauthorized", "message" => "unknown session"}}])
    Embed::SelfTest.private_dir do |state_dir|
      error = assert_raises(Embed::TokenServerRefused) do
        Embed::ClientToken.fetch_client_jwt(state_dir, Embed::SelfTest::TEST_INSTANCE_ID, server.origin,
                                            Embed::SelfTest::TEST_SESSION, Embed::Transport::NET_HTTP)
      end
      assert_equal "unknown session", error.message
    end
  ensure
    server&.close
  end

  def test_cap_routes_missing_is_a_failed_read
    server = LoopbackServer.new([404, {"error" => {"message" => "not found"}}])
    assert_nil Embed::Caps.read_own_caps(server.origin, Embed::SelfTest.test_client_jwt, Embed::Transport::NET_HTTP)
  ensure
    server&.close
  end

  def test_unreachable_server_is_a_transport_error
    port = TCPServer.new("127.0.0.1", 0).then { |socket| socket.addr[1].tap { socket.close } }
    assert_raises(Embed::TransportError) { Embed::Transport.net_http("GET", "http://127.0.0.1:#{port}/", {}, nil) }
  end
end

# The device lifecycle against the C ABI stand-in.
class SessionLifecycleTest < Minitest::Test
  TEST_CLIENT_ID = Embed::SelfTest::TEST_CLIENT_ID

  def setup
    @state_dir = Dir.mktmpdir("ur-embed-test-")
    File.chmod(0o700, @state_dir)
    @fake = FakeCAbi.new
    @events = Queue.new
    @refreshed_jwt = Embed::SelfTest.self_test_jwt(%({"client_id":"#{TEST_CLIENT_ID}","exp":2000000000}))
    RecordingCapReader::INSTANCES.clear
  end

  def teardown
    FileUtils.rm_rf(@state_dir)
  end

  def start(first_cap_reading: nil, transport: Embed::SelfTest::StandInServer.new)
    config = Embed::Config.new(
      state_dir: @state_dir,
      client_jwt: Embed::SelfTest.test_client_jwt,
      client_id: TEST_CLIENT_ID,
      instance_id: Embed::SelfTest::TEST_INSTANCE_ID,
      api_origin: Embed::SelfTest::TEST_API,
      first_cap_reading: first_cap_reading,
    )
    Embed::Session.new(config, fake_sdk(@fake), @events, transport, cap_reader_class: RecordingCapReader)
  end

  def saved_client_jwt
    Embed::State.read_private_file(File.join(@state_dir, Embed::State::CLIENT_JWT_FILE_NAME))
  end

  def test_start_creates_the_device_and_listeners
    session = start
    names = @fake.call_names
    assert_equal %i[urnet_new_network_space_manager_no_storage urnet_network_space_manager_update_network_space_values
                    urnet_network_space_get_api urnet_api_set_by_jwt], names[0, 4]
    assert_equal ['{"host_name":"ur.network","env_name":"main"}', '{"migration_host_name":"bringyour.com"}'], @fake.calls[1][1][1, 2]
    assert_equal Embed::SelfTest.test_client_jwt, @fake.calls[3][1][1]
    arguments = @fake.device_arguments.dup
    space = arguments.delete(:space)
    assert_equal :space, @fake.live_handles[space]
    assert_equal({by_jwt: Embed::SelfTest.test_client_jwt, device_description: Embed::Session::DEVICE_DESCRIPTION,
                  device_spec: Embed::Session::DEVICE_SPEC, app_version: "1", instance_id: Embed::SelfTest::TEST_INSTANCE_ID,
                  enable_rpc: false}, arguments)
    %i[urnet_device_add_jwt_refresh_listener urnet_device_add_auth_logout_listener urnet_device_add_contract_status_change_listener].each do |listener|
      assert_includes names, listener
    end
    assert_equal({"connect_location_id" => {"best_available" => true}}, JSON.parse(@fake.connect_location_json))
    # no first reading: the caps are read at start
    assert RecordingCapReader::INSTANCES[0].started_with
    session.close
  end

  def test_first_cap_reading_from_the_token_server
    @fake.window_status_json = '{"ProviderStateAdded":1}'
    session = start(first_cap_reading: Embed::SelfTest.cap_reading(monthly_byte_limit: 5_000_000_000, monthly_used_byte_count: 1_234_567_890))
    refute RecordingCapReader::INSTANCES[0].started_with
    assert_equal "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap", session.status.line
    session.close
  end

  def test_status_from_the_device_getters
    session = start
    assert_equal "status: connecting | data this month: checking | data total: checking", session.status.line
    @fake.window_status_json = '{"ProviderStateAdded":2}'
    session.apply_event([Embed::EVENT_CAPS_READ, nil])
    assert_equal "status: connected | data this month: unavailable | data total: unavailable", session.status.line
    @fake.client_limit_status_json = %({"Status":"client_limit_exceeded","RetryTime":#{Embed::SelfTest::TEST_RETRY_TIME}})
    session.apply_event([Embed::EVENT_CAPS_READ, Embed::SelfTest.cap_reading(monthly_byte_limit: 5_000_000_000)])
    assert_equal "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap", session.status.line
    # every returned string was freed
    assert_empty @fake.unfreed_strings
    session.close
  end

  def test_caps_read_uses_the_latest_client_jwt
    server = Embed::SelfTest::StandInServer.new([200, {"client_id" => TEST_CLIENT_ID, "monthly_byte_limit" => 9}])
    session = start(transport: server)
    reader = RecordingCapReader::INSTANCES[0]
    @fake.trigger(:urnet_device_add_jwt_refresh_listener, @refreshed_jwt)
    refute session.apply_event(@events.pop(timeout: 0))
    assert_equal 9, reader.read.call.monthly_byte_limit
    assert_equal "Bearer #{@refreshed_jwt}", server.requests[0][:headers]["Authorization"]
    assert_equal "#{@refreshed_jwt}\n", saved_client_jwt
    session.close
  end

  def test_contract_status_change_reads_the_caps
    session = start
    @fake.trigger(:urnet_device_add_contract_status_change_listener, '{"InsufficientBalance":false}')
    event = @events.pop(timeout: 0)
    assert_equal [Embed::EVENT_CONTRACT_STATUS_CHANGED], event
    session.apply_event(event)
    assert_equal 1, RecordingCapReader::INSTANCES[0].wakes
    session.close
  end

  def test_auth_logout_ends_the_run_with_a_config_error
    session = start
    @fake.trigger(:urnet_device_add_auth_logout_listener)
    event = @events.pop(timeout: 0)
    assert_equal [Embed::EVENT_AUTH_LOGOUT], event
    assert_raises(Embed::ConfigError) { session.apply_event(event) }
    session.close
  end

  def test_stop_and_close_order
    session = start
    assert session.apply_event([Embed::EVENT_STOP])
    # a refresh that arrives after the run loop's last read is still saved
    @fake.trigger(:urnet_device_add_jwt_refresh_listener, @refreshed_jwt)
    @fake.calls.clear
    session.close
    names = @fake.call_names
    assert_equal %i[urnet_sub_close urnet_release] * 3, names[0, 6]
    assert_operator names.index(:urnet_device_close), :<, names.index(:urnet_network_space_manager_close)
    assert_empty @fake.live_handles
    assert_empty @fake.listeners
    assert RecordingCapReader::INSTANCES[0].stopped?
    assert_equal "#{@refreshed_jwt}\n", saved_client_jwt
    session.close
  end

  def test_device_error_closes_what_exists
    @fake.device_error = "invalid jwt"
    error = assert_raises(RuntimeError) { start }
    assert_equal "invalid jwt", error.message
    assert_empty @fake.live_handles
    assert_includes @fake.call_names, :urnet_network_space_manager_close
    assert_empty @fake.unfreed_strings
  end

  def test_sdk_logs
    log_dir = File.join(@state_dir, "logs")
    Embed::Session.configure_sdk_logs(fake_sdk(@fake), log_dir)
    assert File.directory?(log_dir)
    assert_equal [:urnet_set_log_dir, [log_dir]], @fake.calls.last
  end
end

# The functions and callback types the app uses exist in the gem's bindings;
# SessionLifecycleTest checks every call's types against them.
class GemSignaturesTest < Minitest::Test
  def test_functions_exist
    skip "the urnetwork gem's bindings are not found (install the gem or set URNETWORK_SDK_RUBY_DIR)" if Bindings::SIGNATURES.nil?

    %i[
      urnet_set_log_dir urnet_new_network_space_manager_no_storage urnet_network_space_manager_update_network_space_values
      urnet_network_space_get_api urnet_api_set_by_jwt urnet_new_device_local_with_defaults urnet_device_add_jwt_refresh_listener
      urnet_device_add_auth_logout_listener urnet_device_add_contract_status_change_listener urnet_device_set_connect_location
      urnet_device_get_client_limit_status urnet_device_get_window_status urnet_sub_close urnet_release urnet_device_close
      urnet_network_space_manager_close urnet_free_string urnet_get_licenses
    ].each { |name| assert Bindings::SIGNATURES.key?(name), name.to_s }
    assert_equal :urnet_contract_status_change_cb, Bindings::SIGNATURES[:urnet_device_add_contract_status_change_listener][0][1]
  end
end
