package com.raven.app.feature.browse

import com.raven.app.core.model.DirectoryEntry
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.isSerialChapter
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SitePlugin

/**
 * Decoupled navigation callback contract for `:feature:browse`.
 * `:app` wires this to Type-Safe Navigation Compose (`NavController`) and `PlayerActivity`
 * without introducing any feature-to-feature module dependencies.
 */
interface BrowseNavigator {
    fun openVideo(
        siteId: SiteId,
        mediaId: String,
        url: String,
        title: String = "",
        coverUrl: String = "",
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
    )

    fun openSiteFeed(route: RavenRoute.SiteFeed)

    fun openSiteDirectory(siteId: SiteId, section: String)

    fun openSiteFunctions(siteId: SiteId)

    fun openSearch(
        initialQuery: String? = null,
        crossSite: Boolean = false,
    )

    fun openSiteAccount(siteId: SiteId)

    fun openBlockedTerms(siteId: SiteId)

    fun openFavorites(siteId: SiteId? = null)

    fun openHistory()

    fun openDownloads(siteId: SiteId? = null)

    fun openSubscriptions(siteId: SiteId? = null)

    fun openSettings()

    fun navigateBack()

    /**
     * Routes a [MediaItem] cleanly by its [MediaItem.kind], supporting dual-media sites
     * (e.g. Iwara videos & image galleries, Cosplaytele photo galleries & embedded videos)
     * with zero site-ID branching.
     */
    fun openMedia(item: MediaItem) {
        if (item.kind.isVideo) {
            openVideo(
                siteId = item.siteId,
                mediaId = item.id,
                url = item.url,
                title = item.title,
                coverUrl = item.coverUrl,
            )
        } else if (item.isSerialChapter()) {
            // 连载的一话本身就是可读内容，列表点击直接开读，不再多一层单话详情。
            openReader(
                siteId = item.siteId,
                mediaId = item.id,
                url = item.url,
                startPage = 1,
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
 * Converts a plugin [FeedRequest] into a Type-Safe [RavenRoute.SiteFeed].
 *
 * Carries the request's sort, starting page, media kind and include-tag set so share-link feeds
 * (e.g. an Iwara gallery search) land on the requested list instead of page 1 of the default feed.
 */
fun FeedRequest.toSiteFeedRoute(title: String? = null): RavenRoute.SiteFeed = RavenRoute.SiteFeed(
    siteId = siteId.value,
    section = section,
    category = category,
    query = query,
    scope = scope.name,
    title = title,
    sort = sort.takeUnless { it == FeedSort.DEFAULT }?.name,
    page = page.takeIf { it > 1 },
    mediaKind = mediaKind?.name,
    includeTags = advancedFilters.includeTags.distinct(),
)

/**
 * Navigates to a resolved [ResolvedLink] cleanly via [BrowseNavigator].
 */
fun BrowseNavigator.openResolvedLink(
    link: ResolvedLink,
    fallbackTitle: String? = null,
) {
    when (link) {
        is ResolvedLink.Feed -> openSiteFeed(link.request.toSiteFeedRoute(title = fallbackTitle))
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
        is ResolvedLink.Directory -> openSiteDirectory(
            siteId = link.siteId,
            section = link.section,
        )
    }
}

/**
 * Navigates to a [DirectoryEntry], preferring [SitePlugin.linkResolver] when available and
 * falling back to a scoped [RavenRoute.SiteFeed].
 */
fun BrowseNavigator.openDirectoryEntry(
    plugin: SitePlugin?,
    entry: DirectoryEntry,
) {
    val resolved = plugin?.linkResolver?.takeIf { it.canHandle(entry.url) }?.resolve(entry.url)
    if (resolved != null) {
        openResolvedLink(resolved, fallbackTitle = entry.title)
        return
    }
    val isAllScope = entry.kind == SearchScope.ALL
    openSiteFeed(
        RavenRoute.SiteFeed(
            siteId = entry.siteId.value,
            category = if (isAllScope) entry.id else null,
            query = if (isAllScope) null else entry.id,
            scope = entry.kind.name,
            title = entry.title,
        ),
    )
}

/**
 * Navigates cleanly for a [MediaItem] that may represent a directory search jump card
 * (`extra["isDirectoryEntry"] == "true"`).
 */
fun BrowseNavigator.openSearchResultItem(
    plugin: SitePlugin?,
    item: MediaItem,
) {
    if (item.isDirectoryEntryItem()) {
        val rawKind = item.extra["directoryKind"]
        val kind = SearchScope.entries.firstOrNull { it.name.equals(rawKind, ignoreCase = true) }
            ?: SearchScope.ALL
        openDirectoryEntry(
            plugin = plugin,
            entry = DirectoryEntry(
                siteId = item.siteId,
                id = item.id,
                title = item.title,
                url = item.url,
                kind = kind,
                coverUrl = item.coverUrl,
            ),
        )
    } else {
        openMedia(item)
    }
}

fun MediaItem.isDirectoryEntryItem(): Boolean =
    extra["isDirectoryEntry"].equals("true", ignoreCase = true)

