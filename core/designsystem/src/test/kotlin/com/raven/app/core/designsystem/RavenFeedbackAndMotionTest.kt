package com.raven.app.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.RavenBreakpoint
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenLayout
import com.raven.app.core.designsystem.theme.RavenMaterialTier
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenSoundSynthesizer
import com.raven.app.core.designsystem.theme.RavenWindowClass
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RavenFeedbackAndMotionTest {

    @Test
    fun `harmony responsive grid maps xs sm md lg breakpoints to 2 4 8 12 columns`() {
        val xs = RavenLayout.gridSpecForWidth(280)
        assertEquals(RavenBreakpoint.XS, xs.breakpoint)
        assertEquals(2, xs.columns)
        assertEquals(12.dp, xs.margin)
        assertEquals(8.dp, xs.gutter)

        val sm = RavenLayout.gridSpecForWidth(390)
        assertEquals(RavenBreakpoint.SM, sm.breakpoint)
        assertEquals(4, sm.columns)
        assertEquals(16.dp, sm.margin)
        assertEquals(12.dp, sm.gutter)

        val md = RavenLayout.gridSpecForWidth(720)
        assertEquals(RavenBreakpoint.MD, md.breakpoint)
        assertEquals(8, md.columns)
        assertEquals(24.dp, md.margin)
        assertEquals(16.dp, md.gutter)

        val lg = RavenLayout.gridSpecForWidth(1080)
        assertEquals(RavenBreakpoint.LG, lg.breakpoint)
        assertEquals(12, lg.columns)
        assertEquals(32.dp, lg.margin)
        assertEquals(20.dp, lg.gutter)
    }

    @Test
    fun `five-tier optical materials increase monotonically from Ultra_Thin to Ultra_Thick`() {
        val specs = RavenMaterialTier.entries.map { RavenMotion.materialSpec(it) }
        for (i in 0 until specs.lastIndex) {
            assertTrue(
                "Expected topAlpha to increase across tiers",
                specs[i].topAlpha < specs[i + 1].topAlpha,
            )
            assertTrue(
                "Expected bottomAlpha to increase across tiers",
                specs[i].bottomAlpha < specs[i + 1].bottomAlpha,
            )
            assertTrue(
                "Expected blurRadius to increase across tiers",
                specs[i].blurRadius < specs[i + 1].blurRadius,
            )
        }
    }

    @Test
    fun `procedural sound synthesizer builds valid clickless RIFF WAVE buffers for every effect`() {
        for (effect in RavenFeedbackEffect.entries) {
            val pcm = RavenSoundSynthesizer.synthesizePcm16(effect)
            val wav = RavenSoundSynthesizer.synthesizeWav(effect)

            assertTrue("Expected PCM frames for $effect", pcm.size >= 32)
            assertEquals("WAV length mismatch for $effect", 44 + pcm.size * 2, wav.size)
            assertEquals('R'.code.toByte(), wav[0])
            assertEquals('I'.code.toByte(), wav[1])
            assertEquals('F'.code.toByte(), wav[2])
            assertEquals('F'.code.toByte(), wav[3])
            assertEquals('W'.code.toByte(), wav[8])
            assertEquals('A'.code.toByte(), wav[9])
            assertEquals('V'.code.toByte(), wav[10])
            assertEquals('E'.code.toByte(), wav[11])

            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("Expected audible non-zero peak for $effect, got $peak", peak > 500)
            assertTrue(
                "Expected smooth start fade for $effect, got ${pcm.first()}",
                abs(pcm.first().toInt()) < 64,
            )
            assertTrue(
                "Expected smooth tail fade for $effect, got ${pcm.last()}",
                abs(pcm.last().toInt()) < 64,
            )
        }
    }

    @Test
    fun `directional tab slide helpers compute signed direction and subtle pixel offsets`() {
        assertEquals(1, RavenMotion.computeTabSlideDirection(fromIndex = 0, toIndex = 2))
        assertEquals(-1, RavenMotion.computeTabSlideDirection(fromIndex = 3, toIndex = 1))
        assertEquals(0, RavenMotion.computeTabSlideDirection(fromIndex = 1, toIndex = 1))
        assertEquals(0, RavenMotion.computeTabSlideDirection(fromIndex = null, toIndex = 1))
        assertEquals(0, RavenMotion.computeTabSlideDirection(fromIndex = 1, toIndex = null))

        val forwardOffset = RavenMotion.computeTabSlideOffset(fullWidth = 1080, direction = 1)
        val backwardOffset = RavenMotion.computeTabSlideOffset(fullWidth = 1080, direction = -1)
        assertTrue(forwardOffset > 0)
        assertEquals(-forwardOffset, backwardOffset)
        assertEquals(0, RavenMotion.computeTabSlideOffset(fullWidth = 1080, direction = 0))
        assertEquals(0, RavenMotion.computeTabSlideOffset(fullWidth = 0, direction = 1))
    }

    @Test
    fun `harmony deceleration easing matches official friction cubic bezier 0_2 0_0 0_1 1_0`() {
        val expected = CubicBezierEasing(0.2f, 0.0f, 0.1f, 1.0f)
        for (step in 0..10) {
            val fraction = step / 10f
            assertEquals(
                "Curve mismatch at t=$fraction",
                expected.transform(fraction),
                RavenMotion.HarmonyDecelerationEasing.transform(fraction),
                0.0001f,
            )
        }
    }

    @Test
    fun `window class and modal sizing helpers match HarmonyOS multi-device rules`() {
        assertEquals(RavenWindowClass.COMPACT, RavenLayout.windowClassForWidth(360))
        assertEquals(RavenWindowClass.COMPACT, RavenLayout.windowClassForWidth(599))
        assertEquals(RavenWindowClass.MEDIUM, RavenLayout.windowClassForWidth(600))
        assertEquals(RavenWindowClass.MEDIUM, RavenLayout.windowClassForWidth(839))
        assertEquals(RavenWindowClass.EXPANDED, RavenLayout.windowClassForWidth(840))
        assertEquals(RavenWindowClass.EXPANDED, RavenLayout.windowClassForWidth(1199))
        assertEquals(RavenWindowClass.LARGE, RavenLayout.windowClassForWidth(1200))

        assertEquals(328.dp, RavenLayout.computeDialogWidth(360))
        assertEquals(400.dp, RavenLayout.computeDialogWidth(600))
        assertEquals(400.dp, RavenLayout.computeDialogWidth(1280))
        assertEquals(240.dp, RavenLayout.computeDialogWidth(240))

        assertFalse(RavenLayout.supportsCenteredSheet(390))
        assertFalse(RavenLayout.supportsCenteredSheet(599))
        assertTrue(RavenLayout.supportsCenteredSheet(600))
        assertTrue(RavenLayout.supportsCenteredSheet(1200))
    }
}
