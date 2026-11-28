package com.raven.app.feature.browse

import com.raven.app.core.common.TagDictionary
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef

/**
 * Ranks the trending tags of the discovery sidebar from a site's work list, honoring per-item cached
 * tags, the blocked-term list and the Chinese tag-translation preference.
 *
 * Extracted from [SearchViewModel] as a pure function so it can be unit-tested directly.
 */
internal fun buildTrendingTags(
        siteId: SiteId,
        works: List<MediaItem>,
        cachedTagsByKey: Map<String, List<TagRef>>,
        blockedTerms: List<String>,
        translateTags: Boolean,
        tagDictionary: TagDictionary,
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
        if (counts.size < SearchViewModel.MAX_TRENDING_TAGS) {
            val fallback = tagDictionary.builtInTagEntries(limit = SearchViewModel.MAX_TRENDING_TAGS)
            fallback.forEachIndexed { idx, sug ->
                val weight = (SearchViewModel.MAX_TRENDING_TAGS - idx).coerceAtLeast(1)
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
            .take(SearchViewModel.MAX_TRENDING_TAGS)
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
