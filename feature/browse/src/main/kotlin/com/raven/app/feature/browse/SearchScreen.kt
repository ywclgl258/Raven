package com.raven.app.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.common.TagSuggestion
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenLoadingSkeleton
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenSiteSwitcherPill
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SitePlugin
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Unified Search destination supporting both **当前站点 (Single-Site)** and **跨站搜索 (Cross-Site)**
 * with TagDictionary suggestions, 30-item search history, Saved Searches, capability-driven
 * Advanced Filters sheet, and concurrent `Semaphore(3)` cross-site results with per-site status pills.
 */
@Composable
fun SearchScreen(
    navigator: BrowseNavigator,
    modifier: Modifier = Modifier,
    route: RavenRoute.Search = RavenRoute.Search(),
    viewModel: SearchViewModel = koinViewModel(
        key = "search_${route.initialQuery.orEmpty()}_${route.crossSite}",
        parameters = { parametersOf(route) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shelves by viewModel.shelfPickerShelves.collectAsStateWithLifecycle()
    val inspectedShelfIds by viewModel.inspectedShelfIds.collectAsStateWithLifecycle()

    SearchScreenContent(
        uiState = uiState,
        pluginResolver = viewModel::getPlugin,
        navigator = navigator,
        onSetMode = viewModel::setMode,
        onSelectActiveSite = viewModel::selectActiveSite,
        onTogglePinSite = viewModel::togglePinnedSite,
        onQueryChange = viewModel::updateQuery,
        onClearQuery = viewModel::clearQuery,
        onSubmitSearch = { viewModel.submitSearch() },
        onApplySuggestion = { sug -> viewModel.applyTagSuggestion(sug, autoSubmit = true) },
        onSelectScope = viewModel::selectScope,
        onSelectSort = viewModel::selectSort,
        onApplyAdvancedFilters = { filters, lang ->
            viewModel.applyAdvancedFilters(filters, lang, autoSubmit = true)
        },
        onResetAdvancedFilters = { viewModel.clearAdvancedFilters(autoSubmit = false) },
        onSelectHistory = viewModel::selectHistoryEntry,
        onRemoveHistory = viewModel::removeHistoryEntry,
        onClearHistory = viewModel::clearSiteHistory,
        onSaveSearch = viewModel::saveCurrentSearch,
        onApplySavedSearch = viewModel::applySavedSearch,
        onDeleteSavedSearch = viewModel::deleteSavedSearch,
        onDismissSavedNotice = viewModel::dismissSavedSearchNotice,
        onToggleCrossSite = viewModel::toggleCrossSearchSite,
        onSelectAllCrossSites = viewModel::selectAllCrossSearchSites,
        onSelectCrossSitesByKind = viewModel::selectCrossSearchSitesByKind,
        onToggleCrossSiteFilter = viewModel::toggleCrossSiteResultFilter,
        onRetryCrossSite = viewModel::retryCrossSite,
        onLoadMoreCrossSite = viewModel::loadMoreCrossSite,
        onLoadMoreSingleSite = viewModel::loadNextSingleSitePage,
        onToggleWatchLater = viewModel::toggleWatchLater,
        shelves = shelves,
        selectedShelfIds = inspectedShelfIds,
        onInspectShelfTarget = viewModel::inspectShelfTarget,
        onToggleShelfMembership = viewModel::setShelfMembership,
        onCreateShelfAndAdd = viewModel::createShelfAndAdd,
        modifier = modifier,
    )
}

@Composable
fun SearchScreenContent(
    uiState: SearchUiState,
    pluginResolver: (SiteId) -> SitePlugin?,
    navigator: BrowseNavigator,
    onSetMode: (SearchMode) -> Unit,
    onSelectActiveSite: (SiteId) -> Unit,
    onTogglePinSite: (SiteId) -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSubmitSearch: () -> Unit,
    onApplySuggestion: (TagSuggestion) -> Unit,
    onSelectScope: (SearchScope) -> Unit,
    onSelectSort: (FeedSort) -> Unit,
    onApplyAdvancedFilters: (AdvancedSearchFilters, ComicLanguage) -> Unit,
    onResetAdvancedFilters: () -> Unit,
    onSelectHistory: (SearchHistoryEntry) -> Unit,
    onRemoveHistory: (SearchHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onSaveSearch: (String) -> Unit,
    onApplySavedSearch: (SavedSearch) -> Unit,
    onDeleteSavedSearch: (String) -> Unit,
    onDismissSavedNotice: () -> Unit,
    onToggleCrossSite: (SiteId) -> Unit,
    onSelectAllCrossSites: () -> Unit,
    onSelectCrossSitesByKind: (MediaKind) -> Unit,
    onToggleCrossSiteFilter: (SiteId) -> Unit,
    onRetryCrossSite: (SiteId) -> Unit,
    onLoadMoreCrossSite: (SiteId) -> Unit,
    onLoadMoreSingleSite: () -> Unit,
    onToggleWatchLater: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    shelves: List<ShelfWithSummary> = emptyList(),
    selectedShelfIds: Set<String> = emptySet(),
    onInspectShelfTarget: (MediaItem?) -> Unit = {},
    onToggleShelfMembership: (shelfId: String, item: MediaItem, add: Boolean) -> Unit = { _, _, _ -> },
    onCreateShelfAndAdd: (name: String, item: MediaItem) -> Unit = { _, _ -> },
) {
    val colors = LocalRavenColors.current
    var showSiteSwitcher by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var showAdvancedSheet by rememberSaveable { mutableStateOf(false) }
    var showSaveSearchSheet by rememberSaveable { mutableStateOf(false) }
    var actionTargetItem by remember { mutableStateOf<MediaItem?>(null) }

    val activeSite = uiState.activeSite
    val isCustomSort = uiState.sort != uiState.nativeSorts.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .testTag("browse_search_screen"),
    ) {
        // Header: Mode toggle pill + Site switcher / Save search button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchModeTogglePill(
                mode = uiState.mode,
                onSelectMode = onSetMode,
                modifier = Modifier.weight(1f),
            )

            if (uiState.mode == SearchMode.SINGLE_SITE && activeSite != null) {
                Box(modifier = Modifier.testTag("site_switcher_pill")) {
                    RavenSiteSwitcherPill(
                        site = activeSite,
                        onClick = { showSiteSwitcher = true },
                    )
                }
            }
        }

        // Unified RavenSearchField + Advanced Filter & Save actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RavenSearchField(
                query = uiState.query,
                onQueryChange = onQueryChange,
                onSearch = { onSubmitSearch() },
                onClear = onClearQuery,
                placeholder = if (uiState.mode == SearchMode.CROSS_SITE) {
                    "跨站搜索 ${uiState.selectedCrossSiteIds.size} 个站点…"
                } else {
                    "在 ${activeSite?.shortName ?: "当前站点"} 中搜索作品、标签、作者…"
                },
                trailingContent = {
                    if (uiState.canSaveCurrentSearch) {
                        IconButton(
                            onClick = { showSaveSearchSheet = true },
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("search_save_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.BookmarkBorder,
                                contentDescription = "保存当前搜索",
                                tint = colors.inkPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )

            if (uiState.mode == SearchMode.SINGLE_SITE && uiState.hasAdvancedFilterSupport) {
                val filterCount = uiState.activeAdvancedFilterCount
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (filterCount > 0) colors.primaryCtaContainer else colors.surface2)
                        .border(0.5.dp, colors.hairline, CircleShape)
                        .ravenSpringPress()
                        .clickable(role = Role.Button) { showAdvancedSheet = true }
                        .testTag("search_advanced_filter_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "高级筛选",
                        tint = if (filterCount > 0) colors.primaryCtaInk else colors.inkPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    if (filterCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.crimson),
                        )
                    }
                }
            }
        }

        // Saved search confirmation toast banner
        val notice = uiState.lastSavedSearchNotice
        if (!notice.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkAdded,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismissSavedNotice,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭提示",
                        tint = colors.inkTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Live 165-entry TagDictionary suggestions bar
        if (uiState.tagSuggestions.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .testTag("search_tag_suggestions_row"),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(uiState.tagSuggestions, key = { it.slug }) { sug ->
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RavenShapes.PillShape)
                            .background(colors.surface2)
                            .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                            .clickable(role = Role.Button) { onApplySuggestion(sug) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("tag_suggestion_${sug.slug}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tag,
                            contentDescription = null,
                            tint = colors.crimson,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "${sug.localizedName} · ${sug.slug}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.inkPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Mode-specific controls row (prominent Sort chip ALWAYS pinned at position 0)
        when (uiState.mode) {
            SearchMode.SINGLE_SITE -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("search_single_controls_row"),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item(key = "search_sort_chip") {
                        RavenFilterChip(
                            label = "排序：${uiState.sort.displayLabel()}",
                            selected = isCustomSort,
                            leadingIcon = Icons.AutoMirrored.Rounded.Sort,
                            onClick = { showSortSheet = true },
                            modifier = Modifier.testTag("search_sort_chip"),
                        )
                    }
                    if (uiState.supportedScopes.size > 1) {
                        items(uiState.supportedScopes, key = { "scope_${it.name}" }) { scope ->
                            RavenFilterChip(
                                label = scope.displayLabel(),
                                selected = uiState.scope == scope,
                                onClick = { onSelectScope(scope) },
                                modifier = Modifier.testTag("search_scope_${scope.name.lowercase()}"),
                            )
                        }
                    }
                }
            }
            SearchMode.CROSS_SITE -> {
                CrossSiteSourceSelectorBar(
                    sort = uiState.sort,
                    isCustomSort = isCustomSort,
                    onOpenSortSheet = { showSortSheet = true },
                    searchableSites = uiState.searchableSites,
                    selectedSiteIds = uiState.selectedCrossSiteIds,
                    onToggleSite = onToggleCrossSite,
                    onSelectAll = onSelectAllCrossSites,
                    onSelectByKind = onSelectCrossSitesByKind,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            thickness = 0.5.dp,
            color = colors.hairline,
        )

        // Body: Discovery panel (before search) or Live Search Results
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (uiState.mode) {
                SearchMode.SINGLE_SITE -> {
                    if (!uiState.hasSearchedSingleSite) {
                        SearchDiscoveryPanel(
                            searchHistory = uiState.searchHistory,
                            savedSearches = uiState.savedSearches,
                            onSelectHistory = onSelectHistory,
                            onRemoveHistory = onRemoveHistory,
                            onClearHistory = onClearHistory,
                            onApplySavedSearch = onApplySavedSearch,
                            onDeleteSavedSearch = onDeleteSavedSearch,
                        )
                    } else {
                        SingleSiteResultsBody(
                            uiState = uiState,
                            activePlugin = activeSite?.id?.let(pluginResolver),
                            navigator = navigator,
                            onRetry = onSubmitSearch,
                            onLoadMore = onLoadMoreSingleSite,
                            onItemLongClick = { actionTargetItem = it },
                        )
                    }
                }
                SearchMode.CROSS_SITE -> {
                    if (!uiState.hasSearchedCrossSite) {
                        SearchDiscoveryPanel(
                            searchHistory = uiState.searchHistory,
                            savedSearches = uiState.savedSearches,
                            onSelectHistory = onSelectHistory,
                            onRemoveHistory = onRemoveHistory,
                            onClearHistory = onClearHistory,
                            onApplySavedSearch = onApplySavedSearch,
                            onDeleteSavedSearch = onDeleteSavedSearch,
                        )
                    } else {
                        CrossSiteResultsBody(
                            uiState = uiState,
                            pluginResolver = pluginResolver,
                            navigator = navigator,
                            onToggleFilterSite = onToggleCrossSiteFilter,
                            onRetrySite = onRetryCrossSite,
                            onLoadMoreSite = onLoadMoreCrossSite,
                            onRetryAll = onSubmitSearch,
                            onItemLongClick = { actionTargetItem = it },
                        )
                    }
                }
            }
        }
    }

    if (showSiteSwitcher && activeSite != null) {
        SiteSwitcherSheet(
            sites = uiState.allSites,
            activeSiteId = activeSite.id,
            pinnedSiteIds = uiState.pinnedSiteIds,
            onSelectSite = onSelectActiveSite,
            onTogglePinSite = onTogglePinSite,
            onDismissRequest = { showSiteSwitcher = false },
        )
    }

    if (showAdvancedSheet && activeSite != null) {
        AdvancedSearchFilterSheet(
            site = activeSite,
            supportedFilters = uiState.supportedFilters,
            supportsExcludeTags = uiState.supportsExcludeTags,
            supportsLanguageFilter = uiState.supportsLanguageFilter,
            initialFilters = uiState.advancedFilters,
            initialLanguage = uiState.comicLanguage,
            onApply = onApplyAdvancedFilters,
            onReset = onResetAdvancedFilters,
            onDismiss = { showAdvancedSheet = false },
        )
    }

    if (showSortSheet) {
        SearchSortPickerSheet(
            mode = uiState.mode,
            activeSiteName = activeSite?.displayName,
            selectedSort = uiState.sort,
            nativeSorts = uiState.nativeSorts,
            localSorts = uiState.localSorts,
            onSelectSort = { sort ->
                onSelectSort(sort)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false },
        )
    }

    if (showSaveSearchSheet) {
        val suggested = uiState.query.trim().ifBlank {
            "${activeSite?.shortName ?: "Raven"} 高级筛选"
        }
        val summary = if (uiState.mode == SearchMode.CROSS_SITE) {
            "跨站搜索 · ${uiState.selectedCrossSiteIds.size} 个站点 · ${uiState.sort.displayLabel()}"
        } else {
            "${activeSite?.displayName ?: "当前站点"} · ${uiState.scope.displayLabel()} · ${uiState.sort.displayLabel()}"
        }
        SaveSearchSheet(
            suggestedName = suggested,
            summary = summary,
            onSave = onSaveSearch,
            onDismiss = { showSaveSearchSheet = false },
        )
    }

    MediaCardActionAndShelfHost(
        targetItem = actionTargetItem,
        watchLaterKeys = uiState.watchLaterKeys,
        shelves = shelves,
        selectedShelfIds = selectedShelfIds,
        onInspectShelfTarget = onInspectShelfTarget,
        onDismiss = { actionTargetItem = null },
        onOpen = { navigator.openSearchResultItem(pluginResolver(it.siteId), it) },
        onToggleWatchLater = onToggleWatchLater,
        onToggleShelfMembership = onToggleShelfMembership,
        onCreateShelfAndAdd = onCreateShelfAndAdd,
        onSearchSimilar = { slug ->
            onQueryChange(slug)
            onSubmitSearch()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSortPickerSheet(
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
private fun SearchModeTogglePill(
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
private fun ModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Box(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RavenShapes.PillShape)
            .background(if (selected) colors.primaryCtaContainer else colors.surface1)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (selected) colors.primaryCtaInk else colors.inkSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun CrossSiteSourceSelectorBar(
    sort: FeedSort,
    isCustomSort: Boolean,
    onOpenSortSheet: () -> Unit,
    searchableSites: List<SiteDescriptor>,
    selectedSiteIds: List<SiteId>,
    onToggleSite: (SiteId) -> Unit,
    onSelectAll: () -> Unit,
    onSelectByKind: (MediaKind) -> Unit,
) {
    val selectedSet = remember(selectedSiteIds) { selectedSiteIds.toSet() }
    val allSelected = selectedSet.size == searchableSites.size

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
        item(key = "cross_select_all") {
            RavenFilterChip(
                label = "全部 (${searchableSites.size})",
                selected = allSelected,
                onClick = onSelectAll,
            )
        }
        items(MediaKind.entries, key = { "cross_kind_${it.name}" }) { kind ->
            val kindIds = searchableSites.filter { it.primaryKind == kind }.map { it.id }.toSet()
            RavenFilterChip(
                label = kind.displayLabel(),
                selected = !allSelected && kindIds.isNotEmpty() && selectedSet == kindIds,
                onClick = { onSelectByKind(kind) },
            )
        }
        items(searchableSites, key = { "cross_src_${it.id.value}" }) { site ->
            RavenFilterChip(
                label = site.shortName,
                selected = site.id in selectedSet,
                onClick = { onToggleSite(site.id) },
                modifier = Modifier.testTag("cross_site_chip_${site.id.value}"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchDiscoveryPanel(
    searchHistory: List<SearchHistoryEntry>,
    savedSearches: List<SavedSearch>,
    onSelectHistory: (SearchHistoryEntry) -> Unit,
    onRemoveHistory: (SearchHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onApplySavedSearch: (SavedSearch) -> Unit,
    onDeleteSavedSearch: (String) -> Unit,
) {
    val colors = LocalRavenColors.current

    if (searchHistory.isEmpty() && savedSearches.isEmpty()) {
        RavenEmptyState(
            title = "开始探索与检索",
            message = "输入关键词、英文或中文标签开始搜索；可随时切换单站高级筛选或跨站并发搜索。",
            icon = Icons.Outlined.Search,
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("search_discovery_panel"),
    ) {
        if (savedSearches.isNotEmpty()) {
            item(key = "saved_searches_block") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .clip(RavenShapes.PillShape)
                                    .background(colors.surface1)
                                    .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                                    .clickable { onSelectHistory(entry) }
                                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                    .testTag("search_history_chip_${entry.query}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
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
    }
}

@Composable
private fun SingleSiteResultsBody(
    uiState: SearchUiState,
    activePlugin: SitePlugin?,
    navigator: BrowseNavigator,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
) {
    val activeSite = uiState.activeSite ?: return

    when {
        uiState.singleSiteAuthRequired -> {
            BrowseAuthGateCard(
                site = activeSite,
                detailMessage = uiState.singleSiteError,
                onOpenAccount = { navigator.openSiteAccount(activeSite.id) },
                onRefreshStatus = onRetry,
            )
        }
        uiState.singleSiteLoading && uiState.singleSiteItems.isEmpty() -> {
            ExploreLoadingGrid(isVideo = activeSite.primaryKind == MediaKind.VIDEO)
        }
        uiState.singleSiteError != null && uiState.singleSiteItems.isEmpty() -> {
            RavenErrorState(
                message = uiState.singleSiteError,
                onRetry = onRetry,
            )
        }
        uiState.singleSiteItems.isEmpty() -> {
            RavenEmptyState(
                title = "没有找到匹配结果",
                message = "可尝试更换关键词、放宽高级筛选或切换到跨站搜索。",
                icon = Icons.Outlined.Search,
            )
        }
        uiState.singleSiteIsDirectory -> {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("single_site_directory_results"),
            ) {
                items(uiState.singleSiteItems, key = { it.compositeKey }) { item ->
                    DirectoryJumpCard(
                        title = item.title,
                        subtitle = "${activeSite.displayName} · 目录入口",
                        onClick = { navigator.openSearchResultItem(activePlugin, item) },
                    )
                }
                item(key = "single_dir_footer") {
                    BrowsePaginationFooter(
                        isLoadingMore = uiState.singleSiteLoadingMore,
                        errorMessage = uiState.singleSitePaginationError,
                        hasMore = uiState.singleSiteHasMore,
                        onRetry = onLoadMore,
                    )
                }
            }
        }
        activeSite.primaryKind == MediaKind.VIDEO -> {
            val siteWatchMap = remember(uiState.watchProgressMap, activeSite.id) {
                val prefix = "${activeSite.id.value}:"
                uiState.watchProgressMap
                    .filterKeys { it.startsWith(prefix) }
                    .mapKeys { it.key.removePrefix(prefix) }
            }
            ExploreVideoGrid(
                items = uiState.singleSiteItems,
                watchProgressMap = siteWatchMap,
                isLoadingMore = uiState.singleSiteLoadingMore,
                paginationError = uiState.singleSitePaginationError,
                hasMore = uiState.singleSiteHasMore,
                currentPage = 1,
                onItemClick = { navigator.openSearchResultItem(activePlugin, it) },
                onItemLongClick = onItemLongClick,
                onLoadNextPage = onLoadMore,
            )
        }
        else -> {
            val siteReadMap = remember(uiState.readingProgressMap, activeSite.id) {
                val prefix = "${activeSite.id.value}:"
                uiState.readingProgressMap
                    .filterKeys { it.startsWith(prefix) }
                    .mapKeys { it.key.removePrefix(prefix) }
            }
            ExploreGalleryGrid(
                siteId = activeSite.id,
                items = uiState.singleSiteItems,
                readingProgressMap = siteReadMap,
                isLoadingMore = uiState.singleSiteLoadingMore,
                paginationError = uiState.singleSitePaginationError,
                hasMore = uiState.singleSiteHasMore,
                currentPage = 1,
                onItemClick = { navigator.openSearchResultItem(activePlugin, it) },
                onItemLongClick = onItemLongClick,
                onLoadNextPage = onLoadMore,
            )
        }
    }
}

@Composable
private fun CrossSiteResultsBody(
    uiState: SearchUiState,
    pluginResolver: (SiteId) -> SitePlugin?,
    navigator: BrowseNavigator,
    onToggleFilterSite: (SiteId) -> Unit,
    onRetrySite: (SiteId) -> Unit,
    onLoadMoreSite: (SiteId) -> Unit,
    onRetryAll: () -> Unit = {},
    onItemLongClick: (MediaItem) -> Unit,
) {
    val colors = LocalRavenColors.current
    val gridState = rememberLazyStaggeredGridState()
    val aspectRatioCache = remember { mutableStateMapOf<String, Float>() }
    val siteShortNames = remember(uiState.allSites) {
        uiState.allSites.associate { it.id to it.shortName }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Per-site live status / retry / load-more pills
        if (uiState.crossSiteBuckets.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("cross_site_status_bar"),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(uiState.crossSiteBuckets, key = { "status_${it.site.id.value}" }) { bucket ->
                    CrossSiteStatusChip(
                        bucket = bucket,
                        selected = uiState.crossSiteFilterSiteId == bucket.site.id,
                        onFilterClick = { onToggleFilterSite(bucket.site.id) },
                        onRetryClick = { onRetrySite(bucket.site.id) },
                        onLoadMoreClick = { onLoadMoreSite(bucket.site.id) },
                        onAuthClick = { navigator.openSiteAccount(bucket.site.id) },
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
        }

        val allLoading = uiState.crossSiteBuckets.isNotEmpty() &&
            uiState.crossSiteBuckets.all { it.status == CrossSiteStatus.LOADING }
        val allCrossFailed = uiState.crossSiteBuckets.isNotEmpty() &&
            uiState.crossSiteBuckets.none { it.status == CrossSiteStatus.LOADING } &&
            uiState.crossSiteBuckets.all {
                it.status == CrossSiteStatus.ERROR || it.status == CrossSiteStatus.AUTH_REQUIRED
            }
        val hasAnyDirectoryJumps = uiState.crossSiteDirectoryBuckets.any {
            it.status == CrossSiteStatus.SUCCESS && it.items.isNotEmpty()
        }

        when {
            allLoading && uiState.crossSiteMediaItems.isEmpty() -> {
                RavenLoadingSkeleton(rowCount = 5)
            }
            uiState.crossSiteMediaItems.isEmpty() && !hasAnyDirectoryJumps && allCrossFailed -> {
                RavenErrorState(
                    message = "所选站点请求失败或需要登录，请点击上方站点胶囊单独重试或登录后再搜",
                    onRetry = onRetryAll,
                )
            }
            uiState.crossSiteMediaItems.isEmpty() && !hasAnyDirectoryJumps && !uiState.isCrossSiteSearching -> {
                RavenEmptyState(
                    title = "跨站搜索暂无结果",
                    message = "上方可点击失败站点胶囊单独重试，或切换更多来源站点。",
                    icon = Icons.Outlined.Search,
                )
            }
            else -> {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val columns = computeGalleryGridColumns(maxWidth.value.toInt())
                    LazyVerticalStaggeredGrid(
                        state = gridState,
                        columns = StaggeredGridCells.Fixed(columns),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 12.dp,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("cross_site_results_grid"),
                    ) {
                        // Directory-only sites rendered as clean jump cards
                        val dirBucketsWithResults = uiState.crossSiteDirectoryBuckets.filter {
                            it.status == CrossSiteStatus.SUCCESS && it.items.isNotEmpty()
                        }
                        if (dirBucketsWithResults.isNotEmpty()) {
                            item(
                                key = "cross_dir_header",
                                span = StaggeredGridItemSpan.FullLine,
                            ) {
                                Text(
                                    text = "目录站直达",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.inkSecondary,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                            dirBucketsWithResults.forEach { bucket ->
                                val previewItems = bucket.items.take(3)
                                previewItems.forEach { dirItem ->
                                    item(
                                        key = "cross_dir_${dirItem.compositeKey}",
                                        span = StaggeredGridItemSpan.FullLine,
                                    ) {
                                        DirectoryJumpCard(
                                            title = dirItem.title,
                                            subtitle = "${bucket.site.displayName} · 目录直达",
                                            onClick = {
                                                navigator.openSearchResultItem(
                                                    plugin = pluginResolver(bucket.site.id),
                                                    item = dirItem,
                                                )
                                            },
                                        )
                                    }
                                }
                                if (bucket.items.size > 3) {
                                    item(
                                        key = "cross_dir_more_${bucket.site.id.value}",
                                        span = StaggeredGridItemSpan.FullLine,
                                    ) {
                                        DirectoryJumpCard(
                                            title = "在 ${bucket.site.displayName} 查看全部 ${bucket.items.size} 条目录结果",
                                            subtitle = "关键词：${uiState.submittedQuery}",
                                            onClick = {
                                                navigator.openSiteFeed(
                                                    RavenRoute.SiteFeed(
                                                        siteId = bucket.site.id.value,
                                                        query = uiState.submittedQuery,
                                                        title = "${bucket.site.shortName} · ${uiState.submittedQuery}",
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // Mixed media result grid with explicit site badges
                        items(
                            items = uiState.crossSiteMediaItems,
                            key = { "cross_${it.compositeKey}" },
                        ) { item ->
                            val siteBadge = siteShortNames[item.siteId] ?: item.siteId.value
                            if (item.kind.isVideo) {
                                RavenVideoCard(
                                    item = item,
                                    onClick = { navigator.openMedia(item) },
                                    onLongClick = { onItemLongClick(item) },
                                    progressFraction = uiState.watchProgressMap[item.compositeKey],
                                    badgeText = siteBadge,
                                )
                            } else {
                                RavenGalleryCard(
                                    item = item,
                                    onClick = { navigator.openMedia(item) },
                                    onLongClick = { onItemLongClick(item) },
                                    aspectRatioOverride = aspectRatioCache[item.compositeKey],
                                    onAspectRatioResolved = { aspectRatioCache[item.compositeKey] = it },
                                    progressFraction = uiState.readingProgressMap[item.compositeKey],
                                    badgeText = siteBadge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrossSiteStatusChip(
    bucket: CrossSiteBucketState,
    selected: Boolean,
    onFilterClick: () -> Unit,
    onRetryClick: () -> Unit,
    onLoadMoreClick: () -> Unit,
    onAuthClick: () -> Unit,
) {
    val colors = LocalRavenColors.current

    val containerColor = when {
        selected -> colors.primaryCtaContainer
        bucket.status == CrossSiteStatus.ERROR -> colors.error.copy(alpha = 0.14f)
        bucket.status == CrossSiteStatus.AUTH_REQUIRED -> colors.warning.copy(alpha = 0.14f)
        else -> colors.surface1
    }
    val borderColor = when {
        selected -> colors.crimson
        bucket.status == CrossSiteStatus.ERROR -> colors.error.copy(alpha = 0.35f)
        bucket.status == CrossSiteStatus.AUTH_REQUIRED -> colors.warning.copy(alpha = 0.35f)
        else -> colors.hairline
    }
    val contentColor = when {
        selected -> colors.primaryCtaInk
        bucket.status == CrossSiteStatus.ERROR -> colors.error
        bucket.status == CrossSiteStatus.AUTH_REQUIRED -> colors.warning
        else -> colors.inkPrimary
    }

    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RavenShapes.PillShape)
            .background(containerColor)
            .border(0.5.dp, borderColor, RavenShapes.PillShape)
            .clickable(role = Role.Button) {
                when (bucket.status) {
                    CrossSiteStatus.ERROR -> onRetryClick()
                    CrossSiteStatus.AUTH_REQUIRED -> onAuthClick()
                    CrossSiteStatus.SUCCESS -> onFilterClick()
                    else -> Unit
                }
            }
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .testTag("cross_status_${bucket.site.id.value}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = bucket.site.shortName,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            maxLines = 1,
        )

        when (bucket.status) {
            CrossSiteStatus.LOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.6.dp,
                    color = colors.crimson,
                )
            }
            CrossSiteStatus.SUCCESS -> {
                Text(
                    text = bucket.items.size.toString(),
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = if (selected) colors.primaryCtaInk else colors.inkSecondary,
                )
                if (bucket.hasMore) {
                    if (bucket.isLoadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.6.dp,
                            color = colors.crimson,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable { onLoadMoreClick() }
                                .testTag("cross_load_more_${bucket.site.id.value}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "+更多",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.crimson,
                            )
                        }
                    }
                }
            }
            CrossSiteStatus.EMPTY -> {
                Text(
                    text = "0",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.inkTertiary,
                )
            }
            CrossSiteStatus.ERROR -> {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "重试 ${bucket.site.shortName}",
                    tint = colors.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                )
            }
            CrossSiteStatus.AUTH_REQUIRED -> {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "需登录",
                    tint = colors.warning,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "登录",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.warning,
                )
            }
            CrossSiteStatus.IDLE -> Unit
        }
    }
}

@Composable
private fun DirectoryJumpCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = LocalRavenColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("directory_jump_card"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RavenShapes.ControlShape)
                .background(colors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        RavenStatusPill(text = "直达", tone = RavenStatusTone.NEUTRAL)

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.inkTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}
