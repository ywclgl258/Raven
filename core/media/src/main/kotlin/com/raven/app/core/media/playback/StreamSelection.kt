@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.playback

import androidx.media3.exoplayer.DefaultLoadControl
import com.raven.app.core.model.VideoStream

/**
 * Media3 [DefaultLoadControl] buffer durations tailored to stream bitrate and link pressure.
 */
data class BufferPlan(
    val minMs: Int,
    val maxMs: Int,
    val forPlaybackMs: Int,
    val afterRebufferMs: Int,
    val backBufferMs: Int = DEFAULT_BACK_BUFFER_MS,
) {
    /** Stable signature used to decide whether an active ExoPlayer instance needs rebuild. */
    val signature: String
        get() = if (backBufferMs == DEFAULT_BACK_BUFFER_MS) {
            "$minMs/$maxMs/$forPlaybackMs/$afterRebufferMs"
        } else {
            "$minMs/$maxMs/$forPlaybackMs/$afterRebufferMs/$backBufferMs"
        }

    companion object {
        const val DEFAULT_BACK_BUFFER_MS: Int = 25_000
    }
}

/**
 * Dynamic [DefaultLoadControl] planner for high-bitrate, 720p, and strained/low-bitrate streams,
 * including a 25s keyframe back-buffer and memory-class-aware target buffer byte cap inspired by
 * Han1meViewer for smoother back/forward scrubbing.
 */
object BufferPlanner {
    val HIGH_BITRATE_PLAN: BufferPlan = BufferPlan(
        minMs = 8_000,
        maxMs = 40_000,
        forPlaybackMs = 500,
        afterRebufferMs = 800,
    )

    val STANDARD_720P_PLAN: BufferPlan = BufferPlan(
        minMs = 4_000,
        maxMs = 15_000,
        forPlaybackMs = 500,
        afterRebufferMs = 1_000,
    )

    val LOW_NET_PLAN: BufferPlan = BufferPlan(
        minMs = 2_500,
        maxMs = 12_000,
        forPlaybackMs = 500,
        afterRebufferMs = 1_000,
    )

    private const val HIGH_BITRATE_THRESHOLD_KBPS = 4_200
    private const val STANDARD_BITRATE_THRESHOLD_KBPS = 2_000

    fun targetBufferBytesForMemoryClass(memoryClassMb: Int): Int = when {
        memoryClassMb <= 192 -> 32 * 1024 * 1024
        memoryClassMb <= 384 -> 48 * 1024 * 1024
        else -> 64 * 1024 * 1024
    }

    fun plan(streamKbps: Int, linkKbps: Int = 0): BufferPlan {
        val strained = linkKbps > 0 && StreamSelector.bandwidthBudget(linkKbps) < streamKbps
        return when {
            streamKbps >= HIGH_BITRATE_THRESHOLD_KBPS -> HIGH_BITRATE_PLAN
            streamKbps >= STANDARD_BITRATE_THRESHOLD_KBPS -> if (strained) LOW_NET_PLAN else STANDARD_720P_PLAN
            else -> LOW_NET_PLAN
        }
    }

    fun planFor(stream: VideoStream, linkKbps: Int = 0): BufferPlan =
        plan(StreamSelector.requiredKbps(stream), linkKbps)

    /**
     * One buffer envelope that covers 480p through 4K. Switching resolution no longer needs a new
     * ExoPlayer (which detaches the video surface and shows a black frame on Hanime1 2K streams).
     */
    val UNIFIED_PLAN: BufferPlan = BufferPlan(
        minMs = 4_000,
        maxMs = 40_000,
        forPlaybackMs = 500,
        afterRebufferMs = 800,
    )

    fun createLoadControl(
        plan: BufferPlan,
        memoryClassMb: Int = 256,
    ): DefaultLoadControl =
        buildLoadControl(
            minMs = plan.minMs,
            maxMs = plan.maxMs,
            forPlaybackMs = plan.forPlaybackMs,
            afterRebufferMs = plan.afterRebufferMs,
            backBufferMs = plan.backBufferMs,
            memoryClassMb = memoryClassMb,
        )

    /** Load control for the single long-lived player instance. */
    fun createUnifiedLoadControl(memoryClassMb: Int = 256): DefaultLoadControl =
        buildLoadControl(
            minMs = UNIFIED_PLAN.minMs,
            maxMs = UNIFIED_PLAN.maxMs,
            forPlaybackMs = UNIFIED_PLAN.forPlaybackMs,
            afterRebufferMs = UNIFIED_PLAN.afterRebufferMs,
            backBufferMs = UNIFIED_PLAN.backBufferMs,
            memoryClassMb = memoryClassMb,
        )

    private fun buildLoadControl(
        minMs: Int,
        maxMs: Int,
        forPlaybackMs: Int,
        afterRebufferMs: Int,
        backBufferMs: Int,
        memoryClassMb: Int,
    ): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, forPlaybackMs, afterRebufferMs)
            .setBackBuffer(backBufferMs, true)
            .setTargetBufferBytes(targetBufferBytesForMemoryClass(memoryClassMb))
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
}

