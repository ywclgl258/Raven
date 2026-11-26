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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenCompactMediaRow
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagSubscription
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Per-Site Tag Subscription Management & Aggregated Tag Feed (`SubscriptionsScreen`).
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

    var unsubscribeTarget by remember { mutableStateOf<TagSubscription?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("subscriptions_screen"),
    ) {
        RavenTopBar(
            title = "标签订阅",
            subtitle = "${uiState.visibleSubscriptions.size} 个订阅 · ${uiState.unreadTagCount} 条未读",
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
                        contentDescription = "打开全局更新收件箱",
                        tint = if (uiState.unreadCount > 0) colors.crimson else colors.inkPrimary,
                    )
                }
            },
        )

        val subscriptionsBySite = remember(uiState.visibleSubscriptions) {
            uiState.visibleSubscriptions.groupBy { it.siteId }
        }

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
                SubscriptionsTab.MANAGE -> {
                    if (uiState.visibleSubscriptions.isEmpty()) {
                        item(key = "sub_empty") {
                            RavenEmptyState(
                                title = "暂无标签订阅",
                                message = "在任意站点的标签或作者列表中点击订阅，即可在这里统一管理排序并检查最新更新。",
                                icon = Icons.Outlined.Subscriptions,
                            )
                        }
                    } else {
                        val sameSiteList = uiState.visibleSubscriptions
                        itemsIndexed(
                            items = sameSiteList,
                            key = { _, sub -> SubscriptionsViewModel.subscriptionSourceId(sub) },
                        ) { index, sub ->
                            val sourceId = SubscriptionsViewModel.subscriptionSourceId(sub)
                            val siteGroup = subscriptionsBySite[sub.siteId].orEmpty()
                            val posInSite = siteGroup.indexOfFirst {
                                it.slug == sub.slug && it.scope == sub.scope
                            }
                            val canUp = posInSite > 0
                            val canDown = posInSite in 0 until siteGroup.lastIndex
                            val isChecking = sourceId in uiState.checkingSourceIds
                            val siteLabel = uiState.siteLabels[sub.siteId] ?: sub.siteId.value

                            Row(
                                modifier = Modifier
                                    .animateItem()
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .clip(RavenShapes.CardShape)
                                    .background(colors.surface1)
                                    .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                                    .ravenSpringPress()
                                    .clickable(role = Role.Button) {
                                        navigator.openSiteFeed(
                                            RavenRoute.SiteFeed(
                                                siteId = sub.siteId.value,
                                                query = sub.slug,
                                                scope = sub.scope.name,
                                                title = sub.label,
                                            ),
                                        )
                                    }
                                    .padding(12.dp)
                                    .testTag("subscription_row_${index}"),
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
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = sub.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RavenStatusPill(text = siteLabel)
                                        RavenStatusPill(text = sub.scope.displayLabel())
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (canUp || canDown) {
                                        Column {
                                            IconButton(
                                                onClick = { viewModel.moveSubscriptionUp(sub) },
                                                enabled = canUp,
                                                modifier = Modifier.size(30.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.KeyboardArrowUp,
                                                    contentDescription = "上移",
                                                    tint = if (canUp) colors.inkSecondary else colors.inkTertiary.copy(alpha = 0.35f),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.moveSubscriptionDown(sub) },
                                                enabled = canDown,
                                                modifier = Modifier.size(30.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.KeyboardArrowDown,
                                                    contentDescription = "下移",
                                                    tint = if (canDown) colors.inkSecondary else colors.inkTertiary.copy(alpha = 0.35f),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = { viewModel.checkSingleTag(sub) },
                                        enabled = !isChecking,
                                        modifier = Modifier.size(36.dp),
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
                                                contentDescription = "检查此标签更新",
                                                tint = colors.inkSecondary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { unsubscribeTarget = sub },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "取消订阅",
                                            tint = colors.inkTertiary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                SubscriptionsTab.FEED -> {
                    if (uiState.aggregatedTagFeed.isEmpty()) {
                        item(key = "sub_feed_empty") {
                            RavenEmptyState(
                                title = "暂无聚合动态",
                                message = "点击右上角刷新按钮检查订阅标签的新发布内容。首次检查会自动建立基线，避免历史内容刷屏。",
                                icon = Icons.Outlined.NotificationsActive,
                                actionLabel = "立即检查更新",
                                onActionClick = viewModel::refreshTagUpdates,
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = uiState.aggregatedTagFeed,
                            key = { _, entry -> entry.id },
                        ) { _, entry ->
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

    unsubscribeTarget?.let { sub ->
        LibraryConfirmBottomSheet(
            title = "取消订阅「${sub.label}」？",
            message = "该标签将从订阅列表移除，并清除对应的标签更新记录。",
            confirmLabel = "取消订阅",
            onConfirm = { viewModel.unsubscribe(sub) },
            onDismiss = { unsubscribeTarget = null },
        )
    }
}
