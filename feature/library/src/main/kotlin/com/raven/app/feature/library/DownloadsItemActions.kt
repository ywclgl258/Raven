package com.raven.app.feature.library


import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus

/** 下载条目动作语义：批量控制可见性、主操作分发与主操作文案。 */
internal fun DownloadsUiState.hasBatchControls(): Boolean =
    activeTransferCount > 0 || pausedCount > 0 || attentionCount > 0 || completedCount > 0

internal fun handleDownloadPrimaryAction(
    navigator: LibraryNavigator,
    viewModel: DownloadsViewModel,
    item: DownloadItem,
) {
    when (item.status) {
        DownloadStatus.COMPLETED -> openDownloadedItem(navigator, item)
        DownloadStatus.DOWNLOADING,
        DownloadStatus.QUEUED,
        DownloadStatus.RETRY_WAIT -> viewModel.pause(item.id)
        DownloadStatus.PAUSED -> viewModel.resume(item.id)
        DownloadStatus.FAILED,
        DownloadStatus.CANCELLED -> viewModel.retry(item.id)
    }
}

internal fun DownloadItem.primaryActionLabel(): String = when (status) {
    DownloadStatus.COMPLETED -> if (kind == DownloadKind.VIDEO) "离线播放" else "离线阅读"
    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.RETRY_WAIT -> "暂停下载"
    DownloadStatus.PAUSED -> "继续下载"
    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> "重试下载"
}
