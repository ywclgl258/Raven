package com.raven.app.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for the Raven Obsidian × Apple HIG × Netflix Cinema color system.
 * Feature modules must consume these semantic tokens (or [LocalRavenColors] / `MaterialTheme.colorScheme`)
 * rather than hardcoding raw hex values.
 */
object RavenColors {
    // Pure dark OLED canvas and three elevation tiers
    val Canvas: Color = Color(0xFF09090B)
    val Background: Color = Canvas
    val Surface1: Color = Color(0xFF121216)
    val Surface2: Color = Color(0xFF1B1B22)
    val Surface3: Color = Color(0xFF24242D)

    // Translucent Apple HIG hairlines and opaque fallback outlines
    val Hairline: Color = Color(0x14FFFFFF) // #FFFFFF @ 8%
    val RimHighlight: Color = Color(0x1FFFFFFF) // #FFFFFF @ 12%
    val OutlineSubtle: Color = Color(0xFF2A2A33)

    // Off-white ink ramp (reduces OLED halation compared to #FFFFFF)
    val InkPrimary: Color = Color(0xFFF5F5F7)
    val InkSecondary: Color = Color(0xFF9E9EA8)
    val InkTertiary: Color = Color(0xFF6E6E78)

    // Raven Crimson — strictly reserved for active nav, progress bars, unread badges, and brand eye.
    // Never flood full-screen backgrounds with Crimson.
    val Crimson: Color = Color(0xFFFF4D58)

    // High-contrast cinema CTAs
    val PrimaryCtaContainer: Color = Color(0xFFF5F5F7)
    val PrimaryCtaInk: Color = Color(0xFF09090B)
    val SecondaryCtaContainer: Color = Color(0xFF1B1B22).copy(alpha = 0.78f)
    val SecondaryCtaInk: Color = InkPrimary

    // Frosted overlays & edge-to-edge vignette scrims
    val FrostedSurfaceOverlay: Color = Color(0xFF121216).copy(alpha = 0.78f)
    val FrostedFallbackScrim: Color = Color(0xFF121216).copy(alpha = 0.92f)
    val ScrimTop: Color = Color(0x0009090B)
    val ScrimBottom: Color = Color(0xFF09090B)
    val BadgeBackdrop: Color = Color(0xCC09090B) // #09090B @ 80%

    // Semantic status colors (Apple HIG dark palette)
    val Success: Color = Color(0xFF30D158)
    val Warning: Color = Color(0xFFFFD60A)
    val Error: Color = Color(0xFFFF453A)

    // Dark shimmer loading gradient stops
    val ShimmerBase: Color = Color(0xFF16161C)
    val ShimmerHighlight: Color = Color(0xFF262630)

    /** Hard cap for optional Hero/Detail top ambient artwork glow. */
    const val AMBIENT_GLOW_MAX_ALPHA: Float = 0.18f
}

/**
 * Extended semantic palette provided via [LocalRavenColors] alongside Material 3's [ColorScheme].
 */
@Immutable
data class RavenExtendedColors(
    val canvas: Color = RavenColors.Canvas,
    val surface1: Color = RavenColors.Surface1,
    val surface2: Color = RavenColors.Surface2,
    val surface3: Color = RavenColors.Surface3,
    val hairline: Color = RavenColors.Hairline,
    val rimHighlight: Color = RavenColors.RimHighlight,
    val outlineSubtle: Color = RavenColors.OutlineSubtle,
    val inkPrimary: Color = RavenColors.InkPrimary,
    val inkSecondary: Color = RavenColors.InkSecondary,
    val inkTertiary: Color = RavenColors.InkTertiary,
    val crimson: Color = RavenColors.Crimson,
    val primaryCtaContainer: Color = RavenColors.PrimaryCtaContainer,
    val primaryCtaInk: Color = RavenColors.PrimaryCtaInk,
    val secondaryCtaContainer: Color = RavenColors.SecondaryCtaContainer,
    val secondaryCtaInk: Color = RavenColors.SecondaryCtaInk,
    val frostedSurfaceOverlay: Color = RavenColors.FrostedSurfaceOverlay,
    val frostedFallbackScrim: Color = RavenColors.FrostedFallbackScrim,
    val scrimTop: Color = RavenColors.ScrimTop,
    val scrimBottom: Color = RavenColors.ScrimBottom,
    val badgeBackdrop: Color = RavenColors.BadgeBackdrop,
    val success: Color = RavenColors.Success,
    val warning: Color = RavenColors.Warning,
    val error: Color = RavenColors.Error,
    val shimmerBase: Color = RavenColors.ShimmerBase,
    val shimmerHighlight: Color = RavenColors.ShimmerHighlight,
)

val LocalRavenColors = staticCompositionLocalOf { RavenExtendedColors() }

/**
 * Material 3 dark [ColorScheme] mapped strictly to Raven Obsidian tokens.
 * `surfaceTint = Color.Transparent` prevents tonal elevation overlays from muddying OLED surfaces.
 */
val RavenDarkColorScheme: ColorScheme = darkColorScheme(
    primary = RavenColors.Crimson,
    onPrimary = RavenColors.InkPrimary,
    primaryContainer = RavenColors.Surface3,
    onPrimaryContainer = RavenColors.InkPrimary,
    secondary = RavenColors.PrimaryCtaContainer,
    onSecondary = RavenColors.PrimaryCtaInk,
    secondaryContainer = RavenColors.Surface2,
    onSecondaryContainer = RavenColors.InkPrimary,
    tertiary = RavenColors.InkSecondary,
    onTertiary = RavenColors.Canvas,
    tertiaryContainer = RavenColors.Surface2,
    onTertiaryContainer = RavenColors.InkPrimary,
    background = RavenColors.Canvas,
    onBackground = RavenColors.InkPrimary,
    surface = RavenColors.Surface1,
    onSurface = RavenColors.InkPrimary,
    surfaceVariant = RavenColors.Surface2,
    onSurfaceVariant = RavenColors.InkSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = RavenColors.InkPrimary,
    inverseOnSurface = RavenColors.Canvas,
    inversePrimary = RavenColors.Crimson,
    outline = RavenColors.OutlineSubtle,
    outlineVariant = RavenColors.Hairline,
    scrim = RavenColors.Canvas,
    error = RavenColors.Error,
    onError = RavenColors.Canvas,
    errorContainer = Color(0xFF3B1517),
    onErrorContainer = Color(0xFFFFB4AB),
    surfaceBright = RavenColors.Surface3,
    surfaceDim = RavenColors.Canvas,
    surfaceContainerLowest = RavenColors.Canvas,
    surfaceContainerLow = RavenColors.Surface1,
    surfaceContainer = RavenColors.Surface1,
    surfaceContainerHigh = RavenColors.Surface2,
    surfaceContainerHighest = RavenColors.Surface3,
)
