package com.raven.app.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.motion.MorphIconEngine
import com.raven.app.core.designsystem.motion.MorphPlan
import com.raven.app.core.designsystem.motion.MorphSpring
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphGlyph
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import kotlin.math.min

private class MorphIconRuntimeState(
    initialGlyph: RavenMorphGlyph,
    preset: MorphSpringPreset,
) {
    var targetGlyph: RavenMorphGlyph = initialGlyph
    var plan: MorphPlan = MorphIconEngine.buildPlan(initialGlyph, initialGlyph)
    var buffers: MutableList<FloatArray> = plan.allocateBuffers().toMutableList()
    var paths: MutableList<Path> = MutableList(plan.pairs.size) { Path() }
    val spring: MorphSpring = MorphSpring(preset)
    var frameTick by mutableFloatStateOf(0f)

    init {
        spring.snapTo(1f)
        MorphIconEngine.interpolatePlan(plan, 1f, buffers)
    }

    fun ensureCapacity(newPlan: MorphPlan) {
        val needed = newPlan.pairs.size
        while (buffers.size < needed) {
            buffers.add(FloatArray(newPlan.pairs[buffers.size].sampleCount * 2))
            paths.add(Path())
        }
        for (i in 0 until needed) {
            val reqFloats = newPlan.pairs[i].sampleCount * 2
            if (buffers[i].size != reqFloats) {
                buffers[i] = FloatArray(reqFloats)
            }
        }
    }
}

/**
 * Renders a stroke-based [RavenMorphGlyph] inside a canonical `24x24` viewBox and smoothly
 * morphs between glyph transitions via Procrustes polar interpolation and an interruptible
 * semi-implicit Euler spring.
 *
 * - Runs a `withFrameNanos` loop ONLY while the spring is active (zero idle GPU/CPU cost).
 * - Snapshots in-flight geometry and preserves clamped spring velocity when [glyph] changes mid-morph.
 * - Snaps immediately with zero frame loops when [LocalMotionReduced] is `true`.
 */