/**
 * Pure quality selection and bitrate estimation strategy.
 *
 * A [HEADROOM] factor (`0.7`) remains available for throughput estimates and buffer planning;
 * stream selection itself is stable and never silently lowers the preferred quality.
 */
object StreamSelector {
    const val HEADROOM: Double = 0.7
    const val UNKNOWN_KBPS: Int = 2_500
    const val DEFAULT_CEILING_HEIGHT: Int = 720

    private val resolutionWithPRegex = Regex("(?<!\\d)(\\d{3,4})\\s*p\\b", RegexOption.IGNORE_CASE)
    private val plainDigitsRegex = Regex("(?<!\\d)(\\d{3,4})(?!\\d)")
    private val fourKRegex = Regex("\\b(4k|uhd)\\b", RegexOption.IGNORE_CASE)
    private val twoKRegex = Regex("\\b(2k|qhd)\\b", RegexOption.IGNORE_CASE)

    /** Typical delivered video bitrates (kbps) sorted ascending by vertical height. */
    private val referenceKbps: List<Pair<Int, Int>> = listOf(
        144 to 200,
        240 to 450,
        360 to 800,
        480 to 1_200,
        720 to 2_500,
        1080 to 4_500,
        1440 to 9_000,
        2160 to 18_000,
    )

    fun bandwidthBudget(kbps: Int): Int =
        if (kbps <= 0) 0 else (kbps * HEADROOM).toInt()

    /**
     * Playback-observed throughput supersedes one-shot startup probing; `0` means unknown.
     */
    fun mergeEstimates(sampledKbps: Int, seededKbps: Int): Int = when {
        sampledKbps > 0 -> sampledKbps
        seededKbps > 0 -> seededKbps
        else -> 0
    }

    fun parseHeight(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        if (fourKRegex.containsMatchIn(text)) return 2160
        if (twoKRegex.containsMatchIn(text)) return 1440
        resolutionWithPRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 144..4320 }?.let { return it }
        plainDigitsRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 144..4320 }?.let { return it }
        return 0
    }

    fun extractHeight(stream: VideoStream): Int {
        val fromQuality = parseHeight(stream.quality)
        if (fromQuality > 0) return fromQuality
        return parseHeight(stream.label)
    }

    fun requiredKbps(stream: VideoStream): Int {
        val explicitBps = stream.bandwidthBps
        if (explicitBps != null && explicitBps > 0L) {
            return (explicitBps / 1_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }
        val height = extractHeight(stream)
        if (height <= 0) return UNKNOWN_KBPS
        return referenceKbps.lastOrNull { it.first <= height }?.second ?: referenceKbps.first().second
    }

    /**
     * Resolves the user's preferred stream from [streams] without altering stored preferences.
     */
    fun preferred(streams: List<VideoStream>, preferredQuality: String): VideoStream? {
        if (streams.isEmpty()) return null
        val normalized = preferredQuality.trim()
        if (normalized.isNotEmpty()) {
            streams.firstOrNull {
                it.quality.equals(normalized, ignoreCase = true) ||
                    it.label.equals(normalized, ignoreCase = true)
            }?.let { return it }
        }
        val ceiling = parseHeight(normalized).takeIf { it > 0 } ?: DEFAULT_CEILING_HEIGHT
        val ranked = streams.map { it to extractHeight(it) }.filter { it.second > 0 }
        return ranked.filter { it.second <= ceiling }.maxByOrNull { it.second }?.first
            ?: ranked.minByOrNull { it.second }?.first
            ?: streams.first()
    }

    /**
     * Resolves the preferred quality without silently lowering it to match a transient speed sample.
     * Buffering may pause playback, but the selected stream stays stable until the user changes it.
     */
    fun pick(streams: List<VideoStream>, preferredQuality: String, bandwidthKbps: Int): VideoStream =
        requireNotNull(preferred(streams, preferredQuality)) { "没有可用的清晰度" }

    /**
     * Preserves the active stream across expired-URL refreshes whenever possible. If it disappeared,
     * retain its quality tier rather than introducing a bandwidth-driven downgrade.
     */
    fun afterRefresh(streams: List<VideoStream>, previous: VideoStream, bandwidthKbps: Int): VideoStream {
        streams.firstOrNull {
            (it.quality.equals(previous.quality, ignoreCase = true) ||
                it.label.equals(previous.label, ignoreCase = true)) &&
                it.url == previous.url
        }?.let { return it }
        val key = previous.quality.ifBlank { previous.label }
        return requireNotNull(preferred(streams, key)) { "刷新后没有可用的清晰度" }
    }

    /**
     * Returns the next lower resolution below [current], or `null` if already at the lowest / unknown tier.
     */
    fun lower(streams: List<VideoStream>, current: VideoStream): VideoStream? =
        lowerCandidates(streams, current).maxByOrNull { extractHeight(it) }

    private fun lowerCandidates(streams: List<VideoStream>, current: VideoStream): List<VideoStream> {
        val currentHeight = extractHeight(current)
        if (currentHeight <= 0) return emptyList()
        return streams.filter { stream ->
            val h = extractHeight(stream)
            h in 1 until currentHeight
        }
    }

    fun recommendedLabel(bandwidthKbps: Int): String {
        val budget = bandwidthBudget(bandwidthKbps)
        if (budget <= 0) return "无法判定"
        return referenceKbps.lastOrNull { it.second <= budget }?.let { "${it.first}p" } ?: "低于 144p"
    }
}

