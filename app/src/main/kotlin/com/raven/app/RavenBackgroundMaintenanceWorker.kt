package com.raven.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.feature.library.SubscriptionSyncRunner
import java.io.File
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 12-hour background maintenance worker executing subscription inbox sync (when enabled),
 * inbox pruning (`<= 10_000` entries), and stale temporary cache cleanup without blocking
 * cold startup.
 */
class RavenBackgroundMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val preferencesRepository: PreferencesRepository by inject()
    private val subscriptionSyncRunner: SubscriptionSyncRunner by inject()
    private val updateInboxDao: UpdateInboxDao by inject()

    override suspend fun doWork(): Result {
        val settings = runCatching { preferencesRepository.currentSettings() }.getOrNull()
        if (settings?.backgroundSubscriptionSync == true) {
            runCatching { subscriptionSyncRunner.syncAll() }
                .onFailure { Log.w(TAG, "Background subscription sync failed", it) }
        }
        runCatching { updateInboxDao.prune(UpdateInboxDao.MAX_INBOX_ITEMS) }
            .onFailure { Log.w(TAG, "UpdateInbox prune failed", it) }
        runCatching { pruneAppTempCaches(applicationContext) }
            .onFailure { Log.w(TAG, "Temp cache trim failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG: String = "RavenMaintenanceWorker"
        private const val WORK_NAME: String = "raven_periodic_maintenance"
        private const val INTERVAL_HOURS: Long = 12L
        private const val STALE_TEMP_AGE_MS: Long = 24L * 60L * 60L * 1_000L

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RavenBackgroundMaintenanceWorker>(
                INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        internal fun pruneAppTempCaches(
            context: Context,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            val cutoff = nowMs - STALE_TEMP_AGE_MS
            val exportsDir = File(context.cacheDir, "raven-exports")
            if (exportsDir.isDirectory) {
                exportsDir.listFiles().orEmpty().forEach { entry ->
                    if (entry.lastModified() in 1L until cutoff) {
                        entry.deleteRecursively()
                    }
                }
            }
            val tmpFiles = context.cacheDir.listFiles().orEmpty()
            tmpFiles.forEach { file ->
                if (file.isFile &&
                    file.name.endsWith(".tmp", ignoreCase = true) &&
                    file.lastModified() in 1L until cutoff
                ) {
                    file.delete()
                }
            }
        }
    }
}
