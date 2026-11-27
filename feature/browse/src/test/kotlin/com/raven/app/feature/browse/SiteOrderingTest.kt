package com.raven.app.feature.browse

import com.raven.app.core.model.SiteId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [SiteOrdering] — the shared reorder/persist logic behind the site switcher
 * drag gesture and its accessibility fallback.
 */
class SiteOrderingTest {

    private val a = SiteId.R34V
    private val b = SiteId.EHENTAI
    private val c = SiteId.NHENTAI
    private val d = SiteId.MANGACOPY

    @Test
    fun `move forwards shifts items and keeps order`() {
        assertEquals(listOf(b, c, a, d), SiteOrdering.move(listOf(a, b, c, d), from = 0, to = 2))
    }

    @Test
    fun `move backwards shifts items and keeps order`() {
        assertEquals(listOf(a, d, b, c), SiteOrdering.move(listOf(a, b, c, d), from = 3, to = 1))
    }

    @Test
    fun `move with identical indices is identity preserving`() {
        val ids = listOf(a, b, c)
        assertSame(ids, SiteOrdering.move(ids, from = 1, to = 1))
    }

    @Test
    fun `move clamps out-of-range indices instead of throwing`() {
        val ids = listOf(a, b, c)
        // -5 collapses to the first slot, 9 to the last slot -> a moves to the end in both cases.
        assertEquals(listOf(b, c, a), SiteOrdering.move(ids, from = -5, to = 9))
        assertEquals(listOf(b, c, a), SiteOrdering.move(ids, from = 0, to = 9))
    }

    @Test
    fun `move on tiny lists is a no-op`() {
        val single = listOf(a)
        assertSame(single, SiteOrdering.move(single, from = 0, to = 1))
        assertSame(emptyList<SiteId>(), SiteOrdering.move(emptyList(), from = 0, to = 1))
    }

    @Test
    fun `applyVisibleReorder splices filtered projection back into stored slots`() {
        // Stored: a b c d, but only b and d are visible (a/c filtered by search).
        val stored = listOf(a, b, c, d)
        val visible = listOf(b, d)
        val reorderedVisible = listOf(d, b)

        val result = SiteOrdering.applyVisibleReorder(
            storedIds = stored,
            visibleIds = visible,
            reorderedVisibleIds = reorderedVisible,
        )

        // Visible slots are positions 1 and 3; d now occupies slot 1, b slot 3. Hidden ids stay.
        assertEquals(listOf(a, d, c, b), result)
    }

    @Test
    fun `applyVisibleReorder is identity when order is unchanged`() {
        val stored = listOf(a, b, c, d)
        val visible = listOf(b, d)
        assertSame(
            stored,
            SiteOrdering.applyVisibleReorder(stored, visible, listOf(b, d)),
        )
    }

    @Test
    fun `applyVisibleReorder rejects inconsistent reorder payloads`() {
        val stored = listOf(a, b, c)
        val visible = listOf(a, b, c)
        // Missing one entry -> leave storage untouched.
        assertEquals(stored, SiteOrdering.applyVisibleReorder(stored, visible, listOf(b, a)))
    }

    @Test
    fun `ensureAllPinned appends missing pins preserving candidate order`() {
        val stored = listOf(a, c)
        val candidates = listOf(d, b, a, c, b)
        assertEquals(listOf(a, c, d, b), SiteOrdering.ensureAllPinned(stored, candidates))
    }

    @Test
    fun `ensureAllPinned is identity when nothing is missing`() {
        val stored = listOf(a, b, c)
        assertSame(stored, SiteOrdering.ensureAllPinned(stored, listOf(b, a)))
    }

    @Test
    fun `reorder then persist roundtrip keeps a stable stored order`() {
        // Simulates drag a->c on the visible list, then verifies the persisted ids re-render identically.
        val stored = listOf(a, b, c, d)
        val dragged = SiteOrdering.move(stored, from = 0, to = 2)
        val persisted = SiteOrdering.applyVisibleReorder(
            storedIds = stored,
            visibleIds = stored,
            reorderedVisibleIds = dragged,
        )
        assertEquals(listOf(b, c, a, d), persisted)
        assertTrue(persisted.distinct().size == persisted.size)
    }
}
