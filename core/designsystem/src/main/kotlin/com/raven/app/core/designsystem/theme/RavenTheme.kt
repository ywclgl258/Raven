package com.raven.app.core.designsystem.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Root theme for Raven (`渡鸦`) implementing **Dark Obsidian × Apple HIG Precision × Netflix Cinema**.
 *
 * Provides [RavenDarkColorScheme], [RavenTypography], [RavenShapes], [LocalRavenColors],
 * [LocalReduceTransparency], and [LocalMotionReduced].
 */
@Composable
fun RavenTheme(
    reduceTransparency: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val extendedColors = remember { RavenExtendedColors() }
    val motionReduced = remember(context) { isSystemMotionReduced(context) }

    CompositionLocalProvider(
        LocalRavenColors provides extendedColors,
        LocalReduceTransparency provides reduceTransparency,
        LocalMotionReduced provides motionReduced,
        LocalContentColor provides extendedColors.inkPrimary,
    ) {
        MaterialTheme(
            colorScheme = RavenDarkColorScheme,
            typography = RavenTypography,
            shapes = RavenShapes.MaterialShapes,
            content = content,
        )
    }
}

/**
 * Ergonomic accessors for Raven theme tokens inside `@Composable` scopes.
 */
object RavenTheme {
    val colors: RavenExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRavenColors.current

    val shapes: RavenShapes
        get() = RavenShapes

    val reduceTransparency: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReduceTransparency.current

    val motionReduced: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalMotionReduced.current
}

private fun isSystemMotionReduced(context: Context): Boolean = runCatching {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    scale == 0f
}.getOrDefault(false)
