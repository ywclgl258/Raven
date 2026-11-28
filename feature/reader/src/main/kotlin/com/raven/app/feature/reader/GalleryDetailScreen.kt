package com.raven.app.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenAnimatedLabel
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenContentCrossfade
import com.raven.app.core.designsystem.component.RavenCrimsonProgressBar
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFeatureTutorialDialog
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenShelfOption
import com.raven.app.core.designsystem.component.RavenShelfPickerSheet
import com.raven.app.core.designsystem.component.RavenTutorialStep
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenHorizontalShelf
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenLoadingSkeleton
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.RavenMorphToggleIcon
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ambientHeroGlow
import com.raven.app.core.designsystem.theme.ravenFrostedSurface
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.ReadingProgress
import com.raven.app.feature.reader.resolveReaderParent
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.RavenRoute
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val INITIAL_PREVIEW_PAGE_LIMIT = 60

/** Tag sheet copy payload: readable translated label, with raw label / slug fallbacks. */
internal fun tagClipboardText(translatedLabel: String, tag: TagRef): String =
    translatedLabel.trim().ifBlank { tag.label.trim() }.ifBlank { tag.slug.trim() }

/** Author sheet copy payload: display name, with the stable author id as fallback. */
internal fun authorClipboardText(author: AuthorRef): String =
    author.name.trim().ifBlank { author.id.trim() }

