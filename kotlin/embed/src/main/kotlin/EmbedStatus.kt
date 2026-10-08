// The status that every embed example shows, with the exact text rules of
// EMBED_CONTRACT.md ("Status"): the status field, the data used this month and
// the running total. The SDK hands the device values over as C ABI JSON with
// the Go field names; the readers and rules here never throw on bad input, so
// the self-test checks them without the native SDK runtime, a network or
// credentials.
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

// GUI and Android only: after an auth logout or a 401 or 409 from the token
// server, until started again
const val statusSignedOut = "signed out"

// GUI and Android only: before start or after stop
const val statusStopped = "stopped"

// the platform disconnected this client for its network's client limit, and
// the sdk holds off reconnecting until the retry time
const val statusClientLimit = "client limit"

// a cap of 0 is reached
const val statusPaused = "paused"
const val statusDataCapReached = "data cap reached"
const val statusConnected = "connected"
const val statusConnecting = "connecting"

// a data field before the first cap reading finishes
const val dataChecking = "checking"

// a data field when the first cap reading failed
const val dataUnavailable = "unavailable"

// a data field whose cap is not set
const val dataNoCap = "no cap"

const val cappedReasonMonthly = "monthly"
const val cappedReasonTotal = "total"

// URNET_CLIENT_LIMIT_STATUS_*
const val clientLimitStatusNone = ""
const val clientLimitStatusExceeded = "client_limit_exceeded"

private val byteUnits = listOf("kB", "MB", "GB", "TB", "PB", "EB")
private const val minuteMillis = 60L * 1000
private const val dayMinutes = 24L * 60
private val rfc3339 = Regex("""(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?([Zz]|[+-]\d{2}:\d{2})""")

/** The client limit status of the c abi: status "" or "client_limit_exceeded". */
data class ClientLimit(val status: String, val retryTime: Long)

private val noClientLimit = ClientLimit(clientLimitStatusNone, 0)

/**
 * Decimal units with one decimal: "0 B", "999 B", "1.0 kB", "5.0 GB". Data plans are sold in decimal
 * units. A value that rounds to 1000.0 moves to the next unit; the unit check follows the Go
 * reference: double division, rounded half up. The tenth is the nearest one with ties to even, from
 * the exact binary value, as Go's %.1f rounds it: 1250 bytes (1.25 kB) shows "1.2 kB" and 1750 bytes
 * "1.8 kB". String.format rounds ties up, so BigDecimal does the rounding.
 */
fun formatByteCount(byteCount: Long): String {
    if (byteCount < 1000) {
        return "$byteCount B"
    }
    var value = byteCount.toDouble() / 1000
    var unitIndex = 0
    while (unitIndex < byteUnits.size - 1 && 1000 <= Math.round(value * 10) / 10.0) {
        value /= 1000
        unitIndex += 1
    }
    return BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + " " + byteUnits[unitIndex]
}

/**
 * The client limit text: "client limit, retry at 19:05 UTC". The retry time (unix milliseconds) is
 * rounded up to the next whole minute in UTC, so the shown time is never before the real retry; a
 * retry time of 0 shows "client limit".
 */
fun clientLimitText(clientLimitRetryTime: Long): String {
    if (clientLimitRetryTime <= 0) {
        return statusClientLimit
    }
    // whole minutes since the epoch, rounded up without overflow
    val retryMinute = (clientLimitRetryTime - 1) / minuteMillis + 1
    val minuteOfDay = retryMinute % dayMinutes
    return "%s, retry at %02d:%02d UTC".format(java.util.Locale.ROOT, statusClientLimit, minuteOfDay / 60, minuteOfDay % 60)
}

/**
 * "resets 2026-11-01 00:00 UTC" for a monthly_period_end in RFC 3339, in UTC with the seconds
 * rounded up to the next whole minute; null when it does not parse (or is after the year 9999).
 */
