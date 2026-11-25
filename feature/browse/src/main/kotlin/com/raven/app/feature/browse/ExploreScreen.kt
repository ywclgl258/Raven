package com.raven.app.feature.browse

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenGalleryCardSkeleton
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenSiteSwitcherPill
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.component.RavenVideoCardSkeleton
import com.raven.app.core.designsystem.component.displayLabel
import com.raven.app.core.designsystem.component.rememberRavenShimmerBrush
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteSectionSpec
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.koin.androidx.compose.koinViewModel

private enum class ExploreSheetKind {
    NONE,
    CATEGORY,
    SORT,
    LANGUAGE,
    DIRECTORY,
}

/**
 * Explore destination featuring a single-row compact filter bar (Category / Sort / Language /
 * Directory / Refresh), persisted per-site filters with one-tap clear, and adaptive video or
 * comic/photo staggered grids.
 */
@Composable
fun ExploreScreen(
    navigator: BrowseNavigator,
    modifier: Modifier = Modifier,
    viewModel: ExploreViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shelves by viewModel.shelfPickerShelves.collectAsStateWithLifecycle()
    val inspectedShelfIds by viewModel.inspectedShelfIds.collectAsStateWithLifecycle()

    ExploreScreenContent(
        uiState = uiState,
        navigator = navigator,
        onSelectSite = viewModel::selectSite,
        onTogglePinSite = viewModel::togglePinnedSite,
        onSelectSection = viewModel::selectSection,
        onSelectSort = viewModel::selectSort,
        onSelectLanguage = viewModel::selectLanguage,
        onClearFilters = viewModel::clearFilters,
        onRefresh = viewModel::refresh,
        onLoadNextPage = viewModel::loadNextPage,
        onToggleWatchLater = viewModel::toggleWatchLater,
        onSaveBrowsePosition = viewModel::saveBrowsePosition,
        shelves = shelves,
        selectedShelfIds = inspectedShelfIds,
        onInspectShelfTarget = viewModel::inspectShelfTarget,
        onToggleShelfMembership = viewModel::setShelfMembership,
        onCreateShelfAndAdd = viewModel::createShelfAndAdd,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreenContent(
    uiState: ExploreUiState,
    navigator: BrowseNavigator,
    onSelectSite: (SiteId) -> Unit,
    onTogglePinSite: (SiteId) -> Unit,
    onSelectSection: (SiteSectionSpec?) -> Unit,
    onSelectSort: (FeedSort) -> Unit,
    onSelectLanguage: (ComicLanguage) -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onToggleWatchLater: (MediaItem) -> Unit,
    onSaveBrowsePosition: (workId: String, throughPage: Int) -> Unit,
    modifier: Modifier = Modifier,
    shelves: List<ShelfWithSummary> = emptyList(),
    selectedShelfIds: Set<String> = emptySet(),
    onInspectShelfTarget: (MediaItem?) -> Unit = {},
    onToggleShelfMembership: (shelfId: String, item: MediaItem, add: Boolean) -> Unit = { _, _, _ -> },
    onCreateShelfAndAdd: (name: String, item: MediaItem) -> Unit = { _, _ -> },
) {
    val colors = LocalRavenColors.current
    val pullState = rememberPullToRefreshState()
    var showSiteSwitcher by rememberSaveable { mutableStateOf(false) }
    var activeSheet by rememberSaveable { mutableStateOf(ExploreSheetKind.NONE) }
    var actionTargetItem by remember { mutableStateOf<MediaItem?>(null) }

    val activeSite = uiState.activeSite

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("browse_explore_screen"),
    ) {
        RavenTopBar(
            frosted = false,
            showBottomHairline = false,
            titleContent = {
                if (activeSite != null) {
                    Box(modifier = Modifier.testTag("site_switcher_pill")) {
                        RavenSiteSwitcherPill(
                            site = activeSite,
                            onClick = { showSiteSwitcher = true },
                        )
                    }
                }
            },
            actions = {
                if (activeSite != null) {
                    IconButton(
                        onClick = { navigator.openSiteFunctions(activeSite.id) },
                        modifier = Modifier.testTag("explore_site_functions_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GridView,
                            contentDescription = "站点功能",
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            },
        )

        if (activeSite != null) {
            // Single-row compact filter bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.canvas)
                    .padding(vertical = 2.dp)
                    .testTag("explore_filter_bar"),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.availableSections.isNotEmpty()) {
                    item(key = "filter_category") {
                        val sectionLabel = uiState.selectedSectionSpec?.title ?: "分类"
                        RavenFilterChip(
                            label = sectionLabel,
                            selected = uiState.selectedSectionSpec != null,
                            leadingIcon = Icons.Outlined.Category,
                            onClick = { activeSheet = ExploreSheetKind.CATEGORY },
                            modifier = Modifier.testTag("explore_category_chip"),
                        )
                    }
                }

                if (uiState.availableSorts.size > 1) {
                    item(key = "filter_sort") {
                        val sortSelected = uiState.filter.sort != FeedSort.DEFAULT
                        val sortLabel = if (sortSelected) uiState.filter.sort.displayLabel() else "排序"
                        RavenFilterChip(
                            label = sortLabel,
                            selected = sortSelected,
                            leadingIcon = Icons.AutoMirrored.Rounded.Sort,
                            onClick = { activeSheet = ExploreSheetKind.SORT },
                            modifier = Modifier.testTag("explore_sort_chip"),
                        )
                    }
                }

                if (uiState.supportsLanguageFilter) {
                    item(key = "filter_language") {
                        val langSelected = uiState.filter.language != ComicLanguage.ALL
                        RavenFilterChip(
                            label = if (langSelected) uiState.filter.language.displayLabel() else "语言",
                            selected = langSelected,
                            leadingIcon = Icons.Outlined.Translate,
                            onClick = { activeSheet = ExploreSheetKind.LANGUAGE },
                            modifier = Modifier.testTag("explore_language_chip"),
                        )
                    }
                }

                if (uiState.directorySections.isNotEmpty()) {
                    item(key = "filter_directory") {
                        RavenFilterChip(
                            label = "目录索引",
                            selected = false,
                            leadingIcon = Icons.Outlined.FolderOpen,
                            onClick = {
                                if (uiState.directorySections.size == 1) {
                                    navigator.openSiteDirectory(
                                        siteId = activeSite.id,
                                        section = uiState.directorySections.first().id,
                                    )
                                } else {
                                    activeSheet = ExploreSheetKind.DIRECTORY
                                }
                            },
                            modifier = Modifier.testTag("explore_directory_chip"),
                        )
                    }
                }

                item(key = "filter_refresh") {
                    RavenFilterChip(
                        label = "刷新",
                        selected = false,
                        leadingIcon = Icons.Rounded.Refresh,
                        onClick = onRefresh,
                        modifier = Modifier.testTag("explore_refresh_chip"),
                    )
                }
            }

            if (uiState.activeFilterSummary.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("explore_active_filter_summary"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(uiState.activeFilterSummary) { summary ->
                            RavenStatusPill(
                                text = summary,
                                tone = RavenStatusTone.ACCENT,
                            )
                        }
                    }

                    TextButton(
                        onClick = onClearFilters,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("explore_clear_filters_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "重置",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
        }

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = uiState.isRefreshing,
                    containerColor = colors.surface2,
                    color = colors.crimson,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                activeSite == null -> {
                    RavenEmptyState(
                        title = "暂无可用站点",
                        message = "未检测到已注册站点。",
                    )
                }
                uiState.authGateRequired -> {
                    BrowseAuthGateCard(
                        site = activeSite,
                        detailMessage = uiState.errorMessage,
                        onOpenAccount = { navigator.openSiteAccount(activeSite.id) },
                        onRefreshStatus = onRefresh,
                    )
                }
                uiState.isLoading && uiState.items.isEmpty() -> {
                    ExploreLoadingGrid(isVideo = uiState.effectiveMediaKind == MediaKind.VIDEO)
                }
                uiState.errorMessage != null && uiState.items.isEmpty() -> {
                    RavenErrorState(
                        message = uiState.errorMessage,
                        onRetry = onRefresh,
                        secondaryActionLabel = if (uiState.hasActiveFilter) "重置筛选" else null,
                        onSecondaryActionClick = if (uiState.hasActiveFilter) onClearFilters else null,
                    )
                }
                uiState.items.isEmpty() -> {
                    RavenEmptyState(
                        title = "暂无匹配内容",
                        message = "可尝试切换分类、排序或清除当前筛选条件。",
                        actionLabel = if (uiState.hasActiveFilter) "重置筛选" else "重新加载",
                        onActionClick = if (uiState.hasActiveFilter) onClearFilters else onRefresh,
                    )
                }
                uiState.effectiveMediaKind == MediaKind.VIDEO -> {
                    ExploreVideoGrid(
                        items = uiState.items,
                        watchProgressMap = uiState.watchProgressMap,
                        isLoadingMore = uiState.isLoadingMore,
                        paginationError = uiState.paginationErrorMessage,
                        hasMore = uiState.hasMore,
                        currentPage = uiState.currentPage,
                        onItemClick = navigator::openMedia,
                        onItemLongClick = { actionTargetItem = it },
                        onLoadNextPage = onLoadNextPage,
                        onSaveBrowsePosition = onSaveBrowsePosition,
                        scrollResetKey = activeSite.id to uiState.filter,
                    )
                }
                else -> {
                    ExploreGalleryGrid(
                        siteId = activeSite.id,
                        items = uiState.items,
                        readingProgressMap = uiState.readingProgressMap,
                        isLoadingMore = uiState.isLoadingMore,
                        paginationError = uiState.paginationErrorMessage,
                        hasMore = uiState.hasMore,
                        currentPage = uiState.currentPage,
                        onItemClick = navigator::openMedia,
                        onItemLongClick = { actionTargetItem = it },
                        onLoadNextPage = onLoadNextPage,
                        onSaveBrowsePosition = onSaveBrowsePosition,
                        scrollResetKey = activeSite.id to uiState.filter,
                    )
                }
            }
        }
    }

    if (showSiteSwitcher && activeSite != null) {
        SiteSwitcherSheet(
            sites = uiState.allSites,
            activeSiteId = activeSite.id,
            pinnedSiteIds = uiState.pinnedSiteIds,
            onSelectSite = onSelectSite,
            onTogglePinSite = onTogglePinSite,
            onDismissRequest = { showSiteSwitcher = false },
        )
    }

    when (activeSheet) {
        ExploreSheetKind.CATEGORY -> {
            RavenBottomSheet(
                onDismissRequest = { activeSheet = ExploreSheetKind.NONE },
                title = "选择分类 / 分区",
                subtitle = activeSite?.displayName,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    RavenGroupedSection {
                        val isDefault = uiState.selectedSectionSpec == null
                        RavenGroupedRow(
                            title = "默认全部",
                            trailingContent = if (isDefault) {
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
                            onClick = {
                                onSelectSection(null)
                                activeSheet = ExploreSheetKind.NONE
                            },
                        )
                        uiState.availableSections.forEach { spec ->
                            RavenGroupedDivider()
                            val selected = uiState.selectedSectionSpec?.id == spec.id
                            RavenGroupedRow(
                                title = spec.title,
                                subtitle = spec.subtitle.ifBlank { null },
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
                                onClick = {
                                    onSelectSection(spec)
                                    activeSheet = ExploreSheetKind.NONE
                                },
                            )
                        }
                    }
                }
            }
        }
        ExploreSheetKind.SORT -> {
            RavenBottomSheet(
                onDismissRequest = { activeSheet = ExploreSheetKind.NONE },
                title = "排序方式",
            ) {
                RavenGroupedSection {
                    uiState.availableSorts.forEachIndexed { idx, sort ->
                        if (idx > 0) RavenGroupedDivider()
                        val selected = uiState.filter.sort == sort ||
                            (uiState.filter.sort == FeedSort.DEFAULT && idx == 0)
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
                            onClick = {
                                onSelectSort(sort)
                                activeSheet = ExploreSheetKind.NONE
                            },
                        )
                    }
                }
            }
        }
        ExploreSheetKind.LANGUAGE -> {
            RavenBottomSheet(
                onDismissRequest = { activeSheet = ExploreSheetKind.NONE },
                title = "语言筛选",
                subtitle = "同时会同步为该站点默认语言偏好",
            ) {
                RavenGroupedSection {
                    ComicLanguage.entries.forEachIndexed { idx, lang ->
                        if (idx > 0) RavenGroupedDivider()
                        val selected = uiState.filter.language == lang
                        RavenGroupedRow(
                            title = lang.displayLabel(),
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
                            onClick = {
                                onSelectLanguage(lang)
                                activeSheet = ExploreSheetKind.NONE
                            },
                        )
                    }
                }
            }
        }
        ExploreSheetKind.DIRECTORY -> {
            if (activeSite != null) {
                RavenBottomSheet(
                    onDismissRequest = { activeSheet = ExploreSheetKind.NONE },
                    title = "目录索引",
                    subtitle = "浏览 ${activeSite.displayName} 的分类、标签、作者与角色目录",
                ) {
                    RavenGroupedSection {
                        uiState.directorySections.forEachIndexed { idx, spec ->
                            if (idx > 0) RavenGroupedDivider()
                            RavenGroupedRow(
                                title = spec.title,
                                subtitle = spec.subtitle.ifBlank { null },
                                showChevron = true,
                                onClick = {
                                    activeSheet = ExploreSheetKind.NONE
                                    navigator.openSiteDirectory(activeSite.id, spec.id)
                                },
                            )
                        }
                    }
                }
            }
        }
        ExploreSheetKind.NONE -> Unit
    }

    MediaCardActionAndShelfHost(
        targetItem = actionTargetItem,
        watchLaterKeys = uiState.watchLaterKeys,
        shelves = shelves,
        selectedShelfIds = selectedShelfIds,
        onInspectShelfTarget = onInspectShelfTarget,
        onDismiss = { actionTargetItem = null },
        onOpen = { navigator.openMedia(it) },
        onToggleWatchLater = onToggleWatchLater,
        onToggleShelfMembership = onToggleShelfMembership,
        onCreateShelfAndAdd = onCreateShelfAndAdd,
        onSearchSimilar = { slug -> navigator.openSearch(initialQuery = slug, crossSite = false) },
    )
}

@Composable
internal fun ExploreVideoGrid(
    items: List<MediaItem>,
    watchProgressMap: Map<String, Float>,
    isLoadingMore: Boolean,
    paginationError: String?,
    hasMore: Boolean,
    currentPage: Int,
    onItemClick: (MediaItem) -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
    onLoadNextPage: () -> Unit,
    onSaveBrowsePosition: ((workId: String, throughPage: Int) -> Unit)? = null,
    badgeProvider: ((MediaItem) -> String?)? = null,
    scrollResetKey: Any? = null,
) {
    val gridState = rememberLazyGridState()
    var lastResetKey by remember { mutableStateOf(scrollResetKey) }
    LaunchedEffect(scrollResetKey) {
        if (lastResetKey != scrollResetKey) {
            lastResetKey = scrollResetKey
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(gridState, hasMore, isLoadingMore, items.size) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 4
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (hasMore && !isLoadingMore) onLoadNextPage()
            }
    }

    if (onSaveBrowsePosition != null) {
        LaunchedEffect(gridState, items, currentPage) {
            snapshotFlow { gridState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { index ->
                    val anchor = items.getOrNull(index)
                    if (anchor != null && index > 0) {
                        onSaveBrowsePosition(anchor.id, currentPage)
                    }
                }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = computeVideoGridColumns(maxWidth.value.toInt())
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("browse_video_grid"),
        ) {
            items(
                items = items,
                key = { it.compositeKey },
            ) { item ->
                RavenVideoCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    progressFraction = watchProgressMap[item.id],
                    badgeText = badgeProvider?.invoke(item) ?: item.extra["badge"],
                )
            }

            item(
                key = "video_grid_footer",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                BrowsePaginationFooter(
                    isLoadingMore = isLoadingMore,
                    errorMessage = paginationError,
                    hasMore = hasMore,
                    onRetry = onLoadNextPage,
                )
            }
        }
    }
}

@Composable
internal fun ExploreGalleryGrid(
    siteId: SiteId,
    items: List<MediaItem>,
    readingProgressMap: Map<String, Float>,
    isLoadingMore: Boolean,
    paginationError: String?,
    hasMore: Boolean,
    currentPage: Int,
    onItemClick: (MediaItem) -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
    onLoadNextPage: () -> Unit,
    onSaveBrowsePosition: ((workId: String, throughPage: Int) -> Unit)? = null,
    badgeProvider: ((MediaItem) -> String?)? = null,
    scrollResetKey: Any? = null,
) {
    val gridState = rememberLazyStaggeredGridState()
    val aspectRatioCache = remember(siteId) { mutableStateMapOf<String, Float>() }
    var lastResetKey by remember { mutableStateOf(scrollResetKey) }
    LaunchedEffect(scrollResetKey) {
        if (lastResetKey != scrollResetKey) {
            lastResetKey = scrollResetKey
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(gridState, hasMore, isLoadingMore, items.size) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 4
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (hasMore && !isLoadingMore) onLoadNextPage()
            }
    }

    if (onSaveBrowsePosition != null) {
        LaunchedEffect(gridState, items, currentPage) {
            snapshotFlow { gridState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { index ->
                    val anchor = items.getOrNull(index)
                    if (anchor != null && index > 0) {
                        onSaveBrowsePosition(anchor.id, currentPage)
                    }
                }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = computeGalleryGridColumns(maxWidth.value.toInt())
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(columns),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 12.dp,
            modifier = Modifier
                .fillMaxSize()
                .testTag("browse_gallery_grid"),
        ) {
            items(
                items = items,
                key = { it.compositeKey },
            ) { item ->
                RavenGalleryCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    aspectRatioOverride = aspectRatioCache[item.compositeKey],
                    onAspectRatioResolved = { ratio ->
                        aspectRatioCache[item.compositeKey] = ratio
                    },
                    progressFraction = readingProgressMap[item.id],
                    badgeText = badgeProvider?.invoke(item) ?: item.language?.takeIf { it.isNotBlank() },
                )
            }

            item(
                key = "gallery_grid_footer",
                span = StaggeredGridItemSpan.FullLine,
            ) {
                BrowsePaginationFooter(
                    isLoadingMore = isLoadingMore,
                    errorMessage = paginationError,
                    hasMore = hasMore,
                    onRetry = onLoadNextPage,
                )
            }
        }
    }
}

@Composable
internal fun ExploreLoadingGrid(isVideo: Boolean) {
    val brush = rememberRavenShimmerBrush()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val columns = if (isVideo) {
            computeVideoGridColumns(maxWidth.value.toInt())
        } else {
            computeGalleryGridColumns(maxWidth.value.toInt())
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(columns) {
                        if (isVideo) {
                            RavenVideoCardSkeleton(brush = brush, modifier = Modifier.weight(1f))
                        } else {
                            RavenGalleryCardSkeleton(brush = brush, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
