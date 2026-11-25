package com.raven.app.feature.reader

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFrostedBox
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenFrostedSurface
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.reader.PanOffset
import com.raven.app.core.media.reader.ReaderGeometry
import com.raven.app.core.media.reader.SpreadNavigator
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen immersive 4-mode Raven Reader (`STRIP`, `LTR`, `RTL`, `SPREAD`) featuring:
 * - Single-tap frosted chrome & system-bar immersion
 * - Double-tap `1x/2x` zoom, pinch `1f..5f`, strictly clamped pan via [ReaderGeometry]
 * - Per-page aspect ratio preservation across mode switches & orientation changes
 * - Hardware volume-key paging (normal/reversed + long-press first/last jump)
 * - Corner [ReaderStatusBar], bottom scrubber w/ floating target page pill & 5 quick buttons
 * - 85%-height Thumbnail Overview sheet, Bookmarks sheet, Single-screen Grouped Settings sheet
 * - 750ms debounced reading progress save + dispose/stop flush & Sequential Queue continuation card.
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    navigator: ReaderNavigator,
    modifier: Modifier = Modifier,
    registerVolumeKeyHandler: (((up: Boolean, isLongPress: Boolean) -> Boolean)?) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val currentRegisterVolume by rememberUpdatedState(registerVolumeKeyHandler)

    // Sync immersive system bars, window brightness, and keepScreenOn with Activity host.
    LaunchedEffect(uiState.chromeVisible, navigator) {
        navigator.setSystemBarsVisible(uiState.chromeVisible)
    }
    LaunchedEffect(uiState.effectiveBrightnessFloat, navigator) {
        navigator.setReaderBrightness(uiState.effectiveBrightnessFloat)
    }
    LaunchedEffect(uiState.preferences.keepScreenOn, navigator) {
        navigator.setKeepScreenOn(uiState.preferences.keepScreenOn)
    }

    // Request focus so Compose preview key events capture hardware volume keys when active.
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // Register optional Activity-level volume key hook and restore window attributes + flush progress on exit.
    DisposableEffect(viewModel, navigator) {
        currentRegisterVolume { up, isLongPress ->
            viewModel.handleVolumeKey(up = up, isLongPress = isLongPress)
        }
        onDispose {
            currentRegisterVolume(null)
            viewModel.flushProgress()
            navigator.setSystemBarsVisible(true)
            navigator.setReaderBrightness(-1f)
            navigator.setKeepScreenOn(false)
        }
    }

    // Also pause active auto-page loop and flush reading progress when the screen stops; resume on start.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.setAutoPagePausedByLifecycle(true)
        viewModel.flushProgress()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.setAutoPagePausedByLifecycle(false)
    }

    // Snackbar messages
    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // Export, Share, and Sequential Queue side-effect events
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderEvent.ScrollToPage -> Unit // Handled by active mode viewport
                is ReaderEvent.RequestSaveDocument -> {
                    navigator.launchSaveDocument(
                        suggestedFileName = event.suggestedFileName,
                        mimeType = event.mimeType,
                    ) { uri ->
                        viewModel.completeSafExport(
                            prepared = event.prepared,
                            targetUri = uri,
                            isTree = false,
                            contentResolver = context.contentResolver,
                        )
                    }
                }
                is ReaderEvent.RequestOpenDocumentTree -> {
                    navigator.launchOpenDocumentTree { treeUri ->
                        viewModel.completeSafExport(
                            prepared = event.prepared,
                            targetUri = treeUri,
                            isTree = true,
                            contentResolver = context.contentResolver,
                        )
                    }
                }
                is ReaderEvent.RequestShareExport -> {
                    val authority = navigator.fileProviderAuthority ?: "${context.packageName}.fileprovider"
                    runCatching {
                        val uris = viewModel.prepareShareUris(
                            context = context,
                            authority = authority,
                            prepared = event.prepared,
                        )
                        val mime = event.prepared.files.firstOrNull()?.mimeType ?: "image/*"
                        navigator.shareFiles(
                            uris = uris,
                            mimeType = mime,
                            subject = event.subject,
                        )
                    }.onFailure {
                        runCatching { viewModel.completeSafExport(event.prepared, null, false, context.contentResolver) }
                    }
                }
                is ReaderEvent.OpenNextQueueItem -> {
                    runCatching {
                        navigator.openQueueItem(
                            item = event.nextItem,
                            queueToken = event.replacement.token,
                            offlineOnly = event.replacement.offlineOnly,
                        )
                    }.onFailure {
                        viewModel.rollbackQueueAdvance(event.previous, event.replacement)
                    }
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val isVolUp = keyEvent.key == Key.VolumeUp
                val isVolDown = keyEvent.key == Key.VolumeDown
                if (isVolUp || isVolDown) {
                    if (!uiState.preferences.volumeKeyPaging) return@onPreviewKeyEvent false
                    return@onPreviewKeyEvent if (keyEvent.type == KeyEventType.KeyDown) {
                        val native: AndroidKeyEvent = keyEvent.nativeKeyEvent
                        val isLongPress = native.isLongPress || native.repeatCount >= 4
                        // Avoid firing multiple rapid single-page steps while holding key until long-press fires.
                        if (native.repeatCount == 0 || native.repeatCount == 4 || native.isLongPress) {
                            viewModel.handleVolumeKey(up = isVolUp, isLongPress = isLongPress)
                        } else {
                            true
                        }
                    } else {
                        true
                    }
                }
                if (keyEvent.type != KeyEventType.KeyDown ||
                    uiState.activeSheet != ReaderSheet.NONE ||
                    uiState.detail == null
                ) {
                    return@onPreviewKeyEvent false
                }
                val isRtl = uiState.preferences.mode == ReaderMode.RTL
                when (keyEvent.key) {
                    Key.DirectionLeft -> viewModel.navigateAdjacentPage(forward = isRtl)
                    Key.DirectionRight -> viewModel.navigateAdjacentPage(forward = !isRtl)
                    Key.DirectionUp, Key.PageUp -> viewModel.navigateAdjacentPage(forward = false)
                    Key.DirectionDown, Key.PageDown, Key.Spacebar -> viewModel.navigateAdjacentPage(forward = true)
                    else -> false
                }
            }
            .testTag(ReaderTestTags.READER_SCREEN),
    ) {
        val isLandscape = maxWidth > maxHeight
        LaunchedEffect(isLandscape) {
            viewModel.onViewportOrientationChanged(isLandscape)
        }

        // Main Reader Content Layer
        when {
            uiState.isLoading && uiState.detail == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = colors.crimson, trackColor = colors.surface3)
                        Text(
                            text = "正在加载图集…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }
            uiState.authRequired -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ReaderAuthGateCard(
                        siteName = uiState.siteDisplayName,
                        message = uiState.authMessage ?: "阅读该站点图集需要先完成账号登录或导入 Cookie。",
                        onOpenAccount = { navigator.openSiteAccount(uiState.siteId) },
                        onRetry = { viewModel.loadGallery(forceRefresh = true) },
                    )
                }
            }
            uiState.detail == null || uiState.pageNumbers.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    RavenErrorState(
                        title = "无法打开阅读器",
                        message = uiState.errorMessage ?: "图集暂无可阅读页面，请检查网络或下载完整性。",
                        onRetry = { viewModel.loadGallery(forceRefresh = true) },
                        secondaryActionLabel = "返回",
                        onSecondaryActionClick = navigator::navigateBack,
                    )
                }
            }
            else -> {
                when (uiState.preferences.mode) {
                    ReaderMode.STRIP -> {
                        StripReaderViewport(
                            uiState = uiState,
                            events = viewModel.events,
                            onVisiblePagesChanged = viewModel::onVisiblePagesChanged,
                            onConsumePendingOffset = viewModel::consumePendingStripRestoreOffset,
                            onSingleTap = viewModel::toggleChrome,
                            onRetryPage = viewModel::retryPage,
                            onInteractionChanged = viewModel::onInteractionStateChanged,
                        )
                    }
                    ReaderMode.LTR -> {
                        PagedHorizontalViewport(
                            reverseLayout = false,
                            uiState = uiState,
                            events = viewModel.events,
                            onPageChanged = { page -> viewModel.onVisiblePagesChanged(page, setOf(page)) },
                            onSingleTap = viewModel::toggleChrome,
                            onRetryPage = viewModel::retryPage,
                            onZoomedChanged = viewModel::onInteractionStateChanged,
                            onPreviousPage = { viewModel.navigateAdjacentPage(forward = false) },
                            onNextPage = { viewModel.navigateAdjacentPage(forward = true) },
                        )
                    }
                    ReaderMode.RTL -> {
                        PagedHorizontalViewport(
                            reverseLayout = true,
                            uiState = uiState,
                            events = viewModel.events,
                            onPageChanged = { page -> viewModel.onVisiblePagesChanged(page, setOf(page)) },
                            onSingleTap = viewModel::toggleChrome,
                            onRetryPage = viewModel::retryPage,
                            onZoomedChanged = viewModel::onInteractionStateChanged,
                            onPreviousPage = { viewModel.navigateAdjacentPage(forward = false) },
                            onNextPage = { viewModel.navigateAdjacentPage(forward = true) },
                        )
                    }
                    ReaderMode.SPREAD -> {
                        val nav = uiState.spreadNavigator
                        if (nav != null) {
                            SpreadReaderViewport(
                                navigator = nav,
                                uiState = uiState,
                                events = viewModel.events,
                                onSheetChanged = { sheet ->
                                    if (sheet.isNotEmpty()) {
                                        viewModel.onVisiblePagesChanged(sheet.first(), sheet.toSet())
                                    }
                                },
                                onSingleTap = viewModel::toggleChrome,
                                onRetryPage = viewModel::retryPage,
                                onZoomedChanged = viewModel::onInteractionStateChanged,
                                onPreviousPage = { viewModel.navigateAdjacentPage(forward = false) },
                                onNextPage = { viewModel.navigateAdjacentPage(forward = true) },
                            )
                        }
                    }
                }
            }
        }

        // Corner Status Bar (visible when chrome hidden & configured enabled)
        if (!uiState.chromeVisible && uiState.showStatusBar && uiState.detail != null) {
            ReaderStatusBar(
                currentPage = uiState.currentPage,
                totalPages = uiState.totalPages,
                progressPercent = uiState.progressPercent,
                autoPageIntervalSeconds = uiState.autoPageIntervalSeconds,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        // Top Frosted Chrome Bar
        AnimatedVisibility(
            visible = uiState.chromeVisible,
            enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { -it },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .testTag(ReaderTestTags.READER_TOP_BAR),
        ) {
            val subtitle = buildList {
                add(uiState.siteDisplayName)
                if (uiState.offlineOnly) add("离线")
                if (uiState.totalPages > 0) {
                    add(RavenFormatters.formatPageProgress(uiState.currentPage, uiState.totalPages))
                }
            }.joinToString(" · ")

            RavenTopBar(
                title = uiState.detail?.item?.title ?: uiState.siteDisplayName,
                subtitle = subtitle,
                frosted = true,
                navigationIcon = {
                    IconButton(onClick = navigator::navigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = colors.inkPrimary,
                        )
                    }
                },
                actions = {
                    if (uiState.detail != null) {
                        IconButton(onClick = { viewModel.toggleCurrentPageBookmark() }) {
                            Icon(
                                imageVector = if (uiState.isCurrentPageBookmarked) {
                                    Icons.Filled.Bookmark
                                } else {
                                    Icons.Outlined.BookmarkBorder
                                },
                                contentDescription = if (uiState.isCurrentPageBookmarked) "取消书签" else "添加书签",
                                tint = if (uiState.isCurrentPageBookmarked) colors.crimson else colors.inkPrimary,
                            )
                        }
                        IconButton(onClick = viewModel::toggleCurrentPageImageFavorite) {
                            Icon(
                                imageVector = if (uiState.isCurrentPageImageFavorited) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = if (uiState.isCurrentPageImageFavorited) "取消收藏单图" else "收藏单图",
                                tint = if (uiState.isCurrentPageImageFavorited) colors.crimson else colors.inkPrimary,
                            )
                        }
                    }
                },
            )
        }

        // Sequential Queue Next Confirmation Card (when near/at final page and queue has next)
        val activeQueueSession = uiState.queueSession
        AnimatedVisibility(
            visible = uiState.showQueueNextCard && activeQueueSession?.hasNext == true && !uiState.chromeVisible,
            enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp),
        ) {
            if (activeQueueSession != null) {
                QueueNextConfirmationCard(
                    session = activeQueueSession,
                    onContinueNext = { viewModel.advanceQueue() },
                    onDismiss = viewModel::dismissQueueNextCard,
                    onEndQueue = viewModel::endQueueSession,
                )
            }
        }

        // Bottom Frosted Quick Icon Bar + Page Scrubber + Optional Inline Panels
        AnimatedVisibility(
            visible = uiState.chromeVisible && uiState.detail != null && uiState.pageNumbers.isNotEmpty(),
            enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { it },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .testTag(ReaderTestTags.READER_BOTTOM_BAR),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Queue next card also accessible above bottom bar when chrome is shown
                if (uiState.showQueueNextCard && activeQueueSession?.hasNext == true) {
                    QueueNextConfirmationCard(
                        session = activeQueueSession,
                        onContinueNext = { viewModel.advanceQueue() },
                        onDismiss = viewModel::dismissQueueNextCard,
                        onEndQueue = viewModel::endQueueSession,
                    )
                }

                // Inline Quick Panels (Mode / Brightness + Auto-Page)
                when (uiState.activeQuickPanel) {
                    ReaderQuickPanel.MODE -> {
                        ReaderQuickModePanel(
                            currentMode = uiState.preferences.mode,
                            currentFit = uiState.preferences.fit,
                            isLandscapeViewport = uiState.isLandscapeViewport,
                            onSelectMode = viewModel::setReadingMode,
                            onSelectFit = viewModel::setReaderFit,
                        )
                    }
                    ReaderQuickPanel.BRIGHTNESS_AUTO_PAGE -> {
                        ReaderQuickBrightnessAutoPagePanel(
                            brightnessPercent = uiState.preferences.brightnessPercent,
                            autoPageIntervalSeconds = uiState.autoPageIntervalSeconds,
                            slideshowOrder = uiState.slideshowOrder,
                            onBrightnessPercentChange = viewModel::setBrightnessPercent,
                            onAutoPageIntervalChange = viewModel::setAutoPageInterval,
                            onSlideshowOrderChange = viewModel::setSlideshowOrder,
                        )
                    }
                    ReaderQuickPanel.NONE -> Unit
                }

                ReaderBottomControlBar(
                    uiState = uiState,
                    onScrubStart = { viewModel.onInteractionStateChanged(true) },
                    onScrubPreview = { targetPage -> viewModel.jumpToPage(targetPage) },
                    onScrubCommit = { targetPage ->
                        viewModel.onInteractionStateChanged(false)
                        viewModel.jumpToPage(targetPage)
                    },
                    onOpenThumbnails = { viewModel.openSheet(ReaderSheet.THUMBNAILS) },
                    onOpenBookmarks = { viewModel.openSheet(ReaderSheet.BOOKMARKS) },
                    onToggleModePanel = { viewModel.toggleQuickPanel(ReaderQuickPanel.MODE) },
                    onToggleBrightnessPanel = { viewModel.toggleQuickPanel(ReaderQuickPanel.BRIGHTNESS_AUTO_PAGE) },
                    onOpenSettings = { viewModel.openSheet(ReaderSheet.SETTINGS) },
                )
            }
        }

        // Export Progress Pill Overlay
        val exportText = uiState.exportStatusText
        if (uiState.isExporting && !exportText.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .ravenFrostedSurface(
                        reduceTransparency = reduceTransparency,
                        shape = RavenShapes.PillShape,
                    )
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.crimson,
                )
                Text(
                    text = exportText,
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    color = colors.inkPrimary,
                )
            }
        }

        // Snackbar Host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (uiState.chromeVisible) 136.dp else 44.dp),
        )
    }

    // Single-screen Modal Bottom Sheets
    when (uiState.activeSheet) {
        ReaderSheet.THUMBNAILS -> {
            ReaderThumbnailsSheet(
                uiState = uiState,
                onLoadThumbnailBitmap = viewModel::loadThumbnailBitmap,
                onSelectPage = { page -> viewModel.jumpToPage(page, closeOverlays = true) },
                onDismiss = viewModel::dismissSheet,
            )
        }
        ReaderSheet.BOOKMARKS -> {
            ReaderBookmarksSheet(
                currentPage = uiState.currentPage,
                isCurrentPageBookmarked = uiState.isCurrentPageBookmarked,
                bookmarks = uiState.pageBookmarks,
                onToggleCurrentPageBookmark = { viewModel.toggleCurrentPageBookmark() },
                onJumpToBookmark = { bookmark ->
                    viewModel.jumpToPage(
                        page = bookmark.page,
                        scrollOffsetPx = bookmark.scrollOffsetPx,
                        closeOverlays = true,
                    )
                },
                onDeleteBookmark = viewModel::deletePageBookmark,
                onDismiss = viewModel::dismissSheet,
            )
        }
        ReaderSheet.SETTINGS -> {
            ReaderSettingsSheet(
                uiState = uiState,
                onSetPerBookOverride = viewModel::setPerBookOverride,
                onResetToSiteDefault = viewModel::resetToSiteDefault,
                onSelectMode = viewModel::setReadingMode,
                onSelectFit = viewModel::setReaderFit,
                onCropChange = viewModel::setCropPercent,
                onCombineCoverChange = viewModel::setCombineCover,
                onShowStatusBarChange = viewModel::setShowStatusBar,
                onTapZonesEnabledChange = viewModel::setTapZonesEnabled,
                onAutoSplitWidePagesChange = viewModel::setAutoSplitWidePages,
                onPreferOriginalChange = viewModel::setPreferOriginalImages,
                onBrightnessPercentChange = viewModel::setBrightnessPercent,
                onKeepScreenOnChange = viewModel::setKeepScreenOn,
                onVolumeKeyPagingChange = viewModel::setVolumeKeyPaging,
                onReverseVolumeKeysChange = viewModel::setReverseVolumeKeys,
                onAutoPageIntervalChange = viewModel::setAutoPageInterval,
                onSlideshowOrderChange = viewModel::setSlideshowOrder,
                onToggleBookmark = { viewModel.toggleCurrentPageBookmark() },
                onToggleImageFavorite = viewModel::toggleCurrentPageImageFavorite,
                onExportCurrentPage = viewModel::exportCurrentPage,
                onExportCbz = viewModel::exportCbz,
                onDismiss = viewModel::dismissSheet,
            )
        }
        ReaderSheet.NONE -> Unit
    }
}

