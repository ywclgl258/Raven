package com.raven.app.feature.browse

import com.raven.app.core.model.SiteId

/**
 * Pure ordering helpers for the site switcher so the UI and its unit tests share one
 * implementation. All functions are side-effect free and safe to call on the composition
 * thread (they only allocate small lists).
 */
object SiteOrdering {

    /**
     * Moves [from] to [to] inside [ids], returning a new list. Out-of-range indices are clamped
     * instead of throwing, so a drag that ends slightly outside the row still lands somewhere sane.
     * Identity-preserving when the move is a no-op.
     */
    fun move(
        ids: List<SiteId>,
        from: Int,
        to: Int,
    ): List<SiteId> = moveGeneric(ids, from, to)

    /**
     * Generic variant so callers can reorder either the visible [SiteId] list or the projected
     * descriptor list with the same clamping semantics.
     */
    fun <T> moveGeneric(
        items: List<T>,
        from: Int,
        to: Int,
    ): List<T> {
        if (items.size < 2) return items
        val safeFrom = from.coerceIn(0, items.lastIndex)
        val safeTo = to.coerceIn(0, items.lastIndex)
        if (safeFrom == safeTo) return items
        val next = items.toMutableList()
        val moved = next.removeAt(safeFrom)
        next.add(safeTo, moved)
        return next
    }

    /**
     * Applies a persistent reorder of the pinned section back onto the full pinned id list.
     *
     * The pinned section may be filtered (search query) or usage-sorted, so the visible order is
     * only a projection. We splice the reordered visible ids back into the slots the visible ids
     * originally occupied, leaving hidden/absent ids at their stored positions. This keeps the
     * stored list authoritative and avoids dropping pins that the user cannot see right now.
     */
    fun applyVisibleReorder(
        storedIds: List<SiteId>,
        visibleIds: List<SiteId>,
        reorderedVisibleIds: List<SiteId>,
    ): List<SiteId> {
        if (storedIds.isEmpty() || visibleIds.isEmpty()) return storedIds
        if (visibleIds == reorderedVisibleIds) return storedIds

        val visibleSet = visibleIds.toSet()
        val orderedVisible = reorderedVisibleIds.filter { it in visibleSet }
        if (orderedVisible.size != visibleIds.size) {
            // A mismatch means the caller handed us an inconsistent order; keep storage untouched.
            return storedIds
        }

        val result = storedIds.toMutableList()
        var cursor = 0
        for (index in result.indices) {
            if (result[index] in visibleSet) {
                result[index] = orderedVisible[cursor]
                cursor++
            }
        }
        return if (result == storedIds) storedIds else result
    }

    /**
     * Adds ids in [candidates] that are not already pinned, preserving their incoming order.
     * Used when a reorder introduces a site that was pinned but missing from the stored list.
     */
    fun ensureAllPinned(storedIds: List<SiteId>, candidates: List<SiteId>): List<SiteId> {
        if (candidates.isEmpty()) return storedIds
        val known = storedIds.toSet()
        val missing = candidates.filterNot { it in known }.distinct()
        return if (missing.isEmpty()) storedIds else storedIds + missing
    }
}
