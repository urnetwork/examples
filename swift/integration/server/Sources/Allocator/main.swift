// SERVER ONLY. The authenticated backend supplies user:<service-user-id> internally;
// never a raw request field or UR client ID. Set URNETWORK_ROOT_JWT,
// URNETWORK_CLIENT_MAP (absolute path in an existing service-owned directory), and
// optional URNETWORK_API_URL. Confirm a crash-left .lock is stale before removing it.
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
enum Failure: Error { case invalid }
func require(_ condition: Bool) throws { if !condition { throw Failure.invalid } }
func matches(_ value: String, _ expression: String) -> Bool { value.range(of: expression, options: .regularExpression) != nil }
func userValid(_ user: String) -> Bool { matches(user, #"\Auser:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}\z"#) }
func idValid(_ id: String) -> Bool { matches(id, #"\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z"#) }
func serviceUser(_ args: [String]) throws -> String { try require(args.count == 1 && userValid(args[0])); return args[0] }
func endpoint(_ base: String) throws -> URL {
    guard var u = URLComponents(string: base), let host = u.host else { throw Failure.invalid }
    let local = ["localhost", "127.0.0.1", "[::1]", "::1"].contains(host)
    try require(u.user == nil && u.password == nil && ["", "/"].contains(u.percentEncodedPath) && u.query == nil && u.fragment == nil &&
        (u.scheme == "https" || (u.scheme == "http" && local)))
    u.path = "/network/auth-client"
    guard let url = u.url else { throw Failure.invalid }
    return url
}
func requestFor(_ user: String, _ client: String?) throws -> [String: String] {
    try require(userValid(user))
    var body = ["description": "service " + user, "device_spec": "urnetwork-examples/swift-server"]
    if let id = client { try require(idValid(id)); body["client_id"] = id }
    return body
}
func parseResponse(_ raw: Data, _ expected: String?) throws -> [String: String] {
    guard let obj = try JSONSerialization.jsonObject(with: raw) as? [String: Any],
          obj["error"] == nil || obj["error"] is NSNull,
          let id = obj["client_id"] as? String, let jwt = obj["by_client_jwt"] as? String else { throw Failure.invalid }
    let parts = jwt.components(separatedBy: ".")
    try require(idValid(id) && parts.count == 3 && parts.allSatisfy { !$0.isEmpty })
    try require(matches(parts[1], #"\A[A-Za-z0-9_-]+\z"#))
    let encoded = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    guard let payload = Data(base64Encoded: encoded + String(repeating: "=", count: (4 - encoded.count % 4) % 4)),
          let claims = try JSONSerialization.jsonObject(with: payload) as? [String: Any], claims["client_id"] as? String == id else { throw Failure.invalid }
    try require(expected == nil || expected == id)
    // This checks claim consistency, not the JWT's signature locally.
    return ["client_id": id, "by_client_jwt": jwt]
}
struct ClientMap: Codable { var version = 1; var clients: [String: String] = [:] }
func loadMap(_ file: String) throws -> ClientMap {
    if !FileManager.default.fileExists(atPath: file) { return ClientMap() }
    let attrs = try FileManager.default.attributesOfItem(atPath: file)
    try require(attrs[.type] as? FileAttributeType == .typeRegular && ((attrs[.size] as? NSNumber)?.intValue ?? limit + 1) <= limit)
    try require(((attrs[.posixPermissions] as? NSNumber)?.intValue ?? 0o777) & 0o077 == 0)
    let map = try JSONDecoder().decode(ClientMap.self, from: Data(contentsOf: URL(fileURLWithPath: file)))
    try require(map.version == 1)
    var seen = Set<String>()
    for (user, id) in map.clients { try require(userValid(user) && idValid(id) && seen.insert(id).inserted) }
    return map
}
func saveMap(_ file: String, _ map: ClientMap) throws {
    let temporary = file + "." + UUID().uuidString
    let fd = open(temporary, O_WRONLY | O_CREAT | O_EXCL, 0o600)
    try require(fd >= 0)
    let output = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    defer { try? FileManager.default.removeItem(atPath: temporary) }
    do {
        try output.write(contentsOf: JSONEncoder().encode(map))
        try output.synchronize()
        try output.close()
    } catch { try? output.close(); throw error }
    try require(rename(temporary, file) == 0)
}
func allocate(_ user: String, _ file: String, _ call: ([String: String]) async throws -> Data) async throws -> [String: String] {
    try require(file.hasPrefix("/") && FileManager.default.fileExists(atPath: (file as NSString).deletingLastPathComponent))
    let lock = file + ".lock"
    try require(mkdir(lock, 0o700) == 0)
    defer { _ = rmdir(lock) }
    var map = try loadMap(file)
    let old = map.clients[user]
    let result = try parseResponse(try await call(requestFor(user, old)), old)
    if old == nil {
        let id = result["client_id"]!
        try require(!map.clients.values.contains(id))
        map.clients[user] = id
        try saveMap(file, map)
    }
    return result
}
final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
func post(_ url: URL, _ root: String, _ body: [String: String]) async throws -> Data {
    try require(!root.isEmpty && !root.contains { $0.isWhitespace })
    var request = URLRequest(url: url, timeoutInterval: 15)
    request.httpMethod = "POST"
    request.setValue("Bearer " + root, forHTTPHeaderField: "Authorization")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.httpBody = try JSONSerialization.data(withJSONObject: body)
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 15; config.timeoutIntervalForResource = 15
    let session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    defer { session.invalidateAndCancel() }
    let (bytes, response) = try await session.bytes(for: request)
    guard let http = response as? HTTPURLResponse else { throw Failure.invalid }
    try require((200..<300).contains(http.statusCode))
    var raw = Data()
    for try await byte in bytes { try require(raw.count < limit); raw.append(byte) }
    return raw
}
func reject(_ action: () throws -> Void) throws {
    do { try action() } catch { return }
    throw Failure.invalid
}
func selfTest() async throws {
    let id = "11111111-1111-1111-1111-111111111111"
    let payload = try JSONSerialization.data(withJSONObject: ["client_id": id]).base64EncodedString().replacingOccurrences(of: "=", with: "").replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
    let jwt = "e30." + payload + ".test"
    let raw = try JSONSerialization.data(withJSONObject: ["client_id": id, "by_client_jwt": jwt])
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("ur-allocator-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    defer { try? FileManager.default.removeItem(at: directory) }
    let file = directory.appendingPathComponent("clients.json").path
    var calls: [[String: String]] = []
    let mock: ([String: String]) async throws -> Data = { body in calls.append(body); return raw }
    let first = try await allocate("user:alice", file, mock), second = try await allocate("user:alice", file, mock)
    try require(first["client_id"] == id && second["by_client_jwt"] == jwt)
    try require(calls[0]["client_id"] == nil && calls[1]["client_id"] == id && calls.allSatisfy { $0["source_client_id"] == nil })
    try require(loadMap(file).clients == ["user:alice": id])
    try require(endpoint("http://127.0.0.1:1234").path == "/network/auth-client")
    try reject { _ = try serviceUser([id]) }; try reject { _ = try serviceUser(["user:a", "--client-id", id]) }
    try reject { _ = try serviceUser(["user:../a"]) }; try reject { _ = try endpoint("http://example.com") }
    try reject { _ = try endpoint("https://example.com/path") }; try reject { _ = try parseResponse(Data(#"{"error":{}}"#.utf8), nil) }
    try reject { _ = try parseResponse(raw, "22222222-2222-2222-2222-222222222222") }
    print("allocator self-test passed")
}
let args = Array(CommandLine.arguments.dropFirst())
do {
    if args == ["--self-test"] { try await selfTest() }
    else {
        let user = try serviceUser(args), env = ProcessInfo.processInfo.environment
        let url = try endpoint(env["URNETWORK_API_URL"] ?? "https://api.bringyour.com")
        guard let root = env["URNETWORK_ROOT_JWT"], let file = env["URNETWORK_CLIENT_MAP"] else { throw Failure.invalid }
        let result = try await allocate(user, file) { try await post(url, root, $0) }
        print(String(decoding: try JSONSerialization.data(withJSONObject: result), as: UTF8.self))
    }
} catch {
    FileHandle.standardError.write(Data("allocator failed: check service key, private mapping and backend API configuration\n".utf8))
    exit(1)
}
