package com.raven.app.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.raven.app.core.database.dao.BackupRestoreJournalDao
import com.raven.app.core.database.dao.BlockedTermDao
import com.raven.app.core.database.dao.BrowsePositionDao
import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.dao.FavoriteDao
import com.raven.app.core.database.dao.ImageFavoriteDao
import com.raven.app.core.database.dao.MediaTagCacheDao
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
import com.raven.app.core.database.dao.InboxDismissedDao
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.entity.BackupRestoreJournalEntity
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.entity.BrowsePositionEntity
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.ImageFavoriteEntity
import com.raven.app.core.database.entity.MediaTagCacheEntity
import com.raven.app.core.database.entity.PageBookmarkEntity
import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.database.entity.ReadingProgressEntity
import com.raven.app.core.database.entity.SavedSearchEntity
import com.raven.app.core.database.entity.SearchHistoryEntity
import com.raven.app.core.database.entity.ShelfEntity
import com.raven.app.core.database.entity.ShelfItemEntity
import com.raven.app.core.database.entity.SmartShelfEntity
import com.raven.app.core.database.entity.TagSubscriptionEntity
import com.raven.app.core.database.entity.SeriesFollowEntity
import com.raven.app.core.database.entity.TagTranslationEntity
import com.raven.app.core.database.entity.TimeBookmarkEntity
import com.raven.app.core.database.entity.InboxDismissedEntity
import com.raven.app.core.database.entity.UpdateInboxEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.TagTranslationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure in-memory [DataStore] of [Preferences] for deterministic JVM unit tests on Windows
 * (where `Build.VERSION.SDK_INT == 0` causes AndroidX DataStore's `File.renameTo` to fail
 * when overwriting an existing `.preferences_pb` file).
 */
internal class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val updated = transform(state.value).toPreferences()
            state.value = updated
            updated
        }
}

/**
 * In-memory reactive fake database backing all 18 DAOs with transactional rollback support
 * so every repository can be exhaustively verified on pure JVM without Robolectric.
 */
internal class FakeRavenDb {
    val watchProgress = FakeWatchProgressDao()
    val readingProgress = FakeReadingProgressDao()
    val favorites = FakeFavoriteDao()
    val shelfItems = FakeShelfItemDao()
    val shelves = FakeShelfDao(shelfItems)
    val smartShelves = FakeSmartShelfDao()
    val pageBookmarks = FakePageBookmarkDao()
    val timeBookmarks = FakeTimeBookmarkDao()
    val imageFavorites = FakeImageFavoriteDao()
    val tagSubscriptions = FakeTagSubscriptionDao()
    val seriesFollows = FakeSeriesFollowDao()
    val seriesFollowDao = seriesFollows
    val savedSearches = FakeSavedSearchDao()
    val updateInbox = FakeUpdateInboxDao()
    val inboxDismissed = FakeInboxDismissedDao()
    val blockedTerms = FakeBlockedTermDao()
    val searchHistory = FakeSearchHistoryDao()
    val downloadItems = FakeDownloadItemDao()
    val playbackPreferences = FakePlaybackPreferencesDao()
    val readerPreferences = FakeReaderPreferencesDao()
    val browsePositions = FakeBrowsePositionDao()
    val tagTranslations = FakeTagTranslationDao()
    val mediaTagCache = FakeMediaTagCacheDao()
    val backupRestoreJournal = FakeBackupRestoreJournalDao()

    val transactionRunner: DatabaseTransactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <R> invoke(block: suspend () -> R): R {
            val snapWatch = watchProgress.rows.value
            val snapReading = readingProgress.rows.value
            val snapFav = favorites.rows.value
            val snapShelves = shelves.rows.value
            val snapShelfItems = shelfItems.rows.value
            val snapSmart = smartShelves.rows.value
            val snapPage = pageBookmarks.rows.value
            val snapTime = timeBookmarks.rows.value
            val snapImg = imageFavorites.rows.value
            val snapSub = tagSubscriptions.rows.value
            val snapSeries = seriesFollows.rows.value
            val snapSaved = savedSearches.rows.value
            val snapInbox = updateInbox.rows.value
            val snapBlocked = blockedTerms.rows.value
            val snapSearch = searchHistory.rows.value
            val snapDl = downloadItems.rows.value
            val snapPlayback = playbackPreferences.rows.value
            val snapReader = readerPreferences.rows.value
            val snapPos = browsePositions.rows.value
            val snapTagTrans = tagTranslations.rows.value
            val snapMediaTagCache = mediaTagCache.rows.value
            val snapRestoreJournal = backupRestoreJournal.rows.value
            return try {
                block()
            } catch (t: Throwable) {
                watchProgress.rows.value = snapWatch
                readingProgress.rows.value = snapReading
                favorites.rows.value = snapFav
                shelves.rows.value = snapShelves
                shelfItems.rows.value = snapShelfItems
                smartShelves.rows.value = snapSmart
                pageBookmarks.rows.value = snapPage
                timeBookmarks.rows.value = snapTime
                imageFavorites.rows.value = snapImg
                tagSubscriptions.rows.value = snapSub
                seriesFollows.rows.value = snapSeries
                savedSearches.rows.value = snapSaved
                updateInbox.rows.value = snapInbox
                blockedTerms.rows.value = snapBlocked
                searchHistory.rows.value = snapSearch
                downloadItems.rows.value = snapDl
                playbackPreferences.rows.value = snapPlayback
                readerPreferences.rows.value = snapReader
                browsePositions.rows.value = snapPos
                tagTranslations.rows.value = snapTagTrans
                mediaTagCache.rows.value = snapMediaTagCache
                backupRestoreJournal.rows.value = snapRestoreJournal
                throw t
            }
        }
    }
}

