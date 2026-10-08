// The cap checks of the embed self-test (EMBED_CONTRACT.md, "Self-test"): the cap object parsing
// and the data fields — checking, unavailable, a later failure keeping the last value, no cap for a
// null limit even with a used count, and the used count of the limit.
package com.example.urnetwork.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cap object parsing and the data field rules. */
class DataCapTest {
    /** A full cap object parses into its fields. */
    @Test
    fun parseFullCapObject() {
        val cap = parseDataCap(
            """
            {
              "client_id": "11111111-1111-1111-1111-111111111111",
              "monthly_byte_limit": 5000000000,
              "monthly_used_byte_count": 1234567890,
              "monthly_period_start": "2026-10-01T00:00:00Z",
              "monthly_period_end": "2026-11-01T00:00:00Z",
              "total_byte_limit": 10000000000,
              "total_used_byte_count": 2500000000,
              "total_period_start": "2026-09-15T08:00:00Z",
              "capped": false,
              "capped_reason": ""
            }
            """.trimIndent(),
        )
        assertNotNull(cap)
        cap!!
        assertEquals("11111111-1111-1111-1111-111111111111", cap.clientId)
        assertEquals(5000000000L, cap.monthlyByteLimit)
        assertEquals(1234567890L, cap.monthlyUsedByteCount)
        assertEquals("2026-11-01T00:00:00Z", cap.monthlyPeriodEnd)
        assertEquals(10000000000L, cap.totalByteLimit)
        assertEquals(2500000000L, cap.totalUsedByteCount)
        assertFalse(cap.capped)
        assertEquals("", cap.cappedReason)
    }

    /** A null or absent limit means no cap; an absent used count is 0. */
    @Test
    fun nullAndAbsentLimits() {
        val cap = parseDataCap("""{"client_id": "c", "monthly_byte_limit": null, "monthly_used_byte_count": 7}""")!!
        assertNull(cap.monthlyByteLimit)
        assertEquals(7L, cap.monthlyUsedByteCount)
        assertNull(cap.totalByteLimit)
        assertEquals(0L, cap.totalUsedByteCount)
        assertFalse(cap.capped)
    }

    /** capped and capped_reason are read as given, including a reason this app does not know. */
    @Test
    fun cappedAndReason() {
        val total = parseDataCap("""{"total_byte_limit": 0, "capped": true, "capped_reason": "total"}""")!!
        assertTrue(total.capped)
        assertEquals(cappedReasonTotal, total.cappedReason)
        assertEquals(0L, total.totalByteLimit)
        val unknown = parseDataCap("""{"monthly_byte_limit": 5, "capped": true, "capped_reason": "weekly"}""")!!
        assertTrue(unknown.capped)
        assertEquals("weekly", unknown.cappedReason)
        // read as capped without a reset time
        assertEquals("data cap reached", embedStatus(EmbedStatusInputs(started = true, capReading = DataCapReading.Read(unknown))))
    }

    /** An error answer, a malformed limit or count, and anything that is not an object are not cap objects. */
    @Test
    fun notCapObjects() {
        val invalid = listOf(
            """{"error": {"message": "Client does not exist."}}""",
            """{"monthly_byte_limit": -1}""",
            """{"monthly_byte_limit": "5000000000"}""",
            """{"total_byte_limit": 1.5}""",
            """{"monthly_used_byte_count": "7"}""",
            """[1, 2, 3]""",
            """"text"""",
            "not json",
            "",
        )
        for (text in invalid) {
            assertNull(text, parseDataCap(text))
        }
    }

    /** The first failure reads as unavailable; a later failure keeps the last value. */
    @Test
    fun readingsKeepTheLastValue() {
        val readings = DataCapReadings()
        assertEquals(DataCapReading.Checking, readings.current)
        readings.failed()
        assertEquals(DataCapReading.Unavailable, readings.current)
        val cap = testCap(monthlyByteLimit = 5000000000, monthlyUsedByteCount = 1000)
        readings.succeeded(cap)
        assertEquals(DataCapReading.Read(cap), readings.current)
        readings.failed()
        assertEquals(DataCapReading.Read(cap), readings.current)
        readings.reset()
        assertEquals(DataCapReading.Checking, readings.current)
    }

    /** The data fields: checking, unavailable, no cap (even with a used count), used of limit. */
    @Test
    fun dataFields() {
        assertEquals("checking", dataFieldText(DataCapReading.Checking, monthly = true))
        assertEquals("unavailable", dataFieldText(DataCapReading.Unavailable, monthly = false))
        // a server need not report usage for an uncapped client: never show a used count without its cap
        val uncapped = DataCapReading.Read(testCap(monthlyByteLimit = null, monthlyUsedByteCount = 123456, totalByteLimit = null, totalUsedByteCount = 99))
        assertEquals("no cap", dataFieldText(uncapped, monthly = true))
        assertEquals("no cap", dataFieldText(uncapped, monthly = false))
        val capped = DataCapReading.Read(testCap(monthlyByteLimit = 5000000000, monthlyUsedByteCount = 1234567890, totalByteLimit = 10000000000, totalUsedByteCount = 10000000000))
        assertEquals("1.2 GB of 5.0 GB", dataFieldText(capped, monthly = true))
        assertEquals("10.0 GB of 10.0 GB", dataFieldText(capped, monthly = false))
    }
}
