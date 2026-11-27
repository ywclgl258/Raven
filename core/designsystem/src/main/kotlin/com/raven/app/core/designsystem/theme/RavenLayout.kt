package com.raven.app.core.designsystem.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * HarmonyOS Design responsive horizontal width breakpoints (`xs` / `sm` / `md` / `lg`).
 */
enum class RavenBreakpoint {
    XS,
    SM,
    MD,
    LG,
}

/**
 * Adaptive window width classes aligned with HarmonyOS / Material 3 multi-device breakpoints:
 * - [COMPACT]: `< 600dp` (phone portrait)
 * - [MEDIUM]: `600..839dp` (unfolded foldable / small tablet)
 * - [EXPANDED]: `840..1199dp` (tablet landscape / large foldable)
 * - [LARGE]: `>= 1200dp` (desktop / wide tablet)
 */
enum class RavenWindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    LARGE,
}

/**
 * Responsive grid metrics (`columns`, `margin`, `gutter`, and adaptive media grid counts)
 * mapped from [RavenBreakpoint].
 */
@Immutable
data class RavenGridSpec(
    val breakpoint: RavenBreakpoint,
    val columns: Int,
    val margin: Dp,
    val gutter: Dp,
    val videoGridColumns: Int,
    val galleryGridColumns: Int,
)

/**
 * HarmonyOS Design 4-tier responsive breakpoint, grid, and modal sizing system (`2 / 4 / 8 / 12` columns).
 */
object RavenLayout {
    const val BREAKPOINT_SM_DP: Int = 320
    const val BREAKPOINT_MD_DP: Int = 600
    const val BREAKPOINT_LG_DP: Int = 840
    const val BREAKPOINT_DESKTOP_DP: Int = 1200

    // Button layout tokens
    val ButtonMaxWidth: Dp = 448.dp
    val ButtonRowSpacing: Dp = 12.dp

    // Dialog sizing tokens: width = min(screenWidth - 32dp, 400dp), max height = 90% viewport
    val DialogMaxWidth: Dp = 400.dp
    val DialogHorizontalMargin: Dp = 16.dp
    const val DIALOG_MAX_HEIGHT_FRACTION: Float = 0.90f

    // Sheet sizing tokens (phone portrait, landscape, tablet/foldable center panel)
    const val SHEET_MEDIUM_HEIGHT_FRACTION: Float = 0.60f
    val SheetLargeTopClearance: Dp = 8.dp
    val SheetLandscapeMaxWidth: Dp = 480.dp
    val SheetCenterMaxWidth: Dp = 480.dp
    val SheetCenterDefaultHeight: Dp = 560.dp
    val SheetCenterMinHeight: Dp = 320.dp
    const val SHEET_CENTER_MAX_HEIGHT_FRACTION: Float = 0.90f

    // Desktop / large-screen content constraint
    val DesktopContentMaxWidth: Dp = 1280.dp

    fun windowClassForWidth(widthDp: Int): RavenWindowClass = when {
        widthDp < BREAKPOINT_MD_DP -> RavenWindowClass.COMPACT
        widthDp < BREAKPOINT_LG_DP -> RavenWindowClass.MEDIUM
        widthDp < BREAKPOINT_DESKTOP_DP -> RavenWindowClass.EXPANDED
        else -> RavenWindowClass.LARGE
    }

    /** Computes HarmonyOS dialog width: `min(screenWidthDp - 32dp, 400dp)`. */
    fun computeDialogWidth(screenWidthDp: Int): Dp {
        val available = (screenWidthDp - 32).coerceAtLeast(240)
        return available.coerceAtMost(DialogMaxWidth.value.toInt()).dp
    }

    /** Returns true on medium/expanded/large screens (`>= 600dp`) where centered sheets are supported. */
    fun supportsCenteredSheet(widthDp: Int): Boolean = widthDp >= BREAKPOINT_MD_DP

