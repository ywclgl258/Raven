package com.raven.app.feature.player

import com.raven.app.core.common.TagDictionary
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI module for `:feature:player` per `ARCHITECTURE.md` §3.1.
 *
 * Registers [PlayerPlaybackPort], [PlayerOfflinePort], and [PlayerViewModel] (accepting an
 * optional [PlayerLaunchArgs] parameter via `parametersOf(args)` or later via `viewModel.open(args)`).
 */
val playerFeatureModule: Module = module {
    factory<PlayerPlaybackPort> { Media3PlayerPlaybackPort(controller = get()) }
    factory<PlayerOfflinePort> {
        CoordinatorPlayerOfflinePort(
            downloadCoordinator = get(),
            localLibraryImporter = get(),
        )
    }
    viewModel { params ->
        PlayerViewModel(
            initialArgs = params.getOrNull<PlayerLaunchArgs>(),
            siteRegistry = get(),
            playback = get(),
            offline = get(),
            playbackQueueStore = get(),
            historyRepository = get(),
            favoriteRepository = get(),
            shelfRepository = get(),
            bookmarkRepository = get(),
            blockedTermsRepository = get(),
            preferencesRepository = get(),
            dispatchers = get(),
            tagDictionary = getOrNull<TagDictionary>() ?: TagDictionary.default,
            mediaTagCacheRepository = get(),
            tagDictionaryRepository = get(),
        )
    }
}
