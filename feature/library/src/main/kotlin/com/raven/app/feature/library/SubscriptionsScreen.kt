package com.raven.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenCompactMediaRow
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenPrimaryButton
import com.raven.app.core.designsystem.component.RavenSecondaryButton
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteId
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Subscription Settings + Subscription Activity (`SubscriptionsScreen`).
 */
@Composable
fun SubscriptionsScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    initialSiteId: SiteId? = null,
    viewModel: SubscriptionsViewModel = koinViewModel(
        key = "subscriptions_${initialSiteId?.value ?: "all"}",
        parameters = { parametersOf(SubscriptionsParams(initialSiteId)) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current

    var editorOpen by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<SubscriptionGroup?>(null) }
    var deleteTarget by remember { mutableStateOf<SubscriptionGroup?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("subscriptions_screen"),
    ) {
        RavenTopBar(
            title = "订阅",
            subtitle = if (uiState.selectedTab == SubscriptionsTab.SETTINGS) {
                "${uiState.visibleSubscriptionGroups.size} 个订阅 · ${uiState.unreadTagCount} 条未读"
            } else {
                "${uiState.aggregatedTagFeed.size} 条动态 · ${uiState.unreadTagCount} 条未读"
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
                IconButton(
                    onClick = {
                        editingGroup = null
                        editorOpen = true
                    },
                    modifier = Modifier.testTag("subscriptions_add_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "添加订阅",
                        tint = colors.inkPrimary,
                    )
                }
                IconButton(
                    onClick = viewModel::refreshTagUpdates,
                    enabled = !uiState.refreshProgress.isRefreshing,
                    modifier = Modifier.testTag("subscriptions_refresh_button"),
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
                            contentDescription = "检查更新",
                            tint = colors.inkPrimary,
                        )
                    }
                }
                if (uiState.selectedTab == SubscriptionsTab.FEED && uiState.unreadTagCount > 0) {
                    IconButton(
                        onClick = viewModel::markAllTagUpdatesRead,
                        modifier = Modifier.testTag("subscriptions_mark_all_read_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DoneAll,
                            contentDescription = "全部标为已读",
                            tint = colors.inkPrimary,
                        )
                    }
                }
                IconButton(
                    onClick = navigator::openGlobalInbox,
                    modifier = Modifier.testTag("subscriptions_open_inbox_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsActive,
                        contentDescription = "打开订阅更新",
                        tint = if (uiState.unreadCount > 0) colors.crimson else colors.inkPrimary,
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (uiState.refreshProgress.isRefreshing) {
                item(key = "sub_refresh_progress") {
                    val p = uiState.refreshProgress
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RavenShapes.ControlShape)
                            .background(colors.surface1)
                            .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "正在并发检查更新（${p.completedSources} / ${p.totalSources}）…",
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
            }

            if (!uiState.message.isNullOrBlank()) {
                item(key = "sub_msg") {
                    LibraryFeedbackBanner(
                        message = uiState.message.orEmpty(),
                        tone = RavenStatusTone.ACCENT,
                        onDismiss = viewModel::clearMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "sub_tabs") {
                RavenFilterChipRow(
                    items = SubscriptionsTab.entries,
                    selectedItem = uiState.selectedTab,
                    labelProvider = { tab ->
                        if (tab == SubscriptionsTab.FEED && uiState.unreadTagCount > 0) {
                            "${tab.label} (${uiState.unreadTagCount})"
                        } else {
                            tab.label
                        }
                    },
                    onItemSelected = viewModel::selectTab,
                    keyProvider = { it.name },
                )
            }

            if (uiState.subscriptionSiteOptions.isNotEmpty()) {
                item(key = "sub_site_chips") {
                    LibrarySiteFilterChips(
                        options = uiState.subscriptionSiteOptions,
                        selectedSiteId = uiState.selectedSiteId,
                        onSelectSite = viewModel::selectSubscriptionSite,
                    )
                }
            }

            when (uiState.selectedTab) {
                SubscriptionsTab.SETTINGS -> {
                    if (uiState.visibleSubscriptionGroups.isEmpty()) {
                        item(key = "sub_empty") {
                            RavenEmptyState(
                                title = if (uiState.selectedSiteId == null) "暂无订阅设置" else "当前站点暂无订阅",
                                message = "添加标签 Slug 并选择单站点、多站点或全站点范围，即可在多个站点聚合成一条逻辑订阅。",
                                icon = Icons.Outlined.Subscriptions,
                                actionLabel = "添加订阅",
                                onActionClick = {
                                    editingGroup = null
                                    editorOpen = true
                                },
                            )
                        }
                    } else {
                        item(key = "sub_add_entry") {
                            RavenPrimaryButton(
                                text = "添加标签订阅",
                                onClick = {
                                    editingGroup = null
                                    editorOpen = true
                                },
                                leadingIcon = Icons.Outlined.Add,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .testTag("subscriptions_add_entry_button"),
                            )
                        }
                        items(
                            items = uiState.visibleSubscriptionGroups,
                            key = { it.key },
                        ) { group ->
                            val isChecking = group.subscriptions.any { sub ->
                                SubscriptionsViewModel.subscriptionSourceId(sub) in uiState.checkingSourceIds
                            }
                            SubscriptionGroupRow(
                                group = group,
                                siteLabels = uiState.siteLabels,
                                isChecking = isChecking,
                                onCheck = { viewModel.checkSubscriptionGroup(group) },
                                onEdit = {
                                    editingGroup = group
                                    editorOpen = true
                                },
                                onDelete = { deleteTarget = group },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 16.dp),
                            )
                        }
                    }
                }

                SubscriptionsTab.FEED -> {
                    if (uiState.aggregatedTagFeed.isEmpty()) {
                        item(key = "sub_feed_empty") {
                            RavenEmptyState(
                                title = "暂无订阅动态",
                                message = "点击右上角刷新按钮检查订阅标签的新发布内容。首次检查会自动建立基线，避免历史内容刷屏。",
                                icon = Icons.Outlined.NotificationsActive,
                                actionLabel = "立即检查更新",
                                onActionClick = viewModel::refreshTagUpdates,
                            )
                        }
                    } else {
                        items(
                            items = uiState.aggregatedTagFeed,
                            key = { it.id },
                        ) { entry ->
                            val siteLabel = uiState.siteLabels[entry.item.siteId] ?: entry.item.siteId.value
                            RavenCompactMediaRow(
                                item = entry.item,
                                onClick = {
                                    if (!entry.isRead) viewModel.markRead(entry.id)
                                    navigator.openMedia(entry.item)
                                },
                                subtitle = "$siteLabel · #${entry.sourceLabel} · ${formatRelativeTime(entry.discoveredAt)}",
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
                                            RavenStatusPill(text = "新发布", tone = RavenStatusTone.ACCENT)
                                        }
                                        RavenStatusPill(text = entry.sourceLabel)
                                    }
                                },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        SubscriptionEditorSheet(
            initialGroup = editingGroup,
            eligibleSites = uiState.onlineTagSearchSites,
            siteLabels = uiState.siteLabels,
            onDismiss = {
                editorOpen = false
                editingGroup = null
            },
            onSave = { slug, label, coverage, siteIds ->
                val group = editingGroup
                if (group == null) {
                    viewModel.createSubscriptionGroup(
                        slug = slug,
                        label = label,
                        coverage = coverage,
                        selectedSiteIds = siteIds,
                    )
                } else {
                    viewModel.updateSubscriptionGroup(
                        group = group,
                        label = label,
                        coverage = coverage,
                        selectedSiteIds = siteIds,
                    )
                }
                editorOpen = false
                editingGroup = null
            },
        )
    }

    deleteTarget?.let { group ->
        LibraryConfirmBottomSheet(
            title = "删除订阅「${group.label}」？",
            message = "将从 ${group.siteCount} 个站点取消订阅，并清除对应的标签更新记录。",
            confirmLabel = "删除订阅",
            onConfirm = { viewModel.deleteSubscriptionGroup(group) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun SubscriptionGroupRow(
    group: SubscriptionGroup,
    siteLabels: Map<SiteId, String>,
    isChecking: Boolean,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val siteNames = group.subscriptions
        .map { siteLabels[it.siteId] ?: it.siteId.value }
        .joinToString(" · ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onEdit)
            .padding(12.dp)
            .testTag("subscription_group_${group.key}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RavenShapes.ControlShape)
                .background(colors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Tag,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = group.label,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RavenStatusPill(text = group.coverage.label, tone = RavenStatusTone.ACCENT)
                RavenStatusPill(text = "${group.siteCount} 站点")
                RavenStatusPill(
                    text = "${group.unreadCount} 未读",
                    tone = if (group.unreadCount > 0) RavenStatusTone.ACCENT else RavenStatusTone.NEUTRAL,
                )
                if (group.scope != SearchScope.TAG) {
                    RavenStatusPill(text = group.scope.displayLabel())
                }
            }
            if (siteNames.isNotBlank()) {
                Text(
                    text = siteNames,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onCheck,
                enabled = !isChecking,
                modifier = Modifier.size(34.dp),
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.crimson,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "检查整组更新",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "修改订阅",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "删除订阅",
                    tint = colors.inkTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionEditorSheet(
    initialGroup: SubscriptionGroup?,
    eligibleSites: List<SiteFilterOption>,
    siteLabels: Map<SiteId, String>,
    onDismiss: () -> Unit,
    onSave: (
        slug: String,
        label: String,
        coverage: SubscriptionCoverage,
        siteIds: Set<SiteId>,
    ) -> Unit,
) {
    val colors = LocalRavenColors.current
    val selectableSites = remember(eligibleSites, initialGroup, siteLabels) {
        val extraSiteIds = initialGroup?.subscriptions.orEmpty()
            .map { it.siteId }
            .filter { siteId -> eligibleSites.none { it.siteId == siteId } }
        eligibleSites + extraSiteIds.distinct().map { siteId ->
            SiteFilterOption(
                siteId = siteId,
                label = siteLabels[siteId] ?: siteId.value,
            )
        }
    }

    var slug by rememberSaveable(initialGroup?.key) { mutableStateOf(initialGroup?.slug.orEmpty()) }
    var label by rememberSaveable(initialGroup?.key) { mutableStateOf(initialGroup?.label.orEmpty()) }
    var coverage by remember(initialGroup?.key) {
        mutableStateOf(initialGroup?.coverage ?: SubscriptionCoverage.SINGLE)
    }
    var selectedSiteIds by remember(initialGroup?.key) {
        mutableStateOf(initialGroup?.siteIds.orEmpty())
    }

    val canSave = slug.trim().isNotEmpty() && when (coverage) {
        SubscriptionCoverage.ALL -> eligibleSites.isNotEmpty()
        SubscriptionCoverage.SINGLE -> selectedSiteIds.size == 1
        SubscriptionCoverage.MULTI -> selectedSiteIds.size >= 2
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = if (initialGroup == null) "添加标签订阅" else "编辑标签订阅",
        subtitle = "同一 Slug 与标签范围在多个站点聚合为一条逻辑订阅",
        modifier = Modifier.testTag("subscription_editor_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = slug,
                onValueChange = { if (initialGroup == null) slug = it },
                label = { Text("标签 Slug") },
                placeholder = { Text("例如 cyberpunk") },
                singleLine = true,
                enabled = initialGroup == null,
                readOnly = initialGroup != null,
                supportingText = if (initialGroup != null) {
                    { Text("Slug 创建后不可修改") }
                } else {
                    null
                },
                shape = RavenShapes.ControlShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                    disabledTextColor = colors.inkTertiary,
                    focusedContainerColor = colors.surface1,
                    unfocusedContainerColor = colors.surface1,
                    cursorColor = colors.crimson,
                    focusedBorderColor = colors.crimson,
                    unfocusedBorderColor = colors.outlineSubtle,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subscription_slug_field"),
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(80) },
                label = { Text("显示名称") },
                placeholder = { Text("留空则使用 Slug") },
                singleLine = true,
                shape = RavenShapes.ControlShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                    focusedContainerColor = colors.surface1,
                    unfocusedContainerColor = colors.surface1,
                    cursorColor = colors.crimson,
                    focusedBorderColor = colors.crimson,
                    unfocusedBorderColor = colors.outlineSubtle,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subscription_label_field"),
            )

            Text(
                text = "订阅范围",
                style = MaterialTheme.typography.labelLarge,
                color = colors.inkPrimary,
            )
            RavenFilterChipRow(
                items = SubscriptionCoverage.entries,
                selectedItem = coverage,
                labelProvider = { it.label },
                onItemSelected = { next ->
                    coverage = next
                    if (next == SubscriptionCoverage.SINGLE && selectedSiteIds.size > 1) {
                        selectedSiteIds = selectedSiteIds.take(1).toSet()
                    }
                },
                keyProvider = { it.name },
                contentPadding = PaddingValues(0.dp),
            )

            when (coverage) {
                SubscriptionCoverage.ALL -> {
                    Text(
                        text = "将订阅所有支持在线标签搜索的站点（共 ${eligibleSites.size} 个）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }

                SubscriptionCoverage.SINGLE,
                SubscriptionCoverage.MULTI,
                -> {
                    Text(
                        text = if (coverage == SubscriptionCoverage.SINGLE) {
                            "选择一个站点"
                        } else {
                            "选择至少两个站点"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.inkPrimary,
                    )
                    if (selectableSites.isEmpty()) {
                        Text(
                            text = "暂无可选站点",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = selectableSites,
                                key = { it.siteId.value },
                            ) { site ->
                                val selected = site.siteId in selectedSiteIds
                                val role = if (coverage == SubscriptionCoverage.SINGLE) {
                                    Role.RadioButton
                                } else {
                                    Role.Checkbox
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(RavenShapes.ControlShape)
                                        .background(
                                            if (selected) colors.crimson.copy(alpha = 0.12f) else colors.surface1,
                                        )
                                        .border(
                                            width = 0.5.dp,
                                            color = if (selected) colors.crimson.copy(alpha = 0.45f) else colors.hairline,
                                            shape = RavenShapes.ControlShape,
                                        )
                                        .clickable(role = role) {
                                            selectedSiteIds = when (coverage) {
                                                SubscriptionCoverage.SINGLE -> setOf(site.siteId)
                                                SubscriptionCoverage.MULTI -> if (selected) {
                                                    selectedSiteIds - site.siteId
                                                } else {
                                                    selectedSiteIds + site.siteId
                                                }
                                                SubscriptionCoverage.ALL -> selectedSiteIds
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("subscription_site_${site.siteId.value}"),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = site.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = colors.inkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = site.siteId.value,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.inkSecondary,
                                            maxLines = 1,
                                        )
                                    }
                                    Icon(
                                        imageVector = if (selected) {
                                            Icons.Outlined.CheckCircle
                                        } else {
                                            Icons.Outlined.RadioButtonUnchecked
                                        },
                                        contentDescription = null,
                                        tint = if (selected) colors.crimson else colors.inkTertiary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RavenSecondaryButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                RavenPrimaryButton(
                    text = if (initialGroup == null) "添加订阅" else "保存修改",
                    onClick = {
                        onSave(
                            slug.trim(),
                            label.trim(),
                            coverage,
                            if (coverage == SubscriptionCoverage.ALL) emptySet() else selectedSiteIds,
                        )
                    },
                    enabled = canSave,
                    leadingIcon = if (initialGroup == null) Icons.Outlined.Add else Icons.Outlined.Edit,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("subscription_save_button"),
                )
            }
        }
    }
}
