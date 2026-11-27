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
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.TagRef
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

    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is GalleryDetailEvent.RequestSaveDocument -> {
                    navigator.launchSaveDocument(
                        suggestedFileName = event.suggestedFileName,
                        mimeType = event.mimeType,
                    ) { uri ->
                        viewModel.completeSafExport(
                            prepared = event.prepared,
                            targetUri = uri,
                            contentResolver = context.contentResolver,
                        )
                    }
                }
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
                                navigator.openGallery(
                                    siteId = uiState.siteId,
                                    mediaId = chapter.id,
                                    url = chapter.url,
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
                                navigator.openAuthorSearch(author, crossSite = true)
                            },
                            onLongPressAuthor = viewModel::openAuthorActions,
                            onOpenTag = { tag ->
                                navigator.openTagFeed(activeDetail.item.siteId, tag)
                            },
                            onLongPressTag = viewModel::openTagActions,
                            onToggleWatchLater = viewModel::toggleWatchLater,
                            onToggleFavorite = viewModel::toggleFavorite,
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
                navigator.openTagSearch(activeTagAction.tag, crossSite)
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
                navigator.openAuthorSearch(activeAuthorAction, crossSite)
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
                    title = "稍后看 · 收藏 · 本地点赞",
                    description = "「稍后看」直通鸦巢的稍后观看队列；「收藏」仅保存作品本身；「点赞」在本地记录该作品的标签权重，专门用于驱动鸦巢的智能推荐。",
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
    showAllPreviewPages: Boolean,
    onExpandAllPages: () -> Unit,
    formatTagLabel: (TagRef) -> String,
    isTagBlocked: (TagRef) -> Boolean,
    onOpenReader: (startPage: Int, offlineOnly: Boolean) -> Unit,
    onOpenVideo: (com.raven.app.core.model.MediaItem) -> Unit,
    onOpenAuthor: (AuthorRef) -> Unit,
    onLongPressAuthor: (AuthorRef) -> Unit,
    onOpenTag: (TagRef) -> Unit,
    onLongPressTag: (TagRef) -> Unit,
    onToggleWatchLater: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLike: () -> Unit,
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
                    onOpenAuthor = onOpenAuthor,
                    onLongPressAuthor = onLongPressAuthor,
                    onToggleWatchLater = onToggleWatchLater,
                    onToggleFavorite = onToggleFavorite,
                    onToggleLike = onToggleLike,
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
                        onRefresh = onRefreshChapters,
                        readState = uiState.chapterPickerReadState,
                        onOpenNextUnread = onOpenChapter,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryHeroHeader(
    uiState: GalleryDetailUiState,
    detail: GalleryDetail,
    onOpenReader: (startPage: Int, offlineOnly: Boolean) -> Unit,
    onOpenAuthor: (AuthorRef) -> Unit,
    onLongPressAuthor: (AuthorRef) -> Unit,
    onToggleWatchLater: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenDownloadSheet: () -> Unit,
    onExportCbz: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val feedback = LocalRavenFeedback.current
    val item = detail.item
    val primaryAuthor = detail.authors.firstOrNull() ?: item.author
    val coverAspect = RavenFormatters.clampAspectRatio(item.coverAspectRatio, defaultRatio = 0.72f)
    val ratingVal = item.rating
    val dateLabelVal = item.dateLabel
    val languageVal = item.language
    val sizeLabelVal = uiState.localSizeLabel ?: detail.fileSizeLabel
    val downloadVal = uiState.activeDownload
    val progressVal = uiState.readingProgress

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .ambientHeroGlow(tint = colors.crimson, alpha = 0.14f)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val canReadImages = (detail.pageCount > 0 || detail.pages.isNotEmpty())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Cover Poster
            Box(
                modifier = Modifier
                    .width(122.dp)
                    .aspectRatio(coverAspect)
                    .clip(RavenShapes.CardShape)
                    .background(colors.surface1)
                    .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                    .let { base ->
                        if (canReadImages) {
                            base.clickable(role = Role.Button) {
                                onOpenReader(uiState.resumeStartPage, uiState.isLocal)
                            }
                        } else {
                            base
                        }
                    },
            ) {
                RavenImage(
                    url = item.coverUrl,
                    siteId = item.siteId,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (progressVal != null && progressVal.progressFraction > 0f) {
                    RavenCrimsonProgressBar(
                        fraction = progressVal.progressFraction,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    )
                }
            }

            // Metadata Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.inkPrimary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                if (primaryAuthor != null && primaryAuthor.name.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(RavenShapes.PillShape)
                            .background(colors.surface2)
                            .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                            .combinedClickable(
                                role = Role.Button,
                                onClick = { onOpenAuthor(primaryAuthor) },
                                onLongClick = { onLongPressAuthor(primaryAuthor) },
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = primaryAuthor.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RavenStatusPill(
                        text = uiState.siteDisplayName,
                        tone = if (uiState.isLocal) RavenStatusTone.SUCCESS else RavenStatusTone.NEUTRAL,
                        tabular = false,
                    )
                    if (detail.pageCount > 0) {
                        RavenStatusPill(
                            text = "${detail.pageCount} 页",
                            tone = RavenStatusTone.NEUTRAL,
                        )
                    }
                    if (ratingVal != null && ratingVal > 0f) {
                        RavenStatusPill(
                            text = String.format(Locale.US, "%.1f", ratingVal),
                            icon = Icons.Rounded.Star,
                            tone = RavenStatusTone.WARNING,
                        )
                    }
                    if (!languageVal.isNullOrBlank()) {
                        RavenStatusPill(
                            text = languageVal,
                            tone = RavenStatusTone.NEUTRAL,
                            tabular = false,
                        )
                    }
                    if (!dateLabelVal.isNullOrBlank()) {
                        RavenStatusPill(
                            text = dateLabelVal,
                            tone = RavenStatusTone.NEUTRAL,
                        )
                    }
                    if (!sizeLabelVal.isNullOrBlank()) {
                        RavenStatusPill(
                            text = sizeLabelVal,
                            tone = RavenStatusTone.NEUTRAL,
                        )
                    }
                    if (uiState.isOfflineComplete || downloadVal?.status == DownloadStatus.COMPLETED) {
                        RavenStatusPill(
                            text = "已离线",
                            icon = Icons.Outlined.OfflinePin,
                            tone = RavenStatusTone.SUCCESS,
                            tabular = false,
                        )
                    } else if (downloadVal != null && downloadVal.status == DownloadStatus.DOWNLOADING) {
                        RavenStatusPill(
                            text = "下载中 ${downloadVal.completedUnits}/${downloadVal.totalUnits}",
                            tone = RavenStatusTone.ACCENT,
                        )
                    }
                }
            }
        }

        // Additional co-authors if present
        if (detail.authors.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                detail.authors.drop(1).forEach { author ->
                    RavenFilterChip(
                        label = author.name,
                        selected = false,
                        leadingIcon = Icons.Outlined.Person,
                        onClick = { onOpenAuthor(author) },
                        onLongClick = { onLongPressAuthor(author) },
                    )
                }
            }
        }

        // Solid White Netflix-style Primary CTA + Optional Offline Read CTA
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (detail.pageCount > 0 || detail.pages.isNotEmpty()) {
                Button(
                    onClick = {
                        feedback.perform(RavenFeedbackEffect.TAP)
                        onOpenReader(uiState.resumeStartPage, uiState.isLocal)
                    },
                    shape = RavenShapes.PillShape,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .ravenSpringPress()
                        .testTag(ReaderTestTags.GALLERY_DETAIL_PRIMARY_CTA),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiState.primaryCtaLabel,
                        style = MaterialTheme.typography.labelLarge.tabular().copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!uiState.isLocal && uiState.canOpenOffline) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 50.dp)
                        .ravenSpringPress()
                        .ravenFrostedSurface(
                            reduceTransparency = reduceTransparency,
                            shape = RavenShapes.PillShape,
                        )
                        .clickable(role = Role.Button) {
                            feedback.perform(RavenFeedbackEffect.TAP)
                            onOpenReader(uiState.resumeStartPage, true)
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OfflinePin,
                            contentDescription = null,
                            tint = colors.success,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "离线读",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.inkPrimary,
                        )
                    }
                }
            }
        }

        // Frosted Action Bar: Watch Later / Favorite / Like / Download / Export CBZ
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FrostedHeroActionButton(
                label = if (uiState.isInWatchLater) "已稍后看" else "稍后看",
                active = uiState.isInWatchLater,
                feedbackEffect = if (uiState.isInWatchLater) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.TOGGLE_ON,
                onClick = onToggleWatchLater,
                modifier = Modifier
                    .weight(1f)
                    .ravenBurstFeedback(trigger = uiState.isInWatchLater),
            ) { tint, iconModifier ->
                RavenMorphIcon(
                    glyph = if (uiState.isInWatchLater) RavenMorphIcons.Check else RavenMorphIcons.Plus,
                    contentDescription = if (uiState.isInWatchLater) "已稍后看" else "稍后看",
                    tint = tint,
                    size = 20.dp,
                    springPreset = MorphSpringPreset.SNAPPY,
                    modifier = iconModifier,
                )
            }
            FrostedHeroActionButton(
                label = if (uiState.isFavorite) "已收藏" else "收藏",
                active = uiState.isFavorite,
                feedbackEffect = if (uiState.isFavorite) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.FAVORITE,
                onClick = onToggleFavorite,
                modifier = Modifier
                    .weight(1f)
                    .ravenBurstFeedback(trigger = uiState.isFavorite),
            ) { tint, iconModifier ->
                RavenMorphIcon(
                    glyph = if (uiState.isFavorite) RavenMorphIcons.HeartFilled else RavenMorphIcons.HeartOutline,
                    contentDescription = if (uiState.isFavorite) "已收藏" else "收藏",
                    tint = tint,
                    size = 20.dp,
                    springPreset = MorphSpringPreset.BOUNCY,
                    modifier = iconModifier,
                )
            }
            FrostedHeroActionButton(
                icon = if (uiState.isLiked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                label = if (uiState.isLiked) "已点赞" else "点赞",
                active = uiState.isLiked,
                feedbackEffect = if (uiState.isLiked) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.FAVORITE,
                onClick = onToggleLike,
                modifier = Modifier
                    .weight(1f)
                    .ravenBurstFeedback(trigger = uiState.isLiked)
                    .testTag("gallery_action_like"),
            )
            if (!uiState.isLocal) {
                val isDownloaded = uiState.isOfflineComplete ||
                    uiState.activeDownload?.status == DownloadStatus.COMPLETED
                FrostedHeroActionButton(
                    label = if (isDownloaded) "已下载" else "下载",
                    active = isDownloaded,
                    onClick = onOpenDownloadSheet,
                    modifier = Modifier.weight(1f),
                ) { tint, iconModifier ->
                    RavenMorphIcon(
                        glyph = if (isDownloaded) RavenMorphIcons.DownloadDone else RavenMorphIcons.Download,
                        contentDescription = if (isDownloaded) "已下载" else "下载",
                        tint = tint,
                        size = 20.dp,
                        springPreset = MorphSpringPreset.SNAPPY,
                        modifier = iconModifier,
                    )
                }
            }
            FrostedHeroActionButton(
                icon = Icons.Outlined.Archive,
                label = "导出 CBZ",
                active = false,
                enabled = !uiState.isExporting,
                onClick = onExportCbz,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FrostedHeroActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    feedbackEffect: RavenFeedbackEffect = RavenFeedbackEffect.TAP,
) {
    FrostedHeroActionButton(
        label = label,
        active = active,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        feedbackEffect = feedbackEffect,
    ) { tint, iconModifier ->
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = iconModifier.size(20.dp),
        )
    }
}

