package com.raven.app.feature.library

import com.raven.app.core.media.download.BatchEnqueueProgress
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.download.DownloadNetworkPolicy
import com.raven.app.core.media.download.OfflineMediaHandle
import com.raven.app.core.media.reader.GalleryAudit
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Feature-level facade over [DownloadCoordinator] enabling pure-JVM ViewModel unit testing
 * while delegating 1:1 to [DownloadCoordinator] in production DI.
 */
interface LibraryDownloadGateway {
    val downloads: Flow<List<DownloadItem>>
    val completedCount: Flow<Int>
    val networkPolicy: StateFlow<DownloadNetworkPolicy>
    val batchProgress: StateFlow<BatchEnqueueProgress>

    fun enqueueBatch(items: List<MediaItem>, groupName: String = "")
    fun cancelBatch()
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun pauseAll()
    suspend fun resumeAll()
    suspend fun retryFailed(id: String): Boolean
    suspend fun retryAllFailed(ids: Collection<String>? = null): Int
    suspend fun delete(id: String, sourceRoot: String = "")
    suspend fun movePriority(id: String, delta: Int)
    suspend fun prioritize(id: String)
    suspend fun setGroup(id: String, groupName: String)
    suspend fun inspectIntegrity(id: String): GalleryAudit?
    suspend fun repair(id: String): DownloadItem?
    suspend fun syncFromDisk(): List<DownloadItem>
    suspend fun isOfflineReady(siteId: SiteId, mediaId: String, kind: MediaKind): Boolean
    suspend fun findOfflineHandle(siteId: SiteId, mediaId: String, kind: MediaKind): OfflineMediaHandle?
    suspend fun findOfflineHandles(items: List<MediaItem>): Map<String, OfflineMediaHandle>
}

/**
 * Production [LibraryDownloadGateway] backed directly by [DownloadCoordinator].
 */
class DownloadCoordinatorGateway(
    private val coordinator: DownloadCoordinator,
) : LibraryDownloadGateway {
    override val downloads: Flow<List<DownloadItem>> get() = coordinator.downloads
    override val completedCount: Flow<Int> get() = coordinator.completedCount
    override val networkPolicy: StateFlow<DownloadNetworkPolicy> get() = coordinator.networkPolicy
    override val batchProgress: StateFlow<BatchEnqueueProgress> get() = coordinator.batchProgress

    override fun enqueueBatch(items: List<MediaItem>, groupName: String) =
        coordinator.enqueueBatch(items, groupName)

    override fun cancelBatch() = coordinator.cancelBatch()

    override suspend fun pause(id: String) = coordinator.pause(id)

    override suspend fun resume(id: String) = coordinator.resume(id)

    override suspend fun pauseAll() = coordinator.pauseAll()

    override suspend fun resumeAll() = coordinator.resumeAll()

    override suspend fun retryFailed(id: String): Boolean = coordinator.retryFailed(id)

    override suspend fun retryAllFailed(ids: Collection<String>?): Int = coordinator.retryAllFailed(ids)

    override suspend fun delete(id: String, sourceRoot: String) = coordinator.delete(id, sourceRoot)

    override suspend fun movePriority(id: String, delta: Int) = coordinator.movePriority(id, delta)

    override suspend fun prioritize(id: String) = coordinator.prioritize(id)

    override suspend fun setGroup(id: String, groupName: String) = coordinator.setGroup(id, groupName)

    override suspend fun inspectIntegrity(id: String): GalleryAudit? = coordinator.inspectIntegrity(id)

    override suspend fun repair(id: String): DownloadItem? = coordinator.repair(id)

    override suspend fun syncFromDisk(): List<DownloadItem> = coordinator.syncFromDisk()

    override suspend fun isOfflineReady(siteId: SiteId, mediaId: String, kind: MediaKind): Boolean =
        coordinator.isOfflineReady(siteId, mediaId, kind)

    override suspend fun findOfflineHandle(
        siteId: SiteId,
        mediaId: String,
        kind: MediaKind,
    ): OfflineMediaHandle? = coordinator.findOfflineHandle(siteId, mediaId, kind)

    override suspend fun findOfflineHandles(items: List<MediaItem>): Map<String, OfflineMediaHandle> =
        coordinator.findOfflineHandles(items)
}
