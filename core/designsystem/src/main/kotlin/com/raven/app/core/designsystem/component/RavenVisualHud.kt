package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.designsystem.motion.RavenMorphGlyph
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFrostedBox
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle

/**
 * Frosted centered capsule HUD for video player brightness, volume, seek, and speed gestures
 * (as well as comic/photo reader brightness and zoom readouts).
 *
 * Renders an icon, optional horizontal progress indicator, and non-jittering tabular label.
 */
@Composable
fun RavenVisualHud(
    visible: Boolean,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    accentColor: Color = LocalRavenColors.current.inkPrimary,
) {
    val colors = LocalRavenColors.current
    RavenVisualHudLayout(
        visible = visible,
        label = label,
        modifier = modifier,
        progress = progress,
        accentColor = accentColor,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.inkPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Morphing-glyph overload of [RavenVisualHud] using [RavenMorphIcon] inside the same frosted
 * capsule HUD layout.
 */
@Composable
fun RavenVisualHud(
    visible: Boolean,
    glyph: RavenMorphGlyph,
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    accentColor: Color = LocalRavenColors.current.inkPrimary,
) {
    val colors = LocalRavenColors.current
    RavenVisualHudLayout(
        visible = visible,
        label = label,
        modifier = modifier,
        progress = progress,
        accentColor = accentColor,
    ) {
        RavenMorphIcon(
            glyph = glyph,
            tint = colors.inkPrimary,
            size = 20.dp,
        )
    }
}

@Composable
private fun RavenVisualHudLayout(
    visible: Boolean,
    label: String,
    modifier: Modifier,
    progress: Float?,
    accentColor: Color,
    iconContent: @Composable () -> Unit,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val motionReduced = LocalMotionReduced.current

    val enterTransition = if (motionReduced) {
        fadeIn(snap())
    } else {
        fadeIn(
            tween(
                durationMillis = 120,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
        ) + scaleIn(
            initialScale = 0.92f,
            animationSpec = spring(
                dampingRatio = 0.72f,
                stiffness = 520f,
            ),
        )
    }
    val exitTransition = if (motionReduced) {
        fadeOut(snap())
    } else {
        fadeOut(
            tween(
                durationMillis = 150,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
        ) + scaleOut(
            targetScale = 0.94f,
            animationSpec = tween(
                durationMillis = 150,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = enterTransition,
        exit = exitTransition,
        modifier = modifier.testTag("raven_visual_hud"),
    ) {
        RavenFrostedBox(
            shape = RavenShapes.PillShape,
            reduceTransparency = reduceTransparency,
            borderColor = colors.rimHighlight,
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                iconContent()

                if (progress != null) {
                    val target = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
                    val animatedProgressState = animateFloatAsState(
                        targetValue = target,
                        animationSpec = if (motionReduced) snap() else tween(durationMillis = 80),
                        label = "ravenHudProgress",
                    )
                    Box(
                        modifier = Modifier
                            .width(108.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(colors.surface3),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .graphicsLayer {
                                    scaleX = animatedProgressState.value.coerceIn(0f, 1f)
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                                .clip(CircleShape)
                                .background(accentColor),
                        )
                    }
                }

                Text(
                    text = label,
                    style = RavenTabularTextStyle.copy(
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.inkPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}