@Composable
private fun FrostedHeroActionButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    feedbackEffect: RavenFeedbackEffect = RavenFeedbackEffect.TAP,
    iconContent: @Composable (tint: Color, modifier: Modifier) -> Unit,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val iconScale by animateFloatAsState(
        targetValue = if (active) 1.12f else 1.0f,
        animationSpec = if (motionReduced) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "galleryHeroActionIconScale",
    )
    val tint = when {
        !enabled -> colors.inkTertiary
        active -> colors.crimson
        else -> colors.inkPrimary
    }

    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .ravenSpringPress(interactionSource = interactionSource, enabled = enabled)
            .ravenFrostedSurface(
                reduceTransparency = reduceTransparency,
                shape = RavenShapes.CardShape,
                borderColor = if (active) colors.crimson.copy(alpha = 0.45f) else colors.hairline,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    feedback.perform(feedbackEffect)
                    onClick()
                },
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        iconContent(
            tint,
            Modifier.graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) colors.inkPrimary else colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GalleryTagChip(
    label: String,
    namespace: String,
    isSubscribed: Boolean,
    isBlocked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val borderColor = when {
        isBlocked -> colors.error.copy(alpha = 0.45f)
        isSubscribed -> colors.crimson.copy(alpha = 0.45f)
        else -> colors.hairline
    }
    val containerColor = when {
        isBlocked -> colors.error.copy(alpha = 0.12f)
        isSubscribed -> colors.crimson.copy(alpha = 0.14f)
        else -> colors.surface1
    }

    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .ravenSpringPress(interactionSource = interactionSource)
            .clip(RavenShapes.PillShape)
            .background(containerColor)
            .border(0.5.dp, borderColor, RavenShapes.PillShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (namespace.isNotBlank()) {
            Text(
                text = namespace,
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                isBlocked -> colors.error
                isSubscribed -> colors.crimson
                else -> colors.inkPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PageBookmarksSection(
    bookmarks: List<PageBookmark>,
    onJumpToPage: (Int) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RavenSectionHeader(
            title = "页码书签",
            subtitle = "${bookmarks.size} 条 · 点击跳转",
        )
        RavenGroupedSection(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            bookmarks.forEachIndexed { index, bookmark ->
                if (index > 0) RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "第 ${bookmark.page} 页",
                    subtitle = bookmark.note.takeIf(String::isNotBlank)
                        ?: if (bookmark.totalPages > 0) "共 ${bookmark.totalPages} 页" else null,
                    icon = Icons.Filled.Bookmark,
                    iconTint = colors.crimson,
                    onClick = { onJumpToPage(bookmark.page) },
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
    }
}

@Composable
private fun PreviewPageTile(
    siteId: com.raven.app.core.model.SiteId,
    pageNumber: Int,
    page: GalleryPage?,
    isCurrentPage: Boolean,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val thumbUrl = page?.thumbUrl?.takeIf(String::isNotBlank)
        ?: page?.imageUrl?.takeIf(String::isNotBlank)
        ?: ""
    val pageWidth = page?.width
    val pageHeight = page?.height
    val ratio = if (pageWidth != null && pageHeight != null && pageWidth > 0 && pageHeight > 0) {
        RavenFormatters.clampAspectRatio(pageWidth.toFloat() / pageHeight.toFloat(), defaultRatio = 0.72f)
    } else {
        0.72f
    }

    Box(
        modifier = modifier
            .aspectRatio(ratio)
            .clip(RavenShapes.ControlShape)
            .background(colors.surface1)
            .border(
                width = if (isCurrentPage) 2.dp else 0.5.dp,
                color = if (isCurrentPage) colors.crimson else colors.hairline,
                shape = RavenShapes.ControlShape,
            )
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (thumbUrl.isNotBlank()) {
            RavenImage(
                url = thumbUrl,
                siteId = siteId,
                contentDescription = "第 $pageNumber 页",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(colors.surface2, colors.surface1)),
                    ),
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
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "P$pageNumber",
                        style = RavenTabularTextStyle,
                        color = colors.inkSecondary,
                    )
                }
            }
        }

        // Bottom-end tabular page badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .clip(RavenShapes.BadgeShape)
                .background(if (isCurrentPage) colors.crimson else colors.badgeBackdrop)
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

@Composable
private fun GalleryAuthGateCard(
    siteName: String,
    message: String,
    onOpenAccount: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(horizontal = 20.dp, vertical = 28.dp)
            .testTag(ReaderTestTags.GALLERY_DETAIL_AUTH_GATE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.crimson.copy(alpha = 0.14f))
                .border(0.5.dp, colors.crimson.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = "$siteName 需要登录验证",
            style = MaterialTheme.typography.titleLarge,
            color = colors.inkPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onOpenAccount,
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = "前往账号登录",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            OutlinedButton(
                onClick = onRetry,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface2,
                    contentColor = colors.inkPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = "刷新状态",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SelectiveDownloadBottomSheet(
    totalPages: Int,
    resumePage: Int?,
    sheetState: DownloadSheetState,
    onDismiss: () -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onRangeChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalRavenColors.current

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "选择下载页码",
        subtitle = "共 $totalPages 页 · 支持整本或自定义页码范围",
        modifier = Modifier.testTag(ReaderTestTags.GALLERY_DOWNLOAD_SHEET),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RavenGroupedSection {
                RavenGroupedRow(
                    title = "下载整本",
                    subtitle = "完整保存全部 $totalPages 页以便离线阅读",
                    icon = if (sheetState.downloadAll) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    iconTint = if (sheetState.downloadAll) colors.crimson else colors.inkSecondary,
                    onClick = { onSelectAll(true) },
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "自定义页码范围",
                    subtitle = "输入如 1, 3, 5-8（自动去重并校验范围）",
                    icon = if (!sheetState.downloadAll) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    iconTint = if (!sheetState.downloadAll) colors.crimson else colors.inkSecondary,
                    onClick = { onSelectAll(false) },
                )

                if (!sheetState.downloadAll) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = sheetState.rangeInput,
                            onValueChange = onRangeChange,
                            label = { Text("页码范围") },
                            placeholder = { Text("例如：1, 3, 5-8") },
                            singleLine = true,
                            isError = sheetState.validationError != null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                            supportingText = {
                                val err = sheetState.validationError
                                if (err != null) {
                                    Text(text = err, color = colors.error)
                                } else {
                                    Text(
                                        text = "已解析 ${sheetState.parsedPages.size} 页（范围 1..$totalPages）",
                                        style = MaterialTheme.typography.bodySmall.tabular(),
                                        color = colors.success,
                                    )
                                }
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

                        // Quick range presets
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (totalPages > 20) {
                                RavenFilterChip(
                                    label = "前 20 页",
                                    selected = sheetState.rangeInput.trim() == "1-20",
                                    onClick = { onRangeChange("1-20") },
                                )
                            }
                            if (resumePage != null && resumePage in 2..totalPages) {
                                val rangeText = "$resumePage-$totalPages"
                                RavenFilterChip(
                                    label = "第 $resumePage 页至末尾",
                                    selected = sheetState.rangeInput.trim() == rangeText,
                                    onClick = { onRangeChange(rangeText) },
                                )
                            }
                            RavenFilterChip(
                                label = "全部 (1-$totalPages)",
                                selected = sheetState.rangeInput.trim() == "1-$totalPages",
                                onClick = { onRangeChange("1-$totalPages") },
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onConfirm,
                enabled = !sheetState.isSubmitting &&
                    (sheetState.downloadAll || (sheetState.validationError == null && sheetState.parsedPages.isNotEmpty())),
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .ravenSpringPress(),
            ) {
                if (sheetState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.primaryCtaInk,
                    )
                } else {
                    val label = if (sheetState.downloadAll) {
                        "开始下载整本 ($totalPages 页)"
                    } else {
                        "下载已选 ${sheetState.parsedPages.size} 页"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.tabular().copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryMorphGroupedRow(
    title: String,
    onClick: () -> Unit,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
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
        label = "galleryMorphGroupedRowBackdrop",
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
            modifier = Modifier
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
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.inkPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagActionBottomSheet(
    state: TagActionState,
    blockTagsGlobally: Boolean,
    onDismiss: () -> Unit,
    onSearchTag: (crossSite: Boolean) -> Unit,
    onCopyTag: (text: String) -> Unit,
    onToggleSubscription: () -> Unit,
    onToggleBlocked: () -> Unit,
    onSaveCustomTranslation: (String) -> Unit,
) {
    val colors = LocalRavenColors.current
    var isEditingTranslation by remember(state.tag.slug, state.tag.namespace) { mutableStateOf(false) }
    var editedChineseName by remember(state.tag.slug, state.tag.namespace, state.translatedLabel) {
        mutableStateOf(
            if (state.translatedLabel != state.tag.slug && state.translatedLabel != state.tag.label) {
                state.translatedLabel
            } else {
                ""
            },
        )
    }

    val subtitle = buildList {
        if (state.tag.namespace.isNotBlank()) add(state.tag.namespace)
        if (state.tag.slug != state.translatedLabel) add(state.tag.slug)
    }.joinToString(" · ").takeIf(String::isNotBlank)

    val copyText = tagClipboardText(state.translatedLabel, state.tag)
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = state.translatedLabel,
        subtitle = subtitle,
        modifier = Modifier.testTag(ReaderTestTags.GALLERY_TAG_ACTION_SHEET),
    ) {
        RavenGroupedSection(title = "搜索标签") {
            RavenGroupedRow(
                title = "本站搜索该标签",
                subtitle = "在当前搜索站点查找该标签作品",
                icon = Icons.Outlined.Search,
                showChevron = true,
                onClick = { onSearchTag(false) },
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "跨站搜索该标签",
                subtitle = "在全部可搜索站点中查找该标签作品",
                icon = Icons.Outlined.TravelExplore,
                showChevron = true,
                onClick = { onSearchTag(true) },
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "复制标签",
                subtitle = "复制可读标签名「$copyText」到剪贴板",
                icon = Icons.Outlined.ContentCopy,
                enabled = copyText.isNotBlank(),
                onClick = { onCopyTag(copyText) },
            )
            RavenGroupedDivider(insetWithIcon = true)
            GalleryMorphGroupedRow(
                title = if (state.isSubscribed) "取消订阅标签更新" else "订阅标签更新",
                onClick = onToggleSubscription,
                leadingIcon = {
                    RavenMorphToggleIcon(
                        active = state.isSubscribed,
                        activeGlyph = RavenMorphIcons.BellActive,
                        inactiveGlyph = RavenMorphIcons.BellOutline,
                        activeTint = colors.crimson,
                        inactiveTint = colors.inkPrimary,
                        size = 18.dp,
                        springPreset = MorphSpringPreset.BOUNCY,
                    )
                },
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "编辑中文译名",
                subtitle = if (state.translatedLabel != state.tag.slug) {
                    "当前译名：${state.translatedLabel}"
                } else {
                    "为该标签自定义中文译名并立即全局生效"
                },
                icon = Icons.Outlined.Translate,
                iconTint = if (isEditingTranslation) colors.crimson else colors.inkPrimary,
                onClick = { isEditingTranslation = !isEditingTranslation },
                modifier = Modifier.testTag("gallery_tag_edit_translation_row"),
            )
            if (isEditingTranslation) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = editedChineseName,
                        onValueChange = { editedChineseName = it },
                        label = { Text("中文译名") },
                        placeholder = { Text("输入该标签的中文译名") },
                        singleLine = true,
                        shape = RavenShapes.ControlShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.crimson,
                            unfocusedBorderColor = colors.outlineSubtle,
                            focusedTextColor = colors.inkPrimary,
                            unfocusedTextColor = colors.inkPrimary,
                            focusedLabelColor = colors.crimson,
                            unfocusedLabelColor = colors.inkSecondary,
                            cursorColor = colors.crimson,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gallery_tag_edit_translation_field"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { isEditingTranslation = false },
                            shape = RavenShapes.PillShape,
                            border = BorderStroke(0.5.dp, colors.outlineSubtle),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = colors.surface2,
                                contentColor = colors.inkSecondary,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                onSaveCustomTranslation(editedChineseName)
                                isEditingTranslation = false
                            },
                            enabled = editedChineseName.trim().isNotEmpty(),
                            shape = RavenShapes.PillShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primaryCtaContainer,
                                contentColor = colors.primaryCtaInk,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .testTag("gallery_tag_edit_translation_save"),
                        ) {
                            Text(
                                text = "保存译名",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = when {
                    state.isBlocked -> "取消屏蔽该标签"
                    blockTagsGlobally -> "全局屏蔽该标签"
                    else -> "在当前站点屏蔽该标签"
                },
                subtitle = when {
                    state.isBlocked -> "当前已被过滤隐藏，点击立即解除屏蔽"
                    blockTagsGlobally -> "屏蔽后将在全部站点同步生效并自动注入搜索排除"
                    else -> "仅影响当前站点，不污染其他来源"
                },
                icon = Icons.Outlined.Block,
                destructive = !state.isBlocked,
                onClick = onToggleBlocked,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorActionBottomSheet(
    author: AuthorRef,
    onDismiss: () -> Unit,
    onCopyAuthorName: () -> Unit,
    onSearchAuthor: (crossSite: Boolean) -> Unit,
) {
    val displayName = authorClipboardText(author)
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = displayName.ifBlank { "作者" },
        subtitle = author.role.takeIf { it.isNotBlank() } ?: "作者操作",
        modifier = Modifier.testTag("gallery_author_action_sheet"),
    ) {
        RavenGroupedSection {
            RavenGroupedRow(
                title = "复制作者名",
                subtitle = "复制「$displayName」到剪贴板",
                icon = Icons.Outlined.ContentCopy,
                enabled = displayName.isNotBlank(),
                onClick = onCopyAuthorName,
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "本站搜索作者",
                subtitle = "在当前搜索站点查找该作者作品",
                icon = Icons.Outlined.Search,
                showChevron = true,
                onClick = { onSearchAuthor(false) },
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "跨站搜索作者",
                subtitle = "在全部可搜索站点中查找该作者作品",
                icon = Icons.Outlined.TravelExplore,
                showChevron = true,
                onClick = { onSearchAuthor(true) },
            )
        }
    }
}

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
    title: String = "选集",
    modifier: Modifier = Modifier,
    fillAvailableHeight: Boolean = false,
    readState: ChapterPickerReadState = ChapterPickerReadState(),
    onOpenNextUnread: ((MediaItem) -> Unit)? = null,
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
                        else -> "共 ${chapters.size} 章 · 点击切换"
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
                    )
                }
            }
        }
    }
}


@Composable
private fun GalleryChapterTile(
    title: String,
    selected: Boolean,
    progress: ReadingProgress?,
    onClick: () -> Unit,
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
            .clickable(role = Role.Button, onClick = onClick)
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
