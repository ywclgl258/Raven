package com.raven.app.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.model.BrowsePosition
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BrowsePositionRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenContentCrossfade
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenLanguageBar
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data class SiteFeedUiState(
    val site: SiteDescriptor? = null,
    val title: String = "",
    val subtitle: String? = null,
    val selectedSort: FeedSort = FeedSort.DEFAULT,
    val availableSorts: List<FeedSort> = emptyList(),
    val comicLanguage: ComicLanguage = ComicLanguage.ALL,
    val supportsLanguageFilter: Boolean = false,
    val canSubscribe: Boolean = false,
    val isSubscribed: Boolean = false,
    val effectiveMediaKind: MediaKind = MediaKind.VIDEO,
    val items: List<MediaItem> = emptyList(),
    val watchProgressMap: Map<String, Float> = emptyMap(),
    val readingProgressMap: Map<String, Float> = emptyMap(),
    val watchLaterKeys: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val authGateRequired: Boolean = false,
    val errorMessage: String? = null,
    val paginationErrorMessage: String? = null,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
)

private data class SiteFeedNetworkState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val authGateRequired: Boolean = false,
    val errorMessage: String? = null,
    val paginationErrorMessage: String? = null,
    val rawItems: List<MediaItem> = emptyList(),
    val serverTitle: String? = null,
    val currentPage: Int = 1,
    val nextPage: Int? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

