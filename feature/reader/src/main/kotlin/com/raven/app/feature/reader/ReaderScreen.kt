package com.raven.app.feature.reader

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.raven.app.core.designsystem.component.RavenContentCrossfade
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFeatureTutorialDialog
import com.raven.app.core.designsystem.component.RavenTutorialStep
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.RavenMorphToggleIcon
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenFrostedBox
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenFrostedSurface
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.reader.PanOffset
import com.raven.app.core.media.reader.ReaderGeometry
import com.raven.app.core.media.reader.SpreadNavigator
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReaderMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen immersive 4-mode Raven Reader (`STRIP`, `LTR`, `RTL`, `SPREAD`) featuring:
 * - Single-tap frosted chrome & system-bar immersion
 * - Double-tap `1x/2x` zoom, pinch `1f..5f`, strictly clamped pan via [ReaderGeometry]
 * - Per-page aspect ratio preservation across mode switches & orientation changes
 * - Hardware volume-key paging (normal/reversed + long-press first/last jump)
 * - Corner [ReaderStatusBar], bottom scrubber w/ floating target page pill & 5 quick buttons
 * - Previous/next chapter controls, sortable chapter picker, and direct work-detail navigation
 * - 85%-height Thumbnail Overview sheet, Bookmarks sheet, Single-screen Grouped Settings sheet
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    navigator: ReaderNavigator,
    modifier: Modifier = Modifier,
    parent: RavenRoute.ReaderParent? = null,
    registerVolumeKeyHandler: (((up: Boolean, isLongPress: Boolean) -> Boolean)?) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
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
        viewModel.onScreenStarted()
    }

    // Snackbar messages
    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val exportPicker = rememberExportDocumentPicker { prepared, uri, tree ->
        viewModel.completeSafExport(prepared, uri, tree, context.contentResolver)
    }
    // Export, Share, and Sequential Queue side-effect events
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderEvent.ScrollToPage -> Unit // Handled by active mode viewport
                is ReaderEvent.RequestSaveDocument -> exportPicker(event.prepared, false)
                is ReaderEvent.RequestOpenDocumentTree -> exportPicker(event.prepared, true)
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
                is ReaderEvent.OpenChapter -> {
                    runCatching {
                        navigator.openReader(
                            siteId = event.nextItem.siteId,
                            mediaId = event.nextItem.id,
                            url = event.nextItem.url,
                            startPage = 1,
                            offlineOnly = false,
                            queueToken = null,
                            parent = parent,
                        )
                    }.onFailure {
                        viewModel.onChapterNavigationFailed()
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
        val readerStageKey = when {
            uiState.isLoading && uiState.detail == null -> "loading"
            uiState.authRequired -> "auth"
            uiState.detail == null || uiState.pageNumbers.isEmpty() -> "error"
            else -> "mode_${uiState.preferences.mode.name}"
        }
        RavenContentCrossfade(targetState = readerStageKey) { stage ->
            when (stage) {
                "loading" -> {
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
                "auth" -> {
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
                "error" -> {
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
                                onStripProgressChanged = viewModel::onStripViewportProgress,
                                onConsumePendingOffset = viewModel::consumePendingStripRestoreOffset,
                                onSingleTap = viewModel::toggleChrome,
                                onRetryPage = viewModel::retryAppendedChapterPage,
                                onRetryAppendChapter = { viewModel.preloadNextSerialChapterIfNeeded(forceRetry = true) },
                                onInteractionChanged = viewModel::onInteractionStateChanged,
                                onEndReached = viewModel::onStripEndReached,
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
        }

        AnimatedVisibility(
            visible = uiState.isChapterTransitionLoading,
            enter = if (motionReduced) EnterTransition.None else fadeIn(tween(120, easing = RavenMotion.HarmonyDecelerationEasing)),
            exit = if (motionReduced) ExitTransition.None else fadeOut(tween(120, easing = RavenMotion.HarmonyDecelerationEasing)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                modifier = Modifier
                    .clip(RavenShapes.PillShape)
                    .background(colors.surface1)
                    .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.crimson,
                )
                Text(
                    text = "正在打开所选话…",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkPrimary,
                )
            }
        }

        // Corner Status Bar (visible when chrome hidden & configured enabled)
        AnimatedVisibility(
            visible = !uiState.chromeVisible && uiState.showStatusBar && uiState.detail != null,
            enter = if (motionReduced) EnterTransition.None else fadeIn(tween(140, easing = RavenMotion.HarmonyDecelerationEasing)),
            exit = if (motionReduced) ExitTransition.None else fadeOut(tween(100, easing = RavenMotion.HarmonyDecelerationEasing)),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            ReaderStatusBar(
                currentPage = uiState.displayCurrentPage,
                totalPages = uiState.displayTotalPages,
                progressPercent = uiState.progressPercent,
                autoPageIntervalSeconds = uiState.autoPageIntervalSeconds,
            )
        }

        // Top Frosted Chrome Bar
        AnimatedVisibility(
            visible = uiState.chromeVisible,
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                fadeIn(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    slideInVertically(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)) { -it }
            },
            exit = if (motionReduced) {
                ExitTransition.None
            } else {
                fadeOut(tween(140, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    slideOutVertically(tween(160, easing = RavenMotion.HarmonyDecelerationEasing)) { -it }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .testTag(ReaderTestTags.READER_TOP_BAR),
        ) {
            val subtitle = buildList {
                add(uiState.siteDisplayName)
                if (uiState.offlineOnly) add("离线")
                if (uiState.displayTotalPages > 0) {
                    add(RavenFormatters.formatPageProgress(uiState.displayCurrentPage, uiState.displayTotalPages))
                }
            }.joinToString(" · ")

            RavenTopBar(
                title = uiState.displayTitle,
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
                        IconButton(
                            onClick = navigator::navigateBack,
                            modifier = Modifier.testTag(ReaderTestTags.READER_OPEN_DETAIL),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "返回作品详情",
                                tint = colors.inkPrimary,
                            )
                        }
                        IconButton(
                            onClick = {
                                feedback.perform(
                                    if (uiState.isCurrentPageBookmarked) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.FAVORITE,
                                )
                                viewModel.toggleCurrentPageBookmark()
                            },
                        ) {
                            RavenMorphToggleIcon(
                                active = uiState.isCurrentPageBookmarked,
                                activeGlyph = RavenMorphIcons.BookmarkFilled,
                                inactiveGlyph = RavenMorphIcons.BookmarkOutline,
                                activeTint = colors.crimson,
                                inactiveTint = colors.inkPrimary,
                                size = 24.dp,
                                springPreset = MorphSpringPreset.SNAPPY,
                                contentDescription = if (uiState.isCurrentPageBookmarked) "取消书签" else "添加书签",
                            )
                        }

                        IconButton(
                            onClick = {
                                feedback.perform(
                                    if (uiState.isCurrentPageImageFavorited) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.FAVORITE,
                                )
                                viewModel.toggleCurrentPageImageFavorite()
                            },
                            modifier = Modifier.ravenBurstFeedback(trigger = uiState.isCurrentPageImageFavorited),
                        ) {
                            RavenMorphToggleIcon(
                                active = uiState.isCurrentPageImageFavorited,
                                activeGlyph = RavenMorphIcons.HeartFilled,
                                inactiveGlyph = RavenMorphIcons.HeartOutline,
                                activeTint = colors.crimson,
                                inactiveTint = colors.inkPrimary,
                                size = 24.dp,
                                springPreset = MorphSpringPreset.BOUNCY,
                                contentDescription = if (uiState.isCurrentPageImageFavorited) "取消收藏单图" else "收藏单图",
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
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                fadeIn(tween(160, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    slideInVertically(tween(200, easing = RavenMotion.HarmonyDecelerationEasing)) { it }
            },
            exit = if (motionReduced) {
                ExitTransition.None
            } else {
                fadeOut(tween(140, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    slideOutVertically(tween(160, easing = RavenMotion.HarmonyDecelerationEasing)) { it }
            },
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
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                fadeIn(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    slideInVertically(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)) { it }
            },
            exit = if (motionReduced) {
                ExitTransition.None
            } else {
                fadeOut(tween(140, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    slideOutVertically(tween(160, easing = RavenMotion.HarmonyDecelerationEasing)) { it }
            },
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
                AnimatedVisibility(
                    visible = uiState.activeQuickPanel != ReaderQuickPanel.NONE,
                    enter = if (motionReduced) {
                        EnterTransition.None
                    } else {
                        expandVertically(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)) +
                            fadeIn(tween(160, easing = RavenMotion.HarmonyDecelerationEasing))
                    },
                    exit = if (motionReduced) {
                        ExitTransition.None
                    } else {
                        shrinkVertically(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)) +
                            fadeOut(tween(120, easing = RavenMotion.HarmonyDecelerationEasing))
                    },
                ) {
                    RavenContentCrossfade(targetState = uiState.activeQuickPanel) { panel ->
                        when (panel) {
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
                    }
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
                    onPreviousChapter = viewModel::openPreviousChapter,
                    onNextChapter = viewModel::openNextChapter,
                    onOpenChapters = { viewModel.openSheet(ReaderSheet.CHAPTERS) },
                )
            }
        }

        // Export Progress Pill Overlay
        val exportText = uiState.exportStatusText
        AnimatedVisibility(
            visible = uiState.isExporting && !exportText.isNullOrBlank(),
            enter = if (motionReduced) EnterTransition.None else fadeIn(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)),
            exit = if (motionReduced) ExitTransition.None else fadeOut(tween(120, easing = RavenMotion.HarmonyDecelerationEasing)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            if (!exportText.isNullOrBlank()) {
                Row(
                    modifier = Modifier
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
                currentPage = uiState.displayCurrentPage,
                isCurrentPageBookmarked = uiState.isCurrentPageBookmarked,
                bookmarks = uiState.pageBookmarks,
                onToggleCurrentPageBookmark = { viewModel.toggleCurrentPageBookmark() },
                onJumpToBookmark = { bookmark ->
                    viewModel.jumpToPage(
                        page = bookmark.page,
                        chapterId = bookmark.mediaId,
                        scrollOffsetPx = bookmark.scrollOffsetPx,
                        closeOverlays = true,
                    )
                },
                onDeleteBookmark = viewModel::deletePageBookmark,
                onDismiss = viewModel::dismissSheet,
            )
        }
        ReaderSheet.CHAPTERS -> {
            ReaderChaptersSheet(
                uiState = uiState,
                onSelectChapter = viewModel::openChapter,
                onRefresh = viewModel::refreshChapters,
                onOpenDetail = navigator::navigateBack,
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

    if (uiState.showReaderTutorial && uiState.detail != null) {
        RavenFeatureTutorialDialog(
            featureTitle = "阅读器使用引导",
            steps = listOf(
                RavenTutorialStep(
                    badge = "连载沉浸阅读",
                    title = "垂直模式无缝下滑下一话",
                    description = "在条漫（垂直下滑）模式下阅读连载漫画时，滑至本话末尾会自动在后台加载并拼接下一话，无需刷新页面即可一直往下滑。",
                    icon = Icons.Outlined.AutoStories,
                ),
                RavenTutorialStep(
                    badge = "阅读控制",
                    title = "轻点呼出菜单 · 双指缩放",
                    description = "轻点屏幕中央显示或隐藏顶底工具栏，可随时切换目录、条漫/左翻/右翻/双页模式与亮度；双击或双指捏合可流畅缩放细节。",
                    icon = Icons.Outlined.Tune,
                ),
                RavenTutorialStep(
                    badge = "高光与书签",
                    title = "单图收藏与页码书签",
                    description = "右上角可将当前精彩画面加入「单图收藏」或打上页码书签，退出阅读器时会自动保存精准阅读进度。",
                    icon = Icons.Outlined.GridView,
                ),
            ),
            onComplete = viewModel::completeReaderTutorial,
            onSkipAll = viewModel::skipAllTutorials,
        )
    }
}

private sealed interface StripFeedEntry {
    val key: String

    data class PrimaryPage(
        val chapterId: String,
        val pageNumber: Int,
    ) : StripFeedEntry {
        override val key: String = "strip_page_$pageNumber"
    }

    data class ChapterDivider(
        val chapterId: String,
        val title: String,
        val pageCount: Int,
    ) : StripFeedEntry {
        override val key: String = "strip_sep_$chapterId"
    }

    data class AppendedPage(
        val chapterId: String,
        val pageNumber: Int,
    ) : StripFeedEntry {
        override val key: String = "strip_chap_${chapterId}_p_$pageNumber"
    }
}

private fun resolveStripIndex(entries: List<StripFeedEntry>, chapterId: String?, page: Int): Int {
    return entries.indexOfFirst { entry ->
        when (entry) {
            is StripFeedEntry.PrimaryPage -> (chapterId == null || entry.chapterId == chapterId) && entry.pageNumber == page
            is StripFeedEntry.AppendedPage -> (chapterId == null || entry.chapterId == chapterId) && entry.pageNumber == page
            else -> false
        }
    }
}

@Composable
private fun StripReaderViewport(
    uiState: ReaderUiState,
    events: SharedFlow<ReaderEvent>,
    onStripProgressChanged: (
        anchorChapterId: String,
        anchorPage: Int,
        visiblePagesByChapter: Map<String, Set<Int>>,
        scrollOffsetPx: Int,
        remainingItemsToBottom: Int,
    ) -> Unit,
    onConsumePendingOffset: () -> Unit,
    onSingleTap: () -> Unit,
    onRetryPage: (chapterMediaId: String, pageNumber: Int) -> Unit,
    onRetryAppendChapter: () -> Unit,
    onInteractionChanged: (Boolean) -> Unit,
    onEndReached: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val pageNumbers = uiState.pageNumbers
    val stripEntries = remember(uiState.mediaId, pageNumbers, uiState.appendedChapters) {
        buildList {
            for (p in pageNumbers) {
                add(StripFeedEntry.PrimaryPage(uiState.mediaId, p))
            }
            for (chapter in uiState.appendedChapters) {
                add(StripFeedEntry.ChapterDivider(chapter.mediaId, chapter.title, chapter.pageNumbers.size))
                for (p in chapter.pageNumbers) {
                    add(StripFeedEntry.AppendedPage(chapter.mediaId, p))
                }
            }
        }
    }
    val appendedChapterMap = remember(uiState.appendedChapters) {
        uiState.appendedChapters.associateBy { it.mediaId }
    }
    val initialIndex = remember(pageNumbers) {
        pageNumbers.indexOf(uiState.currentPage).coerceAtLeast(0)
    }
    val initialOffset = remember(pageNumbers) { uiState.stripScrollOffsetPx.coerceAtLeast(0) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = initialOffset,
    )

    val motionReduced = LocalMotionReduced.current
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(ReaderGeometry.MIN_ZOOM) }
    var pan by remember { mutableStateOf(PanOffset.ZERO) }
    var doubleTapAnimJob by remember { mutableStateOf<Job?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnInteraction by rememberUpdatedState(onInteractionChanged)
    val currentOnStripProgress by rememberUpdatedState(onStripProgressChanged)
    val currentEntries by rememberUpdatedState(stripEntries)
    val currentIsSerialComic by rememberUpdatedState(uiState.isSerialComic)
    val isZoomed by remember { derivedStateOf { zoom > ReaderGeometry.MIN_ZOOM + 0.01f } }

    LaunchedEffect(isZoomed) {
        currentOnInteraction(isZoomed)
    }

    // Restore exact pixel offset when returning to STRIP mode or initial resume.
    LaunchedEffect(uiState.pendingStripRestoreOffsetPx, pageNumbers) {
        val pendingOffset = uiState.pendingStripRestoreOffsetPx ?: return@LaunchedEffect
        val idx = pageNumbers.indexOf(uiState.currentPage).coerceAtLeast(0)
        listState.scrollToItem(idx, pendingOffset.coerceAtLeast(0))
        onConsumePendingOffset()
    }

    // Programmatic jump-to-page handler.
    LaunchedEffect(events) {
        events.collect { event ->
            if (event is ReaderEvent.ScrollToPage) {
                val idx = resolveStripIndex(currentEntries, event.chapterId, event.page)
                if (idx >= 0) {
                    doubleTapAnimJob?.cancel()
                    zoom = ReaderGeometry.MIN_ZOOM
                    pan = PanOffset.ZERO
                    listState.scrollToItem(idx, event.scrollOffsetPx.coerceAtLeast(0))
                }
            }
        }
    }

    // 120Hz zero-recomposition page & seamless-chapter tracker.
    LaunchedEffect(listState, stripEntries) {
        snapshotFlow {
            val visibleInfo = listState.layoutInfo.visibleItemsInfo
            val byChapter = LinkedHashMap<String, MutableSet<Int>>()
            var anchorChapter: String? = null
            var anchorPage: Int? = null
            var lastVisibleIdx = -1
            for (item in visibleInfo) {
                lastVisibleIdx = maxOf(lastVisibleIdx, item.index)
                when (val entry = stripEntries.getOrNull(item.index)) {
                    is StripFeedEntry.PrimaryPage -> {
                        if (anchorChapter == null) {
                            anchorChapter = entry.chapterId
                            anchorPage = entry.pageNumber
                        }
                        byChapter.getOrPut(entry.chapterId) { LinkedHashSet() }.add(entry.pageNumber)
                    }
                    is StripFeedEntry.AppendedPage -> {
                        if (anchorChapter == null) {
                            anchorChapter = entry.chapterId
                            anchorPage = entry.pageNumber
                        }
                        byChapter.getOrPut(entry.chapterId) { LinkedHashSet() }.add(entry.pageNumber)
                    }
                    else -> Unit
                }
            }
            val remaining = if (lastVisibleIdx < 0) stripEntries.size else (stripEntries.lastIndex - lastVisibleIdx).coerceAtLeast(0)
            Triple(anchorChapter to anchorPage, byChapter.mapValues { it.value.toSet() }, remaining)
        }
            .distinctUntilChanged()
            .collectLatest { (anchorPair, visibleMap, remaining) ->
                val (chapId, page) = anchorPair
                if (chapId != null && page != null) {
                    currentOnStripProgress(
                        chapId,
                        page,
                        visibleMap,
                        listState.firstVisibleItemScrollOffset,
                        remaining,
                    )
                }
            }
    }

    // Flush exact pixel offset only when strip scrolling settles (or on dispose).
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { inProgress ->
                if (!inProgress) {
                    val entries = currentEntries
                    val visibleInfo = listState.layoutInfo.visibleItemsInfo
                    val byChapter = LinkedHashMap<String, MutableSet<Int>>()
                    var anchorChapter: String? = null
                    var anchorPage: Int? = null
                    var lastVisibleIdx = -1
                    for (item in visibleInfo) {
                        lastVisibleIdx = maxOf(lastVisibleIdx, item.index)
                        when (val entry = entries.getOrNull(item.index)) {
                            is StripFeedEntry.PrimaryPage -> {
                                if (anchorChapter == null) {
                                    anchorChapter = entry.chapterId
                                    anchorPage = entry.pageNumber
                                }
                                byChapter.getOrPut(entry.chapterId) { LinkedHashSet() }.add(entry.pageNumber)
                            }
                            is StripFeedEntry.AppendedPage -> {
                                if (anchorChapter == null) {
                                    anchorChapter = entry.chapterId
                                    anchorPage = entry.pageNumber
                                }
                                byChapter.getOrPut(entry.chapterId) { LinkedHashSet() }.add(entry.pageNumber)
                            }
                            else -> Unit
                        }
                    }
                    if (anchorChapter != null && anchorPage != null) {
                        val remaining = if (lastVisibleIdx < 0) entries.size else (entries.lastIndex - lastVisibleIdx).coerceAtLeast(0)
                        currentOnStripProgress(
                            anchorChapter,
                            anchorPage,
                            byChapter.mapValues { it.value.toSet() },
                            listState.firstVisibleItemScrollOffset,
                            remaining,
                        )
                    }
                }
            }
    }

    DisposableEffect(listState) {
        onDispose {
            val entries = currentEntries
            val visibleInfo = listState.layoutInfo.visibleItemsInfo
            val byChapter = LinkedHashMap<String, MutableSet<Int>>()
            var anchorChapter: String? = null
            var anchorPage: Int? = null
            for (item in visibleInfo) {
                when (val entry = entries.getOrNull(item.index)) {
                    is StripFeedEntry.PrimaryPage -> {
                        if (anchorChapter == null) {
                            anchorChapter = entry.chapterId
                            anchorPage = entry.pageNumber
                        }
                        byChapter.getOrPut(entry.chapterId) { LinkedHashSet() }.add(entry.pageNumber)
                    }
                    is StripFeedEntry.AppendedPage -> {
                        if (anchorChapter == null) {
                            anchorChapter = entry.chapterId
                            anchorPage = entry.pageNumber
                        }
                        byChapter.getOrPut(entry.chapterId) { LinkedHashSet() }.add(entry.pageNumber)
                    }
                    else -> Unit
                }
            }
            if (anchorChapter != null && anchorPage != null) {
                currentOnStripProgress(
                    anchorChapter,
                    anchorPage,
                    byChapter.mapValues { it.value.toSet() },
                    listState.firstVisibleItemScrollOffset,
                    Int.MAX_VALUE,
                )
            }
        }
    }

    // Trigger end callback only when scrolled to the absolute end (non-serial queue or serial fallback).
    LaunchedEffect(listState, stripEntries) {
        snapshotFlow {
            val totalCount = listState.layoutInfo.totalItemsCount
            totalCount > 0 &&
                listState.isScrollInProgress &&
                !listState.canScrollForward &&
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= totalCount - 1
        }
            .distinctUntilChanged()
            .collectLatest { atEnd ->
                if (atEnd && !currentIsSerialComic) {
                    onEndReached()
                } else if (atEnd && currentIsSerialComic) {
                    onEndReached()
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
                        doubleTapAnimJob?.cancel()
                        val startZoom = zoom
                        val startPan = pan
                        val nextZoom = ReaderGeometry.toggleDoubleTapZoom(startZoom)
                        val targetPan = if (nextZoom <= ReaderGeometry.MIN_ZOOM) PanOffset.ZERO else startPan
                        if (motionReduced) {
                            zoom = nextZoom
                            pan = targetPan
                        } else {
                            doubleTapAnimJob = scope.launch {
                                val progress = Animatable(0f)
                                progress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = RavenMotion.interactiveSpringSpec(),
                                ) {
                                    val t = value
                                    zoom = startZoom + (nextZoom - startZoom) * t
                                    pan = PanOffset(
                                        x = startPan.x + (targetPan.x - startPan.x) * t,
                                        y = startPan.y + (targetPan.y - startPan.y) * t,
                                    )
                                }
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    doubleTapAnimJob?.cancel()
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
            userScrollEnabled = !isZoomed,
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
                items = stripEntries,
                key = { entry -> entry.key },
            ) { entry ->
                when (entry) {
                    is StripFeedEntry.PrimaryPage -> {
                        StripReaderPageItem(
                            pageNumber = entry.pageNumber,
                            loadedPage = uiState.bufferState.pages[entry.pageNumber],
                            errorMessage = uiState.bufferState.errors[entry.pageNumber],
                            preservedAspectRatio = uiState.pageAspectRatios[entry.pageNumber],
                            cropPercent = uiState.preferences.cropPercent,
                            onRetry = { onRetryPage(entry.chapterId, entry.pageNumber) },
                        )
                    }
                    is StripFeedEntry.ChapterDivider -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.canvas)
                                .padding(horizontal = 24.dp, vertical = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = colors.hairline,
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "下一话 · 共 ${entry.pageCount} 页",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.inkTertiary,
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = colors.hairline,
                            )
                        }
                    }
                    is StripFeedEntry.AppendedPage -> {
                        val chapter = appendedChapterMap[entry.chapterId]
                        StripReaderPageItem(
                            pageNumber = entry.pageNumber,
                            loadedPage = chapter?.bufferState?.pages?.get(entry.pageNumber),
                            errorMessage = chapter?.bufferState?.errors?.get(entry.pageNumber),
                            preservedAspectRatio = chapter?.pageAspectRatios?.get(entry.pageNumber),
                            cropPercent = uiState.preferences.cropPercent,
                            onRetry = { onRetryPage(entry.chapterId, entry.pageNumber) },
                        )
                    }
                }
            }
            if (uiState.isSerialComic) {
                item(key = "strip_serial_footer") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp, horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            uiState.isAppendingNextChapter -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = colors.crimson,
                                    )
                                    Text(
                                        text = "正在无感衔接下一话…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.inkSecondary,
                                    )
                                }
                            }
                            !uiState.appendNextChapterError.isNullOrBlank() -> {
                                OutlinedButton(
                                    onClick = onRetryAppendChapter,
                                    shape = RavenShapes.PillShape,
                                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                                ) {
                                    Text(
                                        text = "${uiState.appendNextChapterError} · 点此重试",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.inkPrimary,
                                    )
                                }
                            }
                            uiState.hasReachedFinalSerialChapter -> {
                                Text(
                                    text = "已阅读至最新一话",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.inkTertiary,
                                )
                            }
                        }
                    }
                }
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
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenChapters: () -> Unit,
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
                if (uiState.isSerialComic) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReaderChapterButton(
                            icon = Icons.Outlined.SkipPrevious,
                            label = "上一话",
                            subtitle = uiState.previousChapter?.title
                                ?: if (uiState.isChapterListReady) "已是第一话" else "加载或重试",
                            enabled = !uiState.isChapterTransitionLoading &&
                                (!uiState.isChapterListReady || uiState.previousChapter != null),
                            onClick = onPreviousChapter,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reader_previous_chapter"),
                        )
                        ReaderChapterButton(
                            icon = Icons.Outlined.List,
                            label = "选话",
                            subtitle = if (uiState.chapters.isNotEmpty()) {
                                "共 ${uiState.chapters.size} 话"
                            } else {
                                "查看全部章节"
                            },
                            enabled = !uiState.isChapterTransitionLoading,
                            onClick = onOpenChapters,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(ReaderTestTags.READER_CHAPTER_PICKER),
                        )
                        ReaderChapterButton(
                            icon = Icons.Outlined.SkipNext,
                            label = "下一话",
                            subtitle = uiState.nextChapter?.title
                                ?: if (uiState.isChapterListReady) "已是最后一话" else "加载或重试",
                            enabled = !uiState.isChapterTransitionLoading &&
                                (!uiState.isChapterListReady || uiState.nextChapter != null),
                            onClick = onNextChapter,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reader_next_chapter"),
                        )
                    }
                }

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
                    val bookmarkLabel = if (uiState.pageBookmarks.isNotEmpty()) "书签(${uiState.pageBookmarks.size})" else "书签"
                    ReaderQuickBarButton(
                        label = bookmarkLabel,
                        active = uiState.activeSheet == ReaderSheet.BOOKMARKS || uiState.isCurrentPageBookmarked,
                        onClick = onOpenBookmarks,
                        modifier = Modifier.weight(1f),
                    ) { tint ->
                        RavenMorphIcon(
                            glyph = if (uiState.pageBookmarks.isNotEmpty()) {
                                RavenMorphIcons.BookmarkFilled
                            } else {
                                RavenMorphIcons.BookmarkOutline
                            },
                            contentDescription = bookmarkLabel,
                            tint = tint,
                            size = 20.dp,
                            springPreset = MorphSpringPreset.SNAPPY,
                        )
                    }
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
private fun ReaderChapterButton(
    icon: ImageVector,
    label: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RavenShapes.ControlShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.inkPrimary,
            disabledContentColor = colors.inkTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    ReaderQuickBarButton(
        label = label,
        active = active,
        onClick = onClick,
        modifier = modifier,
    ) { tint ->
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ReaderQuickBarButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconContent: @Composable (tint: Color) -> Unit,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        targetValue = if (active) colors.crimson else colors.inkPrimary,
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 150),
        label = "readerQuickBtnTint",
    )
    val labelColor by animateColorAsState(
        targetValue = if (active) colors.crimson else colors.inkSecondary,
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 150),
        label = "readerQuickBtnLabel",
    )

    Column(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(RavenShapes.ControlShape)
            .ravenSpringPress(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    feedback.perform(RavenFeedbackEffect.TAP)
                    onClick()
                },
            )
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        iconContent(tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = labelColor,
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
