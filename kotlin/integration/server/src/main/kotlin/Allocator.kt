// SERVER ONLY. The authenticated backend supplies user:<service-user-id> internally;
// never pass a raw request field or UR client ID. Set URNETWORK_ROOT_JWT,
// URNETWORK_CLIENT_MAP (absolute path under an existing service-owned directory),
// and optionally URNETWORK_API_URL. Remove a crash-left .lock only after checking it is stale.
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Base64
import kotlin.system.exitProcess

private val json = ObjectMapper()
private const val limit = 1 shl 20
private val users = Regex("user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}")
private val ids = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
private fun serviceUser(args: List<String>): String {
    require(args.size == 1 && users.matches(args[0])) { "expected one user:<service-user-id>" }
    return args[0]
}
private fun endpoint(base: String): URI {
    val u = URI(base)
    val local = u.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
    require(u.host != null && u.userInfo == null && u.rawPath in setOf("", "/") && u.rawQuery == null && u.rawFragment == null &&
        (u.scheme == "https" || (u.scheme == "http" && local))) { "invalid API origin" }
    return u.resolve("/network/auth-client")
}
private fun requestFor(user: String, client: String?): ObjectNode {
    serviceUser(listOf(user))
    val body = json.createObjectNode().put("description", "service $user").put("device_spec", "urnetwork-examples/kotlin-server")
    if (client != null) { require(ids.matches(client)); body.put("client_id", client) }
    return body
}
private fun parseResponse(raw: String, expected: String?): ObjectNode {
    val obj = json.readTree(raw)
    require(obj != null && obj.isObject && (!obj.has("error") || obj["error"].isNull)) { "API failure" }
    val id = obj.path("client_id").textValue() ?: error("missing client ID")
    val jwt = obj.path("by_client_jwt").textValue() ?: error("missing scoped JWT")
    val parts = jwt.split('.')
    require(ids.matches(id) && parts.size == 3 && parts.none { it.isEmpty() } && Regex("[A-Za-z0-9_-]+").matches(parts[1]))
    val claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]))
    require(claims?.path("client_id")?.textValue() == id && (expected == null || expected == id)) { "scoped identity mismatch" }
    // Claim consistency checking does not verify JWT signatures locally.
    return json.createObjectNode().put("client_id", id).put("by_client_jwt", jwt)
}
private fun posix(file: Path) = Files.getFileStore(file).supportsFileAttributeView("posix")
private fun loadMap(file: Path): ObjectNode {
    require(!Files.isSymbolicLink(file))
    if (!Files.exists(file)) return json.createObjectNode().put("version", 1).also { it.set<ObjectNode>("clients", json.createObjectNode()) }
    require(Files.isRegularFile(file) && Files.size(file) <= limit)
    if (posix(file)) require(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).containsAll(Files.getPosixFilePermissions(file))) { "mapping must be private (0600)" }
    val map = json.readTree(Files.readString(file))
    require(map is ObjectNode && map.path("version").isIntegralNumber && map["version"].intValue() == 1 && map.path("clients").isObject)
    val seen = mutableSetOf<String>()
    for ((user, value) in map["clients"].properties()) {
        val id = value.textValue()
        require(users.matches(user) && id != null && ids.matches(id) && seen.add(id))
    }
    return map
}
private fun saveMap(file: Path, map: ObjectNode) {
    val temp = if (posix(file.parent)) Files.createTempFile(file.parent, "clients-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        else Files.createTempFile(file.parent, "clients-", ".json")
    try {
        Files.writeString(temp, json.writeValueAsString(map), StandardOpenOption.TRUNCATE_EXISTING)
        FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp) }
}
private fun allocate(user: String, file: Path, call: (ObjectNode) -> String): ObjectNode {
    require(file.isAbsolute && Files.isDirectory(file.parent))
    val lock = Path.of("$file.lock")
    if (posix(file.parent)) Files.createDirectory(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) else Files.createDirectory(lock)
    try {
        val map = loadMap(file)
        val clients = map["clients"] as ObjectNode
        val old = clients.path(user).textValue()
        val result = parseResponse(call(requestFor(user, old)), old)
        if (old == null) {
            val id = result["client_id"].textValue()
            require(clients.none { it.textValue() == id }) { "client assigned to another user" }
            clients.put(user, id)
            saveMap(file, map)
        }
        return result
    } finally { Files.delete(lock) }
}
private fun post(url: URI, root: String, body: ObjectNode): String {
    require(root.isNotEmpty() && root.none(Char::isWhitespace))
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
    val request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer $root").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    require(response.statusCode() in 200..299 && response.body().length <= limit) { "provisioning HTTP failure" }
    return response.body()
}
private fun reject(action: () -> Unit) { check(runCatching(action).isFailure) { "invalid input accepted" } }
private fun selfTest() {
    val id = "11111111-1111-1111-1111-111111111111"
    val jwt = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(mapOf("client_id" to id))) + ".test"
    val raw = json.writeValueAsString(mapOf("client_id" to id, "by_client_jwt" to jwt))
    val dir = Files.createTempDirectory("ur-allocator-")
    val file = dir.resolve("clients.json")
    try {
        val calls = mutableListOf<ObjectNode>()
        val mock: (ObjectNode) -> String = { calls.add(it); raw }
        check(allocate("user:alice", file, mock)["client_id"].textValue() == id)
        check(allocate("user:alice", file, mock)["by_client_jwt"].textValue() == jwt)
        check(!calls[0].has("client_id") && calls[1]["client_id"].textValue() == id && calls.none { it.has("source_client_id") })
        check(loadMap(file)["clients"]["user:alice"].textValue() == id)
        check(endpoint("http://127.0.0.1:1234").path == "/network/auth-client")
        reject { serviceUser(listOf(id)) }; reject { serviceUser(listOf("user:a", "--client-id", id)) }
        reject { serviceUser(listOf("user:../a")) }; reject { endpoint("http://example.com") }
        reject { endpoint("https://example.com/path") }; reject { parseResponse("{\"error\":{}}", null) }
        reject { parseResponse(raw, "22222222-2222-2222-2222-222222222222") }
    } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir.resolve("clients.json.lock")); Files.delete(dir) }
    println("allocator self-test passed")
}
fun main(args: Array<String>) {
    try {
        if (args.contentEquals(arrayOf("--self-test"))) { selfTest(); return }
        val user = serviceUser(args.toList())
        val url = endpoint(System.getenv("URNETWORK_API_URL") ?: "https://api.bringyour.com")
        val root = requireNotNull(System.getenv("URNETWORK_ROOT_JWT"))
        val file = Path.of(requireNotNull(System.getenv("URNETWORK_CLIENT_MAP")))
        println(json.writeValueAsString(allocate(user, file) { post(url, root, it) }))
    } catch (_: Exception) {
        System.err.println("allocator failed: check service key, private mapping and backend API configuration")
        exitProcess(1)
    }
}
