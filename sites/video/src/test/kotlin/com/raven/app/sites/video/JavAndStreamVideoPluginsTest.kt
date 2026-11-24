package com.raven.app.sites.video

import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.MirrorLeaseRouter
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.RouPngProtocol
import com.raven.app.core.sitespi.SiteHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64

class JavAndStreamVideoPluginsTest {

    // ==================== MISSAV ====================

    @Test
    fun `missav fails over from ws to media mirror via MirrorLeaseRouter and unpacks Base62Packer HLS`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val sampleFeedHtml = """
            <html><body>
            <div class="thumbnail group">
                <a href="/cn/dldss-584?ref=home">
                    <img src="data:image/png;base64,iVBOR" data-src="https://cdn.mrstcdn.store/dldss-584.jpg" alt="DLDSS-584 Title" />
                    <video data-src="https://cdn.mrstcdn.store/dldss-584-prev.mp4"></video>
                </a>
                <span class="absolute">01:58:10</span>
                <div class="truncate"><a href="/cn/dldss-584">DLDSS-584 Full Title</a></div>
            </div>
            <a href="/new?page=12">12</a>
            </body></html>
        """.trimIndent()

        // Primary missav.ws returns Cloudflare challenge -> must switch to missav.media
        fakeHttp.onGet(
            "https://missav.ws/new",
            code = 403,
            headers = mapOf("cf-mitigated" to listOf("challenge")),
            body = "<html><title>Just a moment...</title></html>",
        )
        fakeHttp.onGet("https://missav.media/new", body = sampleFeedHtml)

        // Base62 packed HLS playlist script
        val packedScript = """
            eval(function(p,a,c,k,e,d){return p}('0 1="2://3.4/5-6-7-8-9/a.b";',12,12,'var|source|https|surrit|com|12345678|1234|1234|1234|1234567890ab|playlist|m3u8'.split('|'),0,{}))
        """.trimIndent()
        val detailHtml = """
            <html>
            <head>
                <meta property="og:image" content="https://cdn.mrstcdn.store/dldss-584.jpg" />
                <meta property="og:video:duration" content="7090" />
            </head>
            <body>
                <h1>DLDSS-584 Full Title</h1>
                <a href="/actresses/alice-star">Alice Star</a>
                <a href="/genres/hd">HD</a>
                <script>$packedScript</script>
            </body></html>
        """.trimIndent()
        fakeHttp.onGet("https://missav.media/dldss-584", body = detailHtml)

        val plugin = MissavSitePlugin(fakeHttp, rateLimitBackoffMs = 0L)
        assertTrue(plugin.hostRouter is MirrorLeaseRouter)

        val feed = plugin.feedSource.loadFeed(FeedRequest(SiteId.MISSAV, section = "new"))
        assertEquals(1, feed.items.size)
        assertEquals("dldss-584", feed.items[0].id)
        assertEquals("https://missav.ws/dldss-584", feed.items[0].url)
        assertEquals(7090, feed.items[0].durationSeconds)
        assertEquals(12, feed.totalPages)

        // Subsequent detail request should reuse leased missav.media directly without hitting missav.ws!
        val beforeCount = fakeHttp.requests.size
        val detail = plugin.videoSource.loadVideoDetail("https://missav.media/cn/dldss-584")
        assertEquals(beforeCount + 1, fakeHttp.requests.size)
        assertEquals("https://missav.media/dldss-584", fakeHttp.requests.last().url)
        assertEquals(
            "https://surrit.com/12345678-1234-1234-1234-1234567890ab/playlist.m3u8",
            detail.streams.single().url,
        )
        assertTrue(detail.streams.single().isHls)
        assertEquals("alice-star", detail.authors.single().id)
        assertEquals("hd", detail.tags.single().slug)

