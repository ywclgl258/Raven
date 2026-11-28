package com.raven.app.feature.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalFireDepartment
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
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.common.TagSuggestion
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenBloubPresence
import com.raven.app.core.designsystem.component.RavenContentCrossfade
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFeatureTutorialDialog
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenTutorialStep
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenLoadingSkeleton
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.RavenPrimaryButton
import com.raven.app.core.designsystem.component.RavenRankedVideoCard
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenSiteSwitcherPill
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.component.ravenSquashPress
import com.raven.app.core.designsystem.motion.BloubMood
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
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
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Unified Search destination supporting both **当前站点 (Single-Site)** and **跨站搜索 (Cross-Site)**
 * with TagDictionary suggestions, a merged current-site + cross-site (`GLOBAL`) 30-item search
 * history, Saved Searches, capability-driven Advanced Filters sheet, and concurrent
 * `Semaphore(3)` cross-site results with per-site status pills, retry, and a full-span load-more footer.
 */
@Composable
fun SearchScreen(
    navigator: BrowseNavigator,
    modifier: Modifier = Modifier,
    route: RavenRoute.Search = RavenRoute.Search(),
    viewModel: SearchViewModel = koinViewModel(
        key = "search_${route.initialQuery.orEmpty()}_${route.crossSite}_${route.sourceSiteId.orEmpty()}",
        parameters = { parametersOf(route) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shelves by viewModel.shelfPickerShelves.collectAsStateWithLifecycle()
    val inspectedShelfIds by viewModel.inspectedShelfIds.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()

    SearchScreenContent(
        uiState = uiState,
        pluginResolver = viewModel::getPlugin,
        navigator = navigator,
        onSetMode = viewModel::setMode,
        onSelectActiveSite = viewModel::selectActiveSite,
        onTogglePinSite = viewModel::togglePinnedSite,
        onReorderPinnedSites = viewModel::reorderPinnedSites,
        onSetDynamicSiteSorting = viewModel::setDynamicSiteSorting,
        onSelectStartupDefaultSite = viewModel::setStartupDefaultSiteId,
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
        onApplyDateRange = viewModel::setDateRange,
        onSelectHistory = viewModel::selectHistoryEntry,
        onRemoveHistory = viewModel::removeHistoryEntry,
        onClearHistory = viewModel::clearSiteHistory,
        onSaveSearch = viewModel::saveCurrentSearch,
        onApplySavedSearch = viewModel::applySavedSearch,
        onDeleteSavedSearch = viewModel::deleteSavedSearch,
        onDismissSavedNotice = viewModel::dismissSavedSearchNotice,
        onSelectAllCrossSites = viewModel::selectAllCrossSearchSites,
        onToggleCrossSitesByKind = viewModel::toggleCrossSearchSitesByKind,
        onApplyCrossSites = viewModel::applyCrossSearchSites,
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
        onCompleteSearchTutorial = viewModel::completeSearchTutorial,
        onSkipAllTutorials = viewModel::skipAllTutorials,
        favoriteKeys = favoriteKeys,
        downloadStates = downloadStates,
        onToggleFavorite = viewModel::toggleFavorite,
        onDownloadMedia = viewModel::downloadMedia,
        actionMessage = actionMessage,
        onDismissActionMessage = viewModel::clearActionMessage,
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
    onSelectAllCrossSites: () -> Unit,
    onToggleCrossSitesByKind: (MediaKind) -> Unit,
    onApplyCrossSites: (Collection<SiteId>) -> Unit,
    onToggleCrossSiteFilter: (SiteId) -> Unit,
    onRetryCrossSite: (SiteId) -> Unit,
    onLoadMoreCrossSite: (SiteId) -> Unit,
    onLoadMoreSingleSite: () -> Unit,
    onToggleWatchLater: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    onSelectStartupDefaultSite: (SiteId?) -> Unit = {},
    onReorderPinnedSites: (List<SiteId>) -> Unit = {},
    onSetDynamicSiteSorting: (Boolean) -> Unit = {},
    favoriteKeys: Set<String> = emptySet(),
    downloadStates: Map<String, MediaCardDownloadState> = emptyMap(),
    onToggleFavorite: ((MediaItem) -> Unit)? = null,
    onDownloadMedia: ((MediaItem) -> Unit)? = null,
    actionMessage: String? = null,
    onDismissActionMessage: () -> Unit = {},
    shelves: List<ShelfWithSummary> = emptyList(),
    selectedShelfIds: Set<String> = emptySet(),
    onInspectShelfTarget: (MediaItem?) -> Unit = {},
    onToggleShelfMembership: (shelfId: String, item: MediaItem, add: Boolean) -> Unit = { _, _, _ -> },
    onCreateShelfAndAdd: (name: String, item: MediaItem) -> Unit = { _, _ -> },
    onApplyDateRange: (String?, String?) -> Unit = { _, _ -> },
    onCompleteSearchTutorial: () -> Unit = {},
    onSkipAllTutorials: () -> Unit = {},
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    var showSiteSwitcher by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var showAdvancedSheet by rememberSaveable { mutableStateOf(false) }
    var showDateRangeSheet by rememberSaveable { mutableStateOf(false) }
    var showSaveSearchSheet by rememberSaveable { mutableStateOf(false) }
    var showSourceSheet by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var actionTargetItem by remember { mutableStateOf<MediaItem?>(null) }

    val activeSite = uiState.activeSite
    val siteShortNames = remember(uiState.allSites) {
        uiState.allSites.associate { it.id to it.shortName }
    }
    val isCustomSort = uiState.sort != uiState.nativeSorts.firstOrNull()
    val hasDateFilter = !uiState.advancedFilters.publishedFrom.isNullOrBlank() ||
        !uiState.advancedFilters.publishedTo.isNullOrBlank()
    val dateChipLabel = if (hasDateFilter) {
        val fromLabel = uiState.advancedFilters.publishedFrom.orEmpty().ifBlank { "更早" }
        val toLabel = uiState.advancedFilters.publishedTo.orEmpty().ifBlank { "今天" }
        "$fromLabel ~ $toLabel"
    } else {
        "日期"
    }

    val hasActiveResults = when (uiState.mode) {
        SearchMode.SINGLE_SITE -> uiState.hasSearchedSingleSite
        SearchMode.CROSS_SITE -> uiState.hasSearchedCrossSite
    }
    // Tracks whether the currently rendered result/discovery list is pinned to its absolute top.
    // Reset on every stage change so a stale "not at top" value from the previous list cannot
    // keep the header hidden after switching modes or submitting a new query.
    val resultsAtTop = remember { mutableStateOf(true) }
    LaunchedEffect(
        uiState.mode,
        uiState.hasSearchedSingleSite,
        uiState.hasSearchedCrossSite,
        uiState.submittedQuery,
    ) {
        controlsVisible = true
        resultsAtTop.value = true
    }

    // Downward scroll hides the header. Upward scroll deliberately never re-shows it: only the
    // list reaching its absolute top does (see the LaunchedEffect below). Explicit user intent
    // (typing, opening filter sheets) shows the header while the list remains at the top.
    val currentHasResults by rememberUpdatedState(hasActiveResults)
    val autoHideScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!currentHasResults) return Offset.Zero
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y < 0f && controlsVisible) {
                    controlsVisible = false
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(resultsAtTop.value) {
        val atTop = resultsAtTop.value
        if (SearchControlsVisibility.shouldShow(forcedVisible = false, atAbsoluteTop = atTop)) {
            controlsVisible = true
        } else if (controlsVisible) {
            controlsVisible = false
        }
    }

    val headerEnter = if (motionReduced) {
        EnterTransition.None
    } else {
        expandVertically(
            animationSpec = tween(
                durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
        )
    }
    val headerExit = if (motionReduced) {
        ExitTransition.None
    } else {
        shrinkVertically(
            animationSpec = tween(
                durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS - 30,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .testTag("browse_search_screen"),
    ) {
        if (actionMessage != null) {
            BrowseActionMessageBanner(
                message = actionMessage,
                onDismiss = onDismissActionMessage,
            )
        }
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

        // Auto-collapsible search controls (search field, suggestions, sort/date/source filters)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = headerEnter,
            exit = headerExit,
            modifier = Modifier.testTag("search_collapsible_controls"),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
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
                        onQueryChange = {
                            controlsVisible = true
                            onQueryChange(it)
                        },
                        onSearch = { onSubmitSearch() },
                        onClear = {
                            controlsVisible = true
                            onClearQuery()
                        },
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
                        val filterInteractionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (filterCount > 0) colors.primaryCtaContainer else colors.surface2)
                                .border(0.5.dp, colors.hairline, CircleShape)
                                .ravenSquashPress(interactionSource = filterInteractionSource)
                                .clickable(
                                    interactionSource = filterInteractionSource,
                                    indication = null,
                                    role = Role.Button,
                                ) {
                                    feedback.perform(RavenFeedbackEffect.TAP)
                                    showAdvancedSheet = true
                                }
                                .testTag("search_advanced_filter_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            RavenMorphIcon(
                                glyph = if (filterCount > 0) RavenMorphIcons.FilterActive else RavenMorphIcons.FilterOutline,
                                tint = if (filterCount > 0) colors.primaryCtaInk else colors.inkPrimary,
                                size = 20.dp,
                                springPreset = MorphSpringPreset.SNAPPY,
                                contentDescription = "高级筛选",
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

                // Live TagDictionary suggestions bar
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
                                    .ravenSpringPress()
                                    .clickable(role = Role.Button) {
                                        feedback.perform(RavenFeedbackEffect.TAP)
                                        onApplySuggestion(sug)
                                    }
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

                // Mode-specific controls row (Sort + Date Range + Scope / Cross-Site sources)
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
                            item(key = "search_date_chip") {
                                RavenFilterChip(
                                    label = dateChipLabel,
                                    selected = hasDateFilter,
                                    leadingIcon = Icons.Outlined.DateRange,
                                    onClick = { showDateRangeSheet = true },
                                    modifier = Modifier.testTag("search_date_chip"),
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
                            dateChipLabel = dateChipLabel,
                            hasDateFilter = hasDateFilter,
                            onOpenSortSheet = { showSortSheet = true },
                            onOpenDateRangeSheet = { showDateRangeSheet = true },
                            searchableSites = uiState.searchableSites,
                            selectedSiteIds = uiState.selectedCrossSiteIds,
                            onSelectAll = onSelectAllCrossSites,
                            onOpenSourceSheet = { showSourceSheet = true },
                            onToggleKind = onToggleCrossSitesByKind,
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 4.dp),
                    thickness = 0.5.dp,
                    color = colors.hairline,
                )
            }
        }

        // Body: Discovery panel (before search) or Live Search Results
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(autoHideScrollConnection),
        ) {
            val searchStage = when (uiState.mode) {
                SearchMode.SINGLE_SITE -> if (uiState.hasSearchedSingleSite) "single_results" else "single_discovery"
                SearchMode.CROSS_SITE -> if (uiState.hasSearchedCrossSite) "cross_results" else "cross_discovery"
            }
            RavenContentCrossfade(targetState = searchStage) { stage ->
                when (stage) {
                    "single_discovery", "cross_discovery" -> {
                        SearchDiscoveryPanel(
                            activeSite = activeSite,
                            searchHistory = uiState.searchHistory,
                            siteShortNames = siteShortNames,
                            savedSearches = uiState.savedSearches,
                            trendingTags = uiState.trendingTags,
                            hotTagRanking = uiState.hotTagRanking,
                            hotWorksRanking = uiState.hotWorksRanking,
                            watchProgressMap = uiState.watchProgressMap,
                            readingProgressMap = uiState.readingProgressMap,
                            onSelectHistory = onSelectHistory,
                            onRemoveHistory = onRemoveHistory,
                            onClearHistory = onClearHistory,
                            onApplySavedSearch = onApplySavedSearch,
                            onDeleteSavedSearch = onDeleteSavedSearch,
                            onSelectTagSlug = { slug ->
                                onQueryChange(slug)
                                onSubmitSearch()
                            },
                            onOpenMedia = { navigator.openSearchResultItem(pluginResolver(it.siteId), it) },
                            onLongClickMedia = { actionTargetItem = it },
                            onAtTopChange = { resultsAtTop.value = it },
                        )
                    }
                    "single_results" -> {
                        SingleSiteResultsBody(
                            uiState = uiState,
                            activePlugin = activeSite?.id?.let(pluginResolver),
                            navigator = navigator,
                            onRetry = onSubmitSearch,
                            onLoadMore = onLoadMoreSingleSite,
                            onItemLongClick = { actionTargetItem = it },
                            onAtTopChange = { resultsAtTop.value = it },
                        )
                    }
                    else -> {
                        CrossSiteResultsBody(
                            uiState = uiState,
                            pluginResolver = pluginResolver,
                            navigator = navigator,
                            statusBarVisible = controlsVisible,
                            statusBarEnter = headerEnter,
                            statusBarExit = headerExit,
                            onToggleFilterSite = onToggleCrossSiteFilter,
                            onRetrySite = onRetryCrossSite,
                            onLoadMoreSite = onLoadMoreCrossSite,
                            onRetryAll = onSubmitSearch,
                            onItemLongClick = { actionTargetItem = it },
                            onAtTopChange = { resultsAtTop.value = it },
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
            siteOpenCounts = uiState.siteOpenCounts,
            startupDefaultSiteId = uiState.startupDefaultSiteId,
            dynamicSiteSorting = uiState.dynamicSiteSorting,
            onDynamicSiteSortingChange = onSetDynamicSiteSorting,
            onReorderPinnedSites = onReorderPinnedSites,
            onSelectStartupDefaultSite = onSelectStartupDefaultSite,
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

    if (showSourceSheet) {
        CrossSiteSourceSheet(
            sites = uiState.searchableSites,
            selectedIds = uiState.selectedCrossSiteIds,
            onApply = { ids ->
                onApplyCrossSites(ids)
                showSourceSheet = false
            },
            onDismiss = { showSourceSheet = false },
        )
    }

    if (showDateRangeSheet) {
        SearchDateRangeSheet(
            initialFrom = uiState.advancedFilters.publishedFrom,
            initialTo = uiState.advancedFilters.publishedTo,
            onApply = onApplyDateRange,
            onDismiss = { showDateRangeSheet = false },
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
        favoriteKeys = favoriteKeys,
        onToggleFavorite = onToggleFavorite,
        downloadStateByKey = downloadStates,
        onDownload = onDownloadMedia,
    )

    if (uiState.showSearchTutorial) {
        RavenFeatureTutorialDialog(
            featureTitle = "搜索与筛选导览",
            steps = listOf(
                RavenTutorialStep(
                    badge = "单站 / 跨站搜索",
                    title = "支持中文标签联想与跨站聚合",
                    description = "输入中文或英文关键词会自动匹配标签词典；切到「跨站搜索」可一次在多个站点并行检索并按站点快速筛选结果。",
                    icon = Icons.Outlined.Search,
                ),
                RavenTutorialStep(
                    badge = "高级筛选与保存",
                    title = "支持排序、日期筛选与保存搜索",
                    description = "搜索栏下方可调整排序、发布时间与高级条件；满意的搜索组合可直接保存，随时回访或追踪新内容。",
                    icon = Icons.Outlined.DateRange,
                ),
            ),
            onComplete = onCompleteSearchTutorial,
            onSkipAll = onSkipAllTutorials,
        )
    }
}

