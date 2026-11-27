package com.raven.app.feature.reader

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.PageSelectionParser
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.media.download.PlaybackQueueSession
import com.raven.app.core.media.download.PlaybackQueueStore
import com.raven.app.core.media.reader.ExportMode
import com.raven.app.core.media.reader.GalleryExporter
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.reader.PreparedExport
import com.raven.app.core.media.reader.ReaderBuffer
import com.raven.app.core.media.reader.ReaderBufferState
import com.raven.app.core.media.reader.ReaderGeometry
import com.raven.app.core.media.reader.SpreadNavigator
import com.raven.app.core.model.FeatureTutorialIds
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderPreferences
import com.raven.app.core.model.ReaderStartPosition
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.SiteRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Active bottom sheet in [ReaderScreen]. */
enum class ReaderSheet {
    NONE,
    THUMBNAILS,
    BOOKMARKS,
    CHAPTERS,
    SETTINGS,
}

/** Active lightweight inline frosted panel above the bottom bar in [ReaderScreen]. */
enum class ReaderQuickPanel {
    NONE,
    MODE,
    BRIGHTNESS_AUTO_PAGE,
}

/** Ordering mode for auto-page / slideshow playback. */
enum class SlideshowOrder {
    SEQUENTIAL,
    RANDOM,
}

/**
 * Seamlessly appended serial chapter rendered below the primary chapter in continuous STRIP mode.
 */
data class SerialStripChapter(
    val mediaId: String,
    val title: String,
    val url: String,
    val detail: GalleryDetail,
    val sourceRoot: String,
    val pageNumbers: List<Int>,
    val bufferState: ReaderBufferState<LoadedReaderPage> = ReaderBufferState(),
    val pageAspectRatios: Map<Int, Float> = emptyMap(),
)

/**
 * Immutable UI state for [ReaderScreen].
 */
data class ReaderUiState(
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val offlineOnly: Boolean,
    val siteDisplayName: String = siteId.value,
    val supportsOriginalImages: Boolean = false,
    val isLoading: Boolean = true,
    val detail: GalleryDetail? = null,
    val pageNumbers: List<Int> = emptyList(),
    val totalPages: Int = 0,
    val sourceRoot: String = "",
    val currentPage: Int = 1,
    val stripScrollOffsetPx: Int = 0,
    val pendingStripRestoreOffsetPx: Int? = null,
    val bufferState: ReaderBufferState<LoadedReaderPage> = ReaderBufferState(),
    val pageAspectRatios: Map<Int, Float> = emptyMap(),
    val appendedChapters: List<SerialStripChapter> = emptyList(),
    val isAppendingNextChapter: Boolean = false,
    val appendNextChapterError: String? = null,
    val hasReachedFinalSerialChapter: Boolean = false,
    val activeStripChapterId: String? = null,
    val activeStripChapterTitle: String? = null,
    val activeStripChapterPage: Int? = null,
    val activeStripChapterOffsetPx: Int = 0,
    val activeStripChapterTotalPages: Int? = null,
    val showReaderTutorial: Boolean = false,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val isPerBookOverride: Boolean = false,
    val isLandscapeViewport: Boolean = false,
    val chromeVisible: Boolean = true,
    val showStatusBar: Boolean = false,
    val tapZonesEnabled: Boolean = true,
    val autoSplitWidePages: Boolean = false,
    val activeSheet: ReaderSheet = ReaderSheet.NONE,
    val activeQuickPanel: ReaderQuickPanel = ReaderQuickPanel.NONE,
    val autoPageIntervalSeconds: Int = 0,
    val slideshowOrder: SlideshowOrder = SlideshowOrder.SEQUENTIAL,
    val pageBookmarks: List<PageBookmark> = emptyList(),
    val imageFavoritePages: Set<Int> = emptySet(),
    val thumbnailPageBookmarks: List<PageBookmark> = emptyList(),
    val queueSession: PlaybackQueueSession? = null,
    val showQueueNextCard: Boolean = false,
    val previousChapter: MediaItem? = null,
    val nextChapter: MediaItem? = null,
    val chapters: List<MediaItem> = emptyList(),
    val isChapterListReady: Boolean = false,
    val isChapterListLoading: Boolean = false,
    val chapterListError: String? = null,
    val isChapterTransitionLoading: Boolean = false,
    val isExporting: Boolean = false,
    val exportStatusText: String? = null,
    val authRequired: Boolean = false,
    val authMessage: String? = null,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
) {
    val isSerialComic: Boolean
        get() = !offlineOnly && !detail?.chaptersUrl.isNullOrBlank()

    val displayTitle: String
        get() = if (preferences.mode == ReaderMode.STRIP && !activeStripChapterTitle.isNullOrBlank()) {
            activeStripChapterTitle
        } else {
            detail?.item?.title ?: siteDisplayName
        }

    val displayCurrentPage: Int
        get() = if (preferences.mode == ReaderMode.STRIP && activeStripChapterPage != null) {
            activeStripChapterPage
        } else {
            currentPage
        }

    val displayTotalPages: Int
        get() = if (preferences.mode == ReaderMode.STRIP && activeStripChapterTotalPages != null) {
            activeStripChapterTotalPages
        } else {
            totalPages
        }

    val effectiveSpread: Int
        get() = if (preferences.mode == ReaderMode.SPREAD && isLandscapeViewport) 2 else 1

    val spreadNavigator: SpreadNavigator?
        get() = if (pageNumbers.isEmpty()) {
            null
        } else {
            SpreadNavigator(
                pages = pageNumbers,
                spread = effectiveSpread,
                combineCover = preferences.combineCover,
            )
        }

    val currentSheetPages: List<Int>
        get() = spreadNavigator?.sheetFor(currentPage) ?: listOf(currentPage)

    val displayMediaId: String
        get() = if (preferences.mode == ReaderMode.STRIP) activeStripChapterId ?: mediaId else mediaId

    val isCurrentPageBookmarked: Boolean
        get() = pageBookmarks.any { it.mediaId == displayMediaId && it.page == displayCurrentPage }

    val isCurrentPageImageFavorited: Boolean
        get() = displayCurrentPage in imageFavoritePages

    val isCurrentPageRenderReady: Boolean
        get() {
            if (pageNumbers.isEmpty()) return false
            val targetPages = if (preferences.mode == ReaderMode.SPREAD) currentSheetPages else listOf(currentPage)
            return targetPages.all { p -> p in bufferState.pages && p !in bufferState.errors }
        }

    val progressPercent: Int
        get() = if (displayTotalPages <= 0) 0 else (displayCurrentPage * 100L / displayTotalPages).coerceIn(0L, 100L).toInt()

    /** Effective window brightness (`-1f` = follow system, `0f..1f` = explicit override). */
    val effectiveBrightnessFloat: Float
        get() = if (preferences.brightnessPercent < 0) {
            -1f
        } else {
            (preferences.brightnessPercent.coerceIn(0, 100) / 100f).coerceIn(0.01f, 1f)
        }
}

/**
 * One-shot side effects emitted by [ReaderViewModel] for programmatic scrolls, SAF export,
 * image sharing, sequential queue transitions, and serial chapter advancement.
 */
sealed interface ReaderEvent {
    data class ScrollToPage(
        val page: Int,
        val scrollOffsetPx: Int = 0,
        val chapterId: String? = null,
    ) : ReaderEvent

    data class RequestSaveDocument(
        val prepared: PreparedExport,
        val suggestedFileName: String,
        val mimeType: String,
    ) : ReaderEvent

    data class RequestOpenDocumentTree(
        val prepared: PreparedExport,
    ) : ReaderEvent

    data class RequestShareExport(
        val prepared: PreparedExport,
        val subject: String,
    ) : ReaderEvent

    data class OpenNextQueueItem(
        val previous: PlaybackQueueSession,
        val replacement: PlaybackQueueSession,
        val nextItem: MediaItem,
    ) : ReaderEvent

    data class OpenChapter(
        val nextItem: MediaItem,
        val parent: RavenRoute.ReaderParent? = null,
    ) : ReaderEvent
}

private enum class SerialChapterDirection {
    PREVIOUS,
    NEXT,
}

