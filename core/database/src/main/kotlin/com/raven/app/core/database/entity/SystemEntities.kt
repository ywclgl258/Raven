package com.raven.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderStartPosition
import com.raven.app.core.model.StorageLocation

@Entity(
    tableName = "download_items",
    indices = [
        Index(
            value = ["status", "priority", "updated_at"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(value = ["site_id", "media_id"]),
    ],
)
data class DownloadItemEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "kind") val kind: DownloadKind,
    @ColumnInfo(name = "status") val status: DownloadStatus,
    @ColumnInfo(name = "quality") val quality: String?,
    @ColumnInfo(name = "group_name") val groupName: String,
    @ColumnInfo(name = "progress_fraction") val progressFraction: Float,
    @ColumnInfo(name = "completed_units") val completedUnits: Int,
    @ColumnInfo(name = "total_units") val totalUnits: Int,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long?,
    @ColumnInfo(name = "selected_pages") val selectedPages: List<Int>,
    @ColumnInfo(name = "error_category") val errorCategory: DownloadErrorCategory?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "retry_at_epoch_ms") val retryAtEpochMs: Long?,
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "storage_location") val storageLocation: StorageLocation,
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "playback_preferences",
)
data class PlaybackPreferencesEntity(
    @PrimaryKey @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "default_speed") val defaultSpeed: Float,
    @ColumnInfo(name = "long_press_speed") val longPressSpeed: Float,
    @ColumnInfo(name = "seek_step_seconds") val seekStepSeconds: Int,
    @ColumnInfo(name = "gesture_sensitivity_seconds") val gestureSensitivitySeconds: Int,
    @ColumnInfo(name = "preferred_quality") val preferredQuality: String,
    @ColumnInfo(name = "auto_fullscreen") val autoFullscreen: Boolean,
    @ColumnInfo(name = "auto_download") val autoDownload: Boolean,
) {
    companion object {
        const val GLOBAL_SITE_ID: String = "*"
    }
}

@Entity(
    tableName = "reader_preferences",
    primaryKeys = ["site_id", "media_id"],
    indices = [
        Index(value = ["site_id"]),
    ],
)
data class ReaderPreferencesEntity(
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "mode") val mode: ReaderMode,
    @ColumnInfo(name = "fit") val fit: ReaderFit,
    @ColumnInfo(name = "start_position") val startPosition: ReaderStartPosition,
    @ColumnInfo(name = "crop_percent") val cropPercent: Int,
    @ColumnInfo(name = "combine_cover") val combineCover: Boolean,
    @ColumnInfo(name = "prefer_original_images") val preferOriginalImages: Boolean,
    @ColumnInfo(name = "volume_key_paging") val volumeKeyPaging: Boolean,
    @ColumnInfo(name = "reverse_volume_keys") val reverseVolumeKeys: Boolean,
    @ColumnInfo(name = "keep_screen_on") val keepScreenOn: Boolean,
    @ColumnInfo(name = "brightness_percent") val brightnessPercent: Int,
) {
    companion object {
        const val GLOBAL_SITE_ID: String = "*"
        const val SITE_DEFAULT_MEDIA_ID: String = ""
    }
}

@Entity(
    tableName = "browse_positions",
    indices = [
        Index(value = ["updated_at"], orders = [Index.Order.DESC]),
    ],
)
data class BrowsePositionEntity(
    @PrimaryKey @ColumnInfo(name = "position_key") val positionKey: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "work_id") val workId: String,
    @ColumnInfo(name = "through_page") val throughPage: Int,
    @ColumnInfo(name = "shelf_id") val shelfId: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
