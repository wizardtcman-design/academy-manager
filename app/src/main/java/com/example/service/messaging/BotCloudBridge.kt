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
            return@withContext SyncOutcome(false, false, "ثبت وبهوک روی سرور ناموفق بود؛ کلید API یا آدرس سرور را بررسی کنید")
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
            installmentPayload.put(JSONObject().apply {
                put("id", i.id)
                put("student_id", i.studentId)
                put("installment_number", i.installmentNumber)
                put("total_installments", i.totalInstallments)
                put("course_title", i.courseTitle)
                put("amount", i.amount)
                put("paid_amount", i.paidAmount)
                put("remaining_amount", i.remainingAmount)
                put("due_date_shamsi", i.dueDateShamsi)
                put("due_date_epoch", i.dueDateEpoch)
                put("is_paid", if (i.isPaid) 1 else 0)
                put("paid_date_shamsi", i.fullPaidDateShamsi)
                put("updated_at_epoch", System.currentTimeMillis())
            })
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

        val ok = studentsOk && installmentsOk && summariesOk && remindersOk
        val msg = buildString {
            append(if (ok) "همگام‌سازی سرور انجام شد" else "همگام‌سازی ناقص بود")
            append(" · هنرجو: ${students.size}")
            append(" · قسط: ${installments.size}")
            append(" · یادآوری: ${reminderPayload.length()}")
            if (linked > 0) append(" · لینک تلگرام: $linked")
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

    /** Installs/refreshes the bot webhook so the server receives student messages. */
    suspend fun ensureWebhook(settings: AcademySettings): Boolean = withContext(Dispatchers.IO) {
        val (ok, body) = postJson(settings, "/api/set-webhook", JSONObject())
        if (!ok) Log.w(tag, "set-webhook failed: $body")
        ok
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
}
