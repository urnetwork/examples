// Installation state for one embed installation, kept in one private directory
// named by URNETWORK_EMBED_STATE_DIR (EMBED_CONTRACT.md, "Installation
// state"):
//
// - client.jwt: the installation's scoped client JWT. The token fetch writes
//   it (Token.kt), or your backend tool's provision command, or you; the app
//   rewrites it whenever the SDK refreshes the token.
// - instance-id: this installation's UUID, created on first run and kept for
//   the life of the installation. It is also the installation ID that the app
//   sends to the token server.
// - logs/: the SDK's bounded log files.
//
// Every file is replaced atomically with owner-only permissions. Where the
// file system has POSIX permissions, the directory and its files must not be
// accessible to group or others, and a symlinked file is refused; Windows
// relies on the access control of the user's profile directory. Nothing here
// calls the SDK.
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.EnumSet
import java.util.Locale
import java.util.UUID

const val clientJwtFileName = "client.jwt"
const val instanceIdFileName = "instance-id"

// the sdk's bounded log files
const val logDirName = "logs"

// the largest state file the app reads
const val stateFileByteLimit = 64 * 1024
const val defaultApiUrl = "https://api.bringyour.com"

val ownerReadWrite: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
val ownerAll: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
val groupAndOthers: Set<PosixFilePermission> = EnumSet.of(
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_WRITE,
    PosixFilePermission.OTHERS_EXECUTE,
)

// the 32 hex digits of a uuid, ascii only
private val uuidHex = Regex("[0-9a-fA-F]{32}")

/** A configuration or credential problem that a restart does not fix (exit 78). */
class ConfigException(message: String) : Exception(message)

/**
 * The installation's settings, read once at start. tokenServer is null when no token server is
 * configured; the app then uses the client.jwt already in the state directory. The demo session is
 * a bearer secret, so toString leaves it out.
 */
class EmbedSettings(
    val stateDir: Path,
    val instanceId: String,
    val tokenServer: URI?,
    val demoSession: String?,
    val apiOrigin: URI,
) {
    /** The state directory, ids and origins only. */
    override fun toString() =
        "EmbedSettings(stateDir=$stateDir, instanceId=$instanceId, tokenServer=$tokenServer, apiOrigin=$apiOrigin)"
}

/**
 * Checks the settings from the environment and loads the installation state, creating instance-id
 * on first run. Every error is a configuration error: restarting does not fix it.
 */
fun loadSettings(env: Map<String, String>): EmbedSettings {
    val stateDir = checkStateDir(env["URNETWORK_EMBED_STATE_DIR"])
    val apiOrigin = parseOrigin(env["URNETWORK_API_URL"].takeUnless { it.isNullOrEmpty() } ?: defaultApiUrl, "URNETWORK_API_URL")
    val tokenServerUrl = env["URNETWORK_TOKEN_SERVER_URL"]
    val demoSession = env["URNETWORK_DEMO_SESSION"]
    var tokenServer: URI? = null
    if (!tokenServerUrl.isNullOrEmpty()) {
        tokenServer = parseOrigin(tokenServerUrl, "URNETWORK_TOKEN_SERVER_URL")
        if (!isSessionToken(demoSession)) {
            throw ConfigException("set URNETWORK_DEMO_SESSION to the demo session token for the token server")
        }
    } else if (!demoSession.isNullOrEmpty()) {
        throw ConfigException("URNETWORK_DEMO_SESSION is set without URNETWORK_TOKEN_SERVER_URL")
    }
    val instanceId = loadOrCreateInstanceId(stateDir)
    return EmbedSettings(stateDir, instanceId, tokenServer, if (tokenServer == null) null else demoSession, apiOrigin)
}

/**
 * An origin that the app sends credentials to: an HTTPS origin, or explicit loopback HTTP
 * (localhost, 127.0.0.1, [::1]) for local testing; no credentials, path, query or fragment. The
 * result ends in "/". name is the setting, for the error.
 */
