package com.raven.app.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenCompactMediaRow
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteId
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Custom & Built-In Watch Later Shelf Detail Screen (`ShelfDetailScreen`).
 */
@Composable
fun ShelfDetailScreen(
    shelfId: String,
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    viewModel: ShelfDetailViewModel = koinViewModel(
        key = "shelf_detail_$shelfId",
        parameters = { parametersOf(ShelfDetailTarget.Custom(shelfId)) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) navigator.navigateBack()
    }

    LaunchedEffect(uiState.pendingLaunch) {
        val launch = uiState.pendingLaunch ?: return@LaunchedEffect
        viewModel.consumeLaunchEvent()
        navigator.openMedia(
            item = launch.firstItem,
            offlineOnly = launch.offlineOnly,
            queueToken = launch.queueToken,
        )
    }

    ShelfDetailScreenBody(
        uiState = uiState,
        navigator = navigator,
        screenTestTag = "shelf_detail_screen",
        onSearchQueryChange = viewModel::updateSearchQuery,
        onSelectCompletion = viewModel::selectCompletionFilter,
        onSelectMediaKind = viewModel::selectMediaKindFilter,
        onSelectSite = viewModel::selectSiteFilter,
        onSelectAddedWindow = viewModel::selectAddedWindow,
        onSelectViewSort = viewModel::selectViewSort,
        onResetFilters = viewModel::resetFilters,
        onMoveUp = viewModel::moveItemUp,
        onMoveDown = viewModel::moveItemDown,
        onToggleCompleted = viewModel::toggleItemCompleted,
        onRemoveItem = viewModel::removeItem,
        onStartOnlineQueue = { viewModel.startSequentialOnline() },
        onStartOfflineQueue = { viewModel.startSequentialOffline() },
        onEnqueueMissingDownloads = viewModel::enqueueMissingDownloads,
        onCancelBatchDownload = viewModel::cancelBatchDownload,
        onSaveCustomMetadata = viewModel::updateCustomShelfMetadata,
        onSaveSmartMetadata = null,
        onDeleteShelf = viewModel::deleteCurrentShelf,
        onClearMessage = viewModel::clearMessage,
        modifier = modifier,
    )
}

