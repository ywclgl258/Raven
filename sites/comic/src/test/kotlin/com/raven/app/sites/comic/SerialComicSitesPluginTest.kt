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

        val plugin = MycomicSitePlugin(http)
        val feed = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.MYCOMIC))
        assertEquals(2, feed.items.size)
        assertEquals("1128", feed.items.first().id)
        assertEquals("ONE PIECE航海王", feed.items.first().title)
        assertEquals("https://mycomic.com/covers/1128.webp", feed.items.first().coverUrl)

        val detail = plugin.gallerySource.loadGalleryDetail("https://mycomic.com/comics/1128")
        assertEquals("ONE PIECE航海王", detail.item.title)
        assertEquals(2, detail.pageCount)
        assertEquals("817597", detail.item.extra["chapterId"])
        assertEquals(
            listOf(
                "https://cdn.mycomic.com/manga/1128/1.jpg",
                "https://cdn.mycomic.com/manga/1128/2.webp",
            ),
            detail.pages.map { it.imageUrl },
        )
        assertTrue(detail.chaptersUrl.orEmpty().endsWith("#chapters"))

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
    fun `comic plugin regex literals escape closing braces and brackets for ART ICU`() {
        val pluginClasses = listOf(
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
        pluginClasses.forEach { pluginClass ->
            val companion = pluginClass.declaredClasses.firstOrNull { it.simpleName == "Companion" }
                ?: return@forEach
            companion.declaredFields
                .filter { Regex::class.java.isAssignableFrom(it.type) }
                .forEach { field ->
                    field.isAccessible = true
                    val pattern = (field.get(null) as? Regex)?.pattern ?: return@forEach
                    val openBraces = countUnescaped(pattern, '{')
                    val closeBraces = countUnescaped(pattern, '}')
                    val openBrackets = countUnescaped(pattern, '[')
                    val closeBrackets = countUnescaped(pattern, ']')
                    if (closeBraces > openBraces || closeBrackets > openBrackets) {
                        violations += "${pluginClass.simpleName}.${field.name}: $pattern"
                    }
                }
        }
        assertTrue(
            "Unescaped } or ] will crash Android ART ICU: $violations",
            violations.isEmpty(),
        )
    }

    private fun countUnescaped(pattern: String, target: Char): Int {
        var count = 0
        var backslashes = 0
        pattern.forEach { ch ->
            if (ch == '\\') {
                backslashes++
                return@forEach
            }
            if (ch == target && backslashes % 2 == 0) count++
            backslashes = 0
        }
        return count
    }

}
