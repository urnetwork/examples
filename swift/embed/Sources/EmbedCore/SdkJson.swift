// The structured values that the SDK's C ABI returns: JSON with the Go field
// names (EMBED_CONTRACT.md, "App lifecycle"). Each decoder reads only what the
// status needs. NULL, JSON null and a value that does not decode count as
// absent, so a status read never fails.

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

/// The one field of the SDK's WindowStatus that the status reads.
struct WindowStatus: Decodable {
  var providerStateAdded: Int64?

  /// The Go field name.
  enum CodingKeys: String, CodingKey {
    case providerStateAdded = "ProviderStateAdded"
  }
}

/// Decodes the client limit status JSON. NULL, which the SDK returns only when
/// the call cannot run, and anything that does not decode read as no hold.
public func decodeClientLimitStatus(_ json: String?) -> ClientLimitStatus {
  guard let json,
    let status = try? JSONDecoder().decode(ClientLimitStatus.self, from: Data(json.utf8))
  else {
    return ClientLimitStatus(status: clientLimitStatusNone, retryTime: 0)
  }
  return status
}

/// ProviderStateAdded of the window status JSON; 0 for NULL (no window yet) and
/// for a missing count.
public func decodeProvidersAdded(_ json: String?) -> Int64 {
  guard let json, let status = try? JSONDecoder().decode(WindowStatus.self, from: Data(json.utf8))
  else {
    return 0
  }
  return status.providerStateAdded ?? 0
}
