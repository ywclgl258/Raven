package com.raven.app.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.repository.BackupDataRepository
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.BrowsePositionRepository
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.LikeRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SearchHistoryRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin DI module for `:core:database` per `ARCHITECTURE.md` §3.1.
 * Provides [RavenDatabase], all 20 Room DAOs, [SettingsDataStore], and 13 domain repositories.
 */
val coreDatabaseModule: Module = module {
    single<RavenDatabase> { RavenDatabase.build(androidContext()) }
    single<DatabaseTransactionRunner> { RoomTransactionRunner(get()) }

    // 20 Room DAOs
    single { get<RavenDatabase>().watchProgressDao() }
    single { get<RavenDatabase>().readingProgressDao() }
    single { get<RavenDatabase>().favoriteDao() }
    single { get<RavenDatabase>().shelfDao() }
    single { get<RavenDatabase>().shelfItemDao() }
    single { get<RavenDatabase>().smartShelfDao() }
    single { get<RavenDatabase>().pageBookmarkDao() }
    single { get<RavenDatabase>().timeBookmarkDao() }
    single { get<RavenDatabase>().imageFavoriteDao() }
    single { get<RavenDatabase>().tagSubscriptionDao() }
    single { get<RavenDatabase>().savedSearchDao() }
    single { get<RavenDatabase>().updateInboxDao() }
    single { get<RavenDatabase>().blockedTermDao() }
    single { get<RavenDatabase>().searchHistoryDao() }
    single { get<RavenDatabase>().downloadItemDao() }
    single { get<RavenDatabase>().playbackPreferencesDao() }
    single { get<RavenDatabase>().readerPreferencesDao() }
    single { get<RavenDatabase>().browsePositionDao() }
    single { get<RavenDatabase>().tagTranslationDao() }
    single { get<RavenDatabase>().mediaTagCacheDao() }
    single { get<RavenDatabase>().mediaLikeDao() }

    // Preferences DataStore
    single<DataStore<Preferences>> { SettingsDataStore.createDataStore(androidContext()) }
    single { SettingsDataStore(get()) }

    // Domain Repositories
    single {
        HistoryRepository(
            watchProgressDao = get(),
            readingProgressDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        FavoriteRepository(
            favoriteDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        LikeRepository(
            mediaLikeDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        ShelfRepository(
            shelfDao = get(),
            shelfItemDao = get(),
            smartShelfDao = get(),
            watchProgressDao = get(),
            readingProgressDao = get(),
            favoriteDao = get(),
            downloadItemDao = get(),
            blockedTermDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        BookmarkRepository(
            pageBookmarkDao = get(),
            timeBookmarkDao = get(),
            imageFavoriteDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        SubscriptionRepository(
            tagSubscriptionDao = get(),
            savedSearchDao = get(),
            updateInboxDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        BlockedTermsRepository(
            blockedTermDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        SearchHistoryRepository(
            searchHistoryDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        DownloadStateRepository(
            downloadItemDao = get(),
        )
    }
    single {
        BrowsePositionRepository(
            browsePositionDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        PreferencesRepository(
            settingsDataStore = get(),
            playbackPreferencesDao = get(),
            readerPreferencesDao = get(),
        )
    }
    single {
        TagDictionaryRepository(
            tagTranslationDao = get(),
            transactionRunner = get(),
            runtimeDictionary = getOrNull<TagDictionary>() ?: TagDictionary.default,
        )
    }
    single {
        MediaTagCacheRepository(
            mediaTagCacheDao = get(),
            transactionRunner = get(),
        )
    }
    single {
        BackupDataRepository(
            watchProgressDao = get(),
            readingProgressDao = get(),
            favoriteDao = get(),
            shelfDao = get(),
            shelfItemDao = get(),
            smartShelfDao = get(),
            pageBookmarkDao = get(),
            timeBookmarkDao = get(),
            imageFavoriteDao = get(),
            tagSubscriptionDao = get(),
            savedSearchDao = get(),
            updateInboxDao = get(),
            blockedTermDao = get(),
            searchHistoryDao = get(),
            playbackPreferencesDao = get(),
            readerPreferencesDao = get(),
            settingsDataStore = get(),
            transactionRunner = get(),
            tagTranslationDao = get(),
        )
    }
}
