package com.raven.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenCompactMediaRow
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.InboxSourceKind
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Global Update Inbox (`GlobalInboxScreen`) unifying Tag Subscription updates and Saved Search updates.
 */
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("global_inbox_screen"),
    ) {
        RavenTopBar(
            title = "更新收件箱",
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
                            contentDescription = "检查全部更新",
                            tint = colors.inkPrimary,
                        )
                    }
                }
                if (uiState.unreadCount > 0) {
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
                        contentDescription = "管理标签订阅",
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
            if (uiState.refreshProgress.isRefreshing) {
                item(key = "inbox_refresh_bar") {
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
            }

            if (!uiState.message.isNullOrBlank()) {
                item(key = "inbox_msg") {
                    LibraryFeedbackBanner(
                        message = uiState.message.orEmpty(),
                        tone = RavenStatusTone.ACCENT,
                        onDismiss = viewModel::clearMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // Unread toggle + Source kind filter row
            item(key = "inbox_source_filters") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RavenFilterChip(
                            label = "仅看未读 (${uiState.unreadCount})",
                            selected = uiState.inboxUnreadOnly,
                            onClick = { viewModel.setInboxUnreadOnly(!uiState.inboxUnreadOnly) },
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
                        onItemSelected = viewModel::selectInboxSourceFilter,
                        keyProvider = { it.name },
                    )
                }
            }

            if (uiState.inboxSiteOptions.isNotEmpty()) {
                item(key = "inbox_site_filters") {
                    LibrarySiteFilterChips(
                        options = uiState.inboxSiteOptions,
                        selectedSiteId = uiState.inboxSiteFilter,
                        onSelectSite = viewModel::selectInboxSiteFilter,
                    )
                }
            }

            if (uiState.inboxItems.isEmpty()) {
                item(key = "inbox_empty") {
                    RavenEmptyState(
                        title = if (uiState.inboxUnreadOnly) "没有未读更新" else "收件箱为空",
                        message = if (uiState.inboxUnreadOnly) {
                            "所有订阅标签与保存搜索的新内容均已阅读。"
                        } else {
                            "点击刷新按钮可同时检查所有标签订阅与保存搜索的最新作品（首次检查自动建立基线）。"
                        },
                        icon = Icons.Outlined.NotificationsActive,
                        actionLabel = if (uiState.inboxUnreadOnly) "查看全部记录" else "立即检查更新",
                        onActionClick = {
                            if (uiState.inboxUnreadOnly) {
                                viewModel.setInboxUnreadOnly(false)
                            } else {
                                viewModel.refreshAllUpdates()
                            }
                        },
                    )
                }
            } else {
                items(
                    items = uiState.inboxItems,
                    key = { it.id },
                ) { entry ->
                    val siteLabel = uiState.siteLabels[entry.item.siteId] ?: entry.item.siteId.value
                    val sourcePrefix = if (entry.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION) "标签" else "搜索"

                    RavenCompactMediaRow(
                        item = entry.item,
                        onClick = {
                            if (!entry.isRead) viewModel.markRead(entry.id)
                            navigator.openMedia(entry.item)
                        },
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
                                RavenStatusPill(
                                    text = "$sourcePrefix · ${entry.sourceLabel}",
                                    tone = RavenStatusTone.NEUTRAL,
                                )
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (entry.isRead) viewModel.markUnread(entry.id) else viewModel.markRead(entry.id)
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = if (entry.isRead) {
                                            Icons.Outlined.MarkEmailUnread
                                        } else {
                                            Icons.Outlined.MarkEmailRead
                                        },
                                        contentDescription = if (entry.isRead) "标为未读" else "标为已读",
                                        tint = colors.inkSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.deleteInboxItem(entry.id) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "删除通知",
                                        tint = colors.inkTertiary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
