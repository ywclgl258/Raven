package com.raven.app.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.BlockedTermDao
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
import com.raven.app.core.database.dao.TagTranslationDao
import com.raven.app.core.database.dao.TimeBookmarkDao
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.entity.BlockedTermEntity
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
import com.raven.app.core.database.entity.TagTranslationEntity
import com.raven.app.core.database.entity.TimeBookmarkEntity
import com.raven.app.core.database.entity.UpdateInboxEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.database.repository.BackupDataRepository
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.security.BackupCodec
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.AuthState
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    val appDispatchers: AppDispatchers = CustomAppDispatchers(dispatcher)

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

internal class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val next = transform(state.value).toPreferences()
            state.value = next
            next
        }
}

internal object ImmediateTransactionRunner : DatabaseTransactionRunner {
    override suspend fun <R> invoke(block: suspend () -> R): R = block()
}

internal class FakeWatchProgressDao : WatchProgressDao {
    private val rows = MutableStateFlow<List<WatchProgressEntity>>(emptyList())
    override fun observeRecent(limit: Int): Flow<List<WatchProgressEntity>> = rows.map { it.take(limit) }
    override fun observeAll(): Flow<List<WatchProgressEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<WatchProgressEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId } }
    override fun observeOne(siteId: String, mediaId: String): Flow<WatchProgressEntity?> =
        rows.map { list -> list.firstOrNull { it.siteId == siteId && it.mediaId == mediaId } }
    override suspend fun getOne(siteId: String, mediaId: String): WatchProgressEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll(): List<WatchProgressEntity> = rows.value
    override suspend fun upsert(entity: WatchProgressEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<WatchProgressEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Pair<String, String>, WatchProgressEntity>()
            current.forEach { map[it.siteId to it.mediaId] = it }
            entities.forEach { map[it.siteId to it.mediaId] = it }
            map.values.sortedByDescending { it.updatedAt }
        }
    }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) = Unit
}

internal class FakeReadingProgressDao : ReadingProgressDao {
    private val rows = MutableStateFlow<List<ReadingProgressEntity>>(emptyList())
    override fun observeRecent(limit: Int): Flow<List<ReadingProgressEntity>> = rows.map { it.take(limit) }
    override fun observeAll(): Flow<List<ReadingProgressEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<ReadingProgressEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId } }
    override fun observeOne(siteId: String, mediaId: String): Flow<ReadingProgressEntity?> =
        rows.map { list -> list.firstOrNull { it.siteId == siteId && it.mediaId == mediaId } }
    override suspend fun getOne(siteId: String, mediaId: String): ReadingProgressEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll(): List<ReadingProgressEntity> = rows.value
    override suspend fun upsert(entity: ReadingProgressEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<ReadingProgressEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Pair<String, String>, ReadingProgressEntity>()
            current.forEach { map[it.siteId to it.mediaId] = it }
            entities.forEach { map[it.siteId to it.mediaId] = it }
            map.values.sortedByDescending { it.updatedAt }
        }
    }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) = Unit
}

