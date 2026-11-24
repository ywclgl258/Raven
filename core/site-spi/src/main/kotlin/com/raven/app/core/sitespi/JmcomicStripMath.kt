package com.raven.app.core.sitespi

import java.security.MessageDigest

/**
 * Pure-Kotlin JMComic Canvas strip-count and slice-geometry calculator (`jquery.photo-0.4.js` parity).
 *
 * Strip rules:
 * - `chapterId < scrambleId` -> `0` (unscrambled)
 * - `chapterId < 268850` -> `10`
 * - Otherwise `MD5("$chapterId$filenameStem")`: take lowest hex digit `lastHex` (`'0'..'9'`, `'a'..'f'`),
 *   take its ASCII code `lastHex.code`:
 *   - `chapterId < 421926`: `((lastHex.code % 10) * 2) + 2` (range `{2, 4, ..., 20}`)
 *   - `chapterId >= 421926`: `((lastHex.code % 8) * 2) + 2` (range `{2, 4, ..., 16}`)
 *
 * Geometry rules:
 * - `stripHeight = imageHeight / stripCount`, `remainder = imageHeight % stripCount`
 * - Bottom-to-top strip order; `remainder` is attached to `index == 0` (bottom of source -> top of destination).
 */
object JmcomicStripMath : ImageUnscrambler {
    const val LEGACY_FIXED_TEN_THRESHOLD: Long = 268_850L
    const val MOD_EIGHT_THRESHOLD: Long = 421_926L
    const val MIN_STRIPS: Int = 2
    const val MAX_STRIPS: Int = 20
    const val MAX_UNSCRAMBLE_PIXELS: Long = 12_000_000L
    const val MAX_GALLERY_IMAGE_BYTES: Int = 24 * 1024 * 1024

    private const val HEX_DIGITS = "0123456789abcdef"

    override fun computeStripCount(chapterId: Long, scrambleId: Long, filenameStem: String): Int {
        if (chapterId < scrambleId) return 0
        if (chapterId < LEGACY_FIXED_TEN_THRESHOLD) return 10
        val digest = MessageDigest.getInstance("MD5")
            .digest("$chapterId$filenameStem".toByteArray(Charsets.UTF_8))
        val lastByte = digest.last().toInt()
        val lastHex = HEX_DIGITS[lastByte and 0x0F]
        val modulus = if (chapterId < MOD_EIGHT_THRESHOLD) 10 else 8
        return ((lastHex.code % modulus) * 2) + 2
    }

    override fun computeSlicePlan(imageHeight: Int, stripCount: Int): List<StripSlice> {
        require(imageHeight > 0) { "imageHeight must be positive: $imageHeight" }
        if (stripCount == 0) {
            return listOf(StripSlice(sourceTop = 0, destinationTop = 0, height = imageHeight))
        }
        require(stripCount in MIN_STRIPS..minOf(imageHeight, MAX_STRIPS)) {
            "stripCount ($stripCount) must be within $MIN_STRIPS..${minOf(imageHeight, MAX_STRIPS)}"
        }
        val stripHeight = imageHeight / stripCount
        val remainder = imageHeight % stripCount
        var destinationTop = 0
        return List(stripCount) { index ->
            val sourceTop = imageHeight - (index + 1) * stripHeight - remainder
            val sliceHeight = stripHeight + if (index == 0) remainder else 0
            StripSlice(
                sourceTop = sourceTop,
                destinationTop = destinationTop,
                height = sliceHeight,
            ).also { destinationTop += sliceHeight }
        }
    }

    fun validateDimensions(width: Int, height: Int, stripCount: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width.toLong() * height.toLong() > MAX_UNSCRAMBLE_PIXELS) return false
        if (stripCount == 0) return true
        return stripCount in MIN_STRIPS..minOf(height, MAX_STRIPS)
    }
}
