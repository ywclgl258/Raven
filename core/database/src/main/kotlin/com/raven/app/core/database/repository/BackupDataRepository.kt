package com.raven.app.core.database.repository

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.RavenDatabase
import com.raven.app.core.database.dao.BlockedTermDao
import com.raven.app.core.database.dao.FavoriteDao
import com.raven.app.core.database.dao.ImageFavoriteDao
import com.raven.app.core.database.dao.PageBookmarkDao
import com.raven.app.core.database.dao.PlaybackPreferencesDao
import com.raven.app.core.database.dao.ReaderPreferencesDao
import com.raven.app.core.database.dao.ReadingProgressDao
import com.raven.app.core.database.dao.SavedSearchDao
import com.raven.app.core.database.dao.SearchHistoryDao
import com.raven.app.core.database.dao.ShelfDao
import com.raven.app.core.database.dao.ShelfItemDao
import com.raven.app.core.database.dao.SmartShelfDao
import com.raven.app.core.database.dao.TagSubscriptionDao
import com.raven.app.core.database.dao.TagTranslationDao
import com.raven.app.core.database.dao.TimeBookmarkDao
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.domain.BackupMerger
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.database.model.BackupSummary
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagTranslationSource

/**
 * Exports [RavenBackupPayload] snapshots and atomically merges/applies incoming backups
 * inside a single Room transaction per `ARCHITECTURE.md` §7.2.
 *
 * Strictly excludes credentials, cookies, tokens, encryption keys, download directories,
 * and media files.
 */
