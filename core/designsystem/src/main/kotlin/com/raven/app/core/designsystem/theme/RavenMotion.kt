package com.raven.app.core.designsystem.theme

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

/**
 * HarmonyOS Design 5-tier optical frosted material levels (`Ultra_Thin` -> `Ultra_Thick`).
 */
enum class RavenMaterialTier {
    ULTRA_THIN,
    THIN,
    REGULAR,
    THICK,
    ULTRA_THICK,
}

/**
 * Optical gradient stops, vertical specular edge rim alphas, and blur radius for a [RavenMaterialTier].
 */
@Immutable
data class RavenMaterialSpec(
    val topAlpha: Float,
    val bottomAlpha: Float,
    val rimTopAlpha: Float,
    val rimBottomAlpha: Float,
    val blurRadius: Dp,
)

object RavenMotion {
    /** Crisp tactile press scale (`0.968f`) with natural gravity spring recovery. */
    const val SPRING_PRESSED_SCALE: Float = 0.968f
    const val AMBIENT_GLOW_MAX_ALPHA: Float = RavenColors.AMBIENT_GLOW_MAX_ALPHA
    val DefaultFrostBlurRadius: Dp = 18.dp

    /** HarmonyOS Design official Friction curve `(0.2, 0.0, 0.1, 1.0)` for route, modal, sheet, and tab transitions. */
    val HarmonyDecelerationEasing: CubicBezierEasing = CubicBezierEasing(0.2f, 0.0f, 0.1f, 1.0f)

    /** HarmonyOS Design standard optical easing curve `(0.2, 0.0, 0.0, 1.0)` for micro-emphasis fades and highlights. */
    val HarmonyStandardEasing: CubicBezierEasing = CubicBezierEasing(0.20f, 0f, 0f, 1f)

    /** Legacy deceleration alias preserved temporarily for source compatibility; delegates to [HarmonyDecelerationEasing]. */
    @Deprecated(
        message = "Use RavenMotion.HarmonyDecelerationEasing instead",
        replaceWith = ReplaceWith("RavenMotion.HarmonyDecelerationEasing"),
    )
    val AppleDecelerationEasing: CubicBezierEasing = HarmonyDecelerationEasing

    /** Finger-tracking / instant press-down gravity spring (`damping = 0.92`, `stiffness = 620`). */
    fun <T> responsiveSpringSpec(): SpringSpec<T> = spring(
        dampingRatio = 0.92f,
        stiffness = 620f,
    )

    /** Control release & state-switch gravity spring (`damping = 0.82`, `stiffness = 380`). */
    fun <T> interactiveSpringSpec(): SpringSpec<T> = spring(
        dampingRatio = 0.82f,
        stiffness = 380f,
    )

    /** Gentle spatial layout & container gravity spring (`damping = 0.88`, `stiffness = 280`). */
    fun <T> gentleSpatialSpringSpec(): SpringSpec<T> = spring(
        dampingRatio = 0.88f,
        stiffness = 280f,
    )

    /** Maps each [RavenMaterialTier] to deterministic translucency and optical rim light parameters. */
    fun materialSpec(tier: RavenMaterialTier): RavenMaterialSpec = when (tier) {
        RavenMaterialTier.ULTRA_THIN -> RavenMaterialSpec(
            topAlpha = 0.48f,
            bottomAlpha = 0.60f,
            rimTopAlpha = 0.22f,
            rimBottomAlpha = 0.06f,
            blurRadius = 14.dp,
        )
        RavenMaterialTier.THIN -> RavenMaterialSpec(
            topAlpha = 0.60f,
            bottomAlpha = 0.72f,
            rimTopAlpha = 0.20f,
            rimBottomAlpha = 0.05f,
            blurRadius = 16.dp,
        )
        RavenMaterialTier.REGULAR -> RavenMaterialSpec(
            topAlpha = 0.72f,
            bottomAlpha = 0.84f,
            rimTopAlpha = 0.18f,
            rimBottomAlpha = 0.04f,
            blurRadius = 18.dp,
        )
        RavenMaterialTier.THICK -> RavenMaterialSpec(
            topAlpha = 0.80f,
            bottomAlpha = 0.90f,
            rimTopAlpha = 0.15f,
            rimBottomAlpha = 0.04f,
            blurRadius = 22.dp,
        )
        RavenMaterialTier.ULTRA_THICK -> RavenMaterialSpec(
            topAlpha = 0.86f,
            bottomAlpha = 0.94f,
            rimTopAlpha = 0.12f,
            rimBottomAlpha = 0.03f,
            blurRadius = 26.dp,
        )
    }

