package com.raven.app.core.database.model

import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import kotlinx.serialization.Serializable

/**
 * Last explore filter snapshot persisted per site in DataStore (`site_explore_filter_<siteId>`).
 */
@Serializable
data class ExploreFilterState(
    val section: String? = null,
    val category: String? = null,
    val sort: FeedSort = FeedSort.DEFAULT,
    val language: ComicLanguage = ComicLanguage.ALL,
    val advancedFilters: AdvancedSearchFilters = AdvancedSearchFilters(),
) {
    val isEmpty: Boolean
        get() = section.isNullOrBlank() &&
            category.isNullOrBlank() &&
            sort == FeedSort.DEFAULT &&
            language == ComicLanguage.ALL &&
            advancedFilters.isEmpty
}

/**
 * Persisted presentation preferences for the downloads center. Enum names are stored as strings so
 * older clients can safely fall back to defaults when a newer client introduces another option.
 */
@Serializable
data class DownloadViewPreferences(
    val sort: String = "RECENT",
    val groupMode: String = "NONE",
    val tagMatchAll: Boolean = false,
)

/**
 * Complete reactive snapshot of Preferences DataStore (`raven_settings.preferences_pb`).
 */
@Serializable
data class AppSettings(
    val activeSiteId: SiteId = DEFAULT_ACTIVE_SITE_ID,
    /** `null` keeps the last active site; a value is applied once on process cold start. */
    val startupDefaultSiteId: SiteId? = null,
    /**
     * Order of pinned sites. When [dynamicSiteSorting] is off this is also the displayed order.
     */
    val pinnedSiteIds: List<SiteId> = emptyList(),
    /**
     * `true` keeps the usage-frequency ordering (pinned + category groups sorted by visit count).
     * `false` keeps [pinnedSiteIds] in manual order and category groups in registry order.
     */
    val dynamicSiteSorting: Boolean = DEFAULT_DYNAMIC_SITE_SORTING,
    val crossSearchSiteIds: List<SiteId> = emptyList(),
    val siteOpenCounts: Map<SiteId, Int> = emptyMap(),
    val sectionOpenCounts: Map<SiteId, Map<String, Int>> = emptyMap(),
    val siteLanguages: Map<SiteId, ComicLanguage> = emptyMap(),
    val siteExploreFilters: Map<SiteId, ExploreFilterState> = emptyMap(),
    val translateTags: Boolean = true,
    val autoFullscreen: Boolean = false,
    val preferredQuality: String = DEFAULT_PREFERRED_QUALITY,
    val defaultPlaybackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val streamCacheEnabled: Boolean = false,
    val seekCacheEnabled: Boolean = true,
    val downloadUnmeteredOnly: Boolean = false,
    val downloadView: DownloadViewPreferences = DownloadViewPreferences(),
    val storageLocation: StorageLocation = StorageLocation.PRIVATE,
    val pendingStorageLocation: StorageLocation? = null,
    val reduceTransparency: Boolean = false,
    val uiSoundEffectsEnabled: Boolean = true,
    val autoUpdateCheck: Boolean = true,
    val siteSwitchTutorialSeen: Boolean = false,
    val seenTutorials: Set<String> = emptySet(),
    val tagDictAutoSync: Boolean = true,
    val tagDictEtag: String = "",
    val tagDictReleaseTag: String = "",
    val tagDictUpdatedAt: Long = 0L,
    val blockTagsGlobally: Boolean = true,
    // Phase B / C playback, reader & background sync settings
    val autoplayVideo: Boolean = true,
    /** Automatically advances sequential playback queues after a short foreground countdown. */
    val autoContinueQueue: Boolean = false,
    val backgroundAudioPlayback: Boolean = false,
    val readerTapZonesEnabled: Boolean = true,
    val readerShowStatusBar: Boolean = false,
    val readerPrefetchPages: Int = DEFAULT_READER_PREFETCH_PAGES,
    val readerMemoryBudgetMb: Int = DEFAULT_READER_MEMORY_BUDGET_MB,
    val readerAutoSplitWidePages: Boolean = false,
    val imageLoadConcurrency: Int = DEFAULT_IMAGE_CONCURRENCY,
    val backgroundSubscriptionSync: Boolean = true,
    // Phase D privacy, app lock, incognito & WebDAV sync metadata
    val privacyScreenSecure: Boolean = false,
    val appLockEnabled: Boolean = false,
    val appLockTimeoutSeconds: Int = 0,
    val incognitoMode: Boolean = false,
    val webDavLastSyncAt: Long = 0L,
) {
    fun languageFor(siteId: SiteId): ComicLanguage = siteLanguages[siteId] ?: ComicLanguage.ALL

    fun exploreFilterFor(siteId: SiteId): ExploreFilterState =
        siteExploreFilters[siteId] ?: ExploreFilterState()

    fun siteOpenCount(siteId: SiteId): Int = siteOpenCounts[siteId] ?: 0

    fun hasSeenTutorial(tutorialId: String): Boolean = tutorialId in seenTutorials

    fun sectionOpenCount(siteId: SiteId, sectionId: String): Int =
        sectionOpenCounts[siteId]?.get(sectionId) ?: 0

    fun <T> sortSitesByUsage(sites: List<T>, siteIdSelector: (T) -> SiteId): List<T> {
        if (siteOpenCounts.isEmpty() || sites.size <= 1) return sites
        return sites.sortedByDescending { siteOpenCount(siteIdSelector(it)) }
    }

    fun <T> sortSectionsByUsage(
        siteId: SiteId,
        sections: List<T>,
        sectionIdSelector: (T) -> String,
    ): List<T> {
        val counts = sectionOpenCounts[siteId]
        if (counts.isNullOrEmpty() || sections.size <= 1) return sections
        return sections.sortedByDescending { counts[sectionIdSelector(it)] ?: 0 }
    }

    fun sortMediaKindsByUsage(
        sites: List<SiteDescriptor>,
        defaultOrder: List<MediaKind> = DEFAULT_MEDIA_KIND_ORDER,
    ): List<MediaKind> {
        if (siteOpenCounts.isEmpty()) return defaultOrder
        val groupTotals = LinkedHashMap<MediaKind, Long>(defaultOrder.size)
        for (site in sites) {
            val count = siteOpenCount(site.id)
            if (count > 0) {
                groupTotals[site.primaryKind] = (groupTotals[site.primaryKind] ?: 0L) + count
            }
        }
        if (groupTotals.isEmpty()) return defaultOrder
        return defaultOrder.sortedByDescending { groupTotals[it] ?: 0L }
    }

    companion object {
        val DEFAULT_ACTIVE_SITE_ID: SiteId = SiteId.HANIME1
        val DEFAULT_MEDIA_KIND_ORDER: List<MediaKind> = listOf(
            MediaKind.VIDEO,
            MediaKind.COMIC,
            MediaKind.SERIAL_COMIC,
            MediaKind.PHOTO,
        )
        const val DEFAULT_PREFERRED_QUALITY: String = "auto"
        const val DEFAULT_DYNAMIC_SITE_SORTING: Boolean = true
        const val DEFAULT_PLAYBACK_SPEED: Float = 1.0f
        const val MIN_PLAYBACK_SPEED: Float = 0.25f
        const val MAX_PLAYBACK_SPEED: Float = 3.0f
        const val DEFAULT_READER_PREFETCH_PAGES: Int = 3
        const val MIN_READER_PREFETCH_PAGES: Int = 1
        const val MAX_READER_PREFETCH_PAGES: Int = 6
        const val DEFAULT_READER_MEMORY_BUDGET_MB: Int = 24
        const val MIN_READER_MEMORY_BUDGET_MB: Int = 16
        const val MAX_READER_MEMORY_BUDGET_MB: Int = 96
        const val DEFAULT_IMAGE_CONCURRENCY: Int = 3
        const val MIN_IMAGE_CONCURRENCY: Int = 2
        const val MAX_IMAGE_CONCURRENCY: Int = 3
    }
}

