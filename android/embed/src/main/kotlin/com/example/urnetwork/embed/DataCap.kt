// The installation's own data caps (EMBED_CONTRACT.md, "The cap object"): the app reads them with
// its client JWT (GET /network/client-data-cap) at start, every 5 minutes and soon after the device
// reports a contract status change. Your backend sets the caps with its root credential; the app
// never does. Parsing and the reading rules are plain Kotlin, so the unit tests check them on the
// JVM; the read itself uses HttpURLConnection, which works on Android and on the JVM.
package com.example.urnetwork.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// the URnetwork API the app reads its caps from
const val defaultApiUrl = "https://api.bringyour.com"
const val clientDataCapPath = "/network/client-data-cap"

const val cappedReasonMonthly = "monthly"
const val cappedReasonTotal = "total"

// The server refuses the cap read with this message while the team has not enabled Embed for the
// network (EMBED_CONTRACT.md, "Embed enablement"). The refusal clears the last reading.
const val embedNotEnabledMessage = "Embed isn't enabled for this network."

private const val connectTimeoutMillis = 15 * 1000
private const val readTimeoutMillis = 30 * 1000

// the largest answer the app reads from the token server or the API
const val answerByteLimit = 64 * 1024

/**
 * One cap object: each limit is a byte count or null for no cap. A server need not report usage
 * for an uncapped client, so a used count only means something next to its limit.
 */
data class DataCap(
    val clientId: String,
    val monthlyByteLimit: Long?,
    val monthlyUsedByteCount: Long,
    // RFC 3339; when the monthly usage resets
    val monthlyPeriodEnd: String?,
    val totalByteLimit: Long?,
    val totalUsedByteCount: Long,
    val capped: Boolean,
    // cappedReasonMonthly, cappedReasonTotal, "" when not capped, or a reason this app does not know
    val cappedReason: String,
)

/** The latest cap reading, as the data fields and the status rules read it. */
sealed interface DataCapReading {
    /** Before the first cap read finishes. */
    data object Checking : DataCapReading

    /** The first cap read failed, or the Embed-not-enabled refusal cleared the reading. */
    data object Unavailable : DataCapReading

    /** A cap object, kept through later failed reads. */
    data class Read(val cap: DataCap) : DataCapReading
}

/** The outcome of one cap read. */
sealed interface DataCapReadResult {
    /** A cap object. */
    data class Read(val cap: DataCap) : DataCapReadResult

    /** The Embed-not-enabled refusal, which clears the last reading. */
    data object EmbedNotEnabled : DataCapReadResult

    /** Any other failure, which keeps the last reading. */
    data object Failed : DataCapReadResult
}

/**
 * The latest cap reading with the contract's failure rule: the first failure reads as unavailable,
 * a later failure keeps the last value, and the Embed-not-enabled refusal clears it. Safe for
 * concurrent use: the worker writes it and the activity reads it.
 */
class DataCapReadings {
    @Volatile
    var current: DataCapReading = DataCapReading.Checking
        private set

    /** Starts over at checking, for a new run. */
    fun reset() {
        current = DataCapReading.Checking
    }

    /** Records a cap object. */
    fun succeeded(cap: DataCap) {
        current = DataCapReading.Read(cap)
    }

    /** Records a failed read: unavailable only while nothing was read yet. */
    fun failed() {
        if (current == DataCapReading.Checking) {
            current = DataCapReading.Unavailable
        }
    }

    /**
     * Records the Embed-not-enabled refusal: it clears the last reading, so both data fields read
     * unavailable and the status rules see no cap reading.
     */
    fun notEnabled() {
        current = DataCapReading.Unavailable
    }

    /** Records one cap read's outcome. */
    fun record(result: DataCapReadResult) {
        when (result) {
            is DataCapReadResult.Read -> succeeded(result.cap)
            DataCapReadResult.EmbedNotEnabled -> notEnabled()
            DataCapReadResult.Failed -> failed()
        }
    }
}

/** A failed cap read; a server without the cap routes answers 404, which is one. */
class DataCapReadException(message: String) : Exception(message)

/** The cap read answered the Embed-not-enabled refusal. */
class EmbedNotEnabledException : Exception(embedNotEnabledMessage)

/**
 * Whether JSON is the Embed-not-enabled refusal,
 * {"error": {"message": "Embed isn't enabled for this network."}}.
 */
