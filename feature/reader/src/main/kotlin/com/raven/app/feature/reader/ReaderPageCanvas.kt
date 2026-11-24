package com.raven.app.feature.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.reader.PanOffset
import com.raven.app.core.media.reader.ReaderGeometry
import com.raven.app.core.model.ReaderFit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val STRIP_MIN_ASPECT_RATIO = 0.20f
private const val STRIP_MAX_ASPECT_RATIO = 3.0f
private const val DEFAULT_PAGE_ASPECT_RATIO = 0.72f

/**
 * Interactive single-page viewport supporting:
 * - Single tap -> toggle chrome & immersive system bars
 * - Double tap -> `1x / 2x` zoom toggle via [ReaderGeometry.toggleDoubleTapZoom]
 * - Pinch zoom (`1f..5f`) + strictly clamped pan via [ReaderGeometry.computePanLimit] / [ReaderGeometry.clampPan]
 * - Manual crop (`0..20%`) and [ReaderFit] (`WIDTH`, `HEIGHT`, `ORIGINAL`)
 * - Automatic zoom/pan reset when [pageKey] changes
 * - Clean gesture coexistence with parent `HorizontalPager` when `zoom == 1f`.
 */
@Composable
internal fun PagedReaderCanvas(
    pageKey: Any,
    pageNumber: Int,
    loadedPage: LoadedReaderPage?,
    errorMessage: String?,
    fit: ReaderFit,
    cropPercent: Int,
    onSingleTap: () -> Unit,
    onRetry: () -> Unit,
    onZoomedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    var zoom by remember(pageKey) { mutableFloatStateOf(ReaderGeometry.MIN_ZOOM) }
    var pan by remember(pageKey) { mutableStateOf(PanOffset.ZERO) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val currentFit by rememberUpdatedState(fit)
    val currentCrop by rememberUpdatedState(cropPercent)
    val currentLoaded by rememberUpdatedState(loadedPage)
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnZoomedChanged by rememberUpdatedState(onZoomedChanged)

    // Always notify parent when leaving a page or resetting zoom.
    LaunchedEffect(pageKey) {
        zoom = ReaderGeometry.MIN_ZOOM
        pan = PanOffset.ZERO
        currentOnZoomedChanged(false)
    }

    LaunchedEffect(zoom) {
        currentOnZoomedChanged(zoom > ReaderGeometry.MIN_ZOOM + 0.01f)
    }

    // Re-clamp pan if fit, crop, or viewport dimensions change while zoomed.
    LaunchedEffect(fit, cropPercent, viewportSize, loadedPage) {
        val loaded = loadedPage
        if (loaded != null && viewportSize.width > 0 && viewportSize.height > 0) {
            val limit = ReaderGeometry.computePanLimit(
                bitmapWidth = loaded.pixelWidth,
                bitmapHeight = loaded.pixelHeight,
                containerWidth = viewportSize.width,
                containerHeight = viewportSize.height,
                fit = fit,
                cropPercent = cropPercent,
                zoom = zoom,
            )
            pan = ReaderGeometry.clampPan(pan, limit)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(pageKey) {
                detectTapGestures(
                    onTap = { currentOnSingleTap() },
                    onDoubleTap = {
                        val nextZoom = ReaderGeometry.toggleDoubleTapZoom(zoom)
                        zoom = nextZoom
                        if (nextZoom <= ReaderGeometry.MIN_ZOOM) {
                            pan = PanOffset.ZERO
                        } else {
                            val loaded = currentLoaded
                            if (loaded != null && viewportSize.width > 0 && viewportSize.height > 0) {
                                val limit = ReaderGeometry.computePanLimit(
                                    bitmapWidth = loaded.pixelWidth,
                                    bitmapHeight = loaded.pixelHeight,
                                    containerWidth = viewportSize.width,
                                    containerHeight = viewportSize.height,
                                    fit = currentFit,
                                    cropPercent = currentCrop,
                                    zoom = nextZoom,
                                )
                                pan = ReaderGeometry.clampPan(pan, limit)
                            }
                        }
                    },
                )
            }
            .pointerInput(pageKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var gestureZooming = false
                    val touchSlop = viewConfiguration.touchSlop
                    var accumZoom = 1f
                    var accumPanX = 0f
                    var accumPanY = 0f

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val activePointers = event.changes.count { it.pressed }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!gestureZooming) {
                                accumZoom *= zoomChange
                                accumPanX += panChange.x
                                accumPanY += panChange.y
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1f - accumZoom) * centroidSize
                                val panMotion = kotlin.math.hypot(accumPanX, accumPanY)
                                if (activePointers >= 2 && (zoomMotion > touchSlop || abs(1f - accumZoom) > 0.03f)) {
                                    gestureZooming = true
                                } else if (zoom > ReaderGeometry.MIN_ZOOM + 0.01f && panMotion > touchSlop) {
                                    gestureZooming = true
                                }
                            }

                            if (gestureZooming) {
                                val nextZoom = ReaderGeometry.clampZoom(zoom * zoomChange)
                                val loaded = currentLoaded
                                val nextPan = if (nextZoom <= ReaderGeometry.MIN_ZOOM + 0.005f) {
                                    PanOffset.ZERO
                                } else if (loaded != null && viewportSize.width > 0 && viewportSize.height > 0) {
                                    val limit = ReaderGeometry.computePanLimit(
                                        bitmapWidth = loaded.pixelWidth,
                                        bitmapHeight = loaded.pixelHeight,
                                        containerWidth = viewportSize.width,
                                        containerHeight = viewportSize.height,
                                        fit = currentFit,
                                        cropPercent = currentCrop,
                                        zoom = nextZoom,
                                    )
                                    ReaderGeometry.clampPan(
                                        PanOffset(pan.x + panChange.x, pan.y + panChange.y),
                                        limit,
                                    )
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
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadedPage?.bitmap != null -> {
                val bmp = loadedPage.bitmap
                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                val cropFactor = 1f + ReaderGeometry.clampCropPercent(cropPercent) / 100f
                val contentScale = when (fit) {
                    ReaderFit.WIDTH -> ContentScale.FillWidth
                    ReaderFit.HEIGHT -> ContentScale.FillHeight
                    ReaderFit.ORIGINAL -> ContentScale.None
                }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "第 $pageNumber 页",
                    contentScale = contentScale,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val totalScale = zoom * cropFactor
                            scaleX = totalScale
                            scaleY = totalScale
                            translationX = pan.x
                            translationY = pan.y
                        },
                )
            }
            errorMessage != null -> {
                ReaderPageErrorCard(
                    pageNumber = pageNumber,
                    message = errorMessage,
                    onRetry = onRetry,
                )
            }
            else -> {
                ReaderPageLoadingIndicator(pageNumber = pageNumber)
            }
        }
    }
}