@Composable
internal fun ShelfDetailScreenBody(
    uiState: ShelfDetailUiState,
    navigator: LibraryNavigator,
    screenTestTag: String,
    onSearchQueryChange: (String) -> Unit,
    onSelectCompletion: (ShelfCompletionFilter) -> Unit,
    onSelectMediaKind: (MediaKind?) -> Unit,
    onSelectSite: (SiteId?) -> Unit,
    onSelectAddedWindow: (ShelfAddedWindow) -> Unit,
    onSelectViewSort: (ShelfViewSort) -> Unit,
    onResetFilters: () -> Unit,
    onMoveUp: (SiteId, String) -> Unit,
    onMoveDown: (SiteId, String) -> Unit,
    onToggleCompleted: (ShelfEntryUi) -> Unit,
    onRemoveItem: (SiteId, String) -> Unit,
    onStartOnlineQueue: () -> Unit,
    onStartOfflineQueue: () -> Unit,
    onEnqueueMissingDownloads: () -> Unit,
    onCancelBatchDownload: () -> Unit,
    onSaveCustomMetadata: ((name: String, note: String, isPinned: Boolean, coverKey: String?, clearCover: Boolean) -> Unit)?,
    onSaveSmartMetadata: ((name: String, rule: com.raven.app.core.model.SmartShelfRule, kind: MediaKind?, siteId: SiteId?, isPinned: Boolean) -> Unit)?,
    onDeleteShelf: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var showOfflinePrepSheet by rememberSaveable { mutableStateOf(false) }
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var activeEntryMenu by remember { mutableStateOf<ShelfEntryUi?>(null) }

    val availableSorts = remember(uiState.isSmartShelf) {
        if (uiState.isSmartShelf) {
            listOf(ShelfViewSort.NEWEST, ShelfViewSort.OLDEST, ShelfViewSort.TITLE_AZ)
        } else {
            ShelfViewSort.entries
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag(screenTestTag),
    ) {
        RavenTopBar(
            title = uiState.title.ifBlank { if (uiState.isSmartShelf) "智能片单" else "片单详情" },
            subtitle = "${uiState.visibleEntries.size} / ${uiState.totalItemCount} 项 · ${uiState.filterState.viewSort.label}",
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
                IconButton(
                    onClick = { showOfflinePrepSheet = true },
                    modifier = Modifier.testTag("shelf_offline_prep_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = "离线准备",
                        tint = colors.inkPrimary,
                    )
                }
                IconButton(
                    onClick = { showSortSheet = true },
                    modifier = Modifier.testTag("shelf_sort_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = "视图排序",
                        tint = colors.inkPrimary,
                    )
                }
                IconButton(
                    onClick = { showEditSheet = true },
                    modifier = Modifier.testTag("shelf_edit_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "编辑片单",
                        tint = colors.inkPrimary,
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!uiState.message.isNullOrBlank()) {
                item(key = "shelf_msg") {
                    LibraryFeedbackBanner(
                        message = uiState.message,
                        tone = RavenStatusTone.ACCENT,
                        onDismiss = onClearMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // Header metadata card (notes / smart rule / sequential CTAs)
            item(key = "shelf_header_card") {
                ShelfHeaderActionsCard(
                    uiState = uiState,
                    onStartOnlineQueue = onStartOnlineQueue,
                    onStartOfflineQueue = onStartOfflineQueue,
                    onOpenOfflinePrep = { showOfflinePrepSheet = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // In-shelf search + collapsible multi-filter bar
            item(key = "shelf_search_and_filter") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RavenSearchField(
                            query = uiState.filterState.searchQuery,
                            onQueryChange = onSearchQueryChange,
                            placeholder = "片单内搜索标题或站点…",
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { showFilters = !showFilters },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (showFilters || uiState.filterState.hasActiveFilters) {
                                        colors.crimson.copy(alpha = 0.16f)
                                    } else {
                                        colors.surface2
                                    },
                                )
                                .border(0.5.dp, colors.hairline, CircleShape)
                                .testTag("shelf_toggle_filters_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FilterList,
                                contentDescription = "展开筛选",
                                tint = if (uiState.filterState.hasActiveFilters) colors.crimson else colors.inkPrimary,
                            )
                        }
                    }

                    val motionReduced = com.raven.app.core.designsystem.theme.LocalMotionReduced.current
                    AnimatedVisibility(
                        visible = showFilters,
                        enter = if (motionReduced) {
                            EnterTransition.None
                        } else {
                            expandVertically(tween(200, easing = com.raven.app.core.designsystem.theme.RavenMotion.HarmonyDecelerationEasing)) +
                                fadeIn(tween(160))
                        },
                        exit = if (motionReduced) {
                            ExitTransition.None
                        } else {
                            shrinkVertically(tween(170, easing = com.raven.app.core.designsystem.theme.RavenMotion.HarmonyDecelerationEasing)) +
                                fadeOut(tween(120))
                        },
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.testTag("shelf_multi_filters"),
                        ) {
                            RavenFilterChipRow(
                                items = ShelfCompletionFilter.entries,
                                selectedItem = uiState.filterState.completionFilter,
                                labelProvider = { it.label },
                                onItemSelected = onSelectCompletion,
                                keyProvider = { it.name },
                            )
                            LibraryMediaFilterChips(
                                selected = LibraryMediaFilter.fromKind(uiState.filterState.mediaKindFilter),
                                onSelect = { onSelectMediaKind(it.mediaKind) },
                            )
                            RavenFilterChipRow(
                                items = ShelfAddedWindow.entries,
                                selectedItem = uiState.filterState.addedWindow,
                                labelProvider = { it.label },
                                onItemSelected = onSelectAddedWindow,
                                keyProvider = { it.name },
                            )
                            LibrarySiteFilterChips(
                                options = uiState.siteOptions,
                                selectedSiteId = uiState.filterState.siteIdFilter,
                                onSelectSite = onSelectSite,
                            )
                            if (uiState.filterState.hasActiveFilters) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(onClick = onResetFilters) {
                                        Text("重置筛选", color = colors.crimson)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.visibleEntries.isEmpty()) {
                item(key = "shelf_empty") {
                    RavenEmptyState(
                        title = if (uiState.totalItemCount == 0) "片单内暂无作品" else "没有匹配的筛选结果",
                        message = if (uiState.totalItemCount == 0) {
                            if (uiState.isSmartShelf) {
                                "暂无满足该智能片单规则的作品。"
                            } else {
                                "在任意作品卡片长按或详情页中可快速加入此片单。"
                            }
                        } else {
                            "尝试调整关键词、类型、完成状态或时间范围筛选。"
                        },
                        actionLabel = if (uiState.filterState.hasActiveFilters) "重置筛选" else null,
                        onActionClick = if (uiState.filterState.hasActiveFilters) onResetFilters else null,
                    )
                }
            } else {
                itemsIndexed(
                    items = uiState.visibleEntries,
                    key = { _, entry -> entry.compositeKey },
                ) { index, entry ->
                    ShelfEntryRow(
                        entry = entry,
                        canMoveUp = uiState.canManualReorder && index > 0,
                        canMoveDown = uiState.canManualReorder && index < uiState.visibleEntries.lastIndex,
                        onClick = {
                            navigator.openMedia(
                                item = entry.mediaItem,
                                startPage = entry.resumePage,
                            )
                        },
                        onMoveUp = { onMoveUp(entry.siteId, entry.mediaId) },
                        onMoveDown = { onMoveDown(entry.siteId, entry.mediaId) },
                        onMenuClick = { activeEntryMenu = entry },
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }

    // View Sort Bottom Sheet (never overwrites manual order)
    if (showSortSheet) {
        ShelfSortBottomSheet(
            availableSorts = availableSorts,
            selectedSort = uiState.filterState.viewSort,
            isSmartShelf = uiState.isSmartShelf,
            onSelectSort = {
                onSelectViewSort(it)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false },
        )
    }

    // Offline Preparation Bottom Sheet
    if (showOfflinePrepSheet) {
        ShelfOfflinePreparationSheet(
            summary = uiState.offlinePrep,
            batchProgress = uiState.batchProgress,
            onEnqueueMissing = onEnqueueMissingDownloads,
            onCancelBatch = onCancelBatchDownload,
            onOpenDownloads = {
                showOfflinePrepSheet = false
                navigator.openDownloads()
            },
            onDismiss = { showOfflinePrepSheet = false },
        )
    }

    // Per-entry Action Sheet
    activeEntryMenu?.let { entry ->
        ShelfEntryActionSheet(
            entry = entry,
            isCustomShelf = !uiState.isSmartShelf,
            onOpenOnline = {
                navigator.openMedia(entry.mediaItem, startPage = entry.resumePage)
            },
            onOpenOffline = if (entry.isOfflineReady) {
                { navigator.openMedia(entry.mediaItem, offlineOnly = true, startPage = entry.resumePage) }
            } else {
                null
            },
            onToggleCompleted = { onToggleCompleted(entry) },
            onRemove = { onRemoveItem(entry.siteId, entry.mediaId) },
            onDismiss = { activeEntryMenu = null },
        )
    }

    // Edit Sheet (Custom Shelf Metadata w/ Custom Cover vs Smart Shelf Editor)
    if (showEditSheet) {
        if (!uiState.isSmartShelf && uiState.shelf != null && onSaveCustomMetadata != null) {
            ShelfMetadataEditBottomSheet(
                shelf = uiState.shelf,
                entries = uiState.allEntries,
                onSave = onSaveCustomMetadata,
                onDelete = if (!uiState.shelf.isSystem && uiState.shelf.id != Shelf.WATCH_LATER_ID) {
                    {
                        showEditSheet = false
                        showDeleteConfirm = true
                    }
                } else {
                    null
                },
                onDismiss = { showEditSheet = false },
            )
        } else if (uiState.isSmartShelf && uiState.smartShelf != null && onSaveSmartMetadata != null) {
            SmartShelfEditorBottomSheet(
                editingShelf = uiState.smartShelf,
                siteOptions = uiState.allSiteOptions,
                onSave = onSaveSmartMetadata,
                onDelete = {
                    showEditSheet = false
                    showDeleteConfirm = true
                },
                onDismiss = { showEditSheet = false },
            )
        }
    }

    if (showDeleteConfirm) {
        LibraryConfirmBottomSheet(
            title = "删除「${uiState.title}」？",
            message = if (uiState.isSmartShelf) {
                "仅删除该智能片单规则，不影响任何作品数据。"
            } else {
                "片单分组将被删除，历史记录、收藏与离线下载均会保留。"
            },
            confirmLabel = "确认删除",
            onConfirm = onDeleteShelf,
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun ShelfHeaderActionsCard(
    uiState: ShelfDetailUiState,
    onStartOnlineQueue: () -> Unit,
    onStartOfflineQueue: () -> Unit,
    onOpenOfflinePrep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (uiState.note.isNotBlank()) {
            Text(
                text = uiState.note,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
            )
        }

        uiState.smartShelf?.let { smart ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RavenStatusPill(text = smart.rule.displayLabel(), tone = RavenStatusTone.ACCENT)
                smart.mediaKindFilter?.let { RavenStatusPill(text = it.displayLabel()) }
                smart.siteIdFilter?.let { sid ->
                    val label = uiState.allSiteOptions.firstOrNull { it.siteId == sid }?.label ?: sid.value
                    RavenStatusPill(text = label)
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RavenStatusPill(
                text = "已离线 ${uiState.offlinePrep.readyItems.size}",
                tone = RavenStatusTone.SUCCESS,
                icon = Icons.Outlined.CloudDone,
            )
            if (uiState.offlinePrep.queuedItems.isNotEmpty()) {
                RavenStatusPill(
                    text = "队列中 ${uiState.offlinePrep.queuedItems.size}",
                    tone = RavenStatusTone.WARNING,
                )
            }
            if (uiState.offlinePrep.missingItems.isNotEmpty()) {
                RavenStatusPill(
                    text = "待下载 ${uiState.offlinePrep.missingItems.size}",
                    tone = RavenStatusTone.NEUTRAL,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onStartOnlineQueue,
                enabled = uiState.visibleEntries.isNotEmpty(),
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .ravenSpringPress()
                    .testTag("shelf_sequential_online_button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "顺序连看",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }

            OutlinedButton(
                onClick = onStartOfflineQueue,
                enabled = uiState.offlinePrep.readyItems.isNotEmpty(),
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface2,
                    contentColor = colors.inkPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("shelf_sequential_offline_button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.OfflinePin,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "仅离线连看",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            IconButton(
                onClick = onOpenOfflinePrep,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudDownload,
                    contentDescription = "离线准备",
                    tint = colors.inkPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ShelfEntryRow(
    entry: ShelfEntryUi,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    RavenCompactMediaRow(
        item = entry.mediaItem,
        onClick = onClick,
        onLongClick = onMenuClick,
        subtitle = buildList {
            add(entry.siteLabel)
            add(entry.kind.displayLabel())
            if (entry.addedAt > 0L) add(formatRelativeTime(entry.addedAt))
        }.joinToString(" · "),
        progressFraction = entry.progressFraction,
        badgeText = if (entry.isOfflineReady) "离线" else null,
        statusContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.completed) {
                    RavenStatusPill(text = "已完成", tone = RavenStatusTone.SUCCESS)
                }
                if (!entry.isOfflineReady && entry.downloadStatus != null && entry.downloadStatus.isInProgressBucket()) {
                    RavenStatusPill(
                        text = entry.downloadStatus.displayLabel(),
                        tone = RavenStatusTone.WARNING,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canMoveUp || canMoveDown) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onMoveUp,
                            enabled = canMoveUp,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowUp,
                                contentDescription = "上移",
                                tint = if (canMoveUp) colors.inkSecondary else colors.inkTertiary.copy(alpha = 0.35f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = onMoveDown,
                            enabled = canMoveDown,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "下移",
                                tint = if (canMoveDown) colors.inkSecondary else colors.inkTertiary.copy(alpha = 0.35f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多操作",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfSortBottomSheet(
    availableSorts: List<ShelfViewSort>,
    selectedSort: ShelfViewSort,
    isSmartShelf: Boolean,
    onSelectSort: (ShelfViewSort) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "视图排序",
        subtitle = if (isSmartShelf) "选择智能片单的展示顺序" else "仅改变当前视图顺序，不会覆盖片单的手动排序",
        modifier = Modifier.testTag("shelf_sort_sheet"),
    ) {
        RavenGroupedSection {
            availableSorts.forEachIndexed { index, sort ->
                if (index > 0) RavenGroupedDivider()
                RavenGroupedRow(
                    title = sort.label,
                    onClick = { onSelectSort(sort) },
                    trailingContent = {
                        if (sort == selectedSort) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "当前选中",
                                tint = colors.crimson,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * Single-screen Offline Preparation `RavenBottomSheet` showing ready/queued/missing summary
 * and one-tap batch download via `DownloadCoordinator.enqueueBatch`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfOfflinePreparationSheet(
    summary: OfflinePreparationSummary,
    batchProgress: com.raven.app.core.media.download.BatchEnqueueProgress,
    onEnqueueMissing: () -> Unit,
    onCancelBatch: () -> Unit,
    onOpenDownloads: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "离线准备",
        subtitle = "统计当前筛选结果的离线就绪情况，支持一键批量缓存",
        modifier = Modifier.testTag("shelf_offline_prep_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RavenGroupedSection(title = "离线状态总览") {
                RavenGroupedRow(
                    title = "已就绪（可离线打开）",
                    value = "${summary.readyItems.size} 项",
                    icon = Icons.Outlined.CloudDone,
                    iconTint = colors.success,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "下载队列中",
                    value = "${summary.queuedItems.size} 项",
                    icon = Icons.Outlined.CloudDownload,
                    iconTint = colors.warning,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "尚未缓存",
                    value = "${summary.missingItems.size} 项",
                    icon = Icons.Outlined.RadioButtonUnchecked,
                    iconTint = colors.inkSecondary,
                )
            }

            if (batchProgress.isRunning || batchProgress.processed > 0) {
                RavenGroupedSection(title = "批量加入进度") {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val fraction = if (batchProgress.total > 0) {
                            (batchProgress.processed.toFloat() / batchProgress.total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            color = colors.crimson,
                            trackColor = colors.surface3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "已处理 ${batchProgress.processed} / ${batchProgress.total} · 成功加入 ${batchProgress.added} 项",
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = colors.inkSecondary,
                        )
                        if (batchProgress.errors.isNotEmpty()) {
                            Text(
                                text = batchProgress.errors.take(3).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (batchProgress.isRunning) {
                    OutlinedButton(
                        onClick = onCancelBatch,
                        shape = RavenShapes.PillShape,
                        border = BorderStroke(0.5.dp, colors.error.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("取消批量加入")
                    }
                } else {
                    OutlinedButton(
                        onClick = onOpenDownloads,
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
                        Text("查看下载与收藏")
                    }
                }

                Button(
                    onClick = onEnqueueMissing,
                    enabled = summary.missingItems.isNotEmpty() && !batchProgress.isRunning,
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .ravenSpringPress()
                        .testTag("shelf_offline_enqueue_button"),
                ) {
                    Text(
                        text = if (summary.missingItems.isEmpty()) "全部已覆盖" else "下载缺失 (${summary.missingItems.size})",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfEntryActionSheet(
    entry: ShelfEntryUi,
    isCustomShelf: Boolean,
    onOpenOnline: () -> Unit,
    onOpenOffline: (() -> Unit)?,
    onToggleCompleted: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = entry.title,
        subtitle = "${entry.siteLabel} · ${entry.kind.displayLabel()}",
    ) {
        RavenGroupedSection {
            RavenGroupedRow(
                title = if (entry.kind.isVideo) "立即播放" else "打开阅读",
                icon = Icons.Outlined.PlayArrow,
                showChevron = true,
                onClick = {
                    onDismiss()
                    onOpenOnline()
                },
            )
            if (onOpenOffline != null) {
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "离线打开",
                    icon = Icons.Outlined.OfflinePin,
                    showChevron = true,
                    onClick = {
                        onDismiss()
                        onOpenOffline()
                    },
                )
            }
            if (isCustomShelf) {
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = if (entry.completed) "标记为未完成" else "标记为已完成",
                    icon = Icons.Outlined.CheckCircle,
                    onClick = {
                        onToggleCompleted()
                        onDismiss()
                    },
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "从此片单移出",
                    icon = Icons.Outlined.DeleteOutline,
                    destructive = true,
                    onClick = {
                        onRemove()
                        onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * Single-screen Shelf Metadata Edit Sheet with custom cover selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfMetadataEditBottomSheet(
    shelf: Shelf,
    entries: List<ShelfEntryUi>,
    onSave: (name: String, note: String, isPinned: Boolean, coverKey: String?, clearCover: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val isSystem = shelf.isSystem || shelf.id == Shelf.WATCH_LATER_ID
    var name by rememberSaveable(shelf.id) { mutableStateOf(shelf.name) }
    var note by rememberSaveable(shelf.id) { mutableStateOf(shelf.note) }
    var isPinned by rememberSaveable(shelf.id) { mutableStateOf(shelf.isPinned) }
    var selectedCoverKey by rememberSaveable(shelf.id) { mutableStateOf(shelf.coverCompositeKey) }

    val coverCandidates = remember(entries) {
        entries.filter { it.coverUrl.isNotBlank() }
    }
    val canSave = name.trim().isNotEmpty()

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "编辑片单设置",
        subtitle = if (isSystem) "内置稍后看支持备注、置顶与自定义封面" else "自定义名称、备注、置顶与封面",
        modifier = Modifier.testTag("shelf_metadata_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RavenGroupedSection(title = "基本信息") {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LibraryTextInputField(
                        value = name,
                        onValueChange = { if (!isSystem) name = it.take(ShelfRepository.MAX_SHELF_NAME_LENGTH) },
                        label = "片单名称",
                        placeholder = "输入片单名称",
                        enabled = !isSystem,
                    )
                    LibraryTextInputField(
                        value = note,
                        onValueChange = { note = it.take(ShelfRepository.MAX_SHELF_NOTE_LENGTH) },
                        label = "备注说明",
                        placeholder = "可选备注…",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                    )
                }
                RavenGroupedDivider()
                RavenGroupedSwitchRow(
                    title = "置顶片单",
                    icon = Icons.Outlined.PushPin,
                    checked = isPinned,
                    onCheckedChange = { isPinned = it },
                )
            }

            if (coverCandidates.isNotEmpty()) {
                RavenGroupedSection(title = "自定义封面") {
                    LazyRow(
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "auto_cover") {
                            val selected = selectedCoverKey == null
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RavenShapes.ControlShape)
                                    .background(colors.surface2)
                                    .border(
                                        width = if (selected) 2.dp else 0.5.dp,
                                        color = if (selected) colors.crimson else colors.hairline,
                                        shape = RavenShapes.ControlShape,
                                    )
                                    .clickable(role = Role.Button) { selectedCoverKey = null },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "自动",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) colors.crimson else colors.inkSecondary,
                                )
                            }
                        }

                        items(
                            items = coverCandidates,
                            key = { "cover_${it.compositeKey}" },
                        ) { entry ->
                            val selected = selectedCoverKey == entry.compositeKey
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RavenShapes.ControlShape)
                                    .border(
                                        width = if (selected) 2.dp else 0.5.dp,
                                        color = if (selected) colors.crimson else colors.hairline,
                                        shape = RavenShapes.ControlShape,
                                    )
                                    .clickable(role = Role.Button) { selectedCoverKey = entry.compositeKey },
                            ) {
                                RavenImage(
                                    url = entry.coverUrl,
                                    siteId = entry.siteId,
                                    contentDescription = entry.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }

            if (onDelete != null) {
                RavenGroupedSection {
                    RavenGroupedRow(
                        title = "删除片单",
                        icon = Icons.Outlined.DeleteOutline,
                        destructive = true,
                        onClick = onDelete,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
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
                    Text("取消")
                }

                Button(
                    onClick = {
                        if (canSave) {
                            onSave(
                                name.trim(),
                                note.trim(),
                                isPinned,
                                selectedCoverKey,
                                selectedCoverKey == null,
                            )
                            onDismiss()
                        }
                    },
                    enabled = canSave,
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
                    Text(
                        text = "保存",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}
