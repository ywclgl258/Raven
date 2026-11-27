package com.raven.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.model.FeatureTutorialIds
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SubscriptionsTab(val label: String) {
    SETTINGS("订阅设置"),
    FEED("订阅动态"),
}

/**
 * Site coverage of a logical subscription group derived from per-site [TagSubscription] rows.
 */
enum class SubscriptionCoverage(val label: String) {
    SINGLE("单站点"),
    MULTI("多站点"),
    ALL("全站点"),
}

data class SubscriptionGroup(
    val key: String,
    val slug: String,
    val label: String,
    val scope: SearchScope,
    val coverage: SubscriptionCoverage,
    val subscriptions: List<TagSubscription>,
    val siteCount: Int,
    val unreadCount: Int,
) {
    val siteIds: Set<SiteId> get() = subscriptions.mapTo(linkedSetOf()) { it.siteId }
    val sourceIds: Set<String> get() = subscriptions.mapTo(linkedSetOf()) { SubscriptionSyncRunner.subscriptionSourceId(it) }
}

enum class InboxSourceFilter(val label: String, val kind: InboxSourceKind?) {
    ALL("全部来源", null),
    TAG_SUBSCRIPTION("标签订阅", InboxSourceKind.TAG_SUBSCRIPTION),
    SAVED_SEARCH("保存的搜索", InboxSourceKind.SAVED_SEARCH),
    SERIES_FOLLOW("作品追更", InboxSourceKind.SERIES_FOLLOW),
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
    val selectedTab: SubscriptionsTab = SubscriptionsTab.SETTINGS,
    val selectedSiteId: SiteId? = null,
    val subscriptionGroups: List<SubscriptionGroup> = emptyList(),
    val visibleSubscriptionGroups: List<SubscriptionGroup> = emptyList(),
    val onlineTagSearchSites: List<SiteFilterOption> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val aggregatedTagFeed: List<UpdateInboxItem> = emptyList(),
    val inboxItems: List<UpdateInboxItem> = emptyList(),
    val inboxUnreadOnly: Boolean = false,
    val inboxSourceFilter: InboxSourceFilter = InboxSourceFilter.ALL,
    val inboxSiteFilter: SiteId? = null,
    val inboxSearchQuery: String = "",
    val totalInboxCount: Int = 0,
    val unreadCount: Int = 0,
    val unreadTagCount: Int = 0,
    val unreadSavedSearchCount: Int = 0,
    val unreadSeriesCount: Int = 0,
    val subscriptionSiteOptions: List<SiteFilterOption> = emptyList(),
    val inboxSiteOptions: List<SiteFilterOption> = emptyList(),
    val siteLabels: Map<SiteId, String> = emptyMap(),
    val refreshProgress: InboxRefreshProgress = InboxRefreshProgress(),
    val checkingSourceIds: Set<String> = emptySet(),
    val showSubscriptionsTutorial: Boolean = false,
    val message: String? = null,
)

/**
 * Wrapper passed through Koin `parametersOf(...)` to avoid `@JvmInline value class` boxing issues.
 */
data class SubscriptionsParams(
    val initialSiteId: SiteId? = null,
)

