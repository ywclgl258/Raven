package com.raven.app.sites.comic

import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.ManwaImageAesProtocol
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NewSerialComicSitesPluginTest {

    private fun jsonResp(req: SiteHttpRequest, body: String, code: Int = 200): SiteHttpResponse =
        FakeHttpClientAdapter.htmlResponse(
            url = req.url,
            html = body,
            code = code,
            headers = mapOf("Content-Type" to listOf("application/json")),
        )

    private fun htmlResp(req: SiteHttpRequest, body: String, code: Int = 200): SiteHttpResponse =
        FakeHttpClientAdapter.htmlResponse(
            url = req.url,
            html = body,
            code = code,
        )

    // -------------------------------------------------------------------------
    // 拷贝漫画（官方 3.1.1 签名 / words 重排 / 热辣容灾 / 桌面 AES / 登录）
    // -------------------------------------------------------------------------

    @Test
    fun `mangacopy sends official 3_1_1 signed headers, reorders chapter2 words, and supports home, author search, and login`() = runTest {
        val http = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val capturedHeaders = mutableListOf<Map<String, String>>()

        http.onMatch({ req -> req.url.contains("/api/v3/system/network2") }) { req ->
            jsonResp(req, """{"code":200,"results":{"api":[["api.copy202601.com"]]}}""")
        }
        http.onMatch({ req -> req.url.contains("/api/v3/system/appVersion/last") }) { req ->
            jsonResp(req, """{"code":200,"results":{"version":"3.1.1"}}""")
        }
        http.onMatch({ req -> req.url.contains("/api/v3/h5/homeIndex2") }) { req ->
            capturedHeaders += req.headers
            jsonResp(
                req,
                """
                {
                  "code": 200,
                  "results": {
                    "banners": [{"type": 1, "comic": {"name": "BannerOne", "path_word": "banner-one", "cover": "https://img.example.com/b.jpg"}}],
                    "recComics": {"list": [{"comic": {"name": "RecOne", "path_word": "rec-one", "cover": "https://img.example.com/r.jpg"}}]},
                    "finishComics": {"list": [{"name": "FinOne", "path_word": "fin-one", "cover": "https://img.example.com/f.jpg"}]}
                  }
                }
                """.trimIndent(),
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/update/newest") || (req.url.contains("/api/v3/comics") && !req.url.contains("author=")) }) { req ->
            capturedHeaders += req.headers
            jsonResp(
                req,
                """{"code":200,"results":{"total":1,"offset":0,"list":[{"name":"NewestComic","path_word":"newest-comic","cover":"https://img.example.com/n.jpg","author":[{"name":"AuthorA","path_word":"author-a"}]}]}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comics") && req.url.contains("author=") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"total":1,"offset":0,"list":[{"name":"ByAuthor","path_word":"by-author","cover":"https://img.example.com/a.jpg"}]}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comic2/demo-work") }) { req ->
            jsonResp(
                req,
                """
                {
                  "code": 200,
                  "results": {
                    "comic": {
                      "name": "DemoWork",
                      "path_word": "demo-work",
                      "cover": "https://img.example.com/demo.jpg",
                      "brief": "Copy brief",
                      "author": [{"name": "Tester", "path_word": "tester"}],
                      "theme": [{"name": "奇幻", "path_word": "qihuan"}],
                      "status": {"display": "连载中"}
                    },
                    "groups": {"default": {"name": "默认", "path_word": "default"}}
                  }
                }
                """.trimIndent(),
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comic/demo-work/group/default/chapters") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"total":1,"offset":0,"list":[{"uuid":"ch-1","name":"第1话","index":0,"datetime_created":"2026-01-01"}]}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comic/demo-work/chapter2/ch-1") }) { req ->
            capturedHeaders += req.headers
            jsonResp(
                req,
                """
                {
                  "code": 200,
                  "results": {
                    "comic": {"name": "DemoWork", "path_word": "demo-work", "cover": "https://img.example.com/demo.jpg"},
                    "chapter": {
                      "uuid": "ch-1",
                      "name": "第1话",
                      "words": [2, 0, 1],
                      "contents": [
                        {"url": "https://img.example.com/p2.jpg"},
                        {"url": "https://img.example.com/p0.jpg"},
                        {"url": "https://img.example.com/p1.jpg"}
                      ]
                    }
                  }
                }
                """.trimIndent(),
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/login") && req.method == SiteHttpMethod.POST }) { req ->
            jsonResp(req, """{"code":200,"results":{"token":"copy-jwt-123","username":"raven_user"}}""")
        }

        val plugin = MangacopySitePlugin(http, store)
        val home = plugin.feedSource.loadHome()
        assertEquals("banner-one", home.heroItems.first().id)
        assertTrue(home.sections.size >= 2)

        val hdrs = capturedHeaders.first()
        assertEquals("COPY/3.1.1", hdrs["User-Agent"])
        assertEquals("85", hdrs["versioncode"])
        assertEquals("copyApp", hdrs["source"])
        assertEquals("3", hdrs["platform"])
        assertEquals(CopyHotApiSupport.COPY_UM_STRING, hdrs["umstring"])
        assertFalse(hdrs["X-Auth-Timestamp"].isNullOrBlank())
        assertFalse(hdrs["X-Auth-Signature"].isNullOrBlank())
        assertFalse(hdrs["dt"].isNullOrBlank())

        val authorSearch = plugin.searchSource.search(
            FeedRequest(siteId = SiteId.MANGACOPY, query = "Tester", scope = SearchScope.AUTHOR),
        )
        assertEquals("by-author", authorSearch.items.single().id)

        val chapterDetail = plugin.gallerySource.loadGalleryDetail("https://www.mangacopy.com/comic/demo-work/chapter/ch-1")
        assertEquals(
            listOf(
                "https://img.example.com/p0.jpg",
                "https://img.example.com/p1.jpg",
                "https://img.example.com/p2.jpg",
            ),
            chapterDetail.pages.map { it.imageUrl },
        )

        val auth = plugin.authProvider.loginWithCredentials("raven_user", "secret")
        assertTrue(auth.isAuthenticated)
        assertEquals("copy-jwt-123", store.getBearerToken(SiteId.MANGACOPY))
        plugin.authProvider.logout()
        assertFalse(plugin.authProvider.currentAuthState().isAuthenticated)
    }

    @Test
    fun `mangacopy never sends the token to runtime-discovered or fallback hosts`() = runTest {
        val http = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        store.saveBearerToken(SiteId.MANGACOPY, "copy-jwt-7", "raven")

        // A hostile/compromised `network2` advertises an attacker-controlled API host first.
        http.onMatch({ req -> req.url.contains("/api/v3/system/network2") }) { req ->
            jsonResp(req, """{"code":200,"results":{"api":[["evil-copy.example"]]}}""")
        }
        http.onMatch({ req -> req.url.contains("/api/v3/h5/homeIndex2") }) { req ->
            jsonResp(req, """{"code":200,"results":{}}""")
        }
        // Copy API rejects (210) so the request lands on the HotManga cluster instead.
        http.onMatch({ req -> req.url.startsWith("https://api.copy") && req.url.contains("/api/v3/comics") }) { req ->
            jsonResp(req, """{"code":210,"message":"限制"}""")
        }
        http.onMatch({ req -> req.url.startsWith("https://mapi.hotmanga") && req.url.contains("/api/v3/comics") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"total":1,"offset":0,"list":[{"name":"Confine","path_word":"confine","cover":""}]}}""",
            )
        }

        val plugin = MangacopySitePlugin(http, store)
        plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.MANGACOPY, section = "latest"))

        // No request to any host outside the pinned CopyManga allow-list may carry the token.
        val pinned = CopyHotApiSupport.COPY_API_HOSTS
        val leaked = http.recordedRequests.filter { req ->
            val host = java.net.URI(req.url).host?.lowercase().orEmpty()
            req.headers["Authorization"] != null && pinned.none { it.equals(host, ignoreCase = true) }
        }
        assertTrue("token leaked to non-pinned host: ${leaked.map { it.url }}", leaked.isEmpty())

        // decorateRequestHeaders follows the same rule for the transport interceptor.
        assertEquals(
            emptyMap<String, String>(),
            plugin.authProvider.decorateRequestHeaders(
                SiteHttpRequest(url = "https://evil-copy.example/api/v3/comics", siteId = SiteId.MANGACOPY),
                host = "evil-copy.example",
            ),
        )
        assertEquals(
            emptyMap<String, String>(),
            plugin.authProvider.decorateRequestHeaders(
                SiteHttpRequest(url = "https://mapi.hotmangasg.com/api/v3/comics", siteId = SiteId.MANGACOPY),
                host = "mapi.hotmangasg.com",
            ),
        )
        // The pinned official host still receives it.
        assertEquals(
            mapOf("Authorization" to "Token copy-jwt-7"),
            plugin.authProvider.decorateRequestHeaders(
                SiteHttpRequest(url = "https://api.copy202601.com/api/v3/comics", siteId = SiteId.MANGACOPY),
                host = "api.copy202601.com",
            ),
        )
    }

    @Test
    fun `mangacopy and relamanhua never cache or report a truncated chapter list`() = runTest {
        // CopyManga pages chapters 100 at a time, so a partial "page 1 only" result is only
        // observable when the first page is FULL and the follow-up page fails.
        fun chapterPageJson(offset: Int, count: Int, total: Int = 200): String {
            val items = (0 until count).joinToString(",") { i ->
                """{"uuid":"c${offset + i}","name":"第 ${offset + i + 1} 话"}"""
            }
            return """{"code":200,"results":{"total":$total,"offset":$offset,"list":[$items]}}"""
        }

        // ---- CopyManga ----
        val http = FakeHttpClientAdapter()
        var failNextPage = true
        var chapterCount = 200
        http.onMatch({ req -> req.url.contains("/api/v3/comic2/trunc-work") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"comic":{"name":"Trunc","path_word":"trunc-work","cover":""},"groups":{"default":{"path_word":"default"}}}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comic/trunc-work/group/default/chapters") }) { req ->
            val offset = Regex("""offset=(\d+)""").find(req.url)?.groupValues?.get(1)?.toInt() ?: 0
            if (offset == 100 && failNextPage) {
                SiteHttpResponse(code = 500, finalUrl = req.url, body = "boom".toByteArray())
            } else {
                jsonResp(req, chapterPageJson(offset, (chapterCount - offset).coerceIn(0, 100), chapterCount))
            }
        }
        val mangacopy = MangacopySitePlugin(http)

        // Page 2 fails -> the collected page-1 prefix must NOT be reported as a complete list.
        val firstError = runCatching {
            mangacopy.feedSource.loadFeed(
                FeedRequest(siteId = SiteId.MANGACOPY, section = "chapters", category = "trunc-work"),
            )
        }.exceptionOrNull()
        assertNotNull("truncated chapter list must throw, got $firstError", firstError)

        // Recovery on the same work must return the full ordered list (nothing cached short).
        failNextPage = false
        val recovered = mangacopy.feedSource.loadFeed(
            FeedRequest(siteId = SiteId.MANGACOPY, section = "chapters", category = "trunc-work"),
        )
        assertEquals(200, recovered.items.size)
        assertEquals("c199", recovered.items.last().extra["chapterUuid"])

        chapterCount = 201
        val refreshed = mangacopy.seriesSource.loadChapters("trunc-work")
        assertEquals(201, refreshed.size)
        assertEquals("trunc-work:c200", refreshed.last().id)
        failNextPage = true
        assertNotNull(runCatching { mangacopy.seriesSource.loadChapters("trunc-work") }.exceptionOrNull())

        // ---- HotManga (relamanhua) ----
        val hot = FakeHttpClientAdapter()
        var failHotPage = true
        hot.onMatch({ req -> req.url.contains("/api/v3/comic2/trunc-work") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"comic":{"name":"Trunc","path_word":"trunc-work","cover":""},"groups":{"default":{"path_word":"default"}}}}""",
            )
        }
        hot.onMatch({ req -> req.url.contains("/api/v3/comic/trunc-work/group/default/chapters") }) { req ->
            val offset = Regex("""offset=(\d+)""").find(req.url)?.groupValues?.get(1)?.toInt() ?: 0
            if (offset == 100 && failHotPage) {
                SiteHttpResponse(code = 500, finalUrl = req.url, body = "boom".toByteArray())
            } else {
                jsonResp(req, chapterPageJson(offset, 100))
            }
        }
        val rela = RelamanhuaSitePlugin(hot)
        val hotError = runCatching {
            rela.feedSource.loadFeed(
                FeedRequest(siteId = SiteId.RELAMANHUA, section = "chapters", category = "trunc-work"),
            )
        }.exceptionOrNull()
        assertNotNull("relamanhua truncated list must throw, got $hotError", hotError)

        failHotPage = false
        val hotRecovered = rela.feedSource.loadFeed(
            FeedRequest(siteId = SiteId.RELAMANHUA, section = "chapters", category = "trunc-work"),
        )
        assertEquals(200, hotRecovered.items.size)
        assertEquals("c199", hotRecovered.items.last().extra["chapterUuid"])
    }

    @Test
    fun `mangacopy falls back to hotmanga cluster on 210 and decrypts desktop html aes as last resort`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.startsWith("https://api.copy") && req.url.contains("/api/v3/comic2/fallback-work") }) { req ->
            jsonResp(req, """{"code":210,"message":"限制"}""")
        }
        http.onMatch({ req -> req.url.startsWith("https://mapi.hotmanga") && req.url.contains("/api/v3/comic2/fallback-work") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"comic":{"name":"FallbackWork","path_word":"fallback-work","cover":""},"groups":{"default":{"path_word":"default"}}}}""",
            )
        }
        http.onMatch({ req -> req.url.startsWith("https://mapi.hotmanga") && req.url.contains("/chapters") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"total":1,"offset":0,"list":[{"uuid":"ch-fb","name":"第1话","index":0}]}}""",
            )
        }
        http.onMatch({ req -> req.url.startsWith("https://api.copy") && req.url.contains("/chapter") }) { req ->
            jsonResp(req, """{"code":210,"message":"限制"}""")
        }
        http.onMatch({ req -> req.url.startsWith("https://mapi.hotmanga") && req.url.contains("/chapter/ch-fb") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"chapter":{"uuid":"ch-fb","name":"第1话","contents":[{"url":"https://hotimg.example.com/1.webp"}]}}}""",
            )
        }

        val plugin = MangacopySitePlugin(http)
        val hotFallbackChapter = plugin.gallerySource.loadGalleryDetail("https://www.mangacopy.com/comic/fallback-work/chapter/ch-fb")
        assertEquals(listOf("https://hotimg.example.com/1.webp"), hotFallbackChapter.pages.map { it.imageUrl })

        // When both Copy App API and HotManga return 210 for chapter, decrypt desktop HTML AES-CBC
        val http2 = FakeHttpClientAdapter()
        http2.onMatch({ req -> req.url.contains("/api/v3/comic2/aes-work") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"comic":{"name":"AesWork","path_word":"aes-work","cover":""},"groups":{"default":{"path_word":"default"}}}}""",
            )
        }
        http2.onMatch({ req -> req.url.contains("/api/v3/comic/aes-work/group/default/chapters") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"total":1,"offset":0,"list":[{"uuid":"ch-aes","name":"第1话","index":0}]}}""",
            )
        }
        http2.onMatch({ req -> req.url.contains("/chapter") && req.url.contains("/api/v3/") }) { req ->
            jsonResp(req, """{"code":210,"message":"限制"}""")
        }
        val key = "xxxmanga.woo.key"
        val iv = "1234567890abcdef"
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        val encryptedHex = cipher.doFinal("""[{"url":"https://aesimg.example.com/0.jpg"}]""".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        http2.onMatch({ req -> req.url.contains("www.mangacopy.com/comic/aes-work/chapter/ch-aes") }) { req ->
            htmlResp(
                req,
                """<html><body><script>var cct = '$key'; var contentKey = '$iv$encryptedHex';</script></body></html>""",
            )
        }

        val plugin2 = MangacopySitePlugin(http2)
        val aesChapter = plugin2.gallerySource.loadGalleryDetail("https://www.mangacopy.com/comic/aes-work/chapter/ch-aes")
        assertEquals(listOf("https://aesimg.example.com/0.jpg"), aesChapter.pages.map { it.imageUrl })
    }

    // -------------------------------------------------------------------------
    // 热辣漫画
    // -------------------------------------------------------------------------

    @Test
    fun `relamanhua loads home, list, author search, detail chapters, chapter images, login, and links`() = runTest {
        val http = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        http.onMatch({ req -> req.url.contains("/api/v3/h5/discoverIndex/freeComic") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"banners":[{"comic":{"name":"HotBanner","path_word":"hot-banner","cover":"https://img.example.com/hb.jpg"}}],"recs":{"list":[{"comic":{"name":"HotRec","path_word":"hot-rec","cover":""}}]}}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comics") && !req.url.contains("author=") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"total":1,"offset":0,"list":[{"name":"HotWork","path_word":"hot-work","cover":"https://img.example.com/hw.jpg"}]}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comics") && req.url.contains("author=") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"total":1,"offset":0,"list":[{"name":"HotAuthorHit","path_word":"hot-hit","cover":""}]}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comic2/hot-work") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"comic":{"name":"HotWork","path_word":"hot-work","cover":"https://img.example.com/hw.jpg","brief":"Hot desc","author":[{"name":"RelaAuthor","path_word":"rela-author"}],"theme":[{"name":"恋爱","path_word":"aiqing"}],"status":{"display":"已完结"}},"groups":{"default":{"name":"默认","path_word":"default"}}}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comic/hot-work/group/default/chapters") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"total":1,"offset":0,"list":[{"uuid":"rela-ch-1","name":"第1话","index":0}]}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/comic/hot-work/chapter/rela-ch-1") }) { req ->
            jsonResp(
                req,
                """{"code":200,"results":{"chapter":{"uuid":"rela-ch-1","name":"第1话","contents":[{"url":"https://relaimg.example.com/0.webp"}]}}}""",
            )
        }
        http.onMatch({ req -> req.url.contains("/api/v3/login") && req.method == SiteHttpMethod.POST }) { req ->
            jsonResp(req, """{"code":200,"results":{"token":"hot-jwt-99","username":"rela_user"}}""")
        }

        val plugin = RelamanhuaSitePlugin(http, store)
        val home = plugin.feedSource.loadHome()
        assertEquals("hot-banner", home.heroItems.first().id)

        val authorSearch = plugin.searchSource.search(
            FeedRequest(siteId = SiteId.RELAMANHUA, query = "RelaAuthor", scope = SearchScope.AUTHOR),
        )
        assertEquals("hot-hit", authorSearch.items.single().id)

        val chDetail = plugin.gallerySource.loadGalleryDetail("https://www.hotmangasd.com/comic/hot-work/chapter/rela-ch-1")
        assertEquals(listOf("https://relaimg.example.com/0.webp"), chDetail.pages.map { it.imageUrl })

        val auth = plugin.authProvider.loginWithCredentials("rela_user", "pass")
        assertTrue(auth.isAuthenticated)
        assertEquals("hot-jwt-99", store.getBearerToken(SiteId.RELAMANHUA))

        assertTrue(plugin.linkResolver.resolve("https://www.relamanhua.com/comic/hot-work") is ResolvedLink.Gallery)
    }

    // -------------------------------------------------------------------------
    // 漫蛙2
    // -------------------------------------------------------------------------

    @Test
    fun `manwa2 resolves redirect host before POST api_cate, swaps CDN on error, refreshes AES_KEY, and unwraps images`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.contains("manwari.cc/cate?page=1") }) { req ->
            SiteHttpResponse(
                code = 200,
                finalUrl = "https://manwauu.cc/cate?page=1",
                headers = emptyMap(),
                body = "<html></html>".toByteArray(),
            )
        }
        http.onMatch({ req -> req.url == "https://manwauu.cc/api/cate" && req.method == SiteHttpMethod.POST }) { req ->
            jsonResp(
                req,
                """{"code":200,"data":{"total":1,"list":[{"id":777,"title":"ManwaHit","pic":"/en_images/cover/777.webp","author":"MwAuth","tags":"热血,恋爱"}]}}""",
            )
        }
        http.onMatch({ req -> req.url.startsWith("https://manwauu.cc/api/search") }) { req ->
            jsonResp(
                req,
                """{"code":200,"data":{"total":1,"list":[{"id":777,"title":"ManwaHit","cover":"/en_images/cover/777.webp"}]}}""",
            )
        }
        http.onMatch({ req -> req.url == "https://manwauu.cc/api/comic/777" }) { req ->
            jsonResp(
                req,
                """{"code":200,"data":{"id":777,"title":"ManwaHit","pic":"/en_images/cover/777.webp","author":"MwAuth","tags":"热血","status":"连载中","description":"Mw desc"}}""",
            )
        }
        http.onMatch({ req -> req.url == "https://manwauu.cc/api/comic/777/chapters" }) { req ->
            jsonResp(
                req,
                """{"code":200,"data":[{"id":301,"title":"第1话","sortId":"1"},{"id":302,"title":"第2话","sortId":"2"}]}""",
            )
        }
        http.onMatch({ req -> req.url.startsWith("https://manwauu.cc/api/comic/image/301") }) { req ->
            jsonResp(
                req,
                """{"code":200,"data":{"images":[{"url":"https://img.oldcdn.example/en_images/777/1.webp"}],"pagination":{"totalPages":1}}}""",
            )
        }
        http.onMatch({ req -> req.url == "https://manwauu.cc/static/assets/js/base.js" }) { req ->
            htmlResp(req, """window.CFG = { AES_KEY: "NEW12345-6789-ABCD-EFGH-0123456789AB" };""")
        }

        val plugin = Manwa2SitePlugin(http)
        val home = plugin.feedSource.loadHome()
        assertEquals("777", home.primaryFeedPage!!.items.single().id)

        val search = plugin.searchSource.search(FeedRequest(siteId = SiteId.MANWA2, query = "Manwa"))
        assertEquals("ManwaHit", search.items.single().title)

        val chDetail = plugin.gallerySource.loadGalleryDetail("https://manwauu.cc/comic/777/301")
        val firstPage = chDetail.pages.single()
        assertFalse(firstPage.imageUrl.isBlank())

        // Reload on error switches CDN and refreshes base.js AES_KEY
        val reloadedPageUrl = plugin.gallerySource.reloadUrlOnError(SiteException.Network("timeout"), firstPage)
        assertNotNull(reloadedPageUrl)
        val reloadedPage = plugin.gallerySource.resolvePageImage(reloadedPageUrl!!, preferOriginal = false, forceReload = true)
        assertTrue(reloadedPage.imageUrl.isNotBlank())

        // Transport hook uses refreshed AES key
        val plainPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val encrypted = ManwaImageAesProtocol.encryptForTest(
            plainPng,
            aesKey = "NEW12345-6789-ABCD-EFGH-0123456789AB",
        )
        val unwrapped = plugin.mediaTransportHook.unwrapMediaBytes("https://cdn.example/en_images/1.webp", encrypted)
        assertNotNull(unwrapped)
        assertEquals("image/png", unwrapped!!.contentType)
        assertArrayEquals(plainPng, unwrapped.bytes)

        assertTrue(plugin.linkResolver.resolve("https://manwari.cc/comic/777") is ResolvedLink.Gallery)
    }

    // -------------------------------------------------------------------------
    // 包子漫畫
    // -------------------------------------------------------------------------

    @Test
    fun `baozimh parses amp_comic_list, concatenates subpages, normalizes page_direct, and switches host on challenge or 404`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.contains("tw.webmota.com/api/bzmhq/amp_comic_list") }) { req ->
            jsonResp(
                req,
                """{"items":[{"name":"BaoziComic","comic_id":"baozi-1","topic_img":"cover/baozi-1.jpg","author":"BzAuth","type_names":["玄幻"]}]}""",
            )
        }
        http.onMatch({ req -> req.url == "https://tw.webmota.com/comic/baozi-1" }) { req ->
            htmlResp(
                req,
                """
                <html><body>
                  <h1 class="comics-detail__title">BaoziComic</h1>
                  <h2 class="comics-detail__author">BzAuth</h2>
                  <div class="tag-list"><span class="tag">连载中</span><span class="tag">玄幻</span></div>
                  <p class="comics-detail__desc">Great story</p>
                  <div id="chapter-items">
                    <div class="comics-chapters"><a href="/user/page_direct?comic_id=baozi-1&section_slot=0&chapter_slot=1">第1话</a></div>
                  </div>
                </body></html>
                """.trimIndent(),
            )
        }
        // First host 404s for chapter -> falls back to www.webmota.com, with 2 subpages
        http.onMatch({ req -> req.url == "https://www.webmota.com/comic/chapter/baozi-1/0_1.html" }) { req ->
            htmlResp(
                req,
                """
                <html><body>
                  <div class="chapter-img">
                    <img class="comic-contain__item" src="https://s1.baozimh.com/scomic/baozi-1/0/1/1.jpg">
                  </div>
                  <div class="next_chapter"><a href="/comic/chapter/baozi-1/0_1_2.html">下一頁</a></div>
                </body></html>
                """.trimIndent(),
            )
        }
        http.onMatch({ req -> req.url == "https://www.webmota.com/comic/chapter/baozi-1/0_1_2.html" }) { req ->
            htmlResp(
                req,
                """
                <html><body>
                  <div class="chapter-img">
                    <img class="comic-contain__item" data-src="https://s1.baozimh.com/scomic/baozi-1/0/1/2.jpg">
                  </div>
                </body></html>
                """.trimIndent(),
            )
        }

        val plugin = BaozimhSitePlugin(http)
        val popular = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.BAOZIMH, section = "popular"))
        assertEquals("baozi-1", popular.items.single().id)
        assertTrue(popular.items.single().coverUrl.startsWith("https://"))

        val chapters = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.BAOZIMH, section = "chapters", category = "baozi-1"))
        assertEquals(1, chapters.items.size)
        assertTrue(chapters.items.single().url.contains("/comic/chapter/baozi-1/0_1.html"))

        val chDetail = plugin.gallerySource.loadGalleryDetail("https://tw.webmota.com/comic/chapter/baozi-1/0_1.html")
        assertEquals(
            listOf(
                "https://s1.baozimh.com/scomic/baozi-1/0/1/1.jpg",
                "https://s1.baozimh.com/scomic/baozi-1/0/1/2.jpg",
            ),
            chDetail.pages.map { it.imageUrl },
        )

        val rotatedPageUrl = plugin.gallerySource.reloadUrlOnError(SiteException.Network("cdn fail"), chDetail.pages.first())
        assertNotNull(rotatedPageUrl)
        assertTrue(rotatedPageUrl!!.contains("#"))

        // Gatekeeper challenge detection on all mirrors throws Forbidden(isChallenge = true)
        val blockedHttp = FakeHttpClientAdapter()
        blockedHttp.onMatch({ req -> req.url.contains("/search") }) { req ->
            SiteHttpResponse(
                code = 403,
                finalUrl = req.url,
                headers = mapOf("x-gatekeeper-challenge" to listOf("1")),
                body = "Just a moment...".toByteArray(),
            )
        }
        val blockedPlugin = BaozimhSitePlugin(blockedHttp)
        try {
            blockedPlugin.searchSource.search(FeedRequest(siteId = SiteId.BAOZIMH, query = "test"))
            fail("Expected Forbidden(isChallenge = true) when all mirrors present gatekeeper challenge")
        } catch (expected: SiteException.Forbidden) {
            assertTrue(expected.isChallenge)
        }
        assertTrue(plugin.linkResolver.resolve("https://www.baozimh.com/comic/baozi-1") is ResolvedLink.Gallery)
    }

    // -------------------------------------------------------------------------
    // 嬉皮漫畫（v1/search、全分页章节、元数据、诱饵剔除与双线切图）
    // -------------------------------------------------------------------------

    @Test
    fun `hipmh searches v1_search, loads multi-page chapters and metadata, filters decoys, and swaps tx_cf image hosts`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.startsWith("https://hipapi1.s3file.top/v1/search") }) { req ->
            jsonResp(
                req,
                """{"code":200,"data":{"total":1,"items":[{"mid":"m99","slug":"hip-demo","title":"HipDemo","cover_url":"https://cdn.example.com/c.jpg"}]}}""",
            )
        }
        http.onMatch({ req -> req.url == "https://m.hipmh.com/works/hip-demo" }) { req ->
            htmlResp(
                req,
                """
                <html><body>
                  <h1>HipDemo</h1>
                  <a href="/author/1">AuthorHip</a>
                  <a href="/ongoing" title="連載中">連載中</a>
                  <div data-alt-title-list><span title="HipAlias">HipAlias</span></div>
                  <div id="d-tab-info"><a href="/tag/1">#校园</a><a href="/tag/2">#恋爱</a></div>
                  <div id="d-info-content"><p>Hip description</p></div>
                  <div id="chapters-config" data-mid="m99" data-total="3"></div>
                </body></html>
                """.trimIndent(),
            )
        }
        http.onMatch({ req -> req.url.contains("/v1/manga/chapters") && req.url.contains("page=1") }) { req ->
            jsonResp(
                req,
                """
                {
                  "code": 200,
                  "data": {
                    "items": [
                      {"hid": "bTo3MjQ0LWM6MTM3MjE-NzI0NDoxLjAw", "title": "Ch1"},
                      {"hid": "bTo3MjQ0LWM6MTM3MjI-NzI0NDoyLjAw", "title": "Ch2"}
                    ],
                    "total_pages": 2
                  }
                }
                """.trimIndent(),
            )
        }
        http.onMatch({ req -> req.url.contains("/v1/manga/chapters") && req.url.contains("page=2") }) { req ->
            jsonResp(
                req,
                """
                {
                  "code": 200,
                  "data": {
                    "items": [{"hid": "bTo3MjQ0LWM6MTM3MjM-NzI0NDozLjAw", "title": "Ch3"}],
                    "total_pages": 2
                  }
                }
                """.trimIndent(),
            )
        }

        // Encode 2 real URLs + 1 decoy using qM9..Vx..pL0..Z7 cipher & orderId/sid decoy index
        val encodedAlp = "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val decodedAlp = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val plainJson = """["/p/1.jpg","/p/trap.jpg","/p/2.jpg"]"""
        val b64Url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(plainJson.toByteArray(Charsets.UTF_8))
        val sub = b64Url.map { ch -> encodedAlp[decodedAlp.indexOf(ch)] }.joinToString("")
        val perm = sub.chunked(7).mapIndexed { idx, blk -> if (idx % 2 == 1) blk.reversed() else blk }.joinToString("")
        val tailLen = perm.length / 3
        val headLen = (perm.length - tailLen) / 2
        val obfPayload = "qM9" + perm.substring(tailLen, tailLen + headLen) + "Vx" + perm.drop(tailLen + headLen) + "pL0" + perm.take(tailLen) + "Z7"
        val sid = 1L
        val key = (sid.toBigInteger() * java.math.BigInteger.valueOf(0x9E3779B1L))
            .xor(3.toBigInteger() * java.math.BigInteger.valueOf(0x85EBCA6BL))
            .mod(3.toBigInteger())
        val orderId = java.math.BigInteger.ONE.xor(key).toLong()

        http.onMatch({ req -> req.url.contains("/v2/chapter") }) { req ->
            jsonResp(
                req,
                """{"code":200,"data":{"manga_title":"HipDemo","chapter_title":"Ch1","manga_id":"m99","manga_slug":"hip-demo","line":1,"order_id":$orderId,"sid":$sid,"images":"$obfPayload"}}""",
            )
        }

        val plugin = HipmhSitePlugin(http)
        val search = plugin.searchSource.search(FeedRequest(siteId = SiteId.HIPMH, query = "HipDemo"))
        assertEquals("m99", search.items.single().id)

        val detail = plugin.gallerySource.loadGalleryDetail("https://m.hipmh.com/works/hip-demo")
        assertEquals("AuthorHip", detail.authors.single().name)
        assertTrue(detail.tags.map { it.label }.containsAll(listOf("校园", "恋爱")))

        val chapters = plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "chapters", category = "m99"))
        assertEquals(listOf("Ch1", "Ch2", "Ch3"), chapters.items.map { it.title })

        val frontendHid = "bTo3MjQ0LWM6MTM3MjE-NzI0NDoxLjAw"
        val chDetail = plugin.gallerySource.loadGalleryDetail("https://m.hipmh.com/chapter/go?hid=$frontendHid&mid=m99&slug=hip-demo")
        assertEquals(
            listOf("https://hip-tx-1.s3imgs.top/p/1.jpg", "https://hip-tx-1.s3imgs.top/p/2.jpg"),
            chDetail.pages.map { it.imageUrl },
        )

        val swappedPageUrl = plugin.gallerySource.reloadUrlOnError(
            SiteException.Network("tx line failed"),
            GalleryPage(index = 1, pageUrl = chDetail.pages.first().pageUrl, imageUrl = chDetail.pages.first().imageUrl),
        )
        assertNotNull(swappedPageUrl)
        val swappedPage = plugin.gallerySource.resolvePageImage(swappedPageUrl!!, preferOriginal = false, forceReload = true)
        assertEquals("https://hip-cf-1.s3imgs.top/p/1.jpg", swappedPage.imageUrl)
    }
}
