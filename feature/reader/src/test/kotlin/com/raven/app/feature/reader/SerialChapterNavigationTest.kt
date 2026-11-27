package com.raven.app.feature.reader

import app.cash.turbine.test
import com.raven.app.core.media.reader.ExportMode
import com.raven.app.core.media.reader.OfflineGallerySnapshot
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SerialChapterNavigationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `final page opens official next chapter only once`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val chapters = chapters()
        val detail = serialDetail(chapters.first(), pageCount = 2).let { current ->
            current.copy(
                item = current.item.copy(
                    id = "parent-comic",
                    extra = mapOf("comicId" to "parent-comic", "chapterUuid" to "chapter-1"),
                ),
            )
        }
        val chapterRequests = mutableListOf<String>()
        val viewModel = reader(
            harness = harness,
            detail = detail,
            persistenceScope = backgroundScope,
            chapterLoader = SerialChapterLoader { _, requestedDetail ->
                chapterRequests += requestedDetail.item.id
                chapters
            },
        )
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onVisiblePagesChanged(anchorPage = 2, visiblePages = setOf(2))
            assertTrue(viewModel.navigateAdjacentPage(forward = true))
            assertTrue(viewModel.navigateAdjacentPage(forward = true))
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is ReaderEvent.OpenChapter)
            assertEquals(chapters[1], (event as ReaderEvent.OpenChapter).nextItem)
            assertFalse(viewModel.uiState.value.isChapterTransitionLoading)
            assertEquals(listOf("parent-comic"), chapterRequests)
            assertTrue(viewModel.navigateAdjacentPage(forward = true))
            advanceUntilIdle()
            expectNoEvents()

            // Returning to the screen (ON_START) unlocks subsequent chapter transitions
            viewModel.onScreenStarted()
            viewModel.openNextChapter()
            advanceUntilIdle()
            val secondEvent = awaitItem()
            assertTrue(secondEvent is ReaderEvent.OpenChapter)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `manual chapter controls expose and open adjacent chapters`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val chapters = chapters()
        val detail = serialDetail(chapters[1], pageCount = 2)
        val viewModel = reader(
            harness = harness,
            detail = detail,
            persistenceScope = backgroundScope,
            chapterLoader = SerialChapterLoader { _, _ -> chapters },
        )
        advanceUntilIdle()

        assertEquals(chapters[0], viewModel.uiState.value.previousChapter)
        assertEquals(chapters[2], viewModel.uiState.value.nextChapter)
        viewModel.events.test {
            viewModel.openPreviousChapter()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is ReaderEvent.OpenChapter)
            assertEquals(chapters[0], (event as ReaderEvent.OpenChapter).nextItem)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `chapter picker exposes all chapters and opens the selected chapter`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val chapters = chapters()
        val viewModel = reader(
            harness = harness,
            detail = serialDetail(chapters[1], pageCount = 2),
            persistenceScope = backgroundScope,
            chapterLoader = SerialChapterLoader { _, _ -> chapters },
        )
        advanceUntilIdle()

        assertEquals(chapters, viewModel.uiState.value.chapters)
        viewModel.openSheet(ReaderSheet.CHAPTERS)
        viewModel.openChapter(chapters[1])
        advanceUntilIdle()
        assertEquals(ReaderSheet.NONE, viewModel.uiState.value.activeSheet)

        viewModel.events.test {
            viewModel.openSheet(ReaderSheet.CHAPTERS)
            viewModel.openChapter(chapters[2])
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is ReaderEvent.OpenChapter)
            assertEquals(chapters[2], (event as ReaderEvent.OpenChapter).nextItem)
            assertEquals(ReaderSheet.NONE, viewModel.uiState.value.activeSheet)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed chapter list can be retried from the picker`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val chapters = chapters()
        var attempts = 0
        val viewModel = reader(
            harness = harness,
            detail = serialDetail(chapters.first(), pageCount = 2),
            persistenceScope = backgroundScope,
            chapterLoader = SerialChapterLoader { _, _ ->
                if (attempts++ == 0) error("网络错误") else chapters
            },
        )
        advanceUntilIdle()

        assertEquals("网络错误", viewModel.uiState.value.chapterListError)
        assertFalse(viewModel.uiState.value.isChapterListLoading)

        viewModel.refreshChapters()
        advanceUntilIdle()

        assertEquals(chapters, viewModel.uiState.value.chapters)
        assertTrue(viewModel.uiState.value.isChapterListReady)
        assertNull(viewModel.uiState.value.chapterListError)
        assertEquals(2, attempts)
    }

    @Test
    fun `strip end seamlessly appends next chapter without page reload`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val chapters = chapters()
        val detail = serialDetail(chapters.first(), pageCount = 2)
        val nextDetail = serialDetail(chapters[1], pageCount = 3)
        val viewModel = reader(
            harness = harness,
            detail = detail,
            persistenceScope = backgroundScope,
            chapterLoader = SerialChapterLoader { _, _ -> chapters },
            extraDetails = mapOf(chapters[1].id to nextDetail),
        )
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onVisiblePagesChanged(anchorPage = 1, visiblePages = setOf(1, 2))
            viewModel.onStripEndReached()
            advanceUntilIdle()

            expectNoEvents()
            assertEquals(listOf(chapters[1].id), viewModel.uiState.value.appendedChapters.map { it.mediaId })
            assertEquals(listOf(1, 2, 3), viewModel.uiState.value.appendedChapters.first().pageNumbers)
            assertFalse(viewModel.uiState.value.isAppendingNextChapter)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `last chapter reports boundary without navigation`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val chapters = chapters()
        val detail = serialDetail(chapters.last(), pageCount = 1)
        val viewModel = reader(
            harness = harness,
            detail = detail,
            persistenceScope = backgroundScope,
            chapterLoader = SerialChapterLoader { _, _ -> chapters },
        )
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onVisiblePagesChanged(anchorPage = 1, visiblePages = setOf(1))
            assertTrue(viewModel.navigateAdjacentPage(forward = true))
            advanceUntilIdle()

            expectNoEvents()
            assertEquals("已经是最后一话", viewModel.uiState.value.snackbarMessage)
            assertFalse(viewModel.uiState.value.isChapterTransitionLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ordinary gallery keeps existing final-page behavior`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val detail = sampleGalleryDetail(SiteId.NHENTAI, mediaId = "single-book", pageCount = 2)
        var chapterLoaderCalled = false
        val viewModel = reader(
            harness = harness,
            detail = detail,
            persistenceScope = backgroundScope,
            chapterLoader = SerialChapterLoader { _, _ ->
                chapterLoaderCalled = true
                emptyList()
            },
        )
        advanceUntilIdle()

        viewModel.onVisiblePagesChanged(anchorPage = 2, visiblePages = setOf(2))
        assertFalse(viewModel.navigateAdjacentPage(forward = true))
        advanceUntilIdle()
        assertFalse(chapterLoaderCalled)
        assertNull(viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `production loader requests parent comic chapters and preserves source order`() = runTest(mainDispatcherRule.dispatcher) {
        val chapters = chapters()
        var receivedRequest: FeedRequest? = null
        val siteDescriptor = sampleSiteDescriptor(SiteId.MANGACOPY, primaryKind = MediaKind.SERIAL_COMIC)
        val plugin = object : SitePlugin {
            override val descriptor = siteDescriptor
            override val feedSource = object : FeedSource {
                override suspend fun loadHome(language: ComicLanguage): HomeFeed = HomeFeed(siteId = siteDescriptor.id)

                override suspend fun loadFeed(request: FeedRequest): FeedPage {
                    receivedRequest = request
                    return FeedPage(items = chapters)
                }
            }
        }
        val detail = serialDetail(chapters.first(), pageCount = 2).let { current ->
            current.copy(item = current.item.copy(extra = mapOf("comicId" to "parent-comic")))
        }
        val loader = SiteRegistrySerialChapterLoader(SiteRegistry(listOf(plugin)))

        val loaded = loader.loadChapters(SiteId.MANGACOPY, detail)

        assertEquals(chapters, loaded)
        assertEquals("chapters", receivedRequest?.section)
        assertEquals("parent-comic", receivedRequest?.category)
        assertEquals(1, receivedRequest?.page)
    }

    @Test
    fun `scrolling into appended chapter targets its own mediaId for bookmark and image favorite`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
            val chapters = chapters()
            val viewModel = reader(
                harness = harness,
                detail = serialDetail(chapters.first(), pageCount = 2),
                persistenceScope = backgroundScope,
                chapterLoader = SerialChapterLoader { _, _ -> chapters },
                extraDetails = mapOf(chapters[1].id to serialDetail(chapters[1], pageCount = 3)),
            )
            advanceUntilIdle()
            viewModel.setReadingMode(ReaderMode.STRIP)

            viewModel.onStripEndReached()
            advanceUntilIdle()
            assertEquals(listOf(chapters[1].id), viewModel.uiState.value.appendedChapters.map { it.mediaId })
            viewModel.onStripViewportProgress(
                anchorChapterId = chapters[1].id,
                anchorPage = 2,
                visiblePagesByChapter = mapOf(chapters[1].id to setOf(2)),
                scrollOffsetPx = 320,
                remainingItemsToBottom = 8,
            )
            advanceUntilIdle()

            viewModel.toggleCurrentPageBookmark()
            advanceUntilIdle()
            val bookmarks = harness.bookmarkRepository.observePageBookmarks().first()
            assertEquals(1, bookmarks.size)
            assertEquals(chapters[1].id, bookmarks.single().mediaId)
            assertEquals(2, bookmarks.single().page)
            assertEquals(320, bookmarks.single().scrollOffsetPx)
            assertEquals(66, viewModel.uiState.value.progressPercent)

            viewModel.toggleCurrentPageImageFavorite()
            advanceUntilIdle()
            val favorites = harness.bookmarkRepository
                .observeImageFavoritesForMedia(SiteId.MANGACOPY, chapters[1].id)
                .first()
            assertEquals(1, favorites.size)
            assertEquals(2, favorites.single().pageIndex)
            assertTrue(viewModel.uiState.value.isCurrentPageBookmarked)
            assertTrue(viewModel.uiState.value.isCurrentPageImageFavorited)
            assertTrue(viewModel.uiState.value.thumbnailPageBookmarks.isEmpty())

            val bookmark = bookmarks.single()
            viewModel.events.test {
                viewModel.jumpToPage(bookmark.page, bookmark.scrollOffsetPx, chapterId = bookmark.mediaId)
                assertEquals(ReaderEvent.ScrollToPage(2, 320, chapters[1].id), awaitItem())
                viewModel.jumpToPage(2)
                assertEquals(ReaderEvent.ScrollToPage(2, 0, chapters.first().id), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            viewModel.onStripViewportProgress(chapters.first().id, 2, mapOf(chapters.first().id to setOf(2)), 0, 8)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isCurrentPageBookmarked)
            assertFalse(viewModel.uiState.value.isCurrentPageImageFavorited)
        }

    @Test
    fun `exporting current page inside appended chapter exports a single page`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
            val chapters = chapters()
            val viewModel = reader(
                harness = harness,
                detail = serialDetail(chapters.first(), pageCount = 2),
                persistenceScope = backgroundScope,
                chapterLoader = SerialChapterLoader { _, _ -> chapters },
                extraDetails = mapOf(chapters[1].id to serialDetail(chapters[1], pageCount = 3)),
            )
            advanceUntilIdle()
            viewModel.setReadingMode(ReaderMode.STRIP)
            viewModel.onStripEndReached()
            advanceUntilIdle()
            viewModel.onStripViewportProgress(
                anchorChapterId = chapters[1].id,
                anchorPage = 2,
                visiblePagesByChapter = mapOf(chapters[1].id to setOf(2)),
                scrollOffsetPx = 320,
                remainingItemsToBottom = 8,
            )
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.exportCurrentPage(ExportMode.SAVE_IMAGES)
                advanceUntilIdle()
                val event = awaitItem()
                val prepared = when (event) {
                    is ReaderEvent.RequestShareExport -> event.prepared
                    is ReaderEvent.RequestSaveDocument -> event.prepared
                    is ReaderEvent.RequestOpenDocumentTree -> event.prepared
                    else -> error("unexpected event $event")
                }
                assertEquals(1, prepared.files.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `fast exit before debounce flushes the appended chapter's latest position`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
            val chapters = chapters()
            val viewModel = reader(
                harness = harness,
                detail = serialDetail(chapters.first(), pageCount = 2),
                persistenceScope = backgroundScope,
                chapterLoader = SerialChapterLoader { _, _ -> chapters },
                extraDetails = mapOf(chapters[1].id to serialDetail(chapters[1], pageCount = 3)),
            )
            advanceUntilIdle()
            viewModel.setReadingMode(ReaderMode.STRIP)
            viewModel.onStripEndReached()
            advanceUntilIdle()
            // Viewport moves into chapter 2, page 2 — but we exit before the 750ms debounce elapses.
            viewModel.onStripViewportProgress(
                anchorChapterId = chapters[1].id,
                anchorPage = 2,
                visiblePagesByChapter = mapOf(chapters[1].id to setOf(2)),
                scrollOffsetPx = 640,
                remainingItemsToBottom = 8,
            )
            viewModel.flushProgressNow()

            val progress = harness.historyRepository
                .observeReadingProgress(SiteId.MANGACOPY, chapters[1].id)
                .first()
            assertEquals(2, progress?.currentPage)
            assertEquals(640, progress?.scrollOffsetPx)
        }

    private fun reader(
        harness: ReaderTestHarness,
        detail: GalleryDetail,
        persistenceScope: CoroutineScope,
        chapterLoader: SerialChapterLoader,
        extraDetails: Map<String, GalleryDetail> = emptyMap(),
    ): ReaderViewModel {
        val descriptor = sampleSiteDescriptor(
            detail.item.siteId,
            primaryKind = detail.item.kind,
        )
        return ReaderViewModel(
            route = RavenRoute.Reader(
                siteId = detail.item.siteId.value,
                mediaId = detail.item.id,
                url = detail.item.url,
            ),
            siteRegistry = SiteRegistry(
                listOf(FakeGallerySitePlugin(descriptor, detailHandler = { detail })),
            ),
            pageLoader = FakeReaderPageLoader(
                detailLoader = { _, requestedMediaId, _, _ ->
                    val resolved = extraDetails[requestedMediaId] ?: detail
                    OfflineGallerySnapshot(resolved, emptyList(), emptyList(), "", false)
                },
            ),
            serialChapterLoader = chapterLoader,
            exportGateway = harness.exportGateway,
            historyRepository = harness.historyRepository,
            bookmarkRepository = harness.bookmarkRepository,
            preferencesRepository = harness.preferencesRepository,
            shelfRepository = harness.shelfRepository,
            playbackQueueStore = harness.playbackQueueStore,
            dispatchers = harness.dispatchers,
            persistenceScope = persistenceScope,
            clock = harness.clock,
        )
    }

    private fun chapters(): List<MediaItem> = (1..3).map { index ->
        MediaItem(
            siteId = SiteId.MANGACOPY,
            id = "parent-comic:chapter-$index",
            url = "https://mangacopy.example/comic/parent/chapter-$index",
            kind = MediaKind.SERIAL_COMIC,
            title = "第 $index 话",
            coverUrl = "https://mangacopy.example/cover.jpg",
            extra = mapOf("comicId" to "parent-comic", "chapterUuid" to "chapter-$index"),
        )
    }

    private fun serialDetail(chapter: MediaItem, pageCount: Int): GalleryDetail {
        val sample = sampleGalleryDetail(
            siteId = chapter.siteId,
            mediaId = chapter.id,
            pageCount = pageCount,
            kind = MediaKind.SERIAL_COMIC,
        )
        return sample.copy(
            item = chapter.copy(pageCount = pageCount),
            chaptersUrl = "${chapter.url}/chapters",
        )
    }

    @Test
    fun `serial chapter matcher requires colon boundary and supports chapterUrl hint`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        // Numeric suffix collision guard: chapter hint "10" must NOT match "comic:110".
        val shortAndLongChapters = listOf(
            MediaItem(
                siteId = SiteId.GUAZIMANHUA,
                id = "18741:110",
                url = "https://www.guazimanhua.com/chapter.php?id=110",
                kind = MediaKind.SERIAL_COMIC,
                title = "第110话",
                extra = mapOf("comicId" to "18741", "chapterId" to "110"),
            ),
            MediaItem(
                siteId = SiteId.GUAZIMANHUA,
                id = "18741:10",
                url = "https://www.guazimanhua.com/chapter.php?id=10",
                kind = MediaKind.SERIAL_COMIC,
                title = "第10话",
                extra = mapOf("comicId" to "18741", "chapterId" to "10"),
            ),
        )
        val rootWithShortHint = sampleGalleryDetail(
            siteId = SiteId.GUAZIMANHUA,
            mediaId = "18741",
            pageCount = 2,
            kind = MediaKind.SERIAL_COMIC,
        ).let {
            it.copy(
                item = it.item.copy(
                    extra = mapOf("comicId" to "18741", "chapterId" to "10"),
                ),
                chaptersUrl = "https://www.guazimanhua.com/comic.php?id=18741#chapters",
            )
        }
        val vmShort = reader(
            harness = harness,
            detail = rootWithShortHint,
            persistenceScope = backgroundScope,
            chapterLoader = SerialChapterLoader { _, _ -> shortAndLongChapters },
        )
        advanceUntilIdle()
        assertEquals("18741:110", vmShort.uiState.value.previousChapter?.id)
        assertNull(vmShort.uiState.value.nextChapter)

        // Guazi chapterUrl hint enables nextChapter when root opens first chapter.
        val mhChapters = listOf(
            MediaItem(
                siteId = SiteId.GUAZIMANHUA,
                id = "26001:583501",
                url = "https://www.guazimanhua.com/chapter.php?id=583501",
                kind = MediaKind.SERIAL_COMIC,
                title = "第1话",
                extra = mapOf("comicId" to "26001"),
            ),
            MediaItem(
                siteId = SiteId.GUAZIMANHUA,
                id = "26001:590566",
                url = "https://www.guazimanhua.com/chapter.php?id=590566",
                kind = MediaKind.SERIAL_COMIC,
                title = "第2话",
                extra = mapOf("comicId" to "26001"),
            ),
        )
        val mhRootDetail = sampleGalleryDetail(
            siteId = SiteId.GUAZIMANHUA,
            mediaId = "26001",
            pageCount = 2,
            kind = MediaKind.SERIAL_COMIC,
        ).let {
            it.copy(
                item = it.item.copy(
                    extra = mapOf("chapterUrl" to "https://www.guazimanhua.com/chapter.php?id=583501"),
                ),
                chaptersUrl = "https://www.guazimanhua.com/comic.php?id=26001#chapters",
            )
        }
        val vmMh = reader(
            harness = harness,
            detail = mhRootDetail,
            persistenceScope = backgroundScope,
            chapterLoader = SerialChapterLoader { _, _ -> mhChapters },
        )
        advanceUntilIdle()
        assertNull(vmMh.uiState.value.previousChapter)
        assertEquals("26001:590566", vmMh.uiState.value.nextChapter?.id)
    }
}
