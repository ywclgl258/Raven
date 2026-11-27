package com.raven.app.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.designsystem.motion.NOTIF_POP_SCALE
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val BURST_DURATION_MS: Int = 380
private const val BURST_SPARK_COUNT: Int = 7
internal const val SQUASH_PRESS_SX: Float = 1.04f
internal const val SQUASH_PRESS_SY: Float = 1f / SQUASH_PRESS_SX

internal fun ravenBurstSparkEnvelope(progress: Float): Float {
    if (!progress.isFinite()) return 0f
    return sin(progress.coerceIn(0f, 1f) * PI.toFloat()).coerceIn(0f, 1f)
}

internal fun ravenBurstSparkScale(index: Int): Float {
    val slot = ((index * 2) % 3 + 3) % 3
    return 0.88f + slot * 0.12f
}

internal fun shouldRestartBurst(isRunning: Boolean, progress: Float): Boolean =
    !isRunning || !progress.isFinite() || progress <= 0f || progress >= 1f

/**
 * Zero-recomposition `ModifierNodeElement` that fires a crisp 380ms radial hairline halo and
 * gently varied spiral micro-sparks whenever [trigger] transitions to a truthy or incremented
 * active state. Retriggers coalesce with the visible burst instead of snapping it back to zero.
 *
 * No-ops on initial composition and whenever [LocalMotionReduced] is true. Resolves
 * [Color.Unspecified] via `currentValueOf(LocalRavenColors).crimson`.
 */
fun Modifier.ravenBurstFeedback(
    trigger: Any?,
    burstColor: Color = Color.Unspecified,
    enabled: Boolean = true,
): Modifier = this then RavenBurstFeedbackElement(
    trigger = trigger,
    burstColor = burstColor,
    enabled = enabled,
)

private data class RavenBurstFeedbackElement(
    val trigger: Any?,
    val burstColor: Color,
    val enabled: Boolean,
) : ModifierNodeElement<RavenBurstFeedbackNode>() {
    override fun create(): RavenBurstFeedbackNode = RavenBurstFeedbackNode(
        initialTrigger = trigger,
        burstColor = burstColor,
        enabled = enabled,
    )

    override fun update(node: RavenBurstFeedbackNode) {
        node.updateParams(
            newTrigger = trigger,
            newBurstColor = burstColor,
            newEnabled = enabled,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "ravenBurstFeedback"
        properties["trigger"] = trigger
        properties["burstColor"] = burstColor
        properties["enabled"] = enabled
    }
}

private class RavenBurstFeedbackNode(
    initialTrigger: Any?,
    private var burstColor: Color,
    private var enabled: Boolean,
) : Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {

    private var lastTrigger: Any? = initialTrigger
    private val burstProgress = Animatable(0f)
    private var burstJob: Job? = null

    override val shouldAutoInvalidate: Boolean get() = false

    override fun onDetach() {
        burstJob?.cancel()
    }

    fun updateParams(
        newTrigger: Any?,
        newBurstColor: Color,
        newEnabled: Boolean,
    ) {
        val prev = lastTrigger
        lastTrigger = newTrigger
        val visualChanged = burstColor != newBurstColor || enabled != newEnabled
        burstColor = newBurstColor
        enabled = newEnabled

        if (!enabled) {
            burstJob?.cancel()
            if (isAttached) {
                burstJob = coroutineScope.launch {
                    burstProgress.snapTo(0f)
                }
            }
            invalidateDraw()
            return
        }

        if (shouldFireBurst(previous = prev, current = newTrigger)) {
            fireBurst()
        } else if (visualChanged) {
            invalidateDraw()
        }
    }

    private fun fireBurst() {
        if (!isAttached || !enabled) return
        if (!shouldRestartBurst(burstJob?.isActive == true, burstProgress.value)) return
        if (currentValueOf(LocalMotionReduced)) {
            burstJob?.cancel()
            burstJob = coroutineScope.launch {
                burstProgress.snapTo(0f)
            }
            return
        }
        burstJob?.cancel()
        burstJob = coroutineScope.launch {
            burstProgress.snapTo(0.001f)
            burstProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = BURST_DURATION_MS,
                    easing = RavenMotion.HarmonyDecelerationEasing,
                ),
            )
            burstProgress.snapTo(0f)
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val progress = burstProgress.value
        if (!enabled || currentValueOf(LocalMotionReduced) || progress <= 0f || progress >= 1f || size.minDimension <= 0f) return

        val resolvedColor = if (burstColor == Color.Unspecified) {
            currentValueOf(LocalRavenColors).crimson
        } else {
            burstColor
        }
        val baseRadius = size.minDimension * 0.5f
        val haloRadius = baseRadius * (0.55f + 0.95f * progress)
        val haloAlpha = ((1f - progress) * 0.65f).coerceIn(0f, 1f)

        drawCircle(
            color = resolvedColor.copy(alpha = haloAlpha),
            radius = haloRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )

        val sparkEnvelope = ravenBurstSparkEnvelope(progress)
        val sparkRadius = (1.1.dp.toPx() * (0.6f + 0.5f * sparkEnvelope)).coerceAtLeast(1f)
        val sparkAlpha = (sparkEnvelope * 0.90f).coerceIn(0f, 1f)
        val twoPi = (2.0 * PI).toFloat()

        for (i in 0 until BURST_SPARK_COUNT) {
            val baseAngle = twoPi * i / BURST_SPARK_COUNT
            val spiralAngle = baseAngle + progress * 0.72f
            val distanceScale = 0.96f + (i % 3) * 0.04f
            val dist = baseRadius * (0.48f + 1.12f * progress) * distanceScale
            drawCircle(
                color = resolvedColor.copy(alpha = sparkAlpha),
                radius = sparkRadius * ravenBurstSparkScale(i),
                center = Offset(
                    x = center.x + dist * cos(spiralAngle),
                    y = center.y + dist * sin(spiralAngle),
                ),
            )
        }
    }
}