    /** Navigation choreography timing & parallax constants. */
    const val TAB_TRANSITION_DURATION_MS: Int = 220
    const val PUSH_TRANSITION_DURATION_MS: Int = 320
    const val POP_TRANSITION_DURATION_MS: Int = 280
    const val CHIP_TRANSITION_DURATION_MS: Int = 180
    const val IMAGE_CROSSFADE_MILLIS: Int = 220

    const val TAB_ENTER_INITIAL_SCALE: Float = 0.985f
    const val TAB_SLIDE_OFFSET_FRACTION: Float = 0.055f
    const val PUSH_EXIT_PARALLAX_FRACTION: Float = 0.18f
    const val PUSH_EXIT_ALPHA: Float = 0.72f
    const val PUSH_PARENT_EXIT_SCALE: Float = 0.965f
    const val POP_ENTER_PARALLAX_DIVISOR: Int = 5
    const val MODAL_ENTER_DURATION_MS: Int = 300
    const val MODAL_EXIT_DURATION_MS: Int = 240
    const val MODAL_INITIAL_SCALE: Float = 0.965f

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
     * Computes signed direction (`1` forward, `-1` backward, `0` neutral) between two top-level tabs.
     */
    fun computeTabSlideDirection(fromIndex: Int?, toIndex: Int?): Int {
        if (fromIndex == null || toIndex == null || fromIndex == toIndex) return 0
        return if (toIndex > fromIndex) 1 else -1
    }

    /**
     * Computes subtle pixel slide distance for directional top-level tab switching.
     */
    fun computeTabSlideOffset(fullWidth: Int, direction: Int): Int {
        if (fullWidth <= 0 || direction == 0) return 0
        return (fullWidth * TAB_SLIDE_OFFSET_FRACTION * direction).roundToInt()
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
 * Implemented via [ModifierNodeElement] (`RavenSpringPressNode`) with zero per-composition
 * allocations.
 */
fun Modifier.ravenSpringPress(
    interactionSource: InteractionSource? = null,
    pressedScale: Float = RavenMotion.SPRING_PRESSED_SCALE,
    enabled: Boolean = true,
): Modifier = this.then(
    RavenSpringPressElement(
        interactionSource = interactionSource,
        pressedScale = pressedScale.coerceIn(0.90f, 1.0f),
        enabled = enabled,
    ),
)

private data class RavenSpringPressElement(
    val interactionSource: InteractionSource?,
    val pressedScale: Float,
    val enabled: Boolean,
) : ModifierNodeElement<RavenSpringPressNode>() {
    override fun create(): RavenSpringPressNode =
        RavenSpringPressNode(interactionSource, pressedScale, enabled)

    override fun update(node: RavenSpringPressNode) {
        node.updateParams(interactionSource, pressedScale, enabled)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "ravenSpringPress"
        properties["pressedScale"] = pressedScale
        properties["enabled"] = enabled
    }
}

private class RavenSpringPressNode(
    private var interactionSource: InteractionSource?,
    private var pressedScale: Float,
    private var enabled: Boolean,
) : Modifier.Node(),
    LayoutModifierNode,
    PointerInputModifierNode,
    CompositionLocalConsumerModifierNode {

    private val scaleAnim = Animatable(1f)
    private var pointerPressed = false
    private var interactionJob: Job? = null
    private var animationJob: Job? = null
    private val activePresses = mutableListOf<PressInteraction.Press>()

    override fun onAttach() {
        startCollectingInteractions()
    }

    override fun onDetach() {
        interactionJob?.cancel()
        animationJob?.cancel()
        activePresses.clear()
        pointerPressed = false
    }

    fun updateParams(
        newSource: InteractionSource?,
        newScale: Float,
        newEnabled: Boolean,
    ) {
        val sourceChanged = interactionSource !== newSource
        interactionSource = newSource
        pressedScale = newScale
        enabled = newEnabled
        if (sourceChanged) {
            activePresses.clear()
            pointerPressed = false
            startCollectingInteractions()
        }
        if (!newEnabled) {
            pointerPressed = false
        }
        animateScale()
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
                        animateScale()
                    }
                    is PressInteraction.Release -> {
                        activePresses.remove(interaction.press)
                        animateScale()
                    }
                    is PressInteraction.Cancel -> {
                        activePresses.remove(interaction.press)
                        animateScale()
                    }
                }
            }
        }
    }

    private fun animateScale() {
        if (!isAttached) return
        val isPressed = enabled && if (interactionSource != null) {
            activePresses.isNotEmpty()
        } else {
            pointerPressed
        }
        val target = if (isPressed) pressedScale else 1f
        val reducedMotion = currentValueOf(LocalMotionReduced)
        animationJob?.cancel()
        animationJob = coroutineScope.launch {
            if (reducedMotion) {
                scaleAnim.snapTo(target)
            } else {
                scaleAnim.animateTo(
                    targetValue = target,
                    animationSpec = if (isPressed) {
                        RavenMotion.responsiveSpringSpec()
                    } else {
                        RavenMotion.interactiveSpringSpec()
                    },
                )
            }
        }
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (interactionSource != null || !enabled || pass != PointerEventPass.Main) return
        val nextPressed = pointerEvent.changes.any { it.pressed }
        if (nextPressed != pointerPressed) {
            pointerPressed = nextPressed
            animateScale()
        }
    }

    override fun onCancelPointerInput() {
        if (pointerPressed) {
            pointerPressed = false
            animateScale()
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val s = scaleAnim.value
                scaleX = s
                scaleY = s
            }
        }
    }
}

