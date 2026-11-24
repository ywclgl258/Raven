package com.raven.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.entity.SavedSearchEntity
import com.raven.app.core.database.entity.SearchHistoryEntity
import com.raven.app.core.database.entity.TagSubscriptionEntity
import com.raven.app.core.database.entity.UpdateInboxEntity
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.SearchScope
import kotlinx.coroutines.flow.Flow

@Dao
interface TagSubscriptionDao {
    @Query("SELECT * FROM tag_subscriptions ORDER BY site_id ASC, sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<TagSubscriptionEntity>>

    @Query("SELECT * FROM tag_subscriptions WHERE site_id = :siteId ORDER BY sort_order ASC, created_at ASC")
    fun observeBySite(siteId: String): Flow<List<TagSubscriptionEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM tag_subscriptions WHERE site_id = :siteId AND slug = :slug AND scope = :scope)")
    fun observeExists(siteId: String, slug: String, scope: SearchScope): Flow<Boolean>

    @Query("SELECT * FROM tag_subscriptions WHERE site_id = :siteId ORDER BY sort_order ASC, created_at ASC")
    suspend fun getBySite(siteId: String): List<TagSubscriptionEntity>

    @Query("SELECT * FROM tag_subscriptions WHERE site_id = :siteId AND slug = :slug AND scope = :scope")
    suspend fun getOne(siteId: String, slug: String, scope: SearchScope): TagSubscriptionEntity?

    @Query("SELECT MAX(sort_order) FROM tag_subscriptions WHERE site_id = :siteId")
    suspend fun getMaxSortOrder(siteId: String): Int?

    @Query("SELECT * FROM tag_subscriptions ORDER BY site_id ASC, sort_order ASC, created_at ASC")
    suspend fun getAll(): List<TagSubscriptionEntity>

    @Upsert
    suspend fun upsert(entity: TagSubscriptionEntity)

    @Upsert
    suspend fun upsertAll(entities: List<TagSubscriptionEntity>)

    @Query("UPDATE tag_subscriptions SET label = :label WHERE site_id = :siteId AND slug = :slug AND scope = :scope")
    suspend fun updateLabel(siteId: String, slug: String, scope: SearchScope, label: String)

    @Query("UPDATE tag_subscriptions SET sort_order = :sortOrder WHERE site_id = :siteId AND slug = :slug AND scope = :scope")
    suspend fun updateSortOrder(siteId: String, slug: String, scope: SearchScope, sortOrder: Int)

    @Query("DELETE FROM tag_subscriptions WHERE site_id = :siteId AND slug = :slug AND scope = :scope")
    suspend fun delete(siteId: String, slug: String, scope: SearchScope)

    @Query("DELETE FROM tag_subscriptions WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM tag_subscriptions")
    suspend fun deleteAll()
}

