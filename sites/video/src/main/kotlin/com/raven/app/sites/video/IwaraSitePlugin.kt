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
import com.raven.app.core.model.VideoDetail
import com.raven.app.core.model.VideoStream
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.AuthState
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.LruCache
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteSessionStore
import com.raven.app.core.sitespi.VideoSource
import com.raven.app.core.sitespi.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Iwara (`www.iwara.tv` / `apiq.iwara.tv`) — Dual-Media Video + Image Gallery REST plugin.
 *
 * Preserves all non-negotiable invariants:
 * - 0-based REST pagination (`page = request.page - 1`, `limit = 20`) with dynamic page count recalculation.
 * - Search always uses plural `type=videos` or `type=images` (never singular).
 * - `X-Version = SHA-1("${fileId}_${expires}_${salt}")` with live `/main.*.js` salt refresh fallback.
 * - `Origin`/`x-site` headers on Iwara hosts and `Authorization: Bearer <jwt>` STRICTLY on `apiHosts`.
 * - `authEpoch` tracking for subscription feeds and login/logout race safety.
 * - `GallerySource` for `/image/<id>` galleries (filtering out video attachments) backed by `LruCache`.
 */
class IwaraSitePlugin(
    private val http: HttpClientAdapter,
    private val sessionStore: SiteSessionStore,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val rateLimitBackoffMs: Long = 1_000L,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val galleryCache = LruCache<String, JsonObject>(maxEntries = 16)
    private val saltMutex = Mutex()
    @Volatile
    private var currentSalt: String = DEFAULT_SALT
    private var lastSaltCheckMs: Long = Long.MIN_VALUE / 2

    override val authProvider: AuthProvider = object : AuthProvider {
        override val mode: AuthMode = AuthMode.JwtBearer

        override suspend fun loginWithCredentials(username: String, password: String): AuthState {
            val trimmed = username.trim()
            if (trimmed.isBlank() || password.isEmpty()) {
                throw SiteException.AuthRequired("请填写用户名和密码", siteId = SiteId.IWARA)
            }
            val expectedEpoch = sessionStore.authEpoch(SiteId.IWARA)
            val payload = buildJsonObject {
                put("user", trimmed)
                put("password", password)
            }.toString()
            val response = http.postJson(
                url = "$API_BASE/auth/login",
                jsonBody = payload,
                siteId = SiteId.IWARA,
                headers = mapOf(
                    "Origin" to BASE_URL,
                    "x-site" to "www.iwara.tv",
                ),
                followRedirects = false,
            )
            if (response.code == 401) {
                throw SiteException.AuthRequired("用户名或密码不正确", siteId = SiteId.IWARA)
            }
            val body = VideoSiteSupport.ensureSuccess(response, SiteId.IWARA, checkChallenge = false)
            val root = parseJsonObject(body)
            val token = root.str("token").takeIf { it.isNotBlank() }
                ?: throw SiteException.ParseError("登录响应缺少令牌，请稍后重试或更新应用", siteId = SiteId.IWARA)
            if (sessionStore.authEpoch(SiteId.IWARA) != expectedEpoch) {
                throw SiteException.Cancelled("Iwara 会话已切换", siteId = SiteId.IWARA)
            }
            sessionStore.saveBearerToken(SiteId.IWARA, token, trimmed)
            return AuthState(
                isAuthenticated = true,
                username = trimmed,
                epoch = sessionStore.authEpoch(SiteId.IWARA),
            )
        }

        override suspend fun logout() {
            sessionStore.clearSite(SiteId.IWARA)
        }

        override suspend fun currentAuthState(): AuthState {
            val hasToken = !sessionStore.getBearerToken(SiteId.IWARA).isNullOrBlank()
            return AuthState(
                isAuthenticated = hasToken,
                username = if (hasToken) sessionStore.getUsername(SiteId.IWARA) else null,
                epoch = sessionStore.authEpoch(SiteId.IWARA),
            )
        }

        override fun decorateRequestHeaders(request: SiteHttpRequest, host: String): Map<String, String> {
            val normalizedHost = host.trim().lowercase()
            if (request.authEpoch != null && request.authEpoch != sessionStore.authEpoch(SiteId.IWARA)) {
                throw SiteException.Cancelled("Iwara 会话已切换", siteId = SiteId.IWARA)
            }
            val headers = LinkedHashMap<String, String>()
            if (descriptor.matchesMediaHost(normalizedHost)) {
                headers["Origin"] = BASE_URL
                headers["x-site"] = "www.iwara.tv"
            }
            // Strict credential isolation: Bearer JWT is ONLY sent to apiHosts (never CDN or web hosts).
            if (normalizedHost in API_HOSTS) {
                sessionStore.getBearerToken(SiteId.IWARA)?.takeIf { it.isNotBlank() }?.let { token ->
                    headers["Authorization"] = "Bearer $token"
                }
            }
            return headers
        }
    }

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed = coroutineScope {
            val latestRequest = FeedRequest(siteId = SiteId.IWARA, section = "latest", sort = FeedSort.NEWEST, mediaKind = MediaKind.VIDEO)
            val trendingRequest = FeedRequest(siteId = SiteId.IWARA, section = "trending", sort = FeedSort.TRENDING, mediaKind = MediaKind.VIDEO)
            val latestDeferred = async { loadFeed(latestRequest) }
            val trendingDeferred = async { suspendRunCatching { loadFeed(trendingRequest) }.getOrNull() }
            val latestPage = latestDeferred.await()
            val trendingPage = trendingDeferred.await()
            VideoSiteSupport.buildHomeFeed(
                siteId = SiteId.IWARA,
                primaryTitle = "最新上传",
                primaryPage = latestPage,
                primarySeeAll = latestRequest,
                rankedTitle = "趋势榜",
                rankedPage = trendingPage,
                rankedSeeAll = trendingRequest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            VideoSiteSupport.validateFilters(request.advancedFilters, SiteId.IWARA)
            if (request.page <= 0) {
                throw SiteException.Unsupported("页码必须大于零", siteId = SiteId.IWARA)
            }
            val isSubscriptions = request.section == "subscriptions"
            val isGallery = isGalleryRequest(request)
            var resolvedRequest = request

            val rawQuery = request.query?.trim().orEmpty()
            if (request.scope == SearchScope.AUTHOR && rawQuery.isNotEmpty() && !isUserId(rawQuery)) {
                val profileUrl = "$API_BASE/profile/${HtmlUrlCleaner.encodePathSegment(rawQuery)}"
                val profileObj = requireJsonObject(fetchApiJson(profileUrl))
                val resolvedUserId = profileObj.obj("user")?.str("id").orEmpty()
                if (resolvedUserId.isBlank()) {
                    throw SiteException.NotFound("没有找到该作者，请输入准确的用户名", siteId = SiteId.IWARA)
                }
                resolvedRequest = request.copy(query = resolvedUserId)
            }

            val url = resolveFeedUrl(resolvedRequest)
            val (fromCache, data) = if (isSubscriptions) {
                val epoch = sessionStore.authEpoch(SiteId.IWARA)
                if (sessionStore.getBearerToken(SiteId.IWARA).isNullOrBlank()) {
                    throw SiteException.AuthRequired("请先在设置中登录 Iwara 账号，关注动态需要登录", siteId = SiteId.IWARA)
                }
                val result = try {
                    fetchApiJsonWithCacheFlag(url = url, allowCache = false, authEpoch = epoch)
                } catch (e: SiteException.AuthRequired) {
                    throw SiteException.AuthRequired("请先在设置中登录 Iwara 账号，关注动态需要登录", siteId = SiteId.IWARA, cause = e)
                }
                if (sessionStore.authEpoch(SiteId.IWARA) != epoch) {
                    throw SiteException.Cancelled("Iwara 会话已切换", siteId = SiteId.IWARA)
                }
                result
            } else {
                fetchApiJsonWithCacheFlag(url = url, allowCache = true, authEpoch = null)
            }

            val page = if (isGallery) {
                parseImageFeed(data, resolvedRequest, fromCache = fromCache)
            } else {
                parseVideoFeed(data, resolvedRequest, fromCache = fromCache)
            }
            if (!isGallery && !request.advancedFilters.isEmpty) {
                val filtered = VideoSiteSupport.applyLocalFilters(
                    items = page.items,
                    filters = request.advancedFilters,
                    filterDuration = true,
                    filterRating = false,
                    filterExcludeTags = true,
                )
                return page.copy(items = filtered)
            }
            if (isGallery && request.advancedFilters.excludeTags.isNotEmpty()) {
                val filtered = VideoSiteSupport.applyLocalFilters(
                    items = page.items,
                    filters = request.advancedFilters,
                    filterDuration = false,
                    filterRating = false,
                    filterExcludeTags = true,
                )
                return page.copy(items = filtered)
            }
            return page
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = when (request.section) {
            "popular" -> listOf(FeedSort.POPULAR)
            "liked", "image-likes" -> listOf(FeedSort.LIKES)
            "subscriptions" -> listOf(FeedSort.NEWEST)
            else -> {
                val isSearch = request.scope == SearchScope.ALL && !request.query.isNullOrBlank()
                descriptor.feedSorts.filter { isSearch || it != FeedSort.RELEVANCE }
            }
        }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(
            SearchScope.ALL,
            SearchScope.TAG,
            SearchScope.AUTHOR,
        )
        override val supportedFilters: Set<AdvancedFilterField> = setOf(
            AdvancedFilterField.INCLUDE_TAGS,
            AdvancedFilterField.DURATION,
        )
        override val supportsExcludeTags: Boolean = false
        override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> =
            if (scope == SearchScope.ALL) {
                descriptor.feedSorts
            } else {
                descriptor.feedSorts.filterNot { it == FeedSort.RELEVANCE }
            }
    }

    override val videoSource: VideoSource = object : VideoSource {
        override suspend fun loadVideoDetail(url: String): VideoDetail = coroutineScope {
            val id = extractVideoId(url)
                ?: throw SiteException.ParseError("无法识别 Iwara 视频地址", siteId = SiteId.IWARA)
            val endpoint = "$API_BASE/video/$id"
            val metadata = requireJsonObject(fetchApiJson(endpoint))
            if (metadata.str("embedUrl").isNotBlank()) {
                throw SiteException.Unsupported("该视频为外站嵌入，暂不支持在应用内播放", siteId = SiteId.IWARA)
            }
            val fileUrl = metadata.str("fileUrl")
            if (fileUrl.isBlank()) {
                if (metadata.bool("private") == true) {
                    throw SiteException.AuthRequired("该视频需要登录或作者授权，当前仅支持公开视频", siteId = SiteId.IWARA)
                }
                throw SiteException.NotFound("视频没有可用片源，可能尚在转码或已被移除", siteId = SiteId.IWARA)
            }

            val relatedDeferred = async {
                optionalArray { requireJsonObject(fetchApiJson("$endpoint/related")).arr("results") }
            }
            val commentsDeferred = async {
                optionalArray { requireJsonObject(fetchApiJson("$endpoint/comments?limit=12&page=0")).arr("results") }
            }
            val sources = fetchSignedSources(fileUrl)
            parseVideoDetail(
                metadata = metadata,
                sources = sources,
                related = relatedDeferred.await(),
                comments = commentsDeferred.await(),
            )
        }
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val id = extractImageId(url)
                ?: throw SiteException.ParseError("无法识别 Iwara 图库地址", siteId = SiteId.IWARA)
            val data = fetchGalleryMetadata(id, refresh = false)
            val detail = parseGalleryDetail(data)
            if (detail.pageCount <= 0) {
                throw SiteException.ParseError("该图库没有可阅读的图片文件", siteId = SiteId.IWARA)
            }
            return detail
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            val data = fetchGalleryMetadata(detail.item.id, refresh = forceReload)
            val (pages, _) = parseGalleryFiles(data)
            return pages.getOrNull(pageIndex - 1)
                ?: throw SiteException.NotFound("Iwara 图库没有第 $pageIndex 张可读图片", siteId = SiteId.IWARA)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val id = extractImageId(pageUrl)
                ?: throw SiteException.ParseError("无法识别 Iwara 图库页地址", siteId = SiteId.IWARA)
            val uri = UrlSanitizer.parseUriOrNull(pageUrl)
                ?: throw SiteException.ParseError("无法解析 Iwara 图库页地址", siteId = SiteId.IWARA)
            val page = uri.rawFragment?.removePrefix("page=")?.toIntOrNull()
                ?: VideoSiteSupport.queryParam(uri, "page")?.toIntOrNull()
                ?: throw SiteException.ParseError("Iwara 图库页码缺失", siteId = SiteId.IWARA)
            val reload = forceReload || VideoSiteSupport.queryParam(uri, "reload") == "1"
            val data = fetchGalleryMetadata(id, refresh = reload)
            val (pages, _) = parseGalleryFiles(data)
            return pages.getOrNull(page - 1)
                ?: throw SiteException.NotFound("Iwara 图库没有第 $page 张可读图片", siteId = SiteId.IWARA)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val host = UrlSanitizer.extractHost(url) ?: return false
            return descriptor.matchesRequestHost(host)
        }

        override fun canonicalize(url: String): String? {
            if (!canHandle(url)) return null
            extractVideoId(url)?.let { return "$BASE_URL/video/$it" }
            extractImageId(url)?.let { return "$BASE_URL/image/$it" }
            return UrlSanitizer.normalizeHttpsUrl(url)
        }

        override fun resolve(url: String): ResolvedLink? {
            if (!canHandle(url)) return null
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val host = uri.host?.lowercase() ?: return null
            val isApi = host in API_HOSTS
            if (isApi) {
                val scheme = uri.scheme?.lowercase()
                if (scheme != "https") return null
                if (uri.port > 0 && uri.port != 443) return null
                if (!uri.rawUserInfo.isNullOrEmpty()) return null
            }

            val segments = VideoSiteSupport.pathSegments(uri)
            if (segments.isEmpty()) return null

            when (segments[0].lowercase()) {
                "video" -> {
                    val id = segments.getOrNull(1)?.takeIf { idRegex.matches(it) } ?: return null
                    return ResolvedLink.Video(SiteId.IWARA, id, "$BASE_URL/video/$id")
                }
                "image" -> {
                    val id = segments.getOrNull(1)?.takeIf { idRegex.matches(it) } ?: return null
                    val pageIdx = uri.rawFragment?.removePrefix("page=")?.toIntOrNull()
                        ?: VideoSiteSupport.queryParam(uri, "page")?.toIntOrNull()
                    return ResolvedLink.Gallery(SiteId.IWARA, id, "$BASE_URL/image/$id", pageIdx)
                }
                "profile", "user" -> {
                    if (isApi) return null
                    val user = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                    return ResolvedLink.Feed(
                        FeedRequest(siteId = SiteId.IWARA, query = user, scope = SearchScope.AUTHOR),
                    )
                }
                "search" -> {
                    val params = VideoSiteSupport.parseQueryParams(uri)
                    val q = params["query"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
                    val type = params["type"]?.firstOrNull()?.lowercase()
                    val kind = if (type == "images") MediaKind.PHOTO else MediaKind.VIDEO
                    val page = if (isApi) {
                        params["page"]?.firstOrNull()?.toIntOrNull()?.takeIf { it in 0 until Int.MAX_VALUE }?.plus(1) ?: 1
                    } else {
                        params["page"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    }
                    return ResolvedLink.Feed(
                        FeedRequest(
                            siteId = SiteId.IWARA,
                            query = q,
                            scope = SearchScope.ALL,
                            sort = parseApiSort(params["sort"]?.firstOrNull()),
                            page = page,
                            mediaKind = kind,
                        ),
                    )
                }
                "videos", "images" -> {
                    if (segments.size > 1) return null
                    val mediaKind = if (segments[0].equals("images", ignoreCase = true)) MediaKind.PHOTO else MediaKind.VIDEO
                    val params = VideoSiteSupport.parseQueryParams(uri)
                    val page = if (isApi) {
                        val rawPage = params["page"]?.firstOrNull()
                        if (rawPage == null) {
                            1
                        } else {
                            val idx = rawPage.toIntOrNull() ?: return null
                            if (idx !in 0 until Int.MAX_VALUE) return null
                            idx + 1
                        }
                    } else {
                        params["page"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    }
                    val sort = parseApiSort(params["sort"]?.firstOrNull())
                    val tags = params["tags"]?.firstOrNull().orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val user = params["user"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    if (user != null) {
                        return ResolvedLink.Feed(
                            FeedRequest(
                                siteId = SiteId.IWARA,
                                query = user,
                                scope = SearchScope.AUTHOR,
                                sort = sort,
                                page = page,
                                mediaKind = mediaKind,
                                advancedFilters = AdvancedSearchFilters(includeTags = tags),
                            ),
                        )
                    }
                    if (tags.isNotEmpty()) {
                        return ResolvedLink.Feed(
                            FeedRequest(
                                siteId = SiteId.IWARA,
                                query = tags.first(),
                                scope = SearchScope.TAG,
                                sort = sort,
                                page = page,
                                mediaKind = mediaKind,
                                advancedFilters = AdvancedSearchFilters(includeTags = tags.drop(1)),
                            ),
                        )
                    }
                    return ResolvedLink.Feed(
                        FeedRequest(
                            siteId = SiteId.IWARA,
                            section = if (mediaKind == MediaKind.PHOTO) "images" else "latest",
                            sort = sort,
                            page = page,
                            mediaKind = mediaKind,
                        ),
                    )
                }
                else -> return null
            }
        }
    }

    private suspend fun fetchGalleryMetadata(id: String, refresh: Boolean): JsonObject {
        if (!refresh) {
            galleryCache.get(id)?.let { return it }
        }
        val endpoint = "$API_BASE/image/$id"
        val obj = requireJsonObject(fetchApiJson(endpoint))
        galleryCache.put(id, obj)
        return obj
    }

    private suspend fun fetchApiJson(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        allowCache: Boolean = true,
        authEpoch: Long? = null,
    ): JsonElement = fetchApiJsonWithCacheFlag(url, extraHeaders, allowCache, authEpoch).second

    private suspend fun fetchApiJsonWithCacheFlag(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        allowCache: Boolean = true,
        authEpoch: Long? = null,
    ): Pair<Boolean, JsonElement> {
        val host = UrlSanitizer.extractHost(url).orEmpty()
        val probeReq = SiteHttpRequest(
            url = url,
            siteId = SiteId.IWARA,
            allowCache = allowCache,
            authEpoch = authEpoch,
        )
        val decorated = authProvider.decorateRequestHeaders(probeReq, host)
        val mergedHeaders = LinkedHashMap<String, String>(decorated.size + extraHeaders.size)
        mergedHeaders.putAll(decorated)
        mergedHeaders.putAll(extraHeaders)
        val (response, text) = VideoSiteSupport.fetchApiText(
            http = http,
            siteId = SiteId.IWARA,
            url = url,
            headers = mergedHeaders,
            allowCache = allowCache,
            authEpoch = authEpoch,
            rateLimitBackoffMs = rateLimitBackoffMs,
        )
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw SiteException.ParseError("站点返回了非 JSON 数据，请稍后重试或调整网络", siteId = SiteId.IWARA, cause = e)
        }
        if (element !is JsonObject && element !is JsonArray) {
            throw SiteException.ParseError("站点返回了非 JSON 数据，请稍后重试或调整网络", siteId = SiteId.IWARA)
        }
        return response.fromCache to element
    }

    private suspend fun optionalArray(load: suspend () -> JsonArray): JsonArray = try {
        withTimeoutOrNull(1_200L) { load() } ?: JsonArray(emptyList())
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        JsonArray(emptyList())
    }

    /**
     * Fetches signed video source list from `files.iwara.tv` / `filesq.iwara.tv`.
     * If `Source` is missing, hot-extracts the frontend salt from `www.iwara.tv`'s `/main.*.js`
     * (10-minute cooldown, 5s timeout) and only persists the new salt when retry returns `Source`.
     */
    private suspend fun fetchSignedSources(fileUrl: String): JsonArray {
        val usedSalt = currentSalt
        val firstHeaders = buildSignatureHeaders(fileUrl, usedSalt)
        val first = requireJsonArray(fetchApiJson(fileUrl, extraHeaders = firstHeaders, allowCache = false))
        if (first.isEmpty() || hasSourceStream(first)) return first

        val replacement = saltMutex.withLock {
            if (currentSalt != usedSalt) return@withLock currentSalt
            val now = clockMs()
            if (now - lastSaltCheckMs < SALT_REFRESH_COOLDOWN_MS) return@withLock null
            lastSaltCheckMs = now
            try {
                withTimeoutOrNull(5_000L) {
                    val (_, homeHtml) = VideoSiteSupport.fetchHtml(
                        http = http,
                        siteId = SiteId.IWARA,
                        url = BASE_URL,
                        allowCache = false,
                        rateLimitBackoffMs = rateLimitBackoffMs,
                    )
                    val scriptPath = extractMainScriptPath(homeHtml) ?: return@withTimeoutOrNull null
                    val scriptUrl = VideoSiteSupport.resolveAbsolute(BASE_URL, scriptPath)
                    if (scriptUrl.isBlank()) return@withTimeoutOrNull null
                    val (_, scriptText) = VideoSiteSupport.fetchApiText(
                        http = http,
                        siteId = SiteId.IWARA,
                        url = scriptUrl,
                        allowCache = false,
                        rateLimitBackoffMs = rateLimitBackoffMs,
                    )
                    extractSaltFromScript(scriptText)?.takeIf { it != usedSalt }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
        } ?: return first

        val retried = try {
            requireJsonArray(
                fetchApiJson(
                    fileUrl,
                    extraHeaders = buildSignatureHeaders(fileUrl, replacement),
                    allowCache = false,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            return first
        }
        if (!hasSourceStream(retried)) return first
        saltMutex.withLock {
            currentSalt = replacement
        }
        return retried
    }

    companion object {
        const val BASE_URL: String = "https://www.iwara.tv"
        const val API_BASE: String = "https://apiq.iwara.tv"
        const val IMAGE_CDN: String = "https://i.iwara.tv"
        const val DEFAULT_SALT: String = "mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"
        private const val SALT_REFRESH_COOLDOWN_MS: Long = 10L * 60L * 1000L
        private const val PAGE_LIMIT: Int = 20

        val API_HOSTS: Set<String> = setOf("apiq.iwara.tv", "api.iwara.tv")
        private val FILE_HOSTS: Set<String> = setOf("files.iwara.tv", "filesq.iwara.tv")
        private val VIDEO_EXTENSIONS: Set<String> = setOf("mp4", "webm", "mov", "avi", "mkv", "flv", "wmv", "m4v")

        private val uuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}")
        private val idRegex = Regex("[A-Za-z0-9_-]+")
        private val mainScriptRegex = Regex("""src=["'](/main\.[a-fA-F0-9]+\.js)["']""")
        private val saltRegex = Regex(""""_"\+[\w$.]+expires\+"_([A-Za-z0-9]{16,})"""")

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.IWARA,
            displayName = "Iwara",
            shortName = "Iwara",
            primaryKind = MediaKind.VIDEO,
            supportedKinds = setOf(MediaKind.VIDEO, MediaKind.PHOTO),
            defaultHost = "www.iwara.tv",
            mirrorHosts = listOf("iwara.tv"),
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
                SiteCapability.DUAL_MEDIA,
            ),
            feedSorts = listOf(
                FeedSort.RELEVANCE,
                FeedSort.NEWEST,
                FeedSort.TRENDING,
                FeedSort.POPULAR,
                FeedSort.VIEWS,
                FeedSort.LIKES,
            ),
            sections = listOf(
                SiteSectionSpec("trending", "趋势榜", "看看最近受到关注的作品", defaultSort = FeedSort.TRENDING, mediaKind = MediaKind.VIDEO),
                SiteSectionSpec("latest", "最新上传", defaultSort = FeedSort.NEWEST, mediaKind = MediaKind.VIDEO),
                SiteSectionSpec("images", "最新图库", "图片与图集，使用独立图库接口", defaultSort = FeedSort.NEWEST, mediaKind = MediaKind.PHOTO),
                SiteSectionSpec("image-likes", "图库点赞榜", defaultSort = FeedSort.LIKES, mediaKind = MediaKind.PHOTO),
                SiteSectionSpec("popular", "热门榜", defaultSort = FeedSort.POPULAR, mediaKind = MediaKind.VIDEO),
                SiteSectionSpec("liked", "点赞榜", defaultSort = FeedSort.LIKES, mediaKind = MediaKind.VIDEO),
                SiteSectionSpec("views", "播放榜", defaultSort = FeedSort.VIEWS, mediaKind = MediaKind.VIDEO),
                SiteSectionSpec("subscriptions", "关注动态", "已关注作者的最新作品，需要在设置中登录", defaultSort = FeedSort.NEWEST, mediaKind = MediaKind.VIDEO),
            ),
            searchScopes = listOf(
                SearchScope.ALL,
                SearchScope.TAG,
                SearchScope.AUTHOR,
            ),
            supportedFilters = setOf(
                AdvancedFilterField.INCLUDE_TAGS,
                AdvancedFilterField.DURATION,
            ),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.RAVEN_API_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                apiBase = API_BASE,
                apiHosts = API_HOSTS,
                mediaDomains = setOf("iwara.tv"),
            ),
        )

        fun isUserId(value: String): Boolean = uuidRegex.matches(value.trim())

        fun isGalleryRequest(request: FeedRequest): Boolean =
            request.mediaKind?.isGallery == true ||
                request.section == "images" ||
                request.section == "image-likes"

        fun resolveFeedUrl(request: FeedRequest, apiBase: String = API_BASE): String {
            require(request.page > 0) { "页码必须大于零" }
            val pageZeroBased = request.page - 1
            val base = apiBase.trimEnd('/')
            if (request.section == "subscriptions") {
                return "$base/subscriptions/videos?page=$pageZeroBased&limit=$PAGE_LIMIT"
            }

            val query = request.query?.trim().orEmpty()
            val isSearch = request.scope == SearchScope.ALL && query.isNotEmpty()
            val isGallery = isGalleryRequest(request)

            val endpoint = when {
                isSearch -> "search"
                isGallery -> "images"
                else -> "videos"
            }
            val params = mutableListOf(
                "page" to pageZeroBased.toString(),
                "limit" to PAGE_LIMIT.toString(),
            )
            if (isSearch) {
                // Non-negotiable invariant: ALWAYS plural `videos` or `images`.
                params.add("type" to if (isGallery) "images" else "videos")
                params.add("query" to query)
            }
            val tags = buildList {
                if ((request.scope == SearchScope.TAG || request.scope == SearchScope.CHARACTER) && query.isNotEmpty()) {
                    add(query)
                }
                request.advancedFilters.includeTags.map { it.trim() }.filter { it.isNotEmpty() }.forEach(::add)
            }.distinct()
            if (tags.isNotEmpty()) {
                params.add("tags" to tags.joinToString(","))
            }
            if (request.scope == SearchScope.AUTHOR && query.isNotEmpty()) {
                params.add("user" to query)
            }

            val sort = when {
                request.section == "popular" -> "popularity"
                request.section == "liked" || request.section == "image-likes" -> "likes"
                request.section == "trending" -> "trending"
                request.section == "views" -> "views"
                isSearch && (request.sort == FeedSort.RELEVANCE || request.sort == FeedSort.DEFAULT) -> null
                else -> when (request.sort) {
                    FeedSort.TRENDING -> "trending"
                    FeedSort.POPULAR -> "popularity"
                    FeedSort.VIEWS -> "views"
                    FeedSort.LIKES, FeedSort.RATING -> "likes"
                    else -> "date"
                }
            }
            sort?.let { params.add("sort" to it) }

            val queryStr = params.joinToString("&") { (k, v) ->
                "${HtmlUrlCleaner.encodeQueryParam(k)}=${HtmlUrlCleaner.encodeQueryParam(v)}"
            }
            return "$base/$endpoint?$queryStr"
        }

        fun extractVideoId(url: String): String? {
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!DESCRIPTOR.matchesRequestHost(host)) return null
            val parts = VideoSiteSupport.pathSegments(uri)
            return parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "video" && idRegex.matches(it) }
        }

        fun extractImageId(url: String): String? {
            val uri = UrlSanitizer.parseUriOrNull(url) ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!DESCRIPTOR.matchesRequestHost(host)) return null
            val parts = VideoSiteSupport.pathSegments(uri)
            return parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "image" && idRegex.matches(it) }
        }

        fun buildSignatureHeaders(fileUrl: String, salt: String = DEFAULT_SALT): Map<String, String> {
            val uri = UrlSanitizer.parseUriOrNull(fileUrl)
                ?: throw SiteException.ParseError("Iwara 返回了无法识别的文件地址", siteId = SiteId.IWARA)
            val host = uri.host?.lowercase().orEmpty()
            val parts = VideoSiteSupport.pathSegments(uri)
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                host !in FILE_HOSTS ||
                parts.firstOrNull() != "file"
            ) {
                throw SiteException.ParseError("Iwara 返回了无法识别的文件地址", siteId = SiteId.IWARA)
            }
            val fileId = parts.lastOrNull()?.takeIf { it.isNotBlank() }
                ?: throw SiteException.ParseError("Iwara 文件 ID 缺失", siteId = SiteId.IWARA)
            val expires = VideoSiteSupport.queryParam(uri, "expires")?.takeIf { it.toLongOrNull() != null }
                ?: throw SiteException.ParseError("Iwara 文件地址缺少有效期", siteId = SiteId.IWARA)
            val digest = sha1Hex("${fileId}_${expires}_$salt")
            return mapOf("X-Version" to digest)
        }

        fun sha1Hex(input: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun hasSourceStream(sources: JsonArray): Boolean =
            sources.any { it is JsonObject && it.str("name").equals("Source", ignoreCase = true) }

        fun extractMainScriptPath(html: String): String? =
            mainScriptRegex.find(html)?.groupValues?.get(1)

        fun extractSaltFromScript(script: String): String? =
            saltRegex.find(script)?.groupValues?.get(1)

        fun parseVideoFeed(
            data: JsonElement,
            request: FeedRequest,
            fromCache: Boolean = false,
        ): FeedPage {
            val root = data as? JsonObject
                ?: throw SiteException.ParseError("Iwara 列表响应格式已变化", siteId = SiteId.IWARA)
            val results = root["results"] as? JsonArray
                ?: throw SiteException.ParseError("Iwara 列表响应格式已变化", siteId = SiteId.IWARA)
            val current = (root.num("page", (request.page - 1).toLong()).toInt() + 1).coerceAtLeast(1)
            val limit = root.num("limit", PAGE_LIMIT.toLong()).coerceAtLeast(1L)
            val count = root.num("count", 0L).coerceAtLeast(0L)
            // Dynamic page count recalculation from EVERY response.
            val totalPages = ((count + limit - 1L) / limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(current)
            val items = results.mapNotNull { (it as? JsonObject)?.let(::parseVideoItem) }.distinctBy { it.id }
            val hasMore = current < totalPages
            return FeedPage(
                items = items,
                nextPage = if (hasMore) current + 1 else null,
                hasMore = hasMore,
                totalCount = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                totalPages = totalPages,
                title = request.query?.takeIf { it.isNotBlank() },
                fromCache = fromCache,
            )
        }

        fun parseImageFeed(
            data: JsonElement,
            request: FeedRequest,
            fromCache: Boolean = false,
        ): FeedPage {
            val root = data as? JsonObject
                ?: throw SiteException.ParseError("Iwara 图库列表响应格式已变化", siteId = SiteId.IWARA)
            val results = root["results"] as? JsonArray
                ?: throw SiteException.ParseError("Iwara 图库列表响应格式已变化", siteId = SiteId.IWARA)
            val current = (root.num("page", (request.page - 1).toLong()).toInt() + 1).coerceAtLeast(1)
            val limit = root.num("limit", PAGE_LIMIT.toLong()).coerceAtLeast(1L)
            val count = root.num("count", 0L).coerceAtLeast(0L)
            val totalPages = ((count + limit - 1L) / limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(current)
            val items = results.mapNotNull { (it as? JsonObject)?.let(::parseImageItem) }.distinctBy { it.id }
            val hasMore = current < totalPages
            return FeedPage(
                items = items,
                nextPage = if (hasMore) current + 1 else null,
                hasMore = hasMore,
                totalCount = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                totalPages = totalPages,
                title = request.query?.takeIf { it.isNotBlank() },
                fromCache = fromCache,
            )
        }

        fun parseVideoItem(obj: JsonObject): MediaItem? {
            val id = obj.str("id")
            val title = HtmlUrlCleaner.cleanText(obj.str("title"))
            if (id.isBlank() || title.isBlank()) return null
            val file = obj.obj("file")
            val fileId = file?.str("id").orEmpty()
            val customThumb = obj.obj("customThumbnail")
            val thumb = when {
                customThumb != null && customThumb.str("id").isNotBlank() && customThumb.str("name").isNotBlank() ->
                    buildImageUrl("thumbnail", customThumb.str("id"), customThumb.str("name"))
                fileId.isNotBlank() -> {
                    val thumbIndex = obj.num("thumbnail", 0L).toString().padStart(2, '0')
                    buildImageUrl("thumbnail", fileId, "thumbnail-$thumbIndex.jpg")
                }
                else -> ""
            }
            val durationSec = file?.num("duration", 0L)?.toInt()?.takeIf { it > 0 }
            val slug = obj.str("slug").takeIf { it.isNotBlank() }
            val canonical = if (slug != null) {
                "$BASE_URL/video/$id/${HtmlUrlCleaner.encodePathSegment(slug)}"
            } else {
                "$BASE_URL/video/$id"
            }
            val tags = parseTagRefs(obj, isGallery = false)
            val author = parseAuthorRef(obj.obj("user"), isGallery = false)
            val isEmbed = obj.str("embedUrl").isNotBlank()
            return MediaItem(
                siteId = SiteId.IWARA,
                id = id,
                url = canonical,
                kind = MediaKind.VIDEO,
                title = title,
                coverUrl = thumb,
                durationSeconds = durationSec,
                views = obj.num("numViews", 0L),
                likes = obj.num("numLikes", 0L),
                author = author,
                tags = tags,
                previewUrl = if (fileId.isNotBlank()) buildImageUrl("original", fileId, "preview.webp") else null,
                extra = if (isEmbed) mapOf("badge" to "站外视频") else emptyMap(),
            )
        }

        fun parseImageItem(obj: JsonObject): MediaItem? {
            val id = obj.str("id")
            if (id.isBlank() || !idRegex.matches(id)) return null
            val title = HtmlUrlCleaner.cleanText(obj.str("title")).ifBlank { "图库 $id" }
            val thumbnail = obj.obj("thumbnail")
            val thumbId = thumbnail?.str("id").orEmpty()
            val rawName = thumbnail?.str("name").orEmpty()
            val thumbUrl = if (thumbId.isNotBlank() && rawName.isNotBlank()) {
                val posterName = if (rawName.contains('.')) rawName.substringBeforeLast('.') + ".jpg" else "$rawName.jpg"
                buildImageUrl("thumbnail", thumbId, posterName)
            } else {
                ""
            }
            val w = thumbnail?.num("width", 0L)?.toInt() ?: 0
            val h = thumbnail?.num("height", 0L)?.toInt() ?: 0
            val ratio = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else null
            val slug = obj.str("slug").takeIf { it.isNotBlank() }
            val canonical = if (slug != null) {
                "$BASE_URL/image/$id/${HtmlUrlCleaner.encodePathSegment(slug)}"
            } else {
                "$BASE_URL/image/$id"
            }
            return MediaItem(
                siteId = SiteId.IWARA,
                id = id,
                url = canonical,
                kind = MediaKind.PHOTO,
                title = title,
                coverUrl = thumbUrl,
                coverAspectRatio = ratio,
                pageCount = obj.num("numImages", 0L).toInt().takeIf { it > 0 },
                views = obj.num("numViews", 0L),
                likes = obj.num("numLikes", 0L),
                author = parseAuthorRef(obj.obj("user"), isGallery = true),
                tags = parseTagRefs(obj, isGallery = true),
            )
        }

        fun parseVideoDetail(
            metadata: JsonObject,
            sources: JsonArray,
            related: JsonArray = JsonArray(emptyList()),
            comments: JsonArray = JsonArray(emptyList()),
        ): VideoDetail {
            val item = parseVideoItem(metadata)
                ?: throw SiteException.ParseError("Iwara 详情缺少视频信息", siteId = SiteId.IWARA)
            val originalHeight = metadata.obj("file")?.num("height", 0L)?.toInt() ?: 0

            fun buildStreams(download: Boolean): List<VideoStream> = sources.mapNotNull { el ->
                val srcObj = el as? JsonObject ?: return@mapNotNull null
                val name = srcObj.str("name")
                if (name.equals("preview", ignoreCase = true)) return@mapNotNull null
                val isSource = name.equals("Source", ignoreCase = true)
                val height = if (isSource) originalHeight else name.removeSuffix("p").toIntOrNull() ?: return@mapNotNull null
                val urls = srcObj.obj("src") ?: return@mapNotNull null
                val raw = (if (download) urls.str("download") else urls.str("view")).ifBlank { urls.str("view") }
                val normalized = if (raw.startsWith("//")) "https:$raw" else raw
                if (!UrlSanitizer.isHttpsUrl(normalized)) return@mapNotNull null
                val label = if (isSource) {
                    if (height > 0) "${height}p 原画" else "原画"
                } else {
                    "${height}p"
                }
                VideoStream(
                    quality = if (isSource) "Source" else "${height}p",
                    label = label,
                    url = normalized,
                    isHls = normalized.substringBefore('?').endsWith(".m3u8", ignoreCase = true),
                    mimeTypeHint = "video/mp4",
                )
            }.distinctBy { it.label }.sortedByDescending { stream ->
                val h = VideoSiteSupport.parseStreamHeight(stream.label)
                if (stream.quality == "Source" && h == 0) Int.MAX_VALUE else h
            }

            val playbackStreams = buildStreams(download = false)
            if (playbackStreams.isEmpty()) {
                throw SiteException.ParseError("Iwara 没有可用的 HTTPS 视频源，请稍后重试", siteId = SiteId.IWARA)
            }
            val downloadStreams = buildStreams(download = true)
            val authors = listOfNotNull(parseAuthorRef(metadata.obj("user"), isGallery = false))
            val parsedRelated = related.mapNotNull { (it as? JsonObject)?.let(::parseVideoItem) }
                .filter { it.id != item.id }
                .distinctBy { it.id }
            val parsedComments = comments.mapIndexedNotNull { idx, el ->
                val c = el as? JsonObject ?: return@mapIndexedNotNull null
                val body = HtmlUrlCleaner.cleanText(c.str("body"))
                if (body.isBlank()) return@mapIndexedNotNull null
                val author = c.obj("user")?.let { u ->
                    u.str("name").ifBlank { u.str("username") }
                }.orEmpty().ifBlank { "用户" }
                CommentItem(
                    id = c.str("id").ifBlank { "ic-$idx" },
                    author = author,
                    body = body,
                    timestampLabel = c.str("createdAt").substringBefore('T'),
                )
            }

            return VideoDetail(
                item = item,
                description = metadata.str("body"),
                streams = playbackStreams,
                downloadStreams = downloadStreams,
                tags = item.tags,
                authors = authors,
                comments = parsedComments,
                related = parsedRelated,
            )
        }

        fun parseGalleryFiles(data: JsonObject): Pair<List<GalleryPage>, Int> {
            val item = parseImageItem(data)
                ?: throw SiteException.ParseError("Iwara 图库缺少有效身份", siteId = SiteId.IWARA)
            var skippedVideos = 0
            val pages = mutableListOf<GalleryPage>()
            for (el in data.arr("files")) {
                val file = el as? JsonObject ?: continue
                val type = file.str("type")
                val mime = file.str("mime")
                val name = file.str("name")
                val ext = name.substringAfterLast('.', "").lowercase()
                val isVideo = type.equals("video", ignoreCase = true) ||
                    mime.startsWith("video/", ignoreCase = true) ||
                    ext in VIDEO_EXTENSIONS
                if (isVideo) {
                    skippedVideos++
                    continue
                }
                val fileId = file.str("id")
                if (fileId.isBlank() || name.isBlank()) continue
                val pageNum = pages.size + 1
                val pageUrl = "${item.url}#page=$pageNum"
                val originalUrl = buildImageUrl("original", fileId, name)
                val thumbName = if (name.contains('.')) name.substringBeforeLast('.') + ".jpg" else "$name.jpg"
                val thumbUrl = buildImageUrl("thumbnail", fileId, thumbName)
                val w = file.num("width", 0L).toInt().takeIf { it > 0 }
                val h = file.num("height", 0L).toInt().takeIf { it > 0 }
                pages.add(
                    GalleryPage(
                        index = pageNum,
                        pageUrl = pageUrl,
                        imageUrl = originalUrl,
                        originalImageUrl = originalUrl,
                        thumbUrl = thumbUrl,
                        width = w,
                        height = h,
                        reloadToken = "1",
                    ),
                )
            }
            return pages to skippedVideos
        }

        fun parseGalleryDetail(data: JsonObject): GalleryDetail {
            val rawItem = parseImageItem(data)
                ?: throw SiteException.ParseError("Iwara 图库缺少有效身份", siteId = SiteId.IWARA)
            val (pages, skippedVideos) = parseGalleryFiles(data)
            val firstRatio = rawItem.coverAspectRatio ?: pages.firstOrNull()?.let { p ->
                val pw = p.width
                val ph = p.height
                if (pw != null && ph != null && pw > 0 && ph > 0) {
                    pw.toFloat() / ph.toFloat()
                } else {
                    null
                }
            }
            val item = rawItem.copy(
                pageCount = pages.size,
                coverAspectRatio = firstRatio,
            )
            val note = if (skippedVideos > 0) "此图集另有 $skippedVideos 个视频文件，未计入图片阅读页数。" else ""
            val description = listOf(data.str("body").trim(), note).filter { it.isNotBlank() }.joinToString("\n")
            return GalleryDetail(
                item = item,
                description = description,
                pageCount = pages.size,
                pages = pages,
                tags = item.tags,
                authors = listOfNotNull(item.author),
                previewPageSize = pages.size,
            )
        }

        private fun buildImageUrl(size: String, fileId: String, name: String): String =
            "$IMAGE_CDN/image/${HtmlUrlCleaner.encodePathSegment(size)}/${HtmlUrlCleaner.encodePathSegment(fileId)}/${HtmlUrlCleaner.encodePathSegment(name)}"

        private fun parseTagRefs(obj: JsonObject, isGallery: Boolean): List<TagRef> {
            val path = if (isGallery) "images" else "videos"
            return obj.arr("tags").mapNotNull { el ->
                val tagObj = el as? JsonObject ?: return@mapNotNull null
                val tagId = tagObj.str("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TagRef(
                    slug = tagId,
                    label = tagId,
                    url = "$BASE_URL/$path?tags=${HtmlUrlCleaner.encodeQueryParam(tagId)}",
                )
            }
        }

        private fun parseAuthorRef(user: JsonObject?, isGallery: Boolean): AuthorRef? {
            if (user == null) return null
            val id = user.str("id").takeIf { it.isNotBlank() } ?: return null
            val username = user.str("username")
            val name = user.str("name").ifBlank { username }.ifBlank { id }
            val url = if (isGallery) {
                "$BASE_URL/images?user=${HtmlUrlCleaner.encodeQueryParam(id)}"
            } else {
                val seg = if (username.isBlank()) "user" else "profile"
                val key = username.ifBlank { id }
                "$BASE_URL/$seg/${HtmlUrlCleaner.encodePathSegment(key)}"
            }
            return AuthorRef(id = id, name = name, role = "creator", url = url)
        }

        private fun parseApiSort(raw: String?): FeedSort = when (raw?.lowercase()) {
            "trending" -> FeedSort.TRENDING
            "popularity" -> FeedSort.POPULAR
            "views" -> FeedSort.VIEWS
            "likes" -> FeedSort.LIKES
            "date" -> FeedSort.NEWEST
            else -> FeedSort.DEFAULT
        }

        private fun JsonObject.str(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

        private fun JsonObject.num(key: String, fallback: Long): Long =
            (this[key] as? JsonPrimitive)?.longOrNull ?: fallback

        private fun JsonObject.bool(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.obj(key: String): JsonObject? =
            this[key] as? JsonObject

        private fun JsonObject.arr(key: String): JsonArray =
            (this[key] as? JsonArray) ?: JsonArray(emptyList())
    }

    private fun parseJsonObject(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw SiteException.ParseError("站点返回了无效的 JSON 对象", siteId = SiteId.IWARA, cause = e)
    }

    private fun requireJsonObject(el: JsonElement): JsonObject =
        el as? JsonObject ?: throw SiteException.ParseError("站点返回了非 JSON 对象结构", siteId = SiteId.IWARA)

    private fun requireJsonArray(el: JsonElement): JsonArray =
        el as? JsonArray ?: throw SiteException.ParseError("站点返回了非 JSON 数组结构", siteId = SiteId.IWARA)
}
