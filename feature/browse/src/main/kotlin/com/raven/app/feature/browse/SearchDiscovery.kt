package com.raven.app.feature.browse


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenRankedVideoCard
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.distinctUntilChanged

/** 搜索发现面板：历史、保存的搜索、热门标签与作品榜单。从 `SearchScreen.kt` 拆出，包名与 `testTag` 契约不变。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchDiscoveryPanel(
    activeSite: SiteDescriptor?,
    searchHistory: List<SearchHistoryEntry>,
    savedSearches: List<SavedSearch>,
    siteShortNames: Map<SiteId, String>,
    trendingTags: List<SearchTrendingTag>,
    hotTagRanking: List<SearchTrendingTag>,
    hotWorksRanking: List<MediaItem>,
    watchProgressMap: Map<String, Float>,
    readingProgressMap: Map<String, Float>,
    onSelectHistory: (SearchHistoryEntry) -> Unit,
    onRemoveHistory: (SearchHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onApplySavedSearch: (SavedSearch) -> Unit,
    onDeleteSavedSearch: (String) -> Unit,
    onSelectTagSlug: (String) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onLongClickMedia: (MediaItem) -> Unit,
    onAtTopChange: ((Boolean) -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current

    if (searchHistory.isEmpty() &&
        savedSearches.isEmpty() &&
        trendingTags.isEmpty() &&
        hotWorksRanking.isEmpty()
    ) {
        RavenEmptyState(
            title = "开始探索与检索",
            message = "输入关键词、英文或中文标签开始搜索；可随时切换单站高级筛选或跨站并发搜索。",
            icon = Icons.Outlined.Search,
        )
        return
    }

    val discoveryListState = rememberLazyListState()
    if (onAtTopChange != null) {
        val currentCallback by rememberUpdatedState(onAtTopChange)
        LaunchedEffect(discoveryListState) {
            snapshotFlow {
                SearchControlsVisibility.isAtAbsoluteTop(
                    firstVisibleItemIndex = discoveryListState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = discoveryListState.firstVisibleItemScrollOffset,
                )
            }
                .distinctUntilChanged()
                .collect { atTop -> currentCallback(atTop) }
        }
    }
    LazyColumn(
        state = discoveryListState,
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("search_discovery_panel"),
    ) {
        if (savedSearches.isNotEmpty()) {
            item(key = "saved_searches_block") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkAdded,
                            contentDescription = null,
                            tint = colors.crimson,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "已保存搜索",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.inkPrimary,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        savedSearches.forEach { saved ->
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 38.dp)
                                    .clip(RavenShapes.PillShape)
                                    .background(colors.surface1)
                                    .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                                    .combinedClickable(
                                        onClick = { onApplySavedSearch(saved) },
                                        onLongClick = { onDeleteSavedSearch(saved.id) },
                                    )
                                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                    .testTag("saved_search_chip_${saved.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = saved.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (saved.siteIds.size > 1) {
                                    Text(
                                        text = "${saved.siteIds.size}站",
                                        style = MaterialTheme.typography.labelSmall.tabular(),
                                        color = colors.crimson,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "删除 ${saved.name}",
                                    tint = colors.inkTertiary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onDeleteSavedSearch(saved.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (searchHistory.isNotEmpty()) {
            item(key = "search_history_block") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = null,
                                tint = colors.inkSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "最近搜索",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.inkPrimary,
                            )
                        }
                        TextButton(
                            onClick = onClearHistory,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = colors.inkTertiary,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "清空",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.inkTertiary,
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        searchHistory.forEach { entry ->
                            val historySiteKey = if (entry.siteId.isGlobal) "global" else entry.siteId.value
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .clip(RavenShapes.PillShape)
                                    .background(colors.surface1)
                                    .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                                    .clickable { onSelectHistory(entry) }
                                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                    .testTag(
                                        "search_history_chip_${historySiteKey}_" +
                                            "${entry.scope.name.lowercase()}_${entry.query}",
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = historySiteLabel(entry, siteShortNames),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (entry.siteId.isGlobal) colors.crimson else colors.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (entry.scope != SearchScope.ALL) {
                                        "${entry.scope.displayLabel()}:${entry.query}"
                                    } else {
                                        entry.query
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "删除历史记录",
                                    tint = colors.inkTertiary,
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clickable { onRemoveHistory(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (trendingTags.isNotEmpty()) {
            item(key = "search_trending_tags_block") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("search_trending_tags_section"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocalFireDepartment,
                            contentDescription = null,
                            tint = colors.crimson,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "当前热门标签",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.inkPrimary,
                        )
                        activeSite?.shortName?.let { siteName ->
                            Text(
                                text = "· $siteName",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkTertiary,
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        trendingTags.forEach { tag ->
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .clip(RavenShapes.PillShape)
                                    .background(colors.crimson.copy(alpha = 0.12f))
                                    .border(0.5.dp, colors.crimson.copy(alpha = 0.30f), RavenShapes.PillShape)
                                    .ravenSpringPress()
                                    .clickable(role = Role.Button) {
                                        feedback.perform(RavenFeedbackEffect.TAP)
                                        onSelectTagSlug(tag.slug)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("trending_tag_${tag.slug}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    text = "#",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = colors.crimson,
                                )
                                Text(
                                    text = tag.localizedName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (hotWorksRanking.isNotEmpty()) {
            item(key = "search_hot_works_ranking_block") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_hot_works_section"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RavenSectionHeader(
                        title = "热门作品排行",
                        subtitle = "${activeSite?.shortName ?: "当前站点"} · Top ${hotWorksRanking.size}",
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(
                            items = hotWorksRanking,
                            key = { idx, media -> "hot_work_${idx + 1}_${media.compositeKey}" },
                        ) { idx, media ->
                            val rank = idx + 1
                            if (media.kind.isVideo) {
                                RavenRankedVideoCard(
                                    rank = rank,
                                    item = media,
                                    onClick = { onOpenMedia(media) },
                                    onLongClick = { onLongClickMedia(media) },
                                    progressFraction = watchProgressMap[media.compositeKey],
                                    badgeText = media.extra["badge"],
                                )
                            } else {
                                RavenGalleryCard(
                                    item = media,
                                    onClick = { onOpenMedia(media) },
                                    onLongClick = { onLongClickMedia(media) },
                                    progressFraction = readingProgressMap[media.compositeKey],
                                    badgeText = "#$rank",
                                    modifier = Modifier.width(142.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (hotTagRanking.isNotEmpty()) {
            item(key = "search_hot_tag_ranking_block") {
                RavenGroupedSection(
                    title = "热门标签排行 Top ${hotTagRanking.size}",
                    footer = "轻点任一标签立即搜索对应作品",
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("search_hot_tag_ranking_section"),
                ) {
                    hotTagRanking.forEachIndexed { idx, tag ->
                        if (idx > 0) RavenGroupedDivider()
                        RavenGroupedRow(
                            title = tag.localizedName,
                            subtitle = if (tag.localizedName != tag.slug) tag.slug else null,
                            value = "热度 ${tag.count}",
                            showChevron = true,
                            onClick = { onSelectTagSlug(tag.slug) },
                            trailingContent = {
                                RavenStatusPill(
                                    text = "#${tag.rank}",
                                    tone = if (tag.rank <= 3) RavenStatusTone.ACCENT else RavenStatusTone.NEUTRAL,
                                )
                            },
                            modifier = Modifier.testTag("hot_tag_rank_${tag.rank}"),
                        )
                    }
                }
            }
        }
    }
}

