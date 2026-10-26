package com.example

import com.example.service.messaging.WorkerBotApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3 — the app-side Worker bot connection model:
 * - connect/status JSON parsing (real bot username extraction),
 * - Persian error mapping for 401/403/404/409/500/502,
 * - the token NEVER appears in any parsed result or error message.
 */
class WorkerBotApiTest {

    private val fakeToken = "123456789:ABCdefGHIjklMNOpqrsTUVwxyz012345"

    // ---------- token shape ----------

    @Test
    fun tokenShapeAcceptsBotFatherFormatAndRejectsGarbage() {
        assertTrue(WorkerBotApi.isTokenShapeValid(fakeToken))
        assertTrue(WorkerBotApi.isTokenShapeValid("  ${fakeToken.trim()}  "))
        assertFalse(WorkerBotApi.isTokenShapeValid("hello world"))
        assertFalse(WorkerBotApi.isTokenShapeValid("123456:short"))
        assertFalse(WorkerBotApi.isTokenShapeValid(""))
    }

    // ---------- connect response ----------

    @Test
    fun connectSuccessExtractsRealBotUsernameAndWebhookState() {
        val body = """
            {"success":true,
             "message":"ربات @MyAcademyBot متصل شد و وبهوک ثبت گردید.",
             "data":{"username":"MyAcademyBot","botName":"My Academy",
                     "webhookUrl":"https://worker.test/webhook/default","webhookConfirmed":true}}
        """.trimIndent()
        val result = WorkerBotApi.parseConnectResponse(body)
        assertTrue(result.ok)
        assertEquals("MyAcademyBot", result.username)
        assertEquals("My Academy", result.botName)
        assertTrue(result.webhookConfirmed)
        assertEquals("https://worker.test/webhook/default", result.webhookUrl)
    }

    @Test
    fun connectFailureKeepsServerMessageAndNeverContainsTheToken() {
        val body = """{"success":false,"message":"تلگرام این توکن را نپذیرفت: Unauthorized"}"""
        val result = WorkerBotApi.parseConnectResponse(body)
        assertFalse(result.ok)
        assertEquals("", result.username)
        assertFalse(result.message.contains(fakeToken))
    }

    @Test
    fun connectGarbageBodyIsHandledWithoutCrash() {
        val result = WorkerBotApi.parseConnectResponse("<html>502 Bad Gateway</html>")
        assertFalse(result.ok)
        assertTrue(result.message.isNotBlank())
    }

    // ---------- status response ----------

    @Test
    fun statusParsesConfiguredBotWithConfirmedWebhook() {
        val body = """
            {"success":true,"data":{
                "configured":true,"ok":true,"source":"academy",
                "tokenMasked":"••••2345","username":"MyAcademyBot","botName":"My Academy",
                "webhook":"https://worker.test/webhook/default",
                "expectedWebhook":"https://worker.test/webhook/default",
                "webhookConfirmed":true,"pendingUpdates":3,"lastError":""}}
        """.trimIndent()
        val status = WorkerBotApi.parseStatusResponse(body)!!
        assertTrue(status.configured)
        assertTrue(status.connected)
        assertEquals("academy", status.source)
        assertEquals("MyAcademyBot", status.username)
        assertTrue(status.webhookConfirmed)
        assertEquals(3, status.pendingUpdates)
        assertEquals("••••2345", status.tokenMasked)
        val summary = WorkerBotApi.statusSummary(status)
        assertTrue(summary.contains("@MyAcademyBot"))
        assertFalse(summary.contains(fakeToken))
    }

    @Test
    fun statusOfUnconnectedAcademyIsConfiguredFalseAndSummaryAsksForToken() {
        val body = """
            {"success":true,"data":{"configured":false,"ok":false,"source":"none",
                "username":"","webhook":"","expectedWebhook":"https://worker.test/webhook/default"}}
        """.trimIndent()
        val status = WorkerBotApi.parseStatusResponse(body)!!
        assertFalse(status.configured)
        assertFalse(status.connected)
        val summary = WorkerBotApi.statusSummary(status)
        assertTrue(summary.contains("متصل نیست"))
    }

    @Test
    fun statusGarbageReturnsNull() {
        assertNull(WorkerBotApi.parseStatusResponse("not json at all"))
        assertNull(WorkerBotApi.parseStatusResponse("""{"success":true}"""))
    }

    // ---------- problems diagnostics (server cause codes → Persian fix) ----------

    @Test
    fun statusParsesTheProblemsListFromTheServer() {
        val body = """
            {"success":true,"data":{"configured":true,"ok":false,"source":"academy",
                "username":"","webhook":"","expectedWebhook":"https://worker.test/webhook/default",
                "webhookConfirmed":false,"lastError":"token rejected by Telegram",
                "problems":["token_rejected"]}}
        """.trimIndent()
        val status = WorkerBotApi.parseStatusResponse(body)!!
        assertEquals(listOf("token_rejected"), status.problems)
        // an older Worker without the field still parses (backward compatible)
        val legacy = WorkerBotApi.parseStatusResponse(
            """{"success":true,"data":{"configured":false,"ok":false,"source":"none"}}"""
        )!!
        assertTrue(legacy.problems.isEmpty())
    }

