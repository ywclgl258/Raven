package com.raven.app.feature.library


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.download.BatchEnqueueProgress

/** 下载页横幅层：网络策略提示、批量进度与消息横幅。从 `DownloadsScreen.kt` 拆出，包名与 `testTag` 契约不变。 */
@Composable
internal fun DownloadsNetworkPolicyBanners(
    uiState: DownloadsUiState,
    horizontalPadding: Dp = 16.dp,
) {
    if (!uiState.networkPolicy.isConnected) {
        LibraryFeedbackBanner(
            message = "当前无网络连接，下载任务将在恢复连接后自动继续",
            tone = RavenStatusTone.WARNING,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
    } else if (uiState.networkPolicy.unmeteredOnly && uiState.networkPolicy.isMetered) {
        LibraryFeedbackBanner(
            message = "当前为移动计费网络，已按「仅 Wi-Fi 下载」策略暂停传输",
            tone = RavenStatusTone.WARNING,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
    }
}

@Composable
internal fun DownloadsBatchProgressBanner(
    progress: BatchEnqueueProgress,
    onCancel: () -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (progress.isRunning) {
                    "正在批量加入下载（${progress.processed} / ${progress.total}）"
                } else {
                    "批量加入完成（成功 ${progress.added} / ${progress.total}）"
                },
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = colors.inkPrimary,
            )
            if (progress.isRunning) {
                TextButton(onClick = onCancel) {
                    Text("取消", color = colors.error)
                }
            }
        }
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { (progress.processed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                color = colors.crimson,
                trackColor = colors.surface3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (progress.errors.isNotEmpty()) {
            Text(
                text = progress.errors.take(2).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }
    }
}

@Composable
internal fun DownloadsMessageBanner(
    message: String?,
    onDismiss: () -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    LibraryFeedbackBanner(
        message = message.orEmpty(),
        tone = RavenStatusTone.ACCENT,
        onDismiss = onDismiss,
        modifier = Modifier.padding(horizontal = horizontalPadding),
    )
}
