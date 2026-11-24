package com.raven.app.core.media.reader

import com.raven.app.core.model.ReaderFit

/**
 * Maximum symmetric pan translation `(maxX, maxY)` allowed for a zoomed/cropped page inside its viewport.
 */
data class PanLimit(
    val maxX: Float,
    val maxY: Float,
) {
    companion object {
        val ZERO: PanLimit = PanLimit(0f, 0f)
    }
}

/**
 * 2D pan offset in pixels.
 */
data class PanOffset(
    val x: Float,
    val y: Float,
) {
    companion object {
        val ZERO: PanOffset = PanOffset(0f, 0f)
    }
}

/**
 * Pure-Kotlin reader viewport geometry, pan bounds, and spread-pair navigation math.
 */
object ReaderGeometry {
    const val MIN_ZOOM: Float = 1.0f
    const val MAX_ZOOM: Float = 5.0f
    const val DOUBLE_TAP_ZOOM: Float = 2.0f
    const val MIN_CROP_PERCENT: Int = 0
    const val MAX_CROP_PERCENT: Int = 20

    fun clampZoom(zoom: Float): Float =
        if (zoom.isNaN()) MIN_ZOOM else zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun clampCropPercent(cropPercent: Int): Int =
        cropPercent.coerceIn(MIN_CROP_PERCENT, MAX_CROP_PERCENT)

    fun toggleDoubleTapZoom(currentZoom: Float): Float =
        if (currentZoom > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM

    fun baseFitScale(
        bitmapWidth: Int,
        bitmapHeight: Int,
        containerWidth: Int,
        containerHeight: Int,
        fit: ReaderFit,
    ): Float {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            return 0f
        }
        return when (fit) {
            ReaderFit.WIDTH -> containerWidth.toFloat() / bitmapWidth.toFloat()
            ReaderFit.HEIGHT -> containerHeight.toFloat() / bitmapHeight.toFloat()
            ReaderFit.ORIGINAL -> 1.0f
        }
    }

    fun effectiveScale(
        bitmapWidth: Int,
        bitmapHeight: Int,
        containerWidth: Int,
        containerHeight: Int,
        fit: ReaderFit,
        cropPercent: Int,
        zoom: Float,
    ): Float {
        val fitScale = baseFitScale(bitmapWidth, bitmapHeight, containerWidth, containerHeight, fit)
        if (fitScale <= 0f) return 0f
        val safeZoom = clampZoom(zoom)
        val cropFactor = 1.0f + clampCropPercent(cropPercent) / 100.0f
        return fitScale * safeZoom * cropFactor
    }

    /**
     * Computes exact symmetric pan limits `(maxX, maxY)` preventing over-panning past the rendered
     * bitmap edges for any combination of [ReaderFit], manual crop (`0..20%`), and zoom (`1f..5f`).
     */
    fun computePanLimit(
        bitmapWidth: Int,
        bitmapHeight: Int,
        containerWidth: Int,
        containerHeight: Int,
        fit: ReaderFit,
        cropPercent: Int,
        zoom: Float,
    ): PanLimit {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            return PanLimit.ZERO
        }
        val scale = effectiveScale(bitmapWidth, bitmapHeight, containerWidth, containerHeight, fit, cropPercent, zoom)
        val maxX = (bitmapWidth * scale - containerWidth).coerceAtLeast(0f) / 2f
        val maxY = (bitmapHeight * scale - containerHeight).coerceAtLeast(0f) / 2f
        return PanLimit(maxX = maxX, maxY = maxY)
    }

    fun clampPan(offset: PanOffset, limit: PanLimit): PanOffset = PanOffset(
        x = offset.x.coerceIn(-limit.maxX, limit.maxX),
        y = offset.y.coerceIn(-limit.maxY, limit.maxY),
    )

    /**
     * Groups 1-based [pages] into single-page or 2-page spread sheets respecting [combineCover].
     *
     * When `spread == 2` and `combineCover == false` and page `1` is present at index `0`,
     * cover page `1` stands alone as `[1]` and subsequent pages pair `[2, 3]`, `[4, 5]`, ...
     */
    fun computeSpreadPairs(
        pages: List<Int>,
        spread: Int = 2,
        combineCover: Boolean = false,
    ): List<List<Int>> {
        require(pages.isNotEmpty()) { "pages must not be empty" }
        require(spread in 1..2) { "spread must be 1 or 2, got $spread" }
        if (spread == 1 || combineCover || pages.first() != 1) {
            return pages.chunked(spread)
        }
        return listOf(listOf(1)) + pages.drop(1).chunked(spread)
    }

    fun computeSpreadPairs(
        pageCount: Int,
        spread: Int = 2,
        combineCover: Boolean = false,
    ): List<List<Int>> {
        require(pageCount > 0) { "pageCount must be positive" }
        return computeSpreadPairs((1..pageCount).toList(), spread, combineCover)
    }
}

/**
 * Sheet-level navigation helper for paged (`LTR` / `RTL`) and landscape `SPREAD` modes.
 */
data class SpreadNavigator(
    val pages: List<Int>,
    val spread: Int,
    val combineCover: Boolean,
) {
    val sheets: List<List<Int>> = ReaderGeometry.computeSpreadPairs(pages, spread, combineCover)

    fun sheetIndex(page: Int): Int = sheets.indexOfFirst { page in it }.coerceAtLeast(0)

    fun sheetFor(page: Int): List<Int> = sheets[sheetIndex(page)]

    fun adjacentPage(currentPage: Int, forward: Boolean): Int? {
        val nextIndex = sheetIndex(currentPage) + if (forward) 1 else -1
        return sheets.getOrNull(nextIndex)?.firstOrNull()
    }

    fun boundaryPage(forward: Boolean): Int = if (forward) pages.last() else pages.first()
}
