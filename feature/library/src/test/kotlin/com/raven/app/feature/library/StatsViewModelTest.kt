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
import org.junit.Assert.assertFalse
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
    fun `starts loading until repository flows publish the first aggregate`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("stats_loading"))
        val vm = statsViewModel(harness)

        vm.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.isLoading)
            assertEquals(0, initial.totalWatchedCount)

            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertFalse(loaded.isLoading)
            assertEquals(0, loaded.totalWatchDurationMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

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

        val vm = statsViewModel(harness)

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val stats = expectMostRecentItem()
            assertFalse(stats.isLoading)
            assertTrue(stats.hasAnyData)
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

            val videoSite = stats.siteBreakdowns.single { it.siteId == SiteId.R34V }
            assertEquals(1, videoSite.watchedCount)
            assertEquals(120_000L, videoSite.watchDurationMs)
            assertEquals(0, videoSite.readCount)
            assertEquals(0, videoSite.readPages)
            assertEquals(1, videoSite.favoriteCount)
            assertEquals(0, videoSite.completedDownloads)

            val readingSite = stats.siteBreakdowns.single { it.siteId == SiteId.NHENTAI }
            assertEquals(0, readingSite.watchedCount)
            assertEquals(0, readingSite.watchDurationMs)
            assertEquals(1, readingSite.readCount)
            assertEquals(15, readingSite.readPages)
            assertEquals(0, readingSite.favoriteCount)
            assertEquals(1, readingSite.completedDownloads)

            assertTrue(stats.topTags.any { it.slug == "blonde" && it.count == 2 })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `time range filters cross-time samples before aggregating totals tags and site details`() = runTest {
        val now = 1_700_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val harness = LibraryTestHarness(
            dispatcher = mainRule.dispatcher,
            localRootDir = tempFolder.newFolder("stats_range"),
            nowMs = now,
        )

        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "v_recent",
                url = "https://rule34video.com/video/recent/",
                title = "Recent video",
                positionMs = 30_000L,
                durationMs = 60_000L,
                updatedAt = now - 2L * day,
            ),
        )
        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "v_mid",
                url = "https://rule34video.com/video/mid/",
                title = "Mid video",
                positionMs = 300_000L,
                durationMs = 600_000L,
                updatedAt = now - 15L * day,
            ),
        )
        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "v_old",
                url = "https://rule34video.com/video/old/",
                title = "Old video",
                positionMs = 500_000L,
                durationMs = 900_000L,
                updatedAt = now - 45L * day,
                completed = true,
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "c_recent",
                url = "https://nhentai.net/g/recent/",
                title = "Recent comic",
                currentPage = 5,
                totalPages = 40,
                updatedAt = now - 3L * day,
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "c_mid",
                url = "https://nhentai.net/g/mid/",
                title = "Mid comic",
                currentPage = 20,
                totalPages = 50,
                updatedAt = now - 20L * day,
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "c_old",
                url = "https://nhentai.net/g/old/",
                title = "Old comic",
                currentPage = 99,
                totalPages = 100,
                updatedAt = now - 60L * day,
                completed = true,
            ),
        )
        harness.favoriteRepository.add(
            FavoriteItem(
                siteId = SiteId.R34V,
                targetId = "v_recent",
                kind = FavoriteKind.VIDEO,
                title = "Recent video",
                url = "https://rule34video.com/video/recent/",
                addedAt = now - 4L * day,
            ),
        )
        harness.favoriteRepository.add(
            FavoriteItem(
                siteId = SiteId.NHENTAI,
                targetId = "c_old",
                kind = FavoriteKind.COMIC,
                title = "Old comic",
                url = "https://nhentai.net/g/old/",
                addedAt = now - 50L * day,
            ),
        )
        harness.downloadStateRepository.upsert(
            DownloadItem(
                id = "nhentai:c_recent",
                siteId = SiteId.NHENTAI,
                mediaId = "c_recent",
                url = "https://nhentai.net/g/recent/",
                kind = DownloadKind.COMIC,
                title = "Recent comic",
                status = DownloadStatus.COMPLETED,
                downloadedBytes = 4_096L,
                totalBytes = 4_096L,
                updatedAt = now - 5L * day,
            ),
        )
        harness.downloadStateRepository.upsert(
            DownloadItem(
                id = "nhentai:c_old",
                siteId = SiteId.NHENTAI,
                mediaId = "c_old",
                url = "https://nhentai.net/g/old/",
                kind = DownloadKind.COMIC,
                title = "Old comic",
                status = DownloadStatus.COMPLETED,
                downloadedBytes = 8_192L,
                totalBytes = 8_192L,
                updatedAt = now - 40L * day,
            ),
        )
        harness.downloadStateRepository.upsert(
            DownloadItem(
                id = "r34v:v_recent",
                siteId = SiteId.R34V,
                mediaId = "v_recent",
                url = "https://rule34video.com/video/recent/",
                kind = DownloadKind.VIDEO,
                title = "Recent video",
                status = DownloadStatus.QUEUED,
                downloadedBytes = 1_024L,
                totalBytes = 2_048L,
                updatedAt = now - day,
            ),
        )
        harness.mediaTagCacheRepository.upsertTags(
            siteId = SiteId.R34V,
            mediaId = "v_recent",
            tags = listOf(TagRef(slug = "blonde", label = "blonde")),
        )
        harness.mediaTagCacheRepository.upsertTags(
            siteId = SiteId.R34V,
            mediaId = "v_mid",
            tags = listOf(TagRef(slug = "blonde", label = "blonde")),
        )
        harness.mediaTagCacheRepository.upsertTags(
            siteId = SiteId.R34V,
            mediaId = "v_old",
            tags = listOf(TagRef(slug = "legacy", label = "legacy")),
        )

        val vm = statsViewModel(harness)

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val all = expectMostRecentItem()
            assertEquals(StatsTimeRange.ALL, all.selectedRange)
            assertEquals(830_000L, all.totalWatchDurationMs)
            assertEquals(3, all.totalWatchedCount)
            assertEquals(1, all.completedWatchCount)
            assertEquals(124, all.totalReadPages)
            assertEquals(3, all.totalReadCount)
            assertEquals(1, all.completedReadCount)
            assertEquals(2, all.favoriteCount)
            assertEquals(2, all.downloadCompletedCount)
            assertEquals(12_288L, all.downloadTotalBytes)
            assertEquals(2, all.topTags.size)
            assertTrue(all.topTags.any { it.slug == "blonde" && it.count == 2 })
            assertTrue(all.topTags.any { it.slug == "legacy" && it.count == 1 })

            vm.selectRange(StatsTimeRange.LAST_7_DAYS)
            advanceUntilIdle()
            val lastWeek = expectMostRecentItem()
            assertEquals(StatsTimeRange.LAST_7_DAYS, lastWeek.selectedRange)
            assertTrue(lastWeek.hasAnyData)
            assertEquals(30_000L, lastWeek.totalWatchDurationMs)
            assertEquals(1, lastWeek.totalWatchedCount)
            assertEquals(0, lastWeek.completedWatchCount)
            assertEquals(5, lastWeek.totalReadPages)
            assertEquals(1, lastWeek.totalReadCount)
            assertEquals(0, lastWeek.completedReadCount)
            assertEquals(1, lastWeek.favoriteCount)
            assertEquals(1, lastWeek.downloadCompletedCount)
            assertEquals(4_096L, lastWeek.downloadTotalBytes)
            assertEquals(1, lastWeek.topTags.size)
            assertEquals("blonde", lastWeek.topTags.single().slug)
            assertEquals(1, lastWeek.topTags.single().count)

            val weekVideoSite = lastWeek.siteBreakdowns.single { it.siteId == SiteId.R34V }
            assertEquals(1, weekVideoSite.watchedCount)
            assertEquals(30_000L, weekVideoSite.watchDurationMs)
            assertEquals(0, weekVideoSite.readPages)
            assertEquals(1, weekVideoSite.favoriteCount)
            assertEquals(0, weekVideoSite.completedDownloads)

            val weekReadingSite = lastWeek.siteBreakdowns.single { it.siteId == SiteId.NHENTAI }
            assertEquals(0, weekReadingSite.watchedCount)
            assertEquals(0, weekReadingSite.watchDurationMs)
            assertEquals(1, weekReadingSite.readCount)
            assertEquals(5, weekReadingSite.readPages)
            assertEquals(0, weekReadingSite.favoriteCount)
            assertEquals(1, weekReadingSite.completedDownloads)

            vm.selectRange(StatsTimeRange.LAST_30_DAYS)
            advanceUntilIdle()
            val lastMonth = expectMostRecentItem()
            assertEquals(StatsTimeRange.LAST_30_DAYS, lastMonth.selectedRange)
            assertEquals(330_000L, lastMonth.totalWatchDurationMs)
            assertEquals(2, lastMonth.totalWatchedCount)
            assertEquals(25, lastMonth.totalReadPages)
            assertEquals(2, lastMonth.totalReadCount)
            assertEquals(1, lastMonth.favoriteCount)
            assertEquals(1, lastMonth.downloadCompletedCount)
            assertEquals(4_096L, lastMonth.downloadTotalBytes)
            assertEquals(2, lastMonth.topTags.single { it.slug == "blonde" }.count)
            assertFalse(lastMonth.topTags.any { it.slug == "legacy" })

            val monthVideoSite = lastMonth.siteBreakdowns.single { it.siteId == SiteId.R34V }
            assertEquals(2, monthVideoSite.watchedCount)
            assertEquals(330_000L, monthVideoSite.watchDurationMs)
            assertEquals(0, monthVideoSite.readCount)
            assertEquals(0, monthVideoSite.readPages)
            assertEquals(1, monthVideoSite.favoriteCount)
            assertEquals(0, monthVideoSite.completedDownloads)

            val monthReadingSite = lastMonth.siteBreakdowns.single { it.siteId == SiteId.NHENTAI }
            assertEquals(0, monthReadingSite.watchedCount)
            assertEquals(0, monthReadingSite.watchDurationMs)
            assertEquals(2, monthReadingSite.readCount)
            assertEquals(25, monthReadingSite.readPages)
            assertEquals(0, monthReadingSite.favoriteCount)
            assertEquals(1, monthReadingSite.completedDownloads)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `keeps any-data signal when the selected range excludes all local samples`() = runTest {
        val now = 1_700_000_000_000L
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("stats_range_empty"))
        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "v_old",
                url = "https://rule34video.com/video/old/",
                title = "Old video",
                positionMs = 200_000L,
                durationMs = 400_000L,
                updatedAt = now - 90L * 24L * 60L * 60L * 1000L,
            ),
        )

        val vm = statsViewModel(harness)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val all = expectMostRecentItem()
            assertEquals(1, all.totalWatchedCount)

            vm.selectRange(StatsTimeRange.LAST_7_DAYS)
            advanceUntilIdle()
            val filtered = expectMostRecentItem()
            assertTrue(filtered.hasAnyData)
            assertEquals(0, filtered.totalWatchedCount)
            assertEquals(0L, filtered.totalWatchDurationMs)
            assertTrue(filtered.siteBreakdowns.isEmpty())
            assertTrue(filtered.topTags.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun statsViewModel(harness: LibraryTestHarness): StatsViewModel = StatsViewModel(
        historyRepository = harness.historyRepository,
        favoriteRepository = harness.favoriteRepository,
        shelfRepository = harness.shelfRepository,
        downloadGateway = harness.downloadGateway,
        mediaTagCacheRepository = harness.mediaTagCacheRepository,
        siteRegistry = harness.siteRegistry,
        clock = harness.clock,
    )
}
