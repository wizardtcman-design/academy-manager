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
 * ("1.0.82"). Missing parts count as zero, so "1.1" is newer than "1.0.9" and
 * equal to "1.1.0". Any non-numeric suffix on a part is ignored, so a tag like
 * "1.0.82-beta" compares as 1.0.82.
 */
object VersionCompare {

    fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

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
