package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion

private const val ExpandDurationMillis = 200

/**
 * Inset grouped accordion: a [RavenGroupedRow] header plus content that expands with one
 * synchronized height + fade transition. The chevron rotation shares the same duration/easing
 * as the content, so the affordance never disagrees with the visible state.
 *
 * [modifier] applies to the header row (keeps existing test tags tappable). Honors
 * [LocalMotionReduced] by switching content instantly without a chevron spin.
 */
@Composable
fun RavenExpandableSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMaxLines: Int = 3,
    value: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    expandLabel: String = "展开",
    collapseLabel: String = "收起",
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val duration = if (motionReduced) 0 else ExpandDurationMillis
    val transition = updateTransition(expanded, label = "ravenExpandableSection")
    val chevronRotation by transition.animateFloat(
        transitionSpec = { tween(duration, easing = RavenMotion.HarmonyDecelerationEasing) },
        label = "ravenExpandableChevron",
    ) { open -> if (open) 180f else 0f }

    Column(modifier = Modifier.fillMaxWidth()) {
        RavenGroupedRow(
            title = title,
            subtitle = subtitle,
            subtitleMaxLines = subtitleMaxLines,
            value = value,
            icon = icon,
            enabled = enabled,
            onClick = { onExpandedChange(!expanded) },
            modifier = modifier,
            trailingContent = {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) collapseLabel else expandLabel,
                    tint = colors.inkSecondary,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                )
            },
        )

        transition.AnimatedVisibility(
            visible = { it },
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                expandVertically(tween(duration, easing = RavenMotion.HarmonyDecelerationEasing), expandFrom = Alignment.Top) +
                    fadeIn(tween(duration, easing = RavenMotion.HarmonyDecelerationEasing))
            },
            exit = if (motionReduced) {
                ExitTransition.None
            } else {
                shrinkVertically(tween(duration, easing = RavenMotion.HarmonyDecelerationEasing), shrinkTowards = Alignment.Top) +
                    fadeOut(tween(duration, easing = RavenMotion.HarmonyDecelerationEasing))
            },
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}