@Composable
fun RavenMorphIcon(
    glyph: RavenMorphGlyph,
    tint: Color = LocalContentColor.current,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    strokeWidth: Dp = 2.dp,
    springPreset: MorphSpringPreset = MorphSpringPreset.SNAPPY,
    contentDescription: String? = null,
) {
    val motionReduced = LocalMotionReduced.current
    val runtime = remember { MorphIconRuntimeState(glyph, springPreset) }

    LaunchedEffect(glyph, motionReduced, springPreset) {
        runtime.spring.preset = springPreset
        val glyphChanged = runtime.targetGlyph != glyph
        if (motionReduced) {
            if (glyphChanged || runtime.spring.isRunning) {
                val staticPlan = MorphIconEngine.buildPlan(glyph, glyph)
                runtime.ensureCapacity(staticPlan)
                runtime.plan = staticPlan
                runtime.targetGlyph = glyph
                runtime.spring.snapTo(1f)
                MorphIconEngine.interpolatePlan(staticPlan, 1f, runtime.buffers)
                runtime.frameTick += 1f
            }
            return@LaunchedEffect
        }

        if (glyphChanged) {
            val currentT = runtime.spring.value
            val currentSnapshot = MorphIconEngine.snapshotBuffers(runtime.plan, runtime.buffers, currentT)
            val targetSampled = MorphIconEngine.resampleGlyph(glyph)
            val nextPlan = MorphIconEngine.buildPlan(currentSnapshot, targetSampled)
            runtime.ensureCapacity(nextPlan)
            runtime.plan = nextPlan
            runtime.targetGlyph = glyph
            runtime.spring.start(fromValue = 0f, toValue = 1f, preserveVelocity = true)
            MorphIconEngine.interpolatePlan(nextPlan, 0f, runtime.buffers)
            runtime.frameTick += 1f
        }

        if (runtime.spring.isRunning) {
            var lastNanos = withFrameNanos { it }
            while (runtime.spring.isRunning) {
                val nowNanos = withFrameNanos { it }
                val dt = ((nowNanos - lastNanos).coerceAtLeast(0L)) / 1_000_000_000f
                lastNanos = nowNanos
                runtime.spring.step(dt)
                MorphIconEngine.interpolatePlan(runtime.plan, runtime.spring.value, runtime.buffers)
                runtime.frameTick += 1f
            }
        }
    }

    val semanticsModifier = if (!contentDescription.isNullOrBlank()) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            this.role = Role.Image
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .size(size)
            .then(semanticsModifier),
    ) {
        // Observe frameTick strictly inside draw scope to avoid recomposition during morphing.
        @Suppress("UNUSED_VARIABLE")
        val tick = runtime.frameTick

        val viewBoxScale = min(this.size.width, this.size.height) / 24f
        val offsetX = (this.size.width - 24f * viewBoxScale) * 0.5f
        val offsetY = (this.size.height - 24f * viewBoxScale) * 0.5f
        val strokePx = strokeWidth.toPx()

        val plan = runtime.plan
        val t = runtime.spring.value.coerceIn(0f, 1f)
        val pairCount = plan.pairs.size

        for (i in 0 until pairCount) {
            val pair = plan.pairs[i]
            val pts = runtime.buffers[i]
            val pointCount = min(pair.sampleCount, pts.size / 2)
            if (pointCount < 2) continue

            val path = runtime.paths[i]
            path.rewind()
            path.moveTo(
                pts[0] * viewBoxScale + offsetX,
                pts[1] * viewBoxScale + offsetY,
            )
            for (k in 1 until pointCount) {
                val idx = k * 2
                path.lineTo(
                    pts[idx] * viewBoxScale + offsetX,
                    pts[idx + 1] * viewBoxScale + offsetY,
                )
            }
            val closed = if (t < 0.5f) pair.fromClosed else pair.toClosed
            if (closed) {
                path.close()
            }

            val fillAlpha = when {
                pair.fromFill && pair.toFill -> 1f
                !pair.fromFill && !pair.toFill -> 0f
                pair.toFill -> t
                else -> 1f - t
            }.coerceIn(0f, 1f)

            val strokeAlpha = (1f - fillAlpha).coerceIn(0f, 1f)

            if (fillAlpha > 0.01f) {
                drawPath(
                    path = path,
                    color = tint.copy(alpha = tint.alpha * fillAlpha),
                    style = Fill,
                )
            }
            if (strokeAlpha > 0.01f) {
                drawPath(
                    path = path,
                    color = tint.copy(alpha = tint.alpha * strokeAlpha),
                    style = Stroke(
                        width = strokePx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

/**
 * Convenience toggle icon pairing [RavenMorphIcon] with animated [activeTint] / [inactiveTint]
 * color transition and an optional subtle activation scale kick, respecting [LocalMotionReduced].
 */
@Composable
fun RavenMorphToggleIcon(
    active: Boolean,
    activeGlyph: RavenMorphGlyph,
    inactiveGlyph: RavenMorphGlyph,
    activeTint: Color = LocalRavenColors.current.crimson,
    inactiveTint: Color = LocalRavenColors.current.inkSecondary,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    strokeWidth: Dp = 2.dp,
    springPreset: MorphSpringPreset = MorphSpringPreset.SNAPPY,
    scaleKick: Boolean = true,
    contentDescription: String? = null,
) {
    val motionReduced = LocalMotionReduced.current
    val resolvedTint by animateColorAsState(
        targetValue = if (active) activeTint else inactiveTint,
        animationSpec = if (motionReduced) {
            snap()
        } else {
            tween(
                durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            )
        },
        label = "ravenMorphToggleTint",
    )

    val scaleAnim = remember { Animatable(1f) }
    val kickTriggered = remember { booleanArrayOf(false) }

    LaunchedEffect(active, motionReduced, scaleKick) {
        if (motionReduced || !scaleKick) {
            scaleAnim.snapTo(1f)
            kickTriggered[0] = active
            return@LaunchedEffect
        }
        if (active && !kickTriggered[0]) {
            scaleAnim.snapTo(1f)
            scaleAnim.animateTo(
                targetValue = 1.14f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
            )
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 550f),
            )
        } else if (!active) {
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 600f),
            )
        }
        kickTriggered[0] = active
    }

    RavenMorphIcon(
        glyph = if (active) activeGlyph else inactiveGlyph,
        tint = resolvedTint,
        modifier = modifier.graphicsLayer {
            val s = scaleAnim.value
            scaleX = s
            scaleY = s
        },
        size = size,
        strokeWidth = strokeWidth,
        springPreset = springPreset,
        contentDescription = contentDescription,
    )
}

/**
 * Interactive icon button wrapping [RavenIconButton] (`>= 48dp` touch target, haptics, hover/focus
 * borders, and spring press) with a morphing [RavenMorphIcon].
 */
@Composable
fun RavenMorphIconButton(
    glyph: RavenMorphGlyph,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    containerColor: Color = Color.Transparent,
    tint: Color = LocalRavenColors.current.inkPrimary,
    iconSize: Dp = 22.dp,
    strokeWidth: Dp = 2.dp,
    springPreset: MorphSpringPreset = MorphSpringPreset.SNAPPY,
) {
    RavenIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        containerColor = containerColor,
        contentColor = tint,
        contentDescription = contentDescription,
    ) {
        RavenMorphIcon(
            glyph = glyph,
            tint = LocalContentColor.current,
            size = iconSize,
            strokeWidth = strokeWidth,
            springPreset = springPreset,
            contentDescription = null,
        )
    }
}
