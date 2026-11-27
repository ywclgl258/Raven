package com.raven.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.NaturalSortComparator
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.media.download.BatchEnqueueProgress
import com.raven.app.core.media.download.DownloadNetworkPolicy
import com.raven.app.core.media.reader.GalleryAudit
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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

enum class DownloadSort(val label: String) {
    RECENT("最近更新"),
    NEWEST("最近加入"),
    OLDEST("最早加入"),
    TITLE("标题 A–Z"),
    SIZE("大小 · 从大到小"),
    PROGRESS("进度 · 从高到低"),
    QUEUE("下载队列优先级");

    companion object {
        fun fromPreference(raw: String): DownloadSort = entries.firstOrNull { it.name == raw } ?: RECENT
    }
}

enum class DownloadGroupMode(val label: String) {
    NONE("不分组"),
    CUSTOM("自定义分组"),
    TAG("按标签分组"),
    SITE("按站点分组"),
    KIND("按类型分组");

    companion object {
        fun fromPreference(raw: String): DownloadGroupMode = entries.firstOrNull { it.name == raw } ?: NONE
    }
}

data class DownloadTagOption(
    val key: String,
    val slug: String,
    val label: String,
    val namespace: String,
    val count: Int,
)

data class DownloadTagNamespace(
    val key: String,
    val label: String,
    val tagCount: Int,
)

data class DownloadGroupSummary(
    val name: String,
    val itemCount: Int,
    val completedCount: Int,
    val totalBytes: Long,
)

data class DownloadSection(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val items: List<DownloadItem>,
    val isCollapsed: Boolean = false,
)

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
    val sort: DownloadSort = DownloadSort.RECENT,
    val groupMode: DownloadGroupMode = DownloadGroupMode.NONE,
    val tagMatchAll: Boolean = false,
    val selectedTagKeys: Set<String> = emptySet(),
    val selectedTagNamespace: String? = null,
    val availableTags: List<DownloadTagOption> = emptyList(),
    val visibleTags: List<DownloadTagOption> = emptyList(),
    val tagNamespaces: List<DownloadTagNamespace> = emptyList(),
    val sections: List<DownloadSection> = emptyList(),
    val groupSummaries: List<DownloadGroupSummary> = emptyList(),
    val allItems: List<DownloadItem> = emptyList(),
    val allCount: Int = 0,
    val inProgressCount: Int = 0,
    val completedCount: Int = 0,
    val attentionCount: Int = 0,
    val activeTransferCount: Int = 0,
    val pausedCount: Int = 0,
    val failedCount: Int = 0,
    val totalCompletedBytes: Long = 0L,
    val isCleaningCompleted: Boolean = false,
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
            if (selectedTagKeys.isNotEmpty()) count++
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
    val sort: DownloadSort? = null,
    val groupMode: DownloadGroupMode? = null,
    val tagMatchAll: Boolean? = null,
    val selectedTagKeys: Set<String> = emptySet(),
    val selectedTagNamespace: String? = null,
    val collapsedSectionKeys: Set<String> = emptySet(),
    val integrityState: IntegrityAuditDialogState? = null,
    val isSyncingDisk: Boolean = false,
    val message: String? = null,
    val isCleaningCompleted: Boolean = false,
)

private data class DownloadsSources(
    val downloads: List<DownloadItem>,
    val networkPolicy: DownloadNetworkPolicy,
    val batchProgress: BatchEnqueueProgress,
    val controls: DownloadsControls,
)

/**
 * Wrapper passed through Koin `parametersOf(...)` to avoid `@JvmInline value class` boxing issues.
 */
data class DownloadsParams(
    val initialSiteId: SiteId? = null,
)

