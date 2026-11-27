package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.RavenMotion

/**
 * Swaps changing copy (status labels, percentages, CTA text) with a short fade + small rise so the
 * change is visible without ever blocking the next tap. Snaps immediately under reduced motion.
 */
@Composable
fun RavenAnimatedLabel(
    text: String,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable (String) -> Unit,
) {
    if (LocalMotionReduced.current) {
        Box(modifier = modifier, contentAlignment = contentAlignment) { content(text) }
        return
    }
    val slideOffset = with(LocalDensity.current) { RavenMotion.LABEL_SWAP_SLIDE_DP.dp.roundToPx() }
    val enter = fadeIn(tween(RavenMotion.LABEL_SWAP_MS, easing = RavenMotion.HarmonyDecelerationEasing)) +
        slideInVertically(
            animationSpec = tween(RavenMotion.LABEL_SWAP_MS, easing = RavenMotion.HarmonyDecelerationEasing),
            initialOffsetY = { slideOffset },
        )
    val exit = fadeOut(tween(RavenMotion.LABEL_EXIT_MS, easing = RavenMotion.HarmonyDecelerationEasing)) +
        slideOutVertically(
            animationSpec = tween(RavenMotion.LABEL_EXIT_MS, easing = RavenMotion.HarmonyDecelerationEasing),
            targetOffsetY = { -slideOffset },
        )
    AnimatedContent(
        targetState = text,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
        contentAlignment = contentAlignment,
        transitionSpec = { enter togetherWith exit },
        label = "ravenAnimatedLabel",
    ) { value ->
        content(value)
    }
}

/**
 * One-shot entrance for list items: short fade with a small rise, staggered by position.
 *
 * Items past [RavenMotion.STAGGER_MAX_ITEMS] appear immediately. Use on content that stays composed
 * while scrolling to avoid restarting the entrance as items return to the viewport.
 */
@Composable
fun rememberStaggeredRevealModifier(
    index: Int,
    animate: Boolean = true,
): Modifier {
    if (!animate || LocalMotionReduced.current || index !in 0 until RavenMotion.STAGGER_MAX_ITEMS) {
        return Modifier
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(index) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = RavenMotion.CONTENT_REVEAL_MS,
                delayMillis = RavenMotion.listStaggerDelayMs(index),
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
        )
    }

    return Modifier.graphicsLayer {
        val eased = progress.value.coerceIn(0f, 1f)
        alpha = eased
        translationY = (1f - eased) * 10.dp.toPx()
    }
}

/** Convenience wrapper around [rememberStaggeredRevealModifier] for a list row or section. */
@Composable
fun RavenStaggeredReveal(
    index: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable () -> Unit,
) {
    val reveal = rememberStaggeredRevealModifier(index = index, animate = animate)
    Box(modifier = modifier.then(reveal)) { content() }
}
