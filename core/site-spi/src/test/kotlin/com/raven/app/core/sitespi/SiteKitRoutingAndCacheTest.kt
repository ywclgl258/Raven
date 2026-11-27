package com.raven.app.core.sitespi

import com.raven.app.core.model.SiteException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SiteKitRoutingAndCacheTest {

    @Test
    fun `dual language merger merges descending numeric ids across pages with cursor continuation`() = runTest {
        val streamA = mapOf(
            1 to StreamSlice(listOf("100", "90", "80"), hasNext = true),
            2 to StreamSlice(listOf("70"), hasNext = false),
        )
        val streamB = mapOf(
            1 to StreamSlice(listOf("95", "90", "85"), hasNext = false), // "90" duplicates streamA
        )

        val page1 = DualLanguageMerger.merge(
            cursorRaw = null,
            pageSize = 3,
            strategy = DualMergeStrategy.DESCENDING_NUMERIC_ID,
            idOf = { it },
            fetchStreamA = { p -> streamA[p] ?: StreamSlice(emptyList(), false) },
            fetchStreamB = { p -> streamB[p] ?: StreamSlice(emptyList(), false) },
        )
        assertEquals(listOf("100", "95", "90"), page1.items)
        assertTrue(page1.hasMore)
        assertEquals("1,2,1,2", page1.nextCursor)

        val page2 = DualLanguageMerger.merge(
            cursorRaw = page1.nextCursor,
            pageSize = 5,
            strategy = DualMergeStrategy.DESCENDING_NUMERIC_ID,
            idOf = { it },
            fetchStreamA = { p -> streamA[p] ?: StreamSlice(emptyList(), false) },
            fetchStreamB = { p -> streamB[p] ?: StreamSlice(emptyList(), false) },
        )
        assertEquals(listOf("100", "95", "90", "85", "80", "70"), page1.items + page2.items)
        assertFalse(page2.hasMore)
        assertNull(page2.nextCursor)
    }

    @Test
    fun `dual language merger interleaves popular streams cleanly`() = runTest {
        val page = DualLanguageMerger.merge(
            cursorRaw = null,
            pageSize = 4,
            strategy = DualMergeStrategy.INTERLEAVED,
            idOf = { it },
            fetchStreamA = { StreamSlice(listOf("zh1", "zh2"), hasNext = false) },
            fetchStreamB = { StreamSlice(listOf("ja1", "ja2"), hasNext = false) },
        )
        assertEquals(listOf("zh1", "ja1", "zh2", "ja2"), page.items)
        assertFalse(page.hasMore)
        assertNull(page.nextCursor)
    }

    @Test
    fun `interleaved one-item pages retain turn and skip a delayed duplicate`() = runTest {
        val repeated = "a;b|c+%"
        val items = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = DualLanguageMerger.merge(
                cursor, 1, DualMergeStrategy.INTERLEAVED, { it },
                { StreamSlice(listOf(repeated, "c", "e"), false) },
                { StreamSlice(listOf("b", "d", repeated), false) },
            )
            items += page.items.single()
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(listOf(repeated, "b", "c", "d", "e"), items)
    }

    @Test(expected = SiteException.ParseError::class)
    fun `dual cursor rejects malformed input`() {
        DualCursor.parse("1,2,bad")
    }

    @Test
    fun `mirror lease router caches healthy mirror per route key and fails over cleanly`() = runTest {
        var now = 1_000L
        var probeCount = 0
        val healthyHosts = mutableSetOf("18comic.ink", "jmcomic-zzz.org")

        val router = MirrorLeaseRouter(
            hosts = listOf("jmcomic-zzz.one", "18comic.ink", "jmcomic-zzz.org"),
            leaseDurationMs = 600_000L,
            failureCooldownMs = 120_000L,
            clockMs = { now },
            probeHost = { host ->
                probeCount++
                host in healthyHosts
            },
        )

        // First call probes .one (fails) then .ink (succeeds)
        assertEquals("18comic.ink", router.activeHost("route-wifi"))
        assertEquals(2, probeCount)

        // Within 10-min lease on same routeKey -> no re-probe
        now += 300_000L
        assertEquals("18comic.ink", router.activeHost("route-wifi"))
        assertEquals(2, probeCount)

        // Route key changes (e.g., proxy switched) -> re-probes immediately
        assertEquals("18comic.ink", router.activeHost("route-proxy"))
        assertTrue(probeCount > 2)

        // Report failure on .ink -> fails over to .org
        router.reportHostFailure("18comic.ink", "route-proxy")
        assertEquals("jmcomic-zzz.org", router.activeHost("route-proxy"))

        // URL rewrite preserves path & query
        assertEquals(
            "https://jmcomic-zzz.org/album/12345?o=mr",
            router.rewriteUrl("https://jmcomic-zzz.one/album/12345?o=mr", "jmcomic-zzz.org"),
        )

        val diag = router.diagnostics()
        assertEquals("jmcomic-zzz.org", diag.activeHost)
        assertTrue("18comic.ink" in diag.coolingDownHosts)
        router.resetLease()
        assertNull(router.diagnostics().activeHost)
    }

    @Test
    fun `mirror lease router rethrows cancellation without cooling healthy mirrors`() = runTest {
        val router = MirrorLeaseRouter(
            hosts = listOf("a.example", "b.example"),
            probeHost = { throw CancellationException("cancelled") },
        )
        try {
            router.activeHost("r1")
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertTrue(router.diagnostics().coolingDownHosts.isEmpty())
    }

    @Test
    fun `lru cache enforces capacity order and ttl expiration`() = runTest {
        var now = 10_000L
        val cache = LruCache<String, Int>(maxEntries = 2, ttlMs = 5_000L, clockMs = { now })

        cache.put("a", 1)
        cache.put("b", 2)
        // Touch "a" so "b" becomes LRU
        assertEquals(1, cache.get("a"))
        cache.put("c", 3)
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))

        // Advance past TTL
        now += 6_000L
        assertNull(cache.get("a"))
        assertEquals(1, cache.evictExpired())
        assertEquals(0, cache.size())

        val computed = cache.getOrPutSuspend("fresh") { 99 }
        assertEquals(99, computed)
        assertEquals(99, cache.get("fresh"))
    }

    @Test
    fun `dual language merger advances past empty locally filtered page when hasNext is true`() = runTest {
        val streamA = mapOf(
            1 to StreamSlice<String>(items = emptyList(), hasNext = true),
            2 to StreamSlice(items = listOf("80"), hasNext = false),
        )
        val streamB = mapOf(
            1 to StreamSlice(items = listOf("90"), hasNext = false),
        )
        val merged = DualLanguageMerger.merge(
            cursorRaw = null,
            pageSize = 5,
            strategy = DualMergeStrategy.DESCENDING_NUMERIC_ID,
            idOf = { it },
            fetchStreamA = { p -> streamA[p] ?: StreamSlice(emptyList(), false) },
            fetchStreamB = { p -> streamB[p] ?: StreamSlice(emptyList(), false) },
        )
        assertEquals(listOf("90", "80"), merged.items)
        assertFalse(merged.hasMore)
    }
}
