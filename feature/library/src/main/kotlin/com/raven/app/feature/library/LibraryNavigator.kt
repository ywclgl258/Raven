package com.raven.app.feature.library

import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.isSerialChapter
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SitePlugin

/**
 * Decoupled navigation callback contract for `:feature:library`.
 * `:app` wires this to Type-Safe Navigation Compose (`NavController`) and `PlayerActivity`
 * without introducing any feature-to-feature module dependencies.
 */
interface LibraryNavigator {
    fun openVideo(
        siteId: SiteId,
        mediaId: String,
        url: String,
        title: String = "",
        coverUrl: String = "",
        offlineOnly: Boolean = false,
        queueToken: String? = null,
    )

    fun openGallery(
        siteId: SiteId,
        mediaId: String,
        url: String,
    )

    fun openReader(
        siteId: SiteId,
        mediaId: String,
        url: String,
        startPage: Int = 1,
        offlineOnly: Boolean = false,
        queueToken: String? = null,
    )

    fun openSiteFeed(route: RavenRoute.SiteFeed)

    fun openSiteDirectory(siteId: SiteId, section: String)

    fun openSiteFunctions(siteId: SiteId)

    /**
     * Opens the per-site account/authentication surface for the active site.
     */
    fun openSiteAccount(siteId: SiteId)

    fun openSearch(
        initialQuery: String? = null,
        crossSite: Boolean = false,
        sourceSiteId: SiteId? = null,
    )

    fun openShelfDetail(shelfId: String)

    fun openSmartShelfDetail(smartShelfId: String)

    fun openDownloads(siteId: SiteId? = null)

    fun openFavorites(siteId: SiteId? = null)

    fun openStats()

    fun openHistory()

    fun openImageFavorites()

    fun openSubscriptions(siteId: SiteId? = null)

    fun openGlobalInbox()

    fun openInboxItem(entry: UpdateInboxItem) {
        val item = entry.item
        if (entry.sourceKind == InboxSourceKind.SERIES_FOLLOW && item.kind.isGallery) {
            openReader(siteId = item.siteId, mediaId = item.id, url = item.url)
        } else {
            openMedia(item)
        }
    }

    fun openLocalLibrary()

    fun openSettings()

    fun navigateBack()

    /**
     * Routes a [MediaItem] cleanly by its [MediaItem.kind], supporting dual-media sites,
     * local books, offline-only mode, and sequential queue playback with zero site-ID branching.
     */
    fun openMedia(
        item: MediaItem,
        offlineOnly: Boolean = false,
        queueToken: String? = null,
        startPage: Int = 1,
    ) {
        if (item.kind.isVideo) {
            openVideo(
                siteId = item.siteId,
                mediaId = item.id,
                url = item.url,
                title = item.title,
                coverUrl = item.coverUrl,
                offlineOnly = offlineOnly || item.siteId.isLocal,
                queueToken = queueToken,
            )
        } else if (offlineOnly || queueToken != null || item.siteId.isLocal || startPage > 1) {
            openReader(
                siteId = item.siteId,
                mediaId = item.id,
                url = item.url,
                startPage = startPage.coerceAtLeast(1),
                offlineOnly = offlineOnly || item.siteId.isLocal,
                queueToken = queueToken,
            )
        } else if (item.isSerialChapter()) {
            // 连载的一话本身就是可读内容，列表点击直接开读，不再多一层单话详情。
            openReader(
                siteId = item.siteId,
                mediaId = item.id,
                url = item.url,
                startPage = 1,
                offlineOnly = item.siteId.isLocal,
            )
        } else {
            openGallery(
                siteId = item.siteId,
                mediaId = item.id,
                url = item.url,
            )
        }
    }
}

/**
 * Opens the "all continue" destination for [ProfileScreen]: prefers the user's existing UNFINISHED
 * smart shelf when present, otherwise falls back to the history screen.
 */
fun LibraryNavigator.openProfileContinueAll(unfinishedSmartShelfId: String?) {
    if (!unfinishedSmartShelfId.isNullOrBlank()) {
        openSmartShelfDetail(unfinishedSmartShelfId)
    } else {
        openHistory()
    }
}

/**
 * Opens the active site's account surface only when the site exposes an auth provider.
 */
