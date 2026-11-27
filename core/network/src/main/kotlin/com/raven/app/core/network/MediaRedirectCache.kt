package com.raven.app.core.network

import com.raven.app.core.model.SiteId
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Bounded 30-minute TTL LRU cache for site-origin -> CDN HTTP 302 media redirects
 * (e.g. KVS `/get-file` endpoints).
 *
 * - Application [interceptor]: rewrites eligible origin URLs directly to the cached CDN URL;
 *   if the CDN rejects with `403`, `404`, or `410` (expired token / anti-hotlink change),
 *   immediately invalidates the entry, logs to [rateLimitLog], and re-resolves via origin within
 *   the same OkHttp call.
 * - Network [networkInterceptor]: observes final redirected cross-host hops and backfills the
 *   cache on successful responses.
 *
 * Account isolation (non-negotiable): every entry records the account identity and the
 * [SiteSessionStore-like][credentialEpochProvider] auth epoch under which it was resolved. A cached
 * signed CDN URL minted for account A is NEVER replayed for account B or after a logout/login that
 * moved the epoch — the lookup degrades to a cache miss and re-resolves through origin. Without this,
 * a pending media request could resume playback of the previous account's signed stream, or a
 * logged-out user could keep streaming on a token the site already revoked.
 */
class MediaRedirectCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val eligible: (HttpUrl) -> Boolean,
    private val rateLimitLog: RateLimitLog? = null,
    /** Resolves the account identity owning [originUrl]; null = unpartitioned (tests / guests). */
    private val accountResolver: ((HttpUrl) -> SiteId?)? = null,
    /** Reads the current auth epoch for an account; used to invalidate stale signed URLs. */
    private val credentialEpochProvider: ((SiteId) -> Long)? = null,
) {
    private data class ResolvedEntry(
        val targetUrl: HttpUrl,
        val expiresAtMs: Long,
        val accountId: SiteId? = null,
        val credentialEpoch: Long? = null,
    )

    internal data class RedirectOriginTag(val originUrl: String, val accountId: SiteId? = null)

    init {
        require(ttlMs > 0L) { "ttlMs must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, ResolvedEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedEntry>?): Boolean =
            size > maxEntries
    }

    val interceptor: Interceptor = Interceptor { chain -> interceptApplication(chain) }
    val networkInterceptor: Interceptor = Interceptor { chain -> interceptNetwork(chain) }

    /** Test/introspection view of the active entry for [originUrl] under its current account+epoch. */
    fun getCachedRedirect(originUrl: String): HttpUrl? {
        val url = originUrl.toHttpUrlOrNull() ?: return null
        val accountId = resolveAccount(url)
        return getActive(originUrl, accountId, accountId?.let { currentEpoch(it) })?.targetUrl
    }

    fun invalidate(originUrl: String) {
        synchronized(lock) {
            cache.remove(originUrl)
        }
    }

    /**
     * Drops every entry minted for [siteId]'s account, so no signed URL from before a
     * logout / account switch can be replayed.
     */
    fun invalidateForSite(siteId: SiteId) {
        val accountId = siteId.accountId
        synchronized(lock) {
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.accountId == accountId) {
                    iterator.remove()
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }

    fun size(): Int = synchronized(lock) {
        evictExpiredLocked(clockMs())
        cache.size
    }

    internal fun interceptApplication(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!eligible(original.url)) {
            return chain.proceed(original)
        }
        val key = original.url.toString()
        val accountId = resolveAccount(original.url)
        val epoch = accountId?.let { currentEpoch(it) }
        val tag = RedirectOriginTag(key, accountId)
        val hit = getActive(key, accountId, epoch)
        if (hit != null) {
            val crossHost = !hit.targetUrl.host.equals(original.url.host, ignoreCase = true)
            val directBuilder = original.newBuilder()
                .tag(RedirectOriginTag::class.java, tag)
                .url(hit.targetUrl)
            if (crossHost) {
                directBuilder.removeHeader("Authorization")
                    .removeHeader("Cookie")
            }
            val directRequest = directBuilder.build()
            val directResponse = chain.proceed(directRequest)
            if (directResponse.code !in INVALIDATION_CODES) {
                return directResponse
            }
            val rejectedCode = directResponse.code
            directResponse.close()
            removeIfMatching(key, hit)
            rateLimitLog?.record(original.url.host, "CDN HTTP $rejectedCode，重新解析媒体地址")
        }
        val originRequest = original.newBuilder()
            .tag(RedirectOriginTag::class.java, tag)
            .build()
        return chain.proceed(originRequest)
    }

    internal fun interceptNetwork(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val originTag = request.tag(RedirectOriginTag::class.java)
        val originKey = originTag?.originUrl
        if (originKey != null && response.isSuccessful) {
            val origin = originKey.toHttpUrlOrNull()
            if (origin != null && !request.url.host.equals(origin.host, ignoreCase = true)) {
                val accountId = originTag.accountId
                putIfAbsentOrExpired(originKey, request.url, accountId, accountId?.let { currentEpoch(it) })
            }
        }
        return response
    }

    private fun resolveAccount(url: HttpUrl): SiteId? =
        runCatching { accountResolver?.invoke(url) }.getOrNull()?.accountId

    private fun currentEpoch(accountId: SiteId): Long? =
        runCatching { credentialEpochProvider?.invoke(accountId) }.getOrNull()

    private fun getActive(key: String, accountId: SiteId?, epoch: Long?): ResolvedEntry? {
        val now = clockMs()
        return synchronized(lock) {
            val entry = cache[key] ?: return null
            if (entry.expiresAtMs <= now) {
                cache.remove(key)
                return null
            }
            // A credential-scoped entry is only valid for the same account and the same auth epoch.
            if (entry.accountId != accountId) return null
            if (entry.accountId != null && entry.credentialEpoch != epoch) return null
            entry
        }
    }

    private fun putIfAbsentOrExpired(key: String, targetUrl: HttpUrl, accountId: SiteId?, epoch: Long?) {
        val now = clockMs()
        val fresh = ResolvedEntry(
            targetUrl = targetUrl,
            expiresAtMs = now + ttlMs,
            accountId = accountId,
            credentialEpoch = epoch,
        )
        synchronized(lock) {
            if (cache.size >= maxEntries) {
                evictExpiredLocked(now)
            }
            val existing = cache[key]
            // Replace when absent, expired, or the entry belongs to a different account/epoch —
            // an entry resolved under an old epoch is already unreachable, so it must not block the
            // freshly re-resolved URL from being stored.
            if (existing == null ||
                existing.expiresAtMs <= now ||
                existing.accountId != accountId ||
                existing.credentialEpoch != epoch
            ) {
                cache[key] = fresh
            }
        }
    }

    private fun removeIfMatching(key: String, expected: ResolvedEntry) {
        synchronized(lock) {
            if (cache[key] === expected) {
                cache.remove(key)
            }
        }
    }

    private fun evictExpiredLocked(now: Long) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtMs <= now) {
                iterator.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 30L * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES: Int = 256
        val INVALIDATION_CODES: Set<Int> = setOf(403, 404, 410)
    }
}
