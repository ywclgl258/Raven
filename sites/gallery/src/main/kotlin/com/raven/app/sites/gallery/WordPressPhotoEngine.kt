package com.raven.app.sites.gallery

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DirectoryEntry
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSection
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.DirectorySource
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.JsExtractor
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.LruCache
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SiteRequestKind
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Unified declarative scraping engine for all 5 photo/gallery archives (`4khd`, `everia`, `4kup`,
 * `galleryepic`, `cosplaytele`).
 *
 * Centralizes HTTP document fetching, Cloudflare challenge detection, feed/search routing,
 * lazy-load + `srcset` + `<a href>` image extraction, ad/icon filtering, WP/custom metadata
 * extraction, content-page LRU caching, and stateless `#flatIndex-contentPage-offset` addressing
 * with 4-page drift tolerance.
 */
class WordPressPhotoEngine(
    val config: PhotoSiteConfig,
    private val httpClient: HttpClientAdapter,
    cacheCapacity: Int = 32,
) : FeedSource, GallerySource, SearchSource, LinkResolver {

    val descriptor: SiteDescriptor get() = config.descriptor
    private val siteId get() = descriptor.id

    /** Caches decoded image lists per `(canonicalUrl, contentPage)` to avoid redundant page fetches while reading. */
    private val contentPageCache = LruCache<String, List<String>>(maxEntries = cacheCapacity)
    private val contentThumbCache = LruCache<String, List<String?>>(maxEntries = cacheCapacity)

    val optionalDirectorySource: DirectorySource? = config.directoryConfig?.let { dirConfig ->
        object : DirectorySource {
            override suspend fun loadDirectory(
                section: String,
                page: Int,
                query: String?,
            ): List<DirectoryEntry> = loadDirectoryEntries(dirConfig, section, page, query)

            override fun directorySections(): List<SiteSectionSpec> = dirConfig.sections
        }
    }

    // ---- SearchSource capabilities ----

    override val supportedScopes: Set<SearchScope>
        get() = descriptor.searchScopes.toSet()

    override val isDirectorySearch: Boolean
        get() = config.directoryConfig?.isDirectorySearch == true

    // ---- FeedSource ----

    override fun supportedSorts(request: FeedRequest): List<FeedSort> =
        config.sortsForRequest?.invoke(request) ?: descriptor.feedSorts

    override suspend fun loadHome(language: ComicLanguage): HomeFeed {
        val primary = loadFeed(
            FeedRequest(
                siteId = siteId,
                section = "latest",
                sort = FeedSort.NEWEST,
                language = language,
                page = 1,
            ),
        )
        return HomeFeed(
            siteId = siteId,
            heroItems = primary.items.take(5),
            sections = listOf(
                FeedSection(
                    id = "latest",
                    title = "最新写真",
                    subtitle = "刚发布的写真",
                    items = primary.items,
                    seeAllRequest = FeedRequest(siteId = siteId, section = "latest", page = 1),
                ),
            ),
            primaryFeedPage = primary,
            fromCache = primary.fromCache,
        )
    }

    override suspend fun loadFeed(request: FeedRequest): FeedPage {
        val page = request.page.coerceAtLeast(1)
        val url = resolveFeedUrl(request)
        val (doc, fromCache) = fetchDocument(url)
        return parseFeedDocument(doc, request.copy(page = page), fromCache)
    }

    override suspend fun search(request: FeedRequest): FeedPage {
        val page = request.page.coerceAtLeast(1)
        val normalized = request.copy(
            siteId = siteId,
            page = page,
            query = request.query?.trim(),
        )
        val dirConfig = config.directoryConfig
        if (dirConfig != null && dirConfig.isDirectorySearch && normalized.scope == SearchScope.ALL) {
            val url = resolveFeedUrl(normalized)
            val (doc, fromCache) = fetchDocument(url)
            val entries = dirConfig.parser(doc, descriptor)
            if (entries.isEmpty() && !looksEmpty(doc)) {
                throw SiteException.ParseError(
                    message = "${descriptor.displayName} directory search returned no recognizable entries",
                    siteId = siteId,
                )
            }
            val parsedTotal = dirConfig.directoryTotalPages?.invoke(doc) ?: parseTotalPages(doc)
            val totalPages = parsedTotal?.coerceAtLeast(page) ?: page
            val hasMore = page < totalPages
            return FeedPage(
                items = entries.map { entry ->
                    MediaItem(
                        siteId = siteId,
                        id = entry.id,
                        url = entry.url,
                        kind = descriptor.primaryKind,
                        title = entry.title,
                        coverUrl = entry.coverUrl,
                        extra = mapOf(
                            "isDirectoryEntry" to "true",
                            "directoryKind" to entry.kind.name,
                        ),
                    )
                },
                nextPage = if (hasMore) page + 1 else null,
                hasMore = hasMore,
                totalPages = totalPages,
                title = feedTitle(normalized),
                fromCache = fromCache,
            )
        }
        return loadFeed(normalized)
    }

    // ---- GallerySource ----

    override suspend fun loadGalleryDetail(url: String): GalleryDetail {
        val canonical = canonicalizePostUrl(url)
            ?: throw SiteException.ParseError("Unrecognized ${descriptor.displayName} gallery URL: $url", siteId)
        val firstPageUrl = config.postUrlRule.contentPageUrlBuilder(canonical, 1)
        val (firstDoc, _) = fetchDocument(firstPageUrl)
        val firstExtracted = extractImagesWithThumbs(firstDoc)
        val title = extractDetailTitle(firstDoc)
        val coverUrl = extractDetailCover(firstDoc, firstExtracted.images)
        val embeddedVideos = config.embeddedVideoExtractor
            ?.invoke(firstDoc, canonical, title, coverUrl)
            .orEmpty()

        if (firstExtracted.images.isEmpty() && embeddedVideos.isEmpty()) {
            throw SiteException.ParseError(
                message = "${descriptor.displayName} gallery contains no readable images: $canonical",
                siteId = siteId,
            )
        }

        val contentPageCount = extractContentPageCount(firstDoc)
        val pages: List<GalleryPage>
        if (firstExtracted.images.isNotEmpty()) {
            cacheContentPage(canonical, 1, firstExtracted.images, firstExtracted.thumbs)
            val imagesByPage = LinkedHashMap<Int, List<String>>(contentPageCount)
            val thumbsByPage = LinkedHashMap<Int, List<String?>>(contentPageCount)
            val sizes = ArrayList<Int>(contentPageCount)
            for (contentPage in 1..contentPageCount) {
                val extracted = if (contentPage == 1) {
                    firstExtracted
                } else {
                    loadAndCacheContentPage(canonical, contentPage, forceReload = false)
                }
                if (extracted.images.isEmpty()) {
                    throw SiteException.ParseError(
                        message = "${descriptor.displayName} content page $contentPage has no images: $canonical",
                        siteId = siteId,
                    )
                }
                imagesByPage[contentPage] = extracted.images
                thumbsByPage[contentPage] = extracted.thumbs
                sizes += extracted.images.size
            }
            pages = PhotoPageAddress.buildPages(
                pageSizes = sizes,
                contentPageUrlFor = { page -> config.postUrlRule.contentPageUrlBuilder(canonical, page) },
                resolvedImagesByPage = imagesByPage,
                resolvedThumbsByPage = thumbsByPage,
            )
        } else {
            pages = emptyList()
        }

        val metadata = extractDetailMetadata(firstDoc)
        val dateLabel = extractDetailDate(firstDoc)
        val aspectRatio = extractDetailAspectRatio(firstDoc)
        val postId = extractPostId(canonical)
            ?: throw SiteException.ParseError("Unable to derive post ID for $canonical", siteId)
        val totalImages = pages.size
        val item = MediaItem(
            siteId = siteId,
            id = postId,
            url = canonical,
            kind = descriptor.primaryKind,
            title = title,
            coverUrl = coverUrl,
            coverAspectRatio = aspectRatio,
            pageCount = totalImages,
            dateLabel = dateLabel.ifBlank { null },
            author = metadata.authors.firstOrNull(),
            tags = metadata.tags,
        )
        val description = config.metadataSpec.descriptionBuilder?.invoke(firstDoc, totalImages, contentPageCount)
            ?: defaultDescription(totalImages, contentPageCount)

        return GalleryDetail(
            item = item,
            description = description,
            pageCount = totalImages,
            pages = pages,
            tags = metadata.tags,
            authors = metadata.authors,
            characters = metadata.characters,
            categories = metadata.categories,
            embeddedVideos = embeddedVideos,
            previewPageSize = firstExtracted.images.size.takeIf { it > 0 },
            fileSizeLabel = metadata.fileSizeLabel,
        )
    }

    override suspend fun loadGalleryPage(
        detail: GalleryDetail,
        pageIndex: Int,
        forceReload: Boolean,
    ): GalleryPage {
        if (pageIndex < 1) {
            throw SiteException.ParseError("Invalid gallery page index: $pageIndex", siteId)
        }
        val existing = detail.pages.firstOrNull { it.index == pageIndex }
        if (existing != null) {
            if (!forceReload && existing.imageUrl.isNotBlank()) return existing
            return resolvePageImage(existing.pageUrl, preferOriginal = false, forceReload = forceReload)
        }

        // Sparse detail fallback: resolve deterministically by walking content pages.
        val canonical = canonicalizePostUrl(detail.item.url)
            ?: throw SiteException.ParseError("Unrecognized ${descriptor.displayName} gallery URL: ${detail.item.url}", siteId)
        var remaining = pageIndex - 1
        var contentPage = 1
        repeat(MAX_SPARSE_WALK_PAGES) {
            val extracted = loadAndCacheContentPage(canonical, contentPage, forceReload = forceReload)
            if (extracted.images.isEmpty()) {
                throw SiteException.ParseError(
                    message = "${descriptor.displayName} content page $contentPage has no images",
                    siteId = siteId,
                )
            }
            if (remaining < extracted.images.size) {
                val basePageUrl = config.postUrlRule.contentPageUrlBuilder(canonical, contentPage)
                val encodedUrl = PhotoPageAddress.encode(basePageUrl, pageIndex, contentPage, remaining)
                val imageUrl = extracted.images[remaining]
                val thumb = extracted.thumbs.getOrNull(remaining)?.takeIf { it.isNotBlank() && it != imageUrl }
                return GalleryPage(
                    index = pageIndex,
                    pageUrl = encodedUrl,
                    imageUrl = imageUrl,
                    thumbUrl = thumb,
                )
            }
            remaining -= extracted.images.size
            contentPage++
        }
        throw SiteException.ParseError(
            message = "Page index $pageIndex exceeds maximum walkable content pages for $canonical",
            siteId = siteId,
        )
    }

    override suspend fun resolvePageImage(
        pageUrl: String,
        preferOriginal: Boolean,
        forceReload: Boolean,
    ): GalleryPage {
        val identity = decodePageIdentity(pageUrl)
            ?: throw SiteException.ParseError("Unrecognized ${descriptor.displayName} page URL: $pageUrl", siteId)
        if (forceReload) {
            for (delta in 0 until PhotoPageAddress.MAX_DRIFT_PAGES) {
                evictContentPage(identity.canonicalUrl, identity.contentPage + delta)
            }
        }
        return PhotoPageAddress.resolveImage(
            identity = identity,
            rawPageUrl = pageUrl,
            siteId = siteId,
        ) { canonical, contentPage ->
            loadAndCacheContentPage(canonical, contentPage, forceReload = forceReload).images
        }
    }

    // ---- LinkResolver ----

    override fun canHandle(url: String): Boolean {
        val uri = GalleryUrlUtils.parseUri(url) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (!uri.rawUserInfo.isNullOrEmpty()) return false
        val host = uri.host?.lowercase() ?: return false
        return descriptor.matchesHost(host)
    }

    override fun canonicalize(url: String): String? {
        if (!canHandle(url)) return null
        val httpsUrl = normalizeToSiteHttps(url) ?: return null
        canonicalizePostUrl(httpsUrl)?.let { return it }
        return httpsUrl
    }

    override fun resolve(url: String): ResolvedLink? {
        if (!canHandle(url)) return null
        val httpsUrl = normalizeToSiteHttps(url) ?: return null
        val uri = GalleryUrlUtils.parseUri(httpsUrl) ?: return null

        // 1. Check post / gallery URL (including optional #flatIndex-contentPage-offset fragment)
        val canonicalPost = canonicalizePostUrl(httpsUrl)
        if (canonicalPost != null) {
            val postId = extractPostId(canonicalPost) ?: return null
            val pageIndex = PhotoPageAddress.decode(descriptor, httpsUrl, ::canonicalizePostUrl)?.flatIndex
            return ResolvedLink.Gallery(
                siteId = siteId,
                id = postId,
                canonicalUrl = canonicalPost,
                pageIndex = pageIndex,
            )
        }

        // 2. Check route-scheme specific feeds & directories
        return resolveNonPostLink(uri, httpsUrl)
    }

    // ---- Route Building & Parsing (Internal / Testable) ----

    fun resolveFeedUrl(request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        val base = descriptor.baseUrl
        val query = request.query?.trim().orEmpty()
        val category = request.category?.trim().orEmpty()
        val section = request.section?.trim().orEmpty()

        return when (val scheme = config.routeScheme) {
            is PhotoRouteScheme.WordPressPath -> {
                fun paged(pathSuffix: String, queryString: String): String = buildString {
                    append(base)
                    if (pathSuffix.isNotEmpty()) {
                        append('/')
                        append(pathSuffix.trim('/'))
                    }
                    if (page > 1) append("/page/$page")
                    append('/')
                    if (queryString.isNotBlank()) append("?$queryString")
                }

                when {
                    request.scope == SearchScope.TAG && query.isNotEmpty() ->
                        paged("tag/${HtmlUrlCleaner.encodePathSegment(query)}", "")
                    query.isNotEmpty() ->
                        paged("", "${scheme.searchQueryParam}=${HtmlUrlCleaner.encodePathSegment(query)}")
                    category.isNotEmpty() -> {
                        if (scheme.categoryQueryParam != null && category.all { it.isDigit() }) {
                            paged("", "${scheme.categoryQueryParam}=${HtmlUrlCleaner.encodePathSegment(category)}")
                        } else {
                            paged("category/${HtmlUrlCleaner.encodePathSegment(category)}", "")
                        }
                    }
                    (section == "popular" || request.sort == FeedSort.POPULAR) && scheme.popularPath != null ->
                        paged(scheme.popularPath.trim('/'), "")
                    else -> paged("", "")
                }
            }

            is PhotoRouteScheme.QueryParamWithPathSearch -> {
                fun queryPaged(pathUrl: String): String =
                    if (page <= 1) pathUrl else "$pathUrl?${scheme.pageQueryParam}=$page"

                when {
                    request.scope == SearchScope.TAG && query.isNotEmpty() -> {
                        val encoded = HtmlUrlCleaner.encodePathSegment(query)
                        queryPaged("$base/tag/$encoded")
                    }
                    query.isNotEmpty() -> {
                        val encoded = HtmlUrlCleaner.encodePathSegment(query)
                        if (page <= 1) {
                            "$base${scheme.searchPathPrefix}$encoded"
                        } else {
                            "$base${scheme.searchPathPrefix}$encoded/page/$page"
                        }
                    }
                    category.isNotEmpty() ->
                        queryPaged("$base${scheme.categoryPathPrefix}${HtmlUrlCleaner.encodePathSegment(category)}")
                    section == "popular" || request.sort == FeedSort.POPULAR ->
                        queryPaged("$base${scheme.popularPath}")
                    section.isNotEmpty() && section != "latest" ->
                        queryPaged("$base${scheme.categoryPathPrefix}${HtmlUrlCleaner.encodePathSegment(section)}")
                    else -> queryPaged("$base/")
                }
            }

            is PhotoRouteScheme.GalleryEpicRoutes -> {
                when {
                    request.scope == SearchScope.AUTHOR -> {
                        val id = requirePositiveNumericId(query.ifBlank { category })
                        "$base/en/coser/$id/$page"
                    }
                    section == "coser" && category.isNotEmpty() -> {
                        val id = requirePositiveNumericId(category)
                        "$base/en/coser/$id/$page"
                    }
                    category.startsWith("coser:") -> {
                        val id = requirePositiveNumericId(category.substringAfter(':'))
                        "$base/en/coser/$id/$page"
                    }
                    request.scope == SearchScope.CHARACTER -> {
                        val id = requirePositiveNumericId(query.ifBlank { category })
                        "$base/en/character/$id"
                    }
                    section == "character" && category.isNotEmpty() -> {
                        val id = requirePositiveNumericId(category)
                        "$base/en/character/$id"
                    }
                    category.startsWith("character:") -> {
                        val id = requirePositiveNumericId(category.substringAfter(':'))
                        "$base/en/character/$id"
                    }
                    category.startsWith("series:") || section.startsWith("series:") -> {
                        val raw = if (category.startsWith("series:")) category else section
                        val id = requirePositiveNumericId(raw.substringAfter(':'))
                        "$base/en/parody/$id"
                    }
                    query.isNotEmpty() -> {
                        val encoded = HtmlUrlCleaner.encodePathSegment(query)
                        "$base/en/cosers/$page?coserName=$encoded"
                    }
                    section == "cosers" || category == "cosers" ->
                        "$base/en/cosers/$page"
                    else -> "$base/en/cosplays/$page"
                }
            }
        }
    }

    fun feedTitle(request: FeedRequest): String {
        val query = request.query?.trim().orEmpty()
        val category = request.category?.trim().orEmpty()
        val section = request.section?.trim().orEmpty()
        return when {
            request.scope == SearchScope.AUTHOR || section == "coser" || category.startsWith("coser:") -> "模特写真"
            request.scope == SearchScope.CHARACTER || section == "character" || category.startsWith("character:") -> "角色写真"
            category.startsWith("series:") || section.startsWith("series:") -> "作品角色"
            query.isNotEmpty() -> query
            category.isNotEmpty() ->
                descriptor.sections.firstOrNull { it.id == category || it.category == category }?.title ?: "写真分类"
            section == "popular" || (request.sort == FeedSort.POPULAR && FeedSort.POPULAR in descriptor.feedSorts) ->
                descriptor.sections.firstOrNull { it.id == "popular" }?.title ?: "热门写真"
            section == "cosers" -> "Coser 索引"
            section.isNotEmpty() && section != "latest" ->
                descriptor.sections.firstOrNull { it.id == section }?.title ?: "最新写真"
            else -> "最新写真"
        }
    }

    fun canonicalizePostUrl(rawUrl: String): String? {
        val uri = GalleryUrlUtils.parseUri(rawUrl) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.rawUserInfo.isNullOrEmpty()) return null
        val host = uri.host?.lowercase() ?: return null
        if (!descriptor.matchesHost(host)) return null
        val path = uri.rawPath.orEmpty()
        val match = config.postUrlRule.pathRegex.matchEntire(path) ?: return null
        val firstSegment = path.trim('/').substringBefore('/').lowercase()
        if (firstSegment in config.postUrlRule.reservedFirstSegments) return null
        return config.postUrlRule.canonicalBuilder(descriptor.baseUrl, match)
    }

    fun extractPostId(canonicalUrl: String): String? {
        val uri = GalleryUrlUtils.parseUri(canonicalUrl) ?: return null
        val match = config.postUrlRule.pathRegex.matchEntire(uri.rawPath.orEmpty()) ?: return null
        return config.postUrlRule.postIdExtractor(match).takeIf { it.isNotBlank() }
    }

    fun decodePageIdentity(rawUrl: String): PhotoPageIdentity? =
        PhotoPageAddress.decode(descriptor, rawUrl, ::canonicalizePostUrl)

    fun parseFeedDocument(doc: Document, request: FeedRequest, fromCache: Boolean = false): FeedPage {
        val page = request.page.coerceAtLeast(1)
        val items = parseCards(doc)
        if (items.isEmpty() && !looksEmpty(doc)) {
            throw SiteException.ParseError(
                message = "${descriptor.displayName} returned no recognizable photo sets; markup may have changed",
                siteId = siteId,
            )
        }
        val parsedTotal = parseTotalPages(doc)
        val totalPages = parsedTotal?.coerceAtLeast(page) ?: page
        val hasMore = page < totalPages
        return FeedPage(
            items = items,
            nextPage = if (hasMore) page + 1 else null,
            hasMore = hasMore,
            totalPages = totalPages,
            title = feedTitle(request),
            fromCache = fromCache,
        )
    }

    fun parseCards(doc: Document): List<MediaItem> {
        val baseUrl = doc.location().ifBlank { descriptor.baseUrl }
        val elements = LinkedHashSet<Element>()
        for (selector in config.cardSpec.itemSelectors) {
            elements.addAll(doc.select(selector))
        }
        return elements.mapNotNull { card -> parseSingleCard(card, baseUrl) }.distinctBy { it.id }
    }

    private fun parseSingleCard(card: Element, baseUrl: String): MediaItem? {
        val spec = config.cardSpec
        val link = if (card.tagName().equals("a", ignoreCase = true) && spec.linkSelectors.isEmpty()) {
            card
        } else {
            spec.linkSelectors.firstNotNullOfOrNull { card.selectFirst(it) }
        } ?: return null

        val rawHref = link.absUrl("href").ifBlank { link.attr("href") }
        val resolvedHref = GalleryUrlUtils.resolveAbsoluteUrl(rawHref, baseUrl) ?: return null
        val canonical = canonicalizePostUrl(resolvedHref) ?: return null
        val postId = extractPostId(canonical) ?: return null

        val thumbEl: Element? = spec.thumbSelectors.firstNotNullOfOrNull { card.selectFirst(it) }
            ?: if (spec.requireThumbMatch) return null else card.selectFirst("img")
        if (spec.requireThumbMatch && thumbEl == null) return null

        val title = spec.titleExtractor?.invoke(card, link)
            ?: GalleryUrlUtils.cleanHtmlText(link.text()).ifBlank {
                GalleryUrlUtils.cleanHtmlText(thumbEl?.attr("alt").orEmpty())
            }
        if (title.isBlank()) return null

        val coverUrl = if (thumbEl != null) {
            GalleryUrlUtils.extractImgElementUrl(thumbEl, baseUrl).orEmpty()
        } else {
            ""
        }
        val aspectRatio = GalleryUrlUtils.extractAspectRatio(thumbEl) ?: spec.defaultAspectRatio
        val views = spec.viewsExtractor?.invoke(card)
        val dateLabel = spec.dateExtractor?.invoke(card)?.takeIf { it.isNotBlank() }
            ?: spec.dateSelector?.takeIf { it.isNotBlank() }
                ?.let { card.selectFirst(it)?.text() }
                ?.let { GalleryUrlUtils.cleanHtmlText(it) }
                ?.takeIf { it.isNotBlank() }
        val pageCount = spec.pageCountExtractor?.invoke(card, title)
            ?: extractPhotoCountFromTitle(title)

        return MediaItem(
            siteId = siteId,
            id = postId,
            url = canonical,
            kind = descriptor.primaryKind,
            title = title,
            coverUrl = coverUrl,
            coverAspectRatio = aspectRatio,
            pageCount = pageCount,
            views = views,
            dateLabel = dateLabel,
        )
    }

    fun parseTotalPages(doc: Document): Int? {
        config.cardSpec.totalPagesExtractor?.let { return it(doc) }
        val links = doc.select(config.cardSpec.pagerLinkSelector)
        return links.mapNotNull { anchor ->
            val href = anchor.attr("href")
            val dataPaged = anchor.attr("data-paged")
            val combined = "$href $dataPaged"
            PAGE_NUMBER_ATTR_REGEX.find(combined)?.groupValues?.get(1)?.toIntOrNull()
                ?: anchor.text().trim().replace(",", "").toIntOrNull()
        }.filter { it > 0 }.maxOrNull()
    }

    fun extractContentPageCount(doc: Document): Int = when (val spec = config.imageSpec) {
        is ImageExtractionSpec.DomImages -> {
            if (spec.contentPageLinkSelector.isBlank()) {
                1
            } else {
                doc.select(spec.contentPageLinkSelector).size.coerceAtLeast(1)
            }
        }
        is ImageExtractionSpec.NextJsRscUuidPayload -> 1
    }

    fun extractImages(doc: Document): List<String> = extractImagesWithThumbs(doc).images

    private data class ExtractedImages(
        val images: List<String>,
        val thumbs: List<String?>,
    )

    private fun extractImagesWithThumbs(doc: Document): ExtractedImages = when (val spec = config.imageSpec) {
        is ImageExtractionSpec.DomImages -> extractDomImages(doc, spec)
        is ImageExtractionSpec.NextJsRscUuidPayload -> {
            val urls = extractRscUuidImages(doc, spec.cdnPrefix)
            ExtractedImages(images = urls, thumbs = List(urls.size) { null })
        }
    }

    private fun extractDomImages(doc: Document, spec: ImageExtractionSpec.DomImages): ExtractedImages {
        val baseUrl = doc.location().ifBlank { descriptor.baseUrl }
        val container = if (spec.strictPrimaryContainer && spec.containerSelectors.isNotEmpty()) {
            doc.selectFirst(spec.containerSelectors.first())
                ?: spec.containerSelectors.drop(1).firstNotNullOfOrNull { doc.selectFirst(it) }
        } else {
            spec.containerSelectors.firstNotNullOfOrNull { doc.selectFirst(it) }
        } ?: return ExtractedImages(emptyList(), emptyList())

        // Work on a lightweight clone when stripping excluded zones so the caller's Document is never mutated.
        val scoped = container.clone()
        for (excludeSelector in spec.excludedContainerSelectors) {
            scoped.select(excludeSelector).remove()
        }

        val seen = LinkedHashSet<String>()
        val images = ArrayList<String>()
        val thumbs = ArrayList<String?>()

        for (el in scoped.select(spec.imageSelector)) {
            val candidates = GalleryUrlUtils.extractGalleryImageCandidates(
                element = el,
                baseUrl = baseUrl,
                preferAnchorHref = spec.preferAnchorHref,
                allowedUrlSubstrings = spec.allowedUrlSubstrings,
            )
            val chosen = candidates.firstOrNull { candidate ->
                isAcceptedGalleryImage(candidate, spec.allowedUrlSubstrings)
            } ?: continue

            if (seen.add(chosen)) {
                images += chosen
                val imgEl = if (el.tagName().equals("img", ignoreCase = true)) el else el.selectFirst("img")
                val thumb = imgEl?.let { GalleryUrlUtils.extractImgElementUrl(it, baseUrl) }
                    ?.takeIf { it != chosen && isAcceptedGalleryImage(it, spec.allowedUrlSubstrings) }
                thumbs += thumb
            }
        }
        return ExtractedImages(images, thumbs)
    }

    private fun isAcceptedGalleryImage(url: String, allowedSubstrings: Set<String>): Boolean {
        if (!GalleryUrlUtils.isSafeHttps(url)) return false
        if (GalleryUrlUtils.isLikelyAdOrIconUrl(url)) return false
        if (allowedSubstrings.isEmpty()) return true
        return allowedSubstrings.any { url.contains(it, ignoreCase = true) }
    }

    private fun extractRscUuidImages(doc: Document, cdnPrefix: String): List<String> {
        val rawHtml = doc.outerHtml()
        val unescapedRsc = JsExtractor.extractNextRscFlightChunks(rawHtml)

        val matchedArrays = ArrayList<String>()
        if (unescapedRsc.isNotEmpty()) {
            IMAGES_JSON_ARRAY_REGEX.findAll(unescapedRsc).forEach { matchedArrays += it.groupValues[1] }
        }
        if (matchedArrays.isEmpty()) {
            ESCAPED_IMAGES_ARRAY_REGEX.findAll(rawHtml).forEach { matchedArrays += it.groupValues[1] }
        }
        if (matchedArrays.isEmpty()) {
            IMAGES_JSON_ARRAY_REGEX.findAll(rawHtml).forEach { matchedArrays += it.groupValues[1] }
        }

        val uuids = if (matchedArrays.isNotEmpty()) {
            matchedArrays.flatMap { segment -> UUID_REGEX.findAll(segment).map { it.value.lowercase() }.toList() }
        } else {
            UUID_REGEX.findAll(rawHtml).map { it.value.lowercase() }.toList()
        }

        return uuids.distinct().map { "$cdnPrefix$it" }
    }

    fun extractDetailTitle(doc: Document): String {
        val spec = config.metadataSpec
        if (spec.preferH1Title) {
            val h1 = GalleryUrlUtils.cleanHtmlText(doc.selectFirst("h1")?.text().orEmpty())
            if (h1.isNotBlank()) return h1
        }
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val raw = GalleryUrlUtils.cleanHtmlText(ogTitle.ifBlank { doc.title() })
        val stripped = when {
            spec.titleSuffixRegex != null -> raw.replace(spec.titleSuffixRegex, "").trim()
            else -> raw.replace(GENERIC_TITLE_SUFFIX_REGEX, "").trim()
        }
        return stripped.ifBlank { raw }.ifBlank { "未命名写真集" }
    }

    fun extractDetailCover(doc: Document, images: List<String>): String {
        if (config.metadataSpec.preferOgImageCover) {
            val og = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty().trim()
            if (GalleryUrlUtils.isSafeHttps(og)) return og
        }
        return images.firstOrNull().orEmpty()
    }

    fun extractDetailDate(doc: Document): String =
        doc.selectFirst("time[datetime]")?.attr("datetime")?.trim()?.take(10).orEmpty()

    fun extractDetailAspectRatio(doc: Document): Float? {
        config.metadataSpec.defaultAspectRatio?.let { return it }
        val spec = config.imageSpec
        if (spec is ImageExtractionSpec.DomImages) {
            val firstImg = spec.containerSelectors.firstNotNullOfOrNull { doc.selectFirst(it) }?.selectFirst("img")
            GalleryUrlUtils.extractAspectRatio(firstImg)?.let { return it }
        }
        return config.cardSpec.defaultAspectRatio
    }

    fun extractDetailMetadata(doc: Document): ExtractedGalleryMetadata {
        val baseUrl = doc.location().ifBlank { descriptor.baseUrl }
        config.metadataSpec.customMetadataExtractor?.let { return it(doc, baseUrl) }
        return extractStandardWordPressMetadata(doc, baseUrl)
    }

    fun looksEmpty(doc: Document): Boolean {
        val text = doc.text()
        return text.contains("Nothing Found", ignoreCase = true) ||
            text.contains("no results", ignoreCase = true) ||
            text.contains("no data", ignoreCase = true) ||
            text.contains("没有找到", ignoreCase = true) ||
            text.contains("沒有找到", ignoreCase = true) ||
            text.contains("sorry, but nothing matched", ignoreCase = true)
    }

    // ---- Internal HTTP & Cache Helpers ----

    internal suspend fun fetchDocument(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowCache: Boolean = true,
    ): Pair<Document, Boolean> {
        val response = httpClient.getText(
            url = url,
            siteId = siteId,
            kind = SiteRequestKind.PAGE,
            headers = headers,
            allowCache = allowCache,
        )
        val body = response.bodyText()
        val cfMitigated = response.header("cf-mitigated")
        when (response.code) {
            in 200..299 -> {
                if (GalleryUrlUtils.isChallengeHtml(body, cfMitigated)) {
                    throw SiteException.Forbidden(
                        isChallenge = true,
                        message = "${descriptor.displayName} returned a browser challenge page",
                        siteId = siteId,
                    )
                }
                val effectiveBase = response.finalUrl.ifBlank { url }
                return Jsoup.parse(body, effectiveBase) to response.fromCache
            }
            404 -> throw SiteException.NotFound("HTTP 404 for $url", siteId)
            401 -> throw SiteException.AuthRequired("HTTP 401 for $url", siteId)
            403 -> throw SiteException.Forbidden(
                isChallenge = GalleryUrlUtils.isChallengeHtml(body, cfMitigated),
                message = "HTTP 403 for $url",
                siteId = siteId,
            )
            429 -> throw SiteException.RateLimited(
                retryAfterSeconds = response.retryAfterSeconds,
                message = "HTTP 429 for $url",
                siteId = siteId,
            )
            else -> throw SiteException.Network("HTTP ${response.code} for $url", siteId)
        }
    }

    private suspend fun loadDirectoryEntries(
        dirConfig: DirectoryConfig,
        section: String,
        page: Int,
        query: String?,
    ): List<DirectoryEntry> {
        val url = dirConfig.urlBuilder(descriptor.baseUrl, section, page.coerceAtLeast(1), query)
            ?: return emptyList()
        val (doc, _) = fetchDocument(url)
        val entries = dirConfig.parser(doc, descriptor)
        if (entries.isEmpty() && !looksEmpty(doc)) {
            throw SiteException.ParseError(
                message = "${descriptor.displayName} directory returned no entries for section=$section",
                siteId = siteId,
            )
        }
        return entries
    }

    private suspend fun loadAndCacheContentPage(
        canonical: String,
        contentPage: Int,
        forceReload: Boolean,
    ): ExtractedImages {
        val key = cacheKey(canonical, contentPage)
        if (!forceReload) {
            val cachedImages = contentPageCache.get(key)
            if (cachedImages != null) {
                val cachedThumbs = contentThumbCache.get(key) ?: List(cachedImages.size) { null }
                return ExtractedImages(cachedImages, cachedThumbs)
            }
        }
        val pageUrl = config.postUrlRule.contentPageUrlBuilder(canonical, contentPage)
        val (doc, _) = fetchDocument(pageUrl, allowCache = !forceReload)
        val extracted = extractImagesWithThumbs(doc)
        if (extracted.images.isNotEmpty()) {
            cacheContentPage(canonical, contentPage, extracted.images, extracted.thumbs)
        }
        return extracted
    }

    private fun cacheContentPage(
        canonical: String,
        contentPage: Int,
        images: List<String>,
        thumbs: List<String?>,
    ) {
        val key = cacheKey(canonical, contentPage)
        contentPageCache.put(key, images)
        contentThumbCache.put(key, thumbs)
    }

    private fun evictContentPage(canonical: String, contentPage: Int) {
        val key = cacheKey(canonical, contentPage)
        contentPageCache.remove(key)
        contentThumbCache.remove(key)
    }

    private fun cacheKey(canonical: String, contentPage: Int): String =
        "${siteId.value}|$canonical|$contentPage"

    private fun normalizeToSiteHttps(rawUrl: String): String? {
        val normalized = UrlSanitizer.normalizeHttpsUrl(rawUrl, preserveFragment = true) ?: return null
        val uri = GalleryUrlUtils.parseUri(normalized) ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!descriptor.matchesHost(host)) return null
        // Preserve defaultHost casing/www prefix consistently
        val portPart = if (uri.port > 0 && uri.port != 443 && uri.port != 80) ":${uri.port}" else ""
        val pathPart = uri.rawPath.orEmpty().ifEmpty { "/" }
        val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragmentPart = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "https://${descriptor.defaultHost}$portPart$pathPart$queryPart$fragmentPart"
    }

    private fun resolveNonPostLink(uri: java.net.URI, httpsUrl: String): ResolvedLink? {
        val path = uri.rawPath.orEmpty()
        val segments = GalleryUrlUtils.pathSegments(uri)
        val queryParams = GalleryUrlUtils.queryParameters(uri)

        return when (val scheme = config.routeScheme) {
            is PhotoRouteScheme.WordPressPath -> {
                val page = extractWpPathPage(segments)
                val searchQuery = queryParams[scheme.searchQueryParam]?.trim()
                if (!searchQuery.isNullOrEmpty()) {
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = siteId, query = searchQuery, page = page),
                    )
                }
                if (segments.size >= 2 && segments[0].equals("category", ignoreCase = true)) {
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = siteId, category = segments[1], page = page),
                    )
                }
                if (segments.size >= 2 && segments[0].equals("tag", ignoreCase = true)) {
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = siteId, query = segments[1], scope = SearchScope.TAG, page = page),
                    )
                }
                val catParam = scheme.categoryQueryParam?.let { queryParams[it]?.trim() }
                if (!catParam.isNullOrEmpty()) {
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = siteId, category = catParam, page = page),
                    )
                }
                if (segments.isEmpty() || (segments.size == 2 && segments[0].equals("page", ignoreCase = true) && segments[1].toIntOrNull() != null)) {
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = siteId, section = "latest", page = page),
                    )
                }
                null
            }

            is PhotoRouteScheme.QueryParamWithPathSearch -> {
                val queryPage = queryParams[scheme.pageQueryParam]?.toIntOrNull()?.takeIf { it > 0 } ?: 1
                if (segments.size >= 2 && segments[0].equals("search", ignoreCase = true)) {
                    val page = extractWpPathPage(segments)
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = siteId, query = segments[1], page = page),
                    )
                }
                if (segments.size >= 2 && segments[0].equals("pages", ignoreCase = true)) {
                    val slug = segments[1]
                    return if (slug.equals("popular", ignoreCase = true)) {
                        ResolvedLink.Feed(
                            FeedRequest(siteId = siteId, section = "popular", sort = FeedSort.POPULAR, page = queryPage),
                        )
                    } else {
                        ResolvedLink.Feed(
                            FeedRequest(siteId = siteId, section = slug, category = slug, page = queryPage),
                        )
                    }
                }
                if (segments.size >= 2 && segments[0].equals("tag", ignoreCase = true)) {
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = siteId, query = segments[1], scope = SearchScope.TAG, page = queryPage),
                    )
                }
                if (segments.isEmpty()) {
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = siteId, section = "latest", page = queryPage),
                    )
                }
                null
            }

            is PhotoRouteScheme.GalleryEpicRoutes -> {
                GALLERY_EPIC_COSERS_REGEX.matchEntire(path)?.let { match ->
                    val page = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
                    val coserQuery = queryParams["coserName"].orEmpty().trim()
                    val dirSection = "cosers:$page:$coserQuery"
                    return ResolvedLink.Directory(
                        siteId = siteId,
                        section = dirSection,
                        canonicalUrl = httpsUrl,
                    )
                }
                GALLERY_EPIC_COSER_REGEX.matchEntire(path)?.let { match ->
                    val coserId = match.groupValues[1]
                    val page = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
                    return ResolvedLink.Feed(
                        FeedRequest(
                            siteId = siteId,
                            section = "coser",
                            category = coserId,
                            query = coserId,
                            scope = SearchScope.AUTHOR,
                            page = page,
                        ),
                    )
                }
                GALLERY_EPIC_CHARACTER_REGEX.matchEntire(path)?.let { match ->
                    val charId = match.groupValues[1]
                    return ResolvedLink.Feed(
                        FeedRequest(
                            siteId = siteId,
                            section = "character",
                            category = charId,
                            query = charId,
                            scope = SearchScope.CHARACTER,
                            page = 1,
                        ),
                    )
                }
                GALLERY_EPIC_PARODY_REGEX.matchEntire(path)?.let { match ->
                    val parodyId = match.groupValues[1]
                    return ResolvedLink.Directory(
                        siteId = siteId,
                        section = "series:$parodyId",
                        canonicalUrl = "${descriptor.baseUrl}/en/parody/$parodyId",
                    )
                }
                GALLERY_EPIC_COSPLAYS_REGEX.matchEntire(path)?.let { match ->
                    val page = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = siteId, section = "latest", page = page),
                    )
                }
                null
            }
        }
    }

    private fun extractWpPathPage(segments: List<String>): Int {
        val pageIdx = segments.indexOfFirst { it.equals("page", ignoreCase = true) }
        if (pageIdx >= 0 && pageIdx + 1 < segments.size) {
            segments[pageIdx + 1].toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return 1
    }

    private fun requirePositiveNumericId(raw: String): String {
        val trimmed = raw.trim()
        if (!POSITIVE_NUMERIC_REGEX.matches(trimmed)) {
            throw SiteException.ParseError("Invalid numeric identifier '$raw' for ${descriptor.displayName}", siteId)
        }
        return trimmed
    }

    private fun extractStandardWordPressMetadata(doc: Document, baseUrl: String): ExtractedGalleryMetadata {
        val root = (doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()).clone()
        for (exclude in ImageExtractionSpec.DEFAULT_EXCLUDED_CONTAINERS) {
            root.select(exclude).remove()
        }

        val categories = root.select(
            "a[rel~=\"category\"], .cat-links a[href], .entry-categories a[href], .post-categories a[href], a[href*=\"/category/\"]",
        ).mapNotNull { anchor -> toTagRef(anchor, baseUrl, "category") }.distinctBy { it.slug.lowercase() }

        val tags = root.select(
            "a[rel=\"tag\"], .tags-links a[href], .entry-tags a[href], .post-tags a[href], a[href*=\"/tag/\"]",
        ).mapNotNull { anchor -> toTagRef(anchor, baseUrl, "tag") }
            .filter { tag -> categories.none { it.slug.equals(tag.slug, ignoreCase = true) && it.url == tag.url } }
            .distinctBy { it.slug.lowercase() }

        val authors = root.select(
            "a[rel=\"author\"], .author a[href], .byline a[href]",
        ).mapNotNull { anchor ->
            val name = GalleryUrlUtils.cleanHtmlText(anchor.text())
            if (name.isBlank()) return@mapNotNull null
            val href = GalleryUrlUtils.resolveAbsoluteUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") }, baseUrl).orEmpty()
            val id = slugFromUrlOrText(href, name)
            AuthorRef(id = id, name = name, role = "author", url = href)
        }.distinctBy { it.id.lowercase() }

        val fileSize = FILE_SIZE_REGEX.find(doc.title())?.value

        return ExtractedGalleryMetadata(
            tags = tags,
            authors = authors,
            categories = categories,
            fileSizeLabel = fileSize,
        )
    }

    private fun toTagRef(anchor: Element, baseUrl: String, namespace: String): TagRef? {
        val label = GalleryUrlUtils.cleanHtmlText(anchor.text())
        if (label.isBlank()) return null
        val href = GalleryUrlUtils.resolveAbsoluteUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") }, baseUrl).orEmpty()
        val slug = slugFromUrlOrText(href, label)
        return TagRef(slug = slug, label = label, namespace = namespace, url = href)
    }

    private fun slugFromUrlOrText(url: String, fallback: String): String {
        val lastSeg = GalleryUrlUtils.parseUri(url)?.let { GalleryUrlUtils.pathSegments(it).lastOrNull() }
        return lastSeg?.takeIf { it.isNotBlank() } ?: fallback.lowercase().replace(Regex("\\s+"), "-")
    }

    private fun extractPhotoCountFromTitle(title: String): Int? =
        TITLE_PHOTO_COUNT_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    private fun defaultDescription(totalImages: Int, contentPages: Int): String =
        if (contentPages > 1) "共 $totalImages 张 · $contentPages 个内容分页" else "共 $totalImages 张"

    companion object {
        private const val MAX_SPARSE_WALK_PAGES = 64

        private val PAGE_NUMBER_ATTR_REGEX = Regex("(?:page/|query-3-page=|paged=\"?)(\\d+)")
        private val GENERIC_TITLE_SUFFIX_REGEX = Regex("\\s*[-|–—]\\s*[^-|–—]*$")
        private val POSITIVE_NUMERIC_REGEX = Regex("^[1-9]\\d*$")
        private val TITLE_PHOTO_COUNT_REGEX = Regex("(\\d+)\\s*photos?", RegexOption.IGNORE_CASE)
        private val FILE_SIZE_REGEX = Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:MB|GB)\\b", RegexOption.IGNORE_CASE)

        private val IMAGES_JSON_ARRAY_REGEX = Regex("\"images\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
        private val ESCAPED_IMAGES_ARRAY_REGEX = Regex("\\\\\"images\\\\\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
        private val UUID_REGEX = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", RegexOption.IGNORE_CASE)

        private val GALLERY_EPIC_COSERS_REGEX = Regex("^/en/cosers/(\\d+)/?$")
        private val GALLERY_EPIC_COSER_REGEX = Regex("^/en/coser/(\\d+)/(\\d+)/?$")
        private val GALLERY_EPIC_CHARACTER_REGEX = Regex("^/en/character/(\\d+)/?$")
        private val GALLERY_EPIC_PARODY_REGEX = Regex("^/en/parody/(\\d+)/?$")
        private val GALLERY_EPIC_COSPLAYS_REGEX = Regex("^/en/cosplays/(\\d+)/?$")
    }
}
