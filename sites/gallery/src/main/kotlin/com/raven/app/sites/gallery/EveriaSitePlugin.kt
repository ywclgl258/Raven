package com.raven.app.sites.gallery

import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin

/**
 * Everia (`https://everia.club`) photo-set archive plugin built declaratively on [WordPressPhotoEngine].
 *
 * Key protocol invariants:
 * - Dated WordPress post paths `/YYYY/MM/DD/<slug>[/<page>]/` with multi-page `div.page-links`.
 * - External image CDNs rotate per post (`fujibox.top`, `karubox.top`, etc.), so BOTH
 *   `SiteNetworkProfile.mediaDomains` and `allowedUrlSubstrings` stay intentionally empty;
 *   scoping images to `figure img` excludes site logos and author avatars cleanly.
 */
class EveriaSitePlugin(
    httpClient: HttpClientAdapter,
) : SitePlugin {

    internal val engine: WordPressPhotoEngine = WordPressPhotoEngine(
        config = createConfig(),
        httpClient = httpClient,
    )

    override val descriptor: SiteDescriptor = engine.descriptor
    override val feedSource: FeedSource = engine
    override val gallerySource: GallerySource = engine
    override val searchSource: SearchSource = engine
    override val linkResolver: LinkResolver = engine

    companion object {
        private val DATED_POST_PATH_REGEX = Regex("^/(\\d{4}/\\d{2}/\\d{2}/[^/]+?)(?:/\\d+)?/?$")
        private val TITLE_SUFFIX_REGEX = Regex("\\s*[-|–—]\\s*Everia(?:\\.club)?\\s*$", RegexOption.IGNORE_CASE)

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.EVERIA,
            displayName = "Everia",
            shortName = "Everia",
            primaryKind = MediaKind.PHOTO,
            supportedKinds = setOf(MediaKind.PHOTO),
            defaultHost = "everia.club",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
            ),
            feedSorts = listOf(FeedSort.NEWEST),
            sections = listOf(
                SiteSectionSpec(
                    id = "latest",
                    title = "最新更新",
                    subtitle = "刚发布的写真",
                    defaultSort = FeedSort.NEWEST,
                    mediaKind = MediaKind.PHOTO,
                ),
            ),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                referer = "https://everia.club/",
                mediaReferer = "https://everia.club/",
                // External image CDN hosts rotate per post (fujibox.top, karubox.top, ...).
                mediaDomains = emptySet(),
            ),
        )

        internal fun createConfig(): PhotoSiteConfig = PhotoSiteConfig(
            descriptor = DESCRIPTOR,
            routeScheme = PhotoRouteScheme.WordPressPath(),
            postUrlRule = PostUrlRule(
                pathRegex = DATED_POST_PATH_REGEX,
                canonicalBuilder = { baseUrl, match -> "$baseUrl/${match.groupValues[1]}/" },
                postIdExtractor = { match -> match.groupValues[1].substringAfterLast('/') },
                contentPageUrlBuilder = { canonical, page ->
                    if (page <= 1) canonical else "${canonical.trimEnd('/')}/$page/"
                },
            ),
            cardSpec = CardSpec(
                itemSelectors = listOf("div.rt-holder"),
                linkSelectors = listOf("h3.entry-title a[href]"),
                thumbSelectors = listOf("img.rt-img-responsive", "img"),
                dateSelector = "time",
            ),
            imageSpec = ImageExtractionSpec.DomImages(
                containerSelectors = listOf("div.entry-content", "article"),
                imageSelector = "figure img",
                allowedUrlSubstrings = emptySet(),
                preferAnchorHref = true,
                contentPageLinkSelector = "div.page-links span.post-page-numbers, div.page-links a.post-page-numbers",
            ),
            metadataSpec = DetailMetadataSpec(
                titleSuffixRegex = TITLE_SUFFIX_REGEX,
            ),
        )
    }
}
