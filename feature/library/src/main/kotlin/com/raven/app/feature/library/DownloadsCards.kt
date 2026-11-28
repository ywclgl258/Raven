package com.raven.app.feature.library


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenCrimsonProgressBar
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import kotlin.math.roundToInt

/** 下载条目卡片层：海报卡与列表卡及其详情文案。 */
@Composable
internal fun DownloadPosterCard(
    item: DownloadItem,
    siteLabel: String,
    onPrimaryClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val tone = when (item.status) {
        DownloadStatus.COMPLETED -> RavenStatusTone.SUCCESS
        DownloadStatus.DOWNLOADING -> RavenStatusTone.ACCENT
        DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.RETRY_WAIT -> RavenStatusTone.WARNING
        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> RavenStatusTone.ERROR
    }
    val detailLine = remember(item) { downloadDetailLine(item) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClickLabel = item.primaryActionLabel(), onClick = onPrimaryClick)
            .testTag("download_card_${item.id}"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (item.kind == DownloadKind.VIDEO) 16f / 9f else 3f / 4f)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            ) {
                RavenImage(
                    url = item.coverUrl,
                    siteId = item.siteId,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                RavenStatusPill(
                    text = item.status.displayLabel(),
                    tone = tone,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp),
                )
                if (item.status != DownloadStatus.COMPLETED) {
                    if (item.progressFraction > 0f) {
                        RavenCrimsonProgressBar(
                            fraction = item.progressFraction,
                            trackHeight = 3.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                        )
                    } else if (item.status == DownloadStatus.DOWNLOADING) {
                        LinearProgressIndicator(
                            color = colors.crimson,
                            trackColor = colors.inkPrimary.copy(alpha = 0.22f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(3.dp),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$siteLabel · ${item.kind.displayLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detailLine.isNotEmpty()) {
                Text(
                    text = detailLine,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.status == DownloadStatus.RETRY_WAIT) {
                Text(
                    text = formatRetryCountdown(item.retryAtEpochMs),
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.warning,
                )
            } else if (item.status == DownloadStatus.FAILED || (item.status == DownloadStatus.COMPLETED && !item.errorMessage.isNullOrBlank())) {
                val errText = buildString {
                    item.errorCategory?.let { append("${it.displayLabel()}：") }
                    append(item.errorMessage?.takeIf { it.isNotBlank() } ?: "下载失败")
                }
                Text(
                    text = errText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.groupName.isNotBlank()) {
                    RavenStatusPill(text = item.groupName)
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onPrimaryClick,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.surface2),
                ) {
                    Icon(
                        imageVector = when (item.status) {
                            DownloadStatus.COMPLETED -> Icons.Outlined.OfflinePin
                            DownloadStatus.DOWNLOADING,
                            DownloadStatus.QUEUED,
                            DownloadStatus.RETRY_WAIT -> Icons.Outlined.Pause
                            DownloadStatus.PAUSED -> Icons.Outlined.PlayArrow
                            DownloadStatus.FAILED,
                            DownloadStatus.CANCELLED -> Icons.Outlined.Refresh
                        },
                        contentDescription = item.primaryActionLabel(),
                        tint = if (item.status == DownloadStatus.COMPLETED) colors.success else colors.inkPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多选项",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

internal fun downloadDetailLine(item: DownloadItem): String = buildList {
    if (item.kind != DownloadKind.VIDEO && item.totalUnits > 0) {
        val pagePct = ((item.completedUnits.toFloat() / item.totalUnits.toFloat()) * 100f)
            .roundToInt()
            .coerceIn(0, 100)
        if (item.status != DownloadStatus.COMPLETED) {
            add("${item.completedUnits} / ${item.totalUnits} 页 ($pagePct%)")
        } else {
            add("${item.completedUnits} / ${item.totalUnits} 页")
        }
    } else if (item.status != DownloadStatus.COMPLETED) {
        if (item.progressFraction > 0f) {
            add("${(item.progressFraction * 100f).roundToInt().coerceIn(0, 100)}%")
        } else if (item.status == DownloadStatus.DOWNLOADING) {
            add("正在连接…")
        }
    }
    item.quality?.takeIf { it.isNotBlank() }?.let { add(it) }
    val totalBytes = item.totalBytes?.takeIf { it > 0L }
    if (item.status != DownloadStatus.COMPLETED && item.downloadedBytes > 0L && totalBytes != null) {
        add("${RavenFormatters.formatByteSize(item.downloadedBytes)} / ${RavenFormatters.formatByteSize(totalBytes)}")
    } else if (item.status != DownloadStatus.COMPLETED && item.downloadedBytes > 0L) {
        add("已下载 ${RavenFormatters.formatByteSize(item.downloadedBytes)}")
    } else {
        val sizeBytes = totalBytes ?: item.downloadedBytes
        if (sizeBytes > 0L) add(RavenFormatters.formatByteSize(sizeBytes))
    }
}.joinToString(" · ")
@Composable
internal fun DownloadItemCard(
    item: DownloadItem,
    siteLabel: String,
    onPrimaryClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val tone = when (item.status) {
        DownloadStatus.COMPLETED -> RavenStatusTone.SUCCESS
        DownloadStatus.DOWNLOADING -> RavenStatusTone.ACCENT
        DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.RETRY_WAIT -> RavenStatusTone.WARNING
        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> RavenStatusTone.ERROR
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClickLabel = item.primaryActionLabel(), onClick = onPrimaryClick)
            .padding(12.dp)
            .testTag("download_card_${item.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(if (item.kind == DownloadKind.VIDEO) 96.dp else 62.dp)
                    .height(if (item.kind == DownloadKind.VIDEO) 58.dp else 82.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            ) {
                RavenImage(
                    url = item.coverUrl,
                    siteId = item.siteId,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RavenStatusPill(text = item.status.displayLabel(), tone = tone)
                    RavenStatusPill(text = "$siteLabel · ${item.kind.displayLabel()}")
                    if (item.groupName.isNotBlank()) {
                        RavenStatusPill(text = item.groupName)
                    }
                }

                val detailLine = remember(item) { downloadDetailLine(item) }

                if (detailLine.isNotEmpty()) {
                    Text(
                        text = detailLine,
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (item.status == DownloadStatus.RETRY_WAIT) {
                    Text(
                        text = formatRetryCountdown(item.retryAtEpochMs),
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.warning,
                    )
                } else if (item.status == DownloadStatus.FAILED || (item.status == DownloadStatus.COMPLETED && !item.errorMessage.isNullOrBlank())) {
                    val errText = buildString {
                        item.errorCategory?.let { append("${it.displayLabel()}：") }
                        append(item.errorMessage?.takeIf { it.isNotBlank() } ?: "下载失败")
                    }
                    Text(
                        text = errText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = onPrimaryClick,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.surface2),
                ) {
                    Icon(
                        imageVector = when (item.status) {
                            DownloadStatus.COMPLETED -> Icons.Outlined.OfflinePin
                            DownloadStatus.DOWNLOADING,
                            DownloadStatus.QUEUED,
                            DownloadStatus.RETRY_WAIT -> Icons.Outlined.Pause
                            DownloadStatus.PAUSED -> Icons.Outlined.PlayArrow
                            DownloadStatus.FAILED,
                            DownloadStatus.CANCELLED -> Icons.Outlined.Refresh
                        },
                        contentDescription = item.primaryActionLabel(),
                        tint = if (item.status == DownloadStatus.COMPLETED) colors.success else colors.inkPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多选项",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (item.status != DownloadStatus.COMPLETED) {
            if (item.progressFraction > 0f) {
                RavenCrimsonProgressBar(
                    fraction = item.progressFraction,
                    trackHeight = 3.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RavenShapes.PillShape),
                )
            } else if (item.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    color = colors.crimson,
                    trackColor = colors.inkPrimary.copy(alpha = 0.22f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RavenShapes.PillShape),
                )
            }
        }
    }
}
