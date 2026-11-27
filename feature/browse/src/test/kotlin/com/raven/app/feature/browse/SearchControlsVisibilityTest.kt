package com.raven.app.feature.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [SearchControlsVisibility] — the rule set that replaced "show on any upward
 * gesture" with "visible only while the active list is at its absolute top".
 */
class SearchControlsVisibilityTest {

    @Test
    fun `absolute top requires first item and zero offset`() {
        assertTrue(SearchControlsVisibility.isAtAbsoluteTop(0, 0))
        assertFalse(SearchControlsVisibility.isAtAbsoluteTop(0, 1))
        assertFalse(SearchControlsVisibility.isAtAbsoluteTop(1, 0))
        assertFalse(SearchControlsVisibility.isAtAbsoluteTop(4, 120))
    }

    @Test
    fun `shouldShow returns true at top even without a forced reveal`() {
        assertTrue(SearchControlsVisibility.shouldShow(forcedVisible = false, atAbsoluteTop = true))
    }

    @Test
    fun `shouldShow returns false away from top unless forced`() {
        assertFalse(SearchControlsVisibility.shouldShow(forcedVisible = false, atAbsoluteTop = false))
        assertTrue(SearchControlsVisibility.shouldShow(forcedVisible = true, atAbsoluteTop = false))
    }

    @Test
    fun `scrolling down from top revokes a forced reveal`() {
        assertTrue(
            SearchControlsVisibility.revokesForcedReveal(
                previousAtTop = true,
                currentAtTop = false,
                forcedVisible = true,
            ),
        )
    }

    @Test
    fun `staying at top never revokes a forced reveal`() {
        assertFalse(
            SearchControlsVisibility.revokesForcedReveal(
                previousAtTop = true,
                currentAtTop = true,
                forcedVisible = true,
            ),
        )
    }

    @Test
    fun `returning to top across a scroll does not count as a revoke trigger`() {
        // previousAtTop=false -> currentAtTop=true means the user scrolled back up; the header
        // becomes visible through shouldShow(atTop=true) and no revoke happens.
        assertFalse(
            SearchControlsVisibility.revokesForcedReveal(
                previousAtTop = false,
                currentAtTop = true,
                forcedVisible = false,
            ),
        )
        assertEquals(
            true,
            SearchControlsVisibility.shouldShow(forcedVisible = false, atAbsoluteTop = true),
        )
    }

    @Test
    fun `upward scroll away from top leaves controls hidden`() {
        // The gesture never re-opens the header: at a non-top offset shouldShow stays false.
        assertFalse(SearchControlsVisibility.shouldShow(forcedVisible = false, atAbsoluteTop = false))
    }
}
