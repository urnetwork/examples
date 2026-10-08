// The installation state checks of the embed self-test (EMBED_CONTRACT.md, "Self-test"): the JWT
// client_id claim, private permissions, atomic replacement, the instance id created once, a
// symlinked file refused, the token server settings and the configuration errors. They run on the
// JVM in temporary directories with synthetic tokens.
package com.example.urnetwork.embed

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
fun testJwt(payloadJson: String): String =
    "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray()) + ".test"

/** A synthetic client JWT for clientId. */
fun testClientJwt(clientId: String): String = testJwt("""{"client_id":"$clientId","network_id":"22222222-2222-2222-2222-222222222222"}""")

/** Installation state files, token server settings and configuration errors. */
class EmbedStateTest {
    // a private temporary parent for each test's state directories
    private lateinit var tempDir: File

    /** Creates the private temporary parent. */
    @Before
    fun createTempDir() {
        tempDir = Files.createTempDirectory("ur-embed-test-").toFile()
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
        assertEquals("11111111-1111-1111-1111-111111111111", parseClientJwtClientId(testClientJwt("11111111-1111-1111-1111-111111111111")))
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
            assertThrows("invalid client jwt '$invalidJwt' accepted", EmbedConfigException::class.java) {
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

    /** State files are private, replaced atomically, and a symlinked file is refused. */
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
        assertThrows("a group-readable state directory was accepted", EmbedConfigException::class.java) {
            checkStateDir(stateDir.path)
        }
        setMode(stateDir, "rwx------")
        checkStateDir(stateDir.path)
        // a symlinked credential is refused, so the token cannot be redirected to another file
        val target = File(stateDir, "elsewhere.jwt")
        writePrivateFile(target, (testClientJwt("11111111-1111-1111-1111-111111111111") + "\n").toByteArray())
        Files.delete(file.toPath())
        Files.createSymbolicLink(file.toPath(), target.toPath())
        assertThrows("a symlinked client.jwt was accepted", EmbedConfigException::class.java) {
            loadClientJwt(stateDir)
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
            assertThrows(EmbedConfigException::class.java) {
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
        assertThrows(EmbedConfigException::class.java) {
            loadOrCreateInstanceId(stateDir)
        }
    }

    /** The configuration errors: a missing or relative state directory, and no credential at all. */
    @Test
    fun configurationErrors() {
        assertThrows("an unset state directory was accepted", EmbedConfigException::class.java) {
            checkStateDir("")
        }
        assertThrows("a relative state directory was accepted", EmbedConfigException::class.java) {
            checkStateDir("relative/state")
        }
        assertThrows("a state directory that does not exist was accepted", EmbedConfigException::class.java) {
            checkStateDir(File(tempDir, "absent").path)
        }
        // no token server and no client.jwt: the controller refuses to start with a configuration error
        val stateDir = newStateDir()
        assertNull(loadTokenServerConfig(stateDir, allowEmulatorHost = false))
        assertNull(loadClientJwt(stateDir))
        // a network jwt in client.jwt is a configuration error
        writePrivateFile(File(stateDir, clientJwtFileName), (testJwt("""{"network_id":"22222222-2222-2222-2222-222222222222"}""") + "\n").toByteArray())
        assertThrows("a network jwt was accepted", EmbedConfigException::class.java) {
            loadClientJwt(stateDir)
        }
    }

    /** The client.jwt import writes a private token and refuses a token without a client. */
    @Test
    fun importClientJwt() {
        val stateDir = File(tempDir, stateDirName)
        val clientJwt = testClientJwt("11111111-1111-1111-1111-111111111111")
        assertEquals("11111111-1111-1111-1111-111111111111", importClientJwt(stateDir, "  $clientJwt\n"))
        val stored = loadClientJwt(stateDir)
        assertNotNull(stored)
        assertEquals(clientJwt, stored!!.clientJwt)
        assertEquals("11111111-1111-1111-1111-111111111111", stored.clientId)
        assertThrows(EmbedConfigException::class.java) {
            importClientJwt(stateDir, testJwt("""{"network_id":"22222222-2222-2222-2222-222222222222"}"""))
        }
    }

    /** Token server URLs: an HTTPS origin, or loopback HTTP for local testing, nothing else. */
    @Test
    fun tokenServerOrigins() {
        val accepted = listOf(
            "https://tokens.example.com" to "https://tokens.example.com",
            "https://tokens.example.com/" to "https://tokens.example.com",
            "https://Tokens.Example.com:8443" to "https://tokens.example.com:8443",
            "http://127.0.0.1:8790" to "http://127.0.0.1:8790",
            "http://localhost:8790/" to "http://localhost:8790",
            "http://[::1]:8790" to "http://[::1]:8790",
        )
        for ((url, origin) in accepted) {
            assertEquals(url, origin, tokenServerOrigin(url, allowEmulatorHost = false))
        }
        val refused = listOf(
            "",
            "tokens.example.com",
            "http://tokens.example.com",
            "ftp://tokens.example.com",
            "https://user:pass@tokens.example.com",
            "https://tokens.example.com/urnetwork/client-token",
            "https://tokens.example.com/?a=1",
            "https://tokens.example.com/#fragment",
            // the emulator's host loopback only in debuggable builds
            "http://10.0.2.2:8790",
        )
        for (url in refused) {
            assertThrows("token server url '$url' accepted", EmbedConfigException::class.java) {
                tokenServerOrigin(url, allowEmulatorHost = false)
            }
        }
        assertEquals("http://10.0.2.2:8790", tokenServerOrigin("http://10.0.2.2:8790", allowEmulatorHost = true))
    }

    /** token-server.json round trips, private, and is checked on load. */
    @Test
    fun tokenServerConfig() {
        val stateDir = File(tempDir, stateDirName)
        val session = "demo-session-0123456789abcdef0123456789abcdef"
        assertEquals("http://127.0.0.1:8790", importTokenServerConfig(stateDir, "http://127.0.0.1:8790/", session, allowEmulatorHost = false))
        val config = loadTokenServerConfig(stateDir, allowEmulatorHost = false)
        assertNotNull(config)
        assertEquals("http://127.0.0.1:8790", config!!.url)
        assertEquals(session, config.session)
        if (posixHost) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(stateDir, tokenServerFileName).toPath())))
        }
        // a session that cannot be a bearer token is refused
        for (badSession in listOf("", "has space", "tab\tseparated", "x".repeat(2000))) {
            assertThrows("session '$badSession' accepted", EmbedConfigException::class.java) {
                importTokenServerConfig(stateDir, "https://tokens.example.com", badSession, allowEmulatorHost = false)
            }
        }
        // a file that does not parse, or has an invalid url, is a configuration error
        for (content in listOf("not json", """{"url": "http://tokens.example.com", "session": "$session"}""", """{"url": "https://tokens.example.com"}""")) {
            writePrivateFile(File(stateDir, tokenServerFileName), content.toByteArray())
            assertThrows("token-server.json '$content' accepted", EmbedConfigException::class.java) {
                loadTokenServerConfig(stateDir, allowEmulatorHost = false)
            }
        }
    }
}
