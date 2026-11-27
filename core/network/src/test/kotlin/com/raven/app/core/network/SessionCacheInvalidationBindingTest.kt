package com.raven.app.core.network

import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.security.InMemoryVaultStorage
import com.raven.app.core.security.SessionVault
import com.raven.app.core.security.SoftwareAesGcmVaultCipher
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SessionCacheInvalidationBindingTest {

    @Test
    fun `vault epoch change invalidates MediaRedirectCache and evicts OkHttp HTTP cache`() {
        val storage = InMemoryVaultStorage()
        val vault = SessionVault(SoftwareAesGcmVaultCipher(), storage)
        val tempCacheDir = Files.createTempDirectory("raven-http-cache-test").toFile()
        val origin = MockWebServer()
        val cdn = MockWebServer()
        origin.start()
        cdn.start()

        try {
            val registry = SiteRegistry(
                listOf(
                    object : SitePlugin {
                        override val descriptor: SiteDescriptor = SiteDescriptor(
                            id = SiteId.R34V,
                            displayName = "R34V",
                            primaryKind = MediaKind.VIDEO,
                            defaultHost = "rule34video.com",
                            networkProfile = SiteNetworkProfile(mediaDomains = setOf("cdn.example")),
                        )
                        override val authProvider: AuthProvider = object : AuthProvider {
                            override val mode: AuthMode = AuthMode.FormCredentials
                        }
                    },
                ),
            )
            val rateLimitLog = RateLimitLog()
            val mediaRedirectCache = MediaRedirectCache(
                eligible = { true },
                rateLimitLog = rateLimitLog,
                accountResolver = { SiteId.R34V },
                credentialEpochProvider = { vault.authEpoch(it) },
            )
            val connectionPool = ConnectionPool(4, 5, TimeUnit.MINUTES)
            val dispatcher = Dispatcher()
            val proxySelector = SystemProxySelector { null }
            val pool = RavenOkHttpPool(
                cacheDir = tempCacheDir,
                connectionPool = connectionPool,
                dispatcher = dispatcher,
                dns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getLoopbackAddress())
                },
                proxySelector = proxySelector,
                cookieJar = SiteCookieJar(vault, registry),
                mediaRedirectCache = mediaRedirectCache,
                peerTracker = PeerTracker(),
                siteRegistryProvider = { registry },
                sessionStoreProvider = { vault },
            )
            // Construct binding to verify its constructor attaches the vault listener.
            SessionCacheInvalidationBinding(
                sessionVault = vault,
                mediaRedirectCache = mediaRedirectCache,
                okHttpPool = pool,
            )

            // Populate MediaRedirectCache under current epoch.
            val mediaOriginUrl = origin.url("/get-file/1").toString()
            val mediaClient = OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getLoopbackAddress())
                })
                .addInterceptor(mediaRedirectCache.interceptor)
                .addNetworkInterceptor(mediaRedirectCache.networkInterceptor)
                .build()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.example:${cdn.port}/v.mp4"))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("video-bytes"))
            mediaClient.newCall(Request.Builder().url(mediaOriginUrl).build()).execute().use {
                assertEquals("video-bytes", it.body!!.string())
            }
            assertNotNull(mediaRedirectCache.getCachedRedirect(mediaOriginUrl))

            // Populate RavenOkHttpPool HTTP cache on pageClient.
            val cachedPageUrl = origin.url("/cached-page").toString()
            origin.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Cache-Control", "public, max-age=3600")
                    .setBody("cached-v1"),
            )
            origin.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Cache-Control", "public, max-age=3600")
                    .setBody("fresh-v2"),
            )
            pool.pageClient.newCall(Request.Builder().url(cachedPageUrl).build()).execute().use {
                assertEquals("cached-v1", it.body!!.string())
            }
            pool.pageClient.newCall(Request.Builder().url(cachedPageUrl).build()).execute().use {
                assertEquals("cached-v1", it.body!!.string())
                assertNotNull("Expected HTTP cache hit before epoch change", it.cacheResponse)
            }

            // Mutate Vault credentials -> binding drops MediaRedirectCache and evicts HTTP cache.
            vault.saveBearerToken(SiteId.R34V, "token-new", "alice")

            assertNull(mediaRedirectCache.getCachedRedirect(mediaOriginUrl))
            pool.pageClient.newCall(Request.Builder().url(cachedPageUrl).build()).execute().use {
                assertNull("HTTP cache must be evicted after vault epoch move", it.cacheResponse)
                assertEquals("fresh-v2", it.body!!.string())
            }
        } finally {
            origin.shutdown()
            cdn.shutdown()
            tempCacheDir.deleteRecursively()
        }
    }
}
