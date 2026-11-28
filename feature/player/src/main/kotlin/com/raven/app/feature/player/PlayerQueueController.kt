package com.raven.app.feature.player

import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.media.download.PlaybackQueueSession
import com.raven.app.core.media.download.PlaybackQueueStore
import com.raven.app.core.media.playback.PlayerEngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Player queue + auto-continue: in-place queue switching (select / remove / advance / end) and the
 * 5s auto-continue countdown that is evaluated by the foreground ticker.
 *
 * In-place switching is guarded by [switchInFlightKey]: the queue index is committed before
 * [openArgs] flushes the old progress and resolves the new media, and [PlayerViewModel.open]
 * clears the marker once its load finishes so a failed load cannot wedge the queue.
 */
internal class PlayerQueueController(
    private val store: PlaybackQueueStore,
    private val scope: CoroutineScope,
    private val activeTarget: () -> PlayerLaunchArgs?,
    private val localState: MutableStateFlow<PlayerLocalState>,
    private val settings: () -> AppSettings,
    private val engine: () -> PlayerEngineState,
    private val elapsedRealtime: () -> Long,
    private val openArgs: (PlayerLaunchArgs, Boolean) -> Unit,
    private val persistProgress: suspend (PlayerLaunchArgs, Boolean) -> Unit,
    private val emitSideEffect: (PlayerSideEffect) -> Unit,
    private val notice: (String) -> Unit,
) {
    /** Set to the composite key being switched to; null means a queue switch may start. */
    var switchInFlightKey: String? = null

    fun select(index: Int): Boolean {
        val target = activeTarget() ?: return false
        val token = target.queueToken ?: return false
        if (switchInFlightKey != null) return false
        val previous = store.sessionFor(token, target.siteId, target.mediaId) ?: return false
        if (index !in previous.items.indices || index == previous.currentIndex) return false

        clearCountdown()
        val selected = store.select(token, target.siteId, target.mediaId, index) ?: return false
        return openSelectedQueueItem(previous, target, selected)
    }

    /**
     * Removes an entry with stable token semantics. Removing the current entry opens its successor
     * (or predecessor when it was last) through the same in-place flow; removing the sole entry ends
     * the queue while leaving the current media playable.
     */
    fun remove(index: Int): Boolean {
        val target = activeTarget() ?: return false
        val token = target.queueToken ?: return false
        if (switchInFlightKey != null) return false
        val previous = store.sessionFor(token, target.siteId, target.mediaId) ?: return false
        if (index !in previous.items.indices) return false
        val removesCurrent = index == previous.currentIndex
        if (removesCurrent) clearCountdown()

        val updated = store.removeAt(token, index)
        if (updated == null) {
            return previous.items.size == 1 && store.snapshot() == null
        }
        if (!removesCurrent) {
            if (!updated.hasNext) clearCountdown()
            return true
        }
        return openSelectedQueueItem(previous, target, updated)
    }

    fun advance(): Boolean {
        val target = activeTarget() ?: return false
        val token = target.queueToken ?: return false
        val current = store.sessionFor(token, target.siteId, target.mediaId) ?: return false
        if (!current.hasNext) return false
        return select(current.currentIndex + 1)
    }

    private fun openSelectedQueueItem(
        previous: PlaybackQueueSession,
        target: PlayerLaunchArgs,
        selected: PlaybackQueueSession,
    ): Boolean {
        val item = selected.currentItem ?: run {
            store.restore(previous, selected)
            return false
        }
        switchInFlightKey = target.compositeKey
        if (item.kind.isVideo) {
            val args = PlayerLaunchArgs.fromMediaItem(
                item = item,
                autoplay = true,
                offlineOnly = selected.offlineOnly,
                queueToken = selected.token,
            )
            openArgs(args, true)
        } else {
            scope.launch {
                runCatching { persistProgress(target, true) }
            }
            emitSideEffect(
                PlayerSideEffect.OpenGalleryFromQueue(
                    item = item,
                    offlineOnly = selected.offlineOnly,
                    queueToken = selected.token,
                ),
            )
            switchInFlightKey = null
        }
        return true
    }

    fun cancelCountdown() {
        if (clearCountdown()) notice("已取消自动连播")
    }

    fun clearCountdown(): Boolean {
        val local = localState.value
        if (local.autoContinueDeadlineElapsedMs == null && local.autoContinueRemainingMs == 0L) return false
        localState.update {
            it.copy(autoContinueDeadlineElapsedMs = null, autoContinueRemainingMs = 0L)
        }
        return true
    }

    fun maybeStartCountdown() {
        if (!settings().autoContinueQueue || switchInFlightKey != null) return
        if (localState.value.autoContinueDeadlineElapsedMs != null) return
        val target = activeTarget() ?: return
        val engine = engine()
        if (!engine.isEnded || engine.siteId != target.siteId || engine.mediaId != target.mediaId) return
        val session = store.sessionFor(target.queueToken, target.siteId, target.mediaId) ?: return
        if (!session.hasNext) return
        localState.update {
            it.copy(
                autoContinueDeadlineElapsedMs = elapsedRealtime() + PlayerViewModel.AUTO_CONTINUE_COUNTDOWN_MS,
                autoContinueRemainingMs = PlayerViewModel.AUTO_CONTINUE_COUNTDOWN_MS,
            )
        }
    }

    /** Evaluated only by [runForegroundTicker]; no background countdown coroutine is created. */
    fun evaluateTick(nowElapsedMs: Long = elapsedRealtime()): Boolean {
        val deadline = localState.value.autoContinueDeadlineElapsedMs ?: return false
        val target = activeTarget()
        val engine = engine()
        val session = target?.let {
            store.sessionFor(it.queueToken, it.siteId, it.mediaId)
        }
        if (!settings().autoContinueQueue ||
            target == null ||
            !engine.isEnded ||
            engine.siteId != target.siteId ||
            engine.mediaId != target.mediaId ||
            session?.hasNext != true
        ) {
            clearCountdown()
            return false
        }

        val remaining = deadline - nowElapsedMs
        if (remaining > 0L) {
            if (localState.value.autoContinueRemainingMs != remaining) {
                localState.update { it.copy(autoContinueRemainingMs = remaining) }
            }
            return false
        }

        // Clear before switching so repeated ticks cannot advance the queue twice.
        clearCountdown()
        return advance()
    }

    fun end() {
        clearCountdown()
        val target = activeTarget() ?: return
        val token = target.queueToken ?: return
        store.endIfCurrent(token, target.siteId, target.mediaId)
    }


}
