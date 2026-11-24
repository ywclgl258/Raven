package com.raven.app.core.sitespi

import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteRegistryAndHttpTest {

    private fun fakePlugin(
        descriptor: SiteDescriptor,
        resolver: LinkResolver? = null,
    ): SitePlugin = object : SitePlugin {
        override val descriptor: SiteDescriptor = descriptor
        override val linkResolver: LinkResolver? = resolver
    }

    @Test
    fun `registry indexes plugins by id kind capability host and link`() {
        val iwara = fakePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.IWARA,
                displayName = "Iwara",
                primaryKind = MediaKind.VIDEO,
                supportedKinds = setOf(MediaKind.VIDEO, MediaKind.PHOTO),
                defaultHost = "www.iwara.tv",
                capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH, SiteCapability.DUAL_MEDIA),
                networkProfile = SiteNetworkProfile(
                    apiBase = "https://apiq.iwara.tv",
                    apiHosts = setOf("apiq.iwara.tv", "api.iwara.tv"),
                    mediaDomains = setOf("iwara.tv"),
                ),
            ),
            resolver = object : LinkResolver {
                override fun canHandle(url: String): Boolean = url.contains("iwara.tv/video/")
                override fun canonicalize(url: String): String = url.substringBefore('?')
                override fun resolve(url: String): ResolvedLink =
                    ResolvedLink.Video(SiteId.IWARA, "vid42", canonicalize(url))
            },
        )
        val nhentai = fakePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.NHENTAI,
                displayName = "nhentai",
                primaryKind = MediaKind.COMIC,
                defaultHost = "nhentai.net",
                capabilities = setOf(SiteCapability.FEED, SiteCapability.LANGUAGE_FILTER),
            ),
        )

        val registry = SiteRegistry(listOf(iwara, nhentai))
        assertEquals(2, registry.size)
        assertEquals(iwara, registry.get(SiteId.IWARA))
        assertEquals(listOf(iwara), registry.byPrimaryKind(MediaKind.VIDEO))
        assertEquals(listOf(iwara), registry.supportingKind(MediaKind.PHOTO))
        assertEquals(listOf(nhentai), registry.withCapability(SiteCapability.LANGUAGE_FILTER))

        assertTrue(registry.isRegisteredSiteHost("iwara.tv"))
        assertFalse(registry.isRegisteredSiteHost("apiq.iwara.tv"))
        assertEquals(iwara, registry.findForRequestHost("apiq.iwara.tv"))
        assertEquals(iwara, registry.findForMediaHost("filesq.iwara.tv"))
        assertNull(registry.findForMediaHost("cdn.unknown.example"))

        val resolved = registry.resolveLinkFromText("Check this: https://www.iwara.tv/video/vid42?ref=1。")
        assertNotNull(resolved)
        assertEquals(SiteId.IWARA, resolved!!.first.id)
        val videoLink = resolved.second as ResolvedLink.Video
        assertEquals("vid42", videoLink.id)
        assertEquals("https://www.iwara.tv/video/vid42", videoLink.canonicalUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `registry rejects duplicate site ids`() {
        val desc = SiteDescriptor(id = SiteId.R34V, displayName = "Rule34Video", primaryKind = MediaKind.VIDEO, defaultHost = "rule34video.com")
        SiteRegistry(listOf(fakePlugin(desc), fakePlugin(desc)))
    }

    @Test(expected = SiteException.Unsupported::class)
    fun `registry require throws unsupported for retired sites`() {
        val registry = SiteRegistry(emptyList())
        registry.require(SiteId.RETIRED_KBJ)
    }

    @Test
    fun `http client adapter helpers build requests and parse response headers`() = runTest {
        val captured = mutableListOf<SiteHttpRequest>()
        val client = object : HttpClientAdapter {
            override suspend fun execute(request: SiteHttpRequest): SiteHttpResponse {
                captured.add(request)
                return SiteHttpResponse(
                    code = 429,
                    finalUrl = request.url,
                    headers = mapOf("retry-after" to listOf("45"), "Content-Range" to listOf("bytes 0-47/480")),
                    body = "too many requests".toByteArray(),
                )
            }
        }

        val resp = client.postForm(
            url = "https://rule34video.com/login/",
            fields = mapOf("username" to "alice", "action" to "login"),
            siteId = SiteId.R34V,
        )
        assertEquals(1, captured.size)
        assertEquals(SiteHttpMethod.POST, captured[0].method)
        assertFalse(captured[0].allowRetry)
        assertFalse(captured[0].allowCache)
        assertEquals("username=alice&action=login", String(captured[0].body!!, Charsets.UTF_8))
        assertEquals(45L, resp.retryAfterSeconds)
        assertEquals("bytes 0-47/480", resp.contentRange)
        assertEquals("too many requests", resp.bodyText())
        assertFalse(resp.isSuccessful)
    }
}
