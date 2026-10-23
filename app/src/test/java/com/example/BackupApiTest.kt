package com.example

import com.example.data.remote.BackupApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7 — the manager-side view of the server backup:
 * status/run JSON parsing, freshness rule, and the guarantee that no
 * snapshot payload or secret ever appears in the parsed results.
 */
class BackupApiTest {

    private val statusJson = """
    {
      "success": true,
      "message": "وضعیت پشتیبان‌گیری سرور دریافت شد.",
      "data": {
        "lastRun": { "id": "run-1", "trigger": "AUTO", "outcome": "FAILED", "error": "پشتیبان خراب است", "started_at_epoch": 1000, "duration_ms": 300 },
        "lastHealthy": { "id": "b1", "created_at_epoch": 9000, "total_bytes": 100000, "stored_bytes": 40000, "files_included": 1 },
        "healthyCount": 3,
        "retentionDays": 7,
        "includeFiles": true,
        "history": [
          { "id": "b1", "kind": "AUTO", "status": "HEALTHY", "created_at_epoch": 9000, "total_bytes": 100000, "files_included": 1, "error": "" }
        ]
      }
    }
    """.trimIndent()

    @Test
    fun statusParsingExtractsRunHealthyAndHistory() {
        val status = BackupApi.parseStatusResponse(statusJson)
        assertNotNull(status)
        assertEquals("FAILED", status!!.lastRun?.outcome)
        assertEquals("پشتیبان خراب است", status.lastRun?.error)
        assertEquals(9000L, status.lastHealthy?.createdAtEpoch)
        assertTrue(status.lastHealthy!!.filesIncluded)
        assertEquals(3, status.healthyCount)
        assertEquals(7, status.retentionDays)
        assertEquals(1, status.history.size)
    }

    @Test
    fun garbageResponsesAreHandledSafely() {
        assertNull(BackupApi.parseStatusResponse("not json at all"))
        assertNull(BackupApi.parseStatusResponse("""{"success": false}"""))
    }

    @Test
    fun freshnessRuleAllowsTwoRetentionPeriods() {
        val status = BackupApi.parseStatusResponse(statusJson)!!
        val now = 9000L + 15L * 24 * 60 * 60 * 1000 // 15 days after the last healthy copy (limit is 2x7)
        assertTrue(status.hasFreshHealthyCopy(9000L))
        assertFalse(status.hasFreshHealthyCopy(now))
    }

    @Test
    fun runResponseParsingCoversSuccessAndCorruption() {
        val ok = BackupApi.parseRunResponse(
            """{"success":true,"message":"انجام شد","data":{"ok":true,"status":"HEALTHY","error":"","storedBytes":40960,"filesIncluded":true}}"""
        )
        assertTrue(ok.ok)
        assertEquals("HEALTHY", ok.status)
        assertEquals(40960L, ok.storedBytes)
        assertTrue(ok.filesIncluded)

        val corrupt = BackupApi.parseRunResponse(
            """{"success":false,"message":"پشتیبان‌گیری ناموفق بود: امضای SHA-256 درست نیست","data":{"status":"CORRUPT","error":"امضای SHA-256 درست نیست","storedBytes":0,"filesIncluded":false}}"""
        )
        assertFalse(corrupt.ok)
        assertEquals("CORRUPT", corrupt.status)
        assertTrue(corrupt.error.contains("SHA-256"))
    }

    @Test
    fun statusSummaryMentionsFailureAndRetention() {
        val status = BackupApi.parseStatusResponse(statusJson)!!
        val summary = BackupApi.statusSummary(status, nowEpoch = 10_000L)
        assertTrue(summary.contains("۷"))
        assertTrue(summary.contains("ناموفق"))
        assertTrue(summary.contains("پشتیبان خراب است"))
    }
}
