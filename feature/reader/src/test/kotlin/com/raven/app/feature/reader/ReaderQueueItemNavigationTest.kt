package com.raven.app.feature.reader

import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.RavenRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for review-ui #3: a mixed gallery→video queue item must carry the originating offline
 * flag and queue token, otherwise the player re-queries online metadata and cannot continue the queue.
 */
class ReaderQueueItemNavigationTest {

    private class RecordingNavigator : ReaderNavigator {
        var lastQueueVideo: QueueVideoCall? = null
        var lastVideo: VideoCall? = null
        var lastReader: ReaderCall? = null

        data class QueueVideoCall(
            val siteId: SiteId,
            val mediaId: String,
            val offlineOnly: Boolean,
            val queueToken: String,
        )

        data class VideoCall(val mediaId: String)

        data class ReaderCall(val mediaId: String, val offlineOnly: Boolean, val queueToken: String?)

        override fun openReader(
            siteId: SiteId,
            mediaId: String,
            url: String,
            startPage: Int,
            offlineOnly: Boolean,
            queueToken: String?,
            parent: RavenRoute.ReaderParent?,
        ) {
            lastReader = ReaderCall(mediaId, offlineOnly, queueToken)
        }

        override fun openVideo(
            siteId: SiteId,
            mediaId: String,
            url: String,
            title: String,
            coverUrl: String,
        ) {
            lastVideo = VideoCall(mediaId)
        }

        override fun openQueueVideo(
            siteId: SiteId,
            mediaId: String,
            url: String,
            title: String,
            coverUrl: String,
            offlineOnly: Boolean,
            queueToken: String,
        ) {
            lastQueueVideo = QueueVideoCall(siteId, mediaId, offlineOnly, queueToken)
        }

        override fun openTagFeed(siteId: SiteId, tag: com.raven.app.core.model.TagRef) = Unit
        override fun openAuthorFeed(siteId: SiteId, author: com.raven.app.core.model.AuthorRef) = Unit
        override fun openTagSearch(tag: com.raven.app.core.model.TagRef, crossSite: Boolean, sourceSiteId: SiteId?) = Unit
        override fun openAuthorSearch(
            author: com.raven.app.core.model.AuthorRef,
            crossSite: Boolean,
            sourceSiteId: SiteId?,
        ) = Unit
        override fun openSiteAccount(siteId: SiteId) = Unit
        override fun setSystemBarsVisible(visible: Boolean) = Unit
        override fun setReaderBrightness(value: Float) = Unit
        override fun setKeepScreenOn(enabled: Boolean) = Unit
        override fun shareFiles(uris: List<android.net.Uri>, mimeType: String, subject: String) = Unit
        override fun navigateBack() = Unit
    }

    @Test
    fun `video queue item keeps offline flag and queue token`() {
        val navigator = RecordingNavigator()
        val video = MediaItem(
            siteId = SiteId.IWARA,
            id = "v-queue-1",
            url = "https://iwara.example/video/v-queue-1",
            kind = MediaKind.VIDEO,
            title = "Queue Video",
        )

        navigator.openQueueItem(item = video, queueToken = "token-xyz", offlineOnly = true)

        val call = navigator.lastQueueVideo
        assertEquals("v-queue-1", call?.mediaId)
        assertEquals(true, call?.offlineOnly)
        assertEquals("token-xyz", call?.queueToken)
        // The plain openVideo path (which drops both params) must not be used for queue videos.
        assertEquals(null, navigator.lastVideo)
    }

    @Test
    fun `gallery queue item keeps offline flag and queue token`() {
        val navigator = RecordingNavigator()
        val gallery = MediaItem(
            siteId = SiteId.NHENTAI,
            id = "g-queue-2",
            url = "https://nhentai.example/g/g-queue-2",
            kind = MediaKind.COMIC,
            title = "Queue Gallery",
        )

        navigator.openQueueItem(item = gallery, queueToken = "token-abc", offlineOnly = false)

        val call = navigator.lastReader
        assertEquals("g-queue-2", call?.mediaId)
        assertEquals(false, call?.offlineOnly)
        assertEquals("token-abc", call?.queueToken)
    }
}
