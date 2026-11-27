package com.raven.app.feature.player

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.designsystem.component.RavenBloubLoadingIndicator
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.ravenSquashPress
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFrostedBox
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.media.playback.VideoLoopRange
import com.raven.app.core.model.TimeBookmark
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * Full player overlay stack: frosted top/bottom control chrome, A-B marked scrubber, lock mode
 * unlock pill, long-press temporary speed pill, notice toast, buffering indicator, playback error
 * card, and end-of-video sequential queue confirmation card.
 */
@Composable
internal fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    controlsVisible: Boolean,
    unlockPillVisible: Boolean,
    scrubbingPreviewFrame: Bitmap?,
    onBackClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubPositionChange: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenQualitySheet: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleLock: () -> Unit,
    onRequestPip: () -> Unit,
    onOpenSleepTimerSheet: () -> Unit,
    onOpenSettingsSheet: () -> Unit,
    onRetry: () -> Unit,
    onOpenSiteAccount: () -> Unit,
    onOpenExternalPlayers: () -> Unit,
    onConfirmQueueNext: () -> Unit,
    onEndQueue: () -> Unit,
    onOpenQueue: () -> Unit,
    onCancelAutoContinue: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
    progressStateFlow: StateFlow<PlayerProgressSnapshot>? = null,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val motionReduced = LocalMotionReduced.current
    val supportsPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Lock mode: hide all controls, expose only subtle left "解锁画面" pill
        if (uiState.isLocked) {
            AnimatedVisibility(
                visible = unlockPillVisible,
                enter = if (motionReduced) EnterTransition.None else fadeIn(tween(160, easing = RavenMotion.HarmonyDecelerationEasing)),
                exit = if (motionReduced) ExitTransition.None else fadeOut(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
            ) {
                RavenFrostedBox(
                    shape = RavenShapes.PillShape,
                    reduceTransparency = reduceTransparency,
                    borderColor = colors.rimHighlight,
                    modifier = Modifier
                        .ravenSquashPress()
                        .clickable(role = Role.Button, onClick = onToggleLock)
                        .testTag(PlayerTestTags.PLAYER_UNLOCK_PILL),
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RavenMorphIcon(
                            glyph = if (uiState.isLocked) RavenMorphIcons.LockClosed else RavenMorphIcons.LockOpen,
                            contentDescription = "解锁画面",
                            tint = colors.crimson,
                            size = 18.dp,
                            springPreset = MorphSpringPreset.SNAPPY,
                        )
                        Text(
                            text = "解锁画面",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.inkPrimary,
                        )
                    }
                }
            }
            return@Box
        }

        val activeError = (uiState.loadError ?: uiState.playbackError).takeUnless { uiState.isRefreshingExpiredStream }
        val waitingLabel = when {
            uiState.isRefreshingExpiredStream -> "正在刷新播放地址…"
            activeError != null -> null
            uiState.isLoadingDetail -> "正在加载视频…"
            uiState.isBuffering -> "正在缓冲…"
            uiState.isInitialized && !uiState.isVideoFrameReady -> "正在恢复画面…"
            else -> null
        }

        val showChrome = controlsVisible && !uiState.showQueueNextOverlay

        // 2a. Top frosted header bar (slides cleanly from top edge)
        AnimatedVisibility(
            visible = showChrome,
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                fadeIn(tween(160, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    slideInVertically(tween(200, easing = RavenMotion.HarmonyDecelerationEasing)) { -it }
            },
            exit = if (motionReduced) {
                ExitTransition.None
            } else {
                fadeOut(tween(140, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    slideOutVertically(tween(170, easing = RavenMotion.HarmonyDecelerationEasing)) { -it }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerTopBar(
                uiState = uiState,
                supportsPip = supportsPip,
                onBackClick = onBackClick,
                onOpenQualitySheet = onOpenQualitySheet,
                onOpenSleepTimerSheet = onOpenSleepTimerSheet,
                onToggleLock = onToggleLock,
                onRequestPip = onRequestPip,
                onOpenSettingsSheet = onOpenSettingsSheet,
            )
        }

        // 2b. Center Play / Pause / Replay medallion
        AnimatedVisibility(
            visible = showChrome && waitingLabel == null && activeError == null,
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                fadeIn(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    scaleIn(initialScale = 0.88f, animationSpec = tween(180, easing = RavenMotion.HarmonyDecelerationEasing))
            },
            exit = if (motionReduced) {
                ExitTransition.None
            } else {
                fadeOut(tween(130, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    scaleOut(targetScale = 0.88f, animationSpec = tween(150, easing = RavenMotion.HarmonyDecelerationEasing))
            },
            modifier = Modifier.align(Alignment.Center),
        ) {
            RavenFrostedBox(
                shape = CircleShape,
                reduceTransparency = reduceTransparency,
                borderColor = colors.rimHighlight,
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(58.dp)
                    .ravenSquashPress()
                    .clickable(role = Role.Button, onClick = onTogglePlayPause),
            ) {
                RavenMorphIcon(
                    glyph = when {
                        uiState.isEnded -> RavenMorphIcons.Replay
                        uiState.isPlaying -> RavenMorphIcons.Pause
                        else -> RavenMorphIcons.Play
                    },
                    contentDescription = if (uiState.isPlaying && !uiState.isEnded) "暂停" else "播放",
                    tint = colors.inkPrimary,
                    size = 30.dp,
                    strokeWidth = 2.2.dp,
                    springPreset = MorphSpringPreset.SNAPPY,
                )
            }
        }

        // 2c. Bottom scrim + scrubber (slides cleanly from bottom edge; collects high-freq progress ONLY when visible!)
        AnimatedVisibility(
            visible = showChrome,
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
                    slideOutVertically(tween(170, easing = RavenMotion.HarmonyDecelerationEasing)) { it }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val liveProgress = if (progressStateFlow != null) {
                progressStateFlow.collectAsStateWithLifecycle().value
            } else {
                null
            }
            val barState = if (liveProgress != null) {
                uiState.copy(
                    positionMs = liveProgress.positionMs,
                    bufferedPositionMs = liveProgress.bufferedPositionMs,
                    durationMs = maxOf(uiState.durationMs, liveProgress.durationMs),
                )
            } else {
                uiState
            }
            PlayerBottomBar(
                uiState = barState,
                previewFrame = scrubbingPreviewFrame,
                onTogglePlayPause = onTogglePlayPause,
                onScrubStart = onScrubStart,
                onScrubPositionChange = onScrubPositionChange,
                onScrubCommit = onScrubCommit,
                onOpenSpeedSheet = onOpenSpeedSheet,
                onOpenQualitySheet = onOpenQualitySheet,
                onToggleFullscreen = onToggleFullscreen,
            )
        }

        // 3. Long-press temporary playback speed pill (always visible above controls while held)
        AnimatedVisibility(
            visible = uiState.isTemporarySpeedActive,
            enter = if (motionReduced) {
                EnterTransition.None
            } else {
                fadeIn(tween(120, easing = RavenMotion.HarmonyDecelerationEasing)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(150))
            },
            exit = if (motionReduced) {
                EnterTransition.None.let { ExitTransition.None }
            } else {
                fadeOut(tween(120, easing = RavenMotion.HarmonyDecelerationEasing))
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (uiState.isFullscreen) 24.dp else 14.dp),
        ) {
            RavenFrostedBox(
                shape = RavenShapes.PillShape,
                reduceTransparency = reduceTransparency,
                borderColor = colors.rimHighlight,
                modifier = Modifier.testTag(PlayerTestTags.PLAYER_TEMP_SPEED_PILL),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FastForward,
                        contentDescription = null,
                        tint = colors.crimson,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "倍速播放中 ${formatSpeedLabel(uiState.temporarySpeedValue)}",
                        style = RavenTabularTextStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.inkPrimary,
                    )
                }
            }
        }

        // 4. Transient notice banner (stream refresh, resume, loop)
        AnimatedVisibility(
            visible = !uiState.noticeMessage.isNullOrBlank() && !uiState.isTemporarySpeedActive,
            enter = if (motionReduced) EnterTransition.None else fadeIn(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)),
            exit = if (motionReduced) ExitTransition.None else fadeOut(tween(140, easing = RavenMotion.HarmonyDecelerationEasing)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (controlsVisible) 58.dp else 14.dp, start = 20.dp, end = 20.dp),
        ) {
            val notice = uiState.noticeMessage.orEmpty()
            RavenFrostedBox(
                shape = RavenShapes.PillShape,
                reduceTransparency = reduceTransparency,
                borderColor = colors.hairline,
                modifier = Modifier
                    .clickable(onClick = onDismissNotice)
                    .testTag(PlayerTestTags.PLAYER_NOTICE_BANNER),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 5. Buffering or loading spinner
        AnimatedVisibility(
            visible = waitingLabel != null,
            enter = if (motionReduced) EnterTransition.None else fadeIn(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)),
            exit = if (motionReduced) ExitTransition.None else fadeOut(tween(120, easing = RavenMotion.HarmonyDecelerationEasing)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RavenBloubLoadingIndicator(
                    size = 36.dp,
                    accentColor = colors.crimson,
                )
                waitingLabel?.let { label ->
                    Text(text = label, style = MaterialTheme.typography.labelMedium, color = colors.inkPrimary)
                }
            }
        }

        // 6. Error overlay inside video viewport
        AnimatedVisibility(
            visible = activeError != null,
            enter = if (motionReduced) EnterTransition.None else fadeIn(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)),
            exit = if (motionReduced) ExitTransition.None else fadeOut(tween(140, easing = RavenMotion.HarmonyDecelerationEasing)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            if (activeError != null) {
                PlayerSurfaceErrorOverlay(
                    message = activeError,
                    requiresAuth = uiState.requiresAuth,
                    hasExternalPlayers = uiState.externalPlayers.isNotEmpty() || uiState.url.startsWith("http"),
                    onRetry = onRetry,
                    onOpenSiteAccount = onOpenSiteAccount,
                    onOpenExternalPlayers = onOpenExternalPlayers,
                )
            }
        }

        // 7. End-of-video Sequential Queue Next confirmation overlay
        val queue = uiState.queue
        AnimatedVisibility(
            visible = uiState.showQueueNextOverlay && queue?.nextItem != null,
            enter = if (motionReduced) EnterTransition.None else fadeIn(tween(180, easing = RavenMotion.HarmonyDecelerationEasing)) + scaleIn(initialScale = 0.95f),
            exit = if (motionReduced) ExitTransition.None else fadeOut(tween(140, easing = RavenMotion.HarmonyDecelerationEasing)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            if (queue?.nextItem != null) {
                PlayerQueueEndedOverlay(
                    queue = queue,
                    onConfirmNext = onConfirmQueueNext,
                    onEndQueue = onEndQueue,
                    onOpenQueue = onOpenQueue,
                    onCancelAutoContinue = onCancelAutoContinue,
                )
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    uiState: PlayerUiState,
    supportsPip: Boolean,
    onBackClick: () -> Unit,
    onOpenQualitySheet: () -> Unit,
    onOpenSleepTimerSheet: () -> Unit,
    onToggleLock: () -> Unit,
    onRequestPip: () -> Unit,
    onOpenSettingsSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colors.canvas.copy(alpha = 0.84f),
                        colors.canvas.copy(alpha = 0.36f),
                        Color.Transparent,
                    ),
                ),
            )
            .then(if (uiState.isFullscreen) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier.ravenSquashPress(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = colors.inkPrimary,
            )
        }

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = uiState.title.ifBlank { "视频播放" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            PlayerMiniControlPill(
                text = uiState.activeQualityLabel,
                highlighted = uiState.isOffline || uiState.isLocal,
                enabled = uiState.canChooseQuality,
                onClick = onOpenQualitySheet,
                modifier = Modifier.testTag(PlayerTestTags.PLAYER_QUALITY_PILL),
            )
        }

        if (uiState.sleepTimer.isActive) {
            PlayerMiniControlPill(
                text = uiState.sleepTimer.formattedRemaining,
                highlighted = true,
                leadingIcon = Icons.Rounded.Bedtime,
                onClick = onOpenSleepTimerSheet,
                modifier = Modifier.testTag(PlayerTestTags.PLAYER_SLEEP_INDICATOR),
            )
        }

        IconButton(
            onClick = onToggleLock,
            modifier = Modifier
                .ravenSquashPress()
                .testTag(PlayerTestTags.PLAYER_LOCK_BUTTON),
        ) {
            RavenMorphIcon(
                glyph = if (uiState.isLocked) RavenMorphIcons.LockClosed else RavenMorphIcons.LockOpen,
                contentDescription = "锁屏",
                tint = colors.inkPrimary,
                size = 20.dp,
                springPreset = MorphSpringPreset.SNAPPY,
            )
        }

        if (supportsPip) {
            IconButton(
                onClick = onRequestPip,
                modifier = Modifier
                    .ravenSquashPress()
                    .testTag(PlayerTestTags.PLAYER_PIP_BUTTON),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PictureInPictureAlt,
                    contentDescription = "画中画",
                    tint = colors.inkPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        IconButton(
            onClick = onOpenSettingsSheet,
            modifier = Modifier
                .ravenSquashPress()
                .testTag(PlayerTestTags.PLAYER_MORE_BUTTON),
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "播放设置",
                tint = colors.inkPrimary,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun PlayerBottomBar(
    uiState: PlayerUiState,
    previewFrame: Bitmap?,
    onTogglePlayPause: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubPositionChange: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenQualitySheet: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val durationMs = uiState.effectiveDurationMs
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }

    val displayedPositionMs = if (isScrubbing) scrubPositionMs else uiState.positionMs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        colors.canvas.copy(alpha = 0.45f),
                        colors.canvas.copy(alpha = 0.88f),
                    ),
                ),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AnimatedVisibility(
            visible = isScrubbing && previewFrame != null,
            enter = if (motionReduced) EnterTransition.None else fadeIn(tween(120, easing = RavenMotion.HarmonyDecelerationEasing)),
            exit = if (motionReduced) ExitTransition.None else fadeOut(tween(120, easing = RavenMotion.HarmonyDecelerationEasing)),
        ) {
            val frame = previewFrame
            if (frame != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SeekPreviewThumbnailCard(
                        bitmap = frame,
                        timestampLabel = RavenFormatters.formatDurationMs(scrubPositionMs),
                    )
                }
            }
        }

        PlayerAbScrubber(
            positionMs = displayedPositionMs,
            bufferedPositionMs = uiState.bufferedPositionMs,
            durationMs = durationMs,
            loopRange = uiState.loopRange,
            isScrubbing = isScrubbing,
            onScrubStart = { targetMs ->
                isScrubbing = true
                scrubPositionMs = targetMs
                onScrubStart()
                onScrubPositionChange(targetMs)
            },
            onScrubMove = { targetMs ->
                scrubPositionMs = targetMs
                onScrubPositionChange(targetMs)
            },
            onScrubEnd = { targetMs ->
                isScrubbing = false
                onScrubCommit(targetMs)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            timeBookmarks = uiState.timeBookmarks,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .ravenSquashPress()
                    .testTag(PlayerTestTags.PLAYER_PLAY_PAUSE),
            ) {
                RavenMorphIcon(
                    glyph = when {
                        uiState.isEnded -> RavenMorphIcons.Replay
                        uiState.isPlaying -> RavenMorphIcons.Pause
                        else -> RavenMorphIcons.Play
                    },
                    contentDescription = if (uiState.isPlaying && !uiState.isEnded) "暂停" else "播放",
                    tint = colors.inkPrimary,
                    size = 24.dp,
                    springPreset = MorphSpringPreset.SNAPPY,
                )
            }

            val timeText = "${RavenFormatters.formatDurationMs(displayedPositionMs)} / " +
                if (durationMs > 0L) RavenFormatters.formatDurationMs(durationMs) else "--:--"
            Text(
                text = timeText,
                style = RavenTabularTextStyle.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.inkPrimary,
                maxLines = 1,
                modifier = Modifier.testTag(PlayerTestTags.PLAYER_TIME_LABEL),
            )

            if (uiState.loopRange.hasStart) {
                Spacer(modifier = Modifier.width(4.dp))
                PlayerMiniControlPill(
                    text = if (uiState.loopRange.isActive) "A-B" else "A",
                    highlighted = true,
                    onClick = {},
                    enabled = false,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            PlayerMiniControlPill(
                text = uiState.activeSpeedLabel,
                highlighted = uiState.sessionUserSpeed != null || uiState.effectivePlaybackSpeed != 1.0f,
                onClick = onOpenSpeedSheet,
                modifier = Modifier.testTag(PlayerTestTags.PLAYER_SPEED_PILL),
            )

            Spacer(modifier = Modifier.width(4.dp))

            PlayerMiniControlPill(
                text = uiState.activeQualityLabel,
                highlighted = false,
                enabled = uiState.canChooseQuality,
                onClick = onOpenQualitySheet,
                modifier = Modifier.testTag(PlayerTestTags.PLAYER_BOTTOM_QUALITY_PILL),
            )

            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier
                    .ravenSquashPress()
                    .testTag(PlayerTestTags.PLAYER_FULLSCREEN_BUTTON),
            ) {
                RavenMorphIcon(
                    glyph = if (uiState.isFullscreen) {
                        RavenMorphIcons.FullscreenExit
                    } else {
                        RavenMorphIcons.FullscreenEnter
                    },
                    contentDescription = if (uiState.isFullscreen) "退出全屏" else "全屏",
                    tint = colors.inkPrimary,
                    size = 24.dp,
                    springPreset = MorphSpringPreset.SNAPPY,
                )
            }
        }
    }
}

/**
 * Custom cinema scrubber rendering buffered track, Raven Crimson played progress, active thumb,
 * and A-B loop range span + A/B markers.
 */
@Composable
internal fun PlayerAbScrubber(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    loopRange: VideoLoopRange,
    onScrubStart: (Long) -> Unit,
    onScrubMove: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    modifier: Modifier = Modifier,
    timeBookmarks: List<TimeBookmark> = emptyList(),
    isScrubbing: Boolean = false,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val durationState by rememberUpdatedState(durationMs)
    val startCb by rememberUpdatedState(onScrubStart)
    val moveCb by rememberUpdatedState(onScrubMove)
    val endCb by rememberUpdatedState(onScrubEnd)
    val scrubScaleState = animateFloatAsState(
        targetValue = if (isScrubbing) 1f else 0f,
        animationSpec = if (motionReduced) snap() else RavenMotion.interactiveSpringSpec(),
        label = "playerScrubberExpand",
    )

    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val playedFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedFraction = if (durationMs > 0L) {
        (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .testTag(PlayerTestTags.PLAYER_SCRUBBER)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val dur = durationState
                    if (dur <= 0L) return@detectTapGestures
                    val frac = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                    val target = (dur * frac).toLong()
                    startCb(target)
                    endCb(target)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val dur = durationState
                        if (dur <= 0L) return@detectHorizontalDragGestures
                        dragFraction = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                        startCb((dur * dragFraction).toLong())
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        val dur = durationState
                        if (dur <= 0L) return@detectHorizontalDragGestures
                        change.consume()
                        dragFraction = (dragFraction + dragAmount / trackWidthPx).coerceIn(0f, 1f)
                        moveCb((dur * dragFraction).toLong())
                    },
                    onDragEnd = {
                        val dur = durationState
                        if (dur > 0L) endCb((dur * dragFraction).toLong())
                    },
                    onDragCancel = {
                        val dur = durationState
                        if (dur > 0L) endCb((dur * dragFraction).toLong())
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        ) {
            val expandT = scrubScaleState.value.coerceIn(0f, 1.3f)
            val trackHeight = (3.5.dp + 2.dp * expandT).toPx()
            val thumbRadius = (6.dp + 2.5.dp * expandT).toPx()
            val centerY = size.height / 2f
            val topY = centerY - trackHeight / 2f
            val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)

            // Base track
            drawRoundRect(
                color = colors.surface3.copy(alpha = 0.85f),
                topLeft = Offset(0f, topY),
                size = Size(size.width, trackHeight),
                cornerRadius = corner,
            )

            // Buffered track
            if (bufferedFraction > 0f) {
                drawRoundRect(
                    color = colors.inkSecondary.copy(alpha = 0.40f),
                    topLeft = Offset(0f, topY),
                    size = Size(size.width * bufferedFraction, trackHeight),
                    cornerRadius = corner,
                )
            }

            // Played progress track (Raven Crimson)
            if (playedFraction > 0f) {
                drawRoundRect(
                    color = colors.crimson,
                    topLeft = Offset(0f, topY),
                    size = Size(size.width * playedFraction, trackHeight),
                    cornerRadius = corner,
                )
            }

            // A-B loop range & markers
            if (durationMs > 0L && loopRange.hasStart) {
                val startFrac = (loopRange.startMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val startX = size.width * startFrac
                if (loopRange.isActive) {
                    val endFrac = (loopRange.endMs.toFloat() / durationMs.toFloat()).coerceIn(startFrac, 1f)
                    val endX = size.width * endFrac
                    drawRoundRect(
                        color = colors.warning.copy(alpha = 0.85f),
                        topLeft = Offset(startX, topY - 1.dp.toPx()),
                        size = Size((endX - startX).coerceAtLeast(2.dp.toPx()), trackHeight + 2.dp.toPx()),
                        cornerRadius = corner,
                    )
                    // B marker pin
                    drawLine(
                        color = colors.warning,
                        start = Offset(endX, centerY - 7.dp.toPx()),
                        end = Offset(endX, centerY + 7.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                // A marker pin
                drawLine(
                    color = colors.warning,
                    start = Offset(startX, centerY - 7.dp.toPx()),
                    end = Offset(startX, centerY + 7.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            // TimeBookmark tick markers
            if (durationMs > 0L && timeBookmarks.isNotEmpty()) {
                val tickHalf = 4.5.dp.toPx()
                val tickWidth = 2.dp.toPx()
                for (bm in timeBookmarks) {
                    if (bm.positionMs in 0L..durationMs) {
                        val bmX = size.width * (bm.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        drawLine(
                            color = colors.inkPrimary.copy(alpha = 0.85f),
                            start = Offset(bmX, centerY - tickHalf),
                            end = Offset(bmX, centerY + tickHalf),
                            strokeWidth = tickWidth,
                        )
                    }
                }
            }

            // Scrubber thumb
            val thumbX = (size.width * playedFraction).coerceIn(thumbRadius, (size.width - thumbRadius).coerceAtLeast(thumbRadius))
            drawCircle(
                color = colors.inkPrimary,
                radius = thumbRadius,
                center = Offset(thumbX, centerY),
            )
            drawCircle(
                color = colors.crimson,
                radius = (thumbRadius * 0.42f).coerceAtLeast(2.5.dp.toPx()),
                center = Offset(thumbX, centerY),
            )
        }
    }
}

@Composable
private fun PlayerMiniControlPill(
    text: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val colors = LocalRavenColors.current
    val bg = if (highlighted) colors.crimson.copy(alpha = 0.22f) else colors.badgeBackdrop
    val border = if (highlighted) colors.crimson.copy(alpha = 0.55f) else colors.rimHighlight
    val ink = if (highlighted) colors.crimson else colors.inkPrimary

    Row(
        modifier = modifier
            .clip(RavenShapes.PillShape)
            .background(bg)
            .border(0.5.dp, border, RavenShapes.PillShape)
            .then(
                if (enabled) {
                    Modifier
                        .ravenSpringPress()
                        .clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = text,
            style = RavenTabularTextStyle.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlayerSurfaceErrorOverlay(
    message: String,
    requiresAuth: Boolean,
    hasExternalPlayers: Boolean,
    onRetry: () -> Unit,
    onOpenSiteAccount: () -> Unit,
    onOpenExternalPlayers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current

    RavenFrostedBox(
        shape = RavenShapes.CardShape,
        reduceTransparency = reduceTransparency,
        borderColor = colors.rimHighlight,
        modifier = modifier.padding(horizontal = 24.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = if (requiresAuth) onOpenSiteAccount else onRetry,
                    shape = RavenShapes.PillShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                ) {
                    if (!requiresAuth) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = if (requiresAuth) "前往登录" else "重试",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                if (hasExternalPlayers) {
                    OutlinedButton(
                        onClick = onOpenExternalPlayers,
                        shape = RavenShapes.PillShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.surface2,
                            contentColor = colors.inkPrimary,
                        ),
                    ) {
                        Text(
                            text = "站外播放",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerQueueEndedOverlay(
    queue: PlayerQueueUiState,
    onConfirmNext: () -> Unit,
    onEndQueue: () -> Unit,
    onOpenQueue: () -> Unit,
    onCancelAutoContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val nextItem = queue.nextItem ?: return

    RavenFrostedBox(
        shape = RavenShapes.CardShape,
        reduceTransparency = reduceTransparency,
        borderColor = colors.rimHighlight,
        modifier = modifier.padding(horizontal = 24.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "队列下一项 (${queue.currentIndex + 2} / ${queue.totalCount})",
                style = RavenTabularTextStyle.copy(fontWeight = FontWeight.SemiBold),
                color = colors.crimson,
            )
            Text(
                text = nextItem.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onConfirmNext,
                    shape = RavenShapes.PillShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (queue.autoContinueCountdownActive) {
                            "立即播放 (${queue.autoContinueRemainingSeconds}s)"
                        } else {
                            "立即播放"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                OutlinedButton(
                    onClick = if (queue.autoContinueCountdownActive) onCancelAutoContinue else onEndQueue,
                    shape = RavenShapes.PillShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface2,
                        contentColor = colors.inkSecondary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (queue.autoContinueCountdownActive) "取消连播" else "结束队列",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            OutlinedButton(
                onClick = onOpenQueue,
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface2,
                    contentColor = colors.inkPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "播放队列 · ${queue.currentIndex + 1}/${queue.totalCount} · 剩余 ${queue.remainingCount} 项",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
