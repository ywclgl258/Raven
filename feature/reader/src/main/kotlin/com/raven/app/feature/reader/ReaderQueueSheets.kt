package com.raven.app.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenFrostedSurface
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.download.PlaybackQueueSession
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode

/** 阅读/播放队列续读卡片与模式文案。 */

@Composable
internal fun QueueNextConfirmationCard(
    session: PlaybackQueueSession,
    onContinueNext: () -> Unit,
    onDismiss: () -> Unit,
    onEndQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val next = session.nextItem ?: return
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .ravenFrostedSurface(
                reduceTransparency = reduceTransparency,
                shape = RavenShapes.CardShape,
                borderColor = colors.crimson.copy(alpha = 0.40f),
            )
            .padding(14.dp)
            .testTag(ReaderTestTags.READER_QUEUE_NEXT_CARD),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = null,
                    tint = colors.crimson,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "队列下一项 · ${session.title}（剩余 ${session.remainingCount}）",
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "稍后",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RavenImage(
                url = next.coverUrl,
                siteId = next.siteId,
                contentDescription = next.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(56.dp)
                    .height(74.dp)
                    .clip(RavenShapes.ControlShape)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = next.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val kindLabel = if (next.kind.isVideo) "视频" else "图集"
                Text(
                    text = if (session.offlineOnly) "$kindLabel · 离线队列" else kindLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onContinueNext,
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (next.kind.isVideo) "播放下一项" else "阅读下一本",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
            OutlinedButton(
                onClick = onEndQueue,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface2,
                    contentColor = colors.inkPrimary,
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = "结束队列",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

internal fun readerModeLabel(mode: ReaderMode): String = when (mode) {
    ReaderMode.STRIP -> "条漫"
    ReaderMode.LTR -> "左到右"
    ReaderMode.RTL -> "右到左"
    ReaderMode.SPREAD -> "横屏双页"
}

internal fun readerFitLabel(fit: ReaderFit): String = when (fit) {
    ReaderFit.WIDTH -> "适宽"
    ReaderFit.HEIGHT -> "适高"
    ReaderFit.ORIGINAL -> "原尺寸"
}
