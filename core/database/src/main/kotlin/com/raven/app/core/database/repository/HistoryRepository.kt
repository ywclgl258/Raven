package com.raven.app.core.database.repository

import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.ReadingProgressDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.domain.CompletionPolicy
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.WatchProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages video watch progress and comic/photo reading progress with automatic completion
 * threshold resolution and 500-entry per-site cap pruning.
 */
class HistoryRepository(
    private val watchProgressDao: WatchProgressDao,
    private val readingProgressDao: ReadingProgressDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val maxHistoryPerSite: Int = WatchProgressDao.MAX_PER_SITE,
) {
    // ---- Watch progress ----

    fun observeWatchProgress(limit: Int = maxHistoryPerSite): Flow<List<WatchProgress>> =
        watchProgressDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    fun observeAllWatchProgress(): Flow<List<WatchProgress>> =
        watchProgressDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeWatchProgressBySite(siteId: SiteId): Flow<List<WatchProgress>> =
        watchProgressDao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }

    fun observeWatchProgress(siteId: SiteId, mediaId: String): Flow<WatchProgress?> =
        watchProgressDao.observeOne(siteId.value, mediaId).map { it?.toDomain() }

    suspend fun getWatchProgress(siteId: SiteId, mediaId: String): WatchProgress? =
        watchProgressDao.getOne(siteId.value, mediaId)?.toDomain()

    suspend fun saveWatchProgress(progress: WatchProgress): WatchProgress {
        require(progress.mediaId.isNotBlank()) { "WatchProgress.mediaId must not be blank" }
        val resolved = CompletionPolicy.resolveWatchProgress(progress)
        transactionRunner {
            watchProgressDao.upsert(resolved.toEntity())
            watchProgressDao.pruneSite(resolved.siteId.value, maxHistoryPerSite)
        }
        return resolved
    }

    suspend fun removeWatchProgress(siteId: SiteId, mediaId: String) {
        watchProgressDao.delete(siteId.value, mediaId)
    }

    suspend fun clearWatchProgress(siteId: SiteId) {
        watchProgressDao.deleteBySite(siteId.value)
    }

    suspend fun clearAllWatchProgress() {
        watchProgressDao.deleteAll()
    }

    // ---- Reading progress ----

    fun observeReadingProgress(limit: Int = maxHistoryPerSite): Flow<List<ReadingProgress>> =
        readingProgressDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    fun observeAllReadingProgress(): Flow<List<ReadingProgress>> =
        readingProgressDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeReadingProgressBySite(siteId: SiteId): Flow<List<ReadingProgress>> =
        readingProgressDao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }

    fun observeReadingProgress(siteId: SiteId, mediaId: String): Flow<ReadingProgress?> =
        readingProgressDao.observeOne(siteId.value, mediaId).map { it?.toDomain() }

    suspend fun getReadingProgress(siteId: SiteId, mediaId: String): ReadingProgress? =
        readingProgressDao.getOne(siteId.value, mediaId)?.toDomain()

    suspend fun saveReadingProgress(progress: ReadingProgress): ReadingProgress {
        require(progress.mediaId.isNotBlank()) { "ReadingProgress.mediaId must not be blank" }
        val resolved = CompletionPolicy.resolveReadingProgress(progress)
        transactionRunner {
            readingProgressDao.upsert(resolved.toEntity())
            readingProgressDao.pruneSite(resolved.siteId.value, maxHistoryPerSite)
        }
        return resolved
    }

    suspend fun removeReadingProgress(siteId: SiteId, mediaId: String) {
        readingProgressDao.delete(siteId.value, mediaId)
    }

    suspend fun clearReadingProgress(siteId: SiteId) {
        readingProgressDao.deleteBySite(siteId.value)
    }

    suspend fun clearAllReadingProgress() {
        readingProgressDao.deleteAll()
    }
}
