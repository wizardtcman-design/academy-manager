package com.example.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single entry point for the update feature. It:
 *   - checks GitHub at most once per [CHECK_INTERVAL_MS]
 *   - only cares about a strictly-newer version the user has not skipped
 *   - posts one gentle Persian notification per new version, which opens the
 *     in-app update screen
 *
 * The check is always safe: any network or parse failure simply returns null,
 * so the app is never blocked or disturbed by it.
 */
object UpdateManager {

    const val CHANNEL_UPDATES = "academy_updates"

    /** Notification id for "update available" (stable, so it replaces itself). */
    const val ID_UPDATE = 9101

    /**
     * The minimum gap between two automatic checks. Kept short so a release
     * published while the app is installed is noticed within minutes rather
     * than hours; a manual "بررسی دوباره" always bypasses it (force = true).
     */
    const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

    /** Extra the notification / update screen reads to know a check was asked. */
    const val EXTRA_OPEN_UPDATE = "open_update"

    /**
     * Serialises checks: launch, resume and the background worker can all fire
     * at once, and without this the same release would be fetched and could be
     * notified more than once. Holding it also stops concurrent callers from
     * each paying the interval as a network round-trip.
     */
    private val checkMutex = Mutex()

    fun createChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        // HIGH so the "نسخه تازه" alert is visibly shown (heads-up) instead of
        // being filed silently, which is what makes it easy to miss.
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                "به‌روزرسانی مدیریت آموزشگاه",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اطلاع‌رسانی نسخه‌های تازه مدیریت آموزشگاه"
                // The user should never lose the update alert by accident; keep
                // it visible in the shade until they act on it or dismiss it.
                setShowBadge(true)
            }
        )
    }

    /** The notification title shown in the shade, per the feature request. */
    const val NOTIF_TITLE = "به‌روزرسانی مدیریت آموزشگاه"

    /**
     * Runs a check honouring the interval, and returns the newer [UpdateInfo]
     * when the user should be told about it, or null otherwise. Pass
     * [force] = true from the update screen's own "بررسی دوباره" button so a
     * manual check always goes to the network.
     */
    suspend fun checkForUpdate(ctx: Context, force: Boolean = false): UpdateInfo? =
        checkMutex.withLock { runCheckLocked(ctx, force) }

    /** True when the most recent [checkForUpdate] could not reach GitHub at all. */
    fun lastCheckFailed(): Boolean = GitHubReleaseClient.lastFailure != null

    /**
     * Like [checkForUpdate] but also posts the notification once per version.
     * Used from the background worker, on app launch and on every resume.
     */
    suspend fun checkAndNotify(ctx: Context): UpdateInfo? =
        checkMutex.withLock {
            val update = runCheckLocked(ctx, force = false) ?: return@withLock null
            // One notification per version. If the permission was still missing,
            // the version is NOT marked as notified, so the alert can still
            // appear later once the user grants notification access.
            if (update.version != UpdatePrefs.notifiedVersion(ctx)) {
                val posted = notify(ctx, update)
                if (posted) UpdatePrefs.setNotifiedVersion(ctx, update.version)
            }
            update
        }

    /**
     * The interval-gated core shared by both entry points above. It is called
     * while [checkMutex] is held, so it must never lock again; it also never
     * touches the notification, only decides whether a newer version exists.
     */
    private suspend fun runCheckLocked(ctx: Context, force: Boolean): UpdateInfo? {
        val now = System.currentTimeMillis()
        if (!force && now - UpdatePrefs.lastCheckMs(ctx) < CHECK_INTERVAL_MS) {
            return null
        }
        val latest = GitHubReleaseClient.fetchLatest() ?: return null
        UpdatePrefs.setLastCheckMs(ctx, now)

        // Only a strictly-newer version counts as an update.
        if (!VersionCompare.isNewer(latest.version, BuildConfig.VERSION_NAME)) return null
        // A version the user chose to skip is never offered again.
        if (latest.version == UpdatePrefs.skippedVersion(ctx)) return null
        return latest
    }

    /**
     * Posts the alert for a version already in hand, e.g. right after the user
     * grants notification permission from the update screen. It still respects
     * the one-per-version rule, so no duplicate ever appears.
     */
    fun notifyNow(ctx: Context, update: UpdateInfo): Boolean {
        if (update.version == UpdatePrefs.notifiedVersion(ctx)) return true
        val posted = notify(ctx, update)
        if (posted) UpdatePrefs.setNotifiedVersion(ctx, update.version)
        return posted
    }

    /**
     * Posts the "نسخه تازه" notification. Never throws. Returns true when the
     * notification was actually handed to the system, false when the user has
     * not granted notification permission (so a caller can then prompt for it).
     *
     * The alert carries the exact new version number and the release title, and
     * tapping it opens the in-app update screen.
     */
    fun notify(ctx: Context, update: UpdateInfo): Boolean {
        // Re-create the channel every time: a user who upgraded from an older
        // build that had a different channel still gets the correct one.
        createChannel(ctx)
        val allowed = Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!allowed) return false

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_UPDATE, true)
        }
        val pi = PendingIntent.getActivity(
            ctx, ID_UPDATE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The release title from GitHub, when present, e.g. "مدیریت آموزشگاه v1.0.88".
        val releaseName = update.tagName.ifBlank { "v${update.version}" }
        val body = "نسخه ${update.version} منتشر شد — $releaseName\n" +
                "برای دیدن تغییرات و نصب، بزن."

        val notif = NotificationCompat.Builder(ctx, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            // The title the user sees in the notification bar.
            .setContentTitle(NOTIF_TITLE)
            .setContentText("نسخه ${update.version} آماده است: $releaseName")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(body)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        return try {
            NotificationManagerCompat.from(ctx).notify(ID_UPDATE, notif)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
