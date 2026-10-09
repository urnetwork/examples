// The token fetch checks of the embed self-test (EMBED_CONTRACT.md, "Self-test"), against a
// stand-in token server on the loopback interface: the request (the bearer session and the
// installation_id equal to the instance id), saving client.jwt atomically, a client_id that does not
// match the claim refused, and the answers mapped to signed out and stopped. The stand-in is a plain
// ServerSocket, so the test needs no network and no extra library.
package com.example.urnetwork.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

private val posixHost = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

private const val clientA = "11111111-1111-1111-1111-111111111111"
private const val clientB = "33333333-3333-3333-3333-333333333333"
private const val instanceId = "22222222-2222-2222-2222-222222222222"
private const val demoSession = "demo-session-0123456789abcdef0123456789abcdef"

/** One request the stand-in received. */
internal class StandInRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/** One answer the stand-in sends. */
internal class StandInAnswer(val status: Int, val body: String)

/**
 * A stand-in token server: one HTTP/1.1 request per connection on the loopback interface, each
 * answered by answer and recorded in requests.
 */
internal class StandInServer(private val answer: (StandInRequest) -> StandInAnswer) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    /** The origin to configure as the token server URL. */
    val origin = "http://127.0.0.1:${serverSocket.localPort}"

    /** The requests received so far. */
    val requests = CopyOnWriteArrayList<StandInRequest>()

    init {
        val thread = Thread {
            while (true) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    return@Thread
                }
                socket.use { serve(it) }
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    /** Reads one request and writes its answer. */
    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(" ")
        val headers = lines.drop(1).filter { it.contains(':') }.associate {
            it.substringBefore(':').trim().lowercase(Locale.ROOT) to it.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) {
                break
            }
            read += n
        }
        val request = StandInRequest(
            method = requestLine.getOrElse(0) { "" },
            path = requestLine.getOrElse(1) { "" },
            headers = headers,
            body = body.decodeToString(0, read),
        )
        requests += request
        val response = answer(request)
        val responseBody = response.body.toByteArray()
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.1 ${response.status} Answer\r\n" +
                "Content-Type: application/json\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: ${responseBody.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        output.write(responseBody)
        output.flush()
    }

    /** The request line and headers, up to the blank line; null when the connection ends first. */
    private fun readHead(input: InputStream): String? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val terminator = "\r\n\r\n".toByteArray()
        while (matched < terminator.size) {
            val b = input.read()
            if (b < 0) {
                return null
            }
            head.write(b)
            matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
        }
        return head.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
    }

    /** Stops accepting connections. */
    override fun close() {
        serverSocket.close()
    }
}

/** A token server success answer for clientId with the given client JWT and cap object JSON. */
private fun successBody(clientId: String, clientJwt: String, dataCapJson: String = "null"): String =
    """{"client_id": "$clientId", "by_client_jwt": "$clientJwt", "data_cap": $dataCapJson}"""

/** A token server error answer. */
private fun errorBody(code: String, message: String): String =
    """{"error": {"code": "$code", "message": "$message"}}"""

/** fetchClientJwt against the stand-in token server. */
class TokenFetchTest {
    private lateinit var stateDir: File

