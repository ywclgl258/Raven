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

internal class FakePageBookmarkDao : PageBookmarkDao {
    val rows = MutableStateFlow<Map<String, PageBookmarkEntity>>(emptyMap())

    override fun observeAll(): Flow<List<PageBookmarkEntity>> =
        rows.map { it.values.sortedByDescending(PageBookmarkEntity::createdAt) }

    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<PageBookmarkEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.page } }

    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, page: Int): PageBookmarkEntity? =
        rows.value.values.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.page == page }

    override suspend fun getAll(): List<PageBookmarkEntity> =
        rows.value.values.sortedByDescending(PageBookmarkEntity::createdAt)

    override suspend fun upsert(entity: PageBookmarkEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<PageBookmarkEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.value = rows.value.filterValues { !(it.siteId == siteId && it.mediaId == mediaId) }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeTimeBookmarkDao : TimeBookmarkDao {
    val rows = MutableStateFlow<Map<String, TimeBookmarkEntity>>(emptyMap())

    override fun observeAll(): Flow<List<TimeBookmarkEntity>> =
        rows.map { it.values.sortedByDescending(TimeBookmarkEntity::createdAt) }

    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<TimeBookmarkEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.positionMs } }

    override suspend fun getAll(): List<TimeBookmarkEntity> =
        rows.value.values.sortedByDescending(TimeBookmarkEntity::createdAt)

    override suspend fun getById(id: String): TimeBookmarkEntity? = rows.value[id]

    override suspend fun upsert(entity: TimeBookmarkEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<TimeBookmarkEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.value = rows.value.filterValues { !(it.siteId == siteId && it.mediaId == mediaId) }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeImageFavoriteDao : ImageFavoriteDao {
    val rows = MutableStateFlow<Map<String, ImageFavoriteEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ImageFavoriteEntity>> =
        rows.map { it.values.sortedByDescending(ImageFavoriteEntity::addedAt) }

    override fun observeBySite(siteId: String): Flow<List<ImageFavoriteEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId }.sortedByDescending(ImageFavoriteEntity::addedAt) }

    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<ImageFavoriteEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.pageIndex } }

    override fun observeExists(siteId: String, mediaId: String, pageIndex: Int): Flow<Boolean> =
        rows.map { it.values.any { e -> e.siteId == siteId && e.mediaId == mediaId && e.pageIndex == pageIndex } }

    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, pageIndex: Int): ImageFavoriteEntity? =
        rows.value.values.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.pageIndex == pageIndex }

    override suspend fun getAll(): List<ImageFavoriteEntity> =
        rows.value.values.sortedByDescending(ImageFavoriteEntity::addedAt)

    override suspend fun upsert(entity: ImageFavoriteEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<ImageFavoriteEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override suspend fun prune(limit: Int) {
        val keep = rows.value.values.sortedByDescending { it.addedAt }.take(limit).mapTo(mutableSetOf()) { it.id }
        rows.value = rows.value.filterKeys { it in keep }
    }
}

internal class FakeTagSubscriptionDao : TagSubscriptionDao {
    val rows = MutableStateFlow<Map<Triple<String, String, SearchScope>, TagSubscriptionEntity>>(emptyMap())

    override fun observeAll(): Flow<List<TagSubscriptionEntity>> =
        rows.map { map ->
            map.values.sortedWith(
                compareBy<TagSubscriptionEntity> { it.siteId }.thenBy { it.sortOrder }.thenBy { it.createdAt },
            )
        }

    override fun observeBySite(siteId: String): Flow<List<TagSubscriptionEntity>> =
        rows.map { map ->
            map.values.filter { it.siteId == siteId }
                .sortedWith(compareBy<TagSubscriptionEntity> { it.sortOrder }.thenBy { it.createdAt })
        }

    override fun observeExists(siteId: String, slug: String, scope: SearchScope): Flow<Boolean> =
        rows.map { Triple(siteId, slug, scope) in it }

    override suspend fun getBySite(siteId: String): List<TagSubscriptionEntity> =
        rows.value.values.filter { it.siteId == siteId }
            .sortedWith(compareBy<TagSubscriptionEntity> { it.sortOrder }.thenBy { it.createdAt })

