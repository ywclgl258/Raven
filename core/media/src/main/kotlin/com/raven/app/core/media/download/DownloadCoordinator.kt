package com.raven.app.core.media.download

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.media.playback.DownloadCaches
import com.raven.app.core.media.playback.StreamSelector
import com.raven.app.core.media.reader.GalleryAudit
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.storage.DiskSidecarRecord
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.media.storage.StorageMigrationResult
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.VideoStream
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Progress snapshot for cancellable multi-item batch download enqueueing.
 */
data class BatchEnqueueProgress(
    val total: Int = 0,
    val processed: Int = 0,
    val added: Int = 0,
    val errors: List<String> = emptyList(),
    val isRunning: Boolean = false,
)

/**
 * Resolved local/offline playback or reading target for a [MediaItem].
 */
sealed interface OfflineMediaHandle {
    data class OfflineVideo(
        val cacheKey: String,
        val sourceRoot: String,
        val quality: String,
    ) : OfflineMediaHandle

    data class OfflineGallery(
        val sourceRoot: String,
        val isComplete: Boolean,
        val availablePages: List<Int> = emptyList(),
    ) : OfflineMediaHandle

    data class LocalVideo(
        val bookId: String,
        val filePath: String,
    ) : OfflineMediaHandle

    data class LocalComic(
        val bookId: String,
        val pageCount: Int,
    ) : OfflineMediaHandle
}

/**
 * Unified domain facade coordinating [VideoDownloadEngine], [GalleryDownloadEngine], disk-recovered
 * sidecars ([StorageRootManager]), and [DownloadStateRepository] (Room).
 *
 * Projects all downloads into Room `download_items` so `ShelfRepository`, `SmartShelfEvaluator`,
 * and Downloads UI consume one reactive source of truth.
 */
