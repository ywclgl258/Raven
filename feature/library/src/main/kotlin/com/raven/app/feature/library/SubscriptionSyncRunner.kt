package com.raven.app.feature.library

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.model.ComicLanguage
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Result summary of checking a single tag subscription or saved search source.
 */
data class SourceCheckOutcome(
    val sourceId: String = "",
    val newlyDiscovered: Int = 0,
    val establishedBaseline: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Aggregated result of a full subscription + saved-search refresh pass.
 */
data class SubscriptionSyncSummary(
    val totalSources: Int = 0,
    val newlyDiscoveredCount: Int = 0,
    val baselinedSourceCount: Int = 0,
    val failedSourceCount: Int = 0,
) {
    fun toStatusMessage(): String = buildString {
        when {
            totalSources == 0 -> return "暂无可检查的标签订阅或保存的搜索"
            newlyDiscoveredCount > 0 -> append("发现 $newlyDiscoveredCount 条新内容")
            baselinedSourceCount > 0 -> append("已为 $baselinedSourceCount 个来源建立首次基线")
            else -> append("检查完成，暂无新内容")
        }
        if (newlyDiscoveredCount > 0 && baselinedSourceCount > 0) {
            append("，另有 $baselinedSourceCount 个来源建立基线")
        }
        if (failedSourceCount > 0) {
            append("（$failedSourceCount 个来源刷新失败）")
        }
    }
}

/**
 * Reusable bounded-concurrency runner (`Semaphore(3)`) for refreshing [TagSubscription] and
 * [SavedSearch] updates into [SubscriptionRepository], shared by [SubscriptionsViewModel] and
 * `:app` background maintenance workers.
 */
class SubscriptionSyncRunner(
    private val subscriptionRepository: SubscriptionRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val preferencesRepository: PreferencesRepository,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val tagDictionary: TagDictionary = TagDictionary.default,
    private val mediaTagCacheRepository: MediaTagCacheRepository? = null,
) {
    private val baselinedSources: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun clearSourceBaseline(sourceId: String) {
        baselinedSources.remove(sourceId)
    }

    suspend fun syncAll(
        includeTags: Boolean = true,
        includeSavedSearches: Boolean = true,
        siteFilter: SiteId? = null,
        onSourceStarted: (String) -> Unit = {},
        onSourceFinished: (String, SourceCheckOutcome) -> Unit = { _, _ -> },
    ): SubscriptionSyncSummary = withContext(dispatchers.io) {
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
            return@withContext SubscriptionSyncSummary()
        }

        val settings = preferencesRepository.currentSettings()
        val blockedBySite = blockedTermsRepository.observeAllGrouped().first()
        val cachedTags = mediaTagCacheRepository?.observeAllTags()?.first().orEmpty()
        val semaphore = Semaphore(MAX_CONCURRENT_CHECKS)

        val outcomes = coroutineScope {
            val tagJobs = allSubs.map { sub ->
                val sourceId = subscriptionSourceId(sub)
                async(dispatchers.io) {
                    semaphore.withPermit {
                        onSourceStarted(sourceId)
                        val outcome = refreshTagSubscription(sub, settings, blockedBySite, cachedTags)
                        onSourceFinished(sourceId, outcome)
                        outcome
                    }
                }
            }
            val savedJobs = allSaved.map { saved ->
                val sourceId = saved.id
                async(dispatchers.io) {
                    semaphore.withPermit {
                        onSourceStarted(sourceId)
                        val outcome = refreshSavedSearch(saved, settings, blockedBySite, cachedTags)
                        onSourceFinished(sourceId, outcome)
                        outcome
                    }
                }
            }
            (tagJobs + savedJobs).awaitAll()
        }

        SubscriptionSyncSummary(
            totalSources = totalSources,
            newlyDiscoveredCount = outcomes.sumOf { it.newlyDiscovered },
            baselinedSourceCount = outcomes.count { it.establishedBaseline },
            failedSourceCount = outcomes.count { it.failed },
        )
    }

    suspend fun syncSingleTag(sub: TagSubscription): SourceCheckOutcome = withContext(dispatchers.io) {
        val settings = preferencesRepository.currentSettings()
        val blockedBySite = blockedTermsRepository.observeAllGrouped().first()
        val cachedTags = mediaTagCacheRepository?.observeAllTags()?.first().orEmpty()
        refreshTagSubscription(sub, settings, blockedBySite, cachedTags)
    }

    /**
     * Checks a specific logical subscription group (multiple per-site rows sharing slug + scope)
     * with the same bounded concurrency and shared baseline state as [syncAll].
     */
    suspend fun syncTags(
        subs: List<TagSubscription>,
        onSourceStarted: (String) -> Unit = {},
        onSourceFinished: (String, SourceCheckOutcome) -> Unit = { _, _ -> },
    ): SubscriptionSyncSummary = withContext(dispatchers.io) {
        if (subs.isEmpty()) return@withContext SubscriptionSyncSummary()

        val settings = preferencesRepository.currentSettings()
        val blockedBySite = blockedTermsRepository.observeAllGrouped().first()
        val cachedTags = mediaTagCacheRepository?.observeAllTags()?.first().orEmpty()
        val semaphore = Semaphore(MAX_CONCURRENT_CHECKS)

        val outcomes = coroutineScope {
            subs.map { sub ->
                val sourceId = subscriptionSourceId(sub)
                async(dispatchers.io) {
                    semaphore.withPermit {
                        onSourceStarted(sourceId)
                        val outcome = refreshTagSubscription(sub, settings, blockedBySite, cachedTags)
                        onSourceFinished(sourceId, outcome)
                        outcome
                    }
                }
            }.awaitAll()
        }

        SubscriptionSyncSummary(
            totalSources = subs.size,
            newlyDiscoveredCount = outcomes.sumOf { it.newlyDiscovered },
            baselinedSourceCount = outcomes.count { it.establishedBaseline },
            failedSourceCount = outcomes.count { it.failed },
        )
    }

    private suspend fun refreshTagSubscription(
        sub: TagSubscription,
        settings: AppSettings,
        blockedBySite: Map<SiteId, List<String>>,
        cachedTagsByKey: Map<String, List<TagRef>>,
    ): SourceCheckOutcome {
        val sourceId = subscriptionSourceId(sub)
        val plugin = siteRegistry.get(sub.siteId) ?: return SourceCheckOutcome(sourceId = sourceId, failed = true)
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
                SourceCheckOutcome(sourceId = sourceId, newlyDiscovered = 0, establishedBaseline = true)
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
                SourceCheckOutcome(sourceId = sourceId, newlyDiscovered = newlyFound.size)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            SourceCheckOutcome(sourceId = sourceId, failed = true)
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
                        language = saved.filters.siteLanguages[siteId]
                            ?: saved.language.takeUnless { it == ComicLanguage.ALL }
                            ?: settings.languageFor(siteId),
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
                return SourceCheckOutcome(sourceId = sourceId, failed = true)
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
                SourceCheckOutcome(sourceId = sourceId, newlyDiscovered = 0, establishedBaseline = true)
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
                SourceCheckOutcome(sourceId = sourceId, newlyDiscovered = newlyFound.size)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            SourceCheckOutcome(sourceId = sourceId, failed = true)
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
