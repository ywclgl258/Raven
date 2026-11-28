package com.raven.app.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.media.reader.PanOffset
import com.raven.app.core.media.reader.ReaderGeometry
import com.raven.app.core.media.reader.SpreadNavigator
import com.raven.app.core.model.ReaderFit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 阅读器视口层：条漫 / 左翻 / 右翻 / 双页四种渲染视口与条漫条目模型。从 `ReaderScreen.kt` 拆出，包名与 `testTag` 契约不变。 */

internal sealed interface StripFeedEntry {
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

internal fun resolveStripIndex(entries: List<StripFeedEntry>, chapterId: String?, page: Int): Int {
    return entries.indexOfFirst { entry ->
        when (entry) {
            is StripFeedEntry.PrimaryPage -> (chapterId == null || entry.chapterId == chapterId) && entry.pageNumber == page
            is StripFeedEntry.AppendedPage -> (chapterId == null || entry.chapterId == chapterId) && entry.pageNumber == page
            else -> false
        }
    }
}

@Composable
internal fun StripReaderViewport(
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
internal fun PagedHorizontalViewport(
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
internal fun SpreadReaderViewport(
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

