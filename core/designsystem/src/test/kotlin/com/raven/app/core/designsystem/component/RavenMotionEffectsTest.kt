package com.raven.app.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RavenMotionEffectsTest {

    @Test
    fun `burst spark envelope is bounded and vanishes at both endpoints`() {
        assertEquals(0f, ravenBurstSparkEnvelope(-0.5f), 1e-4f)
        assertEquals(0f, ravenBurstSparkEnvelope(0f), 1e-4f)
        assertEquals(1f, ravenBurstSparkEnvelope(0.5f), 1e-4f)
        assertEquals(0f, ravenBurstSparkEnvelope(1f), 1e-4f)
        assertEquals(0f, ravenBurstSparkEnvelope(1.5f), 1e-4f)
        assertEquals(0f, ravenBurstSparkEnvelope(Float.NaN), 1e-4f)
    }

    @Test
    fun `burst spark sizes vary deterministically within a narrow bounded range`() {
        val scales = (-5..20).map(::ravenBurstSparkScale)
        assertEquals(3, scales.toSet().size)
        assertTrue(scales.all { it in 0.88f..1.12f })
        for (index in -5..20) {
            assertEquals(ravenBurstSparkScale(index), ravenBurstSparkScale(index + 3), 0f)
        }
    }

    @Test
    fun `active burst coalesces retrigger and completed burst can restart`() {
        assertFalse(shouldRestartBurst(isRunning = true, progress = 0.2f))
        assertFalse(shouldRestartBurst(isRunning = true, progress = 0.95f))
        assertTrue(shouldRestartBurst(isRunning = true, progress = 1f))
        assertTrue(shouldRestartBurst(isRunning = false, progress = 0.2f))
        assertTrue(shouldRestartBurst(isRunning = true, progress = Float.NaN))
    }

    @Test
    fun `squash press target scales preserve area`() {
        assertEquals(1.04f, SQUASH_PRESS_SX, 1e-4f)
        assertEquals(1f, SQUASH_PRESS_SX * SQUASH_PRESS_SY, 1e-4f)
        assertTrue(SQUASH_PRESS_SX > 1f)
        assertTrue(SQUASH_PRESS_SY < 1f)
    }
}
