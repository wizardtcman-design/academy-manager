package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "academy_settings")
data class AcademySettings(
    @PrimaryKey val id: String = "current_settings",
    val academyName: String = "آموزشگاه کامپیوتر پیشگامان",
    val phone: String = "۰۲۱-۸۸۸۸۹۹۹۹",
    val address: String = "تهران، میدان انقلاب، خیابان کارگر شمالی، پلاک ۱۲",
    val logoUrl: String = "",
    val currencyUnit: String = "تومان",
    val reminderDaysBefore: Int = 3,
    val smsTemplate: String = "هنرجوی گرامی {نام}، سررسید قسط دوره {دوره} به مبلغ {مبلغ} در تاریخ {تاریخ} می‌باشد.",
    // Telegram Bot configuration
    val telegramBotToken: String = "",
    val telegramBotUsername: String = "",
    val telegramEnabled: Boolean = false,
    val telegramWorkerUrl: String = "",
    val telegramApiSecret: String = "",
    val telegramUseWorker: Boolean = false,
    // Rubika configuration
    val rubikaEnabled: Boolean = false,
    val rubikaBotToken: String = "",
    // Automation flags & Individual Send Times & Admin Notification Templates
    val reminder7DaysEnabled: Boolean = true,
    val reminder7DaysTime: String = "10:00",
    val reminder7DaysAdminTitle: String = "🔔 یادآوری ۷ روز قبل: {studentName}",
    val reminder7DaysAdminTemplate: String = "یادآوری: ۷ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({courseName})",

    val reminder3DaysEnabled: Boolean = true,
    val reminder3DaysTime: String = "10:00",
    val reminder3DaysAdminTitle: String = "🔔 یادآوری ۳ روز قبل: {studentName}",
    val reminder3DaysAdminTemplate: String = "یادآوری: ۳ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} باقی مانده است.",

    val reminder1DayEnabled: Boolean = true,
    val reminder1DayTime: String = "10:00",
    val reminder1DayAdminTitle: String = "⏰ یادآوری قسط فردا: {studentName}",
    val reminder1DayAdminTemplate: String = "یادآوری: فردا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({dueDate}) است.",

    val reminderDueDayEnabled: Boolean = true,
    val reminderDueDayTime: String = "09:00",
    val reminderDueDayAdminTitle: String = "🚨 سررسید قسط امروز: {studentName}",
    val reminderDueDayAdminTemplate: String = "یادآوری فوری: قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} امروز سررسید می‌شود.",

    val reminder1DayAfterEnabled: Boolean = true,
    val reminder1DayAfterTime: String = "11:00",
    val reminder1DayAfterAdminTitle: String = "⚠️ هشدار ۱ روز دیرکرد: {studentName}",
    val reminder1DayAfterAdminTemplate: String = "هشدار دیرکرد: ۱ روز از موعد قسط {installmentNumber} هنرجو {studentName} گذشته است. مانده: {remainingDebt}",

    val reminder3DaysAfterEnabled: Boolean = true,
    val reminder3DaysAfterTime: String = "11:00",
    val reminder3DaysAfterAdminTitle: String = "⚠️ اخطار ۳ روز معوقه: {studentName}",
    val reminder3DaysAfterAdminTemplate: String = "اخطار معوقه: قسط {installmentNumber} هنرجو {studentName} دوره {courseName} به مبلغ {amount} معوق شده است.",

    val courseEndReminderEnabled: Boolean = true,
    val courseEndReminderTime: String = "10:00",
    // Appearance / Theme
    val themeName: String = "BLUE", // BLUE, GREEN, PURPLE, ORANGE, NAVY
    val themeMode: String = "SYSTEM", // LIGHT, DARK, SYSTEM
    // Security & App Lock & Manager Auth
    val managerUsername: String = "admin",
    val appLockEnabled: Boolean = false,
    val appLockPin: String = "",
    val appLockTimeoutMinutes: Int = 5,
    // Backup settings
    val lastBackupEpoch: Long = 0L,
    val autoBackupFrequency: String = "WEEKLY", // DAILY, WEEKLY, MONTHLY
    // Custom expense categories
    val customExpenseCategories: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isTelegramConfigured: Boolean
        get() = if (telegramUseWorker) telegramWorkerUrl.isNotBlank() else telegramBotToken.isNotBlank()

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "academyName" to academyName,
        "phone" to phone,
        "address" to address,
        "logoUrl" to logoUrl,
        "currencyUnit" to currencyUnit,
        "reminderDaysBefore" to reminderDaysBefore,
        "smsTemplate" to smsTemplate,
        "telegramBotToken" to telegramBotToken,
        "telegramBotUsername" to telegramBotUsername,
        "telegramEnabled" to telegramEnabled,
        "telegramWorkerUrl" to telegramWorkerUrl,
        "telegramApiSecret" to telegramApiSecret,
        "telegramUseWorker" to telegramUseWorker,
        "rubikaEnabled" to rubikaEnabled,
        "rubikaBotToken" to rubikaBotToken,
        "reminder7DaysEnabled" to reminder7DaysEnabled,
        "reminder7DaysTime" to reminder7DaysTime,
        "reminder7DaysAdminTitle" to reminder7DaysAdminTitle,
        "reminder7DaysAdminTemplate" to reminder7DaysAdminTemplate,
        "reminder3DaysEnabled" to reminder3DaysEnabled,
        "reminder3DaysTime" to reminder3DaysTime,
        "reminder3DaysAdminTitle" to reminder3DaysAdminTitle,
        "reminder3DaysAdminTemplate" to reminder3DaysAdminTemplate,
        "reminder1DayEnabled" to reminder1DayEnabled,
        "reminder1DayTime" to reminder1DayTime,
        "reminder1DayAdminTitle" to reminder1DayAdminTitle,
        "reminder1DayAdminTemplate" to reminder1DayAdminTemplate,
        "reminderDueDayEnabled" to reminderDueDayEnabled,
        "reminderDueDayTime" to reminderDueDayTime,
        "reminderDueDayAdminTitle" to reminderDueDayAdminTitle,
        "reminderDueDayAdminTemplate" to reminderDueDayAdminTemplate,
        "reminder1DayAfterEnabled" to reminder1DayAfterEnabled,
        "reminder1DayAfterTime" to reminder1DayAfterTime,
        "reminder1DayAfterAdminTitle" to reminder1DayAfterAdminTitle,
        "reminder1DayAfterAdminTemplate" to reminder1DayAfterAdminTemplate,
        "reminder3DaysAfterEnabled" to reminder3DaysAfterEnabled,
        "reminder3DaysAfterTime" to reminder3DaysAfterTime,
        "reminder3DaysAfterAdminTitle" to reminder3DaysAfterAdminTitle,
        "reminder3DaysAfterAdminTemplate" to reminder3DaysAfterAdminTemplate,
        "courseEndReminderEnabled" to courseEndReminderEnabled,
        "courseEndReminderTime" to courseEndReminderTime,
        "themeName" to themeName,
        "themeMode" to themeMode,
        "managerUsername" to managerUsername,
        "appLockEnabled" to appLockEnabled,
        "appLockPin" to appLockPin,
        "appLockTimeoutMinutes" to appLockTimeoutMinutes,
        "lastBackupEpoch" to lastBackupEpoch,
        "autoBackupFrequency" to autoBackupFrequency,
        "customExpenseCategories" to customExpenseCategories,
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): AcademySettings {
            return AcademySettings(
                id = map["id"] as? String ?: "current_settings",
                academyName = map["academyName"] as? String ?: "آموزشگاه کامپیوتر پیشگامان",
                phone = map["phone"] as? String ?: "",
                address = map["address"] as? String ?: "",
                logoUrl = map["logoUrl"] as? String ?: "",
                currencyUnit = map["currencyUnit"] as? String ?: "تومان",
                reminderDaysBefore = (map["reminderDaysBefore"] as? Number)?.toInt() ?: 3,
                smsTemplate = map["smsTemplate"] as? String ?: "",
                telegramBotToken = map["telegramBotToken"] as? String ?: "",
                telegramBotUsername = map["telegramBotUsername"] as? String ?: "",
                telegramEnabled = map["telegramEnabled"] as? Boolean ?: false,
                telegramWorkerUrl = map["telegramWorkerUrl"] as? String ?: "",
                telegramApiSecret = map["telegramApiSecret"] as? String ?: "",
                telegramUseWorker = map["telegramUseWorker"] as? Boolean ?: false,
                rubikaEnabled = map["rubikaEnabled"] as? Boolean ?: false,
                rubikaBotToken = map["rubikaBotToken"] as? String ?: "",
                reminder7DaysEnabled = map["reminder7DaysEnabled"] as? Boolean ?: true,
                reminder7DaysTime = map["reminder7DaysTime"] as? String ?: "10:00",
                reminder7DaysAdminTitle = map["reminder7DaysAdminTitle"] as? String ?: "🔔 یادآوری ۷ روز قبل: {studentName}",
                reminder7DaysAdminTemplate = map["reminder7DaysAdminTemplate"] as? String ?: "یادآوری: ۷ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({courseName})",
                reminder3DaysEnabled = map["reminder3DaysEnabled"] as? Boolean ?: true,
                reminder3DaysTime = map["reminder3DaysTime"] as? String ?: "10:00",
                reminder3DaysAdminTitle = map["reminder3DaysAdminTitle"] as? String ?: "🔔 یادآوری ۳ روز قبل: {studentName}",
                reminder3DaysAdminTemplate = map["reminder3DaysAdminTemplate"] as? String ?: "یادآوری: ۳ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} باقی مانده است.",
                reminder1DayEnabled = map["reminder1DayEnabled"] as? Boolean ?: true,
                reminder1DayTime = map["reminder1DayTime"] as? String ?: "10:00",
                reminder1DayAdminTitle = map["reminder1DayAdminTitle"] as? String ?: "⏰ یادآوری قسط فردا: {studentName}",
                reminder1DayAdminTemplate = map["reminder1DayAdminTemplate"] as? String ?: "یادآوری: فردا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({dueDate}) است.",
                reminderDueDayEnabled = map["reminderDueDayEnabled"] as? Boolean ?: true,
                reminderDueDayTime = map["reminderDueDayTime"] as? String ?: "09:00",
                reminderDueDayAdminTitle = map["reminderDueDayAdminTitle"] as? String ?: "🚨 سررسید قسط امروز: {studentName}",
                reminderDueDayAdminTemplate = map["reminderDueDayAdminTemplate"] as? String ?: "یادآوری فوری: قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} امروز سررسید می‌شود.",
                reminder1DayAfterEnabled = map["reminder1DayAfterEnabled"] as? Boolean ?: true,
                reminder1DayAfterTime = map["reminder1DayAfterTime"] as? String ?: "11:00",
                reminder1DayAfterAdminTitle = map["reminder1DayAfterAdminTitle"] as? String ?: "⚠️ هشدار ۱ روز دیرکرد: {studentName}",
                reminder1DayAfterAdminTemplate = map["reminder1DayAfterAdminTemplate"] as? String ?: "هشدار دیرکرد: ۱ روز از موعد قسط {installmentNumber} هنرجو {studentName} گذشته است. مانده: {remainingDebt}",
                reminder3DaysAfterEnabled = map["reminder3DaysAfterEnabled"] as? Boolean ?: true,
                reminder3DaysAfterTime = map["reminder3DaysAfterTime"] as? String ?: "11:00",
                reminder3DaysAfterAdminTitle = map["reminder3DaysAfterAdminTitle"] as? String ?: "⚠️ اخطار ۳ روز معوقه: {studentName}",
                reminder3DaysAfterAdminTemplate = map["reminder3DaysAfterAdminTemplate"] as? String ?: "اخطار معوقه: قسط {installmentNumber} هنرجو {studentName} دوره {courseName} به مبلغ {amount} معوق شده است.",
                courseEndReminderEnabled = map["courseEndReminderEnabled"] as? Boolean ?: true,
                courseEndReminderTime = map["courseEndReminderTime"] as? String ?: "10:00",
                themeName = map["themeName"] as? String ?: "BLUE",
                themeMode = map["themeMode"] as? String ?: "SYSTEM",
                managerUsername = map["managerUsername"] as? String ?: "admin",
                appLockEnabled = map["appLockEnabled"] as? Boolean ?: false,
                appLockPin = map["appLockPin"] as? String ?: "",
                appLockTimeoutMinutes = (map["appLockTimeoutMinutes"] as? Number)?.toInt() ?: 5,
                lastBackupEpoch = (map["lastBackupEpoch"] as? Number)?.toLong() ?: 0L,
                autoBackupFrequency = map["autoBackupFrequency"] as? String ?: "WEEKLY",
                customExpenseCategories = map["customExpenseCategories"] as? String ?: "",
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
