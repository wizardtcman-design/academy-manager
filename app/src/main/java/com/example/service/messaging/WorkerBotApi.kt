package com.example.service.messaging

import org.json.JSONObject

/**
 * Stage 3 — the app-side model of the Worker's per-academy bot connection.
 *
 * The bot token itself NEVER lives here: it is typed once, sent to the
 * Worker over HTTPS (/api/bot/connect) and stored in the academy's D1 row.
 * Everything this file parses back comes from the Worker and is guaranteed
 * to be token-free (the Worker only ever returns a masked tail).
 *
 * Kept 100% JVM-pure so the parsing and the Persian error mapping are
 * unit-testable without an Android device.
 */
object WorkerBotApi {

    /**
     * The academy this app talks to. One deployment currently serves one
     * academy; the value is centralized here so every request carries the
     * SAME tenant id and isolation cannot drift between call sites.
     */
    const val ACADEMY_ID = "default"

    /** Bot tokens look like 123456789:ABC... — enough to detect a paste mistake. */
    private val TOKEN_SHAPE = Regex("^\\d{6,12}:[A-Za-z0-9_-]{30,}$")

    fun isTokenShapeValid(token: String): Boolean = TOKEN_SHAPE.matches(token.trim())

    // ------------------------------------------------------------------
    // /api/bot/connect response
    // ------------------------------------------------------------------

    data class ConnectResult(
        val ok: Boolean,
        val username: String,
        val botName: String,
        val webhookUrl: String,
        val webhookConfirmed: Boolean,
        val message: String
    )

    /** Parses the Worker's connect reply; extracts the REAL bot username. */
    fun parseConnectResponse(body: String): ConnectResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return ConnectResult(false, "", "", "", false, "پاسخ سرور قابل خواندن نبود.")
        val data = json.optJSONObject("data") ?: JSONObject()
        return ConnectResult(
            ok = json.optBoolean("success", false),
            username = data.optString("username", "").removePrefix("@").trim(),
            botName = data.optString("botName", "").trim(),
            webhookUrl = data.optString("webhookUrl", "").trim(),
            webhookConfirmed = data.optBoolean("webhookConfirmed", false),
            message = json.optString("message", "").trim()
        )
    }

    // ------------------------------------------------------------------
    // /api/bot/status response
    // ------------------------------------------------------------------

    data class WorkerBotStatus(
        val configured: Boolean,
        val source: String,          // academy | server | none
        val username: String,
        val botName: String,
        val webhook: String,         // what Telegram currently has
        val expectedWebhook: String, // what the Worker expects
        val webhookConfirmed: Boolean,
        val pendingUpdates: Int,
        val lastError: String,
        val tokenMasked: String
    ) {
        val connected: Boolean get() = configured && username.isNotBlank()
    }

    fun parseStatusResponse(body: String): WorkerBotStatus? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val data = json.optJSONObject("data") ?: return null
        return WorkerBotStatus(
            configured = data.optBoolean("configured", false),
            source = data.optString("source", "none"),
            username = data.optString("username", "").removePrefix("@").trim(),
            botName = data.optString("botName", "").trim(),
            webhook = data.optString("webhook", "").trim(),
            expectedWebhook = data.optString("expectedWebhook", "").trim(),
            webhookConfirmed = data.optBoolean("webhookConfirmed", false),
            pendingUpdates = if (data.isNull("pendingUpdates")) -1 else data.optInt("pendingUpdates", -1),
            lastError = data.optString("lastError", "").trim(),
            tokenMasked = data.optString("tokenMasked", "").trim()
        )
    }

    /** One-line Persian summary for the settings screen. */
    fun statusSummary(status: WorkerBotStatus): String = buildString {
        if (!status.configured) {
            append("ربات متصل نیست. توکن ربات را وارد و دکمهٔ اتصال را بزنید.")
            return@buildString
        }
        append("ربات @${status.username} متصل است")
        append(if (status.webhookConfirmed) " و وبهوک سالم است ✅" else " اما وبهوک ثبت نشده است ⚠️")
        if (status.lastError.isNotBlank()) append("\nآخرین خطای تلگرام: ${status.lastError}")
    }

    // ------------------------------------------------------------------
    // Persian error mapping (401 / 403 / 404 / 409 / 500 / 502)
    // ------------------------------------------------------------------

    /** Server text when it is useful, otherwise the safe fallback. */
    private fun pick(serverMessage: String, fallback: String): String =
        if (serverMessage.isNotBlank()) serverMessage else fallback

    /**
     * Human-readable Persian message for a failed Worker call.
     * `serverMessage` may be shown when it is already Persian and useful;
     * the mapping guarantees the manager never sees a raw English stack.
     */
    fun friendlyError(httpCode: Int, serverMessage: String?): String {
        val server = serverMessage?.trim().orEmpty()
        return when (httpCode) {
            400 -> pick(server, "درخواست نامعتبر است. توکن ربات را از @BotFather کامل و دقیق کپی کنید.")
            401 -> pick("کلید API سرور نادرست است. «کلید احراز هویت API سرور» را با API Secret Key روی Cloudflare مقایسه کنید. ($server)",
                "کلید API سرور نادرست است. مقدار API Secret Key را بررسی کنید.")
            403 -> if (server.contains("آموزشگاه")) server
                else "این شناسه آموزشگاه روی سرور مجاز نیست. شناسه آموزشگاه (X-Academy-Id) را بررسی کنید."
            404 -> "آدرس سرور یا مسیر API اشتباه است. آدرس کامل Cloudflare Worker (workers.dev) را بررسی کنید."
            409 -> pick(server, "ربات این آموزشگاه متصل نیست یا این ربات قبلاً به آموزشگاه دیگری متصل شده است.")
            429 -> "تعداد درخواست‌ها زیاد است؛ چند لحظه بعد دوباره تلاش کنید."
            500 -> pick("خطای داخلی سرور: $server", "خطای داخلی سرور Cloudflare. لاگ Worker را بررسی کنید.")
            502 -> pick("سرور نتوانست با تلگرام ارتباط برقرار کند: $server",
                "سرور نتوانست با تلگرام ارتباط برقرار کند. اتصال اینترنت سرور را بررسی کنید.")
            else -> pick(server, "خطای ارتباط با سرور Cloudflare (کد $httpCode).")
        }
    }

    /** Network/exception path — no HTTP code exists. */
    fun friendlyNetworkError(detail: String?): String =
        "ارتباط با سرور Cloudflare برقرار نشد. آدرس Worker و اینترنت دستگاه را بررسی کنید.${detail?.let { " ($it)" } ?: ""}"
}
