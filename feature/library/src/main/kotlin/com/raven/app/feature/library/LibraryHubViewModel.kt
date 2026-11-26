package com.raven.app.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.domain.SmartShelfEvaluator
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.model.SmartShelfEntry
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.media.storage.LocalBook
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryHubTab(val label: String) {
    SHELVES("我的片单"),
    SMART_SHELVES("智能片单"),
    LOCAL("本地内容"),
}

data class SmartShelfSummary(
    val smartShelf: SmartShelf,
    val matchCount: Int,
    val previewCoverUrls: List<String>,
    val siteLabel: String?,
)

data class ResolvedLinkPreview(
    val siteId: SiteId,
    val siteLabel: String,
    val kindLabel: String,
    val link: ResolvedLink,
)

data class LocalImportState(
    val isImporting: Boolean = false,
    val progressMessage: String? = null,
    val lastMessage: String? = null,
    val isError: Boolean = false,
)

data class LibraryHubUiState(
    val selectedTab: LibraryHubTab = LibraryHubTab.SHELVES,
    val historyCount: Int = 0,
    val favoriteCount: Int = 0,
    val completedDownloadCount: Int = 0,
    val completedDownloadBytes: Long = 0L,
    val activeDownloadCount: Int = 0,
    val unreadInboxCount: Int = 0,
    val imageFavoriteCount: Int = 0,
    val subscriptionCount: Int = 0,
    val savedSearchCount: Int = 0,
    val shelves: List<ShelfWithSummary> = emptyList(),
    val smartShelves: List<SmartShelfSummary> = emptyList(),
    val localBooks: List<LocalBook> = emptyList(),
    val localBookItems: Map<String, MediaItem> = emptyMap(),
    val allSiteOptions: List<SiteFilterOption> = emptyList(),
    val importState: LocalImportState = LocalImportState(),
    val message: String? = null,
)

private data class LibraryMetricCounts(
    val historyCount: Int,
    val favoriteCount: Int,
    val completedDownloadCount: Int,
    val completedDownloadBytes: Long,
    val activeDownloadCount: Int,
    val unreadInboxCount: Int,
)

private data class LibrarySecondaryCounts(
    val imageFavoriteCount: Int,
    val subscriptionCount: Int,
    val savedSearchCount: Int,
)

private data class LibraryShelfData(
    val shelves: List<ShelfWithSummary>,
    val smartShelves: List<SmartShelf>,
    val catalog: List<SmartShelfEntry>,
)

