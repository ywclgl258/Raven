package com.raven.app.feature.library

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI module for `:feature:library` per `ARCHITECTURE.md` §3.1.
 * Registers [LibraryDownloadGateway] backed by `DownloadCoordinator` plus all Library ViewModels.
 */
val libraryFeatureModule: Module = module {
    single<LibraryDownloadGateway> {
        DownloadCoordinatorGateway(coordinator = get())
    }

    viewModel {
        ProfileViewModel(
            historyRepository = get(),
            shelfRepository = get(),
            subscriptionRepository = get(),
            favoriteRepository = get(),
            downloadGateway = get(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }

    viewModel {
        LibraryHubViewModel(
            shelfRepository = get(),
            historyRepository = get(),
            favoriteRepository = get(),
            bookmarkRepository = get(),
            subscriptionRepository = get(),
            downloadGateway = get(),
            localLibraryImporter = get(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }

    viewModel { params ->
        ShelfDetailViewModel(
            target = params.get<ShelfDetailTarget>(),
            shelfRepository = get(),
            historyRepository = get(),
            downloadGateway = get(),
            playbackQueueStore = get(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }

    viewModel {
        FavoritesViewModel(
            favoriteRepository = get(),
            shelfRepository = get(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }

    viewModel {
        HistoryViewModel(
            historyRepository = get(),
            shelfRepository = get(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }

    viewModel {
        ImageFavoritesViewModel(
            bookmarkRepository = get(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }

    viewModel {
        LocalLibraryViewModel(
            localLibraryImporter = get(),
            shelfRepository = get(),
            dispatchers = get(),
        )
    }

    viewModel {
        DownloadsViewModel(
            downloadGateway = get(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }

    viewModel { params ->
        val routeParams = params.getOrNull<SubscriptionsParams>()
        SubscriptionsViewModel(
            initialSiteId = routeParams?.initialSiteId,
            subscriptionRepository = get(),
            blockedTermsRepository = get(),
            preferencesRepository = get(),
            siteRegistry = get(),
            dispatchers = get(),
        )
    }
}
