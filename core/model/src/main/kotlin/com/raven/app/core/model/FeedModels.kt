package com.raven.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AdvancedSearchFilters(
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val category: String? = null,
    val brand: String? = null,
    val year: Int? = null,
    val month: Int? = null,
    val minDurationMinutes: Int? = null,
    val maxDurationMinutes: Int? = null,
    val minPages: Int? = null,
    val maxPages: Int? = null,
    val minRating: Int? = null,
    val publishedFrom: String? = null,
    val publishedTo: String? = null,
) {
    val isEmpty: Boolean
        get() = includeTags.isEmpty() &&
            excludeTags.isEmpty() &&
            category.isNullOrBlank() &&
            brand.isNullOrBlank() &&
            year == null &&
            month == null &&
            minDurationMinutes == null &&
            maxDurationMinutes == null &&
            minPages == null &&
            maxPages == null &&
            minRating == null &&
            publishedFrom.isNullOrBlank() &&
            publishedTo.isNullOrBlank()
}

@Serializable
data class FeedRequest(
    val siteId: SiteId,
    val section: String? = null,
    val category: String? = null,
    val sort: FeedSort = FeedSort.DEFAULT,
    val language: ComicLanguage = ComicLanguage.ALL,
    val page: Int = 1,
    val cursor: String? = null,
    val query: String? = null,
    val scope: SearchScope = SearchScope.ALL,
    val mediaKind: MediaKind? = null,
    val advancedFilters: AdvancedSearchFilters = AdvancedSearchFilters(),
)

@Serializable
data class FeedPage(
    val items: List<MediaItem>,
    val nextPage: Int? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val totalCount: Int? = null,
    val totalPages: Int? = null,
    val title: String? = null,
    val fromCache: Boolean = false,
)

@Serializable
data class FeedSection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val items: List<MediaItem>,
    val seeAllRequest: FeedRequest? = null,
    val isRanked: Boolean = false,
)

@Serializable
data class HomeFeed(
    val siteId: SiteId,
    val heroItems: List<MediaItem> = emptyList(),
    val sections: List<FeedSection> = emptyList(),
    val primaryFeedPage: FeedPage? = null,
    val fromCache: Boolean = false,
)