internal class FakeFavoriteDao : FavoriteDao {
    private val rows = MutableStateFlow<List<FavoriteEntity>>(emptyList())
    override fun observeAll(): Flow<List<FavoriteEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<FavoriteEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId } }
    override fun observeByKind(kind: FavoriteKind): Flow<List<FavoriteEntity>> =
        rows.map { list -> list.filter { it.kind == kind } }
    override fun observeBySiteAndKind(siteId: String, kind: FavoriteKind): Flow<List<FavoriteEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId && it.kind == kind } }
    override fun observeMediaFavorites(): Flow<List<FavoriteEntity>> =
        rows.map { list -> list.filter { it.kind in setOf(FavoriteKind.VIDEO, FavoriteKind.COMIC, FavoriteKind.PHOTO) } }
    override fun observeExists(siteId: String, kind: FavoriteKind, targetId: String): Flow<Boolean> =
        rows.map { list -> list.any { it.siteId == siteId && it.kind == kind && it.targetId == targetId } }
    override suspend fun exists(siteId: String, kind: FavoriteKind, targetId: String): Boolean =
        rows.value.any { it.siteId == siteId && it.kind == kind && it.targetId == targetId }
    override suspend fun getAll(): List<FavoriteEntity> = rows.value
    override suspend fun upsert(entity: FavoriteEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<FavoriteEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Triple<String, FavoriteKind, String>, FavoriteEntity>()
            current.forEach { map[Triple(it.siteId, it.kind, it.targetId)] = it }
            entities.forEach { map[Triple(it.siteId, it.kind, it.targetId)] = it }
            map.values.sortedByDescending { it.addedAt }
        }
    }
    override suspend fun delete(siteId: String, kind: FavoriteKind, targetId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.kind == kind && it.targetId == targetId } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeShelfDao : ShelfDao {
    private val rows = MutableStateFlow<List<ShelfEntity>>(emptyList())
    override fun observeAll(): Flow<List<ShelfEntity>> = rows
    override fun observeById(id: String): Flow<ShelfEntity?> = rows.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun getById(id: String): ShelfEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll(): List<ShelfEntity> = rows.value
    override suspend fun insertIgnore(entity: ShelfEntity): Long {
        if (rows.value.any { it.id == entity.id }) return -1L
        upsert(entity)
        return 1L
    }
    override suspend fun upsert(entity: ShelfEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<ShelfEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<String, ShelfEntity>()
            current.forEach { map[it.id] = it }
            entities.forEach { map[it.id] = it }
            map.values.toList()
        }
    }
    override suspend fun updateModifiedAt(id: String, modifiedAt: Long) {
        rows.update { list -> list.map { if (it.id == id) it.copy(modifiedAt = modifiedAt) else it } }
    }
    override suspend fun clearCoverIfMatching(id: String, compositeKey: String, modifiedAt: Long) {
        rows.update { list ->
            list.map {
                if (it.id == id && it.coverCompositeKey == compositeKey) {
                    it.copy(coverCompositeKey = null, modifiedAt = modifiedAt)
                } else {
                    it
                }
            }
        }
    }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeShelfItemDao : ShelfItemDao {
    private val rows = MutableStateFlow<List<ShelfItemEntity>>(emptyList())
    override fun observeAll(): Flow<List<ShelfItemEntity>> = rows
    override fun observeByShelf(shelfId: String): Flow<List<ShelfItemEntity>> =
        rows.map { list -> list.filter { it.shelfId == shelfId } }
    override fun observeContainingShelfIds(siteId: String, mediaId: String): Flow<List<String>> =
        rows.map { list -> list.filter { it.siteId == siteId && it.mediaId == mediaId }.map { it.shelfId } }
    override suspend fun getByShelf(shelfId: String): List<ShelfItemEntity> =
        rows.value.filter { it.shelfId == shelfId }
    override suspend fun getAll(): List<ShelfItemEntity> = rows.value
    override suspend fun getOne(shelfId: String, siteId: String, mediaId: String): ShelfItemEntity? =
        rows.value.firstOrNull { it.shelfId == shelfId && it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getMaxPosition(shelfId: String): Int? =
        rows.value.filter { it.shelfId == shelfId }.maxOfOrNull { it.position }
    override suspend fun upsert(entity: ShelfItemEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<ShelfItemEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Triple<String, String, String>, ShelfItemEntity>()
            current.forEach { map[Triple(it.shelfId, it.siteId, it.mediaId)] = it }
            entities.forEach { map[Triple(it.shelfId, it.siteId, it.mediaId)] = it }
            map.values.toList()
        }
    }
    override suspend fun updateCompleted(shelfId: String, siteId: String, mediaId: String, completed: Boolean) = Unit
    override suspend fun updatePosition(shelfId: String, siteId: String, mediaId: String, position: Int) = Unit
    override suspend fun delete(shelfId: String, siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.shelfId == shelfId && it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteByShelf(shelfId: String) {
        rows.update { list -> list.filterNot { it.shelfId == shelfId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeSmartShelfDao : SmartShelfDao {
    private val rows = MutableStateFlow<List<SmartShelfEntity>>(emptyList())
    override fun observeAll(): Flow<List<SmartShelfEntity>> = rows
    override fun observeById(id: String): Flow<SmartShelfEntity?> = rows.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun getById(id: String): SmartShelfEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll(): List<SmartShelfEntity> = rows.value
    override suspend fun upsert(entity: SmartShelfEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<SmartShelfEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<String, SmartShelfEntity>()
            current.forEach { map[it.id] = it }
            entities.forEach { map[it.id] = it }
            map.values.toList()
        }
    }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakePageBookmarkDao : PageBookmarkDao {
    private val rows = MutableStateFlow<List<PageBookmarkEntity>>(emptyList())
    override fun observeAll(): Flow<List<PageBookmarkEntity>> = rows
    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<PageBookmarkEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId && it.mediaId == mediaId } }
    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, page: Int): PageBookmarkEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.page == page }
    override suspend fun getAll(): List<PageBookmarkEntity> = rows.value
    override suspend fun upsert(entity: PageBookmarkEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<PageBookmarkEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<String, PageBookmarkEntity>()
            current.forEach { map[it.id] = it }
            entities.forEach { map[it.id] = it }
            map.values.toList()
        }
    }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeTimeBookmarkDao : TimeBookmarkDao {
    private val rows = MutableStateFlow<List<TimeBookmarkEntity>>(emptyList())
    override fun observeAll(): Flow<List<TimeBookmarkEntity>> = rows
    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<TimeBookmarkEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId && it.mediaId == mediaId } }
    override suspend fun getAll(): List<TimeBookmarkEntity> = rows.value
    override suspend fun upsert(entity: TimeBookmarkEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<TimeBookmarkEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<String, TimeBookmarkEntity>()
            current.forEach { map[it.id] = it }
            entities.forEach { map[it.id] = it }
            map.values.toList()
        }
    }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeImageFavoriteDao : ImageFavoriteDao {
    private val rows = MutableStateFlow<List<ImageFavoriteEntity>>(emptyList())
    override fun observeAll(): Flow<List<ImageFavoriteEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<ImageFavoriteEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId } }
    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<ImageFavoriteEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId && it.mediaId == mediaId } }
    override fun observeExists(siteId: String, mediaId: String, pageIndex: Int): Flow<Boolean> =
        rows.map { list -> list.any { it.siteId == siteId && it.mediaId == mediaId && it.pageIndex == pageIndex } }
    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, pageIndex: Int): ImageFavoriteEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.pageIndex == pageIndex }
    override suspend fun getAll(): List<ImageFavoriteEntity> = rows.value
    override suspend fun upsert(entity: ImageFavoriteEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<ImageFavoriteEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<String, ImageFavoriteEntity>()
            current.forEach { map[it.id] = it }
            entities.forEach { map[it.id] = it }
            map.values.toList()
        }
    }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun prune(limit: Int) = Unit
}

