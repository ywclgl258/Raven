package com.raven.app.sites.video

import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class R34vAndHanimeSitePluginTest {

    // ==================== R34V ====================

    @Test
    fun `r34v resolves feed, search, category, channel, and sort URLs`() {
        assertEquals(
            "https://rule34video.com/latest-updates/",
            R34vSitePlugin.resolveFeedUrl(FeedRequest(SiteId.R34V)),
        )
        assertEquals(
            "https://rule34video.com/most-popular/2/?sort_by=video_viewed",
            R34vSitePlugin.resolveFeedUrl(
                FeedRequest(SiteId.R34V, section = "popular", sort = FeedSort.VIEWS, page = 2),
            ),
        )
        assertEquals(
            "https://rule34video.com/for-you/",
            R34vSitePlugin.resolveFeedUrl(
                FeedRequest(SiteId.R34V, section = "foryou", page = 3),
            ),
        )
        assertEquals(
            "https://rule34video.com/search/cyber%20girl/2/?sort_by=post_date",
            R34vSitePlugin.resolveFeedUrl(
                FeedRequest(SiteId.R34V, query = "cyber girl", scope = SearchScope.ALL, sort = FeedSort.NEWEST, page = 2),
            ),
        )
        assertEquals(
            "https://rule34video.com/tags/3d_animation/",
            R34vSitePlugin.resolveFeedUrl(
                FeedRequest(SiteId.R34V, query = "3d_animation", scope = SearchScope.TAG),
            ),
        )
        assertEquals(
            "https://rule34video.com/models/studio-x/",
            R34vSitePlugin.resolveFeedUrl(
                FeedRequest(SiteId.R34V, query = "studio-x", scope = SearchScope.AUTHOR),
            ),
        )
        assertEquals(
            "https://rule34video.com/categories/blender/",
            R34vSitePlugin.resolveFeedUrl(
                FeedRequest(SiteId.R34V, category = "blender"),
            ),
        )
        assertEquals(
            "https://rule34video.com/channels/featured-chan/",
            R34vSitePlugin.resolveFeedUrl(
                FeedRequest(SiteId.R34V, section = "channel:featured-chan"),
            ),
        )
    }

    @Test
    fun `r34v parses feed cards, pagination, and applies local duration and rating filters`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        fakeHttp.onGet(
            "https://rule34video.com/latest-updates/",
            body = """
                <html><body>
                <h1>Latest Updates</h1>
                <div id="custom_list_videos_latest_videos_list_items" class="thumbs">
                    <div data-video-card-id="1001" class="item thumb">
                        <a class="th" href="/video/1001/alpha-clip/" data-preview="/previews/1001.mp4">
                            <img class="thumb" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" data-rd-src="/thumbs/1001.jpg" alt="Alpha Clip" />
                        </a>
                        <div class="thumb_title">Alpha Clip</div>
                        <span class="time">12:30</span>
                        <span class="video-views-count">15.2K</span>
                        <span class="video-card-meta__rating">92%</span>
                    </div>
                    <div data-video-card-id="1002" class="item thumb">
                        <a class="th" href="/video/1002/short-clip/">
                            <img class="thumb" src="/thumbs/1002.jpg" alt="Short Clip" />
                        </a>
                        <div class="thumb_title">Short Clip</div>
                        <span class="time">02:10</span>
                        <span class="video-views-count">800</span>
                        <span class="video-card-meta__rating">60%</span>
                    </div>
                </div>
                <nav class="fs-pager"><button class="page-of">Page 1 of 42</button></nav>
                </body></html>
            """.trimIndent(),
        )
        val plugin = R34vSitePlugin(fakeHttp, store, rateLimitBackoffMs = 0L)
        val unfiltered = plugin.feedSource.loadFeed(FeedRequest(SiteId.R34V))
        assertEquals(2, unfiltered.items.size)
        assertEquals("1001", unfiltered.items[0].id)
        assertEquals("https://rule34video.com/thumbs/1001.jpg", unfiltered.items[0].coverUrl)
        assertEquals("https://rule34video.com/previews/1001.mp4", unfiltered.items[0].previewUrl)
        assertEquals(750, unfiltered.items[0].durationSeconds)
        assertEquals(15200L, unfiltered.items[0].views)
        assertEquals(4.6f, unfiltered.items[0].rating!!, 0.01f)
        assertEquals(42, unfiltered.totalPages)
        assertEquals(2, unfiltered.nextPage)
        assertTrue(unfiltered.hasMore)

        val filtered = plugin.searchSource.search(
            FeedRequest(
                siteId = SiteId.R34V,
                advancedFilters = AdvancedSearchFilters(minDurationMinutes = 5, minRating = 4),
            ),
        )
        assertEquals(listOf("1001"), filtered.items.map { it.id })
    }

    @Test
    fun `r34v parses detail flashvars streams, downloads, groups, comments, related, and directories`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        fakeHttp.onGet(
            "https://rule34video.com/video/778899/sample/",
            body = """
                <html>
                <head><link rel="canonical" href="https://rule34video.com/video/778899/sample/" /></head>
                <body>
                    <h1>Sample Scene</h1>
                    <span class="item_info">08:45</span>
                    <div class="vp-desc">Line one\nLine two</div>
                    <script>
                        var flashvars = {
                            video_id: '778899',
                            video_title: 'Sample Scene',
                            preview_url: '/contents/videos_screenshots/778899/preview.jpg',
                            video_url: 'https://rule34video.com/get_file/1/778899_720p.mp4',
                            video_url_text: '720p',
                            video_alt_url: 'https://rule34video.com/get_file/1/778899_1080p.mp4',
                            video_alt_url_text: '1080p',
                            video_alt_url2: 'https://rule34video.com/get_file/1/778899_480p.mp4',
                            video_alt_url2_text: '480p'
                        };
                    </script>
                    <a href="/get_file/1/778899_1080p.mp4?download=true">Download 1080p</a>
                    <div class="vp-group__title">Characters</div>
                    <div class="vp-group__list"><a href="/characters/heroine-a/">Heroine A</a></div>
                    <div class="vp-group__title">Artists</div>
                    <div class="vp-group__list"><a href="/models/animator-z/">Animator Z</a></div>
                    <div class="vp-group__title">Categories</div>
                    <div class="vp-group__list"><a href="/categories/3d/">3D</a></div>
                    <div class="vp-group__title">Tags</div>
                    <div class="vp-group__list"><a href="/tags/high-fps/">High FPS</a></div>
                    <div id="tab_comments">
                        <div class="comment_item" data-comment-id="c9">
                            <a href="/members/42/">Viewer42</a>
                            <div class="comment_text">Great lighting!</div>
                            <span class="comment_date">2026-10-01</span>
                        </div>
                    </div>
                    <div id="custom_list_videos_related_videos_items">
                        <div data-video-card-id="778900" class="item thumb">
                            <a class="th" href="/video/778900/next/"><img src="/t/778900.jpg" alt="Next Video" /></a>
                            <div class="thumb_title">Next Video</div>
                        </div>
                    </div>
                </body></html>
            """.trimIndent(),
        )
        fakeHttp.onGet(
            "https://rule34video.com/channels/",
            body = """
                <html><body><div id="list_channels_channels_list_items">
                    <a href="/channels/studio-prime/"><img src="/c/prime.jpg" /><span class="thumb_title">Studio Prime</span></a>
                </div></body></html>
            """.trimIndent(),
        )

        val plugin = R34vSitePlugin(fakeHttp, store, rateLimitBackoffMs = 0L)
        val detail = plugin.videoSource.loadVideoDetail("https://rule34video.com/video/778899/sample/")
        assertEquals("778899", detail.item.id)
        assertEquals(525, detail.item.durationSeconds)
        assertEquals(listOf("1080p", "720p", "480p"), detail.streams.map { it.label })
        assertEquals(listOf("1080p"), detail.downloadStreams.map { it.label })
        assertEquals("https://rule34video.com/tags/heroine-a/", detail.characters.single().url)
        assertEquals("animator-z", detail.authors.single().id)
        assertEquals("3d", detail.categories.single().slug)
        assertEquals("high-fps", detail.tags.single().slug)
        assertEquals("Viewer42", detail.comments.single().author)
        assertEquals("778900", detail.related.single().id)

        val channels = plugin.directorySource.loadDirectory("channels")
        assertEquals(1, channels.size)
        assertEquals("studio-prime", channels[0].id)
        assertEquals("Studio Prime", channels[0].title)
    }

    @Test
    fun `r34v form auth persists PHPSESSID and logout isolates site`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        store.saveBearerToken(SiteId.IWARA, "iwara-jwt-keep")

        fakeHttp.onGet(
            "https://rule34video.com/login/",
            body = """<form><input name="remember_me_csrf_token" value="csrf_abc123" /></form>""",
            headers = mapOf("Set-Cookie" to listOf("kt_rt_popunder=1; Path=/")),
        )
        fakeHttp.onPost("https://rule34video.com/login/") { req ->
            val formBody = String(req.body ?: ByteArray(0), Charsets.UTF_8)
            assertTrue(formBody.contains("remember_me_csrf_token=csrf_abc123"))
            assertTrue(formBody.contains("username=alice"))
            assertTrue(formBody.contains("action=login"))
            SiteHttpResponse(
                code = 200,
                finalUrl = "https://rule34video.com/",
                headers = mapOf("Set-Cookie" to listOf("PHPSESSID=sess_xyz; Path=/; HttpOnly")),
                body = """<html><body><a href="/logout/">Logout</a></body></html>""".toByteArray(),
            )
        }

        val plugin = R34vSitePlugin(fakeHttp, store, rateLimitBackoffMs = 0L)
        assertEquals(AuthMode.FormCredentials, plugin.authProvider.mode)
        assertFalse(plugin.authProvider.currentAuthState().isAuthenticated)

        val state = plugin.authProvider.loginWithCredentials("alice", "secret99")
        assertTrue(state.isAuthenticated)
        assertEquals("alice", state.username)
        assertEquals("sess_xyz", store.getCookies(SiteId.R34V)["PHPSESSID"])

        plugin.authProvider.logout()
        assertFalse(plugin.authProvider.currentAuthState().isAuthenticated)
        assertEquals("iwara-jwt-keep", store.getBearerToken(SiteId.IWARA))
    }

    @Test
    fun `r34v link resolver handles video, search, tags, channels, and directories`() {
        val plugin = R34vSitePlugin(FakeHttpClientAdapter(), InMemorySiteSessionStore())
        val resolver = plugin.linkResolver
        assertTrue(resolver.canHandle("https://rule34video.com/video/12345/demo/"))
        val videoLink = resolver.resolve("https://www.rule34video.com/video/12345/demo/") as ResolvedLink.Video
        assertEquals(SiteId.R34V, videoLink.siteId)
        assertEquals("12345", videoLink.id)
        assertEquals("https://rule34video.com/video/12345/", videoLink.canonicalUrl)

        val tagLink = resolver.resolve("https://rule34video.com/tags/mecha/3/?sort_by=rating") as ResolvedLink.Feed
        assertEquals(SearchScope.TAG, tagLink.request.scope)
        assertEquals("mecha", tagLink.request.query)
        assertEquals(3, tagLink.request.page)
        assertEquals(FeedSort.RATING, tagLink.request.sort)

        val dirLink = resolver.resolve("https://rule34video.com/channels/") as ResolvedLink.Directory
        assertEquals("channels", dirLink.section)
    }

    // ==================== HANIME1 ====================

    @Test
    fun `hanime1 builds search URL with genre, brands, year, month, duration, and multi tags`() {
        val url = HanimeSitePlugin.resolveFeedUrl(
            FeedRequest(
                siteId = SiteId.HANIME1,
                query = "elf",
                scope = SearchScope.ALL,
                page = 2,
                advancedFilters = AdvancedSearchFilters(
                    includeTags = listOf("中文字幕", "無碼"),
                    category = "裏番",
                    brand = "Bunnywalker",
                    year = 2025,
                    month = 6,
                    minDurationMinutes = 15,
                ),
            ),
        )
        assertTrue(url.startsWith("https://hanime1.me/search?"))
        assertTrue(url.contains("query=elf"))
        assertTrue(url.contains("genre=%E8%A3%8F%E7%95%AA"))
        assertTrue(url.contains("brands%5B%5D=Bunnywalker"))
        assertTrue(url.contains("year=2025+%E5%B9%B4"))
        assertTrue(url.contains("month=6+%E6%9C%88"))
        assertTrue(url.contains("duration=15+%E5%88%86%E9%90%98+%2B"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("tags%5B%5D=%E4%B8%AD%E6%96%87%E5%AD%97%E5%B9%95"))
        assertTrue(url.contains("tags%5B%5D=%E7%84%A1%E7%A2%BC"))
    }

    @Test
    fun `hanime1 filters ad containers, applies exclude tags, and parses MP4 and HLS detail`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        fakeHttp.onMatching({ it.url.startsWith("https://hanime1.me/search?") }) { req ->
            SiteHttpResponse(
                code = 200,
                finalUrl = req.url,
                body = """
                    <html><body>
                    <div class="video-item-container">
                        <a class="ad-banner" href="https://ads.example.com/click">Ad</a>
                        <div class="title">Fake Ad</div>
                    </div>
                    <div class="video-item-container">
                        <a class="video-link" href="/watch?v=9001">Watch</a>
                        <img class="main-thumb" src="https://cdn.hanime1.me/9001.jpg" />
                        <div class="title">Good Episode 1</div>
                        <div class="duration">18:20</div>
                        <div class="stats-container">
                            <span class="stat-item">95%</span>
                            <span class="stat-item">2.4萬</span>
                        </div>
                    </div>
                    <div class="video-item-container">
                        <a class="video-link" href="/watch?v=9002">Watch</a>
                        <img class="main-thumb" src="https://cdn.hanime1.me/9002.jpg" />
                        <div class="title">NTR Episode 2</div>
                        <div class="duration">10:00</div>
                        <div class="stats-container">
                            <span class="stat-item">88%</span>
                            <span class="stat-item">1.1萬</span>
                        </div>
                    </div>
                    <ul class="pagination"><a href="/search?page=5">5</a></ul>
                    </body></html>
                """.trimIndent().toByteArray(),
            )
        }
        fakeHttp.onGet(
            "https://hanime1.me/watch?v=9001",
            body = """
                <html>
                <head><meta property="og:video:duration" content="1100" /></head>
                <body>
                    <h3 id="shareBtn-title">Good Episode 1</h3>
                    <video id="player" poster="https://cdn.hanime1.me/poster9001.jpg">
                        <source src="https://v.hanime1.me/9001-720p.mp4" size="720" type="video/mp4" />
                        <source src="https://v.hanime1.me/9001-1080p.m3u8" size="1080" type="application/x-mpegURL" />
                    </video>
                    <div class="video-details-wrapper">觀看次數：24,500次 2026-03-15</div>
                    <a id="video-artist-name" href="/search?query=StudioA">StudioA</a>
                    <div class="single-video-tag"><a href="/search?tags[]=裏番">裏番</a></div>
                    <div class="video-caption-text">Episode synopsis here</div>
                    <div id="comment-tabcontent">
                        <div class="comment-item" data-comment-id="c1">
                            <span class="comment-author">HaniFan</span>
                            <p class="comment-body">Smooth animation</p>
                            <time>2026-03-16</time>
                        </div>
                    </div>
                    <div id="related-tabcontent">
                        <div class="video-item-container">
                            <a class="video-link" href="/watch?v=9009">Watch</a>
                            <img class="main-thumb" src="https://cdn.hanime1.me/9009.jpg" />
                            <div class="title">Related OVA</div>
                        </div>
                    </div>
                </body></html>
            """.trimIndent(),
        )

        val plugin = HanimeSitePlugin(fakeHttp, store, rateLimitBackoffMs = 0L)
        val feed = plugin.searchSource.search(
            FeedRequest(
                siteId = SiteId.HANIME1,
                advancedFilters = AdvancedSearchFilters(excludeTags = listOf("ntr")),
            ),
        )
        assertEquals(listOf("9001"), feed.items.map { it.id })
        assertEquals(5, feed.totalPages)
        assertEquals(24_000L, feed.items[0].views)

        val detail = plugin.videoSource.loadVideoDetail("https://hanime1.me/watch?v=9001")
        assertEquals(listOf("1080p", "720p"), detail.streams.map { it.label })
        assertTrue(detail.streams[0].isHls)
        assertFalse(detail.streams[1].isHls)
        assertEquals("StudioA", detail.authors.single().name)
        assertEquals("HaniFan", detail.comments.single().author)
        assertEquals("9009", detail.related.single().id)
        assertEquals("2026-03-15", detail.item.dateLabel)
    }

    @Test
    fun `hanime1 login verifies 404 on second login GET and persists session`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        var loggedIn = false

        fakeHttp.onGetDynamic("https://hanime1.me/login") {
            if (!loggedIn) {
                SiteHttpResponse(
                    code = 200,
                    finalUrl = "https://hanime1.me/login",
                    headers = mapOf("Set-Cookie" to listOf("XSRF-TOKEN=xsrf1; Path=/")),
                    body = """<form><input name="_token" value="laravel_tok" /></form>""".toByteArray(),
                )
            } else {
                SiteHttpResponse(code = 404, finalUrl = "https://hanime1.me/login")
            }
        }
        fakeHttp.onPost("https://hanime1.me/login") { req ->
            val body = String(req.body ?: ByteArray(0), Charsets.UTF_8)
            assertTrue(body.contains("_token=laravel_tok"))
            assertTrue(body.contains("email=user%40example.com"))
            loggedIn = true
            SiteHttpResponse(
                code = 200,
                finalUrl = "https://hanime1.me/",
                headers = mapOf("Set-Cookie" to listOf("hanime1_session=h_sess_1; Path=/")),
                body = "<html>ok</html>".toByteArray(),
            )
        }

        val plugin = HanimeSitePlugin(fakeHttp, store, rateLimitBackoffMs = 0L)
        val auth = plugin.authProvider.loginWithCredentials("user@example.com", "pw123")
        assertTrue(auth.isAuthenticated)
        assertEquals("user@example.com", auth.username)
        assertEquals("h_sess_1", store.getCookies(SiteId.HANIME1)["hanime1_session"])

        val resolved = plugin.linkResolver.resolve("https://hanime1.me/watch?v=4242") as ResolvedLink.Video
        assertEquals("4242", resolved.id)
        assertNull(plugin.linkResolver.resolve("https://other.example/watch?v=1"))

        // Bad password where second GET /login returns 200 must throw AuthRequired
        loggedIn = false
        fakeHttp.onPost("https://hanime1.me/login") {
            SiteHttpResponse(code = 200, finalUrl = "https://hanime1.me/login", body = "<html>bad</html>".toByteArray())
        }
        try {
            plugin.authProvider.loginWithCredentials("user@example.com", "wrong")
            fail("Expected SiteException.AuthRequired")
        } catch (_: SiteException.AuthRequired) {
            // expected
        }
    }

    @Test
    fun `r34v and hanime1 wire real server-side search sort params and supportedSearchSorts`() {
        val r34v = R34vSitePlugin(FakeHttpClientAdapter(), InMemorySiteSessionStore())
        assertEquals(
            listOf(
                FeedSort.RELEVANCE,
                FeedSort.NEWEST,
                FeedSort.VIEWS,
                FeedSort.LIKES,
                FeedSort.RATING,
                FeedSort.DURATION,
                FeedSort.RANDOM,
            ),
            r34v.searchSource.supportedSearchSorts(SearchScope.ALL),
        )
        assertFalse(FeedSort.RELEVANCE in r34v.searchSource.supportedSearchSorts(SearchScope.TAG))
        assertEquals(
            "https://rule34video.com/search/elf/",
            R34vSitePlugin.resolveFeedUrl(FeedRequest(SiteId.R34V, query = "elf", sort = FeedSort.RELEVANCE)),
        )
        assertEquals(
            "https://rule34video.com/search/elf/?sort_by=most_favourited",
            R34vSitePlugin.resolveFeedUrl(FeedRequest(SiteId.R34V, query = "elf", sort = FeedSort.LIKES)),
        )
        assertEquals(
            "https://rule34video.com/search/elf/?sort_by=video_viewed",
            R34vSitePlugin.resolveFeedUrl(FeedRequest(SiteId.R34V, query = "elf", sort = FeedSort.VIEWS)),
        )
        assertEquals(
            "https://rule34video.com/search/elf/?sort_by=duration",
            R34vSitePlugin.resolveFeedUrl(FeedRequest(SiteId.R34V, query = "elf", sort = FeedSort.DURATION)),
        )
        val favLink = r34v.linkResolver.resolve("https://rule34video.com/search/elf/?sort_by=most_favourited") as ResolvedLink.Feed
        assertEquals(FeedSort.LIKES, favLink.request.sort)

        val hanime = HanimeSitePlugin(FakeHttpClientAdapter(), InMemorySiteSessionStore())
        assertEquals(
            listOf(
                FeedSort.RELEVANCE,
                FeedSort.NEWEST,
                FeedSort.VIEWS,
                FeedSort.LIKES,
                FeedSort.POPULAR,
                FeedSort.DURATION,
            ),
            hanime.searchSource.supportedSearchSorts(SearchScope.ALL),
        )
        val hViews = HanimeSitePlugin.resolveFeedUrl(FeedRequest(SiteId.HANIME1, query = "elf", sort = FeedSort.VIEWS))
        assertTrue(hViews.contains("query=elf"))
        assertTrue(hViews.contains("sort=%E8%A7%80%E7%9C%8B%E6%AC%A1%E6%95%B8"))
        val hLikes = HanimeSitePlugin.resolveFeedUrl(FeedRequest(SiteId.HANIME1, query = "elf", sort = FeedSort.LIKES))
        assertTrue(hLikes.contains("sort=%E8%AE%9A%E5%A5%BD%E6%AF%94%E4%BE%8B"))
        val hNewest = HanimeSitePlugin.resolveFeedUrl(FeedRequest(SiteId.HANIME1, query = "elf", sort = FeedSort.NEWEST))
        assertTrue(hNewest.contains("sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3"))
        val hPopular = HanimeSitePlugin.resolveFeedUrl(FeedRequest(SiteId.HANIME1, query = "elf", sort = FeedSort.POPULAR))
        assertTrue(hPopular.contains("sort=%E6%9C%AC%E6%97%A5%E6%8E%92%E8%A1%8C"))
        val hDuration = HanimeSitePlugin.resolveFeedUrl(FeedRequest(SiteId.HANIME1, query = "elf", sort = FeedSort.DURATION))
        assertTrue(hDuration.contains("sort=%E6%99%82%E9%95%B7%E6%9C%80%E9%95%B7"))
        val hRelevance = HanimeSitePlugin.resolveFeedUrl(FeedRequest(SiteId.HANIME1, query = "elf", sort = FeedSort.RELEVANCE))
        assertFalse(hRelevance.contains("sort="))

        val hLink = hanime.linkResolver.resolve(hViews) as ResolvedLink.Feed
        assertEquals(FeedSort.VIEWS, hLink.request.sort)
    }
}
