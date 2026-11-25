package com.raven.app.feature.reader

import app.cash.turbine.test
import com.raven.app.core.media.reader.OfflineGallerySnapshot
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderPreferences
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `switching across all 4 modes preserves current page, strip offset, and per-page aspect ratios`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.NHENTAI, mediaId = "comic-1", pageCount = 6)
        val registry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.NHENTAI), detailHandler = { detail })),
        )
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
            pageHandler = { d, pageIdx, _, _ ->
                // Distinct deterministic aspect ratio per page: w = 600 + 100*pageIdx, h = 1000
                LoadedReaderPage(
                    page = d.pages.first { it.index == pageIdx },
                    bitmap = null,
                    pixelWidth = 600 + pageIdx * 100,
                    pixelHeight = 1000,
                    byteSize = 512L * 1024L,
                    fromCache = false,
                )
            },
        )

        val viewModel = ReaderViewModel(
            route = RavenRoute.Reader(SiteId.NHENTAI.value, "comic-1", detail.item.url, startPage = 1),
            siteRegistry = registry,
            pageLoader = loader,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = backgroundScope,
            clock = harness.clock,
        )
        advanceUntilIdle()

        // Scroll in STRIP mode to page 3 with pixel offset 240
        viewModel.onVisiblePagesChanged(anchorPage = 3, visiblePages = setOf(3, 4), scrollOffsetPx = 240)
        advanceUntilIdle()

        val ratioPage3 = viewModel.uiState.value.pageAspectRatios[3]
        assertNotNull(ratioPage3)
        assertEquals(0.9f, ratioPage3!!, 0.001f)
        assertEquals(3, viewModel.uiState.value.currentPage)
        assertEquals(240, viewModel.uiState.value.stripScrollOffsetPx)

        // Switch STRIP -> LTR -> RTL -> SPREAD (landscape & portrait) -> STRIP
        viewModel.setReadingMode(ReaderMode.LTR)
        advanceUntilIdle()
        assertEquals(ReaderMode.LTR, viewModel.uiState.value.preferences.mode)
        assertEquals(3, viewModel.uiState.value.currentPage)
        assertEquals(0.9f, viewModel.uiState.value.pageAspectRatios[3]!!, 0.001f)

        viewModel.setReadingMode(ReaderMode.RTL)
        advanceUntilIdle()
        assertEquals(ReaderMode.RTL, viewModel.uiState.value.preferences.mode)
        assertEquals(3, viewModel.uiState.value.currentPage)

        // Landscape SPREAD with combineCover=false pairs [1], [2,3], [4,5], [6]
        viewModel.onViewportOrientationChanged(isLandscape = true)
        viewModel.setReadingMode(ReaderMode.SPREAD)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.effectiveSpread)
        assertEquals(listOf(2, 3), viewModel.uiState.value.currentSheetPages)

        // Toggle combineCover=true -> [1,2], [3,4], [5,6]
        viewModel.setCombineCover(true)
        advanceUntilIdle()
        assertEquals(listOf(3, 4), viewModel.uiState.value.currentSheetPages)

        // Portrait fallback in SPREAD mode -> effectiveSpread == 1, page 3 preserved
        viewModel.onViewportOrientationChanged(isLandscape = false)
        assertEquals(1, viewModel.uiState.value.effectiveSpread)
        assertEquals(listOf(3), viewModel.uiState.value.currentSheetPages)

        // Return to STRIP -> preserves page 3, restores stripScrollOffsetPx, and retains all ratios
        viewModel.setReadingMode(ReaderMode.STRIP)
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.currentPage)
        assertEquals(240, viewModel.uiState.value.pendingStripRestoreOffsetPx)
        assertEquals(0.9f, viewModel.uiState.value.pageAspectRatios[3]!!, 0.001f)
    }

    @Test
    fun `per-book override isolates edits from site default and reset restores site default cleanly`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.EXHENTAI, mediaId = "ex-book", pageCount = 5)
        val registry = SiteRegistry(
            listOf(
                FakeGallerySitePlugin(
                    sampleSiteDescriptor(
                        SiteId.EXHENTAI,
                        displayName = "ExHentai",
                        capabilities = setOf(SiteCapability.ORIGINAL_IMAGES, SiteCapability.AUTH),
                    ),
                    detailHandler = { detail },
                ),
            ),
        )
        // Pre-seed site default for ExHentai: RTL, crop 5%
        harness.preferencesRepository.saveReaderPreferences(
            ReaderPreferences(
                siteId = SiteId.EXHENTAI,
                mediaId = null,
                mode = ReaderMode.RTL,
                fit = ReaderFit.HEIGHT,
                cropPercent = 5,
            ),
        )
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
        )

        val viewModel = ReaderViewModel(
            route = RavenRoute.Reader(SiteId.EXHENTAI.value, "ex-book", detail.item.url),
            siteRegistry = registry,
            pageLoader = loader,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = backgroundScope,
            clock = harness.clock,
        )
        advanceUntilIdle()

        // Inherits site default and exposes capability-driven original-images flag
        assertTrue(viewModel.uiState.value.supportsOriginalImages)
        assertFalse(viewModel.uiState.value.isPerBookOverride)
        assertEquals(ReaderMode.RTL, viewModel.uiState.value.preferences.mode)
        assertEquals(5, viewModel.uiState.value.preferences.cropPercent)

        // Enable per-book override & mutate mode/crop/preferOriginal
        viewModel.setPerBookOverride(true)
        viewModel.setReadingMode(ReaderMode.LTR)
        viewModel.setCropPercent(12)
        viewModel.setPreferOriginalImages(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPerBookOverride)
        assertEquals(ReaderMode.LTR, viewModel.uiState.value.preferences.mode)
        assertEquals(12, viewModel.uiState.value.preferences.cropPercent)
        assertTrue(viewModel.uiState.value.preferences.preferOriginalImages)
        // Buffer reloaded with preferOriginal = true
        assertTrue(loader.loadedRequests.any { (_, preferOriginal) -> preferOriginal })

        // Verify site default was untouched!
        val siteDefault = harness.readerPrefsDao.get(SiteId.EXHENTAI.value, "")
        assertNotNull(siteDefault)
        assertEquals(ReaderMode.RTL, siteDefault!!.mode)
        assertEquals(5, siteDefault.cropPercent)

        // Reset to site default -> restores RTL, crop 5%, preferOriginal false
        viewModel.resetToSiteDefault()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isPerBookOverride)
        assertEquals(ReaderMode.RTL, viewModel.uiState.value.preferences.mode)
        assertEquals(5, viewModel.uiState.value.preferences.cropPercent)
        assertFalse(viewModel.uiState.value.preferences.preferOriginalImages)
        assertNull(harness.readerPrefsDao.get(SiteId.EXHENTAI.value, "ex-book"))
    }

    @Test
    fun `volume key paging handles disabled, normal, reversed, and long-press first-last jumps`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.NHENTAI, mediaId = "vol-1", pageCount = 5)
        val registry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.NHENTAI), detailHandler = { detail })),
        )
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
        )

        val viewModel = ReaderViewModel(
            route = RavenRoute.Reader(SiteId.NHENTAI.value, "vol-1", detail.item.url, startPage = 2),
            siteRegistry = registry,
            pageLoader = loader,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = backgroundScope,
            clock = harness.clock,
        )
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.currentPage)

        // Disabled by default -> returns false and does not move
        assertFalse(viewModel.handleVolumeKey(up = false, isLongPress = false))
        assertEquals(2, viewModel.uiState.value.currentPage)

        // Enable normal volume paging: Down -> next (3), Up -> prev (2), Long Down -> last (5), Long Up -> first (1)
        viewModel.setVolumeKeyPaging(true)
        advanceUntilIdle()
        assertTrue(viewModel.handleVolumeKey(up = false, isLongPress = false))
        assertEquals(3, viewModel.uiState.value.currentPage)
        assertTrue(viewModel.handleVolumeKey(up = true, isLongPress = false))
        assertEquals(2, viewModel.uiState.value.currentPage)
        assertTrue(viewModel.handleVolumeKey(up = false, isLongPress = true))
        assertEquals(5, viewModel.uiState.value.currentPage)
        assertTrue(viewModel.handleVolumeKey(up = true, isLongPress = true))
        assertEquals(1, viewModel.uiState.value.currentPage)

        // Reverse volume keys: Up -> next (2), Long Up -> last (5), Down -> prev (4), Long Down -> first (1)
        viewModel.setReverseVolumeKeys(true)
        advanceUntilIdle()
        assertTrue(viewModel.handleVolumeKey(up = true, isLongPress = false))
        assertEquals(2, viewModel.uiState.value.currentPage)
        assertTrue(viewModel.handleVolumeKey(up = true, isLongPress = true))
        assertEquals(5, viewModel.uiState.value.currentPage)
        assertTrue(viewModel.handleVolumeKey(up = false, isLongPress = false))
        assertEquals(4, viewModel.uiState.value.currentPage)
        assertTrue(viewModel.handleVolumeKey(up = false, isLongPress = true))
        assertEquals(1, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `page bookmark and image favorite toggles update state reactively`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.JMCOMIC, mediaId = "jm-5", pageCount = 4)
        val registry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.JMCOMIC), detailHandler = { detail })),
        )
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
        )

        val viewModel = ReaderViewModel(
            route = RavenRoute.Reader(SiteId.JMCOMIC.value, "jm-5", detail.item.url, startPage = 2),
            siteRegistry = registry,
            pageLoader = loader,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = backgroundScope,
            clock = harness.clock,
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCurrentPageBookmarked)
        assertFalse(viewModel.uiState.value.isCurrentPageImageFavorited)

        // Add bookmark & image favorite on page 2
        viewModel.toggleCurrentPageBookmark(note = "名场面")
        viewModel.toggleCurrentPageImageFavorite()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isCurrentPageBookmarked)
        assertTrue(viewModel.uiState.value.isCurrentPageImageFavorited)
        assertEquals(1, viewModel.uiState.value.pageBookmarks.size)
        assertEquals(2, viewModel.uiState.value.pageBookmarks.single().page)

        // Jump to page 3 -> current page flags are false, page 2 still persisted
        viewModel.jumpToPage(3)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isCurrentPageBookmarked)
        assertFalse(viewModel.uiState.value.isCurrentPageImageFavorited)

        // Return to page 2 & toggle both off
        viewModel.jumpToPage(2)
        advanceUntilIdle()
        viewModel.toggleCurrentPageBookmark()
        viewModel.toggleCurrentPageImageFavorite()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isCurrentPageBookmarked)
        assertFalse(viewModel.uiState.value.isCurrentPageImageFavorited)
    }

    @Test
    fun `reading progress saves with 750ms debounce and flushes immediately on dispose`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.WNACG, mediaId = "wn-99", pageCount = 10)
        val registry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.WNACG), detailHandler = { detail })),
        )
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
        )

        val viewModel = ReaderViewModel(
            route = RavenRoute.Reader(SiteId.WNACG.value, "wn-99", detail.item.url, startPage = 1),
            siteRegistry = registry,
            pageLoader = loader,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = backgroundScope,
            clock = harness.clock,
        )
        // Complete initial load & initial debounced save
        advanceUntilIdle()
        val initialUpserts = harness.readingDao.upsertCount
        assertEquals(1, harness.historyRepository.getReadingProgress(SiteId.WNACG, "wn-99")?.currentPage)

        // Rapid page flips within 750ms coalesce into zero writes at 500ms
        viewModel.jumpToPage(2)
        advanceTimeBy(300L)
        viewModel.jumpToPage(3)
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(initialUpserts, harness.readingDao.upsertCount)

        // After 750ms from last flip (350ms more), single coalesced write occurs for page 3
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(initialUpserts + 1, harness.readingDao.upsertCount)
        assertEquals(3, harness.historyRepository.getReadingProgress(SiteId.WNACG, "wn-99")?.currentPage)

        // Immediate flush persists page 6 without waiting for 750ms debounce
        viewModel.jumpToPage(6)
        runCurrent()
        assertEquals(3, harness.historyRepository.getReadingProgress(SiteId.WNACG, "wn-99")?.currentPage)
        viewModel.flushProgress()
        runCurrent()
        assertEquals(6, harness.historyRepository.getReadingProgress(SiteId.WNACG, "wn-99")?.currentPage)
    }

    @Test
    fun `auto-page waits for current page render ready before advancing`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.NHENTAI, mediaId = "auto-1", pageCount = 4)
        val registry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.NHENTAI), detailHandler = { detail })),
        )
        val page1Gate = CompletableDeferred<Unit>()
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
            pageHandler = { d, idx, _, _ ->
                if (idx == 1) page1Gate.await()
                LoadedReaderPage(
                    page = d.pages.first { it.index == idx },
                    bitmap = null,
                    pixelWidth = 800,
                    pixelHeight = 1200,
                    byteSize = 256L * 1024L,
                    fromCache = false,
                )
            },
        )

        val viewModel = ReaderViewModel(
            route = RavenRoute.Reader(SiteId.NHENTAI.value, "auto-1", detail.item.url, startPage = 1),
            siteRegistry = registry,
            pageLoader = loader,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = backgroundScope,
            random = Random(42),
            clock = harness.clock,
        )
        runCurrent()

        // Enable 3s auto-page while page 1 is still NOT render ready
        viewModel.setAutoPageInterval(3)
        advanceTimeBy(10_000L)
        runCurrent()
        assertFalse(viewModel.uiState.value.isCurrentPageRenderReady)
        assertEquals(1, viewModel.uiState.value.currentPage)

        // Once page 1 becomes render ready, timer counts 3s and advances to page 2
        page1Gate.complete(Unit)
        runCurrent()
        assertTrue(viewModel.uiState.value.isCurrentPageRenderReady)
        advanceTimeBy(3_100L)
        runCurrent()
        assertEquals(2, viewModel.uiState.value.currentPage)

        // Stop auto-page cleanly
        viewModel.setAutoPageInterval(0)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(2, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `sequential queue prompts at final page, advances cleanly, and supports rollback`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail1 = sampleGalleryDetail(SiteId.NHENTAI, mediaId = "q-1", pageCount = 2)
        val item2 = MediaItem(
            siteId = SiteId.NHENTAI,
            id = "q-2",
            url = "https://nhentai.example/g/q-2",
            kind = MediaKind.COMIC,
            title = "Next Book In Queue",
            pageCount = 10,
        )
        harness.shelfRepository.ensureWatchLaterShelf()
        harness.shelfRepository.addItem(
            Shelf.WATCH_LATER_ID,
            ShelfItem(
                shelfId = Shelf.WATCH_LATER_ID,
                siteId = SiteId.NHENTAI,
                mediaId = "q-1",
                url = detail1.item.url,
                kind = MediaKind.COMIC,
                title = detail1.item.title,
            ),
        )
        val session = harness.playbackQueueStore.start(
            title = "稍后看",
            shelfId = Shelf.WATCH_LATER_ID,
            items = listOf(detail1.item, item2),
        )

        val registry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.NHENTAI), detailHandler = { detail1 })),
        )
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail1, emptyList(), emptyList(), "", false) },
        )

        val viewModel = ReaderViewModel(
            route = RavenRoute.Reader(
                siteId = SiteId.NHENTAI.value,
                mediaId = "q-1",
                url = detail1.item.url,
                startPage = 1,
                queueToken = session.token,
            ),
            siteRegistry = registry,
            pageLoader = loader,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = backgroundScope,
            clock = harness.clock,
        )
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showQueueNextCard)

        // Reaching page 2 triggers queue confirmation card & marks shelf item completed
        viewModel.onVisiblePagesChanged(anchorPage = 2, visiblePages = setOf(2))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showQueueNextCard)
        val shelfItem = harness.shelfItemDao.getOne(Shelf.WATCH_LATER_ID, SiteId.NHENTAI.value, "q-1")
        assertTrue(shelfItem?.completed == true)

        // Advancing emits OpenNextQueueItem and updates PlaybackQueueStore
        viewModel.events.test {
            assertTrue(viewModel.advanceQueue())
            val event = awaitItem() as ReaderEvent.OpenNextQueueItem
            assertEquals("q-2", event.nextItem.id)
            assertEquals("q-2", harness.playbackQueueStore.snapshot()?.currentItem?.id)

            // Rollback restores previous head cleanly if UI navigation fails
            viewModel.rollbackQueueAdvance(event.previous, event.replacement)
            assertEquals("q-1", harness.playbackQueueStore.snapshot()?.currentItem?.id)
            assertTrue(viewModel.uiState.value.showQueueNextCard)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invalid siteId, incognito skip, settings sync and auto-page lifecycle pause work cleanly`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val registry = SiteRegistry(emptyList())

        val invalidVm = ReaderViewModel(
            route = RavenRoute.Reader("invalid site!", "id-1", "https://example.com/1"),
            siteRegistry = registry,
            pageLoader = FakeReaderPageLoader(
                detailLoader = { _, _, _, _ -> error("should not load") },
            ),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = backgroundScope,
            clock = harness.clock,
        )
        advanceUntilIdle()
        assertFalse(invalidVm.uiState.value.isLoading)
        assertNotNull(invalidVm.uiState.value.errorMessage)

        val detail = sampleGalleryDetail(SiteId.NHENTAI, mediaId = "incog-1", pageCount = 4)
        harness.preferencesRepository.setIncognitoMode(true)
        val validRegistry = SiteRegistry(
            listOf(FakeGallerySitePlugin(sampleSiteDescriptor(SiteId.NHENTAI), detailHandler = { detail })),
        )
        val validVm = ReaderViewModel(
            route = RavenRoute.Reader(SiteId.NHENTAI.value, "incog-1", detail.item.url),
            siteRegistry = validRegistry,
            pageLoader = FakeReaderPageLoader(
                detailLoader = { _, _, _, _ -> OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false) },
            ),
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = backgroundScope,
            clock = harness.clock,
        )
        advanceUntilIdle()

        validVm.onVisiblePagesChanged(anchorPage = 3, visiblePages = setOf(3))
        validVm.flushProgressNow()
        advanceUntilIdle()
        assertNull(harness.historyRepository.getReadingProgress(SiteId.NHENTAI, "incog-1"))

        validVm.setShowStatusBar(true)
        validVm.setTapZonesEnabled(false)
        validVm.setAutoSplitWidePages(true)
        advanceUntilIdle()
        val settings = harness.preferencesRepository.currentSettings()
        assertTrue(settings.readerShowStatusBar)
        assertFalse(settings.readerTapZonesEnabled)
        assertTrue(settings.readerAutoSplitWidePages)

        // Ensure lifecycle pause suspends auto-page without resetting the user interval
        validVm.onVisiblePagesChanged(anchorPage = 1, visiblePages = setOf(1))
        validVm.setAutoPageInterval(3)
        validVm.setAutoPagePausedByLifecycle(true)
        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(3, validVm.uiState.value.autoPageIntervalSeconds)
        assertEquals(1, validVm.uiState.value.currentPage)
        validVm.setAutoPagePausedByLifecycle(false)
        validVm.setAutoPageInterval(0)
    }
}
