package com.raven.app.core.media.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

/**
 * Immutable snapshot of [ReaderBuffer] state for reactive UI consumption.
 */
data class ReaderBufferState<T : Any>(
    val anchorPage: Int = 0,
    val visiblePages: Set<Int> = emptySet(),
    val pages: Map<Int, T> = emptyMap(),
    val errors: Map<Int, String> = emptyMap(),
    val ratios: Map<Int, Float> = emptyMap(),
    val loadingPages: Set<Int> = emptySet(),
    val offscreenBytes: Long = 0L,
)

/**
 * Manages comic/photo page loading and strong bitmap references independently of LazyItem composition.
 *
 * Invariants:
 * - Maintains `[current - 1 .. current + 3]` prefetch window plus any explicitly visible pages.
 * - Limits concurrent decode/network loads via bounded semaphore (default up to 3).
 * - Enforces a strict **24 MiB** offscreen byte budget, evicting farthest offscreen pages first.
 * - **NEVER** calls `Bitmap.recycle()` on evicted pages so Android RenderThread never crashes.
 */
class ReaderBuffer<T : Any>(
    private val pageNumbers: List<Int>,
    private val scope: CoroutineScope,
    private val byteSizeOf: (T) -> Long,
    private val aspectRatioOf: (T) -> Float? = { null },
    private val offscreenBudgetBytes: Long = DEFAULT_OFFSCREEN_BUDGET_BYTES,
    maxConcurrency: Int = MAX_CONCURRENT_LOADS,
    private val sharedRatios: MutableMap<Int, Float> = LinkedHashMap(),
    private val prefetchAhead: Int = PREFETCH_AHEAD,
    private val loader: suspend (pageNumber: Int, forceReload: Boolean) -> T,
) {
    private val stateLock = Any()
    private val loadedPages = LinkedHashMap<Int, T>()
    private val pageErrors = LinkedHashMap<Int, String>()
    private val activeJobs = LinkedHashMap<Int, Job>()
    private val evictedInCurrentAnchor = LinkedHashSet<Int>()
    private val permits = Semaphore(maxConcurrency.coerceAtLeast(1))

    private var anchorPage: Int = pageNumbers.firstOrNull() ?: 0
    private var visiblePages: Set<Int> = emptySet()
    private var lastOffscreenBytes: Long = 0L
    private var disposed: Boolean = false
    private val effectivePrefetchAhead: Int = prefetchAhead.coerceIn(0, MAX_PREFETCH_AHEAD)

    private val _state = MutableStateFlow(
        ReaderBufferState<T>(
            anchorPage = anchorPage,
            ratios = sharedRatios.toMap(),
        ),
    )
    val state: StateFlow<ReaderBufferState<T>> = _state.asStateFlow()

    fun currentPages(): Map<Int, T> = synchronized(stateLock) { loadedPages.toMap() }

    fun currentErrors(): Map<Int, String> = synchronized(stateLock) { pageErrors.toMap() }

    fun currentRatios(): Map<Int, Float> = synchronized(stateLock) { sharedRatios.toMap() }

    /**
     * Updates the active reading anchor and visible set, cancelling out-of-window loads,
     * scheduling `[anchor - 1 .. anchor + 3]` prefetch, and enforcing the 24 MiB offscreen budget.
     */
    fun updateWindow(page: Int, visible: Set<Int> = setOf(page)) {
        val jobsToStart = mutableListOf<Job>()
        val jobsToCancel = mutableListOf<Job>()

        synchronized(stateLock) {
            if (disposed || pageNumbers.isEmpty()) return
            val index = pageNumbers.binarySearch(page)
            if (index < 0) return

            val previousAnchor = anchorPage
            anchorPage = page
            visiblePages = visible
            if (previousAnchor != anchorPage) {
                evictedInCurrentAnchor.clear()
            }

            val startIdx = (index - PREFETCH_BEHIND).coerceAtLeast(0)
            val endExclusive = minOf(pageNumbers.size, index + effectivePrefetchAhead + 1)
            val windowPages = pageNumbers.subList(startIdx, endExclusive)
            val keep = LinkedHashSet<Int>(visible.size + windowPages.size).apply {
                addAll(visible)
                addAll(windowPages)
            }

            // Cancel jobs and drop references outside the keep window (never calling Bitmap.recycle()).
            activeJobs.keys.toList().filter { it !in keep }.forEach { number ->
                activeJobs.remove(number)?.let(jobsToCancel::add)
            }
            loadedPages.keys.toList().filter { it !in keep }.forEach { loadedPages.remove(it) }
            pageErrors.keys.toList().filter { it !in keep }.forEach { pageErrors.remove(it) }

            // Prioritize anchor & visible pages first.
            scheduleLocked(anchorPage, force = false, prefetch = false)?.let(jobsToStart::add)
            for (v in visible) {
                if (v != anchorPage) {
                    scheduleLocked(v, force = false, prefetch = false)?.let(jobsToStart::add)
                }
            }

            // Prefetch [anchor - 1] and [anchor + 1 .. anchor + prefetchAhead] once anchor is ready.
            if (anchorPage in loadedPages) {
                if (startIdx < index) {
                    scheduleLocked(pageNumbers[startIdx], force = false, prefetch = true)?.let(jobsToStart::add)
                }
                for (aheadIdx in (index + 1) until endExclusive) {
                    scheduleLocked(pageNumbers[aheadIdx], force = false, prefetch = true)?.let(jobsToStart::add)
                }
            }

            enforceOffscreenBudgetLocked(index)
            emitSnapshotLocked()
        }

        jobsToCancel.forEach { it.cancel() }
        jobsToStart.forEach { it.start() }
    }

    /**
     * Retries loading [pageNumber] immediately with `forceReload = true`.
     */
    fun retry(pageNumber: Int) {
        val jobToStart: Job?
        val previousJob: Job?
        synchronized(stateLock) {
            if (disposed || pageNumbers.binarySearch(pageNumber) < 0) return
            previousJob = activeJobs.remove(pageNumber)
            loadedPages.remove(pageNumber)
            pageErrors.remove(pageNumber)
            evictedInCurrentAnchor.remove(pageNumber)
            jobToStart = scheduleLocked(pageNumber, force = true, prefetch = false)
            emitSnapshotLocked()
        }
        previousJob?.cancel()
        jobToStart?.start()
    }

    /**
     * Cancels all pending loads and releases strong references (without calling `Bitmap.recycle()`).
     */
    fun close() {
        val toCancel: List<Job>
        synchronized(stateLock) {
            if (disposed) return
            disposed = true
            toCancel = activeJobs.values.toList()
            activeJobs.clear()
            loadedPages.clear()
            pageErrors.clear()
            evictedInCurrentAnchor.clear()
            lastOffscreenBytes = 0L
            emitSnapshotLocked()
        }
        toCancel.forEach { it.cancel() }
    }

    private fun scheduleLocked(number: Int, force: Boolean, prefetch: Boolean): Job? {
        if (disposed) return null
        if (activeJobs[number]?.isActive == true) return null
        if (!force && (number in loadedPages || number in pageErrors || (prefetch && number in evictedInCurrentAnchor))) {
            return null
        }
        pageErrors.remove(number)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = permits.withPermit { loader(number, force) }
                ensureActive()
                val ratio = aspectRatioOf(result)?.takeIf { it.isFinite() && it > 0f }
                synchronized(stateLock) {
                    if (disposed) return@launch
                    if (ratio != null) sharedRatios[number] = ratio
                    loadedPages[number] = result
                    evictedInCurrentAnchor.remove(number)
                    pageErrors.remove(number)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                synchronized(stateLock) {
                    if (!disposed) {
                        pageErrors[number] = error.message?.takeIf(String::isNotBlank) ?: "图页加载失败"
                    }
                }
            } finally {
                val shouldReevaluate = synchronized(stateLock) {
                    if (activeJobs[number] === coroutineContext[Job]) {
                        activeJobs.remove(number)
                    }
                    !disposed
                }
                if (shouldReevaluate) {
                    val (currentAnchor, currentVisible) = synchronized(stateLock) { anchorPage to visiblePages }
                    updateWindow(currentAnchor, currentVisible)
                }
            }
        }
        activeJobs[number] = job
        return job
    }

    private fun enforceOffscreenBudgetLocked(anchorIndex: Int) {
        val offscreenSortedByDistance = loadedPages.keys
            .filter { it !in visiblePages && it != anchorPage }
            .sortedWith(
                compareBy<Int> { page ->
                    val idx = pageNumbers.binarySearch(page)
                    if (idx >= 0) abs(idx - anchorIndex) else Int.MAX_VALUE
                }.thenBy { it },
            )

        var usedBytes = 0L
        for (page in offscreenSortedByDistance) {
            val item = loadedPages[page] ?: continue
            val size = byteSizeOf(item).coerceAtLeast(0L)
            if (usedBytes + size > offscreenBudgetBytes) {
                // Evict reference ONLY — never call Bitmap.recycle().
                loadedPages.remove(page)
                evictedInCurrentAnchor.add(page)
            } else {
                usedBytes += size
            }
        }
        lastOffscreenBytes = usedBytes
    }

    private fun emitSnapshotLocked() {
        _state.value = ReaderBufferState(
            anchorPage = anchorPage,
            visiblePages = visiblePages,
            pages = loadedPages.toMap(),
            errors = pageErrors.toMap(),
            ratios = sharedRatios.toMap(),
            loadingPages = activeJobs.keys.toSet(),
            offscreenBytes = lastOffscreenBytes,
        )
    }

    companion object {
        const val DEFAULT_OFFSCREEN_BUDGET_BYTES: Long = 24L * 1024L * 1024L
        const val PREFETCH_BEHIND: Int = 1
        const val PREFETCH_AHEAD: Int = 3
        const val MAX_PREFETCH_AHEAD: Int = 8
        const val MAX_CONCURRENT_LOADS: Int = 3

        fun forRenderedPages(
            pageNumbers: List<Int>,
            scope: CoroutineScope,
            sharedRatios: MutableMap<Int, Float> = LinkedHashMap(),
            offscreenBudgetBytes: Long = DEFAULT_OFFSCREEN_BUDGET_BYTES,
            prefetchAhead: Int = PREFETCH_AHEAD,
            maxConcurrency: Int = MAX_CONCURRENT_LOADS,
            loader: suspend (pageNumber: Int, forceReload: Boolean) -> RenderedGalleryPage,
        ): ReaderBuffer<RenderedGalleryPage> = ReaderBuffer(
            pageNumbers = pageNumbers,
            scope = scope,
            byteSizeOf = { rendered ->
                val bmp = rendered.bitmap
                maxOf(bmp.allocationByteCount.toLong(), bmp.byteCount.toLong())
            },
            aspectRatioOf = { rendered ->
                val bmp = rendered.bitmap
                if (bmp.width > 0 && bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else null
            },
            offscreenBudgetBytes = offscreenBudgetBytes,
            maxConcurrency = maxConcurrency,
            sharedRatios = sharedRatios,
            prefetchAhead = prefetchAhead,
            loader = loader,
        )
    }
}
