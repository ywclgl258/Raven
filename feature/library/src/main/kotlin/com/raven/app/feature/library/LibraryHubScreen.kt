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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.storage.LocalBook
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import org.koin.androidx.compose.koinViewModel

/**
 * 「片库」 Asset & Management Hub (`LibraryHubScreen`).
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
        modifier = modifier,
    )
}

@Composable
fun LibraryHubScreenContent(
    uiState: LibraryHubUiState,
    navigator: LibraryNavigator,
    onSelectTab: (LibraryHubTab) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
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
            title = "片库",
            subtitle = "片单、智能聚合与本地资产",
            actions = {
                IconButton(
                    onClick = { showOpenLinkSheet = true },
                    modifier = Modifier.testTag("library_open_link_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = "从链接打开",
                        tint = colors.inkPrimary,
                    )
                }
                IconButton(
                    onClick = {
                        when (uiState.selectedTab) {
                            LibraryHubTab.SMART_SHELVES -> showCreateSmartShelfSheet = true
                            else -> showCreateShelfSheet = true
                        }
                    },
                    modifier = Modifier.testTag("library_create_action_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "新建片单",
                        tint = colors.inkPrimary,
                    )
                }
            },
        )

        LazyColumn(
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

            // 4 Quick Metric Tiles (历史 / 收藏 / 下载 / 更新)
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
                        title = "收藏",
                        count = uiState.favoriteCount,
                        icon = Icons.Outlined.FavoriteBorder,
                        onClick = navigator::openFavorites,
                        testTag = "metric_tile_favorites",
                        modifier = Modifier.weight(1f),
                    )
                    LibraryMetricTile(
                        title = "下载",
                        count = uiState.completedDownloadCount,
                        icon = Icons.Outlined.Download,
                        highlightBadgeCount = uiState.activeDownloadCount,
                        subtitle = if (uiState.activeDownloadCount > 0) "下载中" else "下载",
                        onClick = navigator::openDownloads,
                        testTag = "metric_tile_downloads",
                        modifier = Modifier.weight(1f),
                    )
                    LibraryMetricTile(
                        title = "更新",
                        count = uiState.unreadInboxCount,
                        icon = Icons.Outlined.NotificationsActive,
                        highlightBadgeCount = uiState.unreadInboxCount,
                        onClick = navigator::openGlobalInbox,
                        testTag = "metric_tile_updates",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 3 Primary Tabs: 我的片单 / 智能片单 / 本地内容
            item(key = "hub_tabs") {
                RavenFilterChipRow(
                    items = LibraryHubTab.entries,
                    selectedItem = uiState.selectedTab,
                    labelProvider = { it.label },
                    onItemSelected = onSelectTab,
                    keyProvider = { it.name },
                    modifier = Modifier.testTag("library_hub_tabs"),
                )
            }

            when (uiState.selectedTab) {
                LibraryHubTab.SHELVES -> {
                    item(key = "shelves_header") {
                        RavenSectionHeader(
                            title = "我的片单",
                            subtitle = "共 ${uiState.shelves.size} 个片单",
                            actionLabel = "新建片单",
                            onActionClick = { showCreateShelfSheet = true },
                        )
                    }
                    items(
                        items = uiState.shelves,
                        key = { "shelf_${it.shelf.id}" },
                    ) { summary ->
                        ShelfSummaryCard(
                            summary = summary,
                            onClick = { navigator.openShelfDetail(summary.shelf.id) },
                            onEditClick = { editingShelf = summary.shelf },
                            onTogglePinClick = { onToggleShelfPinned(summary.shelf) },
                            onDeleteClick = if (summary.shelf.isSystem || summary.shelf.id == Shelf.WATCH_LATER_ID) {
                                null
                            } else {
                                { deletingShelf = summary.shelf }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                LibraryHubTab.SMART_SHELVES -> {
                    item(key = "smart_shelves_header") {
                        RavenSectionHeader(
                            title = "智能片单",
                            subtitle = "按规则动态聚合未完成、离线与近期作品",
                            actionLabel = "新建智能片单",
                            onActionClick = { showCreateSmartShelfSheet = true },
                        )
                    }
                    if (uiState.smartShelves.isEmpty()) {
                        item(key = "smart_shelves_empty") {
                            RavenEmptyState(
                                title = "还没有智能片单",
                                message = "创建一个动态规则片单，自动追踪未看完作品、已离线未看内容或近 30 天新增收藏。",
                                icon = Icons.Outlined.AutoAwesome,
                                actionLabel = "新建智能片单",
                                onActionClick = { showCreateSmartShelfSheet = true },
                            )
                        }
                    } else {
                        items(
                            items = uiState.smartShelves,
                            key = { "smart_${it.smartShelf.id}" },
                        ) { summary ->
                            SmartShelfSummaryCard(
                                summary = summary,
                                onClick = { navigator.openSmartShelfDetail(summary.smartShelf.id) },
                                onEditClick = { editingSmartShelf = summary.smartShelf },
                                onDeleteClick = { deletingSmartShelf = summary.smartShelf },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
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
                        item(key = "local_books_grid") {
                            LocalBooksPreviewGrid(
                                books = uiState.localBooks,
                                mediaItems = uiState.localBookItems,
                                onOpenBook = { book ->
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
                                onRemoveBook = { removingLocalBook = it },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
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
                        subtitle = "管理各站点关注的标签与作者动态",
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
}

@Composable
private fun ShelfSummaryCard(
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
                modifier = Modifier.size(40.dp),
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
private fun SmartShelfSummaryCard(
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

@Composable
private fun LocalImportPanel(
    importState: LocalImportState,
    onImportArchive: () -> Unit,
    onImportFolder: () -> Unit,
    onImportVideo: () -> Unit,
    onOpenFromLink: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (importState.isImporting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.crimson,
                )
                Text(
                    text = importState.progressMessage ?: "正在导入本地内容…",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkPrimary,
                )
            }
        } else if (!importState.lastMessage.isNullOrBlank()) {
            LibraryFeedbackBanner(
                message = importState.lastMessage,
                tone = if (importState.isError) RavenStatusTone.ERROR else RavenStatusTone.SUCCESS,
                onDismiss = onDismissStatus,
            )
        }

        RavenGroupedSection(title = "快速导入与打开") {
            RavenGroupedRow(
                title = "导入 CBZ / ZIP 漫画包",
                subtitle = "自动自然排序并按内容去重",
                icon = Icons.Outlined.FolderZip,
                enabled = !importState.isImporting,
                showChevron = true,
                onClick = onImportArchive,
                modifier = Modifier.testTag("import_cbz_row"),
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "导入图片目录",
                subtitle = "选择包含漫画或写真图页的文件夹",
                icon = Icons.Outlined.FolderOpen,
                enabled = !importState.isImporting,
                showChevron = true,
                onClick = onImportFolder,
                modifier = Modifier.testTag("import_folder_row"),
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "导入本地视频",
                subtitle = "支持 MP4 / MKV / WebM / MOV 等常见格式",
                icon = Icons.Outlined.Movie,
                enabled = !importState.isImporting,
                showChevron = true,
                onClick = onImportVideo,
                modifier = Modifier.testTag("import_video_row"),
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "从链接打开",
                subtitle = "自动识别所有已注册站点链接",
                icon = Icons.Outlined.Link,
                showChevron = true,
                onClick = onOpenFromLink,
                modifier = Modifier.testTag("local_tab_open_link_row"),
            )
        }
    }
}

@Composable
private fun LocalBooksPreviewGrid(
    books: List<LocalBook>,
    mediaItems: Map<String, MediaItem>,
    onOpenBook: (LocalBook) -> Unit,
    onRemoveBook: (LocalBook) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = computeLibraryGridColumns(maxWidth.value.toInt())
        val rows = remember(books, columns) { books.chunked(columns) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { rowBooks ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowBooks.forEach { book ->
                        val cover = mediaItems[book.id]?.coverUrl.orEmpty()
                        LocalBookGridCard(
                            book = book,
                            coverUrl = cover,
                            onClick = { onOpenBook(book) },
                            onRemoveClick = { onRemoveBook(book) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowBooks.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalBookGridCard(
    book: LocalBook,
    coverUrl: String,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(
                width = if (selected == true) 1.5.dp else 0.5.dp,
                color = if (selected == true) colors.crimson else colors.hairline,
                shape = RavenShapes.CardShape,
            )
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("local_book_card_${book.id}"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (book.kind == MediaKind.VIDEO) 16f / 10f else 0.78f)
                .background(colors.surface2),
        ) {
            if (coverUrl.isNotBlank()) {
                RavenImage(
                    url = coverUrl,
                    siteId = SiteId.LOCAL,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (book.kind == MediaKind.VIDEO) Icons.Outlined.Movie else Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = colors.inkTertiary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RavenShapes.BadgeShape)
                    .background(colors.badgeBackdrop)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (book.kind == MediaKind.VIDEO) "视频" else "${book.files.size}P",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.inkPrimary,
                )
            }

            if (selected != null) {
                SelectCheckBadge(
                    selected = selected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(colors.badgeBackdrop)
                        .clickable(role = Role.Button, onClick = onRemoveClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多操作",
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = RavenFormatters.formatByteSize(book.bytes),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = colors.inkSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Single-screen "从链接打开" `RavenBottomSheet` powered by `SiteRegistry.resolveLinkFromText`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenFromLinkBottomSheet(
    onResolveLink: (String) -> ResolvedLinkPreview?,
    onOpenResolved: (ResolvedLinkPreview) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var inputText by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val preview = remember(inputText) {
        if (inputText.isBlank()) null else onResolveLink(inputText)
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "从链接打开",
        subtitle = "粘贴任意支持站点的作品页、阅读页或标签链接",
        modifier = Modifier.testTag("open_link_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LibraryTextInputField(
                value = inputText,
                onValueChange = {
                    inputText = it
                    attempted = false
                },
                placeholder = "https://… 或包含链接的分享文本",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                imeAction = ImeAction.Go,
                onImeAction = {
                    attempted = true
                    preview?.let(onOpenResolved)
                },
                isError = attempted && preview == null,
                supportingText = when {
                    preview != null -> "已识别：${preview.siteLabel} · ${preview.kindLabel}"
                    attempted -> "未能识别该链接，请确认来自受支持的站点"
                    else -> null
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        if (pasted.isNotBlank()) {
                            inputText = pasted
                            attempted = true
                        }
                    },
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface1,
                        contentColor = colors.inkPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("粘贴剪贴板", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        attempted = true
                        preview?.let(onOpenResolved)
                    },
                    enabled = inputText.isNotBlank(),
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .ravenSpringPress()
                        .testTag("open_link_confirm_button"),
                ) {
                    Text(
                        text = "打开链接",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}
