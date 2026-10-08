// SERVER ONLY. The Kotlin embed backend tool (EMBED_CONTRACT.md, "Backend
// tools"): the integration allocator (../../integration/server) extended with
// what a backend needs to embed URnetwork. It provisions one client per user
// installation, sets and reads that client's data caps, reads every capped
// client of the network, and removes a client. Your service authenticates its
// user first and supplies the key internally: never take a key, a client ID or
// a cap from a raw request field.
//
//   provision <key> <client-jwt-file>
//   cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total]
//   usage <key>
//   usage-all
//   remove <key>
//   --self-test
//
// <key> is user:<service-user-id> or user:<service-user-id>:<installation-id>.
// Settings: URNETWORK_ROOT_JWT (an API key or a network JWT, from the
// backend's secret store), URNETWORK_CLIENT_MAP (absolute path of this tool's
// private map, in an existing service-owned directory; never the token
// server's map) and optional URNETWORK_API_URL. The map lock is a <map>.lock
// directory; a crash may leave it, so remove it only after confirming that no
// tool still runs.
//
// Exit codes: 0 success; 78 a configuration or credential problem (missing
// settings, an invalid key or map, the root credential refused, the client
// limit); 1 any other failure, with one stderr line that never holds a secret.
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.io.PrintStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Base64
import kotlin.system.exitProcess

const val exitOk = 0
const val exitFailure = 1

// sysexits EX_CONFIG
const val exitConfig = 78
const val limit = 1 shl 20

val json = ObjectMapper()
const val description = "embed client"
const val deviceSpec = "urnetwork-examples/kotlin-embed-server"
const val clientDoesNotExist = "Client does not exist."
const val clientLimitMessage = "client limit reached: your network is at its client limit; see https://ur.io/services"
const val authClientPath = "/network/auth-client"
const val capPath = "/network/client-data-cap"
const val capsPath = "/network/client-data-caps"
const val removePath = "/network/remove-client"
const val usage =
    "usage: provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total] | usage <key> | usage-all | remove <key> | --self-test"

val keys = Regex("user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}")
val ids = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
private val byteCounts = Regex("[0-9]{1,19}")

/** A failure with the exit code it maps to. */
class ToolException(val exitCode: Int, message: String) : Exception(message)

/** One request to the URnetwork API, as the transport sees it. */
data class ApiRequest(val method: String, val pathAndQuery: String, val body: String?)

/** The answer to one request: the HTTP status and the body. */
data class ApiResponse(val status: Int, val body: String)

/** The API transport: HTTPS in production, a stand-in in the self-test. */
fun interface Transport {
    fun send(request: ApiRequest): ApiResponse
}

/**
 * The options of the cap command. A limit that is given with a null value clears that cap; one that
 * is not given is not sent, so the cap keeps its value.
 */
class CapOptions {
    var monthlyGiven = false
    var monthly: Long? = null
    var totalGiven = false
    var total: Long? = null
    var resetTotal = false
}

/** The settings of one run: the transport and, for the commands that use it, the map file. */
private class Settings(val transport: Transport, val mapFile: Path?)

/** The mapped client no longer exists. */
private class ClientMissingException : Exception()

/** Runs the command with the process environment and HTTPS. */
fun main(args: Array<String>) {
    exitProcess(runCommand(args.toList(), System::getenv, System.out, System.err, ::httpTransport))
}

/** Runs one command and returns its exit code. The self-test passes its own environment, streams and stand-in transport. */
fun runCommand(
    args: List<String>,
    env: (String) -> String?,
    out: PrintStream,
    err: PrintStream,
    transport: (URI, String) -> Transport,
): Int {
    try {
        when (args.firstOrNull()) {
            "--self-test" -> {
                if (args.size != 1) throw config(usage)
                try {
                    selfTest()
                } catch (e: Exception) {
                    err.println("embed backend tool self-test failed: ${e.message}")
                    return exitFailure
                }
                out.println("embed backend tool self-test passed")
            }
            "provision" -> {
                if (args.size != 3) throw config(usage)
                provision(checkKey(args[1]), checkJwtFile(args[2]), settings(env, transport, map = true), out)
            }
            "cap" -> {
                if (args.size < 2) throw config(usage)
                cap(checkKey(args[1]), parseCapOptions(args.drop(2)), settings(env, transport, map = true), out)
            }
            "usage" -> {
                if (args.size != 2) throw config(usage)
                readUsage(checkKey(args[1]), settings(env, transport, map = true), out)
            }
            "usage-all" -> {
                if (args.size != 1) throw config(usage)
                readUsageAll(settings(env, transport, map = false), out)
            }
            "remove" -> {
                if (args.size != 2) throw config(usage)
                remove(checkKey(args[1]), settings(env, transport, map = true), out)
            }
            else -> throw config(usage)
        }
        return exitOk
    } catch (e: ToolException) {
        err.println(e.message)
        return e.exitCode
    } catch (e: IOException) {
        err.println("embed backend tool failed: ${e.message ?: e.javaClass.simpleName}")
        return exitFailure
    }
}

