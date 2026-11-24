package com.raven.app.feature.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CONTROLS_AUTO_HIDE_MS: Long = 4_500L
private const val UNLOCK_PILL_AUTO_HIDE_MS: Long = 3_000L
private const val NOTICE_AUTO_DISMISS_MS: Long = 3_500L
private const val DOUBLE_TAP_HUD_MS: Long = 800L

/**
 * Full-featured Dark Obsidian × Apple HIG × Netflix Cinema video player screen.
 *
 * - Strictly binds active progress/A-B loop/sleep-timer/stall polling to
 *   `repeatOnLifecycle(Lifecycle.State.STARTED)` (zero background idle polling).
 * - Adapts seamlessly between portrait, wide-screen split (`>= 840dp`), fullscreen, lock mode,
 *   and PiP mode (`isInPipMode == true` hides all overlays & detail pane cleanly).
 * - Switches related videos and sequential queue items IN-PLACE without stacking Activities.
 */
@Composable
fun VideoPlayerScreen(
    viewModel: PlayerViewModel,
    navigator: PlayerNavigator,
    modifier: Modifier = Modifier,
    isInPipMode: Boolean = false,
    isFullscreen: Boolean? = null,
) {
    val colors = LocalRavenColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val seekPreviewFrame by viewModel.seekPreviewFrame.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var controlsVisible by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var gestureHudState by remember { mutableStateOf<PlayerGestureHudState>(PlayerGestureHudState.Hidden) }
    var doubleTapHudJob by remember { mutableStateOf<Job?>(null) }
    var unlockPillVisible by remember { mutableStateOf(true) }
    var unlockPillTick by remember { mutableIntStateOf(0) }

    val audioManager = remember(context) {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var currentBrightness by remember(context) {
        mutableFloatStateOf(readSystemBrightnessFraction(context))
    }

    val currentPipState by rememberUpdatedState(isInPipMode)
    val effectiveFullscreen = isFullscreen ?: uiState.isFullscreen

    // Keep ViewModel fullscreen state synchronized when host controls it explicitly
    LaunchedEffect(isFullscreen) {
        if (isFullscreen != null) {
            viewModel.setFullscreenState(isFullscreen)
        }
    }

    // Close sheets & transient HUD when entering Picture-in-Picture mode
    LaunchedEffect(isInPipMode) {
        if (isInPipMode) {
            activeSheet = null
            controlsVisible = false
            gestureHudState = PlayerGestureHudState.Hidden
            viewModel.stopTemporarySpeed()
            viewModel.clearSeekPreviewFrame()
        }
    }

    // Lifecycle-aware foreground polling (ZERO background idle polling!)
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.runForegroundTicker()
        }
    }

    // Flush watch progress & pause when backgrounded outside PiP
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onBackgrounded(isInPipMode = currentPipState)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Collect one-shot ViewModel side effects
    LaunchedEffect(viewModel, navigator) {
        viewModel.sideEffects.collect { effect ->
            when (effect) {
                is PlayerSideEffect.ShowMessage -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        duration = SnackbarDuration.Short,
                    )
                }
                is PlayerSideEffect.RequestAutoFullscreen -> {
                    if (!currentPipState) {
                        viewModel.setFullscreenState(true)
                        navigator.requestFullscreen(landscape = effect.landscape)
                    }
                }
                is PlayerSideEffect.OpenGalleryFromQueue -> {
                    navigator.openGallery(effect.item.siteId, effect.item.id, effect.item.url)
                }
                is PlayerSideEffect.ActiveMediaChanged -> {
                    navigator.onActiveMediaChanged(effect.args)
                }
            }
        }
    }

    // Auto-hide controls after 4.5s while playing
    LaunchedEffect(controlsVisible, uiState.isPlaying, activeSheet, isScrubbing, isInPipMode, uiState.isLocked) {
        if (controlsVisible &&
            uiState.isPlaying &&
            activeSheet == null &&
            !isScrubbing &&
            !isInPipMode &&
            !uiState.isLocked
        ) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Auto-fade lock unlock pill after 3s (tapping surface reveals it again)
    LaunchedEffect(uiState.isLocked, unlockPillTick) {
        if (uiState.isLocked) {
            unlockPillVisible = true
            delay(UNLOCK_PILL_AUTO_HIDE_MS)
            unlockPillVisible = false
        } else {
            unlockPillVisible = true
        }
    }

    // Auto-dismiss transient surface notices (stall downgrade / refresh / resume / loop)
    LaunchedEffect(uiState.noticeMessage) {
        if (!uiState.noticeMessage.isNullOrBlank()) {
            delay(NOTICE_AUTO_DISMISS_MS)
            viewModel.clearNotice()
        }
    }

    val enterFullscreenAction = {
        val landscape = if (uiState.videoWidth > 0 && uiState.videoHeight > 0) {
            uiState.videoWidth >= uiState.videoHeight
        } else {
            true
        }
        viewModel.setFullscreenState(true)
        navigator.requestFullscreen(landscape = landscape)
    }

    val exitFullscreenAction = {
        viewModel.setFullscreenState(false)
        navigator.exitFullscreen()
    }

    val toggleFullscreenAction = {
        if (effectiveFullscreen) exitFullscreenAction() else enterFullscreenAction()
    }

    val backAction: () -> Unit = {
        when {
            uiState.isLocked -> viewModel.setLocked(false)
            effectiveFullscreen -> exitFullscreenAction()
            else -> {
                scope.launch {
                    runCatching { viewModel.flushWatchProgress() }
                    navigator.onClose()
                }
            }
        }
    }

    // Back-key unlocks screen in Lock Mode, exits fullscreen when fullscreen, or closes player cleanly
    BackHandler(enabled = !isInPipMode) {
        backAction()
    }

    // Observe active ExoPlayer instance (re-evaluated whenever initialization/stream changes)
    val exoPlayer: ExoPlayer? = remember(
        uiState.isInitialized,
        uiState.currentStream,
        uiState.siteId,
        uiState.mediaId,
    ) {
        viewModel.currentPlayer()
    }

    // PiP mode: render ONLY the crisp Media3 video surface with zero overlays or detail pane
    if (isInPipMode) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.canvas)
                .testTag(PlayerTestTags.PLAYER_SCREEN),
        ) {
            Media3PlayerSurface(
                player = exoPlayer,
                keepScreenOn = uiState.isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    val playerViewportContent: @Composable (Modifier) -> Unit = { viewportModifier ->
        Box(
            modifier = viewportModifier
                .background(colors.canvas),
        ) {
            Media3PlayerSurface(
                player = exoPlayer,
                keepScreenOn = uiState.isPlaying,
                modifier = Modifier.fillMaxSize(),
            )

            PlayerGestureLayer(
                isLocked = uiState.isLocked,
                durationMs = uiState.effectiveDurationMs,
                currentPositionMs = uiState.positionMs,
                sensitivitySeconds = uiState.playbackPreferences.gestureSensitivitySeconds,
                currentBrightnessProvider = { currentBrightness },
                currentVolumeRatioProvider = { readMediaVolumeRatio(audioManager) },
                onSingleTap = { controlsVisible = !controlsVisible },
                onLockedTap = {
                    unlockPillVisible = true
                    unlockPillTick += 1
                },
                onDoubleTapZone = { zone ->
                    when (zone) {
                        PlayerTapZone.LEFT_REWIND -> {
                            viewModel.seekByConfiguredStep(forward = false)
                            doubleTapHudJob?.cancel()
                            gestureHudState = PlayerGestureHudState.DoubleTapSkip(
                                forward = false,
                                stepSeconds = uiState.playbackPreferences.seekStepSeconds,
                            )
                            doubleTapHudJob = scope.launch {
                                delay(DOUBLE_TAP_HUD_MS)
                                if (gestureHudState is PlayerGestureHudState.DoubleTapSkip) {
                                    gestureHudState = PlayerGestureHudState.Hidden
                                }
                            }
                        }
                        PlayerTapZone.RIGHT_FORWARD -> {
                            viewModel.seekByConfiguredStep(forward = true)
                            doubleTapHudJob?.cancel()
                            gestureHudState = PlayerGestureHudState.DoubleTapSkip(
                                forward = true,
                                stepSeconds = uiState.playbackPreferences.seekStepSeconds,
                            )
                            doubleTapHudJob = scope.launch {
                                delay(DOUBLE_TAP_HUD_MS)
                                if (gestureHudState is PlayerGestureHudState.DoubleTapSkip) {
                                    gestureHudState = PlayerGestureHudState.Hidden
                                }
                            }
                        }
                        PlayerTapZone.CENTER_FULLSCREEN -> toggleFullscreenAction()
                    }
                },
                onLongPressStart = { viewModel.startTemporarySpeed() },
                onLongPressEnd = { viewModel.stopTemporarySpeed() },
                onSeekDragPreview = { targetMs, deltaMs ->
                    doubleTapHudJob?.cancel()
                    gestureHudState = PlayerGestureHudState.Seek(
                        targetMs = targetMs,
                        durationMs = uiState.effectiveDurationMs,
                        deltaMs = deltaMs,
                    )
                    viewModel.requestSeekPreviewFrame(targetMs)
                },
                onSeekDragCommit = { targetMs ->
                    gestureHudState = PlayerGestureHudState.Hidden
                    viewModel.clearSeekPreviewFrame()
                    viewModel.seekTo(targetMs)
                },
                onSeekDragCancel = {
                    gestureHudState = PlayerGestureHudState.Hidden
                    viewModel.clearSeekPreviewFrame()
                },
                onBrightnessChange = { fraction ->
                    doubleTapHudJob?.cancel()
                    currentBrightness = fraction
                    navigator.setWindowBrightness(fraction)
                    gestureHudState = PlayerGestureHudState.Brightness(fraction)
                },
                onVolumeChange = { fraction ->
                    doubleTapHudJob?.cancel()
                    applyMediaVolumeRatio(audioManager, fraction)
                    gestureHudState = PlayerGestureHudState.Volume(fraction)
                },
                onVerticalDragEnd = {
                    gestureHudState = PlayerGestureHudState.Hidden
                },
            )

            PlayerControlsOverlay(
                uiState = uiState.copy(isFullscreen = effectiveFullscreen),
                controlsVisible = controlsVisible,
                unlockPillVisible = unlockPillVisible,
                scrubbingPreviewFrame = if (isScrubbing) seekPreviewFrame else null,
                onBackClick = backAction,
                onTogglePlayPause = {
                    viewModel.togglePlayPause()
                    controlsVisible = true
                },
                onScrubStart = {
                    isScrubbing = true
                    controlsVisible = true
                },
                onScrubPositionChange = { posMs ->
                    viewModel.requestSeekPreviewFrame(posMs)
                },
                onScrubCommit = { posMs ->
                    isScrubbing = false
                    viewModel.clearSeekPreviewFrame()
                    viewModel.seekTo(posMs)
                },
                onOpenSpeedSheet = { activeSheet = PlayerSheet.Speed },
                onOpenQualitySheet = {
                    if (uiState.canChooseQuality) {
                        activeSheet = PlayerSheet.Quality
                    }
                },
                onToggleFullscreen = toggleFullscreenAction,
                onToggleLock = {
                    viewModel.toggleLock()
                    if (!uiState.isLocked) {
                        controlsVisible = false
                        unlockPillTick += 1
                    } else {
                        controlsVisible = true
                    }
                },
                onRequestPip = {
                    navigator.requestPip(viewModel.pipAspectRatioPair())
                },
                onOpenSleepTimerSheet = { activeSheet = PlayerSheet.SleepTimer },
                onOpenSettingsSheet = { activeSheet = PlayerSheet.Settings },
                onRetry = { viewModel.retryLoad() },
                onOpenSiteAccount = {
                    uiState.siteId?.let(navigator::openSiteAccount)
                },
                onOpenExternalPlayers = { activeSheet = PlayerSheet.ExternalPlayers },
                onConfirmQueueNext = { viewModel.confirmAdvanceQueue() },
                onEndQueue = { viewModel.endQueue() },
                onDismissNotice = { viewModel.clearNotice() },
            )

            PlayerGestureHudOverlay(
                hudState = gestureHudState,
                previewFrame = if (gestureHudState is PlayerGestureHudState.Seek) seekPreviewFrame else null,
            )
        }
    }

    val detailPaneContent: @Composable (Boolean, Modifier) -> Unit = { compactRelated, paneModifier ->
        PlayerDetailPane(
            uiState = uiState,
            compactRelatedList = compactRelated,
            onToggleWatchLater = { viewModel.toggleWatchLater() },
            onToggleFavorite = { viewModel.toggleFavorite() },
            onOpenDownloadSheet = { activeSheet = PlayerSheet.Download },
            onOpenAddBookmark = {
                activeSheet = PlayerSheet.BookmarkEditor(initialPositionMs = uiState.positionMs)
            },
            onShareClick = {
                shareVideoText(context, uiState.title, uiState.url)
            },
            onOpenExternalPlayers = { activeSheet = PlayerSheet.ExternalPlayers },
            onConfirmQueueNext = { viewModel.confirmAdvanceQueue() },
            onEndQueue = { viewModel.endQueue() },
            onSeekTo = { positionMs -> viewModel.seekTo(positionMs) },
            onAuthorClick = { author ->
                uiState.siteId?.let { siteId -> navigator.openAuthorFeed(siteId, author) }
            },
            onTagClick = { tag ->
                uiState.siteId?.let { siteId -> navigator.openTagFeed(siteId, tag) }
            },
            onTagLongClick = { tagDisplay ->
                activeSheet = PlayerSheet.TagActions(tagDisplay)
            },
            onJumpBookmark = { bookmark -> viewModel.jumpToTimeBookmark(bookmark) },
            onEditBookmark = { bookmark ->
                activeSheet = PlayerSheet.BookmarkEditor(editingBookmark = bookmark)
            },
            onDeleteBookmark = { bookmark -> viewModel.deleteTimeBookmark(bookmark.id) },
            onRelatedClick = { item ->
                if (item.kind.isVideo) {
                    viewModel.selectRelatedVideo(item)
                } else {
                    navigator.openGallery(item.siteId, item.id, item.url)
                }
            },
            modifier = paneModifier,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag(PlayerTestTags.PLAYER_SCREEN),
    ) {
        if (effectiveFullscreen) {
            playerViewportContent(Modifier.fillMaxSize())
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWideLayout = maxWidth >= 840.dp
                if (isWideLayout) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1.35f)
                                .fillMaxHeight()
                                .background(colors.canvas),
                            contentAlignment = Alignment.Center,
                        ) {
                            playerViewportContent(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(RavenFormatters.VIDEO_ASPECT_RATIO),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .background(colors.hairline),
                        )
                        detailPaneContent(
                            true,
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                    ) {
                        playerViewportContent(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(RavenFormatters.VIDEO_ASPECT_RATIO),
                        )
                        detailPaneContent(
                            false,
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                }
            }
        }

        // Raven Obsidian SnackbarHost
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RavenShapes.CardShape,
                containerColor = colors.surface2,
                contentColor = colors.inkPrimary,
                actionColor = colors.crimson,
            )
        }

        // Single active Apple HIG grouped modal bottom sheet
        PlayerSheetHost(
            activeSheet = activeSheet,
            uiState = uiState,
            onDismiss = { activeSheet = null },
            onSwitchSheet = { next -> activeSheet = next },
            onSelectSpeed = { speed -> viewModel.setSessionSpeed(speed) },
            onResetSpeedToDefault = { viewModel.resetSessionSpeedToDefault() },
            onSelectQuality = { stream, saveDefault -> viewModel.selectStream(stream, saveDefault) },
            onSetLoopA = { viewModel.setLoopA() },
            onSetLoopB = { viewModel.setLoopB() },
            onJumpToLoopA = { viewModel.jumpToLoopStart() },
            onClearLoop = { viewModel.clearLoop() },
            onSetSleepTimer = { minutes -> viewModel.setSleepTimerMinutes(minutes) },
            onCancelSleepTimer = { viewModel.cancelSleepTimer() },
            onLockScreen = {
                viewModel.setLocked(true)
                controlsVisible = false
                unlockPillTick += 1
            },
            onRequestPip = { navigator.requestPip(viewModel.pipAspectRatioPair()) },
            onSaveBookmark = { editing, positionMs, label, groupName ->
                if (editing == null) {
                    viewModel.addTimeBookmark(label = label, groupName = groupName, positionMs = positionMs)
                } else {
                    viewModel.updateTimeBookmark(bookmark = editing, label = label, groupName = groupName)
                }
            },
            onOpenTagFeed = { tag ->
                uiState.siteId?.let { siteId -> navigator.openTagFeed(siteId, tag) }
            },
            onToggleTagFavorite = { tag -> viewModel.toggleTagFavorite(tag) },
            onToggleTagBlocked = { tag -> viewModel.toggleTagBlocked(tag) },
            onEnqueueDownload = { stream, groupName -> viewModel.enqueueDownload(stream, groupName) },
            onOpenExternalUrl = { url -> navigator.openExternalUrl(url) },
            onUpdateDefaultSpeed = { speed -> viewModel.updateDefaultSpeedPreference(speed) },
            onUpdateLongPressSpeed = { speed -> viewModel.updateLongPressSpeedPreference(speed) },
            onUpdateSeekStepSeconds = { sec -> viewModel.updateSeekStepSecondsPreference(sec) },
            onUpdateGestureSensitivitySeconds = { sec -> viewModel.updateGestureSensitivitySecondsPreference(sec) },
            onUpdateAutoFullscreen = { enabled -> viewModel.updateAutoFullscreenPreference(enabled) },
            onResetSitePreferences = { viewModel.resetSitePlaybackPreferences() },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Media3PlayerSurface(
    player: ExoPlayer?,
    keepScreenOn: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                this.player = player
                this.keepScreenOn = keepScreenOn
            }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
            }
            if (view.keepScreenOn != keepScreenOn) {
                view.keepScreenOn = keepScreenOn
            }
        },
        onRelease = { view ->
            view.player = null
            view.keepScreenOn = false
        },
        modifier = modifier.testTag(PlayerTestTags.PLAYER_SURFACE),
    )
}

private fun readSystemBrightnessFraction(context: Context): Float = runCatching {
    val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    (raw / 255f).coerceIn(0.05f, 1f)
}.getOrDefault(0.5f)

private fun readMediaVolumeRatio(audioManager: AudioManager?): Float {
    if (audioManager == null) return 0.5f
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
    return current.toFloat() / max.toFloat()
}

private fun applyMediaVolumeRatio(audioManager: AudioManager?, fraction: Float) {
    if (audioManager == null) return
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val target = (fraction.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
    runCatching {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }
}

private fun shareVideoText(context: Context, title: String, url: String) {
    val body = buildString {
        if (title.isNotBlank()) {
            append(title)
            if (url.isNotBlank()) append('\n')
        }
        append(url)
    }.trim()
    if (body.isEmpty()) return
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching {
        context.startActivity(Intent.createChooser(sendIntent, "分享视频"))
    }
}
