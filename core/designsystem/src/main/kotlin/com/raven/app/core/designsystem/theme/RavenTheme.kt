package com.raven.app.core.designsystem.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Root theme for Raven (`渡鸦`) implementing **Dark Obsidian × Apple HIG Precision × Netflix Cinema**.
 *
 * Provides [RavenDarkColorScheme], [RavenTypography], [RavenShapes], [LocalRavenColors],
 * [LocalReduceTransparency], [LocalMotionReduced], and [LocalRavenFeedback].
 */
@Composable
fun RavenTheme(
    reduceTransparency: Boolean = false,
    uiSoundEffectsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val currentHaptic by rememberUpdatedState(haptic)
    val currentSoundEnabled by rememberUpdatedState(uiSoundEffectsEnabled)
    val extendedColors = remember { RavenExtendedColors() }
    val motionReduced = remember(context) { isSystemMotionReduced(context) }
    val feedbackController = remember(context.applicationContext) {
        AndroidRavenFeedbackController(
            context = context.applicationContext,
            hapticProvider = { currentHaptic },
            soundEnabledProvider = { currentSoundEnabled },
        )
    }

    DisposableEffect(feedbackController) {
        onDispose { feedbackController.release() }
    }

    CompositionLocalProvider(
        LocalRavenColors provides extendedColors,
        LocalReduceTransparency provides reduceTransparency,
        LocalMotionReduced provides motionReduced,
        LocalRavenFeedback provides feedbackController,
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
