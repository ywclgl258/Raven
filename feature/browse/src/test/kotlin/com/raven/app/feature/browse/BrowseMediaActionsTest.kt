package com.raven.app.feature.browse

import app.cash.turbine.test
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests the shared long-press favorite/download controller: favorite toggle persistence, download
 * duplicate suppression, and the resulting user-facing messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseMediaActionsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class RecordingEnqueuer : BrowseDownloadEnqueuer {
        val enqueued = mutableListOf<String>()
        override suspend fun enqueue(item: com.raven.app.core.model.MediaItem) {
            enqueued += item.id
        }
    }

    @Test
    fun `toggle favorite persists the work favorite and reports added then removed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val enqueuer = RecordingEnqueuer()
            val actions = BrowseMediaActions(
                favoriteRepository = harness.favoriteRepository,
                downloadStateRepository = harness.downloadStateRepository,
                enqueuer = enqueuer,
                scope = this,
                ioDispatcher = mainDispatcherRule.dispatcher,
                clockMs = harness.clock,
            )
            val item = sampleMediaItem(SiteId.NHENTAI, "w1", MediaKind.COMIC, title = "Work One")

            actions.toggleFavorite(item)
            advanceUntilIdle()

            assertEquals(
                FavoriteKind.COMIC,
                harness.favoriteDao.getAll().single().toDomain().kind,
            )
            assertEquals("已收藏「Work One」", actions.messages.value)

            actions.toggleFavorite(item)
            advanceUntilIdle()

            assertTrue(harness.favoriteDao.getAll().isEmpty())
            assertEquals("已取消收藏「Work One」", actions.messages.value)
        }

    @Test
    fun `download enqueues once and refuses duplicate while active`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val enqueuer = RecordingEnqueuer()
            val actions = BrowseMediaActions(
                favoriteRepository = harness.favoriteRepository,
                downloadStateRepository = harness.downloadStateRepository,
                enqueuer = enqueuer,
                scope = this,
                ioDispatcher = mainDispatcherRule.dispatcher,
                clockMs = harness.clock,
            )
            val item = sampleMediaItem(SiteId.NHENTAI, "w2", MediaKind.VIDEO, title = "Work Two")

            actions.download(item)
            advanceUntilIdle()
            assertEquals(listOf("w2"), enqueuer.enqueued)
            assertTrue(actions.messages.value!!.startsWith("已加入视频下载队列"))

            // Simulate an queued row appearing so the next request is considered duplicate.
            harness.downloadDao.upsert(
                DownloadItem(
                    id = "nhentai:w2",
                    siteId = SiteId.NHENTAI,
                    mediaId = "w2",
                    url = item.url,
                    title = item.title,
                    kind = DownloadKind.VIDEO,
                    status = DownloadStatus.QUEUED,
                ).toEntity(),
            )
            actions.download(item)
            advanceUntilIdle()
            assertEquals("should not enqueue twice", 1, enqueuer.enqueued.size)
            assertTrue(actions.messages.value!!.contains("已在下载队列中"))
        }

    @Test
    fun `download reports completed items without re-enqueueing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val enqueuer = RecordingEnqueuer()
            val actions = BrowseMediaActions(
                favoriteRepository = harness.favoriteRepository,
                downloadStateRepository = harness.downloadStateRepository,
                enqueuer = enqueuer,
                scope = this,
                ioDispatcher = mainDispatcherRule.dispatcher,
                clockMs = harness.clock,
            )
            val item = sampleMediaItem(SiteId.NHENTAI, "w3", MediaKind.PHOTO, title = "Work Three")

            harness.downloadDao.upsert(
                DownloadItem(
                    id = "nhentai:w3",
                    siteId = SiteId.NHENTAI,
                    mediaId = "w3",
                    url = item.url,
                    title = item.title,
                    kind = DownloadKind.PHOTO,
                    status = DownloadStatus.COMPLETED,
                ).toEntity(),
            )
            actions.download(item)
            advanceUntilIdle()

            assertTrue(enqueuer.enqueued.isEmpty())
            assertTrue(actions.messages.value!!.contains("已下载完成"))
        }

    @Test
    fun `download states collapse to active and completed flags`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val actions = BrowseMediaActions(
                favoriteRepository = harness.favoriteRepository,
                downloadStateRepository = DownloadStateRepository(harness.downloadDao, harness.clock),
                enqueuer = RecordingEnqueuer(),
                scope = this,
                ioDispatcher = mainDispatcherRule.dispatcher,
                clockMs = harness.clock,
            )
            harness.downloadDao.upsert(
                DownloadItem(
                    id = "nhentai:w4",
                    siteId = SiteId.NHENTAI,
                    mediaId = "w4",
                    url = "u",
                    title = "t",
                    kind = DownloadKind.COMIC,
                    status = DownloadStatus.DOWNLOADING,
                ).toEntity(),
            )

            actions.downloadStatesFlow(SiteId.NHENTAI).test {
                val state = awaitItem()["nhentai:w4"]
                assertEquals(MediaCardDownloadState(isActive = true, isCompleted = false), state)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `resolve download kind supports video and gallery kinds`() {
        assertEquals(MediaCardDownloadKind.VIDEO, resolveMediaCardDownloadKind(MediaKind.VIDEO))
        assertEquals(MediaCardDownloadKind.GALLERY, resolveMediaCardDownloadKind(MediaKind.COMIC))
        assertEquals(MediaCardDownloadKind.GALLERY, resolveMediaCardDownloadKind(MediaKind.SERIAL_COMIC))
        assertEquals(MediaCardDownloadKind.GALLERY, resolveMediaCardDownloadKind(MediaKind.PHOTO))
        assertFalse(MediaCardDownloadKind.VIDEO == MediaCardDownloadKind.GALLERY)
    }
}
