package com.raven.app.core.designsystem.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Whether frosted blur layers should degrade to a clean opaque dark scrim (`#121216 @ 92%`). */
val LocalReduceTransparency = staticCompositionLocalOf { false }

/** Whether motion/shimmer/spring animations should snap for accessibility or reduced-motion settings. */
val LocalMotionReduced = staticCompositionLocalOf { false }

/**
 * Computed artwork parallax offset, subtle zoom scale, and foreground copy alpha for a single
 * page inside a Hero carousel pager.
 */
@Immutable
data class RavenHeroPageTransform(
    val parallaxFraction: Float,
    val artworkScale: Float,
    val contentAlpha: Float,
)

object RavenMotion {
    /** Crisp Apple HIG tactile press scale (zero wobble with [Spring.DampingRatioNoBouncy]). */
    const val SPRING_PRESSED_SCALE: Float = 0.968f
    const val AMBIENT_GLOW_MAX_ALPHA: Float = RavenColors.AMBIENT_GLOW_MAX_ALPHA
    val DefaultFrostBlurRadius: Dp = 18.dp

    /** Apple HIG smooth deceleration curve used across navigation transitions and micro-motion. */
    val AppleDecelerationEasing: CubicBezierEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** Navigation choreography timing & parallax constants. */
    const val TAB_TRANSITION_DURATION_MS: Int = 220
    const val PUSH_TRANSITION_DURATION_MS: Int = 320
    const val POP_TRANSITION_DURATION_MS: Int = 280
    const val CHIP_TRANSITION_DURATION_MS: Int = 180
    const val IMAGE_CROSSFADE_MILLIS: Int = 220

    const val TAB_ENTER_INITIAL_SCALE: Float = 0.985f
    const val PUSH_EXIT_PARALLAX_FRACTION: Float = 0.18f
    const val PUSH_EXIT_ALPHA: Float = 0.72f
    const val POP_ENTER_PARALLAX_DIVISOR: Int = 5

    /** Hero pager parallax tuning constants. */
    const val HERO_PARALLAX_FACTOR: Float = 0.22f
    const val HERO_MAX_EXTRA_SCALE: Float = 0.045f

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

    /**
     * Computes horizontal parent offset in pixels during an iOS-style push transition (`-18%`).
     */
    fun computeNavPushParentExitOffset(fullWidth: Int): Int {
        if (fullWidth <= 0) return 0
        return -(fullWidth * PUSH_EXIT_PARALLAX_FRACTION).roundToInt()
    }

    /**
     * Computes horizontal parent entry offset in pixels during an iOS-style pop transition (`-20%`).
     */
    fun computeNavPopParentEnterOffset(fullWidth: Int): Int {
        if (fullWidth <= 0) return 0
        return -(fullWidth / POP_ENTER_PARALLAX_DIVISOR)
    }

    /**
     * Computes Apple TV+ / Netflix Hero carousel artwork parallax fraction, subtle zoom scale, and
     * foreground alpha from a signed page offset (`pagerState.getOffsetDistanceInPages(page)`).
     *
     * Because `|parallaxFraction| <= HERO_PARALLAX_FACTOR * |offset| < |offset|`, opposing artwork
     * edges never enter the clipped viewport during swipes.
     */
    fun computeHeroPageTransform(pageOffset: Float): RavenHeroPageTransform {
        if (!pageOffset.isFinite()) {
            return RavenHeroPageTransform(
                parallaxFraction = 0f,
                artworkScale = 1f,
                contentAlpha = 1f,
            )
        }
        val clamped = pageOffset.coerceIn(-1f, 1f)
        val absOffset = abs(clamped)
        val rawParallax = -clamped * HERO_PARALLAX_FACTOR
        return RavenHeroPageTransform(
            parallaxFraction = if (rawParallax == 0f) 0f else rawParallax,
            artworkScale = 1f + (absOffset * HERO_MAX_EXTRA_SCALE),
            contentAlpha = (1f - absOffset * 0.65f).coerceIn(0.35f, 1f),
        )
    }
}

/**
 * Tactile spring press scale (`1.0f -> 0.968f`) matching Apple HIG physical responsiveness
 * with crisp critically damped recovery (`DampingRatioNoBouncy`, `StiffnessMedium`, zero wobble).
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
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
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
 * Reusable Apple-style tactile haptic modifier that triggers only on confirmed press release
 * (never during a cancelled scroll drag) without consuming downstream pointer events.
 */
fun Modifier.ravenPressHaptic(
    interactionSource: InteractionSource? = null,
    hapticType: HapticFeedbackType = HapticFeedbackType.ContextClick,
    enabled: Boolean = true,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    if (interactionSource != null) {
        LaunchedEffect(interactionSource, enabled, hapticType) {
            if (!enabled) return@LaunchedEffect
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Release) {
                    haptic.performHapticFeedback(hapticType)
                }
            }
        }
        this
    } else {
        this.pointerInput(enabled, hapticType) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation()
                if (up != null) {
                    haptic.performHapticFeedback(hapticType)
                }
            }
        }
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
            .then(
                if (borderWidth > 0.dp && borderColor != Color.Transparent) {
                    Modifier.border(
                        width = borderWidth,
                        color = if (frosted) RavenColors.RimHighlight else borderColor,
                        shape = shape,
                    )
                } else {
                    Modifier
                },
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
