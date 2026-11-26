package com.raven.app.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.MediaDateParser
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.model.BrowsePosition
import com.raven.app.core.database.model.ExploreFilterState
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BrowsePositionRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reactive UI state for [ExploreScreen].
 */
data class ExploreUiState(
    val activeSite: SiteDescriptor? = null,
    val allSites: List<SiteDescriptor> = emptyList(),
    val pinnedSiteIds: List<SiteId> = emptyList(),
    val siteOpenCounts: Map<SiteId, Int> = emptyMap(),
    val startupDefaultSiteId: SiteId? = null,
    val filter: ExploreFilterState = ExploreFilterState(),
    val availableSections: List<SiteSectionSpec> = emptyList(),
    val availableSorts: List<FeedSort> = listOf(FeedSort.NEWEST),
    val directorySections: List<SiteSectionSpec> = emptyList(),
    val supportsLanguageFilter: Boolean = false,
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
    val pageTitle: String? = null,
) {
    val selectedSectionSpec: SiteSectionSpec?
        get() = availableSections.firstOrNull { spec ->
            val matchSection = !filter.section.isNullOrBlank() && spec.id == filter.section
            val matchCategory = !filter.category.isNullOrBlank() && spec.category == filter.category
            matchSection || matchCategory
        }

    val hasActiveFilter: Boolean
        get() = !filter.isEmpty

    val activeFilterSummary: List<String>
        get() = buildList {
            selectedSectionSpec?.let { add(it.title) }
                ?: filter.category?.takeIf { it.isNotBlank() }?.let { add(it) }
                ?: filter.section?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (filter.sort != FeedSort.DEFAULT) {
                add(filter.sort.displayLabel())
            }
            if (supportsLanguageFilter && filter.language != ComicLanguage.ALL) {
                add(
                    when (filter.language) {
                        ComicLanguage.ALL -> "全部"
                        ComicLanguage.CHINESE -> "中文"
                        ComicLanguage.JAPANESE -> "日文"
                        ComicLanguage.CHINESE_AND_JAPANESE -> "中＋日"
                    },
                )
            }
        }
}

private data class ExploreLoadKey(
    val siteId: SiteId,
    val filter: ExploreFilterState,
)

private data class ExploreLoadState(
    val loadedKey: ExploreLoadKey? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val authGateRequired: Boolean = false,
    val errorMessage: String? = null,
    val paginationErrorMessage: String? = null,
    val rawItems: List<MediaItem> = emptyList(),
    val currentPage: Int = 1,
    val nextPage: Int? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val pageTitle: String? = null,
)

