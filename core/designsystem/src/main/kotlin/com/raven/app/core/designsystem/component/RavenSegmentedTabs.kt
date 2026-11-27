package com.raven.app.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import kotlin.math.abs

private val SegmentInset = 3.dp

internal fun segmentIndicatorTranslation(animatedIndex: Float, count: Int, segmentWidthPx: Float): Float {
    if (count <= 0 || !segmentWidthPx.isFinite() || segmentWidthPx <= 0f || !animatedIndex.isFinite()) return 0f
    return segmentWidthPx * animatedIndex.coerceIn(0f, (count - 1).toFloat())
}

/** A sliding indicator for primary mode switches, with a scrollable track on narrow layouts. */
@Composable
fun <T> RavenSegmentedTabs(
    items: List<T>,
    selectedItem: T,
    labelProvider: (T) -> String,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val motionReduced = LocalMotionReduced.current
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    val indicatorIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(
            durationMillis = if (motionReduced) 0 else RavenMotion.TAB_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        label = "ravenSegmentedTabsIndicator",
    )
    val scroll = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.ControlShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
    ) {
        val segmentWidth = maxOf(maxWidth / items.size, 84.dp * density.fontScale)
        val segmentPx = with(density) { segmentWidth.roundToPx() }
        val viewportPx = with(density) { maxWidth.roundToPx() }
        // Use the same rounded segment width for the row, the indicator and scroll positioning.
        val measuredSegmentWidth = with(density) { segmentPx.toDp() }

        LaunchedEffect(selectedIndex, segmentPx, viewportPx, motionReduced) {
            val start = selectedIndex * segmentPx
            val end = start + segmentPx
            val target = scroll.value.coerceIn((end - viewportPx).coerceAtLeast(0).coerceAtMost(start), start)
            if (target != scroll.value) {
                if (motionReduced) scroll.scrollTo(target)
                else scroll.animateScrollTo(target, tween(RavenMotion.TAB_TRANSITION_DURATION_MS))
            }
        }

        Box(Modifier.horizontalScroll(scroll).width(measuredSegmentWidth * items.size)) {
            Box(Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(measuredSegmentWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            val index = if (motionReduced) selectedIndex.toFloat() else indicatorIndex
                            translationX = direction * segmentIndicatorTranslation(index, items.size, size.width)
                        }
                        .padding(SegmentInset)
                        .clip(RavenShapes.ControlShape)
                        .background(colors.surface3),
                ) {
                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                            .width(16.dp).height(2.dp).clip(CircleShape).background(colors.crimson),
                    )
                }
            }
            Row(modifier = Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .width(measuredSegmentWidth)
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = selected,
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    feedback.perform(if (selected) RavenFeedbackEffect.TAP else RavenFeedbackEffect.TAB_SWITCH)
                                    onItemSelected(item)
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val position = if (motionReduced) selectedIndex.toFloat() else indicatorIndex
                        val emphasis = (1f - abs(position - index)).coerceIn(0f, 1f)
                        Text(
                            text = labelProvider(item),
                            style = MaterialTheme.typography.labelLarge.tabular().copy(fontWeight = FontWeight.SemiBold),
                            color = lerp(colors.inkSecondary, colors.inkPrimary, emphasis),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
