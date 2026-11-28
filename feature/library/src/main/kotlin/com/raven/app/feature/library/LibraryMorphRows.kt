package com.raven.app.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.RavenMorphToggleIcon
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphGlyph
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.designsystem.theme.tabular

/** 资料库通用徽章与 Morph 分组行。 */

@Composable
internal fun SelectCheckBadge(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) colors.crimson else colors.surface2)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) colors.crimson else colors.outlineSubtle,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "已选中",
                tint = colors.inkPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
internal fun LibraryMorphPinSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val rowBackdropColor by animateColorAsState(
        targetValue = when {
            pressed -> colors.surface2.copy(alpha = 0.72f)
            hovered || focused -> colors.surface2.copy(alpha = 0.42f)
            else -> Color.Transparent
        },
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
        label = "libraryMorphPinSwitchBackdrop",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(rowBackdropColor)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = RectangleShape,
                enabled = true,
                glowColor = colors.interactiveGlow,
                highlightAlpha = 0.04f,
            )
            .toggleable(
                value = checked,
                enabled = true,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { nextChecked ->
                    feedback.perform(
                        if (nextChecked) RavenFeedbackEffect.TOGGLE_ON else RavenFeedbackEffect.TOGGLE_OFF,
                    )
                    onCheckedChange(nextChecked)
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RavenShapes.ControlShape)
                .background(colors.surface2)
                .border(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.lightRimTop, colors.hairline),
                    ),
                    shape = RavenShapes.ControlShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            RavenMorphToggleIcon(
                active = checked,
                activeGlyph = RavenMorphIcons.PinFilled,
                inactiveGlyph = RavenMorphIcons.PinOutline,
                activeTint = colors.crimson,
                inactiveTint = colors.inkPrimary,
                size = 18.dp,
                springPreset = MorphSpringPreset.SNAPPY,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier.ravenBurstFeedback(
                trigger = checked,
                burstColor = colors.crimson,
            ),
        ) {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.inkPrimary,
                    checkedTrackColor = colors.crimson,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = colors.inkSecondary,
                    uncheckedTrackColor = colors.surface3,
                    uncheckedBorderColor = colors.outlineSubtle,
                    disabledCheckedTrackColor = colors.crimson.copy(alpha = 0.4f),
                    disabledUncheckedTrackColor = colors.surface2,
                ),
            )
        }
    }
}

/**
 * Inset grouped list row mirroring `RavenGroupedRow` with a leading [RavenMorphIcon] badge and
 * optional [burstTrigger] radial feedback on state transitions.
 */
@Composable
internal fun LibraryMorphGroupedRow(
    title: String,
    glyph: RavenMorphGlyph,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMaxLines: Int = 3,
    value: String? = null,
    iconTint: Color = LocalRavenColors.current.inkPrimary,
    iconContainerColor: Color = LocalRavenColors.current.surface2,
    springPreset: MorphSpringPreset = MorphSpringPreset.SNAPPY,
    burstTrigger: Any? = null,
    burstColor: Color = LocalRavenColors.current.crimson,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val rowBackdropColor by animateColorAsState(
        targetValue = when {
            !enabled || onClick == null -> Color.Transparent
            pressed -> colors.surface2.copy(alpha = 0.72f)
            hovered || focused -> colors.surface2.copy(alpha = 0.42f)
            else -> Color.Transparent
        },
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
        label = "libraryMorphGroupedRowPressHighlight",
    )
    val iconScaleState = animateFloatAsState(
        targetValue = if (pressed && enabled && onClick != null) 1.08f else 1.0f,
        animationSpec = if (motionReduced) snap() else RavenMotion.responsiveSpringSpec(),
        label = "libraryMorphGroupedRowIconScale",
    )

    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = {
                feedback.perform(RavenFeedbackEffect.TAP)
                onClick()
            },
        )
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(rowBackdropColor)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = RectangleShape,
                enabled = enabled && onClick != null,
                glowColor = colors.interactiveGlow,
                highlightAlpha = 0.04f,
            )
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer {
                    val s = iconScaleState.value
                    scaleX = s
                    scaleY = s
                }
                .then(
                    if (burstTrigger != null) {
                        Modifier.ravenBurstFeedback(
                            trigger = burstTrigger,
                            burstColor = burstColor,
                            enabled = enabled,
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(RavenShapes.ControlShape)
                .background(iconContainerColor)
                .border(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.lightRimTop, colors.hairline),
                    ),
                    shape = RavenShapes.ControlShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            RavenMorphIcon(
                glyph = glyph,
                tint = if (enabled) iconTint else colors.inkTertiary,
                size = 18.dp,
                springPreset = springPreset,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.inkPrimary else colors.inkTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) colors.inkSecondary else colors.inkTertiary,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!value.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 148.dp),
            )
        }
    }
}
