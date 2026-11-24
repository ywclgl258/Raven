package com.raven.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class SubscriptionsTab(val label: String) {
    MANAGE("订阅管理"),
    FEED("聚合动态"),
}

enum class InboxSourceFilter(val label: String, val kind: InboxSourceKind?) {
    ALL("全部来源", null),
    TAG_SUBSCRIPTION("标签订阅", InboxSourceKind.TAG_SUBSCRIPTION),
    SAVED_SEARCH("保存的搜索", InboxSourceKind.SAVED_SEARCH),
}

data class InboxRefreshProgress(
    val isRefreshing: Boolean = false,
    val completedSources: Int = 0,
    val totalSources: Int = 0,
    val newlyDiscoveredCount: Int = 0,
    val baselinedSourceCount: Int = 0,
    val failedSourceCount: Int = 0,
)

data class SubscriptionsUiState(
    val selectedTab: SubscriptionsTab = SubscriptionsTab.MANAGE,
    val selectedSiteId: SiteId? = null,
    val subscriptions: List<TagSubscription> = emptyList(),
    val visibleSubscriptions: List<TagSubscription> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val aggregatedTagFeed: List<UpdateInboxItem> = emptyList(),
    val inboxUnreadOnly: Boolean = false,
    val inboxSourceFilter: InboxSourceFilter = InboxSourceFilter.ALL,
    val inboxSiteFilter: SiteId? = null,
    val inboxItems: List<UpdateInboxItem> = emptyList(),
    val totalInboxCount: Int = 0,
    val unreadCount: Int = 0,
    val unreadTagCount: Int = 0,
    val unreadSavedSearchCount: Int = 0,
    val subscriptionSiteOptions: List<SiteFilterOption> = emptyList(),
    val inboxSiteOptions: List<SiteFilterOption> = emptyList(),
    val siteLabels: Map<SiteId, String> = emptyMap(),
    val refreshProgress: InboxRefreshProgress = InboxRefreshProgress(),
    val checkingSourceId: String? = null,
    val message: String? = null,
)

/**
 * Wrapper passed through Koin `parametersOf(...)` to avoid `@JvmInline value class` boxing issues.
 */
data class SubscriptionsParams(
    val initialSiteId: SiteId? = null,
)

private data class SubscriptionsControls(
    val selectedTab: SubscriptionsTab = SubscriptionsTab.MANAGE,
    val selectedSiteId: SiteId? = null,
    val inboxUnreadOnly: Boolean = false,
    val inboxSourceFilter: InboxSourceFilter = InboxSourceFilter.ALL,
    val inboxSiteFilter: SiteId? = null,
    val refreshProgress: InboxRefreshProgress = InboxRefreshProgress(),
    val checkingSourceId: String? = null,
    val message: String? = null,
)

private data class SourceCheckOutcome(
    val newlyDiscovered: Int = 0,
    val establishedBaseline: Boolean = false,
    val failed: Boolean = false,
)

