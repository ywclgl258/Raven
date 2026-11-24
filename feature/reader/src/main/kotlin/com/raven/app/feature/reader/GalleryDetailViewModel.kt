package com.raven.app.feature.reader

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.PageSelectionParser
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.reader.ExportMode
import com.raven.app.core.media.reader.GalleryExporter
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.reader.PreparedExport
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReadingProgress
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
    val isFavorite: Boolean = false,
    val isInWatchLater: Boolean = false,
    val pageBookmarks: List<PageBookmark> = emptyList(),
    val activeDownload: DownloadItem? = null,
    val translateTags: Boolean = true,
    val favoritedTagSlugs: Set<String> = emptySet(),
    val subscribedTagSlugs: Set<String> = emptySet(),
    val blockedTerms: Set<String> = emptySet(),
    val downloadSheet: DownloadSheetState = DownloadSheetState(),
    val tagAction: TagActionState? = null,
    val isExporting: Boolean = false,
    val exportStatusText: String? = null,
    val authRequired: Boolean = false,
    val authMessage: String? = null,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
) {
    val hasResumeProgress: Boolean
        get() {
            val progress = readingProgress ?: return false
            val total = detail?.pageCount ?: progress.totalPages
            return progress.currentPage > 0 && (total <= 0 || progress.currentPage <= total)
        }

    val resumeStartPage: Int
        get() {
            val progress = readingProgress ?: return 1
            val total = (detail?.pageCount ?: progress.totalPages).coerceAtLeast(1)
            return if (progress.completed) 1 else progress.currentPage.coerceIn(1, total)
        }

    val primaryCtaLabel: String
        get() {
            val progress = readingProgress
            return if (progress != null && progress.currentPage > 1 && !progress.completed) {
                "继续阅读 (第 ${progress.currentPage} 页)"
            } else if (progress != null && progress.completed) {
                "重新阅读"
            } else {
                "开始阅读"
            }
        }

    val canOpenOffline: Boolean
        get() = isLocal || isOfflineComplete || downloadedPages.isNotEmpty() ||
            activeDownload?.status == DownloadStatus.COMPLETED
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
    )

    private val siteId: SiteId = SiteId.of(route.siteId)
    private val mediaId: String = route.mediaId
    private val url: String = route.url

    private val plugin = siteRegistry.get(siteId)
    private val descriptor: SiteDescriptor? = plugin?.descriptor

    private val _uiState = MutableStateFlow(
        GalleryDetailUiState(
            siteId = siteId,
            mediaId = mediaId,
            url = url,
            siteDescriptor = descriptor,
            siteDisplayName = when {
                siteId.isLocal -> "本地导入"
                descriptor != null -> descriptor.displayName
                else -> siteId.value
            },
            isLocal = siteId.isLocal,
        ),
    )
    val uiState: StateFlow<GalleryDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GalleryDetailEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<GalleryDetailEvent> = _events.asSharedFlow()

    private var loadJob: Job? = null
    private var exportJob: Job? = null

    init {
        observeLocalPersonalState()
        loadDetail()
    }

    private fun observeLocalPersonalState() {
        // Combine reading progress, bookmarks, watch-later membership, and downloads.
        viewModelScope.launch(dispatchers.main) {
            combine(
                historyRepository.observeReadingProgress(siteId, mediaId),
                bookmarkRepository.observePageBookmarks(siteId, mediaId),
                shelfRepository.observeContainingShelfIds(siteId, mediaId),
                downloadGateway.observeByMedia(siteId, mediaId),
                preferencesRepository.settings,
            ) { progress, bookmarks, shelfIds, downloads, settings ->
                PersonalBaseSnapshot(
                    progress = progress,
                    bookmarks = bookmarks,
                    isInWatchLater = Shelf.WATCH_LATER_ID in shelfIds,
                    activeDownload = downloads.maxByOrNull { it.updatedAt },
                    translateTags = settings.translateTags,
                )
            }.collectLatest { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        readingProgress = snapshot.progress,
                        pageBookmarks = snapshot.bookmarks,
                        isInWatchLater = snapshot.isInWatchLater,
                        activeDownload = snapshot.activeDownload,
                        translateTags = snapshot.translateTags,
                    )
                }
            }
        }

        // Combine site favorites, tag subscriptions, and blocked terms.
        viewModelScope.launch(dispatchers.main) {
            combine(
                favoriteRepository.observeBySite(siteId),
                subscriptionRepository.observeSubscriptions(siteId),
                blockedTermsRepository.observeTermsBySite(siteId),
            ) { favorites, subscriptions, blocked ->
                SitePersonalSnapshot(
                    favorites = favorites,
                    subscribedSlugs = subscriptions.mapTo(linkedSetOf()) { it.slug.lowercase() },
                    blockedTerms = blocked.mapTo(linkedSetOf()) { ContentFilter.normalizeTerm(it) },
                )
            }.collectLatest { snapshot ->
                _uiState.update { state ->
                    val detailKind = state.detail?.item?.kind
                    val isWorkFav = snapshot.favorites.any { fav ->
                        fav.targetId == mediaId && (
                            (detailKind == MediaKind.PHOTO && fav.kind == FavoriteKind.PHOTO) ||
                                (detailKind != MediaKind.PHOTO && fav.kind == FavoriteKind.COMIC) ||
                                (detailKind == null && (fav.kind == FavoriteKind.COMIC || fav.kind == FavoriteKind.PHOTO))
                            )
                    }
                    val favTagSlugs = snapshot.favorites
                        .filter { it.kind == FavoriteKind.TAG }
                        .mapTo(linkedSetOf()) { it.targetId.lowercase() }
                    val updatedTagAction = state.tagAction?.let { action ->
                        val normSlug = action.tag.slug.lowercase()
                        val normTerm = ContentFilter.normalizeTerm(action.tag.slug)
                        action.copy(
                            isFavorited = normSlug in favTagSlugs,
                            isSubscribed = normSlug in snapshot.subscribedSlugs,
                            isBlocked = normTerm in snapshot.blockedTerms,
                        )
                    }
                    state.copy(
                        isFavorite = isWorkFav,
                        favoritedTagSlugs = favTagSlugs,
                        subscribedTagSlugs = snapshot.subscribedSlugs,
                        blockedTerms = snapshot.blockedTerms,
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

            // Pre-check auth gate on auth-gated sites (e.g., ExHentai) before firing network load.
            val currentPlugin = plugin
            if (!siteId.isLocal && currentPlugin != null && currentPlugin.authProvider.requiresAuthGate) {
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
                    offlineOnly = false,
                )
                val favoriteKind = favoriteKindFor(snapshot.detail.item.kind)
                val isFavNow = runCatching {
                    favoriteRepository.isFavorite(siteId, favoriteKind, mediaId)
                }.getOrDefault(_uiState.value.isFavorite)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        detail = snapshot.detail,
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

    fun formatTagLabel(tag: TagRef): String {
        val raw = tag.label.ifBlank { tag.slug }
        if (!_uiState.value.translateTags) return raw
        val translated = tagDictionary.translate(tag.slug) ?: tagDictionary.translate(raw)
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

    // ---- Tag Long-Press Action Sheet ----

    fun openTagActions(tag: TagRef) {
        val state = _uiState.value
        val normSlug = tag.slug.lowercase()
        val normTerm = ContentFilter.normalizeTerm(tag.slug)
        _uiState.update {
            it.copy(
                tagAction = TagActionState(
                    tag = tag,
                    translatedLabel = formatTagLabel(tag),
                    isFavorited = normSlug in state.favoritedTagSlugs,
                    isSubscribed = normSlug in state.subscribedTagSlugs,
                    isBlocked = normTerm in state.blockedTerms,
                ),
            )
        }
    }

    fun dismissTagActions() {
        _uiState.update { it.copy(tagAction = null) }
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
            val subscribed = subscriptionRepository.toggleSubscription(
                TagSubscription(
                    siteId = siteId,
                    slug = tag.slug,
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
            val blocked = blockedTermsRepository.toggleTerm(siteId, tag.slug)
            emitSnackbar(if (blocked) "已在当前站点屏蔽「$label」" else "已取消屏蔽「$label」")
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

    private fun favoriteKindFor(kind: MediaKind): FavoriteKind = when (kind) {
        MediaKind.PHOTO -> FavoriteKind.PHOTO
        MediaKind.VIDEO -> FavoriteKind.VIDEO
        MediaKind.COMIC -> FavoriteKind.COMIC
    }

    private data class PersonalBaseSnapshot(
        val progress: ReadingProgress?,
        val bookmarks: List<PageBookmark>,
        val isInWatchLater: Boolean,
        val activeDownload: DownloadItem?,
        val translateTags: Boolean,
    )

    private data class SitePersonalSnapshot(
        val favorites: List<FavoriteItem>,
        val subscribedSlugs: Set<String>,
        val blockedTerms: Set<String>,
    )
}
