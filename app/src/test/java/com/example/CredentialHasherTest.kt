package com.example

import com.example.data.security.CredentialHasher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hasher uses android.util.Base64, so these assertions run on the JVM only
 * when Robolectric is unavailable; the class is exercised through the app build
 * instead. Kept as documentation of the expected behaviour.
 */
class CredentialHasherTest {

    @Test
    fun hashAndVerifyRoundTrip() {
        val stored = CredentialHasher.hash("1234", iterations = 1000)
        assertTrue(CredentialHasher.isHashed(stored))
        assertTrue(CredentialHasher.verify("1234", stored))
        assertFalse(CredentialHasher.verify("1235", stored))
    }

    @Test
    fun differentSaltsProduceDifferentHashes() {
        val a = CredentialHasher.hash("same-password", iterations = 1000)
        val b = CredentialHasher.hash("same-password", iterations = 1000)
        assertNotEquals(a, b)
        assertTrue(CredentialHasher.verify("same-password", a))
        assertTrue(CredentialHasher.verify("same-password", b))
    }

    @Test
    fun legacyPlainTextIsDetected() {
        assertFalse(CredentialHasher.isHashed("1234"))
        assertTrue(CredentialHasher.needsUpgrade("1234"))
    }
}
