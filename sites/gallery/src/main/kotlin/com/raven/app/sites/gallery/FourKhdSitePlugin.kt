package com.raven.app.sites.gallery

import com.raven.app.core.model.FeedRequest
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
import org.jsoup.nodes.Element

/**
 * 4KHD (`https://www.4khd.com`) photo-set archive plugin built declaratively on [WordPressPhotoEngine].
 *
 * Key protocol invariants:
 * - Post path `/content/<yearBucket>/<slug>.html[/<page>]`: stable post ID is `<slug>` (never `<yearBucket>`).
 * - Listings/categories paginate via `?query-3-page=N`; search uses `/search/<q>/page/N`.
 * - Content pages flatten via stateless `#flatIndex-contentPage-offset` addressing.
 * - Images are filtered to `pic.4khd.com` / `/4KHD-beautifulGirls` (Jetpack CDN `i0.wp.com/pic.4khd.com/...`).
 * - Passive Cloudflare JS detection script does not trigger challenge false-positives.
 */
class FourKhdSitePlugin(
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
        private val CONTENT_PATH_REGEX = Regex("^/content/(\\d+)/([^/]+)\\.html(?:/(\\d+))?/?$")
        private val TITLE_SUFFIX_REGEX = Regex("\\s*[-|–—]\\s*4KHD\\s*$", RegexOption.IGNORE_CASE)
        private val DIGITS_REGEX = Regex("(\\d[\\d,]*)")
        private const val DEFAULT_COVER_ASPECT_RATIO = 850f / 1158f

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.FOURKHD,
            displayName = "4KHD",
            shortName = "4KHD",
            primaryKind = MediaKind.PHOTO,
            supportedKinds = setOf(MediaKind.PHOTO),
            defaultHost = "www.4khd.com",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
            ),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = listOf(
                SiteSectionSpec(
                    id = "latest",
                    title = "最新更新",
                    subtitle = "刚发布的写真",
                    defaultSort = FeedSort.NEWEST,
                    mediaKind = MediaKind.PHOTO,
                ),
                SiteSectionSpec(
                    id = "popular",
                    title = "热门写真",
                    defaultSort = FeedSort.POPULAR,
                    mediaKind = MediaKind.PHOTO,
                ),
                SiteSectionSpec(
                    id = "cosplay",
                    title = "Cosplay",
                    category = "cosplay",
                    defaultSort = FeedSort.NEWEST,
                    mediaKind = MediaKind.PHOTO,
                ),
                SiteSectionSpec(
                    id = "album",
                    title = "写真集",
                    category = "album",
                    defaultSort = FeedSort.NEWEST,
                    mediaKind = MediaKind.PHOTO,
                ),
            ),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                referer = "https://www.4khd.com/",
                mediaReferer = "https://www.4khd.com/",
                mediaDomains = setOf("i0.wp.com", "pic.4khd.com"),
            ),
        )

        internal fun createConfig(): PhotoSiteConfig = PhotoSiteConfig(
            descriptor = DESCRIPTOR,
            routeScheme = PhotoRouteScheme.QueryParamWithPathSearch(
                pageQueryParam = "query-3-page",
                popularPath = "/pages/popular",
                categoryPathPrefix = "/pages/",
                searchPathPrefix = "/search/",
            ),
            postUrlRule = PostUrlRule(
                pathRegex = CONTENT_PATH_REGEX,
                canonicalBuilder = { baseUrl, match ->
                    "$baseUrl/content/${match.groupValues[1]}/${match.groupValues[2]}.html"
                },
                postIdExtractor = { match -> match.groupValues[2] },
                contentPageUrlBuilder = { canonical, page ->
                    if (page <= 1) canonical else "$canonical/$page"
                },
                reservedFirstSegments = emptySet(),
            ),
            cardSpec = CardSpec(
                itemSelectors = listOf("li.wp-block-post", "li.post"),
                linkSelectors = listOf("h2.wp-block-post-title a[href]", "a[href*=/content/]"),
                thumbSelectors = listOf("figure img", "img"),
                dateSelector = "time",
                viewsExtractor = ::extractEyeViews,
                defaultAspectRatio = DEFAULT_COVER_ASPECT_RATIO,
                pagerLinkSelector = "a.page-numbers[href]",
                totalPagesExtractor = { doc ->
                    doc.select("a.page-numbers[href]")
                        .mapNotNull { it.text().trim().replace(",", "").toIntOrNull() }
                        .filter { it > 0 }
                        .maxOrNull()
                },
            ),
            imageSpec = ImageExtractionSpec.DomImages(
                containerSelectors = listOf("div.entry-content"),
                imageSelector = "img",
                allowedUrlSubstrings = setOf("pic.4khd.com", "/4KHD-beautifulGirls"),
                preferAnchorHref = true,
                contentPageLinkSelector = "ul.page-links li.numpages",
            ),
            metadataSpec = DetailMetadataSpec(
                titleSuffixRegex = TITLE_SUFFIX_REGEX,
                defaultAspectRatio = DEFAULT_COVER_ASPECT_RATIO,
                descriptionBuilder = { _, total, pages -> "共 $total 张 · $pages 个内容分页" },
            ),
            sortsForRequest = ::supportedSortsFor,
        )

        private fun supportedSortsFor(request: FeedRequest): List<FeedSort> = when {
            !request.query.isNullOrBlank() || !request.category.isNullOrBlank() -> listOf(FeedSort.NEWEST)
            request.section == "popular" -> listOf(FeedSort.POPULAR)
            !request.section.isNullOrBlank() && request.section != "latest" -> listOf(FeedSort.NEWEST)
            else -> listOf(FeedSort.NEWEST, FeedSort.POPULAR)
        }

        private fun extractEyeViews(card: Element): Long? {
            val icon = card.selectFirst(".eyeicon") ?: return null
            val siblingDigits = icon.nextSibling()?.toString()?.let { DIGITS_REGEX.find(it)?.groupValues?.get(1) }
            val ownParentDigits = icon.parent()?.ownText()?.let { DIGITS_REGEX.find(it)?.groupValues?.get(1) }
            val fallbackDigits = icon.parent()?.text()?.let { DIGITS_REGEX.find(it)?.groupValues?.get(1) }
            val raw = siblingDigits ?: ownParentDigits ?: fallbackDigits ?: return null
            return raw.replace(",", "").toLongOrNull()
        }
    }
}
