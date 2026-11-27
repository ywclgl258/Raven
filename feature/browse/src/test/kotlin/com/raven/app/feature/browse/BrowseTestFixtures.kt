package com.raven.app.feature.browse

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.BlockedTermDao
import com.raven.app.core.database.dao.BrowsePositionDao
import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.dao.FavoriteDao
import com.raven.app.core.database.dao.MediaTagCacheDao
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
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.entity.BrowsePositionEntity
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.MediaTagCacheEntity
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
import com.raven.app.core.database.entity.UpdateInboxEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BrowsePositionRepository
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SearchHistoryRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DirectoryEntry
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.AuthState
import com.raven.app.core.sitespi.DirectorySource
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
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
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * In-memory [DataStore] of [Preferences] avoiding AndroidX DataStore's Windows `File.renameTo` quirk
 * under `unitTests.isReturnDefaultValues = true`.
 */
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

internal class BrowseTestHarness(
    dispatcher: TestDispatcher,
    var nowMs: Long = 1_700_000_000_000L,
) {
    val dispatchers = CustomAppDispatchers(dispatcher)
    val clock: () -> Long = { nowMs }

    val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <R> invoke(block: suspend () -> R): R = block()
    }

    val watchDao = InMemoryWatchProgressDao()
    val readingDao = InMemoryReadingProgressDao()
    val favoriteDao = InMemoryFavoriteDao()
    val shelfItemDao = InMemoryShelfItemDao()
    val shelfDao = InMemoryShelfDao()
    val smartShelfDao = InMemorySmartShelfDao()
    val blockedTermDao = InMemoryBlockedTermDao()
    val downloadDao = InMemoryDownloadItemDao()
    val playbackPrefsDao = InMemoryPlaybackPreferencesDao()
    val readerPrefsDao = InMemoryReaderPreferencesDao()
    val browsePositionDao = InMemoryBrowsePositionDao()
    val searchHistoryDao = InMemorySearchHistoryDao()
    val tagSubscriptionDao = InMemoryTagSubscriptionDao()
    val savedSearchDao = InMemorySavedSearchDao()
    val updateInboxDao = InMemoryUpdateInboxDao()
    val tagTranslationDao = InMemoryTagTranslationDao()
    val mediaTagCacheDao = InMemoryMediaTagCacheDao()

    val runtimeDictionary = TagDictionary(
        mapOf(
            "tentacles" to "触手",
            "blonde" to "金发",
            "stockings" to "丝袜",
            "guro" to "猎奇",
        ),
    )

    val settingsDataStore = SettingsDataStore(InMemoryPreferencesDataStore())
    val preferencesRepository = PreferencesRepository(settingsDataStore, playbackPrefsDao, readerPrefsDao)
    val blockedTermsRepository = BlockedTermsRepository(blockedTermDao, transactionRunner, clock)
    val historyRepository = HistoryRepository(watchDao, readingDao, transactionRunner)
    val favoriteRepository = FavoriteRepository(favoriteDao, transactionRunner, clock)
    val shelfRepository = ShelfRepository(
        shelfDao = shelfDao,
        shelfItemDao = shelfItemDao,
        smartShelfDao = smartShelfDao,
        watchProgressDao = watchDao,
        readingProgressDao = readingDao,
        favoriteDao = favoriteDao,
        downloadItemDao = downloadDao,
        blockedTermDao = blockedTermDao,
        transactionRunner = transactionRunner,
        clock = clock,
    )
    val browsePositionRepository = BrowsePositionRepository(browsePositionDao, transactionRunner, clock = clock)
    val searchHistoryRepository = SearchHistoryRepository(searchHistoryDao, transactionRunner, clock = clock)
    val subscriptionRepository = SubscriptionRepository(
        tagSubscriptionDao = tagSubscriptionDao,
        savedSearchDao = savedSearchDao,
        updateInboxDao = updateInboxDao,
        transactionRunner = transactionRunner,
        clock = clock,
    )
    val downloadStateRepository = DownloadStateRepository(downloadDao, clock)
    val tagDictionaryRepository = TagDictionaryRepository(
        tagTranslationDao = tagTranslationDao,
        transactionRunner = transactionRunner,
        runtimeDictionary = runtimeDictionary,
        clock = clock,
    )
    val mediaTagCacheRepository = MediaTagCacheRepository(
        mediaTagCacheDao = mediaTagCacheDao,
        transactionRunner = transactionRunner,
        clock = clock,
    )
}