private data class ExploreLocalContext(
    val blockedTerms: List<String>,
    val cachedTagsByKey: Map<String, List<TagRef>>,
    val watchProgressMap: Map<String, Float>,
    val readingProgressMap: Map<String, Float>,
    val watchLaterKeys: Set<String>,
    val dictRevision: Long = 0L,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModel(
    private val siteRegistry: SiteRegistry,
    private val preferencesRepository: PreferencesRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val historyRepository: HistoryRepository,
    private val shelfRepository: ShelfRepository,
    private val browsePositionRepository: BrowsePositionRepository,
    private val dispatchers: AppDispatchers,
    private val tagDictionary: TagDictionary = TagDictionary.default,
    private val mediaTagCacheRepository: MediaTagCacheRepository? = null,
    private val tagDictionaryRepository: TagDictionaryRepository? = null,
) : ViewModel() {

    private val allDescriptors: List<SiteDescriptor> = siteRegistry.allDescriptors()
    private val loadState = MutableStateFlow(ExploreLoadState())
    private val filterEditMutex = Mutex()

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

    private val dictRevisionFlow = tagDictionaryRepository?.observeRevision() ?: flowOf(0L)

    private val loadKeyFlow = preferencesRepository.settings
        .map { settings ->
            val siteId = resolveActiveSiteId(settings.activeSiteId)
            val plugin = siteRegistry.get(siteId)
            val normalizedFilter = normalizeFilterForPlugin(
                plugin = plugin,
                stored = settings.exploreFilterFor(siteId),
                siteLanguage = settings.languageFor(siteId),
            )
            ExploreLoadKey(siteId = siteId, filter = normalizedFilter)
        }
        .distinctUntilChanged()

    private val localContextFlow = loadKeyFlow
        .map { it.siteId }
        .distinctUntilChanged()
        .flatMapLatest { siteId ->
            val cachedTagsFlow = mediaTagCacheRepository?.observeTagsBySite(siteId) ?: flowOf(emptyMap())
            val base = combine(
                blockedTermsRepository.observeEffectiveTermsBySite(siteId),
                cachedTagsFlow,
                historyRepository.observeWatchProgressBySite(siteId),
                historyRepository.observeReadingProgressBySite(siteId),
                shelfRepository.observeShelfItemKeys(Shelf.WATCH_LATER_ID),
            ) { blocked, cachedTags, watches, readings, watchLater ->
                ExploreLocalContext(
                    blockedTerms = blocked,
                    cachedTagsByKey = cachedTags,
                    watchProgressMap = watches.associate { it.mediaId to it.progressFraction },
                    readingProgressMap = readings.associate { it.mediaId to it.progressFraction },
                    watchLaterKeys = watchLater,
                )
            }
            combine(base, dictRevisionFlow) { ctx, rev -> ctx.copy(dictRevision = rev) }
        }

    val uiState: StateFlow<ExploreUiState> = combine(
        preferencesRepository.settings,
        loadKeyFlow,
        localContextFlow,
        loadState,
    ) { settings, key, local, network ->
        val plugin = siteRegistry.get(key.siteId)
        val descriptor = plugin?.descriptor
        val supportsLang = descriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true
        val feedSections = settings.sortSectionsByUsage(
            key.siteId,
            descriptor?.sections?.filterNot { it.isDirectory }.orEmpty(),
        ) { it.id }
        val directorySections = settings.sortSectionsByUsage(
            key.siteId,
            buildDirectorySections(plugin),
        ) { it.id }
        val request = buildFeedRequest(plugin, key.siteId, key.filter, page = 1, cursor = null)
        val availableSorts = plugin?.feedSource?.supportedSorts(request)
            ?.takeIf { it.isNotEmpty() }
            ?: descriptor?.feedSorts?.takeIf { it.isNotEmpty() }
            ?: listOf(FeedSort.NEWEST)

        val selectedSpec = feedSections.firstOrNull { spec ->
            (!key.filter.section.isNullOrBlank() && spec.id == key.filter.section) ||
                (!key.filter.category.isNullOrBlank() && spec.category == key.filter.category)
        }
        val effectiveKind = selectedSpec?.mediaKind ?: descriptor?.primaryKind ?: MediaKind.VIDEO
        val aligned = network.loadedKey == key
        val filteredItems = if (aligned) {
            val adv = key.filter.advancedFilters
            network.rawItems
                .filter { item ->
                    ContentFilter.isAllowed(
                        item = item,
                        blockedTerms = local.blockedTerms,
                        tagDictionary = tagDictionary,
                        extraTags = local.cachedTagsByKey[item.compositeKey].orEmpty(),
                    ) && MediaDateParser.matchesDateRange(
                        dateLabel = item.dateLabel,
                        publishedFrom = adv.publishedFrom,
                        publishedTo = adv.publishedTo,
                    )
                }
                .withLocalizedTags(tagDictionary, settings.translateTags)
        } else {
            emptyList()
        }

        ExploreUiState(
            activeSite = descriptor,
            allSites = settings.sortSitesByUsage(allDescriptors) { it.id },
            pinnedSiteIds = settings.pinnedSiteIds,
            siteOpenCounts = settings.siteOpenCounts,
            startupDefaultSiteId = settings.startupDefaultSiteId,
            filter = key.filter,
            availableSections = feedSections,
            availableSorts = availableSorts,
            directorySections = directorySections,
            supportsLanguageFilter = supportsLang,
            effectiveMediaKind = effectiveKind,
            items = filteredItems,
            watchProgressMap = local.watchProgressMap,
            readingProgressMap = local.readingProgressMap,
            watchLaterKeys = local.watchLaterKeys,
            isLoading = !aligned || network.isLoading,
            isRefreshing = aligned && network.isRefreshing,
            isLoadingMore = aligned && network.isLoadingMore,
            authGateRequired = aligned && network.authGateRequired,
            errorMessage = if (aligned) network.errorMessage else null,
            paginationErrorMessage = if (aligned) network.paginationErrorMessage else null,
            hasMore = aligned && network.hasMore,
            currentPage = if (aligned) network.currentPage else 1,
            pageTitle = if (aligned) network.pageTitle else null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ExploreUiState(
            activeSite = allDescriptors.firstOrNull(),
            allSites = allDescriptors,
            isLoading = true,
        ),
    )

    init {
        viewModelScope.launch {
            loadKeyFlow.collect { key ->
                triggerLoad(key = key, isRefresh = false)
            }
        }
    }

    fun selectSite(siteId: SiteId) {
        if (siteRegistry.get(siteId) == null) return
        viewModelScope.launch {
            preferencesRepository.selectActiveSiteAndRecordOpen(siteId)
        }
    }

    fun togglePinnedSite(siteId: SiteId) {
        viewModelScope.launch {
            preferencesRepository.togglePinnedSite(siteId)
        }
    }

    fun setStartupDefaultSiteId(siteId: SiteId?) {
        if (siteId != null && siteRegistry.get(siteId) == null) return
        viewModelScope.launch {
            preferencesRepository.setStartupDefaultSiteId(siteId)
        }
    }

    fun selectSection(section: SiteSectionSpec?) {
        mutateActiveFilter(recordedSectionId = section?.id) { current ->
            current.copy(
                section = section?.id,
                category = section?.category?.takeIf { it.isNotBlank() },
                sort = if (section != null && current.sort == FeedSort.DEFAULT) {
                    section.defaultSort
                } else {
                    current.sort
                },
            )
        }
    }

    fun selectSort(sort: FeedSort) {
        mutateActiveFilter { current -> current.copy(sort = sort) }
    }

    fun selectLanguage(language: ComicLanguage) {
        mutateActiveFilter(alsoSetLanguage = language) { current ->
            current.copy(language = language)
        }
    }

    fun clearFilters() {
        viewModelScope.launch {
            filterEditMutex.withLock {
                val settings = preferencesRepository.currentSettings()
                val siteId = resolveActiveSiteId(settings.activeSiteId)
                val supportsLang = siteRegistry.get(siteId)?.descriptor
                    ?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true
                if (supportsLang && settings.languageFor(siteId) != ComicLanguage.ALL) {
                    preferencesRepository.setSiteLanguage(siteId, ComicLanguage.ALL)
                }
                preferencesRepository.setSiteExploreFilter(siteId, null)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val settings = preferencesRepository.currentSettings()
            val siteId = resolveActiveSiteId(settings.activeSiteId)
            val plugin = siteRegistry.get(siteId)
            val normalized = normalizeFilterForPlugin(
                plugin = plugin,
                stored = settings.exploreFilterFor(siteId),
                siteLanguage = settings.languageFor(siteId),
            )
            val key = ExploreLoadKey(siteId = siteId, filter = normalized)
            val hasExisting = loadState.value.loadedKey == key && loadState.value.rawItems.isNotEmpty()
            triggerLoad(key = key, isRefresh = hasExisting)
        }
    }

    private fun mutateActiveFilter(
        alsoSetLanguage: ComicLanguage? = null,
        recordedSectionId: String? = null,
        transform: (ExploreFilterState) -> ExploreFilterState,
    ) {
        viewModelScope.launch {
            filterEditMutex.withLock {
                val settings = preferencesRepository.currentSettings()
                val siteId = resolveActiveSiteId(settings.activeSiteId)
                val current = settings.exploreFilterFor(siteId)
                val next = transform(current)
                if (alsoSetLanguage != null) {
                    preferencesRepository.setSiteLanguage(siteId, alsoSetLanguage)
                }
                preferencesRepository.setSiteExploreFilterAndRecordSection(
                    siteId = siteId,
                    filter = next.takeUnless { it.isEmpty },
                    recordedSectionId = recordedSectionId,
                )
            }
        }
    }

    fun loadNextPage() {
        val snapshot = loadState.value
        val key = snapshot.loadedKey ?: return
        if (snapshot.isLoading || snapshot.isLoadingMore || snapshot.authGateRequired || !snapshot.hasMore) {
            return
        }
        val plugin = siteRegistry.get(key.siteId) ?: return
        val feedSource = plugin.feedSource ?: return

        paginateJob?.cancel()
        paginateJob = viewModelScope.launch {
            loadState.update { it.copy(isLoadingMore = true, paginationErrorMessage = null) }
            val nextPageNumber = snapshot.nextPage ?: (snapshot.currentPage + 1)
            val request = buildFeedRequest(
                plugin = plugin,
                siteId = key.siteId,
                filter = key.filter,
                page = nextPageNumber,
                cursor = snapshot.nextCursor,
            )
            try {
                val page = withContext(dispatchers.io) { feedSource.loadFeed(request) }
                loadState.update { state ->
                    if (state.loadedKey != key) return@update state
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
                loadState.update { state ->
                    if (state.loadedKey != key) return@update state
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
        val cleanWorkId = workId.trim()
        if (cleanWorkId.isEmpty()) return
        val siteId = uiState.value.activeSite?.id ?: return
        browsePositionJob?.cancel()
        browsePositionJob = viewModelScope.launch {
            delay(BROWSE_POSITION_DEBOUNCE_MS)
            browsePositionRepository.save(
                BrowsePosition(
                    positionKey = "explore:${siteId.value}",
                    siteId = siteId,
                    workId = cleanWorkId,
                    throughPage = throughPage.coerceAtLeast(1),
                    updatedAt = 0L,
                ),
            )
        }
    }

    private fun triggerLoad(key: ExploreLoadKey, isRefresh: Boolean) {
        loadJob?.cancel()
        paginateJob?.cancel()
        loadJob = viewModelScope.launch {
            val plugin = siteRegistry.get(key.siteId)
            val feedSource = plugin?.feedSource
            if (plugin == null || feedSource == null) {
                loadState.value = ExploreLoadState(
                    loadedKey = key,
                    isLoading = false,
                    errorMessage = "当前站点不支持发现浏览",
                )
                return@launch
            }

            loadState.update { current ->
                if (isRefresh && current.loadedKey == key) {
                    current.copy(
                        isRefreshing = true,
                        isLoading = false,
                        errorMessage = null,
                        paginationErrorMessage = null,
                    )
                } else {
                    ExploreLoadState(loadedKey = key, isLoading = true)
                }
            }

            try {
                if (plugin.authProvider.requiresAuthGate) {
                    val auth = withContext(dispatchers.io) { plugin.authProvider.currentAuthState() }
                    if (!auth.isAuthenticated) {
                        loadState.value = ExploreLoadState(
                            loadedKey = key,
                            isLoading = false,
                            isRefreshing = false,
                            authGateRequired = true,
                        )
                        return@launch
                    }
                }

                val request = buildFeedRequest(
                    plugin = plugin,
                    siteId = key.siteId,
                    filter = key.filter,
                    page = 1,
                    cursor = null,
                )
                val page = withContext(dispatchers.io) { feedSource.loadFeed(request) }
                loadState.value = ExploreLoadState(
                    loadedKey = key,
                    isLoading = false,
                    isRefreshing = false,
                    authGateRequired = false,
                    rawItems = page.items.distinctBy { it.compositeKey },
                    currentPage = 1,
                    nextPage = page.nextPage,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                    pageTitle = page.title,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                val isAuth = error is SiteException.AuthRequired
                loadState.update { current ->
                    if (isRefresh && current.loadedKey == key && !isAuth) {
                        current.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = error.toBrowseErrorMessage(),
                        )
                    } else {
                        ExploreLoadState(
                            loadedKey = key,
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

    private fun normalizeFilterForPlugin(
        plugin: SitePlugin?,
        stored: ExploreFilterState,
        siteLanguage: ComicLanguage,
    ): ExploreFilterState {
        val descriptor = plugin?.descriptor ?: return stored
        val supportsLang = descriptor.hasCapability(SiteCapability.LANGUAGE_FILTER)
        val effectiveLanguage = when {
            !supportsLang -> ComicLanguage.ALL
            stored.language != ComicLanguage.ALL -> stored.language
            else -> siteLanguage
        }
        val validSectionIds = descriptor.sections.filterNot { it.isDirectory }.mapTo(HashSet()) { it.id }
        val validCategories = descriptor.sections.mapNotNull { it.category.takeIf(String::isNotBlank) }.toSet()
        val storedSection = stored.section
        val storedCategory = stored.category
        val safeSection = if (storedSection != null && (validSectionIds.isEmpty() || storedSection in validSectionIds)) {
            storedSection
        } else {
            null
        }
        val safeCategory = if (storedCategory != null && (validCategories.isEmpty() || storedCategory in validCategories)) {
            storedCategory
        } else {
            null
        }
        return stored.copy(
            section = safeSection,
            category = safeCategory,
            language = effectiveLanguage,
        )
    }

    private fun buildFeedRequest(
        plugin: SitePlugin?,
        siteId: SiteId,
        filter: ExploreFilterState,
        page: Int,
        cursor: String?,
    ): FeedRequest {
        val descriptor = plugin?.descriptor
        val sections = descriptor?.sections?.filterNot { it.isDirectory }.orEmpty()
        val matchedSpec = sections.firstOrNull { spec ->
            (!filter.section.isNullOrBlank() && spec.id == filter.section) ||
                (!filter.category.isNullOrBlank() && spec.category == filter.category)
        }
        val effectiveSection = filter.section
            ?: sections.firstOrNull()?.id
            ?: "latest"
        val effectiveSort = if (filter.sort != FeedSort.DEFAULT) {
            filter.sort
        } else {
            matchedSpec?.defaultSort ?: descriptor?.feedSorts?.firstOrNull() ?: FeedSort.NEWEST
        }
        val effectiveLanguage = if (descriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true) {
            filter.language
        } else {
            ComicLanguage.ALL
        }
        return FeedRequest(
            siteId = siteId,
            section = effectiveSection,
            category = filter.category,
            sort = effectiveSort,
            language = effectiveLanguage,
            page = page,
            cursor = cursor,
            mediaKind = matchedSpec?.mediaKind,
            advancedFilters = filter.advancedFilters,
        )
    }

    private fun buildDirectorySections(plugin: SitePlugin?): List<SiteSectionSpec> {
        if (plugin == null) return emptyList()
        val fromSource = plugin.directorySource?.directorySections().orEmpty()
        if (fromSource.isNotEmpty()) return fromSource
        return plugin.descriptor.sections.filter { it.isDirectory }
    }

    private fun resolveActiveSiteId(preferred: SiteId): SiteId =
        if (siteRegistry.get(preferred) != null) {
            preferred
        } else {
            allDescriptors.firstOrNull()?.id ?: preferred
        }
}
