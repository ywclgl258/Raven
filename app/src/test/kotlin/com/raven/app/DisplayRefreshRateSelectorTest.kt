package com.raven.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshRateSelectorTest {

    @Test
    fun `selects highest refresh rate matching current physical resolution`() {
        val fhd60 = DisplayModeCandidate(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f)
        val fhd90 = DisplayModeCandidate(modeId = 2, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 90f)
        val fhd120 = DisplayModeCandidate(modeId = 3, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 120f)
        val hd144 = DisplayModeCandidate(modeId = 4, physicalWidth = 720, physicalHeight = 1600, refreshRate = 144f)

        val chosen = DisplayRefreshRateController.selectOptimalHighRefreshMode(
            current = fhd60,
            supported = listOf(fhd60, fhd90, fhd120, hd144),
        )
        assertEquals(fhd120, chosen)
    }

    @Test
    fun `never switches physical resolution just to chase higher refresh rate`() {
        val qhd60 = DisplayModeCandidate(modeId = 10, physicalWidth = 1440, physicalHeight = 3200, refreshRate = 60f)
        val fhd120 = DisplayModeCandidate(modeId = 11, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 120f)

        val chosen = DisplayRefreshRateController.selectOptimalHighRefreshMode(
            current = qhd60,
            supported = listOf(qhd60, fhd120),
        )
        assertEquals(qhd60, chosen)
    }

    @Test
    fun `ignores invalid or above-cap refresh rates and keeps safe fallback`() {
        val mode60 = DisplayModeCandidate(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f)
        val nanMode = DisplayModeCandidate(modeId = 2, physicalWidth = 1080, physicalHeight = 2400, refreshRate = Float.NaN)
        val zeroMode = DisplayModeCandidate(modeId = 3, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 0f)
        val mode240 = DisplayModeCandidate(modeId = 4, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 240f)
        val mode144 = DisplayModeCandidate(modeId = 5, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 144f)

        val chosen = DisplayRefreshRateController.selectOptimalHighRefreshMode(
            current = mode60,
            supported = listOf(mode60, nanMode, zeroMode, mode240, mode144),
        )
        assertEquals(mode144, chosen)
    }

    @Test
    fun `isHighRefreshDisplay identifies 90Hz and 120Hz panels cleanly`() {
        assertFalse(DisplayRefreshRateController.isHighRefreshDisplay(60f))
        assertFalse(DisplayRefreshRateController.isHighRefreshDisplay(Float.NaN))
        assertTrue(DisplayRefreshRateController.isHighRefreshDisplay(90f))
        assertTrue(DisplayRefreshRateController.isHighRefreshDisplay(120f))
        assertTrue(DisplayRefreshRateController.isHighRefreshDisplay(144f))
    }
}
