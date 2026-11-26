package com.raven.app.core.network

import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Non-blocking suspend coroutine per-host start-time rate pacer (`Mutex + delay`, ZERO `Thread.sleep`).
 *
 * Staggers request dispatch starts per host without holding any OkHttp dispatcher thread and
 * responds immediately to coroutine cancellation. Only registered site request hosts are paced;
 * external media CDNs, update hosts, and fast mirror probes bypass pacing. R34V is the explicit
 * exception for media because its HTML and media endpoints share one upstream rate-limit budget.
 */
class HostPacer(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) {
    private class HostSlot {
        val mutex = Mutex()
        var nextSlotMs: Long = 0L
    }

    private val slots = ConcurrentHashMap<String, HostSlot>()

    /**
     * Reserves the next start slot for [host] separated by [gapMs] and suspends via [delayFn]
     * outside the mutex until the reserved start time arrives.
     *
     * @return the number of milliseconds waited (0 if immediately dispatched).
     */
    suspend fun pace(host: String, gapMs: Long): Long {
        val normalizedHost = host.trim().lowercase(Locale.ROOT)
        if (normalizedHost.isEmpty() || gapMs <= 0L) return 0L
        val slot = slots.computeIfAbsent(normalizedHost) { HostSlot() }
        val waitMs = slot.mutex.withLock {
            val now = clockMs()
            val start = maxOf(now, slot.nextSlotMs)
            slot.nextSlotMs = start + gapMs
            (start - now).coerceAtLeast(0L)
        }
        if (waitMs > 0L) {
            delayFn(waitMs)
        }
        return waitMs
    }

    /**
     * Reserves the next start slot for [host] separated by [baseGapMs] with browser-like
     * `±jitterFraction` timing variance so parallel fetches never fire lockstep bursts.
     */
    suspend fun paceWithJitter(
        host: String,
        baseGapMs: Long,
        jitterFraction: Double = DEFAULT_JITTER_FRACTION,
    ): Long {
        if (baseGapMs <= 0L) return 0L
        val clampedJitter = jitterFraction.coerceIn(0.0, 0.5)
        val factor = if (clampedJitter <= 0.0) {
            1.0
        } else {
            Random.nextDouble(1.0 - clampedJitter, 1.0 + clampedJitter)
        }
        val effectiveGap = (baseGapMs * factor).roundToLong().coerceAtLeast(1L)
        return pace(host, effectiveGap)
    }

    /**
     * Browser-native staggered pacing for multi-thread gallery image requests.
     * Registered site hosts obey `imageIntervalMs` (floored at [MIN_BROWSER_IMAGE_GAP_MS]);
     * external image CDNs use a light [DEFAULT_CDN_IMAGE_GAP_MS] micro-stagger with jitter.
     */
    suspend fun paceForImageWithJitter(
        host: String,
        descriptor: SiteDescriptor?,
    ): Long {
        val baseGap = if (descriptor != null && descriptor.matchesRequestHost(host)) {
            descriptor.networkProfile.imageIntervalMs.coerceAtLeast(MIN_BROWSER_IMAGE_GAP_MS)
        } else {
            DEFAULT_CDN_IMAGE_GAP_MS
        }
        return paceWithJitter(host, baseGap)
    }

    /**
     * Advances the next dispatch slot for [host] by [cooldownMs] when upstream signals HTTP
     * 429/503/509 throttling so all concurrent workers immediately back off together.
     */
    suspend fun penalize(host: String, cooldownMs: Long) {
        val normalizedHost = host.trim().lowercase(Locale.ROOT)
        if (normalizedHost.isEmpty() || cooldownMs <= 0L) return
        val slot = slots.computeIfAbsent(normalizedHost) { HostSlot() }
        slot.mutex.withLock {
            val targetMs = clockMs() + cooldownMs
            if (targetMs > slot.nextSlotMs) {
                slot.nextSlotMs = targetMs
            }
        }
    }

    /**
     * Computes whether a request to [host] / [encodedPath] for [descriptor] should be paced and
     * suspends accordingly. Unregistered/CDN hosts and [SiteRequestKind.PROBE] are never paced;
     * R34V media is the explicit exception for its shared upstream rate-limit budget.
     */
    suspend fun paceForRequest(
        host: String,
        encodedPath: String,
        kind: SiteRequestKind,
        descriptor: SiteDescriptor?,
    ): Long {
        if (kind == SiteRequestKind.PROBE) return 0L
        if (descriptor == null || !descriptor.matchesRequestHost(host)) return 0L
        val gapMs = when {
            descriptor.id == SiteId.R34V && kind == SiteRequestKind.MEDIA ->
                descriptor.networkProfile.pageIntervalMs
            kind == SiteRequestKind.IMAGE || BrowserHeaders.isImagePath(encodedPath) ->
                descriptor.networkProfile.imageIntervalMs
            else -> descriptor.networkProfile.pageIntervalMs
        }
        return pace(host, gapMs)
    }

    fun reset() {
        slots.clear()
    }

    companion object {
        const val DEFAULT_PAGE_GAP_MS: Long = 400L
        const val DEFAULT_IMAGE_GAP_MS: Long = 150L
        const val MIN_BROWSER_IMAGE_GAP_MS: Long = 60L
        const val DEFAULT_CDN_IMAGE_GAP_MS: Long = 45L
        const val DEFAULT_JITTER_FRACTION: Double = 0.25
    }
}