    override suspend fun getOne(siteId: String, slug: String, scope: SearchScope): TagSubscriptionEntity? =
        rows.value[Triple(siteId, slug, scope)]

    override suspend fun getMaxSortOrder(siteId: String): Int? =
        rows.value.values.filter { it.siteId == siteId }.maxOfOrNull { it.sortOrder }

    override suspend fun getAll(): List<TagSubscriptionEntity> =
        rows.value.values.sortedWith(
            compareBy<TagSubscriptionEntity> { it.siteId }.thenBy { it.sortOrder }.thenBy { it.createdAt },
        )

    override suspend fun upsert(entity: TagSubscriptionEntity) {
        rows.value = rows.value + (Triple(entity.siteId, entity.slug, entity.scope) to entity)
    }

    override suspend fun upsertAll(entities: List<TagSubscriptionEntity>) {
        rows.value = rows.value + entities.associateBy { Triple(it.siteId, it.slug, it.scope) }
    }

    override suspend fun updateLabel(siteId: String, slug: String, scope: SearchScope, label: String) {
        val key = Triple(siteId, slug, scope)
        val current = rows.value[key] ?: return
        rows.value = rows.value + (key to current.copy(label = label))
    }

    override suspend fun updateSortOrder(siteId: String, slug: String, scope: SearchScope, sortOrder: Int) {
        val key = Triple(siteId, slug, scope)
        val current = rows.value[key] ?: return
        rows.value = rows.value + (key to current.copy(sortOrder = sortOrder))
    }

