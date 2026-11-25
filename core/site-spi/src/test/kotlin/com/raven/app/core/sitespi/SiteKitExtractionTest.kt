package com.raven.app.core.sitespi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteKitExtractionTest {

    @Test
    fun `base62 packer unpacks single pass without re-expanding dictionary values`() {
        // Payload tokens: 0='https', 1='cdn', 2='mrstcdn', 3='store', 4='01234567-89ab-cdef-0123-456789abcdef', 5='playlist', 6='m3u8', 7='source'
        val packed = """eval(function(p,a,c,k,e,d){return p}('7="0://1.2.3/4/5.6";',10,8,'https|cdn|mrstcdn|store|01234567-89ab-cdef-0123-456789abcdef|playlist|m3u8|source'.split('|'),0,{}))"""
        val unpacked = Base62Packer.unpack(packed)
        assertEquals("""source="https://cdn.mrstcdn.store/01234567-89ab-cdef-0123-456789abcdef/playlist.m3u8";""", unpacked)

        // Base-62 token encoding check: 10->a, 36->A, 61->Z, 62->10
        assertEquals("a", Base62Packer.encodeBaseToken(10, 62))
        assertEquals("A", Base62Packer.encodeBaseToken(36, 62))
        assertEquals("Z", Base62Packer.encodeBaseToken(61, 62))
        assertEquals("10", Base62Packer.encodeBaseToken(62, 62))

        val all = Base62Packer.unpackAll("<script>$packed</script>")
        assertEquals(1, all.size)
        assertTrue(all.first().contains("playlist.m3u8"))
    }

    @Test
    fun `js extractor extracts brace-matched objects containing nested braces and escaped strings`() {
        val html = """
            <script>
            var player_aaaa = {"flag":"play","note":"brace } inside \"quoted\" string","nested":{"a":1},"url":"https:\/\/cdn.jable.vip\/hls\/master.m3u8"}
            const source = 'https:\/\/hanime1.me\/v\/1080p.mp4';
            </script>
        """.trimIndent()

        val obj = JsExtractor.extractBraceObject(html, "player_aaaa")
        assertNotNull(obj)
        assertTrue(obj!!.startsWith("{") && obj.endsWith("}"))
        assertTrue(obj.contains("\"nested\":{\"a\":1}"))
        assertEquals(
            "https://hanime1.me/v/1080p.mp4",
            JsExtractor.extractJsStringVar(html, "source"),
        )
    }

    @Test
    fun `js extractor unescapes unicode hex and extracts next rsc flight chunks`() {
        assertEquals("A/B\"C\n", JsExtractor.unescapeJsString("""\x41\/\u0042\"C\n"""))

        val rscHtml = """
            <script>self.__next_f.push([1,"{\"id\":\"uuid-1\","])</script>
            <script>self.__next_f.push([1,"\"name\":\"Sample\"}"])</script>
        """.trimIndent()
        assertEquals("""{"id":"uuid-1","name":"Sample"}""", JsExtractor.extractNextRscFlightChunks(rscHtml))
    }

    @Test
    fun `html url cleaner normalizes whitespace entities encoding extensions and fragments`() {
        assertEquals("Hello World", HtmlUrlCleaner.cleanText("  Hello\u200b\u00a0  World \n "))
        assertEquals("Tom & Jerry's <Show> \"1\" A", HtmlUrlCleaner.decodeBasicHtmlEntities("Tom &amp; Jerry&#39;s &lt;Show&gt; &quot;1&quot; &#x41;"))
        // Prevent double-decoding &amp;#60; -> &#60; -> <
        assertEquals("&#60;script>", HtmlUrlCleaner.decodeBasicHtmlEntities("&amp;#60;script&gt;"))
        assertEquals("hello%20world", HtmlUrlCleaner.encodePathSegment("hello world"))
        assertEquals("https://i.nhentai.net/galleries/123/1.webp?v=1", HtmlUrlCleaner.deduplicateImageExtension("https://i.nhentai.net/galleries/123/1.webp.webp?v=1"))
        assertEquals("https://hitomi.la/reader/123.html", HtmlUrlCleaner.stripFragment("https://hitomi.la/reader/123.html#5"))
    }
}
