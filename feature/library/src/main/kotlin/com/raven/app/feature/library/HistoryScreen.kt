package com.raven.app.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.domain.CompletionPolicy
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.designsystem.component.RavenCompactMediaRow
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenScrollToTopButton
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class HistoryEntryUi(
    val siteId: SiteId,
    val siteLabel: String,
    val mediaId: String,
    val url: String,
    val kind: MediaKind,
    val title: String,
    val coverUrl: String,
    val progressFraction: Float,
    val statusCaption: String,
    val updatedAt: Long,
    val completed: Boolean,
    val resumePage: Int,
    val mediaItem: MediaItem,
) {
    val compositeKey: String get() = "${siteId.value}:${kind.name}:$mediaId"
}

data class HistoryUiState(
    val searchQuery: String = "",
    val mediaFilter: LibraryMediaFilter = LibraryMediaFilter.ALL,
    val completionFilter: ShelfCompletionFilter = ShelfCompletionFilter.ALL,
    val siteFilter: SiteId? = null,
    val totalCount: Int = 0,
    val entries: List<HistoryEntryUi> = emptyList(),
    val siteOptions: List<SiteFilterOption> = emptyList(),
    val shelves: List<ShelfWithSummary> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedKeys: Set<String> = emptySet(),
    val message: String? = null,
)

private data class HistoryControls(
    val searchQuery: String = "",
    val mediaFilter: LibraryMediaFilter = LibraryMediaFilter.ALL,
    val completionFilter: ShelfCompletionFilter = ShelfCompletionFilter.ALL,
    val siteFilter: SiteId? = null,
    val selectionMode: Boolean = false,
    val selectedKeys: Set<String> = emptySet(),
    val message: String? = null,
)

