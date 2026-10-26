package com.example.service.messaging

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.AcademySettings
import com.example.data.model.Installment
import com.example.data.model.Student
import com.example.data.repository.AcademyRepository
import com.example.data.util.FinanceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Connects the app to the Cloudflare Worker that runs the Telegram bot.
 *
 * The Worker is the component that keeps working when the app is closed:
 * - it receives the bot webhook (student messages)
 * - it stores students / installments / financial summaries (D1)
 * - its cron trigger dispatches reminders, so the phone can be off
 *
 * This class pushes the local Room data into the Worker and publishes all
 * upcoming reminders there.
 */
class BotCloudBridge(private val context: Context) {

    private val tag = "BotCloudBridge"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    data class SyncOutcome(
        val attempted: Boolean,
        val success: Boolean,
        val message: String,
        val students: Int = 0,
        val installments: Int = 0,
        val reminders: Int = 0,
        val linkedStudents: Int = 0
    )

    fun isWorkerConfigured(settings: AcademySettings?): Boolean =
        settings?.telegramUseWorker == true &&
                settings.telegramEnabled &&
                settings.telegramWorkerUrl.isNotBlank() &&
                settings.telegramApiSecret.isNotBlank()

    /**
     * Full push: webhook install, students, installments, financials and reminders.
     * Safe to call on every app start / after each change; all calls are idempotent.
     */
    suspend fun pushAll(): SyncOutcome = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val settings = db.settingsDao().getSettingsDirect()
            ?: return@withContext SyncOutcome(false, false, "تنظیمات آموزشگاه خوانده نشد")

        if (!isWorkerConfigured(settings)) {
            return@withContext SyncOutcome(
                attempted = false,
                success = false,
                message = "حالت سرور Cloudflare فعال نیست (آدرس سرور و کلید API لازم است)"
            )
        }

        if (!ensureWebhook(settings)) {
            return@withContext SyncOutcome(false, false, "به‌روزرسانی وبهوک ربات ناموفق بود؛ ابتدا ربات را متصل کنید و کلید API سرور را بررسی کنید")
        }

        val repo = AcademyRepository(db, com.example.data.remote.FirebaseManager())
        // Stage 2: mint one-time /start link tokens before pushing, so the
        // webhook can verify sha256(token) instead of trusting a raw student id.
        runCatching { repo.ensureTelegramLinkTokens() }
        val students = db.studentDao().getAllStudentsDirect()
        val installments = mutableListOf<Installment>()
        for (s in students) {
            installments += repo.getInstallmentsForStudentDirect(s.id)
        }
        val reminders: List<com.example.data.model.Reminder> =
            db.reminderDao().getAllReminders().first()

        val studentsPayload = JSONArray()
        for (s in students) {
            studentsPayload.put(JSONObject().apply {
                put("id", s.id)
                put("studentCode", s.studentCode)
                put("fullName", s.fullName)
                put("phone", s.phone)
                put("nationalCode", s.nationalCode)
                // Empty values are ignored server-side (the bot stays linked)
                put("telegramChatId", s.telegramChatId)
                put("telegramUsername", s.telegramUsername)
                put("isTelegramConnected", s.isTelegramConnected)
                // Stage 2 secure linking: only the SHA-256 of the token is sent.
                put("telegramLinkHash",
                    if (s.telegramLinkToken.isBlank()) ""
                    else com.example.data.util.SecureToken.sha256Hex(s.telegramLinkToken))
                // 0 = no server-side expiry for app-managed tokens; they are
                // single-use (consumed by the first successful /start).
                put("telegramLinkExpires", 0)
            })
        }

        val studentsOk = postJson(
            settings,
            "/api/sync/students-batch",
            JSONObject().put("students", studentsPayload)
        ).first

        val installmentPayload = JSONArray()
        for (i in installments) {
            installmentPayload.put(com.example.data.util.PaymentSyncPayloads.installmentJson(i))
        }
        val installmentsOk = postJson(
            settings,
            "/api/sync/installments",
            JSONObject().put("installments", installmentPayload)
        ).first

