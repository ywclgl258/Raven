package com.raven.app.feature.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.raven.app.core.common.TimestampParser
import com.raven.app.core.designsystem.component.RavenCompactMediaRow
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenLoadingSkeleton
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RAVEN_TABULAR_FONT_FEATURE
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.CommentItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TimeBookmark

/**
 * Clickable text rendering `HH:MM:SS` / `MM:SS` timestamps parsed by [TimestampParser] as direct
 * Raven Crimson seek links without using deprecated `ClickableText`.
 */
@Composable
fun TimestampText(
    text: String,
    maxDurationMs: Long?,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val colors = LocalRavenColors.current
    val spans = remember(text, maxDurationMs) {
        TimestampParser.parseSpans(text, maxDurationMs?.takeIf { it > 0L })
    }

    val annotated = remember(text, spans, colors.crimson) {
        buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                if (span.start > cursor) {
                    append(text.substring(cursor, span.start))
                }
                val link = LinkAnnotation.Clickable(
                    tag = "timestamp_${span.positionMs}",
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = colors.crimson,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = RAVEN_TABULAR_FONT_FEATURE,
                        ),
                    ),
                    linkInteractionListener = { onSeekTo(span.positionMs) },
                )
                withLink(link) {
                    append(span.matchedText)
                }
                cursor = span.end
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }

    Text(
        text = annotated,
        style = style,
        color = colors.inkSecondary,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

/**
 * Scrollable rich video detail pane shown below the player in portrait and alongside the player
 * on wide screens (`>= 840dp`, hidden in fullscreen and PiP).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun PlayerDetailPane(
    uiState: PlayerUiState,
    compactRelatedList: Boolean,
    onToggleWatchLater: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDownloadSheet: () -> Unit,
    onOpenAddBookmark: () -> Unit,
    onShareClick: () -> Unit,
    onOpenExternalPlayers: () -> Unit,
    onConfirmQueueNext: () -> Unit,
    onEndQueue: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onAuthorClick: (AuthorRef) -> Unit,
    onTagClick: (TagRef) -> Unit,
    onTagLongClick: (PlayerTagDisplay) -> Unit,
    onJumpBookmark: (TimeBookmark) -> Unit,
    onEditBookmark: (TimeBookmark) -> Unit,
    onDeleteBookmark: (TimeBookmark) -> Unit,
    onRelatedClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    onOpenShelfPicker: () -> Unit = {},
    onOpenCast: () -> Unit = {},
) {
    val colors = LocalRavenColors.current
    var descriptionExpanded by remember(uiState.mediaId) { mutableStateOf(false) }
    var commentsExpanded by remember(uiState.mediaId) { mutableStateOf(false) }
    val maxDurationForLinks = uiState.effectiveDurationMs.takeIf { it > 0L }
    val combinedTags = remember(uiState.tags, uiState.characters, uiState.categories) {
        (uiState.tags + uiState.characters + uiState.categories)
            .distinctBy { "${it.tag.namespace.lowercase()}:${it.tag.slug.lowercase()}" }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .navigationBarsPadding()
            .testTag(PlayerTestTags.PLAYER_DETAIL_PANE),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Sequential Queue Next confirmation card
        val queue = uiState.queue
        if (queue != null && queue.hasNext) {
            item(key = "queue_next_card") {
                SequentialQueueNextCard(
                    queue = queue,
                    onConfirmNext = onConfirmQueueNext,
                    onEndQueue = onEndQueue,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                )
            }
        }

        // 2. Title & Metadata Header
        item(key = "header_meta") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = if (queue != null && queue.hasNext) 0.dp else 14.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = uiState.title.ifBlank { "加载中…" },
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.inkPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                VideoMetadataStrip(uiState = uiState)

                if (uiState.authors.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        uiState.authors.forEach { author ->
                            Row(
                                modifier = Modifier
                                    .clip(RavenShapes.PillShape)
                                    .background(colors.surface1)
                                    .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                                    .ravenSpringPress()
                                    .clickable(role = Role.Button) { onAuthorClick(author) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = colors.inkSecondary,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = author.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Action Bar (稍后看 / 收藏 / 下载 / 时间书签 / 分享 / 站外播放)
        item(key = "action_bar") {
            PlayerActionStrip(
                uiState = uiState,
                onToggleWatchLater = onToggleWatchLater,
                onToggleFavorite = onToggleFavorite,
                onOpenDownloadSheet = onOpenDownloadSheet,
                onOpenAddBookmark = onOpenAddBookmark,
                onShareClick = onShareClick,
                onOpenExternalPlayers = onOpenExternalPlayers,
                onOpenShelfPicker = onOpenShelfPicker,
                onOpenCast = onOpenCast,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // Loading skeleton while detail metadata is still loading
        if (uiState.isLoadingDetail && uiState.detail == null) {
            item(key = "detail_loading_skeleton") {
                RavenLoadingSkeleton(rowCount = 3)
            }
        }

        // 4. Expandable Description with clickable TimestampText
        val description = uiState.detail?.description?.trim().orEmpty()
        if (description.isNotEmpty()) {
            item(key = "description_card") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RavenShapes.CardShape)
                        .background(colors.surface1)
                        .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                        .clickable { descriptionExpanded = !descriptionExpanded }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "简介",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.inkPrimary,
                        )
                        Icon(
                            imageVector = if (descriptionExpanded) {
                                Icons.Rounded.ExpandLess
                            } else {
                                Icons.Rounded.ExpandMore
                            },
                            contentDescription = if (descriptionExpanded) "收起简介" else "展开简介",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TimestampText(
                        text = description,
                        maxDurationMs = maxDurationForLinks,
                        onSeekTo = onSeekTo,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 5. Tags / Categories / Characters Section
        if (combinedTags.isNotEmpty()) {
            item(key = "tags_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "标签",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "轻点浏览 · 长按收藏/屏蔽",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkTertiary,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        combinedTags.forEach { tagDisplay ->
                            PlayerTagChip(
                                tagDisplay = tagDisplay,
                                onClick = { onTagClick(tagDisplay.tag) },
                                onLongClick = { onTagLongClick(tagDisplay) },
                            )
                        }
                    }
                }
            }
        }

        // 6. Time Bookmarks Section
        item(key = "time_bookmarks_section") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "时间书签 (${uiState.timeBookmarks.size})",
                        style = MaterialTheme.typography.titleMedium.tabular(),
                        color = colors.inkPrimary,
                    )
                    TextButton(
                        onClick = onOpenAddBookmark,
                        modifier = Modifier.testTag(PlayerTestTags.PLAYER_ADD_BOOKMARK_BUTTON),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = colors.crimson,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "记录当前进度",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.crimson,
                        )
                    }
                }

                if (uiState.timeBookmarks.isEmpty()) {
                    Text(
                        text = "暂无时间书签，可在播放时快速标记精彩节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkTertiary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    RavenGroupedSection(modifier = Modifier.padding(horizontal = 16.dp)) {
                        uiState.timeBookmarks.forEachIndexed { index, bookmark ->
                            if (index > 0) RavenGroupedDivider()
                            TimeBookmarkRow(
                                bookmark = bookmark,
                                onJump = { onJumpBookmark(bookmark) },
                                onEdit = { onEditBookmark(bookmark) },
                                onDelete = { onDeleteBookmark(bookmark) },
                            )
                        }
                    }
                }
            }
        }

        // 7. Expandable Comments Section with clickable TimestampText
        if (uiState.comments.isNotEmpty()) {
            item(key = "comments_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RavenShapes.CardShape)
                        .background(colors.surface1)
                        .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { commentsExpanded = !commentsExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "评论 (${uiState.comments.size})",
                            style = MaterialTheme.typography.titleMedium.tabular(),
                            color = colors.inkPrimary,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = if (commentsExpanded) "收起" else "展开",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.inkSecondary,
                            )
                            Icon(
                                imageVector = if (commentsExpanded) {
                                    Icons.Rounded.ExpandLess
                                } else {
                                    Icons.Rounded.ExpandMore
                                },
                                contentDescription = null,
                                tint = colors.inkSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    val displayedComments = if (commentsExpanded) {
                        uiState.comments
                    } else {
                        uiState.comments.take(2)
                    }
                    displayedComments.forEachIndexed { index, comment ->
                        if (index > 0) {
                            RavenGroupedDivider()
                        }
                        PlayerCommentRow(
                            comment = comment,
                            maxDurationMs = maxDurationForLinks,
                            onSeekTo = onSeekTo,
                        )
                    }
                }
            }
        }

        // 8. Related Videos (tap switches IN-PLACE for videos!)
        if (uiState.relatedItems.isNotEmpty()) {
            item(key = "related_header") {
                RavenSectionHeader(
                    title = "相关推荐",
                    subtitle = "点击视频将直接原位切换播放",
                )
            }

            if (compactRelatedList) {
                items(
                    items = uiState.relatedItems,
                    key = { "related_row_${it.compositeKey}" },
                ) { item ->
                    RavenCompactMediaRow(
                        item = item,
                        onClick = { onRelatedClick(item) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                item(key = "related_shelf") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = uiState.relatedItems,
                            key = { "related_card_${it.compositeKey}" },
                        ) { item ->
                            RavenVideoCard(
                                item = item,
                                onClick = { onRelatedClick(item) },
                                modifier = Modifier.width(204.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SequentialQueueNextCard(
    queue: PlayerQueueUiState,
    onConfirmNext: () -> Unit,
    onEndQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val nextItem = queue.nextItem ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.crimson.copy(alpha = 0.35f), RavenShapes.CardShape)
            .padding(14.dp)
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
                    text = "顺序队列 ${queue.currentIndex + 1}/${queue.totalCount}",
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
                    url = nextItem.coverUrl,
                    siteId = nextItem.siteId,
                    contentDescription = nextItem.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "下一项 · ${queue.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = nextItem.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoMetadataStrip(
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

@Composable
private fun PlayerActionStrip(
    uiState: PlayerUiState,
    onToggleWatchLater: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDownloadSheet: () -> Unit,
    onOpenAddBookmark: () -> Unit,
    onShareClick: () -> Unit,
    onOpenExternalPlayers: () -> Unit,
    onOpenShelfPicker: () -> Unit = {},
    onOpenCast: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val downloadLabel = when {
        uiState.isLocal -> "本地"
        uiState.isOfflineDownloaded -> "已下载"
        uiState.downloadStatus == DownloadStatus.DOWNLOADING ->
            "${(uiState.downloadProgress * 100f).toInt()}%"
        uiState.downloadStatus == DownloadStatus.QUEUED -> "队列中"
        else -> "下载"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailActionButton(
            icon = if (uiState.inWatchLater) Icons.Rounded.WatchLater else Icons.Outlined.WatchLater,
            label = "稍后看",
            active = uiState.inWatchLater,
            onClick = onToggleWatchLater,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_WATCH_LATER),
        )
        DetailActionButton(
            icon = if (uiState.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            label = if (uiState.isFavorite) "已收藏" else "收藏",
            active = uiState.isFavorite,
            onClick = onToggleFavorite,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_FAVORITE),
        )
        DetailActionButton(
            icon = if (uiState.isOfflineDownloaded) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
            label = downloadLabel,
            active = uiState.isOfflineDownloaded,
            enabled = !uiState.isLocal,
            onClick = onOpenDownloadSheet,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_DOWNLOAD),
        )
        DetailActionButton(
            icon = if (uiState.timeBookmarks.isNotEmpty()) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkAdd,
            label = "书签",
            active = uiState.timeBookmarks.isNotEmpty(),
            onClick = onOpenAddBookmark,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_BOOKMARK),
        )
        DetailActionButton(
            icon = Icons.Rounded.Share,
            label = "分享",
            enabled = uiState.url.isNotBlank(),
            onClick = onShareClick,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_SHARE),
        )
        DetailActionButton(
            icon = Icons.Rounded.CollectionsBookmark,
            label = "片单",
            active = uiState.containingShelfIds.any { it != com.raven.app.core.model.Shelf.WATCH_LATER_ID },
            onClick = onOpenShelfPicker,
            modifier = Modifier.weight(1f),
        )
        DetailActionButton(
            icon = Icons.Rounded.Cast,
            label = if (uiState.activeCastDevice != null) "投屏中" else "投屏",
            active = uiState.activeCastDevice != null,
            onClick = onOpenCast,
            modifier = Modifier.weight(1f),
        )
        DetailActionButton(
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            label = "站外",
            enabled = uiState.externalPlayers.isNotEmpty() || uiState.url.startsWith("http"),
            onClick = onOpenExternalPlayers,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_EXTERNAL),
        )
    }
}

@Composable
private fun DetailActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalRavenColors.current
    val tint = when {
        !enabled -> colors.inkTertiary
        active -> colors.crimson
        else -> colors.inkPrimary
    }

    Column(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RavenShapes.ControlShape)
            .then(
                if (enabled) {
                    Modifier
                        .ravenSpringPress()
                        .clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = if (active && enabled) colors.crimson else if (enabled) colors.inkSecondary else colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerTagChip(
    tagDisplay: PlayerTagDisplay,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val container = when {
        tagDisplay.isBlocked -> colors.error.copy(alpha = 0.14f)
        tagDisplay.isFavorited -> colors.crimson.copy(alpha = 0.15f)
        else -> colors.surface1
    }
    val border = when {
        tagDisplay.isBlocked -> colors.error.copy(alpha = 0.40f)
        tagDisplay.isFavorited -> colors.crimson.copy(alpha = 0.40f)
        else -> colors.hairline
    }
    val textColor = when {
        tagDisplay.isBlocked -> colors.error
        tagDisplay.isFavorited -> colors.crimson
        else -> colors.inkPrimary
    }

    Row(
        modifier = Modifier
            .clip(RavenShapes.PillShape)
            .background(container)
            .border(0.5.dp, border, RavenShapes.PillShape)
            .ravenSpringPress()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (tagDisplay.isBlocked) {
            Icon(
                imageVector = Icons.Rounded.Block,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(13.dp),
            )
        } else if (tagDisplay.isFavorited) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = tagDisplay.displayLabel,
            style = MaterialTheme.typography.labelMedium.copy(
                textDecoration = if (tagDisplay.isBlocked) TextDecoration.LineThrough else TextDecoration.None,
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TimeBookmarkRow(
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
private fun PlayerCommentRow(
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
