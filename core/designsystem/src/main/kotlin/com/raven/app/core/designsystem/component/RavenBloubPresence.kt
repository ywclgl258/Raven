package com.raven.app.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.motion.BloubMood
import com.raven.app.core.designsystem.motion.BloubOrbitArc
import com.raven.app.core.designsystem.motion.BloubSilhouette
import com.raven.app.core.designsystem.motion.BloubSilhouettes
import com.raven.app.core.designsystem.motion.PROFILE_SAMPLES
import com.raven.app.core.designsystem.motion.blendSilhouettes
import com.raven.app.core.designsystem.motion.computeOrbitArcs
import com.raven.app.core.designsystem.motion.computeSpiralParticles
import com.raven.app.core.designsystem.motion.loopNoise
import com.raven.app.core.designsystem.motion.projectSilhouettePoints
import com.raven.app.core.designsystem.motion.writeCatmullRomClosedPath
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val MAX_HALO_ALPHA: Float = 0.22f
private const val STATIC_TIME_SEC: Float = 0.35f

/**
 * Raven's living Dark Obsidian × Raven Crimson morphing presence orb modeled after `bloub`
 * with expressive bean-eyes (`豆豆眼`), depth-sorted 3D orbital rings, and spiral vortex particles.
 *
 * Strictly honors [LocalMotionReduced]: when reduced motion is active, runs zero infinite frame
 * loops and snaps directly to crisp static silhouettes.
 */
