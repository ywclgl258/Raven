package com.raven.app.sites.gallery

import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.DirectoryEntry
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.model.TagRef
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Declarative routing scheme for photo/gallery archives.
 */
sealed interface PhotoRouteScheme {
    /**
     * Standard WordPress `/page/N/` + `/?s=<query>` (Everia, 4KUp, Cosplaytele).
     */
    data class WordPressPath(
        val searchQueryParam: String = "s",
        val popularPath: String? = null,
        val categoryQueryParam: String? = "cat",
    ) : PhotoRouteScheme

    /**
     * WordPress block query pagination (`?query-3-page=N`) with dedicated path-paged search
     * (`/search/<query>/page/N`) because query-string pagination on search returns page 1 (4KHD).
     */
    data class QueryParamWithPathSearch(
        val pageQueryParam: String = "query-3-page",
        val popularPath: String = "/pages/popular",
        val categoryPathPrefix: String = "/pages/",
        val searchPathPrefix: String = "/search/",
    ) : PhotoRouteScheme

    /**
     * Next.js path-segmented archive (`/en/cosplays/N`, `/en/coser/<id>/N`, `/en/character/<id>`,
     * `/en/parody/<id>`, `/en/cosers/N?coserName=<q>`) (GalleryEpic).
     */
    data object GalleryEpicRoutes : PhotoRouteScheme
}

/**
 * Declarative post URL canonicalization, stable ID extraction, and content-page routing.
 */
data class PostUrlRule(
    val pathRegex: Regex,
    val canonicalBuilder: (baseUrl: String, match: MatchResult) -> String,
    val postIdExtractor: (match: MatchResult) -> String,
    val contentPageUrlBuilder: (canonical: String, page: Int) -> String,
    val reservedFirstSegments: Set<String> = DEFAULT_RESERVED_SEGMENTS,
) {
    companion object {
        val DEFAULT_RESERVED_SEGMENTS: Set<String> = setOf(
            "page",
            "category",
            "tag",
            "search",
            "author",
            "feed",
            "comments",
            "wp-admin",
            "wp-json",
            "wp-content",
            "wp-login.php",
            "about",
            "contact",
            "privacy",
            "dmca",
            "faq",
        )
    }
}

/**
 * Declarative feed card scraping rules.
 */
data class CardSpec(
    val itemSelectors: List<String>,
    val linkSelectors: List<String>,
    val thumbSelectors: List<String> = listOf("img"),
    val requireThumbMatch: Boolean = false,
    val dateSelector: String? = "time",
    val dateExtractor: ((Element) -> String?)? = null,
    val viewsExtractor: ((Element) -> Long?)? = null,
    val titleExtractor: ((card: Element, link: Element) -> String)? = null,
    val pageCountExtractor: ((card: Element, title: String) -> Int?)? = null,
    val defaultAspectRatio: Float? = null,
    val pagerLinkSelector: String = "a[href*=page/], a[href*=query-3-page=], a.page-numbers, a.page-number, a[data-paged]",
    val totalPagesExtractor: ((Document) -> Int?)? = null,
)

/**
 * Declarative detail-page image extraction strategy.
 */
sealed interface ImageExtractionSpec {
    /**
     * DOM-based extraction inside the first matching container, with lazy-load attribute resolution,
     * `srcset` highest-res picking, optional parent `<a href>` high-res fallback, and ad/icon filtering.
     */
    data class DomImages(
        val containerSelectors: List<String>,
        val imageSelector: String = "img",
        val strictPrimaryContainer: Boolean = false,
        val excludedContainerSelectors: List<String> = DEFAULT_EXCLUDED_CONTAINERS,
        val allowedUrlSubstrings: Set<String> = emptySet(),
        val preferAnchorHref: Boolean = true,
        val contentPageLinkSelector: String = "",
    ) : ImageExtractionSpec

    /**
     * Next.js RSC Flight payload UUID extraction (GalleryEpic).
     * Reads full image UUID arrays from server payload rather than truncated HTML previews.
     */
    data class NextJsRscUuidPayload(
        val cdnPrefix: String,
    ) : ImageExtractionSpec

    companion object {
        val DEFAULT_EXCLUDED_CONTAINERS: List<String> = listOf(
            ".crp_related",
            ".related-posts",
            ".yarpp-related",
            ".jp-relatedposts",
            ".sharedaddy",
            ".download-box",
            ".infobox",
            "aside",
            "nav",
        )
    }
}

/**
 * Extracted structured metadata from a gallery detail document.
 */
data class ExtractedGalleryMetadata(
    val tags: List<TagRef> = emptyList(),
    val authors: List<AuthorRef> = emptyList(),
    val characters: List<TagRef> = emptyList(),
    val categories: List<TagRef> = emptyList(),
    val fileSizeLabel: String? = null,
)

/**
 * Declarative detail-page metadata extraction rules.
 */
data class DetailMetadataSpec(
    val titleSuffixRegex: Regex? = null,
    val preferH1Title: Boolean = false,
    val preferOgImageCover: Boolean = false,
    val defaultCategoryLabel: String = "写真",
    val defaultAspectRatio: Float? = null,
    val descriptionBuilder: ((doc: Document, totalImages: Int, contentPages: Int) -> String)? = null,
    val customMetadataExtractor: ((doc: Document, baseUrl: String) -> ExtractedGalleryMetadata)? = null,
)

/**
 * Declarative directory support for sites exposing browsable indexes (e.g., GalleryEpic Coser/Series directories).
 */
data class DirectoryConfig(
    val sections: List<SiteSectionSpec>,
    val isDirectorySearch: Boolean = true,
    val urlBuilder: (baseUrl: String, section: String, page: Int, query: String?) -> String?,
    val parser: (doc: Document, descriptor: SiteDescriptor) -> List<DirectoryEntry>,
    val directoryTotalPages: ((doc: Document) -> Int?)? = null,
)

/**
 * Declarative specification folding any photo/gallery site into [WordPressPhotoEngine].
 */
data class PhotoSiteConfig(
    val descriptor: SiteDescriptor,
    val routeScheme: PhotoRouteScheme,
    val postUrlRule: PostUrlRule,
    val cardSpec: CardSpec,
    val imageSpec: ImageExtractionSpec,
    val metadataSpec: DetailMetadataSpec = DetailMetadataSpec(),
    val directoryConfig: DirectoryConfig? = null,
    val sortsForRequest: ((FeedRequest) -> List<FeedSort>)? = null,
    val embeddedVideoExtractor: ((doc: Document, canonicalUrl: String, articleTitle: String, coverUrl: String) -> List<MediaItem>)? = null,
)
