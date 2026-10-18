package com.example.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password / PIN hashing based on PBKDF2-HMAC-SHA256.
 *
 * Stored format:  pbkdf2_sha256$<iterations>$<base64 salt>$<base64 hash>
 * A legacy value without the prefix is treated as an old plain-text value and
 * only used to build a hash on the next successful login.
 */
object CredentialHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val PREFIX = "pbkdf2_sha256"
    private const val SALT_BYTES = 16

    fun hash(secret: String, iterations: Int = ITERATIONS): String {
        if (secret.isBlank()) return ""
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(secret, salt, iterations)
        return "$PREFIX\$$iterations\$${encode(salt)}\$${encode(hash)}"
    }

    fun verify(secret: String, stored: String?): Boolean {
        if (secret.isBlank() || stored.isNullOrBlank()) return false
        return if (stored.startsWith("$PREFIX\$")) {
            val parts = stored.split('$')
            if (parts.size != 4) return false
            val iterations = parts[1].toIntOrNull() ?: return false
            val salt = decode(parts[2]) ?: return false
            val expected = decode(parts[3]) ?: return false
            MessageDigest.isEqual(pbkdf2(secret, salt, iterations), expected)
        } else {
            // Legacy plain-text value (old installs): compare, caller should re-hash
            secret == stored
        }
    }

    fun needsUpgrade(stored: String?): Boolean =
        !stored.isNullOrBlank() && !stored.startsWith("$PREFIX\$")

    fun isHashed(value: String?): Boolean = value?.startsWith("$PREFIX\$") == true

    private fun pbkdf2(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(value)
    } catch (_: Exception) {
        null
    }
}
