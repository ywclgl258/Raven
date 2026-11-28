package com.raven.app.feature.reader

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.model.ReaderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Auto-page / slideshow loop: waits for the current page to be render-ready, advances sequentially
 * or randomly, appends the next serial chapter seamlessly in STRIP mode, and stops at the queue end.
 *
 * Extracted from [ReaderViewModel]; the ViewModel keeps the public setters as delegates and passes
 * the target chapter/jump/notice callbacks in.
 */
internal class ReaderAutoPageLoop(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val state: StateFlow<ReaderUiState>,
    private val update: ((ReaderUiState) -> ReaderUiState) -> Unit,
    private val random: Random,
    private val isUserInteracting: () -> Boolean,
    private val jumpToPage: (Int) -> Unit,
    private val jumpToChapterPage: (chapterId: String, page: Int) -> Unit = { _, page -> jumpToPage(page) },
    private val preloadNextChapter: () -> Unit,
    private val requestNextChapterAtBoundary: () -> Boolean,
    private val notice: (String) -> Unit,
) {
    private var job: Job? = null
    private var pausedByLifecycle: Boolean = false

    // ---- Auto-Page & Slideshow (Waits for Current Page Render Ready) ----

    fun setInterval(seconds: Int) {
        val sanitized = if (seconds in ReaderViewModel.ALLOWED_AUTO_PAGE_INTERVALS) seconds else 0
        update { it.copy(autoPageIntervalSeconds = sanitized) }
        restart()
    }

    fun setPausedByLifecycle(paused: Boolean) {
        if (pausedByLifecycle == paused) return
        pausedByLifecycle = paused
        if (paused) {
            job?.cancel()
            job = null
        } else if (state.value.autoPageIntervalSeconds > 0) {
            restart()
        }
    }

    fun restart() {
        job?.cancel()
        val intervalSeconds = state.value.autoPageIntervalSeconds
        if (intervalSeconds <= 0 || pausedByLifecycle) return

        job = scope.launch(dispatchers.main) {
            val visitedRandom = LinkedHashSet<Int>()
            while (isActive) {
                val currentInterval = state.value.autoPageIntervalSeconds
                if (currentInterval <= 0) break

                // Strictly wait until the current page is render-ready and no sheet is blocking the view.
                state.first { state ->
                    state.autoPageIntervalSeconds > 0 &&
                        !state.isLoading &&
                        state.activeSheet == ReaderSheet.NONE &&
                        state.isCurrentPageRenderReady
                }

                delay(currentInterval * 1_000L)

                val latest = state.value
                if (latest.autoPageIntervalSeconds <= 0) break
                if (latest.activeSheet != ReaderSheet.NONE || isUserInteracting() || !latest.isCurrentPageRenderReady) {
                    continue
                }

                val pages = latest.pageNumbers
                // 条漫连读时章末应无缝追加下一话并逐话顺延翻页，避免每轮空转重复触发 preloadNextChapter。
                val continuousStripSequential = latest.isSerialComic &&
                    latest.preferences.mode == ReaderMode.STRIP &&
                    latest.slideshowOrder == SlideshowOrder.SEQUENTIAL
                if (continuousStripSequential) {
                    val orderedChapters = buildList {
                        add(latest.mediaId to latest.pageNumbers)
                        latest.appendedChapters.forEach { add(it.mediaId to it.pageNumbers) }
                    }
                    val activeId = latest.activeStripChapterId
                    val curIdx = if (activeId != null) {
                        orderedChapters.indexOfFirst { it.first == activeId }.takeIf { it >= 0 } ?: 0
                    } else {
                        0
                    }
                    val (curChapId, curPages) = orderedChapters[curIdx]
                    val curPage = if (curIdx == 0) {
                        latest.currentPage
                    } else {
                        latest.activeStripChapterPage ?: curPages.firstOrNull() ?: 1
                    }
                    val nextInCurrent = curPages.firstOrNull { it > curPage }
                    if (nextInCurrent != null) {
                        jumpToChapterPage(curChapId, nextInCurrent)
                        continue
                    }
                    val nextAppended = orderedChapters.getOrNull(curIdx + 1)
                    val firstOfNext = nextAppended?.second?.firstOrNull()
                    if (nextAppended != null && firstOfNext != null) {
                        jumpToChapterPage(nextAppended.first, firstOfNext)
                        continue
                    }
                    if (latest.hasReachedFinalSerialChapter) {
                        update {
                            it.copy(
                                autoPageIntervalSeconds = 0,
                                showQueueNextCard = it.queueSession?.hasNext == true,
                            )
                        }
                        notice("自动翻页已到达末尾")
                        break
                    }
                    if (latest.appendNextChapterError != null && !latest.isAppendingNextChapter) {
                        update { it.copy(autoPageIntervalSeconds = 0) }
                        notice("下一话加载失败，自动翻页已暂停")
                        break
                    }
                    val prevCount = latest.appendedChapters.size
                    preloadNextChapter()
                    state.first { s ->
                        s.autoPageIntervalSeconds <= 0 ||
                            s.appendedChapters.size > prevCount ||
                            s.hasReachedFinalSerialChapter ||
                            (!s.isAppendingNextChapter && s.appendNextChapterError != null)
                    }
                    continue
                }

                if (pages.size <= 1) {
                    val opensNextChapter = latest.slideshowOrder == SlideshowOrder.SEQUENTIAL &&
                        latest.queueSession?.hasNext != true &&
                        requestNextChapterAtBoundary()
                    update { it.copy(autoPageIntervalSeconds = 0) }
                    if (!opensNextChapter) notice("自动翻页已到达末尾")
                    break
                }

                val nextPage: Int? = when (latest.slideshowOrder) {
                    SlideshowOrder.SEQUENTIAL -> {
                        latest.spreadNavigator?.adjacentPage(latest.currentPage, forward = true)
                    }
                    SlideshowOrder.RANDOM -> {
                        visitedRandom.add(latest.currentPage)
                        val remaining = pages.filter { it !in visitedRandom }
                        val pool = remaining.ifEmpty {
                            visitedRandom.clear()
                            visitedRandom.add(latest.currentPage)
                            pages.filter { it != latest.currentPage }
                        }
                        pool.takeIf { it.isNotEmpty() }?.let { list ->
                            list[random.nextInt(list.size)]
                        }
                    }
                }

                if (nextPage != null) {
                    jumpToPage(nextPage)
                } else {
                    val opensNextChapter = latest.slideshowOrder == SlideshowOrder.SEQUENTIAL &&
                        latest.queueSession?.hasNext != true &&
                        requestNextChapterAtBoundary()
                    update {
                        it.copy(
                            autoPageIntervalSeconds = 0,
                            showQueueNextCard = it.queueSession?.hasNext == true,
                        )
                    }
                    if (!opensNextChapter) notice("自动翻页已到达末尾")
                    break
                }
            }
        }
    }

    /** Cancels any running loop (dispose / gallery switch). */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