/** A configuration error (78). */
fun config(message: String) = ToolException(exitConfig, message)

/** The API refused a request (1), with its message made safe to print. */
private fun refused(message: String) = ToolException(exitFailure, "the URnetwork API refused the request: ${printable(message)}")

/**
 * Reissues the key's client, or provisions a new one, and writes the client JWT to jwtFile. A
 * reissue that answers "Client does not exist." (a client deactivated after 30 days without
 * connecting) drops the mapping and provisions a new client. Prints {"client_id": ...}, never the
 * token.
 */
private fun provision(key: String, jwtFile: Path, settings: Settings, out: PrintStream) {
    val mapFile = settings.mapFile!!
    val lock = takeLock(mapFile)
    try {
        val map = loadMap(mapFile)
        val clients = map["clients"] as ObjectNode
        val mapped = clients.path(key).textValue()
        var result: Pair<String, String>? = null
        if (mapped != null) {
            try {
                result = authClientResult(call(settings, "POST", authClientPath, authClientBody(mapped)), mapped)
            } catch (e: ClientMissingException) {
                clients.remove(key)
                saveMap(mapFile, map)
            }
        }
        if (result == null) {
            result = try {
                authClientResult(call(settings, "POST", authClientPath, authClientBody(null)), null)
            } catch (e: ClientMissingException) {
                throw refused(clientDoesNotExist)
            }
            if (clients.any { it.textValue() == result.first }) {
                throw ToolException(exitFailure, "the API answered a client that the map assigns to another key")
            }
            clients.put(key, result.first)
            saveMap(mapFile, map)
        }
        writePrivate(jwtFile, "${result.second}\n".toByteArray(Charsets.UTF_8))
        out.println(json.writeValueAsString(json.createObjectNode().put("client_id", result.first)))
    } finally {
        Files.deleteIfExists(lock)
    }
}

/** Posts only the given cap fields for the key's client and prints the cap object. */
private fun cap(key: String, options: CapOptions, settings: Settings, out: PrintStream) {
    val lock = takeLock(settings.mapFile!!)
    try {
        val clientId = mappedClient(loadMap(settings.mapFile), key)
        out.println(capObject(call(settings, "POST", capPath, capBody(clientId, options))))
    } finally {
        Files.deleteIfExists(lock)
    }
}

/** Prints the key's cap object, read with the root credential. */
private fun readUsage(key: String, settings: Settings, out: PrintStream) {
    val lock = takeLock(settings.mapFile!!)
    try {
        val clientId = mappedClient(loadMap(settings.mapFile), key)
        out.println(capObject(call(settings, "GET", "$capPath?client_id=${encode(clientId)}", null)))
    } finally {
        Files.deleteIfExists(lock)
    }
}

/**
 * Pages through every capped client of the network, 1000 at a time, and prints one cap object per
 * line. It stops at a null cursor or a repeated one.
 */
private fun readUsageAll(settings: Settings, out: PrintStream) {
    val seenCursors = HashSet<String>()
    var cursor: String? = null
    while (true) {
        val path = "$capsPath?limit=1000" + (cursor?.let { "&cursor=${encode(it)}" } ?: "")
        val page = call(settings, "GET", path, null)
        checkRefusal(page)
        val clients = page["clients"]
        if (clients == null || !clients.isArray) {
            throw ToolException(exitFailure, "the URnetwork API answered a page without clients")
        }
        for (client in clients) {
            if (!client.isObject) {
                throw ToolException(exitFailure, "the URnetwork API answered a client that is not a cap object")
            }
            out.println(json.writeValueAsString(client))
        }
        val next = page.path("next_cursor").textValue()
        if (next.isNullOrEmpty() || !seenCursors.add(next)) {
            return
        }
        cursor = next
    }
}

