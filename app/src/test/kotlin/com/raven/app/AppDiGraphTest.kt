package com.raven.app

import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.network.NetworkQualifiers
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteSessionStore
import com.raven.app.feature.settings.RavenAppInfo
import com.raven.app.sites.comic.comicSitesModule
import com.raven.app.sites.gallery.gallerySitesModule
import com.raven.app.sites.video.videoSitesModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class AppDiGraphTest {

    private val stubCoreSpiModule = module {
        single<HttpClientAdapter> {
            object : HttpClientAdapter {
                override suspend fun execute(request: SiteHttpRequest): SiteHttpResponse =
                    SiteHttpResponse(
                        code = 200,
                        finalUrl = request.url,
                        body = ByteArray(0),
                    )
            }
        }
        single<SiteSessionStore> { InMemorySiteSessionStore() }
    }

    private fun buildSiteKoinRegistry(): SiteRegistry {
        val koinApp = koinApplication {
            modules(
                stubCoreSpiModule,
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
    fun allRavenKoinModulesRegistersExact13ModulesWithoutConflicts() {
        assertEquals(13, allRavenKoinModules.size)
        val koinApp = koinApplication {
            modules(allRavenKoinModules)
        }
        try {
            val appInfo = koinApp.koin.get<RavenAppInfo>()
            assertEquals(BuildConfig.VERSION_NAME, appInfo.versionName)
            assertEquals(BuildConfig.VERSION_CODE.toLong(), appInfo.versionCode)
            assertEquals(BuildConfig.APPLICATION_ID, appInfo.packageName)

            val appScope = koinApp.koin.get<CoroutineScope>(NetworkQualifiers.APPLICATION_SCOPE)
            assertNotNull(appScope)
        } finally {
            koinApp.close()
        }
    }

    @Test
    fun koinGraphResolvesAll18LiveSitesInExactAllLiveOrderWithZeroDuplicates() {
        val registry = buildSiteKoinRegistry()
        val orderedIds = registry.allPlugins().map { it.id }

        assertEquals(18, registry.size)
        assertEquals(SiteId.ALL_LIVE, orderedIds)
        assertEquals(orderedIds.size, orderedIds.toSet().size)

        assertEquals(
            SiteId.VIDEO_SITES,
            registry.byPrimaryKind(MediaKind.VIDEO).map { it.id },
        )
        assertEquals(
            SiteId.COMIC_SITES,
            registry.byPrimaryKind(MediaKind.COMIC).map { it.id },
        )
        assertEquals(
            SiteId.PHOTO_SITES,
            registry.byPrimaryKind(MediaKind.PHOTO).map { it.id },
        )
    }

    @Test
    fun createOrderedSiteRegistrySortsShuffledPluginsAndRejectsMissingOrDuplicate() {
        val fullRegistry = buildSiteKoinRegistry()
        val reversedPlugins: List<SitePlugin> = fullRegistry.allPlugins().reversed()

        val sortedRegistry = createOrderedSiteRegistry(reversedPlugins)
        assertEquals(SiteId.ALL_LIVE, sortedRegistry.allPlugins().map { it.id })

        try {
            createOrderedSiteRegistry(reversedPlugins.drop(1))
            fail("Expected IllegalStateException when a live site plugin is missing")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Missing live SitePlugin"))
        }

        try {
            createOrderedSiteRegistry(reversedPlugins + reversedPlugins.first())
            fail("Expected IllegalArgumentException on duplicate SitePlugin")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Duplicate SitePlugin"))
        }
    }

    @Test
    fun siteRegistryResolvesLinksAcrossVideoComicAndGallerySites() {
        val registry = buildSiteKoinRegistry()

        // Video sites
        val r34v = registry.resolveLinkFromText("Watch https://www.rule34video.com/video/12345/demo/ now")
        assertNotNull(r34v)
        assertEquals(SiteId.R34V, r34v!!.first.id)
        assertTrue(r34v.second is ResolvedLink.Video)

        val hanime = registry.resolveLinkFromText("Share https://hanime1.me/watch?v=4242")
        assertNotNull(hanime)
        assertEquals(SiteId.HANIME1, hanime!!.first.id)
        assertTrue(hanime.second is ResolvedLink.Video)

        val missav = registry.resolveLink("https://missav.media/ja/abp-123?foo=bar")
        assertNotNull(missav)
        assertEquals(SiteId.MISSAV, missav!!.first.id)
        assertTrue(missav.second is ResolvedLink.Video)

        val jable = registry.resolveLink("https://jable.vip/vodplay/555-1-1/")
        assertNotNull(jable)
        assertEquals(SiteId.JABLE, jable!!.first.id)
        assertTrue(jable.second is ResolvedLink.Video)

        // Comic sites
        val ehentai = registry.resolveLinkFromText("Look at https://e-hentai.org/g/101/abcd1234/")
        assertNotNull(ehentai)
        assertEquals(SiteId.EHENTAI, ehentai!!.first.id)
        assertTrue(ehentai.second is ResolvedLink.Gallery)

        val exhentai = registry.resolveLinkFromText("Secret https://exhentai.org/g/202/abcd1234/")
        assertNotNull(exhentai)
        assertEquals(SiteId.EXHENTAI, exhentai!!.first.id)
        assertTrue(exhentai.second is ResolvedLink.Gallery)

        val nhentai = registry.resolveLinkFromText("Read https://nhentai.net/g/177013/5/")
        assertNotNull(nhentai)
        assertEquals(SiteId.NHENTAI, nhentai!!.first.id)
        val nhentaiLink = nhentai.second as ResolvedLink.Gallery
        assertEquals(5, nhentaiLink.pageIndex)

        val hitomi = registry.resolveLinkFromText("View https://hitomi.la/reader/123456.html#7")
        assertNotNull(hitomi)
        assertEquals(SiteId.HITOMI, hitomi!!.first.id)
        assertTrue(hitomi.second is ResolvedLink.Gallery)

        val wnacg = registry.resolveLinkFromText("Open https://www.wnacg.com/photos-index-aid-505.html")
        assertNotNull(wnacg)
        assertEquals(SiteId.WNACG, wnacg!!.first.id)
        assertTrue(wnacg.second is ResolvedLink.Gallery)

        val jmcomic = registry.resolveLinkFromText("Chapters https://18comic.ink/album/606#chapters")
        assertNotNull(jmcomic)
        assertEquals(SiteId.JMCOMIC, jmcomic!!.first.id)
        assertTrue(jmcomic.second is ResolvedLink.Feed)

        // Photo / Gallery sites
        val fourKhd = registry.resolveLinkFromText(
            "Photo set https://4khd.com/content/04/sample-set-one.html/2#4-2-0",
        )
        assertNotNull(fourKhd)
        assertEquals(SiteId.FOURKHD, fourKhd!!.first.id)
        assertTrue(fourKhd.second is ResolvedLink.Gallery)

        // Unknown external domain must not resolve
        assertNull(registry.resolveLinkFromText("https://example.invalid/watch?v=1"))
    }

    @Test
    fun all25RavenRoutesRoundTripThroughKotlinxSerialization() {
        val routes: List<RavenRoute> = listOf(
            RavenRoute.Home,
            RavenRoute.Explore,
            RavenRoute.Search(initialQuery = "cyberpunk", crossSite = true),
            RavenRoute.Library,
            RavenRoute.Profile,
            RavenRoute.SiteFeed(
                siteId = SiteId.R34V.value,
                section = "popular",
                category = "3d",
                query = "mecha",
                scope = SearchScope.TAG.name,
                title = "Mecha",
            ),
            RavenRoute.SiteDirectory(siteId = SiteId.R34V.value, section = "channels"),
            RavenRoute.SiteFunctions(siteId = SiteId.EHENTAI.value),
            RavenRoute.GalleryDetail(
                siteId = SiteId.NHENTAI.value,
                mediaId = "177013",
                url = "https://nhentai.net/g/177013/",
            ),
            RavenRoute.Reader(
                siteId = SiteId.NHENTAI.value,
                mediaId = "177013",
                url = "https://nhentai.net/g/177013/",
                startPage = 5,
                offlineOnly = true,
                queueToken = "queue-uuid-01",
            ),
            RavenRoute.Downloads(siteId = SiteId.NHENTAI.value),
            RavenRoute.ShelfDetail(shelfId = "watch-later"),
            RavenRoute.SmartShelfDetail(smartShelfId = "unfinished"),
            RavenRoute.Favorites(siteId = SiteId.R34V.value),
            RavenRoute.History,
            RavenRoute.ImageFavorites,
            RavenRoute.Subscriptions(siteId = SiteId.IWARA.value),
            RavenRoute.GlobalInbox,
            RavenRoute.LocalLibrary,
            RavenRoute.Stats,
            RavenRoute.Settings,
            RavenRoute.SiteAccount(siteId = SiteId.EXHENTAI.value),
            RavenRoute.BlockedTerms(siteId = SiteId.R34V.value),
            RavenRoute.TagDictionary,
            RavenRoute.BackupRestore,
            RavenRoute.StorageMigration,
            RavenRoute.DuplicateScanner,
        )

        assertEquals(27, routes.size)
        val json = Json { encodeDefaults = true }

        routes.forEach { original ->
            val encoded = json.encodeToString(RavenRoute.serializer(), original)
            val decoded = json.decodeFromString(RavenRoute.serializer(), encoded)
            assertEquals("Round-trip mismatch for $original", original, decoded)

            val contractEncoded = MainActivityIntentContract.encodeRoute(original)
            val contractDecoded = MainActivityIntentContract.decodeRoute(contractEncoded)
            assertEquals("Intent contract mismatch for $original", original, contractDecoded)
        }
        assertNull(MainActivityIntentContract.decodeRoute("not-valid-json"))
    }

    @Test
    fun tagAndAuthorFeedRouteResolutionAndPipAspectRatioClampingWorkCleanly() {
        val registry = buildSiteKoinRegistry()

        val tagRoute = MainActivityIntentContract.resolveTagFeedRoute(
            siteRegistry = registry,
            siteId = SiteId.R34V,
            tag = TagRef(slug = "mecha", label = "机甲"),
        )
        assertEquals(SiteId.R34V.value, tagRoute.siteId)
        assertEquals("mecha", tagRoute.query)
        assertEquals(SearchScope.TAG.name, tagRoute.scope)
        assertEquals("机甲", tagRoute.title)

        val authorRoute = MainActivityIntentContract.resolveAuthorFeedRoute(
            siteRegistry = registry,
            siteId = SiteId.IWARA,
            author = AuthorRef(id = "creator-42", name = "Creator 42"),
        )
        assertEquals(SiteId.IWARA.value, authorRoute.siteId)
        assertEquals("creator-42", authorRoute.query)
        assertEquals(SearchScope.AUTHOR.name, authorRoute.scope)
        assertEquals("Creator 42", authorRoute.title)

        // Verify PiP aspect ratio bounds (Android requires roughly 1:2.39 .. 2.39:1)
        val (wideW, wideH) = PlayerActivity.clampPipDimensions(4000 to 500)
        assertTrue(wideW.toFloat() / wideH.toFloat() in 0.42f..2.38f)

        val (tallW, tallH) = PlayerActivity.clampPipDimensions(300 to 2400)
        assertTrue(tallW.toFloat() / tallH.toFloat() in 0.42f..2.38f)

        val (stdW, stdH) = PlayerActivity.clampPipDimensions(1920 to 1080)
        assertEquals(16f / 9f, stdW.toFloat() / stdH.toFloat(), 0.01f)

        val (fallbackW, fallbackH) = PlayerActivity.clampPipDimensions(null)
        assertEquals(16 to 9, fallbackW to fallbackH)
    }
}
