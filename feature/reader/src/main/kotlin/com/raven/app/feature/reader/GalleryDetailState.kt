package com.raven.app.feature.reader

import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.media.reader.PreparedExport
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.workId
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef

/** 图集详情 UI 状态契约：下载 Sheet、标签操作、续读目标、`GalleryDetailUiState` 与 `GalleryDetailEvent`。从 `GalleryDetailViewModel.kt` 拆出。 */

data class DownloadSheetState(
    val visible: Boolean = false,
    val downloadAll: Boolean = true,
    val rangeInput: String = "",
    val parsedPages: List<Int> = emptyList(),
    val validationError: String? = null,
    val isSubmitting: Boolean = false,
)

/**
 * Target tag inspected via long-press inside [GalleryDetailScreen].
 */
data class TagActionState(
    val tag: TagRef,
    val translatedLabel: String,
    val isFavorited: Boolean,
    val isSubscribed: Boolean,
    val isBlocked: Boolean,
)

/** Concrete resume destination: which chapter to open, at which page. */
data class GalleryResumeTarget(
    val item: MediaItem,
    val startPage: Int,
    val completed: Boolean,
    /** True when the target chapter differs from the page currently on screen. */
    val isOtherChapter: Boolean,
)

/**
 * Immutable UI state for [GalleryDetailScreen].
 */
