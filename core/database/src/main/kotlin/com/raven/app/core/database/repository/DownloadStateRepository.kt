package com.raven.app.core.database.repository

import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.mapper.toDomain
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Reactive projection store for unified video (Media3) and gallery/comic downloads.
 */
class DownloadStateRepository(
    private val downloadItemDao: DownloadItemDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<DownloadItem>> =
        downloadItemDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeByStatus(status: DownloadStatus): Flow<List<DownloadItem>> =
        downloadItemDao.observeByStatus(status).map { rows -> rows.map { it.toDomain() } }

    fun observeByStatuses(statuses: Collection<DownloadStatus>): Flow<List<DownloadItem>> {
        val list = statuses.distinct()
        if (list.isEmpty()) return flowOf(emptyList())
        return downloadItemDao.observeByStatuses(list).map { rows -> rows.map { it.toDomain() } }
    }

    fun observeBySite(siteId: SiteId): Flow<List<DownloadItem>> =
        downloadItemDao.observeBySite(siteId.value).map { rows -> rows.map { it.toDomain() } }

    fun observeById(id: String): Flow<DownloadItem?> =
        downloadItemDao.observeById(id).map { it?.toDomain() }

    fun observeByMedia(siteId: SiteId, mediaId: String): Flow<List<DownloadItem>> =
        downloadItemDao.observeByMedia(siteId.value, mediaId).map { rows -> rows.map { it.toDomain() } }

    fun observeCompletedCount(): Flow<Int> =
        downloadItemDao.observeCompletedCount()

    suspend fun getById(id: String): DownloadItem? =
        downloadItemDao.getById(id)?.toDomain()

    suspend fun getByMedia(siteId: SiteId, mediaId: String): List<DownloadItem> =
        downloadItemDao.getByMedia(siteId.value, mediaId).map { it.toDomain() }

    suspend fun upsert(item: DownloadItem) {
        require(item.id.isNotBlank()) { "DownloadItem.id must not be blank" }
        val now = clock()
        val stamped = item.copy(
            createdAt = item.createdAt.takeIf { it > 0L } ?: now,
            updatedAt = item.updatedAt.takeIf { it > 0L } ?: now,
        )
        downloadItemDao.upsert(stamped.toEntity())
    }

    suspend fun upsertAll(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        val now = clock()
        downloadItemDao.upsertAll(
            items.map { item ->
                require(item.id.isNotBlank()) { "DownloadItem.id must not be blank" }
                item.copy(
                    createdAt = item.createdAt.takeIf { it > 0L } ?: now,
                    updatedAt = item.updatedAt.takeIf { it > 0L } ?: now,
                ).toEntity()
            },
        )
    }

    suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        errorCategory: DownloadErrorCategory? = null,
        errorMessage: String? = null,
        retryAtEpochMs: Long? = null,
    ) {
        downloadItemDao.updateStatus(
            id = id,
            status = status,
            errorCategory = errorCategory,
            errorMessage = errorMessage,
            retryAtEpochMs = retryAtEpochMs,
            updatedAt = clock(),
        )
    }

    suspend fun updateProgress(
        id: String,
        progressFraction: Float,
        completedUnits: Int,
        totalUnits: Int,
        downloadedBytes: Long,
        totalBytes: Long?,
    ) {
        downloadItemDao.updateProgress(
            id = id,
            progressFraction = progressFraction.coerceIn(0f, 1f),
            completedUnits = completedUnits.coerceAtLeast(0),
            totalUnits = totalUnits.coerceAtLeast(0),
            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
            totalBytes = totalBytes?.coerceAtLeast(0L),
            updatedAt = clock(),
        )
    }

    suspend fun updatePriority(id: String, priority: Int) {
        downloadItemDao.updatePriority(id = id, priority = priority, updatedAt = clock())
    }

    suspend fun updateGroupName(id: String, groupName: String) {
        downloadItemDao.updateGroupName(id = id, groupName = groupName.trim(), updatedAt = clock())
    }

    suspend fun updateGroupNames(ids: Collection<String>, groupName: String) {
        if (ids.isEmpty()) return
        downloadItemDao.updateGroupNames(ids = ids, groupName = groupName.trim(), updatedAt = clock())
    }

    suspend fun updateLocalPath(
        id: String,
        storageLocation: StorageLocation,
        localPath: String?,
    ) {
        downloadItemDao.updateLocalPath(
            id = id,
            storageLocation = storageLocation,
            localPath = localPath,
            updatedAt = clock(),
        )
    }

    suspend fun delete(id: String) {
        downloadItemDao.deleteById(id)
    }

    suspend fun deleteByMedia(siteId: SiteId, mediaId: String) {
        downloadItemDao.deleteByMedia(siteId.value, mediaId)
    }

    suspend fun deleteByStatus(status: DownloadStatus) {
        downloadItemDao.deleteByStatus(status)
    }
}
