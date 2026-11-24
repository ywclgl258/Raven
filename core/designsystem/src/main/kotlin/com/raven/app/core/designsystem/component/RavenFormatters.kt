package com.raven.app.core.designsystem.component

import kotlin.math.floor

/**
 * Pure Kotlin, locale-independent display formatters for Raven UI metadata.
 * Designed to pair with [com.raven.app.core.designsystem.theme.RavenTabularTextStyle] (`tnum`).
 */
object RavenFormatters {
    const val VIDEO_ASPECT_RATIO: Float = 16f / 9f
    const val DEFAULT_GALLERY_ASPECT_RATIO: Float = 0.72f
    const val MIN_ASPECT_RATIO: Float = 0.45f
    const val MAX_ASPECT_RATIO: Float = 2.2f

    /**
     * Formats duration in seconds into `MM:SS` (`< 1h`) or `H:MM:SS` (`>= 1h`).
     * Returns empty string for `null` or negative inputs.
     */
    fun formatDuration(seconds: Int?): String {
        if (seconds == null || seconds < 0) return ""
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "$hours:${padTwo(minutes)}:${padTwo(secs)}"
        } else {
            "${padTwo(minutes)}:${padTwo(secs)}"
        }
    }

    /** Formats duration in milliseconds into `MM:SS` / `H:MM:SS`. */
    fun formatDurationMs(durationMs: Long): String {
        if (durationMs < 0L) return ""
        val seconds = (durationMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return formatDuration(seconds)
    }

    /**
     * Compact tabular count formatter (`999`, `1K`, `1.2K`, `12.5K`, `100K`, `1.5M`, `2.3B`).
     * Uses deterministic half-up rounding without locale-dependent decimal separators.
     */
    fun formatCount(count: Long?): String {
        if (count == null || count < 0L) return ""
        if (count < 1_000L) return count.toString()

        val units = arrayOf("K", "M", "B", "T")
        var value = count.toDouble()
        var unitIndex = -1
        while (value >= 1_000.0 && unitIndex < units.lastIndex) {
            value /= 1_000.0
            unitIndex++
        }
        return formatScaled(value = value, unitIndex = unitIndex, units = units, spaceBeforeUnit = false)
    }

    /**
     * Formats byte size using binary 1024-based units (`0 B`, `512 B`, `1 KB`, `1.5 MB`, `2.4 GB`).
     * Negative values clamp cleanly to `"0 B"`.
     */
    fun formatByteSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1_024L) return "$bytes B"

        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1_024.0 && unitIndex < units.lastIndex) {
            value /= 1_024.0
            unitIndex++
        }
        return formatScaled(value = value, unitIndex = unitIndex, units = units, spaceBeforeUnit = true)
    }

    /**
     * Clamps an artwork aspect ratio (`width / height`) into safe layout bounds so extreme
     * webtoon strips or invalid server dimensions never break cards or masonry grids.
     */
    fun clampAspectRatio(
        aspectRatio: Float?,
        defaultRatio: Float = DEFAULT_GALLERY_ASPECT_RATIO,
        minRatio: Float = MIN_ASPECT_RATIO,
        maxRatio: Float = MAX_ASPECT_RATIO,
    ): Float {
        val low = minOf(minRatio, maxRatio).coerceAtLeast(0.1f)
        val high = maxOf(minRatio, maxRatio).coerceAtLeast(low)
        val safeDefault = if (defaultRatio.isFinite() && defaultRatio > 0f) {
            defaultRatio.coerceIn(low, high)
        } else {
            DEFAULT_GALLERY_ASPECT_RATIO.coerceIn(low, high)
        }
        if (aspectRatio == null || !aspectRatio.isFinite() || aspectRatio <= 0f) {
            return safeDefault
        }
        return aspectRatio.coerceIn(low, high)
    }

    /** Formats a gallery page badge such as `"42P"`, or empty string for invalid counts. */
    fun formatPageCount(pageCount: Int?): String {
        if (pageCount == null || pageCount <= 0) return ""
        return "${pageCount}P"
    }

    /** Formats reading progress such as `"14 / 120"`. */
    fun formatPageProgress(currentPage: Int, totalPages: Int): String {
        val safeTotal = totalPages.coerceAtLeast(0)
        val safeCurrent = if (safeTotal > 0) currentPage.coerceIn(0, safeTotal) else currentPage.coerceAtLeast(0)
        return "$safeCurrent / $safeTotal"
    }

    private fun formatScaled(
        value: Double,
        unitIndex: Int,
        units: Array<String>,
        spaceBeforeUnit: Boolean,
    ): String {
        val separator = if (spaceBeforeUnit) " " else ""
        val unitLimit = if (spaceBeforeUnit) 1_024L else 1_000L
        return if (value >= 100.0) {
            val rounded = floor(value + 0.5).toLong()
            if (rounded >= unitLimit && unitIndex < units.lastIndex) {
                "1$separator${units[unitIndex + 1]}"
            } else {
                "$rounded$separator${units[unitIndex]}"
            }
        } else {
            val tenths = floor(value * 10.0 + 0.5).toLong()
            val whole = tenths / 10L
            val frac = tenths % 10L
            when {
                whole >= 100L -> "$whole$separator${units[unitIndex]}"
                frac == 0L -> "$whole$separator${units[unitIndex]}"
                else -> "$whole.$frac$separator${units[unitIndex]}"
            }
        }
    }

    private fun padTwo(value: Int): String = if (value < 10) "0$value" else value.toString()
}
