package com.raven.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.raven.app.core.database.entity.ReadingProgressEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = MAX_PER_SITE): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE site_id = :siteId ORDER BY updated_at DESC")
    fun observeBySite(siteId: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE site_id = :siteId AND media_id = :mediaId")
    fun observeOne(siteId: String, mediaId: String): Flow<WatchProgressEntity?>

    @Query("SELECT * FROM watch_progress WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun getOne(siteId: String, mediaId: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress ORDER BY updated_at DESC")
    suspend fun getAll(): List<WatchProgressEntity>

    @Upsert
    suspend fun upsert(entity: WatchProgressEntity)

    @Upsert
    suspend fun upsertAll(entities: List<WatchProgressEntity>)

    @Query("DELETE FROM watch_progress WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun delete(siteId: String, mediaId: String)

    @Query("DELETE FROM watch_progress WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM watch_progress")
    suspend fun deleteAll()

    @Query(
        """
        DELETE FROM watch_progress
        WHERE site_id = :siteId
          AND media_id NOT IN (
            SELECT media_id FROM watch_progress
            WHERE site_id = :siteId
            ORDER BY updated_at DESC
            LIMIT :limit
          )
        """,
    )
    suspend fun pruneSite(siteId: String, limit: Int = MAX_PER_SITE)

    companion object {
        const val MAX_PER_SITE: Int = 500
    }
}

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = MAX_PER_SITE): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE site_id = :siteId ORDER BY updated_at DESC")
    fun observeBySite(siteId: String): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE site_id = :siteId AND media_id = :mediaId")
    fun observeOne(siteId: String, mediaId: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun getOne(siteId: String, mediaId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress ORDER BY updated_at DESC")
    suspend fun getAll(): List<ReadingProgressEntity>

    @Upsert
    suspend fun upsert(entity: ReadingProgressEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ReadingProgressEntity>)

    @Query("DELETE FROM reading_progress WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun delete(siteId: String, mediaId: String)

    @Query("DELETE FROM reading_progress WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM reading_progress")
    suspend fun deleteAll()

    @Query(
        """
        DELETE FROM reading_progress
        WHERE site_id = :siteId
          AND media_id NOT IN (
            SELECT media_id FROM reading_progress
            WHERE site_id = :siteId
            ORDER BY updated_at DESC
            LIMIT :limit
          )
        """,
    )
    suspend fun pruneSite(siteId: String, limit: Int = MAX_PER_SITE)

    companion object {
        const val MAX_PER_SITE: Int = 500
    }
}
