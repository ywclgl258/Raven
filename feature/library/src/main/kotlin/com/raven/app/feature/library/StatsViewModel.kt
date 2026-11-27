package com.raven.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.domain.CompletionPolicy
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * Time window applied to local progress, favorites, completed downloads, and shelf activity
 * before aggregating [StatsUiState]. Comparing timestamps to [startEpochMs] keeps the filter
 * deterministic with an injected clock and avoids collecting a separate repository copy.
 */
enum class StatsTimeRange(
    val label: String,
    private val lookbackMs: Long?,
) {
    ALL("全部", null),
    LAST_7_DAYS("近 7 天", 7L * DAY_MILLIS),
    LAST_30_DAYS("近 30 天", 30L * DAY_MILLIS);

    internal fun startEpochMs(nowMs: Long): Long? = lookbackMs?.let { nowMs - it }
}

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
    val isLoading: Boolean = true,
    val selectedRange: StatsTimeRange = StatsTimeRange.ALL,
    val hasAnyData: Boolean = false,
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

private data class StatsLibrarySnapshot(
    val shelves: List<ShelfWithSummary>,
    val downloads: List<DownloadItem>,
    val cachedTags: Map<String, List<TagRef>>,
    val range: StatsTimeRange,
)

/**
 * Reactive personal analytics ViewModel aggregating watch progress duration, reading progress
 * pages, per-site distribution, offline storage footprint, and top recurring tags.
 */
class StatsViewModel(
    historyRepository: HistoryRepository,
    favoriteRepository: FavoriteRepository,
    shelfRepository: ShelfRepository,
    downloadGateway: LibraryDownloadGateway,
    mediaTagCacheRepository: MediaTagCacheRepository,
    private val siteRegistry: SiteRegistry,
    private val tagDictionary: TagDictionary = TagDictionary.default,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val selectedRange = MutableStateFlow(StatsTimeRange.ALL)

    fun selectRange(range: StatsTimeRange) {
        selectedRange.value = range
    }

    val uiState: StateFlow<StatsUiState> = combine(
        combine(
            historyRepository.observeAllWatchProgress(),
            historyRepository.observeAllReadingProgress(),
            historyRepository.observeReadingHistory(),
            favoriteRepository.observeAll(),
        ) { watches, rawReadings, workReadings, favorites ->
            Triple(watches, Pair(rawReadings, workReadings), favorites)
        },
        combine(
            shelfRepository.observeShelvesWithSummary(),
            downloadGateway.downloads,
            mediaTagCacheRepository.observeAllTags(),
            selectedRange,
        ) { shelves, downloads, cachedTags, range ->
            StatsLibrarySnapshot(
                shelves = shelves,
                downloads = downloads,
                cachedTags = cachedTags,
                range = range,
            )
        },
    ) { (watches, readingPair, favorites), library ->
        val (rawReadings, workReadings) = readingPair
        val cutoff = library.range.startEpochMs(clock())

        fun inRange(epochMs: Long): Boolean = cutoff == null || epochMs >= cutoff

        val rangedWatches = watches.filter { inRange(it.updatedAt) }
        val rangedRawReadings = rawReadings.filter { inRange(it.updatedAt) }
        val rangedWorkReadings = workReadings.filter { inRange(it.updatedAt) }
        val rangedFavorites = favorites.filter { inRange(it.addedAt) }
        val allCompletedDownloads = library.downloads.filter { it.status == DownloadStatus.COMPLETED }
        val completedDownloads = allCompletedDownloads.filter { inRange(it.updatedAt) }
        val hasAnyData = watches.isNotEmpty() ||
            rawReadings.isNotEmpty() ||
            favorites.isNotEmpty() ||
            allCompletedDownloads.isNotEmpty()

        val involvedKeys = buildSet {
            rangedWatches.forEach { add("${it.siteId.value}:${it.mediaId}") }
            rangedRawReadings.forEach { add("${it.siteId.value}:${it.mediaId}") }
            rangedFavorites.forEach { add("${it.siteId.value}:${it.targetId}") }
        }

        val siteIds = buildSet {
            rangedWatches.forEach { add(it.siteId) }
            rangedRawReadings.forEach { add(it.siteId) }
            rangedFavorites.forEach { add(it.siteId) }
            completedDownloads.forEach { add(it.siteId) }
        }
        val watchBySite = rangedWatches.groupBy { it.siteId }
        val readBySite = rangedRawReadings.groupBy { it.siteId }
        val workBySite = rangedWorkReadings.groupBy { it.siteId }
        val favBySite = rangedFavorites.groupBy { it.siteId }
        val dlBySite = completedDownloads.groupBy { it.siteId }

        val breakdowns = siteIds.map { siteId ->
            val siteWatches = watchBySite[siteId].orEmpty()
            val siteWorkReads = workBySite[siteId].orEmpty()
            val siteReads = readBySite[siteId].orEmpty()
            SiteActivityStat(
                siteId = siteId,
                siteName = siteRegistry.displayNameFor(siteId),
                watchedCount = siteWatches.size,
                watchDurationMs = siteWatches.sumOf { it.positionMs.coerceAtLeast(0L) },
                readCount = siteWorkReads.size,
                readPages = siteReads.sumOf { it.currentPage.coerceAtLeast(0) },
                favoriteCount = favBySite[siteId].orEmpty().size,
                completedDownloads = dlBySite[siteId].orEmpty().size,
            )
        }.sortedByDescending { it.totalInteractions }

        val tagCounts = HashMap<String, Pair<String, Int>>()
        for (key in involvedKeys) {
            val refs = library.cachedTags[key].orEmpty()
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
            isLoading = false,
            selectedRange = library.range,
            hasAnyData = hasAnyData,
            totalWatchDurationMs = rangedWatches.sumOf { it.positionMs.coerceAtLeast(0L) },
            totalWatchedCount = rangedWatches.size,
            completedWatchCount = rangedWatches.count { CompletionPolicy.isWatchCompleted(it) },
            totalReadPages = rangedRawReadings.sumOf { it.currentPage.coerceAtLeast(0) },
            totalReadCount = rangedWorkReadings.size,
            completedReadCount = rangedWorkReadings.count { it.isWorkCompleted() },
            favoriteCount = rangedFavorites.size,
            shelfCount = library.shelves.count {
                cutoff == null || it.shelf.modifiedAt >= cutoff
            },
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
