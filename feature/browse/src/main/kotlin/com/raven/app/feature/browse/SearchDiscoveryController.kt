package com.raven.app.feature.browse

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovery sidebar data: per-site trending works with a 10-minute in-memory cache.
 *
 * Extracted from [SearchViewModel]; the ViewModel keeps the tag-ranking projection that is
 * derived inside its UI-state combine.
 */

data class SiteDiscoverySnapshot(
    val works: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val fetchedAtMs: Long = 0L,
)

internal class SearchDiscoveryController(
    private val dispatchers: AppDispatchers,
    private val siteRegistry: SiteRegistry,
    private val activeSiteIdFlow: Flow<SiteId>,
) {
    private val cache = ConcurrentHashMap<SiteId, SiteDiscoverySnapshot>()

    val flow: Flow<SiteDiscoverySnapshot> = activeSiteIdFlow.flatMapLatest { siteId ->
        flow {
            val now = System.currentTimeMillis()
            val cached = cache[siteId]
            if (cached != null && (now - cached.fetchedAtMs) < SearchViewModel.DISCOVERY_CACHE_TTL_MS) {
                emit(cached)
                return@flow
            }
            val plugin = siteRegistry.get(siteId)
            val canFetch = plugin?.feedSource != null
            if (!canFetch) {
                emit(SiteDiscoverySnapshot(works = emptyList(), isLoading = false, fetchedAtMs = now))
                return@flow
            }
            emit(SiteDiscoverySnapshot(works = cached?.works.orEmpty(), isLoading = true, fetchedAtMs = 0L))
            val fetched = withContext(dispatchers.io) {
                fetchWorks(plugin)
            }
            val snapshot = SiteDiscoverySnapshot(
                works = fetched,
                isLoading = false,
                fetchedAtMs = System.currentTimeMillis(),
            )
            if (fetched.isNotEmpty()) {
                cache[siteId] = snapshot
            }
            emit(snapshot)
        }
    }


    private suspend fun fetchWorks(plugin: SitePlugin): List<MediaItem> {
        val siteId = plugin.id
        try {
            val home = plugin.feedSource?.loadHome()
            if (home != null) {
                val combined = buildList {
                    home.sections.firstOrNull { it.isRanked }?.items?.let(::addAll)
                    home.sections.forEach { addAll(it.items) }
                    addAll(home.heroItems)
                    home.primaryFeedPage?.items?.let(::addAll)
                }
                if (combined.isNotEmpty()) {
                    return combined.distinctBy { it.compositeKey }.take(20)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Fall through to popular feed lookup
        }
        return try {
            val page = plugin.feedSource?.loadFeed(
                FeedRequest(
                    siteId = siteId,
                    sort = FeedSort.POPULAR,
                    page = 1,
                ),
            )
            page?.items.orEmpty().distinctBy { it.compositeKey }.take(20)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
