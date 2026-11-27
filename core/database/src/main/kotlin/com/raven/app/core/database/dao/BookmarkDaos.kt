package com.raven.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.raven.app.core.database.entity.ImageFavoriteEntity
import com.raven.app.core.database.entity.PageBookmarkEntity
import com.raven.app.core.database.entity.TimeBookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PageBookmarkDao {
    @Query("SELECT * FROM page_bookmarks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PageBookmarkEntity>>

    @Query("SELECT * FROM page_bookmarks WHERE site_id = :siteId AND media_id = :mediaId ORDER BY page ASC")
    fun observeByMedia(siteId: String, mediaId: String): Flow<List<PageBookmarkEntity>>

    @Query("SELECT * FROM page_bookmarks WHERE site_id = :siteId AND media_id = :mediaId AND page = :page LIMIT 1")
    suspend fun getByMediaAndPage(siteId: String, mediaId: String, page: Int): PageBookmarkEntity?

    @Query("SELECT * FROM page_bookmarks ORDER BY created_at DESC")
    suspend fun getAll(): List<PageBookmarkEntity>

    @Upsert
    suspend fun upsert(entity: PageBookmarkEntity)

    @Upsert
    suspend fun upsertAll(entities: List<PageBookmarkEntity>)

    @Query("DELETE FROM page_bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM page_bookmarks WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun deleteByMedia(siteId: String, mediaId: String)

    @Query("DELETE FROM page_bookmarks")
    suspend fun deleteAll()
}

@Dao
interface TimeBookmarkDao {
    @Query("SELECT * FROM time_bookmarks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TimeBookmarkEntity>>

    @Query("SELECT * FROM time_bookmarks WHERE site_id = :siteId AND media_id = :mediaId ORDER BY position_ms ASC")
    fun observeByMedia(siteId: String, mediaId: String): Flow<List<TimeBookmarkEntity>>

    @Query("SELECT * FROM time_bookmarks ORDER BY created_at DESC")
    suspend fun getAll(): List<TimeBookmarkEntity>

    @Query("SELECT * FROM time_bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TimeBookmarkEntity?

    @Upsert
    suspend fun upsert(entity: TimeBookmarkEntity)

    @Upsert
    suspend fun upsertAll(entities: List<TimeBookmarkEntity>)

    @Query("DELETE FROM time_bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM time_bookmarks WHERE site_id = :siteId AND media_id = :mediaId")
    suspend fun deleteByMedia(siteId: String, mediaId: String)

    @Query("DELETE FROM time_bookmarks")
    suspend fun deleteAll()
}

@Dao
interface ImageFavoriteDao {
    @Query("SELECT * FROM image_favorites ORDER BY added_at DESC")
    fun observeAll(): Flow<List<ImageFavoriteEntity>>

    @Query("SELECT * FROM image_favorites WHERE site_id = :siteId ORDER BY added_at DESC")
    fun observeBySite(siteId: String): Flow<List<ImageFavoriteEntity>>

    @Query("SELECT * FROM image_favorites WHERE site_id = :siteId AND media_id = :mediaId ORDER BY page_index ASC")
    fun observeByMedia(siteId: String, mediaId: String): Flow<List<ImageFavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM image_favorites WHERE site_id = :siteId AND media_id = :mediaId AND page_index = :pageIndex)")
    fun observeExists(siteId: String, mediaId: String, pageIndex: Int): Flow<Boolean>

    @Query("SELECT * FROM image_favorites WHERE site_id = :siteId AND media_id = :mediaId AND page_index = :pageIndex LIMIT 1")
    suspend fun getByMediaAndPage(siteId: String, mediaId: String, pageIndex: Int): ImageFavoriteEntity?

    @Query("SELECT * FROM image_favorites ORDER BY added_at DESC")
    suspend fun getAll(): List<ImageFavoriteEntity>

    @Upsert
    suspend fun upsert(entity: ImageFavoriteEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ImageFavoriteEntity>)

    @Query("DELETE FROM image_favorites WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM image_favorites")
    suspend fun deleteAll()

    @Query(
        """
        DELETE FROM image_favorites
        WHERE id NOT IN (
            SELECT id FROM image_favorites
            ORDER BY added_at DESC
            LIMIT :limit
        )
        """,
    )
    suspend fun prune(limit: Int = MAX_IMAGE_FAVORITES)

    companion object {
        const val MAX_IMAGE_FAVORITES: Int = 20_000
    }
}
