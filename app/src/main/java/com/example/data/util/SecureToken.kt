package com.example.data.util

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Unguessable, expiring tokens for student links and contract signing.
 * Only the SHA-256 of a token ever reaches the server or the database: a leaked
 * database row cannot be used to open someone else's contract.
 */
object SecureToken {
    private val random = SecureRandom()

    /** 32 random bytes, URL-safe base64 without padding (43 chars). */
    fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun fileSha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sb = StringBuilder()
        for (b in digest.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** Unambiguous human code (no 0/O/1/I) for phone verification. */
    fun numericCode(digits: Int = 6): String {
        val sb = StringBuilder()
        repeat(digits) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }
}
