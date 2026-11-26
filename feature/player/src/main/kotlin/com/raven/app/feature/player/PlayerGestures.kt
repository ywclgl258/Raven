package com.raven.app.feature.player

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenVisualHud
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Horizontal zone classification for double-tap playback gestures:
 * left 40% rewind, center 20% fullscreen toggle, right 40% forward.
 */
enum class PlayerTapZone {
    LEFT_REWIND,
    CENTER_FULLSCREEN,
    RIGHT_FORWARD,
}

fun classifyTapZone(xFraction: Float): PlayerTapZone {
    val clamped = if (xFraction.isFinite()) xFraction.coerceIn(0f, 1f) else 0.5f
    return when {
        clamped < 0.40f -> PlayerTapZone.LEFT_REWIND
        clamped > 0.60f -> PlayerTapZone.RIGHT_FORWARD
        else -> PlayerTapZone.CENTER_FULLSCREEN
    }
}

fun computeHorizontalSeekTargetMs(
    startPositionMs: Long,
    durationMs: Long,
    deltaX: Float,
    surfaceWidthPx: Float,
    sensitivitySeconds: Int,
): Long {
    if (durationMs <= 0L) return 0L
    if (!deltaX.isFinite() || !surfaceWidthPx.isFinite() || surfaceWidthPx <= 0f) {
        return startPositionMs.coerceIn(0L, durationMs)
    }
    val spanMs = sensitivitySeconds.coerceIn(15, 300) * 1_000L
    val deltaMs = ((deltaX / surfaceWidthPx) * spanMs).toLong()
    return (startPositionMs + deltaMs).coerceIn(0L, durationMs)
}

fun computeVerticalRatio(
    startRatio: Float,
    deltaY: Float,
    surfaceHeightPx: Float,
    minRatio: Float = 0f,
    maxRatio: Float = 1f,
): Float {
    val safeStart = if (startRatio.isFinite()) startRatio.coerceIn(minRatio, maxRatio) else minRatio
    if (!deltaY.isFinite() || !surfaceHeightPx.isFinite() || surfaceHeightPx <= 0f) {
        return safeStart
    }
    val travelPx = (surfaceHeightPx * 0.72f).coerceAtLeast(1f)
    // Dragging upward (negative deltaY) increases brightness/volume
    val delta = -deltaY / travelPx
    return (safeStart + delta).coerceIn(minRatio, maxRatio)
}

fun formatSignedSecondsDelta(deltaMs: Long): String {
    val seconds = (deltaMs / 1_000L).toInt()
    return when {
        seconds > 0 -> "+${seconds}s"
        seconds < 0 -> "${seconds}s"
        else -> "0s"
    }
}

@Immutable
sealed interface PlayerGestureHudState {
    data object Hidden : PlayerGestureHudState

    data class Seek(
        val targetMs: Long,
        val durationMs: Long,
        val deltaMs: Long,
    ) : PlayerGestureHudState

    data class Brightness(val fraction: Float) : PlayerGestureHudState

    data class Volume(val fraction: Float) : PlayerGestureHudState

    data class DoubleTapSkip(
        val forward: Boolean,
        val stepSeconds: Int,
    ) : PlayerGestureHudState
}

private enum class ActiveDragMode {
    NONE,
    HORIZONTAL_SEEK,
    LEFT_BRIGHTNESS,
    RIGHT_VOLUME,
}

/**
 * Full-surface Compose pointer gesture layer supporting:
 * - Single tap: toggle controls (or briefly reveal unlock pill when locked)
 * - Double tap: left 40% rewind / center 20% fullscreen / right 40% forward
 * - Long press: temporary playback speed while held, restoring cleanly on release or cancel
 * - Horizontal drag: scrubbed timeline seek + optional [LocalSeekPreviewDecoder] frame preview
 * - Vertical drag: left half brightness / right half media volume
 */
