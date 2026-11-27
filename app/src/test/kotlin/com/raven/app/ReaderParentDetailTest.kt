package com.raven.app

import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderParentDetailTest {

    private val reader = RavenRoute.Reader(
        siteId = SiteId.MANGACOPY.value,
        mediaId = "work-18",
        url = "https://example.com/comic/work-18",
        startPage = 7,
    )

    @Test
    fun resumeEntryWithoutDetailInsertsCurrentWorkDetail() {
        assertEquals(
            RavenRoute.GalleryDetail(
                siteId = reader.siteId,
                mediaId = reader.mediaId,
                url = reader.url,
            ),
            readerParentDetail(currentDetail = null, reader = reader),
        )
    }

    @Test
    fun matchingDetailIsReusedWithoutAddingDuplicate() {
        val detail = RavenRoute.GalleryDetail(
            siteId = reader.siteId,
            mediaId = reader.mediaId,
            url = "https://example.com/comic/work-18?from=detail",
        )

        assertNull(readerParentDetail(currentDetail = detail, reader = reader))
    }

    @Test
    fun readerOpenedFromAnotherWorkStillGetsItsOwnDetail() {
        val anotherDetail = RavenRoute.GalleryDetail(
            siteId = reader.siteId,
            mediaId = "another-work",
            url = "https://example.com/comic/another-work",
        )

        assertEquals(reader.mediaId, readerParentDetail(anotherDetail, reader)?.mediaId)
    }

    @Test
    fun offlineReaderPropagatesOfflineOnlyFlagToInsertedParentDetail() {
        val localReader = reader.copy(
            siteId = SiteId.EXHENTAI.value,
            mediaId = "offline-work",
            url = "https://exhentai.org/g/1/abc/",
            offlineOnly = true,
        )

        val parent = readerParentDetail(currentDetail = null, reader = localReader)
        assertEquals(
            RavenRoute.GalleryDetail(
                siteId = SiteId.EXHENTAI.value,
                mediaId = "offline-work",
                url = "https://exhentai.org/g/1/abc/",
                offlineOnly = true,
            ),
            parent,
        )
        assertTrue(parent?.offlineOnly == true)

        // Online detail for the same work must NOT be reused for an offline-only reader
        val onlineSameWorkDetail = RavenRoute.GalleryDetail(
            siteId = SiteId.EXHENTAI.value,
            mediaId = "offline-work",
            url = "https://exhentai.org/g/1/abc/",
            offlineOnly = false,
        )
        assertTrue(readerParentDetail(onlineSameWorkDetail, localReader)?.offlineOnly == true)
    }

    @Test
    fun advancingInsideReaderReplacesStaleParentDetailWithNewWorkDetail() {
        val currentReader = RavenRoute.Reader(
            siteId = SiteId.NHENTAI.value,
            mediaId = "work-a",
            url = "https://nhentai.net/g/1/",
        )
        val staleParent = RavenRoute.GalleryDetail(
            siteId = SiteId.NHENTAI.value,
            mediaId = "work-a",
            url = "https://nhentai.net/g/1/",
        )
        val nextWorkReader = RavenRoute.Reader(
            siteId = SiteId.NHENTAI.value,
            mediaId = "work-b",
            url = "https://nhentai.net/g/2/",
        )

        val plan = planReaderNavigation(
            currentReader = currentReader,
            currentDetail = null,
            previousDetail = staleParent,
            targetReader = nextWorkReader,
        )

        assertTrue(plan.popCurrentReader)
        assertTrue(plan.popStaleParentDetail)
        assertEquals("work-b", plan.insertParentDetail?.mediaId)
    }

    @Test
    fun advancingInsideSameWorkReaderReusesMatchingParentDetail() {
        val currentReader = reader.copy(mediaId = "work-18:ch1")
        val matchingParent = RavenRoute.GalleryDetail(
            siteId = reader.siteId,
            mediaId = "work-18",
            url = reader.url,
        )

        val plan = planReaderNavigation(
            currentReader = currentReader,
            currentDetail = null,
            previousDetail = matchingParent,
            targetReader = reader,
        )

        assertTrue(plan.popCurrentReader)
        assertFalse(plan.popStaleParentDetail)
        assertNull(plan.insertParentDetail)
    }
    @Test
    fun readerWithKnownParentInsertsCanonicalRootDetail() {
        val reader = RavenRoute.Reader(
            siteId = SiteId.JMCOMIC.value,
            mediaId = "chapter-42",
            url = "https://jmcomic.example/photo/42",
            parent = RavenRoute.ReaderParent(
                seriesId = "album-7",
                title = "Album 7",
                url = "https://jmcomic.example/album/7",
            ),
        )

        assertEquals(
            RavenRoute.GalleryDetail(
                siteId = reader.siteId,
                mediaId = "album-7",
                url = "https://jmcomic.example/album/7",
            ),
            readerParentDetail(currentDetail = null, reader = reader),
        )
    }

    @Test
    fun matchingParentRootDetailIsReused() {
        val reader = RavenRoute.Reader(
            siteId = SiteId.MANGACOPY.value,
            mediaId = "work-18:ch1",
            url = "https://example.com/comic/work-18/chapter/ch1",
            parent = RavenRoute.ReaderParent(
                seriesId = "work-18",
                title = "Work 18",
                url = "https://example.com/comic/work-18",
            ),
        )
        val detail = RavenRoute.GalleryDetail(
            siteId = reader.siteId,
            mediaId = "work-18",
            url = "https://example.com/comic/work-18",
        )

        assertNull(readerParentDetail(currentDetail = detail, reader = reader))
    }
}
