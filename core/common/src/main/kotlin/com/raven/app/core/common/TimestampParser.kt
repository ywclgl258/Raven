package com.raven.app.core.common

data class TimestampSpan(
    val start: Int,
    val end: Int,
    val positionMs: Long,
    val matchedText: String,
)

/**
 * Parses clickable `HH:MM:SS` / `MM:SS` timestamps from descriptions/comments and formats durations.
 */
object TimestampParser {
    private val timestampRegex = Regex("(?<![\\d:])(?:(\\d{1,2}):)?([0-5]?\\d):([0-5]\\d)(?![\\d:])")
    private val isoDurationRegex = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$", RegexOption.IGNORE_CASE)
    private val cjkDurationRegex = Regex("(?:(\\d+)\\s*[小]?[時时])?(?:(\\d+)\\s*分[鐘钟]?)?(?:(\\d+)\\s*秒)?")

    /**
     * Finds all timestamp spans in [text]. If [maxDurationMs] is supplied, timestamps exceeding
     * [maxDurationMs] (or when [maxDurationMs] <= 0) are excluded.
     */
    fun parseSpans(text: String, maxDurationMs: Long? = null): List<TimestampSpan> {
        if (text.isBlank()) return emptyList()
        if (maxDurationMs != null && maxDurationMs <= 0L) return emptyList()
        return timestampRegex.findAll(text).mapNotNull { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val seconds = match.groupValues[3].toLongOrNull() ?: return@mapNotNull null
            val totalMs = (hours * 3600L + minutes * 60L + seconds) * 1000L
            if (maxDurationMs != null && totalMs > maxDurationMs) {
                null
            } else {
                TimestampSpan(
                    start = match.range.first,
                    end = match.range.last + 1,
                    positionMs = totalMs,
                    matchedText = match.value,
                )
            }
        }.toList()
    }

    /**
     * Parses `HH:MM:SS`, `MM:SS`, ISO-8601 (`PT1H31M0S`), or CJK duration (`1小時20分30秒`) into milliseconds.
     */
    fun parseDurationToMs(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        timestampRegex.matchEntire(trimmed)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues[2].toLong()
            val sec = m.groupValues[3].toLong()
            return (h * 3600L + min * 60L + sec) * 1000L
        }

        isoDurationRegex.matchEntire(trimmed)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues[2].toLongOrNull() ?: 0L
            val sec = m.groupValues[3].toLongOrNull() ?: 0L
            if (m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty() && m.groupValues[3].isEmpty()) return null
            return (h * 3600L + min * 60L + sec) * 1000L
        }

        if (trimmed.any { it in setOf('時', '时', '分', '秒') }) {
            cjkDurationRegex.matchEntire(trimmed)?.let { m ->
                val h = m.groupValues[1].toLongOrNull() ?: 0L
                val min = m.groupValues[2].toLongOrNull() ?: 0L
                val sec = m.groupValues[3].toLongOrNull() ?: 0L
                if (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty() || m.groupValues[3].isNotEmpty()) {
                    return (h * 3600L + min * 60L + sec) * 1000L
                }
            }
        }

        return trimmed.toLongOrNull()?.takeIf { it >= 0L }?.let { it * 1000L }
    }

    fun formatDurationSeconds(totalSeconds: Int): String {
        val clamped = totalSeconds.coerceAtLeast(0)
        val hours = clamped / 3600
        val minutes = (clamped % 3600) / 60
        val seconds = clamped % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    fun formatPositionMs(positionMs: Long): String =
        formatDurationSeconds((positionMs.coerceAtLeast(0L) / 1000L).toInt())
}

/**
 * Parses human-entered 1-based page selections such as `"1, 3, 5-8"` or `"1，3 5–8"`.
 */
object PageSelectionParser {
    fun parse(raw: String, totalPages: Int): List<Int> {
        require(totalPages > 0) { "Total page count must be positive" }
        val pages = linkedSetOf<Int>()
        val parts = raw.split(',', '，', ' ')
            .map(String::trim)
            .filter(String::isNotEmpty)
        require(parts.isNotEmpty()) { "Page selection must not be empty" }

        for (part in parts) {
            val bounds = part.split('-', '–', '—', limit = 2)
            val start = bounds[0].trim().toIntOrNull()
                ?: throw IllegalArgumentException("Invalid page token: $part")
            val end = if (bounds.size == 2) {
                bounds[1].trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid page range: $part")
            } else {
                start
            }
            require(start in 1..totalPages && end in 1..totalPages && start <= end) {
                "Page range $start-$end out of bounds 1..$totalPages"
            }
            for (p in start..end) pages.add(p)
        }
        return pages.sorted()
    }
}
