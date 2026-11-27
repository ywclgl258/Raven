package com.raven.app.feature.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.BlockedTermDao
import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.dao.FavoriteDao
import com.raven.app.core.database.dao.ImageFavoriteDao
import com.raven.app.core.database.dao.MediaTagCacheDao
import com.raven.app.core.database.dao.PageBookmarkDao
import com.raven.app.core.database.dao.PlaybackPreferencesDao
import com.raven.app.core.database.dao.ReaderPreferencesDao
import com.raven.app.core.database.dao.ReadingProgressDao
import com.raven.app.core.database.dao.SavedSearchDao
import com.raven.app.core.database.dao.ShelfDao
import com.raven.app.core.database.dao.ShelfItemDao
import com.raven.app.core.database.dao.SmartShelfDao
import com.raven.app.core.database.dao.TagSubscriptionDao
import com.raven.app.core.database.dao.TimeBookmarkDao
import com.raven.app.core.database.dao.UpdateInboxDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.ImageFavoriteEntity
import com.raven.app.core.database.entity.MediaTagCacheEntity
import com.raven.app.core.database.entity.PageBookmarkEntity
import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.database.entity.ReadingProgressEntity
import com.raven.app.core.database.entity.SavedSearchEntity
import com.raven.app.core.database.entity.ShelfEntity
import com.raven.app.core.database.entity.ShelfItemEntity
import com.raven.app.core.database.entity.SmartShelfEntity
import com.raven.app.core.database.entity.TagSubscriptionEntity
import com.raven.app.core.database.entity.TimeBookmarkEntity
import com.raven.app.core.database.entity.UpdateInboxEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.media.download.BatchEnqueueProgress
import com.raven.app.core.media.download.DownloadNetworkPolicy
import com.raven.app.core.media.download.InMemoryPlaybackQueuePersistence
import com.raven.app.core.media.download.OfflineMediaHandle
import com.raven.app.core.media.download.PlaybackQueueStore
import com.raven.app.core.media.reader.GalleryAudit
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
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
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

