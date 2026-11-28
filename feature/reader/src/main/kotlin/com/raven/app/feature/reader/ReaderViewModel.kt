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

    /**
     * Reading-progress pipeline (750ms debounce, appended-chapter anchor, immediate flush).
     * Series identity resolves through the same `activeSeriesMetadata` cache the chapter
     * navigator uses, so opaque chapter ids keep their work identity.
     */
    /** Export / SAF share flows (see [ReaderExportCoordinator]). */
    private val exporter = ReaderExportCoordinator(
        scope = viewModelScope,
        dispatchers = dispatchers,
        gateway = exportGateway,
        state = uiState,
        update = { transform -> _uiState.update(transform) },
        emitEvent = { event -> _events.tryEmit(event) },
        notice = ::emitSnackbar,
        mediaId = mediaId,
        supportsOriginalImages = supportsOriginalImages,
        pageLoader = pageLoader,
        offlineOnly = offlineOnly,
    )

    /** Primary page buffer (see [ReaderPageBufferController]). */
    private val pageBuffer = ReaderPageBufferController(
        scope = viewModelScope,
        dispatchers = dispatchers,
        pageLoader = pageLoader,
        state = uiState,
        update = { transform -> _uiState.update(transform) },
        offlineOnly = offlineOnly,
        sharedRatios = { sharedAspectRatios },
    )

    /** Auto-page / slideshow loop (see [ReaderAutoPageLoop]). */
    private val autoPageLoop = ReaderAutoPageLoop(
        scope = viewModelScope,
        dispatchers = dispatchers,
        state = uiState,
        update = { transform -> _uiState.update(transform) },
        random = random,
        isUserInteracting = { isUserZoomedOrDragging },
        jumpToPage = ::jumpToPage,
        preloadNextChapter = { chapterPrefetcher.preloadNextSerialChapterIfNeeded() },
        requestNextChapterAtBoundary = ::requestNextChapterAtBoundary,
        notice = ::emitSnackbar,
    )

    /** Appended serial-chapter buffers for the continuous STRIP stream. */
    private val appendedChapters = ReaderAppendedChapterCache(
        scope = viewModelScope,
        dispatchers = dispatchers,
        pageLoader = pageLoader,
        uiState = { _uiState.value },
        update = { transform -> _uiState.update(transform) },
        memoryBudgetBytes = { pageBuffer.budgetBytes },
        maxConcurrency = { pageBuffer.concurrency },
        prefetchAhead = { pageBuffer.prefetchAhead },
    )

    /** Serial chapter list + next-chapter prefetch (see [ReaderChapterPrefetcher]). */
    private val chapterPrefetcher = ReaderChapterPrefetcher(
        scope = viewModelScope,
        dispatchers = dispatchers,
        siteId = siteId,
        mediaId = mediaId,
        offlineOnly = offlineOnly,
        isInvalidSiteId = isInvalidSiteId,
        uiState = { _uiState.value },
        update = { transform -> _uiState.update(transform) },
        serialChapterLoader = serialChapterLoader,
        pageLoader = pageLoader,
        chapters = appendedChapters,
        supportsOriginalImages = { supportsOriginalImages },
    )

    private val progressStore = ReaderProgressStore(
        siteId = siteId,
        mediaId = mediaId,
        routeUrl = url,
        isInvalidSiteId = isInvalidSiteId,
        historyRepository = historyRepository,
        dispatchers = dispatchers,
        viewModelScope = viewModelScope,
        persistenceScope = persistenceScope,
        clock = clock,
        state = { _uiState.value },
        incognito = { latestIncognitoMode },
        seriesFor = { detail ->
            activeSeriesMetadata ?: resolveSeriesMetadata(detail).also { activeSeriesMetadata = it }
        },
    )

    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private var chapterAdvanceJob: Job? = null
    @Volatile
    private var activeSeriesMetadata: RavenRoute.ReaderParent? = null



    private var chapterTransitionEmitted: Boolean = false

    private var latestIncognitoMode: Boolean = false
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
                    pageBuffer.current() != null &&
                    (pageBuffer.prefetchAhead != targetPrefetch ||
                        pageBuffer.budgetBytes != targetBudgetBytes ||
                        pageBuffer.concurrency != targetConcurrency)
                ) {
                    rebuildBuffer(
                        detail = detail,
                        pageNumbers = _uiState.value.pageNumbers,
                        sourceRoot = _uiState.value.sourceRoot,
                        preferOriginal = pageBuffer.preferOriginal,
                        prefetchAhead = targetPrefetch,
                        offscreenBudgetBytes = targetBudgetBytes,
                        maxConcurrency = targetConcurrency,
                    )
                } else {
                    pageBuffer.prefetchAhead = targetPrefetch
                    pageBuffer.budgetBytes = targetBudgetBytes
                    pageBuffer.concurrency = targetConcurrency
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
                if (detail != null && pageBuffer.current() != null && pageBuffer.preferOriginal != nextPreferOriginal) {
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
        chapterPrefetcher.reset()
        chapterAdvanceJob?.cancel()
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
                    pageBuffer.prefetchAhead = currentSettings.readerPrefetchPages.coerceIn(1, 12)
                    pageBuffer.budgetBytes = currentSettings.readerMemoryBudgetMb.coerceIn(16, 512).toLong() * 1024L * 1024L
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
                progressStore.schedule(page = initialPage, scrollOffsetPx = initialOffset)
                chapterPrefetcher.prepare(detail)
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

    private fun attachAppendedChapterBuffer(
        chapter: SerialStripChapter,
        preferOriginal: Boolean,
    ) = appendedChapters.attach(chapter = chapter, preferOriginal = preferOriginal)

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
                val existingBuffer = appendedChapters.bufferFor(chapId)
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
            chapterPrefetcher.preloadNextSerialChapterIfNeeded()
        }
    }

    fun retryAppendedChapterPage(chapterMediaId: String, pageNumber: Int) {
        if (chapterMediaId == mediaId) {
            retryPage(pageNumber)
            return
        }
        if (appendedChapters.retry(chapterMediaId, pageNumber)) return
        val chapter = _uiState.value.appendedChapters.firstOrNull { it.mediaId == chapterMediaId } ?: return
        attachAppendedChapterBuffer(
            chapter = chapter,
            preferOriginal = supportsOriginalImages && _uiState.value.preferences.preferOriginalImages,
        )
    }

    private fun scheduleAppendedChapterProgressSave(
        chapter: SerialStripChapter,
        page: Int,
        scrollOffsetPx: Int,
    ) = progressStore.scheduleAppended(
        chapter = chapter,
        page = page,
        scrollOffsetPx = scrollOffsetPx,
    )

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
        val state = _uiState.value
        // In continuous STRIP mode the viewport may already be inside an appended chapter, so the
        // current-chapter identity must come from that chapter rather than the primary detail.
        val activeChapter = if (state.preferences.mode == ReaderMode.STRIP) {
            state.appendedChapters.firstOrNull { it.mediaId == state.displayMediaId }
        } else {
            null
        }
        val hints = if (activeChapter != null) {
            listOfNotNull(
                activeChapter.detail.item.id,
                activeChapter.detail.item.extra["chapterId"],
                activeChapter.detail.item.extra["chapterUuid"],
                activeChapter.detail.item.extra["chapterHid"],
            )
        } else {
            listOfNotNull(
                detail.item.id,
                detail.item.extra["chapterId"],
                detail.item.extra["chapterUuid"],
                detail.item.extra["chapterHid"],
            )
        }
        val isCurrent = isMatchingSerialChapter(
            chapter = chapter,
            currentChapterId = activeChapter?.mediaId ?: state.displayMediaId,
            currentChapterUrl = activeChapter?.url ?: detail.item.extra["chapterUrl"].orEmpty(),
            hints = hints,
        )
        dismissSheet()
        if (!isCurrent) requestChapter(direction = null, requestedChapter = chapter)
    }

    /** Kept for the reader UI retry affordance. */
    fun preloadNextSerialChapterIfNeeded(forceRetry: Boolean = false) =
        chapterPrefetcher.preloadNextSerialChapterIfNeeded(forceRetry)

    fun refreshChapters() {
        val detail = _uiState.value.detail ?: return
        if (offlineOnly || detail.chaptersUrl.isNullOrBlank() || isInvalidSiteId) return
        chapterPrefetcher.refresh(detail)
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
                chapterPrefetcher.awaitList()
                chapterPrefetcher.ensurePrepared(detail)
                val target = requestedChapter ?: chapterPrefetcher.prepared(direction)
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

    private fun rebuildBuffer(
        detail: GalleryDetail,
        pageNumbers: List<Int>,
        sourceRoot: String,
        preferOriginal: Boolean,
        prefetchAhead: Int = pageBuffer.prefetchAhead,
        offscreenBudgetBytes: Long = pageBuffer.budgetBytes,
        maxConcurrency: Int = pageBuffer.concurrency,
    ) = pageBuffer.rebuild(
        detail = detail,
        pageNumbers = pageNumbers,
        sourceRoot = sourceRoot,
        preferOriginal = preferOriginal,
        prefetchAhead = prefetchAhead,
        offscreenBudgetBytes = offscreenBudgetBytes,
        maxConcurrency = maxConcurrency,
    )

    // ---- Viewport, Page & Buffer Updates ----

    fun onViewportOrientationChanged(isLandscape: Boolean) {
        if (_uiState.value.isLandscapeViewport == isLandscape) return
        _uiState.update { it.copy(isLandscapeViewport = isLandscape) }
        val state = _uiState.value
        if (state.pageNumbers.isNotEmpty()) {
            pageBuffer.current()?.updateWindow(
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
            progressStore.schedule(page = safeAnchor, scrollOffsetPx = safeOffset)
            maybeTriggerQueueCompletion(safeAnchor)
        }
        pageBuffer.current()?.updateWindow(page = safeAnchor, visible = safeVisible)
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
            val buffer = appendedChapters.bufferFor(activeChapter.mediaId)
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
        pageBuffer.current()?.updateWindow(page = target, visible = visible)
        _events.tryEmit(ReaderEvent.ScrollToPage(page = target, scrollOffsetPx = safeOffset, chapterId = mediaId))
        progressStore.schedule(page = target, scrollOffsetPx = safeOffset)
        maybeTriggerQueueCompletion(target)
    }

    fun retryPage(pageNumber: Int) {
        pageBuffer.current()?.retry(pageNumber)
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
            chapterPrefetcher.preloadNextSerialChapterIfNeeded(forceRetry = true)
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
            pageBuffer.current()?.updateWindow(page = preservedPage, visible = visible)
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
            pageBuffer.current()?.updateWindow(
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

    // ---- Auto-Page & Slideshow（实现见 ReaderAutoPageLoop）----

    fun setAutoPageInterval(seconds: Int) = autoPageLoop.setInterval(seconds)

    fun setAutoPagePausedByLifecycle(paused: Boolean) = autoPageLoop.setPausedByLifecycle(paused)

    fun setSlideshowOrder(order: SlideshowOrder) {
        _uiState.update { it.copy(slideshowOrder = order) }
    }

    // ---- Bookmarks & Single-Image Favorites ----

    /**
     * Resolves the chapter that currently owns the viewport. In continuous STRIP mode the user can
     * scroll into an appended serial chapter; every page-scoped action (bookmark, image favorite,
     * share/export) must target *that* chapter's mediaId and pages, not the initially opened one.
     */
    fun toggleCurrentPageBookmark(note: String = "") {
        val state = _uiState.value
        val detail = state.detail ?: return
        val ctx = resolveActivePageContext(state, detail, mediaId)
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
        val ctx = resolveActivePageContext(state, detail, mediaId)
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

    fun exportCurrentPage(mode: ExportMode) = exporter.exportCurrentPage(mode)

    fun exportCbz(rawRangeInput: String? = null) = exporter.exportCbz(rawRangeInput)

    fun exportPages(
        pages: List<Int>,
        mode: ExportMode,
        followActiveChapter: Boolean = false,
    ) = exporter.exportPages(pages, mode, followActiveChapter)

    fun prepareShareUris(
        context: android.content.Context,
        authority: String,
        prepared: PreparedExport,
    ): List<android.net.Uri> = exporter.prepareShareUris(context, authority, prepared)

    fun completeSafExport(
        prepared: PreparedExport,
        targetUri: android.net.Uri?,
        isTree: Boolean,
        contentResolver: android.content.ContentResolver,
    ) = exporter.completeSafExport(prepared, targetUri, isTree, contentResolver)

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

        val session = state.queueSession ?: return
        // A partial offline download only exposes the pages that exist on disk. Reaching the last
        // of those must never mark the whole work completed in the library.
        val hasFullBookRange = state.totalPages > 0 &&
            state.pageNumbers.size >= state.totalPages &&
            state.pageNumbers.firstOrNull() == 1 &&
            state.pageNumbers.lastOrNull() == state.totalPages
        if (hasFullBookRange) {
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
        }
        if (session.hasNext && !hasPromptedQueueForCurrentBook) {
            hasPromptedQueueForCurrentBook = true
            _uiState.update { it.copy(showQueueNextCard = true) }
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

    // ---- 进度持久化（750ms 防抖 + 立即冲刷；实现见 ReaderProgressStore）----

    /** Cancels any pending debounce timer and flushes the latest reading progress immediately. */
    fun flushProgress() = progressStore.flush()

    /** Suspend helper for immediate deterministic flushing (e.g., tests or pre-navigation). */
    suspend fun flushProgressNow() = progressStore.flushNow()


    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun emitSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    override fun onCleared() {
        autoPageLoop.cancel()
        chapterPrefetcher.cancelAll()
        chapterAdvanceJob?.cancel()
        progressStore.cancel()
        pageBuffer.close()
        flushProgress()
        appendedChapters.closeAll()
        super.onCleared()
    }

    private fun clearPendingAppendedProgress() = progressStore.clearPending()


    /** Persist the primary chapter anchor before an appended chapter becomes the viewport owner. */
    private fun persistPrimaryAnchorBeforeAppended(state: ReaderUiState) =
        progressStore.persistPrimaryAnchorBeforeAppended(state)


    /**
     * Series identity shared by every chapter row of the same work. An explicit route parent wins,
     * so opaque chapter IDs (e.g. JMComic photo routes) keep their work identity across transitions.
     */
    private fun resolveSeriesMetadata(detail: GalleryDetail): RavenRoute.ReaderParent? =
        resolveReaderParent(detail, route.parent)

    private data class ReaderMetadataSnapshot(
        val effective: ReaderPreferences,
        val isPerBookOverride: Boolean,
    )

    companion object {
        const val PROGRESS_SAVE_DEBOUNCE_MS: Long = 750L
        val ALLOWED_AUTO_PAGE_INTERVALS: List<Int> = listOf(0, 3, 5, 10, 20, 30)
    }
}
