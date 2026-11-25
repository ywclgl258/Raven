package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular

/**
 * Semantic tone for [RavenStatusPill].
 */
enum class RavenStatusTone {
    NEUTRAL,
    ACCENT,
    SUCCESS,
    WARNING,
    ERROR,
}

/**
 * Compact semantic status pill (e.g., download state, offline badge, HD quality, auth status).
 */
@Composable
fun RavenStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: RavenStatusTone = RavenStatusTone.NEUTRAL,
    icon: ImageVector? = null,
    tabular: Boolean = true,
) {
    val colors = LocalRavenColors.current
    val accent = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.inkSecondary
        RavenStatusTone.ACCENT -> colors.crimson
        RavenStatusTone.SUCCESS -> colors.success
        RavenStatusTone.WARNING -> colors.warning
        RavenStatusTone.ERROR -> colors.error
    }
    val container = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.surface2
        else -> accent.copy(alpha = 0.14f)
    }
    val borderColor = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.hairline
        else -> accent.copy(alpha = 0.30f)
    }
    val contentColor = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.inkPrimary
        else -> accent
    }

    Row(
        modifier = modifier
            .clip(RavenShapes.PillShape)
            .background(container)
            .border(0.5.dp, borderColor, RavenShapes.PillShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = text,
            style = if (tabular) RavenTabularTextStyle else MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Centered Obsidian empty state with icon medallion, headline, description, and optional CTA.
 */
@Composable
fun RavenEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp)
            .testTag("raven_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.surface2)
                .border(
                    width = 0.75.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.lightRimTop, colors.hairline),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.inkSecondary,
                modifier = Modifier.size(26.dp),
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.inkPrimary,
            textAlign = TextAlign.Center,
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )

        if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    feedback.perform(RavenFeedbackEffect.TAP)
                    onActionClick()
                },
                shape = RavenShapes.PillShape,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/**
 * Centered Obsidian error state with high-contrast Retry CTA and optional secondary action
 * (e.g., sign-in / mirror switch).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RavenErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "加载失败",
    retryLabel: String = "重试",
    secondaryActionLabel: String? = null,
    onSecondaryActionClick: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp)
            .testTag("raven_error_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.error.copy(alpha = 0.14f))
                .border(
                    width = 0.75.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.error.copy(alpha = 0.42f), colors.error.copy(alpha = 0.20f)),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(26.dp),
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.inkPrimary,
            textAlign = TextAlign.Center,
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    feedback.perform(RavenFeedbackEffect.TAP)
                    onRetry()
                },
                shape = RavenShapes.PillShape,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress()
                    .testTag("raven_error_retry"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = retryLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!secondaryActionLabel.isNullOrBlank() && onSecondaryActionClick != null) {
                OutlinedButton(
                    onClick = {
                        feedback.perform(RavenFeedbackEffect.TAP)
                        onSecondaryActionClick()
                    },
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface2,
                        contentColor = colors.inkPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .ravenSpringPress(),
                ) {
                    Text(
                        text = secondaryActionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun rememberRavenShimmerProgress(): State<Float> {
    val motionReduced = LocalMotionReduced.current
    if (motionReduced) {
        return remember { mutableFloatStateOf(0f) }
    }
    val transition = rememberInfiniteTransition(label = "ravenShimmerProgress")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ravenShimmerFraction",
    )
}

/**
 * Animated dark obsidian shimmer brush (degrades to static [RavenColors.ShimmerBase] when reduced
 * motion is active).
 */
@Composable
fun rememberRavenShimmerBrush(): Brush {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    if (motionReduced) {
        return SolidColor(colors.shimmerBase)
    }

    val transition = rememberInfiniteTransition(label = "ravenShimmer")
    val translate by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ravenShimmerOffset",
    )

    return Brush.linearGradient(
        colors = listOf(
            colors.shimmerBase,
            colors.shimmerHighlight,
            colors.shimmerBase,
        ),
        start = Offset(x = translate, y = 0f),
        end = Offset(x = translate + 280f, y = 160f),
    )
}

/**
 * Single shimmer placeholder block with customizable [shape], powered by `drawWithCache` to avoid
 * per-frame gradient allocations and skipped recompositions.
 */
@Composable
fun RavenSkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RavenShapes.ControlShape,
    @Suppress("UNUSED_PARAMETER") brush: Brush? = null,
    progress: State<Float> = rememberRavenShimmerProgress(),
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    Box(
        modifier = modifier
            .clip(shape)
            .drawWithCache {
                val bandWidth = 280f
                val highlightBrush = Brush.linearGradient(
                    colors = listOf(
                        colors.shimmerBase,
                        colors.shimmerHighlight,
                        colors.shimmerBase,
                    ),
                    start = Offset.Zero,
                    end = Offset(bandWidth, size.height.coerceAtLeast(160f)),
                )
                onDrawBehind {
                    drawRect(color = colors.shimmerBase)
                    if (!motionReduced) {
                        val travel = size.width + bandWidth * 2f
                        val tx = progress.value * travel - bandWidth
                        translate(left = tx) {
                            drawRect(
                                brush = highlightBrush,
                                topLeft = Offset.Zero,
                                size = Size(bandWidth, size.height),
                            )
                        }
                    }
                }
            },
    )
}

