// The installation's data caps (EMBED_CONTRACT.md, "Backend: per-user data
// caps"). The backend sets them with the root credential; the app reads its
// own with its client JWT: GET /network/client-data-cap at the URnetwork API.
// Usage is accounted when transfer contracts settle, so the counts lag live
// traffic. The parsing here never throws, so the self-test checks it without a
// network.
import java.io.IOException
import java.net.URI
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

const val capPath = "/network/client-data-cap"

/**
 * One cap object, as GET /network/client-data-cap answers it. A limit is null when that cap is not
 * set. cappedReason is "monthly", "total", or "" when the client is not capped.
 */
data class DataCap(
    val clientId: String = "",
    val monthlyByteLimit: Long? = null,
    val monthlyUsedByteCount: Long = 0,
    val monthlyPeriodStart: String = "",
    // RFC 3339; when monthly usage resets
    val monthlyPeriodEnd: String = "",
    val totalByteLimit: Long? = null,
    val totalUsedByteCount: Long = 0,
    val totalPeriodStart: String = "",
    // true while a cap is reached: the client gets no new transfer contracts
    val capped: Boolean = false,
    val cappedReason: String = "",
) {
    companion object {
        /**
         * The cap object of a JSON text; null when the text is not one: not JSON, not an object, an
         * error answer, or a field of the wrong type.
         */
        fun parse(json: String?): DataCap? =
            try {
                fromObject(parseNullableJsonObject(json))
            } catch (e: IllegalArgumentException) {
                null
            }

        /** The cap object of a parsed object; null as for parse, and for null. */
        fun fromObject(members: JsonObject?): DataCap? {
            val error = members?.get("error")
            if (members == null || (error != null && error !is JsonNull)) {
                return null
            }
            return try {
                DataCap(
                    clientId = members.stringMember("client_id") ?: "",
                    monthlyByteLimit = members.nullableLongMember("monthly_byte_limit"),
                    monthlyUsedByteCount = members.longMember("monthly_used_byte_count"),
                    monthlyPeriodStart = members.stringMember("monthly_period_start") ?: "",
                    monthlyPeriodEnd = members.stringMember("monthly_period_end") ?: "",
                    totalByteLimit = members.nullableLongMember("total_byte_limit"),
                    totalUsedByteCount = members.longMember("total_used_byte_count"),
                    totalPeriodStart = members.stringMember("total_period_start") ?: "",
                    capped = members.booleanMember("capped"),
                    cappedReason = members.stringMember("capped_reason") ?: "",
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * The cap readings so far. A failed reading keeps the last successful one; before any success, a
 * failure shows "unavailable". Owned by the status loop's thread.
 */
class CapReadings {
    /** The latest successful reading; null until one succeeds. */
    var latest: DataCap? = null
        private set

    /** Whether any reading finished, successful or not. */
    var attempted = false
        private set

    /** Records one finished reading: a cap object, or null for a failure. */
    fun record(reading: DataCap?) {
        attempted = true
        if (reading != null) {
            latest = reading
        }
    }
}

/**
 * GET /network/client-data-cap with the client JWT; the client is the JWT's own, so no client_id is
 * sent. Null for any failure: unreachable, a status other than 2xx (a server without the cap routes
 * answers 404), or an answer that is not a cap object. Never throws.
 */
fun readCap(apiOrigin: URI, clientJwt: String, transport: HttpTransport): DataCap? =
    try {
        val answer = transport.send("GET", apiOrigin.resolve(capPath), clientJwt, null)
        if (answer.status in 200..299) DataCap.parse(answer.body) else null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }
