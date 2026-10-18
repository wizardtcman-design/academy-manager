package com.example.service.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.AcademyNotification
import com.example.data.util.PersianDateUtil
import com.example.service.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling scheduled reminders from AlarmManager and device boot.
 */
class ReminderBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("ReminderReceiver", "Received intent action: $action")

        val shouldReschedule = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        if (shouldReschedule) {
            // Restore every pending alarm after reboot, app update, clock/timezone change
            // (AlarmManager drops all alarms on these events).
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val reminderScheduler = com.example.service.ReminderScheduler(context)
                    reminderScheduler.rescheduleAllPendingReminders()
                } catch (e: Exception) {
                    Log.e("ReminderReceiver", "Error rescheduling alarms ($action): ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        if (action == ACTION_TRIGGER_REMINDER) {
            val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
            val studentName = intent.getStringExtra(EXTRA_STUDENT_NAME) ?: "هنرجو"
            val studentId = intent.getStringExtra(EXTRA_STUDENT_ID) ?: ""
            val installmentId = intent.getStringExtra(EXTRA_INSTALLMENT_ID) ?: ""
            val amount = intent.getLongExtra(EXTRA_AMOUNT, 0L)
            val dueDateShamsi = intent.getStringExtra(EXTRA_DUE_DATE_SHAMSI) ?: ""
            val offset = intent.getIntExtra(EXTRA_OFFSET, 0)
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "یادآوری سررسید قسط"
            val isTelegramTest = intent.getBooleanExtra(EXTRA_IS_TELEGRAM_TEST, false)
            val templateTypeParam = intent.getStringExtra(EXTRA_TEMPLATE_TYPE) ?: ""

            val dueCategory = when (offset) {
                -7 -> "۷ روز آینده"
                -3 -> "۳ روز آینده"
                -1 -> "فردا"
                0 -> "امروز"
                1 -> "۱ روز دیرکرد"
                3 -> "۳ روز دیرکرد"
                else -> "سررسید"
            }

            val amountFormatted = PersianDateUtil.formatCurrency(amount)

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val reminderDao = db.reminderDao()
                    val notificationDao = db.notificationDao()
                    val installmentDao = db.installmentDao()
                    val settingsDao = db.settingsDao()
                    val studentDao = db.studentDao()
                    val messageLogDao = db.messageLogDao()
                    val messageTemplateDao = db.messageTemplateDao()

                    val currentSettings = settingsDao.getSettingsDirect() ?: com.example.data.model.AcademySettings()

                    var courseTitle = ""
                    var instNumber = 1
                    var remainingDebt = amount

                    // Check if installment is already paid (skip check if isTelegramTest)
                    if (installmentId.isNotBlank()) {
                        val inst = installmentDao.getInstallmentById(installmentId)
                        if (inst != null) {
                            if (inst.isPaid && !isTelegramTest) {
                                // Already paid, mark reminder as cancelled / completed
                                reminderDao.updateReminderStatus(
                                    id = reminderId,
                                    status = "CANCELLED",
                                    sendTime = PersianDateUtil.currentShamsiFullString() + " ساعت " + PersianDateUtil.currentTimeString(),
                                    error = "قسط قبلاً تسویه شده بود.",
                                    isCompleted = true
                                )
                                return@launch
                            }
                            courseTitle = inst.courseTitle
                            instNumber = inst.installmentNumber
                            remainingDebt = if (inst.remainingAmount > 0) inst.remainingAmount else inst.amount
                        }
                    }

                    // Choose appropriate admin notification template based on offset
                    val templateText = when (offset) {
                        -7 -> currentSettings.reminder7DaysAdminTemplate
                        -3 -> currentSettings.reminder3DaysAdminTemplate
                        -1 -> currentSettings.reminder1DayAdminTemplate
                        0 -> currentSettings.reminderDueDayAdminTemplate
                        1 -> currentSettings.reminder1DayAfterAdminTemplate
                        3 -> currentSettings.reminder3DaysAfterAdminTemplate
                        else -> "یادآوری: موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({dueDate})"
                    }

                    val titleTemplateText = when (offset) {
                        -7 -> currentSettings.reminder7DaysAdminTitle
                        -3 -> currentSettings.reminder3DaysAdminTitle
                        -1 -> currentSettings.reminder1DayAdminTitle
                        0 -> currentSettings.reminderDueDayAdminTitle
                        1 -> currentSettings.reminder1DayAfterAdminTitle
                        3 -> currentSettings.reminder3DaysAfterAdminTitle
                        else -> "📅 سررسید قسط ($dueCategory): {studentName}"
                    }

                    val formattedNotificationMessage = com.example.data.util.FinanceUtil.fillAdminNotificationTemplate(
                        templateText = templateText,
                        studentName = studentName,
                        courseTitle = courseTitle,
                        installmentNumber = instNumber,
                        installmentAmount = amount,
                        remainingDebt = remainingDebt,
                        dueDateShamsi = dueDateShamsi,
                        settings = currentSettings
                    )

                    val notifTitle = com.example.data.util.FinanceUtil.fillAdminNotificationTemplate(
                        templateText = titleTemplateText,
                        studentName = studentName,
                        courseTitle = courseTitle,
                        installmentNumber = instNumber,
                        installmentAmount = amount,
                        remainingDebt = remainingDebt,
                        dueDateShamsi = dueDateShamsi,
                        settings = currentSettings
                    )

                    // Trigger Android Notification
                    val notificationHelper = NotificationHelper(context)
                    val notifId = (reminderId.hashCode() and 0x7FFFFFFF)
                    notificationHelper.showInstallmentDueNotification(
                        notificationId = notifId,
                        studentName = studentName,
                        amountFormatted = amountFormatted,
                        dueDateShamsi = dueDateShamsi,
                        dueCategory = if (isTelegramTest) "تست ارسال خودکار" else dueCategory,
                        customTitle = if (isTelegramTest) "🚀 تست ارسال خودکار زمانبندی شده تلگرام" else notifTitle,
                        customMessage = formattedNotificationMessage
                    )

                    val nowShamsiTime = "${PersianDateUtil.currentShamsiFullString()} ساعت ${PersianDateUtil.currentTimeString()}"

                    // If it is a Telegram Test (or automatic Telegram reminder), execute real Telegram sending via MessagingManager
                    var executionError = ""
                    if (isTelegramTest && studentId.isNotBlank()) {
                        val student = studentDao.getStudentById(studentId)
                        if (student != null) {
                            val messagingManager = com.example.service.messaging.MessagingManager(
                                context = context,
                                messageTemplateDao = messageTemplateDao,
                                messageLogDao = messageLogDao,
                                currentSettings = currentSettings
                            )

                            val resolvedTemplateType = if (templateTypeParam.isNotBlank()) {
                                templateTypeParam
                            } else {
                                when (offset) {
                                    -7 -> "REMINDER_7_DAYS"
                                    -3 -> "REMINDER_3_DAYS"
                                    -1 -> "REMINDER_1_DAY"
                                    0 -> "REMINDER_DUE_DAY"
                                    1, 3 -> "REMINDER_OVERDUE"
                                    else -> "REMINDER_3_DAYS"
                                }
                            }

                            val telegramMessageText = messagingManager.prepareMessage(
                                templateType = resolvedTemplateType,
                                student = student,
                                courseTitle = courseTitle.ifBlank { "دوره آموزشی" },
                                installmentNumber = instNumber,
                                installmentAmount = amount,
                                paidAmount = 0L,
                                remainingDebt = remainingDebt,
                                dueDateShamsi = dueDateShamsi.ifBlank { PersianDateUtil.currentShamsiString() }
                            )

                            val (sendResult, _) = messagingManager.sendMessage(
                                student = student,
                                messageText = telegramMessageText,
                                targetChannel = "TELEGRAM"
                            )

                            when (sendResult) {
                                is com.example.service.messaging.MessageSendResult.Success -> {
                                    executionError = ""
                                }
                                is com.example.service.messaging.MessageSendResult.Failed -> {
                                    executionError = sendResult.error
                                }
                                is com.example.service.messaging.MessageSendResult.NotConnected -> {
                                    executionError = sendResult.reason
                                }
                                is com.example.service.messaging.MessageSendResult.ManualActionRequired -> {
                                    executionError = "اتصال تلگرام فعال نیست یا Chat ID یافت نشد."
                                }
                            }
                        } else {
                            executionError = "هنرجوی مورد نظر در پایگاه داده یافت نشد."
                        }
                    }

                    // Update database reminder
                    val finalStatus = if (executionError.isEmpty()) "SENT" else "FAILED"
                    reminderDao.updateReminderStatus(
                        id = reminderId,
                        status = finalStatus,
                        sendTime = nowShamsiTime,
                        error = executionError,
                        isCompleted = true
                    )

                    // Insert in-app notification
                    val inAppNotif = AcademyNotification(
                        title = if (isTelegramTest) "🚀 تست ارسال خودکار زمانبندی شده: $studentName" else notifTitle,
                        message = if (executionError.isEmpty()) formattedNotificationMessage else "$formattedNotificationMessage\nخطا در ارسال تلگرام: $executionError",
                        type = if (isTelegramTest) "TELEGRAM_TEST" else "INSTALLMENT_DUE",
                        dateShamsi = nowShamsiTime,
                        dateEpoch = System.currentTimeMillis()
                    )
                    notificationDao.insertNotification(inAppNotif)

                } catch (e: Exception) {
                    com.example.data.util.SafeLogger.e("ReminderReceiver", "Error executing reminder: ${e.message}", e)
                    try {
                        val db = AppDatabase.getDatabase(context)
                        db.reminderDao().updateReminderStatus(
                            id = reminderId,
                            status = "FAILED",
                            sendTime = "",
                            error = e.message ?: "خطای ناشناخته در اجرای زمانبندی",
                            isCompleted = false
                        )
                    } catch (_: Exception) {}
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER_REMINDER = "com.example.academymanager.ACTION_TRIGGER_REMINDER"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_STUDENT_ID = "extra_student_id"
        const val EXTRA_STUDENT_NAME = "extra_student_name"
        const val EXTRA_INSTALLMENT_ID = "extra_installment_id"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_DUE_DATE_SHAMSI = "extra_due_date_shamsi"
        const val EXTRA_OFFSET = "extra_offset"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IS_TELEGRAM_TEST = "extra_is_telegram_test"
        const val EXTRA_TEMPLATE_TYPE = "extra_template_type"
    }
}
