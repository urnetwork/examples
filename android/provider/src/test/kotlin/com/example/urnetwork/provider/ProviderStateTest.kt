// The installation state checks of the provider self-test (PROVIDER_CONTRACT.md, "Self-test"): the
// JWT client_id claim, private permissions, atomic replacement, the instance id, the identity and
// the configuration errors. They run on the JVM in temporary directories with synthetic tokens.
package com.example.urnetwork.provider

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

// whether the host file system has POSIX permissions (not on Windows)
private val posixHost = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

/** A synthetic, unsigned JWT with the given payload json. */
private fun testJwt(payloadJson: String): String =
    "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray()) + ".test"

/** Installation state files and configuration errors. */
class ProviderStateTest {
    // a private temporary parent for each test's state directories
    private lateinit var tempDir: File

    /** Creates the private temporary parent. */
    @Before
    fun createTempDir() {
        tempDir = Files.createTempDirectory("ur-provider-test-").toFile()
        setMode(tempDir, "rwx------")
    }

    /** Removes the temporary parent. */
    @After
    fun removeTempDir() {
        tempDir.deleteRecursively()
    }

    /** A new private state directory. */
    private fun newStateDir(): File {
        val stateDir = File(tempDir, "state-${System.nanoTime()}")
        assertTrue(stateDir.mkdir())
        setMode(stateDir, "rwx------")
        return stateDir
    }

