package com.raven.app.sites.comic

import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the review findings covering P2 site-capability honesty and
 * partial/cyclic pagination:
 *
 * - Guazimanhua / Hipmh / Baozimh must not advertise sorts their URLs ignore.
 * - Guazimanhua must distinguish an explicit empty state from an unparseable page.
 * - Manwa2 must not treat a transient empty middle page as "chapter over".
 * - Baozimh must not cache a cyclic / over-limit partial chapter as complete.
 */
class SortAndPaginationSemanticsTest {

    private fun jsonResp(req: SiteHttpRequest, body: String, code: Int = 200): SiteHttpResponse =
        FakeHttpClientAdapter.htmlResponse(
            url = req.url,
            html = body,
            code = code,
            headers = mapOf("Content-Type" to listOf("application/json")),
        )

    // -------------------------------------------------------------------------
    // Guazimanhua: sort reaches the request, empty-vs-unparseable
    // -------------------------------------------------------------------------

    @Test
    fun guazimanhuaEveryAdvertisedSortChangesTheSearchRequest() = runTest {
        val http = FakeHttpClientAdapter()
        val cardsHtml = """
            <html><body>
              <a href="/comic.php?id=18741"><img src="https://img.guazicdn.com/th/comics/cover/16118/260421/cover.webp" /><span class="title">重生水浒世界之最强武大郎</span></a>
            </body></html>
        """.trimIndent()

        // Stub every sort variant so the assertion is about the URL, not about the response body.
        for (sortParam in listOf("update", "hits", "daily", "score")) {
            http.onUrlText("https://www.guazimanhua.com/category.php?keyword=test&sort=$sortParam&page=1", cardsHtml)
        }

        val plugin = GuazimanhuaSitePlugin(http)
        val advertised = plugin.feedSource.supportedSorts(
            FeedRequest(siteId = SiteId.GUAZIMANHUA, query = "test", sort = FeedSort.NEWEST),
        )
        assertEquals(
            listOf(FeedSort.NEWEST, FeedSort.POPULAR, FeedSort.TRENDING, FeedSort.RATING),
            advertised,
        )

        val expectedParamBySort = mapOf(
            FeedSort.NEWEST to "update",
            FeedSort.POPULAR to "hits",
            FeedSort.TRENDING to "daily",
            FeedSort.RATING to "score",
        )
        val seenUrls = mutableListOf<String>()
        val resultCounts = mutableListOf<Int>()
        for (sort in advertised) {
            http.recordedRequests.clear()
            val page = plugin.searchSource.search(FeedRequest(siteId = SiteId.GUAZIMANHUA, query = "test", sort = sort))
            resultCounts += page.items.size
            val sent = http.recordedRequests.last().url
            seenUrls += sent
            assertTrue(
                "sort=$sort must map to sort=${expectedParamBySort[sort]} in $sent",
                sent.contains("sort=${expectedParamBySort[sort]}"),
            )
        }
        assertEquals(List(advertised.size) { 1 }, resultCounts)
        // Distinct sorts produce distinct request URLs: no advertised choice is a no-op.
        assertEquals(advertised.size, seenUrls.distinct().size)
    }

    @Test
    fun guazimanhuaZeroResultPageReturnsEmptyWhileUnrecognizedPageRaisesParseError() = runTest {
        val http = FakeHttpClientAdapter()
        // Genuine empty state carries the site's explicit marker.
        http.onUrlText(
            "https://www.guazimanhua.com/category.php?keyword=empty&sort=hits&page=1",
            "<html><body><div class=\"empty-list\">没有找到相关漫画</div></body></html>",
        )
        // Unrecognized markup: HTTP 200 but no cards and no empty marker.
        http.onUrlText(
            "https://www.guazimanhua.com/category.php?keyword=broken&sort=hits&page=1",
            "<html><body><div class=\"unexpected-layout\">hello</div></body></html>",
        )

        val plugin = GuazimanhuaSitePlugin(http)

        val empty = plugin.searchSource.search(FeedRequest(siteId = SiteId.GUAZIMANHUA, query = "empty"))
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasMore)
        assertNull(empty.nextPage)