internal class FakeTagSubscriptionDao : TagSubscriptionDao {
    private val rows = MutableStateFlow<List<TagSubscriptionEntity>>(emptyList())
    override fun observeAll(): Flow<List<TagSubscriptionEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<TagSubscriptionEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId } }
    override fun observeExists(siteId: String, slug: String, scope: SearchScope): Flow<Boolean> =
        rows.map { list -> list.any { it.siteId == siteId && it.slug == slug && it.scope == scope } }
    override suspend fun getBySite(siteId: String): List<TagSubscriptionEntity> =
        rows.value.filter { it.siteId == siteId }
    override suspend fun getOne(siteId: String, slug: String, scope: SearchScope): TagSubscriptionEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.slug == slug && it.scope == scope }
    override suspend fun getMaxSortOrder(siteId: String): Int? =
        rows.value.filter { it.siteId == siteId }.maxOfOrNull { it.sortOrder }
    override suspend fun getAll(): List<TagSubscriptionEntity> = rows.value
    override suspend fun upsert(entity: TagSubscriptionEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<TagSubscriptionEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Triple<String, String, SearchScope>, TagSubscriptionEntity>()
            current.forEach { map[Triple(it.siteId, it.slug, it.scope)] = it }
            entities.forEach { map[Triple(it.siteId, it.slug, it.scope)] = it }
            map.values.toList()
        }
    }
    override suspend fun updateLabel(siteId: String, slug: String, scope: SearchScope, label: String) = Unit
    override suspend fun updateSortOrder(siteId: String, slug: String, scope: SearchScope, sortOrder: Int) = Unit
    override suspend fun delete(siteId: String, slug: String, scope: SearchScope) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.slug == slug && it.scope == scope } }
    }
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeSavedSearchDao : SavedSearchDao {
    private val rows = MutableStateFlow<List<SavedSearchEntity>>(emptyList())
    override fun observeAll(): Flow<List<SavedSearchEntity>> = rows
    override fun observeById(id: String): Flow<SavedSearchEntity?> = rows.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun getById(id: String): SavedSearchEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll(): List<SavedSearchEntity> = rows.value
    override suspend fun upsert(entity: SavedSearchEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<SavedSearchEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<String, SavedSearchEntity>()
            current.forEach { map[it.id] = it }
            entities.forEach { map[it.id] = it }
            map.values.toList()
        }
    }
    override suspend fun updateName(id: String, name: String) = Unit
    override suspend fun updateLastCheckedAt(id: String, checkedAt: Long) = Unit
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeUpdateInboxDao : UpdateInboxDao {
    private val rows = MutableStateFlow<List<UpdateInboxEntity>>(emptyList())
    override fun observeAll(): Flow<List<UpdateInboxEntity>> = rows
    override fun observeUnread(): Flow<List<UpdateInboxEntity>> = rows.map { list -> list.filter { !it.isRead } }
    override fun observeUnreadCount(): Flow<Int> = observeUnread().map { it.size }
    override fun observeUnreadCountBySourceKind(sourceKind: InboxSourceKind): Flow<Int> =
        rows.map { list -> list.count { !it.isRead && it.sourceKind == sourceKind } }
    override fun observeBySource(sourceKind: InboxSourceKind, sourceId: String): Flow<List<UpdateInboxEntity>> =
        rows.map { list -> list.filter { it.sourceKind == sourceKind && it.sourceId == sourceId } }
    override fun observeBySite(siteId: String): Flow<List<UpdateInboxEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId } }
    override suspend fun getByIds(ids: List<String>): List<UpdateInboxEntity> = rows.value.filter { it.id in ids }
    override suspend fun getAll(): List<UpdateInboxEntity> = rows.value
    override suspend fun upsert(entity: UpdateInboxEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<UpdateInboxEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<String, UpdateInboxEntity>()
            current.forEach { map[it.id] = it }
            entities.forEach { map[it.id] = it }
            map.values.toList()
        }
    }
    override suspend fun setRead(id: String, isRead: Boolean) = Unit
    override suspend fun markReadByIds(ids: List<String>) = Unit
    override suspend fun markAllRead() = Unit
    override suspend fun markAllReadBySourceKind(sourceKind: InboxSourceKind) = Unit
    override suspend fun markAllReadBySource(sourceKind: InboxSourceKind, sourceId: String) = Unit
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteBySource(sourceKind: InboxSourceKind, sourceId: String) = Unit
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun prune(limit: Int) {
        rows.update { list -> list.sortedByDescending { it.discoveredAt }.take(limit) }
    }
}

