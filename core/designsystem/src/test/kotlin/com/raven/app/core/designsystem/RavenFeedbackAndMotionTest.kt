package com.raven.app.core.designsystem

import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenSoundSynthesizer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RavenFeedbackAndMotionTest {

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
}
