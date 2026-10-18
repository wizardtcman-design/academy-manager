package com.example.data.util

import java.text.DecimalFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Robust, highly accurate Jalali (Shamsi / Solar Hijri) date calculation and conversion algorithm.
 * Handles leap years, month date additions (31 days vs 30/29 days), and Persian string formatting.
 */
object PersianDateUtil {

    data class JalaliDate(
        val year: Int,
        val month: Int, // 1..12
        val day: Int    // 1..31
    ) {
        fun format(separator: String = "/"): String {
            val m = if (month < 10) "0$month" else "$month"
            val d = if (day < 10) "0$day" else "$day"
            return "$year$separator$m$separator$d"
        }

        fun formatPersianDigits(separator: String = "/"): String {
            return toPersianDigits(format(separator))
        }

        fun getMonthName(): String {
            return PersianDateUtil.getMonthName(month)
        }

        fun formatFullPersian(): String {
            return "${toPersianDigits(day.toString())} ${getMonthName()} ${toPersianDigits(year.toString())}"
        }

        /**
         * Adds N months to the Shamsi date, keeping the day intact or clamped to the max days in target month.
         * For example: 13 Shahrivar + 1 month -> 13 Mehr; + 2 months -> 13 Aban.
         */
        fun addMonths(monthsToAdd: Int): JalaliDate {
            var newMonth = month + monthsToAdd
            var newYear = year
            while (newMonth > 12) {
                newMonth -= 12
                newYear += 1
            }
            while (newMonth < 1) {
                newMonth += 12
                newYear -= 1
            }
            val maxDays = getDaysInMonth(newYear, newMonth)
            val newDay = if (day > maxDays) maxDays else day
            return JalaliDate(newYear, newMonth, newDay)
        }
    }

    fun getDaysInMonth(year: Int, month: Int): Int {
        return when (month) {
            in 1..6 -> 31
            in 7..11 -> 30
            12 -> if (isLeapYear(year)) 30 else 29
            else -> 30
        }
    }

    fun isLeapYear(year: Int): Boolean {
        // Astronomical Jalali leap year algorithm approximation for 1300-1500
        val breaks = intArrayOf(
            -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
            1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
        )
        val gy = year + 621
        var leapJ = -14
        var jp = breaks[0]
        var j = 1
        while (j < breaks.size && breaks[j] <= year) {
            jp = breaks[j]
            leapJ = leapJ + (jp - breaks[j - 1]) / 33 * 8 + (jp - breaks[j - 1]) % 33 / 4
            j++
        }
        var n = year - jp
        leapJ = leapJ + n / 33 * 8 + (n % 33 + 3) / 4
        if ((n % 33 == 0) && (n > 0)) leapJ--
        val leapG = gy / 4 - (gy / 100 + 1) * 3 / 4 - 150
        val march = 20 + leapJ - leapG
        return if (n < 0) false else ((n % 33) in listOf(1, 5, 9, 13, 17, 22, 26, 30))
    }

