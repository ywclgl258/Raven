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
        http.onUrlText("https://www.guazimanhua.com/category.php?sort=update&page=1", guaziUpdateHtml)
        http.onUrlText("https://www.guazimanhua.com/category.php?sort=update&page=2", guaziUpdateHtml)
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

        // 分类维度：/category.php?cid=25&sort=hits
        http.onUrlText("https://www.guazimanhua.com/category.php?cid=25&sort=hits&page=1", guaziUpdateHtml)
        val categoryFeed = plugin.feedSource.loadFeed(
            FeedRequest(
                siteId = SiteId.GUAZIMANHUA,
                section = "cid_25",
                sort = com.raven.app.core.model.FeedSort.POPULAR,
            ),
        )
        assertEquals(2, categoryFeed.items.size)
        assertTrue(http.recordedRequests.last().url.endsWith("/category.php?cid=25&sort=hits&page=1"))
        assertTrue(
            plugin.descriptor.sections.any { it.id == "cid_25" && it.title == "玄幻" },
        )

        val chapterFeed = plugin.feedSource.loadFeed(
            FeedRequest(siteId = SiteId.GUAZIMANHUA, section = "chapters", category = "18741"),
        )
        assertEquals(2, chapterFeed.items.size)
        assertEquals("18741:954976", chapterFeed.items.first().id)
        assertEquals("0 预告", chapterFeed.items.first().title)
        assertEquals("https://www.guazimanhua.com/chapter.php?id=954976", chapterFeed.items.first().url)

        val chapterDetail = plugin.gallerySource.loadGalleryDetail(
            "https://www.guazimanhua.com/chapter.php?id=954976",
        )
        assertEquals(3, chapterDetail.pageCount)
        assertEquals("https://img.guazicdn.com/th/comics/chapters/16118/260421/1_1.webp", chapterDetail.pages.first().imageUrl)

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

        http.onUrlText(
            "https://api.hdoujin.org/books?q=test",
            """{"entries":[{"id":245982,"key":"67290554d85b","title":"Search Hit","pages":30,
               "thumbnail":{"path":"https://erocdn.net/books/thumb/1/s.webp"}}],"limit":25,"page":1,"total":1}""",
        )
        val search = plugin.searchSource.search(FeedRequest(siteId = SiteId.HDOUJIN, query = "test"))
        assertEquals("Search Hit", search.items.single().title)
        assertEquals("245982/67290554d85b", search.items.single().id)
        assertFalse(search.hasMore)
        assertNull(search.nextPage)

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
            "limit": 30,
            "offset": 0,
            "total": 2,
            "list": [
              {"name": "戰火硝煙下的海拉爾", "path_word": "zhanhuoxiaoyanxiadehailaer",
               "cover": "https://sz.mangafunb.fun/z/cover.jpg.328x422.jpg"},
              {"name": "第二個神之子", "path_word": "degszz",
               "cover": "https://sd.mangafunb.fun/d/cover.jpg.328x422.jpg"}
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

    private val mangacopyDetailJson = """
        {
          "code": 200,
          "results": {
            "comic": {"name": "戰火硝煙下的海拉爾", "path_word": "zhanhuoxiaoyanxiadehailaer",
                      "cover": "https://sz.mangafunb.fun/z/cover.jpg.328x422.jpg", "brief": "简介"},
            "groups": {"default": {"path_word": "default", "name": "默認", "count": 1}}
          }
        }
    """.trimIndent()

    private val mangacopyChaptersJson = """
        {
          "code": 200,
          "results": {
            "list": [
              {"uuid": "9db4712d-e89f-11f1-b027-fa163e02432f", "name": "上篇",
               "comic_path_word": "zhanhuoxiaoyanxiadehailaer"}
            ],
            "total": 1,
            "limit": 500,
            "offset": 0
          }
        }
    """.trimIndent()

    private fun mangacopyChapterHtml(): String {
        val key = "0p0zzpvv.nmn.oop"
        val iv = "abcdefghijklmnop"
        val plain = """[{"url":"https://sz.mangafunb.fun/page/001.jpg"}]"""
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key.toByteArray(), "AES"),
            javax.crypto.spec.IvParameterSpec(iv.toByteArray()),
        )
        val encrypted = cipher.doFinal(plain.toByteArray())
        val contentKey = iv + encrypted.joinToString("") { "%02x".format(it) }
        return """
            <html><body><script>
              var cct = '$key';
              var contentKey = '$contentKey';
            </script></body></html>
        """.trimIndent()
    }

    @Test
    fun `mangacopy parses newest feed and search results`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.startsWith("https://api.copy4000.com/api/v3/comics") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, mangacopyNewestJson)
        }
        http.onMatch({ req -> req.url.startsWith("https://api.copy4000.com/api/v3/search/comic") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, mangacopySearchJson)
        }

        val plugin = MangacopySitePlugin(http)
        val feed = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.MANGACOPY))
        assertEquals(2, feed.items.size)
        assertEquals("zhanhuoxiaoyanxiadehailaer", feed.items.first().id)
        assertEquals("戰火硝煙下的海拉爾", feed.items.first().title)
        assertEquals("https://www.mangacopy.com/comic/zhanhuoxiaoyanxiadehailaer", feed.items.first().url)
        assertEquals("https://sz.mangafunb.fun/z/cover.jpg", feed.items.first().coverUrl)

        val search = plugin.searchSource.search(FeedRequest(siteId = SiteId.MANGACOPY, query = "海賊王"))
        assertEquals("海賊王", search.items.single().title)

        // 详情 + AES-CBC 章节解密
        http.onMatch({ req -> req.url.startsWith("https://api.copy4000.com/api/v3/comic2/zhanhuoxiaoyanxiadehailaer") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, mangacopyDetailJson)
        }
        http.onMatch({ req -> req.url.startsWith("https://api.copy4000.com/api/v3/comic/zhanhuoxiaoyanxiadehailaer/group/default/chapters") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, mangacopyChaptersJson)
        }
        http.onUrlText(
            "https://www.mangacopy.com/comic/zhanhuoxiaoyanxiadehailaer/chapter/9db4712d-e89f-11f1-b027-fa163e02432f",
            mangacopyChapterHtml(),
        )
        val detail = plugin.gallerySource.loadGalleryDetail(
            "https://www.mangacopy.com/comic/zhanhuoxiaoyanxiadehailaer",
        )
        assertEquals("戰火硝煙下的海拉爾", detail.item.title)
        assertEquals("https://sz.mangafunb.fun/z/cover.jpg", detail.item.coverUrl)
        assertEquals(1, detail.pageCount)
        assertEquals("https://sz.mangafunb.fun/page/001.jpg", detail.pages.single().imageUrl)

        val chapterFeed = plugin.feedSource.loadFeed(
            FeedRequest(siteId = SiteId.MANGACOPY, section = "chapters", category = "zhanhuoxiaoyanxiadehailaer"),
        )
        assertEquals(1, chapterFeed.items.size)
        assertEquals("上篇", chapterFeed.items.single().title)
        val chapterDetail = plugin.gallerySource.loadGalleryDetail(chapterFeed.items.single().url)
        assertEquals(1, chapterDetail.pageCount)
        assertEquals("https://sz.mangafunb.fun/page/001.jpg", chapterDetail.pages.single().imageUrl)

        val resolved = plugin.linkResolver.resolve("https://www.mangacopy.com/comic/haizeiwang?from=share")
        assertTrue(resolved is ResolvedLink.Gallery)
        assertNull(plugin.linkResolver.resolve("https://unrelated-domain.invalid/comic/x"))
    }

    // -------------------------------------------------------------------------
    // 漫画160
    // -------------------------------------------------------------------------

    private val mh160ListHtml = """
        <html><body>
          <div class="itemBox">
            <div class="itemImg"><img src="https://p.miyeye.cn/cover/26001.jpg"></div>
            <div class="itemTxt"><a class="title" href="/kanmanhua/26001/">苏生战铳</a></div>
          </div>
          <div class="itemBox">
            <div class="itemImg"><img src="https://p.miyeye.cn/cover/123.jpg"></div>
            <div class="itemTxt"><a class="title" href="/kanmanhua/123/">示例漫画</a></div>
          </div>
        </body></html>
    """.trimIndent()

    private val mh160DetailHtml = """
        <html><head>
          <title>苏生战铳漫画,苏生战铳漫画全集,苏生战铳漫画免费观看-漫画160</title>
          <meta property="og:image" content="https://p.miyeye.cn/cover/26001.jpg">
          <meta name="description" content="苏生战铳漫画简介">
        </head><body>
          <div class="chapters" id="chapterList_1">
            <ul class="list" id="chapterList_ul_1">
              <li><a href="/kanmanhua/26001/590566.html">第65话</a></li>
              <li><a href="/kanmanhua/26001/583501.html">第1话</a></li>
            </ul>
          </div>
        </body></html>
    """.trimIndent()

    private val mh160ChapterHtml = run {
        val payload = "/mh160tuku/s/苏生战铳_26001/第01话_583501/0001.jpg" +
            "\$qingtiandy\$" +
            "/mh160tuku/s/苏生战铳_26001/第01话_583501/0002.jpg"
        val encoded = java.util.Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        """
        <html><body>
          <script>
            var qTcms_S_p_id="583501";
            var qTcms_S_m_murl_e="$encoded";
          </script>
        </body></html>
        """.trimIndent()
    }

    @Test
    fun `mh160 parses mobile feed, detail chapters and encoded reader pages`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://m.mh160ba.com/kanmanhua/zaixian_recent.html", mh160ListHtml)
        http.onUrlText("https://www.mh160ba.com/kanmanhua/26001/", mh160DetailHtml)
        http.onUrlText("https://www.mh160ba.com/kanmanhua/26001/583501.html", mh160ChapterHtml)
        http.onUrlText("https://www.mh160ba.com/kanmanhua/26001/590566.html", mh160ChapterHtml)

        val plugin = Mh160SitePlugin(http)
        val feed = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.MH160))
        assertEquals(2, feed.items.size)
        assertEquals("26001", feed.items.first().id)
        assertEquals("苏生战铳", feed.items.first().title)
        assertEquals("https://p.miyeye.cn/cover/26001.jpg", feed.items.first().coverUrl)

        val detail = plugin.gallerySource.loadGalleryDetail("https://www.mh160ba.com/kanmanhua/26001/")
        assertEquals("苏生战铳", detail.item.title)
        assertEquals(2, detail.pageCount)
        assertEquals("https://www.mh160ba.com/kanmanhua/26001/583501.html", detail.item.extra["chapterUrl"])
        // 章节 id > 542724 时按 id % 5 选择 kingwar 镜像主机
        assertTrue(detail.pages.first().imageUrl.contains(".kingwar.cn/mh160tuku/"))
        assertTrue(detail.pages.first().imageUrl.endsWith("/0001.jpg"))

        val chapterFeed = plugin.feedSource.loadFeed(
            FeedRequest(siteId = SiteId.MH160, section = "chapters", category = "26001"),
        )
        assertEquals(2, chapterFeed.items.size)
        assertEquals("第1话", chapterFeed.items.first().title)
        val chapterDetail = plugin.gallerySource.loadGalleryDetail(chapterFeed.items.first().url)
        assertEquals(2, chapterDetail.pageCount)

        val page = plugin.gallerySource.resolvePageImage(
            "https://www.mh160ba.com/kanmanhua/26001/583501.html#2",
            preferOriginal = false,
            forceReload = true,
        )
        assertEquals(2, page.index)
        assertTrue(page.imageUrl.endsWith("/0002.jpg"))

        val resolved = plugin.linkResolver.resolve("https://www.mh160ba.com/kanmanhua/26001/")
        assertTrue(resolved is ResolvedLink.Gallery)
        assertEquals("26001", (resolved as ResolvedLink.Gallery).id)
        assertNull(plugin.linkResolver.resolve("https://unrelated-domain.invalid/kanmanhua/1/"))
    }

    // -------------------------------------------------------------------------
    // MyComic
    // -------------------------------------------------------------------------

    private val mycomicListHtml = """
        <html><body>
          <div class="list">
            <div class="item">
              <a href="/comics/1128">第07話</a>
              <a href="/comics/1128">ONE PIECE航海王</a>
              <img data-src="/covers/1128.webp">
            </div>
            <div class="item">
              <a href="/comics/55355">第01話</a>
              <a href="/comics/55355">獵人遊戲W</a>
            </div>
          </div>
        </body></html>
    """.trimIndent()

    private val mycomicDetailHtml = """
        <html><head>
          <meta property="og:title" content="ONE PIECE航海王">
          <meta property="og:image" content="https://cdn.mycomic.com/covers/1128.webp">
        </head><body>
          <a href="/chapters/817597">第07話</a>
          <a href="/chapters/794527">第06話</a>
        </body></html>
    """.trimIndent()

    private val mycomicChapterHtml = """
        <html><body>
          <img src="https://cdn.mycomic.com/static/logo.png">
          <img data-src="https://cdn.mycomic.com/manga/1128/1.jpg">
          <img data-src="https://cdn.mycomic.com/manga/1128/2.webp">
        </body></html>
    """.trimIndent()

    @Test
    fun `mycomic parses comics list, detail chapters and reader images`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://mycomic.com/comics?sort=-update", mycomicListHtml)
        http.onUrlText("https://mycomic.com/comics/1128", mycomicDetailHtml)
        http.onUrlText("https://mycomic.com/chapters/817597", mycomicChapterHtml)
        http.onUrlText("https://mycomic.com/chapters/794527", mycomicChapterHtml)

        val plugin = MycomicSitePlugin(http)
        val feed = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.MYCOMIC))
        assertEquals(2, feed.items.size)
        assertEquals("1128", feed.items.first().id)
        assertEquals("ONE PIECE航海王", feed.items.first().title)
        assertEquals("https://mycomic.com/covers/1128.webp", feed.items.first().coverUrl)

        val detail = plugin.gallerySource.loadGalleryDetail("https://mycomic.com/comics/1128")
        assertEquals("ONE PIECE航海王", detail.item.title)
        assertEquals(2, detail.pageCount)
        assertEquals("794527", detail.item.extra["chapterId"])
        assertEquals(
            listOf(
                "https://cdn.mycomic.com/manga/1128/1.jpg",
                "https://cdn.mycomic.com/manga/1128/2.webp",
            ),
            detail.pages.map { it.imageUrl },
        )
        assertTrue(detail.chaptersUrl.orEmpty().endsWith("#chapters"))

        val chapterFeed = plugin.feedSource.loadFeed(
            FeedRequest(siteId = SiteId.MYCOMIC, section = "chapters", category = "1128"),
        )
        assertEquals(2, chapterFeed.items.size)
        val chapterDetail = plugin.gallerySource.loadGalleryDetail(chapterFeed.items.last().url)
        assertEquals(2, chapterDetail.pageCount)
        assertEquals("https://cdn.mycomic.com/manga/1128/1.jpg", chapterDetail.pages.first().imageUrl)

        val page = plugin.gallerySource.resolvePageImage(
            "https://mycomic.com/chapters/817597#2",
            preferOriginal = false,
            forceReload = true,
        )
        assertEquals(2, page.index)
        assertEquals("https://cdn.mycomic.com/manga/1128/2.webp", page.imageUrl)

        val resolved = plugin.linkResolver.resolve("https://mycomic.com/comics/1128?from=list")
        assertTrue(resolved is ResolvedLink.Gallery)
        assertNull(plugin.linkResolver.resolve("https://unrelated-domain.invalid/comics/1"))
    }

    // -------------------------------------------------------------------------
    // 嬉皮漫畫
    // -------------------------------------------------------------------------

    private val hipmhListJson = """
        {
          "code": 200,
          "message": "ok",
          "data": {
            "page": 1,
            "per_page": 18,
            "total": 2,
            "total_pages": 1,
            "items": [
              {"mid": "bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236", "title": "登录武林系统",
               "vertical_image_url": "/kk/vertical/deng-lu-wu-lin-xi-tong-7236.webp"},
              {"mid": "bToxNDE1Nw-shi-shi-ji-ling-di-she-ji-shi-14149", "title": "七十世纪灵地射击师",
               "vertical_image_url": "/kk/vertical/shi-shi-ji.webp"}
            ]
          }
        }
    """.trimIndent()

    private val hipmhChaptersJson = """
        {
          "code": 200,
          "message": "ok",
          "data": {
            "items": [
              {"hid": "bTo3MjQ0LWM6MTM3MjE-NzI0NDoxLjAw", "title": "第1话 祝您武运昌隆"},
              {"hid": "bTo3MjQ0LWM6MjQwNzM-NzI0NDoyNTYuMDA", "title": "第三季 完结话"}
            ],
            "page": 1,
            "per_page": 200,
            "total": 2,
            "total_pages": 1
          }
        }
    """.trimIndent()

    private val hipmhChapterImagesJson = """
        {
          "code": 200,
          "message": "ok",
          "data": {
            "chapter_id": 13721,
            "chapter_number": 1,
            "images": [
              "https://hip-tx-1.s3imgs.top/kk/13721/001.jpg",
              "https://hip-tx-1.s3imgs.top/kk/13721/002.jpg"
            ],
            "line": 1
          }
        }
    """.trimIndent()

    private val hipmhWorkHtml = """
        <html><head>
          <title>登录武林系统 - 嬉皮漫畫</title>
          <meta property="og:image" content="/kk/vertical/deng-lu-wu-lin-xi-tong-7236.webp">
        </head><body>
          <h1>登录武林系统</h1>
        </body></html>
    """.trimIndent()

    @Test
    fun `hipmh parses api list and work detail`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.startsWith("https://hipapi1.s3file.top/v1/mangas") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, hipmhListJson)
        }
        http.onMatch({ req -> req.url.startsWith("https://hipapi1.s3file.top/v1/manga/chapters") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, hipmhChaptersJson)
        }
        http.onMatch({ req -> req.url.startsWith("https://hipapi1.s3file.top/v2/chapter") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, hipmhChapterImagesJson)
        }
        http.onUrlText(
            "https://m.hipmh.com/works/bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236",
            hipmhWorkHtml,
        )

        val plugin = HipmhSitePlugin(http)
        val feed = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.HIPMH))
        assertEquals(2, feed.items.size)
        assertEquals("bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236", feed.items.first().id)
        assertEquals("登录武林系统", feed.items.first().title)
        assertEquals(
            "https://cover.s3imgs.top/kk/vertical/deng-lu-wu-lin-xi-tong-7236.webp",
            feed.items.first().coverUrl,
        )

        val detail = plugin.gallerySource.loadGalleryDetail(
            "https://m.hipmh.com/works/bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236",
        )
        assertEquals("登录武林系统", detail.item.title)
        assertEquals(
            "https://cover.s3imgs.top/kk/vertical/deng-lu-wu-lin-xi-tong-7236.webp",
            detail.item.coverUrl,
        )
        assertEquals(2, detail.pageCount)
        assertEquals("bTo3MjQ0LWM6MTM3MjE-NzI0NDoxLjAw", detail.item.extra["chapterHid"])
        assertTrue(detail.chaptersUrl.orEmpty().endsWith("#chapters"))

        assertEquals(
            listOf(
                "https://hip-tx-1.s3imgs.top/kk/13721/001.jpg",
                "https://hip-tx-1.s3imgs.top/kk/13721/002.jpg",
            ),
            detail.pages.map { it.imageUrl },
        )
        val chapterFeed = plugin.feedSource.loadFeed(
            FeedRequest(siteId = SiteId.HIPMH, section = "chapters", category = "bTo3MjQ0-deng-lu-wu-lin-xi-tong-7236"),
        )
        assertEquals(2, chapterFeed.items.size)
        assertEquals("第1话 祝您武运昌隆", chapterFeed.items.first().title)
        val chapterDetail = plugin.gallerySource.loadGalleryDetail(
            "https://reader.hipmh.top/chapter/bTo3MjQ0LWM6MTM3MjE-NzI0NDoxLjAw",
        )
        assertEquals(2, chapterDetail.pageCount)
        assertEquals("https://hip-tx-1.s3imgs.top/kk/13721/001.jpg", chapterDetail.pages.first().imageUrl)

        val readerPage = plugin.gallerySource.resolvePageImage(
            "https://reader.hipmh.top/chapter/bTo3MjQ0LWM6MTM3MjE-NzI0NDoxLjAw#2",
            preferOriginal = false,
            forceReload = true,
        )
        assertEquals(2, readerPage.index)
        assertEquals("https://hip-tx-1.s3imgs.top/kk/13721/002.jpg", readerPage.imageUrl)

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

    @Test
    fun `mangacopy handles business code 210, chapter errors, canonical pageUrl, and malformed hex safely`() = runTest {
        val http = FakeHttpClientAdapter()
        val plugin = MangacopySitePlugin(http)

        // 1. Business code 210 maps to Forbidden(isChallenge = true) and propagates from chapter feed
        http.onUrlText(
            "https://api.copy4000.com/api/v3/comic2/demo?platform=1",
            """{"code":200,"results":{"comic":{"name":"Demo","path_word":"demo","cover":""},"groups":{"default":{"path_word":"default"}}}}""",
        )
        http.onUrlText(
            "https://api.copy4000.com/api/v3/comic/demo/group/default/chapters?limit=500&offset=0",
            """{"code":210,"message":"请下载官方最新客户端"}""",
        )
        val chapterErr = runCatching {
            plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.MANGACOPY, section = "chapters", category = "demo"))
        }.exceptionOrNull()
        assertTrue(chapterErr is SiteException.Forbidden)
        assertTrue((chapterErr as SiteException.Forbidden).isChallenge)

        // 2. Direct chapter detail preserves chaptersUrl even when chapter API returns 210
        val sampleAesHtml = """
            <html><body><script>
            var cct = '1234567890abcdef';
            var contentKey = 'abcdef1234567890ZZZZ';
            </script></body></html>
        """.trimIndent()
        http.onUrlText("https://www.mangacopy.com/comic/demo/chapter/ch-uuid-2", sampleAesHtml)
        val badHexErr = runCatching {
            plugin.gallerySource.loadGalleryDetail("https://www.mangacopy.com/comic/demo/chapter/ch-uuid-2")
        }.exceptionOrNull()
        // Malformed/short cipherText must map cleanly to SiteException.ParseError (never raw IllegalArgumentException)
        assertTrue(badHexErr is SiteException.ParseError)

        // 3. loadGalleryPage uses pure comicId (never pathWord:uuid) and returns canonical pageUrl resolvable by resolvePageImage
        val aesKey = "1234567890abcdef"
        val ivStr = "abcdef1234567890"
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(aesKey.toByteArray(Charsets.UTF_8), "AES"),
            javax.crypto.spec.IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8)),
        )
        val encryptedHex = cipher.doFinal("""[{"url":"https://img.copy.test/p1.webp"}]""".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val validChapterHtml = """
            <html><body><script>
            var cct = '$aesKey';
            var contentKey = '$ivStr$encryptedHex';
            </script></body></html>
        """.trimIndent()
        http.onUrlText("https://www.mangacopy.com/comic/demo/chapter/ch-uuid-2", validChapterHtml)

        val detail = plugin.gallerySource.loadGalleryDetail("https://www.mangacopy.com/comic/demo/chapter/ch-uuid-2")
        assertEquals("demo:ch-uuid-2", detail.item.id)
        assertEquals("https://www.mangacopy.com/comic/demo#chapters", detail.chaptersUrl)

        val reloaded = plugin.gallerySource.loadGalleryPage(detail, pageIndex = 1, forceReload = true)
        assertEquals("https://www.mangacopy.com/comic/demo/chapter/ch-uuid-2#1", reloaded.pageUrl)
        val resolved = plugin.gallerySource.resolvePageImage(reloaded.pageUrl)
        assertEquals("https://img.copy.test/p1.webp", resolved.imageUrl)
        assertEquals(reloaded.pageUrl, resolved.pageUrl)
    }

    @Test
    fun `serial comic edge cases cover mh160 categories, hdoujin pagination, and empty search handling`() = runTest {
        val http = FakeHttpClientAdapter()

        // 1. MH160 category (#listbody li) fallback
        val mh160 = Mh160SitePlugin(http)
        http.onUrlText(
            "https://m.mh160ba.com/kanmanhua/lianzai",
            """
            <html><body><ul id="listbody">
              <li><a class="ImgA" href="/kanmanhua/1001/"><img src="https://img.mh160.test/1001.jpg" /></a>
              <a href="/kanmanhua/1001/">Category Hit</a></li>
            </ul></body></html>
            """.trimIndent(),
        )
        val mhCat = mh160.feedSource.loadFeed(FeedRequest(siteId = SiteId.MH160, section = "cat_lianzai"))
        assertEquals(listOf("1001"), mhCat.items.map { it.id })

        // 2. HDoujin pagination & malformed JSON rejection
        val hdoujin = HdoujinSitePlugin(http)
        http.onUrlText(
            "https://api.hdoujin.org/books?q=multi",
            """{"entries":[{"id":1,"key":"k1","title":"Book 1"}],"limit":1,"page":1,"total":3}""",
        )
        val hdoujinPage = hdoujin.searchSource.search(FeedRequest(siteId = SiteId.HDOUJIN, query = "multi", page = 1))
        assertTrue(hdoujinPage.hasMore)
        assertEquals(2, hdoujinPage.nextPage)

        http.onUrlText("https://api.hdoujin.org/books?q=broken", "not-json")
        val badJsonErr = runCatching {
            hdoujin.searchSource.search(FeedRequest(siteId = SiteId.HDOUJIN, query = "broken"))
        }.exceptionOrNull()
        assertTrue(badJsonErr is SiteException.ParseError)

        // 3. Hipmh data:null -> ParseError, empty search -> empty FeedPage, chapter error propagated
        val hipmh = HipmhSitePlugin(http)
        http.onUrlText("https://hipapi1.s3file.top/v1/mangas?sort=updated&page=1&per_page=18", """{"code":500,"data":null}""")
        assertTrue(
            runCatching { hipmh.feedSource.loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "latest")) }
                .exceptionOrNull() is SiteException.ParseError,
        )
        http.onUrlText(
            "https://hipapi1.s3file.top/v1/mangas?keyword=none&page=1&per_page=18",
            """{"code":200,"data":{"items":[],"total":0,"total_pages":1}}""",
        )
        assertTrue(hipmh.searchSource.search(FeedRequest(siteId = SiteId.HIPMH, query = "none")).items.isEmpty())

        // 4. Guazimanhua empty search & non-primitive JSON-LD url object
        val guazi = GuazimanhuaSitePlugin(http)
        http.onUrlText("https://www.guazimanhua.com/category.php?keyword=none&sort=hits&page=1", "<html><body><div>无结果</div></body></html>")
        assertTrue(guazi.searchSource.search(FeedRequest(siteId = SiteId.GUAZIMANHUA, query = "none")).items.isEmpty())
        http.onUrlText(
            "https://www.guazimanhua.com/comic.php?id=999",
            """
            <html><body><h1>Safe Title</h1>
            <script type="application/ld+json">
              {"url":{"@id":"https://www.guazimanhua.com/comic.php?id=999"},"name":null}
            </script>
            <a href="/chapter.php?id=101">第1话</a></body></html>
            """.trimIndent(),
        )
        http.onUrlText("https://www.guazimanhua.com/chapter.php?id=101", """<html><body><img src="https://img.guazicdn.com/th/comics/chapters/1.jpg"/></body></html>""")
        val guaziDetail = guazi.gallerySource.loadGalleryDetail("https://www.guazimanhua.com/comic.php?id=999")
        assertEquals("Safe Title", guaziDetail.item.title)

        // 5. Mycomic empty search returns empty FeedPage
        val mycomic = MycomicSitePlugin(http)
        http.onUrlText("https://mycomic.com/comics?q=none&page=1", "<html><body><p>No comics found</p></body></html>")
        assertTrue(mycomic.searchSource.search(FeedRequest(siteId = SiteId.MYCOMIC, query = "none")).items.isEmpty())
    }

    @Test
    fun `single point checks for hdoujin security, mangacopy root url, mh160 single fetch, guazi empty id, mycomic fallback, and hipmh decode`() = runTest {
        // 1. HDoujin Origin/Referer headers, foreign/traversal guard, resolvePageImage AuthRequired
        val hHttp = FakeHttpClientAdapter()
        hHttp.onUrlText("https://api.hdoujin.org/books/detail/10/key1/", """{"id":10,"key":"key1","title":"OK"}""")
        val hdoujin = HdoujinSitePlugin(hHttp)
        hdoujin.gallerySource.loadGalleryDetail("https://hdoujin.org/book/10/key1")
        val apiReq = hHttp.recordedRequests.last()
        assertEquals("https://hdoujin.org", apiReq.headers["Origin"])
        assertEquals("https://hdoujin.org/", apiReq.headers["Referer"])
        assertTrue(runCatching { hdoujin.gallerySource.loadGalleryDetail("https://evil.example/book/10/key1") }.exceptionOrNull() is SiteException.ParseError)
        assertTrue(runCatching { hdoujin.gallerySource.loadGalleryDetail("https://hdoujin.org/book/10/bad%2Fkey") }.exceptionOrNull() is SiteException.ParseError)
        assertTrue(runCatching { hdoujin.gallerySource.resolvePageImage("https://hdoujin.org/book/10/key1#1") }.exceptionOrNull() is SiteException.AuthRequired)

        // 2. Mangacopy preserves root comic URL & deduplicates multi-group UUIDs
        val mcHttp = FakeHttpClientAdapter()
        mcHttp.onMatch({ req -> req.url.contains("/comic2/demo") }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """{"code":200,"results":{"comic":{"name":"Demo","path_word":"demo","cover":"https://img.copy.test/c.jpg"},"groups":{"g1":{"path_word":"default"},"g2":{"path_word":"tank"}}}}""",
            )
        }
        mcHttp.onMatch({ req -> req.url.contains("/group/default/chapters") || req.url.contains("/group/tank/chapters") }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """{"code":200,"results":{"list":[{"uuid":"dup-uuid-1","name":"Ch 1"}]}}""",
            )
        }
        mcHttp.onUrlText("https://www.mangacopy.com/comic/demo/chapter/dup-uuid-1", mangacopyChapterHtml())
        val mc = MangacopySitePlugin(mcHttp)
        val mcRoot = mc.gallerySource.loadGalleryDetail("https://www.mangacopy.com/comic/demo")
        assertEquals("https://www.mangacopy.com/comic/demo", mcRoot.item.url)
        assertEquals("dup-uuid-1", mcRoot.item.extra["chapterUuid"])
        val mcChapters = mc.feedSource.loadFeed(FeedRequest(siteId = SiteId.MANGACOPY, section = "chapters", category = "demo"))
        assertEquals(1, mcChapters.items.size)

        // 3. MH160 root chapterId, single chapter fetch, parseSearchList title, linkResolver non-numeric guard, legacy host & +http Unsupported
        val mhHttp = FakeHttpClientAdapter()
        val legacyPayload = java.util.Base64.getEncoder().encodeToString("/mh/1.jpg".toByteArray())
        val removedPayload = java.util.Base64.getEncoder().encodeToString("+http://removed.invalid/x.jpg".toByteArray())
        mhHttp.onUrlText("https://www.mh160ba.com/kanmanhua/26001/", mh160DetailHtml)
        mhHttp.onUrlText(
            "https://www.mh160ba.com/kanmanhua/26001/583501.html",
            """<html><head><title>第1话</title></head><body><script>var qTcms_S_p_id="100";var qTcms_S_m_murl_e="$legacyPayload";</script></body></html>""",
        )
        mhHttp.onUrlText(
            "https://www.mh160ba.com/kanmanhua/26001/590566.html",
            """<html><head><title>第65话</title></head><body><script>var qTcms_S_p_id="100";var qTcms_S_m_murl_e="$removedPayload";</script></body></html>""",
        )
        mhHttp.onUrlText(
            "https://m.mh160ba.com/kanmanhua/lianzai",
            """<html><body><ul id="listbody"><li><a class="ImgA" href="/kanmanhua/1001/"><img src="https://img.mh160.test/1.jpg" /></a><a href="/kanmanhua/1001/">Category Hit</a></li></ul></body></html>""",
        )
        val mh = Mh160SitePlugin(mhHttp)
        val mhRoot = mh.gallerySource.loadGalleryDetail("https://www.mh160ba.com/kanmanhua/26001/")
        assertEquals("583501", mhRoot.item.extra["chapterId"])
        assertEquals("https://mhpic6.kingwar.cn/mh/1.jpg", mhRoot.pages.single().imageUrl)
        mhHttp.recordedRequests.clear()
        mh.gallerySource.loadGalleryDetail("https://www.mh160ba.com/kanmanhua/26001/583501.html")
        assertEquals(1, mhHttp.recordedRequests.count { it.url.endsWith("583501.html") })
        assertEquals("Category Hit", mh.feedSource.loadFeed(FeedRequest(siteId = SiteId.MH160, section = "cat_lianzai")).items.single().title)
        assertNull(mh.linkResolver.resolve("https://m.mh160ba.com/kanmanhua/lianzai"))
        assertTrue(runCatching { mh.gallerySource.loadGalleryDetail("https://www.mh160ba.com/kanmanhua/26001/590566.html") }.exceptionOrNull() is SiteException.Unsupported)

        // 4. Guazimanhua empty digit guard, chapter link guard, single fetch per chapter
        val gzHttp = FakeHttpClientAdapter()
        gzHttp.onUrlText("https://www.guazimanhua.com/category.php?sort=update&page=1", guaziUpdateHtml)
        gzHttp.onUrlText("https://www.guazimanhua.com/chapter.php?id=77", """<html><title>Ch77漫画在线阅读</title><body><a href="/comic.php?id=28873">Back</a><img src="https://img.guazicdn.com/th/comics/chapters/77.jpg"/></body></html>""")
        val gz = GuazimanhuaSitePlugin(gzHttp)
        gz.feedSource.loadFeed(FeedRequest(siteId = SiteId.GUAZIMANHUA, category = ""))
        assertFalse(gzHttp.recordedRequests.last().url.contains("cid="))
        assertTrue(runCatching { gz.feedSource.loadFeed(FeedRequest(siteId = SiteId.GUAZIMANHUA, section = "chapters", category = "")) }.exceptionOrNull() is SiteException.ParseError)
        assertNull(gz.linkResolver.resolve("https://www.guazimanhua.com/chapter.php?id=77"))
        gzHttp.recordedRequests.clear()
        gz.gallerySource.loadGalleryDetail("https://www.guazimanhua.com/chapter.php?id=77")
        assertEquals(1, gzHttp.recordedRequests.count { it.url.endsWith("chapter.php?id=77") })

        // 5. Mycomic loadGalleryPage pageUrl fallback
        val myHttp = FakeHttpClientAdapter()
        myHttp.onUrlText("https://mycomic.com/chapters/794527", mycomicChapterHtml)
        val mycomic = MycomicSitePlugin(myHttp)
        val myPage = mycomic.gallerySource.loadGalleryPage(
            com.raven.app.core.model.GalleryDetail(
                item = com.raven.app.core.model.MediaItem(
                    siteId = SiteId.MYCOMIC,
                    id = "1128",
                    url = "https://mycomic.com/comics/1128",
                    kind = com.raven.app.core.model.MediaKind.SERIAL_COMIC,
                    title = "One Piece",
                    pageCount = 2,
                    extra = emptyMap(),
                ),
                pageCount = 2,
                pages = listOf(com.raven.app.core.model.GalleryPage(1, "https://mycomic.com/chapters/794527#1", "")),
            ),
            pageIndex = 2,
            forceReload = true,
        )
        assertEquals("https://cdn.mycomic.com/manga/1128/2.webp", myPage.imageUrl)

        // 6. Hipmh obfuscated decodeImages + decoyIndex removal + comicId in chapters feed + /chapter/go?hid=
        val encodedAlp = "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val decodedAlp = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val plainJson = """["/p/real1.jpg","/p/decoy.jpg","/p/real2.jpg"]"""
        val b64Url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(plainJson.toByteArray(Charsets.UTF_8))
        val sub = b64Url.map { ch -> encodedAlp[decodedAlp.indexOf(ch)] }.joinToString("")
        val perm = sub.chunked(7).mapIndexed { idx, blk -> if (idx % 2 == 1) blk.reversed() else blk }.joinToString("")
        val tailLen = perm.length / 3
        val headLen = (perm.length - tailLen) / 2
        val obfPayload = "qM9" + perm.substring(tailLen, tailLen + headLen) + "XX" + perm.drop(tailLen + headLen) + "YYY" + perm.take(tailLen) + "Z7"
        val sid = 1L
        val key = (sid.toBigInteger() * java.math.BigInteger.valueOf(0x9E3779B1L))
            .xor(3.toBigInteger() * java.math.BigInteger.valueOf(0x85EBCA6BL))
            .mod(3.toBigInteger())
        val orderId = java.math.BigInteger.ONE.xor(key).toLong()

        val hipHttp = FakeHttpClientAdapter()
        hipHttp.onMatch({ req -> req.url.startsWith("https://hipapi1.s3file.top/v2/chapter") }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """{"code":200,"data":{"manga_title":"M","chapter_title":"C1","manga_id":"7244","manga_slug":"demo","line":2,"order_id":$orderId,"sid":$sid,"images":"$obfPayload"}}""",
            )
        }
        hipHttp.onMatch({ req -> req.url.startsWith("https://hipapi1.s3file.top/v1/manga/chapters") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, """{"code":200,"data":{"items":[{"hid":"bTo3MjQ0LWM6MTM3MjE-NzI0NDoxLjAw","title":"第1话"}]}}""")
        }
        val hip = HipmhSitePlugin(hipHttp)
        val hipChapter = hip.feedSource.loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "chapters", category = "bTo3MjQ0-demo"))
        assertEquals("bTo3MjQ0-demo", hipChapter.items.single().extra["comicId"])
        val hipPage2 = hip.gallerySource.resolvePageImage("https://reader.hipmh.top/chapter/go?hid=bTo3MjQ0LWM6MTM3MjE-NzI0NDoxLjAw#2")
        assertEquals("https://hip-cf-1.s3imgs.top/p/real2.jpg", hipPage2.imageUrl)
    }

    @Test
    fun `comic plugin regex literals escape closing braces and brackets for ART ICU`() {
        val targetClasses = listOf(
            EhSharedEngine::class.java,
            EhentaiSitePlugin::class.java,
            ExhentaiSitePlugin::class.java,
            NhentaiSitePlugin::class.java,
            HitomiSitePlugin::class.java,
            WnacgSitePlugin::class.java,
            JmcomicSitePlugin::class.java,
            HdoujinSitePlugin::class.java,
            MangacopySitePlugin::class.java,
            Mh160SitePlugin::class.java,
            GuazimanhuaSitePlugin::class.java,
            MycomicSitePlugin::class.java,
            HipmhSitePlugin::class.java,
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
        assertTrue("Expected >0 static Regex fields scanned across comic plugins, got $checkedCount", checkedCount > 0)
        assertTrue(
            "Unescaped {/}/] will crash Android ART ICU: $violations",
            violations.isEmpty(),
        )
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
}