        val broken = runCatching {
            plugin.searchSource.search(FeedRequest(siteId = SiteId.GUAZIMANHUA, query = "broken"))
        }.exceptionOrNull()
        assertTrue("unrecognized page must surface ParseError, got $broken", broken is SiteException.ParseError)
    }

    @Test
    fun guazimanhuaPopularSectionKeepsHitsSortAndChapterFeedAdvertisesNoSorts() = runTest {
        val emptyPlugin = GuazimanhuaSitePlugin(FakeHttpClientAdapter())
        assertEquals(
            emptyList<FeedSort>(),
            emptyPlugin.feedSource.supportedSorts(
                FeedRequest(siteId = SiteId.GUAZIMANHUA, section = "chapters", category = "18741"),
            ),
        )

        // DEFAULT sort on the popular section still routes to hits.
        val http = FakeHttpClientAdapter()
        http.onUrlText(
            "https://www.guazimanhua.com/category.php?sort=hits&page=1",
            "<html><body><a href=\"/comic.php?id=1\"><span class=\"title\">X</span></a></body></html>",
        )
        val plugin = GuazimanhuaSitePlugin(http)
        plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.GUAZIMANHUA, section = "popular"))
        assertTrue(http.recordedRequests.last().url.contains("sort=hits"))
    }

    // -------------------------------------------------------------------------
    // Hipmh: sorts only where the URL encodes them
    // -------------------------------------------------------------------------

    @Test
    fun hipmhSortsArePerSectionAndKeywordSearchAdvertisesRelevanceOnly() = runTest {
        val http = FakeHttpClientAdapter()
        val listJson = """{"code":200,"data":{"page":1,"total_pages":1,"items":[
            {"mid":"m1","title":"Hit","vertical_image_url":"/kk/vertical/hit.webp"}
        ]}}"""
        http.onMatch({ req -> req.url.startsWith("https://hipapi1.s3file.top/v1/mangas") }) { req ->
            jsonResp(req, listJson)
        }

        val plugin = HipmhSitePlugin(http)

        // Unfiltered list: NEWEST / POPULAR both reach the request as distinct sort params.
        assertEquals(
            listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.HIPMH, section = "latest")),
        )
        http.recordedRequests.clear()
        plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "latest", sort = FeedSort.NEWEST))
        val newestUrl = http.recordedRequests.last().url
        http.recordedRequests.clear()
        plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "latest", sort = FeedSort.POPULAR))
        val popularUrl = http.recordedRequests.last().url
        assertTrue("NEWEST must send sort=updated: $newestUrl", newestUrl.contains("sort=updated"))
        assertTrue("POPULAR must send sort=popular: $popularUrl", popularUrl.contains("sort=popular"))
        assertTrue("two advertised sorts must differ", newestUrl != popularUrl)

        // Sort-fixed sections advertise exactly the one value they encode.
        assertEquals(
            listOf(FeedSort.POPULAR),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.HIPMH, section = "popular")),
        )
        assertEquals(
            listOf(FeedSort.POPULAR),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.HIPMH, section = "weekly")),
        )
        assertEquals(
            listOf(FeedSort.NEWEST),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.HIPMH, section = "new")),
        )
        assertEquals(
            listOf(FeedSort.NEWEST),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.HIPMH, section = "genre_wu-xia")),
        )
        // Keyword search accepts no sort parameter: only relevance is honest.
        assertEquals(
            listOf(FeedSort.RELEVANCE),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.HIPMH, query = "test")),
        )
        assertEquals(
            emptyList<FeedSort>(),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.HIPMH, section = "chapters", category = "m1")),
        )

        // Sort-fixed section URL must not carry a caller-selected sort override.
        http.recordedRequests.clear()
        plugin.feedSource.loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "new", sort = FeedSort.POPULAR))
        val newSectionUrl = http.recordedRequests.last().url
        assertTrue("'new' keeps sort=latest regardless of caller sort: $newSectionUrl", newSectionUrl.contains("sort=latest"))
        assertFalse("'new' must not accept a popular override: $newSectionUrl", newSectionUrl.contains("sort=popular"))
    }

    // -------------------------------------------------------------------------
    // Baozimh: sorts only where the URL encodes them
    // -----------------------------------------------------------------

    @Test
    fun baozimhSortsAreSingleValuedPerSectionAndSearchIsRelevanceOnly() {
        val plugin = BaozimhSitePlugin(FakeHttpClientAdapter())

        assertEquals(
            listOf(FeedSort.POPULAR),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.BAOZIMH, section = "popular")),
        )
        assertEquals(
            listOf(FeedSort.NEWEST),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.BAOZIMH, section = "new")),
        )
        // Region / type / status sections all fall through to the default popular listing.
        assertEquals(
            listOf(FeedSort.POPULAR),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.BAOZIMH, section = "type_lianai")),
        )
        assertEquals(
            listOf(FeedSort.RELEVANCE),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.BAOZIMH, query = "test")),
        )
        assertEquals(
            emptyList<FeedSort>(),
            plugin.feedSource.supportedSorts(FeedRequest(siteId = SiteId.BAOZIMH, section = "chapters", category = "baozi-1")),
        )
    }

    // -------------------------------------------------------------------------
    // Manwa2: transient empty middle page must not be cached as a whole chapter
    // -----------------------------------------------------------------

    @Test
    fun manwa2RecoversFromTransientEmptyMiddlePageAndNeverCachesAPrefix() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url == "https://manwauu.cc/cate?page=1" }) { req ->
            SiteHttpResponse(code = 200, finalUrl = req.url, headers = emptyMap(), body = "<html></html>".toByteArray())
        }
        http.onMatch({ req -> req.url == "https://manwauu.cc/api/comic/777/chapters" }) { req ->
            jsonResp(req, """{"code":200,"data":[{"id":301,"title":"第1话","sortId":"1"}]}""")
        }

        var page2Calls = 0
        http.onMatch({ req -> req.url.startsWith("https://manwauu.cc/api/comic/image/301") }) { req ->
            val page = Regex("""page=(\d+)""").find(req.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val body = when (page) {
                1 -> """{"code":200,"data":{"images":[{"url":"https://img.oldcdn.example/en_images/777/1.webp"}],"pagination":{"totalPages":3}}}"""
                2 -> {
                    // First attempt is a transient empty page, second attempt recovers.
                    page2Calls++
                    if (page2Calls == 1) {
                        """{"code":200,"data":{"images":[],"pagination":{"totalPages":3}}}"""
                    } else {
                        """{"code":200,"data":{"images":[{"url":"https://img.oldcdn.example/en_images/777/2.webp"}],"pagination":{"totalPages":3}}}"""
                    }
                }
                else -> """{"code":200,"data":{"images":[{"url":"https://img.oldcdn.example/en_images/777/3.webp"}],"pagination":{"totalPages":3}}}"""
            }
            jsonResp(req, body)
        }

        val plugin = Manwa2SitePlugin(http)
        val detail = plugin.gallerySource.loadGalleryDetail("https://manwauu.cc/comic/777/301")

        // All three pages present: the transient empty page 2 was retried, not treated as chapter end.
        // Manwa2 intentionally rewrites image hosts onto its own CDN, so assert on the path suffix
        // (the page identity) rather than the literal upstream host.
        assertEquals(3, detail.pages.size)
        assertEquals(
            listOf(
                "/en_images/777/1.webp",
                "/en_images/777/2.webp",
                "/en_images/777/3.webp",
            ),
            detail.pages.map { java.net.URI(it.imageUrl).path },
        )
        assertTrue("page 2 must have been retried at least twice", page2Calls >= 2)
    }

    @Test
    fun manwa2PersistentEmptyMiddlePageFailsLoudlyInsteadOfReturningPrefix() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url == "https://manwauu.cc/cate?page=1" }) { req ->
            SiteHttpResponse(code = 200, finalUrl = req.url, headers = emptyMap(), body = "<html></html>".toByteArray())
        }
        http.onMatch({ req -> req.url == "https://manwauu.cc/api/comic/567/chapters" }) { req ->
            jsonResp(req, """{"code":200,"data":[{"id":302,"title":"第1话","sortId":"1"}]}""")
        }
        http.onMatch({ req -> req.url == "https://manwauu.cc/api/comic/567" }) { req ->
            jsonResp(req, """{"code":200,"data":{"id":567,"title":"Persist","pic":"/en_images/cover/567.webp"}}""")
        }
        http.onMatch({ req -> req.url.startsWith("https://manwauu.cc/api/comic/image/302") }) { req ->
            val page = Regex("""page=(\d+)""").find(req.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val body = when (page) {
                1 -> """{"code":200,"data":{"images":[{"url":"https://img.oldcdn.example/302/1.webp"}],"pagination":{"totalPages":3}}}"""
                // Middle page 2 is *persistently* empty while 3 pages were declared.
                else -> """{"code":200,"data":{"images":[],"pagination":{"totalPages":3}}}"""
            }
            jsonResp(req, body)
        }

        val plugin = Manwa2SitePlugin(http)
        val err = runCatching {
            plugin.gallerySource.loadGalleryDetail("https://manwauu.cc/comic/567/302")
        }.exceptionOrNull()
        assertTrue("a persistently empty middle page must not be cached as a complete chapter, got $err", err is SiteException.ParseError)

        // Nothing was cached: a subsequent (recovering) load must attempt the network again.
        http.onMatch({ req -> req.url.startsWith("https://manwauu.cc/api/comic/image/302") }) { req ->
            val page = Regex("""page=(\d+)""").find(req.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val body = """{"code":200,"data":{"images":[{"url":"https://img.oldcdn.example/302/$page.webp"}],"pagination":{"totalPages":3}}}"""
            jsonResp(req, body)
        }
        http.recordedRequests.clear()
        val recovered = plugin.gallerySource.loadGalleryDetail("https://manwauu.cc/comic/567/302")
        assertEquals(3, recovered.pages.size)
        assertTrue(
            "the failed load must not have populated the chapter cache",
            http.recordedRequests.any { it.url.contains("/api/comic/image/302") },
        )
    }

    // -------------------------------------------------------------------------
    // Baozimh: cyclic / over-limit pagination must not be cached as complete
    // -----------------------------------------------------------------

    @Test
    fun baozimhCyclicSubPageChainFailsInsteadOfCachingTruncatedChapter() = runTest {
        // Host-agnostic stubs: mirror rotation may swap the serving host between attempts, so the
        // fixture keys on the chapter path and switches behaviour by phase.
        var cyclicPhase = true
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> CHAPTER_PAGE_1_REGEX.containsMatchIn(req.url) }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """<html><body><div class="chapter-img"><img class="comic-contain__item" src="https://s1.baozimh.com/scomic/cyc-1/0/1/1.jpg"></div>
                   <div class="next_chapter"><a href="/comic/chapter/cyc-1/0_1_2.html">下一頁</a></div></body></html>""",
            )
        }
        http.onMatch({ req -> CHAPTER_PAGE_2_REGEX.containsMatchIn(req.url) }) { req ->
            // Cycle: page 2 points back at page 1; corrected chain continues to page 3.
            val next = if (cyclicPhase) "/comic/chapter/cyc-1/0_1.html" else "/comic/chapter/cyc-1/0_1_3.html"
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """<html><body><div class="chapter-img"><img class="comic-contain__item" src="https://s1.baozimh.com/scomic/cyc-1/0/1/2.jpg"></div>
                   <div class="next_chapter"><a href="$next">下一頁</a></div></body></html>""",
            )
        }
        http.onMatch({ req -> CHAPTER_PAGE_3_REGEX.containsMatchIn(req.url) }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """<html><body><div class="chapter-img"><img class="comic-contain__item" src="https://s1.baozimh.com/scomic/cyc-1/0/1/3.jpg"></div></body></html>""",
            )
        }

        val plugin = BaozimhSitePlugin(http)
        val err = runCatching {
            plugin.gallerySource.loadGalleryDetail("https://tw.webmota.com/comic/chapter/cyc-1/0_1.html")
        }.exceptionOrNull()
        // Mirror rotation may replace the specific error type, but the load MUST fail rather than
        // silently returning the 2-image prefix as a complete chapter.
        assertTrue("a cyclic next-page chain must raise, got $err", err is SiteException)

        // Cache stays cold: a corrected chain must re-hit the network and deliver all 3 pages.
        cyclicPhase = false
        val recovered = plugin.gallerySource.loadGalleryDetail("https://tw.webmota.com/comic/chapter/cyc-1/0_1.html")
        assertEquals(
            listOf(
                "https://s1.baozimh.com/scomic/cyc-1/0/1/1.jpg",
                "https://s1.baozimh.com/scomic/cyc-1/0/1/2.jpg",
                "https://s1.baozimh.com/scomic/cyc-1/0/1/3.jpg",
            ),
            recovered.pages.map { it.imageUrl },
        )
    }

    @Test
    fun baozimhNormalTerminalPageStillReturnsCompleteChapter() = runTest {
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> CHAPTER_PAGE_1_REGEX.containsMatchIn(req.url) && req.url.contains("/ok-1/") }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """<html><body><div class="chapter-img"><img class="comic-contain__item" src="https://s1.baozimh.com/scomic/ok-1/0/1/1.jpg"></div>
                   <div class="next_chapter"><a href="/comic/chapter/ok-1/0_1_2.html">下一頁</a></div></body></html>""",
            )
        }
        // Terminal page: no next_chapter anchor -> genuine end of chapter.
        http.onMatch({ req -> CHAPTER_PAGE_2_REGEX.containsMatchIn(req.url) && req.url.contains("/ok-1/") }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """<html><body><div class="chapter-img"><img class="comic-contain__item" src="https://s1.baozimh.com/scomic/ok-1/0/1/2.jpg"></div></body></html>""",
            )
        }

        val plugin = BaozimhSitePlugin(http)
        val recovered = plugin.gallerySource.loadGalleryDetail("https://tw.webmota.com/comic/chapter/ok-1/0_1.html")
        assertEquals(
            listOf(
                "https://s1.baozimh.com/scomic/ok-1/0/1/1.jpg",
                "https://s1.baozimh.com/scomic/ok-1/0/1/2.jpg",
            ),
            recovered.pages.map { it.imageUrl },
        )
    }

    @Test
    fun baozimhSubPageChainExceedingTraversalCeilingFailsInsteadOfCachingPrefix() = runTest {
        // 40 chained sub-pages exceed MAX_SUB_PAGES (20): the chain never terminates within the
        // guard, so the result is a protective exit and must not be returned as a complete chapter.
        var deepPhase = true
        val http = FakeHttpClientAdapter()
        http.onMatch({ req -> req.url.contains("/comic/chapter/deep-1/") }) { req ->
            val pageNumber = DEEP_PAGE_REGEX.find(req.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val nextLink = when {
                deepPhase && pageNumber < 40 ->
                    "<div class=\"next_chapter\"><a href=\"/comic/chapter/deep-1/0_1_${pageNumber + 1}.html\">下一頁</a></div>"
                !deepPhase && pageNumber < 3 ->
                    "<div class=\"next_chapter\"><a href=\"/comic/chapter/deep-1/0_1_${pageNumber + 1}.html\">下一頁</a></div>"
                else -> ""
            }
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                "<html><body><div class=\"chapter-img\"><img class=\"comic-contain__item\" " +
                    "src=\"https://s1.baozimh.com/scomic/deep-1/0/1/$pageNumber.jpg\"></div>$nextLink</body></html>",
            )
        }

        val plugin = BaozimhSitePlugin(http)
        val err = runCatching {
            plugin.gallerySource.loadGalleryDetail("https://tw.webmota.com/comic/chapter/deep-1/0_1.html")
        }.exceptionOrNull()
        assertTrue("a chain longer than MAX_SUB_PAGES must raise, got $err", err is SiteException)

        // Nothing cached: a short chain afterwards must re-hit the network and return all pages.
        deepPhase = false
        val recovered = plugin.gallerySource.loadGalleryDetail("https://tw.webmota.com/comic/chapter/deep-1/0_1.html")
        assertEquals(3, recovered.pages.size)
        assertEquals(
            listOf(
                "https://s1.baozimh.com/scomic/deep-1/0/1/1.jpg",
                "https://s1.baozimh.com/scomic/deep-1/0/1/2.jpg",
                "https://s1.baozimh.com/scomic/deep-1/0/1/3.jpg",
            ),
            recovered.pages.map { it.imageUrl },
        )
    }

    private companion object {
        // Match the chapter sub-page chain regardless of the serving mirror host or chapter id.
        private val CHAPTER_PAGE_1_REGEX = Regex("""/chapter/([a-z0-9-]+)/0_1\.html""")
        private val CHAPTER_PAGE_2_REGEX = Regex("""/chapter/([a-z0-9-]+)/0_1_2\.html""")
        private val CHAPTER_PAGE_3_REGEX = Regex("""/chapter/([a-z0-9-]+)/0_1_3\.html""")
        private val DEEP_PAGE_REGEX = Regex("""/chapter/deep-1/0_1(?:_(\d+))?\.html""")
    }
}
