package com.raven.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.security.PrivacyLockStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-scoped state for Raven's PIN / biometric app lock.
 *
 * Survives Activity configuration changes (rotation) and internal Activity transitions
 * (`MainActivity` <-> `PlayerActivity` <-> `SiteAuthActivity`), while ensuring cold start
 * waits for the first real [AppSettings] emission before deciding lock status.
 */
data class AppLockSessionState(
    val isLocked: Boolean = false,
    val lockInitialized: Boolean = false,
    val failedAttempts: Int = 0,
    val lockoutUntilElapsedMs: Long = 0L,
    val errorMessage: String? = null,
    val isVerifying: Boolean = false,
)

class AppLockSessionCoordinator(
    private val privacyLockStore: PrivacyLockStore,
    private val dispatchers: AppDispatchers,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _state = MutableStateFlow(AppLockSessionState())
    val state: StateFlow<AppLockSessionState> = _state.asStateFlow()

    private val verifyMutex = Mutex()
    private var latestSettings: AppSettings? = null
    private var lastBackgroundElapsedMs: Long = 0L
    private var startedActivityCount: Int = 0
    private var lifecycleAttached: Boolean = false

    fun attach(application: Application) {
        if (lifecycleAttached) return
        lifecycleAttached = true
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit

                override fun onActivityStarted(activity: Activity) {
                    val wasBackgrounded = startedActivityCount == 0
                    startedActivityCount += 1
                    if (wasBackgrounded && !activity.isChangingConfigurations) {
                        onAppForegrounded(elapsedRealtimeMs())
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                    if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
                        onAppBackgrounded(elapsedRealtimeMs())
                    }
                }
            },
        )
    }

    fun onSettingsLoaded(settings: AppSettings) {
        latestSettings = settings
        val hasPin = privacyLockStore.hasPin()
        _state.update { current ->
            if (!current.lockInitialized) {
                current.copy(
                    lockInitialized = true,
                    isLocked = settings.appLockEnabled && hasPin,
                    errorMessage = null,
                )
            } else if (!settings.appLockEnabled || !hasPin) {
                current.copy(
                    isLocked = false,
                    failedAttempts = 0,
                    lockoutUntilElapsedMs = 0L,
                    errorMessage = null,
                )
            } else {
                current
            }
        }
    }

    fun onAppForegrounded(nowElapsedMs: Long = elapsedRealtimeMs()) {
        val settings = latestSettings
        val bgAt = lastBackgroundElapsedMs
        lastBackgroundElapsedMs = 0L
        if (settings != null && settings.appLockEnabled && privacyLockStore.hasPin() && bgAt > 0L) {
            val elapsedMs = (nowElapsedMs - bgAt).coerceAtLeast(0L)
            val timeoutMs = settings.appLockTimeoutSeconds.coerceAtLeast(0) * 1_000L
            if (elapsedMs >= timeoutMs) {
                _state.update { it.copy(isLocked = true) }
            }
        }
    }

    fun onAppBackgrounded(nowElapsedMs: Long = elapsedRealtimeMs()) {
        lastBackgroundElapsedMs = nowElapsedMs
    }

    suspend fun verifyPin(candidate: String, nowElapsedMs: Long = elapsedRealtimeMs()): Boolean {
        return verifyMutex.withLock {
            val snapshot = _state.value
            if (snapshot.lockoutUntilElapsedMs > nowElapsedMs) {
                return@withLock false
            }
            _state.update { it.copy(isVerifying = true, errorMessage = null) }
            try {
                val ok = withContext(dispatchers.default) {
                    privacyLockStore.verifyPin(candidate)
                }
                if (ok) {
                    _state.update {
                        it.copy(
                            isLocked = false,
                            failedAttempts = 0,
                            lockoutUntilElapsedMs = 0L,
                            errorMessage = null,
                            isVerifying = false,
                        )
                    }
                } else {
                    _state.update { current ->
                        val nextAttempts = current.failedAttempts + 1
                        val nextLockoutUntil = if (nextAttempts >= MAX_PIN_ATTEMPTS) {
                            nowElapsedMs + LOCKOUT_DURATION_MS
                        } else {
                            current.lockoutUntilElapsedMs
                        }
                        current.copy(
                            failedAttempts = nextAttempts,
                            lockoutUntilElapsedMs = nextLockoutUntil,
                            isVerifying = false,
                        )
                    }
                }
                ok
            } catch (t: Throwable) {
                _state.update { it.copy(isVerifying = false) }
                throw t
            }
        }
    }

    fun onLockoutExpired() {
        _state.update { current ->
            if (current.lockoutUntilElapsedMs <= 0L) {
                current
            } else {
                current.copy(
                    lockoutUntilElapsedMs = 0L,
                    failedAttempts = 0,
                )
            }
        }
    }

    fun onBiometricUnlocked() {
        _state.update {
            it.copy(
                isLocked = false,
                failedAttempts = 0,
                lockoutUntilElapsedMs = 0L,
                errorMessage = null,
                isVerifying = false,
            )
        }
    }

    fun onBiometricError(message: String?) {
        val clean = message?.trim()?.takeIf { it.isNotEmpty() } ?: return
        _state.update { it.copy(errorMessage = clean) }
    }

    companion object {
        const val MAX_PIN_ATTEMPTS: Int = 5
        const val LOCKOUT_DURATION_MS: Long = 30_000L
    }
}