internal class FakeBlockedTermDao : BlockedTermDao {
    private val rows = MutableStateFlow<List<BlockedTermEntity>>(emptyList())
    override fun observeAll(): Flow<List<BlockedTermEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<BlockedTermEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId }.sortedByDescending { it.createdAt } }
    override suspend fun exists(siteId: String, term: String): Boolean =
        rows.value.any { it.siteId == siteId && it.term == term }
    override suspend fun getAll(): List<BlockedTermEntity> = rows.value
    override suspend fun upsert(entity: BlockedTermEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<BlockedTermEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Pair<String, String>, BlockedTermEntity>()
            current.forEach { map[it.siteId to it.term] = it }
            entities.forEach { map[it.siteId to it.term] = it }
            map.values.toList()
        }
    }
    override suspend fun delete(siteId: String, term: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.term == term } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeSearchHistoryDao : SearchHistoryDao {
    private val rows = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    override fun observeBySite(siteId: String, limit: Int): Flow<List<SearchHistoryEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId }.take(limit) }
    override suspend fun getAll(): List<SearchHistoryEntity> = rows.value
    override suspend fun upsert(entity: SearchHistoryEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<SearchHistoryEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Triple<String, String, SearchScope>, SearchHistoryEntity>()
            current.forEach { map[Triple(it.siteId, it.query, it.scope)] = it }
            entities.forEach { map[Triple(it.siteId, it.query, it.scope)] = it }
            map.values.toList()
        }
    }
    override suspend fun delete(siteId: String, query: String, scope: SearchScope) = Unit
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) = Unit
}

