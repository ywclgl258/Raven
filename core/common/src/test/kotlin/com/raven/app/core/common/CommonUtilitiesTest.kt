package com.raven.app.core.common

import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonUtilitiesTest {

    @Test
    fun `custom app dispatchers route coroutine execution deterministically`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers: AppDispatchers = CustomAppDispatchers(testDispatcher)
        val result = withContext(dispatchers.io) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `natural sort orders numbered filenames humanly`() {
        val input = listOf("page10.jpg", "page2.jpg", "page1.jpg", "page003.jpg", "page100.jpg", "cover.jpg")
        val sorted = input.sortedWith(NaturalSortComparator)
        assertEquals(
            listOf("cover.jpg", "page1.jpg", "page2.jpg", "page003.jpg", "page10.jpg", "page100.jpg"),
            sorted,
        )
    }

    @Test
    fun `url sanitizer validates https rejects userinfo and matches domains safely`() {
        assertTrue(UrlSanitizer.isHttpsUrl("https://rule34video.com/video/123/"))
        assertFalse(UrlSanitizer.isHttpsUrl("http://rule34video.com/video/123/"))
        assertFalse(UrlSanitizer.isHttpsUrl("https://user:pass@rule34video.com/video/123/"))
        assertFalse(UrlSanitizer.isHttpsUrl("javascript:alert(1)"))

        assertEquals(
            "https://jmcomic-zzz.one/album/12345#chapters",
            UrlSanitizer.normalizeHttpsUrl("http://jmcomic-zzz.one/album/12345#chapters", preserveFragment = true),
        )
        assertEquals(
            "https://rule34video.com/video/123/",
            UrlSanitizer.normalizeHttpsUrl("http://rule34video.com/video/123/#frag", preserveFragment = false),
        )

        assertTrue(UrlSanitizer.hostMatchesDomain("filesq.iwara.tv", "iwara.tv"))
        assertTrue(UrlSanitizer.hostMatchesDomain("iwara.tv", "iwara.tv"))
        assertFalse(UrlSanitizer.hostMatchesDomain("evil-iwara.tv", "iwara.tv"))

        assertTrue(UrlSanitizer.hostMatchesSite("www.missav.media", "missav.ws", listOf("missav.media")))
        assertFalse(UrlSanitizer.hostMatchesSite("missav.fake", "missav.ws", listOf("missav.media")))

        assertEquals("https://example.com/a/b.jpg", UrlSanitizer.resolveRelative("https://example.com/a/page", "b.jpg"))
        assertEquals("https://cdn.example.com/1.webp", UrlSanitizer.resolveRelative("https://example.com/", "//cdn.example.com/1.webp"))
        assertNull(UrlSanitizer.resolveRelative("https://example.com/", "data:image/gif;base64,R0lGOD"))
    }

    @Test
    fun `url sanitizer extracts links from noisy share text`() {
        val text = "快看这个作品（https://nhentai.net/g/420000/）。还有 https://www.iwara.tv/video/abc123!"
        val urls = UrlSanitizer.extractUrlsFromText(text)
        assertEquals(
            listOf("https://nhentai.net/g/420000/", "https://www.iwara.tv/video/abc123"),
            urls,
        )
    }

    @Test
    fun `content filter isolates blocked terms per site and normalizes hyphens`() {
        val r34Item = MediaItem(
            siteId = SiteId.R34V,
            id = "1",
            url = "https://rule34video.com/video/1/big-breasts-demo/",
            kind = MediaKind.VIDEO,
            title = "Big Breasts Demo",
            tags = listOf(TagRef(slug = "blonde")),
        )
        val iwaraItem = MediaItem(
            siteId = SiteId.IWARA,
            id = "2",
            url = "https://www.iwara.tv/video/2",
            kind = MediaKind.VIDEO,
            title = "Big Breasts Dance",
            tags = listOf(TagRef(slug = "blonde")),
        )

        val blocked = listOf(BlockedTerm(SiteId.R34V, "big-breasts"))
        val allowed = ContentFilter.filterAllowed(listOf(r34Item, iwaraItem), blocked)
        assertEquals(listOf(iwaraItem), allowed)

        // Tag match
        assertFalse(ContentFilter.isAllowed(r34Item, listOf("BLONDE")))
        // Hyphen-as-space title match
        assertFalse(ContentFilter.isAllowed(r34Item, listOf("big-breasts")))
        // URL match
        assertFalse(ContentFilter.isAllowed(r34Item, listOf("demo")))
        // Non-matching term passes
        assertTrue(ContentFilter.isAllowed(r34Item, listOf("tentacles")))
    }

    @Test
    fun `tag dictionary loads all 165 bundled entries and ranks prefix suggestions first`() {
        val dict = TagDictionary.default
        assertEquals(165, dict.size)
        assertEquals("巨乳", dict.translate("big-breasts"))
        assertEquals("巨乳", dict.translate("-female:big-breasts"))
        assertEquals("巨乳", dict.translate("big breasts"))
        assertEquals("触手", dict.translate("tentacles"))
        assertNull(dict.translate("non-existent-tag-xyz"))

        val suggestions = dict.suggest("big", limit = 5)
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.slug.contains("big") || it.localizedName.contains("big") })
        assertTrue(suggestions.first().slug.startsWith("big"))

        val zhSuggestions = dict.suggest("巨", limit = 8)
        assertTrue(zhSuggestions.any { it.slug == "big-breasts" && it.localizedName == "巨乳" })
    }

    @Test
    fun `timestamp parser extracts clickable spans parses multi-format durations and formats cleanly`() {
        val text = "Intro 0:05, highlight at 01:23, climax 1:02:03, invalid 99:99 and out-of-range 2:00:00."
        val spans = TimestampParser.parseSpans(text, maxDurationMs = 5_400_000L) // 1h30m
        assertEquals(3, spans.size)
        assertEquals(5_000L, spans[0].positionMs)
        assertEquals("0:05", spans[0].matchedText)
        assertEquals(83_000L, spans[1].positionMs)
        assertEquals(3_723_000L, spans[2].positionMs)

        assertEquals(5_460_000L, TimestampParser.parseDurationToMs("PT1H31M0S"))
        assertEquals(5_025_000L, TimestampParser.parseDurationToMs("1小時23分45秒"))
        assertEquals(150_000L, TimestampParser.parseDurationToMs("02:30"))
        assertEquals("02:30", TimestampParser.formatDurationSeconds(150))
        assertEquals("1:02:03", TimestampParser.formatDurationSeconds(3723))
    }

    @Test
    fun `page selection parser expands ranges and rejects invalid bounds`() {
        assertEquals(listOf(1, 3, 5, 6, 7, 8), PageSelectionParser.parse("1, 3，5-8", totalPages = 10))
        assertEquals(listOf(2, 3, 4), PageSelectionParser.parse("4 2–4", totalPages = 5))
    }
}
