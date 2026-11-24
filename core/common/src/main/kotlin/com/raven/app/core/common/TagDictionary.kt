package com.raven.app.core.common

import kotlinx.serialization.json.Json

data class TagSuggestion(
    val slug: String,
    val localizedName: String,
)

/**
 * Bundled English-slug to Simplified Chinese tag dictionary loaded from classpath resource
 * `/raven/tag-names.json` (165 entries), with extensible constructor for external translation packs.
 */
class TagDictionary(
    entries: Map<String, String>,
) {
    private val normalizedMap: Map<String, String> = buildMap(entries.size) {
        entries.forEach { (k, v) ->
            val slug = k.trim().lowercase()
            if (slug.isNotEmpty() && v.isNotBlank()) {
                put(slug, v.trim())
            }
        }
    }

    val size: Int get() = normalizedMap.size

    fun allEntries(): Map<String, String> = normalizedMap

    /**
     * Translates a tag slug (optionally carrying `-` exclusion prefix or `namespace:`).
     */
    fun translate(rawSlug: String): String? {
        val cleaned = normalizeSlug(rawSlug)
        if (cleaned.isEmpty()) return null
        return normalizedMap[cleaned]
            ?: normalizedMap[cleaned.replace(' ', '-')]
            ?: normalizedMap[cleaned.replace('_', '-')]
    }

    fun displayLabel(rawSlug: String, fallbackLabel: String = rawSlug): String =
        translate(rawSlug) ?: fallbackLabel

    /**
     * Returns prefix-first tag suggestions matching either English slug or Chinese translation.
     * Strips leading `-` exclusion and `namespace:` before matching.
     * Ordering: prefix match first -> shorter slug -> lexicographical slug.
     */
    fun suggest(rawQuery: String, limit: Int = 8): List<TagSuggestion> {
        if (rawQuery.isBlank() || limit <= 0) return emptyList()
        val needle = normalizeSlug(rawQuery)
        if (needle.isEmpty()) return emptyList()

        return normalizedMap.entries
            .asSequence()
            .filter { (slug, zh) ->
                slug.contains(needle, ignoreCase = true) || zh.contains(needle, ignoreCase = true)
            }
            .sortedWith(
                compareBy<Map.Entry<String, String>>(
                    { !(it.key.startsWith(needle, ignoreCase = true) || it.value.startsWith(needle, ignoreCase = true)) },
                    { !it.key.startsWith(needle, ignoreCase = true) },
                    { it.key.length },
                    { it.key },
                ),
            )
            .take(limit)
            .map { TagSuggestion(slug = it.key, localizedName = it.value) }
            .toList()
    }

    private fun normalizeSlug(raw: String): String =
        raw.trim().removePrefix("-").substringAfter(':').trim().lowercase()

    companion object {
        private const val RESOURCE_PATH = "/raven/tag-names.json"

        val default: TagDictionary by lazy { loadFromClasspath() }

        fun loadFromClasspath(classLoader: ClassLoader? = TagDictionary::class.java.classLoader): TagDictionary {
            val stream = TagDictionary::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: classLoader?.getResourceAsStream(RESOURCE_PATH.removePrefix("/"))
                ?: return TagDictionary(emptyMap())
            val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = Json.decodeFromString<Map<String, String>>(raw)
            return TagDictionary(parsed)
        }
    }
}