internal class FakePlaybackPreferencesDao : PlaybackPreferencesDao {
    private val rows = MutableStateFlow<List<PlaybackPreferencesEntity>>(emptyList())
    override fun observeBySite(siteId: String): Flow<PlaybackPreferencesEntity?> =
        rows.map { list -> list.firstOrNull { it.siteId == siteId } }
    override fun observeAll(): Flow<List<PlaybackPreferencesEntity>> = rows
    override suspend fun getBySite(siteId: String): PlaybackPreferencesEntity? =
        rows.value.firstOrNull { it.siteId == siteId }
    override suspend fun getAll(): List<PlaybackPreferencesEntity> = rows.value
    override suspend fun upsert(entity: PlaybackPreferencesEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<PlaybackPreferencesEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<String, PlaybackPreferencesEntity>()
            current.forEach { map[it.siteId] = it }
            entities.forEach { map[it.siteId] = it }
            map.values.toList()
        }
    }
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeReaderPreferencesDao : ReaderPreferencesDao {
    private val rows = MutableStateFlow<List<ReaderPreferencesEntity>>(emptyList())
    override fun observe(siteId: String, mediaId: String): Flow<ReaderPreferencesEntity?> =
        rows.map { list -> list.firstOrNull { it.siteId == siteId && it.mediaId == mediaId } }
    override fun observeBySite(siteId: String): Flow<List<ReaderPreferencesEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId } }
    override fun observeAll(): Flow<List<ReaderPreferencesEntity>> = rows
    override suspend fun get(siteId: String, mediaId: String): ReaderPreferencesEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll(): List<ReaderPreferencesEntity> = rows.value
    override suspend fun upsert(entity: ReaderPreferencesEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<ReaderPreferencesEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Pair<String, String>, ReaderPreferencesEntity>()
            current.forEach { map[it.siteId to it.mediaId] = it }
            entities.forEach { map[it.siteId to it.mediaId] = it }
            map.values.toList()
        }
    }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeTagTranslationDao : TagTranslationDao {
    private val rows = MutableStateFlow<List<TagTranslationEntity>>(emptyList())
    override fun observeBySource(source: TagTranslationSource): Flow<List<TagTranslationEntity>> =
        rows.map { list ->
            list.filter { it.source == source }
                .sortedWith(compareByDescending<TagTranslationEntity> { it.updatedAt }.thenBy { it.rawSlug })
        }
    override fun observeCountBySource(source: TagTranslationSource): Flow<Int> =
        rows.map { list -> list.count { it.source == source } }
    override fun observeAll(): Flow<List<TagTranslationEntity>> = rows
    override suspend fun getBySource(source: TagTranslationSource): List<TagTranslationEntity> =
        rows.value.filter { it.source == source }
    override suspend fun getAll(): List<TagTranslationEntity> = rows.value
    override suspend fun search(query: String, limit: Int): List<TagTranslationEntity> {
        val needle = query.trim().lowercase()
        return rows.value.filter {
            it.rawSlug.lowercase().contains(needle) || it.chineseName.lowercase().contains(needle)
        }.take(limit)
    }
    override suspend fun upsert(entity: TagTranslationEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<TagTranslationEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Triple<TagTranslationSource, String, String>, TagTranslationEntity>()
            current.forEach { map[Triple(it.source, it.namespace, it.rawSlug)] = it }
            entities.forEach { map[Triple(it.source, it.namespace, it.rawSlug)] = it }
            map.values.toList()
        }
    }
    override suspend fun delete(source: TagTranslationSource, namespace: String, rawSlug: String) {
        rows.update { list ->
            list.filterNot { it.source == source && it.namespace == namespace && it.rawSlug == rawSlug }
        }
    }
    override suspend fun deleteBySource(source: TagTranslationSource) {
        rows.update { list -> list.filterNot { it.source == source } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class FakeMediaTagCacheDao : MediaTagCacheDao {
    private val rows = MutableStateFlow<List<MediaTagCacheEntity>>(emptyList())
    override fun observeAll(): Flow<List<MediaTagCacheEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<MediaTagCacheEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId } }
    override suspend fun getOne(siteId: String, mediaId: String): MediaTagCacheEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getBySite(siteId: String): List<MediaTagCacheEntity> =
        rows.value.filter { it.siteId == siteId }
    override suspend fun upsert(entity: MediaTagCacheEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<MediaTagCacheEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Pair<String, String>, MediaTagCacheEntity>()
            current.forEach { map[it.siteId to it.mediaId] = it }
            entities.forEach { map[it.siteId to it.mediaId] = it }
            map.values.sortedByDescending { it.updatedAt }
        }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun prune(limit: Int) {
        rows.update { list -> list.sortedByDescending { it.updatedAt }.take(limit) }
    }
}