/**
 * Full Raven Obsidian × Apple HIG Gallery Detail screen supporting online & local books,
 * auth-gated sites (e.g., ExHentai), selective page download sheet, embedded videos
 * (e.g., Cosplaytele Cossora HLS), translated tags with long-press actions, page bookmarks,
 * and tap-to-jump preview thumbnails.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GalleryDetailScreen(
    viewModel: GalleryDetailViewModel,
    navigator: ReaderNavigator,
    modifier: Modifier = Modifier,
    parent: RavenRoute.ReaderParent? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val clipboardScope = rememberCoroutineScope()
    val copyToClipboard: (String, String) -> Unit = remember(clipboard, clipboardScope, snackbarHostState) {
        { text: String, message: String ->
            if (text.isNotBlank()) {
                clipboard.setText(AnnotatedString(text))
                clipboardScope.launch { snackbarHostState.showSnackbar(message) }
            }
        }
    }
    var showAllPreviewPages by rememberSaveable(uiState.siteId.value, uiState.mediaId) {
        mutableStateOf(false)
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val exportPicker = rememberExportDocumentPicker { prepared, uri, _ ->
        viewModel.completeSafExport(prepared, uri, context.contentResolver)
    }
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is GalleryDetailEvent.RequestSaveDocument -> exportPicker(event.prepared, false)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(ReaderTestTags.GALLERY_DETAIL_SCREEN),
        containerColor = colors.canvas,
        contentColor = colors.inkPrimary,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            RavenTopBar(
                title = uiState.detail?.item?.title ?: uiState.siteDisplayName,
                subtitle = if (uiState.detail != null) uiState.siteDisplayName else null,
                frosted = true,
                navigationIcon = {
                    IconButton(onClick = navigator::navigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = colors.inkPrimary,
                        )
                    }
                },
                actions = {
                    if (!uiState.isLocal) {
                        IconButton(onClick = { viewModel.loadDetail(forceRefresh = true) }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新",
                                tint = colors.inkPrimary,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val detail = uiState.detail
            val motionReduced = LocalMotionReduced.current
            val stageKey = when {
                uiState.isLoading && detail == null -> 0
                uiState.authRequired -> 1
                detail == null -> 2
                else -> 3
            }
            RavenContentCrossfade(targetState = stageKey) { stage ->
                when (stage) {
                    0 -> RavenLoadingSkeleton(rowCount = 6)
                    1 -> Box(modifier = Modifier.fillMaxSize()) {
                        GalleryAuthGateCard(
                            siteName = uiState.siteDisplayName,
                            message = uiState.authMessage
                                ?: "该站点需要有效登录会话后才能浏览图集，请前往账号管理完成登录或导入 Cookie。",
                            onOpenAccount = { navigator.openSiteAccount(uiState.siteId) },
                            onRetry = { viewModel.loadDetail(forceRefresh = true) },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                    2 -> RavenErrorState(
                        title = "图集加载失败",
                        message = uiState.errorMessage ?: "无法获取图集详情，请检查网络或重试。",
                        onRetry = { viewModel.loadDetail(forceRefresh = true) },
                    )
                    else -> {
                        val activeDetail = detail ?: return@RavenContentCrossfade
                        GalleryDetailContent(
                            uiState = uiState,
                            detail = activeDetail,
                            onRefreshChapters = viewModel::refreshChapters,
                            onOpenChapter = { chapter ->
                                // 连载里一话就是一页内容：点章节直接开读，不再先跳到该话的详情页。
                                val saved = uiState.readingProgressByMediaId[chapter.id]
                                val startPage = if (saved != null && !saved.completed && saved.currentPage > 0) {
                                    saved.currentPage.coerceAtLeast(1)
                                } else {
                                    1
                                }
                                navigator.openReader(
                                    siteId = chapter.siteId,
                                    mediaId = chapter.id,
                                    url = chapter.url,
                                    startPage = startPage,
                                    offlineOnly = chapter.siteId.isLocal,
                                    queueToken = null,
                                    parent = resolveReaderParent(activeDetail, parent),
                                )
                            },
                            onOpenChapterDetail = { chapter ->
                                // 长按仍可查看单话详情（评论区、单话标签等）。
                                navigator.openGallery(
                                    siteId = chapter.siteId,
                                    mediaId = chapter.id,
                                    url = chapter.url,
                                    parent = resolveReaderParent(activeDetail, parent),
                                )
                            },
                            showAllPreviewPages = showAllPreviewPages,
                            onExpandAllPages = { showAllPreviewPages = true },
                            formatTagLabel = viewModel::formatTagLabel,
                            isTagBlocked = viewModel::isTagBlocked,
                            onOpenReader = { startPage, offline ->
                                navigator.openReader(
                                    siteId = activeDetail.item.siteId,
                                    mediaId = activeDetail.item.id,
                                    url = activeDetail.item.url,
                                    startPage = startPage,
                                    offlineOnly = offline,
                                    queueToken = null,
                                    parent = resolveReaderParent(activeDetail, parent),
                                )
                            },
                            onOpenResumeTarget = { target ->
                                navigator.openReader(
                                    siteId = target.item.siteId,
                                    mediaId = target.item.id,
                                    url = target.item.url,
                                    startPage = target.startPage,
                                    offlineOnly = target.item.siteId.isLocal,
                                    queueToken = null,
                                    parent = resolveReaderParent(activeDetail, parent),
                                )
                            },
                            onOpenVideo = { video ->
                                navigator.openVideo(
                                    siteId = video.siteId,
                                    mediaId = video.id,
                                    url = video.url,
                                    title = video.title,
                                    coverUrl = video.coverUrl,
                                )
                            },
                            onOpenAuthor = { author ->
                                navigator.openAuthorSearch(author, crossSite = true, sourceSiteId = activeDetail.item.siteId)
                            },
                            onLongPressAuthor = viewModel::openAuthorActions,
                            onOpenTag = { tag ->
                                navigator.openTagFeed(activeDetail.item.siteId, tag)
                            },
                            onLongPressTag = viewModel::openTagActions,
                            onToggleWatchLater = viewModel::toggleWatchLater,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onToggleSeriesFollow = viewModel::toggleSeriesFollow,
                            onToggleLike = viewModel::toggleLike,
                            onOpenDownloadSheet = viewModel::openDownloadSheet,
                            onExportCbz = { viewModel.exportCbz() },
                            onDeleteBookmark = viewModel::deleteBookmark,
                            onOpenRelated = { item ->
                                if (item.kind.isVideo) {
                                    navigator.openVideo(
                                        siteId = item.siteId,
                                        mediaId = item.id,
                                        url = item.url,
                                        title = item.title,
                                        coverUrl = item.coverUrl,
                                    )
                                } else {
                                    navigator.openGallery(
                                        siteId = item.siteId,
                                        mediaId = item.id,
                                        url = item.url,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // Export progress overlay pill at bottom
            val exportText = uiState.exportStatusText
            AnimatedVisibility(
                visible = uiState.isExporting && !exportText.isNullOrBlank(),
                enter = if (motionReduced) EnterTransition.None else fadeIn(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)),
                exit = if (motionReduced) ExitTransition.None else fadeOut(tween(120, easing = RavenMotion.HarmonyDecelerationEasing)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (!exportText.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .ravenFrostedSurface(
                                reduceTransparency = LocalReduceTransparency.current,
                                shape = RavenShapes.PillShape,
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.crimson,
                        )
                        Text(
                            text = exportText,
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    // Selective Page Download Bottom Sheet
    if (uiState.downloadSheet.visible && uiState.detail != null) {
        val activeDetail = uiState.detail ?: return
        SelectiveDownloadBottomSheet(
            totalPages = activeDetail.pageCount,
            resumePage = uiState.readingProgress?.currentPage,
            sheetState = uiState.downloadSheet,
            onDismiss = viewModel::dismissDownloadSheet,
            onSelectAll = viewModel::setDownloadAll,
            onRangeChange = viewModel::updateDownloadRangeInput,
            onConfirm = viewModel::confirmDownload,
        )
    }

    // Tag Long-Press Action Sheet
    val activeTagAction = uiState.tagAction
    if (activeTagAction != null) {
        TagActionBottomSheet(
            state = activeTagAction,
            blockTagsGlobally = uiState.blockTagsGlobally,
            onDismiss = viewModel::dismissTagActions,
            onSearchTag = { crossSite ->
                viewModel.dismissTagActions()
                navigator.openTagSearch(activeTagAction.tag, crossSite, sourceSiteId = uiState.siteId)
            },
            onCopyTag = { text ->
                viewModel.dismissTagActions()
                copyToClipboard(text, "已复制标签名「$text」")
            },
            onToggleSubscription = { viewModel.toggleTagSubscription(activeTagAction.tag) },
            onToggleBlocked = { viewModel.toggleTagBlocked(activeTagAction.tag) },
            onSaveCustomTranslation = { chineseName ->
                viewModel.saveCustomTagTranslation(activeTagAction.tag, chineseName)
            },
        )
    }

    // Author Long-Press Action Sheet
    val activeAuthorAction = uiState.authorAction
    if (activeAuthorAction != null) {
        AuthorActionBottomSheet(
            author = activeAuthorAction,
            onDismiss = viewModel::dismissAuthorActions,
            onCopyAuthorName = {
                val text = authorClipboardText(activeAuthorAction)
                viewModel.dismissAuthorActions()
                copyToClipboard(text, "已复制作者名「$text」")
            },
            onSearchAuthor = { crossSite ->
                viewModel.dismissAuthorActions()
                navigator.openAuthorSearch(activeAuthorAction, crossSite, sourceSiteId = uiState.siteId)
            },
        )
    }

    if (uiState.showShelfPicker && uiState.detail != null) {
        val options = remember(uiState.shelves, uiState.containingShelfIds) {
            uiState.shelves.map { summary ->
                RavenShelfOption(
                    id = summary.shelf.id,
                    name = summary.shelf.name,
                    itemCount = summary.itemCount,
                    selected = summary.shelf.id in uiState.containingShelfIds,
                )
            }
        }
        RavenShelfPickerSheet(
            title = "加入片单 / 书架",
            shelves = options,
            onToggleShelf = viewModel::setShelfMembership,
            onCreateShelf = viewModel::createShelfAndAdd,
            onDismiss = viewModel::dismissShelfPicker,
        )
    }

    if (uiState.showDetailTutorial && uiState.detail != null) {
        RavenFeatureTutorialDialog(
            featureTitle = "作品详情页导览",
            steps = listOf(
                RavenTutorialStep(
                    badge = "核心操作",
                    title = "收藏 · 追更 · 本地点赞",
                    description = "「收藏」保存作品本身；「追更」在有连载来源的站点直接订阅后续章节；「点赞」在本地记录该作品的标签权重，专门用于驱动鸦巢的智能推荐。",
                    icon = Icons.Rounded.ThumbUp,
                ),
                RavenTutorialStep(
                    badge = "标签菜单",
                    title = "轻点搜索 · 长按订阅或屏蔽",
                    description = "轻点任意标签即可按标签检索；长按标签弹出菜单，可订阅该标签的更新动态、复制、编辑中文译名或全局屏蔽。",
                    icon = Icons.Outlined.Search,
                ),
            ),
            onComplete = viewModel::completeDetailTutorial,
            onSkipAll = viewModel::skipAllTutorials,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryDetailContent(
    uiState: GalleryDetailUiState,
    detail: GalleryDetail,
    onRefreshChapters: () -> Unit,
    onOpenChapter: (MediaItem) -> Unit,
    /** Long-press on a chapter tile opens that chapter's own detail page. */
    onOpenChapterDetail: (MediaItem) -> Unit = {},
    showAllPreviewPages: Boolean,
    onExpandAllPages: () -> Unit,
    formatTagLabel: (TagRef) -> String,
    isTagBlocked: (TagRef) -> Boolean,
    onOpenReader: (startPage: Int, offlineOnly: Boolean) -> Unit,
    /** Opens the work's newest reading position, which may live in another chapter. */
    onOpenResumeTarget: (GalleryResumeTarget) -> Unit,
    onOpenVideo: (com.raven.app.core.model.MediaItem) -> Unit,
    onOpenAuthor: (AuthorRef) -> Unit,
    onLongPressAuthor: (AuthorRef) -> Unit,
    onOpenTag: (TagRef) -> Unit,
    onLongPressTag: (TagRef) -> Unit,
    onToggleWatchLater: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleSeriesFollow: () -> Unit = {},
    onOpenDownloadSheet: () -> Unit,
    onExportCbz: () -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onOpenRelated: (com.raven.app.core.model.MediaItem) -> Unit,
) {
    val colors = LocalRavenColors.current
    val listState = rememberLazyListState()
    val pagesByIndex = remember(detail.pages) { detail.pages.associateBy { it.index } }
    val bookmarkedPages = remember(uiState.pageBookmarks) {
        uiState.pageBookmarks.mapTo(hashSetOf()) { it.page }
    }
    val totalPages = detail.pageCount.coerceAtLeast(detail.pages.size)
    val isSerialComic = !detail.chaptersUrl.isNullOrBlank()
    val previewCount = if (showAllPreviewPages) totalPages else minOf(totalPages, INITIAL_PREVIEW_PAGE_LIMIT)
    val previewNumbers = remember(previewCount) { (1..previewCount).toList() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridColumns = when {
            maxWidth >= 960.dp -> 6
            maxWidth >= 720.dp -> 5
            maxWidth >= 520.dp -> 4
            else -> 3
        }
        val previewRows = remember(previewNumbers, gridColumns) {
            previewNumbers.chunked(gridColumns)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. Obsidian Hero Header with ambient glow (<= 0.18f)
            item(key = "hero_header") {
                GalleryHeroHeader(
                    uiState = uiState,
                    detail = detail,
                    onOpenReader = onOpenReader,
                    onOpenResumeTarget = onOpenResumeTarget,
                    onOpenAuthor = onOpenAuthor,
                    onLongPressAuthor = onLongPressAuthor,
                    onToggleWatchLater = onToggleWatchLater,
                    onToggleFavorite = onToggleFavorite,
                    onToggleLike = onToggleLike,
                    onToggleSeriesFollow = onToggleSeriesFollow,
                    onOpenDownloadSheet = onOpenDownloadSheet,
                    onExportCbz = onExportCbz,
                )
            }

            // 2. Description (if present)
            if (detail.description.isNotBlank()) {
                item(key = "description") {
                    RavenGroupedSection(
                        title = "简介",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = detail.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            // 3. Embedded Videos (e.g., Cosplaytele Cossora HLS)
            if (detail.embeddedVideos.isNotEmpty()) {
                item(key = "embedded_videos") {
                    RavenHorizontalShelf(
                        title = "内嵌视频",
                        subtitle = "${detail.embeddedVideos.size} 个可播放视频",
                        modifier = Modifier.testTag(ReaderTestTags.GALLERY_DETAIL_EMBEDDED_VIDEOS),
                    ) {
                        items(
                            items = detail.embeddedVideos,
                            key = { "embed_${it.compositeKey}" },
                        ) { video ->
                            RavenVideoCard(
                                item = video,
                                badgeText = "视频",
                                onClick = { onOpenVideo(video) },
                                modifier = Modifier.width(232.dp),
                            )
                        }
                    }
                }
            }

            // 4. Translated Tags / Characters / Categories
            val allTags = buildList {
                addAll(detail.categories)
                addAll(detail.characters)
                addAll(detail.tags)
            }.distinctBy { "${it.namespace}:${it.slug}".lowercase() }

            if (allTags.isNotEmpty()) {
                item(key = "tags") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RavenSectionHeader(
                            title = "标签",
                            subtitle = "轻点搜索 · 长按复制、订阅或屏蔽",
                        )
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Read tagDictionaryRevision so edited Chinese translations recompose chips live
                            val dictRevision = uiState.tagDictionaryRevision
                            allTags.forEach { tag ->
                                val label = remember(tag, uiState.translateTags, dictRevision) {
                                    formatTagLabel(tag)
                                }
                                val isBlocked = isTagBlocked(tag)
                                val isSubscribed = tag.slug.lowercase() in uiState.subscribedTagSlugs
                                GalleryTagChip(
                                    label = label,
                                    namespace = tag.namespace,
                                    isSubscribed = isSubscribed,
                                    isBlocked = isBlocked,
                                    onClick = { onOpenTag(tag) },
                                    onLongClick = { onLongPressTag(tag) },
                                )
                            }
                        }
                    }
                }
            }

            // 5. Page Bookmarks Jump List
            if (uiState.pageBookmarks.isNotEmpty()) {
                item(key = "bookmarks") {
                    PageBookmarksSection(
                        bookmarks = uiState.pageBookmarks,
                        onJumpToPage = { page -> onOpenReader(page, uiState.isLocal) },
                        onDeleteBookmark = onDeleteBookmark,
                        modifier = Modifier.testTag(ReaderTestTags.GALLERY_DETAIL_BOOKMARKS),
                    )
                }
            }

            // 6. 连载漫画：章节选择（替代图页预览）
            if (isSerialComic) {
                item(key = "chapter_picker") {
                    GalleryChapterPickerSection(
                        chapters = uiState.chapters,
                        loading = uiState.chaptersLoading,
                        error = uiState.chaptersError,
                        currentChapterId = detail.item.id,
                        currentChapterUrl = detail.item.extra["chapterUrl"].orEmpty(),
                        currentChapterHints = listOfNotNull(
                            detail.item.extra["chapterId"],
                            detail.item.extra["chapterUuid"],
                            detail.item.extra["chapterHid"],
                        ),
                        onSelect = onOpenChapter,
                        onLongSelect = onOpenChapterDetail,
                        onRefresh = onRefreshChapters,
                        readState = uiState.chapterPickerReadState,
                        onOpenNextUnread = onOpenChapter,
                        selectHint = "点击直接阅读，长按看单话详情",
                    )
                }
            }

            // 7. Preview Thumbnails Grid Header（非连载漫画）
            if (!isSerialComic && totalPages > 0) {
                item(key = "thumbs_header") {
                    RavenSectionHeader(
                        title = "图页预览",
                        subtitle = "共 $totalPages 页 · 点击直达对应页码",
                        modifier = Modifier.testTag(ReaderTestTags.GALLERY_DETAIL_THUMBNAILS),
                    )
                }

                items(
                    items = previewRows,
                    key = { row -> "thumb_row_${row.first()}" },
                ) { rowPages ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowPages.forEach { pageNumber ->
                            val pageInfo = pagesByIndex[pageNumber]
                            PreviewPageTile(
                                siteId = detail.item.siteId,
                                pageNumber = pageNumber,
                                page = pageInfo,
                                isCurrentPage = uiState.readingProgress?.currentPage == pageNumber,
                                isBookmarked = pageNumber in bookmarkedPages,
                                onClick = { onOpenReader(pageNumber, uiState.isLocal) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(gridColumns - rowPages.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (!showAllPreviewPages && totalPages > INITIAL_PREVIEW_PAGE_LIMIT) {
                    item(key = "expand_preview_pages") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            OutlinedButton(
                                onClick = onExpandAllPages,
                                shape = RavenShapes.PillShape,
                                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = colors.surface1,
                                    contentColor = colors.inkPrimary,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .ravenSpringPress(),
                            ) {
                                Text(
                                    text = "展开全部 $totalPages 页预览",
                                    style = MaterialTheme.typography.labelLarge.tabular(),
                                )
                            }
                        }
                    }
                }
            }

            // 7. Comments
            if (detail.comments.isNotEmpty()) {
                item(key = "comments") {
                    RavenGroupedSection(
                        title = "评论 (${detail.comments.size})",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        detail.comments.take(15).forEachIndexed { index, comment ->
                            if (index > 0) RavenGroupedDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = comment.author.ifBlank { "匿名" },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (comment.timestampLabel.isNotBlank()) {
                                        Text(
                                            text = comment.timestampLabel,
                                            style = MaterialTheme.typography.labelSmall.tabular(),
                                            color = colors.inkTertiary,
                                        )
                                    }
                                }
                                Text(
                                    text = comment.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkSecondary,
                                )
                            }
                        }
                    }
                }
            }

            // 8. Similar recommendations (respecting global/per-site blocked terms)
            val relatedItems = uiState.filteredRelated
            if (relatedItems.isNotEmpty()) {
                item(key = "related") {
                    RavenHorizontalShelf(
                        title = "相似推荐",
                        subtitle = "${relatedItems.size} 部作品",
                    ) {
                        items(
                            items = relatedItems,
                            key = { "rel_${it.compositeKey}" },
                        ) { relatedItem ->
                            RavenGalleryCard(
                                item = relatedItem,
                                onClick = { onOpenRelated(relatedItem) },
                                modifier = Modifier.width(136.dp),
                            )
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
