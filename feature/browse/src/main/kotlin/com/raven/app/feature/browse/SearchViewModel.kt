package com.raven.app.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.BlockedTermExcludeInjection
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.MediaDateParser
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.common.TagSuggestion
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.database.repository.FavoriteRepository
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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


internal fun requestLanguage(plugin: SitePlugin, input: SearchInputState, siteLanguage: ComicLanguage): ComicLanguage {
    val supported = plugin.descriptor.hasCapability(SiteCapability.LANGUAGE_FILTER) ||
        AdvancedFilterField.LANGUAGE in plugin.searchSource?.supportedFilters.orEmpty()
    if (!supported) return ComicLanguage.ALL
    return input.advancedFilters.siteLanguages[plugin.id] ?: input.languageOverride ?: siteLanguage
}

internal data class SearchInputState(
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

internal data class SingleSiteResultState(
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

internal data class CrossSiteResultState(
    val hasSearched: Boolean = false,
    val fetchedSort: FeedSort? = null,
    val bucketsBySite: Map<SiteId, CrossSiteBucketState> = emptyMap(),
)

/**
 * 合并全站最近搜索：同一关键词 + 范围只保留最新一条，按 searchedAt 倒序后截断。
 * GLOBAL 记录在同秒并列时优先，确保跨站搜索仍可一键恢复。
 */
internal fun mergeSearchHistoryEntries(
    entries: List<SearchHistoryEntry>,
    limit: Int = 30,
): List<SearchHistoryEntry> {
    val merged = LinkedHashMap<Pair<String, SearchScope>, SearchHistoryEntry>()
    entries
        .sortedWith(
            compareByDescending<SearchHistoryEntry> { it.searchedAt }
                .thenByDescending { it.siteId.isGlobal },
        )
        .forEach { entry ->
            merged.putIfAbsent(entry.query to entry.scope, entry)
        }
    return merged.values.take(limit)
}

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
    favoriteRepository: FavoriteRepository? = null,
    downloadStateRepository: DownloadStateRepository? = null,
    downloadEnqueuer: BrowseDownloadEnqueuer? = null,
) : ViewModel() {

    private val allDescriptors: List<SiteDescriptor> = siteRegistry.allDescriptors()

    /** Optional so existing JVM tests without download fakes keep constructing successfully. */
    private val mediaActions: BrowseMediaActions? =
        if (favoriteRepository != null && downloadStateRepository != null && downloadEnqueuer != null) {
            BrowseMediaActions(
                favoriteRepository = favoriteRepository,
                downloadStateRepository = downloadStateRepository,
                enqueuer = downloadEnqueuer,
                scope = viewModelScope,
                ioDispatcher = dispatchers.io,
            )
        } else {
            null
        }

    val favoriteKeys: StateFlow<Set<String>> = (
        mediaActions?.favoriteKeysFlow() ?: flowOf(emptySet())
        ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptySet(),
    )

    // Cross-site results span many sites, so aggregate download state across all of them.
    val downloadStates: StateFlow<Map<String, MediaCardDownloadState>> = (
        mediaActions?.downloadStatesFlow(null) ?: flowOf(emptyMap())
        ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyMap(),
    )

    val actionMessage: StateFlow<String?> = mediaActions?.messages
        ?: MutableStateFlow<String?>(null).asStateFlow()

    private val searchablePlugins: List<SitePlugin> = siteRegistry.allPlugins().filter { it.searchSource != null }
    private val searchableDescriptors: List<SiteDescriptor> = searchablePlugins.map { it.descriptor }
    private val searchableIdSet: Set<SiteId> = searchableDescriptors.mapTo(HashSet()) { it.id }

    private val crossSitesMutex = Mutex()

    /**
     * Site supplied by the originating screen (e.g. gallery detail opened from a library shelf).
     * Single-site search and history must use it instead of the ambient active site, otherwise
     * a search started from site B's work runs against site A.
     */
    private val sourceSiteId: SiteId? = initialRoute.sourceSiteId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { SiteId.of(it) }.getOrNull() }

    /** Set when the user explicitly picks a site in Search, which must win over [sourceSiteId]. */
    private val manualSiteOverride = MutableStateFlow<SiteId?>(null)

    private fun resolveSearchSiteId(settings: AppSettings, selectedSiteId: SiteId? = manualSiteOverride.value): SiteId {
        val preferred = selectedSiteId ?: sourceSiteId ?: settings.activeSiteId
        return resolveActiveSiteId(preferred)
    }

    private val inputState = MutableStateFlow(
        SearchInputState(
            mode = if (initialRoute.crossSite) SearchMode.CROSS_SITE else SearchMode.SINGLE_SITE,
            query = initialRoute.initialQuery?.trim().orEmpty(),
        ),
    )

    private val singleSiteRunner = SingleSiteSearchRunner(
        scope = viewModelScope,
        dispatchers = dispatchers,
        siteRegistry = siteRegistry,
        preferences = preferencesRepository,
        blockedTerms = blockedTermsRepository,
        tagDictionary = tagDictionary,
        inputState = { inputState.value },
        currentGeneration = { searchGeneration },
    )

    private val crossSiteRunner = CrossSiteSearchRunner(
        scope = viewModelScope,
        dispatchers = dispatchers,
        siteRegistry = siteRegistry,
        preferences = preferencesRepository,
        blockedTerms = blockedTermsRepository,
        tagDictionary = tagDictionary,
        inputState = { inputState.value },
        currentGeneration = { searchGeneration },
    )

    /**
     * Monotonic intent generation. Bumped on every submit/clear so a stale prepare phase (settings
     * fetch, history write) that finishes late cannot start a request or cancel a newer one.
     */
    private var searchGeneration: Long = 0L
    private val shelfController = BrowseShelfController(
        shelfRepository = shelfRepository,
        scope = viewModelScope,
        ioDispatcher = dispatchers.io,
    )

    val shelfPickerShelves: StateFlow<List<ShelfWithSummary>> get() = shelfController.shelves
    val inspectedShelfIds: StateFlow<Set<String>> get() = shelfController.inspectedShelfIds

    private val activeSiteIdFlow = combine(preferencesRepository.settings, manualSiteOverride, ::resolveSearchSiteId)
        .distinctUntilChanged()

    private val discoveryController = SearchDiscoveryController(
        dispatchers = dispatchers,
        siteRegistry = siteRegistry,
        activeSiteIdFlow = activeSiteIdFlow,
    )

    private val cachedTagsFlow = mediaTagCacheRepository?.observeAllTags() ?: flowOf(emptyMap())
    private val dictRevisionFlow = tagDictionaryRepository?.observeRevision() ?: flowOf(0L)

    private val discoveryFlow = discoveryController.flow

    private val mergedHistoryFlow = searchHistoryRepository
        .observeAll(limit = MAX_HISTORY_CHIPS)
        .map { entries -> mergeSearchHistoryEntries(entries, limit = MAX_HISTORY_CHIPS) }

    private val sharedWatchProgressMapFlow = historyRepository
        .observeAllWatchProgress()
        .map { watches -> watches.associate { it.compositeKey to it.progressFraction } }

    private val sharedReadingProgressMapFlow = historyRepository
        .observeAllReadingProgress()
        .map { readings -> readings.associate { it.compositeKey to it.progressFraction } }

    private val sharedTopDbFlow = combine(
        mergedHistoryFlow,
        subscriptionRepository.observeSavedSearches(),
        blockedTermsRepository.observeAllGrouped(),
    ) { history, saved, groupedBlocked ->
        Triple(history, saved, groupedBlocked)
    }

    private val sharedBottomDbFlow = combine(
        sharedWatchProgressMapFlow,
        sharedReadingProgressMapFlow,
        shelfRepository.observeShelfItemKeys(Shelf.WATCH_LATER_ID),
        cachedTagsFlow,
        dictRevisionFlow,
    ) { watches, readings, watchLater, cachedTags, dictRevision ->
        SearchBottomDbSlice(
            watchProgressMap = watches,
            readingProgressMap = readings,
            watchLaterKeys = watchLater,
            cachedTagsByKey = cachedTags,
            dictRevision = dictRevision,
        )
    }

    private val activeEffectiveBlockedFlow = activeSiteIdFlow.flatMapLatest { siteId ->
        blockedTermsRepository.observeEffectiveTermsBySite(siteId)
    }

    private val dbContextFlow = combine(
        sharedTopDbFlow,
        activeEffectiveBlockedFlow,
        sharedBottomDbFlow,
    ) { (history, saved, groupedBlocked), activeEffectiveBlocked, right ->
        SearchDbContext(
            history = history,
            savedSearches = saved,
            blockedTermsBySite = groupedBlocked,
            activeSiteEffectiveBlocked = activeEffectiveBlocked,
            cachedTagsByKey = right.cachedTagsByKey,
            watchProgressMap = right.watchProgressMap,
            readingProgressMap = right.readingProgressMap,
            watchLaterKeys = right.watchLaterKeys,
            dictRevision = right.dictRevision,
        )
    }

    val uiState: StateFlow<SearchUiState> = combine(
        preferencesRepository.settings,
        combine(inputState, discoveryFlow) { input, discovery -> input to discovery },
        singleSiteRunner.state,
        crossSiteRunner.state,
        dbContextFlow,
    ) { settings, inputAndDiscovery, single, cross, db ->
        val (input, discovery) = inputAndDiscovery
        // A search launched from a gallery's site targets that site, not the ambient active one.
        val activeSiteId = resolveSearchSiteId(settings)
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

        val rankedTags = buildTrendingTags(
            siteId = activeSiteId,
            works = hotWorks,
            cachedTagsByKey = cachedTags,
            blockedTerms = activeSiteBlocked,
            translateTags = translateTags,
            tagDictionary = tagDictionary,
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
            allSites = settings.sortSitesByUsage(allDescriptors) { it.id },
            searchableSites = settings.sortSitesByUsage(searchableDescriptors) { it.id },
            pinnedSiteIds = settings.pinnedSiteIds,
            dynamicSiteSorting = settings.dynamicSiteSorting,
            siteOpenCounts = settings.siteOpenCounts,
            startupDefaultSiteId = settings.startupDefaultSiteId,
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
            showSearchTutorial = !settings.hasSeenTutorial(com.raven.app.core.model.FeatureTutorialIds.SEARCH_DISCOVER),
            lastSavedSearchNotice = input.lastSavedNotice,
        )
    }.flowOn(dispatchers.default).stateIn(
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
        searchGeneration++
        singleSiteRunner.cancel()
        crossSiteRunner.cancelAll()
        inputState.update { it.copy(mode = mode, crossSiteFilterSiteId = null) }
        val q = inputState.value.query.trim()
        if (q.isNotEmpty()) {
            submitSearch()
        }
    }

    fun selectActiveSite(siteId: SiteId) {
        if (siteRegistry.get(siteId) == null) return
        // An explicit pick overrides any gallery-derived source site for this search session.
        manualSiteOverride.value = siteId
        singleSiteRunner.reset(siteId)
        inputState.update {
            it.copy(
                scope = SearchScope.ALL,
                sort = FeedSort.DEFAULT,
                languageOverride = null,
                advancedFilters = AdvancedSearchFilters(),
            )
        }
        viewModelScope.launch {
            preferencesRepository.selectActiveSiteAndRecordOpen(siteId)
        }
    }

    fun togglePinnedSite(siteId: SiteId) {
        viewModelScope.launch {
            preferencesRepository.togglePinnedSite(siteId)
        }
    }

    /** Persists a manual reorder of the pinned sites. */
    fun reorderPinnedSites(siteIds: List<SiteId>) {
        val known = allDescriptors.mapTo(HashSet()) { it.id }
        val sanitized = siteIds.filter { it in known }.distinct()
        if (sanitized.isEmpty()) return
        viewModelScope.launch {
            preferencesRepository.setPinnedSiteIds(sanitized)
        }
    }

    fun setDynamicSiteSorting(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDynamicSiteSorting(enabled)
        }
    }

    fun setStartupDefaultSiteId(siteId: SiteId?) {
        if (siteId != null && siteRegistry.get(siteId) == null) return
        viewModelScope.launch {
            preferencesRepository.setStartupDefaultSiteId(siteId)
        }
    }

    fun updateQuery(newQuery: String) {
        inputState.update { it.copy(query = newQuery, lastSavedNotice = null) }
    }

    fun clearQuery() {
        // Bump generation first so any in-flight prepare phase is invalidated before we reset state.
        searchGeneration++
        singleSiteRunner.cancel()
        crossSiteRunner.cancelAll()
        inputState.update { it.copy(query = "", submittedQuery = "", crossSiteFilterSiteId = null) }
        singleSiteRunner.reset(uiState.value.activeSite?.id)
        crossSiteRunner.reset()
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
                val singleSnap = singleSiteRunner.current()
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
                if (crossSiteRunner.state.value.hasSearched) {
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
        normalizeCrossSiteSelection(ids, searchableDescriptors, searchableIdSet)

    fun toggleCrossSiteResultFilter(siteId: SiteId) {
        inputState.update { current ->
            val next = if (current.crossSiteFilterSiteId == siteId) null else siteId
            current.copy(crossSiteFilterSiteId = next)
        }
    }

    fun selectHistoryEntry(entry: SearchHistoryEntry) {
        val targetMode = if (entry.siteId == SiteId.GLOBAL) {
            SearchMode.CROSS_SITE
        } else {
            SearchMode.SINGLE_SITE
        }
        searchGeneration++
        singleSiteRunner.cancel()
        crossSiteRunner.cancelAll()
        inputState.update {
            it.copy(
                mode = targetMode,
                query = entry.query,
                scope = entry.scope,
                sort = FeedSort.DEFAULT,
                languageOverride = null,
                advancedFilters = AdvancedSearchFilters(),
                crossSiteFilterSiteId = null,
            )
        }
        if (targetMode == SearchMode.CROSS_SITE) {
            submitSearch(overrideMode = SearchMode.CROSS_SITE)
        } else {
            val siteId = entry.siteId
            if (siteRegistry.get(siteId) == null) {
                submitSearch(overrideMode = SearchMode.SINGLE_SITE)
            } else {
                manualSiteOverride.value = siteId
                singleSiteRunner.reset(siteId)
                viewModelScope.launch {
                    preferencesRepository.selectActiveSiteAndRecordOpen(siteId)
                    submitSearch(overrideMode = SearchMode.SINGLE_SITE, overrideSingleSiteId = siteId)
                }
            }
        }
    }

    fun removeHistoryEntry(entry: SearchHistoryEntry) {
        viewModelScope.launch {
            searchHistoryRepository.removeEntry(entry.siteId, entry.query, entry.scope)
        }
    }

    fun clearSiteHistory() {
        viewModelScope.launch {
            val siteId = resolveSearchSiteId(preferencesRepository.currentSettings())
            searchHistoryRepository.clearSite(siteId)
            searchHistoryRepository.clearSite(SiteId.GLOBAL)
        }
    }

    fun saveCurrentSearch(rawName: String) {
        val input = inputState.value
        viewModelScope.launch {
            val settings = preferencesRepository.currentSettings()
            val activeSiteId = resolveSearchSiteId(settings)
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

    fun completeSearchTutorial() {
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.markTutorialSeen(com.raven.app.core.model.FeatureTutorialIds.SEARCH_DISCOVER)
        }
    }

    fun skipAllTutorials() {
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.markTutorialsSeen(com.raven.app.core.model.FeatureTutorialIds.ALL)
        }
    }

    fun applySavedSearch(savedSearch: SavedSearch) {
        val validSites = savedSearch.siteIds.filter { it in searchableIdSet }
        val targetMode = if (validSites.size > 1) SearchMode.CROSS_SITE else SearchMode.SINGLE_SITE

        searchGeneration++
        singleSiteRunner.cancel()
        crossSiteRunner.cancelAll()
        viewModelScope.launch {
            if (targetMode == SearchMode.SINGLE_SITE && validSites.size == 1) {
                manualSiteOverride.value = validSites.first()
                preferencesRepository.setActiveSiteId(validSites.first())
            } else if (targetMode == SearchMode.CROSS_SITE) {
                crossSitesMutex.withLock {
                    preferencesRepository.setCrossSearchSiteIds(normalizeCrossSiteSelection(validSites))
                }
            }
            inputState.update {
                it.copy(
                    mode = targetMode,
                    query = savedSearch.query,
                    scope = savedSearch.scope,
                    sort = savedSearch.sort,
                    languageOverride = savedSearch.language.takeUnless {
                        it == ComicLanguage.ALL && savedSearch.filters.siteLanguages.isNotEmpty()
                    },
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

    fun toggleFavorite(item: MediaItem) {
        mediaActions?.toggleFavorite(item)
    }

    fun downloadMedia(item: MediaItem) {
        mediaActions?.download(item)
    }

    fun clearActionMessage() {
        mediaActions?.clearMessage()
    }

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

        val generation = ++searchGeneration
        viewModelScope.launch {
            val settings = preferencesRepository.currentSettings()
            // A newer submit or a clear happened while we were reading settings: abandon silently.
            if (generation != searchGeneration) return@launch
            val activeSiteId = overrideSingleSiteId ?: resolveSearchSiteId(settings)
            val selectedCross = overrideCrossSiteIds ?: settings.crossSearchSiteIds
                .filter { it in searchableIdSet }
                .ifEmpty { searchableDescriptors.map { it.id } }

            val historySiteId = if (mode == SearchMode.CROSS_SITE) SiteId.GLOBAL else activeSiteId
            if (trimmedQuery.isNotEmpty() && !settings.incognitoMode) {
                searchHistoryRepository.recordSearch(historySiteId, trimmedQuery, currentInput.scope)
            }
            // Re-check after the suspending history write, then hand the generation to the worker so
            // it cannot be mistaken for the latest intent once it actually starts the request.
            if (generation != searchGeneration) return@launch

            when (mode) {
                SearchMode.SINGLE_SITE -> runSingleSiteSearch(
                    targetSiteId = activeSiteId,
                    query = trimmedQuery,
                    inputSnapshot = currentInput,
                    siteLanguage = settings.languageFor(activeSiteId),
                    generation = generation,
                )
                SearchMode.CROSS_SITE -> runCrossSiteSearch(
                    targetSiteIds = selectedCross,
                    query = trimmedQuery,
                    requestedSort = currentInput.sort.takeUnless { it == FeedSort.DEFAULT } ?: FeedSort.RELEVANCE,
                    generation = generation,
                )
            }
        }
    }

    fun loadNextSingleSitePage() = singleSiteRunner.loadNextPage()

    private fun runSingleSiteSearch(
        targetSiteId: SiteId,
        query: String,
        inputSnapshot: SearchInputState,
        siteLanguage: ComicLanguage,
        generation: Long = searchGeneration,
    ) = singleSiteRunner.start(
        targetSiteId = targetSiteId,
        query = query,
        inputSnapshot = inputSnapshot,
        siteLanguage = siteLanguage,
        generation = generation,
    )

    private fun runCrossSiteSearch(
        targetSiteIds: List<SiteId>,
        query: String,
        requestedSort: FeedSort,
        generation: Long = searchGeneration,
    ) = crossSiteRunner.start(
        targetSiteIds = targetSiteIds,
        query = query,
        requestedSort = requestedSort,
        generation = generation,
    )

    fun retryCrossSite(siteId: SiteId) = crossSiteRunner.retry(siteId)

    fun loadMoreCrossSite(siteId: SiteId) = crossSiteRunner.loadMore(siteId)

    private fun buildServerExcludeFilters(
        plugin: SitePlugin,
        baseFilters: AdvancedSearchFilters,
        effectiveBlockedTerms: Collection<String>,
    ): AdvancedSearchFilters =
        serverExcludeFiltersFor(plugin, baseFilters, effectiveBlockedTerms, tagDictionary)

    private fun interleaveBuckets(buckets: List<CrossSiteBucketState>): List<MediaItem> =
        interleaveCrossSiteBuckets(buckets)

    private fun resolveActiveSiteId(preferred: SiteId): SiteId =
        resolveActiveSite(preferred, siteRegistry, allDescriptors)

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
        ): List<String> = BlockedTermExcludeInjection.merge(
            existingExcludeTags = existingExcludeTags,
            effectiveBlockedTerms = effectiveBlockedTerms,
            tagDictionary = tagDictionary,
            maxTerms = maxTerms,
        )

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
