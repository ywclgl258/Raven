package com.raven.app.feature.browse

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenLoadingSkeleton
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.model.DirectoryEntry
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data class DirectoryDisplayEntry(
    val entry: DirectoryEntry,
    val displayTitle: String,
    val secondaryCaption: String?,
)

data class SiteDirectoryUiState(
    val site: SiteDescriptor? = null,
    val activeSection: String = "",
    val sections: List<SiteSectionSpec> = emptyList(),
    val filterQuery: String = "",
    val entries: List<DirectoryDisplayEntry> = emptyList(),
    val totalLoadedCount: Int = 0,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val authGateRequired: Boolean = false,
    val errorMessage: String? = null,
    val paginationErrorMessage: String? = null,
)

private data class DirectoryLoadState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val authGateRequired: Boolean = false,
    val errorMessage: String? = null,
    val paginationErrorMessage: String? = null,
    val rawEntries: List<DirectoryEntry> = emptyList(),
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
)

class SiteDirectoryViewModel(
    val route: RavenRoute.SiteDirectory,
    private val siteRegistry: SiteRegistry,
    private val preferencesRepository: PreferencesRepository,
    private val dispatchers: AppDispatchers,
    private val tagDictionary: TagDictionary = TagDictionary.default,
) : ViewModel() {

    val siteId: SiteId? = runCatching { SiteId.of(route.siteId) }.getOrNull()
    val plugin: SitePlugin? = siteId?.let(siteRegistry::get)

    private val availableSections: List<SiteSectionSpec> = buildList {
        plugin?.directorySource?.directorySections()?.let(::addAll)
        if (isEmpty()) {
            plugin?.descriptor?.sections?.filter { it.isDirectory }?.let(::addAll)
        }
    }

    private val activeSection = MutableStateFlow(
        route.section.ifBlank { availableSections.firstOrNull()?.id.orEmpty() },
    )
    private val filterQuery = MutableStateFlow("")
    private val loadState = MutableStateFlow(DirectoryLoadState())

    private var loadJob: Job? = null
    private var paginateJob: Job? = null

    val uiState: StateFlow<SiteDirectoryUiState> = combine(
        preferencesRepository.settings,
        activeSection,
        filterQuery,
        loadState,
    ) { settings, section, query, state ->
        val translateTags = settings.translateTags
        val needle = query.trim().lowercase()

        val mapped = state.rawEntries.map { entry ->
            val translated = if (translateTags && (entry.kind == SearchScope.TAG || entry.kind == SearchScope.ALL)) {
                tagDictionary.translate(entry.id) ?: tagDictionary.translate(entry.title)
            } else {
                null
            }
            val displayTitle = translated?.takeIf { !it.equals(entry.title, ignoreCase = true) } ?: entry.title
            val caption = buildList {
                if (translated != null && !translated.equals(entry.title, ignoreCase = true)) {
                    add(entry.title)
                }
                if (entry.subtitle.isNotBlank()) add(entry.subtitle)
            }.joinToString(" · ").ifBlank { null }

            DirectoryDisplayEntry(
                entry = entry,
                displayTitle = displayTitle,
                secondaryCaption = caption,
            )
        }

        val filtered = if (needle.isEmpty()) {
            mapped
        } else {
            mapped.filter { item ->
                item.displayTitle.lowercase().contains(needle) ||
                    item.entry.title.lowercase().contains(needle) ||
                    item.entry.id.lowercase().contains(needle) ||
                    (item.secondaryCaption?.lowercase()?.contains(needle) == true)
            }
        }

        SiteDirectoryUiState(
            site = plugin?.descriptor,
            activeSection = section,
            sections = availableSections,
            filterQuery = query,
            entries = filtered,
            totalLoadedCount = state.rawEntries.size,
            isLoading = state.isLoading,
            isRefreshing = state.isRefreshing,
            isLoadingMore = state.isLoadingMore,
            hasMore = state.hasMore && needle.isEmpty(),
            authGateRequired = state.authGateRequired,
            errorMessage = state.errorMessage,
            paginationErrorMessage = state.paginationErrorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SiteDirectoryUiState(
            site = plugin?.descriptor,
            activeSection = activeSection.value,
            sections = availableSections,
            isLoading = true,
        ),
    )

    init {
        viewModelScope.launch {
            activeSection.collect { section ->
                triggerLoad(section = section, remoteQuery = null, isRefresh = false)
            }
        }
    }

    fun selectSection(sectionId: String) {
        if (sectionId == activeSection.value) return
        filterQuery.value = ""
        activeSection.value = sectionId
    }

    fun updateFilterQuery(query: String) {
        filterQuery.value = query
    }

    fun submitRemoteFilter() {
        val q = filterQuery.value.trim().takeIf { it.isNotEmpty() }
        triggerLoad(section = activeSection.value, remoteQuery = q, isRefresh = false)
    }

    fun refresh() {
        val q = filterQuery.value.trim().takeIf { it.isNotEmpty() }
        triggerLoad(
            section = activeSection.value,
            remoteQuery = q,
            isRefresh = loadState.value.rawEntries.isNotEmpty(),
        )
    }

    fun loadNextPage() {
        val snap = loadState.value
        if (snap.isLoading || snap.isLoadingMore || snap.authGateRequired || !snap.hasMore) return
        val dirSource = plugin?.directorySource ?: return
        val section = activeSection.value

        paginateJob?.cancel()
        paginateJob = viewModelScope.launch {
            loadState.update { it.copy(isLoadingMore = true, paginationErrorMessage = null) }
            val nextPage = snap.currentPage + 1
            try {
                val incoming = withContext(dispatchers.io) {
                    dirSource.loadDirectory(
                        section = section,
                        page = nextPage,
                        query = filterQuery.value.trim().takeIf { it.isNotEmpty() },
                    )
                }
                loadState.update { state ->
                    val seen = state.rawEntries.mapTo(HashSet()) { "${it.id}:${it.url}" }
                    val appended = incoming.filter { seen.add("${it.id}:${it.url}") }
                    state.copy(
                        isLoadingMore = false,
                        paginationErrorMessage = null,
                        rawEntries = if (appended.isEmpty()) state.rawEntries else state.rawEntries + appended,
                        currentPage = nextPage,
                        hasMore = appended.isNotEmpty(),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                loadState.update {
                    it.copy(
                        isLoadingMore = false,
                        paginationErrorMessage = error.toBrowseErrorMessage(),
                    )
                }
            }
        }
    }

    private fun triggerLoad(section: String, remoteQuery: String?, isRefresh: Boolean) {
        loadJob?.cancel()
        paginateJob?.cancel()
        loadJob = viewModelScope.launch {
            val targetPlugin = plugin
            val dirSource = targetPlugin?.directorySource
            if (targetPlugin == null || dirSource == null) {
                loadState.value = DirectoryLoadState(
                    isLoading = false,
                    errorMessage = "当前站点暂未提供目录索引",
                )
                return@launch
            }

            loadState.update { current ->
                if (isRefresh && current.rawEntries.isNotEmpty()) {
                    current.copy(isRefreshing = true, isLoading = false, errorMessage = null)
                } else {
                    DirectoryLoadState(isLoading = true)
                }
            }

            try {
                if (targetPlugin.authProvider.requiresAuthGate) {
                    val auth = withContext(dispatchers.io) { targetPlugin.authProvider.currentAuthState() }
                    if (!auth.isAuthenticated) {
                        loadState.value = DirectoryLoadState(
                            isLoading = false,
                            authGateRequired = true,
                        )
                        return@launch
                    }
                }

                val entries = withContext(dispatchers.io) {
                    dirSource.loadDirectory(section = section, page = 1, query = remoteQuery)
                }
                val unique = entries.distinctBy { "${it.id}:${it.url}" }
                loadState.value = DirectoryLoadState(
                    isLoading = false,
                    isRefreshing = false,
                    authGateRequired = false,
                    rawEntries = unique,
                    currentPage = 1,
                    hasMore = unique.size >= 12,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                val isAuth = error is SiteException.AuthRequired
                loadState.update { current ->
                    if (isRefresh && current.rawEntries.isNotEmpty() && !isAuth) {
                        current.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = error.toBrowseErrorMessage(),
                        )
                    } else {
                        DirectoryLoadState(
                            isLoading = false,
                            isRefreshing = false,
                            authGateRequired = isAuth,
                            errorMessage = error.toBrowseErrorMessage(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SiteDirectoryScreen(
    route: RavenRoute.SiteDirectory,
    navigator: BrowseNavigator,
    modifier: Modifier = Modifier,
    viewModel: SiteDirectoryViewModel = koinViewModel(
        key = "site_directory_${route.siteId}_${route.section}",
        parameters = { parametersOf(route) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SiteDirectoryScreenContent(
        uiState = uiState,
        plugin = viewModel.plugin,
        navigator = navigator,
        onSelectSection = viewModel::selectSection,
        onFilterQueryChange = viewModel::updateFilterQuery,
        onSubmitRemoteFilter = viewModel::submitRemoteFilter,
        onRefresh = viewModel::refresh,
        onLoadNextPage = viewModel::loadNextPage,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteDirectoryScreenContent(
    uiState: SiteDirectoryUiState,
    plugin: SitePlugin?,
    navigator: BrowseNavigator,
    onSelectSection: (String) -> Unit,
    onFilterQueryChange: (String) -> Unit,
    onSubmitRemoteFilter: () -> Unit,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(listState, uiState.hasMore, uiState.isLoadingMore, uiState.entries.size) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 4
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (uiState.hasMore && !uiState.isLoadingMore) onLoadNextPage()
            }
    }

    val activeSectionTitle = uiState.sections
        .firstOrNull { it.id == uiState.activeSection }
        ?.title
        ?: "目录索引"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("browse_site_directory_screen"),
    ) {
        RavenTopBar(
            title = activeSectionTitle,
            subtitle = uiState.site?.displayName,
            showBottomHairline = false,
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("site_directory_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
        )

        if (uiState.sections.size > 1) {
            RavenFilterChipRow(
                items = uiState.sections,
                selectedItem = uiState.sections.firstOrNull { it.id == uiState.activeSection },
                labelProvider = { it.title },
                onItemSelected = { onSelectSection(it.id) },
                keyProvider = { it.id },
                contentPadding = PaddingValues(horizontal = 16.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            RavenSearchField(
                query = uiState.filterQuery,
                onQueryChange = onFilterQueryChange,
                onSearch = { onSubmitRemoteFilter() },
                placeholder = "在已加载的目录中筛选（支持中文标签）…",
            )
        }

        HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = uiState.isRefreshing,
                    containerColor = colors.surface2,
                    color = colors.crimson,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val site = uiState.site
            when {
                site == null -> {
                    RavenEmptyState(
                        title = "站点不可用",
                        message = "未找到对应站点插件。",
                    )
                }
                uiState.authGateRequired -> {
                    BrowseAuthGateCard(
                        site = site,
                        detailMessage = uiState.errorMessage,
                        onOpenAccount = { navigator.openSiteAccount(site.id) },
                        onRefreshStatus = onRefresh,
                    )
                }
                uiState.isLoading && uiState.entries.isEmpty() -> {
                    RavenLoadingSkeleton(rowCount = 6)
                }
                uiState.errorMessage != null && uiState.entries.isEmpty() -> {
                    RavenErrorState(
                        message = uiState.errorMessage,
                        onRetry = onRefresh,
                    )
                }
                uiState.entries.isEmpty() -> {
                    RavenEmptyState(
                        title = if (uiState.filterQuery.isNotBlank()) "未筛选到匹配项" else "目录为空",
                        message = if (uiState.filterQuery.isNotBlank()) {
                            "可清除筛选关键词或点击键盘搜索尝试远程查询。"
                        } else {
                            "该分类目录下暂无条目。"
                        },
                        icon = Icons.Outlined.FolderOpen,
                        actionLabel = if (uiState.filterQuery.isNotBlank()) "清除筛选" else "重新加载",
                        onActionClick = if (uiState.filterQuery.isNotBlank()) {
                            { onFilterQueryChange("") }
                        } else {
                            onRefresh
                        },
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("site_directory_list"),
                    ) {
                        items(
                            items = uiState.entries,
                            key = { "${it.entry.id}_${it.entry.url}" },
                        ) { item ->
                            DirectoryEntryCard(
                                item = item,
                                onClick = { navigator.openDirectoryEntry(plugin, item.entry) },
                            )
                        }

                        item(key = "directory_pagination_footer") {
                            BrowsePaginationFooter(
                                isLoadingMore = uiState.isLoadingMore,
                                errorMessage = uiState.paginationErrorMessage,
                                hasMore = uiState.hasMore,
                                onRetry = onLoadNextPage,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryEntryCard(
    item: DirectoryDisplayEntry,
    onClick: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val entry = item.entry
    val countLabel = entry.itemCount?.let { RavenFormatters.formatCount(it.toLong()) }?.takeIf { it.isNotEmpty() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("directory_entry_${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (entry.coverUrl.isNotBlank()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            ) {
                RavenImage(
                    url = entry.coverUrl,
                    siteId = entry.siteId,
                    contentDescription = item.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.secondaryCaption.isNullOrBlank()) {
                Text(
                    text = item.secondaryCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (countLabel != null) {
            RavenStatusPill(text = countLabel)
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.inkTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}
