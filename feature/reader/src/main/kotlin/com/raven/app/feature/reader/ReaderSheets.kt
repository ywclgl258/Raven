package com.raven.app.feature.reader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.common.PageSelectionParser
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSliderRow
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenFrostedSurface
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.download.PlaybackQueueSession
import com.raven.app.core.media.reader.ExportMode
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId
import kotlin.math.roundToInt

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
private fun ReaderThumbnailCell(
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

/**
 * Page Bookmarks quick jump & management [RavenBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderBookmarksSheet(
    currentPage: Int,
    isCurrentPageBookmarked: Boolean,
    bookmarks: List<PageBookmark>,
    onToggleCurrentPageBookmark: () -> Unit,
    onJumpToBookmark: (PageBookmark) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "页码书签",
        subtitle = "当前位于第 $currentPage 页",
        trailingAction = {
            TextButton(onClick = onToggleCurrentPageBookmark) {
                RavenMorphIcon(
                    glyph = if (isCurrentPageBookmarked) RavenMorphIcons.BookmarkFilled else RavenMorphIcons.BookmarkOutline,
                    contentDescription = null,
                    tint = colors.crimson,
                    size = 18.dp,
                    springPreset = MorphSpringPreset.SNAPPY,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isCurrentPageBookmarked) "取消本页" else "添加本页",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkPrimary,
                )
            }
        },
        modifier = Modifier.testTag(ReaderTestTags.READER_BOOKMARKS_SHEET),
    ) {
        if (bookmarks.isEmpty()) {
            RavenEmptyState(
                title = "暂无页码书签",
                message = "点击右上角「添加本页」可随时记录当前阅读页码与滚动位置。",
                icon = Icons.Outlined.BookmarkBorder,
                actionLabel = "添加第 $currentPage 页书签",
                onActionClick = onToggleCurrentPageBookmark,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RavenGroupedSection {
                    bookmarks.forEachIndexed { index, bookmark ->
                        if (index > 0) RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedRow(
                            title = "第 ${bookmark.page} 页",
                            subtitle = bookmark.note.takeIf(String::isNotBlank)
                                ?: if (bookmark.page == currentPage) "当前所在页" else null,
                            icon = Icons.Filled.Bookmark,
                            iconTint = if (bookmark.page == currentPage) colors.crimson else colors.inkPrimary,
                            onClick = { onJumpToBookmark(bookmark) },
                            trailingContent = {
                                IconButton(onClick = { onDeleteBookmark(bookmark.id) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "删除书签",
                                        tint = colors.inkSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Single-Screen Apple HIG Grouped Reader Settings [RavenBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSettingsSheet(
    uiState: ReaderUiState,
    onSetPerBookOverride: (Boolean) -> Unit,
    onResetToSiteDefault: () -> Unit,
    onSelectMode: (ReaderMode) -> Unit,
    onSelectFit: (ReaderFit) -> Unit,
    onCropChange: (Int) -> Unit,
    onCombineCoverChange: (Boolean) -> Unit,
    onShowStatusBarChange: (Boolean) -> Unit,
    onTapZonesEnabledChange: (Boolean) -> Unit,
    onAutoSplitWidePagesChange: (Boolean) -> Unit,
    onPreferOriginalChange: (Boolean) -> Unit,
    onBrightnessPercentChange: (Int) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onVolumeKeyPagingChange: (Boolean) -> Unit,
    onReverseVolumeKeysChange: (Boolean) -> Unit,
    onAutoPageIntervalChange: (Int) -> Unit,
    onSlideshowOrderChange: (SlideshowOrder) -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleImageFavorite: () -> Unit,
    onExportCurrentPage: (ExportMode) -> Unit,
    onExportCbz: (rawRangeOrNull: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val prefs = uiState.preferences
    var cbzExpanded by rememberSaveable { mutableStateOf(false) }
    var cbzRangeAll by rememberSaveable { mutableStateOf(true) }
    var cbzRangeInput by rememberSaveable(uiState.totalPages) {
        mutableStateOf("1-${uiState.totalPages.coerceAtLeast(1)}")
    }

    val cbzValidationError = remember(cbzRangeAll, cbzRangeInput, uiState.totalPages) {
        if (cbzRangeAll) {
            null
        } else {
            runCatching { PageSelectionParser.parse(cbzRangeInput, uiState.totalPages.coerceAtLeast(1)) }
                .exceptionOrNull()
                ?.let { "页码格式无效（1..${uiState.totalPages}，如 1, 3, 5-8）" }
        }
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "阅读设置",
        subtitle = if (uiState.isPerBookOverride) {
            "本书独立设置 · ${uiState.siteDisplayName}"
        } else {
            "来源默认设置 · ${uiState.siteDisplayName}"
        },
        modifier = Modifier.testTag(ReaderTestTags.READER_SETTINGS_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Scope: Per-book override vs site default
            RavenGroupedSection(title = "设置范围") {
                RavenGroupedSwitchRow(
                    title = "仅对本书生效",
                    subtitle = if (uiState.isPerBookOverride) {
                        "修改仅保存在当前作品，不影响站点默认"
                    } else {
                        "当前修改将作为「${uiState.siteDisplayName}」默认阅读配置"
                    },
                    icon = Icons.Outlined.Tune,
                    checked = uiState.isPerBookOverride,
                    onCheckedChange = onSetPerBookOverride,
                )
                val motionReduced = LocalMotionReduced.current
                AnimatedVisibility(
                    visible = uiState.isPerBookOverride,
                    enter = if (motionReduced) EnterTransition.None else expandVertically(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeIn(tween(140)),
                    exit = if (motionReduced) ExitTransition.None else shrinkVertically(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeOut(tween(110)),
                ) {
                    Column {
                        RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedRow(
                            title = "恢复来源默认",
                            subtitle = "清除本书独立配置并继承来源默认设置",
                            icon = Icons.Rounded.RestartAlt,
                            iconTint = colors.crimson,
                            onClick = onResetToSiteDefault,
                        )
                    }
                }
            }

            // 2. Reading Mode, Fit, Crop, Spread & Original Images
            RavenGroupedSection(title = "排版与画面") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "阅读模式",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.inkPrimary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ReaderMode.entries.forEach { mode ->
                            RavenFilterChip(
                                label = readerModeLabel(mode),
                                selected = prefs.mode == mode,
                                onClick = { onSelectMode(mode) },
                            )
                        }
                    }
                    if (prefs.mode == ReaderMode.SPREAD && !uiState.isLandscapeViewport) {
                        Text(
                            text = "当前为竖屏，双页模式已自动回退为单页显示；旋转横屏后立即双页并排。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                    }
                }
                RavenGroupedDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "画面缩放适配",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.inkPrimary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ReaderFit.entries.forEach { fit ->
                            RavenFilterChip(
                                label = readerFitLabel(fit),
                                selected = prefs.fit == fit,
                                onClick = { onSelectFit(fit) },
                            )
                        }
                    }
                }
                RavenGroupedDivider()
                RavenGroupedSliderRow(
                    title = "白边裁切",
                    subtitle = "裁切页面四周留白（0%–20%）",
                    icon = Icons.Outlined.Crop,
                    value = prefs.cropPercent.toFloat(),
                    onValueChange = { onCropChange(it.roundToInt()) },
                    valueLabel = "${prefs.cropPercent}%",
                    valueRange = 0f..20f,
                    steps = 19,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedSwitchRow(
                    title = "双页合并封面",
                    subtitle = "横屏双页时封面（第 1 页）与第 2 页并排展示",
                    icon = Icons.Outlined.ViewAgenda,
                    checked = prefs.combineCover,
                    onCheckedChange = onCombineCoverChange,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedSwitchRow(
                    title = "沉浸角标状态栏",
                    subtitle = "隐藏控制栏时在角落显示时间、电量与页码",
                    checked = uiState.showStatusBar,
                    onCheckedChange = onShowStatusBarChange,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedSwitchRow(
                    title = "三区点按翻页",
                    subtitle = "左 28% / 右 28% 区域点按翻页，中间 44% 切换菜单栏",
                    checked = uiState.tapZonesEnabled,
                    onCheckedChange = onTapZonesEnabledChange,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedSwitchRow(
                    title = "自动分割宽幅跨页",
                    subtitle = "竖屏单页阅读时自动拆分横向双页跨图以便逐半页阅读",
                    checked = uiState.autoSplitWidePages,
                    onCheckedChange = onAutoSplitWidePagesChange,
                )
                if (uiState.supportsOriginalImages) {
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "优先加载原图",
                        subtitle = "请求站点未压缩原图，清晰度更高但更消耗流量与配额",
                        icon = Icons.Outlined.HighQuality,
                        checked = prefs.preferOriginalImages,
                        onCheckedChange = onPreferOriginalChange,
                    )
                }
            }

            // 3. Screen, Brightness & Volume Keys
            RavenGroupedSection(title = "屏幕与物理按键") {
                val motionReduced = LocalMotionReduced.current
                val followSystemBrightness = prefs.brightnessPercent < 0
                RavenGroupedSwitchRow(
                    title = "亮度跟随系统",
                    icon = Icons.Outlined.Brightness6,
                    checked = followSystemBrightness,
                    onCheckedChange = { follow ->
                        onBrightnessPercentChange(if (follow) -1 else 60)
                    },
                )
                AnimatedVisibility(
                    visible = !followSystemBrightness,
                    enter = if (motionReduced) EnterTransition.None else expandVertically(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeIn(tween(140)),
                    exit = if (motionReduced) ExitTransition.None else shrinkVertically(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeOut(tween(110)),
                ) {
                    Column {
                        RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedSliderRow(
                            title = "阅读器屏幕亮度",
                            value = prefs.brightnessPercent.coerceIn(0, 100).toFloat(),
                            onValueChange = { onBrightnessPercentChange(it.roundToInt()) },
                            valueLabel = "${prefs.brightnessPercent.coerceIn(0, 100)}%",
                            valueRange = 0f..100f,
                        )
                    }
                }
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedSwitchRow(
                    title = "阅读时保持屏幕常亮",
                    icon = Icons.Outlined.ScreenLockPortrait,
                    checked = prefs.keepScreenOn,
                    onCheckedChange = onKeepScreenOnChange,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedSwitchRow(
                    title = "音量键翻页",
                    subtitle = "短按翻页，长按直达首尾页",
                    icon = Icons.Outlined.VolumeUp,
                    checked = prefs.volumeKeyPaging,
                    onCheckedChange = onVolumeKeyPagingChange,
                )
                AnimatedVisibility(
                    visible = prefs.volumeKeyPaging,
                    enter = if (motionReduced) EnterTransition.None else expandVertically(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeIn(tween(140)),
                    exit = if (motionReduced) ExitTransition.None else shrinkVertically(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeOut(tween(110)),
                ) {
                    Column {
                        RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedSwitchRow(
                            title = "反转音量键方向",
                            subtitle = "音量 + 翻至下一页，音量 - 返回上一页",
                            checked = prefs.reverseVolumeKeys,
                            onCheckedChange = onReverseVolumeKeysChange,
                        )
                    }
                }
            }

            // 4. Auto-Page & Slideshow
            RavenGroupedSection(
                title = "自动翻页与幻灯片",
                footer = "自动翻页会等待当前页完成渲染后再开始倒计时，避免跳过未加载完成的页面。",
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoMode,
                            contentDescription = null,
                            tint = if (uiState.autoPageIntervalSeconds > 0) colors.crimson else colors.inkSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "翻页间隔",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.inkPrimary,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ReaderViewModel.ALLOWED_AUTO_PAGE_INTERVALS.forEach { sec ->
                            RavenFilterChip(
                                label = if (sec == 0) "关闭" else "${sec}s",
                                selected = uiState.autoPageIntervalSeconds == sec,
                                onClick = { onAutoPageIntervalChange(sec) },
                            )
                        }
                    }
                }
                RavenGroupedDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "幻灯片顺序",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.inkPrimary,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RavenFilterChip(
                            label = "顺序播放",
                            selected = uiState.slideshowOrder == SlideshowOrder.SEQUENTIAL,
                            onClick = { onSlideshowOrderChange(SlideshowOrder.SEQUENTIAL) },
                        )
                        RavenFilterChip(
                            label = "随机浏览",
                            selected = uiState.slideshowOrder == SlideshowOrder.RANDOM,
                            onClick = { onSlideshowOrderChange(SlideshowOrder.RANDOM) },
                        )
                    }
                }
            }

            // 5. Current Page & Export Actions (Inline CBZ range selector)
            RavenGroupedSection(title = "当前页与导出") {
                ReaderMorphGroupedRow(
                    title = if (uiState.isCurrentPageBookmarked) "取消本页书签" else "添加本页书签",
                    subtitle = "第 ${uiState.currentPage} 页",
                    onClick = onToggleBookmark,
                    leadingIcon = {
                        RavenMorphIcon(
                            glyph = if (uiState.isCurrentPageBookmarked) RavenMorphIcons.BookmarkFilled else RavenMorphIcons.BookmarkOutline,
                            tint = if (uiState.isCurrentPageBookmarked) colors.crimson else colors.inkPrimary,
                            size = 18.dp,
                            springPreset = MorphSpringPreset.SNAPPY,
                        )
                    },
                )
                RavenGroupedDivider(insetWithIcon = true)
                ReaderMorphGroupedRow(
                    title = if (uiState.isCurrentPageImageFavorited) "取消收藏当前单图" else "收藏当前单图",
                    subtitle = "将第 ${uiState.currentPage} 页加入单图收藏库",
                    onClick = onToggleImageFavorite,
                    iconBadgeModifier = Modifier.ravenBurstFeedback(trigger = uiState.isCurrentPageImageFavorited),
                    leadingIcon = {
                        RavenMorphIcon(
                            glyph = if (uiState.isCurrentPageImageFavorited) RavenMorphIcons.HeartFilled else RavenMorphIcons.HeartOutline,
                            tint = if (uiState.isCurrentPageImageFavorited) colors.crimson else colors.inkPrimary,
                            size = 18.dp,
                            springPreset = MorphSpringPreset.BOUNCY,
                        )
                    },
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "保存当前原图",
                    subtitle = "通过系统文件选择器保存第 ${uiState.currentPage} 页原图",
                    icon = Icons.Outlined.SaveAlt,
                    enabled = !uiState.isExporting,
                    onClick = {
                        onDismiss()
                        onExportCurrentPage(ExportMode.SAVE_IMAGES)
                    },
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "分享当前图片",
                    subtitle = "调用系统分享发送第 ${uiState.currentPage} 页",
                    icon = Icons.Outlined.Share,
                    enabled = !uiState.isExporting,
                    onClick = {
                        onDismiss()
                        onExportCurrentPage(ExportMode.SHARE_IMAGES)
                    },
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "导出 CBZ 压缩包",
                    subtitle = "支持整本或自定义页码范围打包",
                    icon = Icons.Outlined.Archive,
                    showChevron = !cbzExpanded,
                    enabled = !uiState.isExporting,
                    onClick = { cbzExpanded = !cbzExpanded },
                )
                val motionReduced = LocalMotionReduced.current
                AnimatedVisibility(
                    visible = cbzExpanded,
                    enter = if (motionReduced) EnterTransition.None else expandVertically(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeIn(tween(140)),
                    exit = if (motionReduced) ExitTransition.None else shrinkVertically(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeOut(tween(110)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RavenFilterChip(
                                label = "整本 (${uiState.totalPages} 页)",
                                selected = cbzRangeAll,
                                onClick = { cbzRangeAll = true },
                            )
                            RavenFilterChip(
                                label = "自定义选页",
                                selected = !cbzRangeAll,
                                onClick = { cbzRangeAll = false },
                            )
                        }
                        AnimatedVisibility(
                            visible = !cbzRangeAll,
                            enter = if (motionReduced) EnterTransition.None else expandVertically(tween(160, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeIn(tween(120)),
                            exit = if (motionReduced) ExitTransition.None else shrinkVertically(tween(140, easing = RavenMotion.HarmonyDecelerationEasing)) + fadeOut(tween(100)),
                        ) {
                            OutlinedTextField(
                                value = cbzRangeInput,
                                onValueChange = { cbzRangeInput = it },
                                label = { Text("页码范围") },
                                placeholder = { Text("例如：1, 3, 5-8") },
                                singleLine = true,
                                isError = cbzValidationError != null,
                                supportingText = cbzValidationError?.let { err ->
                                    { Text(text = err, color = colors.error) }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.crimson,
                                    unfocusedBorderColor = colors.outlineSubtle,
                                    errorBorderColor = colors.error,
                                    focusedTextColor = colors.inkPrimary,
                                    unfocusedTextColor = colors.inkPrimary,
                                    cursorColor = colors.crimson,
                                ),
                                shape = RavenShapes.ControlShape,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Button(
                            onClick = {
                                onDismiss()
                                onExportCbz(if (cbzRangeAll) null else cbzRangeInput)
                            },
                            enabled = !uiState.isExporting && (cbzRangeAll || cbzValidationError == null),
                            shape = RavenShapes.PillShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primaryCtaContainer,
                                contentColor = colors.primaryCtaInk,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .ravenSpringPress(),
                        ) {
                            Text(
                                text = if (cbzRangeAll) "立即导出整本 CBZ" else "导出选定页码 CBZ",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ReaderMorphGroupedRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    iconBadgeModifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val rowBackdropColor by animateColorAsState(
        targetValue = when {
            pressed -> colors.surface2.copy(alpha = 0.72f)
            hovered || focused -> colors.surface2.copy(alpha = 0.42f)
            else -> Color.Transparent
        },
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
        label = "readerMorphGroupedRowBackdrop",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(rowBackdropColor)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = RectangleShape,
                enabled = true,
                glowColor = colors.interactiveGlow,
                highlightAlpha = 0.04f,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = true,
                role = Role.Button,
                onClick = {
                    feedback.perform(RavenFeedbackEffect.TAP)
                    onClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = iconBadgeModifier
                .size(32.dp)
                .clip(RavenShapes.ControlShape)
                .background(colors.surface2)
                .border(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.lightRimTop, colors.hairline),
                    ),
                    shape = RavenShapes.ControlShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            leadingIcon()
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact frosted quick panel floating above the bottom bar for instant reading mode & fit switching.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderQuickModePanel(
    currentMode: ReaderMode,
    currentFit: ReaderFit,
    isLandscapeViewport: Boolean,
    onSelectMode: (ReaderMode) -> Unit,
    onSelectFit: (ReaderFit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .ravenFrostedSurface(
                reduceTransparency = reduceTransparency,
                shape = RavenShapes.CardShape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(ReaderTestTags.READER_MODE_PANEL),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "阅读模式",
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkSecondary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReaderMode.entries.forEach { mode ->
                RavenFilterChip(
                    label = readerModeLabel(mode),
                    selected = currentMode == mode,
                    onClick = { onSelectMode(mode) },
                )
            }
        }
        if (currentMode == ReaderMode.SPREAD && !isLandscapeViewport) {
            Text(
                text = "竖屏自动单页显示，横屏自动启用双页并排",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkTertiary,
            )
        }
        Text(
            text = "画面适配",
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkSecondary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReaderFit.entries.forEach { fit ->
                RavenFilterChip(
                    label = readerFitLabel(fit),
                    selected = currentFit == fit,
                    onClick = { onSelectFit(fit) },
                )
            }
        }
    }
}

/**
 * Compact frosted quick panel floating above the bottom bar for instant brightness & auto-page control.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderQuickBrightnessAutoPagePanel(
    brightnessPercent: Int,
    autoPageIntervalSeconds: Int,
    slideshowOrder: SlideshowOrder,
    onBrightnessPercentChange: (Int) -> Unit,
    onAutoPageIntervalChange: (Int) -> Unit,
    onSlideshowOrderChange: (SlideshowOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val followSystem = brightnessPercent < 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .ravenFrostedSurface(
                reduceTransparency = reduceTransparency,
                shape = RavenShapes.CardShape,
            )
            .padding(vertical = 8.dp)
            .testTag(ReaderTestTags.READER_BRIGHTNESS_PANEL),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RavenGroupedSwitchRow(
            title = "亮度跟随系统",
            icon = Icons.Outlined.Brightness6,
            checked = followSystem,
            onCheckedChange = { follow ->
                onBrightnessPercentChange(if (follow) -1 else 60)
            },
        )
        if (!followSystem) {
            RavenGroupedSliderRow(
                title = "屏幕亮度",
                value = brightnessPercent.coerceIn(0, 100).toFloat(),
                onValueChange = { onBrightnessPercentChange(it.roundToInt()) },
                valueLabel = "${brightnessPercent.coerceIn(0, 100)}%",
                valueRange = 0f..100f,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "自动翻页（等待本页渲染完成）",
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkSecondary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ReaderViewModel.ALLOWED_AUTO_PAGE_INTERVALS.forEach { sec ->
                    RavenFilterChip(
                        label = if (sec == 0) "关闭" else "${sec}s",
                        selected = autoPageIntervalSeconds == sec,
                        onClick = { onAutoPageIntervalChange(sec) },
                    )
                }
            }
            if (autoPageIntervalSeconds > 0) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RavenFilterChip(
                        label = "顺序",
                        selected = slideshowOrder == SlideshowOrder.SEQUENTIAL,
                        onClick = { onSlideshowOrderChange(SlideshowOrder.SEQUENTIAL) },
                    )
                    RavenFilterChip(
                        label = "随机",
                        selected = slideshowOrder == SlideshowOrder.RANDOM,
                        onClick = { onSlideshowOrderChange(SlideshowOrder.RANDOM) },
                    )
                }
            }
        }
    }
}

/**
 * Sequential reading/playback queue continuation card displayed at the end of a book when
 * [PlaybackQueueSession.hasNext] is true.
 */
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
