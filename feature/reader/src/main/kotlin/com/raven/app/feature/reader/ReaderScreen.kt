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
                            parent = event.parent ?: parent,
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