    override suspend fun delete(siteId: String, slug: String, scope: SearchScope) {
        rows.value = rows.value - Triple(siteId, slug, scope)
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeSavedSearchDao : SavedSearchDao {
    val rows = MutableStateFlow<Map<String, SavedSearchEntity>>(emptyMap())

    override fun observeAll(): Flow<List<SavedSearchEntity>> =
        rows.map { it.values.sortedByDescending(SavedSearchEntity::createdAt) }

    override fun observeById(id: String): Flow<SavedSearchEntity?> = rows.map { it[id] }
    override suspend fun getById(id: String): SavedSearchEntity? = rows.value[id]
    override suspend fun getAll(): List<SavedSearchEntity> =
        rows.value.values.sortedByDescending(SavedSearchEntity::createdAt)

    override suspend fun upsert(entity: SavedSearchEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<SavedSearchEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun updateName(id: String, name: String) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to current.copy(name = name))
    }

    override suspend fun updateLastCheckedAt(id: String, checkedAt: Long) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to current.copy(lastCheckedAt = checkedAt))
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeUpdateInboxDao : UpdateInboxDao {
    val rows = MutableStateFlow<Map<String, UpdateInboxEntity>>(emptyMap())

    override fun observeAll(): Flow<List<UpdateInboxEntity>> =
        rows.map { it.values.sortedByDescending(UpdateInboxEntity::discoveredAt) }

    override fun observeUnread(): Flow<List<UpdateInboxEntity>> =
        rows.map { it.values.filterNot(UpdateInboxEntity::isRead).sortedByDescending(UpdateInboxEntity::discoveredAt) }

    override fun observeUnreadCount(): Flow<Int> =
        rows.map { it.values.count { e -> !e.isRead } }

    override fun observeUnreadCountBySourceKind(sourceKind: InboxSourceKind): Flow<Int> =
        rows.map { it.values.count { e -> !e.isRead && e.sourceKind == sourceKind } }

    override fun observeBySource(sourceKind: InboxSourceKind, sourceId: String): Flow<List<UpdateInboxEntity>> =
        rows.map {
            it.values.filter { e -> e.sourceKind == sourceKind && e.sourceId == sourceId }
                .sortedByDescending(UpdateInboxEntity::discoveredAt)
        }

    override fun observeBySite(siteId: String): Flow<List<UpdateInboxEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId }.sortedByDescending(UpdateInboxEntity::discoveredAt) }

    override suspend fun getByIds(ids: List<String>): List<UpdateInboxEntity> =
        ids.mapNotNull { rows.value[it] }

    override suspend fun getAll(): List<UpdateInboxEntity> =
        rows.value.values.sortedByDescending(UpdateInboxEntity::discoveredAt)

    override suspend fun upsert(entity: UpdateInboxEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<UpdateInboxEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun setRead(id: String, isRead: Boolean) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to current.copy(isRead = isRead))
    }

    override suspend fun markReadByIds(ids: List<String>) {
        val set = ids.toSet()
        rows.value = rows.value.mapValues { (k, v) -> if (k in set) v.copy(isRead = true) else v }
    }

    override suspend fun markAllRead() {
        rows.value = rows.value.mapValues { (_, v) -> v.copy(isRead = true) }
    }

    override suspend fun markAllReadBySourceKind(sourceKind: InboxSourceKind) {
        rows.value = rows.value.mapValues { (_, v) ->
            if (v.sourceKind == sourceKind) v.copy(isRead = true) else v
        }
    }

    override suspend fun markAllReadBySource(sourceKind: InboxSourceKind, sourceId: String) {
        rows.value = rows.value.mapValues { (_, v) ->
            if (v.sourceKind == sourceKind && v.sourceId == sourceId) v.copy(isRead = true) else v
        }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteBySource(sourceKind: InboxSourceKind, sourceId: String) {
        rows.value = rows.value.filterValues { !(it.sourceKind == sourceKind && it.sourceId == sourceId) }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override suspend fun prune(limit: Int) {
        val sorted = rows.value.values.sortedByDescending(UpdateInboxEntity::discoveredAt)
        rows.value = sorted.take(limit).associateBy { it.id }
    }
}

internal class FakeBlockedTermDao : BlockedTermDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, BlockedTermEntity>>(emptyMap())

    override fun observeAll(): Flow<List<BlockedTermEntity>> =
        rows.map { map ->
            map.values.sortedWith(compareBy<BlockedTermEntity> { it.siteId }.thenByDescending { it.createdAt })
        }

    override fun observeBySite(siteId: String): Flow<List<BlockedTermEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId }.sortedByDescending(BlockedTermEntity::createdAt) }

    override suspend fun exists(siteId: String, term: String): Boolean = (siteId to term) in rows.value

    override suspend fun getAll(): List<BlockedTermEntity> =
        rows.value.values.sortedWith(compareBy<BlockedTermEntity> { it.siteId }.thenByDescending { it.createdAt })

    override suspend fun upsert(entity: BlockedTermEntity) {
        rows.value = rows.value + ((entity.siteId to entity.term) to entity)
    }

    override suspend fun upsertAll(entities: List<BlockedTermEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId to it.term }
    }

    override suspend fun delete(siteId: String, term: String) {
        rows.value = rows.value - (siteId to term)
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeSearchHistoryDao : SearchHistoryDao {
    val rows = MutableStateFlow<Map<Triple<String, String, SearchScope>, SearchHistoryEntity>>(emptyMap())

    override fun observeBySite(siteId: String, limit: Int): Flow<List<SearchHistoryEntity>> =
        rows.map {
            it.values.filter { e -> e.siteId == siteId }
                .sortedByDescending(SearchHistoryEntity::searchedAt)
                .take(limit)
        }

    override fun observeAll(limit: Int): Flow<List<SearchHistoryEntity>> =
        rows.map {
            it.values.sortedByDescending(SearchHistoryEntity::searchedAt)
                .take(limit)
        }

    override suspend fun getAll(): List<SearchHistoryEntity> =
        rows.value.values.sortedWith(
            compareBy<SearchHistoryEntity> { it.siteId }.thenByDescending { it.searchedAt },
        )

    override suspend fun upsert(entity: SearchHistoryEntity) {
        rows.value = rows.value + (Triple(entity.siteId, entity.query, entity.scope) to entity)
    }

    override suspend fun upsertAll(entities: List<SearchHistoryEntity>) {
        rows.value = rows.value + entities.associateBy { Triple(it.siteId, it.query, it.scope) }
    }

    override suspend fun delete(siteId: String, query: String, scope: SearchScope) {
        rows.value = rows.value - Triple(siteId, query, scope)
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
            .sortedByDescending { it.searchedAt }
            .take(limit)
            .mapTo(mutableSetOf()) { Triple(it.siteId, it.query, it.scope) }
        rows.value = rows.value.filterKeys { it.first != siteId || it in keep }
    }
}

internal class FakeDownloadItemDao : DownloadItemDao {
    val rows = MutableStateFlow<Map<String, DownloadItemEntity>>(emptyMap())

    private fun sort(values: Collection<DownloadItemEntity>): List<DownloadItemEntity> =
        values.sortedWith(
            compareBy<DownloadItemEntity> { it.status.name }
                .thenByDescending { it.priority }
                .thenByDescending { it.updatedAt },
        )

    override fun observeAll(): Flow<List<DownloadItemEntity>> = rows.map { sort(it.values) }

    override fun observeByStatus(status: DownloadStatus): Flow<List<DownloadItemEntity>> =
        rows.map { sort(it.values.filter { e -> e.status == status }) }

    override fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadItemEntity>> =
        rows.map { sort(it.values.filter { e -> e.status in statuses }) }

    override fun observeBySite(siteId: String): Flow<List<DownloadItemEntity>> =
        rows.map { sort(it.values.filter { e -> e.siteId == siteId }) }

    override fun observeById(id: String): Flow<DownloadItemEntity?> = rows.map { it[id] }

    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<DownloadItemEntity>> =
        rows.map {
            it.values.filter { e -> e.siteId == siteId && e.mediaId == mediaId }
                .sortedByDescending(DownloadItemEntity::updatedAt)
        }

    override fun observeCompletedCount(): Flow<Int> =
        rows.map { it.values.count { e -> e.status == DownloadStatus.COMPLETED } }

    override suspend fun getById(id: String): DownloadItemEntity? = rows.value[id]

    override suspend fun getByMedia(siteId: String, mediaId: String): List<DownloadItemEntity> =
        rows.value.values.filter { it.siteId == siteId && it.mediaId == mediaId }
            .sortedByDescending(DownloadItemEntity::updatedAt)

    override suspend fun getAll(): List<DownloadItemEntity> = sort(rows.value.values)

    override suspend fun upsert(entity: DownloadItemEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<DownloadItemEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        errorCategory: DownloadErrorCategory?,
        errorMessage: String?,
        retryAtEpochMs: Long?,
        updatedAt: Long,
    ) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (
            id to current.copy(
                status = status,
                errorCategory = errorCategory,
                errorMessage = errorMessage,
                retryAtEpochMs = retryAtEpochMs,
                updatedAt = updatedAt,
            )
            )
    }

    override suspend fun updateProgress(
        id: String,
        progressFraction: Float,
        completedUnits: Int,
        totalUnits: Int,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAt: Long,
    ) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (
            id to current.copy(
                progressFraction = progressFraction,
                completedUnits = completedUnits,
                totalUnits = totalUnits,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                updatedAt = updatedAt,
            )
            )
    }

    override suspend fun updatePriority(id: String, priority: Int, updatedAt: Long) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to current.copy(priority = priority, updatedAt = updatedAt))
    }

    override suspend fun updateGroupName(id: String, groupName: String, updatedAt: Long) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to current.copy(groupName = groupName, updatedAt = updatedAt))
    }

    override suspend fun updateLocalPath(
        id: String,
        storageLocation: StorageLocation,
        localPath: String?,
        updatedAt: Long,
    ) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (
            id to current.copy(
                storageLocation = storageLocation,
                localPath = localPath,
                updatedAt = updatedAt,
            )
            )
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.value = rows.value.filterValues { !(it.siteId == siteId && it.mediaId == mediaId) }
    }

    override suspend fun deleteByStatus(status: DownloadStatus) {
        rows.value = rows.value.filterValues { it.status != status }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakePlaybackPreferencesDao : PlaybackPreferencesDao {
    val rows = MutableStateFlow<Map<String, PlaybackPreferencesEntity>>(emptyMap())

    override fun observeBySite(siteId: String): Flow<PlaybackPreferencesEntity?> = rows.map { it[siteId] }
    override fun observeAll(): Flow<List<PlaybackPreferencesEntity>> = rows.map { it.values.toList() }
    override suspend fun getBySite(siteId: String): PlaybackPreferencesEntity? = rows.value[siteId]
    override suspend fun getAll(): List<PlaybackPreferencesEntity> = rows.value.values.toList()
    override suspend fun upsert(entity: PlaybackPreferencesEntity) {
        rows.value = rows.value + (entity.siteId to entity)
    }

    override suspend fun upsertAll(entities: List<PlaybackPreferencesEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId }
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value - siteId
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeReaderPreferencesDao : ReaderPreferencesDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, ReaderPreferencesEntity>>(emptyMap())

    override fun observe(siteId: String, mediaId: String): Flow<ReaderPreferencesEntity?> =
        rows.map { it[siteId to mediaId] }

    override fun observeBySite(siteId: String): Flow<List<ReaderPreferencesEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId } }

    override fun observeAll(): Flow<List<ReaderPreferencesEntity>> = rows.map { it.values.toList() }
    override suspend fun get(siteId: String, mediaId: String): ReaderPreferencesEntity? =
        rows.value[siteId to mediaId]

    override suspend fun getAll(): List<ReaderPreferencesEntity> = rows.value.values.toList()
    override suspend fun upsert(entity: ReaderPreferencesEntity) {
        rows.value = rows.value + ((entity.siteId to entity.mediaId) to entity)
    }

    override suspend fun upsertAll(entities: List<ReaderPreferencesEntity>) {
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
}

