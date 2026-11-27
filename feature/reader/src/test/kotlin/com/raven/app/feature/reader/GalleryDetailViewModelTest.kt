package com.raven.app.feature.reader

import app.cash.turbine.test
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.reader.OfflineGallerySnapshot
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.SiteRegistry
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `serial work resume follows the newest chapter instead of the chapter on screen`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val workId = "work-7"
        val chapterA = sampleGalleryDetail(
            siteId = SiteId.NHENTAI,
            mediaId = "$workId:c1",
            pageCount = 10,
            kind = MediaKind.SERIAL_COMIC,
        ).let { detail ->
            detail.copy(
                item = detail.item.copy(
                    title = "Sample Gallery $workId · 第 1 话",
                    extra = mapOf("comicId" to workId),
                ),
                chaptersUrl = "https://nhentai.example/g/$workId",
            )
        }

        // Chapter 1 was abandoned halfway; chapter 2 was read afterwards and must win.
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "$workId:c1",
                url = chapterA.item.url,
                title = "第 1 话",
                kind = MediaKind.SERIAL_COMIC,
                currentPage = 6,
                totalPages = 10,
                updatedAt = harness.nowMs - 60_000L,
                seriesId = workId,
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "$workId:c2",
                url = "https://nhentai.example/g/$workId/c2",
                title = "第 2 话",
                kind = MediaKind.SERIAL_COMIC,
                currentPage = 3,
                totalPages = 8,
                updatedAt = harness.nowMs,
                seriesId = workId,
            ),
        )

        val plugin = FakeGallerySitePlugin(
            descriptor = sampleSiteDescriptor(SiteId.NHENTAI, displayName = "nHentai"),
            detailHandler = { chapterA },
        )
        val viewModel = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(SiteId.NHENTAI.value, chapterA.item.id, chapterA.item.url),
            siteRegistry = SiteRegistry(listOf(plugin)),
            pageLoader = FakeReaderPageLoader(
                detailLoader = { _, _, _, _ ->
                    OfflineGallerySnapshot(chapterA, emptyList(), emptyList(), "", false)
                },
            ),
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = FakeGalleryDownloadGateway(),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            tagDictionary = TagDictionary(emptyMap()),
            clock = harness.clock,
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("$workId", state.workSeriesId)
        assertEquals("$workId:c2", state.workResumeProgress?.mediaId)
        val target = state.resumeTarget
        assertNotNull(target)
        assertEquals("$workId:c2", target!!.item.id)
        assertTrue(target.isOtherChapter)
        assertEquals(3, target.startPage)
        assertEquals(3, state.resumeStartPage)
        assertEquals("继续阅读 (第 3 页)", state.primaryCtaLabel)
    }

    @Test
    fun `online gallery load surfaces resume CTA, translated tags, bookmarks, and reactive toggles`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val tag = TagRef(slug = "solo-female", label = "solo female", namespace = "female")
        val detail = sampleGalleryDetail(
            siteId = SiteId.NHENTAI,
            mediaId = "nh-42",
            pageCount = 12,
            tags = listOf(tag),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "nh-42",
                url = detail.item.url,
                title = detail.item.title,
                currentPage = 5,
                totalPages = 12,
                updatedAt = harness.nowMs,
            ),
        )
        harness.bookmarkRepository.savePageBookmark(
            PageBookmark(
                id = "bm-1",
                siteId = SiteId.NHENTAI,
                mediaId = "nh-42",
                url = detail.item.url,
                title = detail.item.title,
                page = 7,
                totalPages = 12,
                note = "精彩跨页",
                createdAt = harness.nowMs,
            ),
        )

        val plugin = FakeGallerySitePlugin(
            descriptor = sampleSiteDescriptor(SiteId.NHENTAI, displayName = "nHentai"),
            detailHandler = { detail },
        )
        val registry = SiteRegistry(listOf(plugin))
        val downloadGateway = FakeGalleryDownloadGateway()
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ ->
                OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false)
            },
        )
        val dictionary = TagDictionary(mapOf("solo-female" to "单人女性"))

        val viewModel = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(SiteId.NHENTAI.value, "nh-42", detail.item.url),
            siteRegistry = registry,
            pageLoader = loader,
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = downloadGateway,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            tagDictionary = dictionary,
            clock = harness.clock,
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("nh-42", state.detail?.item?.id)
        assertEquals("继续阅读 (第 5 页)", state.primaryCtaLabel)
        assertEquals(5, state.resumeStartPage)
        assertEquals("单人女性", viewModel.formatTagLabel(tag))
        assertEquals(1, state.pageBookmarks.size)
        assertEquals(7, state.pageBookmarks.first().page)

        // Favorite stays reactive; Watch Later is video-only, so a comic toggle must be a no-op.
        viewModel.toggleWatchLater()
        viewModel.toggleFavorite()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isInWatchLater)
        assertTrue(viewModel.uiState.value.isFavorite)

        // Tag long-press actions: favorite & block
        viewModel.openTagActions(tag)
        viewModel.toggleTagFavorite(tag)
        viewModel.toggleTagBlocked(tag)
        advanceUntilIdle()
        val actionState = viewModel.uiState.value.tagAction
        assertNotNull(actionState)
        assertTrue(actionState!!.isFavorited)
        assertTrue(actionState.isBlocked)

        // Subscription remains reactive inside the same long-press sheet, including label-only tags.
        viewModel.toggleTagSubscription(tag)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.tagAction!!.isSubscribed)
        assertTrue("solo-female" in viewModel.uiState.value.subscribedTagSlugs)
        assertTrue(
            harness.tagSubscriptionDao.getAll().any {
                it.siteId == SiteId.NHENTAI.value && it.slug == "solo-female"
            },
        )
        viewModel.toggleTagSubscription(tag)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.tagAction!!.isSubscribed)

        val labelOnlyTag = TagRef(slug = "", label = "label-only")
        viewModel.openTagActions(labelOnlyTag)
        viewModel.toggleTagSubscription(labelOnlyTag)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.tagAction!!.isSubscribed)
        assertTrue(harness.tagSubscriptionDao.getAll().any { it.slug == "label-only" })

        // Author long-press owns a separate sheet and never stacks over the tag sheet.
        val author = AuthorRef(id = "author-1", name = "Alice")
        viewModel.openAuthorActions(author)
        assertEquals(author, viewModel.uiState.value.authorAction)
        assertNull(viewModel.uiState.value.tagAction)
        viewModel.dismissAuthorActions()
        assertNull(viewModel.uiState.value.authorAction)

        // Bookmark deletion propagates reactively
        viewModel.deleteBookmark("bm-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pageBookmarks.isEmpty())
    }

    @Test
    fun `local book detail loads directly via LocalLibraryImporter and GalleryPageRepository`() = runTest(mainDispatcherRule.dispatcher) {
        val root = tempFolder.newFolder()
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, root)

        // Import a real 3-page local directory into LocalLibraryImporter
        val srcDir = File(root, "source-comic").apply { mkdirs() }
        File(srcDir, "page1.png").writeBytes(validTestPngBytes("1"))
        File(srcDir, "page2.png").writeBytes(validTestPngBytes("2"))
        File(srcDir, "page10.png").writeBytes(validTestPngBytes("10"))
        val importedBook = harness.localLibraryImporter.importLocalDirectory(srcDir, title = "本地测试漫")

        val storageRootManager = StorageRootManager(
            context = null,
            settingsDataStore = harness.settingsDataStore,
            dispatchers = harness.dispatchers,
            privateRootOverride = File(root, "private-storage").apply { mkdirs() },
        )
        val registry = SiteRegistry(emptyList())
        val realPageRepo = GalleryPageRepository(
            context = null,
            imageClient = OkHttpClient(),
            storageRootManager = storageRootManager,
            localLibraryImporter = harness.localLibraryImporter,
            siteRegistryProvider = { registry },
            dispatchers = harness.dispatchers,
            cacheRootOverride = File(root, "reading-cache").apply { mkdirs() },
            requireAndroidImageDecoder = false,
        )

        val localUri = LocalLibraryImporter.buildLocalUri(importedBook.id)
        val viewModel = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(SiteId.LOCAL.value, importedBook.id, localUri),
            siteRegistry = registry,
            pageLoader = RepositoryReaderPageLoader(realPageRepo),
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = FakeGalleryDownloadGateway(),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            clock = harness.clock,
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.isLocal)
        assertTrue(state.canOpenOffline)
        assertTrue(state.isOfflineComplete)
        assertEquals("本地测试漫", state.detail?.item?.title)
        assertEquals(3, state.detail?.pageCount)
        assertNotNull(state.localSizeLabel)
    }

    @Test
    fun `selective page download validates custom ranges and enqueues full or partial selection`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.HITOMI, mediaId = "ht-9", pageCount = 10)
        val registry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.HITOMI), detailHandler = { detail })),
        )
        val downloadGateway = FakeGalleryDownloadGateway()
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
        )

        val viewModel = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(SiteId.HITOMI.value, "ht-9", detail.item.url),
            siteRegistry = registry,
            pageLoader = loader,
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = downloadGateway,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            clock = harness.clock,
        )
        advanceUntilIdle()

        // Open sheet & enter invalid range -> validation error blocks submit
        viewModel.openDownloadSheet()
        viewModel.updateDownloadRangeInput("1, 15-20")
        assertNotNull(viewModel.uiState.value.downloadSheet.validationError)
        viewModel.confirmDownload()
        advanceUntilIdle()
        assertTrue(downloadGateway.calls.isEmpty())

        // Valid selective range "1, 3, 5-8" -> parsed [1, 3, 5, 6, 7, 8]
        viewModel.updateDownloadRangeInput("1, 3, 5-8")
        assertNull(viewModel.uiState.value.downloadSheet.validationError)
        assertEquals(listOf(1, 3, 5, 6, 7, 8), viewModel.uiState.value.downloadSheet.parsedPages)
        viewModel.confirmDownload()
        advanceUntilIdle()
        assertEquals(1, downloadGateway.calls.size)
        assertEquals(listOf(1, 3, 5, 6, 7, 8), downloadGateway.calls.single().selectedPages)
        assertFalse(viewModel.uiState.value.downloadSheet.visible)

        // Whole book download -> selectedPages = null
        viewModel.openDownloadSheet()
        viewModel.setDownloadAll(true)
        viewModel.confirmDownload()
        advanceUntilIdle()
        assertEquals(2, downloadGateway.calls.size)
        assertNull(downloadGateway.calls.last().selectedPages)
    }

    @Test
    fun `embedded videos section preserves Cosplaytele Cossora HLS embeds separately from gallery pages`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val embeddedVideo = MediaItem(
            siteId = SiteId.COSPLAYTELE,
            id = "cossora-vid-1",
            url = "https://cosplaytele.example/video/cossora-1",
            kind = MediaKind.VIDEO,
            title = "Cosplaytele Bonus HLS Clip",
            durationSeconds = 95,
        )
        val detail = sampleGalleryDetail(
            siteId = SiteId.COSPLAYTELE,
            mediaId = "ct-88",
            pageCount = 15,
            kind = MediaKind.PHOTO,
            embeddedVideos = listOf(embeddedVideo),
        )
        val registry = SiteRegistry(
            listOf(
                FakeGallerySitePlugin(
                    sampleSiteDescriptor(
                        SiteId.COSPLAYTELE,
                        primaryKind = MediaKind.PHOTO,
                        capabilities = setOf(SiteCapability.DUAL_MEDIA, SiteCapability.TAGS),
                    ),
                    detailHandler = { detail },
                ),
            ),
        )
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
        )

        val viewModel = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(SiteId.COSPLAYTELE.value, "ct-88", detail.item.url),
            siteRegistry = registry,
            pageLoader = loader,
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = FakeGalleryDownloadGateway(),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            clock = harness.clock,
        )
        advanceUntilIdle()

        val loaded = viewModel.uiState.value.detail
        assertNotNull(loaded)
        assertEquals(15, loaded!!.pageCount)
        assertEquals(1, loaded.embeddedVideos.size)
        assertEquals("cossora-vid-1", loaded.embeddedVideos.single().id)
        assertEquals(MediaKind.VIDEO, loaded.embeddedVideos.single().kind)

        // Pure-video Cosplaytele post (0 image pages) must block opening the page download sheet cleanly.
        val zeroPageDetail = detail.copy(
            item = detail.item.copy(id = "ct-video-only", pageCount = 0),
            pageCount = 0,
            pages = emptyList(),
        )
        val zeroLoader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(zeroPageDetail, emptyList(), emptyList(), "", false) },
        )
        val zeroVm = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(SiteId.COSPLAYTELE.value, "ct-video-only", zeroPageDetail.item.url),
            siteRegistry = registry,
            pageLoader = zeroLoader,
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = FakeGalleryDownloadGateway(),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            clock = harness.clock,
        )
        advanceUntilIdle()
        zeroVm.openDownloadSheet()
        assertFalse(zeroVm.uiState.value.downloadSheet.visible)
    }

    @Test
    fun `exhentai auth gate triggers on SiteException AuthRequired and recovers after sign-in`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.EXHENTAI, mediaId = "ex-77", pageCount = 8)
        val plugin = FakeGallerySitePlugin(
            descriptor = sampleSiteDescriptor(
                SiteId.EXHENTAI,
                displayName = "ExHentai",
                capabilities = setOf(SiteCapability.AUTH, SiteCapability.ORIGINAL_IMAGES),
                requiresAuthGate = true,
            ),
            detailHandler = { detail },
            requiresAuthGateFlag = true,
            isAuthenticated = false,
        )
        var throwAuthOnLoad = true
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ ->
                if (throwAuthOnLoad) {
                    throw SiteException.AuthRequired("ExHentai 需要有效 Cookie", siteId = SiteId.EXHENTAI)
                }
                OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false)
            },
        )
        val registry = SiteRegistry(listOf(plugin))

        val viewModel = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(SiteId.EXHENTAI.value, "ex-77", detail.item.url),
            siteRegistry = registry,
            pageLoader = loader,
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = FakeGalleryDownloadGateway(),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            clock = harness.clock,
        )
        advanceUntilIdle()

        // Gate active while unauthenticated
        assertTrue(viewModel.uiState.value.authRequired)
        assertNull(viewModel.uiState.value.detail)

        // Even when authState flips true, SiteException.AuthRequired still triggers auth gate cleanly
        plugin.isAuthenticated = true
        viewModel.loadDetail(forceRefresh = true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.authRequired)
        assertEquals("ExHentai 需要有效 Cookie", viewModel.uiState.value.authMessage)

        // Once session succeeds, retry loads detail cleanly
        throwAuthOnLoad = false
        viewModel.loadDetail(forceRefresh = true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.authRequired)
        assertEquals("ex-77", viewModel.uiState.value.detail?.item?.id)
    }

    @Test
    fun `exportCbz builds valid archive and emits RequestSaveDocument event`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.NHENTAI, mediaId = "nh-cbz", pageCount = 3)
        val registry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.NHENTAI), detailHandler = { detail })),
        )
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
        )
        val viewModel = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(SiteId.NHENTAI.value, "nh-cbz", detail.item.url),
            siteRegistry = registry,
            pageLoader = loader,
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = FakeGalleryDownloadGateway(),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            clock = harness.clock,
        )
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.exportCbz(selectedPages = listOf(1, 3))
            advanceUntilIdle()

            val event = awaitItem() as GalleryDetailEvent.RequestSaveDocument
            assertTrue(event.suggestedFileName.endsWith(".cbz"))
            val cbzFile = event.prepared.files.single().file
            ZipFile(cbzFile).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(listOf("000001.png", "000003.png"), names)
            }
            harness.exportGateway.removeStagingFolder(event.prepared.folder)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `caches detail tags saves custom tag translation live and respects blockTagsGlobally`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
            val tag = TagRef(slug = "stockings", label = "stockings", namespace = "female")
            val detail = sampleGalleryDetail(
                siteId = SiteId.NHENTAI,
                mediaId = "nh-tag-edit",
                pageCount = 6,
                tags = listOf(tag),
            ).copy(
                related = listOf(
                    MediaItem(
                        siteId = SiteId.NHENTAI,
                        id = "rel-safe",
                        url = "https://nhentai.net/g/rel-safe/",
                        kind = MediaKind.COMIC,
                        title = "Safe Comic",
                    ),
                    MediaItem(
                        siteId = SiteId.NHENTAI,
                        id = "rel-blocked",
                        url = "https://nhentai.net/g/rel-blocked/",
                        kind = MediaKind.COMIC,
                        title = "Blocked Comic",
                        tags = listOf(tag),
                    ),
                ),
            )
            val registry = SiteRegistry(
                listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.NHENTAI), detailHandler = { detail })),
            )
            val loader = FakeReaderPageLoader(
                detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
            )
            val viewModel = GalleryDetailViewModel(
                route = RavenRoute.GalleryDetail(SiteId.NHENTAI.value, "nh-tag-edit", detail.item.url),
                siteRegistry = registry,
                pageLoader = loader,
                localLibraryImporter = harness.localLibraryImporter,
                downloadGateway = FakeGalleryDownloadGateway(),
                exportGateway = harness.exportGateway,
                historyRepository = harness.historyRepository,
                favoriteRepository = harness.favoriteRepository,
                shelfRepository = harness.shelfRepository,
                bookmarkRepository = harness.bookmarkRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                subscriptionRepository = harness.subscriptionRepository,
                preferencesRepository = harness.preferencesRepository,
                dispatchers = harness.dispatchers,
                tagDictionary = harness.runtimeDictionary,
                clock = harness.clock,
                mediaTagCacheRepository = harness.mediaTagCacheRepository,
                tagDictionaryRepository = harness.tagDictionaryRepository,
            )

            advanceUntilIdle()

            // Detail tags persisted into MediaTagCacheRepository
            val cached = harness.mediaTagCacheRepository.getTags(SiteId.NHENTAI, "nh-tag-edit")
            assertEquals(listOf("stockings"), cached.map { it.slug })

            // Edit Chinese translation via long-press -> updates runtime dictionary & UI immediately
            viewModel.openTagActions(tag)
            viewModel.saveCustomTagTranslation(tag, "黑丝长袜")
            advanceUntilIdle()
            assertEquals("黑丝长袜", viewModel.formatTagLabel(tag))
            assertEquals("黑丝长袜", viewModel.uiState.value.tagAction?.translatedLabel)
            assertTrue(viewModel.uiState.value.tagDictionaryRevision > 0L)

            // Default blockTagsGlobally = true -> blocking writes to SiteId.GLOBAL & filters related galleries
            viewModel.toggleTagBlocked(tag)
            advanceUntilIdle()
            assertTrue(viewModel.isTagBlocked(tag))
            assertTrue(harness.blockedTermDao.getAll().any { it.siteId == SiteId.GLOBAL.value && it.term == "stockings" })
            assertEquals(listOf("rel-safe"), viewModel.uiState.value.filteredRelated.map { it.id })
        }

    @Test
    fun `invalid siteId guard and custom shelf picker membership work cleanly`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val invalidVm = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail("bad site!", "g-0", "https://example.com/g-0"),
            siteRegistry = SiteRegistry(emptyList()),
            pageLoader = FakeReaderPageLoader(detailLoader = { _, _, _, _ -> error("should not load") }),
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = FakeGalleryDownloadGateway(),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            tagDictionary = harness.runtimeDictionary,
            clock = harness.clock,
        )
        advanceUntilIdle()
        assertFalse(invalidVm.uiState.value.isLoading)
        assertNotNull(invalidVm.uiState.value.errorMessage)

        val detail = sampleGalleryDetail(SiteId.NHENTAI, mediaId = "nh-shelf", pageCount = 8)
        val registry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.NHENTAI), detailHandler = { detail })),
        )
        val vm = GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(SiteId.NHENTAI.value, "nh-shelf", detail.item.url),
            siteRegistry = registry,
            pageLoader = FakeReaderPageLoader(
                detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
            ),
            localLibraryImporter = harness.localLibraryImporter,
            downloadGateway = FakeGalleryDownloadGateway(),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            bookmarkRepository = harness.bookmarkRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            subscriptionRepository = harness.subscriptionRepository,
            preferencesRepository = harness.preferencesRepository,
            dispatchers = harness.dispatchers,
            tagDictionary = harness.runtimeDictionary,
            clock = harness.clock,
        )
        advanceUntilIdle()

        vm.openShelfPicker()
        assertTrue(vm.uiState.value.showShelfPicker)
        vm.createShelfAndAdd("本子精选")
        advanceUntilIdle()

        val createdShelf = vm.uiState.value.shelves.firstOrNull { it.shelf.name == "本子精选" }
        assertNotNull(createdShelf)
        assertTrue(createdShelf!!.shelf.id in vm.uiState.value.containingShelfIds)

        vm.setShelfMembership(createdShelf.shelf.id, add = false)
        advanceUntilIdle()
        assertFalse(createdShelf.shelf.id in vm.uiState.value.containingShelfIds)
        vm.dismissShelfPicker()
        assertFalse(vm.uiState.value.showShelfPicker)
    }
}
