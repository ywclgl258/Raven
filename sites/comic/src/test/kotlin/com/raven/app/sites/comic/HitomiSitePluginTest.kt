package com.raven.app.sites.comic

import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HitomiSitePluginTest {

    private val sampleHashA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789ab012f"
    // bucket for sampleHashA: last(1)='f' + last(3).take(2)='12' -> 0xf12 = 3858
    private val sampleHashB = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876540040"
    // bucket for sampleHashB: last(1)='0' + last(3).take(2)='04' -> 0x004 = 4

    private val sampleGgJs = """
        'use strict';
        var gg = {
          m: function(g) {
            var o = 0;
            switch (g) {
              case 3858:
              case 100:
                o = 1;
                break;
            }
            return o;
          },
          b: '1740000000/'
        };
    """.trimIndent()

    private fun packBigEndianInts(ids: List<Int>): ByteArray {
        val buf = ByteBuffer.allocate(ids.size * 4).order(ByteOrder.BIG_ENDIAN)
        ids.forEach { buf.putInt(it) }
        return buf.array()
    }

    private fun packDataEntry(ids: List<Int>): ByteArray {
        val buf = ByteBuffer.allocate(4 + ids.size * 4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(ids.size)
        ids.forEach { buf.putInt(it) }
        return buf.array()
    }

    /**
     * Builds a canonical 464-byte big-endian Hitomi B-Tree node.
     */
    private fun packBTreeNode(
        keys: List<ByteArray>,
        data: List<Pair<Long, Int>>,
        children: List<Long> = List(17) { 0L },
    ): ByteArray {
        require(keys.size == data.size)
        require(children.size == 17)
        val buf = ByteBuffer.allocate(HitomiSitePlugin.BTREE_NODE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(keys.size)
        for (k in keys) {
            buf.putInt(k.size)
            buf.put(k)
        }
        buf.putInt(data.size)
        for ((offset, len) in data) {
            buf.putLong(offset)
            buf.putInt(len)
        }
        for (child in children) {
            buf.putLong(child)
        }
        return buf.array()
    }

    private fun galleryJs(id: Int, title: String = "Hitomi Book $id", blocked: Boolean = false): String = """
        var galleryinfo = {
          "id": "$id",
          "title": "$title",
          "japanese_title": "日本語タイトル $id",
          "language": "chinese",
          "language_localname": "中文",
          "type": "doujinshi",
          "date": "2026-02-01 12:00:00-05",
          "blocked": $blocked,
          "artists": [{"artist": "alpha artist"}],
          "groups": [{"group": "beta circle"}],
          "parodys": [{"parody": "original"}],
          "characters": [{"character": "heroine"}],
          "tags": [
            {"tag": "sole female", "female": "1", "male": ""},
            {"tag": "glasses", "female": "", "male": "1"}
          ],
          "files": [
            {"hash": "$sampleHashA", "width": 1200, "height": 1700, "name": "01.webp"},
            {"hash": "$sampleHashB", "width": 1200, "height": 1700, "name": "02.webp"}
          ]
        };
    """.trimIndent()

    @Test
    fun `gg js regex parser routes webp page and thumbnail urls without js evaluation`() {
        val routing = HitomiSitePlugin.parseGgRouting(sampleGgJs)
        assertEquals("1740000000/", routing.prefix)
        assertEquals(0, routing.defaultServer)
        assertEquals(1, routing.overrides[3858])
        assertEquals(1, routing.overrides[100])

        // Bucket 3858 overrides to server=1 -> w2 & btn
        assertEquals(3858, routing.bucket(sampleHashA))
        assertEquals(
            "https://w2.gold-usergeneratedcontent.net/1740000000/3858/$sampleHashA.webp",
            routing.imageUrl(sampleHashA),
        )
        assertEquals(
            "https://btn.gold-usergeneratedcontent.net/webpbigtn/f/12/$sampleHashA.webp",
            routing.thumbUrl(sampleHashA),
        )

        // Bucket 4 falls back to default=0 -> w1 & atn
        assertEquals(4, routing.bucket(sampleHashB))
        assertEquals(
            "https://w1.gold-usergeneratedcontent.net/1740000000/4/$sampleHashB.webp",
            routing.imageUrl(sampleHashB),
        )
        assertEquals(
            "https://atn.gold-usergeneratedcontent.net/webpbigtn/0/04/$sampleHashB.webp",
            routing.thumbUrl(sampleHashB),
        )
    }

    @Test
    fun `nozomi range 206 pagination works for latest, popular, category, language, and rejects non-206`() = runTest {
        val http = FakeHttpClientAdapter()
        val allIds = (1000 downTo 981).toList() // 20 items -> 2 pages (12 + 8)
        http.onUrlText("${HitomiSitePlugin.CDN_BASE}/gg.js", sampleGgJs)
        http.onBinaryWithRange("${HitomiSitePlugin.CDN_BASE}/index-all.nozomi", packBigEndianInts(allIds))
        http.onBinaryWithRange("${HitomiSitePlugin.CDN_BASE}/popular/year-all.nozomi", packBigEndianInts(listOf(999, 998)))
        http.onBinaryWithRange("${HitomiSitePlugin.CDN_BASE}/type/doujinshi-all.nozomi", packBigEndianInts(listOf(995, 994)))
        http.onBinaryWithRange("${HitomiSitePlugin.CDN_BASE}/index-chinese.nozomi", packBigEndianInts(listOf(997, 996)))
        http.onBinaryWithRange("${HitomiSitePlugin.CDN_BASE}/index-japanese.nozomi", packBigEndianInts(listOf(998, 996, 993)))
        http.onMatch({ req -> req.url.startsWith("${HitomiSitePlugin.CDN_BASE}/galleries/") && req.url.endsWith(".js") }) { req ->
            val id = req.url.substringAfterLast('/').removeSuffix(".js").toInt()
            if (id == 990) {
                SiteHttpResponse(code = 404, finalUrl = req.url)
            } else {
                FakeHttpClientAdapter.htmlResponse(req.url, galleryJs(id))
            }
        }

        val plugin = HitomiSitePlugin(http)

        // Page 1 requests Range: bytes=0-47, skips deleted gallery 990 gracefully
        val page1 = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.HITOMI, page = 1))
        val firstRangeReq = http.recordedRequests.first { it.url.endsWith("index-all.nozomi") }
        assertEquals("bytes=0-47", firstRangeReq.rangeHeader)
        assertEquals(2, page1.totalPages)
        assertTrue(page1.hasMore)
        assertEquals(11, page1.items.size) // 12 minus 404 gallery 990

        // Page 2 requests Range: bytes=48-95
        val page2 = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.HITOMI, page = 2))
        val secondRangeReq = http.recordedRequests.last { it.url.endsWith("index-all.nozomi") }
        assertEquals("bytes=48-95", secondRangeReq.rangeHeader)
        assertEquals(8, page2.items.size)
        assertFalse(page2.hasMore)

        // Popular, category, and dual-language merge
        val pop = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.HITOMI, section = "popular", sort = FeedSort.POPULAR))
        assertEquals(listOf("999", "998"), pop.items.map { it.id })

        val cat = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.HITOMI, category = "doujinshi"))
        assertEquals(listOf("995", "994"), cat.items.map { it.id })

        val dual = plugin.feedSource!!.loadFeed(FeedRequest(SiteId.HITOMI, language = ComicLanguage.CHINESE_AND_JAPANESE))
        // Deduplicated union of [997, 996] and [998, 996, 993] sorted descending
        assertEquals(listOf("998", "997", "996", "993"), dual.items.map { it.id })

        // Reject 200 OK when Range 206 was requested
        val badHttp = FakeHttpClientAdapter()
        badHttp.onUrl(
            "${HitomiSitePlugin.CDN_BASE}/index-all.nozomi",
            SiteHttpResponse(code = 200, finalUrl = "${HitomiSitePlugin.CDN_BASE}/index-all.nozomi", body = packBigEndianInts(listOf(1, 2))),
        )
        try {
            HitomiSitePlugin(badHttp).feedSource!!.loadFeed(FeedRequest(SiteId.HITOMI, page = 1))
            fail("Expected SiteException.ParseError when server ignores Range 206")
        } catch (_: SiteException.ParseError) {
            // expected
        }
    }

    @Test
    fun `464-byte sha256 disk b-tree search supports multi-level lookup, intersection, exclusion, and popular sort`() = runTest {
        val http = FakeHttpClientAdapter()
        val version = "1740000123"
        http.onUrlText("${HitomiSitePlugin.CDN_BASE}/gg.js", sampleGgJs)
        http.onUrlText("${HitomiSitePlugin.CDN_BASE}/galleriesindex/version", "$version\n")

        // Construct sorted SHA-256 4-byte keys for three words: "cat", "dog", "fox"
        val wordData = listOf(
            "cat" to listOf(500, 400, 300),
            "dog" to listOf(400, 300, 200),
            "fox" to listOf(300),
        ).map { (word, ids) ->
            Triple(word, HitomiSitePlugin.sha256Prefix4(word), packDataEntry(ids))
        }.sortedWith { a, b -> HitomiSitePlugin.compareUnsignedBytes(a.second, b.second) }

        // Pack .data file sequentially and record (offset, len)
        val dataOffsets = mutableListOf<Pair<Long, Int>>()
        var cursor = 0L
        val combinedData = ByteArray(wordData.sumOf { it.third.size })
        for (entry in wordData) {
            dataOffsets.add(cursor to entry.third.size)
            entry.third.copyInto(combinedData, destinationOffset = cursor.toInt())
            cursor += entry.third.size
        }

        // Build 2-level B-Tree: root at offset 0 holds middle key (index 1);
        // child[0] at offset 464 holds smallest key (index 0);
        // child[1] at offset 928 holds largest key (index 2).
        val leftLeaf = packBTreeNode(
            keys = listOf(wordData[0].second),
            data = listOf(dataOffsets[0]),
        )
        val rightLeaf = packBTreeNode(
            keys = listOf(wordData[2].second),
            data = listOf(dataOffsets[2]),
        )
        val rootChildren = MutableList(17) { 0L }.apply {
            this[0] = HitomiSitePlugin.BTREE_NODE_BYTES.toLong()
            this[1] = HitomiSitePlugin.BTREE_NODE_BYTES.toLong() * 2L
        }
        val rootNode = packBTreeNode(
            keys = listOf(wordData[1].second),
            data = listOf(dataOffsets[1]),
            children = rootChildren,
        )
        val fullIndex = rootNode + leftLeaf + rightLeaf

        http.onBinaryWithRange("${HitomiSitePlugin.CDN_BASE}/galleriesindex/galleries.$version.index", fullIndex)
        http.onBinaryWithRange("${HitomiSitePlugin.CDN_BASE}/galleriesindex/galleries.$version.data", combinedData)
        http.onBinaryWithRange("${HitomiSitePlugin.CDN_BASE}/popular/year-all.nozomi", packBigEndianInts(listOf(300, 400, 500)))
        http.onMatch({ req -> req.url.endsWith(".js") && req.url.contains("/galleries/") }) { req ->
            val id = req.url.substringAfterLast('/').removeSuffix(".js").toInt()
            FakeHttpClientAdapter.htmlResponse(req.url, galleryJs(id))
        }

        val plugin = HitomiSitePlugin(http)

        // cat AND dog -> {400, 300}, minus fox ({300}) -> {400}
        val res = plugin.searchSource!!.search(
            FeedRequest(SiteId.HITOMI, query = "cat dog -fox", scope = SearchScope.ALL),
        )
        assertEquals(listOf("400"), res.items.map { it.id })

        // cat AND dog with popular sort -> preserves year-all.nozomi order [300, 400]
        val popRes = plugin.searchSource!!.search(
            FeedRequest(SiteId.HITOMI, query = "cat dog", sort = FeedSort.POPULAR),
        )
        assertEquals(listOf("300", "400"), popRes.items.map { it.id })
    }

    @Test
    fun `gallery metadata parsing, blocked check, page resolution, and link resolver work`() = runTest {
        val http = FakeHttpClientAdapter()
        http.onUrlText("${HitomiSitePlugin.CDN_BASE}/gg.js", sampleGgJs)
        http.onUrlText("${HitomiSitePlugin.CDN_BASE}/galleries/123456.js", galleryJs(123456))
        http.onUrlText("${HitomiSitePlugin.CDN_BASE}/galleries/666666.js", galleryJs(666666, blocked = true))

        val plugin = HitomiSitePlugin(http)

        val detail = plugin.gallerySource!!.loadGalleryDetail("https://hitomi.la/doujinshi/some-book-123456.html")
        assertEquals("123456", detail.item.id)
        assertEquals("中文", detail.item.language)
        assertEquals(2, detail.pageCount)
        assertTrue(detail.tags.any { it.slug == "female:sole_female" && it.namespace == "female" })
        assertTrue(detail.tags.any { it.slug == "male:glasses" && it.namespace == "male" })
        assertTrue(detail.authors.any { it.name == "alpha artist" && it.role == "artist" })
        assertTrue(detail.characters.any { it.label == "heroine" })

        val page2 = plugin.gallerySource!!.resolvePageImage("https://hitomi.la/reader/123456.html#2")
        assertEquals(2, page2.index)
        assertEquals(
            "https://w1.gold-usergeneratedcontent.net/1740000000/4/$sampleHashB.webp",
            page2.imageUrl,
        )

        // Blocked gallery throws Forbidden
        try {
            plugin.gallerySource!!.loadGalleryDetail("https://hitomi.la/galleries/666666.html")
            fail("Expected SiteException.Forbidden")
        } catch (_: SiteException.Forbidden) {
            // expected
        }

        val resolver = plugin.linkResolver!!
        assertEquals(
            ResolvedLink.Gallery(SiteId.HITOMI, "123456", "https://hitomi.la/galleries/123456.html"),
            resolver.resolve("https://hitomi.la/manga/test-123456.html"),
        )
        assertEquals(
            ResolvedLink.Gallery(SiteId.HITOMI, "123456", "https://hitomi.la/reader/123456.html#7", pageIndex = 7),
            resolver.resolve("https://hitomi.la/reader/123456.html#7"),
        )
        val searchLink = resolver.resolve("https://hitomi.la/search.html?query=artist%3Aalpha_artist") as ResolvedLink.Feed
        assertEquals("artist:alpha_artist", searchLink.request.query)
        assertNotNull(resolver.canonicalize("https://hitomi.la/doujinshi/test-123456.html"))
    }
}