internal class FakeDatabaseBundle(
    val settingsDataStore: SettingsDataStore = SettingsDataStore(InMemoryPreferencesDataStore()),
    val runtimeDictionary: TagDictionary = TagDictionary(
        mapOf(
            "animated" to "动画",
            "anime" to "动漫",
            "3d" to "3D",
            "cyberpunk" to "赛博朋克",
            "sound" to "有声",
        ),
    ),
) {
    val watchProgressDao = FakeWatchProgressDao()
    val readingProgressDao = FakeReadingProgressDao()
    val favoriteDao = FakeFavoriteDao()
    val shelfDao = FakeShelfDao()
    val shelfItemDao = FakeShelfItemDao()
    val smartShelfDao = FakeSmartShelfDao()
    val pageBookmarkDao = FakePageBookmarkDao()
    val timeBookmarkDao = FakeTimeBookmarkDao()
    val imageFavoriteDao = FakeImageFavoriteDao()
    val tagSubscriptionDao = FakeTagSubscriptionDao()
    val savedSearchDao = FakeSavedSearchDao()
    val updateInboxDao = FakeUpdateInboxDao()
    val blockedTermDao = FakeBlockedTermDao()
    val searchHistoryDao = FakeSearchHistoryDao()
    val playbackPreferencesDao = FakePlaybackPreferencesDao()
    val readerPreferencesDao = FakeReaderPreferencesDao()
    val tagTranslationDao = FakeTagTranslationDao()
    val mediaTagCacheDao = FakeMediaTagCacheDao()

    val preferencesRepository = PreferencesRepository(
        settingsDataStore = settingsDataStore,
        playbackPreferencesDao = playbackPreferencesDao,
        readerPreferencesDao = readerPreferencesDao,
    )

    val blockedTermsRepository = BlockedTermsRepository(
        blockedTermDao = blockedTermDao,
        transactionRunner = ImmediateTransactionRunner,
    )

    val tagDictionaryRepository = TagDictionaryRepository(
        tagTranslationDao = tagTranslationDao,
        transactionRunner = ImmediateTransactionRunner,
        runtimeDictionary = runtimeDictionary,
    )

    val mediaTagCacheRepository = MediaTagCacheRepository(
        mediaTagCacheDao = mediaTagCacheDao,
        transactionRunner = ImmediateTransactionRunner,
    )

    val backupDataRepository = BackupDataRepository(
        watchProgressDao = watchProgressDao,
        readingProgressDao = readingProgressDao,
        favoriteDao = favoriteDao,
        shelfDao = shelfDao,
        shelfItemDao = shelfItemDao,
        smartShelfDao = smartShelfDao,
        pageBookmarkDao = pageBookmarkDao,
        timeBookmarkDao = timeBookmarkDao,
        imageFavoriteDao = imageFavoriteDao,
        tagSubscriptionDao = tagSubscriptionDao,
        savedSearchDao = savedSearchDao,
        updateInboxDao = updateInboxDao,
        blockedTermDao = blockedTermDao,
        searchHistoryDao = searchHistoryDao,
        playbackPreferencesDao = playbackPreferencesDao,
        readerPreferencesDao = readerPreferencesDao,
        settingsDataStore = settingsDataStore,
        transactionRunner = ImmediateTransactionRunner,
        tagTranslationDao = tagTranslationDao,
        clock = { 1_730_000_000_000L },
    )
}

internal class InMemoryBackupSafBridge : BackupSafBridge {
    val files = LinkedHashMap<String, ByteArray>()
    val releasedGrants = mutableListOf<String>()

    override suspend fun writeBytes(documentUri: String, bytes: ByteArray) {
        files[documentUri] = bytes.copyOf()
    }

    override suspend fun readBytes(documentUri: String, maxBytes: Int): ByteArray {
        val stored = files[documentUri] ?: throw IOException("Document not found: $documentUri")
        if (stored.size > maxBytes) throw IOException("Document exceeds maxBytes=$maxBytes")
        return stored.copyOf()
    }

