package com.raven.app.feature.reader

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.media.reader.ReaderBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the per-appended-chapter [ReaderBuffer]s used by the continuous STRIP reader: decode windows,
 * bitmap budgets, retry state and the ratio cache shared with the prefetcher.
 *
 * Extracted from [ReaderViewModel] so the buffer lifecycle (attach / reattach after trim / retry /
 * close) lives in one place; the ViewModel keeps the chapter prefetch state machine.
 */
internal class ReaderAppendedChapterCache(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val pageLoader: ReaderPageLoader,
    private val uiState: () -> ReaderUiState,
    private val update: ((ReaderUiState) -> ReaderUiState) -> Unit,
    private val memoryBudgetBytes: () -> Long,
    private val maxConcurrency: () -> Int,
    private val prefetchAhead: () -> Int,
) {
    private val buffers = ConcurrentHashMap<String, ReaderBuffer<LoadedReaderPage>>()
    private val collectJobs = ConcurrentHashMap<String, Job>()
    private val ratios = ConcurrentHashMap<String, MutableMap<Int, Float>>()
    private val failedChapterIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun bufferFor(chapterId: String): ReaderBuffer<LoadedReaderPage>? = buffers[chapterId]

    /** Decode-window ratio map for a chapter; created on demand and shared with the prefetcher. */
    fun ratiosFor(chapterId: String): MutableMap<Int, Float> =
        ratios.getOrPut(chapterId) { ConcurrentHashMap() }

    fun isFailed(chapterId: String): Boolean = chapterId in failedChapterIds

    fun markFailed(chapterId: String) {
        failedChapterIds.add(chapterId)
    }

    fun clearFailed(chapterId: String) {
        failedChapterIds.remove(chapterId)
    }

    /** (Re)creates the buffer for an appended chapter and mirrors its window into the UI state. */
    fun attach(chapter: SerialStripChapter, preferOriginal: Boolean) {
        val chapterId = chapter.mediaId
        collectJobs.remove(chapterId)?.cancel()
        buffers.remove(chapterId)?.close()
        val ratioMap = ratiosFor(chapterId)
        val perChapterBudget = (memoryBudgetBytes() / 2L).coerceAtLeast(16L * 1024L * 1024L)
        val buffer = ReaderBuffer<LoadedReaderPage>(
            pageNumbers = chapter.pageNumbers,
            scope = scope,
            byteSizeOf = { it.byteSize },
            aspectRatioOf = { it.aspectRatio },
            offscreenBudgetBytes = perChapterBudget,
            maxConcurrency = maxConcurrency(),
            sharedRatios = ratioMap,
            prefetchAhead = prefetchAhead().coerceAtLeast(3),
            loader = { pageNumber, forceReload ->
                pageLoader.loadRenderedPage(
                    detail = chapter.detail,
                    pageIndex = pageNumber,
                    preferOriginal = preferOriginal,
                    forceReload = forceReload,
                    offlineOnly = false,
                    sourceRoot = chapter.sourceRoot,
                )
            },
        )
        buffers[chapterId] = buffer
        val collectJob = scope.launch(dispatchers.main) {
            buffer.state.collectLatest { bufState ->
                update { state ->
                    val idx = state.appendedChapters.indexOfFirst { it.mediaId == chapterId }
                    if (idx < 0) return@update state
                    val updated = state.appendedChapters.toMutableList()
                    val current = updated[idx]
                    updated[idx] = current.copy(
                        bufferState = bufState,
                        pageAspectRatios = if (bufState.ratios.isEmpty()) ratioMap.toMap() else bufState.ratios,
                    )
                    state.copy(appendedChapters = updated)
                }
            }
        }
        collectJobs[chapterId] = collectJob
        val firstPage = chapter.pageNumbers.firstOrNull() ?: 1
        buffer.updateWindow(page = firstPage, visible = setOf(firstPage))
    }

    /** Retries a page when the chapter still has a live buffer. Returns false when it must be reattached. */
    fun retry(chapterId: String, pageNumber: Int): Boolean {
        val existing = buffers[chapterId] ?: return false
        existing.retry(pageNumber)
        return true
    }

    fun updateWindow(chapterId: String, page: Int, visible: Set<Int>) {
        buffers[chapterId]?.updateWindow(page = page, visible = visible)
    }

    /** Trims decoded buffers outside [keepRadius] of [activeChapterId] while keeping cached aspect ratios. */
    fun trimAround(
        activeChapterId: String?,
        orderedChapterIds: List<String>,
        keepRadius: Int = 1,
    ) {
        if (orderedChapterIds.isEmpty()) return
        val keepIds = if (activeChapterId == null) {
            orderedChapterIds.take((keepRadius + 1).coerceAtLeast(1)).toSet()
        } else {
            val activeIdx = orderedChapterIds.indexOf(activeChapterId)
            if (activeIdx < 0) {
                orderedChapterIds.take((keepRadius + 1).coerceAtLeast(1)).toSet()
            } else {
                val from = (activeIdx - keepRadius).coerceAtLeast(0)
                val to = (activeIdx + keepRadius).coerceAtMost(orderedChapterIds.lastIndex)
                orderedChapterIds.subList(from, to + 1).toSet()
            }
        }
        val toEvict = buffers.keys.filter { it !in keepIds }
        for (chapterId in toEvict) {
            collectJobs.remove(chapterId)?.cancel()
            buffers.remove(chapterId)?.close()
        }
    }

    /** Reattaches currently live appended chapter buffers (e.g. after budget/prefetch/concurrency changes). */
    fun reattachLiveBuffers(chapters: List<SerialStripChapter>, preferOriginal: Boolean) {
        val byId = chapters.associateBy { it.mediaId }
        val activeIds = buffers.keys.toList()
        for (chapterId in activeIds) {
            val chapter = byId[chapterId] ?: continue
            attach(chapter = chapter, preferOriginal = preferOriginal)
        }
    }

    /** Cancels collectors and closes every buffer; safe to call from `onCleared`. */
    fun closeAll() {
        collectJobs.values.forEach { it.cancel() }
        collectJobs.clear()
        buffers.values.forEach { it.close() }
        buffers.clear()
        ratios.clear()
        failedChapterIds.clear()
    }
}
