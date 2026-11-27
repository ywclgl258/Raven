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
        LibraryConfirmBottomSheet(
            title = "清理 ${uiState.completedCount} 个已完成下载？",
            message = "将删除全部已完成下载记录及其本地缓存文件，预计释放 " +
                "${RavenFormatters.formatByteSize(uiState.totalCompletedBytes)}。" +
                "进行中、已暂停与需处理的任务不受影响。",
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

@Composable
private fun DownloadsNetworkPolicyBanners(
    uiState: DownloadsUiState,
    horizontalPadding: Dp = 16.dp,
) {
    if (!uiState.networkPolicy.isConnected) {
        LibraryFeedbackBanner(
            message = "当前无网络连接，下载任务将在恢复连接后自动继续",
            tone = RavenStatusTone.WARNING,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
    } else if (uiState.networkPolicy.unmeteredOnly && uiState.networkPolicy.isMetered) {
        LibraryFeedbackBanner(
            message = "当前为移动计费网络，已按「仅 Wi-Fi 下载」策略暂停传输",
            tone = RavenStatusTone.WARNING,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
    }
}

@Composable
private fun DownloadsBatchProgressBanner(
    progress: BatchEnqueueProgress,
    onCancel: () -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (progress.isRunning) {
                    "正在批量加入下载（${progress.processed} / ${progress.total}）"
                } else {
                    "批量加入完成（成功 ${progress.added} / ${progress.total}）"
                },
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = colors.inkPrimary,
            )
            if (progress.isRunning) {
                TextButton(onClick = onCancel) {
                    Text("取消", color = colors.error)
                }
            }
        }
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { (progress.processed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                color = colors.crimson,
                trackColor = colors.surface3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (progress.errors.isNotEmpty()) {
            Text(
                text = progress.errors.take(2).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }
    }
}

@Composable
private fun DownloadsMessageBanner(
    message: String?,
    onDismiss: () -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    LibraryFeedbackBanner(
        message = message.orEmpty(),
        tone = RavenStatusTone.ACCENT,
        onDismiss = onDismiss,
        modifier = Modifier.padding(horizontal = horizontalPadding),
    )
}

@Composable
private fun DownloadsSearchAndPresentationRow(
    uiState: DownloadsUiState,
    onQueryChange: (String) -> Unit,
    onToggleFilters: () -> Unit,
    onOpenLayoutSheet: () -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    val colors = LocalRavenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RavenSearchField(
            query = uiState.searchQuery,
            onQueryChange = onQueryChange,
            placeholder = "搜索标题、标签、站点或分组…",
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onToggleFilters,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (uiState.filtersExpanded || uiState.activeExtraFilterCount > 0) {
                        colors.crimson.copy(alpha = 0.16f)
                    } else {
                        colors.surface2
                    },
                )
                .border(0.5.dp, colors.hairline, CircleShape)
                .testTag("downloads_filter_toggle_button"),
        ) {
            Icon(
                imageVector = Icons.Outlined.FilterList,
                contentDescription = "筛选，已启用 ${uiState.activeExtraFilterCount} 项",
                tint = if (uiState.activeExtraFilterCount > 0) colors.crimson else colors.inkPrimary,
            )
        }
        IconButton(
            onClick = onOpenLayoutSheet,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.surface2)
                .border(0.5.dp, colors.hairline, CircleShape)
                .testTag("downloads_layout_button"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Sort,
                contentDescription = "排列与分组",
                tint = if (uiState.sort != DownloadSort.RECENT || uiState.groupMode != DownloadGroupMode.NONE) {
                    colors.crimson
                } else {
                    colors.inkPrimary
                },
            )
        }
    }
}

