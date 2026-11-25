package com.raven.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.designsystem.component.RavenCompactMediaRow
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

enum class FavoriteKindFilter(val label: String, val kind: FavoriteKind?) {
    ALL("全部", null),
    VIDEO("视频", FavoriteKind.VIDEO),
    COMIC("漫画", FavoriteKind.COMIC),
    PHOTO("写真", FavoriteKind.PHOTO),
    TAG("标签", FavoriteKind.TAG),
    AUTHOR("作者", FavoriteKind.AUTHOR),
    CHARACTER("角色", FavoriteKind.CHARACTER),
}

data class FavoritesUiState(
    val searchQuery: String = "",
    val kindFilter: FavoriteKindFilter = FavoriteKindFilter.ALL,
    val siteFilter: SiteId? = null,
    val totalCount: Int = 0,
    val items: List<FavoriteItem> = emptyList(),
    val siteOptions: List<SiteFilterOption> = emptyList(),
    val siteLabels: Map<SiteId, String> = emptyMap(),
    val shelves: List<ShelfWithSummary> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedKeys: Set<String> = emptySet(),
    val message: String? = null,
)

/**
 * Wrapper passed through Koin `parametersOf(...)` to avoid `@JvmInline value class` boxing issues.
 */
data class FavoritesParams(
    val initialSiteId: SiteId? = null,
)

private data class FavoritesControls(
    val searchQuery: String = "",
    val kindFilter: FavoriteKindFilter = FavoriteKindFilter.ALL,
    val siteFilter: SiteId? = null,
    val selectionMode: Boolean = false,
    val selectedKeys: Set<String> = emptySet(),
    val message: String? = null,
)