@Composable
private fun StripReaderViewport(
    uiState: ReaderUiState,
    events: SharedFlow<ReaderEvent>,
    onVisiblePagesChanged: (anchorPage: Int, visiblePages: Set<Int>, scrollOffsetPx: Int) -> Unit,
    onConsumePendingOffset: () -> Unit,
    onSingleTap: () -> Unit,
    onRetryPage: (Int) -> Unit,
    onInteractionChanged: (Boolean) -> Unit,
) {
    val pageNumbers = uiState.pageNumbers
    val initialIndex = remember(pageNumbers) {
        pageNumbers.indexOf(uiState.currentPage).coerceAtLeast(0)
    }
    val initialOffset = remember(pageNumbers) { uiState.stripScrollOffsetPx.coerceAtLeast(0) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = initialOffset,
    )

    var zoom by remember { mutableFloatStateOf(ReaderGeometry.MIN_ZOOM) }
    var pan by remember { mutableStateOf(PanOffset.ZERO) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnInteraction by rememberUpdatedState(onInteractionChanged)

    LaunchedEffect(zoom) {
        currentOnInteraction(zoom > ReaderGeometry.MIN_ZOOM + 0.01f)
    }

    // Restore exact pixel offset when returning to STRIP mode or initial resume.
    LaunchedEffect(uiState.pendingStripRestoreOffsetPx, pageNumbers) {
        val pendingOffset = uiState.pendingStripRestoreOffsetPx ?: return@LaunchedEffect
        val idx = pageNumbers.indexOf(uiState.currentPage).coerceAtLeast(0)
        listState.scrollToItem(idx, pendingOffset.coerceAtLeast(0))
        onConsumePendingOffset()
    }

    // Programmatic jump-to-page handler.
    LaunchedEffect(events, pageNumbers) {
        events.collect { event ->
            if (event is ReaderEvent.ScrollToPage) {
                val idx = pageNumbers.indexOf(event.page)
                if (idx in pageNumbers.indices) {
                    zoom = ReaderGeometry.MIN_ZOOM
                    pan = PanOffset.ZERO
                    listState.scrollToItem(idx, event.scrollOffsetPx.coerceAtLeast(0))
                }
            }
        }
    }

    // Observe visible strip pages & pixel offset cleanly.
    LaunchedEffect(listState, pageNumbers) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                pageNumbers.getOrNull(item.index)
            }
            Triple(
                visible.firstOrNull(),
                visible.toSet(),
                listState.firstVisibleItemScrollOffset,
            )
        }
            .distinctUntilChanged()
            .collectLatest { (anchor, visibleSet, offsetPx) ->
                if (anchor != null) {
                    onVisiblePagesChanged(anchor, visibleSet, offsetPx)
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnSingleTap() },
                    onDoubleTap = {
                        val nextZoom = ReaderGeometry.toggleDoubleTapZoom(zoom)
                        zoom = nextZoom
                        if (nextZoom <= ReaderGeometry.MIN_ZOOM) pan = PanOffset.ZERO
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var gestureActive = false
                    val touchSlop = viewConfiguration.touchSlop
                    var accumZoom = 1f
                    var accumPanX = 0f
                    var accumPanY = 0f

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val pointers = event.changes.count { it.pressed }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!gestureActive) {
                                accumZoom *= zoomChange
                                accumPanX += panChange.x
                                accumPanY += panChange.y
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1f - accumZoom) * centroidSize
                                val panMotion = kotlin.math.hypot(accumPanX, accumPanY)
                                if (pointers >= 2 && (zoomMotion > touchSlop || abs(1f - accumZoom) > 0.03f)) {
                                    gestureActive = true
                                } else if (zoom > ReaderGeometry.MIN_ZOOM + 0.01f && panMotion > touchSlop) {
                                    gestureActive = true
                                }
                            }
                            if (gestureActive) {
                                val nextZoom = ReaderGeometry.clampZoom(zoom * zoomChange)
                                val nextPan = if (nextZoom <= ReaderGeometry.MIN_ZOOM + 0.005f) {
                                    PanOffset.ZERO
                                } else if (viewportSize.width > 0 && viewportSize.height > 0) {
                                    val limit = ReaderGeometry.computePanLimit(
                                        bitmapWidth = viewportSize.width,
                                        bitmapHeight = viewportSize.height,
                                        containerWidth = viewportSize.width,
                                        containerHeight = viewportSize.height,
                                        fit = ReaderFit.WIDTH,
                                        cropPercent = 0,
                                        zoom = nextZoom,
                                    )
                                    ReaderGeometry.clampPan(PanOffset(pan.x + panChange.x, pan.y + panChange.y), limit)
                                } else {
                                    PanOffset.ZERO
                                }
                                zoom = nextZoom
                                pan = nextPan
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            },
    ) {
        LazyColumn(
            state = listState,
            userScrollEnabled = zoom <= ReaderGeometry.MIN_ZOOM + 0.01f,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            items(
                items = pageNumbers,
                key = { pageNum -> "strip_page_$pageNum" },
            ) { pageNumber ->
                StripReaderPageItem(
                    pageNumber = pageNumber,
                    loadedPage = uiState.bufferState.pages[pageNumber],
                    errorMessage = uiState.bufferState.errors[pageNumber],
                    preservedAspectRatio = uiState.pageAspectRatios[pageNumber],
                    cropPercent = uiState.preferences.cropPercent,
                    onRetry = { onRetryPage(pageNumber) },
                )
            }
        }
    }
}

