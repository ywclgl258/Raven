package com.raven.app.core.common

import com.raven.app.core.model.AdvancedSearchFilters

/**
 * Injects the user's blocked terms into a feed request's server-side `excludeTags`.
 *
 * Home / Explore / site-feed listings used to send the raw request, so sites that omit tags from
 * listing payloads returned blocked works and the client had no tag to filter on. Feeding the
 * terms into `excludeTags` lets each plugin drop them server-side (e.g. nhentai `-tag:"..."`)
 * while the existing client-side [ContentFilter] pass still covers plugins that filter locally.
 */
object BlockedTermExcludeInjection {
    const val MAX_SERVER_EXCLUDE_BLOCKED_TERMS: Int = 12

    fun merge(
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
            for (slug in tagDictionary.canonicalEnglishSlugs(term)) {
                if (result.size >= cap) break
                // Slug canonicalization maps separators to spaces; server exclude syntax wants the
                // canonical hyphenated slug (e.g. `big-breasts`) instead of a quoted phrase.
                val cleaned = slug.trim().lowercase()
                    .replace(Regex("[_\\- ]+"), "-")
                    .trim('-')
                if (cleaned.isNotEmpty() && seenLower.add(cleaned)) {
                    result.add(cleaned)
                }
            }
        }
        return result.toList()
    }

    /**
     * Returns [filters] with blocked terms merged into `excludeTags`. Identity-equal input is
     * returned unchanged when nothing needs to be added, so unaffected requests skip a copy.
     */
    fun apply(
        filters: AdvancedSearchFilters,
        effectiveBlockedTerms: Collection<String>,
        tagDictionary: TagDictionary,
        maxTerms: Int = MAX_SERVER_EXCLUDE_BLOCKED_TERMS,
    ): AdvancedSearchFilters {
        if (effectiveBlockedTerms.isEmpty()) return filters
        val merged = merge(filters.excludeTags, effectiveBlockedTerms, tagDictionary, maxTerms)
        return if (merged == filters.excludeTags) filters else filters.copy(excludeTags = merged)
    }
}
