// The provider status that every provider example shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. These are plain values and
// functions, so the self-test checks them without a network, credentials or the
// native SDK. ClientsServed is the one type here that is safe for concurrent use.

import Foundation

/// Shown once at start, and in every example's README. The app that integrates
/// a provider owns the consent screen; this example starts without asking.
public let consentDisclaimer = """
  Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
  Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
  This example starts providing without asking, because the consent screen belongs to your app.
  """

/// The SDK's provide modes (URNET_PROVIDE_MODE_* in urnetwork_sdk.h). Kept here
/// so the status rules need no native SDK; the executable's self-test checks
/// them against the header.
public let provideModeNone: Int64 = 0
public let provideModeNetwork: Int64 = 1
public let provideModePublic: Int64 = 3

/// The SDK's client limit status values (URNET_CLIENT_LIMIT_STATUS_*): no hold,
/// or the platform disconnected this client for its network's client limit and
/// the SDK holds off reconnecting until the retry time.
public let clientLimitStatusNone = ""
public let clientLimitStatusExceeded = "client_limit_exceeded"

/// The consent_scope values of a GET /sn/wallet entry. The SDK defines network
/// and provider (URNET_SN_WALLET_CONSENT_SCOPE_*). Hotkey delegations come with a
/// later server and SDK change, which adds the SDK's own constant; the app only
/// labels the entry.
public let snWalletConsentScopeNetwork = "network"
public let snWalletConsentScopeProvider = "provider"
public let snWalletConsentScopeHotkey = "hotkey"

/// The payout wallet before the first wallet read finishes.
public let payoutWalletChecking = "checking"
/// The payout wallet when the first wallet read failed.
public let payoutWalletUnavailable = "unavailable"
/// The payout wallet when no wallet is mapped.
public let payoutWalletNotSet = "not set"

/// The owner of the shown payout wallet, in parentheses after its address.
public let payoutWalletScopeHotkey = "hotkey"
public let payoutWalletScopeProvider = "this provider"
public let payoutWalletScopeNetwork = "network"
public let payoutWalletScopeAnotherProvider = "another provider"

/// Distinct clients are counted up to this many; beyond it the count is a lower
/// bound, shown with a trailing "+".
public let clientsServedLimit = 100 * 1000

let zeroIdString = "00000000-0000-0000-0000-000000000000"

/// The providing state, in the order that providerState checks it.
public enum ProviderState: String {
  case stopped
  /// the platform disconnected this client for its network's client limit, and
  /// the SDK holds off reconnecting until the retry time
  case clientLimit = "client limit"
  case paused
  case providing
  case starting
}

/// One status snapshot.
public struct ProviderStatus: Equatable {
  public var state: ProviderState
  /// the end of the client limit hold in unix milliseconds, shown with the
  /// client limit state; 0 when unknown
  public var clientLimitRetryTime: Int64
  public var clientsServed: Int
  public var clientsServedAtLimit: Bool
  public var dataProvidedByteCount: Int64
  /// a coldkey ss58 address, or payoutWalletChecking, payoutWalletUnavailable or
  /// payoutWalletNotSet
  public var payoutWallet: String
  /// "" unless payoutWallet is an address
  public var payoutWalletScope: String

  /// A snapshot; the defaults are the values before anything was counted.
  public init(
    state: ProviderState, clientLimitRetryTime: Int64 = 0, clientsServed: Int = 0,
    clientsServedAtLimit: Bool = false, dataProvidedByteCount: Int64 = 0, payoutWallet: String,
    payoutWalletScope: String = ""
  ) {
    self.state = state
    self.clientLimitRetryTime = clientLimitRetryTime
    self.clientsServed = clientsServed
    self.clientsServedAtLimit = clientsServedAtLimit
    self.dataProvidedByteCount = dataProvidedByteCount
    self.payoutWallet = payoutWallet
    self.payoutWalletScope = payoutWalletScope
  }

  /// The status line, for example
  /// "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)".
  public var line: String {
    let clientsServedText = clientsServedAtLimit ? "\(clientsServed)+" : "\(clientsServed)"
    let payoutWalletText =
      payoutWalletScope.isEmpty ? payoutWallet : "\(payoutWallet) (\(payoutWalletScope))"
    let statusText = providerStatusText(state, clientLimitRetryTime: clientLimitRetryTime)
    let dataProvidedText = formatByteCount(dataProvidedByteCount)
    return
      "status: \(statusText) | clients served: \(clientsServedText) | data provided: \(dataProvidedText) | payout wallet: \(payoutWalletText)"
  }

  /// The fields that change rarely. A change prints a status line at once; the
  /// data counter alone only prints on the periodic line. The status text
  /// carries the client limit retry time, so a new retry time prints too.
  public var key: String {
    let statusText = providerStatusText(state, clientLimitRetryTime: clientLimitRetryTime)
    return
      "\(statusText)|\(clientsServed)|\(clientsServedAtLimit)|\(payoutWallet)|\(payoutWalletScope)"
  }
}

/// The status field text: the state, and for the client limit state the time
/// the SDK retries, for example "client limit, retry at 19:05 UTC". The retry
/// time (unix milliseconds) is rounded up to the next whole minute in UTC, so
/// the shown time is never before the real retry; a retry time of 0 shows
/// "client limit".
public func providerStatusText(_ state: ProviderState, clientLimitRetryTime: Int64) -> String {
  guard state == .clientLimit, 0 < clientLimitRetryTime else {
    return state.rawValue
  }
  let minuteMillis: Int64 = 60 * 1000
  // rounds up without overflowing near the largest retry time
  let retryMinute =
    clientLimitRetryTime / minuteMillis + (clientLimitRetryTime % minuteMillis == 0 ? 0 : 1)
  let minuteOfDay = retryMinute % (24 * 60)
  let twoDigits = { (value: Int64) -> String in value < 10 ? "0\(value)" : "\(value)" }
  return
    "\(ProviderState.clientLimit.rawValue), retry at \(twoDigits(minuteOfDay / 60)):\(twoDigits(minuteOfDay % 60)) UTC"
}

/// Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". The
/// value rounds to the nearest tenth with ties to even, as Go's %.1f does, so
/// 1280 bytes (1.25 KiB) is "1.2 KiB". A value that rounds to 1024.0 moves to
/// the next unit. The rounding is exact integer arithmetic, because printf's
/// tie rule depends on the C runtime: the Windows one before Windows 10 version
/// 2004 rounds ties up.
public func formatByteCount(_ byteCount: Int64) -> String {
  if byteCount < 1024 {
    return "\(byteCount) B"
  }
  let units = ["KiB", "MiB", "GiB", "TiB", "PiB", "EiB"]
  let roundedTenths = { (unitIndex: Int) -> UInt64 in
    var unitByteCount: UInt64 = 1024
    for _ in 0..<unitIndex {
      unitByteCount *= 1024
    }
    let bytes = UInt64(byteCount)
    // the remainder is below 1024 to the 6th power, so ten times it fits
    let scaledRemainder = bytes % unitByteCount * 10
    var tenths = bytes / unitByteCount * 10 + scaledRemainder / unitByteCount
    let rest = scaledRemainder % unitByteCount
    // up past the half, and at exactly half only to an even last digit
    if unitByteCount < rest * 2 || (unitByteCount == rest * 2 && tenths % 2 == 1) {
      tenths += 1
    }
    return tenths
  }
  var unitIndex = 0
  var tenths = roundedTenths(unitIndex)
  while unitIndex < units.count - 1 && 1024 * 10 <= tenths {
    unitIndex += 1
    tenths = roundedTenths(unitIndex)
  }
  return "\(tenths / 10).\(tenths % 10) \(units[unitIndex])"
}

/// The providing state from the device getters, in this order: stopped unless
/// the provide mode is public; client limit while the SDK holds this client off
/// for its network's client limit; paused while paused; providing once the
/// provider is enabled and its platform carrier is connected; starting
/// otherwise.
public func providerState(
  provideMode: Int64, clientLimitStatus: String, providePaused: Bool, provideEnabled: Bool,
  providerConnected: Bool
) -> ProviderState {
  if provideMode != provideModePublic {
    return .stopped
  }
  if clientLimitStatus == clientLimitStatusExceeded {
    return .clientLimit
  }
  if providePaused {
    return .paused
  }
  if provideEnabled && providerConnected {
    return .providing
  }
  return .starting
}

/// Which owner the effective payout wallet belongs to. The consent scope comes
/// first: a hotkey delegation is network-level, with no client id, but is not
/// the network's wallet. Otherwise by the wallet's client id: this provider's
/// own mapping, the network's wallet, or another provider of the network.
public func payoutWalletScopeOf(
  walletConsentScope: String, walletClientId: String, clientId: String
) -> String {
  if walletConsentScope == snWalletConsentScopeHotkey {
    return payoutWalletScopeHotkey
  }
  if walletClientId.isEmpty {
    return payoutWalletScopeNetwork
  }
  if walletClientId.lowercased() == clientId.lowercased() {
    return payoutWalletScopeProvider
  }
  return payoutWalletScopeAnotherProvider
}

/// The peer of one provider contract, by direction as the SDK's contract
/// screens resolve it: the source of a receive (ingress) contract, the
/// destination of a send (egress) contract. A path without that client id is
/// keyed by its stream id, then by the contract id. "" when the contract names
/// no peer at all.
public func contractPeerKey(_ details: ContractDetails, receive: Bool) -> String {
  // a present id is a valid uuid other than the zero id, in canonical form
  let present = { (id: String?) -> String? in
    guard let id, let uuid = UUID(uuidString: id) else {
      return nil
    }
    let text = uuid.uuidString.lowercased()
    return text == zeroIdString ? nil : text
  }
  if let path = details.contractTransferPath {
    if let peerId = present(receive ? path.sourceId : path.destinationId) {
      return peerId
    }
    if let streamId = present(path.streamId) {
      return "stream:" + streamId
    }
  }
  if let contractId = present(details.contractId) {
    return "contract:" + contractId
  }
  return ""
}

/// The distinct clients that held a contract with this provider since the app
/// started. Safe for concurrent use: the SDK delivers contract details on its
/// own threads, and the status loop reads the count on the app's thread.
public final class ClientsServed {
  private let limit: Int

  private let stateLock = NSLock()
  /// contractPeerKey values
  private var peerKeys = Set<String>()
  private var atLimit = false

  /// An empty count that keeps at most limit distinct peers.
  public init(limit: Int) {
    self.limit = limit
  }

  /// Counts the peer of one provider contract.
  public func add(_ details: ContractDetails, receive: Bool) {
    let peerKey = contractPeerKey(details, receive: receive)
    if peerKey.isEmpty {
      return
    }
    stateLock.lock()
    defer { stateLock.unlock() }
    if peerKeys.contains(peerKey) {
      return
    }
    if limit <= peerKeys.count {
      atLimit = true
      return
    }
    peerKeys.insert(peerKey)
  }

  /// The distinct count, and whether the count stopped at the limit.
  public func count() -> (count: Int, atLimit: Bool) {
    stateLock.lock()
    defer { stateLock.unlock() }
    return (peerKeys.count, atLimit)
  }
}
