package com.raven.app.core.database.repository

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.RavenDatabase
import com.raven.app.core.database.dao.BackupRestoreJournalDao
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
import com.raven.app.core.database.dao.SeriesFollowDao
import com.raven.app.core.database.dao.TagTranslationDao
import com.raven.app.core.database.dao.TimeBookmarkDao
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.entity.BackupRestoreJournalEntity
import com.raven.app.core.database.domain.BackupMerger
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.database.model.BackupSummary
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.model.ReadingProgress
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.withLock

 /**
 * Raised when the Room transaction committed but the follow-up Preferences DataStore commit of
 * restore settings failed. The settings journal survives and is replayed on the next startup,
 * export, or restore, so the restore completes without user action.
 */
class BackupPartialRestoreException(
    message: String,
    val dataRestored: Boolean,
    val settingsCommitted: Boolean,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)


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
    private val seriesFollowDao: SeriesFollowDao? = null,
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
    private val tagDictionaryRepository: TagDictionaryRepository? = null,
    private val backupRestoreJournalDao: BackupRestoreJournalDao? = null,
) {
    private val backupMutex = Mutex()

    fun summarize(payload: RavenBackupPayload): BackupSummary = BackupMerger.summarize(payload)

    suspend fun exportPayload(appVersion: String = ""): RavenBackupPayload = backupMutex.withLock {
        // A restore interrupted between its Room commit and its settings write must be finished
        // first, otherwise the exported payload would pair new data with stale settings.
        recoverPendingSettingsLocked()
        val now = clock()
        val appSettings = settingsDataStore.readPersistedSettings()
        val portableSettings = settingsDataStore.exportPortableSettings()
        transactionRunner {
            readLocalSnapshot(
                appVersion = appVersion,
                now = now,
                siteLanguages = appSettings.siteLanguages,
                portableSettings = portableSettings,
            ).toPortableExport()
        }
    }

    /**
     * Serializes backup import/export so Room and DataStore snapshots cannot cross operation
     * generations.
     *
     * Commit protocol (Room and Preferences DataStore cannot share a transaction):
     * 1. One Room transaction merges, replaces every library row, **and** writes
     *    [BackupRestoreJournalEntity] holding the settings that still have to reach DataStore.
     *    The journal is therefore atomic with the data: a crash before commit leaves neither, a
     *    crash after commit always leaves both.
     * 2. `commitBackupSettings` applies that snapshot to DataStore.
     * 3. The journal row is deleted (separate statement; replay is idempotent, so a crash between
     *    2 and 3 just re-applies identical values).
     *
     * A failed step 2 keeps the journal, so the next startup / export / restore replays it and
     * completes the restore. The user-visible states are "restored" and "restored, settings
     * finishing on next launch" — never new library data permanently paired with old settings.
     */
    suspend fun applyBackup(
        incoming: RavenBackupPayload,
        appVersion: String = "",
    ): BackupSummary {
        require(incoming.schemaVersion == RavenBackupPayload.CURRENT_SCHEMA_VERSION) {
            "Unsupported backup schemaVersion: ${incoming.schemaVersion}"
        }
        return backupMutex.withLock {
            // Finish any restore interrupted on a previous run before merging on top of it.
            recoverPendingSettingsLocked()

            val now = clock()
            val appSettings = settingsDataStore.readPersistedSettings()
            val portableSettings = settingsDataStore.exportPortableSettings()

            val (merged, restoreId) = transactionRunner {
                val local = readLocalSnapshot(
                    appVersion = appVersion,
                    now = now,
                    siteLanguages = appSettings.siteLanguages,
                    portableSettings = portableSettings,
                )
                val combined = BackupMerger.merge(local = local, incoming = incoming, now = now)
                writePayloadToDatabase(combined)
                combined to writeRestoreJournal(combined, now)
            }

            commitJournaledSettings(
                settingsMap = merged.settings,
                siteLanguages = merged.siteLanguages,
                restoreId = restoreId,
                journalMustSurviveOnFailure = true,
            )

            // Custom tag entries were replaced inside the Room transaction; the in-memory
            // TagDictionary still holds the previous custom layer until it is rehydrated.
            runCatching { tagDictionaryRepository?.hydrateCustomLayer() }

            BackupMerger.summarize(merged)
        }
    }

    /**
     * Replays a journal left behind by an interrupted restore and clears it. Idempotent; called
     * before every export/restore and from app startup (`RavenApplication`).
     *
     * @return `true` when a pending journal was found and replayed.
     */
    suspend fun recoverPendingSettings(): Boolean = backupMutex.withLock {
        recoverPendingSettingsLocked()
    }

    private suspend fun recoverPendingSettingsLocked(): Boolean {
        val dao = backupRestoreJournalDao ?: return false
        val journal = runCatching { dao.get() }.getOrNull() ?: return false
        val decoded = runCatching { journal.toSettingsSnapshot() }.getOrNull()
        if (decoded == null) {
            // Corrupt journal: drop it rather than replay garbage on every start.
            clearRestoreJournal(journal.restoreId)
            return false
        }
        commitJournaledSettings(
            settingsMap = decoded.first,
            siteLanguages = decoded.second,
            restoreId = journal.restoreId,
            journalMustSurviveOnFailure = false,
        )
        runCatching { tagDictionaryRepository?.hydrateCustomLayer() }
        return true
    }

    private suspend fun commitJournaledSettings(
        settingsMap: Map<String, String>,
        siteLanguages: Map<SiteId, ComicLanguage>,
        restoreId: String?,
        journalMustSurviveOnFailure: Boolean,
    ) {
        try {
            settingsDataStore.commitBackupSettings(settingsMap, siteLanguages)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (failure: Exception) {
            if (journalMustSurviveOnFailure) {
                throw BackupPartialRestoreException(
                    message = "本地数据已恢复，设置将在下次启动时自动补齐",
                    dataRestored = true,
                    settingsCommitted = false,
                    cause = failure,
                )
            }
            throw failure
        }
        clearRestoreJournal(restoreId)
    }

    /**
     * Deletes the journal only when it still belongs to [restoreId]. A newer restore must never
     * lose its settings journal to an older recovery finishing late.
     */
    private suspend fun clearRestoreJournal(restoreId: String?) {
        val dao = backupRestoreJournalDao ?: return
        if (restoreId == null) return
        val current = runCatching { dao.get() }.getOrNull() ?: return
        if (current.restoreId == restoreId) {
            dao.clear()
        }
    }

    private suspend fun writeRestoreJournal(payload: RavenBackupPayload, now: Long): String? {
        val dao = backupRestoreJournalDao ?: return null
        val restoreId = java.util.UUID.randomUUID().toString()
        dao.put(
            BackupRestoreJournalEntity(
                restoreId = restoreId,
                createdAt = now,
                settingsJson = JOURNAL_JSON_ENCODER.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    payload.settings,
                ),
                siteLanguagesJson = JOURNAL_JSON_ENCODER.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    payload.siteLanguages.entries.associate { (siteId, language) -> siteId.value to language.name },
                ),
            ),
        )
        return restoreId
    }


    private fun BackupRestoreJournalEntity.toSettingsSnapshot(): Pair<Map<String, String>, Map<SiteId, ComicLanguage>> {
        val settings = JOURNAL_JSON_ENCODER.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            settingsJson,
        )
        val languagesMap: Map<String, String> = JOURNAL_JSON_ENCODER.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            siteLanguagesJson,
        )
        val languages = languagesMap.mapNotNull { (rawSite, rawLanguage) ->
            val language = ComicLanguage.entries.firstOrNull { it.name == rawLanguage } ?: return@mapNotNull null
            SiteId.of(rawSite) to language
        }.toMap()
        return settings to languages
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
        // Local rows keep their on-device URI while inside the merge pipeline; only the exported
        // copy is scrubbed (see [toPortableExport]). Stripping here used to feed a scrubbed
        // snapshot back into the merge and permanently drop local preview paths.
        val imageFavorites = imageFavoriteDao.getAll().map { it.toDomain() }
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
            seriesFollows = seriesFollowDao?.getAll()?.map { it.toDomain() }.orEmpty(),
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

        seriesFollowDao?.deleteAll()
        if (seriesFollowDao != null && payload.seriesFollows.isNotEmpty()) {
            seriesFollowDao.upsertAll(payload.seriesFollows.map { it.toEntity() })
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

    /**
     * Produces the wire-safe copy of a local snapshot: device-local file paths are removed and
     * nothing else changes. Applied only on the export path ([exportPayload]) so that
     * [applyBackup]'s merge input keeps the real on-device URIs.
     */
    private fun RavenBackupPayload.toPortableExport(): RavenBackupPayload = copy(
        imageFavorites = imageFavorites.map { it.stripLocalDiskPath() },
        pageBookmarks = pageBookmarks.map { it.stripLocalCoverPath() },
        shelfItems = shelfItems.map { it.stripLocalCoverPath() },
        favorites = favorites.map { it.stripLocalCoverPath() },
        watchProgress = watchProgress.map { it.stripLocalCoverPath() },
        readingProgress = readingProgress.map { it.stripLocalCoverPath() },
        timeBookmarks = timeBookmarks.map { it.stripLocalCoverPath() },
        updateInbox = updateInbox.map { entry ->
            val item = entry.item
            if (item.siteId.isLocal && item.coverUrl.isNotBlank() && !UrlSanitizer.isHttpsUrl(item.coverUrl)) {
                entry.copy(item = item.copy(coverUrl = ""))
            } else {
                entry
            }
        },
        customTagTranslations = customTagTranslations.map {
            it.copy(
                namespace = it.namespace.take(RavenBackupPayload.MAX_TAG_NAMESPACE_LENGTH),
                rawSlug = it.rawSlug.take(RavenBackupPayload.MAX_TAG_SLUG_LENGTH),
                chineseName = it.chineseName.take(RavenBackupPayload.MAX_TAG_CHINESE_NAME_LENGTH),
                intro = it.intro.take(RavenBackupPayload.MAX_TAG_INTRO_LENGTH),
            )
        },
    )

    private fun ImageFavorite.stripLocalDiskPath(): ImageFavorite {
        if (!siteId.isLocal) return this
        val stripped = if (imageUrl.isNotBlank() && !UrlSanitizer.isHttpsUrl(imageUrl)) {
            ""
        } else {
            imageUrl
        }
        return if (stripped == imageUrl) this else copy(imageUrl = stripped)
    }

    // Local comics imported from device files can carry `file://` thumbnails. Portable backups
    // must not leak absolute storage paths, so every exported cover for a local source is scrubbed.
    private fun PageBookmark.stripLocalCoverPath(): PageBookmark {
        if (!siteId.isLocal || coverUrl.isBlank() || UrlSanitizer.isHttpsUrl(coverUrl)) return this
        return copy(coverUrl = "")
    }

    private fun ShelfItem.stripLocalCoverPath(): ShelfItem {
        if (!siteId.isLocal || coverUrl.isBlank() || UrlSanitizer.isHttpsUrl(coverUrl)) return this
        return copy(coverUrl = "")
    }

    private fun FavoriteItem.stripLocalCoverPath(): FavoriteItem {
        if (!siteId.isLocal || coverUrl.isBlank() || UrlSanitizer.isHttpsUrl(coverUrl)) return this
        return copy(coverUrl = "")
    }

    private fun WatchProgress.stripLocalCoverPath(): WatchProgress {
        if (!siteId.isLocal || coverUrl.isBlank() || UrlSanitizer.isHttpsUrl(coverUrl)) return this
        return copy(coverUrl = "")
    }

    private fun ReadingProgress.stripLocalCoverPath(): ReadingProgress {
        if (!siteId.isLocal || coverUrl.isBlank() || UrlSanitizer.isHttpsUrl(coverUrl)) return this
        return copy(coverUrl = "")
    }

    private fun TimeBookmark.stripLocalCoverPath(): TimeBookmark {
        if (!siteId.isLocal || coverUrl.isBlank() || UrlSanitizer.isHttpsUrl(coverUrl)) return this
        return copy(coverUrl = "")
    }

    companion object {
        /** Lenient decoder for the journal blobs written by this class only. */
        private val JOURNAL_JSON_ENCODER: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
