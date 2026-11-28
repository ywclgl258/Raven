package com.raven.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular

/** 详情页区块：队列续播卡与视频元数据条。从 `PlayerDetailPane.kt` 拆出。 */

@Composable
internal fun SequentialQueueNextCard(
    queue: PlayerQueueUiState,
    onConfirmNext: () -> Unit,
    onEndQueue: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val nextItem = queue.nextItem
    val displayItem = nextItem ?: queue.items.getOrNull(queue.currentIndex) ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.crimson.copy(alpha = 0.35f), RavenShapes.CardShape)
            .padding(14.dp)
            .clickable(role = Role.Button, onClick = onOpenQueue)
            .testTag(PlayerTestTags.PLAYER_QUEUE_CARD),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RavenStatusPill(
                    text = "播放队列 ${queue.currentIndex + 1}/${queue.totalCount}",
                    tone = RavenStatusTone.ACCENT,
                )
                if (queue.offlineOnly) {
                    RavenStatusPill(text = "纯离线", tone = RavenStatusTone.SUCCESS)
                }
            }
            IconButton(
                onClick = onEndQueue,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "结束队列",
                    tint = colors.inkTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .height(52.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2),
            ) {
                RavenImage(
                    url = displayItem.coverUrl,
                    siteId = displayItem.siteId,
                    contentDescription = displayItem.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (nextItem != null) "下一项 · ${queue.title}" else "当前项 · ${queue.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = displayItem.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nextItem != null) {
                Button(
                    onClick = onConfirmNext,
                    shape = RavenShapes.PillShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .ravenSpringPress()
                        .testTag(PlayerTestTags.PLAYER_QUEUE_NEXT_BUTTON),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "下一项",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoMetadataStrip(
    uiState: PlayerUiState,
) {
    val colors = LocalRavenColors.current
    val item = uiState.detail?.item

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (uiState.siteDisplayName.isNotBlank()) {
            RavenStatusPill(
                text = uiState.siteDisplayName,
                tone = if (uiState.isLocal || uiState.isOffline) RavenStatusTone.SUCCESS else RavenStatusTone.NEUTRAL,
            )
        }
        if (uiState.detail?.isPreviewOnly == true) {
            RavenStatusPill(text = "试看预览", tone = RavenStatusTone.WARNING)
        }
        val durationSec = item?.durationSeconds
            ?: uiState.effectiveDurationMs.takeIf { it > 0L }?.let { (it / 1_000L).toInt() }
        val durationText = RavenFormatters.formatDuration(durationSec)
        val metaSummary = buildList {
            if (durationText.isNotEmpty()) add(durationText)
            RavenFormatters.formatCount(item?.views).takeIf { it.isNotEmpty() }?.let { add("$it 次观看") }
            RavenFormatters.formatCount(item?.likes).takeIf { it.isNotEmpty() }?.let { add("$it 赞") }
            item?.dateLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")

        if (metaSummary.isNotEmpty()) {
            Text(
                text = metaSummary,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = colors.inkSecondary,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}