class SiteFeedViewModel(
    val route: RavenRoute.SiteFeed,
    private val siteRegistry: SiteRegistry,
    private val preferencesRepository: PreferencesRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val historyRepository: HistoryRepository,
    private val shelfRepository: ShelfRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val browsePositionRepository: BrowsePositionRepository,
    private val dispatchers: AppDispatchers,
    private val tagDictionary: TagDictionary = TagDictionary.default,
    private val mediaTagCacheRepository: MediaTagCacheRepository? = null,
    private val tagDictionaryRepository: TagDictionaryRepository? = null,
) : ViewModel() {

    val siteId: SiteId? = runCatching { SiteId.of(route.siteId) }.getOrNull()
    val plugin: SitePlugin? = siteId?.let(siteRegistry::get)
    private val scope: SearchScope = SearchScope.entries.firstOrNull {
        it.name.equals(route.scope, ignoreCase = true)
    } ?: SearchScope.ALL
    private val querySlug: String? = route.query?.trim()?.takeIf { it.isNotEmpty() }
    private val canSubscribe: Boolean =
        querySlug != null && scope in setOf(SearchScope.TAG, SearchScope.AUTHOR, SearchScope.CHARACTER)

    private val selectedSort = MutableStateFlow(FeedSort.DEFAULT)
    private val networkState = MutableStateFlow(SiteFeedNetworkState())

    private var loadJob: Job? = null
    private var paginateJob: Job? = null
    private var browsePositionJob: Job? = null
    private val shelfController = BrowseShelfController(
        shelfRepository = shelfRepository,
        scope = viewModelScope,
        ioDispatcher = dispatchers.io,
    )

    val shelfPickerShelves: StateFlow<List<ShelfWithSummary>> get() = shelfController.shelves
    val inspectedShelfIds: StateFlow<Set<String>> get() = shelfController.inspectedShelfIds

    private val supportsLanguage: Boolean =
        plugin?.descriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true

    private val languageFlow = preferencesRepository.settings
        .map { if (supportsLanguage && siteId != null) it.languageFor(siteId) else ComicLanguage.ALL }
        .distinctUntilChanged()

    private val isSubscribedFlow = if (canSubscribe && querySlug != null && siteId != null) {
        subscriptionRepository.observeIsSubscribed(siteId, querySlug, scope)
    } else {
        flowOf(false)
    }

    private val cachedTagsFlow = siteId?.let { mediaTagCacheRepository?.observeTagsBySite(it) } ?: flowOf(emptyMap())
    private val dictRevisionFlow = tagDictionaryRepository?.observeRevision() ?: flowOf(0L)

    private val localDataFlow = combine(
        combine(
            siteId?.let { blockedTermsRepository.observeEffectiveTermsBySite(it) } ?: flowOf(emptyList()),
            cachedTagsFlow,
            siteId?.let { historyRepository.observeWatchProgressBySite(it) } ?: flowOf(emptyList()),
            siteId?.let { historyRepository.observeReadingProgressBySite(it) } ?: flowOf(emptyList()),
            shelfRepository.observeShelfItemKeys(Shelf.WATCH_LATER_ID),
        ) { blocked, cachedTags, watches, readings, watchLater ->
            SiteFeedLocalData(
                blockedTerms = blocked,
                cachedTagsByKey = cachedTags,
                watchProgressMap = watches.associate { it.mediaId to it.progressFraction },
                readingProgressMap = readings.associate { it.mediaId to it.progressFraction },
                watchLaterKeys = watchLater,
                isSubscribed = false,
            )
        },
        isSubscribedFlow,
        dictRevisionFlow,
    ) { base, subscribed, rev ->
        base.copy(isSubscribed = subscribed, dictRevision = rev)
    }

    val uiState: StateFlow<SiteFeedUiState> = combine(
        preferencesRepository.settings,
        selectedSort,
        localDataFlow,
        networkState,
    ) { settings, sort, local, net ->
        val descriptor = plugin?.descriptor
        val effectiveLanguage = if (supportsLanguage && siteId != null) settings.languageFor(siteId) else ComicLanguage.ALL
        val baseRequest = buildRequest(sort = sort, language = effectiveLanguage, page = 1, cursor = null)
        val availableSorts = plugin?.feedSource?.supportedSorts(baseRequest)
            ?.takeIf { it.isNotEmpty() }
            ?: descriptor?.feedSorts
            ?: emptyList()

        val matchedSection = descriptor?.sections?.firstOrNull { spec ->
            (!route.section.isNullOrBlank() && spec.id == route.section) ||
                (!route.category.isNullOrBlank() && spec.category == route.category)
        }
        val effectiveKind = matchedSection?.mediaKind ?: descriptor?.primaryKind ?: MediaKind.VIDEO

        val filteredItems = net.rawItems
            .filter { item ->
                ContentFilter.isAllowed(
                    item = item,
                    blockedTerms = local.blockedTerms,
                    tagDictionary = tagDictionary,
                    extraTags = local.cachedTagsByKey[item.compositeKey].orEmpty(),
                )
            }
            .withLocalizedTags(tagDictionary, settings.translateTags)

        val resolvedTitle = resolveDisplayTitle(
            explicitTitle = route.title,
            serverTitle = net.serverTitle,
            sectionTitle = matchedSection?.title,
            translateTags = settings.translateTags,
            siteName = descriptor?.displayName ?: siteId?.value ?: route.siteId,
        )
        val resolvedSubtitle = buildList {
            descriptor?.shortName?.let { add(it) }
            if (querySlug != null && scope != SearchScope.ALL) {
                add(scope.displayLabel())
            }
        }.joinToString(" · ").ifBlank { null }

        SiteFeedUiState(
            site = descriptor,
            title = resolvedTitle,
            subtitle = resolvedSubtitle,
            selectedSort = sort,
            availableSorts = availableSorts,
            comicLanguage = effectiveLanguage,
            supportsLanguageFilter = supportsLanguage,
            canSubscribe = canSubscribe,
            isSubscribed = local.isSubscribed,
            effectiveMediaKind = effectiveKind,
            items = filteredItems,
            watchProgressMap = local.watchProgressMap,
            readingProgressMap = local.readingProgressMap,
            watchLaterKeys = local.watchLaterKeys,
            isLoading = net.isLoading,
            isRefreshing = net.isRefreshing,
            isLoadingMore = net.isLoadingMore,
            authGateRequired = net.authGateRequired,
            errorMessage = net.errorMessage,
            paginationErrorMessage = net.paginationErrorMessage,
            hasMore = net.hasMore,
            currentPage = net.currentPage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SiteFeedUiState(
            site = plugin?.descriptor,
            title = route.title ?: plugin?.descriptor?.displayName ?: route.siteId,
            isLoading = true,
        ),
    )

    init {
        val targetSiteId = siteId
        val targetSection = route.section?.trim()?.takeIf { it.isNotEmpty() && !it.equals("chapters", ignoreCase = true) }
        if (targetSiteId != null && targetSection != null) {
            viewModelScope.launch {
                preferencesRepository.recordSectionOpen(targetSiteId, targetSection)
            }
        }
        viewModelScope.launch {
            combine(selectedSort, languageFlow) { sort, lang -> sort to lang }
                .distinctUntilChanged()
                .collect { (sort, lang) ->
                    triggerLoad(sort = sort, language = lang, isRefresh = false)
                }
        }
    }

    fun selectSort(sort: FeedSort) {
        selectedSort.value = sort
    }

    fun selectLanguage(language: ComicLanguage) {
        val targetId = siteId ?: return
        viewModelScope.launch {
            preferencesRepository.setSiteLanguage(targetId, language)
        }
    }

    fun toggleSubscription() {
        val targetId = siteId ?: return
        val slug = querySlug ?: return
        if (!canSubscribe) return
        val label = route.title?.takeIf { it.isNotBlank() }
            ?: tagDictionary.displayLabel(slug, slug)
        val fallbackUrl = plugin?.descriptor?.baseUrl.orEmpty()
        viewModelScope.launch {
            subscriptionRepository.toggleSubscription(
                TagSubscription(
                    siteId = targetId,
                    slug = slug,
                    label = label,
                    url = fallbackUrl,
                    scope = scope,
                ),
            )
        }
    }

    fun refresh() {
        val targetId = siteId ?: return
        viewModelScope.launch {
            val lang = if (supportsLanguage) {
                preferencesRepository.currentSettings().languageFor(targetId)
            } else {
                ComicLanguage.ALL
            }
            triggerLoad(
                sort = selectedSort.value,
                language = lang,
                isRefresh = networkState.value.rawItems.isNotEmpty(),
            )
        }
    }

    fun loadNextPage() {
        val snap = networkState.value
        if (snap.isLoading || snap.isLoadingMore || snap.authGateRequired || !snap.hasMore) return
        val targetId = siteId ?: return
        val targetPlugin = plugin ?: return

        paginateJob?.cancel()
        paginateJob = viewModelScope.launch {
            networkState.update { it.copy(isLoadingMore = true, paginationErrorMessage = null) }
            val nextPageNumber = snap.nextPage ?: (snap.currentPage + 1)
            val lang = if (supportsLanguage) {
                preferencesRepository.currentSettings().languageFor(targetId)
            } else {
                ComicLanguage.ALL
            }
            val request = buildRequest(
                sort = selectedSort.value,
                language = lang,
                page = nextPageNumber,
                cursor = snap.nextCursor,
            )
            try {
                val page = withContext(dispatchers.io) { executeFeedOrSearch(targetPlugin, request) }
                networkState.update { state ->
                    val merged = mergeUniqueMediaItems(state.rawItems, page.items)
                    val progressed = merged.size > state.rawItems.size ||
                        (page.nextCursor != null && page.nextCursor != state.nextCursor)
                    state.copy(
                        isLoadingMore = false,
                        paginationErrorMessage = null,
                        rawItems = merged,
                        currentPage = nextPageNumber,
                        nextPage = page.nextPage,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore && progressed,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                networkState.update { state ->
                    state.copy(
                        isLoadingMore = false,
                        paginationErrorMessage = error.toBrowseErrorMessage(),
                    )
                }
            }
        }
    }

    fun inspectShelfTarget(item: MediaItem?) = shelfController.inspectItem(item)

    fun toggleWatchLater(item: MediaItem) = shelfController.toggleWatchLater(item)

    fun setShelfMembership(shelfId: String, item: MediaItem, add: Boolean) =
        shelfController.setMembership(shelfId, item, add)

    fun createShelfAndAdd(name: String, item: MediaItem) =
        shelfController.createShelfAndAdd(name, item)

    fun saveBrowsePosition(workId: String, throughPage: Int = uiState.value.currentPage) {
        val targetId = siteId ?: return
        val cleanId = workId.trim()
        if (cleanId.isEmpty()) return
        val key = "feed:${targetId.value}:${route.section.orEmpty()}:${route.category.orEmpty()}:${querySlug.orEmpty()}"
        browsePositionJob?.cancel()
        browsePositionJob = viewModelScope.launch {
            delay(BROWSE_POSITION_DEBOUNCE_MS)
            browsePositionRepository.save(
                BrowsePosition(
                    positionKey = key,
                    siteId = targetId,
                    workId = cleanId,
                    throughPage = throughPage.coerceAtLeast(1),
                    updatedAt = 0L,
                ),
            )
        }
    }

    private fun triggerLoad(sort: FeedSort, language: ComicLanguage, isRefresh: Boolean) {
        loadJob?.cancel()
        paginateJob?.cancel()
        loadJob = viewModelScope.launch {
            val targetPlugin = plugin
            if (targetPlugin == null || (targetPlugin.feedSource == null && targetPlugin.searchSource == null)) {
                networkState.value = SiteFeedNetworkState(
                    isLoading = false,
                    errorMessage = "未找到对应站点或该站点不支持列表浏览",
                )
                return@launch
            }

            networkState.update { current ->
                if (isRefresh && current.rawItems.isNotEmpty()) {
                    current.copy(isRefreshing = true, isLoading = false, errorMessage = null)
                } else {
                    SiteFeedNetworkState(isLoading = true)
                }
            }

            try {
                if (targetPlugin.authProvider.requiresAuthGate) {
                    val auth = withContext(dispatchers.io) { targetPlugin.authProvider.currentAuthState() }
                    if (!auth.isAuthenticated) {
                        networkState.value = SiteFeedNetworkState(
                            isLoading = false,
                            authGateRequired = true,
                        )
                        return@launch
                    }
                }

                val request = buildRequest(sort = sort, language = language, page = 1, cursor = null)
                val page = withContext(dispatchers.io) { executeFeedOrSearch(targetPlugin, request) }
                networkState.value = SiteFeedNetworkState(
                    isLoading = false,
                    isRefreshing = false,
                    authGateRequired = false,
                    rawItems = page.items.distinctBy { it.compositeKey },
                    serverTitle = page.title,
                    currentPage = 1,
                    nextPage = page.nextPage,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                val isAuth = error is SiteException.AuthRequired
                networkState.update { current ->
                    if (isRefresh && current.rawItems.isNotEmpty() && !isAuth) {
                        current.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = error.toBrowseErrorMessage(),
                        )
                    } else {
                        SiteFeedNetworkState(
                            isLoading = false,
                            isRefreshing = false,
                            authGateRequired = isAuth,
                            errorMessage = error.toBrowseErrorMessage(),
                        )
                    }
                }
            }
        }
    }

    private suspend fun executeFeedOrSearch(targetPlugin: SitePlugin, request: FeedRequest) =
        if (!request.query.isNullOrBlank() && targetPlugin.searchSource != null) {
            requireNotNull(targetPlugin.searchSource).search(request)
        } else if (targetPlugin.feedSource != null) {
            requireNotNull(targetPlugin.feedSource).loadFeed(request)
        } else {
            requireNotNull(targetPlugin.searchSource).search(request)
        }

    private fun buildRequest(
        sort: FeedSort,
        language: ComicLanguage,
        page: Int,
        cursor: String?,
    ): FeedRequest {
        val matchedSection = plugin?.descriptor?.sections?.firstOrNull { spec ->
            (!route.section.isNullOrBlank() && spec.id == route.section) ||
                (!route.category.isNullOrBlank() && spec.category == route.category)
        }
        return FeedRequest(
            siteId = siteId ?: SiteId.of("unknown"),
            section = route.section,
            category = route.category,
            sort = sort,
            language = language,
            page = page,
            cursor = cursor,
            query = querySlug,
            scope = scope,
            mediaKind = matchedSection?.mediaKind,
        )
    }

    private fun resolveDisplayTitle(
        explicitTitle: String?,
        serverTitle: String?,
        sectionTitle: String?,
        translateTags: Boolean,
        siteName: String,
    ): String {
        if (!explicitTitle.isNullOrBlank()) {
            return if (translateTags && scope == SearchScope.TAG) {
                tagDictionary.displayLabel(explicitTitle, explicitTitle)
            } else {
                explicitTitle
            }
        }
        if (!serverTitle.isNullOrBlank()) return serverTitle
        if (!sectionTitle.isNullOrBlank()) return sectionTitle
        if (querySlug != null) {
            val label = if (translateTags && scope == SearchScope.TAG) {
                tagDictionary.displayLabel(querySlug, querySlug)
            } else {
                querySlug
            }
            return "${scope.displayLabel()}：$label"
        }
        if (!route.category.isNullOrBlank()) return route.category.orEmpty()
        return siteName
    }

    private data class SiteFeedLocalData(
        val blockedTerms: List<String>,
        val cachedTagsByKey: Map<String, List<TagRef>>,
        val watchProgressMap: Map<String, Float>,
        val readingProgressMap: Map<String, Float>,
        val watchLaterKeys: Set<String>,
        val isSubscribed: Boolean,
        val dictRevision: Long = 0L,
    )
}

@Composable
fun SiteFeedScreen(
    route: RavenRoute.SiteFeed,
    navigator: BrowseNavigator,
    modifier: Modifier = Modifier,
    viewModel: SiteFeedViewModel = koinViewModel(
        key = "site_feed_${route.siteId}_${route.section}_${route.category}_${route.query}_${route.scope}",
        parameters = { parametersOf(route) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shelves by viewModel.shelfPickerShelves.collectAsStateWithLifecycle()
    val inspectedShelfIds by viewModel.inspectedShelfIds.collectAsStateWithLifecycle()

    SiteFeedScreenContent(
        uiState = uiState,
        plugin = viewModel.plugin,
        navigator = navigator,
        onSelectSort = viewModel::selectSort,
        onSelectLanguage = viewModel::selectLanguage,
        onToggleSubscribe = viewModel::toggleSubscription,
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
fun SiteFeedScreenContent(
    uiState: SiteFeedUiState,
    plugin: SitePlugin?,
    navigator: BrowseNavigator,
    onSelectSort: (FeedSort) -> Unit,
    onSelectLanguage: (ComicLanguage) -> Unit,
    onToggleSubscribe: () -> Unit,
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
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var actionTargetItem by remember { mutableStateOf<MediaItem?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("browse_site_feed_screen"),
    ) {
        RavenTopBar(
            title = uiState.title,
            subtitle = uiState.subtitle,
            showBottomHairline = !uiState.supportsLanguageFilter && uiState.availableSorts.size <= 1,
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("site_feed_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
            actions = {
                if (uiState.canSubscribe) {
                    IconButton(
                        onClick = onToggleSubscribe,
                        modifier = Modifier.testTag("site_feed_subscribe_button"),
                    ) {
                        Icon(
                            imageVector = if (uiState.isSubscribed) {
                                Icons.Outlined.NotificationsActive
                            } else {
                                Icons.Outlined.NotificationsNone
                            },
                            contentDescription = if (uiState.isSubscribed) "取消订阅" else "订阅此标签",
                            tint = if (uiState.isSubscribed) colors.crimson else colors.inkPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                if (uiState.availableSorts.size > 1) {
                    IconButton(
                        onClick = { showSortSheet = true },
                        modifier = Modifier.testTag("site_feed_sort_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = "排序",
                            tint = if (uiState.selectedSort != FeedSort.DEFAULT) colors.crimson else colors.inkPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            },
        )

        if (uiState.supportsLanguageFilter) {
            RavenLanguageBar(
                selected = uiState.comicLanguage,
                onSelected = onSelectLanguage,
                showBottomHairline = uiState.availableSorts.size <= 1,
            )
        }

        if (uiState.availableSorts.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                RavenFilterChipRow(
                    items = uiState.availableSorts,
                    selectedItem = if (uiState.selectedSort == FeedSort.DEFAULT) {
                        uiState.availableSorts.firstOrNull()
                    } else {
                        uiState.selectedSort
                    },
                    labelProvider = { it.displayLabel() },
                    onItemSelected = onSelectSort,
                    keyProvider = { it.name },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                )
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
            val site = uiState.site
            val feedStage = when {
                site == null -> "no_site"
                uiState.authGateRequired -> "auth"
                uiState.isLoading && uiState.items.isEmpty() -> "loading"
                uiState.errorMessage != null && uiState.items.isEmpty() -> "error"
                uiState.items.isEmpty() -> "empty"
                uiState.effectiveMediaKind == MediaKind.VIDEO -> "video"
                else -> "gallery"
            }
            RavenContentCrossfade(targetState = feedStage) { stage ->
                when (stage) {
                    "no_site" -> {
                        RavenEmptyState(
                            title = "站点不可用",
                            message = "未找到对应站点插件。",
                        )
                    }
                    "auth" -> {
                        val activeSite = site ?: return@RavenContentCrossfade
                        BrowseAuthGateCard(
                            site = activeSite,
                            detailMessage = uiState.errorMessage,
                            onOpenAccount = { navigator.openSiteAccount(activeSite.id) },
                            onRefreshStatus = onRefresh,
                        )
                    }
                    "loading" -> {
                        ExploreLoadingGrid(isVideo = uiState.effectiveMediaKind == MediaKind.VIDEO)
                    }
                    "error" -> {
                        RavenErrorState(
                            message = uiState.errorMessage ?: "加载失败，请重试",
                            onRetry = onRefresh,
                        )
                    }
                    "empty" -> {
                        RavenEmptyState(
                            title = "暂无列表内容",
                            message = "当前分类或标签下没有匹配作品。",
                            actionLabel = "重新加载",
                            onActionClick = onRefresh,
                        )
                    }
                    "video" -> {
                        ExploreVideoGrid(
                            items = uiState.items,
                            watchProgressMap = uiState.watchProgressMap,
                            isLoadingMore = uiState.isLoadingMore,
                            paginationError = uiState.paginationErrorMessage,
                            hasMore = uiState.hasMore,
                            currentPage = uiState.currentPage,
                            onItemClick = { navigator.openSearchResultItem(plugin, it) },
                            onItemLongClick = { actionTargetItem = it },
                            onLoadNextPage = onLoadNextPage,
                            onSaveBrowsePosition = onSaveBrowsePosition,
                        )
                    }
                    else -> {
                        val activeSite = site ?: return@RavenContentCrossfade
                        ExploreGalleryGrid(
                            siteId = activeSite.id,
                            items = uiState.items,
                            readingProgressMap = uiState.readingProgressMap,
                            isLoadingMore = uiState.isLoadingMore,
                            paginationError = uiState.paginationErrorMessage,
                            hasMore = uiState.hasMore,
                            currentPage = uiState.currentPage,
                            onItemClick = { navigator.openSearchResultItem(plugin, it) },
                            onItemLongClick = { actionTargetItem = it },
                            onLoadNextPage = onLoadNextPage,
                            onSaveBrowsePosition = onSaveBrowsePosition,
                        )
                    }
                }
            }
        }
    }

    if (showSortSheet) {
        RavenBottomSheet(
            onDismissRequest = { showSortSheet = false },
            title = "排序方式",
        ) {
            RavenGroupedSection {
                uiState.availableSorts.forEachIndexed { idx, sort ->
                    if (idx > 0) RavenGroupedDivider()
                    val selected = uiState.selectedSort == sort ||
                        (uiState.selectedSort == FeedSort.DEFAULT && idx == 0)
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
                            showSortSheet = false
                        },
                    )
                }
            }
        }
    }

    MediaCardActionAndShelfHost(
        targetItem = actionTargetItem,
        watchLaterKeys = uiState.watchLaterKeys,
        shelves = shelves,
        selectedShelfIds = selectedShelfIds,
        onInspectShelfTarget = onInspectShelfTarget,
        onDismiss = { actionTargetItem = null },
        onOpen = { navigator.openSearchResultItem(plugin, it) },
        onToggleWatchLater = onToggleWatchLater,
        onToggleShelfMembership = onToggleShelfMembership,
        onCreateShelfAndAdd = onCreateShelfAndAdd,
        onSearchSimilar = { slug -> navigator.openSearch(initialQuery = slug, crossSite = false) },
    )
}