fun parseOrigin(value: String, name: String): URI {
    val uri = try {
        URI(value)
    } catch (e: URISyntaxException) {
        throw ConfigException("$name is not a valid URL")
    }
    val host = uri.host
    val loopback = host?.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "[::1]")
    if (!uri.isAbsolute || host == null || uri.rawUserInfo != null || (uri.rawPath ?: "") !in setOf("", "/") ||
        uri.rawQuery != null || uri.rawFragment != null ||
        !(uri.scheme == "https" || (uri.scheme == "http" && loopback))
    ) {
        throw ConfigException("$name must be an HTTPS origin, or loopback HTTP (localhost, 127.0.0.1 or [::1]) for local testing")
    }
    return URI.create("${uri.scheme}://${uri.rawAuthority}/")
}

/**
 * The state directory, which must be an existing absolute directory, private to its owner where the
 * file system has POSIX permissions.
 */
fun checkStateDir(stateDirText: String?): Path {
    if (stateDirText.isNullOrEmpty()) {
        throw ConfigException("set URNETWORK_EMBED_STATE_DIR to this installation's private state directory")
    }
    val stateDir = try {
        Path.of(stateDirText)
    } catch (e: InvalidPathException) {
        throw ConfigException("URNETWORK_EMBED_STATE_DIR is not a valid path")
    }
    if (!stateDir.isAbsolute) {
        throw ConfigException("URNETWORK_EMBED_STATE_DIR must be an absolute path")
    }
    try {
        if (!Files.readAttributes(stateDir, BasicFileAttributes::class.java).isDirectory) {
            throw ConfigException("URNETWORK_EMBED_STATE_DIR is not a directory")
        }
        if (hasPosixPermissions(stateDir) && Files.getPosixFilePermissions(stateDir).any { it in groupAndOthers }) {
            throw ConfigException("the state directory must be private to its owner (chmod 700)")
        }
    } catch (e: IOException) {
        throw ConfigException("state directory: ${describe(e)}")
    }
    return stateDir
}

/**
 * Reads a regular, private state file of bounded size. A symlink is refused so that the credential
 * cannot be redirected to another file.
 */
fun readPrivateFile(path: Path): ByteArray {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attributes.isRegularFile) {
        throw IOException("not a regular file: $path")
    }
    if (stateFileByteLimit < attributes.size()) {
        throw IOException("file is too large: $path")
    }
    if (hasPosixPermissions(path) &&
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).any { it in groupAndOthers }
    ) {
        throw IOException("file must be private to its owner (chmod 600): $path")
    }
    // no-follow again at open, and a bounded read in case the file grew
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
        val data = input.readNBytes(stateFileByteLimit + 1)
        if (stateFileByteLimit < data.size) {
            throw IOException("file is too large: $path")
        }
        return data
    }
}

/**
 * Replaces a state file atomically: a private temporary file in the same directory is written,
 * synced and renamed over the old file.
 */
fun writePrivateFile(path: Path, data: ByteArray) {
    val dir = path.toAbsolutePath().parent
    val prefix = ".${path.fileName}."
    val posix = hasPosixPermissions(dir)
    val tempPath = if (posix) {
        Files.createTempFile(dir, prefix, ".tmp", PosixFilePermissions.asFileAttribute(ownerReadWrite))
    } else {
        Files.createTempFile(dir, prefix, ".tmp")
    }
    var replaced = false
    try {
        if (posix) {
            // owner-only whatever the umask
            Files.setPosixFilePermissions(tempPath, ownerReadWrite)
        }
        FileChannel.open(tempPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        replaced = true
    } finally {
        if (!replaced) {
            try {
                Files.deleteIfExists(tempPath)
            } catch (e: IOException) {
                // keep the original failure
            }
        }
    }
}

/** Creates a directory private to its owner, or keeps an existing one. */
fun createPrivateDirectory(dir: Path) {
    if (Files.isDirectory(dir)) {
        return
    }
    if (hasPosixPermissions(dir)) {
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(ownerAll))
    } else {
        Files.createDirectory(dir)
    }
}

/** Whether the file system of path has POSIX permissions; Windows does not. */
fun hasPosixPermissions(path: Path): Boolean = "posix" in path.fileSystem.supportedFileAttributeViews()