/** Removes the key's client, then its mapping, also when the client is already gone. */
private fun remove(key: String, settings: Settings, out: PrintStream) {
    val mapFile = settings.mapFile!!
    val lock = takeLock(mapFile)
    try {
        val map = loadMap(mapFile)
        val clientId = mappedClient(map, key)
        val answer = call(settings, "POST", removePath, json.createObjectNode().put("client_id", clientId))
        val error = answer["error"]
        if (error != null && !error.isNull) {
            val message = error.path("message").textValue() ?: ""
            if (message != clientDoesNotExist) {
                throw refused(message)
            }
        }
        (map["clients"] as ObjectNode).remove(key)
        saveMap(mapFile, map)
        out.println(json.writeValueAsString(json.createObjectNode().put("removed", clientId)))
    } finally {
        Files.deleteIfExists(lock)
    }
}

/** The auth-client request: a new client without client_id, or a reissue of the mapped one. Never source_client_id. */
fun authClientBody(clientId: String?): ObjectNode {
    val body = json.createObjectNode().put("description", description).put("device_spec", deviceSpec)
    if (clientId != null) {
        body.put("client_id", clientId)
    }
    return body
}

/**
 * The client id and client JWT of an auth-client answer. A refusal with either client limit flag is
 * the client limit (78); "Client does not exist." throws ClientMissingException; other refusals
 * fail. The JWT's client_id claim must match the answer, and a reissue must answer the mapped
 * client. The claim check is not signature verification.
 */
