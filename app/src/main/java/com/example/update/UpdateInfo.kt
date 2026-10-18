package com.example.update

/**
 * The newest official release discovered on GitHub, together with the download
 * link of its APK. It is plain data: nothing here touches the network or the
 * device, so it can be compared, stored and shown freely.
 */
data class UpdateInfo(
    /** The tag as published, e.g. "v1.0.82". */
    val tagName: String,
    /** The version without the leading "v", e.g. "1.0.82". */
    val version: String,
    /** The release notes exactly as published (Persian, markdown-ish). */
    val notes: String,
    /** Direct download URL of the APK asset. */
    val apkUrl: String,
    /** Asset file name, used for the saved file. */
    val apkName: String,
    /** Size in bytes as reported by GitHub; 0 when unknown. */
    val apkSize: Long,
    /** Release page URL, used as a fallback the user can open in a browser. */
    val htmlUrl: String,
    /** Publish time in epoch millis; 0 when unknown. */
    val publishedAt: Long
) {
    /** The release notes, or a sensible Persian line when GitHub has none. */
    fun notesOrDefault(): String =
        notes.trim().ifBlank { "بهبود پایداری و رفع اشکال‌های جزئی." }
}

/**
 * Compares dotted version strings the way the app's versionName is built
 * ("2.4"). Missing parts count as zero, so "1.1" is newer than "1.0.9" and
 * equal to "1.1.0". Everything from the first "-"/"+"/space is dropped, so the
 * CI build suffix in tags like "v2.4-12" never affects the comparison.
 */
object VersionCompare {

    /**
     * The numeric core of a version, e.g. "2.4" from "2.4", "v2.4-12" or
     * "2.4.1-3". GitHub tags in this repo carry the CI build number after a
     * dash ("v2.4-12"); that suffix is NOT part of the version, because the
     * app's own versionName is bumped by hand ("2.4"). Treating it as a 4th
     * number made "2.4-12" look older than "2.4" and hid every update.
     */
    fun parse(v: String): List<Int> {
        val core = v.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .substringBefore(' ')
        return core.split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    /** True when [candidate] is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0

    /** -1 when [a] < [b], 0 when equal, 1 when [a] > [b]. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }
}