fun LibraryNavigator.openProfileSiteAccount(
    activeSite: SiteDescriptor?,
    supportsAccount: Boolean,
) {
    if (!supportsAccount) return
    val siteId = activeSite?.id ?: return
    openSiteAccount(siteId)
}

/**
 * Converts a plugin [FeedRequest] into a Type-Safe [RavenRoute.SiteFeed].
 */
fun FeedRequest.toLibrarySiteFeedRoute(title: String? = null): RavenRoute.SiteFeed = RavenRoute.SiteFeed(
    siteId = siteId.value,
    section = section,
    category = category,
    query = query,
    scope = scope.name,
    title = title,
)

/**
 * Navigates to a [ResolvedLink] resolved via `SiteRegistry.resolveLinkFromText`.
 */
fun LibraryNavigator.openResolvedLink(
    link: ResolvedLink,
    fallbackTitle: String? = null,
) {
    when (link) {
        is ResolvedLink.Feed -> openSiteFeed(link.request.toLibrarySiteFeedRoute(title = fallbackTitle))
        is ResolvedLink.Video -> openVideo(
            siteId = link.siteId,
            mediaId = link.id,
            url = link.canonicalUrl,
            title = fallbackTitle.orEmpty(),
        )
        is ResolvedLink.Gallery -> {
            val page = link.pageIndex
            if (page != null && page > 0) {
                openReader(
                    siteId = link.siteId,
                    mediaId = link.id,
                    url = link.canonicalUrl,
                    startPage = page,
                )
            } else {
                openGallery(
                    siteId = link.siteId,
                    mediaId = link.id,
                    url = link.canonicalUrl,
                )
            }
        }
        is ResolvedLink.Directory -> openSiteDirectory(link.siteId, link.section)
    }
}

/**
 * Opens a [FavoriteItem] cleanly according to its [FavoriteKind] and optional [SitePlugin.linkResolver].
 */
fun LibraryNavigator.openFavorite(
    item: FavoriteItem,
    plugin: SitePlugin? = null,
) {
    when (item.kind) {
        FavoriteKind.VIDEO -> openVideo(
            siteId = item.siteId,
            mediaId = item.targetId,
            url = item.url,
            title = item.title,
            coverUrl = item.coverUrl,
            offlineOnly = item.siteId.isLocal,
        )
        FavoriteKind.COMIC, FavoriteKind.PHOTO -> {
            if (item.siteId.isLocal) {
                openReader(
                    siteId = item.siteId,
                    mediaId = item.targetId,
                    url = item.url,
                    startPage = 1,
                    offlineOnly = true,
                )
            } else {
                openGallery(
                    siteId = item.siteId,
                    mediaId = item.targetId,
                    url = item.url,
                )
            }
        }
        FavoriteKind.TAG, FavoriteKind.AUTHOR, FavoriteKind.CHARACTER -> {
            val resolved = plugin?.linkResolver
                ?.takeIf { item.url.isNotBlank() && it.canHandle(item.url) }
                ?.resolve(item.url)
            if (resolved != null) {
                openResolvedLink(resolved, fallbackTitle = item.title)
                return
            }
            val scope = when (item.kind) {
                FavoriteKind.TAG -> SearchScope.TAG
                FavoriteKind.AUTHOR -> SearchScope.AUTHOR
                FavoriteKind.CHARACTER -> SearchScope.CHARACTER
                else -> SearchScope.ALL
            }
            openSiteFeed(
                RavenRoute.SiteFeed(
                    siteId = item.siteId.value,
                    query = item.targetId,
                    scope = scope.name,
                    title = item.title,
                ),
            )
        }
    }
}

fun LibraryNavigator.openWatchProgress(progress: WatchProgress) {
    openVideo(
        siteId = progress.siteId,
        mediaId = progress.mediaId,
        url = progress.url,
        title = progress.title,
        coverUrl = progress.coverUrl,
        offlineOnly = progress.siteId.isLocal,
    )
}

fun LibraryNavigator.openReadingProgress(progress: ReadingProgress) {
    openReader(
        siteId = progress.siteId,
        mediaId = progress.mediaId,
        url = progress.url,
        startPage = progress.currentPage.coerceAtLeast(1),
        offlineOnly = progress.siteId.isLocal,
    )
}

