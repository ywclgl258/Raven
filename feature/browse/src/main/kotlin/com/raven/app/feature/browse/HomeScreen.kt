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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.zIndex
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
import com.raven.app.core.designsystem.component.RavenFeatureTutorialDialog
import com.raven.app.core.designsystem.component.RavenLanguageBar
import com.raven.app.core.designsystem.component.RavenRankedVideoCard
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenSiteSwitcherPill
import com.raven.app.core.designsystem.component.RavenSkeletonBox
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.RavenTutorialStep
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
import com.raven.app.core.model.workId
import com.raven.app.core.model.workTitle
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
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()

    HomeScreenContent(
        uiState = uiState,
        navigator = navigator,
        onSelectSite = viewModel::selectSite,
        onTogglePinSite = viewModel::togglePinnedSite,
        onReorderPinnedSites = viewModel::reorderPinnedSites,
        onSetDynamicSiteSorting = viewModel::setDynamicSiteSorting,
        onSelectStartupDefaultSite = viewModel::setStartupDefaultSiteId,
        onSelectLanguage = viewModel::selectComicLanguage,
        onRefresh = viewModel::refresh,
        onLoadNextPage = viewModel::loadNextPage,
        onToggleWatchLater = viewModel::toggleWatchLater,
        onDismissTutorial = viewModel::dismissSiteSwitchTutorial,
        onCompleteHomeTutorial = viewModel::completeHomeTutorial,
        onSkipAllTutorials = viewModel::skipAllTutorials,
        onSaveBrowsePosition = viewModel::saveBrowsePosition,
        onDismissRestoredPosition = viewModel::dismissRestoredPosition,
        shelves = shelves,
        selectedShelfIds = inspectedShelfIds,
        onInspectShelfTarget = viewModel::inspectShelfTarget,
        onToggleShelfMembership = viewModel::setShelfMembership,
        onCreateShelfAndAdd = viewModel::createShelfAndAdd,
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
    onCompleteHomeTutorial: () -> Unit = {},
    onSkipAllTutorials: () -> Unit = {},
) {
    val colors = LocalRavenColors.current
    var showSiteSwitcher by rememberSaveable { mutableStateOf(false) }
    var actionTargetItem by remember { mutableStateOf<MediaItem?>(null) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

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
        if (actionMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .zIndex(2f),
            ) {
                BrowseActionMessageBanner(
                    message = actionMessage,
                    onDismiss = onDismissActionMessage,
                )
            }
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
                        snackbarHostState = snackbarHostState,
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
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
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
        favoriteKeys = favoriteKeys,
        onToggleFavorite = onToggleFavorite,
        downloadStateByKey = downloadStates,
        onDownload = onDownloadMedia,
    )

    if (uiState.showHomeTutorial && !uiState.isLoading) {
        RavenFeatureTutorialDialog(
            featureTitle = "欢迎使用 Raven",
            steps = listOf(
                RavenTutorialStep(
                    badge = "多站切换",
                    title = "左上角快速切换站点",
                    description = "点击顶部站点胶囊可在 24 个视频、漫画与写真站点间即时切换，并支持常用站点置顶与自定义启动默认站。",
                    icon = Icons.Rounded.GridView,
                ),
                RavenTutorialStep(
                    badge = "顺滑浏览",
                    title = "下滑自动弹出回顶按钮",
                    description = "任意长列表向下滑动时，右下角会弹性弹出「回到顶部」按钮，一键平滑回到页首，回到顶部后自动隐藏。",
                    icon = Icons.Rounded.Search,
                ),
            ),
            onComplete = onCompleteHomeTutorial,
            onSkipAll = onSkipAllTutorials,
        )
    }
}
