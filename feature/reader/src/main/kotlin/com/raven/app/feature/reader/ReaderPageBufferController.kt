package com.raven.app.feature.reader

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.media.reader.ReaderBuffer
import com.raven.app.core.model.GalleryDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns the primary page buffer: decode window, memory budget, concurrency, prefetch depth and the
 * state collector that mirrors the buffer window into [ReaderUiState].
 *
 * Extracted from [ReaderViewModel]; the tuning fields are exposed as mutable properties because the
 * ViewModel's preference/rebuild paths compare and write them directly.
 */
internal class ReaderPageBufferController(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val pageLoader: ReaderPageLoader,
    private val state: StateFlow<ReaderUiState>,
    private val update: ((ReaderUiState) -> ReaderUiState) -> Unit,
    private val offlineOnly: Boolean,
    private val sharedRatios: () -> MutableMap<Int, Float>,
) {
    var preferOriginal: Boolean = false
    var prefetchAhead: Int = ReaderBuffer.PREFETCH_AHEAD
    var budgetBytes: Long = ReaderBuffer.DEFAULT_OFFSCREEN_BUDGET_BYTES
    var concurrency: Int = ReaderBuffer.MAX_CONCURRENT_LOADS

    private var buffer: ReaderBuffer<LoadedReaderPage>? = null
    private var collectJob: Job? = null

    fun current(): ReaderBuffer<LoadedReaderPage>? = buffer

    /** Cancels the collector and releases the buffer (dispose / gallery switch). */
    fun close() {
        collectJob?.cancel()
        collectJob = null
        buffer?.close()
        buffer = null
    }

    fun rebuild(
        detail: GalleryDetail,
        pageNumbers: List<Int>,
        sourceRoot: String,
        preferOriginal: Boolean,
        prefetchAhead: Int = this.prefetchAhead,
        offscreenBudgetBytes: Long = this.budgetBytes,
        maxConcurrency: Int = this.concurrency,
    ) {
        collectJob?.cancel()
        buffer?.close()
        this.preferOriginal = preferOriginal
        this.prefetchAhead = prefetchAhead
        this.budgetBytes = offscreenBudgetBytes
        this.concurrency = maxConcurrency.coerceIn(1, ReaderBuffer.MAX_CONCURRENT_LOADS)

        val buffer = ReaderBuffer<LoadedReaderPage>(
            pageNumbers = pageNumbers,
            scope = scope,
            byteSizeOf = { it.byteSize },
            aspectRatioOf = { it.aspectRatio },
            offscreenBudgetBytes = offscreenBudgetBytes,
            maxConcurrency = concurrency,
            sharedRatios = sharedRatios(),
            prefetchAhead = prefetchAhead,
            loader = { pageNumber, forceReload ->
                pageLoader.loadRenderedPage(
                    detail = detail,
                    pageIndex = pageNumber,
                    preferOriginal = preferOriginal,
                    forceReload = forceReload,
                    offlineOnly = offlineOnly,
                    sourceRoot = sourceRoot,
                )
            },
        )
        this.buffer = buffer

        collectJob = scope.launch(dispatchers.main) {
            buffer.state.collectLatest { bufState ->
                update { state ->
                    state.copy(
                        bufferState = bufState,
                        pageAspectRatios = if (bufState.ratios.isEmpty()) {
                            sharedRatios().toMap()
                        } else {
                            bufState.ratios
                        },
                    )
                }
            }
        }

        val current = state.value.currentPage
        val visible = state.value.currentSheetPages.toSet()
        buffer.updateWindow(page = current, visible = visible)
    }
}
