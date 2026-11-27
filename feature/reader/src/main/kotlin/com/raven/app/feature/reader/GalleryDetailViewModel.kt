package com.raven.app.feature.reader

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.PageSelectionParser
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.domain.LikeRecommendationEngine
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.LikeRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.reader.ExportMode
import com.raven.app.core.media.reader.GalleryExporter
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.reader.PreparedExport
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FeatureTutorialIds
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.LikedMedia
import com.raven.app.core.model.LikedTagWeight
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SeriesFollow
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.workId
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the selective page download [com.raven.app.core.designsystem.component.RavenBottomSheet].
 */
data class DownloadSheetState(
    val visible: Boolean = false,
    val downloadAll: Boolean = true,
    val rangeInput: String = "",
    val parsedPages: List<Int> = emptyList(),
    val validationError: String? = null,
    val isSubmitting: Boolean = false,
)

/**
 * Target tag inspected via long-press inside [GalleryDetailScreen].
 */
data class TagActionState(
    val tag: TagRef,
    val translatedLabel: String,
    val isFavorited: Boolean,
    val isSubscribed: Boolean,
    val isBlocked: Boolean,
)

/** Concrete resume destination: which chapter to open, at which page. */
data class GalleryResumeTarget(
    val item: MediaItem,
    val startPage: Int,
    val completed: Boolean,
    /** True when the target chapter differs from the page currently on screen. */
    val isOtherChapter: Boolean,
)

/**
 * Immutable UI state for [GalleryDetailScreen].
 */
data class GalleryDetailUiState(
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val siteDescriptor: SiteDescriptor? = null,
    val siteDisplayName: String = siteId.value,
    val isLocal: Boolean = siteId.isLocal,
    val isLoading: Boolean = true,
    val detail: GalleryDetail? = null,
    val downloadedPages: List<Int> = emptyList(),
    val isOfflineComplete: Boolean = false,
    val sourceRoot: String = "",
    val localSizeLabel: String? = null,
    val readingProgress: ReadingProgress? = null,
    /** Reactive site-wide reading history keyed by media/chapter id (one row per work). */
    val readingProgressByMediaId: Map<String, ReadingProgress> = emptyMap(),
    /** Work-level id for serial comics (`comicId`); null for one-shot works. */
    val workSeriesId: String? = null,
    val isFavorite: Boolean = false,
    val isLiked: Boolean = false,
    val isInWatchLater: Boolean = false,
    val showDetailTutorial: Boolean = false,
    val shelves: List<ShelfWithSummary> = emptyList(),
    val containingShelfIds: Set<String> = emptySet(),
    val showShelfPicker: Boolean = false,
    val pageBookmarks: List<PageBookmark> = emptyList(),
    val activeDownload: DownloadItem? = null,
    val translateTags: Boolean = true,
    val blockTagsGlobally: Boolean = true,
    val tagDictionaryRevision: Long = 0L,
    val filteredRelated: List<MediaItem> = emptyList(),
    val chapters: List<MediaItem> = emptyList(),
    val chaptersLoading: Boolean = false,
    val chaptersError: String? = null,
    val favoritedTagSlugs: Set<String> = emptySet(),
    val subscribedTagSlugs: Set<String> = emptySet(),
    val isSeriesFollowed: Boolean = false,
    val seriesFollowSupported: Boolean = false,
    val seriesFollowInProgress: Boolean = false,
    val blockedTerms: Set<String> = emptySet(),
    val downloadSheet: DownloadSheetState = DownloadSheetState(),
    val tagAction: TagActionState? = null,
    /** Author inspected via long-press; shown in the author action sheet. */
    val authorAction: AuthorRef? = null,
    val isExporting: Boolean = false,
    val exportStatusText: String? = null,
    val authRequired: Boolean = false,
    val authMessage: String? = null,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
) {
    val hasResumeProgress: Boolean
        get() = resumeTarget?.completed == false

    /**
     * Where "继续阅读" must land. Inside one serial work the reader can hop chapters, so the newest
     * position of the whole work wins — resuming the chapter this page belongs to would rewind to
     * wherever that chapter was left before the jump.
     */
    val resumeTarget: GalleryResumeTarget?
        get() {
            val currentDetail = detail ?: return null
            val seriesId = workSeriesId?.takeIf(String::isNotBlank)
            if (seriesId == null) {
                val progress = readingProgress ?: return null
                val total = (currentDetail.pageCount.takeIf { it > 0 } ?: progress.totalPages).coerceAtLeast(1)
                if (progress.currentPage <= 0) return null
                return GalleryResumeTarget(
                    item = currentDetail.item,
                    startPage = if (progress.completed) 1 else progress.currentPage.coerceIn(1, total),
                    completed = progress.completed,
                    isOtherChapter = false,
                )
            }
            val latest = workResumeProgress ?: return null
            if (!latest.completed && latest.currentPage > 0) {
                val item = chapterItemFor(latest, currentDetail)
                val total = latest.totalPages.coerceAtLeast(latest.currentPage).coerceAtLeast(1)
                return GalleryResumeTarget(
                    item = item,
                    startPage = latest.currentPage.coerceIn(1, total),
                    completed = false,
                    isOtherChapter = item.id != currentDetail.item.id,
                )
            }
            nextUnreadChapter?.let { chapter ->
                return GalleryResumeTarget(
                    item = chapter,
                    startPage = 1,
                    completed = false,
                    isOtherChapter = chapter.id != currentDetail.item.id,
                )
            }
            val item = chapterItemFor(latest, currentDetail)
            return GalleryResumeTarget(
                item = item,
                startPage = 1,
                completed = true,
                isOtherChapter = item.id != currentDetail.item.id,
            )
        }

    /** Newest reading row across every chapter of this work (chapter rows share the work id). */
    val workResumeProgress: ReadingProgress?
        get() {
            val seriesId = workSeriesId?.takeIf(String::isNotBlank) ?: return readingProgress
            return readingProgressByMediaId.values
                .filter { it.seriesId == seriesId || it.workId == seriesId }
                .plus(listOfNotNull(readingProgress))
                .maxByOrNull { it.updatedAt }
        }

    val resumeStartPage: Int
        get() = resumeTarget?.startPage ?: 1

    val primaryCtaLabel: String
        get() {
            val target = resumeTarget
            return when {
                target == null -> "开始阅读"
                target.completed -> "重新阅读"
                target.startPage > 1 -> "继续阅读 (第 ${target.startPage} 页)"
                target.isOtherChapter -> "继续阅读"
                else -> "开始阅读"
            }
        }

    private fun chapterItemFor(progress: ReadingProgress, currentDetail: GalleryDetail): MediaItem =
        if (progress.mediaId == currentDetail.item.id) {
            currentDetail.item
        } else {
            MediaItem(
                siteId = progress.siteId,
                id = progress.mediaId,
                url = progress.url,
                kind = progress.kind,
                title = progress.title,
                coverUrl = progress.coverUrl,
                pageCount = progress.totalPages.takeIf { it > 0 },
            )
        }

    val canOpenOffline: Boolean
        get() = isLocal || isOfflineComplete || downloadedPages.isNotEmpty() ||
            activeDownload?.status == DownloadStatus.COMPLETED

    /** First unread/unfinished chapter after the current one, or null at the list boundary. */
    val nextUnreadChapter: MediaItem?
        get() {
            val current = detail ?: return null
            return findNextUnreadChapter(
                chapters = chapters,
                currentChapter = current.item,
                progressByMediaId = readingProgressByMediaId,
            )
        }

    val allChaptersCompleted: Boolean
        get() = chapters.isNotEmpty() && chapters.all { chapter ->
            readingProgressByMediaId[chapter.id]?.completed == true
        }

    /** Boundary copy shown when "下一未读" is disabled. */
    val nextUnreadBoundaryLabel: String?
        get() = when {
            chapters.isEmpty() || nextUnreadChapter != null -> null
            allChaptersCompleted -> "全部章节已读完"
            else -> "后续章节已读完"
        }

    val chapterPickerReadState: ChapterPickerReadState
        get() = ChapterPickerReadState(
            progressByMediaId = readingProgressByMediaId,
            nextUnreadChapter = nextUnreadChapter,
            nextUnreadBoundaryLabel = nextUnreadBoundaryLabel,
        )
}

