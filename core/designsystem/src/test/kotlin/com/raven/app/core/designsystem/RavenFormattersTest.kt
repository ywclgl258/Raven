package com.raven.app.core.designsystem

import com.raven.app.core.designsystem.component.RavenFormatters
import org.junit.Assert.assertEquals
import org.junit.Test

class RavenFormattersTest {

    @Test
    fun `formatDuration formats null, negative, mm-ss, and h-mm-ss accurately`() {
        assertEquals("", RavenFormatters.formatDuration(null))
        assertEquals("", RavenFormatters.formatDuration(-1))
        assertEquals("00:00", RavenFormatters.formatDuration(0))
        assertEquals("00:09", RavenFormatters.formatDuration(9))
        assertEquals("01:05", RavenFormatters.formatDuration(65))
        assertEquals("12:34", RavenFormatters.formatDuration(754))
        assertEquals("59:59", RavenFormatters.formatDuration(3_599))
        assertEquals("1:00:00", RavenFormatters.formatDuration(3_600))
        assertEquals("1:01:05", RavenFormatters.formatDuration(3_665))
        assertEquals("10:00:00", RavenFormatters.formatDuration(36_000))
    }

    @Test
    fun `formatDurationMs converts milliseconds and rejects negative values`() {
        assertEquals("", RavenFormatters.formatDurationMs(-500L))
        assertEquals("00:00", RavenFormatters.formatDurationMs(999L))
        assertEquals("02:05", RavenFormatters.formatDurationMs(125_400L))
        assertEquals("1:01:01", RavenFormatters.formatDurationMs(3_661_000L))
    }

    @Test
    fun `formatCount handles units, rounding, and boundary promotions`() {
        assertEquals("", RavenFormatters.formatCount(null))
        assertEquals("", RavenFormatters.formatCount(-42L))
        assertEquals("0", RavenFormatters.formatCount(0L))
        assertEquals("999", RavenFormatters.formatCount(999L))
        assertEquals("1K", RavenFormatters.formatCount(1_000L))
        assertEquals("1.2K", RavenFormatters.formatCount(1_200L))
        assertEquals("1.3K", RavenFormatters.formatCount(1_250L))
        assertEquals("12.4K", RavenFormatters.formatCount(12_400L))
        assertEquals("100K", RavenFormatters.formatCount(99_990L))
        assertEquals("100K", RavenFormatters.formatCount(100_000L))
        assertEquals("1M", RavenFormatters.formatCount(999_500L))
        assertEquals("1.5M", RavenFormatters.formatCount(1_500_000L))
        assertEquals("2.3B", RavenFormatters.formatCount(2_340_000_000L))
    }

    @Test
    fun `formatByteSize handles binary IEC boundaries cleanly`() {
        assertEquals("0 B", RavenFormatters.formatByteSize(-10L))
        assertEquals("0 B", RavenFormatters.formatByteSize(0L))
        assertEquals("512 B", RavenFormatters.formatByteSize(512L))
        assertEquals("1023 B", RavenFormatters.formatByteSize(1_023L))
        assertEquals("1 KB", RavenFormatters.formatByteSize(1_024L))
        assertEquals("1.5 KB", RavenFormatters.formatByteSize(1_536L))
        assertEquals("256 KB", RavenFormatters.formatByteSize(256L * 1_024L))
        assertEquals("1 MB", RavenFormatters.formatByteSize(1_024L * 1_024L))
        assertEquals("2.5 MB", RavenFormatters.formatByteSize(2_621_440L))
        assertEquals("1 GB", RavenFormatters.formatByteSize(1_024L * 1_024L * 1_024L))
        assertEquals("1 GB", RavenFormatters.formatByteSize(1_024L * 1_024L * 1_024L - 1L))
        assertEquals("1.5 TB", RavenFormatters.formatByteSize(1_649_267_441_664L))
    }

    @Test
    fun `clampAspectRatio handles invalid, extreme, normal, and swapped bounds`() {
        assertEquals(RavenFormatters.DEFAULT_GALLERY_ASPECT_RATIO, RavenFormatters.clampAspectRatio(null), 0.0001f)
        assertEquals(RavenFormatters.DEFAULT_GALLERY_ASPECT_RATIO, RavenFormatters.clampAspectRatio(Float.NaN), 0.0001f)
        assertEquals(RavenFormatters.DEFAULT_GALLERY_ASPECT_RATIO, RavenFormatters.clampAspectRatio(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(RavenFormatters.DEFAULT_GALLERY_ASPECT_RATIO, RavenFormatters.clampAspectRatio(Float.NEGATIVE_INFINITY), 0.0001f)
        assertEquals(RavenFormatters.DEFAULT_GALLERY_ASPECT_RATIO, RavenFormatters.clampAspectRatio(0f), 0.0001f)
        assertEquals(RavenFormatters.DEFAULT_GALLERY_ASPECT_RATIO, RavenFormatters.clampAspectRatio(-1.5f), 0.0001f)

        assertEquals(RavenFormatters.MIN_ASPECT_RATIO, RavenFormatters.clampAspectRatio(0.12f), 0.0001f)
        assertEquals(RavenFormatters.MAX_ASPECT_RATIO, RavenFormatters.clampAspectRatio(5.0f), 0.0001f)
        assertEquals(0.75f, RavenFormatters.clampAspectRatio(0.75f), 0.0001f)
        assertEquals(1.5f, RavenFormatters.clampAspectRatio(1.5f), 0.0001f)

        // Swapped bounds still clamp safely
        assertEquals(1.2f, RavenFormatters.clampAspectRatio(1.8f, minRatio = 1.2f, maxRatio = 0.6f), 0.0001f)
        assertEquals(0.6f, RavenFormatters.clampAspectRatio(0.2f, minRatio = 1.2f, maxRatio = 0.6f), 0.0001f)
    }

    @Test
    fun `formatPageCount and formatPageProgress format gallery counters`() {
        assertEquals("", RavenFormatters.formatPageCount(null))
        assertEquals("", RavenFormatters.formatPageCount(0))
        assertEquals("", RavenFormatters.formatPageCount(-3))
        assertEquals("42P", RavenFormatters.formatPageCount(42))

        assertEquals("14 / 120", RavenFormatters.formatPageProgress(14, 120))
        assertEquals("0 / 10", RavenFormatters.formatPageProgress(-2, 10))
        assertEquals("10 / 10", RavenFormatters.formatPageProgress(25, 10))
        assertEquals("0 / 0", RavenFormatters.formatPageProgress(-1, -5))
    }
}