class DownloadCoordinator(
    private val videoDownloadEngine: VideoDownloadEngine,
    private val galleryDownloadEngine: GalleryDownloadEngine,
    private val downloadPolicyController: DownloadPolicyController,
    private val downloadStateRepository: DownloadStateRepository,
    private val storageRootManager: StorageRootManager,
    private val downloadCaches: DownloadCaches,
    private val galleryPageRepository: GalleryPageRepository,
    private val localLibraryImporter: LocalLibraryImporter,
    private val settingsDataStore: SettingsDataStore,
    private val siteRegistryProvider: () -> SiteRegistry,
    private val applicationScope: CoroutineScope,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    val downloads: Flow<List<DownloadItem>> = downloadStateRepository.observeAll()
    val completedCount: Flow<Int> = downloadStateRepository.observeCompletedCount()
    val networkPolicy: StateFlow<DownloadNetworkPolicy> = downloadPolicyController.policy

    private val _batchProgress = MutableStateFlow(BatchEnqueueProgress())
    val batchProgress: StateFlow<BatchEnqueueProgress> = _batchProgress.asStateFlow()

    @Volatile
    private var batchJob: Job? = null

    fun observeBySite(siteId: SiteId): Flow<List<DownloadItem>> =
        downloadStateRepository.observeBySite(siteId)

    fun observeByMedia(siteId: SiteId, mediaId: String): Flow<List<DownloadItem>> =
        downloadStateRepository.observeByMedia(siteId, mediaId)

    fun observeById(id: String): Flow<DownloadItem?> =
        downloadStateRepository.observeById(id)

    /**
     * Initializes download policy sync, resumes pending downloads if permitted, and reconciles
     * disk sidecars into Room (surviving reinstalls).
     */
    fun start() {
        downloadPolicyController.start()
        applicationScope.launch(dispatchers.io) {
            runCatching { syncFromDisk() }
            runCatching { galleryDownloadEngine.resumePending() }
        }
    }

    // ---- Enqueueing (Single & Batch) ----

    fun enqueueVideo(
        item: MediaItem,
        stream: VideoStream,
        groupName: String = "",
        priority: Int = 0,
    ): String = videoDownloadEngine.enqueue(item, stream, groupName = groupName, priority = priority)

    suspend fun enqueueVideoWithPreferredQuality(
        item: MediaItem,
        groupName: String = "",
    ): String = withContext(dispatchers.io) {
        val plugin = siteRegistryProvider().require(item.siteId)
        val source = plugin.videoSource ?: error("站点 ${item.siteId.value} 不支持视频下载")
        val detail = source.loadVideoDetail(item.url)
        val candidates = detail.downloadStreams.ifEmpty { detail.streams }
        val preferred = settingsDataStore.currentSettings().preferredQuality
        val chosen = StreamSelector.preferred(candidates, preferred) ?: error("没有可下载的视频流")
        enqueueVideo(item, chosen, groupName = groupName)
    }

    suspend fun enqueueGallery(
        item: MediaItem,
        selectedPages: Collection<Int>? = null,
        groupName: String = "",
        recheckSavedPages: Boolean = false,
    ): DownloadItem = galleryDownloadEngine.enqueue(
        item = item,
        selectedPages = selectedPages,
        groupName = groupName,
        recheckSavedPages = recheckSavedPages,
    )

    fun enqueueBatch(
        items: List<MediaItem>,
        groupName: String = "",
    ) {
        check(batchJob?.isActive != true) { "已有一批作品正在加入下载，请稍候" }
        val unique = items.distinctBy { it.compositeKey }
        _batchProgress.value = BatchEnqueueProgress(total = unique.size, isRunning = true)

        batchJob = applicationScope.launch(dispatchers.io) {
            try {
                for (item in unique) {
                    ensureActive()
                    try {
                        if (item.kind.isGallery) {
                            enqueueGallery(item, groupName = groupName)
                        } else {
                            enqueueVideoWithPreferredQuality(item, groupName = groupName)
                        }
                        _batchProgress.value = _batchProgress.value.let {
                            it.copy(processed = it.processed + 1, added = it.added + 1)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (error: Exception) {
                        val siteName = siteRegistryProvider().get(item.siteId)?.descriptor?.displayName
                            ?: item.siteId.value
                        _batchProgress.value = _batchProgress.value.let {
                            it.copy(
                                processed = it.processed + 1,
                                errors = it.errors + "$siteName · ${item.title}：${error.message ?: "添加失败"}",
                            )
                        }
                    }
                }
            } finally {
                _batchProgress.value = _batchProgress.value.copy(isRunning = false)
            }
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
    }

    // ---- Unified Task Controls ----

    suspend fun pause(id: String) = withContext(dispatchers.io) {
        val item = downloadStateRepository.getById(id) ?: return@withContext
        if (item.kind == DownloadKind.VIDEO) {
            videoDownloadEngine.setPaused(id, paused = true)
        } else {
            galleryDownloadEngine.pause(id)
        }
    }

    suspend fun resume(id: String) = withContext(dispatchers.io) {
        val item = downloadStateRepository.getById(id) ?: return@withContext
        if (item.kind == DownloadKind.VIDEO) {
            videoDownloadEngine.setPaused(id, paused = false)
        } else {
            galleryDownloadEngine.resume(id)
        }
    }

    suspend fun pauseAll() = withContext(dispatchers.io) {
        videoDownloadEngine.pauseAll()
        galleryDownloadEngine.allTasks()
            .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.RETRY_WAIT }
            .forEach { galleryDownloadEngine.pause(it.id) }
    }

    suspend fun resumeAll() = withContext(dispatchers.io) {
        storageRootManager.checkDownloadsAllowed()
        videoDownloadEngine.resumeAll()
        galleryDownloadEngine.allTasks()
            .filter { it.status == DownloadStatus.PAUSED }
            .forEach { galleryDownloadEngine.resume(it.id) }
    }

    suspend fun retryFailed(id: String): Boolean = withContext(dispatchers.io) {
        val item = downloadStateRepository.getById(id) ?: return@withContext false
        if (item.kind == DownloadKind.VIDEO) {
            videoDownloadEngine.retryFailed(id)
        } else {
            galleryDownloadEngine.retryFailed(id)
        }
    }

    suspend fun retryAllFailed(ids: Collection<String>? = null): Int = withContext(dispatchers.io) {
        val targetSet = ids?.toSet()
        val failedItems = downloadStateRepository.observeByStatus(DownloadStatus.FAILED).first()
            .filter { targetSet == null || it.id in targetSet }
        var retried = 0
        for (item in failedItems) {
            if (runCatching { retryFailed(item.id) }.getOrDefault(false)) {
                retried += 1
            }
        }
        retried
    }

    suspend fun delete(id: String, sourceRoot: String = "") = withContext(dispatchers.io) {
        val item = downloadStateRepository.getById(id)
        when {
            item == null -> {
                videoDownloadEngine.remove(id)
                downloadStateRepository.delete(id)
            }
            item.kind == DownloadKind.VIDEO -> {
                videoDownloadEngine.remove(id)
            }
            else -> {
                galleryDownloadEngine.remove(id, sourceRoot = sourceRoot)
            }
        }
    }

    suspend fun movePriority(id: String, delta: Int) = withContext(dispatchers.io) {
        galleryDownloadEngine.movePriority(id, delta)
    }

    suspend fun prioritize(id: String) = withContext(dispatchers.io) {
        galleryDownloadEngine.prioritize(id)
    }

    suspend fun setGroup(id: String, groupName: String) = withContext(dispatchers.io) {
        downloadStateRepository.updateGroupName(id, groupName.trim())
    }

    suspend fun setGroupForItems(ids: Collection<String>, groupName: String) = withContext(dispatchers.io) {
        downloadStateRepository.updateGroupNames(ids, groupName.trim())
    }

    suspend fun inspectIntegrity(id: String): GalleryAudit? = withContext(dispatchers.io) {
        val item = downloadStateRepository.getById(id) ?: return@withContext null
        if (item.kind == DownloadKind.VIDEO) null else galleryDownloadEngine.inspect(id)
    }

    suspend fun repair(id: String): DownloadItem? = withContext(dispatchers.io) {
        val item = downloadStateRepository.getById(id) ?: return@withContext null
        if (item.kind == DownloadKind.VIDEO) {
            if (item.status == DownloadStatus.FAILED) {
                videoDownloadEngine.retryFailed(id)
            }
            downloadStateRepository.getById(id)
        } else {
            galleryDownloadEngine.repair(id)
        }
    }

    // ---- Disk Sidecar Recovery & Room Projection ----

    /**
     * Reconciles live Media3 downloads + on-disk sidecars across all readable storage roots into
     * Room `download_items` so reinstalls recover all offline books & videos automatically.
     */
    suspend fun syncFromDisk(): List<DownloadItem> = withContext(dispatchers.io) {
        runCatching { videoDownloadEngine.projectAllToRepository() }
        val sidecars = storageRootManager.scanDiskSidecars(
            isVideoCached = { key, root -> downloadCaches.isCached(key, root) },
            inspectComicFolder = { siteId, folder, root ->
                galleryPageRepository.inspectDiskBookForRecovery(siteId, folder, root)
            },
        )
        val projected = mutableListOf<DownloadItem>()
        for (record in sidecars) {
            val id = record.compositeDownloadId
            val existing = downloadStateRepository.getById(id)
            // Do not clobber an active in-progress/paused task with a disk scan snapshot.
            if (existing != null && existing.status in setOf(
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.QUEUED,
                    DownloadStatus.RETRY_WAIT,
                )
            ) {
                continue
            }
            val item = sidecarToDownloadItem(record, existing)
            downloadStateRepository.upsert(item)
            projected += item
        }
        projected
    }

    // ---- Offline Availability Lookup (for ShelfRepository / SmartShelf / Queue / Player / Reader) ----

    suspend fun isOfflineReady(siteId: SiteId, mediaId: String, kind: MediaKind): Boolean =
        findOfflineHandle(siteId, mediaId, kind) != null

    suspend fun findOfflineHandle(
        siteId: SiteId,
        mediaId: String,
        kind: MediaKind,
    ): OfflineMediaHandle? = withContext(dispatchers.io) {
        if (siteId.isLocal) {
            return@withContext if (kind.isVideo) {
                val file = localLibraryImporter.getVideoFile(mediaId) ?: return@withContext null
                if (localLibraryImporter.isLocalVideoComplete(mediaId, file.absolutePath)) {
                    OfflineMediaHandle.LocalVideo(mediaId, file.absolutePath)
                } else {
                    null
                }
            } else {
                val book = localLibraryImporter.getBook(mediaId) ?: return@withContext null
                if (localLibraryImporter.isLocalComicComplete(mediaId)) {
                    OfflineMediaHandle.LocalComic(mediaId, book.files.size)
                } else {
                    null
                }
            }
        }

        if (kind.isGallery) {
            val roots = storageRootManager.readableLayouts().map { it.root.absolutePath }
            for (root in roots) {
                if (galleryPageRepository.isDownloaded(siteId, mediaId, root)) {
                    return@withContext OfflineMediaHandle.OfflineGallery(
                        sourceRoot = root,
                        isComplete = true,
                    )
                }
            }
            return@withContext null
        }

        // Video: check Room records first, then fallback to disk sidecars.
        val rows = downloadStateRepository.getByMedia(siteId, mediaId)
            .filter { it.kind == DownloadKind.VIDEO && it.status == DownloadStatus.COMPLETED }
            .sortedByDescending { it.updatedAt }
        val roots = storageRootManager.readableLayouts().map { it.root.absolutePath }
        for (row in rows) {
            val matchingRoot = roots.firstOrNull { root -> downloadCaches.isCached(row.id, root) }
            if (matchingRoot != null) {
                return@withContext OfflineMediaHandle.OfflineVideo(
                    cacheKey = row.id,
                    sourceRoot = matchingRoot,
                    quality = row.quality.orEmpty(),
                )
            }
        }
        null
    }

    suspend fun findOfflineHandles(items: List<MediaItem>): Map<String, OfflineMediaHandle> =
        withContext(dispatchers.io) {
            val result = LinkedHashMap<String, OfflineMediaHandle>()
            for (item in items.distinctBy { it.compositeKey }) {
                ensureActive()
                findOfflineHandle(item.siteId, item.id, item.kind)?.let { handle ->
                    result[item.compositeKey] = handle
                }
            }
            result
        }

    // ---- Coordinated Storage Migration ----

    suspend fun migrateToSharedStorage(
        onProgress: (doneBytes: Long, totalBytes: Long, currentFileName: String) -> Unit = { _, _, _ -> },
    ): StorageMigrationResult {
        storageRootManager.beginMigration()
        return try {
            videoDownloadEngine.pauseAll()
            galleryDownloadEngine.withWritersStopped {
                storageRootManager.migratePrivateToShared(
                    copyVideos = {
                        downloadCaches.copyPrivateDownloadsToShared { copied, total ->
                            onProgress(copied, total, "正在迁移缓存视频")
                        }
                    },
                    isCompleteComicBook = { folder -> galleryPageRepository.isCompleteBookDirectory(folder) },
                    onProgress = onProgress,
                )
            }
        } finally {
            storageRootManager.endMigration()
        }
    }

    private fun sidecarToDownloadItem(
        record: DiskSidecarRecord,
        existing: DownloadItem?,
    ): DownloadItem {
        val storageLoc = if (storageRootManager.isSharedSource(record.sourceRoot)) {
            StorageLocation.SHARED
        } else {
            StorageLocation.PRIVATE
        }
        val totalUnits = if (record.kind == DownloadKind.VIDEO) 1 else record.totalPages
        val completedUnits = if (record.kind == DownloadKind.VIDEO) {
            if (record.status == DownloadStatus.COMPLETED) 1 else 0
        } else {
            record.completedPages
        }
        val fraction = when {
            record.status == DownloadStatus.COMPLETED -> 1f
            totalUnits > 0 -> (completedUnits.toFloat() / totalUnits.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
        return DownloadItem(
            id = record.compositeDownloadId,
            siteId = record.siteId,
            mediaId = record.mediaId,
            url = record.url,
            title = record.title,
            coverUrl = record.coverUrl,
            kind = record.kind,
            status = record.status,
            quality = record.quality.takeIf { it.isNotBlank() },
            groupName = existing?.groupName.orEmpty(),
            progressFraction = fraction,
            completedUnits = completedUnits,
            totalUnits = totalUnits,
            downloadedBytes = record.bytes,
            totalBytes = if (record.status == DownloadStatus.COMPLETED) record.bytes else existing?.totalBytes,
            selectedPages = record.intactSelection,
            errorCategory = if (record.status == DownloadStatus.FAILED) existing?.errorCategory else null,
            errorMessage = record.detail.takeIf { it.isNotBlank() },
            priority = existing?.priority ?: 0,
            storageLocation = storageLoc,
            localPath = record.sourceRoot.takeIf { it.isNotBlank() },
            createdAt = existing?.createdAt ?: record.updatedAt.takeIf { it > 0L } ?: clockMs(),
            updatedAt = record.updatedAt.takeIf { it > 0L } ?: clockMs(),
        )
    }
}
