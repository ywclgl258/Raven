package com.raven.app.core.database.domain

import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.WatchProgress

/**
 * Deterministic completion & resume threshold semantics for video playback and gallery/comic reading.
 *
 * Video rules (aligned with proven R34V behavior):
 * - Short clips (`1ms..40_000ms`): completing requires reaching `durationMs` (or explicit `completed = true`),
 *   so a 20-second end-credits grace window never marks half of a short clip finished; resumable from `>= 1s`.
 * - Long videos (`> 40_000ms`): marked completed once `positionMs >= durationMs - 20_000ms` (or explicit
 *   `completed = true`); resumable when not completed and `positionMs >= 15s`.
 *
 * Reading rules:
 * - Started when `currentPage > 0` or `completed = true`.
 * - Completed when `completed = true` or (`totalPages > 0 && currentPage >= totalPages`).
 */
object CompletionPolicy {
    const val SHORT_CLIP_MAX_DURATION_MS: Long = 40_000L
    const val LONG_VIDEO_END_GRACE_MS: Long = 20_000L
    const val SHORT_CLIP_RESUME_MIN_MS: Long = 1_000L
    const val LONG_VIDEO_RESUME_MIN_MS: Long = 15_000L

    fun isShortClip(durationMs: Long): Boolean = durationMs in 1L..SHORT_CLIP_MAX_DURATION_MS

    fun isWatchStarted(progress: WatchProgress): Boolean =
        progress.positionMs > 0L || progress.completed

    fun isWatchCompleted(progress: WatchProgress): Boolean {
        if (progress.completed) return true
        val duration = progress.durationMs
        val position = progress.positionMs
        if (duration <= 0L || position <= 0L) return false
        return if (isShortClip(duration)) {
            position >= duration
        } else {
            position >= duration - LONG_VIDEO_END_GRACE_MS
        }
    }

    fun isWatchResumable(progress: WatchProgress): Boolean {
        if (isWatchCompleted(progress)) return false
        val threshold = if (isShortClip(progress.durationMs)) {
            SHORT_CLIP_RESUME_MIN_MS
        } else {
            LONG_VIDEO_RESUME_MIN_MS
        }
        return progress.positionMs >= threshold
    }

    fun resolveWatchProgress(progress: WatchProgress): WatchProgress {
        val safePosition = progress.positionMs.coerceAtLeast(0L)
        val safeDuration = progress.durationMs.coerceAtLeast(0L)
        val normalized = progress.copy(positionMs = safePosition, durationMs = safeDuration)
        return normalized.copy(completed = isWatchCompleted(normalized))
    }

    fun isReadingStarted(progress: ReadingProgress): Boolean =
        progress.currentPage > 0 || progress.completed

    fun isReadingCompleted(progress: ReadingProgress): Boolean =
        progress.completed || (progress.totalPages > 0 && progress.currentPage >= progress.totalPages)

    fun isReadingResumable(progress: ReadingProgress): Boolean =
        isReadingStarted(progress) && !isReadingCompleted(progress)

    fun resolveReadingProgress(progress: ReadingProgress): ReadingProgress {
        val safeTotal = progress.totalPages.coerceAtLeast(0)
        val safePage = if (safeTotal > 0) {
            progress.currentPage.coerceIn(1, safeTotal)
        } else {
            progress.currentPage.coerceAtLeast(1)
        }
        val normalized = progress.copy(currentPage = safePage, totalPages = safeTotal)
        return normalized.copy(completed = isReadingCompleted(normalized))
    }
}