        // Financial summary per student (used by the bot "وضعیت مالی" menu)
        val summaryPayload = JSONArray()
        for (s in students) {
            val list = installments.filter { it.studentId == s.id }
            if (list.isEmpty()) continue
            val enrollments = repo.getEnrollmentsForStudentDirect(s.id)
            val payments = repo.getPaymentsForStudentDirect(s.id)
            val summary = FinanceUtil.calculateStudentFinancials(s.id, enrollments, list, payments)
            summaryPayload.put(JSONObject().apply {
                put("student_id", s.id)
                put("total_tuition", summary.totalTuition)
                put("total_paid", summary.totalPaid)
                put("remaining_debt", summary.remainingDebt)
                put("active_courses_count", list.map { it.courseId }.distinct().size)
                put("courses_summary", list.map { it.courseTitle }.distinct().joinToString("، "))
                put("settlement_status", if (summary.remainingDebt <= 0) "SETTLED" else "PENDING")
                put("updated_at_epoch", System.currentTimeMillis())
            })
        }
        val summariesOk = postJson(
            settings,
            "/api/sync/financials",
            JSONObject().put("summaries", summaryPayload)
        ).first

        // Push the academy's public contact info so the bot menu "تماس با آموزشگاه"
        // shows the academy's REAL name/phone/address — never hardcoded defaults.
        // Keys match what the Worker reads (getSetting) and are stored per academy.
        // Credentials (bot token, API secret, SMS key, app-lock PIN) never leave the device.
        val settingsPayload = JSONObject().apply {
            put("academy_name", settings.academyName.trim())
            put("currency_unit", settings.currencyUnit.trim())
            if (settings.phone.isNotBlank()) put("phone", settings.phone.trim())
            if (settings.address.isNotBlank()) put("address", settings.address.trim())
            // Stage 4: gateway + receipt ordering (the API key goes ONLY to the
            // worker over HTTPS for server-side verification — never to any other
            // service, and no endpoint ever echoes it back).
            put("gateway_provider", settings.gatewayProvider.trim())
            if (settings.gatewayMerchantId.isNotBlank()) put("gateway_merchant_id", settings.gatewayMerchantId.trim())
            if (settings.gatewayApiKey.isNotBlank()) put("gateway_api_key", settings.gatewayApiKey.trim())
            put("receipt_first_payment_delay_minutes", settings.receiptFirstPaymentDelayMinutes)
            put("receipt_first_payment_requires_contract", if (settings.receiptFirstPaymentRequiresContract) "true" else "false")
        }
        val settingsOk = postJson(
            settings,
            "/api/sync/settings",
            settingsPayload
        ).first

        // Stage 4: push the app's REAL payments — the same records shown in the
        // "پرداخت‌ها" tab become the Telegram receipts. The push is idempotent:
        // the worker keys each receipt by the payment id, so re-syncing can
        // never duplicate a transaction or re-send a receipt.
        val payments = db.paymentDao().getAllPayments().firstOrNull() ?: emptyList()
        val paymentsOk = if (payments.isNotEmpty()) {
            postJson(
                settings,
                "/api/sync/payments",
                com.example.data.util.PaymentSyncPayloads.paymentsJson(payments)
            ).first
        } else true

        // Stage 4: pull confirmed ONLINE payments into Room automatically —
        // a verified gateway payment updates the installment with NO manual
        // re-registration (idempotent by orderId).
        val pulledOnline = pullConfirmedOnlinePayments(settings, db)

        // Publish all future reminders so the server can send them with the app closed
        val now = System.currentTimeMillis()
        val reminderPayload = JSONArray()
        for (r in reminders) {
            if (r.status != "SCHEDULED" || r.scheduledTimeEpoch <= now) continue
            val student = students.firstOrNull { it.id == r.studentId } ?: continue
            if (student.telegramChatId.isBlank()) continue
            reminderPayload.put(JSONObject().apply {
                put("id", r.id)
                put("studentId", r.studentId)
                put("studentName", r.studentName)
                put("chatId", student.telegramChatId)
                put("installmentId", r.installmentId)
                put("reminderType", r.type)
                put("message", reminderMessage(r, student, installments))
                put("scheduledEpoch", r.scheduledTimeEpoch)
                put("scheduledTimeStr", r.sendTimeShamsi)
                // Stage 5: the offset lets the worker dedupe app rows against
                // its own deterministic five-nob schedule (no double sends).
                put("triggerDaysOffset", r.triggerDaysOffset)
                put("studentPhone", student.phone)
            })
        }
        val remindersOk = postJson(
            settings,
            "/api/schedule-reminders",
            JSONObject().put("reminders", reminderPayload)
        ).first