        val link = plugin.linkResolver.resolve("https://missav.media/ja/abp-123?foo=bar") as ResolvedLink.Video
        assertEquals("abp-123", link.id)
        assertEquals("https://missav.ws/abp-123", link.canonicalUrl)
    }

    // ==================== JABLE ====================

    @Test
    fun `jable parses MacCMS feed, player_aaaa brace object HLS, bracket title, and jable tv mirror link`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        assertEquals(
            "https://jable.vip/vodsearch/midv-hits-----2---/",
            JableSitePlugin.resolveFeedUrl(
                FeedRequest(SiteId.JABLE, query = "midv", sort = FeedSort.POPULAR, page = 2),
            ),
        )
        fakeHttp.onGet(
            "https://jable.vip/label/new/",
            body = """
                <html><body>
                <div class="video-img-box">
                    <img class="lazyload" src="/template/default/img/loading.gif" data-src="https://img.senlin2026.com/555.jpg" />
                    <h6 class="title"><a href="/vodplay/555-1-1/">MIDV-555 Demo</a></h6>
                    <p class="sub-title"><span>12.5K</span><span>430</span></p>
                </div>
                <ul class="pagination"><a href="/label/new/page/8/">8</a></ul>
                </body></html>
            """.trimIndent(),
        )
        fakeHttp.onGet(
            "https://jable.vip/vodplay/555-1-1/",
            body = """
                <html>
                <head>
                    <title>中文字幕《MIDV-555 完整片名》在线播放 - 高清 | Jable</title>
                    <meta property="og:image" content="https://img.senlin2026.com/555.jpg" />
                </head>
                <body>
                    <a href="/vodtype/28/">中文字幕</a>
                    <a href="/vodsearch/OL-time----------/">OL</a>
                    <script>
                        var player_aaaa={"flag":"play","encrypt":0,"vod_data":{"vod_name":"x","nested":{"a":1}},"url":"https:\/\/cdn.senlin2026.com\/hls\/555\/index.m3u8"}
                    </script>
                </body></html>
            """.trimIndent(),
        )

        val plugin = JableSitePlugin(fakeHttp, rateLimitBackoffMs = 0L)
        val feed = plugin.feedSource.loadFeed(FeedRequest(SiteId.JABLE, section = "new"))
        assertEquals("555", feed.items.single().id)
        assertEquals("https://img.senlin2026.com/555.jpg", feed.items.single().coverUrl)
        assertEquals(8, feed.totalPages)

        val detail = plugin.videoSource.loadVideoDetail("https://jable.tv/vodplay/555-1-1/")
        assertEquals("MIDV-555 完整片名", detail.item.title)
        assertEquals("https://cdn.senlin2026.com/hls/555/index.m3u8", detail.streams.single().url)
        assertTrue(detail.streams.single().isHls)
        assertEquals("28", detail.categories.single().slug)

        val tvLink = plugin.linkResolver.resolve("https://jable.tv/videos/midv-555/") as ResolvedLink.Feed
        assertEquals("midv-555", tvLink.request.query)
        val vipVideo = plugin.linkResolver.resolve("https://jable.vip/vodplay/555-1-1/") as ResolvedLink.Video
        assertEquals("555", vipVideo.id)
    }

    // ==================== ROUVIDEO ====================

    @Test
    fun `rouvideo decodes TanStack ev payload and exposes RouPngProtocol transportHook`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val videoId = "abcdefghijklmnop1234"
        val jsonPayload = """{"videoUrl":"/api/hls/$videoId","thumbVTTUrl":"/api/vtt/$videoId.vtt"}"""
        val keyOffset = 7
        val shiftedBytes = ByteArray(jsonPayload.length) { i ->
            ((jsonPayload[i].code + keyOffset) and 0xFF).toByte()
        }
        val encodedBase64 = Base64.encode(shiftedBytes)

        fakeHttp.onGet(
            "https://rou.video/home",
            body = """
                <html><body>
                <a href="/v/$videoId">
                    <img src="https://img.rou.video/1.jpg" alt="Hero Dup" />
                    <h3>Short Drama Ep 1</h3>
                    <span>1小時02分05秒</span>
                    <span>3.2萬 次觀看</span>
                </a>
                <a href="/v/$videoId"><h3>Hero Dup</h3></a>
                <a href="/home?page=4">4</a>
                </body></html>
            """.trimIndent(),
        )
        fakeHttp.onGet(
            "https://rou.video/v/$videoId",
            body = """
                <html>
                <head>
                    <meta property="og:image" content="https://img.rou.video/1.jpg" />
                    <meta property="og:description" content="Short drama synopsis" />
                </head>
                <body>
                    <h1>Short Drama Ep 1</h1>
                    <a href="/v?tag=%E5%8E%9F%E5%88%9B">原创</a>
                    <a href="/s/series1234567890abcd">Drama Series</a>
                    <script>window.__TS__={ev:${'$'}R[12]={d:"$encodedBase64",k:$keyOffset}};</script>
                </body></html>
            """.trimIndent(),
        )

        val plugin = RouvideoSitePlugin(fakeHttp, rateLimitBackoffMs = 0L)
        assertTrue(plugin.mediaTransportHook === RouPngProtocol.transportHook)
        assertTrue(plugin.mediaTransportHook.isHlsUrl("https://rou.video/api/hls/$videoId"))
        assertTrue(plugin.mediaTransportHook.requiresFullResponseUnwrap("https://rou.video/api/hls/$videoId"))

        // Round-trip verify PNG roUd unwrapping via exposed mediaTransportHook
        val playlistBytes = "#EXTM3U\n#EXT-X-VERSION:3\nseg0.ts\n".toByteArray()
        val wrappedPng = RouPngProtocol.wrap(playlistBytes, deflate = true)
        val unwrapped = plugin.mediaTransportHook.unwrapMediaBytes("https://rou.video/api/hls/$videoId", wrappedPng)
        assertNotNull(unwrapped)
        assertEquals("application/vnd.apple.mpegurl", unwrapped!!.contentType)
        assertEquals(String(playlistBytes), String(unwrapped.bytes))

        val feed = plugin.feedSource.loadFeed(FeedRequest(SiteId.ROUVIDEO, section = "home"))
        assertEquals(1, feed.items.size)
        assertEquals(3725, feed.items[0].durationSeconds)
        assertEquals(32_000L, feed.items[0].views)
        assertEquals(4, feed.totalPages)

        val detail = plugin.videoSource.loadVideoDetail("https://rou.video/v/$videoId")
        assertEquals("https://rou.video/api/hls/$videoId", detail.streams.single().url)
        assertTrue(detail.streams.single().isHls)
        assertEquals("https://rou.video/api/vtt/$videoId.vtt", detail.thumbnailVttUrl)
        assertEquals("series1234567890abcd", detail.tags.single().slug)
        assertEquals("原创", detail.categories.single().slug)

        val link = plugin.linkResolver.resolve("https://rou.video/v/$videoId") as ResolvedLink.Video
        assertEquals(videoId, link.id)
    }

    // ==================== KBJFREE ====================

    @Test
    fun `kbjfree parses Next RSC flight payload and marks streams preview-only`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val rscInner = """5:["${'$'}","${'$'}L1",null,{"videos":[{"id":"$uuid","name":"BJ Live Clip","categoryId":"cat1","mainThumbUrl":"https://cdn.marshlecdn.com/t1.jpg","previewMediaUrl":"https://cdn.marshlecdn.com/p1.mp4","duration":"01:31:00","extra":${'$'}undefined}]}]"""
        val escapedRsc = rscInner.replace("\\", "\\\\").replace("\"", "\\\"")
        val feedHtml = """
            <html><body>
            <script>self.__next_f.push([1,"$escapedRsc"])</script>
            <a href="/videos?sort=recent&page=3">3</a>
            </body></html>
        """.trimIndent()

        val detailHtml = """
            <html>
            <head>
                <script type="application/ld+json">
                {
                  "@type": "VideoObject",
                  "name": "BJ Live Clip",
                  "contentUrl": "https://cdn.marshlecdn.com/preview/$uuid.mp4",
                  "thumbnailUrl": ["https://cdn.marshlecdn.com/t1.jpg"],
                  "duration": "PT1H31M0S",
                  "actor": [{"name": "StreamerA", "url": "https://kbjfree.com/model/StreamerA"}]
                }
                </script>
            </head>
            <body><h1>BJ Live Clip</h1></body></html>
        """.trimIndent()

        fakeHttp.onGet("https://kbjfree.com/videos?sort=recent", body = feedHtml)
        fakeHttp.onGet("https://kbjfree.com/watch/$uuid", body = detailHtml)

        val plugin = KbjfreeSitePlugin(fakeHttp, rateLimitBackoffMs = 0L)
        assertTrue(plugin.descriptor.hasCapability(SiteCapability.PREVIEW_ONLY))

        val feed = plugin.feedSource.loadFeed(FeedRequest(SiteId.KBJFREE, section = "recent"))
        assertEquals(1, feed.items.size)
        assertEquals(uuid, feed.items[0].id)
        assertEquals(5460, feed.items[0].durationSeconds)
        assertEquals(3, feed.totalPages)

        val detail = plugin.videoSource.loadVideoDetail("https://kbjfree.com/watch/$uuid")
        assertTrue(detail.isPreviewOnly)
        assertTrue(detail.streams.single().isPreviewOnly)
        assertFalse(detail.streams.single().isHls)
        assertEquals("预览", detail.streams.single().label)
        assertEquals(5460, detail.item.durationSeconds)
        assertEquals("StreamerA", detail.authors.single().name)

        val link = plugin.linkResolver.resolve("https://kbjfree.com/watch/$uuid") as ResolvedLink.Video
        assertEquals(uuid, link.id)
    }

    // ==================== PORNHUB ====================

    @Test
    fun `pornhub parses both desktop and mobile DOMs and extracts mediaDefinitions HLS and MP4`() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val desktopHtml = """
            <html><body>
            <ul>
                <li class="pcVideoListItem" data-video-vkey="ph654321abcd">
                    <a href="/view_video.php?viewkey=ph654321abcd" title="Desktop Clip">
                        <img data-image="https://ci.phncdn.com/desktop.jpg" data-mediabook="https://ew.phncdn.com/prev.webm" src="data:image/gif;base64,R0l" />
                    </a>
                    <var class="duration">14:22</var>
                    <span class="views"><var>1.5M</var></span>
                </li>
            </ul>
            <a href="/video?o=cm&page=9">9</a>
            </body></html>
        """.trimIndent()

        val mobileHtml = """
            <html><body>
            <ul>
                <li data-video-vkey="ph987654wxyz">
                    <div class="title"><a href="/view_video.php?viewkey=ph987654wxyz">Mobile Clip</a></div>
                    <img data-poster="https://ci.phncdn.com/mobile.jpg" src="data:image/gif;base64,R0l" />
                    <div class="duration"><span class="time">07:05</span></div>
                    <div class="views">42K views</div>
                    <span data-webm="https://ew.phncdn.com/mprev.webm"></span>
                </li>
            </ul>
            </body></html>
        """.trimIndent()

        val detailHtml = """
            <html>
            <head>
                <meta property="og:image" content="https://ci.phncdn.com/desktop.jpg" />
                <meta property="og:description" content="Sample description" />
            </head>
            <body>
                <h1 class="title"><span>Desktop Clip</span></h1>
                <a href="/pornstar/jane-doe">Jane Doe</a>
                <a href="/categories/animation">Animation</a>
                <a href="/tags/hd-video">HD Video</a>
                <script>
                    var flashvars_123 = {
                        "mediaDefinitions": [
                            {"defaultQuality": false, "format": "hls", "height": 480, "videoUrl": "https:\/\/ev-h.phncdn.com\/480p.m3u8"},
                            {"defaultQuality": true, "height": 1080, "format": "hls", "videoUrl": "https:\/\/ev-h.phncdn.com\/1080p.m3u8"},
                            {"defaultQuality": false, "height": 720, "format": "mp4", "videoUrl": "https:\/\/www.pornhub.com\/video\/get_media?s=mp4"}
                        ]
                    };
                </script>
            </body></html>
        """.trimIndent()

        fakeHttp.onGet("https://www.pornhub.com/video?o=cm", body = desktopHtml)
        fakeHttp.onGet("https://www.pornhub.com/video?o=mv", body = mobileHtml)
        fakeHttp.onGet("https://www.pornhub.com/view_video.php?viewkey=ph654321abcd", body = detailHtml)

        val plugin = PornhubSitePlugin(fakeHttp, rateLimitBackoffMs = 0L)

        val desktopFeed = plugin.feedSource.loadFeed(FeedRequest(SiteId.PORNHUB, section = "newest"))
        assertEquals("ph654321abcd", desktopFeed.items.single().id)
        assertEquals(862, desktopFeed.items.single().durationSeconds)
        assertEquals(1_500_000L, desktopFeed.items.single().views)
        assertEquals(9, desktopFeed.totalPages)

        val mobileFeed = plugin.feedSource.loadFeed(FeedRequest(SiteId.PORNHUB, section = "mostviewed"))
        assertEquals("ph987654wxyz", mobileFeed.items.single().id)
        assertEquals("Mobile Clip", mobileFeed.items.single().title)
        assertEquals(425, mobileFeed.items.single().durationSeconds)
        assertEquals(42_000L, mobileFeed.items.single().views)
        assertEquals("https://ew.phncdn.com/mprev.webm", mobileFeed.items.single().previewUrl)

        val detail = plugin.videoSource.loadVideoDetail("https://pornhub.com/view_video.php?viewkey=ph654321abcd")
        assertEquals(listOf("1080p", "480p", "原画"), detail.streams.map { it.label })
        assertTrue(detail.streams[0].isHls)
        assertTrue(detail.streams[1].isHls)
        assertFalse(detail.streams[2].isHls)
        assertEquals("jane-doe", detail.authors.single().id)
        assertEquals("animation", detail.categories.single().slug)
        assertEquals("hd-video", detail.tags.single().slug)

        val link = plugin.linkResolver.resolve("https://pornhub.com/video/search?search=cosplay&t=tag") as ResolvedLink.Feed
        assertEquals(SearchScope.TAG, link.request.scope)
        assertEquals("cosplay", link.request.query)
    }

    @Test
    fun `missav, jable, pornhub, kbjfree, and rouvideo wire search sort params and supportedSearchSorts`() {
        val http = FakeHttpClientAdapter()

        // MissAV
        val missav = MissavSitePlugin(http, rateLimitBackoffMs = 0L)
        assertEquals(
            listOf(FeedSort.RELEVANCE, FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.LIKES, FeedSort.POPULAR),
            missav.searchSource.supportedSearchSorts(SearchScope.ALL),
        )
        assertEquals(
            "https://missav.ws/search/midv",
            MissavSitePlugin.resolveFeedUrl(FeedRequest(SiteId.MISSAV, query = "midv", sort = FeedSort.RELEVANCE)),
        )
        assertEquals(
            "https://missav.ws/search/midv?sort=released_at&page=2",
            MissavSitePlugin.resolveFeedUrl(FeedRequest(SiteId.MISSAV, query = "midv", sort = FeedSort.NEWEST, page = 2)),
        )
        assertEquals(
            "https://missav.ws/search/midv?sort=views",
            MissavSitePlugin.resolveFeedUrl(FeedRequest(SiteId.MISSAV, query = "midv", sort = FeedSort.VIEWS)),
        )
        assertEquals(
            "https://missav.ws/search/midv?sort=saved",
            MissavSitePlugin.resolveFeedUrl(FeedRequest(SiteId.MISSAV, query = "midv", sort = FeedSort.LIKES)),
        )
        assertEquals(
            "https://missav.ws/search/midv?sort=today_views",
            MissavSitePlugin.resolveFeedUrl(FeedRequest(SiteId.MISSAV, query = "midv", sort = FeedSort.POPULAR)),
        )
        val savedLink = missav.linkResolver.resolve("https://missav.ws/search/midv?sort=saved") as ResolvedLink.Feed
        assertEquals(FeedSort.LIKES, savedLink.request.sort)

        // Jable
        val jable = JableSitePlugin(http, rateLimitBackoffMs = 0L)
        assertEquals(
            listOf(FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.POPULAR),
            jable.searchSource.supportedSearchSorts(SearchScope.ALL),
        )
        assertEquals(
            "https://jable.vip/vodsearch/midv-time----------/",
            JableSitePlugin.resolveFeedUrl(FeedRequest(SiteId.JABLE, query = "midv", sort = FeedSort.NEWEST)),
        )
        assertEquals(
            "https://jable.vip/vodsearch/midv-hits----------/",
            JableSitePlugin.resolveFeedUrl(FeedRequest(SiteId.JABLE, query = "midv", sort = FeedSort.VIEWS)),
        )

        // Pornhub
        val pornhub = PornhubSitePlugin(http, rateLimitBackoffMs = 0L)
        assertEquals(
            listOf(FeedSort.RELEVANCE, FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.RATING, FeedSort.DURATION),
            pornhub.searchSource.supportedSearchSorts(SearchScope.ALL),
        )
        assertEquals(listOf(FeedSort.NEWEST), pornhub.searchSource.supportedSearchSorts(SearchScope.AUTHOR))
        assertEquals(
            "https://www.pornhub.com/video/search?search=anime",
            PornhubSitePlugin.resolveFeedUrl(FeedRequest(SiteId.PORNHUB, query = "anime", sort = FeedSort.RELEVANCE)),
        )
        assertEquals(
            "https://www.pornhub.com/video/search?search=anime&o=mr&page=2",
            PornhubSitePlugin.resolveFeedUrl(FeedRequest(SiteId.PORNHUB, query = "anime", sort = FeedSort.NEWEST, page = 2)),
        )
        assertEquals(
            "https://www.pornhub.com/video/search?search=anime&o=mv",
            PornhubSitePlugin.resolveFeedUrl(FeedRequest(SiteId.PORNHUB, query = "anime", sort = FeedSort.VIEWS)),
        )
        assertEquals(
            "https://www.pornhub.com/video/search?search=anime&o=tr",
            PornhubSitePlugin.resolveFeedUrl(FeedRequest(SiteId.PORNHUB, query = "anime", sort = FeedSort.RATING)),
        )
        assertEquals(
            "https://www.pornhub.com/video/search?search=anime&o=tr",
            PornhubSitePlugin.resolveFeedUrl(FeedRequest(SiteId.PORNHUB, query = "anime", sort = FeedSort.LIKES)),
        )
        assertEquals(
            "https://www.pornhub.com/video/search?search=anime&o=lg",
            PornhubSitePlugin.resolveFeedUrl(FeedRequest(SiteId.PORNHUB, query = "anime", sort = FeedSort.DURATION)),
        )
        val phSortedLink = pornhub.linkResolver.resolve("https://www.pornhub.com/video/search?search=anime&o=lg") as ResolvedLink.Feed
        assertEquals(FeedSort.DURATION, phSortedLink.request.sort)

        // KBJFree & rou.video
        val kbj = KbjfreeSitePlugin(http, rateLimitBackoffMs = 0L)
        assertEquals(listOf(FeedSort.NEWEST), kbj.searchSource.supportedSearchSorts(SearchScope.ALL))
        val rou = RouvideoSitePlugin(http, rateLimitBackoffMs = 0L)
        assertEquals(listOf(FeedSort.NEWEST), rou.searchSource.supportedSearchSorts(SearchScope.ALL))
    }
}
