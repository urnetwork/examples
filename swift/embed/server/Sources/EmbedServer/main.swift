// SERVER ONLY: the Swift embed backend tool (EMBED_CONTRACT.md, "Backend tools").
//
//   embed-server provision <key> <client-jwt-file>
//   embed-server cap <key> [--monthly <bytes>|--monthly null]
//                          [--total <bytes>|--total null] [--reset-total]
//   embed-server usage <key>
//   embed-server usage-all
//   embed-server remove <key>
//   embed-server --self-test
//
// It extends the Swift integration allocator (../../integration/server): the
// same settings (URNETWORK_ROOT_JWT, an API key or a network JWT;
// URNETWORK_CLIENT_MAP, an absolute path in an existing private,
// service-owned directory; optional URNETWORK_API_URL), map format, key
// pattern, lock and response checks. Your service authenticates its user
// first and supplies the key internally, as
// user:<service-user-id>:<installation-id>, never a raw request field or a
// URnetwork client ID. Exit codes: 0 success, 78 configuration or credential
// problem, 1 any other failure, with one stderr line that never holds a
// secret. This is a POSIX command-line program on Foundation. Confirm a
// crash-left .lock is stale before removing it.

import Foundation

#if canImport(FoundationNetworking)
  import FoundationNetworking
#endif
#if canImport(Darwin)
  import Darwin
#else
  import Glibc
#endif

let limit = 1 << 20
let exitOk: Int32 = 0
let exitFailure: Int32 = 1
let exitConfig: Int32 = 78
let clientDescription = "embed client"
let deviceSpec = "urnetwork-examples/swift-embed-server"
let usageText =
  "usage: embed-server provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|null] [--total <bytes>|null] [--reset-total] | usage <key> | usage-all | remove <key> | --self-test"
let clientLimitText =
  "client limit reached: your network is at its client limit; see https://ur.io/services"
let clientGoneMessage = "Client does not exist."
let mapInvalid = "the client map is not a valid private map"
let rootRefused = "the API refused the root credential"

/// A failure with its exit code and its one stderr line.
struct Failure: Error, CustomStringConvertible {
  let code: Int32
  let description: String

  /// A failure with the given exit code and text.
  init(_ code: Int32, _ description: String) {
    self.code = code
    self.description = description
  }
}

func matches(_ value: String, _ expression: String) -> Bool {
  return value.range(of: expression, options: .regularExpression) != nil
}

