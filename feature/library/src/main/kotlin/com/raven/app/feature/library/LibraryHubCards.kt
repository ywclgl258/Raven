package com.raven.app.feature.library


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.Shelf

/** 我的页卡片层：智能推荐卡、片单摘要卡与智能片单摘要卡。从 `LibraryHubScreen.kt` 拆出，包名与 `testTag` 契约不变。 */
@Composable
internal fun SmartRecommendationGridCard(
    card: SmartRecommendationCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = modifier
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (card.item.kind.isVideo) 16f / 10f else 0.74f)
                .background(colors.surface2),
        ) {
            RavenImage(
                url = card.item.coverUrl,
                siteId = card.item.siteId,
                contentDescription = card.item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RavenShapes.PillShape)
                    .background(colors.canvas.copy(alpha = 0.78f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = card.siteLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkPrimary,
                    maxLines = 1,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = card.item.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = card.reason,
                style = MaterialTheme.typography.labelSmall,
                color = colors.crimson,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ShelfSummaryCard(
    summary: ShelfWithSummary,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onTogglePinClick: () -> Unit,
    onDeleteClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val shelf = summary.shelf
    val isWatchLater = shelf.id == Shelf.WATCH_LATER_ID

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp)
            .testTag("shelf_card_${shelf.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover preview box
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RavenShapes.ControlShape)
                .background(colors.surface2)
                .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            contentAlignment = Alignment.Center,
        ) {
            val cover = summary.previewCoverUrls.firstOrNull()
            if (!cover.isNullOrBlank()) {
                RavenImage(
                    url = cover,
                    contentDescription = shelf.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = if (isWatchLater) colors.crimson else colors.inkSecondary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = shelf.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (shelf.isPinned) {
                    RavenStatusPill(
                        text = "置顶",
                        tone = RavenStatusTone.ACCENT,
                        icon = Icons.Outlined.PushPin,
                    )
                } else if (isWatchLater) {
                    RavenStatusPill(
                        text = "内置",
                        tone = RavenStatusTone.NEUTRAL,
                    )
                }
            }

            if (shelf.note.isNotBlank()) {
                Text(
                    text = shelf.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = buildString {
                    append("${summary.itemCount} 项作品")
                    if (summary.completedCount > 0) {
                        append(" · 已完成 ${summary.completedCount}")
                    }
                },
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = colors.inkTertiary,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onTogglePinClick,
                modifier = Modifier
                    .size(40.dp)
                    .ravenBurstFeedback(trigger = shelf.isPinned, burstColor = colors.crimson),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = if (shelf.isPinned) "取消置顶" else "置顶",
                    tint = if (shelf.isPinned) colors.crimson else colors.inkTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onEditClick,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "编辑片单",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (onDeleteClick != null) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "删除片单",
                        tint = colors.inkTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SmartShelfSummaryCard(
    summary: SmartShelfSummary,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val smart = summary.smartShelf

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp)
            .testTag("smart_shelf_card_${smart.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RavenShapes.ControlShape)
                .background(colors.crimson.copy(alpha = 0.14f))
                .border(0.5.dp, colors.crimson.copy(alpha = 0.30f), RavenShapes.ControlShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(24.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = smart.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (smart.isPinned) {
                    RavenStatusPill(text = "置顶", tone = RavenStatusTone.ACCENT)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RavenStatusPill(text = smart.rule.displayLabel(), tone = RavenStatusTone.NEUTRAL)
                smart.mediaKindFilter?.let { kind ->
                    RavenStatusPill(text = kind.displayLabel(), tone = RavenStatusTone.NEUTRAL)
                }
                summary.siteLabel?.let { label ->
                    RavenStatusPill(text = label, tone = RavenStatusTone.NEUTRAL)
                }
            }

            Text(
                text = "实时匹配 ${summary.matchCount} 项作品",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = colors.inkTertiary,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEditClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "编辑智能片单",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "删除智能片单",
                    tint = colors.inkTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
