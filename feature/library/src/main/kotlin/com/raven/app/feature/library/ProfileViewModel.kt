package com.raven.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.domain.CompletionPolicy
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Unified unfinished watch/reading activity item displayed on the Profile hub.
 */
data class ProfileContinueItem(
    val siteId: SiteId,
    val siteLabel: String,
    val mediaId: String,
    val url: String,
    val kind: MediaKind,
    val title: String,
    val coverUrl: String,
    val progressFraction: Float,
    val resumeLabel: String,
    val updatedAt: Long,
    val resumePage: Int = 1,
    val mediaItem: MediaItem,
) {
    val compositeKey: String get() = "${siteId.value}:$mediaId"
}

enum class ProfileHeroSource {
    CONTINUE_ACTIVITY,
    WATCH_LATER_FALLBACK,
}

/**
 * Hero Feature Card model for [ProfileScreen]: prioritizes the newest unfinished work matching the
 * active [LibraryMediaFilter], automatically skipping completed works and falling back to Watch Later.
 */
data class ProfileHeroItem(
    val source: ProfileHeroSource,
    val siteId: SiteId,
    val siteLabel: String,
    val mediaId: String,
    val url: String,
    val kind: MediaKind,
    val title: String,
    val coverUrl: String,
    val progressFraction: Float?,
    val statusBadge: String,
    val resumeCaption: String,
    val ctaLabel: String,
    val resumePage: Int = 1,
    val mediaItem: MediaItem,
) {
    val compositeKey: String get() = "${siteId.value}:$mediaId"
}

data class ProfileUiState(
    val selectedFilter: LibraryMediaFilter = LibraryMediaFilter.ALL,
    val activeSite: SiteDescriptor? = null,
    val heroItem: ProfileHeroItem? = null,
    val continueItems: List<ProfileContinueItem> = emptyList(),
    val watchLaterItems: List<ShelfItem> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val unreadInboxCount: Int = 0,
    val activeDownloadCount: Int = 0,
    val completedDownloadCount: Int = 0,
    val totalHistoryCount: Int = 0,
    val totalFavoriteCount: Int = 0,
    val siteLabels: Map<SiteId, String> = emptyMap(),
) {
    val hasAnyContent: Boolean
        get() = heroItem != null || continueItems.isNotEmpty() || watchLaterItems.isNotEmpty() || savedSearches.isNotEmpty()
}

private data class ProfileActivitySnapshot(
    val watches: List<WatchProgress>,
    val readings: List<ReadingProgress>,
    val watchLater: List<ShelfItem>,
)

private data class ProfileStatsSnapshot(
    val savedSearches: List<SavedSearch>,
    val unreadInboxCount: Int,
    val activeDownloadCount: Int,
    val completedDownloadCount: Int,
    val favoriteCount: Int,
)

