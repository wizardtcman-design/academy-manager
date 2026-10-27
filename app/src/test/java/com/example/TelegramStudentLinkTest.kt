package com.example

import com.example.data.model.Student
import com.example.service.messaging.TelegramStudentLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.security.SecureRandom

/**
 * Stage 2 secure student linking:
 * the /start payload is a one-time token whose ONLY server-side trace is its
 * SHA-256, and the deep link is built exactly as the Worker webhook expects.
 */
class TelegramStudentLinkTest {

    private fun freshToken(): String {
        // Same shape the app mints at runtime (SecureToken uses android.util.Base64,
        // which is unavailable in JVM tests): 32 random bytes, URL-safe, unpadded.
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    @Test
    fun tokenShapeMatchesWorkerWebhookRegex() {
        // The Worker accepts /start payloads matching [A-Za-z0-9_-]{20,64}.
        val token = freshToken()
        assertEquals(43, token.length)
        assertTrue(token.matches(Regex("^[A-Za-z0-9_-]{20,64}$")))
    }

    @Test
    fun linkHashIsStableSha256AndNeverTheRawToken() {
        val token = "AbCdEf-_1234567890-abcdefghijklmnopqrstuvwxyz_XY"
        val h1 = TelegramStudentLink.linkHash(token)
        val h2 = TelegramStudentLink.linkHash(token)
        assertEquals(h1, h2)
        assertTrue(h1.matches(Regex("^[0-9a-f]{64}$")))
        assertNotEquals(token, h1)
        assertFalse(h1.contains(token))
    }

    @Test
    fun deepLinkUsesTokenAndStripsAt() {
        val link = TelegramStudentLink.deepLink("@my_academy-bot", "tok-1234567890123456")
        assertEquals("https://t.me/my_academy-bot?start=tok-1234567890123456", link)
    }

    @Test
    fun deepLinkIsEmptyInsteadOfBrokenWhenUsernameOrTokenMissing() {
        assertEquals("", TelegramStudentLink.deepLink("", "tok-1234567890123456"))
        assertEquals("", TelegramStudentLink.deepLink("@bot", ""))
        // The old broken fallback must never come back:
        assertFalse(TelegramStudentLink.deepLink("", "tok").startsWith("https://t.me/?start="))
    }

    @Test
    fun legacyDeepLinkKeepsRawIdOnlyForAppPollMode() {
        assertEquals(
            "https://t.me/bot?start=student-1",
            TelegramStudentLink.legacyDeepLink("@bot", "student-1")
        )
        assertEquals("", TelegramStudentLink.legacyDeepLink("", "student-1"))
    }

    @Test
    fun welcomeTextContainsNameAcademyAndLink() {
        val link = TelegramStudentLink.deepLink("academy_bot", "tok-1234567890123456")
        val text = TelegramStudentLink.welcomeText("آموزشگاه نمونه", "علی رضایی", link)
        assertTrue(text.contains("علی رضایی"))
        assertTrue(text.contains("آموزشگاه نمونه"))
        assertTrue(text.contains("https://t.me/academy_bot?start=tok-1234567890123456"))
        // One-time wording present so the student knows not to forward it.
        assertTrue(text.contains("یک بار"))
    }

    @Test
    fun welcomeTextFallsBackToSafeDefaults() {
        val text = TelegramStudentLink.welcomeText("", "", "https://t.me/b?start=tok")
        assertTrue(text.contains("هنرجو"))
        assertTrue(text.contains("آموزشگاه"))
    }

    @Test
    fun tokenOfPrefersStoredToken() {
        val withToken = Student(firstName = "علی", lastName = "رضایی", telegramLinkToken = "tok-1234567890123456")
        assertEquals("tok-1234567890123456", TelegramStudentLink.tokenOf(withToken))
        val withoutToken = Student(firstName = "ب")
        assertEquals("", TelegramStudentLink.tokenOf(withoutToken))
    }

    @Test
    fun isValidSignUrlAcceptsRealSignLinks() {
        val good = "https://worker.example.com/sign?s=Ab12Cd34Ef56Gh78Ij90Kl12"
        assertTrue(TelegramStudentLink.isValidSignUrl(good))
        assertEquals("Ab12Cd34Ef56Gh78Ij90Kl12", TelegramStudentLink.signTokenOf(good))
        assertTrue(TelegramStudentLink.isValidSignUrl("http://localhost:8787/sign?s=abcDEF123-_456789012"))
    }

    @Test
    fun isValidSignUrlRejectsPlaceholdersAndGarbage() {
        // the legacy placeholder the status endpoint once returned
        assertFalse(TelegramStudentLink.isValidSignUrl("https://worker.example.com/sign?s=link-in-app"))
        // no token at all / short token / wrong host path / non-url
        assertFalse(TelegramStudentLink.isValidSignUrl("https://worker.example.com/sign?s="))
        assertFalse(TelegramStudentLink.isValidSignUrl("https://worker.example.com/sign?s=short"))
        assertFalse(TelegramStudentLink.isValidSignUrl("https://worker.example.com/other?s=Ab12Cd34Ef56Gh78Ij90Kl12"))
        assertFalse(TelegramStudentLink.isValidSignUrl(""))
        assertFalse(TelegramStudentLink.isValidSignUrl("not a url"))
        assertEquals("", TelegramStudentLink.signTokenOf("https://worker.example.com/sign?s=short"))
    }
}
