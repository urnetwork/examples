// Installation state for one provider install, kept in one private directory
// named by URNETWORK_PROVIDER_STATE_DIR (PROVIDER_CONTRACT.md, "Installation
// state"):
//
// - client.jwt: the scoped client credential that the developer's backend
//   issued for this installation. The developer writes it; the app rewrites it
//   whenever the SDK refreshes the token.
// - instance-id: this installation's UUID, created on first run.
// - identity.json: the provider identity (client key seed, provide TLS
//   certificate and key, extender seed), created on first run so the provider
//   keeps one identity across restarts.
//
// Every file is replaced atomically with owner-only permissions. Where the
// file system has POSIX permissions, the directory and its files must not be
// accessible to group or others; Windows relies on the access control of the
// user's profile directory. One process owns a state directory at a time.
// Nothing here calls the SDK.
import java.io.IOException
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val clientJwtFileName = "client.jwt"
const val instanceIdFileName = "instance-id"
const val identityFileName = "identity.json"

// the sdk's bounded log files
const val logDirName = "logs"

// the largest state file the app reads
const val stateFileByteLimit = 64 * 1024

const val providerIdentityVersion = 1
const val clientKeySeedByteCount = 32

val ownerReadWrite: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
val ownerAll: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
private val groupAndOthers: Set<PosixFilePermission> = EnumSet.of(
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
 * identity.json: the provider identity of one client. Byte fields are standard base64 in the file.
 * An identity belongs to one client: a newly provisioned client gets a new identity. Holds private
 * keys, so toString leaves them out.
 */
class ProviderIdentity(
    val clientId: String,
    val clientKeySeed: ByteArray,
    val provideTlsCertificatePem: ByteArray,
    val provideTlsPrivateKeyPem: ByteArray,
    val extenderKeySeed: ByteArray,
) {
    /** Whether other holds the same client and keys. */
    fun sameAs(other: ProviderIdentity): Boolean =
        clientId == other.clientId &&
            clientKeySeed.contentEquals(other.clientKeySeed) &&
            provideTlsCertificatePem.contentEquals(other.provideTlsCertificatePem) &&
            provideTlsPrivateKeyPem.contentEquals(other.provideTlsPrivateKeyPem) &&
            extenderKeySeed.contentEquals(other.extenderKeySeed)

    /** The client id only. */
    override fun toString(): String = "ProviderIdentity(clientId=$clientId)"
}

/**
 * The installation state loaded at start. clientId is the client_id claim of clientJwt. identity
 * is null on first run and when the stored identity belongs to another client. The credential is a
 * bearer secret, so toString leaves it out.
 */
class ProviderConfig(
    val stateDir: Path,
    val clientJwt: String,
    val clientId: String,
    val instanceId: String,
    val identity: ProviderIdentity?,
) {
    /** The state directory and ids only. */
    override fun toString(): String =
        "ProviderConfig(stateDir=$stateDir, clientId=$clientId, instanceId=$instanceId)"
}

/**
 * Loads the installation state, creating instance-id on first run. Every error is a configuration
 * error: restarting does not fix it.
 */
fun loadProviderConfig(stateDirText: String?): ProviderConfig {
    val stateDir = checkStateDir(stateDirText)
    val clientJwtBytes = try {
        readPrivateFile(stateDir.resolve(clientJwtFileName))
    } catch (e: IOException) {
        throw ConfigException("read $clientJwtFileName from the state directory: ${describe(e)}")
    }
    val clientJwt = String(clientJwtBytes, Charsets.UTF_8).trim()
    val clientId = parseClientJwtClientId(clientJwt)
    val instanceId = loadOrCreateInstanceId(stateDir)
    val identity = loadProviderIdentity(stateDir, clientId)
    return ProviderConfig(
        stateDir = stateDir,
        clientJwt = clientJwt,
        clientId = clientId,
        instanceId = instanceId,
        identity = identity,
    )
}

/**
 * The state directory, which must be an existing absolute directory, private to its owner where the
 * file system has POSIX permissions.
 */
fun checkStateDir(stateDirText: String?): Path {
    if (stateDirText.isNullOrEmpty()) {
        throw ConfigException("set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory")
    }
    val stateDir = try {
        Path.of(stateDirText)
    } catch (e: InvalidPathException) {
        throw ConfigException("URNETWORK_PROVIDER_STATE_DIR is not a valid path")
    }
    if (!stateDir.isAbsolute) {
        throw ConfigException("URNETWORK_PROVIDER_STATE_DIR must be an absolute path")
    }
    try {
        if (!Files.readAttributes(stateDir, BasicFileAttributes::class.java).isDirectory) {
            throw ConfigException("URNETWORK_PROVIDER_STATE_DIR is not a directory")
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

/**
 * The client_id claim of a scoped client JWT, in canonical form. This checks the token's shape and
 * claim only; the SDK and the server verify the token itself.
 */
fun parseClientJwtClientId(clientJwt: String): String {
    val notJwt = "client.jwt does not hold a JWT; write the scoped client JWT from your backend"
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
        throw ConfigException("client.jwt has no client_id claim; write a scoped client JWT, not a network JWT")
    }
    return parseId(clientIdClaim) ?: throw ConfigException("client.jwt has an invalid client_id claim")
}

/**
 * Reads instance-id, creating it on first run. An installation keeps one instance id for its
 * lifetime.
 */
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
 * Reads identity.json for clientId. A missing file, or an identity of another client, returns null:
 * the device then creates a new identity, which the app saves. A file that does not parse, has
 * another version or a client key seed that is not 32 bytes is a configuration error.
 */
fun loadProviderIdentity(stateDir: Path, clientId: String): ProviderIdentity? {
    val data = try {
        readPrivateFile(stateDir.resolve(identityFileName))
    } catch (e: NoSuchFileException) {
        return null
    } catch (e: IOException) {
        throw ConfigException("read $identityFileName from the state directory: ${describe(e)}")
    }
    val identity = try {
        parseProviderIdentity(String(data, Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
        // not json, a field of the wrong type, or invalid base64
        null
    } ?: throw ConfigException("$identityFileName is not a valid provider identity; remove it to create a new one")
    if (identity.clientId != clientId) {
        return null
    }
    return identity
}

/** Writes identity.json. */
fun saveProviderIdentity(stateDir: Path, identity: ProviderIdentity) {
    val base64 = { bytes: ByteArray -> Base64.getEncoder().encodeToString(bytes) }
    val members = buildJsonObject {
        put("version", providerIdentityVersion)
        put("client_id", identity.clientId)
        put("client_key_seed", base64(identity.clientKeySeed))
        put("provide_tls_certificate_pem", base64(identity.provideTlsCertificatePem))
        put("provide_tls_private_key_pem", base64(identity.provideTlsPrivateKeyPem))
        if (identity.extenderKeySeed.isNotEmpty()) {
            put("extender_key_seed", base64(identity.extenderKeySeed))
        }
    }
    writePrivateFile(stateDir.resolve(identityFileName), members.toString().toByteArray(Charsets.UTF_8))
}

/**
 * The canonical lowercase form of a UUID, given in its 36-character form or as 32 hex digits; null
 * when the text is not one.
 */
fun parseId(text: String): String? {
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

/** identity.json's fields; null when the version or the client key seed is not valid. */
private fun parseProviderIdentity(text: String): ProviderIdentity? {
    val members = parseNullableJsonObject(text) ?: throw IllegalArgumentException("not a JSON object")
    if (members.longMember("version") != providerIdentityVersion.toLong()) {
        return null
    }
    // a standard base64 member as bytes; empty when it is absent or null
    val base64Member = { name: String ->
        members.stringMember(name)?.let { Base64.getDecoder().decode(it) } ?: ByteArray(0)
    }
    val clientKeySeed = base64Member("client_key_seed")
    if (clientKeySeed.size != clientKeySeedByteCount) {
        return null
    }
    return ProviderIdentity(
        clientId = members.stringMember("client_id") ?: "",
        clientKeySeed = clientKeySeed,
        provideTlsCertificatePem = base64Member("provide_tls_certificate_pem"),
        provideTlsPrivateKeyPem = base64Member("provide_tls_private_key_pem"),
        extenderKeySeed = base64Member("extender_key_seed"),
    )
}
