package com.raven.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.ReadingProgress

/** 连载章节选择区：章节列表分组与章节瓦片。 */

@Composable
internal fun GalleryChapterPickerSection(
    chapters: List<MediaItem>,
    loading: Boolean,
    error: String?,
    currentChapterId: String,
    currentChapterUrl: String,
    currentChapterHints: List<String>,
    onSelect: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "选集",
    fillAvailableHeight: Boolean = false,
    readState: ChapterPickerReadState = ChapterPickerReadState(),
    onLongSelect: ((MediaItem) -> Unit)? = null,
    onOpenNextUnread: ((MediaItem) -> Unit)? = null,
    /** Subtitle suffix for the tap gesture; the detail page reads directly, the reader switches. */
    selectHint: String = "点击切换",
) {
    val colors = LocalRavenColors.current
    var ascending by rememberSaveable { mutableStateOf(true) }
    val ordered = remember(chapters, ascending) {
        if (ascending) chapters else chapters.asReversed()
    }
    val currentIndex = remember(ordered, currentChapterId, currentChapterUrl, currentChapterHints) {
        ordered.indexOfFirst { chapter ->
            isMatchingSerialChapter(chapter, currentChapterId, currentChapterUrl, currentChapterHints)
        }.coerceAtLeast(0)
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = currentIndex)
    LaunchedEffect(currentIndex, ordered.size) {
        if (currentIndex > 0) gridState.scrollToItem(currentIndex)
    }
    val completedCount = chapters.count { readState.isCompleted(it.id) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                )
                Text(
                    text = when {
                        loading -> "章节载入中…"
                        chapters.isEmpty() -> "暂无章节"
                        completedCount > 0 -> "共 ${chapters.size} 章 · 已读 $completedCount 章"
                        else -> "共 ${chapters.size} 章 · $selectHint"
                    },
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = colors.inkSecondary,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onOpenNextUnread != null && chapters.isNotEmpty()) {
                    RavenFilterChip(
                        label = readState.nextUnreadChapter?.let { "下一未读" }
                            ?: readState.nextUnreadBoundaryLabel
                            ?: "下一未读",
                        selected = false,
                        leadingIcon = Icons.Rounded.PlayArrow,
                        enabled = readState.nextUnreadChapter != null,
                        onClick = {
                            readState.nextUnreadChapter?.let { chapter ->
                                onOpenNextUnread.invoke(chapter)
                            }
                        },
                        modifier = Modifier.testTag("gallery_chapter_next_unread"),
                    )
                }
                RavenFilterChip(
                    label = if (ascending) "正序" else "倒序",
                    selected = false,
                    leadingIcon = Icons.AutoMirrored.Rounded.Sort,
                    onClick = { ascending = !ascending },
                    modifier = Modifier.testTag("chapter_sort_chip"),
                )
            }
        }

        when {
            loading && chapters.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.crimson)
            }
            !error.isNullOrBlank() && chapters.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                    TextButton(onClick = onRefresh) {
                        Text("重试", color = colors.crimson)
                    }
                }
            }
            chapters.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无章节",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .then(
                        if (fillAvailableHeight) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.heightIn(max = 520.dp)
                        },
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(ordered, key = { it.id }) { chapter ->
                    val selected = isMatchingSerialChapter(
                        chapter = chapter,
                        currentChapterId = currentChapterId,
                        currentChapterUrl = currentChapterUrl,
                        hints = currentChapterHints,
                    )
                    GalleryChapterTile(
                        title = chapter.title,
                        selected = selected,
                        progress = readState.progressByMediaId[chapter.id],
                        onClick = { onSelect(chapter) },
                        onLongClick = onLongSelect?.let { open -> { open(chapter) } },
                    )
                }
            }
        }
    }
}


@Composable
internal fun GalleryChapterTile(
    title: String,
    selected: Boolean,
    progress: ReadingProgress?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val container = if (selected) colors.crimson.copy(alpha = 0.16f) else colors.surface1
    val borderColor = if (selected) colors.crimson else colors.hairline
    val progressLabel = chapterProgressLabel(progress)
    val completed = progress?.completed == true
    val markerColor = when {
        selected -> colors.crimson
        completed -> colors.success
        else -> colors.inkSecondary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RavenShapes.SmallShape)
            .background(container)
            .border(if (selected) 1.dp else 0.5.dp, borderColor, RavenShapes.SmallShape)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.crimson else colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (progressLabel != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (completed) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = markerColor,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = markerColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