    @Test
    fun diagnosisMapsEveryServerProblemToCauseAndSolution() {
        val noToken = WorkerBotApi.diagnose(
            WorkerBotApi.parseStatusResponse(
                """{"success":true,"data":{"configured":false,"ok":false,"source":"none","problems":["no_token"]}}"""
            )
        )
        assertFalse(noToken.ok)
        assertTrue(noToken.solution.contains("@BotFather"))

        val rejected = WorkerBotApi.diagnose(
            WorkerBotApi.parseStatusResponse(
                """{"success":true,"data":{"configured":false,"ok":false,"source":"academy",
                    "lastError":"token rejected by Telegram","problems":["token_rejected"]}}"""
            )
        )
        assertFalse(rejected.ok)
        assertTrue(rejected.title.contains("نپذیرفت"))
        assertTrue(rejected.solution.contains("/mybots"))

        val noHook = WorkerBotApi.diagnose(
            WorkerBotApi.parseStatusResponse(
                """{"success":true,"data":{"configured":true,"ok":true,"source":"academy","username":"diag_bot",
                    "problems":["webhook_not_set"]}}"""
            )
        )
        assertFalse(noHook.ok)
        assertTrue(noHook.solution.contains("بازیابی وبهوک"))

        val mismatch = WorkerBotApi.diagnose(
            WorkerBotApi.parseStatusResponse(
                """{"success":true,"data":{"configured":true,"ok":true,"source":"academy","username":"diag_bot",
                    "webhook":"https://other.example/webhook/default",
                    "expectedWebhook":"https://worker.test/webhook/default",
                    "webhookConfirmed":false,"problems":["webhook_mismatch"]}}"""
            )
        )
        assertFalse(mismatch.ok)
        assertTrue(mismatch.cause.contains("https://other.example"))

        val healthy = WorkerBotApi.diagnose(
            WorkerBotApi.parseStatusResponse(
                """{"success":true,"data":{"configured":true,"ok":true,"source":"academy","username":"diag_bot",
                    "webhook":"https://worker.test/webhook/default",
                    "expectedWebhook":"https://worker.test/webhook/default",
                    "webhookConfirmed":true,"problems":[]}}"""
            )
        )
        assertTrue(healthy.ok)
    }

    @Test
    fun diagnosisOfUnparseableStatusPointsAtUrlAndKey() {
        val d = WorkerBotApi.diagnose(null)
        assertFalse(d.ok)
        assertTrue(d.solution.contains("آزمایش اتصال"))
    }

    // ---------- Persian error mapping ----------

    @Test
    fun unauthorized401ExplainsTheApiSecret() {
        val msg = WorkerBotApi.friendlyError(401, "دسترسی غیرمجاز.")
        assertTrue(msg.contains("کلید API"))
        assertTrue(msg.contains("دسترسی غیرمجاز"))
    }

    @Test
    fun forbidden403ExplainsTheAcademyId() {
        val msg = WorkerBotApi.friendlyError(403, null)
        assertTrue(msg.contains("آموزشگاه"))
    }

    @Test
    fun conflict409KeepsTheServerReasonAboutTheOtherAcademy() {
        val msg = WorkerBotApi.friendlyError(409, "این ربات قبلاً به آموزشگاه دیگری متصل شده است.")
        assertEquals("این ربات قبلاً به آموزشگاه دیگری متصل شده است.", msg)
    }

    @Test
    fun badGateway502ExplainsTheTelegramReachabilityProblem() {
        val msg = WorkerBotApi.friendlyError(502, "ثبت وبهوک ناموفق بود")
        assertTrue(msg.contains("تلگرام"))
        assertTrue(msg.contains("ثبت وبهوک ناموفق بود"))
    }

    @Test
    fun serverError500IsReportedAsInternalWithDetail() {
        val msg = WorkerBotApi.friendlyError(500, "جدول ربات‌ها آماده نیست")
        assertTrue(msg.contains("خطای داخلی سرور"))
        assertTrue(msg.contains("جدول ربات‌ها آماده نیست"))
    }

    @Test
    fun unknownCodeFallsBackToPersianNetworkMessage() {
        val msg = WorkerBotApi.friendlyError(418, "")
        assertTrue(msg.contains("418"))
        assertTrue(WorkerBotApi.friendlyNetworkError("timeout").contains("برقرار نشد"))
    }

    // ---------- academy id ----------

    @Test
    fun academyIdIsCentralizedAndNeverBlank() {
        assertEquals("default", WorkerBotApi.ACADEMY_ID)
        assertNotEquals("", WorkerBotApi.ACADEMY_ID)
    }
}
