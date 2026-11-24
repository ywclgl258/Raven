package com.raven.app.core.common

import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId

/**
 * Per-site content filter matching blocked terms against tags, titles (hyphen-as-space normalized), and URLs.
 */
object ContentFilter {

    fun normalizeTerm(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").lowercase()

    fun isAllowed(item: MediaItem, blockedTerms: Collection<String>): Boolean {
        if (blockedTerms.isEmpty()) return true
        val normalizedTitle = item.title.replace('-', ' ')
        return blockedTerms.none { raw ->
            val term = normalizeTerm(raw)
            if (term.isEmpty()) return@none false
            val spaced = term.replace('-', ' ')
            val hyphenated = term.replace(' ', '-')
            val tagMatch = item.tags.any { tag ->
                tag.slug.equals(term, ignoreCase = true) ||
                    tag.slug.equals(hyphenated, ignoreCase = true) ||
                    tag.slug.replace('-', ' ').equals(spaced, ignoreCase = true) ||
                    tag.label.equals(term, ignoreCase = true) ||
                    tag.label.replace('-', ' ').equals(spaced, ignoreCase = true)
            }
            if (tagMatch) return@none true
            if (normalizedTitle.contains(spaced, ignoreCase = true)) return@none true
            if (item.title.contains(term, ignoreCase = true)) return@none true
            item.url.contains(term, ignoreCase = true) || item.url.contains(hyphenated, ignoreCase = true)
        }
    }

    fun filterAllowed(
        items: List<MediaItem>,
        blockedTermsBySite: Map<SiteId, Collection<String>>,
    ): List<MediaItem> {
        if (items.isEmpty() || blockedTermsBySite.isEmpty()) return items
        return items.filter { item ->
            val siteTerms = blockedTermsBySite[item.siteId].orEmpty()
            isAllowed(item, siteTerms)
        }
    }

    fun filterAllowed(
        items: List<MediaItem>,
        blockedTerms: Collection<BlockedTerm>,
    ): List<MediaItem> {
        if (items.isEmpty() || blockedTerms.isEmpty()) return items
        val grouped = blockedTerms.groupBy(BlockedTerm::siteId) { it.term }
        return filterAllowed(items, grouped)
    }
}
