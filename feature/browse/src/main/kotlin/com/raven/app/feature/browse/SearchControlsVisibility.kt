package com.raven.app.feature.browse

/**
 * Pure keyboard-free logic for the auto-collapsing search controls header.
 *
 * The old behavior revealed the controls on any upward gesture, which made a half-scrolled list
 * flash the header every time the user nudged back. New rule: the controls exist only while the
 * active result/discovery list sits at its absolute top. Scrolling down hides them, and scrolling
 * up anywhere below the top keeps them hidden until the list actually reaches the top again.
 */
object SearchControlsVisibility {

    /** `true` only when the list is pinned to its very first item with zero offset. */
    fun isAtAbsoluteTop(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
        firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

    /**
     * Resolves whether the header should render.
     *
     * @param forcedVisible set when the user explicitly wants the controls (typing, opening the
     *   advanced filters, submitting a search). It is cleared as soon as the list leaves the top.
     * @param atAbsoluteTop whether the active list currently reports its absolute top.
     */
    fun shouldShow(forcedVisible: Boolean, atAbsoluteTop: Boolean): Boolean =
        forcedVisible || atAbsoluteTop

    /**
     * Whether a scroll report from the body should revoke a pending [forcedVisible] reveal.
     * Only a confirmed departure from the top revokes it; staying at the top or a transient
     * index tick does not.
     */
    fun revokesForcedReveal(previousAtTop: Boolean, currentAtTop: Boolean, forcedVisible: Boolean): Boolean =
        forcedVisible && previousAtTop && !currentAtTop
}
