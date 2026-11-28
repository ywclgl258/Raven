package com.raven.app.core.model

import kotlinx.serialization.Serializable

/**
 * Type-Safe Navigation Compose route contract shared across `:core:designsystem`, all `:feature:*`, and `:app`.
 * Every site-scoped route carries an explicit `siteId` string (matching [SiteId.value]).
 */
@Serializable
sealed interface RavenRoute {

    // 5 Primary Top-Level Destinations
    @Serializable
    data object Home : RavenRoute

    @Serializable
    data object Explore : RavenRoute

    @Serializable
    data class Search(
        val initialQuery: String? = null,
        val crossSite: Boolean = false,
        /**
         * Site that originated the search (e.g. the gallery's site when searching a tag/author
         * from a library-opened detail page). `null` means "use the currently active site".
         * Without this, a detail page for site B opened while site A is active would search A.
         */
        val sourceSiteId: String? = null,
    ) : RavenRoute

    @Serializable
    data object Library : RavenRoute

    @Serializable
    data object Profile : RavenRoute

    // Browse & Site Detail Routes
    @Serializable
    data class SiteFeed(
        val siteId: String,
        val section: String? = null,
        val category: String? = null,
        val query: String? = null,
        val scope: String = "ALL",
        val title: String? = null,
        /**
         * Share-link feed fidelity (e.g. Iwara `?tags=...&page=3&sort=...&type=images`).
         * When set, the feed destination starts on this server sort, page, media kind, and
         * include-tag set instead of resetting to page 1 of the site's default video list.
         */
        val sort: String? = null,
        val page: Int? = null,
        val mediaKind: String? = null,
        val includeTags: List<String> = emptyList(),
    ) : RavenRoute

    @Serializable
    data class SiteDirectory(
        val siteId: String,
        val section: String,
    ) : RavenRoute

    @Serializable
    data class SiteFunctions(
        val siteId: String,
    ) : RavenRoute

    @Serializable
    data class ReaderParent(
        val seriesId: String,
        val title: String = "",
        val url: String = "",
    ) : java.io.Serializable

    @Serializable
    data class GalleryDetail(
        val siteId: String,
        val mediaId: String,
        val url: String,
        val offlineOnly: Boolean = false,
        val parent: ReaderParent? = null,
    ) : RavenRoute

    @Serializable
    data class Reader(
        val siteId: String,
        val mediaId: String,
        val url: String,
        val startPage: Int = 1,
        val offlineOnly: Boolean = false,
        val queueToken: String? = null,
        val parent: ReaderParent? = null,
    ) : RavenRoute

    // Library Sub-Routes
    @Serializable
    data class Downloads(
        val siteId: String? = null,
        /** `downloads` 或 `favorites`，用于打开「下载与收藏」的对应分栏。 */
        val initialTab: String = "downloads",
    ) : RavenRoute

    @Serializable
    data class ShelfDetail(
        val shelfId: String,
    ) : RavenRoute

    @Serializable
    data class SmartShelfDetail(
        val smartShelfId: String,
    ) : RavenRoute

    @Serializable
    data class Favorites(
        val siteId: String? = null,
    ) : RavenRoute

    @Serializable
    data object History : RavenRoute

    @Serializable
    data object ImageFavorites : RavenRoute

    @Serializable
    data class Subscriptions(
        val siteId: String? = null,
    ) : RavenRoute

    @Serializable
    data object GlobalInbox : RavenRoute

    @Serializable
    data object LocalLibrary : RavenRoute

    @Serializable
    data object Stats : RavenRoute

    // Settings & Workflow Sub-Routes (zero chained AlertDialogs)
    @Serializable
    data object Settings : RavenRoute

    @Serializable
    data class SiteAccount(
        val siteId: String,
    ) : RavenRoute

    @Serializable
    data class BlockedTerms(
        val siteId: String,
    ) : RavenRoute

    @Serializable
    data object TagDictionary : RavenRoute

    @Serializable
    data object BackupRestore : RavenRoute

    @Serializable
    data object LanSync : RavenRoute

    @Serializable
    data object Feedback : RavenRoute

    @Serializable
    data object StorageMigration : RavenRoute

    @Serializable
    data object DuplicateScanner : RavenRoute
}