data class GalleryDetailUiState(
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val siteDescriptor: SiteDescriptor? = null,
    val siteDisplayName: String = siteId.value,
    val isLocal: Boolean = siteId.isLocal,
    val isLoading: Boolean = true,
    val detail: GalleryDetail? = null,
    val downloadedPages: List<Int> = emptyList(),
    val isOfflineComplete: Boolean = false,
    val sourceRoot: String = "",
    val localSizeLabel: String? = null,
    val readingProgress: ReadingProgress? = null,
    /** Reactive site-wide reading history keyed by media/chapter id (one row per work). */
    val readingProgressByMediaId: Map<String, ReadingProgress> = emptyMap(),
    /** Work-level id for serial comics (`comicId`); null for one-shot works. */
    val workSeriesId: String? = null,
    val isFavorite: Boolean = false,
    val isLiked: Boolean = false,
    val isInWatchLater: Boolean = false,
    val showDetailTutorial: Boolean = false,
    val shelves: List<ShelfWithSummary> = emptyList(),
    val containingShelfIds: Set<String> = emptySet(),
    val showShelfPicker: Boolean = false,
    val pageBookmarks: List<PageBookmark> = emptyList(),
    val activeDownload: DownloadItem? = null,
    val translateTags: Boolean = true,
    val blockTagsGlobally: Boolean = true,
    val tagDictionaryRevision: Long = 0L,
    val filteredRelated: List<MediaItem> = emptyList(),
    val chapters: List<MediaItem> = emptyList(),
    val chaptersLoading: Boolean = false,
    val chaptersError: String? = null,
    val favoritedTagSlugs: Set<String> = emptySet(),
    val subscribedTagSlugs: Set<String> = emptySet(),
    val isSeriesFollowed: Boolean = false,
    val seriesFollowSupported: Boolean = false,
    val seriesFollowInProgress: Boolean = false,
    val blockedTerms: Set<String> = emptySet(),
    val downloadSheet: DownloadSheetState = DownloadSheetState(),
    val tagAction: TagActionState? = null,
    /** Author inspected via long-press; shown in the author action sheet. */
    val authorAction: AuthorRef? = null,
    val isExporting: Boolean = false,
    val exportStatusText: String? = null,
    val authRequired: Boolean = false,
    val authMessage: String? = null,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
) {
    val hasResumeProgress: Boolean
        get() = resumeTarget?.completed == false

    /**
     * Where "继续阅读" must land. Inside one serial work the reader can hop chapters, so the newest
     * position of the whole work wins — resuming the chapter this page belongs to would rewind to
     * wherever that chapter was left before the jump.
     */
    val resumeTarget: GalleryResumeTarget?
        get() {
            val currentDetail = detail ?: return null
            val seriesId = workSeriesId?.takeIf(String::isNotBlank)
            if (seriesId == null) {
                val progress = readingProgress ?: return null
                val total = (currentDetail.pageCount.takeIf { it > 0 } ?: progress.totalPages).coerceAtLeast(1)
                if (progress.currentPage <= 0) return null
                return GalleryResumeTarget(
                    item = currentDetail.item,
                    startPage = if (progress.completed) 1 else progress.currentPage.coerceIn(1, total),
                    completed = progress.completed,
                    isOtherChapter = false,
                )
            }
            val latest = workResumeProgress ?: return null
            if (!latest.completed && latest.currentPage > 0) {
                val item = chapterItemFor(latest, currentDetail)
                val total = latest.totalPages.coerceAtLeast(latest.currentPage).coerceAtLeast(1)
                return GalleryResumeTarget(
                    item = item,
                    startPage = latest.currentPage.coerceIn(1, total),
                    completed = false,
                    isOtherChapter = item.id != currentDetail.item.id,
                )
            }
            nextUnreadChapter?.let { chapter ->
                return GalleryResumeTarget(
                    item = chapter,
                    startPage = 1,
                    completed = false,
                    isOtherChapter = chapter.id != currentDetail.item.id,
                )
            }
            val item = chapterItemFor(latest, currentDetail)
            return GalleryResumeTarget(
                item = item,
                startPage = 1,
                completed = true,
                isOtherChapter = item.id != currentDetail.item.id,
            )
        }

    /** Newest reading row across every chapter of this work (chapter rows share the work id). */
    val workResumeProgress: ReadingProgress?
        get() {
            val seriesId = workSeriesId?.takeIf(String::isNotBlank) ?: return readingProgress
            return readingProgressByMediaId.values
                .filter { it.seriesId == seriesId || it.workId == seriesId }
                .plus(listOfNotNull(readingProgress))
                .maxByOrNull { it.updatedAt }
        }

    val resumeStartPage: Int
        get() = resumeTarget?.startPage ?: 1

    val primaryCtaLabel: String
        get() {
            val target = resumeTarget
            return when {
                target == null -> "开始阅读"
                target.completed -> "重新阅读"
                target.startPage > 1 -> "继续阅读 (第 ${target.startPage} 页)"
                target.isOtherChapter -> "继续阅读"
                else -> "开始阅读"
            }
        }

    private fun chapterItemFor(progress: ReadingProgress, currentDetail: GalleryDetail): MediaItem =
        if (progress.mediaId == currentDetail.item.id) {
            currentDetail.item
        } else {
            MediaItem(
                siteId = progress.siteId,
                id = progress.mediaId,
                url = progress.url,
                kind = progress.kind,
                title = progress.title,
                coverUrl = progress.coverUrl,
                pageCount = progress.totalPages.takeIf { it > 0 },
            )
        }

    val canOpenOffline: Boolean
        get() = isLocal || isOfflineComplete || downloadedPages.isNotEmpty() ||
            activeDownload?.status == DownloadStatus.COMPLETED

    /** First unread/unfinished chapter after the current one, or null at the list boundary. */
    val nextUnreadChapter: MediaItem?
        get() {
            val current = detail ?: return null
            return findNextUnreadChapter(
                chapters = chapters,
                currentChapter = current.item,
                progressByMediaId = readingProgressByMediaId,
            )
        }

    val allChaptersCompleted: Boolean
        get() = chapters.isNotEmpty() && chapters.all { chapter ->
            readingProgressByMediaId[chapter.id]?.completed == true
        }

    /** Boundary copy shown when "下一未读" is disabled. */
    val nextUnreadBoundaryLabel: String?
        get() = when {
            chapters.isEmpty() || nextUnreadChapter != null -> null
            allChaptersCompleted -> "全部章节已读完"
            else -> "后续章节已读完"
        }

    val chapterPickerReadState: ChapterPickerReadState
        get() = ChapterPickerReadState(
            progressByMediaId = readingProgressByMediaId,
            nextUnreadChapter = nextUnreadChapter,
            nextUnreadBoundaryLabel = nextUnreadBoundaryLabel,
        )
}

/**
 * One-shot side effects emitted by [GalleryDetailViewModel] for SAF export pickers.
 */
sealed interface GalleryDetailEvent {
    data class RequestSaveDocument(
        val prepared: PreparedExport,
        val suggestedFileName: String,
        val mimeType: String,
    ) : GalleryDetailEvent
}
