// The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It checks
// the disclaimer, the status text, the providing state, the clients-served
// count, the SDK's JSON values and the installation state files without a
// network, credentials, a device or the native SDK. `provider --self-test` runs
// every check; `swift test` runs each one as a test.

import Foundation

/// SHA-256 of the consent disclaimer (UTF-8, LF line breaks, no trailing
/// newline), published in PROVIDER_CONTRACT.md for every example to check.
let consentDisclaimerSha256 = "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c"

/// The public Substrate development account, used only as test data.
let selfTestColdkey = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"

/// A failed self-test check.
public struct SelfTestError: Error, CustomStringConvertible {
  public let description: String

  /// A failure with the given text.
  public init(_ description: String) {
    self.description = description
  }
}

/// Every check, in the order --self-test runs them.
public let selfTestChecks: [(name: String, run: () throws -> Void)] = [
  (name: "consent disclaimer", run: checkConsentDisclaimer),
  (name: "byte counts", run: checkFormatByteCount),
  (name: "status text", run: checkStatusText),
  (name: "status lines", run: checkStatusLines),
  (name: "status key", run: checkStatusKey),
  (name: "provider state", run: checkProviderState),
  (name: "payout wallet scope", run: checkPayoutWalletScope),
  (name: "clients served", run: checkClientsServed),
  (name: "sdk json", run: checkSdkJson),
  (name: "client jwt claims", run: checkClientJwtClaims),
  (name: "state files", run: checkStateFiles),
  (name: "provider config", run: checkProviderConfig),
  (name: "usage", run: checkUsage),
]

/// Runs every check and throws the first failure, named by its check.
public func runSelfTest() throws {
  for check in selfTestChecks {
    do {
      try check.run()
    } catch {
      throw SelfTestError("\(check.name): \(error)")
    }
  }
}

/// Whether body throws.
func throwsError(_ body: () throws -> Void) -> Bool {
  do {
    try body()
    return false
  } catch {
    return true
  }
}

