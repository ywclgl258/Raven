package com.raven.app.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.MediaDateParser
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.common.TagSuggestion
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SearchHistoryRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class SearchMode {
    SINGLE_SITE,
    CROSS_SITE,
}

enum class CrossSiteStatus {
    IDLE,
    LOADING,
    SUCCESS,
    EMPTY,
    ERROR,
    AUTH_REQUIRED,
}

data class SearchTrendingTag(
    val slug: String,
    val localizedName: String,
    val count: Int,
    val rank: Int,
)

data class CrossSiteBucketState(
    val site: SiteDescriptor,
    val status: CrossSiteStatus = CrossSiteStatus.IDLE,
    val isDirectorySearch: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val currentPage: Int = 1,
    val nextPage: Int? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
)

data class SearchUiState(
    val mode: SearchMode = SearchMode.SINGLE_SITE,
    val activeSite: SiteDescriptor? = null,
    val allSites: List<SiteDescriptor> = emptyList(),
    val searchableSites: List<SiteDescriptor> = emptyList(),
    val pinnedSiteIds: List<SiteId> = emptyList(),
    val query: String = "",
    val submittedQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val supportedScopes: List<SearchScope> = listOf(SearchScope.ALL),
    val supportedFilters: Set<AdvancedFilterField> = emptySet(),
    val supportsExcludeTags: Boolean = false,
    val supportsLanguageFilter: Boolean = false,
    val sort: FeedSort = FeedSort.RELEVANCE,
    val availableSorts: List<FeedSort> = listOf(FeedSort.RELEVANCE),
    val nativeSorts: List<FeedSort> = listOf(FeedSort.RELEVANCE),
    val localSorts: List<FeedSort> = emptyList(),
    val isLocalSortActive: Boolean = false,
    val comicLanguage: ComicLanguage = ComicLanguage.ALL,
    val advancedFilters: AdvancedSearchFilters = AdvancedSearchFilters(),
    val tagSuggestions: List<TagSuggestion> = emptyList(),
    val trendingTags: List<SearchTrendingTag> = emptyList(),
    val hotTagRanking: List<SearchTrendingTag> = emptyList(),
    val hotWorksRanking: List<MediaItem> = emptyList(),
    val isLoadingDiscovery: Boolean = false,
    val searchHistory: List<SearchHistoryEntry> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    // Single-site results
    val singleSiteItems: List<MediaItem> = emptyList(),
    val singleSiteIsDirectory: Boolean = false,
    val singleSiteLoading: Boolean = false,
    val singleSiteLoadingMore: Boolean = false,
    val singleSiteHasMore: Boolean = false,
    val singleSiteAuthRequired: Boolean = false,
    val singleSiteError: String? = null,
    val singleSitePaginationError: String? = null,
    val hasSearchedSingleSite: Boolean = false,
    // Cross-site search state
    val selectedCrossSiteIds: List<SiteId> = emptyList(),
    val crossSiteBuckets: List<CrossSiteBucketState> = emptyList(),
    val crossSiteMediaItems: List<MediaItem> = emptyList(),
    val crossSiteDirectoryBuckets: List<CrossSiteBucketState> = emptyList(),
    val crossSiteFilterSiteId: SiteId? = null,
    val hasSearchedCrossSite: Boolean = false,
    // Auxiliary progress / shelf lookups
    val watchProgressMap: Map<String, Float> = emptyMap(),
    val readingProgressMap: Map<String, Float> = emptyMap(),
    val watchLaterKeys: Set<String> = emptySet(),
    val lastSavedSearchNotice: String? = null,
) {
    val activeAdvancedFilterCount: Int
        get() = advancedFilters.activeFilterCount(
            language = if (supportsLanguageFilter) comicLanguage else ComicLanguage.ALL,
        )

    val hasAdvancedFilterSupport: Boolean
        get() = supportedFilters.isNotEmpty() || supportsExcludeTags || supportsLanguageFilter

    val isCrossSiteSearching: Boolean
        get() = crossSiteBuckets.any { it.status == CrossSiteStatus.LOADING }

    val canSaveCurrentSearch: Boolean
        get() = query.isNotBlank() || !advancedFilters.isEmpty
}

private data class SearchInputState(
    val mode: SearchMode = SearchMode.SINGLE_SITE,
    val query: String = "",
    val submittedQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val sort: FeedSort = FeedSort.DEFAULT,
    val languageOverride: ComicLanguage? = null,
    val advancedFilters: AdvancedSearchFilters = AdvancedSearchFilters(),
    val crossSiteFilterSiteId: SiteId? = null,
    val lastSavedNotice: String? = null,
)