internal class FakeBrowsePositionDao : BrowsePositionDao {
    val rows = MutableStateFlow<Map<String, BrowsePositionEntity>>(emptyMap())

    override fun observeByKey(positionKey: String): Flow<BrowsePositionEntity?> = rows.map { it[positionKey] }
    override fun observeAll(): Flow<List<BrowsePositionEntity>> =
        rows.map { it.values.sortedByDescending(BrowsePositionEntity::updatedAt) }

    override suspend fun getByKey(positionKey: String): BrowsePositionEntity? = rows.value[positionKey]
    override suspend fun getAll(): List<BrowsePositionEntity> =
        rows.value.values.sortedByDescending(BrowsePositionEntity::updatedAt)

    override suspend fun upsert(entity: BrowsePositionEntity) {
        rows.value = rows.value + (entity.positionKey to entity)
    }

    override suspend fun deleteByKey(positionKey: String) {
        rows.value = rows.value - positionKey
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterValues { it.siteId != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override suspend fun prune(limit: Int) {
        val keep = rows.value.values
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .mapTo(mutableSetOf()) { it.positionKey }
        rows.value = rows.value.filterKeys { it in keep }
    }
}

internal class FakeTagTranslationDao : TagTranslationDao {
    val rows = MutableStateFlow<Map<Triple<TagTranslationSource, String, String>, TagTranslationEntity>>(emptyMap())

    override fun observeBySource(source: TagTranslationSource): Flow<List<TagTranslationEntity>> =
        rows.map { map ->
            map.values.filter { it.source == source }
                .sortedWith(compareByDescending<TagTranslationEntity> { it.updatedAt }.thenBy { it.rawSlug })
        }

    override fun observeCountBySource(source: TagTranslationSource): Flow<Int> =
        rows.map { map -> map.values.count { it.source == source } }

    override fun observeAll(): Flow<List<TagTranslationEntity>> =
        rows.map { map ->
            map.values.sortedWith(
                compareBy<TagTranslationEntity> { it.source.name }
                    .thenBy { it.namespace }
                    .thenBy { it.rawSlug },
            )
        }

    override suspend fun getBySource(source: TagTranslationSource): List<TagTranslationEntity> =
        rows.value.values.filter { it.source == source }
            .sortedWith(compareByDescending<TagTranslationEntity> { it.updatedAt }.thenBy { it.rawSlug })

    override suspend fun getAll(): List<TagTranslationEntity> =
        rows.value.values.sortedWith(
            compareBy<TagTranslationEntity> { it.source.name }
                .thenBy { it.namespace }
                .thenBy { it.rawSlug },
        )

    override suspend fun search(query: String, limit: Int): List<TagTranslationEntity> =
        rows.value.values
            .filter {
                it.rawSlug.contains(query, ignoreCase = true) ||
                    it.chineseName.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareBy<TagTranslationEntity>(
                    { if (it.source == TagTranslationSource.USER_CUSTOM) 0 else 1 },
                    { it.rawSlug.length },
                    { it.rawSlug },
                ),
            )
            .take(limit)

    override suspend fun upsert(entity: TagTranslationEntity) {
        rows.value = rows.value + (Triple(entity.source, entity.namespace, entity.rawSlug) to entity)
    }

    override suspend fun upsertAll(entities: List<TagTranslationEntity>) {
        rows.value = rows.value + entities.associateBy { Triple(it.source, it.namespace, it.rawSlug) }
    }

    override suspend fun delete(source: TagTranslationSource, namespace: String, rawSlug: String) {
        rows.value = rows.value - Triple(source, namespace, rawSlug)
    }

    override suspend fun deleteBySource(source: TagTranslationSource) {
        rows.value = rows.value.filterKeys { it.first != source }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeMediaTagCacheDao : MediaTagCacheDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, MediaTagCacheEntity>>(emptyMap())

    override fun observeAll(): Flow<List<MediaTagCacheEntity>> =
        rows.map { it.values.sortedByDescending(MediaTagCacheEntity::updatedAt) }

    override fun observeBySite(siteId: String): Flow<List<MediaTagCacheEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId }.sortedByDescending(MediaTagCacheEntity::updatedAt) }

    override suspend fun getOne(siteId: String, mediaId: String): MediaTagCacheEntity? =
        rows.value[siteId to mediaId]

    override suspend fun getBySite(siteId: String): List<MediaTagCacheEntity> =
        rows.value.values.filter { it.siteId == siteId }.sortedByDescending(MediaTagCacheEntity::updatedAt)

    override suspend fun upsert(entity: MediaTagCacheEntity) {
        rows.value = rows.value + ((entity.siteId to entity.mediaId) to entity)
    }

    override suspend fun upsertAll(entities: List<MediaTagCacheEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId to it.mediaId }
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override suspend fun prune(limit: Int) {
        val keep = rows.value.values
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .mapTo(mutableSetOf()) { it.siteId to it.mediaId }
        rows.value = rows.value.filterKeys { it in keep }
    }
}

internal class FakeBackupRestoreJournalDao : BackupRestoreJournalDao {
    val rows = MutableStateFlow<BackupRestoreJournalEntity?>(null)

