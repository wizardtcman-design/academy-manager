package com.example.data.util

import android.util.Log

/**
 * Centralized Safe Logger that sanitizes sensitive data (Tokens, Passwords, PINs, Bank Cards, API Keys)
 * before emitting to Android Logcat or Database logs.
 */
object SafeLogger {

    private const val TAG = "AcademyManager"

    fun d(tag: String = TAG, message: String) {
        Log.d(tag, sanitize(message))
    }

    fun i(tag: String = TAG, message: String) {
        Log.i(tag, sanitize(message))
    }

    fun w(tag: String = TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, sanitize(message), throwable)
        } else {
            Log.w(tag, sanitize(message))
        }
    }

    fun e(tag: String = TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, sanitize(message), throwable)
        } else {
            Log.e(tag, sanitize(message))
        }
    }

    /**
     * Masks sensitive tokens, passwords, 16-digit card numbers, and API keys.
     */
    fun sanitize(input: String): String {
        if (input.isBlank()) return input

        var text = input

        // Mask Telegram bot tokens (e.g., 123456789:ABCdefGHI...)
        text = text.replace(Regex("(\\d{8,12}):[A-Za-z0-9_-]{25,50}")) { match ->
            val prefix = match.value.substring(0, 4)
            "$prefix:************************"
        }

        // Mask 16-digit Card Numbers (e.g., 6037-9918-1234-5678 or 6037991812345678)
        text = text.replace(Regex("\\b(\\d{4})[- ]?(\\d{4})[- ]?(\\d{4})[- ]?(\\d{4})\\b")) { match ->
            val last4 = match.groupValues[4]
            "****-****-****-$last4"
        }

        // Mask PIN codes
        text = text.replace(Regex("(?i)(pin|password|token)=([^&\\s]+)")) { match ->
            "${match.groupValues[1]}=******"
        }

        // Mask Google/Firebase API keys
        text = text.replace(Regex("AIza[0-9A-Za-z-_]{35}")) { match ->
            "AIzaSy********************"
        }

        return text
    }
}
