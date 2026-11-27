package com.raven.app.core.media.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.sitespi.ImageUnscrambler
import com.raven.app.core.sitespi.JmcomicStripMath
import java.io.ByteArrayOutputStream

/**
 * Renders scrambled gallery pages (such as JMComic's bottom-to-top horizontal strips) using
 * [BitmapRegionDecoder] + [Canvas] driven purely by [ImageUnscrambler] from the owning `SitePlugin`.
 */
class JmcomicBitmapUnscrambler {

    /**
     * Resolves the effective strip count for [page] using `page.stripCount` or `page.unscrambleSeed`
     * (`"chapterId:scrambleId:filenameStem"`). Returns `0` when no unscrambling is needed.
     */
    fun resolveStripCount(page: GalleryPage, unscrambler: ImageUnscrambler): Int {
        if (page.stripCount > 0) return page.stripCount
        val seed = page.unscrambleSeed?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        val parts = seed.split(':', limit = 3)
        if (parts.size != 3) return 0
        val chapterId = parts[0].toLongOrNull() ?: return 0
        val scrambleId = parts[1].toLongOrNull() ?: return 0
        val filenameStem = parts[2].trim()
        if (filenameStem.isEmpty()) return 0
        return unscrambler.computeStripCount(chapterId, scrambleId, filenameStem)
    }

    fun needsUnscramble(page: GalleryPage, unscrambler: ImageUnscrambler): Boolean =
        resolveStripCount(page, unscrambler) > 0

    /**
     * Unscrambles raw image [bytes] according to [unscrambler] and re-encodes as JPEG (96%) or PNG.
     * Returns [bytes] untouched if `stripCount == 0`.
     */
    fun unscrambleToBytes(
        bytes: ByteArray,
        page: GalleryPage,
        unscrambler: ImageUnscrambler,
    ): ByteArray {
        val stripCount = resolveStripCount(page, unscrambler)
        if (stripCount <= 0) return bytes
        return unscrambleToBytes(bytes, stripCount, unscrambler)
    }

    fun unscrambleToBytes(
        bytes: ByteArray,
        stripCount: Int,
        unscrambler: ImageUnscrambler,
    ): ByteArray {
        if (stripCount <= 0) return bytes
        val decoder = createRegionDecoder(bytes)
        try {
            val width = decoder.width
            val height = decoder.height
            check(JmcomicStripMath.validateDimensions(width, height, stripCount)) {
                "图页尺寸或分条参数无效 (${width}x$height, strips=$stripCount)"
            }
            val slices = unscrambler.computeSlicePlan(height, stripCount)
            val rendered = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(rendered)
                for (slice in slices) {
                    val region = decoder.decodeRegion(
                        Rect(0, slice.sourceTop, width, slice.sourceTop + slice.height),
                        null,
                    ) ?: error("无法解码分条图页")
                    try {
                        // Explicit pixel rect keeps output density-independent.
                        canvas.drawBitmap(
                            region,
                            null,
                            Rect(0, slice.destinationTop, width, slice.destinationTop + slice.height),
                            null,
                        )
                    } finally {
                        region.recycle()
                    }
                }
                val format = chooseEncodeFormat(bytes, rendered)
                val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 92
                val out = ByteArrayOutputStream(bytes.size)
                check(rendered.compress(format, quality, out) && out.size() <= JmcomicStripMath.MAX_GALLERY_IMAGE_BYTES) {
                    "无法保存还原后的分条图页"
                }
                return out.toByteArray()
            } finally {
                rendered.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    /**
     * Decodes a thumbnail directly from scrambled [bytes] without allocating a full-resolution bitmap.
     */
    fun decodeUnscrambledThumbnail(
        bytes: ByteArray,
        stripCount: Int,
        unscrambler: ImageUnscrambler,
        targetSizePx: Int,
    ): Bitmap {
        require(targetSizePx > 0) { "targetSizePx must be positive" }
        val decoder = createRegionDecoder(bytes)
        try {
            val width = decoder.width
            val height = decoder.height
            check(JmcomicStripMath.validateDimensions(width, height, stripCount)) {
                "图页尺寸或分条参数无效 (${width}x$height, strips=$stripCount)"
            }
            val longEdge = maxOf(width, height)
            val targetWidth = ImageStructureValidator.computeThumbnailEdge(width, longEdge, targetSizePx)
            val targetHeight = ImageStructureValidator.computeThumbnailEdge(height, longEdge, targetSizePx)
            val sampleOptions = BitmapFactory.Options().apply {
                inSampleSize = ImageStructureValidator.computeThumbnailSampleSize(longEdge, targetSizePx)
            }
            val slices = unscrambler.computeSlicePlan(height, stripCount)
            val rendered = createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(rendered)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                for (slice in slices) {
                    val destTop = (slice.destinationTop.toLong() * targetHeight / height).toInt()
                    val destBottom = ((slice.destinationTop + slice.height).toLong() * targetHeight / height).toInt()
                    if (destBottom > destTop) {
                        val strip = decoder.decodeRegion(
                            Rect(0, slice.sourceTop, width, slice.sourceTop + slice.height),
                            sampleOptions,
                        ) ?: error("无法解码分条图页缩略图")
                        try {
                            canvas.drawBitmap(strip, null, Rect(0, destTop, targetWidth, destBottom), paint)
                        } finally {
                            strip.recycle()
                        }
                    }
                }
                return rendered
            } catch (t: Throwable) {
                rendered.recycle()
                throw t
            }
        } finally {
            decoder.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun createRegionDecoder(bytes: ByteArray): BitmapRegionDecoder {
        val decoder: BitmapRegionDecoder? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }
        return decoder ?: error("无法初始化分条图页解码器")
    }

    @Suppress("DEPRECATION")
    private fun chooseEncodeFormat(rawBytes: ByteArray, rendered: Bitmap): Bitmap.CompressFormat {
        val isJpeg = rawBytes.size >= 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xD8.toByte()
        if (isJpeg) return Bitmap.CompressFormat.JPEG
        val isWebp = rawBytes.size >= 12 &&
            rawBytes[0] == 'R'.code.toByte() &&
            rawBytes[1] == 'I'.code.toByte() &&
            rawBytes[2] == 'F'.code.toByte() &&
            rawBytes[3] == 'F'.code.toByte() &&
            rawBytes[8] == 'W'.code.toByte() &&
            rawBytes[9] == 'E'.code.toByte() &&
            rawBytes[10] == 'B'.code.toByte() &&
            rawBytes[11] == 'P'.code.toByte()
        if (isWebp) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
        }
        return if (!rendered.hasAlpha()) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
    }
}