@Composable
private fun PagedHorizontalViewport(
    reverseLayout: Boolean,
    uiState: ReaderUiState,
    events: SharedFlow<ReaderEvent>,
    onPageChanged: (Int) -> Unit,
    onSingleTap: () -> Unit,
    onRetryPage: (Int) -> Unit,
    onZoomedChanged: (Boolean) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val pageNumbers = uiState.pageNumbers
    key(reverseLayout, pageNumbers.size) {
        val initialIdx = remember(pageNumbers) {
            pageNumbers.indexOf(uiState.currentPage).coerceIn(0, (pageNumbers.size - 1).coerceAtLeast(0))
        }
        val pagerState = rememberPagerState(initialPage = initialIdx) { pageNumbers.size }
        var isZoomed by remember { mutableStateOf(false) }

        LaunchedEffect(pagerState, pageNumbers) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collectLatest { idx ->
                    val page = pageNumbers.getOrNull(idx) ?: return@collectLatest
                    isZoomed = false
                    onZoomedChanged(false)
                    onPageChanged(page)
                }
        }

        LaunchedEffect(events, pageNumbers) {
            events.collect { event ->
                if (event is ReaderEvent.ScrollToPage) {
                    val idx = pageNumbers.indexOf(event.page)
                    if (idx in pageNumbers.indices && pagerState.currentPage != idx) {
                        isZoomed = false
                        onZoomedChanged(false)
                        pagerState.scrollToPage(idx)
                    }
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            reverseLayout = reverseLayout,
            userScrollEnabled = !isZoomed,
            beyondViewportPageCount = 1,
            key = { idx -> pageNumbers[idx] },
            modifier = Modifier.fillMaxSize(),
        ) { idx ->
            val pageNumber = pageNumbers[idx]
            PagedReaderCanvas(
                pageKey = "${if (reverseLayout) "rtl" else "ltr"}_$pageNumber",
                pageNumber = pageNumber,
                loadedPage = uiState.bufferState.pages[pageNumber],
                errorMessage = uiState.bufferState.errors[pageNumber],
                fit = uiState.preferences.fit,
                cropPercent = uiState.preferences.cropPercent,
                onSingleTap = onSingleTap,
                onRetry = { onRetryPage(pageNumber) },
                onZoomedChanged = { zoomed ->
                    if (pagerState.currentPage == idx) {
                        isZoomed = zoomed
                        onZoomedChanged(zoomed)
                    }
                },
                tapZonesEnabled = uiState.tapZonesEnabled,
                isRtl = reverseLayout,
                autoSplitWidePages = uiState.autoSplitWidePages,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
            )
        }
    }
}

@Composable
private fun SpreadReaderViewport(
    navigator: SpreadNavigator,
    uiState: ReaderUiState,
    events: SharedFlow<ReaderEvent>,
    onSheetChanged: (List<Int>) -> Unit,
    onSingleTap: () -> Unit,
    onRetryPage: (Int) -> Unit,
    onZoomedChanged: (Boolean) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val sheets = navigator.sheets
    key(navigator.spread, navigator.combineCover, sheets.size) {
        val initialSheet = remember(sheets) {
            navigator.sheetIndex(uiState.currentPage).coerceIn(0, (sheets.size - 1).coerceAtLeast(0))
        }
        val pagerState = rememberPagerState(initialPage = initialSheet) { sheets.size }
        var isZoomed by remember { mutableStateOf(false) }

        LaunchedEffect(pagerState, sheets) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collectLatest { idx ->
                    val sheet = sheets.getOrNull(idx) ?: return@collectLatest
                    isZoomed = false
                    onZoomedChanged(false)
                    onSheetChanged(sheet)
                }
        }

        LaunchedEffect(events, navigator) {
            events.collect { event ->
                if (event is ReaderEvent.ScrollToPage) {
                    val idx = navigator.sheetIndex(event.page)
                    if (idx in sheets.indices && pagerState.currentPage != idx) {
                        isZoomed = false
                        onZoomedChanged(false)
                        pagerState.scrollToPage(idx)
                    }
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isZoomed,
            beyondViewportPageCount = 1,
            key = { idx -> sheets[idx].joinToString("-") },
            modifier = Modifier.fillMaxSize(),
        ) { idx ->
            val sheet = sheets[idx]
            SpreadSheetCanvas(
                sheetKey = "spread_${navigator.spread}_${sheet.joinToString("-")}",
                sheetPages = sheet,
                pagesMap = uiState.bufferState.pages,
                errorsMap = uiState.bufferState.errors,
                cropPercent = uiState.preferences.cropPercent,
                onSingleTap = onSingleTap,
                onRetryPage = onRetryPage,
                onZoomedChanged = { zoomed ->
                    if (pagerState.currentPage == idx) {
                        isZoomed = zoomed
                        onZoomedChanged(zoomed)
                    }
                },
                tapZonesEnabled = uiState.tapZonesEnabled,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
            )
        }
    }
}

@Composable
private fun ReaderBottomControlBar(
    uiState: ReaderUiState,
    onScrubStart: () -> Unit,
    onScrubPreview: (Int) -> Unit,
    onScrubCommit: (Int) -> Unit,
    onOpenThumbnails: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onToggleModePanel: () -> Unit,
    onToggleBrightnessPanel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val pages = uiState.pageNumbers
    val maxIdx = (pages.size - 1).coerceAtLeast(0)
    val currentIdx = remember(pages, uiState.currentPage) {
        pages.indexOf(uiState.currentPage).coerceIn(0, maxIdx)
    }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubIndex by remember(currentIdx) { mutableIntStateOf(currentIdx) }
    LaunchedEffect(isScrubbing, scrubIndex) {
        if (isScrubbing) {
            kotlinx.coroutines.delay(250L)
            val target = pages.getOrElse(scrubIndex) { uiState.currentPage }
            onScrubPreview(target)
        }
    }
    val displayPage = pages.getOrElse(if (isScrubbing) scrubIndex else currentIdx) { uiState.currentPage }
    val displayPercent = if (uiState.totalPages <= 0) 0 else ((displayPage.toFloat() / uiState.totalPages) * 100f).toInt().coerceIn(0, 100)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Floating target page pill shown while dragging scrubber
        AnimatedVisibility(
            visible = isScrubbing,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(120)),
        ) {
            RavenFrostedBox(
                shape = RavenShapes.PillShape,
                reduceTransparency = reduceTransparency,
                borderColor = colors.rimHighlight,
                modifier = Modifier.testTag(ReaderTestTags.READER_SCRUBBER_PILL),
            ) {
                Text(
                    text = "第 ${RavenFormatters.formatPageProgress(displayPage, uiState.totalPages)} 页 · $displayPercent%",
                    style = RavenTabularTextStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.inkPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        RavenFrostedBox(
            shape = RectangleShape,
            reduceTransparency = reduceTransparency,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                // Page Scrubber Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = displayPage.toString(),
                        style = RavenTabularTextStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.inkPrimary,
                        modifier = Modifier.width(34.dp),
                        textAlign = TextAlign.End,
                    )
                    Slider(
                        value = (if (isScrubbing) scrubIndex else currentIdx).toFloat(),
                        onValueChange = { raw ->
                            if (!isScrubbing) {
                                isScrubbing = true
                                onScrubStart()
                            }
                            scrubIndex = raw.roundToInt().coerceIn(0, maxIdx)
                        },
                        onValueChangeFinished = {
                            val target = pages.getOrElse(scrubIndex) { uiState.currentPage }
                            isScrubbing = false
                            onScrubCommit(target)
                        },
                        valueRange = 0f..maxIdx.coerceAtLeast(1).toFloat(),
                        enabled = pages.size > 1,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.inkPrimary,
                            activeTrackColor = colors.crimson,
                            inactiveTrackColor = colors.surface3,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = uiState.totalPages.toString(),
                        style = RavenTabularTextStyle,
                        color = colors.inkSecondary,
                        modifier = Modifier.width(34.dp),
                    )
                }

                // 5 Quick Action Buttons: 缩略图 / 书签 / 模式 / 亮度与自动翻页 / 更多设置
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderQuickBarButton(
                        icon = Icons.Outlined.GridView,
                        label = "缩略图",
                        active = uiState.activeSheet == ReaderSheet.THUMBNAILS,
                        onClick = onOpenThumbnails,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderQuickBarButton(
                        icon = if (uiState.pageBookmarks.isNotEmpty()) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        label = if (uiState.pageBookmarks.isNotEmpty()) "书签(${uiState.pageBookmarks.size})" else "书签",
                        active = uiState.activeSheet == ReaderSheet.BOOKMARKS || uiState.isCurrentPageBookmarked,
                        onClick = onOpenBookmarks,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderQuickBarButton(
                        icon = Icons.Outlined.AutoStories,
                        label = readerModeLabel(uiState.preferences.mode),
                        active = uiState.activeQuickPanel == ReaderQuickPanel.MODE,
                        onClick = onToggleModePanel,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderQuickBarButton(
                        icon = Icons.Outlined.Brightness6,
                        label = if (uiState.autoPageIntervalSeconds > 0) "自动 ${uiState.autoPageIntervalSeconds}s" else "亮度/翻页",
                        active = uiState.activeQuickPanel == ReaderQuickPanel.BRIGHTNESS_AUTO_PAGE || uiState.autoPageIntervalSeconds > 0,
                        onClick = onToggleBrightnessPanel,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderQuickBarButton(
                        icon = Icons.Outlined.Tune,
                        label = "更多设置",
                        active = uiState.activeSheet == ReaderSheet.SETTINGS,
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun ReaderQuickBarButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val tint = if (active) colors.crimson else colors.inkPrimary

    Column(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(RavenShapes.ControlShape)
            .ravenSpringPress(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = if (active) colors.crimson else colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReaderAuthGateCard(
    siteName: String,
    message: String,
    onOpenAccount: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(horizontal = 20.dp, vertical = 28.dp)
            .testTag(ReaderTestTags.READER_AUTH_GATE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.crimson.copy(alpha = 0.14f))
                .border(0.5.dp, colors.crimson.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = "$siteName 需要登录验证",
            style = MaterialTheme.typography.titleLarge,
            color = colors.inkPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpenAccount,
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = "前往账号登录",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            OutlinedButton(
                onClick = onRetry,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface2,
                    contentColor = colors.inkPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