/**
 * Shimmer placeholder matching [RavenVideoCard] geometry.
 */
@Composable
fun RavenVideoCardSkeleton(
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    progress: State<Float> = rememberRavenShimmerProgress(),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RavenSkeletonBox(
            shape = RavenShapes.CardShape,
            brush = brush,
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(RavenFormatters.VIDEO_ASPECT_RATIO),
        )
        RavenSkeletonBox(
            brush = brush,
            progress = progress,
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .height(14.dp),
        )
        RavenSkeletonBox(
            brush = brush,
            progress = progress,
            modifier = Modifier
                .fillMaxWidth(0.52f)
                .height(11.dp),
        )
    }
}

/**
 * Shimmer placeholder matching [RavenGalleryCard] geometry.
 */
@Composable
fun RavenGalleryCardSkeleton(
    modifier: Modifier = Modifier,
    aspectRatio: Float = RavenFormatters.DEFAULT_GALLERY_ASPECT_RATIO,
    brush: Brush? = null,
    progress: State<Float> = rememberRavenShimmerProgress(),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        RavenSkeletonBox(
            shape = RavenShapes.CardShape,
            brush = brush,
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(RavenFormatters.clampAspectRatio(aspectRatio)),
        )
        RavenSkeletonBox(
            brush = brush,
            progress = progress,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(13.dp),
        )
    }
}

/**
 * Shimmer placeholder matching [RavenCompactMediaRow] geometry.
 */
@Composable
fun RavenCompactRowSkeleton(
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    progress: State<Float> = rememberRavenShimmerProgress(),
) {
    val colors = LocalRavenColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RavenSkeletonBox(
            shape = RavenShapes.ControlShape,
            brush = brush,
            progress = progress,
            modifier = Modifier
                .width(112.dp)
                .height(64.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RavenSkeletonBox(
                brush = brush,
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(14.dp),
            )
            RavenSkeletonBox(
                brush = brush,
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(11.dp),
            )
        }
    }
}

/**
 * Apple HIG tabular numeric counter with directional vertical odometer transitions
 * (automatically snaps when [LocalMotionReduced] is enabled).
 */
@Composable
fun RavenAnimatedCounter(
    count: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = RavenTabularTextStyle,
    color: Color = LocalRavenColors.current.inkPrimary,
    formatter: (Int) -> String = { it.toString() },
) {
    val motionReduced = LocalMotionReduced.current
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            if (motionReduced) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                val increasing = targetState >= initialState
                val spec = tween<androidx.compose.ui.unit.IntOffset>(
                    durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                    easing = RavenMotion.HarmonyDecelerationEasing,
                )
                val fadeSpec = tween<Float>(
                    durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                    easing = RavenMotion.HarmonyDecelerationEasing,
                )
                val enter = slideInVertically(animationSpec = spec) { fullHeight ->
                    if (increasing) fullHeight / 2 else -fullHeight / 2
                } + fadeIn(animationSpec = fadeSpec)
                val exit = slideOutVertically(animationSpec = spec) { fullHeight ->
                    if (increasing) -fullHeight / 2 else fullHeight / 2
                } + fadeOut(animationSpec = fadeSpec)
                (enter togetherWith exit).using(SizeTransform(clip = false))
            }
        },
        modifier = modifier,
        label = "ravenAnimatedCounter",
    ) { value ->
        Text(
            text = formatter(value),
            style = style.tabular(),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Standard dark-shimmer loading skeleton stack for list/feed screens.
 */
@Composable
fun RavenLoadingSkeleton(
    modifier: Modifier = Modifier,
    rowCount: Int = 5,
    itemSpacing: Dp = 10.dp,
) {
    val progress = rememberRavenShimmerProgress()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("raven_loading_skeleton"),
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        repeat(rowCount.coerceAtLeast(1)) {
            RavenCompactRowSkeleton(progress = progress)
        }
    }
}

/**
 * Smooth Apple-style content crossfade with subtle lift scale (`0.988f -> 1f`), automatically
 * snapping cleanly when [LocalMotionReduced] is active. Key only on coarse view stages so inner
 * lazy lists retain scroll state during pagination or item updates.
 */
@Composable
fun <T> RavenContentCrossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "ravenContentCrossfade",
    content: @Composable (T) -> Unit,
) {
    val motionReduced = LocalMotionReduced.current
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            if (motionReduced) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                val enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
                        easing = RavenMotion.HarmonyDecelerationEasing,
                    ),
                ) + scaleIn(
                    initialScale = 0.988f,
                    animationSpec = tween(
                        durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
                        easing = RavenMotion.HarmonyDecelerationEasing,
                    ),
                )
                val exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 150,
                        easing = RavenMotion.HarmonyDecelerationEasing,
                    ),
                )
                enter togetherWith exit
            }
        },
        modifier = modifier,
        label = label,
    ) { state ->
        content(state)
    }
}
