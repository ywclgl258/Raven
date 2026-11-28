package com.raven.app

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.model.RavenRoute

/** 导航转场与顶部 Tab 判定：Tab 切换、入栈/出栈、图集↔阅读器与模态转场。 */

internal fun NavDestination.topLevelTabIndex(): Int? = when {
    hasRoute<RavenRoute.Home>() -> 0
    hasRoute<RavenRoute.Explore>() -> 1
    hasRoute<RavenRoute.Search>() -> 2
    hasRoute<RavenRoute.Library>() -> 3
    hasRoute<RavenRoute.Profile>() -> 4
    else -> null
}

internal fun NavDestination.isTopLevelTabDestination(): Boolean = topLevelTabIndex() != null

internal fun NavDestination.isImmersiveModalDestination(): Boolean =
    hasRoute<RavenRoute.Reader>() || hasRoute<RavenRoute.GalleryDetail>()

internal fun ravenTabEnterTransition(direction: Int): EnterTransition {
    val base = fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + scaleIn(
        initialScale = RavenMotion.TAB_ENTER_INITIAL_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )
    return if (direction == 0) {
        base
    } else {
        base + slideInHorizontally(
            animationSpec = tween(
                durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
            initialOffsetX = { fullWidth -> RavenMotion.computeTabSlideOffset(fullWidth, direction) },
        )
    }
}

internal fun ravenTabExitTransition(direction: Int): ExitTransition {
    val base = fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS - 40,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )
    return if (direction == 0) {
        base
    } else {
        base + slideOutHorizontally(
            animationSpec = tween(
                durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS - 20,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
            targetOffsetX = { fullWidth -> -RavenMotion.computeTabSlideOffset(fullWidth, direction) },
        )
    }
}

internal fun ravenPushEnterTransition(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialOffsetX = { fullWidth -> fullWidth },
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS / 2,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialAlpha = 0.85f,
    )

internal fun ravenPushParentExitTransition(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetOffsetX = RavenMotion::computeNavPushParentExitOffset,
    ) + scaleOut(
        targetScale = RavenMotion.PUSH_PARENT_EXIT_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetAlpha = RavenMotion.PUSH_EXIT_ALPHA,
    )

internal fun ravenPopParentEnterTransition(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialOffsetX = RavenMotion::computeNavPopParentEnterOffset,
    ) + scaleIn(
        initialScale = RavenMotion.PUSH_PARENT_EXIT_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialAlpha = RavenMotion.PUSH_EXIT_ALPHA,
    )

internal fun ravenPopExitTransition(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetOffsetX = { fullWidth -> fullWidth },
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetAlpha = 0.85f,
    )

internal fun ravenGalleryToReaderEnterTransition(): EnterTransition =
    scaleIn(
        initialScale = 0.96f,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS - 40,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )

internal fun ravenReaderToGalleryExitTransition(): ExitTransition =
    scaleOut(
        targetScale = 0.96f,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS - 30,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )

internal fun ravenModalEnterTransition(): EnterTransition =
    slideInVertically(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialOffsetY = { fullHeight -> fullHeight / 14 },
    ) + scaleIn(
        initialScale = RavenMotion.MODAL_INITIAL_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS - 60,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )

internal fun ravenModalParentExitTransition(): ExitTransition =
    scaleOut(
        targetScale = RavenMotion.PUSH_PARENT_EXIT_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetAlpha = RavenMotion.PUSH_EXIT_ALPHA,
    )

internal fun ravenModalParentEnterTransition(): EnterTransition =
    scaleIn(
        initialScale = RavenMotion.PUSH_PARENT_EXIT_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialAlpha = RavenMotion.PUSH_EXIT_ALPHA,
    )

internal fun ravenModalExitTransition(): ExitTransition =
    slideOutVertically(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetOffsetY = { fullHeight -> fullHeight / 14 },
    ) + scaleOut(
        targetScale = RavenMotion.MODAL_INITIAL_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS - 30,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )
