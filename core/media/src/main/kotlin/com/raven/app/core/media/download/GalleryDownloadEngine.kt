package com.raven.app.core.media.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.media.R
import com.raven.app.core.media.reader.GalleryAudit
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.reader.GalleryStorageException
import com.raven.app.core.media.storage.DiskSidecarRecord
import com.raven.app.core.media.storage.StorageLayout
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * Serial page-by-page comic/photo download engine backed by [DownloadStateRepository] (Room) and
 * [GalleryPageRepository] atomic `.part` + `book.json` / `complete` disk storage.
 *
 * Supports selective page ranges, priority/group management, typed [DownloadErrorCategory] +
 * `Retry-After` `RETRY_WAIT` scheduling, integrity audit, and targeted repair of missing/corrupt pages.
 */
class GalleryDownloadEngine(
    private val context: Context?,
    private val galleryPageRepository: GalleryPageRepository,
    private val storageRootManager: StorageRootManager,
    private val downloadStateRepository: DownloadStateRepository,
    private val applicationScope: CoroutineScope,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val networkAllowedProvider: () -> Boolean = { true },
    private val preferOriginalProvider: suspend (MediaItem) -> Boolean = { false },
    private val serviceStarter: (() -> Unit)? = null,
    private val notificationPrivacyProvider: () -> Boolean = { false },
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val concurrencyProvider: suspend (SiteId) -> Int = { siteId ->
        galleryPageRepository.recommendedImageConcurrency(siteId)
    },
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val retryTimerJobs = ConcurrentHashMap<String, Job>()
    private val maintenanceLock = Mutex()
    private val stateMutationLock = Mutex()

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    internal fun isNotificationPrivacySensitive(): Boolean = notificationPrivacyProvider()

    val canRun: Boolean
        get() = galleryPageRepository.downloadsAllowed && networkAllowedProvider()

    fun downloadId(item: MediaItem): String = "${item.siteId.value}:${item.id}"

    suspend fun allTasks(): List<DownloadItem> =
        downloadStateRepository.observeAll().first().filter { it.kind != DownloadKind.VIDEO }

    suspend fun getTask(id: String): DownloadItem? =
        downloadStateRepository.getById(id)?.takeIf { it.kind != DownloadKind.VIDEO }

    suspend fun enqueue(
        item: MediaItem,
        selectedPages: Collection<Int>? = null,
        groupName: String = "",
        recheckSavedPages: Boolean = false,
    ): DownloadItem = withContext(dispatchers.io) {
        galleryPageRepository.checkDownloadsAllowed()
        require(item.kind.isGallery && item.id.isNotBlank()) { "仅支持图集或写真作品下载" }
        val normalizedPages = selectedPages?.map { page ->
            require(page > 0) { "页码必须大于零" }
            page
        }?.distinct()?.sorted()
        require(normalizedPages == null || normalizedPages.isNotEmpty()) { "请至少选择一页" }

        val id = downloadId(item)
        val kind = if (item.kind == MediaKind.PHOTO) DownloadKind.PHOTO else DownloadKind.COMIC

        val queuedItem = stateMutationLock.withLock {
            galleryPageRepository.checkDownloadsAllowed()
            val previous = getTask(id)
            if (previous != null && (previous.status in ACTIVE_STATUSES || activeJobs.containsKey(id))) {
                val existingSelection = previous.selectedPages.takeIf { it.isNotEmpty() }
                check(previous.status in ACTIVE_STATUSES && normalizedPages == existingSelection) {
                    "当前图集已有不同页码范围的下载任务，请先暂停再修改"
                }
                return@withLock previous
            }
            if (!recheckSavedPages &&
                normalizedPages == null &&
                previous?.status == DownloadStatus.COMPLETED &&
                previous.selectedPages.isEmpty() &&
                galleryPageRepository.isDownloaded(item.siteId, item.id)
            ) {
                return@withLock previous
            }

            retryTimerJobs.remove(id)?.cancel()
            val now = clockMs()
            val next = DownloadItem(
                id = id,
                siteId = item.siteId,
                mediaId = item.id,
                url = item.url,
                title = item.title,
                coverUrl = item.coverUrl,
                kind = kind,
                status = DownloadStatus.QUEUED,
                groupName = groupName.trim().ifBlank { previous?.groupName.orEmpty() },
                progressFraction = previous?.progressFraction ?: 0f,
                completedUnits = previous?.completedUnits ?: 0,
                totalUnits = previous?.totalUnits ?: (item.pageCount ?: 0),
                downloadedBytes = previous?.downloadedBytes ?: 0L,
                totalBytes = previous?.totalBytes,
                selectedPages = normalizedPages.orEmpty(),
                errorCategory = null,
                errorMessage = null,
                retryAtEpochMs = null,
                priority = previous?.priority ?: 0,
                storageLocation = storageRootManager.activeLocation(),
                localPath = storageRootManager.layout().root.absolutePath,
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
            )
            downloadStateRepository.upsert(next)
            _changes.update { it + 1 }
            next
        }

        if (queuedItem.status != DownloadStatus.COMPLETED && canRun) {
            startForegroundWorker()
        }
        queuedItem
    }

    suspend fun pause(id: String) = withContext(dispatchers.io) {
        retryTimerJobs.remove(id)?.cancel()
        updateIf(id, predicate = { it.status in ACTIVE_STATUSES }) { task ->
            task.copy(
                status = DownloadStatus.PAUSED,
                errorCategory = null,
                errorMessage = null,
                retryAtEpochMs = null,
                updatedAt = clockMs(),
            )
        }
        activeJobs[id]?.cancelAndJoin()
    }

    suspend fun resume(id: String): Boolean = withContext(dispatchers.io) {
        galleryPageRepository.checkDownloadsAllowed()
        val resumed = updateIf(id, predicate = { it.status in RESUMABLE_STATUSES }) { task ->
            task.copy(
                status = DownloadStatus.QUEUED,
                errorCategory = null,
                errorMessage = null,
                retryAtEpochMs = null,
                updatedAt = clockMs(),
            )
        }
        if (resumed && canRun) startForegroundWorker()
        resumed
    }

    suspend fun retryFailed(id: String): Boolean = withContext(dispatchers.io) {
        galleryPageRepository.checkDownloadsAllowed()
        // CANCELLED is surfaced as "需处理" too; requeue it like a failure so the button works.
        val queued = updateIf(id, predicate = {
            it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED
        }) { task ->
            task.copy(
                status = DownloadStatus.QUEUED,
                errorCategory = null,
                errorMessage = null,
                retryAtEpochMs = null,
                updatedAt = clockMs(),
            )
        }
        if (queued && canRun) startForegroundWorker()
        queued
    }

    suspend fun remove(id: String, sourceRoot: String = "") = withContext(dispatchers.io + NonCancellable) {
        maintenanceLock.withLock {
            val task = getTask(id) ?: return@withLock
            retryTimerJobs.remove(id)?.cancel()
            activeJobs[id]?.cancelAndJoin()
            galleryPageRepository.removeDownload(
                siteId = task.siteId,
                mediaId = task.mediaId,
                sourceRoot = sourceRoot,
                knownRoot = task.localPath.orEmpty(),
            )
            downloadStateRepository.delete(id)
            _changes.update { it + 1 }
        }
    }

    suspend fun movePriority(id: String, delta: Int) = withContext(dispatchers.io) {
        if (delta == 0) return@withContext
        stateMutationLock.withLock {
            val queue = allTasks()
                .filter { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.CANCELLED }
                .sortedWith(compareByDescending<DownloadItem> { it.priority }.thenBy { it.createdAt })
                .toMutableList()
            val from = queue.indexOfFirst { it.id == id }
            val to = from + delta
            if (from !in queue.indices || to !in queue.indices) return@withLock
            val moved = queue.removeAt(from)
            queue.add(to, moved)
            queue.forEachIndexed { idx, item ->
                val newPriority = queue.size - idx
                if (item.priority != newPriority) {
                    downloadStateRepository.updatePriority(item.id, newPriority)
                }
            }
            _changes.update { it + 1 }
        }
    }

    suspend fun prioritize(id: String) = withContext(dispatchers.io) {
        stateMutationLock.withLock {
            val highest = allTasks().maxOfOrNull { it.priority } ?: 0
            downloadStateRepository.updatePriority(id, highest + 1)
            _changes.update { it + 1 }
        }
    }

    suspend fun setGroup(id: String, groupName: String) = withContext(dispatchers.io) {
        downloadStateRepository.updateGroupName(id, groupName.trim())
        _changes.update { it + 1 }
    }

    suspend fun inspect(id: String): GalleryAudit = withContext(dispatchers.io) {
        val task = getTask(id) ?: error("下载任务不存在")
        galleryPageRepository.auditDownload(task.siteId, task.mediaId, task.localPath.orEmpty())
    }

    /**
     * Audits the write target for [id]: if any pages are missing or corrupt, re-queues ONLY the
     * damaged subset; if every page is valid, commits the `complete` marker immediately.
     */
    suspend fun repair(id: String): DownloadItem = withContext(dispatchers.io) {
        maintenanceLock.withLock {
            galleryPageRepository.checkDownloadsAllowed()
            val task = getTask(id) ?: error("下载任务不存在")
            check(activeJobs[id]?.isActive != true) { "请先暂停当前任务再修复" }
            val layout = galleryPageRepository.downloadLayout()
            val item = task.toMediaItem()
            val selectedPages = task.selectedPages.distinct().sorted()
            val audit = galleryPageRepository.auditWriteTarget(task.siteId, task.mediaId, layout)
                ?: return@withLock enqueue(
                    item,
                    selectedPages = selectedPages.takeIf { it.isNotEmpty() },
                    recheckSavedPages = true,
                )
            val damaged = (audit.missingPages + audit.corruptPages).distinct().sorted()
            if (selectedPages.isEmpty()) {
                if (!audit.isComplete && damaged.isNotEmpty()) {
                    return@withLock enqueue(item, selectedPages = damaged, recheckSavedPages = true)
                }
                return@withLock markRepairedComplete(task, audit, layout, emptyList(), audit.validPages.size)
            }

            // A selective task must never widen to the whole book: only pages the user picked are
            // eligible for repair, and a fully valid selection is complete without the book marker.
            val selectedSet = selectedPages.toSet()
            val damagedSelection = damaged.filter { it in selectedSet }
            if (damagedSelection.isNotEmpty()) {
                return@withLock enqueue(item, selectedPages = damagedSelection, recheckSavedPages = true)
            }
            val validSet = audit.validPages.toSet()
            if (!selectedPages.all { it in validSet }) {
                return@withLock enqueue(item, selectedPages = selectedPages, recheckSavedPages = true)
            }
            markRepairedComplete(task, audit, layout, selectedPages, selectedPages.size)
        }
    }

    private suspend fun markRepairedComplete(
        task: DownloadItem,
        audit: GalleryAudit,
        layout: StorageLayout,
        selection: List<Int>,
        completedUnits: Int,
    ): DownloadItem {
        galleryPageRepository.commitDownload(
            siteId = task.siteId,
            mediaId = task.mediaId,
            layout = layout,
            requestedPages = if (selection.isEmpty()) audit.validPages.toSet() else selection.toSet(),
        )
        writeCompletedSidecar(task, audit, layout, completedPages = completedUnits)
        val repaired = task.copy(
            status = DownloadStatus.COMPLETED,
            progressFraction = 1f,
            completedUnits = completedUnits,
            totalUnits = if (selection.isEmpty()) audit.totalPages else selection.size,
            downloadedBytes = audit.totalBytes,
            selectedPages = selection,
            errorCategory = null,
            errorMessage = null,
            retryAtEpochMs = null,
            storageLocation = layout.location,
            localPath = layout.root.absolutePath,
            updatedAt = clockMs(),
        )
        downloadStateRepository.upsert(repaired)
        _changes.update { it + 1 }
        return repaired
    }

    suspend fun waitForNetwork() = withContext(dispatchers.io) {
        if (!networkAllowedProvider()) {
            activeJobs.values.toList().forEach { it.cancelAndJoin() }
        }
    }

    suspend fun resumePending(): Boolean = withContext(dispatchers.io) {
        val tasks = allTasks()
        tasks.filter { it.status == DownloadStatus.RETRY_WAIT }.forEach(::scheduleRetryTimer)
        if (canRun && tasks.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }) {
            runCatching { startForegroundWorker(keepQueuedOnFailure = true) }.getOrDefault(false)
        } else {
            true
        }
    }

    suspend fun pauseForTimeout() = withContext(dispatchers.io + NonCancellable) {
        val tasks = allTasks().filter { it.status in ACTIVE_STATUSES }
        for (task in tasks) {
            retryTimerJobs.remove(task.id)?.cancel()
            activeJobs[task.id]?.cancel()
            updateIf(task.id, predicate = { it.status in ACTIVE_STATUSES }) { current ->
                current.copy(
                    status = DownloadStatus.PAUSED,
                    retryAtEpochMs = null,
                    errorMessage = "系统后台下载时限已到，点击继续即可恢复",
                    updatedAt = clockMs(),
                )
            }
        }
    }

    suspend fun hasPendingRunnable(): Boolean =
        allTasks().any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }

    /**
     * Drains all active gallery writers while storage migration runs, restoring interrupted tasks
     * to `QUEUED` without touching user-paused tasks.
     */
    suspend fun <T> withWritersStopped(block: suspend () -> T): T = maintenanceLock.withLock {
        val running = activeJobs.toMap()
        for ((id, job) in running) {
            job.cancelAndJoin()
            updateIf(id, predicate = { it.status == DownloadStatus.DOWNLOADING }) {
                it.copy(status = DownloadStatus.QUEUED, updatedAt = clockMs())
            }
        }
        block()
    }

    /**
     * Claims and starts the highest-priority runnable task serially (at most 1 active book at a time).
     * Invoked by [GalleryDownloadService] pump loop (or directly by tests).
     */
    suspend fun launchNext(workerScope: CoroutineScope): Job? {
        if (!canRun || activeJobs.isNotEmpty()) return null
        val claimed = stateMutationLock.withLock {
            if (!canRun || activeJobs.isNotEmpty()) return@withLock null
            val candidate = allTasks()
                .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
                .sortedWith(compareByDescending<DownloadItem> { it.priority }.thenBy { it.createdAt })
                .firstOrNull() ?: return@withLock null
            val layout = galleryPageRepository.downloadLayout()
            val started = candidate.copy(
                status = DownloadStatus.DOWNLOADING,
                errorCategory = null,
                errorMessage = null,
                retryAtEpochMs = null,
                storageLocation = layout.location,
                localPath = layout.root.absolutePath,
                updatedAt = clockMs(),
            )
            downloadStateRepository.upsert(started)
            _changes.update { it + 1 }
            started to layout
        } ?: return null

        val (task, layout) = claimed
        val job = workerScope.launch(start = CoroutineStart.LAZY) {
            executeTask(task, layout)
        }
        activeJobs[task.id] = job
        job.invokeOnCompletion { activeJobs.remove(task.id, job) }
        job.start()
        return job
    }

    private class RetryWaitSignal(val failure: ClassifiedDownloadFailure) : Exception(failure.message)

    private suspend fun executeTask(task: DownloadItem, layout: StorageLayout) {
        var stage = DownloadStage.PREPARING
        val mediaItem = task.toMediaItem()
        try {
            val detail = retryTransient(task.id, stage) {
                galleryPageRepository.prepareDownload(mediaItem, layout)
            }
            val requestedPages = if (task.selectedPages.isNotEmpty()) {
                task.selectedPages.distinct().sorted()
            } else {
                (1..detail.pageCount).toList()
            }
            check(requestedPages.isNotEmpty() && requestedPages.all { it in 1..detail.pageCount }) {
                "所选页码超出本图集范围 (1..${detail.pageCount})"
            }
            val preferOriginal = runCatching { preferOriginalProvider(mediaItem) }.getOrDefault(false)
            val totalRequested = requestedPages.size
            var completedCount = 0
            var totalBytes = 0L

            updateIf(task.id, predicate = { it.status == DownloadStatus.DOWNLOADING }) { current ->
                current.copy(
                    completedUnits = 0,
                    totalUnits = totalRequested,
                    progressFraction = 0f,
                    updatedAt = clockMs(),
                )
            }

            val workerCount = concurrencyProvider(task.siteId)
                .coerceIn(1, MAX_PAGE_WORKERS)
                .coerceAtMost(requestedPages.size)
            val nextIdx = AtomicInteger(0)
            val progressLock = Mutex()
            stage = DownloadStage.DOWNLOADING_PAGE

            coroutineScope {
                repeat(workerCount) { workerIndex ->
                    launch {
                        if (workerIndex > 0) {
                            delay(workerIndex * WORKER_STAGGER_DELAY_MS)
                        }
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val idx = nextIdx.getAndIncrement()
                            if (idx >= requestedPages.size) break
                            val pageIndex = requestedPages[idx]
                            val (_, pageBytes) = retryTransient(task.id, stage) {
                                galleryPageRepository.downloadPage(
                                    detail = detail,
                                    pageIndex = pageIndex,
                                    layout = layout,
                                    preferOriginal = preferOriginal,
                                )
                            }
                            progressLock.withLock {
                                completedCount += 1
                                totalBytes += pageBytes
                                val fraction = (completedCount.toFloat() / totalRequested.toFloat()).coerceIn(0f, 1f)
                                updateIf(task.id, predicate = { it.status == DownloadStatus.DOWNLOADING }) { current ->
                                    current.copy(
                                        completedUnits = completedCount.coerceAtMost(totalRequested),
                                        totalUnits = totalRequested,
                                        progressFraction = fraction,
                                        downloadedBytes = maxOf(current.downloadedBytes, totalBytes),
                                        errorCategory = null,
                                        errorMessage = null,
                                        updatedAt = clockMs(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            stage = DownloadStage.COMMITTING
            val audit = galleryPageRepository.commitDownload(
                siteId = task.siteId,
                mediaId = task.mediaId,
                layout = layout,
                requestedPages = requestedPages.toSet(),
            )
            if (audit.isComplete) {
                runCatching { writeCompletedSidecar(task, audit, layout) }
            }
            // A selective task reports its picked subset; a whole-book repair that fills the last
            // gaps must keep reporting the full book even though it was enqueued as a subset.
            val selective = task.selectedPages.isNotEmpty() && !audit.isComplete
            val completedUnitsForTask = if (selective) {
                requestedPages.count { it in audit.validPages }
            } else {
                audit.validPages.size
            }
            val totalUnitsForTask = if (selective) totalRequested else audit.totalPages
            val didComplete = updateIf(task.id, predicate = { it.status == DownloadStatus.DOWNLOADING }) { current ->
                current.copy(
                    status = DownloadStatus.COMPLETED,
                    progressFraction = if (totalUnitsForTask > 0) {
                        (completedUnitsForTask.toFloat() / totalUnitsForTask.toFloat()).coerceIn(0f, 1f)
                    } else {
                        1f
                    },
                    completedUnits = completedUnitsForTask,
                    totalUnits = totalUnitsForTask,
                    downloadedBytes = audit.totalBytes,
                    selectedPages = if (audit.isComplete) emptyList() else requestedPages,
                    errorCategory = null,
                    errorMessage = null,
                    retryAtEpochMs = null,
                    updatedAt = clockMs(),
                )
            }
            if (didComplete) {
                val notificationPages = if (selective) completedUnitsForTask else audit.validPages.size
                val pageSummary = "$notificationPages 页"
                val detail = if (audit.totalBytes > 0L) {
                    "$pageSummary · ${DownloadNotifications.formatBytes(audit.totalBytes)}"
                } else {
                    pageSummary
                }
                DownloadNotifications.notifyDownloadCompleted(
                    context = context,
                    downloadId = task.id,
                    title = task.title,
                    isVideo = false,
                    detail = detail,
                    privacySensitive = notificationPrivacyProvider(),
                )
            }
        } catch (waitSignal: RetryWaitSignal) {
            val updated = updateIf(task.id, predicate = { it.status == DownloadStatus.DOWNLOADING }) { current ->
                current.copy(
                    status = DownloadStatus.RETRY_WAIT,
                    errorCategory = waitSignal.failure.category,
                    errorMessage = waitSignal.failure.message,
                    retryAtEpochMs = waitSignal.failure.retryAtEpochMs,
                    updatedAt = clockMs(),
                )
            }
            if (updated) {
                getTask(task.id)?.takeIf { it.status == DownloadStatus.RETRY_WAIT }?.let(::scheduleRetryTimer)
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable + dispatchers.io) {
                updateIf(task.id, predicate = { it.status == DownloadStatus.DOWNLOADING }) { current ->
                    current.copy(status = DownloadStatus.QUEUED, updatedAt = clockMs())
                }
            }
            throw ce
        } catch (error: Throwable) {
            val classified = DownloadErrorClassifier.classify(error, stage, clockMs())
            val didFail = updateIf(task.id, predicate = { it.status == DownloadStatus.DOWNLOADING }) { current ->
                current.copy(
                    status = DownloadStatus.FAILED,
                    errorCategory = classified.category,
                    errorMessage = classified.message,
                    retryAtEpochMs = null,
                    updatedAt = clockMs(),
                )
            }
            if (didFail) {
                DownloadNotifications.notifyDownloadFailed(
                    context = context,
                    downloadId = task.id,
                    title = task.title,
                    isVideo = false,
                    errorDetail = classified.message,
                    privacySensitive = notificationPrivacyProvider(),
                )
            }
        }
    }

    private suspend fun <T> retryTransient(
        taskId: String,
        stage: DownloadStage,
        block: suspend () -> T,
    ): T {
        for (attempt in 1..DownloadErrorClassifier.MAX_ATTEMPTS) {
            try {
                return block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                val now = clockMs()
                val failure = DownloadErrorClassifier.classify(error, stage, now)
                if (!failure.retryable || attempt == DownloadErrorClassifier.MAX_ATTEMPTS) {
                    if (failure.retryable && failure.retryAtEpochMs != null &&
                        DownloadErrorClassifier.automaticDelayMs(failure, now) == null
                    ) {
                        throw RetryWaitSignal(failure)
                    }
                    throw error
                }
                val autoDelay = DownloadErrorClassifier.automaticDelayMs(failure, now)
                    ?: throw RetryWaitSignal(failure)
                val waitMs = if (failure.retryAtEpochMs != null) autoDelay else DownloadErrorClassifier.backoffMs(attempt)
                updateIf(taskId, predicate = { it.status == DownloadStatus.DOWNLOADING }) { current ->
                    current.copy(
                        errorCategory = failure.category,
                        errorMessage = "${failure.message}；将自动重试 ${attempt + 1} / ${DownloadErrorClassifier.MAX_ATTEMPTS}",
                        updatedAt = clockMs(),
                    )
                }
                delay(waitMs)
            }
        }
        error("Unreachable retry loop termination")
    }

    private fun scheduleRetryTimer(task: DownloadItem) {
        val targetRetryAt = task.retryAtEpochMs ?: return
        val delayMs = (targetRetryAt - clockMs()).coerceAtLeast(0L)
        context?.let { appContext ->
            runCatching { GalleryRetryWakeupWorker.schedule(appContext, task.id, delayMs) }
        }
        val job = applicationScope.launch(dispatchers.io, start = CoroutineStart.LAZY) {
            delay(delayMs)
            // Conditional transition: never overwrite a task that the user manually paused or deleted.
            val requeued = updateIf(
                id = task.id,
                predicate = { it.status == DownloadStatus.RETRY_WAIT && it.retryAtEpochMs == targetRetryAt },
            ) { current ->
                current.copy(
                    status = DownloadStatus.QUEUED,
                    errorCategory = null,
                    errorMessage = null,
                    retryAtEpochMs = null,
                    updatedAt = clockMs(),
                )
            }
            if (requeued && canRun) {
                runCatching { startForegroundWorker(keepQueuedOnFailure = true) }
            }
        }
        if (retryTimerJobs.putIfAbsent(task.id, job) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { retryTimerJobs.remove(task.id, job) }
        job.start()
    }

    private suspend fun updateIf(
        id: String,
        predicate: (DownloadItem) -> Boolean,
        transform: (DownloadItem) -> DownloadItem,
    ): Boolean = stateMutationLock.withLock {
        val current = getTask(id) ?: return@withLock false
        if (!predicate(current)) return@withLock false
        val updated = transform(current)
        if (updated != current) {
            downloadStateRepository.upsert(updated)
            _changes.update { it + 1 }
        }
        true
    }

    private fun writeCompletedSidecar(
        task: DownloadItem,
        audit: GalleryAudit,
        layout: StorageLayout,
        completedPages: Int = audit.validPages.size,
    ) {
        storageRootManager.writeComicSidecar(
            DiskSidecarRecord(
                kind = task.kind,
                siteId = task.siteId,
                mediaId = task.mediaId,
                title = task.title,
                url = task.url,
                coverUrl = task.coverUrl,
                totalPages = audit.totalPages,
                completedPages = completedPages,
                bytes = audit.totalBytes,
                updatedAt = clockMs(),
                status = DownloadStatus.COMPLETED,
                sourceRoot = layout.root.absolutePath,
            ),
            layout = layout,
        )
    }

    private fun startForegroundWorker(keepQueuedOnFailure: Boolean = false): Boolean {
        serviceStarter?.let {
            it.invoke()
            return true
        }
        val appContext = context?.applicationContext ?: return false
        return try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, GalleryDownloadService::class.java),
            )
            true
        } catch (e: Exception) {
            if (!keepQueuedOnFailure) {
                applicationScope.launch(dispatchers.io) {
                    allTasks().filter { it.status == DownloadStatus.QUEUED }.forEach { task ->
                        updateIf(task.id, predicate = { it.status == DownloadStatus.QUEUED }) { current ->
                            current.copy(
                                status = if (galleryPageRepository.downloadsAllowed) DownloadStatus.PAUSED else DownloadStatus.QUEUED,
                                errorMessage = "系统暂时限制后台下载，请回到下载中心点击继续",
                                updatedAt = clockMs(),
                            )
                        }
                    }
                }
            }
            false
        }
    }

    private fun DownloadItem.toMediaItem(): MediaItem = MediaItem(
        siteId = siteId,
        id = mediaId,
        url = url,
        kind = if (kind == DownloadKind.PHOTO) MediaKind.PHOTO else MediaKind.COMIC,
        title = title,
        coverUrl = coverUrl,
        pageCount = totalUnits.takeIf { it > 0 },
    )

    companion object {
        private val ACTIVE_STATUSES = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.RETRY_WAIT,
        )
        private val RESUMABLE_STATUSES = setOf(
            DownloadStatus.PAUSED,
            DownloadStatus.FAILED,
            DownloadStatus.RETRY_WAIT,
        )
        const val MAX_PAGE_WORKERS: Int = 3
        private const val WORKER_STAGGER_DELAY_MS: Long = 80L
    }
}

/**
 * `dataSync` foreground [Service] executing serial gallery page downloads with a 6-hour capped
 * `PARTIAL_WAKE_LOCK`, throttled progress notification updates, and API 35 `onTimeout` graceful pause.
 */
class GalleryDownloadService : Service() {
    private val engine: GalleryDownloadEngine
        get() = GlobalContext.get().get()

    private val dispatchers: AppDispatchers
        get() = runCatching { GlobalContext.get().get<AppDispatchers>() }.getOrDefault(DefaultAppDispatchers())

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + dispatchers.io) }
    private var pumpJob: Job? = null
    private var progressJob: Job? = null
    private var lastStartId: Int = 0
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isStopping: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isStopping = false
        enterForeground()
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Raven:GalleryDownload")?.apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        isStopping = false
        enterForeground()
        if (pumpJob?.isActive != true) {
            startPump()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        isStopping = true
        val cleanupScope = runCatching {
            GlobalContext.get().get<CoroutineScope>(com.raven.app.core.network.NetworkQualifiers.APPLICATION_SCOPE)
        }.getOrElse { CoroutineScope(SupervisorJob() + dispatchers.io) }
        cleanupScope.launch(dispatchers.io) {
            runCatching { engine.pauseForTimeout() }
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isStopping = true
        serviceScope.cancel()
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
        super.onDestroy()
    }

    private fun enterForeground() {
        DownloadNotifications.ensureChannels(this)
        val initial = buildNotification(null, activeCount = 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, initial)
        }

        if (progressJob?.isActive != true) {
            progressJob = serviceScope.launch {
                engine.changes.collectLatest {
                    delay(200L)
                    val snapshot = runCatching {
                        val all = engine.allTasks()
                        val primary = all.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
                            ?: all.firstOrNull { it.errorCategory == DownloadErrorCategory.STORAGE }
                        val activeCount = all.count {
                            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
                        }
                        primary to activeCount
                    }.getOrNull()
                    val notification = buildNotification(snapshot?.first, snapshot?.second ?: 0)
                    withContext(dispatchers.main) {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(FOREGROUND_NOTIFICATION_ID, notification)
                    }
                }
            }
        }
    }

    private fun startPump() {
        pumpJob = serviceScope.launch {
            while (isActive && !isStopping) {
                val job = engine.launchNext(this) ?: break
                job.join()
            }
            withContext(dispatchers.main) {
                pumpJob = null
                if (!isStopping && engine.hasPendingRunnable() && engine.canRun) {
                    startPump()
                } else {
                    isStopping = true
                    progressJob?.cancel()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStartId)
                }
            }
        }
    }

    private fun buildNotification(task: DownloadItem?, activeCount: Int): Notification {
        val privacySensitive = engine.isNotificationPrivacySensitive()
        val statusText = when {
            task == null -> "正在准备图集下载任务"
            else -> DownloadNotifications.formatGalleryProgressText(
                title = if (privacySensitive) "" else task.title,
                completedUnits = task.completedUnits,
                totalUnits = task.totalUnits,
                downloadedBytes = task.downloadedBytes,
                storageError = if (task.errorCategory == DownloadErrorCategory.STORAGE) {
                    task.errorMessage.orEmpty()
                } else {
                    null
                },
            )
        }
        val titleText = if (activeCount > 1) {
            "${getString(R.string.raven_gallery_download_channel)}（$activeCount 个任务）"
        } else {
            getString(R.string.raven_gallery_download_channel)
        }
        val pendingIntent = DownloadNotifications.buildOpenDownloadsPendingIntent(
            context = this,
            requestCode = FOREGROUND_NOTIFICATION_ID,
        )
        val indeterminate = task == null ||
            task.totalUnits <= 0 ||
            task.errorCategory == DownloadErrorCategory.STORAGE
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(titleText)
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(task?.totalUnits ?: 0, task?.completedUnits ?: 0, indeterminate)
        if (privacySensitive) builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        return builder.build()
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID: Int = 42
        const val NOTIFICATION_CHANNEL_ID: String = "raven-gallery-downloads"
        private const val MAX_WAKE_LOCK_MS: Long = 6L * 60L * 60L * 1_000L
    }
}
