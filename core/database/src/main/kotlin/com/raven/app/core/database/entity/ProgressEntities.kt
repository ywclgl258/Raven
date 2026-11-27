package com.raven.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.raven.app.core.model.MediaKind

@Entity(
    tableName = "watch_progress",
    primaryKeys = ["site_id", "media_id"],
    indices = [
        Index(value = ["updated_at"], orders = [Index.Order.DESC]),
        Index(value = ["site_id", "updated_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
    ],
)
data class WatchProgressEntity(
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed") val completed: Boolean,
    @ColumnInfo(name = "quality") val quality: String?,
)

@Entity(
    tableName = "reading_progress",
    primaryKeys = ["site_id", "media_id"],
    indices = [
        Index(value = ["updated_at"], orders = [Index.Order.DESC]),
        Index(value = ["site_id", "updated_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
    ],
)
data class ReadingProgressEntity(
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "kind") val kind: MediaKind,
    @ColumnInfo(name = "current_page") val currentPage: Int,
    @ColumnInfo(name = "total_pages") val totalPages: Int,
    @ColumnInfo(name = "scroll_offset_px") val scrollOffsetPx: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed") val completed: Boolean,
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "series_title") val seriesTitle: String? = null,
    @ColumnInfo(name = "series_url") val seriesUrl: String? = null,
)
