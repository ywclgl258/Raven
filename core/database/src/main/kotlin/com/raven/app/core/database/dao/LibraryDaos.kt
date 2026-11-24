package com.raven.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.ShelfEntity
import com.raven.app.core.database.entity.ShelfItemEntity
import com.raven.app.core.database.entity.SmartShelfEntity
import com.raven.app.core.model.FavoriteKind
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE site_id = :siteId ORDER BY added_at DESC")
    fun observeBySite(siteId: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE kind = :kind ORDER BY added_at DESC")
    fun observeByKind(kind: FavoriteKind): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE site_id = :siteId AND kind = :kind ORDER BY added_at DESC")
    fun observeBySiteAndKind(siteId: String, kind: FavoriteKind): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE kind IN ('VIDEO', 'COMIC', 'PHOTO') ORDER BY added_at DESC")
    fun observeMediaFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE site_id = :siteId AND kind = :kind AND target_id = :targetId)")
    fun observeExists(siteId: String, kind: FavoriteKind, targetId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE site_id = :siteId AND kind = :kind AND target_id = :targetId)")
    suspend fun exists(siteId: String, kind: FavoriteKind, targetId: String): Boolean

    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    suspend fun getAll(): List<FavoriteEntity>

    @Upsert
    suspend fun upsert(entity: FavoriteEntity)

    @Upsert
    suspend fun upsertAll(entities: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE site_id = :siteId AND kind = :kind AND target_id = :targetId")
    suspend fun delete(siteId: String, kind: FavoriteKind, targetId: String)

    @Query("DELETE FROM favorites WHERE site_id = :siteId")
    suspend fun deleteBySite(siteId: String)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()
}

@Dao
interface ShelfDao {
    @Query("SELECT * FROM shelves ORDER BY is_pinned DESC, modified_at DESC")
    fun observeAll(): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelves WHERE id = :id")
    fun observeById(id: String): Flow<ShelfEntity?>

    @Query("SELECT * FROM shelves WHERE id = :id")
    suspend fun getById(id: String): ShelfEntity?

    @Query("SELECT * FROM shelves ORDER BY is_pinned DESC, modified_at DESC")
    suspend fun getAll(): List<ShelfEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: ShelfEntity): Long

    /** Uses `@Upsert` (INSERT or UPDATE) so SQLite FK `ON DELETE CASCADE` on `shelf_items` is never triggered. */
    @Upsert
    suspend fun upsert(entity: ShelfEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ShelfEntity>)

    @Query("UPDATE shelves SET modified_at = :modifiedAt WHERE id = :id")
    suspend fun updateModifiedAt(id: String, modifiedAt: Long)

    @Query(
        """
        UPDATE shelves
        SET cover_composite_key = NULL, modified_at = :modifiedAt
        WHERE id = :id AND cover_composite_key = :compositeKey
        """,
    )
    suspend fun clearCoverIfMatching(id: String, compositeKey: String, modifiedAt: Long)

    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM shelves")
    suspend fun deleteAll()
}

@Dao
interface ShelfItemDao {
    @Query("SELECT * FROM shelf_items ORDER BY shelf_id ASC, position ASC, added_at ASC")
    fun observeAll(): Flow<List<ShelfItemEntity>>

    @Query("SELECT * FROM shelf_items WHERE shelf_id = :shelfId ORDER BY position ASC, added_at ASC")
    fun observeByShelf(shelfId: String): Flow<List<ShelfItemEntity>>

    @Query("SELECT shelf_id FROM shelf_items WHERE site_id = :siteId AND media_id = :mediaId")
    fun observeContainingShelfIds(siteId: String, mediaId: String): Flow<List<String>>

    @Query("SELECT * FROM shelf_items WHERE shelf_id = :shelfId ORDER BY position ASC, added_at ASC")
    suspend fun getByShelf(shelfId: String): List<ShelfItemEntity>

    @Query("SELECT * FROM shelf_items ORDER BY shelf_id ASC, position ASC, added_at ASC")
    suspend fun getAll(): List<ShelfItemEntity>

    @Query("SELECT * FROM shelf_items WHERE shelf_id = :shelfId AND site_id = :siteId AND media_id = :mediaId")
    suspend fun getOne(shelfId: String, siteId: String, mediaId: String): ShelfItemEntity?

    @Query("SELECT MAX(position) FROM shelf_items WHERE shelf_id = :shelfId")
    suspend fun getMaxPosition(shelfId: String): Int?

    @Upsert
    suspend fun upsert(entity: ShelfItemEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ShelfItemEntity>)

    @Query(
        """
        UPDATE shelf_items
        SET completed = :completed
        WHERE shelf_id = :shelfId AND site_id = :siteId AND media_id = :mediaId
        """,
    )
    suspend fun updateCompleted(shelfId: String, siteId: String, mediaId: String, completed: Boolean)

    @Query(
        """
        UPDATE shelf_items
        SET position = :position
        WHERE shelf_id = :shelfId AND site_id = :siteId AND media_id = :mediaId
        """,
    )
    suspend fun updatePosition(shelfId: String, siteId: String, mediaId: String, position: Int)

    @Query("DELETE FROM shelf_items WHERE shelf_id = :shelfId AND site_id = :siteId AND media_id = :mediaId")
    suspend fun delete(shelfId: String, siteId: String, mediaId: String)

    @Query("DELETE FROM shelf_items WHERE shelf_id = :shelfId")
    suspend fun deleteByShelf(shelfId: String)

    @Query("DELETE FROM shelf_items")
    suspend fun deleteAll()
}

@Dao
interface SmartShelfDao {
    @Query("SELECT * FROM smart_shelves ORDER BY is_pinned DESC, created_at DESC")
    fun observeAll(): Flow<List<SmartShelfEntity>>

    @Query("SELECT * FROM smart_shelves WHERE id = :id")
    fun observeById(id: String): Flow<SmartShelfEntity?>

    @Query("SELECT * FROM smart_shelves WHERE id = :id")
    suspend fun getById(id: String): SmartShelfEntity?

    @Query("SELECT * FROM smart_shelves ORDER BY is_pinned DESC, created_at DESC")
    suspend fun getAll(): List<SmartShelfEntity>

    @Upsert
    suspend fun upsert(entity: SmartShelfEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SmartShelfEntity>)

    @Query("DELETE FROM smart_shelves WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM smart_shelves")
    suspend fun deleteAll()
}
