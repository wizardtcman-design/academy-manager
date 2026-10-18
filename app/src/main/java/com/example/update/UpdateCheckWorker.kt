package com.example.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * A periodic background safety net so the user still hears about a new version
 * even if they rarely open the app. It reuses [UpdateManager] transparently, so
 * it posts the same notification the launch/resume check would, and the shared
 * interval + one-per-version rule keep it from duplicating that alert.
 *
 * WorkManager (not an alarm) is used on purpose: it is exact-timing-free,
 * survives reboots and app updates, and needs no special permission.
 */
class UpdateCheckWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            UpdateManager.checkAndNotify(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "academy_update_check"

        /**
         * Schedules the periodic check. UPDATE (not KEEP) is used so that an app
         * update resets the schedule with the current interval instead of
         * inheriting whatever an older installed build first enqueued.
         */
        fun schedule(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
