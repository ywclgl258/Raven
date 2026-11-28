package com.raven.app.feature.browse

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Cross-site search pipeline: one concurrent bucket per searchable site (at most
 * [SearchViewModel.MAX_CONCURRENT_CROSS_SITE_SEARCHES] in flight), per-site retry and paged
 * "load more", all guarded by the owning ViewModel's monotonic intent generation so a superseded
 * submit can neither cancel a newer run nor publish stale results.
 *
 * Extracted from [SearchViewModel]; the ViewModel reads [state] into its UI state and keeps thin
 * `retryCrossSite` / `loadMoreCrossSite` delegates.
 */
internal class CrossSiteSearchRunner(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val siteRegistry: SiteRegistry,
    private val preferences: PreferencesRepository,
    private val blockedTerms: BlockedTermsRepository,
    private val tagDictionary: TagDictionary,
    private val inputState: () -> SearchInputState,
    private val currentGeneration: () -> Long,
) {
    private val _state = MutableStateFlow(CrossSiteResultState())
    val state: StateFlow<CrossSiteResultState> = _state.asStateFlow()

    private val semaphore = Semaphore(permits = SearchViewModel.MAX_CONCURRENT_CROSS_SITE_SEARCHES)
    private var searchJob: Job? = null
    private val perSiteJobs = mutableMapOf<SiteId, Job>()

    /** Drops all buckets and in-flight work (used by `clearQuery` and mode switches). */
    fun reset() {
        searchJob?.cancel()
        cancelAll()
        _state.value = CrossSiteResultState()
    }

    fun retry(siteId: SiteId) {
        val query = inputState().submittedQuery.trim().ifBlank { inputState().query.trim() }
        if (query.isEmpty()) return
        val plugin = siteRegistry.get(siteId) ?: return
        val searchSource = plugin.searchSource ?: return

        val runGeneration = currentGeneration()
        perSiteJobs[siteId]?.cancel()
        perSiteJobs[siteId] = scope.launch {
            if (runGeneration != currentGeneration()) return@launch
            updateBucket(siteId) { existing ->
                (existing ?: CrossSiteBucketState(
                    site = plugin.descriptor,
                    isDirectorySearch = searchSource.isDirectorySearch,
                )).copy(
                    status = CrossSiteStatus.LOADING,
                    errorMessage = null,
                    isLoadingMore = false,
                )
            }
            val requestedSort = inputState().sort.takeUnless { it == FeedSort.DEFAULT } ?: FeedSort.RELEVANCE
            val resultBucket = executeQuery(
                plugin = plugin,
                query = query,
                requestedSort = requestedSort,
                page = 1,
                cursor = null,
            )
            if (runGeneration != currentGeneration()) return@launch
            updateBucket(siteId) { resultBucket }
        }
    }

    fun loadMore(siteId: SiteId) {
        val query = inputState().submittedQuery.trim()
        if (query.isEmpty()) return
        val existing = _state.value.bucketsBySite[siteId] ?: return
        if (!existing.hasMore || existing.isLoadingMore) return
        val plugin = siteRegistry.get(siteId) ?: return
        val searchSource = plugin.searchSource ?: return
        val requestedSort = _state.value.fetchedSort
            ?: inputState().sort.takeUnless { it == FeedSort.DEFAULT }
            ?: FeedSort.RELEVANCE
        val siteSort = SearchViewModel.resolveCompatibleServerSort(
            nativeSorts = SearchViewModel.resolveNativeSearchSorts(plugin, SearchScope.ALL),
            requested = requestedSort,
        )

        val runGeneration = currentGeneration()
        perSiteJobs[siteId]?.cancel()
        perSiteJobs[siteId] = scope.launch {
            if (runGeneration != currentGeneration()) return@launch
            updateBucket(siteId) { bucket ->
                bucket?.copy(isLoadingMore = true, errorMessage = null) ?: existing
            }
            val nextPageNumber = existing.nextPage ?: (existing.currentPage + 1)
            val result = semaphore.withPermit {
                try {
                    val settings = preferences.currentSettings()
                    val lang = requestLanguage(plugin, inputState(), settings.languageFor(siteId))
                    val effectiveBlocked = blockedTerms.observeEffectiveTermsBySite(siteId).first()
                    val crossFilters = serverExcludeFiltersFor(
                        plugin = plugin,
                        baseFilters = AdvancedSearchFilters(),
                        effectiveBlockedTerms = effectiveBlocked,
                        tagDictionary = tagDictionary,
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
            if (runGeneration != currentGeneration()) return@launch
            result.fold(
                onSuccess = { feedPage ->
                    updateBucket(siteId) { current ->
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
                    updateBucket(siteId) { current ->
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

    fun start(
        targetSiteIds: List<SiteId>,
        query: String,
        requestedSort: FeedSort,
        generation: Long,
    ) {
        // A stale caller must not cancel current jobs nor seed buckets for a superseded intent.
        if (generation != currentGeneration()) return
        searchJob?.cancel()
        cancelAll()

        val plugins = targetSiteIds.mapNotNull { siteRegistry.get(it) }.filter { it.searchSource != null }
        if (plugins.isEmpty()) {
            _state.value = CrossSiteResultState(
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
        _state.value = CrossSiteResultState(
            hasSearched = true,
            fetchedSort = requestedSort,
            bucketsBySite = initialBuckets,
        )

        searchJob = scope.launch {
            if (generation != currentGeneration()) return@launch
            coroutineScope {
                plugins.map { plugin ->
                    async {
                        val bucket = executeQuery(
                            plugin = plugin,
                            query = query,
                            requestedSort = requestedSort,
                            page = 1,
                            cursor = null,
                        )
                        // Skip publishing results from a superseded generation.
                        if (generation == currentGeneration()) {
                            updateBucket(plugin.id) { bucket }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun executeQuery(
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
        val siteSort = SearchViewModel.resolveCompatibleServerSort(
            nativeSorts = SearchViewModel.resolveNativeSearchSorts(plugin, SearchScope.ALL),
            requested = requestedSort,
        )

        return semaphore.withPermit {
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

                val settings = preferences.currentSettings()
                val language = requestLanguage(plugin, inputState(), settings.languageFor(plugin.id))
                val effectiveBlocked = blockedTerms.observeEffectiveTermsBySite(plugin.id).first()
                val crossFilters = serverExcludeFiltersFor(
                    plugin = plugin,
                    baseFilters = AdvancedSearchFilters(),
                    effectiveBlockedTerms = effectiveBlocked,
                    tagDictionary = tagDictionary,
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

    private fun updateBucket(
        siteId: SiteId,
        transform: (CrossSiteBucketState?) -> CrossSiteBucketState,
    ) {
        _state.update { current ->
            val nextMap = LinkedHashMap(current.bucketsBySite)
            nextMap[siteId] = transform(nextMap[siteId])
            current.copy(bucketsBySite = nextMap)
        }
    }

    fun cancelAll() {
        perSiteJobs.values.forEach { it.cancel() }
        perSiteJobs.clear()
    }


}
