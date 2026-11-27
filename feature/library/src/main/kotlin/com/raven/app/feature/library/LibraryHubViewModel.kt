package com.raven.app.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.domain.LikeRecommendationEngine
import com.raven.app.core.database.domain.SmartShelfEvaluator
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.model.SmartShelfEntry
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.LikeRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.media.storage.LocalBook
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FeatureTutorialIds
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.LikedMedia
import com.raven.app.core.model.LikedTagWeight
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryHubTab(val label: String) {
    WATCH_LATER("稍后观看"),
    SMART_RECOMMENDATIONS("智能推荐"),
    LOCAL("本地内容"),
}

data class SmartShelfSummary(
    val smartShelf: SmartShelf,
    val matchCount: Int,
    val previewCoverUrls: List<String>,
    val siteLabel: String?,
)

data class SmartRecommendationCard(
    val item: MediaItem,
    val siteLabel: String,
    val reason: String,
    val matchedTags: List<String>,
    val score: Double,
)

data class WeightedTagDisplay(
    val tag: TagRef,
    val displayLabel: String,
    val normalizedWeight: Float,
    val likeCount: Int,
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
    val selectedTab: LibraryHubTab = LibraryHubTab.WATCH_LATER,
    val historyCount: Int = 0,
    val favoriteCount: Int = 0,
    val completedDownloadCount: Int = 0,
    val completedDownloadBytes: Long = 0L,
    val activeDownloadCount: Int = 0,
    val unreadInboxCount: Int = 0,
    val imageFavoriteCount: Int = 0,
    val subscriptionCount: Int = 0,
    val savedSearchCount: Int = 0,
    val watchLaterItems: List<ShelfItem> = emptyList(),
    val likedWorksCount: Int = 0,
    val likedTagWeights: List<WeightedTagDisplay> = emptyList(),
    val smartRecommendations: List<SmartRecommendationCard> = emptyList(),
    val isLoadingRecommendations: Boolean = false,
    val recommendationsError: String? = null,
    val shelves: List<ShelfWithSummary> = emptyList(),
    val smartShelves: List<SmartShelfSummary> = emptyList(),
    val localBooks: List<LocalBook> = emptyList(),
    val localBookItems: Map<String, MediaItem> = emptyMap(),
    val siteLabels: Map<SiteId, String> = emptyMap(),
    val allSiteOptions: List<SiteFilterOption> = emptyList(),
    val importState: LocalImportState = LocalImportState(),
    val showLibraryTutorial: Boolean = false,
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
    val watchLaterItems: List<ShelfItem>,
)

private data class LibraryShelfData(
    val shelves: List<ShelfWithSummary>,
    val smartShelves: List<SmartShelf>,
    val catalog: List<SmartShelfEntry>,
)

