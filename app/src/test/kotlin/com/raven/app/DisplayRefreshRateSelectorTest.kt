package com.raven.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `user peak of 90Hz selects 90 not 120 and keeps the current resolution`() {
        val fhd60 = mode(1, 60f)
        val fhd90 = mode(2, 90f)
        val fhd120 = mode(3, 120f)
        val qhd144 = DisplayModeCandidate(4, 1440, 3200, 144f)

        val chosen = DisplayRefreshRateController.selectOptimalHighRefreshMode(
            current = fhd60,
            supported = listOf(fhd60, fhd90, fhd120, qhd144),
            maxPreferredHz = 90f,
        )
        assertEquals(fhd90, chosen)
    }

    @Test
    fun `battery saver and a 60Hz user peak clear the forced mode`() {
        assertEquals(
            0f,
            DisplayRefreshRateController.resolvePreferredRefreshCapHz(
                batterySaver = true,
                systemPeakHz = 120f,
            ),
            0.01f,
        )
        assertEquals(
            0f,
            DisplayRefreshRateController.resolvePreferredRefreshCapHz(
                batterySaver = false,
                systemPeakHz = 60f,
            ),
            0.01f,
        )
        assertNull(
            DisplayRefreshRateController.selectOptimalHighRefreshMode(
                current = mode(1, 60f),
                supported = listOf(mode(1, 60f), mode(3, 120f)),
                maxPreferredHz = 0f,
            ),
        )
    }

    @Test
    fun `missing or higher system peak does not raise the app cap or change resolution`() {
        assertEquals(
            120f,
            DisplayRefreshRateController.resolvePreferredRefreshCapHz(
                batterySaver = false,
                systemPeakHz = 120f,
            ),
            0.01f,
        )
        assertEquals(
            144f,
            DisplayRefreshRateController.resolvePreferredRefreshCapHz(
                batterySaver = false,
                systemPeakHz = Float.NaN,
            ),
            0.01f,
        )
        val qhd90 = DisplayModeCandidate(8, 1440, 3200, 90f)
        val chosen = DisplayRefreshRateController.selectOptimalHighRefreshMode(
            current = qhd90,
            supported = listOf(qhd90, mode(3, 120f)),
            maxPreferredHz = 144f,
        )
        assertEquals(qhd90, chosen)
    }

    private fun mode(id: Int, refreshRate: Float) = DisplayModeCandidate(
        modeId = id,
        physicalWidth = 1080,
        physicalHeight = 2400,
        refreshRate = refreshRate,
    )

    @Test
    fun `isHighRefreshDisplay identifies 90Hz and 120Hz panels cleanly`() {
        assertFalse(DisplayRefreshRateController.isHighRefreshDisplay(60f))
        assertFalse(DisplayRefreshRateController.isHighRefreshDisplay(Float.NaN))
        assertTrue(DisplayRefreshRateController.isHighRefreshDisplay(90f))
        assertTrue(DisplayRefreshRateController.isHighRefreshDisplay(120f))
        assertTrue(DisplayRefreshRateController.isHighRefreshDisplay(144f))
    }
}
