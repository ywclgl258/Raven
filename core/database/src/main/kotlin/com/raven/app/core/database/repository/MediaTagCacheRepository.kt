package com.raven.app.core.database.repository

import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.converter.RavenTypeConverters
import com.raven.app.core.database.dao.MediaTagCacheDao
import com.raven.app.core.database.entity.MediaTagCacheEntity
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Caches detail-page tags seen for `(siteId, mediaId)` (bounded to the 5,000 most recent items)
 * so [com.raven.app.core.common.ContentFilter] can block works whose listing cards omit tags.
 */
class MediaTagCacheRepository(
    private val mediaTagCacheDao: MediaTagCacheDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val maxEntries: Int = MediaTagCacheDao.MAX_CACHED_MEDIA,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Observes cached tags for [siteId], keyed by `MediaItem.compositeKey` (`"${siteId.value}:$mediaId"`).
     */
    fun observeTagsBySite(siteId: SiteId): Flow<Map<String, List<TagRef>>> =
        mediaTagCacheDao.observeBySite(siteId.value).map(::toCompositeKeyMap)

    /**
     * Observes all cached tags across all sites, keyed by `MediaItem.compositeKey` (`"${siteId.value}:$mediaId"`).
     */
    fun observeAllTags(): Flow<Map<String, List<TagRef>>> =
        mediaTagCacheDao.observeAll().map(::toCompositeKeyMap)

    suspend fun getTags(siteId: SiteId, mediaId: String): List<TagRef> {
        if (mediaId.isBlank()) return emptyList()
        val entity = mediaTagCacheDao.getOne(siteId.value, mediaId) ?: return emptyList()
        return RavenTypeConverters.decodeTagRefList(entity.tagsJson)
    }

    suspend fun upsertTags(siteId: SiteId, mediaId: String, tags: Collection<TagRef>) {
        if (mediaId.isBlank() || tags.isEmpty()) return
        val normalized = deduplicateTags(tags)
        if (normalized.isEmpty()) return
        transactionRunner {
            val existing = mediaTagCacheDao.getOne(siteId.value, mediaId)
                ?.let { RavenTypeConverters.decodeTagRefList(it.tagsJson) }
                .orEmpty()
            val merged = if (existing.isEmpty()) normalized else deduplicateTags(existing + normalized)
            mediaTagCacheDao.upsert(
                MediaTagCacheEntity(
                    siteId = siteId.value,
                    mediaId = mediaId,
                    tagsJson = RavenTypeConverters.encodeTagRefList(merged),
                    updatedAt = clock(),
                ),
            )
            mediaTagCacheDao.prune(maxEntries)
        }
    }

    suspend fun upsertBatch(entries: Map<Pair<SiteId, String>, Collection<TagRef>>) {
        if (entries.isEmpty()) return
        val now = clock()
        transactionRunner {
            val toUpsert = ArrayList<MediaTagCacheEntity>(entries.size)
            for ((key, tags) in entries) {
                val (siteId, mediaId) = key
                if (mediaId.isBlank() || tags.isEmpty()) continue
                val normalized = deduplicateTags(tags)
                if (normalized.isEmpty()) continue
                val existing = mediaTagCacheDao.getOne(siteId.value, mediaId)
                    ?.let { RavenTypeConverters.decodeTagRefList(it.tagsJson) }
                    .orEmpty()
                val merged = if (existing.isEmpty()) normalized else deduplicateTags(existing + normalized)
                toUpsert.add(
                    MediaTagCacheEntity(
                        siteId = siteId.value,
                        mediaId = mediaId,
                        tagsJson = RavenTypeConverters.encodeTagRefList(merged),
                        updatedAt = now,
                    ),
                )
            }
            if (toUpsert.isNotEmpty()) {
                toUpsert.chunked(UPSERT_CHUNK_SIZE).forEach { chunk ->
                    mediaTagCacheDao.upsertAll(chunk)
                }
                mediaTagCacheDao.prune(maxEntries)
            }
        }
    }

    suspend fun clearSite(siteId: SiteId) {
        mediaTagCacheDao.deleteBySite(siteId.value)
    }

    suspend fun clearAll() {
        mediaTagCacheDao.deleteAll()
    }

    private fun toCompositeKeyMap(rows: List<MediaTagCacheEntity>): Map<String, List<TagRef>> {
        if (rows.isEmpty()) return emptyMap()
        return buildMap(rows.size) {
            for (row in rows) {
                val decoded = RavenTypeConverters.decodeTagRefList(row.tagsJson)
                if (decoded.isNotEmpty()) {
                    put("${row.siteId}:${row.mediaId}", decoded)
                }
            }
        }
    }

    private fun deduplicateTags(tags: Collection<TagRef>): List<TagRef> {
        val seen = LinkedHashMap<String, TagRef>(tags.size)
        for (tag in tags) {
            val bare = TagDictionary.normalizeBareSlug(TagDictionary.splitNamespace(tag.slug).second)
            val labelBare = TagDictionary.normalizeBareSlug(TagDictionary.splitNamespace(tag.label).second)
            val keyBase = bare.ifEmpty { labelBare }
            if (keyBase.isEmpty()) continue
            val ns = tag.namespace.trim().lowercase()
            val dedupeKey = "$ns:$keyBase"
            seen.putIfAbsent(dedupeKey, tag)
        }
        return seen.values.toList()
    }

    companion object {
        private const val UPSERT_CHUNK_SIZE: Int = 200
    }
}
