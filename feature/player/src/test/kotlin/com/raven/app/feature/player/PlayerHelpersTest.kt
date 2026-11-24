package com.raven.app.feature.player

import com.raven.app.core.model.SiteId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerHelpersTest {

    @Test
    fun playerLaunchArgsSerializesAndBuildsFromMediaItemCleanly() {
        val item = sampleVideoItem(SiteId.IWARA, "abc-123", title = "Demo Clip")
        val args = PlayerLaunchArgs.fromMediaItem(
            item = item,
            autoplay = false,
            offlineOnly = true,
            queueToken = "tok-99",
        )
        val encoded = Json.encodeToString(PlayerLaunchArgs.serializer(), args)
        val decoded = Json.decodeFromString(PlayerLaunchArgs.serializer(), encoded)
        assertEquals(args, decoded)
        assertEquals("iwara:abc-123", decoded.compositeKey)
    }

    @Test
    fun classifyTapZoneSplitsLeft40Center20Right40() {
        assertEquals(PlayerTapZone.LEFT_REWIND, classifyTapZone(0f))
        assertEquals(PlayerTapZone.LEFT_REWIND, classifyTapZone(0.399f))
        assertEquals(PlayerTapZone.CENTER_FULLSCREEN, classifyTapZone(0.40f))
        assertEquals(PlayerTapZone.CENTER_FULLSCREEN, classifyTapZone(0.50f))
        assertEquals(PlayerTapZone.CENTER_FULLSCREEN, classifyTapZone(0.60f))
        assertEquals(PlayerTapZone.RIGHT_FORWARD, classifyTapZone(0.601f))
        assertEquals(PlayerTapZone.RIGHT_FORWARD, classifyTapZone(1.0f))
        assertEquals(PlayerTapZone.CENTER_FULLSCREEN, classifyTapZone(Float.NaN))
    }

    @Test
    fun horizontalSeekMathScalesBySensitivityAndClampsToDuration() {
        // Half-width drag with 90s sensitivity = +45s
        val forward = computeHorizontalSeekTargetMs(
            startPositionMs = 60_000L,
            durationMs = 300_000L,
            deltaX = 500f,
            surfaceWidthPx = 1_000f,
            sensitivitySeconds = 90,
        )
        assertEquals(105_000L, forward)

        // Clamps cleanly at 0 and durationMs
        assertEquals(
            0L,
            computeHorizontalSeekTargetMs(10_000L, 300_000L, -1_000f, 1_000f, 90),
        )
        assertEquals(
            300_000L,
            computeHorizontalSeekTargetMs(280_000L, 300_000L, 1_000f, 1_000f, 90),
        )
    }

    @Test
    fun verticalRatioIncreasesWhenDraggingUpAndClampsSafely() {
        val up = computeVerticalRatio(startRatio = 0.5f, deltaY = -360f, surfaceHeightPx = 1_000f)
        assertEquals(1.0f, up, 0.001f)

        val down = computeVerticalRatio(startRatio = 0.5f, deltaY = 720f, surfaceHeightPx = 1_000f, minRatio = 0.01f)
        assertEquals(0.01f, down, 0.001f)
    }

    @Test
    fun speedAndSignedDeltaFormattersAreDeterministic() {
        assertEquals("1.0×", formatSpeedLabel(1.0f))
        assertEquals("1.25×", formatSpeedLabel(1.25f))
        assertEquals("1.5×", formatSpeedLabel(1.5f))
        assertEquals("2.0×", formatSpeedLabel(2.0f))
        assertEquals(1.25f, snapFloatToStep(1.23f, 0.25f, 0.25f, 3.0f), 0.001f)
        assertEquals("+15s", formatSignedSecondsDelta(15_000L))
        assertEquals("-10s", formatSignedSecondsDelta(-10_000L))
        assertEquals("0s", formatSignedSecondsDelta(0L))
    }
}