internal class FakeSitePlugin(
    override val descriptor: SiteDescriptor,
    private val homeHandler: (suspend (ComicLanguage) -> HomeFeed)? = null,
    private val feedHandler: (suspend (FeedRequest) -> FeedPage)? = null,
    private val searchHandler: (suspend (FeedRequest) -> FeedPage)? = null,
    private val directoryHandler: (suspend (section: String, page: Int, query: String?) -> List<DirectoryEntry>)? = null,
    private val directorySectionsList: List<SiteSectionSpec> = emptyList(),
    private val supportedScopesSet: Set<SearchScope> = descriptor.searchScopes.toSet(),
    private val supportedFiltersSet: Set<AdvancedFilterField> = descriptor.supportedFilters,
    private val supportedSearchSortsProvider: ((SearchScope) -> List<FeedSort>)? = null,
    private val supportsExcludeTagsFlag: Boolean = false,
    private val isDirectorySearchFlag: Boolean = false,
    private val requiresAuthGateFlag: Boolean = descriptor.networkProfile.requiresAuthGate,
    var isAuthenticated: Boolean = !requiresAuthGateFlag,
    var authUsername: String? = null,
) : SitePlugin {

    val homeRequests = mutableListOf<ComicLanguage>()
    val feedRequests = mutableListOf<FeedRequest>()
    val searchRequests = mutableListOf<FeedRequest>()

    override val feedSource: FeedSource? = if (homeHandler != null || feedHandler != null) {
        object : FeedSource {
            override suspend fun loadHome(language: ComicLanguage): HomeFeed {
                homeRequests.add(language)
                return homeHandler?.invoke(language) ?: HomeFeed(siteId = descriptor.id)
            }

            override suspend fun loadFeed(request: FeedRequest): FeedPage {
                feedRequests.add(request)
                return feedHandler?.invoke(request) ?: FeedPage(items = emptyList())
            }

            override fun supportedSorts(request: FeedRequest): List<FeedSort> = descriptor.feedSorts
        }
    } else {
        null
    }

    override val searchSource: SearchSource? = if (searchHandler != null) {
        object : SearchSource {
            override suspend fun search(request: FeedRequest): FeedPage {
                searchRequests.add(request)
                return searchHandler.invoke(request)
            }

            override val supportedScopes: Set<SearchScope> = supportedScopesSet
            override val supportedFilters: Set<AdvancedFilterField> = supportedFiltersSet
            override val supportsExcludeTags: Boolean = supportsExcludeTagsFlag
            override val isDirectorySearch: Boolean = isDirectorySearchFlag
            override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> =
                supportedSearchSortsProvider?.invoke(scope) ?: emptyList()
        }
    } else {
        null
    }

    override val directorySource: DirectorySource? = if (directoryHandler != null) {
        object : DirectorySource {
            override suspend fun loadDirectory(
                section: String,
                page: Int,
                query: String?,
            ): List<DirectoryEntry> = directoryHandler.invoke(section, page, query)

            override fun directorySections(): List<SiteSectionSpec> = directorySectionsList
        }
    } else {
        null
    }

    override val authProvider: AuthProvider = object : AuthProvider {
        override val mode: AuthMode = if (requiresAuthGateFlag) AuthMode.FormCredentials else AuthMode.None
        override val requiresAuthGate: Boolean = requiresAuthGateFlag
        override suspend fun currentAuthState(): AuthState = AuthState(
            isAuthenticated = isAuthenticated,
            username = if (isAuthenticated) authUsername else null,
        )
    }
}