private fun shouldFireBurst(previous: Any?, current: Any?): Boolean {
    if (previous == current || current == null) return false
    return when (current) {
        is Boolean -> current
        is Number -> {
            val currVal = current.toDouble()
            if (!currVal.isFinite()) {
                false
            } else if (previous is Number && previous.toDouble().isFinite()) {
                currVal > previous.toDouble()
            } else {
                currVal > 0.0
            }
        }
        else -> true
    }
}

/**
 * Volume-preserving organic squash-and-stretch press feedback (`sx = 1.04f`, `sy = 1 / sx` on
 * press, gently damped spring rebound on release) via [ModifierNodeElement], respecting [LocalMotionReduced].
 */
fun Modifier.ravenSquashPress(
    interactionSource: InteractionSource? = null,
    enabled: Boolean = true,
): Modifier = this then RavenSquashPressElement(
    interactionSource = interactionSource,
    enabled = enabled,
)

private data class RavenSquashPressElement(
    val interactionSource: InteractionSource?,
    val enabled: Boolean,
) : ModifierNodeElement<RavenSquashPressNode>() {
    override fun create(): RavenSquashPressNode = RavenSquashPressNode(
        interactionSource = interactionSource,
        enabled = enabled,
    )

    override fun update(node: RavenSquashPressNode) {
        node.updateParams(
            newSource = interactionSource,
            newEnabled = enabled,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "ravenSquashPress"
        properties["enabled"] = enabled
    }
}

private class RavenSquashPressNode(
    private var interactionSource: InteractionSource?,
    private var enabled: Boolean,
) : Modifier.Node(),
    LayoutModifierNode,
    PointerInputModifierNode,
    CompositionLocalConsumerModifierNode {

    private val sxAnim = Animatable(1f)
    private var pointerPressed = false
    private var interactionJob: Job? = null
    private var animJob: Job? = null
    private val activePresses = mutableListOf<PressInteraction.Press>()

    override fun onAttach() {
        startCollectingInteractions()
    }

    override fun onDetach() {
        interactionJob?.cancel()
        animJob?.cancel()
        activePresses.clear()
        pointerPressed = false
    }

    fun updateParams(
        newSource: InteractionSource?,
        newEnabled: Boolean,
    ) {
        val sourceChanged = interactionSource !== newSource
        interactionSource = newSource
        enabled = newEnabled
        if (sourceChanged) {
            activePresses.clear()
            pointerPressed = false
            startCollectingInteractions()
        }
        if (!newEnabled) {
            pointerPressed = false
        }
        animateSquash()
    }

    private fun startCollectingInteractions() {
        interactionJob?.cancel()
        val source = interactionSource ?: return
        if (!isAttached) return
        interactionJob = coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        activePresses.add(interaction)
                        animateSquash()
                    }
                    is PressInteraction.Release -> {
                        activePresses.remove(interaction.press)
                        animateSquash()
                    }
                    is PressInteraction.Cancel -> {
                        activePresses.remove(interaction.press)
                        animateSquash()
                    }
                }
            }
        }
    }

    private fun animateSquash() {
        if (!isAttached) return
        val isPressed = enabled && if (interactionSource != null) {
            activePresses.isNotEmpty()
        } else {
            pointerPressed
        }
        val targetSx = if (isPressed) SQUASH_PRESS_SX else 1f
        val previousVelocity = sxAnim.velocity
        val motionReduced = currentValueOf(LocalMotionReduced)

        animJob?.cancel()
        animJob = coroutineScope.launch {
            if (motionReduced) {
                sxAnim.snapTo(targetSx)
            } else {
                val spec = if (isPressed) {
                    RavenMotion.pressScaleSpring
                } else {
                    spring(dampingRatio = 0.72f, stiffness = 460f, visibilityThreshold = 0.0004f)
                }
                sxAnim.animateTo(targetSx, spec, initialVelocity = previousVelocity)
            }
        }
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: androidx.compose.ui.unit.IntSize,
    ) {
        if (interactionSource != null || !enabled || pass != PointerEventPass.Main) return
        val nextPressed = pointerEvent.changes.any { it.pressed }
        if (nextPressed != pointerPressed) {
            pointerPressed = nextPressed
            animateSquash()
        }
    }

    override fun onCancelPointerInput() {
        if (pointerPressed) {
            pointerPressed = false
            animateSquash()
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val stretch = sxAnim.value
                scaleX = stretch
                scaleY = 1f / stretch
            }
        }
    }
}

