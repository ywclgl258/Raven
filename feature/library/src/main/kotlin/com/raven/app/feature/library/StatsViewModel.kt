package com.raven.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.domain.CompletionPolicy
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Per-site aggregated activity breakdown shown in [StatsScreen].
 */
data class SiteActivityStat(
    val siteId: SiteId,
    val siteName: String,
    val watchedCount: Int,
    val watchDurationMs: Long,
    val readCount: Int,
    val readPages: Int,
    val favoriteCount: Int,
    val completedDownloads: Int,
) {
    val totalInteractions: Int get() = watchedCount + readCount + favoriteCount + completedDownloads
}

/**
 * Localized high-frequency tag stat aggregated from cached tags of engaged works.
 */
data class TagFrequencyStat(
    val slug: String,
    val label: String,
    val count: Int,
)

data class StatsUiState(
    val totalWatchDurationMs: Long = 0L,
    val totalWatchedCount: Int = 0,
    val completedWatchCount: Int = 0,
    val totalReadPages: Int = 0,
    val totalReadCount: Int = 0,
    val completedReadCount: Int = 0,
    val favoriteCount: Int = 0,
    val shelfCount: Int = 0,
    val downloadCompletedCount: Int = 0,
    val downloadTotalBytes: Long = 0L,
    val siteBreakdowns: List<SiteActivityStat> = emptyList(),
    val topTags: List<TagFrequencyStat> = emptyList(),
)

/**
 * Reactive personal analytics ViewModel aggregating watch duration, reading volume,
 * per-site distribution, offline storage footprint, and top recurring tags.
 */
class StatsViewModel(
    historyRepository: HistoryRepository,
    favoriteRepository: FavoriteRepository,
    shelfRepository: ShelfRepository,
    downloadGateway: LibraryDownloadGateway,
    mediaTagCacheRepository: MediaTagCacheRepository,
    private val siteRegistry: SiteRegistry,
    private val tagDictionary: TagDictionary = TagDictionary.default,
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = combine(
        combine(
            historyRepository.observeAllWatchProgress(),
            historyRepository.observeAllReadingProgress(),
            favoriteRepository.observeAll(),
        ) { watches, readings, favorites ->
            Triple(watches, readings, favorites)
        },
        combine(
            shelfRepository.observeShelvesWithSummary(),
            downloadGateway.downloads,
            mediaTagCacheRepository.observeAllTags(),
        ) { shelves, downloads, cachedTags ->
            Triple(shelves, downloads, cachedTags)
        },
    ) { (watches, readings, favorites), (shelves, downloads, cachedTags) ->
        val completedDownloads = downloads.filter { it.status == DownloadStatus.COMPLETED }
        val involvedKeys = buildSet {
            watches.forEach { add("${it.siteId.value}:${it.mediaId}") }
            readings.forEach { add("${it.siteId.value}:${it.mediaId}") }
            favorites.forEach { add("${it.siteId.value}:${it.targetId}") }
        }

        val siteIds = buildSet {
            watches.forEach { add(it.siteId) }
            readings.forEach { add(it.siteId) }
            favorites.forEach { add(it.siteId) }
            completedDownloads.forEach { add(it.siteId) }
        }
        val watchBySite = watches.groupBy { it.siteId }
        val readBySite = readings.groupBy { it.siteId }
        val favBySite = favorites.groupBy { it.siteId }
        val dlBySite = completedDownloads.groupBy { it.siteId }

        val breakdowns = siteIds.map { siteId ->
            val siteWatches = watchBySite[siteId].orEmpty()
            val siteReads = readBySite[siteId].orEmpty()
            SiteActivityStat(
                siteId = siteId,
                siteName = siteRegistry.displayNameFor(siteId),
                watchedCount = siteWatches.size,
                watchDurationMs = siteWatches.sumOf { it.positionMs.coerceAtLeast(0L) },
                readCount = siteReads.size,
                readPages = siteReads.sumOf { it.currentPage.coerceAtLeast(0) },
                favoriteCount = favBySite[siteId].orEmpty().size,
                completedDownloads = dlBySite[siteId].orEmpty().size,
            )
        }.sortedByDescending { it.totalInteractions }

        val tagCounts = HashMap<String, Pair<String, Int>>()
        for (key in involvedKeys) {
            val refs = cachedTags[key].orEmpty()
            val seenSlugs = HashSet<String>()
            for (tag in refs) {
                val slug = tag.slug.trim().lowercase()
                if (slug.isEmpty() || !seenSlugs.add(slug)) continue
                val label = tagDictionary.displayLabel(slug, tag.label.ifBlank { slug })
                val current = tagCounts[slug]
                tagCounts[slug] = (current?.first ?: label) to ((current?.second ?: 0) + 1)
            }
        }
        val topTags = tagCounts.entries
            .map { (slug, pair) -> TagFrequencyStat(slug = slug, label = pair.first, count = pair.second) }
            .sortedWith(compareByDescending<TagFrequencyStat> { it.count }.thenBy { it.label })
            .take(16)

        StatsUiState(
            totalWatchDurationMs = watches.sumOf { it.positionMs.coerceAtLeast(0L) },
            totalWatchedCount = watches.size,
            completedWatchCount = watches.count { CompletionPolicy.isWatchCompleted(it) },
            totalReadPages = readings.sumOf { it.currentPage.coerceAtLeast(0) },
            totalReadCount = readings.size,
            completedReadCount = readings.count { CompletionPolicy.isReadingCompleted(it) },
            favoriteCount = favorites.size,
            shelfCount = shelves.size,
            downloadCompletedCount = completedDownloads.size,
            downloadTotalBytes = completedDownloads.sumOf { (it.totalBytes ?: it.downloadedBytes).coerceAtLeast(0L) },
            siteBreakdowns = breakdowns,
            topTags = topTags,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = StatsUiState(),
    )
}
