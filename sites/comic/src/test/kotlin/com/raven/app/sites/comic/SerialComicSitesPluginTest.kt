package com.raven.app.sites.comic

import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteSessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class SerialComicSitesPluginTest {

    // -------------------------------------------------------------------------
    // 瓜子漫画
    // -------------------------------------------------------------------------

    private val guaziUpdateHtml = """
        <html><body>
          <section class="mobile-update-grid">
            <article class="mobile-update-card">
              <a class="mobile-update-cover" href="/comic.php?id=28873">
                <img src="https://img.guazicdn.com//dm5/comics/cover/33770/261112/cover.jpg" alt="封面">
              </a>
              <h2><a href="/comic.php?id=28873">星武神诀</a></h2>
              <p><a href="/chapter.php?id=2584980">2月5日公告</a></p>
            </article>
            <article class="mobile-update-card">
              <a class="mobile-update-cover" href="/comic.php?id=18741">
                <img src="https://img.guazicdn.com/th/comics/cover/16118/260421/cover.webp" alt="封面">
              </a>
              <h2><a href="/comic.php?id=18741">重生水浒世界之最强武大郎</a></h2>
            </article>
          </section>
        </body></html>
    """.trimIndent()

    private val guaziDetailHtml = """
        <html><head>
          <meta property="og:image" content="https://img.guazicdn.com/th/comics/cover/16118/260421/cover.webp">
          <script type="application/ld+json">
          {"@context":"https://schema.org","@graph":[
            {"@type":"ComicStory","name":"重生水浒世界之最强武大郎","image":"https://img.guazicdn.com/th/comics/cover/16118/260421/cover.webp"},
            {"@type":"ItemList","name":"章节目录","numberOfItems":2,"itemListElement":[
              {"@type":"ListItem","position":1,"url":"https://www.guazimanhua.com/chapter.php?id=954976","name":"0 预告"},
              {"@type":"ListItem","position":2,"url":"https://www.guazimanhua.com/chapter.php?id=955039","name":"1 我武植重生了"}
            ]}
          ]}
          </script>
        </head><body>
          <h1>重生水浒世界之最强武大郎</h1>
        </body></html>
    """.trimIndent()

    private val guaziChapterHtml = """
        <html><body>
          <div class="reader-images">
            <img src="https://img.guazicdn.com/th/comics/chapters/16118/260421/1_1.webp">
            <img src="https://img.guazicdn.com/th/comics/chapters/16118/260421/1_2.webp">
            <img src="https://img.guazicdn.com/th/comics/chapters/16118/260421/1_3.webp">
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `guazimanhua parses feed detail chapters and page images`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://www.guazimanhua.com/update.php", guaziUpdateHtml)
        http.onUrlText("https://www.guazimanhua.com/update.php?page=2", guaziUpdateHtml)
        http.onUrlText("https://www.guazimanhua.com/comic.php?id=18741", guaziDetailHtml)
        http.onUrlText("https://www.guazimanhua.com/chapter.php?id=954976", guaziChapterHtml)

        val plugin = GuazimanhuaSitePlugin(http)
        val feed = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.GUAZIMANHUA))
        assertEquals(2, feed.items.size)
        assertEquals("28873", feed.items.first().id)
        assertEquals("星武神诀", feed.items.first().title)
        assertEquals(
            "https://img.guazicdn.com//dm5/comics/cover/33770/261112/cover.jpg",
            feed.items.first().coverUrl,
        )

        val detail = plugin.gallerySource.loadGalleryDetail("https://www.guazimanhua.com/comic.php?id=18741")
        assertEquals("重生水浒世界之最强武大郎", detail.item.title)
        assertEquals(3, detail.pageCount)
        assertEquals(3, detail.pages.size)
        assertEquals("https://img.guazicdn.com/th/comics/chapters/16118/260421/1_1.webp", detail.pages.first().imageUrl)
        assertEquals("https://www.guazimanhua.com/comic.php?id=18741#chapters", detail.chaptersUrl)
        assertEquals("954976", detail.item.extra["chapterId"])

        val chapterFeed = plugin.feedSource.loadFeed(
            FeedRequest(siteId = SiteId.GUAZIMANHUA, section = "chapters", category = "18741"),
        )
        assertEquals(2, chapterFeed.items.size)
        assertEquals("18741:954976", chapterFeed.items.first().id)
        assertEquals("0 预告", chapterFeed.items.first().title)
        assertEquals("https://www.guazimanhua.com/chapter.php?id=954976", chapterFeed.items.first().url)

        val page = plugin.gallerySource.resolvePageImage(
            "https://www.guazimanhua.com/chapter.php?id=954976#2",
            preferOriginal = false,
            forceReload = true,
        )
        assertEquals(2, page.index)
        assertEquals("https://img.guazicdn.com/th/comics/chapters/16118/260421/1_2.webp", page.imageUrl)

        val resolved = plugin.linkResolver.resolve("https://www.guazimanhua.com/comic.php?id=18741&from=home")
        assertTrue(resolved is ResolvedLink.Gallery)
        assertEquals("18741", (resolved as ResolvedLink.Gallery).id)
        assertNull(plugin.linkResolver.resolve("https://unrelated-domain.invalid/comic.php?id=1"))
    }

    // -------------------------------------------------------------------------
    // HDoujin
    // -------------------------------------------------------------------------

    private val hdoujinIndexJson = """
        {
          "popular": [
            {"id": 245209, "key": "abcdef123456", "title": "Popular Book", "pages": 87,
             "thumbnail": {"path": "https://erocdn.net/books/thumb/525575/x.webp"}}
          ],
          "doujinshi": [
            {"id": 245957, "key": "fedcba654321", "title": "Doujin Book", "pages": 26,
             "thumbnail": {"path": "https://erocdn.net/books/thumb/526935/y.webp"}}
          ],
          "manga": [
            {"id": 245933, "key": "112233445566", "title": "Manga Book", "pages": 28,
             "thumbnail": {"path": "https://erocdn.net/books/thumb/526891/z.webp"}}
          ],
          "removed": []
        }
    """.trimIndent()

    private val hdoujinDetailJson = """
        {
          "id": 245209,
          "key": "abcdef123456",
          "title": "Popular Book",
          "subtitle": "副标题",
          "pages": 87,
          "thumbnail": {"path": "https://erocdn.net/books/thumb/525575/x.webp"}
        }
    """.trimIndent()

    @Test
    fun `hdoujin parses home index and detail metadata`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://api.hdoujin.org/books/index", hdoujinIndexJson)
        http.onUrlText("https://api.hdoujin.org/books/detail/245209/abcdef123456/", hdoujinDetailJson)

        val plugin = HdoujinSitePlugin(http)
        val home = plugin.feedSource.loadHome(com.raven.app.core.model.ComicLanguage.ALL)
        assertEquals(3, home.sections.size)
        assertEquals(1, home.heroItems.size)
        assertEquals("245209/abcdef123456", home.heroItems.first().id)
        assertEquals("https://hdoujin.org/book/245209/abcdef123456", home.heroItems.first().url)

        val feed = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.HDOUJIN, section = "doujinshi"))
        assertEquals("Doujin Book", feed.items.single().title)

        val detail = plugin.gallerySource.loadGalleryDetail("https://hdoujin.org/book/245209/abcdef123456")
        assertEquals("Popular Book", detail.item.title)
        assertEquals("副标题", detail.description)
        assertEquals(87, detail.pageCount)

        try {
            plugin.gallerySource.loadGalleryPage(detail, 1, forceReload = false)
            fail("Expected AuthRequired because HDoujin reading requires clearance")
        } catch (expected: SiteException.AuthRequired) {
            assertTrue(expected.message.orEmpty().contains("登录"))
        }

        val resolved = plugin.linkResolver.resolve("https://hdoujin.org/book/245209/abcdef123456?share=1")
        assertTrue(resolved is ResolvedLink.Gallery)
        assertNull(plugin.linkResolver.resolve("https://unrelated-domain.invalid/book/1/2"))
    }

    // -------------------------------------------------------------------------
    // 拷贝漫画
    // -------------------------------------------------------------------------

    private val mangacopyNewestJson = """
        {
          "code": 200,
          "results": {
            "limit": 21,
            "offset": 0,
            "list": [
              {"name": "上篇", "comic": {"name": "戰火硝煙下的海拉爾", "path_word": "zhanhuoxiaoyanxiadehailaer",
                "cover": "https://sz.mangafunb.fun/z/cover.jpg", "popular": 123}},
              {"name": "第1话", "comic": {"name": "第二個神之子", "path_word": "degszz",
                "cover": "https://sd.mangafunb.fun/d/cover.jpg", "popular": 125}}
            ]
          }
        }
    """.trimIndent()

    private val mangacopySearchJson = """
        {
          "code": 200,
          "results": {
            "limit": 21,
            "offset": 0,
            "list": [
              {"name": "海賊王", "path_word": "haizeiwang", "cover": "https://sd.mangafunb.fun/h/cover.jpg"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `mangacopy parses newest feed and search results`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.startsWith("https://www.mangacopy.com/api/v3/update/newest") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, mangacopyNewestJson)
        }
        http.onMatch({ req -> req.url.startsWith("https://www.mangacopy.com/api/v3/search/comic") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, mangacopySearchJson)
        }

        val plugin = MangacopySitePlugin(http)
        val feed = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.MANGACOPY))
        assertEquals(2, feed.items.size)
        assertEquals("zhanhuoxiaoyanxiadehailaer", feed.items.first().id)
        assertEquals("戰火硝煙下的海拉爾", feed.items.first().title)
        assertEquals("https://www.mangacopy.com/comic/zhanhuoxiaoyanxiadehailaer", feed.items.first().url)

        val search = plugin.searchSource.search(FeedRequest(siteId = SiteId.MANGACOPY, query = "海賊王"))
        assertEquals("海賊王", search.items.single().title)

        val resolved = plugin.linkResolver.resolve("https://www.mangacopy.com/comic/haizeiwang?from=share")
        assertTrue(resolved is ResolvedLink.Gallery)
        assertNull(plugin.linkResolver.resolve("https://unrelated-domain.invalid/comic/x"))
    }

    // -------------------------------------------------------------------------
    // 嬉皮漫畫
    // -------------------------------------------------------------------------

    private val hipmhHomeHtml = """
        <html><body>
          <main>
            <a href="/works/bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236" title="登录武林系统">
              <img src="/kk/vertical/deng-lu-wu-lin-xi-tong-7236.webp">
            </a>
            <a href="/works/bToxNDE1Nw-shi-shi-ji-ling-di-she-ji-shi-14149" title="七十世纪灵地射击师">
              <img src="/kk/vertical/shi-shi-ji.webp">
            </a>
          </main>
        </body></html>
    """.trimIndent()

    private val hipmhWorkHtml = """
        <html><head>
          <title>登录武林系统 - 嬉皮漫畫</title>
          <meta property="og:image" content="/kk/vertical/deng-lu-wu-lin-xi-tong-7236.webp">
        </head><body>
          <h1>登录武林系统</h1>
          <a href="/chapter/go?hid=bTo3MjQ0LWM6MTM3MjE&m=7244">第1话 祝您武运昌隆</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `hipmh parses works list and work detail`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://m.hipmh.com/", hipmhHomeHtml)
        http.onUrlText(
            "https://m.hipmh.com/works/bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236",
            hipmhWorkHtml,
        )

        val plugin = HipmhSitePlugin(http)
        val feed = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.HIPMH))
        assertEquals(2, feed.items.size)
        assertEquals("bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236", feed.items.first().id)
        assertEquals("登录武林系统", feed.items.first().title)

        val detail = plugin.gallerySource.loadGalleryDetail(
            "https://m.hipmh.com/works/bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236",
        )
        assertEquals("登录武林系统", detail.item.title)
        assertEquals(
            "https://m.hipmh.com/kk/vertical/deng-lu-wu-lin-xi-tong-7236.webp",
            detail.item.coverUrl,
        )
        assertTrue(detail.item.extra["chapterHref"].orEmpty().startsWith("/chapter/go?"))

        val resolved = plugin.linkResolver.resolve("https://m.hipmh.com/works/bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236?x=1")
        assertTrue(resolved is ResolvedLink.Gallery)
        assertNull(plugin.linkResolver.resolve("https://unrelated-domain.invalid/works/x"))
    }

    // -------------------------------------------------------------------------
    // Koin / 注册
    // -------------------------------------------------------------------------

    @Test
    fun `serial comic plugins register through comicSitesModule and reject foreign links`() {
        val fakeHttp = FakeHttpClientAdapter()
        val fakeStore = InMemorySiteSessionStore()
        val koinApp = koinApplication {
            modules(
                module {
                    single<HttpClientAdapter> { fakeHttp }
                    single<SiteSessionStore> { fakeStore }
                },
                comicSitesModule,
            )
        }
        try {
            val plugins = koinApp.koin.getAll<SitePlugin>()
            val ids = plugins.map { it.id }.toSet()
            for (siteId in SiteId.SERIAL_COMIC_SITES) {
                assertTrue("Missing plugin for $siteId", siteId in ids)
            }

            val registry = SiteRegistry(plugins)
            val foreign = "https://unrelated-domain.invalid/item/123"
            for (siteId in SiteId.SERIAL_COMIC_SITES) {
                val plugin = registry.require(siteId)
                assertTrue("$siteId must expose feedSource", plugin.feedSource != null)
                assertTrue("$siteId must expose gallerySource", plugin.gallerySource != null)
                val resolver = plugin.linkResolver
                assertNotNull("$siteId must expose linkResolver", resolver)
                assertFalse("$siteId accepted foreign URL", resolver!!.canHandle(foreign))
                assertNull("$siteId resolved foreign URL", resolver.resolve(foreign))
            }

            assertNotNull(registry.resolveLinkFromText("https://www.guazimanhua.com/comic.php?id=18741"))
            assertNotNull(registry.resolveLinkFromText("https://www.mangacopy.com/comic/haizeiwang"))
            assertNotNull(registry.resolveLinkFromText("https://m.hipmh.com/works/bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236"))
            assertNotNull(registry.resolveLinkFromText("https://hdoujin.org/book/245209/abcdef123456"))
        } finally {
            koinApp.close()
        }
    }
}