package com.raven.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "page_bookmarks",
    indices = [
        Index(value = ["site_id", "media_id", "page"]),
        Index(value = ["created_at"], orders = [Index.Order.DESC]),
    ],
)
data class PageBookmarkEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "page") val page: Int,
    @ColumnInfo(name = "total_pages") val totalPages: Int,
    @ColumnInfo(name = "scroll_offset_px") val scrollOffsetPx: Int,
    @ColumnInfo(name = "note") val note: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "time_bookmarks",
    indices = [
        Index(value = ["site_id", "media_id", "position_ms"]),
        Index(value = ["created_at"], orders = [Index.Order.DESC]),
    ],
)
data class TimeBookmarkEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "group_name") val groupName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "image_favorites",
    indices = [
        Index(value = ["site_id", "media_id", "page_index"]),
        Index(value = ["added_at"], orders = [Index.Order.DESC]),
    ],
)
data class ImageFavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "media_title") val mediaTitle: String,
    @ColumnInfo(name = "page_url") val pageUrl: String,
    @ColumnInfo(name = "image_url") val imageUrl: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)
