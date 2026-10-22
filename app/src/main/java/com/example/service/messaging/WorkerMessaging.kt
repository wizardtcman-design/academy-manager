package com.example.service.messaging

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage 6 — the app-side model of the WORKER's messaging settings
 * (`/api/messaging/*`). JVM-pure so it is unit-testable without Android.
 *
 * The worker is the source of truth for reminder/receipt/welcome texts and
 * per-event auto/manual/channel policy. This file only parses/serializes:
 * it never stores credentials and never sends anything by itself.
 *
 * Channel truth: the worker reports SMS as NOT configured until a real SMS
 * panel is connected — the UI must reflect that honestly and must never
 * claim a real SMS was sent.
 */
object WorkerMessaging {

    // ------------------------------------------------------------
    // Models
    // ------------------------------------------------------------

    data class EventVariable(val name: String, val title: String, val kind: String, val required: Boolean)

    data class EventConfig(
        val key: String,
        val title: String,
        val auto: Boolean,
        val manual: Boolean,
        val channel: String,          // telegram | sms | both
        val defaultTemplate: String,
        val effectiveTemplate: String,
        val isOverridden: Boolean,
        val variables: List<EventVariable>
    )

    data class ChannelStatus(
        val telegramConfigured: Boolean,
        val telegramDetail: String,
        val smsConfigured: Boolean,
        val smsDetail: String,
        val gatewayConfigured: Boolean,
        val gatewayDetail: String
    )

    data class MessagingConfig(
        val events: List<EventConfig>,
        val overdueDailyEnabled: Boolean,
        val overdueForwardDays: Int,
        val sendHour: Int,
        val sendMinute: Int,
        val timezone: String,
        val currency: String,
        val channelStatus: ChannelStatus,
        val stoppedInstallments: List<String>
    )

    data class PreviewResult(
        val valid: Boolean,
        val errors: List<String>,
        val telegramHtml: String,
        val smsText: String
    )

    data class ChannelOutcome(val status: String, val reason: String, val messageId: String)

    data class SendOutcome(
        val ok: Boolean,
        val status: String,          // SENT | FAILED | BLOCKED | IN_FLIGHT
        val idempotent: Boolean,
        val channels: Map<String, ChannelOutcome>
    )

    data class ReminderRow(
        val id: String,
        val installmentId: String,
        val studentName: String,
        val type: String,
        val status: String,
        val scheduledTimeStr: String,
        val error: String,
        val triggerOffset: Int,
        val telegramStatus: String,
        val telegramError: String,
        val smsStatus: String,
        val smsError: String
    )

    data class RemindersView(
        val rows: List<ReminderRow>,
        val stoppedInstallments: Map<String, String>, // id -> reason (empty = active stop)
        val autoReminder: Boolean,
        val autoOverdueDaily: Boolean
    )

    // ------------------------------------------------------------
    // GET /api/messaging/config
    // ------------------------------------------------------------

    fun parseConfig(body: String): MessagingConfig? = runCatching {
        val json = JSONObject(body)
        if (!json.optBoolean("success", false)) return null
        val data = json.optJSONObject("data") ?: return null
        val eventsJson = data.optJSONObject("events") ?: return null
        val events = mutableListOf<EventConfig>()
        for (key in eventsJson.keys()) {
            val e = eventsJson.optJSONObject(key) ?: continue
            val vars = mutableListOf<EventVariable>()
            val varsJson = e.optJSONArray("variables") ?: JSONArray()
            for (i in 0 until varsJson.length()) {
                val v = varsJson.optJSONObject(i) ?: continue
                vars.add(
                    EventVariable(
                        name = v.optString("name"),
                        title = v.optString("title"),
                        kind = v.optString("kind", "text"),
                        required = v.optBoolean("required", false)
                    )
                )
            }
            events.add(
                EventConfig(
                    key = key,
                    title = e.optString("title", key),
                    auto = e.optBoolean("auto", true),
                    manual = e.optBoolean("manual", true),
                    channel = e.optString("channel", "telegram"),
                    defaultTemplate = e.optString("defaultTemplate", ""),
                    effectiveTemplate = e.optString("effectiveTemplate", ""),
                    isOverridden = e.optBoolean("isOverridden", false),
                    variables = vars
                )
            )
        }
        val overdue = data.optJSONObject("overdueDaily") ?: JSONObject()
        val sendTime = data.optJSONObject("sendTime") ?: JSONObject()
        val status = data.optJSONObject("channelStatus") ?: JSONObject()
        val telegram = status.optJSONObject("telegram") ?: JSONObject()
        val sms = status.optJSONObject("sms") ?: JSONObject()
        val gateway = status.optJSONObject("gateway") ?: JSONObject()
        val stopped = mutableListOf<String>()
        val stoppedJson = data.optJSONArray("stoppedInstallments") ?: JSONArray()
        for (i in 0 until stoppedJson.length()) stopped.add(stoppedJson.optString(i))
        MessagingConfig(
            events = events,
            overdueDailyEnabled = overdue.optBoolean("enabled", true),
            overdueForwardDays = overdue.optInt("forwardDays", 3),
            sendHour = sendTime.optInt("hour", 10),
            sendMinute = sendTime.optInt("minute", 0),
            timezone = sendTime.optString("timezone", "Asia/Tehran"),
            currency = data.optString("currency", "تومان"),
            channelStatus = ChannelStatus(
                telegramConfigured = telegram.optBoolean("configured", false),
                telegramDetail = telegram.optString("detail"),
                smsConfigured = sms.optBoolean("configured", false),
                smsDetail = sms.optString("detail"),
                gatewayConfigured = gateway.optBoolean("configured", false),
                gatewayDetail = gateway.optString("detail")
            ),
            stoppedInstallments = stopped
        )
    }.getOrNull()

