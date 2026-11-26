package com.raven.app.sites.gallery

import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.CossoraAesProtocol
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class GallerySitesPluginsTest {

    // ============================================================================================
    // 1. 4KHD (FourKhdSitePlugin)
    // ============================================================================================

    @Test
    fun fourKhdFeedRoutesSortsParsingAndMultiPageGallery() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        fakeHttp.stubHtml("https://www.4khd.com/", FOURKHD_LIST_HTML)
        fakeHttp.stubHtml("https://www.4khd.com/search/sample%20model", FOURKHD_SEARCH_HTML)
        fakeHttp.stubHtml("https://www.4khd.com/content/04/sample-set-one.html", FOURKHD_CONTENT_1_HTML)
        fakeHttp.stubHtml("https://www.4khd.com/content/04/sample-set-one.html/2", FOURKHD_CONTENT_2_HTML)

        val plugin = FourKhdSitePlugin(fakeHttp)
        assertEquals(SiteId.FOURKHD, plugin.id)
        assertEquals(MediaKind.PHOTO, plugin.descriptor.primaryKind)
        assertEquals("https://www.4khd.com/", plugin.descriptor.networkProfile.mediaReferer)
        assertTrue(plugin.descriptor.matchesMediaHost("i0.wp.com"))
        assertTrue(plugin.descriptor.matchesMediaHost("pic.4khd.com"))

        // Route URLs & supported sorts
        assertEquals("https://www.4khd.com/", plugin.engine.resolveFeedUrl(FeedRequest(SiteId.FOURKHD)))
        assertEquals("https://www.4khd.com/?query-3-page=3", plugin.engine.resolveFeedUrl(FeedRequest(SiteId.FOURKHD, page = 3)))
        assertEquals("https://www.4khd.com/pages/popular?query-3-page=2", plugin.engine.resolveFeedUrl(FeedRequest(SiteId.FOURKHD, sort = FeedSort.POPULAR, page = 2)))
        assertEquals("https://www.4khd.com/pages/cosplay?query-3-page=2", plugin.engine.resolveFeedUrl(FeedRequest(SiteId.FOURKHD, category = "cosplay", page = 2)))
        assertEquals("https://www.4khd.com/search/sample%20model", plugin.engine.resolveFeedUrl(FeedRequest(SiteId.FOURKHD, query = "sample model")))
        assertEquals("https://www.4khd.com/search/sample%20model/page/4", plugin.engine.resolveFeedUrl(FeedRequest(SiteId.FOURKHD, query = "sample model", page = 4)))

        assertEquals(listOf(FeedSort.NEWEST, FeedSort.POPULAR), plugin.feedSource.supportedSorts(FeedRequest(SiteId.FOURKHD)))
        assertEquals(listOf(FeedSort.NEWEST), plugin.feedSource.supportedSorts(FeedRequest(SiteId.FOURKHD, query = "sample")))
        assertEquals(listOf(FeedSort.NEWEST), plugin.searchSource.supportedSearchSorts(SearchScope.ALL))
        assertEquals(listOf(FeedSort.NEWEST), plugin.feedSource.supportedSorts(FeedRequest(SiteId.FOURKHD, category = "cosplay")))
        assertEquals(listOf(FeedSort.POPULAR), plugin.feedSource.supportedSorts(FeedRequest(SiteId.FOURKHD, section = "popular")))

        // Listing cards & pager
        val home = plugin.feedSource.loadHome()
        val feed = home.primaryFeedPage!!
        assertEquals(2, feed.items.size)
        assertEquals(411, feed.totalPages)
        assertTrue(feed.hasMore)
        assertEquals(2, feed.nextPage)
        assertTrue(feed.items.none { it.url.contains("attacker") })

        val first = feed.items[0]
        assertEquals("sample-set-one", first.id)
        assertEquals("[JP] Sample Model – Sample One[21MB-31photos]", first.title)
        assertEquals("https://www.4khd.com/content/04/sample-set-one.html", first.url)
        assertEquals(2786L, first.views)
        assertEquals("11/4", first.dateLabel)
        assertEquals(31, first.pageCount)
        assertEquals(850f / 1158f, first.coverAspectRatio!!, 0.001f)

        val second = feed.items[1]
        assertEquals("sample-set-two", second.id)
        assertEquals(1405L, second.views)
        assertEquals("11/2", second.dateLabel)
        assertEquals(114, second.pageCount)

        // Search without pager terminates cleanly
        val searchPage = plugin.searchSource.search(FeedRequest(SiteId.FOURKHD, query = "sample model"))
        assertEquals(1, searchPage.items.size)
        assertEquals("sample-search-hit", searchPage.items.single().id)
        assertFalse(searchPage.hasMore)
        assertNull(searchPage.nextPage)

        // Multi-page gallery detail (3 + 2 = 5 pages, banner ignored)
        val detail = plugin.gallerySource.loadGalleryDetail("https://www.4khd.com/content/04/sample-set-one.html/2")
        assertEquals("sample-set-one", detail.item.id)
        assertEquals("[JP] Sample Model - Sample One[21MB-31photos]", detail.item.title)
        assertEquals(5, detail.pageCount)
        assertEquals(3, detail.previewPageSize)
        assertEquals("https://i0.wp.com/pic.4khd.com/fixture/set-one-001.webp?w=1300", detail.item.coverUrl)
        assertTrue(detail.pages.all { it.imageUrl.contains("pic.4khd.com") })
        assertEquals("https://www.4khd.com/content/04/sample-set-one.html#1-1-0", detail.pages[0].pageUrl)
        assertEquals("https://www.4khd.com/content/04/sample-set-one.html/2#4-2-0", detail.pages[3].pageUrl)
        assertEquals("https://www.4khd.com/content/04/sample-set-one.html/2#5-2-1", detail.pages[4].pageUrl)

        // LinkResolver
        val resolvedGallery = plugin.linkResolver.resolve("https://4khd.com/content/04/sample-set-one.html/2#4-2-0")
        assertEquals(
            ResolvedLink.Gallery(SiteId.FOURKHD, "sample-set-one", "https://www.4khd.com/content/04/sample-set-one.html", pageIndex = 4),
            resolvedGallery,
        )
        val resolvedSearch = plugin.linkResolver.resolve("https://www.4khd.com/search/sample%20model/page/2")
        assertEquals(
            ResolvedLink.Feed(FeedRequest(SiteId.FOURKHD, query = "sample model", page = 2)),
            resolvedSearch,
        )
        val resolvedPop = plugin.linkResolver.resolve("https://www.4khd.com/pages/popular?query-3-page=3")
        assertEquals(
            ResolvedLink.Feed(FeedRequest(SiteId.FOURKHD, section = "popular", sort = FeedSort.POPULAR, page = 3)),
            resolvedPop,
        )
        assertFalse(plugin.linkResolver.canHandle("https://example.invalid/content/04/sample-set-one.html"))
    }

    // ============================================================================================
    // 2. Everia (EveriaSitePlugin)
    // ============================================================================================

    @Test
    fun everiaAllowsRotatingCdnsExcludesChromeAndFlattensDatedMultiPagePosts() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val canonical = "https://everia.club/2026/11/04/sample-model-photobook-one/"
        fakeHttp.stubHtml("https://everia.club/", EVERIA_LIST_HTML)
        fakeHttp.stubHtml(canonical, EVERIA_CONTENT_1_HTML)
        fakeHttp.stubHtml("${canonical}2/", EVERIA_CONTENT_2_HTML)

        val plugin = EveriaSitePlugin(fakeHttp)
        assertTrue(plugin.descriptor.networkProfile.mediaDomains.isEmpty())
        assertEquals(listOf(FeedSort.NEWEST), plugin.feedSource.supportedSorts(FeedRequest(SiteId.EVERIA)))
        assertEquals("https://everia.club/page/2/?s=sample%20model", plugin.engine.resolveFeedUrl(FeedRequest(SiteId.EVERIA, query = "sample model", page = 2)))

        val feed = plugin.feedSource.loadFeed(FeedRequest(SiteId.EVERIA))
        assertEquals(2, feed.items.size)
        assertEquals(1687, feed.totalPages)
        assertTrue(feed.hasMore)
        assertEquals("sample-model-photobook-one", feed.items[0].id)
        assertEquals("Sample Model – PhotoBook One", feed.items[0].title)
        assertEquals(canonical, feed.items[0].url)
        assertTrue(feed.items[0].coverUrl.startsWith("https://miya.fujibox.top/"))
        assertTrue(feed.items.none { it.url.contains("attacker") })

        val detail = plugin.gallerySource.loadGalleryDetail("${canonical}2/")
        assertEquals("sample-model-photobook-one", detail.item.id)
        assertEquals("Sample Model - PhotoBook One", detail.item.title)
        assertEquals(4, detail.pageCount) // 2 on page 1 + 2 on page 2
        assertEquals(2, detail.previewPageSize)
        assertEquals("https://karubox.top/wp-content/uploads/2026/11/KOR10A325_1.webp", detail.pages[0].imageUrl)
        assertEquals("https://karubox.top/wp-content/uploads/2026/11/SAMPLE_4.webp", detail.pages[3].imageUrl)
        assertTrue(detail.pages.none { it.imageUrl.contains("logo") || it.imageUrl.contains("AVATAR") })
        assertEquals("${canonical}2/#3-2-0", detail.pages[2].pageUrl)

        assertEquals(
            ResolvedLink.Gallery(SiteId.EVERIA, "sample-model-photobook-one", canonical, pageIndex = 3),
            plugin.linkResolver.resolve("${canonical}2/#3-2-0"),
        )
        assertEquals(
            ResolvedLink.Feed(FeedRequest(SiteId.EVERIA, query = "sample model", page = 2)),
            plugin.linkResolver.resolve("https://everia.club/page/2/?s=sample%20model"),
        )
    }

    // ============================================================================================
    // 3. 4KUp (FourKupSitePlugin)
    // ============================================================================================

    @Test
    fun fourKupUsesFullSizeAnchorInsteadOfDataUriPlaceholderAndResolvesLinks() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        fakeHttp.stubHtml("https://4kup.net/", FOURKUP_LIST_HTML)
        fakeHttp.stubHtml("https://4kup.net/sample-set-one/", FOURKUP_CONTENT_HTML)

        val plugin = FourKupSitePlugin(fakeHttp)
        assertTrue(plugin.descriptor.matchesMediaHost("blogger.googleusercontent.com"))
        assertEquals("https://4kup.net/page/2/", plugin.engine.resolveFeedUrl(FeedRequest(SiteId.FOURKUP, page = 2)))

        val feed = plugin.feedSource.loadFeed(FeedRequest(SiteId.FOURKUP))
        assertEquals(2, feed.items.size)
        assertEquals(2267, feed.totalPages)
        assertEquals("sample-set-one", feed.items[0].id)
        assertEquals("Sample Set One", feed.items[0].title)
        assertEquals("November 2, 2026", feed.items[0].dateLabel)
        assertTrue(feed.items[0].coverUrl.contains("googleusercontent.com"))
        assertTrue(feed.items.none { it.url.contains("attacker") })

        val detail = plugin.gallerySource.loadGalleryDetail("https://4kup.net/sample-set-one/")
        assertEquals("Sample Set One", detail.item.title)
        assertEquals(3, detail.pageCount)
        assertTrue(detail.pages.all { it.imageUrl.contains("googleusercontent.com") && !it.imageUrl.startsWith("data:") })
        assertTrue(detail.pages[0].imageUrl.endsWith("fixture-001.webp"))
        assertEquals("https://blogger.googleusercontent.com/img/b/R29vZ2xl/a/fixture-001-thumb.webp", detail.pages[0].thumbUrl)
        assertEquals(detail.pages[0].imageUrl, detail.item.coverUrl)
        assertTrue(detail.pages.none { it.imageUrl.contains("site-logo") })

        assertEquals(
            ResolvedLink.Feed(FeedRequest(SiteId.FOURKUP, query = "sample", page = 1)),
            plugin.linkResolver.resolve("https://4kup.net/?s=sample"),
        )
        assertEquals(
            ResolvedLink.Feed(FeedRequest(SiteId.FOURKUP, section = "latest", page = 2)),
            plugin.linkResolver.resolve("https://4kup.net/page/2/"),
        )
        assertNull(plugin.linkResolver.resolve("https://4kup.net/about/"))
    }

    // ============================================================================================
    // 4. GalleryEpic (GalleryEpicSitePlugin)
    // ============================================================================================

    @Test
    fun galleryEpicSupportsDirectorySearchRscImagesScopedPaginationAndMetadata() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        fakeHttp.stubHtml("https://galleryepic.com/en/cosplays/1", GALLERYEPIC_LIST_HTML)
        fakeHttp.stubHtml("https://galleryepic.com/en/coser/291/1", GALLERYEPIC_COSER_FEED_HTML)
        fakeHttp.stubHtml("https://galleryepic.com/en/cosplay/10824", GALLERYEPIC_CONTENT_HTML)
        fakeHttp.stubHtml("https://galleryepic.com/en/cosers/2?coserName=sample%3Amodel", GALLERYEPIC_COSERS_HTML)
        fakeHttp.stubHtml("https://galleryepic.com/en/parody/108", GALLERYEPIC_SERIES_HTML)

        val plugin = GalleryEpicSitePlugin(fakeHttp)
        assertTrue(plugin.searchSource.isDirectorySearch)
        assertTrue(plugin.descriptor.hasCapability(SiteCapability.DIRECTORY))
        assertEquals(listOf("cosers"), plugin.directorySource.directorySections().map { it.id })

        // Feed cards ignore download badge & coser link; caption does not absorb "56P / 6S"
        val feed = plugin.feedSource.loadFeed(FeedRequest(SiteId.GALLERYEPIC))
        assertEquals(2, feed.items.size)
        assertEquals(31, feed.totalPages)
        assertEquals("10824", feed.items[0].id)
        assertEquals("Sample Cosplay – Series", feed.items[0].title)
        assertEquals(56, feed.items[0].pageCount)
        assertTrue(feed.items.none { it.url.contains("/download/") || it.id == "291" })

        // Coser pagination stays scoped to /en/coser/291/ (ignores /en/coser/292/9)
        val coserFeed = plugin.feedSource.loadFeed(
            FeedRequest(SiteId.GALLERYEPIC, section = "coser", category = "291", scope = SearchScope.AUTHOR, page = 1),
        )
        assertEquals(3, coserFeed.totalPages)

        // Gallery detail reads ALL 6 images from Next.js RSC payload, not just 4 previews
        val detail = plugin.gallerySource.loadGalleryDetail("https://galleryepic.com/en/cosplay/10824")
        assertEquals(6, detail.pageCount)
        assertEquals("https://static.galleryepic.xyz/image/aaaaaaaa-1111-4111-8111-111111111111", detail.pages[0].imageUrl)
        assertEquals("https://static.galleryepic.xyz/image/ffffffff-6666-4666-8666-666666666666", detail.pages[5].imageUrl)
        assertEquals("Sample Cosplayer – Sample Cosplay (Series)", detail.item.title)
        assertEquals("2026-11-03", detail.item.dateLabel)
        assertEquals("12MB", detail.fileSizeLabel)
        assertEquals("Sample Cosplayer", detail.authors.single().name)
        assertEquals("291", detail.authors.single().id)
        assertEquals("Sample", detail.characters.single().label)
        assertEquals("691", detail.characters.single().slug)
        assertEquals("Series", detail.tags.single().label)
        assertEquals("series:108", detail.tags.single().slug)

        // DirectorySource: cosers search directory & series character directory
        val cosers = plugin.directorySource.loadDirectory(section = "cosers", page = 2, query = "sample:model")
        assertEquals(listOf("Sample Cosplayer", "Second Cosplayer"), cosers.map { it.title })
        assertEquals(listOf("291", "292"), cosers.map { it.id })
        assertTrue(cosers.all { it.kind == SearchScope.AUTHOR })
        assertTrue(cosers.none { it.url.contains("example.invalid") })

        val characters = plugin.directorySource.loadDirectory(section = "series:108")
        assertEquals(listOf("Sample Character", "Second Character"), characters.map { it.title })
        assertTrue(characters.all { it.kind == SearchScope.CHARACTER })

        // SearchSource fallback for directory search returns matched cosers + totalPages
        val dirSearch = plugin.searchSource.search(FeedRequest(SiteId.GALLERYEPIC, query = "sample:model", page = 2))
        assertEquals(2, dirSearch.items.size)
        assertEquals(22, dirSearch.totalPages)
        assertTrue(dirSearch.hasMore)
        assertEquals("true", dirSearch.items.first().extra["isDirectoryEntry"])

        // LinkResolver across all GalleryEpic URL shapes
        assertEquals(
            ResolvedLink.Gallery(SiteId.GALLERYEPIC, "10824", "https://galleryepic.com/en/cosplay/10824", null),
            plugin.linkResolver.resolve("https://galleryepic.com/en/cosplay/10824"),
        )
        assertEquals(
            ResolvedLink.Feed(FeedRequest(SiteId.GALLERYEPIC, section = "coser", category = "291", query = "291", scope = SearchScope.AUTHOR, page = 2)),
            plugin.linkResolver.resolve("https://galleryepic.com/en/coser/291/2"),
        )
        assertEquals(
            ResolvedLink.Feed(FeedRequest(SiteId.GALLERYEPIC, section = "character", category = "691", query = "691", scope = SearchScope.CHARACTER, page = 1)),
            plugin.linkResolver.resolve("https://galleryepic.com/en/character/691"),
        )
        assertEquals(
            ResolvedLink.Directory(SiteId.GALLERYEPIC, "series:108", "https://galleryepic.com/en/parody/108"),
            plugin.linkResolver.resolve("https://galleryepic.com/en/parody/108"),
        )
        assertEquals(
            ResolvedLink.Directory(SiteId.GALLERYEPIC, "cosers:2:sample", "https://galleryepic.com/en/cosers/2?coserName=sample"),
            plugin.linkResolver.resolve("https://galleryepic.com/en/cosers/2?coserName=sample"),
        )
    }

    // ============================================================================================
    // 5. Cosplaytele (CosplayteleSitePlugin — DUAL-MEDIA + #gallery-1 + CossoraAesProtocol)
    // ============================================================================================

    @Test
    fun cosplayteleEnforcesGallery1IsolationAndDecodesCossoraAesEmbeddedHlsVideos() = runTest {
        val embedUuid = "33c3b905-5871-4657-9714-6fcfa42148e3"
        val secondUuid = "44d4c016-6982-4768-8825-7adfb53259f4"
        val aesKey = "03c34d8f64f8ff77b5b4d754bc8e25bf"
        val iv = ByteArray(16) { it.toByte() }
        val expectedM3u8 = "https://cossora.stream/api-embed/$embedUuid/index.m3u8?token=fixture"
        val expectedPoster = "https://cdn.cossora.stream/posters/$embedUuid.jpg"
        val encryptedVideo = CossoraAesProtocol.encrypt(expectedM3u8, aesKey, iv)
        val encryptedPoster = CossoraAesProtocol.encrypt(expectedPoster, aesKey, iv)
        val embedHtml = """
            <html><body><script>
              const videoURL = '$encryptedVideo';
              const portadaURL = '$encryptedPoster';
              const videoURLDescrypt = decryptLink(videoURL, '$aesKey');
              var config = { file: videoURLDescrypt, defaultQuality: '1920p', type: 'hls' };
            </script></body></html>
        """.trimIndent()

        val articleUrl = "https://cosplaytele.com/sample-gallery/"
        val articleWithEmbed = COSPLAYTELE_CONTENT_HTML.replace(
            "<!-- EMBED_SLOT -->",
            """
            <p><iframe src="https://cossora.stream/embed/$embedUuid"></iframe></p>
            <p><iframe src="https://example.invalid/embed/$secondUuid"></iframe></p>
            """.trimIndent(),
        )

        val fakeHttp = FakeHttpClientAdapter()
        fakeHttp.stubHtml("https://cosplaytele.com/", COSPLAYTELE_LIST_HTML)
        fakeHttp.stubHtml(articleUrl, articleWithEmbed)
        fakeHttp.stubHtml("https://cossora.stream/embed/$embedUuid", embedHtml)

        val plugin = CosplayteleSitePlugin(fakeHttp)
        assertEquals(MediaKind.PHOTO, plugin.descriptor.primaryKind)
        assertEquals(setOf(MediaKind.PHOTO, MediaKind.VIDEO), plugin.descriptor.supportedKinds)
        assertTrue(plugin.descriptor.hasCapability(SiteCapability.DUAL_MEDIA))
        assertTrue(plugin.descriptor.matchesMediaHost("hls-cdn.cossora.stream"))

        // Listing cards
        val feed = plugin.feedSource.loadFeed(FeedRequest(SiteId.COSPLAYTELE))
        assertEquals(2, feed.items.size)
        assertEquals(475, feed.totalPages)
        assertEquals("sample-gallery", feed.items[0].id)
        assertEquals(99, feed.items[0].pageCount)

        // Gallery detail: strict #gallery-1 isolation + embeddedVideos separation + clean blockquote metadata
        val galleryDetail = plugin.gallerySource.loadGalleryDetail(articleUrl)
        assertEquals(3, galleryDetail.pageCount)
        assertTrue(galleryDetail.pages.all { it.imageUrl.contains("sample-") })
        assertTrue(galleryDetail.pages.none { it.imageUrl.contains("other-48") })
        assertEquals(listOf("Sample Cosplayer"), galleryDetail.authors.map { it.name })
        assertEquals(listOf("Sample"), galleryDetail.characters.map { it.label })

        assertEquals(1, galleryDetail.embeddedVideos.size)
        val embedded = galleryDetail.embeddedVideos.single()
        assertEquals(MediaKind.VIDEO, embedded.kind)
        assertEquals("video-$embedUuid", embedded.id)
        assertEquals("$articleUrl#video-$embedUuid", embedded.url)

        // VideoSource playback resolution via #video-<uuid> fragment
        val videoDetail = plugin.videoSource.loadVideoDetail(embedded.url)
        assertEquals("video-$embedUuid", videoDetail.item.id)
        assertEquals(MediaKind.VIDEO, videoDetail.item.kind)
        assertEquals(expectedPoster, videoDetail.item.coverUrl)
        assertTrue(videoDetail.item.title.contains("Sample Cosplayer cosplay Sample"))
        assertEquals(1, videoDetail.streams.size)
        val stream = videoDetail.streams.single()
        assertEquals("1920p", stream.quality)
        assertEquals(expectedM3u8, stream.url)
        assertTrue(stream.isHls)
        assertEquals("application/vnd.apple.mpegurl", stream.mimeTypeHint)
        assertEquals("https://cossora.stream/", stream.headers["Referer"])
        // Embed request must bypass cache and send article Referer
        val embedReq = fakeHttp.recordedRequests.last { it.url == "https://cossora.stream/embed/$embedUuid" }
        assertFalse(embedReq.allowCache)
        assertEquals(articleUrl, embedReq.headers["Referer"])

        // VideoSource also resolves bare single-video article URL and direct Cossora embed URL
        val bareDetail = plugin.videoSource.loadVideoDetail(articleUrl)
        assertEquals(expectedM3u8, bareDetail.streams.single().url)
        val directDetail = plugin.videoSource.loadVideoDetail("https://cossora.stream/embed/$embedUuid")
        assertEquals(expectedM3u8, directDetail.streams.single().url)

        // Untrusted decrypted media host MUST fail with SiteException.ParseError
        val untrustedEncrypted = CossoraAesProtocol.encrypt("https://example.invalid/evil.m3u8", aesKey, iv)
        fakeHttp.stubHtml(
            "https://cossora.stream/embed/$embedUuid",
            embedHtml.replace(encryptedVideo, untrustedEncrypted),
        )
        val untrustedErr = runCatching { plugin.videoSource.loadVideoDetail(embedded.url) }.exceptionOrNull()
        assertTrue(untrustedErr is SiteException.ParseError)

        // Multi-video article without fragment throws ParseError; unknown video fragment throws NotFound
        val multiArticleUrl = "https://cosplaytele.com/multi-video/"
        fakeHttp.stubHtml(
            multiArticleUrl,
            COSPLAYTELE_CONTENT_HTML.replace(
                "<!-- EMBED_SLOT -->",
                """
                <iframe src="https://cossora.stream/embed/$embedUuid"></iframe>
                <iframe src="https://cossora.stream/embed/$secondUuid"></iframe>
                """.trimIndent(),
            ),
        )
        assertTrue(runCatching { plugin.videoSource.loadVideoDetail(multiArticleUrl) }.exceptionOrNull() is SiteException.ParseError)
        assertTrue(
            runCatching {
                plugin.videoSource.loadVideoDetail("$multiArticleUrl#video-00000000-0000-4000-8000-000000000000")
            }.exceptionOrNull() is SiteException.NotFound,
        )

        // LinkResolver & MediaTransportHook
        assertEquals(
            ResolvedLink.Video(SiteId.COSPLAYTELE, "video-$embedUuid", "$articleUrl#video-$embedUuid"),
            plugin.linkResolver.resolve("$articleUrl#video-$embedUuid"),
        )
        assertEquals(
            ResolvedLink.Video(SiteId.COSPLAYTELE, "video-$embedUuid", "https://cossora.stream/embed/$embedUuid"),
            plugin.linkResolver.resolve("https://cossora.stream/embed/$embedUuid"),
        )
        assertEquals(
            ResolvedLink.Gallery(SiteId.COSPLAYTELE, "sample-gallery", articleUrl, pageIndex = 2),
            plugin.linkResolver.resolve("$articleUrl#2-1-1"),
        )
        assertEquals(
            mapOf("Referer" to "https://cossora.stream/"),
            plugin.mediaTransportHook.extraMediaHeaders("https://hls-cdn.cossora.stream/seg/01.ts"),
        )
    }

    // ============================================================================================
    // 6. Koin DI Module & SiteRegistry Integration
    // ============================================================================================

    @Test
    fun gallerySitesModuleRegistersAllFivePhotoPluginsIntoSiteRegistry() {
        val fakeHttp = FakeHttpClientAdapter()
        val koinApp = koinApplication {
            modules(
                module { single<HttpClientAdapter> { fakeHttp } },
                gallerySitesModule,
            )
        }
        try {
            val plugins = koinApp.koin.getAll<SitePlugin>()
            assertEquals(5, plugins.size)
            assertEquals(SiteId.PHOTO_SITES.toSet(), plugins.map { it.id }.toSet())

            val order = SiteId.ALL_LIVE.withIndex().associate { (i, id) -> id to i }
            val registry = SiteRegistry(plugins.sortedBy { order[it.id] ?: Int.MAX_VALUE })
            assertEquals(SiteId.PHOTO_SITES, registry.byPrimaryKind(MediaKind.PHOTO).map { it.id })
            assertEquals(listOf(SiteId.COSPLAYTELE), registry.supportingKind(MediaKind.VIDEO).map { it.id })

            // Media host routing invariants
            assertEquals(SiteId.FOURKHD, registry.findForMediaHost("i0.wp.com")?.id)
            assertEquals(SiteId.FOURKUP, registry.findForMediaHost("blogger.googleusercontent.com")?.id)
            assertEquals(SiteId.GALLERYEPIC, registry.findForMediaHost("static.galleryepic.xyz")?.id)
            assertEquals(SiteId.COSPLAYTELE, registry.findForMediaHost("hls-cdn.cossora.stream")?.id)
            // Everia intentionally registers zero fixed mediaDomains (rotating CDNs)
            assertNull(registry.findForMediaHost("karubox.top"))

            // Cross-site LinkResolver dispatch via SiteRegistry
            val resolvedCosplayVideo = registry.resolveLinkFromText(
                "Check this out https://cosplaytele.com/sample-gallery/#video-33c3b905-5871-4657-9714-6fcfa42148e3!",
            )
            assertNotNull(resolvedCosplayVideo)
            assertEquals(SiteId.COSPLAYTELE, resolvedCosplayVideo!!.first.id)
            assertTrue(resolvedCosplayVideo.second is ResolvedLink.Video)

            val resolved4khd = registry.resolveLinkFromText("https://www.4khd.com/content/04/sample-set-one.html")
            assertEquals(SiteId.FOURKHD, resolved4khd?.first?.id)
            assertTrue(resolved4khd?.second is ResolvedLink.Gallery)

            listOf(SiteId.EVERIA, SiteId.FOURKHD, SiteId.FOURKUP, SiteId.COSPLAYTELE).forEach { id ->
                assertTrue("$id should advertise TAG scope", SearchScope.TAG in registry.require(id).descriptor.searchScopes)
            }
        } finally {
            koinApp.close()
        }
    }

    @Test
    fun gallerySitesHandleTagRoutesBloggerExtensionlessUrlsDirectoryPaginationAndPureVideoPosts() = runTest {
        val fakeHttp = FakeHttpClientAdapter()

        // 1. 4KHD & Everia TAG / category routes
        val fourKhd = FourKhdSitePlugin(fakeHttp)
        assertEquals(
            "https://www.4khd.com/tag/cosplay?query-3-page=2",
            fourKhd.engine.resolveFeedUrl(FeedRequest(SiteId.FOURKHD, query = "cosplay", scope = SearchScope.TAG, page = 2)),
        )
        assertEquals(
            "https://www.4khd.com/pages/cosplay?query-3-page=2",
            fourKhd.engine.resolveFeedUrl(FeedRequest(SiteId.FOURKHD, category = "cosplay", page = 2)),
        )

        val everia = EveriaSitePlugin(fakeHttp)
        assertEquals(
            "https://everia.club/tag/gravure/page/2/",
            everia.engine.resolveFeedUrl(FeedRequest(SiteId.EVERIA, query = "gravure", scope = SearchScope.TAG, page = 2)),
        )
        assertEquals(
            "https://everia.club/category/gravure/page/2/",
            everia.engine.resolveFeedUrl(FeedRequest(SiteId.EVERIA, category = "gravure", page = 2)),
        )

        // 2. 4KUp accepts extensionless Blogger full-size URLs & resolves page images
        val fourKup = FourKupSitePlugin(fakeHttp)
        val post4kup = "https://4kup.net/extensionless-blogger-set/"
        fakeHttp.stubHtml(
            post4kup,
            """
            <!DOCTYPE html><html><head><title>4KUp Blogger - 4KUp</title></head><body>
              <article><div class="entry-content">
                <a class="thumb-photo" href="https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsDemoToken/s1600/photo_01">
                  <picture><img data-src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsDemoToken/s400/photo_01" src="data:image/gif;base64,R0lGOD" /></picture>
                </a>
              </div></article>
            </body></html>
            """.trimIndent(),
        )
        val kupDetail = fourKup.gallerySource.loadGalleryDetail(post4kup)
        assertEquals(
            listOf("https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsDemoToken/s1600/photo_01"),
            kupDetail.pages.map { it.imageUrl },
        )
        assertEquals(
            kupDetail.pages.single().imageUrl,
            fourKup.gallerySource.resolvePageImage("$post4kup#1-1-0", preferOriginal = false, forceReload = true).imageUrl,
        )

        // 3. GalleryEpic cosers:<basePage>:<query> advances when caller passes page > 1
        val epic = GalleryEpicSitePlugin(fakeHttp)
        fakeHttp.stubHtml(
            "https://galleryepic.com/en/cosers/3?coserName=alice",
            """<html><body><a href="/en/coser/301/1"><h3>Alice P3</h3></a></body></html>""",
        )
        val dirPage3 = epic.directorySource.loadDirectory(section = "cosers:2:alice", page = 3)
        assertEquals(listOf("301"), dirPage3.map { it.id })

        // 4. Cosplaytele pure-video post (no #gallery-1, protocol-relative data-src iframe) & /about/#video canonicalize guard
        val cosplay = CosplayteleSitePlugin(fakeHttp)
        val videoOnlyUrl = "https://cosplaytele.com/video-only-post/"
        val embedUuid = "44444444-5555-4666-8777-888888888888"
        fakeHttp.stubHtml(
            videoOnlyUrl,
            """
            <!DOCTYPE html><html><head><title>Video Only - Cosplaytele</title></head><body>
              <article class="post"><div class="entry-content single-page">
                <iframe data-src="//cossora.stream/embed/$embedUuid"></iframe>
              </div></article>
            </body></html>
            """.trimIndent(),
        )
        val pureVideoDetail = cosplay.gallerySource.loadGalleryDetail(videoOnlyUrl)
        assertEquals(0, pureVideoDetail.pageCount)
        assertTrue(pureVideoDetail.pages.isEmpty())
        assertEquals(listOf("video-$embedUuid"), pureVideoDetail.embeddedVideos.map { it.id })
        assertNull(cosplay.linkResolver.canonicalize("https://cosplaytele.com/about/#video-$embedUuid"))
    }

    @Test
    fun galleryPluginRegexLiteralsEscapeBracesAndBracketsForAndroidArtIcu() {
        val targetClasses = listOf(
            FourKhdSitePlugin::class.java,
            EveriaSitePlugin::class.java,
            FourKupSitePlugin::class.java,
            GalleryEpicSitePlugin::class.java,
            CosplayteleSitePlugin::class.java,
            WordPressPhotoEngine::class.java,
            GalleryUrlUtils::class.java,
            PhotoPageAddress::class.java,
        )
        val violations = mutableListOf<String>()
        var checkedCount = 0
        targetClasses.forEach { targetClass ->
            val holders = listOf(targetClass) + targetClass.declaredClasses.toList()
            holders.forEach { holder ->
                holder.declaredFields
                    .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && Regex::class.java.isAssignableFrom(it.type) }
                    .forEach { field ->
                        field.isAccessible = true
                        val pattern = (field.get(null) as? Regex)?.pattern ?: return@forEach
                        checkedCount++
                        val err = findIcuRegexViolation(pattern)
                        if (err != null) {
                            violations += "${targetClass.simpleName}.${field.name} [$err]: $pattern"
                        }
                    }
            }
        }
        assertTrue("Expected >0 static Regex fields scanned across gallery plugins, got $checkedCount", checkedCount > 0)
        assertTrue("Unescaped {/}/] will crash Android ART ICU: $violations", violations.isEmpty())
    }

    private fun findIcuRegexViolation(pattern: String): String? {
        var i = 0
        val n = pattern.length
        var inCharClass = false
        var ccStart = -1
        while (i < n) {
            val ch = pattern[i]
            if (ch == '\\') {
                if (i + 1 < n && (pattern[i + 1] == 'p' || pattern[i + 1] == 'P') && i + 2 < n && pattern[i + 2] == '{') {
                    val closeProp = pattern.indexOf('}', i + 3)
                    if (closeProp != -1) {
                        i = closeProp + 1
                        continue
                    }
                }
                i += 2
                continue
            }
            if (inCharClass) {
                if (ch == '{' || ch == '}') return "unescaped '$ch' inside character class"
                if (ch == ']' && i > ccStart + 1) inCharClass = false
                i++
                continue
            }
            when (ch) {
                '[' -> {
                    inCharClass = true
                    ccStart = if (i + 1 < n && pattern[i + 1] == '^') i + 1 else i
                }
                ']' -> return "unescaped ']'"
                '{' -> {
                    val qMatch = Regex("^\\{\\d+(?:,\\d*)?\\}").find(pattern.substring(i))
                    if (qMatch != null) {
                        i += qMatch.value.length
                        continue
                    }
                    return "unescaped '{'"
                }
                '}' -> return "unescaped '}'"
            }
            i++
        }
        return null
    }

    // ============================================================================================
    // Neutral HTML Test Fixtures
    // ============================================================================================

    companion object {
        private val FOURKHD_LIST_HTML = """
            <!DOCTYPE html><html lang="en"><head><title>Latest - 4KHD</title></head><body>
            <ul class="wp-block-post-template">
              <li class="wp-block-post">
                <div class="wp-block-template-part">
                  <figure><a href="https://www.4khd.com/content/04/sample-set-one.html">
                    <img src="https://i0.wp.com/pic.4khd.com/fixture/set-one.jpg?resize=850%2C1158" width="850" height="1158" />
                  </a></figure>
                  <div class="wp-block-post-date"><time datetime="2026-11-04T17:10:00+08:00">11/4</time></div>
                  <div class="wp-block-group"><div class="eyeicon"></div>2786</div>
                  <h2 class="wp-block-post-title"><a href="https://www.4khd.com/content/04/sample-set-one.html">[JP] Sample Model &#8211; Sample One[21MB-31photos]</a></h2>
                </div>
              </li>
              <li class="wp-block-post">
                <div class="wp-block-template-part">
                  <figure><a href="https://www.4khd.com/content/02/sample-set-two.html">
                    <img src="https://i0.wp.com/pic.4khd.com/fixture/set-two.jpg?resize=850%2C1158" width="850" height="1158" />
                  </a></figure>
                  <div class="wp-block-post-date"><time datetime="2026-11-02T18:59:00+08:00">11/2</time></div>
                  <div class="eyeicon"></div>1405
                  <h2 class="wp-block-post-title"><a href="https://www.4khd.com/content/02/sample-set-two.html">【Sample Cosplay】 Sample Costume[503MB-114photos]</a></h2>
                </div>
              </li>
              <li class="wp-block-post">
                <h2 class="wp-block-post-title"><a href="https://example.invalid.attacker/content/09/elsewhere.html">Foreign host</a></h2>
              </li>
            </ul>
            <nav><a class="page-numbers" href="?query-3-page=2">2</a><a class="page-numbers" href="?query-3-page=411">411</a></nav>
            </body></html>
        """.trimIndent()

        private val FOURKHD_SEARCH_HTML = """
            <!DOCTYPE html><html><head><title>search - 4KHD</title></head><body>
            <ul><li class="wp-block-post">
              <figure><a href="https://www.4khd.com/content/07/sample-search-hit.html">
                <img src="https://i0.wp.com/pic.4khd.com/fixture/search-hit.jpg" width="850" height="1158" />
              </a></figure>
              <div class="eyeicon"></div>640
              <h2 class="wp-block-post-title"><a href="https://www.4khd.com/content/07/sample-search-hit.html">Sample Search Hit[8MB-30photos]</a></h2>
            </li></ul></body></html>
        """.trimIndent()

        private val FOURKHD_CONTENT_1_HTML = """
            <!DOCTYPE html><html><head>
              <title>[JP] Sample Model - Sample One[21MB-31photos] - 4KHD</title>
              <meta property="og:title" content="[JP] Sample Model - Sample One[21MB-31photos] - 4KHD" />
            </head><body>
            <div class="entry-content">
              <p><a href="https://i0.wp.com/pic.4khd.com/fixture/set-one-001.webp?w=1300"><img src="https://i0.wp.com/pic.4khd.com/fixture/set-one-001.webp?w=1300" width="1300" height="1500" /></a></p>
              <p><a href="https://i0.wp.com/pic.4khd.com/fixture/set-one-002.webp?w=1300"><img src="https://i0.wp.com/pic.4khd.com/fixture/set-one-002.webp?w=1300" width="1300" height="1500" /></a></p>
              <p><a href="https://i0.wp.com/pic.4khd.com/fixture/set-one-003.webp?w=1300"><img src="https://i0.wp.com/pic.4khd.com/fixture/set-one-003.webp?w=1300" width="1300" height="1500" /></a></p>
              <p><img src="https://www.4khd.com/static/banner.gif" width="700" height="160" alt="banner" /></p>
            </div>
            <ul class="page-links">
              <li class="numpages current"><span>1</span></li>
              <li class="numpages"><a href="https://www.4khd.com/content/04/sample-set-one.html/2">2</a></li>
            </ul></body></html>
        """.trimIndent()

        private val FOURKHD_CONTENT_2_HTML = """
            <!DOCTYPE html><html><head><title>[JP] Sample Model - Sample One[21MB-31photos] - 4KHD</title></head><body>
            <div class="entry-content">
              <p><a href="https://i0.wp.com/pic.4khd.com/fixture/set-one-004.webp?w=1300"><img src="https://i0.wp.com/pic.4khd.com/fixture/set-one-004.webp?w=1300" /></a></p>
              <p><a href="https://i0.wp.com/pic.4khd.com/fixture/set-one-005.webp?w=1300"><img src="https://i0.wp.com/pic.4khd.com/fixture/set-one-005.webp?w=1300" /></a></p>
            </div>
            <ul class="page-links">
              <li class="numpages"><a href="https://www.4khd.com/content/04/sample-set-one.html">1</a></li>
              <li class="numpages current"><span>2</span></li>
            </ul></body></html>
        """.trimIndent()

        private val EVERIA_LIST_HTML = """
            <!DOCTYPE html><html><head><title>Everia.club</title></head><body>
            <div class="rt-grid">
              <div class="rt-holder">
                <a href="https://everia.club/2026/11/04/sample-model-photobook-one/">
                  <img src="https://miya.fujibox.top/wp-content/uploads/2026/11/SAMPLE_0.webp" class="rt-img-responsive" width="400" height="600" />
                </a>
                <h3 class="entry-title"><a href="https://everia.club/2026/11/04/sample-model-photobook-one/">Sample Model &#8211; PhotoBook One</a></h3>
              </div>
              <div class="rt-holder">
                <a href="https://everia.club/2026/11/04/sample-model-photobook-two/">
                  <img src="https://miya.fujibox.top/wp-content/uploads/2026/11/SAMPLE2_0.webp" class="rt-img-responsive" width="400" height="600" />
                </a>
                <h3 class="entry-title"><a href="https://everia.club/2026/11/04/sample-model-photobook-two/">Sample Model &#8211; PhotoBook Two</a></h3>
              </div>
              <div class="rt-holder">
                <h3 class="entry-title"><a href="https://example.invalid.attacker/2026/11/04/foreign/">Foreign</a></h3>
              </div>
            </div>
            <div class="pagination">
              <a data-paged="2" href="https://everia.club/page/2/">2</a>
              <a data-paged="1687" href="https://everia.club/page/1687/">1687</a>
            </div></body></html>
        """.trimIndent()

        private val EVERIA_CONTENT_1_HTML = """
            <!DOCTYPE html><html><head>
              <title>Sample Model - PhotoBook One - Everia.club</title>
              <meta property="og:title" content="Sample Model - PhotoBook One - Everia.club" />
            </head><body>
            <article class="post"><div class="entry-content">
              <figure class="wp-block-gallery">
                <figure class="wp-block-image"><img src="https://karubox.top/wp-content/uploads/2026/11/KOR10A325_1.webp" /></figure>
                <figure class="wp-block-image"><img src="https://karubox.top/wp-content/uploads/2026/11/SAMPLE_2.webp" /></figure>
              </figure>
              <img src="https://everia.club/static/logo.png" alt="logo" />
              <img src="https://karubox.top/wp-content/uploads/2026/11/AVATAR_author.webp" alt="avatar" />
              <div class="page-links">
                <span class="post-page-numbers current">1</span>
                <a href="https://everia.club/2026/11/04/sample-model-photobook-one/2/" class="post-page-numbers">2</a>
              </div>
            </div></article></body></html>
        """.trimIndent()

        private val EVERIA_CONTENT_2_HTML = """
            <!DOCTYPE html><html><head><title>Sample Model - PhotoBook One - Everia.club</title></head><body>
            <article class="post"><div class="entry-content">
              <figure class="wp-block-gallery">
                <figure class="wp-block-image"><img src="https://karubox.top/wp-content/uploads/2026/11/SAMPLE_3.webp" /></figure>
                <figure class="wp-block-image"><img src="https://karubox.top/wp-content/uploads/2026/11/SAMPLE_4.webp" /></figure>
              </figure>
            </div></article></body></html>
        """.trimIndent()

        private val FOURKUP_LIST_HTML = """
            <!DOCTYPE html><html><head><title>4kUp</title></head><body>
            <div class="generate-columns-container">
              <article><div class="post-thumbnail"><img src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/fixture-one.webp" width="605" height="462" /></div>
                <h2 class="entry-title"><a href="https://4kup.net/sample-set-one/">Sample Set One</a></h2>
                <time class="entry-date" datetime="2026-11-02T20:18:13+00:00">November 2, 2026</time></article>
              <article><div class="post-thumbnail"><img src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/fixture-two.webp" width="605" height="462" /></div>
                <h2 class="entry-title"><a href="https://4kup.net/sample-set-two/">Sample Set Two</a></h2>
                <time class="entry-date" datetime="2026-11-01T10:00:00+00:00">November 1, 2026</time></article>
              <article><h2 class="entry-title"><a href="https://example.invalid.attacker/foreign/">Foreign</a></h2></article>
            </div>
            <nav><a class="page-numbers" href="https://4kup.net/page/2267/">2267</a></nav></body></html>
        """.trimIndent()

        private val FOURKUP_CONTENT_HTML = """
            <!DOCTYPE html><html><head>
              <title>Sample Set One - 4kUp</title>
              <meta property="og:title" content="Sample Set One - 4kUp" />
            </head><body><article class="post"><div class="entry-content">
              <div id="gallery" class="flex-images">
                <div class="item"><a class="thumb-photo" href="https://blogger.googleusercontent.com/img/b/R29vZ2xl/a/fixture-001.webp"><img data-src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/a/fixture-001-thumb.webp" src="data:image/gif;base64,R0lGODlhAQABAIAAAP" width="1067" height="1600" /></a></div>
                <div class="item"><a class="thumb-photo" href="https://blogger.googleusercontent.com/img/b/R29vZ2xl/a/fixture-002.webp"><img data-src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/a/fixture-002-thumb.webp" src="data:image/gif;base64,R0lGODlhAQABAIAAAP" width="1067" height="1600" /></a></div>
                <div class="item"><a class="thumb-photo" href="https://blogger.googleusercontent.com/img/b/R29vZ2xl/a/fixture-003.webp"><img data-src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/a/fixture-003-thumb.webp" src="data:image/gif;base64,R0lGODlhAQABAIAAAP" width="1067" height="1600" /></a></div>
              </div>
              <p><img src="https://4kup.net/wp-content/uploads/site-logo.png" alt="site" /></p>
            </div></article></body></html>
        """.trimIndent()

        private val GALLERYEPIC_LIST_HTML = """
            <!DOCTYPE html><html><head><title>Gallery Epic</title></head><body><div class="grid">
              <div>
                <a href="/en/cosplay/10824">
                  <div><img src="https://static.galleryepic.xyz/image/aaaaaaaa-1111-4111-8111-111111111111" /><p>56P / 6S</p></div>
                  <div><h3>Sample Cosplay</h3><p>Series</p></div>
                </a>
                <a href="/en/download/cosplay/10824"><svg></svg></a>
                <a href="/en/coser/291/1"><h3>Sample Cosplayer</h3></a>
              </div>
              <div>
                <a href="/en/cosplay/10823">
                  <div><img src="https://static.galleryepic.xyz/image/bbbbbbbb-2222-4222-8222-222222222222" /><p>25P</p></div>
                  <div><h3>Second Cosplay</h3><p>Other Series</p></div>
                </a>
              </div>
            </div>
            <div class="pagination"><a href="/en/cosplays/2">2</a><a href="/en/cosplays/31">31</a></div>
            </body></html>
        """.trimIndent()

        private val GALLERYEPIC_COSER_FEED_HTML = GALLERYEPIC_LIST_HTML.replace(
            "</body>",
            "<nav><a href=\"/en/coser/291/3\">3</a><a href=\"/en/coser/292/9\">9</a></nav></body>",
        )

        private val GALLERYEPIC_CONTENT_HTML = """
            <!DOCTYPE html><html><head>
              <title>Sample Cosplayer – Sample Cosplay, 6 Photos | Gallery Epic</title>
              <meta property="og:image" content="https://static.galleryepic.xyz/image/aaaaaaaa-1111-4111-8111-111111111111" />
              <meta name="description" content="Sample Cosplayer cosplay of Sample from Series: 5 photos, 1 selfie, 12MB." />
            </head><body>
            <h1>Sample Cosplayer – Sample Cosplay (Series)</h1>
            <div class="grid">
              <img src="https://static.galleryepic.xyz/image/aaaaaaaa-1111-4111-8111-111111111111" />
              <img src="https://static.galleryepic.xyz/image/bbbbbbbb-2222-4222-8222-222222222222" />
              <img src="https://static.galleryepic.xyz/image/cccccccc-3333-4333-8333-333333333333" />
              <img src="https://static.galleryepic.xyz/image/dddddddd-4444-4444-8444-444444444444" />
            </div>
            <dl>
              <dt>Cosplayer</dt><dd><a href="/en/coser/291/1">Sample Cosplayer</a></dd>
              <dt>Character</dt><dd><a href="/en/character/691">Sample</a></dd>
              <dt>Series</dt><dd><a href="/en/parody/108">Series</a></dd>
              <dt>Contents</dt><dd>5 photos, 1 selfie · 12MB</dd>
              <dt>Published</dt><dd><time dateTime="2026-11-03T04:43:05.625Z">2026-11-03</time></dd>
            </dl>
            <script>self.__next_f.push([1,"{\"cosplay\":{\"id\":10824,\"images\":[\"aaaaaaaa-1111-4111-8111-111111111111\",\"bbbbbbbb-2222-4222-8222-222222222222\",\"cccccccc-3333-4333-8333-333333333333\",\"dddddddd-4444-4444-8444-444444444444\",\"eeeeeeee-5555-4555-8555-555555555555\",\"ffffffff-6666-4666-8666-666666666666\"]}}"])</script>
            </body></html>
        """.trimIndent()

        private val GALLERYEPIC_COSERS_HTML = """
            <html><body><h1>All Cosers</h1><div class="grid">
              <a href="/en/coser/291/1"><img src="https://static.galleryepic.xyz/image/aaaaaaaa-1111-4111-8111-111111111111" /><h3>Sample Cosplayer</h3></a>
              <a href="/en/coser/292/1"><h3>Second Cosplayer</h3></a>
              <a href="https://example.invalid/en/coser/1/1"><h3>Foreign entry</h3></a>
            </div><nav><a href="/en/cosers/1">1</a><a href="/en/cosers/2">2</a><a href="/en/cosers/22">22</a></nav></body></html>
        """.trimIndent()

        private val GALLERYEPIC_SERIES_HTML = """
            <html><body><h1>Sample Series Cosplay</h1><div class="grid">
              <a href="/en/character/691"><p>Sample Character</p></a>
              <a href="/en/character/692"><p>Second Character</p></a>
            </div></body></html>
        """.trimIndent()

        private val COSPLAYTELE_LIST_HTML = """
            <!DOCTYPE html><html><head><title>Cosplaytele</title></head><body>
            <div class="row">
              <div class="col post-item">
                <a href="https://cosplaytele.com/sample-gallery/"><img src="https://cosplaytele.com/wp-content/uploads/2026/11/sample-101_result.webp" class="wp-post-image" width="2133" height="1600" /></a>
                <h5 class="post-title"><a href="https://cosplaytele.com/sample-gallery/">Sample Cosplayer cosplay Sample &#8211; Series &#8220;99 photos&#8221;</a></h5>
              </div>
              <div class="col post-item">
                <a href="https://cosplaytele.com/sample-second/"><img src="https://cosplaytele.com/wp-content/uploads/2026/11/sample2-26_result.webp" class="wp-post-image" width="2399" height="1600" /></a>
                <h5 class="post-title"><a href="https://cosplaytele.com/sample-second/">Second Cosplayer cosplay Two &#8220;61 photos&#8221;</a></h5>
              </div>
            </div>
            <div class="pagination"><a class="page-number" href="https://cosplaytele.com/page/475/">475</a></div>
            </body></html>
        """.trimIndent()

        private val COSPLAYTELE_CONTENT_HTML = """
            <!DOCTYPE html><html><head>
              <title>Sample Cosplayer cosplay Sample</title>
              <meta property="og:title" content="Sample Cosplayer cosplay Sample - Cosplaytele" />
            </head><body><article class="post"><div class="entry-content single-page">
              <blockquote><p><strong>Cosplayer: Sample Cosplayer</strong><br /><strong>Character: Sample</strong><br />
                <strong>Photos: 3 photos</strong></p></blockquote>
              <div id="gallery-1" class="gallery">
                <figure class="gallery-item"><a href="https://cosplaytele.com/wp-content/uploads/2026/11/sample-1_result.webp"><img width="1200" height="1600" src="https://cosplaytele.com/wp-content/uploads/2026/11/sample-1_result.webp" /></a></figure>
                <figure class="gallery-item"><a href="https://cosplaytele.com/wp-content/uploads/2026/11/sample-2_result.webp"><img width="1200" height="1600" src="https://cosplaytele.com/wp-content/uploads/2026/11/sample-2_result.webp" /></a></figure>
                <figure class="gallery-item"><a href="https://cosplaytele.com/wp-content/uploads/2026/11/sample-3_result.webp"><img width="1200" height="1600" src="https://cosplaytele.com/wp-content/uploads/2026/11/sample-3_result.webp" /></a></figure>
              </div>
              <!-- EMBED_SLOT -->
              <div class="crp_related"><h3>Recommend For You:</h3><ul><li>
                <a href="https://cosplaytele.com/other/"><figure><img src="https://cosplaytele.com/wp-content/uploads/2026/07/other-48_result.webp" /></figure></a>
              </li></ul></div>
            </div></article></body></html>
        """.trimIndent()
    }
}
