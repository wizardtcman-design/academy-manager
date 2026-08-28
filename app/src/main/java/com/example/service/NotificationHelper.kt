package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity

/**
 * Local Notification Helper Architecture.
 * Ready for triggering reminders for:
 * - Today's installment due
 * - Tomorrow's installment due
 * - 3 days ahead installment due
 * - Overdue installment alerts
 * - New payment recorded
 * - Course completion
 * - Student follow-up
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_INSTALLMENTS = "channel_academy_installments"
        const val CHANNEL_PAYMENTS = "channel_academy_payments"
        const val CHANNEL_FOLLOWUPS = "channel_academy_followups"
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val installmentsChannel = NotificationChannel(
                CHANNEL_INSTALLMENTS,
                "سررسید اقساط و یادآوری شهریه",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان‌های مربوط به اقساط امروز، فردا و معوقه هنرجویان"
                enableVibration(true)
            }

            val paymentsChannel = NotificationChannel(
                CHANNEL_PAYMENTS,
                "دریافت پرداخت‌ها",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "اعلان‌های ثبت و تایید واریزی شهریه"
            }

            val followupsChannel = NotificationChannel(
                CHANNEL_FOLLOWUPS,
                "پیگیری‌ها و پایان دوره",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "اعلان‌های یادآوری پیگیری وضعیت هنرجو و اتمام دوره آموزشی"
            }

            manager.createNotificationChannel(installmentsChannel)
            manager.createNotificationChannel(paymentsChannel)
            manager.createNotificationChannel(followupsChannel)
        }
    }

    fun showInstallmentDueNotification(
        notificationId: Int,
        studentName: String,
        amountFormatted: String,
        dueDateShamsi: String,
        dueCategory: String, // "امروز", "فردا", "۳ روز آینده", "معوقه"
        customTitle: String = "",
        customMessage: String = ""
    ) {
        val title = if (customTitle.isNotBlank()) {
            customTitle
        } else {
            when (dueCategory) {
                "امروز" -> "🔔 سررسید قسط امروز: $studentName"
                "فردا" -> "⏰ یادآوری قسط فردا: $studentName"
                "معوقه" -> "⚠️ هشدار قسط معوقه: $studentName"
                else -> "📅 سررسید قسط ($dueCategory): $studentName"
            }
        }

        val message = if (customMessage.isNotBlank()) {
            customMessage
        } else {
            "مبلغ: $amountFormatted - تاریخ سررسید: $dueDateShamsi"
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_INSTALLMENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\nجهت بررسی وضعیت هنرجو و ثبت پرداخت لمس کنید."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Handled when notification permission is not yet granted
        }
    }
}
