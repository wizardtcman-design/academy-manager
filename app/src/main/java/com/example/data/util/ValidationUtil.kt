package com.example.data.util

/** Validators for Iranian national code and mobile numbers. */
object ValidationUtil {

    /** Checks the 10-digit national code with its official check digit. */
    fun isValidNationalCode(input: String): Boolean {
        val code = input.trim().filter { it.isDigit() }.map { toEnglishDigit(it) }.joinToString("")
        if (code.length != 10) return false
        if (code.toSet().size == 1) return false // 0000000000, 1111111111, ...
        val check = code[9] - '0'
        var sum = 0
        for (i in 0 until 9) {
            sum += (code[i] - '0') * (10 - i)
        }
        val remainder = sum % 11
        return (remainder < 2 && check == remainder) || (remainder >= 2 && check == 11 - remainder)
    }

    /** Iranian mobile numbers: 09xxxxxxxxx (also accepts +98 and Persian digits). */
    fun isValidMobile(input: String): Boolean {
        var digits = input.trim().filter { it.isDigit() }.map { toEnglishDigit(it) }.joinToString("")
        if (digits.startsWith("98") && digits.length == 12) digits = "0" + digits.substring(2)
        if (digits.startsWith("9") && digits.length == 10) digits = "0$digits"
        return Regex("^09\\d{9}$").matches(digits)
    }

    /** Normalizes a phone for display/storage: keeps digits and a leading +. */
    fun normalizePhone(input: String): String {
        val hasPlus = input.trim().startsWith("+")
        val digits = input.filter { it.isDigit() }.map { toEnglishDigit(it) }.joinToString("")
        return if (hasPlus) "+$digits" else digits
    }

    private fun toEnglishDigit(c: Char): Char = when (c) {
        in '۰'..'۹' -> '0' + (c - '۰')
        in '٠'..'٩' -> '0' + (c - '٠')
        else -> c
    }
}
