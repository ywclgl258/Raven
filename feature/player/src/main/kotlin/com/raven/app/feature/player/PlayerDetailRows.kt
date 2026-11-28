package com.raven.app.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.model.CommentItem
import com.raven.app.core.model.TimeBookmark

/** 详情页列表行：时间书签行与评论行。 */

@Composable
internal fun TimeBookmarkRow(
    bookmark: TimeBookmark,
    onJump: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalRavenColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onJump)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RavenStatusPill(
            text = RavenFormatters.formatDurationMs(bookmark.positionMs),
            tone = RavenStatusTone.ACCENT,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = bookmark.label.ifBlank { "时间点 ${RavenFormatters.formatDurationMs(bookmark.positionMs)}" },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (bookmark.groupName.isNotBlank()) {
                Text(
                    text = bookmark.groupName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "编辑书签",
                tint = colors.inkSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "删除书签",
                tint = colors.inkTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun PlayerCommentRow(
    comment: CommentItem,
    maxDurationMs: Long?,
    onSeekTo: (Long) -> Unit,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = comment.author.ifBlank { "匿名用户" },
                style = MaterialTheme.typography.labelLarge,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val suffix = buildList {
                if (comment.timestampLabel.isNotBlank()) add(comment.timestampLabel)
                comment.likes?.takeIf { it > 0 }?.let { add("${RavenFormatters.formatCount(it.toLong())} 赞") }
            }.joinToString(" · ")
            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    style = RavenTabularTextStyle,
                    color = colors.inkTertiary,
                )
            }
        }
        TimestampText(
            text = comment.body,
            maxDurationMs = maxDurationMs,
            onSeekTo = onSeekTo,
        )
    }
}
