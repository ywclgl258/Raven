@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.download

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.media.R
import com.raven.app.core.media.playback.DownloadCaches
import com.raven.app.core.media.playback.HlsDownloadSupport
import com.raven.app.core.media.playback.MediaTransportDataSourceFactory
import com.raven.app.core.media.playback.PlayerSessionController
import com.raven.app.core.media.storage.DiskSidecarRecord
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.VideoStream
import com.raven.app.core.sitespi.MediaTransportHook
import com.raven.app.core.sitespi.SiteRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext

/**
 * Pure-Kotlin deletion generation guard preventing stale asynchronous stream refreshes from
 * resurrecting a download record after the user deleted it.
 */
class DeletionGenerationGuard {
    private val generations = ConcurrentHashMap<String, Long>()
    private val inFlightRemovals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val globalRemovalGeneration = AtomicLong(0L)

    fun globalGeneration(): Long = globalRemovalGeneration.get()

    fun currentGeneration(id: String): Long = generations[id] ?: 0L

    /** Immutable snapshot of per-id generations, so a long scan can detect deletes started after it. */
    fun snapshotGenerations(): Map<String, Long> = generations.toMap()

    fun isRemoving(id: String): Boolean = inFlightRemovals.containsKey(id)

    fun removalDeferred(id: String): CompletableDeferred<Unit>? = inFlightRemovals[id]

    /**
     * Registers an exclusive removal token and increments [id]'s generation monotonically.
     * Returns `null` if a removal is already in flight for [id].
     */
    fun beginRemoval(id: String): CompletableDeferred<Unit>? {
        val deferred = CompletableDeferred<Unit>()
        if (inFlightRemovals.putIfAbsent(id, deferred) != null) return null
        generations.compute(id) { _, current -> (current ?: 0L) + 1L }
        globalRemovalGeneration.incrementAndGet()
        return deferred
    }

    fun completeRemoval(id: String, deferred: CompletableDeferred<Unit>) {
        inFlightRemovals.remove(id, deferred)
        deferred.complete(Unit)
    }

    /**
     * Returns `true` if a refresh operation started at [expectedGeneration] must abort because
     * [id] is currently being deleted or has been deleted since [expectedGeneration].
     */
    fun isStale(id: String, expectedGeneration: Long?): Boolean {
        if (expectedGeneration == null) return false
        return isRemoving(id) || expectedGeneration != currentGeneration(id)
    }
}

/**
 * Serialized metadata embedded in Media3 [DownloadRequest.data].
 */