    // ------------------------------------------------------------
    // PUT /api/messaging/config — the payload builder
    // ------------------------------------------------------------

    class ConfigPatch {
        internal val events = JSONObject()
        internal var overdueDaily: JSONObject? = null
        internal var sendHour: Int? = null
        internal var sendMinute: Int? = null
        internal var timezone: String? = null

        fun event(key: String, auto: Boolean? = null, manual: Boolean? = null, channel: String? = null, template: String? = null): ConfigPatch {
            val patch = JSONObject()
            if (auto != null) patch.put("auto", auto)
            if (manual != null) patch.put("manual", manual)
            if (channel != null) patch.put("channel", channel)
            if (template != null) patch.put("template", template)
            events.put(key, patch)
            return this
        }

        fun overdueDaily(enabled: Boolean? = null, forwardDays: Int? = null): ConfigPatch {
            val patch = JSONObject()
            if (enabled != null) patch.put("enabled", enabled)
            if (forwardDays != null) patch.put("forwardDays", forwardDays)
            overdueDaily = patch
            return this
        }

        fun sendTime(hour: Int? = null, minute: Int? = null, tz: String? = null): ConfigPatch {
            if (hour != null) sendHour = hour
            if (minute != null) sendMinute = minute
            if (tz != null) timezone = tz
            return this
        }

        fun toJson(): JSONObject {
            val root = JSONObject()
            root.put("events", events)
            overdueDaily?.let { root.put("overdueDaily", it) }
            sendHour?.let { root.put("sendHour", it) }
            sendMinute?.let { root.put("sendMinute", it) }
            timezone?.let { root.put("timezone", it) }
            return root
        }
    }

    fun configPatch(): ConfigPatch = ConfigPatch()

    /** Server-side validation errors of a refused PUT (success:false). */
    fun parseConfigErrors(body: String): List<String> = runCatching {
        val json = JSONObject(body)
        val errors = mutableListOf<String>()
        json.optJSONArray("errors")?.let { arr ->
            for (i in 0 until arr.length()) errors.add(arr.optString(i))
        }
        if (errors.isEmpty()) errors.add(json.optString("message", "خطای نامشخص"))
        errors
    }.getOrDefault(listOf("پاسخ سرور قابل خواندن نبود."))

    // ------------------------------------------------------------
    // POST /api/messaging/preview
    // ------------------------------------------------------------

    fun parsePreview(body: String): PreviewResult = runCatching {
        val json = JSONObject(body)
        val data = json.optJSONObject("data") ?: JSONObject()
        val errors = mutableListOf<String>()
        data.optJSONArray("errors")?.let { arr ->
            for (i in 0 until arr.length()) errors.add(arr.optString(i))
        }
        PreviewResult(
            valid = data.optBoolean("valid", false),
            errors = errors,
            telegramHtml = data.optString("telegramHtml", ""),
            smsText = data.optString("smsText", "")
        )
    }.getOrDefault(PreviewResult(valid = false, errors = listOf("پیش‌نمایش ساخته نشد."), telegramHtml = "", smsText = ""))

