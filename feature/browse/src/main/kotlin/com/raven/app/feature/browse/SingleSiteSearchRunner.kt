package com.raven.app.feature.browse

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.model.ComicLanguage
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-site search pipeline: first page, "load more" pagination and the request builder
 * (scope/sort/language normalisation plus server-side blocked-term excludes).
 *
 * Extracted from [SearchViewModel]; the ViewModel reads [state] into its UI state and keeps the
 * monotonic intent-generation guard through the injected lambdas.
 */
internal class SingleSiteSearchRunner(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val siteRegistry: SiteRegistry,
    private val preferences: PreferencesRepository,
    private val blockedTerms: BlockedTermsRepository,
    private val tagDictionary: TagDictionary,
    private val inputState: () -> SearchInputState,
    private val currentGeneration: () -> Long,
) {
    private val _state = MutableStateFlow(SingleSiteResultState())
    val state: StateFlow<SingleSiteResultState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var paginateJob: Job? = null

    fun current(): SingleSiteResultState = _state.value

    fun cancel() {
        searchJob?.cancel()
        paginateJob?.cancel()
    }

    fun reset(siteId: SiteId?) {
        cancel()
        _state.value = SingleSiteResultState(siteId = siteId)
    }

    fun loadNextPage() {
        val snap = _state.value
        val siteId = snap.siteId ?: return
        if (snap.isLoading || snap.isLoadingMore || snap.authRequired || !snap.hasMore) return
        val plugin = siteRegistry.get(siteId) ?: return
        val searchSource = plugin.searchSource ?: return

        paginateJob?.cancel()
        paginateJob = scope.launch {
            _state.update { it.copy(isLoadingMore = true, paginationErrorMessage = null) }
            val nextPageNumber = snap.nextPage ?: (snap.currentPage + 1)
            val input = inputState()
            val settings = preferences.currentSettings()
            val effectiveBlocked = blockedTerms.observeEffectiveTermsBySite(siteId).first()
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
                _state.update { cur ->
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
                _state.update { cur ->
                    if (cur.siteId != siteId) return@update cur
                    cur.copy(
                        isLoadingMore = false,
                        paginationErrorMessage = error.toBrowseErrorMessage(),
                    )
                }
            }
        }
    }

    fun start(
        targetSiteId: SiteId,
        query: String,
        inputSnapshot: SearchInputState,
        siteLanguage: ComicLanguage,
        generation: Long,
    ) {
        // A stale caller must not cancel the current job nor overwrite the newer result state.
        if (generation != currentGeneration()) return
        searchJob?.cancel()
        paginateJob?.cancel()
        searchJob = scope.launch {
            if (generation != currentGeneration()) return@launch
            val plugin = siteRegistry.get(targetSiteId)
            val searchSource = plugin?.searchSource
            if (plugin == null || searchSource == null) {
                _state.value = SingleSiteResultState(
                    siteId = targetSiteId,
                    isLoading = false,
                    hasSearched = true,
                    errorMessage = "当前站点暂不支持搜索功能",
                )
                return@launch
            }

            _state.value = SingleSiteResultState(
                siteId = targetSiteId,
                isLoading = true,
                hasSearched = true,
            )

            try {
                if (plugin.authProvider.requiresAuthGate) {
                    val auth = withContext(dispatchers.io) { plugin.authProvider.currentAuthState() }
                    if (!auth.isAuthenticated) {
                        _state.value = SingleSiteResultState(
                            siteId = targetSiteId,
                            isLoading = false,
                            hasSearched = true,
                            authRequired = true,
                        )
                        return@launch
                    }
                }

                val effectiveBlocked = blockedTerms.observeEffectiveTermsBySite(targetSiteId).first()
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
                // A newer submit/clear may have landed while the network call was running.
                if (generation != currentGeneration()) return@launch
                _state.value = SingleSiteResultState(
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
                _state.value = SingleSiteResultState(
                    siteId = targetSiteId,
                    isLoading = false,
                    hasSearched = true,
                    authRequired = error is SiteException.AuthRequired,
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
        val effectiveLanguage = requestLanguage(plugin, inputSnapshot, siteLanguage)
        val nativeSorts = SearchViewModel.resolveNativeSearchSorts(plugin, effectiveScope)
        val requestedSort = if (inputSnapshot.sort == FeedSort.DEFAULT) nativeSorts.first() else inputSnapshot.sort
        val effectiveSort = SearchViewModel.resolveCompatibleServerSort(nativeSorts, requestedSort)
        val effectiveFilters = serverExcludeFiltersFor(
            plugin = plugin,
            baseFilters = inputSnapshot.advancedFilters,
            effectiveBlockedTerms = effectiveBlockedTerms,
            tagDictionary = tagDictionary,
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


}
