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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenCrimsonProgressBar
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenSegmentedTabs
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.media.download.BatchEnqueueProgress
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.SiteId
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt

/**
 * Unified Downloads Center (`DownloadsScreen`) managing Video (Media3) & Gallery/Comic downloads.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    siteId: SiteId? = null,
    embedded: Boolean = false,
    collectionLayout: LibraryCollectionLayout = defaultCollectionLayout(),
    viewModel: DownloadsViewModel = koinViewModel(
        key = "downloads_${siteId?.value.orEmpty()}",
        parameters = { parametersOf(DownloadsParams(siteId)) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current

    var activeMenuTarget by remember { mutableStateOf<DownloadItem?>(null) }
    var groupEditTarget by remember { mutableStateOf<DownloadItem?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<DownloadItem?>(null) }
    var showCleanCompletedConfirm by rememberSaveable { mutableStateOf(false) }
    var showLayoutSheet by rememberSaveable { mutableStateOf(false) }
    var showGroupManager by rememberSaveable { mutableStateOf(false) }
    var showGroupCreator by rememberSaveable { mutableStateOf(false) }
    var renameGroupTarget by remember { mutableStateOf<DownloadGroupSummary?>(null) }
    var clearGroupTarget by remember { mutableStateOf<DownloadGroupSummary?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("downloads_screen"),
    ) {
        if (!embedded) RavenTopBar(
            title = "下载与收藏",
            subtitle = "已完成 ${uiState.completedCount} 项 · ${RavenFormatters.formatByteSize(uiState.totalCompletedBytes)}",
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
                    onClick = viewModel::syncFromDisk,
                    enabled = !uiState.isSyncingDisk,
                    modifier = Modifier.testTag("downloads_sync_disk_button"),
                ) {
                    if (uiState.isSyncingDisk) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.crimson,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = "同步磁盘离线记录",
                            tint = colors.inkPrimary,
                        )
                    }
                }
            },
        )

        if (collectionLayout == LibraryCollectionLayout.WATERFALL) {
            DownloadsWaterfallContent(
                uiState = uiState,
                viewModel = viewModel,
                navigator = navigator,
                onMenuTarget = { activeMenuTarget = it },
                onOpenLayoutSheet = { showLayoutSheet = true },
                onOpenGroupManager = { showGroupManager = true },
                onCleanCompleted = { showCleanCompletedConfirm = true },
            )
        } else {
            DownloadsListContent(
                uiState = uiState,
                viewModel = viewModel,
                navigator = navigator,
                onMenuTarget = { activeMenuTarget = it },
                onOpenLayoutSheet = { showLayoutSheet = true },
                onOpenGroupManager = { showGroupManager = true },
                onCleanCompleted = { showCleanCompletedConfirm = true },
            )
        }
    }

    if (showLayoutSheet) {
        DownloadLayoutSheet(
            selectedSort = uiState.sort,
            selectedGroupMode = uiState.groupMode,
            customGroupCount = uiState.groupSummaries.size,
            onSelectSort = viewModel::selectSort,
            onSelectGroupMode = viewModel::selectGroupMode,
            onManageGroups = {
                showLayoutSheet = false
                showGroupManager = true
            },
            onDismiss = { showLayoutSheet = false },
        )
    }

    if (showGroupManager) {
        DownloadGroupManagerSheet(
            groups = uiState.groupSummaries,
            onCreate = {
                showGroupManager = false
                showGroupCreator = true
            },
            onRename = {
                showGroupManager = false
                renameGroupTarget = it
            },
            onClear = {
                showGroupManager = false
                clearGroupTarget = it
            },
            onDismiss = { showGroupManager = false },
        )
    }

    if (showGroupCreator) {
        DownloadGroupCreateSheet(
            items = uiState.allItems,
            siteLabels = uiState.siteLabels,
            onSave = viewModel::createGroup,
            onDismiss = { showGroupCreator = false },
        )
    }

    renameGroupTarget?.let { group ->
        DownloadGroupRenameSheet(
            group = group,
            onSave = { viewModel.renameGroup(group.name, it) },
            onDismiss = { renameGroupTarget = null },
        )
    }

    clearGroupTarget?.let { group ->
        LibraryConfirmBottomSheet(
            title = "移除分组「${group.name}」？",
            message = "${group.itemCount} 个下载项将变为未分组，已有下载记录和缓存文件都会保留。",
            confirmLabel = "移除分组",
            destructive = false,
            onConfirm = { viewModel.clearGroup(group.name) },
            onDismiss = { clearGroupTarget = null },
        )
    }

    // Per-Item Action Bottom Sheet
    val liveMenuTarget = activeMenuTarget?.let { target -> uiState.allItems.firstOrNull { it.id == target.id } }
    liveMenuTarget?.let { item ->
        val canReorder = if (item.kind == DownloadKind.VIDEO) {
            item.status == DownloadStatus.QUEUED
        } else {
            item.status.isInProgressBucket()
        }
        DownloadItemActionSheet(
            item = item,
            siteLabel = uiState.siteLabels[item.siteId] ?: item.siteId.value,
            onOpenOffline = if (item.status == DownloadStatus.COMPLETED) {
                { openDownloadedItem(navigator, item) }
            } else {
                null
            },
            onPause = if (item.status.isActiveTransfer()) {
                { viewModel.pause(item.id) }
            } else {
                null
            },
            onResume = if (item.status == DownloadStatus.PAUSED) {
                { viewModel.resume(item.id) }
            } else {
                null
            },
            onRetry = if (item.status.needsAttention()) {
                { viewModel.retry(item.id) }
            } else {
                null
            },
            onPrioritize = if (canReorder) {
                { viewModel.prioritize(item.id) }
            } else {
                null
            },
            onMoveUp = if (canReorder) {
                { viewModel.movePriority(item.id, +1) }
            } else {
                null
            },
            onMoveDown = if (canReorder) {
                { viewModel.movePriority(item.id, -1) }
            } else {
                null
            },
            onEditGroup = { groupEditTarget = item },
            onInspectIntegrity = if (item.kind != DownloadKind.VIDEO) {
                { viewModel.inspectIntegrity(item) }
            } else {
                null
            },
            onDelete = { deleteConfirmTarget = item },
            onDismiss = { activeMenuTarget = null },
        )
    }

    // Group Assignment Sheet
    groupEditTarget?.let { item ->
        DownloadGroupEditorSheet(
            item = item,
            existingGroups = uiState.availableGroups,
            onSave = { group -> viewModel.setGroup(item.id, group) },
            onDismiss = { groupEditTarget = null },
        )
    }

    // Integrity Audit & Repair Sheet
    uiState.integrityState?.let { state ->
        GalleryIntegrityAuditSheet(
            state = state,
            onRepair = { viewModel.repairGallery(state.item) },
            onDismiss = viewModel::dismissIntegritySheet,
        )
    }

    // Delete Confirmation Sheet
    deleteConfirmTarget?.let { item ->
        LibraryConfirmBottomSheet(
            title = "删除下载「${item.title}」？",
            message = "将移除下载记录并清理对应的本地缓存文件。",
            confirmLabel = "确认删除",
            onConfirm = { viewModel.delete(item) },
            onDismiss = { deleteConfirmTarget = null },
        )
    }

    // Clean Completed Confirmation
    if (showCleanCompletedConfirm) {
        val scopeName = uiState.siteFilter?.let { uiState.siteLabels[it] ?: it.value }
        LibraryConfirmBottomSheet(
            title = "清理 ${uiState.completedCount} 个已完成下载？",
            message = buildString {
                append("将删除")
                append(scopeName?.let { "「$it」" } ?: "全部站点")
                append("的已完成下载记录及其本地缓存文件，预计释放 ")
                append(RavenFormatters.formatByteSize(uiState.totalCompletedBytes))
                append("。进行中、已暂停与需处理的任务不受影响。")
            },
            confirmLabel = "清理已完成",
            onConfirm = { viewModel.deleteCompletedDownloads() },
            onDismiss = { showCleanCompletedConfirm = false },
        )
    }
}

@Composable
private fun DownloadsListContent(
    uiState: DownloadsUiState,
    viewModel: DownloadsViewModel,
    navigator: LibraryNavigator,
    onMenuTarget: (DownloadItem) -> Unit,
    onOpenLayoutSheet: () -> Unit,
    onOpenGroupManager: () -> Unit,
    onCleanCompleted: () -> Unit,
) {
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        if (!uiState.networkPolicy.isConnected) {
            item(key = "dl_net_offline") {
                DownloadsNetworkPolicyBanners(uiState)
            }
        } else if (uiState.networkPolicy.unmeteredOnly && uiState.networkPolicy.isMetered) {
            item(key = "dl_net_metered") {
                DownloadsNetworkPolicyBanners(uiState)
            }
        }

        if (uiState.batchProgress.isRunning || uiState.batchProgress.errors.isNotEmpty()) {
            item(key = "dl_batch_banner") {
                DownloadsBatchProgressBanner(
                    progress = uiState.batchProgress,
                    onCancel = viewModel::cancelBatchEnqueue,
                )
            }
        }

        if (!uiState.message.isNullOrBlank()) {
            item(key = "dl_msg") {
                DownloadsMessageBanner(
                    message = uiState.message,
                    onDismiss = viewModel::clearMessage,
                )
            }
        }

        item(key = "dl_search_and_view") {
            DownloadsSearchAndPresentationRow(
                uiState = uiState,
                onQueryChange = viewModel::updateSearchQuery,
                onToggleFilters = viewModel::toggleFiltersExpanded,
                onOpenLayoutSheet = onOpenLayoutSheet,
            )
        }

        item(key = "dl_status_tabs") {
            DownloadsStatusFilterTabs(
                uiState = uiState,
                onSelectTab = viewModel::selectTab,
                modifier = Modifier.testTag("downloads_status_tabs"),
            )
        }

        if (uiState.hasBatchControls()) {
            item(key = "dl_batch_controls") {
                DownloadsBatchControls(
                    uiState = uiState,
                    onPauseAll = viewModel::pauseAll,
                    onResumeAll = viewModel::resumeAll,
                    onRetryAll = viewModel::retryAllFailed,
                    onCleanCompleted = onCleanCompleted,
                )
            }
        }

        item(key = "dl_collapsible_filters") {
            DownloadsExtraFiltersPanel(
                uiState = uiState,
                viewModel = viewModel,
                onOpenGroupManager = onOpenGroupManager,
            )
        }

        if (uiState.items.isEmpty()) {
            item(key = "dl_empty") {
                DownloadsEmptyState(
                    uiState = uiState,
                    onSearch = navigator::openSearch,
                    onResetFilters = viewModel::resetExtraFilters,
                )
            }
        } else {
            uiState.sections.forEach { section ->
                if (uiState.groupMode != DownloadGroupMode.NONE) {
                    item(key = "dl_section_header_${section.key}") {
                        DownloadSectionHeader(
                            section = section,
                            onToggle = { viewModel.toggleSection(section.key) },
                        )
                    }
                }
                if (!section.isCollapsed) {
                    items(
                        items = section.items,
                        key = { "${section.key}:${it.id}" },
                    ) { item ->
                        DownloadItemCard(
                            item = item,
                            siteLabel = uiState.siteLabels[item.siteId] ?: item.siteId.value,
                            onPrimaryClick = { handleDownloadPrimaryAction(navigator, viewModel, item) },
                            onMenuClick = { onMenuTarget(item) },
                            modifier = Modifier
                                .animateItem()
                                .padding(horizontal = 16.dp),
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
                .padding(end = 18.dp, bottom = 22.dp),
        )
    }
}

@Composable
private fun DownloadsWaterfallContent(
    uiState: DownloadsUiState,
    viewModel: DownloadsViewModel,
    navigator: LibraryNavigator,
    onMenuTarget: (DownloadItem) -> Unit,
    onOpenLayoutSheet: () -> Unit,
    onOpenGroupManager: () -> Unit,
    onCleanCompleted: () -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = computeCollectionWaterfallColumns(maxWidth.value.toInt())

        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .testTag("downloads_waterfall_grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
        ) {
            if (!uiState.networkPolicy.isConnected) {
                item(key = "dl_net_offline", span = StaggeredGridItemSpan.FullLine) {
                    DownloadsNetworkPolicyBanners(uiState, horizontalPadding = 0.dp)
                }
            } else if (uiState.networkPolicy.unmeteredOnly && uiState.networkPolicy.isMetered) {
                item(key = "dl_net_metered", span = StaggeredGridItemSpan.FullLine) {
                    DownloadsNetworkPolicyBanners(uiState, horizontalPadding = 0.dp)
                }
            }

            if (uiState.batchProgress.isRunning || uiState.batchProgress.errors.isNotEmpty()) {
                item(key = "dl_batch_banner", span = StaggeredGridItemSpan.FullLine) {
                    DownloadsBatchProgressBanner(
                        progress = uiState.batchProgress,
                        onCancel = viewModel::cancelBatchEnqueue,
                        horizontalPadding = 0.dp,
                    )
                }
            }

            if (!uiState.message.isNullOrBlank()) {
                item(key = "dl_msg", span = StaggeredGridItemSpan.FullLine) {
                    DownloadsMessageBanner(
                        message = uiState.message,
                        onDismiss = viewModel::clearMessage,
                        horizontalPadding = 0.dp,
                    )
                }
            }

            item(key = "dl_search_and_view", span = StaggeredGridItemSpan.FullLine) {
                DownloadsSearchAndPresentationRow(
                    uiState = uiState,
                    onQueryChange = viewModel::updateSearchQuery,
                    onToggleFilters = viewModel::toggleFiltersExpanded,
                    onOpenLayoutSheet = onOpenLayoutSheet,
                    horizontalPadding = 0.dp,
                )
            }

            item(key = "dl_status_tabs", span = StaggeredGridItemSpan.FullLine) {
                DownloadsStatusFilterTabs(
                    uiState = uiState,
                    onSelectTab = viewModel::selectTab,
                    modifier = Modifier.testTag("downloads_status_tabs"),
                    horizontalPadding = 0.dp,
                )
            }

            if (uiState.hasBatchControls()) {
                item(key = "dl_batch_controls", span = StaggeredGridItemSpan.FullLine) {
                    DownloadsBatchControls(
                        uiState = uiState,
                        onPauseAll = viewModel::pauseAll,
                        onResumeAll = viewModel::resumeAll,
                        onRetryAll = viewModel::retryAllFailed,
                        onCleanCompleted = onCleanCompleted,
                        horizontalPadding = 0.dp,
                    )
                }
            }

            item(key = "dl_collapsible_filters", span = StaggeredGridItemSpan.FullLine) {
                DownloadsExtraFiltersPanel(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenGroupManager = onOpenGroupManager,
                    horizontalPadding = 0.dp,
                )
            }

            if (uiState.items.isEmpty()) {
                item(key = "dl_empty", span = StaggeredGridItemSpan.FullLine) {
                    DownloadsEmptyState(
                        uiState = uiState,
                        onSearch = navigator::openSearch,
                        onResetFilters = viewModel::resetExtraFilters,
                    )
                }
            } else {
                uiState.sections.forEach { section ->
                    if (uiState.groupMode != DownloadGroupMode.NONE) {
                        item(key = "dl_section_header_${section.key}", span = StaggeredGridItemSpan.FullLine) {
                            DownloadSectionHeader(
                                section = section,
                                onToggle = { viewModel.toggleSection(section.key) },
                            )
                        }
                    }
                    if (!section.isCollapsed) {
                        staggeredItems(
                            items = section.items,
                            key = { "${section.key}:${it.id}" },
                        ) { item ->
                            DownloadPosterCard(
                                item = item,
                                siteLabel = uiState.siteLabels[item.siteId] ?: item.siteId.value,
                                onPrimaryClick = { handleDownloadPrimaryAction(navigator, viewModel, item) },
                                onMenuClick = { onMenuTarget(item) },
                            )
                        }
                    }
                }
            }
        }
        RavenScrollToTopButton(
            staggeredGridState = gridState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 22.dp),
        )
    }
}


internal fun computeCollectionWaterfallColumns(widthDp: Int): Int = when {
    widthDp >= 840 -> 4
    widthDp >= 600 -> 3
    else -> 2
}

internal fun defaultCollectionLayout(): LibraryCollectionLayout = LibraryCollectionLayout.WATERFALL

