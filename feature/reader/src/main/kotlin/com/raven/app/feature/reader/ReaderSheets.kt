package com.raven.app.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId

/**
 * 85%-height Thumbnail Overview [RavenBottomSheet] with adaptive grid, crimson current-page rim,
 * bookmark badges, and one-tap jump.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderThumbnailsSheet(
    uiState: ReaderUiState,
    onLoadThumbnailBitmap: suspend (pageNumber: Int, targetSizePx: Int) -> Bitmap?,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val detail = uiState.detail
    val pagesByIndex = remember(detail?.pages) {
        detail?.pages?.associateBy { it.index }.orEmpty()
    }
    val bookmarkedPages = remember(uiState.thumbnailPageBookmarks) {
        uiState.thumbnailPageBookmarks.mapTo(hashSetOf()) { it.page }
    }
    val initialIndex = remember(uiState.pageNumbers, uiState.currentPage) {
        uiState.pageNumbers.indexOf(uiState.currentPage).coerceAtLeast(0)
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialIndex)

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "页面缩略图",
        subtitle = "当前第 ${uiState.currentPage} / ${uiState.totalPages} 页 · 点击直达",
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = Modifier.testTag(ReaderTestTags.READER_THUMBNAILS_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                state = gridState,
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            ) {
                items(
                    items = uiState.pageNumbers,
                    key = { pageNum -> "thumb_$pageNum" },
                ) { pageNumber ->
                    val isCurrent = pageNumber == uiState.currentPage
                    val loaded = uiState.bufferState.pages[pageNumber]
                    val pageMeta = pagesByIndex[pageNumber]
                    ReaderThumbnailCell(
                        siteId = uiState.siteId,
                        pageNumber = pageNumber,
                        pageMeta = pageMeta,
                        loadedBitmap = loaded?.bitmap,
                        offlineOnly = uiState.offlineOnly,
                        isCurrent = isCurrent,
                        isBookmarked = pageNumber in bookmarkedPages,
                        onLoadThumbnailBitmap = onLoadThumbnailBitmap,
                        onClick = { onSelectPage(pageNumber) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderChaptersSheet(
    uiState: ReaderUiState,
    onSelectChapter: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onOpenDetail: () -> Unit,
    onDismiss: () -> Unit,
) {
    val detail = uiState.detail ?: return
    // In strip mode the viewport may already be inside an appended chapter; highlight that one.
    val currentChapterHints = remember(detail.item, uiState.mediaId, uiState.displayMediaId) {
        listOfNotNull(
            uiState.displayMediaId,
            uiState.mediaId,
            detail.item.id,
            detail.item.extra["chapterId"],
            detail.item.extra["chapterUuid"],
            detail.item.extra["chapterHid"],
        )
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "选话",
        subtitle = detail.item.title,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.testTag(ReaderTestTags.READER_CHAPTERS_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            GalleryChapterPickerSection(
                chapters = uiState.chapters,
                loading = uiState.isChapterListLoading,
                error = uiState.chapterListError,
                currentChapterId = uiState.displayMediaId,
                currentChapterUrl = detail.item.extra["chapterUrl"].orEmpty(),
                currentChapterHints = currentChapterHints,
                onSelect = onSelectChapter,
                onRefresh = onRefresh,
                title = "章节列表",
                fillAvailableHeight = true,
                modifier = Modifier.weight(1f),
            )
            RavenGroupedSection(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                RavenGroupedRow(
                    title = "返回作品详情",
                    subtitle = "查看作品简介、收藏与下载",
                    icon = Icons.Outlined.Info,
                    showChevron = true,
                    onClick = onOpenDetail,
                )
            }
        }
    }
}

@Composable
internal fun ReaderThumbnailCell(
    siteId: SiteId,
    pageNumber: Int,
    pageMeta: GalleryPage?,
    loadedBitmap: Bitmap?,
    offlineOnly: Boolean,
    isCurrent: Boolean,
    isBookmarked: Boolean,
    onLoadThumbnailBitmap: suspend (pageNumber: Int, targetSizePx: Int) -> Bitmap?,
    onClick: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val remoteThumbUrl = pageMeta?.thumbUrl?.takeIf(String::isNotBlank)
        ?: pageMeta?.imageUrl?.takeIf { it.isNotBlank() && (!offlineOnly || siteId.isLocal) }
        ?: ""

    var localThumb by remember(pageNumber, loadedBitmap, remoteThumbUrl) {
        mutableStateOf<Bitmap?>(loadedBitmap)
    }

    LaunchedEffect(pageNumber, remoteThumbUrl, loadedBitmap) {
        if (loadedBitmap != null) {
            localThumb = loadedBitmap
        } else if (remoteThumbUrl.isBlank() || offlineOnly) {
            localThumb = onLoadThumbnailBitmap(pageNumber, 320)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RavenShapes.ControlShape)
            .background(colors.surface1)
            .border(
                width = if (isCurrent) 2.dp else 0.5.dp,
                color = if (isCurrent) colors.crimson else colors.hairline,
                shape = RavenShapes.ControlShape,
            )
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        val activeBitmap = localThumb
        when {
            activeBitmap != null -> {
                val imageBitmap = remember(activeBitmap) { activeBitmap.asImageBitmap() }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "第 $pageNumber 页",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            remoteThumbUrl.isNotBlank() -> {
                RavenImage(
                    url = remoteThumbUrl,
                    siteId = siteId,
                    contentDescription = "第 $pageNumber 页",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                            contentDescription = null,
                            tint = colors.inkTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "P$pageNumber",
                            style = RavenTabularTextStyle,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .clip(RavenShapes.BadgeShape)
                .background(if (isCurrent) colors.crimson else colors.badgeBackdrop)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = pageNumber.toString(),
                style = RavenTabularTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = colors.inkPrimary,
            )
        }

        if (isBookmarked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(colors.badgeBackdrop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = "已加书签",
                    tint = colors.crimson,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
