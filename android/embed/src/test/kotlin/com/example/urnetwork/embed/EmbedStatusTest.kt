// The status checks of the embed self-test (EMBED_CONTRACT.md, "Status" and "Self-test"): the byte,
// reset time, client limit text and status line vectors, and the status rules with the rule vectors.
// They run on the JVM without credentials, a network or the native sdk runtime; the sdk constant
// they read is a compile-time constant.
package com.example.urnetwork.embed

import com.bringyour.sdk.Sdk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val testClientId = "11111111-1111-1111-1111-111111111111"

/** A cap object with the given fields; uncapped with no limits by default. */
fun testCap(
    monthlyByteLimit: Long? = null,
    monthlyUsedByteCount: Long = 0,
    monthlyPeriodEnd: String? = "2026-11-01T00:00:00Z",
    totalByteLimit: Long? = null,
    totalUsedByteCount: Long = 0,
    capped: Boolean = false,
    cappedReason: String = "",
): DataCap = DataCap(
    clientId = testClientId,
    monthlyByteLimit = monthlyByteLimit,
    monthlyUsedByteCount = monthlyUsedByteCount,
    monthlyPeriodEnd = monthlyPeriodEnd,
    totalByteLimit = totalByteLimit,
    totalUsedByteCount = totalUsedByteCount,
    capped = capped,
    cappedReason = cappedReason,
)

/** Status text rules and the golden vectors. */
class EmbedStatusTest {
    /** The sdk's client limit status is the contract's text. */
    @Test
    fun clientLimitStatusConstant() {
        assertEquals("client_limit_exceeded", Sdk.ClientLimitStatusExceeded)
    }

    /** Data amounts use decimal units with one decimal, ties to even. */
    @Test
    fun formatDataAmount() {
        val cases = listOf(
            0L to "0 B",
            999L to "999 B",
            1000L to "1.0 kB",
            999949L to "999.9 kB",
            // exact halves round to even, as Go's %.1f does
            1250L to "1.2 kB",
            1750L to "1.8 kB",
            // rounds to 1000.0 kB, so the next unit
            999999L to "1.0 MB",
            1234567890L to "1.2 GB",
            5000000000L to "5.0 GB",
            10000000000L to "10.0 GB",
            3000000000000L to "3.0 TB",
            Long.MAX_VALUE to "9.2 EB",
        )
        for ((byteCount, text) in cases) {
            assertEquals("byte count $byteCount", text, formatDataAmount(byteCount))
        }
    }

    /** The monthly reset time is in UTC, with seconds rounded up to the next whole minute. */
    @Test
    fun monthlyResetText() {
        val cases = listOf(
            "2026-11-01T00:00:00Z" to "resets 2026-11-01 00:00 UTC",
            // rounds up
            "2026-10-31T23:59:00.001Z" to "resets 2026-11-01 00:00 UTC",
            // to UTC
            "2026-10-31T19:00:00-05:00" to "resets 2026-11-01 00:00 UTC",
        )
        for ((end, text) in cases) {
            assertEquals(end, text, monthlyResetText(end))
        }
        assertNull(monthlyResetText("not a time"))
        assertNull(monthlyResetText(null))
    }

    /** The client limit status names the retry time in UTC, rounded up to the next whole minute. */
    @Test
    fun clientLimitStatusText() {
        // 2026-10-06 19:05:00.000 UTC, exactly on a minute
        assertEquals("client limit, retry at 19:05 UTC", clientLimitStatusText(1791313500000))
        // 19:04:00.001 rounds up, so the shown time is never before the retry
        assertEquals("client limit, retry at 19:05 UTC", clientLimitStatusText(1791313440001))
        assertEquals("client limit", clientLimitStatusText(0))
    }

