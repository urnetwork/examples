// The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks the
// status text and its rules, the data fields, the cap object, the client JWT
// claim, the token fetch and the cap read against a stand-in server, the
// installation state files and the configuration errors, without a network,
// credentials, a device or the native SDK. `embed --self-test` runs every
// check; `swift test` runs each one as a test. The data is synthetic.

import Foundation

#if canImport(Darwin)
  import Darwin
#elseif canImport(Glibc)
  import Glibc
#elseif canImport(Musl)
  import Musl
#endif

/// A failed self-test check.
public struct SelfTestError: Error, CustomStringConvertible {
  public let description: String

  /// A failure with the given text.
  public init(_ description: String) {
    self.description = description
  }
}

let clientId1 = "11111111-1111-1111-1111-111111111111"
let clientId2 = "22222222-2222-2222-2222-222222222222"
let testTokenServerUrl = "http://127.0.0.1:8790"
let testDemoSession = "demo-session-0123456789abcdef0123456789"
let testApiUrl = "http://127.0.0.1:8791"

/// A JWT with the given payload; the header and signature are placeholders.
func selfTestJwt(_ payloadJson: String) -> String {
  let payload = Data(payloadJson.utf8).base64EncodedString()
    .replacingOccurrences(of: "+", with: "-")
    .replacingOccurrences(of: "/", with: "_")
    .replacingOccurrences(of: "=", with: "")
  return "e30.\(payload).sig"
}

