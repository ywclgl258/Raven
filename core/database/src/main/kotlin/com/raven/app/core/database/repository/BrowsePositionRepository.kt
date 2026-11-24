package com.raven.app.core.database.repository

import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.BrowsePositionDao
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.database.model.BrowsePosition
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists the last 32 cross-process scroll restoration anchors (`workId`, `throughPage`, `shelfId`).
 */
class BrowsePositionRepository(
    private val browsePositionDao: BrowsePositionDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val maxPositions: Int = BrowsePositionDao.MAX_BROWSE_POSITIONS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(positionKey: String): Flow<BrowsePosition?> =
        browsePositionDao.observeByKey(positionKey).map { it?.toDomain() }

    fun observeAll(): Flow<List<BrowsePosition>> =
        browsePositionDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun get(positionKey: String): BrowsePosition? =
        browsePositionDao.getByKey(positionKey)?.toDomain()

    suspend fun save(position: BrowsePosition) {
        require(position.positionKey.isNotBlank()) { "BrowsePosition.positionKey must not be blank" }
        require(position.workId.isNotBlank()) { "BrowsePosition.workId must not be blank" }
        val stamped = position.copy(
            throughPage = position.throughPage.coerceAtLeast(1),
            updatedAt = position.updatedAt.takeIf { it > 0L } ?: clock(),
        )
        transactionRunner {
            browsePositionDao.upsert(stamped.toEntity())
            browsePositionDao.prune(maxPositions)
        }
    }

    suspend fun remove(positionKey: String) {
        browsePositionDao.deleteByKey(positionKey)
    }

    suspend fun clearBySite(siteId: SiteId) {
        browsePositionDao.deleteBySite(siteId.value)
    }

    suspend fun clearAll() {
        browsePositionDao.deleteAll()
    }
}
