package com.raven.app.core.network

import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.security.InMemoryVaultStorage
import com.raven.app.core.security.SessionVault
import com.raven.app.core.security.SoftwareAesGcmVaultCipher
import com.raven.app.core.sitespi.ManwaImageAesProtocol
import com.raven.app.core.sitespi.MediaTransportHook
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class SiteImageTransportUnwrapTest {

    @Test
    fun `imageClient transparently unwraps Manwa2 AES-CBC encrypted images and keeps plain images intact`() {
        val server = MockWebServer()
        server.start()
        try {
            val plainWebp = "RIFF\u0020\u0000\u0000\u0000WEBPVP8 manwa-transparent-test".toByteArray(Charsets.ISO_8859_1)
            val encryptedPayload = ManwaImageAesProtocol.encryptForTest(plainWebp)
            val plainJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(encryptedPayload)),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody(Buffer().write(plainJpeg)),
            )

            val host = "manwauu.cc"
            val cdnHost = "cdn.manwa-img.example"
            val plugin = object : SitePlugin {
                override val descriptor: SiteDescriptor = SiteDescriptor(
                    id = SiteId.MANWA2,
                    displayName = "漫蛙2",
                    primaryKind = MediaKind.COMIC,
                    defaultHost = host,
                    networkProfile = SiteNetworkProfile(
                        mediaReferer = "https://$host/",
                        mediaDomains = setOf(host, cdnHost),
                    ),
                )

                override val mediaTransportHook: MediaTransportHook = ManwaImageAesProtocol.createTransportHook(
                    keyProvider = { ManwaImageAesProtocol.DEFAULT_AES_KEY },
                    urlMatcher = { url -> url.contains("/en_images/") },
                )
            }
            val registry = SiteRegistry(listOf(plugin))
            val vault = SessionVault(SoftwareAesGcmVaultCipher(), InMemoryVaultStorage())
            val connectionPool = ConnectionPool(4, 1, TimeUnit.MINUTES)
            val dispatcher = Dispatcher()
            val localDns = Ipv4FirstDns(
                delegate = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        if (hostname == host || hostname == cdnHost) {
                            listOf(InetAddress.getLoopbackAddress())
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                },
            )
            val pool = RavenOkHttpPool(
                cacheDir = null,
                connectionPool = connectionPool,
                dispatcher = dispatcher,
                dns = localDns,
                proxySelector = SystemProxySelector { null },
                cookieJar = SiteCookieJar(vault, registry),
                mediaRedirectCache = MediaRedirectCache(eligible = { false }, rateLimitLog = RateLimitLog()),
                peerTracker = PeerTracker(),
                siteRegistryProvider = { registry },
            )

            val encReq = Request.Builder()
                .url("http://$cdnHost:${server.port}/en_images/10/1.webp")
                .build()
            pool.imageClient.newCall(encReq).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("image/webp", resp.header("Content-Type"))
                assertArrayEquals(plainWebp, resp.body!!.bytes())
            }

            val plainReq = Request.Builder()
                .url("http://$cdnHost:${server.port}/static/cover.jpg")
                .build()
            pool.imageClient.newCall(plainReq).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("image/jpeg", resp.header("Content-Type"))
                assertArrayEquals(plainJpeg, resp.body!!.bytes())
            }
        } finally {
            server.shutdown()
        }
    }
}