    /** Sets a POSIX mode, on hosts that have them. */
    private fun setMode(file: File, mode: String) {
        if (posixHost) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))
        }
    }

    /** Only a JWT with a valid client_id claim is a client credential. */
    @Test
    fun clientJwtClaims() {
        val clientId = parseClientJwtClientId(testJwt("""{"client_id":"11111111-1111-1111-1111-111111111111","network_id":"22222222-2222-2222-2222-222222222222"}"""))
        assertEquals("11111111-1111-1111-1111-111111111111", clientId)
        // the claim is normalized to the sdk's lowercase form
        assertEquals("abcdefab-1111-1111-1111-111111111111", parseClientJwtClientId(testJwt("""{"client_id":"ABCDEFAB-1111-1111-1111-111111111111"}""")))
        val invalidJwts = listOf(
            "",
            "not-a-jwt",
            // a network jwt has no client_id claim
            testJwt("""{"network_id":"22222222-2222-2222-2222-222222222222"}"""),
            testJwt("""{"client_id":"not-a-uuid"}"""),
            testJwt("""{"client_id":11111111}"""),
            "e30.%%%.test",
            "e30..test",
        )
        for (invalidJwt in invalidJwts) {
            assertThrows("invalid client jwt '$invalidJwt' accepted", ProviderConfigException::class.java) {
                parseClientJwtClientId(invalidJwt)
            }
        }
    }

    /** UUIDs take the sdk's two forms and come out in its canonical form. */
    @Test
    fun uuids() {
        assertEquals("11111111-2222-3333-4444-555555555555", parseUuid("11111111-2222-3333-4444-555555555555"))
        assertEquals("11111111-2222-3333-4444-555555555555", parseUuid("11111111222233334444555555555555"))
        assertEquals("aaaaaaaa-2222-3333-4444-555555555555", parseUuid("AAAAAAAA-2222-3333-4444-555555555555"))
        for (invalid in listOf("", "1-1-1-1-1", "11111111-2222-3333-4444-55555555555g", "11111111-2222-3333-4444_555555555555")) {
            assertNull("invalid uuid '$invalid' accepted", parseUuid(invalid))
        }
    }

    /** State files are private and replaced atomically. */
    @Test
    fun privateFiles() {
        val stateDir = newStateDir()
        val file = File(stateDir, clientJwtFileName)
        writePrivateFile(file, "first\n".toByteArray())
        writePrivateFile(file, "second\n".toByteArray())
        assertArrayEquals("second\n".toByteArray(), readPrivateFile(file))
        // the replacement leaves no temporary file behind
        assertEquals(listOf(clientJwtFileName), stateDir.list()!!.toList())
        if (!posixHost) {
            return
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        // a file or directory that others can read is refused
        setMode(file, "rw-r--r--")
        assertThrows("a group-readable credential file was accepted", IOException::class.java) {
            readPrivateFile(file)
        }
        setMode(file, "rw-------")
        setMode(stateDir, "rwxr-xr-x")
        assertThrows("a group-readable state directory was accepted", ProviderConfigException::class.java) {
            checkStateDir(stateDir.path)
        }
        setMode(stateDir, "rwx------")
        checkStateDir(stateDir.path)
        // a symlinked credential is refused
        val target = File(stateDir, "elsewhere.jwt")
        writePrivateFile(target, "redirected\n".toByteArray())
        val link = File(stateDir, "link.jwt")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertThrows("a symlinked credential file was accepted", IOException::class.java) {
            readPrivateFile(link)
        }
    }

    /** The app creates the state directory and its logs directory private to their owner. */
    @Test
    fun stateDirCreation() {
        val stateDir = File(tempDir, stateDirName)
        ensureStateDir(stateDir)
        assertTrue(stateDir.isDirectory)
        // an existing directory is kept
        ensureStateDir(stateDir)
        val logDir = ensureLogDir(stateDir)
        assertTrue(logDir.isDirectory)
        assertEquals(File(stateDir, logDirName), logDir)
        if (posixHost) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(stateDir.toPath())))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(logDir.toPath())))
            // an existing directory that others can access is refused, not repaired
            setMode(stateDir, "rwxrwx--x")
            assertThrows(ProviderConfigException::class.java) {
                ensureStateDir(stateDir)
            }
        }
    }

    /** The instance id is created once and reused. */
    @Test
    fun instanceId() {
        val stateDir = newStateDir()
        val instanceId = loadOrCreateInstanceId(stateDir)
        assertEquals(instanceId, parseUuid(instanceId))
        assertEquals(instanceId, loadOrCreateInstanceId(stateDir))
        writePrivateFile(File(stateDir, instanceIdFileName), "not-a-uuid\n".toByteArray())
        assertThrows(ProviderConfigException::class.java) {
            loadOrCreateInstanceId(stateDir)
        }
    }

    /**
     * The identity round trips, belongs to its client, and is missing on first run, so the device
     * gets no key material and makes a new identity.
     */
    @Test
    fun identity() {
        val stateDir = newStateDir()
        val clientId = "11111111-1111-1111-1111-111111111111"
        // first run: no identity, so no key material
        assertNull(loadProviderIdentity(stateDir, clientId))
        val identity = ProviderIdentity(
            clientId = clientId,
            clientKeySeed = ByteArray(32) { 1 },
            provideTlsCertificatePem = "synthetic certificate".toByteArray(),
            provideTlsPrivateKeyPem = "synthetic private key".toByteArray(),
            extenderKeySeed = ByteArray(32) { 2 },
        )
        saveProviderIdentity(stateDir, identity)
        val loaded = loadProviderIdentity(stateDir, clientId)
        assertNotNull(loaded)
        assertArrayEquals(identity.clientKeySeed, loaded!!.clientKeySeed)
        assertArrayEquals(identity.provideTlsCertificatePem, loaded.provideTlsCertificatePem)
        assertArrayEquals(identity.provideTlsPrivateKeyPem, loaded.provideTlsPrivateKeyPem)
        assertArrayEquals(identity.extenderKeySeed, loaded.extenderKeySeed)
        // another client's identity is ignored: that client gets a new identity
        assertNull(loadProviderIdentity(stateDir, "22222222-2222-2222-2222-222222222222"))
        // an identity without an extender seed omits it
        saveProviderIdentity(
            stateDir,
            ProviderIdentity(
                clientId = clientId,
                clientKeySeed = ByteArray(32) { 3 },
                provideTlsCertificatePem = "synthetic certificate".toByteArray(),
                provideTlsPrivateKeyPem = "synthetic private key".toByteArray(),
                extenderKeySeed = ByteArray(0),
            ),
        )
        assertFalse(readPrivateFile(File(stateDir, identityFileName)).decodeToString().contains("extender_key_seed"))
        assertEquals(0, loadProviderIdentity(stateDir, clientId)!!.extenderKeySeed.size)
        // an invalid identity is refused
        val invalidIdentities = listOf(
            """{"version":1}""",
            """{"version":2,"client_id":"$clientId","client_key_seed":"${Base64.getEncoder().encodeToString(ByteArray(32))}"}""",
            """{"version":1,"client_id":"$clientId","client_key_seed":"${Base64.getEncoder().encodeToString(ByteArray(31))}"}""",
            """{"version":1,"client_id":"$clientId","client_key_seed":"not base64!"}""",
            "not json",
        )
        for (invalidIdentity in invalidIdentities) {
            writePrivateFile(File(stateDir, identityFileName), invalidIdentity.toByteArray())
            assertThrows("invalid identity '$invalidIdentity' accepted", ProviderConfigException::class.java) {
                loadProviderIdentity(stateDir, clientId)
            }
        }
    }

    /** A missing or incomplete installation state is refused; a first run loads. */
    @Test
    fun providerConfig() {
        assertThrows("a missing state directory was accepted", ProviderConfigException::class.java) {
            loadProviderConfig("")
        }
        assertThrows("a relative state directory was accepted", ProviderConfigException::class.java) {
            loadProviderConfig("relative/state")
        }
        assertThrows("a state directory that does not exist was accepted", ProviderConfigException::class.java) {
            loadProviderConfig(File(tempDir, "absent").path)
        }
        val stateDir = newStateDir()
        assertThrows("a state directory without client.jwt was accepted", ProviderConfigException::class.java) {
            loadProviderConfig(stateDir.path)
        }
        // a network jwt has no client_id claim
        writePrivateFile(File(stateDir, clientJwtFileName), (testJwt("""{"network_id":"22222222-2222-2222-2222-222222222222"}""") + "\n").toByteArray())
        assertThrows("a network jwt was accepted", ProviderConfigException::class.java) {
            loadProviderConfig(stateDir.path)
        }
        val clientJwt = testJwt("""{"client_id":"11111111-1111-1111-1111-111111111111"}""")
        writePrivateFile(File(stateDir, clientJwtFileName), "$clientJwt\n".toByteArray())
        val config = loadProviderConfig(stateDir.path)
        assertEquals(clientJwt, config.clientJwt)
        assertEquals("11111111-1111-1111-1111-111111111111", config.clientId)
        assertEquals(config.instanceId, parseUuid(config.instanceId))
        assertNull(config.identity)
        // the next start keeps the instance id
        assertEquals(config.instanceId, loadProviderConfig(stateDir.path).instanceId)
    }

    /** The sign-in import writes a private client.jwt and refuses a token without a client. */
    @Test
    fun importClientJwt() {
        val stateDir = File(tempDir, stateDirName)
        val clientJwt = testJwt("""{"client_id":"11111111-1111-1111-1111-111111111111"}""")
        assertEquals("11111111-1111-1111-1111-111111111111", importClientJwt(stateDir, " $clientJwt\n"))
        assertArrayEquals("$clientJwt\n".toByteArray(), readPrivateFile(File(stateDir, clientJwtFileName)))
        assertEquals("11111111-1111-1111-1111-111111111111", loadProviderConfig(stateDir.path).clientId)
        // a network jwt is refused and the stored token is kept
        assertThrows(ProviderConfigException::class.java) {
            importClientJwt(stateDir, testJwt("""{"network_id":"22222222-2222-2222-2222-222222222222"}"""))
        }
        assertArrayEquals("$clientJwt\n".toByteArray(), readPrivateFile(File(stateDir, clientJwtFileName)))
    }
}
