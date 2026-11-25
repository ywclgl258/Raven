package com.raven.app.sites.video

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.AuthorRef
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * KBJFree (`kbjfree.com`) — Next.js App Router (RSC Flight) BJ recording archive.
 *
 * Full streams require site credits; public pages expose muted preview MP4 clips only
 * (`SiteCapability.PREVIEW_ONLY`, `VideoStream.isPreviewOnly = true`, `VideoDetail.isPreviewOnly = true`).
 */
class KbjfreeSitePlugin(
    private val http: HttpClientAdapter,
    private val rateLimitBackoffMs: Long = 1_000L,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed = coroutineScope {
            val recentRequest = FeedRequest(siteId = SiteId.KBJFREE, section = "recent", sort = FeedSort.NEWEST)
            val popularRequest = FeedRequest(siteId = SiteId.KBJFREE, section = "popular", sort = FeedSort.POPULAR)
            val recentDeferred = async { loadFeed(recentRequest) }
            val popularDeferred = async { suspendRunCatching { loadFeed(popularRequest) }.getOrNull() }
            val recentPage = recentDeferred.await()
            val popularPage = popularDeferred.await()
            VideoSiteSupport.buildHomeFeed(
                siteId = SiteId.KBJFREE,
                primaryTitle = "最新上传",
                primaryPage = recentPage,
                primarySeeAll = recentRequest,
                rankedTitle = "热门",
                rankedPage = popularPage,
                rankedSeeAll = popularRequest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val url = resolveFeedUrl(request)
            val (response, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.KBJFREE,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val items = parseCards(html, BASE_URL)
            if (items.isEmpty() && !looksEmpty(html)) {
                throw SiteException.ParseError("KBJFree 未返回可识别的列表，站点可能已改版", siteId = SiteId.KBJFREE)
            }
            val current = request.page.coerceAtLeast(1)
            val total = lastPage(html, current)
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
            request.section == "popular" || request.section == "trending" -> listOf(FeedSort.POPULAR)
            else -> listOf(FeedSort.NEWEST, FeedSort.POPULAR)
        }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
        override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> = listOf(FeedSort.NEWEST)
    }

    override val videoSource: VideoSource = object : VideoSource {
        override suspend fun loadVideoDetail(url: String): VideoDetail {
            val canonical = canonicalVideoUrl(url)
                ?: throw SiteException.ParseError("无法识别 KBJFree 作品地址", siteId = SiteId.KBJFREE)
            val (_, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.KBJFREE,
                url = canonical,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, canonical)
            val streams = extractPreviewStreams(doc, BASE_URL)
            if (streams.isEmpty()) {
                throw SiteException.ParseError("详情页没有可播放的预览地址，站点可能已改版", siteId = SiteId.KBJFREE)
            }
            return parseDetail(doc, html, canonical, BASE_URL, streams)
        }
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
                return ResolvedLink.Video(SiteId.KBJFREE, id, canonical)
            }
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val page = VideoSiteSupport.queryParam(uri, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            VideoSiteSupport.queryParam(uri, "q")?.takeIf { it.isNotBlank() }?.let { q ->
                return ResolvedLink.Feed(FeedRequest(SiteId.KBJFREE, query = q, scope = SearchScope.ALL, page = page))
            }
            VideoSiteSupport.queryParam(uri, "filter")?.takeIf { it.isNotBlank() }?.let { filter ->
                return ResolvedLink.Feed(FeedRequest(SiteId.KBJFREE, category = filter, page = page))
            }
            VideoSiteSupport.queryParam(uri, "sort")?.let { sort ->
                return if (sort.equals("popular", ignoreCase = true)) {
                    ResolvedLink.Feed(FeedRequest(SiteId.KBJFREE, section = "popular", sort = FeedSort.POPULAR, page = page))
                } else {
                    ResolvedLink.Feed(FeedRequest(SiteId.KBJFREE, section = "recent", sort = FeedSort.NEWEST, page = page))
                }
            }
            return null
        }
    }

    companion object {
        const val BASE_URL: String = "https://kbjfree.com"
        private const val DEFAULT_COVER_RATIO: Float = 480f / 270f
        private const val PREVIEW_DESCRIPTION: String =
            "完整片源需要在 KBJFree 站内用积分解锁；这里播放的是站点公开的预览片段。"

        private val watchPathRegex = Regex("^/watch/([0-9a-f-]{16,40})/?$", RegexOption.IGNORE_CASE)
        private val rscVideoAnchorRegex = Regex("""\{"id":"([0-9a-f-]{16,40})","name":"((?:\\.|[^"\\])*)","categoryId"""")
        private val pageParamRegex = Regex("[?&]page=(\\d+)")
        private val fieldRegexMap: Map<String, Regex> = listOf(
            "mainThumbUrl",
            "coverUrl",
            "previewMediaUrl",
            "duration",
        ).associateWith { name ->
            Regex("\"${Regex.escape(name)}\":\"((?:\\\\.|[^\"\\\\])*)\"")
        }
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.KBJFREE,
            displayName = "KBJFree",
            shortName = "KBJFree",
            primaryKind = MediaKind.VIDEO,
            supportedKinds = setOf(MediaKind.VIDEO),
            defaultHost = "kbjfree.com",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.AUTHORS,
                SiteCapability.RELATED,
                SiteCapability.PREVIEW_ONLY,
            ),
            accessNote = "仅提供公开预览片段",
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = listOf(
                SiteSectionSpec("recent", "最新上传", "刚发布的录制", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec("popular", "热门", defaultSort = FeedSort.POPULAR),
                SiteSectionSpec("for-you", "为你推荐", category = "for-you", defaultSort = FeedSort.NEWEST),
            ),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                mediaDomains = setOf("marshlecdn.com"),
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
                return paged("$base/search?q=${HtmlUrlCleaner.encodePathSegment(query)}")
            }
            if (category.isNotEmpty()) {
                return paged("$base/videos?filter=${HtmlUrlCleaner.encodePathSegment(category)}")
            }
            if (section == "for-you") {
                return paged("$base/videos?filter=for-you")
            }
            val sortKey = if (section == "popular" || section == "trending" || request.sort == FeedSort.POPULAR) {
                "popular"
            } else {
                "recent"
            }
            return paged("$base/videos?sort=$sortKey")
        }

        fun feedTitle(request: FeedRequest): String {
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) return query
            if (request.category == "for-you" || request.section == "for-you") return "为你推荐"
            if (request.section == "popular" || request.section == "trending" || request.sort == FeedSort.POPULAR) {
                return "热门"
            }
            return "最新上传"
        }

        fun canonicalVideoUrl(raw: String, baseUrl: String = BASE_URL): String? {
            if (raw.isBlank()) return null
            val candidate = if (raw.startsWith("/")) "${baseUrl.trimEnd('/')}$raw" else raw.trim()
            val uri = UrlSanitizer.parseUriOrNull(candidate) ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!DESCRIPTOR.matchesHost(host)) return null
            val match = watchPathRegex.find(uri.rawPath.orEmpty()) ?: return null
            return "${baseUrl.trimEnd('/')}/watch/${match.groupValues[1]}"
        }

        /**
         * Extracts video items from Next.js App Router RSC Flight `self.__next_f.push([1,"..."])` chunks.
         * Uses anchored window extraction because RSC payloads contain non-JSON tokens such as `$undefined`.
         */
        fun parseCards(html: String, baseUrl: String = BASE_URL): List<MediaItem> {
            val payload = JsExtractor.extractNextRscFlightChunks(html)
            if (payload.isBlank()) return emptyList()
            val items = LinkedHashMap<String, MediaItem>()
            val matches = rscVideoAnchorRegex.findAll(payload).toList()
            for ((index, match) in matches.withIndex()) {
                val id = match.groupValues[1]
                if (items.containsKey(id)) continue
                val nextStart = matches.getOrNull(index + 1)?.range?.first ?: payload.length
                val windowEnd = minOf(payload.length, nextStart, match.range.first + 2_600)
                val window = payload.substring(match.range.first, windowEnd)
                val title = HtmlUrlCleaner.cleanText(JsExtractor.unescapeJsString(match.groupValues[2])).ifBlank { id }
                val thumb = extractStringField(window, "mainThumbUrl")
                    .ifBlank { extractStringField(window, "coverUrl") }
                val preview = extractStringField(window, "previewMediaUrl")
                val durationStr = extractStringField(window, "duration").substringBefore('.').take(8)
                items[id] = MediaItem(
                    siteId = SiteId.KBJFREE,
                    id = id,
                    url = "${baseUrl.trimEnd('/')}/watch/$id",
                    kind = MediaKind.VIDEO,
                    title = title,
                    coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, thumb),
                    coverAspectRatio = DEFAULT_COVER_RATIO,
                    durationSeconds = VideoSiteSupport.parseDurationSeconds(durationStr),
                    previewUrl = VideoSiteSupport.resolveAbsolute(baseUrl, preview).ifBlank { null },
                    extra = mapOf("previewOnly" to "true"),
                )
            }
            return items.values.toList()
        }

        private fun extractStringField(window: String, name: String): String {
            val regex = fieldRegexMap[name]
                ?: Regex("\"${Regex.escape(name)}\":\"((?:\\\\.|[^\"\\\\])*)\"")
            return regex.find(window)
                ?.groupValues
                ?.get(1)
                ?.let(JsExtractor::unescapeJsString)
                .orEmpty()
        }

        fun extractPreviewStreams(doc: Document, baseUrl: String = BASE_URL): List<VideoStream> {
            val streams = LinkedHashMap<String, VideoStream>()
            for (script in doc.select("script[type=application/ld+json]")) {
                val obj = runCatching { json.parseToJsonElement(script.data()) as? JsonObject }.getOrNull() ?: continue
                val contentUrl = (obj["contentUrl"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val resolved = VideoSiteSupport.resolveAbsolute(baseUrl, contentUrl)
                if (resolved.startsWith("http")) {
                    val isHls = resolved.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                    streams["preview"] = VideoStream(
                        quality = "preview",
                        label = "预览",
                        url = resolved,
                        isHls = isHls,
                        mimeTypeHint = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4",
                        isPreviewOnly = true,
                    )
                }
            }
            if (streams.isEmpty()) {
                for (video in doc.select("video[src], video source[src]")) {
                    val src = video.attr("src").ifBlank { video.absUrl("src") }
                    val resolved = VideoSiteSupport.resolveAbsolute(baseUrl, src)
                    if (resolved.startsWith("http")) {
                        val isHls = resolved.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                        streams.putIfAbsent(
                            "preview",
                            VideoStream(
                                quality = "preview",
                                label = "预览",
                                url = resolved,
                                isHls = isHls,
                                mimeTypeHint = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4",
                                isPreviewOnly = true,
                            ),
                        )
                    }
                }
            }
            return streams.values.toList()
        }

        fun parseDetail(
            doc: Document,
            rawHtml: String,
            canonicalUrl: String,
            baseUrl: String,
            streams: List<VideoStream>,
        ): VideoDetail {
            val id = canonicalUrl.substringAfterLast('/')
            var ldJson: JsonObject? = null
            for (script in doc.select("script[type=application/ld+json]")) {
                val obj = runCatching { json.parseToJsonElement(script.data()) as? JsonObject }.getOrNull() ?: continue
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                if (!name.isNullOrBlank()) {
                    ldJson = obj
                    break
                }
            }

            val heading = (ldJson?.get("name") as? JsonPrimitive)?.contentOrNull
                ?.let { HtmlUrlCleaner.cleanText(JsExtractor.unescapeJsString(it)) }
                ?.takeIf { it.isNotBlank() }
                ?: HtmlUrlCleaner.cleanText(doc.selectFirst("h1")?.text().orEmpty())

            val posterRaw = when (val thumbEl = ldJson?.get("thumbnailUrl")) {
                is JsonArray -> (thumbEl.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
                is JsonPrimitive -> thumbEl.contentOrNull.orEmpty()
                else -> doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            }
            val durationRaw = (ldJson?.get("duration") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val authors = (ldJson?.get("actor") as? JsonArray)?.mapNotNull { entry ->
                val actorObj = entry as? JsonObject ?: return@mapNotNull null
                val name = (actorObj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                val href = (actorObj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                AuthorRef(
                    id = name,
                    name = name,
                    role = "streamer",
                    url = VideoSiteSupport.resolveAbsolute(baseUrl, href)
                        .ifBlank { "${baseUrl.trimEnd('/')}/model/${HtmlUrlCleaner.encodePathSegment(name)}" },
                )
            }.orEmpty()

            val related = parseCards(rawHtml, baseUrl).filter { it.id != id }
            val item = MediaItem(
                siteId = SiteId.KBJFREE,
                id = id,
                url = canonicalUrl,
                kind = MediaKind.VIDEO,
                title = heading.ifBlank { "录制 $id" },
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, posterRaw),
                coverAspectRatio = DEFAULT_COVER_RATIO,
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationRaw),
                author = authors.firstOrNull(),
                extra = mapOf("previewOnly" to "true"),
            )

            return VideoDetail(
                item = item,
                description = PREVIEW_DESCRIPTION,
                streams = streams,
                authors = authors,
                related = related,
                isPreviewOnly = true,
            )
        }

        fun looksEmpty(html: String): Boolean {
            val payload = JsExtractor.extractNextRscFlightChunks(html)
            return payload.contains("\"videos\":[]")
        }

        fun lastPage(html: String, currentPage: Int): Int {
            val doc = Jsoup.parse(html)
            val maxPage = doc.select("a[href*=page=]").mapNotNull { a ->
                pageParamRegex.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull() ?: currentPage
            return maxPage.coerceAtLeast(currentPage)
        }
    }
}