/**
 * Cross-process browse scroll restoration anchor persisted in `browse_positions` (capped at 32).
 */
@Serializable
data class BrowsePosition(
    val positionKey: String,
    val siteId: SiteId? = null,
    val workId: String,
    val throughPage: Int,
    val shelfId: String = "",
    val updatedAt: Long,
)

/**
 * Shelf paired with lightweight aggregate counts and preview covers for Library cards.
 */
data class ShelfWithSummary(
    val shelf: Shelf,
    val itemCount: Int,
    val completedCount: Int,
    val previewCoverUrls: List<String> = emptyList(),
)

/**
 * Evaluated dynamic entry produced by `SmartShelfEvaluator`.
 */
data class SmartShelfEntry(
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val title: String,
    val coverUrl: String = "",
    val kind: MediaKind,
    val lastActivityAt: Long = 0L,
    val addedAt: Long = 0L,
    val progressFraction: Float? = null,
    val started: Boolean = false,
    val completed: Boolean = false,
    val isOffline: Boolean = false,
    val downloadStatus: DownloadStatus? = null,
) {
    val compositeKey: String get() = "${siteId.value}:$mediaId"
}

/**
 * Structured preview counts shown before applying an encrypted backup restore.
 */
data class BackupSummary(
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val watchHistoryCount: Int,
    val readingHistoryCount: Int,
    val favoritesCount: Int,
    val shelvesCount: Int,
    val shelfItemsCount: Int,
    val smartShelvesCount: Int,
    val pageBookmarksCount: Int,
    val timeBookmarksCount: Int,
    val imageFavoritesCount: Int,
    val tagSubscriptionsCount: Int,
    val savedSearchesCount: Int,
    val updateInboxCount: Int,
    val blockedTermsCount: Int,
    val searchHistoryCount: Int,
    val customTagTranslationsCount: Int = 0,
) {
    val totalHistoryCount: Int get() = watchHistoryCount + readingHistoryCount
    val totalBookmarksCount: Int get() = pageBookmarksCount + timeBookmarksCount
}

/**
 * Live entry counts across all three persisted/bundled tag dictionary tiers.
 */
data class TagDictionaryStats(
    val builtInCount: Int,
    val communityCount: Int,
    val customCount: Int,
) {
    val totalCount: Int get() = builtInCount + communityCount + customCount
}
