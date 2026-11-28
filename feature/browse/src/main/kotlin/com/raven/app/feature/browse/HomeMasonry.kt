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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenHorizontalShelf
import com.raven.app.core.designsystem.component.RavenLanguageBar
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenSiteSwitcherPill
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.workId
import com.raven.app.core.model.workTitle
import com.raven.app.core.model.SiteDescriptor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** 漫画/写真瀑布流首页：自适应列宽、置顶续读与多本继续阅读。 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryMasonryHome(
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
    val restoredHost = remember { SnackbarHostState() }
    var isRestoringPosition by remember { mutableStateOf(false) }
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

    // Infinite scroll trigger. Suppressed while a pagination error is showing so the failed page is
    // not re-requested on every recomposition arrow; only the footer retry button requests again.
    LaunchedEffect(gridState, uiState.hasMore, uiState.isLoadingMore, uiState.paginationErrorMessage, uiState.primaryItems.size) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 4
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (uiState.hasMore && !uiState.isLoadingMore && uiState.paginationErrorMessage == null) {
                    onLoadNextPage()
                }
            }
    }

    // Browse position persistence.
    // Masonry uses `primaryItems` as its only media items, but the grid is offset by the resume
    // card, the continue-reading shelf and the restored-anchor banner which are all full-span items.
    // Reading the visible item key keeps the saved anchor aligned with the actual work.
    val currentGalleryItems by rememberUpdatedState(uiState.primaryItems)
    val currentGalleryPage by rememberUpdatedState(uiState.currentPage)
    LaunchedEffect(gridState, activeSite.id) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo
                .firstNotNullOfOrNull { info -> (info.key as? String)?.takeIf { it.isNotEmpty() } }
        }
            .distinctUntilChanged()
            .collect { itemKey ->
                if (itemKey == null) return@collect
                // Staggered-grid item keys are `MediaItem.compositeKey` ("site:id"); chrome rows
                // (resume card, continue-reading shelf, restored-anchor banner, footer) use keys
                // that never collide with a composite key, so a non-match simply skips the save.
                val anchor = currentGalleryItems.firstOrNull { it.compositeKey == itemKey } ?: return@collect
                onSaveBrowsePosition(anchor.id, currentGalleryPage, "")
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
                                            key = { "cr_${it.siteId.value}:${it.workId}" },
                                        ) { progress ->
                                            val workTitle = progress.workTitle
                                            val chapterTitle = progress.title.takeIf {
                                                (progress.seriesId != null || progress.workId != progress.mediaId) &&
                                                    it.isNotBlank() && it != workTitle
                                            }
                                            val readingItem = MediaItem(
                                                siteId = progress.siteId,
                                                id = progress.mediaId,
                                                url = progress.url,
                                                kind = progress.kind,
                                                title = workTitle,
                                                coverUrl = progress.coverUrl,
                                                pageCount = progress.totalPages,
                                            )
                                            val siteLabel = uiState.allSites
                                                .firstOrNull { it.id == progress.siteId }
                                                ?.displayName
                                            val badge = listOfNotNull(
                                                siteLabel,
                                                chapterTitle ?: progress.kind.displayLabel(),
                                            ).joinToString(" · ")
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
                                                            val headerCount = (if (uiState.resumeReading != null) 1 else 0) +
                                                                (if (additionalReading.isNotEmpty()) 1 else 0) + 1
                                                            scope.launch { gridState.animateScrollToItem(headerCount + itemIndex) }
                                                        },
                                                        host = host,
                                                    )
                                                    isRestoringPosition = false
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
            BrowseRefreshFeedback(
                errorMessage = uiState.errorMessage.takeUnless { uiState.primaryItems.isEmpty() || uiState.authGateRequired },
                onRetry = onRefresh,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
