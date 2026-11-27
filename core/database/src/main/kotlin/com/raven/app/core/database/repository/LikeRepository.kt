package com.raven.app.core.database.repository

import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.MediaLikeDao
import com.raven.app.core.database.domain.LikeRecommendationEngine
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.model.LikedMedia
import com.raven.app.core.model.LikedTagWeight
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local-only like repository that stores liked works alongside their tags and derives
 * weighted tag preference profiles for Smart Recommendations and Similar Recommendations.
 */
class LikeRepository(
    private val mediaLikeDao: MediaLikeDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<LikedMedia>> =
        mediaLikeDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeBySite(siteId: SiteId): Flow<List<LikedMedia>> =
        mediaLikeDao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }

    fun observeIsLiked(siteId: SiteId, mediaId: String): Flow<Boolean> =
        mediaLikeDao.observeExists(siteId.value, mediaId)

    fun observeTagWeights(limit: Int = 40): Flow<List<LikedTagWeight>> =
        observeAll().map { likes ->
            LikeRecommendationEngine.computeTagWeights(
                likes = likes,
                nowMs = clock(),
                limit = limit,
            )
        }

    suspend fun isLiked(siteId: SiteId, mediaId: String): Boolean =
        mediaLikeDao.exists(siteId.value, mediaId)

    suspend fun getAll(): List<LikedMedia> =
        mediaLikeDao.getAll().map { it.toDomain() }

    /**
     * Atomically toggles like state for [item]. Returns `true` if now liked, `false` if unliked.
     */
    suspend fun toggle(item: LikedMedia): Boolean {
        require(item.mediaId.isNotBlank()) { "LikedMedia.mediaId must not be blank" }
        return transactionRunner {
            val exists = mediaLikeDao.exists(item.siteId.value, item.mediaId)
            if (exists) {
                mediaLikeDao.delete(item.siteId.value, item.mediaId)
                false
            } else {
                val stamped = if (item.likedAt > 0L) item else item.copy(likedAt = clock())
                mediaLikeDao.upsert(stamped.toEntity())
                true
            }
        }
    }

    suspend fun like(item: LikedMedia) {
        require(item.mediaId.isNotBlank()) { "LikedMedia.mediaId must not be blank" }
        val stamped = if (item.likedAt > 0L) item else item.copy(likedAt = clock())
        mediaLikeDao.upsert(stamped.toEntity())
    }

    suspend fun unlike(siteId: SiteId, mediaId: String) {
        mediaLikeDao.delete(siteId.value, mediaId)
    }

    suspend fun clearBySite(siteId: SiteId) {
        mediaLikeDao.deleteBySite(siteId.value)
    }
}
