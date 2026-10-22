package com.example.service.messaging

import com.example.data.model.Student
import com.example.data.util.SecureToken

/**
 * Stage 2 — secure student linking.
 *
 * The Telegram deep link payload is a ONE-TIME random token, never the raw
 * student id: a forwarded or guessed id must not hand somebody else's
 * financial data to another chat. The app keeps the raw token on the student
 * row (`Student.telegramLinkToken`); only its SHA-256 is pushed to the Worker
 * (`students.telegram_link_hash` via /api/sync), where the webhook burns the
 * token after one successful /start binding.
 *
 * Kept 100% JVM-pure so it is unit-testable without an Android device.
 */
object TelegramStudentLink {

    /** Server-side tokens (issued by /api/students/:id/telegram-link) live 30 days. */
    const val LINK_VALIDITY_DAYS = 30

    /** The ONLY form of the token that ever leaves the device. */
    fun linkHash(token: String): String = SecureToken.sha256Hex(token)

    /** Token of this student, or "" when none was generated yet. */
    fun tokenOf(student: Student): String = student.telegramLinkToken.trim()

    /**
     * https://t.me/<bot>?start=<token>. Returns "" when the bot username or the
     * token is missing — a broken `https://t.me/?start=...` link must never be
     * shown or sent.
     */
    fun deepLink(botUsername: String, token: String): String {
        val user = botUsername.trim().removePrefix("@")
        val clean = token.trim()
        if (user.isBlank() || clean.isBlank()) return ""
        return "https://t.me/$user?start=$clean"
    }

    /** Legacy fallback for app-poll academies before the first sync mints tokens. */
    fun legacyDeepLink(botUsername: String, studentId: String): String {
        val user = botUsername.trim().removePrefix("@")
        if (user.isBlank() || studentId.isBlank()) return ""
        return "https://t.me/$user?start=$studentId"
    }

    /** Persian welcome message for a newly registered student (SMS-ready, never auto-sent as SMS). */
    fun welcomeText(academyName: String, fullName: String, botLink: String): String {
        val name = fullName.trim().ifBlank { "هنرجو" }
        val academy = academyName.trim().ifBlank { "آموزشگاه" }
        return buildString {
            append("سلام $name عزیز! 👋\n\n")
            append("به خانواده $academy خوش آمدید. 🌱\n")
            append("برای دریافت اعلان‌های اقساط، رسیدها و قرارداد آموزشی خود در تلگرام، روی لینک زیر بزنید و Start را بزنید:\n\n")
            append(botLink)
            append("\n\n🔒 این لینک مخصوص شماست و فقط یک بار قابل استفاده است.")
        }
    }
}