class ReaderViewModel(
    private val route: RavenRoute.Reader,
    private val siteRegistry: SiteRegistry,
    private val pageLoader: ReaderPageLoader,
    private val serialChapterLoader: SerialChapterLoader = SiteRegistrySerialChapterLoader(siteRegistry),
    private val exportGateway: GalleryExportGateway,
    private val historyRepository: HistoryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val preferencesRepository: PreferencesRepository,
    private val shelfRepository: ShelfRepository,
    private val playbackQueueStore: PlaybackQueueStore,
    private val dispatchers: AppDispatchers,
    private val persistenceScope: CoroutineScope? = null,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val resolveConcurrency: (Int) -> Int = { requested ->
        requested.coerceIn(1, ReaderBuffer.MAX_CONCURRENT_LOADS)
    },
) : ViewModel() {

    constructor(
        route: RavenRoute.Reader,
        siteRegistry: SiteRegistry,
        galleryPageRepository: GalleryPageRepository,
        galleryExporter: GalleryExporter,
        historyRepository: HistoryRepository,
        bookmarkRepository: BookmarkRepository,
        preferencesRepository: PreferencesRepository,
        shelfRepository: ShelfRepository,
        playbackQueueStore: PlaybackQueueStore,
        dispatchers: AppDispatchers,
        applicationScope: CoroutineScope,
    ) : this(
        route = route,
        siteRegistry = siteRegistry,
        pageLoader = RepositoryReaderPageLoader(galleryPageRepository),
        exportGateway = MediaGalleryExportGateway(galleryExporter),
        historyRepository = historyRepository,
        bookmarkRepository = bookmarkRepository,
        preferencesRepository = preferencesRepository,
        shelfRepository = shelfRepository,
        playbackQueueStore = playbackQueueStore,
        dispatchers = dispatchers,
        persistenceScope = applicationScope,
        resolveConcurrency = { requested ->
            val targetSiteId = try {
                SiteId.of(route.siteId)
            } catch (_: Throwable) {
                SiteId.GLOBAL
            }
            galleryPageRepository.recommendedImageConcurrency(targetSiteId, requested)
        },
    )

    private val parsedSiteId: SiteId? = runCatching { SiteId.of(route.siteId) }.getOrNull()
    private val siteId: SiteId = parsedSiteId ?: SiteId.GLOBAL
    private val isInvalidSiteId: Boolean = parsedSiteId == null
    private val mediaId: String = route.mediaId
    private val url: String = route.url
    private val offlineOnly: Boolean = route.offlineOnly || siteId.isLocal

    private val plugin = if (isInvalidSiteId) null else siteRegistry.get(siteId)
    private val descriptor: SiteDescriptor? = plugin?.descriptor
    private val supportsOriginalImages: Boolean =
        descriptor?.hasCapability(SiteCapability.ORIGINAL_IMAGES) == true

    /**
     * Preserved across all reading mode switches, `preferOriginalImages` reloads, and
     * configuration changes so page placeholders never jump in height.
     */
    private val sharedAspectRatios: MutableMap<Int, Float> = ConcurrentHashMap()

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            siteId = siteId,
            mediaId = mediaId,
            url = url,
            offlineOnly = offlineOnly,
            siteDisplayName = when {
                isInvalidSiteId -> route.siteId
                siteId.isLocal -> "本地导入"
                descriptor != null -> descriptor.displayName
                else -> siteId.value
            },
            supportsOriginalImages = supportsOriginalImages,
            currentPage = route.startPage.coerceAtLeast(1),
            queueSession = if (isInvalidSiteId) null else playbackQueueStore.sessionFor(route.queueToken, siteId, mediaId),
        ),
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ReaderEvent> = _events.asSharedFlow()

    private var readerBuffer: ReaderBuffer<LoadedReaderPage>? = null
    private var bufferCollectJob: Job? = null
    private var loadJob: Job? = null
    private var progressSaveJob: Job? = null
    private var autoPageJob: Job? = null
    private var exportJob: Job? = null
    private var chapterListJob: Job? = null
    private var chapterAdvanceJob: Job? = null
    private var appendNextChapterJob: Job? = null
    private var appendedProgressSaveJob: Job? = null
    // Latest appended-chapter anchor awaiting the debounce; flushed on early exit / onCleared.
    private var pendingAppendedProgress: PendingAppendedProgress? = null
    @Volatile
    private var activeSeriesMetadata: RavenRoute.ReaderParent? = null
    private val appendedChapterBuffers: MutableMap<String, ReaderBuffer<LoadedReaderPage>> = ConcurrentHashMap()
    private val appendedChapterCollectJobs: MutableMap<String, Job> = ConcurrentHashMap()
    private val appendedChapterRatios: MutableMap<String, MutableMap<Int, Float>> = ConcurrentHashMap()
    private val failedAppendChapterIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var preparedNextChapter: MediaItem? = null

    @Volatile
    private var preparedPreviousChapter: MediaItem? = null

    @Volatile
    private var serialChaptersPrepared: Boolean = false
    private var chapterTransitionEmitted: Boolean = false

    private var hasDirtyProgress: Boolean = false
    private var lastSavedPage: Int = -1
    private var lastSavedOffsetPx: Int = -1
    private var activePreferOriginal: Boolean = false
    private var activePrefetchPages: Int = ReaderBuffer.PREFETCH_AHEAD
    private var activeMemoryBudgetBytes: Long = ReaderBuffer.DEFAULT_OFFSCREEN_BUDGET_BYTES
    private var activeImageConcurrency: Int = ReaderBuffer.MAX_CONCURRENT_LOADS
    private var latestIncognitoMode: Boolean = false
    private var autoPagePausedByLifecycle: Boolean = false
    private var isUserZoomedOrDragging: Boolean = false
    private var hasPromptedQueueForCurrentBook: Boolean = false

    init {
        observePreferencesAndBookmarks()
        observeBookmarksForActiveChapter()
        observeFirstChapterBookmarks()
        observeQueueSession()
        loadGallery()
    }

    private fun observeFirstChapterBookmarks() {
        if (isInvalidSiteId) return
        viewModelScope.launch(dispatchers.main) {
            bookmarkRepository.observePageBookmarks(siteId, mediaId).collectLatest { bookmarks ->
                _uiState.update { it.copy(thumbnailPageBookmarks = bookmarks) }
            }
        }
    }

    private fun observeBookmarksForActiveChapter() {
        if (isInvalidSiteId) return
        viewModelScope.launch(dispatchers.main) {
            _uiState.map { it.displayMediaId }.distinctUntilChanged().collectLatest { id ->
                combine(
                    bookmarkRepository.observePageBookmarks(siteId, id),
                    bookmarkRepository.observeImageFavoritesForMedia(siteId, id),
                ) { bookmarks, imageFavorites ->
                    Pair(bookmarks, imageFavorites.mapTo(linkedSetOf()) { it.pageIndex })
                }.collectLatest { (bookmarks, imageFavoritePages) ->
                    _uiState.update { state ->
                        if (state.displayMediaId != id) state else state.copy(
                            pageBookmarks = bookmarks,
                            imageFavoritePages = imageFavoritePages,
                        )
                    }
                }
            }
        }
    }

    private fun observePreferencesAndBookmarks() {
        viewModelScope.launch(dispatchers.main) {
            preferencesRepository.settings.collectLatest { appSettings ->
                val targetPrefetch = appSettings.readerPrefetchPages.coerceIn(1, 12)
                val targetBudgetBytes = appSettings.readerMemoryBudgetMb.coerceIn(16, 512).toLong() * 1024L * 1024L
                val targetConcurrency = resolveConcurrency(appSettings.imageLoadConcurrency)
                latestIncognitoMode = appSettings.incognitoMode
                _uiState.update { state ->
                    state.copy(
                        showStatusBar = appSettings.readerShowStatusBar,
                        tapZonesEnabled = appSettings.readerTapZonesEnabled,
                        autoSplitWidePages = appSettings.readerAutoSplitWidePages,
                        showReaderTutorial = !appSettings.hasSeenTutorial(FeatureTutorialIds.READER_SERIAL),
                    )
                }
                val detail = _uiState.value.detail
                if (detail != null &&
                    readerBuffer != null &&
                    (activePrefetchPages != targetPrefetch ||
                        activeMemoryBudgetBytes != targetBudgetBytes ||
                        activeImageConcurrency != targetConcurrency)
                ) {
                    rebuildBuffer(
                        detail = detail,
                        pageNumbers = _uiState.value.pageNumbers,
                        sourceRoot = _uiState.value.sourceRoot,
                        preferOriginal = activePreferOriginal,
                        prefetchAhead = targetPrefetch,
                        offscreenBudgetBytes = targetBudgetBytes,
                        maxConcurrency = targetConcurrency,
                    )
                } else {
                    activePrefetchPages = targetPrefetch
                    activeMemoryBudgetBytes = targetBudgetBytes
                    activeImageConcurrency = targetConcurrency
                }
            }
        }
        if (isInvalidSiteId) return
        viewModelScope.launch(dispatchers.main) {
            combine(
                preferencesRepository.observeBookReaderPreferences(siteId, mediaId),
                preferencesRepository.observeSiteReaderPreferences(siteId),
                preferencesRepository.observeGlobalReaderPreferences(),
            ) { bookOverride, siteOverride, globalDefault ->
                val effective = bookOverride
                    ?: siteOverride?.copy(mediaId = mediaId)
                    ?: globalDefault.copy(siteId = siteId, mediaId = mediaId)
                ReaderMetadataSnapshot(
                    effective = effective,
                    isPerBookOverride = bookOverride != null,
                )
            }.collectLatest { snapshot ->
                val nextPreferOriginal = supportsOriginalImages && snapshot.effective.preferOriginalImages
                val detail = _uiState.value.detail

                _uiState.update { state ->
                    state.copy(
                        preferences = snapshot.effective,
                        isPerBookOverride = snapshot.isPerBookOverride,
                    )
                }

                // Rebuild buffer cleanly when preferOriginal differs from active buffer while keeping sharedAspectRatios.
                if (detail != null && readerBuffer != null && activePreferOriginal != nextPreferOriginal) {
                    rebuildBuffer(
                        detail = detail,
                        pageNumbers = _uiState.value.pageNumbers,
                        sourceRoot = _uiState.value.sourceRoot,
                        preferOriginal = nextPreferOriginal,
                    )
                }
            }
        }
    }

    private fun observeQueueSession() {
        val token = route.queueToken
        if (token.isNullOrBlank()) return
        viewModelScope.launch(dispatchers.main) {
            playbackQueueStore.currentSession.collectLatest {
                val active = playbackQueueStore.sessionFor(token, siteId, mediaId)
                _uiState.update { state ->
                    state.copy(
                        queueSession = active,
                        showQueueNextCard = if (active?.hasNext != true) false else state.showQueueNextCard,
                    )
                }
            }
        }
    }

    fun loadGallery(forceRefresh: Boolean = false) {
        if (!forceRefresh && loadJob?.isActive == true) return
        loadJob?.cancel()
        chapterListJob?.cancel()
        chapterAdvanceJob?.cancel()
        preparedNextChapter = null
        preparedPreviousChapter = null
        serialChaptersPrepared = false
        chapterTransitionEmitted = false
        activeSeriesMetadata = null
        _uiState.update {
            it.copy(
                previousChapter = null,
                nextChapter = null,
                chapters = emptyList(),
                isChapterListReady = false,
                isChapterListLoading = false,
                chapterListError = null,
                isChapterTransitionLoading = false,
            )
        }
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

            // Pre-check auth gate when opening online from an auth-gated site.
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
                                ?: "阅读 ${it.siteDisplayName} 图集需要先完成账号登录或导入 Cookie。",
                        )
                    }
                    return@launch
                }
            }

            try {
                val effectivePrefs = preferencesRepository
                    .observeEffectiveReaderPreferences(siteId, mediaId)
                    .first()
                val savedProgress = historyRepository.getReadingProgress(siteId, mediaId)
                val snapshot = pageLoader.loadDetail(
                    siteId = siteId,
                    mediaId = mediaId,
                    url = url,
                    offlineOnly = offlineOnly,
                )
                val detail = snapshot.detail
                activeSeriesMetadata = resolveSeriesMetadata(detail)
                val totalPages = detail.pageCount.coerceAtLeast(detail.pages.size).coerceAtLeast(1)
                val pageNumbers = if (offlineOnly && !siteId.isLocal && snapshot.downloadedPages.isNotEmpty() && !snapshot.isComplete) {
                    snapshot.downloadedPages.distinct().sorted()
                } else {
                    (1..totalPages).toList()
                }

                // Seed aspect ratios from page metadata whenever available.
                for (page in detail.pages) {
                    val w = page.width
                    val h = page.height
                    if (w != null && h != null && w > 0 && h > 0) {
                        sharedAspectRatios.putIfAbsent(page.index, w.toFloat() / h.toFloat())
                    }
                }

                val initialPage = resolveInitialPage(
                    requestedStartPage = route.startPage,
                    pageNumbers = pageNumbers,
                    startPosition = effectivePrefs.startPosition,
                    savedProgress = savedProgress,
                )
                val initialOffset = if (
                    savedProgress != null &&
                    savedProgress.currentPage == initialPage &&
                    effectivePrefs.mode == ReaderMode.STRIP
                ) {
                    savedProgress.scrollOffsetPx.coerceAtLeast(0)
                } else {
                    0
                }

                val currentSettings = runCatching { preferencesRepository.currentSettings() }.getOrNull()
                if (currentSettings != null) {
                    activePrefetchPages = currentSettings.readerPrefetchPages.coerceIn(1, 12)
                    activeMemoryBudgetBytes = currentSettings.readerMemoryBudgetMb.coerceIn(16, 512).toLong() * 1024L * 1024L
                    latestIncognitoMode = currentSettings.incognitoMode
                }
                val preferOriginal = supportsOriginalImages && effectivePrefs.preferOriginalImages
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        detail = detail,
                        pageNumbers = pageNumbers,
                        totalPages = totalPages,
                        sourceRoot = snapshot.sourceRoot,
                        currentPage = initialPage,
                        stripScrollOffsetPx = initialOffset,
                        pendingStripRestoreOffsetPx = initialOffset.takeIf { off -> off > 0 },
                        pageAspectRatios = sharedAspectRatios.toMap(),
                        preferences = effectivePrefs,
                        showStatusBar = currentSettings?.readerShowStatusBar ?: it.showStatusBar,
                        tapZonesEnabled = currentSettings?.readerTapZonesEnabled ?: it.tapZonesEnabled,
                        autoSplitWidePages = currentSettings?.readerAutoSplitWidePages ?: it.autoSplitWidePages,
                        errorMessage = null,
                        authRequired = false,
                    )
                }

                rebuildBuffer(
                    detail = detail,
                    pageNumbers = pageNumbers,
                    sourceRoot = snapshot.sourceRoot,
                    preferOriginal = preferOriginal,
                )

                _events.emit(ReaderEvent.ScrollToPage(page = initialPage, scrollOffsetPx = initialOffset))
                scheduleProgressSave(page = initialPage, scrollOffsetPx = initialOffset)
                prepareSerialChapters(detail)
            } catch (ce: CancellationException) {
                throw ce
            } catch (auth: SiteException.AuthRequired) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        authRequired = true,
                        authMessage = auth.message.takeIf(String::isNotBlank)
                            ?: descriptor?.accessNote
                            ?: "该站点需要有效登录会话后才能阅读。",
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message?.takeIf(String::isNotBlank) ?: "图集加载失败",
                    )
                }
            }
        }
    }

    private fun prepareSerialChapters(detail: GalleryDetail) {
        if (offlineOnly || detail.chaptersUrl.isNullOrBlank()) return
        chapterListJob = viewModelScope.launch(dispatchers.io) {
            _uiState.update {
                it.copy(isChapterListLoading = true, chapterListError = null)
            }
            try {
                cacheAdjacentChapters(serialChapterLoader.loadChapters(siteId, detail), detail)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isChapterListLoading = false,
                        chapterListError = error.message?.takeIf(String::isNotBlank) ?: "章节加载失败，请重试",
                    )
                }
            }
        }
    }

    private fun cacheAdjacentChapters(
        chapters: List<MediaItem>,
        detail: GalleryDetail,
    ) {
        val hints = listOfNotNull(
            detail.item.extra["chapterId"],
            detail.item.extra["chapterUuid"],
            detail.item.extra["chapterHid"],
        )
        val chapterUrlHint = detail.item.extra["chapterUrl"]?.trim().orEmpty()
        val currentIndex = chapters.indexOfFirst { chapter ->
            chapter.id == mediaId ||
                chapter.id == detail.item.id ||
                (chapterUrlHint.isNotEmpty() && chapter.url == chapterUrlHint) ||
                hints.any { hint ->
                    hint.isNotBlank() && (chapter.id == hint || chapter.id.endsWith(":$hint"))
                }
        }
        val previous = chapters.getOrNull(currentIndex - 1)?.takeIf { currentIndex > 0 && it.url.isNotBlank() }
        val next = chapters.getOrNull(currentIndex + 1)?.takeIf { currentIndex >= 0 && it.url.isNotBlank() }
        preparedPreviousChapter = previous
                preparedNextChapter = next
        serialChaptersPrepared = true
        _uiState.update {
            it.copy(
                previousChapter = previous,
                nextChapter = next,
                chapters = chapters,
                isChapterListReady = true,
                isChapterListLoading = false,
                chapterListError = null,
                hasReachedFinalSerialChapter = next == null && it.appendedChapters.isEmpty(),
            )
        }
    }

    /**
     * Seamlessly loads the next serial chapter and appends it to the continuous STRIP stream
     * without reloading or interrupting the active reader viewport.
     */
    fun preloadNextSerialChapterIfNeeded(forceRetry: Boolean = false) {
        val state = _uiState.value
        if (offlineOnly || !state.isSerialComic || state.preferences.mode != ReaderMode.STRIP || isInvalidSiteId) return
        if (appendNextChapterJob?.isActive == true) return
        if (!forceRetry && state.hasReachedFinalSerialChapter) return

        appendNextChapterJob = viewModelScope.launch(dispatchers.io) {
            try {
                chapterListJob?.join()
                val baseDetail = _uiState.value.detail ?: return@launch
                if (!serialChaptersPrepared) {
                    _uiState.update { it.copy(isChapterListLoading = true, chapterListError = null) }
                    cacheAdjacentChapters(serialChapterLoader.loadChapters(siteId, baseDetail), baseDetail)
                }
                val chapters = _uiState.value.chapters
                if (chapters.isEmpty()) return@launch

                val lastChapter = _uiState.value.appendedChapters.lastOrNull()
                val tailMediaId = lastChapter?.mediaId ?: mediaId
                val tailDetail = lastChapter?.detail ?: baseDetail
                val tailHints = listOfNotNull(
                    tailDetail.item.id,
                    tailDetail.item.extra["chapterId"],
                    tailDetail.item.extra["chapterUuid"],
                    tailDetail.item.extra["chapterHid"],
                )
                val tailUrlHint = tailDetail.item.extra["chapterUrl"]?.trim().orEmpty()
                val tailIndex = chapters.indexOfFirst { chapter ->
                    isMatchingSerialChapter(
                        chapter = chapter,
                        currentChapterId = tailMediaId,
                        currentChapterUrl = tailUrlHint,
                        hints = tailHints,
                    )
                }
                val nextChapterItem = chapters.getOrNull(tailIndex + 1)
                    ?.takeIf { tailIndex >= 0 && it.url.isNotBlank() }

                if (nextChapterItem == null) {
                    _uiState.update {
                        it.copy(
                            isAppendingNextChapter = false,
                            appendNextChapterError = null,
                            hasReachedFinalSerialChapter = true,
                        )
                    }
                    return@launch
                }

                if (!forceRetry && nextChapterItem.id in failedAppendChapterIds) return@launch
                val alreadyLoaded = nextChapterItem.id == mediaId ||
                    _uiState.value.appendedChapters.any { it.mediaId == nextChapterItem.id }
                if (alreadyLoaded) return@launch

                _uiState.update {
                    it.copy(
                        isAppendingNextChapter = true,
                        appendNextChapterError = null,
                    )
                }

                val snapshot = pageLoader.loadDetail(
                    siteId = siteId,
                    mediaId = nextChapterItem.id,
                    url = nextChapterItem.url,
                    offlineOnly = false,
                )
                val nextDetail = snapshot.detail
                val resolvedMediaId = nextDetail.item.id.ifBlank { nextChapterItem.id }
                val total = maxOf(nextDetail.pageCount, nextDetail.pages.size).coerceAtLeast(0)
                val nextPages = if (nextDetail.pages.isNotEmpty()) {
                    nextDetail.pages.map { it.index }.distinct().sorted()
                } else {
                    (1..total).toList()
                }
                if (nextPages.isEmpty()) {
                    throw IllegalStateException("下一话暂无可加载图片")
                }

                val ratioMap = ConcurrentHashMap<Int, Float>()
                for (page in nextDetail.pages) {
                    val w = page.width
                    val h = page.height
                    if (w != null && h != null && w > 0 && h > 0) {
                        ratioMap.putIfAbsent(page.index, w.toFloat() / h.toFloat())
                    }
                }
                appendedChapterRatios[resolvedMediaId] = ratioMap
                failedAppendChapterIds.remove(nextChapterItem.id)
                failedAppendChapterIds.remove(resolvedMediaId)

                val preferOriginal = supportsOriginalImages && _uiState.value.preferences.preferOriginalImages
                val chapterTitle = nextChapterItem.title.ifBlank { nextDetail.item.title }
                val newChapter = SerialStripChapter(
                    mediaId = resolvedMediaId,
                    title = chapterTitle,
                    url = nextChapterItem.url,
                    detail = nextDetail,
                    sourceRoot = snapshot.sourceRoot,
                    pageNumbers = nextPages,
                    pageAspectRatios = ratioMap.toMap(),
                )

                val hasAnotherAfter = chapters.getOrNull(tailIndex + 2)?.url?.isNotBlank() == true
                _uiState.update { state ->
                    if (state.appendedChapters.any { it.mediaId == resolvedMediaId }) {
                        state.copy(isAppendingNextChapter = false, appendNextChapterError = null)
                    } else {
                        state.copy(
                            appendedChapters = state.appendedChapters + newChapter,
                            isAppendingNextChapter = false,
                            appendNextChapterError = null,
                            hasReachedFinalSerialChapter = !hasAnotherAfter,
                        )
                    }
                }

                attachAppendedChapterBuffer(
                    chapter = newChapter,
                    preferOriginal = preferOriginal,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                val tailId = _uiState.value.chapters.let { list ->
                    val lastId = _uiState.value.appendedChapters.lastOrNull()?.mediaId ?: mediaId
                    val idx = list.indexOfFirst { it.id == lastId }
                    list.getOrNull(idx + 1)?.id
                }
                if (!tailId.isNullOrBlank()) {
                    failedAppendChapterIds.add(tailId)
                }
                _uiState.update {
                    it.copy(
                        isAppendingNextChapter = false,
                        appendNextChapterError = error.message?.takeIf(String::isNotBlank) ?: "下一话加载失败，点击重试",
                    )
                }
            }
        }
    }

    private fun attachAppendedChapterBuffer(
        chapter: SerialStripChapter,
        preferOriginal: Boolean,
    ) {
        val chapterId = chapter.mediaId
        appendedChapterCollectJobs.remove(chapterId)?.cancel()
        appendedChapterBuffers.remove(chapterId)?.close()
        val ratioMap = appendedChapterRatios.getOrPut(chapterId) { ConcurrentHashMap() }
        val perChapterBudget = (activeMemoryBudgetBytes / 2L).coerceAtLeast(16L * 1024L * 1024L)
        val buffer = ReaderBuffer<LoadedReaderPage>(
            pageNumbers = chapter.pageNumbers,
            scope = viewModelScope,
            byteSizeOf = { it.byteSize },
            aspectRatioOf = { it.aspectRatio },
            offscreenBudgetBytes = perChapterBudget,
            maxConcurrency = activeImageConcurrency,
            sharedRatios = ratioMap,
            prefetchAhead = activePrefetchPages.coerceAtLeast(3),
            loader = { pageNumber, forceReload ->
                pageLoader.loadRenderedPage(
                    detail = chapter.detail,
                    pageIndex = pageNumber,
                    preferOriginal = preferOriginal,
                    forceReload = forceReload,
                    offlineOnly = false,
                    sourceRoot = chapter.sourceRoot,
                )
            },
        )
        appendedChapterBuffers[chapterId] = buffer
        val collectJob = viewModelScope.launch(dispatchers.main) {
            buffer.state.collectLatest { bufState ->
                _uiState.update { state ->
                    val idx = state.appendedChapters.indexOfFirst { it.mediaId == chapterId }
                    if (idx < 0) return@update state
                    val updated = state.appendedChapters.toMutableList()
                    val current = updated[idx]
                    updated[idx] = current.copy(
                        bufferState = bufState,
                        pageAspectRatios = if (bufState.ratios.isEmpty()) ratioMap.toMap() else bufState.ratios,
                    )
                    state.copy(appendedChapters = updated)
                }
            }
        }
        appendedChapterCollectJobs[chapterId] = collectJob
        val firstPage = chapter.pageNumbers.firstOrNull() ?: 1
        buffer.updateWindow(page = firstPage, visible = setOf(firstPage))
    }

    fun onStripViewportProgress(
        anchorChapterId: String,
        anchorPage: Int,
        visiblePagesByChapter: Map<String, Set<Int>>,
        scrollOffsetPx: Int,
        remainingItemsToBottom: Int,
    ) {
        val state = _uiState.value
        if (anchorChapterId == mediaId) {
            clearPendingAppendedProgress()
            if (state.activeStripChapterId != null) {
                _uiState.update {
                    it.copy(
                        activeStripChapterId = null,
                        activeStripChapterTitle = null,
                        activeStripChapterPage = null,
                        activeStripChapterOffsetPx = 0,
                        activeStripChapterTotalPages = null,
                    )
                }
            }
            onVisiblePagesChanged(
                anchorPage = anchorPage,
                visiblePages = visiblePagesByChapter[mediaId].orEmpty().ifEmpty { setOf(anchorPage) },
                scrollOffsetPx = scrollOffsetPx,
            )
        } else {
            val chapter = state.appendedChapters.firstOrNull { it.mediaId == anchorChapterId }
            if (chapter != null) {
                if (state.activeStripChapterId == null) {
                    persistPrimaryAnchorBeforeAppended(state)
                }
                val safePage = nearestPageIn(chapter.pageNumbers, anchorPage)
                _uiState.update {
                    it.copy(
                        activeStripChapterId = chapter.mediaId,
                        activeStripChapterTitle = chapter.title,
                        activeStripChapterPage = safePage,
                        activeStripChapterOffsetPx = scrollOffsetPx.coerceAtLeast(0),
                        activeStripChapterTotalPages = chapter.pageNumbers.size,
                    )
                }
                scheduleAppendedChapterProgressSave(
                    chapter = chapter,
                    page = safePage,
                    scrollOffsetPx = scrollOffsetPx,
                )
            }
        }

        // Update decode window for every visible appended chapter (and reattach if trimmed earlier).
        visiblePagesByChapter.forEach { (chapId, pages) ->
            if (chapId != mediaId && pages.isNotEmpty()) {
                val chapter = state.appendedChapters.firstOrNull { it.mediaId == chapId }
                val existingBuffer = appendedChapterBuffers[chapId]
                if (existingBuffer != null) {
                    existingBuffer.updateWindow(page = pages.min(), visible = pages)
                } else if (chapter != null) {
                    attachAppendedChapterBuffer(
                        chapter = chapter,
                        preferOriginal = supportsOriginalImages && state.preferences.preferOriginalImages,
                    )
                }
            }
        }

        // Auto-preload next chapter smoothly when within 4 items of the bottom of the strip.
        if (state.isSerialComic && remainingItemsToBottom <= 4) {
            preloadNextSerialChapterIfNeeded()
        }
    }

    fun retryAppendedChapterPage(chapterMediaId: String, pageNumber: Int) {
        if (chapterMediaId == mediaId) {
            retryPage(pageNumber)
            return
        }
        val existing = appendedChapterBuffers[chapterMediaId]
        if (existing != null) {
            existing.retry(pageNumber)
        } else {
            val chapter = _uiState.value.appendedChapters.firstOrNull { it.mediaId == chapterMediaId } ?: return
            attachAppendedChapterBuffer(
                chapter = chapter,
                preferOriginal = supportsOriginalImages && _uiState.value.preferences.preferOriginalImages,
            )
        }
    }

    private fun scheduleAppendedChapterProgressSave(
        chapter: SerialStripChapter,
        page: Int,
        scrollOffsetPx: Int,
    ) {
        val series = activeSeriesMetadata ?: resolveSeriesMetadata(chapter.detail).also {
            activeSeriesMetadata = it
        }
        // Remember the newest appended-chapter anchor so an early exit (before the debounce elapses)
        // can still flush it instead of only persisting the first chapter's stale position.
        pendingAppendedProgress = PendingAppendedProgress(
            mediaId = chapter.mediaId,
            url = chapter.url,
            title = chapter.title,
            coverUrl = chapter.detail.item.coverUrl,
            kind = chapter.detail.item.kind,
            page = page,
            totalPages = chapter.pageNumbers.size.coerceAtLeast(1),
            scrollOffsetPx = scrollOffsetPx,
            series = series,
        )
        appendedProgressSaveJob?.cancel()
        appendedProgressSaveJob = viewModelScope.launch(dispatchers.io) {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            // Reverse scrolling back to the primary chapter (or into a different appended chapter)
            // must not let this delayed write promote an outdated chapter in history.
            if (_uiState.value.activeStripChapterId != chapter.mediaId) {
                if (pendingAppendedProgress?.mediaId == chapter.mediaId) {
                    pendingAppendedProgress = null
                }
                return@launch
            }
            persistProgressEntity(
                siteId = siteId,
                mediaId = chapter.mediaId,
                url = chapter.url,
                title = chapter.title,
                coverUrl = chapter.detail.item.coverUrl,
                kind = chapter.detail.item.kind,
                currentPage = page,
                totalPages = chapter.pageNumbers.size.coerceAtLeast(1),
                scrollOffsetPx = scrollOffsetPx,
                series = series,
            )
            if (pendingAppendedProgress?.mediaId == chapter.mediaId) {
                pendingAppendedProgress = null
            }
        }
    }

    private data class PendingAppendedProgress(
        val mediaId: String,
        val url: String,
        val title: String,
        val coverUrl: String,
        val kind: com.raven.app.core.model.MediaKind,
        val page: Int,
        val totalPages: Int,
        val scrollOffsetPx: Int,
        val series: RavenRoute.ReaderParent?,
    )

    /** Persists the pending appended-chapter anchor synchronously; safe under cancellation. */
    private suspend fun flushPendingAppendedProgress() {
        val pending = pendingAppendedProgress ?: return
        persistProgressEntity(
            siteId = siteId,
            mediaId = pending.mediaId,
            url = pending.url,
            title = pending.title,
            coverUrl = pending.coverUrl,
            kind = pending.kind,
            currentPage = pending.page,
            totalPages = pending.totalPages,
            scrollOffsetPx = pending.scrollOffsetPx,
            series = pending.series,
        )
    }

    fun completeReaderTutorial() {
        _uiState.update { it.copy(showReaderTutorial = false) }
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.markTutorialSeen(FeatureTutorialIds.READER_SERIAL)
        }
    }

    fun skipAllTutorials() {
        _uiState.update { it.copy(showReaderTutorial = false) }
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.markTutorialsSeen(FeatureTutorialIds.ALL)
        }
    }

    private fun requestNextChapterAtBoundary(): Boolean = requestChapter(SerialChapterDirection.NEXT)

    fun openPreviousChapter() {
        requestChapter(SerialChapterDirection.PREVIOUS)
    }

    fun openNextChapter() {
        requestChapter(SerialChapterDirection.NEXT)
    }

    fun openChapter(chapter: MediaItem) {
        val detail = _uiState.value.detail ?: return
        val hints = listOfNotNull(
            detail.item.id,
            detail.item.extra["chapterId"],
            detail.item.extra["chapterUuid"],
            detail.item.extra["chapterHid"],
        )
        val isCurrent = isMatchingSerialChapter(
            chapter = chapter,
            // 连读时视口可能已经在追加的下一话里，判断需以可见章节为准。
            currentChapterId = _uiState.value.displayMediaId,
            currentChapterUrl = detail.item.extra["chapterUrl"].orEmpty(),
            hints = hints,
        )
        dismissSheet()
        if (!isCurrent) requestChapter(direction = null, requestedChapter = chapter)
    }

    fun refreshChapters() {
        val detail = _uiState.value.detail ?: return
        if (offlineOnly || detail.chaptersUrl.isNullOrBlank() || isInvalidSiteId) return
        chapterListJob?.cancel()
        serialChaptersPrepared = false
        prepareSerialChapters(detail)
    }

    private fun requestChapter(
        direction: SerialChapterDirection?,
        requestedChapter: MediaItem? = null,
    ): Boolean {
        val detail = _uiState.value.detail ?: return false
        if (offlineOnly || detail.chaptersUrl.isNullOrBlank() || isInvalidSiteId) return false
        if (chapterTransitionEmitted || chapterAdvanceJob?.isActive == true) return true

        chapterAdvanceJob = viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isChapterTransitionLoading = true) }
            try {
                chapterListJob?.join()
                if (!serialChaptersPrepared) {
                    _uiState.update { it.copy(isChapterListLoading = true, chapterListError = null) }
                    cacheAdjacentChapters(serialChapterLoader.loadChapters(siteId, detail), detail)
                }
                val target = requestedChapter ?: when (direction) {
                    SerialChapterDirection.PREVIOUS -> preparedPreviousChapter
                    SerialChapterDirection.NEXT -> preparedNextChapter
                    null -> null
                }
                if (target == null) {
                    emitSnackbar(
                        when (direction) {
                            SerialChapterDirection.PREVIOUS -> "已经是第一话"
                            SerialChapterDirection.NEXT -> "已经是最后一话"
                            null -> "所选章节不可用"
                        },
                    )
                    return@launch
                }

                chapterTransitionEmitted = true
                flushProgress()
                _events.emit(
                    ReaderEvent.OpenChapter(
                        nextItem = target,
                        parent = route.parent?.takeIf { it.seriesId.isNotBlank() } ?: activeSeriesMetadata,
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                val message = error.message?.takeIf(String::isNotBlank) ?: "章节加载失败，请重试"
                _uiState.update {
                    it.copy(isChapterListLoading = false, chapterListError = message)
                }
                emitSnackbar(message)
            } finally {
                _uiState.update { it.copy(isChapterTransitionLoading = false) }
            }
        }
        return true
    }

    fun onChapterNavigationFailed() {
        chapterTransitionEmitted = false
        _uiState.update { it.copy(isChapterTransitionLoading = false) }
        emitSnackbar("无法打开所选章节，请重试")
    }

    fun onScreenStarted() {
        if (chapterAdvanceJob?.isActive != true) {
            chapterTransitionEmitted = false
        }
    }

    private fun resolveInitialPage(
        requestedStartPage: Int,
        pageNumbers: List<Int>,
        startPosition: ReaderStartPosition,
        savedProgress: ReadingProgress?,
    ): Int {
        if (pageNumbers.isEmpty()) return 1
        val target = when {
            requestedStartPage > 1 -> requestedStartPage
            startPosition == ReaderStartPosition.LAST -> pageNumbers.last()
            startPosition == ReaderStartPosition.FIRST -> pageNumbers.first()
            savedProgress != null && !savedProgress.completed && savedProgress.currentPage in pageNumbers ->
                savedProgress.currentPage
            else -> pageNumbers.first()
        }
        return nearestPageIn(pageNumbers, target)
    }

    private fun rebuildBuffer(
        detail: GalleryDetail,
        pageNumbers: List<Int>,
        sourceRoot: String,
        preferOriginal: Boolean,
        prefetchAhead: Int = activePrefetchPages,
        offscreenBudgetBytes: Long = activeMemoryBudgetBytes,
        maxConcurrency: Int = activeImageConcurrency,
    ) {
        bufferCollectJob?.cancel()
        readerBuffer?.close()
        activePreferOriginal = preferOriginal
        activePrefetchPages = prefetchAhead
        activeMemoryBudgetBytes = offscreenBudgetBytes
        activeImageConcurrency = maxConcurrency.coerceIn(1, ReaderBuffer.MAX_CONCURRENT_LOADS)

        val buffer = ReaderBuffer<LoadedReaderPage>(
            pageNumbers = pageNumbers,
            scope = viewModelScope,
            byteSizeOf = { it.byteSize },
            aspectRatioOf = { it.aspectRatio },
            offscreenBudgetBytes = offscreenBudgetBytes,
            maxConcurrency = activeImageConcurrency,
            sharedRatios = sharedAspectRatios,
            prefetchAhead = prefetchAhead,
            loader = { pageNumber, forceReload ->
                pageLoader.loadRenderedPage(
                    detail = detail,
                    pageIndex = pageNumber,
                    preferOriginal = preferOriginal,
                    forceReload = forceReload,
                    offlineOnly = offlineOnly,
                    sourceRoot = sourceRoot,
                )
            },
        )
        readerBuffer = buffer

        bufferCollectJob = viewModelScope.launch(dispatchers.main) {
            buffer.state.collectLatest { bufState ->
                _uiState.update { state ->
                    state.copy(
                        bufferState = bufState,
                        pageAspectRatios = if (bufState.ratios.isEmpty()) {
                            sharedAspectRatios.toMap()
                        } else {
                            bufState.ratios
                        },
                    )
                }
            }
        }

        val current = _uiState.value.currentPage
        val visible = _uiState.value.currentSheetPages.toSet()
        buffer.updateWindow(page = current, visible = visible)
    }

    // ---- Viewport, Page & Buffer Updates ----

    fun onViewportOrientationChanged(isLandscape: Boolean) {
        if (_uiState.value.isLandscapeViewport == isLandscape) return
        _uiState.update { it.copy(isLandscapeViewport = isLandscape) }
        val state = _uiState.value
        if (state.pageNumbers.isNotEmpty()) {
            readerBuffer?.updateWindow(
                page = state.currentPage,
                visible = state.currentSheetPages.toSet(),
            )
        }
    }

    fun onVisiblePagesChanged(
        anchorPage: Int,
        visiblePages: Set<Int> = setOf(anchorPage),
        scrollOffsetPx: Int = 0,
    ) {
        val state = _uiState.value
        if (state.pageNumbers.isEmpty()) return
        val safeAnchor = nearestPageIn(state.pageNumbers, anchorPage)
        val safeVisible = visiblePages.filter { it in state.pageNumbers }.toSet().ifEmpty { setOf(safeAnchor) }
        val safeOffset = scrollOffsetPx.coerceAtLeast(0)

        val pageChanged = safeAnchor != state.currentPage
        val offsetChanged = state.preferences.mode == ReaderMode.STRIP && safeOffset != state.stripScrollOffsetPx

        if (pageChanged || offsetChanged) {
            _uiState.update {
                it.copy(
                    currentPage = safeAnchor,
                    stripScrollOffsetPx = if (it.preferences.mode == ReaderMode.STRIP) safeOffset else it.stripScrollOffsetPx,
                )
            }
            scheduleProgressSave(page = safeAnchor, scrollOffsetPx = safeOffset)
            maybeTriggerQueueCompletion(safeAnchor)
        }
        readerBuffer?.updateWindow(page = safeAnchor, visible = safeVisible)
    }

    fun consumePendingStripRestoreOffset() {
        if (_uiState.value.pendingStripRestoreOffsetPx != null) {
            _uiState.update { it.copy(pendingStripRestoreOffsetPx = null) }
        }
    }

    fun jumpToPage(
        page: Int,
        scrollOffsetPx: Int = 0,
        closeOverlays: Boolean = false,
        chapterId: String = mediaId,
    ) {
        val state = _uiState.value
        val safeOffset = scrollOffsetPx.coerceAtLeast(0)
        if (chapterId != mediaId) {
            if (state.preferences.mode != ReaderMode.STRIP || chapterId != state.displayMediaId) return
            val activeChapter = state.appendedChapters.firstOrNull { it.mediaId == chapterId } ?: return
            val target = nearestPageIn(activeChapter.pageNumbers, page)
            if (state.activeStripChapterId == null) {
                persistPrimaryAnchorBeforeAppended(state)
            }
            _uiState.update {
                it.copy(
                    activeStripChapterPage = target,
                    activeStripChapterOffsetPx = safeOffset,
                    activeSheet = if (closeOverlays) ReaderSheet.NONE else it.activeSheet,
                    activeQuickPanel = if (closeOverlays) ReaderQuickPanel.NONE else it.activeQuickPanel,
                )
            }
            val buffer = appendedChapterBuffers[activeChapter.mediaId]
            buffer?.updateWindow(page = target, visible = setOf(target))
            _events.tryEmit(ReaderEvent.ScrollToPage(page = target, scrollOffsetPx = safeOffset, chapterId = activeChapter.mediaId))
            scheduleAppendedChapterProgressSave(chapter = activeChapter, page = target, scrollOffsetPx = safeOffset)
            return
        }

        if (state.pageNumbers.isEmpty()) return
        clearPendingAppendedProgress()
        val target = nearestPageIn(state.pageNumbers, page)

        _uiState.update {
            it.copy(
                currentPage = target,
                stripScrollOffsetPx = if (it.preferences.mode == ReaderMode.STRIP) safeOffset else it.stripScrollOffsetPx,
                pendingStripRestoreOffsetPx = if (it.preferences.mode == ReaderMode.STRIP && safeOffset > 0) safeOffset else null,
                activeSheet = if (closeOverlays) ReaderSheet.NONE else it.activeSheet,
                activeQuickPanel = if (closeOverlays) ReaderQuickPanel.NONE else it.activeQuickPanel,
            )
        }
        val visible = _uiState.value.currentSheetPages.toSet()
        readerBuffer?.updateWindow(page = target, visible = visible)
        _events.tryEmit(ReaderEvent.ScrollToPage(page = target, scrollOffsetPx = safeOffset, chapterId = mediaId))
        scheduleProgressSave(page = target, scrollOffsetPx = safeOffset)
        maybeTriggerQueueCompletion(target)
    }

    fun retryPage(pageNumber: Int) {
        readerBuffer?.retry(pageNumber)
    }

    fun onInteractionStateChanged(isZoomedOrDragging: Boolean) {
        isUserZoomedOrDragging = isZoomedOrDragging
    }

    // ---- Chrome, Panels & Sheets ----

    fun toggleChrome() {
        _uiState.update { state ->
            val nextVisible = !state.chromeVisible
            state.copy(
                chromeVisible = nextVisible,
                activeQuickPanel = if (!nextVisible) ReaderQuickPanel.NONE else state.activeQuickPanel,
            )
        }
    }

    fun setChromeVisible(visible: Boolean) {
        _uiState.update { state ->
            state.copy(
                chromeVisible = visible,
                activeQuickPanel = if (!visible) ReaderQuickPanel.NONE else state.activeQuickPanel,
            )
        }
    }

    fun setShowStatusBar(show: Boolean) {
        _uiState.update { it.copy(showStatusBar = show) }
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.setReaderShowStatusBar(show)
        }
    }

    fun setTapZonesEnabled(enabled: Boolean) {
        _uiState.update { it.copy(tapZonesEnabled = enabled) }
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.setReaderTapZonesEnabled(enabled)
        }
    }

    fun setAutoSplitWidePages(enabled: Boolean) {
        _uiState.update { it.copy(autoSplitWidePages = enabled) }
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.setReaderAutoSplitWidePages(enabled)
        }
    }

    fun openSheet(sheet: ReaderSheet) {
        _uiState.update {
            it.copy(
                activeSheet = sheet,
                activeQuickPanel = ReaderQuickPanel.NONE,
            )
        }
    }

    fun dismissSheet() {
        _uiState.update { it.copy(activeSheet = ReaderSheet.NONE) }
    }

    fun toggleQuickPanel(panel: ReaderQuickPanel) {
        _uiState.update { state ->
            val next = if (state.activeQuickPanel == panel) ReaderQuickPanel.NONE else panel
            state.copy(
                chromeVisible = true,
                activeQuickPanel = next,
                activeSheet = ReaderSheet.NONE,
            )
        }
    }

    // ---- Hardware Volume-Key Paging ----

    /**
     * Handles Android hardware Volume Up/Down key events.
     *
     * - Returns `false` when `volumeKeyPaging` is disabled so Android handles media volume normally.
     * - Supports normal (`down -> next, up -> prev`) and reversed (`up -> next, down -> prev`) direction.
     * - Supports long-press jump straight to first / last page via [SpreadNavigator.boundaryPage].
     */
    fun onStripEndReached() {
        val state = _uiState.value
        if (state.isSerialComic && state.preferences.mode == ReaderMode.STRIP) {
            preloadNextSerialChapterIfNeeded(forceRetry = true)
            return
        }
        val lastPage = state.pageNumbers.lastOrNull() ?: return
        onVisiblePagesChanged(
            anchorPage = lastPage,
            visiblePages = setOf(lastPage),
            scrollOffsetPx = state.stripScrollOffsetPx,
        )
        if (_uiState.value.queueSession?.hasNext == true) {
            _uiState.update { it.copy(showQueueNextCard = true) }
        } else {
            requestNextChapterAtBoundary()
        }
    }

    fun navigateAdjacentPage(forward: Boolean): Boolean {
        val state = _uiState.value
        val nav = state.spreadNavigator ?: return false
        val target = nav.adjacentPage(currentPage = state.currentPage, forward = forward)
        if (target != null) {
            jumpToPage(target)
            return true
        } else if (forward && state.queueSession?.hasNext == true) {
            _uiState.update { it.copy(showQueueNextCard = true) }
            return true
        } else if (forward && requestNextChapterAtBoundary()) {
            return true
        }
        return false
    }

    fun handleVolumeKey(up: Boolean, isLongPress: Boolean): Boolean {
        val state = _uiState.value
        if (!state.preferences.volumeKeyPaging || state.pageNumbers.isEmpty()) {
            return false
        }
        val forward = if (state.preferences.reverseVolumeKeys) up else !up
        val nav = state.spreadNavigator ?: return false
        val target = if (isLongPress) {
            nav.boundaryPage(forward = forward)
        } else {
            nav.adjacentPage(currentPage = state.currentPage, forward = forward)
        }
        if (target != null) {
            jumpToPage(target)
        } else if (forward && state.queueSession?.hasNext == true) {
            _uiState.update { it.copy(showQueueNextCard = true) }
        } else if (forward) {
            requestNextChapterAtBoundary()
        }
        return true
    }

    // ---- Reader Preferences (Per-Book Override vs Site Default) ----

    fun setPerBookOverride(enabled: Boolean) {
        val current = _uiState.value.preferences
        _uiState.update { it.copy(isPerBookOverride = enabled) }
        viewModelScope.launch(dispatchers.io) {
            if (enabled) {
                preferencesRepository.saveReaderPreferences(
                    current.copy(siteId = siteId, mediaId = mediaId),
                )
                emitSnackbar("已启用本书独立阅读设置")
            } else {
                preferencesRepository.resetBookReaderPreferences(siteId, mediaId)
                emitSnackbar("已切换为跟随来源默认设置")
            }
        }
    }

    fun resetToSiteDefault() {
        _uiState.update { it.copy(isPerBookOverride = false) }
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.resetBookReaderPreferences(siteId, mediaId)
            emitSnackbar("已恢复来源默认设置")
        }
    }

    fun setReadingMode(mode: ReaderMode) {
        val prevMode = _uiState.value.preferences.mode
        val preservedPage = _uiState.value.currentPage
        val preservedOffset = if (mode == ReaderMode.STRIP) _uiState.value.stripScrollOffsetPx else 0
        updatePreferences { it.copy(mode = mode) }
        if (prevMode != mode && _uiState.value.pageNumbers.isNotEmpty()) {
            _uiState.update { state ->
                state.copy(
                    pendingStripRestoreOffsetPx = if (mode == ReaderMode.STRIP && preservedOffset > 0) preservedOffset else null,
                )
            }
            val visible = _uiState.value.currentSheetPages.toSet()
            readerBuffer?.updateWindow(page = preservedPage, visible = visible)
            _events.tryEmit(ReaderEvent.ScrollToPage(page = preservedPage, scrollOffsetPx = preservedOffset))
        }
    }

    fun setReaderFit(fit: ReaderFit) = updatePreferences { it.copy(fit = fit) }

    fun setCropPercent(cropPercent: Int) =
        updatePreferences { it.copy(cropPercent = ReaderGeometry.clampCropPercent(cropPercent)) }

    fun setCombineCover(combineCover: Boolean) {
        updatePreferences { it.copy(combineCover = combineCover) }
        val state = _uiState.value
        if (state.pageNumbers.isNotEmpty()) {
            readerBuffer?.updateWindow(
                page = state.currentPage,
                visible = state.currentSheetPages.toSet(),
            )
        }
    }

    fun setPreferOriginalImages(preferOriginal: Boolean) {
        if (!supportsOriginalImages) return
        updatePreferences { it.copy(preferOriginalImages = preferOriginal) }
    }

    fun setVolumeKeyPaging(enabled: Boolean) = updatePreferences { it.copy(volumeKeyPaging = enabled) }

    fun setReverseVolumeKeys(reversed: Boolean) = updatePreferences { it.copy(reverseVolumeKeys = reversed) }

    fun setKeepScreenOn(enabled: Boolean) = updatePreferences { it.copy(keepScreenOn = enabled) }

    /** `-1` = follow system brightness, `0..100` = explicit percent. */
    fun setBrightnessPercent(percent: Int) {
        val clamped = if (percent < 0) -1 else percent.coerceIn(0, 100)
        updatePreferences { it.copy(brightnessPercent = clamped) }
    }

    fun setStartPosition(position: ReaderStartPosition) =
        updatePreferences { it.copy(startPosition = position) }

    private fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) {
        val currentState = _uiState.value
        val updated = transform(currentState.preferences)
        val scoped = if (currentState.isPerBookOverride) {
            updated.copy(siteId = siteId, mediaId = mediaId)
        } else {
            updated.copy(siteId = siteId, mediaId = null)
        }
        // Optimistic state update keeps sliders/switches instantaneous with zero lag.
        _uiState.update { it.copy(preferences = updated) }
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.saveReaderPreferences(scoped)
        }
    }

    // ---- Auto-Page & Slideshow (Waits for Current Page Render Ready) ----

    fun setAutoPageInterval(seconds: Int) {
        val sanitized = if (seconds in ALLOWED_AUTO_PAGE_INTERVALS) seconds else 0
        _uiState.update { it.copy(autoPageIntervalSeconds = sanitized) }
        restartAutoPageLoop()
    }

    fun setAutoPagePausedByLifecycle(paused: Boolean) {
        if (autoPagePausedByLifecycle == paused) return
        autoPagePausedByLifecycle = paused
        if (paused) {
            autoPageJob?.cancel()
            autoPageJob = null
        } else if (_uiState.value.autoPageIntervalSeconds > 0) {
            restartAutoPageLoop()
        }
    }

    fun setSlideshowOrder(order: SlideshowOrder) {
        _uiState.update { it.copy(slideshowOrder = order) }
    }

    private fun restartAutoPageLoop() {
        autoPageJob?.cancel()
        val intervalSeconds = _uiState.value.autoPageIntervalSeconds
        if (intervalSeconds <= 0 || autoPagePausedByLifecycle) return

        autoPageJob = viewModelScope.launch(dispatchers.main) {
            val visitedRandom = LinkedHashSet<Int>()
            while (isActive) {
                val currentInterval = _uiState.value.autoPageIntervalSeconds
                if (currentInterval <= 0) break

                // Strictly wait until the current page is render-ready and no sheet is blocking the view.
                uiState.first { state ->
                    state.autoPageIntervalSeconds > 0 &&
                        !state.isLoading &&
                        state.activeSheet == ReaderSheet.NONE &&
                        state.isCurrentPageRenderReady
                }

                delay(currentInterval * 1_000L)

                val latest = _uiState.value
                if (latest.autoPageIntervalSeconds <= 0) break
                if (latest.activeSheet != ReaderSheet.NONE || isUserZoomedOrDragging || !latest.isCurrentPageRenderReady) {
                    continue
                }

                val pages = latest.pageNumbers
                if (pages.size <= 1) {
                    val opensNextChapter = latest.slideshowOrder == SlideshowOrder.SEQUENTIAL &&
                        latest.queueSession?.hasNext != true &&
                        requestNextChapterAtBoundary()
                    _uiState.update { it.copy(autoPageIntervalSeconds = 0) }
                    if (!opensNextChapter) emitSnackbar("自动翻页已到达末尾")
                    break
                }

                val nextPage: Int? = when (latest.slideshowOrder) {
                    SlideshowOrder.SEQUENTIAL -> {
                        latest.spreadNavigator?.adjacentPage(latest.currentPage, forward = true)
                    }
                    SlideshowOrder.RANDOM -> {
                        visitedRandom.add(latest.currentPage)
                        val remaining = pages.filter { it !in visitedRandom }
                        val pool = remaining.ifEmpty {
                            visitedRandom.clear()
                            visitedRandom.add(latest.currentPage)
                            pages.filter { it != latest.currentPage }
                        }
                        pool.takeIf { it.isNotEmpty() }?.let { list ->
                            list[random.nextInt(list.size)]
                        }
                    }
                }

                if (nextPage != null) {
                    jumpToPage(nextPage)
                } else {
                    val opensNextChapter = latest.slideshowOrder == SlideshowOrder.SEQUENTIAL &&
                        latest.queueSession?.hasNext != true &&
                        requestNextChapterAtBoundary()
                    _uiState.update {
                        it.copy(
                            autoPageIntervalSeconds = 0,
                            showQueueNextCard = it.queueSession?.hasNext == true,
                        )
                    }
                    if (!opensNextChapter) emitSnackbar("自动翻页已到达末尾")
                    break
                }
            }
        }
    }

    // ---- Bookmarks & Single-Image Favorites ----

    /**
     * Resolves the chapter that currently owns the viewport. In continuous STRIP mode the user can
     * scroll into an appended serial chapter; every page-scoped action (bookmark, image favorite,
     * share/export) must target *that* chapter's mediaId and pages, not the initially opened one.
     */
    private data class ActivePageContext(
        val mediaId: String,
        val url: String,
        val title: String,
        val coverUrl: String,
        val detail: GalleryDetail,
        val page: Int,
        val totalPages: Int,
        val bufferState: ReaderBufferState<LoadedReaderPage>,
    )

    private fun resolveActivePageContext(state: ReaderUiState, fallbackDetail: GalleryDetail): ActivePageContext {
        val chapterId = state.activeStripChapterId
        val chapter = if (state.preferences.mode == ReaderMode.STRIP && chapterId != null) {
            state.appendedChapters.firstOrNull { it.mediaId == chapterId }
        } else {
            null
        }
        return if (chapter != null) {
            ActivePageContext(
                mediaId = chapter.mediaId,
                url = chapter.url,
                title = chapter.title,
                coverUrl = chapter.detail.item.coverUrl,
                detail = chapter.detail,
                page = state.activeStripChapterPage ?: state.currentPage,
                totalPages = chapter.pageNumbers.size.coerceAtLeast(1),
                bufferState = chapter.bufferState,
            )
        } else {
            ActivePageContext(
                mediaId = mediaId,
                url = fallbackDetail.item.url,
                title = fallbackDetail.item.title,
                coverUrl = fallbackDetail.item.coverUrl,
                detail = fallbackDetail,
                page = state.currentPage,
                totalPages = state.totalPages.coerceAtLeast(1),
                bufferState = state.bufferState,
            )
        }
    }

    fun toggleCurrentPageBookmark(note: String = "") {
        val state = _uiState.value
        val detail = state.detail ?: return
        val ctx = resolveActivePageContext(state, detail)
        viewModelScope.launch(dispatchers.io) {
            val added = bookmarkRepository.togglePageBookmark(
                PageBookmark(
                    id = "",
                    siteId = siteId,
                    mediaId = ctx.mediaId,
                    url = ctx.url,
                    title = ctx.title,
                    coverUrl = ctx.coverUrl,
                    page = ctx.page,
                    totalPages = ctx.totalPages,
                    scrollOffsetPx = if (ctx.mediaId == mediaId) state.stripScrollOffsetPx else state.activeStripChapterOffsetPx,
                    note = note.trim(),
                    createdAt = clock(),
                ),
            )
            emitSnackbar(if (added) "已添加第 ${ctx.page} 页书签" else "已取消第 ${ctx.page} 页书签")
        }
    }

    fun deletePageBookmark(bookmarkId: String) {
        viewModelScope.launch(dispatchers.io) {
            bookmarkRepository.deletePageBookmark(bookmarkId)
            emitSnackbar("已删除书签")
        }
    }

    fun toggleCurrentPageImageFavorite() {
        val state = _uiState.value
        val detail = state.detail ?: return
        val ctx = resolveActivePageContext(state, detail)
        val page = ctx.page
        val loadedPage = ctx.bufferState.pages[page]?.page
        val detailPage = ctx.detail.pages.firstOrNull { it.index == page }
        val pageUrl = loadedPage?.pageUrl?.takeIf(String::isNotBlank)
            ?: detailPage?.pageUrl?.takeIf(String::isNotBlank)
            ?: ctx.url
        val imageUrl = loadedPage?.imageUrl?.takeIf(String::isNotBlank)
            ?: detailPage?.imageUrl?.takeIf(String::isNotBlank)
            ?: detailPage?.thumbUrl?.takeIf(String::isNotBlank)
            ?: ctx.coverUrl

        viewModelScope.launch(dispatchers.io) {
            val added = bookmarkRepository.toggleImageFavorite(
                ImageFavorite(
                    id = "",
                    siteId = siteId,
                    mediaId = ctx.mediaId,
                    mediaTitle = ctx.title,
                    pageUrl = pageUrl,
                    imageUrl = imageUrl,
                    pageIndex = page,
                    addedAt = clock(),
                ),
            )
            emitSnackbar(if (added) "已收藏第 $page 页单图" else "已取消收藏第 $page 页单图")
        }
    }

    // ---- Export & Share (GalleryExporter) ----

    fun exportCurrentPage(mode: ExportMode) {
        val state = _uiState.value
        val detail = state.detail ?: return
        // In STRIP mode the visible page may belong to an appended chapter; export must use that
        // chapter's detail/pages/source so the exported bytes match the image on screen.
        val ctx = resolveActivePageContext(state, detail)
        exportPages(pages = listOf(ctx.page), mode = mode, followActiveChapter = true)
    }

    fun exportCbz(rawRangeInput: String? = null) {
        val state = _uiState.value
        val total = state.totalPages
        if (total <= 0) return

        val targetPages = if (rawRangeInput.isNullOrBlank()) {
            state.pageNumbers.ifEmpty { (1..total).toList() }
        } else {
            try {
                PageSelectionParser.parse(rawRangeInput, total)
            } catch (_: IllegalArgumentException) {
                emitSnackbar("页码格式无效（有效范围 1..$total，如 1, 3, 5-8）")
                return
            }
        }
        // Whole-gallery archive: page numbers come from the opened gallery, never an appended one.
        exportPages(pages = targetPages, mode = ExportMode.CBZ, followActiveChapter = false)
    }

    fun exportPages(
        pages: List<Int>,
        mode: ExportMode,
        followActiveChapter: Boolean = false,
    ) {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.isExporting) return
        // The current-page path follows the on-screen appended chapter; range/gallery exports keep
        // the opened gallery's detail because their page numbers refer to it.
        val ctx = if (followActiveChapter) resolveActivePageContext(state, detail) else null

        val unique = pages.distinct().sorted()
        if (unique.isEmpty()) {
            emitSnackbar("没有可导出的图页")
            return
        }

        exportJob?.cancel()
        exportJob = viewModelScope.launch(dispatchers.io) {
            val actionName = when (mode) {
                ExportMode.SAVE_IMAGES -> "保存原图"
                ExportMode.SHARE_IMAGES -> "准备分享"
                ExportMode.CBZ -> "打包 CBZ"
            }
            _uiState.update { it.copy(isExporting = true, exportStatusText = "正在$actionName…") }
            try {
                val preferOriginal = supportsOriginalImages && state.preferences.preferOriginalImages
                // Non-following exports use the opened gallery's detail and root.
                val exportDetail = ctx?.detail ?: detail
                val exportSourceRoot = if (ctx != null && ctx.mediaId != mediaId) {
                    state.appendedChapters.firstOrNull { it.mediaId == ctx.mediaId }?.sourceRoot
                        ?: state.sourceRoot
                } else {
                    state.sourceRoot
                }
                val prepared = exportGateway.prepare(
                    title = exportDetail.item.title,
                    pages = unique,
                    mode = mode,
                    loadPageBytes = { pageIndex ->
                        pageLoader.loadOriginalBytes(
                            detail = exportDetail,
                            pageIndex = pageIndex,
                            preferOriginal = preferOriginal,
                            offlineOnly = offlineOnly,
                            sourceRoot = exportSourceRoot,
                        )
                    },
                    onProgress = { text ->
                        _uiState.update { it.copy(exportStatusText = text) }
                    },
                )
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                when (mode) {
                    ExportMode.SHARE_IMAGES -> {
                        _events.emit(
                            ReaderEvent.RequestShareExport(
                                prepared = prepared,
                                subject = "${exportDetail.item.title} - 第 ${unique.first()} 页",
                            ),
                        )
                    }
                    ExportMode.CBZ -> {
                        val file = prepared.files.single()
                        _events.emit(
                            ReaderEvent.RequestSaveDocument(
                                prepared = prepared,
                                suggestedFileName = file.file.name,
                                mimeType = file.mimeType,
                            ),
                        )
                    }
                    ExportMode.SAVE_IMAGES -> {
                        if (prepared.files.size == 1) {
                            val file = prepared.files.single()
                            _events.emit(
                                ReaderEvent.RequestSaveDocument(
                                    prepared = prepared,
                                    suggestedFileName = file.file.name,
                                    mimeType = file.mimeType,
                                ),
                            )
                        } else {
                            _events.emit(ReaderEvent.RequestOpenDocumentTree(prepared = prepared))
                        }
                    }
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                throw ce
            } catch (error: Throwable) {
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                emitSnackbar(error.message?.takeIf(String::isNotBlank) ?: "${actionName}失败")
            }
        }
    }

    fun prepareShareUris(
        context: Context,
        authority: String,
        prepared: PreparedExport,
    ): List<Uri> = exportGateway.prepareShareUris(
        context = context,
        authority = authority,
        prepared = prepared,
    )

    fun completeSafExport(
        prepared: PreparedExport,
        targetUri: Uri?,
        isTree: Boolean,
        contentResolver: ContentResolver,
    ) {
        viewModelScope.launch(dispatchers.io) {
            if (targetUri == null) {
                runCatching { exportGateway.removeStagingFolder(prepared.folder) }
                emitSnackbar("已取消保存")
                return@launch
            }
            _uiState.update { it.copy(isExporting = true, exportStatusText = "正在写入目标位置…") }
            try {
                val outcome = exportGateway.saveToSaf(
                    prepared = prepared,
                    targetUri = targetUri,
                    isTree = isTree,
                    contentResolver = contentResolver,
                    onProgress = { text -> _uiState.update { it.copy(exportStatusText = text) } },
                )
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                emitSnackbar("已成功保存 ${outcome.savedCount} 个文件")
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                throw ce
            } catch (error: Throwable) {
                _uiState.update { it.copy(isExporting = false, exportStatusText = null) }
                emitSnackbar(error.message?.takeIf(String::isNotBlank) ?: "保存文件失败")
            }
        }
    }

    // ---- Thumbnail Helper (Local / Offline Sheet Previews) ----

    suspend fun loadThumbnailBitmap(pageNumber: Int, targetSizePx: Int = 360): Bitmap? {
        val state = _uiState.value
        val detail = state.detail ?: return null
        return runCatching {
            pageLoader.loadThumbnailBitmap(
                detail = detail,
                pageIndex = pageNumber,
                targetSizePx = targetSizePx,
                preferOriginal = false,
                offlineOnly = offlineOnly,
                sourceRoot = state.sourceRoot,
            )
        }.getOrNull()
    }

    // ---- Sequential Queue Advancement ----

    private fun maybeTriggerQueueCompletion(currentPage: Int) {
        val state = _uiState.value
        val lastPage = state.pageNumbers.lastOrNull() ?: return
        val onFinalSheet = lastPage in state.currentSheetPages || currentPage >= lastPage
        if (!onFinalSheet) return

        val session = state.queueSession
        if (session != null) {
            viewModelScope.launch(dispatchers.io) {
                runCatching {
                    shelfRepository.setItemCompleted(
                        shelfId = session.shelfId,
                        siteId = siteId,
                        mediaId = mediaId,
                        completed = true,
                    )
                }
            }
            if (session.hasNext && !hasPromptedQueueForCurrentBook) {
                hasPromptedQueueForCurrentBook = true
                _uiState.update { it.copy(showQueueNextCard = true) }
            }
        }
    }

    fun dismissQueueNextCard() {
        _uiState.update { it.copy(showQueueNextCard = false) }
    }

    fun endQueueSession() {
        val token = _uiState.value.queueSession?.token ?: route.queueToken
        if (!token.isNullOrBlank()) {
            playbackQueueStore.end(token)
        }
        _uiState.update { it.copy(showQueueNextCard = false, queueSession = null) }
    }

    fun advanceQueue(): Boolean {
        val current = _uiState.value.queueSession ?: return false
        val advanced = playbackQueueStore.advance(current.token, siteId, mediaId) ?: return false
        val nextItem = advanced.currentItem ?: run {
            playbackQueueStore.restore(current, advanced)
            return false
        }
        flushProgress()
        _uiState.update { it.copy(showQueueNextCard = false, queueSession = advanced) }
        _events.tryEmit(
            ReaderEvent.OpenNextQueueItem(
                previous = current,
                replacement = advanced,
                nextItem = nextItem,
            ),
        )
        return true
    }

    fun rollbackQueueAdvance(previous: PlaybackQueueSession, replacement: PlaybackQueueSession) {
        playbackQueueStore.restore(previous, replacement)
        _uiState.update { it.copy(queueSession = previous, showQueueNextCard = true) }
    }

    // ---- 750ms Debounced Progress Persistence + Immediate Flush on Dispose ----

    private fun scheduleProgressSave(page: Int, scrollOffsetPx: Int) {
        if (_uiState.value.detail == null) return
        hasDirtyProgress = true
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch(dispatchers.io) {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            persistProgressInternal(page = page, scrollOffsetPx = scrollOffsetPx)
        }
    }

    /**
     * Cancels any pending 750ms debounce timer and flushes the latest reading progress immediately.
     * Safe to invoke from `DisposableEffect.onDispose`, `ON_STOP`, and `onCleared`.
     */
    fun flushProgress() {
        progressSaveJob?.cancel()
        progressSaveJob = null
        appendedProgressSaveJob?.cancel()
        appendedProgressSaveJob = null
        val state = _uiState.value
        val detail = state.detail ?: return
        val activeChapterId = state.activeStripChapterId
        val scope = persistenceScope ?: viewModelScope
        val pendingChapter = pendingAppendedProgress
        if (pendingChapter != null) {
            if (activeChapterId == pendingChapter.mediaId) {
                // The appended chapter still owns the viewport: flush it under NonCancellable so a
                // fast exit cannot lose its position while the delayed job is cancelled.
                scope.launch(dispatchers.io + NonCancellable) {
                    flushPendingAppendedProgress()
                    if (pendingAppendedProgress?.mediaId == pendingChapter.mediaId) {
                        pendingAppendedProgress = null
                    }
                }
            } else {
                // Reverse scrolling left a stale appended anchor; never write it over the primary row.
                pendingAppendedProgress = null
            }
        }
        // While an appended chapter is active it owns the latest history row; writing the primary
        // row here would promote a chapter the reader has already scrolled past.
        if (activeChapterId != null) return
        if (!hasDirtyProgress && lastSavedPage == state.currentPage && lastSavedOffsetPx == state.stripScrollOffsetPx) {
            return
        }
        val page = state.currentPage
        val offset = state.stripScrollOffsetPx
        val total = state.totalPages.coerceAtLeast(1)
        val item = detail.item
        val series = activeSeriesMetadata ?: resolveSeriesMetadata(detail)
        scope.launch(dispatchers.io + NonCancellable) {
            persistProgressEntity(
                siteId = siteId,
                mediaId = mediaId,
                url = item.url.ifBlank { url },
                title = item.title,
                coverUrl = item.coverUrl,
                kind = item.kind,
                currentPage = page,
                totalPages = total,
                scrollOffsetPx = offset,
                series = series,
            )
        }
    }

    /** Suspend helper for immediate deterministic flushing (e.g., tests or pre-navigation). */
    suspend fun flushProgressNow() {
        progressSaveJob?.cancel()
        progressSaveJob = null
        appendedProgressSaveJob?.cancel()
        appendedProgressSaveJob = null
        val state = _uiState.value
        if (state.detail == null) return
        val activeChapterId = state.activeStripChapterId
        withContext(dispatchers.io + NonCancellable) {
            val pendingChapter = pendingAppendedProgress
            if (pendingChapter != null && activeChapterId == pendingChapter.mediaId) {
                flushPendingAppendedProgress()
            }
            pendingAppendedProgress = null
            if (activeChapterId == null) {
                persistProgressInternal(
                    page = state.currentPage,
                    scrollOffsetPx = state.stripScrollOffsetPx,
                )
            }
        }
    }

    private suspend fun persistProgressInternal(page: Int, scrollOffsetPx: Int) {
        val state = _uiState.value
        val detail = state.detail ?: return
        val item = detail.item
        val total = state.totalPages.coerceAtLeast(1)
        val series = activeSeriesMetadata ?: resolveSeriesMetadata(detail)
        persistProgressEntity(
            siteId = siteId,
            mediaId = mediaId,
            url = item.url.ifBlank { url },
            title = item.title,
            coverUrl = item.coverUrl,
            kind = item.kind,
            currentPage = page,
            totalPages = total,
            scrollOffsetPx = scrollOffsetPx,
            series = series,
        )
    }

    private suspend fun persistProgressEntity(
        siteId: SiteId,
        mediaId: String,
        url: String,
        title: String,
        coverUrl: String,
        kind: com.raven.app.core.model.MediaKind,
        currentPage: Int,
        totalPages: Int,
        scrollOffsetPx: Int,
        series: RavenRoute.ReaderParent?,
    ) {
        if (isInvalidSiteId || latestIncognitoMode) {
            hasDirtyProgress = false
            return
        }
        val clampedPage = currentPage.coerceIn(1, totalPages.coerceAtLeast(1))
        val clampedOffset = scrollOffsetPx.coerceAtLeast(0)
        runCatching {
            historyRepository.saveReadingProgress(
                ReadingProgress(
                    siteId = siteId,
                    mediaId = mediaId,
                    url = url,
                    title = title,
                    coverUrl = coverUrl,
                    kind = kind,
                    currentPage = clampedPage,
                    totalPages = totalPages,
                    scrollOffsetPx = clampedOffset,
                    updatedAt = clock(),
                    seriesId = series?.seriesId,
                    seriesTitle = series?.title?.takeIf(String::isNotBlank),
                    seriesUrl = series?.url?.takeIf(String::isNotBlank),
                ),
            )
            hasDirtyProgress = false
            lastSavedPage = clampedPage
            lastSavedOffsetPx = clampedOffset
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun emitSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    override fun onCleared() {
        autoPageJob?.cancel()
        chapterListJob?.cancel()
        chapterAdvanceJob?.cancel()
        appendNextChapterJob?.cancel()
        appendedProgressSaveJob?.cancel()
        bufferCollectJob?.cancel()
        appendedChapterCollectJobs.values.forEach { it.cancel() }
        appendedChapterCollectJobs.clear()
        flushProgress()
        readerBuffer?.close()
        readerBuffer = null
        appendedChapterBuffers.values.forEach { it.close() }
        appendedChapterBuffers.clear()
        super.onCleared()
    }

    private fun clearPendingAppendedProgress() {
        pendingAppendedProgress = null
        appendedProgressSaveJob?.cancel()
        appendedProgressSaveJob = null
    }

    /** Persist the primary chapter anchor before an appended chapter becomes the viewport owner. */
    private fun persistPrimaryAnchorBeforeAppended(state: ReaderUiState) {
        if (!hasDirtyProgress) return
        progressSaveJob?.cancel()
        progressSaveJob = null
        val page = state.currentPage
        val offset = state.stripScrollOffsetPx
        viewModelScope.launch(dispatchers.io + NonCancellable) {
            persistProgressInternal(page = page, scrollOffsetPx = offset)
        }
    }

    /**
     * Series identity shared by every chapter row of the same work. An explicit route parent wins,
     * so opaque chapter IDs (e.g. JMComic photo routes) keep their work identity across transitions.
     */
    private fun resolveSeriesMetadata(detail: GalleryDetail): RavenRoute.ReaderParent? =
        resolveReaderParent(detail, route.parent)

    private fun nearestPageIn(pages: List<Int>, target: Int): Int {
        if (pages.isEmpty()) return 1
        val idx = pages.binarySearch(target)
        if (idx >= 0) return pages[idx]
        val insert = (-idx - 1).coerceIn(0, pages.lastIndex)
        return pages[insert]
    }

    private data class ReaderMetadataSnapshot(
        val effective: ReaderPreferences,
        val isPerBookOverride: Boolean,
    )

    companion object {
        const val PROGRESS_SAVE_DEBOUNCE_MS: Long = 750L
        val ALLOWED_AUTO_PAGE_INTERVALS: List<Int> = listOf(0, 3, 5, 10, 20, 30)
    }
}
