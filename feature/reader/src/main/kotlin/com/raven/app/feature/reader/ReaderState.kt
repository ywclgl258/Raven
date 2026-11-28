package com.raven.app.feature.reader

import com.raven.app.core.media.download.PlaybackQueueSession
import com.raven.app.core.media.reader.PreparedExport
import com.raven.app.core.media.reader.ReaderBufferState
import com.raven.app.core.media.reader.SpreadNavigator
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderPreferences
import com.raven.app.core.model.SiteId

/** 阅读器 UI 状态契约：连续条漫章节、含全部派生属性的 `ReaderUiState` 与一次性 `ReaderEvent`。从 `ReaderViewModel.kt` 拆出，包名与语义不变。 */

enum class SlideshowOrder {
    SEQUENTIAL,
    RANDOM,
}

/**
 * Seamlessly appended serial chapter rendered below the primary chapter in continuous STRIP mode.
 */
data class SerialStripChapter(
    val mediaId: String,
    val title: String,
    val url: String,
    val detail: GalleryDetail,
    val sourceRoot: String,
    val pageNumbers: List<Int>,
    val bufferState: ReaderBufferState<LoadedReaderPage> = ReaderBufferState(),
    val pageAspectRatios: Map<Int, Float> = emptyMap(),
)

/**
 * Immutable UI state for [ReaderScreen].
 */
data class ReaderUiState(
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val offlineOnly: Boolean,
    val siteDisplayName: String = siteId.value,
    val supportsOriginalImages: Boolean = false,
    val isLoading: Boolean = true,
    val detail: GalleryDetail? = null,
    val pageNumbers: List<Int> = emptyList(),
    val totalPages: Int = 0,
    val sourceRoot: String = "",
    val currentPage: Int = 1,
    val stripScrollOffsetPx: Int = 0,
    val pendingStripRestoreOffsetPx: Int? = null,
    val bufferState: ReaderBufferState<LoadedReaderPage> = ReaderBufferState(),
    val pageAspectRatios: Map<Int, Float> = emptyMap(),
    val appendedChapters: List<SerialStripChapter> = emptyList(),
    val isAppendingNextChapter: Boolean = false,
    val appendNextChapterError: String? = null,
    val hasReachedFinalSerialChapter: Boolean = false,
    val activeStripChapterId: String? = null,
    val activeStripChapterTitle: String? = null,
    val activeStripChapterPage: Int? = null,
    val activeStripChapterOffsetPx: Int = 0,
    val activeStripChapterTotalPages: Int? = null,
    val showReaderTutorial: Boolean = false,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val isPerBookOverride: Boolean = false,
    val isLandscapeViewport: Boolean = false,
    val chromeVisible: Boolean = true,
    val showStatusBar: Boolean = false,
    val tapZonesEnabled: Boolean = true,
    val autoSplitWidePages: Boolean = false,
    val activeSheet: ReaderSheet = ReaderSheet.NONE,
    val activeQuickPanel: ReaderQuickPanel = ReaderQuickPanel.NONE,
    val autoPageIntervalSeconds: Int = 0,
    val slideshowOrder: SlideshowOrder = SlideshowOrder.SEQUENTIAL,
    val pageBookmarks: List<PageBookmark> = emptyList(),
    val imageFavoritePages: Set<Int> = emptySet(),
    val thumbnailPageBookmarks: List<PageBookmark> = emptyList(),
    val queueSession: PlaybackQueueSession? = null,
    val showQueueNextCard: Boolean = false,
    val previousChapter: MediaItem? = null,
    val nextChapter: MediaItem? = null,
    val chapters: List<MediaItem> = emptyList(),
    val isChapterListReady: Boolean = false,
    val isChapterListLoading: Boolean = false,
    val chapterListError: String? = null,
    val isChapterTransitionLoading: Boolean = false,
    val isExporting: Boolean = false,
    val exportStatusText: String? = null,
    val authRequired: Boolean = false,
    val authMessage: String? = null,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
) {
    val isSerialComic: Boolean
        get() = !offlineOnly && !detail?.chaptersUrl.isNullOrBlank()

    val displayTitle: String
        get() = if (preferences.mode == ReaderMode.STRIP && !activeStripChapterTitle.isNullOrBlank()) {
            activeStripChapterTitle
        } else {
            detail?.item?.title ?: siteDisplayName
        }

    val displayCurrentPage: Int
        get() = if (preferences.mode == ReaderMode.STRIP && activeStripChapterPage != null) {
            activeStripChapterPage
        } else {
            currentPage
        }

    val displayTotalPages: Int
        get() = if (preferences.mode == ReaderMode.STRIP && activeStripChapterTotalPages != null) {
            activeStripChapterTotalPages
        } else {
            totalPages
        }

    val effectiveSpread: Int
        get() = if (preferences.mode == ReaderMode.SPREAD && isLandscapeViewport) 2 else 1

    val spreadNavigator: SpreadNavigator?
        get() = if (pageNumbers.isEmpty()) {
            null
        } else {
            SpreadNavigator(
                pages = pageNumbers,
                spread = effectiveSpread,
                combineCover = preferences.combineCover,
            )
        }

    val currentSheetPages: List<Int>
        get() = spreadNavigator?.sheetFor(currentPage) ?: listOf(currentPage)

    val displayMediaId: String
        get() = if (preferences.mode == ReaderMode.STRIP) activeStripChapterId ?: mediaId else mediaId

    val isCurrentPageBookmarked: Boolean
        get() = pageBookmarks.any { it.mediaId == displayMediaId && it.page == displayCurrentPage }

    val isCurrentPageImageFavorited: Boolean
        get() = displayCurrentPage in imageFavoritePages

    val isCurrentPageRenderReady: Boolean
        get() {
            if (pageNumbers.isEmpty()) return false
            val targetPages = if (preferences.mode == ReaderMode.SPREAD) currentSheetPages else listOf(currentPage)
            return targetPages.all { p -> p in bufferState.pages && p !in bufferState.errors }
        }

    val progressPercent: Int
        get() = if (displayTotalPages <= 0) 0 else (displayCurrentPage * 100L / displayTotalPages).coerceIn(0L, 100L).toInt()

    /** Effective window brightness (`-1f` = follow system, `0f..1f` = explicit override). */
    val effectiveBrightnessFloat: Float
        get() = if (preferences.brightnessPercent < 0) {
            -1f
        } else {
            (preferences.brightnessPercent.coerceIn(0, 100) / 100f).coerceIn(0.01f, 1f)
        }
}

/**
 * One-shot side effects emitted by [ReaderViewModel] for programmatic scrolls, SAF export,
 * image sharing, sequential queue transitions, and serial chapter advancement.
 */
sealed interface ReaderEvent {
    data class ScrollToPage(
        val page: Int,
        val scrollOffsetPx: Int = 0,
        val chapterId: String? = null,
    ) : ReaderEvent

    data class RequestSaveDocument(
        val prepared: PreparedExport,
        val suggestedFileName: String,
        val mimeType: String,
    ) : ReaderEvent

    data class RequestOpenDocumentTree(
        val prepared: PreparedExport,
    ) : ReaderEvent

    data class RequestShareExport(
        val prepared: PreparedExport,
        val subject: String,
    ) : ReaderEvent

    data class OpenNextQueueItem(
        val previous: PlaybackQueueSession,
        val replacement: PlaybackQueueSession,
        val nextItem: MediaItem,
    ) : ReaderEvent

    data class OpenChapter(
        val nextItem: MediaItem,
        val parent: RavenRoute.ReaderParent? = null,
    ) : ReaderEvent
}
