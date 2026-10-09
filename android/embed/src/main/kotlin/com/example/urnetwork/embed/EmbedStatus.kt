// The status fields that every embed example shows, with the exact rules of EMBED_CONTRACT.md
// ("Status"): the status, the data used this month and the running total, each against its cap.
// These functions take plain values, so the unit tests check them on the JVM without the native
// sdk runtime. The sdk value they compare with is a compile-time constant of the gomobile Sdk
// class, which Kotlin copies into this code, so reading it loads nothing.
package com.example.urnetwork.embed

import com.bringyour.sdk.Sdk
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

// GUI and Android: after an auth logout, or a 401 or 409 from the token server, until started again
const val embedStatusSignedOut = "signed out"

// GUI and Android: before start and after stop
const val embedStatusStopped = "stopped"

// the platform disconnected this client for its network's concurrent client limit, and the sdk
// holds off reconnecting until the retry time
const val embedStatusClientLimit = "client limit"

// a cap of 0 is reached: the backend paused this installation
const val embedStatusPaused = "paused"
const val embedStatusDataCapReached = "data cap reached"
const val embedStatusConnected = "connected"
const val embedStatusConnecting = "connecting"

// a data field before the first cap read finishes
const val dataFieldChecking = "checking"

// a data field when the first cap read failed
const val dataFieldUnavailable = "unavailable"

// a data field without a cap
const val dataFieldNoCap = "no cap"

private val dataUnits = listOf("kB", "MB", "GB", "TB", "PB", "EB")
private val thousand = BigDecimal(1000)

// the client limit retry time and the monthly reset time, in UTC
private val hourMinuteFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val dateMinuteFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)

/** What the status rules read: the run, the device and the latest cap reading. */
data class EmbedStatusInputs(
    val started: Boolean,
    val signedOut: Boolean = false,
    // "" or Sdk.ClientLimitStatusExceeded
    val clientLimitStatus: String = "",
    // the end of the client limit hold in unix milliseconds; 0 when there is none
    val clientLimitRetryTime: Long = 0,
    val capReading: DataCapReading = DataCapReading.Checking,
    // ProviderStateAdded of the device's window status
    val providersAdded: Long = 0,
)

/** One status snapshot: the three fields as text. */
data class EmbedStatusFields(val status: String, val dataThisMonth: String, val dataTotal: String) {
    /**
     * The console examples' status line, for example
     * "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap". The app shows
     * the fields as labeled values; the line is for its log and the self-test.
     */
    fun line(): String = "status: $status | data this month: $dataThisMonth | data total: $dataTotal"
}

/** The three fields from the inputs. */
fun embedStatusFields(inputs: EmbedStatusInputs): EmbedStatusFields = EmbedStatusFields(
    status = embedStatus(inputs),
    dataThisMonth = dataFieldText(inputs.capReading, monthly = true),
    dataTotal = dataFieldText(inputs.capReading, monthly = false),
)

/**
 * The status: the first rule that applies. Signed out; stopped; the client limit with its retry
 * time; paused while the reached cap (the one capped_reason names) is 0; data cap reached, with the
 * monthly reset time for the monthly cap; connected once the window has a provider added;
 * connecting otherwise.
 */
fun embedStatus(inputs: EmbedStatusInputs): String {
    if (inputs.signedOut) {
        return embedStatusSignedOut
    }
    if (!inputs.started) {
        return embedStatusStopped
    }
    if (inputs.clientLimitStatus == Sdk.ClientLimitStatusExceeded) {
        return clientLimitStatusText(inputs.clientLimitRetryTime)
    }
    val cap = (inputs.capReading as? DataCapReading.Read)?.cap
    if (cap != null && cap.capped) {
        // an unknown reason names no cap: capped, but neither paused nor with a reset time
        val reachedLimit = when (cap.cappedReason) {
            cappedReasonMonthly -> cap.monthlyByteLimit
            cappedReasonTotal -> cap.totalByteLimit
            else -> null
        }
        if (reachedLimit == 0L) {
            return embedStatusPaused
        }
        if (cap.cappedReason == cappedReasonMonthly) {
            val resetText = monthlyResetText(cap.monthlyPeriodEnd)
            if (resetText != null) {
                return "$embedStatusDataCapReached, $resetText"
            }
        }
        return embedStatusDataCapReached
    }
    if (1 <= inputs.providersAdded) {
        return embedStatusConnected
    }
    return embedStatusConnecting
}

/**
 * A data field: checking, unavailable, no cap for a null limit (even with a used count), or the
 * used count of the limit, for example "1.2 GB of 5.0 GB".
 */
fun dataFieldText(reading: DataCapReading, monthly: Boolean): String = when (reading) {
    DataCapReading.Checking -> dataFieldChecking
    DataCapReading.Unavailable -> dataFieldUnavailable
    is DataCapReading.Read -> {
        val limit = if (monthly) reading.cap.monthlyByteLimit else reading.cap.totalByteLimit
        val used = if (monthly) reading.cap.monthlyUsedByteCount else reading.cap.totalUsedByteCount
        if (limit == null) dataFieldNoCap else "${formatDataAmount(used)} of ${formatDataAmount(limit)}"
    }
}

/**
 * Decimal units with one decimal, as data plans are sold: "0 B", "999 B", "1.0 kB", "12.4 MB". A
 * value that rounds to 1000.0 moves to the next unit. The decimal is the exact value rounded to the
 * nearest tenth with ties to even, never a binary fraction: 1050 bytes shows "1.0 kB" and 1250
 * bytes "1.2 kB".
 */
fun formatDataAmount(byteCount: Long): String {
    if (byteCount < 1000) {
        return "$byteCount B"
    }
    // dividing by 1000 is exact in decimal, so the tie rule sees the exact value
    var value = BigDecimal(byteCount).divide(thousand)
    var unitIndex = 0
    while (unitIndex < dataUnits.size - 1 && thousand <= value.setScale(1, RoundingMode.HALF_EVEN)) {
        value = value.divide(thousand)
        unitIndex += 1
    }
    return "${value.setScale(1, RoundingMode.HALF_EVEN).toPlainString()} ${dataUnits[unitIndex]}"
}

/**
 * The client limit status, "client limit, retry at 19:05 UTC": the retry time (unix
 * milliseconds) rounded up to the next whole minute in UTC, so the shown time is never before the
 * real retry. A retry time of 0 shows "client limit".
 */
fun clientLimitStatusText(retryTime: Long): String {
    if (retryTime <= 0) {
        return embedStatusClientLimit
    }
    val minuteMillis = 60L * 1000
    val retryMinute = (retryTime + minuteMillis - 1) / minuteMillis
    val retryAt = Instant.ofEpochSecond(retryMinute * 60).atOffset(ZoneOffset.UTC)
    return "$embedStatusClientLimit, retry at ${hourMinuteFormat.format(retryAt)} UTC"
}

/**
 * "resets 2026-11-01 00:00 UTC" from monthly_period_end (RFC 3339, any offset) in UTC, with any
 * seconds rounded up to the next whole minute; null when it does not parse.
 */
fun monthlyResetText(monthlyPeriodEnd: String?): String? {
    if (monthlyPeriodEnd == null) {
        return null
    }
    val end = try {
        OffsetDateTime.parse(monthlyPeriodEnd).toInstant()
    } catch (e: DateTimeParseException) {
        return null
    }
    val minute = end.truncatedTo(ChronoUnit.MINUTES)
    val resetAt = if (minute == end) end else minute.plus(1, ChronoUnit.MINUTES)
    return "resets ${dateMinuteFormat.format(resetAt.atOffset(ZoneOffset.UTC))} UTC"
}
