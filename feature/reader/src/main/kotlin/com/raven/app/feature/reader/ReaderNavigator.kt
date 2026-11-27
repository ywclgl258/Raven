package com.raven.app.feature.reader

import android.net.Uri
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef

/**
 * Decoupled navigation and host-window callback contract for `:feature:reader`.
 *
 * `:app` wires this to Type-Safe Navigation Compose (`NavController`), `PlayerActivity`,
 * `WindowInsetsControllerCompat`, Activity window brightness / `FLAG_KEEP_SCREEN_ON`,
 * Activity Result SAF contracts, and Android share sheets without any feature-to-feature coupling.
 */
interface ReaderNavigator {
    fun openReader(
        siteId: SiteId,
        mediaId: String,
        url: String,
        startPage: Int = 1,
        offlineOnly: Boolean = false,
        queueToken: String? = null,
    )

    fun openVideo(
        siteId: SiteId,
        mediaId: String,
        url: String,
        title: String = "",
        coverUrl: String = "",
    )

    /**
     * Mixed gallery→video queue hand-off. Defaults keep single-item hosts source-compatible; a
     * queue-aware host overrides this to preserve [offlineOnly] and [queueToken] so a video reached
     * from a gallery queue stays offline and can continue along the same queue instead of reloading
     * online metadata.
     */
    fun openQueueVideo(
        siteId: SiteId,
        mediaId: String,
        url: String,
        title: String,
        coverUrl: String,
        offlineOnly: Boolean,
        queueToken: String,
    ) {
        openVideo(
            siteId = siteId,
            mediaId = mediaId,
            url = url,
            title = title,
            coverUrl = coverUrl,
        )
    }

    fun openTagFeed(
        siteId: SiteId,
        tag: TagRef,
    )

    fun openAuthorFeed(
        siteId: SiteId,
        author: AuthorRef,
    )

    /**
     * Opens unified Search pre-filled with the tag's canonical slug (raw label fallback),
     * either on the active site or across all searchable sites.
     *
     * [sourceSiteId] is the site that owns the tag. Single-site search must target it (not the
     * ambient active site) so a gallery opened from the library still searches its own site.
     */
    fun openTagSearch(
        tag: TagRef,
        crossSite: Boolean,
        sourceSiteId: SiteId? = null,
    )

    /**
     * Opens unified Search pre-filled with the author's display name (id fallback).
     * Gallery Detail author taps use this instead of the site-locked [openAuthorFeed].
     */
    fun openAuthorSearch(
        author: AuthorRef,
        crossSite: Boolean,
        sourceSiteId: SiteId? = null,
    )

    fun openSiteAccount(siteId: SiteId)

    fun setSystemBarsVisible(visible: Boolean)

    /**
     * Adjusts reader window brightness: negative value (`-1f`) follows system brightness;
     * values in `0f..1f` set explicit screen brightness override.
     */
    fun setReaderBrightness(value: Float)

    fun setKeepScreenOn(enabled: Boolean)

    fun shareFiles(
        uris: List<Uri>,
        mimeType: String,
        subject: String = "",
    )

    fun navigateBack()

    /**
     * Optional navigation hook for opening related galleries from Gallery Detail.
     * Defaults to no-op so hosts implementing the core 12 callbacks remain source-compatible.
     */
    fun openGallery(
        siteId: SiteId,
        mediaId: String,
        url: String,
        offlineOnly: Boolean = false,
        queueToken: String? = null,
    ) = Unit

    /**
     * Optional custom `FileProvider` authority for image sharing.
     * When `null`, `:feature:reader` defaults to `"${context.packageName}.fileprovider"`.
     */
    val fileProviderAuthority: String?
        get() = null
}

/**
 * Opens a [MediaItem] from a sequential reading/playback queue according to its [MediaItem.kind]
 * with zero site-ID branching.
 */
fun ReaderNavigator.openQueueItem(
    item: MediaItem,
    queueToken: String,
    offlineOnly: Boolean,
) {
    if (item.kind.isVideo) {
        // Use the queue-aware overload so the video inherits the offline flag and queue token the
        // gallery branch passes. The plain `openVideo` drops both and would re-query online data.
        openQueueVideo(
            siteId = item.siteId,
            mediaId = item.id,
            url = item.url,
            title = item.title,
            coverUrl = item.coverUrl,
            offlineOnly = offlineOnly,
            queueToken = queueToken,
        )
    } else {
        openReader(
            siteId = item.siteId,
            mediaId = item.id,
            url = item.url,
            startPage = 1,
            offlineOnly = offlineOnly,
            queueToken = queueToken,
        )
    }
}
