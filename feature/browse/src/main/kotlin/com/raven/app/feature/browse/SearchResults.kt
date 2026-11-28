package com.raven.app.feature.browse


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBloubPresence
import com.raven.app.core.designsystem.component.RavenContentCrossfade
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenLoadingSkeleton
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.motion.BloubMood
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SitePlugin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** 搜索结果主体：单站结果、加载态与跨站结果分组。 */
@Composable
internal fun SingleSiteResultsBody(
    uiState: SearchUiState,
    activePlugin: SitePlugin?,
    navigator: BrowseNavigator,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
    onAtTopChange: ((Boolean) -> Unit)? = null,
) {
    val activeSite = uiState.activeSite ?: return

    val singleStage = when {
        uiState.singleSiteAuthRequired -> "auth"
        uiState.singleSiteLoading && uiState.singleSiteItems.isEmpty() -> "loading"
        uiState.singleSiteError != null && uiState.singleSiteItems.isEmpty() -> "error"
        uiState.singleSiteItems.isEmpty() -> "empty"
        uiState.singleSiteIsDirectory -> "directory"
        activeSite.primaryKind == MediaKind.VIDEO -> "video"
        else -> "gallery"
    }
    RavenContentCrossfade(targetState = singleStage) { stage ->
        when (stage) {
            "auth" -> {
                BrowseAuthGateCard(
                    site = activeSite,
                    detailMessage = uiState.singleSiteError,
                    onOpenAccount = { navigator.openSiteAccount(activeSite.id) },
                    onRefreshStatus = onRetry,
                )
            }
            "loading" -> {
                SearchActiveLoadingState(subtitle = "正在检索 ${activeSite.shortName}…") {
                    ExploreLoadingGrid(isVideo = activeSite.primaryKind == MediaKind.VIDEO)
                }
            }
            "error" -> {
                RavenErrorState(
                    message = uiState.singleSiteError ?: "搜索失败",
                    onRetry = onRetry,
                )
            }
            "empty" -> {
                RavenEmptyState(
                    title = "没有找到匹配结果",
                    message = "可尝试更换关键词、放宽高级筛选或切换到跨站搜索。",
                    icon = Icons.Outlined.Search,
                )
            }
            "directory" -> {
                val dirListState = rememberLazyListState()
                if (onAtTopChange != null) {
                    val currentCallback by rememberUpdatedState(onAtTopChange)
                    LaunchedEffect(dirListState) {
                        snapshotFlow {
                            SearchControlsVisibility.isAtAbsoluteTop(
                                firstVisibleItemIndex = dirListState.firstVisibleItemIndex,
                                firstVisibleItemScrollOffset = dirListState.firstVisibleItemScrollOffset,
                            )
                        }
                            .distinctUntilChanged()
                            .collect { atTop -> currentCallback(atTop) }
                    }
                }
                LaunchedEffect(
                    dirListState,
                    uiState.singleSiteHasMore,
                    uiState.singleSiteLoadingMore,
                    uiState.singleSitePaginationError,
                    uiState.singleSiteItems.size,
                ) {
                    snapshotFlow {
                        val total = dirListState.layoutInfo.totalItemsCount
                        val lastVisible = dirListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        total > 0 && lastVisible >= total - 3
                    }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            if (uiState.singleSiteHasMore &&
                                !uiState.singleSiteLoadingMore &&
                                uiState.singleSitePaginationError == null
                            ) {
                                onLoadMore()
                            }
                        }
                }
                LazyColumn(
                    state = dirListState,
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
            "video" -> {
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
                    onAtTopChange = onAtTopChange,
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
                    onAtTopChange = onAtTopChange,
                )
            }
        }
    }
}

@Composable
internal fun SearchActiveLoadingState(
    subtitle: String,
    content: @Composable () -> Unit,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            RavenBloubPresence(
                mood = BloubMood.SEARCHING,
                size = 64.dp,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            content()
        }
    }
}

@Composable
internal fun CrossSiteResultsBody(
    uiState: SearchUiState,
    pluginResolver: (SiteId) -> SitePlugin?,
    navigator: BrowseNavigator,
    statusBarVisible: Boolean = true,
    statusBarEnter: EnterTransition = EnterTransition.None,
    statusBarExit: ExitTransition = ExitTransition.None,
    onToggleFilterSite: (SiteId) -> Unit,
    onRetrySite: (SiteId) -> Unit,
    onLoadMoreSite: (SiteId) -> Unit,
    onRetryAll: () -> Unit = {},
    onItemLongClick: (MediaItem) -> Unit,
    onAtTopChange: ((Boolean) -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val gridState = rememberLazyStaggeredGridState()
    val aspectRatioCache = remember { mutableStateMapOf<String, Float>() }
    if (onAtTopChange != null) {
        val currentCallback by rememberUpdatedState(onAtTopChange)
        LaunchedEffect(gridState) {
            snapshotFlow {
                SearchControlsVisibility.isAtAbsoluteTop(
                    firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset,
                )
            }
                .distinctUntilChanged()
                .collect { atTop -> currentCallback(atTop) }
        }
    }
    val siteShortNames = remember(uiState.allSites) {
        uiState.allSites.associate { it.id to it.shortName }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Per-site live status / retry / load-more pills (collapses with search header on scroll down)
        if (uiState.crossSiteBuckets.isNotEmpty()) {
            AnimatedVisibility(
                visible = statusBarVisible,
                enter = statusBarEnter,
                exit = statusBarExit,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
            }
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
        val hasAnyLoadableBuckets = uiState.crossSiteBuckets.any { it.hasMore || it.isLoadingMore }

        when {
            allLoading && uiState.crossSiteMediaItems.isEmpty() -> {
                SearchActiveLoadingState(subtitle = "正在跨站并发搜索…") {
                    RavenLoadingSkeleton(rowCount = 5)
                }
            }
            uiState.crossSiteMediaItems.isEmpty() && !hasAnyDirectoryJumps && allCrossFailed -> {
                RavenErrorState(
                    message = "所选站点请求失败或需要登录，请点击上方站点胶囊单独重试或登录后再搜",
                    onRetry = onRetryAll,
                )
            }
            uiState.crossSiteMediaItems.isEmpty() &&
                !hasAnyDirectoryJumps &&
                !uiState.isCrossSiteSearching &&
                !hasAnyLoadableBuckets -> {
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

                        val footerBuckets = uiState.crossSiteBuckets.filter {
                            it.hasMore || it.isLoadingMore
                        }
                        if (footerBuckets.isNotEmpty()) {
                            item(
                                key = "cross_pagination_footer",
                                span = StaggeredGridItemSpan.FullLine,
                            ) {
                                CrossSitePaginationFooter(
                                    buckets = footerBuckets,
                                    onLoadMoreSite = onLoadMoreSite,
                                )
                            }
                        }
                    }
                    RavenScrollToTopButton(
                        staggeredGridState = gridState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 18.dp, bottom = 22.dp),
                    )
                }
            }
        }
    }
}