    /** A private state directory. */
    @Before
    fun createStateDir() {
        stateDir = Files.createTempDirectory("ur-embed-fetch-").toFile()
        if (posixHost) {
            Files.setPosixFilePermissions(stateDir.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }

    /** Removes the state directory. */
    @After
    fun removeStateDir() {
        stateDir.deleteRecursively()
    }

    /** Fetches with the stand-in as the token server. */
    private fun fetch(server: StandInServer): FetchedClientToken =
        fetchClientJwt(stateDir, TokenServerConfig(url = server.origin, session = demoSession), instanceId)

    /**
     * A success posts the instance id with the bearer session, saves client.jwt atomically and
     * privately, and returns the client and its cap object.
     */
    @Test
    fun success() {
        val clientJwt = testClientJwt(clientA)
        val capJson = """{"client_id": "$clientA", "monthly_byte_limit": 5000000000, "monthly_used_byte_count": 1234567890, "monthly_period_end": "2026-11-01T00:00:00Z", "total_byte_limit": null, "total_used_byte_count": 0, "capped": false, "capped_reason": ""}"""
        StandInServer { StandInAnswer(200, successBody(clientA, clientJwt, capJson)) }.use { server ->
            val token = fetch(server)
            assertEquals(clientA, token.clientId)
            assertEquals(clientJwt, token.clientJwt)
            assertNotNull(token.dataCap)
            assertEquals(5000000000L, token.dataCap!!.monthlyByteLimit)
            assertEquals("1.2 GB of 5.0 GB", dataFieldText(DataCapReading.Read(token.dataCap!!), monthly = true))

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals(clientTokenPath, request.path)
            assertEquals("Bearer $demoSession", request.headers["authorization"])
            assertTrue(request.headers["content-type"].orEmpty().startsWith("application/json"))
            val body = Json.parseToJsonElement(request.body) as JsonObject
            assertEquals(instanceId, (body["installation_id"] as JsonPrimitive).content)

            // client.jwt is saved, private, with no temporary file left behind
            assertEquals("$clientJwt\n", File(stateDir, clientJwtFileName).readText())
            assertEquals(listOf(clientJwtFileName), stateDir.list()!!.toList())
            if (posixHost) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(stateDir, clientJwtFileName).toPath())))
            }
            assertEquals(clientA, loadClientJwt(stateDir)!!.clientId)
        }
    }

    /** A token server that could not read the caps answers data_cap null; the token still comes. */
    @Test
    fun nullDataCap() {
        StandInServer { StandInAnswer(200, successBody(clientA, testClientJwt(clientA))) }.use { server ->
            val token = fetch(server)
            assertEquals(clientA, token.clientId)
            assertNull(token.dataCap)
        }
    }

    /** A token for another client, or a network JWT, is refused and nothing is saved. */
    @Test
    fun mismatchedTokenRefused() {
        val answers = listOf(
            // the claim names another client
            successBody(clientA, testClientJwt(clientB)),
            // a network jwt has no client_id claim
            successBody(clientA, testJwt("""{"network_id":"22222222-2222-2222-2222-222222222222"}""")),
            // not a jwt at all
            successBody(clientA, "not-a-jwt"),
            // no client_id
            """{"by_client_jwt": "${testClientJwt(clientA)}"}""",
        )
        for (answer in answers) {
            StandInServer { StandInAnswer(200, answer) }.use { server ->
                val e = assertThrows(answer, TokenFetchException::class.java) { fetch(server) }
                assertEquals(answer, TokenFetchFailure.Stopped, e.failure)
            }
            assertTrue("client.jwt saved for '$answer'", !File(stateDir, clientJwtFileName).exists())
        }
    }

    /** 401 and 409 sign the installation out with the error's message. */
    @Test
    fun signedOutAnswers() {
        val answers = listOf(
            StandInAnswer(401, errorBody("unauthorized", "unknown demo session")),
            StandInAnswer(409, errorBody("installation_limit", "the user already has 5 installations")),
            StandInAnswer(409, errorBody("client_limit", "your network is at its client limit; see https://ur.io/services")),
        )
        for (answer in answers) {
            StandInServer { answer }.use { server ->
                val e = assertThrows(TokenFetchException::class.java) { fetch(server) }
                assertEquals(TokenFetchFailure.SignedOut, e.failure)
                val message = ((Json.parseToJsonElement(answer.body) as JsonObject)["error"] as JsonObject)["message"] as JsonPrimitive
                assertEquals(message.content, e.message)
            }
        }
    }

    /** A 5xx, another error status or an invalid answer stops with a message. */
    @Test
    fun stoppedAnswers() {
        val answers = listOf(
            StandInAnswer(502, errorBody("upstream", "the URnetwork API failed")),
            StandInAnswer(503, errorBody("busy", "retry")),
            StandInAnswer(500, "not json"),
            StandInAnswer(400, errorBody("invalid_request", "installation_id must be a lowercase UUID")),
            StandInAnswer(404, "404 page not found"),
            StandInAnswer(405, errorBody("method_not_allowed", "use POST")),
            StandInAnswer(500, errorBody("internal", "internal error")),
            StandInAnswer(200, "not json"),
        )
        for (answer in answers) {
            StandInServer { answer }.use { server ->
                val e = assertThrows("${answer.status} ${answer.body}", TokenFetchException::class.java) { fetch(server) }
                assertEquals(TokenFetchFailure.Stopped, e.failure)
                assertTrue(e.message.orEmpty() != "")
            }
        }
    }

    /** An unreachable token server stops. */
    @Test
    fun unreachable() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val e = assertThrows(TokenFetchException::class.java) {
            fetchClientJwt(stateDir, TokenServerConfig(url = "http://127.0.0.1:$closedPort", session = demoSession), instanceId)
        }
        assertEquals(TokenFetchFailure.Stopped, e.failure)
        assertTrue(e.message.orEmpty().startsWith("the token server is unreachable"))
    }
}