@Composable
internal fun PlayerGestureLayer(
    isLocked: Boolean,
    durationMs: Long,
    currentPositionMs: Long,
    sensitivitySeconds: Int,
    currentBrightnessProvider: () -> Float,
    currentVolumeRatioProvider: () -> Float,
    onSingleTap: () -> Unit,
    onLockedTap: () -> Unit,
    onDoubleTapZone: (PlayerTapZone) -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onSeekDragPreview: (targetMs: Long, deltaMs: Long) -> Unit,
    onSeekDragCommit: (targetMs: Long) -> Unit,
    onSeekDragCancel: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVerticalDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    currentPositionMsProvider: () -> Long = { currentPositionMs },
) {
    val scope = rememberCoroutineScope()
    val lockedState by rememberUpdatedState(isLocked)
    val durationState by rememberUpdatedState(durationMs)
    val positionProvider by rememberUpdatedState(currentPositionMsProvider)
    val sensitivityState by rememberUpdatedState(sensitivitySeconds)
    val brightnessProvider by rememberUpdatedState(currentBrightnessProvider)
    val volumeProvider by rememberUpdatedState(currentVolumeRatioProvider)
    val singleTapCb by rememberUpdatedState(onSingleTap)
    val lockedTapCb by rememberUpdatedState(onLockedTap)
    val doubleTapCb by rememberUpdatedState(onDoubleTapZone)
    val longPressStartCb by rememberUpdatedState(onLongPressStart)
    val longPressEndCb by rememberUpdatedState(onLongPressEnd)
    val seekPreviewCb by rememberUpdatedState(onSeekDragPreview)
    val seekCommitCb by rememberUpdatedState(onSeekDragCommit)
    val seekCancelCb by rememberUpdatedState(onSeekDragCancel)
    val brightnessCb by rememberUpdatedState(onBrightnessChange)
    val volumeCb by rememberUpdatedState(onVolumeChange)
    val verticalEndCb by rememberUpdatedState(onVerticalDragEnd)

    val tapMemory = remember {
        object {
            var lastTapTimeMs: Long = 0L
            var lastTapPos: Offset = Offset.Zero
            var pendingSingleTapJob: Job? = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(PlayerTestTags.PLAYER_GESTURE_LAYER)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) return@awaitEachGesture
                    val downPos = down.position
                    val downTime = down.uptimeMillis
                    val touchSlop = viewConfiguration.touchSlop
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    val heightPx = size.height.toFloat().coerceAtLeast(1f)

                    if (lockedState) {
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (hypot(change.position.x - downPos.x, change.position.y - downPos.y) > touchSlop) {
                                moved = true
                            }
                            if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                if (!moved) lockedTapCb()
                                break
                            }
                        }
                        return@awaitEachGesture
                    }

                    var dragMode = ActiveDragMode.NONE
                    var cumulativeDeltaX = 0f
                    var cumulativeDeltaY = 0f
                    var seekStartMs = positionProvider()
                    var lastSeekTargetMs = seekStartMs
                    var verticalStartRatio = 0.5f
                    var longPressTriggered = false
                    var pointerReleased = false
                    var gestureCancelled = false

                    // Phase 1: classify tap vs long-press vs horizontal/vertical drag
                    while (!pointerReleased && dragMode == ActiveDragMode.NONE && !longPressTriggered) {
                        val elapsed = (SystemClock.uptimeMillis() - downTime).coerceAtLeast(0L)
                        val remaining = longPressTimeout - elapsed
                        if (remaining <= 0L) {
                            tapMemory.pendingSingleTapJob?.cancel()
                            tapMemory.pendingSingleTapJob = null
                            tapMemory.lastTapTimeMs = 0L
                            longPressTriggered = true
                            break
                        }
                        val event = withTimeoutOrNull(remaining) {
                            awaitPointerEvent(PointerEventPass.Main)
                        }
                        if (event == null) {
                            tapMemory.pendingSingleTapJob?.cancel()
                            tapMemory.pendingSingleTapJob = null
                            tapMemory.lastTapTimeMs = 0L
                            longPressTriggered = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            gestureCancelled = true
                            break
                        }
                        if (change.isConsumed) {
                            gestureCancelled = true
                            break
                        }
                        if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                            pointerReleased = true
                            break
                        }

                        val dx = change.position.x - downPos.x
                        val dy = change.position.y - downPos.y
                        if (hypot(dx, dy) > touchSlop) {
                            tapMemory.pendingSingleTapJob?.cancel()
                            tapMemory.pendingSingleTapJob = null
                            tapMemory.lastTapTimeMs = 0L
                            dragMode = when {
                                abs(dx) >= abs(dy) -> {
                                    if (durationState > 0L) {
                                        seekStartMs = positionProvider()
                                        cumulativeDeltaX = dx
                                        lastSeekTargetMs = computeHorizontalSeekTargetMs(
                                            startPositionMs = seekStartMs,
                                            durationMs = durationState,
                                            deltaX = cumulativeDeltaX,
                                            surfaceWidthPx = widthPx,
                                            sensitivitySeconds = sensitivityState,
                                        )
                                        seekPreviewCb(lastSeekTargetMs, lastSeekTargetMs - seekStartMs)
                                        ActiveDragMode.HORIZONTAL_SEEK
                                    } else {
                                        ActiveDragMode.NONE
                                    }
                                }
                                downPos.x < widthPx * 0.5f -> {
                                    verticalStartRatio = brightnessProvider()
                                    cumulativeDeltaY = dy
                                    val next = computeVerticalRatio(
                                        startRatio = verticalStartRatio,
                                        deltaY = cumulativeDeltaY,
                                        surfaceHeightPx = heightPx,
                                        minRatio = 0.01f,
                                        maxRatio = 1f,
                                    )
                                    brightnessCb(next)
                                    ActiveDragMode.LEFT_BRIGHTNESS
                                }
                                else -> {
                                    verticalStartRatio = volumeProvider()
                                    cumulativeDeltaY = dy
                                    val next = computeVerticalRatio(
                                        startRatio = verticalStartRatio,
                                        deltaY = cumulativeDeltaY,
                                        surfaceHeightPx = heightPx,
                                        minRatio = 0f,
                                        maxRatio = 1f,
                                    )
                                    volumeCb(next)
                                    ActiveDragMode.RIGHT_VOLUME
                                }
                            }
                            if (dragMode != ActiveDragMode.NONE) {
                                change.consume()
                            } else {
                                gestureCancelled = true
                                break
                            }
                        }
                    }

                    // Phase 2A: Long press temporary playback speed (guaranteed restore in finally)
                    if (longPressTriggered) {
                        longPressStartCb()
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val anyPressed = event.changes.any { it.pressed }
                                event.changes.forEach { it.consume() }
                                if (!anyPressed) break
                            }
                        } finally {
                            longPressEndCb()
                        }
                        return@awaitEachGesture
                    }

                    // Phase 2B: Active drag tracking
                    if (dragMode != ActiveDragMode.NONE) {
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || change.isConsumed) {
                                    gestureCancelled = true
                                    break
                                }
                                if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                    change.consume()
                                    break
                                }
                                val step = change.positionChange()
                                change.consume()
                                when (dragMode) {
                                    ActiveDragMode.HORIZONTAL_SEEK -> {
                                        cumulativeDeltaX += step.x
                                        lastSeekTargetMs = computeHorizontalSeekTargetMs(
                                            startPositionMs = seekStartMs,
                                            durationMs = durationState,
                                            deltaX = cumulativeDeltaX,
                                            surfaceWidthPx = widthPx,
                                            sensitivitySeconds = sensitivityState,
                                        )
                                        seekPreviewCb(lastSeekTargetMs, lastSeekTargetMs - seekStartMs)
                                    }
                                    ActiveDragMode.LEFT_BRIGHTNESS -> {
                                        cumulativeDeltaY += step.y
                                        val next = computeVerticalRatio(
                                            startRatio = verticalStartRatio,
                                            deltaY = cumulativeDeltaY,
                                            surfaceHeightPx = heightPx,
                                            minRatio = 0.01f,
                                            maxRatio = 1f,
                                        )
                                        brightnessCb(next)
                                    }
                                    ActiveDragMode.RIGHT_VOLUME -> {
                                        cumulativeDeltaY += step.y
                                        val next = computeVerticalRatio(
                                            startRatio = verticalStartRatio,
                                            deltaY = cumulativeDeltaY,
                                            surfaceHeightPx = heightPx,
                                            minRatio = 0f,
                                            maxRatio = 1f,
                                        )
                                        volumeCb(next)
                                    }
                                    ActiveDragMode.NONE -> Unit
                                }
                            }
                        } finally {
                            when (dragMode) {
                                ActiveDragMode.HORIZONTAL_SEEK -> {
                                    if (gestureCancelled) {
                                        seekCancelCb()
                                    } else {
                                        seekCommitCb(lastSeekTargetMs)
                                    }
                                }
                                ActiveDragMode.LEFT_BRIGHTNESS,
                                ActiveDragMode.RIGHT_VOLUME,
                                -> verticalEndCb()
                                ActiveDragMode.NONE -> Unit
                            }
                        }
                        return@awaitEachGesture
                    }

                    // Phase 2C: Single tap vs double tap
                    if (pointerReleased && !gestureCancelled) {
                        val now = SystemClock.uptimeMillis()
                        val isDoubleTap = tapMemory.lastTapTimeMs > 0L &&
                            (now - tapMemory.lastTapTimeMs) <= doubleTapTimeout &&
                            hypot(
                                downPos.x - tapMemory.lastTapPos.x,
                                downPos.y - tapMemory.lastTapPos.y,
                            ) <= touchSlop * 6f

                        if (isDoubleTap) {
                            tapMemory.pendingSingleTapJob?.cancel()
                            tapMemory.pendingSingleTapJob = null
                            tapMemory.lastTapTimeMs = 0L
                            doubleTapCb(classifyTapZone(downPos.x / widthPx))
                        } else {
                            tapMemory.lastTapTimeMs = now
                            tapMemory.lastTapPos = downPos
                            tapMemory.pendingSingleTapJob?.cancel()
                            tapMemory.pendingSingleTapJob = scope.launch {
                                delay(doubleTapTimeout)
                                tapMemory.lastTapTimeMs = 0L
                                singleTapCb()
                            }
                        }
                    }
                }
            },
    )
}

