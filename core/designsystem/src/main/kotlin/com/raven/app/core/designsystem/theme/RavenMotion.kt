package com.raven.app.core.designsystem.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Whether frosted blur layers should degrade to a clean opaque dark scrim (`#121216 @ 92%`). */
val LocalReduceTransparency = staticCompositionLocalOf { false }

/** Whether motion/shimmer/spring animations should snap for accessibility or reduced-motion settings. */
val LocalMotionReduced = staticCompositionLocalOf { false }

object RavenMotion {
    const val SPRING_PRESSED_SCALE: Float = 0.97f
    const val AMBIENT_GLOW_MAX_ALPHA: Float = RavenColors.AMBIENT_GLOW_MAX_ALPHA
    val DefaultFrostBlurRadius: Dp = 18.dp

    /** Clamps any requested ambient hero glow alpha strictly into `0f..0.18f`. */
    fun clampAmbientGlowAlpha(alpha: Float): Float {
        if (!alpha.isFinite()) return 0f
        return alpha.coerceIn(0f, AMBIENT_GLOW_MAX_ALPHA)
    }

    /** Returns true when native API 31+ blur can be applied cleanly. */
    fun supportsNativeBlur(
        sdkInt: Int = Build.VERSION.SDK_INT,
        reduceTransparency: Boolean = false,
    ): Boolean = !reduceTransparency && sdkInt >= Build.VERSION_CODES.S
}

/**
 * Tactile spring press scale (`1.0f -> 0.97f`) matching Apple HIG physical responsiveness.
 *
 * When [interactionSource] is supplied, press state tracks Compose's interaction stream directly
 * (including keyboard/talkback presses). When omitted, a non-consuming pointer observer detects
 * down/up gestures cleanly without interfering with downstream `clickable` handlers.
 */
fun Modifier.ravenSpringPress(
    interactionSource: InteractionSource? = null,
    pressedScale: Float = RavenMotion.SPRING_PRESSED_SCALE,
    enabled: Boolean = true,
): Modifier = composed {
    val reducedMotion = LocalMotionReduced.current
    val targetScale = pressedScale.coerceIn(0.90f, 1.0f)
    var pointerPressed by remember { mutableStateOf(false) }

    val interactionPressed = if (interactionSource != null) {
        val state by interactionSource.collectIsPressedAsState()
        state
    } else {
        false
    }

    val isPressed = enabled && (if (interactionSource != null) interactionPressed else pointerPressed)
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "ravenSpringPress",
    )

    val observerModifier = if (interactionSource == null) {
        Modifier.pointerInput(enabled) {
            if (!enabled) {
                pointerPressed = false
                return@pointerInput
            }
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pointerPressed = true
                waitForUpOrCancellation()
                pointerPressed = false
            }
        }
    } else {
        Modifier
    }

    this.then(observerModifier).graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Applies a refined dark frosted surface styling directly onto a container holding foreground content
 * without blurring child text/icons.
 *
 * - On API 31+ when `reduceTransparency == false`: multi-stop translucent obsidian frost with
 *   specular rim sheen and hairline edge.
 * - On API 24–30 or when `reduceTransparency == true`: clean `#121216 @ 92%` scrim + [RavenColors.Hairline] border.
 *
 * For surfaces that also render a native API 31+ blurred backdrop layer behind crisp foreground content,
 * use [RavenFrostedBox].
 */
fun Modifier.ravenFrostedSurface(
    reduceTransparency: Boolean = false,
    shape: Shape = RavenShapes.CardShape,
    borderColor: Color = RavenColors.Hairline,
    borderWidth: Dp = 1.dp,
): Modifier {
    val frosted = RavenMotion.supportsNativeBlur(reduceTransparency = reduceTransparency)
    val backgroundModifier = if (frosted) {
        Modifier.background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    RavenColors.Surface2.copy(alpha = 0.78f),
                    RavenColors.Surface1.copy(alpha = 0.84f),
                ),
            ),
            shape = shape,
        )
    } else {
        Modifier.background(
            color = RavenColors.FrostedFallbackScrim,
            shape = shape,
        )
    }
    return this
        .clip(shape)
        .then(backgroundModifier)
        .border(
            width = borderWidth,
            color = if (frosted) RavenColors.RimHighlight else borderColor,
            shape = shape,
        )
}

/**
 * Native API 31+ blurred backdrop layer for dedicated background-only composables (placed behind
 * crisp unblurred foreground content inside [RavenFrostedBox]). Falls back to `#121216 @ 92%` on
 * API 24–30 or when [reduceTransparency] is enabled. Zero third-party backdrop dependencies.
 */
fun Modifier.ravenFrostedBackdropLayer(
    reduceTransparency: Boolean = false,
    shape: Shape = RavenShapes.CardShape,
    blurRadius: Dp = RavenMotion.DefaultFrostBlurRadius,
): Modifier {
    return if (RavenMotion.supportsNativeBlur(reduceTransparency = reduceTransparency)) {
        this
            .clip(shape)
            .blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment(shape))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        RavenColors.Surface2.copy(alpha = 0.72f),
                        RavenColors.Surface1.copy(alpha = 0.82f),
                    ),
                ),
                shape = shape,
            )
    } else {
        this
            .clip(shape)
            .background(color = RavenColors.FrostedFallbackScrim, shape = shape)
    }
}

/**
 * Frosted obsidian container that pairs a native API 31+ blurred background layer (with API 24–30 /
 * `reduceTransparency` fallback) beneath razor-sharp foreground [content] and a subtle hairline rim.
 */
@Composable
fun RavenFrostedBox(
    modifier: Modifier = Modifier,
    shape: Shape = RavenShapes.CardShape,
    reduceTransparency: Boolean = LocalReduceTransparency.current,
    blurRadius: Dp = RavenMotion.DefaultFrostBlurRadius,
    borderColor: Color = RavenColors.Hairline,
    borderWidth: Dp = 1.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val frosted = RavenMotion.supportsNativeBlur(reduceTransparency = reduceTransparency)
    Box(
        modifier = modifier
            .clip(shape)
            .border(
                width = borderWidth,
                color = if (frosted) RavenColors.RimHighlight else borderColor,
                shape = shape,
            ),
        contentAlignment = contentAlignment,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .ravenFrostedBackdropLayer(
                    reduceTransparency = reduceTransparency,
                    shape = shape,
                    blurRadius = blurRadius,
                ),
        )
        content()
    }
}

/**
 * Low-saturation ambient top hero/detail glow strictly capped at `alpha <= 0.18f`.
 * Never mutates global [RavenDarkColorScheme] or floods full-screen backgrounds.
 */
fun Modifier.ambientHeroGlow(
    tint: Color = RavenColors.Crimson,
    alpha: Float = 0.14f,
): Modifier {
    val clampedAlpha = RavenMotion.clampAmbientGlowAlpha(alpha)
    if (clampedAlpha <= 0f) return this
    return this.drawBehind {
        val radius = maxOf(size.width, size.height).coerceAtLeast(1f) * 0.85f
        val center = Offset(x = size.width * 0.68f, y = size.height * 0.18f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    tint.copy(alpha = clampedAlpha),
                    tint.copy(alpha = clampedAlpha * 0.35f),
                    Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
        )
    }
}