private data class SingleSiteResultState(
    val siteId: SiteId? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasSearched: Boolean = false,
    val authRequired: Boolean = false,
    val errorMessage: String? = null,
    val paginationErrorMessage: String? = null,
    val rawItems: List<MediaItem> = emptyList(),
    val fetchedSort: FeedSort? = null,
    val isDirectoryResults: Boolean = false,
    val currentPage: Int = 1,
    val nextPage: Int? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

private data class CrossSiteResultState(
    val hasSearched: Boolean = false,
    val fetchedSort: FeedSort? = null,
    val bucketsBySite: Map<SiteId, CrossSiteBucketState> = emptyMap(),
)

private data class SearchTopDbSlice(
    val history: List<SearchHistoryEntry>,
    val savedSearches: List<SavedSearch>,
    val blockedTermsBySite: Map<SiteId, List<String>>,
    val activeSiteEffectiveBlocked: List<String>,
)

private data class SearchBottomDbSlice(
    val watchProgressMap: Map<String, Float>,
    val readingProgressMap: Map<String, Float>,
    val watchLaterKeys: Set<String>,
    val cachedTagsByKey: Map<String, List<TagRef>>,
    val dictRevision: Long,
)

private data class SearchDbContext(
    val history: List<SearchHistoryEntry>,
    val savedSearches: List<SavedSearch>,
    val blockedTermsBySite: Map<SiteId, List<String>>,
    val activeSiteEffectiveBlocked: List<String>,
    val cachedTagsByKey: Map<String, List<TagRef>>,
    val watchProgressMap: Map<String, Float>,
    val readingProgressMap: Map<String, Float>,
    val watchLaterKeys: Set<String>,
    val dictRevision: Long = 0L,
)

private data class SiteDiscoverySnapshot(
    val works: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val fetchedAtMs: Long = 0L,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    initialRoute: RavenRoute.Search = RavenRoute.Search(),
    private val siteRegistry: SiteRegistry,
    private val preferencesRepository: PreferencesRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val historyRepository: HistoryRepository,
    private val shelfRepository: ShelfRepository,
    private val dispatchers: AppDispatchers,
    private val tagDictionary: TagDictionary = TagDictionary.default,
    private val mediaTagCacheRepository: MediaTagCacheRepository? = null,
    private val tagDictionaryRepository: TagDictionaryRepository? = null,
) : ViewModel() {

    private val allDescriptors: List<SiteDescriptor> = siteRegistry.allDescriptors()
    private val searchablePlugins: List<SitePlugin> = siteRegistry.allPlugins().filter { it.searchSource != null }
    private val searchableDescriptors: List<SiteDescriptor> = searchablePlugins.map { it.descriptor }
    private val searchableIdSet: Set<SiteId> = searchableDescriptors.mapTo(HashSet()) { it.id }

    private val crossSearchSemaphore = Semaphore(permits = MAX_CONCURRENT_CROSS_SITE_SEARCHES)
    private val crossSitesMutex = Mutex()

    private val inputState = MutableStateFlow(
        SearchInputState(
            mode = if (initialRoute.crossSite) SearchMode.CROSS_SITE else SearchMode.SINGLE_SITE,
            query = initialRoute.initialQuery?.trim().orEmpty(),
        ),
    )
    private val singleSiteState = MutableStateFlow(SingleSiteResultState())
    private val crossSiteState = MutableStateFlow(CrossSiteResultState())

    private var singleSearchJob: Job? = null
    private var singlePaginateJob: Job? = null
    private var crossSearchJob: Job? = null
    private val crossPerSiteJobs = mutableMapOf<SiteId, Job>()
    private val discoveryCache = ConcurrentHashMap<SiteId, SiteDiscoverySnapshot>()
    private val shelfController = BrowseShelfController(
        shelfRepository = shelfRepository,
        scope = viewModelScope,
        ioDispatcher = dispatchers.io,
    )

    val shelfPickerShelves: StateFlow<List<ShelfWithSummary>> get() = shelfController.shelves
    val inspectedShelfIds: StateFlow<Set<String>> get() = shelfController.inspectedShelfIds

    private val activeSiteIdFlow = preferencesRepository.settings
        .map { resolveActiveSiteId(it.activeSiteId) }
        .distinctUntilChanged()

    private val cachedTagsFlow = mediaTagCacheRepository?.observeAllTags() ?: flowOf(emptyMap())
    private val dictRevisionFlow = tagDictionaryRepository?.observeRevision() ?: flowOf(0L)

    private val discoveryFlow = activeSiteIdFlow.flatMapLatest { siteId ->
        flow {
            val now = System.currentTimeMillis()
            val cached = discoveryCache[siteId]
            if (cached != null && (now - cached.fetchedAtMs) < DISCOVERY_CACHE_TTL_MS) {
                emit(cached)
                return@flow
            }
            val plugin = siteRegistry.get(siteId)
            val canFetch = plugin?.feedSource != null
            if (!canFetch) {
                emit(SiteDiscoverySnapshot(works = emptyList(), isLoading = false, fetchedAtMs = now))
                return@flow
            }
            emit(SiteDiscoverySnapshot(works = cached?.works.orEmpty(), isLoading = true, fetchedAtMs = 0L))
            val fetched = withContext(dispatchers.io) {
                fetchDiscoveryWorks(plugin)
            }
            val snapshot = SiteDiscoverySnapshot(
                works = fetched,
                isLoading = false,
                fetchedAtMs = System.currentTimeMillis(),
            )
            if (fetched.isNotEmpty()) {
                discoveryCache[siteId] = snapshot
            }
            emit(snapshot)
        }
    }

    private val dbContextFlow = activeSiteIdFlow.flatMapLatest { siteId ->
        val topFour = combine(
            searchHistoryRepository.observeBySite(siteId, limit = MAX_HISTORY_CHIPS),
            subscriptionRepository.observeSavedSearches(),
            blockedTermsRepository.observeAllGrouped(),
            blockedTermsRepository.observeEffectiveTermsBySite(siteId),
        ) { history, saved, groupedBlocked, activeEffectiveBlocked ->
            SearchTopDbSlice(
                history = history,
                savedSearches = saved,
                blockedTermsBySite = groupedBlocked,
                activeSiteEffectiveBlocked = activeEffectiveBlocked,
            )
        }

        val bottomFour = combine(
            historyRepository.observeAllWatchProgress(),
            historyRepository.observeAllReadingProgress(),
            shelfRepository.observeShelfItemKeys(Shelf.WATCH_LATER_ID),
            cachedTagsFlow,
            dictRevisionFlow,
        ) { watches, readings, watchLater, cachedTags, dictRevision ->
            SearchBottomDbSlice(
                watchProgressMap = watches.associate { it.compositeKey to it.progressFraction },
                readingProgressMap = readings.associate { it.compositeKey to it.progressFraction },
                watchLaterKeys = watchLater,
                cachedTagsByKey = cachedTags,
                dictRevision = dictRevision,
            )
        }

        combine(topFour, bottomFour) { left, right ->
            SearchDbContext(
                history = left.history,
                savedSearches = left.savedSearches,
                blockedTermsBySite = left.blockedTermsBySite,
                activeSiteEffectiveBlocked = left.activeSiteEffectiveBlocked,
                cachedTagsByKey = right.cachedTagsByKey,
                watchProgressMap = right.watchProgressMap,
                readingProgressMap = right.readingProgressMap,
                watchLaterKeys = right.watchLaterKeys,
                dictRevision = right.dictRevision,
            )
        }
    }

    val uiState: StateFlow<SearchUiState> = combine(
        preferencesRepository.settings,
        combine(inputState, discoveryFlow) { input, discovery -> input to discovery },
        singleSiteState,
        crossSiteState,
        dbContextFlow,
    ) { settings, inputAndDiscovery, single, cross, db ->
        val (input, discovery) = inputAndDiscovery
        val activeSiteId = resolveActiveSiteId(settings.activeSiteId)
        val activePlugin = siteRegistry.get(activeSiteId)
        val activeDescriptor = activePlugin?.descriptor
        val searchSource = activePlugin?.searchSource
        val translateTags = settings.translateTags

        val supportedScopes = SearchScope.entries.filter { scope ->
            val supported = searchSource?.supportedScopes ?: setOf(SearchScope.ALL)
            scope in supported
        }.ifEmpty { listOf(SearchScope.ALL) }
        val effectiveScope = if (input.scope in supportedScopes) input.scope else supportedScopes.first()

        val supportedFilters = searchSource?.supportedFilters ?: emptySet()
        val supportsExcludeTags = searchSource?.supportsExcludeTags == true ||
            AdvancedFilterField.EXCLUDE_TAGS in supportedFilters
        val supportsLang = activeDescriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true ||
            AdvancedFilterField.LANGUAGE in supportedFilters
        val effectiveLanguage = if (supportsLang) {
            input.languageOverride ?: settings.languageFor(activeSiteId)
        } else {
            ComicLanguage.ALL
        }

        val singleNativeSorts = resolveNativeSearchSorts(activePlugin, effectiveScope)
        val singleLocalSorts = buildLocalFallbackSorts(
            primaryKind = activeDescriptor?.primaryKind,
            nativeSorts = singleNativeSorts,
        )
        val nativeSorts = when (input.mode) {
            SearchMode.SINGLE_SITE -> singleNativeSorts
            SearchMode.CROSS_SITE -> CROSS_SITE_SORTS
        }
        val localSorts = when (input.mode) {
            SearchMode.SINGLE_SITE -> singleLocalSorts
            SearchMode.CROSS_SITE -> emptyList()
        }
        val availableSorts = nativeSorts + localSorts
        val effectiveSort = when {
            input.sort == FeedSort.DEFAULT -> nativeSorts.first()
            input.sort in availableSorts -> input.sort
            else -> nativeSorts.first()
        }
        val isLocalSortActive = input.mode == SearchMode.SINGLE_SITE && effectiveSort !in singleNativeSorts

        // TagDictionary live suggestions from active trailing query token
        val activeToken = extractLastQueryToken(input.query)
        val suggestions = if (activeToken.isNotEmpty()) {
            tagDictionary.suggest(activeToken, limit = 8)
        } else {
            emptyList()
        }

        val blockedBySite = db.blockedTermsBySite
        val globalBlocked = blockedBySite[SiteId.GLOBAL].orEmpty()
        val activeSiteBlocked = db.activeSiteEffectiveBlocked
        val cachedTags = db.cachedTagsByKey
        val activeFromDate = input.advancedFilters.publishedFrom
        val activeToDate = input.advancedFilters.publishedTo

        val singleAligned = single.siteId == activeSiteId
        val baseSingleItems = if (singleAligned) {
            single.rawItems
                .filter { item ->
                    ContentFilter.isAllowed(
                        item = item,
                        blockedTerms = activeSiteBlocked,
                        tagDictionary = tagDictionary,
                        extraTags = cachedTags[item.compositeKey].orEmpty(),
                    ) && MediaDateParser.matchesDateRange(
                        dateLabel = item.dateLabel,
                        publishedFrom = activeFromDate,
                        publishedTo = activeToDate,
                    )
                }
                .withLocalizedTags(tagDictionary, translateTags)
        } else {
            emptyList()
        }
        val filteredSingleItems = if (isLocalSortActive && !single.isDirectoryResults) {
            sortMediaItemsClientSide(baseSingleItems, effectiveSort)
        } else {
            baseSingleItems
        }

        // Selected cross-site sources: empty persisted list means ALL searchable sites
        val selectedCrossIds = settings.crossSearchSiteIds
            .filter { it in searchableIdSet }
            .ifEmpty { searchableDescriptors.map { it.id } }

        val orderedCrossBuckets = selectedCrossIds.mapNotNull { id ->
            val rawBucket = cross.bucketsBySite[id] ?: return@mapNotNull null
            val siteBlocked = (globalBlocked + blockedBySite[id].orEmpty()).distinct()
            val allowed = rawBucket.items
                .filter { item ->
                    ContentFilter.isAllowed(
                        item = item,
                        blockedTerms = siteBlocked,
                        tagDictionary = tagDictionary,
                        extraTags = cachedTags[item.compositeKey].orEmpty(),
                    ) && MediaDateParser.matchesDateRange(
                        dateLabel = item.dateLabel,
                        publishedFrom = activeFromDate,
                        publishedTo = activeToDate,
                    )
                }
                .withLocalizedTags(tagDictionary, translateTags)
            rawBucket.copy(items = allowed)
        }

        val hotWorks = discovery.works
            .filter { item ->
                ContentFilter.isAllowed(
                    item = item,
                    blockedTerms = activeSiteBlocked,
                    tagDictionary = tagDictionary,
                    extraTags = cachedTags[item.compositeKey].orEmpty(),
                )
            }
            .distinctBy { it.compositeKey }
            .take(MAX_HOT_WORKS_RANKING)
            .withLocalizedTags(tagDictionary, translateTags)

        val rankedTags = buildDiscoveryTagRankings(
            siteId = activeSiteId,
            works = hotWorks,
            cachedTagsByKey = cachedTags,
            blockedTerms = activeSiteBlocked,
            translateTags = translateTags,
        )

        val directoryBuckets = orderedCrossBuckets.filter { it.isDirectorySearch }
        val mediaBuckets = orderedCrossBuckets.filterNot { it.isDirectorySearch }
        val activeFilterSite = input.crossSiteFilterSiteId?.takeIf { filterId ->
            mediaBuckets.any { it.site.id == filterId }
        }
        val visibleMediaBuckets = if (activeFilterSite != null) {
            mediaBuckets.filter { it.site.id == activeFilterSite }
        } else {
            mediaBuckets
        }
        val interleavedMediaItems = interleaveBuckets(visibleMediaBuckets)
        val mixedMediaItems = if (
            input.mode == SearchMode.CROSS_SITE &&
            effectiveSort != FeedSort.RELEVANCE &&
            effectiveSort != FeedSort.DEFAULT
        ) {
            sortMediaItemsClientSide(interleavedMediaItems, effectiveSort)
        } else {
            interleavedMediaItems
        }

        SearchUiState(
            mode = input.mode,
            activeSite = activeDescriptor,
            allSites = allDescriptors,
            searchableSites = searchableDescriptors,
            pinnedSiteIds = settings.pinnedSiteIds,
            query = input.query,
            submittedQuery = input.submittedQuery,
            scope = effectiveScope,
            supportedScopes = supportedScopes,
            supportedFilters = supportedFilters,
            supportsExcludeTags = supportsExcludeTags,
            supportsLanguageFilter = supportsLang,
            sort = effectiveSort,
            availableSorts = availableSorts,
            nativeSorts = nativeSorts,
            localSorts = localSorts,
            isLocalSortActive = isLocalSortActive,
            comicLanguage = effectiveLanguage,
            advancedFilters = input.advancedFilters,
            tagSuggestions = suggestions,
            trendingTags = rankedTags.take(MAX_TRENDING_TAGS),
            hotTagRanking = rankedTags.take(MAX_HOT_TAG_RANKING),
            hotWorksRanking = hotWorks,
            isLoadingDiscovery = discovery.isLoading,
            searchHistory = db.history,
            savedSearches = db.savedSearches,
            singleSiteItems = filteredSingleItems,
            singleSiteIsDirectory = singleAligned && single.isDirectoryResults,
            singleSiteLoading = singleAligned && single.isLoading,
            singleSiteLoadingMore = singleAligned && single.isLoadingMore,
            singleSiteHasMore = singleAligned && single.hasMore,
            singleSiteAuthRequired = singleAligned && single.authRequired,
            singleSiteError = if (singleAligned) single.errorMessage else null,
            singleSitePaginationError = if (singleAligned) single.paginationErrorMessage else null,
            hasSearchedSingleSite = singleAligned && single.hasSearched,
            selectedCrossSiteIds = selectedCrossIds,
            crossSiteBuckets = orderedCrossBuckets,
            crossSiteMediaItems = mixedMediaItems,
            crossSiteDirectoryBuckets = directoryBuckets,
            crossSiteFilterSiteId = activeFilterSite,
            hasSearchedCrossSite = cross.hasSearched,
            watchProgressMap = db.watchProgressMap,
            readingProgressMap = db.readingProgressMap,
            watchLaterKeys = db.watchLaterKeys,
            lastSavedSearchNotice = input.lastSavedNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SearchUiState(
            mode = if (initialRoute.crossSite) SearchMode.CROSS_SITE else SearchMode.SINGLE_SITE,
            activeSite = allDescriptors.firstOrNull(),
            allSites = allDescriptors,
            searchableSites = searchableDescriptors,
            query = initialRoute.initialQuery?.trim().orEmpty(),
            selectedCrossSiteIds = searchableDescriptors.map { it.id },
        ),
    )

    init {
        val startQuery = initialRoute.initialQuery?.trim().orEmpty()
        if (startQuery.isNotEmpty()) {
            viewModelScope.launch {
                submitSearch()
            }
        }
    }

    fun getPlugin(siteId: SiteId): SitePlugin? = siteRegistry.get(siteId)

    fun setMode(mode: SearchMode) {
        if (inputState.value.mode == mode) return
        inputState.update { it.copy(mode = mode, crossSiteFilterSiteId = null) }
        val q = inputState.value.query.trim()
        if (q.isNotEmpty()) {
            submitSearch()
        }
    }

    fun selectActiveSite(siteId: SiteId) {
        if (siteRegistry.get(siteId) == null) return
        singleSearchJob?.cancel()
        singlePaginateJob?.cancel()
        singleSiteState.value = SingleSiteResultState(siteId = siteId)
        inputState.update {
            it.copy(
                scope = SearchScope.ALL,
                sort = FeedSort.DEFAULT,
                languageOverride = null,
                advancedFilters = AdvancedSearchFilters(),
            )
        }
        viewModelScope.launch {
            preferencesRepository.setActiveSiteId(siteId)
        }
    }

    fun togglePinnedSite(siteId: SiteId) {
        viewModelScope.launch {
            preferencesRepository.togglePinnedSite(siteId)
        }
    }

    fun updateQuery(newQuery: String) {
        inputState.update { it.copy(query = newQuery, lastSavedNotice = null) }
    }

    fun clearQuery() {
        singleSearchJob?.cancel()
        singlePaginateJob?.cancel()
        crossSearchJob?.cancel()
        cancelAllCrossSiteJobs()
        inputState.update { it.copy(query = "", submittedQuery = "", crossSiteFilterSiteId = null) }
        singleSiteState.value = SingleSiteResultState(siteId = uiState.value.activeSite?.id)
        crossSiteState.value = CrossSiteResultState()
    }

    fun applyTagSuggestion(suggestion: TagSuggestion, autoSubmit: Boolean = false) {
        val nextQuery = applySuggestionToken(inputState.value.query, suggestion.slug)
        inputState.update { it.copy(query = nextQuery) }
        if (autoSubmit) {
            submitSearch()
        }
    }

    fun selectScope(scope: SearchScope) {
        if (inputState.value.scope == scope) return
        inputState.update { it.copy(scope = scope) }
        if (inputState.value.mode == SearchMode.SINGLE_SITE &&
            (inputState.value.query.isNotBlank() || !inputState.value.advancedFilters.isEmpty)
        ) {
            submitSearch()
        }
    }

    fun selectSort(sort: FeedSort) {
        val current = inputState.value
        if (current.sort == sort) return
        inputState.update { it.copy(sort = sort) }

        val hasQuery = current.query.isNotBlank()
        val hasFilters = !current.advancedFilters.isEmpty
        when (current.mode) {
            SearchMode.SINGLE_SITE -> {
                if (!hasQuery && !hasFilters) return
                val singleSnap = singleSiteState.value
                val plugin = singleSnap.siteId?.let { siteRegistry.get(it) }
                if (singleSnap.hasSearched && plugin != null) {
                    val nativeSorts = resolveNativeSearchSorts(plugin, current.scope)
                    val targetServerSort = resolveCompatibleServerSort(nativeSorts, sort)
                    if (singleSnap.fetchedSort == targetServerSort) {
                        return
                    }
                }
                submitSearch()
            }
            SearchMode.CROSS_SITE -> {
                if (!hasQuery) return
                if (crossSiteState.value.hasSearched) {
                    submitSearch()
                }
            }
        }
    }

    fun applyAdvancedFilters(
        filters: AdvancedSearchFilters,
        language: ComicLanguage,
        autoSubmit: Boolean = true,
    ) {
        inputState.update {
            it.copy(
                advancedFilters = filters,
                languageOverride = language,
            )
        }
        if (autoSubmit && inputState.value.mode == SearchMode.SINGLE_SITE) {
            submitSearch()
        }
    }

    fun clearAdvancedFilters(autoSubmit: Boolean = false) {
        inputState.update {
            it.copy(
                advancedFilters = AdvancedSearchFilters(),
                languageOverride = ComicLanguage.ALL,
            )
        }
        if (autoSubmit && inputState.value.query.isNotBlank()) {
            submitSearch()
        }
    }

    fun setDateRange(publishedFrom: String?, publishedTo: String?) {
        val cleanFrom = publishedFrom?.trim()?.takeIf { it.isNotEmpty() }
        val cleanTo = publishedTo?.trim()?.takeIf { it.isNotEmpty() }
        inputState.update { current ->
            current.copy(
                advancedFilters = current.advancedFilters.copy(
                    publishedFrom = cleanFrom,
                    publishedTo = cleanTo,
                ),
            )
        }
    }

    fun toggleCrossSearchSite(siteId: SiteId) {
        if (siteId !in searchableIdSet) return
        viewModelScope.launch {
            crossSitesMutex.withLock {
                val settings = preferencesRepository.currentSettings()
                val currentSelection = settings.crossSearchSiteIds
                    .filter { it in searchableIdSet }
                    .ifEmpty { searchableDescriptors.map { it.id } }
                val next = if (siteId in currentSelection) {
                    if (currentSelection.size <= 1) currentSelection else currentSelection - siteId
                } else {
                    currentSelection + siteId
                }
                val persisted = if (next.size == searchableDescriptors.size) emptyList() else next
                preferencesRepository.setCrossSearchSiteIds(persisted)
            }
        }
    }

    fun selectAllCrossSearchSites() {
        viewModelScope.launch {
            crossSitesMutex.withLock {
                preferencesRepository.setCrossSearchSiteIds(emptyList())
            }
        }
    }

    /**
     * 按大分类整体切换跨站搜索来源：
     * - 该分类已全部选中 → 从当前选择中移除（至少保留 1 个站点）；
     * - 否则 → 把该分类全部站点并入当前选择。
     * 选择集合等于全部可搜索站点时持久化为空列表（空列表语义即「全部」）。
     */
    fun toggleCrossSearchSitesByKind(kind: MediaKind) {
        val kindIds = searchableDescriptors.filter { it.primaryKind == kind }.map { it.id }
        if (kindIds.isEmpty()) return
        viewModelScope.launch {
            crossSitesMutex.withLock {
                val settings = preferencesRepository.currentSettings()
                val current = settings.crossSearchSiteIds
                    .filter { it in searchableIdSet }
                    .ifEmpty { searchableDescriptors.map { it.id } }
                val kindSet = kindIds.toSet()
                val next = if (current.toSet().containsAll(kindSet)) {
                    val remaining = current.filterNot { it in kindSet }
                    if (remaining.isEmpty()) current else remaining
                } else {
                    (current + kindIds).distinct()
                }
                preferencesRepository.setCrossSearchSiteIds(normalizeCrossSiteSelection(next))
            }
        }
    }

    /** 一次性应用来源选择（来源面板「应用」按钮）。空集合视为无效，保持原选择。 */
    fun applyCrossSearchSites(siteIds: Collection<SiteId>) {
        val cleaned = siteIds.distinct().filter { it in searchableIdSet }
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            crossSitesMutex.withLock {
                preferencesRepository.setCrossSearchSiteIds(normalizeCrossSiteSelection(cleaned))
            }
        }
    }

    private fun normalizeCrossSiteSelection(ids: List<SiteId>): List<SiteId> =
        if (ids.size == searchableDescriptors.size && ids.toSet() == searchableIdSet) {
            emptyList()
        } else {
            ids
        }

    fun toggleCrossSiteResultFilter(siteId: SiteId) {
        inputState.update { current ->
            val next = if (current.crossSiteFilterSiteId == siteId) null else siteId
            current.copy(crossSiteFilterSiteId = next)
        }
    }

    fun selectHistoryEntry(entry: SearchHistoryEntry) {
        inputState.update {
            it.copy(
                query = entry.query,
                scope = entry.scope,
            )
        }
        submitSearch()
    }

    fun removeHistoryEntry(entry: SearchHistoryEntry) {
        viewModelScope.launch {
            searchHistoryRepository.removeEntry(entry.siteId, entry.query, entry.scope)
        }
    }

    fun clearSiteHistory() {
        viewModelScope.launch {
            val siteId = resolveActiveSiteId(preferencesRepository.currentSettings().activeSiteId)
            searchHistoryRepository.clearSite(siteId)
        }
    }

    fun saveCurrentSearch(rawName: String) {
        val input = inputState.value
        viewModelScope.launch {
            val settings = preferencesRepository.currentSettings()
            val activeSiteId = resolveActiveSiteId(settings.activeSiteId)
            val activeSite = siteRegistry.get(activeSiteId)?.descriptor
            val selectedCross = settings.crossSearchSiteIds
                .filter { it in searchableIdSet }
                .ifEmpty { searchableDescriptors.map { it.id } }
            val targetSiteIds = when (input.mode) {
                SearchMode.SINGLE_SITE -> listOfNotNull(activeSite?.id)
                SearchMode.CROSS_SITE -> selectedCross
            }
            if (targetSiteIds.isEmpty()) return@launch
            val fallbackName = input.query.trim().ifBlank {
                activeSite?.shortName?.let { "$it 筛选搜索" } ?: "已保存搜索"
            }
            val cleanName = rawName.trim()
                .ifBlank { fallbackName }
                .take(SubscriptionRepository.MAX_SAVED_SEARCH_NAME_LENGTH)
            val effectiveSort = when {
                input.sort != FeedSort.DEFAULT -> input.sort
                input.mode == SearchMode.CROSS_SITE -> FeedSort.RELEVANCE
                else -> resolveNativeSearchSorts(siteRegistry.get(activeSiteId), input.scope).first()
            }
            val effectiveLanguage = input.languageOverride ?: settings.languageFor(activeSiteId)

            val saved = subscriptionRepository.saveSavedSearch(
                SavedSearch(
                    id = "",
                    name = cleanName,
                    query = input.query.trim(),
                    scope = input.scope,
                    siteIds = targetSiteIds,
                    sort = effectiveSort,
                    language = effectiveLanguage,
                    filters = input.advancedFilters,
                ),
            )
            inputState.update { it.copy(lastSavedNotice = "已保存搜索「${saved.name}」") }
        }
    }

    fun dismissSavedSearchNotice() {
        inputState.update { it.copy(lastSavedNotice = null) }
    }

    fun applySavedSearch(savedSearch: SavedSearch) {
        val validSites = savedSearch.siteIds.filter { it in searchableIdSet }
        val targetMode = if (validSites.size > 1) SearchMode.CROSS_SITE else SearchMode.SINGLE_SITE

        viewModelScope.launch {
            if (targetMode == SearchMode.SINGLE_SITE && validSites.size == 1) {
                preferencesRepository.setActiveSiteId(validSites.first())
            } else if (targetMode == SearchMode.CROSS_SITE) {
                preferencesRepository.setCrossSearchSiteIds(validSites)
            }
            inputState.update {
                it.copy(
                    mode = targetMode,
                    query = savedSearch.query,
                    scope = savedSearch.scope,
                    sort = savedSearch.sort,
                    languageOverride = savedSearch.language,
                    advancedFilters = savedSearch.filters,
                    crossSiteFilterSiteId = null,
                )
            }
            submitSearch(
                overrideMode = targetMode,
                overrideSingleSiteId = validSites.firstOrNull(),
                overrideCrossSiteIds = validSites.takeIf { targetMode == SearchMode.CROSS_SITE },
            )
        }
    }

    fun deleteSavedSearch(id: String) {
        viewModelScope.launch {
            subscriptionRepository.deleteSavedSearch(id)
        }
    }

    fun inspectShelfTarget(item: MediaItem?) = shelfController.inspectItem(item)

    fun toggleWatchLater(item: MediaItem) = shelfController.toggleWatchLater(item)

    fun setShelfMembership(shelfId: String, item: MediaItem, add: Boolean) =
        shelfController.setMembership(shelfId, item, add)

    fun createShelfAndAdd(name: String, item: MediaItem) =
        shelfController.createShelfAndAdd(name, item)

    fun submitSearch(
        overrideMode: SearchMode? = null,
        overrideSingleSiteId: SiteId? = null,
        overrideCrossSiteIds: List<SiteId>? = null,
    ) {
        val currentInput = inputState.value
        val mode = overrideMode ?: currentInput.mode
        val trimmedQuery = currentInput.query.trim()
        val hasFilters = !currentInput.advancedFilters.isEmpty

        if (mode == SearchMode.CROSS_SITE && trimmedQuery.isEmpty()) return
        if (mode == SearchMode.SINGLE_SITE && trimmedQuery.isEmpty() && !hasFilters) return

        inputState.update { it.copy(submittedQuery = trimmedQuery, crossSiteFilterSiteId = null) }

        viewModelScope.launch {
            val settings = preferencesRepository.currentSettings()
            val activeSiteId = overrideSingleSiteId ?: resolveActiveSiteId(settings.activeSiteId)
            val selectedCross = overrideCrossSiteIds ?: settings.crossSearchSiteIds
                .filter { it in searchableIdSet }
                .ifEmpty { searchableDescriptors.map { it.id } }

            if (trimmedQuery.isNotEmpty() && !settings.incognitoMode) {
                searchHistoryRepository.recordSearch(activeSiteId, trimmedQuery, currentInput.scope)
            }

            when (mode) {
                SearchMode.SINGLE_SITE -> runSingleSiteSearch(
                    targetSiteId = activeSiteId,
                    query = trimmedQuery,
                    inputSnapshot = currentInput,
                    siteLanguage = settings.languageFor(activeSiteId),
                )
                SearchMode.CROSS_SITE -> runCrossSiteSearch(
                    targetSiteIds = selectedCross,
                    query = trimmedQuery,
                    requestedSort = currentInput.sort.takeUnless { it == FeedSort.DEFAULT } ?: FeedSort.RELEVANCE,
                )
            }
        }
    }

    fun loadNextSingleSitePage() {
        val snap = singleSiteState.value
        val siteId = snap.siteId ?: return
        if (snap.isLoading || snap.isLoadingMore || snap.authRequired || !snap.hasMore) return
        val plugin = siteRegistry.get(siteId) ?: return
        val searchSource = plugin.searchSource ?: return

        singlePaginateJob?.cancel()
        singlePaginateJob = viewModelScope.launch {
            singleSiteState.update { it.copy(isLoadingMore = true, paginationErrorMessage = null) }
            val nextPageNumber = snap.nextPage ?: (snap.currentPage + 1)
            val input = inputState.value
            val settings = preferencesRepository.currentSettings()
            val effectiveBlocked = blockedTermsRepository.observeEffectiveTermsBySite(siteId).first()
            val request = buildSingleSiteRequest(
                plugin = plugin,
                query = input.submittedQuery,
                page = nextPageNumber,
                cursor = snap.nextCursor,
                inputSnapshot = input,
                siteLanguage = settings.languageFor(siteId),
                effectiveBlockedTerms = effectiveBlocked,
            )
            try {
                val page = withContext(dispatchers.io) { searchSource.search(request) }
                singleSiteState.update { cur ->
                    if (cur.siteId != siteId) return@update cur
                    val merged = mergeUniqueMediaItems(cur.rawItems, page.items)
                    val progressed = merged.size > cur.rawItems.size ||
                        (page.nextCursor != null && page.nextCursor != cur.nextCursor)
                    cur.copy(
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
                singleSiteState.update { cur ->
                    if (cur.siteId != siteId) return@update cur
                    cur.copy(
                        isLoadingMore = false,
                        paginationErrorMessage = error.toBrowseErrorMessage(),
                    )
                }
            }
        }
    }

    fun retryCrossSite(siteId: SiteId) {
        val query = inputState.value.submittedQuery.trim().ifBlank { inputState.value.query.trim() }
        if (query.isEmpty()) return
        val plugin = siteRegistry.get(siteId) ?: return
        val searchSource = plugin.searchSource ?: return

        crossPerSiteJobs[siteId]?.cancel()
        crossPerSiteJobs[siteId] = viewModelScope.launch {
            updateCrossBucket(siteId) { existing ->
                (existing ?: CrossSiteBucketState(
                    site = plugin.descriptor,
                    isDirectorySearch = searchSource.isDirectorySearch,
                )).copy(
                    status = CrossSiteStatus.LOADING,
                    errorMessage = null,
                    isLoadingMore = false,
                )
            }
            val requestedSort = inputState.value.sort.takeUnless { it == FeedSort.DEFAULT } ?: FeedSort.RELEVANCE
            val resultBucket = executeCrossSiteQuery(
                plugin = plugin,
                query = query,
                requestedSort = requestedSort,
                page = 1,
                cursor = null,
            )
            updateCrossBucket(siteId) { resultBucket }
        }
    }

    fun loadMoreCrossSite(siteId: SiteId) {
        val query = inputState.value.submittedQuery.trim()
        if (query.isEmpty()) return
        val existing = crossSiteState.value.bucketsBySite[siteId] ?: return
        if (!existing.hasMore || existing.isLoadingMore || existing.status != CrossSiteStatus.SUCCESS) return
        val plugin = siteRegistry.get(siteId) ?: return
        val searchSource = plugin.searchSource ?: return
        val requestedSort = crossSiteState.value.fetchedSort
            ?: inputState.value.sort.takeUnless { it == FeedSort.DEFAULT }
            ?: FeedSort.RELEVANCE
        val siteSort = resolveCompatibleServerSort(
            nativeSorts = resolveNativeSearchSorts(plugin, SearchScope.ALL),
            requested = requestedSort,
        )

        crossPerSiteJobs[siteId]?.cancel()
        crossPerSiteJobs[siteId] = viewModelScope.launch {
            updateCrossBucket(siteId) { bucket ->
                bucket?.copy(isLoadingMore = true, errorMessage = null) ?: existing
            }
            val nextPageNumber = existing.nextPage ?: (existing.currentPage + 1)
            val result = crossSearchSemaphore.withPermit {
                try {
                    val settings = preferencesRepository.currentSettings()
                    val lang = if (plugin.descriptor.hasCapability(SiteCapability.LANGUAGE_FILTER)) {
                        settings.languageFor(siteId)
                    } else {
                        ComicLanguage.ALL
                    }
                    val effectiveBlocked = blockedTermsRepository.observeEffectiveTermsBySite(siteId).first()
                    val crossFilters = buildServerExcludeFilters(
                        plugin = plugin,
                        baseFilters = AdvancedSearchFilters(),
                        effectiveBlockedTerms = effectiveBlocked,
                    )
                    val req = FeedRequest(
                        siteId = siteId,
                        query = query,
                        scope = SearchScope.ALL,
                        sort = siteSort,
                        language = lang,
                        page = nextPageNumber,
                        cursor = existing.nextCursor,
                        advancedFilters = crossFilters,
                    )
                    val feedPage = withContext(dispatchers.io) { searchSource.search(req) }
                    Result.success(feedPage)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
            result.fold(
                onSuccess = { feedPage ->
                    updateCrossBucket(siteId) { current ->
                        val base = current ?: existing
                        val merged = mergeUniqueMediaItems(base.items, feedPage.items)
                        val progressed = merged.size > base.items.size ||
                            (feedPage.nextCursor != null && feedPage.nextCursor != base.nextCursor)
                        base.copy(
                            status = CrossSiteStatus.SUCCESS,
                            items = merged,
                            currentPage = nextPageNumber,
                            nextPage = feedPage.nextPage,
                            nextCursor = feedPage.nextCursor,
                            hasMore = feedPage.hasMore && progressed,
                            isLoadingMore = false,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = { error ->
                    updateCrossBucket(siteId) { current ->
                        val base = current ?: existing
                        base.copy(
                            isLoadingMore = false,
                            errorMessage = error.toBrowseErrorMessage(),
                        )
                    }
                },
            )
        }
    }

    private fun runSingleSiteSearch(
        targetSiteId: SiteId,
        query: String,
        inputSnapshot: SearchInputState,
        siteLanguage: ComicLanguage,
    ) {
        singleSearchJob?.cancel()
        singlePaginateJob?.cancel()
        singleSearchJob = viewModelScope.launch {
            val plugin = siteRegistry.get(targetSiteId)
            val searchSource = plugin?.searchSource
            if (plugin == null || searchSource == null) {
                singleSiteState.value = SingleSiteResultState(
                    siteId = targetSiteId,
                    isLoading = false,
                    hasSearched = true,
                    errorMessage = "当前站点暂不支持搜索功能",
                )
                return@launch
            }

            singleSiteState.value = SingleSiteResultState(
                siteId = targetSiteId,
                isLoading = true,
                hasSearched = true,
            )

            try {
                if (plugin.authProvider.requiresAuthGate) {
                    val auth = withContext(dispatchers.io) { plugin.authProvider.currentAuthState() }
                    if (!auth.isAuthenticated) {
                        singleSiteState.value = SingleSiteResultState(
                            siteId = targetSiteId,
                            isLoading = false,
                            hasSearched = true,
                            authRequired = true,
                        )
                        return@launch
                    }
                }

                val effectiveBlocked = blockedTermsRepository.observeEffectiveTermsBySite(targetSiteId).first()
                val request = buildSingleSiteRequest(
                    plugin = plugin,
                    query = query,
                    page = 1,
                    cursor = null,
                    inputSnapshot = inputSnapshot,
                    siteLanguage = siteLanguage,
                    effectiveBlockedTerms = effectiveBlocked,
                )
                val page = withContext(dispatchers.io) { searchSource.search(request) }
                singleSiteState.value = SingleSiteResultState(
                    siteId = targetSiteId,
                    isLoading = false,
                    hasSearched = true,
                    rawItems = page.items.distinctBy { it.compositeKey },
                    fetchedSort = request.sort,
                    isDirectoryResults = searchSource.isDirectorySearch ||
                        page.items.any { it.isDirectoryEntryItem() },
                    currentPage = 1,
                    nextPage = page.nextPage,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                singleSiteState.value = SingleSiteResultState(
                    siteId = targetSiteId,
                    isLoading = false,
                    hasSearched = true,
                    authRequired = error is SiteException.AuthRequired,
                    errorMessage = error.toBrowseErrorMessage(),
                )
            }
        }
    }

    private fun runCrossSiteSearch(
        targetSiteIds: List<SiteId>,
        query: String,
        requestedSort: FeedSort,
    ) {
        crossSearchJob?.cancel()
        cancelAllCrossSiteJobs()

        val plugins = targetSiteIds.mapNotNull { siteRegistry.get(it) }.filter { it.searchSource != null }
        if (plugins.isEmpty()) {
            crossSiteState.value = CrossSiteResultState(
                hasSearched = true,
                fetchedSort = requestedSort,
                bucketsBySite = emptyMap(),
            )
            return
        }

        val initialBuckets = plugins.associate { plugin ->
            plugin.id to CrossSiteBucketState(
                site = plugin.descriptor,
                status = CrossSiteStatus.LOADING,
                isDirectorySearch = plugin.searchSource?.isDirectorySearch == true,
            )
        }
        crossSiteState.value = CrossSiteResultState(
            hasSearched = true,
            fetchedSort = requestedSort,
            bucketsBySite = initialBuckets,
        )

        crossSearchJob = viewModelScope.launch {
            coroutineScope {
                plugins.map { plugin ->
                    async {
                        val bucket = executeCrossSiteQuery(
                            plugin = plugin,
                            query = query,
                            requestedSort = requestedSort,
                            page = 1,
                            cursor = null,
                        )
                        updateCrossBucket(plugin.id) { bucket }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun executeCrossSiteQuery(
        plugin: SitePlugin,
        query: String,
        requestedSort: FeedSort,
        page: Int,
        cursor: String?,
    ): CrossSiteBucketState {
        val searchSource = plugin.searchSource ?: return CrossSiteBucketState(
            site = plugin.descriptor,
            status = CrossSiteStatus.ERROR,
            errorMessage = "不支持搜索",
        )
        val isDir = searchSource.isDirectorySearch
        val siteSort = resolveCompatibleServerSort(
            nativeSorts = resolveNativeSearchSorts(plugin, SearchScope.ALL),
            requested = requestedSort,
        )

        return crossSearchSemaphore.withPermit {
            try {
                if (plugin.authProvider.requiresAuthGate) {
                    val auth = withContext(dispatchers.io) { plugin.authProvider.currentAuthState() }
                    if (!auth.isAuthenticated) {
                        return@withPermit CrossSiteBucketState(
                            site = plugin.descriptor,
                            status = CrossSiteStatus.AUTH_REQUIRED,
                            isDirectorySearch = isDir,
                            errorMessage = "需要登录",
                        )
                    }
                }

                val settings = preferencesRepository.currentSettings()
                val language = if (plugin.descriptor.hasCapability(SiteCapability.LANGUAGE_FILTER)) {
                    settings.languageFor(plugin.id)
                } else {
                    ComicLanguage.ALL
                }
                val effectiveBlocked = blockedTermsRepository.observeEffectiveTermsBySite(plugin.id).first()
                val crossFilters = buildServerExcludeFilters(
                    plugin = plugin,
                    baseFilters = AdvancedSearchFilters(),
                    effectiveBlockedTerms = effectiveBlocked,
                )
                val request = FeedRequest(
                    siteId = plugin.id,
                    query = query,
                    scope = SearchScope.ALL,
                    sort = siteSort,
                    language = language,
                    page = page,
                    cursor = cursor,
                    advancedFilters = crossFilters,
                )
                val result = withContext(dispatchers.io) { searchSource.search(request) }
                val unique = result.items.distinctBy { it.compositeKey }
                CrossSiteBucketState(
                    site = plugin.descriptor,
                    status = if (unique.isEmpty()) CrossSiteStatus.EMPTY else CrossSiteStatus.SUCCESS,
                    isDirectorySearch = isDir || unique.any { it.isDirectoryEntryItem() },
                    items = unique,
                    currentPage = page,
                    nextPage = result.nextPage,
                    nextCursor = result.nextCursor,
                    hasMore = result.hasMore,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                CrossSiteBucketState(
                    site = plugin.descriptor,
                    status = if (error is SiteException.AuthRequired) {
                        CrossSiteStatus.AUTH_REQUIRED
                    } else {
                        CrossSiteStatus.ERROR
                    },
                    isDirectorySearch = isDir,
                    errorMessage = error.toBrowseErrorMessage(),
                )
            }
        }
    }

    private fun buildSingleSiteRequest(
        plugin: SitePlugin,
        query: String,
        page: Int,
        cursor: String?,
        inputSnapshot: SearchInputState,
        siteLanguage: ComicLanguage,
        effectiveBlockedTerms: Collection<String> = emptyList(),
    ): FeedRequest {
        val searchSource = plugin.searchSource
        val supportedScopes = searchSource?.supportedScopes ?: setOf(SearchScope.ALL)
        val effectiveScope = if (inputSnapshot.scope in supportedScopes) {
            inputSnapshot.scope
        } else {
            supportedScopes.firstOrNull() ?: SearchScope.ALL
        }
        val supportsLang = plugin.descriptor.hasCapability(SiteCapability.LANGUAGE_FILTER) ||
            AdvancedFilterField.LANGUAGE in (searchSource?.supportedFilters ?: emptySet())
        val effectiveLanguage = if (supportsLang) {
            inputSnapshot.languageOverride ?: siteLanguage
        } else {
            ComicLanguage.ALL
        }
        val nativeSorts = resolveNativeSearchSorts(plugin, effectiveScope)
        val requestedSort = if (inputSnapshot.sort == FeedSort.DEFAULT) nativeSorts.first() else inputSnapshot.sort
        val effectiveSort = resolveCompatibleServerSort(nativeSorts, requestedSort)
        val effectiveFilters = buildServerExcludeFilters(
            plugin = plugin,
            baseFilters = inputSnapshot.advancedFilters,
            effectiveBlockedTerms = effectiveBlockedTerms,
        )
        return FeedRequest(
            siteId = plugin.id,
            category = effectiveFilters.category?.takeIf { it.isNotBlank() },
            sort = effectiveSort,
            language = effectiveLanguage,
            page = page,
            cursor = cursor,
            query = query.ifBlank { null },
            scope = effectiveScope,
            advancedFilters = effectiveFilters,
        )
    }

    private fun buildServerExcludeFilters(
        plugin: SitePlugin,
        baseFilters: AdvancedSearchFilters,
        effectiveBlockedTerms: Collection<String>,
    ): AdvancedSearchFilters {
        val searchSource = plugin.searchSource ?: return baseFilters
        val supportsExclude = searchSource.supportsExcludeTags ||
            AdvancedFilterField.EXCLUDE_TAGS in searchSource.supportedFilters
        if (!supportsExclude || effectiveBlockedTerms.isEmpty()) return baseFilters
        val merged = mergeBlockedExcludeTags(
            existingExcludeTags = baseFilters.excludeTags,
            effectiveBlockedTerms = effectiveBlockedTerms,
            tagDictionary = tagDictionary,
        )
        return if (merged == baseFilters.excludeTags) {
            baseFilters
        } else {
            baseFilters.copy(excludeTags = merged)
        }
    }

    private fun updateCrossBucket(
        siteId: SiteId,
        transform: (CrossSiteBucketState?) -> CrossSiteBucketState,
    ) {
        crossSiteState.update { current ->
            val nextMap = LinkedHashMap(current.bucketsBySite)
            nextMap[siteId] = transform(nextMap[siteId])
            current.copy(bucketsBySite = nextMap)
        }
    }

    private fun cancelAllCrossSiteJobs() {
        crossPerSiteJobs.values.forEach { it.cancel() }
        crossPerSiteJobs.clear()
    }

    private fun interleaveBuckets(buckets: List<CrossSiteBucketState>): List<MediaItem> {
        val lists = buckets.map { it.items }.filter { it.isNotEmpty() }
        if (lists.isEmpty()) return emptyList()
        if (lists.size == 1) return lists.first()
        val maxLen = lists.maxOf { it.size }
        val result = ArrayList<MediaItem>(lists.sumOf { it.size })
        val seen = HashSet<String>()
        for (i in 0 until maxLen) {
            for (list in lists) {
                val item = list.getOrNull(i) ?: continue
                if (seen.add(item.compositeKey)) {
                    result.add(item)
                }
            }
        }
        return result
    }

    private suspend fun fetchDiscoveryWorks(plugin: SitePlugin): List<MediaItem> {
        val siteId = plugin.id
        try {
            val home = plugin.feedSource?.loadHome()
            if (home != null) {
                val combined = buildList {
                    home.sections.firstOrNull { it.isRanked }?.items?.let(::addAll)
                    home.sections.forEach { addAll(it.items) }
                    addAll(home.heroItems)
                    home.primaryFeedPage?.items?.let(::addAll)
                }
                if (combined.isNotEmpty()) {
                    return combined.distinctBy { it.compositeKey }.take(20)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Fall through to popular feed lookup
        }
        return try {
            val page = plugin.feedSource?.loadFeed(
                FeedRequest(
                    siteId = siteId,
                    sort = FeedSort.POPULAR,
                    page = 1,
                ),
            )
            page?.items.orEmpty().distinctBy { it.compositeKey }.take(20)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun buildDiscoveryTagRankings(
        siteId: SiteId,
        works: List<MediaItem>,
        cachedTagsByKey: Map<String, List<TagRef>>,
        blockedTerms: List<String>,
        translateTags: Boolean,
    ): List<SearchTrendingTag> {
        val counts = LinkedHashMap<String, Int>()
        val labels = LinkedHashMap<String, String>()
        val namespaces = LinkedHashMap<String, String>()

        fun recordTag(slugRaw: String, labelRaw: String, nsRaw: String, weight: Int) {
            val slug = slugRaw.trim()
            if (slug.isEmpty()) return
            val key = slug.lowercase()
            if (blockedTerms.any { it.equals(key, ignoreCase = true) }) return
            counts[key] = (counts[key] ?: 0) + weight
            labels.putIfAbsent(key, labelRaw.ifBlank { slug })
            if (nsRaw.isNotBlank()) namespaces.putIfAbsent(key, nsRaw)
        }

        works.forEachIndexed { idx, item ->
            val rankWeight = (works.size - idx).coerceAtLeast(1)
            item.tags.forEach { tag ->
                recordTag(tag.slug, tag.label, tag.namespace, weight = rankWeight)
            }
            cachedTagsByKey[item.compositeKey]?.forEach { tag ->
                recordTag(tag.slug, tag.label, tag.namespace, weight = rankWeight)
            }
        }

        val sitePrefix = "${siteId.value}:"
        cachedTagsByKey.forEach { (key, tags) ->
            if (key.startsWith(sitePrefix)) {
                tags.forEach { tag ->
                    recordTag(tag.slug, tag.label, tag.namespace, weight = 2)
                }
            }
        }

        val observedKeys = counts.keys.toSet()
        if (counts.size < MAX_TRENDING_TAGS) {
            val fallback = tagDictionary.builtInTagEntries(limit = MAX_TRENDING_TAGS)
            fallback.forEachIndexed { idx, sug ->
                val weight = (MAX_TRENDING_TAGS - idx).coerceAtLeast(1)
                if (sug.slug.lowercase() !in counts) {
                    recordTag(sug.slug, sug.localizedName, sug.namespace, weight = weight)
                }
            }
        }

        return counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.key in observedKeys }
                    .thenByDescending { it.value }
                    .thenBy { it.key },
            )
            .take(MAX_TRENDING_TAGS)
            .mapIndexed { idx, entry ->
                val key = entry.key
                val ns = namespaces[key].orEmpty()
                val rawLabel = labels[key] ?: key
                val localized = if (translateTags) {
                    tagDictionary.translate(key, ns)
                        ?: tagDictionary.translate(rawLabel, ns)
                        ?: rawLabel
                } else {
                    key
                }
                SearchTrendingTag(
                    slug = key,
                    localizedName = localized,
                    count = entry.value,
                    rank = idx + 1,
                )
            }
    }

    private fun resolveActiveSiteId(preferred: SiteId): SiteId =
        if (siteRegistry.get(preferred) != null) {
            preferred
        } else {
            allDescriptors.firstOrNull()?.id ?: preferred
        }

    companion object {
        const val MAX_CONCURRENT_CROSS_SITE_SEARCHES: Int = 3
        const val MAX_HISTORY_CHIPS: Int = 30
        const val MAX_SERVER_EXCLUDE_BLOCKED_TERMS: Int = 12
        const val DISCOVERY_CACHE_TTL_MS: Long = 10L * 60_000L
        const val MAX_TRENDING_TAGS: Int = 12
        const val MAX_HOT_TAG_RANKING: Int = 10
        const val MAX_HOT_WORKS_RANKING: Int = 10

        internal fun mergeBlockedExcludeTags(
            existingExcludeTags: List<String>,
            effectiveBlockedTerms: Collection<String>,
            tagDictionary: TagDictionary,
            maxTerms: Int = MAX_SERVER_EXCLUDE_BLOCKED_TERMS,
        ): List<String> {
            if (effectiveBlockedTerms.isEmpty()) return existingExcludeTags
            val result = LinkedHashSet<String>()
            val seenLower = HashSet<String>()
            for (raw in existingExcludeTags) {
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty() && seenLower.add(trimmed.lowercase())) {
                    result.add(trimmed)
                }
            }
            val cap = maxOf(result.size, maxTerms)
            for (term in effectiveBlockedTerms) {
                if (result.size >= cap) break
                val englishSlugs = tagDictionary.canonicalEnglishSlugs(term)
                for (slug in englishSlugs) {
                    if (result.size >= cap) break
                    val cleaned = slug.trim()
                    if (cleaned.isNotEmpty() && seenLower.add(cleaned.lowercase())) {
                        result.add(cleaned)
                    }
                }
            }
            return result.toList()
        }

        val CROSS_SITE_SORTS: List<FeedSort> = listOf(
            FeedSort.RELEVANCE,
            FeedSort.NEWEST,
            FeedSort.OLDEST,
            FeedSort.VIEWS,
            FeedSort.LIKES,
            FeedSort.RATING,
            FeedSort.POPULAR,
            FeedSort.DURATION,
        )

        private val VIDEO_LOCAL_FALLBACK_SORTS: List<FeedSort> = listOf(
            FeedSort.NEWEST,
            FeedSort.OLDEST,
            FeedSort.VIEWS,
            FeedSort.LIKES,
            FeedSort.RATING,
            FeedSort.DURATION,
        )

        private val GALLERY_LOCAL_FALLBACK_SORTS: List<FeedSort> = listOf(
            FeedSort.NEWEST,
            FeedSort.OLDEST,
            FeedSort.VIEWS,
            FeedSort.LIKES,
            FeedSort.RATING,
        )

        internal fun resolveNativeSearchSorts(
            plugin: SitePlugin?,
            scope: SearchScope,
        ): List<FeedSort> {
            if (plugin == null) return listOf(FeedSort.RELEVANCE)
            val explicit = plugin.searchSource?.supportedSearchSorts(scope).orEmpty()
            if (explicit.isNotEmpty()) return explicit.distinct()
            val fromFeed = plugin.feedSource?.supportedSorts(
                FeedRequest(
                    siteId = plugin.id,
                    query = "q",
                    scope = scope,
                ),
            ).orEmpty()
            if (fromFeed.isNotEmpty()) return fromFeed.distinct()
            val fromDescriptor = plugin.descriptor.feedSorts
            return if (fromDescriptor.isNotEmpty()) {
                buildList {
                    if (scope == SearchScope.ALL) add(FeedSort.RELEVANCE)
                    fromDescriptor.forEach { if (it !in this) add(it) }
                }
            } else {
                listOf(FeedSort.RELEVANCE)
            }
        }

        internal fun buildLocalFallbackSorts(
            primaryKind: MediaKind?,
            nativeSorts: List<FeedSort>,
        ): List<FeedSort> {
            val candidates = if (primaryKind == MediaKind.VIDEO || primaryKind == null) {
                VIDEO_LOCAL_FALLBACK_SORTS
            } else {
                GALLERY_LOCAL_FALLBACK_SORTS
            }
            return candidates.filter { it !in nativeSorts }
        }

        internal fun resolveCompatibleServerSort(
            nativeSorts: List<FeedSort>,
            requested: FeedSort,
        ): FeedSort {
            if (nativeSorts.isEmpty()) return requested.takeUnless { it == FeedSort.DEFAULT } ?: FeedSort.RELEVANCE
            if (requested == FeedSort.DEFAULT) return nativeSorts.first()
            if (requested in nativeSorts) return requested
            val fallbacks: List<FeedSort> = when (requested) {
                FeedSort.OLDEST -> listOf(FeedSort.NEWEST, FeedSort.RELEVANCE)
                FeedSort.LIKES -> listOf(FeedSort.RATING, FeedSort.POPULAR, FeedSort.VIEWS)
                FeedSort.RATING -> listOf(FeedSort.LIKES, FeedSort.POPULAR, FeedSort.VIEWS)
                FeedSort.VIEWS -> listOf(FeedSort.POPULAR, FeedSort.TRENDING, FeedSort.LIKES)
                FeedSort.POPULAR -> listOf(FeedSort.VIEWS, FeedSort.TRENDING, FeedSort.LIKES, FeedSort.RATING)
                FeedSort.TRENDING -> listOf(FeedSort.POPULAR, FeedSort.VIEWS, FeedSort.NEWEST)
                FeedSort.DURATION -> listOf(FeedSort.RELEVANCE, FeedSort.NEWEST)
                FeedSort.NEWEST -> listOf(FeedSort.RELEVANCE)
                FeedSort.RELEVANCE -> listOf(FeedSort.NEWEST)
                FeedSort.RANDOM -> listOf(FeedSort.RELEVANCE, FeedSort.NEWEST)
                FeedSort.DEFAULT -> emptyList()
            }
            return fallbacks.firstOrNull { it in nativeSorts } ?: nativeSorts.first()
        }
    }
}
