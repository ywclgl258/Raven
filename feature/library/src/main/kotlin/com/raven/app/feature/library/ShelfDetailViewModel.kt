package com.raven.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.NaturalSortComparator
import com.raven.app.core.database.domain.CompletionPolicy
import com.raven.app.core.database.model.SmartShelfEntry
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.media.download.BatchEnqueueProgress
import com.raven.app.core.media.download.PlaybackQueueStore
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.model.workId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Target selector for [ShelfDetailViewModel]: either a custom/system [Shelf] or a dynamic [SmartShelf].
 */
sealed interface ShelfDetailTarget {
    val id: String

    data class Custom(val shelfId: String) : ShelfDetailTarget {
        override val id: String get() = shelfId
    }

    data class Smart(val smartShelfId: String) : ShelfDetailTarget {
        override val id: String get() = smartShelfId
    }
}

enum class ShelfCompletionFilter(val label: String) {
    ALL("全部状态"),
    UNCOMPLETED("未完成"),
    COMPLETED("已完成"),
}

enum class ShelfAddedWindow(val label: String, val windowMs: Long?) {
    ALL("全部时间", null),
    DAYS_7("近 7 天", 7L * 24L * 60L * 60L * 1000L),
    DAYS_30("近 30 天", 30L * 24L * 60L * 60L * 1000L),
    DAYS_90("近 90 天", 90L * 24L * 60L * 60L * 1000L),
}

enum class ShelfViewSort(val label: String) {
    MANUAL("手动顺序"),
    NEWEST("最新加入"),
    OLDEST("最早加入"),
    TITLE_AZ("标题 A-Z"),
}

/**
 * Unified row model rendered on both [ShelfDetailScreen] and [SmartShelfDetailScreen].
 */
data class ShelfEntryUi(
    val siteId: SiteId,
    val siteLabel: String,
    val mediaId: String,
    val url: String,
    val kind: MediaKind,
    val title: String,
    val coverUrl: String,
    val position: Int,
    val addedAt: Long,
    val lastActivityAt: Long,
    val completed: Boolean,
    val progressFraction: Float?,
    val resumePage: Int,
    val isOfflineReady: Boolean,
    val downloadStatus: DownloadStatus?,
    val mediaItem: MediaItem,
) {
    val compositeKey: String get() = "${siteId.value}:$mediaId"
}

/**
 * Offline preparation readiness breakdown displayed inside the Offline Preparation `RavenBottomSheet`.
 */
data class OfflinePreparationSummary(
    val readyItems: List<ShelfEntryUi> = emptyList(),
    val queuedItems: List<ShelfEntryUi> = emptyList(),
    val missingItems: List<ShelfEntryUi> = emptyList(),
) {
    val totalCount: Int get() = readyItems.size + queuedItems.size + missingItems.size
}

/**
 * One-shot navigation event emitted when a sequential playback/reading queue starts.
 */
data class ShelfQueueLaunchEvent(
    val firstItem: MediaItem,
    val queueToken: String,
    val offlineOnly: Boolean,
    val skippedCount: Int = 0,
)

data class ShelfDetailFilterState(
    val searchQuery: String = "",
    val completionFilter: ShelfCompletionFilter = ShelfCompletionFilter.ALL,
    val mediaKindFilter: MediaKind? = null,
    val siteIdFilter: SiteId? = null,
    val addedWindow: ShelfAddedWindow = ShelfAddedWindow.ALL,
    val viewSort: ShelfViewSort = ShelfViewSort.MANUAL,
) {
    val hasActiveFilters: Boolean
        get() = searchQuery.isNotBlank() ||
            completionFilter != ShelfCompletionFilter.ALL ||
            mediaKindFilter != null ||
            siteIdFilter != null ||
            addedWindow != ShelfAddedWindow.ALL
}

