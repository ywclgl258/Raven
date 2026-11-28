package com.raven.app.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenLayout
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.rememberRavenWindowSize

/**
 * Presentation container mode for [RavenBottomSheet].
 * - [Bottom]: Anchored to the bottom edge (`ModalBottomSheet`), capped at `480dp` in phone landscape.
 * - [Center]: Renders as a centered HarmonyOS panel (`480dp` max width, max 90% height) on screens `>= 600dp`,
 *   falling back to [Bottom] on compact screens.
 * - [Auto]: Uses [Center] on screens `>= 600dp` and [Bottom] on compact screens.
 */
enum class RavenSheetPresentation {
    Bottom,
    Center,
    Auto,
}

/**
 * Height detent strategy for [RavenBottomSheet].
 * - [Auto]: Adapts to content height (skips partial expand to avoid clipping short menus).
 * - [Medium]: Allows half-sheet expansion (~60% viewport height) with drag-to-expand support.
 * - [Large]: Expands up to full viewport minus status bar + `8dp` top clearance.
 * - [Fixed]: Uses an explicit fixed height.
 */
sealed interface RavenSheetDetent {
    data object Auto : RavenSheetDetent
    data object Medium : RavenSheetDetent
    data object Large : RavenSheetDetent
    data class Fixed(val height: Dp) : RavenSheetDetent
}

/**
 * Shared visual shell for both bottom-anchored and centered wide-screen [RavenBottomSheet] panels
 * (`Surface2`, top optical rim highlight degraded under `reduceTransparency`, optional header + divider).
 */
