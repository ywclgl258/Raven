package com.raven.app.core.common

import com.raven.app.core.model.TagTranslationSource
import java.util.Locale
import kotlinx.serialization.json.Json

data class TagSuggestion(
    val slug: String,
    val localizedName: String,
    val namespace: String = "",
    val source: TagTranslationSource = TagTranslationSource.BUILTIN,
)

/**
 * Four-tier automatic Chinese tag localization engine:
 * 1. User custom overrides (`USER_CUSTOM`)
 * 2. Community EhTagTranslation pack (`COMMUNITY_EHTAG`, 40,000+ tags persisted in Room)
 * 3. Bundled built-in dictionary (`/raven/tag-names.json`, 165 entries)
 * 4. Compound-word smart splitter for multi-word English tags (e.g. `black latex stockings`)
 *
 * Dynamic layers live in an atomically swapped `@Volatile` immutable snapshot for lock-free O(1)
 * lookups across Compose, paging, and [ContentFilter].
 */
class TagDictionary(
    entries: Map<String, String>,
) {
    private data class SuggestionIndexEntry(
        val displaySlug: String,
        val displaySlugLower: String,
        val normalizedBare: String,
        val namespace: String,
        val localizedName: String,
        val localizedNameLower: String,
        val source: TagTranslationSource,
    )

    private class CommunityLayer(
        val bare: Map<String, String>,
        val namespaced: Map<String, String>,
        val reverseZhToEn: Map<String, List<String>>,
        val suggestions: List<SuggestionIndexEntry>,
        val prefixBuckets: Map<Char, List<SuggestionIndexEntry>>,
        val count: Int,
    ) {
        companion object {
            val EMPTY = CommunityLayer(
                bare = emptyMap(),
                namespaced = emptyMap(),
                reverseZhToEn = emptyMap(),
                suggestions = emptyList(),
                prefixBuckets = emptyMap(),
                count = 0,
            )
        }
    }

    private class CustomLayer(
        val bare: Map<String, String>,
        val namespaced: Map<String, String>,
        val reverseZhToEn: Map<String, List<String>>,
        val suggestions: List<SuggestionIndexEntry>,
        val count: Int,
    ) {
        companion object {
            val EMPTY = CustomLayer(
                bare = emptyMap(),
                namespaced = emptyMap(),
                reverseZhToEn = emptyMap(),
                suggestions = emptyList(),
                count = 0,
            )
        }
    }

    private val builtInOriginalMap: Map<String, String> = buildMap(entries.size) {
        entries.forEach { (k, v) ->
            val slug = k.trim().lowercase(Locale.ROOT)
            if (slug.isNotEmpty() && v.isNotBlank()) {
                put(slug, v.trim())
            }
        }
    }

    private val builtInNormalizedMap: Map<String, String> = buildMap(builtInOriginalMap.size) {
        builtInOriginalMap.forEach { (k, v) ->
            val bare = normalizeBareSlug(k)
            if (bare.isNotEmpty()) {
                put(bare, v)
            }
        }
    }

    private val builtInReverseZhToEn: Map<String, List<String>> = buildReverseIndex(builtInNormalizedMap)

    private val builtInSuggestions: List<SuggestionIndexEntry> = builtInOriginalMap.entries.map { (slug, zh) ->
        SuggestionIndexEntry(
            displaySlug = slug,
            displaySlugLower = slug,
            normalizedBare = normalizeBareSlug(slug),
            namespace = "",
            localizedName = zh,
            localizedNameLower = zh.lowercase(Locale.ROOT),
            source = TagTranslationSource.BUILTIN,
        )
    }

    @Volatile
    private var communityLayer: CommunityLayer = CommunityLayer.EMPTY

    @Volatile
    private var customLayer: CustomLayer = CustomLayer.EMPTY

    val builtInSize: Int get() = builtInOriginalMap.size

    val size: Int get() = builtInOriginalMap.size + communityLayer.count + customLayer.count

    fun allEntries(): Map<String, String> {
        val community = communityLayer
        val custom = customLayer
        if (community.count == 0 && custom.count == 0) return builtInOriginalMap
        return buildMap(builtInOriginalMap.size + community.bare.size + custom.bare.size) {
            putAll(builtInOriginalMap)
            putAll(community.bare)
            putAll(custom.bare)
        }
    }

    /**
     * Atomically replaces only the `COMMUNITY_EHTAG` dynamic layer without touching `USER_CUSTOM`.
     */
    fun updateCommunityLayer(
        communityBare: Map<String, String> = emptyMap(),
        communityNamespaced: Map<String, String> = emptyMap(),
    ) {
        val explicitBare = LinkedHashMap<String, String>(communityBare.size)
        val normBare = LinkedHashMap<String, String>(communityBare.size)
        val normNs = LinkedHashMap<String, String>(communityNamespaced.size)

        communityBare.forEach { (k, v) ->
            val bare = normalizeBareSlug(k)
            val zh = v.trim()
            if (bare.isNotEmpty() && zh.isNotEmpty()) {
                explicitBare[bare] = zh
                normBare[bare] = zh
            }
        }
        val explicitBareCount = explicitBare.size
        communityNamespaced.forEach { (k, v) ->
            val (ns, bare) = parseNamespacedKey(k)
            val zh = v.trim()
            if (bare.isNotEmpty() && zh.isNotEmpty()) {
                if (ns.isNotEmpty()) {
                    normNs["$ns:$bare"] = zh
                    normBare.putIfAbsent(bare, zh)
                } else {
                    explicitBare[bare] = zh
                    normBare[bare] = zh
                }
            }
        }

        val reverse = buildReverseIndex(normBare, normNs)
        val suggestions = buildSuggestionIndex(explicitBare, normNs, TagTranslationSource.COMMUNITY_EHTAG)
        val buckets = buildPrefixBuckets(suggestions)
        val count = explicitBareCount + normNs.size
        communityLayer = CommunityLayer(
            bare = normBare,
            namespaced = normNs,
            reverseZhToEn = reverse,
            suggestions = suggestions,
            prefixBuckets = buckets,
            count = count,
        )
    }

    /**
     * Atomically replaces only the `USER_CUSTOM` dynamic layer without rebuilding 40,000+ community tags.
     */
    fun updateCustomLayer(
        customBare: Map<String, String> = emptyMap(),
        customNamespaced: Map<String, String> = emptyMap(),
    ) {
        val normBare = LinkedHashMap<String, String>(customBare.size)
        val normNs = LinkedHashMap<String, String>(customNamespaced.size)

        customBare.forEach { (k, v) ->
            val bare = normalizeBareSlug(k)
            val zh = v.trim()
            if (bare.isNotEmpty() && zh.isNotEmpty()) {
                normBare[bare] = zh
            }
        }
        customNamespaced.forEach { (k, v) ->
            val (ns, bare) = parseNamespacedKey(k)
            val zh = v.trim()
            if (bare.isNotEmpty() && zh.isNotEmpty()) {
                if (ns.isNotEmpty()) {
                    normNs["$ns:$bare"] = zh
                } else {
                    normBare[bare] = zh
                }
            }
        }

        val reverse = buildReverseIndex(normBare, normNs)
        val suggestions = buildSuggestionIndex(normBare, normNs, TagTranslationSource.USER_CUSTOM)
        customLayer = CustomLayer(
            bare = normBare,
            namespaced = normNs,
            reverseZhToEn = reverse,
            suggestions = suggestions,
            count = normBare.size + normNs.size,
        )
    }

    /**
     * Replaces both dynamic runtime layers hydrated from Room (`COMMUNITY_EHTAG` + `USER_CUSTOM`).
     * Namespaced map keys may be supplied as `"namespace:rawSlug"` (e.g. `"female:big_breasts"`).
     */
    fun updateDynamicLayers(
        communityBare: Map<String, String> = emptyMap(),
        communityNamespaced: Map<String, String> = emptyMap(),
        customBare: Map<String, String> = emptyMap(),
        customNamespaced: Map<String, String> = emptyMap(),
    ) {
        updateCommunityLayer(communityBare, communityNamespaced)
        updateCustomLayer(customBare, customNamespaced)
    }

    /** Clears all dynamic community & custom layers (primarily for deterministic unit tests). */
    fun resetDynamicLayers() {
        communityLayer = CommunityLayer.EMPTY
        customLayer = CustomLayer.EMPTY
    }

    /**
     * Translates a tag slug (optionally carrying `-` exclusion prefix or `namespace:`)
     * through the 4-tier priority chain:
     * 1. User Custom -> 2. Community EhTagTranslation -> 3. Built-in 165 -> 4. Compound-Word Splitter.
     */
    fun translate(rawSlug: String, namespace: String? = null): String? {
        val (embeddedNs, bareRaw) = splitNamespace(rawSlug)
        val bare = normalizeBareSlug(bareRaw)
        if (bare.isEmpty()) return null
        val effectiveNs = normalizeNamespace(namespace).ifEmpty { embeddedNs }
        val custom = customLayer
        val community = communityLayer

        lookupDirect(custom, community, bare, effectiveNs)?.let { return it }
        return translateCompound(custom, community, bare)
    }

    fun displayLabel(rawSlug: String, fallbackLabel: String = rawSlug, namespace: String? = null): String =
        translate(rawSlug, namespace) ?: fallbackLabel

    /**
     * Expands a blocked term or tag into normalized bilingual English <-> Chinese equivalents
     * (e.g. `"触手"` -> `{"触手", "tentacles"}`, `"female:stockings"` -> `{"female:stockings", "stockings", "丝袜"}`).
     */
    fun expandSynonyms(term: String): Set<String> {
        val (ns, bareRaw) = splitNamespace(term)
        val bare = normalizeBareSlug(bareRaw)
        if (bare.isEmpty()) return emptySet()

        val custom = customLayer
        val community = communityLayer
        val result = LinkedHashSet<String>(4)
        if (ns.isNotEmpty()) {
            result.add("$ns:$bare")
        }
        result.add(bare)

        // Forward lookup (English -> Chinese) using exact/inflection layers only (no compound noise)
        lookupDirect(custom, community, bare, ns)?.let { zh ->
            val zhNorm = normalizeChineseKey(zh)
            if (zhNorm.isNotEmpty()) result.add(zhNorm)
        }

        // Reverse lookup (Chinese -> English) across Custom -> Built-in -> Community
        val zhKey = normalizeChineseKey(bare)
        custom.reverseZhToEn[zhKey]?.forEach(result::add)
        builtInReverseZhToEn[zhKey]?.forEach(result::add)
        community.reverseZhToEn[zhKey]?.forEach(result::add)

        return result
    }

    /**
     * Maps a Chinese query or blocked term to canonical English slugs for server-side `excludeTags`
     * injection. If [term] is already English/ASCII, returns its normalized canonical form.
     */
    fun canonicalEnglishSlugs(term: String): List<String> {
        val (ns, bareRaw) = splitNamespace(term)
        val bare = normalizeBareSlug(bareRaw)
        if (bare.isEmpty()) return emptyList()

        if (!containsCjk(bare)) {
            val formatted = if (ns.isNotEmpty()) "$ns:$bare" else bare
            return listOf(formatted)
        }

        val custom = customLayer
        val community = communityLayer
        val zhKey = normalizeChineseKey(bare)
        val matches = LinkedHashSet<String>()
        fun collect(list: List<String>?) {
            list?.forEach { en ->
                if (en.isNotBlank() && !containsCjk(en)) {
                    matches.add(if (ns.isNotEmpty()) "$ns:$en" else en)
                }
            }
        }
        collect(custom.reverseZhToEn[zhKey])
        collect(builtInReverseZhToEn[zhKey])
        collect(community.reverseZhToEn[zhKey])
        return matches.toList()
    }

    /** Returns curated built-in tag entries for search discovery fallback. */
    fun builtInTagEntries(limit: Int = 12): List<TagSuggestion> =
        builtInSuggestions.take(limit.coerceAtLeast(0)).map { entry ->
            TagSuggestion(
                slug = entry.displaySlug,
                localizedName = entry.localizedName,
                namespace = entry.namespace,
                source = entry.source,
            )
        }

    /**
     * Alias for [suggest] with default limit 12 for tag dictionary & search autocomplete sheets.
     */
    fun suggestions(query: String, limit: Int = 12): List<TagSuggestion> = suggest(query, limit)

    /**
     * Returns prefix-first tag suggestions across Custom + Built-in + Community layers matching
     * either English slug or Chinese translation. Prefix-bucketed for fast 40,000+ tag autocomplete.
     */
    fun suggest(rawQuery: String, limit: Int = 8): List<TagSuggestion> {
        if (rawQuery.isBlank() || limit <= 0) return emptyList()
        val (nsFilter, bareRaw) = splitNamespace(rawQuery)
        val needle = normalizeBareSlug(bareRaw)
        val needleRaw = bareRaw.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptyList()

        val custom = customLayer
        val community = communityLayer
        val exact = ArrayList<SuggestionIndexEntry>()
        val slugPrefix = ArrayList<SuggestionIndexEntry>()
        val zhPrefix = ArrayList<SuggestionIndexEntry>()
        val contains = ArrayList<SuggestionIndexEntry>()
        val containsCap = maxOf(limit * 8, 64)
        val seenBare = HashSet<String>()

        fun consider(list: List<SuggestionIndexEntry>, containsOnly: Boolean = false) {
            for (entry in list) {
                if (nsFilter.isNotEmpty() && entry.namespace != nsFilter) {
                    continue
                }
                if (containsOnly) {
                    if (contains.size >= containsCap) return
                    val isContains = entry.normalizedBare.contains(needle) ||
                        entry.displaySlugLower.contains(needleRaw) ||
                        entry.localizedNameLower.contains(needle)
                    if (!isContains) continue
                    val dedupeKey = if (nsFilter.isNotEmpty()) "${entry.namespace}:${entry.normalizedBare}" else entry.normalizedBare
                    if (seenBare.add(dedupeKey)) {
                        contains.add(entry)
                    }
                    continue
                }

                val isExact = entry.normalizedBare == needle ||
                    entry.displaySlugLower == needleRaw ||
                    entry.localizedNameLower == needle
                val isSlugPrefix = !isExact && (
                    entry.normalizedBare.startsWith(needle) ||
                        entry.displaySlugLower.startsWith(needleRaw)
                    )
                val isZhPrefix = !isExact && !isSlugPrefix && entry.localizedNameLower.startsWith(needle)
                val isContains = !isExact && !isSlugPrefix && !isZhPrefix &&
                    contains.size < containsCap && (
                    entry.normalizedBare.contains(needle) ||
                        entry.displaySlugLower.contains(needleRaw) ||
                        entry.localizedNameLower.contains(needle)
                    )

                if (!isExact && !isSlugPrefix && !isZhPrefix && !isContains) continue

                val dedupeKey = if (nsFilter.isNotEmpty()) "${entry.namespace}:${entry.normalizedBare}" else entry.normalizedBare
                if (!seenBare.add(dedupeKey)) continue

                when {
                    isExact -> exact.add(entry)
                    isSlugPrefix -> slugPrefix.add(entry)
                    isZhPrefix -> zhPrefix.add(entry)
                    else -> contains.add(entry)
                }
            }
        }

        // Scan highest-priority source first so dedupe preserves Custom > Built-in > Community
        consider(custom.suggestions)
        consider(builtInSuggestions)

        val primaryChar = needle.first()
        community.prefixBuckets[primaryChar]?.let { consider(it) }
        val rawFirst = needleRaw.firstOrNull()
        if (rawFirst != null && rawFirst != primaryChar) {
            community.prefixBuckets[rawFirst]?.let { consider(it) }
        }
        if (exact.size + slugPrefix.size + zhPrefix.size < limit && contains.size < containsCap) {
            consider(community.suggestions, containsOnly = true)
        }

        val entryComparator = compareBy<SuggestionIndexEntry>(
            { sourceRank(it.source) },
            { it.displaySlug.length },
            { it.displaySlugLower },
        )
        exact.sortWith(entryComparator)
        slugPrefix.sortWith(entryComparator)
        zhPrefix.sortWith(entryComparator)
        contains.sortWith(entryComparator)

        val out = ArrayList<TagSuggestion>(limit)
        fun drain(bucket: List<SuggestionIndexEntry>) {
            for (item in bucket) {
                if (out.size >= limit) return
                out.add(
                    TagSuggestion(
                        slug = item.displaySlug,
                        localizedName = item.localizedName,
                        namespace = item.namespace,
                        source = item.source,
                    ),
                )
            }
        }
        drain(exact)
        drain(slugPrefix)
        drain(zhPrefix)
        drain(contains)
        return out
    }

    private fun lookupDirect(
        custom: CustomLayer,
        community: CommunityLayer,
        bare: String,
        ns: String,
    ): String? {
        // Tier 1: User Custom (namespaced -> bare -> singular/plural)
        if (ns.isNotEmpty()) {
            lookupWithInflection(custom.namespaced, bare, ns)?.let { return it }
        }
        lookupWithInflection(custom.bare, bare, "")?.let { return it }

        // Tier 2: Community EhTagTranslation (namespaced -> bare -> singular/plural)
        if (ns.isNotEmpty()) {
            lookupWithInflection(community.namespaced, bare, ns)?.let { return it }
        }
        lookupWithInflection(community.bare, bare, "")?.let { return it }

        // Tier 3: Built-in 165 (bare -> singular/plural)
        lookupWithInflection(builtInNormalizedMap, bare, "")?.let { return it }

        return null
    }

    private fun lookupWithInflection(map: Map<String, String>, bare: String, ns: String): String? {
        if (map.isEmpty()) return null
        fun key(candidate: String): String = if (ns.isNotEmpty()) "$ns:$candidate" else candidate

        map[key(bare)]?.let { return it }

        for (variant in singularCandidates(bare)) {
            map[key(variant)]?.let { return it }
        }
        for (variant in pluralCandidates(bare)) {
            map[key(variant)]?.let { return it }
        }
        return null
    }

    /**
     * Tier 4: Greedily matches longest known 3/2/1-word phrases across Tiers 1..3.
     * Succeeds only when at least 2 segments translate AND at least 50% of tokens are covered.
     */
    private fun translateCompound(
        custom: CustomLayer,
        community: CommunityLayer,
        bare: String,
    ): String? {
        val tokens = bare.split(' ').filter { it.isNotEmpty() }
        if (tokens.size !in 2..8) return null

        val parts = ArrayList<String>(tokens.size)
        var matchedSegments = 0
        var translatedTokenCount = 0
        var idx = 0
        while (idx < tokens.size) {
            val maxWindow = minOf(3, tokens.size - idx)
            var matched = false
            for (len in maxWindow downTo 1) {
                val phrase = if (len == 1) {
                    tokens[idx]
                } else {
                    tokens.subList(idx, idx + len).joinToString(" ")
                }
                val translated = lookupDirect(custom, community, phrase, "")
                if (translated != null) {
                    parts.add(translated)
                    matchedSegments++
                    translatedTokenCount += len
                    idx += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                parts.add(tokens[idx])
                idx++
            }
        }

        if (matchedSegments < 2 || translatedTokenCount * 2 < tokens.size) {
            return null
        }
        return parts.joinToString(" ")
    }

    private fun sourceRank(source: TagTranslationSource): Int = when (source) {
        TagTranslationSource.USER_CUSTOM -> 0
        TagTranslationSource.BUILTIN -> 1
        TagTranslationSource.COMMUNITY_EHTAG -> 2
    }

    private fun buildReverseIndex(
        bareMap: Map<String, String>,
        namespacedMap: Map<String, String> = emptyMap(),
    ): Map<String, List<String>> {
        if (bareMap.isEmpty() && namespacedMap.isEmpty()) return emptyMap()
        val acc = HashMap<String, LinkedHashSet<String>>()
        fun add(slug: String, zh: String) {
            val zhKey = normalizeChineseKey(zh)
            if (zhKey.isEmpty() || slug.isEmpty()) return
            acc.getOrPut(zhKey) { LinkedHashSet(2) }.add(slug)
        }
        bareMap.forEach { (slug, zh) -> add(slug, zh) }
        namespacedMap.forEach { (nsSlug, zh) ->
            val bare = nsSlug.substringAfter(':', nsSlug)
            add(bare, zh)
        }
        return acc.mapValues { (_, set) -> set.toList() }
    }

    private fun buildSuggestionIndex(
        bareMap: Map<String, String>,
        namespacedMap: Map<String, String>,
        source: TagTranslationSource,
    ): List<SuggestionIndexEntry> {
        val list = ArrayList<SuggestionIndexEntry>(bareMap.size + namespacedMap.size)
        namespacedMap.forEach { (nsSlug, zh) ->
            val ns = nsSlug.substringBefore(':', "")
            val bare = nsSlug.substringAfter(':', nsSlug)
            list.add(
                SuggestionIndexEntry(
                    displaySlug = bare,
                    displaySlugLower = bare,
                    normalizedBare = bare,
                    namespace = ns,
                    localizedName = zh,
                    localizedNameLower = zh.lowercase(Locale.ROOT),
                    source = source,
                ),
            )
        }
        bareMap.forEach { (bare, zh) ->
            list.add(
                SuggestionIndexEntry(
                    displaySlug = bare,
                    displaySlugLower = bare,
                    normalizedBare = bare,
                    namespace = "",
                    localizedName = zh,
                    localizedNameLower = zh.lowercase(Locale.ROOT),
                    source = source,
                ),
            )
        }
        return list
    }

    private fun buildPrefixBuckets(
        entries: List<SuggestionIndexEntry>,
    ): Map<Char, List<SuggestionIndexEntry>> {
        if (entries.isEmpty()) return emptyMap()
        val buckets = HashMap<Char, ArrayList<SuggestionIndexEntry>>()
        for (entry in entries) {
            val c1 = entry.normalizedBare.firstOrNull()
            val c2 = entry.displaySlugLower.firstOrNull()
            val c3 = entry.localizedNameLower.firstOrNull()
            if (c1 != null) buckets.getOrPut(c1) { ArrayList() }.add(entry)
            if (c2 != null && c2 != c1) buckets.getOrPut(c2) { ArrayList() }.add(entry)
            if (c3 != null && c3 != c1 && c3 != c2) buckets.getOrPut(c3) { ArrayList() }.add(entry)
        }
        return buckets
    }

    companion object {
        private const val RESOURCE_PATH = "/raven/tag-names.json"
        private val SEPARATOR_REGEX = Regex("[_\\-]+")
        private val MULTI_SPACE_REGEX = Regex("\\s+")

        val default: TagDictionary by lazy { loadFromClasspath() }

        fun loadFromClasspath(classLoader: ClassLoader? = TagDictionary::class.java.classLoader): TagDictionary {
            val stream = TagDictionary::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: classLoader?.getResourceAsStream(RESOURCE_PATH.removePrefix("/"))
                ?: return TagDictionary(emptyMap())
            val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = Json.decodeFromString<Map<String, String>>(raw)
            return TagDictionary(parsed)
        }

        fun normalizeNamespace(raw: String?): String =
            raw?.trim()?.lowercase(Locale.ROOT).orEmpty()

        /**
         * Splits optional leading `-` exclusion and `namespace:` prefix.
         * Returns `(normalizedNamespace, barePart)`.
         */
        fun splitNamespace(raw: String): Pair<String, String> {
            val cleaned = raw.trim().removePrefix("-").trim()
            val colonIdx = cleaned.indexOf(':')
            if (colonIdx > 0 && colonIdx < cleaned.lastIndex) {
                val nsPart = cleaned.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
                val rest = cleaned.substring(colonIdx + 1).trim()
                if (nsPart.isNotEmpty() && nsPart.all { it.isLetterOrDigit() || it == '_' || it == '-' } && rest.isNotEmpty()) {
                    return nsPart to rest
                }
            }
            return "" to cleaned
        }

        /**
         * Normalizes a bare tag slug: strips leading `-`, converts `_` and `-` to single space,
         * collapses whitespace, and lowercases.
         */
        fun normalizeBareSlug(raw: String): String {
            val stripped = raw.trim().removePrefix("-").trim()
            if (stripped.isEmpty()) return ""
            return stripped
                .replace(SEPARATOR_REGEX, " ")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
                .lowercase(Locale.ROOT)
        }

        private fun parseNamespacedKey(raw: String): Pair<String, String> {
            val (ns, bareRaw) = splitNamespace(raw)
            return ns to normalizeBareSlug(bareRaw)
        }

        private fun normalizeChineseKey(raw: String): String =
            raw.trim().replace(MULTI_SPACE_REGEX, " ").lowercase(Locale.ROOT)

        private fun containsCjk(text: String): Boolean = text.any { ch ->
            val code = ch.code
            code in 0x4E00..0x9FFF ||
                code in 0x3400..0x4DBF ||
                code in 0x3040..0x30FF ||
                code in 0xAC00..0xD7AF
        }

        private fun singularCandidates(bare: String): List<String> {
            val lastSpace = bare.lastIndexOf(' ')
            val prefix = if (lastSpace >= 0) bare.substring(0, lastSpace + 1) else ""
            val word = if (lastSpace >= 0) bare.substring(lastSpace + 1) else bare
            if (word.length <= 3 || word.endsWith("ss") || word.endsWith("us")) return emptyList()
            val out = ArrayList<String>(2)
            if (word.endsWith("ies") && word.length > 4) {
                out.add(prefix + word.dropLast(3) + "y")
            }
            if ((word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") || word.endsWith("zes")) && word.length > 4) {
                out.add(prefix + word.dropLast(2))
            }
            if (word.endsWith("s") && !word.endsWith("is")) {
                out.add(prefix + word.dropLast(1))
            }
            return out
        }

        private fun pluralCandidates(bare: String): List<String> {
            val lastSpace = bare.lastIndexOf(' ')
            val prefix = if (lastSpace >= 0) bare.substring(0, lastSpace + 1) else ""
            val word = if (lastSpace >= 0) bare.substring(lastSpace + 1) else bare
            if (word.length <= 2 || word.endsWith("s")) return emptyList()
            val out = ArrayList<String>(2)
            out.add(prefix + word + "s")
            if (word.endsWith("ch") || word.endsWith("sh") || word.endsWith("x") || word.endsWith("z")) {
                out.add(prefix + word + "es")
            }
            return out
        }
    }
}
