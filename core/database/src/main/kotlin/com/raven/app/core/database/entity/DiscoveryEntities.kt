package com.raven.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope

@Entity(
    tableName = "tag_subscriptions",
    primaryKeys = ["site_id", "slug", "scope"],
    indices = [
        Index(value = ["site_id", "sort_order"], orders = [Index.Order.ASC, Index.Order.ASC]),
    ],
)
data class TagSubscriptionEntity(
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "slug") val slug: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "scope") val scope: SearchScope,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "saved_searches",
    indices = [
        Index(value = ["created_at"], orders = [Index.Order.DESC]),
    ],
)
data class SavedSearchEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "query") val query: String,
    @ColumnInfo(name = "scope") val scope: SearchScope,
    @ColumnInfo(name = "site_ids") val siteIds: List<String>,
    @ColumnInfo(name = "media_kind") val mediaKind: MediaKind?,
    @ColumnInfo(name = "sort") val sort: FeedSort,
    @ColumnInfo(name = "language") val language: ComicLanguage,
    @ColumnInfo(name = "filters") val filters: AdvancedSearchFilters,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long?,
)

@Entity(
    tableName = "update_inbox",
    indices = [
        Index(value = ["is_read", "discovered_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["source_kind", "source_id"]),
    ],
)
data class UpdateInboxEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source_kind") val sourceKind: InboxSourceKind,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "source_label") val sourceLabel: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "item_json") val item: MediaItem,
    @ColumnInfo(name = "discovered_at") val discoveredAt: Long,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
)

@Entity(
    tableName = "blocked_terms",
    primaryKeys = ["site_id", "term"],
    indices = [
        Index(value = ["site_id"]),
    ],
)
data class BlockedTermEntity(
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "term") val term: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "search_history",
    primaryKeys = ["site_id", "query", "scope"],
    indices = [
        Index(value = ["site_id", "searched_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
    ],
)
data class SearchHistoryEntity(
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "query") val query: String,
    @ColumnInfo(name = "scope") val scope: SearchScope,
    @ColumnInfo(name = "searched_at") val searchedAt: Long,
)
