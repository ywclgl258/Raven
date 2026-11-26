package com.raven.app.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.model.BrowsePosition
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BrowsePositionRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSection
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.WatchProgress
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Complete reactive UI state for [HomeScreen], adapting cleanly between
 * Netflix Cinema Home ([MediaKind.VIDEO]) and Frameless Masonry Home ([MediaKind.COMIC] / [MediaKind.PHOTO]).
 */
data class HomeUiState(
    val activeSite: SiteDescriptor? = null,
    val allSites: List<SiteDescriptor> = emptyList(),
    val pinnedSiteIds: List<SiteId> = emptyList(),
    val siteOpenCounts: Map<SiteId, Int> = emptyMap(),
    val startupDefaultSiteId: SiteId? = null,
    val comicLanguage: ComicLanguage = ComicLanguage.ALL,
    val supportsLanguageFilter: Boolean = false,
    val translateTags: Boolean = true,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val authGateRequired: Boolean = false,
    val errorMessage: String? = null,
    val paginationErrorMessage: String? = null,
    val heroItems: List<MediaItem> = emptyList(),
    val sections: List<FeedSection> = emptyList(),
    val primaryItems: List<MediaItem> = emptyList(),
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val continueWatching: List<WatchProgress> = emptyList(),
    val resumeReading: ReadingProgress? = null,
    val watchProgressMap: Map<String, Float> = emptyMap(),
    val readingProgressMap: Map<String, Float> = emptyMap(),
    val watchLaterKeys: Set<String> = emptySet(),
    val showSiteSwitchTutorial: Boolean = false,
    val restoredPosition: BrowsePosition? = null,
    val fromCache: Boolean = false,
) {
    val isVideoHome: Boolean
        get() = activeSite?.primaryKind == MediaKind.VIDEO

    val isEmpty: Boolean
        get() = heroItems.isEmpty() && sections.isEmpty() && primaryItems.isEmpty()
}

private data class HomeFeedLoadState(
    val loadedKey: Pair<SiteId, ComicLanguage>? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val authGateRequired: Boolean = false,
    val errorMessage: String? = null,
    val paginationErrorMessage: String? = null,
    val rawHeroItems: List<MediaItem> = emptyList(),
    val rawSections: List<FeedSection> = emptyList(),
    val rawPrimaryItems: List<MediaItem> = emptyList(),
    val paginationBaseRequest: FeedRequest? = null,
    val currentPage: Int = 1,
    val nextPage: Int? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val fromCache: Boolean = false,
)

