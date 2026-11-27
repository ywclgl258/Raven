package com.raven.app.core.database.repository

import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.FavoriteDao
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages cross-site and per-site favorites (videos, comics, photos, tags, authors, characters).
 */
class FavoriteRepository(
    private val favoriteDao: FavoriteDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<FavoriteItem>> =
        favoriteDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Observes only media work favorites (VIDEO / COMIC / PHOTO), excluding legacy tag favorites. */
    fun observeMediaFavorites(): Flow<List<FavoriteItem>> =
        favoriteDao.observeMediaFavorites().map { rows -> rows.map { it.toDomain() } }

    fun observeBySite(siteId: SiteId): Flow<List<FavoriteItem>> =
        favoriteDao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }

    fun observeByKind(kind: FavoriteKind): Flow<List<FavoriteItem>> =
        favoriteDao.observeByKind(kind).map { rows -> rows.map { it.toDomain() } }

    fun observeBySiteAndKind(siteId: SiteId, kind: FavoriteKind): Flow<List<FavoriteItem>> =
        favoriteDao.observeBySiteAndKind(siteId.value, kind).map { rows -> rows.map { it.toDomain() } }

    fun observeIsFavorite(siteId: SiteId, kind: FavoriteKind, targetId: String): Flow<Boolean> =
        favoriteDao.observeExists(siteId.value, kind, targetId)

    suspend fun isFavorite(siteId: SiteId, kind: FavoriteKind, targetId: String): Boolean =
        favoriteDao.exists(siteId.value, kind, targetId)

    suspend fun add(item: FavoriteItem) {
        require(item.targetId.isNotBlank()) { "FavoriteItem.targetId must not be blank" }
        val stamped = if (item.addedAt > 0L) item else item.copy(addedAt = clock())
        favoriteDao.upsert(stamped.toEntity())
    }

    suspend fun remove(siteId: SiteId, kind: FavoriteKind, targetId: String) {
        favoriteDao.delete(siteId.value, kind, targetId)
    }

    /**
     * Atomically toggles a favorite item. Returns `true` if now favorited, `false` if removed.
     */
    suspend fun toggle(item: FavoriteItem): Boolean {
        require(item.targetId.isNotBlank()) { "FavoriteItem.targetId must not be blank" }
        return transactionRunner {
            val exists = favoriteDao.exists(item.siteId.value, item.kind, item.targetId)
            if (exists) {
                favoriteDao.delete(item.siteId.value, item.kind, item.targetId)
                false
            } else {
                val stamped = if (item.addedAt > 0L) item else item.copy(addedAt = clock())
                favoriteDao.upsert(stamped.toEntity())
                true
            }
        }
    }

    suspend fun clearBySite(siteId: SiteId) {
        favoriteDao.deleteBySite(siteId.value)
    }
}
