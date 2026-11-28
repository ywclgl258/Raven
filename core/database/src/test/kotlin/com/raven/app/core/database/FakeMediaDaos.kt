package com.raven.app.core.database

import com.raven.app.core.database.dao.BackupRestoreJournalDao
import com.raven.app.core.database.dao.BrowsePositionDao
import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.dao.MediaTagCacheDao
import com.raven.app.core.database.dao.PlaybackPreferencesDao
import com.raven.app.core.database.dao.ReaderPreferencesDao
import com.raven.app.core.database.dao.SeriesFollowDao
import com.raven.app.core.database.dao.TagTranslationDao
import com.raven.app.core.database.entity.BackupRestoreJournalEntity
import com.raven.app.core.database.entity.BrowsePositionEntity
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.entity.MediaTagCacheEntity
import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.database.entity.SeriesFollowEntity
import com.raven.app.core.database.entity.TagTranslationEntity
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.TagTranslationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class FakeDownloadItemDao : DownloadItemDao {
    val rows = MutableStateFlow<Map<String, DownloadItemEntity>>(emptyMap())

    private fun sort(values: Collection<DownloadItemEntity>): List<DownloadItemEntity> =
        values.sortedWith(
            compareBy<DownloadItemEntity> { it.status.name }
                .thenByDescending { it.priority }
                .thenByDescending { it.updatedAt },
        )

    override fun observeAll(): Flow<List<DownloadItemEntity>> = rows.map { sort(it.values) }

    override fun observeByStatus(status: DownloadStatus): Flow<List<DownloadItemEntity>> =
        rows.map { sort(it.values.filter { e -> e.status == status }) }

    override fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadItemEntity>> =
        rows.map { sort(it.values.filter { e -> e.status in statuses }) }

    override fun observeBySite(siteId: String): Flow<List<DownloadItemEntity>> =
        rows.map { sort(it.values.filter { e -> e.siteId == siteId }) }

    override fun observeById(id: String): Flow<DownloadItemEntity?> = rows.map { it[id] }

    override fun observeByMedia(siteId: String, mediaId: String): Flow<List<DownloadItemEntity>> =
        rows.map {
            it.values.filter { e -> e.siteId == siteId && e.mediaId == mediaId }
                .sortedByDescending(DownloadItemEntity::updatedAt)
        }

    override fun observeCompletedCount(): Flow<Int> =
        rows.map { it.values.count { e -> e.status == DownloadStatus.COMPLETED } }

    override suspend fun getById(id: String): DownloadItemEntity? = rows.value[id]

    override suspend fun getByMedia(siteId: String, mediaId: String): List<DownloadItemEntity> =
        rows.value.values.filter { it.siteId == siteId && it.mediaId == mediaId }
            .sortedByDescending(DownloadItemEntity::updatedAt)

    override suspend fun getAll(): List<DownloadItemEntity> = sort(rows.value.values)

    override suspend fun upsert(entity: DownloadItemEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<DownloadItemEntity>) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        errorCategory: DownloadErrorCategory?,
        errorMessage: String?,
        retryAtEpochMs: Long?,
        updatedAt: Long,
    ) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (
            id to current.copy(
                status = status,
                errorCategory = errorCategory,
                errorMessage = errorMessage,
                retryAtEpochMs = retryAtEpochMs,
                updatedAt = updatedAt,
            )
            )
    }

    override suspend fun updateProgress(
        id: String,
        progressFraction: Float,
        completedUnits: Int,
        totalUnits: Int,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAt: Long,
    ) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (
            id to current.copy(
                progressFraction = progressFraction,
                completedUnits = completedUnits,
                totalUnits = totalUnits,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                updatedAt = updatedAt,
            )
            )
    }

    override suspend fun updatePriority(id: String, priority: Int, updatedAt: Long) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to current.copy(priority = priority, updatedAt = updatedAt))
    }

    override suspend fun updateGroupName(id: String, groupName: String, updatedAt: Long) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to current.copy(groupName = groupName, updatedAt = updatedAt))
    }

    override suspend fun updateLocalPath(
        id: String,
        storageLocation: StorageLocation,
        localPath: String?,
        updatedAt: Long,
    ) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (
            id to current.copy(
                storageLocation = storageLocation,
                localPath = localPath,
                updatedAt = updatedAt,
            )
            )
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.value = rows.value.filterValues { !(it.siteId == siteId && it.mediaId == mediaId) }
    }

    override suspend fun deleteByStatus(status: DownloadStatus) {
        rows.value = rows.value.filterValues { it.status != status }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakePlaybackPreferencesDao : PlaybackPreferencesDao {
    val rows = MutableStateFlow<Map<String, PlaybackPreferencesEntity>>(emptyMap())

    override fun observeBySite(siteId: String): Flow<PlaybackPreferencesEntity?> = rows.map { it[siteId] }
    override fun observeAll(): Flow<List<PlaybackPreferencesEntity>> = rows.map { it.values.toList() }
    override suspend fun getBySite(siteId: String): PlaybackPreferencesEntity? = rows.value[siteId]
    override suspend fun getAll(): List<PlaybackPreferencesEntity> = rows.value.values.toList()
    override suspend fun upsert(entity: PlaybackPreferencesEntity) {
        rows.value = rows.value + (entity.siteId to entity)
    }

    override suspend fun upsertAll(entities: List<PlaybackPreferencesEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId }
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value - siteId
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeReaderPreferencesDao : ReaderPreferencesDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, ReaderPreferencesEntity>>(emptyMap())

    override fun observe(siteId: String, mediaId: String): Flow<ReaderPreferencesEntity?> =
        rows.map { it[siteId to mediaId] }

    override fun observeBySite(siteId: String): Flow<List<ReaderPreferencesEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId } }

    override fun observeAll(): Flow<List<ReaderPreferencesEntity>> = rows.map { it.values.toList() }
    override suspend fun get(siteId: String, mediaId: String): ReaderPreferencesEntity? =
        rows.value[siteId to mediaId]

    override suspend fun getAll(): List<ReaderPreferencesEntity> = rows.value.values.toList()
    override suspend fun upsert(entity: ReaderPreferencesEntity) {
        rows.value = rows.value + ((entity.siteId to entity.mediaId) to entity)
    }

    override suspend fun upsertAll(entities: List<ReaderPreferencesEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId to it.mediaId }
    }

    override suspend fun delete(siteId: String, mediaId: String) {
        rows.value = rows.value - (siteId to mediaId)
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeBrowsePositionDao : BrowsePositionDao {
    val rows = MutableStateFlow<Map<String, BrowsePositionEntity>>(emptyMap())

    override fun observeByKey(positionKey: String): Flow<BrowsePositionEntity?> = rows.map { it[positionKey] }
    override fun observeAll(): Flow<List<BrowsePositionEntity>> =
        rows.map { it.values.sortedByDescending(BrowsePositionEntity::updatedAt) }

    override suspend fun getByKey(positionKey: String): BrowsePositionEntity? = rows.value[positionKey]
    override suspend fun getAll(): List<BrowsePositionEntity> =
        rows.value.values.sortedByDescending(BrowsePositionEntity::updatedAt)

    override suspend fun upsert(entity: BrowsePositionEntity) {
        rows.value = rows.value + (entity.positionKey to entity)
    }

    override suspend fun deleteByKey(positionKey: String) {
        rows.value = rows.value - positionKey
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterValues { it.siteId != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override suspend fun prune(limit: Int) {
        val keep = rows.value.values
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .mapTo(mutableSetOf()) { it.positionKey }
        rows.value = rows.value.filterKeys { it in keep }
    }
}

internal class FakeTagTranslationDao : TagTranslationDao {
    val rows = MutableStateFlow<Map<Triple<TagTranslationSource, String, String>, TagTranslationEntity>>(emptyMap())

    override fun observeBySource(source: TagTranslationSource): Flow<List<TagTranslationEntity>> =
        rows.map { map ->
            map.values.filter { it.source == source }
                .sortedWith(compareByDescending<TagTranslationEntity> { it.updatedAt }.thenBy { it.rawSlug })
        }

    override fun observeCountBySource(source: TagTranslationSource): Flow<Int> =
        rows.map { map -> map.values.count { it.source == source } }

    override fun observeAll(): Flow<List<TagTranslationEntity>> =
        rows.map { map ->
            map.values.sortedWith(
                compareBy<TagTranslationEntity> { it.source.name }
                    .thenBy { it.namespace }
                    .thenBy { it.rawSlug },
            )
        }

    override suspend fun getBySource(source: TagTranslationSource): List<TagTranslationEntity> =
        rows.value.values.filter { it.source == source }
            .sortedWith(compareByDescending<TagTranslationEntity> { it.updatedAt }.thenBy { it.rawSlug })

    override suspend fun getAll(): List<TagTranslationEntity> =
        rows.value.values.sortedWith(
            compareBy<TagTranslationEntity> { it.source.name }
                .thenBy { it.namespace }
                .thenBy { it.rawSlug },
        )

    override suspend fun search(query: String, limit: Int): List<TagTranslationEntity> =
        rows.value.values
            .filter {
                it.rawSlug.contains(query, ignoreCase = true) ||
                    it.chineseName.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareBy<TagTranslationEntity>(
                    { if (it.source == TagTranslationSource.USER_CUSTOM) 0 else 1 },
                    { it.rawSlug.length },
                    { it.rawSlug },
                ),
            )
            .take(limit)

    override suspend fun upsert(entity: TagTranslationEntity) {
        rows.value = rows.value + (Triple(entity.source, entity.namespace, entity.rawSlug) to entity)
    }

    override suspend fun upsertAll(entities: List<TagTranslationEntity>) {
        rows.value = rows.value + entities.associateBy { Triple(it.source, it.namespace, it.rawSlug) }
    }

    override suspend fun delete(source: TagTranslationSource, namespace: String, rawSlug: String) {
        rows.value = rows.value - Triple(source, namespace, rawSlug)
    }

    override suspend fun deleteBySource(source: TagTranslationSource) {
        rows.value = rows.value.filterKeys { it.first != source }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

internal class FakeMediaTagCacheDao : MediaTagCacheDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, MediaTagCacheEntity>>(emptyMap())

    override fun observeAll(): Flow<List<MediaTagCacheEntity>> =
        rows.map { it.values.sortedByDescending(MediaTagCacheEntity::updatedAt) }

    override fun observeBySite(siteId: String): Flow<List<MediaTagCacheEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId }.sortedByDescending(MediaTagCacheEntity::updatedAt) }

    override suspend fun getOne(siteId: String, mediaId: String): MediaTagCacheEntity? =
        rows.value[siteId to mediaId]

    override suspend fun getBySite(siteId: String): List<MediaTagCacheEntity> =
        rows.value.values.filter { it.siteId == siteId }.sortedByDescending(MediaTagCacheEntity::updatedAt)

    override suspend fun upsert(entity: MediaTagCacheEntity) {
        rows.value = rows.value + ((entity.siteId to entity.mediaId) to entity)
    }

    override suspend fun upsertAll(entities: List<MediaTagCacheEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId to it.mediaId }
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override suspend fun prune(limit: Int) {
        val keep = rows.value.values
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .mapTo(mutableSetOf()) { it.siteId to it.mediaId }
        rows.value = rows.value.filterKeys { it in keep }
    }
}

internal class FakeBackupRestoreJournalDao : BackupRestoreJournalDao {
    val rows = MutableStateFlow<BackupRestoreJournalEntity?>(null)

    override suspend fun get(): BackupRestoreJournalEntity? = rows.value

    override suspend fun put(entry: BackupRestoreJournalEntity) {
        rows.value = entry
    }

    override suspend fun clear() {
        rows.value = null
    }
}

internal class FakeSeriesFollowDao : SeriesFollowDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, SeriesFollowEntity>>(emptyMap())

    override fun observeAll(): Flow<List<SeriesFollowEntity>> =
        rows.map { it.values.toList() }

    override fun observeBySite(siteId: String): Flow<List<SeriesFollowEntity>> =
        rows.map { it.values.filter { e -> e.siteId == siteId } }

    override suspend fun getOne(siteId: String, seriesId: String): SeriesFollowEntity? =
        rows.value[siteId to seriesId]

    override suspend fun getAll(): List<SeriesFollowEntity> =
        rows.value.values.toList()

    override suspend fun upsert(entity: SeriesFollowEntity) {
        rows.value = rows.value + ((entity.siteId to entity.seriesId) to entity)
    }

    override suspend fun upsertAll(entities: List<SeriesFollowEntity>) {
        rows.value = rows.value + entities.associateBy { it.siteId to it.seriesId }
    }

    override suspend fun updateSeen(siteId: String, seriesId: String, seenChapterIds: List<String>) {
        val cur = rows.value[siteId to seriesId] ?: return
        rows.value = rows.value + ((siteId to seriesId) to cur.copy(seenChapterIds = seenChapterIds))
    }

    override suspend fun updateLastChecked(siteId: String, seriesId: String, lastCheckedAt: Long) {
        val cur = rows.value[siteId to seriesId] ?: return
        rows.value = rows.value + ((siteId to seriesId) to cur.copy(lastCheckedAt = lastCheckedAt))
    }

    override suspend fun delete(siteId: String, seriesId: String) {
        rows.value = rows.value - (siteId to seriesId)
    }

    override suspend fun deleteBySite(siteId: String) {
        rows.value = rows.value.filterKeys { it.first != siteId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}