class SubscriptionsViewModel(
    initialSiteId: SiteId? = null,
    private val subscriptionRepository: SubscriptionRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val preferencesRepository: PreferencesRepository,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val tagDictionary: TagDictionary = TagDictionary.default,
    private val mediaTagCacheRepository: MediaTagCacheRepository? = null,
) : ViewModel() {

    private val controls = MutableStateFlow(
        SubscriptionsControls(
            selectedSiteId = initialSiteId,
        ),
    )

    /**
     * Tracks tag-subscription source IDs that have completed at least one successful baseline check
     * even if the initial feed returned zero items.
     */
    private val baselinedSources: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private var refreshJob: Job? = null

    private val cachedTagsFlow = mediaTagCacheRepository?.observeAllTags() ?: flowOf(emptyMap())

    private val repoDataFlow = combine(
        subscriptionRepository.observeAllSubscriptions(),
        subscriptionRepository.observeSavedSearches(),
        subscriptionRepository.observeInbox(),
        blockedTermsRepository.observeAllGrouped(),
        cachedTagsFlow,
    ) { subs, savedSearches, inbox, blockedBySite, cachedTags ->
        SubscriptionsRepoSnapshot(
            subscriptions = subs,
            savedSearches = savedSearches,
            inbox = inbox,
            blockedBySite = blockedBySite,
            cachedTagsByKey = cachedTags,
        )
    }

    val uiState: StateFlow<SubscriptionsUiState> = combine(
        repoDataFlow,
        controls,
    ) { repo, ctrl ->
        val allSubs = repo.subscriptions
        val savedSearches = repo.savedSearches
        val allInbox = filterInboxEntries(repo.inbox, repo.blockedBySite, repo.cachedTagsByKey)
        val sortedSubs = allSubs.sortedWith(
            compareBy<TagSubscription> { it.siteId.value }.thenBy { it.sortOrder },
        )
        val activeSiteFilter = ctrl.selectedSiteId
        val visibleSubs = if (activeSiteFilter == null) {
            sortedSubs
        } else {
            sortedSubs.filter { it.siteId == activeSiteFilter }
        }

        val sortedInbox = allInbox.sortedByDescending { it.discoveredAt }

        val tagFeed = sortedInbox.filter { entry ->
            entry.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION &&
                (activeSiteFilter == null || entry.item.siteId == activeSiteFilter)
        }

        val filteredInbox = sortedInbox.filter { entry ->
            val matchesUnread = !ctrl.inboxUnreadOnly || !entry.isRead
            val matchesSource = ctrl.inboxSourceFilter.kind == null ||
                entry.sourceKind == ctrl.inboxSourceFilter.kind
            val matchesSite = ctrl.inboxSiteFilter == null ||
                entry.item.siteId == ctrl.inboxSiteFilter
            matchesUnread && matchesSource && matchesSite
        }

        val allPresentSites = buildList {
            allSubs.forEach { add(it.siteId) }
            allInbox.forEach { add(it.item.siteId) }
            if (activeSiteFilter != null) add(activeSiteFilter)
        }

        SubscriptionsUiState(
            selectedTab = ctrl.selectedTab,
            selectedSiteId = activeSiteFilter,
            subscriptions = sortedSubs,
            visibleSubscriptions = visibleSubs,
            savedSearches = savedSearches,
            aggregatedTagFeed = tagFeed,
            inboxUnreadOnly = ctrl.inboxUnreadOnly,
            inboxSourceFilter = ctrl.inboxSourceFilter,
            inboxSiteFilter = ctrl.inboxSiteFilter,
            inboxItems = filteredInbox,
            totalInboxCount = sortedInbox.size,
            unreadCount = sortedInbox.count { !it.isRead },
            unreadTagCount = sortedInbox.count { !it.isRead && it.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION },
            unreadSavedSearchCount = sortedInbox.count { !it.isRead && it.sourceKind == InboxSourceKind.SAVED_SEARCH },
            subscriptionSiteOptions = siteRegistry.buildSiteFilterOptions(allSubs.map { it.siteId }),
            inboxSiteOptions = siteRegistry.buildSiteFilterOptions(allInbox.map { it.item.siteId }),
            siteLabels = allPresentSites.distinct().associateWith { siteRegistry.displayNameFor(it) },
            refreshProgress = ctrl.refreshProgress,
            checkingSourceId = ctrl.checkingSourceId,
            message = ctrl.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SubscriptionsUiState(selectedSiteId = initialSiteId),
    )

    // ---- Navigation / Filter Controls ----

    fun selectTab(tab: SubscriptionsTab) {
        controls.update { it.copy(selectedTab = tab) }
    }

    fun selectSubscriptionSite(siteId: SiteId?) {
        controls.update { it.copy(selectedSiteId = siteId) }
    }

    fun setInboxUnreadOnly(unreadOnly: Boolean) {
        controls.update { it.copy(inboxUnreadOnly = unreadOnly) }
    }

    fun selectInboxSourceFilter(filter: InboxSourceFilter) {
        controls.update { it.copy(inboxSourceFilter = filter) }
    }

    fun selectInboxSiteFilter(siteId: SiteId?) {
        controls.update { it.copy(inboxSiteFilter = siteId) }
    }

    fun clearMessage() {
        controls.update { it.copy(message = null) }
    }

    // ---- Tag Subscription Management ----

    fun moveSubscriptionUp(sub: TagSubscription) = moveSubscriptionBy(sub, delta = -1)

    fun moveSubscriptionDown(sub: TagSubscription) = moveSubscriptionBy(sub, delta = 1)

    private fun moveSubscriptionBy(sub: TagSubscription, delta: Int) {
        viewModelScope.launch(dispatchers.io) {
            val siteSubs = subscriptionRepository.observeSubscriptions(sub.siteId).first()
            val fromIndex = siteSubs.indexOfFirst { it.slug == sub.slug && it.scope == sub.scope }
            val toIndex = fromIndex + delta
            if (fromIndex in siteSubs.indices && toIndex in siteSubs.indices) {
                subscriptionRepository.moveSubscription(sub.siteId, fromIndex, toIndex)
            }
        }
    }

    fun unsubscribe(sub: TagSubscription) {
        viewModelScope.launch(dispatchers.io) {
            val sourceId = subscriptionSourceId(sub)
            subscriptionRepository.unsubscribe(sub.siteId, sub.slug, sub.scope)
            subscriptionRepository.clearInboxBySource(InboxSourceKind.TAG_SUBSCRIPTION, sourceId)
            baselinedSources.remove(sourceId)
            controls.update { it.copy(message = "已取消订阅「${sub.label}」") }
        }
    }

    fun deleteSavedSearch(id: String) {
        viewModelScope.launch(dispatchers.io) {
            subscriptionRepository.deleteSavedSearch(id)
            baselinedSources.remove(id)
            controls.update { it.copy(message = "已删除保存的搜索") }
        }
    }

    // ---- Inbox Read / Delete Operations ----

    fun markRead(id: String) {
        viewModelScope.launch(dispatchers.io) {
            subscriptionRepository.markRead(id)
        }
    }

    fun markUnread(id: String) {
        viewModelScope.launch(dispatchers.io) {
            subscriptionRepository.markUnread(id)
        }
    }

    fun markAllRead() {
        val filterKind = controls.value.inboxSourceFilter.kind
        viewModelScope.launch(dispatchers.io) {
            if (filterKind == null) {
                subscriptionRepository.markAllRead()
            } else {
                subscriptionRepository.markAllReadBySourceKind(filterKind)
            }
            controls.update { it.copy(message = "已全部标为已读") }
        }
    }

    fun markAllTagUpdatesRead() {
        viewModelScope.launch(dispatchers.io) {
            subscriptionRepository.markAllReadBySourceKind(InboxSourceKind.TAG_SUBSCRIPTION)
            controls.update { it.copy(message = "标签订阅动态已全部标为已读") }
        }
    }

    fun deleteInboxItem(id: String) {
        viewModelScope.launch(dispatchers.io) {
            subscriptionRepository.deleteInboxItem(id)
        }
    }

    // ---- Single-Tag & Global Concurrent Refresh (Semaphore(3) + First-Check Baseline) ----

    fun checkSingleTag(sub: TagSubscription) {
        val sourceId = subscriptionSourceId(sub)
        viewModelScope.launch(dispatchers.io) {
            controls.update { it.copy(checkingSourceId = sourceId) }
            try {
                val settings = preferencesRepository.currentSettings()
                val blockedBySite = blockedTermsRepository.observeAllGrouped().first()
                val cachedTags = mediaTagCacheRepository?.observeAllTags()?.first().orEmpty()
                val outcome = refreshTagSubscription(sub, settings, blockedBySite, cachedTags)
                controls.update {
                    it.copy(
                        checkingSourceId = null,
                        message = when {
                            outcome.failed -> "检查「${sub.label}」更新失败，请稍后重试"
                            outcome.establishedBaseline -> "已为「${sub.label}」建立基线，后续新发布将自动提醒"
                            outcome.newlyDiscovered > 0 -> "「${sub.label}」发现 ${outcome.newlyDiscovered} 条新内容"
                            else -> "「${sub.label}」暂无新内容"
                        },
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                controls.update { if (it.checkingSourceId == sourceId) it.copy(checkingSourceId = null) else it }
            }
        }
    }

    fun refreshTagUpdates() = refreshAllUpdates(
        includeTags = true,
        includeSavedSearches = false,
        siteFilter = controls.value.selectedSiteId,
    )

    fun refreshAllUpdates(
        includeTags: Boolean = true,
        includeSavedSearches: Boolean = true,
        siteFilter: SiteId? = null,
    ) {
        if (controls.value.refreshProgress.isRefreshing) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(dispatchers.io) {
            val allSubs = if (includeTags) {
                subscriptionRepository.observeAllSubscriptions().first()
                    .filter { siteFilter == null || it.siteId == siteFilter }
            } else {
                emptyList()
            }
            val allSaved = if (includeSavedSearches) {
                subscriptionRepository.observeSavedSearches().first()
                    .filter { search -> siteFilter == null || siteFilter in search.siteIds }
            } else {
                emptyList()
            }

            val totalSources = allSubs.size + allSaved.size
            if (totalSources == 0) {
                controls.update { it.copy(message = "暂无可检查的标签订阅或保存的搜索") }
                return@launch
            }

            controls.update {
                it.copy(
                    refreshProgress = InboxRefreshProgress(
                        isRefreshing = true,
                        completedSources = 0,
                        totalSources = totalSources,
                    ),
                )
            }

            val settings = preferencesRepository.currentSettings()
            val blockedBySite = blockedTermsRepository.observeAllGrouped().first()
            val cachedTags = mediaTagCacheRepository?.observeAllTags()?.first().orEmpty()
            val semaphore = Semaphore(MAX_CONCURRENT_CHECKS)

            try {
                val outcomes = coroutineScope {
                    val tagJobs = allSubs.map { sub ->
                        async(dispatchers.io) {
                            semaphore.withPermit {
                                val outcome = refreshTagSubscription(sub, settings, blockedBySite, cachedTags)
                                recordProgressTick(outcome)
                                outcome
                            }
                        }
                    }
                    val savedJobs = allSaved.map { saved ->
                        async(dispatchers.io) {
                            semaphore.withPermit {
                                val outcome = refreshSavedSearch(saved, settings, blockedBySite, cachedTags)
                                recordProgressTick(outcome)
                                outcome
                            }
                        }
                    }
                    (tagJobs + savedJobs).awaitAll()
                }

                val newCount = outcomes.sumOf { it.newlyDiscovered }
                val baselined = outcomes.count { it.establishedBaseline }
                val failed = outcomes.count { it.failed }

                val summaryMessage = buildString {
                    when {
                        newCount > 0 -> append("发现 $newCount 条新内容")
                        baselined > 0 -> append("已为 $baselined 个来源建立首次基线")
                        else -> append("检查完成，暂无新内容")
                    }
                    if (newCount > 0 && baselined > 0) {
                        append("，另有 $baselined 个来源建立基线")
                    }
                    if (failed > 0) {
                        append("（$failed 个来源刷新失败）")
                    }
                }

                controls.update {
                    it.copy(
                        refreshProgress = it.refreshProgress.copy(isRefreshing = false),
                        message = summaryMessage,
                    )
                }
            } catch (ce: CancellationException) {
                controls.update { it.copy(refreshProgress = it.refreshProgress.copy(isRefreshing = false)) }
                throw ce
            }
        }
    }

    private fun recordProgressTick(outcome: SourceCheckOutcome) {
        controls.update { current ->
            val p = current.refreshProgress
            current.copy(
                refreshProgress = p.copy(
                    completedSources = p.completedSources + 1,
                    newlyDiscoveredCount = p.newlyDiscoveredCount + outcome.newlyDiscovered,
                    baselinedSourceCount = p.baselinedSourceCount + if (outcome.establishedBaseline) 1 else 0,
                    failedSourceCount = p.failedSourceCount + if (outcome.failed) 1 else 0,
                ),
            )
        }
    }

    private suspend fun refreshTagSubscription(
        sub: TagSubscription,
        settings: AppSettings,
        blockedBySite: Map<SiteId, List<String>>,
        cachedTagsByKey: Map<String, List<TagRef>>,
    ): SourceCheckOutcome {
        val plugin = siteRegistry.get(sub.siteId) ?: return SourceCheckOutcome(failed = true)
        val sourceId = subscriptionSourceId(sub)
        return try {
            val existing = subscriptionRepository
                .observeInboxBySource(InboxSourceKind.TAG_SUBSCRIPTION, sourceId)
                .first()
            val isBaseline = existing.isEmpty() && sourceId !in baselinedSources
            val knownKeys = existing.mapTo(mutableSetOf()) { it.item.compositeKey }

            val rawItems = fetchTagItems(plugin, sub, settings)
            val allowed = filterBlocked(rawItems, blockedBySite, cachedTagsByKey)
            val now = clockMs()

            if (isBaseline) {
                val baselineEntries = allowed.mapIndexed { idx, media ->
                    UpdateInboxItem(
                        id = inboxEntryId(InboxSourceKind.TAG_SUBSCRIPTION, sourceId, media.compositeKey),
                        sourceKind = InboxSourceKind.TAG_SUBSCRIPTION,
                        sourceId = sourceId,
                        sourceLabel = sub.label,
                        item = media,
                        discoveredAt = (now - idx).coerceAtLeast(0L),
                        isRead = true,
                    )
                }
                if (baselineEntries.isNotEmpty()) {
                    subscriptionRepository.upsertInboxItems(baselineEntries)
                }
                baselinedSources.add(sourceId)
                SourceCheckOutcome(newlyDiscovered = 0, establishedBaseline = true)
            } else {
                val newlyFound = allowed.filter { it.compositeKey !in knownKeys }
                if (newlyFound.isNotEmpty()) {
                    val entries = newlyFound.mapIndexed { idx, media ->
                        UpdateInboxItem(
                            id = inboxEntryId(InboxSourceKind.TAG_SUBSCRIPTION, sourceId, media.compositeKey),
                            sourceKind = InboxSourceKind.TAG_SUBSCRIPTION,
                            sourceId = sourceId,
                            sourceLabel = sub.label,
                            item = media,
                            discoveredAt = (now - idx).coerceAtLeast(0L),
                            isRead = false,
                        )
                    }
                    subscriptionRepository.upsertInboxItems(entries)
                }
                baselinedSources.add(sourceId)
                SourceCheckOutcome(newlyDiscovered = newlyFound.size)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            SourceCheckOutcome(failed = true)
        }
    }

    private suspend fun refreshSavedSearch(
        saved: SavedSearch,
        settings: AppSettings,
        blockedBySite: Map<SiteId, List<String>>,
        cachedTagsByKey: Map<String, List<TagRef>>,
    ): SourceCheckOutcome {
        val sourceId = saved.id
        return try {
            val existing = subscriptionRepository
                .observeInboxBySource(InboxSourceKind.SAVED_SEARCH, sourceId)
                .first()
            val isBaseline = saved.lastCheckedAt == null &&
                existing.isEmpty() &&
                sourceId !in baselinedSources
            val knownKeys = existing.mapTo(mutableSetOf()) { it.item.compositeKey }

            val collected = linkedMapOf<String, MediaItem>()
            var anySucceeded = false

            for (siteId in saved.siteIds) {
                val plugin = siteRegistry.get(siteId) ?: continue
                try {
                    val request = FeedRequest(
                        siteId = siteId,
                        query = saved.query.takeIf { it.isNotBlank() },
                        scope = saved.scope,
                        mediaKind = saved.mediaKind,
                        sort = saved.sort,
                        language = if (saved.language != com.raven.app.core.model.ComicLanguage.ALL) {
                            saved.language
                        } else {
                            settings.languageFor(siteId)
                        },
                        advancedFilters = saved.filters,
                        page = 1,
                    )
                    val items = executePluginQuery(plugin, request)
                    anySucceeded = true
                    for (media in items) {
                        collected.putIfAbsent(media.compositeKey, media)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Continue remaining target sites inside this saved search
                }
            }

            if (!anySucceeded) {
                return SourceCheckOutcome(failed = true)
            }

            val allowed = filterBlocked(collected.values.toList(), blockedBySite, cachedTagsByKey)
            val now = clockMs()

            if (isBaseline) {
                val baselineEntries = allowed.mapIndexed { idx, media ->
                    UpdateInboxItem(
                        id = inboxEntryId(InboxSourceKind.SAVED_SEARCH, sourceId, media.compositeKey),
                        sourceKind = InboxSourceKind.SAVED_SEARCH,
                        sourceId = sourceId,
                        sourceLabel = saved.name,
                        item = media,
                        discoveredAt = (now - idx).coerceAtLeast(0L),
                        isRead = true,
                    )
                }
                if (baselineEntries.isNotEmpty()) {
                    subscriptionRepository.upsertInboxItems(baselineEntries)
                }
                subscriptionRepository.updateLastCheckedAt(saved.id, now)
                baselinedSources.add(sourceId)
                SourceCheckOutcome(newlyDiscovered = 0, establishedBaseline = true)
            } else {
                val newlyFound = allowed.filter { it.compositeKey !in knownKeys }
                if (newlyFound.isNotEmpty()) {
                    val entries = newlyFound.mapIndexed { idx, media ->
                        UpdateInboxItem(
                            id = inboxEntryId(InboxSourceKind.SAVED_SEARCH, sourceId, media.compositeKey),
                            sourceKind = InboxSourceKind.SAVED_SEARCH,
                            sourceId = sourceId,
                            sourceLabel = saved.name,
                            item = media,
                            discoveredAt = (now - idx).coerceAtLeast(0L),
                            isRead = false,
                        )
                    }
                    subscriptionRepository.upsertInboxItems(entries)
                }
                subscriptionRepository.updateLastCheckedAt(saved.id, now)
                baselinedSources.add(sourceId)
                SourceCheckOutcome(newlyDiscovered = newlyFound.size)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            SourceCheckOutcome(failed = true)
        }
    }

    private suspend fun fetchTagItems(
        plugin: SitePlugin,
        sub: TagSubscription,
        settings: AppSettings,
    ): List<MediaItem> {
        val resolvedFeed = plugin.linkResolver
            ?.takeIf { sub.url.isNotBlank() && it.canHandle(sub.url) }
            ?.resolve(sub.url) as? ResolvedLink.Feed
        val request = resolvedFeed?.request ?: FeedRequest(
            siteId = sub.siteId,
            query = sub.slug,
            scope = sub.scope,
            sort = FeedSort.NEWEST,
            language = settings.languageFor(sub.siteId),
            page = 1,
        )
        return executePluginQuery(plugin, request)
    }

    private suspend fun executePluginQuery(
        plugin: SitePlugin,
        request: FeedRequest,
    ): List<MediaItem> {
        val page = when {
            !request.query.isNullOrBlank() && plugin.searchSource != null ->
                requireNotNull(plugin.searchSource).search(request)
            plugin.feedSource != null ->
                requireNotNull(plugin.feedSource).loadFeed(request)
            plugin.searchSource != null ->
                requireNotNull(plugin.searchSource).search(request)
            else -> return emptyList()
        }
        return page.items.distinctBy { it.compositeKey }
    }

    private fun filterBlocked(
        items: List<MediaItem>,
        blockedBySite: Map<SiteId, List<String>>,
        cachedTagsByKey: Map<String, List<TagRef>>,
    ): List<MediaItem> = ContentFilter.filterAllowed(
        items = items,
        blockedTermsBySite = blockedBySite,
        tagDictionary = tagDictionary,
        extraTagsByMediaKey = cachedTagsByKey,
    )

    private fun filterInboxEntries(
        entries: List<UpdateInboxItem>,
        blockedBySite: Map<SiteId, List<String>>,
        cachedTagsByKey: Map<String, List<TagRef>>,
    ): List<UpdateInboxItem> {
        if (entries.isEmpty() || blockedBySite.isEmpty()) return entries
        val globalTerms = blockedBySite[SiteId.GLOBAL].orEmpty()
        return entries.filter { entry ->
            val siteTerms = blockedBySite[entry.item.siteId].orEmpty()
            val effectiveTerms = when {
                globalTerms.isEmpty() -> siteTerms
                siteTerms.isEmpty() -> globalTerms
                else -> globalTerms + siteTerms
            }
            if (effectiveTerms.isEmpty()) {
                true
            } else {
                ContentFilter.isAllowed(
                    item = entry.item,
                    blockedTerms = effectiveTerms,
                    tagDictionary = tagDictionary,
                    extraTags = cachedTagsByKey[entry.item.compositeKey].orEmpty(),
                )
            }
        }
    }

    private data class SubscriptionsRepoSnapshot(
        val subscriptions: List<TagSubscription>,
        val savedSearches: List<SavedSearch>,
        val inbox: List<UpdateInboxItem>,
        val blockedBySite: Map<SiteId, List<String>>,
        val cachedTagsByKey: Map<String, List<TagRef>>,
    )

    companion object {
        const val MAX_CONCURRENT_CHECKS: Int = 3

        fun subscriptionSourceId(sub: TagSubscription): String =
            "${sub.siteId.value}:${sub.scope.name}:${sub.slug}"

        fun inboxEntryId(
            sourceKind: InboxSourceKind,
            sourceId: String,
            itemCompositeKey: String,
        ): String = "${sourceKind.name}:$sourceId:$itemCompositeKey"
    }
}
