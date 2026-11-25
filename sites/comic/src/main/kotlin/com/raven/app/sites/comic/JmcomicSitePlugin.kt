package com.raven.app.sites.comic

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
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HostRouter
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.ImageUnscrambler
import com.raven.app.core.sitespi.JmcomicStripMath
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.LruCache
import com.raven.app.core.sitespi.MirrorLeaseRouter
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * JMComic (`SiteId.JMCOMIC`) comic plugin.
 * Routes traffic across the 4 verified public mirrors via a 10-minute route-keyed [MirrorLeaseRouter],
 * supports multi-chapter albums (`#chapters`), and exposes [JmcomicStripMath] via [imageUnscrambler]
 * with per-page `unscrambleSeed` and `stripCount`.
 */
class JmcomicSitePlugin(
    private val httpClient: HttpClientAdapter,
    clockMs: () -> Long = System::currentTimeMillis,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    private val mirrorProbeUrls: List<String> = ALL_HOSTS.map { host -> "https://$host/albums?o=mr" }

    private val mirrorRouter = MirrorLeaseRouter(
        hosts = ALL_HOSTS,
        leaseDurationMs = MirrorLeaseRouter.DEFAULT_LEASE_DURATION_MS,
        clockMs = clockMs,
        probeHost = { host -> probeMirror(host) },
    )

    private val chapterCache = LruCache<String, ParsedChapter>(maxEntries = 16, clockMs = clockMs)
    private val routeLock = Any()
    private var lastObservedRouteKey: String? = null

    override val hostRouter: HostRouter = mirrorRouter

    override val imageUnscrambler: ImageUnscrambler = JmcomicStripMath

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(FeedRequest(siteId = SiteId.JMCOMIC, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.JMCOMIC, primaryFeedPage = page)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage = executeFeed(request)

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = when {
            isChaptersRequest(request) -> emptyList()
            !request.query.isNullOrBlank() -> SEARCH_SORTS
            else -> listOf(FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.LIKES, FeedSort.POPULAR)
        }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = executeFeed(request)

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)

        override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> = SEARCH_SORTS
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val identity = parseIdentity(url)
                ?: throw SiteException.ParseError("无法识别禁漫天堂漫画地址：$url", siteId = SiteId.JMCOMIC)
            val album = if (identity.kind == "album") {
                withMirror { host ->
                    val doc = fetchDocument(canonicalResourceUrl(identity), host)
                    parseAlbum(doc, identity.id)
                }
            } else {
                null
            }
            val chapter = loadChapter(album?.readingId ?: identity.id, forceReload = false)
            return buildGalleryDetail(album, chapter)
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.JMCOMIC)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val pageUrl = detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl
                ?: detail.pages.firstOrNull()?.pageUrl?.substringBefore('#')?.let { "$it#$pageIndex" }
                ?: throw SiteException.ParseError("无法定位禁漫天堂章节页地址", siteId = SiteId.JMCOMIC)
            return resolvePageImage(pageUrl = pageUrl, preferOriginal = false, forceReload = forceReload)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val parsed = parseSiteUrl(pageUrl)
                ?: throw SiteException.ParseError("无法识别禁漫天堂图页地址：$pageUrl", siteId = SiteId.JMCOMIC)
            val identity = parseIdentity(parsed)
                ?.takeIf { it.kind == "photo" }
                ?: throw SiteException.ParseError("无法识别禁漫天堂图页地址：$pageUrl", siteId = SiteId.JMCOMIC)
            val pageIndex = parsePositiveInt(parsed.fragment)
                ?: throw SiteException.ParseError("无法识别禁漫天堂图页编号：$pageUrl", siteId = SiteId.JMCOMIC)
            val reloadFlag = forceReload || parsed.queryParameter("reload") == "1"
            val chapter = loadChapter(identity.id, forceReload = reloadFlag)
            return buildGalleryPage(chapter, pageIndex)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean = parseSiteUrl(url) != null

        override fun canonicalize(url: String): String? {
            val parsed = parseSiteUrl(url) ?: return null
            parseIdentity(parsed)?.let { identity ->
                val base = canonicalResourceUrl(identity)
                return when {
                    identity.kind == "album" && parsed.fragment == CHAPTERS_FRAGMENT -> "$base#$CHAPTERS_FRAGMENT"
                    identity.kind == "photo" && parsePositiveInt(parsed.fragment) != null -> "$base#${parsed.fragment}"
                    else -> base
                }
            }
            return ComicUrl.parseStrictHttps("${descriptor.baseUrl}${parsed.encodedPath}")
                ?.withQuery(parsed.queryParams)
                ?.withFragment(null)
                ?.toString()
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = parseSiteUrl(url) ?: return null
            parseFeedLink(parsed)?.let { return ResolvedLink.Feed(it) }
            val identity = parseIdentity(parsed) ?: return null
            return when (identity.kind) {
                "album" -> ResolvedLink.Gallery(
                    siteId = SiteId.JMCOMIC,
                    id = identity.id,
                    canonicalUrl = canonicalResourceUrl(identity),
                )
                "photo" -> {
                    val pageIndex = parsePositiveInt(parsed.fragment)
                    val base = canonicalResourceUrl(identity)
                    ResolvedLink.Gallery(
                        siteId = SiteId.JMCOMIC,
                        id = "chapter-${identity.id}",
                        canonicalUrl = if (pageIndex != null) "$base#$pageIndex" else base,
                        pageIndex = pageIndex,
                    )
                }
                else -> null
            }
        }
    }

    // -------------------------------------------------------------------------
    // Mirror Routing & Failover
    // -------------------------------------------------------------------------

    private suspend fun <T> withMirror(block: suspend (host: String) -> T): T {
        val routeKey = httpClient.currentRouteKey(mirrorProbeUrls)
        synchronized(routeLock) {
            if (lastObservedRouteKey != null && lastObservedRouteKey != routeKey) {
                mirrorRouter.invalidate()
            }
            lastObservedRouteKey = routeKey
        }
        val attempted = LinkedHashSet<String>()
        var lastError: Throwable? = null

        repeat(ALL_HOSTS.size) {
            val preferred = mirrorRouter.activeHost(routeKey)
            val host = if (preferred !in attempted) {
                preferred
            } else {
                mirrorRouter.candidateHosts().firstOrNull { it !in attempted }
                    ?: return@repeat
            }
            attempted.add(host)
            try {
                val result = block(host)
                mirrorRouter.reportHostSuccess(host, routeKey)
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteException.NotFound) {
                throw e
            } catch (e: SiteException) {
                if (isRetryableMirrorFailure(e)) {
                    lastError = e
                    mirrorRouter.reportHostFailure(host, routeKey, e)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                lastError = e
                mirrorRouter.reportHostFailure(host, routeKey, e)
            }
        }

        throw (lastError as? SiteException) ?: SiteException.Network(
            message = "当前系统网络未找到可用的禁漫天堂公开镜像",
            siteId = SiteId.JMCOMIC,
            cause = lastError,
        )
    }

    private fun isRetryableMirrorFailure(error: SiteException): Boolean = when (error) {
        is SiteException.Network,
        is SiteException.TlsError,
        is SiteException.RateLimited,
        -> true
        is SiteException.Forbidden -> true
        else -> false
    }

    private suspend fun probeMirror(host: String): Boolean {
        return try {
            val doc = fetchDocument(
                canonicalUrl = "${descriptor.baseUrl}/albums?o=mr",
                host = host,
                kind = SiteRequestKind.PROBE,
            )
            val finalParsed = parseSiteUrl(doc.location())
            finalParsed != null && parseCards(doc).isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchDocument(
        canonicalUrl: String,
        host: String,
        kind: SiteRequestKind = SiteRequestKind.PAGE,
    ): Document {
        val initial = ComicUrl.requireParse(canonicalUrl, SiteId.JMCOMIC).withHost(host)
        var current = initial

        repeat(MAX_REDIRECTS) {
            val response = try {
                httpClient.execute(
                    SiteHttpRequest(
                        url = current.toString(),
                        siteId = SiteId.JMCOMIC,
                        method = SiteHttpMethod.GET,
                        kind = kind,
                        headers = mapOf("Referer" to "https://${current.host}/"),
                        followRedirects = false,
                        allowCookies = false,
                        allowCache = false,
                        allowRetry = false,
                        timeoutMs = MIRROR_TIMEOUT_MS,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteException) {
                throw e
            } catch (e: Exception) {
                throw SiteException.Network(
                    message = "禁漫天堂页面请求失败：${e.message ?: "网络异常"}",
                    siteId = SiteId.JMCOMIC,
                    cause = e,
                )
            }

            if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
                throw SiteException.Forbidden(
                    isChallenge = true,
                    message = "禁漫天堂要求完成浏览器验证",
                    siteId = SiteId.JMCOMIC,
                )
            }

            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                    ?: throw SiteException.Network("禁漫天堂跳转缺少目标地址", siteId = SiteId.JMCOMIC)
                val target = current.resolve(location)
                    ?.takeIf { isRedirectAllowed(initial, it) }
                    ?: throw SiteException.Network("禁漫天堂页面发生非预期跳转：$location", siteId = SiteId.JMCOMIC)
                current = target
                return@repeat
            }

            if (!response.isSuccessful) {
                when (response.code) {
                    404 -> throw SiteException.NotFound("禁漫天堂资源不存在（HTTP 404）", siteId = SiteId.JMCOMIC)
                    429 -> throw SiteException.RateLimited(
                        retryAfterSeconds = response.retryAfterSeconds,
                        message = "禁漫天堂请求频率受限",
                        siteId = SiteId.JMCOMIC,
                    )
                    401, 403 -> throw SiteException.Forbidden(
                        message = "禁漫天堂拒绝访问（HTTP ${response.code}）",
                        siteId = SiteId.JMCOMIC,
                    )
                    else -> throw SiteException.Network(
                        message = "禁漫天堂请求失败：HTTP ${response.code}",
                        siteId = SiteId.JMCOMIC,
                    )
                }
            }

            if (response.body.size > MAX_DOCUMENT_BYTES) {
                throw SiteException.Network("禁漫天堂页面过大", siteId = SiteId.JMCOMIC)
            }
            if (response.body.isEmpty()) {
                throw SiteException.Network("禁漫天堂返回空响应", siteId = SiteId.JMCOMIC)
            }
            val finalLocation = response.finalUrl.takeIf { it.isNotBlank() } ?: current.toString()
            return Jsoup.parse(response.bodyText(), finalLocation)
        }

        throw SiteException.Network("禁漫天堂页面跳转次数过多", siteId = SiteId.JMCOMIC)
    }

    // -------------------------------------------------------------------------
    // Core Feed, Album & Chapter Parsing
    // -------------------------------------------------------------------------

    private suspend fun executeFeed(request: FeedRequest): FeedPage {
        val canonicalUrl = resolveFeedUrl(request)
        return withMirror { host ->
            val doc = fetchDocument(canonicalUrl, host)
            if (isChaptersRequest(request)) {
                val albumId = extractChaptersAlbumId(request)
                    ?: throw SiteException.ParseError("无法识别禁漫天堂漫画编号", siteId = SiteId.JMCOMIC)
                buildChapterFeed(parseAlbum(doc, albumId))
            } else {
                parseFeed(doc, request, feedHeading(request))
            }
        }
    }

    private suspend fun loadChapter(chapterId: String, forceReload: Boolean): ParsedChapter {
        val routeKey = httpClient.currentRouteKey(mirrorProbeUrls)
        return withMirror { host ->
            val cacheKey = "$routeKey|$host|$chapterId"
            if (!forceReload) {
                chapterCache.get(cacheKey)?.let { return@withMirror it }
            }
            val canonical = canonicalResourceUrl(ResourceIdentity("photo", chapterId))
            val doc = fetchDocument(canonical, host)
            parseChapter(doc, chapterId).also { chapterCache.put(cacheKey, it) }
        }
    }

    internal fun resolveFeedUrl(request: FeedRequest): String {
        if (isChaptersRequest(request)) {
            val albumId = extractChaptersAlbumId(request)
                ?: throw SiteException.ParseError("无法识别禁漫天堂漫画编号", siteId = SiteId.JMCOMIC)
            return canonicalResourceUrl(ResourceIdentity("album", albumId))
        }
        if (request.page <= 0) {
            throw SiteException.ParseError("页码无效：${request.page}", siteId = SiteId.JMCOMIC)
        }
        var builder = ComicUrl.requireParse(descriptor.baseUrl, SiteId.JMCOMIC)
        val sortParam = sortToQueryParam(request.sort)
        val query = request.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            builder = builder.addPathSegments("search/photos")
                .addQueryParameter("search_query", query)
            if (sortParam != null) builder = builder.addQueryParameter("o", sortParam)
            if (request.page > 1) builder = builder.addQueryParameter("page", request.page.toString())
            return builder.toString()
        }

        val categorySlug = request.category?.trim()?.lowercase()?.takeIf { it in CATEGORY_SLUGS }
            ?: request.section?.trim()?.lowercase()?.takeIf { it in CATEGORY_SLUGS }
        builder = builder.addPathSegment("albums")
        if (categorySlug != null) {
            builder = builder.addPathSegment(categorySlug)
        }
        builder = builder.addQueryParameter("o", sortParam ?: "mr")
        if (request.page > 1) builder = builder.addQueryParameter("page", request.page.toString())
        return builder.toString()
    }

    internal data class ResourceIdentity(val kind: String, val id: String)
    internal data class Episode(val id: String, val title: String)
    internal data class ParsedAlbum(
        val item: MediaItem,
        val readingId: String,
        val episodes: List<Episode>,
        val description: String,
    )
    internal data class ParsedPage(
        val imageUrl: String,
        val stripCount: Int,
        val unscrambleSeed: String?,
    )
    internal data class ParsedChapter(
        val id: String,
        val title: String,
        val referer: String,
        val pages: List<ParsedPage>,
    )
    private data class Card(val identity: ResourceIdentity, val title: String, val coverUrl: String)

    internal fun parseFeed(doc: Document, request: FeedRequest, heading: String): FeedPage {
        val base = ComicUrl.requireParse(doc.location(), SiteId.JMCOMIC)
        val isEmptySearch = base.encodedPath == SEARCH_PATH &&
            doc.select("span.text-danger").any { it.text().trim() == "查無A漫." } &&
            doc.select("h3").any { text ->
                val t = text.text().trim()
                t.startsWith("找不到和") && t.endsWith("相符的內容")
            }

        val entries = if (isEmptySearch) {
            emptyList()
        } else {
            parseCards(doc).map { card ->
                buildMediaItem(card.identity, card.title, card.coverUrl)
            }.distinctBy { it.id }
        }

        if (entries.isEmpty() && !isEmptySearch) {
            throw SiteException.Network("禁漫天堂未返回可识别的漫画列表", siteId = SiteId.JMCOMIC)
        }

        val activeFeed = parseFeedLink(base)
            ?: throw SiteException.ParseError("无法识别禁漫天堂列表地址：${doc.location()}", siteId = SiteId.JMCOMIC)
        val nextPage = if (request.page < Int.MAX_VALUE) request.page + 1 else null
        val hasNext = entries.isNotEmpty() && nextPage != null &&
            doc.select(".pagination a[href], .pagination option[value]").any { el ->
                val attr = if (el.tagName().equals("option", ignoreCase = true)) "value" else "href"
                val target = base.resolve(el.attr(attr)) ?: return@any false
                parseFeedLink(target) == activeFeed.copy(page = nextPage)
            }

        return FeedPage(
            items = entries,
            nextPage = if (hasNext) nextPage else null,
            hasMore = hasNext,
            title = heading,
        )
    }

    private fun parseCards(doc: Document): List<Card> {
        val base = ComicUrl.parse(doc.location()) ?: return emptyList()
        return doc.select(".list-col").mapNotNull { card ->
            val overlay = card.selectFirst(".thumb-overlay-albums, .thumb-overlay") ?: return@mapNotNull null
            val link = overlay.selectFirst("a[href]") ?: return@mapNotNull null
            val resolved = base.resolve(link.attr("href")) ?: return@mapNotNull null
            val identity = parseIdentity(resolved)?.takeIf { it.kind == "album" } ?: return@mapNotNull null
            val title = HtmlUrlCleaner.cleanText(card.selectFirst(".video-title")?.text().orEmpty())
            if (title.isBlank()) return@mapNotNull null
            Card(identity, title, extractCoverUrl(base, overlay.selectFirst("img")))
        }
    }

    internal fun parseAlbum(doc: Document, expectedId: String): ParsedAlbum {
        val base = ComicUrl.requireParse(doc.location(), SiteId.JMCOMIC)
        val title = HtmlUrlCleaner.cleanText(doc.selectFirst("#book-name")?.text().orEmpty())
        if (title.isBlank()) {
            throw SiteException.Network("禁漫天堂未返回可识别的漫画详情", siteId = SiteId.JMCOMIC)
        }
        val expectedIdentity = ResourceIdentity("album", expectedId)
        if (parseIdentity(base) != expectedIdentity) {
            throw SiteException.ParseError("禁漫天堂返回了其他漫画", siteId = SiteId.JMCOMIC)
        }

        val episodes = doc.select("#episode-block .episode a[href]").mapNotNull { anchor ->
            val chapterId = extractChapterId(base, anchor) ?: return@mapNotNull null
            val label = HtmlUrlCleaner.cleanText(anchor.text()).ifBlank { "章节 $chapterId" }
            Episode(chapterId, label)
        }.distinctBy { it.id }

        val readingId = doc.select("a.reading[href]")
            .firstNotNullOfOrNull { extractChapterId(base, it) }
            ?: episodes.firstOrNull()?.id
            ?: throw SiteException.ParseError("禁漫天堂未提供公开阅读入口", siteId = SiteId.JMCOMIC)

        val coverUrl = doc.select("img[itemprop=image]")
            .asSequence()
            .map { extractCoverUrl(base, it) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

        val description = HtmlUrlCleaner.cleanText(
            doc.selectFirst("meta[name=description]")?.attr("content").orEmpty(),
        )

        return ParsedAlbum(
            item = buildMediaItem(expectedIdentity, title, coverUrl),
            readingId = readingId,
            episodes = episodes,
            description = description,
        )
    }

    internal fun buildChapterFeed(album: ParsedAlbum): FeedPage {
        val items = album.episodes.map { episode ->
            buildMediaItem(
                identity = ResourceIdentity("photo", episode.id),
                title = "${album.item.title} · ${episode.title}",
                coverUrl = album.item.coverUrl,
            )
        }
        return FeedPage(
            items = items,
            nextPage = null,
            hasMore = false,
            totalCount = items.size,
            totalPages = 1,
            title = "全部章节",
        )
    }

    internal fun parseChapter(doc: Document, chapterId: String): ParsedChapter {
        val base = ComicUrl.requireParse(doc.location(), SiteId.JMCOMIC)
        if (parseIdentity(base) != ResourceIdentity("photo", chapterId)) {
            throw SiteException.ParseError("禁漫天堂返回了其他章节", siteId = SiteId.JMCOMIC)
        }
        val title = HtmlUrlCleaner.cleanText(doc.selectFirst("h1.pull-left")?.text().orEmpty())
        val images = doc.select("img[id^=album_photo]")
        if (title.isBlank() || images.isEmpty()) {
            throw SiteException.ParseError("禁漫天堂未返回可识别的公开图页", siteId = SiteId.JMCOMIC)
        }

        val vars = HashMap<String, String>()
        doc.select("script:not([src])").forEach { scriptEl ->
            SCRIPT_VAR_REGEX.findAll(scriptEl.data()).forEach { m ->
                vars[m.groupValues[1]] = m.groupValues[3]
            }
        }
        if (vars["aid"] != null && vars["aid"] != chapterId) {
            throw SiteException.ParseError("禁漫天堂返回了其他章节脚本参数", siteId = SiteId.JMCOMIC)
        }

        val indexedPages = images.map { img ->
            val pageIdx = img.attr("data-page").toIntOrNull()?.takeIf { it >= 0 }
                ?: throw SiteException.ParseError("禁漫天堂图页编号无效", siteId = SiteId.JMCOMIC)
            val rawOriginal = img.attr("data-original")
            val mediaUrl = validateMediaUrl(base, rawOriginal)
                ?: throw SiteException.ParseError("JMComic 第 ${pageIdx + 1} 页返回未知图源", siteId = SiteId.JMCOMIC)
            val segments = mediaUrl.pathSegments
            if (segments.size != 4 ||
                segments.take(3) != listOf("media", "photos", chapterId) ||
                !MEDIA_FILE_REGEX.matches(segments.last())
            ) {
                throw SiteException.ParseError(
                    "JMComic 第 ${pageIdx + 1} 页的图片路径与当前章节不匹配",
                    siteId = SiteId.JMCOMIC,
                )
            }

            val filename = segments.last()
            val container = img.parent()?.takeIf { it.hasClass("scramble-page") }
            val (strips, seed) = if (container == null || vars["speed"] == "1" || filename.endsWith(".gif", ignoreCase = true)) {
                0 to null
            } else {
                val scrambleId = vars["scramble_id"]?.toLongOrNull()
                    ?: throw SiteException.ParseError("禁漫天堂图页缺少公开显示参数", siteId = SiteId.JMCOMIC)
                if (vars["aid"] != chapterId || container.id() != filename) {
                    throw SiteException.ParseError("禁漫天堂图页显示参数与图片地址不符", siteId = SiteId.JMCOMIC)
                }
                val filenameStem = filename.substringBefore('.')
                val computedStrips = JmcomicStripMath.computeStripCount(
                    chapterId = chapterId.toLong(),
                    scrambleId = scrambleId,
                    filenameStem = filenameStem,
                )
                val seedValue = if (computedStrips > 0) "$chapterId:$scrambleId:$filenameStem" else null
                computedStrips to seedValue
            }

            pageIdx to ParsedPage(
                imageUrl = mediaUrl.toString(),
                stripCount = strips,
                unscrambleSeed = seed,
            )
        }.distinct().sortedBy { it.first }

        if (!indexedPages.withIndex().all { it.index == it.value.first }) {
            throw SiteException.ParseError("禁漫天堂图页列表不完整或编号重复", siteId = SiteId.JMCOMIC)
        }

        return ParsedChapter(
            id = chapterId,
            title = title,
            referer = base.toString(),
            pages = indexedPages.map { it.second },
        )
    }

    internal fun buildGalleryDetail(album: ParsedAlbum?, chapter: ParsedChapter): GalleryDetail {
        val count = chapter.pages.size
        val baseItem = album?.item ?: buildMediaItem(
            identity = ResourceIdentity("photo", chapter.id),
            title = chapter.title,
            coverUrl = "",
        )
        val pages = chapter.pages.mapIndexed { idx, p ->
            val pageNum = idx + 1
            GalleryPage(
                index = pageNum,
                pageUrl = buildPageUrl(chapter.id, pageNum),
                imageUrl = p.imageUrl,
                stripCount = p.stripCount,
                unscrambleSeed = p.unscrambleSeed,
            )
        }
        val chaptersUrl = if (album != null && album.episodes.size > 1) {
            "${album.item.url}#$CHAPTERS_FRAGMENT"
        } else {
            null
        }
        return GalleryDetail(
            item = baseItem.copy(
                pageCount = count,
                extra = baseItem.extra + buildMap {
                    put("chapterId", chapter.id)
                    put("imageReferer", chapter.referer)
                },
            ),
            description = album?.description.orEmpty(),
            pageCount = count,
            pages = pages,
            chaptersUrl = chaptersUrl,
        )
    }

    internal fun buildGalleryPage(chapter: ParsedChapter, pageIndex: Int): GalleryPage {
        if (pageIndex !in 1..chapter.pages.size) {
            throw SiteException.NotFound("页码超出范围：$pageIndex / ${chapter.pages.size}", siteId = SiteId.JMCOMIC)
        }
        val p = chapter.pages[pageIndex - 1]
        return GalleryPage(
            index = pageIndex,
            pageUrl = buildPageUrl(chapter.id, pageIndex),
            imageUrl = p.imageUrl,
            stripCount = p.stripCount,
            unscrambleSeed = p.unscrambleSeed,
        )
    }

    // -------------------------------------------------------------------------
    // URL & Validation Helpers
    // -------------------------------------------------------------------------

    internal fun isRedirectAllowed(initial: ComicUrl, target: ComicUrl): Boolean {
        if (!isSiteUrl(target) || target.fragment != null) return false
        val identity = parseIdentity(initial)
        return if (identity != null) {
            parseIdentity(target) == identity && target.rawQuery == initial.rawQuery
        } else {
            target.encodedPath == initial.encodedPath && parseFeedLink(target) == parseFeedLink(initial)
        }
    }

    internal fun parseFeedLink(url: ComicUrl): FeedRequest? {
        if (!isSiteUrl(url)) return null
        val page = url.queryParameter("page")?.let { parsePositiveInt(it) ?: return null } ?: 1
        return when {
            url.encodedPath == SEARCH_PATH -> {
                val query = url.queryParameter("search_query")?.takeIf { it.isNotBlank() } ?: return null
                val sort = parseSortQueryParam(url.queryParameter("o")) ?: FeedSort.RELEVANCE
                FeedRequest(siteId = SiteId.JMCOMIC, query = query, sort = sort, page = page)
            }
            url.encodedPath == CATALOGUE_PATH -> {
                val oParam = url.queryParameter("o")
                val sort = if (oParam == null) FeedSort.NEWEST else parseSortQueryParam(oParam) ?: return null
                FeedRequest(siteId = SiteId.JMCOMIC, section = "latest", sort = sort, page = page)
            }
            url.encodedPath.startsWith("$CATALOGUE_PATH/") -> {
                val slug = url.pathSegments.getOrNull(1)?.lowercase()
                val oParam = url.queryParameter("o")
                val sort = if (oParam == null) FeedSort.NEWEST else parseSortQueryParam(oParam)
                if (url.pathSegments.size == 2 && slug in CATEGORY_SLUGS && sort != null) {
                    FeedRequest(siteId = SiteId.JMCOMIC, category = slug, sort = sort, page = page)
                } else {
                    null
                }
            }
            else -> {
                parseIdentity(url)
                    ?.takeIf { it.kind == "album" && url.fragment == CHAPTERS_FRAGMENT }
                    ?.let { FeedRequest(siteId = SiteId.JMCOMIC, section = CHAPTERS_SECTION, category = it.id) }
            }
        }
    }

    internal fun parseSiteUrl(raw: String): ComicUrl? =
        ComicUrl.parseStrictHttps(raw)?.takeIf(::isSiteUrl)

    internal fun parseIdentity(raw: String): ResourceIdentity? =
        parseSiteUrl(raw)?.let(::parseIdentity)

    internal fun parseIdentity(url: ComicUrl): ResourceIdentity? {
        if (!isSiteUrl(url)) return null
        val match = RESOURCE_PATH_REGEX.matchEntire(url.encodedPath) ?: return null
        val kind = match.groupValues[1]
        val id = match.groupValues[2]
        return if (isValidId(id)) ResourceIdentity(kind, id) else null
    }

    private fun canonicalResourceUrl(identity: ResourceIdentity): String =
        "${descriptor.baseUrl}/${identity.kind}/${identity.id}"

    private fun buildPageUrl(chapterId: String, pageIndex: Int): String =
        "${canonicalResourceUrl(ResourceIdentity("photo", chapterId))}#$pageIndex"

    private fun buildMediaItem(identity: ResourceIdentity, title: String, coverUrl: String): MediaItem = MediaItem(
        siteId = SiteId.JMCOMIC,
        id = if (identity.kind == "album") identity.id else "chapter-${identity.id}",
        url = canonicalResourceUrl(identity),
        kind = MediaKind.COMIC,
        title = title,
        coverUrl = coverUrl,
    )

    private fun extractChapterId(base: ComicUrl, anchor: Element): String? =
        base.resolve(anchor.attr("href"))?.let(::parseIdentity)?.takeIf { it.kind == "photo" }?.id

    private fun extractCoverUrl(base: ComicUrl, img: Element?): String {
        if (img == null) return ""
        for (attr in COVER_ATTRS) {
            val resolved = validateMediaUrl(base, img.attr(attr)) ?: continue
            val segs = resolved.pathSegments
            if (segs.size == 3 && segs.take(2) == listOf("media", "albums") && COVER_FILE_REGEX.matches(segs.last())) {
                return resolved.toString()
            }
        }
        return ""
    }

    private fun validateMediaUrl(base: ComicUrl, raw: String): ComicUrl? {
        val trimmed = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val resolved = base.resolve(trimmed)?.takeIf { it.isDefaultHttpsPort && it.fragment == null } ?: return null
        val allowed = resolved.host in CDN_HOSTS ||
            CDN_HOST_REGEX.matches(resolved.host) ||
            isSiteUrl(resolved)
        return if (allowed) resolved else null
    }

    private fun isSiteUrl(url: ComicUrl): Boolean =
        url.isDefaultHttpsPort && url.host in ALL_HOSTS_SET

    private fun isChaptersRequest(request: FeedRequest): Boolean {
        val category = request.category
        return request.section.equals(CHAPTERS_SECTION, ignoreCase = true) ||
            (category != null && isValidId(category.trim()))
    }

    private fun extractChaptersAlbumId(request: FeedRequest): String? {
        request.category?.trim()?.takeIf(::isValidId)?.let { return it }
        request.query?.trim()?.takeIf(::isValidId)?.let { return it }
        return null
    }

    private fun feedHeading(request: FeedRequest): String {
        val query = request.query
        val category = request.category
        val section = request.section
        return when {
            isChaptersRequest(request) -> "全部章节"
            !query.isNullOrBlank() -> query.trim()
            !category.isNullOrBlank() -> CATEGORIES[category.lowercase()] ?: category
            section != null && section in CATEGORIES -> CATEGORIES[section] ?: "最新漫画"
            else -> "最新漫画"
        }
    }

    private fun isValidId(id: String): Boolean =
        POSITIVE_ID_REGEX.matches(id) && id.toLongOrNull() != null

    private fun parsePositiveInt(raw: String?): Int? =
        raw?.takeIf(POSITIVE_ID_REGEX::matches)?.toIntOrNull()

    companion object {
        const val CHAPTERS_FRAGMENT: String = "chapters"
        const val CHAPTERS_SECTION: String = "chapters"

        private const val CATALOGUE_PATH = "/albums"
        private const val SEARCH_PATH = "/search/photos"
        private const val MAX_REDIRECTS = 3
        private const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
        private const val MIRROR_TIMEOUT_MS = 5_000L

        val ALL_HOSTS: List<String> = listOf(
            "jmcomic-zzz.one",
            "18comic.ink",
            "jmcomic-zzz.org",
            "18comic.vip",
        )
        private val ALL_HOSTS_SET: Set<String> = ALL_HOSTS.toSet()

        val CDN_HOSTS: Set<String> = setOf(
            "cdn-msp.18comic.ink",
            "cdn-msp2.18comic.ink",
            "cdn-msp3.18comic.ink",
            "cdn-msp.jmcomic-zzz.one",
            "cdn-msp2.jmcomic-zzz.one",
            "cdn-msp3.jmcomic-zzz.one",
        )
        private val CDN_HOST_REGEX = Regex(
            """^cdn-msp\d*\.(?:18comic\.(?:ink|vip)|jmcomic-zzz\.(?:one|org))$""",
            RegexOption.IGNORE_CASE,
        )

        val CATEGORIES: LinkedHashMap<String, String> = linkedMapOf(
            "doujin" to "同人",
            "single" to "单本",
            "short" to "短篇",
            "hanman" to "韩漫",
            "another" to "其他",
        )
        private val CATEGORY_SLUGS: Set<String> = CATEGORIES.keys

        private val REDIRECT_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)
        private val COVER_ATTRS: List<String> = listOf("data-original", "data-src", "src")
        private val RESOURCE_PATH_REGEX = Regex("""^/(album|photo)/([1-9][0-9]*)(?:/[^/]+)?/?$""")
        private val POSITIVE_ID_REGEX = Regex("""^[1-9][0-9]*$""")
        private val MEDIA_FILE_REGEX = Regex("""^[0-9]+\.(?:jpe?g|png|gif|webp|avif)$""", RegexOption.IGNORE_CASE)
        private val COVER_FILE_REGEX =
            Regex("""^[0-9]+(?:_[0-9]+x[0-9]+)?\.(?:jpe?g|png|gif|webp|avif)$""", RegexOption.IGNORE_CASE)
        private val SCRIPT_VAR_REGEX =
            Regex("""(?:^|(?<=[;\r\n]))\s*(?:var|let|const)\s+(scramble_id|aid|speed)\s*=\s*(['"]?)([0-9]*)\2\s*;""")

        val SEARCH_SORTS: List<FeedSort> = listOf(
            FeedSort.RELEVANCE,
            FeedSort.NEWEST,
            FeedSort.VIEWS,
            FeedSort.LIKES,
            FeedSort.POPULAR,
        )

        private fun sortToQueryParam(sort: FeedSort): String? = when (sort) {
            FeedSort.NEWEST -> "mr"
            FeedSort.VIEWS -> "mv"
            FeedSort.LIKES -> "tf"
            FeedSort.POPULAR -> "mp"
            else -> null
        }

        private fun parseSortQueryParam(raw: String?): FeedSort? = when (raw?.lowercase()) {
            "mr" -> FeedSort.NEWEST
            "mv" -> FeedSort.VIEWS
            "tf" -> FeedSort.LIKES
            "mp" -> FeedSort.POPULAR
            else -> null
        }

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.JMCOMIC,
            displayName = "JMComic",
            shortName = "JMComic",
            primaryKind = MediaKind.COMIC,
            supportedKinds = setOf(MediaKind.COMIC),
            defaultHost = ALL_HOSTS.first(),
            mirrorHosts = ALL_HOSTS.drop(1),
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.MIRRORS,
            ),
            accessNote = "自动选择镜像",
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.LIKES, FeedSort.POPULAR, FeedSort.RELEVANCE),
            sections = buildList {
                add(SiteSectionSpec(id = "latest", title = "最新漫画", defaultSort = FeedSort.NEWEST))
                CATEGORIES.forEach { (key, title) ->
                    add(SiteSectionSpec(id = key, title = title, category = key, defaultSort = FeedSort.NEWEST))
                }
            },
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "https://${ALL_HOSTS.first()}/",
                mediaReferer = "https://${ALL_HOSTS.first()}/",
                mediaDomains = CDN_HOSTS,
            ),
        )
    }
}
