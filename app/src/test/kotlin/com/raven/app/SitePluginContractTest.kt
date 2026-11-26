package com.raven.app

import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteSessionStore
import com.raven.app.sites.comic.comicSitesModule
import com.raven.app.sites.gallery.gallerySitesModule
import com.raven.app.sites.video.videoSitesModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Contract test verifying structural, SPI, capability, auth, and host-isolation invariants
 * across all 24 registered [com.raven.app.core.sitespi.SitePlugin] implementations.
 */
class SitePluginContractTest {

    private fun buildRegistry(): SiteRegistry {
        val koinApp = koinApplication {
            modules(
                module {
                    single<HttpClientAdapter> {
                        object : HttpClientAdapter {
                            override suspend fun execute(request: SiteHttpRequest): SiteHttpResponse =
                                SiteHttpResponse(code = 200, finalUrl = request.url, body = ByteArray(0))
                        }
                    }
                    single<SiteSessionStore> { InMemorySiteSessionStore() }
                },
                videoSitesModule,
                comicSitesModule,
                gallerySitesModule,
                appModule,
            )
        }
        return try {
            koinApp.koin.get<SiteRegistry>()
        } finally {
            koinApp.close()
        }
    }

    @Test
    fun `all 24 registered site plugins satisfy SPI metadata, capability, auth and host contracts`() {
        val registry = buildRegistry()
        val plugins = registry.allPlugins()
        assertEquals(SiteId.ALL_LIVE.size, plugins.size)
        assertEquals(SiteId.ALL_LIVE, plugins.map { it.id })

        plugins.forEach { plugin ->
            val desc = plugin.descriptor
            assertTrue("displayName blank for ${plugin.id}", desc.displayName.isNotBlank())
            assertTrue("shortName blank for ${plugin.id}", desc.shortName.isNotBlank())
            assertTrue("defaultHost blank for ${plugin.id}", desc.defaultHost.isNotBlank())
            assertTrue("defaultHost not matched for ${plugin.id}", desc.matchesHost(desc.defaultHost))
            assertTrue("hostRouter candidates empty for ${plugin.id}", plugin.hostRouter.candidateHosts().isNotEmpty())

            when (desc.primaryKind) {
                MediaKind.VIDEO -> assertNotNull("Missing videoSource for ${plugin.id}", plugin.videoSource)
                MediaKind.COMIC,
                MediaKind.SERIAL_COMIC,
                MediaKind.PHOTO,
                -> assertNotNull("Missing gallerySource for ${plugin.id}", plugin.gallerySource)
            }

            if (desc.hasCapability(SiteCapability.FEED)) {
                assertNotNull("FEED capability without feedSource on ${plugin.id}", plugin.feedSource)
            }
            if (desc.hasCapability(SiteCapability.SEARCH)) {
                assertNotNull("SEARCH capability without searchSource on ${plugin.id}", plugin.searchSource)
            }
            if (desc.hasCapability(SiteCapability.DIRECTORY)) {
                assertNotNull("DIRECTORY capability without directorySource on ${plugin.id}", plugin.directorySource)
            }
            if (desc.hasCapability(SiteCapability.DUAL_MEDIA)) {
                assertNotNull("DUAL_MEDIA without videoSource on ${plugin.id}", plugin.videoSource)
                assertNotNull("DUAL_MEDIA without gallerySource on ${plugin.id}", plugin.gallerySource)
            }
            if (desc.hasCapability(SiteCapability.AUTH) || plugin.authProvider.requiresAuthGate) {
                assertTrue(
                    "Auth-capable site ${plugin.id} must declare non-None AuthMode",
                    plugin.authProvider.mode != AuthMode.None,
                )
            }

            plugin.linkResolver?.let { resolver ->
                val foreignUrl = "https://unrelated-domain.invalid/item/123"
                assertFalse("Resolver for ${plugin.id} accepted foreign URL", resolver.canHandle(foreignUrl))
                assertNull("Resolver for ${plugin.id} resolved foreign URL", resolver.resolve(foreignUrl))
            }
        }
    }
}