/// The allocators' key pattern.
func keyValid(_ key: String) -> Bool {
  return matches(key, #"\Auser:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}\z"#)
}

/// A canonical client id.
func idValid(_ id: String) -> Bool {
  return matches(id, #"\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z"#)
}

/// The API origin with the allocators' rules: HTTPS, or explicit loopback HTTP
/// for local tests and mocks; no credentials, path beyond "/", query or
/// fragment. nil when invalid.
func origin(_ base: String) -> String? {
  guard var components = URLComponents(string: base), let host = components.host, !host.isEmpty
  else {
    return nil
  }
  let local = ["localhost", "127.0.0.1", "[::1]", "::1"].contains(host)
  guard components.user == nil, components.password == nil,
    ["", "/"].contains(components.percentEncodedPath), components.query == nil,
    components.fragment == nil,
    components.scheme == "https" || (components.scheme == "http" && local)
  else {
    return nil
  }
  components.path = ""
  return components.string
}

/// Whether a client JWT's client_id claim equals id. This checks consistency;
/// it is not local signature verification.
func claimMatches(_ jwt: String, _ id: String) -> Bool {
  let parts = jwt.components(separatedBy: ".")
  guard parts.count == 3, parts.allSatisfy({ !$0.isEmpty }),
    matches(parts[1], #"\A[A-Za-z0-9_-]+\z"#)
  else {
    return false
  }
  let encoded = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(
    of: "_", with: "/")
  guard
    let payload = Data(
      base64Encoded: encoded + String(repeating: "=", count: (4 - encoded.count % 4) % 4)),
    let claims = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any]
  else {
    return false
  }
  return claims["client_id"] as? String == id
}

/// Compact JSON with sorted keys and unescaped slashes.
func jsonData(_ object: Any) -> Data? {
  return try? JSONSerialization.data(
    withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
}

/// One line of compact JSON.
func jsonLine(_ object: Any) -> String {
  return jsonData(object).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
}

/// A CodingKey for any member name, to see every member of an object.
struct AnyKey: CodingKey {
  let stringValue: String
  var intValue: Int? { nil }

  init?(stringValue: String) {
    self.stringValue = stringValue
  }

  init?(intValue: Int) {
    return nil
  }
}

/// The private map: version 1 and the client of each key. Only the
/// allocators' fields are accepted, so a map with other fields (such as the
/// token server's pending_caps) is refused and never rewritten by this tool.
struct ClientMap: Codable {
  var version = 1
  var clients: [String: String] = [:]

  enum CodingKeys: String, CodingKey {
    case version
    case clients
  }

  /// An empty map.
  init() {}

  /// Decodes a map with exactly the version and clients members.
  init(from decoder: Decoder) throws {
    let members = try decoder.container(keyedBy: AnyKey.self)
    guard Set(members.allKeys.map(\.stringValue)) == ["version", "clients"] else {
      throw Failure(exitConfig, mapInvalid)
    }
    let container = try decoder.container(keyedBy: CodingKeys.self)
    version = try container.decode(Int.self, forKey: .version)
    clients = try container.decode([String: String].self, forKey: .clients)
  }
}

/// Loads the private map: a missing file is an empty map.
func loadMap(_ path: String) throws -> ClientMap {
  let attributes: [FileAttributeKey: Any]
  do {
    // the file itself: a symlink is not followed
    attributes = try FileManager.default.attributesOfItem(atPath: path)
  } catch let error as CocoaError
    where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile
  {
    return ClientMap()
  } catch {
    throw Failure(exitConfig, mapInvalid)
  }
  guard attributes[.type] as? FileAttributeType == .typeRegular,
    ((attributes[.size] as? NSNumber)?.intValue ?? limit + 1) <= limit,
    ((attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0o777) & 0o077 == 0,
    let data = FileManager.default.contents(atPath: path),
    let map = try? JSONDecoder().decode(ClientMap.self, from: data), map.version == 1
  else {
    throw Failure(exitConfig, mapInvalid)
  }
  var seen = Set<String>()
  for (key, id) in map.clients where !keyValid(key) || !idValid(id) || !seen.insert(id).inserted {
    throw Failure(exitConfig, mapInvalid)
  }
  return map
}

/// Writes data to path atomically, owner-only: a private temporary file in the
/// same directory, synced, then renamed over the old file.
func writePrivate(_ path: String, _ data: Data) throws {
  let failure = Failure(exitFailure, "could not write \((path as NSString).lastPathComponent)")
  var template = Array((path + ".XXXXXX").utf8CString)
  let fd = template.withUnsafeMutableBufferPointer { mkstemp($0.baseAddress!) }
  if fd < 0 {
    throw failure
  }
  let temporary = template.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
  var ok = fchmod(fd, 0o600) == 0
  if ok {
    ok = data.withUnsafeBytes { buffer -> Bool in
      var offset = 0
      while offset < buffer.count {
        let count = write(fd, buffer.baseAddress! + offset, buffer.count - offset)
        if count < 0 && errno == EINTR {
          continue
        }
        if count <= 0 {
          return false
        }
        offset += count
      }
      return true
    }
  }
  ok = ok && fsync(fd) == 0
  if close(fd) != 0 {
    ok = false
  }
  ok = ok && rename(temporary, path) == 0
  if !ok {
    unlink(temporary)
    throw failure
  }
}

/// Saves the map.
func saveMap(_ path: String, _ map: ClientMap) throws {
  let encoder = JSONEncoder()
  encoder.outputFormatting = [.sortedKeys]
  try writePrivate(path, try encoder.encode(map))
}

/// Runs body while holding the exclusive <map>.lock directory, through the
/// remote call and the map update.
func withMapLock<T>(_ mapPath: String, _ body: () throws -> T) throws -> T {
  let lock = mapPath + ".lock"
  if mkdir(lock, 0o700) != 0 {
    throw Failure(exitFailure, "the client map is locked by another run; retry")
  }
  defer { rmdir(lock) }
  return try body()
}

/// One API answer.
struct ApiAnswer {
  var status: Int
  var body: Data
}

/// One call to the URnetwork API: the method, the path with its query, and an
/// optional JSON body. nil when no answer arrived.
typealias Call = (_ method: String, _ path: String, _ body: Data?) -> ApiAnswer?

/// The tool's map, API and output.
struct Tool {
  var mapPath: String
  var call: Call
  var output: (String) -> Void
}

/// The kind of an API answer.
enum Kind {
  case ok
  case refused
  case unauthorized
  case failed
}

/// The API's refusal, {"error": {"message": ..., "client_limit_exceeded": true}};
/// a member of another type is absent.
struct Refusal: Decodable {
  struct Body: Decodable {
    var message: String?
    var clientLimitExceeded: Bool?
    var upgradeRequired: Bool?

    enum CodingKeys: String, CodingKey {
      case message
      case clientLimitExceeded = "client_limit_exceeded"
      case upgradeRequired = "upgrade_required"
    }

    init(from decoder: Decoder) throws {
      let container = try decoder.container(keyedBy: CodingKeys.self)
      message = try? container.decodeIfPresent(String.self, forKey: .message)
      clientLimitExceeded = try? container.decodeIfPresent(Bool.self, forKey: .clientLimitExceeded)
      upgradeRequired = try? container.decodeIfPresent(Bool.self, forKey: .upgradeRequired)
    }
  }

  var error: Body?
}

/// An API answer, read: its kind, its JSON object and its HTTP status (0
/// without an answer).
struct Answer {
  var kind: Kind
  var object: [String: Any] = [:]
  var body = Data()
  var status = 0

  /// The refusal's message, or "".
  var refusalMessage: String {
    return (try? JSONDecoder().decode(Refusal.self, from: body))?.error?.message ?? ""
  }

  /// Whether the refusal is either client limit flag.
  var clientLimitRefusal: Bool {
    let error = (try? JSONDecoder().decode(Refusal.self, from: body))?.error
    return error?.clientLimitExceeded == true || error?.upgradeRequired == true
  }
}

/// Calls the API and reads its answer as a JSON object.
func callApi(_ tool: Tool, _ method: String, _ path: String, _ body: [String: Any]?) -> Answer {
  var bodyData: Data?
  if let body {
    guard let data = jsonData(body) else {
      return Answer(kind: .failed)
    }
    bodyData = data
  }
  guard let response = tool.call(method, path, bodyData) else {
    return Answer(kind: .failed)
  }
  if response.status == 401 || response.status == 403 {
    return Answer(kind: .unauthorized, status: response.status)
  }
  guard (200..<300).contains(response.status),
    let object = (try? JSONSerialization.jsonObject(with: response.body)) as? [String: Any]
  else {
    return Answer(kind: .failed, status: response.status)
  }
  let refused = object["error"] != nil && !(object["error"] is NSNull)
  return Answer(
    kind: refused ? .refused : .ok, object: object, body: response.body, status: response.status)
}

func requireKey(_ key: String, _ command: String) throws {
  if !keyValid(key) {
    throw Failure(exitConfig, "\(command): invalid key")
  }
}

/// The key's mapped client, or a configuration failure.
func requireClient(_ map: ClientMap, _ key: String) throws -> String {
  guard let client = map.clients[key] else {
    throw Failure(exitConfig, "no client is mapped for \(key)")
  }
  return client
}

/// provision <key> <client-jwt-file>: reissues the key's client, or provisions
/// a new one; on "Client does not exist." it drops the mapping and provisions a
/// new client. The client JWT goes only to the file.
func provision(_ tool: Tool, _ key: String, _ jwtFile: String) throws {
  try requireKey(key, "provision")
  if jwtFile.isEmpty {
    throw Failure(exitConfig, "provision: invalid file")
  }
  try withMapLock(tool.mapPath) {
    var map = try loadMap(tool.mapPath)
    var old = map.clients[key]
    var answer = Answer(kind: .failed)
    attempts: for _ in 0..<2 {
      var body: [String: Any] = ["description": clientDescription, "device_spec": deviceSpec]
      if let old {
        body["client_id"] = old
      }
      answer = callApi(tool, "POST", "/network/auth-client", body)
      switch answer.kind {
      case .unauthorized:
        throw Failure(exitConfig, rootRefused)
      case .failed:
        throw Failure(exitFailure, "provisioning failed: no valid answer (HTTP \(answer.status))")
      case .refused:
        if answer.clientLimitRefusal {
          throw Failure(exitConfig, clientLimitText)
        }
        if old != nil && answer.refusalMessage == clientGoneMessage {
          // deactivated after 30 days without connecting: provision anew
          map.clients[key] = nil
          try saveMap(tool.mapPath, map)
          old = nil
          continue attempts
        }
        throw Failure(exitFailure, "provisioning refused: \(answer.refusalMessage)")
      case .ok:
        break attempts
      }
    }
    guard answer.kind == .ok, let id = answer.object["client_id"] as? String,
      let jwt = answer.object["by_client_jwt"] as? String, idValid(id), claimMatches(jwt, id),
      old == nil || old == id
    else {
      throw Failure(exitFailure, "provisioning answered an invalid client")
    }
    if old == nil {
      if map.clients.values.contains(id) {
        throw Failure(exitFailure, "provisioning answered a client mapped to another key")
      }
      map.clients[key] = id
      try saveMap(tool.mapPath, map)
    }
    do {
      try writePrivate(jwtFile, Data((jwt + "\n").utf8))
    } catch {
      throw Failure(exitFailure, "could not write the client JWT file")
    }
    tool.output(jsonLine(["client_id": id]))
  }
}

/// A byte count: a decimal integer from 0 to 9223372036854775807, no units.
func parseByteCount(_ text: String) -> Int64? {
  guard !text.isEmpty, text.utf8.allSatisfy({ UInt8(ascii: "0") <= $0 && $0 <= UInt8(ascii: "9") })
  else {
    return nil
  }
  return Int64(text)
}

/// The cap request body from the options: only the given fields, with null for
/// a cleared cap and reset_total only when given.
func capRequest(_ client: String, _ options: [String]) -> [String: Any]? {
  var body: [String: Any] = ["client_id": client]
  var monthly = false
  var total = false
  var reset = false
  var index = 0
  while index < options.count {
    let option = options[index]
    index += 1
    if option == "--reset-total" && !reset {
      reset = true
      body["reset_total"] = true
      continue
    }
    let isMonthly = option == "--monthly" && !monthly
    let isTotal = option == "--total" && !total
    guard isMonthly || isTotal, index < options.count else {
      return nil
    }
    let value = options[index]
    index += 1
    var limitValue: Any = NSNull()
    if value != "null" {
      guard let count = parseByteCount(value) else {
        return nil
      }
      limitValue = count
    }
    body[isMonthly ? "monthly_byte_limit" : "total_byte_limit"] = limitValue
    if isMonthly {
      monthly = true
    } else {
      total = true
    }
  }
  return monthly || total || reset ? body : nil
}

/// Prints a cap object answer, or throws the failure.
func printCap(_ tool: Tool, _ answer: Answer) throws {
  switch answer.kind {
  case .unauthorized:
    throw Failure(exitConfig, rootRefused)
  case .refused:
    throw Failure(exitFailure, "the API refused: \(answer.refusalMessage)")
  case .ok where answer.object["client_id"] != nil:
    tool.output(jsonLine(answer.object))
  default:
    throw Failure(
      exitFailure,
      "no cap object in the answer (HTTP \(answer.status); a server without the cap routes answers 404)"
    )
  }
}

/// cap <key> [options]: posts only the given fields.
func cap(_ tool: Tool, _ key: String, _ options: [String]) throws {
  try requireKey(key, "cap")
  let client = try requireClient(try loadMap(tool.mapPath), key)
  guard let body = capRequest(client, options) else {
    throw Failure(exitConfig, "cap: give --monthly, --total or --reset-total with byte counts or null")
  }
  try printCap(tool, callApi(tool, "POST", "/network/client-data-cap", body))
}

/// usage <key>: the key's cap object, read with the root credential.
func usage(_ tool: Tool, _ key: String) throws {
  try requireKey(key, "usage")
  let client = try requireClient(try loadMap(tool.mapPath), key)
  try printCap(tool, callApi(tool, "GET", "/network/client-data-cap?client_id=\(client)", nil))
}

/// Percent-encodes all but the unreserved characters.
func encodeQueryValue(_ text: String) -> String {
  let unreserved = CharacterSet(
    charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
  return text.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
}

/// usage-all: pages through GET /network/client-data-caps with limit=1000 and
/// prints one cap object per line, stopping at a null cursor or a repeated one.
func usageAll(_ tool: Tool) throws {
  var cursor: String?
  while true {
    var path = "/network/client-data-caps?limit=1000"
    if let cursor {
      path += "&cursor=" + encodeQueryValue(cursor)
    }
    let answer = callApi(tool, "GET", path, nil)
    if answer.kind == .unauthorized {
      throw Failure(exitConfig, rootRefused)
    }
    guard answer.kind == .ok, let clients = answer.object["clients"] as? [Any],
      clients.allSatisfy({ $0 is [String: Any] })
    else {
      throw Failure(exitFailure, "no page of cap objects in the answer (HTTP \(answer.status))")
    }
    for client in clients {
      tool.output(jsonLine(client))
    }
    guard let next = answer.object["next_cursor"] as? String, next != cursor else {
      return
    }
    cursor = next
  }
}

/// remove <key>: removes the key's client, then the mapping, also when the
/// client no longer exists.
func removeClient(_ tool: Tool, _ key: String) throws {
  try requireKey(key, "remove")
  try withMapLock(tool.mapPath) {
    var map = try loadMap(tool.mapPath)
    let client = try requireClient(map, key)
    let answer = callApi(tool, "POST", "/network/remove-client", ["client_id": client])
    if answer.kind == .unauthorized {
      throw Failure(exitConfig, rootRefused)
    }
    if answer.kind == .failed {
      throw Failure(exitFailure, "removing the client failed (HTTP \(answer.status))")
    }
    if answer.kind == .refused && answer.refusalMessage != clientGoneMessage {
      throw Failure(exitFailure, "removing the client failed: \(answer.refusalMessage)")
    }
    map.clients[key] = nil
    try saveMap(tool.mapPath, map)
    tool.output(jsonLine(["removed": client]))
  }
}

/// Runs one command line (the arguments after the program name), giving a
/// failure's one line to error.
func run(_ tool: Tool, _ args: [String], error: (String) -> Void) -> Int32 {
  do {
    switch (args.first, args.count) {
    case ("provision", 3):
      try provision(tool, args[1], args[2])
    case ("cap", 2...):
      try cap(tool, args[1], Array(args.dropFirst(2)))
    case ("usage", 2):
      try usage(tool, args[1])
    case ("usage-all", 1):
      try usageAll(tool)
    case ("remove", 2):
      try removeClient(tool, args[1])
    default:
      throw Failure(exitConfig, usageText)
    }
    return exitOk
  } catch let failure as Failure {
    error(failure.description)
    return failure.code
  } catch let other {
    error("\(other)")
    return exitFailure
  }
}

/// Refuses redirects: the tool talks to exactly the origin it was given.
final class NoRedirect: NSObject, URLSessionTaskDelegate {
  func urlSession(
    _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void
  ) {
    completionHandler(nil)
  }
}

/// One API call with the root credential as the bearer token: no redirects, an
/// ephemeral session, a 15 second timeout and a bounded answer. nil when no
/// answer arrived.
func apiCall(_ apiOrigin: String, _ root: String, _ method: String, _ path: String, _ body: Data?)
  -> ApiAnswer?
{
  guard let url = URL(string: apiOrigin + path) else {
    return nil
  }
  var request = URLRequest(url: url, timeoutInterval: 15)
  request.httpMethod = method
  request.setValue("Bearer " + root, forHTTPHeaderField: "Authorization")
  if let body {
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.httpBody = body
  }
  let configuration = URLSessionConfiguration.ephemeral
  configuration.timeoutIntervalForRequest = 15
  configuration.timeoutIntervalForResource = 15
  let session = URLSession(configuration: configuration, delegate: NoRedirect(), delegateQueue: nil)
  defer { session.finishTasksAndInvalidate() }
  /// The task's outcome, written once before done is signaled.
  final class Outcome: @unchecked Sendable {
    var data: Data?
    var response: URLResponse?
  }
  let outcome = Outcome()
  let done = DispatchSemaphore(value: 0)
  session.dataTask(with: request) { data, response, _ in
    outcome.data = data
    outcome.response = response
    done.signal()
  }.resume()
  done.wait()
  guard let http = outcome.response as? HTTPURLResponse else {
    return nil
  }
  let data = outcome.data ?? Data()
  if limit < data.count {
    return nil
  }
  return ApiAnswer(status: http.statusCode, body: data)
}

/// A failed self-test check.
struct SelfTestFailure: Error, CustomStringConvertible {
  let description: String
}

func check(_ condition: Bool, line: Int = #line) throws {
  if !condition {
    throw SelfTestFailure(description: "embed-server self-test failed at line \(line)")
  }
}

/// A mock API: canned answers in order, and the requests it saw.
final class Mock {
  var answers: [ApiAnswer]
  var methods: [String] = []
  var paths: [String] = []
  /// the exact JSON bodies, and the same parsed
  var rawBodies: [String?] = []
  var bodies: [[String: Any]?] = []

  init(_ answers: [(Int, String)] = []) {
    self.answers = answers.map { ApiAnswer(status: $0.0, body: Data($0.1.utf8)) }
  }

  func call(_ method: String, _ path: String, _ body: Data?) -> ApiAnswer? {
    if methods.count >= answers.count {
      return nil
    }
    methods.append(method)
    paths.append(path)
    rawBodies.append(body.map { String(decoding: $0, as: UTF8.self) })
    bodies.append(body.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] })
    return answers[methods.count - 1]
  }
}

/// Runs a command line against the mock, with its exit code and output.
func runWith(_ mock: Mock, _ mapPath: String, _ args: [String]) -> (
  code: Int32, out: String, err: String
) {
  var out = ""
  var err = ""
  let tool = Tool(mapPath: mapPath, call: mock.call, output: { out += $0 + "\n" })
  let code = run(tool, args) { err += $0 + "\n" }
  return (code, out, err)
}

func selfTest() throws {
  let id = "11111111-1111-1111-1111-111111111111"
  let id2 = "22222222-2222-2222-2222-222222222222"
  // fixed offline token payloads holding only {"client_id": id} and
  // {"client_id": id2}
  let jwt = "e30.eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ.test"
  let jwt2 = "e30.eyJjbGllbnRfaWQiOiIyMjIyMjIyMi0yMjIyLTIyMjItMjIyMi0yMjIyMjIyMjIyMjIifQ.test"
  let answer = jsonLine(["client_id": id, "by_client_jwt": jwt])
  let answer2 = jsonLine(["client_id": id2, "by_client_jwt": jwt2])
  let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
    "ur-embed-server-" + UUID().uuidString)
  try FileManager.default.createDirectory(
    at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
  defer { try? FileManager.default.removeItem(at: directory) }
  let mapPath = directory.appendingPathComponent("clients.json").path
  let jwtPath = directory.appendingPathComponent("client.jwt").path
  let key = "user:alice:22222222-2222-2222-2222-222222222222"
  func isPrivate(_ path: String) -> Bool {
    let attributes = try? FileManager.default.attributesOfItem(atPath: path)
    return ((attributes?[.posixPermissions] as? NSNumber)?.intValue ?? 0o777) & 0o077 == 0
  }
  func mapped(_ key: String) -> String? {
    return (try? loadMap(mapPath))?.clients[key]
  }

  // provision: a new client, then a reissue; the token never reaches output
  let m = Mock([(200, answer), (200, answer)])
  var result = runWith(m, mapPath, ["provision", key, jwtPath])
  try check(result.code == exitOk && result.out == #"{"client_id":"\#(id)"}"# + "\n")
  try check(!result.out.contains(".test") && !result.err.contains(".test"))
  result = runWith(m, mapPath, ["provision", key, jwtPath])
  try check(result.code == exitOk && !result.out.contains(".test"))
  try check(m.paths[0] == "/network/auth-client" && m.methods[0] == "POST")
  try check(m.bodies[0]?["client_id"] == nil && m.bodies[0]?["source_client_id"] == nil)
  try check(
    m.bodies[0]?["description"] as? String == clientDescription
      && m.bodies[0]?["device_spec"] as? String == deviceSpec)
  try check(m.bodies[1]?["client_id"] as? String == id && m.bodies[1]?["source_client_id"] == nil)
  // the client JWT file and the map are private, the map round trips
  try check(isPrivate(jwtPath) && isPrivate(mapPath))
  try check(FileManager.default.contents(atPath: jwtPath) == Data((jwt + "\n").utf8))
  try check(mapped(key) == id)
  // "Client does not exist." drops the mapping and provisions anew
  let gone = Mock([(200, #"{"error":{"message":"Client does not exist."}}"#), (200, answer2)])
  try check(runWith(gone, mapPath, ["provision", key, jwtPath]).code == exitOk)
  try check(gone.bodies[0]?["client_id"] as? String == id && gone.bodies[1]?["client_id"] == nil)
  try check(mapped(key) == id2)
  // the client limit (either flag) and a refused root credential are 78
  let newKey = "user:bob:33333333-3333-3333-3333-333333333333"
  for refusal in [
    #"{"error":{"client_limit_exceeded":true,"message":"Client limit exceeded."}}"#,
    #"{"error":{"client_limit_exceeded":false,"upgrade_required":true,"message":"x"}}"#,
  ] {
    result = runWith(Mock([(200, refusal)]), mapPath, ["provision", newKey, jwtPath])
    try check(result.code == exitConfig && result.err == clientLimitText + "\n")
  }
  try check(runWith(Mock([(401, "{}")]), mapPath, ["provision", newKey, jwtPath]).code == exitConfig)
  // invalid answers are failures: an error, a mismatched claim, not json, and
  // a reissue (key, mapped to id2) answering another client
  let mismatched = jsonLine(["client_id": id2, "by_client_jwt": jwt])
  for (invalid, args) in [
    (#"{"error":{"message":"no"}}"#, ["provision", newKey, jwtPath]),
    (#"{"error":{"client_limit_exceeded":1,"message":"no"}}"#, ["provision", newKey, jwtPath]),
    (mismatched, ["provision", newKey, jwtPath]),
    ("not json", ["provision", newKey, jwtPath]),
    (answer, ["provision", key, jwtPath]),
  ] {
    try check(runWith(Mock([(200, invalid)]), mapPath, args).code == exitFailure)
  }
  try check(mapped(newKey) == nil && mapped(key) == id2)
  // cap: only the given fields, null for a cleared cap, integer byte counts
  let capAnswer =
    #"{"client_id":"\#(id2)","monthly_byte_limit":5,"monthly_used_byte_count":0,"total_byte_limit":null,"capped":false,"capped_reason":""}"#
  let caps = Mock([(200, capAnswer), (200, capAnswer), (200, capAnswer)])
  result = runWith(caps, mapPath, ["cap", key, "--monthly", "5"])
  try check(result.code == exitOk && result.out.contains(#""monthly_byte_limit":5"#))
  try check(result.out.hasSuffix("}\n") && result.out.filter { $0 == "\n" }.count == 1)
  try check(runWith(caps, mapPath, ["cap", key, "--total", "null", "--monthly", "0"]).code == exitOk)
  try check(runWith(caps, mapPath, ["cap", key, "--reset-total"]).code == exitOk)
  try check(caps.paths[0] == "/network/client-data-cap" && caps.methods[0] == "POST")
  try check(caps.rawBodies[0] == #"{"client_id":"\#(id2)","monthly_byte_limit":5}"#)
  try check(
    caps.rawBodies[1] == #"{"client_id":"\#(id2)","monthly_byte_limit":0,"total_byte_limit":null}"#)
  try check(caps.rawBodies[2] == #"{"client_id":"\#(id2)","reset_total":true}"#)
  // invalid options are 78 and reach no API
  for args in [
    ["cap", key], ["cap", key, "--monthly", "10GB"], ["cap", key, "--monthly", "-1"],
    ["cap", key, "--monthly", "9223372036854775808"], ["cap", key, "--monthly", "1.5"],
    ["cap", key, "--monthly", "+5"], ["cap", key, "--monthly"],
    ["cap", key, "--monthly", "1", "--monthly", "2"], ["cap", key, "--weekly", "1"],
  ] {
    let none = Mock()
    try check(runWith(none, mapPath, args).code == exitConfig && none.methods.isEmpty)
  }
  let maxMock = Mock([(200, capAnswer)])
  try check(runWith(maxMock, mapPath, ["cap", key, "--total", "9223372036854775807"]).code == exitOk)
  try check(
    maxMock.rawBodies[0] == #"{"client_id":"\#(id2)","total_byte_limit":9223372036854775807}"#)
  // usage: the cap object, read with the root credential; a 404 is a failure
  let reads = Mock([(200, capAnswer), (404, "404 page not found"), (200, #"{"error":{"message":"no"}}"#)])
  try check(runWith(reads, mapPath, ["usage", key]).code == exitOk)
  try check(
    reads.methods[0] == "GET" && reads.paths[0] == "/network/client-data-cap?client_id=\(id2)"
      && reads.rawBodies[0] == nil)
  try check(runWith(reads, mapPath, ["usage", key]).code == exitFailure)
  try check(runWith(reads, mapPath, ["usage", key]).code == exitFailure)
  try check(runWith(Mock(), mapPath, ["usage", "user:nobody"]).code == exitConfig)
  // usage-all: paging, and the stop on a repeated cursor
  let pages = Mock([
    (200, #"{"clients":[{"client_id":"a"},{"client_id":"b"}],"next_cursor":"c 1/+"}"#),
    (200, #"{"clients":[{"client_id":"c"}],"next_cursor":null}"#),
  ])
  result = runWith(pages, mapPath, ["usage-all"])
  try check(
    result.code == exitOk
      && result.out == "{\"client_id\":\"a\"}\n{\"client_id\":\"b\"}\n{\"client_id\":\"c\"}\n")
  try check(
    pages.paths == [
      "/network/client-data-caps?limit=1000",
      "/network/client-data-caps?limit=1000&cursor=c%201%2F%2B",
    ])
  let repeated = Mock([
    (200, #"{"clients":[],"next_cursor":"x"}"#),
    (200, #"{"clients":[{"client_id":"d"}],"next_cursor":"x"}"#),
    (200, #"{"clients":[],"next_cursor":null}"#),
  ])
  result = runWith(repeated, mapPath, ["usage-all"])
  try check(result.code == exitOk && repeated.methods.count == 2 && result.out == "{\"client_id\":\"d\"}\n")
  // remove: the mapping goes for both answers
  let removed = Mock([(200, "{}")])
  result = runWith(removed, mapPath, ["remove", key])
  try check(result.code == exitOk && result.out == #"{"removed":"\#(id2)"}"# + "\n")
  try check(removed.paths[0] == "/network/remove-client" && removed.bodies[0]?["client_id"] as? String == id2)
  try check(mapped(key) == nil)
  try check(runWith(Mock([(200, answer)]), mapPath, ["provision", key, jwtPath]).code == exitOk)
  let already = Mock([(200, #"{"error":{"message":"Client does not exist."}}"#)])
  try check(runWith(already, mapPath, ["remove", key]).code == exitOk && mapped(key) == nil)
  // a map with other fields, such as the token server's, is refused
  try check(
    FileManager.default.createFile(
      atPath: mapPath, contents: Data(#"{"version":1,"clients":{},"pending_caps":[]}"#.utf8),
      attributes: [.posixPermissions: 0o600]))
  chmod(mapPath, 0o600)
  let refused = Mock([(200, answer)])
  try check(runWith(refused, mapPath, ["provision", key, jwtPath]).code == exitConfig)
  try check(refused.methods.isEmpty)
  // a map open to others is refused too
  try check(
    FileManager.default.createFile(
      atPath: mapPath, contents: Data(#"{"version":1,"clients":{}}"#.utf8)))
  chmod(mapPath, 0o644)
  try check(runWith(Mock([(200, answer)]), mapPath, ["provision", key, jwtPath]).code == exitConfig)
  try FileManager.default.removeItem(atPath: mapPath)
  // keys and command lines
  try check(keyValid("user:alice") && keyValid(key) && !keyValid("user:../a") && !keyValid(id))
  try check(!keyValid("user:") && !keyValid("user:-a"))
  for args in [["provision", "user:a"], ["usage"], ["usage-all", "x"], ["--client-id", "x"], ["remove", id], []] {
    let unused = Mock()
    try check(runWith(unused, mapPath, args).code == exitConfig && unused.methods.isEmpty)
  }
  // origins follow the allocators' rules
  try check(origin("http://127.0.0.1:1234") == "http://127.0.0.1:1234")
  try check(origin("https://api.bringyour.com/") == "https://api.bringyour.com")
  try check(origin("http://[::1]:8790") == "http://[::1]:8790")
  for invalid in [
    "http://example.com", "https://example.com/path", "https://u:p@example.com",
    "https://example.com?x=1", "https://example.com#f", "ftp://example.com",
  ] {
    try check(origin(invalid) == nil)
  }
}

/// Writes one line to stdout at once.
func writeOutput(_ line: String) {
  try? FileHandle.standardOutput.write(contentsOf: Data((line + "\n").utf8))
}

/// Writes one line to stderr.
func writeError(_ line: String) {
  try? FileHandle.standardError.write(contentsOf: Data((line + "\n").utf8))
}

/// Runs the command line and returns the exit code.
func main() -> Int32 {
  let args = Array(CommandLine.arguments.dropFirst())
  if args == ["--self-test"] {
    do {
      try selfTest()
    } catch {
      writeError("\(error)")
      return exitFailure
    }
    writeOutput("embed-server self-test passed")
    return exitOk
  }
  let environment = ProcessInfo.processInfo.environment
  let root = environment["URNETWORK_ROOT_JWT"] ?? ""
  let mapPath = environment["URNETWORK_CLIENT_MAP"] ?? ""
  let base = environment["URNETWORK_API_URL"] ?? ""
  if args.isEmpty {
    writeError(usageText)
    return exitConfig
  }
  if root.isEmpty || root.contains(where: { $0.isWhitespace }) {
    writeError("set URNETWORK_ROOT_JWT to the root credential")
    return exitConfig
  }
  if !mapPath.hasPrefix("/") {
    writeError("set URNETWORK_CLIENT_MAP to an absolute path in a private directory")
    return exitConfig
  }
  guard let apiOrigin = origin(base.isEmpty ? "https://api.bringyour.com" : base) else {
    writeError("URNETWORK_API_URL must be an HTTPS origin, or loopback HTTP for local tests")
    return exitConfig
  }
  let tool = Tool(
    mapPath: mapPath, call: { apiCall(apiOrigin, root, $0, $1, $2) }, output: writeOutput)
  return run(tool, args, error: writeError)
}

exit(main())