    override suspend fun get(): BackupRestoreJournalEntity? = rows.value

    override suspend fun put(entry: BackupRestoreJournalEntity) {
        rows.value = entry
    }

    override suspend fun clear() {
        rows.value = null
    }
}

internal class FakeSeriesFollowDao : SeriesFollowDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, SeriesFollowEntity>>(emptyMap())

    override fun observeAll(): Flow<List<SeriesFollowEntity>> =
        rows.map { it.values.toList() }

    override fun observeBySite(siteId: String): Flow<List<SeriesFollowEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId } }

    override suspend fun getOne(siteId: String, seriesId: String): SeriesFollowEntity? =
        rows.value[siteId to seriesId]

    override suspend fun getAll(): List<SeriesFollowEntity> =
        rows.value.values.toList()

    override suspend fun upsert(entity: SeriesFollowEntity) {
        rows.value = rows.value + ((entity.siteId to entity.seriesId) to entity)
    }

    override suspend fun upsertAll(entities: List<SeriesFollowEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId to it.seriesId }
    }

    override suspend fun updateSeen(siteId: String, seriesId: String, seenChapterIds: List<String>) {
        val cur = rows.value[siteId to seriesId] ?: return
        rows.value = rows.value + ((siteId to seriesId) to cur.copy(seenChapterIds = seenChapterIds))
    }

    override suspend fun updateLastChecked(siteId: String, seriesId: String, lastCheckedAt: Long) {
        val cur = rows.value[siteId to seriesId] ?: return
        rows.value = rows.value + ((siteId to seriesId) to cur.copy(lastCheckedAt = lastCheckedAt))
    }

    override suspend fun delete(siteId: String, seriesId: String) {
        rows.value = rows.value - (siteId to seriesId)
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}
