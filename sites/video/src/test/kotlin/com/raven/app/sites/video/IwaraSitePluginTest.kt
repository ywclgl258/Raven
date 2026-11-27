package com.raven.app.sites.video

import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IwaraSitePluginTest {

    @Test
    fun `iwara search uses plural videos and images and 0-based pagination`() {
        val videoSearch = IwaraSitePlugin.resolveFeedUrl(
            FeedRequest(SiteId.IWARA, query = "miku dance", scope = SearchScope.ALL, page = 1, mediaKind = MediaKind.VIDEO),
        )
        assertEquals("https://apiq.iwara.tv/search?page=0&limit=20&type=videos&query=miku+dance", videoSearch)

        val imageSearch = IwaraSitePlugin.resolveFeedUrl(
            FeedRequest(SiteId.IWARA, query = "render", scope = SearchScope.ALL, page = 3, mediaKind = MediaKind.PHOTO),
        )
        assertEquals("https://apiq.iwara.tv/search?page=2&limit=20&type=images&query=render", imageSearch)
    }

    @Test
    fun `iwara recalculates total pages dynamically and resolves username to UUID`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val userUuid = "11111111-2222-3333-4444-555555555555"

        fakeHttp.onGet(
            "https://apiq.iwara.tv/profile/alice_creator",
            body = """{"user":{"id":"$userUuid","username":"alice_creator","name":"Alice"}}""",
        )
        fakeHttp.onGet(
            "https://apiq.iwara.tv/videos?page=0&limit=20&user=$userUuid&sort=date",
            body = """
                {
                  "page": 0, "limit": 20, "count": 40,
                  "results": [
                    {
                      "id": "vidA", "title": "First MMD", "slug": "first-mmd",
                      "numViews": 1200, "numLikes": 85, "thumbnail": 2,
                      "file": {"id": "f1", "duration": 185},
                      "tags": [{"id": "mmd"}]
                    }
                  ]
                }
            """.trimIndent(),
        )
        fakeHttp.onGet(
            "https://apiq.iwara.tv/videos?page=1&limit=20&user=$userUuid&sort=date",
            body = """
                {
                  "page": 1, "limit": 20, "count": 65,
                  "results": [
                    {"id": "vidB", "title": "Second MMD", "numViews": 500, "numLikes": 12}
                  ]
                }
            """.trimIndent(),
        )

        val plugin = IwaraSitePlugin(fakeHttp, store, rateLimitBackoffMs = 0L)
        val p1 = plugin.feedSource.loadFeed(
            FeedRequest(SiteId.IWARA, query = "alice_creator", scope = SearchScope.AUTHOR, page = 1),
        )
        assertEquals(2, p1.totalPages)
        assertEquals(2, p1.nextPage)
        assertEquals("https://i.iwara.tv/image/thumbnail/f1/thumbnail-02.jpg", p1.items.single().coverUrl)

        val p2 = plugin.feedSource.loadFeed(
            FeedRequest(SiteId.IWARA, query = "alice_creator", scope = SearchScope.AUTHOR, page = 2),
        )
        // Dynamic count jumped from 40 -> 65, so totalPages MUST recalculate to 4!
        assertEquals(4, p2.totalPages)
        assertEquals(3, p2.nextPage)
        assertTrue(p2.hasMore)
    }

    @Test
    fun `iwara attaches Origin and x-site and isolates Bearer JWT strictly to apiHosts`() {
        val store = InMemorySiteSessionStore()
        store.saveBearerToken(SiteId.IWARA, "jwt_secret_token", "alice")
        val plugin = IwaraSitePlugin(FakeHttpClientAdapter(), store)

        val apiHeaders = plugin.authProvider.decorateRequestHeaders(
            SiteHttpRequest(url = "https://apiq.iwara.tv/subscriptions/videos", siteId = SiteId.IWARA),
            host = "apiq.iwara.tv",
        )
        assertEquals("https://www.iwara.tv", apiHeaders["Origin"])
        assertEquals("www.iwara.tv", apiHeaders["x-site"])
        assertEquals("Bearer jwt_secret_token", apiHeaders["Authorization"])

        val cdnHeaders = plugin.authProvider.decorateRequestHeaders(
            SiteHttpRequest(url = "https://files.iwara.tv/file/abc?expires=1700000000", siteId = SiteId.IWARA),
            host = "files.iwara.tv",
        )
        assertEquals("https://www.iwara.tv", cdnHeaders["Origin"])
        assertEquals("www.iwara.tv", cdnHeaders["x-site"])
        assertNull("Bearer token MUST NEVER be attached to files.iwara.tv", cdnHeaders["Authorization"])

        val imgHeaders = plugin.authProvider.decorateRequestHeaders(
            SiteHttpRequest(url = "https://i.iwara.tv/image/original/1/a.png", siteId = SiteId.IWARA),
            host = "i.iwara.tv",
        )
        assertNull("Bearer token MUST NEVER be attached to i.iwara.tv", imgHeaders["Authorization"])

        val webHeaders = plugin.authProvider.decorateRequestHeaders(
            SiteHttpRequest(url = "https://www.iwara.tv/", siteId = SiteId.IWARA),
            host = "www.iwara.tv",
        )
        assertNull("Bearer token MUST NEVER be attached to www.iwara.tv", webHeaders["Authorization"])
    }

    @Test
    fun `iwara rejects a stale authEpoch at the decorator and surfaces it as Cancelled`() = runTest {
        val store = InMemorySiteSessionStore()
        store.saveBearerToken(SiteId.IWARA, "jwt_v1", "alice")
        val plugin = IwaraSitePlugin(FakeHttpClientAdapter(), store)
        val epoch = store.authEpoch(SiteId.IWARA)

        // A request tagged with the current epoch is decorated normally.
        val ok = plugin.authProvider.decorateRequestHeaders(
            SiteHttpRequest(
                url = "https://apiq.iwara.tv/subscriptions/videos",
                siteId = SiteId.IWARA,
                authEpoch = epoch,
            ),
            host = "apiq.iwara.tv",
        )
        assertEquals("Bearer jwt_v1", ok["Authorization"])

        // The account switches mid-flight: a tag from the old epoch must be refused.
        store.clearSite(SiteId.IWARA)
        store.saveBearerToken(SiteId.IWARA, "jwt_v2", "bob")
        assertTrue(store.authEpoch(SiteId.IWARA) > epoch)
        var thrown: Throwable? = null
        try {
            plugin.authProvider.decorateRequestHeaders(
                SiteHttpRequest(
                    url = "https://apiq.iwara.tv/subscriptions/videos",
                    siteId = SiteId.IWARA,
                    authEpoch = epoch,
                ),
                host = "apiq.iwara.tv",
            )
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("stale epoch must be Cancelled, got $thrown", thrown is SiteException.Cancelled)

        // Untagged requests (public media/API reads) are never cancelled by an account switch.
        val untagged = plugin.authProvider.decorateRequestHeaders(
            SiteHttpRequest(url = "https://i.iwara.tv/image/1/a.png", siteId = SiteId.IWARA),
            host = "i.iwara.tv",
        )
        assertNull(untagged["Authorization"])
    }

    @Test
    fun `iwara X-Version SHA-1 and dynamic salt hot-extraction fallback on missing Source`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val fileId = "file-uuid-999"
        val expires = "1790000000"
        val fileUrl = "https://files.iwara.tv/file/$fileId?expires=$expires&hash=deadbeef"
        val defaultSig = IwaraSitePlugin.sha1Hex("${fileId}_${expires}_${IwaraSitePlugin.DEFAULT_SALT}")
        val newSalt = "NewRotatedSalt1234567890"
        val newSig = IwaraSitePlugin.sha1Hex("${fileId}_${expires}_$newSalt")

        fakeHttp.onGet(
            "https://apiq.iwara.tv/video/vid123",
            body = """
                {
                  "id": "vid123", "title": "Signed Video", "body": "Desc",
                  "fileUrl": "$fileUrl",
                  "file": {"id": "$fileId", "duration": 90, "height": 1080},
                  "user": {"id": "u1", "username": "maker", "name": "Maker"},
                  "tags": [{"id": "original"}]
                }
            """.trimIndent(),
        )
        fakeHttp.onGet("https://apiq.iwara.tv/video/vid123/related", body = """{"results":[]}""")
        fakeHttp.onGet(
            "https://apiq.iwara.tv/video/vid123/comments?limit=12&page=0",
            body = """{"results":[{"id":"cm1","body":"Nice!","createdAt":"2026-05-01T12:00:00Z","user":{"name":"Fan"}}]}""",
        )
        fakeHttp.onGet(
            "https://www.iwara.tv",
            body = """<html><head><script src="/main.a1b2c3d4.js"></script></head></html>""",
        )
        fakeHttp.onGet(
            "https://www.iwara.tv/main.a1b2c3d4.js",
            body = """function sign(e){return sha1(e.id+"_"+e.expires+"_$newSalt");}""",
        )
        fakeHttp.onGetDynamic(fileUrl) { req ->
            assertNull("Never send Bearer to files.iwara.tv", req.headers["Authorization"])
            when (req.headers["X-Version"]) {
                defaultSig -> SiteHttpResponse(
                    code = 200,
                    finalUrl = fileUrl,
                    body = """
                        [
                          {"name":"preview","src":{"view":"//cdn.iwara.tv/prev.mp4"}},
                          {"name":"540","src":{"view":"//cdn.iwara.tv/540.mp4","download":"//cdn.iwara.tv/540-dl.mp4"}}
                        ]
                    """.trimIndent().toByteArray(),
                )
                newSig -> SiteHttpResponse(
                    code = 200,
                    finalUrl = fileUrl,
                    body = """
                        [
                          {"name":"preview","src":{"view":"//cdn.iwara.tv/prev.mp4"}},
                          {"name":"540","src":{"view":"//cdn.iwara.tv/540.mp4","download":"//cdn.iwara.tv/540-dl.mp4"}},
                          {"name":"Source","src":{"view":"//cdn.iwara.tv/src.mp4","download":"//cdn.iwara.tv/src-dl.mp4"}}
                        ]
                    """.trimIndent().toByteArray(),
                )
                else -> SiteHttpResponse(code = 403, finalUrl = fileUrl)
            }
        }

        val plugin = IwaraSitePlugin(fakeHttp, store, clockMs = { 10_000_000L }, rateLimitBackoffMs = 0L)
        val detail = plugin.videoSource.loadVideoDetail("https://www.iwara.tv/video/vid123/signed-video")

        assertEquals(listOf("1080p 原画", "540p"), detail.streams.map { it.label })
        assertEquals("https://cdn.iwara.tv/src.mp4", detail.streams[0].url)
        assertEquals("https://cdn.iwara.tv/src-dl.mp4", detail.downloadStreams[0].url)
        assertEquals("Fan", detail.comments.single().author)
    }

    @Test
    fun `iwara auth login, subscriptions authEpoch cancellation, and dual-media gallery source`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()

        fakeHttp.onPost("https://apiq.iwara.tv/auth/login") { req ->
            assertFalse(req.followRedirects)
            assertFalse(req.allowRetry)
            SiteHttpResponse(
                code = 200,
                finalUrl = req.url,
                body = """{"token":"jwt_fresh_123"}""".toByteArray(),
            )
        }
        val plugin = IwaraSitePlugin(fakeHttp, store, rateLimitBackoffMs = 0L)
        assertTrue(plugin.descriptor.hasCapability(SiteCapability.DUAL_MEDIA))
        assertNotNull(plugin.gallerySource)

        // Subscription without auth fails with AuthRequired
        try {
            plugin.feedSource.loadFeed(FeedRequest(SiteId.IWARA, section = "subscriptions"))
            fail("Expected SiteException.AuthRequired")
        } catch (_: SiteException.AuthRequired) {
            // expected
        }

        val auth = plugin.authProvider.loginWithCredentials("bob", "pw")
        assertTrue(auth.isAuthenticated)
        assertEquals("jwt_fresh_123", store.getBearerToken(SiteId.IWARA))

        // Simulate logout happening mid-flight during subscription request -> Cancelled via authEpoch
        fakeHttp.onGetDynamic("https://apiq.iwara.tv/subscriptions/videos?page=0&limit=20") { req ->
            assertFalse(req.allowCache)
            assertEquals("Bearer jwt_fresh_123", req.headers["Authorization"])
            plugin.authProvider.logout()
            SiteHttpResponse(code = 200, finalUrl = req.url, body = """{"page":0,"limit":20,"count":0,"results":[]}""".toByteArray())
        }
        try {
            plugin.feedSource.loadFeed(FeedRequest(SiteId.IWARA, section = "subscriptions"))
            fail("Expected SiteException.Cancelled on authEpoch mismatch")
        } catch (_: SiteException.Cancelled) {
            // expected
        }

        // Dual-media GallerySource: filters out video attachments and caches detail
        var imageApiHits = 0
        fakeHttp.onGetDynamic("https://apiq.iwara.tv/image/img777") { req ->
            imageApiHits++
            SiteHttpResponse(
                code = 200,
                finalUrl = req.url,
                body = """
                    {
                      "id": "img777", "title": "Art Set", "slug": "art-set", "body": "Gallery body",
                      "numImages": 3, "numViews": 300, "numLikes": 50,
                      "thumbnail": {"id": "t1", "name": "cover.png", "width": 800, "height": 1200},
                      "files": [
                        {"id": "p1", "name": "01.png", "type": "image", "mime": "image/png", "width": 1600, "height": 2400},
                        {"id": "v1", "name": "clip.mp4", "type": "video", "mime": "video/mp4"},
                        {"id": "p2", "name": "02.jpg", "type": "image", "mime": "image/jpeg", "width": 1200, "height": 1800}
                      ]
                    }
                """.trimIndent().toByteArray(),
            )
        }

        val gallery = plugin.gallerySource.loadGalleryDetail("https://www.iwara.tv/image/img777/art-set")
        assertEquals(2, gallery.pageCount)
        assertEquals(MediaKind.PHOTO, gallery.item.kind)
        assertTrue(gallery.description.contains("另有 1 个视频文件"))
        assertEquals("https://i.iwara.tv/image/original/p1/01.png", gallery.pages[0].imageUrl)
        assertEquals("https://www.iwara.tv/image/img777/art-set#page=2", gallery.pages[1].pageUrl)

        // Cached page lookup should NOT hit API again
        val p2 = plugin.gallerySource.resolvePageImage("https://www.iwara.tv/image/img777/art-set#page=2")
        assertEquals("https://i.iwara.tv/image/original/p2/02.jpg", p2.imageUrl)
        assertEquals(1, imageApiHits)

        // `?reload=1` forces cache refresh
        plugin.gallerySource.resolvePageImage("https://www.iwara.tv/image/img777/art-set?reload=1#page=2")
        assertEquals(2, imageApiHits)

        // LinkResolver handles API URLs (0-based -> 1-based page) and rejects insecure HTTP API URLs
        val apiResolved = plugin.linkResolver.resolve(
            "https://apiq.iwara.tv/images?page=2&sort=likes&tags=render,4k",
        ) as ResolvedLink.Feed
        assertEquals(MediaKind.PHOTO, apiResolved.request.mediaKind)
        assertEquals(3, apiResolved.request.page)
        assertEquals(FeedSort.LIKES, apiResolved.request.sort)
        assertEquals("render", apiResolved.request.query)
        assertEquals(listOf("4k"), apiResolved.request.advancedFilters.includeTags)

        assertNull(plugin.linkResolver.resolve("http://apiq.iwara.tv/videos?page=0"))
    }

    @Test
    fun `iwara search wires native sort params and advertises supportedSearchSorts`() {
        val plugin = IwaraSitePlugin(FakeHttpClientAdapter(), InMemorySiteSessionStore())
        assertEquals(
            listOf(
                FeedSort.RELEVANCE,
                FeedSort.NEWEST,
                FeedSort.TRENDING,
                FeedSort.POPULAR,
                FeedSort.VIEWS,
                FeedSort.LIKES,
            ),
            plugin.searchSource.supportedSearchSorts(SearchScope.ALL),
        )
        assertEquals(
            "https://apiq.iwara.tv/search?page=0&limit=20&type=videos&query=miku",
            IwaraSitePlugin.resolveFeedUrl(FeedRequest(SiteId.IWARA, query = "miku", sort = FeedSort.RELEVANCE)),
        )
        assertEquals(
            "https://apiq.iwara.tv/search?page=0&limit=20&type=videos&query=miku&sort=date",
            IwaraSitePlugin.resolveFeedUrl(FeedRequest(SiteId.IWARA, query = "miku", sort = FeedSort.NEWEST)),
        )
        assertEquals(
            "https://apiq.iwara.tv/search?page=0&limit=20&type=videos&query=miku&sort=views",
            IwaraSitePlugin.resolveFeedUrl(FeedRequest(SiteId.IWARA, query = "miku", sort = FeedSort.VIEWS)),
        )
        assertEquals(
            "https://apiq.iwara.tv/search?page=0&limit=20&type=videos&query=miku&sort=likes",
            IwaraSitePlugin.resolveFeedUrl(FeedRequest(SiteId.IWARA, query = "miku", sort = FeedSort.LIKES)),
        )
        assertEquals(
            "https://apiq.iwara.tv/search?page=0&limit=20&type=videos&query=miku&sort=trending",
            IwaraSitePlugin.resolveFeedUrl(FeedRequest(SiteId.IWARA, query = "miku", sort = FeedSort.TRENDING)),
        )
        assertEquals(
            "https://apiq.iwara.tv/search?page=0&limit=20&type=videos&query=miku&sort=popularity",
            IwaraSitePlugin.resolveFeedUrl(FeedRequest(SiteId.IWARA, query = "miku", sort = FeedSort.POPULAR)),
        )
    }

    @Test
    fun `iwara bypasses cache on signed source lookup and surfaces ParseError on malformed root`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        val allowCacheFlags = mutableListOf<Boolean>()
        fakeHttp.onGet(
            "https://apiq.iwara.tv/video/vid111",
            body = """{"id":"vid111","title":"Demo","fileUrl":"https://filesq.iwara.tv/file/f1?expires=9999999999"}""",
        )
        fakeHttp.onGet("https://apiq.iwara.tv/video/vid111/related", body = """{"results":[]}""")
        fakeHttp.onGet("https://apiq.iwara.tv/video/vid111/comments?limit=12&page=0", body = """{"results":[]}""")
        fakeHttp.onGetDynamic("https://filesq.iwara.tv/file/f1?expires=9999999999") { req ->
            allowCacheFlags += req.allowCache
            SiteHttpResponse(
                code = 200,
                finalUrl = req.url,
                body = """[{"name":"720","src":{"view":"//cdn.iwara.tv/720.mp4","download":"//cdn.iwara.tv/720_dl.mp4"}}]""".toByteArray(),
            )
        }
        val plugin = IwaraSitePlugin(fakeHttp, store, rateLimitBackoffMs = 0L)
        val detail = plugin.videoSource.loadVideoDetail("https://www.iwara.tv/video/vid111/demo")
        assertEquals(listOf("720p"), detail.streams.map { it.label })
        assertTrue(allowCacheFlags.isNotEmpty() && allowCacheFlags.all { !it })

        fakeHttp.onGet("https://apiq.iwara.tv/video/badjson", body = """["unexpected","array"]""")
        try {
            plugin.videoSource.loadVideoDetail("https://www.iwara.tv/video/badjson/demo")
            fail("Expected SiteException.ParseError")
        } catch (_: SiteException.ParseError) {
            // expected
        }
    }

    @Test
    fun `iwara sets HLS mime hint, parses uppercase height, and maps embedUrl and private videos`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val store = InMemorySiteSessionStore()
        fakeHttp.onGet(
            "https://apiq.iwara.tv/video/vidhls",
            body = """{"id":"vidhls","title":"HLS Upper","fileUrl":"https://filesq.iwara.tv/file/f2?expires=9999999999"}""",
        )
        fakeHttp.onGet("https://apiq.iwara.tv/video/vidhls/related", body = """{"results":[]}""")
        fakeHttp.onGet("https://apiq.iwara.tv/video/vidhls/comments?limit=12&page=0", body = """{"results":[]}""")
        fakeHttp.onGetDynamic("https://filesq.iwara.tv/file/f2?expires=9999999999") { req ->
            SiteHttpResponse(
                code = 200,
                finalUrl = req.url,
                body = """[{"name":"720P","src":{"view":"https://cdn.iwara.tv/hls/720.m3u8?token=abc","download":"https://cdn.iwara.tv/dl/720.mp4"}}]""".toByteArray(),
            )
        }
        val plugin = IwaraSitePlugin(fakeHttp, store, rateLimitBackoffMs = 0L)
        val detail = plugin.videoSource.loadVideoDetail("https://www.iwara.tv/video/vidhls")
        assertTrue(detail.streams.single().isHls)
        assertEquals("application/vnd.apple.mpegurl", detail.streams.single().mimeTypeHint)
        assertEquals("720p", detail.streams.single().label)
        assertEquals("https://cdn.iwara.tv/dl/720.mp4", detail.downloadStreams.single().url)

        fakeHttp.onGet("https://apiq.iwara.tv/video/ext1", body = """{"id":"ext1","embedUrl":"https://youtu.be/xyz"}""")
        try {
            plugin.videoSource.loadVideoDetail("https://www.iwara.tv/video/ext1")
            fail("Expected SiteException.Unsupported")
        } catch (_: SiteException.Unsupported) {
            // expected
        }

        fakeHttp.onGet("https://apiq.iwara.tv/video/priv1", body = """{"id":"priv1","private":true}""")
        try {
            plugin.videoSource.loadVideoDetail("https://www.iwara.tv/video/priv1")
            fail("Expected SiteException.AuthRequired")
        } catch (_: SiteException.AuthRequired) {
            // expected
        }
    }
}