internal class FakeWatchProgressDao : WatchProgressDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, WatchProgressEntity>>(emptyMap())

    override fun observeRecent(limit: Int): Flow<List<WatchProgressEntity>> =
        rows.map { it.values.sortedByDescending(WatchProgressEntity::updatedAt).take(limit) }

    override fun observeAll(): Flow<List<WatchProgressEntity>> =
        rows.map { it.values.sortedByDescending(WatchProgressEntity::updatedAt) }

    override fun observeBySite(siteId: String): Flow<List<WatchProgressEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId }.sortedByDescending(WatchProgressEntity::updatedAt) }

    override fun observeOne(siteId: String, mediaId: String): Flow<WatchProgressEntity?> =
        rows.map { it[siteId to mediaId] }

    override suspend fun getOne(siteId: String, mediaId: String): WatchProgressEntity? =
        rows.value[siteId to mediaId]

    override suspend fun getAll(): List<WatchProgressEntity> =
        rows.value.values.sortedByDescending(WatchProgressEntity::updatedAt)

    override suspend fun upsert(entity: WatchProgressEntity) {
        rows.value = rows.value + ((entity.siteId to entity.mediaId) to entity)
    }

    override suspend fun upsertAll(entities: List<WatchProgressEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId to it.mediaId }
    }

    override suspend fun delete(siteId: String, mediaId: String) {
        rows.value = rows.value - (siteId to mediaId)
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override suspend fun pruneSite(siteId: String, limit: Int) {
        val keep = rows.value.values
            .filter { it.siteId == siteId }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .mapTo(mutableSetOf()) { it.mediaId }
        rows.value = rows.value.filterKeys { (s, m) -> s != siteId || m in keep }
    }
}

internal class FakeReadingProgressDao : ReadingProgressDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, ReadingProgressEntity>>(emptyMap())

    override fun observeRecent(limit: Int): Flow<List<ReadingProgressEntity>> =
        rows.map { it.values.sortedByDescending(ReadingProgressEntity::updatedAt).take(limit) }

    override fun observeAll(): Flow<List<ReadingProgressEntity>> =
        rows.map { it.values.sortedByDescending(ReadingProgressEntity::updatedAt) }

    override fun observeBySite(siteId: String): Flow<List<ReadingProgressEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId }.sortedByDescending(ReadingProgressEntity::updatedAt) }

    override fun observeOne(siteId: String, mediaId: String): Flow<ReadingProgressEntity?> =
        rows.map { it[siteId to mediaId] }

    override suspend fun getOne(siteId: String, mediaId: String): ReadingProgressEntity? =
        rows.value[siteId to mediaId]

    override suspend fun getAll(): List<ReadingProgressEntity> =
        rows.value.values.sortedByDescending(ReadingProgressEntity::updatedAt)

    override suspend fun upsert(entity: ReadingProgressEntity) {
        rows.value = rows.value + ((entity.siteId to entity.mediaId) to entity)
    }

    override suspend fun upsertAll(entities: List<ReadingProgressEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId to it.mediaId }
    }

    override suspend fun delete(siteId: String, mediaId: String) {
        rows.value = rows.value - (siteId to mediaId)
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override suspend fun pruneSite(siteId: String, limit: Int) {
        val keepWorks = rows.value.values
            .filter { it.siteId == siteId }
            .groupBy { it.seriesId?.takeIf { s -> s.isNotBlank() } ?: it.mediaId }
            .mapValues { (_, chapters) -> chapters.maxOf { it.updatedAt } }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit)
            .mapTo(mutableSetOf()) { it.key }
        rows.value = rows.value.filter { (key, entity) ->
            key.first != siteId || (entity.seriesId?.takeIf { it.isNotBlank() } ?: entity.mediaId) in keepWorks
        }
    }

    override suspend fun deleteWork(siteId: String, workId: String) {
        rows.value = rows.value.filterNot { (key, entity) ->
            key.first == siteId && (entity.seriesId?.takeIf { it.isNotBlank() } ?: entity.mediaId) == workId
        }
    }

    override suspend fun linkChapters(siteId: String, seriesId: String, seriesTitle: String?, seriesUrl: String?, chapterIds: List<String>) {
        val idSet = chapterIds.toSet()
        rows.value = rows.value.mapValues { (key, entity) ->
            if (key.first == siteId && key.second in idSet &&
                (entity.seriesId.isNullOrBlank() || entity.seriesId == seriesId)
            ) {
                val changed = entity.seriesId.isNullOrBlank() || entity.seriesId != seriesId ||
                    (entity.seriesTitle.isNullOrBlank() && !seriesTitle.isNullOrBlank()) ||
                    (entity.seriesUrl.isNullOrBlank() && !seriesUrl.isNullOrBlank())
                if (!changed) entity else entity.copy(
                    seriesId = seriesId,
                    seriesTitle = entity.seriesTitle?.takeIf { it.isNotBlank() } ?: seriesTitle,
                    seriesUrl = entity.seriesUrl?.takeIf { it.isNotBlank() } ?: seriesUrl,
                )
            } else entity
        }
    }
}