internal class FakeLibraryDownloadGateway(
    private val downloadStateRepository: DownloadStateRepository,
) : LibraryDownloadGateway {
    override val downloads: Flow<List<DownloadItem>> = downloadStateRepository.observeAll()
    override val completedCount: Flow<Int> = downloadStateRepository.observeCompletedCount()

    val mutableNetworkPolicy = MutableStateFlow(DownloadNetworkPolicy())
    override val networkPolicy: StateFlow<DownloadNetworkPolicy> = mutableNetworkPolicy.asStateFlow()

    val mutableBatchProgress = MutableStateFlow(BatchEnqueueProgress())
    override val batchProgress: StateFlow<BatchEnqueueProgress> = mutableBatchProgress.asStateFlow()

    val enqueuedBatches = mutableListOf<Pair<List<MediaItem>, String>>()
    val pausedIds = mutableListOf<String>()
    val resumedIds = mutableListOf<String>()
    val retriedIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()
    val prioritizedIds = mutableListOf<String>()
    val priorityMoves = mutableListOf<Pair<String, Int>>()
    val inspectedIds = mutableListOf<String>()
    val repairedIds = mutableListOf<String>()
    var pauseAllCount: Int = 0
    var resumeAllCount: Int = 0
    var syncFromDiskCount: Int = 0
    var cancelBatchCount: Int = 0

    val auditResults = mutableMapOf<String, GalleryAudit>()
    val offlineReadyKeys = mutableSetOf<String>()

    override fun enqueueBatch(items: List<MediaItem>, groupName: String) {
        enqueuedBatches += items to groupName
        mutableBatchProgress.value = BatchEnqueueProgress(
            total = items.size,
            processed = items.size,
            added = items.size,
            isRunning = false,
        )
    }

    override fun cancelBatch() {
        cancelBatchCount += 1
        mutableBatchProgress.update { it.copy(isRunning = false) }
    }

    override suspend fun pause(id: String) {
        pausedIds += id
        downloadStateRepository.updateStatus(id, DownloadStatus.PAUSED)
    }

    override suspend fun resume(id: String) {
        resumedIds += id
        downloadStateRepository.updateStatus(id, DownloadStatus.QUEUED)
    }

    override suspend fun pauseAll() {
        pauseAllCount += 1
        val current = downloadStateRepository.observeAll().first()
        current.filter { it.status.isActiveTransfer() }.forEach {
            downloadStateRepository.updateStatus(it.id, DownloadStatus.PAUSED)
        }
    }

    override suspend fun resumeAll() {
        resumeAllCount += 1
        val current = downloadStateRepository.observeAll().first()
        current.filter { it.status == DownloadStatus.PAUSED }.forEach {
            downloadStateRepository.updateStatus(it.id, DownloadStatus.QUEUED)
        }
    }

    override suspend fun retryFailed(id: String): Boolean {
        retriedIds += id
        val item = downloadStateRepository.getById(id) ?: return false
        if (item.status != DownloadStatus.FAILED && item.status != DownloadStatus.CANCELLED) return false
        downloadStateRepository.updateStatus(id, DownloadStatus.QUEUED)
        return true
    }

    override suspend fun retryAllFailed(ids: Collection<String>?): Int {
        val targetSet = ids?.toSet()
        val failed = downloadStateRepository.observeByStatus(DownloadStatus.FAILED).first()
            .filter { targetSet == null || it.id in targetSet }
        failed.forEach {
            retriedIds += it.id
            downloadStateRepository.updateStatus(it.id, DownloadStatus.QUEUED)
        }
        return failed.size
    }

    override suspend fun delete(id: String, sourceRoot: String) {
        deletedIds += id
        downloadStateRepository.delete(id)
    }

    override suspend fun movePriority(id: String, delta: Int) {
        priorityMoves += id to delta
        val current = downloadStateRepository.getById(id) ?: return
        downloadStateRepository.updatePriority(id, current.priority + delta)
    }

    override suspend fun prioritize(id: String) {
        prioritizedIds += id
        val max = downloadStateRepository.observeAll().first().maxOfOrNull { it.priority } ?: 0
        downloadStateRepository.updatePriority(id, max + 1)
    }

    override suspend fun setGroup(id: String, groupName: String) {
        downloadStateRepository.updateGroupName(id, groupName)
    }

    override suspend fun setGroupForItems(ids: Collection<String>, groupName: String) {
        downloadStateRepository.updateGroupNames(ids, groupName)
    }

    override suspend fun inspectIntegrity(id: String): GalleryAudit? {
        inspectedIds += id
        return auditResults[id]
    }

    override suspend fun repair(id: String): DownloadItem? {
        repairedIds += id
        downloadStateRepository.updateStatus(id, DownloadStatus.QUEUED)
        return downloadStateRepository.getById(id)
    }

    override suspend fun syncFromDisk(): List<DownloadItem> {
        syncFromDiskCount += 1
        return downloadStateRepository.observeAll().first()
    }

    override suspend fun isOfflineReady(siteId: SiteId, mediaId: String, kind: MediaKind): Boolean {
        val key = "${siteId.value}:$mediaId"
        if (siteId.isLocal || key in offlineReadyKeys) return true
        return downloadStateRepository.getByMedia(siteId, mediaId)
            .any { it.status == DownloadStatus.COMPLETED }
    }

    override suspend fun findOfflineHandle(
        siteId: SiteId,
        mediaId: String,
        kind: MediaKind,
    ): OfflineMediaHandle? {
        if (!isOfflineReady(siteId, mediaId, kind)) return null
        return if (kind.isVideo) {
            OfflineMediaHandle.OfflineVideo(cacheKey = "${siteId.value}:$mediaId", sourceRoot = "", quality = "720p")
        } else {
            OfflineMediaHandle.OfflineGallery(sourceRoot = "", isComplete = true)
        }
    }

    override suspend fun findOfflineHandles(items: List<MediaItem>): Map<String, OfflineMediaHandle> {
        val map = linkedMapOf<String, OfflineMediaHandle>()
        for (item in items) {
            findOfflineHandle(item.siteId, item.id, item.kind)?.let { map[item.compositeKey] = it }
        }
        return map
    }
}