@Serializable
data class VideoDownloadMeta(
    val siteId: SiteId,
    val mediaId: String,
    val title: String,
    val url: String,
    val coverUrl: String = "",
    val durationSeconds: Int? = null,
    val quality: String,
    val groupName: String = "",
    val priority: Int = 0,
    val createdAt: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Media3-backed video download engine (`maxParallelDownloads = 2`, `minRetryCount = 3`),
 * supporting MP4 Range + HLS segment downloads, 1-time stream URL refresh on failure guarded by
 * [DeletionGenerationGuard], disk sidecar persistence for reinstall recovery, and reactive
 * projection into [DownloadStateRepository].
 */
class VideoDownloadEngine(
    private val context: Context,
    private val downloadCaches: DownloadCaches,
    private val transportFactory: MediaTransportDataSourceFactory,
    private val storageRootManager: StorageRootManager,
    private val downloadStateRepository: DownloadStateRepository,
    private val siteRegistryProvider: () -> SiteRegistry,
    private val applicationScope: CoroutineScope,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private data class ProjectedProgressSnapshot(
        val state: Int,
        val bytesDownloaded: Long,
        val contentLength: Long,
        val percentDeci: Int,
    )

    val generationGuard: DeletionGenerationGuard = DeletionGenerationGuard()
    private val refreshedOnce = ConcurrentHashMap.newKeySet<String>()
    private val refreshJobs = ConcurrentHashMap<String, Job>()
    private val submittedIds = ConcurrentHashMap.newKeySet<String>()
    private val lastProjectedSnapshots = ConcurrentHashMap<String, ProjectedProgressSnapshot>()
    private val projectionMutex = Mutex()
    private val mutationMutex = Mutex()
    private var progressPollJob: Job? = null

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    val manager: DownloadManager by lazy {
        val downloaderFactory = HlsDownloadSupport.createDownloaderFactory(
            cache = downloadCaches.downloadCache(),
            upstreamFactoryProvider = { request ->
                val ownerSiteId = parseSiteIdFromDownloadId(request.id)
                val headers = decodeMetaBytes(request.data)?.headers.orEmpty()
                transportFactory.createFactory(siteId = ownerSiteId, extraHeaders = headers)
            },
            hookProvider = { request ->
                val ownerSiteId = parseSiteIdFromDownloadId(request.id)
                ownerSiteId?.let { siteRegistryProvider().get(it)?.mediaTransportHook }
                    ?: MediaTransportHook.None
            },
            executor = Executors.newFixedThreadPool(3),
        )

        DownloadManager(
            appContext,
            DefaultDownloadIndex(downloadCaches.databaseProvider),
            downloaderFactory,
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            minRetryCount = MIN_RETRY_COUNT
            addListener(object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) {
                    _changes.update { it + 1 }
                    applicationScope.launch(dispatchers.io) { projectAllToRepository() }
                    if (downloadManager.currentDownloads.isNotEmpty()) {
                        applicationScope.launch(dispatchers.main) { ensureProgressPolling() }
                    }
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    val id = download.request.id
                    submittedIds.remove(id)
                    lastProjectedSnapshots.remove(id)
                    val removal = generationGuard.removalDeferred(id) ?: generationGuard.beginRemoval(id)
                    if (removal != null) {
                        cleanupRemoved(id, removal)
                    }
                    _changes.update { it + 1 }
                }

                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    val id = download.request.id
                    submittedIds.remove(id)
                    val meta = decodeMeta(download)
                    val failureDetail = finalException?.let {
                        DownloadErrorClassifier.classify(it, DownloadStage.DOWNLOADING_PAGE, clockMs()).message
                    } ?: "视频下载失败"

                    if (download.state == Download.STATE_FAILED) {
                        lastProjectedSnapshots.remove(id)
                        if (!generationGuard.isRemoving(id) && refreshedOnce.add(id)) {
                            val generation = generationGuard.currentGeneration(id)
                            val job = applicationScope.launch(dispatchers.io) {
                                var refreshQueued = false
                                try {
                                    val resolvedMeta = meta
                                    val freshStream = resolvedMeta?.let { refreshStream(it) }
                                    ensureActive()
                                    if (resolvedMeta != null && freshStream != null) {
                                        enqueueInternal(resolvedMeta, freshStream, expectedGeneration = generation)
                                        refreshQueued = true
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: Exception) {
                                    // Leave failed state intact in repository.
                                } finally {
                                    refreshJobs.remove(id, coroutineContext[Job])
                                    if (!refreshQueued && !generationGuard.isRemoving(id)) {
                                        DownloadNotifications.notifyDownloadFailed(
                                            context = appContext,
                                            downloadId = id,
                                            title = meta?.title.orEmpty(),
                                            isVideo = true,
                                            errorDetail = failureDetail,
                                        )
                                    }
                                }
                            }
                            refreshJobs[id] = job
                        } else if (!generationGuard.isRemoving(id)) {
                            DownloadNotifications.notifyDownloadFailed(
                                context = appContext,
                                downloadId = id,
                                title = meta?.title.orEmpty(),
                                isVideo = true,
                                errorDetail = failureDetail,
                            )
                        }
                    } else if (download.state == Download.STATE_COMPLETED) {
                        lastProjectedSnapshots.remove(id)
                        refreshedOnce.remove(id)
                        refreshJobs.remove(id)?.cancel()
                        if (meta != null) {
                            val sizeText = download.bytesDownloaded.takeIf { it > 0L }
                                ?.let(DownloadNotifications::formatBytes)
                            val detail = listOfNotNull(
                                meta.quality.takeIf { it.isNotBlank() },
                                sizeText,
                            ).joinToString(" · ")
                            DownloadNotifications.notifyDownloadCompleted(
                                context = appContext,
                                downloadId = id,
                                title = meta.title,
                                isVideo = true,
                                detail = detail.ifBlank { null },
                            )
                        }
                    } else if (download.state == Download.STATE_STOPPED || download.state == Download.STATE_REMOVING) {
                        lastProjectedSnapshots.remove(id)
                    } else if (
                        download.state == Download.STATE_DOWNLOADING ||
                        download.state == Download.STATE_QUEUED ||
                        download.state == Download.STATE_RESTARTING
                    ) {
                        applicationScope.launch(dispatchers.main) { ensureProgressPolling() }
                    }

                    applicationScope.launch(dispatchers.io) {
                        projectDownloadToRepository(download, finalException)
                    }
                    _changes.update { it + 1 }
                }
            })
            if (storageRootManager.canWriteDownloads) {
                resumeDownloads()
            }
        }
    }

    private fun ensureProgressPolling() {
        if (progressPollJob?.isActive == true) return
        progressPollJob = applicationScope.launch(dispatchers.main) {
            try {
                while (true) {
                    val currentDownloads = manager.currentDownloads.toList()
                    if (currentDownloads.isEmpty()) break
                    val changed = currentDownloads.filter { dl ->
                        val percentDeci = if (dl.percentDownloaded >= 0f && dl.percentDownloaded.isFinite()) {
                            (dl.percentDownloaded * 10f).roundToInt()
                        } else {
                            -1
                        }
                        val snap = ProjectedProgressSnapshot(
                            state = dl.state,
                            bytesDownloaded = dl.bytesDownloaded,
                            contentLength = dl.contentLength,
                            percentDeci = percentDeci,
                        )
                        lastProjectedSnapshots.put(dl.request.id, snap) != snap
                    }
                    if (changed.isNotEmpty()) {
                        withContext(dispatchers.io) {
                            for (dl in changed) {
                                projectDownloadToRepository(dl, finalException = null)
                            }
                        }
                        _changes.update { it + 1 }
                    }
                    val hasActive = currentDownloads.any { dl ->
                        dl.state == Download.STATE_DOWNLOADING ||
                            dl.state == Download.STATE_QUEUED ||
                            dl.state == Download.STATE_RESTARTING
                    }
                    if (!hasActive) break
                    delay(PROGRESS_POLL_INTERVAL_MS)
                }
            } finally {
                progressPollJob = null
            }
        }
    }

    fun setRequirements(requirements: Requirements) {
        applicationScope.launch(dispatchers.main) {
            manager.requirements = requirements
        }
    }

    fun pauseAllForTimeout() {
        applicationScope.launch(dispatchers.main) {
            manager.pauseDownloads()
        }
    }

    fun pauseAll() {
        applicationScope.launch(dispatchers.main) {
            manager.pauseDownloads()
        }
    }

    suspend fun pauseAllAndWait() = withContext(dispatchers.main) {
        manager.pauseDownloads()
    }

    suspend fun awaitWriteQuiescence(timeoutMs: Long = 5_000L) = withContext(dispatchers.io) {
        val start = clockMs()
        while (clockMs() - start < timeoutMs) {
            val active = runCatching { manager.currentDownloads }.getOrNull().orEmpty().any { dl ->
                dl.state == Download.STATE_DOWNLOADING ||
                    dl.state == Download.STATE_QUEUED ||
                    dl.state == Download.STATE_RESTARTING
            }
            if (!active) break
            kotlinx.coroutines.delay(100)
        }
    }

    fun resumeAll() {
        storageRootManager.checkDownloadsAllowed()
        applicationScope.launch(dispatchers.main) {
            manager.resumeDownloads()
        }
    }

    fun enqueue(
        item: MediaItem,
        stream: VideoStream,
        groupName: String = "",
        priority: Int = 0,
    ): String {
        val quality = stream.quality.ifBlank { stream.label.ifBlank { "default" } }
        val meta = VideoDownloadMeta(
            siteId = item.siteId,
            mediaId = item.id,
            title = item.title,
            url = item.url,
            coverUrl = item.coverUrl,
            durationSeconds = item.durationSeconds,
            quality = quality,
            groupName = groupName.trim(),
            priority = priority,
            createdAt = clockMs(),
            headers = stream.headers,
        )
        return enqueueInternal(meta, stream, expectedGeneration = null)
    }

    private fun enqueueInternal(
        meta: VideoDownloadMeta,
        stream: VideoStream,
        expectedGeneration: Long?,
    ): String {
        storageRootManager.checkDownloadsAllowed()
        val id = PlayerSessionController.cacheKey(meta.siteId, meta.mediaId, meta.quality)
        if (generationGuard.isStale(id, expectedGeneration)) return id

        generationGuard.removalDeferred(id)?.let { pendingRemoval ->
            applicationScope.launch(dispatchers.main) {
                pendingRemoval.await()
                if (!generationGuard.isStale(id, expectedGeneration)) {
                    runCatching { enqueueInternal(meta, stream, expectedGeneration) }
                }
            }
            return id
        }
        if (generationGuard.isStale(id, expectedGeneration)) return id

        val mimeHint = transportFactory.resolveMimeTypeHint(meta.siteId, stream.url, stream.mimeTypeHint)
        val requestBuilder = DownloadRequest.Builder(id, stream.url.toUri())
            .setData(json.encodeToString(VideoDownloadMeta.serializer(), meta).toByteArray(Charsets.UTF_8))
        if (mimeHint == MimeTypes.APPLICATION_M3U8 || stream.isHls) {
            requestBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else {
            requestBuilder.setCustomCacheKey(id)
        }
        val request = requestBuilder.build()

        submittedIds.add(id)
        try {
            DownloadService.sendResumeDownloads(appContext, VideoDownloadService::class.java, false)
            DownloadService.sendAddDownload(appContext, VideoDownloadService::class.java, request, false)
            applicationScope.launch(dispatchers.io) {
                projectionMutex.withLock {
                    if (generationGuard.isRemoving(id) || generationGuard.isStale(id, expectedGeneration)) {
                        return@withLock
                    }
                    val existing = downloadStateRepository.getById(id)
                    if (existing != null &&
                        existing.status in setOf(
                            DownloadStatus.DOWNLOADING,
                            DownloadStatus.COMPLETED,
                            DownloadStatus.PAUSED,
                        )
                    ) {
                        return@withLock
                    }
                    downloadStateRepository.upsert(
                        DownloadItem(
                            id = id,
                            siteId = meta.siteId,
                            mediaId = meta.mediaId,
                            url = meta.url,
                            title = meta.title,
                            coverUrl = meta.coverUrl,
                            kind = DownloadKind.VIDEO,
                            status = DownloadStatus.QUEUED,
                            quality = meta.quality,
                            groupName = meta.groupName,
                            priority = meta.priority,
                            storageLocation = storageRootManager.activeLocation(),
                            localPath = storageRootManager.layout().root.absolutePath,
                            createdAt = meta.createdAt.takeIf { it > 0L } ?: clockMs(),
                            updatedAt = clockMs(),
                        ),
                    )
                }
            }
        } catch (error: Exception) {
            submittedIds.remove(id)
            throw error
        }
        return id
    }

    fun setPaused(id: String, paused: Boolean) {
        if (!paused) {
            storageRootManager.checkDownloadsAllowed()
            DownloadService.sendResumeDownloads(appContext, VideoDownloadService::class.java, false)
        }
        DownloadService.sendSetStopReason(
            appContext,
            VideoDownloadService::class.java,
            id,
            if (paused) STOP_REASON_MANUAL_PAUSE else Download.STOP_REASON_NONE,
            false,
        )
    }

    suspend fun retryFailed(id: String): Boolean = withContext(dispatchers.io) {
        storageRootManager.checkDownloadsAllowed()
        val generation = generationGuard.currentGeneration(id)
        refreshJobs[id]?.join()
        val current = runCatching { manager.downloadIndex.getDownload(id) }.getOrNull()
        if (id in submittedIds || generationGuard.isRemoving(id)) {
            return@withContext false
        }
        val retryable = current == null ||
            current.state == Download.STATE_FAILED ||
            current.state == Download.STATE_COMPLETED
        if (!retryable) {
            return@withContext false
        }
        val meta = (current?.let(::decodeMeta) ?: recoverMetaFromDiskOrDb(id))
            ?: error("无法读取视频下载元数据")
        if (current != null && current.state == Download.STATE_COMPLETED) {
            runCatching { manager.removeDownload(id) }
        }
        val freshStream = refreshStream(meta) ?: error("原清晰度 ${meta.quality} 暂不可用")
        val recheckState = runCatching { manager.downloadIndex.getDownload(id)?.state }.getOrNull()
        if (recheckState != null && recheckState != Download.STATE_FAILED && recheckState != Download.STATE_COMPLETED || id in submittedIds || generationGuard.isStale(id, generation)) {
            return@withContext false
        }
        refreshedOnce.remove(id)
        withContext(dispatchers.main) {
            enqueueInternal(meta, freshStream, expectedGeneration = generation)
        }
        true
    }

    private suspend fun recoverMetaFromDiskOrDb(id: String): VideoDownloadMeta? {
        val parts = id.split(':')
        if (parts.size < 3) return null
        val siteId = runCatching { SiteId.of(parts.first()) }.getOrNull() ?: return null
        val quality = parts.last()
        val mediaId = parts.subList(1, parts.size - 1).joinToString(":")
        for (layout in storageRootManager.readableLayouts()) {
            val record = storageRootManager.readSidecar(
                storageRootManager.videoSidecarFile(siteId, mediaId, quality, layout),
            ) ?: continue
            return VideoDownloadMeta(
                siteId = record.siteId,
                mediaId = record.mediaId,
                title = record.title,
                url = record.url,
                coverUrl = record.coverUrl,
                durationSeconds = record.durationSeconds,
                quality = record.quality.ifBlank { quality },
                headers = emptyMap(),
            )
        }
        val dbItem = runCatching { downloadStateRepository.getById(id) }.getOrNull() ?: return null
        return VideoDownloadMeta(
            siteId = dbItem.siteId,
            mediaId = dbItem.mediaId,
            title = dbItem.title,
            url = dbItem.url,
            coverUrl = dbItem.coverUrl,
            durationSeconds = null,
            quality = dbItem.quality.orEmpty().ifBlank { quality },
            headers = emptyMap(),
        )
    }

    fun remove(id: String) {
        val removal = generationGuard.beginRemoval(id) ?: return
        refreshedOnce.remove(id)
        refreshJobs.remove(id)?.cancel()
        try {
            val existing = runCatching { manager.downloadIndex.getDownload(id) }.getOrNull()
            if (id !in submittedIds && existing == null) {
                cleanupRemoved(id, removal)
            } else {
                DownloadService.sendRemoveDownload(appContext, VideoDownloadService::class.java, id, false)
            }
        } catch (error: Exception) {
            generationGuard.completeRemoval(id, removal)
            throw error
        }
    }

    fun listDownloads(): List<Download> = buildList {
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) add(cursor.download)
        }
    }

    fun decodeMeta(download: Download): VideoDownloadMeta? = decodeMetaBytes(download.request.data)

    private fun decodeMetaBytes(data: ByteArray): VideoDownloadMeta? = runCatching {
        if (data.isEmpty()) return null
        json.decodeFromString(VideoDownloadMeta.serializer(), String(data, Charsets.UTF_8))
    }.getOrNull()

    fun isCached(cacheKey: String, sourceRoot: String = ""): Boolean =
        downloadCaches.isCached(cacheKey, sourceRoot)

    internal fun removalGeneration(): Long = generationGuard.globalGeneration()

    internal suspend fun <T> withMutationLock(block: suspend () -> T): T = mutationMutex.withLock { block() }

    suspend fun projectAllToRepository() = withContext(dispatchers.io) {
        val current = listDownloads()
        for (download in current) {
            projectDownloadToRepository(download, finalException = null)
        }
    }

    private suspend fun projectDownloadToRepository(download: Download, finalException: Exception?) {
        projectionMutex.withLock {
            val id = download.request.id
            val generation = generationGuard.currentGeneration(id)
            if (generationGuard.isStale(id, generation)) return
            val authoritative = runCatching { manager.downloadIndex.getDownload(id) }.getOrNull() ?: return
            if (generationGuard.isStale(id, generation)) return
            val meta = decodeMeta(authoritative) ?: return
            val existing = downloadStateRepository.getById(id)
            val status = mapMedia3State(authoritative)
            var recoveryIndexError: String? = null
            if (status == DownloadStatus.COMPLETED) {
                mutationMutex.withLock {
                    if (generationGuard.isStale(id, generation)) return
                    try {
                        writeCompletedSidecar(authoritative, meta)
                    } catch (_: Exception) {
                        // Keep the media completion state, but expose that reinstall recovery needs retry.
                        recoveryIndexError = "下载已完成，但恢复索引保存失败，将在下次启动自动重试"
                    }
                }
            }
            val classified = if (status == DownloadStatus.FAILED && finalException != null) {
                DownloadErrorClassifier.classify(finalException, DownloadStage.DOWNLOADING_PAGE, clockMs())
            } else {
                null
            }
            val fraction = DownloadNotifications.computeVideoProgressFraction(
                percentDownloaded = authoritative.percentDownloaded,
                bytesDownloaded = authoritative.bytesDownloaded,
                contentLength = authoritative.contentLength,
                isCompleted = status == DownloadStatus.COMPLETED,
                fallback = if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSED) {
                    existing?.progressFraction ?: 0f
                } else {
                    0f
                },
            )
            val totalBytes = authoritative.contentLength.takeIf { it > 0L } ?: existing?.totalBytes
            mutationMutex.withLock {
                if (generationGuard.isStale(id, generation)) return
                downloadStateRepository.upsert(
                    DownloadItem(
                        id = id,
                        siteId = meta.siteId,
                        mediaId = meta.mediaId,
                        url = meta.url,
                        title = meta.title,
                        coverUrl = meta.coverUrl,
                        kind = DownloadKind.VIDEO,
                        status = status,
                        quality = meta.quality,
                        groupName = existing?.groupName ?: meta.groupName,
                        progressFraction = fraction,
                        completedUnits = if (status == DownloadStatus.COMPLETED) 1 else 0,
                        totalUnits = 1,
                        downloadedBytes = authoritative.bytesDownloaded.coerceAtLeast(0L),
                        totalBytes = totalBytes,
                        errorCategory = classified?.category
                            ?: if (status == DownloadStatus.FAILED) {
                                DownloadErrorCategory.NETWORK
                            } else {
                                recoveryIndexError?.let { DownloadErrorCategory.STORAGE }
                            },
                        errorMessage = classified?.message
                            ?: if (status == DownloadStatus.FAILED) "视频下载失败" else recoveryIndexError,
                        priority = existing?.priority ?: meta.priority,
                        storageLocation = existing?.storageLocation ?: storageRootManager.activeLocation(),
                        localPath = existing?.localPath ?: storageRootManager.layout().root.absolutePath,
                        createdAt = existing?.createdAt ?: meta.createdAt.takeIf { it > 0L } ?: authoritative.startTimeMs,
                        updatedAt = clockMs(),
                    ),
                )
            }
        }
    }

    private fun writeCompletedSidecar(download: Download, meta: VideoDownloadMeta) {
        val layout = storageRootManager.layout()
        val rootPath = layout.root.absolutePath
        // Media3's index survives root changes; never project an old download into a root that
        // does not actually contain its complete cache spans.
        if (!isCached(download.request.id, rootPath)) return
        val target = storageRootManager.videoSidecarFile(meta.siteId, meta.mediaId, meta.quality, layout)
        val existing = storageRootManager.readSidecar(target)
        val record = DiskSidecarRecord(
            kind = DownloadKind.VIDEO,
            siteId = meta.siteId,
            mediaId = meta.mediaId,
            quality = meta.quality,
            cacheKey = download.request.id,
            title = meta.title,
            url = meta.url,
            coverUrl = meta.coverUrl,
            durationSeconds = meta.durationSeconds,
            bytes = download.bytesDownloaded.coerceAtLeast(0L),
            updatedAt = clockMs(),
            status = DownloadStatus.COMPLETED,
            sourceRoot = rootPath,
        )
        val unchanged = existing?.copy(
            updatedAt = record.updatedAt,
            versionCode = record.versionCode,
            sourceRoot = record.sourceRoot,
        ) == record
        if (unchanged) return
        storageRootManager.writeVideoSidecar(record, layout = layout)
    }

    private fun cleanupRemoved(id: String, deferred: CompletableDeferred<Unit>) {
        applicationScope.launch(dispatchers.io) {
            try {
                mutationMutex.withLock {
                    runCatching { downloadCaches.removeDownload(id) }
                    val parts = id.split(':')
                    if (parts.size >= 3) {
                        val siteId = runCatching { SiteId.of(parts.first()) }.getOrNull()
                        val mediaId = parts.subList(1, parts.size - 1).joinToString(":")
                        val quality = parts.last()
                        if (siteId != null) {
                            runCatching { storageRootManager.removeVideoSidecar(siteId, mediaId, quality) }
                        }
                    }
                    runCatching { downloadStateRepository.delete(id) }
                }
            } finally {
                generationGuard.completeRemoval(id, deferred)
            }
        }
    }

    private suspend fun refreshStream(meta: VideoDownloadMeta): VideoStream? {
        val plugin = siteRegistryProvider().get(meta.siteId) ?: return null
        val source = plugin.videoSource ?: return null
        val refreshed = source.refreshDownloadStreams(meta.url)
        return refreshed.firstOrNull {
            it.quality.equals(meta.quality, ignoreCase = true) ||
                it.label.equals(meta.quality, ignoreCase = true)
        }
    }

    private fun parseSiteIdFromDownloadId(id: String): SiteId? =
        id.substringBefore(':').takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { SiteId.of(raw) }.getOrNull()
        }

    companion object {
        const val MAX_PARALLEL_DOWNLOADS: Int = 2
        const val MIN_RETRY_COUNT: Int = 3
        const val STOP_REASON_MANUAL_PAUSE: Int = 1
        const val PROGRESS_POLL_INTERVAL_MS: Long = 500L

        fun mapMedia3State(download: Download): DownloadStatus = when (download.state) {
            Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
            Download.STATE_FAILED -> DownloadStatus.FAILED
            Download.STATE_STOPPED -> DownloadStatus.PAUSED
            Download.STATE_REMOVING -> DownloadStatus.CANCELLED
            Download.STATE_QUEUED,
            Download.STATE_RESTARTING,
            -> DownloadStatus.QUEUED
            else -> DownloadStatus.QUEUED
        }
    }
}

