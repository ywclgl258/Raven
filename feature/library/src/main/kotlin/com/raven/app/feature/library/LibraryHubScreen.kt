package com.raven.app.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.designsystem.component.RavenAnimatedCounter
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenCompactMediaRow
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFeatureTutorialDialog
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenSegmentedTabs
import com.raven.app.core.designsystem.component.RavenTutorialStep
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenMorphIconButton
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.component.ravenSquashPress
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.storage.LocalBook
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import org.koin.androidx.compose.koinViewModel

/**
 * 「鸦巢」 Asset & Management Hub (`LibraryHubScreen`).
 */
@Composable
fun LibraryHubScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    viewModel: LibraryHubViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val archiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importArchive(uri)
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importDirectory(uri)
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importVideo(uri)
    }

    LibraryHubScreenContent(
        uiState = uiState,
        navigator = navigator,
        onSelectTab = viewModel::selectTab,
        onRemoveFromWatchLater = viewModel::removeFromWatchLater,
        onRefreshSmartRecommendations = { viewModel.refreshSmartRecommendations(force = true) },
        onCreateShelf = viewModel::createShelf,
        onUpdateShelf = viewModel::updateShelf,
        onToggleShelfPinned = viewModel::toggleShelfPinned,
        onDeleteShelf = viewModel::deleteShelf,
        onSaveSmartShelf = viewModel::saveSmartShelf,
        onDeleteSmartShelf = viewModel::deleteSmartShelf,
        onResolveLink = viewModel::resolveLinkText,
        onImportArchiveClick = {
            archiveLauncher.launch(
                arrayOf(
                    "application/zip",
                    "application/x-cbz",
                    "application/vnd.comicbook+zip",
                    "application/octet-stream",
                ),
            )
        },
        onImportFolderClick = { folderLauncher.launch(null) },
        onImportVideoClick = { videoLauncher.launch(arrayOf("video/*")) },
        onRemoveLocalBook = viewModel::removeLocalBook,
        onDismissMessage = viewModel::clearMessage,
        onDismissImportStatus = viewModel::clearImportStatus,
        onCancelImport = viewModel::cancelImport,
        onRetryImport = viewModel::retryImport,
        onCompleteTutorial = viewModel::completeLibraryTutorial,
        onSkipAllTutorials = viewModel::skipAllTutorials,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryHubScreenContent(
    uiState: LibraryHubUiState,
    navigator: LibraryNavigator,
    onSelectTab: (LibraryHubTab) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFromWatchLater: (ShelfItem) -> Unit = {},
    onRefreshSmartRecommendations: () -> Unit = {},
    onCreateShelf: (name: String, note: String, isPinned: Boolean) -> Unit,
    onUpdateShelf: (shelfId: String, name: String, note: String, isPinned: Boolean) -> Unit,
    onToggleShelfPinned: (Shelf) -> Unit,
    onDeleteShelf: (String) -> Unit,
    onSaveSmartShelf: (
        id: String,
        name: String,
        rule: SmartShelfRule,
        mediaKindFilter: MediaKind?,
        siteIdFilter: SiteId?,
        isPinned: Boolean,
    ) -> Unit,
    onDeleteSmartShelf: (String) -> Unit,
    onResolveLink: (String) -> ResolvedLinkPreview?,
    onImportArchiveClick: () -> Unit,
    onImportFolderClick: () -> Unit,
    onImportVideoClick: () -> Unit,
    onRemoveLocalBook: (String) -> Unit,
    onDismissMessage: () -> Unit,
    onDismissImportStatus: () -> Unit,
    onCancelImport: () -> Unit = {},
    onRetryImport: () -> Unit = {},
    onCompleteTutorial: () -> Unit = {},
    onSkipAllTutorials: () -> Unit = {},
) {
    val colors = LocalRavenColors.current
    val listState = rememberLazyListState()
    var showCreateShelfSheet by rememberSaveable { mutableStateOf(false) }
    var editingShelf by remember { mutableStateOf<Shelf?>(null) }
    var deletingShelf by remember { mutableStateOf<Shelf?>(null) }

    var showCreateSmartShelfSheet by rememberSaveable { mutableStateOf(false) }
    var editingSmartShelf by remember { mutableStateOf<SmartShelf?>(null) }
    var deletingSmartShelf by remember { mutableStateOf<SmartShelf?>(null) }

    var showOpenLinkSheet by rememberSaveable { mutableStateOf(false) }
    var removingLocalBook by remember { mutableStateOf<LocalBook?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("library_hub_screen"),
    ) {
        RavenTopBar(
            title = "鸦巢",
            subtitle = "稍后观看、智能推荐、下载收藏与本地内容",
            actions = {
                RavenMorphIconButton(
                    glyph = RavenMorphIcons.Link,
                    contentDescription = "从链接打开",
                    onClick = { showOpenLinkSheet = true },
                    tint = colors.inkPrimary,
                    modifier = Modifier.testTag("library_open_link_button"),
                )
            },
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val localGridColumns = computeLibraryGridColumns(maxWidth.value.toInt())
            val localBookRows = remember(uiState.localBooks, localGridColumns) {
                uiState.localBooks.chunked(localGridColumns)
            }
            val recommendationRows = remember(uiState.smartRecommendations, localGridColumns) {
                uiState.smartRecommendations.chunked(localGridColumns)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            if (!uiState.message.isNullOrBlank()) {
                item(key = "hub_feedback") {
                    LibraryFeedbackBanner(
                        message = uiState.message,
                        tone = RavenStatusTone.ACCENT,
                        onDismiss = onDismissMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // 4. 一级入口：下载与收藏（大卡片 + 两个大按钮）
            item(key = "hub_downloads_favorites") {
                LibraryDownloadsFavoritesCard(
                    completedDownloads = uiState.completedDownloadCount,
                    completedDownloadBytes = uiState.completedDownloadBytes,
                    activeDownloads = uiState.activeDownloadCount,
                    favorites = uiState.favoriteCount,
                    onOpenDownloads = { navigator.openDownloads() },
                    onOpenFavorites = { navigator.openFavorites() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 5. 次级指标：历史 / 更新
            item(key = "hub_metric_tiles") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("library_metric_tiles"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryMetricTile(
                        title = "历史",
                        count = uiState.historyCount,
                        icon = Icons.Outlined.History,
                        onClick = navigator::openHistory,
                        testTag = "metric_tile_history",
                        modifier = Modifier.weight(1f),
                    )
                    LibraryMetricTile(
                        title = "订阅更新",
                        count = uiState.unreadInboxCount,
                        icon = Icons.Outlined.NotificationsActive,
                        highlightBadgeCount = uiState.unreadInboxCount,
                        onClick = navigator::openGlobalInbox,
                        testTag = "metric_tile_updates",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 3 Primary Tabs: 稍后观看 / 智能推荐 / 本地内容
            item(key = "hub_tabs") {
                RavenSegmentedTabs(
                    items = LibraryHubTab.entries,
                    selectedItem = uiState.selectedTab,
                    labelProvider = { tab ->
                        when (tab) {
                            LibraryHubTab.WATCH_LATER -> "${tab.label} (${uiState.watchLaterItems.size})"
                            LibraryHubTab.SMART_RECOMMENDATIONS -> "${tab.label} (${uiState.smartRecommendations.size})"
                            LibraryHubTab.LOCAL -> "${tab.label} (${uiState.localBooks.size})"
                        }
                    },
                    onItemSelected = onSelectTab,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("library_hub_tabs"),
                )
            }

            when (uiState.selectedTab) {
                LibraryHubTab.WATCH_LATER -> {
                    item(key = "watch_later_header") {
                        RavenSectionHeader(
                            title = "稍后观看",
                            subtitle = "共 ${uiState.watchLaterItems.size} 项待看作品",
                            actionLabel = if (uiState.watchLaterItems.isNotEmpty()) "管理全部" else null,
                            onActionClick = if (uiState.watchLaterItems.isNotEmpty()) {
                                { navigator.openShelfDetail(Shelf.WATCH_LATER_ID) }
                            } else {
                                null
                            },
                        )
                    }
                    if (uiState.watchLaterItems.isEmpty()) {
                        item(key = "watch_later_empty") {
                            RavenEmptyState(
                                title = "稍后观看列表为空",
                                message = "在视频详情页轻点「稍后看」，即可加入这里随时续看；漫画与写真用收藏或追更管理。",
                                icon = Icons.Outlined.WatchLater,
                            )
                        }
                    } else {
                        items(
                            items = uiState.watchLaterItems,
                            key = { "wl_${it.compositeKey}" },
                        ) { shelfItem ->
                            val siteLabel = uiState.siteLabels[shelfItem.siteId] ?: shelfItem.siteId.value
                            val meta = buildList {
                                add(siteLabel)
                                add(shelfItem.kind.displayLabel())
                                shelfItem.pageCount?.takeIf { it > 0 }?.let { add("${it} 页") }
                                shelfItem.durationSeconds?.takeIf { it > 0 }?.let {
                                    add(RavenFormatters.formatDuration(it))
                                }
                            }.joinToString(" · ")
                            val mediaItem = MediaItem(
                                siteId = shelfItem.siteId,
                                id = shelfItem.mediaId,
                                url = shelfItem.url,
                                kind = shelfItem.kind,
                                title = shelfItem.title,
                                coverUrl = shelfItem.coverUrl,
                                durationSeconds = shelfItem.durationSeconds,
                                pageCount = shelfItem.pageCount,
                            )
                            RavenCompactMediaRow(
                                item = mediaItem,
                                subtitle = meta,
                                badgeText = if (shelfItem.completed) "已看完" else null,
                                onClick = {
                                    if (shelfItem.kind.isVideo) {
                                        navigator.openVideo(
                                            siteId = shelfItem.siteId,
                                            mediaId = shelfItem.mediaId,
                                            url = shelfItem.url,
                                            title = shelfItem.title,
                                            coverUrl = shelfItem.coverUrl,
                                        )
                                    } else {
                                        navigator.openGallery(
                                            siteId = shelfItem.siteId,
                                            mediaId = shelfItem.mediaId,
                                            url = shelfItem.url,
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(
                                        onClick = { onRemoveFromWatchLater(shelfItem) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "移出稍后观看",
                                            tint = colors.inkTertiary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 16.dp),
                            )
                        }
                    }
                }

                LibraryHubTab.SMART_RECOMMENDATIONS -> {
                    item(key = "smart_rec_header") {
                        RavenSectionHeader(
                            title = "智能推荐",
                            subtitle = if (uiState.likedWorksCount > 0) {
                                "基于本地 ${uiState.likedWorksCount} 部点赞作品的标签权重实时分析"
                            } else {
                                "根据本地点赞作品分析标签偏好权重"
                            },
                            actionLabel = if (uiState.likedWorksCount > 0 && !uiState.isLoadingRecommendations) "换一批" else null,
                            onActionClick = if (uiState.likedWorksCount > 0 && !uiState.isLoadingRecommendations) {
                                onRefreshSmartRecommendations
                            } else {
                                null
                            },
                        )
                    }

                    if (uiState.likedTagWeights.isNotEmpty()) {
                        item(key = "smart_rec_tag_weights") {
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
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "你的高权重偏好标签",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.inkPrimary,
                                    )
                                    Text(
                                        text = "轻点搜索",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.inkTertiary,
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    uiState.likedTagWeights.take(10).forEach { entry ->
                                        val weightPct = (entry.normalizedWeight * 100f).toInt().coerceIn(10, 100)
                                        Row(
                                            modifier = Modifier
                                                .clip(RavenShapes.PillShape)
                                                .background(colors.crimson.copy(alpha = 0.08f + entry.normalizedWeight * 0.14f))
                                                .border(
                                                    width = 0.5.dp,
                                                    color = colors.crimson.copy(alpha = 0.25f + entry.normalizedWeight * 0.35f),
                                                    shape = RavenShapes.PillShape,
                                                )
                                                .ravenSpringPress()
                                                .clickable(role = Role.Button) {
                                                    val query = entry.tag.slug.ifBlank { entry.displayLabel }
                                                    navigator.openSearch(initialQuery = query, crossSite = true)
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Text(
                                                text = entry.displayLabel,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = colors.inkPrimary,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = "${weightPct}%",
                                                style = MaterialTheme.typography.labelSmall.tabular(),
                                                color = colors.crimson,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    when {
                        uiState.likedWorksCount == 0 -> {
                            item(key = "smart_rec_no_likes") {
                                RavenEmptyState(
                                    title = "还没有点赞偏好数据",
                                    message = "在作品详情页轻点「点赞」按钮，软件会在本地记录该作品的标签并按频次与时效分析权重，自动为你推荐高匹配度作品。",
                                    icon = Icons.Outlined.ThumbUp,
                                )
                            }
                        }
                        uiState.isLoadingRecommendations && uiState.smartRecommendations.isEmpty() -> {
                            item(key = "smart_rec_loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            color = colors.crimson,
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(28.dp),
                                        )
                                        Text(
                                            text = "正在按你的高权重标签匹配推荐作品…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.inkSecondary,
                                        )
                                    }
                                }
                            }
                        }
                        uiState.smartRecommendations.isEmpty() -> {
                            item(key = "smart_rec_empty") {
                                RavenEmptyState(
                                    title = "暂无推荐结果",
                                    message = uiState.recommendationsError ?: "点击刷新重新根据你的点赞偏好拉取推荐作品。",
                                    icon = Icons.Outlined.AutoAwesome,
                                    actionLabel = "刷新推荐",
                                    onActionClick = onRefreshSmartRecommendations,
                                )
                            }
                        }
                        else -> {
                            itemsIndexed(
                                items = recommendationRows,
                                key = { idx, row -> "smart_rec_row_${idx}_${row.first().item.compositeKey}" },
                            ) { _, rowCards ->
                                Row(
                                    modifier = Modifier
                                        .animateItem()
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    rowCards.forEach { card ->
                                        SmartRecommendationGridCard(
                                            card = card,
                                            onClick = { navigator.openMedia(card.item) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(localGridColumns - rowCards.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                LibraryHubTab.LOCAL -> {
                    item(key = "local_import_controls") {
                        LocalImportPanel(
                            importState = uiState.importState,
                            onImportArchive = onImportArchiveClick,
                            onImportFolder = onImportFolderClick,
                            onImportVideo = onImportVideoClick,
                            onOpenFromLink = { showOpenLinkSheet = true },
                            onDismissStatus = onDismissImportStatus,
                            onCancelImport = onCancelImport,
                            onRetryImport = onRetryImport,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    item(key = "local_books_header") {
                        RavenSectionHeader(
                            title = "本地内容",
                            subtitle = "已导入 ${uiState.localBooks.size} 项",
                            actionLabel = "管理全部",
                            onActionClick = navigator::openLocalLibrary,
                        )
                    }

                    if (uiState.localBooks.isEmpty()) {
                        item(key = "local_books_empty") {
                            RavenEmptyState(
                                title = "本地库暂无内容",
                                message = "支持导入 CBZ/ZIP 漫画压缩包、图片文件夹与本地视频，离线秒开不占外网流量。",
                                icon = Icons.Outlined.FolderZip,
                                actionLabel = "导入 CBZ / ZIP",
                                onActionClick = onImportArchiveClick,
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = localBookRows,
                            key = { idx, row -> "hub_local_row_${idx}_${row.first().id}" },
                        ) { _, rowBooks ->
                            Row(
                                modifier = Modifier
                                    .animateItem()
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                rowBooks.forEach { book ->
                                    val cover = uiState.localBookItems[book.id]?.coverUrl.orEmpty()
                                    LocalBookGridCard(
                                        book = book,
                                        coverUrl = cover,
                                        onClick = {
                                            val item = uiState.localBookItems[book.id]
                                            if (item != null) {
                                                navigator.openMedia(item, offlineOnly = true)
                                            } else if (book.kind == MediaKind.VIDEO) {
                                                navigator.openVideo(
                                                    siteId = SiteId.LOCAL,
                                                    mediaId = book.id,
                                                    url = LocalLibraryImporter.buildLocalUri(book.id),
                                                    title = book.title,
                                                    offlineOnly = true,
                                                )
                                            } else {
                                                navigator.openReader(
                                                    siteId = SiteId.LOCAL,
                                                    mediaId = book.id,
                                                    url = LocalLibraryImporter.buildLocalUri(book.id),
                                                    startPage = 1,
                                                    offlineOnly = true,
                                                )
                                            }
                                        },
                                        onRemoveClick = { removingLocalBook = book },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(localGridColumns - rowBooks.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            // Apple HIG Grouped Shortcuts: 单图收藏 / 标签订阅 / 本地库管理
            item(key = "hub_grouped_shortcuts") {
                RavenGroupedSection(
                    title = "更多库管理",
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("library_grouped_shortcuts"),
                ) {
                    RavenGroupedRow(
                        title = "单图收藏",
                        subtitle = "阅读器内收藏的单页高光画面",
                        value = "${uiState.imageFavoriteCount} 张",
                        icon = Icons.Outlined.Collections,
                        showChevron = true,
                        onClick = navigator::openImageFavorites,
                        modifier = Modifier.testTag("shortcut_image_favorites"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "标签订阅",
                        subtitle = "标签与作者动态 · ${uiState.savedSearchCount} 个保存搜索",
                        value = "${uiState.subscriptionCount} 个",
                        icon = Icons.Outlined.Subscriptions,
                        showChevron = true,
                        onClick = { navigator.openSubscriptions() },
                        modifier = Modifier.testTag("shortcut_subscriptions"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "本地库管理",
                        subtitle = "导入压缩包、图片目录、本地视频与空间清理",
                        value = "${uiState.localBooks.size} 项",
                        icon = Icons.Outlined.VideoLibrary,
                        showChevron = true,
                        onClick = navigator::openLocalLibrary,
                        modifier = Modifier.testTag("shortcut_local_library"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "观看与阅读统计",
                        subtitle = "时长、页数、站点分布与标签偏好",
                        icon = Icons.Outlined.BarChart,
                        showChevron = true,
                        onClick = navigator::openStats,
                        modifier = Modifier.testTag("shortcut_stats"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "从链接打开",
                        subtitle = "粘贴任意支持站点的作品或列表链接",
                        icon = Icons.Outlined.Link,
                        showChevron = true,
                        onClick = { showOpenLinkSheet = true },
                        modifier = Modifier.testTag("shortcut_open_from_link"),
                    )
                }
            }
        }

            RavenScrollToTopButton(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 22.dp),
            )
        }
    }

    // Create / Edit Custom Shelf Sheet
    if (showCreateShelfSheet || editingShelf != null) {
        val target = editingShelf
        ShelfEditorBottomSheet(
            editingShelf = target,
            onSave = { name, note, isPinned ->
                if (target == null) {
                    onCreateShelf(name, note, isPinned)
                } else {
                    onUpdateShelf(target.id, name, note, isPinned)
                }
            },
            onDismiss = {
                showCreateShelfSheet = false
                editingShelf = null
            },
            onDelete = if (target != null && !target.isSystem && target.id != Shelf.WATCH_LATER_ID) {
                {
                    editingShelf = null
                    deletingShelf = target
                }
            } else {
                null
            },
        )
    }

    deletingShelf?.let { shelf ->
        LibraryConfirmBottomSheet(
            title = "删除片单「${shelf.name}」？",
            message = "片单内的作品分组关系将被移除，不会删除历史、收藏或已下载文件。",
            confirmLabel = "删除片单",
            onConfirm = { onDeleteShelf(shelf.id) },
            onDismiss = { deletingShelf = null },
        )
    }

    // Create / Edit Smart Shelf Sheet (single-screen)
    if (showCreateSmartShelfSheet || editingSmartShelf != null) {
        val target = editingSmartShelf
        SmartShelfEditorBottomSheet(
            editingShelf = target,
            siteOptions = uiState.allSiteOptions,
            onSave = { name, rule, mediaKind, siteId, isPinned ->
                onSaveSmartShelf(
                    target?.id.orEmpty(),
                    name,
                    rule,
                    mediaKind,
                    siteId,
                    isPinned,
                )
            },
            onDismiss = {
                showCreateSmartShelfSheet = false
                editingSmartShelf = null
            },
            onDelete = if (target != null) {
                {
                    editingSmartShelf = null
                    deletingSmartShelf = target
                }
            } else {
                null
            },
        )
    }

    deletingSmartShelf?.let { smartShelf ->
        LibraryConfirmBottomSheet(
            title = "删除智能片单「${smartShelf.name}」？",
            message = "仅删除该智能规则配置，不会影响任何作品记录。",
            confirmLabel = "删除",
            onConfirm = { onDeleteSmartShelf(smartShelf.id) },
            onDismiss = { deletingSmartShelf = null },
        )
    }

    removingLocalBook?.let { book ->
        LibraryConfirmBottomSheet(
            title = "移除本地内容「${book.title}」？",
            message = "将永久删除应用本地库中已导入的副本（${RavenFormatters.formatByteSize(book.bytes)}）。",
            confirmLabel = "移除副本",
            onConfirm = { onRemoveLocalBook(book.id) },
            onDismiss = { removingLocalBook = null },
        )
    }

    if (showOpenLinkSheet) {
        OpenFromLinkBottomSheet(
            onResolveLink = onResolveLink,
            onOpenResolved = { preview ->
                showOpenLinkSheet = false
                navigator.openResolvedLink(preview.link, fallbackTitle = preview.siteLabel)
            },
            onDismiss = { showOpenLinkSheet = false },
        )
    }

    if (uiState.showLibraryTutorial) {
        RavenFeatureTutorialDialog(
            featureTitle = "鸦巢功能导览",
            steps = listOf(
                RavenTutorialStep(
                    badge = "稍后观看",
                    title = "直达你的待看清单",
                    description = "在视频详情页点选「稍后看」的作品会汇总到「稍后观看」栏，一键续看或移出；漫画与写真走收藏与追更。",
                    icon = Icons.Outlined.WatchLater,
                ),
                RavenTutorialStep(
                    badge = "智能推荐",
                    title = "基于本地点赞的偏好推荐",
                    description = "给喜欢的作品点「点赞」后，应用只在本地分析这些作品的标签权重，并在「智能推荐」为你匹配高权重偏好作品。",
                    icon = Icons.Outlined.ThumbUp,
                ),
                RavenTutorialStep(
                    badge = "收藏与订阅分工",
                    title = "收藏存作品 · 订阅追标签",
                    description = "「收藏」仅保存作品本身；想追踪特定标签的新发布，可在作品详情页长按标签选择「订阅标签更新」。",
                    icon = Icons.Outlined.Subscriptions,
                ),
            ),
            onComplete = onCompleteTutorial,
            onSkipAll = onSkipAllTutorials,
        )
    }
}
