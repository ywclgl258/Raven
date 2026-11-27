package com.raven.app.sites.video

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.CommentItem
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
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.JsExtractor
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.RequestSessionScope
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import com.raven.app.core.sitespi.SiteSessionStore
import com.raven.app.core.sitespi.VideoSource
import com.raven.app.core.sitespi.formatCookieHeader
import com.raven.app.core.sitespi.suspendRunCatching
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Hanime1 (`hanime1.me`) — Laravel SSR anime/hentai video site.
 * All browse, genre, tag, brand, year/month, and search routes funnel through `/search?...`.
 */
class HanimeSitePlugin(
    private val http: HttpClientAdapter,
    private val sessionStore: SiteSessionStore,
    private val rateLimitBackoffMs: Long = 1_000L,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed = coroutineScope {
            val recentRequest = FeedRequest(siteId = SiteId.HANIME1, section = "recent", sort = FeedSort.DEFAULT)
            val todayRequest = FeedRequest(siteId = SiteId.HANIME1, section = "today", sort = FeedSort.POPULAR)
            val recentDeferred = async { loadFeed(recentRequest) }
            val todayDeferred = async { suspendRunCatching { loadFeed(todayRequest) }.getOrNull() }
            val recentPage = recentDeferred.await()
            val todayPage = todayDeferred.await()
            VideoSiteSupport.buildHomeFeed(
                siteId = SiteId.HANIME1,
                primaryTitle = "最新上市",
                primaryPage = recentPage,
                primarySeeAll = recentRequest,
                rankedTitle = "本日排行",
                rankedPage = todayPage,
                rankedSeeAll = todayRequest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            VideoSiteSupport.validateFilters(request.advancedFilters, SiteId.HANIME1)
            // Account sections address /user/<uid>/..., and the uid only exists after sign-in.
            val url = resolveFeedUrl(request, accountUserId = sessionStore.getSecret(SiteId.HANIME1, HANIME_USER_ID_KEY))
            val (response, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.HANIME1,
                url = url,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            val doc = Jsoup.parse(html, url)
            val rawCards = parseCards(doc, BASE_URL)
            val filtered = VideoSiteSupport.applyLocalFilters(
                items = rawCards,
                filters = request.advancedFilters,
                filterDuration = true,
                filterRating = true,
                filterExcludeTags = true,
            )
            val current = request.page.coerceAtLeast(1)
            val lastPage = doc.select("ul.pagination a[href*=page]").mapNotNull { anchor ->
                pageParamRegex.find(anchor.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull()?.coerceAtLeast(current) ?: current
            val hasMore = current < lastPage
            val heading = HtmlUrlCleaner.cleanText(
                doc.selectFirst("h1, .horizontal-row-title h3")?.text().orEmpty(),
            ).ifBlank { null }
            return FeedPage(
                items = filtered,
                nextPage = if (hasMore) current + 1 else null,
                hasMore = hasMore,
                totalPages = lastPage,
                title = heading,
                fromCache = response.fromCache,
            )
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = when {
            !request.query.isNullOrBlank() -> SEARCH_SORTS
            request.section in setOf("today", "week", "month") -> listOf(FeedSort.POPULAR)
            request.section == "liked" -> listOf(FeedSort.LIKES)
            request.section.isNullOrBlank() || request.section == "recent" -> descriptor.feedSorts
            else -> listOf(FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.LIKES, FeedSort.POPULAR, FeedSort.DURATION)
        }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(
            SearchScope.ALL,
            SearchScope.TAG,
            SearchScope.AUTHOR,
        )
        override val supportedFilters: Set<AdvancedFilterField> = descriptor.supportedFilters
        override val supportsExcludeTags: Boolean = true
        override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> = SEARCH_SORTS
    }

    override val videoSource: VideoSource = object : VideoSource {
        override suspend fun loadVideoDetail(url: String): VideoDetail {
            val target = VideoSiteSupport.resolveAbsolute(BASE_URL, url)
                .ifBlank { throw SiteException.ParseError("无法识别 Hanime1 视频地址", siteId = SiteId.HANIME1) }
            val (_, html) = VideoSiteSupport.fetchHtml(
                http = http,
                siteId = SiteId.HANIME1,
                url = target,
                rateLimitBackoffMs = rateLimitBackoffMs,
            )
            return parseDetail(Jsoup.parse(html, target), target, BASE_URL)
        }
    }

    override val authProvider: AuthProvider = object : AuthProvider {
        /**
         * Hanime1 guards its login form with per-session Cloudflare / Laravel challenge tokens, so a
         * plain POST frequently fails with "站点可能要求浏览器验证". WebViewCookie mirrors the
         * site's own browser flow and is the primary path; `loginWithCredentials` stays available as
         * a fast path, and both write into the same session bucket below.
         */
        override val mode: AuthMode = AuthMode.WebViewCookie(
            loginUrl = "$BASE_URL/login",
            targetUrl = "$BASE_URL/",
            allowedHosts = setOf(
                "hanime1.me",
                "www.hanime1.me",
                "challenges.cloudflare.com",
            ),
            requiredCookies = setOf("cf_clearance"),
            optionalCookies = setOf("PHPSESSID", "__cf_bm", "laravel_session"),
        )

        override val supportsCredentialFallback: Boolean = true

        override suspend fun loginWithCredentials(username: String, password: String): AuthState {
            val trimmedEmail = username.trim()
            if (trimmedEmail.isBlank() || password.isEmpty()) {
                throw SiteException.AuthRequired("请填写邮箱和密码", siteId = SiteId.HANIME1)
            }
            // Candidate handshake runs fully ISOLATED: every hop carries only the cookies we have
            // collected ourselves, and no Set-Cookie reaches the vault. A failed or half-completed
            // login therefore cannot bump the auth epoch, evict the live session, or leave a
            // foreign account's cookies cached for the next PAGE/API request.
            val candidate = LinkedHashMap<String, String>()
            seedBotManagementCookies(candidate)

            val loginUrl = "$BASE_URL/login"
            val getResp = http.execute(
                SiteHttpRequest(
                    url = loginUrl,
                    siteId = SiteId.HANIME1,
                    kind = SiteRequestKind.PAGE,
                    headers = candidateHeaders(candidate),
                    allowCache = false,
                    allowRetry = false,
                    sessionScope = RequestSessionScope.ISOLATED,
                ),
            )
            val loginHtml = VideoSiteSupport.ensureSuccess(getResp, SiteId.HANIME1, checkChallenge = true)
            candidate.putAll(VideoSiteSupport.extractSetCookies(getResp))

            val token = Jsoup.parse(loginHtml)
                .selectFirst("input[name=_token]")
                ?.attr("value")
                ?.takeIf { it.isNotBlank() }
                ?: throw SiteException.ParseError("无法读取登录令牌，站点可能要求浏览器验证", siteId = SiteId.HANIME1)

            val postResp = http.postForm(
                url = loginUrl,
                fields = linkedMapOf(
                    "_token" to token,
                    "email" to trimmedEmail,
                    "password" to password,
                ),
                siteId = SiteId.HANIME1,
                headers = candidateHeaders(candidate),
                followRedirects = true,
                sessionScope = RequestSessionScope.ISOLATED,
            )
            if (postResp.code >= 400) {
                throw VideoSiteSupport.toSiteException(postResp, SiteId.HANIME1)
            }
            candidate.putAll(VideoSiteSupport.extractSetCookies(postResp))

            // Laravel convention on hanime1: an authenticated GET /login returns HTTP 404.
            val verifyResp = http.execute(
                SiteHttpRequest(
                    url = loginUrl,
                    siteId = SiteId.HANIME1,
                    kind = SiteRequestKind.PAGE,
                    headers = candidateHeaders(candidate),
                    allowCache = false,
                    allowRetry = false,
                    sessionScope = RequestSessionScope.ISOLATED,
                ),
            )
            if (verifyResp.code != 404) {
                if (verifyResp.code == 403 || verifyResp.code == 429 || verifyResp.code >= 500) {
                    throw VideoSiteSupport.toSiteException(verifyResp, SiteId.HANIME1)
                }
                throw SiteException.AuthRequired("邮箱或密码错误，或站点要求完成验证", siteId = SiteId.HANIME1)
            }
            candidate.putAll(VideoSiteSupport.extractSetCookies(verifyResp))

            // Verified. Persist ONLY the verified candidate set in one atomic snapshot, replacing any
            // cookies belonging to the previous account, and record identity in the same write.
            val userId = extractUserId(verifyResp.bodyText())
            sessionStore.storeSessionSnapshot(
                siteId = SiteId.HANIME1,
                cookies = candidate,
                secrets = buildMap<String, String?> {
                    put(SESSION_MARKER_KEY, "1")
                    put(HANIME_USER_ID_KEY, userId?.takeIf { it.isNotBlank() })
                    put(HANIME_ACCOUNT_EMAIL_KEY, trimmedEmail)
                },
                bumpEpoch = true,
            )
            sessionStore.saveUsername(SiteId.HANIME1, trimmedEmail)
            return AuthState(
                isAuthenticated = true,
                username = trimmedEmail,
                epoch = sessionStore.authEpoch(SiteId.HANIME1),
            )
        }

        /**
         * Bot-management cookies (`cf_clearance` / `__cf_bm`) are host-scoped anti-automation
         * tokens, not account identity, and are required to reach the login page at all. Nothing
         * account-identifying (`PHPSESSID`, `laravel_session`, `XSRF-TOKEN`) is ever replayed.
         */
        private fun seedBotManagementCookies(target: MutableMap<String, String>) {
            val stored = sessionStore.getCookies(SiteId.HANIME1)
            for (name in BOT_MANAGEMENT_COOKIES) {
                stored[name]?.takeIf { it.isNotBlank() }?.let { target[name] = it }
            }
        }

        private fun candidateHeaders(candidate: Map<String, String>): Map<String, String> =
            if (candidate.isEmpty()) emptyMap()
            else mapOf("Cookie" to formatCookieHeader(candidate))

        override suspend fun logout() {
            sessionStore.clearSite(SiteId.HANIME1)
        }

        /**
         * WebView / manual cookie import path. Verifies the imported session against the site
         * before persisting so an expired or invalid cookie set cannot masquerade as signed in.
         *
         * This is an *account identity* check, not a status-code check: hanime1 serves its
         * anonymous homepage with HTTP 200 too, so a bare "200 OK" proves nothing. A signed-in
         * page renders the header's `/user/<uid>` link; an anonymous page never does.
         */
        override suspend fun importCookies(cookies: Map<String, String>): AuthState {
            if (cookies.isEmpty() || !hasHanimeSessionCookie(cookies)) {
                throw SiteException.AuthRequired(
                    "未获取到有效的 Hanime1 会话 Cookie，请重新登录",
                    siteId = SiteId.HANIME1,
                )
            }
            // Candidate verification is ISOLATED: only these cookies are presented, and nothing the
            // probe returns may be merged into (or epoch-bump) the live session bucket.
            val verifyResp = http.execute(
                SiteHttpRequest(
                    url = "$BASE_URL/",
                    siteId = SiteId.HANIME1,
                    kind = SiteRequestKind.PAGE,
                    headers = candidateHeaders(cookies),
                    allowCookies = false,
                    allowCache = false,
                    allowRetry = false,
                    sessionScope = RequestSessionScope.ISOLATED,
                ),
            )
            if (verifyResp.code == 403 || verifyResp.code == 429 || verifyResp.code >= 500) {
                throw VideoSiteSupport.toSiteException(verifyResp, SiteId.HANIME1)
            }
            if (verifyResp.code != 200) {
                throw SiteException.AuthRequired("Hanime1 会话验证失败（HTTP ${verifyResp.code}）", siteId = SiteId.HANIME1)
            }
            val body = verifyResp.bodyText()
            if (VideoSiteSupport.isChallengeResponse(verifyResp, body)) {
                throw SiteException.Forbidden(
                    isChallenge = true,
                    message = "Hanime1 验证被浏览器挑战拦截，请稍后重试",
                    siteId = SiteId.HANIME1,
                )
            }
            val accountUserId = extractUserId(body)
                ?: throw SiteException.AuthRequired(
                    "Hanime1 返回的是匿名首页（HTTP 200），该 Cookie 未登录任何账号，请重新登录",
                    siteId = SiteId.HANIME1,
                )
            val previousUserId = sessionStore.getSecret(SiteId.HANIME1, HANIME_USER_ID_KEY)
            val sameAccount = previousUserId == null || previousUserId == accountUserId
            val previousEmail = if (sameAccount) {
                sessionStore.getSecret(SiteId.HANIME1, HANIME_ACCOUNT_EMAIL_KEY)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val resolvedUsername = previousEmail ?: "UID $accountUserId"
            sessionStore.storeSessionSnapshot(
                siteId = SiteId.HANIME1,
                cookies = cookies,
                secrets = buildMap<String, String?> {
                    put(SESSION_MARKER_KEY, "1")
                    put(HANIME_USER_ID_KEY, accountUserId)
                    put(HANIME_ACCOUNT_EMAIL_KEY, previousEmail)
                },
                bumpEpoch = true,
            )
            sessionStore.saveUsername(SiteId.HANIME1, resolvedUsername)
            return AuthState(
                isAuthenticated = true,
                username = resolvedUsername,
                epoch = sessionStore.authEpoch(SiteId.HANIME1),
            )
        }

        override suspend fun currentAuthState(): AuthState {
            // Signed-in state requires a verifiable account identity. Anonymous visitors also carry
            // PHPSESSID / cf_clearance, so the presence of a session cookie alone means nothing.
            val hasSessionCookie = hasHanimeSessionCookie(sessionStore.getCookies(SiteId.HANIME1))
            val userId = sessionStore.getSecret(SiteId.HANIME1, HANIME_USER_ID_KEY)?.takeIf { it.isNotBlank() }
            val accountEmail = sessionStore.getSecret(SiteId.HANIME1, HANIME_ACCOUNT_EMAIL_KEY)?.takeIf { it.isNotBlank() }
            val hasAccountIdentity = userId != null || accountEmail != null
            val hasMarker = sessionStore.getSecret(SiteId.HANIME1, SESSION_MARKER_KEY) == "1"
            val isAuthenticated = hasSessionCookie && hasAccountIdentity && hasMarker
            val resolvedUsername = if (isAuthenticated) {
                sessionStore.getUsername(SiteId.HANIME1)?.takeIf { it.isNotBlank() }
                    ?: accountEmail
                    ?: userId?.let { "UID $it" }
            } else {
                null
            }
            return AuthState(
                isAuthenticated = isAuthenticated,
                username = resolvedUsername,
                epoch = sessionStore.authEpoch(SiteId.HANIME1),
            )
        }

        private fun hasHanimeSessionCookie(cookies: Map<String, String>): Boolean =
            cookies["hanime1_session"]?.isNotBlank() == true ||
                cookies["PHPSESSID"]?.isNotBlank() == true ||
                cookies["laravel_session"]?.isNotBlank() == true ||
                cookies["cf_clearance"]?.isNotBlank() == true
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val host = UrlSanitizer.extractHost(url) ?: return false
            return descriptor.matchesHost(host)
        }

        override fun canonicalize(url: String): String? {
            if (!canHandle(url)) return null
            videoId(url)?.let { id -> return "$BASE_URL/watch?v=$id" }
            return UrlSanitizer.normalizeHttpsUrl(url)
        }

        override fun resolve(url: String): ResolvedLink? {
            if (!canHandle(url)) return null
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            videoId(url)?.let { id ->
                return ResolvedLink.Video(
                    siteId = SiteId.HANIME1,
                    id = id,
                    canonicalUrl = "$BASE_URL/watch?v=$id",
                )
            }
            val params = VideoSiteSupport.parseQueryParams(uri)
            val tags = params["tags[]"].orEmpty().filter { it.isNotBlank() }
            val genre = params["genre"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val query = params["query"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val brand = params["brands[]"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val year = params["year"]?.firstOrNull()?.let { yearExtractRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val month = params["month"]?.firstOrNull()?.let { monthExtractRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val page = params["page"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val sortParam = params["sort"]?.firstOrNull()
            val mappedSection = sortKeyMap.entries.firstOrNull { it.value == sortParam }?.key

            val filters = AdvancedSearchFilters(
                includeTags = if (query != null) tags else tags.drop(1),
                category = if (query != null || tags.isNotEmpty()) genre else null,
                brand = brand,
                year = year,
                month = month,
            )

            val resolvedSort = parseHanimeSort(sortParam)
            return when {
                query != null -> ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.HANIME1,
                        query = query,
                        scope = SearchScope.ALL,
                        sort = resolvedSort ?: FeedSort.RELEVANCE,
                        page = page,
                        advancedFilters = filters,
                    ),
                )
                tags.isNotEmpty() -> ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.HANIME1,
                        query = tags.first(),
                        scope = SearchScope.TAG,
                        sort = resolvedSort ?: FeedSort.DEFAULT,
                        page = page,
                        advancedFilters = filters,
                    ),
                )
                genre != null -> ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.HANIME1,
                        category = genre,
                        sort = resolvedSort ?: FeedSort.DEFAULT,
                        page = page,
                        advancedFilters = filters,
                    ),
                )
                mappedSection != null -> ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.HANIME1,
                        section = mappedSection,
                        page = page,
                        advancedFilters = filters,
                    ),
                )
                brand != null || year != null || month != null -> ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.HANIME1,
                        page = page,
                        advancedFilters = filters,
                    ),
                )
                else -> null
            }
        }
    }

    companion object {
        const val BASE_URL: String = "https://hanime1.me"
        private const val SESSION_MARKER_KEY = "hanime_session_ok"

        /** Numeric Hanime1 account id parsed from the header's `/user/<uid>` link. */
        private const val HANIME_USER_ID_KEY = "hanime_user_id"

        /** Email that owns the current session; lets a later candidate login replace, not merge. */
        private const val HANIME_ACCOUNT_EMAIL_KEY = "hanime_account_email"

        /**
         * Anti-automation cookies that must ride along on a candidate login handshake. They are
         * host/scraper tokens, never account identity, so replaying them is safe while `PHPSESSID`
         * and `laravel_session` never are.
         */
        private val BOT_MANAGEMENT_COOKIES: Set<String> = setOf("cf_clearance", "__cf_bm", "_cfuvid")

        private val USER_ID_REGEX = Regex("/user/(\\d+)")

        private val videoHrefRegex = Regex("[?&]v=(\\d+)")
        private val pageParamRegex = Regex("[?&]page=(\\d+)")
        private val viewsLabelRegex = Regex("觀看次數：([^\\s]+)")
        private val dateLabelRegex = Regex("(\\d{4}-\\d{2}-\\d{2})")
        private val sourceLiteralRegex = Regex("""const\s+source\s*=\s*(['"])([^'"]+)\1""")
        private val yearExtractRegex = Regex("(\\d{4})")
        private val monthExtractRegex = Regex("(\\d{1,2})")
        private val whitespaceRegex = Regex("\\s+")

        val sortKeyMap: Map<String, String> = mapOf(
            "recent" to "最新上市",
            "upload" to "最新上傳",
            "today" to "本日排行",
            "week" to "本週排行",
            "month" to "本月排行",
            "popular" to "觀看次數",
            "liked" to "讚好比例",
            "longest" to "時長最長",
            "watching" to "他們在看",
        )

        val genreKeys: Set<String> = setOf(
            "裏番",
            "泡麵番",
            "Motion Anime",
            "3DCG",
            "2.5D",
            "2D動畫",
            "AI生成",
            "MMD",
            "Cosplay",
            "新番預告",
        )

        val SEARCH_SORTS: List<FeedSort> = listOf(
            FeedSort.RELEVANCE,
            FeedSort.NEWEST,
            FeedSort.VIEWS,
            FeedSort.LIKES,
            FeedSort.POPULAR,
            FeedSort.DURATION,
        )

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.HANIME1,
            displayName = "Hanime1",
            shortName = "Hanime1",
            primaryKind = MediaKind.VIDEO,
            supportedKinds = setOf(MediaKind.VIDEO),
            defaultHost = "hanime1.me",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.ADVANCED_SEARCH,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.COMMENTS,
                SiteCapability.RELATED,
                SiteCapability.AUTH,
                SiteCapability.STREAM_QUALITIES,
            ),
            feedSorts = listOf(
                FeedSort.NEWEST,
                FeedSort.VIEWS,
                FeedSort.LIKES,
                FeedSort.POPULAR,
                FeedSort.DURATION,
            ),
            sections = listOf(
                SiteSectionSpec("recent", "最新上市", "刚上架的作品", defaultSort = FeedSort.DEFAULT),
                SiteSectionSpec("upload", "最新上傳", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec("today", "本日排行", defaultSort = FeedSort.POPULAR),
                SiteSectionSpec("week", "本週排行", defaultSort = FeedSort.POPULAR),
                SiteSectionSpec("month", "本月排行", defaultSort = FeedSort.POPULAR),
                SiteSectionSpec("popular", "觀看次數", defaultSort = FeedSort.VIEWS),
                SiteSectionSpec("liked", "讚好比例", defaultSort = FeedSort.LIKES),
                // Native account surfaces (require sign-in), mirroring Han1meViewer's MyList types.
                SiteSectionSpec("my_likes", "我喜欢的影片", subtitle = "需登录 Hanime1 账号"),
                SiteSectionSpec("my_saves", "稍后再看", subtitle = "需登录 Hanime1 账号"),
                SiteSectionSpec("my_subscriptions", "我的订阅", subtitle = "需登录 Hanime1 账号"),
                SiteSectionSpec("裏番", "裏番", category = "裏番"),
                SiteSectionSpec("泡麵番", "泡麵番", category = "泡麵番"),
                SiteSectionSpec("Motion Anime", "Motion Anime", category = "Motion Anime"),
                SiteSectionSpec("3DCG", "3DCG", category = "3DCG"),
                SiteSectionSpec("2.5D", "2.5D", category = "2.5D"),
                SiteSectionSpec("2D動畫", "2D動畫", category = "2D動畫"),
                SiteSectionSpec("AI生成", "AI生成", category = "AI生成"),
                SiteSectionSpec("MMD", "MMD", category = "MMD"),
                SiteSectionSpec("Cosplay", "Cosplay", category = "Cosplay"),
            ),
            searchScopes = listOf(
                SearchScope.ALL,
                SearchScope.TAG,
                SearchScope.AUTHOR,
            ),
            supportedFilters = setOf(
                AdvancedFilterField.INCLUDE_TAGS,
                AdvancedFilterField.EXCLUDE_TAGS,
                AdvancedFilterField.CATEGORY,
                AdvancedFilterField.BRAND,
                AdvancedFilterField.YEAR,
                AdvancedFilterField.MONTH,
                AdvancedFilterField.DURATION,
                AdvancedFilterField.RATING,
            ),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
            ),
        )

        fun videoId(url: String): String? = videoHrefRegex.find(url)?.groupValues?.get(1)

        fun resolveFeedUrl(
            request: FeedRequest,
            baseUrl: String = BASE_URL,
            accountUserId: String? = null,
        ): String {
            VideoSiteSupport.validateFilters(request.advancedFilters, SiteId.HANIME1)
            val params = LinkedHashMap<String, String>()
            val query = request.query?.trim().orEmpty()
            val category = request.category?.trim().orEmpty()
            val section = request.section?.trim().orEmpty()
            val tagValues = LinkedHashSet<String>()

            if (query.isNotEmpty()) {
                when (request.scope) {
                    SearchScope.TAG -> tagValues.add(query)
                    SearchScope.AUTHOR -> params["brands[]"] = query
                    SearchScope.ALL, SearchScope.CHARACTER -> params["query"] = query
                }
            }
            if (category.isNotEmpty()) {
                params["genre"] = category
            } else if (section in genreKeys) {
                params["genre"] = section
            } else if (section.isNotEmpty()) {
                sortKeyMap[section]?.let { params["sort"] = it }
            }

            val f = request.advancedFilters
            val filterCategory = f.category?.trim().orEmpty()
            if (filterCategory.isNotEmpty() && params["genre"] == null) {
                params["genre"] = filterCategory
            }
            val filterBrand = f.brand?.trim().orEmpty()
            if (filterBrand.isNotEmpty()) {
                params["brands[]"] = filterBrand
            }
            val filterYear = f.year
            if (filterYear != null && filterYear > 0) {
                params["year"] = "$filterYear 年"
            }
            val filterMonth = f.month
            if (filterMonth != null && filterMonth > 0) {
                params["month"] = "$filterMonth 月"
            }
            val minMinutes = f.minDurationMinutes ?: 0
            val maxMinutes = f.maxDurationMinutes ?: 0
            if (minMinutes > 0) {
                params["duration"] = "$minMinutes 分鐘 +"
            } else if (maxMinutes > 0) {
                params["duration"] = "0 - $maxMinutes 分鐘"
            }

            f.includeTags.map { it.trim() }.filter { it.isNotEmpty() }.forEach { tagValues.add(it) }

            val mappedSort = sortToHanime(request.sort)
            if (mappedSort.isNotEmpty() && (params["sort"] == null || section.isEmpty() || section == "recent")) {
                params["sort"] = mappedSort
            }
            if (params["genre"] == null && params["query"] == null && tagValues.isEmpty() && params["sort"] == null) {
                params["sort"] = "最新上市"
            }

            val page = request.page.coerceAtLeast(1)

            // Native account surfaces: /user/<uid>/likes | saves | SL (see Han1meViewer MyListType).
            val nativeAccountPath = when (section) {
                "my_likes" -> "likes"
                "my_saves" -> "saves"
                "my_subscriptions" -> "SL"
                else -> null
            }
            if (nativeAccountPath != null) {
                // Account pages live outside /search, so the caller supplies the resolved user id.
                val uid = accountUserId?.trim().orEmpty().ifBlank {
                    throw SiteException.AuthRequired(
                        "请先登录 Hanime1 账号以查看该分区", siteId = SiteId.HANIME1,
                    )
                }
                return buildString {
                    append(baseUrl.trimEnd('/'))
                    append("/user/").append(uid).append('/').append(nativeAccountPath)
                    if (page > 1) append("?page=").append(page)
                }
            }

            if (page > 1) {
                params["page"] = page.toString()
            }

            val pairs = buildList {
                params.forEach { (k, v) -> add(k to v) }
                tagValues.forEach { tag -> add("tags[]" to tag) }
            }
            val encodedQuery = pairs.joinToString("&") { (k, v) ->
                "${HtmlUrlCleaner.encodeQueryParam(k)}=${HtmlUrlCleaner.encodeQueryParam(v)}"
            }
            return "${baseUrl.trimEnd('/')}/search?$encodedQuery"
        }

        private fun sortToHanime(sort: FeedSort): String = when (sort) {
            FeedSort.NEWEST -> "最新上傳"
            FeedSort.VIEWS -> "觀看次數"
            FeedSort.LIKES, FeedSort.RATING -> "讚好比例"
            FeedSort.DURATION -> "時長最長"
            FeedSort.POPULAR, FeedSort.RANDOM, FeedSort.TRENDING -> "本日排行"
            FeedSort.RELEVANCE, FeedSort.DEFAULT, FeedSort.OLDEST -> ""
        }

        private fun parseHanimeSort(raw: String?): FeedSort? = when (raw) {
            "最新上市", "最新上傳" -> FeedSort.NEWEST
            "觀看次數" -> FeedSort.VIEWS
            "讚好比例" -> FeedSort.LIKES
            "時長最長" -> FeedSort.DURATION
            "本日排行", "本週排行", "本月排行" -> FeedSort.POPULAR
            else -> null
        }

        /**
         * Hanime1 renders its signed-in header with an `/user/<uid>` link; that numeric id is what
         * the native `/user/<uid>/likes|saves|SL` sections need. Returns null on anonymous pages.
         */
        fun extractUserId(html: String): String? =
            USER_ID_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        fun persistAccountUserId(store: com.raven.app.core.sitespi.SiteSessionStore, userId: String?) {
            if (userId.isNullOrBlank()) return
            store.saveSecret(SiteId.HANIME1, HANIME_USER_ID_KEY, userId)
        }

        fun parseCards(doc: Document, baseUrl: String = BASE_URL): List<MediaItem> =
            doc.select("div.video-item-container")
                .mapNotNull { parseCard(it, baseUrl) }
                .distinctBy { it.id }

        private fun parseCard(card: Element, baseUrl: String): MediaItem? {
            // Filter out ad containers by requiring a `watch?v=` link.
            val link = card.selectFirst("a.video-link[href*=watch]") ?: return null
            val rawHref = link.attr("href").ifBlank { link.absUrl("href") }
            val id = videoId(rawHref) ?: return null
            val title = HtmlUrlCleaner.cleanText(
                card.selectFirst(".title")?.text().orEmpty().ifBlank { card.attr("title") },
            )
            if (title.isBlank()) return null
            val thumbEl = card.selectFirst("img.main-thumb, img")
            val thumbRaw = listOf(
                thumbEl?.attr("src"),
                thumbEl?.attr("data-src"),
            ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", ignoreCase = true) }
            val stats = card.select(".stats-container .stat-item")
            val ratingLabel = HtmlUrlCleaner.cleanText(stats.getOrNull(0)?.text().orEmpty())
            val viewsLabel = HtmlUrlCleaner.cleanText(stats.getOrNull(1)?.text().orEmpty())
            val durationLabel = HtmlUrlCleaner.cleanText(card.selectFirst(".duration")?.text().orEmpty())
            val url = if (rawHref.startsWith("http://") || rawHref.startsWith("https://")) {
                rawHref
            } else {
                "${baseUrl.trimEnd('/')}/watch?v=$id"
            }
            return MediaItem(
                siteId = SiteId.HANIME1,
                id = id,
                url = url,
                kind = MediaKind.VIDEO,
                title = title,
                coverUrl = VideoSiteSupport.resolveAbsolute(baseUrl, thumbRaw),
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationLabel),
                views = VideoSiteSupport.parseCount(viewsLabel),
                rating = VideoSiteSupport.parseRatingStars(ratingLabel),
                extra = buildMap {
                    if (ratingLabel.isNotBlank()) put("ratingLabel", ratingLabel)
                },
            )
        }

        fun parseDetail(doc: Document, fallbackUrl: String, baseUrl: String = BASE_URL): VideoDetail {
            val id = videoId(fallbackUrl)
                ?: throw SiteException.ParseError("详情页无法识别：缺少视频 ID", siteId = SiteId.HANIME1)

            val sourceTags = doc.select("video#player source[src]")
            val streams = sourceTags.mapNotNull { src ->
                val rawUrl = src.attr("src").ifBlank { src.absUrl("src") }.trim()
                val resolved = VideoSiteSupport.resolveAbsolute(baseUrl, rawUrl)
                if (!resolved.startsWith("http")) return@mapNotNull null
                val size = src.attr("size").trim()
                val type = src.attr("type").trim()
                val isHls = resolved.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                    type.contains("mpegurl", ignoreCase = true)
                // `size` may be 1080, 1920x1080, or the 2K/4K aliases; fall back to the file name so
                // 2K sources without an attribute keep their own tier instead of colliding on "原画".
                val height = VideoSiteSupport.parseStreamHeight(size)
                    .takeIf { it > 0 }
                    ?: VideoSiteSupport.parseStreamHeight(resolved.substringAfterLast('/'))
                val label = when {
                    height > 0 -> "${height}p"
                    isHls -> "自动"
                    else -> "原画"
                }
                VideoStream(
                    quality = label,
                    label = label,
                    url = resolved,
                    isHls = isHls,
                    mimeTypeHint = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4",
                )
            }.distinctBy { it.url }.let { parsed ->
                // Two sources at the same tier (e.g. 1080p HLS + 1080p MP4) must stay selectable.
                val used = mutableSetOf<String>()
                parsed.map { stream ->
                    if (used.add(stream.label)) {
                        stream
                    } else {
                        val suffix = if (stream.isHls) " · HLS" else " · MP4"
                        val label = "${stream.label}$suffix"
                        stream.copy(quality = label, label = label)
                    }
                }
            }.ifEmpty {
                val html = doc.outerHtml()
                val scriptSource = JsExtractor.extractJsStringVar(html, "source")
                    ?: sourceLiteralRegex.find(html)?.groupValues?.get(2)?.let { JsExtractor.unescapeJsString(it) }
                if (!scriptSource.isNullOrBlank() && scriptSource.startsWith("http")) {
                    val isHls = scriptSource.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                    listOf(
                        VideoStream(
                            quality = if (isHls) "auto" else "原画",
                            label = if (isHls) "自动" else "原画",
                            url = scriptSource,
                            isHls = isHls,
                            mimeTypeHint = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4",
                        ),
                    )
                } else {
                    emptyList()
                }
            }.distinctBy { it.label }.sortedByDescending { VideoSiteSupport.parseStreamHeight(it.label) }

            if (streams.isEmpty()) {
                throw SiteException.ParseError("详情页没有播放地址，可能需要登录或站点已改版", siteId = SiteId.HANIME1)
            }

            val heading = HtmlUrlCleaner.cleanText(
                doc.getElementById("shareBtn-title")?.text().orEmpty()
                    .ifBlank { doc.selectFirst("h1")?.text().orEmpty() },
            )
            val poster = VideoSiteSupport.resolveAbsolute(
                baseUrl,
                doc.selectFirst("video#player")?.attr("poster")
                    ?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content"),
            )
            val metaText = doc.select("div.video-details-wrapper")
                .firstOrNull { it.text().contains("觀看次數") }
                ?.text()
                .orEmpty()
            val viewsText = viewsLabelRegex.find(metaText)?.groupValues?.get(1).orEmpty()
            val dateText = dateLabelRegex.find(metaText)?.groupValues?.get(1).orEmpty()

            val tags = doc.select("div.single-video-tag > a")
                .map { HtmlUrlCleaner.cleanText(it.text()) }
                .filter { it.isNotBlank() }
                .distinct()
                .map { tag ->
                    TagRef(
                        slug = slugify(tag),
                        label = tag,
                        url = "${baseUrl.trimEnd('/')}/search?tags[]=${HtmlUrlCleaner.encodeQueryParam(tag)}",
                    )
                }

            val artistEl = doc.getElementById("video-artist-name")
            val artistName = HtmlUrlCleaner.cleanText(artistEl?.text().orEmpty())
            val authors = if (artistName.isNotBlank()) {
                val artistUrl = VideoSiteSupport.resolveAbsolute(baseUrl, artistEl?.attr("href"))
                    .ifBlank { "${baseUrl.trimEnd('/')}/search?query=${HtmlUrlCleaner.encodeQueryParam(artistName)}" }
                listOf(AuthorRef(id = slugify(artistName), name = artistName, role = "brand", url = artistUrl))
            } else {
                emptyList()
            }

            val captionEl = doc.selectFirst("div[class^=video-caption-text]")
            val description = HtmlUrlCleaner.cleanText(
                captionEl?.ownText().orEmpty().ifBlank { captionEl?.text().orEmpty() },
            )

            val related = doc.getElementById("related-tabcontent")
                ?.select("div.video-item-container")
                .orEmpty()
                .mapNotNull { parseCard(it, baseUrl) }
                .filter { it.id != id }
                .distinctBy { it.id }

            val comments = parseComments(doc)
            val durationRaw = doc.selectFirst("meta[property=og:video:duration]")?.attr("content").orEmpty()

            val item = MediaItem(
                siteId = SiteId.HANIME1,
                id = id,
                url = fallbackUrl,
                kind = MediaKind.VIDEO,
                title = heading.ifBlank { "视频 $id" },
                coverUrl = poster,
                durationSeconds = VideoSiteSupport.parseDurationSeconds(durationRaw),
                views = VideoSiteSupport.parseCount(viewsText),
                dateLabel = dateText.ifBlank { null },
                author = authors.firstOrNull(),
                tags = tags,
            )

            return VideoDetail(
                item = item,
                description = description,
                streams = streams,
                tags = tags,
                authors = authors,
                comments = comments,
                related = related,
            )
        }

        private fun parseComments(doc: Document): List<CommentItem> {
            val commentEls = doc.select(
                "#comment-tabcontent .comment-item, #comment-tabcontent .video-comment, .comment-index-text, .comment-list .comment",
            )
            return commentEls.mapIndexedNotNull { idx, el ->
                val author = HtmlUrlCleaner.cleanText(
                    el.selectFirst(".comment-author, .author, .username, a[href*=channel]")?.text().orEmpty(),
                )
                val body = HtmlUrlCleaner.cleanText(
                    el.selectFirst(".comment-body, .comment-text, .content, p")?.text().orEmpty(),
                )
                if (body.isBlank()) return@mapIndexedNotNull null
                val time = HtmlUrlCleaner.cleanText(
                    el.selectFirst(".comment-date, .date, time, .timestamp")?.text().orEmpty(),
                )
                CommentItem(
                    id = el.attr("data-comment-id").ifBlank { "hc-$idx" },
                    author = author.ifBlank { "用户" },
                    body = body,
                    timestampLabel = time,
                )
            }.take(100)
        }

        private fun slugify(text: String): String =
            text.lowercase(Locale.ROOT).replace(whitespaceRegex, "-")
    }
}
