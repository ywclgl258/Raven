package com.raven.app.core.network

import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostPacerTest {

    private val r34Descriptor = SiteDescriptor(
        id = SiteId.R34V,
        displayName = "Rule34Video",
        primaryKind = MediaKind.VIDEO,
        defaultHost = "rule34video.com",
        networkProfile = SiteNetworkProfile(
            pageIntervalMs = 400L,
            imageIntervalMs = 150L,
            mediaDomains = setOf("cdn.example.com"),
        ),
    )

    @Test
    fun `staggers starts by gap and catches up immediately after idle`() = runTest {
        val pacer = HostPacer(
            clockMs = { currentTime },
            delayFn = { ms -> delay(ms) },
        )

        advanceTimeBy(1_000L)
        assertEquals(0L, pacer.pace("rule34video.com", 400L))
        assertEquals(1_000L, currentTime)

        // Second request immediately after -> waits full 400ms
        val waited2 = pacer.pace("rule34video.com", 400L)
        assertEquals(400L, waited2)
        assertEquals(1_400L, currentTime)

        // Arriving after idle period -> 0 wait
        advanceTimeBy(5_000L)
        assertEquals(0L, pacer.pace("rule34video.com", 400L))
    }

    @Test
    fun `concurrent coroutines reserve unique staggered slots without blocking threads`() = runTest {
        val pacer = HostPacer(
            clockMs = { currentTime },
            delayFn = { ms -> delay(ms) },
        )

        val waitResults = (1..5).map {
            async { pacer.pace("rule34video.com", 100L) }
        }.awaitAll()

        assertEquals(listOf(0L, 100L, 200L, 300L, 400L), waitResults.sorted())
        assertEquals(400L, currentTime)
    }

    @Test
    fun `cancelling a waiting coroutine aborts cleanly without blocking peers`() = runTest {
        val pacer = HostPacer(
            clockMs = { currentTime },
            delayFn = { ms -> delay(ms) },
        )

        // Slot 0 consumed immediately
        assertEquals(0L, pacer.pace("rule34video.com", 400L))

        var cancelledCaught = false
        val job2 = launch {
            try {
                pacer.pace("rule34video.com", 400L) // waits 400ms
            } catch (_: CancellationException) {
                cancelledCaught = true
            }
        }
        val deferred3 = async {
            pacer.pace("rule34video.com", 400L) // waits 800ms
        }

        runCurrent()
        job2.cancel()
        runCurrent()
        assertTrue(cancelledCaught)

        assertEquals(800L, deferred3.await())
        assertEquals(800L, currentTime)
    }

    @Test
    fun `paces page and image lanes on registered hosts but bypasses CDNs media and probes`() = runTest {
        val pacer = HostPacer(
            clockMs = { currentTime },
            delayFn = { ms -> delay(ms) },
        )

        // Page request reserves 400ms gap
        assertEquals(0L, pacer.paceForRequest("rule34video.com", "/latest-updates/", SiteRequestKind.PAGE, r34Descriptor))
        // Immediate image on same host waits 400ms (behind page), then sets next slot +150ms
        assertEquals(400L, pacer.paceForRequest("rule34video.com", "/thumbs/cover.webp", SiteRequestKind.IMAGE, r34Descriptor))
        // Next image waits 150ms
        assertEquals(150L, pacer.paceForRequest("rule34video.com", "/thumbs/cover2.jpg", SiteRequestKind.PAGE, r34Descriptor))

        // External CDN, PROBE, and MEDIA never wait
        assertEquals(0L, pacer.paceForRequest("cdn.example.com", "/video.mp4", SiteRequestKind.IMAGE, r34Descriptor))
        assertEquals(0L, pacer.paceForRequest("rule34video.com", "/latest-updates/", SiteRequestKind.PROBE, r34Descriptor))
        assertEquals(0L, pacer.paceForRequest("rule34video.com", "/get-file/1/", SiteRequestKind.MEDIA, r34Descriptor))
        assertEquals(0L, pacer.paceForRequest("api.github.com", "/releases/latest", SiteRequestKind.API, null))
    }

    @Test
    fun `distinct hosts pace independently`() = runTest {
        val pacer = HostPacer(
            clockMs = { currentTime },
            delayFn = { ms -> delay(ms) },
        )

        assertEquals(0L, pacer.pace("rule34video.com", 400L))
        assertEquals(0L, pacer.pace("hanime1.me", 400L))
        assertEquals(0L, currentTime)
    }
}