/// The disclaimer is the contract's exact text. The digest is first checked
/// with the FIPS 180-2 test vectors, one of them two blocks long.
public func checkConsentDisclaimer() throws {
  let vectors: [(text: String, digest: String)] = [
    (text: "abc", digest: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
    (text: "", digest: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
    (
      text: "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
      digest: "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    ),
  ]
  for vector in vectors {
    let digest = sha256Hex(Array(vector.text.utf8))
    if digest != vector.digest {
      throw SelfTestError("sha-256 of \"\(vector.text)\" is \(digest), want \(vector.digest)")
    }
  }
  if sha256Hex(Array(consentDisclaimer.utf8)) != consentDisclaimerSha256 {
    throw SelfTestError("consent disclaimer differs from PROVIDER_CONTRACT.md")
  }
}

/// Byte counts use binary units with one decimal, rounded with ties to even.
public func checkFormatByteCount() throws {
  let cases: [(byteCount: Int64, text: String)] = [
    (byteCount: 0, text: "0 B"),
    (byteCount: 1023, text: "1023 B"),
    (byteCount: 1024, text: "1.0 KiB"),
    (byteCount: 1536, text: "1.5 KiB"),
    // exact halves: 1.25 KiB and 1.75 KiB round to the even tenth
    (byteCount: 1280, text: "1.2 KiB"),
    (byteCount: 1792, text: "1.8 KiB"),
    // the same rule one unit up: 1.25 MiB
    (byteCount: 1_310_720, text: "1.2 MiB"),
    (byteCount: 1_048_575, text: "1.0 MiB"),
    (byteCount: 13_002_342, text: "12.4 MiB"),
    (byteCount: 5 * 1024 * 1024 * 1024, text: "5.0 GiB"),
    (byteCount: 3 * 1024 * 1024 * 1024 * 1024, text: "3.0 TiB"),
    // the largest count rounds without overflowing
    (byteCount: Int64.max, text: "8.0 EiB"),
  ]
  for c in cases {
    let text = formatByteCount(c.byteCount)
    if text != c.text {
      throw SelfTestError("byte count \(c.byteCount) formats as \"\(text)\", want \"\(c.text)\"")
    }
  }
}

/// The client limit status names the SDK's retry time in UTC, rounded up to the
/// next whole minute; other states never show it.
public func checkStatusText() throws {
  let cases: [(state: ProviderState, clientLimitRetryTime: Int64, text: String)] = [
    // 2026-10-06 19:05:00.000 UTC, exactly on a minute
    (
      state: .clientLimit, clientLimitRetryTime: 1_791_313_500_000,
      text: "client limit, retry at 19:05 UTC"
    ),
    // 19:04:00.001 rounds up, so the shown time is never before the retry
    (
      state: .clientLimit, clientLimitRetryTime: 1_791_313_440_001,
      text: "client limit, retry at 19:05 UTC"
    ),
    // no retry time
    (state: .clientLimit, clientLimitRetryTime: 0, text: "client limit"),
    // 23:59:00.001 rolls over the hour and the day
    (
      state: .clientLimit, clientLimitRetryTime: 1_791_331_140_001,
      text: "client limit, retry at 00:00 UTC"
    ),
    (state: .starting, clientLimitRetryTime: 1_791_313_500_000, text: "starting"),
  ]
  for c in cases {
    let text = providerStatusText(c.state, clientLimitRetryTime: c.clientLimitRetryTime)
    if text != c.text {
      throw SelfTestError(
        "status text \"\(text)\" for \(c.state) retrying at \(c.clientLimitRetryTime), want \"\(c.text)\""
      )
    }
  }
}

/// The status line matches the contract's golden lines.
public func checkStatusLines() throws {
  let cases: [(status: ProviderStatus, line: String)] = [
    (
      status: ProviderStatus(state: .starting, payoutWallet: payoutWalletChecking),
      line: "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking"
    ),
    (
      status: ProviderStatus(
        state: .providing, clientsServed: 3, dataProvidedByteCount: 13_002_342,
        payoutWallet: selfTestColdkey, payoutWalletScope: payoutWalletScopeNetwork),
      line:
        "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)"
    ),
    (
      status: ProviderStatus(
        state: .providing, clientsServed: 3, dataProvidedByteCount: 13_002_342,
        payoutWallet: selfTestColdkey, payoutWalletScope: payoutWalletScopeHotkey),
      line:
        "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (hotkey)"
    ),
    (
      status: ProviderStatus(
        state: .paused, clientsServed: clientsServedLimit, clientsServedAtLimit: true,
        dataProvidedByteCount: 1536, payoutWallet: selfTestColdkey,
        payoutWalletScope: payoutWalletScopeProvider),
      line:
        "status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (this provider)"
    ),
    (
      status: ProviderStatus(state: .stopped, payoutWallet: payoutWalletNotSet),
      line: "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set"
    ),
    (
      status: ProviderStatus(
        state: .clientLimit, clientLimitRetryTime: 1_791_313_500_000, payoutWallet: selfTestColdkey,
        payoutWalletScope: payoutWalletScopeNetwork),
      line:
        "status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)"
    ),
  ]
  for c in cases {
    if c.status.line != c.line {
      throw SelfTestError("status line \"\(c.status.line)\", want \"\(c.line)\"")
    }
  }
}

/// A change of the status text prints a line at once, including a new client
/// limit retry time; the data counter alone does not.
public func checkStatusKey() throws {
  let status = ProviderStatus(
    state: .clientLimit, clientLimitRetryTime: 1_791_313_500_000, payoutWallet: payoutWalletChecking
  )
  var retried = status
  // 19:25 UTC
  retried.clientLimitRetryTime = 1_791_314_700_000
  if status.key == retried.key {
    throw SelfTestError("a new client limit retry time does not print a status line")
  }
  var counted = status
  counted.dataProvidedByteCount = 1536
  if status.key != counted.key {
    throw SelfTestError("the data counter alone prints a status line")
  }
}

/// The providing state follows the provide mode, client limit, pause, enable
/// and connected rules, in that order.
public func checkProviderState() throws {
  let cases:
    [(
      provideMode: Int64, clientLimitStatus: String, providePaused: Bool, provideEnabled: Bool,
      providerConnected: Bool, state: ProviderState
    )] = [
      (provideModeNone, clientLimitStatusNone, false, false, false, .stopped),
      (provideModeNetwork, clientLimitStatusNone, false, true, true, .stopped),
      (provideModePublic, clientLimitStatusNone, false, true, false, .starting),
      (provideModePublic, clientLimitStatusNone, false, false, true, .starting),
      (provideModePublic, clientLimitStatusNone, false, true, true, .providing),
      (provideModePublic, clientLimitStatusNone, true, true, true, .paused),
      // the client limit comes after stopped and before every other state
      (provideModeNetwork, clientLimitStatusExceeded, false, true, true, .stopped),
      (provideModePublic, clientLimitStatusExceeded, true, true, true, .clientLimit),
      (provideModePublic, clientLimitStatusExceeded, false, false, false, .clientLimit),
      (provideModePublic, clientLimitStatusNone, true, true, true, .paused),
    ]
  for c in cases {
    let state = providerState(
      provideMode: c.provideMode, clientLimitStatus: c.clientLimitStatus,
      providePaused: c.providePaused, provideEnabled: c.provideEnabled,
      providerConnected: c.providerConnected)
    if state != c.state {
      throw SelfTestError("provider state \(state) for \(c), want \(c.state)")
    }
  }
}

/// The payout wallet is labeled by its consent scope first, then by the owner of
/// its mapping.
public func checkPayoutWalletScope() throws {
  let clientId = "11111111-1111-1111-1111-111111111111"
  let cases: [(walletConsentScope: String, walletClientId: String, scope: String)] = [
    // a hotkey delegation is network-level but not the network's wallet
    (snWalletConsentScopeHotkey, "", payoutWalletScopeHotkey),
    (snWalletConsentScopeHotkey, clientId, payoutWalletScopeHotkey),
    (snWalletConsentScopeNetwork, "", payoutWalletScopeNetwork),
    ("", "", payoutWalletScopeNetwork),
    (snWalletConsentScopeProvider, clientId, payoutWalletScopeProvider),
    ("", clientId, payoutWalletScopeProvider),
    (
      snWalletConsentScopeProvider, "22222222-2222-2222-2222-222222222222",
      payoutWalletScopeAnotherProvider
    ),
  ]
  for c in cases {
    let scope = payoutWalletScopeOf(
      walletConsentScope: c.walletConsentScope, walletClientId: c.walletClientId, clientId: clientId
    )
    if scope != c.scope {
      throw SelfTestError(
        "payout wallet scope \"\(scope)\" for consent scope \"\(c.walletConsentScope)\" and client \"\(c.walletClientId)\", want \"\(c.scope)\""
      )
    }
  }
}

/// Contract peers resolve by direction and count once per client, up to the
/// limit.
public func checkClientsServed() throws {
  let provider = "11111111-1111-1111-1111-111111111111"
  let clientA = "22222222-2222-2222-2222-222222222222"
  let clientB = "33333333-3333-3333-3333-333333333333"
  let stream = "44444444-4444-4444-4444-444444444444"
  let contract = {
    (contractId: String, sourceId: String?, destinationId: String?, streamId: String?)
      -> ContractDetails in
    ContractDetails(
      contractId: contractId,
      contractTransferPath: ContractDetails.TransferPath(
        sourceId: sourceId, destinationId: destinationId, streamId: streamId))
  }

  // the peer is the source of a receive contract and the destination of a send contract
  let keyCases: [(details: ContractDetails, receive: Bool, peerKey: String)] = [
    (contract("55555555-5555-5555-5555-555555555555", clientA, provider, nil), true, clientA),
    (contract("66666666-6666-6666-6666-666666666666", provider, clientA, nil), false, clientA),
    (
      contract("77777777-7777-7777-7777-777777777777", zeroIdString, provider, stream), true,
      "stream:" + stream
    ),
    (
      ContractDetails(
        contractId: "88888888-8888-8888-8888-888888888888", contractTransferPath: nil),
      true, "contract:88888888-8888-8888-8888-888888888888"
    ),
  ]
  for c in keyCases {
    let peerKey = contractPeerKey(c.details, receive: c.receive)
    if peerKey != c.peerKey {
      throw SelfTestError("contract peer key \"\(peerKey)\", want \"\(c.peerKey)\"")
    }
  }

  // both directions of one client count once
  let served = ClientsServed(limit: 2)
  served.add(
    contract("55555555-5555-5555-5555-555555555555", clientA, provider, nil), receive: true)
  served.add(
    contract("66666666-6666-6666-6666-666666666666", provider, clientA, nil), receive: false)
  served.add(
    contract("99999999-9999-9999-9999-999999999999", clientA, provider, nil), receive: true)
  var (count, atLimit) = served.count()
  if count != 1 || atLimit {
    throw SelfTestError("one client counted as \(count) (at limit \(atLimit))")
  }
  served.add(
    contract("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", clientB, provider, nil), receive: true)
  (count, atLimit) = served.count()
  if count != 2 || atLimit {
    throw SelfTestError("two clients counted as \(count) (at limit \(atLimit))")
  }
  // a third distinct peer reaches the limit of 2
  served.add(
    contract("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", zeroIdString, provider, stream), receive: true)
  (count, atLimit) = served.count()
  if count != 2 || !atLimit {
    throw SelfTestError("limited count \(count) (at limit \(atLimit))")
  }
}

/// The JSON values of the C ABI decode to the status inputs, with NULL and JSON
/// null read as absent.
public func checkSdkJson() throws {
  let clientLimitCases: [(json: String?, status: ClientLimitStatus)] = [
    (
      #"{"Status": "client_limit_exceeded", "RetryTime": 1791313500000}"#,
      ClientLimitStatus(status: clientLimitStatusExceeded, retryTime: 1_791_313_500_000)
    ),
    (
      #"{"Status": "", "RetryTime": 0}"#,
      ClientLimitStatus(status: clientLimitStatusNone, retryTime: 0)
    ),
    // the call could not run
    (nil, ClientLimitStatus(status: clientLimitStatusNone, retryTime: 0)),
  ]
  for c in clientLimitCases {
    let status = decodeClientLimitStatus(c.json)
    if status != c.status {
      throw SelfTestError("client limit status \(status) for \(c.json ?? "NULL")")
    }
  }

  let byteCountCases: [(json: String?, byteCount: Int64)] = [
    (
      #"{"RemoteEgressByteCount": 5, "RemoteIngressByteCount": 7, "LocalEgressByteCount": 100}"#, 12
    ),
    ("null", 0),
    (nil, 0),
  ]
  for c in byteCountCases {
    let byteCount = decodeDataProvidedByteCount(c.json)
    if byteCount != c.byteCount {
      throw SelfTestError("data provided \(byteCount) for \(c.json ?? "NULL"), want \(c.byteCount)")
    }
  }

  let detailsJson = """
    {"ContractId": "33333333-3333-3333-3333-333333333333", "ContractTransferPath": {"SourceId": "22222222-2222-2222-2222-222222222222", "DestinationId": "11111111-1111-1111-1111-111111111111", "StreamId": null}, "Status": "open"}
    """
  guard let details = decodeContractDetails(detailsJson),
    contractPeerKey(details, receive: true) == "22222222-2222-2222-2222-222222222222"
  else {
    throw SelfTestError("contract details json does not resolve to its source client")
  }
  guard
    let unrouted = decodeContractDetails(
      #"{"ContractId": "88888888-8888-8888-8888-888888888888", "ContractTransferPath": null}"#),
    contractPeerKey(unrouted, receive: false) == "contract:88888888-8888-8888-8888-888888888888"
  else {
    throw SelfTestError("contract details json without a path does not resolve to its contract")
  }

  let walletJson = """
    {"coldkey_ss58": "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", "client_id": "11111111-1111-1111-1111-111111111111", "set_at_millis": 1, "consent_scope": "provider"}
    """
  let wallet = decodeSnWallet(walletJson)
  let expectedWallet = SnWallet(
    coldkeySs58: selfTestColdkey, clientId: "11111111-1111-1111-1111-111111111111",
    consentScope: snWalletConsentScopeProvider)
  if wallet != expectedWallet {
    throw SelfTestError("wallet json decodes as \(String(describing: wallet))")
  }
  for json in [nil, "null", #"{"coldkey_ss58": "", "set_at_millis": 1}"#] {
    if decodeSnWallet(json) != nil {
      throw SelfTestError("wallet json \(json ?? "NULL") is a mapped wallet")
    }
  }

  let walletReadCases: [(resultJson: String?, errorText: String?, succeeded: Bool)] = [
    (
      #"{"wallet": {"coldkey_ss58": "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", "set_at_millis": 1}}"#,
      nil, true
    ),
    (#"{"wallet": null, "wallets": null, "error": null}"#, nil, true),
    (#"{"error": {"message": "unavailable"}}"#, nil, false),
    (#"{}"#, "context deadline exceeded", false),
    ("null", nil, false),
    (nil, nil, false),
  ]
  for c in walletReadCases {
    if snGetWalletSucceeded(resultJson: c.resultJson, errorText: c.errorText) != c.succeeded {
      throw SelfTestError(
        "wallet read \(c.resultJson ?? "NULL") with error \(c.errorText ?? "NULL"), want succeeded \(c.succeeded)"
      )
    }
  }
}

/// A synthetic, unsigned JWT with the given payload JSON.
func selfTestJwt(_ payloadJson: String) -> String {
  let payload = Data(payloadJson.utf8).base64EncodedString()
    .replacingOccurrences(of: "+", with: "-")
    .replacingOccurrences(of: "/", with: "_")
    .replacingOccurrences(of: "=", with: "")
  return "e30.\(payload).test"
}

/// Only a JWT with a valid client_id claim is a client credential.
public func checkClientJwtClaims() throws {
  let clientId = try parseClientJwtClientId(
    selfTestJwt(
      #"{"client_id":"11111111-1111-1111-1111-111111111111","network_id":"22222222-2222-2222-2222-222222222222"}"#
    ))
  if clientId != "11111111-1111-1111-1111-111111111111" {
    throw SelfTestError("client jwt claim \"\(clientId)\"")
  }
  let invalidJwts = [
    "",
    "not-a-jwt",
    // a network jwt has no client_id claim
    selfTestJwt(#"{"network_id":"22222222-2222-2222-2222-222222222222"}"#),
    selfTestJwt(#"{"client_id":"not-a-uuid"}"#),
    "e30.%%%.test",
  ]
  for invalidJwt in invalidJwts {
    if !throwsError({ _ = try parseClientJwtClientId(invalidJwt) }) {
      throw SelfTestError("invalid client jwt \"\(invalidJwt)\" accepted")
    }
  }
}

/// A new private temporary directory, which the caller removes.
func makeSelfTestDirectory() throws -> String {
  let path = joinPath(
    NSTemporaryDirectory(), "ur-provider-self-test-\(UUID().uuidString.lowercased())")
  #if os(Windows)
    try FileManager.default.createDirectory(atPath: path, withIntermediateDirectories: false)
  #else
    try FileManager.default.createDirectory(
      atPath: path, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
  #endif
  return path
}

/// State files are private, replaced atomically, created once and bound to
/// their client.
public func checkStateFiles() throws {
  let stateDir = try makeSelfTestDirectory()
  defer { try? FileManager.default.removeItem(atPath: stateDir) }

  // an atomic private write leaves only the file behind
  let path = joinPath(stateDir, clientJwtFileName)
  try writePrivateFile(path, Data("first\n".utf8))
  try writePrivateFile(path, Data("second\n".utf8))
  let data = try readPrivateFile(path)
  if data != Data("second\n".utf8) {
    throw SelfTestError("private file round trip \"\(String(decoding: data, as: UTF8.self))\"")
  }
  let names = try FileManager.default.contentsOfDirectory(atPath: stateDir)
  if names != [clientJwtFileName] {
    throw SelfTestError("the state directory holds \(names.sorted()) after two writes")
  }
  #if !os(Windows)
    let permissions =
      (try FileManager.default.attributesOfItem(atPath: path)[.posixPermissions] as? NSNumber)?
      .intValue ?? -1
    if permissions != 0o600 {
      throw SelfTestError("private file mode \(String(permissions, radix: 8))")
    }
    // a file or directory that others can read is refused, and so is a symlink
    try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: path)
    if !throwsError({ _ = try readPrivateFile(path) }) {
      throw SelfTestError("a group-readable credential file was accepted")
    }
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path)
    let linkPath = joinPath(stateDir, "linked.jwt")
    try FileManager.default.createSymbolicLink(atPath: linkPath, withDestinationPath: path)
    if !throwsError({ _ = try readPrivateFile(linkPath) }) {
      throw SelfTestError("a symlinked credential file was accepted")
    }
    try FileManager.default.removeItem(atPath: linkPath)
    try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: stateDir)
    if !throwsError({ try checkStateDir(stateDir) }) {
      throw SelfTestError("a group-readable state directory was accepted")
    }
    try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: stateDir)
  #endif
  try checkStateDir(stateDir)

  // the instance id is created once and reused
  let instanceId = try loadOrCreateInstanceId(stateDir: stateDir)
  if UUID(uuidString: instanceId) == nil {
    throw SelfTestError("instance id \"\(instanceId)\" is not a uuid")
  }
  let again = try loadOrCreateInstanceId(stateDir: stateDir)
  if again != instanceId {
    throw SelfTestError("instance id changed from \"\(instanceId)\" to \"\(again)\"")
  }

  // the identity belongs to its client, in the same file format as the other examples
  let clientId = "11111111-1111-1111-1111-111111111111"
  let identity = ProviderIdentity(
    version: providerIdentityVersion, clientId: clientId,
    clientKeySeed: Data(repeating: 1, count: 32),
    provideTlsCertificatePem: Data("synthetic certificate".utf8),
    provideTlsPrivateKeyPem: Data("synthetic private key".utf8),
    extenderKeySeed: Data(repeating: 2, count: 32))
  try saveProviderIdentity(stateDir: stateDir, identity: identity)
  let loaded = try loadProviderIdentity(stateDir: stateDir, clientId: clientId)
  if loaded != identity {
    throw SelfTestError("identity round trip failed")
  }
  let identityJson = """
    {"version":1,"client_id":"11111111-1111-1111-1111-111111111111","client_key_seed":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","provide_tls_certificate_pem":"c3ludGhldGljIGNlcnRpZmljYXRl","provide_tls_private_key_pem":"c3ludGhldGljIHByaXZhdGUga2V5","extender_key_seed":"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="}
    """
  try writePrivateFile(joinPath(stateDir, identityFileName), Data(identityJson.utf8))
  if try loadProviderIdentity(stateDir: stateDir, clientId: clientId) != identity {
    throw SelfTestError("an identity.json in the shared format does not load")
  }
  // the identity's key material recreates the device's identity
  let keyMaterial = providerKeyMaterial(loaded)
  if keyMaterial?.clientKeySeed != [UInt8](repeating: 1, count: 32)
    || keyMaterial?.extenderKeySeed != [UInt8](repeating: 2, count: 32)
  {
    throw SelfTestError("identity key material differs")
  }
  let other = try loadProviderIdentity(
    stateDir: stateDir, clientId: "22222222-2222-2222-2222-222222222222")
  if other != nil {
    throw SelfTestError("another client's identity was used")
  }
  // without an identity the device gets no key material and makes a new identity
  if providerKeyMaterial(other) != nil {
    throw SelfTestError("a first run passes key material")
  }
  try writePrivateFile(joinPath(stateDir, identityFileName), Data(#"{"version":1}"#.utf8))
  if !throwsError({ _ = try loadProviderIdentity(stateDir: stateDir, clientId: clientId) }) {
    throw SelfTestError("an invalid identity was accepted")
  }
}

/// A missing or incomplete installation state is refused; a first run loads.
public func checkProviderConfig() throws {
  if !throwsError({ _ = try loadProviderConfig(stateDir: nil) }) {
    throw SelfTestError("a missing state directory was accepted")
  }
  if !throwsError({ _ = try loadProviderConfig(stateDir: "relative/state") }) {
    throw SelfTestError("a relative state directory was accepted")
  }
  let stateDir = try makeSelfTestDirectory()
  defer { try? FileManager.default.removeItem(atPath: stateDir) }
  if !throwsError({ _ = try loadProviderConfig(stateDir: stateDir) }) {
    throw SelfTestError("a state directory without client.jwt was accepted")
  }
  // a network jwt has no client_id claim
  let networkJwt = selfTestJwt(#"{"network_id":"22222222-2222-2222-2222-222222222222"}"#)
  try writePrivateFile(joinPath(stateDir, clientJwtFileName), Data((networkJwt + "\n").utf8))
  if !throwsError({ _ = try loadProviderConfig(stateDir: stateDir) }) {
    throw SelfTestError("a network jwt was accepted")
  }
  let clientJwt = selfTestJwt(#"{"client_id":"11111111-1111-1111-1111-111111111111"}"#)
  try writePrivateFile(joinPath(stateDir, clientJwtFileName), Data((clientJwt + "\n").utf8))
  let config = try loadProviderConfig(stateDir: stateDir)
  if config.clientJwt != clientJwt || config.clientId != "11111111-1111-1111-1111-111111111111"
    || config.instanceId.isEmpty || config.identity != nil
  {
    throw SelfTestError("first-run configuration differs")
  }
}

/// Unknown arguments are a usage error, which exits with the configuration code
/// 78, like every problem a restart does not fix.
public func checkUsage() throws {
  let cases: [(arguments: [String], command: ProviderCommand)] = [
    (arguments: [], command: .run),
    (arguments: ["run"], command: .run),
    (arguments: ["--self-test"], command: .selfTest),
    (arguments: ["--version"], command: .version),
    (arguments: ["--unknown"], command: .usage),
    (arguments: ["run", "--self-test"], command: .usage),
  ]
  for c in cases {
    let command = ProviderCommand(arguments: c.arguments)
    if command != c.command {
      throw SelfTestError("arguments \(c.arguments) are \(command), want \(c.command)")
    }
  }
  if ProviderExitCode.config != 78 {
    throw SelfTestError("the usage exit code is \(ProviderExitCode.config), want 78")
  }
}
