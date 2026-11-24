package com.raven.app.core.common

import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef

/**
 * Bilingual content filter matching global (`SiteId.GLOBAL`) and per-site blocked terms against:
 * - Item tags (`slug` and `label`, normalized for `_`, `-`, whitespace, and `namespace:`)
 * - Extra cached detail-page tags (from `MediaTagCacheRepository`)
 * - Titles and URLs (with `_` and `-` unified to spaces)
 * - Chinese <-> English dictionary synonyms via [TagDictionary]
 */
object ContentFilter {

    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val SEPARATOR_REGEX = Regex("[_\\-]+")

    fun normalizeTerm(raw: String): String =
        raw.trim().replace(MULTI_SPACE_REGEX, " ").lowercase()

    fun canonicalizeForMatching(raw: String): String {
        val (_, bareRaw) = TagDictionary.splitNamespace(raw)
        return TagDictionary.normalizeBareSlug(bareRaw)
    }

    fun isAllowed(
        item: MediaItem,
        blockedTerms: Collection<String>,
        tagDictionary: TagDictionary = TagDictionary.default,
        extraTags: Collection<TagRef> = emptyList(),
    ): Boolean {
        if (blockedTerms.isEmpty()) return true

        val normalizedTitle = item.title
            .replace(SEPARATOR_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .lowercase()
        val rawLowerTitle = item.title.lowercase()
        val lowerUrl = item.url.lowercase()
        val normalizedUrl = lowerUrl.replace(SEPARATOR_REGEX, " ")

        val allTags = if (extraTags.isEmpty()) item.tags else item.tags + extraTags
        val normalizedTagTokens = if (allTags.isEmpty()) {
            emptySet()
        } else {
            buildSet(allTags.size * 3) {
                for (tag in allTags) {
                    val slugNorm = normalizeTerm(tag.slug)
                    if (slugNorm.isNotEmpty()) add(slugNorm)
                    val slugBare = canonicalizeForMatching(tag.slug)
                    if (slugBare.isNotEmpty()) {
                        add(slugBare)
                        if (tag.namespace.isNotBlank()) {
                            add("${tag.namespace.trim().lowercase()}:$slugBare")
                        }
                    }
                    val labelNorm = normalizeTerm(tag.label)
                    if (labelNorm.isNotEmpty()) add(labelNorm)
                    val labelBare = canonicalizeForMatching(tag.label)
                    if (labelBare.isNotEmpty()) add(labelBare)
                }
            }
        }

        return blockedTerms.none { raw ->
            val rawTerm = normalizeTerm(raw)
            if (rawTerm.isEmpty()) return@none false

            val candidates = LinkedHashSet<String>(4)
            candidates.add(rawTerm)
            val bare = canonicalizeForMatching(rawTerm)
            if (bare.isNotEmpty()) candidates.add(bare)
            candidates.addAll(tagDictionary.expandSynonyms(rawTerm))

            candidates.any { candidate ->
                if (candidate.isEmpty()) return@any false
                val spaced = candidate.replace(SEPARATOR_REGEX, " ").replace(MULTI_SPACE_REGEX, " ").trim()
                val hyphenated = spaced.replace(' ', '-')
                val underscored = spaced.replace(' ', '_')

                if (normalizedTagTokens.isNotEmpty()) {
                    if (candidate in normalizedTagTokens || spaced in normalizedTagTokens) {
                        return@any true
                    }
                }
                if (spaced.isNotEmpty() && normalizedTitle.contains(spaced)) return@any true
                if (rawLowerTitle.contains(candidate)) return@any true
                lowerUrl.contains(candidate) ||
                    (hyphenated.isNotEmpty() && lowerUrl.contains(hyphenated)) ||
                    (underscored.isNotEmpty() && lowerUrl.contains(underscored)) ||
                    (spaced.isNotEmpty() && normalizedUrl.contains(spaced))
            }
        }
    }

    fun filterAllowed(
        items: List<MediaItem>,
        blockedTermsBySite: Map<SiteId, Collection<String>>,
        tagDictionary: TagDictionary = TagDictionary.default,
        extraTagsByMediaKey: Map<String, Collection<TagRef>> = emptyMap(),
    ): List<MediaItem> {
        if (items.isEmpty() || blockedTermsBySite.isEmpty()) return items
        val globalTerms = blockedTermsBySite[SiteId.GLOBAL].orEmpty()
        return items.filter { item ->
            val siteTerms = blockedTermsBySite[item.siteId].orEmpty()
            val effectiveTerms = when {
                globalTerms.isEmpty() -> siteTerms
                siteTerms.isEmpty() -> globalTerms
                else -> globalTerms + siteTerms
            }
            if (effectiveTerms.isEmpty()) {
                true
            } else {
                val extra = extraTagsByMediaKey[item.compositeKey].orEmpty()
                isAllowed(
                    item = item,
                    blockedTerms = effectiveTerms,
                    tagDictionary = tagDictionary,
                    extraTags = extra,
                )
            }
        }
    }

    fun filterAllowed(
        items: List<MediaItem>,
        blockedTerms: Collection<BlockedTerm>,
        tagDictionary: TagDictionary = TagDictionary.default,
        extraTagsByMediaKey: Map<String, Collection<TagRef>> = emptyMap(),
    ): List<MediaItem> {
        if (items.isEmpty() || blockedTerms.isEmpty()) return items
        val grouped = blockedTerms.groupBy(BlockedTerm::siteId) { it.term }
        return filterAllowed(
            items = items,
            blockedTermsBySite = grouped,
            tagDictionary = tagDictionary,
            extraTagsByMediaKey = extraTagsByMediaKey,
        )
    }
}