/**
 * Compact numeric pill badge with a bounded [NOTIF_POP_SCALE] (`1.08f`) two-stage pop whenever
 * [count] increases, preserving [RavenTabularTextStyle] and `99+` overflow formatting.
 * Rapid count changes continue from the visible scale without a snap or an abandoned mid-pop state.
 * Renders nothing when `count <= 0`.
 */
@Composable
fun RavenPopBadge(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    maxCount: Int = 99,
) {
    if (count <= 0) return

    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val resolvedContainer = if (containerColor == Color.Unspecified) colors.crimson else containerColor
    val resolvedContent = if (contentColor == Color.Unspecified) colors.inkPrimary else contentColor
    val popScale = remember { Animatable(1f) }
    var lastCount by remember { mutableIntStateOf(count) }

    LaunchedEffect(count, motionReduced) {
        if (motionReduced) {
            lastCount = count
            popScale.snapTo(1f)
            return@LaunchedEffect
        }
        if (count > lastCount) {
            popScale.animateTo(
                targetValue = NOTIF_POP_SCALE,
                animationSpec = tween(
                    durationMillis = 90,
                    easing = RavenMotion.HarmonyDecelerationEasing,
                ),
            )
            popScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 180,
                    easing = RavenMotion.HarmonyDecelerationEasing,
                ),
            )
        } else {
            popScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 120,
                    easing = RavenMotion.HarmonyDecelerationEasing,
                ),
            )
        }
        lastCount = count
    }

    val safeMax = maxCount.coerceAtLeast(1)
    val badgeText = if (count > safeMax) "${safeMax}+" else count.toString()

    Box(
        modifier = modifier
            .graphicsLayer {
                val s = popScale.value
                scaleX = s
                scaleY = s
            }
            .defaultMinSize(minWidth = 18.dp, minHeight = 16.dp)
            .clip(CircleShape)
            .background(resolvedContainer)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badgeText,
            style = RavenTabularTextStyle.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = resolvedContent,
            maxLines = 1,
            softWrap = false,
        )
    }
}