/**
 * Media3 `dataSync` foreground [DownloadService] for video downloads with API 35 `onTimeout` graceful pause.
 */
class VideoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    NOTIFICATION_CHANNEL_ID,
    R.string.raven_video_download_channel,
    0,
) {
    private val engine: VideoDownloadEngine
        get() = GlobalContext.get().get()

    override fun getDownloadManager(): DownloadManager = engine.manager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, PLATFORM_SCHEDULER_JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        DownloadNotifications.ensureChannels(this)
        val primary = downloads.firstOrNull { it.state == Download.STATE_DOWNLOADING }
            ?: downloads.firstOrNull()
        val activeCount = downloads.count {
            it.state == Download.STATE_DOWNLOADING ||
                it.state == Download.STATE_QUEUED ||
                it.state == Download.STATE_RESTARTING
        }
        val title = primary?.let { engine.decodeMeta(it)?.title }.orEmpty()
        val waitingForNetwork = notMetRequirements != 0
        val fraction = primary?.let {
            DownloadNotifications.computeVideoProgressFraction(
                percentDownloaded = it.percentDownloaded,
                bytesDownloaded = it.bytesDownloaded,
                contentLength = it.contentLength,
                isCompleted = it.state == Download.STATE_COMPLETED,
            )
        } ?: 0f
        val statusText = primary?.let {
            DownloadNotifications.formatVideoProgressText(
                title = title,
                percentDownloaded = it.percentDownloaded,
                bytesDownloaded = it.bytesDownloaded,
                contentLength = it.contentLength,
                waitingForNetwork = waitingForNetwork,
            )
        } ?: "正在准备视频下载任务"
        val channelTitle = if (activeCount > 1) {
            "${getString(R.string.raven_video_download_channel)}（$activeCount 个任务）"
        } else {
            getString(R.string.raven_video_download_channel)
        }
        val contentIntent = DownloadNotifications.buildOpenDownloadsPendingIntent(
            context = this,
            requestCode = FOREGROUND_NOTIFICATION_ID,
        )
        val determinate = !waitingForNetwork && fraction > 0f
        val progressPercent = (fraction * 100f).roundToInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(channelTitle)
            .setContentText(statusText)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(if (determinate) 100 else 0, if (determinate) progressPercent else 0, !determinate)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { engine.pauseAllForTimeout() }
        stopSelf()
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID: Int = 40
        const val PLATFORM_SCHEDULER_JOB_ID: Int = 41
        const val NOTIFICATION_CHANNEL_ID: String = "raven-video-downloads"
        const val EXTRA_OPEN_DOWNLOADS: String = "raven_open_downloads"
    }
}