/**
 * One-shot side effects emitted by [GalleryDetailViewModel] for SAF export pickers.
 */
sealed interface GalleryDetailEvent {
    data class RequestSaveDocument(
        val prepared: PreparedExport,
        val suggestedFileName: String,
        val mimeType: String,
    ) : GalleryDetailEvent
}

class GalleryDetailViewModel(
    private val route: RavenRoute.GalleryDetail,
    private val siteRegistry: SiteRegistry,
    private val pageLoader: ReaderPageLoader,
    private val localLibraryImporter: LocalLibraryImporter,
    private val downloadGateway: GalleryDownloadGateway,
    private val exportGateway: GalleryExportGateway,
    private val historyRepository: HistoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val shelfRepository: ShelfRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val preferencesRepository: PreferencesRepository,
    private val dispatchers: AppDispatchers,
    private val tagDictionary: TagDictionary = TagDictionary.default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mediaTagCacheRepository: MediaTagCacheRepository? = null,
    private val tagDictionaryRepository: TagDictionaryRepository? = null,
    private val likeRepository: LikeRepository? = null,
) : ViewModel() {

    constructor(
        route: RavenRoute.GalleryDetail,
        siteRegistry: SiteRegistry,
        galleryPageRepository: GalleryPageRepository,
        localLibraryImporter: LocalLibraryImporter,
        downloadCoordinator: DownloadCoordinator,
        galleryExporter: GalleryExporter,
        historyRepository: HistoryRepository,
        favoriteRepository: FavoriteRepository,
        shelfRepository: ShelfRepository,
        bookmarkRepository: BookmarkRepository,
        blockedTermsRepository: BlockedTermsRepository,
        subscriptionRepository: SubscriptionRepository,
        preferencesRepository: PreferencesRepository,
        dispatchers: AppDispatchers,
        tagDictionary: TagDictionary = TagDictionary.default,
        clock: () -> Long = System::currentTimeMillis,
        mediaTagCacheRepository: MediaTagCacheRepository? = null,
        tagDictionaryRepository: TagDictionaryRepository? = null,
        likeRepository: LikeRepository? = null,
    ) : this(
        route = route,
        siteRegistry = siteRegistry,
        pageLoader = RepositoryReaderPageLoader(galleryPageRepository),
        localLibraryImporter = localLibraryImporter,
        downloadGateway = CoordinatorGalleryDownloadGateway(downloadCoordinator),
        exportGateway = MediaGalleryExportGateway(galleryExporter),
        historyRepository = historyRepository,
        favoriteRepository = favoriteRepository,
        shelfRepository = shelfRepository,
        bookmarkRepository = bookmarkRepository,
        blockedTermsRepository = blockedTermsRepository,
        subscriptionRepository = subscriptionRepository,
        preferencesRepository = preferencesRepository,
        dispatchers = dispatchers,
        tagDictionary = tagDictionary,
        clock = clock,
        mediaTagCacheRepository = mediaTagCacheRepository,
        tagDictionaryRepository = tagDictionaryRepository,
        likeRepository = likeRepository,
    )

    private val parsedSiteId: SiteId? = runCatching { SiteId.of(route.siteId) }.getOrNull()
    private val siteId: SiteId = parsedSiteId ?: SiteId.GLOBAL
    private val isInvalidSiteId: Boolean = parsedSiteId == null
    private val mediaId: String = route.mediaId
    private val url: String = route.url
    private val offlineOnly: Boolean = route.offlineOnly

    private val plugin = if (isInvalidSiteId) null else siteRegistry.get(siteId)
    private val descriptor: SiteDescriptor? = plugin?.descriptor

    private val _uiState = MutableStateFlow(
        GalleryDetailUiState(
            siteId = siteId,
            mediaId = mediaId,
            url = url,
            siteDescriptor = descriptor,
            siteDisplayName = when {
                isInvalidSiteId -> route.siteId
                siteId.isLocal -> "本地导入"
                descriptor != null -> descriptor.displayName
                else -> siteId.value
            },
            isLocal = !isInvalidSiteId && siteId.isLocal,
        ),
    )
    val uiState: StateFlow<GalleryDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GalleryDetailEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<GalleryDetailEvent> = _events.asSharedFlow()

    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private var chaptersJob: Job? = null
    private var similarJob: Job? = null
    private var resumeApplied: Boolean = false
    private val activeMediaId = MutableStateFlow(mediaId)
    @Volatile
    private var latestCachedTagsByKey: Map<String, List<TagRef>> = emptyMap()
    @Volatile
    private var latestLikedTagWeights: List<LikedTagWeight> = emptyList()
    @Volatile
    private var latestRawSimilarCandidates: List<MediaItem> = emptyList()
    @Volatile
    private var latestAuthorCandidateKeys: Set<String> = emptySet()
    @Volatile
    private var latestTitleCandidateKeys: Set<String> = emptySet()
    @Volatile
    private var latestTagHintKeysByItem: Map<String, Set<String>> = emptyMap()

    init {
        observeLocalPersonalState()
        loadDetail()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLocalPersonalState() {
        if (isInvalidSiteId) return
        // Site-wide history powers per-chapter read markers and "下一未读" without per-chapter queries.
        viewModelScope.launch(dispatchers.main) {
            historyRepository.observeReadingProgressBySite(siteId).collectLatest { history ->
                val byMediaId = history.associateBy { it.mediaId }
                _uiState.update { state ->
                    if (state.readingProgressByMediaId == byMediaId) {
                        state
                    } else {
                        state.copy(readingProgressByMediaId = byMediaId)
                    }
                }
            }
        }
        // Combine reading progress, bookmarks, watch-later / shelf membership, and downloads
        // keyed by the active work/chapter id so serial auto-resume stays synchronized.
        viewModelScope.launch(dispatchers.main) {
            activeMediaId.flatMapLatest { targetMediaId ->
                combine(
                    historyRepository.observeReadingProgress(siteId, targetMediaId),
                    bookmarkRepository.observePageBookmarks(siteId, targetMediaId),
                    shelfRepository.observeContainingShelfIds(siteId, targetMediaId),
                    downloadGateway.observeByMedia(siteId, targetMediaId),
                    preferencesRepository.settings,
                ) { progress, bookmarks, shelfIds, downloads, settings ->
                    PersonalBaseSnapshot(
                        progress = progress,
                        bookmarks = bookmarks,
                        containingShelfIds = shelfIds.toSet(),
                        activeDownload = downloads.maxByOrNull { it.updatedAt },
                        translateTags = settings.translateTags,
                        blockTagsGlobally = settings.blockTagsGlobally,
                    )
                }
            }.collectLatest { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        readingProgress = snapshot.progress ?: state.readingProgress.takeIf {
                            state.detail != null && state.detail.item.id != mediaId
                        },
                        pageBookmarks = snapshot.bookmarks,
                        containingShelfIds = snapshot.containingShelfIds,
                        isInWatchLater = Shelf.WATCH_LATER_ID in snapshot.containingShelfIds,
                        activeDownload = snapshot.activeDownload,
                        translateTags = snapshot.translateTags,
                        blockTagsGlobally = snapshot.blockTagsGlobally,
                    )
                }
            }
        }

        // Observe tutorial flag and local like state + liked tag weights.
        viewModelScope.launch(dispatchers.main) {
            preferencesRepository.settings.collectLatest { settings ->
                _uiState.update {
                    it.copy(showDetailTutorial = !settings.hasSeenTutorial(FeatureTutorialIds.DETAIL_ACTIONS))
                }
            }
        }
        likeRepository?.let { likesRepo ->
            viewModelScope.launch(dispatchers.main) {
                activeMediaId.flatMapLatest { targetMediaId ->
                    likesRepo.observeIsLiked(siteId, targetMediaId)
                }.collectLatest { liked ->
                    _uiState.update { it.copy(isLiked = liked) }
                }
            }
            viewModelScope.launch(dispatchers.main) {
                likesRepo.observeTagWeights().collectLatest { weights ->
                    latestLikedTagWeights = weights
                    val detail = _uiState.value.detail ?: return@collectLatest
                    val base = latestRawSimilarCandidates.ifEmpty { detail.related }
                    if (base.isNotEmpty()) {
                        _uiState.update { state ->
                            state.copy(
                                filteredRelated = rankAndFilterSimilar(
                                    detail = detail,
                                    candidates = base,
                                    blockedTerms = state.blockedTerms,
                                    cachedTagsByKey = latestCachedTagsByKey,
                                ),
                            )
                        }
                    }
                }
            }
        }

        viewModelScope.launch(dispatchers.main) {
            shelfRepository.observeShelvesWithSummary().collectLatest { shelves ->
                _uiState.update { it.copy(shelves = shelves) }
            }
        }

        _uiState.update { it.copy(seriesFollowSupported = plugin?.seriesSource != null) }

        viewModelScope.launch(dispatchers.main) {
            combine(
                subscriptionRepository.observeSeriesFollows(),
                _uiState.map { it.detail },
            ) { follows, detail -> follows to detail }
                .collectLatest { (follows, detail) ->
                    val sid = detail?.item?.extra?.get("comicId")?.takeIf { it.isNotBlank() }
                        ?: detail?.item?.id?.substringBefore(':')?.takeIf { it.isNotBlank() }
                        ?: return@collectLatest
                    _uiState.update { state ->
                        state.copy(isSeriesFollowed = follows.any { it.siteId == siteId && it.seriesId == sid })
                    }
                }
        }

        // Combine site favorites, tag subscriptions, effective blocked terms, cached tags, and dictionary revision.
        val cachedTagsFlow = mediaTagCacheRepository?.observeTagsBySite(siteId) ?: flowOf(emptyMap())
        val dictRevisionFlow = tagDictionaryRepository?.observeRevision() ?: flowOf(0L)
        viewModelScope.launch(dispatchers.main) {
            combine(
                combine(
                    favoriteRepository.observeBySite(siteId),
                    subscriptionRepository.observeSubscriptions(siteId),
                    blockedTermsRepository.observeEffectiveTermsBySite(siteId),
                    cachedTagsFlow,
                    dictRevisionFlow,
                ) { favorites, subscriptions, blocked, cachedTags, dictRevision ->
                    SitePersonalSnapshot(
                        favorites = favorites,
                        subscribedSlugs = subscriptions.mapTo(linkedSetOf()) { it.slug.lowercase() },
                        blockedTerms = blocked.mapTo(linkedSetOf()) { ContentFilter.normalizeTerm(it) },
                        cachedTagsByKey = cachedTags,
                        dictRevision = dictRevision,
                    )
                },
                activeMediaId,
            ) { snapshot, currentTargetId ->
                snapshot to currentTargetId
            }.collectLatest { (snapshot, currentTargetId) ->
                latestCachedTagsByKey = snapshot.cachedTagsByKey
                _uiState.update { state ->
                    val detailKind = state.detail?.item?.kind
                    val isWorkFav = snapshot.favorites.any { fav ->
                        fav.targetId == currentTargetId && (
                            (detailKind == MediaKind.PHOTO && fav.kind == FavoriteKind.PHOTO) ||
                                (detailKind != MediaKind.PHOTO && fav.kind == FavoriteKind.COMIC) ||
                                (detailKind == null && (fav.kind == FavoriteKind.COMIC || fav.kind == FavoriteKind.PHOTO))
                            )
                    }
                    val favTagSlugs = snapshot.favorites
                        .filter { it.kind == FavoriteKind.TAG }
                        .mapTo(linkedSetOf()) { it.targetId.lowercase() }
                    val updatedTagAction = state.tagAction?.let { action ->
                        val normSlug = subscriptionSlug(action.tag).lowercase()
                        action.copy(
                            translatedLabel = formatTagLabelInternal(action.tag, state.translateTags),
                            isFavorited = normSlug in favTagSlugs,
                            isSubscribed = normSlug in snapshot.subscribedSlugs,
                            isBlocked = matchesBlockedTerms(action.tag, snapshot.blockedTerms),
                        )
                    }
                    val filteredRelated = state.detail?.let { detail ->
                        rankAndFilterSimilar(
                            detail = detail,
                            candidates = latestRawSimilarCandidates.ifEmpty { detail.related },
                            blockedTerms = snapshot.blockedTerms,
                            cachedTagsByKey = snapshot.cachedTagsByKey,
                        )
                    } ?: emptyList()
                    state.copy(
                        isFavorite = isWorkFav,
                        favoritedTagSlugs = emptySet(),
                        subscribedTagSlugs = snapshot.subscribedSlugs,
                        blockedTerms = snapshot.blockedTerms,
                        tagDictionaryRevision = snapshot.dictRevision,
                        filteredRelated = filteredRelated,
                        tagAction = updatedTagAction,
                    )
                }
            }
        }
    }

    fun loadDetail(forceRefresh: Boolean = false) {
        if (!forceRefresh && loadJob?.isActive == true) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(dispatchers.io) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    authRequired = false,
                    authMessage = null,
                )
            }

            if (isInvalidSiteId) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "无效的站点标识：${route.siteId}",
                    )
                }
                return@launch
            }

            // Pre-check auth gate on auth-gated sites (e.g., ExHentai) before firing network load.
            val currentPlugin = plugin
            if (!offlineOnly && !siteId.isLocal && currentPlugin != null && currentPlugin.authProvider.requiresAuthGate) {
                val authState = runCatching { currentPlugin.authProvider.currentAuthState() }.getOrNull()
                if (authState?.isAuthenticated != true) {
                    val accessNote = descriptor?.accessNote
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            authRequired = true,
                            authMessage = accessNote?.takeIf(String::isNotBlank)
                                ?: "访问 ${it.siteDisplayName} 图集需要先完成账号登录或导入 Cookie。",
                        )
                    }
                    return@launch
                }
            }

            try {
                val localBookSize = if (siteId.isLocal) {
                    val bookId = LocalLibraryImporter.parseLocalBookId(url) ?: mediaId
                    localLibraryImporter.getBook(bookId)?.let { book ->
                        LocalLibraryImporter.formatHumanBytes(book.bytes)
                    }
                } else {
                    null
                }

                val snapshot = pageLoader.loadDetail(
                    siteId = siteId,
                    mediaId = mediaId,
                    url = url,
                    offlineOnly = offlineOnly,
                )
                val resolvedId = snapshot.detail.item.id.ifBlank { mediaId }
                activeMediaId.value = resolvedId
                val favoriteKind = favoriteKindFor(snapshot.detail.item.kind)
                val isFavNow = runCatching {
                    favoriteRepository.isFavorite(siteId, favoriteKind, resolvedId)
                }.getOrDefault(_uiState.value.isFavorite)
                cacheDetailTags(snapshot.detail)

                latestRawSimilarCandidates = snapshot.detail.related
                latestAuthorCandidateKeys = emptySet()
                latestTitleCandidateKeys = emptySet()
                latestTagHintKeysByItem = emptyMap()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        detail = snapshot.detail,
                        workSeriesId = resolveWorkSeriesId(snapshot.detail),
                        filteredRelated = rankAndFilterSimilar(
                            detail = snapshot.detail,
                            candidates = snapshot.detail.related,
                            blockedTerms = it.blockedTerms,
                            cachedTagsByKey = latestCachedTagsByKey,
                        ),
                        downloadedPages = snapshot.downloadedPages,
                        isOfflineComplete = snapshot.isComplete,
                        sourceRoot = snapshot.sourceRoot,
                        localSizeLabel = localBookSize ?: snapshot.detail.fileSizeLabel,
                        isFavorite = isFavNow,
                        authRequired = false,
                        authMessage = null,
                        errorMessage = null,
                    )
                }
                if (!offlineOnly) {
                    launchEnhancedSimilarRecommendations(snapshot.detail, resolvedId)
                }
                if (!resumeApplied && !offlineOnly) {
                    resumeApplied = true
                    loadChaptersAndMaybeResume(snapshot.detail)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (auth: SiteException.AuthRequired) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        authRequired = true,
                        authMessage = auth.message.takeIf(String::isNotBlank)
                            ?: descriptor?.accessNote
                            ?: "该站点需要有效登录会话后才能查看图集详情。",
                        errorMessage = null,
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message?.takeIf(String::isNotBlank) ?: "图集详情加载失败",
                    )
                }
            }
        }
    }

    /** 手动刷新章节列表（详情页「选集」面板打开时调用）。 */
    fun refreshChapters() {
        val detail = _uiState.value.detail ?: return
        if (_uiState.value.chaptersLoading) return
        chaptersJob?.cancel()
        chaptersJob = viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(chaptersLoading = true, chaptersError = null) }
            try {
                val chapters = fetchChapters(detail)
                _uiState.update {
                    it.copy(
                        chaptersLoading = false,
                        chapters = chapters,
                        chaptersError = if (chapters.isEmpty()) "没有可用章节" else null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        chaptersLoading = false,
                        chaptersError = error.message?.takeIf(String::isNotBlank) ?: "章节列表加载失败",
                    )
                }
            }
        }
    }

    private suspend fun fetchChapters(detail: GalleryDetail): List<MediaItem> {
        if (detail.chaptersUrl.isNullOrBlank()) return emptyList()
        val feed = siteRegistry.get(siteId)?.feedSource ?: return emptyList()
        val comicId = detail.item.extra["comicId"]?.takeIf { it.isNotBlank() }
            ?: detail.item.id.substringBefore(':')
        if (comicId.isBlank()) return emptyList()
        val chapters = feed.loadFeed(
            FeedRequest(siteId = siteId, section = "chapters", category = comicId, page = 1),
        ).items
        if (chapters.isNotEmpty()) {
            val title = if (detail.item.id == comicId) detail.item.title else detail.item.title.substringBefore(" · ", "")
            historyRepository.linkReadingChapters(
                siteId, comicId, title.takeIf { it.isNotBlank() }, detail.chaptersUrl?.substringBefore('#'),
                chapters.map { it.id } + comicId,
            )
        }
        return chapters
    }

    /**
     * Fetches same-author, same-title/series, and high-weight shared-tag candidates on the same site,
     * merges them with plugin-provided `related`, and ranks strictly by:
     * 1. Same author -> 2. Same title / series -> 3. High-weight shared tags.
     */
    private fun launchEnhancedSimilarRecommendations(
        detail: GalleryDetail,
        expectedMediaId: String,
    ) {
        similarJob?.cancel()
        similarJob = viewModelScope.launch(dispatchers.io) {
            val feed = plugin?.feedSource ?: return@launch
            val supportedScopes = descriptor?.searchScopes.orEmpty()
            val merged = LinkedHashMap<String, MediaItem>()
            detail.related.forEach { merged.putIfAbsent(it.compositeKey, it) }

            val authorKeys = LinkedHashSet<String>()
            val titleKeys = LinkedHashSet<String>()
            val tagHints = LinkedHashMap<String, MutableSet<String>>()

            val authorName = (detail.authors.firstOrNull() ?: detail.item.author)
                ?.name
                ?.trim()
                .orEmpty()
            if (authorName.length >= 2) {
                val authorScope = if (SearchScope.AUTHOR in supportedScopes) SearchScope.AUTHOR else SearchScope.ALL
                val authorItems = runCatching {
                    feed.loadFeed(
                        FeedRequest(siteId = siteId, query = authorName, scope = authorScope, page = 1),
                    ).items
                }.getOrDefault(emptyList())
                for (item in authorItems.take(12)) {
                    if (item.compositeKey != detail.item.compositeKey && item.id != expectedMediaId) {
                        merged.putIfAbsent(item.compositeKey, item)
                        authorKeys.add(item.compositeKey)
                    }
                }
            }

            val coreTitle = LikeRecommendationEngine.extractCoreTitleQuery(detail.item.title)
            if (coreTitle.length >= 2 && !coreTitle.equals(authorName, ignoreCase = true)) {
                val titleItems = runCatching {
                    feed.loadFeed(
                        FeedRequest(siteId = siteId, query = coreTitle, scope = SearchScope.ALL, page = 1),
                    ).items
                }.getOrDefault(emptyList())
                for (item in titleItems.take(10)) {
                    if (item.compositeKey != detail.item.compositeKey && item.id != expectedMediaId) {
                        merged.putIfAbsent(item.compositeKey, item)
                        titleKeys.add(item.compositeKey)
                    }
                }
            }

            val sourceTags = (detail.tags + detail.characters + detail.categories + detail.item.tags)
                .distinctBy { LikeRecommendationEngine.canonicalTagKey(it) }
            val topTags = LikeRecommendationEngine.selectPrioritizedTags(
                sourceTags = sourceTags,
                likedTagWeights = latestLikedTagWeights,
                limit = 2,
            )
            val tagScope = if (SearchScope.TAG in supportedScopes) SearchScope.TAG else SearchScope.ALL
            for (tag in topTags) {
                val query = tag.slug.trim().ifEmpty { tag.label.trim() }
                if (query.isEmpty()) continue
                val hintKey = LikeRecommendationEngine.canonicalTagKey(tag)
                val tagItems = runCatching {
                    feed.loadFeed(
                        FeedRequest(siteId = siteId, query = query, scope = tagScope, page = 1),
                    ).items
                }.getOrDefault(emptyList())
                for (item in tagItems.take(10)) {
                    if (item.compositeKey != detail.item.compositeKey && item.id != expectedMediaId) {
                        merged.putIfAbsent(item.compositeKey, item)
                        if (hintKey != null) {
                            tagHints.getOrPut(item.compositeKey) { LinkedHashSet() }.add(hintKey)
                        }
                    }
                }
            }

            if (_uiState.value.detail?.item?.id != expectedMediaId) return@launch
            val allCandidates = merged.values.toList()
            latestRawSimilarCandidates = allCandidates
            latestAuthorCandidateKeys = authorKeys
            latestTitleCandidateKeys = titleKeys
            latestTagHintKeysByItem = tagHints.mapValues { it.value.toSet() }

            _uiState.update { state ->
                state.copy(
                    filteredRelated = rankAndFilterSimilar(
                        detail = detail,
                        candidates = allCandidates,
                        blockedTerms = state.blockedTerms,
                        cachedTagsByKey = latestCachedTagsByKey,
                    ),
                )
            }
        }
    }

    private fun rankAndFilterSimilar(
        detail: GalleryDetail,
        candidates: List<MediaItem>,
        blockedTerms: Set<String>,
        cachedTagsByKey: Map<String, List<TagRef>>,
    ): List<MediaItem> {
        val allowed = filterRelatedItems(
            raw = candidates,
            blockedTerms = blockedTerms,
            cachedTagsByKey = cachedTagsByKey,
        )
        val authors = buildList {
            detail.authors.mapTo(this) { it.name }
            detail.item.author?.name?.let(::add)
        }
        val sourceTags = detail.tags + detail.characters + detail.categories + detail.item.tags
        return LikeRecommendationEngine.rankSimilarWorks(
            sourceTitle = detail.item.title,
            sourceAuthors = authors,
            sourceTags = sourceTags,
            sourceCompositeKey = detail.item.compositeKey,
            sourceMediaId = detail.item.id,
            candidates = allowed,
            likedTagWeights = latestLikedTagWeights,
            cachedTagsByKey = cachedTagsByKey,
            tagHintKeysByItem = latestTagHintKeysByItem,
            authorCandidateKeys = latestAuthorCandidateKeys,
            titleCandidateKeys = latestTitleCandidateKeys,
            limit = 18,
        )
    }


    /**
     * 连载漫画：自动载入章节列表，并在存在历史记录时切换到最近阅读的章节；
     * 没有历史则停留在插件默认的第一章。
     */
    private fun loadChaptersAndMaybeResume(detail: GalleryDetail) {
        if (detail.chaptersUrl.isNullOrBlank()) return
        chaptersJob?.cancel()
        chaptersJob = viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(chaptersLoading = true, chaptersError = null) }
            val chapters = try {
                fetchChapters(detail)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        chaptersLoading = false,
                        chaptersError = error.message?.takeIf(String::isNotBlank) ?: "章节列表加载失败",
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    chaptersLoading = false,
                    chapters = chapters,
                    chaptersError = if (chapters.isEmpty()) "没有可用章节" else null,
                )
            }
            // Explicit chapter selections must never be replaced by an older reading-history entry.
            if (!isSeriesRootEntry(detail) || chapters.size <= 1) return@launch
            val chapterIds = chapters.mapTo(HashSet()) { it.id }
            val history = runCatching {
                historyRepository.observeReadingProgressBySite(siteId).first()
            }.getOrElse { emptyList() }
            val candidates = history.filter {
                (it.mediaId == detail.item.id || it.mediaId in chapterIds) && it.currentPage > 0
            }
            // Prefer the newest unfinished chapter; a finished chapter is only a fallback.
            val resume = candidates.firstOrNull { !it.completed } ?: candidates.firstOrNull() ?: return@launch
            val chapter = chapters.firstOrNull { it.id == resume.mediaId } ?: return@launch
            if (chapter.id == detail.item.id) return@launch
            val snapshot = try {
                pageLoader.loadDetail(siteId, chapter.id, chapter.url, offlineOnly = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                emitSnackbar(error.message?.takeIf(String::isNotBlank) ?: "历史续读章节加载失败，请手动选话")
                return@launch
            }
            // A refresh or a newer selection may have changed the visible detail while resume was loading.
            if (_uiState.value.detail?.item?.id != detail.item.id) return@launch
            val resumedId = snapshot.detail.item.id.ifBlank { chapter.id }
            activeMediaId.value = resumedId
            val resumedFav = runCatching {
                favoriteRepository.isFavorite(siteId, favoriteKindFor(snapshot.detail.item.kind), resumedId)
            }.getOrDefault(_uiState.value.isFavorite)
            _uiState.update {
                it.copy(
                    detail = snapshot.detail,
                    downloadedPages = snapshot.downloadedPages,
                    isOfflineComplete = snapshot.isComplete,
                    sourceRoot = snapshot.sourceRoot,
                    isFavorite = resumedFav,
                    workSeriesId = resolveWorkSeriesId(snapshot.detail),
                    readingProgress = it.readingProgressByMediaId[chapter.id],
                    localSizeLabel = snapshot.detail.fileSizeLabel,
                    filteredRelated = filterRelatedItems(
                        raw = snapshot.detail.related,
                        blockedTerms = it.blockedTerms,
                        cachedTagsByKey = latestCachedTagsByKey,
                    ),
                )
            }
        }
    }

    /** Work identity shared by every chapter row of the same serial comic. */
    private fun resolveWorkSeriesId(detail: GalleryDetail): String? {
        val extras = detail.item.extra
        // Only serial comics group chapters; photo sets and one-shot comics keep their own id.
        val isSerialWork = detail.item.kind.isSerialComic || extras["comicId"]?.isNotBlank() == true
        if (!isSerialWork) return null
        val explicit = listOf("comicId", "seriesId", "albumId")
            .firstNotNullOfOrNull { key -> extras[key]?.trim()?.takeIf(String::isNotBlank) }
        if (explicit != null) return explicit
        return detail.item.id.substringBefore(':').takeIf { detail.item.id.contains(':') && it.isNotBlank() }
    }

    private fun isSeriesRootEntry(detail: GalleryDetail): Boolean {
        val comicId = detail.item.extra["comicId"]?.takeIf { it.isNotBlank() }
        if (comicId != null) {
            return mediaId == comicId && detail.item.id == comicId
        }
        val chaptersBaseUrl = detail.chaptersUrl
            ?.substringBefore('#')
            ?.trimEnd('/')
            ?: return false
        return mediaId == detail.item.id && chaptersBaseUrl == detail.item.url.trimEnd('/')
    }

    fun toggleSeriesFollow() {
        if (_uiState.value.seriesFollowInProgress) return
        val detail = _uiState.value.detail ?: return
        val seriesSource = plugin?.seriesSource ?: return
        val siteId = this.siteId ?: return
        val seriesId = detail.item.extra["comicId"]?.takeIf { it.isNotBlank() }
            ?: detail.item.id.substringBefore(':').takeIf { it.isNotBlank() }
            ?: return
        val seriesUrl = detail.chaptersUrl?.substringBefore('#')?.takeIf { it.isNotBlank() }
            ?: detail.item.url.substringBefore("/chapter/").trimEnd('/')
        val seriesTitle = detail.item.title.substringBefore(" · ").ifBlank { detail.item.title }
        val currentlyFollowed = _uiState.value.isSeriesFollowed
        _uiState.update { it.copy(seriesFollowInProgress = true) }
        viewModelScope.launch(dispatchers.io) {
            try {
                if (currentlyFollowed) {
                    subscriptionRepository.unfollowSeries(siteId, seriesId)
                } else {
                    val chapters = seriesSource.loadChapters(seriesId)
                    subscriptionRepository.followSeries(
                        SeriesFollow(
                            siteId = siteId,
                            seriesId = seriesId,
                            title = seriesTitle,
                            url = seriesUrl,
                        ),
                        chapters,
                    )
                }
                _uiState.update { it.copy(isSeriesFollowed = !currentlyFollowed, seriesFollowInProgress = false) }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(seriesFollowInProgress = false) }
                throw ce
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        seriesFollowInProgress = false,
                        snackbarMessage = error.message?.takeIf(String::isNotBlank) ?: "作品追更失败",
                    )
                }
            }
        }
    }

    fun formatTagLabel(tag: TagRef): String =
        formatTagLabelInternal(tag, _uiState.value.translateTags)

    fun isTagBlocked(tag: TagRef): Boolean =
        matchesBlockedTerms(tag, _uiState.value.blockedTerms)

    private fun formatTagLabelInternal(tag: TagRef, translateTags: Boolean): String {
        val raw = tag.label.ifBlank { tag.slug }
        if (!translateTags) return raw
        val translated = tagDictionary.translate(tag.slug, tag.namespace)
            ?: tagDictionary.translate(raw, tag.namespace)
        return translated ?: raw
    }

    fun toggleFavorite() {
        val detail = _uiState.value.detail ?: return
        val item = detail.item
        val kind = favoriteKindFor(item.kind)
        viewModelScope.launch(dispatchers.io) {
            val added = favoriteRepository.toggle(
                FavoriteItem(
                    siteId = item.siteId,
                    targetId = item.id,
                    kind = kind,
                    title = item.title,
                    url = item.url,
                    coverUrl = item.coverUrl,
                    addedAt = clock(),
                ),
            )
            emitSnackbar(if (added) "已加入收藏" else "已取消收藏")
        }
    }

    fun toggleWatchLater() {
        val detail = _uiState.value.detail ?: return
        val item = detail.item
        // 漫画/写真靠阅读进度续读，稍后看只对视频有意义；旧入口也不能再写入。
        if (!item.kind.isVideo) return
        viewModelScope.launch(dispatchers.io) {
            val added = shelfRepository.toggleItem(
                shelfId = Shelf.WATCH_LATER_ID,
                item = ShelfItem(
                    shelfId = Shelf.WATCH_LATER_ID,
                    siteId = item.siteId,
                    mediaId = item.id,
                    url = item.url,
                    kind = item.kind,
                    title = item.title,
                    coverUrl = item.coverUrl,
                    addedAt = clock(),
                    pageCount = detail.pageCount,
                ),
            )
            emitSnackbar(if (added) "已加入稍后看" else "已移出稍后看")
        }
    }

    fun toggleLike() {
        val repo = likeRepository ?: return
        val detail = _uiState.value.detail ?: return
        val item = detail.item
        val allTags = (detail.tags + detail.characters + detail.categories + item.tags)
            .distinctBy { LikeRecommendationEngine.canonicalTagKey(it) }
        val authorName = (detail.authors.firstOrNull() ?: item.author)?.name.orEmpty()
        viewModelScope.launch(dispatchers.io) {
            val liked = repo.toggle(
                LikedMedia(
                    siteId = item.siteId,
                    mediaId = item.id,
                    kind = item.kind,
                    title = item.title,
                    url = item.url,
                    coverUrl = item.coverUrl,
                    authorName = authorName,
                    tags = allTags,
                    likedAt = clock(),
                ),
            )
            emitSnackbar(
                if (liked) {
                    if (allTags.isNotEmpty()) "已点赞 · 已记录 ${allTags.size} 个标签偏好" else "已点赞"
                } else {
                    "已取消点赞"
                },
            )
        }
    }

    fun completeDetailTutorial() {
        _uiState.update { it.copy(showDetailTutorial = false) }
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.markTutorialSeen(FeatureTutorialIds.DETAIL_ACTIONS)
        }
    }

    fun skipAllTutorials() {
        _uiState.update { it.copy(showDetailTutorial = false) }
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.markTutorialsSeen(FeatureTutorialIds.ALL)
        }
    }

    fun openShelfPicker() {
        if (_uiState.value.detail == null) return
        _uiState.update { it.copy(showShelfPicker = true) }
    }

    fun dismissShelfPicker() {
        _uiState.update { it.copy(showShelfPicker = false) }
    }

    fun setShelfMembership(shelfId: String, add: Boolean) {
        val detail = _uiState.value.detail ?: return
        val item = detail.item
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                if (add) {
                    shelfRepository.addItem(
                        shelfId = shelfId,
                        item = ShelfItem(
                            shelfId = shelfId,
                            siteId = item.siteId,
                            mediaId = item.id,
                            url = item.url,
                            kind = item.kind,
                            title = item.title,
                            coverUrl = item.coverUrl,
                            addedAt = clock(),
                            pageCount = detail.pageCount,
                        ),
                    )
                } else {
                    shelfRepository.removeItem(shelfId, item.siteId, item.id)
                }
            }.onFailure { err ->
                emitSnackbar(err.message?.takeIf(String::isNotBlank) ?: "更新片单失败")
            }
        }
    }

    fun createShelfAndAdd(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val detail = _uiState.value.detail ?: return
        val item = detail.item
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val created = shelfRepository.createShelf(name = clean)
                shelfRepository.addItem(
                    shelfId = created.id,
                    item = ShelfItem(
                        shelfId = created.id,
                        siteId = item.siteId,
                        mediaId = item.id,
                        url = item.url,
                        kind = item.kind,
                        title = item.title,
                        coverUrl = item.coverUrl,
                        addedAt = clock(),
                        pageCount = detail.pageCount,
                    ),
                )
                created
            }.onSuccess { created ->
                emitSnackbar("已加入片单「${created.name}」")
            }.onFailure { err ->
                emitSnackbar(err.message?.takeIf(String::isNotBlank) ?: "创建片单失败")
            }
        }
    }

    fun deleteBookmark(bookmarkId: String) {
        viewModelScope.launch(dispatchers.io) {
            bookmarkRepository.deletePageBookmark(bookmarkId)
            emitSnackbar("已删除页码书签")
        }
    }

    // ---- Selective Page Download Sheet ----

    fun openDownloadSheet() {
        val detail = _uiState.value.detail ?: return
        if (siteId.isLocal) {
            emitSnackbar("本地导入作品已在设备本地保存")
            return
        }
        if (detail.pageCount <= 0 && detail.pages.isEmpty()) {
            emitSnackbar("该作品暂无可下载图页")
            return
        }
        val totalPages = detail.pageCount.coerceAtLeast(1)
        _uiState.update { state ->
            state.copy(
                downloadSheet = DownloadSheetState(
                    visible = true,
                    downloadAll = true,
                    rangeInput = "1-$totalPages",
                    parsedPages = (1..totalPages).toList(),
                    validationError = null,
                    isSubmitting = false,
                ),
            )
        }
    }

    fun dismissDownloadSheet() {
        _uiState.update { state ->
            state.copy(downloadSheet = state.downloadSheet.copy(visible = false, isSubmitting = false))
        }
    }

    fun setDownloadAll(downloadAll: Boolean) {
        val totalPages = _uiState.value.detail?.pageCount ?: return
        _uiState.update { state ->
            if (downloadAll) {
                state.copy(
                    downloadSheet = state.downloadSheet.copy(
                        downloadAll = true,
                        validationError = null,
                    ),
                )
            } else {
                val input = state.downloadSheet.rangeInput
                val validation = validateRange(input, totalPages)
                state.copy(
                    downloadSheet = state.downloadSheet.copy(
                        downloadAll = false,
                        parsedPages = validation.first,
                        validationError = validation.second,
                    ),
                )
            }
        }
    }

    fun updateDownloadRangeInput(raw: String) {
        val totalPages = _uiState.value.detail?.pageCount ?: return
        val (pages, error) = validateRange(raw, totalPages)
        _uiState.update { state ->
            state.copy(
                downloadSheet = state.downloadSheet.copy(
                    downloadAll = false,
                    rangeInput = raw,
                    parsedPages = pages,
                    validationError = error,
                ),
            )
        }
    }

    fun confirmDownload() {
        val state = _uiState.value
        val detail = state.detail ?: return
        val sheet = state.downloadSheet
        if (sheet.isSubmitting) return

        val selectedPages: List<Int>? = if (sheet.downloadAll) {
            null
        } else {
            val (pages, error) = validateRange(sheet.rangeInput, detail.pageCount)
            if (error != null || pages.isEmpty()) {
                _uiState.update {
                    it.copy(
                        downloadSheet = it.downloadSheet.copy(
                            parsedPages = pages,
                            validationError = error ?: "请输入有效页码范围",
                        ),
                    )
                }
                return
            }
            pages
        }

        _uiState.update { it.copy(downloadSheet = it.downloadSheet.copy(isSubmitting = true)) }
        viewModelScope.launch(dispatchers.io) {
            try {
                downloadGateway.enqueueGallery(
                    item = detail.item.copy(pageCount = detail.pageCount),
                    selectedPages = selectedPages,
                )
                _uiState.update {
                    it.copy(
                        downloadSheet = it.downloadSheet.copy(visible = false, isSubmitting = false),
                    )
                }
                val message = if (selectedPages == null) {
                    "已加入整本下载队列（共 ${detail.pageCount} 页）"
                } else {
                    "已加入选页下载队列（共 ${selectedPages.size} 页）"
                }
                emitSnackbar(message)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        downloadSheet = it.downloadSheet.copy(
                            isSubmitting = false,
                            validationError = error.message?.takeIf(String::isNotBlank) ?: "加入下载队列失败",
                        ),
                    )
                }
            }
        }
    }

    /** Canonical subscription key; some plugins expose only a readable label without a slug. */
    private fun subscriptionSlug(tag: TagRef): String =
        tag.slug.trim().ifBlank { tag.label.trim() }

    // ---- Tag Long-Press Action Sheet ----

    fun openTagActions(tag: TagRef) {
        val state = _uiState.value
        val normSlug = subscriptionSlug(tag).lowercase()
        _uiState.update {
            it.copy(
                tagAction = TagActionState(
                    tag = tag,
                    translatedLabel = formatTagLabel(tag),
                    isFavorited = normSlug in state.favoritedTagSlugs,
                    isSubscribed = normSlug in state.subscribedTagSlugs,
                    isBlocked = matchesBlockedTerms(tag, state.blockedTerms),
                ),
                authorAction = null,
            )
        }
    }

    fun dismissTagActions() {
        _uiState.update { it.copy(tagAction = null) }
    }

    // ---- Author Long-Press Action Sheet ----

    fun openAuthorActions(author: AuthorRef) {
        _uiState.update { it.copy(authorAction = author, tagAction = null) }
    }

    fun dismissAuthorActions() {
        _uiState.update { it.copy(authorAction = null) }
    }

    fun toggleTagFavorite(tag: TagRef) {
        viewModelScope.launch(dispatchers.io) {
            val label = formatTagLabel(tag)
            val added = favoriteRepository.toggle(
                FavoriteItem(
                    siteId = siteId,
                    targetId = tag.slug,
                    kind = FavoriteKind.TAG,
                    title = label,
                    url = tag.url,
                    addedAt = clock(),
                    extra = buildMap {
                        if (tag.namespace.isNotBlank()) put("namespace", tag.namespace)
                    },
                ),
            )
            emitSnackbar(if (added) "已收藏标签「$label」" else "已取消收藏标签「$label」")
        }
    }

    fun toggleTagSubscription(tag: TagRef) {
        viewModelScope.launch(dispatchers.io) {
            val label = formatTagLabel(tag)
            val slug = subscriptionSlug(tag)
            if (slug.isEmpty()) {
                emitSnackbar("无法订阅标签「$label」：缺少标签标识")
                return@launch
            }
            val subscribed = subscriptionRepository.toggleSubscription(
                TagSubscription(
                    siteId = siteId,
                    slug = slug,
                    label = label,
                    url = tag.url,
                    scope = SearchScope.TAG,
                    createdAt = clock(),
                ),
            )
            emitSnackbar(if (subscribed) "已订阅标签「$label」" else "已取消订阅标签「$label」")
        }
    }

    fun toggleTagBlocked(tag: TagRef) {
        viewModelScope.launch(dispatchers.io) {
            val label = formatTagLabel(tag)
            val term = tag.slug.trim().ifBlank { tag.label.trim() }
            if (term.isEmpty()) return@launch
            val settings = preferencesRepository.currentSettings()
            val primaryScope = if (settings.blockTagsGlobally) SiteId.GLOBAL else siteId
            val normalized = ContentFilter.normalizeTerm(term)
            val globalTerms = blockedTermsRepository.observeGlobalTerms().first()
            val siteTerms = blockedTermsRepository.observeTermsBySite(siteId).first()
            val matchingGlobal = globalTerms.filter { isMatchingTermForTag(tag, it) }
            val matchingSite = siteTerms.filter { isMatchingTermForTag(tag, it) }
            val nowBlocked = if (matchingGlobal.isNotEmpty() || matchingSite.isNotEmpty()) {
                matchingGlobal.forEach { blockedTermsRepository.removeTerm(SiteId.GLOBAL, it) }
                matchingSite.forEach { blockedTermsRepository.removeTerm(siteId, it) }
                false
            } else {
                blockedTermsRepository.addTerm(primaryScope, normalized)
                true
            }
            val msg = when {
                !nowBlocked -> "已取消屏蔽「$label」"
                settings.blockTagsGlobally -> "已全局屏蔽「$label」"
                else -> "已在当前站点屏蔽「$label」"
            }
            emitSnackbar(msg)
        }
    }

    fun saveCustomTagTranslation(tag: TagRef, chineseName: String) {
        val repo = tagDictionaryRepository ?: return
        val slug = tag.slug.trim().ifBlank { tag.label.trim() }
        val cleanZh = chineseName.trim()
        if (slug.isEmpty() || cleanZh.isEmpty()) {
            emitSnackbar("请输入有效的中文译名")
            return
        }
        viewModelScope.launch(dispatchers.io) {
            val saved = repo.upsertCustomTranslation(
                rawSlug = slug,
                chineseName = cleanZh,
                namespace = tag.namespace,
            )
            if (saved) {
                repo.hydrateRuntimeDictionary()
                _uiState.update { state ->
                    val updatedAction = state.tagAction?.let { action ->
                        if (action.tag.slug == tag.slug && action.tag.namespace == tag.namespace) {
                            action.copy(translatedLabel = formatTagLabelInternal(action.tag, state.translateTags))
                        } else {
                            action
                        }
                    }
                    state.copy(tagAction = updatedAction)
                }
                emitSnackbar("已更新中文译名：$cleanZh")
            }
        }
    }

    // ---- CBZ Export ----

    fun exportCbz(selectedPages: List<Int>? = null) {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.isExporting) return

        val pages = selectedPages?.takeIf { it.isNotEmpty() } ?: (1..detail.pageCount).toList()
        if (pages.isEmpty()) {
            emitSnackbar("没有可导出的图页")
            return
        }

        exportJob?.cancel()
        exportJob = viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isExporting = true, exportStatusText = "正在准备导出 CBZ…") }
            try {
                val preferOriginal = if (descriptor?.hasCapability(SiteCapability.ORIGINAL_IMAGES) == true) {
                    runCatching {
                        preferencesRepository.observeEffectiveReaderPreferences(siteId, mediaId).first().preferOriginalImages
                    }.getOrDefault(false)
                } else {
                    false
                }
                val offlineOnly = state.isLocal || state.isOfflineComplete
                val prepared = exportGateway.prepare(
                    title = detail.item.title,
                    pages = pages,
                    mode = ExportMode.CBZ,
                    loadPageBytes = { pageIndex ->
                        pageLoader.loadOriginalBytes(
                            detail = detail,
                            pageIndex = pageIndex,
                            preferOriginal = preferOriginal,
                            offlineOnly = offlineOnly,
                            sourceRoot = state.sourceRoot,
                        )
                    },
                    onProgress = { progressText ->
                        _uiState.update { it.copy(exportStatusText = progressText) }
                    },
                )
                val firstFile = prepared.files.firstOrNull()
                if (firstFile != null) {
                    _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                    _events.emit(
                        GalleryDetailEvent.RequestSaveDocument(
                            prepared = prepared,
                            suggestedFileName = firstFile.file.name,
                            mimeType = firstFile.mimeType,
                        ),
                    )
                } else {
                    runCatching { exportGateway.removeStagingFolder(prepared.folder) }
                    _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                    emitSnackbar("导出文件为空")
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                throw ce
            } catch (error: Throwable) {
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                emitSnackbar(error.message?.takeIf(String::isNotBlank) ?: "导出 CBZ 失败")
            }
        }
    }

    fun completeSafExport(
        prepared: PreparedExport,
        targetUri: Uri?,
        contentResolver: ContentResolver,
    ) {
        viewModelScope.launch(dispatchers.io) {
            if (targetUri == null) {
                runCatching { exportGateway.removeStagingFolder(prepared.folder) }
                emitSnackbar("已取消导出")
                return@launch
            }
            _uiState.update { it.copy(isExporting = true, exportStatusText = "正在写入目标文件…") }
            try {
                val outcome = exportGateway.saveToSaf(
                    prepared = prepared,
                    targetUri = targetUri,
                    isTree = false,
                    contentResolver = contentResolver,
                    onProgress = { text -> _uiState.update { it.copy(exportStatusText = text) } },
                )
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                emitSnackbar("已成功导出 CBZ（${outcome.savedCount} 个文件）")
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                throw ce
            } catch (error: Throwable) {
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                emitSnackbar(error.message?.takeIf(String::isNotBlank) ?: "保存 CBZ 失败")
            }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun emitSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    private fun validateRange(raw: String, totalPages: Int): Pair<List<Int>, String?> {
        if (totalPages <= 0) return emptyList<Int>() to "当前图集页数无效"
        if (raw.isBlank()) return emptyList<Int>() to "请输入页码范围，例如 1, 3, 5-8"
        return try {
            val parsed = PageSelectionParser.parse(raw, totalPages)
            parsed to null
        } catch (error: IllegalArgumentException) {
            emptyList<Int>() to (error.message?.let { "页码格式无效（有效范围 1..$totalPages）" } ?: "页码格式无效")
        }
    }

    private fun cacheDetailTags(detail: GalleryDetail) {
        val repo = mediaTagCacheRepository ?: return
        if (siteId.isLocal || mediaId.isBlank()) return
        val allTags = detail.tags + detail.characters + detail.categories + detail.item.tags
        if (allTags.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            runCatching { repo.upsertTags(siteId, mediaId, allTags) }
        }
    }

    private fun filterRelatedItems(
        raw: List<MediaItem>,
        blockedTerms: Set<String>,
        cachedTagsByKey: Map<String, List<TagRef>>,
    ): List<MediaItem> {
        if (raw.isEmpty() || blockedTerms.isEmpty()) return raw
        return raw.filter { item ->
            ContentFilter.isAllowed(
                item = item,
                blockedTerms = blockedTerms,
                tagDictionary = tagDictionary,
                extraTags = cachedTagsByKey[item.compositeKey].orEmpty(),
            )
        }
    }

    private fun matchesBlockedTerms(tag: TagRef, blockedTerms: Set<String>): Boolean {
        if (blockedTerms.isEmpty()) return false
        return blockedTerms.any { isMatchingTermForTag(tag, it) }
    }

    private fun isMatchingTermForTag(tag: TagRef, blockedTerm: String): Boolean {
        val slugOrLabel = tag.slug.ifBlank { tag.label }
        val tagCandidates = buildSet {
            val norm = ContentFilter.normalizeTerm(slugOrLabel)
            if (norm.isNotEmpty()) add(norm)
            val bare = ContentFilter.canonicalizeForMatching(slugOrLabel)
            if (bare.isNotEmpty()) add(bare)
            addAll(tagDictionary.expandSynonyms(slugOrLabel))
            if (tag.label.isNotBlank() && tag.label != slugOrLabel) {
                addAll(tagDictionary.expandSynonyms(tag.label))
            }
        }
        val bNorm = ContentFilter.normalizeTerm(blockedTerm)
        val bBare = ContentFilter.canonicalizeForMatching(blockedTerm)
        if (bNorm in tagCandidates || bBare in tagCandidates) return true
        return tagDictionary.expandSynonyms(blockedTerm).any { it in tagCandidates }
    }

    private fun favoriteKindFor(kind: MediaKind): FavoriteKind = when (kind) {
        MediaKind.PHOTO -> FavoriteKind.PHOTO
        MediaKind.VIDEO -> FavoriteKind.VIDEO
        MediaKind.COMIC, MediaKind.SERIAL_COMIC -> FavoriteKind.COMIC
    }

    private data class PersonalBaseSnapshot(
        val progress: ReadingProgress?,
        val bookmarks: List<PageBookmark>,
        val containingShelfIds: Set<String>,
        val activeDownload: DownloadItem?,
        val translateTags: Boolean,
        val blockTagsGlobally: Boolean,
    )

    private data class SitePersonalSnapshot(
        val favorites: List<FavoriteItem>,
        val subscribedSlugs: Set<String>,
        val blockedTerms: Set<String>,
        val cachedTagsByKey: Map<String, List<TagRef>>,
        val dictRevision: Long,
    )
}
