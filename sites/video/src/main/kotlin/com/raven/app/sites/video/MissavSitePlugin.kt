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
import com.raven.app.core.sitespi.Base62Packer
import com.raven.app.core.sitespi.DirectorySource
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.HostRouter
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.MirrorLeaseRouter
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.VideoSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * MissAV (`missav.ws` / `missav.media`) — JAV tube with `.ws` / `.media` mirror failover
 * via [MirrorLeaseRouter] and single-pass [Base62Packer] HLS playlist extraction.
 */
class MissavSitePlugin(
    private val http: HttpClientAdapter,
    clockMs: () -> Long = System::currentTimeMillis,
    private val rateLimitBackoffMs: Long = 1_000L,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val hostRouter: HostRouter = MirrorLeaseRouter(
        hosts = descriptor.allHosts,
        clockMs = clockMs,
    )

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed = coroutineScope {
            val newRequest = FeedRequest(siteId = SiteId.MISSAV, section = "new", sort = FeedSort.NEWEST)
            val popularRequest = FeedRequest(siteId = SiteId.MISSAV, section = "popular", sort = FeedSort.POPULAR)
            val newDeferred = async { loadFeed(newRequest) }
            val popularDeferred = async { runCatching { loadFeed(popularRequest) }.getOrNull() }
            val newPage = newDeferred.await()
            val popularPage = popularDeferred.await()
            VideoSiteSupport.buildHomeFeed(
                siteId = SiteId.MISSAV,
                primaryTitle = "最近更新",
                primaryPage = newPage,
                primarySeeAll = newRequest,
                rankedTitle = "人气排行",
                rankedPage = popularPage,
                rankedSeeAll = popularRequest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val url = resolveFeedUrl(request)
            val (response, html) = VideoSiteSupport.fetchHtmlWithMirror(
                http = http,
                router = hostRouter,
                siteId = SiteId.MISSAV,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val assetOrigin = VideoSiteSupport.hostOrigin(response.finalUrl, BASE_URL)
            val doc = Jsoup.parse(html, assetOrigin)
            val items = parseCards(doc, assetOrigin)
            if (items.isEmpty() && !looksEmpty(doc)) {
                throw SiteException.ParseError("MissAV 未返回可识别的列表，站点可能已改版", siteId = SiteId.MISSAV)
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
            !request.query.isNullOrBlank() || !request.category.isNullOrBlank() -> listOf(FeedSort.NEWEST)
            request.section == "popular" -> listOf(FeedSort.POPULAR)
            else -> listOf(FeedSort.NEWEST, FeedSort.VIEWS)
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
            val canonicalUrl = canonicalVideoUrl(url)
                ?: throw SiteException.ParseError("无法识别 MissAV 作品地址", siteId = SiteId.MISSAV)
            val (response, html) = VideoSiteSupport.fetchHtmlWithMirror(
                http = http,
                router = hostRouter,
                siteId = SiteId.MISSAV,
                url = canonicalUrl,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val assetOrigin = VideoSiteSupport.hostOrigin(response.finalUrl, BASE_URL)
            val doc = Jsoup.parse(html, assetOrigin)
            val streams = extractStreams(html)
            if (streams.isEmpty()) {
                throw SiteException.ParseError("详情页没有播放地址，可能需要登录或站点已改版", siteId = SiteId.MISSAV)
            }
            return parseDetail(doc, canonicalUrl, assetOrigin, streams)
        }
    }

    override val directorySource: DirectorySource = object : DirectorySource {
        override suspend fun loadDirectory(
            section: String,
            page: Int,
            query: String?,
        ): List<DirectoryEntry> {
            val path = when (section) {
                "genres" -> "/genres"
                "actresses" -> "/actresses"
                else -> return emptyList()
            }
            val url = if (page > 1) "$BASE_URL$path?page=$page" else "$BASE_URL$path"
            val (response, html) = VideoSiteSupport.fetchHtmlWithMirror(
                http = http,
                router = hostRouter,
                siteId = SiteId.MISSAV,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val assetOrigin = VideoSiteSupport.hostOrigin(response.finalUrl, BASE_URL)
            val doc = Jsoup.parse(html, assetOrigin)
            val segment = if (section == "genres") "/genres/" else "/actresses/"
            val kind = if (section == "genres") SearchScope.TAG else SearchScope.AUTHOR
            val entries = doc.select("a[href*=$segment]").mapNotNull { a ->
                val rawHref = a.attr("href")
                val slug = rawHref.substringAfter(segment).trimEnd('/').substringBefore('?').substringBefore('#')
                if (slug.isBlank() || slug == "ranking" || slug.contains('/')) return@mapNotNull null
                val label = HtmlUrlCleaner.cleanText(a.text()).ifBlank { HtmlUrlCleaner.decodeUrlComponent(slug) }
                DirectoryEntry(
                    siteId = SiteId.MISSAV,
                    id = slug,
                    title = label,
                    url = "$BASE_URL$segment$slug",
                    kind = kind,
                )
            }.distinctBy { it.id }
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
                val code = canonical.substringAfterLast('/')
                return ResolvedLink.Video(SiteId.MISSAV, code, canonical)
            }
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val rawParts = VideoSiteSupport.pathSegments(uri)
            val parts = if (rawParts.size > 1 && rawParts.first().matches(langPrefixRegex)) {
                rawParts.drop(1)
            } else {
                rawParts
            }
            if (parts.isEmpty()) {
                return ResolvedLink.Feed(FeedRequest(SiteId.MISSAV, section = "new"))
            }
            val page = VideoSiteSupport.queryParam(uri, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val sort = when (VideoSiteSupport.queryParam(uri, "sort")) {
                "views" -> FeedSort.VIEWS
                "monthly_views" -> FeedSort.POPULAR
                else -> FeedSort.NEWEST
            }
            return when (parts[0].lowercase()) {
                "search" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                    ResolvedLink.Feed(FeedRequest(SiteId.MISSAV, query = it, scope = SearchScope.ALL, page = page))
                }
                "genres" -> {
                    val slug = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    if (slug == null) {
                        ResolvedLink.Directory(SiteId.MISSAV, "genres", "$BASE_URL/genres")
                    } else {
                        ResolvedLink.Feed(FeedRequest(SiteId.MISSAV, query = slug, scope = SearchScope.TAG, page = page))
                    }
                }
                "actresses" -> {
                    val slug = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "ranking" }
                    if (slug == null) {
                        ResolvedLink.Directory(SiteId.MISSAV, "actresses", "$BASE_URL/actresses")
                    } else {
                        ResolvedLink.Feed(FeedRequest(SiteId.MISSAV, query = slug, scope = SearchScope.AUTHOR, page = page))
                    }
                }
                "uncensored-leak", "chinese-subtitle" -> ResolvedLink.Feed(
                    FeedRequest(SiteId.MISSAV, category = parts[0].lowercase(), page = page),
                )
                "new", "release" -> ResolvedLink.Feed(
                    FeedRequest(SiteId.MISSAV, section = parts[0].lowercase(), sort = sort, page = page),
                )
                else -> null
            }
        }
    }

    companion object {
        const val BASE_URL: String = "https://missav.ws"
        private val videoPathRegex = Regex("^/([a-z0-9]+(?:-[a-z0-9]+)*-[a-z]*\\d+)/?$", RegexOption.IGNORE_CASE)
        private val playlistRegex = Regex("""(https?://[^'"\s]+?/[0-9a-f-]{32,36}/playlist\.m3u8)""")
        private val pageParamRegex = Regex("[?&]page=(\\d+)")
        private val langPrefixRegex = Regex("^[a-z]{2}(?:-[a-z]{2})?$", RegexOption.IGNORE_CASE)

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.MISSAV,
            displayName = "MissAV",
            shortName = "MissAV",
            primaryKind = MediaKind.VIDEO,
            supportedKinds = setOf(MediaKind.VIDEO),
            defaultHost = "missav.ws",
            mirrorHosts = listOf("missav.media"),
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.DIRECTORY,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.RELATED,
                SiteCapability.MIRRORS,
            ),
            accessNote = "受限时切换镜像",
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.POPULAR),
            sections = listOf(
                SiteSectionSpec("new", "最近更新", "刚上架的作品", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec("release", "新作上市", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec("uncensored-leak", "无码流出", category = "uncensored-leak"),
                SiteSectionSpec("chinese-subtitle", "中文字幕", category = "chinese-subtitle"),
                SiteSectionSpec("popular", "人气排行", defaultSort = FeedSort.POPULAR),
                SiteSectionSpec("genres", "类型索引", "按题材浏览", isDirectory = true),
                SiteSectionSpec("actresses", "女优列表", isDirectory = true),
            ),
            searchScopes = listOf(SearchScope.ALL, SearchScope.TAG, SearchScope.AUTHOR),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                mediaDomains = setOf("mrstcdn.store"),
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
                val encoded = HtmlUrlCleaner.encodePathSegment(query)
                return when (request.scope) {
                    SearchScope.TAG -> paged("$base/genres/$encoded")
                    SearchScope.AUTHOR -> paged("$base/actresses/$encoded")
                    SearchScope.ALL, SearchScope.CHARACTER -> paged("$base/search/$encoded")
                }
            }
            if (category.isNotEmpty()) {
                return paged("$base/${category.trim('/')}")
            }
            if (section == "popular") {
                return paged("$base/new?sort=monthly_views")
            }

            val path = section.ifBlank { "new" }.trim('/')
            val sortParam = when (request.sort) {
                FeedSort.VIEWS -> "views"
                FeedSort.POPULAR -> "monthly_views"
                else -> null
            }
            val url = if (sortParam != null) "$base/$path?sort=$sortParam" else "$base/$path"
            return paged(url)
        }

        fun feedTitle(request: FeedRequest): String {
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return when (request.scope) {
                    SearchScope.TAG -> "类型 · $query"
                    SearchScope.AUTHOR -> "女优 · $query"
                    else -> query
                }
            }
            val cat = request.category?.trim().orEmpty()
            if (cat.isNotEmpty()) {
                return DESCRIPTOR.sections.firstOrNull { it.category == cat || it.id == cat }?.title ?: cat
            }
            val sec = request.section?.trim().orEmpty()
            return DESCRIPTOR.sections.firstOrNull { it.id == sec }?.title ?: "最近更新"
        }

        /**
         * Normalizes any MissAV primary/mirror video URL (stripping language prefixes & query params)
         * to `https://missav.ws/<code>`.
         */
        fun canonicalVideoUrl(raw: String, baseUrl: String = BASE_URL): String? {
            if (raw.isBlank()) return null
            val candidate = if (raw.startsWith("/")) "${baseUrl.trimEnd('/')}$raw" else raw.trim()
            val uri = UrlSanitizer.parseUriOrNull(candidate) ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!DESCRIPTOR.matchesHost(host)) return null
            val segments = VideoSiteSupport.pathSegments(uri)
            val code = segments.lastOrNull { it.isNotBlank() } ?: return null
            if (!videoPathRegex.matches("/$code/")) return null
            return "${baseUrl.trimEnd('/')}/$code"
        }

        fun parseCards(doc: Document, assetOrigin: String = BASE_URL): List<MediaItem> =
            doc.select("div.thumbnail.group")
                .mapNotNull { parseCard(it, assetOrigin) }
                .distinctBy { it.id }

        private fun parseCard(card: Element, assetOrigin: String): MediaItem? {
            val link = card.selectFirst("a[href]") ?: return null
            val canonical = canonicalVideoUrl(link.attr("href")) ?: return null
            val id = canonical.substringAfterLast('/')
            if (id.isBlank()) return null
            val title = HtmlUrlCleaner.cleanText(
                card.selectFirst("div.truncate a")?.text().orEmpty()
                    .ifBlank { card.selectFirst("img[alt]")?.attr("alt").orEmpty() },
            )
            if (title.isBlank()) return null
            val img = card.selectFirst("img")
            val thumbRaw = listOf(img?.attr("data-src"), img?.attr("src"))
                .firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", ignoreCase = true) }
            val previewRaw = card.selectFirst("video[data-src]")?.attr("data-src")
            val durationLabel = HtmlUrlCleaner.cleanText(card.selectFirst("span.absolute")?.text().orEmpty())
            return MediaItem(
                siteId = SiteId.MISSAV,
                id = id,
                url = canonical,
                kind = MediaKind.VIDEO,
                title = title,
                coverUrl = VideoSiteSupport.resolveAbsolute(assetOrigin, thumbRaw),
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationLabel),
                previewUrl = VideoSiteSupport.resolveAbsolute(assetOrigin, previewRaw).ifBlank { null },
            )
        }

        fun looksEmpty(doc: Document): Boolean {
            if (doc.select("div.thumbnail.group").isNotEmpty()) return false
            val text = doc.text()
            return text.contains("沒有找到", ignoreCase = true) ||
                text.contains("没有找到", ignoreCase = true) ||
                text.contains("No results", ignoreCase = true)
        }

        fun lastPage(doc: Document): Int? =
            doc.select("a[href*=page=]").mapNotNull { a ->
                pageParamRegex.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull()

        /**
         * Extracts the master HLS playlist URL using single-pass [Base62Packer.unpackAll].
         */
        fun extractStreams(html: String): List<VideoStream> {
            for (unpacked in Base62Packer.unpackAll(html)) {
                findPlaylistUrl(unpacked)?.let { playlistUrl ->
                    return listOf(
                        VideoStream(
                            quality = "auto",
                            label = "自动",
                            url = playlistUrl,
                            isHls = true,
                            mimeTypeHint = "application/vnd.apple.mpegurl",
                        ),
                    )
                }
            }
            findPlaylistUrl(html)?.let { playlistUrl ->
                return listOf(
                    VideoStream(
                        quality = "auto",
                        label = "自动",
                        url = playlistUrl,
                        isHls = true,
                        mimeTypeHint = "application/vnd.apple.mpegurl",
                    ),
                )
            }
            return emptyList()
        }

        private fun findPlaylistUrl(text: String): String? =
            playlistRegex.find(text)?.groupValues?.get(1)

        fun parseDetail(
            doc: Document,
            canonicalUrl: String,
            assetOrigin: String,
            streams: List<VideoStream>,
        ): VideoDetail {
            val id = canonicalUrl.trimEnd('/').substringAfterLast('/')
            val heading = HtmlUrlCleaner.cleanText(doc.selectFirst("h1")?.text().orEmpty())
            val posterRaw = doc.selectFirst("video.player")?.attr("data-poster").orEmpty()
                .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
            val durationRaw = doc.selectFirst("meta[property=og:video:duration]")?.attr("content").orEmpty()
            val description = HtmlUrlCleaner.cleanText(
                doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(),
            )

            val authors = doc.select("a[href*=/actresses/]").mapNotNull { a ->
                val slug = a.attr("href").substringAfter("/actresses/").trimEnd('/').substringBefore('?')
                if (slug.isBlank() || slug == "ranking" || slug.contains('/')) return@mapNotNull null
                val name = HtmlUrlCleaner.cleanText(a.text()).ifBlank { HtmlUrlCleaner.decodeUrlComponent(slug) }
                AuthorRef(id = slug, name = name, role = "actress", url = "$BASE_URL/actresses/$slug")
            }.distinctBy { it.id }

            val tags = doc.select("a[href*=/genres/]").mapNotNull { a ->
                val slug = a.attr("href").substringAfter("/genres/").trimEnd('/').substringBefore('?')
                if (slug.isBlank() || slug.contains('/')) return@mapNotNull null
                val label = HtmlUrlCleaner.decodeUrlComponent(slug)
                TagRef(slug = slug, label = label, url = "$BASE_URL/genres/$slug")
            }.distinctBy { it.slug }

            val related = parseCards(doc, assetOrigin).filter { it.id != id }

            val item = MediaItem(
                siteId = SiteId.MISSAV,
                id = id,
                url = canonicalUrl,
                kind = MediaKind.VIDEO,
                title = heading.ifBlank { "作品 $id" },
                coverUrl = VideoSiteSupport.resolveAbsolute(assetOrigin, posterRaw),
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
                related = related,
            )
        }
    }
}