class ProfileViewModel(
    private val historyRepository: HistoryRepository,
    private val shelfRepository: ShelfRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val favoriteRepository: FavoriteRepository,
    private val downloadGateway: LibraryDownloadGateway,
    private val preferencesRepository: PreferencesRepository,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val selectedFilter = MutableStateFlow(LibraryMediaFilter.ALL)

    private val activityFlow = combine(
        historyRepository.observeAllWatchProgress(),
        historyRepository.observeAllReadingProgress(),
        shelfRepository.observeShelfItems(Shelf.WATCH_LATER_ID),
    ) { watches, readings, watchLater ->
        ProfileActivitySnapshot(
            watches = watches,
            readings = readings,
            watchLater = watchLater,
        )
    }

    private val statsFlow = combine(
        subscriptionRepository.observeSavedSearches(),
        subscriptionRepository.observeUnreadCount(),
        downloadGateway.downloads,
        favoriteRepository.observeAll(),
    ) { savedSearches, unreadCount, downloads, favorites ->
        ProfileStatsSnapshot(
            savedSearches = savedSearches,
            unreadInboxCount = unreadCount,
            activeDownloadCount = downloads.count { it.status.isActiveTransfer() },
            completedDownloadCount = downloads.count { it.status == DownloadStatus.COMPLETED },
            favoriteCount = favorites.size,
        )
    }

    val uiState: StateFlow<ProfileUiState> = combine(
        selectedFilter,
        activityFlow,
        statsFlow,
        preferencesRepository.settings,
    ) { filter, activity, stats, settings ->
        buildUiState(
            filter = filter,
            activity = activity,
            stats = stats,
            activeSiteId = settings.activeSiteId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ProfileUiState(),
    )

    fun selectFilter(filter: LibraryMediaFilter) {
        selectedFilter.value = filter
    }

    fun removeFromWatchLater(siteId: SiteId, mediaId: String) {
        viewModelScope.launch(dispatchers.io) {
            shelfRepository.removeItem(Shelf.WATCH_LATER_ID, siteId, mediaId)
        }
    }

    fun deleteSavedSearch(id: String) {
        viewModelScope.launch(dispatchers.io) {
            subscriptionRepository.deleteSavedSearch(id)
        }
    }

    private fun buildUiState(
        filter: LibraryMediaFilter,
        activity: ProfileActivitySnapshot,
        stats: ProfileStatsSnapshot,
        activeSiteId: SiteId,
    ): ProfileUiState {
        val kindFilter = filter.mediaKind

        val unfinishedWatches = activity.watches
            .asSequence()
            .filter { CompletionPolicy.isWatchStarted(it) && !CompletionPolicy.isWatchCompleted(it) }
            .map { watch ->
                ProfileContinueItem(
                    siteId = watch.siteId,
                    siteLabel = siteRegistry.displayNameFor(watch.siteId),
                    mediaId = watch.mediaId,
                    url = watch.url,
                    kind = MediaKind.VIDEO,
                    title = watch.title,
                    coverUrl = watch.coverUrl,
                    progressFraction = watch.progressFraction,
                    resumeLabel = watch.resumeCaption(),
                    updatedAt = watch.updatedAt,
                    resumePage = 1,
                    mediaItem = watch.toMediaItem(),
                )
            }

        val unfinishedReadings = activity.readings
            .asSequence()
            .filter { CompletionPolicy.isReadingStarted(it) && !CompletionPolicy.isReadingCompleted(it) }
            .map { reading ->
                ProfileContinueItem(
                    siteId = reading.siteId,
                    siteLabel = siteRegistry.displayNameFor(reading.siteId),
                    mediaId = reading.mediaId,
                    url = reading.url,
                    kind = reading.kind,
                    title = reading.title,
                    coverUrl = reading.coverUrl,
                    progressFraction = reading.progressFraction,
                    resumeLabel = reading.resumeCaption(),
                    updatedAt = reading.updatedAt,
                    resumePage = reading.currentPage.coerceAtLeast(1),
                    mediaItem = reading.toMediaItem(),
                )
            }

        val allContinue = (unfinishedWatches + unfinishedReadings)
            .sortedByDescending { it.updatedAt }
            .filter { kindFilter == null || it.kind == kindFilter }
            .toList()

        val filteredWatchLater = activity.watchLater
            .filter { kindFilter == null || it.kind == kindFilter }

        val heroItem = resolveHeroItem(
            continueItems = allContinue,
            watchLaterItems = filteredWatchLater,
        )

        val siteLabels = buildMap {
            allContinue.forEach { put(it.siteId, it.siteLabel) }
            filteredWatchLater.forEach { put(it.siteId, siteRegistry.displayNameFor(it.siteId)) }
            stats.savedSearches.forEach { search ->
                search.siteIds.forEach { id -> put(id, siteRegistry.displayNameFor(id)) }
            }
        }

        return ProfileUiState(
            selectedFilter = filter,
            activeSite = siteRegistry.get(activeSiteId)?.descriptor,
            heroItem = heroItem,
            continueItems = allContinue,
            watchLaterItems = filteredWatchLater,
            savedSearches = stats.savedSearches,
            unreadInboxCount = stats.unreadInboxCount,
            activeDownloadCount = stats.activeDownloadCount,
            completedDownloadCount = stats.completedDownloadCount,
            totalHistoryCount = activity.watches.size + activity.readings.size,
            totalFavoriteCount = stats.favoriteCount,
            siteLabels = siteLabels,
        )
    }

    private fun resolveHeroItem(
        continueItems: List<ProfileContinueItem>,
        watchLaterItems: List<ShelfItem>,
    ): ProfileHeroItem? {
        val topContinue = continueItems.firstOrNull()
        if (topContinue != null) {
            return ProfileHeroItem(
                source = ProfileHeroSource.CONTINUE_ACTIVITY,
                siteId = topContinue.siteId,
                siteLabel = topContinue.siteLabel,
                mediaId = topContinue.mediaId,
                url = topContinue.url,
                kind = topContinue.kind,
                title = topContinue.title,
                coverUrl = topContinue.coverUrl,
                progressFraction = topContinue.progressFraction.takeIf { it > 0f },
                statusBadge = if (topContinue.kind.isVideo) "继续观看" else "继续阅读",
                resumeCaption = "${topContinue.siteLabel} · ${topContinue.resumeLabel}",
                ctaLabel = if (topContinue.kind.isVideo) "继续观看" else "继续阅读",
                resumePage = topContinue.resumePage,
                mediaItem = topContinue.mediaItem,
            )
        }

        val fallback = watchLaterItems.firstOrNull { !it.completed } ?: watchLaterItems.firstOrNull()
        if (fallback != null) {
            val siteLabel = siteRegistry.displayNameFor(fallback.siteId)
            return ProfileHeroItem(
                source = ProfileHeroSource.WATCH_LATER_FALLBACK,
                siteId = fallback.siteId,
                siteLabel = siteLabel,
                mediaId = fallback.mediaId,
                url = fallback.url,
                kind = fallback.kind,
                title = fallback.title,
                coverUrl = fallback.coverUrl,
                progressFraction = null,
                statusBadge = "稍后看推荐",
                resumeCaption = "$siteLabel · ${fallback.kind.displayLabel()}",
                ctaLabel = if (fallback.kind.isVideo) "开始观看" else "开始阅读",
                resumePage = 1,
                mediaItem = fallback.toMediaItem(),
            )
        }

        return null
    }
}
