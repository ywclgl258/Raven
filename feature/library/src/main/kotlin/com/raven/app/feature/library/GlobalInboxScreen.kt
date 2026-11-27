package com.raven.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenCompactMediaRow
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.UpdateInboxItem
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Subscription Updates (`GlobalInboxScreen`) unifying tag subscription updates and saved-search
 * updates. Supports a persistent waterfall/list presentation switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalInboxScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionsViewModel = koinViewModel(
        key = "global_inbox",
        parameters = { parametersOf(SubscriptionsParams(null)) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current
    var layout by rememberSaveable { mutableStateOf(LibraryCollectionLayout.WATERFALL) }
    var actionTarget by remember { mutableStateOf<UpdateInboxItem?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("global_inbox_screen"),
    ) {
        RavenTopBar(
            title = "订阅更新",
            subtitle = if (uiState.unreadCount > 0) {
                "${uiState.unreadCount} 条未读 · 共 ${uiState.totalInboxCount} 条"
            } else {
                "全部已读 · 共 ${uiState.totalInboxCount} 条"
            },
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
                LibraryCollectionLayoutButton(
                    layout = layout,
                    onToggle = {
                        layout = if (layout == LibraryCollectionLayout.WATERFALL) {
                            LibraryCollectionLayout.LIST
                        } else {
                            LibraryCollectionLayout.WATERFALL
                        }
                    },
                )
                IconButton(
                    onClick = { viewModel.refreshAllUpdates() },
                    enabled = !uiState.refreshProgress.isRefreshing,
                    modifier = Modifier.testTag("inbox_refresh_all_button"),
                ) {
                    if (uiState.refreshProgress.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.crimson,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "检查订阅更新",
                            tint = colors.inkPrimary,
                        )
                    }
                }
                if (uiState.inboxItems.any { !it.isRead }) {
                    IconButton(
                        onClick = viewModel::markAllRead,
                        modifier = Modifier.testTag("inbox_mark_all_read_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DoneAll,
                            contentDescription = "全部标为已读",
                            tint = colors.inkPrimary,
                        )
                    }
                }
                IconButton(
                    onClick = { navigator.openSubscriptions() },
                    modifier = Modifier.testTag("inbox_manage_subscriptions_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Subscriptions,
                        contentDescription = "管理订阅",
                        tint = colors.inkPrimary,
                    )
                }
            },
        )

        if (layout == LibraryCollectionLayout.WATERFALL) {
            SubscriptionUpdatesWaterfall(
                uiState = uiState,
                onClearMessage = viewModel::clearMessage,
                onRefresh = { viewModel.refreshAllUpdates() },
                onSearchQueryChange = viewModel::updateInboxSearchQuery,
                onToggleUnreadOnly = { viewModel.setInboxUnreadOnly(!uiState.inboxUnreadOnly) },
                onSelectSourceFilter = viewModel::selectInboxSourceFilter,
                onSelectSiteFilter = viewModel::selectInboxSiteFilter,
                onItemClick = { entry ->
                    if (!entry.isRead) viewModel.markRead(entry.id)
                    navigator.openMedia(entry.item)
                },
                onItemMenuClick = { actionTarget = it },
            )
        } else {
            SubscriptionUpdatesList(
                uiState = uiState,
                onClearMessage = viewModel::clearMessage,
                onRefresh = { viewModel.refreshAllUpdates() },
                onSearchQueryChange = viewModel::updateInboxSearchQuery,
                onToggleUnreadOnly = { viewModel.setInboxUnreadOnly(!uiState.inboxUnreadOnly) },
                onSelectSourceFilter = viewModel::selectInboxSourceFilter,
                onSelectSiteFilter = viewModel::selectInboxSiteFilter,
                onItemClick = { entry ->
                    if (!entry.isRead) viewModel.markRead(entry.id)
                    navigator.openMedia(entry.item)
                },
                onItemMenuClick = { actionTarget = it },
            )
        }
    }

    actionTarget?.let { target ->
        val siteLabel = uiState.siteLabels[target.item.siteId] ?: target.item.siteId.value
        val sourcePrefix = if (target.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION) "标签" else "搜索"
        RavenBottomSheet(
            onDismissRequest = { actionTarget = null },
            title = "更新操作",
            subtitle = "$siteLabel · $sourcePrefix：${target.sourceLabel}",
        ) {
            RavenGroupedRow(
                title = "打开作品",
                subtitle = target.item.title,
                icon = Icons.Outlined.PlayArrow,
                showChevron = true,
                onClick = {
                    if (!target.isRead) viewModel.markRead(target.id)
                    actionTarget = null
                    navigator.openMedia(target.item)
                },
            )
            RavenGroupedDivider(insetWithIcon = true)
            LibraryMorphGroupedRow(
                title = if (target.isRead) "标为未读" else "标为已读",
                glyph = if (target.isRead) RavenMorphIcons.MailUnread else RavenMorphIcons.MailRead,
                onClick = {
                    if (target.isRead) viewModel.markUnread(target.id) else viewModel.markRead(target.id)
                    actionTarget = null
                },
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "删除这条更新",
                icon = Icons.Outlined.DeleteOutline,
                destructive = true,
                onClick = {
                    viewModel.deleteInboxItem(target.id)
                    actionTarget = null
                },
            )
        }
    }
}

