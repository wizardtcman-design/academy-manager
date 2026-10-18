package com.example.update

import android.content.Context
import com.example.BuildConfig

/**
 * Small, private store for the update feature's bookkeeping. It only holds
 * plain, non-sensitive flags, so the ordinary private preferences are enough --
 * no encryption is needed here.
 *
 *   - last check time, so a check runs at most once per interval
 *   - the version the user chose to skip, so we never nag about it again
 *   - the newest version we have already shown What's New for, so the
 *     "مدیریت آموزشگاه به‌روز شد" dialog appears exactly once per version
 */
object UpdatePrefs {
    private const val PREFS = "academy_update"

    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_SKIPPED_VERSION = "skipped_version"
    private const val KEY_WHATS_NEW_SHOWN = "whats_new_shown_version"
    private const val KEY_NOTIFIED_VERSION = "notified_version"
    private const val KEY_OFFERED_VERSION = "offered_version"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCheckMs(ctx: Context): Long = p(ctx).getLong(KEY_LAST_CHECK, 0L)
    fun setLastCheckMs(ctx: Context, v: Long) =
        p(ctx).edit().putLong(KEY_LAST_CHECK, v).apply()

    fun skippedVersion(ctx: Context): String =
        p(ctx).getString(KEY_SKIPPED_VERSION, "").orEmpty()
    fun setSkippedVersion(ctx: Context, v: String) =
        p(ctx).edit().putString(KEY_SKIPPED_VERSION, v).apply()

    /** The version whose What's New dialog has already been shown after update. */
    fun whatsNewShownVersion(ctx: Context): String =
        p(ctx).getString(KEY_WHATS_NEW_SHOWN, "").orEmpty()
    fun setWhatsNewShownVersion(ctx: Context, v: String) =
        p(ctx).edit().putString(KEY_WHATS_NEW_SHOWN, v).apply()

    /** The newer version we have already posted one update notification for. */
    fun notifiedVersion(ctx: Context): String =
        p(ctx).getString(KEY_NOTIFIED_VERSION, "").orEmpty()
    fun setNotifiedVersion(ctx: Context, v: String) =
        p(ctx).edit().putString(KEY_NOTIFIED_VERSION, v).apply()

    /** The app version from which the user was already told about an update. */
    fun offeredVersion(ctx: Context): String =
        p(ctx).getString(KEY_OFFERED_VERSION, "").orEmpty()
    fun setOfferedVersion(ctx: Context, v: String) =
        p(ctx).edit().putString(KEY_OFFERED_VERSION, v).apply()

    /** True when the running build is the one the user skipped (or older). */
    fun isRunningSkipped(ctx: Context): Boolean {
        val skip = skippedVersion(ctx)
        if (skip.isBlank()) return false
        // The skip only applies while the user has not yet installed that exact
        // version: once installed, the current version equals it and the skip is
        // naturally behind us.
        return VersionCompare.compare(BuildConfig.VERSION_NAME, skip) <= 0
    }
}
