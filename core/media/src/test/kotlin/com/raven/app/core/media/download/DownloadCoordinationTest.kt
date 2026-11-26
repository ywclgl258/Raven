package com.raven.app.core.media.download

import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.media.TestMediaFixtures
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.reader.GalleryStorageException
import com.raven.app.core.media.storage.DiskSidecarRecord
import com.raven.app.core.media.storage.LocalLibraryImporter
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
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadCoordinationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---- Error Categorization & Retry-After ----

    @Test
    fun `error classifier maps typed SiteExceptions, Retry-After, TLS interruptions, and storage failures`() {
        val now = 1_000_000L

        val rateLimitedShort = DownloadErrorClassifier.classify(
            SiteException.RateLimited(retryAfterSeconds = 10L),
            nowMs = now,
        )
        assertEquals(DownloadErrorCategory.RATE_LIMITED, rateLimitedShort.category)
        assertTrue(rateLimitedShort.retryable)
        assertEquals(10_000L, DownloadErrorClassifier.automaticDelayMs(rateLimitedShort, now))

        // Retry-After > 30s returns null automaticDelayMs -> triggers RETRY_WAIT transition
        val rateLimitedLong = DownloadErrorClassifier.classify(
            SiteException.RateLimited(retryAfterSeconds = 120L),
            nowMs = now,
        )
        assertEquals(now + 120_000L, rateLimitedLong.retryAtEpochMs)
        assertNull(DownloadErrorClassifier.automaticDelayMs(rateLimitedLong, now))

        assertEquals(
            DownloadErrorCategory.AUTH_REQUIRED,
            DownloadErrorClassifier.classify(SiteException.AuthRequired()).category,
        )
        assertEquals(
            DownloadErrorCategory.NOT_FOUND,
            DownloadErrorClassifier.classify(SiteException.NotFound()).category,
        )
        assertEquals(
            DownloadErrorCategory.QUOTA_EXCEEDED,
            DownloadErrorClassifier.classify(SiteException.QuotaExceeded()).category,
        )
        assertEquals(
            DownloadErrorCategory.SOURCE_CHANGED,
            DownloadErrorClassifier.classify(SiteException.ParseError("changed")).category,
        )
        assertEquals(
            DownloadErrorCategory.STORAGE,
            DownloadErrorClassifier.classify(GalleryStorageException("disk full")).category,
        )
        assertEquals(
            DownloadErrorCategory.STORAGE,
            DownloadErrorClassifier.classify(IOException("io"), stage = DownloadStage.COMMITTING).category,
        )

        // TLS socket timeout -> transient NETWORK (retryable)
        val transientTls = SSLHandshakeException("Read error").apply {
            initCause(SocketTimeoutException("timeout"))
        }
        val tlsNet = DownloadErrorClassifier.classify(transientTls)
        assertEquals(DownloadErrorCategory.NETWORK, tlsNet.category)
        assertTrue(tlsNet.retryable)

        // TLS certificate validation failure -> hard TLS (non-retryable)
        val certFailure = SSLHandshakeException("Cert error").apply {
            initCause(CertPathValidatorException("expired"))
        }
        val tlsHard = DownloadErrorClassifier.classify(certFailure)
        assertEquals(DownloadErrorCategory.TLS, tlsHard.category)
        assertFalse(tlsHard.retryable)
    }

    @Test
    fun `gallery download engine downloads multi-page book with 3 concurrent workers and caps strict sites`() =
        runBlocking {
            val appDispatchers = DefaultAppDispatchers()
            val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val server = MockWebServer()
            val validPng = TestMediaFixtures.validPng("concurrent")
            server.start()
            try {
                repeat(4) {
                    server.enqueue(MockResponse().setBody(Buffer().write(validPng)))
                }
                val item = MediaItem(
                    siteId = SiteId.NHENTAI,
                    id = "1004",
                    url = "https://nhentai.net/g/1004/",
                    kind = MediaKind.COMIC,
                    title = "Concurrent 4-Page Book",
                    pageCount = 4,
                )
                val pages = (1..4).map { idx ->
                    GalleryPage(
                        index = idx,
                        pageUrl = "https://nhentai.net/g/1004/$idx/",
                        imageUrl = server.url("/img/$idx.png").toString(),
                    )
                }
                val plugin = object : SitePlugin {
                    override val descriptor = SiteDescriptor(
                        id = SiteId.NHENTAI,
                        displayName = "nHentai",
                        primaryKind = MediaKind.COMIC,
                        defaultHost = "nhentai.net",
                    )
                    override val gallerySource = object : GallerySource {
                        override suspend fun loadGalleryDetail(url: String) = GalleryDetail(
                            item = item,
                            pageCount = 4,
                            pages = pages,
                        )
                        override suspend fun loadGalleryPage(detail: GalleryDetail, pageIndex: Int, forceReload: Boolean) =
                            pages[pageIndex - 1]
                        override suspend fun resolvePageImage(pageUrl: String, preferOriginal: Boolean, forceReload: Boolean) =
                            pages.first { it.pageUrl == pageUrl }
                    }
                }
                val strictPlugin = object : SitePlugin {
                    override val descriptor = SiteDescriptor(
                        id = SiteId.EHENTAI,
                        displayName = "E-Hentai",
                        primaryKind = MediaKind.COMIC,
                        defaultHost = "e-hentai.org",
                        networkProfile = com.raven.app.core.model.SiteNetworkProfile(
                            pageIntervalMs = 800L,
                            imageIntervalMs = 400L,
                        ),
                    )
                }
                val storage = StorageRootManager(
                    context = null,
                    settingsDataStore = null,
                    privateRootOverride = tempFolder.newFolder("conc-dl-private"),
                    sharedRootOverride = null,
                    dispatchers = appDispatchers,
                )
                val pageRepo = GalleryPageRepository(
                    context = null,
                    imageClient = OkHttpClient(),
                    storageRootManager = storage,
                    localLibraryImporter = LocalLibraryImporter(null, tempFolder.newFolder("conc-dl-lib"), appDispatchers),
                    siteRegistryProvider = { SiteRegistry(listOf(plugin, strictPlugin)) },
                    dispatchers = appDispatchers,
                    cacheRootOverride = tempFolder.newFolder("conc-dl-cache"),
                    requireAndroidImageDecoder = false,
                )
                assertEquals(2, pageRepo.recommendedImageConcurrency(SiteId.EHENTAI, 3))
                assertEquals(3, pageRepo.recommendedImageConcurrency(SiteId.NHENTAI, 3))

                val engine = GalleryDownloadEngine(
                    context = null,
                    galleryPageRepository = pageRepo,
                    storageRootManager = storage,
                    downloadStateRepository = DownloadStateRepository(FakeDownloadItemDao()),
                    applicationScope = engineScope,
                    dispatchers = appDispatchers,
                    serviceStarter = {},
                    concurrencyProvider = { siteId -> pageRepo.recommendedImageConcurrency(siteId, 3) },
                )
                val queued = engine.enqueue(item)
                engine.launchNext(engineScope)!!.join()

                val completed = engine.getTask(queued.id)!!
                assertEquals(DownloadStatus.COMPLETED, completed.status)
                assertEquals(4, completed.completedUnits)
                assertEquals(4, server.requestCount)
                assertTrue(pageRepo.isDownloaded(SiteId.NHENTAI, "1004"))
            } finally {
                engineScope.cancel()
                server.shutdown()
            }
        }

    @Test
    fun `download notifications compute video progress, format details, and use collision-free IDs`() {
        assertEquals(1f, DownloadNotifications.computeVideoProgressFraction(-1f, 0L, -1L, isCompleted = true), 0.0001f)
        assertEquals(0.45f, DownloadNotifications.computeVideoProgressFraction(45f, 450L, 1_000L, isCompleted = false), 0.0001f)
        assertEquals(0.5f, DownloadNotifications.computeVideoProgressFraction(-1f, 500L, 1_000L, isCompleted = false), 0.0001f)
        assertEquals(0.25f, DownloadNotifications.computeVideoProgressFraction(-1f, 0L, -1L, isCompleted = false, fallback = 0.25f), 0.0001f)

        val knownVideoText = DownloadNotifications.formatVideoProgressText(
            title = "测试视频",
            percentDownloaded = 42.4f,
            bytesDownloaded = 20L * 1_024L * 1_024L,
            contentLength = 50L * 1_024L * 1_024L,
            waitingForNetwork = false,
        )
        assertTrue(knownVideoText.contains("42%"))
        assertTrue(knownVideoText.contains("20.0 MB / 50.0 MB"))

        val streamBytesOnlyText = DownloadNotifications.formatVideoProgressText(
            title = "HLS 视频",
            percentDownloaded = -1f,
            bytesDownloaded = 5L * 1_024L * 1_024L,
            contentLength = -1L,
            waitingForNetwork = false,
        )
        assertTrue(streamBytesOnlyText.contains("已下载 5.0 MB"))

        assertTrue(
            DownloadNotifications.formatVideoProgressText("A", 0f, 0L, -1L, waitingForNetwork = true)
                .startsWith("等待网络条件"),
        )
        assertTrue(
            DownloadNotifications.formatVideoProgressText("A", -1f, 0L, -1L, waitingForNetwork = false)
                .contains("正在连接"),
        )

        val galleryText = DownloadNotifications.formatGalleryProgressText(
            title = "图集作品",
            completedUnits = 3,
            totalUnits = 10,
            downloadedBytes = 2L * 1_024L * 1_024L,
            storageError = null,
        )
        assertTrue(galleryText.contains("3 / 10 页 (30%)"))
        assertTrue(galleryText.contains("2.0 MB"))
        assertTrue(
            DownloadNotifications.formatGalleryProgressText("图集", 0, 10, 0L, "磁盘已满")
                .startsWith("存储写入失败"),
        )

        val idA = DownloadNotifications.stableEventNotificationId("r34v:100:720p")
        val idB = DownloadNotifications.stableEventNotificationId("r34v:100:720p")
        assertEquals(idA, idB)
        assertTrue(idA >= 10_000)
        assertTrue(idA != VideoDownloadService.FOREGROUND_NOTIFICATION_ID)
        assertTrue(idA != GalleryDownloadService.FOREGROUND_NOTIFICATION_ID)
    }

    @Test
    fun `stale completed sidecar cannot overwrite newer projection or storage warning`() {
        val existing = DownloadItem(
            id = "r34v:v1:1080p",
            siteId = SiteId.R34V,
            mediaId = "v1",
            url = "https://rule34video.com/v1",
            title = "new-title",
            kind = DownloadKind.VIDEO,
            status = DownloadStatus.COMPLETED,
            downloadedBytes = 200L,
            errorCategory = DownloadErrorCategory.STORAGE,
            errorMessage = "sidecar write failed",
            updatedAt = 200L,
        )
        val oldSidecar = DiskSidecarRecord(
            kind = DownloadKind.VIDEO,
            siteId = SiteId.R34V,
            mediaId = "v1",
            quality = "1080p",
            title = "old-title",
            bytes = 100L,
            updatedAt = 100L,
            status = DownloadStatus.COMPLETED,
        )

        assertTrue(shouldSkipStaleCompletedVideoSidecar(oldSidecar, existing))
        assertFalse(shouldSkipStaleCompletedVideoSidecar(oldSidecar.copy(updatedAt = 300L), existing))
        assertFalse(
            shouldSkipStaleCompletedVideoSidecar(
                oldSidecar.copy(sourceRoot = "/shared"),
                existing.copy(localPath = "/private"),
            ),
        )
        assertFalse(
            shouldSkipStaleCompletedVideoSidecar(
                oldSidecar.copy(status = DownloadStatus.FAILED),
                existing,
            ),
        )
    }

    // ---- Deletion Generation Guard ----

    @Test
    fun `deletion generation guard prevents stale refresh tasks from resurrecting deleted downloads`() {
        val guard = DeletionGenerationGuard()
        val id = "r34v:vid-42:720p"

        val capturedGen = guard.currentGeneration(id)
        val scanGeneration = guard.globalGeneration()
        assertFalse(guard.isStale(id, capturedGen))

        // User deletes while refresh is in flight
        val removalToken = guard.beginRemoval(id)
        assertNotNull(removalToken)
        assertTrue(guard.globalGeneration() > scanGeneration)
        val completedRemovalGeneration = guard.globalGeneration()
        assertTrue(guard.isRemoving(id))
        assertTrue(guard.isStale(id, capturedGen))
        // Duplicate removal while already in-flight returns null
        assertNull(guard.beginRemoval(id))
        assertEquals(completedRemovalGeneration, guard.globalGeneration())

        // Even after removal finishes, the old refresh's generation remains permanently stale
        guard.completeRemoval(id, removalToken!!)
        assertEquals(completedRemovalGeneration, guard.globalGeneration())
        assertFalse(guard.isRemoving(id))
        assertTrue(guard.isStale(id, capturedGen))
        assertFalse(guard.isStale(id, guard.currentGeneration(id)))
    }

    // ---- DownloadPolicyController ----

    @Test
    fun `download policy controller evaluates unmetered and connectivity rules accurately`() {
        assertTrue(DownloadPolicyController.evaluate(isConnected = true, isMetered = true, unmeteredOnly = false).isAllowed)
        assertFalse(DownloadPolicyController.evaluate(isConnected = true, isMetered = true, unmeteredOnly = true).isAllowed)
        assertTrue(DownloadPolicyController.evaluate(isConnected = true, isMetered = false, unmeteredOnly = true).isAllowed)
        assertFalse(DownloadPolicyController.evaluate(isConnected = false, isMetered = false, unmeteredOnly = false).isAllowed)
    }

    // ---- GalleryDownloadEngine end-to-end: selective pages, audit, repair, and RETRY_WAIT ----

    @Test
    fun `gallery download engine executes serial download, audits corrupt page, and repairs missing pages`() =
        runBlocking {
            val appDispatchers = DefaultAppDispatchers()
            val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val server = MockWebServer()
            val page1Png = TestMediaFixtures.validPng("g1")
            val page2Png = TestMediaFixtures.validPng("g2")
            server.start()
            try {
                val p1Url = server.url("/img/1.png").toString()
                val p2Url = server.url("/img/2.png").toString()
                server.enqueue(MockResponse().setBody(Buffer().write(page1Png)))
                server.enqueue(MockResponse().setBody(Buffer().write(page2Png)))
                // Extra response for targeted repair of page 2
                server.enqueue(MockResponse().setBody(Buffer().write(page2Png)))

                val galleryItem = MediaItem(
                    siteId = SiteId.NHENTAI,
                    id = "999",
                    url = "https://nhentai.net/g/999/",
                    kind = MediaKind.COMIC,
                    title = "Test Comic 999",
                    pageCount = 2,
                )
                val fakePlugin = object : SitePlugin {
                    override val descriptor = SiteDescriptor(
                        id = SiteId.NHENTAI,
                        displayName = "nHentai",
                        primaryKind = MediaKind.COMIC,
                        defaultHost = "nhentai.net",
                    )
                    override val gallerySource = object : GallerySource {
                        override suspend fun loadGalleryDetail(url: String): GalleryDetail = GalleryDetail(
                            item = galleryItem,
                            pageCount = 2,
                            pages = listOf(
                                GalleryPage(index = 1, pageUrl = "https://nhentai.net/g/999/1/", imageUrl = p1Url),
                                GalleryPage(index = 2, pageUrl = "https://nhentai.net/g/999/2/", imageUrl = p2Url),
                            ),
                        )

                        override suspend fun loadGalleryPage(detail: GalleryDetail, pageIndex: Int, forceReload: Boolean): GalleryPage =
                            detail.pages.first { it.index == pageIndex }

                        override suspend fun resolvePageImage(pageUrl: String, preferOriginal: Boolean, forceReload: Boolean): GalleryPage =
                            if (pageUrl.endsWith("/1/")) {
                                GalleryPage(index = 1, pageUrl = pageUrl, imageUrl = p1Url)
                            } else {
                                GalleryPage(index = 2, pageUrl = pageUrl, imageUrl = p2Url)
                            }
                    }
                }

                val privateRoot = tempFolder.newFolder("dl-private")
                val storage = StorageRootManager(
                    context = null,
                    settingsDataStore = null,
                    privateRootOverride = privateRoot,
                    sharedRootOverride = null,
                    dispatchers = appDispatchers,
                )
                val localImporter = LocalLibraryImporter(
                    context = null,
                    rootDir = tempFolder.newFolder("dl-local"),
                    dispatchers = appDispatchers,
                    requireAndroidImageDecoder = false,
                )
                val pageRepo = GalleryPageRepository(
                    context = null,
                    imageClient = OkHttpClient(),
                    storageRootManager = storage,
                    localLibraryImporter = localImporter,
                    siteRegistryProvider = { SiteRegistry(listOf(fakePlugin)) },
                    dispatchers = appDispatchers,
                    cacheRootOverride = tempFolder.newFolder("dl-cache"),
                    requireAndroidImageDecoder = false,
                )
                val stateRepo = DownloadStateRepository(FakeDownloadItemDao())
                val engine = GalleryDownloadEngine(
                    context = null,
                    galleryPageRepository = pageRepo,
                    storageRootManager = storage,
                    downloadStateRepository = stateRepo,
                    applicationScope = engineScope,
                    dispatchers = appDispatchers,
                    serviceStarter = {},
                )

                // Enqueue and run serial download
                val queued = engine.enqueue(galleryItem)
                assertEquals(DownloadStatus.QUEUED, queued.status)
                val job = engine.launchNext(engineScope)
                assertNotNull(job)
                job!!.join()

                val completed = engine.getTask(queued.id)!!
                assertEquals(DownloadStatus.COMPLETED, completed.status)
                assertEquals(2, completed.completedUnits)
                assertTrue(pageRepo.isDownloaded(SiteId.NHENTAI, "999"))

                // Corrupt page 2 on disk and run integrity audit
                val bookDir = pageRepo.bookFolder(SiteId.NHENTAI, "999", storage.layout())
                val p2Key = StorageRootManager.sha256Hex("https://nhentai.net/g/999/2/")
                File(bookDir, "$p2Key.image").writeBytes(TestMediaFixtures.truncatedPng())

                val auditBefore = engine.inspect(queued.id)
                assertFalse(auditBefore.isComplete)
                assertEquals(listOf(1), auditBefore.validPages)
                assertEquals(listOf(2), auditBefore.corruptPages)

                // Repair should re-queue ONLY page 2
                val repairing = engine.repair(queued.id)
                assertEquals(DownloadStatus.QUEUED, repairing.status)
                assertEquals(listOf(2), repairing.selectedPages)

                engine.launchNext(engineScope)!!.join()

                val auditAfter = engine.inspect(queued.id)
                assertTrue(auditAfter.isComplete)
                assertEquals(DownloadStatus.COMPLETED, engine.getTask(queued.id)!!.status)
            } finally {
                engineScope.cancel()
                server.shutdown()
            }
        }

    @Test
    fun `gallery page repository recovers via reloadUrlOnError on HTTP 403`() = runBlocking {
        val appDispatchers = DefaultAppDispatchers()
        val server = MockWebServer()
        val recoveredPng = TestMediaFixtures.validPng("eh-reloaded")
        server.start()
        try {
            val badImgUrl = server.url("/expired.png").toString()
            val freshImgUrl = server.url("/fresh.png").toString()
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody(Buffer().write(recoveredPng)))

            val item = MediaItem(
                siteId = SiteId.EHENTAI,
                id = "777",
                url = "https://e-hentai.org/g/777/token/",
                kind = MediaKind.COMIC,
                title = "EH Test",
                pageCount = 1,
            )
            var sawReloadToken = false
            val ehPlugin = object : SitePlugin {
                override val descriptor = SiteDescriptor(
                    id = SiteId.EHENTAI,
                    displayName = "E-Hentai",
                    primaryKind = MediaKind.COMIC,
                    defaultHost = "e-hentai.org",
                )
                override val gallerySource = object : GallerySource {
                    override suspend fun loadGalleryDetail(url: String): GalleryDetail = GalleryDetail(
                        item = item,
                        pageCount = 1,
                        pages = listOf(GalleryPage(index = 1, pageUrl = "https://e-hentai.org/s/abc/777-1")),
                    )

                    override suspend fun loadGalleryPage(detail: GalleryDetail, pageIndex: Int, forceReload: Boolean): GalleryPage =
                        resolvePageImage("https://e-hentai.org/s/abc/777-1", false, forceReload)

                    override suspend fun resolvePageImage(pageUrl: String, preferOriginal: Boolean, forceReload: Boolean): GalleryPage {
                        val isReload = pageUrl.contains("nl=tok123")
                        if (isReload) sawReloadToken = true
                        return GalleryPage(
                            index = 1,
                            pageUrl = pageUrl,
                            imageUrl = if (isReload) freshImgUrl else badImgUrl,
                            reloadToken = "tok123",
                        )
                    }

                    override fun reloadUrlOnError(cause: SiteException, page: GalleryPage): String? =
                        if (cause is SiteException.Forbidden && !page.reloadToken.isNullOrBlank()) {
                            "${page.pageUrl}?nl=${page.reloadToken}"
                        } else {
                            null
                        }
                }
            }

            val storage = StorageRootManager(
                context = null,
                settingsDataStore = null,
                privateRootOverride = tempFolder.newFolder("eh-priv"),
                sharedRootOverride = null,
                dispatchers = appDispatchers,
            )
            val localImporter = LocalLibraryImporter(
                context = null,
                rootDir = tempFolder.newFolder("eh-local"),
                dispatchers = appDispatchers,
                requireAndroidImageDecoder = false,
            )
            val repo = GalleryPageRepository(
                context = null,
                imageClient = OkHttpClient(),
                storageRootManager = storage,
                localLibraryImporter = localImporter,
                siteRegistryProvider = { SiteRegistry(listOf(ehPlugin)) },
                dispatchers = appDispatchers,
                cacheRootOverride = tempFolder.newFolder("eh-cache"),
                requireAndroidImageDecoder = false,
            )

            val detail = ehPlugin.gallerySource.loadGalleryDetail(item.url)
            val loaded = repo.loadOriginalPage(detail, pageIndex = 1)
            assertTrue(sawReloadToken)
            assertArrayEquals(recoveredPng, loaded.bytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gallery page repository triggers reloadUrlOnError on corrupt image bytes and separates preferOriginal cache keys`() =
        runBlocking {
            val appDispatchers = DefaultAppDispatchers()
            val server = MockWebServer()
            val samplePng = TestMediaFixtures.validPng("sample")
            val originalPng = TestMediaFixtures.validPng("original")
            server.start()
            try {
                val badImgUrl = server.url("/corrupt.png").toString()
                val freshImgUrl = server.url("/fresh-sample.png").toString()
                val origUrl = server.url("/original.png").toString()
                // 1st attempt (preferOriginal=false) returns corrupt body with HTTP 200 -> reloadUrlOnError -> freshImgUrl
                server.enqueue(MockResponse().setBody(Buffer().write(TestMediaFixtures.truncatedPng())))
                server.enqueue(MockResponse().setBody(Buffer().write(samplePng)))
                // 2nd call with preferOriginal=true should NOT hit the preferOriginal=false cache entry!
                server.enqueue(MockResponse().setBody(Buffer().write(originalPng)))

                val item = MediaItem(
                    siteId = SiteId.EHENTAI,
                    id = "778",
                    url = "https://e-hentai.org/g/778/token/",
                    kind = MediaKind.COMIC,
                    title = "EH Corrupt + Original Test",
                    pageCount = 1,
                )
                val ehPlugin = object : SitePlugin {
                    override val descriptor = SiteDescriptor(
                        id = SiteId.EHENTAI,
                        displayName = "E-Hentai",
                        primaryKind = MediaKind.COMIC,
                        defaultHost = "e-hentai.org",
                    )
                    override val gallerySource = object : GallerySource {
                        override suspend fun loadGalleryDetail(url: String): GalleryDetail = GalleryDetail(
                            item = item,
                            pageCount = 1,
                            pages = listOf(GalleryPage(index = 1, pageUrl = "https://e-hentai.org/s/xyz/778-1")),
                        )

                        override suspend fun loadGalleryPage(detail: GalleryDetail, pageIndex: Int, forceReload: Boolean): GalleryPage =
                            resolvePageImage("https://e-hentai.org/s/xyz/778-1", false, forceReload)

                        override suspend fun resolvePageImage(pageUrl: String, preferOriginal: Boolean, forceReload: Boolean): GalleryPage {
                            val isReload = pageUrl.contains("nl=hath2")
                            return GalleryPage(
                                index = 1,
                                pageUrl = pageUrl,
                                imageUrl = if (isReload) freshImgUrl else badImgUrl,
                                originalImageUrl = origUrl,
                                reloadToken = "hath2",
                            )
                        }

                        override fun reloadUrlOnError(cause: SiteException, page: GalleryPage): String? =
                            if (cause is SiteException.Network && !page.reloadToken.isNullOrBlank()) {
                                "${page.pageUrl}?nl=${page.reloadToken}"
                            } else {
                                null
                            }
                    }
                }

                val storage = StorageRootManager(
                    context = null,
                    settingsDataStore = null,
                    privateRootOverride = tempFolder.newFolder("eh2-priv"),
                    sharedRootOverride = null,
                    dispatchers = appDispatchers,
                )
                val localImporter = LocalLibraryImporter(
                    context = null,
                    rootDir = tempFolder.newFolder("eh2-local"),
                    dispatchers = appDispatchers,
                    requireAndroidImageDecoder = false,
                )
                val repo = GalleryPageRepository(
                    context = null,
                    imageClient = OkHttpClient(),
                    storageRootManager = storage,
                    localLibraryImporter = localImporter,
                    siteRegistryProvider = { SiteRegistry(listOf(ehPlugin)) },
                    dispatchers = appDispatchers,
                    cacheRootOverride = tempFolder.newFolder("eh2-cache"),
                    requireAndroidImageDecoder = false,
                )

                val detail = ehPlugin.gallerySource.loadGalleryDetail(item.url)
                val sampleLoaded = repo.loadOriginalPage(detail, pageIndex = 1, preferOriginal = false)
                assertArrayEquals(samplePng, sampleLoaded.bytes)

                // Wait - consume the accidental extra mock response? Wait: we only should enqueue 3 responses! Let's fix enqueue above!
                val origLoaded = repo.loadOriginalPage(detail, pageIndex = 1, preferOriginal = true)
                assertFalse(origLoaded.fromCache)
                assertArrayEquals(originalPng, origLoaded.bytes)
                assertTrue(repo.readingCacheBytes() > 0L)
                repo.clearAllReadingCaches()
                assertEquals(0L, repo.readingCacheBytes())
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `gallery download engine transitions to RETRY_WAIT on long Retry-After and respects manual pause`() =
        runBlocking {
            val appDispatchers = DefaultAppDispatchers()
            val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val item = MediaItem(
                    siteId = SiteId.EHENTAI,
                    id = "888",
                    url = "https://e-hentai.org/g/888/token/",
                    kind = MediaKind.COMIC,
                    title = "Rate Limited Book",
                    pageCount = 1,
                )
                val rateLimitedPlugin = object : SitePlugin {
                    override val descriptor = SiteDescriptor(
                        id = SiteId.EHENTAI,
                        displayName = "E-Hentai",
                        primaryKind = MediaKind.COMIC,
                        defaultHost = "e-hentai.org",
                    )
                    override val gallerySource = object : GallerySource {
                        override suspend fun loadGalleryDetail(url: String): GalleryDetail =
                            throw SiteException.RateLimited(retryAfterSeconds = 120L, siteId = SiteId.EHENTAI)

                        override suspend fun loadGalleryPage(detail: GalleryDetail, pageIndex: Int, forceReload: Boolean): GalleryPage =
                            throw SiteException.RateLimited(retryAfterSeconds = 120L, siteId = SiteId.EHENTAI)

                        override suspend fun resolvePageImage(pageUrl: String, preferOriginal: Boolean, forceReload: Boolean): GalleryPage =
                            throw SiteException.RateLimited(retryAfterSeconds = 120L, siteId = SiteId.EHENTAI)
                    }
                }
                val storage = StorageRootManager(
                    context = null,
                    settingsDataStore = null,
                    privateRootOverride = tempFolder.newFolder("rl-priv"),
                    sharedRootOverride = null,
                    dispatchers = appDispatchers,
                )
                val localImporter = LocalLibraryImporter(
                    context = null,
                    rootDir = tempFolder.newFolder("rl-local"),
                    dispatchers = appDispatchers,
                    requireAndroidImageDecoder = false,
                )
                val pageRepo = GalleryPageRepository(
                    context = null,
                    imageClient = OkHttpClient(),
                    storageRootManager = storage,
                    localLibraryImporter = localImporter,
                    siteRegistryProvider = { SiteRegistry(listOf(rateLimitedPlugin)) },
                    dispatchers = appDispatchers,
                    cacheRootOverride = tempFolder.newFolder("rl-cache"),
                    requireAndroidImageDecoder = false,
                )
                val now = 10_000L
                val engine = GalleryDownloadEngine(
                    context = null,
                    galleryPageRepository = pageRepo,
                    storageRootManager = storage,
                    downloadStateRepository = DownloadStateRepository(FakeDownloadItemDao()),
                    applicationScope = engineScope,
                    dispatchers = appDispatchers,
                    serviceStarter = {},
                    clockMs = { now },
                )

                val queued = engine.enqueue(item)
                engine.launchNext(engineScope)!!.join()

                val waiting = engine.getTask(queued.id)!!
                assertEquals(DownloadStatus.RETRY_WAIT, waiting.status)
                assertEquals(DownloadErrorCategory.RATE_LIMITED, waiting.errorCategory)
                assertEquals(now + 120_000L, waiting.retryAtEpochMs)

                // Manual pause clears retry timer and moves to PAUSED
                engine.pause(queued.id)
                val paused = engine.getTask(queued.id)!!
                assertEquals(DownloadStatus.PAUSED, paused.status)
                assertNull(paused.retryAtEpochMs)
            } finally {
                engineScope.cancel()
            }
        }

    /** In-memory Room DAO fake for deterministic JVM unit tests. */
    private class FakeDownloadItemDao : DownloadItemDao {
        private val items = MutableStateFlow<Map<String, DownloadItemEntity>>(emptyMap())

        override fun observeAll(): Flow<List<DownloadItemEntity>> =
            items.map { it.values.sortedWith(compareBy<DownloadItemEntity> { e -> e.status.ordinal }.thenByDescending { e -> e.priority }.thenByDescending { e -> e.updatedAt }) }

        override fun observeByStatus(status: DownloadStatus): Flow<List<DownloadItemEntity>> =
            observeAll().map { list -> list.filter { it.status == status } }

        override fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadItemEntity>> =
            observeAll().map { list -> list.filter { it.status in statuses } }

        override fun observeBySite(siteId: String): Flow<List<DownloadItemEntity>> =
            observeAll().map { list -> list.filter { it.siteId == siteId } }

        override fun observeById(id: String): Flow<DownloadItemEntity?> =
            items.map { it[id] }

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