internal class FakeFavoriteDao : FavoriteDao {
    val rows = MutableStateFlow<Map<Triple<String, FavoriteKind, String>, FavoriteEntity>>(emptyMap())

    override fun observeAll(): Flow<List<FavoriteEntity>> =
        rows.map { it.values.sortedByDescending(FavoriteEntity::addedAt) }

    override fun observeBySite(siteId: String): Flow<List<FavoriteEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId }.sortedByDescending(FavoriteEntity::addedAt) }

    override fun observeByKind(kind: FavoriteKind): Flow<List<FavoriteEntity>> =
        rows.map { it.values.filter { e -> e.kind == kind }.sortedByDescending(FavoriteEntity::addedAt) }

    override fun observeBySiteAndKind(siteId: String, kind: FavoriteKind): Flow<List<FavoriteEntity>> =
        rows.map {
            it.values.filter { e -> e.siteId == siteId && e.kind == kind }.sortedByDescending(FavoriteEntity::addedAt)
        }

    override fun observeMediaFavorites(): Flow<List<FavoriteEntity>> =
        rows.map {
            it.values.filter { e ->
                e.kind == FavoriteKind.VIDEO || e.kind == FavoriteKind.COMIC || e.kind == FavoriteKind.PHOTO
            }.sortedByDescending(FavoriteEntity::addedAt)
        }

    override fun observeExists(siteId: String, kind: FavoriteKind, targetId: String): Flow<Boolean> =
        rows.map { Triple(siteId, kind, targetId) in it }

    override suspend fun exists(siteId: String, kind: FavoriteKind, targetId: String): Boolean =
        Triple(siteId, kind, targetId) in rows.value

    override suspend fun getAll(): List<FavoriteEntity> =
        rows.value.values.sortedByDescending(FavoriteEntity::addedAt)

    override suspend fun upsert(entity: FavoriteEntity) {
        rows.value = rows.value + (Triple(entity.siteId, entity.kind, entity.targetId) to entity)
    }

    override suspend fun upsertAll(entities: List<FavoriteEntity>) {
        rows.value = rows.value + entities.associateBy { Triple(it.siteId, it.kind, it.targetId) }
    }

    override suspend fun delete(siteId: String, kind: FavoriteKind, targetId: String) {
        rows.value = rows.value - Triple(siteId, kind, targetId)
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeShelfDao(
    private val shelfItemDao: FakeShelfItemDao,
) : ShelfDao {
    val rows = MutableStateFlow<Map<String, ShelfEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ShelfEntity>> =
        rows.map { map ->
            map.values.sortedWith(compareByDescending<ShelfEntity> { it.isPinned }.thenByDescending { it.modifiedAt })
        }

    override fun observeById(id: String): Flow<ShelfEntity?> = rows.map { it[id] }
    override suspend fun getById(id: String): ShelfEntity? = rows.value[id]
    override suspend fun getAll(): List<ShelfEntity> =
        rows.value.values.sortedWith(compareByDescending<ShelfEntity> { it.isPinned }.thenByDescending { it.modifiedAt })

    override suspend fun insertIgnore(entity: ShelfEntity): Long {
        if (entity.id in rows.value) return -1L
        rows.value = rows.value + (entity.id to entity)
        return 1L
    }

    override suspend fun upsert(entity: ShelfEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<ShelfEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun updateModifiedAt(id: String, modifiedAt: Long) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to current.copy(modifiedAt = modifiedAt))
    }

    override suspend fun clearCoverIfMatching(id: String, compositeKey: String, modifiedAt: Long) {
        val current = rows.value[id] ?: return
        if (current.coverCompositeKey == compositeKey) {
            rows.value = rows.value + (id to current.copy(coverCompositeKey = null, modifiedAt = modifiedAt))
        }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
        shelfItemDao.deleteByShelf(id) // mirrors FK ON DELETE CASCADE
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
        shelfItemDao.deleteAll()
    }
}

