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
          AND COALESCE(series_id, media_id) NOT IN (
            SELECT COALESCE(series_id, media_id) AS work_id
            FROM reading_progress
            WHERE site_id = :siteId
            GROUP BY work_id
            ORDER BY MAX(updated_at) DESC, work_id ASC
            LIMIT :limit
          )
        """,
    )
    suspend fun pruneSite(siteId: String, limit: Int = MAX_PER_SITE)

    @Query(
        """
        DELETE FROM reading_progress
        WHERE site_id = :siteId
          AND (
            series_id = :workId
            OR (
              series_id IS NULL
              AND (media_id = :workId OR substr(media_id, 1, length(:workId) + 1) = :workId || ':')
            )
          )
        """,
    )
    suspend fun deleteWork(siteId: String, workId: String)

    @Query(
        """
        UPDATE reading_progress
        SET series_id = :seriesId,
            series_title = COALESCE(series_title, :seriesTitle),
            series_url = COALESCE(series_url, :seriesUrl)
        WHERE site_id = :siteId
          AND media_id IN (:chapterIds)
          AND (series_id IS NULL OR series_id = :seriesId)
          AND (series_id IS NULL OR series_id <> :seriesId
               OR (series_title IS NULL AND :seriesTitle IS NOT NULL)
               OR (series_url IS NULL AND :seriesUrl IS NOT NULL))
        """,
    )
    suspend fun linkChapters(
        siteId: String,
        seriesId: String,
        seriesTitle: String?,
        seriesUrl: String?,
        chapterIds: List<String>,
    )

    companion object {
        const val MAX_PER_SITE: Int = 500
    }
}