internal class FakeLibrarySitePlugin(
    override val descriptor: SiteDescriptor,
    private val feedHandler: (suspend (FeedRequest) -> FeedPage)? = null,
    private val searchHandler: (suspend (FeedRequest) -> FeedPage)? = null,
    private val linkHandler: ((String) -> ResolvedLink?)? = null,
    authMode: AuthMode = AuthMode.None,
) : SitePlugin {
    override val authProvider: AuthProvider = object : AuthProvider {
        override val mode: AuthMode = authMode
    }
    override val feedSource: FeedSource? = if (feedHandler != null) {
        object : FeedSource {
            override suspend fun loadHome(language: ComicLanguage): HomeFeed = HomeFeed(siteId = descriptor.id)
            override suspend fun loadFeed(request: FeedRequest): FeedPage = feedHandler.invoke(request)
            override fun supportedSorts(request: FeedRequest): List<FeedSort> = descriptor.feedSorts
        }
    } else {
        null
    }

    override val searchSource: SearchSource? = if (searchHandler != null) {
        object : SearchSource {
            override suspend fun search(request: FeedRequest): FeedPage = searchHandler.invoke(request)
        }
    } else {
        null
    }

    override val linkResolver: LinkResolver? = if (linkHandler != null) {
        object : LinkResolver {
            override fun canHandle(url: String): Boolean = url.contains(descriptor.defaultHost, ignoreCase = true)
            override fun canonicalize(url: String): String = url.trim()
            override fun resolve(url: String): ResolvedLink? = linkHandler.invoke(url)
        }
    } else {
        null
    }
}

internal class LibraryTestHarness(
    dispatcher: TestDispatcher,
    localRootDir: File,
    var nowMs: Long = 1_700_000_000_000L,
    plugins: List<SitePlugin> = defaultTestPlugins(),
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
    val tagSubscriptionDao = InMemoryTagSubscriptionDao()
    val savedSearchDao = InMemorySavedSearchDao()
    val updateInboxDao = InMemoryUpdateInboxDao()
    val pageBookmarkDao = InMemoryPageBookmarkDao()
    val timeBookmarkDao = InMemoryTimeBookmarkDao()
    val imageFavoriteDao = InMemoryImageFavoriteDao()
    val mediaTagCacheDao = InMemoryMediaTagCacheDao()

    val runtimeDictionary = TagDictionary(
        mapOf(
            "tentacles" to "触手",
            "guro" to "猎奇",
        ),
    )

    val settingsDataStore = SettingsDataStore(InMemoryPreferencesDataStore())
    val preferencesRepository = PreferencesRepository(settingsDataStore, playbackPrefsDao, readerPrefsDao)
    val blockedTermsRepository = BlockedTermsRepository(blockedTermDao, transactionRunner, clock)
    val mediaTagCacheRepository = MediaTagCacheRepository(mediaTagCacheDao, transactionRunner, clock = clock)
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
    val bookmarkRepository = BookmarkRepository(
        pageBookmarkDao = pageBookmarkDao,
        timeBookmarkDao = timeBookmarkDao,
        imageFavoriteDao = imageFavoriteDao,
        transactionRunner = transactionRunner,
        clock = clock,
    )
    val subscriptionRepository = SubscriptionRepository(
        tagSubscriptionDao = tagSubscriptionDao,
        savedSearchDao = savedSearchDao,
        updateInboxDao = updateInboxDao,
        transactionRunner = transactionRunner,
        clock = clock,
    )
    val downloadStateRepository = DownloadStateRepository(downloadDao, clock)
    val downloadGateway = FakeLibraryDownloadGateway(downloadStateRepository)
    val playbackQueueStore = PlaybackQueueStore(
        persistence = InMemoryPlaybackQueuePersistence(),
        dispatchers = dispatchers,
    )
    val localLibraryImporter = LocalLibraryImporter(
        context = null,
        rootDir = localRootDir,
        dispatchers = dispatchers,
        requireAndroidImageDecoder = false,
        clockMs = clock,
    )
    val siteRegistry = SiteRegistry(plugins)
}

internal fun defaultTestPlugins(): List<SitePlugin> = listOf(
    FakeLibrarySitePlugin(
        descriptor = SiteDescriptor(
            id = SiteId.R34V,
            displayName = "Rule34Video",
            shortName = "R34V",
            primaryKind = MediaKind.VIDEO,
            defaultHost = "rule34video.com",
        ),
        linkHandler = { url ->
            val id = url.substringAfter("/video/").substringBefore('/').takeIf { it.isNotBlank() }
                ?: return@FakeLibrarySitePlugin null
            ResolvedLink.Video(siteId = SiteId.R34V, id = id, canonicalUrl = url)
        },
    ),
    FakeLibrarySitePlugin(
        descriptor = SiteDescriptor(
            id = SiteId.NHENTAI,
            displayName = "nHentai",
            shortName = "nHentai",
            primaryKind = MediaKind.COMIC,
            defaultHost = "nhentai.net",
        ),
        linkHandler = { url ->
            val id = url.substringAfter("/g/").substringBefore('/').takeIf { it.isNotBlank() }
                ?: return@FakeLibrarySitePlugin null
            ResolvedLink.Gallery(siteId = SiteId.NHENTAI, id = id, canonicalUrl = url)
        },
        authMode = AuthMode.CookieImport(setOf("sessionid")),
    ),
    FakeLibrarySitePlugin(
        descriptor = SiteDescriptor(
            id = SiteId.EVERIA,
            displayName = "Everia",
            shortName = "Everia",
            primaryKind = MediaKind.PHOTO,
            defaultHost = "everia.club",
        ),
    ),
)

