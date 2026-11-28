package com.raven.app.feature.reader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.media.reader.ExportMode
import com.raven.app.core.media.reader.PreparedExport
import com.raven.app.core.common.PageSelectionParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Page/gallery export and SAF share flows: current page export, CBZ range export, batch page export,
 * share-URI preparation and SAF completion. Owns the single in-flight export job.
 *
 * Extracted from [ReaderViewModel]; the ViewModel keeps thin delegates.
 */
internal class ReaderExportCoordinator(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val gateway: GalleryExportGateway,
    private val state: StateFlow<ReaderUiState>,
    private val update: ((ReaderUiState) -> ReaderUiState) -> Unit,
    private val emitEvent: (ReaderEvent) -> Unit,
    private val notice: (String) -> Unit,
    private val mediaId: String,
    private val supportsOriginalImages: Boolean,
    private val pageLoader: ReaderPageLoader,
    private val offlineOnly: Boolean,
) {
    private var exportJob: Job? = null

    fun exportCurrentPage(mode: ExportMode) {
        val state = state.value
        val detail = state.detail ?: return
        // In STRIP mode the visible page may belong to an appended chapter; export must use that
        // chapter's detail/pages/source so the exported bytes match the image on screen.
        val ctx = resolveActivePageContext(state, detail, mediaId)
        exportPages(pages = listOf(ctx.page), mode = mode, followActiveChapter = true)
    }

    fun exportCbz(rawRangeInput: String? = null) {
        val state = state.value
        val total = state.totalPages
        if (total <= 0) return

        val targetPages = if (rawRangeInput.isNullOrBlank()) {
            state.pageNumbers.ifEmpty { (1..total).toList() }
        } else {
            try {
                PageSelectionParser.parse(rawRangeInput, total)
            } catch (_: IllegalArgumentException) {
                notice("页码格式无效（有效范围 1..$total，如 1, 3, 5-8）")
                return
            }
        }
        // Whole-gallery archive: page numbers come from the opened gallery, never an appended one.
        exportPages(pages = targetPages, mode = ExportMode.CBZ, followActiveChapter = false)
    }

    fun exportPages(
        pages: List<Int>,
        mode: ExportMode,
        followActiveChapter: Boolean = false,
    ) {
        val state = state.value
        val detail = state.detail ?: return
        if (state.isExporting) return
        // The current-page path follows the on-screen appended chapter; range/gallery exports keep
        // the opened gallery's detail because their page numbers refer to it.
        val ctx = if (followActiveChapter) resolveActivePageContext(state, detail, mediaId) else null

        val unique = pages.distinct().sorted()
        if (unique.isEmpty()) {
            notice("没有可导出的图页")
            return
        }

        exportJob?.cancel()
        exportJob = scope.launch(dispatchers.io) {
            val actionName = when (mode) {
                ExportMode.SAVE_IMAGES -> "保存原图"
                ExportMode.SHARE_IMAGES -> "准备分享"
                ExportMode.CBZ -> "打包 CBZ"
            }
            update { it.copy(isExporting = true, exportStatusText = "正在$actionName…") }
            try {
                val preferOriginal = supportsOriginalImages && state.preferences.preferOriginalImages
                // Non-following exports use the opened gallery's detail and root.
                val exportDetail = ctx?.detail ?: detail
                val exportSourceRoot = if (ctx != null && ctx.mediaId != mediaId) {
                    state.appendedChapters.firstOrNull { it.mediaId == ctx.mediaId }?.sourceRoot
                        ?: state.sourceRoot
                } else {
                    state.sourceRoot
                }
                val prepared = gateway.prepare(
                    title = exportDetail.item.title,
                    pages = unique,
                    mode = mode,
                    loadPageBytes = { pageIndex ->
                        pageLoader.loadOriginalBytes(
                            detail = exportDetail,
                            pageIndex = pageIndex,
                            preferOriginal = preferOriginal,
                            offlineOnly = offlineOnly,
                            sourceRoot = exportSourceRoot,
                        )
                    },
                    onProgress = { text ->
                        update { it.copy(exportStatusText = text) }
                    },
                )
                update { it.copy(isExporting = false, exportStatusText = null) }
                when (mode) {
                    ExportMode.SHARE_IMAGES -> {
                        emitEvent(
                            ReaderEvent.RequestShareExport(
                                prepared = prepared,
                                subject = "${exportDetail.item.title} - 第 ${unique.first()} 页",
                            ),
                        )
                    }
                    ExportMode.CBZ -> {
                        val file = prepared.files.single()
                        emitEvent(
                            ReaderEvent.RequestSaveDocument(
                                prepared = prepared,
                                suggestedFileName = file.file.name,
                                mimeType = file.mimeType,
                            ),
                        )
                    }
                    ExportMode.SAVE_IMAGES -> {
                        if (prepared.files.size == 1) {
                            val file = prepared.files.single()
                            emitEvent(
                                ReaderEvent.RequestSaveDocument(
                                    prepared = prepared,
                                    suggestedFileName = file.file.name,
                                    mimeType = file.mimeType,
                                ),
                            )
                        } else {
                            emitEvent(ReaderEvent.RequestOpenDocumentTree(prepared = prepared))
                        }
                    }
                }
            } catch (ce: CancellationException) {
                update { it.copy(isExporting = false, exportStatusText = null) }
                throw ce
            } catch (error: Throwable) {
                update { it.copy(isExporting = false, exportStatusText = null) }
                notice(error.message?.takeIf(String::isNotBlank) ?: "${actionName}失败")
            }
        }
    }

    fun prepareShareUris(
        context: Context,
        authority: String,
        prepared: PreparedExport,
    ): List<Uri> = gateway.prepareShareUris(
        context = context,
        authority = authority,
        prepared = prepared,
    )

    fun completeSafExport(
        prepared: PreparedExport,
        targetUri: Uri?,
        isTree: Boolean,
        contentResolver: ContentResolver,
    ) {
        scope.launch(dispatchers.io) {
            if (targetUri == null) {
                runCatching { gateway.removeStagingFolder(prepared.folder) }
                notice("已取消保存")
                return@launch
            }
            update { it.copy(isExporting = true, exportStatusText = "正在写入目标位置…") }
            try {
                val outcome = gateway.saveToSaf(
                    prepared = prepared,
                    targetUri = targetUri,
                    isTree = isTree,
                    contentResolver = contentResolver,
                    onProgress = { text -> update { it.copy(exportStatusText = text) } },
                )
                update { it.copy(isExporting = false, exportStatusText = null) }
                notice("已成功保存 ${outcome.savedCount} 个文件")
            } catch (ce: CancellationException) {
                update { it.copy(isExporting = false, exportStatusText = null) }
                throw ce
            } catch (error: Throwable) {
                update { it.copy(isExporting = false, exportStatusText = null) }
                notice(error.message?.takeIf(String::isNotBlank) ?: "保存文件失败")
            }
        }
    }

    // ---- Thumbnail Helper (Local / Offline Sheet Previews) ----

    /** Cancels any in-flight export (dispose). */
    fun cancel() {
        exportJob?.cancel()
        exportJob = null
    }
}
