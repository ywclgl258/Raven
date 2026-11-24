package com.raven.app.sites.gallery

import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.HtmlUrlCleaner

/**
 * Stateless multi-page gallery addressing for photo-set archives.
 *
 * Every flat gallery page carries `<contentPageUrl>#<flatIndex>-<contentPage>-<offset>`:
 * - `flatIndex`: 1-based global page index in the photo book (`1..totalPages`).
 * - `contentPage`: 1-based article pagination index (`1..contentPageCount`).
 * - `offset`: 0-based image index within `contentPage`.
 *
 * Because all three coordinates travel inside the URL fragment, readers and downloaders can
 * deterministically resolve any sparse page after a process restart without caching page sizes.
 * If the remote site shifts images across content pages, [resolveImage] tolerates up to
 * [MAX_DRIFT_PAGES] (4) forward content pages while preserving the original page identity.
 */
data class PhotoPageIdentity(
    val canonicalUrl: String,
    val flatIndex: Int,
    val contentPage: Int,
    val offset: Int,
)

object PhotoPageAddress {
    const val MAX_DRIFT_PAGES: Int = 4

    private val fragmentRegex = Regex("^(\\d+)-(\\d+)-(\\d+)$")

    fun encode(
        contentPageUrl: String,
        flatIndex: Int,
        contentPage: Int,
        offset: Int,
    ): String {
        require(flatIndex > 0) { "flatIndex must be >= 1, was $flatIndex" }
        require(contentPage > 0) { "contentPage must be >= 1, was $contentPage" }
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        val base = HtmlUrlCleaner.stripFragment(contentPageUrl)
        return "$base#$flatIndex-$contentPage-$offset"
    }

    /**
     * Decodes a stateless `#flatIndex-contentPage-offset` URL, verifying strict HTTPS and site host ownership.
     */
    fun decode(
        descriptor: SiteDescriptor,
        rawUrl: String,
        canonicalizePostUrl: (String) -> String?,
    ): PhotoPageIdentity? {
        val uri = GalleryUrlUtils.parseUri(rawUrl) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.rawUserInfo.isNullOrEmpty()) return null
        val host = uri.host?.lowercase() ?: return null
        if (!descriptor.matchesHost(host)) return null

        val canonical = canonicalizePostUrl(rawUrl) ?: return null
        val fragment = uri.rawFragment.orEmpty()
        val match = fragmentRegex.matchEntire(fragment) ?: return null
        val flatIndex = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val contentPage = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val offset = match.groupValues[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return PhotoPageIdentity(
            canonicalUrl = canonical,
            flatIndex = flatIndex,
            contentPage = contentPage,
            offset = offset,
        )
    }

    /**
     * Flattens variable-length content pages into deterministic 1-based [GalleryPage] entries.
     * Supports uneven middle or trailing pages without assuming fixed page sizes.
     */
    fun buildPages(
        pageSizes: List<Int>,
        contentPageUrlFor: (contentPage: Int) -> String,
        resolvedImagesByPage: Map<Int, List<String>> = emptyMap(),
        resolvedThumbsByPage: Map<Int, List<String?>> = emptyMap(),
    ): List<GalleryPage> {
        require(pageSizes.isNotEmpty() && pageSizes.all { it > 0 }) {
            "Each content page must contain at least one image"
        }
        return buildList(pageSizes.sum()) {
            pageSizes.forEachIndexed { zeroBasedPage, count ->
                val contentPage = zeroBasedPage + 1
                val basePageUrl = contentPageUrlFor(contentPage)
                val pageImages = resolvedImagesByPage[contentPage]
                val pageThumbs = resolvedThumbsByPage[contentPage]
                repeat(count) { offset ->
                    val flatIndex = size + 1
                    val imageUrl = pageImages?.getOrNull(offset).orEmpty()
                    val thumbCandidate = pageThumbs?.getOrNull(offset)?.takeIf {
                        it.isNotBlank() && it != imageUrl
                    }
                    add(
                        GalleryPage(
                            index = flatIndex,
                            pageUrl = encode(basePageUrl, flatIndex, contentPage, offset),
                            imageUrl = imageUrl,
                            thumbUrl = thumbCandidate,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Resolves a single page's image URL with up to [MAX_DRIFT_PAGES] forward drift tolerance.
     * Keeps [identity.flatIndex] and [rawPageUrl] intact even when the image has shifted to a later page.
     */
    suspend fun resolveImage(
        identity: PhotoPageIdentity,
        rawPageUrl: String,
        siteId: SiteId,
        loadContentPage: suspend (canonicalUrl: String, contentPage: Int) -> List<String>,
    ): GalleryPage {
        var currentPage = identity.contentPage
        var currentOffset = identity.offset
        repeat(MAX_DRIFT_PAGES) {
            val images = loadContentPage(identity.canonicalUrl, currentPage)
            if (images.isEmpty()) {
                throw SiteException.ParseError(
                    message = "Content page $currentPage returned no images for ${identity.canonicalUrl}",
                    siteId = siteId,
                )
            }
            if (currentOffset in images.indices) {
                return GalleryPage(
                    index = identity.flatIndex,
                    pageUrl = rawPageUrl,
                    imageUrl = images[currentOffset],
                )
            }
            if (currentOffset < 0) {
                throw SiteException.ParseError(
                    message = "Invalid image offset $currentOffset for ${identity.canonicalUrl}",
                    siteId = siteId,
                )
            }
            currentOffset -= images.size
            currentPage++
        }
        throw SiteException.ParseError(
            message = "Pagination structure drifted beyond $MAX_DRIFT_PAGES content pages for ${identity.canonicalUrl}",
            siteId = siteId,
        )
    }
}
