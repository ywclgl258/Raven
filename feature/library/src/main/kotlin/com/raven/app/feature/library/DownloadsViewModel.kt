package com.raven.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.media.download.BatchEnqueueProgress
import com.raven.app.core.media.download.DownloadNetworkPolicy
import com.raven.app.core.media.reader.GalleryAudit
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DownloadStatusTab(val label: String) {
    ALL("全部"),
    IN_PROGRESS("进行中"),
    COMPLETED("已完成"),
    ATTENTION("需处理");

    fun matches(status: DownloadStatus): Boolean = when (this) {
        ALL -> true
        IN_PROGRESS -> status.isInProgressBucket()
        COMPLETED -> status == DownloadStatus.COMPLETED
        ATTENTION -> status.needsAttention()
    }
}

enum class DownloadKindFilter(val label: String, val kind: DownloadKind?) {
    ALL("全部类型", null),
    VIDEO("视频", DownloadKind.VIDEO),
    COMIC("漫画", DownloadKind.COMIC),
    PHOTO("写真", DownloadKind.PHOTO),
}

data class IntegrityAuditDialogState(
    val item: DownloadItem,
    val isChecking: Boolean = false,
    val isRepairing: Boolean = false,
    val audit: GalleryAudit? = null,
    val errorMessage: String? = null,
)

data class DownloadsUiState(
    val selectedTab: DownloadStatusTab = DownloadStatusTab.ALL,
    val searchQuery: String = "",
    val kindFilter: DownloadKindFilter = DownloadKindFilter.ALL,
    val siteFilter: SiteId? = null,
    val groupFilter: String? = null,
    val filtersExpanded: Boolean = false,
    val allCount: Int = 0,
    val inProgressCount: Int = 0,
    val completedCount: Int = 0,
    val attentionCount: Int = 0,
    val activeTransferCount: Int = 0,
    val pausedCount: Int = 0,
    val failedCount: Int = 0,
    val totalCompletedBytes: Long = 0L,
    val items: List<DownloadItem> = emptyList(),
    val siteOptions: List<SiteFilterOption> = emptyList(),
    val siteLabels: Map<SiteId, String> = emptyMap(),
    val availableGroups: List<String> = emptyList(),
    val networkPolicy: DownloadNetworkPolicy = DownloadNetworkPolicy(),
    val batchProgress: BatchEnqueueProgress = BatchEnqueueProgress(),
    val integrityState: IntegrityAuditDialogState? = null,
    val isSyncingDisk: Boolean = false,
    val message: String? = null,
) {
    val activeExtraFilterCount: Int
        get() {
            var count = 0
            if (kindFilter != DownloadKindFilter.ALL) count++
            if (siteFilter != null) count++
            if (!groupFilter.isNullOrBlank()) count++
            return count
        }

    fun countForTab(tab: DownloadStatusTab): Int = when (tab) {
        DownloadStatusTab.ALL -> allCount
        DownloadStatusTab.IN_PROGRESS -> inProgressCount
        DownloadStatusTab.COMPLETED -> completedCount
        DownloadStatusTab.ATTENTION -> attentionCount
    }
}

private data class DownloadsControls(
    val selectedTab: DownloadStatusTab = DownloadStatusTab.ALL,
    val searchQuery: String = "",
    val kindFilter: DownloadKindFilter = DownloadKindFilter.ALL,
    val siteFilter: SiteId? = null,
    val groupFilter: String? = null,
    val filtersExpanded: Boolean = false,
    val integrityState: IntegrityAuditDialogState? = null,
    val isSyncingDisk: Boolean = false,
    val message: String? = null,
)

