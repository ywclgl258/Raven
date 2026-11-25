package com.raven.app.feature.reader

import android.graphics.Bitmap
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
import com.raven.app.core.database.dao.TagTranslationDao
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
import com.raven.app.core.database.entity.TagTranslationEntity
import com.raven.app.core.database.entity.TimeBookmarkEntity
import com.raven.app.core.database.entity.UpdateInboxEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.media.download.InMemoryPlaybackQueuePersistence
import com.raven.app.core.media.download.PlaybackQueueStore
import com.raven.app.core.media.reader.GalleryExporter
import com.raven.app.core.media.reader.OfflineGallerySnapshot
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.AuthState
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.SitePlugin
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
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

internal class ReaderTestHarness(
    dispatcher: TestDispatcher,
    tempRoot: File,
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
    val shelfDao = InMemoryShelfDao()
    val shelfItemDao = InMemoryShelfItemDao()
    val smartShelfDao = InMemorySmartShelfDao()
    val pageBookmarkDao = InMemoryPageBookmarkDao()
    val timeBookmarkDao = InMemoryTimeBookmarkDao()
    val imageFavoriteDao = InMemoryImageFavoriteDao()
    val blockedTermDao = InMemoryBlockedTermDao()
    val downloadDao = InMemoryDownloadItemDao()
    val playbackPrefsDao = InMemoryPlaybackPreferencesDao()
    val readerPrefsDao = InMemoryReaderPreferencesDao()
    val tagSubscriptionDao = InMemoryTagSubscriptionDao()
    val savedSearchDao = InMemorySavedSearchDao()
    val updateInboxDao = InMemoryUpdateInboxDao()

    val settingsDataStore = SettingsDataStore(InMemoryPreferencesDataStore())
    val preferencesRepository = PreferencesRepository(settingsDataStore, playbackPrefsDao, readerPrefsDao)
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
    val blockedTermsRepository = BlockedTermsRepository(blockedTermDao, transactionRunner, clock)
    val subscriptionRepository = SubscriptionRepository(
        tagSubscriptionDao = tagSubscriptionDao,
        savedSearchDao = savedSearchDao,
        updateInboxDao = updateInboxDao,
        transactionRunner = transactionRunner,
        clock = clock,
    )

    val localLibraryImporter = LocalLibraryImporter(
        context = null,
        rootDir = File(tempRoot, "local-lib").apply { mkdirs() },
        dispatchers = dispatchers,
        requireAndroidImageDecoder = false,
        freeSpaceProvider = { 10L * 1024L * 1024L * 1024L },
        clockMs = clock,
    )

    val galleryExporter = GalleryExporter(
        stagingRoot = File(tempRoot, "exports").apply { mkdirs() },
        dispatchers = dispatchers,
        requireAndroidImageDecoder = false,
    )

    val exportGateway = MediaGalleryExportGateway(galleryExporter)

    val playbackQueueStore = PlaybackQueueStore(
        persistence = InMemoryPlaybackQueuePersistence(),
        dispatchers = dispatchers,
        tokenGenerator = { "queue-token-1" },
    )

    val tagTranslationDao = InMemoryTagTranslationDao()
    val mediaTagCacheDao = InMemoryMediaTagCacheDao()
    val runtimeDictionary = TagDictionary(mapOf("solo-female" to "单人女性", "tentacles" to "触手"))
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

internal class FakeGallerySitePlugin(
    override val descriptor: SiteDescriptor,
    private val detailHandler: (suspend (url: String) -> GalleryDetail)? = null,
    private val requiresAuthGateFlag: Boolean = descriptor.networkProfile.requiresAuthGate,
    var isAuthenticated: Boolean = !requiresAuthGateFlag,
) : SitePlugin {
    override val gallerySource: GallerySource? = detailHandler?.let { handler ->
        object : GallerySource {
            override suspend fun loadGalleryDetail(url: String): GalleryDetail = handler(url)

            override suspend fun loadGalleryPage(
                detail: GalleryDetail,
                pageIndex: Int,
                forceReload: Boolean,
            ): GalleryPage = detail.pages.firstOrNull { it.index == pageIndex }
                ?: GalleryPage(
                    index = pageIndex,
                    pageUrl = "${detail.item.url}/page/$pageIndex",
                    imageUrl = "https://${descriptor.defaultHost}/img/$pageIndex.png",
                )

            override suspend fun resolvePageImage(
                pageUrl: String,
                preferOriginal: Boolean,
                forceReload: Boolean,
            ): GalleryPage = GalleryPage(
                index = 1,
                pageUrl = pageUrl,
                imageUrl = "$pageUrl/raw.png",
            )
        }
    }

    override val authProvider: AuthProvider = object : AuthProvider {
        override val mode: AuthMode = if (requiresAuthGateFlag) AuthMode.CookieImport(setOf("ipb_member_id", "ipb_pass_hash")) else AuthMode.None
        override val requiresAuthGate: Boolean = requiresAuthGateFlag
        override suspend fun currentAuthState(): AuthState = AuthState(isAuthenticated = isAuthenticated)
    }
}

internal class FakeGalleryDownloadGateway : GalleryDownloadGateway {
    data class EnqueueCall(
        val item: MediaItem,
        val selectedPages: List<Int>?,
    )

    val calls = mutableListOf<EnqueueCall>()
    private val itemsFlow = MutableStateFlow<List<DownloadItem>>(emptyList())

    override fun observeByMedia(siteId: SiteId, mediaId: String): Flow<List<DownloadItem>> =
        itemsFlow.map { list -> list.filter { it.siteId == siteId && it.mediaId == mediaId } }

    override suspend fun enqueueGallery(
        item: MediaItem,
        selectedPages: Collection<Int>?,
        groupName: String,
        recheckSavedPages: Boolean,
    ): DownloadItem {
        val sorted = selectedPages?.distinct()?.sorted()
        calls += EnqueueCall(item = item, selectedPages = sorted)
        val created = DownloadItem(
            id = item.compositeKey,
            siteId = item.siteId,
            mediaId = item.id,
            url = item.url,
            title = item.title,
            coverUrl = item.coverUrl,
            kind = if (item.kind == MediaKind.PHOTO) DownloadKind.PHOTO else DownloadKind.COMIC,
            status = DownloadStatus.QUEUED,
            totalUnits = item.pageCount ?: 0,
            selectedPages = sorted.orEmpty(),
        )
        itemsFlow.update { list -> list.filterNot { it.id == created.id } + created }
        return created
    }
}

internal class FakeReaderPageLoader(
    private val detailLoader: suspend (siteId: SiteId, mediaId: String, url: String, offlineOnly: Boolean) -> OfflineGallerySnapshot,
    private val pageHandler: suspend (
        detail: GalleryDetail,
        pageIndex: Int,
        preferOriginal: Boolean,
        forceReload: Boolean,
    ) -> LoadedReaderPage = { detail, pageIndex, _, _ ->
        val ratioWidth = 800 + pageIndex * 40
        val ratioHeight = 1200
        LoadedReaderPage(
            page = detail.pages.firstOrNull { it.index == pageIndex } ?: GalleryPage(
                index = pageIndex,
                pageUrl = "${detail.item.url}/p/$pageIndex",
                imageUrl = "https://cdn.example/img/$pageIndex.png",
                width = ratioWidth,
                height = ratioHeight,
            ),
            bitmap = null,
            pixelWidth = ratioWidth,
            pixelHeight = ratioHeight,
            byteSize = 1024L * 1024L,
            fromCache = false,
        )
    },
    private val originalBytesHandler: suspend (pageIndex: Int) -> ByteArray = { idx ->
        validTestPngBytes("p-$idx")
    },
) : ReaderPageLoader {

    val loadedRequests = mutableListOf<Pair<Int, Boolean>>()

    override suspend fun loadDetail(
        siteId: SiteId,
        mediaId: String,
        url: String,
        offlineOnly: Boolean,
    ): OfflineGallerySnapshot = detailLoader(siteId, mediaId, url, offlineOnly)

    override suspend fun loadRenderedPage(
        detail: GalleryDetail,
        pageIndex: Int,
        preferOriginal: Boolean,
        forceReload: Boolean,
        offlineOnly: Boolean,
        sourceRoot: String,
    ): LoadedReaderPage {
        loadedRequests += pageIndex to preferOriginal
        return pageHandler(detail, pageIndex, preferOriginal, forceReload)
    }

    override suspend fun loadOriginalBytes(
        detail: GalleryDetail,
        pageIndex: Int,
        preferOriginal: Boolean,
        offlineOnly: Boolean,
        sourceRoot: String,
    ): ByteArray = originalBytesHandler(pageIndex)

    override suspend fun loadThumbnailBitmap(
        detail: GalleryDetail,
        pageIndex: Int,
        targetSizePx: Int,
        preferOriginal: Boolean,
        offlineOnly: Boolean,
        sourceRoot: String,
    ): Bitmap? = null
}

internal fun sampleSiteDescriptor(
    siteId: SiteId,
    displayName: String = siteId.value.uppercase(),
    primaryKind: MediaKind = MediaKind.COMIC,
    capabilities: Set<SiteCapability> = setOf(SiteCapability.TAGS, SiteCapability.AUTHORS),
    requiresAuthGate: Boolean = false,
): SiteDescriptor = SiteDescriptor(
    id = siteId,
    displayName = displayName,
    primaryKind = primaryKind,
    defaultHost = "${siteId.value}.example",
    capabilities = capabilities,
    networkProfile = SiteNetworkProfile(requiresAuthGate = requiresAuthGate),
)

internal fun sampleGalleryDetail(
    siteId: SiteId = SiteId.NHENTAI,
    mediaId: String = "g-101",
    pageCount: Int = 6,
    kind: MediaKind = MediaKind.COMIC,
    embeddedVideos: List<MediaItem> = emptyList(),
    tags: List<com.raven.app.core.model.TagRef> = emptyList(),
): GalleryDetail {
    val item = MediaItem(
        siteId = siteId,
        id = mediaId,
        url = "https://${siteId.value}.example/g/$mediaId",
        kind = kind,
        title = "Sample Gallery $mediaId",
        coverUrl = "https://${siteId.value}.example/cover/$mediaId.jpg",
        pageCount = pageCount,
    )
    val pages = (1..pageCount).map { p ->
        GalleryPage(
            index = p,
            pageUrl = "${item.url}/$p",
            imageUrl = "https://${siteId.value}.example/img/$mediaId/$p.jpg",
            thumbUrl = "https://${siteId.value}.example/thumb/$mediaId/$p.jpg",
            width = 800 + p * 50,
            height = 1200,
        )
    }
    return GalleryDetail(
        item = item,
        description = "Test gallery description",
        pageCount = pageCount,
        pages = pages,
        tags = tags,
        embeddedVideos = embeddedVideos,
    )
}

internal fun validTestPngBytes(tag: String = "default"): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    writePngChunk(out, "IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0))
    writePngChunk(out, "IDAT", "idat-$tag".toByteArray(Charsets.UTF_8))
    writePngChunk(out, "IEND", ByteArray(0))
    return out.toByteArray()
}

private fun writePngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    writeIntBE(out, data.size)
    out.write(typeBytes)
    out.write(data)
    val crc = CRC32().apply {
        update(typeBytes)
        update(data)
    }.value.toInt()
    writeIntBE(out, crc)
}

