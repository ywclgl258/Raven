package com.raven.app.feature.browse

import com.raven.app.core.common.TagDictionary
import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry

/**
 * Pure search rules shared by [SearchViewModel] and its collaborators.
 *
 * Extracted so the merge/interleave/selection logic can be unit-tested without a ViewModel and
 * reused by the cross-site search pipeline.
 */

/** Round-robin the per-site result buckets, dropping duplicates by `compositeKey`. */
internal fun interleaveCrossSiteBuckets(buckets: List<CrossSiteBucketState>): List<MediaItem> {
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

/**
 * Injects blocked terms into a plugin server-side `excludeTags` filter when that plugin supports it.
 * Plugins without exclude support (or with no blocked terms) get the untouched baseline filters.
 */
internal fun serverExcludeFiltersFor(
    plugin: SitePlugin,
    baseFilters: AdvancedSearchFilters,
    effectiveBlockedTerms: Collection<String>,
    tagDictionary: TagDictionary,
): AdvancedSearchFilters {
    val searchSource = plugin.searchSource ?: return baseFilters
    val supportsExclude = searchSource.supportsExcludeTags ||
        AdvancedFilterField.EXCLUDE_TAGS in searchSource.supportedFilters
    if (!supportsExclude || effectiveBlockedTerms.isEmpty()) return baseFilters
    val merged = SearchViewModel.mergeBlockedExcludeTags(
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

/** Falls back to the first available site when the preferred one is not registered. */
internal fun resolveActiveSite(
    preferred: SiteId,
    siteRegistry: SiteRegistry,
    allDescriptors: List<SiteDescriptor>,
): SiteId =
    if (siteRegistry.get(preferred) != null) {
        preferred
    } else {
        allDescriptors.firstOrNull()?.id ?: preferred
    }

/**
 * Empty selection means "all searchable sites", so a full explicit selection is collapsed to an
 * empty list and persists as the default instead of a redundant id snapshot.
 */
internal fun normalizeCrossSiteSelection(
    ids: List<SiteId>,
    searchableDescriptors: List<SiteDescriptor>,
    searchableIdSet: Set<SiteId>,
): List<SiteId> =
    if (ids.size == searchableDescriptors.size && ids.toSet() == searchableIdSet) {
        emptyList()
    } else {
        ids
    }