fun isEmbedNotEnabled(json: JsonElement?): Boolean {
    val error = (json as? JsonObject)?.get("error") as? JsonObject ?: return false
    val message = error["message"] as? JsonPrimitive ?: return false
    return message.isString && message.content == embedNotEnabledMessage
}

/**
 * A cap object from its JSON, or null when the JSON is not one: an error answer, a limit that is
 * not an integer of 0 or more, or a used count that is not an integer.
 */
fun parseDataCap(json: JsonElement?): DataCap? {
    val obj = json as? JsonObject ?: return null
    if (obj["error"] != null && obj["error"] != JsonNull) {
        return null
    }
    // a limit: null when absent or JSON null; invalid unless an integer of 0 or more
    val invalid = Any()
    val limit = { name: String ->
        when (val value = obj[name]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: invalid
            else -> invalid
        }
    }
    // a used count: 0 when absent; invalid unless an integer
    val count = { name: String ->
        when (val value = obj[name]) {
            null, JsonNull -> 0L
            is JsonPrimitive -> value.takeIf { !it.isString }?.longOrNull ?: invalid
            else -> invalid
        }
    }
    val text = { name: String -> (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content }
    val monthlyByteLimit = limit("monthly_byte_limit")
    val totalByteLimit = limit("total_byte_limit")
    val monthlyUsedByteCount = count("monthly_used_byte_count")
    val totalUsedByteCount = count("total_used_byte_count")
    if (monthlyByteLimit === invalid || totalByteLimit === invalid ||
        monthlyUsedByteCount === invalid || totalUsedByteCount === invalid
    ) {
        return null
    }
    return DataCap(
        clientId = text("client_id").orEmpty(),
        monthlyByteLimit = monthlyByteLimit as Long?,
        monthlyUsedByteCount = monthlyUsedByteCount as Long,
        monthlyPeriodEnd = text("monthly_period_end"),
        totalByteLimit = totalByteLimit as Long?,
        totalUsedByteCount = totalUsedByteCount as Long,
        capped = (obj["capped"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false,
        cappedReason = text("capped_reason").orEmpty(),
    )
}

/** A cap object from JSON text, or null when the text is not one. */
fun parseDataCap(text: String): DataCap? {
    val json = try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        return null
    }
    return parseDataCap(json)
}

/**
 * Reads at most limit bytes; a longer answer is refused. (InputStream.readNBytes needs Android 13,
 * and the app runs on Android 8.)
 */
fun readBounded(stream: InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) {
            break
        }
        out.write(buffer, 0, n)
        if (limit < out.size()) {
            throw IOException("the answer is too large")
        }
    }
    return out.toByteArray()
}

/**
 * Reads this installation's caps with its client JWT: GET /network/client-data-cap, which answers
 * with the client's own cap object. Throws DataCapReadException for any failure, including a 404
 * from a server without the cap routes, and EmbedNotEnabledException for the Embed-not-enabled
 * refusal. Never log the token.
 */
fun readDataCap(apiUrl: String, clientJwt: String): DataCap {
    val connection = try {
        URL(apiUrl.trimEnd('/') + clientDataCapPath).openConnection() as HttpURLConnection
    } catch (e: IOException) {
        throw DataCapReadException("cap read: ${e.message}")
    }
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Authorization", "Bearer $clientJwt")
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            throw DataCapReadException("cap read answered HTTP $status")
        }
        val text = connection.inputStream.use { readBounded(it, answerByteLimit) }.decodeToString()
        val json = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
        if (isEmbedNotEnabled(json)) {
            throw EmbedNotEnabledException()
        }
        return parseDataCap(json) ?: throw DataCapReadException("cap read answered something that is not a cap object")
    } catch (e: IOException) {
        throw DataCapReadException("cap read: ${e.message}")
    } finally {
        connection.disconnect()
    }
}

/** Reads this installation's caps as a [DataCapReadResult]. */
fun readDataCapResult(apiUrl: String, clientJwt: String): DataCapReadResult =
    try {
        DataCapReadResult.Read(readDataCap(apiUrl, clientJwt))
    } catch (e: EmbedNotEnabledException) {
        DataCapReadResult.EmbedNotEnabled
    } catch (e: DataCapReadException) {
        DataCapReadResult.Failed
    }
