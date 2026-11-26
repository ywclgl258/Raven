package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.sitespi.ResolvedLink
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class LibraryHubViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `custom shelf CRUD creates updates pins and protects built in watch later`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("hub1"))
        val vm = LibraryHubViewModel(
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            bookmarkRepository = harness.bookmarkRepository,
            subscriptionRepository = harness.subscriptionRepository,
            downloadGateway = harness.downloadGateway,
            localLibraryImporter = harness.localLibraryImporter,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertTrue(initial.shelves.any { it.shelf.id == Shelf.WATCH_LATER_ID })

            // Create custom shelf
            vm.createShelf(name = "周末合集", note = "高分精选", isPinned = true)
            advanceUntilIdle()
            val afterCreate = expectMostRecentItem()
            val created = afterCreate.shelves.firstOrNull { it.shelf.name == "周末合集" }?.shelf
            assertNotNull(created)
            assertEquals("高分精选", created?.note)
            assertTrue(created?.isPinned == true)

            // Update custom shelf
            vm.updateShelf(
                shelfId = created!!.id,
                name = "年度精选",
                note = "更新备注",
                isPinned = false,
            )
            advanceUntilIdle()
            val afterUpdate = expectMostRecentItem()
            val updated = afterUpdate.shelves.first { it.shelf.id == created.id }.shelf
            assertEquals("年度精选", updated.name)
            assertEquals("更新备注", updated.note)
            assertFalse(updated.isPinned)

            // Deleting built-in watch-later must be rejected
            vm.deleteShelf(Shelf.WATCH_LATER_ID)
            advanceUntilIdle()
            val afterAttempt = expectMostRecentItem()
            assertTrue(afterAttempt.shelves.any { it.shelf.id == Shelf.WATCH_LATER_ID })
            assertTrue(afterAttempt.message?.contains("不可删除") == true)

            // Deleting custom shelf removes it cleanly
            vm.deleteShelf(created.id)
            advanceUntilIdle()
            val afterDelete = expectMostRecentItem()
            assertFalse(afterDelete.shelves.any { it.shelf.id == created.id })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `smart shelf CRUD evaluates live counts dynamically and resolves share links`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("hub2"))
        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "vid-1",
                url = "https://rule34video.com/video/vid-1/",
                title = "Unfinished Video",
                positionMs = 45_000L,
                durationMs = 300_000L,
                updatedAt = harness.nowMs,
            ),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(
                id = "nh-dl-1",
                siteId = SiteId.NHENTAI,
                mediaId = "12345",
                kind = DownloadKind.COMIC,
                status = DownloadStatus.COMPLETED,
                title = "Offline Unseen Book",
            ),
        )

        val vm = LibraryHubViewModel(
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            bookmarkRepository = harness.bookmarkRepository,
            subscriptionRepository = harness.subscriptionRepository,
            downloadGateway = harness.downloadGateway,
            localLibraryImporter = harness.localLibraryImporter,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()

            vm.saveSmartShelf(
                name = "未看完视频",
                rule = SmartShelfRule.UNFINISHED,
                mediaKindFilter = MediaKind.VIDEO,
                siteIdFilter = SiteId.R34V,
                isPinned = true,
            )
            vm.saveSmartShelf(
                name = "已离线未看漫画",
                rule = SmartShelfRule.OFFLINE_UNSEEN,
                mediaKindFilter = MediaKind.COMIC,
                siteIdFilter = null,
                isPinned = false,
            )
            advanceUntilIdle()

            val state = expectMostRecentItem()
            assertEquals(2, state.smartShelves.size)
            val unfinishedSummary = state.smartShelves.first { it.smartShelf.name == "未看完视频" }
            assertEquals(1, unfinishedSummary.matchCount)
            val offlineSummary = state.smartShelves.first { it.smartShelf.name == "已离线未看漫画" }
            assertEquals(1, offlineSummary.matchCount)

            // Delete smart shelf
            vm.deleteSmartShelf(offlineSummary.smartShelf.id)
            advanceUntilIdle()
            assertEquals(1, expectMostRecentItem().smartShelves.size)

            // Resolve link from arbitrary share text using SiteRegistry.resolveLinkFromText
            val resolved = vm.resolveLinkText("推荐看这个 https://nhentai.net/g/556677/ 很好看")
            assertNotNull(resolved)
            assertEquals(SiteId.NHENTAI, resolved?.siteId)
            assertTrue(resolved?.link is ResolvedLink.Gallery)
            assertEquals("556677", (resolved?.link as ResolvedLink.Gallery).id)

            assertNull(vm.resolveLinkText("https://unknown-site.invalid/foo"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hub metrics expose offline bytes and saved searches while local removal purges references`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("hub3"))
        harness.downloadStateRepository.upsertAll(
            listOf(
                sampleDownload(
                    id = "dl-complete-1",
                    status = DownloadStatus.COMPLETED,
                    downloadedBytes = 1_048_576L,
                ),
                sampleDownload(
                    id = "dl-complete-2",
                    status = DownloadStatus.COMPLETED,
                    downloadedBytes = 0L,
                    totalBytes = 2_097_152L,
                ),
                sampleDownload(
                    id = "dl-active",
                    status = DownloadStatus.DOWNLOADING,
                    downloadedBytes = 4_096L,
                ),
            ),
        )
        harness.subscriptionRepository.subscribe(
            TagSubscription(
                siteId = SiteId.R34V,
                slug = "cyberpunk",
                label = "赛博朋克",
                url = "https://rule34video.com/tags/cyberpunk/",
                scope = SearchScope.TAG,
            ),
        )
        harness.subscriptionRepository.saveSavedSearch(
            SavedSearch(
                id = "ss-mecha",
                name = "机甲追踪",
                query = "mecha",
                siteIds = listOf(SiteId.R34V),
                createdAt = harness.nowMs,
            ),
        )

        val incomingDir = tempFolder.newFolder("hub3-book")
        File(incomingDir, "page1.png").writeBytes(minimalValidPngBytes(5))
        val book = harness.localLibraryImporter.importLocalDirectory(incomingDir, title = "本地漫画")
        val shelf = harness.shelfRepository.createShelf("混合片单")
        harness.shelfRepository.addItem(
            shelf.id,
            ShelfItem(
                shelfId = shelf.id,
                siteId = SiteId.LOCAL,
                mediaId = book.id,
                url = "ravenlocal://${book.id}",
                kind = MediaKind.COMIC,
                title = book.title,
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.LOCAL,
                mediaId = book.id,
                url = "ravenlocal://${book.id}",
                title = book.title,
                kind = MediaKind.COMIC,
                currentPage = 1,
                totalPages = 1,
                updatedAt = harness.nowMs,
            ),
        )

        val vm = LibraryHubViewModel(
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            bookmarkRepository = harness.bookmarkRepository,
            subscriptionRepository = harness.subscriptionRepository,
            downloadGateway = harness.downloadGateway,
            localLibraryImporter = harness.localLibraryImporter,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(2, state.completedDownloadCount)
            assertEquals(3_145_728L, state.completedDownloadBytes)
            assertEquals(1, state.activeDownloadCount)
            assertEquals(1, state.subscriptionCount)
            assertEquals(1, state.savedSearchCount)
            assertEquals(1, state.localBooks.size)
            assertEquals(1, state.shelves.first { it.shelf.id == shelf.id }.itemCount)
            assertEquals(1, state.historyCount)

            vm.removeLocalBook(book.id)
            advanceUntilIdle()
            val afterRemoval = expectMostRecentItem()
            assertEquals(0, afterRemoval.localBooks.size)
            assertEquals(0, afterRemoval.historyCount)
            assertTrue(afterRemoval.message?.contains("清理关联片单") == true)
            assertTrue(harness.shelfRepository.observeShelfItems(shelf.id).first().isEmpty())
            assertNull(harness.historyRepository.getReadingProgress(SiteId.LOCAL, book.id))
            cancelAndIgnoreRemainingEvents()
        }
    }
}
