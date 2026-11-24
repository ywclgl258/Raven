package com.raven.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.raven.app.core.model.TagTranslationSource

@Entity(
    tableName = "tag_translations",
    primaryKeys = ["source", "namespace", "raw_slug"],
    indices = [
        Index(value = ["raw_slug"]),
        Index(value = ["chinese_name"]),
        Index(value = ["source"]),
    ],
)
data class TagTranslationEntity(
    @ColumnInfo(name = "namespace") val namespace: String,
    @ColumnInfo(name = "raw_slug") val rawSlug: String,
    @ColumnInfo(name = "source") val source: TagTranslationSource,
    @ColumnInfo(name = "chinese_name") val chineseName: String,
    @ColumnInfo(name = "intro") val intro: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "media_tag_cache",
    primaryKeys = ["site_id", "media_id"],
    indices = [
        Index(value = ["updated_at"], orders = [Index.Order.DESC]),
    ],
)
data class MediaTagCacheEntity(
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