data class ShelfDetailUiState(
    val isSmartShelf: Boolean = false,
    val shelf: Shelf? = null,
    val smartShelf: SmartShelf? = null,
    val isNotFound: Boolean = false,
    val title: String = "",
    val note: String = "",
    val totalItemCount: Int = 0,
    val completedCount: Int = 0,
    val allEntries: List<ShelfEntryUi> = emptyList(),
    val visibleEntries: List<ShelfEntryUi> = emptyList(),
    val filterState: ShelfDetailFilterState = ShelfDetailFilterState(),
    val siteOptions: List<SiteFilterOption> = emptyList(),
    val allSiteOptions: List<SiteFilterOption> = emptyList(),
    val offlinePrep: OfflinePreparationSummary = OfflinePreparationSummary(),
    val batchProgress: BatchEnqueueProgress = BatchEnqueueProgress(),
    val pendingLaunch: ShelfQueueLaunchEvent? = null,
    val deleted: Boolean = false,
    val message: String? = null,
) {
    val canManualReorder: Boolean
        get() = !isSmartShelf &&
            filterState.viewSort == ShelfViewSort.MANUAL &&
            !filterState.hasActiveFilters
}

private data class ShelfSourcePayload(
    val shelf: Shelf?,
    val smartShelf: SmartShelf?,
    val customItems: List<ShelfItem>,
    val smartEntries: List<SmartShelfEntry>,
)

private data class ShelfAuxState(
    val watches: List<WatchProgress>,
    val rawReadings: List<ReadingProgress>,
    val workReadings: List<ReadingProgress>,
    val downloads: List<DownloadItem>,
    val batchProgress: BatchEnqueueProgress,
)

private data class ShelfEphemeralState(
    val pendingLaunch: ShelfQueueLaunchEvent? = null,
    val deleted: Boolean = false,
    val message: String? = null,
)

