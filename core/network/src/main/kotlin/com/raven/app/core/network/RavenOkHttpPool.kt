package com.raven.app.core.network

import com.raven.app.core.sitespi.RequestSessionScope
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import com.raven.app.core.sitespi.SiteSessionStore
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File
import java.net.Proxy
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
    sessionStoreProvider: () -> SiteSessionStore,
) {
    private val httpCache: Cache? = cacheDir?.let { Cache(it, HTTP_CACHE_SIZE_BYTES) }

    private val sharedBase: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .dns(dns)
        .proxySelector(proxySelector)
        .eventListenerFactory(peerTracker.eventListenerFactory())
        .build()

    // Update and backup requests use the same transport pool but carry no site session or decorators.
    val externalApiClient: OkHttpClient = sharedBase.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    val externalDownloadClient: OkHttpClient = externalApiClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val credentialGuard = CrossHostCredentialGuardInterceptor(siteRegistryProvider)

    /**
     * Outermost application interceptor on every cookie-bearing client: captures the account
     * auth epoch before the call leaves so [SiteCookieJar.saveFromResponse] can reject a
     * `Set-Cookie` that races a logout (NET-09). Must stay FIRST in the interceptor list — OkHttp's
     * application chain (and therefore its cookie JarBridge callbacks) runs inside it.
     */
    private val cookieEpochGuard = SiteCookieEpochInterceptor(sessionStoreProvider, siteRegistryProvider)

    /** Standard HTML/document browsing client (5s connect, 12s read, 25s call, 32 MiB HTTP cache). */
    val pageClient: OkHttpClient = sharedBase.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .cache(httpCache)
        .addInterceptor(cookieEpochGuard)
        .addInterceptor(SiteRequestHeaderInterceptor(SiteRequestKind.PAGE, siteRegistryProvider))
        .addNetworkInterceptor(credentialGuard)
        .build()

    /** REST/JSON API client (5s connect, 15s read, 25s call, 32 MiB HTTP cache). */
    val apiClient: OkHttpClient = sharedBase.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .cache(httpCache)
        .addInterceptor(cookieEpochGuard)
        .addInterceptor(SiteRequestHeaderInterceptor(SiteRequestKind.API, siteRegistryProvider))
        .addNetworkInterceptor(credentialGuard)
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
        .addInterceptor(cookieEpochGuard)
        .addInterceptor(SiteRequestHeaderInterceptor(SiteRequestKind.IMAGE, siteRegistryProvider))
        .addInterceptor(SiteImageTransportUnwrapInterceptor(siteRegistryProvider))
        .addNetworkInterceptor(credentialGuard)
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
        .addInterceptor(cookieEpochGuard)
        .addInterceptor(mediaRedirectCache.interceptor)
        .addNetworkInterceptor(mediaRedirectCache.networkInterceptor)
        .addInterceptor(SiteRequestHeaderInterceptor(SiteRequestKind.MEDIA, siteRegistryProvider))
        .addNetworkInterceptor(credentialGuard)
        .build()

    /**
     * Dedicated LAN DLNA/UPnP casting client: strictly no redirects, no cookies, direct LAN
     * socket (`Proxy.NO_PROXY`), short timeouts, and zero site header decoration.
     */
    val lanClient: OkHttpClient = sharedBase.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .proxy(Proxy.NO_PROXY)
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

    private val mediaNoCookieNoRedirectClient: OkHttpClient = mediaNoRedirectClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val imageNoCookieNoRedirectClient: OkHttpClient = imageNoRedirectClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val pageNoCookieNoRetryClient: OkHttpClient = pageNoRetryClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val apiNoCookieNoRetryClient: OkHttpClient = apiNoRetryClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val probeFollowRedirectsClient: OkHttpClient = probeClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Selects the exact pre-built [OkHttpClient] matching [request] without allocating any client.
     *
     * [RequestSessionScope.ISOLATED] requests always take a `NO_COOKIES` variant: the cookie jar
     * must neither supply nor absorb credentials for a candidate login handshake.
     */
    fun clientFor(request: SiteHttpRequest): OkHttpClient {
        val allowCookies = request.allowCookies && request.sessionScope == RequestSessionScope.SITE
        return when (request.kind) {
            SiteRequestKind.PROBE -> if (request.followRedirects) probeFollowRedirectsClient else probeClient
            SiteRequestKind.MEDIA -> when {
                !request.followRedirects && !allowCookies -> mediaNoCookieNoRedirectClient
                !request.followRedirects -> mediaNoRedirectClient
                !allowCookies -> mediaNoCookieClient
                else -> mediaClient
            }
            SiteRequestKind.IMAGE -> when {
                !request.followRedirects && !allowCookies -> imageNoCookieNoRedirectClient
                !request.followRedirects -> imageNoRedirectClient
                !allowCookies -> imageNoCookieClient
                else -> imageClient
            }
            SiteRequestKind.API -> when {
                !request.followRedirects && !allowCookies -> apiNoCookieNoRedirectClient
                !request.followRedirects -> apiNoRedirectClient
                !allowCookies && !request.allowRetry -> apiNoCookieNoRetryClient
                !allowCookies -> apiNoCookieClient
                !request.allowRetry -> apiNoRetryClient
                else -> apiClient
            }
            SiteRequestKind.PAGE -> when {
                !request.followRedirects && !allowCookies -> pageNoCookieNoRedirectClient
                !request.followRedirects -> pageNoRedirectClient
                !allowCookies && !request.allowRetry -> pageNoCookieNoRetryClient
                !allowCookies -> pageNoCookieClient
                !request.allowRetry -> pageNoRetryClient
                else -> pageClient
            }
        }
    }

    fun evictIdleConnections() {
        connectionPool.evictAll()
    }

    /**
     * Drops every cached PAGE/API response body.
     *
     * The OkHttp cache is keyed by URL only, so it cannot distinguish "this page rendered for
     * account A" from "for account B". On any account change (login / logout / import) full
     * eviction is the safe action; account-scoped requests additionally bypass the cache entirely
     * (see `SiteHttpClient.buildOkHttpRequest`).
     */
    fun evictHttpCache() {
        httpCache?.evictAll()
    }

    companion object {
        const val HTTP_CACHE_SIZE_BYTES: Long = 32L * 1024 * 1024
    }
}
