// Installation state for one embed installation, kept in one private directory (EMBED_CONTRACT.md,
// "Installation state"): embed/ in the app's no-backup files directory, which backups and device
// transfers leave out.
//
//   - client.jwt: the installation's scoped client JWT. The token fetch writes it on every start
//     (without a token server, the debug-only import writes it); the app rewrites it whenever the
//     sdk refreshes the token.
//   - instance-id: this installation's UUID, created on first run. It is also the installation ID
//     that the app sends to the token server.
//   - token-server.json: {"url": "...", "session": "..."}, the token server origin and the demo
//     session token. Debug builds import it over adb; your app's own sign-in replaces it.
//   - logs/: the sdk's bounded log files.
//
// Every file is replaced atomically with owner-only permissions, and the directory and its files
// must not be accessible to group or others. These functions use only java.nio and
// kotlinx.serialization, so the unit tests run them on the JVM; a host file system without POSIX
// permissions (Windows) skips the permission checks.
package com.example.urnetwork.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.Locale
import java.util.UUID

// the state directory's name in the app's no-backup files directory
const val stateDirName = "embed"
const val clientJwtFileName = "client.jwt"
const val instanceIdFileName = "instance-id"
const val tokenServerFileName = "token-server.json"
const val logDirName = "logs"

// the largest state file the app reads
private const val stateFileByteLimit = 64 * 1024

// the longest demo session token the app accepts
private const val sessionLengthLimit = 1024

// The loopback hosts a token server may use over plain HTTP, for local testing. The emulator
// reaches the host's loopback at 10.0.2.2, which debuggable builds also accept; release builds
// allow only HTTPS in their network security configuration anyway.
private val loopbackHosts = setOf("localhost", "127.0.0.1", "[::1]")
private const val emulatorHostLoopback = "10.0.2.2"

// whether the file system has POSIX permissions: always on Android, not on a Windows host
private val posixPermissions = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

private val ownerOnlyDirPermissions = PosixFilePermissions.fromString("rwx------")
private val ownerOnlyFilePermissions = PosixFilePermissions.fromString("rw-------")
private val groupOrOtherPermissions = setOf(
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_WRITE,
    PosixFilePermission.OTHERS_EXECUTE,
)

/**
 * A configuration or credential problem that restarting does not fix: the Android form of the
 * console examples' exit code 78. The app shows the message and does not start.
 */
class EmbedConfigException(message: String) : Exception(message)

/**
 * The token server settings in token-server.json: its HTTPS origin (or a loopback HTTP origin for
 * local testing) and the demo session token. The session is a bearer secret: never print or log
 * it.
 */
class TokenServerConfig(val url: String, val session: String)

/** The client JWT in client.jwt and its client_id claim. Never print or log the token. */
class StoredClientJwt(val clientJwt: String, val clientId: String)

/** The state directory must be an existing absolute directory, private to its owner. */
fun checkStateDir(stateDir: String) {
    if (stateDir == "") {
        throw EmbedConfigException("the embed state directory is not set")
    }
    val path = File(stateDir).toPath()
    if (!path.isAbsolute) {
        throw EmbedConfigException("the embed state directory must be an absolute path")
    }
    val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java)
    } catch (e: NoSuchFileException) {
        throw EmbedConfigException("the embed state directory does not exist")
    } catch (e: IOException) {
        throw EmbedConfigException("embed state directory: ${e.message}")
    }
    if (!attributes.isDirectory) {
        throw EmbedConfigException("the embed state directory is not a directory")
    }
    if (posixPermissions && Files.getPosixFilePermissions(path).any { it in groupOrOtherPermissions }) {
        throw EmbedConfigException("the embed state directory must be private to its owner (mode 700)")
    }
}

/**
 * Creates the state directory, private to its owner, unless it exists, then checks it. The app
 * creates it on first use; an existing directory that others can access is refused.
 */
fun ensureStateDir(stateDir: File) {
    val path = stateDir.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        try {
            if (posixPermissions) {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(ownerOnlyDirPermissions))
            } else {
                Files.createDirectory(path)
            }
        } catch (e: FileAlreadyExistsException) {
            // created at the same time by another caller; checked below
        } catch (e: IOException) {
            throw EmbedConfigException("create the embed state directory: ${e.message}")
        }
    }
    checkStateDir(stateDir.path)
}

/**
 * Creates logs/ in the state directory, private to its owner, unless it exists, and returns it. The
 * sdk keeps its log files there.
 */