@Composable
private fun SubscriptionUpdatesWaterfall(
    uiState: SubscriptionsUiState,
    onClearMessage: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleUnreadOnly: () -> Unit,
    onSelectSourceFilter: (InboxSourceFilter) -> Unit,
    onSelectSiteFilter: (SiteId?) -> Unit,
    onItemClick: (UpdateInboxItem) -> Unit,
    onItemMenuClick: (UpdateInboxItem) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = when {
            maxWidth >= 840.dp -> 4
            maxWidth >= 600.dp -> 3
            else -> 2
        }

        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .testTag("subscription_update_grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 12.dp,
        ) {
            item(key = "inbox_header", span = StaggeredGridItemSpan.FullLine) {
                SubscriptionUpdatesHeader(
                    uiState = uiState,
                    onClearMessage = onClearMessage,
                    onSearchQueryChange = onSearchQueryChange,
                    onToggleUnreadOnly = onToggleUnreadOnly,
                    onSelectSourceFilter = onSelectSourceFilter,
                    onSelectSiteFilter = onSelectSiteFilter,
                )
            }

            if (uiState.inboxItems.isEmpty()) {
                item(key = "inbox_empty", span = StaggeredGridItemSpan.FullLine) {
                    SubscriptionUpdatesEmptyState(
                        uiState = uiState,
                        onRefresh = onRefresh,
                        onClearSearch = { onSearchQueryChange("") },
                        onViewAll = { onToggleUnreadOnly() },
                    )
                }
            } else {
                items(
                    items = uiState.inboxItems,
                    key = { it.id },
                ) { entry ->
                    val siteLabel = uiState.siteLabels[entry.item.siteId] ?: entry.item.siteId.value
                    val sourcePrefix = if (entry.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION) "标签" else "搜索"
                    SubscriptionUpdateCard(
                        entry = entry,
                        siteLabel = siteLabel,
                        sourcePrefix = sourcePrefix,
                        onClick = { onItemClick(entry) },
                        onLongClick = { onItemMenuClick(entry) },
                        onMenuClick = { onItemMenuClick(entry) },
                        modifier = Modifier.animateItem(),
                    )
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
private fun SubscriptionUpdatesList(
    uiState: SubscriptionsUiState,
    onClearMessage: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleUnreadOnly: () -> Unit,
    onSelectSourceFilter: (InboxSourceFilter) -> Unit,
    onSelectSiteFilter: (SiteId?) -> Unit,
    onItemClick: (UpdateInboxItem) -> Unit,
    onItemMenuClick: (UpdateInboxItem) -> Unit,
) {
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("subscription_update_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "inbox_header") {
            SubscriptionUpdatesHeader(
                uiState = uiState,
                onClearMessage = onClearMessage,
                onSearchQueryChange = onSearchQueryChange,
                onToggleUnreadOnly = onToggleUnreadOnly,
                onSelectSourceFilter = onSelectSourceFilter,
                onSelectSiteFilter = onSelectSiteFilter,
            )
        }

        if (uiState.inboxItems.isEmpty()) {
            item(key = "inbox_empty") {
                SubscriptionUpdatesEmptyState(
                    uiState = uiState,
                    onRefresh = onRefresh,
                    onClearSearch = { onSearchQueryChange("") },
                    onViewAll = { onToggleUnreadOnly() },
                )
            }
        } else {
            items(
                count = uiState.inboxItems.size,
                key = { index -> uiState.inboxItems[index].id },
            ) { index ->
                val entry = uiState.inboxItems[index]
                val siteLabel = uiState.siteLabels[entry.item.siteId] ?: entry.item.siteId.value
                val sourcePrefix = if (entry.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION) "标签" else "搜索"
                SubscriptionUpdateListRow(
                    entry = entry,
                    siteLabel = siteLabel,
                    sourcePrefix = sourcePrefix,
                    onClick = { onItemClick(entry) },
                    onLongClick = { onItemMenuClick(entry) },
                    onMenuClick = { onItemMenuClick(entry) },
                    modifier = Modifier
                        .animateItem()
                        .testTag("inbox_list_item_${entry.id}"),
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

@Composable
private fun SubscriptionUpdatesHeader(
    uiState: SubscriptionsUiState,
    onClearMessage: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleUnreadOnly: () -> Unit,
    onSelectSourceFilter: (InboxSourceFilter) -> Unit,
    onSelectSiteFilter: (SiteId?) -> Unit,
) {
    val colors = LocalRavenColors.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (uiState.refreshProgress.isRefreshing) {
            val p = uiState.refreshProgress
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface1)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "正在检查订阅与保存搜索（${p.completedSources} / ${p.totalSources}）…",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.inkPrimary,
                )
                if (p.totalSources > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (p.completedSources.toFloat() / p.totalSources.toFloat()).coerceIn(0f, 1f)
                        },
                        color = colors.crimson,
                        trackColor = colors.surface3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (!uiState.message.isNullOrBlank()) {
            LibraryFeedbackBanner(
                message = uiState.message.orEmpty(),
                tone = RavenStatusTone.ACCENT,
                onDismiss = onClearMessage,
            )
        }

        RavenSearchField(
            query = uiState.inboxSearchQuery,
            onQueryChange = onSearchQueryChange,
            placeholder = "搜索标题、站点或订阅来源…",
            modifier = Modifier.testTag("inbox_search_field"),
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RavenFilterChip(
                    label = "仅看未读 (${uiState.unreadCount})",
                    selected = uiState.inboxUnreadOnly,
                    onClick = onToggleUnreadOnly,
                    modifier = Modifier.testTag("inbox_unread_filter_chip"),
                )
            }
            RavenFilterChipRow(
                items = InboxSourceFilter.entries,
                selectedItem = uiState.inboxSourceFilter,
                labelProvider = { filter ->
                    when (filter) {
                        InboxSourceFilter.ALL -> filter.label
                        InboxSourceFilter.TAG_SUBSCRIPTION ->
                            if (uiState.unreadTagCount > 0) "${filter.label} (${uiState.unreadTagCount})" else filter.label
                        InboxSourceFilter.SAVED_SEARCH ->
                            if (uiState.unreadSavedSearchCount > 0) "${filter.label} (${uiState.unreadSavedSearchCount})" else filter.label
                    }
                },
                onItemSelected = onSelectSourceFilter,
                keyProvider = { it.name },
                contentPadding = PaddingValues(0.dp),
            )
        }

        if (uiState.inboxSiteOptions.isNotEmpty()) {
            LibrarySiteFilterChips(
                options = uiState.inboxSiteOptions,
                selectedSiteId = uiState.inboxSiteFilter,
                onSelectSite = onSelectSiteFilter,
                contentPadding = PaddingValues(0.dp),
            )
        }
    }
}

@Composable
private fun SubscriptionUpdatesEmptyState(
    uiState: SubscriptionsUiState,
    onRefresh: () -> Unit,
    onClearSearch: () -> Unit,
    onViewAll: () -> Unit,
) {
    val isSearching = uiState.inboxSearchQuery.isNotBlank()
    RavenEmptyState(
        title = when {
            isSearching -> "没有匹配的订阅更新"
            uiState.inboxUnreadOnly -> "没有未读订阅更新"
            else -> "暂无订阅更新"
        },
        message = when {
            isSearching -> "尝试更换关键词，或清除搜索与筛选条件后重试。"
            uiState.inboxUnreadOnly -> "所有标签订阅与保存搜索的新内容均已阅读。"
            else -> "点击刷新按钮可同时检查所有标签订阅与保存搜索的最新作品（首次检查自动建立基线）。"
        },
        icon = Icons.Outlined.NotificationsActive,
        actionLabel = when {
            isSearching -> "清除搜索"
            uiState.inboxUnreadOnly -> "查看全部记录"
            else -> "立即检查更新"
        },
        onActionClick = {
            when {
                isSearching -> onClearSearch()
                uiState.inboxUnreadOnly -> onViewAll()
                else -> onRefresh()
            }
        },
    )
}

@Composable
private fun SubscriptionUpdateListRow(
    entry: UpdateInboxItem,
    siteLabel: String,
    sourcePrefix: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    RavenCompactMediaRow(
        item = entry.item,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        subtitle = "$siteLabel · $sourcePrefix：${entry.sourceLabel} · ${formatRelativeTime(entry.discoveredAt)}",
        statusContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!entry.isRead) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(colors.crimson),
                    )
                    RavenStatusPill(text = "未读", tone = RavenStatusTone.ACCENT)
                }
                RavenStatusPill(text = "$sourcePrefix · ${entry.sourceLabel}")
            }
        },
        trailingContent = {
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("inbox_list_menu_${entry.id}"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更新操作",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
private fun SubscriptionUpdateCard(
    entry: UpdateInboxItem,
    siteLabel: String,
    sourcePrefix: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val unreadBadge = if (!entry.isRead) "未读" else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("inbox_item_${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (entry.item.kind.isVideo) {
                RavenVideoCard(
                    item = entry.item,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    badgeText = unreadBadge,
                    showMetadata = false,
                )
            } else {
                RavenGalleryCard(
                    item = entry.item,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    badgeText = unreadBadge,
                    showCaption = false,
                )
            }
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.canvas.copy(alpha = 0.58f))
                    .testTag("inbox_menu_${entry.id}"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更新操作",
                    tint = colors.inkPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (!entry.item.kind.isVideo) {
            Text(
                text = entry.item.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "$siteLabel · $sourcePrefix：${entry.sourceLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatRelativeTime(entry.discoveredAt),
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = colors.inkTertiary,
            maxLines = 1,
        )
    }
}
