package com.raven.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.raven.app.core.database.entity.SeriesFollowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesFollowDao {
    @Query("SELECT * FROM series_follows ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SeriesFollowEntity>>

    @Query("SELECT * FROM series_follows WHERE site_id = :siteId ORDER BY created_at DESC")
    fun observeBySite(siteId: String): Flow<List<SeriesFollowEntity>>

    @Query("SELECT * FROM series_follows WHERE site_id = :siteId AND series_id = :seriesId")
    suspend fun getOne(siteId: String, seriesId: String): SeriesFollowEntity?

    @Query("SELECT * FROM series_follows ORDER BY created_at DESC")
    suspend fun getAll(): List<SeriesFollowEntity>

    @Upsert
    suspend fun upsert(entity: SeriesFollowEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SeriesFollowEntity>)

    @Query("UPDATE series_follows SET seen_chapter_ids = :seenChapterIds WHERE site_id = :siteId AND series_id = :seriesId")
    suspend fun updateSeen(siteId: String, seriesId: String, seenChapterIds: List<String>)

    @Query("UPDATE series_follows SET last_checked_at = :lastCheckedAt WHERE site_id = :siteId AND series_id = :seriesId")
    suspend fun updateLastChecked(siteId: String, seriesId: String, lastCheckedAt: Long)

    @Query("DELETE FROM series_follows WHERE site_id = :siteId AND series_id = :seriesId")
    suspend fun delete(siteId: String, seriesId: String)

    @Query("DELETE FROM series_follows WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM series_follows")
    suspend fun deleteAll()
}
