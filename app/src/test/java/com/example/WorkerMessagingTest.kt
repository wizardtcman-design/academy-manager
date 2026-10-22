package com.example

import com.example.service.messaging.WorkerMessaging
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 6 — the app-side model of the worker's messaging settings:
 * - config parsing (per-event policy, REAL channel status, stopped list),
 * - the SMS channel must read as NOT configured (no panel in this stage),
 * - config patch serialization for PUT,
 * - preview + manual-send response parsing (idempotency preserved),
 * - per-installment reminder view parsing (stop/resume UI data),
 * - credentials never appear in any parsed value.
 */
class WorkerMessagingTest {

    private val configBody = """
        {"success":true,"message":"ok","data":{
          "version":1,
          "events":{
            "INSTALLMENT_REMINDER":{"title":"یادآوری اقساط","auto":true,"manual":true,"channel":"telegram",
              "defaultTemplate":"{nobHead} {studentName}","effectiveTemplate":"قسط {installmentLine} یادت نره",
              "isOverridden":true,
              "variables":[{"name":"studentName","title":"نام هنرجو","kind":"text","required":true},
                           {"name":"amount","title":"مبلغ","kind":"money","required":true}]},
            "OVERDUE_DAILY":{"title":"یادآوری روزانه","auto":true,"manual":true,"channel":"both",
              "defaultTemplate":"x","effectiveTemplate":"x","isOverridden":false,"variables":[]}
          },
          "overdueDaily":{"enabled":true,"forwardDays":3},
          "sendTime":{"hour":9,"minute":30,"timezone":"Asia/Tehran"},
          "currency":"تومان",
          "channelStatus":{
            "telegram":{"configured":true,"detail":"ربات متصل است."},
            "sms":{"configured":false,"detail":"پنل پیامکی متصل نیست؛ ارسال پیامک غیرفعال است."},
            "gateway":{"configured":false,"detail":"درگاه پرداخت آنلاین متصل نیست؛ لینک پرداخت صادر نمی‌شود."}},
          "stoppedInstallments":["ins-9"]
        }}
    """.trimIndent()

    @Test
    fun configParsingExtractsEventsPolicyAndRealChannelStatus() {
        val cfg = WorkerMessaging.parseConfig(configBody)
        assertNotNull(cfg)
        cfg!!
        assertEquals(2, cfg.events.size)
        val reminder = cfg.events.first { it.key == "INSTALLMENT_REMINDER" }
        assertTrue(reminder.isOverridden)
        assertTrue(reminder.auto)
        assertEquals("telegram", reminder.channel)
        assertEquals("studentName", reminder.variables.first().name)
        assertTrue(reminder.variables.first().required)

        assertTrue(cfg.overdueDailyEnabled)
        assertEquals(3, cfg.overdueForwardDays)
        assertEquals(9, cfg.sendHour)
        assertEquals(30, cfg.sendMinute)
        assertEquals("Asia/Tehran", cfg.timezone)
        assertEquals(listOf("ins-9"), cfg.stoppedInstallments)
    }

    @Test
    fun smsChannelIsNeverReportedAsConfiguredInThisStage() {
        val cfg = WorkerMessaging.parseConfig(configBody)!!
        assertFalse(cfg.channelStatus.smsConfigured)
        assertTrue(cfg.channelStatus.telegramConfigured)
        assertFalse(cfg.channelStatus.gatewayConfigured)
        // the UI copy must not pretend SMS works:
        assertTrue(cfg.channelStatus.smsDetail.contains("متصل نیست"))
    }

    @Test
    fun invalidConfigBodyYieldsNullNotACrash() {
        assertNull(WorkerMessaging.parseConfig("not json at all"))
        assertNull(WorkerMessaging.parseConfig("""{"success":false}"""))
        assertNull(WorkerMessaging.parseConfig("""{"success":true}"""))
    }

    @Test
    fun configPatchSerializesEveryFieldTheWorkerValidates() {
        val payload = WorkerMessaging.configPatch()
            .event("INSTALLMENT_REMINDER", auto = false, manual = true, channel = "both", template = "سلام {studentName}")
            .event("OVERDUE_DAILY", auto = true)
            .overdueDaily(enabled = false, forwardDays = 2)
            .sendTime(hour = 8, minute = 15, tz = "Asia/Tehran")
            .toJson()
        assertEquals(false, payload.getJSONObject("events").getJSONObject("INSTALLMENT_REMINDER").getBoolean("auto"))
        assertEquals("both", payload.getJSONObject("events").getJSONObject("INSTALLMENT_REMINDER").getString("channel"))
        assertEquals(true, payload.getJSONObject("events").getJSONObject("OVERDUE_DAILY").getBoolean("auto"))
        assertEquals(false, payload.getJSONObject("overdueDaily").getBoolean("enabled"))
        assertEquals(2, payload.getJSONObject("overdueDaily").getInt("forwardDays"))
        assertEquals(8, payload.getInt("sendHour"))
        assertEquals(15, payload.getInt("sendMinute"))
        assertEquals("Asia/Tehran", payload.getString("timezone"))
    }

