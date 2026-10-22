package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import com.example.data.remote.FirebaseManager
import com.example.data.repository.AcademyRepository
import com.example.service.messaging.BotCloudBridge
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync.
 *
 * Without this the app only synced when it was opened and the phone moved to a
 * new network. With WorkManager the cloud is refreshed at least every few hours
 * (15 minutes minimum on Android), including after a reboot.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            val db = AppDatabase.getDatabase(ctx)
            val repo = AcademyRepository(db, FirebaseManager())
            if (repo.isFirebaseReady()) {
                repo.performCloudSync()
            }

            // Publish to the bot server when it is configured
            val bridge = BotCloudBridge(ctx)
            val settings = db.settingsDao().getSettingsDirect()
            if (bridge.isWorkerConfigured(settings)) {
                bridge.pushAll()
            }
            Result.success()
        } catch (e: Exception) {
            Log.w("SyncWorker", "Background sync failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "academy_periodic_sync"
        private const val INTERVAL_HOURS = 3L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Stage 4: one-shot sync right after a payment is recorded or reversed,
         * so the Telegram receipt goes out immediately instead of waiting for
         * the periodic cycle. Safe to call anywhere; no-op without network.
         */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "academy_oneshot_sync",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
