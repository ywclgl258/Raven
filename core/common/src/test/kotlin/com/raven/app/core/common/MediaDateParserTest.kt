package com.raven.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDateParserTest {

    private val anchorDay = MediaDateParser.parseIsoDateToEpochDay("2026-05-15")!!

    @Test
    fun `parses ISO, slash, dot, Chinese, and English month date strings`() {
        assertEquals(anchorDay, MediaDateParser.parseMediaDateLabelToEpochDay("2026-05-15T12:34:56Z", anchorDay))
        assertEquals(anchorDay, MediaDateParser.parseMediaDateLabelToEpochDay("2026/5/15", anchorDay))
        assertEquals(anchorDay, MediaDateParser.parseMediaDateLabelToEpochDay("2026.05.15", anchorDay))
        assertEquals(anchorDay, MediaDateParser.parseMediaDateLabelToEpochDay("2026年5月15日", anchorDay))
        assertEquals(anchorDay, MediaDateParser.parseMediaDateLabelToEpochDay("May 15, 2026", anchorDay))
        assertEquals(anchorDay, MediaDateParser.parseMediaDateLabelToEpochDay("15 May 2026", anchorDay))
    }

    @Test
    fun `parses relative English and Chinese date labels relative to nowEpochDay`() {
        assertEquals(anchorDay, MediaDateParser.parseMediaDateLabelToEpochDay("今天 09:00", anchorDay))
        assertEquals(anchorDay - 1L, MediaDateParser.parseMediaDateLabelToEpochDay("昨天", anchorDay))
        assertEquals(anchorDay - 2L, MediaDateParser.parseMediaDateLabelToEpochDay("2 days ago", anchorDay))
        assertEquals(anchorDay - 14L, MediaDateParser.parseMediaDateLabelToEpochDay("2 weeks ago", anchorDay))
        assertEquals(anchorDay - 30L, MediaDateParser.parseMediaDateLabelToEpochDay("1个月前", anchorDay))
        assertNull(MediaDateParser.parseMediaDateLabelToEpochDay("unknown release date", anchorDay))
    }

    @Test
    fun `round-trips ISO date formatting and validates leap or invalid calendar dates`() {
        val leapDay = MediaDateParser.parseIsoDateToEpochDay("2024-02-29")
        assertEquals("2024-02-29", MediaDateParser.formatEpochDayToIso(leapDay!!))
        assertEquals(leapDay, MediaDateParser.parseMediaDateLabelToEpochDay("2024年2月29日"))
        assertNull(MediaDateParser.parseMediaDateLabelToEpochDay("发布于2025年2月29日"))
        assertEquals(anchorDay - 365L, MediaDateParser.parseMediaDateLabelToEpochDay("1年前", anchorDay))
        assertNull(MediaDateParser.parseIsoDateToEpochDay("2025-02-29"))
        assertNull(MediaDateParser.parseIsoDateToEpochDay("2026-13-01"))
    }

    @Test
    fun `matchesDateRange filters inclusively, handles reversed bounds, and excludes unparseable labels when bounded`() {
        assertTrue(MediaDateParser.matchesDateRange(null, null, null, anchorDay))
        assertTrue(MediaDateParser.matchesDateRange("2026-05-10", "2026-05-10", "2026-05-20", anchorDay))
        assertTrue(MediaDateParser.matchesDateRange("2026-05-20", "2026-05-10", "2026-05-20", anchorDay))
        assertTrue(MediaDateParser.matchesDateRange("3 days ago", "2026-05-10", "2026-05-20", anchorDay))

        assertFalse(MediaDateParser.matchesDateRange("2026-05-09", "2026-05-10", "2026-05-20", anchorDay))
        assertFalse(MediaDateParser.matchesDateRange("2026-05-21", "2026-05-10", "2026-05-20", anchorDay))
        assertFalse(MediaDateParser.matchesDateRange("未知时间", "2026-05-10", "2026-05-20", anchorDay))

        // Reversed user input (from > to) still normalizes cleanly
        assertTrue(MediaDateParser.matchesDateRange("2026-05-12", "2026-05-20", "2026-05-10", anchorDay))
    }
}