let clientJwt1 = selfTestJwt(#"{"client_id":"11111111-1111-1111-1111-111111111111"}"#)
let networkJwt = selfTestJwt(#"{"network_id":"33333333-3333-3333-3333-333333333333"}"#)

/// Throws the message unless ok.
func expect(_ ok: Bool, _ message: @autoclosure () -> String) throws {
  if !ok {
    throw SelfTestError(message())
  }
}

/// Whether the body throws.
func throwsError(_ body: () throws -> Void) -> Bool {
  do {
    try body()
    return false
  } catch {
    return true
  }
}

/// A server that gives its answers in order and keeps the requests; a nil
/// answer is no answer at all, as when the server is unreachable.
final class StandIn {
  var answers: [HttpResponse?]
  var requests: [HttpRequest] = []
  private var next = 0

  /// A stand-in with the given answers.
  init(_ answers: [HttpResponse?]) {
    self.answers = answers
  }

  /// The stand-in's HTTP function.
  func http(_ request: HttpRequest) throws -> HttpResponse {
    requests.append(request)
    if next >= answers.count {
      throw HttpError("the stand-in has no more answers")
    }
    let answer = answers[next]
    next += 1
    guard let answer else {
      throw HttpError("connection refused")
    }
    return answer
  }
}

/// An answer with a text body.
func answer(_ status: Int, _ body: String) -> HttpResponse {
  return HttpResponse(status: status, body: Data(body.utf8))
}

/// A new private temporary directory, which the caller removes.
func makeSelfTestDirectory() throws -> String {
  let path = joinPath(
    NSTemporaryDirectory(), "ur-embed-self-test-\(UUID().uuidString.lowercased())")
  #if os(Windows)
    try FileManager.default.createDirectory(atPath: path, withIntermediateDirectories: false)
  #else
    try FileManager.default.createDirectory(
      atPath: path, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
  #endif
  return path
}

/// The text of a file, or "".
func readText(_ path: String) -> String {
  return (FileManager.default.contents(atPath: path)).map { String(decoding: $0, as: UTF8.self) }
    ?? ""
}

/// Writes a file with the given POSIX permissions.
func writeText(_ path: String, _ text: String, permissions: Int) {
  FileManager.default.createFile(
    atPath: path, contents: Data(text.utf8), attributes: [.posixPermissions: permissions])
  #if !os(Windows)
    chmod(path, mode_t(permissions))
  #endif
}

/// A cap reading with the given caps.
func reading(
  monthly: Int64?, monthlyUsed: Int64 = 0, total: Int64? = nil, totalUsed: Int64 = 0,
  capped: Bool = false, reason: String = "", periodEnd: String = ""
) -> Caps {
  var caps = Caps()
  caps.apply(
    Cap(
      monthlyByteLimit: monthly, monthlyUsedByteCount: monthlyUsed, monthlyPeriodEnd: periodEnd,
      totalByteLimit: total, totalUsedByteCount: totalUsed, capped: capped, cappedReason: reason))
  return caps
}

/// Every check, in the order --self-test runs them.
public let selfTestChecks: [(name: String, run: () throws -> Void)] = [
  (name: "byte counts", run: checkFormatByteCount),
  (name: "reset text", run: checkResetText),
  (name: "client limit text", run: checkClientLimitText),
  (name: "status lines", run: checkStatusLines),
  (name: "status rules", run: checkStatusRules),
  (name: "data fields", run: checkDataFields),
  (name: "cap object", run: checkCapObject),
  (name: "sdk json", run: checkSdkJson),
  (name: "client jwt claims", run: checkClientJwtClaims),
  (name: "origin url", run: checkOriginUrl),
  (name: "token fetch", run: checkTokenFetch),
  (name: "cap read", run: checkCapRead),
  (name: "state files", run: checkStateFiles),
  (name: "configuration", run: checkConfiguration),
  (name: "usage", run: checkUsage),
  (name: "start line", run: checkStartLine),
]

/// The start line names the client and the installation, and --licenses names
/// the license kind of the platform it was built for.
public func checkStartLine() throws {
  try expect(
    startLine(clientId: "11111111-1111-1111-1111-111111111111", instanceId: "22222222-2222-2222-2222-222222222222")
      == "embed client 11111111-1111-1111-1111-111111111111, installation 22222222-2222-2222-2222-222222222222",
    "the start line differs")
  #if os(Windows)
    try expect(licenseApp == "windows", "the license app kind is not windows")
  #elseif canImport(Darwin)
    try expect(licenseApp == "apple", "the license app kind is not apple")
  #else
    try expect(licenseApp == "linux", "the license app kind is not linux")
  #endif
}

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

/// Data amounts use decimal units with one decimal, ties to even on the exact
/// value: 1050 bytes is exactly 1.05 kB and shows "1.0 kB".
public func checkFormatByteCount() throws {
  let cases: [(Int64, String)] = [
    (0, "0 B"), (999, "999 B"), (1000, "1.0 kB"), (999949, "999.9 kB"), (1050, "1.0 kB"),
    (1150, "1.2 kB"), (1250, "1.2 kB"), (1750, "1.8 kB"), (999950, "1.0 MB"), (999999, "1.0 MB"), (1234567890, "1.2 GB"), (5000000000, "5.0 GB"),
    (10000000000, "10.0 GB"), (3000000000000, "3.0 TB"), (Int64.max, "9.2 EB"),
  ]
  for (byteCount, text) in cases {
    try expect(
      formatByteCount(byteCount) == text,
      "byte count \(byteCount) shows \"\(formatByteCount(byteCount))\", want \"\(text)\"")
  }
}

/// The monthly reset time is in UTC, rounded up to the next minute.
public func checkResetText() throws {
  let cases: [(String, String)] = [
    ("2026-11-01T00:00:00Z", "resets 2026-11-01 00:00 UTC"),
    ("2026-10-31T23:59:00.001Z", "resets 2026-11-01 00:00 UTC"),
    ("2026-10-31T19:00:00-05:00", "resets 2026-11-01 00:00 UTC"),
    ("2026-12-31T23:59:30Z", "resets 2027-01-01 00:00 UTC"),
    ("2028-02-29T12:00:00+01:00", "resets 2028-02-29 11:00 UTC"),
    ("2026-11-01T00:00:00.000Z", "resets 2026-11-01 00:00 UTC"),
  ]
  for (periodEnd, text) in cases {
    try expect(resetText(periodEnd) == text, "period end \(periodEnd) shows another reset text")
  }
  for invalid in [
    "", "soon", "2026-11-01", "2026-11-01T00:00:00", "2026-13-01T00:00:00Z",
    "2027-02-29T00:00:00Z", "2026-11-01T24:00:00Z", "2026-11-01T00:00:00+0500",
    "2026-11-01T00:00:00.Z", "2026-11-01T00:00:00Zjunk",
  ] {
    try expect(resetText(invalid) == nil, "period end \"\(invalid)\" parsed")
  }
}

/// The client limit text names the rounded-up retry time.
public func checkClientLimitText() throws {
  try expect(
    clientLimitText(retryTime: 1_791_313_500_000) == "client limit, retry at 19:05 UTC",
    "retry 1791313500000")
  try expect(
    clientLimitText(retryTime: 1_791_313_440_001) == "client limit, retry at 19:05 UTC",
    "retry 1791313440001")
  try expect(clientLimitText(retryTime: 0) == "client limit", "retry 0")
}

/// The console status line matches the contract's golden lines.
public func checkStatusLines() throws {
  var unavailable = Caps()
  unavailable.apply(nil)
  // the first reading answers the Embed-not-enabled refusal; and a capped
  // monthly reading, then the refusal
  var refused = Caps()
  refused.clear()
  var cleared = reading(
    monthly: 5_000_000_000, monthlyUsed: 5_000_000_000, capped: true, reason: "monthly",
    periodEnd: "2026-11-01T00:00:00Z")
  cleared.clear()
  let cases: [(StatusInput, String)] = [
    (
      StatusInput(caps: Caps(), providersAdded: 0),
      "status: connecting | data this month: checking | data total: checking"
    ),
    (
      StatusInput(caps: reading(monthly: 5_000_000_000, monthlyUsed: 1_234_567_890), providersAdded: 1),
      "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap"
    ),
    (
      StatusInput(caps: unavailable, providersAdded: 1),
      "status: connected | data this month: unavailable | data total: unavailable"
    ),
    (
      StatusInput(caps: refused, providersAdded: 1),
      "status: connected | data this month: unavailable | data total: unavailable"
    ),
    (
      StatusInput(caps: cleared, providersAdded: 1),
      "status: connected | data this month: unavailable | data total: unavailable"
    ),
    (
      StatusInput(
        caps: reading(
          monthly: 5_000_000_000, monthlyUsed: 5_000_000_000, capped: true, reason: "monthly",
          periodEnd: "2026-11-01T00:00:00Z"), providersAdded: 1),
      "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap"
    ),
    (
      StatusInput(
        caps: reading(
          monthly: nil, total: 10_000_000_000, totalUsed: 10_000_000_000, capped: true,
          reason: "total"), providersAdded: 1),
      "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB"
    ),
    (
      StatusInput(caps: reading(monthly: 0, capped: true, reason: "monthly"), providersAdded: 1),
      "status: paused | data this month: 0 B of 0 B | data total: no cap"
    ),
    (
      StatusInput(
        clientLimitStatus: clientLimitStatusExceeded, clientLimitRetryTime: 1_791_313_500_000,
        caps: reading(monthly: 5_000_000_000), providersAdded: 1),
      "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap"
    ),
  ]
  for (index, (input, line)) in cases.enumerated() {
    try expect(
      statusLine(input) == line,
      "status line case \(index) is \"\(statusLine(input))\", want \"\(line)\"")
  }
}

/// The status rules apply in the contract's order.
public func checkStatusRules() throws {
  let cases: [(StatusInput, String)] = [
    (StatusInput(started: false), "stopped"),
    (StatusInput(started: false, signedOut: true), "signed out"),
    (
      StatusInput(
        clientLimitStatus: clientLimitStatusExceeded, clientLimitRetryTime: 1_791_313_500_000,
        caps: reading(monthly: 0, capped: true, reason: "monthly"), providersAdded: 3),
      "client limit, retry at 19:05 UTC"
    ),
    (StatusInput(caps: reading(monthly: 0, capped: true, reason: "monthly"), providersAdded: 3), "paused"),
    (
      StatusInput(
        caps: reading(monthly: 5_000_000_000, total: 0, capped: true, reason: "total"),
        providersAdded: 3), "paused"
    ),
    (
      StatusInput(
        caps: reading(
          monthly: 5_000_000_000, monthlyUsed: 5_000_000_000, capped: true, reason: "monthly",
          periodEnd: "2026-11-01T00:00:00Z"), providersAdded: 3),
      "data cap reached, resets 2026-11-01 00:00 UTC"
    ),
    (
      StatusInput(
        caps: reading(
          monthly: nil, total: 10_000_000_000, totalUsed: 10_000_000_000, capped: true,
          reason: "total"), providersAdded: 0), "data cap reached"
    ),
    (StatusInput(caps: reading(monthly: 5_000_000_000), providersAdded: 1), "connected"),
    (StatusInput(caps: Caps(), providersAdded: 0), "connecting"),
  ]
  for (index, (input, status)) in cases.enumerated() {
    try expect(
      statusText(input) == status,
      "status rule case \(index) is \"\(statusText(input))\", want \"\(status)\"")
  }
}

/// The data fields: checking, unavailable, a later failure keeping the last
/// value, no cap for a null limit even with a used count, and used of limit.
public func checkDataFields() throws {
  var caps = Caps()
  try expect(dataField(caps, monthly: true) == "checking", "before a reading")
  caps.apply(nil)
  try expect(dataField(caps, monthly: false) == "unavailable", "after a failed first reading")
  let cap = parseCap(
    Data(
      #"{"monthly_byte_limit":null,"monthly_used_byte_count":500,"total_byte_limit":2000,"total_used_byte_count":1250}"#
        .utf8))
  try expect(cap != nil, "a cap object did not parse")
  caps.apply(cap)
  try expect(dataField(caps, monthly: true) == "no cap", "a null limit with a used count")
  try expect(dataField(caps, monthly: false) == "1.2 kB of 2.0 kB", "used of limit")
  caps.apply(nil)
  try expect(dataField(caps, monthly: false) == "1.2 kB of 2.0 kB", "a later failure")
  caps.clear()
  try expect(
    dataField(caps, monthly: true) == "unavailable" && dataField(caps, monthly: false) == "unavailable",
    "the Embed-not-enabled refusal did not clear the last reading")
  // the outcome of a cap read: a reading replaces, another failure keeps it, and the
  // Embed-not-enabled refusal clears it
  var recorded = Caps()
  recorded.record(cap, notEnabled: false)
  recorded.record(nil, notEnabled: false)
  try expect(recorded.cap == cap, "a failed cap read did not keep the reading")
  recorded.record(nil, notEnabled: true)
  try expect(recorded.state == .unavailable, "the Embed-not-enabled cap read did not clear the reading")
}

/// The cap object: null or absent limits, capped and its reason, an unknown
/// reason read as capped without a reset time, and refused answers.
public func checkCapObject() throws {
  let full = parseCap(
    Data(
      #"{"client_id":"11111111-1111-1111-1111-111111111111","monthly_byte_limit":5000000000,"monthly_used_byte_count":1234567890,"monthly_period_start":"2026-10-01T00:00:00Z","monthly_period_end":"2026-11-01T00:00:00Z","total_byte_limit":null,"total_used_byte_count":99,"total_period_start":"2026-09-15T00:00:00Z","capped":false,"capped_reason":""}"#
        .utf8))
  try expect(
    full
      == Cap(
        monthlyByteLimit: 5_000_000_000, monthlyUsedByteCount: 1_234_567_890,
        monthlyPeriodEnd: "2026-11-01T00:00:00Z", totalByteLimit: nil, totalUsedByteCount: 99,
        capped: false, cappedReason: ""),
    "the full cap object reads differently")
  try expect(
    parseCap(Data(#"{"capped":false}"#.utf8)) == Cap(), "absent limits read as caps")
  // an unknown reason is capped, without a reset time and without a pause
  let unknown = parseCap(
    Data(
      #"{"monthly_byte_limit":0,"capped":true,"capped_reason":"weekly","monthly_period_end":"2026-11-01T00:00:00Z"}"#
        .utf8))
  try expect(unknown?.capped == true, "an unknown reason did not read as capped")
  var input = StatusInput(providersAdded: 1)
  input.caps.apply(unknown)
  try expect(statusText(input) == "data cap reached", "an unknown reason shows \(statusText(input))")
  // a monthly cap whose period end does not parse
  input.caps.apply(
    parseCap(
      Data(
        #"{"monthly_byte_limit":5,"monthly_used_byte_count":5,"monthly_period_end":"soon","capped":true,"capped_reason":"monthly"}"#
          .utf8)))
  try expect(
    statusText(input) == "data cap reached", "an unreadable period end shows \(statusText(input))")
  for refused in [
    #"{"error":{"message":"no"}}"#, #"{"monthly_byte_limit":"5"}"#,
    #"{"monthly_used_byte_count":1.5}"#, #"{"capped":"yes"}"#, #"{"capped_reason":7}"#,
    #"{"total_byte_limit":9223372036854775808}"#, "[]", "null", "not json",
  ] {
    try expect(
      parseCap(Data(refused.utf8)) == nil && !capNotEnabled(Data(refused.utf8)),
      "\(refused) read as a cap object")
  }
  // the Embed-not-enabled refusal is no cap object, and is recognized
  let notEnabled = Data(#"{"error":{"message":"Embed isn't enabled for this network."}}"#.utf8)
  try expect(
    parseCap(notEnabled) == nil && capNotEnabled(notEnabled), "the Embed-not-enabled refusal misread")
}

/// The C ABI's JSON values decode to the status inputs; NULL and junk are
/// absent.
public func checkSdkJson() throws {
  try expect(
    decodeClientLimitStatus(#"{"Status":"client_limit_exceeded","RetryTime":1791313500000}"#)
      == ClientLimitStatus(status: clientLimitStatusExceeded, retryTime: 1_791_313_500_000),
    "the client limit status decodes differently")
  try expect(
    decodeClientLimitStatus(nil) == ClientLimitStatus(status: "", retryTime: 0),
    "a NULL client limit status is not no hold")
  try expect(
    decodeProvidersAdded(#"{"ProviderStateAdded":3,"TargetSize":8}"#) == 3,
    "the window status decodes differently")
  try expect(decodeProvidersAdded(nil) == 0, "a NULL window status is not 0 providers")
  try expect(decodeProvidersAdded("not json") == 0, "a bad window status is not 0 providers")
}

/// Only a JWT with a valid client_id claim is a client credential.
public func checkClientJwtClaims() throws {
  try expect(try parseClientJwtClientId(clientJwt1) == clientId1, "a client JWT was refused")
  try expect(
    try parseClientJwtClientId(
      selfTestJwt(#"{"client_id":"AAAAAAAA-1111-1111-1111-111111111111"}"#))
      == "aaaaaaaa-1111-1111-1111-111111111111", "an uppercase client id is not canonical")
  for refused in [
    networkJwt, selfTestJwt(#"{"client_id":"not-a-uuid"}"#), "", "not-a-jwt", "a..b", "a.b.c.d",
    "e30.!!!.sig", "e30.eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ.",
  ] {
    try expect(throwsError { _ = try parseClientJwtClientId(refused) }, "JWT \"\(refused)\" was accepted")
  }
}

/// Origins follow the allocators' rules.
public func checkOriginUrl() throws {
  let accepted: [(String, String)] = [
    ("https://api.bringyour.com", "https://api.bringyour.com/x"),
    ("https://api.bringyour.com/", "https://api.bringyour.com/x"),
    ("https://example.com:8443", "https://example.com:8443/x"),
    ("http://localhost:8790", "http://localhost:8790/x"),
    ("http://127.0.0.1", "http://127.0.0.1/x"),
    ("http://[::1]:8790", "http://[::1]:8790/x"),
    ("https://[2001:db8::1]", "https://[2001:db8::1]/x"),
  ]
  for (origin, url) in accepted {
    try expect(originUrl(origin, path: "/x") == url, "origin \(origin) was refused or changed")
  }
  for refused in [
    "http://example.com", "https://example.com/path", "https://user@example.com",
    "https://example.com?x=1", "https://example.com#f", "ftp://example.com", "https://",
    "https://exa mple.com", "https://example.com:0", "https://example.com:99999",
    "https://example.com:", "localhost:8790", "http://127.0.0.2", "http://[::2]",
  ] {
    try expect(originUrl(refused, path: "/x") == nil, "origin \(refused) was accepted")
  }
}

/// The settings for a state directory with the token server.
func tokenSettings(_ stateDir: String) -> EmbedSettings {
  return EmbedSettings(
    stateDir: stateDir, tokenServerUrl: testTokenServerUrl, demoSession: testDemoSession,
    apiUrl: testApiUrl)
}

/// The token fetch against a stand-in server: the request, saving client.jwt,
/// a mismatched client refused, and the answers mapped to exit codes.
public func checkTokenFetch() throws {
  let stateDir = try makeSelfTestDirectory()
  defer { try? FileManager.default.removeItem(atPath: stateDir) }
  let jwtPath = joinPath(stateDir, clientJwtFileName)
  let server = StandIn([
    answer(
      200,
      #"{"client_id":"\#(clientId1)","by_client_jwt":"\#(clientJwt1)","data_cap":{"monthly_byte_limit":5000000000,"monthly_used_byte_count":1234567890,"capped":false}}"#
    )
  ])
  guard case .loaded(let config) = loadEmbedConfig(settings: tokenSettings(stateDir), http: server.http)
  else {
    throw SelfTestError("a token answer did not load")
  }
  try expect(server.requests.count == 1, "the token server got another number of requests")
  let request = server.requests[0]
  let instanceId = readText(joinPath(stateDir, instanceIdFileName)).trimmingCharacters(
    in: .whitespacesAndNewlines)
  let body = request.body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
  try expect(
    request.method == "POST" && request.url == "\(testTokenServerUrl)/urnetwork/client-token"
      && request.authorization == testDemoSession && body?.count == 1
      && body?["installation_id"] as? String == instanceId && instanceId == config.instanceId,
    "the token request is not the contract's")
  try expect(
    readText(jwtPath) == clientJwt1 + "\n" && config.clientId == clientId1
      && config.clientJwt == clientJwt1 && config.firstCap?.monthlyByteLimit == 5_000_000_000,
    "the token answer was not saved or kept")
  #if !os(Windows)
    let attributes = try FileManager.default.attributesOfItem(atPath: jwtPath)
    try expect(
      ((attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0o777) & 0o077 == 0,
      "client.jwt is not private")
    try expect(
      try FileManager.default.contentsOfDirectory(atPath: stateDir).count == 2,
      "the state directory has leftover files")
  #endif
  // answers that are refusals (78) or failures (1); client.jwt stays
  let answers: [(HttpResponse?, Int32)] = [
    (
      answer(
        200, #"{"client_id":"\#(clientId2)","by_client_jwt":"\#(clientJwt1)","data_cap":null}"#),
      EmbedExitCode.failure
    ),
    (answer(200, #"{"client_id":"\#(clientId1)","by_client_jwt":"\#(networkJwt)"}"#), EmbedExitCode.failure),
    (answer(200, "not json"), EmbedExitCode.failure),
    (
      answer(401, #"{"error":{"code":"unauthorized","message":"unknown demo session"}}"#),
      EmbedExitCode.config
    ),
    (
      answer(409, #"{"error":{"code":"installation_limit","message":"this user has 5 installations"}}"#),
      EmbedExitCode.config
    ),
    (
      answer(
        409,
        #"{"error":{"code":"client_limit","message":"the network is at its client limit; see https://ur.io/services"}}"#
      ), EmbedExitCode.config
    ),
    (answer(503, #"{"error":{"code":"busy","message":"retry"}}"#), EmbedExitCode.failure),
    (answer(500, ""), EmbedExitCode.failure),
    (answer(400, #"{"error":{"code":"invalid_request"}}"#), EmbedExitCode.failure),
    (nil, EmbedExitCode.failure),
  ]
  for (index, (response, code)) in answers.enumerated() {
    let refusing = StandIn([response])
    guard
      case .failed(let exitCode, let error) = loadEmbedConfig(
        settings: tokenSettings(stateDir), http: refusing.http)
    else {
      throw SelfTestError("token answer case \(index) loaded")
    }
    try expect(exitCode == code, "token answer case \(index) gave exit \(exitCode), want \(code)")
    if index == 3 {
      try expect(error.contains("unknown demo session"), "a refusal does not show its message")
    }
    try expect(readText(jwtPath) == clientJwt1 + "\n", "token answer case \(index) replaced client.jwt")
  }
  // a null data_cap is no first reading
  let noCap = StandIn([
    answer(200, #"{"client_id":"\#(clientId1)","by_client_jwt":"\#(clientJwt1)","data_cap":null}"#)
  ])
  guard case .loaded(let noCapConfig) = loadEmbedConfig(settings: tokenSettings(stateDir), http: noCap.http),
    noCapConfig.firstCap == nil
  else {
    throw SelfTestError("a null data_cap gave a first reading")
  }
}

/// The cap read: the client JWT as the bearer, and failed readings.
public func checkCapRead() throws {
  let server = StandIn([
    answer(
      200,
      #"{"client_id":"\#(clientId1)","monthly_byte_limit":0,"monthly_used_byte_count":0,"capped":true,"capped_reason":"monthly"}"#
    )
  ])
  let cap = try readCaps(http: server.http, apiUrl: testApiUrl, clientJwt: clientJwt1)
  try expect(cap.capped && cap.cappedReason == "monthly", "a cap answer was not read")
  let request = server.requests[0]
  try expect(
    request.method == "GET" && request.url == "\(testApiUrl)/network/client-data-cap"
      && request.authorization == clientJwt1 && request.body == nil,
    "the cap request is not the contract's")
  let failed: [HttpResponse?] = [
    answer(404, "404 page not found"), answer(200, #"{"error":{"message":"no such client"}}"#),
    answer(401, ""), nil,
  ]
  for (index, response) in failed.enumerated() {
    let failing = StandIn([response])
    var failure: Error?
    do {
      _ = try readCaps(http: failing.http, apiUrl: testApiUrl, clientJwt: clientJwt1)
    } catch {
      failure = error
    }
    try expect(failure is CapReadError, "failed cap answer \(index) was read")
  }
  let refusing = StandIn([answer(200, #"{"error":{"message":"Embed isn't enabled for this network."}}"#)])
  var refusal: Error?
  do {
    _ = try readCaps(http: refusing.http, apiUrl: testApiUrl, clientJwt: clientJwt1)
  } catch {
    refusal = error
  }
  try expect(
    refusal is EmbedNotEnabledError && "\(refusal!)" == embedNotEnabledMessage,
    "the Embed-not-enabled refusal read as \(String(describing: refusal))")
}

/// State files are private, atomic and created once; a symlink is refused.
public func checkStateFiles() throws {
  let stateDir = try makeSelfTestDirectory()
  defer { try? FileManager.default.removeItem(atPath: stateDir) }
  try checkStateDir(stateDir)
  let path = joinPath(stateDir, "file")
  try writePrivateFile(path, Data("one".utf8))
  try writePrivateFile(path, Data("two".utf8))
  try expect(try readPrivateFile(path) == Data("two".utf8), "a state file was not replaced")
  let first = try loadOrCreateInstanceId(stateDir: stateDir)
  try expect(
    try loadOrCreateInstanceId(stateDir: stateDir) == first,
    "instance-id was not created once and reused")
  #if !os(Windows)
    let attributes = try FileManager.default.attributesOfItem(atPath: path)
    try expect(
      ((attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0o777) & 0o077 == 0,
      "a state file is not private")
    try expect(
      try FileManager.default.contentsOfDirectory(atPath: stateDir).count == 2,
      "a replacement left a temporary file")
    let shared = joinPath(stateDir, "shared")
    writeText(shared, "x", permissions: 0o644)
    try expect(throwsError { _ = try readPrivateFile(shared) }, "a file open to others was read")
    let link = joinPath(stateDir, "link")
    try FileManager.default.createSymbolicLink(atPath: link, withDestinationPath: path)
    try expect(throwsError { _ = try readPrivateFile(link) }, "a symlinked state file was read")
    chmod(stateDir, 0o755)
    let sharedDirAccepted = !throwsError { try checkStateDir(stateDir) }
    chmod(stateDir, 0o700)
    try expect(!sharedDirAccepted, "a state directory open to others was accepted")
  #endif
  let missing = joinPath(stateDir, "missing")
  do {
    _ = try readPrivateFile(missing)
    throw SelfTestError("a missing file was read")
  } catch let error as StateFileError {
    try expect(error.notFound, "a missing file did not read as missing")
  }
}

/// A missing or relative state directory, no token server and no client.jwt, a
/// network JWT and invalid settings are configuration errors (78).
public func checkConfiguration() throws {
  let stateDir = try makeSelfTestDirectory()
  defer { try? FileManager.default.removeItem(atPath: stateDir) }
  let none = StandIn([])
  let refused: [EmbedSettings] = [
    EmbedSettings(stateDir: nil, tokenServerUrl: nil, demoSession: nil, apiUrl: nil),
    EmbedSettings(stateDir: "", tokenServerUrl: nil, demoSession: nil, apiUrl: nil),
    EmbedSettings(stateDir: "relative/state", tokenServerUrl: nil, demoSession: nil, apiUrl: nil),
    // no token server and no client.jwt
    EmbedSettings(stateDir: stateDir, tokenServerUrl: nil, demoSession: nil, apiUrl: nil),
    EmbedSettings(stateDir: stateDir, tokenServerUrl: testTokenServerUrl, demoSession: nil, apiUrl: nil),
    EmbedSettings(stateDir: stateDir, tokenServerUrl: nil, demoSession: testDemoSession, apiUrl: nil),
    EmbedSettings(
      stateDir: stateDir, tokenServerUrl: "http://example.com", demoSession: testDemoSession,
      apiUrl: nil),
    EmbedSettings(
      stateDir: stateDir, tokenServerUrl: testTokenServerUrl, demoSession: "has space", apiUrl: nil),
    EmbedSettings(
      stateDir: stateDir, tokenServerUrl: nil, demoSession: nil, apiUrl: "https://example.com/path"),
  ]
  for (index, settings) in refused.enumerated() {
    guard case .failed(let exitCode, _) = loadEmbedConfig(settings: settings, http: none.http) else {
      throw SelfTestError("configuration case \(index) loaded")
    }
    try expect(
      exitCode == EmbedExitCode.config,
      "configuration case \(index) gave exit \(exitCode), want 78")
  }
  try expect(none.requests.isEmpty, "a configuration error reached the token server")
  let fromFile = EmbedSettings(stateDir: stateDir, tokenServerUrl: nil, demoSession: nil, apiUrl: nil)
  try saveClientJwt(stateDir: stateDir, clientJwt: networkJwt)
  guard case .failed(let networkExitCode, _) = loadEmbedConfig(settings: fromFile, http: none.http),
    networkExitCode == EmbedExitCode.config
  else {
    throw SelfTestError("a network JWT in client.jwt was accepted")
  }
  try writePrivateFile(joinPath(stateDir, clientJwtFileName), Data("  \(clientJwt1)\n\n".utf8))
  guard case .loaded(let config) = loadEmbedConfig(settings: fromFile, http: none.http),
    config.clientId == clientId1, config.clientJwt == clientJwt1, config.apiUrl == defaultApiUrl,
    config.firstCap == nil
  else {
    throw SelfTestError("client.jwt from the backend tool did not load")
  }
}

/// Unknown arguments are a usage error, which exits with the configuration code
/// 78, like every problem a restart does not fix.
public func checkUsage() throws {
  let cases: [(arguments: [String], command: EmbedCommand)] = [
    (arguments: [], command: .run),
    (arguments: ["run"], command: .run),
    (arguments: ["--self-test"], command: .selfTest),
    (arguments: ["--licenses"], command: .licenses),
    (arguments: ["--version"], command: .version),
    (arguments: ["--unknown"], command: .usage),
    (arguments: ["run", "--self-test"], command: .usage),
  ]
  for c in cases {
    let command = EmbedCommand(arguments: c.arguments)
    try expect(command == c.command, "arguments \(c.arguments) are \(command), want \(c.command)")
  }
  try expect(
    EmbedExitCode.config == 78 && EmbedExitCode.failure == 1 && EmbedExitCode.stopped == 0,
    "the exit codes are not 0, 1 and 78")
}