    @Test
    fun configErrorsAreExtractedForTheManager() {
        val body = """{"success":false,"message":"ذخیره نشد","errors":["متغیر ناشناخته: {nonsenseVar}"]}"""
        val errors = WorkerMessaging.parseConfigErrors(body)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("nonsenseVar"))
        // a body without an errors array still yields the server message
        val fallback = WorkerMessaging.parseConfigErrors("""{"success":false,"message":"خطای سرور"}""")
        assertEquals("خطای سرور", fallback.first())
    }

    @Test
    fun previewParsingCarriesSampleRenderAndErrors() {
        val ok = WorkerMessaging.parsePreview(
            """{"success":true,"data":{"valid":true,"errors":[],
                "telegramHtml":"🔔 یادآوری قسط — هنرجو نمونه","smsText":"یادآوری — هنرجو نمونه"}}"""
        )
        assertTrue(ok.valid)
        assertTrue(ok.telegramHtml.contains("هنرجو نمونه"))
        assertEquals(emptyList<String>(), ok.errors)

        val invalid = WorkerMessaging.parsePreview(
            """{"success":true,"data":{"valid":false,"errors":["متغیر ناشناخته: {x}"],"telegramHtml":"","smsText":""}}"""
        )
        assertFalse(invalid.valid)
        assertTrue(invalid.errors.first().contains("x"))
    }

    @Test
    fun sendResponseParsingKeepsIdempotencyAndPerChannelStatus() {
        val sent = WorkerMessaging.parseSendResponse(
            """{"success":true,"message":"ارسال شد","data":{"status":"SENT",
                "channels":{"telegram":{"status":"SENT","messageId":"42"},
                            "sms":{"status":"UNCONFIGURED","reason":"پنل پیامکی متصل نیست"}}}}"""
        )
        assertEquals("SENT", sent.status)
        assertFalse(sent.idempotent)
        assertEquals("SENT", sent.channels["telegram"]?.status)
        assertEquals("42", sent.channels["telegram"]?.messageId)
        assertEquals("UNCONFIGURED", sent.channels["sms"]?.status)
        assertTrue(sent.channels["sms"]?.reason!!.contains("پنل پیامکی"))

        val idempotent = WorkerMessaging.parseSendResponse(
            """{"success":true,"message":"قبلاً ارسال شده بود","data":{"status":"SENT","idempotent":true,
                "channels":{"telegram":{"status":"SENT"}}}}"""
        )
        assertTrue(idempotent.idempotent)
        assertEquals("SENT", idempotent.status)

        val inFlight = WorkerMessaging.parseSendResponse(
            """{"success":true,"message":"در جریان","data":{"status":"IN_FLIGHT","channels":{"telegram":{"status":"PENDING"}}}}"""
        )
        assertEquals("IN_FLIGHT", inFlight.status)

        val blocked = WorkerMessaging.parseSendResponse(
            """{"success":false,"message":"ارسال دستی مجاز نیست","status":"BLOCKED",
                "channels":{"telegram":{"status":"BLOCKED","reason":"manual_send_disabled_by_settings"}},"errorCode":403}"""
        )
        assertEquals("BLOCKED", blocked.status)
        assertEquals("BLOCKED", blocked.channels["telegram"]?.status)
    }

    @Test
    fun remindersViewParsingExposesStopStateAndChannelStatuses() {
        val body = """
            {"success":true,"message":"ok","data":{
              "reminders":[
                {"id":"auto:ins-1:-1","installmentId":"ins-1","studentName":"هنرجو","type":"INSTALLMENT_UPCOMING",
                 "status":"SCHEDULED","scheduledTimeStr":"10:00 ۱۴۰۵/۰۸/۰۱","error":"","triggerOffset":-1,
                 "deliveries":{"telegram":{"status":"PENDING","error":""},"sms":{"status":"SKIPPED","error":"sms_provider_not_configured"}}},
                {"id":"auto:ins-2:3","installmentId":"ins-2","studentName":"هنرجو","type":"INSTALLMENT_OVERDUE_DAILY",
                 "status":"SENT","scheduledTimeStr":"10:00 ۱۴۰۵/۰۷/۲۸","error":"","triggerOffset":3,"deliveries":{}}
              ],
              "stoppedInstallments":{"ins-2":{"reason":"قول پرداخت حضوری","actor":"manager-api","at":1}},
              "autoSending":{"INSTALLMENT_REMINDER":true,"OVERDUE_DAILY":false}
            }}
        """.trimIndent()
        val view = WorkerMessaging.parseRemindersView(body)
        assertNotNull(view)
        view!!
        assertEquals(2, view.rows.size)
        assertEquals("SCHEDULED", view.rows[0].status)
        assertEquals("SKIPPED", view.rows[0].smsStatus)
        assertEquals(3, view.rows[1].triggerOffset)
        assertEquals("قول پرداخت حضوری", view.stoppedInstallments["ins-2"])
        assertTrue(view.autoReminder)
        assertFalse(view.autoOverdueDaily)

        // Persian label of a daily row shows the real day count
        val label = WorkerMessaging.reminderRowLabel(view.rows[1])
        assertTrue(label.contains("۳ روز پس از سررسید"))
        assertEquals("پیکربندی‌نشده", WorkerMessaging.statusLabelFa("UNCONFIGURED"))
        assertEquals("ارسال‌شده", WorkerMessaging.statusLabelFa("SENT"))
    }

    @Test
    fun noCredentialEverAppearsInParsedValues() {
        val secret = "super-secret-api-key-123"
        val body = configBody + secret // injected anywhere in the payload
        val cfg = WorkerMessaging.parseConfig(body)!!
        val dumped = cfg.toString()
        assertFalse(dumped.contains(secret))
        val patch = WorkerMessaging.configPatch().event("OVERDUE_DAILY", template = "x").toJson().toString()
        assertFalse(patch.contains(secret))
    }
}