class DownloadsViewModel(
    private val downloadGateway: LibraryDownloadGateway,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val controls = MutableStateFlow(DownloadsControls())

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadGateway.downloads,
        downloadGateway.networkPolicy,
        downloadGateway.batchProgress,
        controls,
    ) { allDownloads, policy, batchProgress, ctrl ->
        val query = ctrl.searchQuery.trim().lowercase()
        val groups = allDownloads
            .mapNotNull { it.groupName.trim().takeIf(String::isNotEmpty) }
            .distinct()
            .sorted()

        val activeGroup = ctrl.groupFilter?.takeIf { it in groups }

        val filtered = allDownloads.filter { item ->
            val siteLabel = siteRegistry.displayNameFor(item.siteId)
            val matchesTab = ctrl.selectedTab.matches(item.status)
            val matchesKind = ctrl.kindFilter.kind == null || item.kind == ctrl.kindFilter.kind
            val matchesSite = ctrl.siteFilter == null || item.siteId == ctrl.siteFilter
            val matchesGroup = activeGroup == null || item.groupName.trim() == activeGroup
            val matchesQuery = query.isEmpty() ||
                item.title.lowercase().contains(query) ||
                item.groupName.lowercase().contains(query) ||
                siteLabel.lowercase().contains(query) ||
                item.siteId.value.lowercase().contains(query)

            matchesTab && matchesKind && matchesSite && matchesGroup && matchesQuery
        }

        DownloadsUiState(
            selectedTab = ctrl.selectedTab,
            searchQuery = ctrl.searchQuery,
            kindFilter = ctrl.kindFilter,
            siteFilter = ctrl.siteFilter,
            groupFilter = activeGroup,
            filtersExpanded = ctrl.filtersExpanded,
            allCount = allDownloads.size,
            inProgressCount = allDownloads.count { it.status.isInProgressBucket() },
            completedCount = allDownloads.count { it.status == DownloadStatus.COMPLETED },
            attentionCount = allDownloads.count { it.status.needsAttention() },
            activeTransferCount = allDownloads.count { it.status.isActiveTransfer() },
            pausedCount = allDownloads.count { it.status == DownloadStatus.PAUSED },
            failedCount = allDownloads.count { it.status == DownloadStatus.FAILED },
            totalCompletedBytes = allDownloads
                .filter { it.status == DownloadStatus.COMPLETED }
                .sumOf { it.totalBytes ?: it.downloadedBytes },
            items = filtered,
            siteOptions = siteRegistry.buildSiteFilterOptions(allDownloads.map { it.siteId }),
            siteLabels = allDownloads.associate { it.siteId to siteRegistry.displayNameFor(it.siteId) },
            availableGroups = groups,
            networkPolicy = policy,
            batchProgress = batchProgress,
            integrityState = ctrl.integrityState,
            isSyncingDisk = ctrl.isSyncingDisk,
            message = ctrl.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DownloadsUiState(),
    )

    // ---- Filters & Tabs ----

    fun selectTab(tab: DownloadStatusTab) {
        controls.update { it.copy(selectedTab = tab) }
    }

    fun updateSearchQuery(query: String) {
        controls.update { it.copy(searchQuery = query) }
    }

    fun toggleFiltersExpanded() {
        controls.update { it.copy(filtersExpanded = !it.filtersExpanded) }
    }

    fun selectKindFilter(filter: DownloadKindFilter) {
        controls.update { it.copy(kindFilter = filter) }
    }

    fun selectSiteFilter(siteId: SiteId?) {
        controls.update { it.copy(siteFilter = siteId) }
    }

    fun selectGroupFilter(group: String?) {
        controls.update { it.copy(groupFilter = group?.trim()?.takeIf(String::isNotEmpty)) }
    }

    fun resetExtraFilters() {
        controls.update {
            it.copy(
                kindFilter = DownloadKindFilter.ALL,
                siteFilter = null,
                groupFilter = null,
                searchQuery = "",
            )
        }
    }

    // ---- Batch Controls ----

    fun pauseAll() {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.pauseAll() }
                .onSuccess { controls.update { it.copy(message = "已暂停全部进行中的下载") } }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "暂停失败") } }
        }
    }

    fun resumeAll() {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.resumeAll() }
                .onSuccess { controls.update { it.copy(message = "已恢复已暂停的下载任务") } }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "恢复失败") } }
        }
    }

    fun retryAllFailed() {
        val visibleFailedIds = uiState.value.items
            .filter { it.status == DownloadStatus.FAILED }
            .map { it.id }
            .takeIf { it.isNotEmpty() }
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.retryAllFailed(visibleFailedIds) }
                .onSuccess { count ->
                    controls.update {
                        it.copy(
                            message = if (count > 0) "已重新加入 $count 个失败任务" else "当前没有可重试的失败任务",
                        )
                    }
                }
                .onFailure { e ->
                    controls.update { it.copy(message = e.message ?: "批量重试失败") }
                }
        }
    }

    fun cancelBatchEnqueue() {
        downloadGateway.cancelBatch()
    }

    fun syncFromDisk() {
        if (controls.value.isSyncingDisk) return
        viewModelScope.launch(dispatchers.io) {
            controls.update { it.copy(isSyncingDisk = true) }
            runCatching { downloadGateway.syncFromDisk() }
                .onSuccess { recovered ->
                    controls.update {
                        it.copy(
                            isSyncingDisk = false,
                            message = "磁盘同步完成（校验 ${recovered.size} 条离线记录）",
                        )
                    }
                }
                .onFailure { e ->
                    controls.update {
                        it.copy(
                            isSyncingDisk = false,
                            message = e.message ?: "同步磁盘记录失败",
                        )
                    }
                }
        }
    }

    // ---- Per-Item Controls ----

    fun pause(id: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.pause(id) }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "暂停失败") } }
        }
    }

    fun resume(id: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.resume(id) }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "继续失败") } }
        }
    }

    fun retry(id: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.retryFailed(id) }
                .onSuccess { ok ->
                    if (!ok) controls.update { it.copy(message = "任务暂时无法重试") }
                }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "重试失败") } }
        }
    }

    fun movePriority(id: String, delta: Int) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.movePriority(id, delta) }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "调整优先级失败") } }
        }
    }

    fun prioritize(id: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.prioritize(id) }
                .onSuccess { controls.update { it.copy(message = "已将任务置顶优先下载") } }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "置顶失败") } }
        }
    }

    fun setGroup(id: String, groupName: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.setGroup(id, groupName.trim()) }
                .onSuccess {
                    val trimmed = groupName.trim()
                    controls.update {
                        it.copy(
                            message = if (trimmed.isEmpty()) "已清除下载分组" else "已归入分组「$trimmed」",
                        )
                    }
                }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "设置分组失败") } }
        }
    }

    fun delete(item: DownloadItem) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.delete(item.id, item.localPath.orEmpty()) }
                .onSuccess { controls.update { it.copy(message = "已删除「${item.title}」") } }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "删除失败") } }
        }
    }

    // ---- Gallery Integrity Audit & Repair ----

    fun inspectIntegrity(item: DownloadItem) {
        if (item.kind == DownloadKind.VIDEO) {
            controls.update { it.copy(message = "完整性逐页校验仅适用于漫画与写真任务") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            controls.update {
                it.copy(
                    integrityState = IntegrityAuditDialogState(
                        item = item,
                        isChecking = true,
                    ),
                )
            }
            try {
                val audit = downloadGateway.inspectIntegrity(item.id)
                controls.update {
                    it.copy(
                        integrityState = IntegrityAuditDialogState(
                            item = item,
                            isChecking = false,
                            audit = audit,
                            errorMessage = if (audit == null) "未找到本地图集记录，无法执行完整性检查" else null,
                        ),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                controls.update {
                    it.copy(
                        integrityState = IntegrityAuditDialogState(
                            item = item,
                            isChecking = false,
                            errorMessage = error.message ?: "检查完整性失败",
                        ),
                    )
                }
            }
        }
    }

    fun repairGallery(item: DownloadItem) {
        viewModelScope.launch(dispatchers.io) {
            controls.update { current ->
                current.copy(
                    integrityState = current.integrityState?.copy(isRepairing = true),
                )
            }
            try {
                val updated = downloadGateway.repair(item.id)
                controls.update {
                    it.copy(
                        integrityState = null,
                        message = if (updated != null) {
                            "已将缺失或损坏页加入修复队列"
                        } else {
                            "未检测到可修复的任务"
                        },
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                controls.update { current ->
                    current.copy(
                        integrityState = current.integrityState?.copy(
                            isRepairing = false,
                            errorMessage = error.message ?: "触发修复失败",
                        ),
                    )
                }
            }
        }
    }

    fun dismissIntegritySheet() {
        controls.update { it.copy(integrityState = null) }
    }

    fun clearMessage() {
        controls.update { it.copy(message = null) }
    }
}
