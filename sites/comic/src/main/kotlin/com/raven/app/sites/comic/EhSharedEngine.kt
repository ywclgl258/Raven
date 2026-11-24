package com.raven.app.sites.comic

import com.raven.app.core.common.ContentFilter
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
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteRequestKind
import com.raven.app.core.sitespi.SiteSessionStore
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.ProtocolException

/**
 * Shared protocol engine powering both [EhentaiSitePlugin] and [ExhentaiSitePlugin].
 * Encapsulates forum WebView/cookie authentication, `nw=1` injection, `f_cats` XOR bitmasking,
 * `#unext` cursor pagination, paginated gallery preview loading, `fullimg.php` original resolution,
 * and `?nl=` reload recovery.
 */
class EhSharedEngine(
    val descriptor: SiteDescriptor,
    private val httpClient: HttpClientAdapter,
    private val sessionStore: SiteSessionStore,
    override val requiresAuthGate: Boolean,
) : FeedSource, GallerySource, SearchSource, AuthProvider, LinkResolver {

    private val siteId: SiteId = descriptor.id
    private val previewPageCache = LruCache<String, List<GalleryPage>>(maxEntries = 64)

    override val mode: AuthMode = AuthMode.WebViewCookie(
        loginUrl = OFFICIAL_LOGIN_URL,
        targetUrl = "https://${descriptor.defaultHost}/",
        allowedHosts = ALLOWED_AUTH_HOSTS,
        requiredCookies = REQUIRED_COOKIES,
        optionalCookies = OPTIONAL_COOKIES,
    )

    override val supportedScopes: Set<SearchScope> = setOf(
        SearchScope.ALL,
        SearchScope.TAG,
        SearchScope.AUTHOR,
    )

    override val supportedFilters: Set<AdvancedFilterField> = SUPPORTED_FILTERS

    override val supportsExcludeTags: Boolean = true

    override val rejectCrossHostImageRedirect: Boolean = true

    // -------------------------------------------------------------------------
    // AuthProvider
    // -------------------------------------------------------------------------

    override fun defaultCookies(host: String): Map<String, String> =
        if (descriptor.matchesHost(host)) mapOf("nw" to "1") else emptyMap()

    override suspend fun currentAuthState(): AuthState {
        val cookies = sessionStore.getCookies(siteId)
        val authenticated = hasValidIdentity(cookies)
        val username = if (authenticated) {
            sessionStore.getUsername(siteId) ?: cookies["ipb_member_id"]?.let { "UID $it" }
        } else {
            null
        }
        return AuthState(
            isAuthenticated = authenticated,
            username = username,
            epoch = sessionStore.authEpoch(siteId),
        )
    }

    override suspend fun logout() {
        sessionStore.clearSite(siteId)
        previewPageCache.clear()
    }

    override suspend fun importCookies(cookies: Map<String, String>): AuthState {
        val candidate = sanitizeAndValidateCookies(cookies).toMutableMap()
        val verifyUrl = resolveFeedUrl(FeedRequest(siteId = siteId, section = "latest"))
        val cookieHeader = buildString {
            candidate.forEach { (k, v) ->
                if (isNotEmpty()) append("; ")
                append(k).append('=').append(v)
            }
            if (isNotEmpty()) append("; ")
            append("nw=1")
        }

        val response = try {
            httpClient.execute(
                SiteHttpRequest(
                    url = verifyUrl,
                    siteId = siteId,
                    method = SiteHttpMethod.GET,
                    kind = SiteRequestKind.PAGE,
                    headers = mapOf("Cookie" to cookieHeader),
                    allowCookies = false,
                    allowCache = false,
                    allowRetry = false,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw SiteException.AuthRequired(
                message = "${descriptor.displayName} 会话验证失败：${e.message}",
                siteId = siteId,
                cause = e,
            )
        } catch (e: Exception) {
            throw SiteException.AuthRequired(
                message = "${descriptor.displayName} 会话验证失败：${e.message ?: "网络异常"}",
                siteId = siteId,
                cause = e,
            )
        }

        if (!response.isSuccessful) {
            throw SiteException.AuthRequired(
                message = "${descriptor.displayName} 会话验证失败：HTTP ${response.code}",
                siteId = siteId,
            )
        }

        // Parse and validate that the session can actually access a gallery listing
        // (prevents persisting invalid/Sad-Panda sessions on ExHentai).
        parseFeed(
            body = response.bodyText(),
            baseUrl = verifyUrl,
            page = 1,
            clientLanguage = ComicLanguage.ALL,
            heading = "最新画廊",
        )

        // Merge any refreshed allowed cookies (e.g., igneous / sk) returned by Set-Cookie
        for (rawSetCookie in response.headers("Set-Cookie")) {
            val pair = rawSetCookie.substringBefore(';').split('=', limit = 2)
            if (pair.size != 2) continue
            val name = pair[0].trim()
            val value = pair[1].trim()
            if (name in ALLOWED_SESSION_COOKIES && isValidCookieValue(value)) {
                if (name == "igneous" && (value.equals("mystery", ignoreCase = true) || value.equals("expired", ignoreCase = true))) {
                    continue
                }
                candidate[name] = value
            }
        }

        sessionStore.replaceCookies(siteId, candidate)
        val username = "UID ${candidate["ipb_member_id"]}"
        sessionStore.saveUsername(siteId, username)
        return AuthState(
            isAuthenticated = true,
            username = username,
            epoch = sessionStore.authEpoch(siteId),
        )
    }

    suspend fun importCookieHeader(rawHeader: String): AuthState =
        importCookies(parseCookieHeader(rawHeader))

    // -------------------------------------------------------------------------
    // FeedSource & SearchSource
    // -------------------------------------------------------------------------

    override suspend fun loadHome(language: ComicLanguage): HomeFeed {
        val page = loadFeed(FeedRequest(siteId = siteId, section = "latest", language = language, page = 1))
        return HomeFeed(siteId = siteId, primaryFeedPage = page)
    }

    override suspend fun loadFeed(request: FeedRequest): FeedPage {
        requireAccess()
        val url = resolveFeedUrl(request)
        val html = fetchHtml(url)
        val isPopular = isPopularRequest(request)
        val clientLanguage = if (isPopular) request.language else ComicLanguage.ALL
        val parsed = parseFeed(
            body = html,
            baseUrl = url,
            page = request.page.coerceAtLeast(1),
            clientLanguage = clientLanguage,
            heading = feedHeading(request),
        )
        val excludes = request.advancedFilters.excludeTags
        if (excludes.isEmpty()) return parsed
        return parsed.copy(
            items = parsed.items.filter { item -> ContentFilter.isAllowed(item, excludes) },
        )
    }

    override suspend fun search(request: FeedRequest): FeedPage = loadFeed(request)

    override fun supportedSorts(request: FeedRequest): List<FeedSort> = listOf(FeedSort.NEWEST)

    override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> = listOf(FeedSort.NEWEST)

    fun resolveFeedUrl(request: FeedRequest): String {
        val cursorRaw = request.cursor?.trim().orEmpty()
        if (cursorRaw.isNotEmpty()) {
            val cursor = ComicUrl.parseStrictHttps(cursorRaw)
                ?.takeIf { descriptor.matchesHost(it.host) && it.encodedPath in VALID_CURSOR_PATHS }
                ?: throw SiteException.ParseError("原站翻页地址无效，请刷新列表", siteId = siteId)
            return applyFiltersAndLanguage(cursor, request, fromCursor = true)
        }

        var builder = ComicUrl.requireParse(descriptor.baseUrl, siteId)
        if (isPopularRequest(request)) {
            builder = builder.addPathSegment("popular")
        }

        val categoryKey = resolveCategoryKey(request.category)
            ?: request.section?.takeIf { it in CATEGORIES }
        if (categoryKey != null) {
            builder = builder.setQueryParameter("f_cats", computeFCats(categoryKey.toInt()).toString())
        }

        val initialSearch = buildPrimaryQuery(request)
        if (initialSearch.isNotEmpty()) {
            builder = builder.setQueryParameter("f_search", initialSearch)
        }

        return applyFiltersAndLanguage(builder, request, fromCursor = false)
    }

    private fun applyFiltersAndLanguage(
        initialUrl: ComicUrl,
        request: FeedRequest,
        fromCursor: Boolean,
    ): String {
        var url = initialUrl
        val filters = request.advancedFilters
        validateFilters(filters)

        val primaryQuery = buildPrimaryQuery(request)
        val baseQuery = if (primaryQuery.isNotEmpty()) {
            primaryQuery
        } else if (fromCursor) {
            url.queryParameter("f_search").orEmpty()
        } else {
            ""
        }

        val languageTerms = buildLanguageTerms(request.language)
        val includeTerms = filters.includeTags.mapNotNull { formatSearchTag(it, exclude = false) }
        val excludeTerms = filters.excludeTags.mapNotNull { formatSearchTag(it, exclude = true) }

        val existingTokens = tokenizeSearchTerms(baseQuery)
        val combinedTerms = (existingTokens + languageTerms + includeTerms + excludeTerms)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

        if (combinedTerms.isNotEmpty() || url.queryParameter("f_search") != null) {
            url = url.setQueryParameter("f_search", combinedTerms.joinToString(" "))
        }

        if (request.language != ComicLanguage.ALL) {
            url = url.setQueryParameter("f_sfl", "on")
        }
        val minPages = filters.minPages ?: 0
        val maxPages = filters.maxPages ?: 0
        val minRating = filters.minRating ?: 0
        if (minPages > 0) {
            url = url.setQueryParameter("f_spf", minPages.toString())
        }
        if (maxPages > 0) {
            url = url.setQueryParameter("f_spt", maxPages.toString())
        }
        if (minRating > 0) {
            url = url.setQueryParameter("f_sr", "on")
            url = url.setQueryParameter("f_srdd", minRating.toString())
        }

        val hasDirectCategory = resolveCategoryKey(request.category) != null ||
            (request.section != null && request.section in CATEGORIES)
        if (!filters.category.isNullOrBlank() && !hasDirectCategory) {
            val catKey = resolveCategoryKey(filters.category)
                ?: throw SiteException.Unsupported("不支持的画廊分类：${filters.category}", siteId = siteId)
            url = url.setQueryParameter("f_cats", computeFCats(catKey.toInt()).toString())
        }

        if (!filters.isEmpty) {
            url = url.setQueryParameter("advsearch", "1")
        }

        url = url.setQueryParameter("inline_set", "dm_l")
        return url.toString()
    }

    // -------------------------------------------------------------------------
    // GallerySource
    // -------------------------------------------------------------------------

    override suspend fun loadGalleryDetail(url: String): GalleryDetail {
        requireAccess()
        val canonical = canonicalGalleryUrl(url)
            ?: throw SiteException.ParseError("无法识别画廊地址：$url", siteId = siteId)
        val html = fetchHtml(canonical)
        val detail = parseGalleryDetail(html, canonical)
        previewPageCache.put(previewCacheKey(canonical, 0), detail.pages)
        return detail
    }

    override suspend fun loadGalleryPage(
        detail: GalleryDetail,
        pageIndex: Int,
        forceReload: Boolean,
    ): GalleryPage {
        requireAccess()
        if (pageIndex !in 1..detail.pageCount) {
            throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = siteId)
        }
        val pageUrl = resolvePageLink(detail, pageIndex, forceReload)
        return resolvePageImage(pageUrl = pageUrl, preferOriginal = false, forceReload = forceReload)
    }

    override suspend fun resolvePageImage(
        pageUrl: String,
        preferOriginal: Boolean,
        forceReload: Boolean,
    ): GalleryPage {
        requireAccess()
        val index = extractPageIndex(pageUrl)
            ?: throw SiteException.ParseError("无法识别漫画页地址：$pageUrl", siteId = siteId)
        val html = fetchHtml(pageUrl, allowCache = !forceReload)
        return parsePageImage(
            body = html,
            pageUrl = pageUrl,
            expectedIndex = index,
            preferOriginal = preferOriginal,
        )
    }

    override fun reloadUrlOnError(cause: SiteException, page: GalleryPage): String? {
        val token = page.reloadToken?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val shouldRetryWithToken = when (cause) {
            is SiteException.Forbidden -> !cause.isChallenge
            is SiteException.TlsError -> true
            is SiteException.Network -> true
            else -> false
        }
        if (!shouldRetryWithToken) return null
        val parsed = ComicUrl.parseStrictHttps(page.pageUrl) ?: return null
        if (!descriptor.matchesHost(parsed.host)) return null
        return parsed.setQueryParameter("nl", token).toString()
    }

    private suspend fun resolvePageLink(
        detail: GalleryDetail,
        pageIndex: Int,
        forceReload: Boolean,
    ): String {
        detail.pages.firstOrNull { it.index == pageIndex }?.let { return it.pageUrl }
        val canonical = canonicalGalleryUrl(detail.item.url)
            ?: throw SiteException.ParseError("画廊地址无效：${detail.item.url}", siteId = siteId)
        val perPreviewPage = (detail.previewPageSize ?: detail.pages.size).coerceAtLeast(1)
        val bucket = (pageIndex - 1) / perPreviewPage
        val cacheKey = previewCacheKey(canonical, bucket)

        val cached = if (forceReload) null else previewPageCache.get(cacheKey)
        val bucketPages = cached ?: run {
            val pagedUrl = ComicUrl.requireParse(canonical, siteId)
                .setQueryParameter("p", bucket.toString())
                .toString()
            val html = fetchHtml(pagedUrl, allowCache = !forceReload)
            checkAccess(html)
            val doc = Jsoup.parse(html, pagedUrl)
            parsePreviewLinks(doc).also { previewPageCache.put(cacheKey, it) }
        }

        return bucketPages.firstOrNull { it.index == pageIndex }?.pageUrl
            ?: throw SiteException.NotFound("原站没有返回第 $pageIndex 页，请刷新画廊后重试", siteId = siteId)
    }

    // -------------------------------------------------------------------------
    // LinkResolver
    // -------------------------------------------------------------------------

    override fun canHandle(url: String): Boolean {
        val parsed = ComicUrl.parse(url) ?: return false
        return descriptor.matchesHost(parsed.host)
    }

    override fun canonicalize(url: String): String? {
        val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        val normalized = ComicUrl.parseStrictHttps("https://${descriptor.defaultHost}${parsed.encodedPath}")
            ?.withQuery(parsed.queryParams)
            ?: return null
        GALLERY_PATH_REGEX.matchEntire(normalized.encodedPath)?.let { m ->
            return "https://${descriptor.defaultHost}/g/${m.groupValues[1]}/${m.groupValues[2]}/"
        }
        IMAGE_PATH_REGEX.matchEntire(normalized.encodedPath)?.let { m ->
            return "https://${descriptor.defaultHost}/s/${m.groupValues[1]}/${m.groupValues[2]}-${m.groupValues[3]}"
        }
        return normalized.withFragment(null).toString()
    }

    override fun resolve(url: String): ResolvedLink? {
        val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        GALLERY_PATH_REGEX.matchEntire(parsed.encodedPath)?.let { m ->
            val gid = m.groupValues[1]
            val token = m.groupValues[2]
            return ResolvedLink.Gallery(
                siteId = siteId,
                id = gid,
                canonicalUrl = "https://${descriptor.defaultHost}/g/$gid/$token/",
            )
        }
        IMAGE_PATH_REGEX.matchEntire(parsed.encodedPath)?.let { m ->
            val key = m.groupValues[1]
            val gid = m.groupValues[2]
            val page = m.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return ResolvedLink.Gallery(
                siteId = siteId,
                id = gid,
                canonicalUrl = "https://${descriptor.defaultHost}/s/$key/$gid-$page",
                pageIndex = page,
            )
        }
        val segments = parsed.pathSegments
        if (segments.firstOrNull() == "tag" && segments.size >= 2) {
            val tag = segments.drop(1).joinToString("/").replace('+', ' ')
            if (tag.isNotBlank()) {
                return ResolvedLink.Feed(
                    FeedRequest(
                        siteId = siteId,
                        query = tag,
                        scope = SearchScope.TAG,
                    ),
                )
            }
        }
        if (parsed.encodedPath == "/popular" || parsed.encodedPath == "/popular/") {
            return ResolvedLink.Feed(
                FeedRequest(siteId = siteId, section = "popular", sort = FeedSort.POPULAR),
            )
        }
        parsed.queryParameter("f_search")?.takeIf { it.isNotBlank() }?.let { query ->
            val uploaderMatch = UPLOADER_QUERY_REGEX.matchEntire(query.trim())
            return if (uploaderMatch != null) {
                ResolvedLink.Feed(
                    FeedRequest(
                        siteId = siteId,
                        query = uploaderMatch.groupValues[1],
                        scope = SearchScope.AUTHOR,
                    ),
                )
            } else {
                ResolvedLink.Feed(
                    FeedRequest(siteId = siteId, query = query, scope = SearchScope.ALL),
                )
            }
        }
        parsed.queryParameter("f_cats")?.toIntOrNull()?.let { mask ->
            val catId = (ALL_CATEGORIES_MASK xor mask).toString()
            if (catId in CATEGORIES) {
                return ResolvedLink.Feed(
                    FeedRequest(siteId = siteId, section = catId, category = catId),
                )
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Parsing & Network Helpers
    // -------------------------------------------------------------------------

    private fun requireAccess() {
        if (requiresAuthGate && !hasValidIdentity(sessionStore.getCookies(siteId))) {
            throw SiteException.AuthRequired(
                message = "ExHentai 必须登录并具备账号权限；未登录可能只显示白屏",
                siteId = siteId,
            )
        }
    }

    private suspend fun fetchHtml(url: String, allowCache: Boolean = true): String {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = siteId,
                kind = SiteRequestKind.PAGE,
                allowCache = allowCache,
                authEpoch = sessionStore.authEpoch(siteId),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            if (requiresAuthGate && e is SiteException.Forbidden && !e.isChallenge) {
                throw SiteException.AuthRequired(
                    message = "ExHentai 会话已失效或需要官方验证，请重新登录",
                    siteId = siteId,
                    cause = e,
                )
            }
            throw e
        } catch (e: ProtocolException) {
            if (requiresAuthGate && e.message.orEmpty().contains("follow-up", ignoreCase = true)) {
                throw SiteException.AuthRequired(
                    message = "ExHentai 会话已失效或需要官方验证，请重新登录",
                    siteId = siteId,
                    cause = e,
                )
            }
            throw SiteException.Network(
                message = "${descriptor.displayName} 请求异常：${e.message ?: "重定向错误"}",
                siteId = siteId,
                cause = e,
            )
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "${descriptor.displayName} 网络请求失败：${e.message ?: "未知错误"}",
                siteId = siteId,
                cause = e,
            )
        }

        if (requiresAuthGate && (response.code == 401 || response.code == 403)) {
            throw SiteException.AuthRequired(
                message = "ExHentai 会话已失效或需要官方验证，请重新登录",
                siteId = siteId,
            )
        }
        response.throwIfNotSuccessful(siteId, descriptor.displayName)
        return response.bodyText()
    }

    internal fun checkAccess(body: String) {
        if (body.contains("Image View Limit Exceeded", ignoreCase = true)) {
            throw SiteException.QuotaExceeded(
                message = "原站图片配额已用尽，请稍后重试",
                siteId = siteId,
            )
        }
        if (body.contains("temporarily banned", ignoreCase = true) ||
            body.contains("excessive pageloads", ignoreCase = true)
        ) {
            throw SiteException.RateLimited(
                message = "原站访问频率已受限，请稍后重试",
                siteId = siteId,
            )
        }
        if (body.contains("This gallery has been removed", ignoreCase = true) ||
            body.contains("pining for the fjords", ignoreCase = true)
        ) {
            throw SiteException.NotFound(
                message = "该画廊已被原站移除",
                siteId = siteId,
            )
        }
        if (requiresAuthGate) {
            val hasHtml = body.contains("<html", ignoreCase = true)
            val hasGalleryGrid = body.contains("class=\"itg", ignoreCase = true) ||
                body.contains("id=\"gn\"", ignoreCase = true) ||
                body.contains("id=\"img\"", ignoreCase = true)
            val isLoginPrompt = body.contains("act=Login", ignoreCase = true) && !hasGalleryGrid
            if ((!hasHtml && !hasGalleryGrid) || isLoginPrompt) {
                throw SiteException.AuthRequired(
                    message = "ExHentai 会话已失效或账号暂无访问权限，请重新登录",
                    siteId = siteId,
                )
            }
        }
    }

    internal fun parseFeed(
        body: String,
        baseUrl: String,
        page: Int,
        clientLanguage: ComicLanguage,
        heading: String,
    ): FeedPage {
        checkAccess(body)
        val doc = Jsoup.parse(body, baseUrl)
        val rows = (doc.select("table.itg tr") + doc.select(".itg .gl1t")).distinct()
        val parsed = rows.mapNotNull { row ->
            val anchor = row.selectFirst(".glname a[href*=/g/], a[href*=/g/]") ?: return@mapNotNull null
            val canonical = canonicalGalleryUrl(anchor.absUrl("href")) ?: return@mapNotNull null
            val gid = GALLERY_PATH_REGEX.find(ComicUrl.requireParse(canonical, siteId).encodedPath)
                ?.groupValues?.get(1) ?: return@mapNotNull null
            val title = HtmlUrlCleaner.cleanText(
                row.selectFirst(".glink")?.text().orEmpty()
                    .ifBlank { anchor.attr("title") }
                    .ifBlank { anchor.text() },
            )
            if (title.isBlank()) return@mapNotNull null

            val imageEl = row.selectFirst(".glthumb img, .gl3t img, img")
            val thumb = imageEl?.let { img ->
                resolveHttps(img.attr("data-src").ifBlank { img.attr("src") }, baseUrl)
            }.orEmpty()
            val width = extractImageDimension(imageEl, "width")
            val height = extractImageDimension(imageEl, "height")
            val aspectRatio = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
            val pageCount = PAGES_COUNT_REGEX.find(row.text())
                ?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            val categoryText = HtmlUrlCleaner.cleanText(row.selectFirst(".cn, .cs")?.text().orEmpty())
            val languageTags = row.select("[title^=language:]")
                .map { it.attr("title").substringAfter(':').trim().lowercase() }
                .filter { it.isNotEmpty() }
            val extra = buildMap {
                if (categoryText.isNotEmpty()) put("category", categoryText)
            }

            MediaItem(
                siteId = siteId,
                id = gid,
                url = canonical,
                kind = MediaKind.COMIC,
                title = title,
                coverUrl = thumb,
                coverAspectRatio = aspectRatio,
                pageCount = pageCount,
                language = languageTags.firstOrNull(),
                extra = extra,
            ) to languageTags
        }.distinctBy { it.first.id }

        val isExplicitEmpty = body.contains("No hits found", ignoreCase = true) ||
            body.contains("No unfiltered results", ignoreCase = true)
        if (parsed.isEmpty() && !isExplicitEmpty) {
            if (requiresAuthGate) {
                throw SiteException.AuthRequired(
                    message = "ExHentai 未返回画廊，请检查本站会话和账号权限",
                    siteId = siteId,
                )
            }
            throw SiteException.ParseError(
                message = "原站未返回可识别的画廊列表，请稍后重试",
                siteId = siteId,
            )
        }

        val nextCursor = extractNextCursor(doc, baseUrl)
        val requiredLanguages = clientLanguage.languageKeys()
        val filteredItems = parsed
            .filter { (_, langs) -> requiredLanguages.isEmpty() || langs.any(requiredLanguages::contains) }
            .map { it.first }

        return FeedPage(
            items = filteredItems,
            nextPage = if (nextCursor != null) page + 1 else null,
            nextCursor = nextCursor,
            hasMore = nextCursor != null,
            title = heading,
        )
    }

    internal fun parseGalleryDetail(body: String, canonicalUrl: String): GalleryDetail {
        checkAccess(body)
        val canonical = canonicalGalleryUrl(canonicalUrl)
            ?: throw SiteException.ParseError("画廊地址无效：$canonicalUrl", siteId = siteId)
        val doc = Jsoup.parse(body, canonical)
        val title = HtmlUrlCleaner.cleanText(
            doc.selectFirst("#gn")?.text().orEmpty()
                .ifBlank { doc.selectFirst("#gj")?.text().orEmpty() },
        )
        if (title.isBlank()) {
            throw SiteException.ParseError("原站未返回画廊详情，请检查会话或内容访问权限", siteId = siteId)
        }
        val japaneseTitle = HtmlUrlCleaner.cleanText(doc.selectFirst("#gj")?.text().orEmpty())

        val info = doc.select("#gdd tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size >= 2) {
                cells[0].text().trim().removeSuffix(":") to HtmlUrlCleaner.cleanText(cells[1].text())
            } else {
                null
            }
        }.toMap()

        val previewPages = parsePreviewLinks(doc)
        val pageCount = info["Length"]
            ?.let { DIGITS_REGEX.find(it)?.value?.replace(",", "")?.toIntOrNull() }
            ?: previewPages.lastOrNull()?.index
            ?: 0
        if (pageCount <= 0 || previewPages.isEmpty()) {
            throw SiteException.ParseError("画廊没有可读取的页码，请检查账号权限或刷新重试", siteId = siteId)
        }

        val tags = mutableListOf<TagRef>()
        val authors = mutableListOf<AuthorRef>()
        val characters = mutableListOf<TagRef>()
        doc.select("#taglist a[href]").forEach { anchor ->
            val href = anchor.absUrl("href")
            val resolved = resolve(href) as? ResolvedLink.Feed ?: return@forEach
            val fullTag = resolved.request.query?.trim().orEmpty()
            if (fullTag.isEmpty()) return@forEach
            val namespace = if (fullTag.contains(':')) fullTag.substringBefore(':') else ""
            val label = if (fullTag.contains(':')) fullTag.substringAfter(':') else fullTag
            val tagRef = TagRef(slug = fullTag, label = label, namespace = namespace, url = href)
            if (tags.none { it.slug == tagRef.slug }) {
                tags.add(tagRef)
            }
            when (namespace) {
                "artist", "group" -> {
                    if (authors.none { it.id == fullTag }) {
                        authors.add(AuthorRef(id = label, name = label, role = namespace, url = href))
                    }
                }
                "character" -> {
                    if (characters.none { it.slug == tagRef.slug }) {
                        characters.add(tagRef)
                    }
                }
            }
        }

        val uploader = HtmlUrlCleaner.cleanText(doc.selectFirst("#gdn a")?.text().orEmpty())
        if (uploader.isNotEmpty()) {
            val uploaderUrl = ComicUrl.requireParse(descriptor.baseUrl, siteId)
                .setQueryParameter("f_search", "uploader:\"$uploader\"")
                .toString()
            authors.add(0, AuthorRef(id = uploader, name = uploader, role = "uploader", url = uploaderUrl))
        }

        val cover = doc.selectFirst("#gd1 [style]")
            ?.let { extractCssImageUrl(it.attr("style"), canonical) }
            .orEmpty()
        val categoryLabel = HtmlUrlCleaner.cleanText(doc.selectFirst("#gdc .cn, #gdc .cs")?.text().orEmpty())
        val categories = if (categoryLabel.isNotEmpty()) {
            val catKey = resolveCategoryKey(categoryLabel) ?: categoryLabel.lowercase()
            listOf(TagRef(slug = catKey, label = categoryLabel, namespace = "category"))
        } else {
            emptyList()
        }
        val gid = GALLERY_PATH_REGEX.find(ComicUrl.requireParse(canonical, siteId).encodedPath)!!
            .groupValues[1]
        val ratingLabel = HtmlUrlCleaner.cleanText(doc.selectFirst("#rating_label")?.text().orEmpty())
        val ratingFloat = FLOAT_REGEX.find(ratingLabel)?.value?.toFloatOrNull()
        val language = info["Language"].orEmpty()
        val posted = info["Posted"].orEmpty()
        val fileSize = info["File Size"].orEmpty().ifBlank { null }

        val description = buildList {
            if (japaneseTitle.isNotEmpty() && japaneseTitle != title) add(japaneseTitle)
            val meta = listOfNotNull(
                categoryLabel.takeIf { it.isNotEmpty() },
                posted.takeIf { it.isNotEmpty() },
                fileSize,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) add(meta)
        }.joinToString("\n")

        val item = MediaItem(
            siteId = siteId,
            id = gid,
            url = canonical,
            kind = MediaKind.COMIC,
            title = title,
            coverUrl = cover,
            pageCount = pageCount,
            rating = ratingFloat,
            dateLabel = posted.ifBlank { null },
            author = authors.firstOrNull(),
            tags = tags,
            language = language.ifBlank { null },
            extra = buildMap {
                if (japaneseTitle.isNotEmpty()) put("japaneseTitle", japaneseTitle)
                if (categoryLabel.isNotEmpty()) put("category", categoryLabel)
                if (ratingLabel.isNotEmpty()) put("ratingLabel", ratingLabel)
            },
        )

        return GalleryDetail(
            item = item,
            description = description,
            pageCount = pageCount,
            pages = previewPages,
            tags = tags,
            authors = authors,
            characters = characters,
            categories = categories,
            previewPageSize = previewPages.size,
            fileSizeLabel = fileSize,
        )
    }

    internal fun parsePreviewLinks(doc: Document): List<GalleryPage> =
        doc.select("#gdt a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href")
            val index = extractPageIndex(href) ?: return@mapNotNull null
            val thumb = anchor.selectFirst("img")?.let { img ->
                resolveHttps(img.attr("data-src").ifBlank { img.attr("src") }, href)
            }
            GalleryPage(
                index = index,
                pageUrl = href,
                thumbUrl = thumb?.takeIf { it.isNotEmpty() },
            )
        }.distinctBy { it.index }.sortedBy { it.index }

    internal fun parsePageImage(
        body: String,
        pageUrl: String,
        expectedIndex: Int,
        preferOriginal: Boolean,
    ): GalleryPage {
        checkAccess(body)
        val doc = Jsoup.parse(body, pageUrl)
        val displayUrl = doc.selectFirst("img#img")?.attr("src")?.let { resolveHttps(it, pageUrl) }.orEmpty()
        if (displayUrl.contains("509.gif", ignoreCase = true) ||
            displayUrl.contains("509s.gif", ignoreCase = true)
        ) {
            throw SiteException.QuotaExceeded("原站图片配额已用尽，请稍后重试", siteId = siteId)
        }
        if (displayUrl.isBlank()) {
            throw SiteException.ParseError("原站未返回 HTTPS 图片地址，请重试此页", siteId = siteId)
        }

        val reloadToken = NL_TOKEN_REGEX.find(body)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val originalUrl = doc.selectFirst("a[href*=fullimg.php]")
            ?.absUrl("href")
            ?.takeIf { ComicUrl.parseStrictHttps(it) != null }
        val originalSize = ORIGINAL_SIZE_REGEX.find(doc.body().text())
            ?.groupValues?.get(1)
            ?.let { HtmlUrlCleaner.cleanText(it) }
            ?.takeIf { it.isNotBlank() }

        val chosenImageUrl = if (preferOriginal && !originalUrl.isNullOrBlank()) originalUrl else displayUrl

        return GalleryPage(
            index = expectedIndex,
            pageUrl = pageUrl,
            imageUrl = chosenImageUrl,
            originalImageUrl = originalUrl,
            reloadToken = reloadToken,
            originalSizeLabel = originalSize,
        )
    }

    private fun extractNextCursor(doc: Document, baseUrl: String): String? {
        doc.selectFirst("a#unext[href]")?.absUrl("href")?.let { candidate ->
            val parsed = ComicUrl.parseStrictHttps(candidate)
            if (parsed != null && descriptor.matchesHost(parsed.host)) {
                return candidate
            }
        }
        return doc.select(".ptt a[href]")
            .lastOrNull()
            ?.takeIf { it.text().contains('>') }
            ?.absUrl("href")
            ?.takeIf { candidate ->
                candidate != baseUrl &&
                    ComicUrl.parseStrictHttps(candidate)?.let { descriptor.matchesHost(it.host) } == true
            }
    }

    private fun canonicalGalleryUrl(raw: String): String? {
        val parsed = ComicUrl.parseStrictHttps(raw)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        val match = GALLERY_PATH_REGEX.matchEntire(parsed.encodedPath) ?: return null
        return "https://${descriptor.defaultHost}/g/${match.groupValues[1]}/${match.groupValues[2]}/"
    }

    private fun extractPageIndex(raw: String): Int? {
        val parsed = ComicUrl.parseStrictHttps(raw)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        return IMAGE_PATH_REGEX.matchEntire(parsed.encodedPath)
            ?.groupValues?.get(3)?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun resolveHttps(raw: String, baseUrl: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val resolved = ComicUrl.parse(baseUrl)?.resolve(trimmed) ?: ComicUrl.parse(trimmed) ?: return ""
        return if (resolved.isHttps) resolved.toString() else ""
    }

    private fun extractCssImageUrl(style: String, baseUrl: String): String =
        CSS_URL_REGEX.find(style)?.groupValues?.get(1)?.let { resolveHttps(it, baseUrl) }.orEmpty()

    private fun extractImageDimension(element: org.jsoup.nodes.Element?, attr: String): Int {
        if (element == null) return 0
        element.attr(attr).toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val style = element.attr("style")
        if (style.isNotBlank()) {
            Regex("""$attr\s*:\s*(\d+)px""", RegexOption.IGNORE_CASE)
                .find(style)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return 0
    }

    private fun formatSearchTag(raw: String, exclude: Boolean): String? {
        val cleaned = raw.trim().let { if (exclude) it.removePrefix("-").trim() else it }
        if (cleaned.isEmpty()) return null
        val prefix = if (exclude) "-" else ""
        if (cleaned.contains('"')) return "$prefix$cleaned"
        val colonIdx = cleaned.indexOf(':')
        if (colonIdx > 0 && colonIdx < cleaned.lastIndex) {
            val ns = cleaned.substring(0, colonIdx).trim()
            val value = cleaned.substring(colonIdx + 1).trim()
            if (ns.isNotEmpty() && value.isNotEmpty()) {
                return if (value.any(Char::isWhitespace)) {
                    "$prefix$ns:\"$value\""
                } else {
                    "$prefix$ns:$value"
                }
            }
        }
        return if (cleaned.any(Char::isWhitespace)) {
            "$prefix\"$cleaned\""
        } else {
            "$prefix$cleaned"
        }
    }

    private fun buildPrimaryQuery(request: FeedRequest): String {
        val query = request.query?.trim().orEmpty()
        if (query.isEmpty()) return ""
        return when (request.scope) {
            SearchScope.AUTHOR -> {
                if (query.startsWith("uploader:", ignoreCase = true)) query
                else "uploader:\"${query.replace("\"", "\\\"")}\""
            }
            SearchScope.ALL, SearchScope.TAG, SearchScope.CHARACTER -> query
        }
    }

    private fun isPopularRequest(request: FeedRequest): Boolean =
        request.section.equals("popular", ignoreCase = true) ||
            (request.sort == FeedSort.POPULAR && request.query.isNullOrBlank() && request.category.isNullOrBlank())

    private fun feedHeading(request: FeedRequest): String {
        val category = request.category
        val section = request.section
        val query = request.query
        return when {
            isPopularRequest(request) -> "热门画廊"
            !category.isNullOrBlank() -> CATEGORIES[resolveCategoryKey(category)] ?: category
            section != null && section in CATEGORIES -> CATEGORIES[section] ?: "画廊分类"
            !query.isNullOrBlank() -> query
            else -> "最新画廊"
        }
    }

    private fun validateFilters(filters: com.raven.app.core.model.AdvancedSearchFilters) {
        val minP = filters.minPages ?: 0
        val maxP = filters.maxPages ?: 0
        val minR = filters.minRating ?: 0
        if (minP < 0 || maxP < 0 || (minP > 0 && maxP > 0 && minP > maxP)) {
            throw SiteException.ParseError("页数筛选范围无效", siteId = siteId)
        }
        if (minR != 0 && minR !in 1..5) {
            throw SiteException.ParseError("评分筛选必须在 1 到 5 之间", siteId = siteId)
        }
    }

    private fun previewCacheKey(canonical: String, bucket: Int): String = "$canonical|p=$bucket"

    private fun sanitizeAndValidateCookies(rawCookies: Map<String, String>): Map<String, String> {
        val filtered = LinkedHashMap<String, String>()
        for ((rawName, rawValue) in rawCookies) {
            val name = rawName.trim()
            val value = rawValue.trim()
            if (name in ALLOWED_SESSION_COOKIES && value.isNotEmpty()) {
                filtered[name] = value
            }
        }
        if (!hasValidIdentity(filtered)) {
            throw SiteException.AuthRequired(
                message = "请填写有效的 ipb_member_id 和 ipb_pass_hash，ExHentai 可同时填写 igneous",
                siteId = siteId,
            )
        }
        if (!filtered.values.all(::isValidCookieValue)) {
            throw SiteException.AuthRequired(
                message = "Cookie 字段格式无效，请重新复制",
                siteId = siteId,
            )
        }
        return filtered
    }

    companion object {
        const val OFFICIAL_LOGIN_URL: String = "https://forums.e-hentai.org/index.php?act=Login"
        const val ALL_CATEGORIES_MASK: Int = 1023

        val ALLOWED_AUTH_HOSTS: Set<String> = setOf(
            "forums.e-hentai.org",
            "e-hentai.org",
            "exhentai.org",
            "www.e-hentai.org",
            "www.exhentai.org",
        )

        val REQUIRED_COOKIES: Set<String> = setOf("ipb_member_id", "ipb_pass_hash")
        val OPTIONAL_COOKIES: Set<String> = setOf("igneous", "sk")
        val ALLOWED_SESSION_COOKIES: Set<String> = REQUIRED_COOKIES + OPTIONAL_COOKIES

        val CATEGORIES: LinkedHashMap<String, String> = linkedMapOf(
            "2" to "同人志",
            "4" to "漫画",
            "8" to "画师 CG",
            "16" to "游戏 CG",
            "512" to "西方作品",
            "256" to "非成人",
            "32" to "图集",
            "64" to "Cosplay",
            "128" to "真人",
            "1" to "其他",
        )

        val SUPPORTED_FILTERS: Set<AdvancedFilterField> = setOf(
            AdvancedFilterField.INCLUDE_TAGS,
            AdvancedFilterField.EXCLUDE_TAGS,
            AdvancedFilterField.CATEGORY,
            AdvancedFilterField.PAGES,
            AdvancedFilterField.RATING,
            AdvancedFilterField.LANGUAGE,
        )

        val CAPABILITIES: Set<SiteCapability> = setOf(
            SiteCapability.FEED,
            SiteCapability.EXPLORE,
            SiteCapability.SEARCH,
            SiteCapability.ADVANCED_SEARCH,
            SiteCapability.TAGS,
            SiteCapability.AUTHORS,
            SiteCapability.CHARACTERS,
            SiteCapability.AUTH,
            SiteCapability.COOKIE_IMPORT,
            SiteCapability.LANGUAGE_FILTER,
            SiteCapability.ORIGINAL_IMAGES,
        )

        private val CATEGORY_ALIASES: Map<String, String> = mapOf(
            "doujinshi" to "2",
            "manga" to "4",
            "artist cg" to "8",
            "artistcg" to "8",
            "game cg" to "16",
            "gamecg" to "16",
            "western" to "512",
            "non-h" to "256",
            "image set" to "32",
            "imageset" to "32",
            "cosplay" to "64",
            "asian porn" to "128",
            "misc" to "1",
        )

        private val VALID_CURSOR_PATHS: Set<String> = setOf("/", "/popular", "/popular/")
        private val GALLERY_PATH_REGEX = Regex("^/g/(\\d+)/([a-fA-F0-9]+)/?$")
        private val IMAGE_PATH_REGEX = Regex("^/s/([a-fA-F0-9]+)/(\\d+)-(\\d+)/?$")
        private val PASS_HASH_REGEX = Regex("^[A-Za-z0-9_-]{16,128}$")
        private val SEARCH_TOKEN_REGEX = Regex("""-?(?:[^\s"]|"[^"]*")+""")
        private val UPLOADER_QUERY_REGEX = Regex("""^uploader:"(.+)"$""", RegexOption.IGNORE_CASE)
        private val PAGES_COUNT_REGEX = Regex("""([\d,]+)\s+pages?""", RegexOption.IGNORE_CASE)
        private val DIGITS_REGEX = Regex("""[\d,]+""")
        private val FLOAT_REGEX = Regex("""\d+(?:\.\d+)?""")
        private val CSS_URL_REGEX = Regex("""url\(\s*['"]?([^)'"\s]+)""")
        private val NL_TOKEN_REGEX = Regex("""nl\(['"]([A-Za-z0-9-]+)['"]\)""")
        private val ORIGINAL_SIZE_REGEX = Regex(
            """(?:Load|Download)\s+original[^,]*,\s*size:\s*([\d.]+\s*[KMGT]?B)""",
            RegexOption.IGNORE_CASE,
        )

        fun computeFCats(categoryBit: Int): Int = ALL_CATEGORIES_MASK xor categoryBit

        fun resolveCategoryKey(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed in CATEGORIES) return trimmed
            CATEGORIES.entries.firstOrNull { it.value.equals(trimmed, ignoreCase = true) }?.let { return it.key }
            return CATEGORY_ALIASES[trimmed.lowercase()]
        }

        fun buildLanguageTerms(language: ComicLanguage): List<String> {
            val keys = language.languageKeys()
            if (keys.isEmpty()) return emptyList()
            val prefix = if (keys.size > 1) "~" else ""
            return keys.map { "${prefix}language:$it$" }
        }

        fun hasValidIdentity(cookies: Map<String, String>): Boolean {
            val memberId = cookies["ipb_member_id"]?.trim()?.toLongOrNull() ?: return false
            val passHash = cookies["ipb_pass_hash"]?.trim() ?: return false
            return memberId > 0L && PASS_HASH_REGEX.matches(passHash)
        }

        fun parseCookieHeader(raw: String): Map<String, String> {
            val cleaned = raw.trim().replaceFirst(Regex("^cookie:\\s*", RegexOption.IGNORE_CASE), "")
            val out = LinkedHashMap<String, String>()
            for (part in cleaned.split(';')) {
                val pair = part.trim().split('=', limit = 2)
                if (pair.size == 2) {
                    val name = pair[0].trim()
                    val value = pair[1].trim()
                    if (name in ALLOWED_SESSION_COOKIES && value.isNotEmpty()) {
                        out[name] = value
                    }
                }
            }
            return out
        }

        fun isValidCookieValue(value: String): Boolean =
            value.isNotEmpty() && value.all { it.code in 33..126 && it != ';' }

        fun buildSections(): List<SiteSectionSpec> = buildList {
            add(SiteSectionSpec(id = "latest", title = "最新画廊", defaultSort = FeedSort.NEWEST))
            add(SiteSectionSpec(id = "popular", title = "热门画廊", defaultSort = FeedSort.POPULAR))
            CATEGORIES.forEach { (key, title) ->
                add(SiteSectionSpec(id = key, title = title, category = key, defaultSort = FeedSort.NEWEST))
            }
        }

        fun buildDescriptor(
            id: SiteId,
            displayName: String,
            defaultHost: String,
            accessNote: String? = null,
            requiresAuthGate: Boolean = false,
        ): SiteDescriptor = SiteDescriptor(
            id = id,
            displayName = displayName,
            shortName = displayName,
            primaryKind = MediaKind.COMIC,
            supportedKinds = setOf(MediaKind.COMIC),
            defaultHost = defaultHost,
            capabilities = CAPABILITIES,
            accessNote = accessNote,
            feedSorts = listOf(FeedSort.NEWEST),
            sections = buildSections(),
            searchScopes = listOf(SearchScope.ALL, SearchScope.TAG, SearchScope.AUTHOR),
            supportedFilters = SUPPORTED_FILTERS,
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.RAVEN_API_UA,
                referer = "https://$defaultHost/",
                mediaReferer = "https://$defaultHost/",
                mediaDomains = setOf("ehgt.org", "hath.network"),
                disableFeedSnapshot = requiresAuthGate,
                requiresAuthGate = requiresAuthGate,
            ),
        )

        private fun tokenizeSearchTerms(query: String): List<String> {
            if (query.isBlank()) return emptyList()
            return SEARCH_TOKEN_REGEX.findAll(query).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        }
    }
}
