package com.raven.app.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * Remembers the active [RavenGridSpec] for the current configuration's screen width.
 */
@Composable
fun rememberRavenGridSpec(): RavenGridSpec {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) { RavenLayout.gridSpecForWidth(widthDp) }
}

/**
 * Remembers the active [RavenWindowClass] for the current configuration's screen width.
 */
@Composable
fun rememberRavenWindowClass(): RavenWindowClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) { RavenLayout.windowClassForWidth(widthDp) }
}