class ShelfDetailViewModel(
    private val target: ShelfDetailTarget,
    private val shelfRepository: ShelfRepository,
    private val historyRepository: HistoryRepository,
    private val downloadGateway: LibraryDownloadGateway,
    private val playbackQueueStore: PlaybackQueueStore,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val initialSort = when (target) {
        is ShelfDetailTarget.Custom -> ShelfViewSort.MANUAL
        is ShelfDetailTarget.Smart -> ShelfViewSort.NEWEST
    }
    private val filterState = MutableStateFlow(ShelfDetailFilterState(viewSort = initialSort))
    private val ephemeralState = MutableStateFlow(ShelfEphemeralState())
    private val allSiteOptions: List<SiteFilterOption> = siteRegistry.buildSiteFilterOptions(
        presentSiteIds = emptyList(),
        includeAllRegistered = true,
    )

    private val sourcePayloadFlow: Flow<ShelfSourcePayload> = when (target) {
        is ShelfDetailTarget.Custom -> combine(
            shelfRepository.observeShelf(target.shelfId),
            shelfRepository.observeShelfItems(target.shelfId),
        ) { shelf, items ->
            ShelfSourcePayload(
                shelf = shelf,
                smartShelf = null,
                customItems = items,
                smartEntries = emptyList(),
            )
        }
        is ShelfDetailTarget.Smart -> combine(
            shelfRepository.observeSmartShelf(target.smartShelfId),
            shelfRepository.observeSmartShelfEntries(target.smartShelfId, nowProvider = clockMs),
        ) { smartShelf, entries ->
            ShelfSourcePayload(
                shelf = null,
                smartShelf = smartShelf,
                customItems = emptyList(),
                smartEntries = entries,
            )
        }
    }

    private val auxFlow = combine(
        historyRepository.observeAllWatchProgress(),
        historyRepository.observeAllReadingProgress(),
        historyRepository.observeReadingHistory(),
        downloadGateway.downloads,
        downloadGateway.batchProgress,
    ) { watches, rawReadings, workReadings, downloads, batchProgress ->
        ShelfAuxState(
            watches = watches,
            rawReadings = rawReadings,
            workReadings = workReadings,
            downloads = downloads,
            batchProgress = batchProgress,
        )
    }

    val uiState: StateFlow<ShelfDetailUiState> = combine(
        sourcePayloadFlow,
        auxFlow,
        filterState,
        ephemeralState,
    ) { source, aux, filters, ephemeral ->
        buildUiState(source, aux, filters, ephemeral)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ShelfDetailUiState(
            isSmartShelf = target is ShelfDetailTarget.Smart,
            filterState = ShelfDetailFilterState(viewSort = initialSort),
            allSiteOptions = allSiteOptions,
        ),
    )

    // ---- Search, Filter & View Sort (NEVER mutates manual ShelfItem.position) ----

    fun updateSearchQuery(query: String) {
        filterState.update { it.copy(searchQuery = query) }
    }

    fun selectCompletionFilter(filter: ShelfCompletionFilter) {
        filterState.update { it.copy(completionFilter = filter) }
    }

    fun selectMediaKindFilter(kind: MediaKind?) {
        filterState.update { it.copy(mediaKindFilter = kind) }
    }

    fun selectSiteFilter(siteId: SiteId?) {
        filterState.update { it.copy(siteIdFilter = siteId) }
    }

    fun selectAddedWindow(window: ShelfAddedWindow) {
        filterState.update { it.copy(addedWindow = window) }
    }

    fun selectViewSort(sort: ShelfViewSort) {
        filterState.update { it.copy(viewSort = sort) }
    }

    fun resetFilters() {
        filterState.update {
            it.copy(
                searchQuery = "",
                completionFilter = ShelfCompletionFilter.ALL,
                mediaKindFilter = null,
                siteIdFilter = null,
                addedWindow = ShelfAddedWindow.ALL,
            )
        }
    }

    // ---- Custom Shelf Item Operations ----

    fun moveItemUp(siteId: SiteId, mediaId: String) = moveItemBy(siteId, mediaId, delta = -1)

    fun moveItemDown(siteId: SiteId, mediaId: String) = moveItemBy(siteId, mediaId, delta = 1)

    private fun moveItemBy(siteId: SiteId, mediaId: String, delta: Int) {
        val customTarget = target as? ShelfDetailTarget.Custom ?: return
        viewModelScope.launch(dispatchers.io) {
            shelfRepository.moveItem(customTarget.shelfId, siteId, mediaId, delta)
        }
    }

    fun toggleItemCompleted(entry: ShelfEntryUi) {
        val customTarget = target as? ShelfDetailTarget.Custom ?: return
        val nextCompleted = !entry.completed
        viewModelScope.launch(dispatchers.io) {
            shelfRepository.setItemCompleted(
                shelfId = customTarget.shelfId,
                siteId = entry.siteId,
                mediaId = entry.mediaId,
                completed = nextCompleted,
            )
        }
    }

    fun removeItem(siteId: SiteId, mediaId: String) {
        val customTarget = target as? ShelfDetailTarget.Custom ?: return
        viewModelScope.launch(dispatchers.io) {
            shelfRepository.removeItem(customTarget.shelfId, siteId, mediaId)
            ephemeralState.update { it.copy(message = "已从片单中移出") }
        }
    }

    // ---- Shelf Metadata Edit (Name, Notes, Pin, Custom Cover) ----

    fun updateCustomShelfMetadata(
        name: String,
        note: String,
        isPinned: Boolean,
        coverCompositeKey: String?,
        clearCover: Boolean,
    ) {
        val customTarget = target as? ShelfDetailTarget.Custom ?: return
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val renameArg = if (customTarget.shelfId == Shelf.WATCH_LATER_ID) null else name
                shelfRepository.updateShelf(
                    shelfId = customTarget.shelfId,
                    name = renameArg,
                    note = note,
                    isPinned = isPinned,
                    coverCompositeKey = coverCompositeKey,
                    clearCover = clearCover,
                )
            }.onSuccess {
                ephemeralState.update { it.copy(message = "已保存片单设置") }
            }.onFailure { error ->
                ephemeralState.update { it.copy(message = error.message ?: "保存片单设置失败") }
            }
        }
    }

    fun updateSmartShelfMetadata(
        name: String,
        rule: SmartShelfRule,
        mediaKindFilter: MediaKind?,
        siteIdFilter: SiteId?,
        isPinned: Boolean,
    ) {
        val smartTarget = target as? ShelfDetailTarget.Smart ?: return
        val existingCreatedAt = uiState.value.smartShelf?.createdAt ?: clockMs()
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                shelfRepository.saveSmartShelf(
                    SmartShelf(
                        id = smartTarget.smartShelfId,
                        name = name,
                        rule = rule,
                        mediaKindFilter = mediaKindFilter,
                        siteIdFilter = siteIdFilter,
                        isPinned = isPinned,
                        createdAt = existingCreatedAt,
                    ),
                )
            }.onSuccess {
                ephemeralState.update { it.copy(message = "已更新智能片单配置") }
            }.onFailure { error ->
                ephemeralState.update { it.copy(message = error.message ?: "更新智能片单失败") }
            }
        }
    }

    fun deleteCurrentShelf() {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                when (target) {
                    is ShelfDetailTarget.Custom -> shelfRepository.deleteShelf(target.shelfId)
                    is ShelfDetailTarget.Smart -> shelfRepository.deleteSmartShelf(target.smartShelfId)
                }
            }.onSuccess {
                ephemeralState.update { it.copy(deleted = true) }
            }.onFailure { error ->
                ephemeralState.update { it.copy(message = error.message ?: "删除失败") }
            }
        }
    }

    // ---- Sequential Watch / Read Queue (Online & Offline-Only via PlaybackQueueStore) ----

    fun startSequentialOnline(includeCompleted: Boolean = false) {
        val snapshot = uiState.value
        val candidates = selectQueueCandidates(snapshot.visibleEntries, includeCompleted)
        if (candidates.isEmpty()) {
            ephemeralState.update { it.copy(message = "当前筛选下没有可连续观看或阅读的作品") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                playbackQueueStore.start(
                    title = snapshot.title.ifBlank { "顺序队列" },
                    shelfId = target.id,
                    items = candidates.map { it.mediaItem },
                    offlineOnly = false,
                )
            }.onSuccess { session ->
                val first = session.currentItem ?: return@onSuccess
                ephemeralState.update {
                    it.copy(
                        pendingLaunch = ShelfQueueLaunchEvent(
                            firstItem = first,
                            queueToken = session.token,
                            offlineOnly = false,
                            skippedCount = 0,
                        ),
                    )
                }
            }.onFailure { error ->
                ephemeralState.update { it.copy(message = error.message ?: "无法启动顺序播放") }
            }
        }
    }

    fun startSequentialOffline(includeCompleted: Boolean = false) {
        val snapshot = uiState.value
        val candidates = selectQueueCandidates(snapshot.visibleEntries, includeCompleted)
        if (candidates.isEmpty()) {
            ephemeralState.update { it.copy(message = "当前筛选下没有可离线打开的作品") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            try {
                val result = playbackQueueStore.startOfflineFiltered(
                    title = snapshot.title.ifBlank { "离线队列" },
                    shelfId = target.id,
                    candidates = candidates.map { it.mediaItem },
                    isOfflineReady = { item ->
                        downloadGateway.isOfflineReady(item.siteId, item.id, item.kind)
                    },
                )
                val first = result.session.currentItem ?: return@launch
                val skipNote = if (result.skippedCount > 0) {
                    "已跳过 ${result.skippedCount} 项未离线作品"
                } else {
                    null
                }
                ephemeralState.update {
                    it.copy(
                        pendingLaunch = ShelfQueueLaunchEvent(
                            firstItem = first,
                            queueToken = result.session.token,
                            offlineOnly = true,
                            skippedCount = result.skippedCount,
                        ),
                        message = skipNote,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                ephemeralState.update {
                    it.copy(message = error.message ?: "当前筛选下没有完整可离线打开的作品")
                }
            }
        }
    }

    fun consumeLaunchEvent() {
        ephemeralState.update { it.copy(pendingLaunch = null) }
    }

    // ---- Offline Preparation Batch Download ----

    fun enqueueMissingDownloads() {
        val snapshot = uiState.value
        val missing = snapshot.offlinePrep.missingItems
        if (missing.isEmpty()) {
            ephemeralState.update { it.copy(message = "当前筛选下的作品已全部离线或已在队列中") }
            return
        }
        runCatching {
            downloadGateway.enqueueBatch(
                items = missing.map { it.mediaItem },
                groupName = snapshot.title.trim(),
            )
        }.onSuccess {
            ephemeralState.update { it.copy(message = "正在将 ${missing.size} 项作品加入离线下载…") }
        }.onFailure { error ->
            ephemeralState.update { it.copy(message = error.message ?: "加入批量下载失败") }
        }
    }

    fun cancelBatchDownload() {
        downloadGateway.cancelBatch()
    }

    fun clearMessage() {
        ephemeralState.update { it.copy(message = null) }
    }

    private fun selectQueueCandidates(
        visible: List<ShelfEntryUi>,
        includeCompleted: Boolean,
    ): List<ShelfEntryUi> {
        if (includeCompleted) return visible
        val unfinished = visible.filterNot { it.completed }
        return unfinished.ifEmpty { visible }
    }

    private fun buildUiState(
        source: ShelfSourcePayload,
        aux: ShelfAuxState,
        filters: ShelfDetailFilterState,
        ephemeral: ShelfEphemeralState,
    ): ShelfDetailUiState {
        val watchByKey = aux.watches.associateBy { it.compositeKey }
        val readingByChapterKey = aux.rawReadings.associateBy { it.compositeKey }
        val readingByWorkKey = aux.workReadings.associateBy { "${it.siteId.value}:${it.workId}" }
        val downloadsByKey = aux.downloads.groupBy { "${it.siteId.value}:${it.mediaId}" }
        val rawEntries = when (target) {
            is ShelfDetailTarget.Custom -> source.customItems.map { item ->
                val key = item.compositeKey
                val watch = if (item.kind.isVideo) watchByKey[key] else null
                val chapterReading = if (item.kind.isGallery) readingByChapterKey[key] else null
                val workReading = if (item.kind.isGallery) readingByWorkKey["${item.siteId.value}:${item.mediaId}"] else null
                val reading = chapterReading ?: workReading
                val chapterMatch = chapterReading != null
                val dls = downloadsByKey[key].orEmpty()
                val bestStatus = resolveAggregateDownloadStatus(dls)
                val isCompleted = if (item.completionChangedAt != null) {
                    item.completed
                } else {
                    item.completed || (watch != null && CompletionPolicy.isWatchCompleted(watch)) ||
                        (reading != null && if (chapterMatch) CompletionPolicy.isReadingCompleted(reading) else reading.isWorkCompleted())
                }
                val fraction = when {
                    watch != null && watch.durationMs > 0L -> watch.progressFraction
                    reading != null && reading.totalPages > 0 -> reading.progressFraction
                    else -> null
                }
                val resumePage = reading?.currentPage?.coerceAtLeast(1) ?: 1
                ShelfEntryUi(
                    siteId = item.siteId,
                    siteLabel = siteRegistry.displayNameFor(item.siteId),
                    mediaId = item.mediaId,
                    url = item.url,
                    kind = item.kind,
                    title = item.title,
                    coverUrl = item.coverUrl,
                    position = item.position,
                    addedAt = item.addedAt,
                    lastActivityAt = maxOf(item.addedAt, watch?.updatedAt ?: 0L, reading?.updatedAt ?: 0L),
                    completed = isCompleted,
                    progressFraction = fraction,
                    resumePage = resumePage,
                    isOfflineReady = item.siteId.isLocal || bestStatus == DownloadStatus.COMPLETED,
                    downloadStatus = bestStatus,
                    mediaItem = if (reading != null && !chapterMatch) reading.toWorkMediaItem() else item.toMediaItem(),
                )
            }

            is ShelfDetailTarget.Smart -> source.smartEntries.mapIndexed { index, entry ->
                val key = entry.compositeKey
                val reading = if (entry.kind.isGallery) {
                    readingByWorkKey["${entry.siteId.value}:${entry.mediaId}"] ?: readingByChapterKey[key]
                } else {
                    null
                }
                val dls = downloadsByKey[key].orEmpty()
                val bestStatus = resolveAggregateDownloadStatus(dls) ?: entry.downloadStatus
                ShelfEntryUi(
                    siteId = entry.siteId,
                    siteLabel = siteRegistry.displayNameFor(entry.siteId),
                    mediaId = entry.mediaId,
                    url = entry.url,
                    kind = entry.kind,
                    title = entry.title,
                    coverUrl = entry.coverUrl,
                    position = index,
                    addedAt = entry.addedAt.takeIf { it > 0L } ?: entry.lastActivityAt,
                    lastActivityAt = entry.lastActivityAt,
                    completed = entry.completed,
                    progressFraction = entry.progressFraction,
                    resumePage = reading?.currentPage?.coerceAtLeast(1) ?: 1,
                    isOfflineReady = entry.isOffline || bestStatus == DownloadStatus.COMPLETED,
                    downloadStatus = bestStatus,
                    mediaItem = if (reading != null) reading.toWorkMediaItem() else entry.toMediaItem(),
                )
            }
        }

        val now = clockMs()
        val query = filters.searchQuery.trim().lowercase()
        val windowMs = filters.addedWindow.windowMs

        val filtered = rawEntries.filter { entry ->
            val matchesQuery = query.isEmpty() ||
                entry.title.lowercase().contains(query) ||
                entry.siteLabel.lowercase().contains(query) ||
                entry.siteId.value.lowercase().contains(query) ||
                entry.mediaId.lowercase().contains(query)
            val matchesCompletion = when (filters.completionFilter) {
                ShelfCompletionFilter.ALL -> true
                ShelfCompletionFilter.UNCOMPLETED -> !entry.completed
                ShelfCompletionFilter.COMPLETED -> entry.completed
            }
            val matchesKind = filters.mediaKindFilter == null || entry.kind == filters.mediaKindFilter
            val matchesSite = filters.siteIdFilter == null || entry.siteId == filters.siteIdFilter
            val matchesWindow = windowMs == null ||
                (entry.addedAt > 0L && (now - entry.addedAt) <= windowMs)

            matchesQuery && matchesCompletion && matchesKind && matchesSite && matchesWindow
        }

        val sorted = when (filters.viewSort) {
            ShelfViewSort.MANUAL -> filtered.sortedBy { it.position }
            ShelfViewSort.NEWEST -> filtered.sortedByDescending { it.addedAt }
            ShelfViewSort.OLDEST -> filtered.sortedBy { it.addedAt }
            ShelfViewSort.TITLE_AZ -> filtered.sortedWith { a, b ->
                NaturalSortComparator.compare(a.title, b.title)
            }
        }

        val offlinePrep = computeOfflinePrepSummary(sorted)
        val siteOptions = siteRegistry.buildSiteFilterOptions(rawEntries.map { it.siteId })

        val isNotFound = when (target) {
            is ShelfDetailTarget.Custom -> source.shelf == null
            is ShelfDetailTarget.Smart -> source.smartShelf == null
        }

        return ShelfDetailUiState(
            isSmartShelf = target is ShelfDetailTarget.Smart,
            shelf = source.shelf,
            smartShelf = source.smartShelf,
            isNotFound = isNotFound,
            title = source.shelf?.name ?: source.smartShelf?.name.orEmpty(),
            note = source.shelf?.note.orEmpty(),
            totalItemCount = rawEntries.size,
            completedCount = rawEntries.count { it.completed },
            allEntries = rawEntries,
            visibleEntries = sorted,
            filterState = filters,
            siteOptions = siteOptions,
            allSiteOptions = allSiteOptions,
            offlinePrep = offlinePrep,
            batchProgress = aux.batchProgress,
            pendingLaunch = ephemeral.pendingLaunch,
            deleted = ephemeral.deleted,
            message = ephemeral.message,
        )
    }

    private fun resolveAggregateDownloadStatus(downloads: List<DownloadItem>): DownloadStatus? {
        if (downloads.isEmpty()) return null
        return when {
            downloads.any { it.status == DownloadStatus.COMPLETED } -> DownloadStatus.COMPLETED
            downloads.any { it.status == DownloadStatus.DOWNLOADING } -> DownloadStatus.DOWNLOADING
            downloads.any { it.status == DownloadStatus.QUEUED } -> DownloadStatus.QUEUED
            downloads.any { it.status == DownloadStatus.RETRY_WAIT } -> DownloadStatus.RETRY_WAIT
            downloads.any { it.status == DownloadStatus.PAUSED } -> DownloadStatus.PAUSED
            downloads.any { it.status == DownloadStatus.FAILED } -> DownloadStatus.FAILED
            else -> downloads.first().status
        }
    }

    private fun computeOfflinePrepSummary(entries: List<ShelfEntryUi>): OfflinePreparationSummary {
        val ready = mutableListOf<ShelfEntryUi>()
        val queued = mutableListOf<ShelfEntryUi>()
        val missing = mutableListOf<ShelfEntryUi>()
        for (entry in entries) {
            when {
                entry.isOfflineReady -> ready += entry
                entry.downloadStatus != null && entry.downloadStatus.isInProgressBucket() -> queued += entry
                else -> missing += entry
            }
        }
        return OfflinePreparationSummary(
            readyItems = ready,
            queuedItems = queued,
            missingItems = missing,
        )
    }
}