private fun authClientResult(answer: ObjectNode, expected: String?): Pair<String, String> {
    val error = answer["error"]
    if (error != null && !error.isNull) {
        if (error.path("client_limit_exceeded").asBoolean(false) || error.path("upgrade_required").asBoolean(false)) {
            throw ToolException(exitConfig, clientLimitMessage)
        }
        val message = error.path("message").textValue() ?: ""
        if (message == clientDoesNotExist) {
            throw ClientMissingException()
        }
        throw refused(message)
    }
    val id = answer.path("client_id").textValue() ?: ""
    val jwt = answer.path("by_client_jwt").textValue() ?: ""
    val parts = jwt.split(".")
    if (!ids.matches(id) || parts.size != 3 || parts.any { it.isEmpty() } || !Regex("[A-Za-z0-9_-]+").matches(parts[1])) {
        throw ToolException(exitFailure, "the URnetwork API answered an invalid client")
    }
    val claim = try {
        json.readTree(Base64.getUrlDecoder().decode(parts[1]))?.path("client_id")?.textValue()
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
    if (claim != id || (expected != null && expected != id)) {
        throw ToolException(exitFailure, "the URnetwork API answered a client JWT for another client")
    }
    return id to jwt
}

/** The cap request: client_id and only the given fields. A cleared cap is JSON null; byte counts are integers. */
fun capBody(clientId: String, options: CapOptions): ObjectNode {
    val body = json.createObjectNode().put("client_id", clientId)
    if (options.monthlyGiven) {
        options.monthly?.let { body.put("monthly_byte_limit", it) } ?: body.putNull("monthly_byte_limit")
    }
    if (options.totalGiven) {
        options.total?.let { body.put("total_byte_limit", it) } ?: body.putNull("total_byte_limit")
    }
    if (options.resetTotal) {
        body.put("reset_total", true)
    }
    return body
}

/** The cap options; at least one, each at most once. A usage error is a configuration error. */
fun parseCapOptions(args: List<String>): CapOptions {
    val capUsage = "cap needs at least one of --monthly <bytes>|null, --total <bytes>|null and --reset-total, each at most once"
    val options = CapOptions()
    var index = 0
    while (index < args.size) {
        val option = args[index]
        val hasValue = index + 1 < args.size
        when {
            option == "--monthly" && !options.monthlyGiven && hasValue -> {
                options.monthlyGiven = true
                options.monthly = byteLimit(args[index + 1])
                index += 2
            }
            option == "--total" && !options.totalGiven && hasValue -> {
                options.totalGiven = true
                options.total = byteLimit(args[index + 1])
                index += 2
            }
            option == "--reset-total" && !options.resetTotal -> {
                options.resetTotal = true
                index += 1
            }
            else -> throw config(capUsage)
        }
    }
    if (!options.monthlyGiven && !options.totalGiven && !options.resetTotal) {
        throw config(capUsage)
    }
    return options
}

/** A byte count from 0 to 9223372036854775807 without units, or null. */
private fun byteLimit(text: String): Long? {
    if (text == "null") {
        return null
    }
    return text.takeIf { byteCounts.matches(it) }?.toLongOrNull()
        ?: throw config("a byte count is a decimal integer from 0 to 9223372036854775807 without units, or null")
}

/** The cap object of an answer, as one line of JSON; a refusal fails. */
private fun capObject(answer: ObjectNode): String {
    checkRefusal(answer)
    return json.writeValueAsString(answer)
}

/** Fails when an answer carries a refusal. */
private fun checkRefusal(answer: ObjectNode) {
    val error = answer["error"]
    if (error != null && !error.isNull) {
        throw refused(error.path("message").textValue() ?: "")
    }
}

/** The client mapped to key; an unmapped key is a configuration error. */
private fun mappedClient(map: ObjectNode, key: String): String =
    map["clients"].path(key).textValue() ?: throw config("no client is mapped for this key; provision it first")

/** A valid key: user:<service-user-id>, optionally with :<installation-id>. */
private fun checkKey(key: String): String =
    if (keys.matches(key)) key else throw config("expected a key user:<service-user-id>[:<installation-id>]")

/** The client JWT file: its directory must exist; a symlink or a directory is refused. */
private fun checkJwtFile(text: String): Path {
    val path = try {
        Path.of(text).toAbsolutePath()
    } catch (e: InvalidPathException) {
        throw config("the client JWT file path is not valid")
    }
    if (path.parent == null || !Files.isDirectory(path.parent) || Files.isDirectory(path) || Files.isSymbolicLink(path)) {
        throw config("the client JWT file needs an existing directory and must not be a symlink")
    }
    return path
}

/** The settings, checked: the root credential, the API origin and, when the command uses it, the map file. */
private fun settings(env: (String) -> String?, transport: (URI, String) -> Transport, map: Boolean): Settings {
    val root = env("URNETWORK_ROOT_JWT") ?: ""
    if (root.isEmpty() || root.any { it <= ' ' || it > '~' }) {
        throw config("set URNETWORK_ROOT_JWT to the root credential (an API key or a network JWT) from the backend's secret store")
    }
    val origin = endpoint(env("URNETWORK_API_URL") ?: "https://api.bringyour.com")
    var mapFile: Path? = null
    if (map) {
        mapFile = try {
            env("URNETWORK_CLIENT_MAP")?.let { Path.of(it) }
        } catch (e: InvalidPathException) {
            null
        }
        if (mapFile == null || !mapFile.isAbsolute || mapFile.parent == null || !Files.isDirectory(mapFile.parent)) {
            throw config("set URNETWORK_CLIENT_MAP to an absolute file path in an existing private, service-owned directory")
        }
    }
    return Settings(transport(origin, root), mapFile)
}

/** The API origin: HTTPS, or explicit loopback HTTP for local mocks. */
fun endpoint(value: String): URI {
    val u = try {
        URI(value)
    } catch (e: Exception) {
        throw config("URNETWORK_API_URL must be an HTTPS origin")
    }
    val local = u.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
    if (u.host == null || u.userInfo != null || (u.rawPath ?: "") !in setOf("", "/") || u.rawQuery != null || u.rawFragment != null ||
        !(u.scheme == "https" || (u.scheme == "http" && local))
    ) {
        throw config("URNETWORK_API_URL must be an HTTPS origin, or explicit loopback HTTP for a mock")
    }
    return URI.create("${u.scheme}://${u.rawAuthority}/")
}

/**
 * The JSON object of a 2xx answer. HTTP 401 or 403 is the root credential refused (78); another
 * status, an unreachable API or a body that is not a JSON object is a failure (1). A refusal inside
 * the object (an "error" member) is for the caller.
 */
private fun call(settings: Settings, method: String, pathAndQuery: String, body: ObjectNode?): ObjectNode {
    val response = try {
        settings.transport.send(ApiRequest(method, pathAndQuery, body?.let { json.writeValueAsString(it) }))
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ToolException(exitFailure, "interrupted")
    } catch (e: IOException) {
        throw ToolException(exitFailure, "could not reach the URnetwork API")
    }
    when {
        response.status == 401 || response.status == 403 -> throw ToolException(exitConfig, "the URnetwork API refused the root credential")
        response.status == 404 -> throw ToolException(exitFailure, "the URnetwork API answered HTTP 404; a server without this route answers 404")
        response.status !in 200..299 -> throw ToolException(exitFailure, "the URnetwork API answered HTTP ${response.status}")
    }
    val node = try {
        json.readTree(response.body)
    } catch (e: JsonProcessingException) {
        null
    }
    return node as? ObjectNode ?: throw ToolException(exitFailure, "the URnetwork API answered something other than a JSON object")
}

/** HTTPS to the API with the root credential: 15 seconds, no redirects, a bounded answer. */
private fun httpTransport(origin: URI, root: String): Transport {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
    return Transport { request ->
        val builder = HttpRequest.newBuilder(origin.resolve(request.pathAndQuery)).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer $root")
        if (request.body != null) {
            builder.header("Content-Type", "application/json").method(request.method, HttpRequest.BodyPublishers.ofString(request.body))
        } else {
            builder.method(request.method, HttpRequest.BodyPublishers.noBody())
        }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            val data = input.readNBytes(limit + 1)
            if (limit < data.size) {
                throw IOException("the answer is too large")
            }
            ApiResponse(response.statusCode(), String(data, Charsets.UTF_8))
        }
    }
}