class HistoryViewModel(
    private val historyRepository: HistoryRepository,
    private val shelfRepository: ShelfRepository,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val controls = MutableStateFlow(HistoryControls())

    val uiState: StateFlow<HistoryUiState> = combine(
        historyRepository.observeAllWatchProgress(),
        historyRepository.observeAllReadingProgress(),
        shelfRepository.observeShelvesWithSummary(),
        controls,
    ) { watches, readings, shelves, ctrl ->
        val merged = buildList {
            watches.forEach { w ->
                add(
                    HistoryEntryUi(
                        siteId = w.siteId,
                        siteLabel = siteRegistry.displayNameFor(w.siteId),
                        mediaId = w.mediaId,
                        url = w.url,
                        kind = MediaKind.VIDEO,
                        title = w.title,
                        coverUrl = w.coverUrl,
                        progressFraction = w.progressFraction,
                        statusCaption = w.resumeCaption(),
                        updatedAt = w.updatedAt,
                        completed = CompletionPolicy.isWatchCompleted(w),
                        resumePage = 1,
                        mediaItem = w.toMediaItem(),
                    ),
                )
            }
            readings.forEach { r ->
                add(
                    HistoryEntryUi(
                        siteId = r.siteId,
                        siteLabel = siteRegistry.displayNameFor(r.siteId),
                        mediaId = r.mediaId,
                        url = r.url,
                        kind = r.kind,
                        title = r.title,
                        coverUrl = r.coverUrl,
                        progressFraction = r.progressFraction,
                        statusCaption = r.resumeCaption(),
                        updatedAt = r.updatedAt,
                        completed = CompletionPolicy.isReadingCompleted(r),
                        resumePage = r.currentPage.coerceAtLeast(1),
                        mediaItem = r.toMediaItem(),
                    ),
                )
            }
        }.sortedByDescending { it.updatedAt }

        val query = ctrl.searchQuery.trim().lowercase()
        val filtered = merged.filter { entry ->
            val matchesQuery = query.isEmpty() ||
                entry.title.lowercase().contains(query) ||
                entry.siteLabel.lowercase().contains(query) ||
                entry.siteId.value.lowercase().contains(query)
            val matchesKind = ctrl.mediaFilter.mediaKind == null || entry.kind == ctrl.mediaFilter.mediaKind
            val matchesCompletion = when (ctrl.completionFilter) {
                ShelfCompletionFilter.ALL -> true
                ShelfCompletionFilter.UNCOMPLETED -> !entry.completed
                ShelfCompletionFilter.COMPLETED -> entry.completed
            }
            val matchesSite = ctrl.siteFilter == null || entry.siteId == ctrl.siteFilter
            matchesQuery && matchesKind && matchesCompletion && matchesSite
        }

        val validKeys = filtered.mapTo(mutableSetOf()) { it.compositeKey }
        HistoryUiState(
            searchQuery = ctrl.searchQuery,
            mediaFilter = ctrl.mediaFilter,
            completionFilter = ctrl.completionFilter,
            siteFilter = ctrl.siteFilter,
            totalCount = merged.size,
            entries = filtered,
            siteOptions = siteRegistry.buildSiteFilterOptions(merged.map { it.siteId }),
            shelves = shelves,
            selectionMode = ctrl.selectionMode,
            selectedKeys = ctrl.selectedKeys.intersect(validKeys),
            message = ctrl.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HistoryUiState(),
    )

    fun updateSearchQuery(query: String) {
        controls.update { it.copy(searchQuery = query) }
    }

    fun selectMediaFilter(filter: LibraryMediaFilter) {
        controls.update { it.copy(mediaFilter = filter) }
    }

    fun selectCompletionFilter(filter: ShelfCompletionFilter) {
        controls.update { it.copy(completionFilter = filter) }
    }

    fun selectSiteFilter(siteId: SiteId?) {
        controls.update { it.copy(siteFilter = siteId) }
    }

    fun toggleSelectionMode(enabled: Boolean = !controls.value.selectionMode) {
        controls.update {
            it.copy(
                selectionMode = enabled,
                selectedKeys = if (enabled) it.selectedKeys else emptySet(),
            )
        }
    }

    fun toggleSelectEntry(entry: HistoryEntryUi) {
        controls.update { current ->
            val next = if (entry.compositeKey in current.selectedKeys) {
                current.selectedKeys - entry.compositeKey
            } else {
                current.selectedKeys + entry.compositeKey
            }
            current.copy(selectionMode = true, selectedKeys = next)
        }
    }

    fun selectAllVisible() {
        val visible = uiState.value.entries.mapTo(linkedSetOf()) { it.compositeKey }
        controls.update { it.copy(selectionMode = true, selectedKeys = visible) }
    }

    fun clearSelection() {
        controls.update { it.copy(selectionMode = false, selectedKeys = emptySet()) }
    }

    fun removeEntry(entry: HistoryEntryUi) {
        viewModelScope.launch(dispatchers.io) {
            if (entry.kind.isVideo) {
                historyRepository.removeWatchProgress(entry.siteId, entry.mediaId)
            } else {
                historyRepository.removeReadingProgress(entry.siteId, entry.mediaId)
            }
            controls.update { it.copy(message = "已删除该条历史记录") }
        }
    }

    fun removeSelected() {
        val snapshot = uiState.value
        val targets = snapshot.entries.filter { it.compositeKey in snapshot.selectedKeys }
        if (targets.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            for (entry in targets) {
                if (entry.kind.isVideo) {
                    historyRepository.removeWatchProgress(entry.siteId, entry.mediaId)
                } else {
                    historyRepository.removeReadingProgress(entry.siteId, entry.mediaId)
                }
            }
            controls.update {
                it.copy(
                    selectionMode = false,
                    selectedKeys = emptySet(),
                    message = "已移除 ${targets.size} 条历史记录",
                )
            }
        }
    }

    fun clearHistory(siteId: SiteId? = null) {
        viewModelScope.launch(dispatchers.io) {
            if (siteId == null) {
                historyRepository.clearAllWatchProgress()
                historyRepository.clearAllReadingProgress()
            } else {
                historyRepository.clearWatchProgress(siteId)
                historyRepository.clearReadingProgress(siteId)
            }
            controls.update {
                it.copy(
                    selectionMode = false,
                    selectedKeys = emptySet(),
                    message = if (siteId == null) {
                        "已清空全部观看与阅读历史"
                    } else {
                        "已清空 ${siteRegistry.displayNameFor(siteId)} 的历史记录"
                    },
                )
            }
        }
    }

    fun addEntriesToShelf(shelfId: String, entries: List<HistoryEntryUi>) {
        if (entries.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            val added = shelfRepository.addItems(
                shelfId = shelfId,
                items = entries.map { it.mediaItem.toLibraryShelfItem(shelfId) },
            )
            controls.update {
                it.copy(
                    selectionMode = false,
                    selectedKeys = emptySet(),
                    message = if (added > 0) "已将 $added 项加入片单" else "所选作品已在目标片单中",
                )
            }
        }
    }

    fun createShelfAndAddEntries(shelfName: String, entries: List<HistoryEntryUi>) {
        if (entries.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val created = shelfRepository.createShelf(name = shelfName)
                val added = shelfRepository.addItems(
                    shelfId = created.id,
                    items = entries.map { it.mediaItem.toLibraryShelfItem(created.id) },
                )
                created.name to added
            }.onSuccess { (name, added) ->
                controls.update {
                    it.copy(
                        selectionMode = false,
                        selectedKeys = emptySet(),
                        message = "已创建「$name」并加入 $added 项作品",
                    )
                }
            }.onFailure { error ->
                controls.update { it.copy(message = error.message ?: "创建片单失败") }
            }
        }
    }

    fun clearMessage() {
        controls.update { it.copy(message = null) }
    }
}

