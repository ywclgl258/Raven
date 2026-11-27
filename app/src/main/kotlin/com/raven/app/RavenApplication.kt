package com.raven.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.log.RavenLog
import com.raven.app.core.database.repository.BackupDataRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.network.ApplicationScope
import com.raven.app.core.network.NetworkMonitor
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.feature.settings.AppLockSessionCoordinator
import com.raven.app.core.network.SessionCacheInvalidationBinding
import com.raven.app.feature.settings.EhTagTranslationSyncer
import com.raven.app.feature.settings.RavenAppInfo
import com.raven.app.feature.settings.RavenUpdater
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
 * 1. Installs [RavenDiagnostics] first so a crash during startup is still recorded.
 * 2. Initializes Koin across all 13 core, site, feature, and app modules + WorkManager factory.
 * 3. Installs Coil 3's [SingletonImageLoader] backed by Koin's single site-partitioned [ImageLoader].
 * 4. Starts [NetworkMonitor] for IPv4/proxy/route-epoch invalidation and metered-network tracking.
 * 5. Runs [StorageRootManager.applyPendingOnColdStart] on [ApplicationScope] BEFORE starting
 *    [DownloadCoordinator], ensuring deferred storage switches and reinstall adoption complete
 *    before Media3 caches or disk sidecars open.
 */
class RavenApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()

        // Diagnostics first: everything below can fail and must land in the rolling log.
        runCatching {
            RavenDiagnostics.install(
                context = this,
                info = RavenAppInfo.fromContext(this),
                debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            )
        }
        RavenLog.breadcrumb(TAG, "onCreate begin")

        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(this@RavenApplication)
                workManagerFactory()
                modules(allRavenKoinModules)
            }
        }
        RavenLog.breadcrumb(TAG, "Koin ready")

        SingletonImageLoader.setSafe(this)

        val koin = getKoin()
        val lockCoordinator = koin.get<AppLockSessionCoordinator>()
        lockCoordinator.attach(this)
        val networkMonitor = koin.get<NetworkMonitor>()
        runCatching { networkMonitor.start() }
            .onFailure { RavenLog.w(TAG, "Failed to start NetworkMonitor", it) }

        val applicationScope = koin.get<ApplicationScope>()
        val dispatchers = koin.get<AppDispatchers>()
        val storageRootManager = koin.get<StorageRootManager>()
        val downloadCoordinator = koin.get<DownloadCoordinator>()
        val tagDictionaryRepository = koin.get<TagDictionaryRepository>()
        val ehTagTranslationSyncer = koin.get<EhTagTranslationSyncer>()
        val preferencesRepository = koin.get<PreferencesRepository>()
        val siteRegistry = koin.get<SiteRegistry>()
        val updater = koin.get<RavenUpdater>()
        // Constructing this binding attaches the Vault account-change listener. It is resolved
        // explicitly because Koin eager singletons require AndroidContext in JVM graph tests.
        runCatching { koin.get<SessionCacheInvalidationBinding>() }
            .onFailure { RavenLog.w(TAG, "Failed to attach session cache invalidation", it) }

        applicationScope.launch(dispatchers.io) {
            // Finish a restore interrupted between its Room commit and its settings write before
            // anything reads AppSettings, so the app never boots with restored data + stale settings.
            runCatching { koin.get<BackupDataRepository>().recoverPendingSettings() }
                .onFailure { RavenLog.w(TAG, "Failed to recover pending backup restore settings", it) }

            launch(dispatchers.main) {
                preferencesRepository.settings.collect(lockCoordinator::onSettingsLoaded)
            }
            launch {
                runCatching {
                    applyStartupDefaultSiteOnColdStart(preferencesRepository, siteRegistry)
                }.onFailure { RavenLog.w(TAG, "Failed to apply startup default site", it) }
            }
            launch {
                runCatching {
                    runStartupUpdateCheck(updater, preferencesRepository)
                }.onFailure { RavenLog.w(TAG, "Startup update check failed", it) }
            }
            launch {
                runCatching { storageRootManager.applyPendingOnColdStart() }
                    .onFailure { RavenLog.w(TAG, "Failed to apply pending storage location on cold start", it) }
                runCatching { downloadCoordinator.start() }
                    .onFailure { RavenLog.w(TAG, "Failed to start DownloadCoordinator", it) }
                runCatching { RavenBackgroundMaintenanceWorker.schedulePeriodic(this@RavenApplication) }
                    .onFailure { RavenLog.w(TAG, "Failed to schedule periodic background maintenance", it) }
            }
            launch {
                runCatching { tagDictionaryRepository.hydrateRuntimeDictionary() }
                    .onFailure { RavenLog.w(TAG, "Failed to hydrate TagDictionary on startup", it) }
                runCatching { ehTagTranslationSyncer.syncIfDue() }
                    .onFailure { RavenLog.w(TAG, "Failed to auto-sync EhTagTranslation dictionary", it) }
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        getKoin().get()

    companion object {
        private const val TAG: String = "RavenApplication"
    }
}
