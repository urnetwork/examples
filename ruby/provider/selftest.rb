# frozen_string_literal: true

# The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It checks
# the disclaimer, the status text, the providing state, the clients-served
# count, the installation state files and the usage exit code without a
# network, credentials, a device or the native SDK. `main.rb --self-test` runs
# every check; provider_test.rb runs each one as a test.

require "digest"
require "fileutils"
require "json"
require "stringio"
require "tmpdir"
require_relative "state"
require_relative "status"

module Provider
  # The self-test checks; each raises SelfTest::Failure when it fails.
  module SelfTest
    # A self-test check that failed.
    class Failure < StandardError; end

    # sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
    # newline), published in PROVIDER_CONTRACT.md for every example to check
    CONSENT_DISCLAIMER_SHA256 = "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c"

    # the public substrate development account, test data only
    TEST_WALLET = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"

    TEST_PROVIDER_ID = "11111111-1111-1111-1111-111111111111"
    TEST_CLIENT_A_ID = "22222222-2222-2222-2222-222222222222"
    TEST_CLIENT_B_ID = "33333333-3333-3333-3333-333333333333"
    TEST_STREAM_ID = "44444444-4444-4444-4444-444444444444"
    TEST_ZERO_ID = "00000000-0000-0000-0000-000000000000"

    CHECKS = %i[
      check_consent_disclaimer
      check_format_byte_count
      check_status_text
      check_status_lines
      check_status_key
      check_provider_state
      check_payout_wallet_scope
      check_clients_served
      check_sdk_status_json
      check_client_jwt_claims
      check_state_files
      check_provider_config
      check_usage_exit_code
    ].freeze

    module_function

    # Runs every check and raises the first failure.
    def run
      CHECKS.each { |check| public_send(check) }
    end

    # Raises a failure with message unless condition holds.
    def expect(condition, message)
      raise Failure, message unless condition
    end

    # Runs the block, which must raise one of errors.
    def expect_refused(message, *errors)
      errors = [ConfigError] if errors.empty?
      begin
        yield
      rescue *errors
        return
      end
      raise Failure, message
    end

    # The disclaimer is the contract's exact text.
    def check_consent_disclaimer
      digest = Digest::SHA256.hexdigest(CONSENT_DISCLAIMER.encode(Encoding::UTF_8))
      expect(digest == CONSENT_DISCLAIMER_SHA256, "consent disclaimer differs from PROVIDER_CONTRACT.md")
    end

    # Byte counts use binary units with one decimal.
    def check_format_byte_count
      [
        [0, "0 B"],
        [1023, "1023 B"],
        [1024, "1.0 KiB"],
        [1536, "1.5 KiB"],
        [1_048_575, "1.0 MiB"],
        [13_002_342, "12.4 MiB"],
        [5 * 1024 * 1024 * 1024, "5.0 GiB"],
        [3 * 1024 * 1024 * 1024 * 1024, "3.0 TiB"],
      ].each do |byte_count, text|
        formatted = Provider.format_byte_count(byte_count)
        expect(formatted == text, "byte count #{byte_count} formats as #{formatted.inspect}, want #{text.inspect}")
      end
    end

    # The client limit status names the SDK's retry time in UTC, rounded up to
    # the next whole minute; other states never show it.
    def check_status_text
      [
        # 2026-10-06 19:05:00.000 UTC, exactly on a minute
        [PROVIDER_STATE_CLIENT_LIMIT, 1_791_313_500_000, "client limit, retry at 19:05 UTC"],
        # 19:04:00.001 rounds up, so the shown time is never before the retry
        [PROVIDER_STATE_CLIENT_LIMIT, 1_791_313_440_001, "client limit, retry at 19:05 UTC"],
        # no retry time
        [PROVIDER_STATE_CLIENT_LIMIT, 0, "client limit"],
        # 23:59:00.001 rolls over the hour and the day
        [PROVIDER_STATE_CLIENT_LIMIT, 1_791_331_140_001, "client limit, retry at 00:00 UTC"],
        [PROVIDER_STATE_STARTING, 1_791_313_500_000, "starting"],
      ].each do |state, client_limit_retry_time, text|
        status_text = Provider.provider_status_text(state, client_limit_retry_time)
        expect(status_text == text, "status text #{status_text.inspect} for #{state.inspect} retrying at #{client_limit_retry_time}, want #{text.inspect}")
      end
    end

    # The status line matches the contract's golden lines.
    def check_status_lines
      [
        [
          Status.new(state: PROVIDER_STATE_STARTING, payout_wallet: PAYOUT_WALLET_CHECKING),
          "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking",
        ],
        [
          Status.new(state: PROVIDER_STATE_PROVIDING, clients_served: 3, data_provided_byte_count: 13_002_342,
                     payout_wallet: TEST_WALLET, payout_wallet_scope: PAYOUT_WALLET_SCOPE_NETWORK),
          "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: #{TEST_WALLET} (network)",
        ],
        [
          Status.new(state: PROVIDER_STATE_PROVIDING, clients_served: 3, data_provided_byte_count: 13_002_342,
                     payout_wallet: TEST_WALLET, payout_wallet_scope: PAYOUT_WALLET_SCOPE_HOTKEY),
          "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: #{TEST_WALLET} (hotkey)",
        ],
        [
          Status.new(state: PROVIDER_STATE_PAUSED, clients_served: CLIENTS_SERVED_LIMIT, clients_served_at_limit: true,
                     data_provided_byte_count: 1536, payout_wallet: TEST_WALLET,
                     payout_wallet_scope: PAYOUT_WALLET_SCOPE_PROVIDER),
          "status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: #{TEST_WALLET} (this provider)",
        ],
        [
          Status.new(state: PROVIDER_STATE_STOPPED, payout_wallet: PAYOUT_WALLET_NOT_SET),
          "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set",
        ],
        [
          Status.new(state: PROVIDER_STATE_CLIENT_LIMIT, client_limit_retry_time: 1_791_313_500_000,
                     payout_wallet: TEST_WALLET, payout_wallet_scope: PAYOUT_WALLET_SCOPE_NETWORK),
          "status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: #{TEST_WALLET} (network)",
        ],
      ].each do |status, line|
        expect(status.line == line, "status line #{status.line.inspect}, want #{line.inspect}")
      end
    end

    # A change of the status text prints a line at once, including a new client
    # limit retry time; the data counter alone does not.
    def check_status_key
      status = Status.new(state: PROVIDER_STATE_CLIENT_LIMIT, client_limit_retry_time: 1_791_313_500_000,
                          payout_wallet: PAYOUT_WALLET_CHECKING)
      # 19:25 UTC
      retried = Status.new(state: PROVIDER_STATE_CLIENT_LIMIT, client_limit_retry_time: 1_791_314_700_000,
                           payout_wallet: PAYOUT_WALLET_CHECKING)
      expect(status.key != retried.key, "a new client limit retry time does not print a status line")
      counted = Status.new(state: PROVIDER_STATE_CLIENT_LIMIT, client_limit_retry_time: 1_791_313_500_000,
                           data_provided_byte_count: 1536, payout_wallet: PAYOUT_WALLET_CHECKING)
      expect(status.key == counted.key, "the data counter alone prints a status line")
    end

    # The providing state follows the provide mode, client limit, pause, enable
    # and connected rules, in that order.
    def check_provider_state
      # provide mode, client limit status, paused, enabled, connected, state
      [
        [PROVIDE_MODE_NONE, CLIENT_LIMIT_STATUS_NONE, false, false, false, PROVIDER_STATE_STOPPED],
        [PROVIDE_MODE_NETWORK, CLIENT_LIMIT_STATUS_NONE, false, true, true, PROVIDER_STATE_STOPPED],
        [PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, false, true, false, PROVIDER_STATE_STARTING],
        [PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, false, false, true, PROVIDER_STATE_STARTING],
        [PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, false, true, true, PROVIDER_STATE_PROVIDING],
        [PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, true, true, true, PROVIDER_STATE_PAUSED],
        # the client limit comes after stopped and before every other state
        [PROVIDE_MODE_NETWORK, CLIENT_LIMIT_STATUS_EXCEEDED, false, true, true, PROVIDER_STATE_STOPPED],
        [PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_EXCEEDED, true, true, true, PROVIDER_STATE_CLIENT_LIMIT],
        [PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_EXCEEDED, false, false, false, PROVIDER_STATE_CLIENT_LIMIT],
        [PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, true, true, true, PROVIDER_STATE_PAUSED],
      ].each do |provide_mode, client_limit_status, paused, enabled, connected, state|
        actual = Provider.provider_state(provide_mode, client_limit_status, paused, enabled, connected)
        case_text = "mode #{provide_mode}, client limit #{client_limit_status.inspect}, paused #{paused}, enabled #{enabled}, connected #{connected}"
        expect(actual == state, "provider state #{actual.inspect} for #{case_text}; want #{state.inspect}")
      end
    end

    # The payout wallet is labeled by its consent scope first, then by the
    # owner of its mapping.
    def check_payout_wallet_scope
      [
        # a hotkey delegation is network-level but not the network's wallet
        [SN_WALLET_CONSENT_SCOPE_HOTKEY, "", PAYOUT_WALLET_SCOPE_HOTKEY],
        [SN_WALLET_CONSENT_SCOPE_HOTKEY, TEST_PROVIDER_ID, PAYOUT_WALLET_SCOPE_HOTKEY],
        [SN_WALLET_CONSENT_SCOPE_NETWORK, "", PAYOUT_WALLET_SCOPE_NETWORK],
        ["", "", PAYOUT_WALLET_SCOPE_NETWORK],
        [SN_WALLET_CONSENT_SCOPE_PROVIDER, TEST_PROVIDER_ID, PAYOUT_WALLET_SCOPE_PROVIDER],
        ["", TEST_PROVIDER_ID, PAYOUT_WALLET_SCOPE_PROVIDER],
        [SN_WALLET_CONSENT_SCOPE_PROVIDER, TEST_CLIENT_A_ID, PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER],
      ].each do |wallet_consent_scope, wallet_client_id, scope|
        actual = Provider.payout_wallet_scope(wallet_consent_scope, wallet_client_id, TEST_PROVIDER_ID)
        case_text = "consent scope #{wallet_consent_scope.inspect} and client #{wallet_client_id.inspect}"
        expect(actual == scope, "payout wallet scope #{actual.inspect} for #{case_text}, want #{scope.inspect}")
      end
    end

    # A provider contract as the C ABI's contract details listeners deliver it.
    def contract_json(contract_id, source_id, destination_id, stream_id)
      JSON.generate(
        "ContractId" => contract_id,
        "ContractUsedByteCount" => 0,
        "ContractByteCount" => 0,
        "ContractBitRate" => 0,
        "ContractTransferPath" => {"SourceId" => source_id, "DestinationId" => destination_id, "StreamId" => stream_id},
        "Status" => "open",
      )
    end

    # Contract peers resolve by direction and count once per client, up to the
    # limit.
    def check_clients_served
      # the peer is the source of a receive contract and the destination of a send contract
      [
        [contract_json("55555555-5555-5555-5555-555555555555", TEST_CLIENT_A_ID, TEST_PROVIDER_ID, nil), true, TEST_CLIENT_A_ID],
        [contract_json("66666666-6666-6666-6666-666666666666", TEST_PROVIDER_ID, TEST_CLIENT_A_ID, nil), false, TEST_CLIENT_A_ID],
        [contract_json("77777777-7777-7777-7777-777777777777", TEST_ZERO_ID, TEST_PROVIDER_ID, TEST_STREAM_ID), true, "stream:#{TEST_STREAM_ID}"],
        [contract_json("88888888-8888-8888-8888-888888888888", nil, nil, nil), true, "contract:88888888-8888-8888-8888-888888888888"],
        ['{"ContractId": "88888888-8888-8888-8888-888888888888", "ContractTransferPath": null, "Status": "open"}', true,
         "contract:88888888-8888-8888-8888-888888888888"],
      ].each do |details_json, receive, peer_key|
        actual = Provider.contract_peer_key(Provider.parse_json_object(details_json), receive)
        expect(actual == peer_key, "contract peer key #{actual.inspect}, want #{peer_key.inspect}")
      end

      # both directions of one client count once
      add = ->(served, details_json, receive) { served.add(Provider.parse_json_object(details_json), receive) }
      served = ClientsServed.new(2)
      add.call(served, contract_json("55555555-5555-5555-5555-555555555555", TEST_CLIENT_A_ID, TEST_PROVIDER_ID, nil), true)
      add.call(served, contract_json("66666666-6666-6666-6666-666666666666", TEST_PROVIDER_ID, TEST_CLIENT_A_ID, nil), false)
      add.call(served, contract_json("99999999-9999-9999-9999-999999999999", TEST_CLIENT_A_ID, TEST_PROVIDER_ID, nil), true)
      expect(served.count == [1, false], "one client counted as #{served.count.inspect}")
      add.call(served, contract_json("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", TEST_CLIENT_B_ID, TEST_PROVIDER_ID, nil), true)
      expect(served.count == [2, false], "two clients counted as #{served.count.inspect}")
      # a third distinct peer reaches the limit of 2
      add.call(served, contract_json("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", TEST_ZERO_ID, TEST_PROVIDER_ID, TEST_STREAM_ID), true)
      expect(served.count == [2, true], "limited count #{served.count.inspect}")
      # null details from the sdk count nothing
      add.call(served, "null", true)
      expect(served.count == [2, true], "null contract details changed the count to #{served.count.inspect}")
    end

    # The client limit status and the provider packet stats read from the C
    # ABI's JSON: NULL is no limit and null stats are 0 bytes.
    def check_sdk_status_json
      [
        [nil, [CLIENT_LIMIT_STATUS_NONE, 0]],
        ['{"Status": "", "RetryTime": 0}', [CLIENT_LIMIT_STATUS_NONE, 0]],
        ['{"Status": "client_limit_exceeded", "RetryTime": 1791313500000}', [CLIENT_LIMIT_STATUS_EXCEEDED, 1_791_313_500_000]],
      ].each do |status_json, client_limit|
        actual = Provider.parse_client_limit_status(status_json)
        expect(actual == client_limit, "client limit status #{actual.inspect} from #{status_json.inspect}, want #{client_limit.inspect}")
      end
      [
        [nil, 0],
        ["null", 0],
        ['{"RemoteEgressByteCount": 5, "RemoteIngressByteCount": 7, "LocalEgressByteCount": 11}', 12],
      ].each do |packet_stats_json, byte_count|
        actual = Provider.parse_data_provided_byte_count(packet_stats_json)
        expect(actual == byte_count, "data provided #{actual} from #{packet_stats_json.inspect}, want #{byte_count}")
      end
    end

    # A synthetic, unsigned JWT with the given payload JSON.
    def self_test_jwt(payload_json)
      payload = [payload_json].pack("m0").tr("+/", "-_").delete("=")
      "e30.#{payload}.test"
    end

    # Only a JWT with a valid client_id claim is a client credential.
    def check_client_jwt_claims
      client_id = State.parse_client_jwt_client_id(
        self_test_jwt(%({"client_id":"#{TEST_PROVIDER_ID}","network_id":"#{TEST_CLIENT_A_ID}"})),
      )
      expect(client_id == TEST_PROVIDER_ID, "client jwt claim #{client_id.inspect}")
      [
        "",
        "not-a-jwt",
        self_test_jwt(%({"network_id":"#{TEST_CLIENT_A_ID}"})),
        self_test_jwt('{"client_id":"not-a-uuid"}'),
        "e30.%%%.test",
      ].each do |invalid_jwt|
        expect_refused("invalid client jwt #{invalid_jwt.inspect} accepted") { State.parse_client_jwt_client_id(invalid_jwt) }
      end
    end

    # A private temporary state directory for the block, removed after it.
    def with_state_dir
      state_dir = Dir.mktmpdir("ur-provider-self-test-")
      File.chmod(0o700, state_dir) if State::POSIX
      yield state_dir
    ensure
      FileUtils.rm_rf(state_dir) if state_dir
    end

    # State files are private, replaced atomically, created once and bound to
    # their client.
    def check_state_files
      with_state_dir do |state_dir|
        # an atomic private write leaves no temporary file behind
        path = File.join(state_dir, State::CLIENT_JWT_FILE_NAME)
        State.write_private_file(path, "first\n")
        State.write_private_file(path, "second\n")
        expect(State.read_private_file(path) == "second\n", "private file round trip #{State.read_private_file(path).inspect}")
        expect(Dir.children(state_dir) == [State::CLIENT_JWT_FILE_NAME], "private writes left #{Dir.children(state_dir).inspect}")
        if State::POSIX
          mode = File.stat(path).mode & 0o777
          expect(mode == 0o600, format("private file mode %o", mode))
          # a file or directory that others can read is refused
          File.chmod(0o644, path)
          expect_refused("a group-readable credential file was accepted", StateFileError) { State.read_private_file(path) }
          File.chmod(0o600, path)
          File.chmod(0o755, state_dir)
          expect_refused("a group-readable state directory was accepted") { State.check_state_dir(state_dir) }
          File.chmod(0o700, state_dir)
          # a symlink could redirect the credential to another file
          target = File.join(state_dir, "target")
          State.write_private_file(target, "redirected\n")
          link = File.join(state_dir, "link.jwt")
          File.symlink(target, link)
          expect_refused("a symlinked credential file was accepted", StateFileError) { State.read_private_file(link) }
        end
        State.check_state_dir(state_dir)

        # the instance id is created once and reused
        instance_id = State.load_or_create_instance_id(state_dir)
        expect(State.parse_uuid(instance_id) == instance_id, "instance id #{instance_id.inspect} is not a uuid")
        again = State.load_or_create_instance_id(state_dir)
        expect(again == instance_id, "instance id changed from #{instance_id.inspect} to #{again.inspect}")

        # the identity belongs to its client
        identity = Identity.new(
          client_id: TEST_PROVIDER_ID,
          client_key_seed: ([1] * 32).pack("C*"),
          provide_tls_certificate_pem: "synthetic certificate".b,
          provide_tls_private_key_pem: "synthetic private key".b,
          extender_key_seed: ([2] * 32).pack("C*"),
        )
        State.save_identity(state_dir, identity)
        loaded = State.load_identity(state_dir, TEST_PROVIDER_ID)
        expect(loaded == identity, "identity round trip failed")
        key_material = State.key_material(loaded)
        expect(!key_material.nil? && key_material.client_key_seed == identity.client_key_seed &&
               key_material.extender_key_seed == identity.extender_key_seed, "identity key material differs")
        other = State.load_identity(state_dir, TEST_CLIENT_A_ID)
        expect(other.nil?, "another client's identity was used")
        # without an identity the device gets no key material and makes a new identity
        expect(State.key_material(other).nil?, "a first run passes key material")
        State.write_private_file(File.join(state_dir, State::IDENTITY_FILE_NAME), '{"version":1}')
        expect_refused("an invalid identity was accepted") { State.load_identity(state_dir, TEST_PROVIDER_ID) }
      end
    end

    # A missing or incomplete installation state is refused; a first run loads.
    def check_provider_config
      expect_refused("a missing state directory was accepted") { State.load_config("") }
      expect_refused("a relative state directory was accepted") { State.load_config(File.join("relative", "state")) }
      with_state_dir do |state_dir|
        expect_refused("a state directory without client.jwt was accepted") { State.load_config(state_dir) }
        # a network jwt has no client_id claim
        network_jwt = self_test_jwt(%({"network_id":"#{TEST_CLIENT_A_ID}"}))
        State.write_private_file(File.join(state_dir, State::CLIENT_JWT_FILE_NAME), "#{network_jwt}\n")
        expect_refused("a network jwt was accepted") { State.load_config(state_dir) }
        client_jwt = self_test_jwt(%({"client_id":"#{TEST_PROVIDER_ID}"}))
        State.write_private_file(File.join(state_dir, State::CLIENT_JWT_FILE_NAME), "#{client_jwt}\n")
        config = State.load_config(state_dir)
        expect(config.client_jwt == client_jwt && config.client_id == TEST_PROVIDER_ID && !config.instance_id.empty? &&
               config.identity.nil?, "first-run configuration differs")
      end
    end

    # An unknown command is a usage error with exit code 78, before anything
    # starts.
    def check_usage_exit_code
      # required here: commands requires this file
      require_relative "commands"
      stderr = $stderr
      $stderr = StringIO.new
      begin
        exit_code = Provider.run(["--unknown"])
      ensure
        $stderr = stderr
      end
      expect(exit_code == EXIT_CONFIG, "usage error exit code #{exit_code}, want #{EXIT_CONFIG}")
    end
  end
end