internal class FakeShelfItemDao : ShelfItemDao {
    val rows = MutableStateFlow<Map<Triple<String, String, String>, ShelfItemEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ShelfItemEntity>> =
        rows.map { map ->
            map.values.sortedWith(
                compareBy<ShelfItemEntity> { it.shelfId }.thenBy { it.position }.thenBy { it.addedAt },
            )
        }

    override fun observeByShelf(shelfId: String): Flow<List<ShelfItemEntity>> =
        rows.map { map ->
            map.values.filter { it.shelfId == shelfId }
                .sortedWith(compareBy<ShelfItemEntity> { it.position }.thenBy { it.addedAt })
        }

    override fun observeContainingShelfIds(siteId: String, mediaId: String): Flow<List<String>> =
        rows.map { map -> map.values.filter { it.siteId == siteId && it.mediaId == mediaId }.map { it.shelfId } }

    override suspend fun getByShelf(shelfId: String): List<ShelfItemEntity> =
        rows.value.values.filter { it.shelfId == shelfId }
            .sortedWith(compareBy<ShelfItemEntity> { it.position }.thenBy { it.addedAt })

    override suspend fun getAll(): List<ShelfItemEntity> =
        rows.value.values.sortedWith(
            compareBy<ShelfItemEntity> { it.shelfId }.thenBy { it.position }.thenBy { it.addedAt },
        )

    override suspend fun getOne(shelfId: String, siteId: String, mediaId: String): ShelfItemEntity? =
        rows.value[Triple(shelfId, siteId, mediaId)]

    override suspend fun getMaxPosition(shelfId: String): Int? =
        rows.value.values.filter { it.shelfId == shelfId }.maxOfOrNull { it.position }

    override suspend fun upsert(entity: ShelfItemEntity) {
        rows.value = rows.value + (Triple(entity.shelfId, entity.siteId, entity.mediaId) to entity)
    }

    override suspend fun upsertAll(entities: List<ShelfItemEntity>) {
        rows.value = rows.value + entities.associateBy { Triple(it.shelfId, it.siteId, it.mediaId) }
    }

    override suspend fun updateCompleted(shelfId: String, siteId: String, mediaId: String, completed: Boolean, changedAt: Long) {
        val key = Triple(shelfId, siteId, mediaId)
        val current = rows.value[key] ?: return
        rows.value = rows.value + (key to current.copy(completed = completed, completionChangedAt = changedAt))
    }

    override suspend fun updatePosition(shelfId: String, siteId: String, mediaId: String, position: Int) {
        val key = Triple(shelfId, siteId, mediaId)
        val current = rows.value[key] ?: return
        rows.value = rows.value + (key to current.copy(position = position))
    }

    override suspend fun delete(shelfId: String, siteId: String, mediaId: String) {
        rows.value = rows.value - Triple(shelfId, siteId, mediaId)
    }

    override suspend fun deleteByShelf(shelfId: String) {
        rows.value = rows.value.filterKeys { it.first != shelfId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeSmartShelfDao : SmartShelfDao {
    val rows = MutableStateFlow<Map<String, SmartShelfEntity>>(emptyMap())

    override fun observeAll(): Flow<List<SmartShelfEntity>> =
        rows.map { map ->
            map.values.sortedWith(
                compareByDescending<SmartShelfEntity> { it.isPinned }.thenByDescending { it.createdAt },
            )
        }

    override fun observeById(id: String): Flow<SmartShelfEntity?> = rows.map { it[id] }
    override suspend fun getById(id: String): SmartShelfEntity? = rows.value[id]
    override suspend fun getAll(): List<SmartShelfEntity> =
        rows.value.values.sortedWith(
            compareByDescending<SmartShelfEntity> { it.isPinned }.thenByDescending { it.createdAt },
        )

    override suspend fun upsert(entity: SmartShelfEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<SmartShelfEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}
