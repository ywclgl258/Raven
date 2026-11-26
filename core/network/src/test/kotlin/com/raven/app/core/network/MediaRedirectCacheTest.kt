package com.raven.app.core.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MediaRedirectCacheTest {

    private fun clientOf(cache: MediaRedirectCache): OkHttpClient = OkHttpClient.Builder()
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == "cdn.example" || hostname == "cdn2.example") {
                        listOf(InetAddress.getLoopbackAddress())
                    } else {
                        Dns.SYSTEM.lookup(hostname)
                    }
            },
        )
        .addInterceptor(cache.interceptor)
        .addNetworkInterceptor(cache.networkInterceptor)
        .build()

    private fun fetchBody(client: OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }

    @Test
    fun `second request hits cached CDN directly without revisiting origin`() {
        val origin = MockWebServer()
        val cdn = MockWebServer()
        origin.start()
        cdn.start()
        try {
            val cdnUrl = "http://cdn.example:${cdn.port}/media.mp4"
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdnUrl))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("first"))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("second"))

            val cache = MediaRedirectCache(eligible = { true })
            val client = clientOf(cache)
            val originUrl = origin.url("/get-file/100").toString()

            assertEquals("first", fetchBody(client, originUrl))
            assertEquals("second", fetchBody(client, originUrl))
            assertEquals(1, origin.requestCount)
            assertEquals(2, cdn.requestCount)
            assertEquals(cdnUrl, cache.getCachedRedirect(originUrl)?.toString())
        } finally {
            origin.shutdown()
            cdn.shutdown()
        }
    }

    @Test
    fun `invalidates cached redirect on 403 404 and 410 and re-resolves transparently within the same call`() {
        for (rejectCode in listOf(403, 404, 410)) {
            val origin = MockWebServer()
            val cdn = MockWebServer()
            origin.start()
            cdn.start()
            try {
                val cdnUrl = "http://cdn.example:${cdn.port}/signed-$rejectCode"
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdnUrl))
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$cdnUrl-fresh"))
                cdn.enqueue(MockResponse().setResponseCode(200).setBody("initial"))
                cdn.enqueue(MockResponse().setResponseCode(rejectCode))
                cdn.enqueue(MockResponse().setResponseCode(200).setBody("recovered-$rejectCode"))
                cdn.enqueue(MockResponse().setResponseCode(200).setBody("cached-fresh"))

                val log = RateLimitLog()
                val cache = MediaRedirectCache(eligible = { true }, rateLimitLog = log)
                val client = clientOf(cache)
                val originUrl = origin.url("/get-file/$rejectCode").toString()

                assertEquals("initial", fetchBody(client, originUrl))
                assertEquals("recovered-$rejectCode", fetchBody(client, originUrl))
                assertEquals("cached-fresh", fetchBody(client, originUrl))

                assertEquals(2, origin.requestCount)
                assertEquals(4, cdn.requestCount)
                assertTrue(log.report().contains("CDN HTTP $rejectCode"))
            } finally {
                origin.shutdown()
                cdn.shutdown()
            }
        }
    }

    @Test
    fun `expires entries after 30-minute TTL and evicts LRU entries beyond maxEntries`() {
        val origin = MockWebServer()
        val cdn = MockWebServer()
        origin.start()
        cdn.start()
        try {
            var now = 1_000_000L
            val cache = MediaRedirectCache(
                ttlMs = MediaRedirectCache.DEFAULT_TTL_MS,
                maxEntries = 2,
                clockMs = { now },
                eligible = { true },
            )
            val client = clientOf(cache)

            val u1 = origin.url("/get-file/1").toString()
            val u2 = origin.url("/get-file/2").toString()
            val u3 = origin.url("/get-file/3").toString()

            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.example:${cdn.port}/m1"))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("body-1"))
            assertEquals("body-1", fetchBody(client, u1))

            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.example:${cdn.port}/m2"))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("body-2"))
            assertEquals("body-2", fetchBody(client, u2))

            // Touch u1 directly on CDN (cache hit) so u2 becomes LRU
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("body-1-hit"))
            assertEquals("body-1-hit", fetchBody(client, u1))

            // Adding u3 evicts LRU entry u2
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.example:${cdn.port}/m3"))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("body-3"))
            assertEquals("body-3", fetchBody(client, u3))
            assertNull(cache.getCachedRedirect(u2))
            assertEquals(2, cache.size())
            assertEquals(3, origin.requestCount)
            assertEquals(4, cdn.requestCount)

            // Advance past 30-minute TTL -> entries expire
            now += MediaRedirectCache.DEFAULT_TTL_MS + 1L
            assertNull(cache.getCachedRedirect(u1))
            assertNull(cache.getCachedRedirect(u3))
            assertEquals(0, cache.size())
        } finally {
            origin.shutdown()
            cdn.shutdown()
        }
    }

    @Test
    fun `non-redirecting responses and ineligible hosts never enter cache`() {
        val origin = MockWebServer()
        origin.start()
        try {
            origin.enqueue(MockResponse().setResponseCode(200).setBody("direct1"))
            origin.enqueue(MockResponse().setResponseCode(200).setBody("direct2"))

            val cache = MediaRedirectCache(eligible = { true })
            val client = clientOf(cache)
            val url = origin.url("/video.mp4").toString()

            assertEquals("direct1", fetchBody(client, url))
            assertEquals("direct2", fetchBody(client, url))
            assertEquals(2, origin.requestCount)
            assertEquals(0, cache.size())
        } finally {
            origin.shutdown()
        }
    }

    @Test
    fun `strips Authorization and Cookie headers when cache hit rewrites cross-host to CDN`() {
        val origin = MockWebServer()
        val cdn = MockWebServer()
        origin.start()
        cdn.start()
        try {
            val cdnUrl = "http://cdn.example:${cdn.port}/video.mp4"
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdnUrl))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("first"))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("hit"))

            val cache = MediaRedirectCache(eligible = { true })
            val client = clientOf(cache)
            val originUrl = origin.url("/get-file/secret").toString()

            val req1 = Request.Builder()
                .url(originUrl)
                .header("Cookie", "session=topsecret")
                .build()
            client.newCall(req1).execute().use { assertEquals("first", it.body!!.string()) }

            val req2 = Request.Builder()
                .url(originUrl)
                .header("Authorization", "Bearer topsecret")
                .header("Cookie", "session=topsecret")
                .build()
            client.newCall(req2).execute().use { assertEquals("hit", it.body!!.string()) }

            cdn.takeRequest()
            val hitReq = cdn.takeRequest()
            assertNull("Authorization must be stripped on cross-host cache hit", hitReq.getHeader("Authorization"))
            assertNull("Cookie must be stripped on cross-host cache hit", hitReq.getHeader("Cookie"))
        } finally {
            origin.shutdown()
            cdn.shutdown()
        }
    }
}
