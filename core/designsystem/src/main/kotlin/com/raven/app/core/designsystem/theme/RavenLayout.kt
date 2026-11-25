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
 * HarmonyOS Design 4-tier responsive breakpoint and grid system (`2 / 4 / 8 / 12` columns).
 */
object RavenLayout {
    const val BREAKPOINT_SM_DP: Int = 320
    const val BREAKPOINT_MD_DP: Int = 600
    const val BREAKPOINT_LG_DP: Int = 840

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