private data class SiteLocalContext(
    val blockedTerms: List<String>,
    val cachedTagsByKey: Map<String, List<TagRef>>,
    val watchProgress: List<WatchProgress>,
    val readingProgress: List<ReadingProgress>,
    val watchLaterKeys: Set<String>,
    val browsePosition: BrowsePosition?,
    val dictRevision: Long = 0L,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val siteRegistry: SiteRegistry,
    private val preferencesRepository: PreferencesRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val historyRepository: HistoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val shelfRepository: ShelfRepository,
    private val browsePositionRepository: BrowsePositionRepository,
    private val dispatchers: AppDispatchers,
    private val tagDictionary: TagDictionary = TagDictionary.default,
    private val mediaTagCacheRepository: MediaTagCacheRepository? = null,
    private val tagDictionaryRepository: TagDictionaryRepository? = null,
) : ViewModel() {

    private val allDescriptors: List<SiteDescriptor> = siteRegistry.allDescriptors()
    private val feedState = MutableStateFlow(HomeFeedLoadState())
    private val showSiteSwitchTutorial = MutableStateFlow(false)
    private val dismissedRestoreKey = MutableStateFlow<String?>(null)

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

    private val resolvedSiteKeyFlow = preferencesRepository.settings
        .map { settings ->
            val siteId = resolveActiveSiteId(settings.activeSiteId)
            val descriptor = siteRegistry.get(siteId)?.descriptor
            val supportsLang = descriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true
            val language = if (supportsLang) settings.languageFor(siteId) else ComicLanguage.ALL
            siteId to language
        }
        .distinctUntilChanged()

    private val siteLocalContextFlow = resolvedSiteKeyFlow.flatMapLatest { (siteId, language) ->
        val cachedTagsFlow = mediaTagCacheRepository?.observeTagsBySite(siteId) ?: flowOf(emptyMap())
        val baseFlow = combine(
            blockedTermsRepository.observeEffectiveTermsBySite(siteId),
            cachedTagsFlow,
            historyRepository.observeWatchProgressBySite(siteId),
            historyRepository.observeReadingProgressBySite(siteId),
            shelfRepository.observeShelfItemKeys(Shelf.WATCH_LATER_ID),
        ) { blockedTerms, cachedTags, watches, readings, watchLaterKeys ->
            SiteLocalContext(
                blockedTerms = blockedTerms,
                cachedTagsByKey = cachedTags,
                watchProgress = watches,
                readingProgress = readings,
                watchLaterKeys = watchLaterKeys,
                browsePosition = null,
            )
        }
        combine(
            baseFlow,
            browsePositionRepository.observe(homePositionKey(siteId, language)),
            dictRevisionFlow,
        ) { base, browsePos, dictRevision ->
            base.copy(browsePosition = browsePos, dictRevision = dictRevision)
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        preferencesRepository.settings,
        siteLocalContextFlow,
        feedState,
        showSiteSwitchTutorial,
        dismissedRestoreKey,
    ) { settings, local, network, tutorialVisible, dismissedKey ->
        val siteId = resolveActiveSiteId(settings.activeSiteId)
        val plugin = siteRegistry.get(siteId)
        val descriptor = plugin?.descriptor
        val supportsLanguage = descriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true
        val effectiveLanguage = if (supportsLanguage) settings.languageFor(siteId) else ComicLanguage.ALL
        val translateTags = settings.translateTags
        val expectedKey = siteId to effectiveLanguage
        val isAligned = network.loadedKey == expectedKey

        val blocked = local.blockedTerms
        val cachedTags = local.cachedTagsByKey
        val isItemAllowed: (MediaItem) -> Boolean = { item ->
            ContentFilter.isAllowed(
                item = item,
                blockedTerms = blocked,
                tagDictionary = tagDictionary,
                extraTags = cachedTags[item.compositeKey].orEmpty(),
            )
        }

        val filteredPrimary = if (isAligned) {
            network.rawPrimaryItems
                .filter(isItemAllowed)
                .withLocalizedTags(tagDictionary, translateTags)
        } else {
            emptyList()
        }

        val filteredSections = if (isAligned) {
            val allowedSections = network.rawSections.mapNotNull { section ->
                val allowed = section.items
                    .filter(isItemAllowed)
                    .withLocalizedTags(tagDictionary, translateTags)
                if (allowed.isEmpty()) null else section.copy(items = allowed)
            }
            settings.sortSectionsByUsage(siteId, allowedSections) { it.id }
        } else {
            emptyList()
        }

        val rawHero = if (isAligned) {
            network.rawHeroItems
                .filter(isItemAllowed)
                .withLocalizedTags(tagDictionary, translateTags)
        } else {
            emptyList()
        }
        val effectiveHero = if (descriptor?.primaryKind == MediaKind.VIDEO && rawHero.isEmpty()) {
            filteredPrimary.take(5)
        } else {
            rawHero
        }

        val continueWatching = local.watchProgress
            .filter { !it.completed && it.positionMs > 0L }
            .filter { wp ->
                isItemAllowed(
                    MediaItem(
                        siteId = wp.siteId,
                        id = wp.mediaId,
                        url = wp.url,
                        kind = MediaKind.VIDEO,
                        title = wp.title,
                        coverUrl = wp.coverUrl,
                    ),
                )
            }
            .take(12)

        val resumeReading = local.readingProgress
            .filter { !it.completed && it.currentPage > 0 }
            .firstOrNull { rp ->
                isItemAllowed(
                    MediaItem(
                        siteId = rp.siteId,
                        id = rp.mediaId,
                        url = rp.url,
                        kind = rp.kind,
                        title = rp.title,
                        coverUrl = rp.coverUrl,
                    ),
                )
            }

        val watchProgressMap = local.watchProgress.associate { it.mediaId to it.progressFraction }
        val readingProgressMap = local.readingProgress.associate { it.mediaId to it.progressFraction }

        val posKey = homePositionKey(siteId, effectiveLanguage)
        val restored = local.browsePosition?.takeIf {
            it.throughPage > 1 && dismissedKey != posKey
        }

        HomeUiState(
            activeSite = descriptor,
            allSites = settings.sortSitesByUsage(allDescriptors) { it.id },
            pinnedSiteIds = settings.pinnedSiteIds,
            siteOpenCounts = settings.siteOpenCounts,
            startupDefaultSiteId = settings.startupDefaultSiteId,
            comicLanguage = effectiveLanguage,
            supportsLanguageFilter = supportsLanguage,
            translateTags = translateTags,
            isLoading = !isAligned || network.isLoading,
            isRefreshing = isAligned && network.isRefreshing,
            isLoadingMore = isAligned && network.isLoadingMore,
            authGateRequired = isAligned && network.authGateRequired,
            errorMessage = if (isAligned) network.errorMessage else null,
            paginationErrorMessage = if (isAligned) network.paginationErrorMessage else null,
            heroItems = effectiveHero,
            sections = filteredSections,
            primaryItems = filteredPrimary,
            hasMore = isAligned && network.hasMore,
            currentPage = if (isAligned) network.currentPage else 1,
            continueWatching = continueWatching,
            resumeReading = resumeReading,
            watchProgressMap = watchProgressMap,
            readingProgressMap = readingProgressMap,
            watchLaterKeys = local.watchLaterKeys,
            showSiteSwitchTutorial = tutorialVisible,
            restoredPosition = restored,
            fromCache = isAligned && network.fromCache,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(
            activeSite = allDescriptors.firstOrNull(),
            allSites = allDescriptors,
            isLoading = true,
        ),
    )

    init {
        observeActiveSiteAndLoad()
        evaluateFirstRunTutorial()
    }

    private fun observeActiveSiteAndLoad() {
        viewModelScope.launch {
            resolvedSiteKeyFlow.collect { (siteId, language) ->
                triggerLoadHome(siteId = siteId, language = language, isRefresh = false)
            }
        }
    }

    private fun evaluateFirstRunTutorial() {
        viewModelScope.launch {
            val settings = preferencesRepository.settings.first()
            if (settings.siteSwitchTutorialSeen) return@launch
            val hasWatchHistory = historyRepository.observeWatchProgress(limit = 1).first().isNotEmpty()
            val hasReadingHistory = historyRepository.observeReadingProgress(limit = 1).first().isNotEmpty()
            val hasFavorites = favoriteRepository.observeAll().first().isNotEmpty()
            if (!hasWatchHistory && !hasReadingHistory && !hasFavorites) {
                val consumed = preferencesRepository.consumeSiteSwitchTutorial()
                if (consumed) {
                    showSiteSwitchTutorial.value = true
                }
            }
        }
    }

    fun selectSite(siteId: SiteId) {
        if (siteRegistry.get(siteId) == null) return
        showSiteSwitchTutorial.value = false
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

    fun selectComicLanguage(language: ComicLanguage) {
        viewModelScope.launch {
            val currentSiteId = resolveActiveSiteId(preferencesRepository.currentSettings().activeSiteId)
            preferencesRepository.setSiteLanguage(currentSiteId, language)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val settings = preferencesRepository.currentSettings()
            val siteId = resolveActiveSiteId(settings.activeSiteId)
            val descriptor = siteRegistry.get(siteId)?.descriptor
            val supportsLang = descriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true
            val language = if (supportsLang) settings.languageFor(siteId) else ComicLanguage.ALL
            val hasExisting = feedState.value.loadedKey == (siteId to language) &&
                (feedState.value.rawPrimaryItems.isNotEmpty() || feedState.value.rawHeroItems.isNotEmpty())
            triggerLoadHome(
                siteId = siteId,
                language = language,
                isRefresh = hasExisting,
            )
        }
    }

    fun loadNextPage() {
        val snapshot = feedState.value
        val loadedKey = snapshot.loadedKey ?: return
        if (snapshot.isLoading || snapshot.isLoadingMore || snapshot.authGateRequired || !snapshot.hasMore) {
            return
        }
        val (siteId, language) = loadedKey
        val plugin = siteRegistry.get(siteId) ?: return
        val feedSource = plugin.feedSource ?: return

        paginateJob?.cancel()
        paginateJob = viewModelScope.launch {
            feedState.update { it.copy(isLoadingMore = true, paginationErrorMessage = null) }
            val nextPageNumber = snapshot.nextPage ?: (snapshot.currentPage + 1)
            val baseRequest = snapshot.paginationBaseRequest ?: FeedRequest(
                siteId = siteId,
                section = "latest",
                sort = FeedSort.NEWEST,
                language = language,
            )
            val request = baseRequest.copy(
                siteId = siteId,
                language = language,
                page = nextPageNumber,
                cursor = snapshot.nextCursor,
            )
            try {
                val page = withContext(dispatchers.io) { feedSource.loadFeed(request) }
                feedState.update { state ->
                    if (state.loadedKey != loadedKey) return@update state
                    val merged = mergeUniqueMediaItems(state.rawPrimaryItems, page.items)
                    val progressed = merged.size > state.rawPrimaryItems.size ||
                        (page.nextCursor != null && page.nextCursor != state.nextCursor)
                    state.copy(
                        isLoadingMore = false,
                        paginationErrorMessage = null,
                        rawPrimaryItems = merged,
                        currentPage = nextPageNumber,
                        nextPage = page.nextPage,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore && progressed,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                feedState.update { state ->
                    if (state.loadedKey != loadedKey) return@update state
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

    fun dismissSiteSwitchTutorial() {
        showSiteSwitchTutorial.value = false
    }

    fun saveBrowsePosition(
        workId: String,
        throughPage: Int = feedState.value.currentPage,
        shelfId: String = "",
    ) {
        val cleanWorkId = workId.trim()
        if (cleanWorkId.isEmpty()) return
        browsePositionJob?.cancel()
        browsePositionJob = viewModelScope.launch {
            delay(BROWSE_POSITION_DEBOUNCE_MS)
            val settings = preferencesRepository.currentSettings()
            val siteId = resolveActiveSiteId(settings.activeSiteId)
            val supportsLang = siteRegistry.get(siteId)?.descriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true
            val language = if (supportsLang) settings.languageFor(siteId) else ComicLanguage.ALL
            val key = homePositionKey(siteId, language)
            browsePositionRepository.save(
                BrowsePosition(
                    positionKey = key,
                    siteId = siteId,
                    workId = cleanWorkId,
                    throughPage = throughPage.coerceAtLeast(1),
                    shelfId = shelfId,
                    updatedAt = 0L,
                ),
            )
        }
    }

    fun dismissRestoredPosition() {
        viewModelScope.launch {
            val settings = preferencesRepository.currentSettings()
            val siteId = resolveActiveSiteId(settings.activeSiteId)
            val supportsLang = siteRegistry.get(siteId)?.descriptor?.hasCapability(SiteCapability.LANGUAGE_FILTER) == true
            val language = if (supportsLang) settings.languageFor(siteId) else ComicLanguage.ALL
            dismissedRestoreKey.value = homePositionKey(siteId, language)
        }
    }

    private fun triggerLoadHome(
        siteId: SiteId,
        language: ComicLanguage,
        isRefresh: Boolean,
    ) {
        val key = siteId to language
        loadJob?.cancel()
        paginateJob?.cancel()
        loadJob = viewModelScope.launch {
            val plugin = siteRegistry.get(siteId)
            val feedSource = plugin?.feedSource
            if (plugin == null || feedSource == null) {
                feedState.value = HomeFeedLoadState(
                    loadedKey = key,
                    isLoading = false,
                    errorMessage = "当前站点不支持首页浏览",
                )
                return@launch
            }

            feedState.update { current ->
                if (isRefresh && current.loadedKey == key) {
                    current.copy(
                        isRefreshing = true,
                        isLoading = false,
                        errorMessage = null,
                        paginationErrorMessage = null,
                    )
                } else {
                    HomeFeedLoadState(
                        loadedKey = key,
                        isLoading = true,
                    )
                }
            }

            try {
                if (plugin.authProvider.requiresAuthGate) {
                    val authState = withContext(dispatchers.io) {
                        plugin.authProvider.currentAuthState()
                    }
                    if (!authState.isAuthenticated) {
                        feedState.value = HomeFeedLoadState(
                            loadedKey = key,
                            isLoading = false,
                            isRefreshing = false,
                            authGateRequired = true,
                        )
                        return@launch
                    }
                }

                val homeFeed = withContext(dispatchers.io) { feedSource.loadHome(language) }
                val primaryPage = homeFeed.primaryFeedPage
                val primaryItems = primaryPage?.items
                    ?: homeFeed.sections.lastOrNull { !it.isRanked }?.items
                    ?: emptyList()
                val baseRequest = homeFeed.sections
                    .lastOrNull { !it.isRanked && it.seeAllRequest != null }
                    ?.seeAllRequest
                    ?: FeedRequest(
                        siteId = siteId,
                        section = "latest",
                        sort = FeedSort.NEWEST,
                        language = language,
                    )

                feedState.value = HomeFeedLoadState(
                    loadedKey = key,
                    isLoading = false,
                    isRefreshing = false,
                    authGateRequired = false,
                    rawHeroItems = homeFeed.heroItems.distinctBy { it.compositeKey },
                    rawSections = homeFeed.sections,
                    rawPrimaryItems = primaryItems.distinctBy { it.compositeKey },
                    paginationBaseRequest = baseRequest,
                    currentPage = 1,
                    nextPage = primaryPage?.nextPage,
                    nextCursor = primaryPage?.nextCursor,
                    hasMore = primaryPage?.hasMore ?: false,
                    fromCache = homeFeed.fromCache,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                val isAuth = error is SiteException.AuthRequired
                feedState.update { current ->
                    if (isRefresh && current.loadedKey == key && !isAuth) {
                        current.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = error.toBrowseErrorMessage(),
                        )
                    } else {
                        HomeFeedLoadState(
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

    private fun resolveActiveSiteId(preferred: SiteId): SiteId =
        if (siteRegistry.get(preferred) != null) {
            preferred
        } else {
            allDescriptors.firstOrNull()?.id ?: preferred
        }

    companion object {
        fun homePositionKey(siteId: SiteId, language: ComicLanguage): String =
            "home:${siteId.value}:${language.name}"
    }
}
