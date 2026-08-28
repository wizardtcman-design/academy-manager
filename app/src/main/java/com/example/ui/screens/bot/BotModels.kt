package com.example.ui.screens.bot

data class BotInteractiveButton(
    val id: String,
    val defaultTitle: String,
    var customTitle: String,
    val iconEmoji: String,
    var isEnabled: Boolean,
    val description: String,
    val sampleResponse: String
)

data class BotAntiSpamConfig(
    val maxPerHour: Int = 50,
    val delayBetweenMessagesSeconds: Int = 2,
    val silentHoursEnabled: Boolean = true,
    val silentHoursStart: String = "22:00",
    val silentHoursEnd: String = "08:00",
    val autoRetryFailed: Boolean = true
)

enum class BotTabSection(val title: String, val index: Int) {
    DASHBOARD("داشبورد", 0),
    STUDENTS("هنرجویان", 1),
    MESSAGES("پیام‌ها", 2),
    BUTTONS("کلیدهای ربات", 3),
    REMINDERS("یادآوری‌ها", 4),
    LOGS("لاگ پیام‌ها", 5),
    SETTINGS("تنظیمات", 6)
}