/**
 * HarmonyOS Design pointer-origin interactive light effect + top specular rim highlight.
 * Tracks touch coordinates from [interactionSource] via [PressInteraction.Press.pressPosition]
 * and radiates a soft radial sheen across the surface while pressed.
 */
fun Modifier.ravenInteractiveLight(
    interactionSource: InteractionSource,
    shape: Shape = RavenShapes.CardShape,
    enabled: Boolean = true,
    glowColor: Color = RavenColors.InteractiveGlow,
    highlightAlpha: Float = 0.08f,
): Modifier = composed {
    var pressOrigin by remember(interactionSource) { mutableStateOf(Offset.Unspecified) }
    var isPressed by remember(interactionSource) { mutableStateOf(false) }
    var isHovered by remember(interactionSource) { mutableStateOf(false) }
    var isFocused by remember(interactionSource) { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pressOrigin = interaction.pressPosition
                    isPressed = true
                }
                is PressInteraction.Release,
                is PressInteraction.Cancel,
                -> isPressed = false
                is HoverInteraction.Enter -> isHovered = true
                is HoverInteraction.Exit -> isHovered = false
                is FocusInteraction.Focus -> isFocused = true
                is FocusInteraction.Unfocus -> isFocused = false
            }
        }
    }
    val motionReduced = LocalMotionReduced.current
    val targetAlpha = when {
        !enabled -> 0f
        isPressed -> 1f
        isHovered || isFocused -> 0.45f
        else -> 0f
    }
    val overlayAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (motionReduced) {
            snap()
        } else if (isPressed) {
            RavenMotion.responsiveSpringSpec()
        } else {
            RavenMotion.interactiveSpringSpec()
        },
        label = "ravenInteractiveLightAlpha",
    )
    if (overlayAlpha <= 0.005f) {
        this
    } else {
        val clampedRimAlpha = highlightAlpha.coerceIn(0f, 0.25f) * overlayAlpha
        val clampedGlowAlpha = (glowColor.alpha * overlayAlpha).coerceIn(0f, 0.35f)
        this.drawWithContent {
            drawContent()
            val outline = shape.createOutline(size, layoutDirection, this)
            val clipOutlinePath = Path().apply { addOutline(outline) }
            val resolvedOrigin = if (pressOrigin == Offset.Unspecified) {
                Offset(size.width * 0.5f, size.height * 0.5f)
            } else {
                Offset(
                    x = pressOrigin.x.coerceIn(0f, size.width),
                    y = pressOrigin.y.coerceIn(0f, size.height),
                )
            }
            val radius = maxOf(size.width, size.height).coerceAtLeast(1f) * 0.9f
            clipPath(clipOutlinePath) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = clampedGlowAlpha),
                            glowColor.copy(alpha = clampedGlowAlpha * 0.30f),
                            Color.Transparent,
                        ),
                        center = resolvedOrigin,
                        radius = radius,
                    ),
                    radius = radius,
                    center = resolvedOrigin,
                )
            }
            if (clampedRimAlpha > 0f) {
                drawOutline(
                    outline = outline,
                    color = Color.White.copy(alpha = clampedRimAlpha),
                )
            }
        }
    }
}

/**
 * Subtle specular rim sheen & pointer-origin interactive light overlay when pressed,
 * respecting [LocalMotionReduced].
 */
