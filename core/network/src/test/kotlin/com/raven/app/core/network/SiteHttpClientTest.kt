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
import com.raven.app.core.sitespi.RequestSessionScope
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse
import com.raven.app.core.sitespi.SiteAuthCancelledException
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotNull
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
            sessionStoreProvider = { vault },
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
    fun `external clients follow redirects without reading or writing site credentials`() {
        val stack = buildTestStack()
        val cookies = mapOf("session" to "before")
        stack.vault.replaceCookies(SiteId.IWARA, cookies)
        stack.vault.saveBearerToken(SiteId.IWARA, "account-token")
        MockWebServer().use { server ->
            server.start()
            val initial = server.url("/latest").newBuilder().host("api.github.com").build()
            val redirected = initial.newBuilder().host("www.iwara.tv").build()
            for (client in listOf(stack.pool.externalApiClient, stack.pool.externalDownloadClient)) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirected.toString()))
                server.enqueue(MockResponse().setHeader("Set-Cookie", "session=changed; Path=/"))
                client.newCall(okhttp3.Request.Builder().url(initial).build()).execute().use { assertTrue(it.isSuccessful) }
                repeat(2) {
                    val sent = server.takeRequest()
                    assertNull(sent.getHeader("Cookie"))
                    assertNull(sent.getHeader("Authorization"))
                }
                assertEquals(cookies, stack.vault.getCookies(SiteId.IWARA))
            }
        }
    }

    @Test
    fun `pre-built clients share one transport pool dispatcher DNS and proxy selector`() {
        val stack = buildTestStack()
        val clients = listOf(
            stack.pool.pageClient,
            stack.pool.apiClient,
            stack.pool.imageClient,
            stack.pool.mediaClient,
            stack.pool.probeClient,
            stack.pool.externalApiClient,
            stack.pool.externalDownloadClient,
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
    fun `end-to-end late Set-Cookie after logout cannot restore the session through real OkHttp`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val stack = buildTestStack()
            stack.vault.saveCookies(SiteId.IWARA, mapOf("session" to "logged-in"))

            // The response is released only after logout has fully completed: the classic NET-09 race.
            val logoutDone = java.util.concurrent.CountDownLatch(1)
            val requestSent = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    requestSent.countDown()
                    logoutDone.await(10, TimeUnit.SECONDS)
                    return if (request.path == "/after-logout") {
                        MockResponse().setResponseCode(200).setHeader("Set-Cookie", "session=resurrected; Path=/")
                    } else {
                        MockResponse().setResponseCode(200).setBody("anonymous")
                    }
                }
            }
            val apiBase = "http://www.iwara.tv:${server.port}"

            var lateResponse: SiteHttpResponse? = null
            var lateError: Throwable? = null
            val worker = Thread {
                try {
                    lateResponse = runBlocking {
                        stack.client.execute(
                            SiteHttpRequest(url = "$apiBase/after-logout", siteId = SiteId.IWARA, kind = SiteRequestKind.PAGE),
                        )
                    }
                } catch (t: Throwable) {
                    lateError = t
                }
            }
            worker.start()

            // Wait until the request has left the device: its dispatch epoch is already captured.
            assertTrue("Request never reached the server", requestSent.await(10, TimeUnit.SECONDS))
            stack.vault.clearSite(SiteId.IWARA)
            val epochAfterLogout = stack.vault.authEpoch(SiteId.IWARA)
            logoutDone.countDown()
            worker.join(10_000)
            assertNull("In-flight request should still succeed: $lateError", lateError)
            assertNotNull(lateResponse)

            // Logout stands: neither the plain summary nor the sealed jar regained the session.
            assertTrue("Late Set-Cookie restored the cleared session", stack.vault.getCookies(SiteId.IWARA).isEmpty())
            assertEquals(epochAfterLogout, stack.vault.authEpoch(SiteId.IWARA))

            // A following request goes out unauthenticated and the session stays logged out.
            val next = stack.client.execute(
                SiteHttpRequest(url = "$apiBase/next", siteId = SiteId.IWARA, kind = SiteRequestKind.PAGE),
            )
            assertEquals("anonymous", next.bodyText())
            assertTrue(stack.vault.getCookies(SiteId.IWARA).isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `ISOLATED probe sends only candidate cookies and never persists response Set-Cookie`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val stack = buildTestStack()
            // Live session that must remain untouched by the candidate handshake.
            stack.vault.replaceCookies(SiteId.IWARA, mapOf("session" to "live-account"))
            val epochBefore = stack.vault.authEpoch(SiteId.IWARA)

            val base = "http://www.iwara.tv:${server.port}"
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Set-Cookie", "session=candidate-account; Path=/")
                    .setBody("verify"),
            )

            val response = stack.client.execute(
                SiteHttpRequest(
                    url = "$base/login-candidate",
                    siteId = SiteId.IWARA,
                    kind = SiteRequestKind.PAGE,
                    headers = mapOf("Cookie" to "session=candidate-account"),
                    allowCookies = false,
                    allowRetry = false,
                    sessionScope = RequestSessionScope.ISOLATED,
                ),
            )
            assertEquals("verify", response.bodyText())

            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            // Exactly the candidate cookie — no site jar decoration on top of it.
            assertEquals("session=candidate-account", request.getHeader("Cookie"))
            // The candidate response never reached the vault: live session and epoch intact.
            assertEquals("live-account", stack.vault.getCookies(SiteId.IWARA)["session"])
            assertEquals(epochBefore, stack.vault.authEpoch(SiteId.IWARA))
        } finally {
            server.shutdown()
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
        assertSame(stack.pool.connectionPool, stack.pool.lanClient.connectionPool)
        assertFalse(stack.pool.lanClient.followRedirects)
        assertSame(CookieJar.NO_COOKIES, stack.pool.lanClient.cookieJar)
    }

    @Test
    fun `ISOLATED scope selects a no-cookie client so a candidate handshake never touches the jar`() {
        val stack = buildTestStack()
        for (kind in listOf(SiteRequestKind.PAGE, SiteRequestKind.API, SiteRequestKind.IMAGE, SiteRequestKind.MEDIA)) {
            val isolated = stack.pool.clientFor(
                SiteHttpRequest(
                    url = "https://www.iwara.tv/x",
                    siteId = SiteId.IWARA,
                    kind = kind,
                    allowCookies = true,
                    sessionScope = RequestSessionScope.ISOLATED,
                ),
            )
            assertSame("kind=$kind must bypass the site cookie jar", CookieJar.NO_COOKIES, isolated.cookieJar)
        }
        // SITE scope keeps the jar.
        val normal = stack.pool.clientFor(
            SiteHttpRequest(url = "https://www.iwara.tv/x", siteId = SiteId.IWARA, kind = SiteRequestKind.PAGE),
        )
        assertSame(stack.pool.cookieJar, normal.cookieJar)
    }

    @Test
    fun `interceptor-level Iwara epoch cancellation surfaces as SiteException Cancelled and a safe IOException`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val stack = buildTestStack()
            stack.vault.saveBearerToken(SiteId.IWARA, "jwt", "alice")
            val epochAtDispatch = stack.vault.authEpoch(SiteId.IWARA)

            // Session switches between dispatch and the header-decoration step.
            stack.vault.clearSite(SiteId.IWARA)

            val base = "http://apiq.iwara.tv:${server.port}"
            var caught: Throwable? = null
            try {
                stack.client.execute(
                    SiteHttpRequest(
                        url = "$base/subscriptions/videos",
                        siteId = SiteId.IWARA,
                        kind = SiteRequestKind.API,
                        authEpoch = epochAtDispatch,
                    ),
                )
            } catch (t: Throwable) {
                caught = t
            }
            assertTrue("Expected SiteException.Cancelled, got $caught", caught is SiteException.Cancelled)
            assertEquals(0, server.requestCount)

            // Raw mapping: an interceptor that throws inside the chain must not escape as a
            // non-IOException. SiteAuthCancelledException IS an IOException, and the mapper turns
            // it back into the typed cancellation.
            val raw = SiteAuthCancelledException("Iwara 会话已切换", siteId = SiteId.IWARA)
            assertTrue("SiteAuthCancelledException must be an IOException", raw is IOException)
            val mapped = SiteHttpErrorMapper.fromThrowable(raw, SiteId.IWARA)
            assertTrue(mapped is SiteException.Cancelled)
            assertEquals("Iwara 会话已切换", mapped.message)
            assertEquals(SiteId.IWARA, (mapped as SiteException.Cancelled).siteId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `account-scoped PAGE and API requests never replay the shared HTTP cache across account change`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            var cachedNowMs = 1_700_000_000_000L
            val cacheDir = java.nio.file.Files.createTempDirectory("raven-http-cache").toFile()
            try {
                val stack = buildTestStack()
                // A cache-bearing pool, mirroring production (32 MiB disk cache).
                val cachedPool = RavenOkHttpPool(
                    cacheDir = cacheDir,
                    connectionPool = stack.pool.connectionPool,
                    dispatcher = stack.pool.dispatcher,
                    dns = stack.pool.dns,
                    proxySelector = stack.pool.proxySelector,
                    cookieJar = stack.pool.cookieJar,
                    mediaRedirectCache = stack.pool.mediaRedirectCache,
                    peerTracker = PeerTracker(),
                    siteRegistryProvider = { stack.registry },
                    sessionStoreProvider = { stack.vault },
                )
                val cachedClient = SiteHttpClient(
                    okHttpPool = cachedPool,
                    hostPacer = HostPacer(clockMs = { cachedNowMs }),
                    sessionStore = stack.vault,
                    networkMonitor = stack.networkMonitor,
                    rateLimitLog = stack.rateLimitLog,
                    siteRegistry = stack.registry,
                    dispatchers = CustomAppDispatchers(Dispatchers.IO),
                )

                stack.vault.saveCookies(SiteId.IWARA, mapOf("session" to "account-A"))
                val base = "http://www.iwara.tv:${server.port}"
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Cache-Control", "max-age=600")
                        .setHeader("ETag", "\"a\"")
                        .setBody("body-for-account-A"),
                )
                val epochA = stack.vault.authEpoch(SiteId.IWARA)
                val ra = cachedClient.execute(
                    SiteHttpRequest(
                        url = "$base/subscriptions",
                        siteId = SiteId.IWARA,
                        kind = SiteRequestKind.PAGE,
                        authEpoch = epochA,
                    ),
                )
                assertEquals("body-for-account-A", ra.bodyText())

                // Account switch: logout then login as B.
                stack.vault.clearSite(SiteId.IWARA)
                stack.vault.saveCookies(SiteId.IWARA, mapOf("session" to "account-B"))
                val epochB = stack.vault.authEpoch(SiteId.IWARA)

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Cache-Control", "max-age=600")
                        .setBody("body-for-account-B"),
                )
                val rb = cachedClient.execute(
                    SiteHttpRequest(
                        url = "$base/subscriptions",
                        siteId = SiteId.IWARA,
                        kind = SiteRequestKind.PAGE,
                        authEpoch = epochB,
                    ),
                )
                assertEquals("body-for-account-B", rb.bodyText())
                assertEquals("Second account must hit the network, not the shared cache", 2, server.requestCount)
                cachedNowMs += 1_000L
            } finally {
                cacheDir.deleteRecursively()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `evictHttpCache drops cached bodies so a later unauthenticated request refetches`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val cacheDir = java.nio.file.Files.createTempDirectory("raven-http-cache-evict").toFile()
            try {
                val stack = buildTestStack()
                val pool = RavenOkHttpPool(
                    cacheDir = cacheDir,
                    connectionPool = stack.pool.connectionPool,
                    dispatcher = stack.pool.dispatcher,
                    dns = stack.pool.dns,
                    proxySelector = stack.pool.proxySelector,
                    cookieJar = stack.pool.cookieJar,
                    mediaRedirectCache = stack.pool.mediaRedirectCache,
                    peerTracker = PeerTracker(),
                    siteRegistryProvider = { stack.registry },
                    sessionStoreProvider = { stack.vault },
                )
                val base = "http://www.iwara.tv:${server.port}"
                server.enqueue(
                    MockResponse().setResponseCode(200)
                        .setHeader("Cache-Control", "max-age=600")
                        .setBody("v1"),
                )
                // Anonymously cacheable request (no authEpoch) -> may be stored.
                pool.pageClient.newCall(okhttp3.Request.Builder().url("$base/page").build()).execute().use {
                    assertEquals("v1", it.body!!.string())
                }
                pool.evictHttpCache()
                server.enqueue(MockResponse().setResponseCode(200).setBody("v2"))
                pool.pageClient.newCall(okhttp3.Request.Builder().url("$base/page").build()).execute().use {
                    assertEquals("v2", it.body!!.string())
                }
                assertEquals(2, server.requestCount)
            } finally {
                cacheDir.deleteRecursively()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cross-host 302 redirect strips manually attached Cookie and Authorization while preserving unscoped WebDAV Basic auth`() = runTest {
        val originServer = MockWebServer()
        val foreignCdn = MockWebServer()
        val webdavServer = MockWebServer()
        originServer.start()
        foreignCdn.start()
        webdavServer.start()
        try {
            val stack = buildTestStack(
                serverHostAlias = "apiq.iwara.tv",
                cdnHostAlias = "foreign-cdn.example",
            )
            stack.vault.saveBearerToken(SiteId.IWARA, "iwara-secret-jwt", "alice")

            val cdnTarget = "http://foreign-cdn.example:${foreignCdn.port}/stream.mp4"
            originServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdnTarget))
            foreignCdn.enqueue(MockResponse().setResponseCode(200).setBody("stream-bytes"))

            val siteRes = stack.client.execute(
                SiteHttpRequest(
                    url = "http://apiq.iwara.tv:${originServer.port}/video/1",
                    siteId = SiteId.IWARA,
                    kind = SiteRequestKind.API,
                    headers = mapOf("Cookie" to "manual=secret"),
                ),
            )
            assertEquals(200, siteRes.code)
            val cdnReq = foreignCdn.takeRequest()
            assertNull("Manual Cookie must be stripped on cross-host redirect", cdnReq.getHeader("Cookie"))
            assertNull("Bearer Authorization must be stripped on cross-host redirect", cdnReq.getHeader("Authorization"))

            // Unscoped WebDAV Basic auth on apiClient must remain untouched
            webdavServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val webdavReq = okhttp3.Request.Builder()
                .url(webdavServer.url("/vault/"))
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .build()
            stack.pool.apiClient.newCall(webdavReq).execute().use { assertEquals(200, it.code) }
            assertEquals("Basic dXNlcjpwYXNz", webdavServer.takeRequest().getHeader("Authorization"))
        } finally {
            originServer.shutdown()
            foreignCdn.shutdown()
            webdavServer.shutdown()
        }
    }
}
