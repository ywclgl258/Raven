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
 * 4KUp (`https://4kup.net`) photo-set archive plugin built declaratively on [WordPressPhotoEngine].
 *
 * Key protocol invariants:
 * - GeneratePress single-page WordPress posts (`/<slug>/`) with images hosted on Blogger CDN
 *   (`googleusercontent.com` / `blogger.googleusercontent.com`).
 * - Article `img[src]` uses `data:image/gif` placeholders while full-size images live on
 *   `a.thumb-photo[href]` (with thumbnails on `img[data-src]`), so anchor high-res fallback wins
 *   and gallery cover derives from the first extracted full-size image.
 */
class FourKupSitePlugin(
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
        private val POST_PATH_REGEX = Regex("^/([^/]+?)(?:/\\d+)?/?$")
        private val TITLE_SUFFIX_REGEX = Regex("\\s*[-|–—]\\s*4kUp(?:\\.net)?\\s*$", RegexOption.IGNORE_CASE)

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.FOURKUP,
            displayName = "4KUp",
            shortName = "4KUp",
            primaryKind = MediaKind.PHOTO,
            supportedKinds = setOf(MediaKind.PHOTO),
            defaultHost = "4kup.net",
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
            searchScopes = listOf(SearchScope.ALL, SearchScope.TAG),
            networkProfile = SiteNetworkProfile(
                referer = "https://4kup.net/",
                mediaReferer = "https://4kup.net/",
                mediaDomains = setOf("blogger.googleusercontent.com"),
            ),
        )

        internal fun createConfig(): PhotoSiteConfig = PhotoSiteConfig(
            descriptor = DESCRIPTOR,
            routeScheme = PhotoRouteScheme.WordPressPath(),
            postUrlRule = PostUrlRule(
                pathRegex = POST_PATH_REGEX,
                canonicalBuilder = { baseUrl, match -> "$baseUrl/${match.groupValues[1]}/" },
                postIdExtractor = { match -> match.groupValues[1] },
                contentPageUrlBuilder = { canonical, page ->
                    if (page <= 1) canonical else "${canonical.trimEnd('/')}/$page/"
                },
            ),
            cardSpec = CardSpec(
                itemSelectors = listOf("div.generate-columns-container article"),
                linkSelectors = listOf("h2.entry-title a[href]"),
                thumbSelectors = listOf("div.post-thumbnail img", "img"),
                dateSelector = "time.entry-date",
            ),
            imageSpec = ImageExtractionSpec.DomImages(
                containerSelectors = listOf("div#gallery", "div.entry-content", "article"),
                imageSelector = "a.thumb-photo[href], img",
                allowedUrlSubstrings = setOf("googleusercontent.com"),
                preferAnchorHref = true,
            ),
            metadataSpec = DetailMetadataSpec(
                titleSuffixRegex = TITLE_SUFFIX_REGEX,
            ),
        )
    }
}
