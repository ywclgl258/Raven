package com.raven.app.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.ThumbUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenLoadingSkeleton
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RAVEN_TABULAR_FONT_FEATURE
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
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
    onOpenQueue: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onAuthorClick: (AuthorRef) -> Unit,
    onTagClick: (TagRef) -> Unit,
    onTagLongClick: (PlayerTagDisplay) -> Unit,
    onJumpBookmark: (TimeBookmark) -> Unit,
    onEditBookmark: (TimeBookmark) -> Unit,
    onDeleteBookmark: (TimeBookmark) -> Unit,
    onRelatedClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    onToggleLike: () -> Unit = {},
    onOpenCast: () -> Unit = {},
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val listState = rememberLazyListState()
    var descriptionExpanded by remember(uiState.mediaId) { mutableStateOf(false) }
    var commentsExpanded by remember(uiState.mediaId) { mutableStateOf(false) }
    val maxDurationForLinks = uiState.effectiveDurationMs.takeIf { it > 0L }
    val combinedTags = remember(uiState.tags, uiState.characters, uiState.categories) {
        (uiState.tags + uiState.characters + uiState.categories)
            .distinctBy { "${it.tag.namespace.lowercase()}:${it.tag.slug.lowercase()}" }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .testTag(PlayerTestTags.PLAYER_DETAIL_PANE),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        // 1. Sequential Queue Next confirmation card
        val queue = uiState.queue
        if (queue != null) {
            item(key = "queue_next_card") {
                SequentialQueueNextCard(
                    queue = queue,
                    onConfirmNext = onConfirmQueueNext,
                    onEndQueue = onEndQueue,
                    onOpenQueue = onOpenQueue,
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
                        top = if (queue != null) 0.dp else 14.dp,
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

        // 3. Action Bar (稍后看 / 收藏 / 点赞 / 下载 / 时间书签 / 分享 / 投屏 / 站外播放)
        item(key = "action_bar") {
            PlayerActionStrip(
                uiState = uiState,
                onToggleWatchLater = onToggleWatchLater,
                onToggleFavorite = onToggleFavorite,
                onToggleLike = onToggleLike,
                onOpenDownloadSheet = onOpenDownloadSheet,
                onOpenAddBookmark = onOpenAddBookmark,
                onShareClick = onShareClick,
                onOpenExternalPlayers = onOpenExternalPlayers,
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
                val descChevronAngle = animateFloatAsState(
                    targetValue = if (descriptionExpanded) 180f else 0f,
                    animationSpec = if (motionReduced) {
                        snap()
                    } else {
                        tween(durationMillis = 180, easing = RavenMotion.HarmonyDecelerationEasing)
                    },
                    label = "playerDescChevronRotation",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RavenShapes.CardShape)
                        .background(colors.surface1)
                        .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                        .animateContentSize(
                            animationSpec = if (motionReduced) snap() else RavenMotion.interactiveSpringSpec(),
                        )
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
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = if (descriptionExpanded) "收起简介" else "展开简介",
                            tint = colors.inkSecondary,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer { rotationZ = descChevronAngle.value },
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
                            text = "轻点浏览 · 长按订阅/屏蔽",
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
                val commentsChevronAngle = animateFloatAsState(
                    targetValue = if (commentsExpanded) 180f else 0f,
                    animationSpec = if (motionReduced) {
                        snap()
                    } else {
                        tween(durationMillis = 180, easing = RavenMotion.HarmonyDecelerationEasing)
                    },
                    label = "playerCommentsChevronRotation",
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RavenShapes.CardShape)
                        .background(colors.surface1)
                        .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                        .animateContentSize(
                            animationSpec = if (motionReduced) snap() else RavenMotion.interactiveSpringSpec(),
                        )
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
                                imageVector = Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = colors.inkSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .graphicsLayer { rotationZ = commentsChevronAngle.value },
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
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp),
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
                                modifier = Modifier
                                    .animateItem()
                                    .width(204.dp),
                            )
                        }
                    }
                }
            }
        }
        }

        RavenScrollToTopButton(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 22.dp),
        )
    }
}