internal fun sampleMedia(
    siteId: SiteId,
    id: String,
    kind: MediaKind = MediaKind.VIDEO,
    title: String = "Work $id",
): MediaItem = MediaItem(
    siteId = siteId,
    id = id,
    url = "https://${siteId.value}.example/item/$id",
    kind = kind,
    title = title,
    coverUrl = "https://${siteId.value}.example/cover/$id.jpg",
    durationSeconds = if (kind == MediaKind.VIDEO) 600 else null,
    pageCount = if (kind != MediaKind.VIDEO) 24 else null,
)

/**
 * Seeds a minimal valid local-library manifest so [LocalLibraryImporter.books] reports one item.
 */
internal fun seedLocalBook(
    rootDir: File,
    id: String = "a".repeat(64),
    title: String = "Local Book",
): File {
    val bookDir = File(rootDir, id).apply { mkdirs() }
    File(bookDir, "1.image").writeBytes(byteArrayOf(1))
    File(bookDir, "book.json").writeText(
        """{"id":"$id","title":"$title","files":["1.image"],"bytes":1,"importedAt":1000,"kind":"COMIC"}""",
    )
    return bookDir
}

internal fun sampleDownload(
    id: String,
    siteId: SiteId = SiteId.NHENTAI,
    mediaId: String = id,
    kind: DownloadKind = DownloadKind.COMIC,
    status: DownloadStatus = DownloadStatus.QUEUED,
    title: String = "Download $id",
    groupName: String = "",
    priority: Int = 0,
    totalUnits: Int = 20,
    completedUnits: Int = 0,
    downloadedBytes: Long = 1_048_576L,
    totalBytes: Long? = 2_097_152L,
    errorCategory: DownloadErrorCategory? = null,
    errorMessage: String? = null,
    createdAt: Long = 0L,
    updatedAt: Long = createdAt,
): DownloadItem = DownloadItem(
    id = id,
    siteId = siteId,
    mediaId = mediaId,
    url = "https://${siteId.value}.example/item/$mediaId",
    title = title,
    coverUrl = "https://${siteId.value}.example/cover/$mediaId.jpg",
    kind = kind,
    status = status,
    groupName = groupName,
    progressFraction = if (totalUnits > 0) completedUnits.toFloat() / totalUnits.toFloat() else 0f,
    completedUnits = completedUnits,
    totalUnits = totalUnits,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    priority = priority,
    errorCategory = errorCategory,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * Generates a minimal valid 1x1 PNG byte array accepted by `ImageStructureValidator` on pure JVM.
 */
internal fun minimalValidPngBytes(seed: Byte = 1): ByteArray {
    fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeInt(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        writeInt(crc)
        return out.toByteArray()
    }

    val ihdr = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)
    val rawScanline = byteArrayOf(0, seed, 0x20, 0x40)
    val deflater = Deflater()
    deflater.setInput(rawScanline)
    deflater.finish()
    val buf = ByteArray(64)
    val compressedLen = deflater.deflate(buf)
    deflater.end()

    return ByteArrayOutputStream().apply {
        write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        write(chunk("IHDR", ihdr))
        write(chunk("IDAT", buf.copyOf(compressedLen)))
        write(chunk("IEND", ByteArray(0)))
    }.toByteArray()
}

