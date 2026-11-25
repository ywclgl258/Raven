package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.WatchProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `aggregates watch duration reading pages site breakdown downloads and localized top tags`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("stats1"))
        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "v1",
                url = "https://rule34video.com/video/1/",
                title = "Video 1",
                positionMs = 120_000L,
                durationMs = 120_000L,
                updatedAt = 100L,
                completed = true,
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "c1",
                url = "https://nhentai.net/g/1/",
                title = "Comic 1",
                kind = MediaKind.COMIC,
                currentPage = 15,
                totalPages = 30,
                updatedAt = 200L,
            ),
        )
        harness.favoriteRepository.add(
            FavoriteItem(
                siteId = SiteId.R34V,
                targetId = "v1",
                kind = FavoriteKind.VIDEO,
                title = "Video 1",
                url = "https://rule34video.com/video/1/",
                addedAt = 300L,
            ),
        )
        harness.downloadStateRepository.upsert(
            DownloadItem(
                id = "nhentai:c1",
                siteId = SiteId.NHENTAI,
                mediaId = "c1",
                url = "https://nhentai.net/g/1/",
                kind = DownloadKind.COMIC,
                title = "Comic 1",
                status = DownloadStatus.COMPLETED,
                downloadedBytes = 2_048L,
                totalBytes = 4_096L,
                updatedAt = 400L,
            ),
        )
        harness.mediaTagCacheRepository.upsertTags(
            siteId = SiteId.R34V,
            mediaId = "v1",
            tags = listOf(TagRef(slug = "blonde", label = "blonde")),
        )
        harness.mediaTagCacheRepository.upsertTags(
            siteId = SiteId.NHENTAI,
            mediaId = "c1",
            tags = listOf(TagRef(slug = "blonde", label = "blonde")),
        )

        val vm = StatsViewModel(
            historyRepository = harness.historyRepository,
            favoriteRepository = harness.favoriteRepository,
            shelfRepository = harness.shelfRepository,
            downloadGateway = harness.downloadGateway,
            mediaTagCacheRepository = harness.mediaTagCacheRepository,
            siteRegistry = harness.siteRegistry,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val stats = expectMostRecentItem()
            assertEquals(120_000L, stats.totalWatchDurationMs)
            assertEquals(1, stats.totalWatchedCount)
            assertEquals(1, stats.completedWatchCount)
            assertEquals(15, stats.totalReadPages)
            assertEquals(1, stats.totalReadCount)
            assertEquals(0, stats.completedReadCount)
            assertEquals(1, stats.favoriteCount)
            assertEquals(1, stats.downloadCompletedCount)
            assertEquals(4_096L, stats.downloadTotalBytes)
            assertEquals(2, stats.siteBreakdowns.size)
            assertTrue(stats.topTags.any { it.slug == "blonde" && it.count == 2 })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