@Composable
private fun DownloadsStatusFilterTabs(
    uiState: DownloadsUiState,
    onSelectTab: (DownloadStatusTab) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    RavenSegmentedTabs(
        items = DownloadStatusTab.entries,
        selectedItem = uiState.selectedTab,
        labelProvider = { tab -> "${tab.label} ${uiState.countForTab(tab)}" },
        onItemSelected = onSelectTab,
        modifier = modifier.padding(horizontal = horizontalPadding),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadsBatchControls(
    uiState: DownloadsUiState,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onRetryAll: () -> Unit,
    onCleanCompleted: () -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    val colors = LocalRavenColors.current
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState.activeTransferCount > 0) {
            OutlinedButton(
                onClick = onPauseAll,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface1,
                    contentColor = colors.inkPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("downloads_pause_all_button"),
            ) {
                Icon(Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("全部暂停", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (uiState.pausedCount > 0) {
            OutlinedButton(
                onClick = onResumeAll,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface1,
                    contentColor = colors.inkPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("downloads_resume_all_button"),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("全部继续", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (uiState.attentionCount > 0) {
            OutlinedButton(
                onClick = onRetryAll,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.crimson.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.crimson.copy(alpha = 0.10f),
                    contentColor = colors.crimson,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("downloads_retry_all_button"),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("重试需处理 (${uiState.attentionCount})", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (uiState.completedCount > 0) {
            OutlinedButton(
                onClick = onCleanCompleted,
                enabled = !uiState.isCleaningCompleted,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.error.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.error.copy(alpha = 0.10f),
                    contentColor = colors.error,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("downloads_clean_completed_button"),
            ) {
                Icon(
                    Icons.Outlined.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (uiState.isCleaningCompleted) {
                        "清理中…"
                    } else {
                        "清理已完成 (${uiState.completedCount})"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun DownloadsExtraFiltersPanel(
    uiState: DownloadsUiState,
    viewModel: DownloadsViewModel,
    onOpenGroupManager: () -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    val colors = LocalRavenColors.current
    val motionReduced = com.raven.app.core.designsystem.theme.LocalMotionReduced.current
    AnimatedVisibility(
        visible = uiState.filtersExpanded,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("downloads_extra_filters"),
        ) {
            RavenFilterChipRow(
                items = DownloadKindFilter.entries,
                selectedItem = uiState.kindFilter,
                labelProvider = { it.label },
                onItemSelected = viewModel::selectKindFilter,
                keyProvider = { it.name },
                contentPadding = PaddingValues(horizontal = horizontalPadding),
            )
            LibrarySiteFilterChips(
                options = uiState.siteOptions,
                selectedSiteId = uiState.siteFilter,
                onSelectSite = viewModel::selectSiteFilter,
                contentPadding = PaddingValues(horizontal = horizontalPadding),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "自定义分组",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkPrimary,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onOpenGroupManager,
                    modifier = Modifier.testTag("downloads_open_group_manager"),
                ) {
                    Text("管理 ${uiState.availableGroups.size}", color = colors.crimson)
                }
            }
            if (uiState.availableGroups.isNotEmpty()) {
                val groupOptions: List<String?> = remember(uiState.availableGroups) {
                    listOf(null) + uiState.availableGroups
                }
                RavenFilterChipRow(
                    items = groupOptions,
                    selectedItem = uiState.groupFilter,
                    labelProvider = { it ?: "全部分组" },
                    onItemSelected = viewModel::selectGroupFilter,
                    keyProvider = { it ?: "_all_groups" },
                    contentPadding = PaddingValues(horizontal = horizontalPadding),
                )
            } else {
                Text(
                    text = "可从单项菜单设置分组，或在管理中批量创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkTertiary,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
            }
            DownloadsTagPanel(
                availableTagCount = uiState.availableTags.size,
                visibleTags = uiState.visibleTags,
                namespaces = uiState.tagNamespaces,
                selectedNamespace = uiState.selectedTagNamespace,
                selectedTagKeys = uiState.selectedTagKeys,
                selectedTags = uiState.availableTags.filter { it.key in uiState.selectedTagKeys },
                matchAll = uiState.tagMatchAll,
                onToggleTag = viewModel::toggleTag,
                onSelectNamespace = viewModel::selectTagNamespace,
                onToggleMatchAll = viewModel::toggleTagMatchAll,
                onClearTags = viewModel::clearTagFilters,
            )
            if (uiState.activeExtraFilterCount > 0 || uiState.searchQuery.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = viewModel::resetExtraFilters) {
                        Text("清除筛选", color = colors.crimson)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsEmptyState(
    uiState: DownloadsUiState,
    onSearch: () -> Unit,
    onResetFilters: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("downloads_empty_state"),
    ) {
        RavenEmptyState(
            title = if (uiState.allCount == 0) "还没有下载任务" else "当前分类下暂无下载项",
            message = if (uiState.allCount == 0) {
                "在视频详情页、漫画详情页或片单「离线准备」中可以将作品缓存到本地离线观看。"
            } else {
                "切换上方状态标签或清除筛选条件即可查看其他下载任务。"
            },
            icon = Icons.Outlined.Download,
            actionLabel = if (uiState.allCount == 0) "去搜索作品" else "清除筛选",
            onActionClick = if (uiState.allCount == 0) onSearch else onResetFilters,
        )
    }
}

private fun DownloadsUiState.hasBatchControls(): Boolean =
    activeTransferCount > 0 || pausedCount > 0 || attentionCount > 0 || completedCount > 0

private fun handleDownloadPrimaryAction(
    navigator: LibraryNavigator,
    viewModel: DownloadsViewModel,
    item: DownloadItem,
) {
    when (item.status) {
        DownloadStatus.COMPLETED -> openDownloadedItem(navigator, item)
        DownloadStatus.DOWNLOADING,
        DownloadStatus.QUEUED,
        DownloadStatus.RETRY_WAIT -> viewModel.pause(item.id)
        DownloadStatus.PAUSED -> viewModel.resume(item.id)
        DownloadStatus.FAILED,
        DownloadStatus.CANCELLED -> viewModel.retry(item.id)
    }
}

@Composable
private fun DownloadPosterCard(
    item: DownloadItem,
    siteLabel: String,
    onPrimaryClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val tone = when (item.status) {
        DownloadStatus.COMPLETED -> RavenStatusTone.SUCCESS
        DownloadStatus.DOWNLOADING -> RavenStatusTone.ACCENT
        DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.RETRY_WAIT -> RavenStatusTone.WARNING
        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> RavenStatusTone.ERROR
    }
    val detailLine = remember(item) { downloadDetailLine(item) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onPrimaryClick)
            .testTag("download_card_${item.id}"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (item.kind == DownloadKind.VIDEO) 16f / 9f else 3f / 4f)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            ) {
                RavenImage(
                    url = item.coverUrl,
                    siteId = item.siteId,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                RavenStatusPill(
                    text = item.status.displayLabel(),
                    tone = tone,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp),
                )
                if (item.status != DownloadStatus.COMPLETED) {
                    if (item.progressFraction > 0f) {
                        RavenCrimsonProgressBar(
                            fraction = item.progressFraction,
                            trackHeight = 3.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                        )
                    } else if (item.status == DownloadStatus.DOWNLOADING) {
                        LinearProgressIndicator(
                            color = colors.crimson,
                            trackColor = colors.inkPrimary.copy(alpha = 0.22f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(3.dp),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$siteLabel · ${item.kind.displayLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detailLine.isNotEmpty()) {
                Text(
                    text = detailLine,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.status == DownloadStatus.RETRY_WAIT) {
                Text(
                    text = formatRetryCountdown(item.retryAtEpochMs),
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.warning,
                )
            } else if (item.status == DownloadStatus.FAILED || (item.status == DownloadStatus.COMPLETED && !item.errorMessage.isNullOrBlank())) {
                val errText = buildString {
                    item.errorCategory?.let { append("${it.displayLabel()}：") }
                    append(item.errorMessage?.takeIf { it.isNotBlank() } ?: "下载失败")
                }
                Text(
                    text = errText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.groupName.isNotBlank()) {
                    RavenStatusPill(text = item.groupName)
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onPrimaryClick,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.surface2),
                ) {
                    Icon(
                        imageVector = when (item.status) {
                            DownloadStatus.COMPLETED -> Icons.Outlined.OfflinePin
                            DownloadStatus.DOWNLOADING,
                            DownloadStatus.QUEUED,
                            DownloadStatus.RETRY_WAIT -> Icons.Outlined.Pause
                            DownloadStatus.PAUSED -> Icons.Outlined.PlayArrow
                            DownloadStatus.FAILED,
                            DownloadStatus.CANCELLED -> Icons.Outlined.Refresh
                        },
                        contentDescription = "执行快捷操作",
                        tint = if (item.status == DownloadStatus.COMPLETED) colors.success else colors.inkPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多选项",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private fun downloadDetailLine(item: DownloadItem): String = buildList {
    if (item.kind != DownloadKind.VIDEO && item.totalUnits > 0) {
        val pagePct = ((item.completedUnits.toFloat() / item.totalUnits.toFloat()) * 100f)
            .roundToInt()
            .coerceIn(0, 100)
        if (item.status != DownloadStatus.COMPLETED) {
            add("${item.completedUnits} / ${item.totalUnits} 页 ($pagePct%)")
        } else {
            add("${item.completedUnits} / ${item.totalUnits} 页")
        }
    } else if (item.status != DownloadStatus.COMPLETED) {
        if (item.progressFraction > 0f) {
            add("${(item.progressFraction * 100f).roundToInt().coerceIn(0, 100)}%")
        } else if (item.status == DownloadStatus.DOWNLOADING) {
            add("正在连接…")
        }
    }
    item.quality?.takeIf { it.isNotBlank() }?.let { add(it) }
    val totalBytes = item.totalBytes?.takeIf { it > 0L }
    if (item.status != DownloadStatus.COMPLETED && item.downloadedBytes > 0L && totalBytes != null) {
        add("${RavenFormatters.formatByteSize(item.downloadedBytes)} / ${RavenFormatters.formatByteSize(totalBytes)}")
    } else if (item.status != DownloadStatus.COMPLETED && item.downloadedBytes > 0L) {
        add("已下载 ${RavenFormatters.formatByteSize(item.downloadedBytes)}")
    } else {
        val sizeBytes = totalBytes ?: item.downloadedBytes
        if (sizeBytes > 0L) add(RavenFormatters.formatByteSize(sizeBytes))
    }
}.joinToString(" · ")

internal fun computeCollectionWaterfallColumns(widthDp: Int): Int = when {
    widthDp >= 840 -> 4
    widthDp >= 600 -> 3
    else -> 2
}

internal fun defaultCollectionLayout(): LibraryCollectionLayout = LibraryCollectionLayout.WATERFALL

@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    siteLabel: String,
    onPrimaryClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val tone = when (item.status) {
        DownloadStatus.COMPLETED -> RavenStatusTone.SUCCESS
        DownloadStatus.DOWNLOADING -> RavenStatusTone.ACCENT
        DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.RETRY_WAIT -> RavenStatusTone.WARNING
        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> RavenStatusTone.ERROR
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onPrimaryClick)
            .padding(12.dp)
            .testTag("download_card_${item.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(if (item.kind == DownloadKind.VIDEO) 96.dp else 62.dp)
                    .height(if (item.kind == DownloadKind.VIDEO) 58.dp else 82.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            ) {
                RavenImage(
                    url = item.coverUrl,
                    siteId = item.siteId,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RavenStatusPill(text = item.status.displayLabel(), tone = tone)
                    RavenStatusPill(text = "$siteLabel · ${item.kind.displayLabel()}")
                    if (item.groupName.isNotBlank()) {
                        RavenStatusPill(text = item.groupName)
                    }
                }

                val detailLine = remember(item) { downloadDetailLine(item) }

                if (detailLine.isNotEmpty()) {
                    Text(
                        text = detailLine,
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (item.status == DownloadStatus.RETRY_WAIT) {
                    Text(
                        text = formatRetryCountdown(item.retryAtEpochMs),
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.warning,
                    )
                } else if (item.status == DownloadStatus.FAILED || (item.status == DownloadStatus.COMPLETED && !item.errorMessage.isNullOrBlank())) {
                    val errText = buildString {
                        item.errorCategory?.let { append("${it.displayLabel()}：") }
                        append(item.errorMessage?.takeIf { it.isNotBlank() } ?: "下载失败")
                    }
                    Text(
                        text = errText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = onPrimaryClick,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.surface2),
                ) {
                    Icon(
                        imageVector = when (item.status) {
                            DownloadStatus.COMPLETED -> Icons.Outlined.OfflinePin
                            DownloadStatus.DOWNLOADING,
                            DownloadStatus.QUEUED,
                            DownloadStatus.RETRY_WAIT -> Icons.Outlined.Pause
                            DownloadStatus.PAUSED -> Icons.Outlined.PlayArrow
                            DownloadStatus.FAILED,
                            DownloadStatus.CANCELLED -> Icons.Outlined.Refresh
                        },
                        contentDescription = "执行快捷操作",
                        tint = if (item.status == DownloadStatus.COMPLETED) colors.success else colors.inkPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多选项",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (item.status != DownloadStatus.COMPLETED) {
            if (item.progressFraction > 0f) {
                RavenCrimsonProgressBar(
                    fraction = item.progressFraction,
                    trackHeight = 3.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RavenShapes.PillShape),
                )
            } else if (item.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    color = colors.crimson,
                    trackColor = colors.inkPrimary.copy(alpha = 0.22f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RavenShapes.PillShape),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadItemActionSheet(
    item: DownloadItem,
    siteLabel: String,
    onOpenOffline: (() -> Unit)?,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onPrioritize: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onEditGroup: () -> Unit,
    onInspectIntegrity: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = item.title,
        subtitle = "$siteLabel · ${item.status.displayLabel()}",
        modifier = Modifier.testTag("download_item_sheet"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RavenGroupedSection {
                var addedRow = false
                if (onOpenOffline != null) {
                    addedRow = true
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
                if (onPause != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = "暂停下载",
                        icon = Icons.Outlined.Pause,
                        onClick = {
                            onDismiss()
                            onPause()
                        },
                    )
                }
                if (onResume != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = "继续下载",
                        icon = Icons.Outlined.PlayArrow,
                        onClick = {
                            onDismiss()
                            onResume()
                        },
                    )
                }
                if (onRetry != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = "立即重试",
                        icon = Icons.Outlined.Refresh,
                        onClick = {
                            onDismiss()
                            onRetry()
                        },
                    )
                }
                if (onPrioritize != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = "置顶优先下载",
                        icon = Icons.Outlined.VerticalAlignTop,
                        onClick = {
                            onDismiss()
                            onPrioritize()
                        },
                    )
                }
                if (onMoveUp != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = if (item.kind == DownloadKind.VIDEO) "上移一位" else "提高队列优先级",
                        icon = Icons.Outlined.KeyboardArrowUp,
                        onClick = {
                            onDismiss()
                            onMoveUp()
                        },
                    )
                }
                if (onMoveDown != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = if (item.kind == DownloadKind.VIDEO) "下移一位" else "降低队列优先级",
                        icon = Icons.Outlined.KeyboardArrowDown,
                        onClick = {
                            onDismiss()
                            onMoveDown()
                        },
                    )
                }
                if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "设置分组",
                    value = item.groupName.ifBlank { "未分组" },
                    icon = Icons.Outlined.Folder,
                    showChevron = true,
                    onClick = {
                        onDismiss()
                        onEditGroup()
                    },
                )
                if (onInspectIntegrity != null) {
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "检查完整性与修复缺页",
                        subtitle = "逐页校验已下载图片是否缺失或损坏",
                        icon = Icons.Outlined.Verified,
                        showChevron = true,
                        onClick = {
                            onDismiss()
                            onInspectIntegrity()
                        },
                        modifier = Modifier.testTag("download_inspect_integrity_row"),
                    )
                }
            }

            RavenGroupedSection {
                RavenGroupedRow(
                    title = "删除下载与缓存文件",
                    icon = Icons.Outlined.DeleteOutline,
                    destructive = true,
                    onClick = {
                        onDismiss()
                        onDelete()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DownloadGroupEditorSheet(
    item: DownloadItem,
    existingGroups: List<String>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    var groupName by rememberSaveable(item.id) { mutableStateOf(item.groupName) }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "设置下载分组",
        subtitle = item.title,
        modifier = Modifier.testTag("download_group_sheet"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryTextInputField(
                value = groupName,
                onValueChange = { groupName = it.take(40) },
                placeholder = "留空则清除分组，例如：长途离线、合集",
            )
            if (existingGroups.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    existingGroups.forEach { existing ->
                        RavenFilterChip(
                            label = existing,
                            selected = groupName.trim() == existing,
                            onClick = { groupName = existing },
                        )
                    }
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
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("取消", color = colors.inkPrimary)
                }
                Button(
                    onClick = {
                        onSave(groupName.trim())
                        onDismiss()
                    },
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("保存分组", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryIntegrityAuditSheet(
    state: IntegrityAuditDialogState,
    onRepair: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "完整性检查与修复",
        subtitle = state.item.title,
        modifier = Modifier.testTag("download_integrity_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state.isChecking -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = colors.crimson,
                        )
                        Text(
                            text = "正在逐页校验已下载文件…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                        )
                    }
                }

                !state.errorMessage.isNullOrBlank() -> {
                    LibraryFeedbackBanner(
                        message = state.errorMessage,
                        tone = RavenStatusTone.ERROR,
                    )
                }

                state.audit != null -> {
                    val audit = state.audit
                    RavenGroupedSection(title = "校验报告") {
                        LibraryMorphGroupedRow(
                            title = "整体状态",
                            value = if (audit.isComplete) "完整无损" else "发现缺页或损坏",
                            glyph = if (audit.isComplete) RavenMorphIcons.Verified else RavenMorphIcons.Build,
                            iconTint = if (audit.isComplete) colors.success else colors.warning,
                        )
                        RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedRow(title = "总页数", value = "${audit.totalPages} 页")
                        RavenGroupedDivider()
                        RavenGroupedRow(title = "完好页", value = "${audit.validPages.size} 页")
                        RavenGroupedDivider()
                        RavenGroupedRow(title = "缺失页", value = "${audit.missingPages.size} 页")
                        RavenGroupedDivider()
                        RavenGroupedRow(title = "损坏页", value = "${audit.corruptPages.size} 页")
                        RavenGroupedDivider()
                        RavenGroupedRow(title = "已占空间", value = RavenFormatters.formatByteSize(audit.totalBytes))
                    }

                    if (!audit.isComplete) {
                        Button(
                            onClick = onRepair,
                            enabled = !state.isRepairing,
                            shape = RavenShapes.PillShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primaryCtaContainer,
                                contentColor = colors.primaryCtaInk,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .ravenSpringPress()
                                .testTag("download_repair_button"),
                        ) {
                            Text(
                                text = if (state.isRepairing) {
                                    "正在提交修复…"
                                } else {
                                    "修复缺失与损坏页 (${audit.missingPages.size + audit.corruptPages.size} 页)"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openDownloadedItem(
    navigator: LibraryNavigator,
    item: DownloadItem,
) {
    if (item.kind == DownloadKind.VIDEO) {
        navigator.openVideo(
            siteId = item.siteId,
            mediaId = item.mediaId,
            url = item.url,
            title = item.title,
            coverUrl = item.coverUrl,
            offlineOnly = true,
        )
    } else {
        navigator.openReader(
            siteId = item.siteId,
            mediaId = item.mediaId,
            url = item.url,
            startPage = 1,
            offlineOnly = true,
        )
    }
}
