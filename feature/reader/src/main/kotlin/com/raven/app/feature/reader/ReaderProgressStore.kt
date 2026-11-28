package com.raven.app.feature.reader

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the reader's debounced reading-progress pipeline: the 750ms trailing save, the appended
 * serial-chapter anchor (which must never promote a stale chapter row), and the immediate flush
 * used on dispose / app background.
 *
 * Extracted from [ReaderViewModel] so the persistence rules live in one testable place; the
 * ViewModel keeps [ReaderProgressStore.flush] and [ReaderProgressStore.flushNow] as its public
 * entry points.
 */
internal class ReaderProgressStore(
    private val siteId: SiteId,
    private val mediaId: String,
    private val routeUrl: String,
    private val isInvalidSiteId: Boolean,
    private val historyRepository: HistoryRepository,
    private val dispatchers: AppDispatchers,
    private val viewModelScope: CoroutineScope,
    private val persistenceScope: CoroutineScope?,
    private val clock: () -> Long,
    private val state: () -> ReaderUiState,
    private val incognito: () -> Boolean,
    private val seriesFor: (GalleryDetail) -> RavenRoute.ReaderParent?,
) {
    private var progressSaveJob: Job? = null
    private var appendedProgressSaveJob: Job? = null
    private var pendingAppended: PendingAppendedChapterProgress? = null
    private var hasDirtyProgress: Boolean = false
    private var lastSavedPage: Int = -1
    private var lastSavedOffsetPx: Int = -1

    fun schedule(page: Int, scrollOffsetPx: Int) {
        if (state().detail == null) return
        hasDirtyProgress = true
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch(dispatchers.io) {
            delay(ReaderViewModel.PROGRESS_SAVE_DEBOUNCE_MS)
            persistProgressInternal(page = page, scrollOffsetPx = scrollOffsetPx)
        }
    }

    fun scheduleAppended(chapter: SerialStripChapter, page: Int, scrollOffsetPx: Int) {
        val series = seriesFor(chapter.detail)
        // Remember the newest appended-chapter anchor so an early exit (before the debounce elapses)
        // can still flush it instead of only persisting the first chapter's stale position.
        pendingAppended = PendingAppendedChapterProgress(
            mediaId = chapter.mediaId,
            url = chapter.url,
            title = chapter.title,
            coverUrl = chapter.detail.item.coverUrl,
            kind = chapter.detail.item.kind,
            page = page,
            totalPages = chapter.pageNumbers.size.coerceAtLeast(1),
            scrollOffsetPx = scrollOffsetPx,
            series = series,
        )
        appendedProgressSaveJob?.cancel()
        appendedProgressSaveJob = viewModelScope.launch(dispatchers.io) {
            delay(ReaderViewModel.PROGRESS_SAVE_DEBOUNCE_MS)
            // Reverse scrolling back to the primary chapter (or into a different appended chapter)
            // must not let this delayed write promote an outdated chapter in history.
            if (state().activeStripChapterId != chapter.mediaId) {
                if (pendingAppended?.mediaId == chapter.mediaId) {
                    pendingAppended = null
                }
                return@launch
            }
            persistProgressEntity(
                siteId = siteId,
                mediaId = chapter.mediaId,
                url = chapter.url,
                title = chapter.title,
                coverUrl = chapter.detail.item.coverUrl,
                kind = chapter.detail.item.kind,
                currentPage = page,
                totalPages = chapter.pageNumbers.size.coerceAtLeast(1),
                scrollOffsetPx = scrollOffsetPx,
                series = series,
            )
            if (pendingAppended?.mediaId == chapter.mediaId) {
                pendingAppended = null
            }
        }
    }

    /**
     * Cancels any pending 750ms debounce timer and flushes the latest reading progress immediately.
     * Safe to invoke from `DisposableEffect.onDispose`, `ON_STOP`, and `onCleared`.
     */
    fun flush() {
        progressSaveJob?.cancel()
        progressSaveJob = null
        appendedProgressSaveJob?.cancel()
        appendedProgressSaveJob = null
        val snapshot = state()
        val detail = snapshot.detail ?: return
        val activeChapterId = snapshot.activeStripChapterId
        val scope = persistenceScope ?: viewModelScope
        val pendingChapter = pendingAppended
        if (pendingChapter != null) {
            if (activeChapterId == pendingChapter.mediaId) {
                // The appended chapter still owns the viewport: flush it under NonCancellable so a
                // fast exit cannot lose its position while the delayed job is cancelled.
                scope.launch(dispatchers.io + NonCancellable) {
                    flushPending()
                    if (pendingAppended?.mediaId == pendingChapter.mediaId) {
                        pendingAppended = null
                    }
                }
            } else {
                // Reverse scrolling left a stale appended anchor; never write it over the primary row.
                pendingAppended = null
            }
        }
        // While an appended chapter is active it owns the latest history row; writing the primary
        // row here would promote a chapter the reader has already scrolled past.
        if (activeChapterId != null) return
        if (!hasDirtyProgress && lastSavedPage == snapshot.currentPage &&
            lastSavedOffsetPx == snapshot.stripScrollOffsetPx
        ) {
            return
        }
        val page = snapshot.currentPage
        val offset = snapshot.stripScrollOffsetPx
        val total = snapshot.totalPages.coerceAtLeast(1)
        val item = detail.item
        val series = seriesFor(detail)
        scope.launch(dispatchers.io + NonCancellable) {
            persistProgressEntity(
                siteId = siteId,
                mediaId = mediaId,
                url = item.url.ifBlank { routeUrl },
                title = item.title,
                coverUrl = item.coverUrl,
                kind = item.kind,
                currentPage = page,
                totalPages = total,
                scrollOffsetPx = offset,
                series = series,
            )
        }
    }

    /** Suspend helper for immediate deterministic flushing (e.g., tests or pre-navigation). */
    suspend fun flushNow() {
        progressSaveJob?.cancel()
        progressSaveJob = null
        appendedProgressSaveJob?.cancel()
        appendedProgressSaveJob = null
        val snapshot = state()
        if (snapshot.detail == null) return
        val activeChapterId = snapshot.activeStripChapterId
        withContext(dispatchers.io + NonCancellable) {
            val pendingChapter = pendingAppended
            if (pendingChapter != null && activeChapterId == pendingChapter.mediaId) {
                flushPending()
            }
            pendingAppended = null
            if (activeChapterId == null) {
                persistProgressInternal(
                    page = snapshot.currentPage,
                    scrollOffsetPx = snapshot.stripScrollOffsetPx,
                )
            }
        }
    }

    /** Drops any pending appended-chapter anchor without writing it. */
    fun clearPending() {
        pendingAppended = null
        appendedProgressSaveJob?.cancel()
        appendedProgressSaveJob = null
    }

    /** Persist the primary chapter anchor before an appended chapter becomes the viewport owner. */
    fun persistPrimaryAnchorBeforeAppended(snapshot: ReaderUiState) {
        if (!hasDirtyProgress) return
        progressSaveJob?.cancel()
        progressSaveJob = null
        val page = snapshot.currentPage
        val offset = snapshot.stripScrollOffsetPx
        viewModelScope.launch(dispatchers.io + NonCancellable) {
            persistProgressInternal(page = page, scrollOffsetPx = offset)
        }
    }

    /** Cancels every pending write; used from `onCleared` before the final [flush]. */
    fun cancel() {
        progressSaveJob?.cancel()
        progressSaveJob = null
        appendedProgressSaveJob?.cancel()
        appendedProgressSaveJob = null
    }

    private suspend fun persistProgressInternal(page: Int, scrollOffsetPx: Int) {
        val snapshot = state()
        val detail = snapshot.detail ?: return
        val item = detail.item
        val total = snapshot.totalPages.coerceAtLeast(1)
        val series = seriesFor(detail)
        persistProgressEntity(
            siteId = siteId,
            mediaId = mediaId,
            url = item.url.ifBlank { routeUrl },
            title = item.title,
            coverUrl = item.coverUrl,
            kind = item.kind,
            currentPage = page,
            totalPages = total,
            scrollOffsetPx = scrollOffsetPx,
            series = series,
        )
    }

    private suspend fun persistProgressEntity(
        siteId: SiteId,
        mediaId: String,
        url: String,
        title: String,
        coverUrl: String,
        kind: MediaKind,
        currentPage: Int,
        totalPages: Int,
        scrollOffsetPx: Int,
        series: RavenRoute.ReaderParent?,
    ) {
        if (isInvalidSiteId || incognito()) {
            hasDirtyProgress = false
            return
        }
        val clampedPage = currentPage.coerceIn(1, totalPages.coerceAtLeast(1))
        val clampedOffset = scrollOffsetPx.coerceAtLeast(0)
        runCatching {
            historyRepository.saveReadingProgress(
                ReadingProgress(
                    siteId = siteId,
                    mediaId = mediaId,
                    url = url,
                    title = title,
                    coverUrl = coverUrl,
                    kind = kind,
                    currentPage = clampedPage,
                    totalPages = totalPages,
                    scrollOffsetPx = clampedOffset,
                    updatedAt = clock(),
                    seriesId = series?.seriesId,
                    seriesTitle = series?.title?.takeIf(String::isNotBlank),
                    seriesUrl = series?.url?.takeIf(String::isNotBlank),
                ),
            )
            hasDirtyProgress = false
            lastSavedPage = clampedPage
            lastSavedOffsetPx = clampedOffset
        }
    }

    /** Persists the pending appended-chapter anchor synchronously; safe under cancellation. */
    private suspend fun flushPending() {
        val pending = pendingAppended ?: return
        persistProgressEntity(
            siteId = siteId,
            mediaId = pending.mediaId,
            url = pending.url,
            title = pending.title,
            coverUrl = pending.coverUrl,
            kind = pending.kind,
            currentPage = pending.page,
            totalPages = pending.totalPages,
            scrollOffsetPx = pending.scrollOffsetPx,
            series = pending.series,
        )
    }
}

/** Appended-chapter anchor awaiting the debounce window. */
internal data class PendingAppendedChapterProgress(
    val mediaId: String,
    val url: String,
    val title: String,
    val coverUrl: String,
    val kind: MediaKind,
    val page: Int,
    val totalPages: Int,
    val scrollOffsetPx: Int,
    val series: RavenRoute.ReaderParent?,
)
