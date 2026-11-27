package com.raven.app.core.database.repository

import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.RavenDatabase
import com.raven.app.core.database.dao.BlockedTermDao
import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.dao.FavoriteDao
import com.raven.app.core.database.dao.ReadingProgressDao
import com.raven.app.core.database.dao.ShelfDao
import com.raven.app.core.database.dao.ShelfItemDao
import com.raven.app.core.database.dao.SmartShelfDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.domain.SmartShelfEvaluator
import com.raven.app.core.database.entity.ShelfEntity
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.model.SmartShelfEntry
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Manages built-in `"watch-later"` shelf, custom shelves (notes, pinning, custom cover, manual reorder,
 * completion tracking), and [SmartShelf] CRUD + live dynamic rule evaluation.
 */
class ShelfRepository(
    private val shelfDao: ShelfDao,
    private val shelfItemDao: ShelfItemDao,
    private val smartShelfDao: SmartShelfDao,
    private val watchProgressDao: WatchProgressDao,
    private val readingProgressDao: ReadingProgressDao,
    private val favoriteDao: FavoriteDao,
    private val downloadItemDao: DownloadItemDao,
    private val blockedTermDao: BlockedTermDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ---- Built-in Watch Later guarantee ----

    suspend fun ensureWatchLaterShelf(
        defaultName: String = RavenDatabase.DEFAULT_WATCH_LATER_NAME,
    ): Shelf {
        val now = clock()
        shelfDao.insertIgnore(
            ShelfEntity(
                id = Shelf.WATCH_LATER_ID,
                name = defaultName,
                note = "",
                isPinned = false,
                isSystem = true,
                coverCompositeKey = null,
                createdAt = now,
                modifiedAt = now,
            ),
        )
        return requireNotNull(shelfDao.getById(Shelf.WATCH_LATER_ID)).toDomain()
    }

    // ---- Custom & System Shelves ----

    fun observeShelves(): Flow<List<Shelf>> =
        shelfDao.observeAll()
            .onStart { ensureWatchLaterShelf() }
            .map { rows -> rows.map { it.toDomain() } }

    fun observeShelvesWithSummary(): Flow<List<ShelfWithSummary>> =
        combine(
            observeShelves(),
            shelfItemDao.observeAll().map { rows -> rows.map { it.toDomain() } },
        ) { shelves, allItems ->
            val itemsByShelf = allItems.groupBy { it.shelfId }
            shelves.map { shelf ->
                val items = itemsByShelf[shelf.id].orEmpty()
                val explicitCover = shelf.coverCompositeKey?.let { key ->
                    items.firstOrNull { it.compositeKey == key }?.coverUrl?.takeIf(String::isNotBlank)
                }
                val covers = if (explicitCover != null) {
                    listOf(explicitCover)
                } else {
                    items.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.take(3)
                }
                ShelfWithSummary(
                    shelf = shelf,
                    itemCount = items.size,
                    completedCount = items.count { it.completed },
                    previewCoverUrls = covers,
                )
            }
        }

    fun observeShelf(shelfId: String): Flow<Shelf?> =
        shelfDao.observeById(shelfId)
            .onStart {
                if (shelfId == Shelf.WATCH_LATER_ID) ensureWatchLaterShelf()
            }
            .map { it?.toDomain() }

    fun observeShelfItems(shelfId: String): Flow<List<ShelfItem>> =
        shelfItemDao.observeByShelf(shelfId).map { rows -> rows.map { it.toDomain() } }

    fun observeShelfItemKeys(shelfId: String): Flow<Set<String>> =
        observeShelfItems(shelfId).map { items -> items.mapTo(linkedSetOf()) { it.compositeKey } }

    fun observeContainingShelfIds(siteId: SiteId, mediaId: String): Flow<List<String>> =
        shelfItemDao.observeContainingShelfIds(siteId.value, mediaId)

    suspend fun createShelf(
        name: String,
        note: String = "",
        isPinned: Boolean = false,
        id: String = UUID.randomUUID().toString(),
    ): Shelf {
        val cleanName = name.trim()
        val cleanNote = note.trim()
        require(cleanName.length in 1..MAX_SHELF_NAME_LENGTH) {
            "Shelf name must be 1..$MAX_SHELF_NAME_LENGTH characters"
        }
        require(cleanNote.length <= MAX_SHELF_NOTE_LENGTH) {
            "Shelf note must be at most $MAX_SHELF_NOTE_LENGTH characters"
        }
        require(id.isNotBlank() && id != Shelf.WATCH_LATER_ID) {
            "Invalid custom shelf id"
        }
        val now = clock()
        val shelf = Shelf(
            id = id,
            name = cleanName,
            note = cleanNote,
            isPinned = isPinned,
            isSystem = false,
            coverCompositeKey = null,
            createdAt = now,
            modifiedAt = now,
        )
        shelfDao.upsert(shelf.toEntity())
        return shelf
    }

    suspend fun updateShelf(
        shelfId: String,
        name: String? = null,
        note: String? = null,
        isPinned: Boolean? = null,
        coverCompositeKey: String? = null,
        clearCover: Boolean = false,
    ): Shelf = transactionRunner {
        if (shelfId == Shelf.WATCH_LATER_ID) ensureWatchLaterShelf()
        val existing = requireNotNull(shelfDao.getById(shelfId)) { "Shelf not found: $shelfId" }.toDomain()

        val nextName = name?.trim() ?: existing.name
        val nextNote = note?.trim() ?: existing.note
        val nextPinned = isPinned ?: existing.isPinned
        val nextCover = when {
            clearCover -> null
            coverCompositeKey != null -> coverCompositeKey.trim().takeIf(String::isNotEmpty)
            else -> existing.coverCompositeKey
        }

        require(nextName.length in 1..MAX_SHELF_NAME_LENGTH) {
            "Shelf name must be 1..$MAX_SHELF_NAME_LENGTH characters"
        }
        require(nextNote.length <= MAX_SHELF_NOTE_LENGTH) {
            "Shelf note must be at most $MAX_SHELF_NOTE_LENGTH characters"
        }
        if (shelfId == Shelf.WATCH_LATER_ID) {
            require(nextName == existing.name) { "Built-in watch-later shelf cannot be renamed" }
        }
        if (nextCover != null) {
            val items = shelfItemDao.getByShelf(shelfId)
            require(items.any { "${it.siteId}:${it.mediaId}" == nextCover }) {
                "Cover item $nextCover is not in shelf $shelfId"
            }
        }

        val updated = existing.copy(
            name = nextName,
            note = nextNote,
            isPinned = nextPinned,
            coverCompositeKey = nextCover,
            modifiedAt = nextModifiedAt(existing.modifiedAt),
        )
        shelfDao.upsert(updated.toEntity())
        updated
    }

    suspend fun deleteShelf(shelfId: String) {
        require(shelfId != Shelf.WATCH_LATER_ID) { "Built-in watch-later shelf cannot be deleted" }
        shelfDao.deleteById(shelfId)
    }

    suspend fun addItem(shelfId: String, item: ShelfItem): Boolean {
        require(item.mediaId.isNotBlank()) { "ShelfItem.mediaId must not be blank" }
        return transactionRunner {
            if (shelfId == Shelf.WATCH_LATER_ID) ensureWatchLaterShelf()
            val shelf = requireNotNull(shelfDao.getById(shelfId)) { "Shelf not found: $shelfId" }
            val existing = shelfItemDao.getOne(shelfId, item.siteId.value, item.mediaId)
            if (existing != null) return@transactionRunner false

            val nextPos = (shelfItemDao.getMaxPosition(shelfId) ?: -1) + 1
            val now = clock()
            val prepared = item.copy(
                shelfId = shelfId,
                position = nextPos,
                addedAt = item.addedAt.takeIf { it > 0L } ?: now,
            )
            shelfItemDao.upsert(prepared.toEntity())
            shelfDao.updateModifiedAt(shelfId, nextModifiedAt(shelf.modifiedAt))
            true
        }
    }

    suspend fun addItems(shelfId: String, items: List<ShelfItem>): Int {
        if (items.isEmpty()) return 0
        require(items.all { it.mediaId.isNotBlank() }) { "ShelfItem.mediaId must not be blank" }
        return transactionRunner {
            if (shelfId == Shelf.WATCH_LATER_ID) ensureWatchLaterShelf()
            val shelf = requireNotNull(shelfDao.getById(shelfId)) { "Shelf not found: $shelfId" }
            val existingKeys = shelfItemDao.getByShelf(shelfId)
                .mapTo(mutableSetOf()) { "${it.siteId}:${it.mediaId}" }
            var nextPos = (shelfItemDao.getMaxPosition(shelfId) ?: -1) + 1
            val now = clock()
            val toInsert = buildList {
                for (item in items) {
                    if (existingKeys.add(item.compositeKey)) {
                        add(
                            item.copy(
                                shelfId = shelfId,
                                position = nextPos++,
                                addedAt = item.addedAt.takeIf { it > 0L } ?: now,
                            ).toEntity(),
                        )
                    }
                }
            }
            if (toInsert.isNotEmpty()) {
                shelfItemDao.upsertAll(toInsert)
                shelfDao.updateModifiedAt(shelfId, nextModifiedAt(shelf.modifiedAt))
            }
            toInsert.size
        }
    }

    suspend fun toggleItem(shelfId: String, item: ShelfItem): Boolean = transactionRunner {
        if (shelfId == Shelf.WATCH_LATER_ID) ensureWatchLaterShelf()
        val existing = shelfItemDao.getOne(shelfId, item.siteId.value, item.mediaId)
        if (existing != null) {
            removeItemInternal(shelfId, item.siteId, item.mediaId)
            false
        } else {
            val shelf = requireNotNull(shelfDao.getById(shelfId)) { "Shelf not found: $shelfId" }
            val nextPos = (shelfItemDao.getMaxPosition(shelfId) ?: -1) + 1
            val now = clock()
            shelfItemDao.upsert(
                item.copy(
                    shelfId = shelfId,
                    position = nextPos,
                    addedAt = item.addedAt.takeIf { it > 0L } ?: now,
                ).toEntity(),
            )
            shelfDao.updateModifiedAt(shelfId, nextModifiedAt(shelf.modifiedAt))
            true
        }
    }

    suspend fun removeItem(shelfId: String, siteId: SiteId, mediaId: String) {
        transactionRunner {
            removeItemInternal(shelfId, siteId, mediaId)
        }
    }

    private suspend fun removeItemInternal(shelfId: String, siteId: SiteId, mediaId: String) {
        val shelf = shelfDao.getById(shelfId) ?: return
        shelfItemDao.delete(shelfId, siteId.value, mediaId)
        val bumped = nextModifiedAt(shelf.modifiedAt)
        shelfDao.clearCoverIfMatching(shelfId, "${siteId.value}:$mediaId", bumped)
        shelfDao.updateModifiedAt(shelfId, bumped)
    }

    suspend fun setItemCompleted(
        shelfId: String,
        siteId: SiteId,
        mediaId: String,
        completed: Boolean,
    ) {
        transactionRunner {
            val shelf = shelfDao.getById(shelfId) ?: return@transactionRunner
            val changedAt = nextModifiedAt(shelf.modifiedAt)
            shelfItemDao.updateCompleted(shelfId, siteId.value, mediaId, completed, changedAt)
            shelfDao.updateModifiedAt(shelfId, changedAt)
        }
    }

    suspend fun moveItem(
        shelfId: String,
        siteId: SiteId,
        mediaId: String,
        delta: Int,
    ): Boolean {
        if (delta == 0) return false
        return transactionRunner {
            val shelf = shelfDao.getById(shelfId) ?: return@transactionRunner false
            val items = shelfItemDao.getByShelf(shelfId).toMutableList()
            val from = items.indexOfFirst { it.siteId == siteId.value && it.mediaId == mediaId }
            val to = from + delta
            if (from !in items.indices || to !in items.indices) return@transactionRunner false
            val moved = items.removeAt(from)
            items.add(to, moved)
            items.forEachIndexed { index, entity ->
                if (entity.position != index) {
                    shelfItemDao.updatePosition(shelfId, entity.siteId, entity.mediaId, index)
                }
            }
            shelfDao.updateModifiedAt(shelfId, nextModifiedAt(shelf.modifiedAt))
            true
        }
    }

    suspend fun reorderItems(
        shelfId: String,
        orderedKeys: List<Pair<SiteId, String>>,
    ) {
        transactionRunner {
            val shelf = shelfDao.getById(shelfId) ?: return@transactionRunner
            val existing = shelfItemDao.getByShelf(shelfId)
            val byKey = existing.associateBy { SiteId(it.siteId) to it.mediaId }
            val ordered = buildList {
                val seen = mutableSetOf<Pair<SiteId, String>>()
                for (key in orderedKeys) {
                    val entity = byKey[key]
                    if (entity != null && seen.add(key)) add(entity)
                }
                for (entity in existing) {
                    val key = SiteId(entity.siteId) to entity.mediaId
                    if (seen.add(key)) add(entity)
                }
            }
            ordered.forEachIndexed { index, entity ->
                if (entity.position != index) {
                    shelfItemDao.updatePosition(shelfId, entity.siteId, entity.mediaId, index)
                }
            }
            shelfDao.updateModifiedAt(shelfId, nextModifiedAt(shelf.modifiedAt))
        }
    }

    // ---- Smart Shelves CRUD & Live Evaluator ----

    fun observeSmartShelves(): Flow<List<SmartShelf>> =
        smartShelfDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeSmartShelf(id: String): Flow<SmartShelf?> =
        smartShelfDao.observeById(id).map { it?.toDomain() }

    suspend fun saveSmartShelf(smartShelf: SmartShelf): SmartShelf {
        val cleanName = smartShelf.name.trim()
        require(cleanName.length in 1..MAX_SHELF_NAME_LENGTH) {
            "SmartShelf name must be 1..$MAX_SHELF_NAME_LENGTH characters"
        }
        val prepared = smartShelf.copy(
            id = smartShelf.id.trim().ifBlank { UUID.randomUUID().toString() },
            name = cleanName,
            createdAt = smartShelf.createdAt.takeIf { it > 0L } ?: clock(),
        )
        smartShelfDao.upsert(prepared.toEntity())
        return prepared
    }

    suspend fun deleteSmartShelf(id: String) {
        smartShelfDao.deleteById(id)
    }

    fun observeCatalog(): Flow<List<SmartShelfEntry>> {
        val progressAndFavs = combine(
            watchProgressDao.observeAll().map { rows -> rows.map { it.toDomain() } },
            readingProgressDao.observeAll().map { rows -> rows.map { it.toDomain() } },
            favoriteDao.observeMediaFavorites().map { rows -> rows.map { it.toDomain() } },
        ) { watches, readings, favs -> Triple(watches, readings, favs) }

        val shelvesDownloadsBlocked = combine(
            shelfItemDao.observeAll().map { rows -> rows.map { it.toDomain() } },
            downloadItemDao.observeAll().map { rows -> rows.map { it.toDomain() } },
            blockedTermDao.observeAll().map { rows -> rows.map { it.toDomain() } },
        ) { shelfItems, downloads, blocked -> Triple(shelfItems, downloads, blocked) }

        return combine(progressAndFavs, shelvesDownloadsBlocked) { left, right ->
            SmartShelfEvaluator.buildCatalog(
                watchProgress = left.first,
                readingProgress = left.second,
                favorites = left.third,
                shelfItems = right.first,
                downloads = right.second,
                blockedTerms = right.third,
            )
        }
    }

    fun observeSmartShelfEntries(
        smartShelf: SmartShelf,
        nowProvider: () -> Long = clock,
    ): Flow<List<SmartShelfEntry>> =
        observeCatalog().map { catalog ->
            SmartShelfEvaluator.evaluate(smartShelf, catalog, now = nowProvider())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSmartShelfEntries(
        smartShelfId: String,
        nowProvider: () -> Long = clock,
    ): Flow<List<SmartShelfEntry>> =
        observeSmartShelf(smartShelfId).flatMapLatest { shelf ->
            if (shelf == null) flowOf(emptyList()) else observeSmartShelfEntries(shelf, nowProvider)
        }

    private fun nextModifiedAt(previous: Long): Long =
        maxOf(clock(), previous.coerceAtMost(Long.MAX_VALUE - 1L) + 1L)

    companion object {
        const val MAX_SHELF_NAME_LENGTH: Int = 80
        const val MAX_SHELF_NOTE_LENGTH: Int = 1_000
    }
}
