package com.raven.app.core.network

import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-blocking suspend coroutine per-host start-time rate pacer (`Mutex + delay`, ZERO `Thread.sleep`).
 *
 * Staggers request dispatch starts per host without holding any OkHttp dispatcher thread and
 * responds immediately to coroutine cancellation. Only registered site request hosts are paced;
 * external media CDNs, update hosts, and fast mirror probes bypass pacing.
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
     * Computes whether a request to [host] / [encodedPath] for [descriptor] should be paced and
     * suspends accordingly. Unregistered/CDN hosts and [SiteRequestKind.PROBE] are never paced.
     */
    suspend fun paceForRequest(
        host: String,
        encodedPath: String,
        kind: SiteRequestKind,
        descriptor: SiteDescriptor?,
    ): Long {
        if (kind == SiteRequestKind.PROBE || kind == SiteRequestKind.MEDIA) return 0L
        if (descriptor == null || !descriptor.matchesRequestHost(host)) return 0L
        val gapMs = if (kind == SiteRequestKind.IMAGE || BrowserHeaders.isImagePath(encodedPath)) {
            descriptor.networkProfile.imageIntervalMs
        } else {
            descriptor.networkProfile.pageIntervalMs
        }
        return pace(host, gapMs)
    }

    fun reset() {
        slots.clear()
    }

    companion object {
        const val DEFAULT_PAGE_GAP_MS: Long = 400L
        const val DEFAULT_IMAGE_GAP_MS: Long = 150L
    }
}
