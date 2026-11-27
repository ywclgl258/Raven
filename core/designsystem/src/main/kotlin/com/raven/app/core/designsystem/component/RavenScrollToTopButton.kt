package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.ravenSpringPress
import kotlinx.coroutines.launch

private const val DefaultOffsetThresholdPx = 280

/**
 * Floating scroll-to-top button with spring-driven pop-in and bounce-out transitions.
 * Automatically hides when the target scroll container is near the top.
 */
@Composable
fun RavenScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "回到顶部",
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    val enter: EnterTransition = if (motionReduced) {
        EnterTransition.None
    } else {
        scaleIn(
            initialScale = 0.45f,
            animationSpec = spring(
                dampingRatio = 0.56f,
                stiffness = 440f,
            ),
        ) + slideInVertically(
            initialOffsetY = { (it * 0.7f).toInt() },
            animationSpec = spring<IntOffset>(
                dampingRatio = 0.62f,
                stiffness = 420f,
            ),
        ) + fadeIn(animationSpec = tween(durationMillis = 150))
    }

    val exit: ExitTransition = if (motionReduced) {
        ExitTransition.None
    } else {
        scaleOut(
            targetScale = 0.45f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 520f,
            ),
        ) + slideOutVertically(
            targetOffsetY = { (it * 0.65f).toInt() },
            animationSpec = spring<IntOffset>(
                dampingRatio = 0.72f,
                stiffness = 460f,
            ),
        ) + fadeOut(animationSpec = tween(durationMillis = 160))
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        label = "ravenScrollToTopVisibility",
    ) {
        Box(
            modifier = Modifier
                .testTag("raven_scroll_to_top_button")
                .size(48.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = CircleShape,
                    ambientColor = colors.crimson.copy(alpha = 0.28f),
                    spotColor = colors.crimson.copy(alpha = 0.32f),
                )
                .ravenSpringPress(interactionSource = interactionSource)
                .clip(CircleShape)
                .background(colors.surface2.copy(alpha = 0.94f))
                .border(
                    width = 1.dp,
                    color = colors.crimson.copy(alpha = 0.45f),
                    shape = CircleShape,
                )
                .semantics { this.contentDescription = contentDescription }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
fun RavenScrollToTopButton(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    itemThreshold: Int = 1,
    offsetThresholdPx: Int = DefaultOffsetThresholdPx,
) {
    val scope = rememberCoroutineScope()
    val visible by remember(listState, itemThreshold, offsetThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex >= itemThreshold ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > offsetThresholdPx)
        }
    }
    RavenScrollToTopButton(
        visible = visible,
        onClick = {
            scope.launch {
                if (listState.firstVisibleItemIndex > 18) {
                    listState.scrollToItem(12)
                }
                listState.animateScrollToItem(0)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun RavenScrollToTopButton(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    itemThreshold: Int = 2,
    offsetThresholdPx: Int = DefaultOffsetThresholdPx,
) {
    val scope = rememberCoroutineScope()
    val visible by remember(gridState, itemThreshold, offsetThresholdPx) {
        derivedStateOf {
            gridState.firstVisibleItemIndex >= itemThreshold ||
                (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset > offsetThresholdPx)
        }
    }
    RavenScrollToTopButton(
        visible = visible,
        onClick = {
            scope.launch {
                if (gridState.firstVisibleItemIndex > 24) {
                    gridState.scrollToItem(12)
                }
                gridState.animateScrollToItem(0)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun RavenScrollToTopButton(
    staggeredGridState: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    itemThreshold: Int = 2,
    offsetThresholdPx: Int = DefaultOffsetThresholdPx,
) {
    val scope = rememberCoroutineScope()
    val visible by remember(staggeredGridState, itemThreshold, offsetThresholdPx) {
        derivedStateOf {
            staggeredGridState.firstVisibleItemIndex >= itemThreshold ||
                (
                    staggeredGridState.firstVisibleItemIndex == 0 &&
                        staggeredGridState.firstVisibleItemScrollOffset > offsetThresholdPx
                    )
        }
    }
    RavenScrollToTopButton(
        visible = visible,
        onClick = {
            scope.launch {
                if (staggeredGridState.firstVisibleItemIndex > 24) {
                    staggeredGridState.scrollToItem(12)
                }
                staggeredGridState.animateScrollToItem(0)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun RavenScrollToTopButton(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    offsetThresholdPx: Int = DefaultOffsetThresholdPx,
) {
    val scope = rememberCoroutineScope()
    val visible by remember(scrollState, offsetThresholdPx) {
        derivedStateOf { scrollState.value > offsetThresholdPx }
    }
    RavenScrollToTopButton(
        visible = visible,
        onClick = {
            scope.launch { scrollState.animateScrollTo(0) }
        },
        modifier = modifier,
    )
}
