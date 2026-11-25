package com.raven.app.sites.comic

import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.DualLanguageMerger
import com.raven.app.core.sitespi.DualMergeStrategy
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.JsExtractor
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.LruCache
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import com.raven.app.core.sitespi.StreamSlice
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * WNACG (`SiteId.WNACG`) comic plugin.
 * Enforces [SiteNetworkProfile.DESKTOP_WINDOWS_UA] on every request (desktop vs mobile markup differ),
 * supports feeds, categories, language routing, search/tag/author queries, unpacks `document.writeln`
 * wrapped `imglist` arrays, and strips the trailing `/themes/weitu/images/bg/shoucang.jpg` reminder.
 */
class WnacgSitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    private val json = Json { isLenient = true }
    private val imageListCache = LruCache<String, List<String>>(maxEntries = 16)

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(
                FeedRequest(siteId = SiteId.WNACG, section = "latest", language = language, page = 1),
            )
            return HomeFeed(siteId = SiteId.WNACG, primaryFeedPage = page)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage = executeFeed(request)

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = listOf(FeedSort.NEWEST)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = executeFeed(request)

        override val supportedScopes: Set<SearchScope> = setOf(
            SearchScope.ALL,
            SearchScope.TAG,
            SearchScope.AUTHOR,
        )

        override val supportedFilters: Set<AdvancedFilterField> = setOf(
            AdvancedFilterField.CATEGORY,
            AdvancedFilterField.LANGUAGE,
        )

        override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> = listOf(FeedSort.NEWEST)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val canonical = canonicalGalleryUrl(url)
                ?: throw SiteException.ParseError("无法识别 WNACG 画廊地址：$url", siteId = SiteId.WNACG)
            val detailHtml = fetchDesktopHtml(canonical)
            val imageUrls = loadGalleryImageUrls(canonical, forceReload = false)
            return parseGalleryDetail(detailHtml, canonical, imageUrls).also {
                imageListCache.put(canonical, imageUrls)
            }
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.WNACG)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val canonical = canonicalGalleryUrl(detail.item.url)
                ?: throw SiteException.ParseError("无法识别 WNACG 画廊地址：${detail.item.url}", siteId = SiteId.WNACG)
            return resolvePageImage("$canonical#$pageIndex", preferOriginal = false, forceReload = forceReload)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val identity = parsePageIdentity(pageUrl)
                ?: throw SiteException.ParseError("无法识别 WNACG 图页地址：$pageUrl", siteId = SiteId.WNACG)
            val urls = if (forceReload) {
                loadGalleryImageUrls(identity.canonical, forceReload = true)
            } else {
                imageListCache.get(identity.canonical)
                    ?: loadGalleryImageUrls(identity.canonical, forceReload = false)
            }.also { imageListCache.put(identity.canonical, it) }
            return buildGalleryPage(identity, urls)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return isSiteUrl(parsed)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf(::isSiteUrl) ?: return null
            canonicalGalleryUrl(url)?.let { canonical ->
                val pageFrag = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 && it.toString() == parsed.fragment }
                return if (pageFrag != null) "$canonical#$pageFrag" else canonical
            }
            return ComicUrl.parseStrictHttps("${descriptor.baseUrl}${parsed.encodedPath}")
                ?.withQuery(parsed.queryParams)
                ?.withFragment(null)
                ?.toString()
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf(::isSiteUrl) ?: return null
            canonicalGalleryUrl(url)?.let { canonical ->
                val pageFrag = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 && it.toString() == parsed.fragment }
                return ResolvedLink.Gallery(
                    siteId = SiteId.WNACG,
                    id = extractGalleryId(canonical),
                    canonicalUrl = if (pageFrag != null) "$canonical#$pageFrag" else canonical,
                    pageIndex = pageFrag,
                )
            }
            parseFeedLink(url)?.let { return ResolvedLink.Feed(it) }
            return null
        }
    }

    // -------------------------------------------------------------------------
    // Feed, Categories & Language Routing
    // -------------------------------------------------------------------------

    private suspend fun executeFeed(request: FeedRequest): FeedPage {
        if (request.page <= 0) {
            throw SiteException.ParseError("页码无效：${request.page}", siteId = SiteId.WNACG)
        }
        val query = request.query?.trim().orEmpty()
        if (query.isEmpty() && request.language == ComicLanguage.CHINESE_AND_JAPANESE) {
            val baseCat = resolveBaseCategory(request)
            val pair = DUAL_LANGUAGE_CATEGORY_PAIRS[baseCat ?: "5"]
            if (pair != null) {
                return mergeDualLanguageCategories(request, pair.first, pair.second)
            }
        }
        val url = resolveFeedUrl(request)
        val html = fetchDesktopHtml(url)
        return parseFeed(html, url, request, feedHeading(request))
    }

    private suspend fun mergeDualLanguageCategories(
        request: FeedRequest,
        chineseCategory: String,
        japaneseCategory: String,
    ): FeedPage {
        suspend fun fetchCategorySlice(cateId: String, page: Int): StreamSlice<MediaItem> {
            val req = request.copy(
                section = null,
                category = cateId,
                language = ComicLanguage.ALL,
                page = page,
                cursor = null,
            )
            val url = resolveFeedUrl(req)
            val parsed = parseFeed(fetchDesktopHtml(url), url, req, feedHeading(req))
            return StreamSlice(items = parsed.items, hasNext = parsed.hasMore)
        }

        val merged = DualLanguageMerger.merge(
            cursorRaw = request.cursor,
            pageSize = DUAL_MERGE_PAGE_SIZE,
            strategy = DualMergeStrategy.DESCENDING_NUMERIC_ID,
            idOf = { it.id },
            fetchStreamA = { p -> fetchCategorySlice(chineseCategory, p) },
            fetchStreamB = { p -> fetchCategorySlice(japaneseCategory, p) },
        )
        return FeedPage(
            items = merged.items,
            nextPage = if (merged.hasMore) request.page + 1 else null,
            nextCursor = merged.nextCursor,
            hasMore = merged.hasMore,
            title = feedHeading(request),
        )
    }

    internal fun resolveFeedUrl(request: FeedRequest): String {
        if (request.page <= 0) {
            throw SiteException.ParseError("页码无效：${request.page}", siteId = SiteId.WNACG)
        }
        val base = ComicUrl.requireParse(descriptor.baseUrl, SiteId.WNACG)
        val query = request.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            return when (request.scope) {
                SearchScope.TAG -> {
                    base.addPathSegment("albums-index-page-${request.page}-tag-$query.html").toString()
                }
                SearchScope.ALL, SearchScope.AUTHOR, SearchScope.CHARACTER -> {
                    base.addPathSegments("search/index.php")
                        .addQueryParameter("s", "create_time_DESC")
                        .addQueryParameter("q", query)
                        .addQueryParameter("p", request.page.toString())
                        .toString()
                }
            }
        }

        val resolvedCategory = resolveEffectiveCategory(request)
        if (resolvedCategory != null) {
            return base.addPathSegment("albums-index-page-${request.page}-cate-$resolvedCategory.html").toString()
        }
        return base.addPathSegment("albums-index-page-${request.page}.html").toString()
    }

    private fun resolveBaseCategory(request: FeedRequest): String? {
        val raw = request.category?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.section?.takeIf { it in ALL_VALID_CATEGORY_IDS }
            ?: request.advancedFilters.category?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (raw in ALL_VALID_CATEGORY_IDS) return raw
        CATEGORIES.entries.firstOrNull { it.value.equals(raw, ignoreCase = true) }?.let { return it.key }
        return null
    }

    private fun resolveEffectiveCategory(request: FeedRequest): String? {
        val baseCat = resolveBaseCategory(request)
        return when (request.language) {
            ComicLanguage.ALL -> baseCat
            ComicLanguage.CHINESE -> when (baseCat) {
                null, "5" -> "1"
                "6" -> "9"
                "7" -> "10"
                "19" -> "20"
                else -> baseCat
            }
            ComicLanguage.JAPANESE -> when (baseCat) {
                null, "5" -> "12"
                "6" -> "13"
                "7" -> "14"
                else -> baseCat
            }
            ComicLanguage.CHINESE_AND_JAPANESE -> baseCat
        }
    }

    // -------------------------------------------------------------------------
    // Network & Parsing
    // -------------------------------------------------------------------------

    private suspend fun fetchDesktopHtml(url: String, allowCache: Boolean = true): String {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.WNACG,
                kind = SiteRequestKind.PAGE,
                headers = mapOf(
                    "User-Agent" to SiteNetworkProfile.DESKTOP_WINDOWS_UA,
                    "Referer" to "${descriptor.baseUrl}/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                ),
                allowCache = allowCache,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw e
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "WNACG 网络请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.WNACG,
                cause = e,
            )
        }
        response.throwIfNotSuccessful(SiteId.WNACG, descriptor.displayName)
        return response.bodyText()
    }

    private suspend fun loadGalleryImageUrls(canonical: String, forceReload: Boolean): List<String> {
        val readerUrl = buildReaderUrl(canonical)
        val html = fetchDesktopHtml(readerUrl, allowCache = !forceReload)
        return parseReaderImageUrls(html, readerUrl)
    }

    internal fun parseFeed(
        body: String,
        url: String,
        request: FeedRequest,
        heading: String,
    ): FeedPage {
        val base = ComicUrl.requireParse(url, SiteId.WNACG)
        val doc = Jsoup.parse(body, url)
        val entries = doc.select(".gallary_item").mapNotNull { card ->
            val link = card.selectFirst(".title > a[href]") ?: return@mapNotNull null
            val resolvedHref = base.resolve(link.attr("href"))?.toString() ?: return@mapNotNull null
            val canonical = canonicalGalleryUrl(resolvedHref) ?: return@mapNotNull null
            val title = HtmlUrlCleaner.cleanText(
                link.text().ifBlank { link.attr("title") },
            )
            if (title.isBlank()) return@mapNotNull null
            val coverUrl = extractCoverUrl(base, card.selectFirst("img"))
            val infoText = card.selectFirst(".info_col")?.text().orEmpty()
            val pageCount = CARD_COUNT_REGEX.find(infoText)
                ?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            MediaItem(
                siteId = SiteId.WNACG,
                id = extractGalleryId(canonical),
                url = canonical,
                kind = MediaKind.COMIC,
                title = title,
                coverUrl = coverUrl,
                pageCount = pageCount,
            )
        }.distinctBy { it.id }

        val isExplicitEmptySearch = base.encodedPath == "/search/index.php" &&
            doc.select("#bodywrap .result > b")
                .any { it.text().replace(",", "").trim().toLongOrNull() == 0L }

        if (entries.isEmpty() && !isExplicitEmptySearch) {
            throw SiteException.ParseError("WNACG 未返回可识别的画廊列表", siteId = SiteId.WNACG)
        }

        val currentPage = request.page
        val nextPage = if (currentPage < Int.MAX_VALUE) currentPage + 1 else null
        val currentLink = parseFeedLink(url)
        val hasNext = entries.isNotEmpty() && nextPage != null &&
            doc.select("span.thispage + a[href]").any { anchor ->
                val target = base.resolve(anchor.attr("href"))?.takeIf(::isSiteUrl) ?: return@any false
                when (base.encodedPath) {
                    "/search/index.php" -> target.encodedPath == "/search/index.php" &&
                        target.queryParameter("p")?.toIntOrNull() == nextPage &&
                        target.queryParameter("q").let { it == null || it == base.queryParameter("q") }
                    else -> {
                        if (currentLink != null) {
                            parseFeedLink(target.toString()) == currentLink.copy(page = nextPage)
                        } else {
                            CATALOG_PATH_REGEX.matchEntire(target.encodedPath)
                                ?.groupValues?.get(1)?.toIntOrNull() == nextPage
                        }
                    }
                }
            }

        return FeedPage(
            items = entries,
            nextPage = if (hasNext) nextPage else null,
            hasMore = hasNext,
            title = heading,
        )
    }

    internal fun parseGalleryDetail(
        body: String,
        canonical: String,
        imageUrls: List<String>,
    ): GalleryDetail {
        val doc = Jsoup.parse(body, canonical)
        val base = ComicUrl.requireParse(canonical, SiteId.WNACG)
        val title = HtmlUrlCleaner.cleanText(doc.selectFirst("h2")?.text().orEmpty())
        if (title.isBlank()) {
            throw SiteException.ParseError("WNACG 未返回画廊详情", siteId = SiteId.WNACG)
        }

        val infoLabels = doc.select(".uwconn label").map { HtmlUrlCleaner.cleanText(it.text()) }
        val declaredCount = infoLabels
            .firstOrNull { it.startsWith("頁數：") || it.startsWith("页数：") }
            ?.substringAfter('：')?.trim()
            ?.let { DETAIL_COUNT_REGEX.find(it)?.value?.replace(",", "")?.toIntOrNull() }

        if (imageUrls.isEmpty() || (declaredCount != null && declaredCount != imageUrls.size)) {
            throw SiteException.ParseError("WNACG 图页数量不完整，请刷新画廊", siteId = SiteId.WNACG)
        }

        val categoryLabel = infoLabels
            .firstOrNull { it.startsWith("分類：") || it.startsWith("分类：") }
            ?.substringAfter('：')?.trim().orEmpty()
        val description = doc.select(".uwconn p")
            .firstOrNull { text ->
                val t = text.text().trim()
                t.startsWith("簡介：") || t.startsWith("简介：")
            }
            ?.wholeText()?.trim()
            ?.removePrefix("簡介：")?.removePrefix("简介：")?.trim()
            .orEmpty()
        val uploader = HtmlUrlCleaner.cleanText(doc.selectFirst(".uwuinfo p")?.text().orEmpty())

        val tags = doc.select(".addtags a.tagshow[href]").mapNotNull { anchor ->
            val label = HtmlUrlCleaner.cleanText(anchor.text())
            if (label.isEmpty()) return@mapNotNull null
            val href = base.resolve(anchor.attr("href"))?.toString() ?: return@mapNotNull null
            val tagReq = parseFeedLink(href)?.takeIf { it.scope == SearchScope.TAG } ?: return@mapNotNull null
            val slug = tagReq.query?.trim().orEmpty()
            if (slug.isEmpty()) return@mapNotNull null
            TagRef(
                slug = slug,
                label = label,
                namespace = "tag",
                url = resolveFeedUrl(tagReq.copy(page = 1)),
            )
        }.distinctBy { it.slug }

        val authors = if (uploader.isNotEmpty()) {
            listOf(
                AuthorRef(
                    id = uploader,
                    name = uploader,
                    role = "uploader",
                    url = resolveFeedUrl(FeedRequest(SiteId.WNACG, query = uploader, scope = SearchScope.AUTHOR)),
                ),
            )
        } else {
            emptyList()
        }

        val categories = if (categoryLabel.isNotEmpty()) {
            listOf(TagRef(slug = categoryLabel, label = categoryLabel, namespace = "category"))
        } else {
            emptyList()
        }

        val pages = imageUrls.mapIndexed { idx, imgUrl ->
            GalleryPage(
                index = idx + 1,
                pageUrl = "$canonical#${idx + 1}",
                imageUrl = imgUrl,
            )
        }

        val coverUrl = extractCoverUrl(base, doc.selectFirst(".uwthumb img")).ifBlank { imageUrls.first() }
        val item = MediaItem(
            siteId = SiteId.WNACG,
            id = extractGalleryId(canonical),
            url = canonical,
            kind = MediaKind.COMIC,
            title = title,
            coverUrl = coverUrl,
            pageCount = pages.size,
            author = authors.firstOrNull(),
            tags = tags,
            extra = buildMap {
                if (categoryLabel.isNotEmpty()) put("category", categoryLabel)
            },
        )

        return GalleryDetail(
            item = item,
            description = description,
            pageCount = pages.size,
            pages = pages,
            tags = tags,
            authors = authors,
            categories = categories,
        )
    }

    internal fun parseReaderImageUrls(body: String, readerUrl: String): List<String> {
        val unpackedScript = unpackDocumentWriteln(body) ?: body
        val arrayLiteral = extractImgListArray(unpackedScript)
            ?: throw SiteException.ParseError("WNACG 未返回可识别的图页列表", siteId = SiteId.WNACG)

        val rawUrls = IMAGE_ENTRY_REGEX.findAll(arrayLiteral).map { entry ->
            val token = IMAGE_FIELD_REGEX.findAll(entry.groupValues[1])
                .mapNotNull { it.groups[1]?.value }
                .singleOrNull()
                ?: throw SiteException.ParseError("WNACG 图页列表含有无效的图片地址", siteId = SiteId.WNACG)
            val inner = token.substring(1, token.length - 1)
            JsExtractor.unescapeJsString(inner)
        }.toList()

        val stripped = if (rawUrls.lastOrNull() == FAVORITE_REMINDER_PATH) {
            rawUrls.dropLast(1)
        } else {
            rawUrls
        }
        if (stripped.isEmpty()) {
            throw SiteException.ParseError("WNACG 图页列表为空", siteId = SiteId.WNACG)
        }

        val base = ComicUrl.requireParse(readerUrl, SiteId.WNACG)
        return stripped.map { raw ->
            if (raw == FAVORITE_REMINDER_PATH) {
                throw SiteException.ParseError("WNACG 图页列表含有非法占位图", siteId = SiteId.WNACG)
            }
            validateMediaUrl(base, raw, IMAGE_HOSTS)
                ?: throw SiteException.ParseError("WNACG 图页列表含有无效的图片地址：$raw", siteId = SiteId.WNACG)
        }
    }

    private fun unpackDocumentWriteln(body: String): String? {
        val quoted = WRITTEN_IMGLIST_REGEX.find(body)?.groupValues?.get(1) ?: return null
        return runCatching { json.decodeFromString<String>(quoted) }
            .getOrElse {
                JsExtractor.unescapeJsString(quoted.substring(1, quoted.length - 1))
            }
    }

    private fun extractImgListArray(script: String): String? {
        val match = IMGLIST_DECLARATION_REGEX.find(script) ?: return null
        val bracketIdx = match.range.last
        val balanced = JsExtractor.extractBalanced(script, bracketIdx, '[', ']') ?: return null
        return balanced.substring(1, balanced.length - 1)
    }

    internal data class PageIdentity(val canonical: String, val index: Int)

    internal fun parsePageIdentity(raw: String): PageIdentity? {
        val parsed = ComicUrl.parse(raw) ?: return null
        val index = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 && it.toString() == parsed.fragment }
            ?: return null
        val canonical = canonicalGalleryUrl(raw) ?: return null
        return PageIdentity(canonical = canonical, index = index)
    }

    private fun buildGalleryPage(identity: PageIdentity, urls: List<String>): GalleryPage {
        val idx = identity.index
        if (idx !in 1..urls.size) {
            throw SiteException.NotFound("页码超出范围：$idx / ${urls.size}", siteId = SiteId.WNACG)
        }
        return GalleryPage(
            index = idx,
            pageUrl = "${identity.canonical}#$idx",
            imageUrl = urls[idx - 1],
        )
    }

    internal fun canonicalGalleryUrl(raw: String): String? {
        val parsed = ComicUrl.parse(raw)?.takeIf(::isSiteUrl) ?: return null
        val aid = GALLERY_PATH_REGEX.matchEntire(parsed.encodedPath)?.groupValues?.get(1) ?: return null
        return "${descriptor.baseUrl}/photos-index-aid-$aid.html"
    }

    internal fun buildReaderUrl(canonical: String): String {
        val aid = extractGalleryId(canonical)
        return "${descriptor.baseUrl}/photos-gallery-aid-$aid.html"
    }

    internal fun parseFeedLink(raw: String): FeedRequest? {
        val parsed = ComicUrl.parse(raw)?.takeIf(::isSiteUrl) ?: return null
        if (parsed.encodedPath == "/search/index.php") {
            val query = parsed.queryParameter("q")?.takeIf { it.isNotBlank() } ?: return null
            val page = parsed.queryParameter("p")?.let { pStr ->
                pStr.toIntOrNull()?.takeIf { it > 0 } ?: return null
            } ?: 1
            return FeedRequest(siteId = SiteId.WNACG, query = query, page = page, scope = SearchScope.ALL)
        }
        val singleSegment = parsed.pathSegments.singleOrNull()?.let { "/$it" } ?: return null
        TAG_PATH_REGEX.matchEntire(singleSegment)?.let { m ->
            val page = m.groupValues[1].ifEmpty { "1" }.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val tag = m.groupValues[2].trim()
            if (tag.isEmpty()) return null
            return FeedRequest(siteId = SiteId.WNACG, query = tag, page = page, scope = SearchScope.TAG)
        }
        CATE_PATH_REGEX.matchEntire(singleSegment)?.let { m ->
            val page = m.groupValues[1].ifEmpty { "1" }.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val cate = m.groupValues[2].trim()
            if (cate.isEmpty()) return null
            return FeedRequest(siteId = SiteId.WNACG, category = cate, page = page)
        }
        return null
    }

    private fun extractGalleryId(canonical: String): String =
        canonical.substringAfterLast("-aid-").removeSuffix(".html")

    private fun isSiteUrl(url: ComicUrl): Boolean =
        url.isDefaultHttpsPort && descriptor.matchesHost(url.host)

    private fun extractCoverUrl(base: ComicUrl, img: Element?): String =
        img?.let { validateMediaUrl(base, it.attr("src"), COVER_HOSTS, COVER_HOST_REGEX) }.orEmpty()

    private fun validateMediaUrl(
        base: ComicUrl,
        raw: String,
        allowedHosts: Set<String>,
        hostRegex: Regex? = null,
    ): String? {
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val resolved = if (value.startsWith("//")) {
            ComicUrl.parseStrictHttps("https:$value")
        } else {
            base.resolve(value)?.takeIf { it.isDefaultHttpsPort }
        } ?: return null
        val matchesRegex = when {
            hostRegex != null -> hostRegex.matches(resolved.host)
            allowedHosts === IMAGE_HOSTS -> IMAGE_HOST_REGEX.matches(resolved.host)
            allowedHosts === COVER_HOSTS -> COVER_HOST_REGEX.matches(resolved.host)
            else -> false
        }
        val hostAllowed = isSiteUrl(resolved) || resolved.host in allowedHosts || matchesRegex
        if (!hostAllowed) return null
        if (!IMAGE_EXTENSION_REGEX.containsMatchIn(resolved.encodedPath)) return null
        return resolved.toString()
    }

    private fun feedHeading(request: FeedRequest): String {
        val query = request.query
        val category = request.category
        val section = request.section
        return when {
            !query.isNullOrBlank() -> query
            !category.isNullOrBlank() -> CATEGORIES[category] ?: ALL_CATEGORY_NAMES[category] ?: category
            section != null && section in CATEGORIES -> CATEGORIES[section] ?: "最新画廊"
            else -> "最新画廊"
        }
    }

    companion object {
        const val FAVORITE_REMINDER_PATH: String = "/themes/weitu/images/bg/shoucang.jpg"
        private const val DUAL_MERGE_PAGE_SIZE = 12

        val COVER_HOSTS: Set<String> = setOf("t2.wnimg1.ru", "t3.wnimg1.ru", "t4.qy0.ru")
        val IMAGE_HOSTS: Set<String> = setOf("img2.wnimg1.ru", "img5.wnimg1.ru", "img5.qy0.ru")
        private val COVER_HOST_REGEX = Regex("""^t\d+\.(?:wnimg1|qy0)\.ru$""", RegexOption.IGNORE_CASE)
        private val IMAGE_HOST_REGEX = Regex("""^(?:img|t)\d+\.(?:wnimg1|qy0)\.ru$""", RegexOption.IGNORE_CASE)

        val CATEGORIES: LinkedHashMap<String, String> = linkedMapOf(
            "5" to "同人志",
            "6" to "单行本",
            "7" to "杂志短篇",
            "16" to "CG画集",
            "19" to "韩漫",
            "3" to "Cosplay",
        )

        private val ALL_CATEGORY_NAMES: Map<String, String> = CATEGORIES + mapOf(
            "1" to "同人志·汉化",
            "12" to "同人志·日语",
            "9" to "单行本·汉化",
            "13" to "单行本·日语",
            "10" to "杂志短篇·汉化",
            "14" to "杂志短篇·日语",
            "20" to "韩漫·汉化",
        )

        private val ALL_VALID_CATEGORY_IDS: Set<String> = ALL_CATEGORY_NAMES.keys

        private val DUAL_LANGUAGE_CATEGORY_PAIRS: Map<String, Pair<String, String>> = mapOf(
            "5" to ("1" to "12"),
            "6" to ("9" to "13"),
            "7" to ("10" to "14"),
        )

        private val GALLERY_PATH_REGEX =
            Regex("""^/photos-(?:index|gallery|slide|list)(?:-page-[0-9]+)?-aid-([1-9][0-9]*)(?:-page-[0-9]+)?\.html$""")
        private val CATALOG_PATH_REGEX = Regex("""^/albums-index-page-([1-9][0-9]*)\.html$""")
        private val TAG_PATH_REGEX = Regex("""^/albums-index(?:-page-([1-9][0-9]*))?-tag-(.+)\.html$""")
        private val CATE_PATH_REGEX = Regex("""^/albums-index(?:-page-([1-9][0-9]*))?-cate-([1-9][0-9]*)\.html$""")
        private val CARD_COUNT_REGEX = Regex("""([0-9][0-9,]*)\s*[張张][圖图]片""")
        private val DETAIL_COUNT_REGEX = Regex("""^([0-9][0-9,]*)""")
        private val IMAGE_EXTENSION_REGEX = Regex("""\.(?:jpe?g|png|webp|gif|avif)$""", RegexOption.IGNORE_CASE)

        private const val QUOTED = """(?:"(?:[^"\\]|\\.)*+"|'(?:[^'\\]|\\.)*+')"""
        private val IMGLIST_DECLARATION_REGEX = Regex("""\b(?:var|let|const)\s+imglist\s*=\s*\[""")
        private val WRITTEN_IMGLIST_REGEX =
            Regex("""(?m)^[ \t]*document\.writeln\((".*\bvar imglist\s*=.*")\);[ \t]*$""")
        private val IMAGE_ENTRY_REGEX = Regex("""\{((?:$QUOTED|[^\{\}"'])*+)\}""")
        private val IMAGE_FIELD_REGEX =
            Regex("""(?:^|,)\s*(?:url|'url'|"url")\s*:\s*(?:fast_img_host\s*\+\s*)?($QUOTED)|$QUOTED""")

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.WNACG,
            displayName = "WNACG",
            shortName = "WNACG",
            primaryKind = MediaKind.COMIC,
            supportedKinds = setOf(MediaKind.COMIC),
            defaultHost = "www.wnacg.com",
            mirrorHosts = listOf("wnacg.com"),
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.LANGUAGE_FILTER,
            ),
            feedSorts = listOf(FeedSort.NEWEST),
            sections = buildList {
                add(SiteSectionSpec(id = "latest", title = "最新画廊", defaultSort = FeedSort.NEWEST))
                CATEGORIES.forEach { (key, title) ->
                    add(SiteSectionSpec(id = key, title = title, category = key, defaultSort = FeedSort.NEWEST))
                }
            },
            searchScopes = listOf(SearchScope.ALL, SearchScope.TAG, SearchScope.AUTHOR),
            supportedFilters = setOf(AdvancedFilterField.CATEGORY, AdvancedFilterField.LANGUAGE),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DESKTOP_WINDOWS_UA,
                referer = "https://www.wnacg.com/",
                mediaReferer = "https://www.wnacg.com/",
                mediaDomains = COVER_HOSTS + IMAGE_HOSTS,
            ),
        )
    }
}
