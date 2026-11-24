package com.raven.app

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.network.ApplicationScope
import com.raven.app.core.network.NetworkMonitor
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

/**
 * Application entry point for Raven (`渡鸦`).
 *
 * Startup responsibilities (zero blocking I/O on the main thread):
 * 1. Initializes Koin across all 13 core, site, feature, and app modules + WorkManager factory.
 * 2. Installs Coil 3's [SingletonImageLoader] backed by Koin's single site-partitioned [ImageLoader].
 * 3. Starts [NetworkMonitor] for IPv4/proxy/route-epoch invalidation and metered-network tracking.
 * 4. Runs [StorageRootManager.applyPendingOnColdStart] on [ApplicationScope] BEFORE starting
 *    [DownloadCoordinator], ensuring deferred storage switches and reinstall adoption complete
 *    before Media3 caches or disk sidecars open.
 */
class RavenApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()

        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(this@RavenApplication)
                workManagerFactory()
                modules(allRavenKoinModules)
            }
        }

        SingletonImageLoader.setSafe(this)

        val koin = getKoin()
        val networkMonitor = koin.get<NetworkMonitor>()
        runCatching { networkMonitor.start() }
            .onFailure { Log.w(TAG, "Failed to start NetworkMonitor", it) }

        val applicationScope = koin.get<ApplicationScope>()
        val dispatchers = koin.get<AppDispatchers>()
        val storageRootManager = koin.get<StorageRootManager>()
        val downloadCoordinator = koin.get<DownloadCoordinator>()

        applicationScope.launch(dispatchers.io) {
            runCatching { storageRootManager.applyPendingOnColdStart() }
                .onFailure { Log.w(TAG, "Failed to apply pending storage location on cold start", it) }
            runCatching { downloadCoordinator.start() }
                .onFailure { Log.w(TAG, "Failed to start DownloadCoordinator", it) }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        getKoin().get()

    companion object {
        private const val TAG: String = "RavenApplication"
    }
}
