package com.raven.app.core.network

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
 */
class MediaRedirectCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val eligible: (HttpUrl) -> Boolean,
    private val rateLimitLog: RateLimitLog? = null,
) {
    private data class ResolvedEntry(
        val targetUrl: HttpUrl,
        val expiresAtMs: Long,
    )

    private data class RedirectOriginTag(val originUrl: String)

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

    fun getCachedRedirect(originUrl: String): HttpUrl? = getActive(originUrl)?.targetUrl

    fun invalidate(originUrl: String) {
        synchronized(lock) {
            cache.remove(originUrl)
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
        val tag = RedirectOriginTag(key)
        val hit = getActive(key)
        if (hit != null) {
            val directRequest = original.newBuilder()
                .tag(RedirectOriginTag::class.java, tag)
                .url(hit.targetUrl)
                .build()
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
        val originKey = request.tag(RedirectOriginTag::class.java)?.originUrl
        if (originKey != null && response.isSuccessful) {
            val origin = originKey.toHttpUrlOrNull()
            if (origin != null && !request.url.host.equals(origin.host, ignoreCase = true)) {
                putIfAbsentOrExpired(originKey, request.url)
            }
        }
        return response
    }

    private fun getActive(key: String): ResolvedEntry? {
        val now = clockMs()
        return synchronized(lock) {
            val entry = cache[key] ?: return null
            if (entry.expiresAtMs <= now) {
                cache.remove(key)
                return null
            }
            entry
        }
    }

    private fun putIfAbsentOrExpired(key: String, targetUrl: HttpUrl) {
        val now = clockMs()
        val fresh = ResolvedEntry(targetUrl = targetUrl, expiresAtMs = now + ttlMs)
        synchronized(lock) {
            if (cache.size >= maxEntries) {
                evictExpiredLocked(now)
            }
            val existing = cache[key]
            if (existing == null || existing.expiresAtMs <= now) {
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