fun resetText(monthlyPeriodEnd: String?): String? {
    if (monthlyPeriodEnd == null) {
        return null
    }
    val match = rfc3339.matchEntire(monthlyPeriodEnd) ?: return null
    val groups = match.groupValues
    return try {
        val zone = groups[8]
        val offset = if (zone.equals("Z", ignoreCase = true)) {
            ZoneOffset.UTC
        } else {
            val sign = if (zone[0] == '-') -1 else 1
            ZoneOffset.ofHoursMinutes(sign * zone.substring(1, 3).toInt(), sign * zone.substring(4, 6).toInt())
        }
        val end = OffsetDateTime.of(
            groups[1].toInt(), groups[2].toInt(), groups[3].toInt(),
            groups[4].toInt(), groups[5].toInt(), groups[6].toInt(), 0, offset,
        )
        val partialSecond = groups[7].any { it != '0' }
        val epochSecond = end.toEpochSecond()
        var minute = Math.floorDiv(epochSecond, 60L)
        if (Math.floorMod(epochSecond, 60L) != 0L || partialSecond) {
            minute += 1
        }
        val utc = LocalDateTime.ofEpochSecond(minute * 60, 0, ZoneOffset.UTC)
        if (9999 < utc.year) {
            return null
        }
        "resets %04d-%02d-%02d %02d:%02d UTC".format(
            java.util.Locale.ROOT, utc.year, utc.monthValue, utc.dayOfMonth, utc.hour, utc.minute,
        )
    } catch (e: DateTimeException) {
        // an invalid date or offset
        null
    }
}

/**
 * The status field: the first rule that applies. started and signedOut matter to GUI apps only; a
 * console app passes started and not signed out. latestCap is the latest successful cap reading,
 * null before one.
 */
fun embedStatus(
    started: Boolean,
    signedOut: Boolean,
    clientLimitStatus: String,
    clientLimitRetryTime: Long,
    latestCap: DataCap?,
    providersAdded: Int,
): String {
    if (signedOut) {
        return statusSignedOut
    }
    if (!started) {
        return statusStopped
    }
    if (clientLimitStatus == clientLimitStatusExceeded) {
        return clientLimitText(clientLimitRetryTime)
    }
    if (latestCap != null && latestCap.capped) {
        // the cap that capped_reason names; an unknown reason names none
        val reachedLimit = when (latestCap.cappedReason) {
            cappedReasonMonthly -> latestCap.monthlyByteLimit
            cappedReasonTotal -> latestCap.totalByteLimit
            else -> null
        }
        if (reachedLimit == 0L) {
            return statusPaused
        }
        if (latestCap.cappedReason == cappedReasonMonthly) {
            resetText(latestCap.monthlyPeriodEnd)?.let { return "$statusDataCapReached, $it" }
        }
        return statusDataCapReached
    }
    if (1 <= providersAdded) {
        return statusConnected
    }
    return statusConnecting
}

/** A data field: "checking", "unavailable", "no cap" or "<used> of <limit>". A field without a cap never shows a used count. */
fun dataField(readings: CapReadings, monthly: Boolean): String {
    val cap = readings.latest ?: return if (readings.attempted) dataUnavailable else dataChecking
    val limit = (if (monthly) cap.monthlyByteLimit else cap.totalByteLimit) ?: return dataNoCap
    val used = if (monthly) cap.monthlyUsedByteCount else cap.totalUsedByteCount
    return "${formatByteCount(used)} of ${formatByteCount(limit)}"
}

/** The console status line. */
fun statusLine(status: String, monthly: String, total: String) =
    "status: $status | data this month: $monthly | data total: $total"

/**
 * The c abi's client limit status JSON, for example {"Status": "client_limit_exceeded", "RetryTime":
 * 1791313500000}. NULL (the call could not run) and a value that does not parse read as no limit.
 */
fun parseClientLimit(clientLimitStatusJson: String?): ClientLimit =
    try {
        val members = parseNullableJsonObject(clientLimitStatusJson)
        if (members == null) {
            noClientLimit
        } else {
            ClientLimit(members.stringMember("Status") ?: clientLimitStatusNone, members.longMember("RetryTime"))
        }
    } catch (e: IllegalArgumentException) {
        noClientLimit
    }

/**
 * ProviderStateAdded of the c abi's window status JSON: the providers in the window. NULL before the
 * window exists, and a value that does not parse, read as 0.
 */
fun providersAdded(windowStatusJson: String?): Int =
    try {
        val added = parseNullableJsonObject(windowStatusJson)?.longMember("ProviderStateAdded") ?: 0
        added.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    } catch (e: IllegalArgumentException) {
        0
    }
