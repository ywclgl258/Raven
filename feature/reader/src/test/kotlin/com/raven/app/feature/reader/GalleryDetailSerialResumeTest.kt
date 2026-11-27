package com.raven.app.feature.reader

import com.raven.app.core.media.reader.OfflineGallerySnapshot
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryDetailSerialResumeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `delayed history resume never replaces an explicitly selected chapter`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val chapters = chapters()
        val selected = serialDetail(chapters[1])
        saveProgress(harness, chapters[0], page = 11)
        val requests = mutableListOf<String>()
        val loader = FakeReaderPageLoader(
            detailLoader = { _, mediaId, _, _ ->
                requests += mediaId
                OfflineGallerySnapshot(selected, emptyList(), emptyList(), "", false)
            },
        )
        val chaptersGate = CompletableDeferred<Unit>()
        val feed = feedSource {
            chaptersGate.await()
            FeedPage(items = chapters)
        }
        val viewModel = viewModel(harness, selected, loader, feed)
        advanceUntilIdle()
        assertEquals(selected.item.id, viewModel.uiState.value.detail?.item?.id)

        // Simulates the old 1–2 second delayed chapter response that used to restore chapter 17.
        chaptersGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(selected.item.id, viewModel.uiState.value.detail?.item?.id)
        assertEquals(listOf(selected.item.id), requests)
        assertEquals(chapters, viewModel.uiState.value.chapters)
    }

    @Test
    fun `series root resumes latest chapter even after its final page`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val chapters = chapters()
        val root = serialDetail(chapters[0]).let { detail ->
            detail.copy(
                item = detail.item.copy(
                    id = "parent-comic",
                    extra = mapOf("comicId" to "parent-comic", "chapterUuid" to "chapter-1"),
                ),
            )
        }
        val resumeDetail = serialDetail(chapters[1])
        saveProgress(harness, chapters[0], page = 7)
        harness.historyRepository.saveReadingProgress(
            progressOf(chapters[1], page = 48, completed = true, nowMs = harness.nowMs + 1),
        )
        val loader = FakeReaderPageLoader(
            detailLoader = { _, mediaId, _, _ ->
                val detail = if (mediaId == root.item.id) root else resumeDetail
                OfflineGallerySnapshot(detail, emptyList(), emptyList(), "", false)
            },
        )
        val viewModel = viewModel(harness, root, loader, feedSource { FeedPage(items = chapters) })

        advanceUntilIdle()

        assertEquals(resumeDetail.item.id, viewModel.uiState.value.detail?.item?.id)
        assertEquals(48, viewModel.uiState.value.readingProgress?.currentPage)

        // Favorite toggle after serial auto-resume must sync UI isFavorite state with the resumed chapter id
        assertFalse(viewModel.uiState.value.isFavorite)
        viewModel.toggleFavorite()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFavorite)
    }

    @Test
    fun `chapter loading failure stays on selection and exposes an error`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val selected = serialDetail(chapters()[1])
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ ->
                OfflineGallerySnapshot(selected, emptyList(), emptyList(), "", false)
            },
        )
        val viewModel = viewModel(
            harness = harness,
            detail = selected,
            loader = loader,
            feed = feedSource { error("章节源暂时不可用") },
        )

        advanceUntilIdle()

        assertEquals(selected.item.id, viewModel.uiState.value.detail?.item?.id)
        assertEquals("章节源暂时不可用", viewModel.uiState.value.chaptersError)
        assertFalse(viewModel.uiState.value.chaptersLoading)
    }

    @Test
    fun `serial chapter history maps read markers and next unread reactively`() = runTest(mainDispatcherRule.dispatcher) {
        val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
        val chapters = chapters()
        val current = serialDetail(chapters[0])
        saveProgress(harness, chapters[0], page = 7)
        saveProgress(harness, chapters[1], page = 48, completed = true)
        val loader = FakeReaderPageLoader(
            detailLoader = { _, _, _, _ ->
                OfflineGallerySnapshot(current, emptyList(), emptyList(), "", false)
            },
        )
        val viewModel = viewModel(harness, current, loader, feedSource { FeedPage(items = chapters) })

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(chapters, state.chapters)
        val currentProgress = state.readingProgressByMediaId[chapters[0].id]
        assertNotNull(currentProgress)
        assertEquals(7, currentProgress?.currentPage)
        assertFalse(currentProgress?.completed == true)
        assertTrue(state.readingProgressByMediaId.getValue(chapters[1].id).completed)
        assertEquals("读到 7 页", state.chapterPickerReadState.progressLabel(chapters[0].id))
        assertEquals("已读", state.chapterPickerReadState.progressLabel(chapters[1].id))
        assertEquals(chapters[2].id, state.nextUnreadChapter?.id)
        assertEquals(chapters[2].id, state.chapterPickerReadState.nextUnreadChapter?.id)
        assertFalse(state.allChaptersCompleted)
        assertNull(state.nextUnreadBoundaryLabel)

        // New history arrivals update the map and next-unread target without rebuilding the VM.
        saveProgress(harness, chapters[2], page = 48, completed = true)
        advanceUntilIdle()
        val laterState = viewModel.uiState.value
        assertNull(laterState.nextUnreadChapter)
        assertFalse(laterState.allChaptersCompleted)
        assertEquals("后续章节已读完", laterState.nextUnreadBoundaryLabel)

        saveProgress(harness, chapters[0], page = 48, completed = true)
        advanceUntilIdle()
        val finishedState = viewModel.uiState.value
        assertTrue(finishedState.allChaptersCompleted)
        assertEquals("全部章节已读完", finishedState.nextUnreadBoundaryLabel)
        assertEquals("全部章节已读完", finishedState.chapterPickerReadState.nextUnreadBoundaryLabel)
    }

    @Test
    fun `next unread starts after the current chapter and stops at the later boundary`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = ReaderTestHarness(mainDispatcherRule.dispatcher, tempFolder.newFolder())
            val chapters = chapters()
            val current = serialDetail(chapters[1])
            saveProgress(harness, chapters[0], page = 5)
            saveProgress(harness, chapters[1], page = 10)
            val loader = FakeReaderPageLoader(
                detailLoader = { _, _, _, _ ->
                    OfflineGallerySnapshot(current, emptyList(), emptyList(), "", false)
                },
            )
            val viewModel = viewModel(harness, current, loader, feedSource { FeedPage(items = chapters) })

            advanceUntilIdle()

            // Chapter 17 is unfinished but sits before the current chapter, so chapter 19 wins.
            assertEquals(chapters[2].id, viewModel.uiState.value.nextUnreadChapter?.id)
            assertFalse(viewModel.uiState.value.allChaptersCompleted)

            saveProgress(harness, chapters[2], page = 48, completed = true)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.nextUnreadChapter)
            assertFalse(viewModel.uiState.value.allChaptersCompleted)
            assertEquals("后续章节已读完", viewModel.uiState.value.nextUnreadBoundaryLabel)
        }

    @Test
    fun `chapter progress markers distinguish completed and unfinished chapters`() {
        val chapter = chapters()[1]
        assertNull(chapterProgressLabel(null))
        assertEquals("读到 7 页", chapterProgressLabel(progressOf(chapter, page = 7)))
        assertEquals("已读", chapterProgressLabel(progressOf(chapter, page = 48, completed = true)))
    }

    @Test
    fun `shared chapter picker read state defaults keep other callers unchanged`() {
        val chapter = chapters()[0]
        val readState = ChapterPickerReadState()
        assertNull(readState.nextUnreadChapter)
        assertNull(readState.nextUnreadBoundaryLabel)
        assertNull(readState.progressLabel(chapter.id))
        assertFalse(readState.isCompleted(chapter.id))
    }

    private fun viewModel(
        harness: ReaderTestHarness,
        detail: GalleryDetail,
        loader: ReaderPageLoader,
        feed: FeedSource,
    ): GalleryDetailViewModel {
        val siteDescriptor = sampleSiteDescriptor(SiteId.MANGACOPY, primaryKind = MediaKind.SERIAL_COMIC)
        val plugin = object : SitePlugin {
            override val descriptor = siteDescriptor
            override val feedSource = feed
        }
        return GalleryDetailViewModel(
            route = RavenRoute.GalleryDetail(
                siteId = detail.item.siteId.value,
                mediaId = detail.item.id,
                url = detail.item.url,
            ),
            siteRegistry = SiteRegistry(listOf(plugin)),
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
    }

    private fun feedSource(load: suspend () -> FeedPage): FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed = HomeFeed(siteId = SiteId.MANGACOPY)
        override suspend fun loadFeed(request: FeedRequest): FeedPage = load()
    }

    private fun progressOf(
        chapter: MediaItem,
        page: Int,
        completed: Boolean = false,
        nowMs: Long = 0L,
    ): ReadingProgress = ReadingProgress(
        siteId = chapter.siteId,
        mediaId = chapter.id,
        url = chapter.url,
        title = chapter.title,
        kind = MediaKind.SERIAL_COMIC,
        currentPage = if (completed) 48 else page,
        totalPages = 48,
        updatedAt = nowMs,
        completed = completed,
    )

    private suspend fun saveProgress(
        harness: ReaderTestHarness,
        chapter: MediaItem,
        page: Int,
        completed: Boolean = false,
    ) {
        harness.historyRepository.saveReadingProgress(
            progressOf(chapter, page = page, completed = completed, nowMs = harness.nowMs),
        )
    }

    private fun chapters(): List<MediaItem> = (17..19).map { index ->
        MediaItem(
            siteId = SiteId.MANGACOPY,
            id = "parent-comic:chapter-$index",
            url = "https://mangacopy.example/comic/parent/chapter-$index",
            kind = MediaKind.SERIAL_COMIC,
            title = "第 $index 话",
            extra = mapOf("comicId" to "parent-comic", "chapterUuid" to "chapter-$index"),
        )
    }

    private fun serialDetail(chapter: MediaItem): GalleryDetail {
        val sample = sampleGalleryDetail(
            siteId = chapter.siteId,
            mediaId = chapter.id,
            pageCount = 48,
            kind = MediaKind.SERIAL_COMIC,
        )
        return sample.copy(
            item = chapter.copy(pageCount = 48),
            chaptersUrl = "https://mangacopy.example/comic/parent#chapters",
        )
    }
}
