@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    fun currentGeneration(id: String): Long = generations[id] ?: 0L

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

    val generationGuard: DeletionGenerationGuard = DeletionGenerationGuard()
    private val refreshedOnce = ConcurrentHashMap.newKeySet<String>()
    private val refreshJobs = ConcurrentHashMap<String, Job>()
    private val submittedIds = ConcurrentHashMap.newKeySet<String>()

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    val manager: DownloadManager by lazy {
        val downloaderFactory = HlsDownloadSupport.createDownloaderFactory(
            cache = downloadCaches.downloadCache(),
            upstreamFactoryProvider = { request ->
                val ownerSiteId = parseSiteIdFromDownloadId(request.id)
                transportFactory.createFactory(siteId = ownerSiteId)
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
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    val id = download.request.id
                    submittedIds.remove(id)
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
                    if (download.state == Download.STATE_FAILED &&
                        !generationGuard.isRemoving(id) &&
                        refreshedOnce.add(id)
                    ) {
                        val generation = generationGuard.currentGeneration(id)
                        val job = applicationScope.launch(dispatchers.io) {
                            try {
                                val resolvedMeta = meta ?: return@launch
                                val freshStream = refreshStream(resolvedMeta) ?: return@launch
                                ensureActive()
                                enqueueInternal(resolvedMeta, freshStream, expectedGeneration = generation)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                // Leave failed state intact in repository.
                            } finally {
                                refreshJobs.remove(id, coroutineContext[Job])
                            }
                        }
                        refreshJobs[id] = job
                    } else if (download.state == Download.STATE_COMPLETED) {
                        refreshedOnce.remove(id)
                        refreshJobs.remove(id)?.cancel()
                        if (meta != null) {
                            applicationScope.launch(dispatchers.io) {
                                runCatching { writeCompletedSidecar(download, meta) }
                            }
                        }
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

    fun setRequirements(requirements: Requirements) {
        manager.requirements = requirements
    }

    fun pauseAllForTimeout() {
        manager.pauseDownloads()
    }

    fun pauseAll() {
        manager.pauseDownloads()
    }

    fun resumeAll() {
        storageRootManager.checkDownloadsAllowed()
        manager.resumeDownloads()
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
        val current = manager.downloadIndex.getDownload(id)
        if (current?.state != Download.STATE_FAILED || id in submittedIds || generationGuard.isRemoving(id)) {
            return@withContext false
        }
        val meta = decodeMeta(current) ?: error("无法读取视频下载元数据")
        val freshStream = refreshStream(meta) ?: error("原清晰度 ${meta.quality} 暂不可用")
        val recheck = manager.downloadIndex.getDownload(id)?.state
        if (recheck != Download.STATE_FAILED || id in submittedIds || generationGuard.isStale(id, generation)) {
            return@withContext false
        }
        refreshedOnce.remove(id)
        withContext(dispatchers.main) {
            enqueueInternal(meta, freshStream, expectedGeneration = generation)
        }
        true
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

    fun decodeMeta(download: Download): VideoDownloadMeta? = runCatching {
        if (download.request.data.isEmpty()) return null
        json.decodeFromString(VideoDownloadMeta.serializer(), String(download.request.data, Charsets.UTF_8))
    }.getOrNull()

    fun isCached(cacheKey: String, sourceRoot: String = ""): Boolean =
        downloadCaches.isCached(cacheKey, sourceRoot)

    suspend fun projectAllToRepository() = withContext(dispatchers.io) {
        val current = listDownloads()
        for (download in current) {
            projectDownloadToRepository(download, finalException = null)
        }
    }

    private suspend fun projectDownloadToRepository(download: Download, finalException: Exception?) {
        val id = download.request.id
        if (generationGuard.isRemoving(id)) return
        val meta = decodeMeta(download) ?: return
        val existing = downloadStateRepository.getById(id)
        val status = mapMedia3State(download)
        val classified = if (status == DownloadStatus.FAILED && finalException != null) {
            DownloadErrorClassifier.classify(finalException, DownloadStage.DOWNLOADING_PAGE, clockMs())
        } else {
            null
        }
        val fraction = when {
            status == DownloadStatus.COMPLETED -> 1f
            download.percentDownloaded >= 0f -> (download.percentDownloaded / 100f).coerceIn(0f, 1f)
            else -> existing?.progressFraction ?: 0f
        }
        val totalBytes = download.contentLength.takeIf { it > 0L } ?: existing?.totalBytes
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
                downloadedBytes = download.bytesDownloaded.coerceAtLeast(0L),
                totalBytes = totalBytes,
                errorCategory = classified?.category
                    ?: if (status == DownloadStatus.FAILED) DownloadErrorCategory.NETWORK else null,
                errorMessage = classified?.message ?: if (status == DownloadStatus.FAILED) "视频下载失败" else null,
                priority = existing?.priority ?: meta.priority,
                storageLocation = storageRootManager.activeLocation(),
                localPath = storageRootManager.layout().root.absolutePath,
                createdAt = existing?.createdAt ?: meta.createdAt.takeIf { it > 0L } ?: download.startTimeMs,
                updatedAt = clockMs(),
            ),
        )
    }

    private fun writeCompletedSidecar(download: Download, meta: VideoDownloadMeta) {
        val layout = storageRootManager.layout()
        storageRootManager.writeVideoSidecar(
            DiskSidecarRecord(
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
                sourceRoot = layout.root.absolutePath,
            ),
            layout = layout,
        )
    }

    private fun cleanupRemoved(id: String, deferred: CompletableDeferred<Unit>) {
        applicationScope.launch(dispatchers.io) {
            try {
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
            } finally {
                generationGuard.completeRemoval(id, deferred)
            }
        }
    }

    private suspend fun refreshStream(meta: VideoDownloadMeta): VideoStream? {
        val plugin = siteRegistryProvider().get(meta.siteId) ?: return null
        val source = plugin.videoSource ?: return null
        val refreshed = source.refreshStreams(meta.url)
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

    private val notificationHelper by lazy {
        DownloadNotificationHelper(this, NOTIFICATION_CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager = engine.manager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, PLATFORM_SCHEDULER_JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_OPEN_DOWNLOADS, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                this,
                FOREGROUND_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return notificationHelper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            contentIntent,
            getString(R.string.raven_video_download_channel),
            downloads,
            notMetRequirements,
        )
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