        // Push deletions of students (tombstones) before anything else
        val deleted = pushStudentDeletions(settings, db)

        // Pull links the students created in the bot back into Room
        val linked = pullTelegramLinks(students)

        val ok = studentsOk && installmentsOk && summariesOk && remindersOk && settingsOk && paymentsOk
        val msg = buildString {
            append(if (ok) "همگام‌سازی سرور انجام شد" else "همگام‌سازی ناقص بود")
            append(" · هنرجو: ${students.size}")
            append(" · قسط: ${installments.size}")
            append(" · یادآوری: ${reminderPayload.length()}")
            if (linked > 0) append(" · لینک تلگرام: $linked")
            if (pulledOnline > 0) append(" · پرداخت آنلاین: $pulledOnline")
            if (deleted > 0) append(" · حذف‌شده: $deleted")
        }
        Log.d(tag, msg)

        SyncOutcome(
            attempted = true,
            success = ok,
            message = msg,
            students = students.size,
            installments = installments.size,
            reminders = reminderPayload.length(),
            linkedStudents = linked
        )
    }

    /** Sends pending student deletions to the server and clears them from Room. */
    private suspend fun pushStudentDeletions(
        settings: AcademySettings,
        db: AppDatabase
    ): Int {
        val pending: List<com.example.data.model.PendingStudentDeletion> = runCatching {
            db.studentDao().getPendingDeletions().first()
        }.getOrDefault(emptyList())
        if (pending.isEmpty()) return 0

        var cleared = 0
        for (row in pending) {
            val (ok, _) = postJson(settings, "/api/sync/student-delete", JSONObject().put("id", row.studentId))
            if (ok) {
                db.studentDao().clearStudentDeletion(row.studentId)
                cleared++
            }
        }
        return cleared
    }

    /** Deletes a student on the server immediately (best effort). */
    suspend fun deleteStudentRemote(studentId: String): Boolean = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect() ?: return@withContext false
        if (!isWorkerConfigured(settings)) return@withContext false
        postJson(settings, "/api/sync/student-delete", JSONObject().put("id", studentId)).first
    }

    /**
     * Pulls the Telegram links that students created by messaging the bot.
     * The server is the source of truth for telegramChatId.
     */
    suspend fun pullTelegramLinks(students: List<Student>): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val settings = db.settingsDao().getSettingsDirect() ?: return@withContext 0
        if (!isWorkerConfigured(settings)) return@withContext 0

        val (ok, body) = postJson(settings, "/api/sync/telegram-links", JSONObject())
        if (!ok) return@withContext 0

        var updated = 0
        try {
            val links = JSONObject(body).optJSONObject("data")?.optJSONArray("links")
                ?: return@withContext 0
            for (i in 0 until links.length()) {
                val item = links.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val chatId = item.optString("telegramChatId")
                if (id.isBlank() || chatId.isBlank()) continue
                val student = db.studentDao().getStudentById(id) ?: continue
                if (student.telegramChatId == chatId) continue
                db.studentDao().updateStudent(
                    student.copy(
                        telegramChatId = chatId,
                        telegramUsername = item.optString("telegramUsername"),
                        telegramEnabled = true,
                        telegramLinkedAt = item.optLong("linkedAt", System.currentTimeMillis())
                    )
                )
                updated++
            }
        } catch (e: Exception) {
            Log.w(tag, "pullTelegramLinks parse failed: ${e.message}")
        }
        updated
    }

    /**
     * Installs/refreshes the bot webhook so the server receives student messages.
     * Uses the per-academy repair endpoint (/api/bot/refresh-webhook): it works
     * with the academy's OWN bot token and its OWN webhook secret — the legacy
     * /api/set-webhook depended on a global token the deployment no longer has.
     */
    suspend fun ensureWebhook(settings: AcademySettings): Boolean = withContext(Dispatchers.IO) {
        val (ok, body) = postJson(settings, "/api/bot/refresh-webhook", JSONObject())
        if (!ok) Log.w(tag, "refresh-webhook failed: $body")
        ok
    }

    /**
     * Stage 4: pulls VERIFIED online payments from the worker and applies them
     * into Room exactly once (order id = payment id). Mirrors the repository's
     * own installment math so balances stay consistent without manual entry.
     */
    private suspend fun pullConfirmedOnlinePayments(
        settings: AcademySettings,
        db: AppDatabase
    ): Int = withContext(Dispatchers.IO) {
        val current = db.settingsDao().getSettingsDirect() ?: return@withContext 0
        if (!isWorkerConfigured(current)) return@withContext 0
        val since = current.onlinePaymentsPulledAtEpoch
        val (ok, body) = getJson(settings, "/api/payments/confirmed?since=$since")
        if (!ok) return@withContext 0

        var applied = 0
        try {
            val list = JSONObject(body).optJSONObject("data")?.optJSONArray("payments")
                ?: return@withContext 0
            var newest = since
            for (i in 0 until list.length()) {
                val t = list.optJSONObject(i) ?: continue
                val orderId = t.optString("orderId")
                val verifiedAt = t.optLong("verifiedAtEpoch", 0L)
                if (verifiedAt > newest) newest = verifiedAt
                val payment = com.example.data.util.PaymentSyncPayloads.onlinePaymentFromTxn(
                    orderId = orderId,
                    studentId = t.optString("studentId"),
                    installmentId = t.optString("installmentId"),
                    amount = t.optLong("amount", 0L),
                    ref = t.optString("ref"),
                    verifiedAtEpoch = verifiedAt,
                    existingPaymentIds = db.paymentDao().getPaymentById(orderId)?.let { setOf(it.id) } ?: emptySet(),
                    paymentMethod = t.optString("method", "ONLINE").ifBlank { "ONLINE" },
                    source = t.optString("source", "online").ifBlank { "online" }
                ) ?: continue

                db.paymentDao().insertPayment(payment)
                val inst = db.installmentDao().getInstallmentById(payment.installmentId)
                if (inst != null) {
                    val paymentsFor = db.paymentDao().getPaymentsForInstallment(inst.id).firstOrNull() ?: listOf(payment)
                    db.installmentDao().updateInstallment(
                        com.example.data.util.PaymentSyncPayloads.installmentAfterPayment(inst, paymentsFor)
                    )
                }
                applied++
            }
            if (newest > since) {
                db.settingsDao().insertOrUpdate(current.copy(onlinePaymentsPulledAtEpoch = newest))
            }
        } catch (e: Exception) {
            Log.w(tag, "pullConfirmedOnlinePayments parse failed: ${e.message}")
        }
        applied
    }

    /** Cancels the server-side reminder queue of an installment (e.g. after payment). */
    suspend fun cancelInstallmentReminders(installmentId: String) {
        withContext(Dispatchers.IO) {
            if (installmentId.isBlank()) return@withContext
            val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
            if (!isWorkerConfigured(settings)) return@withContext
            postJson(settings!!, "/api/cancel-reminders", JSONObject().put("installmentId", installmentId))
        }
    }

    // ============================================================
    // Stage 6: server messaging settings / templates / manual send
    // (the worker is the source of truth; these are thin transports)
    // ============================================================

    /** GET /api/messaging/config — null when the worker is not configured/unreachable. */
    suspend fun pullServerMessagingConfig(): WorkerMessaging.MessagingConfig? = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext null
        val (ok, body) = getJson(settings!!, "/api/messaging/config")
        if (!ok) return@withContext null
        WorkerMessaging.parseConfig(body)
    }

    /** PUT /api/messaging/config — returns (ok, bodyOrError). */
    suspend fun pushServerMessagingConfig(payload: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        requestJson(settings!!, "PUT", "/api/messaging/config", payload)
    }

    /** POST /api/messaging/preview — sample-data render; NEVER sends anything. */
    suspend fun previewServerTemplate(payload: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        postJson(settings!!, "/api/messaging/preview", payload)
    }

    /** POST /api/messaging/templates/reset — eventType optional (null = all). */
    suspend fun resetServerTemplate(eventType: String?): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        val payload = JSONObject()
        if (!eventType.isNullOrBlank()) payload.put("eventType", eventType)
        postJson(settings!!, "/api/messaging/templates/reset", payload)
    }

    /** POST /api/messaging/send — manager manual reminder send (idempotent by clientMsgId). */
    suspend fun sendManualReminder(
        studentId: String,
        installmentId: String,
        eventType: String,
        channel: String,
        text: String?,
        clientMsgId: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        val payload = JSONObject()
            .put("studentId", studentId)
            .put("installmentId", installmentId)
            .put("eventType", eventType)
            .put("channel", channel)
            .put("clientMsgId", clientMsgId)
        if (!text.isNullOrBlank()) payload.put("text", text)
        postJson(settings!!, "/api/messaging/send", payload)
    }

    /** GET /api/messaging/reminders — per-installment schedule/status for the manager. */
    suspend fun pullRemindersView(studentId: String? = null, installmentId: String? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        val query = buildList {
            if (!studentId.isNullOrBlank()) add("studentId=$studentId")
            if (!installmentId.isNullOrBlank()) add("installmentId=$installmentId")
        }.joinToString("&")
        val path = if (query.isBlank()) "/api/messaging/reminders" else "/api/messaging/reminders?$query"
        getJson(settings!!, path)
    }

    /** POST /api/messaging/reminders/stop — scope: THIS installment only. */
    suspend fun stopInstallmentReminders(installmentId: String, reason: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        val payload = JSONObject().put("installmentId", installmentId)
        if (reason.isNotBlank()) payload.put("reason", reason)
        postJson(settings!!, "/api/messaging/reminders/stop", payload)
    }

    // ------------------------------------------------------------
    // Stage 9 — SMS panel settings (/api/messaging/sms/*)
    //
    // Secret values are sent ONLY when the manager actually typed one;
    // a blank field keeps the server-stored value (never cleared, never
    // echoed back). The provider selection can change without touching
    // the message history or the other panel's stored fields.
    // ------------------------------------------------------------

    /** GET /api/messaging/sms/config — masked config; null when unreachable. */
    suspend fun pullSmsPanelConfig(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        getJson(settings!!, "/api/messaging/sms/config")
    }

    /**
     * PUT /api/messaging/sms/config — select the active provider and save its
     * fields. Only non-blank values are sent; blank secrets keep the stored
     * value on the server. provider "" (or "none") deactivates SMS sending
     * while keeping every stored field and all message history.
     */
    suspend fun pushSmsPanelConfig(provider: String, fields: Map<String, String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        val payload = JSONObject().put("provider", provider)
        if (fields.isNotEmpty()) {
            val f = JSONObject()
            for ((k, v) in fields) if (v.isNotBlank()) f.put(k, v)
            if (f.length() > 0) payload.put("fields", f)
        }
        requestJson(settings!!, "PUT", "/api/messaging/sms/config", payload)
    }

    /** POST /api/messaging/sms/test-connection — FREE check only; never sends an SMS. */
    suspend fun testSmsPanelConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        postJson(settings!!, "/api/messaging/sms/test-connection", JSONObject())
    }

    /** POST /api/messaging/reminders/resume — rebuilds the schedule of ONE installment. */
    suspend fun resumeInstallmentReminders(installmentId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getDatabase(context).settingsDao().getSettingsDirect()
        if (!isWorkerConfigured(settings)) return@withContext Pair(false, "اتصال به سرور پیکربندی نشده است.")
        postJson(settings!!, "/api/messaging/reminders/resume", JSONObject().put("installmentId", installmentId))
    }

    // ==================================================================
    // Stage 7: server-side backup control (metadata only; the snapshot
    // payload itself never leaves the Worker's D1).
    // ==================================================================

    /** GET /api/backup/status -> (ok, raw body for BackupApi parsing). */
    suspend fun fetchBackupStatus(settings: AcademySettings): Pair<Boolean, String> =
        withContext(Dispatchers.IO) { requestJson(settings, "GET", "/api/backup/status", null) }

    /** POST /api/backup/run -> manual/retry run of the verified backup. */
    suspend fun runServerBackup(settings: AcademySettings): Pair<Boolean, String> =
        withContext(Dispatchers.IO) { requestJson(settings, "POST", "/api/backup/run", JSONObject()) }

    /** GET /api/finance/health -> server-side consistency report (read-only). */
    suspend fun fetchFinanceHealth(settings: AcademySettings): Pair<Boolean, String> =
        withContext(Dispatchers.IO) { requestJson(settings, "GET", "/api/finance/health", null) }

    /**
     * Generalized JSON transport (GET/POST/PUT) shared by the stage 6
     * messaging config PUT and the stage 7 backup/finance GET/POST calls.
     */
    private fun requestJson(
        settings: AcademySettings,
        method: String,
        path: String,
        payload: JSONObject?
    ): Pair<Boolean, String> {
        return try {
            val base = settings.telegramWorkerUrl.trim().removeSuffix("/")
            val builder = Request.Builder()
                .url(base + path)
                .addHeader("Authorization", "Bearer ${settings.telegramApiSecret.trim()}")
                .addHeader("X-Academy-Id", WorkerBotApi.ACADEMY_ID)
            when (method) {
                "GET" -> builder.get()
                "PUT" -> builder.put(
                    (payload ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                else -> builder.post(
                    (payload ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                )
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                val json = runCatching { JSONObject(body) }.getOrNull()
                val success = response.isSuccessful && json?.optBoolean("success", false) == true
                Pair(success, body)
            }
        } catch (e: Exception) {
            Log.w(tag, "$method $path failed: ${e.message}")
            Pair(false, e.message ?: "network error")
        }
    }

    private fun reminderMessage(
        reminder: com.example.data.model.Reminder,
        student: Student,
        installments: List<Installment>
    ): String {
        val installment = installments.firstOrNull { it.id == reminder.installmentId }
        val currency = "تومان"
        val offset = reminder.triggerDaysOffset
        val whenText = when {
            offset < 0 -> "${-offset} روز دیگر"
            offset == 0 -> "امروز"
            else -> "$offset روز پیش"
        }
        return buildString {
            append("🔔 <b>یادآوری قسط</b>\n")
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("👤 هنرجو: <b>${student.fullName}</b>\n")
            if (installment != null) {
                append("📚 دوره: ${installment.courseTitle}\n")
                append("🔹 قسط شماره ${installment.installmentNumber} از ${installment.totalInstallments}\n")
                append("💵 مبلغ: <b>${java.text.NumberFormat.getIntegerInstance(java.util.Locale("fa")).format(installment.remainingAmount)} $currency</b>\n")
                append("📆 سررسید: <b>${installment.dueDateShamsi}</b> ($whenText)\n")
            }
            append("━━━━━━━━━━━━━━━━━━━\n")
            append("لطفاً در صورت پرداخت، رسید خود را برای مدیریت آموزشگاه ارسال نمایید. 🙏")
        }
    }

    private fun postJson(settings: AcademySettings, path: String, payload: JSONObject): Pair<Boolean, String> {
        return try {
            val base = settings.telegramWorkerUrl.trim().removeSuffix("/")
            val request = Request.Builder()
                .url(base + path)
                .addHeader("Authorization", "Bearer ${settings.telegramApiSecret.trim()}")
                .addHeader("X-Academy-Id", WorkerBotApi.ACADEMY_ID)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val json = runCatching { JSONObject(body) }.getOrNull()
                val success = response.isSuccessful && json?.optBoolean("success", false) == true
                Pair(success, body)
            }
        } catch (e: Exception) {
            Log.w(tag, "POST $path failed: ${e.message}")
            Pair(false, e.message ?: "network error")
        }
    }

    private fun getJson(settings: AcademySettings, path: String): Pair<Boolean, String> {
        return try {
            val base = settings.telegramWorkerUrl.trim().removeSuffix("/")
            val request = Request.Builder()
                .url(base + path)
                .addHeader("Authorization", "Bearer ${settings.telegramApiSecret.trim()}")
                .addHeader("X-Academy-Id", WorkerBotApi.ACADEMY_ID)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val json = runCatching { JSONObject(body) }.getOrNull()
                val success = response.isSuccessful && json?.optBoolean("success", false) == true
                Pair(success, body)
            }
        } catch (e: Exception) {
            Log.w(tag, "GET $path failed: ${e.message}")
            Pair(false, e.message ?: "network error")
        }
    }
}
