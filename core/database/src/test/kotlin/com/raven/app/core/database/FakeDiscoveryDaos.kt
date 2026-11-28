package com.raven.app.core.database

import com.raven.app.core.database.dao.BlockedTermDao
import com.raven.app.core.database.dao.SavedSearchDao
import com.raven.app.core.database.dao.SearchHistoryDao
import com.raven.app.core.database.dao.TagSubscriptionDao
import com.raven.app.core.database.dao.InboxDismissedDao
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.entity.SavedSearchEntity
import com.raven.app.core.database.entity.SearchHistoryEntity
import com.raven.app.core.database.entity.TagSubscriptionEntity
import com.raven.app.core.database.entity.InboxDismissedEntity
import com.raven.app.core.database.entity.UpdateInboxEntity
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.SearchScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

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

internal class FakeInboxDismissedDao : InboxDismissedDao {
    val rows = MutableStateFlow<Map<String, InboxDismissedEntity>>(emptyMap())

    override suspend fun upsert(entity: InboxDismissedEntity) {
        rows.value = rows.value + (entity.entryKey to entity)
    }

    override suspend fun itemKeysForSource(sourceKind: InboxSourceKind, sourceId: String): List<String> =
        rows.value.values
            .filter { it.sourceKind == sourceKind && it.sourceId == sourceId }
            .map(InboxDismissedEntity::itemKey)

    override suspend fun deleteBySource(sourceKind: InboxSourceKind, sourceId: String) {
        rows.value = rows.value.filterValues { it.sourceKind != sourceKind || it.sourceId != sourceId }
    }

    override suspend fun prune(limit: Int) {
        val kept = rows.value.values.sortedByDescending(InboxDismissedEntity::dismissedAt).take(limit)
        rows.value = kept.associateBy(InboxDismissedEntity::entryKey)
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
