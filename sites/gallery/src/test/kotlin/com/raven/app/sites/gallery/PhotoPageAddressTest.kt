package com.raven.app.sites.gallery

import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPageAddressTest {

    @Test
    fun encodeAndDecodeSurviveProcessRestartForEveriaAndFourKhd() {
        val everiaPlugin = EveriaSitePlugin(FakeHttpClientAdapter())
        val everiaCanonical = "https://everia.club/2026/11/04/sample-model-photobook-one/"
        val encoded = PhotoPageAddress.encode("${everiaCanonical}2/", flatIndex = 13, contentPage = 2, offset = 0)
        assertEquals("https://everia.club/2026/11/04/sample-model-photobook-one/2/#13-2-0", encoded)

        val decoded = everiaPlugin.engine.decodePageIdentity(encoded)
        assertNotNull(decoded)
        assertEquals(everiaCanonical, decoded!!.canonicalUrl)
        assertEquals(13, decoded.flatIndex)
        assertEquals(2, decoded.contentPage)
        assertEquals(0, decoded.offset)

        val fourKhdPlugin = FourKhdSitePlugin(FakeHttpClientAdapter())
        val fourKhdCanonical = "https://www.4khd.com/content/04/sample-set-one.html"
        val fourKhdUrl = PhotoPageAddress.encode("$fourKhdCanonical/3", flatIndex = 7, contentPage = 3, offset = 1)
        assertEquals("https://www.4khd.com/content/04/sample-set-one.html/3#7-3-1", fourKhdUrl)
        val fourKhdIdentity = fourKhdPlugin.engine.decodePageIdentity(fourKhdUrl)
        assertNotNull(fourKhdIdentity)
        assertEquals(fourKhdCanonical, fourKhdIdentity!!.canonicalUrl)
        assertEquals(7, fourKhdIdentity.flatIndex)
        assertEquals(3, fourKhdIdentity.contentPage)
        assertEquals(1, fourKhdIdentity.offset)
    }

    @Test
    fun variableLengthMiddlePagesBuildExactFlatIndices() {
        val everiaCanonical = "https://everia.club/2026/11/04/sample/"
        val everiaPlugin = EveriaSitePlugin(FakeHttpClientAdapter())
        val pages = PhotoPageAddress.buildPages(
            pageSizes = listOf(12, 6, 12),
            contentPageUrlFor = { page -> if (page == 1) everiaCanonical else "${everiaCanonical}$page/" },
        )
        assertEquals(30, pages.size)
        assertEquals((1..30).toList(), pages.map { it.index })

        val nineteenth = everiaPlugin.engine.decodePageIdentity(pages[18].pageUrl)!!
        assertEquals(19, nineteenth.flatIndex)
        assertEquals(3, nineteenth.contentPage)
        assertEquals(0, nineteenth.offset)

        val last = everiaPlugin.engine.decodePageIdentity(pages.last().pageUrl)!!
        assertEquals(30, last.flatIndex)
        assertEquals(3, last.contentPage)
        assertEquals(11, last.offset)
    }

    @Test
    fun decodeRejectsForeignHostsNonHttpsAndMalformedFragments() {
        val everia = EveriaSitePlugin(FakeHttpClientAdapter())
        assertNull(everia.engine.decodePageIdentity("https://example.invalid/2026/11/04/sample/#1-1-0"))
        assertNull(everia.engine.decodePageIdentity("http://everia.club/2026/11/04/sample/#1-1-0"))
        assertNull(everia.engine.decodePageIdentity("https://user:pass@everia.club/2026/11/04/sample/#1-1-0"))
        assertNull(everia.engine.decodePageIdentity("https://everia.club/page/2/#1-1-0"))
        assertNull(everia.engine.decodePageIdentity("https://everia.club/2026/11/04/sample/"))
        assertNull(everia.engine.decodePageIdentity("https://everia.club/2026/11/04/sample/#not-valid"))
        assertNull(everia.engine.decodePageIdentity("https://everia.club/2026/11/04/sample/#0-1-0"))
        assertNull(everia.engine.decodePageIdentity("https://everia.club/2026/11/04/sample/#1-0-0"))
    }

    @Test
    fun driftToleranceFollowsShortenedPagesUpToFourContentPagesAndPreservesIdentity() = runTest {
        val canonical = "https://everia.club/2026/11/04/sample/"
        val rawUrl = PhotoPageAddress.encode("${canonical}2/", flatIndex = 19, contentPage = 2, offset = 2)
        val visited = mutableListOf<Int>()

        val resolved = PhotoPageAddress.resolveImage(
            identity = PhotoPageIdentity(canonical, flatIndex = 19, contentPage = 2, offset = 2),
            rawPageUrl = rawUrl,
            siteId = SiteId.EVERIA,
        ) { book, page ->
            assertEquals(canonical, book)
            visited += page
            if (page == 2) listOf("https://karubox.top/1.webp")
            else listOf("https://karubox.top/2.webp", "https://karubox.top/target.webp")
        }

        assertEquals(listOf(2, 3), visited)
        assertEquals(19, resolved.index)
        assertEquals(rawUrl, resolved.pageUrl)
        assertEquals("https://karubox.top/target.webp", resolved.imageUrl)
    }

    @Test
    fun driftToleranceSucceedsOnExactFourthPageAndFailsBeyondFourOrOnEmptyPage() = runTest {
        val canonical = "https://everia.club/2026/11/04/sample/"
        val urlExact4 = PhotoPageAddress.encode("${canonical}2/", flatIndex = 19, contentPage = 2, offset = 3)
        val visited = mutableListOf<Int>()

        // Pages 2, 3, 4 each have 1 image (total 3), so offset 3 lands at index 0 of page 5 (4th visited page).
        val boundaryPage = PhotoPageAddress.resolveImage(
            identity = PhotoPageIdentity(canonical, flatIndex = 19, contentPage = 2, offset = 3),
            rawPageUrl = urlExact4,
            siteId = SiteId.EVERIA,
        ) { _, page ->
            visited += page
            listOf("https://karubox.top/page-$page.webp")
        }
        assertEquals(listOf(2, 3, 4, 5), visited)
        assertEquals("https://karubox.top/page-5.webp", boundaryPage.imageUrl)

        // Offset 4 needs a 5th page -> fails cleanly after visiting 4 pages (2, 3, 4, 5).
        visited.clear()
        val exceeded = runCatching {
            PhotoPageAddress.resolveImage(
                identity = PhotoPageIdentity(canonical, flatIndex = 19, contentPage = 2, offset = 4),
                rawPageUrl = PhotoPageAddress.encode("${canonical}2/", 19, 2, 4),
                siteId = SiteId.EVERIA,
            ) { _, page ->
                visited += page
                listOf("https://karubox.top/page-$page.webp")
            }
        }
        assertEquals(listOf(2, 3, 4, 5), visited)
        assertTrue(exceeded.exceptionOrNull() is SiteException.ParseError)

        // Empty intermediate page stops immediately without probing further pages.
        visited.clear()
        val emptyStop = runCatching {
            PhotoPageAddress.resolveImage(
                identity = PhotoPageIdentity(canonical, flatIndex = 19, contentPage = 2, offset = 4),
                rawPageUrl = PhotoPageAddress.encode("${canonical}2/", 19, 2, 4),
                siteId = SiteId.EVERIA,
            ) { _, page ->
                visited += page
                if (page == 2) listOf("https://karubox.top/first.webp") else emptyList()
            }
        }
        assertEquals(listOf(2, 3), visited)
        assertTrue(emptyStop.exceptionOrNull() is SiteException.ParseError)
    }

    @Test
    fun sparseResolvePageImageFetchesOnlyTargetContentPageAndSupportsForceReload() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val canonical = "https://everia.club/2026/11/04/sparse-book/"
        var page2Version = 1
        fakeHttp.stubDynamic("${canonical}2/") { req ->
            val html = """
                <html><body><article><div class="entry-content">
                  <figure><img src="https://karubox.top/wp-content/uploads/p2-v$page2Version-a.webp" /></figure>
                  <figure><img src="https://karubox.top/wp-content/uploads/p2-v$page2Version-b.webp" /></figure>
                </div></article></body></html>
            """.trimIndent()
            SiteHttpResponse(code = 200, finalUrl = req.url, body = html.toByteArray())
        }

        val plugin = EveriaSitePlugin(fakeHttp)
        val savedPageUrl = PhotoPageAddress.encode("${canonical}2/", flatIndex = 14, contentPage = 2, offset = 1)

        // Cold resolve fetches ONLY page 2 (never page 1)
        val first = plugin.gallerySource.resolvePageImage(savedPageUrl, forceReload = false)
        assertEquals(14, first.index)
        assertEquals("https://karubox.top/wp-content/uploads/p2-v1-b.webp", first.imageUrl)
        assertEquals(listOf("${canonical}2/"), fakeHttp.recordedRequests.map { it.url })

        // Cached resolve does not hit HTTP again
        page2Version = 2
        val cached = plugin.gallerySource.resolvePageImage(savedPageUrl, forceReload = false)
        assertEquals("https://karubox.top/wp-content/uploads/p2-v1-b.webp", cached.imageUrl)
        assertEquals(1, fakeHttp.recordedRequests.size)

        // forceReload = true invalidates cache, sets allowCache = false, and retrieves fresh URL
        val refreshed = plugin.gallerySource.resolvePageImage(savedPageUrl, forceReload = true)
        assertEquals("https://karubox.top/wp-content/uploads/p2-v2-b.webp", refreshed.imageUrl)
        assertEquals(2, fakeHttp.recordedRequests.size)
        assertEquals(false, fakeHttp.recordedRequests.last().allowCache)
    }

    @Test
    fun sparseLoadGalleryPageWalksContentPagesWhenDetailHasEmptyPageList() = runTest {
        val fakeHttp = FakeHttpClientAdapter()
        val canonical = "https://www.4khd.com/content/04/sparse-set.html"
        fakeHttp.stubHtml(
            canonical,
            """
            <html><body><div class="entry-content">
              <p><a href="https://i0.wp.com/pic.4khd.com/s/1.webp"><img src="https://i0.wp.com/pic.4khd.com/s/1.webp" /></a></p>
              <p><a href="https://i0.wp.com/pic.4khd.com/s/2.webp"><img src="https://i0.wp.com/pic.4khd.com/s/2.webp" /></a></p>
            </div></body></html>
            """.trimIndent(),
        )
        fakeHttp.stubHtml(
            "$canonical/2",
            """
            <html><body><div class="entry-content">
              <p><a href="https://i0.wp.com/pic.4khd.com/s/3.webp"><img src="https://i0.wp.com/pic.4khd.com/s/3.webp" /></a></p>
              <p><a href="https://i0.wp.com/pic.4khd.com/s/4.webp"><img src="https://i0.wp.com/pic.4khd.com/s/4.webp" /></a></p>
            </div></body></html>
            """.trimIndent(),
        )

        val plugin = FourKhdSitePlugin(fakeHttp)
        val sparseDetail = GalleryDetail(
            item = MediaItem(
                siteId = SiteId.FOURKHD,
                id = "sparse-set",
                url = canonical,
                kind = MediaKind.PHOTO,
                title = "Sparse Set",
            ),
            pageCount = 4,
            pages = emptyList(),
        )

        val page4 = plugin.gallerySource.loadGalleryPage(sparseDetail, pageIndex = 4)
        assertEquals(4, page4.index)
        assertEquals("$canonical/2#4-2-1", page4.pageUrl)
        assertEquals("https://i0.wp.com/pic.4khd.com/s/4.webp", page4.imageUrl)
    }
}
