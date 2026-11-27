package com.raven.app.core.media.reader

import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.media.TestMediaFixtures
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.sitespi.JmcomicStripMath
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderCoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---- ReaderGeometry ----

    @Test
    fun `reader geometry clamps zoom 1 to 5 and crop 0 to 20 and computes exact pan limits`() {
        assertEquals(1f, ReaderGeometry.clampZoom(0.2f), 0.0001f)
        assertEquals(5f, ReaderGeometry.clampZoom(9.0f), 0.0001f)
        assertEquals(1f, ReaderGeometry.clampZoom(Float.NaN), 0.0001f)
        assertEquals(0, ReaderGeometry.clampCropPercent(-5))
        assertEquals(20, ReaderGeometry.clampCropPercent(45))

        // Width fit, 1000x2000 page in 1000x1000 container, zoom=1, crop=0 => rendered 1000x2000 => maxX=0, maxY=500
        val widthFit = ReaderGeometry.computePanLimit(
            bitmapWidth = 1000,
            bitmapHeight = 2000,
            containerWidth = 1000,
            containerHeight = 1000,
            fit = ReaderFit.WIDTH,
            cropPercent = 0,
            zoom = 1f,
        )
        assertEquals(0f, widthFit.maxX, 0.01f)
        assertEquals(500f, widthFit.maxY, 0.01f)

        // Height fit, 1000x2000 page in 1000x1000 container, zoom=2, crop=10% => scale = 0.5 * 2 * 1.1 = 1.1
        // rendered = 1100x2200 => maxX = (1100-1000)/2 = 50, maxY = (2200-1000)/2 = 600
        val heightFit = ReaderGeometry.computePanLimit(
            bitmapWidth = 1000,
            bitmapHeight = 2000,
            containerWidth = 1000,
            containerHeight = 1000,
            fit = ReaderFit.HEIGHT,
            cropPercent = 10,
            zoom = 2f,
        )
        assertEquals(50f, heightFit.maxX, 0.01f)
        assertEquals(600f, heightFit.maxY, 0.01f)

        // Original fit, 600x400 image in 1000x1000 container at zoom=1 => smaller than viewport => maxX=0, maxY=0
        val smallOriginal = ReaderGeometry.computePanLimit(600, 400, 1000, 1000, ReaderFit.ORIGINAL, 0, 1f)
        assertEquals(0f, smallOriginal.maxX, 0.01f)
        assertEquals(0f, smallOriginal.maxY, 0.01f)

        // Pan clamping prevents over-panning
        val clamped = ReaderGeometry.clampPan(PanOffset(x = 999f, y = -999f), heightFit)
        assertEquals(50f, clamped.x, 0.01f)
        assertEquals(-600f, clamped.y, 0.01f)
    }

    @Test
    fun `reader geometry computes spread pairs with combineCover false vs true`() {
        val separateCover = ReaderGeometry.computeSpreadPairs(pageCount = 5, spread = 2, combineCover = false)
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5)), separateCover)

        val combinedCover = ReaderGeometry.computeSpreadPairs(pageCount = 5, spread = 2, combineCover = true)
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), combinedCover)

        val nav = SpreadNavigator(pages = listOf(1, 2, 3, 4, 5), spread = 2, combineCover = false)
        assertEquals(0, nav.sheetIndex(1))
        assertEquals(1, nav.sheetIndex(3))
        assertEquals(2, nav.adjacentPage(1, forward = true))
        assertNull(nav.adjacentPage(1, forward = false))
        assertEquals(4, nav.adjacentPage(3, forward = true))
        assertEquals(1, nav.boundaryPage(forward = false))
        assertEquals(5, nav.boundaryPage(forward = true))
    }

    // ---- ReaderBuffer (24 MiB budget, farthest-first eviction, Semaphore(2), [current-1..current+3]) ----

    private data class FakeBitmapPage(val page: Int, val bytes: Long, val ratio: Float = 0.75f)

    @Test
    fun `reader buffer prefetches current-1 to current+3 and enforces 24MiB offscreen budget evicting farthest first`() =
        runTest {
            val pages = (1..10).toList()
            val tenMiB = 10L * 1024L * 1024L
            val loadedCalls = mutableListOf<Int>()

            val buffer = ReaderBuffer(
                pageNumbers = pages,
                scope = this,
                byteSizeOf = { it.bytes },
                aspectRatioOf = { it.ratio },
                offscreenBudgetBytes = ReaderBuffer.DEFAULT_OFFSCREEN_BUDGET_BYTES, // 24 MiB
                maxConcurrency = 2,
                loader = { page, _ ->
                    loadedCalls += page
                    FakeBitmapPage(page, bytes = tenMiB)
                },
            )

            // Anchor on page 3 with visible={3}: window should be [2, 3, 4, 5, 6]
            buffer.updateWindow(page = 3, visible = setOf(3))
            advanceUntilIdle()

            // Page 3 is visible/anchor (not counted in offscreen budget).
            // Offscreen candidates by distance from 3:
            // dist 1: page 2 (10 MiB), page 4 (10 MiB) -> 20 MiB <= 24 MiB (KEPT)
            // dist 2: page 5 (10 MiB) -> 30 MiB > 24 MiB (EVICTED)
            // dist 3: page 6 (10 MiB) -> EVICTED
            val state = buffer.state.value
            assertTrue(3 in state.pages)
            assertTrue(2 in state.pages)
            assertTrue(4 in state.pages)
            assertFalse(5 in state.pages)
            assertFalse(6 in state.pages)
            assertTrue(state.offscreenBytes <= ReaderBuffer.DEFAULT_OFFSCREEN_BUDGET_BYTES)
            assertEquals(20L * 1024L * 1024L, state.offscreenBytes)
            // Never loaded outside [2..6]
            assertTrue(loadedCalls.all { it in 2..6 })
            // Ratios preserved even for evicted pages
            assertEquals(0.75f, state.ratios[5])

            buffer.close()
        }

    @Test
    fun `visible page overlapping prefetch starts a single decode`() = runTest {
        val loaded = mutableListOf<Int>()
        val buffer = ReaderBuffer(
            pageNumbers = (1..6).toList(), scope = this, byteSizeOf = { 1L }, prefetchAhead = 1,
        ) { page, _ ->
            loaded += page
            page
        }
        buffer.updateWindow(1)
        advanceUntilIdle()
        // Page 2 is already loaded. Page 3 is both visible and the next prefetch.
        buffer.updateWindow(2, setOf(2, 3))
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), loaded)
        buffer.close()
    }

    @Test
    fun `reader buffer enforces Semaphore(2) max concurrency limit`() = runTest {
        val inFlight = AtomicInteger(0)
        val peakConcurrency = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val buffer = ReaderBuffer(
            pageNumbers = (1..6).toList(),
            scope = this,
            byteSizeOf = { 1024L },
            maxConcurrency = 2,
            loader = { page, _ ->
                val current = inFlight.incrementAndGet()
                peakConcurrency.updateAndGet { maxOf(it, current) }
                if (page > 1) {
                    delay(50)
                }
                gate.await()
                inFlight.decrementAndGet()
                FakeBitmapPage(page, 1024L)
            },
        )

        // Load visible pages 1, 2, 3, 4 simultaneously
        buffer.updateWindow(page = 1, visible = setOf(1, 2, 3, 4))
        testScheduler.runCurrent()
        assertEquals(2, peakConcurrency.get())

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(peakConcurrency.get() <= 2)
        assertEquals(setOf(1, 2, 3, 4), buffer.currentPages().keys)

        buffer.close()
    }

    @Test
    fun `reader buffer re-fetches previously evicted page when anchor moves and honors custom prefetchAhead`() =
        runTest {
            val tenMiB = 10L * 1024L * 1024L
            val loadCounts = HashMap<Int, Int>()

            val buffer = ReaderBuffer(
                pageNumbers = (1..8).toList(),
                scope = this,
                byteSizeOf = { it.bytes },
                offscreenBudgetBytes = 12L * 1024L * 1024L,
                maxConcurrency = 1,
                prefetchAhead = 5,
                loader = { page, _ ->
                    loadCounts[page] = (loadCounts[page] ?: 0) + 1
                    FakeBitmapPage(page, bytes = tenMiB)
                },
            )

            buffer.updateWindow(page = 1, visible = setOf(1))
            advanceUntilIdle()

            // Page 5 was within prefetchAhead=5, loaded and then evicted under 12MiB offscreen budget.
            assertFalse(5 in buffer.currentPages())
            assertTrue((loadCounts[5] ?: 0) >= 1)

            // Moving anchor to page 5 clears anchor-local eviction tracking so page 5 re-fetches!
            val priorCount = loadCounts[5] ?: 0
            buffer.updateWindow(page = 5, visible = setOf(5))
            advanceUntilIdle()
            assertTrue(5 in buffer.currentPages())
            assertEquals(priorCount + 1, loadCounts[5])

            buffer.close()
        }

    // ---- ImageStructureValidator & JmcomicBitmapUnscrambler ----

    @Test
    fun `image structure validator verifies container completeness and 4MP power-of-2 inSampleSize`() {
        assertTrue(ImageStructureValidator.isCompleteImageBytes(TestMediaFixtures.validPng()))
        assertFalse(ImageStructureValidator.isCompleteImageBytes(TestMediaFixtures.truncatedPng()))
        assertTrue(ImageStructureValidator.isCompleteImageBytes(TestMediaFixtures.validJpeg()))
        assertFalse(ImageStructureValidator.isCompleteImageBytes(TestMediaFixtures.truncatedJpeg()))
        assertTrue(ImageStructureValidator.isCompleteImageBytes(TestMediaFixtures.validGif()))
        assertTrue(ImageStructureValidator.isCompleteImageBytes(TestMediaFixtures.validWebp()))

        // 4000x4000 = 16MP -> sample 2 gives 2000x2000 = 4MP <= 4_000_000
        assertEquals(2, ImageStructureValidator.computeInSampleSize(4000, 4000, 4_000_000L))
        // 8000x8000 = 64MP -> sample 4 gives 2000x2000 = 4MP
        assertEquals(4, ImageStructureValidator.computeInSampleSize(8000, 8000, 4_000_000L))
        // 1200x1800 = 2.16MP -> sample 1
        assertEquals(1, ImageStructureValidator.computeInSampleSize(1200, 1800, 4_000_000L))

        val unscrambler = JmcomicBitmapUnscrambler()
        val page = GalleryPage(
            index = 1,
            pageUrl = "https://jmcomic/photo/500000#1",
            imageUrl = "https://cdn/media/photos/500000/00001.webp",
            unscrambleSeed = "500000:220980:00001",
            stripCount = 0,
        )
        val expectedStrips = JmcomicStripMath.computeStripCount(500_000L, 220_980L, "00001")
        assertEquals(expectedStrips, unscrambler.resolveStripCount(page, JmcomicStripMath))
        assertTrue(unscrambler.needsUnscramble(page, JmcomicStripMath))
    }

    // ---- GalleryExporter ----

    @Test
    fun `gallery exporter builds cbz archive, enforces 100 share cap, and cleans staging on failure`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val stagingRoot = tempFolder.newFolder("exports")
            val exporter = GalleryExporter(
                stagingRoot = stagingRoot,
                dispatchers = CustomAppDispatchers(dispatcher),
                requireAndroidImageDecoder = false,
            )

            // CBZ export with selected pages
            val prepared = exporter.prepare(
                title = "My/Comic:Vol*1",
                pages = listOf(3, 1, 2),
                mode = ExportMode.CBZ,
                loadPageBytes = { idx -> TestMediaFixtures.validPng("p$idx") },
            )
            assertEquals(1, prepared.files.size)
            val cbzFile = prepared.files.single().file
            assertTrue(cbzFile.name.endsWith(".cbz"))
            assertFalse(cbzFile.name.contains('/'))
            ZipFile(cbzFile).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(listOf("000001.png", "000002.png", "000003.png"), names)
            }
            exporter.removeStagingFolder(prepared.folder)
            assertFalse(prepared.folder.exists())

            // Share mode strictly rejects > 100 pages
            var shareRejected = false
            try {
                exporter.prepare(
                    title = "TooMany",
                    pages = (1..101).toList(),
                    mode = ExportMode.SHARE_IMAGES,
                    loadPageBytes = { TestMediaFixtures.validPng() },
                )
            } catch (_: IllegalArgumentException) {
                shareRejected = true
            }
            assertTrue(shareRejected)

            // Failure during load cleans up temporary staging directory automatically
            var exportFailed = false
            try {
                exporter.prepare(
                    title = "Failing",
                    pages = listOf(1, 2),
                    mode = ExportMode.CBZ,
                    loadPageBytes = { page ->
                        if (page == 2) throw IllegalStateException("page 2 missing")
                        TestMediaFixtures.validPng("p1")
                    },
                )
            } catch (_: IllegalStateException) {
                exportFailed = true
            }
            assertTrue(exportFailed)
            assertEquals(0, stagingRoot.listFiles().orEmpty().size)
        }
}
