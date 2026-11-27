package com.raven.app.core.designsystem

import com.raven.app.core.designsystem.theme.RavenMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the relative duration of status and content transitions and the bounds of stagger delays.
 */
class RavenMotionRhythmTest {

    @Test
    fun `label exits are shorter than label and content entrances`() {
        assertTrue(RavenMotion.LABEL_EXIT_MS < RavenMotion.LABEL_SWAP_MS)
        assertTrue(RavenMotion.LABEL_SWAP_MS <= RavenMotion.CONTENT_REVEAL_MS)
        assertTrue(RavenMotion.CONTENT_REVEAL_MS < RavenMotion.PUSH_TRANSITION_DURATION_MS)
        assertTrue(RavenMotion.LABEL_EXIT_MS <= 140)
    }

    @Test
    fun `stagger only delays the first screenful`() {
        assertEquals(0, RavenMotion.listStaggerDelayMs(0))
        assertEquals(RavenMotion.STAGGER_STEP_MS, RavenMotion.listStaggerDelayMs(1))
        assertEquals(
            RavenMotion.STAGGER_STEP_MS * (RavenMotion.STAGGER_MAX_ITEMS - 1),
            RavenMotion.listStaggerDelayMs(RavenMotion.STAGGER_MAX_ITEMS - 1),
        )
        assertEquals(0, RavenMotion.listStaggerDelayMs(RavenMotion.STAGGER_MAX_ITEMS))
        assertEquals(0, RavenMotion.listStaggerDelayMs(120))
    }

    @Test
    fun `degenerate stagger inputs never delay rendering`() {
        assertEquals(0, RavenMotion.listStaggerDelayMs(-3))
        assertEquals(0, RavenMotion.listStaggerDelayMs(3, stepMs = 0))
        assertEquals(0, RavenMotion.listStaggerDelayMs(3, maxItems = 0))
    }
}
