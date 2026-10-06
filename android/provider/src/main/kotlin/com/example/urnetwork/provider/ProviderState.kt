// Installation state for one provider install, kept in one private directory (PROVIDER_CONTRACT.md,
// "Installation state"): provider/ in the app's no-backup files directory, which backups and device
// transfers leave out.
//
//   - client.jwt: the scoped client credential that the developer's backend issued for this
//     installation. The app's sign-in flow writes it (in this example, the debug-only import); the
//     app rewrites it whenever the sdk refreshes the token.
//   - instance-id: this installation's UUID, created on first run.
//   - identity.json: the provider identity (client key seed, provide TLS certificate and key,
//     extender seed), created on first run so the provider keeps one identity across restarts.
//   - logs/: the sdk's bounded log files.
//
// Every file is replaced atomically with owner-only permissions, and the directory and its files
// must not be accessible to group or others. These functions use only java.nio and
// kotlinx.serialization, so the unit tests run them on the JVM; a host file system without POSIX
// permissions (Windows) skips the permission checks.
package com.example.urnetwork.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
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
const val stateDirName = "provider"
const val clientJwtFileName = "client.jwt"
const val instanceIdFileName = "instance-id"
const val identityFileName = "identity.json"
const val logDirName = "logs"

// the largest state file the app reads
private const val stateFileByteLimit = 64 * 1024

private const val providerIdentityVersion = 1

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
 * console examples' exit code 78. The app shows the message and does not provide.
 */
class ProviderConfigException(message: String) : Exception(message)

/**
 * identity.json, one JSON object with standard base64 byte fields. An identity belongs to one
 * client: a newly provisioned client gets a new identity. It holds private keys: never print or
 * log it.
 */
class ProviderIdentity(
    val clientId: String,
    val clientKeySeed: ByteArray,
    val provideTlsCertificatePem: ByteArray,
    val provideTlsPrivateKeyPem: ByteArray,
    // empty when the device has none
    val extenderKeySeed: ByteArray,
)

/** The installation state loaded at start. */
class ProviderConfig(
    val stateDir: File,
    val clientJwt: String,
    // the client_id claim of clientJwt
    val clientId: String,
    val instanceId: String,
    // null on first run, and when the stored identity belongs to another client
    val identity: ProviderIdentity?,
)

/**
 * Loads the installation state, creating instance-id on first run. Every error is a
 * ProviderConfigException: restarting does not fix it.
 */
fun loadProviderConfig(stateDir: String): ProviderConfig {
    checkStateDir(stateDir)
    val dir = File(stateDir)
    val clientJwtBytes = try {
        readPrivateFile(File(dir, clientJwtFileName))
    } catch (e: NoSuchFileException) {
        throw ProviderConfigException("$clientJwtFileName is missing from the state directory; import the scoped client JWT from your backend")
    } catch (e: IOException) {
        throw ProviderConfigException("read $clientJwtFileName from the state directory: ${e.message}")
    }
    val clientJwt = clientJwtBytes.decodeToString().trim()
    val clientId = parseClientJwtClientId(clientJwt)
    val instanceId = loadOrCreateInstanceId(dir)
    val identity = loadProviderIdentity(dir, clientId)
    return ProviderConfig(
        stateDir = dir,
        clientJwt = clientJwt,
        clientId = clientId,
        instanceId = instanceId,
        identity = identity,
    )
}

/** The state directory must be an existing absolute directory, private to its owner. */
fun checkStateDir(stateDir: String) {
    if (stateDir == "") {
        throw ProviderConfigException("the provider state directory is not set")
    }
    val path = File(stateDir).toPath()
    if (!path.isAbsolute) {
        throw ProviderConfigException("the provider state directory must be an absolute path")
    }
    val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java)
    } catch (e: NoSuchFileException) {
        throw ProviderConfigException("the provider state directory does not exist")
    } catch (e: IOException) {
        throw ProviderConfigException("provider state directory: ${e.message}")
    }
    if (!attributes.isDirectory) {
        throw ProviderConfigException("the provider state directory is not a directory")
    }
    if (posixPermissions && Files.getPosixFilePermissions(path).any { it in groupOrOtherPermissions }) {
        throw ProviderConfigException("the provider state directory must be private to its owner (mode 700)")
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
            throw ProviderConfigException("create the provider state directory: ${e.message}")
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
 * sdk and the server verify the token itself.
 */
fun parseClientJwtClientId(clientJwt: String): String {
    val notJwt = ProviderConfigException("$clientJwtFileName does not hold a JWT; import the scoped client JWT from your backend")
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
        throw ProviderConfigException("$clientJwtFileName has no client_id claim; import a scoped client JWT, not a network JWT")
    }
    return parseUuid(clientIdClaim)
        ?: throw ProviderConfigException("$clientJwtFileName has an invalid client_id claim")
}

