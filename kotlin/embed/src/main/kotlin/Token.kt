// Obtaining the installation's client JWT (EMBED_CONTRACT.md, "Obtaining the
// client JWT"). With a token server configured, fetchClientJwt posts this
// installation's instance id to your backend's token endpoint with the demo
// session and saves the scoped client JWT it answers as client.jwt; that one
// function is the place to put your own sign-in. Without one, the app uses the
// client.jwt that your backend tool's provision command (or you) wrote.
//
// The app never holds the root credential: the backend provisions the client
// and only the scoped client JWT reaches the installation.
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val tokenPath = "/urnetwork/client-token"

// the largest answer the app reads
const val responseByteLimit = 1 shl 20

// the longest error message from the token server that the app shows
private const val messageLimit = 300

/**
 * The installation's credential: the scoped client JWT, its client id and, from the token server, a
 * first cap reading (or null). The JWT is a bearer secret, so toString leaves it out.
 */
class ClientCredential(val clientJwt: String, val clientId: String, val firstCap: DataCap?) {
    /** The client id only. */
    override fun toString() = "ClientCredential(clientId=$clientId)"
}

/**
 * The token server did not deliver a client JWT. exitCode is 78 when restarting does not help (the
 * session was refused, or a limit was hit; GUI apps show "signed out") and 1 for any other failure
 * (GUI apps show "stopped").
 */
class TokenFetchException(val exitCode: Int, message: String) : Exception(message) {
    /** The status that GUI and Android apps show for this failure. */
    val guiStatus: String get() = if (exitCode == exitConfig) statusSignedOut else statusStopped
}

/** The status and body of one HTTP answer; body is null when it is longer than the limit. */
data class HttpAnswer(val status: Int, val body: String?)

/** One HTTP exchange with a bounded answer; the self-test passes a stand-in. */
fun interface HttpTransport {
    /** Sends method to uri with the bearer token and, when jsonBody is not null, a JSON body. */
    fun send(method: String, uri: URI, bearer: String, jsonBody: String?): HttpAnswer
}

/** HTTPS through the JDK client: 15 seconds, no redirects, a bounded answer. */
fun jdkTransport(): HttpTransport {
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    return HttpTransport { method, uri, bearer, jsonBody ->
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $bearer")
        if (jsonBody != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            val data = input.readNBytes(responseByteLimit + 1)
            HttpAnswer(response.statusCode(), if (responseByteLimit < data.size) null else String(data, Charsets.UTF_8))
        }
    }
}

/** Whether text can be a demo session token: printable ASCII without whitespace. */
fun isSessionToken(text: String?): Boolean = !text.isNullOrEmpty() && text.all { it in '!'..'~' }

/**
 * The client JWT from the token server when one is configured, otherwise the client.jwt in the
 * state directory. A missing or invalid JWT, or a network JWT, is a configuration error.
 */
fun obtainClientJwt(settings: EmbedSettings, transport: HttpTransport?): ClientCredential {
    val tokenServer = settings.tokenServer
    if (tokenServer != null) {
        return fetchClientJwt(tokenServer, settings.demoSession!!, settings.instanceId, settings.stateDir, transport ?: jdkTransport())
    }
    val clientJwt = readClientJwt(settings.stateDir) ?: throw ConfigException(
        "no token server is configured and the state directory has no client.jwt: set URNETWORK_TOKEN_SERVER_URL " +
            "and URNETWORK_DEMO_SESSION, or write client.jwt with your backend tool's provision command",
    )
    return ClientCredential(clientJwt, parseClientJwtClientId(clientJwt), null)
}

/**
 * fetchClientJwt obtains this installation's client JWT from your backend: POST
 * /urnetwork/client-token with the demo session as the bearer token and {"installation_id":
 * instanceId}. It checks that the JWT's client_id claim equals the answer's client_id and saves the
 * JWT as client.jwt. A start fetches again, so every start reissues the client. Replace this
 * function with your own sign-in.
 */
fun fetchClientJwt(tokenServer: URI, demoSession: String, instanceId: String, stateDir: Path, transport: HttpTransport): ClientCredential {
    val request = buildJsonObject { put("installation_id", instanceId) }.toString()
    val answer = try {
        transport.send("POST", tokenServer.resolve(tokenPath), demoSession, request)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw TokenFetchException(exitFailure, "the token fetch was interrupted")
    } catch (e: IOException) {
        throw TokenFetchException(exitFailure, "could not reach the token server: ${printable(e.message ?: e.javaClass.simpleName)}")
    } catch (e: IllegalArgumentException) {
        throw TokenFetchException(exitFailure, "could not reach the token server: ${printable(e.message ?: e.javaClass.simpleName)}")
    }
    return tokenAnswer(answer.status, answer.body, stateDir)
}

/**
 * Interprets the token server's answer: 200 saves and returns the credential; 401 and 409 are
 * configuration errors with the server's message; anything else is a failure.
 */
fun tokenAnswer(status: Int, body: String?, stateDir: Path): ClientCredential {
    if (status == 200) {
        val credential = parseToken(body)
        saveClientJwt(stateDir, credential.clientJwt)
        return credential
    }
    val message = errorMessage(body)
    if (status == 401 || status == 409) {
        throw TokenFetchException(exitConfig, "the token server refused this installation: ${message ?: defaultMessage(status)}")
    }
    throw TokenFetchException(exitFailure, "the token server answered HTTP $status" + (message?.let { ": $it" } ?: ""))
}

/** The credential of a 200 answer; an invalid answer is a failure. */
private fun parseToken(body: String?): ClientCredential {
    val invalid = "the token server answered an invalid token"
    val members = try {
        parseNullableJsonObject(body ?: "") ?: throw TokenFetchException(exitFailure, invalid)
    } catch (e: IllegalArgumentException) {
        throw TokenFetchException(exitFailure, invalid)
    }
    val (clientIdText, clientJwtText) = try {
        members.stringMember("client_id") to members.stringMember("by_client_jwt")
    } catch (e: IllegalArgumentException) {
        throw TokenFetchException(exitFailure, invalid)
    }
    val clientId = parseId(clientIdText)
    if (clientId == null || clientJwtText == null) {
        throw TokenFetchException(exitFailure, invalid)
    }
    val clientJwt = clientJwtText.trim()
    val claim = try {
        parseClientJwtClientId(clientJwt)
    } catch (e: ConfigException) {
        throw TokenFetchException(exitFailure, "the token server answered a JWT without a valid client_id claim")
    }
    if (claim != clientId) {
        throw TokenFetchException(exitFailure, "the token server's client_id does not match its client JWT")
    }
    val firstCap = try {
        DataCap.fromObject(members.objectMember("data_cap"))
    } catch (e: IllegalArgumentException) {
        null
    }
    return ClientCredential(clientJwt, clientId, firstCap)
}

/** The message of an error answer {"error": {"code": ..., "message": ...}}, made safe to print; null when there is none. */
private fun errorMessage(body: String?): String? =
    try {
        parseNullableJsonObject(body)?.objectMember("error")?.stringMember("message")
            ?.takeUnless { it.isBlank() }?.let(::printable)
    } catch (e: IllegalArgumentException) {
        null
    }

/** The reason for a 401 or 409 answer without a message. */
private fun defaultMessage(status: Int) =
    if (status == 401) "the demo session is not valid" else "no client is available for this installation"

/** Text safe for one line: no control characters, bounded. */
private fun printable(text: String): String =
    text.take(messageLimit).map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
