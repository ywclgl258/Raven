package com.raven.app.feature.browse

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenPrimaryButton
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId

/**
 * 搜索页「模式 / 排序 / 来源」控件层：模式切换 Pill、排序 Sheet、跨站来源选择条与来源 Sheet。
 *
 * 从 `SearchScreen.kt` 拆出，保持同包、同名与既有 `testTag` 契约不变，便于独立演进与评审。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSortPickerSheet(
    mode: SearchMode,
    activeSiteName: String?,
    selectedSort: FeedSort,
    nativeSorts: List<FeedSort>,
    localSorts: List<FeedSort>,
    onSelectSort: (FeedSort) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val subtitle = when (mode) {
        SearchMode.CROSS_SITE -> "向各站点请求最匹配排序，并按所选条件合并重排"
        SearchMode.SINGLE_SITE -> activeSiteName?.let { "当前站点：$it" }
    }
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "排序方式",
        subtitle = subtitle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("search_sort_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (nativeSorts.isNotEmpty()) {
                RavenGroupedSection(
                    title = if (mode == SearchMode.SINGLE_SITE && localSorts.isNotEmpty()) "站点原生排序" else null,
                ) {
                    nativeSorts.forEachIndexed { idx, sort ->
                        if (idx > 0) RavenGroupedDivider()
                        val selected = selectedSort == sort
                        RavenGroupedRow(
                            title = sort.displayLabel(),
                            trailingContent = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "已选中",
                                        tint = colors.crimson,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = { onSelectSort(sort) },
                            modifier = Modifier.testTag("search_sort_${sort.name.lowercase()}"),
                        )
                    }
                }
            }

            if (mode == SearchMode.SINGLE_SITE && localSorts.isNotEmpty()) {
                RavenGroupedSection(
                    title = "已加载结果排序",
                    footer = "当前站点搜索接口未原生提供以下排序，将在已加载结果内实时重排",
                ) {
                    localSorts.forEachIndexed { idx, sort ->
                        if (idx > 0) RavenGroupedDivider()
                        val selected = selectedSort == sort
                        RavenGroupedRow(
                            title = sort.displayLabel(),
                            trailingContent = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "已选中",
                                        tint = colors.crimson,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = { onSelectSort(sort) },
                            modifier = Modifier.testTag("search_sort_${sort.name.lowercase()}"),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun SearchModeTogglePill(
    mode: SearchMode,
    onSelectMode: (SearchMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RavenShapes.PillShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
            .padding(3.dp)
            .testTag("search_mode_toggle_pill"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegment(
            label = "当前站点",
            selected = mode == SearchMode.SINGLE_SITE,
            onClick = { onSelectMode(SearchMode.SINGLE_SITE) },
            modifier = Modifier
                .weight(1f)
                .testTag("search_mode_single"),
        )
        ModeSegment(
            label = "跨站搜索",
            selected = mode == SearchMode.CROSS_SITE,
            onClick = { onSelectMode(SearchMode.CROSS_SITE) },
            modifier = Modifier
                .weight(1f)
                .testTag("search_mode_cross"),
        )
    }
}

@Composable
internal fun ModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val colorSpec = if (motionReduced) {
        snap<androidx.compose.ui.graphics.Color>()
    } else {
        tween(durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS, easing = RavenMotion.HarmonyDecelerationEasing)
    }
    val container by animateColorAsState(
        targetValue = if (selected) colors.primaryCtaContainer else colors.surface1,
        animationSpec = colorSpec,
        label = "searchModeSegmentContainer",
    )
    val ink by animateColorAsState(
        targetValue = if (selected) colors.primaryCtaInk else colors.inkSecondary,
        animationSpec = colorSpec,
        label = "searchModeSegmentInk",
    )
    Box(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RavenShapes.PillShape)
            .background(container)
            .ravenSpringPress()
            .clickable(role = Role.Tab) {
                if (!selected) {
                    feedback.perform(RavenFeedbackEffect.TAB_SWITCH)
                }
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = ink,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CrossSiteSourceSelectorBar(
    sort: FeedSort,
    isCustomSort: Boolean,
    dateChipLabel: String,
    hasDateFilter: Boolean,
    onOpenSortSheet: () -> Unit,
    onOpenDateRangeSheet: () -> Unit,
    searchableSites: List<SiteDescriptor>,
    selectedSiteIds: List<SiteId>,
    onSelectAll: () -> Unit,
    onOpenSourceSheet: () -> Unit,
    onToggleKind: (MediaKind) -> Unit,
) {
    val selectedSet = remember(selectedSiteIds) { selectedSiteIds.toSet() }
    val allSelected = selectedSet.size == searchableSites.size
    val kindGroups = remember(searchableSites) {
        MediaKind.entries.mapNotNull { kind ->
            val sites = searchableSites.filter { it.primaryKind == kind }
            if (sites.isEmpty()) null else kind to sites
        }
    }
    val sourceLabel = remember(selectedSet, kindGroups, allSelected) {
        when {
            allSelected -> "来源 · 全部"
            kindGroups.size == 1 -> "来源 · ${selectedSet.size} 站"
            else -> {
                val fullySelected = kindGroups.filter { (_, sites) -> sites.all { it.id in selectedSet } }
                if (fullySelected.size == 1) {
                    "来源 · ${fullySelected.first().first.displayLabel()}"
                } else {
                    "来源 · ${selectedSet.size} 站"
                }
            }
        }
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag("cross_site_source_selector"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "search_sort_chip") {
            RavenFilterChip(
                label = "排序：${sort.displayLabel()}",
                selected = isCustomSort,
                leadingIcon = Icons.AutoMirrored.Rounded.Sort,
                onClick = onOpenSortSheet,
                modifier = Modifier.testTag("search_sort_chip"),
            )
        }
        item(key = "search_date_chip") {
            RavenFilterChip(
                label = dateChipLabel,
                selected = hasDateFilter,
                leadingIcon = Icons.Outlined.DateRange,
                onClick = onOpenDateRangeSheet,
                modifier = Modifier.testTag("search_date_chip"),
            )
        }
        item(key = "cross_select_all") {
            RavenFilterChip(
                label = "全部 (${searchableSites.size})",
                selected = allSelected,
                onClick = onSelectAll,
                modifier = Modifier.testTag("cross_site_select_all"),
            )
        }
        item(key = "cross_source_chip") {
            RavenFilterChip(
                label = sourceLabel,
                selected = !allSelected,
                leadingIcon = Icons.Outlined.Tune,
                onClick = onOpenSourceSheet,
                modifier = Modifier.testTag("cross_site_source_chip"),
            )
        }
        items(kindGroups, key = { "cross_kind_${it.first.name}" }) { (kind, sites) ->
            val kindIds = remember(sites) { sites.map { it.id }.toSet() }
            val selectedCount = kindIds.count { it in selectedSet }
            RavenFilterChip(
                label = if (selectedCount == kindIds.size) {
                    "${kind.displayLabel()} ${kindIds.size}"
                } else {
                    "${kind.displayLabel()} $selectedCount/${kindIds.size}"
                },
                selected = selectedCount == kindIds.size,
                onClick = { onToggleKind(kind) },
                modifier = Modifier.testTag("cross_kind_chip_${kind.name.lowercase()}"),
            )
        }
    }
}

/**
 * 跨站搜索来源面板：按大分类分组展示全部可搜索站点，支持分类级全选/取消与逐站多选，
 * 应用后一次写回选择，避免逐站切换造成多次持久化。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CrossSiteSourceSheet(
    sites: List<SiteDescriptor>,
    selectedIds: List<SiteId>,
    onApply: (List<SiteId>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val allIds = remember(sites) { sites.map { it.id }.toSet() }
    var draft by remember(selectedIds) { mutableStateOf(selectedIds.toSet()) }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "搜索来源",
        subtitle = "已选 ${draft.size} / ${allIds.size} 个站点 · 可按分类或单站选择",
        trailingAction = {
            TextButton(onClick = { draft = allIds }) {
                Text("全选", color = colors.crimson, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .testTag("cross_site_source_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediaKind.entries.forEach { kind ->
                val kindSites = sites.filter { it.primaryKind == kind }
                if (kindSites.isEmpty()) return@forEach
                val kindIds = kindSites.map { it.id }.toSet()
                val selectedCount = kindIds.count { it in draft }
                RavenGroupedSection(
                    title = "${kind.displayLabel()} · $selectedCount/${kindIds.size}",
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { draft = toggleCategory(draft, kindIds) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TriStateCheckbox(
                            state = when (selectedCount) {
                                0 -> ToggleableState.Off
                                kindIds.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            },
                            onClick = null,
                        )
                        Text(
                            text = "全选${kind.displayLabel()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "$selectedCount/${kindIds.size}",
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = colors.inkSecondary,
                        )
                    }
                    RavenGroupedDivider()
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        kindSites.forEach { site ->
                            RavenFilterChip(
                                label = site.shortName,
                                selected = site.id in draft,
                                onClick = {
                                    draft = if (site.id in draft) draft - site.id else draft + site.id
                                },
                                modifier = Modifier.testTag("source_site_${site.id.value}"),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            RavenPrimaryButton(
                text = if (draft.isEmpty()) "至少选择 1 个站点" else "应用 · ${draft.size} 个站点",
                onClick = { onApply(sites.map { it.id }.filter { it in draft }) },
                enabled = draft.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun toggleCategory(current: Set<SiteId>, kindIds: Set<SiteId>): Set<SiteId> =
    if (current.containsAll(kindIds)) current - kindIds else current + kindIds
