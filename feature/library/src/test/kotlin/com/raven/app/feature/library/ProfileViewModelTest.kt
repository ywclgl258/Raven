package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.model.WatchProgress
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
class ProfileViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `initial state is loading until repositories hydrate`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("prof-loading"))
        harness.preferencesRepository.setActiveSiteId(SiteId.R34V)
        val vm = harness.profileViewModel()

        vm.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.isLoading)
            assertNull(initial.heroItem)
            advanceUntilIdle()

            val loaded = expectMostRecentItem()
            assertFalse(loaded.isLoading)
            assertEquals(LibraryMediaFilter.ALL, loaded.selectedFilter)
            assertEquals("R34V", loaded.activeSite?.shortName)
            assertFalse(loaded.activeSiteSupportsAccount)
            cancelAndIgnoreRemainingEvents()
        }
    }

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

        val vm = harness.profileViewModel()

        vm.uiState.test {
            // Skip initial loading emission until repository streams combine.
            var state = awaitItem()
            assertTrue(state.isLoading)
            advanceUntilIdle()
            while (state.heroItem == null) {
                state = awaitItem()
            }

            assertFalse(state.isLoading)
            // ALL filter skips v-done and selects c-wip from Continue activity
            assertEquals("c-wip", state.heroItem.mediaId)
            assertEquals(ProfileHeroSource.CONTINUE_ACTIVITY, state.heroItem.source)
            assertEquals(1, state.continueItems.size)
            assertEquals("第 10 / 30 页", state.continueItems.single().resumeLabel)
            assertEquals(10, state.continueItems.single().resumePage)

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
    fun `profile aggregates unread inbox downloads favorites subscriptions images and local content`() = runTest {
        val localRoot = tempFolder.newFolder("prof2-local")
        seedLocalBook(localRoot)
        val harness = LibraryTestHarness(mainRule.dispatcher, localRoot)
        harness.localLibraryImporter.refreshBooksSnapshot()

        harness.subscriptionRepository.saveSavedSearch(
            SavedSearch(
                id = "ss-1",
                name = "精选动画",
                query = "3d",
                siteIds = listOf(SiteId.R34V),
                createdAt = 1_000L,
            ),
        )
        harness.subscriptionRepository.subscribe(
            TagSubscription(
                siteId = SiteId.R34V,
                slug = "3d",
                label = "3D",
                url = "https://rule34video.com/tag/3d",
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
        harness.favoriteRepository.add(
            FavoriteItem(
                siteId = SiteId.R34V,
                targetId = "m1",
                kind = FavoriteKind.VIDEO,
                title = "Favourite Video",
                url = "https://rule34video.com/video/m1/",
                addedAt = 2_000L,
            ),
        )
        harness.bookmarkRepository.saveImageFavorite(
            ImageFavorite(
                id = "img-1",
                siteId = SiteId.NHENTAI,
                mediaId = "c1",
                mediaTitle = "Comic One",
                pageUrl = "https://nhentai.net/g/c1/1/",
                imageUrl = "https://nhentai.net/g/c1/1.jpg",
                pageIndex = 1,
                addedAt = 3_000L,
            ),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(id = "dl-active", kind = DownloadKind.VIDEO, status = DownloadStatus.DOWNLOADING),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(id = "dl-done", kind = DownloadKind.COMIC, status = DownloadStatus.COMPLETED),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(id = "dl-failed", kind = DownloadKind.COMIC, status = DownloadStatus.FAILED),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(id = "dl-cancelled", kind = DownloadKind.VIDEO, status = DownloadStatus.CANCELLED),
        )
        harness.downloadStateRepository.upsert(
            sampleDownload(id = "dl-paused", kind = DownloadKind.COMIC, status = DownloadStatus.PAUSED),
        )

        val vm = harness.profileViewModel()

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()

            assertFalse(state.isLoading)
            assertEquals(1, state.unreadInboxCount)
            assertEquals(1, state.activeDownloadCount)
            assertEquals(1, state.completedDownloadCount)
            assertEquals(1, state.pausedDownloadCount)
            assertEquals(2, state.attentionDownloadCount)
            assertEquals(5, state.totalDownloadCount)
            assertEquals(
                "2 项需处理 · 1 项已暂停 · 1 项进行中 · 1 项已完成",
                state.downloadSummary,
            )
            assertEquals(1, state.savedSearches.size)
            assertNotNull(state.savedSearches.firstOrNull { it.id == "ss-1" })
            assertEquals(1, state.totalFavoriteCount)
            assertEquals(1, state.imageFavoriteCount)
            assertEquals(1, state.tagSubscriptionCount)
            assertEquals(1, state.localContentCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `profile exposes site account entry and prefers unfinished smart shelf for all continue`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("prof3"))
        harness.preferencesRepository.setActiveSiteId(SiteId.NHENTAI)
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "c-wip",
                url = "https://nhentai.net/g/c-wip/",
                title = "Ongoing Comic",
                kind = MediaKind.COMIC,
                currentPage = 7,
                totalPages = 20,
                updatedAt = 1_500L,
            ),
        )

        val vm = harness.profileViewModel()
        val navigator = RecordingLibraryNavigator()

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val withoutSmartShelf = expectMostRecentItem()

            assertEquals("nHentai", withoutSmartShelf.activeSite?.shortName)
            assertTrue(withoutSmartShelf.activeSiteSupportsAccount)
            assertNull(withoutSmartShelf.unfinishedSmartShelfId)

            navigator.openProfileContinueAll(withoutSmartShelf.unfinishedSmartShelfId)
            assertEquals(1, navigator.historyCount)
            assertNull(navigator.openedSmartShelfId)

            navigator.openProfileSiteAccount(
                withoutSmartShelf.activeSite,
                withoutSmartShelf.activeSiteSupportsAccount,
            )
            assertEquals(SiteId.NHENTAI, navigator.openedSiteAccountId)

            harness.shelfRepository.saveSmartShelf(
                SmartShelf(
                    id = "smart-unfinished",
                    name = "未完成",
                    rule = SmartShelfRule.UNFINISHED,
                    createdAt = 1L,
                ),
            )
            advanceUntilIdle()
            val withSmartShelf = expectMostRecentItem()
            assertEquals("smart-unfinished", withSmartShelf.unfinishedSmartShelfId)

            navigator.openProfileContinueAll(withSmartShelf.unfinishedSmartShelfId)
            assertEquals("smart-unfinished", navigator.openedSmartShelfId)
            assertEquals(1, navigator.historyCount)

            harness.preferencesRepository.setActiveSiteId(SiteId.R34V)
            advanceUntilIdle()
            val unsupported = expectMostRecentItem()
            assertEquals("R34V", unsupported.activeSite?.shortName)
            assertFalse(unsupported.activeSiteSupportsAccount)

            val unsupportedNavigator = RecordingLibraryNavigator()
            unsupportedNavigator.openProfileSiteAccount(
                unsupported.activeSite,
                unsupported.activeSiteSupportsAccount,
            )
            assertNull(unsupportedNavigator.openedSiteAccountId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private fun LibraryTestHarness.profileViewModel(): ProfileViewModel = ProfileViewModel(
    historyRepository = historyRepository,
    shelfRepository = shelfRepository,
    subscriptionRepository = subscriptionRepository,
    favoriteRepository = favoriteRepository,
    bookmarkRepository = bookmarkRepository,
    localLibraryImporter = localLibraryImporter,
    downloadGateway = downloadGateway,
    preferencesRepository = preferencesRepository,
    siteRegistry = siteRegistry,
    dispatchers = dispatchers,
)

private class RecordingLibraryNavigator : LibraryNavigator {
    var historyCount: Int = 0
    var openedSmartShelfId: String? = null
    var openedSiteAccountId: SiteId? = null

    override fun openHistory() {
        historyCount += 1
    }

    override fun openSmartShelfDetail(smartShelfId: String) {
        openedSmartShelfId = smartShelfId
    }

    override fun openSiteAccount(siteId: SiteId) {
        openedSiteAccountId = siteId
    }

    override fun openSiteFeed(route: RavenRoute.SiteFeed) = Unit

    override fun openSiteFunctions(siteId: SiteId) = Unit

    override fun openSearch(initialQuery: String?, crossSite: Boolean) = Unit

    override fun openShelfDetail(shelfId: String) = Unit

    override fun openDownloads(siteId: SiteId?) = Unit

    override fun openFavorites(siteId: SiteId?) = Unit

    override fun openStats() = Unit

    override fun openImageFavorites() = Unit

    override fun openSubscriptions(siteId: SiteId?) = Unit

    override fun openGlobalInbox() = Unit

    override fun openLocalLibrary() = Unit

    override fun openSettings() = Unit

    override fun navigateBack() = Unit

    override fun openVideo(
        siteId: SiteId,
        mediaId: String,
        url: String,
        title: String,
        coverUrl: String,
        offlineOnly: Boolean,
        queueToken: String?,
    ) = Unit

    override fun openGallery(siteId: SiteId, mediaId: String, url: String) = Unit

    override fun openReader(
        siteId: SiteId,
        mediaId: String,
        url: String,
        startPage: Int,
        offlineOnly: Boolean,
        queueToken: String?,
    ) = Unit
}