    /**
     * Converts Gregorian Date/Epoch to JalaliDate
     */
    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): JalaliDate {
        val gDaysInMonth = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var isGLeap = (gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0)
        if (isGLeap) gDaysInMonth[2] = 29

        var gDayCount = 0
        for (i in 1 until gm) {
            gDayCount += gDaysInMonth[i]
        }
        gDayCount += gd

        var jy: Int
        var jm: Int
        var jd: Int

        val marchDay = if (isGLeap) 20 else 21
        if (gDayCount >= 80) { // After 20/21 March
            val jDayCount = gDayCount - 79
            jy = gy - 621
            if (jDayCount <= 186) {
                jm = 1 + (jDayCount - 1) / 31
                jd = 1 + (jDayCount - 1) % 31
            } else {
                val rem = jDayCount - 186
                jm = 7 + (rem - 1) / 30
                jd = 1 + (rem - 1) % 30
            }
        } else {
            jy = gy - 622
            val isPrevGLeap = ((gy - 1) % 4 == 0 && (gy - 1) % 100 != 0) || ((gy - 1) % 400 == 0)
            val prevGTotal = if (isPrevGLeap) 366 else 365
            val jDayCount = gDayCount + (prevGTotal - 79)
            if (jDayCount <= 186) {
                jm = 1 + (jDayCount - 1) / 31
                jd = 1 + (jDayCount - 1) % 31
            } else {
                val rem = jDayCount - 186
                jm = 7 + (rem - 1) / 30
                jd = 1 + (rem - 1) % 30
            }
        }

        return JalaliDate(jy, jm, jd)
    }

    fun fromEpoch(epochMillis: Long = System.currentTimeMillis()): JalaliDate {
        val cal = Calendar.getInstance()
        cal.timeInMillis = epochMillis
        val gy = cal.get(Calendar.YEAR)
        val gm = cal.get(Calendar.MONTH) + 1
        val gd = cal.get(Calendar.DAY_OF_MONTH)
        return gregorianToJalali(gy, gm, gd)
    }

    fun currentJalali(): JalaliDate = fromEpoch(System.currentTimeMillis())

    fun currentShamsiString(): String = currentJalali().format()

    fun currentShamsiFullString(): String = currentJalali().formatFullPersian()

    fun getDayOfWeekPersian(epochMillis: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> "شنبه"
            Calendar.SUNDAY -> "یک‌شنبه"
            Calendar.MONDAY -> "دوشنبه"
            Calendar.TUESDAY -> "سه‌شنبه"
            Calendar.WEDNESDAY -> "چهارشنبه"
            Calendar.THURSDAY -> "پنج‌شنبه"
            Calendar.FRIDAY -> "جمعه"
            else -> ""
        }
    }

    fun toLatinDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    in '۰'..'۹' -> ('0' + (ch - '۰'))
                    in '٠'..'٩' -> ('0' + (ch - '٠'))
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    fun toPersianDigits(text: String): String {
        val persianNumbers = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val builder = StringBuilder()
        for (ch in text) {
            if (ch in '0'..'9') {
                builder.append(persianNumbers[ch - '0'])
            } else {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    fun getMonthName(month: Int): String {
        val names = listOf(
            "فروردین", "اردیبهشت", "خرداد",
            "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر",
            "دی", "بهمن", "اسفند"
        )
        return if (month in 1..12) names[month - 1] else ""
    }

    /**
     * Groups a digit string in threes with the Persian thousands separator, so
     * inputs read "۴٬۲۳۳٬۳۳۴" instead of a wall of digits. Non-digits are
     * dropped first; an empty input stays empty (so the placeholder shows).
     */
    /** Persian (or Arabic) digits to plain ASCII digits, everything else dropped. */
    fun toPlainDigits(input: String): String {
        val sb = StringBuilder()
        for (ch in input) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
            }
        }
        return sb.toString()
    }

    fun groupDigits(input: String): String {
        val digits = input.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        val grouped = StringBuilder()
        digits.forEachIndexed { i, ch ->
            if (i > 0 && (digits.length - i) % 3 == 0) grouped.append("٬")
            grouped.append(ch)
        }
        return toPersianDigits(grouped.toString())
    }

    fun formatCurrency(amount: Long, unit: String = "تومان"): String {
        val formatter = DecimalFormat("#,###")
        val formatted = formatter.format(amount)
        return "${toPersianDigits(formatted)} $unit"
    }

    fun parseShamsi(dateStr: String): JalaliDate? {
        return try {
            val parts = dateStr.replace("−", "-").replace("–", "-").split("/", "-")
            if (parts.size == 3) {
                val y = parts[0].trim().toInt()
                val m = parts[1].trim().toInt()
                val d = parts[2].trim().toInt()
                JalaliDate(y, m, d)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun jalaliToGregorian(jy: Int, jm: Int, jd: Int): Triple<Int, Int, Int> {
        val breaks = intArrayOf(
            -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
            1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
        )
        val gy = jy + 621
        var leapJ = -14
        var jp = breaks[0]
        var j = 1
        while (j < breaks.size && breaks[j] <= jy) {
            jp = breaks[j]
            leapJ = leapJ + (jp - breaks[j - 1]) / 33 * 8 + (jp - breaks[j - 1]) % 33 / 4
            j++
        }
        var n = jy - jp
        leapJ = leapJ + n / 33 * 8 + (n % 33 + 3) / 4
        if ((n % 33 == 0) && (n > 0)) leapJ--
        val isGLeap = (gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0)

        val jDayCount = if (jm <= 6) {
            (jm - 1) * 31 + jd
        } else {
            186 + (jm - 7) * 30 + jd
        }

        val gDayCount = jDayCount + 79
        val gDaysInMonth = intArrayOf(0, 31, if (isGLeap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 1
        var dCount = gDayCount
        val totalYearDays = if (isGLeap) 366 else 365
        if (dCount > totalYearDays) {
            dCount -= totalYearDays
            return Triple(gy + 1, 1, dCount)
        }
        while (gm <= 12 && dCount > gDaysInMonth[gm]) {
            dCount -= gDaysInMonth[gm]
            gm++
        }
        return Triple(gy, gm, dCount)
    }

    fun parseShamsiToEpoch(dateStr: String): Long {
        val jalali = parseShamsi(dateStr) ?: return System.currentTimeMillis()
        val (gy, gm, gd) = jalaliToGregorian(jalali.year, jalali.month, jalali.day)
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, gy)
        cal.set(Calendar.MONTH, gm - 1)
        cal.set(Calendar.DAY_OF_MONTH, gd)
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun currentTimeString(): String {
        val cal = Calendar.getInstance()
        val h = String.format("%02d", cal.get(Calendar.HOUR_OF_DAY))
        val m = String.format("%02d", cal.get(Calendar.MINUTE))
        return "$h:$m"
    }

    fun formatEpochToShamsi(epochMillis: Long): String {
        return fromEpoch(epochMillis).format()
    }
}
