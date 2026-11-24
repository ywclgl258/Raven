package com.raven.app.core.network

import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pre-built pool of specialized [OkHttpClient] instances sharing ONE [ConnectionPool],
 * ONE [Dispatcher], [Ipv4FirstDns], and [SystemProxySelector].
 *
 * Strictly satisfies the zero per-request `OkHttpClient.newBuilder().build()` invariant:
 * every client variant (including no-redirect and no-cookie variants) is constructed once here.
 */
class RavenOkHttpPool(
    cacheDir: File?,
    val connectionPool: ConnectionPool,
    val dispatcher: Dispatcher,
    val dns: Dns,
    val proxySelector: SystemProxySelector,
    val cookieJar: SiteCookieJar,
    val mediaRedirectCache: MediaRedirectCache,
    val peerTracker: PeerTracker,
    siteRegistryProvider: () -> SiteRegistry,
) {
    private val httpCache: Cache? = cacheDir?.let { Cache(it, HTTP_CACHE_SIZE_BYTES) }

    private val sharedBase: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .dns(dns)
        .proxySelector(proxySelector)
        .eventListenerFactory(peerTracker.eventListenerFactory())
        .build()

    /** Standard HTML/document browsing client (5s connect, 12s read, 25s call, 32 MiB HTTP cache). */
    val pageClient: OkHttpClient = sharedBase.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .cache(httpCache)
        .addInterceptor(SiteRequestHeaderInterceptor(SiteRequestKind.PAGE, siteRegistryProvider))
        .build()

    /** REST/JSON API client (5s connect, 15s read, 25s call, 32 MiB HTTP cache). */
    val apiClient: OkHttpClient = sharedBase.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .cache(httpCache)
        .addInterceptor(SiteRequestHeaderInterceptor(SiteRequestKind.API, siteRegistryProvider))
        .build()

    /**
     * High-resolution gallery/cover image client (no total call timeout so slow links can complete
     * up to 24 MiB original pages; 30s read stall timeout; no HTTP cache — Coil/GalleryCache own caching).
     */
    val imageClient: OkHttpClient = sharedBase.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .cache(null)
        .addInterceptor(SiteRequestHeaderInterceptor(SiteRequestKind.IMAGE, siteRegistryProvider))
        .build()

    /**
     * Streaming & download media client: no call timeout, 30s read timeout, no HTTP cache,
     * integrated [MediaRedirectCache] (application + network interceptors) and `<video>` fetch shape.
     */
    val mediaClient: OkHttpClient = sharedBase.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .cache(null)
        .addInterceptor(mediaRedirectCache.interceptor)
        .addNetworkInterceptor(mediaRedirectCache.networkInterceptor)
        .addInterceptor(SiteRequestHeaderInterceptor(SiteRequestKind.MEDIA, siteRegistryProvider))
        .build()

    /**
     * Fast mirror & connectivity probe client: short timeouts (3s/4s/5s), zero cookies,
     * no cache, no automatic redirects, no retry on connection failure.
     */
    val probeClient: OkHttpClient = sharedBase.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor(SiteRequestHeaderInterceptor(SiteRequestKind.PROBE, siteRegistryProvider))
        .build()

    // Pre-built internal variants so SiteHttpClient never invokes newBuilder().build() per request.
    private val pageNoRetryClient: OkHttpClient = pageClient.newBuilder()
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    private val apiNoRetryClient: OkHttpClient = apiClient.newBuilder()
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    private val pageNoRedirectClient: OkHttpClient = pageNoRetryClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val apiNoRedirectClient: OkHttpClient = apiNoRetryClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val pageNoCookieClient: OkHttpClient = pageClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val pageNoCookieNoRedirectClient: OkHttpClient = pageNoRedirectClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val apiNoCookieClient: OkHttpClient = apiClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val apiNoCookieNoRedirectClient: OkHttpClient = apiNoRedirectClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val imageNoCookieClient: OkHttpClient = imageClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val imageNoRedirectClient: OkHttpClient = imageClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val mediaNoRedirectClient: OkHttpClient = mediaClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val mediaNoCookieClient: OkHttpClient = mediaClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    /**
     * Selects the exact pre-built [OkHttpClient] matching [request] without allocating any client.
     */
    fun clientFor(request: SiteHttpRequest): OkHttpClient = when (request.kind) {
        SiteRequestKind.PROBE -> probeClient
        SiteRequestKind.MEDIA -> when {
            !request.followRedirects -> mediaNoRedirectClient
            !request.allowCookies -> mediaNoCookieClient
            else -> mediaClient
        }
        SiteRequestKind.IMAGE -> when {
            !request.followRedirects -> imageNoRedirectClient
            !request.allowCookies -> imageNoCookieClient
            else -> imageClient
        }
        SiteRequestKind.API -> when {
            !request.followRedirects && !request.allowCookies -> apiNoCookieNoRedirectClient
            !request.followRedirects -> apiNoRedirectClient
            !request.allowCookies -> apiNoCookieClient
            !request.allowRetry -> apiNoRetryClient
            else -> apiClient
        }
        SiteRequestKind.PAGE -> when {
            !request.followRedirects && !request.allowCookies -> pageNoCookieNoRedirectClient
            !request.followRedirects -> pageNoRedirectClient
            !request.allowCookies -> pageNoCookieClient
            !request.allowRetry -> pageNoRetryClient
            else -> pageClient
        }
    }

    fun evictIdleConnections() {
        connectionPool.evictAll()
    }

    companion object {
        const val HTTP_CACHE_SIZE_BYTES: Long = 32L * 1024 * 1024
    }
}