private fun writeIntBE(out: ByteArrayOutputStream, value: Int) {
    out.write((value ushr 24) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
}

// ---- Lightweight Reactive In-Memory Room DAOs ----

internal class InMemoryWatchProgressDao : WatchProgressDao {
    private val rows = MutableStateFlow<List<WatchProgressEntity>>(emptyList())
    override fun observeRecent(limit: Int): Flow<List<WatchProgressEntity>> = rows.map { it.take(limit) }
    override fun observeAll(): Flow<List<WatchProgressEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<WatchProgressEntity>> = rows.map { it.filter { e -> e.siteId == siteId } }
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
    override suspend fun deleteBySite(siteId: String) { rows.update { it.filterNot { e -> e.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) = Unit
}

internal class InMemoryReadingProgressDao : ReadingProgressDao {
    private val rows = MutableStateFlow<List<ReadingProgressEntity>>(emptyList())
    var upsertCount: Int = 0
        private set

    override fun observeRecent(limit: Int): Flow<List<ReadingProgressEntity>> = rows.map { it.take(limit) }
    override fun observeAll(): Flow<List<ReadingProgressEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<ReadingProgressEntity>> = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeOne(siteId: String, mediaId: String): Flow<ReadingProgressEntity?> =
        rows.map { it.firstOrNull { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override suspend fun getOne(siteId: String, mediaId: String): ReadingProgressEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll(): List<ReadingProgressEntity> = rows.value
    override suspend fun upsert(entity: ReadingProgressEntity) {
        upsertCount += 1
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.mediaId == entity.mediaId } + entity }
    }
    override suspend fun upsertAll(entities: List<ReadingProgressEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) { rows.update { it.filterNot { e -> e.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) = Unit
}

internal class InMemoryFavoriteDao : FavoriteDao {
    private val rows = MutableStateFlow<List<FavoriteEntity>>(emptyList())
    override fun observeAll(): Flow<List<FavoriteEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<FavoriteEntity>> = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeByKind(kind: FavoriteKind): Flow<List<FavoriteEntity>> = rows.map { it.filter { e -> e.kind == kind } }
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
    override suspend fun deleteBySite(siteId: String) { rows.update { it.filterNot { e -> e.siteId == siteId } } }
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
    override suspend fun clearCoverIfMatching(id: String, compositeKey: String, modifiedAt: Long) = Unit
    override suspend fun deleteById(id: String) { rows.update { it.filterNot { e -> e.id == id } } }
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
    override suspend fun updateCompleted(shelfId: String, siteId: String, mediaId: String, completed: Boolean) {
        rows.update { list ->
            list.map { if (it.shelfId == shelfId && it.siteId == siteId && it.mediaId == mediaId) it.copy(completed = completed) else it }
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
    override suspend fun deleteByShelf(shelfId: String) { rows.update { it.filterNot { e -> e.shelfId == shelfId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemorySmartShelfDao : SmartShelfDao {
    private val rows = MutableStateFlow<List<SmartShelfEntity>>(emptyList())
    override fun observeAll(): Flow<List<SmartShelfEntity>> = rows
    override fun observeById(id: String): Flow<SmartShelfEntity?> = rows.map { it.firstOrNull { e -> e.id == id } }
    override suspend fun getById(id: String): SmartShelfEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll(): List<SmartShelfEntity> = rows.value
    override suspend fun upsert(entity: SmartShelfEntity) { rows.update { it.filterNot { e -> e.id == entity.id } + entity } }
    override suspend fun upsertAll(entities: List<SmartShelfEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { it.filterNot { e -> e.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
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
    override fun observeAll(): Flow<List<TimeBookmarkEntity>> = rows
    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<TimeBookmarkEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override suspend fun getAll(): List<TimeBookmarkEntity> = rows.value
    override suspend fun upsert(entity: TimeBookmarkEntity) { rows.update { it.filterNot { e -> e.id == entity.id } + entity } }
    override suspend fun upsertAll(entities: List<TimeBookmarkEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { it.filterNot { e -> e.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.update { it.filterNot { e -> e.siteId == siteId && e.mediaId == mediaId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryImageFavoriteDao : ImageFavoriteDao {
    private val rows = MutableStateFlow<List<ImageFavoriteEntity>>(emptyList())
    override fun observeAll(): Flow<List<ImageFavoriteEntity>> = rows.map { it.sortedByDescending { e -> e.addedAt } }
    override fun observeBySite(siteId: String): Flow<List<ImageFavoriteEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId } }
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

internal class InMemoryBlockedTermDao : BlockedTermDao {
    private val rows = MutableStateFlow<List<BlockedTermEntity>>(emptyList())
    override fun observeAll(): Flow<List<BlockedTermEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<BlockedTermEntity>> = rows.map { it.filter { e -> e.siteId == siteId } }
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
    override suspend fun deleteBySite(siteId: String) { rows.update { it.filterNot { e -> e.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryDownloadItemDao : DownloadItemDao {
    private val rows = MutableStateFlow<List<DownloadItemEntity>>(emptyList())
    override fun observeAll(): Flow<List<DownloadItemEntity>> = rows
    override fun observeByStatus(status: DownloadStatus): Flow<List<DownloadItemEntity>> = rows.map { it.filter { e -> e.status == status } }
    override fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadItemEntity>> = rows.map { it.filter { e -> e.status in statuses } }
    override fun observeBySite(siteId: String): Flow<List<DownloadItemEntity>> = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeById(id: String): Flow<DownloadItemEntity?> = rows.map { it.firstOrNull { e -> e.id == id } }
    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<DownloadItemEntity>> =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override fun observeCompletedCount(): Flow<Int> = rows.map { it.count { e -> e.status == DownloadStatus.COMPLETED } }
    override suspend fun getById(id: String): DownloadItemEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getByMedia(siteId: String, mediaId: String): List<DownloadItemEntity> =
        rows.value.filter { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll(): List<DownloadItemEntity> = rows.value
    override suspend fun upsert(entity: DownloadItemEntity) { rows.update { it.filterNot { e -> e.id == entity.id } + entity } }
    override suspend fun upsertAll(entities: List<DownloadItemEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateStatus(id: String, status: DownloadStatus, errorCategory: DownloadErrorCategory?, errorMessage: String?, retryAtEpochMs: Long?, updatedAt: Long) = Unit
    override suspend fun updateProgress(id: String, progressFraction: Float, completedUnits: Int, totalUnits: Int, downloadedBytes: Long, totalBytes: Long?, updatedAt: Long) = Unit
    override suspend fun updatePriority(id: String, priority: Int, updatedAt: Long) = Unit
    override suspend fun updateGroupName(id: String, groupName: String, updatedAt: Long) = Unit
    override suspend fun updateLocalPath(id: String, storageLocation: StorageLocation, localPath: String?, updatedAt: Long) = Unit
    override suspend fun deleteById(id: String) { rows.update { it.filterNot { e -> e.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.update { it.filterNot { e -> e.siteId == siteId && e.mediaId == mediaId } }
    }
    override suspend fun deleteByStatus(status: DownloadStatus) { rows.update { it.filterNot { e -> e.status == status } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryPlaybackPreferencesDao : PlaybackPreferencesDao {
    private val rows = MutableStateFlow<List<PlaybackPreferencesEntity>>(emptyList())
    override fun observeBySite(siteId: String): Flow<PlaybackPreferencesEntity?> = rows.map { it.firstOrNull { e -> e.siteId == siteId } }
    override fun observeAll(): Flow<List<PlaybackPreferencesEntity>> = rows
    override suspend fun getBySite(siteId: String): PlaybackPreferencesEntity? = rows.value.firstOrNull { it.siteId == siteId }
    override suspend fun getAll(): List<PlaybackPreferencesEntity> = rows.value
    override suspend fun upsert(entity: PlaybackPreferencesEntity) { rows.update { it.filterNot { e -> e.siteId == entity.siteId } + entity } }
    override suspend fun upsertAll(entities: List<PlaybackPreferencesEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteBySite(siteId: String) { rows.update { it.filterNot { e -> e.siteId == siteId } } }
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
    override suspend fun deleteBySite(siteId: String) { rows.update { it.filterNot { e -> e.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryTagSubscriptionDao : TagSubscriptionDao {
    private val rows = MutableStateFlow<List<TagSubscriptionEntity>>(emptyList())
    override fun observeAll(): Flow<List<TagSubscriptionEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<TagSubscriptionEntity>> = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeExists(siteId: String, slug: String, scope: SearchScope): Flow<Boolean> =
        rows.map { it.any { e -> e.siteId == siteId && e.slug == slug && e.scope == scope } }
    override suspend fun getBySite(siteId: String): List<TagSubscriptionEntity> = rows.value.filter { it.siteId == siteId }
    override suspend fun getOne(siteId: String, slug: String, scope: SearchScope): TagSubscriptionEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.slug == slug && it.scope == scope }
    override suspend fun getMaxSortOrder(siteId: String): Int? = rows.value.filter { it.siteId == siteId }.maxOfOrNull { it.sortOrder }
    override suspend fun getAll(): List<TagSubscriptionEntity> = rows.value
    override suspend fun upsert(entity: TagSubscriptionEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.slug == entity.slug && it.scope == entity.scope } + entity }
    }
    override suspend fun upsertAll(entities: List<TagSubscriptionEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateLabel(siteId: String, slug: String, scope: SearchScope, label: String) = Unit
    override suspend fun updateSortOrder(siteId: String, slug: String, scope: SearchScope, sortOrder: Int) = Unit
    override suspend fun delete(siteId: String, slug: String, scope: SearchScope) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.slug == slug && it.scope == scope } }
    }
    override suspend fun deleteBySite(siteId: String) { rows.update { it.filterNot { e -> e.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemorySavedSearchDao : SavedSearchDao {
    private val rows = MutableStateFlow<List<SavedSearchEntity>>(emptyList())
    override fun observeAll(): Flow<List<SavedSearchEntity>> = rows
    override fun observeById(id: String): Flow<SavedSearchEntity?> = rows.map { it.firstOrNull { e -> e.id == id } }
    override suspend fun getById(id: String): SavedSearchEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll(): List<SavedSearchEntity> = rows.value
    override suspend fun upsert(entity: SavedSearchEntity) { rows.update { it.filterNot { e -> e.id == entity.id } + entity } }
    override suspend fun upsertAll(entities: List<SavedSearchEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateName(id: String, name: String) = Unit
    override suspend fun updateLastCheckedAt(id: String, checkedAt: Long) = Unit
    override suspend fun deleteById(id: String) { rows.update { it.filterNot { e -> e.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryUpdateInboxDao : UpdateInboxDao {
    private val rows = MutableStateFlow<List<UpdateInboxEntity>>(emptyList())
    override fun observeAll(): Flow<List<UpdateInboxEntity>> = rows
    override fun observeUnread(): Flow<List<UpdateInboxEntity>> = rows.map { it.filterNot { e -> e.isRead } }
    override fun observeUnreadCount(): Flow<Int> = rows.map { it.count { e -> !e.isRead } }
    override fun observeUnreadCountBySourceKind(sourceKind: InboxSourceKind): Flow<Int> = rows.map { 0 }
    override fun observeBySource(sourceKind: InboxSourceKind, sourceId: String): Flow<List<UpdateInboxEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<UpdateInboxEntity>> = rows
    override suspend fun getByIds(ids: List<String>): List<UpdateInboxEntity> = rows.value.filter { it.id in ids }
    override suspend fun getAll(): List<UpdateInboxEntity> = rows.value
    override suspend fun upsert(entity: UpdateInboxEntity) { rows.update { it.filterNot { e -> e.id == entity.id } + entity } }
    override suspend fun upsertAll(entities: List<UpdateInboxEntity>) = entities.forEach { upsert(it) }
    override suspend fun setRead(id: String, isRead: Boolean) = Unit
    override suspend fun markReadByIds(ids: List<String>) = Unit
    override suspend fun markAllRead() = Unit
    override suspend fun markAllReadBySourceKind(sourceKind: InboxSourceKind) = Unit
    override suspend fun markAllReadBySource(sourceKind: InboxSourceKind, sourceId: String) = Unit
    override suspend fun deleteById(id: String) { rows.update { it.filterNot { e -> e.id == id } } }
    override suspend fun deleteBySource(sourceKind: InboxSourceKind, sourceId: String) = Unit
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