enum class StallReason {
    STARTUP_STALL,
    MID_PLAY_STALL,
    REPEATED_STALLS,
}

sealed interface StallDecision {
    data object Hold : StallDecision
    data class Downgrade(val reason: StallReason) : StallDecision
}

/**
 * Pure stall downgrade thresholds.
 *
 * Auto-downgrades on any of:
 * - Startup stall `>= 6s` (while `positionMs < 5s`)
 * - Mid-playback single stall `>= 8s`
 * - `>= 3` stalls within a rolling `30s` window
 *
 * Stateful limits enforced by [StallTracker]: at most 2 downgrades per video and 20s cooldown.
 */
object StallPolicy {
    const val STARTUP_MS: Long = 6_000L
    const val PLAYBACK_MS: Long = 8_000L
    const val WINDOW_MS: Long = 30_000L
    const val MAX_STALLS: Int = 3
    const val STARTUP_POSITION_MS: Long = 5_000L
    const val MAX_DOWNGRADES_PER_VIDEO: Int = 2
    const val COOLDOWN_MS: Long = 20_000L

    fun evaluateReason(bufferingMs: Long, positionMs: Long, stallsInWindow: Int): StallReason? = when {
        positionMs < STARTUP_POSITION_MS && bufferingMs >= STARTUP_MS -> StallReason.STARTUP_STALL
        bufferingMs >= PLAYBACK_MS -> StallReason.MID_PLAY_STALL
        stallsInWindow >= MAX_STALLS -> StallReason.REPEATED_STALLS
        else -> null
    }

    fun shouldDowngrade(bufferingMs: Long, positionMs: Long, stallsInWindow: Int): Boolean =
        evaluateReason(bufferingMs, positionMs, stallsInWindow) != null
}

/**
 * Per-video stateful stall detector enforcing max 2 downgrades per video and 20s cooldown.
 */
class StallTracker(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val stallEvents = ArrayDeque<Long>()
    private var bufferingStartedAtMs: Long? = null
    private var lastDowngradeAtMs: Long = Long.MIN_VALUE

    var downgradeCount: Int = 0
        private set

    fun reset() {
        stallEvents.clear()
        bufferingStartedAtMs = null
        lastDowngradeAtMs = Long.MIN_VALUE
        downgradeCount = 0
    }

    fun onBufferingChanged(isBuffering: Boolean, nowMs: Long = clockMs()) {
        if (isBuffering) {
            if (bufferingStartedAtMs == null) {
                bufferingStartedAtMs = nowMs
                pruneWindow(nowMs)
                stallEvents.addLast(nowMs)
            }
        } else {
            bufferingStartedAtMs = null
        }
    }

    fun stallsInWindow(nowMs: Long = clockMs()): Int {
        pruneWindow(nowMs)
        return stallEvents.size
    }

    fun evaluate(
        positionMs: Long,
        isBuffering: Boolean,
        nowMs: Long = clockMs(),
    ): StallDecision {
        if (downgradeCount >= StallPolicy.MAX_DOWNGRADES_PER_VIDEO) {
            return StallDecision.Hold
        }
        if (lastDowngradeAtMs != Long.MIN_VALUE && (nowMs - lastDowngradeAtMs) < StallPolicy.COOLDOWN_MS) {
            return StallDecision.Hold
        }
        if (isBuffering && bufferingStartedAtMs == null) {
            onBufferingChanged(true, nowMs)
        } else if (!isBuffering) {
            bufferingStartedAtMs = null
        }
        pruneWindow(nowMs)
        val bufferingMs = bufferingStartedAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        val reason = StallPolicy.evaluateReason(
            bufferingMs = bufferingMs,
            positionMs = positionMs,
            stallsInWindow = stallEvents.size,
        )
        return if (reason != null) StallDecision.Downgrade(reason) else StallDecision.Hold
    }

    /**
     * Records that an actual stream downgrade took place, starting the 20s cooldown window.
     */
    fun recordDowngrade(nowMs: Long = clockMs()) {
        downgradeCount += 1
        lastDowngradeAtMs = nowMs
        stallEvents.clear()
        bufferingStartedAtMs = null
    }

    private fun pruneWindow(nowMs: Long) {
        val cutoff = nowMs - StallPolicy.WINDOW_MS
        while (stallEvents.isNotEmpty() && stallEvents.first() < cutoff) {
            stallEvents.removeFirst()
        }
    }
}
