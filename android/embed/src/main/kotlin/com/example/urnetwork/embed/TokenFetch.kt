// Obtaining the installation's client JWT from the token server (EMBED_CONTRACT.md, "Obtaining the
// client JWT" and "The token server"): the shape of your service's own sign-in endpoint. The app
// posts its instance id with the demo session as the bearer token; the token server provisions or
// reissues the installation's client with its root credential, which never leaves the backend, and
// answers with the scoped client JWT. fetchClientJwt is the one function to replace with your own
// sign-in. It uses HttpURLConnection, which works on Android and on the JVM, so the unit tests run
// it against a stand-in server.
package com.example.urnetwork.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// the token server's route
const val clientTokenPath = "/urnetwork/client-token"

private const val connectTimeoutMillis = 15 * 1000
private const val readTimeoutMillis = 60 * 1000

/**
 * How a failed token fetch leaves the app: SignedOut for a 401 (an unknown session) or a 409 (the
 * installation limit or the client limit); Stopped for an unreachable server, a 5xx or an invalid
 * answer. The console examples exit with 78 and 1 for these.
 */
enum class TokenFetchFailure {
    SignedOut,
    Stopped,
}

/** A failed token fetch, with the message to show. */
class TokenFetchException(val failure: TokenFetchFailure, message: String) : Exception(message)

/**
 * The token server's answer, after client.jwt was saved: the client id and, when the token server
 * read it, the client's cap object as the first cap reading. Never print or log the token.
 */
class FetchedClientToken(val clientId: String, val clientJwt: String, val dataCap: DataCap?)

/** One HTTP exchange: the status code and the answer text. */
private class Answer(val status: Int, val text: String)

/**
 * Obtains this installation's client JWT: posts {"installation_id": instanceId} to the token server
 * with the demo session as the bearer token, checks that by_client_jwt carries a client_id claim
 * equal to client_id, and saves it as client.jwt. A start fetches every time, so a start reissues
 * the installation's client. Replace this function with your app's own sign-in.
 */
fun fetchClientJwt(stateDir: File, tokenServer: TokenServerConfig, instanceId: String): FetchedClientToken {
    val body = buildJsonObject { put("installation_id", instanceId) }.toString()
    val answer = post(tokenServer.url + clientTokenPath, tokenServer.session, body)
    val json = try {
        Json.parseToJsonElement(answer.text) as? JsonObject
    } catch (e: Exception) {
        null
    }
    if (answer.status != HttpURLConnection.HTTP_OK) {
        // the token server's error shape is {"error": {"code": "...", "message": "..."}}
        val error = json?.get("error") as? JsonObject
        val message = (error?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: "the token server answered HTTP ${answer.status}"
        val failure = when (answer.status) {
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_CONFLICT -> TokenFetchFailure.SignedOut
            else -> TokenFetchFailure.Stopped
        }
        throw TokenFetchException(failure, message)
    }
    val invalid = { reason: String -> TokenFetchException(TokenFetchFailure.Stopped, "the token server answered $reason") }
    if (json == null) {
        throw invalid("something that is not JSON")
    }
    val text = { name: String -> (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content }
    val clientId = text("client_id")?.let { parseUuid(it) } ?: throw invalid("no valid client_id")
    val clientJwt = text("by_client_jwt")?.trim()?.takeIf { it != "" } ?: throw invalid("no by_client_jwt")
    val claimClientId = try {
        parseClientJwtClientId(clientJwt)
    } catch (e: EmbedConfigException) {
        throw invalid("a token that is not a scoped client JWT: ${e.message}")
    }
    if (claimClientId != clientId) {
        throw invalid("a client JWT for another client")
    }
    try {
        saveClientJwt(stateDir, clientJwt)
    } catch (e: IOException) {
        throw TokenFetchException(TokenFetchFailure.Stopped, "save $clientJwtFileName: ${e.message}")
    }
    // data_cap is null when the token server could not read the caps; it never blocks the token
    return FetchedClientToken(clientId = clientId, clientJwt = clientJwt, dataCap = parseDataCap(json["data_cap"]))
}

/** Posts a JSON body with a bearer token; an unreachable server is a Stopped failure. */
private fun post(url: String, bearer: String, body: String): Answer {
    val bodyBytes = body.toByteArray()
    val connection = try {
        URL(url).openConnection() as HttpURLConnection
    } catch (e: IOException) {
        throw TokenFetchException(TokenFetchFailure.Stopped, "the token server is unreachable: ${e.message}")
    }
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $bearer")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        // No streaming mode: the body is small, and in streaming mode a 401 makes the JDK's
        // HttpURLConnection drop the answer (it cannot retry the authentication), so the token
        // server's error message would be lost. Buffered, the 401 body reads as any error.
        connection.outputStream.use { it.write(bodyBytes) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { readBounded(it, answerByteLimit) }?.decodeToString().orEmpty()
        return Answer(status = status, text = text)
    } catch (e: IOException) {
        throw TokenFetchException(TokenFetchFailure.Stopped, "the token server is unreachable: ${e.message}")
    } finally {
        connection.disconnect()
    }
}