    override fun releaseUriGrant(documentUri: String) {
        releasedGrants += documentUri
    }
}

internal class InMemoryUpdateStateStore(
    initial: PersistedUpdateState = PersistedUpdateState(),
) : UpdateStateStore {
    var state: PersistedUpdateState = initial
    override suspend fun read(): PersistedUpdateState = state
    override suspend fun write(state: PersistedUpdateState) {
        this.state = state
    }
}

/**
 * Encrypts a legacy R34V JSON string into the exact `R34VBK01` wire format specified in
 * `ARCHITECTURE.md` §7.2 (36-byte header AAD + PBKDF2WithHmacSHA1 600k + AES-256-GCM)
 * so [BackupRestoreViewModelTest] exercises the real `BackupCodec` + `LegacyR34vBackupAdapter`.
 */
internal fun encryptLegacyR34vWire(legacyJson: String, password: CharArray): ByteArray {
    val random = SecureRandom()
    val salt = ByteArray(BackupCodec.SALT_BYTES).also(random::nextBytes)
    val iv = ByteArray(BackupCodec.IV_BYTES).also(random::nextBytes)
    val header = ByteArray(BackupCodec.HEADER_BYTES)
    System.arraycopy(BackupCodec.LEGACY_R34V_MAGIC_BYTES, 0, header, 0, BackupCodec.MAGIC_BYTES)
    System.arraycopy(salt, 0, header, BackupCodec.MAGIC_BYTES, BackupCodec.SALT_BYTES)
    System.arraycopy(iv, 0, header, BackupCodec.MAGIC_BYTES + BackupCodec.SALT_BYTES, BackupCodec.IV_BYTES)

    val spec = PBEKeySpec(password, salt, BackupCodec.PBKDF2_ITERATIONS, BackupCodec.KEY_BITS)
    val key = SecretKeyFactory.getInstance(BackupCodec.KDF_ALGORITHM).generateSecret(spec).encoded
    spec.clearPassword()
    val cipher = Cipher.getInstance(BackupCodec.CIPHER_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(BackupCodec.GCM_TAG_BITS, iv))
    cipher.updateAAD(header)
    val ct = cipher.doFinal(legacyJson.toByteArray(Charsets.UTF_8))
    key.fill(0)
    return header + ct
}

internal class FakeSiteAuthProvider(
    private val siteId: SiteId,
    override val mode: AuthMode,
    private val sessionStore: InMemorySiteSessionStore,
    override val requiresAuthGate: Boolean = false,
    var acceptCredentials: (String, String) -> Boolean = { u, p -> u.isNotBlank() && p.isNotEmpty() },
    var acceptCookies: (Map<String, String>) -> Boolean = { true },
) : AuthProvider {

    override suspend fun loginWithCredentials(username: String, password: String): AuthState {
        if (!acceptCredentials(username, password)) {
            throw SiteException.AuthRequired("账号或密码错误", siteId = siteId)
        }
        when (mode) {
            AuthMode.JwtBearer -> sessionStore.saveBearerToken(siteId, "jwt-token-$username", username)
            else -> {
                sessionStore.saveCookies(siteId, mapOf("session" to "sid-$username"))
                sessionStore.saveUsername(siteId, username)
            }
        }
        return currentAuthState()
    }

    override suspend fun importCookies(cookies: Map<String, String>): AuthState {
        if (!acceptCookies(cookies)) {
            throw SiteException.AuthRequired("账号暂无 ${siteId.value} 访问权限", siteId = siteId)
        }
        sessionStore.replaceCookies(siteId, cookies)
        val uid = cookies["ipb_member_id"]?.let { "UID $it" } ?: "imported"
        sessionStore.saveUsername(siteId, uid)
        return currentAuthState()
    }

    override suspend fun logout() {
        sessionStore.clearSite(siteId)
    }

    override suspend fun currentAuthState(): AuthState {
        val hasCreds = sessionStore.getBearerToken(siteId) != null || sessionStore.getCookies(siteId).isNotEmpty()
        return AuthState(
            isAuthenticated = hasCreds,
            username = if (hasCreds) sessionStore.getUsername(siteId) else null,
            epoch = sessionStore.authEpoch(siteId),
        )
    }
}

