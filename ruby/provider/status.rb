# frozen_string_literal: true

# The provider status that every provider example shows, with the exact text
# rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
# data provided and the payout wallet, read only. Everything here is plain
# Ruby, so the self-test checks it without the native SDK, a network or
# credentials. ClientsServed is the one stateful class; it is safe for use from
# SDK threads.

require "json"

module Provider
  # Shown once at start, and in every example's README. The app that integrates
  # a provider owns the consent screen; this example starts without asking.
  CONSENT_DISCLAIMER = <<~TEXT.chomp
    Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
    Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
    This example starts providing without asking, because the consent screen belongs to your app.
  TEXT

  # provide modes of the C ABI (URNET_PROVIDE_MODE_NONE, _NETWORK and _PUBLIC)
  PROVIDE_MODE_NONE = 0
  PROVIDE_MODE_NETWORK = 1
  PROVIDE_MODE_PUBLIC = 3

  # client limit status values (URNET_CLIENT_LIMIT_STATUS_NONE and _EXCEEDED)
  CLIENT_LIMIT_STATUS_NONE = ""
  CLIENT_LIMIT_STATUS_EXCEEDED = "client_limit_exceeded"

  # consent_scope values of a GET /sn/wallet entry
  # (URNET_SN_WALLET_CONSENT_SCOPE_NETWORK and _PROVIDER). Hotkey delegations
  # come with a later server and SDK change, which adds the SDK's own constant;
  # the app only labels the entry.
  SN_WALLET_CONSENT_SCOPE_NETWORK = "network"
  SN_WALLET_CONSENT_SCOPE_PROVIDER = "provider"
  SN_WALLET_CONSENT_SCOPE_HOTKEY = "hotkey"

  PROVIDER_STATE_STOPPED = "stopped"
  # the platform disconnected this client for its network's client limit, and
  # the SDK holds off reconnecting until the retry time
  PROVIDER_STATE_CLIENT_LIMIT = "client limit"
  PROVIDER_STATE_STARTING = "starting"
  PROVIDER_STATE_PAUSED = "paused"
  PROVIDER_STATE_PROVIDING = "providing"

  # the payout wallet before the first wallet read finishes
  PAYOUT_WALLET_CHECKING = "checking"
  # the payout wallet when the first wallet read failed
  PAYOUT_WALLET_UNAVAILABLE = "unavailable"
  # the payout wallet when no wallet is mapped
  PAYOUT_WALLET_NOT_SET = "not set"

  PAYOUT_WALLET_SCOPE_HOTKEY = "hotkey"
  PAYOUT_WALLET_SCOPE_PROVIDER = "this provider"
  PAYOUT_WALLET_SCOPE_NETWORK = "network"
  PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER = "another provider"

  # Distinct clients are counted up to this many; beyond it the count is a
  # lower bound, shown with a trailing "+".
  CLIENTS_SERVED_LIMIT = 100 * 1000

  ZERO_ID = "00000000-0000-0000-0000-000000000000"

  BYTE_UNITS = %w[KiB MiB GiB TiB PiB EiB].freeze

  # One status snapshot.
  class Status
    attr_reader :state, :client_limit_retry_time, :clients_served, :clients_served_at_limit,
                :data_provided_byte_count, :payout_wallet, :payout_wallet_scope

    # state is one of the PROVIDER_STATE_* texts; client_limit_retry_time is
    # the end of the client limit hold in unix milliseconds, 0 when unknown;
    # payout_wallet is a coldkey ss58 address or one of the PAYOUT_WALLET_*
    # texts, and payout_wallet_scope is "" unless payout_wallet is an address.
    def initialize(state:, client_limit_retry_time: 0, clients_served: 0, clients_served_at_limit: false,
                   data_provided_byte_count: 0, payout_wallet: PAYOUT_WALLET_CHECKING, payout_wallet_scope: "")
      @state = state
      @client_limit_retry_time = client_limit_retry_time
      @clients_served = clients_served
      @clients_served_at_limit = clients_served_at_limit
      @data_provided_byte_count = data_provided_byte_count
      @payout_wallet = payout_wallet
      @payout_wallet_scope = payout_wallet_scope
    end

    # The status line, for example
    # "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)".
    def line
      clients_served = @clients_served.to_s
      clients_served += "+" if @clients_served_at_limit
      payout_wallet = @payout_wallet_scope.empty? ? @payout_wallet : "#{@payout_wallet} (#{@payout_wallet_scope})"
      status_text = Provider.provider_status_text(@state, @client_limit_retry_time)
      data_provided = Provider.format_byte_count(@data_provided_byte_count)
      "status: #{status_text} | clients served: #{clients_served} | data provided: #{data_provided} | payout wallet: #{payout_wallet}"
    end

    # The fields that change rarely. A change prints a status line at once; the
    # data counter alone only prints on the periodic line. The status text
    # carries the client limit retry time, so a new retry time prints too.
    def key
      [
        Provider.provider_status_text(@state, @client_limit_retry_time),
        @clients_served,
        @clients_served_at_limit,
        @payout_wallet,
        @payout_wallet_scope,
      ]
    end
  end

  # The status field text: the state, and for the client limit state the time
  # the SDK retries, for example "client limit, retry at 19:05 UTC". The retry
  # time (unix milliseconds) is rounded up to the next whole minute in UTC, so
  # the shown time is never before the real retry; 0 shows "client limit".
  def self.provider_status_text(state, client_limit_retry_time)
    return state if state != PROVIDER_STATE_CLIENT_LIMIT || client_limit_retry_time <= 0

    minute_millis = 60 * 1000
    retry_minute = (client_limit_retry_time + minute_millis - 1) / minute_millis
    # unix time has no leap seconds, so every utc day is 24 * 60 minutes
    minute_of_day = retry_minute % (24 * 60)
    format("%s, retry at %02d:%02d UTC", PROVIDER_STATE_CLIENT_LIMIT, minute_of_day / 60, minute_of_day % 60)
  end

  # Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A
  # value that rounds to 1024.0 moves to the next unit.
  def self.format_byte_count(byte_count)
    return "#{byte_count} B" if byte_count < 1024

    value = byte_count / 1024.0
    unit_index = 0
    while unit_index < BYTE_UNITS.length - 1 && 1024 <= (value * 10).round / 10.0
      value /= 1024
      unit_index += 1
    end
    format("%.1f %s", value, BYTE_UNITS[unit_index])
  end

  # The providing state from the device getters, in this order: stopped unless
  # the provide mode is public; client limit while the SDK holds this client
  # off for its network's client limit; paused while paused; providing once the
  # provider is enabled and its platform carrier is connected; starting
  # otherwise.
  def self.provider_state(provide_mode, client_limit_status, provide_paused, provide_enabled, provider_connected)
    return PROVIDER_STATE_STOPPED if provide_mode != PROVIDE_MODE_PUBLIC
    return PROVIDER_STATE_CLIENT_LIMIT if client_limit_status == CLIENT_LIMIT_STATUS_EXCEEDED
    return PROVIDER_STATE_PAUSED if provide_paused
    return PROVIDER_STATE_PROVIDING if provide_enabled && provider_connected

    PROVIDER_STATE_STARTING
  end

  # Which owner the effective payout wallet belongs to. The consent scope comes
  # first: a hotkey delegation is network-level, with no client id, but is not
  # the network's wallet. Otherwise by the wallet's client id: this provider's
  # own mapping, the network's wallet, or another provider of the network.
  def self.payout_wallet_scope(wallet_consent_scope, wallet_client_id, client_id)
    return PAYOUT_WALLET_SCOPE_HOTKEY if wallet_consent_scope == SN_WALLET_CONSENT_SCOPE_HOTKEY
    return PAYOUT_WALLET_SCOPE_NETWORK if wallet_client_id.empty?
    return PAYOUT_WALLET_SCOPE_PROVIDER if wallet_client_id == client_id

    PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER
  end

  # The peer of one provider contract (the C ABI's ContractDetails JSON
  # object), by direction as the SDK's contract screens resolve it: the source
  # of a receive (ingress) contract, the destination of a send (egress)
  # contract. A path without that client id is keyed by its stream id, then by
  # the contract id; "" when the contract names none of them.
  def self.contract_peer_key(details, receive)
    present = ->(value) { value.is_a?(String) && !value.empty? && value != ZERO_ID }
    path = details["ContractTransferPath"]
    if path.is_a?(Hash)
      peer_id = receive ? path["SourceId"] : path["DestinationId"]
      return peer_id if present.call(peer_id)
      return "stream:#{path['StreamId']}" if present.call(path["StreamId"])
    end
    return "contract:#{details['ContractId']}" if present.call(details["ContractId"])

    ""
  end

  # A JSON object from the C ABI, or nil for NULL, JSON null or anything that
  # is not an object.
  def self.parse_json_object(text)
    return nil if text.nil? || text.empty?

    value = JSON.parse(text)
    value.is_a?(Hash) ? value : nil
  rescue JSON::ParserError
    nil
  end

  # A JSON integer field, or 0 when it is missing or not an integer.
  def self.json_int(value)
    value.is_a?(Integer) ? value : 0
  end

  # A JSON string field, or "" when it is missing or not a string.
  def self.json_text(value)
    value.is_a?(String) ? value : ""
  end

  # The client limit status and its retry time (unix milliseconds) from the
  # JSON of urnet_device_get_client_limit_status. The C ABI returns NULL only
  # when the call cannot run, and NULL reads as no limit.
  def self.parse_client_limit_status(status_json)
    status = parse_json_object(status_json)
    return [CLIENT_LIMIT_STATUS_NONE, 0] if status.nil?

    [json_text(status["Status"]), json_int(status["RetryTime"])]
  end

  # Bytes relayed for clients in both directions since the device started,
  # from the JSON of urnet_device_get_provider_packet_stats; 0 when the stats
  # are null.
  def self.parse_data_provided_byte_count(packet_stats_json)
    packet_stats = parse_json_object(packet_stats_json)
    return 0 if packet_stats.nil?

    json_int(packet_stats["RemoteEgressByteCount"]) + json_int(packet_stats["RemoteIngressByteCount"])
  end

  # The distinct clients that held a contract with this provider since the app
  # started. Safe for concurrent use: the SDK delivers contract details on its
  # own threads.
  class ClientsServed
    # An empty count that keeps at most limit distinct peers.
    def initialize(limit)
      @limit = limit
      @state_lock = Mutex.new
      # set of contract_peer_key values, as hash keys
      @peer_keys = {}
      @at_limit = false
    end

    # Counts the peer of one provider contract.
    def add(details, receive)
      return if details.nil?

      peer_key = Provider.contract_peer_key(details, receive)
      return if peer_key.empty?

      @state_lock.synchronize do
        next if @peer_keys.key?(peer_key)

        if @limit <= @peer_keys.length
          @at_limit = true
          next
        end
        @peer_keys[peer_key] = true
      end
    end

    # The distinct count, and whether the count stopped at the limit.
    def count
      @state_lock.synchronize { [@peer_keys.length, @at_limit] }
    end
  end
end