// ---- Reactive In-Memory Room DAOs ----

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
            val keepWorks = list.filter { it.siteId == siteId }
                .groupBy { it.seriesId?.takeIf { s -> s.isNotBlank() } ?: it.mediaId }
                .mapValues { (_, chapters) -> chapters.maxOf { it.updatedAt } }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(limit).map { it.key }.toSet()
            list.filter { it.siteId != siteId || (it.seriesId?.takeIf { s -> s.isNotBlank() } ?: it.mediaId) in keepWorks }
        }
    }
    override suspend fun deleteWork(siteId: String, workId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && (it.seriesId?.takeIf { s -> s.isNotBlank() } ?: it.mediaId) == workId } }
    }
    override suspend fun linkChapters(siteId: String, seriesId: String, seriesTitle: String?, seriesUrl: String?, chapterIds: List<String>) {
        val idSet = chapterIds.toSet()
        rows.update { list -> list.map { entity ->
            if (entity.siteId == siteId && entity.mediaId in idSet && (entity.seriesId.isNullOrBlank() || entity.seriesId == seriesId)) {
                val changed = entity.seriesId.isNullOrBlank() || entity.seriesId != seriesId ||
                    (entity.seriesTitle.isNullOrBlank() && !seriesTitle.isNullOrBlank()) ||
                    (entity.seriesUrl.isNullOrBlank() && !seriesUrl.isNullOrBlank())
                if (!changed) entity else entity.copy(
                    seriesId = seriesId,
                    seriesTitle = entity.seriesTitle?.takeIf { it.isNotBlank() } ?: seriesTitle,
                    seriesUrl = entity.seriesUrl?.takeIf { it.isNotBlank() } ?: seriesUrl,
                )
            } else entity
        } }
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
    override fun observeAll(): Flow<List<DownloadItemEntity>> =
        rows.map { it.sortedWith(compareBy<DownloadItemEntity> { e -> e.status.ordinal }.thenByDescending { e -> e.priority }.thenByDescending { e -> e.updatedAt }) }
    override fun observeByStatus(status: DownloadStatus): Flow<List<DownloadItemEntity>> =
        observeAll().map { it.filter { e -> e.status == status } }
    override fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadItemEntity>> =
        observeAll().map { it.filter { e -> e.status in statuses } }
    override fun observeBySite(siteId: String): Flow<List<DownloadItemEntity>> =
        observeAll().map { it.filter { e -> e.siteId == siteId } }
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

internal class InMemoryPageBookmarkDao : PageBookmarkDao {
    private val rows = MutableStateFlow<List<PageBookmarkEntity>>(emptyList())
    override fun observeAll(): Flow<List<PageBookmarkEntity>> = rows.map { it.sortedByDescending { e -> e.createdAt } }
    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<PageBookmarkEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.page } }
    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, page: Int): PageBookmarkEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.page == page }
    override suspend fun getAll(): List<PageBookmarkEntity> = rows.value
    override suspend fun upsert(entity: PageBookmarkEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<PageBookmarkEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryTimeBookmarkDao : TimeBookmarkDao {
    private val rows = MutableStateFlow<List<TimeBookmarkEntity>>(emptyList())
    override fun observeAll(): Flow<List<TimeBookmarkEntity>> = rows.map { it.sortedByDescending { e -> e.createdAt } }
    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<TimeBookmarkEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.positionMs } }
    override suspend fun getAll(): List<TimeBookmarkEntity> = rows.value
    override suspend fun getById(id: String): TimeBookmarkEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun upsert(entity: TimeBookmarkEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<TimeBookmarkEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryImageFavoriteDao : ImageFavoriteDao {
    private val rows = MutableStateFlow<List<ImageFavoriteEntity>>(emptyList())
    override fun observeAll(): Flow<List<ImageFavoriteEntity>> = rows.map { it.sortedByDescending { e -> e.addedAt } }
    override fun observeBySite(siteId: String): Flow<List<ImageFavoriteEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId }.sortedByDescending { e -> e.addedAt } }
    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<ImageFavoriteEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.pageIndex } }
    override fun observeExists(siteId: String, mediaId: String, pageIndex: Int): Flow<Boolean> =
        rows.map { it.any { e -> e.siteId == siteId && e.mediaId == mediaId && e.pageIndex == pageIndex } }
    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, pageIndex: Int): ImageFavoriteEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.pageIndex == pageIndex }
    override suspend fun getAll(): List<ImageFavoriteEntity> = rows.value
    override suspend fun upsert(entity: ImageFavoriteEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<ImageFavoriteEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun prune(limit: Int) {
        rows.update { list -> list.sortedByDescending { it.addedAt }.take(limit) }
    }
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
