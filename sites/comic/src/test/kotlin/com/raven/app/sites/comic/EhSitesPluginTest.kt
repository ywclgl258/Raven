package com.raven.app.sites.comic

import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.ResolvedLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EhSitesPluginTest {

    private fun sampleFeedHtml(
        host: String,
        nextUrl: String? = "https://$host/?next=2001000&f_cats=1021",
        rows: List<Triple<String, String, String>> = listOf(
            Triple("2001001/abcdef1234", "[Artist] Chinese Sample", "chinese"),
            Triple("2001002/1234abcdef", "[Artist] Japanese Sample", "japanese"),
        ),
    ): String = buildString {
        append("<html><body><table class=\"itg\">")
        for ((path, title, lang) in rows) {
            append(
                """
                <tr>
                  <td class="gl1c"><div class="cn">Doujinshi</div></td>
                  <td class="gl2c"><div class="glthumb"><img data-src="https://ehgt.org/t/$path.jpg" width="250" height="350" /></div></td>
                  <td class="gl3c glname">
                    <a href="https://$host/g/$path/"><div class="glink">$title</div></a>
                    <div title="language:$lang">$lang</div>
                  </td>
                  <td class="gl4c"><div>32 pages</div></td>
                </tr>
                """.trimIndent(),
            )
        }
        append("</table>")
        if (nextUrl != null) {
            append("""<a id="unext" href="$nextUrl">Next &gt;</a>""")
        }
        append("</body></html>")
    }

    private fun sampleDetailHtml(host: String, gid: String = "2001001", token: String = "abcdef1234"): String = """
        <html><body>
          <h1 id="gn">[Artist] Sample Gallery Title</h1>
          <h1 id="gj">[作者] サンプル画廊</h1>
          <div id="gd1"><div style="width:250px;height:354px;background:transparent url(https://ehgt.org/cover/$gid.jpg) 0 0 no-repeat"></div></div>
          <div id="gdc"><div class="cs">Doujinshi</div></div>
          <div id="gdn"><a href="https://$host/uploader/TestUploader">TestUploader</a></div>
          <div id="rating_label">Average: 4.85</div>
          <table id="gdd">
            <tr><td>Posted:</td><td>2026-01-15 10:00</td></tr>
            <tr><td>Language:</td><td>Chinese</td></tr>
            <tr><td>File Size:</td><td>42.5 MB</td></tr>
            <tr><td>Length:</td><td>4 pages</td></tr>
          </table>
          <div id="taglist">
            <a href="https://$host/tag/female:big+breasts">big breasts</a>
            <a href="https://$host/tag/artist:demo+artist">demo artist</a>
            <a href="https://$host/tag/character:alice">alice</a>
          </div>
          <div id="gdt">
            <a href="https://$host/s/aaaa1111/$gid-1"><img src="https://ehgt.org/th/1.jpg" /></a>
            <a href="https://$host/s/bbbb2222/$gid-2"><img src="https://ehgt.org/th/2.jpg" /></a>
          </div>
        </body></html>
    """.trimIndent()

    private fun sampleDetailPage2Html(host: String, gid: String = "2001001"): String = """
        <html><body>
          <div id="gdt">
            <a href="https://$host/s/cccc3333/$gid-3"><img src="https://ehgt.org/th/3.jpg" /></a>
            <a href="https://$host/s/dddd4444/$gid-4"><img src="https://ehgt.org/th/4.jpg" /></a>
          </div>
        </body></html>
    """.trimIndent()

    private fun sampleImagePageHtml(
        host: String,
        imgSrc: String = "https://hath.network/h/sample-display.jpg",
        nlToken: String = "12345-67890",
        originalUrl: String? = "https://$host/fullimg.php?gid=2001001&page=3&key=xyz",
    ): String = """
        <html><body>
          <img id="img" src="$imgSrc" />
          <a id="loadfail" onclick="return nl('$nlToken')">Click here if the image fails loading</a>
          ${originalUrl?.let { """<a href="$it">Download original 2000 x 2800 4.2 MB source</a> (Load original 2000 x 2800, size: 4.2 MB)""" }.orEmpty()}
        </body></html>
    """.trimIndent()

    @Test
    fun `descriptors and auth mode match EH and EX protocol invariants`() {
        val http = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val eh = EhentaiSitePlugin(http, store)
        val ex = ExhentaiSitePlugin(http, store)

        assertEquals(SiteId.EHENTAI, eh.id)
        assertEquals(SiteId.EXHENTAI, ex.id)
        assertTrue(eh.descriptor.hasCapability(SiteCapability.ORIGINAL_IMAGES))
        assertTrue(eh.descriptor.hasCapability(SiteCapability.COOKIE_IMPORT))
        assertFalse(eh.authProvider.requiresAuthGate)
        assertTrue(ex.authProvider.requiresAuthGate)
        assertTrue(ex.descriptor.networkProfile.requiresAuthGate)
        assertTrue(ex.descriptor.networkProfile.disableFeedSnapshot)
        assertTrue(eh.gallerySource!!.rejectCrossHostImageRedirect)
        assertTrue(ex.gallerySource!!.rejectCrossHostImageRedirect)

        val exMode = ex.authProvider.mode as AuthMode.WebViewCookie
        assertEquals("https://forums.e-hentai.org/index.php?act=Login", exMode.loginUrl)
        assertEquals("https://exhentai.org/", exMode.targetUrl)
        assertEquals(setOf("ipb_member_id", "ipb_pass_hash"), exMode.requiredCookies)
        assertEquals(setOf("igneous", "sk"), exMode.optionalCookies)
        assertTrue("forums.e-hentai.org" in exMode.allowedHosts)
        assertTrue("exhentai.org" in exMode.allowedHosts)
        assertFalse("evil.example.com" in exMode.allowedHosts)

        assertEquals(mapOf("nw" to "1"), eh.authProvider.defaultCookies("e-hentai.org"))
        assertEquals(mapOf("nw" to "1"), eh.authProvider.defaultCookies("www.e-hentai.org"))
        assertEquals(emptyMap<String, String>(), eh.authProvider.defaultCookies("ehgt.org"))
        assertEquals(mapOf("nw" to "1"), ex.authProvider.defaultCookies("exhentai.org"))
        assertEquals(emptyMap<String, String>(), ex.authProvider.defaultCookies("hath.network"))
    }

    @Test
    fun `exhentai auth gate blocks unauthenticated access and verifies cookies before persisting`() = runTest {
        val http = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val ex = ExhentaiSitePlugin(http, store)
        val eh = EhentaiSitePlugin(http, store)

        // Unauthenticated ExHentai feed must immediately throw AuthRequired without hitting network
        try {
            ex.feedSource!!.loadFeed(FeedRequest(SiteId.EXHENTAI))
            fail("Expected SiteException.AuthRequired")
        } catch (_: SiteException.AuthRequired) {
            assertEquals(0, http.recordedRequests.size)
        }

        // Blank / Sad Panda response during importCookies must fail and NOT persist cookies
        http.onMatch({ req -> req.url.startsWith("https://exhentai.org/") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, "")
        }
        try {
            ex.authProvider.importCookies(
                mapOf(
                    "ipb_member_id" to "123456",
                    "ipb_pass_hash" to "0123456789abcdef0123456789abcdef",
                ),
            )
            fail("Expected SiteException.AuthRequired on blank ExHentai page")
        } catch (_: SiteException.AuthRequired) {
            assertTrue(store.getCookies(SiteId.EXHENTAI).isEmpty())
            assertFalse(ex.authProvider.currentAuthState().isAuthenticated)
        }

        // Valid ExHentai response verifies cookies, merges Set-Cookie igneous, persists, and unlocks feed
        http.onMatch({ req -> req.url.startsWith("https://exhentai.org/") }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                url = req.url,
                html = sampleFeedHtml("exhentai.org"),
                headers = mapOf("Set-Cookie" to listOf("igneous=abc987def654; path=/; domain=.exhentai.org")),
            )
        }
        val state = ex.authProvider.importCookies(
            mapOf(
                "ipb_member_id" to "123456",
                "ipb_pass_hash" to "0123456789abcdef0123456789abcdef",
                "sk" to "sktokenvalue1234",
            ),
        )
        assertTrue(state.isAuthenticated)
        assertEquals("UID 123456", state.username)
        val saved = store.getCookies(SiteId.EXHENTAI.accountId)
        assertEquals("123456", saved["ipb_member_id"])
        assertEquals("abc987def654", saved["igneous"])
        assertEquals("sktokenvalue1234", saved["sk"])
        // Verification request must include nw=1 in Cookie header and disallow cookie jar leakage
        val verifyReq = http.recordedRequests.last()
        assertFalse(verifyReq.allowCookies)
        assertTrue(verifyReq.headers["Cookie"]?.contains("nw=1") == true)

        // Now ExHentai feed succeeds
        val page = ex.feedSource!!.loadFeed(FeedRequest(SiteId.EXHENTAI))
        assertEquals(2, page.items.size)

        // ExHentai and E-Hentai share one account, so logging out of either signs both out.
        store.saveCookies(SiteId.EHENTAI, mapOf("ipb_member_id" to "999", "ipb_pass_hash" to "fedcba9876543210fedcba9876543210"))
        assertTrue(eh.authProvider.currentAuthState().isAuthenticated)
        ex.authProvider.logout()
        assertFalse(ex.authProvider.currentAuthState().isAuthenticated)
        assertFalse(eh.authProvider.currentAuthState().isAuthenticated)
        assertTrue(store.getCookies(SiteId.EHENTAI.accountId).isEmpty())
    }

    @Test
    fun `ehentai routing computes f_cats XOR mask, language queries, popular filter, and unext cursor paging`() = runTest {
        val http = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val eh = EhentaiSitePlugin(http, store)

        http.onMatch({ true }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                url = req.url,
                html = sampleFeedHtml("e-hentai.org"),
            )
        }

        // Category Doujinshi (2) -> f_cats = 1023 xor 2 = 1021, CHINESE_AND_JAPANESE -> ~language:chinese$ ~language:japanese$
        val catPage = eh.feedSource!!.loadFeed(
            FeedRequest(
                siteId = SiteId.EHENTAI,
                category = "2",
                language = ComicLanguage.CHINESE_AND_JAPANESE,
                advancedFilters = AdvancedSearchFilters(
                    includeTags = listOf("sole_female"),
                    excludeTags = listOf("guro"),
                    minPages = 20,
                    maxPages = 100,
                    minRating = 4,
                ),
            ),
        )
        val firstUrl = ComicUrl.requireParse(http.recordedRequests.last().url)
        assertEquals("1021", firstUrl.queryParameter("f_cats"))
        assertEquals("dm_l", firstUrl.queryParameter("inline_set"))
        assertEquals("on", firstUrl.queryParameter("f_sfl"))
        assertEquals("20", firstUrl.queryParameter("f_spf"))
        assertEquals("100", firstUrl.queryParameter("f_spt"))
        assertEquals("on", firstUrl.queryParameter("f_sr"))
        assertEquals("4", firstUrl.queryParameter("f_srdd"))
        assertEquals("1", firstUrl.queryParameter("advsearch"))
        val searchQuery = firstUrl.queryParameter("f_search").orEmpty()
        assertTrue(searchQuery.contains("~language:chinese$"))
        assertTrue(searchQuery.contains("~language:japanese$"))
        assertTrue(searchQuery.contains("sole_female"))
        assertTrue(searchQuery.contains("-guro"))
        assertTrue(catPage.hasMore)
        assertEquals("https://e-hentai.org/?next=2001000&f_cats=1021", catPage.nextCursor)

        // Follow #unext cursor without duplicating f_search tokens
        val cursorWithSearch = "https://e-hentai.org/?next=2001000&f_search=language%3Achinese%24"
        eh.feedSource!!.loadFeed(
            FeedRequest(
                siteId = SiteId.EHENTAI,
                language = ComicLanguage.CHINESE,
                page = 2,
                cursor = cursorWithSearch,
            ),
        )
        val cursorReqUrl = ComicUrl.requireParse(http.recordedRequests.last().url)
        assertEquals("2001000", cursorReqUrl.queryParameter("next"))
        assertEquals("language:chinese$", cursorReqUrl.queryParameter("f_search"))

        // Popular page applies client-side language filter
        val popPage = eh.feedSource!!.loadFeed(
            FeedRequest(
                siteId = SiteId.EHENTAI,
                section = "popular",
                sort = FeedSort.POPULAR,
                language = ComicLanguage.CHINESE,
            ),
        )
        assertEquals(1, popPage.items.size)
        assertEquals("2001001", popPage.items.single().id)
        assertEquals("chinese", popPage.items.single().language)

        // Reject cross-host cursor URLs
        try {
            eh.feedSource!!.loadFeed(FeedRequest(SiteId.EHENTAI, cursor = "https://evil.example.com/?next=1"))
            fail("Expected SiteException.ParseError")
        } catch (_: SiteException.ParseError) {
            // expected
        }
    }

    @Test
    fun `ehentai detail pagination, original image resolution, quota error, and nl reload work`() = runTest {
        val http = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val eh = EhentaiSitePlugin(http, store)

        val galleryUrl = "https://e-hentai.org/g/2001001/abcdef1234/"
        http.onUrlText(galleryUrl, sampleDetailHtml("e-hentai.org"))
        http.onUrlText("$galleryUrl?p=1", sampleDetailPage2Html("e-hentai.org"))
        http.onUrlText(
            "https://e-hentai.org/s/cccc3333/2001001-3",
            sampleImagePageHtml("e-hentai.org"),
        )
        http.onUrlText(
            "https://e-hentai.org/s/dddd4444/2001001-4",
            sampleImagePageHtml("e-hentai.org", imgSrc = "https://ehgt.org/img/509.gif"),
        )

        val detail = eh.gallerySource!!.loadGalleryDetail(galleryUrl)
        assertEquals("2001001", detail.item.id)
        assertEquals(4, detail.pageCount)
        assertEquals(2, detail.previewPageSize)
        assertEquals("42.5 MB", detail.fileSizeLabel)
        assertEquals("https://ehgt.org/cover/2001001.jpg", detail.item.coverUrl)
        assertTrue(detail.tags.any { it.slug == "female:big breasts" && it.namespace == "female" })
        assertTrue(detail.authors.any { it.name == "TestUploader" && it.role == "uploader" })
        assertTrue(detail.authors.any { it.name == "demo artist" && it.role == "artist" })
        assertTrue(detail.characters.any { it.label == "alice" })

        // Page 3 triggers paginated ?p=1 fetch and resolves display image + original metadata + nl token
        val page3 = eh.gallerySource!!.loadGalleryPage(detail, pageIndex = 3)
        assertEquals(3, page3.index)
        assertEquals("https://hath.network/h/sample-display.jpg", page3.imageUrl)
        assertEquals("https://e-hentai.org/fullimg.php?gid=2001001&page=3&key=xyz", page3.originalImageUrl)
        assertEquals("12345-67890", page3.reloadToken)
        assertEquals("4.2 MB", page3.originalSizeLabel)

        // preferOriginal = true returns fullimg.php as imageUrl
        val page3Orig = eh.gallerySource!!.resolvePageImage(
            pageUrl = "https://e-hentai.org/s/cccc3333/2001001-3",
            preferOriginal = true,
        )
        assertEquals("https://e-hentai.org/fullimg.php?gid=2001001&page=3&key=xyz", page3Orig.imageUrl)

        // reloadUrlOnError returns ?nl= URL on Forbidden / TlsError / Network, null on QuotaExceeded
        assertEquals(
            "https://e-hentai.org/s/cccc3333/2001001-3?nl=12345-67890",
            eh.gallerySource!!.reloadUrlOnError(SiteException.Forbidden(isChallenge = false), page3),
        )
        assertEquals(
            "https://e-hentai.org/s/cccc3333/2001001-3?nl=12345-67890",
            eh.gallerySource!!.reloadUrlOnError(SiteException.TlsError("reset"), page3),
        )
        assertNull(eh.gallerySource!!.reloadUrlOnError(SiteException.QuotaExceeded(), page3))

        // 509.gif throws SiteException.QuotaExceeded
        try {
            eh.gallerySource!!.loadGalleryPage(detail, pageIndex = 4)
            fail("Expected SiteException.QuotaExceeded")
        } catch (_: SiteException.QuotaExceeded) {
            // expected
        }
    }

    @Test
    fun `ehentai link resolver handles gallery, page, tag, uploader, and category links`() {
        val eh = EhentaiSitePlugin(FakeHttpClientAdapter(), InMemorySiteSessionStore())
        val resolver = eh.linkResolver!!

        val galleryLink = resolver.resolve("https://www.e-hentai.org/g/987654/abcdef0123/?p=2")
        assertEquals(
            ResolvedLink.Gallery(SiteId.EHENTAI, "987654", "https://e-hentai.org/g/987654/abcdef0123/"),
            galleryLink,
        )

        val pageLink = resolver.resolve("https://e-hentai.org/s/deadbeef01/987654-12")
        assertEquals(
            ResolvedLink.Gallery(SiteId.EHENTAI, "987654", "https://e-hentai.org/s/deadbeef01/987654-12", pageIndex = 12),
            pageLink,
        )

        val tagLink = resolver.resolve("https://e-hentai.org/tag/female:big+breasts") as ResolvedLink.Feed
        assertEquals(SearchScope.TAG, tagLink.request.scope)
        assertEquals("female:big breasts", tagLink.request.query)

        val uploaderLink = resolver.resolve("https://e-hentai.org/?f_search=uploader%3A%22Alice%22") as ResolvedLink.Feed
        assertEquals(SearchScope.AUTHOR, uploaderLink.request.scope)
        assertEquals("Alice", uploaderLink.request.query)

        val catLink = resolver.resolve("https://e-hentai.org/?f_cats=1019") as ResolvedLink.Feed
        assertEquals("4", catLink.request.category)
        assertNotNull(resolver.canonicalize("https://e-hentai.org/g/987654/abcdef0123?p=1#top"))
    }

    @Test
    fun `ehentai formats multi-word and namespaced excludeTags safely alongside non-empty query`() = runTest {
        val http = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val eh = EhentaiSitePlugin(http, store)
        http.onMatch({ true }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, sampleFeedHtml("e-hentai.org"))
        }

        eh.searchSource!!.search(
            FeedRequest(
                siteId = SiteId.EHENTAI,
                query = "elf",
                advancedFilters = AdvancedSearchFilters(
                    excludeTags = listOf("guro", "big breasts", "female:sole female"),
                ),
            ),
        )
        val url = ComicUrl.requireParse(http.recordedRequests.last().url)
        val fSearch = url.queryParameter("f_search").orEmpty()
        assertTrue(fSearch.contains("elf"))
        assertTrue(fSearch.contains("-guro"))
        assertTrue(fSearch.contains("-\"big breasts\""))
        assertTrue(fSearch.contains("-female:\"sole female\""))
    }

    @Test
    fun `ehentai formats TAG scope search with quotes and deduplicates same artist id`() = runTest {
        val http = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val eh = EhentaiSitePlugin(http, store)
        http.onMatch({ true }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, sampleFeedHtml("e-hentai.org"))
        }

        eh.searchSource!!.search(
            FeedRequest(siteId = SiteId.EHENTAI, query = "female:big breasts", scope = SearchScope.TAG),
        )
        val url = ComicUrl.requireParse(http.recordedRequests.last().url)
        assertEquals("female:\"big breasts\"", url.queryParameter("f_search"))

        val detailHtml = """
            <html><body>
            <div id="gd2"><h1 id="gn">Demo</h1></div>
            <div id="gdc"><div class="cn">Manga</div></div>
            <div id="taglist">
                <a href="https://e-hentai.org/tag/artist:alice">alice</a>
                <a href="https://e-hentai.org/tag/group:alice">alice</a>
            </div>
            <div id="gdt"><a href="https://e-hentai.org/s/aaaaaaaaaa/1-1"><img src="https://ehgt.org/t/1.jpg" /></a></div>
            </body></html>
        """.trimIndent()
        http.onUrlText("https://e-hentai.org/g/1/aaaaaaaaaa/", detailHtml)
        val detail = eh.gallerySource!!.loadGalleryDetail("https://e-hentai.org/g/1/aaaaaaaaaa/")
        assertEquals(listOf("alice"), detail.authors.map { it.id })
    }

    @Test
    fun `exhentai 403 challenge throws Forbidden and ehentai skips translated language tag and handles limits`() = runTest {
        val exHttp = FakeHttpClientAdapter()
        val exStore = InMemorySiteSessionStore()
        exStore.saveCookies(SiteId.EXHENTAI.accountId, mapOf("ipb_member_id" to "1", "ipb_pass_hash" to "0123456789abcdef0123456789abcdef"))
        exHttp.onMatch({ true }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                "<html><title>Just a moment...</title></html>",
                code = 403,
                headers = mapOf("cf-mitigated" to listOf("challenge")),
            )
        }
        val ex = ExhentaiSitePlugin(exHttp, exStore)
        try {
            ex.feedSource!!.loadFeed(FeedRequest(SiteId.EXHENTAI))
            fail("Expected SiteException.Forbidden challenge")
        } catch (err: SiteException.Forbidden) {
            assertTrue(err.isChallenge)
        }

        val ehHttp = FakeHttpClientAdapter()
        val eh = EhentaiSitePlugin(ehHttp, InMemorySiteSessionStore())
        val translatedFirstHtml = """
            <html><body><table class="itg"><tr>
              <td class="gl2c"><div class="cn">Manga</div></td>
              <td class="gl3c glname"><a href="https://e-hentai.org/g/3001/abcdef1234/"><div class="glink">Demo</div><div class="gt" title="language:translated">translated</div><div class="gt" title="language:chinese">chinese</div></a></td>
              <td class="gl4c"><div>20 pages</div></td>
            </tr></table></body></html>
        """.trimIndent()
        ehHttp.onMatch({ req -> req.url.contains("f_search=tag") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, translatedFirstHtml)
        }
        val langFeed = eh.searchSource!!.search(FeedRequest(SiteId.EHENTAI, query = "tag"))
        assertEquals("chinese", langFeed.items.single().language)

        ehHttp.onMatch({ req -> req.url.contains("f_search=none") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, "<html><body><p>No hits found</p></body></html>")
        }
        assertTrue(eh.searchSource!!.search(FeedRequest(SiteId.EHENTAI, query = "none")).items.isEmpty())

        ehHttp.onMatch({ req -> req.url.contains("f_search=quota") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, "Image View Limit Exceeded")
        }
        assertTrue(runCatching { eh.searchSource!!.search(FeedRequest(SiteId.EHENTAI, query = "quota")) }.exceptionOrNull() is SiteException.QuotaExceeded)

        ehHttp.onMatch({ req -> req.url.contains("f_search=ban") }) { req ->
            FakeHttpClientAdapter.htmlResponse(req.url, "Your IP address has been temporarily banned")
        }
        assertTrue(runCatching { eh.searchSource!!.search(FeedRequest(SiteId.EHENTAI, query = "ban")) }.exceptionOrNull() is SiteException.RateLimited)
    }

    @Test
    fun `e-hentai and exhentai expose native discovery sections and build their URLs`() {
        val sections = EhSharedEngine.buildSections()
        val ids = sections.map { it.id }.toSet()
        assertTrue("latest" in ids)
        assertTrue("popular" in ids)
        assertTrue("toplist" in ids)
        assertTrue("watched" in ids)
        assertTrue("mytags" in ids)

        // Both hostnames share the same native surfaces.
        val eh = EhentaiSitePlugin(FakeHttpClientAdapter(), InMemorySiteSessionStore())
        val ex = ExhentaiSitePlugin(FakeHttpClientAdapter(), InMemorySiteSessionStore())
        assertEquals(ids, eh.descriptor.sections.map { it.id }.toSet())
        assertEquals(ids, ex.descriptor.sections.map { it.id }.toSet())

        // Native sections resolve to the site's own endpoints rather than the default gallery list.
        val engine = EhSharedEngine(
            descriptor = eh.descriptor,
            httpClient = FakeHttpClientAdapter(),
            sessionStore = InMemorySiteSessionStore(),
            requiresAuthGate = false,
        )
        // The engine appends its inline-view parameter, so assert on path + native params.
        val toplist = engine.resolveFeedUrl(FeedRequest(SiteId.EHENTAI, section = "toplist"))
        assertTrue(toplist.startsWith("https://e-hentai.org/toplist.php?"))
        assertTrue(toplist.contains("tl=1"))
        assertTrue(engine.resolveFeedUrl(FeedRequest(SiteId.EHENTAI, section = "watched"))
            .startsWith("https://e-hentai.org/watched"))
        assertTrue(engine.resolveFeedUrl(FeedRequest(SiteId.EHENTAI, section = "mytags"))
            .startsWith("https://e-hentai.org/mytags"))
        assertTrue(engine.resolveFeedUrl(FeedRequest(SiteId.EHENTAI, section = "popular"))
            .startsWith("https://e-hentai.org/popular"))
    }
}
