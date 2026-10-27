package com.example

import com.example.service.contract.ContractCloudClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the test-data cleanup client contract:
 * payload shape (never leaks names/phones — ids + confirm only), parsing of
 * the server preview/purge answers, and Persian label fallbacks.
 */
class TestDataCleanupClientTest {

    @Test
    fun payloadCarriesOnlyExplicitIdsAndConfirm() {
        val payload = ContractCloudClient.buildTestDataPayload(listOf("stu-1", "stu-2"), confirm = true)
        assertEquals(listOf("stu-1", "stu-2"), payload.optJSONArray("studentIds")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        })
        assertEquals(true, payload.optBoolean("confirm"))
        // No names, no phones, no tokens: the request is id-only by design.
        assertEquals(setOf("studentIds", "confirm"), payload.keys().asSequence().toSet())
        assertFalse(payload.toString().contains("0912"))
    }

    @Test
    fun previewAnswerIsParsedIntoCountsAndStudents() {
        val data = JSONObject(
            """
            {
              "mode": "preview",
              "students": [
                {"id": "stu-1", "fullName": "آزمایشی", "studentCode": "100",
                 "phone": "09120000001", "isTelegramConnected": true, "contracts": 2}
              ],
              "unknownIds": ["stu-x"],
              "counts": {"contracts": 2, "installments": 3, "messageLogsUnscoped": 1},
              "labels": {"contracts": "قراردادها"},
              "fileBytes": 2048,
              "totalRecords": 7
            }
            """.trimIndent()
        )
        val parsed = ContractCloudClient.parseTestDataCounts(data)
        assertEquals(3L, parsed.counts["installments"])
        assertEquals(1L, parsed.counts["messageLogsUnscoped"])
        assertEquals(2048L, parsed.fileBytes)
        assertEquals(7L, parsed.totalRecords)
        assertEquals(listOf("stu-x"), parsed.unknownIds)
        assertEquals(1, parsed.students.size)
        with(parsed.students[0]) {
            assertEquals("stu-1", id)
            assertEquals("آزمایشی", fullName)
            assertTrue(isTelegramConnected)
            assertEquals(2L, contracts)
        }
        // Empty/null data never throws.
        assertEquals(0L, ContractCloudClient.parseTestDataCounts(null).totalRecords)
    }

    @Test
    fun purgeAnswerIsParsedWithRemainingCheck() {
        val data = JSONObject(
            """
            {
              "mode": "purge",
              "students": [{"id": "stu-1", "fullName": "آزمایشی"}],
              "deleted": {"contracts": 2, "students": 1, "installments": 3},
              "deletedTotal": 6,
              "fileBytesFreed": 1024,
              "remainingClean": true
            }
            """.trimIndent()
        )
        val parsed = ContractCloudClient.parseTestDataPurge(data)
        assertEquals(6L, parsed.deletedTotal)
        assertEquals(3L, parsed.deleted["installments"])
        assertTrue(parsed.remainingClean)
        assertEquals(listOf("آزمایشی"), parsed.students)
        // Missing data degrades to "not clean", never a false success.
        assertFalse(ContractCloudClient.parseTestDataPurge(null).remainingClean)
    }

    @Test
    fun labelsFallBackToPersianWhenServerLabelMissing() {
        assertEquals("اقساط", ContractCloudClient.countLabelFa("installments", emptyMap()))
        assertEquals("قراردادها", ContractCloudClient.countLabelFa("contracts", mapOf("contracts" to "قراردادها")))
        assertEquals("mystery_key", ContractCloudClient.countLabelFa("mystery_key", emptyMap()))
    }
}