    // ------------------------------------------------------------
    // POST /api/messaging/send
    // ------------------------------------------------------------

    fun parseSendResponse(body: String): SendOutcome = runCatching {
        val json = JSONObject(body)
        val ok = json.optBoolean("success", false)
        val data = json.optJSONObject("data")
        val status = data?.optString("status", "") ?: ""
        val idempotent = data?.optBoolean("idempotent", false) ?: false
        val channelsJson = data?.optJSONObject("channels")
            ?: json.optJSONObject("channels")
            ?: JSONObject()
        val channels = mutableMapOf<String, ChannelOutcome>()
        for (key in channelsJson.keys()) {
            val c = channelsJson.optJSONObject(key) ?: continue
            channels[key] = ChannelOutcome(
                status = c.optString("status", ""),
                reason = c.optString("reason", ""),
                messageId = c.optString("messageId", "")
            )
        }
        SendOutcome(
            ok = ok,
            status = when {
                idempotent -> "SENT"
                status.isNotBlank() -> status
                ok -> "SENT"
                else -> "FAILED"
            },
            idempotent = idempotent,
            channels = channels
        )
    }.getOrDefault(SendOutcome(ok = false, status = "FAILED", idempotent = false, channels = emptyMap()))

    // ------------------------------------------------------------
    // GET /api/messaging/reminders
    // ------------------------------------------------------------

    fun parseRemindersView(body: String): RemindersView? = runCatching {
        val json = JSONObject(body)
        if (!json.optBoolean("success", false)) return null
        val data = json.optJSONObject("data") ?: return null
        val rowsJson = data.optJSONArray("reminders") ?: JSONArray()
        val rows = mutableListOf<ReminderRow>()
        for (i in 0 until rowsJson.length()) {
            val r = rowsJson.optJSONObject(i) ?: continue
            val deliveries = r.optJSONObject("deliveries") ?: JSONObject()
            val tg = deliveries.optJSONObject("telegram") ?: JSONObject()
            val sms = deliveries.optJSONObject("sms") ?: JSONObject()
            rows.add(
                ReminderRow(
                    id = r.optString("id"),
                    installmentId = r.optString("installmentId"),
                    studentName = r.optString("studentName"),
                    type = r.optString("type"),
                    status = r.optString("status"),
                    scheduledTimeStr = r.optString("scheduledTimeStr"),
                    error = r.optString("error"),
                    triggerOffset = r.optInt("triggerOffset", 0),
                    telegramStatus = tg.optString("status", ""),
                    telegramError = tg.optString("error", ""),
                    smsStatus = sms.optString("status", ""),
                    smsError = sms.optString("error", "")
                )
            )
        }
        val stoppedJson = data.optJSONObject("stoppedInstallments") ?: JSONObject()
        val stopped = mutableMapOf<String, String>()
        for (key in stoppedJson.keys()) {
            stopped[key] = stoppedJson.optJSONObject(key)?.optString("reason", "") ?: ""
        }
        val auto = data.optJSONObject("autoSending") ?: JSONObject()
        RemindersView(
            rows = rows,
            stoppedInstallments = stopped,
            autoReminder = auto.optBoolean("INSTALLMENT_REMINDER", true),
            autoOverdueDaily = auto.optBoolean("OVERDUE_DAILY", true)
        )
    }.getOrNull()

    /** Human-readable one-liner for a reminder row (Persian, RTL UI). */
    fun reminderRowLabel(row: ReminderRow): String {
        val typeLabel = when (row.triggerOffset) {
            -7 -> "۷ روز قبل"
            -4 -> "۴ روز قبل"
            -1 -> "۱ روز قبل"
            0 -> "روز سررسید"
            1 -> "۱ روز پس از سررسید"
            else -> if (row.triggerOffset > 1) "${row.triggerOffset} روز پس از سررسید" else row.type
        }
        return "$typeLabel — ${row.scheduledTimeStr}"
    }

    fun statusLabelFa(status: String): String = when (status.uppercase()) {
        "SENT", "SUCCESS" -> "ارسال‌شده"
        "SCHEDULED", "PENDING" -> "زمان‌بندی‌شده"
        "FAILED" -> "ناموفق"
        "CANCELLED" -> "لغوشده"
        "SKIPPED" -> "ردشده"
        "UNCONFIGURED" -> "پیکربندی‌نشده"
        "BLOCKED" -> "مسدود"
        "IN_FLIGHT" -> "در جریان"
        else -> status.ifBlank { "نامشخص" }
    }
}
