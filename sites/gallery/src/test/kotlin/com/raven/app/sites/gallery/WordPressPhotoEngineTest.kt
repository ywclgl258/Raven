package com.raven.app.sites.gallery

import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordPressPhotoEngineTest {

    @Test
    fun lazyLoadAttributesSrcsetAndHighResAnchorFallbackWorkInPriorityOrder() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val url = "https://everia.club/2026/11/05/lazy-test/"
        fakeHttp.stubHtml(
            url,
            """
            <!DOCTYPE html>
            <html><head><title>Lazy Test - Everia.club</title></head><body>
            <article class="post">
              <div class="entry-content">
                <figure><a href="https://cdn.example.net/full-from-anchor.webp">
                  <img data-src="https://cdn.example.net/thumb-data-src.webp"
                       src="data:image/gif;base64,R0lGODlhAQABAIAAAP" />
                </a></figure>
                <figure>
                  <img data-lazy-src="https://cdn.example.net/from-lazy-src.webp"
                       src="data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=" />
                </figure>
                <figure>
                  <img data-src="https://cdn.example.net/from-data-src.jpg"
                       src="https://cdn.example.net/placeholder-low.jpg" />
                </figure>
                <figure>
                  <img src="https://cdn.example.net/small.webp"
                       srcset="https://cdn.example.net/photo.webp?resize=400,600 400w, https://cdn.example.net/photo.webp?resize=1600,2400 1600w, https://cdn.example.net/photo.webp?resize=800,1200 800w" />
                </figure>
                <figure><img src="https://cdn.example.net/icon.svg" /></figure>
                <figure><img src="https://secure.gravatar.com/avatar/12345.jpg" /></figure>
                <figure><img src="https://cdn.example.net/tracking.gif" width="1" height="1" /></figure>
                <figure><img src="https://cdn.example.net/ads/promo.jpg" alt="banner" /></figure>
              </div>
              <footer class="entry-footer">
                <span class="cat-links"><a href="https://everia.club/category/gravure/" rel="category tag">Gravure</a></span>
                <span class="tags-links">
                  <a href="https://everia.club/tag/studio/" rel="tag">Studio</a>
                  <a href="https://everia.club/tag/portrait/" rel="tag">Portrait</a>
                </span>
                <span class="byline"><a href="https://everia.club/author/editor/" rel="author">Editor</a></span>
              </footer>
              <aside><a href="https://everia.club/tag/sidebar-noise/" rel="tag">SidebarNoise</a></aside>
            </article>
            </body></html>
            """.trimIndent(),
        )

        val plugin = EveriaSitePlugin(fakeHttp)
        val detail = plugin.gallerySource.loadGalleryDetail(url)

        assertEquals(
            listOf(
                "https://cdn.example.net/full-from-anchor.webp",
                "https://cdn.example.net/from-lazy-src.webp",
                "https://cdn.example.net/from-data-src.jpg",
                "https://cdn.example.net/photo.webp?resize=1600,2400",
            ),
            detail.pages.map { it.imageUrl },
        )
        // High-res anchor photo preserves distinct lazy thumbnail
        assertEquals("https://cdn.example.net/thumb-data-src.webp", detail.pages.first().thumbUrl)
        // Tags, categories, authors extracted cleanly without aside noise
        assertEquals(listOf("gravure"), detail.categories.map { it.slug })
        assertEquals(listOf("studio", "portrait"), detail.tags.map { it.slug })
        assertEquals(listOf("Editor"), detail.authors.map { it.name })
        assertEquals("Editor", detail.item.author?.name)
    }

    @Test
    fun cardThumbnailsReadLazyAttributesWithoutFollowingPostAnchorHref() {
        val plugin = EveriaSitePlugin(FakeHttpClientAdapter())
        val html = """
            <html><body>
            <div class="rt-holder">
              <a href="https://everia.club/2026/11/05/lazy-card/">
                <img class="rt-img-responsive"
                     src="data:image/gif;base64,R0lGODlhAQABAIAAAP"
                     data-lazy-src="https://miya.fujibox.top/uploads/cover-lazy.webp"
                     width="400" height="600" />
              </a>
              <h3 class="entry-title"><a href="https://everia.club/2026/11/05/lazy-card/">Lazy Card</a></h3>
            </div>
            </body></html>
        """.trimIndent()
        val cards = plugin.engine.parseCards(Jsoup.parse(html, "https://everia.club/"))
        assertEquals(1, cards.size)
        assertEquals("https://miya.fujibox.top/uploads/cover-lazy.webp", cards.single().coverUrl)
        assertEquals(400f / 600f, cards.single().coverAspectRatio!!, 0.001f)
    }

    @Test
    fun passiveCloudflareScriptWithContentDoesNotTriggerChallengeError() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val passiveHtml = """
            <!DOCTYPE html>
            <html><head><title>Latest - 4KHD</title>
            <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script></head><body>
            <ul><li class="wp-block-post">
              <figure><img src="https://i0.wp.com/pic.4khd.com/a.jpg" width="850" height="1158" /></figure>
              <h2 class="wp-block-post-title"><a href="https://www.4khd.com/content/04/ok-post.html">OK Post</a></h2>
            </li></ul></body></html>
        """.trimIndent()
        fakeHttp.stubHtml("https://www.4khd.com/", passiveHtml)

        val plugin = FourKhdSitePlugin(fakeHttp)
        val page = plugin.feedSource.loadFeed(FeedRequest(SiteId.FOURKHD))
        assertEquals(1, page.items.size)
        assertEquals("ok-post", page.items.single().id)

        // Genuine Cloudflare interstitial challenge MUST throw SiteException.Forbidden(isChallenge = true)
        fakeHttp.stubHtml(
            "https://www.4khd.com/?query-3-page=2",
            "<html><head><title>Just a moment...</title></head><body>Checking your browser... challenge-platform</body></html>",
        )
        val challengeErr = runCatching {
            plugin.feedSource.loadFeed(FeedRequest(SiteId.FOURKHD, page = 2))
        }.exceptionOrNull()
        assertTrue(challengeErr is SiteException.Forbidden && challengeErr.isChallenge)
        assertFalse(GalleryUrlUtils.isChallengeHtml(passiveHtml))
    }

    @Test
    fun httpErrorCodesAndEmptySearchResultsMapToTypedExceptions() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        fakeHttp.stubHtml("https://everia.club/?s=missing", "<html><body><h1>Nothing Found</h1></body></html>")
        fakeHttp.stubHtml("https://everia.club/?s=broken", "<html><body><div>Unexpected layout</div></body></html>")
        fakeHttp.stubHtml("https://everia.club/?s=notfound", "not found", code = 404)
        fakeHttp.stubHtml(
            "https://everia.club/?s=ratelimit",
            "slow down",
            code = 429,
            headers = mapOf("Retry-After" to listOf("45")),
        )
        fakeHttp.stubHtml(
            "https://everia.club/?s=forbidden",
            "blocked",
            code = 403,
            headers = mapOf("cf-mitigated" to listOf("challenge")),
        )
        fakeHttp.stubHtml("https://everia.club/?s=servererr", "oops", code = 502)

        val plugin = EveriaSitePlugin(fakeHttp)

        val empty = plugin.searchSource.search(FeedRequest(SiteId.EVERIA, query = "missing"))
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasMore)

        val parseErr = runCatching { plugin.searchSource.search(FeedRequest(SiteId.EVERIA, query = "broken")) }.exceptionOrNull()
        assertTrue(parseErr is SiteException.ParseError)

        val notFound = runCatching { plugin.searchSource.search(FeedRequest(SiteId.EVERIA, query = "notfound")) }.exceptionOrNull()
        assertTrue(notFound is SiteException.NotFound)

        val rateLimit = runCatching { plugin.searchSource.search(FeedRequest(SiteId.EVERIA, query = "ratelimit")) }.exceptionOrNull()
        assertTrue(rateLimit is SiteException.RateLimited)
        assertEquals(45L, (rateLimit as SiteException.RateLimited).retryAfterSeconds)

        val forbidden = runCatching { plugin.searchSource.search(FeedRequest(SiteId.EVERIA, query = "forbidden")) }.exceptionOrNull()
        assertTrue(forbidden is SiteException.Forbidden && forbidden.isChallenge)

        val netErr = runCatching { plugin.searchSource.search(FeedRequest(SiteId.EVERIA, query = "servererr")) }.exceptionOrNull()
        assertTrue(netErr is SiteException.Network)
    }

    @Test
    fun parseTotalPagesReadsBareDataPagedAndStrictPrimaryContainerDoesNotFallback() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val cosplayPlugin = CosplayteleSitePlugin(fakeHttp)

        val pagedDoc = Jsoup.parse(
            """<html><body><div class="pagination"><a href="#" data-paged="14">14</a></div></body></html>""",
        )
        assertEquals(14, cosplayPlugin.engine.parseTotalPages(pagedDoc))

        // Cosplaytele enables strictPrimaryContainer=true on #gallery-1; without #gallery-1, secondary selectors must NOT leak images
        val fallbackTrapDoc = Jsoup.parse(
            """<html><body><div class="entry-content single-page"><img src="https://cosplaytele.com/wp-content/uploads/related-leak.webp" /></div></body></html>""",
        )
        assertEquals(emptyList<String>(), cosplayPlugin.engine.extractImages(fallbackTrapDoc))
    }

    @Test
    fun galleryEpicRscFallbackMatchesOnlyFullCdnImageUrlsNotArbitraryUuids() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val imageUuid = "aaaaaaaa-1111-4111-8111-111111111111"
        val trackerUuid = "99999999-9999-4999-8999-999999999999"
        val html = """
            <html><head><title>Test Gallery - Gallery Epic</title></head><body>
              <div data-analytics-id="$trackerUuid"></div>
              <img src="https://static.galleryepic.xyz/image/$imageUuid" />
              <dl><dt>Published</dt><dd><time dateTime="2026-11-03T00:00:00Z">2026-11-03</time></dd></dl>
            </body></html>
        """.trimIndent()
        val plugin = GalleryEpicSitePlugin(fakeHttp)
        fakeHttp.stubHtml("https://galleryepic.com/en/cosplay/555", html)
        val detail = plugin.gallerySource.loadGalleryDetail("https://galleryepic.com/en/cosplay/555")
        assertEquals(listOf("https://static.galleryepic.xyz/image/$imageUuid"), detail.pages.map { it.imageUrl })
    }
}
