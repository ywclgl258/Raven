package com.raven.app.core.database.repository

import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.SavedSearchDao
import com.raven.app.core.database.dao.TagSubscriptionDao
import com.raven.app.core.database.dao.SeriesFollowDao
import com.raven.app.core.database.dao.InboxDismissedDao
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.entity.InboxDismissedEntity
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.SeriesFollow
import com.raven.app.core.model.UpdateInboxItem
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages per-site tag/author subscriptions (with manual sort order), multi-site saved searches,
 * and the unified update inbox (read/unread/mark-all-read).
 */
class SubscriptionRepository(
    private val tagSubscriptionDao: TagSubscriptionDao,
    private val savedSearchDao: SavedSearchDao,
    private val updateInboxDao: UpdateInboxDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
    private val seriesFollowDao: SeriesFollowDao? = null,
    private val inboxDismissedDao: InboxDismissedDao? = null,
) {
    // ---- Tag subscriptions ----

    fun observeAllSubscriptions(): Flow<List<TagSubscription>> =
        tagSubscriptionDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeSubscriptions(siteId: SiteId): Flow<List<TagSubscription>> =
        tagSubscriptionDao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }

    fun observeIsSubscribed(
        siteId: SiteId,
        slug: String,
        scope: SearchScope = SearchScope.TAG,
    ): Flow<Boolean> =
        tagSubscriptionDao.observeExists(siteId.value, slug, scope)

    suspend fun subscribe(subscription: TagSubscription): TagSubscription {
        val cleanSlug = subscription.slug.trim()
        require(cleanSlug.isNotEmpty()) { "TagSubscription.slug must not be blank" }
        return transactionRunner {
            val existing = tagSubscriptionDao.getOne(
                subscription.siteId.value,
                cleanSlug,
                subscription.scope,
            )
            val sortOrder = existing?.sortOrder
                ?: ((tagSubscriptionDao.getMaxSortOrder(subscription.siteId.value) ?: -1) + 1)
            val prepared = subscription.copy(
                slug = cleanSlug,
                label = subscription.label.trim().ifBlank { existing?.label ?: cleanSlug },
                sortOrder = sortOrder,
                createdAt = existing?.createdAt
                    ?: subscription.createdAt.takeIf { it > 0L }
                    ?: clock(),
            )
            tagSubscriptionDao.upsert(prepared.toEntity())
            prepared
        }
    }

    suspend fun unsubscribe(
        siteId: SiteId,
        slug: String,
        scope: SearchScope = SearchScope.TAG,
    ) {
        transactionRunner { deleteSubscription(siteId, slug.trim(), scope) }
    }

    private suspend fun deleteSubscription(siteId: SiteId, slug: String, scope: SearchScope) {
        tagSubscriptionDao.delete(siteId.value, slug, scope)
        updateInboxDao.deleteBySource(InboxSourceKind.TAG_SUBSCRIPTION, "${siteId.value}:${scope.name}:$slug")
        inboxDismissedDao?.deleteBySource(InboxSourceKind.TAG_SUBSCRIPTION, "${siteId.value}:${scope.name}:$slug")
    }

    /**
     * Toggles a tag subscription. Returns `true` if now subscribed, `false` if unsubscribed.
     */
    suspend fun toggleSubscription(subscription: TagSubscription): Boolean {
        val cleanSlug = subscription.slug.trim()
        require(cleanSlug.isNotEmpty()) { "TagSubscription.slug must not be blank" }
        return transactionRunner {
            val existing = tagSubscriptionDao.getOne(
                subscription.siteId.value,
                cleanSlug,
                subscription.scope,
            )
            if (existing != null) {
                deleteSubscription(subscription.siteId, cleanSlug, subscription.scope)
                false
            } else {
                val nextOrder = (tagSubscriptionDao.getMaxSortOrder(subscription.siteId.value) ?: -1) + 1
                val prepared = subscription.copy(
                    slug = cleanSlug,
                    label = subscription.label.trim().ifBlank { cleanSlug },
                    sortOrder = nextOrder,
                    createdAt = subscription.createdAt.takeIf { it > 0L } ?: clock(),
                )
                tagSubscriptionDao.upsert(prepared.toEntity())
                true
            }
        }
    }

    suspend fun renameSubscription(
        siteId: SiteId,
        slug: String,
        scope: SearchScope = SearchScope.TAG,
        newLabel: String,
    ) {
        val cleanSlug = slug.trim()
        val label = newLabel.trim().ifBlank { cleanSlug }
        tagSubscriptionDao.updateLabel(siteId.value, cleanSlug, scope, label)
    }

    suspend fun moveSubscription(siteId: SiteId, fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex == toIndex) return false
        return transactionRunner {
            val list = tagSubscriptionDao.getBySite(siteId.value).toMutableList()
            if (fromIndex !in list.indices || toIndex !in list.indices) return@transactionRunner false
            val moved = list.removeAt(fromIndex)
            list.add(toIndex, moved)
            list.forEachIndexed { index, entity ->
                if (entity.sortOrder != index) {
                    tagSubscriptionDao.updateSortOrder(entity.siteId, entity.slug, entity.scope, index)
                }
            }
            true
        }
    }

    suspend fun reorderSubscriptions(
        siteId: SiteId,
        orderedKeys: List<Pair<String, SearchScope>>,
    ) {
        transactionRunner {
            val existing = tagSubscriptionDao.getBySite(siteId.value)
            val byKey = existing.associateBy { it.slug to it.scope }
            val ordered = buildList {
                val seen = mutableSetOf<Pair<String, SearchScope>>()
                for (key in orderedKeys) {
                    val entity = byKey[key]
                    if (entity != null && seen.add(key)) add(entity)
                }
                for (entity in existing) {
                    val key = entity.slug to entity.scope
                    if (seen.add(key)) add(entity)
                }
            }
            ordered.forEachIndexed { index, entity ->
                if (entity.sortOrder != index) {
                    tagSubscriptionDao.updateSortOrder(entity.siteId, entity.slug, entity.scope, index)
                }
            }
        }
    }

    suspend fun clearSubscriptions(siteId: SiteId) {
        tagSubscriptionDao.deleteBySite(siteId.value)
    }

    // ---- Saved searches ----

    fun observeSavedSearches(): Flow<List<SavedSearch>> =
        savedSearchDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeSavedSearch(id: String): Flow<SavedSearch?> =
        savedSearchDao.observeById(id).map { it?.toDomain() }

    suspend fun saveSavedSearch(search: SavedSearch): SavedSearch {
        val cleanName = search.name.trim()
        require(cleanName.length in 1..MAX_SAVED_SEARCH_NAME_LENGTH) {
            "SavedSearch name must be 1..$MAX_SAVED_SEARCH_NAME_LENGTH characters"
        }
        require(search.siteIds.isNotEmpty()) { "SavedSearch must target at least one site" }
        val prepared = search.copy(
            id = search.id.trim().ifBlank { UUID.randomUUID().toString() },
            name = cleanName,
            query = search.query.trim(),
            siteIds = search.siteIds.distinct(),
            createdAt = search.createdAt.takeIf { it > 0L } ?: clock(),
        )
        savedSearchDao.upsert(prepared.toEntity())
        return prepared
    }

    suspend fun renameSavedSearch(id: String, name: String) {
        val cleanName = name.trim()
        require(cleanName.length in 1..MAX_SAVED_SEARCH_NAME_LENGTH) {
            "SavedSearch name must be 1..$MAX_SAVED_SEARCH_NAME_LENGTH characters"
        }
        savedSearchDao.updateName(id, cleanName)
    }

    suspend fun updateLastCheckedAt(id: String, checkedAt: Long = clock()) {
        savedSearchDao.updateLastCheckedAt(id, checkedAt)
    }

    suspend fun deleteSavedSearch(id: String) {
        transactionRunner {
            savedSearchDao.deleteById(id)
            updateInboxDao.deleteBySource(InboxSourceKind.SAVED_SEARCH, id)
            inboxDismissedDao?.deleteBySource(InboxSourceKind.SAVED_SEARCH, id)
        }
    }

    // ---- Unified update inbox ----

    fun observeInbox(): Flow<List<UpdateInboxItem>> =
        updateInboxDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeUnreadInbox(): Flow<List<UpdateInboxItem>> =
        updateInboxDao.observeUnread().map { rows -> rows.map { it.toDomain() } }

    fun observeUnreadCount(): Flow<Int> =
        updateInboxDao.observeUnreadCount()

    fun observeUnreadCountBySourceKind(sourceKind: InboxSourceKind): Flow<Int> =
        updateInboxDao.observeUnreadCountBySourceKind(sourceKind)

    fun observeInboxBySource(
        sourceKind: InboxSourceKind,
        sourceId: String,
    ): Flow<List<UpdateInboxItem>> =
        updateInboxDao.observeBySource(sourceKind, sourceId).map { rows -> rows.map { it.toDomain() } }

    fun observeInboxBySite(siteId: SiteId): Flow<List<UpdateInboxItem>> =
        updateInboxDao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }

    /**
     * Upserts newly discovered inbox entries while preserving `isRead = true` for any item
     * already marked read locally.
     */
    suspend fun upsertInboxItems(items: List<UpdateInboxItem>) {
        if (items.isEmpty()) return
        transactionRunner {
            val normalized = items.map { entry ->
                if (entry.id.isNotBlank()) entry
                else entry.copy(
                    id = "${entry.sourceKind.name}:${entry.sourceId}:${entry.item.compositeKey}",
                )
            }
            val existingById = normalized
                .map { it.id }
                .distinct()
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap { chunk -> updateInboxDao.getByIds(chunk) }
                .associateBy { it.id }

            val merged = normalized.map { entry ->
                val existing = existingById[entry.id]
                val effective = if (existing != null) {
                    entry.copy(
                        isRead = existing.isRead || entry.isRead,
                        discoveredAt = maxOf(existing.discoveredAt, entry.discoveredAt),
                    )
                } else {
                    entry
                }
                effective.toEntity()
            }
            updateInboxDao.upsertAll(merged)
            updateInboxDao.prune(UpdateInboxDao.MAX_INBOX_ITEMS)
        }
    }

    suspend fun markRead(id: String) {
        updateInboxDao.setRead(id, isRead = true)
    }

    suspend fun markRead(ids: Collection<String>) {
        if (ids.isEmpty()) return
        transactionRunner {
            ids.distinct().chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
                updateInboxDao.markReadByIds(chunk)
            }
        }
    }

    suspend fun markUnread(id: String) {
        updateInboxDao.setRead(id, isRead = false)
    }

    suspend fun markAllRead() {
        updateInboxDao.markAllRead()
    }

    suspend fun markAllReadBySourceKind(sourceKind: InboxSourceKind) {
        updateInboxDao.markAllReadBySourceKind(sourceKind)
    }

    suspend fun markAllReadBySource(sourceKind: InboxSourceKind, sourceId: String) {
        updateInboxDao.markAllReadBySource(sourceKind, sourceId)
    }

    suspend fun deleteInboxItem(id: String) {
        updateInboxDao.deleteById(id)
    }

    /**
     * Removes an inbox entry and remembers it as dismissed, so the next sync does not re-add the
     * same item as "newly discovered".
     */
    suspend fun dismissInboxItem(entry: UpdateInboxItem) {
        val dao = inboxDismissedDao ?: run {
            updateInboxDao.deleteById(entry.id)
            return
        }
        transactionRunner {
            updateInboxDao.deleteById(entry.id)
            dao.upsert(
                InboxDismissedEntity(
                    entryKey = "${entry.sourceKind.name}:${entry.sourceId}:${entry.item.compositeKey}",
                    sourceKind = entry.sourceKind,
                    sourceId = entry.sourceId,
                    itemKey = entry.item.compositeKey,
                    dismissedAt = clock(),
                ),
            )
            dao.prune(MAX_DISMISSED_INBOX_KEYS)
        }
    }

    suspend fun dismissedInboxKeys(sourceKind: InboxSourceKind, sourceId: String): Set<String> =
        inboxDismissedDao?.itemKeysForSource(sourceKind, sourceId)?.toSet() ?: emptySet()

    suspend fun clearInboxBySource(sourceKind: InboxSourceKind, sourceId: String) {
        updateInboxDao.deleteBySource(sourceKind, sourceId)
    }

    // ---- Series follows (按作品追更) ----

    fun observeSeriesFollows(): Flow<List<SeriesFollow>> {
        val dao = seriesFollowDao ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    }

    fun observeSeriesFollows(siteId: com.raven.app.core.model.SiteId): Flow<List<SeriesFollow>> {
        val dao = seriesFollowDao ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return dao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }
    }

    suspend fun followSeries(follow: SeriesFollow, chapters: List<com.raven.app.core.model.MediaItem>) {
        validateSeriesCatalog(follow, chapters)
        val dao = requireNotNull(seriesFollowDao) { "seriesFollowDao is required" }
        transactionRunner {
            if (dao.getOne(follow.siteId.value, follow.seriesId) != null) return@transactionRunner
            val checkedAt = clock()
            val prepared = follow.copy(
                title = follow.title.trim().ifBlank { follow.seriesId },
                createdAt = checkedAt,
                lastCheckedAt = checkedAt,
                seenChapterIds = chapters.map { it.id }.distinct(),
            )
            dao.upsert(prepared.toEntity())
        }
    }

    suspend fun syncSeries(follow: SeriesFollow, chapters: List<com.raven.app.core.model.MediaItem>): Int {
        validateSeriesCatalog(follow, chapters)
        val dao = requireNotNull(seriesFollowDao) { "seriesFollowDao is required" }
        return transactionRunner {
            val existing = dao.getOne(follow.siteId.value, follow.seriesId) ?: return@transactionRunner 0
            if (existing.createdAt != follow.createdAt) return@transactionRunner 0
            val seen = existing.seenChapterIds.toSet()
            val newChapters = chapters.filter { it.id !in seen }.distinctBy { it.id }
            if (newChapters.isNotEmpty()) {
                val sourceId = "${follow.siteId.value}:${follow.seriesId}"
                val items = newChapters.map { ch ->
                    com.raven.app.core.model.UpdateInboxItem(
                        id = "SERIES_FOLLOW:$sourceId:${ch.compositeKey}",
                        sourceKind = com.raven.app.core.model.InboxSourceKind.SERIES_FOLLOW,
                        sourceId = sourceId,
                        sourceLabel = follow.title.ifBlank { existing.title },
                        item = ch,
                        discoveredAt = clock(),
                        isRead = false,
                    )
                }
                upsertInboxItems(items)
                dao.updateSeen(
                    follow.siteId.value,
                    follow.seriesId,
                    (existing.seenChapterIds + newChapters.map { it.id }).distinct(),
                )
            }
            dao.updateLastChecked(follow.siteId.value, follow.seriesId, clock())
            newChapters.size
        }
    }

    private fun validateSeriesCatalog(follow: SeriesFollow, chapters: List<com.raven.app.core.model.MediaItem>) {
        require(follow.seriesId.isNotBlank() && chapters.isNotEmpty()) { "章节目录为空，请稍后重试" }
        require(chapters.all { it.siteId == follow.siteId && it.kind.isGallery && it.id.isNotBlank() }) { "章节目录无效，请稍后重试" }
    }

    suspend fun unfollowSeries(siteId: com.raven.app.core.model.SiteId, seriesId: String) {
        val dao = seriesFollowDao ?: return
        transactionRunner {
            dao.delete(siteId.value, seriesId)
            updateInboxDao.deleteBySource(com.raven.app.core.model.InboxSourceKind.SERIES_FOLLOW, "${siteId.value}:$seriesId")
            inboxDismissedDao?.deleteBySource(
                com.raven.app.core.model.InboxSourceKind.SERIES_FOLLOW,
                "${siteId.value}:$seriesId",
            )
        }
    }

    companion object {
        const val MAX_SAVED_SEARCH_NAME_LENGTH: Int = 80
        private const val SQLITE_BIND_CHUNK_SIZE: Int = 500
        private const val MAX_DISMISSED_INBOX_KEYS: Int = 4_000
    }
}
