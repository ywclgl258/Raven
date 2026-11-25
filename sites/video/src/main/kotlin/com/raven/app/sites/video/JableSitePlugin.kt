package com.raven.app.sites.video

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSort
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
import com.raven.app.core.model.VideoDetail
import com.raven.app.core.model.VideoStream
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.JsExtractor
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.VideoSource
import com.raven.app.core.sitespi.suspendRunCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Jable (`jable.vip` primary, `jable.tv` mirror recognition) — MacCMS JAV tube.
 *
 * Uses `JsExtractor.extractBraceObject(html, "player_aaaa")` for HLS extraction and
 * extracts clean video titles from `<title>` `《...》` brackets.
 */
class JableSitePlugin(
    private val http: HttpClientAdapter,
    private val rateLimitBackoffMs: Long = 1_000L,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed = coroutineScope {
            val newRequest = FeedRequest(siteId = SiteId.JABLE, section = "new", sort = FeedSort.NEWEST)
            val hotRequest = FeedRequest(siteId = SiteId.JABLE, section = "hot", sort = FeedSort.POPULAR)
            val newDeferred = async { loadFeed(newRequest) }
            val hotDeferred = async { suspendRunCatching { loadFeed(hotRequest) }.getOrNull() }
            val newPage = newDeferred.await()
            val hotPage = hotDeferred.await()
            VideoSiteSupport.buildHomeFeed(
                siteId = SiteId.JABLE,
                primaryTitle = "最近更新",
                primaryPage = newPage,
                primarySeeAll = newRequest,
                rankedTitle = "最高人气",
                rankedPage = hotPage,
                rankedSeeAll = hotRequest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val url = resolveFeedUrl(request)
            val (response, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.JABLE,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, url)
            val items = parseCards(doc, BASE_URL)
            if (items.isEmpty() && !looksEmpty(doc)) {
                throw SiteException.ParseError("Jable 未返回可识别的列表，站点可能已改版", siteId = SiteId.JABLE)
            }
            val current = request.page.coerceAtLeast(1)
            val total = lastPage(doc)?.coerceAtLeast(current) ?: current
            val hasMore = current < total
            return FeedPage(
                items = items,
                nextPage = if (hasMore) current + 1 else null,
                hasMore = hasMore,
                totalPages = total,
                title = feedTitle(request),
                fromCache = response.fromCache,
            )
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = when {
            request.section == "hot" -> listOf(FeedSort.POPULAR)
            !request.query.isNullOrBlank() -> listOf(FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.POPULAR)
            else -> listOf(FeedSort.NEWEST)
        }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
        override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> =
            listOf(FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.POPULAR)
    }

    override val videoSource: VideoSource = object : VideoSource {
        override suspend fun loadVideoDetail(url: String): VideoDetail {
            val id = extractVideoId(url)
                ?: throw SiteException.ParseError("无法识别 Jable 作品地址", siteId = SiteId.JABLE)
            val target = "$BASE_URL/vodplay/$id-1-1/"
            val (_, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.JABLE,
                url = target,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, target)
            val streams = extractStreams(html)
            if (streams.isEmpty()) {
                throw SiteException.ParseError("详情页没有播放地址，可能需要 VIP 权限或站点已改版", siteId = SiteId.JABLE)
            }
            return parseDetail(doc, target, BASE_URL, streams)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val host = UrlSanitizer.extractHost(url) ?: return false
            return descriptor.matchesHost(host)
        }

        override fun canonicalize(url: String): String? {
            if (!canHandle(url)) return null
            extractVideoId(url)?.let { id -> return "$BASE_URL/vodplay/$id-1-1/" }
            return UrlSanitizer.normalizeHttpsUrl(url)
        }

        override fun resolve(url: String): ResolvedLink? {
            if (!canHandle(url)) return null
            extractVideoId(url)?.let { id ->
                return ResolvedLink.Video(
                    siteId = SiteId.JABLE,
                    id = id,
                    canonicalUrl = "$BASE_URL/vodplay/$id-1-1/",
                )
            }
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val parts = VideoSiteSupport.pathSegments(uri)
            if (parts.isEmpty()) {
                return ResolvedLink.Feed(FeedRequest(SiteId.JABLE, section = "new"))
            }
            return when (parts[0].lowercase()) {
                "vodsearch" -> {
                    val rawSegment = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                    val match = vodSearchSegmentRegex.matchEntire(rawSegment)
                    val query = HtmlUrlCleaner.cleanText(match?.groupValues?.get(1) ?: rawSegment)
                    val sort = if (match?.groupValues?.get(2) == "hits") FeedSort.POPULAR else FeedSort.NEWEST
                    val page = match?.groupValues?.get(4)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    ResolvedLink.Feed(
                        FeedRequest(
                            siteId = SiteId.JABLE,
                            query = query,
                            scope = SearchScope.ALL,
                            sort = sort,
                            page = page,
                        ),
                    )
                }
                "vodtype" -> {
                    val cat = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                    val page = if (parts.getOrNull(2) == "page") {
                        parts.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    } else {
                        1
                    }
                    ResolvedLink.Feed(FeedRequest(SiteId.JABLE, category = cat, page = page))
                }
                "label" -> {
                    val key = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                    val page = if (parts.getOrNull(2) == "page") {
                        parts.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    } else {
                        1
                    }
                    ResolvedLink.Feed(
                        FeedRequest(
                            siteId = SiteId.JABLE,
                            section = key,
                            sort = if (key == "hot") FeedSort.POPULAR else FeedSort.NEWEST,
                            page = page,
                        ),
                    )
                }
                // Recognize shared original `jable.tv/videos/<code-slug>/` links and route to search.
                "videos" -> {
                    val code = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                    ResolvedLink.Feed(
                        FeedRequest(
                            siteId = SiteId.JABLE,
                            query = code,
                            scope = SearchScope.ALL,
                            sort = FeedSort.NEWEST,
                        ),
                    )
                }
                else -> null
            }
        }
    }

    companion object {
        const val BASE_URL: String = "https://jable.vip"
        private val playPathRegex = Regex("^/vodplay/(\\d+)-\\d+-\\d+/?$")
        private val vodSearchSegmentRegex = Regex("^(.*?)-(time|hits)(-*)?(\\d+)?-*$")
        private val vodTypeRegex = Regex("/vodtype/(\\d+)/?")
        private val pagePathRegex = Regex("/page/(\\d+)/?")
        private val playerUrlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        private val bracketTitleRegex = Regex("《(.+?)》")

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.JABLE,
            displayName = "Jable",
            shortName = "Jable",
            primaryKind = MediaKind.VIDEO,
            supportedKinds = setOf(MediaKind.VIDEO),
            defaultHost = "jable.vip",
            mirrorHosts = listOf("jable.tv"),
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
                SiteCapability.RELATED,
            ),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = listOf(
                SiteSectionSpec("new", "最近更新", "刚上线的作品", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec("hot", "最高人气", defaultSort = FeedSort.POPULAR),
                SiteSectionSpec("c28", "中文字幕", category = "28"),
                SiteSectionSpec("c21", "日本有码", category = "21"),
                SiteSectionSpec("c22", "日本无码", category = "22"),
                SiteSectionSpec("c20", "国产", category = "20"),
                SiteSectionSpec("c23", "欧美", category = "23"),
                SiteSectionSpec("c24", "动漫", category = "24"),
            ),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                mediaDomains = setOf("senlin2026.com", "sl260204.top"),
            ),
        )

        fun resolveFeedUrl(request: FeedRequest, baseUrl: String = BASE_URL): String {
            val base = baseUrl.trimEnd('/')
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            val category = request.category?.trim().orEmpty()
            val section = request.section?.trim().orEmpty()

            if (query.isNotEmpty()) {
                val encoded = HtmlUrlCleaner.encodeQueryParam(query)
                val sortSuffix = if (request.sort == FeedSort.POPULAR || request.sort == FeedSort.VIEWS) {
                    "-hits"
                } else {
                    "-time"
                }
                val path = if (page <= 1) {
                    "/vodsearch/$encoded$sortSuffix----------/"
                } else {
                    "/vodsearch/$encoded$sortSuffix-----$page---/"
                }
                return "$base$path"
            }
            if (category.isNotEmpty()) {
                return paged("$base/vodtype/$category/", page)
            }
            if (section == "hot" || (section.isEmpty() && request.sort == FeedSort.POPULAR)) {
                return paged("$base/label/hot/", page)
            }
            if (section.startsWith("c") && section.drop(1).all(Char::isDigit) && section.length > 1) {
                return paged("$base/vodtype/${section.drop(1)}/", page)
            }
            val labelKey = section.ifBlank { "new" }
            return paged("$base/label/$labelKey/", page)
        }

        private fun paged(url: String, page: Int): String =
            if (page <= 1) url else "${url.trimEnd('/')}/page/$page/"

        fun feedTitle(request: FeedRequest): String {
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) return query
            val cat = request.category?.trim().orEmpty()
            if (cat.isNotEmpty()) {
                return DESCRIPTOR.sections.firstOrNull { it.category == cat || it.id == "c$cat" }?.title ?: "分类"
            }
            val sec = request.section?.trim().orEmpty()
            return DESCRIPTOR.sections.firstOrNull { it.id == sec }?.title ?: "最近更新"
        }

        fun extractVideoId(raw: String, baseUrl: String = BASE_URL): String? {
            if (raw.isBlank()) return null
            val candidate = if (raw.startsWith("/")) "${baseUrl.trimEnd('/')}$raw" else raw.trim()
            val uri = UrlSanitizer.parseUriOrNull(candidate) ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!DESCRIPTOR.matchesHost(host)) return null
            return playPathRegex.find(uri.rawPath.orEmpty())?.groupValues?.get(1)
        }

        fun parseCards(doc: Document, baseUrl: String = BASE_URL): List<MediaItem> =
            doc.select("div.video-img-box")
                .mapNotNull { parseCard(it, baseUrl) }
                .distinctBy { it.id }

        private fun parseCard(card: Element, baseUrl: String): MediaItem? {
            val link = card.selectFirst("h6.title a[href]") ?: card.selectFirst("a[href*=vodplay]") ?: return null
            val href = link.attr("href").ifBlank { link.absUrl("href") }
            val id = extractVideoId(href, baseUrl) ?: return null
            val title = HtmlUrlCleaner.cleanText(link.text())
                .ifBlank { HtmlUrlCleaner.cleanText(card.selectFirst("img")?.attr("alt").orEmpty()) }
            if (title.isBlank()) return null

            val image = card.selectFirst("img.lazyload, img")
            val thumbRaw = listOf(image?.attr("data-src"), image?.attr("src"))
                .firstOrNull {
                    !it.isNullOrBlank() &&
                        !it.startsWith("/template/", ignoreCase = true) &&
                        !it.startsWith("data:", ignoreCase = true)
                }
            val stats = card.select("p.sub-title span")
                .map { HtmlUrlCleaner.cleanText(it.text()) }
                .filter { it.isNotBlank() }
            return MediaItem(
                siteId = SiteId.JABLE,
                id = id,
                url = "${baseUrl.trimEnd('/')}/vodplay/$id-1-1/",
                kind = MediaKind.VIDEO,
                title = title,
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, thumbRaw),
                views = VideoSiteSupport.parseCount(stats.firstOrNull()),
                likes = VideoSiteSupport.parseCount(stats.getOrNull(1)),
            )
        }

        /**
         * Extracts the `player_aaaa` JS object via string-aware brace balancing ([JsExtractor.extractBraceObject])
         * and unescapes its `"url"` field to an HLS stream.
         */
        fun extractStreams(html: String): List<VideoStream> {
            val playerObj = JsExtractor.extractBraceObject(html, "player_aaaa") ?: return emptyList()
            val rawUrl = playerUrlRegex.find(playerObj)?.groupValues?.get(1) ?: return emptyList()
            val unescaped = JsExtractor.unescapeJsString(rawUrl).trim()
            if (!unescaped.startsWith("http://") && !unescaped.startsWith("https://")) return emptyList()
            val isHls = unescaped.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                unescaped.contains(".m3u8", ignoreCase = true)
            return listOf(
                VideoStream(
                    quality = "auto",
                    label = "自动",
                    url = unescaped,
                    isHls = isHls,
                    mimeTypeHint = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4",
                ),
            )
        }

        /**
         * MacCMS detail pages omit `<h1>`; clean the title from `<title>`'s `《...》` brackets first.
         */
        fun extractTitle(doc: Document, id: String): String {
            val rawTitle = doc.title().trim()
            val bracketed = bracketTitleRegex.find(rawTitle)?.groupValues?.get(1)?.let(HtmlUrlCleaner::cleanText)
            if (!bracketed.isNullOrBlank()) return bracketed
            val ogTitle = HtmlUrlCleaner.cleanText(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            if (ogTitle.isNotBlank()) return ogTitle
            val fallback = HtmlUrlCleaner.cleanText(rawTitle)
                .substringBefore("在线播放")
                .substringBefore(" | Jable")
                .trim()
            return fallback.ifBlank { "作品 $id" }
        }

        fun parseDetail(
            doc: Document,
            url: String,
            baseUrl: String,
            streams: List<VideoStream>,
        ): VideoDetail {
            val id = extractVideoId(url, baseUrl) ?: url.trimEnd('/').substringAfterLast('/')
            val posterRaw = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
                .ifBlank { doc.selectFirst("img.lazyload")?.attr("data-src").orEmpty() }
            val description = HtmlUrlCleaner.cleanText(
                doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(),
            ).take(240)

            val tags = doc.select("a[href*=vodsearch]").mapNotNull { a ->
                val label = HtmlUrlCleaner.cleanText(a.text())
                if (label.isBlank()) return@mapNotNull null
                val encoded = HtmlUrlCleaner.encodePathSegment(label)
                TagRef(
                    slug = encoded,
                    label = label,
                    url = "${baseUrl.trimEnd('/')}/vodsearch/$encoded-time----------/",
                )
            }.distinctBy { it.slug }.take(24)

            val categories = doc.select("a[href*=vodtype]").mapNotNull { a ->
                val catId = vodTypeRegex.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val label = HtmlUrlCleaner.cleanText(a.text())
                if (label.isBlank() || label == "更多") return@mapNotNull null
                TagRef(
                    slug = catId,
                    label = label,
                    namespace = "category",
                    url = "${baseUrl.trimEnd('/')}/vodtype/$catId/",
                )
            }.distinctBy { it.slug }

            val related = parseCards(doc, baseUrl).filter { it.id != id }
            val item = MediaItem(
                siteId = SiteId.JABLE,
                id = id,
                url = url,
                kind = MediaKind.VIDEO,
                title = extractTitle(doc, id),
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, posterRaw),
                tags = tags,
            )

            return VideoDetail(
                item = item,
                description = description,
                streams = streams,
                tags = tags,
                categories = categories,
                related = related,
            )
        }

        fun looksEmpty(doc: Document): Boolean {
            if (doc.select("div.video-img-box").isNotEmpty()) return false
            val text = doc.text()
            return text.contains("沒有找到", ignoreCase = true) ||
                text.contains("没有找到", ignoreCase = true) ||
                text.contains("暂无", ignoreCase = true)
        }

        fun lastPage(doc: Document): Int? =
            doc.select("ul.pagination a[href]").mapNotNull { a ->
                a.text().trim().toIntOrNull()
                    ?: pagePathRegex.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull()
    }
}
