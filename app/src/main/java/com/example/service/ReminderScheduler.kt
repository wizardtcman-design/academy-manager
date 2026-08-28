package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.AcademySettings
import com.example.data.model.Installment
import com.example.data.model.Reminder
import com.example.data.util.PersianDateUtil
import com.example.service.receiver.ReminderBroadcastReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

/**
 * Service to manage real Android AlarmManager scheduling for all installment reminders.
 * Supports individual enabled/disabled toggles and hour/minute configurations for:
 * - 7 days before
 * - 3 days before
 * - 1 day before
 * - Day of due date (0)
 * - 1 day after (overdue)
 * - 3 days after (overdue)
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Parse "HH:mm" time string into hours and minutes
     */
    private fun parseTimeString(timeStr: String, defaultHour: Int = 10, defaultMinute: Int = 0): Pair<Int, Int> {
        return try {
            val parts = timeStr.trim().split(":", "：")
            if (parts.size == 2) {
                val h = parts[0].trim().toInt().coerceIn(0, 23)
                val m = parts[1].trim().toInt().coerceIn(0, 59)
                Pair(h, m)
            } else {
                Pair(defaultHour, defaultMinute)
            }
        } catch (_: Exception) {
            Pair(defaultHour, defaultMinute)
        }
    }

    /**
     * Calculate exact epoch millis for an installment given offset in days (-7, -3, -1, 0, 1, 3) and time "HH:mm".
     */
    fun calculateTargetTriggerEpoch(dueDateShamsi: String, daysOffset: Int, timeStr: String): Long {
        val jalali = PersianDateUtil.parseShamsi(dueDateShamsi) ?: return 0L
        val (gy, gm, gd) = PersianDateUtil.jalaliToGregorian(jalali.year, jalali.month, jalali.day)

        val (hour, minute) = parseTimeString(timeStr)

        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, gy)
        cal.set(Calendar.MONTH, gm - 1)
        cal.set(Calendar.DAY_OF_MONTH, gd)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Apply days offset
        cal.add(Calendar.DAY_OF_MONTH, daysOffset)
        return cal.timeInMillis
    }

    /**
     * Schedules all enabled reminders for a specific installment according to current settings.
     */
    suspend fun scheduleRemindersForInstallment(installment: Installment, settings: AcademySettings) = withContext(Dispatchers.IO) {
        if (installment.isPaid || installment.dueDateShamsi.isBlank()) return@withContext

        val db = AppDatabase.getDatabase(context)
        val reminderDao = db.reminderDao()

        val rules = listOf(
            Triple(-7, settings.reminder7DaysEnabled, settings.reminder7DaysTime),
            Triple(-3, settings.reminder3DaysEnabled, settings.reminder3DaysTime),
            Triple(-1, settings.reminder1DayEnabled, settings.reminder1DayTime),
            Triple(0, settings.reminderDueDayEnabled, settings.reminderDueDayTime),
            Triple(1, settings.reminder1DayAfterEnabled, settings.reminder1DayAfterTime),
            Triple(3, settings.reminder3DaysAfterEnabled, settings.reminder3DaysAfterTime)
        )

        val now = System.currentTimeMillis()

        for ((offset, isEnabled, timeStr) in rules) {
            if (!isEnabled) continue

            val triggerEpoch = calculateTargetTriggerEpoch(installment.dueDateShamsi, offset, timeStr)

            // Do not schedule if it's already in the past
            if (triggerEpoch <= now) continue

            // Check if reminder already exists in DB
            val existing = reminderDao.getReminderForInstallmentAndOffset(installment.id, offset)
            val reminderId = existing?.id ?: UUID.randomUUID().toString()

            val offsetLabel = when (offset) {
                -7 -> "۷ روز قبل"
                -3 -> "۳ روز قبل"
                -1 -> "۱ روز قبل (فردا)"
                0 -> "روز سررسید"
                1 -> "۱ روز بعد"
                3 -> "۳ روز بعد"
                else -> ""
            }

            val reminder = Reminder(
                id = reminderId,
                title = "یادآوری سررسید قسط ${installment.installmentNumber} ($offsetLabel)",
                type = "INSTALLMENT",
                studentId = installment.studentId,
                studentName = installment.studentName,
                installmentId = installment.id,
                targetDateEpoch = installment.dueDateEpoch,
                targetDateShamsi = installment.dueDateShamsi,
                scheduledTimeEpoch = triggerEpoch,
                triggerDaysOffset = offset,
                status = "SCHEDULED",
                isCompleted = false,
                channel = "IN_APP",
                messageBody = "قسط ${installment.installmentNumber} دوره ${installment.courseTitle} هنرجو ${installment.studentName}",
                createdAt = System.currentTimeMillis()
            )

            reminderDao.insertReminder(reminder)

            // Set system alarm
            setAlarm(reminder, installment, offset)
        }
    }

    /**
     * Sets exact Android Alarm for a reminder item.
     */
    fun setAlarm(reminder: Reminder, installment: Installment, offset: Int) {
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ReminderBroadcastReceiver.ACTION_TRIGGER_REMINDER
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, reminder.id)
            putExtra(ReminderBroadcastReceiver.EXTRA_STUDENT_ID, reminder.studentId)
            putExtra(ReminderBroadcastReceiver.EXTRA_STUDENT_NAME, reminder.studentName)
            putExtra(ReminderBroadcastReceiver.EXTRA_INSTALLMENT_ID, reminder.installmentId)
            putExtra(ReminderBroadcastReceiver.EXTRA_AMOUNT, installment.remainingAmount.takeIf { it > 0 } ?: installment.amount)
            putExtra(ReminderBroadcastReceiver.EXTRA_DUE_DATE_SHAMSI, reminder.targetDateShamsi)
            putExtra(ReminderBroadcastReceiver.EXTRA_OFFSET, offset)
            putExtra(ReminderBroadcastReceiver.EXTRA_TITLE, reminder.title)
        }

        val requestCode = (reminder.id.hashCode() and 0x7FFFFFFF)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.scheduledTimeEpoch,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    reminder.scheduledTimeEpoch,
                    pendingIntent
                )
            }
            Log.d("ReminderScheduler", "Successfully scheduled alarm for ${reminder.studentName} at ${reminder.scheduledTimeEpoch}")
        } catch (e: Exception) {
            Log.e("ReminderScheduler", "Failed to schedule alarm: ${e.message}", e)
        }
    }

    /**
     * Cancels an individual scheduled reminder alarm.
     */
    suspend fun cancelReminder(reminderId: String) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val reminder = db.reminderDao().getReminderById(reminderId) ?: return@withContext

        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ReminderBroadcastReceiver.ACTION_TRIGGER_REMINDER
        }
        val requestCode = (reminder.id.hashCode() and 0x7FFFFFFF)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        db.reminderDao().updateReminderStatus(
            id = reminderId,
            status = "CANCELLED",
            sendTime = "",
            error = "توسط مدیر لغو شد.",
            isCompleted = true
        )
    }

    /**
     * Triggers an immediate test reminder notification for an installment (for testing).
     */
    fun triggerImmediateTestReminder(installment: Installment, studentName: String) {
        val notificationHelper = NotificationHelper(context)
        val amountFormatted = PersianDateUtil.formatCurrency(installment.remainingAmount.takeIf { it > 0 } ?: installment.amount)
        val notifId = (installment.id.hashCode() and 0x7FFFFFFF)
        notificationHelper.showInstallmentDueNotification(
            notificationId = notifId,
            studentName = studentName,
            amountFormatted = amountFormatted,
            dueDateShamsi = installment.dueDateShamsi,
            dueCategory = "تست یادآوری قسط"
        )
    }

    /**
     * Schedules a real automatic Telegram reminder test using AlarmManager in background.
     * Uses real student data and reminder template.
     */
    suspend fun scheduleAutomaticTelegramTestReminder(
        student: com.example.data.model.Student,
        targetEpoch: Long,
        templateType: String = "REMINDER_3_DAYS",
        customAmount: Long = 2500000L,
        dueDateShamsi: String = PersianDateUtil.currentShamsiString()
    ): Reminder = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val reminderDao = db.reminderDao()

        val reminderId = UUID.randomUUID().toString()
        val reminder = Reminder(
            id = reminderId,
            title = "🚀 تست ارسال خودکار تلگرام (${student.fullName})",
            type = "TELEGRAM_TEST",
            studentId = student.id,
            studentName = student.fullName,
            installmentId = "",
            targetDateEpoch = targetEpoch,
            targetDateShamsi = dueDateShamsi,
            scheduledTimeEpoch = targetEpoch,
            triggerDaysOffset = 0,
            status = "SCHEDULED",
            isCompleted = false,
            channel = "TELEGRAM",
            messageBody = "تست ارسال خودکار زمانبندی‌شده از طریق AlarmManager",
            createdAt = System.currentTimeMillis()
        )

        reminderDao.insertReminder(reminder)

        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ReminderBroadcastReceiver.ACTION_TRIGGER_REMINDER
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, reminder.id)
            putExtra(ReminderBroadcastReceiver.EXTRA_STUDENT_ID, student.id)
            putExtra(ReminderBroadcastReceiver.EXTRA_STUDENT_NAME, student.fullName)
            putExtra(ReminderBroadcastReceiver.EXTRA_INSTALLMENT_ID, "")
            putExtra(ReminderBroadcastReceiver.EXTRA_AMOUNT, customAmount)
            putExtra(ReminderBroadcastReceiver.EXTRA_DUE_DATE_SHAMSI, dueDateShamsi)
            putExtra(ReminderBroadcastReceiver.EXTRA_OFFSET, 0)
            putExtra(ReminderBroadcastReceiver.EXTRA_TITLE, reminder.title)
            putExtra(ReminderBroadcastReceiver.EXTRA_IS_TELEGRAM_TEST, true)
            putExtra(ReminderBroadcastReceiver.EXTRA_TEMPLATE_TYPE, templateType)
        }

        val requestCode = (reminder.id.hashCode() and 0x7FFFFFFF)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetEpoch,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    targetEpoch,
                    pendingIntent
                )
            }
            Log.d("ReminderScheduler", "Successfully scheduled test alarm for ${student.fullName} at $targetEpoch")
        } catch (e: Exception) {
            Log.e("ReminderScheduler", "Failed to schedule test alarm: ${e.message}", e)
        }

        reminder
    }

    /**
     * Reschedules all pending reminders across all active unpaid installments.
     */
    suspend fun rescheduleAllPendingReminders() = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val settings = db.settingsDao().getSettings().firstOrNull() ?: AcademySettings()
        val unpaidInstallments = db.installmentDao().getAllInstallments().firstOrNull()?.filter { !it.isPaid } ?: emptyList()

        for (installment in unpaidInstallments) {
            scheduleRemindersForInstallment(installment, settings)
        }
    }
}
