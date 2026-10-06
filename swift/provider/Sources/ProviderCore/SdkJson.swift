// The structured values that the SDK's C ABI returns and passes to callbacks:
// JSON with the Go field names (PROVIDER_CONTRACT.md, "App lifecycle").
// Each decoder reads only what the status needs. NULL, JSON null and a value
// that does not decode count as absent, so a status read never fails.

import Foundation

/// ClientLimitStatus. RetryTime is when the hold ends, in unix milliseconds, 0
/// without a hold.
public struct ClientLimitStatus: Decodable, Equatable {
  /// clientLimitStatusNone or clientLimitStatusExceeded
  public var status: String
  public var retryTime: Int64

  /// The Go field names.
  enum CodingKeys: String, CodingKey {
    case status = "Status"
    case retryTime = "RetryTime"
  }

  /// A status with the given fields.
  public init(status: String, retryTime: Int64) {
    self.status = status
    self.retryTime = retryTime
  }

  /// Decodes the fields, each defaulting to no hold.
  public init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    status = try container.decodeIfPresent(String.self, forKey: .status) ?? clientLimitStatusNone
    retryTime = try container.decodeIfPresent(Int64.self, forKey: .retryTime) ?? 0
  }
}

/// The fields of the SDK's ContractDetails that name a provider contract's peer.
public struct ContractDetails: Decodable, Equatable {
  public var contractId: String?
  public var contractTransferPath: TransferPath?

  /// The contract's source and destination clients, and its stream on a
  /// multi-hop path.
  public struct TransferPath: Decodable, Equatable {
    public var sourceId: String?
    public var destinationId: String?
    public var streamId: String?

    /// The Go field names.
    enum CodingKeys: String, CodingKey {
      case sourceId = "SourceId"
      case destinationId = "DestinationId"
      case streamId = "StreamId"
    }

    /// A path with the given ids.
    public init(sourceId: String?, destinationId: String?, streamId: String?) {
      self.sourceId = sourceId
      self.destinationId = destinationId
      self.streamId = streamId
    }
  }

  /// The Go field names.
  enum CodingKeys: String, CodingKey {
    case contractId = "ContractId"
    case contractTransferPath = "ContractTransferPath"
  }

  /// Details with the given contract id and path.
  public init(contractId: String?, contractTransferPath: TransferPath?) {
    self.contractId = contractId
    self.contractTransferPath = contractTransferPath
  }
}

/// The effective payout wallet (SnWallet). clientId and consentScope are ""
/// when the entry has none.
public struct SnWallet: Decodable, Equatable {
  public var coldkeySs58: String
  public var clientId: String
  public var consentScope: String

  /// The JSON field names.
  enum CodingKeys: String, CodingKey {
    case coldkeySs58 = "coldkey_ss58"
    case clientId = "client_id"
    case consentScope = "consent_scope"
  }

  /// A wallet with the given fields.
  public init(coldkeySs58: String, clientId: String, consentScope: String) {
    self.coldkeySs58 = coldkeySs58
    self.clientId = clientId
    self.consentScope = consentScope
  }

  /// Decodes the fields; a missing field is "".
  public init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    coldkeySs58 = try container.decodeIfPresent(String.self, forKey: .coldkeySs58) ?? ""
    clientId = try container.decodeIfPresent(String.self, forKey: .clientId) ?? ""
    consentScope = try container.decodeIfPresent(String.self, forKey: .consentScope) ?? ""
  }
}

/// The PacketStats fields of bytes relayed for clients.
struct ProviderPacketStats: Decodable {
  var remoteEgressByteCount: Int64
  var remoteIngressByteCount: Int64

  /// The Go field names.
  enum CodingKeys: String, CodingKey {
    case remoteEgressByteCount = "RemoteEgressByteCount"
    case remoteIngressByteCount = "RemoteIngressByteCount"
  }

  /// Decodes the two counters; a missing counter is 0.
  init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    remoteEgressByteCount =
      try container.decodeIfPresent(Int64.self, forKey: .remoteEgressByteCount) ?? 0
    remoteIngressByteCount =
      try container.decodeIfPresent(Int64.self, forKey: .remoteIngressByteCount) ?? 0
  }
}

/// Whether a SnGetWalletResult carries an error object.
struct SnGetWalletResult: Decodable {
  var hasError: Bool

  /// The JSON field name.
  enum CodingKeys: String, CodingKey {
    case error
  }

  /// Reads only the presence of a non-null error.
  init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    hasError = container.contains(.error) ? !(try container.decodeNil(forKey: .error)) : false
  }
}

/// One SDK JSON value; nil for NULL, JSON null and a value that does not decode.
func decodeSdkJson<T: Decodable>(_ type: T.Type, _ json: String?) -> T? {
  guard let json else {
    return nil
  }
  let trimmed = json.trimmingCharacters(in: .whitespacesAndNewlines)
  if trimmed.isEmpty || trimmed == "null" {
    return nil
  }
  return try? JSONDecoder().decode(T.self, from: Data(trimmed.utf8))
}

/// The client limit status of urnet_device_get_client_limit_status. NULL, which
/// the call returns only when it cannot run, reads as no limit.
public func decodeClientLimitStatus(_ json: String?) -> ClientLimitStatus {
  return decodeSdkJson(ClientLimitStatus.self, json)
    ?? ClientLimitStatus(status: clientLimitStatusNone, retryTime: 0)
}

/// Data provided, from urnet_device_get_provider_packet_stats: bytes relayed for
/// clients in both directions since the device started; 0 when the stats are
/// null.
public func decodeDataProvidedByteCount(_ json: String?) -> Int64 {
  guard let packetStats = decodeSdkJson(ProviderPacketStats.self, json) else {
    return 0
  }
  let (byteCount, overflow) = packetStats.remoteEgressByteCount.addingReportingOverflow(
    packetStats.remoteIngressByteCount)
  return overflow ? Int64.max : byteCount
}

/// The contract details of a provider ingress or egress listener call.
public func decodeContractDetails(_ json: String?) -> ContractDetails? {
  return decodeSdkJson(ContractDetails.self, json)
}

/// The effective payout wallet of urnet_device_local_get_sn_wallet; nil when no
/// wallet is mapped (NULL, null or an entry without an address).
public func decodeSnWallet(_ json: String?) -> SnWallet? {
  guard let wallet = decodeSdkJson(SnWallet.self, json), !wallet.coldkeySs58.isEmpty else {
    return nil
  }
  return wallet
}

/// Whether a wallet read succeeded, from the arguments of urnet_sn_get_wallet_cb:
/// no error text, and a result that decodes without an error object.
public func snGetWalletSucceeded(resultJson: String?, errorText: String?) -> Bool {
  if errorText != nil {
    return false
  }
  guard let result = decodeSdkJson(SnGetWalletResult.self, resultJson) else {
    return false
  }
  return !result.hasError
}
