package com.raven.app.sites.video

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.AuthorRef
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
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.VideoSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pornhub (`www.pornhub.com` / `pornhub.com`) — Dual desktop (`li.pcVideoListItem`)
 * and mobile (`li[data-video-vkey]`) DOM parser with `mediaDefinitions` HLS/MP4 extraction.
 */
class PornhubSitePlugin(
    private val http: HttpClientAdapter,
    private val rateLimitBackoffMs: Long = 1_000L,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed = coroutineScope {
            val newestRequest = FeedRequest(siteId = SiteId.PORNHUB, section = "newest", sort = FeedSort.NEWEST)
            val viewedRequest = FeedRequest(siteId = SiteId.PORNHUB, section = "mostviewed", sort = FeedSort.VIEWS)
            val newestDeferred = async { loadFeed(newestRequest) }
            val viewedDeferred = async { runCatching { loadFeed(viewedRequest) }.getOrNull() }
            val newestPage = newestDeferred.await()
            val viewedPage = viewedDeferred.await()
            VideoSiteSupport.buildHomeFeed(
                siteId = SiteId.PORNHUB,
                primaryTitle = "最新",
                primaryPage = newestPage,
                primarySeeAll = newestRequest,
                rankedTitle = "最多播放",
                rankedPage = viewedPage,
                rankedSeeAll = viewedRequest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val url = resolveFeedUrl(request)
            val (response, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.PORNHUB,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, url)
            val items = parseCards(doc, BASE_URL)
            if (items.isEmpty() && !looksEmpty(doc)) {
                throw SiteException.ParseError("Pornhub 未返回可识别的列表，站点可能已改版或要求登录", siteId = SiteId.PORNHUB)
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

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = when {
            !request.query.isNullOrBlank() || !request.category.isNullOrBlank() -> listOf(FeedSort.NEWEST)
            request.section == "mostviewed" -> listOf(FeedSort.VIEWS)
            request.section == "toprated" -> listOf(FeedSort.RATING)
            else -> descriptor.feedSorts
        }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(
            SearchScope.ALL,
            SearchScope.TAG,
            SearchScope.AUTHOR,
        )
    }

    override val videoSource: VideoSource = object : VideoSource {
        override suspend fun loadVideoDetail(url: String): VideoDetail {
            val key = extractViewKey(url)
                ?: throw SiteException.ParseError("无法识别 Pornhub 视频地址", siteId = SiteId.PORNHUB)
            val target = "$BASE_URL/view_video.php?viewkey=$key"
            val (_, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.PORNHUB,
                url = target,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, target)
            val streams = extractStreams(doc)
            if (streams.isEmpty()) {
                throw SiteException.ParseError("详情页没有播放地址，可能需要登录或站点已改版", siteId = SiteId.PORNHUB)
            }
            return parseDetail(doc, target, BASE_URL, streams)
        }
    }

    override val directorySource: DirectorySource = object : DirectorySource {
        override suspend fun loadDirectory(
            section: String,
            page: Int,
            query: String?,
        ): List<DirectoryEntry> {
            val (path, segment, kind) = when (section) {
                "categories" -> Triple("/categories", "/categories/", SearchScope.ALL)
                "pornstars" -> Triple("/pornstars", "/pornstar/", SearchScope.AUTHOR)
                else -> return emptyList()
            }
            val url = if (page > 1) "$BASE_URL$path?page=$page" else "$BASE_URL$path"
            val (_, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.PORNHUB,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, url)
            val entries = doc.select("a[href*=$segment]").mapNotNull { a ->
                val rawHref = a.attr("href")
                val slug = rawHref.substringAfter(segment).trimEnd('/').substringBefore('?').substringBefore('#')
                if (slug.isBlank() || slug.contains('/')) return@mapNotNull null
                val title = HtmlUrlCleaner.cleanText(a.text()).ifBlank { slug }
                DirectoryEntry(
                    siteId = SiteId.PORNHUB,
                    id = slug,
                    title = title,
                    url = VideoSiteSupport.resolveAbsolute(BASE_URL, rawHref),
                    kind = kind,
                )
            }.distinctBy { it.id }.take(400)
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
            extractViewKey(url)?.let { key -> return "$BASE_URL/view_video.php?viewkey=$key" }
            return UrlSanitizer.normalizeHttpsUrl(url)
        }

        override fun resolve(url: String): ResolvedLink? {
            if (!canHandle(url)) return null
            extractViewKey(url)?.let { key ->
                return ResolvedLink.Video(
                    siteId = SiteId.PORNHUB,
                    id = key,
                    canonicalUrl = "$BASE_URL/view_video.php?viewkey=$key",
                )
            }
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val page = VideoSiteSupport.queryParam(uri, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val rawPath = uri.rawPath.orEmpty()
            if (searchPathRegex.matches(rawPath)) {
                val q = VideoSiteSupport.queryParam(uri, "search")?.takeIf { it.isNotBlank() } ?: return null
                val scope = if (VideoSiteSupport.queryParam(uri, "t").equals("tag", ignoreCase = true)) {
                    SearchScope.TAG
                } else {
                    SearchScope.ALL
                }
                return ResolvedLink.Feed(FeedRequest(SiteId.PORNHUB, query = q, scope = scope, page = page))
            }

            val parts = VideoSiteSupport.pathSegments(uri)
            return when (parts.firstOrNull()?.lowercase()) {
                "pornstar", "model" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { slug ->
                    ResolvedLink.Feed(
                        FeedRequest(
                            siteId = SiteId.PORNHUB,
                            query = slug.replace('-', ' '),
                            scope = SearchScope.AUTHOR,
                            page = page,
                        ),
                    )
                }
                "pornstars" -> ResolvedLink.Directory(SiteId.PORNHUB, "pornstars", "$BASE_URL/pornstars")
                "categories" -> {
                    val slug = parts.getOrNull(1)?.takeIf { it.isNotBlank() && parts.size == 2 }
                    if (slug == null) {
                        ResolvedLink.Directory(SiteId.PORNHUB, "categories", "$BASE_URL/categories")
                    } else {
                        ResolvedLink.Feed(FeedRequest(SiteId.PORNHUB, category = slug, page = page))
                    }
                }
                "video" -> {
                    val cat = VideoSiteSupport.queryParam(uri, "c")?.takeIf { it.isNotBlank() }
                    if (cat != null) {
                        ResolvedLink.Feed(FeedRequest(SiteId.PORNHUB, category = cat, page = page))
                    } else {
                        val order = VideoSiteSupport.queryParam(uri, "o")?.lowercase()
                        val (section, sort) = when (order) {
                            "mv" -> "mostviewed" to FeedSort.VIEWS
                            "tr" -> "toprated" to FeedSort.RATING
                            else -> "newest" to FeedSort.NEWEST
                        }
                        ResolvedLink.Feed(FeedRequest(SiteId.PORNHUB, section = section, sort = sort, page = page))
                    }
                }
                else -> null
            }
        }
    }

    companion object {
        const val BASE_URL: String = "https://www.pornhub.com"
        private val searchPathRegex = Regex("^/video/search/?$", RegexOption.IGNORE_CASE)
        private val viewKeyRegex = Regex("[0-9a-z]{8,24}")
        private val pathViewKeyRegex = Regex("[0-9a-z]{10,20}")
        private val hrefViewKeyRegex = Regex("viewkey=([0-9a-z]+)")
        private val mediaDefRegex = Regex(
            """"height"\s*:\s*(\d+)[^{}]*?"format"\s*:\s*"(hls|mp4)"[^{}]*?"videoUrl"\s*:\s*"(https?:[^"]+)"""",
        )
        private val fallbackM3u8Regex = Regex(""""videoUrl"\s*:\s*"(https?:[^"]+\.m3u8[^"]*)"""")
        private val pageParamRegex = Regex("[?&]page=(\\d+)")
        private val slugCleanupRegex = Regex("[^a-z0-9]+")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.PORNHUB,
            displayName = "Pornhub",
            shortName = "Pornhub",
            primaryKind = MediaKind.VIDEO,
            supportedKinds = setOf(MediaKind.VIDEO),
            defaultHost = "www.pornhub.com",
            mirrorHosts = listOf("pornhub.com"),
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.DIRECTORY,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.RELATED,
                SiteCapability.STREAM_QUALITIES,
            ),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.RATING),
            sections = listOf(
                SiteSectionSpec("newest", "最新", "刚发布的视频", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec("mostviewed", "最多播放", defaultSort = FeedSort.VIEWS),
                SiteSectionSpec("toprated", "评分最高", defaultSort = FeedSort.RATING),
                SiteSectionSpec("categories", "分类索引", isDirectory = true),
                SiteSectionSpec("pornstars", "演员列表", isDirectory = true),
            ),
            searchScopes = listOf(SearchScope.ALL, SearchScope.TAG, SearchScope.AUTHOR),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                mediaDomains = setOf("phncdn.com"),
            ),
        )

        fun resolveFeedUrl(request: FeedRequest, baseUrl: String = BASE_URL): String {
            val base = baseUrl.trimEnd('/')
            val page = request.page.coerceAtLeast(1)
            val ampPage = if (page > 1) "&page=$page" else ""
            val qPage = if (page > 1) "?page=$page" else ""

            val query = request.query?.trim().orEmpty()
            val category = request.category?.trim().orEmpty()
            val section = request.section?.trim().orEmpty()

            if (query.isNotEmpty()) {
                return when (request.scope) {
                    SearchScope.TAG -> "$base/video/search?search=${HtmlUrlCleaner.encodePathSegment(query)}&t=tag$ampPage"
                    SearchScope.AUTHOR -> "$base/pornstar/${slugify(query)}$qPage"
                    SearchScope.ALL, SearchScope.CHARACTER -> "$base/video/search?search=${HtmlUrlCleaner.encodePathSegment(query)}$ampPage"
                }
            }
            if (category.isNotEmpty()) {
                return if (category.all(Char::isDigit)) {
                    "$base/video?c=$category$ampPage"
                } else {
                    "$base/categories/${HtmlUrlCleaner.encodePathSegment(category)}$qPage"
                }
            }
            val order = when {
                section == "mostviewed" -> "mv"
                section == "toprated" -> "tr"
                request.sort == FeedSort.VIEWS || request.sort == FeedSort.POPULAR -> "mv"
                request.sort == FeedSort.RATING -> "tr"
                else -> "cm"
            }
            return "$base/video?o=$order$ampPage"
        }

        private fun slugify(raw: String): String =
            raw.trim().lowercase().replace(slugCleanupRegex, "-").trim('-')

        fun feedTitle(request: FeedRequest): String {
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return when (request.scope) {
                    SearchScope.TAG -> "标签 · $query"
                    SearchScope.AUTHOR -> "演员 · $query"
                    else -> query
                }
            }
            if (!request.category.isNullOrBlank()) return "分类"
            val sec = request.section?.trim().orEmpty()
            return DESCRIPTOR.sections.firstOrNull { it.id == sec }?.title ?: when (request.sort) {
                FeedSort.VIEWS, FeedSort.POPULAR -> "最多播放"
                FeedSort.RATING -> "评分最高"
                else -> "最新"
            }
        }

        fun extractViewKey(raw: String): String? {
            if (raw.isBlank()) return null
            val uri = UrlSanitizer.parseUriOrNull(raw.trim()) ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!DESCRIPTOR.matchesHost(host)) return null
            val queryKey = VideoSiteSupport.queryParam(uri, "viewkey")
            val candidate = queryKey ?: VideoSiteSupport.pathSegments(uri).firstOrNull {
                pathViewKeyRegex.matches(it)
            }
            return candidate?.takeIf { viewKeyRegex.matches(it) }
        }

        /**
         * Dual DOM parsing: tries desktop `li.pcVideoListItem` first, then falls back to mobile
         * `li[data-video-vkey]` layout seamlessly.
         */
        fun parseCards(doc: Document, baseUrl: String = BASE_URL): List<MediaItem> {
            val desktop = doc.select("li.pcVideoListItem")
                .mapNotNull { parseDesktopCard(it, baseUrl) }
                .distinctBy { it.id }
            if (desktop.isNotEmpty()) return desktop
            return doc.select("li[data-video-vkey]")
                .mapNotNull { parseMobileCard(it, baseUrl) }
                .distinctBy { it.id }
        }

        private fun parseDesktopCard(item: Element, baseUrl: String): MediaItem? {
            val key = item.attr("data-video-vkey").trim().ifBlank {
                hrefViewKeyRegex.find(item.selectFirst("a[href*=viewkey]")?.attr("href").orEmpty())
                    ?.groupValues?.get(1).orEmpty()
            }
            if (key.isBlank()) return null
            val anchor = item.selectFirst("a[href*=viewkey]")
            val title = HtmlUrlCleaner.cleanText(
                anchor?.attr("title").orEmpty().ifBlank { anchor?.text().orEmpty() },
            )
            if (title.isBlank()) return null
            val img = item.selectFirst("img[data-image], img[src]")
            val thumbRaw = listOf(img?.attr("data-image"), img?.attr("src"))
                .firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", ignoreCase = true) }
            val durationLabel = HtmlUrlCleaner.cleanText(item.selectFirst("var.duration")?.text().orEmpty())
            val viewsLabel = HtmlUrlCleaner.cleanText(item.selectFirst("span.views var")?.text().orEmpty())
            val preview = item.selectFirst("img[data-mediabook]")?.attr("data-mediabook").orEmpty()
            return MediaItem(
                siteId = SiteId.PORNHUB,
                id = key,
                url = "${baseUrl.trimEnd('/')}/view_video.php?viewkey=$key",
                kind = MediaKind.VIDEO,
                title = title,
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, thumbRaw),
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationLabel),
                views = VideoSiteSupport.parseCount(viewsLabel),
                previewUrl = VideoSiteSupport.resolveAbsolute(baseUrl, preview).ifBlank { null },
            )
        }

        private fun parseMobileCard(item: Element, baseUrl: String): MediaItem? {
            val key = item.attr("data-video-vkey").trim()
            if (!viewKeyRegex.matches(key)) return null
            val anchor = item.selectFirst("div.title a[href*=viewkey]") ?: item.selectFirst("a[href*=viewkey]")
            val title = HtmlUrlCleaner.cleanText(
                anchor?.text().orEmpty().ifBlank { anchor?.attr("title").orEmpty() },
            )
            if (title.isBlank()) return null
            val img = item.selectFirst("img[data-image], img[data-poster], img[src]")
            val thumbRaw = listOf(img?.attr("data-image"), img?.attr("data-poster"), img?.attr("src"))
                .firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", ignoreCase = true) }
            val durationLabel = HtmlUrlCleaner.cleanText(
                item.selectFirst("div.duration span.time, span.time")?.text().orEmpty(),
            )
            val viewsLabel = HtmlUrlCleaner.cleanText(item.selectFirst("div.views")?.text().orEmpty())
                .substringBefore(' ')
            val preview = item.selectFirst("[data-webm]")?.attr("data-webm").orEmpty()
            return MediaItem(
                siteId = SiteId.PORNHUB,
                id = key,
                url = "${baseUrl.trimEnd('/')}/view_video.php?viewkey=$key",
                kind = MediaKind.VIDEO,
                title = title,
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, thumbRaw),
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationLabel),
                views = VideoSiteSupport.parseCount(viewsLabel),
                previewUrl = VideoSiteSupport.resolveAbsolute(baseUrl, preview).ifBlank { null },
            )
        }

        /**
         * Extracts HLS/MP4 streams from `mediaDefinitions` in `flashvars_<id>` scripts.
         * Supports both structured JSON array parsing (any field order) and non-nested brace regex fallback.
         */
        fun extractStreams(doc: Document): List<VideoStream> {
            val scripts = doc.select("script").map { it.data() }.filter { it.contains("mediaDefinitions") }
            val source = scripts.joinToString("\n").ifBlank { doc.outerHtml() }
            val found = LinkedHashMap<String, VideoStream>()

            // 1. Structured balanced-array JSON parse (handles arbitrary property order)
            var searchFrom = 0
            while (true) {
                val marker = source.indexOf("mediaDefinitions", searchFrom)
                if (marker < 0) break
                val bracketStart = source.indexOf('[', marker)
                if (bracketStart < 0) break
                val arrayText = JsExtractor.extractBalanced(source, bracketStart, '[', ']')
                if (arrayText != null) {
                    val arr = runCatching { json.parseToJsonElement(arrayText) as? JsonArray }.getOrNull()
                    if (arr != null) {
                        for (el in arr) {
                            val obj = el as? JsonObject ?: continue
                            val format = (obj["format"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: continue
                            if (format != "hls" && format != "mp4") continue
                            val rawUrl = (obj["videoUrl"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) continue
                            val height = (obj["height"] as? JsonPrimitive)?.intOrNull
                                ?: (obj["quality"] as? JsonPrimitive)?.contentOrNull?.removeSuffix("p")?.toIntOrNull()
                                ?: 0
                            addMediaDefinitionStream(found, height, format, rawUrl)
                        }
                    }
                }
                searchFrom = marker + "mediaDefinitions".length
            }

            // 2. Non-nested brace regex fallback (handles non-strict JS object literals)
            for (match in mediaDefRegex.findAll(source)) {
                val height = match.groupValues[1].toIntOrNull() ?: 0
                val format = match.groupValues[2].lowercase()
                val rawUrl = JsExtractor.unescapeJsString(match.groupValues[3]).trim()
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) continue
                addMediaDefinitionStream(found, height, format, rawUrl)
            }

            if (found.isNotEmpty()) {
                return found.values.sortedWith(
                    compareByDescending<VideoStream> { it.isHls }
                        .thenByDescending { VideoSiteSupport.parseStreamHeight(it.label) },
                )
            }

            return fallbackM3u8Regex.findAll(source)
                .map { m ->
                    val url = JsExtractor.unescapeJsString(m.groupValues[1]).trim()
                    VideoStream(
                        quality = "auto",
                        label = "自动",
                        url = url,
                        isHls = true,
                        mimeTypeHint = "application/vnd.apple.mpegurl",
                    )
                }
                .distinctBy { it.url }
                .toList()
        }

        private fun addMediaDefinitionStream(
            target: LinkedHashMap<String, VideoStream>,
            height: Int,
            format: String,
            url: String,
        ) {
            val isMp4 = format == "mp4"
            val label = when {
                isMp4 -> "原画"
                height > 0 -> "${height}p"
                else -> "自动"
            }
            val quality = when {
                isMp4 && height > 0 -> "${height}p"
                isMp4 -> "mp4"
                height > 0 -> "${height}p"
                else -> "auto"
            }
            target.putIfAbsent(
                label,
                VideoStream(
                    quality = quality,
                    label = label,
                    url = url,
                    isHls = !isMp4,
                    mimeTypeHint = if (isMp4) "video/mp4" else "application/vnd.apple.mpegurl",
                ),
            )
        }

        fun parseDetail(
            doc: Document,
            canonicalUrl: String,
            baseUrl: String,
            streams: List<VideoStream>,
        ): VideoDetail {
            val key = extractViewKey(canonicalUrl) ?: canonicalUrl.substringAfter("viewkey=")
            val heading = HtmlUrlCleaner.cleanText(
                doc.selectFirst("h1.title span, h1.title")?.text().orEmpty(),
            ).ifBlank {
                HtmlUrlCleaner.cleanText(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            }
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            val description = HtmlUrlCleaner.cleanText(
                doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(),
            )
            val durationRaw = doc.selectFirst("meta[property=video:duration], meta[property=og:video:duration]")
                ?.attr("content").orEmpty()

            val authors = doc.select("a[href^='/pornstar/'], a[href^='/model/']").mapNotNull { a ->
                val href = a.attr("href")
                val slug = href.substringAfter("/pornstar/", href.substringAfter("/model/", "")).trimEnd('/').substringBefore('?')
                val label = HtmlUrlCleaner.cleanText(a.text())
                if (slug.isBlank() || label.isBlank()) return@mapNotNull null
                AuthorRef(
                    id = slug,
                    name = label,
                    role = "performer",
                    url = VideoSiteSupport.resolveAbsolute(baseUrl, href),
                )
            }.distinctBy { it.id }.take(12)

            val categories = doc.select("a[href*=categories/], a[href*=video?c=]").mapNotNull { a ->
                val href = a.attr("href")
                val slug = href.substringAfter("categories/", href.substringAfter("c=", ""))
                    .substringBefore('&')
                    .substringBefore('?')
                    .trimEnd('/')
                val label = HtmlUrlCleaner.cleanText(a.text())
                if (slug.isBlank() || label.isBlank()) return@mapNotNull null
                TagRef(
                    slug = slug,
                    label = label,
                    namespace = "category",
                    url = VideoSiteSupport.resolveAbsolute(baseUrl, href),
                )
            }.distinctBy { it.slug }.take(16)

            val tags = doc.select("a[href*=tags/]").mapNotNull { a ->
                val href = a.attr("href")
                val slug = href.substringAfter("tags/").trimEnd('/').substringBefore('?')
                val label = HtmlUrlCleaner.cleanText(a.text())
                if (slug.isBlank() || label.isBlank()) return@mapNotNull null
                TagRef(
                    slug = slug,
                    label = label,
                    url = VideoSiteSupport.resolveAbsolute(baseUrl, href),
                )
            }.distinctBy { it.slug }.take(16)

            val related = parseCards(doc, baseUrl).filter { it.id != key }
            val item = MediaItem(
                siteId = SiteId.PORNHUB,
                id = key,
                url = canonicalUrl,
                kind = MediaKind.VIDEO,
                title = heading.ifBlank { "视频 $key" },
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, poster),
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationRaw),
                author = authors.firstOrNull(),
                tags = tags,
            )

            return VideoDetail(
                item = item,
                description = description,
                streams = streams,
                tags = tags,
                authors = authors,
                categories = categories,
                related = related,
            )
        }

        fun totalPages(doc: Document, currentPage: Int): Int {
            val maxFound = doc.select("a[href*=page=], li.page_number a[href]").mapNotNull { a ->
                a.text().trim().toIntOrNull()
                    ?: pageParamRegex.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull() ?: currentPage
            return maxFound.coerceAtLeast(currentPage)
        }

        fun looksEmpty(doc: Document): Boolean {
            if (doc.select("li.pcVideoListItem").isNotEmpty() || doc.select("li[data-video-vkey]").isNotEmpty()) {
                return false
            }
            val text = doc.text()
            return text.contains("No results", ignoreCase = true) ||
                text.contains("没有找到", ignoreCase = true)
        }
    }
}
