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
import com.raven.app.core.sitespi.DualLanguageMerger
import com.raven.app.core.sitespi.DualMergeStrategy
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.LruCache
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import com.raven.app.core.sitespi.StreamSlice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * nhentai (`SiteId.NHENTAI`) plugin backed by the public `/api/v2/` REST API.
 * Features validated `/api/v2/cdn` server allowlisting, duplicate image extension cleanup via
 * [HtmlUrlCleaner.deduplicateImageExtension], and [DualLanguageMerger] for `CHINESE_AND_JAPANESE`.
 */
class NhentaiSitePlugin(
    private val httpClient: HttpClientAdapter,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cdnMutex = Mutex()
    private val cdnCache = LruCache<String, NhentaiCdn>(
        maxEntries = 1,
        ttlMs = CDN_CACHE_TTL_MS,
        clockMs = clockMs,
    )
    private val galleryCache = LruCache<String, JsonObject>(maxEntries = 32, clockMs = clockMs)
    private val dualStreamPageCache = LruCache<String, StreamSlice<MediaItem>>(
        maxEntries = 12,
        ttlMs = DUAL_STREAM_CACHE_TTL_MS,
        clockMs = clockMs,
    )

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(
                FeedRequest(siteId = SiteId.NHENTAI, section = "latest", language = language, page = 1),
            )
            return HomeFeed(siteId = SiteId.NHENTAI, primaryFeedPage = page)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage = executeFeed(request)

        override fun supportedSorts(request: FeedRequest): List<FeedSort> =
            if (isPopularSection(request)) listOf(FeedSort.POPULAR)
            else listOf(FeedSort.NEWEST, FeedSort.POPULAR, FeedSort.TRENDING)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = executeFeed(request)

        override val supportedScopes: Set<SearchScope> = setOf(
            SearchScope.ALL,
            SearchScope.TAG,
            SearchScope.AUTHOR,
            SearchScope.CHARACTER,
        )

        override val supportedFilters: Set<AdvancedFilterField> = SUPPORTED_FILTERS

        override val supportsExcludeTags: Boolean = true

        override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> =
            listOf(FeedSort.NEWEST, FeedSort.POPULAR, FeedSort.TRENDING)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val id = extractGalleryId(url)
                ?: throw SiteException.ParseError("无法识别 nhentai 画廊地址：$url", siteId = SiteId.NHENTAI)
            val info = fetchGalleryInfo(id, forceRefresh = false)
            val cdn = fetchCdn(forceRefresh = false)
            return parseGalleryDetail(info, cdn)
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.NHENTAI)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val pageUrl = "${descriptor.baseUrl}/g/${detail.item.id}/$pageIndex/"
            return resolvePageImage(pageUrl = pageUrl, preferOriginal = false, forceReload = forceReload)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val (id, page) = extractPageIdentity(pageUrl)
                ?: throw SiteException.ParseError("无法识别 nhentai 页码：$pageUrl", siteId = SiteId.NHENTAI)
            val reloadQuery = ComicUrl.parse(pageUrl)?.queryParameter("reload") == "1"
            val refresh = forceReload || reloadQuery
            val info = fetchGalleryInfo(id, forceRefresh = refresh)
            val cdn = fetchCdn(forceRefresh = refresh)
            return parsePageImage(info, page, cdn)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            GALLERY_PATH_REGEX.matchEntire(parsed.encodedPath)?.let { m ->
                return "${descriptor.baseUrl}/g/${m.groupValues[1]}/"
            }
            PAGE_PATH_REGEX.matchEntire(parsed.encodedPath)?.let { m ->
                val page = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
                return "${descriptor.baseUrl}/g/${m.groupValues[1]}/$page/"
            }
            return ComicUrl.parseStrictHttps("${descriptor.baseUrl}${parsed.encodedPath}")
                ?.withQuery(parsed.queryParams)
                ?.withFragment(null)
                ?.toString()
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            GALLERY_PATH_REGEX.matchEntire(parsed.encodedPath)?.let { m ->
                val id = m.groupValues[1]
                return ResolvedLink.Gallery(
                    siteId = SiteId.NHENTAI,
                    id = id,
                    canonicalUrl = "${descriptor.baseUrl}/g/$id/",
                )
            }
            PAGE_PATH_REGEX.matchEntire(parsed.encodedPath)?.let { m ->
                val id = m.groupValues[1]
                val page = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
                return ResolvedLink.Gallery(
                    siteId = SiteId.NHENTAI,
                    id = id,
                    canonicalUrl = "${descriptor.baseUrl}/g/$id/$page/",
                    pageIndex = page,
                )
            }
            parsed.queryParameter("q")?.takeIf { it.isNotBlank() }?.let { q ->
                return ResolvedLink.Feed(
                    FeedRequest(siteId = SiteId.NHENTAI, query = q, scope = SearchScope.ALL),
                )
            }
            val segments = parsed.pathSegments
            if (segments.size == 2 && segments[0] in LINK_FACET_TYPES) {
                val type = segments[0]
                val decoded = segments[1].replace('-', ' ').trim()
                if (decoded.isEmpty()) return null
                return when (type) {
                    "artist" -> ResolvedLink.Feed(
                        FeedRequest(siteId = SiteId.NHENTAI, query = decoded, scope = SearchScope.AUTHOR),
                    )
                    "category" -> ResolvedLink.Feed(
                        FeedRequest(siteId = SiteId.NHENTAI, category = segments[1].lowercase()),
                    )
                    else -> ResolvedLink.Feed(
                        FeedRequest(
                            siteId = SiteId.NHENTAI,
                            query = "$type:\"$decoded\"",
                            scope = if (type == "character") SearchScope.CHARACTER else SearchScope.TAG,
                        ),
                    )
                }
            }
            return null
        }
    }

    // -------------------------------------------------------------------------
    // Core Feed & API Implementation
    // -------------------------------------------------------------------------

    private suspend fun executeFeed(request: FeedRequest): FeedPage {
        validatePage(request.page)
        if (request.language == ComicLanguage.CHINESE_AND_JAPANESE) {
            return loadDualLanguageFeed(request)
        }
        val url = resolveApiUrl(request)
        val element = fetchJson(url)
        val cdn = fetchCdn(forceRefresh = false)
        val parsed = parseFeed(element, request.page, cdn, feedHeading(request))
        val excludes = request.advancedFilters.excludeTags
        if (excludes.isEmpty()) return parsed
        return parsed.copy(
            items = parsed.items.filter { item -> ContentFilter.isAllowed(item, excludes) },
        )
    }

    private suspend fun loadDualLanguageFeed(request: FeedRequest): FeedPage {
        val isPopular = isPopularSection(request) ||
            request.sort == FeedSort.POPULAR ||
            request.sort == FeedSort.TRENDING
        val strategy = if (isPopular) DualMergeStrategy.INTERLEAVED else DualMergeStrategy.DESCENDING_NUMERIC_ID

        suspend fun fetchStreamSlice(language: ComicLanguage, page: Int, excludeChinese: Boolean): StreamSlice<MediaItem> {
            val baseReq = request.copy(language = language, page = page, cursor = null)
            var url = ComicUrl.requireParse(resolveApiUrl(baseReq), SiteId.NHENTAI)
            if (excludeChinese) {
                val existing = url.queryParameter("query").orEmpty()
                url = url.setQueryParameter("query", "$existing -language:chinese".trim())
            }
            val finalUrl = url.toString()
            dualStreamPageCache.get(finalUrl)?.let { return it }
            val element = fetchJson(finalUrl)
            val cdn = fetchCdn(forceRefresh = false)
            val parsed = parseFeed(element, page, cdn, feedHeading(request))
            val excludes = request.advancedFilters.excludeTags
            val filteredItems = if (excludes.isEmpty()) {
                parsed.items
            } else {
                parsed.items.filter { item -> ContentFilter.isAllowed(item, excludes) }
            }
            val slice = StreamSlice(items = filteredItems, hasNext = parsed.hasMore)
            dualStreamPageCache.put(finalUrl, slice)
            return slice
        }

        val merged = DualLanguageMerger.merge(
            cursorRaw = request.cursor,
            pageSize = DUAL_MERGE_PAGE_SIZE,
            strategy = strategy,
            idOf = { it.id },
            fetchStreamA = { p -> fetchStreamSlice(ComicLanguage.CHINESE, p, excludeChinese = false) },
            fetchStreamB = { p -> fetchStreamSlice(ComicLanguage.JAPANESE, p, excludeChinese = true) },
        )

        return FeedPage(
            items = merged.items,
            nextPage = if (merged.hasMore) request.page + 1 else null,
            nextCursor = merged.nextCursor,
            hasMore = merged.hasMore,
            title = feedHeading(request),
        )
    }

    internal fun resolveApiUrl(request: FeedRequest): String {
        validatePage(request.page)
        val filters = request.advancedFilters
        val minPages = filters.minPages ?: 0
        val maxPages = filters.maxPages ?: 0
        if (minPages < 0 || maxPages < 0 || (minPages > 0 && maxPages > 0 && minPages > maxPages)) {
            throw SiteException.ParseError("页数筛选范围无效", siteId = SiteId.NHENTAI)
        }

        val primaryQuery = buildQueryTerm(request)
        val categoryValue = request.category?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.section?.takeIf { it in CATEGORIES }
            ?: filters.category?.trim()?.takeIf { it.isNotEmpty() }
        val languageTerm = request.language.languageKeys().firstOrNull()?.let { "language:$it" }.orEmpty()

        val rawTerms = buildList {
            if (primaryQuery.isNotEmpty()) add(primaryQuery)
            if (categoryValue != null) add("category:${categoryValue.lowercase()}")
            if (languageTerm.isNotEmpty()) add(languageTerm)
            filters.includeTags.mapNotNull { formatNhentaiTag(it, exclude = false) }.forEach(::add)
            filters.excludeTags.mapNotNull { formatNhentaiTag(it, exclude = true) }.forEach(::add)
            if (minPages > 0) add("pages:>=$minPages")
            if (maxPages > 0) add("pages:<=$maxPages")
        }.distinct()
        val anchoredTerms = if (rawTerms.isNotEmpty() && rawTerms.all { it.startsWith("-") }) {
            listOf("pages:>0") + rawTerms
        } else {
            rawTerms
        }
        val terms = anchoredTerms.joinToString(" ")

        val base = ComicUrl.requireParse("${descriptor.baseUrl}/api/v2", SiteId.NHENTAI)
        val popularSection = isPopularSection(request)
        return when {
            popularSection && terms.isEmpty() -> {
                base.addPathSegments("galleries/popular").toString()
            }
            terms.isNotEmpty() || request.sort == FeedSort.POPULAR || request.sort == FeedSort.TRENDING -> {
                val sortParam = when {
                    popularSection || request.sort == FeedSort.TRENDING -> "popular-today"
                    request.sort == FeedSort.POPULAR -> "popular"
                    else -> "date"
                }
                base.addPathSegment("search")
                    .addQueryParameter("query", terms.ifEmpty { "pages:>0" })
                    .addQueryParameter("sort", sortParam)
                    .addQueryParameter("page", request.page.toString())
                    .toString()
            }
            else -> {
                base.addPathSegment("galleries")
                    .addQueryParameter("page", request.page.toString())
                    .toString()
            }
        }
    }

    private fun formatNhentaiTag(raw: String, exclude: Boolean): String? {
        val cleaned = raw.trim().let { if (exclude) it.removePrefix("-").trim() else it }
        if (cleaned.isEmpty()) return null
        val prefix = if (exclude) "-" else ""
        if (cleaned.contains('"')) return "$prefix$cleaned"

        val colonIdx = cleaned.indexOf(':')
        if (colonIdx > 0 && colonIdx < cleaned.lastIndex) {
            val ns = cleaned.substring(0, colonIdx).trim().lowercase()
            val value = cleaned.substring(colonIdx + 1).trim()
            if (ns.isNotEmpty() && value.isNotEmpty()) {
                return if (ns in NHENTAI_NAMESPACES) {
                    if (value.any(Char::isWhitespace)) "$prefix$ns:\"$value\"" else "$prefix$ns:$value"
                } else {
                    // Strip EH/Hitomi-only namespaces (e.g. female:/male:) so nHentai search matches the tag
                    val normalizedVal = value.replace('_', ' ').replace(MULTI_SPACE_REGEX, " ").trim()
                    if (normalizedVal.any(Char::isWhitespace)) "$prefix\"$normalizedVal\"" else "$prefix$normalizedVal"
                }
            }
        }
        return if (cleaned.any(Char::isWhitespace)) {
            "$prefix\"$cleaned\""
        } else {
            "$prefix$cleaned"
        }
    }

    private fun buildQueryTerm(request: FeedRequest): String {
        val raw = request.query?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return when (request.scope) {
            SearchScope.AUTHOR -> formatScopedQuery(defaultPrefix = "artist", raw = raw)
            SearchScope.CHARACTER -> formatScopedQuery(defaultPrefix = "character", raw = raw)
            SearchScope.TAG -> formatScopedQuery(defaultPrefix = "tag", raw = raw)
            SearchScope.ALL -> raw
        }
    }

    private fun formatScopedQuery(defaultPrefix: String, raw: String): String {
        val cleaned = raw.replace("\"", "").trim()
        val colonIdx = cleaned.indexOf(':')
        if (colonIdx > 0) {
            val ns = cleaned.substring(0, colonIdx).trim()
            val value = cleaned.substring(colonIdx + 1).trim()
            return if (value.any(Char::isWhitespace)) "$ns:\"$value\"" else "$ns:$value"
        }
        return "$defaultPrefix:\"$cleaned\""
    }

    private suspend fun fetchCdn(forceRefresh: Boolean): NhentaiCdn = cdnMutex.withLock {
        if (!forceRefresh) {
            cdnCache.get(CDN_CACHE_KEY)?.let { return@withLock it }
        }
        val element = fetchJson("${descriptor.baseUrl}/api/v2/cdn")
        val parsed = parseCdn(element.asObjectOrThrow("nhentai CDN 响应格式无效"))
        cdnCache.put(CDN_CACHE_KEY, parsed)
        parsed
    }

    private suspend fun fetchGalleryInfo(id: String, forceRefresh: Boolean): JsonObject {
        if (!forceRefresh) {
            galleryCache.get(id)?.let { return it }
        }
        val element = fetchJson("${descriptor.baseUrl}/api/v2/galleries/$id")
        val obj = element.asObjectOrThrow("nhentai 画廊详情格式无效")
        galleryCache.put(id, obj)
        return obj
    }

    private suspend fun fetchJson(url: String): JsonElement {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.NHENTAI,
                kind = SiteRequestKind.API,
                headers = mapOf("Cache-Control" to "no-cache"),
                allowCache = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw e
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "nhentai 网络请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.NHENTAI,
                cause = e,
            )
        }

        when (response.code) {
            429 -> throw SiteException.RateLimited(
                retryAfterSeconds = response.retryAfterSeconds,
                message = "nhentai 请求频率已达限制，请稍后重试",
                siteId = SiteId.NHENTAI,
            )
            401 -> throw SiteException.AuthRequired(
                message = "nhentai 需要登录验证",
                siteId = SiteId.NHENTAI,
            )
            403 -> {
                val body = response.bodyText()
                val isChallenge = response.header("cf-mitigated").equals("challenge", ignoreCase = true) ||
                    body.contains("cf-challenge", ignoreCase = true) ||
                    body.contains("Just a moment", ignoreCase = true) ||
                    body.contains("Checking your browser", ignoreCase = true) ||
                    body.contains("DDoS-Guard", ignoreCase = true)
                throw SiteException.Forbidden(
                    isChallenge = isChallenge,
                    message = if (isChallenge) "nhentai 触发浏览器验证挑战" else "nhentai 暂时拒绝访问，请稍后重试",
                    siteId = SiteId.NHENTAI,
                )
            }
            404 -> throw SiteException.NotFound(
                message = "该画廊已被原站移除或不存在",
                siteId = SiteId.NHENTAI,
            )
        }
        if (!response.isSuccessful) {
            throw SiteException.Network("nhentai 读取失败：HTTP ${response.code}", siteId = SiteId.NHENTAI)
        }
        return try {
            json.parseToJsonElement(response.bodyText())
        } catch (e: Exception) {
            throw SiteException.ParseError("nhentai JSON 解析失败：${e.message}", siteId = SiteId.NHENTAI, cause = e)
        }
    }

    // -------------------------------------------------------------------------
    // Parsing Helpers
    // -------------------------------------------------------------------------

    internal data class NhentaiCdn(
        val images: List<String>,
        val thumbs: List<String>,
    )

    internal fun parseCdn(obj: JsonObject): NhentaiCdn {
        fun validatedHosts(key: String): List<String> {
            val arr = obj[key]?.asArrayOrNull()
                ?: throw SiteException.ParseError("nhentai 未返回 $key 字段", siteId = SiteId.NHENTAI)
            val hosts = arr.mapNotNull { el ->
                val raw = el.stringOrNull()?.trim()?.trimEnd('/') ?: return@mapNotNull null
                val parsed = ComicUrl.parseStrictHttps(raw) ?: return@mapNotNull null
                val validHost = ALLOWED_CDN_HOST_REGEX.matches(parsed.host)
                val validPath = parsed.encodedPath == "/"
                if (validHost && validPath) raw else null
            }
            if (hosts.isEmpty()) {
                throw SiteException.ParseError("nhentai 未返回有效图片服务器（$key）", siteId = SiteId.NHENTAI)
            }
            return hosts
        }
        return NhentaiCdn(
            images = validatedHosts("image_servers"),
            thumbs = validatedHosts("thumb_servers"),
        )
    }

    internal fun parseFeed(
        element: JsonElement,
        page: Int,
        cdn: NhentaiCdn,
        heading: String,
    ): FeedPage {
        val itemsArray: JsonArray
        val totalPages: Int
        if (element is JsonArray) {
            itemsArray = element
            totalPages = 1
        } else {
            val obj = element.asObjectOrThrow("nhentai 列表响应格式无效")
            itemsArray = obj["result"]?.asArrayOrNull()
                ?: throw SiteException.ParseError("nhentai 未返回画廊列表", siteId = SiteId.NHENTAI)
            totalPages = (obj.intOrZero("num_pages")).coerceAtLeast(1)
        }
        val items = itemsArray.mapNotNull { el ->
            (el as? JsonObject)?.let { obj -> runCatching { parseMediaItem(obj, cdn) }.getOrNull() }
        }
        if (items.isEmpty() && itemsArray.isNotEmpty()) {
            throw SiteException.ParseError("nhentai 画廊项解析全部失败", siteId = SiteId.NHENTAI)
        }
        val hasMore = page < totalPages && items.isNotEmpty()
        return FeedPage(
            items = items,
            nextPage = if (hasMore) page + 1 else null,
            hasMore = hasMore,
            totalPages = totalPages,
            title = heading,
        )
    }

    internal fun parseGalleryDetail(obj: JsonObject, cdn: NhentaiCdn): GalleryDetail {
        val baseItem = parseMediaItem(obj, cdn)
        val mediaId = obj.stringOrEmpty("media_id")
        val pagesArray = obj["pages"]?.asArrayOrNull()
            ?: throw SiteException.ParseError("nhentai 未返回图页", siteId = SiteId.NHENTAI)
        val expectedNumPages = obj.intOrZero("num_pages")
        if (pagesArray.isEmpty() || (expectedNumPages > 0 && pagesArray.size != expectedNumPages)) {
            throw SiteException.ParseError("nhentai 图页数量不完整，请重试", siteId = SiteId.NHENTAI)
        }

        val galleryPages = pagesArray.mapIndexed { idx, el ->
            val pageObj = el.asObjectOrThrow("nhentai 图页数据无效")
            val number = pageObj.intOrZero("number")
            val expected = idx + 1
            if (number != expected) {
                throw SiteException.ParseError("原站页码顺序已变化，请刷新画廊", siteId = SiteId.NHENTAI)
            }
            val server = cdn.images[idx % cdn.images.size]
            val imageUrl = buildValidatedMediaUrl(server, pageObj.stringOrEmpty("path"), mediaId)
            val width = pageObj.intOrZero("width").takeIf { it > 0 }
            val height = pageObj.intOrZero("height").takeIf { it > 0 }
            GalleryPage(
                index = expected,
                pageUrl = "${baseItem.url}$expected/",
                imageUrl = imageUrl,
                width = width,
                height = height,
            )
        }

        val tags = mutableListOf<TagRef>()
        val authors = mutableListOf<AuthorRef>()
        val characters = mutableListOf<TagRef>()
        val categories = mutableListOf<TagRef>()
        val languages = mutableListOf<String>()

        obj["tags"]?.asArrayOrNull()?.forEach { raw ->
            val tagObj = raw.asObjectOrNull() ?: return@forEach
            val type = tagObj.stringOrEmpty("type").lowercase()
            val name = tagObj.stringOrEmpty("name")
            if (name.isBlank()) return@forEach
            val count = tagObj.intOrZero("count").takeIf { it > 0 }
            val searchQuery = "$type:\"${name.replace("\"", "")}\""
            val searchUrl = ComicUrl.requireParse(descriptor.baseUrl, SiteId.NHENTAI)
                .addPathSegment("search")
                .addQueryParameter("q", searchQuery)
                .toString()

            val tagRef = TagRef(
                slug = searchQuery,
                label = name,
                namespace = type,
                url = searchUrl,
                count = count,
            )
            tags.add(tagRef)
            when (type) {
                "language" -> if (name != "translated") languages.add(name)
                "artist" -> authors.add(AuthorRef(id = name, name = name, role = "artist", url = searchUrl))
                "character" -> characters.add(tagRef)
                "category" -> categories.add(tagRef)
            }
        }

        val japaneseTitle = obj["title"]?.asObjectOrNull()?.stringOrEmpty("japanese")
            ?.ifBlank { obj.stringOrEmpty("japanese_title") }
            .orEmpty()
        val favorites = obj.longOrNull("num_favorites")
        val languageLabel = languages.joinToString(" / ").ifBlank { null }
        val description = buildList {
            if (japaneseTitle.isNotEmpty() && japaneseTitle != baseItem.title) add(japaneseTitle)
            if (favorites != null) add("$favorites 人收藏")
        }.joinToString("\n")

        val enrichedItem = baseItem.copy(
            language = languageLabel,
            author = authors.firstOrNull(),
            tags = tags,
            likes = favorites ?: baseItem.likes,
            extra = baseItem.extra + buildMap {
                if (japaneseTitle.isNotEmpty()) put("japaneseTitle", japaneseTitle)
            },
        )

        return GalleryDetail(
            item = enrichedItem,
            description = description,
            pageCount = galleryPages.size,
            pages = galleryPages,
            tags = tags,
            authors = authors,
            characters = characters,
            categories = categories,
        )
    }

    internal fun parsePageImage(obj: JsonObject, page: Int, cdn: NhentaiCdn): GalleryPage {
        val pagesArray = obj["pages"]?.asArrayOrNull()
            ?: throw SiteException.ParseError("nhentai 未返回图页", siteId = SiteId.NHENTAI)
        if (page !in 1..pagesArray.size) {
            throw SiteException.NotFound("页码超出范围：$page / ${pagesArray.size}", siteId = SiteId.NHENTAI)
        }
        val pageObj = pagesArray[page - 1].asObjectOrThrow("nhentai 图页数据无效")
        if (pageObj.intOrZero("number") != page) {
            throw SiteException.ParseError("原站页码顺序已变化，请刷新画廊", siteId = SiteId.NHENTAI)
        }
        val id = obj.stringOrEmpty("id")
        val mediaId = obj.stringOrEmpty("media_id")
        val server = cdn.images[(page - 1) % cdn.images.size]
        val imageUrl = buildValidatedMediaUrl(server, pageObj.stringOrEmpty("path"), mediaId)
        val width = pageObj.intOrZero("width").takeIf { it > 0 }
        val height = pageObj.intOrZero("height").takeIf { it > 0 }
        return GalleryPage(
            index = page,
            pageUrl = "${descriptor.baseUrl}/g/$id/$page/",
            imageUrl = imageUrl,
            width = width,
            height = height,
        )
    }

    private fun parseMediaItem(obj: JsonObject, cdn: NhentaiCdn): MediaItem {
        val id = obj.stringOrEmpty("id")
        val mediaId = obj.stringOrEmpty("media_id")
        if (!DIGITS_ONLY_REGEX.matches(id) || !DIGITS_ONLY_REGEX.matches(mediaId)) {
            throw SiteException.ParseError("nhentai 画廊信息无效（id=$id, media_id=$mediaId）", siteId = SiteId.NHENTAI)
        }
        val titleObj = obj["title"]?.asObjectOrNull()
        val title = HtmlUrlCleaner.cleanText(
            obj.stringOrEmpty("english_title")
                .ifBlank { titleObj?.stringOrEmpty("english").orEmpty() }
                .ifBlank { obj.stringOrEmpty("japanese_title") }
                .ifBlank { titleObj?.stringOrEmpty("japanese").orEmpty() }
                .ifBlank { titleObj?.stringOrEmpty("pretty").orEmpty() },
        )
        if (title.isBlank()) {
            throw SiteException.ParseError("nhentai 画廊缺少标题（id=$id）", siteId = SiteId.NHENTAI)
        }

        val thumbEl = obj["thumbnail"]
        val thumbObj = thumbEl?.asObjectOrNull()
        val thumbPath = when {
            thumbObj != null -> thumbObj.stringOrEmpty("path")
            thumbEl != null -> thumbEl.stringOrNull().orEmpty()
            else -> ""
        }
        val thumbUrl = buildValidatedMediaUrl(cdn.thumbs.first(), thumbPath, mediaId)
        val thumbWidth = obj.intOrZero("thumbnail_width").takeIf { it > 0 }
            ?: thumbObj?.intOrZero("width")?.takeIf { it > 0 }
            ?: 0
        val thumbHeight = obj.intOrZero("thumbnail_height").takeIf { it > 0 }
            ?: thumbObj?.intOrZero("height")?.takeIf { it > 0 }
            ?: 0
        val aspectRatio = if (thumbWidth > 0 && thumbHeight > 0) thumbWidth.toFloat() / thumbHeight.toFloat() else null

        val numPages = obj.intOrZero("num_pages").takeIf { it > 0 }
        val numFavorites = obj.longOrNull("num_favorites")

        return MediaItem(
            siteId = SiteId.NHENTAI,
            id = id,
            url = "${descriptor.baseUrl}/g/$id/",
            kind = MediaKind.COMIC,
            title = title,
            coverUrl = thumbUrl,
            coverAspectRatio = aspectRatio,
            pageCount = numPages,
            likes = numFavorites,
        )
    }

    internal fun buildValidatedMediaUrl(host: String, rawPath: String, mediaId: String): String {
        val cleaned = HtmlUrlCleaner.deduplicateImageExtension(rawPath.trim().trimStart('/'))
        val match = GALLERY_SUBPATH_REGEX.matchEntire(cleaned)
        if (match == null || match.groupValues[1] != mediaId) {
            throw SiteException.ParseError("nhentai 图片地址无效：$rawPath", siteId = SiteId.NHENTAI)
        }
        return "${host.trimEnd('/')}/$cleaned"
    }

    private fun extractGalleryId(raw: String): String? {
        val parsed = ComicUrl.parseStrictHttps(raw)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        return GALLERY_PATH_REGEX.matchEntire(parsed.encodedPath)?.groupValues?.get(1)
    }

    private fun extractPageIdentity(raw: String): Pair<String, Int>? {
        val parsed = ComicUrl.parseStrictHttps(raw)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        val match = PAGE_PATH_REGEX.matchEntire(parsed.encodedPath) ?: return null
        val page = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return match.groupValues[1] to page
    }

    private fun isPopularSection(request: FeedRequest): Boolean =
        request.section.equals("popular", ignoreCase = true)

    private fun feedHeading(request: FeedRequest): String {
        val category = request.category
        val section = request.section
        val query = request.query
        return when {
            isPopularSection(request) -> "今日热门"
            !category.isNullOrBlank() -> CATEGORIES[category.lowercase()] ?: category
            section != null && section in CATEGORIES -> CATEGORIES[section] ?: section
            !query.isNullOrBlank() -> query
            else -> "最新画廊"
        }
    }

    private fun validatePage(page: Int) {
        if (page !in 1..MAX_PAGE) {
            throw SiteException.ParseError("页码无效：$page", siteId = SiteId.NHENTAI)
        }
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { this is JsonNull }?.contentOrNull

    private fun JsonElement.asObjectOrThrow(message: String): JsonObject =
        (this as? JsonObject) ?: throw SiteException.ParseError(message, siteId = SiteId.NHENTAI)

    private fun JsonObject.stringOrEmpty(key: String): String =
        this[key]?.stringOrNull().orEmpty()

    private fun JsonObject.intOrZero(key: String): Int {
        val prim = this[key] as? JsonPrimitive ?: return 0
        if (prim is JsonNull) return 0
        return prim.intOrNull ?: prim.contentOrNull?.toIntOrNull() ?: 0
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val prim = this[key] as? JsonPrimitive ?: return null
        if (prim is JsonNull) return null
        return prim.longOrNull ?: prim.contentOrNull?.toLongOrNull()
    }

    companion object {
        private const val CDN_CACHE_KEY = "cdn"
        private const val CDN_CACHE_TTL_MS = 30 * 60 * 1000L
        private const val DUAL_STREAM_CACHE_TTL_MS = 3 * 60 * 1000L
        private const val DUAL_MERGE_PAGE_SIZE = 24
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val NHENTAI_NAMESPACES = setOf(
            "tag", "artist", "character", "parody", "group", "language", "category", "pages", "uploaded",
        )
        private const val MAX_PAGE = 1_000_000

        private val ALLOWED_CDN_HOST_REGEX = Regex("""^[it]\d*\.nhentai\.net$""")
        private val GALLERY_PATH_REGEX = Regex("""^/g/(\d+)/?$""")
        private val PAGE_PATH_REGEX = Regex("""^/g/(\d+)/(\d+)/?$""")
        private val GALLERY_SUBPATH_REGEX = Regex(
            """^galleries/(\d+)/[A-Za-z0-9_.-]+\.(?:webp|jpg|jpeg|png|gif)$""",
            RegexOption.IGNORE_CASE,
        )
        private val DIGITS_ONLY_REGEX = Regex("""^\d+$""")
        private val LINK_FACET_TYPES = setOf("tag", "artist", "group", "parody", "character", "language", "category")

        val CATEGORIES: LinkedHashMap<String, String> = linkedMapOf(
            "doujinshi" to "同人志",
            "manga" to "漫画",
            "artistcg" to "画师 CG",
            "gamecg" to "游戏 CG",
        )

        val SUPPORTED_FILTERS: Set<AdvancedFilterField> = setOf(
            AdvancedFilterField.INCLUDE_TAGS,
            AdvancedFilterField.EXCLUDE_TAGS,
            AdvancedFilterField.CATEGORY,
            AdvancedFilterField.PAGES,
            AdvancedFilterField.LANGUAGE,
        )

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.NHENTAI,
            displayName = "nhentai",
            shortName = "nhentai",
            primaryKind = MediaKind.COMIC,
            supportedKinds = setOf(MediaKind.COMIC),
            defaultHost = "nhentai.net",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.ADVANCED_SEARCH,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.CHARACTERS,
                SiteCapability.LANGUAGE_FILTER,
            ),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR, FeedSort.TRENDING),
            sections = buildList {
                add(SiteSectionSpec(id = "latest", title = "最新画廊", defaultSort = FeedSort.NEWEST))
                add(SiteSectionSpec(id = "popular", title = "今日热门", defaultSort = FeedSort.POPULAR))
                CATEGORIES.forEach { (key, label) ->
                    add(SiteSectionSpec(id = key, title = label, category = key, defaultSort = FeedSort.NEWEST))
                }
            },
            searchScopes = listOf(SearchScope.ALL, SearchScope.TAG, SearchScope.AUTHOR, SearchScope.CHARACTER),
            supportedFilters = SUPPORTED_FILTERS,
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.RAVEN_API_UA,
                referer = "https://nhentai.net/",
                mediaReferer = "https://nhentai.net/",
                mediaDomains = setOf("nhentai.net"),
            ),
        )
    }
}