@Composable
fun RavenBloubPresence(
    mood: BloubMood,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    accentColor: Color = LocalRavenColors.current.crimson,
    interactive: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val motionReduced = LocalMotionReduced.current
    val resolvedAccent = if (accentColor == Color.Unspecified) colors.crimson else accentColor
    val scope = rememberCoroutineScope()

    val frameTimeSec = remember { mutableFloatStateOf(STATIC_TIME_SEC) }
    var currentMood by remember { mutableStateOf(mood) }
    val fromSilhouette = remember { BloubSilhouette().copyFrom(BloubSilhouettes.forMood(mood)) }
    val blendedSilhouette = remember { BloubSilhouette().copyFrom(BloubSilhouettes.forMood(mood)) }
    val morphProgress = remember { Animatable(1f) }
    val tapSquash = remember { Animatable(0f) }
    val winkProgress = remember { Animatable(0f) }

    val pointsBuffer = remember { FloatArray(PROFILE_SAMPLES * 2) }
    val bodyPath = remember { Path() }
    val crestPath = remember { Path() }
    val mouthPath = remember { Path() }
    val arcPath = remember { Path() }
    var tapCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(mood, motionReduced) {
        if (mood != currentMood) {
            fromSilhouette.copyFrom(blendedSilhouette)
            currentMood = mood
            if (motionReduced) {
                blendedSilhouette.copyFrom(BloubSilhouettes.forMood(mood))
                morphProgress.snapTo(1f)
            } else {
                morphProgress.snapTo(0f)
                morphProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = RavenMotion.gentleSpatialSpringSpec(),
                )
            }
        } else if (motionReduced) {
            blendedSilhouette.copyFrom(BloubSilhouettes.forMood(mood))
            morphProgress.snapTo(1f)
        }
    }

    LaunchedEffect(motionReduced) {
        if (motionReduced) {
            frameTimeSec.floatValue = STATIC_TIME_SEC
            return@LaunchedEffect
        }
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    frameTimeSec.floatValue += dt
                }
                lastNanos = now
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    val interactiveModifier = if (interactive) {
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    tapCount += 1
                    feedback.perform(RavenFeedbackEffect.TAP)
                    if (!motionReduced) {
                        scope.launch {
                            tapSquash.snapTo(1f)
                            tapSquash.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(dampingRatio = 0.45f, stiffness = 420f),
                            )
                        }
                        scope.launch {
                            winkProgress.snapTo(1f)
                            winkProgress.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                },
            )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(semanticsModifier)
            .then(interactiveModifier)
            .ravenBurstFeedback(
                trigger = tapCount,
                burstColor = resolvedAccent,
                enabled = interactive,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val t = if (motionReduced) STATIC_TIME_SEC else frameTimeSec.floatValue
            val centerX = this.size.width * 0.5f
            val centerY = this.size.height * 0.5f
            val baseRadius = this.size.minDimension * 0.34f

            val targetSilhouette = BloubSilhouettes.forMood(currentMood)
            blendSilhouettes(
                a = fromSilhouette,
                b = targetSilhouette,
                t = morphProgress.value,
                out = blendedSilhouette,
            )

            // Apply living idle breathing and interactive squash-and-stretch
            val breath = if (motionReduced) 0f else loopNoise(t, period = 3.6f, seed = 0.7f)
            val bounce = tapSquash.value
            val moodPulse = when (currentMood) {
                BloubMood.SUCCESS -> if (motionReduced) 0f else sin(t * 5.2f) * 0.03f
                BloubMood.ERROR -> if (motionReduced) 0f else loopNoise(t, period = 1.6f, seed = 4.2f) * 0.025f
                else -> 0f
            }
            val effectiveSx = blendedSilhouette.sx * (1f + breath * 0.025f + bounce * 0.14f)
            val effectiveSy = blendedSilhouette.sy * (1f - breath * 0.025f - bounce * 0.11f + moodPulse)
            val savedSx = blendedSilhouette.sx
            val savedSy = blendedSilhouette.sy
            val savedCy = blendedSilhouette.cy
            val effectiveCy = savedCy - bounce * 0.08f + moodPulse * 0.5f
            blendedSilhouette.sx = effectiveSx
            blendedSilhouette.sy = effectiveSy
            blendedSilhouette.cy = effectiveCy

            val haloAlpha = when (currentMood) {
                BloubMood.ERROR -> 0.18f
                BloubMood.SUCCESS -> 0.20f
                BloubMood.ORBIT, BloubMood.SEARCHING -> 0.18f
                else -> 0.14f
            }.coerceAtMost(MAX_HALO_ALPHA)
            val haloColor = when (currentMood) {
                BloubMood.ERROR -> colors.error
                BloubMood.SUCCESS -> resolvedAccent
                else -> resolvedAccent
            }

            // 1. Subtle obsidian accent halo (strictly capped <= 0.22f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        haloColor.copy(alpha = haloAlpha),
                        haloColor.copy(alpha = haloAlpha * 0.32f),
                        Color.Transparent,
                    ),
                    center = Offset(centerX, centerY),
                    radius = baseRadius * 1.85f,
                ),
                center = Offset(centerX, centerY),
                radius = baseRadius * 1.85f,
            )

            // 2. Depth-sorted back orbital rings
            val arcs = if (currentMood == BloubMood.ORBIT || currentMood == BloubMood.SEARCHING) {
                computeOrbitArcs(t = t, scale = baseRadius)
            } else {
                emptyList()
            }
            for (arc in arcs) {
                if (!arc.isFront) {
                    drawOrbitArc(
                        arc = arc,
                        centerX = centerX,
                        centerY = centerY,
                        primaryColor = resolvedAccent,
                        secondaryColor = colors.lightRimTop,
                        baseStrokePx = 1.6.dp.toPx(),
                        arcPath = arcPath,
                    )
                }
            }

            // 3. Catmull-Rom morphed Dark Obsidian glass body
            val projectedCount = projectSilhouettePoints(
                silhouette = blendedSilhouette,
                centerX = centerX,
                centerY = centerY,
                baseRadius = baseRadius,
                outPoints = pointsBuffer,
            )
            blendedSilhouette.sx = savedSx
            blendedSilhouette.sy = savedSy
            blendedSilhouette.cy = savedCy

            if (projectedCount >= 3) {
                writeCatmullRomClosedPath(
                    pointsXY = pointsBuffer,
                    pointCount = projectedCount,
                    outPath = bodyPath,
                )
                drawPath(
                    path = bodyPath,
                    brush = Brush.radialGradient(
                        colors = listOf(colors.surface3, colors.surface2, colors.surface1),
                        center = Offset(centerX - baseRadius * 0.24f, centerY - baseRadius * 0.28f),
                        radius = baseRadius * 1.55f,
                    ),
                )
                if (currentMood == BloubMood.ERROR) {
                    drawPath(path = bodyPath, color = colors.error.copy(alpha = 0.12f))
                } else if (currentMood == BloubMood.SUCCESS) {
                    drawPath(path = bodyPath, color = resolvedAccent.copy(alpha = 0.10f))
                }
                drawPath(
                    path = bodyPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.lightRimTop, colors.lightRimBottom),
                        startY = centerY - baseRadius,
                        endY = centerY + baseRadius,
                    ),
                    style = Stroke(width = 1.1.dp.toPx()),
                )
            }

            val faceCenterX = centerX + blendedSilhouette.cx * baseRadius
            val faceCenterY = centerY + effectiveCy * baseRadius

            // 3b. Sleek Raven crimson feather-crest flick at top-left crown
            drawFeatherCrest(
                centerX = faceCenterX,
                centerY = faceCenterY,
                baseRadius = baseRadius,
                sx = effectiveSx,
                sy = effectiveSy,
                bodyRotRad = blendedSilhouette.rot,
                swayDeg = if (motionReduced) 0f else breath * 3.5f - bounce * 5.5f,
                crestColor = resolvedAccent,
                rimHighlightColor = colors.lightRimTop,
                crestPath = crestPath,
            )

            // 4. Expressive Raven bean-eyes (豆豆眼) & mood/wink mouth
            drawBeanEyes(
                mood = currentMood,
                t = t,
                motionReduced = motionReduced,
                winkAmount = winkProgress.value,
                centerX = faceCenterX,
                centerY = faceCenterY,
                baseRadius = baseRadius,
                bodyRotRad = blendedSilhouette.rot,
                inkColor = colors.inkPrimary,
                accentColor = if (currentMood == BloubMood.ERROR) colors.error else resolvedAccent,
                errorColor = colors.error,
                mouthPath = mouthPath,
            )

            // 5. Depth-sorted front orbital rings
            for (arc in arcs) {
                if (arc.isFront) {
                    drawOrbitArc(
                        arc = arc,
                        centerX = centerX,
                        centerY = centerY,
                        primaryColor = resolvedAccent,
                        secondaryColor = colors.inkPrimary,
                        baseStrokePx = 1.6.dp.toPx(),
                        arcPath = arcPath,
                    )
                }
            }

            // 6. Spiral vortex particles for SEARCHING mood
            if (currentMood == BloubMood.SEARCHING) {
                val particles = computeSpiralParticles(t = t, scale = baseRadius * 1.08f, count = 9)
                for (particle in particles) {
                    val particleColor = if (particle.index % 2 == 0) resolvedAccent else colors.inkPrimary
                    drawCircle(
                        color = particleColor.copy(alpha = particle.alpha),
                        radius = particle.radius.coerceAtLeast(1.dp.toPx()),
                        center = Offset(centerX + particle.x, centerY + particle.y),
                    )
                }
            }
        }
    }
}