internal fun createTestSiteRegistry(
    sessionStore: InMemorySiteSessionStore = InMemorySiteSessionStore(),
    exhentaiAcceptCookies: (Map<String, String>) -> Boolean = { true },
    r34vHost: String = "rule34video.com",
): SiteRegistry {
    val ehAllowed = setOf("forums.e-hentai.org", "e-hentai.org", "exhentai.org", "www.e-hentai.org", "www.exhentai.org")
    val plugins = listOf(
        stubPlugin(
            id = SiteId.R34V,
            displayName = "Rule34Video",
            host = r34vHost,
            kind = MediaKind.VIDEO,
            authProvider = FakeSiteAuthProvider(SiteId.R34V, AuthMode.FormCredentials, sessionStore),
        ),
        stubPlugin(
            id = SiteId.HANIME1,
            displayName = "Hanime1",
            host = "hanime1.me",
            kind = MediaKind.VIDEO,
            authProvider = FakeSiteAuthProvider(SiteId.HANIME1, AuthMode.FormCredentials, sessionStore),
        ),
        stubPlugin(
            id = SiteId.IWARA,
            displayName = "Iwara",
            host = "www.iwara.tv",
            kind = MediaKind.VIDEO,
            apiHosts = setOf("apiq.iwara.tv", "api.iwara.tv"),
            authProvider = FakeSiteAuthProvider(SiteId.IWARA, AuthMode.JwtBearer, sessionStore),
        ),
        stubPlugin(
            id = SiteId.EHENTAI,
            displayName = "E-Hentai",
            host = "e-hentai.org",
            kind = MediaKind.COMIC,
            authProvider = FakeSiteAuthProvider(
                siteId = SiteId.EHENTAI,
                mode = AuthMode.WebViewCookie(
                    loginUrl = "https://forums.e-hentai.org/index.php?act=Login",
                    targetUrl = "https://e-hentai.org/",
                    allowedHosts = ehAllowed,
                    requiredCookies = setOf("ipb_member_id", "ipb_pass_hash"),
                    optionalCookies = setOf("igneous", "sk"),
                ),
                sessionStore = sessionStore,
            ),
        ),
        stubPlugin(
            id = SiteId.EXHENTAI,
            displayName = "ExHentai",
            host = "exhentai.org",
            kind = MediaKind.COMIC,
            authProvider = FakeSiteAuthProvider(
                siteId = SiteId.EXHENTAI,
                mode = AuthMode.WebViewCookie(
                    loginUrl = "https://forums.e-hentai.org/index.php?act=Login",
                    targetUrl = "https://exhentai.org/",
                    allowedHosts = ehAllowed,
                    requiredCookies = setOf("ipb_member_id", "ipb_pass_hash"),
                    optionalCookies = setOf("igneous", "sk"),
                ),
                sessionStore = sessionStore,
                requiresAuthGate = true,
                acceptCookies = exhentaiAcceptCookies,
            ),
        ),
        stubPlugin(
            id = SiteId.NHENTAI,
            displayName = "nhentai",
            host = "nhentai.net",
            kind = MediaKind.COMIC,
            authProvider = AuthProvider.None,
        ),
    )
    return SiteRegistry(plugins)
}

internal fun stubPlugin(
    id: SiteId,
    displayName: String,
    host: String,
    kind: MediaKind = MediaKind.VIDEO,
    apiHosts: Set<String> = emptySet(),
    authProvider: AuthProvider = AuthProvider.None,
): SitePlugin = object : SitePlugin {
    override val descriptor: SiteDescriptor = SiteDescriptor(
        id = id,
        displayName = displayName,
        shortName = displayName,
        primaryKind = kind,
        defaultHost = host,
        capabilities = buildSet {
            add(SiteCapability.FEED)
            if (authProvider.mode != AuthMode.None) add(SiteCapability.AUTH)
            if (authProvider.mode is AuthMode.WebViewCookie || authProvider.mode is AuthMode.CookieImport) {
                add(SiteCapability.COOKIE_IMPORT)
            }
        },
        networkProfile = SiteNetworkProfile(
            apiHosts = apiHosts,
            requiresAuthGate = authProvider.requiresAuthGate,
        ),
    )
    override val authProvider: AuthProvider = authProvider
}