class FavoritesViewModel(
    private val favoriteRepository: FavoriteRepository,
    private val shelfRepository: ShelfRepository,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
    initialSiteId: SiteId? = null,
) : ViewModel() {

    private val controls = MutableStateFlow(FavoritesControls(siteFilter = initialSiteId))

    val uiState: StateFlow<FavoritesUiState> = combine(
        favoriteRepository.observeAll(),
        shelfRepository.observeShelvesWithSummary(),
        controls,
    ) { allFavorites, shelves, ctrl ->
        val query = ctrl.searchQuery.trim().lowercase()
        val filtered = allFavorites.filter { fav ->
            val siteLabel = siteRegistry.displayNameFor(fav.siteId)
            val matchesQuery = query.isEmpty() ||
                fav.title.lowercase().contains(query) ||
                fav.targetId.lowercase().contains(query) ||
                siteLabel.lowercase().contains(query) ||
                fav.siteId.value.lowercase().contains(query)
            val matchesKind = ctrl.kindFilter.kind == null || fav.kind == ctrl.kindFilter.kind
            val matchesSite = ctrl.siteFilter == null || fav.siteId == ctrl.siteFilter
            matchesQuery && matchesKind && matchesSite
        }
        val validKeys = filtered.mapTo(mutableSetOf()) { it.compositeKey }
        FavoritesUiState(
            searchQuery = ctrl.searchQuery,
            kindFilter = ctrl.kindFilter,
            siteFilter = ctrl.siteFilter,
            totalCount = allFavorites.size,
            items = filtered,
            siteOptions = siteRegistry.buildSiteFilterOptions(allFavorites.map { it.siteId }),
            siteLabels = allFavorites.associate { it.siteId to siteRegistry.displayNameFor(it.siteId) },
            shelves = shelves,
            selectionMode = ctrl.selectionMode,
            selectedKeys = ctrl.selectedKeys.intersect(validKeys),
            message = ctrl.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = FavoritesUiState(),
    )

    fun pluginFor(siteId: SiteId): SitePlugin? = siteRegistry.get(siteId)

    fun updateSearchQuery(query: String) {
        controls.update { it.copy(searchQuery = query) }
    }

    fun selectKindFilter(filter: FavoriteKindFilter) {
        controls.update { it.copy(kindFilter = filter) }
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

    fun toggleSelectItem(item: FavoriteItem) {
        controls.update { current ->
            val next = if (item.compositeKey in current.selectedKeys) {
                current.selectedKeys - item.compositeKey
            } else {
                current.selectedKeys + item.compositeKey
            }
            current.copy(selectionMode = true, selectedKeys = next)
        }
    }

    fun selectAllVisible() {
        val visibleKeys = uiState.value.items.mapTo(linkedSetOf()) { it.compositeKey }
        controls.update { it.copy(selectionMode = true, selectedKeys = visibleKeys) }
    }

    fun clearSelection() {
        controls.update { it.copy(selectionMode = false, selectedKeys = emptySet()) }
    }

    fun removeFavorite(item: FavoriteItem) {
        viewModelScope.launch(dispatchers.io) {
            favoriteRepository.remove(item.siteId, item.kind, item.targetId)
            controls.update { it.copy(message = "已取消收藏") }
        }
    }

    fun removeSelected() {
        val snapshot = uiState.value
        val targets = snapshot.items.filter { it.compositeKey in snapshot.selectedKeys }
        if (targets.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            for (fav in targets) {
                favoriteRepository.remove(fav.siteId, fav.kind, fav.targetId)
            }
            controls.update {
                it.copy(
                    selectionMode = false,
                    selectedKeys = emptySet(),
                    message = "已移除 ${targets.size} 项收藏",
                )
            }
        }
    }

    fun clearSiteFavorites(siteId: SiteId) {
        viewModelScope.launch(dispatchers.io) {
            favoriteRepository.clearBySite(siteId)
            controls.update {
                it.copy(
                    selectionMode = false,
                    selectedKeys = emptySet(),
                    message = "已清空 ${siteRegistry.displayNameFor(siteId)} 的收藏",
                )
            }
        }
    }

    fun addFavoritesToShelf(
        shelfId: String,
        items: List<FavoriteItem>,
    ) {
        val shelfItems = items.mapNotNull { it.toShelfItemOrNull(shelfId) }
        if (shelfItems.isEmpty()) {
            controls.update { it.copy(message = "仅支持将视频、漫画、写真作品加入片单") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            val added = shelfRepository.addItems(shelfId, shelfItems)
            controls.update {
                it.copy(
                    selectionMode = false,
                    selectedKeys = emptySet(),
                    message = if (added > 0) "已将 $added 项作品加入片单" else "所选作品已在目标片单中",
                )
            }
        }
    }

    fun createShelfAndAddFavorites(
        shelfName: String,
        items: List<FavoriteItem>,
    ) {
        val shelfItems = items.mapNotNull { it.toShelfItemOrNull() }
        if (shelfItems.isEmpty()) {
            controls.update { it.copy(message = "仅支持将视频、漫画、写真作品加入片单") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val created = shelfRepository.createShelf(name = shelfName)
                val added = shelfRepository.addItems(created.id, shelfItems)
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
fun FavoritesScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    siteId: SiteId? = null,
    embedded: Boolean = false,
    viewModel: FavoritesViewModel = koinViewModel(
        key = "favorites_${siteId?.value.orEmpty()}",
        parameters = { parametersOf(FavoritesParams(siteId)) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current

    var shelfPickerTargets by remember { mutableStateOf<List<FavoriteItem>?>(null) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("favorites_screen"),
    ) {
        if (!embedded) RavenTopBar(
            title = "我的收藏",
            subtitle = "共 ${uiState.items.size} / ${uiState.totalCount} 项",
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
                    modifier = Modifier.testTag("favorites_multiselect_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckBox,
                        contentDescription = "批量管理",
                        tint = if (uiState.selectionMode) colors.crimson else colors.inkPrimary,
                    )
                }
                if (uiState.siteFilter != null && uiState.items.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.testTag("favorites_clear_site_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "清空当前站点收藏",
                            tint = colors.inkSecondary,
                        )
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!uiState.message.isNullOrBlank()) {
                item(key = "fav_msg") {
                    LibraryFeedbackBanner(
                        message = uiState.message.orEmpty(),
                        tone = RavenStatusTone.ACCENT,
                        onDismiss = viewModel::clearMessage,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "fav_search") {
                RavenSearchField(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    placeholder = "搜索收藏的作品、标签或作者…",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item(key = "fav_kind_chips") {
                RavenFilterChipRow(
                    items = FavoriteKindFilter.entries,
                    selectedItem = uiState.kindFilter,
                    labelProvider = { it.label },
                    onItemSelected = viewModel::selectKindFilter,
                    keyProvider = { it.name },
                )
            }

            if (uiState.siteOptions.isNotEmpty()) {
                item(key = "fav_site_chips") {
                    LibrarySiteFilterChips(
                        options = uiState.siteOptions,
                        selectedSiteId = uiState.siteFilter,
                        onSelectSite = viewModel::selectSiteFilter,
                    )
                }
            }

            if (uiState.items.isEmpty()) {
                item(key = "fav_empty") {
                    RavenEmptyState(
                        title = if (uiState.totalCount == 0) "暂无收藏内容" else "没有匹配的收藏",
                        message = if (uiState.totalCount == 0) {
                            "收藏喜欢的作品、标签或作者后，可在这里跨站统一检索并批量整理进片单。"
                        } else {
                            "尝试清除关键词或切换类型与站点筛选。"
                        },
                        icon = Icons.Outlined.FavoriteBorder,
                    )
                }
            } else {
                items(
                    items = uiState.items,
                    key = { it.compositeKey },
                ) { fav ->
                    val selected = fav.compositeKey in uiState.selectedKeys
                    val siteLabel = uiState.siteLabels[fav.siteId] ?: fav.siteId.value
                    val mediaItem = fav.toMediaItemOrNull()

                    if (mediaItem != null) {
                        RavenCompactMediaRow(
                            item = mediaItem,
                            onClick = {
                                if (uiState.selectionMode) {
                                    viewModel.toggleSelectItem(fav)
                                } else {
                                    navigator.openFavorite(fav, viewModel.pluginFor(fav.siteId))
                                }
                            },
                            onLongClick = { viewModel.toggleSelectItem(fav) },
                            subtitle = "$siteLabel · ${fav.kind.displayLabel()} · ${formatRelativeTime(fav.addedAt)}",
                            trailingContent = {
                                if (uiState.selectionMode) {
                                    SelectCheckBadge(selected = selected)
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { shelfPickerTargets = listOf(fav) },
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
                                            onClick = { viewModel.removeFavorite(fav) },
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.DeleteOutline,
                                                contentDescription = "取消收藏",
                                                tint = colors.inkTertiary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        // Tag / Author / Character favorite row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clip(RavenShapes.CardShape)
                                .background(colors.surface1)
                                .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                                .ravenSpringPress()
                                .clickable(role = Role.Button) {
                                    if (uiState.selectionMode) {
                                        viewModel.toggleSelectItem(fav)
                                    } else {
                                        navigator.openFavorite(fav, viewModel.pluginFor(fav.siteId))
                                    }
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RavenShapes.ControlShape)
                                    .background(colors.surface2),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (fav.kind == FavoriteKind.AUTHOR) {
                                        Icons.Outlined.Person
                                    } else {
                                        Icons.Outlined.Tag
                                    },
                                    contentDescription = null,
                                    tint = colors.crimson,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = fav.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RavenStatusPill(text = fav.kind.displayLabel())
                                    Text(
                                        text = "$siteLabel · ${formatRelativeTime(fav.addedAt)}",
                                        style = MaterialTheme.typography.bodySmall.tabular(),
                                        color = colors.inkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (uiState.selectionMode) {
                                SelectCheckBadge(selected = selected)
                            } else {
                                IconButton(
                                    onClick = { viewModel.removeFavorite(fav) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "取消收藏",
                                        tint = colors.inkTertiary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (uiState.selectionMode) {
            val selectedItems = uiState.items.filter { it.compositeKey in uiState.selectedKeys }
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
        val mediaTargets = targets.filter { it.toShelfItemOrNull() != null }
        ShelfPickerBottomSheet(
            shelves = uiState.shelves,
            itemCount = mediaTargets.size,
            onSelectShelf = { shelfId -> viewModel.addFavoritesToShelf(shelfId, mediaTargets) },
            onCreateAndAdd = { name -> viewModel.createShelfAndAddFavorites(name, mediaTargets) },
            onDismiss = { shelfPickerTargets = null },
        )
    }

    if (showClearConfirm && uiState.siteFilter != null) {
        val siteId = uiState.siteFilter!!
        val label = uiState.siteLabels[siteId] ?: siteId.value
        LibraryConfirmBottomSheet(
            title = "清空「$label」的全部收藏？",
            message = "该站点的所有收藏条目将被移除，此操作不可撤销。",
            confirmLabel = "确认清空",
            onConfirm = { viewModel.clearSiteFavorites(siteId) },
            onDismiss = { showClearConfirm = false },
        )
    }
}