/**
 * Writes client.jwt, creating the state directory: what an app's sign-in flow does with the scoped
 * client JWT that its backend returns. The token must carry a client_id claim. Returns the client
 * id; never print or log the token.
 */
fun importClientJwt(stateDir: File, clientJwt: String): String {
    val trimmedClientJwt = clientJwt.trim()
    val clientId = parseClientJwtClientId(trimmedClientJwt)
    ensureStateDir(stateDir)
    try {
        writePrivateFile(File(stateDir, clientJwtFileName), "$trimmedClientJwt\n".toByteArray())
    } catch (e: IOException) {
        throw ProviderConfigException("write $clientJwtFileName: ${e.message}")
    }
    return clientId
}

/**
 * Reads instance-id, creating it on first run. An installation keeps one instance id for its
 * lifetime.
 */
fun loadOrCreateInstanceId(stateDir: File): String {
    val file = File(stateDir, instanceIdFileName)
    val data = try {
        readPrivateFile(file)
    } catch (e: NoSuchFileException) {
        null
    } catch (e: IOException) {
        throw ProviderConfigException("read $instanceIdFileName from the state directory: ${e.message}")
    }
    if (data != null) {
        return parseUuid(data.decodeToString().trim())
            ?: throw ProviderConfigException("$instanceIdFileName does not hold a UUID")
    }
    val instanceId = UUID.randomUUID().toString()
    try {
        writePrivateFile(file, "$instanceId\n".toByteArray())
    } catch (e: IOException) {
        throw ProviderConfigException("write $instanceIdFileName: ${e.message}")
    }
    return instanceId
}

/**
 * Reads identity.json for clientId. A missing file, or an identity of another client, returns
 * null: the device then creates a new identity, which the app saves.
 */
fun loadProviderIdentity(stateDir: File, clientId: String): ProviderIdentity? {
    val data = try {
        readPrivateFile(File(stateDir, identityFileName))
    } catch (e: NoSuchFileException) {
        return null
    } catch (e: IOException) {
        throw ProviderConfigException("read $identityFileName from the state directory: ${e.message}")
    }
    val identity = parseProviderIdentity(data)
        ?: throw ProviderConfigException("$identityFileName is not a valid provider identity; remove it to create a new one")
    if (identity.clientId != clientId) {
        return null
    }
    return identity
}

/** Writes identity.json. */
fun saveProviderIdentity(stateDir: File, identity: ProviderIdentity) {
    val encoder = Base64.getEncoder()
    val json = buildJsonObject {
        put("version", providerIdentityVersion)
        put("client_id", identity.clientId)
        put("client_key_seed", encoder.encodeToString(identity.clientKeySeed))
        put("provide_tls_certificate_pem", encoder.encodeToString(identity.provideTlsCertificatePem))
        put("provide_tls_private_key_pem", encoder.encodeToString(identity.provideTlsPrivateKeyPem))
        if (identity.extenderKeySeed.isNotEmpty()) {
            put("extender_key_seed", encoder.encodeToString(identity.extenderKeySeed))
        }
    }
    writePrivateFile(File(stateDir, identityFileName), json.toString().toByteArray())
}

/**
 * Parses identity.json; null when it does not parse, has another version, or a client key seed
 * that is not 32 bytes.
 */
private fun parseProviderIdentity(data: ByteArray): ProviderIdentity? {
    val json = try {
        Json.parseToJsonElement(data.decodeToString()) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    // a string field, or null when it is missing or not a string
    val text = { name: String -> (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content }
    // a base64 byte field: empty when missing, null when it is not valid base64
    val bytes = { name: String ->
        try {
            Base64.getDecoder().decode(text(name).orEmpty())
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    val version = (json["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    val clientId = text("client_id")
    val clientKeySeed = bytes("client_key_seed")
    val provideTlsCertificatePem = bytes("provide_tls_certificate_pem")
    val provideTlsPrivateKeyPem = bytes("provide_tls_private_key_pem")
    val extenderKeySeed = bytes("extender_key_seed")
    if (version != providerIdentityVersion || clientId == null || clientKeySeed == null || clientKeySeed.size != 32 ||
        provideTlsCertificatePem == null || provideTlsPrivateKeyPem == null || extenderKeySeed == null
    ) {
        return null
    }
    return ProviderIdentity(
        clientId = clientId,
        clientKeySeed = clientKeySeed,
        provideTlsCertificatePem = provideTlsCertificatePem,
        provideTlsPrivateKeyPem = provideTlsPrivateKeyPem,
        extenderKeySeed = extenderKeySeed,
    )
}
