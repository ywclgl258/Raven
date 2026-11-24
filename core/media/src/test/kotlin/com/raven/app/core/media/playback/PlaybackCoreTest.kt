@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.playback

import androidx.media3.common.MimeTypes
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.VideoStream
import com.raven.app.core.sitespi.MediaTransportHook
import com.raven.app.core.sitespi.RouPngProtocol
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoreTest {

    private val standardStreams = listOf(
        VideoStream(quality = "1080p", label = "1080p Full HD", url = "https://cdn.example/1080.mp4"),
        VideoStream(quality = "720p", label = "720p HD", url = "https://cdn.example/720.mp4"),
        VideoStream(quality = "480p", label = "480p SD", url = "https://cdn.example/480.mp4"),
        VideoStream(quality = "360p", label = "360p", url = "https://cdn.example/360.mp4"),
    )

    // ---- StreamSelector ----

    @Test
    fun `stream selector respects preferred quality when bandwidth unknown`() {
        val picked1080 = StreamSelector.pick(standardStreams, "1080p", bandwidthKbps = 0)
        assertEquals("1080p", picked1080.quality)

        // Default "auto" falls back to 720p ceiling without mutating preference.
        val pickedAuto = StreamSelector.pick(standardStreams, "auto", bandwidthKbps = 0)
        assertEquals("720p", pickedAuto.quality)
    }

    @Test
    fun `stream selector applies 0_7 headroom without mutating preference`() {
        // 4000 kbps * 0.7 = 2800 kbps budget -> supports 720p (2500), not 1080p (4500)
        val picked = StreamSelector.pick(standardStreams, "1080p", bandwidthKbps = 4_000)
        assertEquals("720p", picked.quality)

        // 1800 kbps * 0.7 = 1260 kbps budget -> supports 480p (1200)
        val pickedMid = StreamSelector.pick(standardStreams, "1080p", bandwidthKbps = 1_800)
        assertEquals("480p", pickedMid.quality)

        // Extremely slow link (300 kbps -> 210 kbps budget) still returns lowest tier (360p)
        val pickedLow = StreamSelector.pick(standardStreams, "1080p", bandwidthKbps = 300)
        assertEquals("360p", pickedLow.quality)

        // Sufficient bandwidth (7000 * 0.7 = 4900 >= 4500) keeps 1080p
        val pickedHigh = StreamSelector.pick(standardStreams, "1080p", bandwidthKbps = 7_000)
        assertEquals("1080p", pickedHigh.quality)
    }

    @Test
    fun `stream selector handles 4K parsing, afterRefresh, lower, and mergeEstimates`() {
        val streams = listOf(
            VideoStream(quality = "4K", label = "4K UHD", url = "https://cdn.example/4k.mp4"),
            VideoStream(quality = "1080p", label = "1080p", url = "https://cdn.example/1080.mp4"),
        )
        assertEquals(2160, StreamSelector.extractHeight(streams[0]))
        assertEquals(18_000, StreamSelector.requiredKbps(streams[0]))
        assertEquals("1080p", StreamSelector.lower(streams, streams[0])?.quality)
        assertNull(StreamSelector.lower(streams, streams[1]))

        val refreshed = StreamSelector.afterRefresh(
            streams = listOf(
                VideoStream(quality = "1080p", label = "1080p", url = "https://cdn.example/1080-new.mp4"),
                VideoStream(quality = "720p", label = "720p", url = "https://cdn.example/720-new.mp4"),
            ),
            previous = streams[1],
            bandwidthKbps = 2_000,
        )
        assertEquals("https://cdn.example/1080-new.mp4", refreshed.url)

        // Observed playback sample overrides probe seed
        assertEquals(5_000, StreamSelector.mergeEstimates(sampledKbps = 5_000, seededKbps = 1_200))
        assertEquals(1_200, StreamSelector.mergeEstimates(sampledKbps = 0, seededKbps = 1_200))
        assertEquals(0, StreamSelector.mergeEstimates(0, 0))
    }

    // ---- BufferPlanner ----

    @Test
    fun `buffer planner produces distinct signatures for high, 720p, and strained low-net`() {
        val high = BufferPlanner.plan(streamKbps = 4_500, linkKbps = 10_000)
        assertEquals("8000/40000/500/800", high.signature)

        val std720 = BufferPlanner.plan(streamKbps = 2_500, linkKbps = 5_000)
        assertEquals("4000/15000/500/1000", std720.signature)

        // 3000 kbps * 0.7 = 2100 < 2500 -> strained 720p tightens to low-net buffer
        val strained720 = BufferPlanner.plan(streamKbps = 2_500, linkKbps = 3_000)
        assertEquals("2500/12000/500/1000", strained720.signature)

        val lowStream = BufferPlanner.plan(streamKbps = 1_200, linkKbps = 0)
        assertEquals("2500/12000/500/1000", lowStream.signature)

        assertNotEquals(high.signature, std720.signature)
        assertNotEquals(std720.signature, strained720.signature)
    }

    // ---- StallPolicy & StallTracker ----

    @Test
    fun `stall policy triggers on 6s startup stall, 8s mid-play stall, or 3 stalls in 30s`() {
        // Startup stall (>=6s when position < 5s)
        assertFalse(StallPolicy.shouldDowngrade(bufferingMs = 5_999L, positionMs = 1_000L, stallsInWindow = 1))
        assertEquals(
            StallReason.STARTUP_STALL,
            StallPolicy.evaluateReason(bufferingMs = 6_000L, positionMs = 4_999L, stallsInWindow = 1),
        )
        // At 6s mid-play (>=5s), not enough yet; needs 8s
        assertFalse(StallPolicy.shouldDowngrade(bufferingMs = 6_000L, positionMs = 10_000L, stallsInWindow = 1))
        assertEquals(
            StallReason.MID_PLAY_STALL,
            StallPolicy.evaluateReason(bufferingMs = 8_000L, positionMs = 10_000L, stallsInWindow = 1),
        )
        // Repeated stalls >= 3 in 30s window
        assertEquals(
            StallReason.REPEATED_STALLS,
            StallPolicy.evaluateReason(bufferingMs = 500L, positionMs = 20_000L, stallsInWindow = 3),
        )
    }

    @Test
    fun `stall tracker enforces 20s cooldown and max 2 downgrades per video`() {
        var now = 1_000L
        val tracker = StallTracker(clockMs = { now })

        // First downgrade via 6s startup stall
        tracker.onBufferingChanged(true, now)
        now += 6_000L
        assertEquals(StallDecision.Downgrade(StallReason.STARTUP_STALL), tracker.evaluate(positionMs = 0L, isBuffering = true, nowMs = now))
        tracker.recordDowngrade(now)
        assertEquals(1, tracker.downgradeCount)

        // During 20s cooldown, even a 10s stall must hold
        now += 5_000L
        tracker.onBufferingChanged(true, now)
        now += 10_000L // total elapsed since downgrade = 15s < 20s
        assertEquals(StallDecision.Hold, tracker.evaluate(positionMs = 12_000L, isBuffering = true, nowMs = now))

        // After 20s cooldown expires, 3 stalls within 30s triggers second downgrade
        now += 10_000L // 25s since downgrade
        tracker.onBufferingChanged(false, now)
        tracker.onBufferingChanged(true, now)
        tracker.onBufferingChanged(false, now + 500L)
        tracker.onBufferingChanged(true, now + 1_000L)
        tracker.onBufferingChanged(false, now + 1_500L)
        tracker.onBufferingChanged(true, now + 2_000L)
        assertEquals(
            StallDecision.Downgrade(StallReason.REPEATED_STALLS),
            tracker.evaluate(positionMs = 30_000L, isBuffering = true, nowMs = now + 2_000L),
        )
        tracker.recordDowngrade(now + 2_000L)
        assertEquals(2, tracker.downgradeCount)

        // Third downgrade is strictly blocked (max 2 downgrades per video)
        now += 60_000L
        tracker.onBufferingChanged(true, now)
        now += 15_000L
        assertEquals(StallDecision.Hold, tracker.evaluate(positionMs = 50_000L, isBuffering = true, nowMs = now))

        // Reset clears counters for next video
        tracker.reset()
        assertEquals(0, tracker.downgradeCount)
    }

    // ---- LinkSpeedEstimator ----

    @Test
    fun `link speed estimator invalidates on routeEpoch change and respects TTL and sample precedence`() {
        var epoch = 1L
        var now = 10_000L
        val estimator = LinkSpeedEstimator(
            context = null,
            routeEpochProvider = { epoch },
            clockMs = { now },
        )

        assertFalse(estimator.isKnown())
        // Stale epoch seed rejected
        assertFalse(estimator.seed(3_000, measuredOnEpoch = 0L))
        // Current epoch seed accepted
        assertTrue(estimator.seed(3_000, measuredOnEpoch = 1L))
        assertEquals(3_000, estimator.estimateKbps())

        // Sub-16KiB transfer ignored
        assertFalse(estimator.recordSample(bytes = 4_096L, elapsedMs = 10L, measuredOnEpoch = 1L))
        assertEquals(3_000, estimator.estimateKbps())

        // Valid transfer sample (100_000 bytes in 100 ms = 8_000 kbps) overrides seed
        assertTrue(estimator.recordSample(bytes = 100_000L, elapsedMs = 100L, measuredOnEpoch = 1L))
        assertEquals(8_000, estimator.estimateKbps())

        // Route epoch change immediately wipes estimates
        epoch = 2L
        assertEquals(0, estimator.estimateKbps())
        assertFalse(estimator.isKnown())

        // 5-minute TTL expiration
        assertTrue(estimator.seed(4_500, measuredOnEpoch = 2L))
        assertEquals(4_500, estimator.estimateKbps())
        now += LinkSpeedEstimator.ESTIMATE_TTL_MS + 1L
        assertEquals(0, estimator.estimateKbps())
    }

    // ---- VideoLoopRange & PiP Aspect Ratio ----

    @Test
    fun `video loop range enforces 1s minimum and evaluates repeat boundaries`() {
        val pending = VideoLoopRange.NONE.withStart(10_000L)
        assertTrue(pending.hasStart)
        assertFalse(pending.isActive)

        var threw = false
        try {
            pending.withEnd(10_500L)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)

        val active = pending.withEnd(15_000L)
        assertTrue(active.isActive)
        assertTrue(active.contains(10_000L))
        assertTrue(active.contains(14_999L))
        assertFalse(active.contains(15_000L))
        assertFalse(active.shouldRepeat(14_999L, isPlaying = true))
        assertFalse(active.shouldRepeat(15_000L, isPlaying = false))
        assertTrue(active.shouldRepeat(15_000L, isPlaying = true))
        assertTrue(active.isValidFor(30_000L))
        assertFalse(active.isValidFor(12_000L))
    }

    @Test
    fun `pip aspect ratio clamps extreme aspect ratios to Android PiP bounds`() {
        assertNull(PlayerSessionController.computePipAspectRatio(0, 1080))
        val normal = PlayerSessionController.computePipAspectRatio(1920, 1080)
        assertNotNull(normal)
        assertEquals(1778, normal!!.first)
        assertEquals(1000, normal.second)

        val ultraWide = PlayerSessionController.computePipAspectRatio(4000, 1000)!!
        assertEquals(2390, ultraWide.first)

        val tallVertical = PlayerSessionController.computePipAspectRatio(500, 3000)!!
        assertEquals(419, tallVertical.first)
    }

    // ---- MediaTransportDataSourceFactory & RouPngProtocol bridge ----

    @Test
    fun `media transport factory resolves rou video HLS mime hint and unwrapping hook via SPI`() {
        val rouPlugin = object : SitePlugin {
            override val descriptor = SiteDescriptor(
                id = SiteId.ROUVIDEO,
                displayName = "rou.video",
                primaryKind = MediaKind.VIDEO,
                defaultHost = "rou.video",
            )
            override val mediaTransportHook: MediaTransportHook = RouPngProtocol.transportHook
        }
        val factory = MediaTransportDataSourceFactory(
            mediaClient = OkHttpClient(),
            siteRegistryProvider = { SiteRegistry(listOf(rouPlugin)) },
        )

        val mime = factory.resolveMimeTypeHint(SiteId.ROUVIDEO, "https://rou.video/api/hls/abc123")
        assertEquals(MimeTypes.APPLICATION_M3U8, mime)

        val hook = factory.resolveTransportHook(null, "https://rou.video/api/hls/abc123")
        assertTrue(hook.requiresFullResponseUnwrap("https://rou.video/api/hls/abc123"))

        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:10\nseg0.png\n#EXT-X-ENDLIST\n".toByteArray()
        val wrapped = RouPngProtocol.wrap(manifest, deflate = true)
        val unwrapped = hook.unwrapMediaBytes("https://rou.video/api/hls/abc123", wrapped)
        assertNotNull(unwrapped)
        assertEquals("application/vnd.apple.mpegurl", unwrapped!!.contentType)
        assertTrue(manifest.contentEquals(unwrapped.bytes))
    }
}