/** The client.jwt in the state directory; null when it does not exist. Other problems are configuration errors. */
fun readClientJwt(stateDir: Path): String? =
    try {
        String(readPrivateFile(stateDir.resolve(clientJwtFileName)), Charsets.UTF_8).trim()
    } catch (e: NoSuchFileException) {
        null
    } catch (e: IOException) {
        throw ConfigException("read $clientJwtFileName from the state directory: ${describe(e)}")
    }

/** Saves the client JWT as client.jwt; a failure is a configuration error. */
fun saveClientJwt(stateDir: Path, clientJwt: String) {
    try {
        writePrivateFile(stateDir.resolve(clientJwtFileName), "$clientJwt\n".toByteArray(Charsets.UTF_8))
    } catch (e: IOException) {
        // never print the token itself
        throw ConfigException("write $clientJwtFileName: ${describe(e)}")
    }
}

/**
 * The client_id claim of a scoped client JWT, in canonical form. This checks the token's shape and
 * claim only; the SDK and the server verify the token itself. A network JWT has no client_id claim
 * and is refused.
 */
fun parseClientJwtClientId(clientJwt: String): String {
    val notJwt = "client.jwt does not hold a JWT; obtain the scoped client JWT from your backend"
    val parts = clientJwt.split(".")
    if (parts.size != 3 || parts.any { it.isEmpty() }) {
        throw ConfigException(notJwt)
    }
    val payload = try {
        Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
    } catch (e: IllegalArgumentException) {
        throw ConfigException(notJwt)
    }
    val clientIdClaim = try {
        parseNullableJsonObject(String(payload, Charsets.UTF_8))?.stringMember("client_id")
    } catch (e: IllegalArgumentException) {
        // a payload that is not json, or a client_id that is not a string
        null
    }
    if (clientIdClaim.isNullOrEmpty()) {
        throw ConfigException("the JWT has no client_id claim; use a scoped client JWT, not a network JWT")
    }
    return parseId(clientIdClaim) ?: throw ConfigException("the JWT has an invalid client_id claim")
}

/** Reads instance-id, creating it on first run. An installation keeps one instance id for its lifetime. */
fun loadOrCreateInstanceId(stateDir: Path): String {
    val path = stateDir.resolve(instanceIdFileName)
    try {
        val instanceId = parseId(String(readPrivateFile(path), Charsets.UTF_8).trim())
        return instanceId ?: throw ConfigException("$instanceIdFileName does not hold a UUID")
    } catch (e: NoSuchFileException) {
        // first run
    } catch (e: IOException) {
        throw ConfigException("read $instanceIdFileName from the state directory: ${describe(e)}")
    }
    val instanceId = UUID.randomUUID().toString()
    try {
        writePrivateFile(path, "$instanceId\n".toByteArray(Charsets.UTF_8))
    } catch (e: IOException) {
        throw ConfigException("write $instanceIdFileName: ${describe(e)}")
    }
    return instanceId
}

/**
 * The canonical lowercase form of a UUID, given in its 36-character form or as 32 hex digits; null
 * when the text is not one.
 */
fun parseId(text: String?): String? {
    if (text == null) {
        return null
    }
    val hex = when (text.length) {
        36 -> {
            if (text[8] != '-' || text[13] != '-' || text[18] != '-' || text[23] != '-') {
                return null
            }
            text.substring(0, 8) + text.substring(9, 13) + text.substring(14, 18) +
                text.substring(19, 23) + text.substring(24)
        }
        32 -> text
        else -> return null
    }
    if (!uuidHex.matches(hex)) {
        return null
    }
    val lower = hex.lowercase(Locale.ROOT)
    return listOf(
        lower.substring(0, 8),
        lower.substring(8, 12),
        lower.substring(12, 16),
        lower.substring(16, 20),
        lower.substring(20),
    ).joinToString("-")
}

/** A short reason for a file error, without a stack trace. */
fun describe(e: IOException): String = when (e) {
    is NoSuchFileException -> "no such file: ${e.message}"
    is AccessDeniedException -> "permission denied: ${e.message}"
    // a FileSystemException message names the file and the reason
    else -> e.message ?: e.javaClass.simpleName
}
