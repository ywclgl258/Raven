package com.raven.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.raven.app.core.database.entity.BrowsePositionEntity
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadItemDao {
    @Query("SELECT * FROM download_items ORDER BY status ASC, priority DESC, updated_at DESC")
    fun observeAll(): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM download_items WHERE status = :status ORDER BY priority DESC, updated_at DESC")
    fun observeByStatus(status: DownloadStatus): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM download_items WHERE status IN (:statuses) ORDER BY status ASC, priority DESC, updated_at DESC")
    fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM download_items WHERE site_id = :siteId ORDER BY status ASC, priority DESC, updated_at DESC")
    fun observeBySite(siteId: String): Flow<List<DownloadItemEntity>>

    @Query("SELECT * FROM download_items WHERE id = :id")
    fun observeById(id: String): Flow<DownloadItemEntity?>

    @Query("SELECT * FROM download_items WHERE site_id = :siteId AND media_id = :mediaId ORDER BY updated_at DESC")
    fun observeByMedia(siteId: String, mediaId: String): Flow<List<DownloadItemEntity>>

    @Query("SELECT COUNT(*) FROM download_items WHERE status = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT * FROM download_items WHERE id = :id")
    suspend fun getById(id: String): DownloadItemEntity?

    @Query("SELECT * FROM download_items WHERE site_id = :siteId AND media_id = :mediaId ORDER BY updated_at DESC")
    suspend fun getByMedia(siteId: String, mediaId: String): List<DownloadItemEntity>

    @Query("SELECT * FROM download_items ORDER BY status ASC, priority DESC, updated_at DESC")
    suspend fun getAll(): List<DownloadItemEntity>

    @Upsert
    suspend fun upsert(entity: DownloadItemEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DownloadItemEntity>)

    @Query(
        """
        UPDATE download_items
        SET status = :status,
            error_category = :errorCategory,
            error_message = :errorMessage,
            retry_at_epoch_ms = :retryAtEpochMs,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        errorCategory: DownloadErrorCategory?,
        errorMessage: String?,
        retryAtEpochMs: Long?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE download_items
        SET progress_fraction = :progressFraction,
            completed_units = :completedUnits,
            total_units = :totalUnits,
            downloaded_bytes = :downloadedBytes,
            total_bytes = :totalBytes,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateProgress(
        id: String,
        progressFraction: Float,
        completedUnits: Int,
        totalUnits: Int,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAt: Long,
    )

    @Query("UPDATE download_items SET priority = :priority, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePriority(id: String, priority: Int, updatedAt: Long)

    @Query("UPDATE download_items SET group_name = :groupName, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateGroupName(id: String, groupName: String, updatedAt: Long)

    @Query(
        """
        UPDATE download_items
        SET storage_location = :storageLocation,
            local_path = :localPath,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateLocalPath(
        id: String,
        storageLocation: StorageLocation,
        localPath: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM download_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_items WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun deleteByMedia(siteId: String, mediaId: String)

    @Query("DELETE FROM download_items WHERE status = :status")
    suspend fun deleteByStatus(status: DownloadStatus)

    @Query("DELETE FROM download_items")
    suspend fun deleteAll()
}

@Dao
interface PlaybackPreferencesDao {
    @Query("SELECT * FROM playback_preferences WHERE site_id = :siteId")
    fun observeBySite(siteId: String): Flow<PlaybackPreferencesEntity?>

    @Query("SELECT * FROM playback_preferences")
    fun observeAll(): Flow<List<PlaybackPreferencesEntity>>

    @Query("SELECT * FROM playback_preferences WHERE site_id = :siteId")
    suspend fun getBySite(siteId: String): PlaybackPreferencesEntity?

    @Query("SELECT * FROM playback_preferences")
    suspend fun getAll(): List<PlaybackPreferencesEntity>

    @Upsert
    suspend fun upsert(entity: PlaybackPreferencesEntity)

    @Upsert
    suspend fun upsertAll(entities: List<PlaybackPreferencesEntity>)

    @Query("DELETE FROM playback_preferences WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM playback_preferences")
    suspend fun deleteAll()
}

@Dao
interface ReaderPreferencesDao {
    @Query("SELECT * FROM reader_preferences WHERE site_id = :siteId AND media_id = :mediaId")
    fun observe(siteId: String, mediaId: String): Flow<ReaderPreferencesEntity?>

    @Query("SELECT * FROM reader_preferences WHERE site_id = :siteId")
    fun observeBySite(siteId: String): Flow<List<ReaderPreferencesEntity>>

    @Query("SELECT * FROM reader_preferences")
    fun observeAll(): Flow<List<ReaderPreferencesEntity>>

    @Query("SELECT * FROM reader_preferences WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun get(siteId: String, mediaId: String): ReaderPreferencesEntity?

    @Query("SELECT * FROM reader_preferences")
    suspend fun getAll(): List<ReaderPreferencesEntity>

    @Upsert
    suspend fun upsert(entity: ReaderPreferencesEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ReaderPreferencesEntity>)

    @Query("DELETE FROM reader_preferences WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun delete(siteId: String, mediaId: String)

    @Query("DELETE FROM reader_preferences WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM reader_preferences")
    suspend fun deleteAll()
}

@Dao
interface BrowsePositionDao {
    @Query("SELECT * FROM browse_positions WHERE position_key = :positionKey")
    fun observeByKey(positionKey: String): Flow<BrowsePositionEntity?>

    @Query("SELECT * FROM browse_positions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<BrowsePositionEntity>>

    @Query("SELECT * FROM browse_positions WHERE position_key = :positionKey")
    suspend fun getByKey(positionKey: String): BrowsePositionEntity?

    @Query("SELECT * FROM browse_positions ORDER BY updated_at DESC")
    suspend fun getAll(): List<BrowsePositionEntity>

    @Upsert
    suspend fun upsert(entity: BrowsePositionEntity)

    @Query("DELETE FROM browse_positions WHERE position_key = :positionKey")
    suspend fun deleteByKey(positionKey: String)

    @Query("DELETE FROM browse_positions WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM browse_positions")
    suspend fun deleteAll()

    @Query(
        """
        DELETE FROM browse_positions
        WHERE position_key NOT IN (
            SELECT position_key FROM browse_positions
            ORDER BY updated_at DESC
            LIMIT :limit
        )
        """,
    )
    suspend fun prune(limit: Int = MAX_BROWSE_POSITIONS)

    companion object {
        const val MAX_BROWSE_POSITIONS: Int = 32
    }
}
