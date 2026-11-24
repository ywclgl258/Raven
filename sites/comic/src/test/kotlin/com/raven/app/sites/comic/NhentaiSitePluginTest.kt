package com.raven.app.sites.comic

import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.ResolvedLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NhentaiSitePluginTest {

    private val cdnJson = """
        {
          "image_servers": [
            "https://evil.example.com/",
            "https://i1.nhentai.net/",
            "https://i2.nhentai.net/"
          ],
          "thumb_servers": [
            "https://t1.nhentai.net/",
            "http://insecure.nhentai.net/"
          ]
        }
    """.trimIndent()

    private fun gallerySummaryJson(id: Int, mediaId: Int, title: String, duplicateExt: Boolean = false): String {
        val thumbPath = if (duplicateExt) "galleries/$mediaId/thumb.webp.webp" else "galleries/$mediaId/thumb.webp"
        return """
            {
              "id": $id,
              "media_id": "$mediaId",
              "english_title": "$title",
              "thumbnail": { "path": "$thumbPath", "width": 350, "height": 500 },
              "num_pages": 3,
              "num_favorites": 120
            }
        """.trimIndent()
    }

    private fun galleryDetailJson(id: Int = 550001, mediaId: Int = 990001): String = """
        {
          "id": $id,
          "media_id": "$mediaId",
          "title": { "english": "[ArtistA] Sample Book", "japanese": "[作家A] サンプル本" },
          "thumbnail": { "path": "galleries/$mediaId/cover.jpg.jpg", "width": 350, "height": 495 },
          "num_pages": 3,
          "num_favorites": 888,
          "tags": [
            { "type": "artist", "name": "artist_a", "count": 42 },
            { "type": "character", "name": "heroine", "count": 100 },
            { "type": "category", "name": "doujinshi", "count": 5000 },
            { "type": "language", "name": "chinese", "count": 2000 },
            { "type": "tag", "name": "full color", "count": 900 }
          ],
          "pages": [
            { "number": 1, "path": "galleries/$mediaId/1.webp.webp", "width": 1280, "height": 1800 },
            { "number": 2, "path": "galleries/$mediaId/2.png", "width": 1280, "height": 1800 },
            { "number": 3, "path": "galleries/$mediaId/3.jpg", "width": 1280, "height": 1800 }
          ]
        }
    """.trimIndent()

    @Test
    fun `cdn allowlist filters untrusted hosts and caches for 30 minutes`() = runTest {
        var now = 1_000_000L
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://nhentai.net/api/v2/cdn", cdnJson)
        http.onUrlText(
            "https://nhentai.net/api/v2/galleries?page=1",
            """{"num_pages": 5, "result": [${gallerySummaryJson(5001, 8001, "Book One", duplicateExt = true)}]}""",
        )

        val plugin = NhentaiSitePlugin(http, clockMs = { now })
        val feed1 = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.NHENTAI, page = 1))
        assertEquals(1, feed1.items.size)
        assertEquals("https://t1.nhentai.net/galleries/8001/thumb.webp", feed1.items.single().coverUrl)
        assertEquals(5, feed1.totalPages)
        assertTrue(feed1.hasMore)

        // Second call within 30 minutes reuses cached /api/v2/cdn
        now += 10 * 60 * 1000L
        plugin.feedSource!!.loadFeed(FeedRequest(SiteId.NHENTAI, page = 1))
        val cdnCallsBeforeExpiry = http.recordedRequests.count { it.url.endsWith("/api/v2/cdn") }
        assertEquals(1, cdnCallsBeforeExpiry)

        // After 30 minutes CDN is re-fetched
        now += 25 * 60 * 1000L
        plugin.feedSource!!.loadFeed(FeedRequest(SiteId.NHENTAI, page = 1))
        val cdnCallsAfterExpiry = http.recordedRequests.count { it.url.endsWith("/api/v2/cdn") }
        assertEquals(2, cdnCallsAfterExpiry)
    }

    @Test
    fun `popular today, search filters, detail parsing, and round-robin CDN work`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://nhentai.net/api/v2/cdn", cdnJson)
        http.onUrlText(
            "https://nhentai.net/api/v2/galleries/popular",
            """[${gallerySummaryJson(7001, 9001, "Popular Book")}]""",
        )
        http.onUrlText("https://nhentai.net/api/v2/galleries/550001", galleryDetailJson())
        http.onMatch({ req -> req.url.startsWith("https://nhentai.net/api/v2/search?") }) { req ->
            FakeHttpClientAdapter.htmlResponse(
                req.url,
                """{"num_pages": 2, "result": [${gallerySummaryJson(7002, 9002, "Filtered Result")}]}""",
            )
        }

        val plugin = NhentaiSitePlugin(http)

        // Popular today array response
        val popular = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.NHENTAI, section = "popular"))
        assertEquals(1, popular.items.size)
        assertEquals("7001", popular.items.single().id)
        assertFalse(popular.hasMore)

        // Search with artist scope + advanced filters
        plugin.searchSource!!.search(
            FeedRequest(
                siteId = SiteId.NHENTAI,
                query = "artist_a",
                scope = SearchScope.AUTHOR,
                sort = FeedSort.POPULAR,
                language = ComicLanguage.CHINESE,
                advancedFilters = AdvancedSearchFilters(
                    includeTags = listOf("tag:\"full color\""),
                    excludeTags = listOf("guro"),
                    minPages = 10,
                    maxPages = 80,
                    category = "doujinshi",
                ),
            ),
        )
        val searchUrl = ComicUrl.requireParse(http.recordedRequests.last().url)
        assertEquals("popular", searchUrl.queryParameter("sort"))
        val q = searchUrl.queryParameter("query").orEmpty()
        assertTrue(q.contains("artist:\"artist_a\""))
        assertTrue(q.contains("language:chinese"))
        assertTrue(q.contains("category:doujinshi"))
        assertTrue(q.contains("-guro"))
        assertTrue(q.contains("pages:>=10"))
        assertTrue(q.contains("pages:<=80"))

        // Multi-word & EH-namespaced excludeTags quote properly, strip unsupported female: namespace,
        // and anchor pure-exclude feed requests with pages:>0
        val multiExcludeUrl = ComicUrl.requireParse(
            plugin.resolveApiUrl(
                FeedRequest(
                    siteId = SiteId.NHENTAI,
                    query = "elf",
                    advancedFilters = AdvancedSearchFilters(
                        excludeTags = listOf("big breasts", "female:sole_female", "tag:dark skin"),
                    ),
                ),
            ),
        )
        val multiQ = multiExcludeUrl.queryParameter("query").orEmpty()
        assertTrue(multiQ.contains("elf"))
        assertTrue(multiQ.contains("-\"big breasts\""))
        assertTrue(multiQ.contains("-\"sole female\""))
        assertTrue(multiQ.contains("-tag:\"dark skin\""))

        val pureExcludeUrl = ComicUrl.requireParse(
            plugin.resolveApiUrl(
                FeedRequest(
                    siteId = SiteId.NHENTAI,
                    advancedFilters = AdvancedSearchFilters(excludeTags = listOf("guro")),
                ),
            ),
        )
        assertEquals("pages:>0 -guro", pureExcludeUrl.queryParameter("query"))

        // Gallery detail deduplicates cover/page extensions and rotates image_servers
        val detail = plugin.gallerySource!!.loadGalleryDetail("https://nhentai.net/g/550001/")
        assertEquals("https://t1.nhentai.net/galleries/990001/cover.jpg", detail.item.coverUrl)
        assertEquals(3, detail.pageCount)
        assertEquals("https://i1.nhentai.net/galleries/990001/1.webp", detail.pages[0].imageUrl)
        assertEquals("https://i2.nhentai.net/galleries/990001/2.png", detail.pages[1].imageUrl)
        assertEquals("https://i1.nhentai.net/galleries/990001/3.jpg", detail.pages[2].imageUrl)
        assertEquals("chinese", detail.item.language)
        assertEquals("artist_a", detail.authors.single().name)
        assertEquals("heroine", detail.characters.single().label)
        assertEquals("doujinshi", detail.categories.single().label)
    }

    @Test
    fun `dual language merger combines chinese and disjoint japanese streams across pages`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("https://nhentai.net/api/v2/cdn", cdnJson)

        // Generate 15 Chinese (even IDs 1000..972) and 15 Japanese (odd IDs 999..971)
        val cnPage1 = (0 until 15).map { i -> gallerySummaryJson(1000 - i * 2, 10000 + i, "CN $i") }
        val jpPage1 = (0 until 15).map { i -> gallerySummaryJson(999 - i * 2, 20000 + i, "JP $i") }

        http.onMatch({ req -> req.url.startsWith("https://nhentai.net/api/v2/search?") }) { req ->
            val q = ComicUrl.requireParse(req.url).queryParameter("query").orEmpty()
            val body = if (q.contains("-language:chinese")) {
                """{"num_pages": 1, "result": [${jpPage1.joinToString(",")}]}"""
            } else {
                """{"num_pages": 1, "result": [${cnPage1.joinToString(",")}]}"""
            }
            FakeHttpClientAdapter.htmlResponse(req.url, body)
        }

        val plugin = NhentaiSitePlugin(http)

        // Page 1 returns 24 items sorted strictly descending by ID
        val page1 = plugin.feedSource!!.loadFeed(
            FeedRequest(SiteId.NHENTAI, language = ComicLanguage.CHINESE_AND_JAPANESE, page = 1),
        )
        assertEquals(24, page1.items.size)
        assertTrue(page1.hasMore)
        assertNotNull(page1.nextCursor)
        val ids1 = page1.items.map { it.id.toInt() }
        assertEquals((1000 downTo 977).toList(), ids1)

        // Verify stream B appended -language:chinese
        val streamBReq = http.recordedRequests.first { req ->
            req.url.contains("search?") && ComicUrl.requireParse(req.url).queryParameter("query")?.contains("-language:chinese") == true
        }
        assertTrue(ComicUrl.requireParse(streamBReq.url).queryParameter("query")!!.contains("language:japanese -language:chinese"))

        // Page 2 resumes from cursor and delivers remaining 6 items
        val page2 = plugin.feedSource!!.loadFeed(
            FeedRequest(SiteId.NHENTAI, language = ComicLanguage.CHINESE_AND_JAPANESE, page = 2, cursor = page1.nextCursor),
        )
        assertEquals(6, page2.items.size)
        assertFalse(page2.hasMore)
        assertEquals((976 downTo 971).toList(), page2.items.map { it.id.toInt() })
    }

    @Test
    fun `rate limit error maps Retry-After and link resolver parses all nhentai link shapes`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText(
            url = "https://nhentai.net/api/v2/galleries?page=1",
            body = "Too Many Requests",
            code = 429,
            headers = mapOf("Retry-After" to listOf("45")),
        )
        val plugin = NhentaiSitePlugin(http)
        try {
            plugin.feedSource!!.loadFeed(FeedRequest(SiteId.NHENTAI))
            fail("Expected SiteException.RateLimited")
        } catch (e: SiteException.RateLimited) {
            assertEquals(45L, e.retryAfterSeconds)
        }

        val resolver = plugin.linkResolver!!
        assertEquals(
            ResolvedLink.Gallery(SiteId.NHENTAI, "177013", "https://nhentai.net/g/177013/"),
            resolver.resolve("https://nhentai.net/g/177013/"),
        )
        assertEquals(
            ResolvedLink.Gallery(SiteId.NHENTAI, "177013", "https://nhentai.net/g/177013/5/", pageIndex = 5),
            resolver.resolve("https://nhentai.net/g/177013/5/"),
        )
        val artistFeed = resolver.resolve("https://nhentai.net/artist/shindo-l/") as ResolvedLink.Feed
        assertEquals(SearchScope.AUTHOR, artistFeed.request.scope)
        assertEquals("shindo l", artistFeed.request.query)
        val catFeed = resolver.resolve("https://nhentai.net/category/manga/") as ResolvedLink.Feed
        assertEquals("manga", catFeed.request.category)
    }

    @Test
    fun `nhentai search wires date, popular, popular-today sort params and supportedSearchSorts`() {
        val plugin = NhentaiSitePlugin(FakeHttpClientAdapter())
        assertEquals(
            listOf(FeedSort.NEWEST, FeedSort.POPULAR, FeedSort.TRENDING),
            plugin.searchSource.supportedSearchSorts(SearchScope.ALL),
        )
        val newestUrl = ComicUrl.requireParse(
            plugin.resolveApiUrl(FeedRequest(SiteId.NHENTAI, query = "elf", sort = FeedSort.NEWEST)),
        )
        assertEquals("date", newestUrl.queryParameter("sort"))

        val popularUrl = ComicUrl.requireParse(
            plugin.resolveApiUrl(FeedRequest(SiteId.NHENTAI, query = "elf", sort = FeedSort.POPULAR)),
        )
        assertEquals("popular", popularUrl.queryParameter("sort"))

        val trendingUrl = ComicUrl.requireParse(
            plugin.resolveApiUrl(FeedRequest(SiteId.NHENTAI, query = "elf", sort = FeedSort.TRENDING)),
        )
        assertEquals("popular-today", trendingUrl.queryParameter("sort"))
    }
}