class LibraryHubViewModel(
    private val shelfRepository: ShelfRepository,
    private val historyRepository: HistoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val downloadGateway: LibraryDownloadGateway,
    private val localLibraryImporter: LocalLibraryImporter,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val selectedTab = MutableStateFlow(LibraryHubTab.SHELVES)
    private val importState = MutableStateFlow(LocalImportState())
    private val transientMessage = MutableStateFlow<String?>(null)

    private val metricCountsFlow = combine(
        historyRepository.observeAllWatchProgress(),
        historyRepository.observeAllReadingProgress(),
        favoriteRepository.observeAll(),
        downloadGateway.downloads,
        subscriptionRepository.observeUnreadCount(),
    ) { watches, readings, favorites, downloads, unreadCount ->
        val completedDownloads = downloads.filter { it.status == DownloadStatus.COMPLETED }
        LibraryMetricCounts(
            historyCount = watches.size + readings.size,
            favoriteCount = favorites.size,
            completedDownloadCount = completedDownloads.size,
            completedDownloadBytes = completedDownloads.sumOf { item ->
                item.downloadedBytes.takeIf { it > 0L } ?: item.totalBytes ?: 0L
            },
            activeDownloadCount = downloads.count { it.status.isActiveTransfer() },
            unreadInboxCount = unreadCount,
        )
    }

    private val secondaryCountsFlow = combine(
        bookmarkRepository.observeImageFavorites(),
        subscriptionRepository.observeAllSubscriptions(),
        subscriptionRepository.observeSavedSearches(),
    ) { imageFavorites, subscriptions, savedSearches ->
        LibrarySecondaryCounts(
            imageFavoriteCount = imageFavorites.size,
            subscriptionCount = subscriptions.size,
            savedSearchCount = savedSearches.size,
        )
    }

    private val shelfDataFlow = combine(
        shelfRepository.observeShelvesWithSummary(),
        shelfRepository.observeSmartShelves(),
        shelfRepository.observeCatalog(),
    ) { shelves, smartShelves, catalog ->
        LibraryShelfData(
            shelves = shelves,
            smartShelves = smartShelves,
            catalog = catalog,
        )
    }

    private val allSiteOptions: List<SiteFilterOption> = siteRegistry.buildSiteFilterOptions(
        presentSiteIds = emptyList(),
        includeAllRegistered = true,
    )

    private val controlsFlow = combine(
        selectedTab,
        importState,
        transientMessage,
        localLibraryImporter.books,
    ) { tab, importing, message, books ->
        HubControlsSnapshot(
            tab = tab,
            importState = importing,
            message = message,
            localBooks = books,
        )
    }

    val uiState: StateFlow<LibraryHubUiState> = combine(
        metricCountsFlow,
        secondaryCountsFlow,
        shelfDataFlow,
        controlsFlow,
    ) { metrics, secondary, shelfData, controls ->
        val now = clockMs()
        val sortedShelves = shelfData.shelves.sortedWith(
            compareByDescending<ShelfWithSummary> { it.shelf.id == Shelf.WATCH_LATER_ID }
                .thenByDescending { it.shelf.isPinned }
                .thenByDescending { it.shelf.modifiedAt },
        )
        val smartSummaries = shelfData.smartShelves
            .sortedWith(
                compareByDescending<SmartShelf> { it.isPinned }
                    .thenByDescending { it.createdAt },
            )
            .map { smartShelf ->
                val entries = SmartShelfEvaluator.evaluate(smartShelf, shelfData.catalog, now = now)
                SmartShelfSummary(
                    smartShelf = smartShelf,
                    matchCount = entries.size,
                    previewCoverUrls = entries.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.take(3),
                    siteLabel = smartShelf.siteIdFilter?.let { siteRegistry.displayNameFor(it) },
                )
            }

        val localItemMap = controls.localBooks.associate { book ->
            book.id to localLibraryImporter.toMediaItem(book)
        }

        LibraryHubUiState(
            selectedTab = controls.tab,
            historyCount = metrics.historyCount,
            favoriteCount = metrics.favoriteCount,
            completedDownloadCount = metrics.completedDownloadCount,
            completedDownloadBytes = metrics.completedDownloadBytes,
            activeDownloadCount = metrics.activeDownloadCount,
            unreadInboxCount = metrics.unreadInboxCount,
            imageFavoriteCount = secondary.imageFavoriteCount,
            subscriptionCount = secondary.subscriptionCount,
            savedSearchCount = secondary.savedSearchCount,
            shelves = sortedShelves,
            smartShelves = smartSummaries,
            localBooks = controls.localBooks,
            localBookItems = localItemMap,
            allSiteOptions = allSiteOptions,
            importState = controls.importState,
            message = controls.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LibraryHubUiState(allSiteOptions = allSiteOptions),
    )

    fun selectTab(tab: LibraryHubTab) {
        selectedTab.value = tab
    }

    fun clearMessage() {
        transientMessage.value = null
    }

    fun clearImportStatus() {
        importState.update { it.copy(lastMessage = null, isError = false) }
    }

    // ---- Custom Shelf CRUD ----

    fun createShelf(
        name: String,
        note: String = "",
        isPinned: Boolean = false,
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            transientMessage.value = "请输入片单名称"
            return
        }
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                shelfRepository.createShelf(name = cleanName, note = note, isPinned = isPinned)
            }.onSuccess { created ->
                transientMessage.value = "已创建片单「${created.name}」"
            }.onFailure { error ->
                transientMessage.value = error.message ?: "创建片单失败"
            }
        }
    }

    fun updateShelf(
        shelfId: String,
        name: String,
        note: String,
        isPinned: Boolean,
    ) {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val renameArg = if (shelfId == Shelf.WATCH_LATER_ID) null else name
                shelfRepository.updateShelf(
                    shelfId = shelfId,
                    name = renameArg,
                    note = note,
                    isPinned = isPinned,
                )
            }.onSuccess { updated ->
                transientMessage.value = "已更新片单「${updated.name}」"
            }.onFailure { error ->
                transientMessage.value = error.message ?: "更新片单失败"
            }
        }
    }

    fun toggleShelfPinned(shelf: Shelf) {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                shelfRepository.updateShelf(shelfId = shelf.id, isPinned = !shelf.isPinned)
            }.onFailure { error ->
                transientMessage.value = error.message ?: "更新置顶状态失败"
            }
        }
    }

    fun deleteShelf(shelfId: String) {
        if (shelfId == Shelf.WATCH_LATER_ID) {
            transientMessage.value = "内置「稍后看」片单不可删除"
            return
        }
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                shelfRepository.deleteShelf(shelfId)
            }.onSuccess {
                transientMessage.value = "已删除片单"
            }.onFailure { error ->
                transientMessage.value = error.message ?: "删除片单失败"
            }
        }
    }

    // ---- Smart Shelf CRUD ----

    fun saveSmartShelf(
        id: String = "",
        name: String,
        rule: SmartShelfRule,
        mediaKindFilter: MediaKind?,
        siteIdFilter: SiteId?,
        isPinned: Boolean,
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            transientMessage.value = "请输入智能片单名称"
            return
        }
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                shelfRepository.saveSmartShelf(
                    SmartShelf(
                        id = id,
                        name = cleanName,
                        rule = rule,
                        mediaKindFilter = mediaKindFilter,
                        siteIdFilter = siteIdFilter,
                        isPinned = isPinned,
                    ),
                )
            }.onSuccess { saved ->
                transientMessage.value = if (id.isBlank()) {
                    "已创建智能片单「${saved.name}」"
                } else {
                    "已更新智能片单「${saved.name}」"
                }
            }.onFailure { error ->
                transientMessage.value = error.message ?: "保存智能片单失败"
            }
        }
    }

    fun deleteSmartShelf(id: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                shelfRepository.deleteSmartShelf(id)
            }.onSuccess {
                transientMessage.value = "已删除智能片单"
            }.onFailure { error ->
                transientMessage.value = error.message ?: "删除智能片单失败"
            }
        }
    }

    // ---- Open From Link ----

    fun resolveLinkText(rawText: String): ResolvedLinkPreview? {
        val resolvedPair = siteRegistry.resolveLinkFromText(rawText) ?: return null
        val (plugin, link) = resolvedPair
        val kindLabel = when (link) {
            is ResolvedLink.Video -> "视频"
            is ResolvedLink.Gallery -> if (link.pageIndex != null) "图集 · 第 ${link.pageIndex} 页" else "漫画 / 图集"
            is ResolvedLink.Feed -> "站点列表 / 标签"
            is ResolvedLink.Directory -> "站点目录"
        }
        return ResolvedLinkPreview(
            siteId = plugin.id,
            siteLabel = plugin.descriptor.displayName,
            kindLabel = kindLabel,
            link = link,
        )
    }

    // ---- Local SAF Imports ----

    fun importArchive(uri: Uri) = runLocalImport(startLabel = "正在导入 CBZ / ZIP 压缩包…") {
        localLibraryImporter.importArchive(uri) { progress ->
            importState.update { it.copy(progressMessage = progress) }
        }
    }

    fun importDirectory(treeUri: Uri) = runLocalImport(startLabel = "正在扫描图片目录…") {
        localLibraryImporter.importDirectory(treeUri) { progress ->
            importState.update { it.copy(progressMessage = progress) }
        }
    }

    fun importVideo(uri: Uri) = runLocalImport(startLabel = "正在导入本地视频…") {
        localLibraryImporter.importVideo(uri) { progress ->
            importState.update { it.copy(progressMessage = progress) }
        }
    }

    fun removeLocalBook(bookId: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                deleteLocalBookCompletely(
                    bookId = bookId,
                    localLibraryImporter = localLibraryImporter,
                    shelfRepository = shelfRepository,
                    historyRepository = historyRepository,
                )
            }.onSuccess {
                transientMessage.value = "已移除本地内容，并清理关联片单与历史记录"
            }.onFailure { error ->
                transientMessage.value = error.message ?: "移除本地内容失败"
            }
        }
    }

    fun refreshLocalBooks() {
        viewModelScope.launch(dispatchers.io) {
            localLibraryImporter.refreshBooksSnapshot()
        }
    }

    private fun runLocalImport(
        startLabel: String,
        action: suspend () -> MediaItem,
    ) {
        if (importState.value.isImporting) return
        viewModelScope.launch(dispatchers.io) {
            importState.value = LocalImportState(
                isImporting = true,
                progressMessage = startLabel,
            )
            try {
                val imported = action()
                importState.value = LocalImportState(
                    isImporting = false,
                    lastMessage = "已导入「${imported.title}」",
                    isError = false,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                importState.value = LocalImportState(
                    isImporting = false,
                    lastMessage = error.message ?: "导入失败，请确认文件完整且空间充足",
                    isError = true,
                )
            }
        }
    }

    private data class HubControlsSnapshot(
        val tab: LibraryHubTab,
        val importState: LocalImportState,
        val message: String?,
        val localBooks: List<LocalBook>,
    )
}
