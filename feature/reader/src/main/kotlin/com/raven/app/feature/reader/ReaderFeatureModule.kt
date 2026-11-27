package com.raven.app.feature.reader

import com.raven.app.core.common.TagDictionary
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.network.NetworkQualifiers
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI module for `:feature:reader` per `ARCHITECTURE.md` §3.1.
 * Registers [GalleryDetailViewModel] and [ReaderViewModel].
 */
val readerFeatureModule: Module = module {
    viewModel { params ->
        GalleryDetailViewModel(
            route = params.get<RavenRoute.GalleryDetail>(),
            siteRegistry = get(),
            galleryPageRepository = get(),
            localLibraryImporter = get(),
            downloadCoordinator = get(),
            galleryExporter = get(),
            historyRepository = get(),
            favoriteRepository = get(),
            shelfRepository = get(),
            bookmarkRepository = get(),
            blockedTermsRepository = get(),
            subscriptionRepository = get(),
            preferencesRepository = get(),
            dispatchers = get(),
            tagDictionary = getOrNull<TagDictionary>() ?: TagDictionary.default,
            mediaTagCacheRepository = get(),
            tagDictionaryRepository = get(),
            likeRepository = get(),
        )
    }

    viewModel { params ->
        ReaderViewModel(
            route = params.get<RavenRoute.Reader>(),
            siteRegistry = get(),
            galleryPageRepository = get(),
            galleryExporter = get(),
            historyRepository = get(),
            bookmarkRepository = get(),
            preferencesRepository = get(),
            shelfRepository = get(),
            playbackQueueStore = get(),
            dispatchers = get(),
            applicationScope = get(NetworkQualifiers.APPLICATION_SCOPE),
        )
    }
}