fun Modifier.ravenPressSurfaceHighlight(
    interactionSource: InteractionSource,
    shape: Shape = RavenShapes.CardShape,
    enabled: Boolean = true,
    highlightAlpha: Float = 0.08f,
): Modifier = ravenInteractiveLight(
    interactionSource = interactionSource,
    shape = shape,
    enabled = enabled,
    glowColor = RavenColors.InteractiveGlow,
    highlightAlpha = highlightAlpha,
)

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
 * - On API 31+ when `reduceTransparency == false`: multi-stop translucent optical frost tuned by
 *   [tier] (`Ultra_Thin` -> `Ultra_Thick`) with top-lit specular refraction rim.
 * - On API 24–30 or when `reduceTransparency == true`: clean `#121216 @ 92%` scrim + [RavenColors.Hairline] border.
 *
 * For surfaces that also render a dedicated backdrop layer behind crisp foreground content, use [RavenFrostedBox].
 */
fun Modifier.ravenFrostedSurface(
    reduceTransparency: Boolean = false,
    shape: Shape = RavenShapes.CardShape,
    borderColor: Color = RavenColors.Hairline,
    borderWidth: Dp = 1.dp,
    tier: RavenMaterialTier = RavenMaterialTier.REGULAR,
): Modifier {
    val frosted = RavenMotion.supportsNativeBlur(reduceTransparency = reduceTransparency)
    val spec = RavenMotion.materialSpec(tier)
    val backgroundModifier = if (frosted) {
        Modifier.background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    RavenColors.Surface2.copy(alpha = spec.topAlpha),
                    RavenColors.Surface1.copy(alpha = spec.bottomAlpha),
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
    val rimBorderModifier = if (frosted) {
        Modifier.border(
            width = borderWidth,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = spec.rimTopAlpha),
                    Color.White.copy(alpha = spec.rimBottomAlpha),
                ),
            ),
            shape = shape,
        )
    } else {
        Modifier.border(
            width = borderWidth,
            color = borderColor,
            shape = shape,
        )
    }
    return this
        .clip(shape)
        .then(backgroundModifier)
        .then(rimBorderModifier)
}

/**
 * Optical frosted backdrop layer for dedicated background-only composables (placed behind crisp
 * unblurred foreground content inside [RavenFrostedBox]). Falls back to `#121216 @ 92%` on
 * API 24–30 or when [reduceTransparency] is enabled. Zero third-party backdrop dependencies.
 */
fun Modifier.ravenFrostedBackdropLayer(
    reduceTransparency: Boolean = false,
    shape: Shape = RavenShapes.CardShape,
    @Suppress("UNUSED_PARAMETER") blurRadius: Dp = RavenMotion.DefaultFrostBlurRadius,
    tier: RavenMaterialTier = RavenMaterialTier.REGULAR,
): Modifier {
    val spec = RavenMotion.materialSpec(tier)
    return if (RavenMotion.supportsNativeBlur(reduceTransparency = reduceTransparency)) {
        this
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        RavenColors.Surface2.copy(alpha = spec.topAlpha),
                        RavenColors.Surface1.copy(alpha = spec.bottomAlpha),
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
 * Frosted obsidian container pairing an optical frosted backdrop layer tuned by [tier]
 * (with API 24–30 / `reduceTransparency` fallback) beneath razor-sharp foreground [content]
 * and a top-lit vertical specular rim.
 */
@Composable
fun RavenFrostedBox(
    modifier: Modifier = Modifier,
    shape: Shape = RavenShapes.CardShape,
    reduceTransparency: Boolean = LocalReduceTransparency.current,
    blurRadius: Dp = RavenMotion.DefaultFrostBlurRadius,
    borderColor: Color = RavenColors.Hairline,
    borderWidth: Dp = 1.dp,
    tier: RavenMaterialTier = RavenMaterialTier.REGULAR,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val frosted = RavenMotion.supportsNativeBlur(reduceTransparency = reduceTransparency)
    val spec = RavenMotion.materialSpec(tier)
    val borderModifier = when {
        borderWidth <= 0.dp || borderColor == Color.Transparent -> Modifier
        frosted -> Modifier.border(
            width = borderWidth,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = spec.rimTopAlpha),
                    Color.White.copy(alpha = spec.rimBottomAlpha),
                ),
            ),
            shape = shape,
        )
        else -> Modifier.border(
            width = borderWidth,
            color = borderColor,
            shape = shape,
        )
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(borderModifier),
        contentAlignment = contentAlignment,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .ravenFrostedBackdropLayer(
                    reduceTransparency = reduceTransparency,
                    shape = shape,
                    blurRadius = blurRadius,
                    tier = tier,
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
