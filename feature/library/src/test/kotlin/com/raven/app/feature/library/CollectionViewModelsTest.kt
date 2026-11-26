package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.WatchProgress
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModelsTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `favorites view model filters across sites and batch adds media favorites to shelf`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("col1"))
        val shelf = harness.shelfRepository.createShelf("收藏整理")
        harness.favoriteRepository.add(
            FavoriteItem(
                siteId = SiteId.R34V,
                targetId = "v1",
                kind = FavoriteKind.VIDEO,
                title = "Favorite Clip",
                url = "https://rule34video.com/video/v1/",
                addedAt = 100L,
            ),
        )
        harness.favoriteRepository.add(
            FavoriteItem(
                siteId = SiteId.NHENTAI,
                targetId = "tag-scifi",
                kind = FavoriteKind.TAG,
                title = "Sci-Fi",
                url = "https://nhentai.net/tag/sci-fi/",
                addedAt = 200L,
            ),
        )

        val vm = FavoritesViewModel(
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertEquals(2, expectMostRecentItem().items.size)

            vm.selectKindFilter(FavoriteKindFilter.VIDEO)
            advanceUntilIdle()
            val filtered = expectMostRecentItem().items
            assertEquals(listOf("v1"), filtered.map { it.targetId })

            vm.addFavoritesToShelf(shelf.id, filtered)
            advanceUntilIdle()
            val shelfItems = harness.shelfRepository.observeShelfItems(shelf.id).first()
            assertEquals(listOf("v1"), shelfItems.map { it.mediaId })

            vm.selectKindFilter(FavoriteKindFilter.ALL)
            vm.clearSiteFavorites(SiteId.NHENTAI)
            advanceUntilIdle()
            assertEquals(1, expectMostRecentItem().totalCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `history image favorites and local library view models support filtering batch shelf add and removal`() = runTest {
        val localRoot = tempFolder.newFolder("col2-local")
        val harness = LibraryTestHarness(mainRule.dispatcher, localRoot)
        val shelf = harness.shelfRepository.createShelf("混合片单")

        // Seed history
        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "h-vid",
                url = "https://rule34video.com/video/h-vid/",
                title = "History Video",
                positionMs = 60_000L,
                durationMs = 300_000L,
                updatedAt = 2_000L,
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "h-comic",
                url = "https://nhentai.net/g/h-comic/",
                title = "History Comic",
                kind = MediaKind.COMIC,
                currentPage = 10,
                totalPages = 10,
                updatedAt = 3_000L,
            ),
        )

        val historyVm = HistoryViewModel(
            historyRepository = harness.historyRepository,
            shelfRepository = harness.shelfRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
        )
        historyVm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(listOf("h-comic", "h-vid"), initial.entries.map { it.mediaId })

            historyVm.selectCompletionFilter(ShelfCompletionFilter.UNCOMPLETED)
            advanceUntilIdle()
            val uncompleted = expectMostRecentItem()
            assertEquals(listOf("h-vid"), uncompleted.entries.map { it.mediaId })

            historyVm.addEntriesToShelf(shelf.id, uncompleted.entries)
            advanceUntilIdle()
            assertEquals(1, harness.shelfRepository.observeShelfItems(shelf.id).first().size)

            historyVm.clearHistory()
            advanceUntilIdle()
            assertEquals(0, expectMostRecentItem().totalCount)
            cancelAndIgnoreRemainingEvents()
        }

        // Seed image favorites
        harness.bookmarkRepository.saveImageFavorite(
            ImageFavorite(
                id = "img-1",
                siteId = SiteId.NHENTAI,
                mediaId = "500",
                mediaTitle = "Artbook",
                pageUrl = "https://nhentai.net/g/500/3/",
                imageUrl = "https://i.nhentai.net/500/3.png",
                pageIndex = 3,
                addedAt = 1_000L,
            ),
        )
        val imageVm = ImageFavoritesViewModel(
            bookmarkRepository = harness.bookmarkRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
        )
        imageVm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertEquals(1, expectMostRecentItem().items.size)
            imageVm.deleteImageFavorite("img-1")
            advanceUntilIdle()
            assertEquals(0, expectMostRecentItem().totalCount)
            cancelAndIgnoreRemainingEvents()
        }

        // Import a local comic directory into LocalLibraryImporter and verify LocalLibraryViewModel
        val incomingDir = tempFolder.newFolder("sample-book")
        File(incomingDir, "page1.png").writeBytes(minimalValidPngBytes(1))
        File(incomingDir, "page2.png").writeBytes(minimalValidPngBytes(2))
        val imported = harness.localLibraryImporter.importLocalDirectory(incomingDir, title = "本地测试漫画")

        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.LOCAL,
                mediaId = imported.id,
                url = "ravenlocal://${imported.id}",
                title = "本地测试漫画",
                positionMs = 1_000L,
                durationMs = 10_000L,
                updatedAt = 4_000L,
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.LOCAL,
                mediaId = imported.id,
                url = "ravenlocal://${imported.id}",
                title = "本地测试漫画",
                kind = MediaKind.COMIC,
                currentPage = 1,
                totalPages = 2,
                updatedAt = 5_000L,
            ),
        )

        val localVm = LocalLibraryViewModel(
            localLibraryImporter = harness.localLibraryImporter,
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            dispatchers = harness.dispatchers,
        )
        localVm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(1, state.totalCount)
            assertEquals(imported.id, state.books.single().id)
            assertTrue(state.totalBytes > 0L)

            localVm.toggleSelectBook(imported.id)
            advanceUntilIdle()
            localVm.addSelectedToShelf(shelf.id)
            advanceUntilIdle()
            assertEquals(2, harness.shelfRepository.observeShelfItems(shelf.id).first().size)

            localVm.removeBook(imported.id)
            advanceUntilIdle()
            assertEquals(0, expectMostRecentItem().totalCount)
            assertNull(harness.historyRepository.getWatchProgress(SiteId.LOCAL, imported.id))
            assertNull(harness.historyRepository.getReadingProgress(SiteId.LOCAL, imported.id))
            assertEquals(
                listOf("h-vid"),
                harness.shelfRepository.observeShelfItems(shelf.id).first().map { it.mediaId },
            )
            assertTrue(localVm.uiState.value.message?.contains("清理关联片单") == true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `local library batch removal resets selection reports summary and exposes usable space`() = runTest {
        val localRoot = tempFolder.newFolder("col3-local")
        val harness = LibraryTestHarness(mainRule.dispatcher, localRoot)
        val firstDir = tempFolder.newFolder("batch-book-1")
        File(firstDir, "page1.png").writeBytes(minimalValidPngBytes(3))
        val first = harness.localLibraryImporter.importLocalDirectory(firstDir, title = "批量漫画一")
        val secondDir = tempFolder.newFolder("batch-book-2")
        File(secondDir, "page1.png").writeBytes(minimalValidPngBytes(4))
        val second = harness.localLibraryImporter.importLocalDirectory(secondDir, title = "批量漫画二")

        val shelf = harness.shelfRepository.createShelf("批量片单")
        harness.shelfRepository.addItem(
            shelf.id,
            ShelfItem(
                shelfId = shelf.id,
                siteId = SiteId.LOCAL,
                mediaId = first.id,
                url = "ravenlocal://${first.id}",
                kind = MediaKind.COMIC,
                title = first.title,
            ),
        )

        val vm = LocalLibraryViewModel(
            localLibraryImporter = harness.localLibraryImporter,
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            dispatchers = harness.dispatchers,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(2, initial.totalCount)
            assertTrue(initial.availableBytes != null && initial.availableBytes > 0L)

            // Nothing selected -> batch delete is a no-op
            vm.removeSelectedBooks()
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.totalCount)
            assertNull(vm.uiState.value.message)

            vm.selectAllVisible()
            advanceUntilIdle()
            assertEquals(setOf(first.id, second.id), expectMostRecentItem().selectedIds)

            vm.removeSelectedBooks()
            advanceUntilIdle()
            val afterDelete = expectMostRecentItem()
            assertEquals(0, afterDelete.totalCount)
            assertEquals(false, afterDelete.selectionMode)
            assertTrue(afterDelete.selectedIds.isEmpty())
            assertEquals("已删除 2 项本地内容，并清理关联片单与历史记录", afterDelete.message)
            assertTrue(harness.shelfRepository.observeShelfItems(shelf.id).first().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
