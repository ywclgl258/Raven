package com.raven.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.raven.app.core.database.entity.MediaTagCacheEntity
import com.raven.app.core.database.entity.TagTranslationEntity
import com.raven.app.core.model.TagTranslationSource
import kotlinx.coroutines.flow.Flow

@Dao
interface TagTranslationDao {
    @Query("SELECT * FROM tag_translations WHERE source = :source ORDER BY updated_at DESC, raw_slug ASC")
    fun observeBySource(source: TagTranslationSource): Flow<List<TagTranslationEntity>>

    @Query("SELECT COUNT(*) FROM tag_translations WHERE source = :source")
    fun observeCountBySource(source: TagTranslationSource): Flow<Int>

    @Query("SELECT * FROM tag_translations ORDER BY source ASC, namespace ASC, raw_slug ASC")
    fun observeAll(): Flow<List<TagTranslationEntity>>

    @Query("SELECT * FROM tag_translations WHERE source = :source ORDER BY updated_at DESC, raw_slug ASC")
    suspend fun getBySource(source: TagTranslationSource): List<TagTranslationEntity>

    @Query("SELECT * FROM tag_translations ORDER BY source ASC, namespace ASC, raw_slug ASC")
    suspend fun getAll(): List<TagTranslationEntity>

    @Query(
        """
        SELECT * FROM tag_translations
        WHERE raw_slug LIKE '%' || :query || '%'
           OR chinese_name LIKE '%' || :query || '%'
        ORDER BY CASE WHEN source = 'USER_CUSTOM' THEN 0 ELSE 1 END,
                 LENGTH(raw_slug) ASC,
                 raw_slug ASC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 50): List<TagTranslationEntity>

    @Upsert
    suspend fun upsert(entity: TagTranslationEntity)

    @Upsert
    suspend fun upsertAll(entities: List<TagTranslationEntity>)

    @Query("DELETE FROM tag_translations WHERE source = :source AND namespace = :namespace AND raw_slug = :rawSlug")
    suspend fun delete(source: TagTranslationSource, namespace: String, rawSlug: String)

    @Query("DELETE FROM tag_translations WHERE source = :source")
    suspend fun deleteBySource(source: TagTranslationSource)

    @Query("DELETE FROM tag_translations")
    suspend fun deleteAll()
}

@Dao
interface MediaTagCacheDao {
    @Query("SELECT * FROM media_tag_cache ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<MediaTagCacheEntity>>

    @Query("SELECT * FROM media_tag_cache WHERE site_id = :siteId ORDER BY updated_at DESC")
    fun observeBySite(siteId: String): Flow<List<MediaTagCacheEntity>>

    @Query("SELECT * FROM media_tag_cache WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun getOne(siteId: String, mediaId: String): MediaTagCacheEntity?

    @Query("SELECT * FROM media_tag_cache WHERE site_id = :siteId ORDER BY updated_at DESC")
    suspend fun getBySite(siteId: String): List<MediaTagCacheEntity>

    @Upsert
    suspend fun upsert(entity: MediaTagCacheEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MediaTagCacheEntity>)

    @Query("DELETE FROM media_tag_cache WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM media_tag_cache")
    suspend fun deleteAll()

    @Query(
        """
        DELETE FROM media_tag_cache
        WHERE rowid NOT IN (
            SELECT rowid FROM media_tag_cache
            ORDER BY updated_at DESC
            LIMIT :limit
        )
        """,
    )
    suspend fun prune(limit: Int = MAX_CACHED_MEDIA)

    companion object {
        const val MAX_CACHED_MEDIA: Int = 5_000
    }
}