/**
 * Landscape dual-page spread sheet rendering 1 or 2 pages side-by-side inside a unified
 * zoomable/pannable surface.
 */
@Composable
internal fun SpreadSheetCanvas(
    sheetKey: Any,
    sheetPages: List<Int>,
    pagesMap: Map<Int, LoadedReaderPage>,
    errorsMap: Map<Int, String>,
    cropPercent: Int,
    onSingleTap: () -> Unit,
    onRetryPage: (Int) -> Unit,
    onZoomedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sheetPages.size == 1) {
        val pageNum = sheetPages.first()
        PagedReaderCanvas(
            pageKey = sheetKey,
            pageNumber = pageNum,
            loadedPage = pagesMap[pageNum],
            errorMessage = errorsMap[pageNum],
            fit = ReaderFit.HEIGHT,
            cropPercent = cropPercent,
            onSingleTap = onSingleTap,
            onRetry = { onRetryPage(pageNum) },
            onZoomedChanged = onZoomedChanged,
            modifier = modifier,
        )
        return
    }

    val colors = LocalRavenColors.current
    var zoom by remember(sheetKey) { mutableFloatStateOf(ReaderGeometry.MIN_ZOOM) }
    var pan by remember(sheetKey) { mutableStateOf(PanOffset.ZERO) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val currentCrop by rememberUpdatedState(cropPercent)
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnZoomedChanged by rememberUpdatedState(onZoomedChanged)

    LaunchedEffect(sheetKey) {
        zoom = ReaderGeometry.MIN_ZOOM
        pan = PanOffset.ZERO
        currentOnZoomedChanged(false)
    }

    LaunchedEffect(zoom) {
        currentOnZoomedChanged(zoom > ReaderGeometry.MIN_ZOOM + 0.01f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(sheetKey) {
                detectTapGestures(
                    onTap = { currentOnSingleTap() },
                    onDoubleTap = {
                        val nextZoom = ReaderGeometry.toggleDoubleTapZoom(zoom)
                        zoom = nextZoom
                        if (nextZoom <= ReaderGeometry.MIN_ZOOM) pan = PanOffset.ZERO
                    },
                )
            }
            .pointerInput(sheetKey) {
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
                                        fit = ReaderFit.HEIGHT,
                                        cropPercent = currentCrop,
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
        contentAlignment = Alignment.Center,
    ) {
        val cropFactor = 1f + ReaderGeometry.clampCropPercent(cropPercent) / 100f
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val totalScale = zoom * cropFactor
                    scaleX = totalScale
                    scaleY = totalScale
                    translationX = pan.x
                    translationY = pan.y
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sheetPages.forEach { pageNum ->
                val loaded = pagesMap[pageNum]
                val err = errorsMap[pageNum]
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loaded?.bitmap != null -> {
                            val bmp = loaded.bitmap
                            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "第 $pageNum 页",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        err != null -> {
                            ReaderPageErrorCard(
                                pageNumber = pageNum,
                                message = err,
                                onRetry = { onRetryPage(pageNum) },
                            )
                        }
                        else -> {
                            ReaderPageLoadingIndicator(pageNumber = pageNum)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Vertical continuous strip page item preserving per-page aspect ratios from [ReaderViewModel]
 * so scrolling across already-measured pages never causes placeholder height jumping, with inline
 * per-page retry button on decode/network error.
 */
@Composable
internal fun StripReaderPageItem(
    pageNumber: Int,
    loadedPage: LoadedReaderPage?,
    errorMessage: String?,
    preservedAspectRatio: Float?,
    cropPercent: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val resolvedRatio = RavenFormatters.clampAspectRatio(
        aspectRatio = loadedPage?.aspectRatio ?: preservedAspectRatio,
        defaultRatio = DEFAULT_PAGE_ASPECT_RATIO,
        minRatio = STRIP_MIN_ASPECT_RATIO,
        maxRatio = STRIP_MAX_ASPECT_RATIO,
    )
    val cropFactor = 1f + ReaderGeometry.clampCropPercent(cropPercent) / 100f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(resolvedRatio)
            .background(colors.canvas)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadedPage?.bitmap != null -> {
                val bmp = loadedPage.bitmap
                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "第 $pageNumber 页",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = cropFactor
                            scaleY = cropFactor
                        },
                )
            }
            errorMessage != null -> {
                ReaderPageErrorCard(
                    pageNumber = pageNumber,
                    message = errorMessage,
                    onRetry = onRetry,
                )
            }
            else -> {
                ReaderPageLoadingIndicator(pageNumber = pageNumber)
            }
        }
    }
}

@Composable
private fun ReaderPageLoadingIndicator(pageNumber: Int) {
    val colors = LocalRavenColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
            color = colors.crimson,
            trackColor = colors.surface3,
        )
        Text(
            text = "P$pageNumber",
            style = RavenTabularTextStyle,
            color = colors.inkTertiary,
        )
    }
}

@Composable
private fun ReaderPageErrorCard(
    pageNumber: Int,
    message: String,
    onRetry: () -> Unit,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = colors.error,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = "第 $pageNumber 页加载失败",
            style = MaterialTheme.typography.titleSmall.tabular(),
            color = colors.inkPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
        Button(
            onClick = onRetry,
            shape = RavenShapes.PillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primaryCtaContainer,
                contentColor = colors.primaryCtaInk,
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            modifier = Modifier
                .heightIn(min = 44.dp)
                .ravenSpringPress(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/**
 * Compact corner status HUD rendered when immersive reader chrome is hidden (`!chromeVisible`).
 * Shows live local clock (`HH:mm`), battery percentage, tabular `page / total`, progress %,
 * and an optional active auto-page badge.
 */
@Composable
internal fun ReaderStatusBar(
    currentPage: Int,
    totalPages: Int,
    progressPercent: Int,
    autoPageIntervalSeconds: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var clockText by remember { mutableStateOf(formatCurrentTimeHm()) }
    var batteryPercent by remember { mutableIntStateOf(readBatteryPercent(context)) }

    // Update wall-clock time only while STARTED (zero background battery drain).
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                clockText = formatCurrentTimeHm()
                val nowMs = System.currentTimeMillis()
                val delayToNextMinute = (60_000L - (nowMs % 60_000L)).coerceIn(1_000L, 60_000L)
                delay(delayToNextMinute)
            }
        }
    }

    // Observe battery percentage cleanly via ACTION_BATTERY_CHANGED sticky broadcast.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent != null) {
                    val pct = extractBatteryPercent(intent)
                    if (pct >= 0) batteryPercent = pct
                }
            }
        }
        val sticky = runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        sticky?.let {
            val pct = extractBatteryPercent(it)
            if (pct >= 0) batteryPercent = pct
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RavenShapes.PillShape)
            .background(colors.badgeBackdrop)
            .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(ReaderTestTags.READER_STATUS_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = RavenFormatters.formatPageProgress(currentPage, totalPages),
            style = RavenTabularTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = colors.inkPrimary,
        )
        Text(
            text = "$progressPercent%",
            style = RavenTabularTextStyle.copy(fontSize = 11.sp),
            color = colors.inkSecondary,
        )
        if (autoPageIntervalSeconds > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoMode,
                    contentDescription = "自动翻页中",
                    tint = colors.crimson,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "${autoPageIntervalSeconds}s",
                    style = RavenTabularTextStyle.copy(fontSize = 11.sp),
                    color = colors.crimson,
                )
            }
        }
        Text(
            text = clockText,
            style = RavenTabularTextStyle.copy(fontSize = 11.sp),
            color = colors.inkSecondary,
        )
        if (batteryPercent >= 0) {
            Text(
                text = "$batteryPercent%",
                style = RavenTabularTextStyle.copy(fontSize = 11.sp),
                color = if (batteryPercent <= 15) colors.warning else colors.inkSecondary,
            )
        }
    }
}

private fun formatCurrentTimeHm(): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.ROOT)
    return formatter.format(Date())
}

private fun readBatteryPercent(context: Context): Int = runCatching {
    val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    sticky?.let(::extractBatteryPercent) ?: -1
}.getOrDefault(-1)

private fun extractBatteryPercent(intent: Intent): Int {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return -1
    return ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
}