private data class RecommendationState(
    val isLoading: Boolean = false,
    val items: List<SmartRecommendationCard> = emptyList(),
    val error: String? = null,
    val lastLikesSignature: String = "",
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
    private val likeRepository: LikeRepository? = null,
    private val blockedTermsRepository: BlockedTermsRepository? = null,
    private val mediaTagCacheRepository: MediaTagCacheRepository? = null,
    private val preferencesRepository: PreferencesRepository? = null,
    private val tagDictionary: TagDictionary = TagDictionary.default,
) : ViewModel() {

    private val selectedTab = MutableStateFlow(LibraryHubTab.WATCH_LATER)
    private val importState = MutableStateFlow(LocalImportState())
    private val transientMessage = MutableStateFlow<String?>(null)
    private val recommendationState = MutableStateFlow(RecommendationState())
    private var recommendationJob: Job? = null

    private val likesFlow = likeRepository?.observeAll() ?: flowOf(emptyList())
    private val settingsFlow = preferencesRepository?.settings ?: flowOf(null)

    private val metricCountsFlow = combine(
        historyRepository.observeAllWatchProgress(),
        historyRepository.observeReadingHistory(),
        favoriteRepository.observeMediaFavorites(),
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
        shelfRepository.observeShelfItems(Shelf.WATCH_LATER_ID),
    ) { imageFavorites, subscriptions, savedSearches, watchLater ->
        LibrarySecondaryCounts(
            imageFavoriteCount = imageFavorites.size,
            subscriptionCount = subscriptions.size,
            savedSearchCount = savedSearches.size,
            watchLaterItems = watchLater.sortedByDescending { it.addedAt },
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
        importState,
        transientMessage,
        localLibraryImporter.books,
    ) { importing, message, books ->
        HubControlsSnapshot(
            importState = importing,
            message = message,
            localBooks = books,
        )
    }

    private val recommendationInputsFlow = combine(
        likesFlow,
        recommendationState,
        settingsFlow,
    ) { likes, recState, appSettings ->
        Triple(likes, recState, appSettings)
    }

    private val contentFlow = combine(
        metricCountsFlow,
        secondaryCountsFlow,
        shelfDataFlow,
        controlsFlow,
        recommendationInputsFlow,
    ) { metrics, secondary, shelfData, controls, (likes, recState, appSettings) ->
        val now = clockMs()
        val translateTags = appSettings?.translateTags ?: true
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

        val rawWeights = LikeRecommendationEngine.computeTagWeights(likes = likes, nowMs = now, limit = 16)
        val displayWeights = rawWeights.map { entry ->
            WeightedTagDisplay(
                tag = entry.tag,
                displayLabel = formatTagDisplay(entry.tag, translateTags),
                normalizedWeight = entry.normalizedWeight,
                likeCount = entry.likeCount,
            )
        }

        val localItemMap = controls.localBooks.associate { book ->
            book.id to localLibraryImporter.toMediaItem(book)
        }
        val siteLabels = buildMap {
            secondary.watchLaterItems.forEach { put(it.siteId, siteRegistry.displayNameFor(it.siteId)) }
            recState.items.forEach { put(it.item.siteId, it.siteLabel) }
        }

        LibraryHubUiState(
            historyCount = metrics.historyCount,
            favoriteCount = metrics.favoriteCount,
            completedDownloadCount = metrics.completedDownloadCount,
            completedDownloadBytes = metrics.completedDownloadBytes,
            activeDownloadCount = metrics.activeDownloadCount,
            unreadInboxCount = metrics.unreadInboxCount,
            imageFavoriteCount = secondary.imageFavoriteCount,
            subscriptionCount = secondary.subscriptionCount,
            savedSearchCount = secondary.savedSearchCount,
            watchLaterItems = secondary.watchLaterItems,
            likedWorksCount = likes.size,
            likedTagWeights = displayWeights,
            smartRecommendations = recState.items,
            isLoadingRecommendations = recState.isLoading,
            recommendationsError = recState.error,
            shelves = sortedShelves,
            smartShelves = smartSummaries,
            localBooks = controls.localBooks,
            localBookItems = localItemMap,
            siteLabels = siteLabels,
            allSiteOptions = allSiteOptions,
            importState = controls.importState,
            showLibraryTutorial = appSettings != null && !appSettings.hasSeenTutorial(FeatureTutorialIds.LIBRARY_HUB),
            message = controls.message,
        )
    }.flowOn(dispatchers.default)

    private val initialState = LibraryHubUiState(allSiteOptions = allSiteOptions)
    val uiState = combine(contentFlow, selectedTab) { content, tab ->
        content.copy(selectedTab = tab)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = initialState,
    )

    init {
        viewModelScope.launch(dispatchers.io) {
            combine(selectedTab, likesFlow) { tab, likes -> tab to likes }
                .collectLatest { (tab, likes) ->
                    if (likes.isEmpty()) {
                        recommendationState.value = RecommendationState()
                        return@collectLatest
                    }
                    val signature = likes.joinToString("|") { "${it.compositeKey}:${it.likedAt}" }
                    if (tab == LibraryHubTab.SMART_RECOMMENDATIONS &&
                        recommendationState.value.lastLikesSignature != signature &&
                        recommendationJob?.isActive != true
                    ) {
                        refreshSmartRecommendations(force = false)
                    }
                }
        }
    }

    fun selectTab(tab: LibraryHubTab) {
        selectedTab.value = tab
        if (tab == LibraryHubTab.SMART_RECOMMENDATIONS &&
            recommendationState.value.items.isEmpty() &&
            !recommendationState.value.isLoading
        ) {
            refreshSmartRecommendations(force = false)
        }
    }

    fun clearMessage() {
        transientMessage.value = null
    }

    fun clearImportStatus() {
        importState.update { it.copy(lastMessage = null, isError = false) }
    }

    fun completeLibraryTutorial() {
        val repo = preferencesRepository ?: return
        viewModelScope.launch(dispatchers.io) {
            repo.markTutorialSeen(FeatureTutorialIds.LIBRARY_HUB)
        }
    }

    fun skipAllTutorials() {
        val repo = preferencesRepository ?: return
        viewModelScope.launch(dispatchers.io) {
            repo.markTutorialsSeen(FeatureTutorialIds.ALL)
        }
    }

    // ---- Watch Later Direct Actions ----

    fun removeFromWatchLater(item: ShelfItem) {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                shelfRepository.removeItem(Shelf.WATCH_LATER_ID, item.siteId, item.mediaId)
            }.onSuccess {
                transientMessage.value = "已从稍后观看移除「${item.title}」"
            }.onFailure { error ->
                transientMessage.value = error.message ?: "移出稍后观看失败"
            }
        }
    }

    // ---- Like-Powered Smart Recommendations ----

    fun refreshSmartRecommendations(force: Boolean = true) {
        val likesRepo = likeRepository ?: return
        if (!force && recommendationJob?.isActive == true) return
        recommendationJob?.cancel()
        recommendationJob = viewModelScope.launch(dispatchers.io) {
            val likes = runCatching { likesRepo.getAll() }.getOrDefault(emptyList())
            if (likes.isEmpty()) {
                recommendationState.value = RecommendationState()
                return@launch
            }
            val signature = likes.joinToString("|") { "${it.compositeKey}:${it.likedAt}" }
            val weights = LikeRecommendationEngine.computeTagWeights(likes = likes, nowMs = clockMs(), limit = 20)
            if (weights.isEmpty()) {
                recommendationState.value = RecommendationState(
                    isLoading = false,
                    items = emptyList(),
                    error = "已点赞的作品缺少可用标签，多点赞几个带标签的作品即可生成推荐",
                    lastLikesSignature = signature,
                )
                return@launch
            }

            recommendationState.update { it.copy(isLoading = true, error = null) }

            try {
                val appSettings = runCatching { preferencesRepository?.currentSettings() }.getOrNull()
                val translateTags = appSettings?.translateTags ?: true
                val blockedGlobal = runCatching {
                    blockedTermsRepository?.observeGlobalTerms()?.first().orEmpty()
                }.getOrDefault(emptySet())
                val cachedTags = runCatching {
                    mediaTagCacheRepository?.observeAllTags()?.first().orEmpty()
                }.getOrDefault(emptyMap())

                // Prioritize sites where the user has liked works, then fall back to the active site.
                val likedSiteOrder = likes
                    .groupingBy { it.siteId }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .toMutableList()
                appSettings?.activeSiteId?.let { active ->
                    if (active !in likedSiteOrder) likedSiteOrder.add(active)
                }

                val targetPlugins = likedSiteOrder
                    .mapNotNull { siteRegistry.get(it) }
                    .filter { plugin ->
                        !plugin.id.isLocal &&
                            plugin.feedSource != null &&
                            plugin.descriptor.hasCapability(SiteCapability.SEARCH)
                    }
                    .take(3)

                val topTags = weights.take(4)
                val candidatesByKey = LinkedHashMap<String, MediaItem>()
                val hintKeysByItem = LinkedHashMap<String, MutableSet<String>>()

                coroutineScope {
                    val queries = buildList {
                        for (plugin in targetPlugins) {
                            val supportedScopes = plugin.descriptor.searchScopes
                            val tagScope = if (SearchScope.TAG in supportedScopes) SearchScope.TAG else SearchScope.ALL
                            for (tagWeight in topTags.take(3)) {
                                val query = tagWeight.tag.slug.trim().ifEmpty { tagWeight.tag.label.trim() }
                                if (query.isNotEmpty()) {
                                    add(Triple(plugin, tagScope, tagWeight))
                                }
                            }
                        }
                    }

                    val fetched = queries.map { (plugin, scope, tagWeight) ->
                        async {
                            val query = tagWeight.tag.slug.trim().ifEmpty { tagWeight.tag.label.trim() }
                            val items = runCatching {
                                plugin.feedSource?.loadFeed(
                                    FeedRequest(
                                        siteId = plugin.id,
                                        query = query,
                                        scope = scope,
                                        page = 1,
                                    ),
                                )?.items.orEmpty()
                            }.getOrDefault(emptyList())
                            tagWeight to items.take(12)
                        }
                    }.awaitAll()

                    for ((tagWeight, items) in fetched) {
                        val hintKey = LikeRecommendationEngine.canonicalTagKey(tagWeight.tag)
                        for (item in items) {
                            candidatesByKey.putIfAbsent(item.compositeKey, item)
                            if (hintKey != null) {
                                hintKeysByItem.getOrPut(item.compositeKey) { LinkedHashSet() }.add(hintKey)
                            }
                        }
                    }
                }

                val allowedCandidates = candidatesByKey.values.filter { item ->
                    val siteBlocked = runCatching {
                        blockedTermsRepository?.observeEffectiveTermsBySite(item.siteId)?.first().orEmpty()
                    }.getOrDefault(blockedGlobal)
                    ContentFilter.isAllowed(
                        item = item,
                        blockedTerms = siteBlocked,
                        tagDictionary = tagDictionary,
                        extraTags = cachedTags[item.compositeKey].orEmpty(),
                    )
                }

                val ranked = LikeRecommendationEngine.rankSmartRecommendations(
                    likes = likes,
                    tagWeights = weights,
                    candidates = allowedCandidates,
                    sourceTagHintByKey = hintKeysByItem.mapValues { it.value.toSet() },
                    cachedTagsByKey = cachedTags,
                    limit = 30,
                )

                val cards = ranked.map { rec ->
                    val translatedMatched = rec.matchedTags.map { raw ->
                        if (translateTags) tagDictionary.translate(raw, "") ?: raw else raw
                    }
                    val reason = if (translatedMatched.isNotEmpty()) {
                        "偏好匹配：${translatedMatched.joinToString(" · ")}"
                    } else {
                        rec.reason.ifBlank { "基于你的点赞偏好推荐" }
                    }
                    SmartRecommendationCard(
                        item = rec.item,
                        siteLabel = siteRegistry.displayNameFor(rec.item.siteId),
                        reason = reason,
                        matchedTags = translatedMatched,
                        score = rec.score,
                    )
                }

                recommendationState.value = RecommendationState(
                    isLoading = false,
                    items = cards,
                    error = if (cards.isEmpty()) "暂未拉取到可推荐作品，请检查网络或点击刷新重试" else null,
                    lastLikesSignature = signature,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                recommendationState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message?.takeIf(String::isNotBlank) ?: "智能推荐拉取失败，请重试",
                    )
                }
            }
        }
    }

    private fun formatTagDisplay(tag: TagRef, translateTags: Boolean): String {
        val raw = tag.label.ifBlank { tag.slug }
        if (!translateTags) return raw
        return tagDictionary.translate(tag.slug, tag.namespace)
            ?: tagDictionary.translate(raw, tag.namespace)
            ?: raw
    }

    // ---- Custom Shelf CRUD (retained for compatibility) ----

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

    // ---- Smart Shelf CRUD (retained for compatibility) ----

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
        val importState: LocalImportState,
        val message: String?,
        val localBooks: List<LocalBook>,
    )
}
