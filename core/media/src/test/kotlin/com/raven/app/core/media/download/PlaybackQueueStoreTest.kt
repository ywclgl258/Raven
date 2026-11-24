package com.raven.app.core.media.download

import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
}
