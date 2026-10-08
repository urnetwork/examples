# frozen_string_literal: true

# The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks
# the golden vectors and status rules, the data fields, the cap object, the
# JWT claim, the token fetch against a stand-in server, the installation
# state files and the configuration exit codes without a network,
# credentials, a device or the native SDK. `main.rb --self-test` runs every
# check; embed_test.rb runs each one as a test.

require "fileutils"
require "json"
require "stringio"
require "tmpdir"
require_relative "commands"

module Embed
  # The self-test checks.
  module SelfTest
    # A self-test check that failed.
    class Failure < StandardError; end

    TEST_CLIENT_ID = "11111111-1111-1111-1111-111111111111"
    TEST_OTHER_CLIENT_ID = "22222222-2222-2222-2222-222222222222"
    TEST_INSTANCE_ID = "33333333-3333-3333-3333-333333333333"
    TEST_TOKEN_SERVER = "http://127.0.0.1:8790"
    TEST_API = "http://127.0.0.1:8791"
    # a stand-in demo session; real sessions come from your service's sign-in
    TEST_SESSION = "self-test-demo-session-0123456789abcdef"

    # 2026-10-06 19:05:00.000 UTC
    TEST_RETRY_TIME = 1_791_313_500_000

    # the settings that run reads
    EMBED_SETTINGS = %w[URNETWORK_EMBED_STATE_DIR URNETWORK_TOKEN_SERVER_URL URNETWORK_DEMO_SESSION URNETWORK_API_URL].freeze

    CHECKS = %i[
      check_byte_vectors
      check_reset_vectors
      check_client_limit_vectors
      check_status_lines
      check_status_rules
      check_data_fields
      check_cap_parsing
      check_sdk_status_json
      check_client_jwt_claims
      check_origins
      check_token_fetch
      check_state_files
      check_config_errors
    ].freeze

    # A stand-in for the token server and the API: a transport callable that
    # logs each request and answers from a queue of [status, body] answers. An
    # answer that is a TransportError is raised, as an unreachable server.
    class StandInServer
      attr_reader :requests

      def initialize(*answers)
        @answers = answers
        @requests = []
      end

      def call(method, url, headers, body)
        @requests << {method: method, url: url, headers: headers.dup, body: body}
        answer = @answers.shift
        raise Failure, "unexpected request #{method} #{url}" if answer.nil?
        raise answer if answer.is_a?(Exception)

        status, value = answer
        [status, value.is_a?(String) ? value : JSON.generate(value)]
      end
    end

    module_function

    # Runs every check and raises the first failure.
    def run
      CHECKS.each { |check| public_send(check) }
    end

    # An unsigned JWT with payload_json as its claims, for the claim checks only.
    def self_test_jwt(payload_json)
      segment = ->(text) { [text].pack("m0").tr("+/", "-_").delete("=") }
      "#{segment.call('{"alg":"none"}')}.#{segment.call(payload_json)}.c2lnbmF0dXJl"
    end

    def test_client_jwt
      self_test_jwt(%({"client_id":"#{TEST_CLIENT_ID}"}))
    end

    # A cap reading from cap object fields, through the real parser.
    def cap_reading(**fields)
      reading = Caps.parse_cap_object({"client_id" => TEST_CLIENT_ID}.merge(fields.transform_keys(&:to_s)))
      raise Failure, "the self-test's own cap object does not parse: #{fields}" if reading.nil?

      reading
    end

    def expect(condition, message)
      raise Failure, message unless condition
    end

    # Raises Failure unless the block raises error_class.
    def expect_raises(error_class, message)
      yield
    rescue error_class
      nil
    rescue StandardError => error
      raise Failure, "#{message}: raised #{error.class} instead"
    else
      raise Failure, message
    end

    # A private temporary directory (0700), removed afterwards.
    def private_dir
      Dir.mktmpdir("ur-embed-self-test-") do |directory|
        File.chmod(0o700, directory) if State::POSIX
        yield directory
      end
    end

    # The embed settings set to values, and the other embed settings unset,
    # for the duration; the previous environment is restored afterwards.
    def embed_environment(values)
      saved = EMBED_SETTINGS.to_h { |name| [name, ENV.fetch(name, nil)] }
      EMBED_SETTINGS.each { |name| ENV.delete(name) }
      values.each { |name, value| ENV[name] = value }
      yield
    ensure
      saved.each { |name, value| value.nil? ? ENV.delete(name) : ENV[name] = value }
    end

    # Runs the block with $stdout and $stderr captured; returns the block's
    # value and the captured text.
    def captured
      out = StringIO.new
      saved_out, saved_err = $stdout, $stderr
      $stdout = out
      $stderr = out
      [yield, out.string]
    ensure
      $stdout, $stderr = saved_out, saved_err
    end

    # A token server success answer.
    def token_answer(client_id: TEST_CLIENT_ID, client_jwt: test_client_jwt, data_cap: :default)
      data_cap = {"client_id" => client_id, "monthly_byte_limit" => 10_000_000_000, "monthly_used_byte_count" => 0} if data_cap == :default
      {"client_id" => client_id, "by_client_jwt" => client_jwt, "data_cap" => data_cap}
    end

    # Data amounts use decimal units, one decimal, ties to even.
    def check_byte_vectors
      [
        [0, "0 B"],
        [999, "999 B"],
        [1000, "1.0 kB"],
        [999_949, "999.9 kB"],
        # exact halves round to even, as Go's %.1f does
        [1250, "1.2 kB"],
        [1750, "1.8 kB"],
        # 1.05 is just above 1.05 in binary, so Go's %.1f rounds it up; Ruby's
        # format("%.1f") gets 1.0, which is why the rounding is exact here
        [1050, "1.1 kB"],
        # rounds to 1000.0 kB, so it moves to the next unit
        [999_999, "1.0 MB"],
        [1_234_567_890, "1.2 GB"],
        [5_000_000_000, "5.0 GB"],
        [10_000_000_000, "10.0 GB"],
        [3_000_000_000_000, "3.0 TB"],
      ].each do |byte_count, text|
        actual = Embed.format_byte_count(byte_count)
        expect(actual == text, "#{byte_count} bytes format as #{actual.inspect}, want #{text.inspect}")
      end
    end

    # The monthly reset time is in UTC with the seconds rounded up; text that
    # does not parse has no reset time.
    def check_reset_vectors
      [
        ["2026-11-01T00:00:00Z", "resets 2026-11-01 00:00 UTC"],
        # rounds up to the next whole minute
        ["2026-10-31T23:59:00.001Z", "resets 2026-11-01 00:00 UTC"],
        # converted to UTC
        ["2026-10-31T19:00:00-05:00", "resets 2026-11-01 00:00 UTC"],
        ["2026-10-31T23:59:59.999999999Z", "resets 2026-11-01 00:00 UTC"],
        # rounding up rolls over the day and the year
        ["2026-12-31T23:59:30+00:00", "resets 2027-01-01 00:00 UTC"],
      ].each do |period_end, text|
        actual = Embed.reset_time_text(period_end)
        expect(actual == text, "#{period_end.inspect} resets as #{actual.inspect}, want #{text.inspect}")
      end
      ["", "2026-11-01", "not a time", "2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2026-11-01T00:00:00"].each do |unparsed|
        expect(Embed.reset_time_text(unparsed).nil?, "#{unparsed.inspect} must not parse as a reset time")
      end
    end

    # The client limit status names the SDK's retry time in UTC, rounded up.
    def check_client_limit_vectors
      [
        [TEST_RETRY_TIME, "client limit, retry at 19:05 UTC"],
        # 19:04:00.001 UTC rounds up
        [1_791_313_440_001, "client limit, retry at 19:05 UTC"],
        [0, "client limit"],
      ].each do |retry_time, text|
        actual = Embed.client_limit_text(retry_time)
        expect(actual == text, "retry #{retry_time} shows #{actual.inspect}, want #{text.inspect}")
      end
    end

    # The contract's console status line vectors.
    def check_status_lines
      failed = CapState.new
      failed.record(nil)
      [
        # connecting, caps not read yet
        ["", 0, CapState.new, 0, "status: connecting | data this month: checking | data total: checking"],
        ["", 0, CapState.new(cap_reading(monthly_byte_limit: 5_000_000_000, monthly_used_byte_count: 1_234_567_890, total_byte_limit: nil)), 1,
         "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap"],
        ["", 0, failed, 1, "status: connected | data this month: unavailable | data total: unavailable"],
        ["", 0, CapState.new(cap_reading(monthly_byte_limit: 5_000_000_000, monthly_used_byte_count: 5_000_000_000,
                                         monthly_period_end: "2026-11-01T00:00:00Z", capped: true, capped_reason: CAPPED_REASON_MONTHLY)), 3,
         "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap"],
        ["", 0, CapState.new(cap_reading(total_byte_limit: 10_000_000_000, total_used_byte_count: 10_000_000_000,
                                         capped: true, capped_reason: CAPPED_REASON_TOTAL)), 3,
         "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB"],
        ["", 0, CapState.new(cap_reading(monthly_byte_limit: 0, monthly_used_byte_count: 0, capped: true, capped_reason: CAPPED_REASON_MONTHLY)), 3,
         "status: paused | data this month: 0 B of 0 B | data total: no cap"],
        [CLIENT_LIMIT_STATUS_EXCEEDED, TEST_RETRY_TIME, CapState.new(cap_reading(monthly_byte_limit: 5_000_000_000, monthly_used_byte_count: 0)), 0,
         "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap"],
      ].each do |client_limit_status, retry_time, cap_state, providers_added, line|
        actual = Embed.status_snapshot(true, false, client_limit_status, retry_time, cap_state, providers_added).line
        expect(actual == line, "status line #{actual.inspect}, want #{line.inspect}")
      end
    end

    # The contract's status rule vectors, in rule order.
    def check_status_rules
      capped_monthly_zero = cap_reading(monthly_byte_limit: 0, capped: true, capped_reason: CAPPED_REASON_MONTHLY)
      capped_total_zero = cap_reading(total_byte_limit: 0, monthly_byte_limit: 5_000_000_000, capped: true, capped_reason: CAPPED_REASON_TOTAL)
      capped_monthly = cap_reading(monthly_byte_limit: 5_000_000_000, monthly_period_end: "2026-11-01T00:00:00Z", capped: true, capped_reason: CAPPED_REASON_MONTHLY)
      capped_total = cap_reading(total_byte_limit: 10_000_000_000, capped: true, capped_reason: CAPPED_REASON_TOTAL)
      not_capped = cap_reading
      [
        [false, false, CLIENT_LIMIT_STATUS_NONE, 0, nil, 0, "stopped"],
        [false, true, CLIENT_LIMIT_STATUS_NONE, 0, nil, 0, "signed out"],
        # client limit before paused
        [true, false, CLIENT_LIMIT_STATUS_EXCEEDED, TEST_RETRY_TIME, capped_monthly_zero, 3, "client limit, retry at 19:05 UTC"],
        [true, false, CLIENT_LIMIT_STATUS_NONE, 0, capped_monthly_zero, 3, "paused"],
        # the cap that capped_reason names decides paused
        [true, false, CLIENT_LIMIT_STATUS_NONE, 0, capped_total_zero, 3, "paused"],
        # paused before data cap reached
        [true, false, CLIENT_LIMIT_STATUS_NONE, 0, capped_monthly, 3, "data cap reached, resets 2026-11-01 00:00 UTC"],
        [true, false, CLIENT_LIMIT_STATUS_NONE, 0, capped_total, 0, "data cap reached"],
        [true, false, CLIENT_LIMIT_STATUS_NONE, 0, not_capped, 1, "connected"],
        [true, false, CLIENT_LIMIT_STATUS_NONE, 0, nil, 0, "connecting"],
      ].each do |started, signed_out, client_limit_status, retry_time, reading, providers_added, status|
        actual = Embed.embed_status(started, signed_out, client_limit_status, retry_time, reading, providers_added)
        expect(actual == status, "status #{actual.inspect}, want #{status.inspect} for started=#{started} signed_out=#{signed_out} reading=#{reading.inspect}")
      end
      # a monthly cap whose period end does not parse is reached without a reset time
      unparsed_end = cap_reading(monthly_byte_limit: 5, monthly_period_end: "soon", capped: true, capped_reason: CAPPED_REASON_MONTHLY)
      expect(Embed.embed_status(true, false, "", 0, unparsed_end, 1) == "data cap reached", "an unparsed period end must show plain data cap reached")
    end

    # checking, unavailable, a later failure keeping the last value, no cap
    # for an unset limit even with a used count, and <used> of <limit>.
    def check_data_fields
      cap_state = CapState.new
      expect(Embed.data_field_text(cap_state, true) == "checking", "data this month before a reading must be checking")
      expect(Embed.data_field_text(cap_state, false) == "checking", "data total before a reading must be checking")
      cap_state.record(nil)
      expect(Embed.data_field_text(cap_state, true) == "unavailable", "a failed first reading must be unavailable")
      cap_state.record(nil)
      expect(Embed.data_field_text(cap_state, false) == "unavailable", "a second failure before any success stays unavailable")
      cap_state.record(cap_reading(monthly_byte_limit: 2000, monthly_used_byte_count: 1250, total_byte_limit: nil, total_used_byte_count: 999))
      expect(Embed.data_field_text(cap_state, true) == "1.2 kB of 2.0 kB", "a reading must show <used> of <limit>")
      expect(Embed.data_field_text(cap_state, false) == "no cap", "an unset cap shows no cap, never its used count")
      cap_state.record(nil)
      expect(Embed.data_field_text(cap_state, true) == "1.2 kB of 2.0 kB", "a later failure must keep the last value")
    end

    # Null or absent limits, capped and capped_reason, an unknown reason read
    # as capped without a reset time, and objects that are not cap objects.
    def check_cap_parsing
      minimal = Caps.parse_cap_object({"client_id" => TEST_CLIENT_ID})
      expect(!minimal.nil?, "a cap object with only client_id must parse")
      expect(minimal.monthly_byte_limit.nil? && minimal.total_byte_limit.nil?, "absent limits must be no cap")
      expect(minimal.monthly_used_byte_count.zero? && !minimal.capped && minimal.capped_reason == "", "absent counts and flags must default")
      nulls = Caps.parse_cap_object({"client_id" => TEST_CLIENT_ID, "monthly_byte_limit" => nil, "total_byte_limit" => 5, "capped" => nil})
      expect(!nulls.nil? && nulls.monthly_byte_limit.nil? && nulls.total_byte_limit == 5, "a null limit must be no cap")
      capped = Caps.parse_cap_answer(JSON.generate({
        "client_id" => TEST_CLIENT_ID, "monthly_byte_limit" => 10, "monthly_used_byte_count" => 10,
        "monthly_period_start" => "2026-10-01T00:00:00Z", "monthly_period_end" => "2026-11-01T00:00:00Z",
        "total_byte_limit" => nil, "total_used_byte_count" => 10, "total_period_start" => "2026-09-15T00:00:00Z",
        "capped" => true, "capped_reason" => "monthly",
      }))
      expect(!capped.nil? && capped.capped && capped.capped_reason == "monthly", "capped and capped_reason must parse")
      unknown = Caps.parse_cap_object({
        "client_id" => TEST_CLIENT_ID, "monthly_byte_limit" => 10, "monthly_period_end" => "2026-11-01T00:00:00Z",
        "capped" => true, "capped_reason" => "weekly",
      })
      expect(!unknown.nil?, "an unknown capped_reason must still parse")
      expect(Embed.embed_status(true, false, "", 0, unknown, 1) == "data cap reached", "an unknown capped_reason must read as capped without a reset time")
      [
        {"error" => {"message" => "no permission"}},
        {"client_id" => TEST_CLIENT_ID, "monthly_byte_limit" => "5"},
        {"client_id" => TEST_CLIENT_ID, "monthly_byte_limit" => -1},
        {"client_id" => TEST_CLIENT_ID, "monthly_byte_limit" => 5.0},
        {"client_id" => TEST_CLIENT_ID, "total_byte_limit" => true},
        {"client_id" => TEST_CLIENT_ID, "capped" => "yes"},
        {"client_id" => TEST_CLIENT_ID, "capped_reason" => 3},
        [TEST_CLIENT_ID],
        nil,
      ].each do |invalid|
        expect(Caps.parse_cap_object(invalid).nil?, "#{invalid.inspect} must not parse as a cap object")
      end
      expect(Caps.parse_cap_answer("not json").nil?, "an answer that is not JSON must not parse")
      # the app's own read: a 404 from a server without the cap routes is a failure
      expect(Caps.read_own_caps(TEST_API, test_client_jwt, StandInServer.new([404, "not found"])).nil?, "a 404 must be a failed read")
      expect(Caps.read_own_caps(TEST_API, test_client_jwt, StandInServer.new(TransportError.new("unreachable"))).nil?, "an unreachable API must be a failed read")
      server = StandInServer.new([200, {"client_id" => TEST_CLIENT_ID, "monthly_byte_limit" => 7}])
      reading = Caps.read_own_caps(TEST_API, test_client_jwt, server)
      expect(!reading.nil? && reading.monthly_byte_limit == 7, "a cap answer must be read")
      request = server.requests[0]
      expect(request[:method] == "GET" && request[:url] == "#{TEST_API}/network/client-data-cap", "the cap read must GET /network/client-data-cap")
      expect(request[:headers]["Authorization"] == "Bearer #{test_client_jwt}", "the cap read must use the client JWT")
    end

    # The client limit status and the window status in the C ABI's JSON.
    def check_sdk_status_json
      expect(Embed.parse_client_limit_status(nil) == [CLIENT_LIMIT_STATUS_NONE, 0], "NULL must read as no client limit")
      expect(Embed.parse_client_limit_status(%({"Status":"client_limit_exceeded","RetryTime":#{TEST_RETRY_TIME}})) == [CLIENT_LIMIT_STATUS_EXCEEDED, TEST_RETRY_TIME],
             "the client limit status must parse")
      expect(Embed.parse_client_limit_status("not json") == [CLIENT_LIMIT_STATUS_NONE, 0], "invalid JSON must read as no client limit")
      expect(Embed.parse_providers_added(nil).zero?, "no window yet must be no providers")
      expect(Embed.parse_providers_added('{"ProviderStateAdded":2,"TargetSize":4}') == 2, "ProviderStateAdded must parse")
      expect(Embed.parse_providers_added('{"ProviderStateAdded":true}').zero?, "a non-integer ProviderStateAdded must be 0")
    end

    # A client JWT is accepted; a network JWT, a malformed token and an
    # invalid UUID are refused.
    def check_client_jwt_claims
      expect(State.parse_client_jwt_client_id(test_client_jwt) == TEST_CLIENT_ID, "a client JWT must be accepted")
      expect(State.parse_client_jwt_client_id(self_test_jwt(%({"client_id":"#{TEST_CLIENT_ID.upcase}"}))) == TEST_CLIENT_ID,
             "a client_id claim must be read in canonical form")
      [
        self_test_jwt('{"network_id":"44444444-4444-4444-4444-444444444444"}'),
        self_test_jwt('{"client_id":"not-a-uuid"}'),
        self_test_jwt('{"client_id":""}'),
        self_test_jwt('"a string"'),
        "not-a-jwt",
        "a.b",
        "a..c",
        "a.!!!.c",
      ].each do |client_jwt|
        expect_raises(ConfigError, "#{client_jwt.inspect} must be refused") { State.parse_client_jwt_client_id(client_jwt) }
      end
    end

    # The token server and API URLs are HTTPS origins, or explicit loopback HTTP.
    def check_origins
      [
        ["https://tokens.example.com", "https://tokens.example.com"],
        ["https://tokens.example.com/", "https://tokens.example.com"],
        ["https://tokens.example.com:8443", "https://tokens.example.com:8443"],
        ["http://127.0.0.1:8790", "http://127.0.0.1:8790"],
        ["http://localhost:8790", "http://localhost:8790"],
        ["http://[::1]:8790", "http://[::1]:8790"],
      ].each do |url, origin|
        actual = Transport.check_origin(url, "TEST")
        expect(actual == origin, "#{url.inspect} must be accepted as #{origin.inspect}, got #{actual.inspect}")
      end
      ["", "http://example.com", "https://example.com/path", "https://user:password@example.com", "https://example.com?query=1",
       "https://example.com?", "https://example.com#fragment", "ftp://example.com", "https://example.com:notaport"].each do |url|
        expect_raises(ConfigError, "#{url.inspect} must be refused") { Transport.check_origin(url, "TEST") }
      end
    end

    # The token fetch against a stand-in token server: the request, saving
    # client.jwt atomically, the client_id claim check, and the answers mapped
    # to the exit codes.
    def check_token_fetch
      private_dir do |state_dir|
        server = StandInServer.new([200, token_answer])
        fetched = ClientToken.fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION, server)
        request = server.requests[0]
        expect(request[:method] == "POST", "the token fetch must POST")
        expect(request[:url] == TEST_TOKEN_SERVER + ClientToken::TOKEN_ROUTE, "the token fetch must post to /urnetwork/client-token")
        expect(request[:headers]["Authorization"] == "Bearer #{TEST_SESSION}", "the demo session must be the bearer token")
        expect(JSON.parse(request[:body]) == {"installation_id" => TEST_INSTANCE_ID}, "installation_id must equal the instance-id")
        expect(fetched.client_id == TEST_CLIENT_ID && fetched.client_jwt == test_client_jwt, "the answer must be returned")
        expect(!fetched.data_cap.nil? && fetched.data_cap.monthly_byte_limit == 10_000_000_000, "data_cap must be the first reading")
        client_jwt_path = File.join(state_dir, State::CLIENT_JWT_FILE_NAME)
        expect(State.read_private_file(client_jwt_path) == "#{test_client_jwt}\n", "client.jwt must hold the fetched token")
        expect((File.stat(client_jwt_path).mode & 0o777) == 0o600, "client.jwt must be 0600") if State::POSIX
        expect(Dir.children(state_dir).sort == [State::CLIENT_JWT_FILE_NAME], "the atomic write must leave no temporary file")

        # data_cap null, from a token server that could not read the caps
        fetched = ClientToken.fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION, StandInServer.new([200, token_answer(data_cap: nil)]))
        expect(fetched.data_cap.nil?, "a null data_cap must leave no first reading")

        # a client_id that does not match the claim is refused, and client.jwt is kept
        mismatch = StandInServer.new([200, token_answer(client_id: TEST_OTHER_CLIENT_ID)])
        expect_raises(TokenServerError, "a client_id that does not match the claim must be refused") do
          ClientToken.fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION, mismatch)
        end
        expect(State.read_private_file(client_jwt_path) == "#{test_client_jwt}\n", "a refused answer must not replace client.jwt")
        network_jwt = self_test_jwt('{"network_id":"44444444-4444-4444-4444-444444444444"}')
        [
          [[401, {"error" => {"code" => "unauthorized", "message" => "unknown session"}}], TokenServerRefused],
          [[409, {"error" => {"code" => "installation_limit", "message" => "too many installations"}}], TokenServerRefused],
          [[409, {"error" => {"code" => "client_limit", "message" => "client limit; see https://ur.io/services"}}], TokenServerRefused],
          [[503, {"error" => {"code" => "busy", "message" => "retry"}}], TokenServerError],
          [[502, {"error" => {"code" => "upstream", "message" => "upstream failed"}}], TokenServerError],
          [[500, "internal error"], TokenServerError],
          [[200, "not json"], TokenServerError],
          [[200, {"client_id" => TEST_CLIENT_ID}], TokenServerError],
          [[200, token_answer(client_jwt: network_jwt)], TokenServerError],
          [TransportError.new("connection refused"), TokenServerError],
        ].each do |answer, error_class|
          expect_raises(error_class, "the answer #{answer.inspect} must raise #{error_class}") do
            ClientToken.fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION, StandInServer.new(answer))
          end
        end
        # the server's message is passed on
        begin
          ClientToken.fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION,
                                       StandInServer.new([401, {"error" => {"code" => "unauthorized", "message" => "unknown session"}}]))
        rescue TokenServerRefused => error
          expect(error.message == "unknown session", "the token server's error message must be shown")
        end
      end

      # the run command maps the answers to the exit codes, and never prints the session
      [
        [[401, {"error" => {"code" => "unauthorized", "message" => "unknown session"}}], EXIT_CONFIG],
        [[409, {"error" => {"code" => "client_limit", "message" => "client limit"}}], EXIT_CONFIG],
        [[503, {"error" => {"code" => "busy", "message" => "retry"}}], EXIT_FAILURE],
        [TransportError.new("connection refused"), EXIT_FAILURE],
      ].each do |answer, exit_code|
        private_dir do |state_dir|
          settings = {"URNETWORK_EMBED_STATE_DIR" => state_dir, "URNETWORK_TOKEN_SERVER_URL" => TEST_TOKEN_SERVER, "URNETWORK_DEMO_SESSION" => TEST_SESSION}
          actual, output = embed_environment(settings) { captured { Embed.run(["run"], transport: StandInServer.new(answer)) } }
          expect(actual == exit_code, "the answer #{answer.inspect} must exit #{exit_code}, got #{actual}")
          expect(!output.include?(TEST_SESSION), "the demo session must never be printed")
        end
      end
    end

    # Private permissions on POSIX, atomic replacement, instance-id created
    # once and reused, and symlinked or oversized files refused.
    def check_state_files
      private_dir do |state_dir|
        State.check_state_dir(state_dir)
        path = File.join(state_dir, State::CLIENT_JWT_FILE_NAME)
        expect_raises(Errno::ENOENT, "a missing file must raise Errno::ENOENT") { State.read_private_file(path) }
        expect(State.load_client_jwt(state_dir).nil?, "a missing client.jwt must load as nil")
        State.write_private_file(path, "first\n")
        State.write_private_file(path, "  second  \n")
        expect(State.read_private_file(path) == "  second  \n", "the second write must replace the first")
        expect(State.load_client_jwt(state_dir) == "second", "client.jwt must load without surrounding whitespace")
        expect(Dir.children(state_dir).sort == [State::CLIENT_JWT_FILE_NAME], "the atomic writes must leave no temporary file")

        instance_id = State.load_or_create_instance_id(state_dir)
        expect(State.load_or_create_instance_id(state_dir) == instance_id, "instance-id must be created once and reused")
        State.write_private_file(File.join(state_dir, State::INSTANCE_ID_FILE_NAME), "not a uuid\n")
        expect_raises(ConfigError, "an invalid instance-id must be refused") { State.load_or_create_instance_id(state_dir) }

        large = File.join(state_dir, "large")
        State.write_private_file(large, "x" * (State::STATE_FILE_BYTE_LIMIT + 1))
        expect_raises(StateFileError, "an oversized state file must be refused") { State.read_private_file(large) }

        if State::POSIX
          State.write_private_file(path, "token\n")
          File.chmod(0o644, path)
          expect_raises(StateFileError, "a file readable by others must be refused") { State.read_private_file(path) }
          expect_raises(ConfigError, "an unprotected client.jwt must be a configuration error") { State.load_client_jwt(state_dir) }
          File.chmod(0o600, path)
          File.unlink(path)
          target = File.join(state_dir, "target")
          State.write_private_file(target, "token\n")
          File.symlink(target, path)
          expect_raises(StateFileError, "a symlinked state file must be refused") { State.read_private_file(path) }
          File.chmod(0o755, state_dir)
          expect_raises(ConfigError, "a state directory others can read must be refused") { State.check_state_dir(state_dir) }
          File.chmod(0o700, state_dir)
        end
      end
      expect_raises(ConfigError, "a missing state directory must be refused") { State.check_state_dir("") }
      expect_raises(ConfigError, "a relative state directory must be refused") { State.check_state_dir(File.join("relative", "state")) }
      private_dir do |parent|
        expect_raises(ConfigError, "a nonexistent state directory must be refused") { State.check_state_dir(File.join(parent, "missing")) }
      end
    end

    # The configuration errors exit with 78 before any SDK call, and so does a
    # usage error.
    def check_config_errors
      run_quietly = lambda do |args, settings = {}|
        embed_environment(settings) { captured { Embed.run(args, transport: StandInServer.new) }.first }
      end
      expect(run_quietly.call(["run"]) == EXIT_CONFIG, "a missing state directory must exit 78")
      expect(run_quietly.call(["run"], {"URNETWORK_EMBED_STATE_DIR" => File.join("relative", "state")}) == EXIT_CONFIG, "a relative state directory must exit 78")
      private_dir do |state_dir|
        expect(run_quietly.call([], {"URNETWORK_EMBED_STATE_DIR" => state_dir}) == EXIT_CONFIG, "no token server and no client.jwt must exit 78")
        expect(run_quietly.call(["run"], {"URNETWORK_EMBED_STATE_DIR" => state_dir, "URNETWORK_TOKEN_SERVER_URL" => TEST_TOKEN_SERVER}) == EXIT_CONFIG,
               "a token server URL without a demo session must exit 78")
        expect(run_quietly.call(["run"], {"URNETWORK_EMBED_STATE_DIR" => state_dir, "URNETWORK_TOKEN_SERVER_URL" => "http://example.com",
                                           "URNETWORK_DEMO_SESSION" => TEST_SESSION}) == EXIT_CONFIG,
               "a token server URL that is not HTTPS must exit 78")
        expect(run_quietly.call(["run"], {"URNETWORK_EMBED_STATE_DIR" => state_dir, "URNETWORK_API_URL" => "https://example.com/path"}) == EXIT_CONFIG,
               "an invalid API URL must exit 78")
        State.write_private_file(File.join(state_dir, State::CLIENT_JWT_FILE_NAME), "#{self_test_jwt('{"network_id":"44444444-4444-4444-4444-444444444444"}')}\n")
        expect(run_quietly.call(["run"], {"URNETWORK_EMBED_STATE_DIR" => state_dir}) == EXIT_CONFIG, "a network JWT in client.jwt must exit 78")
      end
      expect(run_quietly.call(["bogus"]) == EXIT_CONFIG, "an unknown command must exit 78")
      expect(run_quietly.call(%w[run extra]) == EXIT_CONFIG, "extra arguments must exit 78")
    end
  end
end
