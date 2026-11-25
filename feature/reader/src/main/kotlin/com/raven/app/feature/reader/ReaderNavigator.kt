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

    fun openTagFeed(
        siteId: SiteId,
        tag: TagRef,
    )

    fun openAuthorFeed(
        siteId: SiteId,
        author: AuthorRef,
    )

    fun openSiteAccount(siteId: SiteId)

    fun setSystemBarsVisible(visible: Boolean)

    /**
     * Adjusts reader window brightness: negative value (`-1f`) follows system brightness;
     * values in `0f..1f` set explicit screen brightness override.
     */
    fun setReaderBrightness(value: Float)

    fun setKeepScreenOn(enabled: Boolean)

    fun launchSaveDocument(
        suggestedFileName: String,
        mimeType: String,
        onResult: (Uri?) -> Unit,
    )

    fun launchOpenDocumentTree(
        onResult: (Uri?) -> Unit,
    )

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
        openVideo(
            siteId = item.siteId,
            mediaId = item.id,
            url = item.url,
            title = item.title,
            coverUrl = item.coverUrl,
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
