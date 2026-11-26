package com.raven.app.feature.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.database.model.ShelfWithSummary
import kotlinx.coroutines.launch
import com.raven.app.core.designsystem.component.RavenContentCrossfade
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenGalleryCardSkeleton
import com.raven.app.core.designsystem.component.RavenHeroBanner
import com.raven.app.core.designsystem.component.RavenHorizontalShelf
import com.raven.app.core.designsystem.component.RavenLanguageBar
import com.raven.app.core.designsystem.component.RavenRankedVideoCard
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenSiteSwitcherPill
import com.raven.app.core.designsystem.component.RavenSkeletonBox
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.component.RavenVideoCardSkeleton
import com.raven.app.core.designsystem.component.rememberRavenShimmerProgress
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.koin.androidx.compose.koinViewModel

/**
 * Primary Home destination adapting between Netflix Cinema Home (video sites) and
 * Frameless Native-Aspect-Ratio Masonry Home (comic & photo sites).
 */
@Composable
fun HomeScreen(
    navigator: BrowseNavigator,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shelves by viewModel.shelfPickerShelves.collectAsStateWithLifecycle()
    val inspectedShelfIds by viewModel.inspectedShelfIds.collectAsStateWithLifecycle()

    HomeScreenContent(
        uiState = uiState,
        navigator = navigator,
        onSelectSite = viewModel::selectSite,
        onTogglePinSite = viewModel::togglePinnedSite,
        onSelectStartupDefaultSite = viewModel::setStartupDefaultSiteId,
        onSelectLanguage = viewModel::selectComicLanguage,
        onRefresh = viewModel::refresh,
        onLoadNextPage = viewModel::loadNextPage,
        onToggleWatchLater = viewModel::toggleWatchLater,
        onDismissTutorial = viewModel::dismissSiteSwitchTutorial,
        onSaveBrowsePosition = viewModel::saveBrowsePosition,
        onDismissRestoredPosition = viewModel::dismissRestoredPosition,
        shelves = shelves,
        selectedShelfIds = inspectedShelfIds,
        onInspectShelfTarget = viewModel::inspectShelfTarget,
        onToggleShelfMembership = viewModel::setShelfMembership,
        onCreateShelfAndAdd = viewModel::createShelfAndAdd,
        modifier = modifier,
    )
}

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    navigator: BrowseNavigator,
    onSelectSite: (SiteId) -> Unit,
    onTogglePinSite: (SiteId) -> Unit,
    onSelectLanguage: (ComicLanguage) -> Unit,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onToggleWatchLater: (MediaItem) -> Unit,
    onDismissTutorial: () -> Unit,
    onSaveBrowsePosition: (workId: String, throughPage: Int, shelfId: String) -> Unit,
    onDismissRestoredPosition: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectStartupDefaultSite: (SiteId?) -> Unit = {},
    shelves: List<ShelfWithSummary> = emptyList(),
    selectedShelfIds: Set<String> = emptySet(),
    onInspectShelfTarget: (MediaItem?) -> Unit = {},
    onToggleShelfMembership: (shelfId: String, item: MediaItem, add: Boolean) -> Unit = { _, _, _ -> },
    onCreateShelfAndAdd: (name: String, item: MediaItem) -> Unit = { _, _ -> },
) {
    val colors = LocalRavenColors.current
    var showSiteSwitcher by rememberSaveable { mutableStateOf(false) }
    var actionTargetItem by remember { mutableStateOf<MediaItem?>(null) }

    val activeSite = uiState.activeSite

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("browse_home_screen"),
    ) {
        val homeStageKey = when {
            activeSite == null -> "none"
            uiState.isVideoHome -> "video"
            else -> "gallery"
        }
        RavenContentCrossfade(targetState = homeStageKey) { stage ->
            when (stage) {
                "none" -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RavenEmptyState(
                            title = "暂无可用站点",
                            message = "未检测到已注册的站点插件。",
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                "video" -> {
                    val site = activeSite ?: return@RavenContentCrossfade
                    CinemaVideoHome(
                        uiState = uiState,
                        activeSite = site,
                        navigator = navigator,
                        onOpenSiteSwitcher = {
                            onDismissTutorial()
                            showSiteSwitcher = true
                        },
                        onRefresh = onRefresh,
                        onLoadNextPage = onLoadNextPage,
                        onToggleWatchLater = onToggleWatchLater,
                        onDismissTutorial = onDismissTutorial,
                        onSaveBrowsePosition = onSaveBrowsePosition,
                        onDismissRestoredPosition = onDismissRestoredPosition,
                        onItemLongClick = { actionTargetItem = it },
                    )
                }
                else -> {
                    val site = activeSite ?: return@RavenContentCrossfade
                    GalleryMasonryHome(
                        uiState = uiState,
                        activeSite = site,
                        navigator = navigator,
                        onOpenSiteSwitcher = {
                            onDismissTutorial()
                            showSiteSwitcher = true
                        },
                        onSelectLanguage = onSelectLanguage,
                        onRefresh = onRefresh,
                        onLoadNextPage = onLoadNextPage,
                        onDismissTutorial = onDismissTutorial,
                        onSaveBrowsePosition = onSaveBrowsePosition,
                        onDismissRestoredPosition = onDismissRestoredPosition,
                        onItemLongClick = { actionTargetItem = it },
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
            siteOpenCounts = uiState.siteOpenCounts,
            startupDefaultSiteId = uiState.startupDefaultSiteId,
            onSelectStartupDefaultSite = onSelectStartupDefaultSite,
            onSelectSite = onSelectSite,
            onTogglePinSite = onTogglePinSite,
            onDismissRequest = { showSiteSwitcher = false },
        )
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
        onSearchSimilar = { tagSlug ->
            navigator.openSearch(initialQuery = tagSlug, crossSite = false)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CinemaVideoHome(
    uiState: HomeUiState,
    activeSite: SiteDescriptor,
    navigator: BrowseNavigator,
    onOpenSiteSwitcher: () -> Unit,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onToggleWatchLater: (MediaItem) -> Unit,
    onDismissTutorial: () -> Unit,
    onSaveBrowsePosition: (String, Int, String) -> Unit,
    onDismissRestoredPosition: () -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val listState = key(activeSite.id) { rememberLazyListState() }
    val coroutineScope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()

    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 36
        }
    }

    // Infinite scroll trigger when approaching bottom of primary stream
    LaunchedEffect(listState, uiState.hasMore, uiState.isLoadingMore, uiState.primaryItems.size) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (uiState.hasMore && !uiState.isLoadingMore) {
                    onLoadNextPage()
                }
            }
    }

    // Persist browse position anchor when scrolling through primary video stream
    val currentPrimaryItems by rememberUpdatedState(uiState.primaryItems)
    val currentPageNum by rememberUpdatedState(uiState.currentPage)
    LaunchedEffect(listState, activeSite.id) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstIdx ->
                val itemsList = currentPrimaryItems
                val anchor = itemsList.getOrNull(firstIdx.coerceIn(0, (itemsList.size - 1).coerceAtLeast(0)))
                if (anchor != null && firstIdx > 0) {
                    onSaveBrowsePosition(anchor.id, currentPageNum, "latest")
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 56.dp),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            val streamStage = when {
                uiState.authGateRequired -> HomeStreamStage.AUTH_GATE
                uiState.isLoading && uiState.isEmpty -> HomeStreamStage.LOADING
                uiState.errorMessage != null && uiState.isEmpty -> HomeStreamStage.ERROR
                uiState.isEmpty -> HomeStreamStage.EMPTY
                else -> HomeStreamStage.CONTENT
            }
            RavenContentCrossfade(
                targetState = streamStage,
                modifier = Modifier.fillMaxSize(),
                label = "cinemaHomeStageCrossfade",
            ) { stage ->
                when (stage) {
                    HomeStreamStage.AUTH_GATE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(top = 64.dp),
                        ) {
                            BrowseAuthGateCard(
                                site = activeSite,
                                detailMessage = uiState.errorMessage,
                                onOpenAccount = { navigator.openSiteAccount(activeSite.id) },
                                onRefreshStatus = onRefresh,
                            )
                        }
                    }
                    HomeStreamStage.LOADING -> {
                        CinemaHomeSkeleton()
                    }
                    HomeStreamStage.ERROR -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(top = 64.dp),
                        ) {
                            RavenErrorState(
                                message = uiState.errorMessage.orEmpty(),
                                onRetry = onRefresh,
                                secondaryActionLabel = if (activeSite.networkProfile.requiresAuthGate) "前往登录" else null,
                                onSecondaryActionClick = if (activeSite.networkProfile.requiresAuthGate) {
                                    { navigator.openSiteAccount(activeSite.id) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    HomeStreamStage.EMPTY -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(top = 64.dp),
                        ) {
                            RavenEmptyState(
                                title = "暂无推荐视频",
                                message = "当前筛选或屏蔽词过滤后暂无可展示内容，下拉可重新刷新。",
                                actionLabel = "重新加载",
                                onActionClick = onRefresh,
                            )
                        }
                    }
                    HomeStreamStage.CONTENT -> {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val gridColumns = computeVideoGridColumns(maxWidth.value.toInt())
                        val primaryRows = remember(uiState.primaryItems, gridColumns) {
                            uiState.primaryItems.chunked(gridColumns)
                        }
                        val hasHero = uiState.heroItems.isNotEmpty()

                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("cinema_home_list"),
                        ) {
                            if (hasHero) {
                                item(key = "cinema_hero_banner") {
                                    RavenHeroBanner(
                                        items = uiState.heroItems,
                                        onPlayClick = { navigator.openMedia(it) },
                                        onWatchLaterClick = onToggleWatchLater,
                                        onItemClick = { navigator.openMedia(it) },
                                        isInWatchLater = { it.compositeKey in uiState.watchLaterKeys },
                                        modifier = Modifier.testTag("hero_play_button"),
                                    )
                                }
                            } else {
                                item(key = "cinema_top_spacer") {
                                    Spacer(
                                        modifier = Modifier
                                            .statusBarsPadding()
                                            .height(64.dp),
                                    )
                                }
                            }

                            if (uiState.showSiteSwitchTutorial) {
                                item(key = "cinema_site_switch_tutorial") {
                                    SiteSwitchTutorialCard(
                                        siteCount = uiState.allSites.size,
                                        onOpenSwitcher = onOpenSiteSwitcher,
                                        onDismiss = onDismissTutorial,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }

                            val restoredPos = uiState.restoredPosition
                            if (restoredPos != null) {
                                item(key = "cinema_restored_anchor") {
                                    RestoredAnchorBanner(
                                        throughPage = restoredPos.throughPage,
                                        onJump = {
                                            val rowIdx = primaryRows.indexOfFirst { row ->
                                                row.any { it.id == restoredPos.workId }
                                            }
                                            if (rowIdx >= 0) {
                                                val headerCount = 1 +
                                                    (if (uiState.showSiteSwitchTutorial) 1 else 0) +
                                                    1 +
                                                    (if (uiState.continueWatching.isNotEmpty()) 1 else 0) +
                                                    uiState.sections.size +
                                                    1
                                                coroutineScope.launch {
                                                    listState.animateScrollToItem(headerCount + rowIdx)
                                                }
                                            } else {
                                                coroutineScope.launch { listState.animateScrollToItem(0) }
                                            }
                                        },
                                        onDismiss = onDismissRestoredPosition,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }

                            if (uiState.continueWatching.isNotEmpty()) {
                                item(key = "cinema_continue_watching") {
                                    RavenHorizontalShelf(
                                        title = "继续观看",
                                        subtitle = "上次未看完的视频",
                                        actionLabel = "历史记录",
                                        onActionClick = navigator::openHistory,
                                        modifier = Modifier.testTag("home_continue_watching_shelf"),
                                    ) {
                                        items(
                                            items = uiState.continueWatching,
                                            key = { "cw_${it.compositeKey}" },
                                        ) { wp ->
                                            val item = MediaItem(
                                                siteId = wp.siteId,
                                                id = wp.mediaId,
                                                url = wp.url,
                                                kind = MediaKind.VIDEO,
                                                title = wp.title,
                                                coverUrl = wp.coverUrl,
                                                durationSeconds = (wp.durationMs / 1000L).toInt().takeIf { it > 0 },
                                            )
                                            val subtitle = buildString {
                                                append(RavenFormatters.formatDurationMs(wp.positionMs))
                                                if (wp.durationMs > 0L) {
                                                    append(" / ")
                                                    append(RavenFormatters.formatDurationMs(wp.durationMs))
                                                }
                                            }
                                            RavenVideoCard(
                                                item = item,
                                                onClick = {
                                                    navigator.openVideo(
                                                        siteId = wp.siteId,
                                                        mediaId = wp.mediaId,
                                                        url = wp.url,
                                                        title = wp.title,
                                                        coverUrl = wp.coverUrl,
                                                    )
                                                },
                                                onLongClick = { onItemLongClick(item) },
                                                progressFraction = wp.progressFraction.coerceAtLeast(0.03f),
                                                subtitleOverride = subtitle,
                                                modifier = Modifier.width(214.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            uiState.sections.forEach { section ->
                                val seeAll = section.seeAllRequest
                                item(key = "cinema_section_${section.id}") {
                                    RavenHorizontalShelf(
                                        title = section.title,
                                        subtitle = section.subtitle.ifBlank { null },
                                        actionLabel = if (seeAll != null) "查看全部" else null,
                                        onActionClick = seeAll?.let { req ->
                                            { navigator.openSiteFeed(req.toSiteFeedRoute(title = section.title)) }
                                        },
                                        modifier = Modifier.testTag(
                                            if (section.isRanked) "home_top10_shelf" else "home_shelf_${section.id}",
                                        ),
                                    ) {
                                        if (section.isRanked) {
                                            itemsIndexed(
                                                items = section.items.take(10),
                                                key = { idx, item -> "rank_${section.id}_${idx}_${item.compositeKey}" },
                                            ) { index, item ->
                                                RavenRankedVideoCard(
                                                    rank = index + 1,
                                                    item = item,
                                                    onClick = { navigator.openMedia(item) },
                                                    onLongClick = { onItemLongClick(item) },
                                                    progressFraction = uiState.watchProgressMap[item.id],
                                                    badgeText = item.extra["badge"],
                                                )
                                            }
                                        } else {
                                            items(
                                                items = section.items,
                                                key = { "sec_${section.id}_${it.compositeKey}" },
                                            ) { item ->
                                                RavenVideoCard(
                                                    item = item,
                                                    onClick = { navigator.openMedia(item) },
                                                    onLongClick = { onItemLongClick(item) },
                                                    progressFraction = uiState.watchProgressMap[item.id],
                                                    badgeText = item.extra["badge"],
                                                    modifier = Modifier.width(210.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (primaryRows.isNotEmpty()) {
                                item(key = "cinema_latest_grid_header") {
                                    RavenSectionHeader(
                                        title = "更多精彩",
                                        subtitle = "${activeSite.displayName} · 持续更新",
                                    )
                                }

                                itemsIndexed(
                                    items = primaryRows,
                                    key = { rowIdx, row -> "vrow_${rowIdx}_${row.first().compositeKey}" },
                                ) { _, rowItems ->
                                    Row(
                                        modifier = Modifier
                                            .animateItem()
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        rowItems.forEach { item ->
                                            RavenVideoCard(
                                                item = item,
                                                onClick = { navigator.openMedia(item) },
                                                onLongClick = { onItemLongClick(item) },
                                                progressFraction = uiState.watchProgressMap[item.id],
                                                badgeText = item.extra["badge"],
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        repeat(gridColumns - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }

                                item(key = "cinema_pagination_footer") {
                                    BrowsePaginationFooter(
                                        isLoadingMore = uiState.isLoadingMore,
                                        errorMessage = uiState.paginationErrorMessage,
                                        hasMore = uiState.hasMore,
                                        onRetry = onLoadNextPage,
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        // Floating frosted top bar overlaying the full-bleed Hero Banner cleanly
        RavenTopBar(
            frosted = isScrolled || uiState.heroItems.isEmpty(),
            showBottomHairline = isScrolled,
            titleContent = {
                Box(modifier = Modifier.testTag("site_switcher_pill")) {
                    RavenSiteSwitcherPill(
                        site = activeSite,
                        frosted = !isScrolled && uiState.heroItems.isNotEmpty(),
                        onClick = onOpenSiteSwitcher,
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        feedback.perform(RavenFeedbackEffect.TAP)
                        navigator.openSiteFunctions(activeSite.id)
                    },
                    modifier = Modifier.testTag("home_site_functions_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GridView,
                        contentDescription = "站点功能",
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(
                    onClick = {
                        feedback.perform(RavenFeedbackEffect.TAP)
                        navigator.openSearch()
                    },
                    modifier = Modifier.testTag("home_search_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "搜索",
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryMasonryHome(
    uiState: HomeUiState,
    activeSite: SiteDescriptor,
    navigator: BrowseNavigator,
    onOpenSiteSwitcher: () -> Unit,
    onSelectLanguage: (ComicLanguage) -> Unit,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onDismissTutorial: () -> Unit,
    onSaveBrowsePosition: (String, Int, String) -> Unit,
    onDismissRestoredPosition: () -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val gridState = key(activeSite.id) { rememberLazyStaggeredGridState() }
    val coroutineScope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()
    val aspectRatioCache = remember(activeSite.id) { mutableStateMapOf<String, Float>() }

    // Collapsible language bar state driven by vertical scroll direction
    var languageBarVisible by remember(activeSite.id) { mutableStateOf(true) }

    LaunchedEffect(gridState, uiState.supportsLanguageFilter, activeSite.id) {
        if (!uiState.supportsLanguageFilter) return@LaunchedEffect
        var previousIndex = 0
        var previousOffset = 0
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (index == 0 && offset < 24) {
                    languageBarVisible = true
                } else {
                    val scrollingDown = index > previousIndex ||
                        (index == previousIndex && offset > previousOffset + 18)
                    val scrollingUp = index < previousIndex ||
                        (index == previousIndex && offset < previousOffset - 18)
                    if (scrollingDown) languageBarVisible = false
                    else if (scrollingUp) languageBarVisible = true
                }
                previousIndex = index
                previousOffset = offset
            }
    }

    // Infinite scroll trigger
    LaunchedEffect(gridState, uiState.hasMore, uiState.isLoadingMore, uiState.primaryItems.size) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 4
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (uiState.hasMore && !uiState.isLoadingMore) {
                    onLoadNextPage()
                }
            }
    }

    // Browse position persistence
    val currentGalleryItems by rememberUpdatedState(uiState.primaryItems)
    val currentGalleryPage by rememberUpdatedState(uiState.currentPage)
    LaunchedEffect(gridState, activeSite.id) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstIdx ->
                val itemsList = currentGalleryItems
                val anchor = itemsList.getOrNull(firstIdx.coerceIn(0, (itemsList.size - 1).coerceAtLeast(0)))
                if (anchor != null && firstIdx > 0) {
                    onSaveBrowsePosition(anchor.id, currentGalleryPage, "")
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        RavenTopBar(
            frosted = false,
            showBottomHairline = !uiState.supportsLanguageFilter || !languageBarVisible,
            titleContent = {
                Box(modifier = Modifier.testTag("site_switcher_pill")) {
                    RavenSiteSwitcherPill(
                        site = activeSite,
                        frosted = false,
                        onClick = onOpenSiteSwitcher,
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        feedback.perform(RavenFeedbackEffect.TAP)
                        navigator.openSiteFunctions(activeSite.id)
                    },
                    modifier = Modifier.testTag("home_site_functions_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GridView,
                        contentDescription = "站点功能",
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(
                    onClick = {
                        feedback.perform(RavenFeedbackEffect.TAP)
                        navigator.openSearch()
                    },
                    modifier = Modifier.testTag("home_search_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "搜索",
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )

        if (uiState.supportsLanguageFilter) {
            RavenLanguageBar(
                selected = uiState.comicLanguage,
                onSelected = onSelectLanguage,
                visible = languageBarVisible,
                modifier = Modifier.testTag("home_language_bar"),
            )
        }

        val motionReduced = LocalMotionReduced.current
        AnimatedVisibility(
            visible = uiState.showSiteSwitchTutorial,
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                expandVertically(tween(200, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    fadeIn(tween(180, easing = RavenMotion.HarmonyDecelerationEasing))
            },
            exit = if (motionReduced) {
                ExitTransition.None
            } else {
                shrinkVertically(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    fadeOut(tween(140, easing = RavenMotion.HarmonyDecelerationEasing))
            },
        ) {
            SiteSwitchTutorialCard(
                siteCount = uiState.allSites.size,
                onOpenSwitcher = onOpenSiteSwitcher,
                onDismiss = onDismissTutorial,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
            val galleryStage = when {
                uiState.authGateRequired -> HomeStreamStage.AUTH_GATE
                uiState.isLoading && uiState.primaryItems.isEmpty() -> HomeStreamStage.LOADING
                uiState.errorMessage != null && uiState.primaryItems.isEmpty() -> HomeStreamStage.ERROR
                uiState.primaryItems.isEmpty() -> HomeStreamStage.EMPTY
                else -> HomeStreamStage.CONTENT
            }
            RavenContentCrossfade(
                targetState = galleryStage,
                modifier = Modifier.fillMaxSize(),
                label = "galleryHomeStageCrossfade",
            ) { stage ->
                when (stage) {
                    HomeStreamStage.AUTH_GATE -> {
                        BrowseAuthGateCard(
                            site = activeSite,
                            detailMessage = uiState.errorMessage,
                            onOpenAccount = { navigator.openSiteAccount(activeSite.id) },
                            onRefreshStatus = onRefresh,
                        )
                    }
                    HomeStreamStage.LOADING -> {
                        GalleryMasonrySkeleton()
                    }
                    HomeStreamStage.ERROR -> {
                        RavenErrorState(
                            message = uiState.errorMessage.orEmpty(),
                            onRetry = onRefresh,
                            secondaryActionLabel = if (activeSite.networkProfile.requiresAuthGate) "前往登录" else null,
                            onSecondaryActionClick = if (activeSite.networkProfile.requiresAuthGate) {
                                { navigator.openSiteAccount(activeSite.id) }
                            } else {
                                null
                            },
                        )
                    }
                    HomeStreamStage.EMPTY -> {
                        RavenEmptyState(
                            title = "暂无作品",
                            message = "当前语言或屏蔽词条件下暂无内容，可切换语言或下拉刷新。",
                            actionLabel = "重新加载",
                            onActionClick = onRefresh,
                        )
                    }
                    HomeStreamStage.CONTENT -> {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val columns = computeGalleryGridColumns(maxWidth.value.toInt())

                        LazyVerticalStaggeredGrid(
                            state = gridState,
                            columns = StaggeredGridCells.Fixed(columns),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 10.dp,
                                bottom = 28.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalItemSpacing = 12.dp,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("gallery_masonry_grid"),
                        ) {
                            val resume = uiState.resumeReading
                            if (resume != null) {
                                item(
                                    key = "gallery_resume_card_${resume.compositeKey}",
                                    span = StaggeredGridItemSpan.FullLine,
                                ) {
                                    CompactResumeReadingCard(
                                        progress = resume,
                                        onResumeClick = {
                                            navigator.openReader(
                                                siteId = resume.siteId,
                                                mediaId = resume.mediaId,
                                                url = resume.url,
                                                startPage = resume.currentPage.coerceAtLeast(1),
                                            )
                                        },
                                    )
                                }
                            }

                            val additionalReading = if (resume != null) {
                                uiState.continueReading.drop(1)
                            } else {
                                emptyList()
                            }
                            if (additionalReading.isNotEmpty()) {
                                item(
                                    key = "gallery_continue_reading_shelf",
                                    span = StaggeredGridItemSpan.FullLine,
                                ) {
                                    RavenHorizontalShelf(
                                        title = "继续阅读",
                                        subtitle = "更多未读完的作品",
                                        actionLabel = "历史记录",
                                        onActionClick = navigator::openHistory,
                                        modifier = Modifier.testTag("home_continue_reading_shelf"),
                                    ) {
                                        items(
                                            items = additionalReading,
                                            key = { "cr_${it.compositeKey}" },
                                        ) { progress ->
                                            val readingItem = MediaItem(
                                                siteId = progress.siteId,
                                                id = progress.mediaId,
                                                url = progress.url,
                                                kind = progress.kind,
                                                title = progress.title,
                                                coverUrl = progress.coverUrl,
                                                pageCount = progress.totalPages,
                                            )
                                            val siteLabel = uiState.allSites
                                                .firstOrNull { it.id == progress.siteId }
                                                ?.displayName
                                            val badge = listOfNotNull(siteLabel, progress.kind.displayLabel())
                                                .joinToString(" · ")
                                            RavenGalleryCard(
                                                item = readingItem,
                                                onClick = {
                                                    navigator.openReader(
                                                        siteId = progress.siteId,
                                                        mediaId = progress.mediaId,
                                                        url = progress.url,
                                                        startPage = progress.currentPage.coerceAtLeast(1),
                                                    )
                                                },
                                                onLongClick = { onItemLongClick(readingItem) },
                                                progressFraction = progress.progressFraction,
                                                badgeText = badge,
                                                modifier = Modifier.width(148.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            val restoredPos = uiState.restoredPosition
                            if (restoredPos != null) {
                                item(
                                    key = "gallery_restored_anchor",
                                    span = StaggeredGridItemSpan.FullLine,
                                ) {
                                    RestoredAnchorBanner(
                                        throughPage = restoredPos.throughPage,
                                        onJump = {
                                            val itemIdx = uiState.primaryItems.indexOfFirst { it.id == restoredPos.workId }
                                            if (itemIdx >= 0) {
                                                val headerCount = (if (uiState.resumeReading != null) 1 else 0) +
                                                    (if (additionalReading.isNotEmpty()) 1 else 0) + 1
                                                coroutineScope.launch {
                                                    gridState.animateScrollToItem(headerCount + itemIdx)
                                                }
                                            }
                                        },
                                        onDismiss = onDismissRestoredPosition,
                                    )
                                }
                            }

                            items(
                                items = uiState.primaryItems,
                                key = { it.compositeKey },
                            ) { item ->
                                RavenGalleryCard(
                                    item = item,
                                    onClick = { navigator.openMedia(item) },
                                    onLongClick = { onItemLongClick(item) },
                                    aspectRatioOverride = aspectRatioCache[item.compositeKey],
                                    onAspectRatioResolved = { ratio ->
                                        aspectRatioCache[item.compositeKey] = ratio
                                    },
                                    progressFraction = uiState.readingProgressMap[item.id],
                                    badgeText = item.language?.takeIf { it.isNotBlank() },
                                )
                            }

                            item(
                                key = "gallery_pagination_footer",
                                span = StaggeredGridItemSpan.FullLine,
                            ) {
                                BrowsePaginationFooter(
                                    isLoadingMore = uiState.isLoadingMore,
                                    errorMessage = uiState.paginationErrorMessage,
                                    hasMore = uiState.hasMore,
                                    onRetry = onLoadNextPage,
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

private enum class HomeStreamStage {
    AUTH_GATE,
    LOADING,
    ERROR,
    EMPTY,
    CONTENT,
}

@Composable
private fun CinemaHomeSkeleton() {
    val shimmerProgress = rememberRavenShimmerProgress()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("cinema_home_skeleton"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RavenSkeletonBox(
            shape = RavenShapes.CardShape,
            progress = shimmerProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RavenSkeletonBox(
                progress = shimmerProgress,
                modifier = Modifier
                    .width(120.dp)
                    .height(18.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RavenVideoCardSkeleton(progress = shimmerProgress, modifier = Modifier.weight(1f))
                RavenVideoCardSkeleton(progress = shimmerProgress, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GalleryMasonrySkeleton() {
    val shimmerProgress = rememberRavenShimmerProgress()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("gallery_masonry_skeleton"),
    ) {
        val columns = computeGalleryGridColumns(maxWidth.value.toInt())
        val ratios = listOf(0.70f, 0.85f, 0.65f, 0.78f, 0.72f, 0.82f)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) { rowIdx ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(columns) { colIdx ->
                        RavenGalleryCardSkeleton(
                            aspectRatio = ratios[(rowIdx * columns + colIdx) % ratios.size],
                            progress = shimmerProgress,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
