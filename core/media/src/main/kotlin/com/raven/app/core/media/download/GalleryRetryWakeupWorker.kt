package com.raven.app.core.media.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager wake-up worker guaranteeing `RETRY_WAIT` gallery downloads recover after Android Doze
 * or app process termination once network connectivity is available.
 */
class GalleryRetryWakeupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val galleryDownloadEngine: GalleryDownloadEngine by inject()
    private val downloadCoordinator: DownloadCoordinator by inject()

    override suspend fun doWork(): Result {
        return runCatching {
            downloadCoordinator.syncFromDisk()
            // resumePending reports false only when a runnable task exists but the foreground
            // service could not be started (e.g. API 31+ background FGS denial). Never report
            // success in that case or the retry timer is permanently lost.
            if (galleryDownloadEngine.resumePending()) {
                Result.success()
            } else {
                Result.retry()
            }
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME: String = "raven-gallery-retry-wakeup"

        fun schedule(context: Context, taskId: String, delayMs: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<GalleryRetryWakeupWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("$UNIQUE_WORK_NAME:$taskId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
