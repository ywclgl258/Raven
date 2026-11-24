package com.raven.app.core.database

import com.raven.app.core.database.domain.CompletionPolicy
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionPolicyTest {

    @Test
    fun `short clips under 40s require reaching duration or explicit completion`() {
        val midShort = watch(positionMs = 25_000L, durationMs = 30_000L)
        assertTrue(CompletionPolicy.isShortClip(midShort.durationMs))
        assertTrue(CompletionPolicy.isWatchStarted(midShort))
        assertFalse(CompletionPolicy.isWatchCompleted(midShort))
        assertTrue(CompletionPolicy.isWatchResumable(midShort))

        val endShort = watch(positionMs = 30_000L, durationMs = 30_000L)
        assertTrue(CompletionPolicy.isWatchCompleted(endShort))
        assertFalse(CompletionPolicy.isWatchResumable(endShort))

        val barelyStartedShort = watch(positionMs = 500L, durationMs = 30_000L)
        assertTrue(CompletionPolicy.isWatchStarted(barelyStartedShort))
        assertFalse(CompletionPolicy.isWatchResumable(barelyStartedShort))

        val oneSecondShort = watch(positionMs = 1_000L, durationMs = 30_000L)
        assertTrue(CompletionPolicy.isWatchResumable(oneSecondShort))
    }

    @Test
    fun `long videos over 40s use 20s tail grace window and 15s resume threshold`() {
        val beforeResumeThreshold = watch(positionMs = 14_999L, durationMs = 300_000L)
        assertTrue(CompletionPolicy.isWatchStarted(beforeResumeThreshold))
        assertFalse(CompletionPolicy.isWatchResumable(beforeResumeThreshold))
        assertFalse(CompletionPolicy.isWatchCompleted(beforeResumeThreshold))

        val resumable = watch(positionMs = 15_000L, durationMs = 300_000L)
        assertTrue(CompletionPolicy.isWatchResumable(resumable))
        assertFalse(CompletionPolicy.isWatchCompleted(resumable))

        val justBeforeGrace = watch(positionMs = 279_999L, durationMs = 300_000L)
        assertFalse(CompletionPolicy.isWatchCompleted(justBeforeGrace))
        assertTrue(CompletionPolicy.isWatchResumable(justBeforeGrace))

        val insideGrace = watch(positionMs = 280_000L, durationMs = 300_000L)
        assertTrue(CompletionPolicy.isWatchCompleted(insideGrace))
        assertFalse(CompletionPolicy.isWatchResumable(insideGrace))
        assertTrue(CompletionPolicy.resolveWatchProgress(insideGrace).completed)
    }

    @Test
    fun `explicit completed flag always wins even with zero duration`() {
        val explicit = watch(positionMs = 0L, durationMs = 0L, completed = true)
        assertTrue(CompletionPolicy.isWatchStarted(explicit))
        assertTrue(CompletionPolicy.isWatchCompleted(explicit))
        assertFalse(CompletionPolicy.isWatchResumable(explicit))
    }

    @Test
    fun `reading progress marks completed on final page and clamps out of bounds page`() {
        val middle = reading(currentPage = 10, totalPages = 24)
        assertTrue(CompletionPolicy.isReadingStarted(middle))
        assertFalse(CompletionPolicy.isReadingCompleted(middle))
        assertTrue(CompletionPolicy.isReadingResumable(middle))

        val finalPage = reading(currentPage = 24, totalPages = 24)
        assertTrue(CompletionPolicy.isReadingCompleted(finalPage))
        assertFalse(CompletionPolicy.isReadingResumable(finalPage))
        assertTrue(CompletionPolicy.resolveReadingProgress(finalPage).completed)

        val clamped = CompletionPolicy.resolveReadingProgress(reading(currentPage = 99, totalPages = 24))
        assertEquals(24, clamped.currentPage)
        assertTrue(clamped.completed)

        val unknownTotal = CompletionPolicy.resolveReadingProgress(reading(currentPage = 0, totalPages = 0))
        assertEquals(1, unknownTotal.currentPage)
        assertFalse(unknownTotal.completed)
    }

    private fun watch(
        positionMs: Long,
        durationMs: Long,
        completed: Boolean = false,
    ) = WatchProgress(
        siteId = SiteId.R34V,
        mediaId = "v1",
        url = "https://rule34video.com/video/1/",
        title = "Video",
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = 1000L,
        completed = completed,
    )

    private fun reading(
        currentPage: Int,
        totalPages: Int,
        completed: Boolean = false,
    ) = ReadingProgress(
        siteId = SiteId.NHENTAI,
        mediaId = "g1",
        url = "https://nhentai.net/g/1/",
        title = "Book",
        kind = MediaKind.COMIC,
        currentPage = currentPage,
        totalPages = totalPages,
        updatedAt = 1000L,
        completed = completed,
    )
}
