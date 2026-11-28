package com.raven.app.feature.reader

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Direction of a serial-chapter step; `null` means an explicit chapter was requested. */
internal enum class SerialChapterDirection {
    PREVIOUS,
    NEXT,
}

/**
 * Serial chapter list + next-chapter prefetch for the continuous STRIP reader.
 *
 * Owns the chapter-list job, the "adjacent chapters are prepared" flag and the append job that
 * loads the next chapter's detail and hands it to [ReaderAppendedChapterCache]. The ViewModel keeps
 * the navigation decision (`requestChapter`) and the reading-progress pipeline.
 */
internal class ReaderChapterPrefetcher(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val siteId: SiteId,
    private val mediaId: String,
    private val offlineOnly: Boolean,
    private val isInvalidSiteId: Boolean,
    private val uiState: () -> ReaderUiState,
    private val update: ((ReaderUiState) -> ReaderUiState) -> Unit,
    private val serialChapterLoader: SerialChapterLoader,
    private val pageLoader: ReaderPageLoader,
    private val chapters: ReaderAppendedChapterCache,
    private val supportsOriginalImages: () -> Boolean,
) {
    private var chapterJob: Job? = null
    private var appendJob: Job? = null
    private var preparedNext: MediaItem? = null
    private var preparedPrevious: MediaItem? = null
    private var prepared = false

    /** Drops prepared neighbours and cancels the chapter list (new gallery loaded). */
    fun reset() {
        chapterJob?.cancel()
        prepared = false
        preparedNext = null
        preparedPrevious = null
    }

    /** Cancels in-flight chapter work; safe from `onCleared`. */
    fun cancelAll() {
        chapterJob?.cancel()
        appendJob?.cancel()
    }

    fun prepared(direction: SerialChapterDirection?): MediaItem? = when (direction) {
        SerialChapterDirection.PREVIOUS -> preparedPrevious
        SerialChapterDirection.NEXT -> preparedNext
        null -> null
    }

    /** Joins an in-flight chapter list load before reading the prepared neighbours. */
    suspend fun awaitList() {
        chapterJob?.join()
    }

    /** Loads the chapter list when it is not prepared yet (used right before a chapter step). */
    suspend fun ensurePrepared(detail: GalleryDetail) {
        if (prepared) return
        update { it.copy(isChapterListLoading = true, chapterListError = null) }
        cacheAdjacentChapters(serialChapterLoader.loadChapters(siteId, detail), detail)
    }

    /** Rebuilds the chapter list from scratch. */
    fun refresh(detail: GalleryDetail) {
        chapterJob?.cancel()
        prepared = false
        prepare(detail)
    }

    fun prepare(detail: GalleryDetail) {
        if (offlineOnly || detail.chaptersUrl.isNullOrBlank()) return
        chapterJob = scope.launch(dispatchers.io) {
            update {
                it.copy(isChapterListLoading = true, chapterListError = null)
            }
            try {
                cacheAdjacentChapters(serialChapterLoader.loadChapters(siteId, detail), detail)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                update {
                    it.copy(
                        isChapterListLoading = false,
                        chapterListError = error.message?.takeIf(String::isNotBlank) ?: "章节加载失败，请重试",
                    )
                }
            }
        }
    }

    private fun cacheAdjacentChapters(
        chapters: List<MediaItem>,
        detail: GalleryDetail,
    ) {
        val hints = listOfNotNull(
            detail.item.extra["chapterId"],
            detail.item.extra["chapterUuid"],
            detail.item.extra["chapterHid"],
        )
        val chapterUrlHint = detail.item.extra["chapterUrl"]?.trim().orEmpty()
        val currentIndex = chapters.indexOfFirst { chapter ->
            chapter.id == mediaId ||
                chapter.id == detail.item.id ||
                (chapterUrlHint.isNotEmpty() && chapter.url == chapterUrlHint) ||
                hints.any { hint ->
                    hint.isNotBlank() && (chapter.id == hint || chapter.id.endsWith(":$hint"))
                }
        }
        val previous = chapters.getOrNull(currentIndex - 1)?.takeIf { currentIndex > 0 && it.url.isNotBlank() }
        val next = chapters.getOrNull(currentIndex + 1)?.takeIf { currentIndex >= 0 && it.url.isNotBlank() }
        preparedPrevious = previous
                preparedNext = next
        prepared = true
        update {
            it.copy(
                previousChapter = previous,
                nextChapter = next,
                chapters = chapters,
                isChapterListReady = true,
                isChapterListLoading = false,
                chapterListError = null,
                hasReachedFinalSerialChapter = next == null && it.chapters.isEmpty(),
            )
        }
    }

    /**
     * Seamlessly loads the next serial chapter and appends it to the continuous STRIP stream
     * without reloading or interrupting the active reader viewport.
     */
    fun preloadNextSerialChapterIfNeeded(forceRetry: Boolean = false) {
        val state = uiState()
        if (offlineOnly || !state.isSerialComic || state.preferences.mode != ReaderMode.STRIP || isInvalidSiteId) return
        if (appendJob?.isActive == true) return
        if (!forceRetry && state.hasReachedFinalSerialChapter) return

        appendJob = scope.launch(dispatchers.io) {
            try {
                chapterJob?.join()
                val baseDetail = uiState().detail ?: return@launch
                if (!prepared) {
                    update { it.copy(isChapterListLoading = true, chapterListError = null) }
                    cacheAdjacentChapters(serialChapterLoader.loadChapters(siteId, baseDetail), baseDetail)
                }
                val chapterList = uiState().chapters
                if (chapterList.isEmpty()) return@launch

                val lastChapter = uiState().appendedChapters.lastOrNull()
                val tailMediaId = lastChapter?.mediaId ?: mediaId
                val tailDetail = lastChapter?.detail ?: baseDetail
                val tailHints = listOfNotNull(
                    tailDetail.item.id,
                    tailDetail.item.extra["chapterId"],
                    tailDetail.item.extra["chapterUuid"],
                    tailDetail.item.extra["chapterHid"],
                )
                val tailUrlHint = tailDetail.item.extra["chapterUrl"]?.trim().orEmpty()
                val tailIndex = chapterList.indexOfFirst { chapter ->
                    isMatchingSerialChapter(
                        chapter = chapter,
                        currentChapterId = tailMediaId,
                        currentChapterUrl = tailUrlHint,
                        hints = tailHints,
                    )
                }
                val nextChapterItem = chapterList.getOrNull(tailIndex + 1)
                    ?.takeIf { tailIndex >= 0 && it.url.isNotBlank() }

                if (nextChapterItem == null) {
                    update {
                        it.copy(
                            isAppendingNextChapter = false,
                            appendNextChapterError = null,
                            hasReachedFinalSerialChapter = true,
                        )
                    }
                    return@launch
                }

                if (!forceRetry && chapters.isFailed(nextChapterItem.id)) return@launch
                val alreadyLoaded = nextChapterItem.id == mediaId ||
                    uiState().appendedChapters.any { it.mediaId == nextChapterItem.id }
                if (alreadyLoaded) return@launch

                update {
                    it.copy(
                        isAppendingNextChapter = true,
                        appendNextChapterError = null,
                    )
                }

                val snapshot = pageLoader.loadDetail(
                    siteId = siteId,
                    mediaId = nextChapterItem.id,
                    url = nextChapterItem.url,
                    offlineOnly = false,
                )
                val nextDetail = snapshot.detail
                val resolvedMediaId = nextDetail.item.id.ifBlank { nextChapterItem.id }
                val total = maxOf(nextDetail.pageCount, nextDetail.pages.size).coerceAtLeast(0)
                val nextPages = if (nextDetail.pages.isNotEmpty()) {
                    nextDetail.pages.map { it.index }.distinct().sorted()
                } else {
                    (1..total).toList()
                }
                if (nextPages.isEmpty()) {
                    throw IllegalStateException("下一话暂无可加载图片")
                }

                val ratioMap = ConcurrentHashMap<Int, Float>()
                for (page in nextDetail.pages) {
                    val w = page.width
                    val h = page.height
                    if (w != null && h != null && w > 0 && h > 0) {
                        ratioMap.putIfAbsent(page.index, w.toFloat() / h.toFloat())
                    }
                }
                chapters.ratiosFor(resolvedMediaId).putAll(ratioMap)
                chapters.clearFailed(nextChapterItem.id)
                chapters.clearFailed(resolvedMediaId)

                val preferOriginal = supportsOriginalImages() && uiState().preferences.preferOriginalImages
                val chapterTitle = nextChapterItem.title.ifBlank { nextDetail.item.title }
                val newChapter = SerialStripChapter(
                    mediaId = resolvedMediaId,
                    title = chapterTitle,
                    url = nextChapterItem.url,
                    detail = nextDetail,
                    sourceRoot = snapshot.sourceRoot,
                    pageNumbers = nextPages,
                    pageAspectRatios = ratioMap.toMap(),
                )

                val hasAnotherAfter = chapterList.getOrNull(tailIndex + 2)?.url?.isNotBlank() == true
                update { state ->
                    if (state.appendedChapters.any { it.mediaId == resolvedMediaId }) {
                        state.copy(isAppendingNextChapter = false, appendNextChapterError = null)
                    } else {
                        state.copy(
                            appendedChapters = state.appendedChapters + newChapter,
                            isAppendingNextChapter = false,
                            appendNextChapterError = null,
                            hasReachedFinalSerialChapter = !hasAnotherAfter,
                        )
                    }
                }

                chapters.attach(
                    chapter = newChapter,
                    preferOriginal = preferOriginal,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                val tailId = uiState().chapters.let { list ->
                    val lastId = uiState().appendedChapters.lastOrNull()?.mediaId ?: mediaId
                    val idx = list.indexOfFirst { it.id == lastId }
                    list.getOrNull(idx + 1)?.id
                }
                if (!tailId.isNullOrBlank()) {
                    chapters.markFailed(tailId)
                }
                update {
                    it.copy(
                        isAppendingNextChapter = false,
                        appendNextChapterError = error.message?.takeIf(String::isNotBlank) ?: "下一话加载失败，点击重试",
                    )
                }
            }
        }
    }


}
