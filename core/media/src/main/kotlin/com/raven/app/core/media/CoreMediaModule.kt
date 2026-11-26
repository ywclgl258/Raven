@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media

import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.media.cast.LanCastManager
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.download.DownloadPolicyController
import com.raven.app.core.media.download.GalleryDownloadEngine
import com.raven.app.core.media.download.GalleryRetryWakeupWorker
import com.raven.app.core.media.download.PlaybackQueueStore
import com.raven.app.core.media.download.VideoDownloadEngine
import com.raven.app.core.media.playback.DownloadCaches
import com.raven.app.core.media.playback.LinkSpeedEstimator
import com.raven.app.core.media.playback.MediaBandwidthProber
import com.raven.app.core.media.playback.MediaTransportDataSourceFactory
import com.raven.app.core.media.playback.PlayerSessionController
import com.raven.app.core.media.reader.GalleryExporter
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.reader.JmcomicBitmapUnscrambler
import com.raven.app.core.media.storage.DuplicateScanner
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.network.HostPacer
import com.raven.app.core.network.NetworkQualifiers
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin DI module for `:core:media` per `ARCHITECTURE.md` §3.1.
 *
 * Uses lazy `{ get<SiteRegistry>() }` and engine getters where needed to guarantee zero circular
 * DI initialization across `:core:network`, `:core:database`, `:core:media`, and `:sites:*`.
 */
val coreMediaModule: Module = module {
    // ---- Storage & Local Library ----
    single {
        StorageRootManager(
            context = androidContext(),
            settingsDataStore = get(),
            dispatchers = get(),
        )
    }

    single {
        LocalLibraryImporter(
            context = androidContext(),
            dispatchers = get(),
        )
    }

    // ---- Playback Core ----
    single {
        LinkSpeedEstimator(
            context = androidContext(),
            networkMonitor = get(),
        )
    }

    single {
        MediaBandwidthProber(
            mediaClient = get(NetworkQualifiers.MEDIA),
            siteRegistryProvider = { get<SiteRegistry>() },
            dispatchers = get(),
        )
    }

    single {
        MediaTransportDataSourceFactory(
            mediaClient = get(NetworkQualifiers.MEDIA),
            siteRegistryProvider = { get<SiteRegistry>() },
        )
    }

    single {
        DownloadCaches(
            context = androidContext(),
            storageRootManager = get(),
            transportFactory = get(),
            applicationScope = get(NetworkQualifiers.APPLICATION_SCOPE),
            dispatchers = get(),
        )
    }

    single {
        PlayerSessionController(
            context = androidContext(),
            downloadCaches = get(),
            linkSpeedEstimator = get(),
            transportFactory = get(),
            dispatchers = get(),
        )
    }

    // ---- Reader & Gallery Core ----
    single { JmcomicBitmapUnscrambler() }

    single {
        GalleryPageRepository(
            context = androidContext(),
            imageClient = get(NetworkQualifiers.IMAGE),
            storageRootManager = get(),
            localLibraryImporter = get(),
            siteRegistryProvider = { get<SiteRegistry>() },
            unscrambler = get(),
            dispatchers = get(),
            hostPacer = get<HostPacer>(),
        )
    }

    single {
        GalleryExporter(
            context = androidContext(),
            dispatchers = get(),
        )
    }

    single {
        DuplicateScanner(
            localImporter = get(),
            downloadedBookProvider = { shouldStop ->
                get<GalleryPageRepository>().duplicateBookCandidates(shouldStop)
            },
            dispatchers = get(),
        )
    }

    // ---- Unified Download & Sequential Queue Core ----
    single {
        VideoDownloadEngine(
            context = androidContext(),
            downloadCaches = get(),
            transportFactory = get(),
            storageRootManager = get(),
            downloadStateRepository = get(),
            siteRegistryProvider = { get<SiteRegistry>() },
            applicationScope = get(NetworkQualifiers.APPLICATION_SCOPE),
            dispatchers = get(),
        )
    }

    single {
        GalleryDownloadEngine(
            context = androidContext(),
            galleryPageRepository = get(),
            storageRootManager = get(),
            downloadStateRepository = get(),
            applicationScope = get(NetworkQualifiers.APPLICATION_SCOPE),
            dispatchers = get(),
            networkAllowedProvider = { get<DownloadPolicyController>().isNetworkAllowed },
            preferOriginalProvider = { item ->
                get<PreferencesRepository>()
                    .observeEffectiveReaderPreferences(item.siteId, item.id)
                    .first()
                    .preferOriginalImages
            },
            concurrencyProvider = { siteId ->
                val requested = get<PreferencesRepository>().currentSettings().imageLoadConcurrency
                get<GalleryPageRepository>().recommendedImageConcurrency(siteId, requested)
            },
        )
    }

    single {
        DownloadPolicyController(
            networkMonitor = get(),
            settingsDataStore = get(),
            applicationScope = get(NetworkQualifiers.APPLICATION_SCOPE),
            applyVideoRequirements = { req -> get<VideoDownloadEngine>().setRequirements(req) },
            onGalleryPolicyChanged = { allowed ->
                val engine = get<GalleryDownloadEngine>()
                engine.waitForNetwork()
                if (allowed) engine.resumePending()
            },
        )
    }

    single {
        DownloadCoordinator(
            videoDownloadEngine = get(),
            galleryDownloadEngine = get(),
            downloadPolicyController = get(),
            downloadStateRepository = get(),
            storageRootManager = get(),
            downloadCaches = get(),
            galleryPageRepository = get(),
            localLibraryImporter = get(),
            settingsDataStore = get(),
            siteRegistryProvider = { get<SiteRegistry>() },
            applicationScope = get(NetworkQualifiers.APPLICATION_SCOPE),
            dispatchers = get(),
        )
    }

    single {
        PlaybackQueueStore(
            context = androidContext(),
            dispatchers = get(),
            applicationScope = get(NetworkQualifiers.APPLICATION_SCOPE),
        )
    }

    single {
        LanCastManager(
            context = androidContext(),
            okHttpClient = get(NetworkQualifiers.MEDIA),
            dispatchers = get(),
        )
    }

    workerOf(::GalleryRetryWakeupWorker)
}