internal fun sampleMediaItem(
    siteId: SiteId,
    id: String,
    kind: MediaKind = MediaKind.VIDEO,
    title: String = "Title $id",
    tags: List<com.raven.app.core.model.TagRef> = emptyList(),
    extra: Map<String, String> = emptyMap(),
    durationSeconds: Int? = if (kind == MediaKind.VIDEO) 620 else null,
    pageCount: Int? = if (kind != MediaKind.VIDEO) 32 else null,
    views: Long? = null,
    likes: Long? = null,
    rating: Float? = null,
    dateLabel: String? = null,
): MediaItem = MediaItem(
    siteId = siteId,
    id = id,
    url = "https://${siteId.value}.example/item/$id",
    kind = kind,
    title = title,
    coverUrl = "https://${siteId.value}.example/thumb/$id.jpg",
    durationSeconds = durationSeconds,
    pageCount = pageCount,
    views = views,
    likes = likes,
    rating = rating,
    dateLabel = dateLabel,
    tags = tags,
    extra = extra,
)

// ---- Lightweight Reactive In-Memory DAOs ----

internal class InMemoryWatchProgressDao : WatchProgressDao {
    private val rows = MutableStateFlow<List<WatchProgressEntity>>(emptyList())
    override fun observeRecent(limit: Int): Flow<List<WatchProgressEntity>> =
        rows.map { it.sortedByDescending { e -> e.updatedAt }.take(limit) }
    override fun observeAll(): Flow<List<WatchProgressEntity>> =
        rows.map { it.sortedByDescending { e -> e.updatedAt } }
    override fun observeBySite(siteId: String): Flow<List<WatchProgressEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId }.sortedByDescending { e -> e.updatedAt } }
    override fun observeOne(siteId: String, mediaId: String): Flow<WatchProgressEntity?> =
        rows.map { it.firstOrNull { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override suspend fun getOne(siteId: String, mediaId: String): WatchProgressEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll(): List<WatchProgressEntity> = rows.value
    override suspend fun upsert(entity: WatchProgressEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.mediaId == entity.mediaId } + entity }
    }
    override suspend fun upsertAll(entities: List<WatchProgressEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) {
        rows.update { list ->
            val kept = list.filter { it.siteId == siteId }.sortedByDescending { it.updatedAt }.take(limit)
            list.filterNot { it.siteId == siteId } + kept
        }
    }
}

internal class InMemoryReadingProgressDao : ReadingProgressDao {
    private val rows = MutableStateFlow<List<ReadingProgressEntity>>(emptyList())
    override fun observeRecent(limit: Int): Flow<List<ReadingProgressEntity>> =
        rows.map { it.sortedByDescending { e -> e.updatedAt }.take(limit) }
    override fun observeAll(): Flow<List<ReadingProgressEntity>> =
        rows.map { it.sortedByDescending { e -> e.updatedAt } }
    override fun observeBySite(siteId: String): Flow<List<ReadingProgressEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId }.sortedByDescending { e -> e.updatedAt } }
    override fun observeOne(siteId: String, mediaId: String): Flow<ReadingProgressEntity?> =
        rows.map { it.firstOrNull { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override suspend fun getOne(siteId: String, mediaId: String): ReadingProgressEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll(): List<ReadingProgressEntity> = rows.value
    override suspend fun upsert(entity: ReadingProgressEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.mediaId == entity.mediaId } + entity }
    }
    override suspend fun upsertAll(entities: List<ReadingProgressEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) {
        rows.update { list ->
            val kept = list.filter { it.siteId == siteId }.sortedByDescending { it.updatedAt }.take(limit)
            list.filterNot { it.siteId == siteId } + kept
        }
    }
}