/**
 * Compact `ORBIT` loading indicator with Dark Obsidian core and 3D depth-sorted Raven Crimson
 * orbital rings for player buffering and async loading states.
 */
@Composable
fun RavenBloubLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    accentColor: Color = LocalRavenColors.current.crimson,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val resolvedAccent = if (accentColor == Color.Unspecified) colors.crimson else accentColor
    val frameTimeSec = remember { mutableFloatStateOf(STATIC_TIME_SEC) }
    val pointsBuffer = remember { FloatArray(PROFILE_SAMPLES * 2) }
    val bodyPath = remember { Path() }
    val crestPath = remember { Path() }
    val mouthPath = remember { Path() }
    val arcPath = remember { Path() }
    var tapCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(motionReduced) {
        if (motionReduced) {
            frameTimeSec.floatValue = STATIC_TIME_SEC
            return@LaunchedEffect
        }
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    frameTimeSec.floatValue += dt
                }
                lastNanos = now
            }
        }
    }

    Canvas(modifier = modifier.size(size)) {
        val t = if (motionReduced) STATIC_TIME_SEC else frameTimeSec.floatValue
        val centerX = this.size.width * 0.5f
        val centerY = this.size.height * 0.5f
        val baseRadius = this.size.minDimension * 0.34f
        val arcs = computeOrbitArcs(t = t, scale = baseRadius)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    resolvedAccent.copy(alpha = 0.18f),
                    Color.Transparent,
                ),
                center = Offset(centerX, centerY),
                radius = baseRadius * 1.7f,
            ),
            center = Offset(centerX, centerY),
            radius = baseRadius * 1.7f,
        )

        for (arc in arcs) {
            if (!arc.isFront) {
                drawOrbitArc(
                    arc = arc,
                    centerX = centerX,
                    centerY = centerY,
                    primaryColor = resolvedAccent,
                    secondaryColor = colors.lightRimTop,
                    baseStrokePx = 1.4.dp.toPx(),
                    arcPath = arcPath,
                )
            }
        }

        val count = projectSilhouettePoints(
            silhouette = BloubSilhouettes.CompactCore,
            centerX = centerX,
            centerY = centerY,
            baseRadius = baseRadius,
            outPoints = pointsBuffer,
        )
        if (count >= 3) {
            writeCatmullRomClosedPath(pointsBuffer, count, bodyPath)
            drawPath(
                path = bodyPath,
                brush = Brush.radialGradient(
                    colors = listOf(colors.surface3, colors.surface1),
                    center = Offset(centerX - baseRadius * 0.2f, centerY - baseRadius * 0.2f),
                    radius = baseRadius * 1.2f,
                ),
            )
            drawPath(
                path = bodyPath,
                brush = Brush.verticalGradient(
                    colors = listOf(colors.lightRimTop, colors.hairline),
                    startY = centerY - baseRadius * 0.7f,
                    endY = centerY + baseRadius * 0.7f,
                ),
                style = Stroke(width = 0.9.dp.toPx()),
            )
        }

        for (arc in arcs) {
            if (arc.isFront) {
                drawOrbitArc(
                    arc = arc,
                    centerX = centerX,
                    centerY = centerY,
                    primaryColor = resolvedAccent,
                    secondaryColor = colors.inkPrimary,
                    baseStrokePx = 1.4.dp.toPx(),
                    arcPath = arcPath,
                )
            }
        }
    }
}