/**
 * The map in file; an empty map when the file does not exist. A map with fields this tool does not
 * write, such as the token server's pending_caps, is refused, so that two tools never rewrite each
 * other's map.
 */
fun loadMap(file: Path): ObjectNode {
    if (Files.isSymbolicLink(file)) {
        throw config("the client map must be a regular file")
    }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        return json.createObjectNode().put("version", 1).also { it.set<ObjectNode>("clients", json.createObjectNode()) }
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > limit) {
        throw config("the client map must be a regular file")
    }
    if (posix(file) && !setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            .containsAll(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))
    ) {
        throw config("the client map must be private (0600)")
    }
    val parsed = try {
        json.readTree(Files.readString(file))
    } catch (e: JsonProcessingException) {
        null
    }
    val map = parsed as? ObjectNode ?: throw config("the client map is not a JSON object")
    for ((name, _) in map.properties()) {
        if (name != "version" && name != "clients") {
            throw config("the client map has fields this tool does not write, such as the token server's pending_caps; give each tool its own map")
        }
    }
    if (!map.path("version").isIntegralNumber || map["version"].intValue() != 1 || !map.path("clients").isObject) {
        throw config("the client map is not version 1 with a clients object")
    }
    val seen = HashSet<String>()
    for ((key, value) in map["clients"].properties()) {
        val id = value.textValue()
        if (!keys.matches(key) || id == null || !ids.matches(id) || !seen.add(id)) {
            throw config("the client map has an invalid entry")
        }
    }
    return map
}

/** Replaces the map atomically, private to its owner. */
fun saveMap(file: Path, map: ObjectNode) {
    val ordered = json.createObjectNode().put("version", 1)
    ordered.set<JsonNode>("clients", map["clients"])
    writePrivate(file, json.writeValueAsBytes(ordered))
}

/**
 * Takes the map's exclusive lock: a <map>.lock directory created exclusively and held through the
 * remote calls and the map update. The C# tool creates a <map>.lock file at the same path, so the
 * tools exclude each other.
 */
fun takeLock(mapFile: Path): Path {
    val lock = Path.of("$mapFile.lock")
    try {
        if (posix(mapFile.parent)) {
            Files.createDirectory(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else {
            Files.createDirectory(lock)
        }
    } catch (e: FileAlreadyExistsException) {
        throw ToolException(exitFailure, "another run holds the client map's lock; retry, and remove a stale .lock only after confirming that no tool still runs")
    }
    return lock
}

/** Replaces file atomically: a private temporary file in the same directory, synced, then renamed over it. */
fun writePrivate(file: Path, data: ByteArray) {
    val dir = file.toAbsolutePath().parent
    val prefix = ".${file.fileName}."
    val temp = if (posix(dir)) {
        Files.createTempFile(dir, prefix, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createTempFile(dir, prefix, ".tmp")
    }
    try {
        FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}

/** Whether the file system of path has POSIX permissions; Windows does not. */
fun posix(path: Path) = "posix" in path.fileSystem.supportedFileAttributeViews()

/** A query parameter value, URL-encoded. */
private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

/** Text safe for one stderr line: no control characters, bounded. */
private fun printable(text: String): String = text.take(200).map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
