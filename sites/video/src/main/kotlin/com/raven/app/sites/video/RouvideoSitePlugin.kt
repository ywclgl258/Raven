package com.raven.app.sites.video

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DirectoryEntry
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
import com.raven.app.core.sitespi.DirectorySource
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.JsExtractor
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.MediaTransportHook
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.RouPngProtocol
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.VideoSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * rou.video (`rou.video`) — TanStack Start SSR video site.
 *
 * Decodes `ev` base64 + byte-offset `-k` payload via [RouPngProtocol] -> `/api/hls/<id>`,
 * and exposes [RouPngProtocol.transportHook] on [mediaTransportHook] for PNG `roUd` unwrapping.
 */
class RouvideoSitePlugin(
    private val http: HttpClientAdapter,
    private val rateLimitBackoffMs: Long = 1_000L,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val mediaTransportHook: MediaTransportHook = RouPngProtocol.transportHook

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val homeRequest = FeedRequest(siteId = SiteId.ROUVIDEO, section = "home", sort = FeedSort.NEWEST)
            val primaryPage = loadFeed(homeRequest)
            return VideoSiteSupport.buildHomeFeed(
                siteId = SiteId.ROUVIDEO,
                primaryTitle = "为你推荐",
                primaryPage = primaryPage,
                primarySeeAll = homeRequest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val url = resolveFeedUrl(request)
            val (response, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.ROUVIDEO,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, url)
            val items = parseCards(doc, BASE_URL)
            if (items.isEmpty() && !looksEmpty(doc)) {
                throw SiteException.ParseError("rou.video 未返回可识别的列表，站点可能已改版", siteId = SiteId.ROUVIDEO)
            }
            val current = request.page.coerceAtLeast(1)
            val total = totalPages(doc, current)
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

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = listOf(FeedSort.NEWEST)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL, SearchScope.TAG)
        override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> = listOf(FeedSort.NEWEST)
    }

    override val videoSource: VideoSource = object : VideoSource {
        override suspend fun loadVideoDetail(url: String): VideoDetail {
            val canonical = canonicalVideoUrl(url)
                ?: throw SiteException.ParseError("无法识别 rou.video 作品地址", siteId = SiteId.ROUVIDEO)
            val (_, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.ROUVIDEO,
                url = canonical,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, canonical)
            val (streams, thumbVttUrl) = extractStreamsAndVtt(html, BASE_URL)
            if (streams.isEmpty()) {
                throw SiteException.ParseError("详情页没有播放地址，站点可能已改版或该片需要会员", siteId = SiteId.ROUVIDEO)
            }
            return parseDetail(doc, canonical, BASE_URL, streams, thumbVttUrl)
        }
    }

    override val directorySource: DirectorySource = object : DirectorySource {
        override suspend fun loadDirectory(
            section: String,
            page: Int,
            query: String?,
        ): List<DirectoryEntry> {
            if (page > 1) return emptyList()
            if (section != "category" && section != "series") return emptyList()
            val url = "$BASE_URL/cat"
            val (_, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.ROUVIDEO,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, url)
            val entries = if (section == "series") {
                doc.select("a[href^='/s/']").mapNotNull { a ->
                    val href = a.attr("href")
                    val id = extractSeriesId(href) ?: return@mapNotNull null
                    DirectoryEntry(
                        siteId = SiteId.ROUVIDEO,
                        id = id,
                        title = HtmlUrlCleaner.cleanText(a.text()).ifBlank { "系列" },
                        url = "$BASE_URL/s/$id",
                        kind = SearchScope.TAG,
                    )
                }.distinctBy { it.id }
            } else {
                val primary = doc.select("a[href*=series?tag=]").mapNotNull { a ->
                    val encoded = a.attr("href").substringAfter("tag=", "").substringBefore('&')
                    if (encoded.isBlank()) return@mapNotNull null
                    val tag = HtmlUrlCleaner.decodeUrlComponent(encoded)
                    DirectoryEntry(
                        siteId = SiteId.ROUVIDEO,
                        id = tag,
                        title = HtmlUrlCleaner.cleanText(a.text()).ifBlank { tag },
                        url = "$BASE_URL/v?tag=$encoded",
                        kind = SearchScope.ALL,
                    )
                }.distinctBy { it.id }
                if (primary.isNotEmpty()) {
                    primary
                } else {
                    doc.select("a[href*=tag=]").mapNotNull { a ->
                        val encoded = a.attr("href").substringAfter("tag=", "").substringBefore('&')
                        val tag = HtmlUrlCleaner.decodeUrlComponent(encoded)
                        if (tag.isBlank()) return@mapNotNull null
                        DirectoryEntry(
                            siteId = SiteId.ROUVIDEO,
                            id = tag,
                            title = HtmlUrlCleaner.cleanText(a.text()).ifBlank { tag },
                            url = VideoSiteSupport.resolveAbsolute(BASE_URL, a.attr("href")),
                            kind = SearchScope.ALL,
                        )
                    }.distinctBy { it.id }
                }
            }
            val q = query?.trim().orEmpty()
            return if (q.isEmpty()) {
                entries
            } else {
                entries.filter { it.title.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
            }
        }

        override fun directorySections(): List<SiteSectionSpec> =
            descriptor.sections.filter { it.isDirectory }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val host = UrlSanitizer.extractHost(url) ?: return false
            return descriptor.matchesHost(host)
        }

        override fun canonicalize(url: String): String? {
            if (!canHandle(url)) return null
            canonicalVideoUrl(url)?.let { return it }
            return UrlSanitizer.normalizeHttpsUrl(url)
        }

        override fun resolve(url: String): ResolvedLink? {
            if (!canHandle(url)) return null
            canonicalVideoUrl(url)?.let { canonical ->
                val id = canonical.substringAfterLast('/')
                return ResolvedLink.Video(SiteId.ROUVIDEO, id, canonical)
            }
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val parts = VideoSiteSupport.pathSegments(uri)
            val page = VideoSiteSupport.queryParam(uri, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            return when (parts.firstOrNull()?.lowercase()) {
                "s" -> extractSeriesId(url)?.let { sid ->
                    ResolvedLink.Feed(FeedRequest(SiteId.ROUVIDEO, query = sid, scope = SearchScope.TAG, page = page))
                }
                "search" -> VideoSiteSupport.queryParam(uri, "q")?.takeIf { it.isNotBlank() }?.let { q ->
                    ResolvedLink.Feed(FeedRequest(SiteId.ROUVIDEO, query = q, scope = SearchScope.ALL, page = page))
                }
                "v", "series" -> {
                    val tag = VideoSiteSupport.queryParam(uri, "tag")?.takeIf { it.isNotBlank() }
                    if (tag != null) {
                        ResolvedLink.Feed(FeedRequest(SiteId.ROUVIDEO, category = tag, page = page))
                    } else if (parts.first().equals("v", ignoreCase = true)) {
                        ResolvedLink.Feed(FeedRequest(SiteId.ROUVIDEO, section = "v", page = page))
                    } else {
                        null
                    }
                }
                "cat" -> ResolvedLink.Directory(SiteId.ROUVIDEO, "category", "$BASE_URL/cat")
                "home", null -> ResolvedLink.Feed(FeedRequest(SiteId.ROUVIDEO, section = "home", page = page))
                else -> null
            }
        }
    }

    companion object {
        const val BASE_URL: String = "https://rou.video"
        private const val DEFAULT_COVER_RATIO: Float = 1280f / 800f

        private val videoPathRegex = Regex("^/v/([A-Za-z0-9]{16,40})/?$")
        private val seriesPathRegex = Regex("^/s/([A-Za-z0-9]{16,40})/?$")
        private val chineseDurationCheck = Regex("^(\\d+(?:小時|小时|時|时))?\\d+分\\d+秒$")
        private val clockDurationCheck = Regex("^\\d+:\\d{2}(:\\d{2})?$")
        private val pageParamRegex = Regex("[?&]page=(\\d+)")
        private val videoUrlRegex = Regex("\"videoUrl\"\\s*:\\s*\"([^\"]+)\"")
        private val thumbVttRegex = Regex("\"thumbVTTUrl\"\\s*:\\s*\"([^\"]+)\"")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.ROUVIDEO,
            displayName = "rou.video",
            shortName = "rou.video",
            primaryKind = MediaKind.VIDEO,
            supportedKinds = setOf(MediaKind.VIDEO),
            defaultHost = "rou.video",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.DIRECTORY,
                SiteCapability.TAGS,
                SiteCapability.RELATED,
            ),
            feedSorts = listOf(FeedSort.NEWEST),
            sections = listOf(
                SiteSectionSpec("home", "为你推荐", "站点自己排的首页流", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec("v", "全部影片", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec("category", "探索分类", "按题材与片商浏览", isDirectory = true),
                SiteSectionSpec("series", "系列", isDirectory = true),
            ),
            searchScopes = listOf(SearchScope.ALL, SearchScope.TAG),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                mediaDomains = setOf("rn221.xyz", "rn252.xyz"),
            ),
        )

        fun resolveFeedUrl(request: FeedRequest, baseUrl: String = BASE_URL): String {
            val base = baseUrl.trimEnd('/')
            val page = request.page.coerceAtLeast(1)
            fun paged(url: String): String {
                if (page <= 1) return url
                return if (url.contains('?')) "$url&page=$page" else "$url?page=$page"
            }

            val query = request.query?.trim().orEmpty()
            val category = request.category?.trim().orEmpty()
            val section = request.section?.trim().orEmpty()

            if (query.isNotEmpty()) {
                return when (request.scope) {
                    SearchScope.TAG -> paged("$base/s/${HtmlUrlCleaner.encodePathSegment(query)}")
                    else -> paged("$base/search?q=${HtmlUrlCleaner.encodePathSegment(query)}")
                }
            }
            if (category.isNotEmpty()) {
                return paged("$base/v?tag=${HtmlUrlCleaner.encodePathSegment(category)}")
            }
            val path = section.ifBlank { "home" }.trim('/')
            return paged("$base/$path")
        }

        fun feedTitle(request: FeedRequest): String {
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return if (request.scope == SearchScope.TAG) "系列影片" else query
            }
            val cat = request.category?.trim().orEmpty()
            if (cat.isNotEmpty()) return cat
            val sec = request.section?.trim().orEmpty()
            return DESCRIPTOR.sections.firstOrNull { it.id == sec }?.title ?: "全部影片"
        }

        fun canonicalVideoUrl(raw: String, baseUrl: String = BASE_URL): String? {
            if (raw.isBlank()) return null
            val candidate = if (raw.startsWith("/")) "${baseUrl.trimEnd('/')}$raw" else raw.trim()
            val uri = UrlSanitizer.parseUriOrNull(candidate) ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!DESCRIPTOR.matchesHost(host)) return null
            val match = videoPathRegex.find(uri.rawPath.orEmpty()) ?: return null
            return "${baseUrl.trimEnd('/')}/v/${match.groupValues[1]}"
        }

        fun extractSeriesId(raw: String, baseUrl: String = BASE_URL): String? {
            if (raw.isBlank()) return null
            val candidate = if (raw.startsWith("/")) "${baseUrl.trimEnd('/')}$raw" else raw.trim()
            val uri = UrlSanitizer.parseUriOrNull(candidate) ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!DESCRIPTOR.matchesHost(host)) return null
            return seriesPathRegex.find(uri.rawPath.orEmpty())?.groupValues?.get(1)
        }

        fun parseCards(doc: Document, baseUrl: String = BASE_URL): List<MediaItem> =
            doc.select("a[href^='/v/']")
                .mapNotNull { parseCard(it, baseUrl) }
                .distinctBy { it.id }

        private fun parseCard(anchor: Element, baseUrl: String): MediaItem? {
            val canonical = canonicalVideoUrl(anchor.attr("href"), baseUrl) ?: return null
            val id = canonical.substringAfterLast('/')
            val img = anchor.selectFirst("img")
            val title = HtmlUrlCleaner.cleanText(
                anchor.selectFirst("h3")?.text().orEmpty().ifBlank { img?.attr("alt").orEmpty() },
            )
            if (title.isBlank()) return null
            val thumbRaw = listOf(img?.attr("src"), img?.attr("data-src"))
                .firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", ignoreCase = true) }
            val labels = anchor.select("span")
                .map { HtmlUrlCleaner.cleanText(it.text()) }
                .filter { it.isNotBlank() }
            val durationLabel = labels.firstOrNull { chineseDurationCheck.containsMatchIn(it) }
                ?: labels.firstOrNull { clockDurationCheck.containsMatchIn(it) }
                .orEmpty()
            val viewsLabel = labels.firstOrNull {
                it.contains("次觀看", ignoreCase = true) || it.contains("次观看", ignoreCase = true)
            }.orEmpty()
            return MediaItem(
                siteId = SiteId.ROUVIDEO,
                id = id,
                url = canonical,
                kind = MediaKind.VIDEO,
                title = title,
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, thumbRaw),
                coverAspectRatio = DEFAULT_COVER_RATIO,
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationLabel),
                views = VideoSiteSupport.parseCount(viewsLabel),
            )
        }

        /**
         * Decodes TanStack Start `ev` base64 + byte-offset `-k` payload via [RouPngProtocol]
         * to extract `/api/hls/<id>` and optional `thumbVTTUrl`.
         */
        fun extractStreamsAndVtt(html: String, baseUrl: String = BASE_URL): Pair<List<VideoStream>, String?> {
            var videoPath: String? = null
            var vttPath: String? = null

            val ev = RouPngProtocol.extractEvFromHtml(html)
            if (ev != null) {
                val decoded = runCatching { RouPngProtocol.decodeEv(ev.first, ev.second) }.getOrNull()
                if (!decoded.isNullOrBlank()) {
                    val obj = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
                    if (obj != null) {
                        videoPath = (obj["videoUrl"] as? JsonPrimitive)?.contentOrNull
                        vttPath = (obj["thumbVTTUrl"] as? JsonPrimitive)?.contentOrNull
                    }
                    if (videoPath.isNullOrBlank()) {
                        videoPath = videoUrlRegex.find(decoded)?.groupValues?.get(1)?.let(JsExtractor::unescapeJsString)
                    }
                    if (vttPath.isNullOrBlank()) {
                        vttPath = thumbVttRegex.find(decoded)?.groupValues?.get(1)?.let(JsExtractor::unescapeJsString)
                    }
                }
            }

            if (videoPath.isNullOrBlank()) {
                videoPath = videoUrlRegex.find(html)?.groupValues?.get(1)?.let(JsExtractor::unescapeJsString)
            }

            val resolvedStreamUrl = VideoSiteSupport.resolveAbsolute(baseUrl, videoPath)
            val resolvedVttUrl = VideoSiteSupport.resolveAbsolute(baseUrl, vttPath).ifBlank { null }
            if (resolvedStreamUrl.isBlank()) return emptyList<VideoStream>() to resolvedVttUrl

            val isHls = RouPngProtocol.isRouHlsUrl(resolvedStreamUrl)
            val stream = VideoStream(
                quality = "auto",
                label = "自动",
                url = resolvedStreamUrl,
                isHls = isHls,
                mimeTypeHint = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4",
            )
            return listOf(stream) to resolvedVttUrl
        }

        fun parseDetail(
            doc: Document,
            canonicalUrl: String,
            baseUrl: String,
            streams: List<VideoStream>,
            thumbnailVttUrl: String?,
        ): VideoDetail {
            val id = canonicalUrl.substringAfterLast('/')
            val heading = HtmlUrlCleaner.cleanText(doc.selectFirst("h1")?.text().orEmpty())
                .ifBlank { HtmlUrlCleaner.cleanText(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()) }
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            val durationRaw = doc.selectFirst("meta[property=og:video:duration]")?.attr("content").orEmpty()
            val description = HtmlUrlCleaner.cleanText(
                doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(),
            )

            val categories = doc.select("a[href*=series?tag=], a[href*=/v?tag=]").mapNotNull { a ->
                val encoded = a.attr("href").substringAfter("tag=", "").substringBefore('&')
                if (encoded.isBlank()) return@mapNotNull null
                val tag = HtmlUrlCleaner.decodeUrlComponent(encoded)
                TagRef(
                    slug = tag,
                    label = HtmlUrlCleaner.cleanText(a.text()).ifBlank { tag },
                    namespace = "category",
                    url = "${baseUrl.trimEnd('/')}/v?tag=$encoded",
                )
            }.distinctBy { it.slug }

            val seriesAnchor = doc.selectFirst("a[href^='/s/']")
            val seriesTags = if (seriesAnchor != null) {
                val sid = extractSeriesId(seriesAnchor.attr("href"), baseUrl)
                if (sid != null) {
                    listOf(
                        TagRef(
                            slug = sid,
                            label = HtmlUrlCleaner.cleanText(seriesAnchor.text()).ifBlank { "系列" },
                            namespace = "series",
                            url = "${baseUrl.trimEnd('/')}/s/$sid",
                        ),
                    )
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val related = parseCards(doc, baseUrl).filter { it.id != id }
            val item = MediaItem(
                siteId = SiteId.ROUVIDEO,
                id = id,
                url = canonicalUrl,
                kind = MediaKind.VIDEO,
                title = heading.ifBlank { "作品 $id" },
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, poster),
                coverAspectRatio = DEFAULT_COVER_RATIO,
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationRaw),
                tags = seriesTags + categories,
            )

            return VideoDetail(
                item = item,
                description = description,
                streams = streams,
                tags = seriesTags,
                categories = categories,
                related = related,
                thumbnailVttUrl = thumbnailVttUrl,
            )
        }

        fun totalPages(doc: Document, currentPage: Int): Int {
            val maxFound = doc.select("a[href*=page=]").mapNotNull { a ->
                pageParamRegex.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull() ?: currentPage
            return maxFound.coerceAtLeast(currentPage)
        }

        fun looksEmpty(doc: Document): Boolean {
            if (doc.select("a[href^='/v/']").isNotEmpty()) return false
            val text = doc.text()
            return text.contains("沒有結果", ignoreCase = true) ||
                text.contains("没有结果", ignoreCase = true) ||
                text.contains("No results", ignoreCase = true)
        }
    }
}
