package com.raven.app.core.media.download

import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class PlaybackQueueStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleItem(id: String, siteId: SiteId = SiteId.R34V, kind: MediaKind = MediaKind.VIDEO): MediaItem =
        MediaItem(
            siteId = siteId,
            id = id,
            url = "https://example.test/$id",
            kind = kind,
            title = "Item $id",
        )

    @Test
    fun `queue store creates UUID token session, deduplicates, advances, restores, and ends`() {
        var counter = 0
        val store = PlaybackQueueStore(
            persistence = InMemoryPlaybackQueuePersistence(),
            tokenGenerator = { "tok-${++counter}" },
        )

        val item1 = sampleItem("1")
        val item2 = sampleItem("2")
        val item3 = sampleItem("3", siteId = SiteId.NHENTAI, kind = MediaKind.COMIC)

        val started = store.start(
            title = "Watch Later",
            shelfId = "watch-later",
            items = listOf(item1, item1, item2, item3),
            offlineOnly = false,
        )
        assertEquals("tok-1", started.token)
        assertEquals(3, started.items.size)
        assertEquals(item1, started.currentItem)
        assertEquals(item2, started.nextItem)
        assertEquals(2, started.remainingCount)

        // Wrong token or wrong item fails validation
        assertNull(store.sessionFor("wrong", SiteId.R34V, "1"))
        assertNull(store.sessionFor("tok-1", SiteId.R34V, "2"))
        assertNotNull(store.sessionFor("tok-1", SiteId.R34V, "1"))

        // Advance from item1 -> item2
        val step2 = store.advance("tok-1", SiteId.R34V, "1")
        assertNotNull(step2)
        assertEquals(1, step2!!.currentIndex)
        assertEquals(item2, step2.currentItem)

        // Rollback via restore()
        store.restore(previous = started, replacement = step2)
        assertEquals(0, store.snapshot()!!.currentIndex)

        // Advance to end
        val at1 = store.advance("tok-1", SiteId.R34V, "1")!!
        val at2 = store.advance("tok-1", SiteId.R34V, "2")!!
        assertFalse(at2.hasNext)
        assertNull(store.advance("tok-1", SiteId.NHENTAI, "3"))

        store.endIfCurrent("tok-1", SiteId.NHENTAI, "3")
        assertNull(store.snapshot())
    }

    @Test
    fun `queue store filters offline-only sessions and persists atomically to disk`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queueFile = File(tempFolder.newFolder("queue"), "playback-queue.json")
        val store = PlaybackQueueStore(
            persistence = AtomicFilePlaybackQueuePersistence(queueFile),
            dispatchers = CustomAppDispatchers(dispatcher),
        )

        val ready1 = sampleItem("a")
        val onlineOnly = sampleItem("b")
        val ready2 = sampleItem("c")

        val result = store.startOfflineFiltered(
            title = "Offline Flight",
            shelfId = "custom-1",
            candidates = listOf(ready1, onlineOnly, ready2),
            isOfflineReady = { it.id != "b" },
        )

        assertEquals(1, result.skippedCount)
        assertTrue(result.session.offlineOnly)
        assertEquals(listOf("a", "c"), result.session.items.map { it.id })

        // Fresh store instance reloads persisted session cleanly
        val reloaded = PlaybackQueueStore(
            persistence = AtomicFilePlaybackQueuePersistence(queueFile),
            dispatchers = CustomAppDispatchers(dispatcher),
        )
        assertEquals(result.session, reloaded.snapshot())

        reloaded.end(result.session.token)
        assertFalse(queueFile.exists())
    }

    @Test
    fun `queue store persists asynchronously via applicationScope without blocking caller`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queueFile = File(tempFolder.newFolder("queue-async"), "playback-queue.json")
        val store = PlaybackQueueStore(
            persistence = AtomicFilePlaybackQueuePersistence(queueFile),
            dispatchers = CustomAppDispatchers(dispatcher),
            applicationScope = this,
        )

        val started = store.start(
            title = "Async Shelf",
            shelfId = "async-1",
            items = listOf(sampleItem("x"), sampleItem("y")),
        )
        // Not flushed to disk yet on caller thread before dispatcher runs.
        assertFalse(queueFile.exists())
        advanceUntilIdle()
        assertTrue(queueFile.isFile)
        assertEquals(started, AtomicFilePlaybackQueuePersistence(queueFile).load())

        store.end(started.token)
        advanceUntilIdle()
        assertFalse(queueFile.exists())
    }

    @Test
    fun `queue store selects arbitrary history and upcoming indexes safely`() {
        val store = PlaybackQueueStore(tokenGenerator = { "select-token" })
        val items = listOf(sampleItem("1"), sampleItem("2"), sampleItem("3"))
        val started = store.start("Queue", "shelf", items)

        assertEquals(started, store.select("select-token", SiteId.R34V, "1", 0))
        assertNull(store.select("wrong", SiteId.R34V, "1", 2))
        assertNull(store.select("select-token", SiteId.R34V, "2", 2))
        assertNull(store.select("select-token", SiteId.R34V, "1", -1))
        assertNull(store.select("select-token", SiteId.R34V, "1", items.size))
        assertEquals(0, store.snapshot()?.currentIndex)

        val upcoming = store.select("select-token", SiteId.R34V, "1", 2)
        assertEquals(2, upcoming?.currentIndex)
        assertEquals("3", upcoming?.currentItem?.id)
        assertEquals(0, upcoming?.remainingCount)

        val history = store.select("select-token", SiteId.R34V, "3", 0)
        assertEquals(0, history?.currentIndex)
        assertEquals("1", history?.currentItem?.id)
        assertEquals("2", history?.nextItem?.id)
    }

    @Test
    fun `queue store removal preserves current item or explicitly selects a replacement`() {
        val store = PlaybackQueueStore(tokenGenerator = { "remove-token" })
        store.start("Queue", "shelf", listOf(sampleItem("1"), sampleItem("2"), sampleItem("3"), sampleItem("4")))
        store.advance("remove-token", SiteId.R34V, "1")
        store.advance("remove-token", SiteId.R34V, "2")

        val beforeCurrent = store.removeAt("remove-token", 0)
        assertEquals(listOf("2", "3", "4"), beforeCurrent?.items?.map { it.id })
        assertEquals(1, beforeCurrent?.currentIndex)
        assertEquals("3", beforeCurrent?.currentItem?.id)

        val afterCurrent = store.removeAt("remove-token", 2)
        assertEquals(listOf("2", "3"), afterCurrent?.items?.map { it.id })
        assertEquals(1, afterCurrent?.currentIndex)
        assertEquals("3", afterCurrent?.currentItem?.id)

        val lastCurrent = store.removeAt("remove-token", 1)
        assertEquals(listOf("2"), lastCurrent?.items?.map { it.id })
        assertEquals(0, lastCurrent?.currentIndex)
        assertEquals("2", lastCurrent?.currentItem?.id)

        assertNull(store.removeAt("remove-token", 0))
        assertNull(store.snapshot())

        val nextStore = PlaybackQueueStore(tokenGenerator = { "next-token" })
        nextStore.start("Queue", "shelf", listOf(sampleItem("1"), sampleItem("2"), sampleItem("3")))
        val nextCurrent = nextStore.removeAt("next-token", 0)
        assertEquals(listOf("2", "3"), nextCurrent?.items?.map { it.id })
        assertEquals(0, nextCurrent?.currentIndex)
        assertEquals("2", nextCurrent?.currentItem?.id)
        assertNull(nextStore.removeAt("next-token", 99))
        assertEquals(nextCurrent, nextStore.snapshot())
    }
}
