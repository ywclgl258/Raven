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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Hitomi (`SiteId.HITOMI`) plugin reading public binary `.nozomi` indexes via HTTP 206 Range requests,
 * 464-byte SHA-256 disk B-Tree keyword search, regex-only `gg.js` routing (ZERO JS evaluation),
 * and `galleries/<id>.js` JSON metadata.
 */
class HitomiSitePlugin(
    private val httpClient: HttpClientAdapter,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val routingMutex = Mutex()
    private val routingCache = LruCache<String, RoutingConfig>(
        maxEntries = 1,
        ttlMs = GG_ROUTING_TTL_MS,
        clockMs = clockMs,
    )
    private val detailCache = LruCache<String, JsonObject>(maxEntries = 32, clockMs = clockMs)
    private val searchCache = LruCache<String, List<Int>>(
        maxEntries = 8,
        ttlMs = SEARCH_CACHE_TTL_MS,
        clockMs = clockMs,
    )

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(
                FeedRequest(siteId = SiteId.HITOMI, section = "latest", language = language, page = 1),
            )
            return HomeFeed(siteId = SiteId.HITOMI, primaryFeedPage = page)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage = executeFeed(request)

        override fun supportedSorts(request: FeedRequest): List<FeedSort> =
            listOf(FeedSort.NEWEST, FeedSort.OLDEST, FeedSort.POPULAR)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = executeFeed(request)

        override val supportedScopes: Set<SearchScope> = setOf(
            SearchScope.ALL,
            SearchScope.TAG,
            SearchScope.AUTHOR,
            SearchScope.CHARACTER,
        )

        override val supportedFilters: Set<AdvancedFilterField> = setOf(
            AdvancedFilterField.INCLUDE_TAGS,
            AdvancedFilterField.EXCLUDE_TAGS,
            AdvancedFilterField.LANGUAGE,
        )

        override val supportsExcludeTags: Boolean = true

        override fun supportedSearchSorts(scope: SearchScope): List<FeedSort> =
            listOf(FeedSort.NEWEST, FeedSort.OLDEST, FeedSort.POPULAR)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val id = extractGalleryId(url)
                ?: throw SiteException.ParseError("无法识别 Hitomi 画廊地址：$url", siteId = SiteId.HITOMI)
            val info = fetchGalleryInfo(id, forceRefresh = false)
            val routing = fetchRouting(forceRefresh = false)
            return parseGalleryDetail(info, routing)
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.HITOMI)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            return resolvePageImage(
                pageUrl = "${descriptor.baseUrl}/reader/${detail.item.id}.html#$pageIndex",
                preferOriginal = false,
                forceReload = forceReload,
            )
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val (id, page) = extractPageIdentity(pageUrl)
                ?: throw SiteException.ParseError("无法识别 Hitomi 页码：$pageUrl", siteId = SiteId.HITOMI)
            val reloadQuery = ComicUrl.parse(pageUrl)?.queryParameter("reload") == "1"
            val refresh = forceReload || reloadQuery
            val info = fetchGalleryInfo(id, forceRefresh = refresh)
            val routing = fetchRouting(forceRefresh = refresh)
            return parsePageImage(info, page, routing)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            extractGalleryId(url)?.let { id ->
                return "${descriptor.baseUrl}/galleries/$id.html"
            }
            extractPageIdentity(url)?.let { (id, page) ->
                return "${descriptor.baseUrl}/reader/$id.html#$page"
            }
            return ComicUrl.parseStrictHttps("${descriptor.baseUrl}${parsed.encodedPath}")
                ?.withQuery(parsed.queryParams)
                ?.withFragment(null)
                ?.toString()
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            extractGalleryId(url)?.let { id ->
                return ResolvedLink.Gallery(
                    siteId = SiteId.HITOMI,
                    id = id,
                    canonicalUrl = "${descriptor.baseUrl}/galleries/$id.html",
                )
            }
            extractPageIdentity(url)?.let { (id, page) ->
                return ResolvedLink.Gallery(
                    siteId = SiteId.HITOMI,
                    id = id,
                    canonicalUrl = "${descriptor.baseUrl}/reader/$id.html#$page",
                    pageIndex = page,
                )
            }
            parsed.queryParameter("query")?.takeIf { it.isNotBlank() }?.let { query ->
                return ResolvedLink.Feed(
                    FeedRequest(siteId = SiteId.HITOMI, query = query, scope = SearchScope.ALL),
                )
            }
            return null
        }
    }

    // -------------------------------------------------------------------------
    // Core Feed, Range Paging & B-Tree Search
    // -------------------------------------------------------------------------

    private suspend fun executeFeed(request: FeedRequest): FeedPage = coroutineScope {
        if (request.page !in 1..MAX_PAGE) {
            throw SiteException.ParseError("页码无效：${request.page}", siteId = SiteId.HITOMI)
        }
        val query = buildQuery(request)
        val popular = isPopularRequest(request)
        val oldest = !popular && request.sort == FeedSort.OLDEST
        val languages = request.language.languageKeys()
        val singlePositiveFacet = if (query.isBlank()) {
            null
        } else {
            parseTerms(query).singleOrNull()?.takeIf { !it.excluded && it.value.contains(':') }?.value
        }

        val pageIds: List<Int>
        val totalPages: Int?
        var nextCursor: String? = null
        var hasMore: Boolean

        if (query.isBlank() && languages.size > 1 && !oldest) {
            val merged = mergeDualLanguageNozomi(request, popular)
            pageIds = merged.items
            nextCursor = merged.nextCursor
            hasMore = merged.hasMore
            totalPages = null
        } else if ((languages.isEmpty() && (query.isBlank() || singlePositiveFacet != null)) ||
            (query.isBlank() && languages.size == 1)
        ) {
            val facet = languages.singleOrNull()?.let { "language:$it" } ?: singlePositiveFacet.orEmpty()
            val url = nozomiUrl(facet, popular)
            if (oldest) {
                val probe = fetchBinary(url = url, offset = 0L, length = 4, allowMissing = true)
                val totalBytes = probe.totalBytes
                val totalIds = totalBytes / 4L
                val computedTotalPages = ((totalIds + PAGE_SIZE - 1) / PAGE_SIZE).toInt().coerceAtLeast(1)
                val endByte = totalBytes - (request.page - 1L) * PAGE_BYTES
                pageIds = if (totalBytes <= 0L || endByte <= 0L) {
                    emptyList()
                } else {
                    val startByte = maxOf(0L, endByte - PAGE_BYTES)
                    val sliceLen = (endByte - startByte).toInt()
                    val payload = fetchBinary(url = url, offset = startByte, length = sliceLen, allowMissing = true)
                    decodeNozomiIds(payload.bytes).asReversed()
                }
                totalPages = computedTotalPages
                hasMore = request.page < computedTotalPages && pageIds.isNotEmpty()
            } else {
                val offset = (request.page - 1L) * PAGE_BYTES
                val payload = fetchBinary(
                    url = url,
                    offset = offset,
                    length = PAGE_BYTES,
                    allowMissing = true,
                )
                pageIds = decodeNozomiIds(payload.bytes)
                val totalIds = payload.totalBytes / 4L
                val computedTotalPages = ((totalIds + PAGE_SIZE - 1) / PAGE_SIZE).toInt().coerceAtLeast(1)
                totalPages = computedTotalPages
                hasMore = request.page < computedTotalPages && pageIds.isNotEmpty()
            }
        } else {
            val allIds = executeSearch(query, popular, request.language, oldest = oldest)
            val start = (request.page - 1) * PAGE_SIZE
            pageIds = if (start < allIds.size) allIds.subList(start, minOf(start + PAGE_SIZE, allIds.size)) else emptyList()
            val computedTotalPages = ((allIds.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
            totalPages = computedTotalPages
            hasMore = request.page < computedTotalPages && pageIds.isNotEmpty()
        }

        val routing = fetchRouting(forceRefresh = false)
        val permits = Semaphore(CONCURRENT_METADATA_PERMITS)
        val cards = pageIds.map { id ->
            async {
                permits.withPermit {
                    try {
                        parseGalleryDetail(fetchGalleryInfo(id.toString(), forceRefresh = false), routing).item
                    } catch (_: SiteException.NotFound) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()

        val excludes = request.advancedFilters.excludeTags
        val filteredCards = if (excludes.isEmpty()) {
            cards
        } else {
            cards.filter { card -> ContentFilter.isAllowed(card, excludes) }
        }

        FeedPage(
            items = filteredCards,
            nextPage = if (hasMore) request.page + 1 else null,
            nextCursor = nextCursor,
            hasMore = hasMore,
            totalPages = totalPages,
            title = feedHeading(request),
        )
    }

    private suspend fun mergeDualLanguageNozomi(
        request: FeedRequest,
        popular: Boolean,
    ): com.raven.app.core.sitespi.MergedPage<Int> {
        val strategy = if (popular) DualMergeStrategy.INTERLEAVED else DualMergeStrategy.DESCENDING_NUMERIC_ID

        suspend fun fetchNozomiPage(lang: String, page: Int): StreamSlice<Int> {
            val offset = (page - 1L) * PAGE_BYTES
            val payload = fetchBinary(
                url = nozomiUrl("language:$lang", popular),
                offset = offset,
                length = PAGE_BYTES,
                allowMissing = true,
            )
            val ids = decodeNozomiIds(payload.bytes)
            val totalIds = payload.totalBytes / 4L
            val hasNext = (page.toLong() * PAGE_SIZE) < totalIds && ids.isNotEmpty()
            return StreamSlice(items = ids, hasNext = hasNext)
        }

        return DualLanguageMerger.merge(
            cursorRaw = request.cursor,
            pageSize = PAGE_SIZE,
            strategy = strategy,
            idOf = { it.toString() },
            fetchStreamA = { p -> fetchNozomiPage("chinese", p) },
            fetchStreamB = { p -> fetchNozomiPage("japanese", p) },
        )
    }

    internal suspend fun executeSearch(
        query: String,
        popular: Boolean,
        language: ComicLanguage,
        oldest: Boolean = false,
    ): List<Int> {
        val cacheKey = "$query|$popular|$oldest|${language.name}"
        searchCache.get(cacheKey)?.let { return it }

        val terms = if (query.isBlank()) emptyList() else parseTerms(query)
        var included: Set<Int>? = null
        val excluded = HashSet<Int>()

        // Evaluate positive terms first to short-circuit empty intersections early
        for (term in terms.sortedBy { it.excluded }) {
            currentCoroutineContext().ensureActive()
            val ids = if (term.value.contains(':')) {
                decodeNozomiIds(fetchBinary(nozomiUrl(term.value, popular = false), allowMissing = true).bytes)
            } else {
                lookupWordInBTree(term.value)
            }
            if (term.excluded) {
                excluded.addAll(ids)
            } else {
                included = if (included == null) ids.toHashSet() else included.intersect(ids.toHashSet())
                if (included.isEmpty()) return emptyList()
            }
        }

        val languages = language.languageKeys()
        if (languages.isNotEmpty()) {
            val baseCandidates = included
            included = buildSet {
                for (lang in languages) {
                    val langBytes = fetchBinary(nozomiUrl("language:$lang", popular = false), allowMissing = true).bytes
                    if (baseCandidates == null) {
                        addAll(decodeNozomiIds(langBytes))
                    } else {
                        addAll(orderedMatches(langBytes, baseCandidates))
                    }
                }
            }
            if (included.isEmpty()) return emptyList()
        }

        val matchedSet = included.orEmpty()
        val ordered = when {
            popular -> {
                val popBytes = fetchBinary(nozomiUrl("", popular = true)).bytes
                orderedMatches(popBytes, matchedSet)
            }
            oldest -> matchedSet.sorted()
            else -> matchedSet.sortedDescending()
        }

        val finalIds = ordered.filter { it !in excluded }
        if (finalIds.size <= MAX_CACHED_SEARCH_RESULTS) {
            searchCache.put(cacheKey, finalIds)
        }
        return finalIds
    }

    internal suspend fun lookupWordInBTree(term: String): List<Int> {
        val version = fetchBinary("$CDN_BASE/galleriesindex/version").bytes
            .toString(Charsets.UTF_8).trim()
        if (!INDEX_VERSION_REGEX.matches(version)) {
            throw SiteException.ParseError("Hitomi 搜索索引版本无效：$version", siteId = SiteId.HITOMI)
        }
        val base = "$CDN_BASE/galleriesindex/galleries.$version"
        val key = sha256Prefix4(term)
        var address = 0L
        val visited = HashSet<Long>()

        repeat(MAX_BTREE_DEPTH) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(address)) {
                throw SiteException.ParseError("Hitomi 搜索索引出现循环，请稍后重试", siteId = SiteId.HITOMI)
            }
            val nodeBytes = fetchBinary("$base.index", offset = address, length = BTREE_NODE_BYTES).bytes
            val node = parseBTreeNode(nodeBytes)

            var idx = 0
            while (idx < node.keys.size && compareUnsignedBytes(key, node.keys[idx]) > 0) {
                idx++
            }
            if (idx < node.keys.size && compareUnsignedBytes(key, node.keys[idx]) == 0) {
                val (dataOffset, dataLength) = node.data[idx]
                val dataBytes = fetchBinary("$base.data", offset = dataOffset, length = dataLength).bytes
                if (dataBytes.size < 4) {
                    throw SiteException.ParseError("Hitomi 搜索结果不完整", siteId = SiteId.HITOMI)
                }
                val count = ByteBuffer.wrap(dataBytes).order(ByteOrder.BIG_ENDIAN).int.toLong()
                if (count < 0L || count * 4L + 4L != dataBytes.size.toLong()) {
                    throw SiteException.ParseError("Hitomi 搜索结果长度校验失败", siteId = SiteId.HITOMI)
                }
                return decodeNozomiIds(dataBytes.copyOfRange(4, dataBytes.size))
            }
            val nextChild = node.children.getOrNull(idx) ?: 0L
            if (nextChild == 0L) return emptyList()
            address = nextChild
        }

        throw SiteException.ParseError("Hitomi 搜索索引层级超出上限", siteId = SiteId.HITOMI)
    }

    // -------------------------------------------------------------------------
    // Network & Caching Helpers
    // -------------------------------------------------------------------------

    internal data class BinaryPayload(val bytes: ByteArray, val totalBytes: Long)

    private suspend fun fetchBinary(
        url: String,
        offset: Long? = null,
        length: Int = 0,
        allowMissing: Boolean = false,
    ): BinaryPayload {
        val maxBytes = if (offset != null) length else MAX_INDEX_BYTES
        if (maxBytes !in 1..MAX_INDEX_BYTES || (offset != null && offset !in 0L..MAX_RANGE_OFFSET)) {
            throw SiteException.ParseError("Hitomi 索引请求范围无效", siteId = SiteId.HITOMI)
        }
        val rangeHeader = offset?.let { "bytes=$it-${it + length - 1}" }
        val response = try {
            httpClient.getBytes(
                url = url,
                siteId = SiteId.HITOMI,
                kind = SiteRequestKind.API,
                headers = mapOf("Cache-Control" to "no-cache"),
                rangeHeader = rangeHeader,
                allowCache = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw e
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "Hitomi 网络请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.HITOMI,
                cause = e,
            )
        }

        if (allowMissing && response.code in setOf(404, 416)) {
            return BinaryPayload(ByteArray(0), 0L)
        }
        when (response.code) {
            429 -> throw SiteException.RateLimited(
                retryAfterSeconds = response.retryAfterSeconds,
                message = "Hitomi 请求频率已受限，请稍后重试",
                siteId = SiteId.HITOMI,
            )
            403 -> throw SiteException.Forbidden(
                message = "Hitomi 暂时拒绝访问，请检查网络或重试此页",
                siteId = SiteId.HITOMI,
            )
            404 -> throw SiteException.NotFound(
                message = "Hitomi 画廊或索引不存在，请刷新后重试",
                siteId = SiteId.HITOMI,
            )
        }
        if (!response.isSuccessful) {
            throw SiteException.Network("Hitomi 读取失败：HTTP ${response.code}", siteId = SiteId.HITOMI)
        }

        val bytes = response.body
        if (bytes.size > maxBytes) {
            throw SiteException.ParseError("Hitomi 索引过大，请使用更具体的查询", siteId = SiteId.HITOMI)
        }

        val contentRangeMatch = response.contentRange?.let { CONTENT_RANGE_REGEX.matchEntire(it.trim()) }
        if (offset != null) {
            val rangeStart = contentRangeMatch?.groupValues?.get(1)?.toLongOrNull()
            val rangeEnd = contentRangeMatch?.groupValues?.get(2)?.toLongOrNull()
            val rangeTotal = contentRangeMatch?.groupValues?.get(3)?.toLongOrNull()
            if (response.code != 206 || rangeStart != offset || rangeEnd == null || rangeTotal == null) {
                throw SiteException.ParseError("Hitomi 服务器未返回正确索引分段（需 HTTP 206）", siteId = SiteId.HITOMI)
            }
            val expectedLen = rangeEnd - offset + 1L
            if (bytes.size.toLong() != expectedLen) {
                throw SiteException.ParseError("Hitomi 索引分段长度不完整", siteId = SiteId.HITOMI)
            }
            return BinaryPayload(bytes, rangeTotal)
        }

        val totalBytes = contentRangeMatch?.groupValues?.get(3)?.toLongOrNull() ?: bytes.size.toLong()
        return BinaryPayload(bytes, totalBytes)
    }

    private suspend fun fetchRouting(forceRefresh: Boolean): RoutingConfig = routingMutex.withLock {
        if (!forceRefresh) {
            routingCache.get(ROUTING_CACHE_KEY)?.let { return@withLock it }
        }
        val script = fetchBinary("$CDN_BASE/gg.js").bytes.toString(Charsets.UTF_8)
        val parsed = parseGgRouting(script)
        routingCache.put(ROUTING_CACHE_KEY, parsed)
        parsed
    }

    private suspend fun fetchGalleryInfo(id: String, forceRefresh: Boolean): JsonObject {
        if (!forceRefresh) {
            detailCache.get(id)?.let { return it }
        }
        val script = fetchBinary("$CDN_BASE/galleries/$id.js").bytes.toString(Charsets.UTF_8)
        val parsed = parseGalleryInfoScript(script, id)
        detailCache.put(id, parsed)
        return parsed
    }

    // -------------------------------------------------------------------------
    // Pure Protocol Parsers (Exposed Internal/Public for Direct Testing)
    // -------------------------------------------------------------------------

    data class RoutingConfig(
        val prefix: String,
        val defaultServer: Int,
        val overrides: Map<Int, Int>,
    ) {
        fun bucket(hash: String): Int {
            if (!SHA256_HASH_REGEX.matches(hash)) {
                throw SiteException.ParseError("Hitomi 图片散列无效：$hash", siteId = SiteId.HITOMI)
            }
            val hex = hash.takeLast(1) + hash.takeLast(3).take(2)
            return hex.toInt(16)
        }

        fun imageUrl(hash: String): String {
            val b = bucket(hash)
            val server = 1 + (overrides[b] ?: defaultServer)
            return "https://w$server.gold-usergeneratedcontent.net/$prefix$b/$hash.webp"
        }

        fun thumbUrl(hash: String): String {
            val b = bucket(hash)
            val serverChar = ('a'.code + (overrides[b] ?: defaultServer)).toChar()
            val dir1 = hash.takeLast(1)
            val dir2 = hash.takeLast(3).take(2)
            return "https://${serverChar}tn.gold-usergeneratedcontent.net/webpbigtn/$dir1/$dir2/$hash.webp"
        }
    }

    data class BTreeNode(
        val keys: List<ByteArray>,
        val data: List<Pair<Long, Int>>,
        val children: List<Long>,
    )

    data class SearchTerm(
        val excluded: Boolean,
        val value: String,
    )

    internal fun parseGalleryInfoScript(script: String, expectedId: String): JsonObject {
        val match = GALLERY_INFO_REGEX.matchEntire(script)
            ?: throw SiteException.ParseError("Hitomi 画廊信息格式已变化", siteId = SiteId.HITOMI)
        val rawJson = match.groupValues[1].trim().removeSuffix(";")
        val obj = try {
            json.parseToJsonElement(rawJson) as? JsonObject
        } catch (e: Exception) {
            throw SiteException.ParseError("Hitomi 画廊 JSON 解析失败：${e.message}", siteId = SiteId.HITOMI, cause = e)
        } ?: throw SiteException.ParseError("Hitomi 画廊信息非 JSON 对象", siteId = SiteId.HITOMI)

        if (obj.stringOrEmpty("id") != expectedId) {
            throw SiteException.ParseError("Hitomi 返回的画廊编号不一致", siteId = SiteId.HITOMI)
        }
        val blocked = (obj["blocked"] as? JsonPrimitive)?.booleanOrNull == true ||
            obj.stringOrEmpty("blocked").equals("true", ignoreCase = true)
        if (blocked) {
            throw SiteException.Forbidden(message = "原站不允许访问此画廊", siteId = SiteId.HITOMI)
        }
        return obj
    }

    internal fun parseGalleryDetail(obj: JsonObject, routing: RoutingConfig): GalleryDetail {
        val id = obj.stringOrEmpty("id")
        if (!DIGITS_ONLY_REGEX.matches(id)) {
            throw SiteException.ParseError("Hitomi 画廊编号无效：$id", siteId = SiteId.HITOMI)
        }
        val files = obj["files"]?.asArrayOrNull()
            ?: throw SiteException.ParseError("Hitomi 未返回图页", siteId = SiteId.HITOMI)
        if (files.isEmpty()) {
            throw SiteException.ParseError("此条目没有漫画图页", siteId = SiteId.HITOMI)
        }

        val firstFile = files[0].asObjectOrNull()
            ?: throw SiteException.ParseError("Hitomi 首页图元数据无效", siteId = SiteId.HITOMI)
        val firstHash = firstFile.stringOrEmpty("hash")
        val coverWidth = firstFile.intOrZero("width")
        val coverHeight = firstFile.intOrZero("height")
        val aspectRatio = if (coverWidth > 0 && coverHeight > 0) coverWidth.toFloat() / coverHeight.toFloat() else null

        val title = HtmlUrlCleaner.cleanText(
            obj.stringOrEmpty("title").ifBlank { obj.stringOrEmpty("japanese_title") },
        )
        val japaneseTitle = HtmlUrlCleaner.cleanText(obj.stringOrEmpty("japanese_title"))
        val language = HtmlUrlCleaner.cleanText(
            obj.stringOrEmpty("language_localname").ifBlank { obj.stringOrEmpty("language") },
        )
        val type = HtmlUrlCleaner.cleanText(obj.stringOrEmpty("type"))
        val date = HtmlUrlCleaner.cleanText(obj.stringOrEmpty("date"))

        val pages = files.mapIndexed { idx, el ->
            val fileObj = el.asObjectOrNull()
                ?: throw SiteException.ParseError("Hitomi 第 ${idx + 1} 页元数据无效", siteId = SiteId.HITOMI)
            val hash = fileObj.stringOrEmpty("hash")
            val pageNum = idx + 1
            GalleryPage(
                index = pageNum,
                pageUrl = "${descriptor.baseUrl}/reader/$id.html#$pageNum",
                imageUrl = routing.imageUrl(hash),
                thumbUrl = routing.thumbUrl(hash),
                width = fileObj.intOrZero("width").takeIf { it > 0 },
                height = fileObj.intOrZero("height").takeIf { it > 0 },
            )
        }

        val tags = mutableListOf<TagRef>()
        val authors = mutableListOf<AuthorRef>()
        val characters = mutableListOf<TagRef>()
        val categories = if (type.isNotEmpty()) {
            listOf(TagRef(slug = type.lowercase(), label = CATEGORIES[type.lowercase()] ?: type, namespace = "type"))
        } else {
            emptyList()
        }

        val facetSpecs = listOf(
            Triple("artists", "artist", "artist"),
            Triple("groups", "group", "group"),
            Triple("parodys", "parody", "series"),
            Triple("characters", "character", "character"),
            Triple("tags", "tag", "tag"),
        )
        for ((arrayKey, propKey, defaultField) in facetSpecs) {
            obj[arrayKey]?.asArrayOrNull()?.forEach { raw ->
                val itemObj = raw.asObjectOrNull() ?: return@forEach
                val name = HtmlUrlCleaner.cleanText(itemObj.stringOrEmpty(propKey))
                if (name.isEmpty()) return@forEach
                val namespace = when {
                    arrayKey == "tags" && itemObj.stringOrEmpty("female") == "1" -> "female"
                    arrayKey == "tags" && itemObj.stringOrEmpty("male") == "1" -> "male"
                    else -> defaultField
                }
                val facetQuery = "$namespace:${name.replace(' ', '_')}"
                val searchUrl = ComicUrl.requireParse(descriptor.baseUrl, SiteId.HITOMI)
                    .addPathSegment("search.html")
                    .addQueryParameter("query", facetQuery)
                    .toString()

                val tagRef = TagRef(slug = facetQuery, label = name, namespace = namespace, url = searchUrl)
                tags.add(tagRef)
                when (arrayKey) {
                    "artists" -> authors.add(AuthorRef(id = name, name = name, role = "artist", url = searchUrl))
                    "groups" -> authors.add(AuthorRef(id = name, name = name, role = "group", url = searchUrl))
                    "characters" -> characters.add(tagRef)
                }
            }
        }

        val description = buildList {
            if (japaneseTitle.isNotEmpty() && japaneseTitle != title) add(japaneseTitle)
            val meta = listOf(type, date).filter { it.isNotEmpty() }.joinToString(" · ")
            if (meta.isNotEmpty()) add(meta)
        }.joinToString("\n")

        val item = MediaItem(
            siteId = SiteId.HITOMI,
            id = id,
            url = "${descriptor.baseUrl}/galleries/$id.html",
            kind = MediaKind.COMIC,
            title = title.ifBlank { "Gallery $id" },
            coverUrl = routing.thumbUrl(firstHash),
            coverAspectRatio = aspectRatio,
            pageCount = pages.size,
            dateLabel = date.ifBlank { null },
            author = authors.firstOrNull(),
            tags = tags,
            language = language.ifBlank { null },
            extra = buildMap {
                if (type.isNotEmpty()) put("type", type)
                if (japaneseTitle.isNotEmpty()) put("japaneseTitle", japaneseTitle)
            },
        )

        return GalleryDetail(
            item = item,
            description = description,
            pageCount = pages.size,
            pages = pages,
            tags = tags,
            authors = authors,
            characters = characters,
            categories = categories,
        )
    }

    internal fun parsePageImage(obj: JsonObject, page: Int, routing: RoutingConfig): GalleryPage {
        val files = obj["files"]?.asArrayOrNull()
            ?: throw SiteException.ParseError("Hitomi 未返回图页", siteId = SiteId.HITOMI)
        if (page !in 1..files.size) {
            throw SiteException.NotFound("页码超出范围：$page / ${files.size}", siteId = SiteId.HITOMI)
        }
        val id = obj.stringOrEmpty("id")
        val fileObj = files[page - 1].asObjectOrNull()
            ?: throw SiteException.ParseError("Hitomi 图页元数据无效", siteId = SiteId.HITOMI)
        val hash = fileObj.stringOrEmpty("hash")
        return GalleryPage(
            index = page,
            pageUrl = "${descriptor.baseUrl}/reader/$id.html#$page",
            imageUrl = routing.imageUrl(hash),
            thumbUrl = routing.thumbUrl(hash),
            width = fileObj.intOrZero("width").takeIf { it > 0 },
            height = fileObj.intOrZero("height").takeIf { it > 0 },
        )
    }

    internal fun buildQuery(request: FeedRequest): String {
        val raw = request.query?.trim().orEmpty()
        val categoryValue = request.category?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.section?.takeIf { it in CATEGORIES }
        val parts = mutableListOf<String>()
        if (categoryValue != null) {
            parts.add("type:${categoryValue.lowercase().replace(' ', '_')}")
        }
        if (raw.isNotEmpty()) {
            val formatted = when (request.scope) {
                SearchScope.AUTHOR -> if (raw.contains(':')) raw else "artist:${raw.replace(' ', '_')}"
                SearchScope.CHARACTER -> if (raw.contains(':')) raw else "character:${raw.replace(' ', '_')}"
                SearchScope.TAG -> if (raw.contains(':')) raw else "tag:${raw.replace(' ', '_')}"
                SearchScope.ALL -> raw
            }
            parts.add(formatted)
        }
        request.advancedFilters.includeTags
            .mapNotNull { formatHitomiTerm(it, exclude = false) }
            .forEach(parts::add)

        // Hitomi B-Tree search requires >=1 positive term and <=6 total terms.
        // Append server-side -tag terms only when a positive anchor exists, respecting the 6-term cap
        // (any remaining excludeTags are still enforced locally on loaded cards).
        val baseQuery = parts.joinToString(" ")
        val existingTerms = if (baseQuery.isBlank()) emptyList() else QUERY_TERM_REGEX.findAll(baseQuery.lowercase()).map { it.value }.toList()
        val hasPositiveAnchor = existingTerms.any { !it.startsWith('-') }
        if (hasPositiveAnchor && request.advancedFilters.excludeTags.isNotEmpty()) {
            val existingSet = existingTerms.toMutableSet()
            var budget = (MAX_QUERY_TERMS - existingTerms.size).coerceAtLeast(0)
            for (rawExclude in request.advancedFilters.excludeTags) {
                if (budget <= 0) break
                val formatted = formatHitomiTerm(rawExclude, exclude = true) ?: continue
                if (existingSet.add(formatted.lowercase())) {
                    parts.add(formatted)
                    budget--
                }
            }
        }
        return parts.joinToString(" ")
    }

    private fun formatHitomiTerm(raw: String, exclude: Boolean): String? {
        val cleaned = raw.trim()
            .let { if (exclude) it.removePrefix("-").trim() else it }
            .replace("\"", "")
        if (cleaned.isEmpty() || cleaned.contains('/') || cleaned.contains('#')) return null
        val prefix = if (exclude) "-" else ""
        val colonIdx = cleaned.indexOf(':')
        if (colonIdx > 0 && colonIdx < cleaned.lastIndex) {
            val field = cleaned.substring(0, colonIdx).trim().lowercase()
            val value = cleaned.substring(colonIdx + 1).trim().replace(' ', '_')
            if (value.isEmpty()) return null
            return if (field in VALID_FACET_FIELDS) {
                "$prefix$field:$value"
            } else {
                "$prefix$value"
            }
        }
        return if (cleaned.any(Char::isWhitespace)) {
            "$prefix\"$cleaned\""
        } else {
            "$prefix$cleaned"
        }
    }

    private fun isPopularRequest(request: FeedRequest): Boolean =
        request.section.equals("popular", ignoreCase = true) || request.sort == FeedSort.POPULAR

    private fun feedHeading(request: FeedRequest): String {
        val category = request.category
        val section = request.section
        val query = request.query
        return when {
            section.equals("popular", ignoreCase = true) -> "年度热门"
            !category.isNullOrBlank() -> CATEGORIES[category.lowercase()] ?: category
            section != null && section in CATEGORIES -> CATEGORIES[section] ?: section
            !query.isNullOrBlank() -> query
            else -> "最新画廊"
        }
    }

    private fun extractGalleryId(raw: String): String? {
        val parsed = ComicUrl.parse(raw)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        val match = GALLERY_PATH_REGEX.matchEntire(parsed.encodedPath) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
    }

    private fun extractPageIdentity(raw: String): Pair<String, Int>? {
        val parsed = ComicUrl.parse(raw)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        val id = READER_PATH_REGEX.matchEntire(parsed.encodedPath)?.groupValues?.get(1) ?: return null
        val page = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return id to page
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonObject.stringOrEmpty(key: String): String =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()

    private fun JsonObject.intOrZero(key: String): Int {
        val prim = this[key] as? JsonPrimitive ?: return 0
        if (prim is JsonNull) return 0
        return prim.intOrNull ?: prim.contentOrNull?.toIntOrNull() ?: 0
    }

    companion object {
        const val CDN_BASE: String = "https://ltn.gold-usergeneratedcontent.net"
        const val PAGE_SIZE: Int = 12
        const val PAGE_BYTES: Int = PAGE_SIZE * 4
        const val BTREE_NODE_BYTES: Int = 464

        private const val ROUTING_CACHE_KEY = "gg"
        private const val GG_ROUTING_TTL_MS = 10 * 60 * 1000L
        private const val SEARCH_CACHE_TTL_MS = 3 * 60 * 1000L
        private const val MAX_INDEX_BYTES = 16 * 1024 * 1024
        private const val MAX_INDEX_IDS = 500_000
        private const val MAX_CACHED_SEARCH_RESULTS = 50_000
        private const val MAX_RANGE_OFFSET = 1_000_000_000_000L
        private const val MAX_BTREE_DEPTH = 16
        private const val MAX_QUERY_TERMS = 6
        private const val MAX_PAGE = 1_000_000
        private const val CONCURRENT_METADATA_PERMITS = 3

        private val VALID_FACET_FIELDS = setOf(
            "artist", "group", "series", "character", "tag", "language", "type", "female", "male",
        )
        private val CONTENT_RANGE_REGEX = Regex("""bytes (\d+)-(\d+)/(\d+)""")
        private val INDEX_VERSION_REGEX = Regex("""^[0-9]{1,24}$""")
        private val SHA256_HASH_REGEX = Regex("""^[0-9a-f]{64}$""")
        private val DIGITS_ONLY_REGEX = Regex("""^\d+$""")
        private val GG_PREFIX_REGEX = Regex("""\bb\s*:\s*['"]([0-9]+/)['"]""")
        private val GG_DEFAULT_REGEX = Regex("""var\s+o\s*=\s*([01])\s*;""")
        private val GG_CASE_BLOCK_REGEX = Regex("""((?:\s*case\s+\d+\s*:\s*)+)o\s*=\s*([01])\s*;\s*break\s*;""")
        private val GG_CASE_NUM_REGEX = Regex("""case\s+(\d+)""")
        private val QUERY_TERM_REGEX = Regex("""-?(?:[^\s"]|"[^"]*")+""")
        private val GALLERY_INFO_REGEX = Regex("""^\s*var\s+galleryinfo\s*=\s*([\s\S]+?)\s*;?\s*$""")
        private val GALLERY_PATH_REGEX = Regex("""^/(?:galleries/(\d+)|(?:doujinshi|manga|cg|gamecg|artistcg)/[^/]+-(\d+))\.html$""")
        private val READER_PATH_REGEX = Regex("""^/reader/(\d+)\.html$""")

        val CATEGORIES: LinkedHashMap<String, String> = linkedMapOf(
            "doujinshi" to "同人志",
            "manga" to "漫画",
            "artistcg" to "画师 CG",
            "gamecg" to "游戏 CG",
        )

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SiteId.HITOMI,
            displayName = "Hitomi",
            shortName = "Hitomi",
            primaryKind = MediaKind.COMIC,
            supportedKinds = setOf(MediaKind.COMIC),
            defaultHost = "hitomi.la",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.CHARACTERS,
                SiteCapability.LANGUAGE_FILTER,
            ),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.OLDEST, FeedSort.POPULAR),
            sections = buildList {
                add(SiteSectionSpec(id = "latest", title = "最新画廊", defaultSort = FeedSort.NEWEST))
                add(SiteSectionSpec(id = "popular", title = "年度热门", defaultSort = FeedSort.POPULAR))
                CATEGORIES.forEach { (key, label) ->
                    add(SiteSectionSpec(id = key, title = label, category = key, defaultSort = FeedSort.NEWEST))
                }
            },
            searchScopes = listOf(SearchScope.ALL, SearchScope.TAG, SearchScope.AUTHOR, SearchScope.CHARACTER),
            supportedFilters = setOf(
                AdvancedFilterField.INCLUDE_TAGS,
                AdvancedFilterField.EXCLUDE_TAGS,
                AdvancedFilterField.LANGUAGE,
            ),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.RAVEN_API_UA,
                referer = "https://hitomi.la/",
                mediaReferer = "https://hitomi.la/",
                apiBase = CDN_BASE,
                apiHosts = setOf("ltn.gold-usergeneratedcontent.net"),
                mediaDomains = setOf("gold-usergeneratedcontent.net"),
            ),
        )

        /**
         * Pure regex-only `gg.js` parser — remote JavaScript is NEVER evaluated.
         */
        fun parseGgRouting(script: String): RoutingConfig {
            val prefix = GG_PREFIX_REGEX.find(script)?.groupValues?.get(1)
                ?: throw SiteException.ParseError("Hitomi 图片路由格式已变化（缺少 b 前缀）", siteId = SiteId.HITOMI)
            val defaultServer = GG_DEFAULT_REGEX.find(script)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw SiteException.ParseError("Hitomi 图片路由格式已变化（缺少默认分片）", siteId = SiteId.HITOMI)
            val overrides = HashMap<Int, Int>()
            GG_CASE_BLOCK_REGEX.findAll(script).forEach { block ->
                val targetServer = block.groupValues[2].toInt()
                GG_CASE_NUM_REGEX.findAll(block.groupValues[1]).forEach { caseMatch ->
                    val bucket = caseMatch.groupValues[1].toIntOrNull()
                        ?.takeIf { it in 0..4095 }
                        ?: throw SiteException.ParseError("Hitomi 路由桶号越界", siteId = SiteId.HITOMI)
                    overrides[bucket] = targetServer
                }
            }
            if (overrides.isEmpty()) {
                throw SiteException.ParseError("Hitomi 图片分片规则不可识别，请更新客户端", siteId = SiteId.HITOMI)
            }
            return RoutingConfig(prefix = prefix, defaultServer = defaultServer, overrides = overrides)
        }

        fun decodeNozomiIds(bytes: ByteArray): List<Int> {
            if (bytes.size % 4 != 0) {
                throw SiteException.ParseError("Hitomi 索引长度无效（非 4 字节对齐）", siteId = SiteId.HITOMI)
            }
            val count = bytes.size / 4
            if (count > MAX_INDEX_IDS) {
                throw SiteException.ParseError("Hitomi 查询结果过多，请使用更具体的关键词", siteId = SiteId.HITOMI)
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return List(count) {
                val id = buffer.int
                if (id <= 0) throw SiteException.ParseError("Hitomi 索引编号无效：$id", siteId = SiteId.HITOMI)
                id
            }
        }

        fun orderedMatches(bytes: ByteArray, matches: Set<Int>): List<Int> {
            if (bytes.size % 4 != 0) {
                throw SiteException.ParseError("Hitomi 排行索引长度无效", siteId = SiteId.HITOMI)
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val selected = ArrayList<Int>()
            while (buffer.hasRemaining()) {
                val id = buffer.int
                if (id <= 0) throw SiteException.ParseError("Hitomi 排行编号无效：$id", siteId = SiteId.HITOMI)
                if (id in matches) selected.add(id)
            }
            return selected
        }

        fun parseBTreeNode(bytes: ByteArray): BTreeNode {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            fun readCount(): Int {
                val c = buf.int
                if (c !in 0..16) throw SiteException.ParseError("Hitomi 搜索节点无效（count=$c）", siteId = SiteId.HITOMI)
                return c
            }
            return try {
                val keyCount = readCount()
                val keys = List(keyCount) {
                    val size = buf.int
                    if (size !in 1..32) {
                        throw SiteException.ParseError("Hitomi B-Tree 键长度无效：$size", siteId = SiteId.HITOMI)
                    }
                    ByteArray(size).also { buf.get(it) }
                }
                val dataCount = readCount()
                if (dataCount != keyCount) {
                    throw SiteException.ParseError("Hitomi B-Tree 键值数量不匹配", siteId = SiteId.HITOMI)
                }
                val data = List(dataCount) {
                    val offset = buf.long
                    val length = buf.int
                    if (offset < 0L || length !in 1..MAX_INDEX_BYTES) {
                        throw SiteException.ParseError("Hitomi B-Tree 数据指针越界", siteId = SiteId.HITOMI)
                    }
                    offset to length
                }
                val children = List(17) {
                    val child = buf.long
                    if (child < 0L) {
                        throw SiteException.ParseError("Hitomi B-Tree 子节点偏移无效", siteId = SiteId.HITOMI)
                    }
                    child
                }
                BTreeNode(keys = keys, data = data, children = children)
            } catch (e: BufferUnderflowException) {
                throw SiteException.ParseError("Hitomi 搜索节点不完整", siteId = SiteId.HITOMI, cause = e)
            }
        }

        fun compareUnsignedBytes(left: ByteArray, right: ByteArray): Int {
            val minLen = minOf(left.size, right.size)
            for (i in 0 until minLen) {
                val cmp = (left[i].toInt() and 0xFF).compareTo(right[i].toInt() and 0xFF)
                if (cmp != 0) return cmp
            }
            return left.size.compareTo(right.size)
        }

        fun sha256Prefix4(term: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(term.toByteArray(Charsets.UTF_8)).copyOf(4)

        fun parseTerms(query: String): List<SearchTerm> {
            val rawTerms = QUERY_TERM_REGEX.findAll(query.lowercase()).map { it.value }.toList()
            if (rawTerms.isEmpty() || rawTerms.size > 6) {
                throw SiteException.ParseError("请使用 1–6 个查询词，带空格的标签可用下划线连接", siteId = SiteId.HITOMI)
            }
            if (rawTerms.none { !it.startsWith('-') }) {
                throw SiteException.ParseError("请至少包含一个正向查询词", siteId = SiteId.HITOMI)
            }
            return rawTerms.map { raw ->
                val excluded = raw.startsWith('-')
                val cleaned = raw.removePrefix("-").replace("\"", "").replace('_', ' ').trim()
                if (cleaned.isEmpty() || cleaned.length > 200 || cleaned.contains('/') || cleaned.contains('#')) {
                    throw SiteException.ParseError("Hitomi 查询格式无效：$raw", siteId = SiteId.HITOMI)
                }
                SearchTerm(excluded = excluded, value = cleaned)
            }
        }

        fun nozomiUrl(facet: String, popular: Boolean = false): String {
            val base = ComicUrl.requireParse(CDN_BASE, SiteId.HITOMI)
            if (facet.isBlank()) {
                return if (popular) {
                    base.addPathSegments("popular/year-all.nozomi").toString()
                } else {
                    base.addPathSegment("index-all.nozomi").toString()
                }
            }
            val field = facet.substringBefore(':')
            val value = facet.substringAfter(':')
            if (field !in VALID_FACET_FIELDS || value.isBlank()) {
                throw SiteException.Unsupported("Hitomi 不支持此查询范围：$facet", siteId = SiteId.HITOMI)
            }
            return if (field == "language") {
                if (popular) {
                    base.addPathSegments("popular/year-$value.nozomi").toString()
                } else {
                    base.addPathSegment("index-$value.nozomi").toString()
                }
            } else {
                val dir = if (field == "female" || field == "male") "tag" else field
                var builder = base.addPathSegment(dir)
                if (popular) builder = builder.addPathSegments("popular/year")
                val fileName = "${if (field == "female" || field == "male") facet else value}-all.nozomi"
                builder.addPathSegment(fileName).toString()
            }
        }
    }
}