class BackupDataRepository(
    private val watchProgressDao: WatchProgressDao,
    private val readingProgressDao: ReadingProgressDao,
    private val favoriteDao: FavoriteDao,
    private val shelfDao: ShelfDao,
    private val shelfItemDao: ShelfItemDao,
    private val smartShelfDao: SmartShelfDao,
    private val pageBookmarkDao: PageBookmarkDao,
    private val timeBookmarkDao: TimeBookmarkDao,
    private val imageFavoriteDao: ImageFavoriteDao,
    private val tagSubscriptionDao: TagSubscriptionDao,
    private val savedSearchDao: SavedSearchDao,
    private val updateInboxDao: UpdateInboxDao,
    private val blockedTermDao: BlockedTermDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val playbackPreferencesDao: PlaybackPreferencesDao,
    private val readerPreferencesDao: ReaderPreferencesDao,
    private val settingsDataStore: SettingsDataStore,
    private val transactionRunner: DatabaseTransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tagTranslationDao: TagTranslationDao? = null,
) {
    fun summarize(payload: RavenBackupPayload): BackupSummary = BackupMerger.summarize(payload)

    suspend fun exportPayload(appVersion: String = ""): RavenBackupPayload {
        val now = clock()
        val appSettings = settingsDataStore.currentSettings()
        val portableSettings = settingsDataStore.exportPortableSettings()
        return transactionRunner {
            readLocalSnapshot(
                appVersion = appVersion,
                now = now,
                siteLanguages = appSettings.siteLanguages,
                portableSettings = portableSettings,
            )
        }
    }

    /**
     * Atomically merges [incoming] into local state inside a single Room transaction.
     * Any failure rolls back all Room mutations completely and leaves [SettingsDataStore] untouched.
     */
    suspend fun applyBackup(
        incoming: RavenBackupPayload,
        appVersion: String = "",
    ): BackupSummary {
        require(incoming.schemaVersion == RavenBackupPayload.CURRENT_SCHEMA_VERSION) {
            "Unsupported backup schemaVersion: ${incoming.schemaVersion}"
        }
        val now = clock()
        val appSettings = settingsDataStore.currentSettings()
        val portableSettings = settingsDataStore.exportPortableSettings()

        val merged = transactionRunner {
            val local = readLocalSnapshot(
                appVersion = appVersion,
                now = now,
                siteLanguages = appSettings.siteLanguages,
                portableSettings = portableSettings,
            )
            val combined = BackupMerger.merge(local = local, incoming = incoming, now = now)
            writePayloadToDatabase(combined)
            combined
        }

        settingsDataStore.applyBackupSettings(
            settingsMap = merged.settings,
            siteLanguages = merged.siteLanguages,
        )
        return BackupMerger.summarize(merged)
    }

    private suspend fun readLocalSnapshot(
        appVersion: String,
        now: Long,
        siteLanguages: Map<SiteId, ComicLanguage>,
        portableSettings: Map<String, String>,
    ): RavenBackupPayload {
        val shelves = shelfDao.getAll().map { it.toDomain() }.let { existing ->
            if (existing.any { it.id == Shelf.WATCH_LATER_ID }) {
                existing
            } else {
                listOf(
                    Shelf(
                        id = Shelf.WATCH_LATER_ID,
                        name = RavenDatabase.DEFAULT_WATCH_LATER_NAME,
                        isSystem = true,
                        createdAt = now,
                        modifiedAt = now,
                    ),
                ) + existing
            }
        }
        val imageFavorites = imageFavoriteDao.getAll().map { it.toDomain().stripLocalDiskPath() }
        return RavenBackupPayload(
            schemaVersion = RavenBackupPayload.CURRENT_SCHEMA_VERSION,
            exportedAt = now,
            appVersion = appVersion,
            watchProgress = watchProgressDao.getAll().map { it.toDomain() },
            readingProgress = readingProgressDao.getAll().map { it.toDomain() },
            favorites = favoriteDao.getAll().map { it.toDomain() },
            shelves = shelves,
            shelfItems = shelfItemDao.getAll().map { it.toDomain() },
            smartShelves = smartShelfDao.getAll().map { it.toDomain() },
            pageBookmarks = pageBookmarkDao.getAll().map { it.toDomain() },
            timeBookmarks = timeBookmarkDao.getAll().map { it.toDomain() },
            imageFavorites = imageFavorites,
            tagSubscriptions = tagSubscriptionDao.getAll().map { it.toDomain() },
            savedSearches = savedSearchDao.getAll().map { it.toDomain() },
            updateInbox = updateInboxDao.getAll().map { it.toDomain() },
            blockedTerms = blockedTermDao.getAll().map { it.toDomain() },
            searchHistory = searchHistoryDao.getAll().map { it.toDomain() },
            playbackPreferences = playbackPreferencesDao.getAll().map { it.toDomain() },
            readerPreferences = readerPreferencesDao.getAll().map { it.toDomain() },
            siteLanguages = siteLanguages,
            settings = portableSettings,
            customTagTranslations = tagTranslationDao
                ?.getBySource(TagTranslationSource.USER_CUSTOM)
                ?.map { it.toDomain() }
                .orEmpty(),
        )
    }

    private suspend fun writePayloadToDatabase(payload: RavenBackupPayload) {
        watchProgressDao.deleteAll()
        if (payload.watchProgress.isNotEmpty()) {
            watchProgressDao.upsertAll(payload.watchProgress.map { it.toEntity() })
        }

        readingProgressDao.deleteAll()
        if (payload.readingProgress.isNotEmpty()) {
            readingProgressDao.upsertAll(payload.readingProgress.map { it.toEntity() })
        }

        favoriteDao.deleteAll()
        if (payload.favorites.isNotEmpty()) {
            favoriteDao.upsertAll(payload.favorites.map { it.toEntity() })
        }

        // Delete children before parents and insert parents before children to satisfy FK constraints.
        shelfItemDao.deleteAll()
        shelfDao.deleteAll()
        if (payload.shelves.isNotEmpty()) {
            shelfDao.upsertAll(payload.shelves.map { it.toEntity() })
        }
        if (payload.shelfItems.isNotEmpty()) {
            shelfItemDao.upsertAll(payload.shelfItems.map { it.toEntity() })
        }

        smartShelfDao.deleteAll()
        if (payload.smartShelves.isNotEmpty()) {
            smartShelfDao.upsertAll(payload.smartShelves.map { it.toEntity() })
        }

        pageBookmarkDao.deleteAll()
        if (payload.pageBookmarks.isNotEmpty()) {
            pageBookmarkDao.upsertAll(payload.pageBookmarks.map { it.toEntity() })
        }

        timeBookmarkDao.deleteAll()
        if (payload.timeBookmarks.isNotEmpty()) {
            timeBookmarkDao.upsertAll(payload.timeBookmarks.map { it.toEntity() })
        }

        imageFavoriteDao.deleteAll()
        if (payload.imageFavorites.isNotEmpty()) {
            imageFavoriteDao.upsertAll(payload.imageFavorites.map { it.toEntity() })
        }

        tagSubscriptionDao.deleteAll()
        if (payload.tagSubscriptions.isNotEmpty()) {
            tagSubscriptionDao.upsertAll(payload.tagSubscriptions.map { it.toEntity() })
        }

        savedSearchDao.deleteAll()
        if (payload.savedSearches.isNotEmpty()) {
            savedSearchDao.upsertAll(payload.savedSearches.map { it.toEntity() })
        }

        updateInboxDao.deleteAll()
        if (payload.updateInbox.isNotEmpty()) {
            updateInboxDao.upsertAll(payload.updateInbox.map { it.toEntity() })
        }

        blockedTermDao.deleteAll()
        if (payload.blockedTerms.isNotEmpty()) {
            blockedTermDao.upsertAll(payload.blockedTerms.map { it.toEntity() })
        }

        searchHistoryDao.deleteAll()
        if (payload.searchHistory.isNotEmpty()) {
            searchHistoryDao.upsertAll(payload.searchHistory.map { it.toEntity() })
        }

        playbackPreferencesDao.deleteAll()
        if (payload.playbackPreferences.isNotEmpty()) {
            playbackPreferencesDao.upsertAll(payload.playbackPreferences.map { it.toEntity() })
        }

        readerPreferencesDao.deleteAll()
        if (payload.readerPreferences.isNotEmpty()) {
            readerPreferencesDao.upsertAll(payload.readerPreferences.map { it.toEntity() })
        }

        tagTranslationDao?.let { dao ->
            dao.deleteBySource(TagTranslationSource.USER_CUSTOM)
            if (payload.customTagTranslations.isNotEmpty()) {
                payload.customTagTranslations
                    .map { it.copy(source = TagTranslationSource.USER_CUSTOM).toEntity() }
                    .chunked(150)
                    .forEach { chunk -> dao.upsertAll(chunk) }
            }
        }
    }

    private fun ImageFavorite.stripLocalDiskPath(): ImageFavorite {
        if (!siteId.isLocal) return this
        val stripped = if (imageUrl.isNotBlank() && !UrlSanitizer.isHttpsUrl(imageUrl)) {
            ""
        } else {
            imageUrl
        }
        return if (stripped == imageUrl) this else copy(imageUrl = stripped)
    }
}