@Dao
interface SavedSearchDao {
    @Query("SELECT * FROM saved_searches ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SavedSearchEntity>>

    @Query("SELECT * FROM saved_searches WHERE id = :id")
    fun observeById(id: String): Flow<SavedSearchEntity?>

    @Query("SELECT * FROM saved_searches WHERE id = :id")
    suspend fun getById(id: String): SavedSearchEntity?

    @Query("SELECT * FROM saved_searches ORDER BY created_at DESC")
    suspend fun getAll(): List<SavedSearchEntity>

    @Upsert
    suspend fun upsert(entity: SavedSearchEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SavedSearchEntity>)

    @Query("UPDATE saved_searches SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    @Query("UPDATE saved_searches SET last_checked_at = :checkedAt WHERE id = :id")
    suspend fun updateLastCheckedAt(id: String, checkedAt: Long)

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM saved_searches")
    suspend fun deleteAll()
}

@Dao
interface UpdateInboxDao {
    @Query("SELECT * FROM update_inbox ORDER BY discovered_at DESC")
    fun observeAll(): Flow<List<UpdateInboxEntity>>

    @Query("SELECT * FROM update_inbox WHERE is_read = 0 ORDER BY discovered_at DESC")
    fun observeUnread(): Flow<List<UpdateInboxEntity>>

    @Query("SELECT COUNT(*) FROM update_inbox WHERE is_read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM update_inbox WHERE is_read = 0 AND source_kind = :sourceKind")
    fun observeUnreadCountBySourceKind(sourceKind: InboxSourceKind): Flow<Int>

    @Query("SELECT * FROM update_inbox WHERE source_kind = :sourceKind AND source_id = :sourceId ORDER BY discovered_at DESC")
    fun observeBySource(sourceKind: InboxSourceKind, sourceId: String): Flow<List<UpdateInboxEntity>>

    @Query("SELECT * FROM update_inbox WHERE site_id = :siteId ORDER BY discovered_at DESC")
    fun observeBySite(siteId: String): Flow<List<UpdateInboxEntity>>

    @Query("SELECT * FROM update_inbox WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<UpdateInboxEntity>

    @Query("SELECT * FROM update_inbox ORDER BY discovered_at DESC")
    suspend fun getAll(): List<UpdateInboxEntity>

    @Upsert
    suspend fun upsert(entity: UpdateInboxEntity)

    @Upsert
    suspend fun upsertAll(entities: List<UpdateInboxEntity>)

    @Query("UPDATE update_inbox SET is_read = :isRead WHERE id = :id")
    suspend fun setRead(id: String, isRead: Boolean)

    @Query("UPDATE update_inbox SET is_read = 1 WHERE id IN (:ids)")
    suspend fun markReadByIds(ids: List<String>)

    @Query("UPDATE update_inbox SET is_read = 1 WHERE is_read = 0")
    suspend fun markAllRead()

    @Query("UPDATE update_inbox SET is_read = 1 WHERE is_read = 0 AND source_kind = :sourceKind")
    suspend fun markAllReadBySourceKind(sourceKind: InboxSourceKind)

    @Query("UPDATE update_inbox SET is_read = 1 WHERE is_read = 0 AND source_kind = :sourceKind AND source_id = :sourceId")
    suspend fun markAllReadBySource(sourceKind: InboxSourceKind, sourceId: String)

    @Query("DELETE FROM update_inbox WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM update_inbox WHERE source_kind = :sourceKind AND source_id = :sourceId")
    suspend fun deleteBySource(sourceKind: InboxSourceKind, sourceId: String)

    @Query("DELETE FROM update_inbox")
    suspend fun deleteAll()
}

@Dao
interface BlockedTermDao {
    @Query("SELECT * FROM blocked_terms ORDER BY site_id ASC, created_at DESC")
    fun observeAll(): Flow<List<BlockedTermEntity>>

    @Query("SELECT * FROM blocked_terms WHERE site_id = :siteId ORDER BY created_at DESC")
    fun observeBySite(siteId: String): Flow<List<BlockedTermEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_terms WHERE site_id = :siteId AND term = :term)")
    suspend fun exists(siteId: String, term: String): Boolean

    @Query("SELECT * FROM blocked_terms ORDER BY site_id ASC, created_at DESC")
    suspend fun getAll(): List<BlockedTermEntity>

    @Upsert
    suspend fun upsert(entity: BlockedTermEntity)

    @Upsert
    suspend fun upsertAll(entities: List<BlockedTermEntity>)

    @Query("DELETE FROM blocked_terms WHERE site_id = :siteId AND term = :term")
    suspend fun delete(siteId: String, term: String)

    @Query("DELETE FROM blocked_terms WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM blocked_terms")
    suspend fun deleteAll()
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history WHERE site_id = :siteId ORDER BY searched_at DESC LIMIT :limit")
    fun observeBySite(siteId: String, limit: Int = MAX_PER_SITE): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY site_id ASC, searched_at DESC")
    suspend fun getAll(): List<SearchHistoryEntity>

    @Upsert
    suspend fun upsert(entity: SearchHistoryEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SearchHistoryEntity>)

    @Query("DELETE FROM search_history WHERE site_id = :siteId AND query = :query AND scope = :scope")
    suspend fun delete(siteId: String, query: String, scope: SearchScope)

    @Query("DELETE FROM search_history WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM search_history")
    suspend fun deleteAll()

    @Query(
        """
        DELETE FROM search_history
        WHERE site_id = :siteId
          AND rowid NOT IN (
            SELECT rowid FROM search_history
            WHERE site_id = :siteId
            ORDER BY searched_at DESC
            LIMIT :limit
          )
        """,
    )
    suspend fun pruneSite(siteId: String, limit: Int = MAX_PER_SITE)

    companion object {
        const val MAX_PER_SITE: Int = 30
    }
}
