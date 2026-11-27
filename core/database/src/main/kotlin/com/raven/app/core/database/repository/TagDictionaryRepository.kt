package com.raven.app.core.database.repository

import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.TagTranslationDao
import com.raven.app.core.database.entity.TagTranslationEntity
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.model.TagDictionaryStats
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.TagTranslationEntry
import com.raven.app.core.model.TagTranslationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists community EhTagTranslation entries and user custom tag overrides in Room
 * (`tag_translations`) and hydrates [TagDictionary] dynamic layers atomically.
 */
class TagDictionaryRepository(
    private val tagTranslationDao: TagTranslationDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val runtimeDictionary: TagDictionary = TagDictionary.default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _revision = MutableStateFlow(0L)
    private val hydrateMutex = Mutex()

    /** Monotonic counter incremented every time [hydrateRuntimeDictionary] refreshes [runtimeDictionary]. */
    fun observeRevision(): StateFlow<Long> = _revision.asStateFlow()

    fun observeStats(): Flow<TagDictionaryStats> = combine(
        tagTranslationDao.observeCountBySource(TagTranslationSource.COMMUNITY_EHTAG),
        tagTranslationDao.observeCountBySource(TagTranslationSource.USER_CUSTOM),
    ) { communityCount, customCount ->
        TagDictionaryStats(
            builtInCount = runtimeDictionary.builtInSize,
            communityCount = communityCount,
            customCount = customCount,
        )
    }

    fun observeCustomEntries(): Flow<List<TagTranslationEntry>> =
        tagTranslationDao.observeBySource(TagTranslationSource.USER_CUSTOM)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun getCustomEntries(): List<TagTranslationEntry> =
        tagTranslationDao.getBySource(TagTranslationSource.USER_CUSTOM).map { it.toDomain() }

    suspend fun searchEntries(query: String, limit: Int = 50): List<TagTranslationEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || limit <= 0) return emptyList()
        val escaped = escapeLikeQuery(trimmed)
        return tagTranslationDao.search(escaped, limit).map { it.toDomain() }
    }

    /**
     * Adds or updates a `USER_CUSTOM` translation override and immediately refreshes
     * [runtimeDictionary] custom layer without reloading 40,000+ community entries.
     */
    suspend fun upsertCustomEntry(
        rawSlug: String,
        chineseName: String,
        namespace: String = "",
        intro: String = "",
    ): Boolean {
        val (embeddedNs, barePart) = TagDictionary.splitNamespace(rawSlug)
        val normalizedSlug = TagDictionary.normalizeBareSlug(barePart)
        val normalizedNs = TagDictionary.normalizeNamespace(namespace).ifEmpty { embeddedNs }
        val trimmedZh = chineseName.trim()
        if (normalizedSlug.isEmpty() || trimmedZh.isEmpty()) return false

        tagTranslationDao.upsert(
            TagTranslationEntity(
                // Clamped to the backup-export bounds: the editor must not be able to persist a
                // translation that would later make the whole backup fail validation.
                namespace = normalizedNs.take(RavenBackupPayload.MAX_TAG_NAMESPACE_LENGTH),
                rawSlug = normalizedSlug.take(RavenBackupPayload.MAX_TAG_SLUG_LENGTH),
                source = TagTranslationSource.USER_CUSTOM,
                chineseName = trimmedZh.take(RavenBackupPayload.MAX_TAG_CHINESE_NAME_LENGTH),
                intro = intro.trim().take(RavenBackupPayload.MAX_TAG_INTRO_LENGTH),
                updatedAt = clock(),
            ),
        )
        hydrateCustomLayer()
        return true
    }

    suspend fun upsertCustomTranslation(
        rawSlug: String,
        chineseName: String,
        namespace: String = "",
        intro: String = "",
    ): Boolean = upsertCustomEntry(rawSlug, chineseName, namespace, intro)

    suspend fun deleteCustomEntry(rawSlug: String, namespace: String = "") {
        val (embeddedNs, barePart) = TagDictionary.splitNamespace(rawSlug)
        val normalizedSlug = TagDictionary.normalizeBareSlug(barePart)
        val normalizedNs = TagDictionary.normalizeNamespace(namespace).ifEmpty { embeddedNs }
        if (normalizedSlug.isEmpty()) return
        tagTranslationDao.delete(
            source = TagTranslationSource.USER_CUSTOM,
            namespace = normalizedNs,
            rawSlug = normalizedSlug,
        )
        hydrateCustomLayer()
    }

    suspend fun deleteCustomTranslation(rawSlug: String, namespace: String = "") =
        deleteCustomEntry(rawSlug, namespace)

    suspend fun clearCustomEntries() {
        tagTranslationDao.deleteBySource(TagTranslationSource.USER_CUSTOM)
        hydrateCustomLayer()
    }

    /**
     * Transactionally replaces all `COMMUNITY_EHTAG` entries (safe for 40,000+ rows via
     * SQLite-friendly chunked upserts) and immediately rehydrates [runtimeDictionary].
     */
    suspend fun replaceCommunityEntries(entries: List<TagTranslationEntry>): Int {
        val now = clock()
        val deduped = LinkedHashMap<Pair<String, String>, TagTranslationEntity>(entries.size)
        for (entry in entries) {
            val (embeddedNs, barePart) = TagDictionary.splitNamespace(entry.rawSlug)
            val normalizedSlug = TagDictionary.normalizeBareSlug(barePart)
            val normalizedNs = TagDictionary.normalizeNamespace(entry.namespace).ifEmpty { embeddedNs }
            val zh = entry.chineseName.trim()
            if (normalizedSlug.isEmpty() || zh.isEmpty()) continue
            deduped[normalizedNs to normalizedSlug] = TagTranslationEntity(
                namespace = normalizedNs,
                rawSlug = normalizedSlug,
                source = TagTranslationSource.COMMUNITY_EHTAG,
                chineseName = zh,
                intro = entry.intro.trim(),
                updatedAt = if (entry.updatedAt > 0L) entry.updatedAt else now,
            )
        }

        val normalizedList = deduped.values.toList()
        transactionRunner {
            tagTranslationDao.deleteBySource(TagTranslationSource.COMMUNITY_EHTAG)
            // Keep bind parameters <= 900 per statement for API 24 SQLite compatibility (6 cols * 150 = 900).
            normalizedList.chunked(UPSERT_CHUNK_SIZE).forEach { chunk ->
                tagTranslationDao.upsertAll(chunk)
            }
        }
        hydrateCommunityLayer()
        return normalizedList.size
    }

    suspend fun replaceCommunityTranslations(entries: List<TagTranslationEntry>): Int =
        replaceCommunityEntries(entries)

    suspend fun clearCommunityEntries() {
        tagTranslationDao.deleteBySource(TagTranslationSource.COMMUNITY_EHTAG)
        hydrateCommunityLayer()
    }

    suspend fun hydrateCustomLayer(): Int = hydrateMutex.withLock {
        val customRows = tagTranslationDao.getBySource(TagTranslationSource.USER_CUSTOM)
        val (customBare, customNamespaced) = partitionRows(customRows)
        runtimeDictionary.updateCustomLayer(
            customBare = customBare,
            customNamespaced = customNamespaced,
        )
        _revision.update { it + 1L }
        customRows.size
    }

    suspend fun hydrateCommunityLayer(): Int = hydrateMutex.withLock {
        val communityRows = tagTranslationDao.getBySource(TagTranslationSource.COMMUNITY_EHTAG)
        val (communityBare, communityNamespaced) = partitionRows(communityRows)
        runtimeDictionary.updateCommunityLayer(
            communityBare = communityBare,
            communityNamespaced = communityNamespaced,
        )
        _revision.update { it + 1L }
        communityRows.size
    }

    /**
     * Loads persisted `COMMUNITY_EHTAG` and `USER_CUSTOM` rows from Room and pushes them
     * atomically into [runtimeDictionary] via [TagDictionary.updateDynamicLayers].
     */
    suspend fun hydrateRuntimeDictionary(): TagDictionaryStats = hydrateMutex.withLock {
        val communityRows = tagTranslationDao.getBySource(TagTranslationSource.COMMUNITY_EHTAG)
        val customRows = tagTranslationDao.getBySource(TagTranslationSource.USER_CUSTOM)

        val (communityBare, communityNamespaced) = partitionRows(communityRows)
        val (customBare, customNamespaced) = partitionRows(customRows)

        runtimeDictionary.updateDynamicLayers(
            communityBare = communityBare,
            communityNamespaced = communityNamespaced,
            customBare = customBare,
            customNamespaced = customNamespaced,
        )
        _revision.update { it + 1L }

        TagDictionaryStats(
            builtInCount = runtimeDictionary.builtInSize,
            communityCount = communityRows.size,
            customCount = customRows.size,
        )
    }

    private fun partitionRows(rows: List<TagTranslationEntity>): Pair<Map<String, String>, Map<String, String>> {
        val bare = LinkedHashMap<String, String>()
        val namespaced = LinkedHashMap<String, String>()
        for (row in rows) {
            if (row.namespace.isBlank()) {
                bare[row.rawSlug] = row.chineseName
            } else {
                namespaced["${row.namespace}:${row.rawSlug}"] = row.chineseName
            }
        }
        return bare to namespaced
    }

    private fun escapeLikeQuery(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    companion object {
        private const val UPSERT_CHUNK_SIZE: Int = 150
    }
}