fun ensureLogDir(stateDir: File): File {
    val logDir = File(stateDir, logDirName)
    val path = logDir.toPath()
    if (!Files.isDirectory(path)) {
        try {
            if (posixPermissions) {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(ownerOnlyDirPermissions))
            } else {
                Files.createDirectory(path)
            }
        } catch (e: FileAlreadyExistsException) {
            // created at the same time by another caller
        }
    }
    return logDir
}

/**
 * Reads a regular, private state file of bounded size. A symlink is refused so that the credential
 * cannot be redirected to another file. A missing file throws NoSuchFileException.
 */
fun readPrivateFile(file: File): ByteArray {
    val path = file.toPath()
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attributes.isRegularFile) {
        throw IOException("${file.name} is not a regular file")
    }
    if (stateFileByteLimit < attributes.size()) {
        throw IOException("${file.name} is too large")
    }
    if (posixPermissions && Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).any { it in groupOrOtherPermissions }) {
        throw IOException("${file.name} must be private to its owner (mode 600)")
    }
    return Files.readAllBytes(path)
}

/**
 * Replaces a state file atomically: a private temporary file in the same directory is written,
 * synced and renamed over the old file.
 */
fun writePrivateFile(file: File, data: ByteArray) {
    val path = file.toPath()
    val tempPath = if (posixPermissions) {
        Files.createTempFile(path.parent, ".${file.name}.", ".tmp", PosixFilePermissions.asFileAttribute(ownerOnlyFilePermissions))
    } else {
        Files.createTempFile(path.parent, ".${file.name}.", ".tmp")
    }
    try {
        if (posixPermissions) {
            // the umask may have removed bits; set the mode exactly
            Files.setPosixFilePermissions(tempPath, ownerOnlyFilePermissions)
        }
        FileChannel.open(tempPath, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        Files.deleteIfExists(tempPath)
        throw e
    }
}

/**
 * A UUID in the sdk's id forms (36 characters with dashes, or 32 hex digits) as the sdk's
 * canonical lowercase string; null when it is not one.
 */
fun parseUuid(text: String): String? {
    val dashed = text.length == 36 && text[8] == '-' && text[13] == '-' && text[18] == '-' && text[23] == '-'
    val hex = when {
        dashed -> text.replace("-", "")
        text.length == 32 -> text
        else -> return null
    }
    if (hex.length != 32 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        return null
    }
    val lower = hex.lowercase(Locale.ROOT)
    return "${lower.substring(0, 8)}-${lower.substring(8, 12)}-${lower.substring(12, 16)}-${lower.substring(16, 20)}-${lower.substring(20)}"
}

/**
 * The client_id claim of a scoped client JWT. This checks the token's shape and claim only; the
 * sdk and the server verify the token itself. A network JWT has no client_id claim and is
 * refused: an installation runs only with its own client.
 */
fun parseClientJwtClientId(clientJwt: String): String {
    val notJwt = EmbedConfigException("the client JWT is not a JWT; get a scoped client JWT from your backend")
    val parts = clientJwt.split(".")
    if (parts.size != 3 || parts.any { it == "" }) {
        throw notJwt
    }
    val payload = try {
        Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
    } catch (e: IllegalArgumentException) {
        throw notJwt
    }
    val claims = try {
        Json.parseToJsonElement(payload.decodeToString()) as? JsonObject
    } catch (e: Exception) {
        null
    }
    val clientIdClaim = (claims?.get("client_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (clientIdClaim.isNullOrEmpty()) {
        throw EmbedConfigException("the client JWT has no client_id claim; use a scoped client JWT, not a network JWT")
    }
    return parseUuid(clientIdClaim)
        ?: throw EmbedConfigException("the client JWT has an invalid client_id claim")
}

/** Writes client.jwt atomically. Never print or log the token. */
fun saveClientJwt(stateDir: File, clientJwt: String) {
    writePrivateFile(File(stateDir, clientJwtFileName), "${clientJwt.trim()}\n".toByteArray())
}

/**
 * Writes client.jwt, creating the state directory: what the debug import does with a scoped client
 * JWT from your backend tool's provision. The token must carry a client_id claim. Returns the
 * client id.
 */
fun importClientJwt(stateDir: File, clientJwt: String): String {
    val trimmedClientJwt = clientJwt.trim()
    val clientId = parseClientJwtClientId(trimmedClientJwt)
    ensureStateDir(stateDir)
    try {
        saveClientJwt(stateDir, trimmedClientJwt)
    } catch (e: IOException) {
        throw EmbedConfigException("write $clientJwtFileName: ${e.message}")
    }
    return clientId
}

/**
 * Reads client.jwt and its client_id claim; null when the file is missing. A token without a
 * client_id claim, such as a network JWT, is a configuration error.
 */
fun loadClientJwt(stateDir: File): StoredClientJwt? {
    val data = try {
        readPrivateFile(File(stateDir, clientJwtFileName))
    } catch (e: NoSuchFileException) {
        return null
    } catch (e: IOException) {
        throw EmbedConfigException("read $clientJwtFileName from the state directory: ${e.message}")
    }
    val clientJwt = data.decodeToString().trim()
    return StoredClientJwt(clientJwt = clientJwt, clientId = parseClientJwtClientId(clientJwt))
}

/**
 * Reads instance-id, creating it on first run. An installation keeps one instance id for its
 * lifetime; it is also the installation ID it sends to the token server.
 */
fun loadOrCreateInstanceId(stateDir: File): String {
    val file = File(stateDir, instanceIdFileName)
    val data = try {
        readPrivateFile(file)
    } catch (e: NoSuchFileException) {
        null
    } catch (e: IOException) {
        throw EmbedConfigException("read $instanceIdFileName from the state directory: ${e.message}")
    }
    if (data != null) {
        return parseUuid(data.decodeToString().trim())
            ?: throw EmbedConfigException("$instanceIdFileName does not hold a UUID")
    }
    val instanceId = UUID.randomUUID().toString()
    try {
        writePrivateFile(file, "$instanceId\n".toByteArray())
    } catch (e: IOException) {
        throw EmbedConfigException("write $instanceIdFileName: ${e.message}")
    }
    return instanceId
}

/**
 * Checks a token server URL and returns its origin: an HTTPS origin, or plain HTTP to a loopback
 * host for local testing (and the emulator's 10.0.2.2 when allowEmulatorHost). The URL may not
 * carry credentials, a path beyond "/", a query or a fragment; the app appends the route itself.
 */
fun tokenServerOrigin(url: String, allowEmulatorHost: Boolean): String {
    val invalid = { reason: String -> EmbedConfigException("the token server URL $reason") }
    val uri = try {
        URI(url.trim())
    } catch (e: URISyntaxException) {
        throw invalid("is not a URL")
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    if (scheme == null || host.isNullOrEmpty() || uri.isOpaque) {
        throw invalid("must be an origin such as https://tokens.example.com")
    }
    if (uri.rawUserInfo != null) {
        throw invalid("must not carry credentials")
    }
    if (!(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") || uri.rawQuery != null || uri.rawFragment != null) {
        throw invalid("must be an origin, with no path, query or fragment")
    }
    when (scheme) {
        "https" -> {}
        "http" -> if (host !in loopbackHosts && !(allowEmulatorHost && host == emulatorHostLoopback)) {
            throw invalid("must use HTTPS; plain HTTP is only for a loopback token server")
        }
        else -> throw invalid("must use HTTPS")
    }
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "$scheme://$host$port"
}

/** Checks a demo session token: visible ASCII, as an Authorization header needs. */
private fun checkSession(session: String) {
    if (session == "" || sessionLengthLimit < session.length || !session.all { it in '!'..'~' }) {
        throw EmbedConfigException("the demo session must be a non-empty token of visible ASCII characters")
    }
}

/**
 * Reads token-server.json; null when the file is missing, and then the app uses the client.jwt in
 * its state.
 */
fun loadTokenServerConfig(stateDir: File, allowEmulatorHost: Boolean): TokenServerConfig? {
    val data = try {
        readPrivateFile(File(stateDir, tokenServerFileName))
    } catch (e: NoSuchFileException) {
        return null
    } catch (e: IOException) {
        throw EmbedConfigException("read $tokenServerFileName from the state directory: ${e.message}")
    }
    val json = try {
        Json.parseToJsonElement(data.decodeToString()) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: throw EmbedConfigException("$tokenServerFileName is not a JSON object")
    val text = { name: String -> (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty() }
    val session = text("session")
    checkSession(session)
    return TokenServerConfig(url = tokenServerOrigin(text("url"), allowEmulatorHost), session = session)
}

/**
 * Writes token-server.json, creating the state directory: what the debug import does for local
 * testing. Returns the token server origin; never print or log the session.
 */
fun importTokenServerConfig(stateDir: File, url: String, session: String, allowEmulatorHost: Boolean): String {
    val origin = tokenServerOrigin(url, allowEmulatorHost)
    checkSession(session)
    ensureStateDir(stateDir)
    val json = buildJsonObject {
        put("url", origin)
        put("session", session)
    }
    try {
        writePrivateFile(File(stateDir, tokenServerFileName), json.toString().toByteArray())
    } catch (e: IOException) {
        throw EmbedConfigException("write $tokenServerFileName: ${e.message}")
    }
    return origin
}
