package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.WatchProgress
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
class ShelfDetailViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `search multi filter and view sort never overwrite persisted manual order`() = runTest {
        val dayMs = 24L * 60L * 60L * 1000L
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("shelf1"))
        val now = harness.nowMs
        val shelf = harness.shelfRepository.createShelf(name = "测试片单")

        harness.shelfRepository.addItems(
            shelf.id,
            listOf(
                ShelfItem(
                    shelfId = shelf.id,
                    siteId = SiteId.R34V,
                    mediaId = "a",
                    url = "https://rule34video.com/video/a/",
                    kind = MediaKind.VIDEO,
                    title = "Zebra Clip",
                    coverUrl = "https://rule34video.com/a.jpg",
                    addedAt = now - 2L * dayMs,
                    completed = false,
                ),
                ShelfItem(
                    shelfId = shelf.id,
                    siteId = SiteId.NHENTAI,
                    mediaId = "b",
                    url = "https://nhentai.net/g/b/",
                    kind = MediaKind.COMIC,
                    title = "Alpha Comic",
                    coverUrl = "https://nhentai.net/b.jpg",
                    addedAt = now - 20L * dayMs,
                    completed = true,
                ),
                ShelfItem(
                    shelfId = shelf.id,
                    siteId = SiteId.EVERIA,
                    mediaId = "c",
                    url = "https://everia.club/c/",
                    kind = MediaKind.PHOTO,
                    title = "Middle Set",
                    coverUrl = "https://everia.club/c.jpg",
                    addedAt = now - 60L * dayMs,
                    completed = false,
                ),
            ),
        )

        val vm = ShelfDetailViewModel(
            target = ShelfDetailTarget.Custom(shelf.id),
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            downloadGateway = harness.downloadGateway,
            playbackQueueStore = harness.playbackQueueStore,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val manualState = expectMostRecentItem()
            assertEquals(listOf("a", "b", "c"), manualState.visibleEntries.map { it.mediaId })

            // View sort by TITLE_AZ must NOT overwrite persisted positions in Room
            vm.selectViewSort(ShelfViewSort.TITLE_AZ)
            advanceUntilIdle()
            val titleSorted = expectMostRecentItem()
            assertEquals(listOf("b", "c", "a"), titleSorted.visibleEntries.map { it.mediaId })
            val persisted = harness.shelfRepository.observeShelfItems(shelf.id).first()
            assertEquals(listOf("a", "b", "c"), persisted.map { it.mediaId })

            // Switch back to MANUAL -> original order intact, then move item c up
            vm.selectViewSort(ShelfViewSort.MANUAL)
            advanceUntilIdle()
            vm.moveItemUp(SiteId.EVERIA, "c")
            advanceUntilIdle()
            val moved = expectMostRecentItem()
            assertEquals(listOf("a", "c", "b"), moved.visibleEntries.map { it.mediaId })

            // Multi-filter: completion + added window + mediaKind + search
            vm.selectCompletionFilter(ShelfCompletionFilter.UNCOMPLETED)
            vm.selectAddedWindow(ShelfAddedWindow.DAYS_7)
            advanceUntilIdle()
            assertEquals(listOf("a"), expectMostRecentItem().visibleEntries.map { it.mediaId })

            vm.resetFilters()
            vm.selectAddedWindow(ShelfAddedWindow.DAYS_30)
            advanceUntilIdle()
            assertEquals(listOf("a", "b"), expectMostRecentItem().visibleEntries.map { it.mediaId })

            vm.resetFilters()
            vm.updateSearchQuery("nhentai")
            advanceUntilIdle()
            assertEquals(listOf("b"), expectMostRecentItem().visibleEntries.map { it.mediaId })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offline preparation splits ready queued missing and sequential queue respects offline filtering`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("shelf2"))
        val shelf = harness.shelfRepository.createShelf(name = "离线清单")
        harness.shelfRepository.addItems(
            shelf.id,
            listOf(
                ShelfItem(
                    shelfId = shelf.id,
                    siteId = SiteId.NHENTAI,
                    mediaId = "ready-1",
                    url = "https://nhentai.net/g/ready-1/",
                    kind = MediaKind.COMIC,
                    title = "Ready Comic",
                    coverUrl = "https://nhentai.net/r1.jpg",
                ),
                ShelfItem(
                    shelfId = shelf.id,
                    siteId = SiteId.NHENTAI,
                    mediaId = "queued-2",
                    url = "https://nhentai.net/g/queued-2/",
                    kind = MediaKind.COMIC,
                    title = "Queued Comic",
                    coverUrl = "https://nhentai.net/q2.jpg",
                ),
                ShelfItem(
                    shelfId = shelf.id,
                    siteId = SiteId.R34V,
                    mediaId = "missing-3",
                    url = "https://rule34video.com/video/missing-3/",
                    kind = MediaKind.VIDEO,
                    title = "Missing Video",
                    coverUrl = "https://rule34video.com/m3.jpg",
                ),
            ),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(
                id = "nhentai:ready-1",
                siteId = SiteId.NHENTAI,
                mediaId = "ready-1",
                kind = DownloadKind.COMIC,
                status = DownloadStatus.COMPLETED,
            ),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(
                id = "nhentai:queued-2",
                siteId = SiteId.NHENTAI,
                mediaId = "queued-2",
                kind = DownloadKind.COMIC,
                status = DownloadStatus.DOWNLOADING,
            ),
        )

        val vm = ShelfDetailViewModel(
            target = ShelfDetailTarget.Custom(shelf.id),
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            downloadGateway = harness.downloadGateway,
            playbackQueueStore = harness.playbackQueueStore,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(listOf("ready-1"), state.offlinePrep.readyItems.map { it.mediaId })
            assertEquals(listOf("queued-2"), state.offlinePrep.queuedItems.map { it.mediaId })
            assertEquals(listOf("missing-3"), state.offlinePrep.missingItems.map { it.mediaId })

            // One-tap batch enqueue should enqueue ONLY missing items with shelf name as group
            vm.enqueueMissingDownloads()
            advanceUntilIdle()
            assertEquals(1, harness.downloadGateway.enqueuedBatches.size)
            val (enqueuedItems, groupName) = harness.downloadGateway.enqueuedBatches.single()
            assertEquals(listOf("missing-3"), enqueuedItems.map { it.id })
            assertEquals("离线清单", groupName)

            // Offline-only sequential queue skips queued-2 and missing-3, launching ready-1
            vm.startSequentialOffline()
            advanceUntilIdle()
            val afterOfflineLaunch = expectMostRecentItem()
            val launchEvent = afterOfflineLaunch.pendingLaunch
            assertNotNull(launchEvent)
            assertEquals("ready-1", launchEvent?.firstItem?.id)
            assertTrue(launchEvent?.offlineOnly == true)
            assertEquals(2, launchEvent?.skippedCount)
            assertEquals(launchEvent?.queueToken, harness.playbackQueueStore.snapshot()?.token)

            vm.consumeLaunchEvent()
            advanceUntilIdle()
            assertNull(expectMostRecentItem().pendingLaunch)

            // Custom cover selection + clear cover metadata edit
            vm.updateCustomShelfMetadata(
                name = "离线精选",
                note = "长途航班",
                isPinned = true,
                coverCompositeKey = "nhentai:ready-1",
                clearCover = false,
            )
            advanceUntilIdle()
            val updated = expectMostRecentItem().shelf!!
            assertEquals("离线精选", updated.name)
            assertEquals("nhentai:ready-1", updated.coverCompositeKey)
            assertTrue(updated.isPinned)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `smart shelf detail evaluates dynamic rule and allows rule update`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("shelf3"))
        val smart = harness.shelfRepository.saveSmartShelf(
            SmartShelf(
                id = "smart-offline",
                name = "离线未看",
                rule = SmartShelfRule.OFFLINE_UNSEEN,
                mediaKindFilter = MediaKind.COMIC,
            ),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(
                id = "nhentai:100",
                siteId = SiteId.NHENTAI,
                mediaId = "100",
                kind = DownloadKind.COMIC,
                status = DownloadStatus.COMPLETED,
                title = "Offline Comic 100",
            ),
        )

        val vm = ShelfDetailViewModel(
            target = ShelfDetailTarget.Smart(smart.id),
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            downloadGateway = harness.downloadGateway,
            playbackQueueStore = harness.playbackQueueStore,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertTrue(state.isSmartShelf)
            assertEquals("离线未看", state.title)
            assertEquals(listOf("100"), state.visibleEntries.map { it.mediaId })

            vm.updateSmartShelfMetadata(
                name = "近期新增",
                rule = SmartShelfRule.RECENT,
                mediaKindFilter = null,
                siteIdFilter = null,
                isPinned = true,
            )
            advanceUntilIdle()
            val updated = expectMostRecentItem()
            assertEquals("近期新增", updated.title)
            assertEquals(SmartShelfRule.RECENT, updated.smartShelf?.rule)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `missing shelf sets isNotFound and unmarking completed overrides auto-completion`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("shelf4"))

        // Non-existent shelf sets isNotFound even when not triggered by delete button
        val missingVm = ShelfDetailViewModel(
            target = ShelfDetailTarget.Custom("missing-id"),
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            downloadGateway = harness.downloadGateway,
            playbackQueueStore = harness.playbackQueueStore,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )
        missingVm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertTrue(expectMostRecentItem().isNotFound)
            cancelAndIgnoreRemainingEvents()
        }

        // Auto-completed item via WatchProgress can be un-marked via toggleItemCompleted
        val shelf = harness.shelfRepository.createShelf(name = "完成开关")
        harness.shelfRepository.addItem(
            shelf.id,
            ShelfItem(
                shelfId = shelf.id,
                siteId = SiteId.R34V,
                mediaId = "w99",
                url = "https://rule34video.com/video/99/",
                kind = MediaKind.VIDEO,
                title = "Auto Completed",
                coverUrl = "",
                addedAt = 100L,
                completed = false,
            ),
        )
        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "w99",
                url = "https://rule34video.com/video/99/",
                title = "Auto Completed",
                positionMs = 115_000L,
                durationMs = 120_000L,
                updatedAt = 200L,
                completed = true,
            ),
        )

        fun openShelf() = ShelfDetailViewModel(
            target = ShelfDetailTarget.Custom(shelf.id),
            shelfRepository = harness.shelfRepository,
            historyRepository = harness.historyRepository,
            downloadGateway = harness.downloadGateway,
            playbackQueueStore = harness.playbackQueueStore,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )
        val vm = openShelf()
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val initialEntry = expectMostRecentItem().allEntries.single()
            assertTrue(initialEntry.completed)

            vm.toggleItemCompleted(initialEntry)
            advanceUntilIdle()
            val afterUnmark = expectMostRecentItem().allEntries.single()
            assertFalse(afterUnmark.completed)
            cancelAndIgnoreRemainingEvents()
        }
        openShelf().uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertFalse(expectMostRecentItem().allEntries.single().completed)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