/**
 * Subtle breathing crimson pulse dot for active status strips and brand accents.
 * Renders a crisp static dot with zero frame loops when [LocalMotionReduced] is true.
 */
@Composable
fun RavenLivingBrandDot(
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
    color: Color = LocalRavenColors.current.crimson,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val resolvedColor = if (color == Color.Unspecified) colors.crimson else color

    val pulsePhase = if (motionReduced) {
        null
    } else {
        rememberInfiniteTransition(label = "ravenLivingBrandDot").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ravenLivingBrandDotPulse",
        )
    }

    Canvas(modifier = modifier.size(size)) {
        val phase = pulsePhase?.value ?: 0f
        val coreRadius = (this.size.minDimension * 0.5f) * (1f + phase * 0.08f)
        val haloRadius = coreRadius * (1.7f + phase * 0.5f)
        val haloAlpha = if (motionReduced) 0.14f else (0.10f + phase * 0.12f).coerceAtMost(MAX_HALO_ALPHA)

        drawCircle(
            color = resolvedColor.copy(alpha = haloAlpha),
            radius = haloRadius,
            center = center,
        )
        drawCircle(
            color = resolvedColor,
            radius = coreRadius,
            center = center,
        )
    }
}

private fun DrawScope.drawOrbitArc(
    arc: BloubOrbitArc,
    centerX: Float,
    centerY: Float,
    primaryColor: Color,
    secondaryColor: Color,
    baseStrokePx: Float,
    arcPath: Path,
) {
    if (arc.pointCount < 2) return
    arcPath.reset()
    arcPath.moveTo(centerX + arc.xAt(0), centerY + arc.yAt(0))
    for (i in 1 until arc.pointCount) {
        arcPath.lineTo(centerX + arc.xAt(i), centerY + arc.yAt(i))
    }
    val tint = if (arc.ringIndex == 0) primaryColor else secondaryColor
    drawPath(
        path = arcPath,
        color = tint.copy(alpha = arc.alpha),
        style = Stroke(
            width = (baseStrokePx * arc.strokeScale).coerceAtLeast(0.75f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun DrawScope.drawFeatherCrest(
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    sx: Float,
    sy: Float,
    bodyRotRad: Float,
    swayDeg: Float,
    crestColor: Color,
    rimHighlightColor: Color,
    crestPath: Path,
) {
    val rotDegrees = (bodyRotRad * 180f / PI.toFloat()) + swayDeg
    val anchorX = centerX - baseRadius * 0.42f * sx
    val anchorY = centerY - baseRadius * 0.78f * sy

    crestPath.reset()
    crestPath.moveTo(anchorX + baseRadius * 0.14f, anchorY + baseRadius * 0.04f)
    crestPath.cubicTo(
        anchorX + baseRadius * 0.08f,
        anchorY - baseRadius * 0.22f,
        anchorX - baseRadius * 0.14f,
        anchorY - baseRadius * 0.40f,
        anchorX - baseRadius * 0.36f,
        anchorY - baseRadius * 0.38f,
    )
    crestPath.cubicTo(
        anchorX - baseRadius * 0.20f,
        anchorY - baseRadius * 0.22f,
        anchorX - baseRadius * 0.12f,
        anchorY - baseRadius * 0.12f,
        anchorX - baseRadius * 0.08f,
        anchorY - baseRadius * 0.06f,
    )
    crestPath.cubicTo(
        anchorX - baseRadius * 0.24f,
        anchorY - baseRadius * 0.14f,
        anchorX - baseRadius * 0.38f,
        anchorY - baseRadius * 0.14f,
        anchorX - baseRadius * 0.46f,
        anchorY - baseRadius * 0.06f,
    )
    crestPath.cubicTo(
        anchorX - baseRadius * 0.30f,
        anchorY + baseRadius * 0.02f,
        anchorX - baseRadius * 0.14f,
        anchorY + baseRadius * 0.08f,
        anchorX + baseRadius * 0.14f,
        anchorY + baseRadius * 0.04f,
    )
    crestPath.close()

    rotate(degrees = rotDegrees, pivot = Offset(centerX, centerY)) {
        drawPath(path = crestPath, color = crestColor)
        drawPath(
            path = crestPath,
            color = rimHighlightColor.copy(alpha = 0.28f),
            style = Stroke(width = 0.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun DrawScope.drawBeanEyes(
    mood: BloubMood,
    t: Float,
    motionReduced: Boolean,
    winkAmount: Float,
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    bodyRotRad: Float,
    inkColor: Color,
    accentColor: Color,
    errorColor: Color,
    mouthPath: Path,
) {
    val gazeX = if (motionReduced) {
        if (mood == BloubMood.EMPTY) baseRadius * 0.06f else 0f
    } else {
        val period = if (mood == BloubMood.SEARCHING) 2.2f else 4.4f
        loopNoise(t, period = period, seed = 1.7f) * baseRadius * 0.12f
    }
    val gazeY = if (motionReduced) {
        if (mood == BloubMood.EMPTY) -baseRadius * 0.04f else 0f
    } else {
        val period = if (mood == BloubMood.SEARCHING) 2.7f else 5.3f
        loopNoise(t, period = period, seed = 3.9f) * baseRadius * 0.07f
    }

    // Periodic natural blink every ~3.8s
    val blinkCycle = if (motionReduced) 1f else (t % 3.8f)
    val autoBlink = if (!motionReduced && blinkCycle < 0.16f) {
        sin((blinkCycle / 0.16f) * PI.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val eyeSpacing = baseRadius * 0.34f
    val eyeWidth = baseRadius * 0.145f
    val baseEyeHeight = when (mood) {
        BloubMood.ORBIT -> baseRadius * 0.18f
        BloubMood.ERROR -> baseRadius * 0.21f
        BloubMood.SUCCESS -> baseRadius * 0.19f
        else -> baseRadius * 0.23f
    }

    val rotDegrees = (bodyRotRad * 180f / PI.toFloat())
    val eyeColor = if (mood == BloubMood.ERROR) accentColor else inkColor

    rotate(degrees = rotDegrees, pivot = Offset(centerX, centerY)) {
        for (side in -1..1 step 2) {
            val isRightEye = side > 0
            val blinkScale = (1f - autoBlink * 0.88f) *
                if (isRightEye) (1f - winkAmount * 0.85f) else 1f
            val h = (baseEyeHeight * blinkScale).coerceAtLeast(baseRadius * 0.04f)
            val ex = centerX + side * eyeSpacing * 0.5f + gazeX
            val ey = centerY - baseRadius * 0.04f + gazeY
            val tiltDeg = when (mood) {
                BloubMood.ERROR -> side * -16f
                BloubMood.SUCCESS -> side * 10f
                BloubMood.EMPTY -> -6f
                else -> 0f
            }

            rotate(degrees = tiltDeg, pivot = Offset(ex, ey)) {
                drawRoundRect(
                    color = eyeColor,
                    topLeft = Offset(ex - eyeWidth * 0.5f, ey - h * 0.5f),
                    size = Size(eyeWidth, h),
                    cornerRadius = CornerRadius(eyeWidth * 0.5f, eyeWidth * 0.5f),
                )
            }
        }

        val mouthStrokePx = (baseRadius * 0.075f).coerceAtLeast(1.2.dp.toPx())
        val mouthX = centerX + gazeX * 0.7f
        val mouthY = centerY + baseRadius * 0.24f + gazeY * 0.6f
        val mouthHalfW = baseRadius * 0.16f

        if (winkAmount > 0.1f || mood == BloubMood.SUCCESS) {
            val smileAlpha = if (mood == BloubMood.SUCCESS) {
                1f
            } else {
                ((winkAmount - 0.1f) / 0.25f).coerceIn(0f, 1f)
            }
            mouthPath.reset()
            mouthPath.moveTo(mouthX - mouthHalfW, mouthY - baseRadius * 0.02f)
            mouthPath.quadraticTo(
                mouthX,
                mouthY + baseRadius * 0.10f,
                mouthX + mouthHalfW,
                mouthY - baseRadius * 0.02f,
            )
            drawPath(
                path = mouthPath,
                color = inkColor.copy(alpha = smileAlpha),
                style = Stroke(width = mouthStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else if (mood == BloubMood.ERROR) {
            val waveW = mouthHalfW * 1.1f
            val waveAmp = baseRadius * 0.045f
            mouthPath.reset()
            mouthPath.moveTo(mouthX - waveW, mouthY + waveAmp * 0.3f)
            mouthPath.cubicTo(
                mouthX - waveW * 0.45f,
                mouthY - waveAmp,
                mouthX - waveW * 0.15f,
                mouthY + waveAmp,
                mouthX,
                mouthY,
            )
            mouthPath.cubicTo(
                mouthX + waveW * 0.15f,
                mouthY - waveAmp,
                mouthX + waveW * 0.45f,
                mouthY + waveAmp,
                mouthX + waveW,
                mouthY - waveAmp * 0.3f,
            )
            drawPath(
                path = mouthPath,
                color = errorColor,
                style = Stroke(width = mouthStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