/**
 * Centered visual gesture HUD built on [RavenVisualHud] plus an optional `160x90` seek-frame
 * thumbnail decoded via `LocalSeekPreviewDecoder` for local and complete offline MP4 files.
 */
@Composable
internal fun PlayerGestureHudOverlay(
    hudState: PlayerGestureHudState,
    previewFrame: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    var lastActiveHud by remember { mutableStateOf<PlayerGestureHudState>(PlayerGestureHudState.Hidden) }
    if (hudState !is PlayerGestureHudState.Hidden) {
        lastActiveHud = hudState
    }
    val visible = hudState !is PlayerGestureHudState.Hidden
    val displayedHud = if (visible) hudState else lastActiveHud

    val doubleTapSkip = hudState as? PlayerGestureHudState.DoubleTapSkip
    val rippleProgress = remember { Animatable(0f) }
    LaunchedEffect(doubleTapSkip) {
        if (doubleTapSkip != null && !motionReduced) {
            rippleProgress.snapTo(0f)
            rippleProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 480, easing = RavenMotion.HarmonyDecelerationEasing),
            )
        } else {
            rippleProgress.snapTo(0f)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (doubleTapSkip != null && !motionReduced) {
            val isForward = doubleTapSkip.forward
            val glowColor = colors.crimson
            Canvas(modifier = Modifier.fillMaxSize()) {
                val p = rippleProgress.value
                if (p > 0f && p < 1f) {
                    val alpha = (1f - p) * 0.24f
                    val centerX = if (isForward) size.width * 0.92f else size.width * 0.08f
                    val centerY = size.height * 0.5f
                    val radius = size.minDimension * (0.28f + 0.52f * p)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                glowColor.copy(alpha = alpha),
                                glowColor.copy(alpha = alpha * 0.35f),
                                Color.Transparent,
                            ),
                            center = Offset(centerX, centerY),
                            radius = radius.coerceAtLeast(1f),
                        ),
                        radius = radius.coerceAtLeast(1f),
                        center = Offset(centerX, centerY),
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = hudState is PlayerGestureHudState.Seek && previewFrame != null,
                enter = if (motionReduced) EnterTransition.None else fadeIn(tween(120, easing = RavenMotion.HarmonyDecelerationEasing)),
                exit = if (motionReduced) ExitTransition.None else fadeOut(tween(150, easing = RavenMotion.HarmonyDecelerationEasing)),
            ) {
                val frame = previewFrame
                val seekState = hudState as? PlayerGestureHudState.Seek
                if (frame != null && seekState != null) {
                    SeekPreviewThumbnailCard(
                        bitmap = frame,
                        timestampLabel = RavenFormatters.formatDurationMs(seekState.targetMs),
                    )
                }
            }

            when (val active = displayedHud) {
                PlayerGestureHudState.Hidden -> Unit
                is PlayerGestureHudState.Seek -> {
                    val progress = if (active.durationMs > 0L) {
                        (active.targetMs.toFloat() / active.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val icon = if (active.deltaMs >= 0L) {
                        Icons.Rounded.FastForward
                    } else {
                        Icons.Rounded.FastRewind
                    }
                    val label = "${RavenFormatters.formatDurationMs(active.targetMs)} / " +
                        "${RavenFormatters.formatDurationMs(active.durationMs)} " +
                        "(${formatSignedSecondsDelta(active.deltaMs)})"
                    RavenVisualHud(
                        visible = visible,
                        icon = icon,
                        label = label,
                        progress = progress,
                        accentColor = colors.crimson,
                    )
                }
                is PlayerGestureHudState.Brightness -> {
                    val percent = (active.fraction.coerceIn(0f, 1f) * 100f).roundToInt()
                    val icon = when {
                        percent <= 30 -> Icons.Rounded.BrightnessLow
                        percent <= 70 -> Icons.Rounded.BrightnessMedium
                        else -> Icons.Rounded.BrightnessHigh
                    }
                    RavenVisualHud(
                        visible = visible,
                        icon = icon,
                        label = "$percent%",
                        progress = active.fraction,
                        accentColor = colors.inkPrimary,
                    )
                }
                is PlayerGestureHudState.Volume -> {
                    val percent = (active.fraction.coerceIn(0f, 1f) * 100f).roundToInt()
                    val icon = when {
                        percent == 0 -> Icons.AutoMirrored.Rounded.VolumeOff
                        percent <= 45 -> Icons.AutoMirrored.Rounded.VolumeDown
                        else -> Icons.AutoMirrored.Rounded.VolumeUp
                    }
                    RavenVisualHud(
                        visible = visible,
                        icon = icon,
                        label = "$percent%",
                        progress = active.fraction,
                        accentColor = colors.inkPrimary,
                    )
                }
                is PlayerGestureHudState.DoubleTapSkip -> {
                    val icon = if (active.forward) {
                        Icons.Rounded.FastForward
                    } else {
                        Icons.Rounded.FastRewind
                    }
                    val prefix = if (active.forward) "+" else "-"
                    RavenVisualHud(
                        visible = visible,
                        icon = icon,
                        label = "${prefix}${active.stepSeconds}s",
                        progress = null,
                        accentColor = colors.crimson,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SeekPreviewThumbnailCard(
    bitmap: Bitmap,
    timestampLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Box(
        modifier = modifier
            .width(160.dp)
            .height(90.dp)
            .clip(RavenShapes.ControlShape)
            .background(colors.canvas)
            .border(1.dp, colors.rimHighlight, RavenShapes.ControlShape),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 5.dp)
                .clip(RavenShapes.BadgeShape)
                .background(colors.badgeBackdrop)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = timestampLabel,
                style = RavenTabularTextStyle.copy(fontWeight = FontWeight.SemiBold),
                color = colors.inkPrimary,
                maxLines = 1,
            )
        }
    }
}
