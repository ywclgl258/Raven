package com.raven.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WatchProgress(
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val title: String,
    val coverUrl: String = "",
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean = false,
    val quality: String? = null,
) {
    val compositeKey: String get() = "${siteId.value}:$mediaId"
    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

@Serializable
data class ReadingProgress(
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val title: String,
    val coverUrl: String = "",
    val kind: MediaKind = MediaKind.COMIC,
    val currentPage: Int,
    val totalPages: Int,
    val scrollOffsetPx: Int = 0,
    val updatedAt: Long,
    val completed: Boolean = false,
) {
    val compositeKey: String get() = "${siteId.value}:$mediaId"
    val progressFraction: Float
        get() = if (totalPages <= 0) 0f else (currentPage.toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
}

@Serializable
enum class FavoriteKind {
    VIDEO,
    COMIC,
    PHOTO,
    TAG,
    AUTHOR,
    CHARACTER,
    ;

    val isMediaWork: Boolean
        get() = this == VIDEO || this == COMIC || this == PHOTO
}

@Serializable
data class LikedMedia(
    val siteId: SiteId,
    val mediaId: String,
    val kind: MediaKind,
    val title: String,
    val url: String,
    val coverUrl: String = "",
    val authorName: String = "",
    val tags: List<TagRef> = emptyList(),
    val likedAt: Long = 0L,
) {
    val compositeKey: String get() = "${siteId.value}:$mediaId"
}

@Serializable
data class LikedTagWeight(
    val tag: TagRef,
    val weight: Double,
    val normalizedWeight: Float,
    val likeCount: Int,
    val lastLikedAt: Long,
)

/**
 * Stable identifiers for first-use interactive feature tutorials persisted in DataStore.
 */
object FeatureTutorialIds {
    const val HOME_BROWSE: String = "home_browse"
    const val DETAIL_ACTIONS: String = "detail_actions"
    const val READER_SERIAL: String = "reader_serial"
    const val LIBRARY_HUB: String = "library_hub"
    const val SEARCH_DISCOVER: String = "search_discover"
    const val SUBSCRIPTIONS_TAGS: String = "subscriptions_tags"

    val ALL: Set<String> = setOf(
        HOME_BROWSE,
        DETAIL_ACTIONS,
        READER_SERIAL,
        LIBRARY_HUB,
        SEARCH_DISCOVER,
        SUBSCRIPTIONS_TAGS,
    )
}

@Serializable
data class FavoriteItem(
    val siteId: SiteId,
    val targetId: String,
    val kind: FavoriteKind,
    val title: String,
    val url: String,
    val coverUrl: String = "",
    val addedAt: Long,
    val extra: Map<String, String> = emptyMap(),
) {
    val compositeKey: String get() = "${siteId.value}:${kind.name}:$targetId"
}

@Serializable
data class Shelf(
    val id: String,
    val name: String,
    val note: String = "",
    val isPinned: Boolean = false,
    val isSystem: Boolean = false,
    val coverCompositeKey: String? = null,
    val createdAt: Long = 0L,
    val modifiedAt: Long = createdAt,
) {
    companion object {
        const val WATCH_LATER_ID: String = "watch-later"
    }
}

@Serializable
data class ShelfItem(
    val shelfId: String,
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val kind: MediaKind,
    val title: String,
    val coverUrl: String = "",
    val position: Int = 0,
    val addedAt: Long = 0L,
    val completed: Boolean = false,
    val durationSeconds: Int? = null,
    val pageCount: Int? = null,
    val completionChangedAt: Long? = null,
) {
    val compositeKey: String get() = "${siteId.value}:$mediaId"
}

@Serializable
enum class SmartShelfRule {
    UNFINISHED,
    OFFLINE_UNSEEN,
    RECENT,
}

@Serializable
data class SmartShelf(
    val id: String,
    val name: String,
    val rule: SmartShelfRule,
    val mediaKindFilter: MediaKind? = null,
    val siteIdFilter: SiteId? = null,
    val isPinned: Boolean = false,
    val createdAt: Long = 0L,
)

@Serializable
data class PageBookmark(
    val id: String,
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val title: String,
    val coverUrl: String = "",
    val page: Int,
    val totalPages: Int = 0,
    val scrollOffsetPx: Int = 0,
    val note: String = "",
    val createdAt: Long = 0L,
)

@Serializable
data class TimeBookmark(
    val id: String,
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val title: String,
    val coverUrl: String = "",
    val positionMs: Long,
    val durationMs: Long = 0L,
    val label: String = "",
    val groupName: String = "",
    val createdAt: Long = 0L,
)

@Serializable
data class ImageFavorite(
    val id: String,
    val siteId: SiteId,
    val mediaId: String,
    val mediaTitle: String,
    val pageUrl: String,
    val imageUrl: String,
    val pageIndex: Int,
    val addedAt: Long = 0L,
)

@Serializable
data class TagSubscription(
    val siteId: SiteId,
    val slug: String,
    val label: String,
    val url: String,
    val scope: SearchScope = SearchScope.TAG,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
)

@Serializable
data class SavedSearch(
    val id: String,
    val name: String,
    val query: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val siteIds: List<SiteId>,
    val mediaKind: MediaKind? = null,
    val sort: FeedSort = FeedSort.NEWEST,
    val language: ComicLanguage = ComicLanguage.ALL,
    val filters: AdvancedSearchFilters = AdvancedSearchFilters(),
    val createdAt: Long = 0L,
    val lastCheckedAt: Long? = null,
)

@Serializable
enum class InboxSourceKind {
    TAG_SUBSCRIPTION,
    SAVED_SEARCH,
}

@Serializable
data class UpdateInboxItem(
    val id: String,
    val sourceKind: InboxSourceKind,
    val sourceId: String,
    val sourceLabel: String,
    val item: MediaItem,
    val discoveredAt: Long,
    val isRead: Boolean = false,
)

@Serializable
data class BlockedTerm(
    val siteId: SiteId,
    val term: String,
    val createdAt: Long = 0L,
)

@Serializable
enum class TagTranslationSource {
    USER_CUSTOM,
    COMMUNITY_EHTAG,
    BUILTIN,
}

@Serializable
data class TagTranslationEntry(
    val namespace: String = "",
    val rawSlug: String,
    val chineseName: String,
    val intro: String = "",
    val source: TagTranslationSource = TagTranslationSource.USER_CUSTOM,
    val updatedAt: Long = 0L,
)

@Serializable
data class SearchHistoryEntry(
    val siteId: SiteId,
    val query: String,
    val scope: SearchScope = SearchScope.ALL,
    val searchedAt: Long,
)

@Serializable
data class PlaybackPreferences(
    val siteId: SiteId? = null,
    val defaultSpeed: Float = 1.0f,
    val longPressSpeed: Float = 2.0f,
    val seekStepSeconds: Int = 10,
    val gestureSensitivitySeconds: Int = 90,
    val preferredQuality: String = "auto",
    val autoFullscreen: Boolean = false,
    val autoDownload: Boolean = false,
)

@Serializable
enum class ReaderMode {
    STRIP,
    LTR,
    RTL,
    SPREAD,
}

@Serializable
enum class ReaderFit {
    WIDTH,
    HEIGHT,
    ORIGINAL,
}

@Serializable
enum class ReaderStartPosition {
    RESUME,
    FIRST,
    LAST,
}

@Serializable
data class ReaderPreferences(
    val siteId: SiteId? = null,
    val mediaId: String? = null,
    val mode: ReaderMode = ReaderMode.STRIP,
    val fit: ReaderFit = ReaderFit.WIDTH,
    val startPosition: ReaderStartPosition = ReaderStartPosition.RESUME,
    val cropPercent: Int = 0,
    val combineCover: Boolean = false,
    val preferOriginalImages: Boolean = false,
    val volumeKeyPaging: Boolean = false,
    val reverseVolumeKeys: Boolean = false,
    val keepScreenOn: Boolean = true,
    val brightnessPercent: Int = -1,
)

@Serializable
enum class DownloadKind {
    VIDEO,
    COMIC,
    PHOTO,
}

@Serializable
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    RETRY_WAIT,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Serializable
enum class DownloadErrorCategory {
    NETWORK,
    RATE_LIMITED,
    AUTH_REQUIRED,
    NOT_FOUND,
    QUOTA_EXCEEDED,
    STORAGE,
    TLS,
    SOURCE_CHANGED,
    UNKNOWN,
}

@Serializable
enum class StorageLocation {
    PRIVATE,
    SHARED,
}

@Serializable
data class DownloadItem(
    val id: String,
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val title: String,
    val coverUrl: String = "",
    val kind: DownloadKind,
    val status: DownloadStatus,
    val quality: String? = null,
    val groupName: String = "",
    val progressFraction: Float = 0f,
    val completedUnits: Int = 0,
    val totalUnits: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val selectedPages: List<Int> = emptyList(),
    val errorCategory: DownloadErrorCategory? = null,
    val errorMessage: String? = null,
    val retryAtEpochMs: Long? = null,
    val priority: Int = 0,
    val storageLocation: StorageLocation = StorageLocation.PRIVATE,
    val localPath: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = createdAt,
)

/**
 * Complete encrypted-backup payload for native Raven format (`RVNBK01`).
 * Never includes session cookies, auth tokens, encryption keys, or media binaries.
 */
@Serializable
data class RavenBackupPayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: Long,
    val appVersion: String = "",
    val watchProgress: List<WatchProgress> = emptyList(),
    val readingProgress: List<ReadingProgress> = emptyList(),
    val favorites: List<FavoriteItem> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    val shelfItems: List<ShelfItem> = emptyList(),
    val smartShelves: List<SmartShelf> = emptyList(),
    val pageBookmarks: List<PageBookmark> = emptyList(),
    val timeBookmarks: List<TimeBookmark> = emptyList(),
    val imageFavorites: List<ImageFavorite> = emptyList(),
    val tagSubscriptions: List<TagSubscription> = emptyList(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val updateInbox: List<UpdateInboxItem> = emptyList(),
    val blockedTerms: List<BlockedTerm> = emptyList(),
    val searchHistory: List<SearchHistoryEntry> = emptyList(),
    val playbackPreferences: List<PlaybackPreferences> = emptyList(),
    val readerPreferences: List<ReaderPreferences> = emptyList(),
    val siteLanguages: Map<SiteId, ComicLanguage> = emptyMap(),
    val settings: Map<String, String> = emptyMap(),
    val customTagTranslations: List<TagTranslationEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val NATIVE_MAGIC: String = "RVNBK01"
        const val LEGACY_R34V_MAGIC: String = "R34VBK01"
        const val MAX_PLAINTEXT_BYTES: Int = 16 * 1024 * 1024

        // Canonical collection bounds shared by `BackupValidator` (reject on import/export) and
        // `BackupMerger` (trim the merged union before it is written). A restore that accepts an
        // incoming payload must therefore always produce a payload that exports again — the
        // "restore then immediately export" regression depends on these bounds agreeing.
        const val MAX_SHELVES: Int = 1_000
        const val MAX_ITEMS_PER_SHELF: Int = 50_000
        const val MAX_SMART_SHELVES: Int = 1_000
        const val MAX_PAGE_BOOKMARKS: Int = 50_000
        const val MAX_TIME_BOOKMARKS: Int = 5_000
        const val MAX_IMAGE_FAVORITES: Int = 20_000
        const val MAX_SAVED_SEARCHES: Int = 1_000
        const val MAX_INBOX_ITEMS: Int = 10_000
        const val MAX_CUSTOM_TAG_TRANSLATIONS: Int = 50_000
        const val MAX_SEARCH_HISTORY_PER_SITE: Int = 30
        // Per-site caps. Watch/reading progress use the 500-per-site DAO prune, so merging keeps
        // that independent.
        const val MAX_FAVORITES_PER_SITE: Int = 50_000
        const val MAX_SUBSCRIPTIONS_PER_SITE: Int = 5_000
        const val MAX_BLOCKED_PER_SITE: Int = 10_000

        // Editorial bounds: writers (tag editor, legacy adapter) clamp to these so data that is
        // already in the app never trips the backup validator on the next export.
        const val MAX_TAG_CHINESE_NAME_LENGTH: Int = 200
        const val MAX_TAG_SLUG_LENGTH: Int = 200
        const val MAX_TAG_NAMESPACE_LENGTH: Int = 64
        const val MAX_TAG_INTRO_LENGTH: Int = 2_048
    }
}
