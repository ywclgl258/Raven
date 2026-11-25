package com.raven.app.sites.comic

import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.JmcomicStripMath
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteHttpResponse
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteSessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class WnacgAndJmcomicPluginTest {

    // -------------------------------------------------------------------------
    // WNACG Tests
    // -------------------------------------------------------------------------

    private fun wnacgFeedHtml(items: List<Pair<Int, String>>, nextHref: String? = null): String = buildString {
        append("<html><body>")
        for ((aid, title) in items) {
            append(
                """
                <div class="gallary_item">
                  <div class="pic_box"><img src="//t2.wnimg1.ru/data/t/00/$aid.jpg" /></div>
                  <div class="info">
                    <div class="title"><a href="/photos-index-aid-$aid.html">$title</a></div>
                    <div class="info_col">24張圖片，創建於2026-01-01</div>
                  </div>
                </div>
                """.trimIndent(),
            )
        }
        if (nextHref != null) {
            append("""<div class="paginator"><span class="thispage">1</span><a href="$nextHref">2</a></div>""")
        }
        append("</body></html>")
    }

    private val wnacgDetailHtml = """
        <html><body>
          <h2>[測試作者] 紳士漫畫測試本</h2>
          <div class="uwthumb"><img src="//t3.wnimg1.ru/data/t/01/77777.jpg" /></div>
          <div class="uwconn">
            <label>分類：同人誌 / 漢化</label>
            <label>頁數：2P</label>
            <p>簡介：這是一本測試簡介。</p>
            <div class="addtags">
              <a class="tagshow" href="/albums-index-tag-%E5%85%A8%E5%BD%A9.html">全彩</a>
            </div>
          </div>
          <div class="uwuinfo"><p>UploaderDemo</p></div>
        </body></html>
    """.trimIndent()

    private val wnacgReaderHtmlWithWriteln = """
        <html><head><script>
          document.writeln("var imglist = [{ url: fast_img_host + \"//img2.wnimg1.ru/data/77777/001.jpg\", caption: \"1\"}, { url: \"//img5.qy0.ru/data/77777/002.webp\", caption: \"2\"}, { url: \"/themes/weitu/images/bg/shoucang.jpg\", caption: \"end\"}];");
        </script></head><body></body></html>
    """.trimIndent()

    @Test
    fun `wnacg enforces desktop windows UA, routes feeds, categories, languages, and search`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText(
            "https://www.wnacg.com/albums-index-page-1.html",
            wnacgFeedHtml(listOf(77777 to "Latest Book"), nextHref = "/albums-index-page-2.html"),
        )
        http.onUrlText(
            "https://www.wnacg.com/albums-index-page-1-cate-1.html",
            wnacgFeedHtml(listOf(80002 to "Chinese Book 2", 80000 to "Chinese Book 1")),
        )
        http.onUrlText(
            "https://www.wnacg.com/albums-index-page-1-cate-12.html",
            wnacgFeedHtml(listOf(80003 to "Japanese Book 2", 80001 to "Japanese Book 1")),
        )
        http.onUrlText(
            "https://www.wnacg.com/search/index.php?s=create_time_DESC&q=demo+author&p=1",
            wnacgFeedHtml(listOf(77778 to "Author Work")),
        )
        http.onUrlText(
            "https://www.wnacg.com/albums-index-page-1-tag-%E5%85%A8%E5%BD%A9.html",
            wnacgFeedHtml(listOf(77779 to "Full Color Work")),
        )

        val plugin = WnacgSitePlugin(http)
        assertEquals(SiteNetworkProfile.DESKTOP_WINDOWS_UA, plugin.descriptor.networkProfile.userAgent)

        // Latest feed with pagination
        val latest = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.WNACG, page = 1))
        assertEquals(1, latest.items.size)
        assertEquals("77777", latest.items.single().id)
        assertEquals(24, latest.items.single().pageCount)
        assertEquals("https://t2.wnimg1.ru/data/t/00/77777.jpg", latest.items.single().coverUrl)
        assertTrue(latest.hasMore)
        assertEquals(2, latest.nextPage)

        // Every request enforces DESKTOP_WINDOWS_UA header
        assertEquals(SiteNetworkProfile.DESKTOP_WINDOWS_UA, http.recordedRequests.last().headers["User-Agent"])

        // Chinese language filter -> cate-1
        val cn = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.WNACG, language = ComicLanguage.CHINESE))
        assertEquals(listOf("80002", "80000"), cn.items.map { it.id })

        // Dual language filter merges cate-1 and cate-12 descending by numeric ID
        val dual = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.WNACG, language = ComicLanguage.CHINESE_AND_JAPANESE))
        assertEquals(listOf("80003", "80002", "80001", "80000"), dual.items.map { it.id })

        // Author search and tag browse
        val authorRes = plugin.searchSource!!.search(FeedRequest(SiteId.WNACG, query = "demo author", scope = SearchScope.AUTHOR))
        assertEquals("77778", authorRes.items.single().id)

        val tagRes = plugin.searchSource!!.search(FeedRequest(SiteId.WNACG, query = "全彩", scope = SearchScope.TAG))
        assertEquals("77779", tagRes.items.single().id)
    }

    @Test
    fun `wnacg detail unpacks document writeln imglist, strips shoucang jpg, and resolves links`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://www.wnacg.com/photos-index-aid-77777.html", wnacgDetailHtml)
        http.onUrlText("https://www.wnacg.com/photos-gallery-aid-77777.html", wnacgReaderHtmlWithWriteln)

        val plugin = WnacgSitePlugin(http)
        val detail = plugin.gallerySource!!.loadGalleryDetail("https://wnacg.com/photos-slide-aid-77777.html")
        assertEquals("77777", detail.item.id)
        assertEquals(2, detail.pageCount) // shoucang.jpg stripped!
        assertEquals("https://img2.wnimg1.ru/data/77777/001.jpg", detail.pages[0].imageUrl)
        assertEquals("https://img5.qy0.ru/data/77777/002.webp", detail.pages[1].imageUrl)
        assertTrue(detail.pages.none { it.imageUrl.contains("shoucang.jpg") })
        assertEquals("UploaderDemo", detail.authors.single().name)
        assertEquals("全彩", detail.tags.single().label)

        val page2 = plugin.gallerySource!!.resolvePageImage("https://www.wnacg.com/photos-index-aid-77777.html#2")
        assertEquals(2, page2.index)
        assertEquals("https://img5.qy0.ru/data/77777/002.webp", page2.imageUrl)

        val resolver = plugin.linkResolver!!
        assertEquals(
            ResolvedLink.Gallery(SiteId.WNACG, "77777", "https://www.wnacg.com/photos-index-aid-77777.html#2", pageIndex = 2),
            resolver.resolve("https://wnacg.com/photos-gallery-aid-77777.html#2"),
        )
        val tagResolved = resolver.resolve("https://www.wnacg.com/albums-index-page-3-tag-%E5%85%A8%E5%BD%A9.html") as ResolvedLink.Feed
        assertEquals(SearchScope.TAG, tagResolved.request.scope)
        assertEquals("全彩", tagResolved.request.query)
        assertEquals(3, tagResolved.request.page)
    }

    // -------------------------------------------------------------------------
    // JMComic Tests
    // -------------------------------------------------------------------------

    private fun jmCatalogueHtml(host: String): String = """
        <html><body>
          <div class="list-col">
            <div class="thumb-overlay-albums">
              <a href="/album/500001/sample-comic">
                <img data-original="https://cdn-msp.18comic.ink/media/albums/500001_3x4.jpg" />
              </a>
            </div>
            <span class="video-title">JM Sample Album</span>
          </div>
          <ul class="pagination">
            <li><a href="https://$host/albums?o=mr&amp;page=2">2</a></li>
          </ul>
        </body></html>
    """.trimIndent()

    private fun jmAlbumHtml(): String = """
        <html><head><meta name="description" content="Multi-chapter comic description" /></head><body>
          <h1 id="book-name">JM Multi-Chapter Title</h1>
          <img itemprop="image" data-original="https://cdn-msp.18comic.ink/media/albums/500001.jpg" />
          <a class="reading" href="/photo/500010">開始閱讀</a>
          <div id="episode-block">
            <ul class="episode">
              <li><a href="/photo/500010">第1話</a></li>
              <li><a href="/photo/500011">第2話</a></li>
            </ul>
          </div>
        </body></html>
    """.trimIndent()

    private fun jmChapterHtml(chapterId: Long = 500010L, scrambleId: Long = 220980L): String = """
        <html><body>
          <h1 class="pull-left">JM Multi-Chapter Title 第1話</h1>
          <script>
            var scramble_id = $scrambleId;
            var aid = $chapterId;
            var speed = '0';
          </script>
          <div class="scramble-page" id="00001.webp">
            <img id="album_photo_0" data-page="0" data-original="https://cdn-msp.18comic.ink/media/photos/$chapterId/00001.webp" />
          </div>
          <div class="scramble-page" id="00002.gif">
            <img id="album_photo_1" data-page="1" data-original="https://cdn-msp2.18comic.ink/media/photos/$chapterId/00002.gif" />
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `jmcomic mirror lease router fails over on challenge, caches 10-min lease, and invalidates on route change`() = runTest {
        var now = 10_000L
        val http = FakeHttpClientAdapter()

        // Primary mirror (jmcomic-zzz.one) returns CF challenge; secondary (18comic.ink) is healthy
        http.onMatch({ req -> req.url.startsWith("https://jmcomic-zzz.one/") }) { req ->
            SiteHttpResponse(
                code = 403,
                finalUrl = req.url,
                headers = mapOf("cf-mitigated" to listOf("challenge")),
                body = "Just a moment...".toByteArray(),
            )
        }
        http.onMatch({ req -> req.url.startsWith("https://18comic.ink/albums") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, jmCatalogueHtml("18comic.ink"))
        }
        http.onMatch({ req -> req.url.startsWith("https://jmcomic-zzz.org/albums") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, jmCatalogueHtml("jmcomic-zzz.org"))
        }

        val plugin = JmcomicSitePlugin(http, clockMs = { now })
        assertEquals(
            listOf("jmcomic-zzz.one", "18comic.ink", "jmcomic-zzz.org", "18comic.vip"),
            plugin.hostRouter.candidateHosts(),
        )

        // First call probes jmcomic-zzz.one (challenge) -> fails over to 18comic.ink
        val feed1 = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.JMCOMIC, page = 1))
        assertEquals(1, feed1.items.size)
        assertEquals("500001", feed1.items.single().id)
        assertTrue(feed1.hasMore)
        assertEquals(2, feed1.nextPage)
        assertEquals("18comic.ink", plugin.hostRouter.activeHost(http.routeKey))

        // Second call within 10-min lease uses 18comic.ink directly without re-probing jmcomic-zzz.one
        val beforeCount = http.recordedRequests.size
        now += 5 * 60 * 1000L
        plugin.feedSource!!.loadFeed(FeedRequest(SiteId.JMCOMIC, page = 1))
        val newRequests = http.recordedRequests.drop(beforeCount)
        assertEquals(1, newRequests.size)
        assertTrue(newRequests.single().url.startsWith("https://18comic.ink/"))

        // Changing routeKey invalidates lease immediately and re-probes
        http.routeKey = "route-wifi-2"
        val beforeRouteChange = http.recordedRequests.size
        plugin.feedSource!!.loadFeed(FeedRequest(SiteId.JMCOMIC, page = 1))
        assertTrue(http.recordedRequests.drop(beforeRouteChange).any { it.url.startsWith("https://jmcomic-zzz.one/") })

        // Advancing clock past 10-minute lease expires lease and re-probes
        now += 11 * 60 * 1000L
        val beforeExpiry = http.recordedRequests.size
        plugin.feedSource!!.loadFeed(FeedRequest(SiteId.JMCOMIC, page = 1))
        assertTrue(http.recordedRequests.drop(beforeExpiry).any { it.url.startsWith("https://jmcomic-zzz.one/") })
    }

    @Test
    fun `jmcomic multi-chapter album, chapters feed, strip math unscrambler, and link resolver work`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url == "https://jmcomic-zzz.one/albums?o=mr" }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, jmCatalogueHtml("jmcomic-zzz.one"))
        }
        http.onMatch({ req -> req.url == "https://jmcomic-zzz.one/album/500001" }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, jmAlbumHtml())
        }
        http.onMatch({ req -> req.url == "https://jmcomic-zzz.one/photo/500010" }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, jmChapterHtml(500010L, 220980L))
        }

        val plugin = JmcomicSitePlugin(http)
        assertSame(JmcomicStripMath, plugin.imageUnscrambler)

        // Load multi-chapter album detail
        val detail = plugin.gallerySource!!.loadGalleryDetail("https://18comic.ink/album/500001/title")
        assertEquals("500001", detail.item.id)
        assertEquals("https://jmcomic-zzz.one/album/500001#chapters", detail.chaptersUrl)
        assertEquals(2, detail.pageCount)

        // Page 1 is scrambled with exact JmcomicStripMath stripCount and unscrambleSeed
        val expectedStrips = JmcomicStripMath.computeStripCount(500010L, 220980L, "00001")
        assertTrue(expectedStrips >= 2)
        assertEquals(expectedStrips, detail.pages[0].stripCount)
        assertEquals("500010:220980:00001", detail.pages[0].unscrambleSeed)

        // Page 2 is a .gif -> never scrambled
        assertEquals(0, detail.pages[1].stripCount)
        assertNull(detail.pages[1].unscrambleSeed)

        // Resolve #chapters link and load chapters feed
        val resolver = plugin.linkResolver!!
        assertEquals(
            "https://jmcomic-zzz.one/album/500001#chapters",
            resolver.canonicalize("https://18comic.vip/album/500001#chapters"),
        )
        val chaptersLink = resolver.resolve("https://18comic.vip/album/500001#chapters") as ResolvedLink.Feed
        val chapterFeed = plugin.feedSource!!.loadFeed(chaptersLink.request)
        assertEquals(2, chapterFeed.items.size)
        assertEquals("chapter-500010", chapterFeed.items[0].id)
        assertEquals("chapter-500011", chapterFeed.items[1].id)
        assertEquals("https://jmcomic-zzz.one/photo/500011", chapterFeed.items[1].url)

        // Resolve individual page link
        val pageLink = resolver.resolve("https://jmcomic-zzz.org/photo/500010#1")
        assertEquals(
            ResolvedLink.Gallery(SiteId.JMCOMIC, "chapter-500010", "https://jmcomic-zzz.one/photo/500010#1", pageIndex = 1),
            pageLink,
        )
    }

    // -------------------------------------------------------------------------
    // Koin comicSitesModule & SiteRegistry Integration Test
    // -------------------------------------------------------------------------

    @Test
    fun `comicSitesModule binds all 12 comic and serial-comic plugins and registers cleanly in SiteRegistry`() {
        val fakeHttp = FakeHttpClientAdapter()
        val fakeStore = InMemorySiteSessionStore()
        val testDepsModule = module {
            single<HttpClientAdapter> { fakeHttp }
            single<SiteSessionStore> { fakeStore }
        }

        val koinApp = koinApplication {
            modules(testDepsModule, comicSitesModule)
        }
        try {
            val plugins = koinApp.koin.getAll<SitePlugin>()
            assertEquals(12, plugins.size)
            assertEquals(
                (SiteId.COMIC_SITES + SiteId.SERIAL_COMIC_SITES).toSet(),
                plugins.map { it.id }.toSet(),
            )

            val registry = SiteRegistry(plugins)
            assertEquals(12, registry.size)
            assertNotNull(registry.resolveLinkFromText("Look at https://e-hentai.org/g/101/abcd1234/"))
            assertNotNull(registry.resolveLinkFromText("Secret https://exhentai.org/g/202/abcd1234/"))
            assertNotNull(registry.resolveLinkFromText("Read https://nhentai.net/g/303/"))
            assertNotNull(registry.resolveLinkFromText("View https://hitomi.la/galleries/404.html"))
            assertNotNull(registry.resolveLinkFromText("Open https://www.wnacg.com/photos-index-aid-505.html"))
            assertNotNull(registry.resolveLinkFromText("Chapters https://18comic.ink/album/606#chapters"))
            assertNotNull(registry.resolveLinkFromText("Book https://hdoujin.org/book/12345/abcdef123456"))
            assertNotNull(registry.resolveLinkFromText("Comic https://www.mangacopy.com/comic/abcdefg"))
            assertNotNull(registry.resolveLinkFromText("Comic https://www.guazimanhua.com/comic.php?id=18741"))
            assertNotNull(registry.resolveLinkFromText("Comic https://mh160mh.com/comic/1234"))
            assertNotNull(registry.resolveLinkFromText("Comic https://mycomic.com/comic/1234"))
            assertNotNull(registry.resolveLinkFromText("Comic https://m.hipmh.com/works/demo-slug"))
        } finally {
            koinApp.close()
        }
    }

    @Test
    fun `jmcomic rejects unauthorized cross-resource redirect`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.endsWith("/albums?o=mr") }) { req ->
            SiteHttpResponse(
                code = 302,
                finalUrl = req.url,
                headers = mapOf("Location" to listOf("https://evil.example.com/phishing")),
            )
        }
        val plugin = JmcomicSitePlugin(http)
        try {
            plugin.feedSource!!.loadFeed(FeedRequest(SiteId.JMCOMIC))
            fail("Expected SiteException.Network when all mirrors fail with invalid redirect")
        } catch (_: SiteException.Network) {
            // expected
        }
    }

    @Test
    fun `jmcomic search wires o=mr, mv, tf, mp sort params, parses links, and advertises supportedSearchSorts`() {
        val plugin = JmcomicSitePlugin(FakeHttpClientAdapter())
        assertEquals(
            listOf(FeedSort.RELEVANCE, FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.LIKES, FeedSort.POPULAR),
            plugin.searchSource.supportedSearchSorts(SearchScope.ALL),
        )
        assertEquals(
            "https://jmcomic-zzz.one/search/photos?search_query=elf",
            plugin.resolveFeedUrl(FeedRequest(SiteId.JMCOMIC, query = "elf", sort = FeedSort.RELEVANCE)),
        )
        assertEquals(
            "https://jmcomic-zzz.one/search/photos?search_query=elf&o=mr",
            plugin.resolveFeedUrl(FeedRequest(SiteId.JMCOMIC, query = "elf", sort = FeedSort.NEWEST)),
        )
        assertEquals(
            "https://jmcomic-zzz.one/search/photos?search_query=elf&o=mv",
            plugin.resolveFeedUrl(FeedRequest(SiteId.JMCOMIC, query = "elf", sort = FeedSort.VIEWS)),
        )
        assertEquals(
            "https://jmcomic-zzz.one/search/photos?search_query=elf&o=tf",
            plugin.resolveFeedUrl(FeedRequest(SiteId.JMCOMIC, query = "elf", sort = FeedSort.LIKES)),
        )
        assertEquals(
            "https://jmcomic-zzz.one/search/photos?search_query=elf&o=mp&page=2",
            plugin.resolveFeedUrl(FeedRequest(SiteId.JMCOMIC, query = "elf", sort = FeedSort.POPULAR, page = 2)),
        )

        val parsedLikes = plugin.parseFeedLink(
            ComicUrl.requireParse("https://jmcomic-zzz.one/search/photos?search_query=elf&o=tf&page=3"),
        )
        assertNotNull(parsedLikes)
        assertEquals(FeedSort.LIKES, parsedLikes!!.sort)
        assertEquals(3, parsedLikes.page)
    }

    @Test
    fun `jmcomic accepts bare catalogue links and numbered org cdn hosts while wnacg accepts dynamic tN wnimg1 hosts`() = runTest {
        val jm = JmcomicSitePlugin(FakeHttpClientAdapter())
        val bareCatalogue = jm.parseFeedLink(ComicUrl.requireParse("https://jmcomic-zzz.one/albums?page=2"))
        assertEquals(FeedRequest(SiteId.JMCOMIC, section = "latest", sort = FeedSort.NEWEST, page = 2), bareCatalogue)
        val bareCat = jm.parseFeedLink(ComicUrl.requireParse("https://jmcomic-zzz.one/albums/hanman"))
        assertEquals(FeedRequest(SiteId.JMCOMIC, category = "hanman", sort = FeedSort.NEWEST, page = 1), bareCat)

        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.endsWith("/albums?o=mr") }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                jmCatalogueHtml("jmcomic-zzz.one").replace(
                    "https://cdn-msp.18comic.ink/media/albums/500001_3x4.jpg",
                    "https://cdn-msp9.jmcomic-zzz.org/media/albums/500001_3x4.jpg",
                ),
            )
        }
        val jmWithDynamicCdn = JmcomicSitePlugin(http)
        val feed = jmWithDynamicCdn.feedSource!!.loadFeed(FeedRequest(SiteId.JMCOMIC))
        assertEquals("https://cdn-msp9.jmcomic-zzz.org/media/albums/500001_3x4.jpg", feed.items.single().coverUrl)

        val wnacgHttp = FakeHttpClientAdapter()
        wnacgHttp.onMatch({ req -> req.url.endsWith("/albums-index-page-1.html") }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                wnacgFeedHtml(listOf(909 to "Sample")).replace("//t2.wnimg1.ru/data/t/00/909.jpg", "//t7.wnimg1.ru/data/cover/909.jpg"),
            )
        }
        val wnacg = WnacgSitePlugin(wnacgHttp)
        val wFeed = wnacg.feedSource!!.loadFeed(FeedRequest(SiteId.WNACG))
        assertEquals("https://t7.wnimg1.ru/data/cover/909.jpg", wFeed.items.single().coverUrl)
    }
}
