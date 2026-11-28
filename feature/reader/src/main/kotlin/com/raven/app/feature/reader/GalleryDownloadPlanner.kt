package com.raven.app.feature.reader

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.PageSelectionParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Selective gallery download sheet: opens with "all pages" preselected, validates a manual page
 * range (`1, 3, 5-8`), and enqueues the work through [GalleryDownloadGateway].
 *
 * Extracted from [GalleryDetailViewModel]; the ViewModel keeps thin delegates so the Compose
 * entry points stay unchanged.
 */
internal class GalleryDownloadPlanner(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val gateway: GalleryDownloadGateway,
    private val isLocal: () -> Boolean,
    private val uiState: () -> GalleryDetailUiState,
    private val update: ((GalleryDetailUiState) -> GalleryDetailUiState) -> Unit,
    private val snackbar: (String) -> Unit,
) {
    fun openDownloadSheet() {
        val detail = uiState().detail ?: return
        if (isLocal()) {
            snackbar("本地导入作品已在设备本地保存")
            return
        }
        if (detail.pageCount <= 0 && detail.pages.isEmpty()) {
            snackbar("该作品暂无可下载图页")
            return
        }
        val totalPages = detail.pageCount.coerceAtLeast(1)
        update { state ->
            state.copy(
                downloadSheet = DownloadSheetState(
                    visible = true,
                    downloadAll = true,
                    rangeInput = "1-$totalPages",
                    parsedPages = (1..totalPages).toList(),
                    validationError = null,
                    isSubmitting = false,
                ),
            )
        }
    }

    fun dismissDownloadSheet() {
        update { state ->
            state.copy(downloadSheet = state.downloadSheet.copy(visible = false, isSubmitting = false))
        }
    }

    fun setDownloadAll(downloadAll: Boolean) {
        val totalPages = uiState().detail?.pageCount ?: return
        update { state ->
            if (downloadAll) {
                state.copy(
                    downloadSheet = state.downloadSheet.copy(
                        downloadAll = true,
                        validationError = null,
                    ),
                )
            } else {
                val input = state.downloadSheet.rangeInput
                val validation = validateRange(input, totalPages)
                state.copy(
                    downloadSheet = state.downloadSheet.copy(
                        downloadAll = false,
                        parsedPages = validation.first,
                        validationError = validation.second,
                    ),
                )
            }
        }
    }

    fun updateDownloadRangeInput(raw: String) {
        val totalPages = uiState().detail?.pageCount ?: return
        val (pages, error) = validateRange(raw, totalPages)
        update { state ->
            state.copy(
                downloadSheet = state.downloadSheet.copy(
                    downloadAll = false,
                    rangeInput = raw,
                    parsedPages = pages,
                    validationError = error,
                ),
            )
        }
    }

    fun confirmDownload() {
        val state = uiState()
        val detail = state.detail ?: return
        val sheet = state.downloadSheet
        if (sheet.isSubmitting) return

        val selectedPages: List<Int>? = if (sheet.downloadAll) {
            null
        } else {
            val (pages, error) = validateRange(sheet.rangeInput, detail.pageCount)
            if (error != null || pages.isEmpty()) {
                update {
                    it.copy(
                        downloadSheet = it.downloadSheet.copy(
                            parsedPages = pages,
                            validationError = error ?: "请输入有效页码范围",
                        ),
                    )
                }
                return
            }
            pages
        }

        update { it.copy(downloadSheet = it.downloadSheet.copy(isSubmitting = true)) }
        scope.launch(dispatchers.io) {
            try {
                gateway.enqueueGallery(
                    item = detail.item.copy(pageCount = detail.pageCount),
                    selectedPages = selectedPages,
                )
                update {
                    it.copy(
                        downloadSheet = it.downloadSheet.copy(visible = false, isSubmitting = false),
                    )
                }
                val message = if (selectedPages == null) {
                    "已加入整本下载队列（共 ${detail.pageCount} 页）"
                } else {
                    "已加入选页下载队列（共 ${selectedPages.size} 页）"
                }
                snackbar(message)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                update {
                    it.copy(
                        downloadSheet = it.downloadSheet.copy(
                            isSubmitting = false,
                            validationError = error.message?.takeIf(String::isNotBlank) ?: "加入下载队列失败",
                        ),
                    )
                }
            }
        }
    }

    private fun validateRange(raw: String, totalPages: Int): Pair<List<Int>, String?> {
        if (totalPages <= 0) return emptyList<Int>() to "当前图集页数无效"
        if (raw.isBlank()) return emptyList<Int>() to "请输入页码范围，例如 1, 3, 5-8"
        return try {
            val parsed = PageSelectionParser.parse(raw, totalPages)
            parsed to null
        } catch (error: IllegalArgumentException) {
            emptyList<Int>() to (error.message?.let { "页码格式无效（有效范围 1..$totalPages）" } ?: "页码格式无效")
        }
    }


}
