package com.raven.app.feature.browse

import com.raven.app.core.common.TagDictionary
import com.raven.app.core.model.RavenRoute
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI module for `:feature:browse` per `ARCHITECTURE.md` §3.1.
 * Registers [HomeViewModel], [ExploreViewModel], [SiteFeedViewModel], [SiteDirectoryViewModel],
 * [SiteFunctionsViewModel], and [SearchViewModel].
 */
val browseFeatureModule: Module = module {
    viewModel {
        HomeViewModel(
            siteRegistry = get(),
            preferencesRepository = get(),
            blockedTermsRepository = get(),
            historyRepository = get(),
            favoriteRepository = get(),
            shelfRepository = get(),
            browsePositionRepository = get(),
            dispatchers = get(),
            tagDictionary = getOrNull<TagDictionary>() ?: TagDictionary.default,
            mediaTagCacheRepository = get(),
            tagDictionaryRepository = get(),
            downloadStateRepository = get(),
            downloadEnqueuer = CoordinatorBrowseDownloadEnqueuer(coordinator = get<com.raven.app.core.media.download.DownloadCoordinator>()),
        )
    }

    viewModel {
        ExploreViewModel(
            siteRegistry = get(),
            preferencesRepository = get(),
            blockedTermsRepository = get(),
            historyRepository = get(),
            shelfRepository = get(),
            browsePositionRepository = get(),
            dispatchers = get(),
            tagDictionary = getOrNull<TagDictionary>() ?: TagDictionary.default,
            mediaTagCacheRepository = get(),
            tagDictionaryRepository = get(),
            favoriteRepository = get(),
            downloadStateRepository = get(),
            downloadEnqueuer = CoordinatorBrowseDownloadEnqueuer(coordinator = get<com.raven.app.core.media.download.DownloadCoordinator>()),
        )
    }

    viewModel { params ->
        SiteFeedViewModel(
            route = params.get<RavenRoute.SiteFeed>(),
            siteRegistry = get(),
            preferencesRepository = get(),
            blockedTermsRepository = get(),
            historyRepository = get(),
            shelfRepository = get(),
            subscriptionRepository = get(),
            browsePositionRepository = get(),
            dispatchers = get(),
            tagDictionary = getOrNull<TagDictionary>() ?: TagDictionary.default,
            mediaTagCacheRepository = get(),
            tagDictionaryRepository = get(),
            favoriteRepository = get(),
            downloadStateRepository = get(),
            downloadEnqueuer = CoordinatorBrowseDownloadEnqueuer(coordinator = get<com.raven.app.core.media.download.DownloadCoordinator>()),
        )
    }

    viewModel { params ->
        SiteDirectoryViewModel(
            route = params.get<RavenRoute.SiteDirectory>(),
            siteRegistry = get(),
            preferencesRepository = get(),
            dispatchers = get(),
            tagDictionary = getOrNull<TagDictionary>() ?: TagDictionary.default,
        )
    }

    viewModel { params ->
        SiteFunctionsViewModel(
            route = params.get<RavenRoute.SiteFunctions>(),
            siteRegistry = get(),
            preferencesRepository = get(),
            favoriteRepository = get(),
            downloadStateRepository = get(),
            subscriptionRepository = get(),
            blockedTermsRepository = get(),
            dispatchers = get(),
        )
    }

    viewModel { params ->
        SearchViewModel(
            initialRoute = params.getOrNull<RavenRoute.Search>() ?: RavenRoute.Search(),
            siteRegistry = get(),
            preferencesRepository = get(),
            searchHistoryRepository = get(),
            subscriptionRepository = get(),
            blockedTermsRepository = get(),
            historyRepository = get(),
            shelfRepository = get(),
            dispatchers = get(),
            tagDictionary = getOrNull<TagDictionary>() ?: TagDictionary.default,
            mediaTagCacheRepository = get(),
            tagDictionaryRepository = get(),
            favoriteRepository = get(),
            downloadStateRepository = get(),
            downloadEnqueuer = CoordinatorBrowseDownloadEnqueuer(coordinator = get<com.raven.app.core.media.download.DownloadCoordinator>()),
        )
    }
}
