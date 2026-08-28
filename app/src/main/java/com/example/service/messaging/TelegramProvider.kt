package com.example.service.messaging

import android.content.Context
import android.util.Log
import com.example.data.model.AcademySettings
import com.example.data.model.AuditLog
import com.example.data.model.Student
import com.example.data.repository.AcademyRepository
import com.example.data.util.PdfDocumentHelper
import com.example.data.util.PersianDateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class TelegramPollResult(
    val isSuccess: Boolean,
    val newConnectionsCount: Int = 0,
    val failedConnectionsCount: Int = 0,
    val totalProcessedUpdates: Int = 0,
    val message: String = ""
)

class TelegramProvider(
    private var context: Context? = null,
    private var settings: AcademySettings? = null
) : MessagingProvider {

    private val tag = "TelegramProvider"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // Persistent offset for getUpdates polling
    private var lastUpdateOffset: Long = 0L

    override val channelName: String = "TELEGRAM"

    override val isEnabled: Boolean
        get() = settings?.telegramEnabled == true

    override val isConfigured: Boolean
        get() {
            val s = settings ?: return false
            return if (s.telegramUseWorker) {
                s.telegramWorkerUrl.isNotBlank()
            } else {
                s.telegramBotToken.isNotBlank()
            }
        }

    fun updateSettings(newSettings: AcademySettings?) {
        this.settings = newSettings
    }

    fun updateContext(newContext: Context) {
        this.context = newContext
    }

    override suspend fun sendMessage(
        student: Student,
        messageText: String
    ): MessageSendResult = withContext(Dispatchers.IO) {
        val s = settings
        if (s == null) {
            return@withContext MessageSendResult.Failed("تنظیمات تلگرام لود نشده است.")
        }

        if (!student.isTelegramConnected || student.telegramChatId.isBlank()) {
            return@withContext MessageSendResult.NotConnected(
                "این هنرجو هنوز ربات تلگرام آموزشگاه را متصل (Start) نکرده است."
            )
        }

        // If Cloudflare Worker backend is enabled
        if (s.telegramUseWorker && s.telegramWorkerUrl.isNotBlank()) {
            val (ok, desc) = sendMessageViaCloudflareWorker(
                workerUrl = s.telegramWorkerUrl,
                apiSecret = s.telegramApiSecret,
                chatId = student.telegramChatId,
                studentId = student.id,
                studentName = student.fullName,
                text = messageText
            )
            return@withContext if (ok) {
                MessageSendResult.Success(UUID.randomUUID().toString(), desc)
            } else {
                MessageSendResult.Failed(desc)
            }
        }

        // Direct Telegram Bot API
        val botToken = s.telegramBotToken.trim()
        if (botToken.isBlank()) {
            return@withContext MessageSendResult.NotConnected(
                "توکن ربات تلگرام یا آدرس سرور Cloudflare در تنظیمات آموزشگاه ثبت نشده است."
            )
        }

        val (ok, desc) = sendMessageToChatInternal(
            botToken = botToken,
            chatId = student.telegramChatId,
            text = messageText,
            parseMode = "HTML",
            replyMarkup = getStudentMenuReplyMarkupJson()
        )
        if (ok) {
            MessageSendResult.Success(UUID.randomUUID().toString(), "پیام با موفقیت از طریق ربات تلگرام ارسال شد.")
        } else {
            MessageSendResult.Failed(desc)
        }
    }

    /**
     * Sends a direct text message to a specific Telegram Chat ID
     */
    suspend fun sendMessageToChat(
        chatId: String,
        text: String,
        parseMode: String = "HTML",
        withMenu: Boolean = true
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val botToken = settings?.telegramBotToken?.trim() ?: ""
        if (botToken.isBlank()) {
            return@withContext Pair(false, "توکن ربات تلگرام تنظیم نشده است.")
        }
        val replyMarkup = if (withMenu) getStudentMenuReplyMarkupJson() else null
        sendMessageToChatInternal(botToken, chatId, text, parseMode, replyMarkup)
    }

    /**
     * Sends an official PDF document directly to the Telegram Chat
     */
    suspend fun sendDocumentToChat(
        chatId: String,
        file: File,
        caption: String = "",
        customFileName: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val botToken = settings?.telegramBotToken?.trim() ?: ""
        if (botToken.isBlank()) {
            return@withContext Pair(false, "توکن ربات تلگرام تنظیم نشده است.")
        }
        sendDocumentToChatInternal(botToken, chatId, file, caption, customFileName)
    }

    /**
     * Generates standard Telegram Reply Keyboard JSON with real interactive student buttons
     */
    fun getStudentMenuReplyMarkupJson(): String {
        val keyboard = JSONArray().apply {
            // Row 1
            put(JSONArray().apply {
                put(JSONObject().put("text", "💰 وضعیت مالی"))
                put(JSONObject().put("text", "📅 اقساط من"))
            })
            // Row 2
            put(JSONArray().apply {
                put(JSONObject().put("text", "🧾 رسیدهای من"))
                put(JSONObject().put("text", "📄 قرارداد من"))
            })
            // Row 3
            put(JSONArray().apply {
                put(JSONObject().put("text", "📞 تماس با آموزشگاه"))
                put(JSONObject().put("text", "🔄 بروزرسانی اطلاعات"))
            })
        }

        return JSONObject().apply {
            put("keyboard", keyboard)
            put("resize_keyboard", true)
            put("is_persistent", true)
        }.toString()
    }

    private fun sendMessageToChatInternal(
        botToken: String,
        chatId: String,
        text: String,
        parseMode: String = "HTML",
        replyMarkup: String? = null
    ): Pair<Boolean, String> {
        return try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val formBuilder = FormBody.Builder()
                .add("chat_id", chatId.trim())
                .add("text", text)

            if (parseMode.isNotBlank()) {
                formBuilder.add("parse_mode", parseMode)
            }
            if (!replyMarkup.isNullOrBlank()) {
                formBuilder.add("reply_markup", replyMarkup)
            }

            val request = Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val ok = json.optBoolean("ok", false)
                    if (ok) {
                        Pair(true, "ارسال شد")
                    } else {
                        val desc = json.optString("description", "خطای تلگرام")
                        // If HTML parsing failed due to unexpected characters, fallback to plain text
                        if (desc.contains("can't parse entities", ignoreCase = true) && parseMode == "HTML") {
                            return sendMessageToChatInternal(botToken, chatId, stripHtml(text), parseMode = "", replyMarkup = replyMarkup)
                        }
                        Pair(false, desc)
                    }
                } else {
                    Pair(false, "خطای سرور تلگرام (کد ${response.code})")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to send telegram message to $chatId", e)
            Pair(false, "خطای شبکه: ${e.localizedMessage ?: "عدم اتصال"}")
        }
    }

    private fun sendDocumentToChatInternal(
        botToken: String,
        chatId: String,
        file: File,
        caption: String = "",
        customFileName: String? = null,
        parseMode: String = "HTML"
    ): Pair<Boolean, String> {
        return try {
            if (!file.exists() || file.length() == 0L) {
                return Pair(false, "فایل سندی برای ارسال یافت نشد.")
            }

            val url = "https://api.telegram.org/bot$botToken/sendDocument"
            val mediaType = "application/pdf".toMediaTypeOrNull()
            val fileBody = file.asRequestBody(mediaType)
            val nameToUse = customFileName ?: file.name

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.trim())
                .addFormDataPart("document", nameToUse, fileBody)

            if (caption.isNotBlank()) {
                multipartBuilder.addFormDataPart("caption", caption)
                if (parseMode.isNotBlank()) {
                    multipartBuilder.addFormDataPart("parse_mode", parseMode)
                }
            }

            val request = Request.Builder()
                .url(url)
                .post(multipartBuilder.build())
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val ok = json.optBoolean("ok", false)
                    if (ok) {
                        Pair(true, "فایل با موفقیت ارسال شد")
                    } else {
                        val desc = json.optString("description", "خطای ارسال فایل")
                        if (desc.contains("can't parse entities", ignoreCase = true) && parseMode == "HTML") {
                            return sendDocumentToChatInternal(botToken, chatId, file, stripHtml(caption), customFileName, parseMode = "")
                        }
                        Pair(false, desc)
                    }
                } else {
                    Pair(false, "خطای سرور تلگرام در ارسال فایل (کد ${response.code})")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to send document to $chatId", e)
            Pair(false, "خطای شبکه در ارسال فایل: ${e.localizedMessage ?: "عدم ارتباط"}")
        }
    }

    private fun stripHtml(input: String): String {
        return input.replace(Regex("<[^>]*>"), "")
    }

    /**
     * Tests Cloudflare Worker API connection and server-side Telegram bot status
     */
    suspend fun testCloudflareWorkerConnection(workerUrl: String, apiSecret: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = workerUrl.trim().removeSuffix("/")
        if (cleanUrl.isBlank()) {
            return@withContext Pair(false, "آدرس Cloudflare Worker وارد نشده است.")
        }
        try {
            val endpoint = "$cleanUrl/api/test-connection"
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (apiSecret.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer ${apiSecret.trim()}")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                val json = try {
                    if (body.isNotBlank()) JSONObject(body) else null
                } catch (_: Exception) {
                    null
                }

                val isSuccess = json?.optBoolean("success", false) == true
                val message = json?.optString("message", "") ?: ""

                if (response.isSuccessful && isSuccess) {
                    val data = json?.optJSONObject("data")
                    val username = data?.optString("username", "") ?: ""
                    Pair(true, message.ifBlank { "اتصال به Cloudflare Worker و سرور تلگرام با موفقیت تایید شد!" })
                } else {
                    val errorCode = json?.optInt("errorCode", response.code) ?: response.code
                    val errorMsg = if (message.isNotBlank()) {
                        "خطای سرور ($errorCode): $message"
                    } else if (body.isNotBlank()) {
                        "پاسخ سرور ($errorCode): $body"
                    } else {
                        "خطای ارتباط با سرور Cloudflare ($errorCode)"
                    }
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Pair(false, "خطا در برقراری ارتباط با Cloudflare Worker: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * Sends a message via Cloudflare Worker HTTPS REST API
     */
    suspend fun sendMessageViaCloudflareWorker(
        workerUrl: String,
        apiSecret: String,
        chatId: String,
        studentId: String = "",
        studentName: String = "",
        text: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = workerUrl.trim().removeSuffix("/")
        if (cleanUrl.isBlank()) {
            return@withContext Pair(false, "آدرس سرور Cloudflare تنظیم نشده است.")
        }
        try {
            val endpoint = "$cleanUrl/api/send-message"
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("studentId", studentId)
                put("studentName", studentName)
                put("message", text)
                put("parseMode", "HTML")
                put("withMenu", true)
            }

            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (apiSecret.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer ${apiSecret.trim()}")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                val json = try {
                    if (body.isNotBlank()) JSONObject(body) else null
                } catch (_: Exception) {
                    null
                }

                val isSuccess = json?.optBoolean("success", false) == true
                val message = json?.optString("message", "") ?: ""

                if (response.isSuccessful && isSuccess) {
                    Pair(true, message.ifBlank { "پیام با موفقیت از طریق سرور ارسال شد." })
                } else {
                    val errorCode = json?.optInt("errorCode", response.code) ?: response.code
                    val errorMsg = if (message.isNotBlank()) message else "خطای ارسال ($errorCode)"
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Pair(false, "خطا در ارسال پیام به سرور: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * Synchronizes student record to Cloudflare Worker D1 & KV
     */
    suspend fun syncStudentToCloudflareWorker(
        workerUrl: String,
        apiSecret: String,
        student: Student
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = workerUrl.trim().removeSuffix("/")
        if (cleanUrl.isBlank()) return@withContext Pair(false, "آدرس سرور مشخص نیست")

        try {
            val endpoint = "$cleanUrl/api/sync/student"
            val payload = JSONObject().apply {
                put("id", student.id)
                put("studentCode", student.studentCode)
                put("fullName", student.fullName)
                put("phone", student.phone)
                put("nationalCode", student.nationalCode)
                put("telegramChatId", student.telegramChatId)
                put("telegramUsername", student.telegramUsername)
                put("isTelegramConnected", student.isTelegramConnected)
            }

            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (apiSecret.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer ${apiSecret.trim()}")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                Pair(response.isSuccessful, if (response.isSuccessful) "همگام‌سازی موفق" else "خطا در همگام‌سازی")
            }
        } catch (e: Exception) {
            Pair(false, e.localizedMessage ?: "خطای همگام‌سازی")
        }
    }

    /**
     * Tests Telegram bot token validity against Telegram getMe API
     */
    suspend fun testBotConnection(token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) {
            return@withContext Pair(false, "توکن ربات وارد نشده است.")
        }
        try {
            val url = "https://api.telegram.org/bot$cleanToken/getMe"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val json = try {
                    if (body.isNotBlank()) JSONObject(body) else null
                } catch (_: Exception) {
                    null
                }

                if (response.isSuccessful && json != null && json.optBoolean("ok", false)) {
                    val result = json.optJSONObject("result")
                    val botUsername = result?.optString("username", "") ?: ""
                    val botFirstName = result?.optString("first_name", "") ?: ""
                    val botId = result?.optLong("id", 0L) ?: 0L
                    val msg = if (botUsername.isNotBlank()) {
                        "اتصال با موفقیت برقرار شد!\nنام ربات: $botFirstName\nنام کاربری: @$botUsername\nشناسه ربات: $botId"
                    } else {
                        "اتصال با موفقیت برقرار شد!\nنام ربات: $botFirstName"
                    }
                    Pair(true, msg)
                } else {
                    val desc = json?.optString("description")?.ifBlank { null }
                    val errorCode = json?.optInt("error_code", response.code) ?: response.code
                    val errorMsg = if (!desc.isNullOrBlank()) {
                        "خطای تلگرام ($errorCode): $desc"
                    } else if (body.isNotBlank()) {
                        "پاسخ سرور تلگرام ($errorCode): $body"
                    } else {
                        "کد خطای تلگرام: $errorCode"
                    }
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Pair(false, "خطا در ارتباط با سرور تلگرام: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * Generates deep link for student onboarding with real student ID
     * Format: https://t.me/BOT_USERNAME?start=STUDENT_ID
     */
    fun generateStudentDeepLink(student: Student, botUsername: String = ""): String {
        val candidate = if (botUsername.isNotBlank()) botUsername else (settings?.telegramBotUsername ?: "")
        val cleanUsername = candidate.removePrefix("@").trim()
        return if (cleanUsername.isNotBlank()) {
            "https://t.me/$cleanUsername?start=${student.id}"
        } else {
            "https://t.me/?start=${student.id}"
        }
    }

    /**
     * Polls Telegram getUpdates API and processes real student connection requests (/start <STUDENT_ID>)
     * and real Telegram student menu buttons:
     * - 💰 وضعیت مالی
     * - 📅 اقساط من
     * - 🧾 رسیدهای من
     * - 📄 قرارداد من
     * - 📞 تماس با آموزشگاه
     * - 🔄 بروزرسانی اطلاعات
     */
    suspend fun fetchAndProcessUpdates(
        academyRepository: AcademyRepository,
        overrideContext: Context? = null
    ): TelegramPollResult = withContext(Dispatchers.IO) {
        val activeContext = overrideContext ?: context
        val botToken = settings?.telegramBotToken?.trim() ?: ""
        if (botToken.isBlank()) {
            return@withContext TelegramPollResult(
                isSuccess = false,
                message = "توکن ربات تلگرام در تنظیمات آموزشگاه ثبت نشده است."
            )
        }

        try {
            val offsetParam = if (lastUpdateOffset > 0) "&offset=$lastUpdateOffset" else ""
            val url = "https://api.telegram.org/bot$botToken/getUpdates?limit=50&timeout=2$offsetParam"
            val request = Request.Builder().url(url).get().build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext TelegramPollResult(
                        isSuccess = false,
                        message = "خطا در دریافت پیام‌های تلگرام (کد ${response.code})"
                    )
                }

                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    val desc = json.optString("description", "پاسخ ناموفق از تلگرام")
                    return@withContext TelegramPollResult(isSuccess = false, message = desc)
                }

                val resultsArray = json.optJSONArray("result")
                if (resultsArray == null || resultsArray.length() == 0) {
                    return@withContext TelegramPollResult(
                        isSuccess = true,
                        totalProcessedUpdates = 0,
                        message = "پیام جدیدی در تلگرام یافت نشد."
                    )
                }

                var newConnections = 0
                var failedConnections = 0
                val totalUpdates = resultsArray.length()

                for (i in 0 until resultsArray.length()) {
                    val update = resultsArray.optJSONObject(i) ?: continue
                    val updateId = update.optLong("update_id", 0L)
                    if (updateId >= lastUpdateOffset) {
                        lastUpdateOffset = updateId + 1
                    }

                    val messageObj = update.optJSONObject("message") ?: continue
                    val fromObj = messageObj.optJSONObject("from")
                    val chatObj = messageObj.optJSONObject("chat")

                    val chatId = chatObj?.optLong("id")?.toString()
                        ?: fromObj?.optLong("id")?.toString()
                        ?: ""
                    val username = fromObj?.optString("username", "")
                        ?: chatObj?.optString("username", "")
                        ?: ""
                    val firstName = fromObj?.optString("first_name", "")
                        ?: chatObj?.optString("first_name", "")
                        ?: ""
                    val text = messageObj.optString("text", "").trim()

                    if (chatId.isBlank()) continue

                    // ========================================================
                    // 1. Process /start command
                    // ========================================================
                    if (text.startsWith("/start")) {
                        val cleaned = text.removePrefix("/start").trim()
                        val studentIdParam = if (cleaned.startsWith("=")) {
                            cleaned.removePrefix("=").trim()
                        } else if (cleaned.contains("@")) {
                            cleaned.substringAfter(" ").trim()
                        } else {
                            cleaned.split("\\s+".toRegex()).firstOrNull() ?: ""
                        }.trim()

                        if (studentIdParam.isBlank()) {
                            // User clicked start without student parameter
                            val linkedStudent = academyRepository.getStudentByTelegramChatId(chatId)
                            if (linkedStudent != null) {
                                val greeting = buildString {
                                    append("سلام <b>${linkedStudent.fullName}</b> عزیز! 👋\n\n")
                                    append("به پنل اختصاصی هنرجو در ربات آموزشگاه خوش آمدید.\n")
                                    append("از منوی زیر می‌توانید وضعیت مالی، اقساط، رسیدها و قرارداد خود را دریافت نمایید:")
                                }
                                sendMessageToChatInternal(
                                    botToken = botToken,
                                    chatId = chatId,
                                    text = greeting,
                                    parseMode = "HTML",
                                    replyMarkup = getStudentMenuReplyMarkupJson()
                                )
                            } else {
                                val greeting = buildString {
                                    append("سلام ${if (firstName.isNotBlank()) firstName else "هنرجوی گرامی"}! 👋\n\n")
                                    append("به ربات هوشمند آموزشگاه خوش آمدید.\n")
                                    append("برای اتصال حساب کاربری و دریافت خودکار اعلان‌ها و اقساط، لطفاً از لینک اختصاصی که توسط آموزشگاه برای شما پیامک یا ارسال شده است وارد شوید.")
                                }
                                sendMessageToChatInternal(botToken, chatId, greeting)
                            }
                        } else {
                            // Real student ID received from Telegram Start deep link
                            val student = academyRepository.getStudentById(studentIdParam)
                                ?: academyRepository.getStudentByTelegramToken(studentIdParam)

                            if (student == null) {
                                // Student ID not found in Room
                                failedConnections++
                                val errorMsg = "❌ هنرجوی گرامی، شناسه ارسالی (<code>$studentIdParam</code>) در سامانه آموزشگاه یافت نشد.\nلطفاً از لینک اختصاصی معتبر استفاده کنید یا با مدیریت آموزشگاه تماس بگیرید."
                                sendMessageToChatInternal(botToken, chatId, errorMsg, parseMode = "HTML")

                                academyRepository.logCustomAudit(
                                    AuditLog(
                                        entityType = "STUDENT",
                                        entityId = studentIdParam,
                                        actionType = "TELEGRAM_LINK_FAILED",
                                        newValue = "Chat ID: $chatId, Username: @$username",
                                        reason = "شناسه هنرجو در دیتابیس یافت نشد",
                                        changedBy = "ربات تلگرام",
                                        timestampShamsi = PersianDateUtil.currentShamsiString(),
                                        timestampEpoch = System.currentTimeMillis(),
                                        description = "تلاش ناموفق اتصال تلگرام: شناسه $studentIdParam در دیتابیس یافت نشد (شناسه چت تلگرام: $chatId, نام: $firstName)"
                                    )
                                )
                            } else {
                                // Prevent one Telegram Chat ID from being linked to multiple students
                                val otherStudent = academyRepository.getStudentByTelegramChatId(chatId)
                                if (otherStudent != null && otherStudent.id != student.id) {
                                    failedConnections++
                                    val conflictMsg = "⚠️ این حساب تلگرام قبلاً به هنرجوی دیگری (<b>${otherStudent.fullName}</b>) متصل شده است.\nامکان اتصال همزمان یک حساب تلگرام به چند هنرجو وجود ندارد. برای تغییر، ابتدا باید اتصال قبلی در سامانه لغو گردد."
                                    sendMessageToChatInternal(botToken, chatId, conflictMsg, parseMode = "HTML")

                                    academyRepository.logCustomAudit(
                                        AuditLog(
                                            entityType = "STUDENT",
                                            entityId = student.id,
                                            actionType = "TELEGRAM_LINK_FAILED",
                                            previousValue = "متصل به ${otherStudent.fullName} (ID: ${otherStudent.id})",
                                            newValue = "درخواست برای ${student.fullName} (ID: ${student.id})",
                                            reason = "تکراری بودن شناسه چت تلگرام",
                                            changedBy = "ربات تلگرام",
                                            timestampShamsi = PersianDateUtil.currentShamsiString(),
                                            timestampEpoch = System.currentTimeMillis(),
                                            description = "تلاش ناموفق اتصال تلگرام: شناسه چت $chatId قبلاً به هنرجو ${otherStudent.fullName} متصل است. اتصال به ${student.fullName} رد شد."
                                        )
                                    )
                                } else if (student.isTelegramConnected && student.telegramChatId == chatId) {
                                    // Student is already connected
                                    val alreadyMsg = buildString {
                                        append("✅ سلام <b>${student.fullName}</b> عزیز!\n\n")
                                        append("حساب کاربری شما متصل و فعال است 🟢\n")
                                        append("از گزینه‌های کیبورد زیر می‌توانید برای استعلام اقساط، مالی، رسیدها و قراردادها استفاده کنید:")
                                    }
                                    sendMessageToChatInternal(
                                        botToken = botToken,
                                        chatId = chatId,
                                        text = alreadyMsg,
                                        parseMode = "HTML",
                                        replyMarkup = getStudentMenuReplyMarkupJson()
                                    )
                                } else {
                                    // Connect student!
                                    val now = System.currentTimeMillis()
                                    val updatedStudent = student.copy(
                                        telegramEnabled = true,
                                        telegramChatId = chatId,
                                        telegramUsername = username,
                                        telegramLinkedAt = if (student.telegramLinkedAt > 0L) student.telegramLinkedAt else now,
                                        updatedAt = now
                                    )
                                    academyRepository.updateStudent(updatedStudent)
                                    newConnections++

                                    academyRepository.logCustomAudit(
                                        AuditLog(
                                            entityType = "STUDENT",
                                            entityId = student.id,
                                            actionType = "TELEGRAM_LINKED",
                                            previousValue = if (student.telegramChatId.isNotBlank()) "Chat ID: ${student.telegramChatId}" else "غیرمتصل",
                                            newValue = "Chat ID: $chatId, @$username",
                                            reason = "اتصال خودکار از طریق کلیک روی Start در لینک تلگرام",
                                            changedBy = "ربات تلگرام",
                                            timestampShamsi = PersianDateUtil.currentShamsiString(),
                                            timestampEpoch = now,
                                            description = "اتصال موفق تلگرام: هنرجو ${student.fullName} (کد: ${student.studentCode}) با موفقیت به شناسه چت $chatId متصل شد."
                                        )
                                    )

                                    // Send welcome confirmation message with interactive Reply Keyboard
                                    val welcomeMsg = buildString {
                                        append("🎉 سلام <b>${student.fullName}</b> عزیز!\n\n")
                                        append("اتصال حساب تلگرام شما به سامانه هوشمند آموزشگاه با موفقیت برقرار شد. ✅\n\n")
                                        append("👤 <b>نام هنرجو:</b> ${student.fullName}\n")
                                        if (student.studentCode.isNotBlank()) {
                                            append("🆔 <b>کد هنرجویی:</b> ${student.studentCode}\n")
                                        }
                                        append("📅 <b>تاریخ اتصال:</b> ${PersianDateUtil.currentShamsiString()}\n\n")
                                        append("از منوی زیر می‌توانید وضعیت مالی، اقساط، سوابق واریزی‌ها و دریافت فایل PDF قرارداد و رسیدها را به راحتی مدیریت کنید 👇")
                                    }
                                    sendMessageToChatInternal(
                                        botToken = botToken,
                                        chatId = chatId,
                                        text = welcomeMsg,
                                        parseMode = "HTML",
                                        replyMarkup = getStudentMenuReplyMarkupJson()
                                    )
                                }
                            }
                        }
                    } else {
                        // ========================================================
                        // 2. Process Interactive Menu Buttons
                        // ========================================================
                        val student = academyRepository.getStudentByTelegramChatId(chatId)

                        if (student == null) {
                            val notLinkedMsg = buildString {
                                append("⚠️ <b>حساب تلگرام شما هنوز به سامانه آموزشگاه متصل نشده است.</b>\n\n")
                                append("لطفاً از لینک اختصاصی که توسط آموزشگاه برای شما پیامک شده است وارد شوید و دکمه Start را بزنید.")
                            }
                            sendMessageToChatInternal(botToken, chatId, notLinkedMsg, parseMode = "HTML")
                        } else {
                            val normalized = text.trim()

                            when {
                                normalized == "💰 وضعیت مالی" || normalized.contains("وضعیت مالی") || normalized == "/finance" || normalized == "/balance" -> {
                                    handleFinancialStatus(botToken, chatId, student, academyRepository)
                                }
                                normalized == "📅 اقساط من" || normalized.contains("اقساط من") || normalized.contains("اقساط") || normalized == "/installments" -> {
                                    handleInstallments(botToken, chatId, student, academyRepository)
                                }
                                normalized == "🧾 رسیدهای من" || normalized.contains("رسیدهای من") || normalized.contains("رسید") || normalized == "/receipts" -> {
                                    handleReceipts(activeContext, botToken, chatId, student, academyRepository)
                                }
                                normalized == "📄 قرارداد من" || normalized.contains("قرارداد من") || normalized.contains("قرارداد") || normalized == "/contract" -> {
                                    handleContract(activeContext, botToken, chatId, student, academyRepository)
                                }
                                normalized == "📞 تماس با آموزشگاه" || normalized.contains("تماس با آموزشگاه") || normalized.contains("تماس") || normalized == "/contact" || normalized == "/about" -> {
                                    handleContactInfo(botToken, chatId)
                                }
                                normalized == "🔄 بروزرسانی اطلاعات" || normalized.contains("بروزرسانی اطلاعات") || normalized.contains("بروزرسانی") || normalized == "/refresh" || normalized == "/profile" -> {
                                    handleRefreshData(botToken, chatId, student, academyRepository)
                                }
                                else -> {
                                    // Default menu reminder
                                    val menuPrompt = buildString {
                                        append("سلام <b>${student.fullName}</b> عزیز 🌸\n\n")
                                        append("لطفاً یکی از گزینه‌های زیر را انتخاب نمایید:")
                                    }
                                    sendMessageToChatInternal(
                                        botToken = botToken,
                                        chatId = chatId,
                                        text = menuPrompt,
                                        parseMode = "HTML",
                                        replyMarkup = getStudentMenuReplyMarkupJson()
                                    )
                                }
                            }
                        }
                    }
                }

                TelegramPollResult(
                    isSuccess = true,
                    newConnectionsCount = newConnections,
                    failedConnectionsCount = failedConnections,
                    totalProcessedUpdates = totalUpdates,
                    message = if (newConnections > 0) "$newConnections اتصال جدید تلگرام با موفقیت برقرار شد." else "بررسی انجام شد ($totalUpdates پیام پردازش شد)."
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch updates from Telegram", e)
            TelegramPollResult(
                isSuccess = false,
                message = "خطا در ارتباط با سرور تلگرام: ${e.localizedMessage ?: "عدم دسترسی اینترنت"}"
            )
        }
    }

    /**
     * 💰 وضعیت مالی:
     * Show current course, total tuition, discount, total paid, remaining balance, and settlement status.
     */
    private suspend fun handleFinancialStatus(
        botToken: String,
        chatId: String,
        student: Student,
        academyRepository: AcademyRepository
    ) {
        val enrollments = academyRepository.getEnrollmentsForStudentDirect(student.id)
        val installments = academyRepository.getInstallmentsForStudentDirect(student.id)
        val payments = academyRepository.getPaymentsForStudentDirect(student.id).filter { !it.isReversed }
        val currency = settings?.currencyUnit ?: "تومان"

        val totalTuition = enrollments.sumOf { it.totalFee }
        val totalDiscount = enrollments.sumOf { it.discount }
        val finalTuition = (totalTuition - totalDiscount).coerceAtLeast(0L)
        val totalPaid = payments.sumOf { it.amount }
        val remainingDebt = (finalTuition - totalPaid).coerceAtLeast(0L)

        val hasOverdue = installments.any { !it.isPaid && it.dueDateEpoch > 0 && it.dueDateEpoch < System.currentTimeMillis() }

        val statusText = when {
            enrollments.isEmpty() -> "بدون دوره آموزشی ثبت‌شده"
            remainingDebt == 0L -> "✅ تسویه کامل شده (بدون بدهی)"
            hasOverdue -> "🚨 دارای اقساط معوقه و سررسید گذشته"
            else -> "⏳ دارای اقساط جاری در حال پرداخت"
        }

        val response = buildString {
            append("📊 <b>وضعیت مالی و شهریه</b>\n")
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("👤 <b>هنرجو:</b> ${student.fullName}\n")
            if (student.studentCode.isNotBlank()) {
                append("🆔 <b>کد هنرجویی:</b> ${student.studentCode}\n")
            }
            append("\n")

            if (enrollments.isNotEmpty()) {
                append("📚 <b>دوره‌های ثبت‌نام شده:</b>\n")
                enrollments.forEachIndexed { index, en ->
                    append("${index + 1}. <b>${en.courseTitle}</b>\n")
                    append("   • شهریه مصوب: ${PersianDateUtil.formatCurrency(en.totalFee, currency)}\n")
                    if (en.discount > 0) {
                        append("   • تخفیف: ${PersianDateUtil.formatCurrency(en.discount, currency)}\n")
                    }
                    append("   • شهریه نهایی: ${PersianDateUtil.formatCurrency(en.finalFee, currency)}\n")
                }
                append("\n")
            }

            append("💳 <b>خلاصه وضعیت مالی:</b>\n")
            append("• کل شهریه نهایی: <b>${PersianDateUtil.formatCurrency(finalTuition, currency)}</b>\n")
            append("• کل پرداختی تا کنون: <b>${PersianDateUtil.formatCurrency(totalPaid, currency)}</b>\n")
            append("• مانده بدهی کل: <b>${PersianDateUtil.formatCurrency(remainingDebt, currency)}</b>\n")
            append("• وضعیت تسویه: <b>$statusText</b>\n")
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("📅 تاریخ استعلام: ${PersianDateUtil.currentShamsiString()}")
        }

        sendMessageToChatInternal(
            botToken = botToken,
            chatId = chatId,
            text = response,
            parseMode = "HTML",
            replyMarkup = getStudentMenuReplyMarkupJson()
        )
    }

    /**
     * 📅 اقساط من:
     * Show all installments with installment number, amount, due date, paid/unpaid status.
     */
    private suspend fun handleInstallments(
        botToken: String,
        chatId: String,
        student: Student,
        academyRepository: AcademyRepository
    ) {
        val installments = academyRepository.getInstallmentsForStudentDirect(student.id)
            .sortedWith(compareBy({ it.dueDateEpoch }, { it.installmentNumber }))
        val currency = settings?.currencyUnit ?: "تومان"

        if (installments.isEmpty()) {
            val msg = "📅 <b>اقساط من</b>\n\nهنرجوی گرامی ${student.fullName}، در حال حاضر هیچ قسطی برای شما در سیستم ثبت نشده است."
            sendMessageToChatInternal(
                botToken = botToken,
                chatId = chatId,
                text = msg,
                parseMode = "HTML",
                replyMarkup = getStudentMenuReplyMarkupJson()
            )
            return
        }

        val paidCount = installments.count { it.isPaid }
        val pendingCount = installments.size - paidCount

        val response = buildString {
            append("📅 <b>جدول و وضعیت اقساط</b>\n")
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("👤 <b>هنرجو:</b> ${student.fullName}\n")
            append("📊 تعداد کل اقساط: ${installments.size} قسط ($paidCount پرداخت‌شده | $pendingCount مانده)\n\n")

            installments.forEachIndexed { index, inst ->
                val num = if (inst.totalInstallments > 0) "${inst.installmentNumber} از ${inst.totalInstallments}" else "${index + 1}"
                val title = if (inst.courseTitle.isNotBlank()) " (${inst.courseTitle})" else ""
                append("🔹 <b>قسط شماره $num</b>$title\n")
                append("   💵 مبلغ: <b>${PersianDateUtil.formatCurrency(inst.amount, currency)}</b>\n")
                append("   📆 سررسید: <b>${inst.dueDateShamsi}</b>\n")

                when {
                    inst.isPaid -> {
                        val paidDate = if (inst.fullPaidDateShamsi.isNotBlank()) " در تاریخ ${inst.fullPaidDateShamsi}" else ""
                        append("   وضعیت: <b>✅ پرداخت شده$paidDate</b>\n")
                    }
                    inst.dueDateEpoch > 0 && inst.dueDateEpoch < System.currentTimeMillis() -> {
                        val daysOverdue = ((System.currentTimeMillis() - inst.dueDateEpoch) / (1000 * 60 * 60 * 24)).coerceAtLeast(1)
                        append("   وضعیت: <b>🚨 سررسید گذشته ($daysOverdue روز تاخیر)</b>\n")
                        if (inst.paidAmount > 0) {
                            append("   • واریز جزئی: ${PersianDateUtil.formatCurrency(inst.paidAmount, currency)}\n")
                            append("   • مانده این قسط: ${PersianDateUtil.formatCurrency(inst.remainingAmount, currency)}\n")
                        }
                    }
                    else -> {
                        append("   وضعیت: <b>⏳ در انتظار پرداخت</b>\n")
                        if (inst.paidAmount > 0) {
                            append("   • واریز جزئی: ${PersianDateUtil.formatCurrency(inst.paidAmount, currency)}\n")
                            append("   • مانده این قسط: ${PersianDateUtil.formatCurrency(inst.remainingAmount, currency)}\n")
                        }
                    }
                }
                append("\n")
            }

            append("━━━━━━━━━━━━━━━━━━━\n")
            append("💡 جهت پرداخت اقساط یا دریافت شماره کارت، با آموزشگاه در تماس باشید.")
        }

        sendMessageToChatInternal(
            botToken = botToken,
            chatId = chatId,
            text = response,
            parseMode = "HTML",
            replyMarkup = getStudentMenuReplyMarkupJson()
        )
    }

    /**
     * 🧾 رسیدهای من:
     * Show the student's payment history and allow sending the real PDF receipt for each payment when available.
     */
    private suspend fun handleReceipts(
        context: Context?,
        botToken: String,
        chatId: String,
        student: Student,
        academyRepository: AcademyRepository
    ) {
        val payments = academyRepository.getPaymentsForStudentDirect(student.id)
            .filter { !it.isReversed }
            .sortedByDescending { it.paymentDateEpoch }
        val currency = settings?.currencyUnit ?: "تومان"

        if (payments.isEmpty()) {
            val msg = "🧾 <b>رسیدهای پرداخت</b>\n\nهنرجوی گرامی ${student.fullName}، تاکنون هیچ تراکنش پرداختی برای شما ثبت نشده است."
            sendMessageToChatInternal(
                botToken = botToken,
                chatId = chatId,
                text = msg,
                parseMode = "HTML",
                replyMarkup = getStudentMenuReplyMarkupJson()
            )
            return
        }

        val totalPaid = payments.sumOf { it.amount }
        val summaryText = buildString {
            append("🧾 <b>سوابق و رسیدهای پرداخت</b>\n")
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("👤 <b>هنرجو:</b> ${student.fullName}\n")
            append("💰 کل مبالغ واریزی: <b>${PersianDateUtil.formatCurrency(totalPaid, currency)}</b> (${payments.size} تراکنش)\n\n")

            payments.take(6).forEachIndexed { idx, p ->
                val methodTitle = when (p.paymentMethod) {
                    "TRANSFER" -> "کارت به کارت"
                    "POS" -> "پوز (کارتخوان)"
                    "CASH" -> "نقدی"
                    "BANK_DEPOSIT" -> "واریز بانکی"
                    else -> "سایر"
                }
                append("${idx + 1}. <b>رسید: <code>${p.transactionId}</code></b>\n")
                append("   💵 مبلغ: <b>${PersianDateUtil.formatCurrency(p.amount, currency)}</b>\n")
                append("   📅 تاریخ: ${p.paymentDateShamsi} (${p.paymentTime})\n")
                append("   💳 روش: $methodTitle\n")
                if (p.trackingNumber.isNotBlank()) {
                    append("   🔖 شماره پیگیری: <code>${p.trackingNumber}</code>\n")
                }
                append("\n")
            }
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("📄 فایل رسمی PDF رسیدها در حال آماده‌سازی و ارسال است...")
        }

        sendMessageToChatInternal(
            botToken = botToken,
            chatId = chatId,
            text = summaryText,
            parseMode = "HTML",
            replyMarkup = getStudentMenuReplyMarkupJson()
        )

        // Generate and send real PDF receipts for recent payments
        if (context != null) {
            val latestPayments = payments.take(3)
            for (payment in latestPayments) {
                try {
                    val installment = if (payment.installmentId.isNotBlank()) {
                        academyRepository.getInstallmentById(payment.installmentId)
                    } else null

                    val course = if (payment.enrollmentId.isNotBlank()) {
                        val enrollment = academyRepository.getEnrollmentById(payment.enrollmentId)
                        if (enrollment != null && enrollment.courseId.isNotBlank()) {
                            academyRepository.getCourseById(enrollment.courseId)
                        } else null
                    } else null

                    val pdfFile = PdfDocumentHelper.generatePaymentReceiptPdf(
                        context = context,
                        payment = payment,
                        installment = installment,
                        student = student,
                        course = course,
                        settings = settings
                    )

                    val caption = "📄 <b>رسید رسمی پرداخت</b>\nشماره رسید: <code>${payment.transactionId}</code>\nمبلغ: ${PersianDateUtil.formatCurrency(payment.amount, currency)}\nتاریخ: ${payment.paymentDateShamsi}"
                    sendDocumentToChatInternal(
                        botToken = botToken,
                        chatId = chatId,
                        file = pdfFile,
                        caption = caption,
                        customFileName = "Receipt_${payment.transactionId}.pdf"
                    )
                } catch (e: Exception) {
                    Log.e(tag, "Failed to generate and send PDF receipt for ${payment.transactionId}", e)
                }
            }
        }
    }

    /**
     * 📄 قرارداد من:
     * Send the real enrollment contract PDF when available.
     */
    private suspend fun handleContract(
        context: Context?,
        botToken: String,
        chatId: String,
        student: Student,
        academyRepository: AcademyRepository
    ) {
        val enrollments = academyRepository.getEnrollmentsForStudentDirect(student.id)

        if (enrollments.isEmpty()) {
            val msg = "📄 <b>برگه قرارداد و ثبت‌نام</b>\n\nهنرجوی گرامی ${student.fullName}، هیچ ثبت‌نام یا قرارداد فعالی برای شما در سامانه یافت نشد."
            sendMessageToChatInternal(
                botToken = botToken,
                chatId = chatId,
                text = msg,
                parseMode = "HTML",
                replyMarkup = getStudentMenuReplyMarkupJson()
            )
            return
        }

        val headerMsg = "📄 <b>در حال تولید و ارسال فایل رسمی قرارداد دوره‌های آموزشی شما...</b>"
        sendMessageToChatInternal(
            botToken = botToken,
            chatId = chatId,
            text = headerMsg,
            parseMode = "HTML",
            replyMarkup = getStudentMenuReplyMarkupJson()
        )

        if (context != null) {
            for (enrollment in enrollments) {
                try {
                    val course = if (enrollment.courseId.isNotBlank()) {
                        academyRepository.getCourseById(enrollment.courseId)
                    } else null

                    val allStudentInstallments = academyRepository.getInstallmentsForStudentDirect(student.id)
                    val enrollmentInstallments = allStudentInstallments.filter { it.enrollmentId == enrollment.id }
                        .ifEmpty { allStudentInstallments }

                    val pdfFile = PdfDocumentHelper.generateEnrollmentContractPdf(
                        context = context,
                        enrollment = enrollment,
                        student = student,
                        course = course,
                        installments = enrollmentInstallments,
                        settings = settings
                    )

                    val caption = buildString {
                        append("📄 <b>برگه قرارداد رسمی ثبت‌نام دوره</b>\n")
                        append("📚 دوره: <b>${course?.title ?: enrollment.courseTitle}</b>\n")
                        append("👤 هنرجو: ${student.fullName}\n")
                        append("🗓 تاریخ ثبت‌نام: ${enrollment.registrationDateShamsi}")
                    }

                    val sanitizedCourseName = (course?.title ?: enrollment.courseTitle).replace("\\s+".toRegex(), "_")
                    sendDocumentToChatInternal(
                        botToken = botToken,
                        chatId = chatId,
                        file = pdfFile,
                        caption = caption,
                        customFileName = "Contract_${sanitizedCourseName}.pdf"
                    )
                } catch (e: Exception) {
                    Log.e(tag, "Failed to generate and send contract PDF for ${enrollment.id}", e)
                }
            }
        } else {
            val fallbackMsg = buildString {
                append("📄 <b>مشخصات قراردادهای آموزشی:</b>\n\n")
                enrollments.forEach { en ->
                    append("🔹 <b>دوره: ${en.courseTitle}</b>\n")
                    append("• تاریخ ثبت‌نام: ${en.registrationDateShamsi}\n")
                    append("• شهریه نهایی: ${PersianDateUtil.formatCurrency(en.finalFee, settings?.currencyUnit ?: "تومان")}\n\n")
                }
            }
            sendMessageToChatInternal(
                botToken = botToken,
                chatId = chatId,
                text = fallbackMsg,
                parseMode = "HTML",
                replyMarkup = getStudentMenuReplyMarkupJson()
            )
        }
    }

    /**
     * 📞 تماس با آموزشگاه:
     * Show the configured academy contact information.
     */
    private suspend fun handleContactInfo(
        botToken: String,
        chatId: String
    ) {
        val academyName = settings?.academyName?.ifEmpty { "آموزشگاه کامپیوتر پیشگامان" } ?: "آموزشگاه کامپیوتر پیشگامان"
        val phone = settings?.phone?.ifEmpty { "۰۲۱-۸۸۸۸۹۹۹۹" } ?: "۰۲۱-۸۸۸۸۹۹۹۹"
        val address = settings?.address?.ifEmpty { "تهران، خیابان انقلاب، نبش کارگر شمالی" } ?: "تهران، خیابان انقلاب، نبش کارگر شمالی"
        val botUsername = settings?.telegramBotUsername?.trim()?.removePrefix("@") ?: ""

        val response = buildString {
            append("🏢 <b>$academyName</b>\n")
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("📞 <b>تلفن تماس:</b> <code>$phone</code>\n")
            append("📍 <b>نشانی آموزشگاه:</b>\n$address\n\n")
            append("⏰ <b>ساعات کاری:</b> همه‌روزه از ۹:۰۰ الی ۲۰:۳۰\n")
            if (botUsername.isNotBlank()) {
                append("🤖 <b>شناسه ربات تلگرام:</b> @$botUsername\n")
            }
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("🌸 همکاران ما با افتخار آماده راهنمایی و پاسخگویی به شما هنرجویان گرامی می‌باشند.")
        }

        sendMessageToChatInternal(
            botToken = botToken,
            chatId = chatId,
            text = response,
            parseMode = "HTML",
            replyMarkup = getStudentMenuReplyMarkupJson()
        )
    }

    /**
     * 🔄 بروزرسانی اطلاعات:
     * Reload the student's latest data from the app database and send the updated information.
     */
    private suspend fun handleRefreshData(
        botToken: String,
        chatId: String,
        student: Student,
        academyRepository: AcademyRepository
    ) {
        val freshStudent = academyRepository.getStudentById(student.id) ?: student
        val enrollments = academyRepository.getEnrollmentsForStudentDirect(freshStudent.id)
        val payments = academyRepository.getPaymentsForStudentDirect(freshStudent.id).filter { !it.isReversed }
        val currency = settings?.currencyUnit ?: "تومان"

        val totalPaid = payments.sumOf { it.amount }
        val totalTuition = enrollments.sumOf { it.finalFee }
        val remainingDebt = (totalTuition - totalPaid).coerceAtLeast(0L)

        val response = buildString {
            append("🔄 <b>اطلاعات حساب کاربری شما به‌روزرسانی شد</b> ✅\n")
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("👤 <b>نام هنرجو:</b> ${freshStudent.fullName}\n")
            if (freshStudent.studentCode.isNotBlank()) {
                append("🆔 <b>کد هنرجویی:</b> ${freshStudent.studentCode}\n")
            }
            if (freshStudent.nationalCode.isNotBlank()) {
                append("🪪 <b>کد ملی:</b> ${freshStudent.nationalCode}\n")
            }
            append("📱 <b>شماره تماس:</b> ${freshStudent.phone}\n")
            append("📚 <b>تعداد دوره‌های فعال:</b> ${enrollments.size}\n")
            append("💰 <b>مانده کل بدهی:</b> ${PersianDateUtil.formatCurrency(remainingDebt, currency)}\n")
            append("🟢 <b>وضعیت اتصال:</b> متصل و فعال\n")
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("⏰ آخرین زمان استعلام: ${PersianDateUtil.currentShamsiString()}")
        }

        sendMessageToChatInternal(
            botToken = botToken,
            chatId = chatId,
            text = response,
            parseMode = "HTML",
            replyMarkup = getStudentMenuReplyMarkupJson()
        )
    }
}