internal class InMemoryFavoriteDao : FavoriteDao {
    private val rows = MutableStateFlow<List<FavoriteEntity>>(emptyList())
    override fun observeAll(): Flow<List<FavoriteEntity>> = rows.map { it.sortedByDescending { e -> e.addedAt } }
    override fun observeBySite(siteId: String): Flow<List<FavoriteEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId }.sortedByDescending { e -> e.addedAt } }
    override fun observeByKind(kind: FavoriteKind): Flow<List<FavoriteEntity>> =
        rows.map { it.filter { e -> e.kind == kind }.sortedByDescending { e -> e.addedAt } }
    override fun observeBySiteAndKind(siteId: String, kind: FavoriteKind): Flow<List<FavoriteEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId && e.kind == kind } }
    override fun observeMediaFavorites(): Flow<List<FavoriteEntity>> =
        rows.map { it.filter { e -> e.kind in setOf(FavoriteKind.VIDEO, FavoriteKind.COMIC, FavoriteKind.PHOTO) } }
    override fun observeExists(siteId: String, kind: FavoriteKind, targetId: String): Flow<Boolean> =
        rows.map { it.any { e -> e.siteId == siteId && e.kind == kind && e.targetId == targetId } }
    override suspend fun exists(siteId: String, kind: FavoriteKind, targetId: String): Boolean =
        rows.value.any { it.siteId == siteId && it.kind == kind && it.targetId == targetId }
    override suspend fun getAll(): List<FavoriteEntity> = rows.value
    override suspend fun upsert(entity: FavoriteEntity) {
        rows.update { list ->
            list.filterNot { it.siteId == entity.siteId && it.kind == entity.kind && it.targetId == entity.targetId } + entity
        }
    }
    override suspend fun upsertAll(entities: List<FavoriteEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, kind: FavoriteKind, targetId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.kind == kind && it.targetId == targetId } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryShelfDao : ShelfDao {
    private val rows = MutableStateFlow<List<ShelfEntity>>(emptyList())
    override fun observeAll(): Flow<List<ShelfEntity>> = rows
    override fun observeById(id: String): Flow<ShelfEntity?> = rows.map { it.firstOrNull { e -> e.id == id } }
    override suspend fun getById(id: String): ShelfEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll(): List<ShelfEntity> = rows.value
    override suspend fun insertIgnore(entity: ShelfEntity): Long {
        if (rows.value.any { it.id == entity.id }) return -1L
        rows.update { it + entity }
        return 1L
    }
    override suspend fun upsert(entity: ShelfEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<ShelfEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateModifiedAt(id: String, modifiedAt: Long) {
        rows.update { list -> list.map { if (it.id == id) it.copy(modifiedAt = modifiedAt) else it } }
    }
    override suspend fun clearCoverIfMatching(id: String, compositeKey: String, modifiedAt: Long) {
        rows.update { list ->
            list.map { if (it.id == id && it.coverCompositeKey == compositeKey) it.copy(coverCompositeKey = null, modifiedAt = modifiedAt) else it }
        }
    }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryShelfItemDao : ShelfItemDao {
    private val rows = MutableStateFlow<List<ShelfItemEntity>>(emptyList())
    override fun observeAll(): Flow<List<ShelfItemEntity>> = rows
    override fun observeByShelf(shelfId: String): Flow<List<ShelfItemEntity>> =
        rows.map { it.filter { e -> e.shelfId == shelfId }.sortedBy { e -> e.position } }
    override fun observeContainingShelfIds(siteId: String, mediaId: String): Flow<List<String>> =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.map { e -> e.shelfId } }
    override suspend fun getByShelf(shelfId: String): List<ShelfItemEntity> =
        rows.value.filter { it.shelfId == shelfId }.sortedBy { it.position }
    override suspend fun getAll(): List<ShelfItemEntity> = rows.value
    override suspend fun getOne(shelfId: String, siteId: String, mediaId: String): ShelfItemEntity? =
        rows.value.firstOrNull { it.shelfId == shelfId && it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getMaxPosition(shelfId: String): Int? =
        rows.value.filter { it.shelfId == shelfId }.maxOfOrNull { it.position }
    override suspend fun upsert(entity: ShelfItemEntity) {
        rows.update { list ->
            list.filterNot { it.shelfId == entity.shelfId && it.siteId == entity.siteId && it.mediaId == entity.mediaId } + entity
        }
    }
    override suspend fun upsertAll(entities: List<ShelfItemEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateCompleted(shelfId: String, siteId: String, mediaId: String, completed: Boolean, changedAt: Long) {
        rows.update { list ->
            list.map { if (it.shelfId == shelfId && it.siteId == siteId && it.mediaId == mediaId) it.copy(completed = completed, completionChangedAt = changedAt) else it }
        }
    }
    override suspend fun updatePosition(shelfId: String, siteId: String, mediaId: String, position: Int) {
        rows.update { list ->
            list.map { if (it.shelfId == shelfId && it.siteId == siteId && it.mediaId == mediaId) it.copy(position = position) else it }
        }
    }
    override suspend fun delete(shelfId: String, siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.shelfId == shelfId && it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteByShelf(shelfId: String) {
        rows.update { list -> list.filterNot { it.shelfId == shelfId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemorySmartShelfDao : SmartShelfDao {
    private val rows = MutableStateFlow<List<SmartShelfEntity>>(emptyList())
    override fun observeAll(): Flow<List<SmartShelfEntity>> = rows
    override fun observeById(id: String): Flow<SmartShelfEntity?> = rows.map { it.firstOrNull { e -> e.id == id } }
    override suspend fun getById(id: String): SmartShelfEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll(): List<SmartShelfEntity> = rows.value
    override suspend fun upsert(entity: SmartShelfEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<SmartShelfEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryBlockedTermDao : BlockedTermDao {
    private val rows = MutableStateFlow<List<BlockedTermEntity>>(emptyList())
    override fun observeAll(): Flow<List<BlockedTermEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<BlockedTermEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId } }
    override suspend fun exists(siteId: String, term: String): Boolean =
        rows.value.any { it.siteId == siteId && it.term == term }
    override suspend fun getAll(): List<BlockedTermEntity> = rows.value
    override suspend fun upsert(entity: BlockedTermEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.term == entity.term } + entity }
    }
    override suspend fun upsertAll(entities: List<BlockedTermEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, term: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.term == term } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryDownloadItemDao : DownloadItemDao {
    private val rows = MutableStateFlow<List<DownloadItemEntity>>(emptyList())
    override fun observeAll(): Flow<List<DownloadItemEntity>> = rows
    override fun observeByStatus(status: DownloadStatus): Flow<List<DownloadItemEntity>> =
        rows.map { it.filter { e -> e.status == status } }
    override fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadItemEntity>> =
        rows.map { it.filter { e -> e.status in statuses } }
    override fun observeBySite(siteId: String): Flow<List<DownloadItemEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeById(id: String): Flow<DownloadItemEntity?> = rows.map { it.firstOrNull { e -> e.id == id } }
    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<DownloadItemEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override fun observeCompletedCount(): Flow<Int> = rows.map { it.count { e -> e.status == DownloadStatus.COMPLETED } }
    override suspend fun getById(id: String): DownloadItemEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getByMedia(siteId: String, mediaId: String): List<DownloadItemEntity> =
        rows.value.filter { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll(): List<DownloadItemEntity> = rows.value
    override suspend fun upsert(entity: DownloadItemEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<DownloadItemEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateStatus(id: String, status: DownloadStatus, errorCategory: DownloadErrorCategory?, errorMessage: String?, retryAtEpochMs: Long?, updatedAt: Long) {
        rows.update { list -> list.map { if (it.id == id) it.copy(status = status, errorCategory = errorCategory, errorMessage = errorMessage, retryAtEpochMs = retryAtEpochMs, updatedAt = updatedAt) else it } }
    }
    override suspend fun updateProgress(id: String, progressFraction: Float, completedUnits: Int, totalUnits: Int, downloadedBytes: Long, totalBytes: Long?, updatedAt: Long) {
        rows.update { list -> list.map { if (it.id == id) it.copy(progressFraction = progressFraction, completedUnits = completedUnits, totalUnits = totalUnits, downloadedBytes = downloadedBytes, totalBytes = totalBytes, updatedAt = updatedAt) else it } }
    }
    override suspend fun updatePriority(id: String, priority: Int, updatedAt: Long) {
        rows.update { list -> list.map { if (it.id == id) it.copy(priority = priority, updatedAt = updatedAt) else it } }
    }
    override suspend fun updateGroupName(id: String, groupName: String, updatedAt: Long) {
        rows.update { list -> list.map { if (it.id == id) it.copy(groupName = groupName, updatedAt = updatedAt) else it } }
    }
    override suspend fun updateLocalPath(id: String, storageLocation: StorageLocation, localPath: String?, updatedAt: Long) {
        rows.update { list -> list.map { if (it.id == id) it.copy(storageLocation = storageLocation, localPath = localPath, updatedAt = updatedAt) else it } }
    }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteByStatus(status: DownloadStatus) {
        rows.update { list -> list.filterNot { it.status == status } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryPlaybackPreferencesDao : PlaybackPreferencesDao {
    private val rows = MutableStateFlow<List<PlaybackPreferencesEntity>>(emptyList())
    override fun observeBySite(siteId: String): Flow<PlaybackPreferencesEntity?> =
        rows.map { it.firstOrNull { e -> e.siteId == siteId } }
    override fun observeAll(): Flow<List<PlaybackPreferencesEntity>> = rows
    override suspend fun getBySite(siteId: String): PlaybackPreferencesEntity? = rows.value.firstOrNull { it.siteId == siteId }
    override suspend fun getAll(): List<PlaybackPreferencesEntity> = rows.value
    override suspend fun upsert(entity: PlaybackPreferencesEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId } + entity }
    }
    override suspend fun upsertAll(entities: List<PlaybackPreferencesEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryReaderPreferencesDao : ReaderPreferencesDao {
    private val rows = MutableStateFlow<List<ReaderPreferencesEntity>>(emptyList())
    override fun observe(siteId: String, mediaId: String): Flow<ReaderPreferencesEntity?> =
        rows.map { it.firstOrNull { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override fun observeBySite(siteId: String): Flow<List<ReaderPreferencesEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeAll(): Flow<List<ReaderPreferencesEntity>> = rows
    override suspend fun get(siteId: String, mediaId: String): ReaderPreferencesEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll(): List<ReaderPreferencesEntity> = rows.value
    override suspend fun upsert(entity: ReaderPreferencesEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.mediaId == entity.mediaId } + entity }
    }
    override suspend fun upsertAll(entities: List<ReaderPreferencesEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryBrowsePositionDao : BrowsePositionDao {
    private val rows = MutableStateFlow<List<BrowsePositionEntity>>(emptyList())
    override fun observeByKey(positionKey: String): Flow<BrowsePositionEntity?> =
        rows.map { it.firstOrNull { e -> e.positionKey == positionKey } }
    override fun observeAll(): Flow<List<BrowsePositionEntity>> =
        rows.map { it.sortedByDescending { e -> e.updatedAt } }
    override suspend fun getByKey(positionKey: String): BrowsePositionEntity? =
        rows.value.firstOrNull { it.positionKey == positionKey }
    override suspend fun getAll(): List<BrowsePositionEntity> = rows.value
    override suspend fun upsert(entity: BrowsePositionEntity) {
        rows.update { list -> list.filterNot { it.positionKey == entity.positionKey } + entity }
    }
    override suspend fun deleteByKey(positionKey: String) {
        rows.update { list -> list.filterNot { it.positionKey == positionKey } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun prune(limit: Int) {
        rows.update { list -> list.sortedByDescending { it.updatedAt }.take(limit) }
    }
}

internal class InMemorySearchHistoryDao : SearchHistoryDao {
    private val rows = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    override fun observeBySite(siteId: String, limit: Int): Flow<List<SearchHistoryEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId }.sortedByDescending { e -> e.searchedAt }.take(limit) }
    override fun observeAll(limit: Int): Flow<List<SearchHistoryEntity>> =
        rows.map { it.sortedByDescending { e -> e.searchedAt }.take(limit) }
    override suspend fun getAll(): List<SearchHistoryEntity> = rows.value
    override suspend fun upsert(entity: SearchHistoryEntity) {
        rows.update { list ->
            list.filterNot { it.siteId == entity.siteId && it.query == entity.query && it.scope == entity.scope } + entity
        }
    }
    override suspend fun upsertAll(entities: List<SearchHistoryEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, query: String, scope: SearchScope) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.query == query && it.scope == scope } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) {
        rows.update { list ->
            val kept = list.filter { it.siteId == siteId }.sortedByDescending { it.searchedAt }.take(limit)
            list.filterNot { it.siteId == siteId } + kept
        }
    }
}

internal class InMemoryTagSubscriptionDao : TagSubscriptionDao {
    private val rows = MutableStateFlow<List<TagSubscriptionEntity>>(emptyList())
    override fun observeAll(): Flow<List<TagSubscriptionEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<TagSubscriptionEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId }.sortedBy { e -> e.sortOrder } }
    override fun observeExists(siteId: String, slug: String, scope: SearchScope): Flow<Boolean> =
        rows.map { it.any { e -> e.siteId == siteId && e.slug == slug && e.scope == scope } }
    override suspend fun getBySite(siteId: String): List<TagSubscriptionEntity> =
        rows.value.filter { it.siteId == siteId }.sortedBy { it.sortOrder }
    override suspend fun getOne(siteId: String, slug: String, scope: SearchScope): TagSubscriptionEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.slug == slug && it.scope == scope }
    override suspend fun getMaxSortOrder(siteId: String): Int? =
        rows.value.filter { it.siteId == siteId }.maxOfOrNull { it.sortOrder }
    override suspend fun getAll(): List<TagSubscriptionEntity> = rows.value
    override suspend fun upsert(entity: TagSubscriptionEntity) {
        rows.update { list ->
            list.filterNot { it.siteId == entity.siteId && it.slug == entity.slug && it.scope == entity.scope } + entity
        }
    }
    override suspend fun upsertAll(entities: List<TagSubscriptionEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateLabel(siteId: String, slug: String, scope: SearchScope, label: String) {
        rows.update { list ->
            list.map { if (it.siteId == siteId && it.slug == slug && it.scope == scope) it.copy(label = label) else it }
        }
    }
    override suspend fun updateSortOrder(siteId: String, slug: String, scope: SearchScope, sortOrder: Int) {
        rows.update { list ->
            list.map { if (it.siteId == siteId && it.slug == slug && it.scope == scope) it.copy(sortOrder = sortOrder) else it }
        }
    }
    override suspend fun delete(siteId: String, slug: String, scope: SearchScope) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.slug == slug && it.scope == scope } }
    }
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemorySavedSearchDao : SavedSearchDao {
    private val rows = MutableStateFlow<List<SavedSearchEntity>>(emptyList())
    override fun observeAll(): Flow<List<SavedSearchEntity>> =
        rows.map { it.sortedByDescending { e -> e.createdAt } }
    override fun observeById(id: String): Flow<SavedSearchEntity?> = rows.map { it.firstOrNull { e -> e.id == id } }
    override suspend fun getById(id: String): SavedSearchEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll(): List<SavedSearchEntity> = rows.value
    override suspend fun upsert(entity: SavedSearchEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<SavedSearchEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateName(id: String, name: String) {
        rows.update { list -> list.map { if (it.id == id) it.copy(name = name) else it } }
    }
    override suspend fun updateLastCheckedAt(id: String, checkedAt: Long) {
        rows.update { list -> list.map { if (it.id == id) it.copy(lastCheckedAt = checkedAt) else it } }
    }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryUpdateInboxDao : UpdateInboxDao {
    private val rows = MutableStateFlow<List<UpdateInboxEntity>>(emptyList())
    override fun observeAll(): Flow<List<UpdateInboxEntity>> = rows
    override fun observeUnread(): Flow<List<UpdateInboxEntity>> = rows.map { it.filterNot { e -> e.isRead } }
    override fun observeUnreadCount(): Flow<Int> = rows.map { it.count { e -> !e.isRead } }
    override fun observeUnreadCountBySourceKind(sourceKind: InboxSourceKind): Flow<Int> =
        rows.map { it.count { e -> !e.isRead && e.sourceKind == sourceKind } }
    override fun observeBySource(sourceKind: InboxSourceKind, sourceId: String): Flow<List<UpdateInboxEntity>> =
        rows.map { it.filter { e -> e.sourceKind == sourceKind && e.sourceId == sourceId } }
    override fun observeBySite(siteId: String): Flow<List<UpdateInboxEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId } }
    override suspend fun getByIds(ids: List<String>): List<UpdateInboxEntity> = rows.value.filter { it.id in ids }
    override suspend fun getAll(): List<UpdateInboxEntity> = rows.value
    override suspend fun upsert(entity: UpdateInboxEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<UpdateInboxEntity>) = entities.forEach { upsert(it) }
    override suspend fun setRead(id: String, isRead: Boolean) {
        rows.update { list -> list.map { if (it.id == id) it.copy(isRead = isRead) else it } }
    }
    override suspend fun markReadByIds(ids: List<String>) {
        rows.update { list -> list.map { if (it.id in ids) it.copy(isRead = true) else it } }
    }
    override suspend fun markAllRead() { rows.update { list -> list.map { it.copy(isRead = true) } } }
    override suspend fun markAllReadBySourceKind(sourceKind: InboxSourceKind) {
        rows.update { list -> list.map { if (it.sourceKind == sourceKind) it.copy(isRead = true) else it } }
    }
    override suspend fun markAllReadBySource(sourceKind: InboxSourceKind, sourceId: String) {
        rows.update { list ->
            list.map { if (it.sourceKind == sourceKind && it.sourceId == sourceId) it.copy(isRead = true) else it }
        }
    }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteBySource(sourceKind: InboxSourceKind, sourceId: String) {
        rows.update { list -> list.filterNot { it.sourceKind == sourceKind && it.sourceId == sourceId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun prune(limit: Int) {
        rows.update { list -> list.sortedByDescending { it.discoveredAt }.take(limit) }
    }
}

internal class InMemoryTagTranslationDao : TagTranslationDao {
    private val rows = MutableStateFlow<List<TagTranslationEntity>>(emptyList())
    override fun observeBySource(source: TagTranslationSource): Flow<List<TagTranslationEntity>> =
        rows.map { list -> list.filter { it.source == source } }
    override fun observeCountBySource(source: TagTranslationSource): Flow<Int> =
        rows.map { list -> list.count { it.source == source } }
    override fun observeAll(): Flow<List<TagTranslationEntity>> = rows
    override suspend fun getBySource(source: TagTranslationSource): List<TagTranslationEntity> =
        rows.value.filter { it.source == source }
    override suspend fun getAll(): List<TagTranslationEntity> = rows.value
    override suspend fun search(query: String, limit: Int): List<TagTranslationEntity> =
        rows.value.filter {
            it.rawSlug.contains(query, ignoreCase = true) || it.chineseName.contains(query, ignoreCase = true)
        }.take(limit)
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

internal class InMemoryMediaTagCacheDao : MediaTagCacheDao {
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