@Composable
fun HistoryScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current

    var shelfPickerTargets by remember { mutableStateOf<List<HistoryEntryUi>?>(null) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("history_screen"),
    ) {
        RavenTopBar(
            title = "观看与阅读历史",
            subtitle = "共 ${uiState.entries.size} / ${uiState.totalCount} 条",
            navigationIcon = {
                IconButton(onClick = navigator::navigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = { viewModel.toggleSelectionMode() },
                    modifier = Modifier.testTag("history_multiselect_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckBox,
                        contentDescription = "批量管理",
                        tint = if (uiState.selectionMode) colors.crimson else colors.inkPrimary,
                    )
                }
                if (uiState.totalCount > 0) {
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.testTag("history_clear_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "清空历史",
                            tint = colors.inkSecondary,
                        )
                    }
                }
            },
        )

        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            if (!uiState.message.isNullOrBlank()) {
                item(key = "hist_msg") {
                    LibraryFeedbackBanner(
                        message = uiState.message.orEmpty(),
                        tone = RavenStatusTone.ACCENT,
                        onDismiss = viewModel::clearMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "hist_search") {
                RavenSearchField(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    placeholder = "搜索历史记录标题或站点…",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item(key = "hist_media_chips") {
                LibraryMediaFilterChips(
                    selected = uiState.mediaFilter,
                    onSelect = viewModel::selectMediaFilter,
                )
            }

            item(key = "hist_completion_chips") {
                RavenFilterChipRow(
                    items = ShelfCompletionFilter.entries,
                    selectedItem = uiState.completionFilter,
                    labelProvider = { it.label },
                    onItemSelected = viewModel::selectCompletionFilter,
                    keyProvider = { it.name },
                )
            }

            if (uiState.siteOptions.isNotEmpty()) {
                item(key = "hist_site_chips") {
                    LibrarySiteFilterChips(
                        options = uiState.siteOptions,
                        selectedSiteId = uiState.siteFilter,
                        onSelectSite = viewModel::selectSiteFilter,
                    )
                }
            }

            if (uiState.entries.isEmpty()) {
                item(key = "hist_empty") {
                    RavenEmptyState(
                        title = if (uiState.totalCount == 0) "暂无观看与阅读历史" else "没有匹配的历史记录",
                        message = if (uiState.totalCount == 0) {
                            "播放视频或阅读漫画写真时会自动记录进度，方便随时继续。"
                        } else {
                            "尝试更换关键词或清除筛选条件。"
                        },
                        icon = Icons.Outlined.History,
                    )
                }
            } else {
                items(
                    items = uiState.entries,
                    key = { it.compositeKey },
                ) { entry ->
                    val selected = entry.compositeKey in uiState.selectedKeys
                    RavenCompactMediaRow(
                        item = entry.mediaItem,
                        onClick = {
                            if (uiState.selectionMode) {
                                viewModel.toggleSelectEntry(entry)
                            } else {
                                navigator.openMedia(entry.mediaItem, startPage = entry.resumePage)
                            }
                        },
                        onLongClick = { viewModel.toggleSelectEntry(entry) },
                        subtitle = "${entry.siteLabel} · ${entry.statusCaption} · ${formatRelativeTime(entry.updatedAt)}",
                        progressFraction = entry.progressFraction.takeIf { it > 0f },
                        statusContent = {
                            if (entry.completed) {
                                RavenStatusPill(text = "已完成", tone = RavenStatusTone.SUCCESS)
                            }
                        },
                        trailingContent = {
                            if (uiState.selectionMode) {
                                SelectCheckBadge(selected = selected)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { shelfPickerTargets = listOf(entry) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.BookmarkAdd,
                                            contentDescription = "加入片单",
                                            tint = colors.inkSecondary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeEntry(entry) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "删除历史",
                                            tint = colors.inkTertiary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp),
                    )
                }
            }
            }
            RavenScrollToTopButton(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 22.dp),
            )
        }

        val motionReduced = com.raven.app.core.designsystem.theme.LocalMotionReduced.current
        AnimatedVisibility(
            visible = uiState.selectionMode,
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                expandVertically(tween(200, easing = com.raven.app.core.designsystem.theme.RavenMotion.HarmonyDecelerationEasing)) +
                    fadeIn(tween(160))
            },
            exit = if (motionReduced) {
                ExitTransition.None
            } else {
                shrinkVertically(tween(170, easing = com.raven.app.core.designsystem.theme.RavenMotion.HarmonyDecelerationEasing)) +
                    fadeOut(tween(120))
            },
        ) {
            val selectedItems = uiState.entries.filter { it.compositeKey in uiState.selectedKeys }
            LibrarySelectionActionBar(
                selectedCount = selectedItems.size,
                onSelectAll = viewModel::selectAllVisible,
                onClearSelection = viewModel::clearSelection,
                onAddToShelf = {
                    if (selectedItems.isNotEmpty()) shelfPickerTargets = selectedItems
                },
                onDeleteSelected = {
                    if (selectedItems.isNotEmpty()) viewModel.removeSelected()
                },
            )
        }
    }

    shelfPickerTargets?.let { targets ->
        ShelfPickerBottomSheet(
            shelves = uiState.shelves,
            itemCount = targets.size,
            onSelectShelf = { shelfId -> viewModel.addEntriesToShelf(shelfId, targets) },
            onCreateAndAdd = { name -> viewModel.createShelfAndAddEntries(name, targets) },
            onDismiss = { shelfPickerTargets = null },
        )
    }

    if (showClearConfirm) {
        val targetSite = uiState.siteFilter
        val siteLabel = targetSite?.let { sid ->
            uiState.siteOptions.firstOrNull { it.siteId == sid }?.label ?: sid.value
        }
        LibraryConfirmBottomSheet(
            title = if (siteLabel != null) "清空「$siteLabel」历史记录？" else "清空全部历史记录？",
            message = "观看与阅读进度记录将被永久清除，片单与收藏不受影响。",
            confirmLabel = "确认清空",
            onConfirm = { viewModel.clearHistory(targetSite) },
            onDismiss = { showClearConfirm = false },
        )
    }
}
