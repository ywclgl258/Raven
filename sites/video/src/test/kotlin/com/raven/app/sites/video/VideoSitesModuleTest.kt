package com.raven.app.sites.video

import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.MirrorLeaseRouter
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.RouPngProtocol
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class VideoSitesModuleTest {

    @Test
    fun `videoSitesModule registers all 8 video SitePlugins cleanly in Koin and SiteRegistry`() {
        val fakeHttp = FakeHttpClientAdapter()
        val sessionStore = InMemorySiteSessionStore()
        val app = koinApplication {
            modules(
                module {
                    single<HttpClientAdapter> { fakeHttp }
                    single<SiteSessionStore> { sessionStore }
                },
                videoSitesModule,
            )
        }
        try {
            val plugins = app.koin.getAll<SitePlugin>()
            assertEquals(8, plugins.size)
            assertEquals(SiteId.VIDEO_SITES.toSet(), plugins.map { it.id }.toSet())

            val registry = SiteRegistry(plugins)
            assertEquals(8, registry.size)

            // Verify special plugin contracts
            val iwara = registry.require(SiteId.IWARA)
            assertTrue(iwara.descriptor.hasCapability(SiteCapability.DUAL_MEDIA))
            assertEquals(setOf(MediaKind.VIDEO, MediaKind.PHOTO), iwara.descriptor.supportedKinds)
            assertNotNull(iwara.gallerySource)

            val missav = registry.require(SiteId.MISSAV)
            assertTrue(missav.hostRouter is MirrorLeaseRouter)

            val rou = registry.require(SiteId.ROUVIDEO)
            assertTrue(rou.mediaTransportHook === RouPngProtocol.transportHook)

            val kbj = registry.require(SiteId.KBJFREE)
            assertTrue(kbj.descriptor.hasCapability(SiteCapability.PREVIEW_ONLY))

            // Verify cross-site link resolution via SiteRegistry
            val samples = listOf(
                "https://rule34video.com/video/123456/demo/" to SiteId.R34V,
                "https://hanime1.me/watch?v=9988" to SiteId.HANIME1,
                "https://www.iwara.tv/video/abc123/test" to SiteId.IWARA,
                "https://apiq.iwara.tv/images?page=0&sort=likes" to SiteId.IWARA,
                "https://missav.media/cn/dldss-584" to SiteId.MISSAV,
                "https://jable.tv/vodplay/777-1-1/" to SiteId.JABLE,
                "https://rou.video/v/abcdefghijklmnop1234" to SiteId.ROUVIDEO,
                "https://kbjfree.com/watch/123e4567-e89b-12d3-a456-426614174000" to SiteId.KBJFREE,
                "https://pornhub.com/view_video.php?viewkey=ph1234567890" to SiteId.PORNHUB,
            )
            for ((url, expectedSite) in samples) {
                val resolved = registry.resolveLink(url)
                assertNotNull("Expected SiteRegistry to resolve $url", resolved)
                assertEquals("Wrong site for $url", expectedSite, resolved!!.first.id)
            }

            val fromText = registry.resolveLinkFromText("Check this out: https://missav.media/ja/ssni-100!")
            assertNotNull(fromText)
            assertEquals(SiteId.MISSAV, fromText!!.first.id)
            assertTrue(fromText.second is ResolvedLink.Video)
        } finally {
            app.close()
        }
    }
}
