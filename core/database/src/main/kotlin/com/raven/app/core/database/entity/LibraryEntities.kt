package com.raven.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SmartShelfRule

@Entity(
    tableName = "favorites",
    primaryKeys = ["site_id", "kind", "target_id"],
    indices = [
        Index(value = ["added_at"], orders = [Index.Order.DESC]),
        Index(value = ["kind", "added_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
    ],
)
data class FavoriteEntity(
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "kind") val kind: FavoriteKind,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "extra") val extra: Map<String, String>,
)

@Entity(
    tableName = "shelves",
    indices = [
        Index(value = ["is_pinned", "modified_at"], orders = [Index.Order.DESC, Index.Order.DESC]),
    ],
)
data class ShelfEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "note") val note: String,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
    @ColumnInfo(name = "cover_composite_key") val coverCompositeKey: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
)

@Entity(
    tableName = "shelf_items",
    primaryKeys = ["shelf_id", "site_id", "media_id"],
    foreignKeys = [
        ForeignKey(
            entity = ShelfEntity::class,
            parentColumns = ["id"],
            childColumns = ["shelf_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["shelf_id", "position"], orders = [Index.Order.ASC, Index.Order.ASC]),
        Index(value = ["shelf_id", "added_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["site_id", "media_id"]),
    ],
)
data class ShelfItemEntity(
    @ColumnInfo(name = "shelf_id") val shelfId: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "kind") val kind: MediaKind,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "completed") val completed: Boolean,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int?,
    @ColumnInfo(name = "page_count") val pageCount: Int?,
)

@Entity(
    tableName = "smart_shelves",
    indices = [
        Index(value = ["is_pinned", "created_at"], orders = [Index.Order.DESC, Index.Order.DESC]),
    ],
)
data class SmartShelfEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "rule") val rule: SmartShelfRule,
    @ColumnInfo(name = "media_kind_filter") val mediaKindFilter: MediaKind?,
    @ColumnInfo(name = "site_id_filter") val siteIdFilter: String?,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "media_likes",
    primaryKeys = ["site_id", "media_id"],
    indices = [
        Index(value = ["liked_at"], orders = [Index.Order.DESC]),
    ],
)
data class MediaLikeEntity(
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "kind") val kind: MediaKind,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "author_name") val authorName: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    @ColumnInfo(name = "liked_at") val likedAt: Long,
)
