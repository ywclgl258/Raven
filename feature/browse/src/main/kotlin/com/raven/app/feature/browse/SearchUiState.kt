package com.raven.app.feature.browse

import com.raven.app.core.model.TagRef
import com.raven.app.core.common.TagSuggestion
import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId

/** 搜索 UI 状态契约：热词、跨站分桶与含派生属性的 `SearchUiState`。从 `SearchViewModel.kt` 拆出。 */

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
    val dynamicSiteSorting: Boolean = true,
    val siteOpenCounts: Map<SiteId, Int> = emptyMap(),
    val startupDefaultSiteId: SiteId? = null,
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
    val showSearchTutorial: Boolean = false,
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

/** Internal DB slices combined into `SearchUiState`; grouped here with the state contracts. */
internal data class SearchTopDbSlice(
    val history: List<SearchHistoryEntry>,
    val savedSearches: List<SavedSearch>,
    val blockedTermsBySite: Map<SiteId, List<String>>,
    val activeSiteEffectiveBlocked: List<String>,
)

internal data class SearchBottomDbSlice(
    val watchProgressMap: Map<String, Float>,
    val readingProgressMap: Map<String, Float>,
    val watchLaterKeys: Set<String>,
    val cachedTagsByKey: Map<String, List<TagRef>>,
    val dictRevision: Long,
)

internal data class SearchDbContext(
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
