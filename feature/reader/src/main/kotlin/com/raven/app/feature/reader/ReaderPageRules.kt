package com.raven.app.feature.reader

import com.raven.app.core.media.reader.ReaderBufferState
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderStartPosition
import com.raven.app.core.model.ReadingProgress

/**
 * Page-resolution rules shared by the reader ViewModel: which page to open at, the nearest
 * available page, and which chapter/context currently owns the viewport in continuous STRIP mode.
 *
 * Extracted as pure functions so they can be unit-tested without a ViewModel.
 */

internal data class ActivePageContext(
        val mediaId: String,
        val url: String,
        val title: String,
        val coverUrl: String,
        val detail: GalleryDetail,
        val page: Int,
        val totalPages: Int,
        val bufferState: ReaderBufferState<LoadedReaderPage>,
    )

internal fun resolveActivePageContext(
    state: ReaderUiState,
    fallbackDetail: GalleryDetail,
    mediaId: String,
): ActivePageContext {
        val chapterId = state.activeStripChapterId
        val chapter = if (state.preferences.mode == ReaderMode.STRIP && chapterId != null) {
            state.appendedChapters.firstOrNull { it.mediaId == chapterId }
        } else {
            null
        }
        return if (chapter != null) {
            ActivePageContext(
                mediaId = chapter.mediaId,
                url = chapter.url,
                title = chapter.title,
                coverUrl = chapter.detail.item.coverUrl,
                detail = chapter.detail,
                page = state.activeStripChapterPage ?: state.currentPage,
                totalPages = chapter.pageNumbers.size.coerceAtLeast(1),
                bufferState = chapter.bufferState,
            )
        } else {
            ActivePageContext(
                mediaId = mediaId,
                url = fallbackDetail.item.url,
                title = fallbackDetail.item.title,
                coverUrl = fallbackDetail.item.coverUrl,
                detail = fallbackDetail,
                page = state.currentPage,
                totalPages = state.totalPages.coerceAtLeast(1),
                bufferState = state.bufferState,
            )
        }
    }

internal fun nearestPageIn(pages: List<Int>, target: Int): Int {
        if (pages.isEmpty()) return 1
        val idx = pages.binarySearch(target)
        if (idx >= 0) return pages[idx]
        val insert = (-idx - 1).coerceIn(0, pages.lastIndex)
        return pages[insert]
    }

internal fun resolveInitialPage(
        requestedStartPage: Int,
        pageNumbers: List<Int>,
        startPosition: ReaderStartPosition,
        savedProgress: ReadingProgress?,
    ): Int {
        if (pageNumbers.isEmpty()) return 1
        val target = when {
            requestedStartPage > 1 -> requestedStartPage
            startPosition == ReaderStartPosition.LAST -> pageNumbers.last()
            startPosition == ReaderStartPosition.FIRST -> pageNumbers.first()
            savedProgress != null && !savedProgress.completed && savedProgress.currentPage in pageNumbers ->
                savedProgress.currentPage
            else -> pageNumbers.first()
        }
        return nearestPageIn(pageNumbers, target)
    }
