package com.raven.app.core.sitespi

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Route-keyed mirror lease router with health fallback (default 10-minute lease).
 * Automatically invalidates and re-selects a healthy mirror whenever `routeKey` changes
 * (e.g., network switch or proxy change) or when the leased host fails.
 */
class MirrorLeaseRouter(
    hosts: List<String>,
    private val leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
    private val failureCooldownMs: Long = DEFAULT_FAILURE_COOLDOWN_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val probeHost: (suspend (host: String) -> Boolean)? = null,
) : HostRouter {

    private data class Lease(
        val routeKey: String,
        val host: String,
        val expiresAtMs: Long,
    )

    private val candidates: List<String> = hosts
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()

    private val standard = HostRouter.Standard(candidates)
    private val probeMutex = Mutex()
    private val stateLock = Any()

    private var activeLease: Lease? = null
    private val failedUntilMs = HashMap<String, Long>()

    init {
        require(candidates.isNotEmpty()) { "MirrorLeaseRouter requires at least one candidate host" }
        require(leaseDurationMs > 0L) { "leaseDurationMs must be positive" }
    }

    override fun candidateHosts(): List<String> = candidates

    override fun rewriteUrl(url: String, host: String): String = standard.rewriteUrl(url, host)

    override suspend fun activeHost(routeKey: String): String {
        val now = clockMs()
        synchronized(stateLock) {
            val lease = activeLease
            if (lease != null && lease.routeKey == routeKey && now < lease.expiresAtMs && !isCoolingDownLocked(lease.host, now)) {
                return lease.host
            }
        }

        return probeMutex.withLock {
            val checkTime = clockMs()
            synchronized(stateLock) {
                val lease = activeLease
                if (lease != null && lease.routeKey == routeKey && checkTime < lease.expiresAtMs && !isCoolingDownLocked(lease.host, checkTime)) {
                    return@withLock lease.host
                }
            }

            val orderedCandidates = synchronized(stateLock) {
                val healthy = candidates.filter { !isCoolingDownLocked(it, checkTime) }
                val cooling = candidates.filter { isCoolingDownLocked(it, checkTime) }
                healthy + cooling
            }

            val probe = probeHost
            val selected = if (probe != null) {
                var picked: String? = null
                for (host in orderedCandidates) {
                    val ok = runCatching { probe.invoke(host) }.getOrDefault(false)
                    if (ok) {
                        picked = host
                        break
                    } else {
                        markFailureInternal(host, checkTime)
                    }
                }
                picked ?: orderedCandidates.first()
            } else {
                orderedCandidates.first()
            }

            synchronized(stateLock) {
                activeLease = Lease(
                    routeKey = routeKey,
                    host = selected,
                    expiresAtMs = clockMs() + leaseDurationMs,
                )
            }
            selected
        }
    }

    override fun reportHostFailure(host: String, routeKey: String, cause: Throwable?) {
        val normalized = host.trim().lowercase()
        val now = clockMs()
        synchronized(stateLock) {
            failedUntilMs[normalized] = now + failureCooldownMs
            if (activeLease?.host == normalized && (routeKey.isEmpty() || activeLease?.routeKey == routeKey)) {
                activeLease = null
            }
        }
    }

    override fun reportHostSuccess(host: String, routeKey: String) {
        val normalized = host.trim().lowercase()
        if (normalized !in candidates) return
        val now = clockMs()
        synchronized(stateLock) {
            failedUntilMs.remove(normalized)
            activeLease = Lease(
                routeKey = routeKey,
                host = normalized,
                expiresAtMs = now + leaseDurationMs,
            )
        }
    }

    fun invalidate() {
        synchronized(stateLock) {
            activeLease = null
            failedUntilMs.clear()
        }
    }

    private fun markFailureInternal(host: String, now: Long) {
        synchronized(stateLock) {
            failedUntilMs[host] = now + failureCooldownMs
        }
    }

    private fun isCoolingDownLocked(host: String, now: Long): Boolean {
        val until = failedUntilMs[host] ?: return false
        if (now >= until) {
            failedUntilMs.remove(host)
            return false
        }
        return true
    }

    companion object {
        const val DEFAULT_LEASE_DURATION_MS: Long = 10 * 60 * 1000L
        const val DEFAULT_FAILURE_COOLDOWN_MS: Long = 2 * 60 * 1000L
    }
}
