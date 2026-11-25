package com.raven.app.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import kotlin.math.roundToInt

/**
 * Apple HIG inset grouped container (`Surface1`, `14dp` corners, `0.5dp` `Hairline` rim).
 * Use inside settings screens, preferences sheets, and single-screen form sheets instead of
 * chained `AlertDialog` workflows.
 */
@Composable
fun RavenGroupedSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalRavenColors.current
    val sectionRimBrush = remember(colors.rimHighlight, colors.hairline) {
        Brush.verticalGradient(
            colors = listOf(colors.rimHighlight, colors.hairline),
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { heading() },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RavenShapes.CardShape)
                .background(colors.surface1)
                .border(0.5.dp, sectionRimBrush, RavenShapes.CardShape),
            content = content,
        )

        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkTertiary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * Crisp `0.5dp` Apple HIG inset hairline divider between rows inside [RavenGroupedSection].
 */
@Composable
fun RavenGroupedDivider(
    modifier: Modifier = Modifier,
    insetWithIcon: Boolean = false,
) {
    val colors = LocalRavenColors.current
    HorizontalDivider(
        modifier = modifier.padding(start = if (insetWithIcon) 60.dp else 16.dp),
        thickness = 0.5.dp,
        color = colors.hairline,
    )
}

/**
 * Apple HIG inset grouped list row supporting optional leading icon badge, title, subtitle,
 * tabular value readout, trailing chevron, custom trailing slot, and destructive styling.
 */
@Composable
fun RavenGroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMaxLines: Int = 3,
    value: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalRavenColors.current.inkPrimary,
    iconContainerColor: Color = LocalRavenColors.current.surface2,
    showChevron: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowBackdropColor by animateColorAsState(
        targetValue = if (pressed && enabled && onClick != null) {
            colors.surface2.copy(alpha = 0.72f)
        } else {
            Color.Transparent
        },
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
        label = "ravenGroupedRowPressHighlight",
    )

    val resolvedTitleColor = when {
        !enabled -> colors.inkTertiary
        destructive -> colors.error
        else -> colors.inkPrimary
    }
    val resolvedIconTint = when {
        !enabled -> colors.inkTertiary
        destructive -> colors.error
        else -> iconTint
    }
    val resolvedIconBg = when {
        destructive -> colors.error.copy(alpha = 0.14f)
        else -> iconContainerColor
    }

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
            .heightIn(min = 52.dp)
            .background(rowBackdropColor)
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(resolvedIconBg)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = resolvedIconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = resolvedTitleColor,
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
            )
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(10.dp))
            trailingContent()
        }

        if (showChevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.inkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Full-row toggleable switch row designed for [RavenGroupedSection].
 */
@Composable
fun RavenGroupedSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalRavenColors.current.inkPrimary,
    iconContainerColor: Color = LocalRavenColors.current.surface2,
    enabled: Boolean = true,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val switchBackdropColor by animateColorAsState(
        targetValue = if (pressed && enabled) colors.surface2.copy(alpha = 0.72f) else Color.Transparent,
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
        label = "ravenSwitchRowPressHighlight",
    )

    RavenGroupedRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        iconContainerColor = iconContainerColor,
        enabled = enabled,
        modifier = modifier
            .background(switchBackdropColor)
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { nextChecked ->
                    feedback.perform(
                        if (nextChecked) RavenFeedbackEffect.TOGGLE_ON else RavenFeedbackEffect.TOGGLE_OFF,
                    )
                    onCheckedChange(nextChecked)
                },
            ),
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
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
        },
    )
}

/**
 * Grouped slider row with live tabular value readout for playback speed, seek step, brightness,
 * and reader crop controls inside [RavenGroupedSection].
 */
@Composable
fun RavenGroupedSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalRavenColors.current.inkPrimary,
    iconContainerColor: Color = LocalRavenColors.current.surface2,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    fun stepIndexFor(v: Float): Int {
        if (steps <= 0) return -1
        val norm = ((v.coerceIn(valueRange.start, valueRange.endInclusive) - valueRange.start) / span)
        return (norm * (steps + 1)).roundToInt()
    }
    var lastStepIndex by remember(steps, valueRange) { mutableIntStateOf(stepIndexFor(value)) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RavenShapes.ControlShape)
                        .background(iconContainerColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) iconTint else colors.inkTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = if (enabled) colors.inkPrimary else colors.inkTertiary,
                maxLines = 1,
            )
        }

        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { next ->
                val nextStep = stepIndexFor(next)
                if (steps > 0 && nextStep != lastStepIndex) {
                    lastStepIndex = nextStep
                    feedback.perform(RavenFeedbackEffect.TAB_SWITCH, hapticOnly = true)
                }
                onValueChange(next)
            },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = {
                feedback.perform(RavenFeedbackEffect.TAP)
                onValueChangeFinished?.invoke()
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.inkPrimary,
                activeTrackColor = colors.crimson,
                inactiveTrackColor = colors.surface3,
                activeTickColor = colors.inkPrimary.copy(alpha = 0.55f),
                inactiveTickColor = colors.inkTertiary.copy(alpha = 0.55f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
