package com.raven.app.core.network

import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.security.InMemoryVaultStorage
import com.raven.app.core.security.SessionVault
import com.raven.app.core.security.SoftwareAesGcmVaultCipher
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class SiteHttpClientTest {

    private fun buildTestStack(
        serverHostAlias: String = "apiq.iwara.tv",
        cdnHostAlias: String = "filesq.iwara.tv",
        updateHostAlias: String = "api.github.com",
        pacerClockMs: () -> Long = System::currentTimeMillis,
        pacerDelayFn: suspend (Long) -> Unit = {},
    ): TestNetworkStack {
        val vault = SessionVault(SoftwareAesGcmVaultCipher(), InMemoryVaultStorage())
        val iwaraPlugin = object : SitePlugin {
            override val descriptor: SiteDescriptor = SiteDescriptor(
                id = SiteId.IWARA,
                displayName = "Iwara",
                primaryKind = MediaKind.VIDEO,
                defaultHost = "www.iwara.tv",
                networkProfile = SiteNetworkProfile(
                    userAgent = "RavenTest/1.0",
                    referer = "https://www.iwara.tv/",
                    mediaReferer = "https://www.iwara.tv/",
                    apiBase = "https://apiq.iwara.tv",
                    apiHosts = setOf("apiq.iwara.tv", "api.iwara.tv"),
                    mediaDomains = setOf("iwara.tv"),
                    pageIntervalMs = 200L,
                    imageIntervalMs = 80L,
                ),
            )
            override val authProvider: AuthProvider = object : AuthProvider {
                override val mode: AuthMode = AuthMode.JwtBearer
                override fun decorateRequestHeaders(request: SiteHttpRequest, host: String): Map<String, String> {
                    val out = LinkedHashMap<String, String>()
                    out["Origin"] = "https://www.iwara.tv"
                    out["x-site"] = "www.iwara.tv"
                    // Deliberately return Authorization even on CDN to verify SiteRequestHeaderInterceptor's guard!
                    vault.getBearerToken(SiteId.IWARA)?.let { out["Authorization"] = "Bearer $it" }
                    return out
                }
            }
        }
        val registry = SiteRegistry(listOf(iwaraPlugin))

        val localDns = Ipv4FirstDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname in setOf(serverHostAlias, cdnHostAlias, updateHostAlias, "www.iwara.tv", "iwara.tv")) {
                        listOf(InetAddress.getLoopbackAddress())
                    } else {
                        Dns.SYSTEM.lookup(hostname)
                    }
            },
        )
        val proxySelector = SystemProxySelector { null }
        val connectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)
        val dispatcher = Dispatcher()
        val rateLimitLog = RateLimitLog()
        val peerTracker = PeerTracker()
        val mediaRedirectCache = MediaRedirectCache(
            eligible = { url -> registry.isRegisteredSiteHost(url.host) },
            rateLimitLog = rateLimitLog,
        )
        val cookieJar = SiteCookieJar(vault, registry)
        val networkMonitor = NetworkMonitor(context = null, proxySelector = proxySelector, connectionPool = connectionPool)
        val pool = RavenOkHttpPool(
            cacheDir = null,
            connectionPool = connectionPool,
            dispatcher = dispatcher,
            dns = localDns,
            proxySelector = proxySelector,
            cookieJar = cookieJar,
            mediaRedirectCache = mediaRedirectCache,
            peerTracker = peerTracker,
            siteRegistryProvider = { registry },
        )
        val hostPacer = HostPacer(clockMs = pacerClockMs, delayFn = pacerDelayFn)
        val client = SiteHttpClient(
            okHttpPool = pool,
            hostPacer = hostPacer,
            sessionStore = vault,
            networkMonitor = networkMonitor,
            rateLimitLog = rateLimitLog,
            siteRegistry = registry,
            dispatchers = CustomAppDispatchers(Dispatchers.IO),
        )
        val diagnostics = NetworkDiagnostics(
            httpClient = client,
            networkMonitor = networkMonitor,
            proxySelector = proxySelector,
            peerTracker = peerTracker,
            rateLimitLog = rateLimitLog,
            siteRegistry = registry,
        )
        return TestNetworkStack(vault, registry, pool, client, hostPacer, networkMonitor, rateLimitLog, diagnostics)
    }

    private data class TestNetworkStack(
        val vault: SessionVault,
        val registry: SiteRegistry,
        val pool: RavenOkHttpPool,
        val client: SiteHttpClient,
        val hostPacer: HostPacer,
        val networkMonitor: NetworkMonitor,
        val rateLimitLog: RateLimitLog,
        val diagnostics: NetworkDiagnostics,
    )

    @Test
    fun `all 5 pre-built OkHttpClients share ONE ConnectionPool Dispatcher Dns and ProxySelector`() {
        val stack = buildTestStack()
        val clients = listOf(
            stack.pool.pageClient,
            stack.pool.apiClient,
            stack.pool.imageClient,
            stack.pool.mediaClient,
            stack.pool.probeClient,
        )
        for (c in clients) {
            assertSame(stack.pool.connectionPool, c.connectionPool)
            assertSame(stack.pool.dispatcher, c.dispatcher)
            assertSame(stack.pool.dns, c.dns)
            assertSame(stack.pool.proxySelector, c.proxySelector)
        }
    }

    @Test
    fun `attaches site headers and Bearer strictly on allowed API hosts and strips credentials on CDNs and update hosts`() = runTest {
        val apiServer = MockWebServer()
        val cdnServer = MockWebServer()
        val updateServer = MockWebServer()
        apiServer.start()
        cdnServer.start()
        updateServer.start()
        try {
            val pacedWaits = mutableListOf<Long>()
            val stack = buildTestStack(
                pacerClockMs = { 10_000L },
                pacerDelayFn = { ms -> pacedWaits.add(ms) },
            )
            stack.vault.saveBearerToken(SiteId.IWARA, "secret-jwt-123", "alice")

            apiServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
            apiServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":2}"""))
            cdnServer.enqueue(MockResponse().setResponseCode(200).setBody("binary-media"))
            updateServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"tag":"v1.0"}"""))

            // 1. API host receives UA, Referer, Origin, x-site, AND Bearer token
            val apiUrl = "http://apiq.iwara.tv:${apiServer.port}/subscriptions/videos"
            val r1 = stack.client.execute(
                SiteHttpRequest(
                    url = apiUrl,
                    siteId = SiteId.IWARA,
                    kind = SiteRequestKind.API,
                    authEpoch = stack.vault.authEpoch(SiteId.IWARA),
                ),
            )
            assertEquals(200, r1.code)
            val rec1 = apiServer.takeRequest()
            assertEquals("RavenTest/1.0", rec1.getHeader("User-Agent"))
            assertEquals("https://www.iwara.tv/", rec1.getHeader("Referer"))
            assertEquals("https://www.iwara.tv", rec1.getHeader("Origin"))
            assertEquals("www.iwara.tv", rec1.getHeader("x-site"))
            assertEquals("Bearer secret-jwt-123", rec1.getHeader("Authorization"))

            // Second immediate call to same API host triggers HostPacer (200ms)
            stack.client.execute(SiteHttpRequest(url = apiUrl, siteId = SiteId.IWARA, kind = SiteRequestKind.API))
            assertEquals(listOf(200L), pacedWaits)

            // 2. Media CDN host receives public UA/Referer/Origin, NEVER Authorization!
            val cdnUrl = "http://filesq.iwara.tv:${cdnServer.port}/file/abc.mp4"
            val rCdn = stack.client.execute(
                SiteHttpRequest(url = cdnUrl, siteId = SiteId.IWARA, kind = SiteRequestKind.MEDIA),
            )
            assertEquals(200, rCdn.code)
            val recCdn = cdnServer.takeRequest()
            assertEquals("RavenTest/1.0", recCdn.getHeader("User-Agent"))
            assertEquals("video", recCdn.getHeader("Sec-Fetch-Dest"))
            assertNull("Authorization MUST NEVER be sent to media CDN", recCdn.getHeader("Authorization"))
            assertNull("Cookie MUST NEVER be sent to media CDN", recCdn.getHeader("Cookie"))

            // 3. Unregistered update host receives default UA only, zero site Referer/Authorization/Cookies
            val updateUrl = "http://api.github.com:${updateServer.port}/releases/latest"
            stack.client.execute(SiteHttpRequest(url = updateUrl, kind = SiteRequestKind.API))
            val recUpdate = updateServer.takeRequest()
            assertNull(recUpdate.getHeader("Authorization"))
            assertNull(recUpdate.getHeader("Cookie"))
            assertNull(recUpdate.getHeader("Referer"))
            assertTrue(recUpdate.getHeader("User-Agent")!!.isNotBlank())
        } finally {
            apiServer.shutdown()
            cdnServer.shutdown()
            updateServer.shutdown()
        }
    }

    @Test
    fun `rejects stale authEpoch with SiteException Cancelled before hitting the network`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val stack = buildTestStack()
            stack.vault.saveBearerToken(SiteId.IWARA, "jwt-v1", "alice")
            val staleEpoch = stack.vault.authEpoch(SiteId.IWARA)

            // User logs out -> epoch increments
            stack.vault.clearSite(SiteId.IWARA)
            assertTrue(stack.vault.authEpoch(SiteId.IWARA) > staleEpoch)

            var caught: Throwable? = null
            try {
                stack.client.execute(
                    SiteHttpRequest(
                        url = "http://apiq.iwara.tv:${server.port}/subscriptions/videos",
                        siteId = SiteId.IWARA,
                        kind = SiteRequestKind.API,
                        authEpoch = staleEpoch,
                    ),
                )
            } catch (t: Throwable) {
                caught = t
            }
            assertTrue("Expected SiteException.Cancelled, got $caught", caught is SiteException.Cancelled)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `maps HTTP status codes and TLS or transport failures to typed SiteException and logs 429`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val stack = buildTestStack()
            val base = "http://apiq.iwara.tv:${server.port}"

            // 429 with Retry-After -> RateLimited + logged in RateLimitLog
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
            var rateErr: Throwable? = null
            try {
                stack.client.executeChecked(SiteHttpRequest(url = "$base/v", siteId = SiteId.IWARA, kind = SiteRequestKind.API))
            } catch (t: Throwable) {
                rateErr = t
            }
            assertTrue(rateErr is SiteException.RateLimited)
            assertEquals(30L, (rateErr as SiteException.RateLimited).retryAfterSeconds)
            assertTrue(stack.rateLimitLog.report().contains("HTTP 429"))

            // The next request to the same host must respect Retry-After instead of immediately retrying.
            var pacedNowMs = 10_000L
            val paced = buildTestStack(
                pacerClockMs = { pacedNowMs },
                pacerDelayFn = { ms -> pacedNowMs += ms },
            )
            val pacedBase = "http://apiq.iwara.tv:${server.port}"
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3"))
            paced.client.execute(SiteHttpRequest(url = "$pacedBase/limited", siteId = SiteId.IWARA, kind = SiteRequestKind.API))
            assertEquals(10_000L, pacedNowMs)
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            paced.client.execute(SiteHttpRequest(url = "$pacedBase/after-limit", siteId = SiteId.IWARA, kind = SiteRequestKind.API))
            assertEquals(13_000L, pacedNowMs)

            // 401 -> AuthRequired
            assertTrue(SiteHttpErrorMapper.fromResponse(SiteHttpResponse(401, "$base/a"), SiteId.IWARA) is SiteException.AuthRequired)

            // 403 challenge vs plain
            val cf = SiteHttpErrorMapper.fromResponse(
                SiteHttpResponse(403, "$base/b", headers = mapOf("cf-mitigated" to listOf("challenge"))),
                SiteId.IWARA,
            )
            assertTrue(cf is SiteException.Forbidden && cf.isChallenge)
            val plain403 = SiteHttpErrorMapper.fromResponse(SiteHttpResponse(403, "$base/b"), SiteId.IWARA)
            assertTrue(plain403 is SiteException.Forbidden && !plain403.isChallenge)

            // 404 -> NotFound, 509 -> QuotaExceeded, 500 -> Network
            assertTrue(SiteHttpErrorMapper.fromResponse(SiteHttpResponse(404, "$base/c"), SiteId.IWARA) is SiteException.NotFound)
            assertTrue(SiteHttpErrorMapper.fromResponse(SiteHttpResponse(509, "$base/d"), SiteId.IWARA) is SiteException.QuotaExceeded)
            assertTrue(SiteHttpErrorMapper.fromResponse(SiteHttpResponse(500, "$base/e"), SiteId.IWARA) is SiteException.Network)

            // TLS exception -> SiteException.TlsError
            val tlsMapped = SiteHttpErrorMapper.fromThrowable(SSLHandshakeException("cert failed"), SiteId.IWARA)
            assertTrue(tlsMapped is SiteException.TlsError)

            // Closed port -> SiteException.Network
            val closedServer = MockWebServer()
            closedServer.start()
            val closedPort = closedServer.port
            closedServer.shutdown()
            var netErr: Throwable? = null
            try {
                stack.client.execute(SiteHttpRequest(url = "http://127.0.0.1:$closedPort/unreachable", kind = SiteRequestKind.PROBE))
            } catch (t: Throwable) {
                netErr = t
            }
            assertTrue("Expected SiteException.Network, got $netErr", netErr is SiteException.Network)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `integrates MediaRedirectCache on MEDIA requests through SiteHttpClient`() = runTest {
        val origin = MockWebServer()
        val cdn = MockWebServer()
        origin.start()
        cdn.start()
        try {
            val stack = buildTestStack()
            val cdnUrl = "http://filesq.iwara.tv:${cdn.port}/stream.mp4"
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdnUrl))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("chunk1"))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("chunk2"))

            val mediaUrl = "http://www.iwara.tv:${origin.port}/get-file/1"
            val r1 = stack.client.execute(SiteHttpRequest(url = mediaUrl, siteId = SiteId.IWARA, kind = SiteRequestKind.MEDIA))
            val r2 = stack.client.execute(SiteHttpRequest(url = mediaUrl, siteId = SiteId.IWARA, kind = SiteRequestKind.MEDIA))
            assertEquals("chunk1", r1.bodyText())
            assertEquals("chunk2", r2.bodyText())
            assertEquals(1, origin.requestCount)
            assertEquals(2, cdn.requestCount)
        } finally {
            origin.shutdown()
            cdn.shutdown()
        }
    }

    @Test
    fun `Ipv4FirstDns orders IPv4 ahead of IPv6 stably`() {
        val v6a = InetAddress.getByName("2001:db8::1")
        val v4a = InetAddress.getByName("93.184.216.34")
        val v6b = InetAddress.getByName("2001:db8::2")
        val v4b = InetAddress.getByName("93.184.216.35")

        val ordered = Ipv4FirstDns.order(listOf(v6a, v4a, v6b, v4b))
        assertEquals(listOf(v4a, v4b, v6a, v6b), ordered)
        assertTrue(ordered[0] is Inet4Address)
        assertTrue(ordered[2] is Inet6Address)
    }

    @Test
    fun `SystemProxySelector never falls back to DIRECT and wraps errors as IOException`() {
        val uri = URI("https://rule34video.com/")
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7897))
        val withFallback = object : ProxySelector() {
            override fun select(u: URI): List<Proxy> = listOf(proxy, Proxy.NO_PROXY)
            override fun connectFailed(u: URI, sa: SocketAddress, ioe: IOException) {}
        }
        val selector = SystemProxySelector { withFallback }
        assertEquals(listOf(proxy), selector.select(uri))
        assertTrue(selector.describe(uri).contains("127.0.0.1:7897"))

        // PAC exclusion (DIRECT first) stays DIRECT
        val pacDirect = object : ProxySelector() {
            override fun select(u: URI): List<Proxy> = listOf(Proxy.NO_PROXY, proxy)
            override fun connectFailed(u: URI, sa: SocketAddress, ioe: IOException) {}
        }
        assertEquals(listOf(Proxy.NO_PROXY), SystemProxySelector { pacDirect }.select(uri))

        // Failing OS selector throws IOException instead of silent DIRECT
        val broken = object : ProxySelector() {
            override fun select(u: URI): List<Proxy> = error("boom")
            override fun connectFailed(u: URI, sa: SocketAddress, ioe: IOException) {}
        }
        assertThrows(IOException::class.java) { SystemProxySelector { broken }.select(uri) }
    }

    @Test
    fun `NetworkMonitor increments routeEpoch updates isMetered and changes currentRouteKey`() {
        val stack = buildTestStack()
        val monitor = stack.networkMonitor
        assertEquals(0L, monitor.routeEpoch.value)
        val key0 = monitor.currentRouteKey(listOf("https://www.iwara.tv"))

        monitor.onDefaultNetworkAvailable(networkHandle = 1001L, metered = true)
        assertEquals(1L, monitor.routeEpoch.value)
        assertTrue(monitor.isMetered.value)
        assertTrue(monitor.isConnected.value)
        val key1 = monitor.currentRouteKey(listOf("https://www.iwara.tv"))
        assertNotEquals(key0, key1)

        monitor.onCapabilitiesChanged(networkHandle = 1001L, metered = false)
        assertFalse(monitor.isMetered.value)
        assertEquals(1L, monitor.routeEpoch.value)

        monitor.onProxyChanged()
        assertEquals(2L, monitor.routeEpoch.value)

        monitor.onDefaultNetworkLost(networkHandle = 1001L)
        assertEquals(3L, monitor.routeEpoch.value)
        assertFalse(monitor.isConnected.value)
    }

    @Test
    fun `RavenImageExtras partitions cache keys by SiteId and NetworkDiagnostics reports live probe`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val stack = buildTestStack()
            val url = "https://ehgt.org/t/01/cover.jpg"
            val keyEh = RavenImageExtras.partitionCacheKey(url, SiteId.EHENTAI, stack.registry)
            val keyEx = RavenImageExtras.partitionCacheKey(url, SiteId.EXHENTAI, stack.registry)
            assertEquals("ehentai|$url", keyEh)
            assertEquals("exhentai|$url", keyEx)
            assertNotEquals(keyEh, keyEx)

            // Auto-resolves via SiteRegistry mediaDomains when explicitSiteId is null
            val keyIwara = RavenImageExtras.partitionCacheKey("https://i.iwara.tv/image/1.jpg", null, stack.registry)
            assertEquals("iwara|https://i.iwara.tv/image/1.jpg", keyIwara)

            // Live NetworkDiagnostics probe
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val report = stack.diagnostics.diagnose(
                siteId = SiteId.IWARA,
                probeUrl = "http://www.iwara.tv:${server.port}/",
            )
            assertTrue(report.reachable)
            assertEquals(200, report.httpStatus)
            assertTrue(report.toDisplayText().contains("实时连接正常"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `RavenOkHttpPool clientFor honors all flag combinations accurately`() {
        val stack = buildTestStack()
        val mediaNoCookieNoRedir = stack.pool.clientFor(
            SiteHttpRequest(url = "https://x.test/v.mp4", kind = SiteRequestKind.MEDIA, followRedirects = false, allowCookies = false),
        )
        assertFalse(mediaNoCookieNoRedir.followRedirects)
        assertSame(CookieJar.NO_COOKIES, mediaNoCookieNoRedir.cookieJar)

        val imageNoCookieNoRedir = stack.pool.clientFor(
            SiteHttpRequest(url = "https://x.test/1.jpg", kind = SiteRequestKind.IMAGE, followRedirects = false, allowCookies = false),
        )
        assertFalse(imageNoCookieNoRedir.followRedirects)
        assertSame(CookieJar.NO_COOKIES, imageNoCookieNoRedir.cookieJar)

        val apiNoCookieNoRetry = stack.pool.clientFor(
            SiteHttpRequest(url = "https://x.test/api", kind = SiteRequestKind.API, allowCookies = false, allowRetry = false),
        )
        assertSame(CookieJar.NO_COOKIES, apiNoCookieNoRetry.cookieJar)
        assertFalse(apiNoCookieNoRetry.retryOnConnectionFailure)

        val probeFollow = stack.pool.clientFor(
            SiteHttpRequest(url = "https://x.test/", kind = SiteRequestKind.PROBE, followRedirects = true),
        )
        assertTrue(probeFollow.followRedirects)
    }
}