class DownloadsViewModel(
    private val downloadGateway: LibraryDownloadGateway,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
    private val mediaTagCacheRepository: MediaTagCacheRepository,
    private val preferencesRepository: PreferencesRepository,
    private val tagDictionary: TagDictionary = TagDictionary.default,
    initialSiteId: SiteId? = null,
) : ViewModel() {

    private val controls = MutableStateFlow(DownloadsControls(siteFilter = initialSiteId))
    private var inspectJob: Job? = null

    private val sources = combine(
        downloadGateway.downloads,
        downloadGateway.networkPolicy,
        downloadGateway.batchProgress,
        controls,
    ) { downloads, policy, batchProgress, ctrl ->
        DownloadsSources(downloads, policy, batchProgress, ctrl)
    }

    val uiState: StateFlow<DownloadsUiState> = combine(
        sources,
        mediaTagCacheRepository.observeAllTags(),
        preferencesRepository.settings,
    ) { source, cachedTags, settings ->
        val ctrl = source.controls
        val allDownloads = source.downloads
        val queryTerms = ctrl.searchQuery.trim().lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
        val groups = allDownloads
            .mapNotNull { it.groupName.trim().takeIf(String::isNotEmpty) }
            .distinct()
            .sortedWith(NaturalSortComparator)
        val sort = ctrl.sort ?: DownloadSort.fromPreference(settings.downloadView.sort)
        val groupMode = ctrl.groupMode ?: DownloadGroupMode.fromPreference(settings.downloadView.groupMode)
        val tagMatchAll = ctrl.tagMatchAll ?: settings.downloadView.tagMatchAll

        val tagIndex = buildTagIndex(allDownloads, cachedTags)
        val availableTags = tagIndex.options
        val optionsByKey = availableTags.associateBy(DownloadTagOption::key)
        val activeTagKeys = ctrl.selectedTagKeys.filterTo(linkedSetOf()) { it in optionsByKey }
        val activeGroup = ctrl.groupFilter?.takeIf { it in groups }
        val tagNamespaces = availableTags
            .groupBy { it.namespace }
            .map { (namespace, options) ->
                DownloadTagNamespace(
                    key = namespace,
                    label = downloadTagNamespaceLabel(namespace),
                    tagCount = options.size,
                )
            }
            .sortedWith(compareByDescending<DownloadTagNamespace> { it.tagCount }.thenBy { it.label })
        val activeTagNamespace = ctrl.selectedTagNamespace?.takeIf { namespace ->
            tagNamespaces.any { it.key == namespace }
        }
        val tagQuery = ctrl.searchQuery.trim().lowercase(Locale.ROOT)
        val visibleTags = availableTags.filter { tag ->
            (activeTagNamespace == null || tag.namespace == activeTagNamespace) &&
                (tagQuery.isEmpty() || tag.label.lowercase(Locale.ROOT).contains(tagQuery) ||
                    tag.slug.contains(tagQuery) || tag.namespace.contains(tagQuery))
        }.take(24)

        val filtered = allDownloads.filter { item ->
            val siteLabel = siteRegistry.displayNameFor(item.siteId)
            val itemTags = tagIndex.tagsByItemId[item.id].orEmpty()
            val matchesTab = ctrl.selectedTab.matches(item.status)
            val matchesKind = ctrl.kindFilter.kind == null || item.kind == ctrl.kindFilter.kind
            val matchesSite = ctrl.siteFilter == null || item.siteId == ctrl.siteFilter
            val matchesGroup = activeGroup == null || item.groupName.trim() == activeGroup
            val matchesTags = activeTagKeys.isEmpty() || if (tagMatchAll) {
                itemTags.mapTo(HashSet()) { it.key }.containsAll(activeTagKeys)
            } else {
                itemTags.any { it.key in activeTagKeys }
            }
            val searchableText = buildString {
                append(item.title.lowercase(Locale.ROOT))
                append(' ').append(item.groupName.lowercase(Locale.ROOT))
                append(' ').append(siteLabel.lowercase(Locale.ROOT))
                append(' ').append(item.siteId.value.lowercase(Locale.ROOT))
                item.quality?.let { append(' ').append(it.lowercase(Locale.ROOT)) }
                itemTags.forEach { tag ->
                    append(' ').append(tag.label.lowercase(Locale.ROOT))
                    append(' ').append(tag.slug)
                    append(' ').append(tag.namespace)
                }
            }
            val matchesQuery = queryTerms.all(searchableText::contains)

            matchesTab && matchesKind && matchesSite && matchesGroup && matchesTags && matchesQuery
        }
        val sorted = sortDownloads(filtered, sort)
        val sections = buildDownloadSections(
            sortedItems = sorted,
            groupMode = groupMode,
            selectedTagKeys = activeTagKeys,
            tagIndex = tagIndex,
            collapsedSectionKeys = ctrl.collapsedSectionKeys,
        )
        val groupSummaries = groups.map { name ->
            val members = allDownloads.filter { it.groupName.trim() == name }
            DownloadGroupSummary(
                name = name,
                itemCount = members.size,
                completedCount = members.count { it.status == DownloadStatus.COMPLETED },
                totalBytes = members.sumOf { downloadBytes(it) },
            )
        }

        // 站点入口进入时，统计和批量操作必须都限定在该站点，而不是默默作用于全局。
        val scopedDownloads = if (ctrl.siteFilter == null) allDownloads else allDownloads.filter { it.siteId == ctrl.siteFilter }
        DownloadsUiState(
            selectedTab = ctrl.selectedTab,
            searchQuery = ctrl.searchQuery,
            kindFilter = ctrl.kindFilter,
            siteFilter = ctrl.siteFilter,
            groupFilter = activeGroup,
            filtersExpanded = ctrl.filtersExpanded,
            sort = sort,
            groupMode = groupMode,
            tagMatchAll = tagMatchAll,
            selectedTagKeys = activeTagKeys,
            selectedTagNamespace = activeTagNamespace,
            availableTags = availableTags,
            visibleTags = visibleTags,
            tagNamespaces = tagNamespaces,
            sections = sections,
            groupSummaries = groupSummaries,
            allItems = sortDownloads(allDownloads, DownloadSort.RECENT),
            allCount = allDownloads.size,
            inProgressCount = scopedDownloads.count { it.status.isInProgressBucket() },
            completedCount = scopedDownloads.count { it.status == DownloadStatus.COMPLETED },
            attentionCount = scopedDownloads.count { it.status.needsAttention() },
            activeTransferCount = scopedDownloads.count { it.status.isActiveTransfer() },
            pausedCount = scopedDownloads.count { it.status == DownloadStatus.PAUSED },
            failedCount = scopedDownloads.count { it.status == DownloadStatus.FAILED },
            totalCompletedBytes = scopedDownloads
                .filter { it.status == DownloadStatus.COMPLETED }
                .sumOf { it.totalBytes ?: it.downloadedBytes },
            items = sorted,
            siteOptions = siteRegistry.buildSiteFilterOptions(allDownloads.map { it.siteId }),
            siteLabels = allDownloads.associate { it.siteId to siteRegistry.displayNameFor(it.siteId) },
            availableGroups = groups,
            networkPolicy = source.networkPolicy,
            batchProgress = source.batchProgress,
            integrityState = ctrl.integrityState,
            isCleaningCompleted = ctrl.isCleaningCompleted,
            isSyncingDisk = ctrl.isSyncingDisk,
            message = ctrl.message,
        )
    }.flowOn(dispatchers.default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DownloadsUiState(),
    )

    // ---- Filters, tags, presentation & tabs ----

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

    fun toggleTag(tagKey: String) {
        controls.update {
            val selected = if (tagKey in it.selectedTagKeys) it.selectedTagKeys - tagKey else it.selectedTagKeys + tagKey
            it.copy(selectedTagKeys = selected)
        }
    }

    fun selectTagNamespace(namespace: String?) {
        controls.update { it.copy(selectedTagNamespace = namespace) }
    }

    fun toggleTagMatchAll() {
        val next = !(controls.value.tagMatchAll ?: uiState.value.tagMatchAll)
        controls.update { it.copy(tagMatchAll = next) }
        viewModelScope.launch(dispatchers.io) {
            runCatching { preferencesRepository.setDownloadTagMatchAll(next) }
                .onFailure { error -> showPersistenceError(error) }
        }
    }

    fun clearTagFilters() {
        controls.update { it.copy(selectedTagKeys = emptySet(), selectedTagNamespace = null) }
    }

    fun selectSort(sort: DownloadSort) {
        controls.update { it.copy(sort = sort) }
        viewModelScope.launch(dispatchers.io) {
            runCatching { preferencesRepository.setDownloadSort(sort.name) }
                .onFailure { error -> showPersistenceError(error) }
        }
    }

    fun selectGroupMode(mode: DownloadGroupMode) {
        controls.update { it.copy(groupMode = mode) }
        viewModelScope.launch(dispatchers.io) {
            runCatching { preferencesRepository.setDownloadGroupMode(mode.name) }
                .onFailure { error -> showPersistenceError(error) }
        }
    }

    fun toggleSection(sectionKey: String) {
        controls.update {
            val collapsed = if (sectionKey in it.collapsedSectionKeys) {
                it.collapsedSectionKeys - sectionKey
            } else {
                it.collapsedSectionKeys + sectionKey
            }
            it.copy(collapsedSectionKeys = collapsed)
        }
    }

    fun resetExtraFilters() {
        controls.update {
            it.copy(
                kindFilter = DownloadKindFilter.ALL,
                siteFilter = null,
                groupFilter = null,
                selectedTagKeys = emptySet(),
                selectedTagNamespace = null,
                searchQuery = "",
            )
        }
    }

    // ---- Batch Controls ----

    /** Everything a batch action touches must match the site's visible scope, never the whole DB. */
    private fun scopedItems(): List<DownloadItem> {
        val scope = uiState.value.siteFilter
        return if (scope == null) uiState.value.allItems else uiState.value.allItems.filter { it.siteId == scope }
    }

    private fun scopeLabel(): String? = uiState.value.siteFilter?.let(siteRegistry::displayNameFor)

    fun pauseAll() {
        val targets = scopedItems().filter { it.status.isActiveTransfer() }
        if (targets.isEmpty()) {
            controls.update { it.copy(message = "当前没有进行中的下载") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            var paused = 0
            var failed = 0
            targets.forEach { item ->
                runCatching { downloadGateway.pause(item.id) }.onSuccess { paused++ }.onFailure { failed++ }
            }
            val scope = scopeLabel()?.let { "「$it」 " }.orEmpty()
            controls.update {
                it.copy(
                    message = buildString {
                        append("已暂停 ${scope}$paused 项下载")
                        if (failed > 0) append("，$failed 项失败")
                    },
                )
            }
        }
    }

    fun resumeAll() {
        val targets = scopedItems().filter { it.status == DownloadStatus.PAUSED }
        if (targets.isEmpty()) {
            controls.update { it.copy(message = "当前没有已暂停的下载") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            var resumed = 0
            var failed = 0
            targets.forEach { item ->
                runCatching { downloadGateway.resume(item.id) }.onSuccess { resumed++ }.onFailure { failed++ }
            }
            val scope = scopeLabel()?.let { "「$it」 " }.orEmpty()
            controls.update {
                it.copy(
                    message = buildString {
                        append("已继续 ${scope}$resumed 项下载")
                        if (failed > 0) append("，$failed 项失败")
                    },
                )
            }
        }
    }

    fun retryAllFailed() {
        val targets = scopedItems().filter { it.status.needsAttention() }
        if (targets.isEmpty()) {
            controls.update { it.copy(message = "当前没有可重试的需处理任务") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            var retried = 0
            var skipped = 0
            targets.forEach { item ->
                try {
                    if (downloadGateway.retryFailed(item.id)) {
                        retried += 1
                    } else {
                        skipped += 1
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    skipped += 1
                }
            }
            val scope = scopeLabel()?.let { "「$it」 " }.orEmpty()
            controls.update {
                it.copy(
                    message = when {
                        retried > 0 && skipped > 0 -> "已重新加入 ${scope}$retried 个需处理任务，$skipped 项暂时无法重试"
                        retried > 0 -> "已重新加入 ${scope}$retried 个需处理任务"
                        else -> "当前没有可重试的需处理任务"
                    },
                )
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
                .onSuccess { showVideoQueueOrder(id) }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "调整优先级失败") } }
        }
    }

    fun prioritize(id: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.prioritize(id) }
                .onSuccess {
                    showVideoQueueOrder(id)
                    controls.update { it.copy(message = "已将任务置顶优先下载") }
                }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "置顶失败") } }
        }
    }

    private fun showVideoQueueOrder(id: String) {
        if (uiState.value.allItems.any { it.id == id && it.kind == DownloadKind.VIDEO }) {
            controls.update { it.copy(sort = DownloadSort.QUEUE) }
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

    fun createGroup(groupName: String, itemIds: Set<String>) {
        val normalized = groupName.trim()
        val targets = uiState.value.allItems.filter { it.id in itemIds }
        if (normalized.isEmpty() || targets.isEmpty()) {
            controls.update { it.copy(message = "创建分组需要填写名称并至少选择一个下载项") }
            return
        }
        updateGroupMembers(targets.map { it.id }, normalized, "已创建分组「$normalized」，归入 ${targets.size} 项")
    }

    fun renameGroup(oldName: String, newName: String) {
        val normalized = newName.trim()
        val targets = uiState.value.allItems.filter { it.groupName.trim() == oldName }
        if (normalized.isEmpty() || targets.isEmpty()) {
            controls.update { it.copy(message = "请输入新的分组名称") }
            return
        }
        if (normalized == oldName) return
        val merges = uiState.value.availableGroups.any { it == normalized }
        updateGroupMembers(
            ids = targets.map { it.id },
            groupName = normalized,
            successMessage = if (merges) {
                "已将「$oldName」合并至「$normalized」"
            } else {
                "已将「$oldName」重命名为「$normalized」"
            },
            onCommitted = {
                controls.update {
                    it.copy(groupFilter = if (it.groupFilter == oldName) normalized else it.groupFilter)
                }
            },
        )
    }

    fun clearGroup(groupName: String) {
        val targets = uiState.value.allItems.filter { it.groupName.trim() == groupName }
        if (targets.isEmpty()) return
        updateGroupMembers(
            ids = targets.map { it.id },
            groupName = "",
            successMessage = "已移除分组「$groupName」，保留下载与缓存文件",
            onCommitted = {
                controls.update { it.copy(groupFilter = if (it.groupFilter == groupName) null else it.groupFilter) }
            },
        )
    }

    private fun updateGroupMembers(
        ids: List<String>,
        groupName: String,
        successMessage: String,
        onCommitted: () -> Unit = {},
    ) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.setGroupForItems(ids, groupName) }
                .onSuccess {
                    onCommitted()
                    controls.update { it.copy(message = successMessage) }
                }
                .onFailure { error ->
                    controls.update { it.copy(message = error.message ?: "批量更新分组失败") }
                }
        }
    }

    private fun showPersistenceError(error: Throwable) {
        controls.update { it.copy(message = "当前页面已生效，但保存排列偏好失败：${error.message ?: "未知错误"}") }
    }

    fun delete(item: DownloadItem) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { downloadGateway.delete(item.id, item.localPath.orEmpty()) }
                .onSuccess { controls.update { it.copy(message = "已删除「${item.title}」") } }
                .onFailure { e -> controls.update { it.copy(message = e.message ?: "删除失败") } }
        }
    }

    fun deleteCompletedDownloads() {
        if (controls.value.isCleaningCompleted) return
        val targets = scopedItems().filter { it.status == DownloadStatus.COMPLETED }
        if (targets.isEmpty()) {
            controls.update { it.copy(message = "当前没有可清理的已完成下载") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            controls.update { it.copy(isCleaningCompleted = true) }
            var deletedCount = 0
            var releasedBytes = 0L
            var failedCount = 0
            targets.forEach { item ->
                try {
                    downloadGateway.delete(item.id, item.localPath.orEmpty())
                    deletedCount += 1
                    releasedBytes += downloadBytes(item)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    failedCount += 1
                }
            }
            val scope = scopeLabel()?.let { "「$it」 " }.orEmpty()
            controls.update {
                it.copy(
                    isCleaningCompleted = false,
                    message = completedCleanupMessage(deletedCount, releasedBytes, failedCount, scope),
                )
            }
        }
    }

    // ---- Gallery Integrity Audit & Repair ----

    fun inspectIntegrity(item: DownloadItem) {
        if (item.kind == DownloadKind.VIDEO) {
            controls.update { it.copy(message = "完整性逐页校验仅适用于漫画与写真任务") }
            return
        }
        inspectJob?.cancel()
        val checking = IntegrityAuditDialogState(item = item, isChecking = true)
        controls.update { it.copy(integrityState = checking) }
        inspectJob = viewModelScope.launch {
            val result = try {
                val audit = withContext(dispatchers.io) { downloadGateway.inspectIntegrity(item.id) }
                checking.copy(
                    isChecking = false,
                    audit = audit,
                    errorMessage = if (audit == null) "未找到本地图集记录，无法执行完整性检查" else null,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                checking.copy(isChecking = false, errorMessage = error.message ?: "检查完整性失败")
            }
            controls.update { if (it.integrityState == checking) it.copy(integrityState = result) else it }
        }
    }

    fun repairGallery(item: DownloadItem) {
        val sheet = controls.value.integrityState ?: return
        if (sheet.item.id != item.id || sheet.isRepairing || sheet.isChecking) return
        val repairing = sheet.copy(isRepairing = true)
        controls.update { it.copy(integrityState = repairing) }
        viewModelScope.launch {
            try {
                val updated = withContext(dispatchers.io) { downloadGateway.repair(item.id) }
                val message = if (updated != null) "已将缺失或损坏页加入修复队列" else "未检测到可修复的任务"
                controls.update { current ->
                    if (current.integrityState === repairing) current.copy(integrityState = null, message = message)
                    else current
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                controls.update { current ->
                    if (current.integrityState !== repairing) current
                    else current.copy(integrityState = sheet.copy(errorMessage = error.message ?: "触发修复失败"))
                }
            }
        }
    }

    fun dismissIntegritySheet() {
        inspectJob?.cancel()
        controls.update { it.copy(integrityState = null) }
    }

    fun clearMessage() {
        controls.update { it.copy(message = null) }
    }

    private fun buildTagIndex(
        downloads: List<DownloadItem>,
        cachedTags: Map<String, List<com.raven.app.core.model.TagRef>>,
    ): DownloadTagIndex {
        val tagsByItemId = HashMap<String, List<DownloadTagOption>>(downloads.size)
        val metadata = LinkedHashMap<String, DownloadTagOption>()
        val counts = HashMap<String, Int>()
        for (item in downloads) {
            val refs = cachedTags["${item.siteId.value}:${item.mediaId}"].orEmpty()
            val itemTags = refs.mapNotNull { ref ->
                val (embeddedNamespace, rawBare) = TagDictionary.splitNamespace(ref.slug)
                val namespace = ref.namespace.trim().ifEmpty { embeddedNamespace }.lowercase(Locale.ROOT)
                val bare = TagDictionary.normalizeBareSlug(rawBare)
                if (bare.isEmpty()) return@mapNotNull null
                val key = "$namespace:$bare"
                DownloadTagOption(
                    key = key,
                    slug = bare,
                    label = tagDictionary.displayLabel(
                        rawSlug = ref.slug,
                        fallbackLabel = ref.label.ifBlank { rawBare },
                        namespace = namespace,
                    ),
                    namespace = namespace,
                    count = 0,
                )
            }.distinctBy(DownloadTagOption::key)
            tagsByItemId[item.id] = itemTags
            itemTags.forEach { tag ->
                metadata.putIfAbsent(tag.key, tag)
                counts[tag.key] = (counts[tag.key] ?: 0) + 1
            }
        }
        val options = metadata.values.map { tag -> tag.copy(count = counts[tag.key] ?: 0) }
            .sortedWith(compareByDescending<DownloadTagOption> { it.count }.thenBy { it.label })
        return DownloadTagIndex(options = options, tagsByItemId = tagsByItemId)
    }

    private fun buildDownloadSections(
        sortedItems: List<DownloadItem>,
        groupMode: DownloadGroupMode,
        selectedTagKeys: Set<String>,
        tagIndex: DownloadTagIndex,
        collapsedSectionKeys: Set<String>,
    ): List<DownloadSection> {
        val sections = when (groupMode) {
            DownloadGroupMode.NONE -> listOf(
                DownloadSection(key = "NONE:all", title = "全部下载", items = sortedItems),
            )

            DownloadGroupMode.CUSTOM -> {
                val byGroup = sortedItems.groupBy { it.groupName.trim() }
                val named = byGroup.keys.filter(String::isNotEmpty).sortedWith(NaturalSortComparator).map { name ->
                    DownloadSection(
                        key = "CUSTOM:named:$name",
                        title = name,
                        subtitle = "自定义分组",
                        items = byGroup[name].orEmpty(),
                    )
                }
                val ungrouped = byGroup[""].orEmpty()
                if (ungrouped.isEmpty()) {
                    named
                } else {
                    named + DownloadSection(
                        key = "CUSTOM:special:ungrouped",
                        title = "未分组",
                        subtitle = "尚未归入手动分组",
                        items = ungrouped,
                    )
                }
            }

            DownloadGroupMode.TAG -> {
                val itemsByTag = LinkedHashMap<String, MutableList<DownloadItem>>()
                val untagged = mutableListOf<DownloadItem>()
                sortedItems.forEach { item ->
                    val itemTags = tagIndex.tagsByItemId[item.id].orEmpty()
                    val includedTags = if (selectedTagKeys.isEmpty()) {
                        itemTags
                    } else {
                        itemTags.filter { it.key in selectedTagKeys }
                    }
                    if (includedTags.isEmpty()) {
                        untagged += item
                    } else {
                        includedTags.forEach { tag -> itemsByTag.getOrPut(tag.key) { mutableListOf() } += item }
                    }
                }
                val tagOrder = tagIndex.options.mapIndexed { index, tag -> tag.key to index }.toMap()
                val taggedSections = itemsByTag.entries.sortedBy { (key, _) ->
                    tagOrder[key] ?: Int.MAX_VALUE
                }.map { (key, items) ->
                    val tag = tagIndex.options.first { it.key == key }
                    DownloadSection(
                        key = "TAG:named:$key",
                        title = tag.label,
                        subtitle = "${downloadTagNamespaceLabel(tag.namespace)} · 同一作品可出现在多个标签下",
                        items = items,
                    )
                }
                if (untagged.isEmpty()) {
                    taggedSections
                } else {
                    taggedSections + DownloadSection(
                        key = "TAG:special:untagged",
                        title = "未标记",
                        subtitle = "这些下载项还没有可用的标签缓存",
                        items = untagged,
                    )
                }
            }

            DownloadGroupMode.SITE -> sortedItems.groupBy { it.siteId }.entries
                .sortedWith { left, right ->
                    NaturalSortComparator.compare(
                        siteRegistry.displayNameFor(left.key),
                        siteRegistry.displayNameFor(right.key),
                    )
                }
                .map { (siteId, items) ->
                    DownloadSection(
                        key = "SITE:${siteId.value}",
                        title = siteRegistry.displayNameFor(siteId),
                        subtitle = "站点",
                        items = items,
                    )
                }

            DownloadGroupMode.KIND -> DownloadKind.entries.mapNotNull { kind ->
                val items = sortedItems.filter { it.kind == kind }
                if (items.isEmpty()) {
                    null
                } else {
                    DownloadSection(
                        key = "KIND:${kind.name}",
                        title = kind.displayLabel(),
                        subtitle = "内容类型",
                        items = items,
                    )
                }
            }
        }
        return sections.map { it.copy(isCollapsed = it.key in collapsedSectionKeys) }
    }
}

private data class DownloadTagIndex(
    val options: List<DownloadTagOption>,
    val tagsByItemId: Map<String, List<DownloadTagOption>>,
)

private fun sortDownloads(items: List<DownloadItem>, sort: DownloadSort): List<DownloadItem> {
    val byTitle = Comparator<DownloadItem> { left, right -> NaturalSortComparator.compare(left.title, right.title) }
    return when (sort) {
        DownloadSort.RECENT -> items.sortedWith(
            compareByDescending<DownloadItem> { it.updatedAt }.then(byTitle).thenBy { it.id },
        )
        DownloadSort.NEWEST -> items.sortedWith(
            compareByDescending<DownloadItem> { it.createdAt }.then(byTitle).thenBy { it.id },
        )
        DownloadSort.OLDEST -> items.sortedWith(
            compareBy<DownloadItem> { it.createdAt }.then(byTitle).thenBy { it.id },
        )
        DownloadSort.TITLE -> items.sortedWith(byTitle.thenByDescending { it.updatedAt }.thenBy { it.id })
        DownloadSort.SIZE -> items.sortedWith(
            compareByDescending<DownloadItem> { downloadBytes(it) }.then(byTitle).thenBy { it.id },
        )
        DownloadSort.PROGRESS -> items.sortedWith(
            compareByDescending<DownloadItem> { downloadProgress(it) }.then(byTitle).thenBy { it.id },
        )
        DownloadSort.QUEUE -> items.sortedWith(
            compareByDescending<DownloadItem> { it.priority }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )
    }
}

private fun downloadBytes(item: DownloadItem): Long = (item.totalBytes ?: item.downloadedBytes).coerceAtLeast(0L)

private fun completedCleanupMessage(
    deletedCount: Int,
    releasedBytes: Long,
    failedCount: Int,
    scope: String = "",
): String = when {
    deletedCount > 0 && failedCount > 0 ->
        "已清理 ${scope}$deletedCount 个已完成下载，释放 ${RavenFormatters.formatByteSize(releasedBytes)}；$failedCount 项删除失败"
    deletedCount > 0 ->
        "已清理 ${scope}$deletedCount 个已完成下载，释放 ${RavenFormatters.formatByteSize(releasedBytes)}"
    else -> "未能清理已完成下载，请稍后重试"
}

private fun downloadProgress(item: DownloadItem): Float = when {
    item.status == DownloadStatus.COMPLETED -> 1f
    item.totalUnits > 0 -> (item.completedUnits.toFloat() / item.totalUnits).coerceIn(0f, 1f)
    else -> item.progressFraction.coerceIn(0f, 1f)
}

internal fun downloadTagNamespaceLabel(namespace: String): String = when (namespace.trim().lowercase(Locale.ROOT)) {
    "artist" -> "作者"
    "parody", "series" -> "原作"
    "character" -> "角色"
    "group" -> "社团"
    "language" -> "语言"
    "category" -> "分类"
    "female" -> "女性标签"
    "male" -> "男性标签"
    "mixed" -> "混合标签"
    "reclass" -> "重分类"
    "other" -> "其他"
    "" -> "通用"
    else -> namespace
}
