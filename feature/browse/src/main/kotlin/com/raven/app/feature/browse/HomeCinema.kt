package com.raven.app.feature.browse

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.raven.app.core.designsystem.component.RavenContentCrossfade
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenHeroBanner
import com.raven.app.core.designsystem.component.RavenHorizontalShelf
import com.raven.app.core.designsystem.component.RavenRankedVideoCard
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenSiteSwitcherPill
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.workId
import com.raven.app.core.model.SiteDescriptor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** 影院式视频首页：全出血 Hero 轮播、继续观看、Top 10 与横向片单货架。从 `HomeScreen.kt` 拆出，包名与 `testTag` 契约不变。 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CinemaVideoHome(
    uiState: HomeUiState,
    activeSite: SiteDescriptor,
    navigator: BrowseNavigator,
    onOpenSiteSwitcher: () -> Unit,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onToggleWatchLater: (MediaItem) -> Unit,
    onDismissTutorial: () -> Unit,
    onCompleteHomeTutorial: () -> Unit = {},
    onSkipAllTutorials: () -> Unit = {},
    onSaveBrowsePosition: (String, Int, String) -> Unit,
    onDismissRestoredPosition: () -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val listState = key(activeSite.id) { rememberLazyListState() }
    val coroutineScope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()
    val restoredHost = remember { SnackbarHostState() }
    var isRestoringPosition by remember { mutableStateOf(false) }

    // Keyed on `listState` so switching sites (which rebuilds the state via `key(activeSite.id)`)
    // never leaves the top bar bound to the previous site's scroll position.
    val isScrolled by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 36
        }
    }

    // Infinite scroll trigger when approaching bottom of primary stream.
    // Suppressed while a pagination error is showing so a failed page cannot be re-requested on
    // every effect restart; only the footer's explicit retry may try again.
    LaunchedEffect(listState, uiState.hasMore, uiState.isLoadingMore, uiState.paginationErrorMessage, uiState.primaryItems.size) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (uiState.hasMore && !uiState.isLoadingMore && uiState.paginationErrorMessage == null) {
                    onLoadNextPage()
                }
            }
    }

    // Persist browse position anchor when scrolling through primary video stream.
    // Resolve the anchor from the LazyColumn item *key* instead of `firstVisibleItemIndex`: the list
    // index counts the hero banner, tutorial, restored-anchor banner, continue-watching shelf,
    // ranked shelves and the section header, so a raw index would select the wrong flat item.
    val currentPrimaryItems by rememberUpdatedState(uiState.primaryItems)
    val currentPageNum by rememberUpdatedState(uiState.currentPage)
    LaunchedEffect(listState, activeSite.id) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { (it.key as? String)?.startsWith("vrow_") == true }
                ?.key as? String
        }
            .distinctUntilChanged()
            .collect { rowKey ->
                if (rowKey == null) return@collect
                // Key shape is "vrow_<rowIdx>_<firstItemCompositeKey>" where compositeKey is
                // "site:id". Match by suffix so IDs containing underscores stay unambiguous.
                val anchor = currentPrimaryItems.firstOrNull { rowKey.endsWith("_${it.compositeKey}") }
                    ?: return@collect
                onSaveBrowsePosition(anchor.id, currentPageNum, "latest")
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
                                            val scope = coroutineScope
                                            val host = restoredHost
                                            if (!isRestoringPosition) {
                                                isRestoringPosition = true
                                                scope.launch {
                                                    jumpToSavedWork(
                                                        targetId = restoredPos.workId,
                                                        readItems = { uiState.primaryItems },
                                                        hasMore = { uiState.hasMore },
                                                        onLoadNextPage = onLoadNextPage,
                                                        onFound = { itemIndex ->
                                                            val rowIdx = itemIndex / gridColumns
                                                            val headerCount = 1 +
                                                                (if (uiState.showSiteSwitchTutorial) 1 else 0) +
                                                                1 +
                                                                (if (uiState.continueWatching.isNotEmpty()) 1 else 0) +
                                                                uiState.sections.size +
                                                                1
                                                            scope.launch { listState.animateScrollToItem(headerCount + rowIdx) }
                                                        },
                                                        host = host,
                                                    )
                                                    isRestoringPosition = false
                                                }
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
            BrowseRefreshFeedback(
                errorMessage = uiState.errorMessage.takeUnless { uiState.isEmpty || uiState.authGateRequired },
                onRetry = onRefresh,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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

        RavenScrollToTopButton(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 22.dp),
        )
    }
}
