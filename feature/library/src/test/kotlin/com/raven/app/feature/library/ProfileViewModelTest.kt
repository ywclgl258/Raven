package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.model.WatchProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `hero skips completed works and falls back to watch later when none unfinished`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("prof1"))

        // Completed video (within 20s end grace window) & unfinished comic
        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "v-done",
                url = "https://rule34video.com/video/v-done/",
                title = "Finished Clip",
                positionMs = 590_000L,
                durationMs = 600_000L,
                updatedAt = 2_000L,
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "c-wip",
                url = "https://nhentai.net/g/c-wip/",
                title = "Ongoing Comic",
                kind = MediaKind.COMIC,
                currentPage = 10,
                totalPages = 30,
                updatedAt = 1_500L,
            ),
        )
        harness.shelfRepository.addItem(
            Shelf.WATCH_LATER_ID,
            ShelfItem(
                shelfId = Shelf.WATCH_LATER_ID,
                siteId = SiteId.R34V,
                mediaId = "v-wl",
                url = "https://rule34video.com/video/v-wl/",
                kind = MediaKind.VIDEO,
                title = "Queued Video",
                addedAt = 1_000L,
            ),
        )

        val vm = ProfileViewModel(
            historyRepository = harness.historyRepository,
            shelfRepository = harness.shelfRepository,
            subscriptionRepository = harness.subscriptionRepository,
            favoriteRepository = harness.favoriteRepository,
            downloadGateway = harness.downloadGateway,
            preferencesRepository = harness.preferencesRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
        )

        vm.uiState.test {
            // Skip initial empty emission until repository streams combine
            var state = awaitItem()
            advanceUntilIdle()
            while (state.heroItem == null) {
                state = awaitItem()
            }

            // ALL filter skips v-done and selects c-wip from Continue activity
            assertEquals("c-wip", state.heroItem.mediaId)
            assertEquals(ProfileHeroSource.CONTINUE_ACTIVITY, state.heroItem.source)
            assertEquals(1, state.continueItems.size)

            // Switching to VIDEO filter skips v-done (completed) and falls back to Watch Later v-wl
            vm.selectFilter(LibraryMediaFilter.VIDEO)
            advanceUntilIdle()
            val videoState = expectMostRecentItem()
            assertEquals(LibraryMediaFilter.VIDEO, videoState.selectedFilter)
            assertEquals("v-wl", videoState.heroItem?.mediaId)
            assertEquals(ProfileHeroSource.WATCH_LATER_FALLBACK, videoState.heroItem?.source)
            assertEquals(0, videoState.continueItems.size)
            assertEquals(1, videoState.watchLaterItems.size)

            // PHOTO filter has neither activity nor watch later -> null hero
            vm.selectFilter(LibraryMediaFilter.PHOTO)
            advanceUntilIdle()
            val photoState = expectMostRecentItem()
            assertNull(photoState.heroItem)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `profile aggregates unread inbox active downloads and saved searches reactively`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("prof2"))

        harness.subscriptionRepository.saveSavedSearch(
            SavedSearch(
                id = "ss-1",
                name = "精选动画",
                query = "3d",
                siteIds = listOf(SiteId.R34V),
                createdAt = 1_000L,
            ),
        )
        harness.subscriptionRepository.upsertInboxItems(
            listOf(
                UpdateInboxItem(
                    id = "in-1",
                    sourceKind = InboxSourceKind.TAG_SUBSCRIPTION,
                    sourceId = "r34v:TAG:3d",
                    sourceLabel = "3d",
                    item = sampleMedia(SiteId.R34V, "m1"),
                    discoveredAt = 5_000L,
                    isRead = false,
                ),
                UpdateInboxItem(
                    id = "in-2",
                    sourceKind = InboxSourceKind.SAVED_SEARCH,
                    sourceId = "ss-1",
                    sourceLabel = "精选动画",
                    item = sampleMedia(SiteId.R34V, "m2"),
                    discoveredAt = 6_000L,
                    isRead = true,
                ),
            ),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(id = "dl-1", kind = DownloadKind.VIDEO, status = DownloadStatus.DOWNLOADING),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(id = "dl-2", kind = DownloadKind.COMIC, status = DownloadStatus.COMPLETED),
        )

        val vm = ProfileViewModel(
            historyRepository = harness.historyRepository,
            shelfRepository = harness.shelfRepository,
            subscriptionRepository = harness.subscriptionRepository,
            favoriteRepository = harness.favoriteRepository,
            downloadGateway = harness.downloadGateway,
            preferencesRepository = harness.preferencesRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(1, state.unreadInboxCount)
            assertEquals(1, state.activeDownloadCount)
            assertEquals(1, state.completedDownloadCount)
            assertEquals(1, state.savedSearches.size)
            assertNotNull(state.savedSearches.firstOrNull { it.id == "ss-1" })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
