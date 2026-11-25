package com.raven.app.core.common

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * API 24-safe UTC date parser and range matcher for published media dates (`YYYY-MM-DD`,
 * slash/dot dates, Chinese dates, English month names, and EN/ZH relative labels).
 */
object MediaDateParser {
    private const val MILLIS_PER_DAY: Long = 86_400_000L
    private val utcZone: TimeZone = TimeZone.getTimeZone("UTC")

    private val isoDateRegex = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
    private val ymdRegex = Regex("(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?")
    private val englishMonthFirstRegex = Regex(
        "\\b([A-Za-z]{3,9})\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})\\b",
        RegexOption.IGNORE_CASE,
    )
    private val englishDayFirstRegex = Regex(
        "\\b(\\d{1,2})\\s+([A-Za-z]{3,9})\\.?\\s+(\\d{4})\\b",
        RegexOption.IGNORE_CASE,
    )
    private val relativeNumberUnitRegex = Regex(
        "(\\d+)\\s*(?:(seconds?|secs?|minutes?|mins?|hours?|hrs?|秒|分钟|小时|時)|" +
            "(days?|d|天|日)|" +
            "(weeks?|w|周|星期|禮拜|礼拜)|" +
            "(months?|mos?|个月|個月|月)|" +
            "(years?|yrs?|年))\\s*(?:ago|前)?",
        RegexOption.IGNORE_CASE,
    )

    private val monthMap: Map<String, Int> = mapOf(
        "jan" to 1, "january" to 1,
        "feb" to 2, "february" to 2,
        "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4,
        "may" to 5,
        "jun" to 6, "june" to 6,
        "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8,
        "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "october" to 10,
        "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12,
    )

    fun currentUtcEpochDay(nowMs: Long = System.currentTimeMillis()): Long =
        floorDivDays(nowMs)

    fun parseIsoDateToEpochDay(isoDate: String?): Long? {
        val text = isoDate?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = isoDateRegex.matchEntire(text) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        return toUtcEpochDay(year, month, day)
    }

    fun formatEpochDayToIso(epochDay: Long): String {
        val cal = GregorianCalendar(utcZone).apply {
            clear()
            timeInMillis = epochDay * MILLIS_PER_DAY
        }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d)
    }

    fun parseMediaDateLabelToEpochDay(
        dateLabel: String?,
        nowEpochDay: Long = currentUtcEpochDay(),
    ): Long? {
        val raw = dateLabel?.trim().orEmpty()
        if (raw.isEmpty()) return null

        // 1. Explicit numeric Y-M-D / Y/M/D / Y.M.D / YYYY年M月D日
        ymdRegex.find(raw)?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            if (y != null && mo != null && d != null) {
                toUtcEpochDay(y, mo, d)?.let { return it }
            }
        }

        // 2. English month name formats
        englishMonthFirstRegex.find(raw)?.let { m ->
            val mo = monthMap[m.groupValues[1].lowercase(Locale.ROOT)]
            val d = m.groupValues[2].toIntOrNull()
            val y = m.groupValues[3].toIntOrNull()
            if (y != null && mo != null && d != null) {
                toUtcEpochDay(y, mo, d)?.let { return it }
            }
        }
        englishDayFirstRegex.find(raw)?.let { m ->
            val d = m.groupValues[1].toIntOrNull()
            val mo = monthMap[m.groupValues[2].lowercase(Locale.ROOT)]
            val y = m.groupValues[3].toIntOrNull()
            if (y != null && mo != null && d != null) {
                toUtcEpochDay(y, mo, d)?.let { return it }
            }
        }

        // 3. Relative keywords (EN/ZH)
        val lower = raw.lowercase(Locale.ROOT)
        if (lower.contains("today") || lower.contains("just now") ||
            raw.contains("今天") || raw.contains("刚刚")
        ) {
            return nowEpochDay
        }
        if (lower.contains("yesterday") || raw.contains("昨天") || raw.contains("昨日")) {
            return nowEpochDay - 1L
        }

        val relMatch = relativeNumberUnitRegex.find(raw)
        if (relMatch != null) {
            val amount = relMatch.groupValues[1].toLongOrNull()?.coerceAtLeast(0L) ?: return null
            return when {
                relMatch.groupValues[2].isNotEmpty() -> nowEpochDay
                relMatch.groupValues[3].isNotEmpty() -> nowEpochDay - amount
                relMatch.groupValues[4].isNotEmpty() -> nowEpochDay - amount * 7L
                relMatch.groupValues[5].isNotEmpty() -> nowEpochDay - amount * 30L
                relMatch.groupValues[6].isNotEmpty() -> nowEpochDay - amount * 365L
                else -> null
            }
        }

        return null
    }

    fun matchesDateRange(
        dateLabel: String?,
        publishedFrom: String?,
        publishedTo: String?,
        nowEpochDay: Long = currentUtcEpochDay(),
    ): Boolean {
        val rawFrom = parseIsoDateToEpochDay(publishedFrom)
        val rawTo = parseIsoDateToEpochDay(publishedTo)
        if (rawFrom == null && rawTo == null) return true
        val fromDay = if (rawFrom != null && rawTo != null) minOf(rawFrom, rawTo) else rawFrom
        val toDay = if (rawFrom != null && rawTo != null) maxOf(rawFrom, rawTo) else rawTo
        val itemDay = parseMediaDateLabelToEpochDay(dateLabel, nowEpochDay) ?: return false
        if (fromDay != null && itemDay < fromDay) return false
        if (toDay != null && itemDay > toDay) return false
        return true
    }

    private fun toUtcEpochDay(year: Int, month: Int, day: Int): Long? {
        if (year !in 1970..2100 || month !in 1..12 || day !in 1..31) return null
        val cal = GregorianCalendar(utcZone).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }
        return runCatching {
            floorDivDays(cal.timeInMillis)
        }.getOrNull()
    }

    private fun floorDivDays(epochMs: Long): Long =
        if (epochMs >= 0L) {
            epochMs / MILLIS_PER_DAY
        } else {
            ((epochMs + 1L) / MILLIS_PER_DAY) - 1L
        }
}
