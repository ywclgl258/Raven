package com.raven.app.sites.video

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.CommentItem
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
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.AuthState
import com.raven.app.core.sitespi.DirectorySource
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.JsExtractor
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteSessionStore
import com.raven.app.core.sitespi.VideoSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Rule34Video (`rule34video.com`) — Kernel Video Sharing (KVS) SSR video tube.
 */
class R34vSitePlugin(
    private val http: HttpClientAdapter,
    private val sessionStore: SiteSessionStore,
    private val rateLimitBackoffMs: Long = 1_000L,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed = coroutineScope {
            val latestRequest = FeedRequest(siteId = SiteId.R34V, section = "latest", sort = FeedSort.NEWEST)
            val popularRequest = FeedRequest(siteId = SiteId.R34V, section = "popular", sort = FeedSort.VIEWS)
            val latestDeferred = async { loadFeed(latestRequest) }
            val popularDeferred = async { runCatching { loadFeed(popularRequest) }.getOrNull() }
            val latestPage = latestDeferred.await()
            val popularPage = popularDeferred.await()
            VideoSiteSupport.buildHomeFeed(
                siteId = SiteId.R34V,
                primaryTitle = "最新更新",
                primaryPage = latestPage,
                primarySeeAll = latestRequest,
                rankedTitle = "热门榜",
                rankedPage = popularPage,
                rankedSeeAll = popularRequest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            VideoSiteSupport.validateFilters(request.advancedFilters, SiteId.R34V)
            val url = resolveFeedUrl(request)
            val (response, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.R34V,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            if (html.contains("id=\"login_username\"")) {
                throw SiteException.AuthRequired("此页面需要登录，请在设置中登录", siteId = SiteId.R34V)
            }
            val parsed = parseFeed(html, request, baseUrl = BASE_URL, fromCache = response.fromCache)
            val filtered = VideoSiteSupport.applyLocalFilters(
                items = parsed.items,
                filters = request.advancedFilters,
                filterDuration = true,
                filterRating = true,
            )
            return parsed.copy(items = filtered)
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = descriptor.feedSorts
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(
            SearchScope.ALL,
            SearchScope.TAG,
            SearchScope.AUTHOR,
            SearchScope.CHARACTER,
        )
        override val supportedFilters: Set<AdvancedFilterField> = setOf(
            AdvancedFilterField.DURATION,
            AdvancedFilterField.RATING,
        )
        override val supportsExcludeTags: Boolean = false
    }

    override val videoSource: VideoSource = object : VideoSource {
        override suspend fun loadVideoDetail(url: String): VideoDetail {
            val target = VideoSiteSupport.resolveAbsolute(BASE_URL, url)
                .ifBlank { throw SiteException.ParseError("无法识别 Rule34Video 视频地址", siteId = SiteId.R34V) }
            val (_, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.R34V,
                url = target,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            if (html.contains("id=\"login_username\"") && !html.contains("flashvars")) {
                throw SiteException.AuthRequired("此视频需要登录后观看", siteId = SiteId.R34V)
            }
            return parseDetail(html, target, BASE_URL)
        }
    }

    override val directorySource: DirectorySource = object : DirectorySource {
        override suspend fun loadDirectory(
            section: String,
            page: Int,
            query: String?,
        ): List<DirectoryEntry> {
            val url = directoryUrl(section, page) ?: return emptyList()
            val (_, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.R34V,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val entries = when {
                section == "works" -> parseWorksDirectory(html, BASE_URL)
                section == "artists" || section.startsWith("work:") -> parseRankingDirectory(html, BASE_URL)
                else -> parseStandardDirectory(html, section, BASE_URL)
            }
            val q = query?.trim().orEmpty()
            return if (q.isEmpty()) {
                entries
            } else {
                entries.filter {
                    it.title.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
                }
            }
        }

        override fun directorySections(): List<SiteSectionSpec> =
            descriptor.sections.filter { it.isDirectory }
    }

    override val authProvider: AuthProvider = object : AuthProvider {
        override val mode: AuthMode = AuthMode.FormCredentials

        override suspend fun loginWithCredentials(username: String, password: String): AuthState {
            val trimmedUser = username.trim()
            if (trimmedUser.isBlank() || password.isEmpty()) {
                throw SiteException.AuthRequired("请填写用户名和密码", siteId = SiteId.R34V)
            }
            val loginUrl = "$BASE_URL/login/"
            val getResp = http.getText(
                url = loginUrl,
                siteId = SiteId.R34V,
                allowCache = false,
            )
            val loginHtml = VideoSiteSupport.ensureSuccess(getResp, SiteId.R34V, checkChallenge = true)
            val csrfToken = Jsoup.parse(loginHtml)
                .selectFirst("input[name=remember_me_csrf_token]")
                ?.attr("value")
                ?.takeIf { it.isNotBlank() }
                ?: throw SiteException.ParseError("无法读取登录令牌，站点可能要求浏览器验证", siteId = SiteId.R34V)

            val cookies = LinkedHashMap<String, String>()
            cookies.putAll(sessionStore.getCookies(SiteId.R34V))
            cookies.putAll(VideoSiteSupport.extractSetCookies(getResp))

            val postHeaders = if (cookies.isNotEmpty()) {
                mapOf("Cookie" to VideoSiteSupport.formatCookieHeader(cookies))
            } else {
                emptyMap()
            }

            val postResp = http.postForm(
                url = loginUrl,
                fields = linkedMapOf(
                    "username" to trimmedUser,
                    "pass" to password,
                    "action" to "login",
                    "remember_me" to "1",
                    "remember_me_csrf_token" to csrfToken,
                ),
                siteId = SiteId.R34V,
                headers = postHeaders,
                followRedirects = true,
            )
            val body = VideoSiteSupport.ensureSuccess(postResp, SiteId.R34V, checkChallenge = true)
            val doc = Jsoup.parse(body)
            val errorText = HtmlUrlCleaner.cleanText(
                doc.selectFirst(".generic-error, .alert-error")?.text().orEmpty(),
            )
            if (errorText.isNotBlank()) {
                throw SiteException.AuthRequired(errorText, siteId = SiteId.R34V)
            }
            if (body.contains("id=\"login_username\"") &&
                !body.contains("action=logout") &&
                !body.contains("/logout/")
            ) {
                throw SiteException.AuthRequired("登录未成功，请检查用户名、密码和邮箱验证", siteId = SiteId.R34V)
            }

            cookies.putAll(VideoSiteSupport.extractSetCookies(postResp))
            if (cookies.isNotEmpty()) {
                sessionStore.saveCookies(SiteId.R34V, cookies)
            }
            val persisted = sessionStore.getCookies(SiteId.R34V)
            if (!persisted.containsKey("PHPSESSID")) {
                throw SiteException.AuthRequired("服务器没有返回会话 (缺少 PHPSESSID)", siteId = SiteId.R34V)
            }
            sessionStore.saveUsername(SiteId.R34V, trimmedUser)
            return AuthState(
                isAuthenticated = true,
                username = trimmedUser,
                epoch = sessionStore.authEpoch(SiteId.R34V),
            )
        }

        override suspend fun logout() {
            sessionStore.clearSite(SiteId.R34V)
        }

        override suspend fun currentAuthState(): AuthState {
            val hasSession = sessionStore.getCookies(SiteId.R34V).containsKey("PHPSESSID")
            return AuthState(
                isAuthenticated = hasSession,
                username = if (hasSession) sessionStore.getUsername(SiteId.R34V) else null,
                epoch = sessionStore.authEpoch(SiteId.R34V),
            )
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val host = UrlSanitizer.extractHost(url) ?: return false
            return descriptor.matchesHost(host)
        }

        override fun canonicalize(url: String): String? {
            if (!canHandle(url)) return null
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val path = uri.rawPath.orEmpty()
            videoIdRegex.find(path)?.let { match ->
                return "$BASE_URL/video/${match.groupValues[1]}/"
            }
            return UrlSanitizer.normalizeHttpsUrl(url)
        }

        override fun resolve(url: String): ResolvedLink? {
            if (!canHandle(url)) return null
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val segments = VideoSiteSupport.pathSegments(uri)
            if (segments.isEmpty()) {
                return ResolvedLink.Feed(FeedRequest(siteId = SiteId.R34V, section = "latest"))
            }
            val sort = parseSortParam(VideoSiteSupport.queryParam(uri, "sort_by"))
            return when (segments[0].lowercase()) {
                "video" -> {
                    val id = segments.getOrNull(1)?.takeIf { it.all(Char::isDigit) } ?: return null
                    ResolvedLink.Video(
                        siteId = SiteId.R34V,
                        id = id,
                        canonicalUrl = "$BASE_URL/video/$id/",
                    )
                }
                "search" -> {
                    val query = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                    val page = segments.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    ResolvedLink.Feed(
                        FeedRequest(
                            siteId = SiteId.R34V,
                            query = query,
                            scope = SearchScope.ALL,
                            sort = sort,
                            page = page,
                        ),
                    )
                }
                "tags" -> {
                    val slug = segments.getOrNull(1)?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
                    if (slug == null) {
                        ResolvedLink.Directory(SiteId.R34V, "tags", "$BASE_URL/tags/")
                    } else {
                        val page = segments.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        ResolvedLink.Feed(
                            FeedRequest(
                                siteId = SiteId.R34V,
                                query = slug,
                                scope = SearchScope.TAG,
                                sort = sort,
                                page = page,
                            ),
                        )
                    }
                }
                "characters" -> {
                    val slug = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                    val page = segments.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    ResolvedLink.Feed(
                        FeedRequest(
                            siteId = SiteId.R34V,
                            query = slug,
                            scope = SearchScope.CHARACTER,
                            sort = sort,
                            page = page,
                        ),
                    )
                }
                "models" -> {
                    val slug = segments.getOrNull(1)?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
                    if (slug == null) {
                        ResolvedLink.Directory(SiteId.R34V, "models", "$BASE_URL/models/")
                    } else {
                        val page = segments.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        ResolvedLink.Feed(
                            FeedRequest(
                                siteId = SiteId.R34V,
                                query = slug,
                                scope = SearchScope.AUTHOR,
                                sort = sort,
                                page = page,
                            ),
                        )
                    }
                }
                "categories" -> {
                    val slug = segments.getOrNull(1)?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
                    if (slug == null) {
                        ResolvedLink.Directory(SiteId.R34V, "categories", "$BASE_URL/categories/")
                    } else {
                        val page = segments.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        ResolvedLink.Feed(
                            FeedRequest(
                                siteId = SiteId.R34V,
                                category = slug,
                                sort = sort,
                                page = page,
                            ),
                        )
                    }
                }
                "channels" -> {
                    val slug = segments.getOrNull(1)?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
                    if (slug == null) {
                        ResolvedLink.Directory(SiteId.R34V, "channels", "$BASE_URL/channels/")
                    } else {
                        val page = segments.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        ResolvedLink.Feed(
                            FeedRequest(
                                siteId = SiteId.R34V,
                                section = "channel:$slug",
                                sort = sort,
                                page = page,
                            ),
                        )
                    }
                }
                "top-characters" -> {
                    val slug = segments.getOrNull(1)?.takeIf { it.isNotBlank() }
                    val sec = if (slug == null) "works" else "work:$slug"
                    val canonical = if (slug == null) "$BASE_URL/top-characters/" else "$BASE_URL/top-characters/$slug/"
                    ResolvedLink.Directory(SiteId.R34V, sec, canonical)
                }
                "top-model" -> ResolvedLink.Directory(SiteId.R34V, "artists", "$BASE_URL/top-model/?sort=az")
                "latest-updates" -> {
                    val page = segments.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    ResolvedLink.Feed(FeedRequest(SiteId.R34V, section = "latest", sort = sort, page = page))
                }
                "most-popular" -> {
                    val page = segments.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    ResolvedLink.Feed(FeedRequest(SiteId.R34V, section = "popular", sort = sort, page = page))
                }
                "top-rated" -> {
                    val page = segments.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    ResolvedLink.Feed(FeedRequest(SiteId.R34V, section = "rated", sort = sort, page = page))
                }
                "for-you" -> ResolvedLink.Feed(FeedRequest(SiteId.R34V, section = "foryou", sort = sort))
                else -> null
            }
        }
    }

    companion object {
        const val BASE_URL: String = "https://rule34video.com"
        private val videoIdRegex = Regex("/video/(\\d+)/?")
        private val pageOfRegex = Regex("Page\\s+([\\d,]+)\\s+of\\s+([\\d,]+)")
        private val pathPageRegex = Regex("/(\\d+)/?(?:\\?.*)?$")
        private val groupLinkRegex = Regex("/(characters|categories|models|tags|channels)/([^/?#]+)/?")
        private val workLinkRegex = Regex("^/top-characters/([^/?#]+)/$")
        private val allCountRegex = Regex("^All \\d+", RegexOption.IGNORE_CASE)
        private val durationClockRegex = Regex("\\d+:\\d{2}(:\\d{2})?")
        private val downloadQualityRegex = Regex("(\\d{3,4})p?\\.mp4", RegexOption.IGNORE_CASE)
        private val qualityTextRegex = Regex("\\d{3,4}p", RegexOption.IGNORE_CASE)
        private val flashvarFieldRegex = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(['"])((?:\\.|(?!\2).)*)\2""")

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.R34V,
            displayName = "Rule34Video",
            shortName = "R34V",
            primaryKind = MediaKind.VIDEO,
            supportedKinds = setOf(MediaKind.VIDEO),
            defaultHost = "rule34video.com",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.ADVANCED_SEARCH,
                SiteCapability.DIRECTORY,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.CHARACTERS,
                SiteCapability.COMMENTS,
                SiteCapability.RELATED,
                SiteCapability.AUTH,
                SiteCapability.STREAM_QUALITIES,
            ),
            feedSorts = listOf(
                FeedSort.RELEVANCE,
                FeedSort.NEWEST,
                FeedSort.VIEWS,
                FeedSort.RATING,
                FeedSort.DURATION,
                FeedSort.RANDOM,
            ),
            sections = listOf(
                SiteSectionSpec("popular", "热门榜", "看看大家最近在看什么", defaultSort = FeedSort.VIEWS),
                SiteSectionSpec("latest", "最新更新", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec("rated", "高分榜", defaultSort = FeedSort.RATING),
                SiteSectionSpec("foryou", "为你推荐", defaultSort = FeedSort.DEFAULT),
                SiteSectionSpec("categories", "分类", "按内容类型浏览", isDirectory = true),
                SiteSectionSpec("works", "角色与作品", isDirectory = true),
                SiteSectionSpec("artists", "作者排行", isDirectory = true),
                SiteSectionSpec("models", "作者索引", isDirectory = true),
                SiteSectionSpec("tags", "标签索引", isDirectory = true),
                SiteSectionSpec("channels", "频道索引", isDirectory = true),
            ),
            searchScopes = listOf(
                SearchScope.ALL,
                SearchScope.TAG,
                SearchScope.AUTHOR,
                SearchScope.CHARACTER,
            ),
            supportedFilters = setOf(
                AdvancedFilterField.DURATION,
                AdvancedFilterField.RATING,
            ),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
            ),
        )

        fun resolveFeedUrl(request: FeedRequest, baseUrl: String = BASE_URL): String {
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            val category = request.category?.trim().orEmpty()
            val section = request.section?.trim().orEmpty()

            var isSearch = false
            var isForYou = false
            val path = when {
                query.isNotEmpty() -> {
                    val encoded = HtmlUrlCleaner.encodePathSegment(query)
                    when (request.scope) {
                        SearchScope.ALL -> {
                            isSearch = true
                            "/search/$encoded/"
                        }
                        SearchScope.TAG, SearchScope.CHARACTER -> "/tags/$encoded/"
                        SearchScope.AUTHOR -> "/models/$encoded/"
                    }
                }
                category.isNotEmpty() -> "/categories/${HtmlUrlCleaner.encodePathSegment(category)}/"
                section.startsWith("channel:") -> {
                    val slug = section.removePrefix("channel:").trim()
                    "/channels/${HtmlUrlCleaner.encodePathSegment(slug)}/"
                }
                section == "popular" || section == "most-popular" -> "/most-popular/"
                section == "rated" || section == "top-rated" -> "/top-rated/"
                section == "foryou" || section == "for-you" -> {
                    isForYou = true
                    "/for-you/"
                }
                else -> "/latest-updates/"
            }

            val pageSuffix = if (page > 1 && !isForYou) "$page/" else ""
            val sortParam = sortToParam(request.sort)
            val sortPart = if (sortParam.isNotEmpty() && (request.sort != FeedSort.NEWEST || isSearch)) {
                "?sort_by=$sortParam"
            } else {
                ""
            }
            return "${baseUrl.trimEnd('/')}$path$pageSuffix$sortPart"
        }

        fun directoryUrl(section: String, page: Int = 1, baseUrl: String = BASE_URL): String? {
            val base = baseUrl.trimEnd('/')
            val pageSuffix = if (page > 1) "$page/" else ""
            return when {
                section in setOf("categories", "tags", "models", "channels") -> "$base/$section/$pageSuffix"
                section == "works" -> "$base/top-characters/"
                section == "artists" -> "$base/top-model/?sort=az"
                section.startsWith("work:") -> {
                    val slug = section.removePrefix("work:").trim()
                    if (slug.isEmpty()) null else "$base/top-characters/$slug/"
                }
                else -> null
            }
        }

        private fun sortToParam(sort: FeedSort): String = when (sort) {
            FeedSort.NEWEST -> "post_date"
            FeedSort.VIEWS, FeedSort.POPULAR -> "video_viewed"
            FeedSort.RATING -> "rating"
            FeedSort.DURATION -> "duration"
            FeedSort.RANDOM -> "pseudo_rand"
            FeedSort.RELEVANCE, FeedSort.DEFAULT, FeedSort.TRENDING -> ""
        }

        private fun parseSortParam(raw: String?): FeedSort = when (raw?.lowercase()) {
            "post_date" -> FeedSort.NEWEST
            "video_viewed" -> FeedSort.VIEWS
            "rating" -> FeedSort.RATING
            "duration" -> FeedSort.DURATION
            "pseudo_rand" -> FeedSort.RANDOM
            else -> FeedSort.DEFAULT
        }

        private fun Element?.cleanText(): String = HtmlUrlCleaner.cleanText(this?.text().orEmpty())

        private fun extractThumb(img: Element?, baseUrl: String): String {
            if (img == null) return ""
            val raw = listOf(
                img.attr("data-rd-src"),
                img.attr("src"),
                img.attr("data-rd-jpg"),
                img.attr("data-src"),
            ).firstOrNull { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
            return VideoSiteSupport.resolveAbsolute(baseUrl, raw)
        }

        private fun feedScope(doc: Element): Element =
            doc.selectFirst("#custom_list_videos_latest_videos_list_items, .thumbs[id], .thumbs") ?: doc

        fun parseCards(rootContainer: Element, baseUrl: String = BASE_URL): List<MediaItem> {
            val root = feedScope(rootContainer)
            val elements = root.select("div[data-video-card-id]").ifEmpty { root.select("div.item.thumb") }
            return elements.mapNotNull { item ->
                val anchor = item.selectFirst("a.th, a[href*=/video/]") ?: return@mapNotNull null
                val url = VideoSiteSupport.resolveAbsolute(baseUrl, anchor.attr("href"))
                val id = videoIdRegex.find(url)?.groupValues?.get(1) ?: return@mapNotNull null
                val img = item.selectFirst("img.thumb, img")
                val title = listOf(
                    item.selectFirst(".thumb_title").cleanText(),
                    HtmlUrlCleaner.cleanText(img?.attr("alt").orEmpty()),
                    HtmlUrlCleaner.cleanText(anchor.attr("title")),
                ).firstOrNull { it.isNotBlank() }.orEmpty()
                val durationLabel = item.selectFirst(".time").cleanText()
                val viewsLabel = item.selectFirst(".video-views-count").cleanText()
                val ratingLabel = item.selectFirst(".video-card-meta__rating, .rating").cleanText()
                val badge = item.selectFirst(".futa").cleanText()
                val preview = VideoSiteSupport.resolveAbsolute(
                    baseUrl,
                    item.selectFirst("[data-preview]")?.attr("data-preview"),
                )
                MediaItem(
                    siteId = SiteId.R34V,
                    id = id,
                    url = url,
                    kind = MediaKind.VIDEO,
                    title = title.ifBlank { "Video $id" },
                    coverUrl = extractThumb(img, baseUrl),
                    durationSeconds = VideoSiteSupport.parseDurationSeconds(durationLabel),
                    views = VideoSiteSupport.parseCount(viewsLabel),
                    rating = VideoSiteSupport.parseRatingStars(ratingLabel),
                    previewUrl = preview.ifBlank { null },
                    extra = buildMap {
                        if (badge.isNotBlank()) put("badge", badge)
                        if (ratingLabel.isNotBlank()) put("ratingLabel", ratingLabel)
                    },
                )
            }.distinctBy { it.id }
        }

        fun parseFeed(
            html: String,
            request: FeedRequest,
            baseUrl: String = BASE_URL,
            fromCache: Boolean = false,
        ): FeedPage {
            val doc = Jsoup.parse(html, baseUrl)
            val videos = parseCards(doc, baseUrl)
            if (videos.isEmpty() && doc.selectFirst(".thumbs") == null) {
                throw SiteException.ParseError("列表页结构无法识别，请检查登录状态或站点是否改版", siteId = SiteId.R34V)
            }
            if (videos.isEmpty() && feedScope(doc).select("div.item").isNotEmpty()) {
                throw SiteException.ParseError("列表结构已变化：没有可识别的视频链接", siteId = SiteId.R34V)
            }
            val pager = doc.select("button.page-of, [data-rd-jump], nav.fs-pager button")
                .firstNotNullOfOrNull { pageOfRegex.find(it.text()) }
            val current = pager?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: request.page.coerceAtLeast(1)
            val total = pager?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull()
                ?: doc.select("nav a[data-page], .pagination a, .fs-pager a").mapNotNull { a ->
                    a.attr("data-page").toIntOrNull()
                        ?: pathPageRegex.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                }.maxOrNull()
                ?: current
            val safeTotal = total.coerceAtLeast(current)
            val hasMore = current < safeTotal
            return FeedPage(
                items = videos,
                nextPage = if (hasMore) current + 1 else null,
                hasMore = hasMore,
                totalPages = safeTotal,
                title = doc.selectFirst("h1").cleanText().ifBlank { null },
                fromCache = fromCache,
            )
        }

        fun extractFlashvars(scripts: List<String>): Map<String, String> {
            for (script in scripts) {
                val block = JsExtractor.extractBraceObject(script, "flashvars") ?: continue
                val inner = block.removePrefix("{").removeSuffix("}")
                val fields = flashvarFieldRegex.findAll(inner).associate { m ->
                    m.groupValues[1] to JsExtractor.unescapeJsString(m.groupValues[3])
                }
                if (fields.isNotEmpty()) return fields
            }
            return emptyMap()
        }

        fun parseDetail(html: String, fallbackUrl: String, baseUrl: String = BASE_URL): VideoDetail {
            val doc = Jsoup.parse(html, baseUrl)
            val fv = extractFlashvars(doc.select("script").map { it.data() })
            val id = fv["video_id"].orEmpty().ifBlank {
                videoIdRegex.find(fallbackUrl)?.groupValues?.get(1).orEmpty()
            }
            if (id.isBlank()) {
                throw SiteException.ParseError("详情页无法识别：缺少视频 ID", siteId = SiteId.R34V)
            }
            val streamKeys = listOf("video_url", "video_alt_url", "video_alt_url2", "video_alt_url3", "video_alt_url4")
            val streams = streamKeys.mapNotNull { key ->
                val rawUrl = fv[key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val resolved = VideoSiteSupport.resolveAbsolute(baseUrl, rawUrl)
                if (resolved.isBlank()) return@mapNotNull null
                val label = HtmlUrlCleaner.cleanText(fv["${key}_text"].orEmpty()).ifBlank { "原画" }
                val isHls = resolved.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                VideoStream(
                    quality = label,
                    label = label,
                    url = resolved,
                    isHls = isHls,
                    mimeTypeHint = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4",
                )
            }.distinctBy { it.label }.sortedByDescending { VideoSiteSupport.parseStreamHeight(it.label) }
            if (streams.isEmpty()) {
                throw SiteException.ParseError("详情页没有播放地址，可能需要登录或站点已改版", siteId = SiteId.R34V)
            }

            val tags = mutableListOf<TagRef>()
            val characters = mutableListOf<TagRef>()
            val categories = mutableListOf<TagRef>()
            val authors = mutableListOf<AuthorRef>()

            val headingNames = mapOf(
                "characters" to "characters",
                "categories" to "categories",
                "artists" to "artists",
                "artist" to "artists",
                "tags" to "tags",
            )
            for (heading in doc.select(".vp-group__title")) {
                val bucket = headingNames[heading.text().lowercase().trim()] ?: continue
                var next = heading.nextElementSibling()
                while (next != null && !next.hasClass("vp-group__title")) {
                    val links = next.select("a[href]").toMutableList()
                    if (next.tagName() == "a") links.add(next)
                    for (a in links) {
                        val m = groupLinkRegex.find(a.attr("href")) ?: continue
                        val slug = m.groupValues[2]
                        val label = a.cleanText().ifBlank { slug.replace('-', ' ') }
                        val resolved = VideoSiteSupport.resolveAbsolute(
                            baseUrl,
                            a.attr("href").replace("/characters/", "/tags/"),
                        )
                        when (bucket) {
                            "characters" -> if (characters.none { it.slug == slug }) {
                                characters.add(TagRef(slug = slug, label = label, namespace = "character", url = resolved))
                            }
                            "categories" -> if (categories.none { it.slug == slug }) {
                                categories.add(TagRef(slug = slug, label = label, namespace = "category", url = resolved))
                            }
                            "artists" -> if (authors.none { it.id == slug }) {
                                authors.add(AuthorRef(id = slug, name = label, role = "artist", url = resolved))
                            }
                            "tags" -> if (tags.none { it.slug == slug }) {
                                tags.add(TagRef(slug = slug, label = label, url = resolved))
                            }
                        }
                    }
                    next = next.nextElementSibling()
                }
            }

            if (tags.isEmpty() && categories.isEmpty() && authors.isEmpty() && characters.isEmpty()) {
                for (a in doc.select("[data-suggest-type=tag] a[href*=/tags/]")) {
                    val slug = a.attr("href").trimEnd('/').substringAfterLast('/')
                    if (slug.isNotBlank() && tags.none { it.slug == slug }) {
                        tags.add(TagRef(slug = slug, label = a.cleanText().ifBlank { slug }, url = VideoSiteSupport.resolveAbsolute(baseUrl, a.attr("href"))))
                    }
                }
                for (a in doc.select("[data-suggest-type=category] a[href*=/categories/]")) {
                    val slug = a.attr("href").trimEnd('/').substringAfterLast('/')
                    if (slug.isNotBlank() && categories.none { it.slug == slug }) {
                        categories.add(TagRef(slug = slug, label = a.cleanText().ifBlank { slug }, namespace = "category", url = VideoSiteSupport.resolveAbsolute(baseUrl, a.attr("href"))))
                    }
                }
                for (a in doc.select("[data-suggest-type=model] a[href*=/models/]")) {
                    val slug = a.attr("href").trimEnd('/').substringAfterLast('/')
                    if (slug.isNotBlank() && authors.none { it.id == slug }) {
                        authors.add(AuthorRef(id = slug, name = a.cleanText().ifBlank { slug }, role = "artist", url = VideoSiteSupport.resolveAbsolute(baseUrl, a.attr("href"))))
                    }
                }
            }

            val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty()
                .ifBlank { doc.selectFirst("meta[property=og:url]")?.attr("content").orEmpty() }
                .ifBlank { fallbackUrl }
                .let { VideoSiteSupport.resolveAbsolute(baseUrl, it).ifBlank { fallbackUrl } }

            val durationLabel = doc.select("span.item_info")
                .map { it.cleanText() }
                .firstOrNull { durationClockRegex.matches(it) }
                .orEmpty()
            val description = doc.selectFirst(".vp-desc, .js-vp-desc")?.wholeText()?.trim().orEmpty()
            val relatedContainer = doc.selectFirst("#custom_list_videos_related_videos_items")
            val related = if (relatedContainer != null) {
                parseCards(Jsoup.parse("<div class='thumbs'>${relatedContainer.html()}</div>", baseUrl), baseUrl)
                    .filter { it.id != id }
            } else {
                emptyList()
            }

            val comments = doc.select("#tab_comments .comment_item, #tab_comments .comment-item, #tab_comments .comment")
                .mapIndexedNotNull { idx, item ->
                    val author = item.selectFirst("a[href*=/members/], .comment_author, .author").cleanText()
                    val body = item.selectFirst(".comment_text, .comment-text, .text, .message").cleanText()
                    if (author.isBlank() || body.isBlank()) return@mapIndexedNotNull null
                    val timestamp = item.selectFirst(".comment_date, .date, time").cleanText()
                    CommentItem(
                        id = item.attr("data-comment-id").ifBlank { "c-$idx" },
                        author = author,
                        body = body,
                        timestampLabel = timestamp,
                    )
                }.take(100)

            val downloadStreams = doc.select("a[href*=download=true]").mapNotNull { a ->
                val resolved = VideoSiteSupport.resolveAbsolute(baseUrl, a.attr("href"))
                if (resolved.isBlank()) return@mapNotNull null
                val label = downloadQualityRegex.find(a.attr("href"))?.groupValues?.get(1)?.let { "${it}p" }
                    ?: qualityTextRegex.find(a.text())?.value
                    ?: "原画"
                VideoStream(
                    quality = label,
                    label = label,
                    url = resolved,
                    isHls = false,
                    mimeTypeHint = "video/mp4",
                )
            }.distinctBy { it.label }.sortedByDescending { VideoSiteSupport.parseStreamHeight(it.label) }

            val title = doc.selectFirst("h1").cleanText()
                .ifBlank { HtmlUrlCleaner.cleanText(fv["video_title"].orEmpty()) }
                .ifBlank { "Video $id" }

            val item = MediaItem(
                siteId = SiteId.R34V,
                id = id,
                url = canonical,
                kind = MediaKind.VIDEO,
                title = title,
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, fv["preview_url"]),
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationLabel),
                author = authors.firstOrNull(),
                tags = tags,
            )

            return VideoDetail(
                item = item,
                description = description,
                streams = streams,
                downloadStreams = downloadStreams,
                tags = tags,
                authors = authors,
                characters = characters,
                categories = categories,
                comments = comments,
                related = related,
            )
        }

        fun parseStandardDirectory(html: String, segment: String, baseUrl: String = BASE_URL): List<DirectoryEntry> {
            val doc = Jsoup.parse(html, baseUrl)
            val root = doc.selectFirst(
                "#list_categories_categories_list_items, #list_tags_tags_list_items, #list_models_models_list_items, #list_channels_channels_list_items, .thumbs",
            ) ?: doc
            val segRegex = Regex("/${Regex.escape(segment)}/([^/?#]+)/?")
            val kind = when (segment) {
                "tags" -> SearchScope.TAG
                "models" -> SearchScope.AUTHOR
                else -> SearchScope.ALL
            }
            return root.select("a[href]").mapNotNull { a ->
                val slug = segRegex.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val title = listOf(
                    a.selectFirst(".thumb_title").cleanText(),
                    HtmlUrlCleaner.cleanText(a.attr("title")),
                    a.cleanText(),
                    slug.replace('-', ' '),
                ).first { it.isNotBlank() }
                val count = VideoSiteSupport.parseCount(
                    a.selectFirst(".videos, .count, .thumb_count")?.text(),
                )?.toInt()
                DirectoryEntry(
                    siteId = SiteId.R34V,
                    id = slug,
                    title = title,
                    url = VideoSiteSupport.resolveAbsolute(baseUrl, a.attr("href")),
                    kind = kind,
                    coverUrl = extractThumb(a.selectFirst("img"), baseUrl),
                    itemCount = count,
                )
            }.distinctBy { it.id }
        }

        fun parseWorksDirectory(html: String, baseUrl: String = BASE_URL): List<DirectoryEntry> {
            val doc = Jsoup.parse(html, baseUrl)
            return doc.select("a[href]").mapNotNull { a ->
                val slug = workLinkRegex.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val label = a.cleanText()
                if (label.isEmpty() || allCountRegex.containsMatchIn(label)) return@mapNotNull null
                DirectoryEntry(
                    siteId = SiteId.R34V,
                    id = slug,
                    title = label,
                    url = VideoSiteSupport.resolveAbsolute(baseUrl, a.attr("href")),
                    kind = SearchScope.CHARACTER,
                )
            }.distinctBy { it.id }
        }

        fun parseRankingDirectory(html: String, baseUrl: String = BASE_URL): List<DirectoryEntry> {
            val doc = Jsoup.parse(html, baseUrl)
            val rows = doc.select("li.cs-row")
            if (rows.isNotEmpty()) {
                return rows.mapNotNull { row ->
                    val a = row.selectFirst("a[href*=/tags/], a[href*=/characters/]") ?: return@mapNotNull null
                    val slug = a.attr("href").trimEnd('/').substringAfterLast('/')
                    if (slug.isBlank()) return@mapNotNull null
                    DirectoryEntry(
                        siteId = SiteId.R34V,
                        id = slug,
                        title = row.selectFirst(".cs-row__t").cleanText().ifBlank { a.cleanText() },
                        url = VideoSiteSupport.resolveAbsolute(baseUrl, a.attr("href")),
                        kind = SearchScope.CHARACTER,
                        subtitle = row.selectFirst(".cs-row__v").cleanText(),
                        coverUrl = extractThumb(row.selectFirst("img"), baseUrl),
                    )
                }.distinctBy { it.id }
            }
            val title = doc.select("h2.title").firstOrNull { it.text().equals("Top Artists", ignoreCase = true) }
            val root = title?.parent()?.parent()?.selectFirst(".aside_wrap") ?: return emptyList()
            return root.select("a.item[href*=/models/]").mapNotNull { a ->
                val slug = a.attr("href").trimEnd('/').substringAfterLast('/')
                if (slug.isBlank()) return@mapNotNull null
                DirectoryEntry(
                    siteId = SiteId.R34V,
                    id = slug,
                    title = a.selectFirst(".name, .title, .thumb_title").cleanText().ifBlank { a.cleanText() },
                    url = VideoSiteSupport.resolveAbsolute(baseUrl, a.attr("href")),
                    kind = SearchScope.AUTHOR,
                    subtitle = a.selectFirst(".rating").cleanText(),
                    coverUrl = extractThumb(a.selectFirst("img"), baseUrl),
                )
            }.distinctBy { it.id }
        }
    }
}
