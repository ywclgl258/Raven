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
        } finally {
            koinApp.close()
        }
    }

    @Test
    fun `mangacopy handles business code 210, chapter errors, canonical pageUrl, and malformed hex safely`() = runTest {
        val http = FakeHttpClientAdapter()
        val plugin = MangacopySitePlugin(http)

        // 1. Business code 210 maps to Forbidden(isChallenge = true) and propagates from chapter feed
        http.onMatch({ req ->
            req.url.startsWith("https://api.copy4000.com/api/v3/comic2/demo")
        }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """{"code":200,"results":{"comic":{"name":"Demo","path_word":"demo","cover":""},"groups":{"default":{"path_word":"default"}}}}""",
                headers = mapOf("Content-Type" to listOf("application/json")),
            )
        }
        http.onMatch({ req ->
            req.url.startsWith("https://api.copy4000.com/api/v3/comic/demo/group/default/chapters")
        }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """{"code":210,"message":"请下载官方最新客户端"}""",
                headers = mapOf("Content-Type" to listOf("application/json")),
            )
        }
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
    fun `serial comic edge cases cover hipmh and guazimanhua empty search handling`() = runTest {
        val http = FakeHttpClientAdapter()

        // 1. Hipmh data:null -> ParseError, empty search -> empty FeedPage
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

        // 2. Guazimanhua empty search & non-primitive JSON-LD url object
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
    }

    @Test
    fun `single point checks for mangacopy root url, guazi empty id, and hipmh decode`() = runTest {
        // 1. Mangacopy preserves root comic URL & deduplicates multi-group UUIDs
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

        // 2. Guazimanhua empty digit guard, chapter link guard, single fetch per chapter
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

        // 3. Hipmh obfuscated decodeImages + decoyIndex removal + comicId in chapters feed + /chapter/go?hid=
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
            MangacopySitePlugin::class.java,
            CopyHotApiSupport::class.java,
            RelamanhuaSitePlugin::class.java,
            GuazimanhuaSitePlugin::class.java,
            HipmhSitePlugin::class.java,
            Manwa2SitePlugin::class.java,
            BaozimhSitePlugin::class.java,
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

    @Test
    fun `guazimanhua resolves chapters fragment and forwards forceReload cache flag`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://www.guazimanhua.com/chapter.php?id=954976", guaziChapterHtml)
        val plugin = GuazimanhuaSitePlugin(http)
        val chapLink = plugin.linkResolver.resolve("https://www.guazimanhua.com/comic.php?id=18741#chapters")
        assertTrue(chapLink is com.raven.app.core.sitespi.ResolvedLink.Feed)
        val page = plugin.gallerySource.resolvePageImage(
            "https://www.guazimanhua.com/chapter.php?id=954976#1",
            preferOriginal = false,
            forceReload = true,
        )
        assertEquals(1, page.index)
        assertFalse(http.recordedRequests.last().allowCache)
    }
}
