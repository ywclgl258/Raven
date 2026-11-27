package com.raven.app.feature.settings

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.raven.app.core.designsystem.component.RavenAppLockOverlay
import com.raven.app.core.designsystem.theme.LocalRavenColors
import kotlinx.coroutines.delay

/** Do not attach sensitive views until the process-wide lock decision permits it. */
@Composable
fun RavenPrivacyGate(
    coordinator: AppLockSessionCoordinator,
    onBiometricClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val state by coordinator.state.collectAsState()
    val savedState = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(LocalRavenColors.current.canvas)) {
        if (!state.lockInitialized) return@Box
        if (!state.isLocked) {
            savedState.SaveableStateProvider("unlocked", content)
            return@Box
        }
        val deadline = state.lockoutUntilElapsedMs
        fun remainingSeconds(): Int {
            val remainingMillis = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            return ((remainingMillis + 999L) / 1000L).toInt()
        }
        var remaining by remember(deadline) { mutableIntStateOf(remainingSeconds()) }
        LaunchedEffect(deadline) {
            if (deadline == 0L) return@LaunchedEffect
            while (remaining > 0) {
                delay(1000L)
                remaining = remainingSeconds()
            }
            coordinator.onLockoutExpired()
        }
        RavenAppLockOverlay(
            onSubmitPinAsync = { coordinator.verifyPin(it) },
            errorMessage = state.errorMessage,
            lockoutSecondsRemaining = remaining,
            onBiometricClick = onBiometricClick,
        )
    }
}