private data class SubscriptionsControls(
    val selectedTab: SubscriptionsTab = SubscriptionsTab.SETTINGS,
    val selectedSiteId: SiteId? = null,
    val inboxUnreadOnly: Boolean = false,
    val inboxSourceFilter: InboxSourceFilter = InboxSourceFilter.ALL,
    val inboxSiteFilter: SiteId? = null,
    val inboxSearchQuery: String = "",
    val checkingSourceIds: Set<String> = emptySet(),
    val refreshProgress: InboxRefreshProgress = InboxRefreshProgress(),
    val message: String? = null,
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
    private val syncRunner: SubscriptionSyncRunner = SubscriptionSyncRunner(
        subscriptionRepository = subscriptionRepository,
        blockedTermsRepository = blockedTermsRepository,
        preferencesRepository = preferencesRepository,
        siteRegistry = siteRegistry,
        dispatchers = dispatchers,
        clockMs = clockMs,
        tagDictionary = tagDictionary,
        mediaTagCacheRepository = mediaTagCacheRepository,
    ),
) : ViewModel() {
    private val onlineTagSearchSites: List<SiteFilterOption> = siteRegistry.allPlugins()
        .filter { plugin ->
            plugin.searchSource != null && SearchScope.TAG in plugin.descriptor.searchScopes
        }
        .map { plugin ->
            SiteFilterOption(
                siteId = plugin.id,
                label = plugin.descriptor.shortName.ifBlank { plugin.descriptor.displayName },
                primaryKind = plugin.descriptor.primaryKind,
            )
        }

    private val onlineTagSearchSiteIds: Set<SiteId> = onlineTagSearchSites.mapTo(linkedSetOf()) { it.siteId }

    private val controls = MutableStateFlow(
        SubscriptionsControls(
            selectedSiteId = initialSiteId,
        ),
    )

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
        preferencesRepository.settings,
    ) { repo, ctrl, appSettings ->
        val allSubs = repo.subscriptions
        val savedSearches = repo.savedSearches
        val allInbox = filterInboxEntries(repo.inbox, repo.blockedBySite, repo.cachedTagsByKey)
        val sortedSubs = allSubs.sortedWith(
            compareBy<TagSubscription> { it.siteId.value }.thenBy { it.sortOrder },
        )
        val activeSiteFilter = ctrl.selectedSiteId
        val allGroups = deriveSubscriptionGroups(
            subscriptions = sortedSubs,
            inbox = allInbox.sortedByDescending { it.discoveredAt },
            eligibleSiteIds = onlineTagSearchSiteIds,
        )
        val visibleGroups = if (activeSiteFilter == null) {
            allGroups
        } else {
            allGroups.filter { activeSiteFilter in it.siteIds }
        }

        val sortedInbox = allInbox.sortedByDescending { it.discoveredAt }

        val tagFeed = sortedInbox.filter { entry ->
            entry.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION &&
                (activeSiteFilter == null || entry.item.siteId == activeSiteFilter)
        }

        val inboxQuery = ctrl.inboxSearchQuery.trim()
        val filteredInbox = sortedInbox.filter { entry ->
            val matchesUnread = !ctrl.inboxUnreadOnly || !entry.isRead
            val matchesSource = ctrl.inboxSourceFilter.kind == null ||
                entry.sourceKind == ctrl.inboxSourceFilter.kind
            val matchesSite = ctrl.inboxSiteFilter == null ||
                entry.item.siteId == ctrl.inboxSiteFilter
            val matchesQuery = inboxQuery.isEmpty() ||
                entry.item.title.contains(inboxQuery, ignoreCase = true) ||
                entry.sourceLabel.contains(inboxQuery, ignoreCase = true) ||
                entry.item.siteId.value.contains(inboxQuery, ignoreCase = true) ||
                siteRegistry.displayNameFor(entry.item.siteId).contains(inboxQuery, ignoreCase = true)
            matchesUnread && matchesSource && matchesSite && matchesQuery
        }

        val allPresentSites = buildList {
            allSubs.forEach { add(it.siteId) }
            allInbox.forEach { add(it.item.siteId) }
            if (activeSiteFilter != null) add(activeSiteFilter)
        }

        SubscriptionsUiState(
            selectedTab = ctrl.selectedTab,
            selectedSiteId = activeSiteFilter,
            subscriptionGroups = allGroups,
            visibleSubscriptionGroups = visibleGroups,
            onlineTagSearchSites = onlineTagSearchSites,
            savedSearches = savedSearches,
            aggregatedTagFeed = tagFeed,
            inboxItems = filteredInbox,
            inboxUnreadOnly = ctrl.inboxUnreadOnly,
            inboxSourceFilter = ctrl.inboxSourceFilter,
            inboxSiteFilter = ctrl.inboxSiteFilter,
            inboxSearchQuery = ctrl.inboxSearchQuery,
            totalInboxCount = sortedInbox.size,
            unreadCount = sortedInbox.count { !it.isRead },
            unreadTagCount = sortedInbox.count { !it.isRead && it.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION },
            unreadSavedSearchCount = sortedInbox.count { !it.isRead && it.sourceKind == InboxSourceKind.SAVED_SEARCH },
            unreadSeriesCount = sortedInbox.count { !it.isRead && it.sourceKind == InboxSourceKind.SERIES_FOLLOW },
            subscriptionSiteOptions = siteRegistry.buildSiteFilterOptions(allSubs.map { it.siteId }),
            inboxSiteOptions = siteRegistry.buildSiteFilterOptions(allInbox.map { it.item.siteId }),
            siteLabels = allPresentSites.distinct().associateWith { siteRegistry.displayNameFor(it) },
            refreshProgress = ctrl.refreshProgress,
            checkingSourceIds = ctrl.checkingSourceIds,
            showSubscriptionsTutorial = !appSettings.hasSeenTutorial(FeatureTutorialIds.SUBSCRIPTIONS_TAGS),
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

    fun completeSubscriptionsTutorial() {
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.markTutorialSeen(FeatureTutorialIds.SUBSCRIPTIONS_TAGS)
        }
    }

    fun skipAllTutorials() {
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.markTutorialsSeen(FeatureTutorialIds.ALL)
        }
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

    fun updateInboxSearchQuery(query: String) {
        controls.update { it.copy(inboxSearchQuery = query) }
    }


    fun clearMessage() {
        controls.update { it.copy(message = null) }
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
        val unreadIds = uiState.value.inboxItems.filterNot { it.isRead }.map { it.id }
        if (unreadIds.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            subscriptionRepository.markRead(unreadIds)
            controls.update { it.copy(message = "已将当前筛选的 ${unreadIds.size} 条更新标为已读") }
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

    // ---- Logical Subscription Group Management ----

    fun createSubscriptionGroup(
        slug: String,
        label: String,
        coverage: SubscriptionCoverage,
        selectedSiteIds: Set<SiteId>,
    ) {
        persistSubscriptionGroup(
            existingGroup = null,
            slug = slug,
            label = label,
            coverage = coverage,
            selectedSiteIds = selectedSiteIds,
        )
    }

    fun updateSubscriptionGroup(
        group: SubscriptionGroup,
        label: String,
        coverage: SubscriptionCoverage,
        selectedSiteIds: Set<SiteId>,
    ) {
        persistSubscriptionGroup(
            existingGroup = group,
            slug = group.slug,
            label = label,
            coverage = coverage,
            selectedSiteIds = selectedSiteIds,
        )
    }

    fun deleteSubscriptionGroup(group: SubscriptionGroup) {
        viewModelScope.launch(dispatchers.io) {
            group.subscriptions.forEach { sub ->
                subscriptionRepository.unsubscribe(sub.siteId, sub.slug, sub.scope)
                syncRunner.clearSourceBaseline(subscriptionSourceId(sub))
            }
            controls.update { it.copy(message = "已删除订阅「${group.label}」") }
        }
    }

    fun checkSubscriptionGroup(group: SubscriptionGroup) {
        val sourceIds = group.sourceIds
        if (sourceIds.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            controls.update { it.copy(checkingSourceIds = it.checkingSourceIds + sourceIds) }
            try {
                val summary = syncRunner.syncTags(group.subscriptions)
                controls.update { it.copy(message = groupCheckMessage(group.label, summary)) }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                controls.update { it.copy(checkingSourceIds = it.checkingSourceIds - sourceIds) }
            }
        }
    }

    private fun persistSubscriptionGroup(
        existingGroup: SubscriptionGroup?,
        slug: String,
        label: String,
        coverage: SubscriptionCoverage,
        selectedSiteIds: Set<SiteId>,
    ) {
        val cleanSlug = (existingGroup?.slug ?: slug).trim()
        if (cleanSlug.isEmpty()) {
            controls.update { it.copy(message = "标签 Slug 不能为空") }
            return
        }
        val cleanLabel = label.trim().ifBlank { cleanSlug }
        val scope = existingGroup?.scope ?: SearchScope.TAG
        viewModelScope.launch(dispatchers.io) {
            try {
                val allRows = subscriptionRepository.observeAllSubscriptions().first()
                val currentRows = existingGroup?.subscriptions
                    ?: allRows.filter { it.slug == cleanSlug && it.scope == scope }
                val targetSiteIds = when (coverage) {
                    SubscriptionCoverage.ALL -> onlineTagSearchSiteIds
                    SubscriptionCoverage.SINGLE -> selectedSiteIds.take(1).toSet()
                    SubscriptionCoverage.MULTI -> selectedSiteIds
                }
                val validationMessage = when {
                    targetSiteIds.isEmpty() && coverage == SubscriptionCoverage.ALL ->
                        "当前没有支持在线标签搜索的站点"
                    targetSiteIds.isEmpty() -> "请至少选择一个站点"
                    coverage == SubscriptionCoverage.SINGLE && targetSiteIds.size != 1 ->
                        "单站点订阅只能选择一个站点"
                    coverage == SubscriptionCoverage.MULTI && targetSiteIds.size < 2 ->
                        "多站点订阅至少选择两个站点"
                    else -> null
                }
                if (validationMessage != null) {
                    controls.update { it.copy(message = validationMessage) }
                    return@launch
                }

                val currentSiteIds = currentRows.mapTo(linkedSetOf()) { it.siteId }
                currentRows
                    .filter { it.siteId !in targetSiteIds }
                    .forEach { sub ->
                        subscriptionRepository.unsubscribe(sub.siteId, sub.slug, sub.scope)
                        syncRunner.clearSourceBaseline(subscriptionSourceId(sub))
                    }
                (targetSiteIds - currentSiteIds).forEach { siteId ->
                    val baseUrl = siteRegistry.get(siteId)?.descriptor?.baseUrl.orEmpty()
                    subscriptionRepository.subscribe(
                        TagSubscription(
                            siteId = siteId,
                            slug = cleanSlug,
                            label = cleanLabel,
                            url = baseUrl,
                            scope = scope,
                        ),
                    )
                }
                currentRows
                    .filter { it.siteId in targetSiteIds && it.label != cleanLabel }
                    .forEach { sub ->
                        subscriptionRepository.renameSubscription(
                            siteId = sub.siteId,
                            slug = sub.slug,
                            scope = sub.scope,
                            newLabel = cleanLabel,
                        )
                    }
                val action = when {
                    existingGroup != null -> "已更新订阅"
                    currentSiteIds.isNotEmpty() -> "已合并订阅"
                    else -> "已添加订阅"
                }
                controls.update { it.copy(message = "$action「$cleanLabel」") }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                controls.update { it.copy(message = "保存订阅失败，请稍后重试") }
            }
        }
    }

    private fun groupCheckMessage(label: String, summary: SubscriptionSyncSummary): String = buildString {
        when {
            summary.totalSources > 0 && summary.failedSourceCount == summary.totalSources ->
                append("检查「$label」更新失败，请稍后重试")
            summary.newlyDiscoveredCount > 0 ->
                append("「$label」发现 ${summary.newlyDiscoveredCount} 条新内容")
            summary.baselinedSourceCount > 0 ->
                append("已为「$label」建立基线，后续新发布将自动提醒")
            else -> append("「$label」暂无新内容")
        }
        if (summary.failedSourceCount > 0 && summary.failedSourceCount < summary.totalSources) {
            append("（${summary.failedSourceCount} 个站点检查失败）")
        }
    }

    fun refreshTagUpdates() = refreshAllUpdates(
        includeTags = true,
        includeSavedSearches = false,
        includeSeries = false,
        siteFilter = controls.value.selectedSiteId,
    )

    fun refreshAllUpdates(
        includeTags: Boolean = true,
        includeSavedSearches: Boolean = true,
        includeSeries: Boolean = true,
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

            val seriesCount = if (includeSeries) {
                subscriptionRepository.observeSeriesFollows().first().count { siteFilter == null || it.siteId == siteFilter }
            } else {
                0
            }
            val totalSources = allSubs.size + allSaved.size + seriesCount
            if (totalSources == 0) {
                controls.update { it.copy(message = "暂无可检查的订阅或追更作品") }
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

            try {
                val summary = syncRunner.syncAll(
                    includeTags = includeTags,
                    includeSavedSearches = includeSavedSearches,
                    includeSeries = includeSeries,
                    siteFilter = siteFilter,
                    onSourceStarted = { sourceId ->
                        controls.update { it.copy(checkingSourceIds = it.checkingSourceIds + sourceId) }
                    },
                    onSourceFinished = { sourceId, outcome ->
                        recordProgressTick(sourceId, outcome)
                    },
                )
                controls.update {
                    it.copy(
                        refreshProgress = it.refreshProgress.copy(isRefreshing = false),
                        checkingSourceIds = emptySet(),
                        message = summary.toStatusMessage(),
                    )
                }
            } catch (ce: CancellationException) {
                controls.update {
                    it.copy(
                        refreshProgress = it.refreshProgress.copy(isRefreshing = false),
                        checkingSourceIds = emptySet(),
                    )
                }
                throw ce
            }
        }
    }

    private fun recordProgressTick(sourceId: String, outcome: SourceCheckOutcome) {
        controls.update { current ->
            val p = current.refreshProgress
            current.copy(
                checkingSourceIds = current.checkingSourceIds - sourceId,
                refreshProgress = p.copy(
                    completedSources = p.completedSources + 1,
                    newlyDiscoveredCount = p.newlyDiscoveredCount + outcome.newlyDiscovered,
                    baselinedSourceCount = p.baselinedSourceCount + if (outcome.establishedBaseline) 1 else 0,
                    failedSourceCount = p.failedSourceCount + if (outcome.failed) 1 else 0,
                ),
            )
        }
    }

    private fun deriveSubscriptionGroups(
        subscriptions: List<TagSubscription>,
        inbox: List<UpdateInboxItem>,
        eligibleSiteIds: Set<SiteId>,
    ): List<SubscriptionGroup> {
        if (subscriptions.isEmpty()) return emptyList()
        return subscriptions
            .groupBy { it.scope to it.slug }
            .map { (key, rows) ->
                val scope = key.first
                val slug = key.second
                val sortedRows = rows.sortedWith(
                    compareBy<TagSubscription> { it.siteId.value }
                        .thenBy { it.sortOrder }
                        .thenBy { it.label },
                )
                val label = sortedRows.firstOrNull { it.label.isNotBlank() }?.label ?: slug
                val siteIds = sortedRows.mapTo(linkedSetOf()) { it.siteId }
                val sourceIds = sortedRows.mapTo(linkedSetOf()) { SubscriptionSyncRunner.subscriptionSourceId(it) }
                val unreadCount = inbox.count { entry ->
                    entry.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION &&
                        entry.sourceId in sourceIds &&
                        !entry.isRead
                }
                val coverage = when {
                    siteIds.size <= 1 -> SubscriptionCoverage.SINGLE
                    eligibleSiteIds.size > 1 && siteIds.containsAll(eligibleSiteIds) -> SubscriptionCoverage.ALL
                    else -> SubscriptionCoverage.MULTI
                }
                SubscriptionGroup(
                    key = "${scope.name}:$slug",
                    slug = slug,
                    label = label,
                    scope = scope,
                    coverage = coverage,
                    subscriptions = sortedRows,
                    siteCount = siteIds.size,
                    unreadCount = unreadCount,
                )
            }
            .sortedWith(
                compareBy<SubscriptionGroup> { group -> group.subscriptions.minOf { it.sortOrder } }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.scope.name }
                    .thenBy { it.slug },
            )
    }

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
        const val MAX_CONCURRENT_CHECKS: Int = SubscriptionSyncRunner.MAX_CONCURRENT_CHECKS

        fun subscriptionSourceId(sub: TagSubscription): String =
            SubscriptionSyncRunner.subscriptionSourceId(sub)

        fun inboxEntryId(
            sourceKind: InboxSourceKind,
            sourceId: String,
            itemCompositeKey: String,
        ): String = SubscriptionSyncRunner.inboxEntryId(sourceKind, sourceId, itemCompositeKey)
    }
}
