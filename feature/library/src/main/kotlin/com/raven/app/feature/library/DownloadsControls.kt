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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenSegmentedTabs
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes

/** 下载页筛选与批量控制层：搜索/展示行、状态标签、批量控制、高级筛选面板与空状态。 */
@Composable
internal fun DownloadsSearchAndPresentationRow(
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
internal fun DownloadsStatusFilterTabs(
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
internal fun DownloadsBatchControls(
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
internal fun DownloadsExtraFiltersPanel(
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
internal fun DownloadsEmptyState(
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
