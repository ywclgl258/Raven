package com.raven.app.core.media.download

import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.media.TestMediaFixtures
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.storage.DiskSidecarRecord
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.media.storage.OrphanedImportStaging
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression coverage for gallery offline page identity, selective-task recovery and
 * delete-versus-scan consistency (media review findings on GalleryPageRepository,
 * DownloadCoordinator and GalleryDownloadEngine).
 */
class GalleryOfflineStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Fixture whose pages differ only inside the query string, matching plugins that encode the
     * page index after `?`. Before the fix every page of the chapter hashed to one file.
     */
    private class QueryOnlyPageSource(
        private val item: MediaItem,
        private val pageCount: Int,
        private val imageUrls: Map<Int, String>,
    ) : SitePlugin {
        override val descriptor = SiteDescriptor(
            id = item.siteId,
            displayName = item.siteId.value,
            primaryKind = item.kind,
            defaultHost = "example.test",
        )
        override val gallerySource = object : GallerySource {
            override suspend fun loadGalleryDetail(url: String): GalleryDetail = GalleryDetail(
                item = item,
                pageCount = pageCount,
                pages = (1..pageCount).map { idx ->
                    GalleryPage(
                        index = idx,
                        pageUrl = "${item.url}chapter.png?page=$idx",
                        imageUrl = imageUrls.getValue(idx),
                    )
                },
            )

            override suspend fun loadGalleryPage(detail: GalleryDetail, pageIndex: Int, forceReload: Boolean): GalleryPage =
                detail.pages.first { it.index == pageIndex }

            override suspend fun resolvePageImage(pageUrl: String, preferOriginal: Boolean, forceReload: Boolean): GalleryPage {
                val idx = pageUrl.substringAfter("page=").substringBefore('&').toInt()
                return GalleryPage(index = idx, pageUrl = pageUrl, imageUrl = imageUrls.getValue(idx))
            }
        }
    }

    private class Env(
        val repo: GalleryPageRepository,
        val storage: StorageRootManager,
        val stateRepo: DownloadStateRepository,
        val plugin: SitePlugin,
        val item: MediaItem,
    )

    private fun createEnv(
        item: MediaItem,
        pageCount: Int,
        imageUrls: Map<Int, String>,
        name: String,
        reuseExisting: Boolean = false,
    ): Env {
        val dispatchers = DefaultAppDispatchers()
        val plugin = QueryOnlyPageSource(item, pageCount, imageUrls)
        fun root(name0: String): File =
            if (reuseExisting) tempFolder.root.resolve(name0).apply { mkdirs() } else tempFolder.newFolder(name0)
        val storage = StorageRootManager(
            context = null,
            settingsDataStore = null,
            privateRootOverride = root("$name-private"),
            sharedRootOverride = null,
            dispatchers = dispatchers,
        )
        val repo = GalleryPageRepository(
            context = null,
            imageClient = OkHttpClient(),
            storageRootManager = storage,
            localLibraryImporter = LocalLibraryImporter(
                context = null,
                rootDir = root("$name-local"),
                dispatchers = dispatchers,
                requireAndroidImageDecoder = false,
            ),
            siteRegistryProvider = { SiteRegistry(listOf(plugin)) },
            dispatchers = dispatchers,
            cacheRootOverride = root("$name-cache"),
            requireAndroidImageDecoder = false,
        )
        return Env(repo, storage, DownloadStateRepository(FakeDownloadItemDao()), plugin, item)
    }

    // ---- 1. Page identity ----

    @Test
    fun `pages of one chapter differing only in the query keep separate files and survive reopen`() = runBlocking {
        val server = MockWebServer()
        val pages = (1..3).map { idx -> TestMediaFixtures.validPng("page-$idx") }
        // Serve bytes by request path: concurrent workers would otherwise consume a FIFO queue
        // out of order and the assertion below would compare the wrong page.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val idx = request.path.orEmpty().substringAfterLast('/').substringBefore('.').toIntOrNull() ?: 1
                return MockResponse().setBody(Buffer().write(pages[idx - 1]))
            }
        }
        server.start()
        try {
            val item = MediaItem(
                siteId = SiteId.NHENTAI,
                id = "chapter-9",
                url = "https://example.test/gallery/9/",
                kind = MediaKind.COMIC,
                title = "One chapter, three pages",
                pageCount = 3,
            )
            val urls = (1..3).associateWith { idx -> server.url("/img/9/$idx.png").toString() }
            val env = createEnv(item, 3, urls, "keys")
            val detail = env.plugin.gallerySource!!.loadGalleryDetail(item.url)

            for (page in 1..3) {
                env.repo.downloadPage(detail, page)
            }

            val bookDir = env.repo.bookFolder(item.siteId, item.id, env.storage.layout())
            assertEquals(3, bookDir.listFiles().orEmpty().count { it.extension == "image" })

            assertArrayEquals(pages[0], env.repo.loadOriginalPage(detail, 1).bytes)
            assertArrayEquals(pages[1], env.repo.loadOriginalPage(detail, 2).bytes)
            assertArrayEquals(pages[2], env.repo.loadOriginalPage(detail, 3).bytes)

            // A second repository instance (process restart) resolves the same per-page files.
            // It reuses the on-disk roots, so the "reopen" constructor path is exercised.
            val reopened = createEnv(item, 3, urls, "keys", reuseExisting = true).repo
            assertArrayEquals(pages[1], reopened.loadOriginalPage(detail, 2).bytes)
            assertArrayEquals(pages[2], reopened.loadOriginalPage(detail, 3).bytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `legacy page files written before the key change are still readable`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val png = TestMediaFixtures.validPng("legacy")
            server.enqueue(MockResponse().setBody(Buffer().write(png)))

            val item = MediaItem(
                siteId = SiteId.NHENTAI,
                id = "legacy-1",
                url = "https://example.test/gallery/1/",
                kind = MediaKind.COMIC,
                title = "Legacy page",
                pageCount = 1,
            )
            val urls = mapOf(1 to server.url("/img/1.png").toString())
            val env = createEnv(item, 1, urls, "legacy")
            val detail = env.plugin.gallerySource!!.loadGalleryDetail(item.url)
            env.repo.downloadPage(detail, 1)

            // Replace the page with the pre-fix layout (URL key without the page index).
            val bookDir = env.repo.bookFolder(item.siteId, item.id, env.storage.layout())
            bookDir.listFiles().orEmpty()
                .filter { it.extension == "image" || it.name.endsWith(".page.json") }
                .forEach { it.delete() }
            val legacyPageUrl = detail.pages.single().pageUrl
            val legacyKey = StorageRootManager.sha256Hex(legacyPageUrl.substringBefore('?'))
            File(bookDir, "$legacyKey.image").writeBytes(png)
            File(bookDir, "$legacyKey.page.json").writeText(
                """{"page":{"index":1,"pageUrl":"$legacyPageUrl"},"encodedBytes":${png.size}}""",
            )

            assertArrayEquals(png, env.repo.loadOriginalPage(detail, 1).bytes)
        } finally {
            server.shutdown()
        }
    }

    // ---- 2. Selective task recovery ----

    @Test
    fun `paused selective task keeps its page selection when a rescan finds a partial book`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val png = TestMediaFixtures.validPng("partial")
            // Only the first selected page is on disk; the process died before the second.
            server.enqueue(MockResponse().setBody(Buffer().write(png)))
            val item = MediaItem(
                siteId = SiteId.NHENTAI,
                id = "sel-1",
                url = "https://example.test/gallery/sel/",
                kind = MediaKind.COMIC,
                title = "Selective",
                pageCount = 4,
            )
            val urls = (1..4).associateWith { idx -> server.url("/img/sel/$idx.png").toString() }
            val env = createEnv(item, 4, urls, "sel")
            val detail = env.plugin.gallerySource!!.loadGalleryDetail(item.url)
            val layout = env.storage.layout()
            env.repo.prepareDownload(item, layout)
            env.repo.downloadPage(detail, 1, layout)

            val existingRow = DownloadItem(
                id = "${item.siteId.value}:${item.id}",
                siteId = item.siteId,
                mediaId = item.id,
                url = item.url,
                title = item.title,
                kind = DownloadKind.COMIC,
                status = DownloadStatus.PAUSED,
                selectedPages = listOf(1, 2),
                totalUnits = 4,
                updatedAt = 900L,
            )
            env.stateRepo.upsert(existingRow)

            val record = env.repo.inspectDiskBookForRecovery(
                siteId = item.siteId,
                folder = env.repo.bookFolder(item.siteId, item.id, layout),
                sourceRoot = layout.root.absolutePath,
            )!!
            val projection = resolveGalleryScanProjection(record, env.stateRepo.getById(existingRow.id))

            assertEquals(DownloadStatus.PAUSED, projection.status)
            // The selection survives: resuming must not silently switch to the whole book.
            assertEquals(listOf(1, 2), projection.selectedPages)
            assertEquals(1, projection.completedUnits)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fully downloaded selective task is recovered as completed instead of paused`() {
        val record = DiskSidecarRecord(
            kind = DownloadKind.COMIC,
            siteId = SiteId.NHENTAI,
            mediaId = "sel-2",
            totalPages = 4,
            completedPages = 2,
            status = DownloadStatus.COMPLETED,
            intactSelection = listOf(1, 2),
        )
        val existing = DownloadItem(
            id = "nhentai:sel-2",
            siteId = SiteId.NHENTAI,
            mediaId = "sel-2",
            url = "https://example.test/gallery/sel2/",
            title = "Selective 2",
            kind = DownloadKind.COMIC,
            status = DownloadStatus.PAUSED,
            selectedPages = listOf(1, 2),
            updatedAt = 500L,
        )

        val projection = resolveGalleryScanProjection(record, existing)

        assertEquals(DownloadStatus.COMPLETED, projection.status)
        assertTrue(projection.selectedPages.isEmpty())
        assertEquals(2, projection.completedUnits)
    }

    @Test
    fun `whole book recovery stays completed and clears any stale selection`() {
        val record = DiskSidecarRecord(
            kind = DownloadKind.COMIC,
            siteId = SiteId.NHENTAI,
            mediaId = "whole-1",
            totalPages = 3,
            completedPages = 3,
            status = DownloadStatus.COMPLETED,
            intactSelection = emptyList(),
        )
        val existing = DownloadItem(
            id = "nhentai:whole-1",
            siteId = SiteId.NHENTAI,
            mediaId = "whole-1",
            url = "https://example.test/gallery/whole/",
            title = "Whole",
            kind = DownloadKind.COMIC,
            status = DownloadStatus.PAUSED,
            selectedPages = listOf(1),
            updatedAt = 500L,
        )

        val projection = resolveGalleryScanProjection(record, existing)

        assertEquals(DownloadStatus.COMPLETED, projection.status)
        assertTrue(projection.selectedPages.isEmpty())
        assertEquals(3, projection.completedUnits)
    }

    @Test
    fun `resume after a pause and rescan downloads only the original selection, never the whole book`() = runBlocking {
        val server = MockWebServer()
        var served = 0
        val png = TestMediaFixtures.validPng("resume")
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                served += 1
                return MockResponse().setBody(Buffer().write(png))
            }
        }
        server.start()
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val item = MediaItem(
                siteId = SiteId.NHENTAI,
                id = "resume-1",
                url = "https://example.test/gallery/resume/",
                kind = MediaKind.COMIC,
                title = "Resume selective",
                pageCount = 4,
            )
            val urls = (1..4).associateWith { idx -> server.url("/img/resume/$idx.png").toString() }
            val env = createEnv(item, 4, urls, "resume")
            val engine = GalleryDownloadEngine(
                context = null,
                galleryPageRepository = env.repo,
                storageRootManager = env.storage,
                downloadStateRepository = env.stateRepo,
                applicationScope = engineScope,
                dispatchers = DefaultAppDispatchers(),
                serviceStarter = {},
            )

            val queued = engine.enqueue(item, selectedPages = listOf(1, 2))
            engine.launchNext(engineScope)!!.join()
            assertEquals(DownloadStatus.COMPLETED, engine.getTask(queued.id)!!.status)
            assertEquals(2, served)

            // Process restart: the row is a paused task and the disk holds both selected pages.
            env.stateRepo.upsert(
                engine.getTask(queued.id)!!.copy(status = DownloadStatus.PAUSED, updatedAt = 1_000L),
            )
            val layout = env.storage.layout()
            val record = env.repo.inspectDiskBookForRecovery(
                siteId = item.siteId,
                folder = env.repo.bookFolder(item.siteId, item.id, layout),
                sourceRoot = layout.root.absolutePath,
            )!!
            val projection = resolveGalleryScanProjection(record, env.stateRepo.getById(queued.id))
            assertEquals(DownloadStatus.COMPLETED, projection.status)

            val resumedRow = env.stateRepo.getById(queued.id)!!.copy(
                status = projection.status,
                selectedPages = projection.selectedPages,
                completedUnits = projection.completedUnits,
                updatedAt = 1_100L,
            )
            env.stateRepo.upsert(resumedRow)

            // Resuming a recovered selective task must not re-enqueue the whole book.
            assertEquals(DownloadStatus.COMPLETED, engine.getTask(queued.id)!!.status)
            assertTrue(engine.getTask(queued.id)!!.selectedPages.isEmpty())
            assertEquals(2, served)
        } finally {
            engineScope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `repair of a completed selective task never widens to unselected pages`() = runBlocking {
        val server = MockWebServer()
        var served = 0
        val png = TestMediaFixtures.validPng("repair-selective")
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                served += 1
                return MockResponse().setBody(Buffer().write(png))
            }
        }
        server.start()
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val item = MediaItem(
                siteId = SiteId.NHENTAI,
                id = "repair-selective",
                url = "https://example.test/gallery/repair-selective/",
                kind = MediaKind.COMIC,
                title = "Repair selective",
                pageCount = 4,
            )
            val urls = (1..4).associateWith { idx -> server.url("/img/repair-selective/$idx.png").toString() }
            val env = createEnv(item, 4, urls, "repair-selective")
            val engine = GalleryDownloadEngine(
                context = null,
                galleryPageRepository = env.repo,
                storageRootManager = env.storage,
                downloadStateRepository = env.stateRepo,
                applicationScope = engineScope,
                dispatchers = DefaultAppDispatchers(),
                serviceStarter = {},
            )

            val queued = engine.enqueue(item, selectedPages = listOf(1, 2))
            engine.launchNext(engineScope)!!.join()
            assertEquals(2, served)
            val completed = engine.getTask(queued.id)!!
            assertEquals(DownloadStatus.COMPLETED, completed.status)
            assertEquals(listOf(1, 2), completed.selectedPages)
            assertEquals(2, completed.completedUnits)
            assertEquals(2, completed.totalUnits)

            // Pages 3/4 were never selected. Repair must not treat them as damaged work.
            val repaired = engine.repair(queued.id)
            assertEquals(DownloadStatus.COMPLETED, repaired.status)
            assertEquals(listOf(1, 2), repaired.selectedPages)
            assertEquals(2, repaired.completedUnits)
            assertEquals(2, repaired.totalUnits)
            assertEquals(2, served)
        } finally {
            engineScope.cancel()
            server.shutdown()
        }
    }

    // ---- 3. Delete versus scan ----

    @Test
    fun `projection is refused when a deletion lands between scan and commit`() {
        val record = DiskSidecarRecord(
            kind = DownloadKind.COMIC,
            siteId = SiteId.NHENTAI,
            mediaId = "gone-1",
            totalPages = 2,
            completedPages = 2,
            status = DownloadStatus.COMPLETED,
            sourceRoot = "/private",
        )
        val guard = DeletionGenerationGuard()
        val scanGeneration = guard.globalGeneration()

        // Interleaving A: the delete starts before the scan commits.
        val removal = guard.beginRemoval(record.compositeDownloadId)!!
        assertFalse(
            shouldProjectDiskSidecar(
                record = record,
                existing = null,
                scanGeneration = scanGeneration,
                galleryGeneration = scanGeneration,
                currentScanGeneration = { scanGeneration },
                galleryGuard = guard,
            ),
        )
        guard.completeRemoval(record.compositeDownloadId, removal)
        // After completion the captured generation stays permanently stale for that id.
        assertFalse(
            shouldProjectDiskSidecar(
                record = record,
                existing = null,
                scanGeneration = scanGeneration,
                galleryGeneration = scanGeneration,
                currentScanGeneration = { scanGeneration },
                galleryGuard = guard,
            ),
        )

        // Interleaving B: no deletion -> the intact record is projected.
        assertTrue(
            shouldProjectDiskSidecar(
                record = record.copy(mediaId = "kept-1"),
                existing = null,
                scanGeneration = scanGeneration,
                galleryGeneration = scanGeneration,
                currentScanGeneration = { scanGeneration },
                galleryGuard = guard,
            ),
        )

        // Interleaving C: another removal advanced the global generation -> stale snapshot dropped.
        val advanced = DeletionGenerationGuard()
        val base = advanced.globalGeneration()
        advanced.beginRemoval("r34v:other:720p")
        assertFalse(
            shouldProjectDiskSidecar(
                record = record,
                existing = null,
                scanGeneration = base,
                galleryGeneration = base,
                currentScanGeneration = { advanced.globalGeneration() },
                galleryGuard = advanced,
            ),
        )
    }

    // ---- 4. Orphan staging ----

    @Test
    fun `orphaned import staging directories are reclaimed only when stale`() {
        val root = tempFolder.newFolder("staging")
        val stale = File(root, "incoming-abc123").apply {
            mkdirs()
            File(this, "0.image").writeBytes(ByteArray(4096))
            setLastModified(1_000L)
        }
        val fresh = File(root, "incoming-fresh").apply {
            mkdirs()
            File(this, "0.image").writeBytes(ByteArray(1024))
        }
        val decoy = File(root, "not-staging").apply { mkdirs() }

        val result = OrphanedImportStaging.reclaim(
            libraryRoot = root,
            nowMs = 1_000L + OrphanedImportStaging.MIN_ORPHAN_AGE_MS + 60_000L,
        )

        assertEquals(1, result.removedFolders)
        assertEquals(4096L, result.reclaimedBytes)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(decoy.exists())
    }

    /** In-memory Room DAO fake shared by the media unit tests. */
    private class FakeDownloadItemDao : DownloadItemDao {
        private val items = MutableStateFlow<Map<String, DownloadItemEntity>>(emptyMap())

        override fun observeAll(): Flow<List<DownloadItemEntity>> =
            items.map { map ->
                map.values.sortedWith(
                    compareBy<DownloadItemEntity> { it.status.ordinal }
                        .thenByDescending { it.priority }
                        .thenByDescending { it.updatedAt },
                )
            }

        override fun observeByStatus(status: DownloadStatus): Flow<List<DownloadItemEntity>> =
            observeAll().map { list -> list.filter { it.status == status } }

        override fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadItemEntity>> =
            observeAll().map { list -> list.filter { it.status in statuses } }

        override fun observeBySite(siteId: String): Flow<List<DownloadItemEntity>> =
            observeAll().map { list -> list.filter { it.siteId == siteId } }

        override fun observeById(id: String): Flow<DownloadItemEntity?> = items.map { it[id] }

        override fun observeByMedia(siteId: String, mediaId: String): Flow<List<DownloadItemEntity>> =
            observeAll().map { list -> list.filter { it.siteId == siteId && it.mediaId == mediaId } }

        override fun observeCompletedCount(): Flow<Int> =
            items.map { map -> map.values.count { it.status == DownloadStatus.COMPLETED } }

        override suspend fun getById(id: String): DownloadItemEntity? = items.value[id]

        override suspend fun getByMedia(siteId: String, mediaId: String): List<DownloadItemEntity> =
            items.value.values.filter { it.siteId == siteId && it.mediaId == mediaId }

        override suspend fun getAll(): List<DownloadItemEntity> = items.value.values.toList()

        override suspend fun upsert(entity: DownloadItemEntity) {
            items.value = items.value + (entity.id to entity)
        }

        override suspend fun upsertAll(entities: List<DownloadItemEntity>) {
            items.value = items.value + entities.associateBy { it.id }
        }

        override suspend fun updateStatus(
            id: String,
            status: DownloadStatus,
            errorCategory: DownloadErrorCategory?,
            errorMessage: String?,
            retryAtEpochMs: Long?,
            updatedAt: Long,
        ) {
            val existing = items.value[id] ?: return
            upsert(
                existing.copy(
                    status = status,
                    errorCategory = errorCategory,
                    errorMessage = errorMessage,
                    retryAtEpochMs = retryAtEpochMs,
                    updatedAt = updatedAt,
                ),
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
            val existing = items.value[id] ?: return
            upsert(
                existing.copy(
                    progressFraction = progressFraction,
                    completedUnits = completedUnits,
                    totalUnits = totalUnits,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    updatedAt = updatedAt,
                ),
            )
        }

        override suspend fun updatePriority(id: String, priority: Int, updatedAt: Long) {
            val existing = items.value[id] ?: return
            upsert(existing.copy(priority = priority, updatedAt = updatedAt))
        }

        override suspend fun updateGroupName(id: String, groupName: String, updatedAt: Long) {
            val existing = items.value[id] ?: return
            upsert(existing.copy(groupName = groupName, updatedAt = updatedAt))
        }

        override suspend fun updateLocalPath(
            id: String,
            storageLocation: StorageLocation,
            localPath: String?,
            updatedAt: Long,
        ) {
            val existing = items.value[id] ?: return
            upsert(existing.copy(storageLocation = storageLocation, localPath = localPath, updatedAt = updatedAt))
        }

        override suspend fun deleteById(id: String) {
            items.value = items.value - id
        }

        override suspend fun deleteByMedia(siteId: String, mediaId: String) {
            items.value = items.value.filterValues { !(it.siteId == siteId && it.mediaId == mediaId) }
        }

        override suspend fun deleteByStatus(status: DownloadStatus) {
            items.value = items.value.filterValues { it.status != status }
        }

        override suspend fun deleteAll() {
            items.value = emptyMap()
        }
    }
}