    fun gridSpecForWidth(widthDp: Int): RavenGridSpec = when {
        widthDp < BREAKPOINT_SM_DP -> RavenGridSpec(
            breakpoint = RavenBreakpoint.XS,
            columns = 2,
            margin = 12.dp,
            gutter = 8.dp,
            videoGridColumns = 1,
            galleryGridColumns = 2,
        )
        widthDp < BREAKPOINT_MD_DP -> RavenGridSpec(
            breakpoint = RavenBreakpoint.SM,
            columns = 4,
            margin = 16.dp,
            gutter = 12.dp,
            videoGridColumns = 2,
            galleryGridColumns = 2,
        )
        widthDp < BREAKPOINT_LG_DP -> RavenGridSpec(
            breakpoint = RavenBreakpoint.MD,
            columns = 8,
            margin = 24.dp,
            gutter = 16.dp,
            videoGridColumns = 3,
            galleryGridColumns = 4,
        )
        else -> RavenGridSpec(
            breakpoint = RavenBreakpoint.LG,
            columns = 12,
            margin = 32.dp,
            gutter = 20.dp,
            videoGridColumns = 4,
            galleryGridColumns = 5,
        )
    }
}

/**
 * Responsive window width/height in `dp` sourced from [LocalWindowInfo] rather than
 * `LocalConfiguration`.
 *
 * `Configuration.screenWidthDp` / `screenHeightDp` describe the *device* screen and are rounded to
 * whole `dp`. On edge-to-edge (`enableEdgeToEdge`) and in split-screen / freeform / foldable
 * multi-window they overstate the space actually available to this composition, which is exactly
 * what Lint's `ConfigurationScreenWidthHeight` warns about. `WindowInfo.containerSize` reports the
 * real current window bounds and updates on resize, so modal sizing stays correct when the user
 * drags the window divider or unfolds the device.
 */
@Immutable
data class RavenWindowSize(val widthDp: Int, val heightDp: Int)

/**
 * Resolves the current window size in `dp`, preferring live [WindowInfo.containerSize] bounds and
 * falling back to the configuration only before the host `ComposeView` has reported a real size.
 */
@Composable
fun rememberRavenWindowSize(): RavenWindowSize {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // WindowInfo's default implementation yields Int.MIN_VALUE and an unattached LazyWindowInfo
    // yields 0x0, so guard both before trusting the value.
    val hasMeasuredSize = containerSize.width > 0 && containerSize.height > 0
    val widthDp = if (hasMeasuredSize) {
        with(density) { containerSize.width.toDp().value }.roundToInt()
    } else {
        configuration.deviceScreenFallbackWidth()
    }
    val heightDp = if (hasMeasuredSize) {
        with(density) { containerSize.height.toDp().value }.roundToInt()
    } else {
        configuration.deviceScreenFallbackHeight()
    }
    // A single remember keeps the slot layout stable across the measured/unmeasured transition.
    return remember(widthDp, heightDp) { RavenWindowSize(widthDp, heightDp) }
}

/**
 * Device-screen width `dp`, used only by [rememberRavenWindowSize] before the composition window
 * has been measured.
 *
 * Deliberately a plain (non-`@Composable`) function: on edge-to-edge and multi-window these values
 * overstate the usable window, so they must never be the primary source inside composition —
 * keeping the read here prevents lint's `ConfigurationScreenWidthHeight` from being masked by a
 * blanket suppression while still avoiding a zero-sized first frame.
 */
private fun Configuration.deviceScreenFallbackWidth(): Int = screenWidthDp

/** Device-screen height `dp` counterpart of [deviceScreenFallbackWidth]. */
private fun Configuration.deviceScreenFallbackHeight(): Int = screenHeightDp

/**
 * The current window width in `dp`, for the active composition window.
 */
@Composable
fun rememberRavenWindowWidthDp(): Int = rememberRavenWindowSize().widthDp

/**
 * Remembers the active [RavenGridSpec] for the current window width.
 */
@Composable
fun rememberRavenGridSpec(): RavenGridSpec {
    val widthDp = rememberRavenWindowWidthDp()
    return remember(widthDp) { RavenLayout.gridSpecForWidth(widthDp) }
}

/**
 * Remembers the active [RavenWindowClass] for the current window width.
 */
@Composable
fun rememberRavenWindowClass(): RavenWindowClass {
    val widthDp = rememberRavenWindowWidthDp()
    return remember(widthDp) { RavenLayout.windowClassForWidth(widthDp) }
}