@Composable
fun RavenSheetSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    applySystemBarPadding: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (applySystemBarPadding) Modifier.navigationBarsPadding() else Modifier)
            .imePadding(),
    ) {
        if (!reduceTransparency) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, colors.lightRimTop, Color.Transparent),
                        ),
                    ),
            )
        }
        if (!title.isNullOrBlank() || trailingAction != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (trailingAction != null) {
                    trailingAction()
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = colors.hairline,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * Unified Raven Obsidian bottom sheet (`Surface2`, HarmonyOS `32dp` top radius `BindSheet`,
 * multi-detent height support, wide-screen centered presentation, optical rim highlight, and safe
 * navigation-bar + IME insets).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RavenBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState? = null,
    presentation: RavenSheetPresentation = RavenSheetPresentation.Bottom,
    detent: RavenSheetDetent = RavenSheetDetent.Auto,
    title: String? = null,
    subtitle: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    dismissOnScrimClick: Boolean = true,
    confirmValueChange: (SheetValue) -> Boolean = { true },
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val reduceTransparency = LocalReduceTransparency.current
    val windowSize = rememberRavenWindowSize()
    val screenWidthDp = windowSize.widthDp
    val screenHeightDp = windowSize.heightDp
    val isLandscapePhone = screenWidthDp > screenHeightDp && screenWidthDp < RavenLayout.BREAKPOINT_MD_DP

    val useCenterPanel = when (presentation) {
        RavenSheetPresentation.Bottom -> false
        RavenSheetPresentation.Center,
        RavenSheetPresentation.Auto,
        -> RavenLayout.supportsCenteredSheet(screenWidthDp)
    }

    LaunchedEffect(Unit) {
        feedback.perform(RavenFeedbackEffect.SHEET_OPEN)
    }

    if (useCenterPanel) {
        val motionReduced = LocalMotionReduced.current
        val maxHeight = (minOf(screenHeightDp, screenWidthDp).coerceAtLeast(360) *
            RavenLayout.SHEET_CENTER_MAX_HEIGHT_FRACTION).dp
        val effectiveMaxHeight = maxOf(maxHeight, (screenHeightDp * RavenLayout.SHEET_CENTER_MAX_HEIGHT_FRACTION).dp)
        val containerColor = colors.surface2
        val borderColor = if (reduceTransparency) colors.outlineSubtle else colors.hairline
        val enterProgress = remember(motionReduced) { Animatable(if (motionReduced) 1f else 0f) }
        LaunchedEffect(motionReduced) {
            if (motionReduced) {
                enterProgress.snapTo(1f)
            } else {
                enterProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = RavenMotion.HarmonyDecelerationEasing,
                    ),
                )
            }
        }
        val detentModifier = when (detent) {
            is RavenSheetDetent.Fixed -> Modifier.height(detent.height.coerceAtMost(effectiveMaxHeight))
            RavenSheetDetent.Large -> Modifier.heightIn(
                min = RavenLayout.SheetCenterDefaultHeight.coerceAtMost(effectiveMaxHeight),
                max = effectiveMaxHeight,
            )
            RavenSheetDetent.Medium -> Modifier.heightIn(
                min = RavenLayout.SheetCenterMinHeight.coerceAtMost(effectiveMaxHeight),
                max = (screenHeightDp * RavenLayout.SHEET_MEDIUM_HEIGHT_FRACTION).dp
                    .coerceAtLeast(RavenLayout.SheetCenterMinHeight)
                    .coerceAtMost(effectiveMaxHeight),
            )
            RavenSheetDetent.Auto -> Modifier.heightIn(max = effectiveMaxHeight)
        }

        Dialog(
            onDismissRequest = {
                if (dismissOnScrimClick) onDismissRequest()
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = dismissOnScrimClick,
                dismissOnBackPress = dismissOnScrimClick,
            ),
        ) {
            Surface(
                modifier = modifier
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .widthIn(max = RavenLayout.SheetCenterMaxWidth)
                    .fillMaxWidth()
                    .then(detentModifier)
                    .graphicsLayer {
                        val p = enterProgress.value
                        alpha = p
                        val scale = 0.95f + 0.05f * p
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - p) * 14.dp.toPx()
                    }
                    .border(0.75.dp, borderColor, RavenShapes.DialogShape)
                    .onPreviewKeyEvent { keyEvent ->
                        if (dismissOnScrimClick &&
                            keyEvent.type == KeyEventType.KeyUp &&
                            keyEvent.key == Key.Escape
                        ) {
                            onDismissRequest()
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("raven_bottom_sheet"),
                shape = RavenShapes.DialogShape,
                color = containerColor,
                contentColor = colors.inkPrimary,
                tonalElevation = 0.dp,
            ) {
                RavenSheetSurface(
                    title = title,
                    subtitle = subtitle,
                    trailingAction = trailingAction,
                    contentPadding = contentPadding,
                    applySystemBarPadding = false,
                    content = content,
                )
            }
        }
        return
    }

    val latestDismissOnScrimClick by rememberUpdatedState(dismissOnScrimClick)
    val latestConfirmValueChange by rememberUpdatedState(confirmValueChange)
    val resolvedSheetState = sheetState ?: rememberModalBottomSheetState(
        skipPartiallyExpanded = detent == RavenSheetDetent.Large || detent == RavenSheetDetent.Auto,
        confirmValueChange = { target ->
            if (!latestDismissOnScrimClick && target == SheetValue.Hidden) {
                false
            } else {
                latestConfirmValueChange(target)
            }
        },
    )

    val detentModifier = when (detent) {
        is RavenSheetDetent.Fixed -> Modifier.height(detent.height)
        RavenSheetDetent.Large -> Modifier.fillMaxHeight()
        RavenSheetDetent.Medium,
        RavenSheetDetent.Auto,
        -> Modifier
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = RavenLayout.SheetLargeTopClearance)
            .onPreviewKeyEvent { keyEvent ->
                if (dismissOnScrimClick &&
                    keyEvent.type == KeyEventType.KeyUp &&
                    keyEvent.key == Key.Escape
                ) {
                    onDismissRequest()
                    true
                } else {
                    false
                }
            }
            .testTag("raven_bottom_sheet"),
        sheetState = resolvedSheetState,
        sheetMaxWidth = if (isLandscapePhone) RavenLayout.SheetLandscapeMaxWidth else RavenLayout.SheetCenterMaxWidth,
        shape = RavenShapes.SheetShape,
        containerColor = colors.surface2,
        contentColor = colors.inkPrimary,
        tonalElevation = 0.dp,
        scrimColor = colors.canvas.copy(alpha = 0.62f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = dismissOnScrimClick,
        ),
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.5.dp)
                        .clip(CircleShape)
                        .background(colors.inkTertiary.copy(alpha = 0.72f)),
                )
            }
        },
    ) {
        RavenSheetSurface(
            modifier = detentModifier,
            title = title,
            subtitle = subtitle,
            trailingAction = trailingAction,
            contentPadding = contentPadding,
            applySystemBarPadding = true,
            content = content,
        )
    }
}