    /** The console status line vectors, from the same fields the app shows. */
    @Test
    fun statusLines() {
        data class Case(val inputs: EmbedStatusInputs, val line: String)
        val cases = listOf(
            Case(
                EmbedStatusInputs(started = true),
                "status: connecting | data this month: checking | data total: checking",
            ),
            Case(
                EmbedStatusInputs(
                    started = true,
                    providersAdded = 2,
                    capReading = DataCapReading.Read(testCap(monthlyByteLimit = 5000000000, monthlyUsedByteCount = 1234567890)),
                ),
                "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap",
            ),
            Case(
                EmbedStatusInputs(started = true, providersAdded = 2, capReading = DataCapReading.Unavailable),
                "status: connected | data this month: unavailable | data total: unavailable",
            ),
            Case(
                EmbedStatusInputs(
                    started = true,
                    providersAdded = 2,
                    capReading = DataCapReading.Read(
                        testCap(
                            monthlyByteLimit = 5000000000,
                            monthlyUsedByteCount = 5000000000,
                            monthlyPeriodEnd = "2026-11-01T00:00:00Z",
                            capped = true,
                            cappedReason = cappedReasonMonthly,
                        ),
                    ),
                ),
                "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap",
            ),
            Case(
                EmbedStatusInputs(
                    started = true,
                    providersAdded = 2,
                    capReading = DataCapReading.Read(
                        testCap(totalByteLimit = 10000000000, totalUsedByteCount = 10000000000, capped = true, cappedReason = cappedReasonTotal),
                    ),
                ),
                "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB",
            ),
            Case(
                EmbedStatusInputs(
                    started = true,
                    providersAdded = 2,
                    capReading = DataCapReading.Read(
                        testCap(monthlyByteLimit = 0, monthlyUsedByteCount = 0, capped = true, cappedReason = cappedReasonMonthly),
                    ),
                ),
                "status: paused | data this month: 0 B of 0 B | data total: no cap",
            ),
            Case(
                EmbedStatusInputs(
                    started = true,
                    clientLimitStatus = Sdk.ClientLimitStatusExceeded,
                    clientLimitRetryTime = 1791313500000,
                    capReading = DataCapReading.Read(testCap(monthlyByteLimit = 5000000000, monthlyUsedByteCount = 0)),
                ),
                "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap",
            ),
        )
        for (case in cases) {
            assertEquals(case.line, embedStatusFields(case.inputs).line())
        }
    }

    /** The status rule vectors: the first rule that applies, in the contract's order. */
    @Test
    fun statusRules() {
        data class Case(val inputs: EmbedStatusInputs, val status: String)
        val clientLimit = Sdk.ClientLimitStatusExceeded
        val cases = listOf(
            Case(EmbedStatusInputs(started = false, signedOut = false), "stopped"),
            Case(EmbedStatusInputs(started = false, signedOut = true), "signed out"),
            // client limit before paused
            Case(
                EmbedStatusInputs(
                    started = true,
                    clientLimitStatus = clientLimit,
                    clientLimitRetryTime = 1791313500000,
                    capReading = DataCapReading.Read(testCap(monthlyByteLimit = 0, capped = true, cappedReason = cappedReasonMonthly)),
                    providersAdded = 3,
                ),
                "client limit, retry at 19:05 UTC",
            ),
            // paused before data cap reached
            Case(
                EmbedStatusInputs(
                    started = true,
                    capReading = DataCapReading.Read(testCap(monthlyByteLimit = 0, capped = true, cappedReason = cappedReasonMonthly)),
                    providersAdded = 3,
                ),
                "paused",
            ),
            // the cap that capped_reason names decides paused
            Case(
                EmbedStatusInputs(
                    started = true,
                    capReading = DataCapReading.Read(
                        testCap(monthlyByteLimit = 5000000000, totalByteLimit = 0, capped = true, cappedReason = cappedReasonTotal),
                    ),
                    providersAdded = 3,
                ),
                "paused",
            ),
            Case(
                EmbedStatusInputs(
                    started = true,
                    capReading = DataCapReading.Read(
                        testCap(monthlyByteLimit = 5000000000, monthlyPeriodEnd = "2026-11-01T00:00:00Z", capped = true, cappedReason = cappedReasonMonthly),
                    ),
                    providersAdded = 3,
                ),
                "data cap reached, resets 2026-11-01 00:00 UTC",
            ),
            Case(
                EmbedStatusInputs(
                    started = true,
                    capReading = DataCapReading.Read(testCap(totalByteLimit = 10000000000, capped = true, cappedReason = cappedReasonTotal)),
                    providersAdded = 0,
                ),
                "data cap reached",
            ),
            Case(EmbedStatusInputs(started = true, capReading = DataCapReading.Read(testCap()), providersAdded = 1), "connected"),
            Case(EmbedStatusInputs(started = true, capReading = DataCapReading.Checking, providersAdded = 0), "connecting"),
            // a monthly cap whose period end does not parse has no reset time
            Case(
                EmbedStatusInputs(
                    started = true,
                    capReading = DataCapReading.Read(
                        testCap(monthlyByteLimit = 5000000000, monthlyPeriodEnd = "soon", capped = true, cappedReason = cappedReasonMonthly),
                    ),
                ),
                "data cap reached",
            ),
            // an unknown reason names no cap: capped without a reset time, and not paused
            Case(
                EmbedStatusInputs(
                    started = true,
                    capReading = DataCapReading.Read(testCap(monthlyByteLimit = 0, capped = true, cappedReason = "weekly")),
                ),
                "data cap reached",
            ),
            // a failed first cap reading names no cap, so the connection decides
            Case(EmbedStatusInputs(started = true, capReading = DataCapReading.Unavailable, providersAdded = 1), "connected"),
        )
        for (case in cases) {
            assertEquals(case.inputs.toString(), case.status, embedStatus(case.inputs))
        }
    }
}
