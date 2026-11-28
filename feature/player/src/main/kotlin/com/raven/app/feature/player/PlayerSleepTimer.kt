package com.raven.app.feature.player

import com.raven.app.core.common.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Monotonic sleep timer: set/cancel, a background-resilient deadline watcher for background audio
 * and PiP, and the foreground tick evaluation used by `runForegroundTicker`.
 *
 * Extracted from [PlayerViewModel]; the countdown fields stay in [PlayerLocalState] so the UI state
 * assembly is unchanged.
 */
internal class PlayerSleepTimer(
    private val scope: CoroutineScope,
    private val backgroundScope: () -> CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val elapsedRealtime: () -> Long,
    private val localState: MutableStateFlow<PlayerLocalState>,
    private val activeTarget: () -> PlayerLaunchArgs?,
    private val pausePlayback: () -> Unit,
    private val persistProgress: suspend (PlayerLaunchArgs, Boolean) -> Unit,
    private val notice: (String) -> Unit,
    private val emitSideEffect: (PlayerSideEffect) -> Unit,
) {
    private var backgroundJob: Job? = null

    fun setMinutes(minutes: Int): Boolean {
        if (minutes !in PlayerViewModel.MIN_SLEEP_MINUTES..PlayerViewModel.MAX_SLEEP_MINUTES) {
            emitSideEffect(PlayerSideEffect.ShowMessage("睡眠定时支持 1 至 180 分钟"))
            return false
        }
        val durationMs = minutes * 60_000L
        val deadline = elapsedRealtime() + durationMs
        localState.update {
            it.copy(
                sleepTimerDeadlineElapsedMs = deadline,
                sleepTimerDurationMinutes = minutes,
                sleepTimerRemainingMs = durationMs,
            )
        }
        notice("已设置 ${minutes} 分钟后暂停播放")
        return true
    }

    /** Stops the background watcher without clearing the deadline (foreground ticker takes over). */
    fun disarm() {
        backgroundJob?.cancel()
        backgroundJob = null
    }

    fun cancel() {
        if (localState.value.sleepTimerDeadlineElapsedMs == null) return
        backgroundJob?.cancel()
        backgroundJob = null
        localState.update {
            it.copy(
                sleepTimerDeadlineElapsedMs = null,
                sleepTimerDurationMinutes = null,
                sleepTimerRemainingMs = 0L,
            )
        }
        notice("已取消睡眠定时")
    }

    /**
     * Arms a background-resilient deadline watcher.
     *
     * The foreground `stallAndSleepJob` is bound to `repeatOnLifecycle(STARTED)` and therefore
     * stops the moment the player is backgrounded. With background audio (or PiP) playback keeps
     * running, so without this watcher the deadline could pass entirely unnoticed until the user
     * returns to the screen. Uses the same injectable monotonic clock as [evaluateSleepTimerTick]
     * and is disarmed by [onForegroundResumed] to avoid two competing evaluators.
     */
    fun armBackground(playbackContinuesInBackground: Boolean) {
        backgroundJob?.cancel()
        backgroundJob = null
        if (!playbackContinuesInBackground) return
        if (localState.value.sleepTimerDeadlineElapsedMs == null) return
        backgroundJob = backgroundScope().launch {
            while (isActive) {
                val deadline = localState.value.sleepTimerDeadlineElapsedMs ?: break
                val remaining = deadline - elapsedRealtime()
                if (remaining <= 0L) {
                    // `evaluateSleepTimerTick` pauses ExoPlayer and starts a persist launch, both
                    // of which must run on the application thread. Re-read the clock inside the hop
                    // so the expiry decision is not based on a timestamp captured before dispatch.
                    withContext(dispatchers.main) { evaluateTick(elapsedRealtime()) }
                    break
                }
                delay(remaining.coerceAtMost(PlayerViewModel.BACKGROUND_SLEEP_TIMER_POLL_MS))
            }
        }
    }

    /**
     * Evaluates the monotonic sleep timer against [nowElapsedMs].
     * Returns `true` when the timer expired and paused playback.
     */
    fun evaluateTick(nowElapsedMs: Long = elapsedRealtime()): Boolean {
        val deadline = localState.value.sleepTimerDeadlineElapsedMs ?: return false
        val remaining = deadline - nowElapsedMs
        return if (remaining <= 0L) {
            localState.update {
                it.copy(
                    sleepTimerDeadlineElapsedMs = null,
                    sleepTimerDurationMinutes = null,
                    sleepTimerRemainingMs = 0L,
                )
            }
            pausePlayback()
            val target = activeTarget()
            if (target != null) {
                scope.launch {
                    runCatching { persistProgress(target, true) }
                }
            }
            val msg = "睡眠定时已到，已暂停播放"
            notice(msg)
            emitSideEffect(PlayerSideEffect.ShowMessage(msg))
            true
        } else {
            if (localState.value.sleepTimerRemainingMs != remaining) {
                localState.update { it.copy(sleepTimerRemainingMs = remaining) }
            }
            false
        }
    }

    // ---- Lock & Fullscreen State ----


}
