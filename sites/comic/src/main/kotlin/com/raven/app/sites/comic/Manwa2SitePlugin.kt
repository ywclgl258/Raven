package com.raven.app.sites.comic

import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSection
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
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HostRouter
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.LruCache
import com.raven.app.core.sitespi.ManwaImageAesProtocol
import com.raven.app.core.sitespi.MediaTransportHook
import com.raven.app.core.sitespi.MirrorLeaseRouter
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup

/**
 * 漫蛙2 (`SiteId.MANWA2`) 连载漫画插件。
 *
 * 参考 `copymanga-copy20/manwayuplus` 与 `keiyoushi/manwa` 协议实现：
 * - 自动跟踪主域名跳转（如 `manwari.cc` -> `manwauu.cc`），避免 `POST /api/cate` 在 302 时退化为 GET 405
 * - 支持 `POST /api/cate`、HTML 卡片降级解析、`GET /api/search`、`GET /api/comic/{id}`、
 *   `GET /api/comic/{id}/chapters`、`GET /api/comic/image/{chapterId}`
 * - 暴露 [ManwaImageAesProtocol] [MediaTransportHook]，通过 `:core:network` 与 `:core:media`
 *   透明解密封面与章节加密图片，并在图床失败时自动切换多条 CDN 线路 + 刷新 `/static/assets/js/base.js` 中的 `AES_KEY`
 */
class Manwa2SitePlugin(
    private val httpClient: HttpClientAdapter,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val hostResolveMutex = Mutex()
    private val keyRefreshMutex = Mutex()

    @Volatile
    private var currentAesKey: String = ManwaImageAesProtocol.DEFAULT_AES_KEY

    @Volatile
    private var lastKeyRefreshAtMs: Long = 0L

    @Volatile
    private var cachedActualHost: String? = null

    @Volatile
    private var actualHostExpiresAtMs: Long = 0L

    @Volatile
    private var cachedRouteKey: String = ""

    private val mirrorRouter = MirrorLeaseRouter(
        hosts = ALL_SITE_HOSTS,
        clockMs = clockMs,
    )

    private val detailCache = LruCache<String, JsonObject>(
        maxEntries = 64,
        ttlMs = DETAIL_CACHE_TTL_MS,
        clockMs = clockMs,
    )
    private val chapterListCache = LruCache<String, List<ChapterEntry>>(
        maxEntries = 64,
        ttlMs = DETAIL_CACHE_TTL_MS,
        clockMs = clockMs,
    )
    private val chapterImageCache = LruCache<String, List<String>>(
        maxEntries = 128,
        ttlMs = CHAPTER_CACHE_TTL_MS,
        clockMs = clockMs,
    )

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val hostRouter: HostRouter = mirrorRouter

    override val mediaTransportHook: MediaTransportHook =
        ManwaImageAesProtocol.createTransportHook(
            keyProvider = { currentAesKey },
            urlMatcher = ::isEncryptedImageUrl,
        )

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val popular = runCatching {
                loadFeed(FeedRequest(siteId = SiteId.MANWA2, section = "popular", page = 1))
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            val latest = loadFeed(FeedRequest(siteId = SiteId.MANWA2, section = "latest", page = 1))
            val sections = buildList {
                if (popular != null && popular.items.isNotEmpty()) {
                    add(
                        FeedSection(
                            id = "popular",
                            title = "人气榜单",
                            items = popular.items,
                            seeAllRequest = FeedRequest(siteId = SiteId.MANWA2, section = "popular"),
                            isRanked = true,
                        ),
                    )
                }
            }
            return HomeFeed(
                siteId = SiteId.MANWA2,
                heroItems = popular?.items?.take(6) ?: latest.items.take(6),
                sections = sections,
                primaryFeedPage = latest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return searchComics(query, page)
            }
            val section = request.section?.trim()?.lowercase().orEmpty()
            if (section == "chapters") {
                val comicId = request.category?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw SiteException.ParseError("章节请求缺少作品编号", siteId = SiteId.MANWA2)
                return loadChapterFeed(comicId)
            }
            return loadCategoryFeed(section, page, request.sort)
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> =
            if (request.section.equals("chapters", ignoreCase = true)) {
                emptyList()
            } else {
                listOf(FeedSort.NEWEST, FeedSort.POPULAR)
            }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage {
            val query = request.query?.trim().orEmpty()
            if (query.isEmpty()) {
                throw SiteException.ParseError("漫蛙2搜索关键词不能为空", siteId = SiteId.MANWA2)
            }
            return searchComics(query, request.page.coerceAtLeast(1))
        }

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            parseChapterIds(url)?.let { (comicId, chapterId) ->
                return loadChapterDetail(comicId, chapterId, url)
            }
            val comicId = extractComicId(url)
                ?: throw SiteException.ParseError("无法识别漫蛙2作品地址：$url", siteId = SiteId.MANWA2)
            val detailObj = fetchComicDetail(comicId)
            val meta = parseComicMeta(detailObj, comicId)
            val chapters = fetchChapters(comicId)
            val first = chapters.firstOrNull()
            val cdnIdx = parseCdnIndex(url)
            val pages = if (first != null) {
                loadChapterImages(first.id, forceReload = false).mapIndexed { index, rawImg ->
                    GalleryPage(
                        index = index + 1,
                        pageUrl = pageAddress(comicId, first.id, index + 1, cdnIdx),
                        imageUrl = rewriteImageHost(rawImg, cdnIdx),
                    )
                }
            } else {
                emptyList()
            }
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.MANWA2,
                    id = comicId,
                    url = "${descriptor.baseUrl}/comic/$comicId",
                    kind = MediaKind.SERIAL_COMIC,
                    title = meta.title,
                    coverUrl = meta.coverUrl,
                    pageCount = pages.size,
                    views = meta.views,
                    likes = meta.likes,
                    author = meta.authors.firstOrNull(),
                    tags = meta.tags,
                    extra = buildMap {
                        put("comicId", comicId)
                        put("chapterId", first?.id.orEmpty())
                        put("chapterName", first?.title.orEmpty())
                    },
                ),
                description = meta.description,
                pageCount = pages.size.coerceAtLeast(1),
                pages = pages,
                tags = meta.tags,
                authors = meta.authors,
                chaptersUrl = if (chapters.size > 1) "${descriptor.baseUrl}/comic/$comicId#chapters" else null,
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.MANWA2)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val comicId = detail.item.extra["comicId"].orEmpty()
                .ifBlank { extractComicId(detail.item.url).orEmpty() }
                .ifBlank { detail.item.id.substringBefore(':') }
            val chapterId = detail.item.extra["chapterId"].orEmpty().ifBlank {
                detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl
                    ?.let { parseChapterIds(it)?.second }
                    .orEmpty()
            }
            if (comicId.isBlank() || chapterId.isBlank()) {
                throw SiteException.ParseError("漫蛙2章节信息缺失", siteId = SiteId.MANWA2)
            }
            val images = loadChapterImages(chapterId, forceReload = forceReload)
            val rawImg = images.getOrNull(pageIndex - 1)
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${images.size}", siteId = SiteId.MANWA2)
            return GalleryPage(
                index = pageIndex,
                pageUrl = pageAddress(comicId, chapterId, pageIndex, cdnIdx = 0),
                imageUrl = rewriteImageHost(rawImg, 0),
            )
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val parsed = ComicUrl.parse(pageUrl)
                ?: throw SiteException.ParseError("无法识别漫蛙2图页地址", siteId = SiteId.MANWA2)
            val (comicId, chapterId) = parseChapterIds(pageUrl)
                ?: throw SiteException.ParseError("无法识别漫蛙2章节编号", siteId = SiteId.MANWA2)
            val pageIndex = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val cdnIdx = parseCdnIndex(pageUrl)
            if (forceReload || cdnIdx > 0) {
                refreshAesKeyIfNeeded()
            }
            val images = loadChapterImages(chapterId, forceReload = forceReload)
            val rawImg = images.getOrNull(pageIndex - 1)
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${images.size}", siteId = SiteId.MANWA2)
            return GalleryPage(
                index = pageIndex,
                pageUrl = pageAddress(comicId, chapterId, pageIndex, cdnIdx),
                imageUrl = rewriteImageHost(rawImg, cdnIdx),
            )
        }

        override fun reloadUrlOnError(cause: SiteException, page: GalleryPage): String? {
            if (cause is SiteException.NotFound || cause is SiteException.AuthRequired) return null
            val parsed = ComicUrl.parse(page.pageUrl) ?: return null
            val currentCdn = parsed.queryParameter("cdn")?.toIntOrNull() ?: 0
            val nextCdn = currentCdn + 1
            if (nextCdn >= CDN_HOSTS.size) return null
            return parsed.setQueryParameter("cdn", nextCdn.toString()).toString()
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            parseChapterIds(parsed.toString())?.let { (comicId, chapterId) ->
                return "${descriptor.baseUrl}/comic/$comicId/$chapterId"
            }
            val comicId = extractComicId(parsed.toString()) ?: return null
            val base = "${descriptor.baseUrl}/comic/$comicId"
            return if (parsed.fragment.equals("chapters", ignoreCase = true)) "$base#chapters" else base
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            parseChapterIds(parsed.toString())?.let { (comicId, chapterId) ->
                return ResolvedLink.Gallery(
                    siteId = SiteId.MANWA2,
                    id = "$comicId:$chapterId",
                    canonicalUrl = "${descriptor.baseUrl}/comic/$comicId/$chapterId",
                )
            }
            val comicId = extractComicId(parsed.toString()) ?: return null
            if (parsed.fragment.equals("chapters", ignoreCase = true)) {
                return ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.MANWA2,
                        section = "chapters",
                        category = comicId,
                    ),
                )
            }
            return ResolvedLink.Gallery(
                siteId = SiteId.MANWA2,
                id = comicId,
                canonicalUrl = "${descriptor.baseUrl}/comic/$comicId",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Internal API, Redirect Host Resolution & HTML Fallback
    // -------------------------------------------------------------------------

    private suspend fun loadCategoryFeed(section: String, page: Int, sort: FeedSort): FeedPage {
        val sortCode = if (section == "popular" || sort == FeedSort.POPULAR) 1 else 2
        val statusCode = when (section) {
            "ongoing" -> 1
            "completed" -> 2
            else -> 0
        }
        val areaId = when (section) {
            "area_kr" -> 1
            "area_jp" -> 2
            "area_cn" -> 3
            else -> 0
        }
        val payload = buildJsonObject {
            put("page", buildJsonObject {
                put("page", page)
                put("pageSize", PAGE_SIZE)
            })
            put("category", "comic")
            put("sort", sortCode)
            put("comic", buildJsonObject {
                put("status", statusCode)
                put("areaId", areaId)
                put("tag", "")
                put("typeId", 1)
                put("level", 0)
                put("day", 0)
            })
            put("video", buildJsonObject {})
            put("novel", buildJsonObject {})
        }.toString()

        val actualHost = resolveActualHost()
        val postResult = runCatching {
            val resp = httpClient.postJson(
                url = "https://$actualHost/api/cate",
                jsonBody = payload,
                siteId = SiteId.MANWA2,
                headers = apiHeaders(actualHost),
                followRedirects = false,
            )
            resp.throwIfNotSuccessful(SiteId.MANWA2, descriptor.displayName)
            val root = json.parseToJsonElement(resp.bodyText()).jsonObject
            val code = root.safeInt("code") ?: 200
            if (code != 200) {
                throw SiteException.ParseError("漫蛙2分类接口返回 $code：${root.safeString("msg")}", siteId = SiteId.MANWA2)
            }
            val data = root.safeObject("data") ?: throw SiteException.ParseError("漫蛙2分类数据为空", siteId = SiteId.MANWA2)
            val list = data.safeArray("list").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseCateComic) }
            val total = data.safeInt("total") ?: list.size
            FeedPage(
                items = list,
                hasMore = page * PAGE_SIZE < total && list.isNotEmpty(),
                nextPage = if (page * PAGE_SIZE < total && list.isNotEmpty()) page + 1 else null,
                totalCount = total,
                title = sectionTitle(section),
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

        if (postResult != null && postResult.items.isNotEmpty()) {
            return postResult
        }

        // HTML fallback: `/cate?page=<N>`
        val htmlResp = httpClient.getText(
            url = "https://$actualHost/cate?page=$page",
            siteId = SiteId.MANWA2,
            kind = SiteRequestKind.PAGE,
            headers = mapOf("Referer" to "https://$actualHost/"),
        )
        htmlResp.throwIfNotSuccessful(SiteId.MANWA2, descriptor.displayName)
        val doc = Jsoup.parse(htmlResp.bodyText(), "https://$actualHost")
        val items = parseCateHtmlCards(doc, actualHost)
        if (items.isEmpty()) {
            throw SiteException.ParseError("漫蛙2列表结构不可识别", siteId = SiteId.MANWA2)
        }
        val hasNext = doc.select("a[href*=\"page=${page + 1}\"], li.next:not(.disabled)").isNotEmpty()
        return FeedPage(
            items = items,
            hasMore = hasNext,
            nextPage = if (hasNext) page + 1 else null,
            title = sectionTitle(section),
        )
    }

    private fun parseCateHtmlCards(doc: org.jsoup.nodes.Document, host: String): List<MediaItem> {
        val seen = LinkedHashMap<String, MediaItem>()
        doc.select("a[href*=/comic/]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            val comicId = extractComicId("https://$host$href") ?: return@forEach
            val img = anchor.selectFirst("img") ?: anchor.parent()?.selectFirst("img")
            val rawCover = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src").orEmpty()
            val title = anchor.attr("title").ifBlank {
                anchor.selectFirst("h3, h4, p, .title, .name")?.text()
                    ?: anchor.text()
            }.let { HtmlUrlCleaner.cleanText(it) }
            if (title.isBlank()) return@forEach
            seen.putIfAbsent(
                comicId,
                MediaItem(
                    siteId = SiteId.MANWA2,
                    id = comicId,
                    url = "${descriptor.baseUrl}/comic/$comicId",
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = rewriteImageHost(rawCover, 0),
                ),
            )
        }
        return seen.values.toList()
    }

    private suspend fun searchComics(query: String, page: Int): FeedPage {
        val actualHost = resolveActualHost()
        val url = ComicUrl.parse("https://$actualHost/api/search")!!
            .addQueryParameter("type", "mh")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", SEARCH_PAGE_SIZE.toString())
            .addQueryParameter("keyword", query)
            .toString()
        val data = fetchApiData(url, actualHost) as? JsonObject
            ?: throw SiteException.ParseError("漫蛙2搜索结果格式异常", siteId = SiteId.MANWA2)
        val items = data.safeArray("list").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseSearchComic) }
        val total = data.safeInt("total") ?: items.size
        return FeedPage(
            items = items,
            hasMore = page * SEARCH_PAGE_SIZE < total && items.isNotEmpty(),
            nextPage = if (page * SEARCH_PAGE_SIZE < total && items.isNotEmpty()) page + 1 else null,
            totalCount = total,
            title = query,
        )
    }

    private suspend fun fetchComicDetail(comicId: String): JsonObject =
        detailCache.getOrPutSuspend(comicId) {
            val host = resolveActualHost()
            fetchApiData("https://$host/api/comic/$comicId", host) as? JsonObject
                ?: throw SiteException.ParseError("漫蛙2详情数据格式异常", siteId = SiteId.MANWA2)
        }

    private suspend fun fetchChapters(comicId: String): List<ChapterEntry> =
        chapterListCache.getOrPutSuspend(comicId) {
            val host = resolveActualHost()
            val data = fetchApiData("https://$host/api/comic/$comicId/chapters", host)
            val arr = when (data) {
                is JsonArray -> data
                is JsonObject -> data.safeArray("list") ?: data.safeArray("chapters") ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            val parsed = arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj.safeInt("id")?.toString() ?: obj.safeString("id").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val title = obj.safeString("title").ifBlank { obj.safeString("name") }.ifBlank { "章节 $id" }
                val sortId = obj.safeString("sortId").toDoubleOrNull() ?: Double.MAX_VALUE
                ChapterEntry(id = id, title = HtmlUrlCleaner.cleanText(title), sortId = sortId)
            }
            parsed.sortedBy { it.sortId }.distinctBy { it.id }
        }

    private suspend fun loadChapterFeed(comicId: String): FeedPage {
        val chapters = fetchChapters(comicId)
        val items = chapters.map { ch ->
            MediaItem(
                siteId = SiteId.MANWA2,
                id = "$comicId:${ch.id}",
                url = "${descriptor.baseUrl}/comic/$comicId/${ch.id}",
                kind = MediaKind.SERIAL_COMIC,
                title = ch.title,
                extra = mapOf("comicId" to comicId, "chapterId" to ch.id),
            )
        }
        return FeedPage(items = items, title = "全部章节")
    }

    private suspend fun loadChapterDetail(comicId: String, chapterId: String, rawUrl: String): GalleryDetail {
        val meta = runCatching { parseComicMeta(fetchComicDetail(comicId), comicId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        val chapters = runCatching { fetchChapters(comicId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        val ch = chapters.firstOrNull { it.id == chapterId }
        val cdnIdx = parseCdnIndex(rawUrl)
        val images = loadChapterImages(chapterId, forceReload = false)
        val pages = images.mapIndexed { index, rawImg ->
            GalleryPage(
                index = index + 1,
                pageUrl = pageAddress(comicId, chapterId, index + 1, cdnIdx),
                imageUrl = rewriteImageHost(rawImg, cdnIdx),
            )
        }
        val title = listOfNotNull(meta?.title, ch?.title ?: "章节 $chapterId")
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return GalleryDetail(
            item = MediaItem(
                siteId = SiteId.MANWA2,
                id = "$comicId:$chapterId",
                url = "${descriptor.baseUrl}/comic/$comicId/$chapterId",
                kind = MediaKind.SERIAL_COMIC,
                title = title,
                coverUrl = meta?.coverUrl.orEmpty(),
                pageCount = pages.size,
                author = meta?.authors?.firstOrNull(),
                tags = meta?.tags.orEmpty(),
                extra = mapOf(
                    "comicId" to comicId,
                    "chapterId" to chapterId,
                    "chapterName" to (ch?.title ?: chapterId),
                ),
            ),
            description = meta?.description.orEmpty(),
            pageCount = pages.size.coerceAtLeast(1),
            pages = pages,
            tags = meta?.tags.orEmpty(),
            authors = meta?.authors.orEmpty(),
            chaptersUrl = "${descriptor.baseUrl}/comic/$comicId#chapters",
        )
    }

    private suspend fun loadChapterImages(chapterId: String, forceReload: Boolean): List<String> {
        if (chapterId.isBlank()) return emptyList()
        if (!forceReload) {
            chapterImageCache.get(chapterId)?.let { return it }
        }
        val host = resolveActualHost()
        val collected = mutableListOf<String>()
        var page = 1
        var totalPages = 1
        var emptyRetries = 0
        while (page <= totalPages && page <= MAX_IMAGE_PAGES) {
            val url = "https://$host/api/comic/image/$chapterId?page=$page&page_size=$IMAGE_PAGE_SIZE"
            val data = fetchApiData(url, host) as? JsonObject
                ?: throw SiteException.ParseError("漫蛙2章节图页格式异常", siteId = SiteId.MANWA2)
            val imgs = data.safeArray("images").orEmpty().mapNotNull { el ->
                (el as? JsonObject)?.safeString("url")?.takeIf { it.isNotBlank() }
            }
            val tp = data.safeObject("pagination")?.safeInt("totalPages") ?: 1
            totalPages = tp.coerceAtLeast(1)
            if (imgs.isEmpty()) {
                // Upstream still claims more pages: a transient empty page must not be read as
                // "chapter over". Retry the SAME page a bounded number of times, then fail loudly
                // instead of caching a prefix as a complete chapter.
                if (page < totalPages && emptyRetries < MAX_EMPTY_PAGE_RETRIES) {
                    emptyRetries++
                    continue
                }
                if (page < totalPages) {
                    throw SiteException.ParseError(
                        "漫蛙2章节第 $page 页为空但仍有 ${totalPages - page + 1} 页未取，章节不完整",
                        siteId = SiteId.MANWA2,
                    )
                }
                break
            }
            emptyRetries = 0
            collected += imgs
            page++
        }
        // Hitting the hard page ceiling with additional upstream pages is a protective exit, never
        // a complete chapter — refuse to cache it so the next attempt can fetch the full set.
        if (page <= totalPages && page > MAX_IMAGE_PAGES) {
            throw SiteException.ParseError(
                "漫蛙2章节页数超过上限 $MAX_IMAGE_PAGES（声明 $totalPages 页），未缓存部分结果",
                siteId = SiteId.MANWA2,
            )
        }
        if (collected.isEmpty()) {
            throw SiteException.ParseError("漫蛙2章节暂无可用图片", siteId = SiteId.MANWA2)
        }
        chapterImageCache.put(chapterId, collected)
        return collected
    }

    private suspend fun fetchApiData(url: String, host: String): kotlinx.serialization.json.JsonElement {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.MANWA2,
                kind = SiteRequestKind.API,
                headers = apiHeaders(host),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SiteException) {
            throw se
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "漫蛙2接口请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.MANWA2,
                cause = e,
            )
        }
        response.throwIfNotSuccessful(SiteId.MANWA2, descriptor.displayName)
        val root = runCatching { json.parseToJsonElement(response.bodyText()).jsonObject }.getOrNull()
            ?: throw SiteException.ParseError("漫蛙2接口响应格式异常", siteId = SiteId.MANWA2)
        val code = root.safeInt("code") ?: 200
        if (code != 200) {
            throw SiteException.ParseError(
                "漫蛙2接口返回 $code：${root.safeString("msg").ifBlank { "未知错误" }}",
                siteId = SiteId.MANWA2,
            )
        }
        return root["data"] ?: throw SiteException.ParseError("漫蛙2接口缺少 data 字段", siteId = SiteId.MANWA2)
    }

    private suspend fun resolveActualHost(): String {
        val routeKey = httpClient.currentRouteKey(ALL_SITE_HOSTS.map { "https://$it/cate?page=1" })
        val now = clockMs()
        val existing = cachedActualHost
        if (existing != null && cachedRouteKey == routeKey && now < actualHostExpiresAtMs) {
            return existing
        }
        return hostResolveMutex.withLock {
            val recheckNow = clockMs()
            val recheckHost = cachedActualHost
            if (recheckHost != null && cachedRouteKey == routeKey && recheckNow < actualHostExpiresAtMs) {
                return@withLock recheckHost
            }
            val preferred = mirrorRouter.activeHost(routeKey)
            val ordered = buildList {
                if (preferred.isNotBlank()) add(preferred)
                ALL_SITE_HOSTS.forEach { if (it !in this) add(it) }
            }.take(4)

            for (candidate in ordered) {
                try {
                    val resp = httpClient.execute(
                        com.raven.app.core.sitespi.SiteHttpRequest(
                            url = "https://$candidate/cate?page=1",
                            siteId = SiteId.MANWA2,
                            kind = SiteRequestKind.PAGE,
                            timeoutMs = 5_000L,
                        ),
                    )
                    if (resp.isSuccessful) {
                        val finalHost = ComicUrl.parse(resp.finalUrl)?.host ?: candidate
                        mirrorRouter.reportHostSuccess(candidate, routeKey)
                        cachedActualHost = finalHost
                        cachedRouteKey = routeKey
                        actualHostExpiresAtMs = clockMs() + MirrorLeaseRouter.DEFAULT_LEASE_DURATION_MS
                        return@withLock finalHost
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    mirrorRouter.reportHostFailure(candidate, routeKey, err)
                }
            }
            val fallback = preferred.ifBlank { ALL_SITE_HOSTS.first() }
            cachedActualHost = fallback
            cachedRouteKey = routeKey
            actualHostExpiresAtMs = clockMs() + 60_000L
            fallback
        }
    }

    private suspend fun refreshAesKeyIfNeeded() {
        val now = clockMs()
        if (now - lastKeyRefreshAtMs < KEY_REFRESH_COOLDOWN_MS) return
        if (!keyRefreshMutex.tryLock()) return
        try {
            if (clockMs() - lastKeyRefreshAtMs < KEY_REFRESH_COOLDOWN_MS) return
            lastKeyRefreshAtMs = clockMs()
            val host = resolveActualHost()
            val resp = runCatching {
                httpClient.getText(
                    url = "https://$host/static/assets/js/base.js",
                    siteId = SiteId.MANWA2,
                    kind = SiteRequestKind.PAGE,
                    headers = mapOf("Referer" to "https://$host/"),
                )
            }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return
            if (resp.isSuccessful) {
                ManwaImageAesProtocol.extractAesKeyFromJs(resp.bodyText())?.let { extracted ->
                    currentAesKey = extracted
                }
            }
        } finally {
            keyRefreshMutex.unlock()
        }
    }

    private fun parseCateComic(obj: JsonObject): MediaItem? {
        val urlPath = obj.safeString("url").takeIf { it.isNotBlank() }
        val comicId = obj.safeInt("id")?.toString()
            ?: urlPath?.let { extractComicId("https://${descriptor.defaultHost}$it") }
            ?: return null
        val title = obj.safeString("title").takeIf { it.isNotBlank() } ?: return null
        val rawCover = obj.safeString("pic").ifBlank { obj.safeString("cover") }
        val author = obj.safeString("author").takeIf { it.isNotBlank() }?.let {
            AuthorRef(id = it, name = HtmlUrlCleaner.cleanText(it))
        }
        val tags = obj.safeString("tags").split(',', ' ')
            .map { HtmlUrlCleaner.cleanText(it) }
            .filter { it.isNotBlank() }
            .map { TagRef(slug = it, label = it) }
        return MediaItem(
            siteId = SiteId.MANWA2,
            id = comicId,
            url = "${descriptor.baseUrl}/comic/$comicId",
            kind = MediaKind.SERIAL_COMIC,
            title = HtmlUrlCleaner.cleanText(title),
            coverUrl = rewriteImageHost(rawCover, 0),
            author = author,
            tags = tags,
        )
    }

    private fun parseSearchComic(obj: JsonObject): MediaItem? {
        val urlPath = obj.safeString("url")
        val id = obj.safeInt("id")?.toString()
            ?: extractComicId("https://${descriptor.defaultHost}$urlPath")
            ?: return null
        val title = obj.safeString("title").takeIf { it.isNotBlank() } ?: return null
        val rawCover = obj.safeString("cover").ifBlank { obj.safeString("pic") }
        val author = obj.safeString("author").takeIf { it.isNotBlank() }?.let {
            AuthorRef(id = it, name = HtmlUrlCleaner.cleanText(it))
        }
        val tags = obj.safeString("tags").split(',', ' ')
            .map { HtmlUrlCleaner.cleanText(it) }
            .filter { it.isNotBlank() }
            .map { TagRef(slug = it, label = it) }
        return MediaItem(
            siteId = SiteId.MANWA2,
            id = id,
            url = "${descriptor.baseUrl}/comic/$id",
            kind = MediaKind.SERIAL_COMIC,
            title = HtmlUrlCleaner.cleanText(title),
            coverUrl = rewriteImageHost(rawCover, 0),
            author = author,
            tags = tags,
        )
    }

    private fun parseComicMeta(obj: JsonObject, comicId: String): ComicMeta {
        val title = obj.safeString("title").ifBlank { "漫画 $comicId" }.let { HtmlUrlCleaner.cleanText(it) }
        val cover = rewriteImageHost(obj.safeString("cover").ifBlank { obj.safeString("pic") }, 0)
        val authors = obj.safeString("author").split(',', '、', '/')
            .map { HtmlUrlCleaner.cleanText(it) }
            .filter { it.isNotBlank() }
            .map { AuthorRef(id = it, name = it) }
        val tags = obj.safeString("tags").split(',', ' ')
            .map { HtmlUrlCleaner.cleanText(it) }
            .filter { it.isNotBlank() }
            .map { TagRef(slug = it, label = it) }
        val statusLabel = when (obj.safeInt("status")) {
            1 -> "连载中"
            2 -> "已完结"
            else -> null
        }
        val views = obj.safeLong("tHits")?.takeIf { it > 0 }
        val likes = obj.safeLong("collection")?.takeIf { it > 0 }
        val intro = HtmlUrlCleaner.cleanText(obj.safeString("intro").ifBlank { obj.safeString("description") })
        val description = buildString {
            val stats = listOfNotNull(
                statusLabel,
                views?.let { "阅读 $it" },
                likes?.let { "收藏 $it" },
            ).joinToString(" · ")
            if (stats.isNotEmpty()) append(stats)
            if (intro.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(intro)
            }
        }
        return ComicMeta(
            title = title,
            coverUrl = cover,
            authors = authors,
            tags = tags,
            description = description,
            views = views,
            likes = likes,
        )
    }

    private fun apiHeaders(host: String): Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Authorization" to "Bearer null",
        "Cookie" to "route_idx=0; typeId=1",
        "Origin" to "https://$host",
        "Referer" to "https://$host/cate?page=1",
        "X-Requested-With" to "XMLHttpRequest",
    )

    internal fun rewriteImageHost(rawUrl: String, cdnIndex: Int): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val targetCdn = CDN_HOSTS[cdnIndex.coerceIn(0, CDN_HOSTS.lastIndex)]
        if (trimmed.startsWith("/")) {
            return "https://$targetCdn$trimmed"
        }
        val parsed = ComicUrl.parse(trimmed) ?: return trimmed
        // Strip raw IP:port or stale CDN hosts (e.g., 52.128.238.250:5367, tu.mhttu.cc) onto active CDN host.
        return "https://$targetCdn${parsed.encodedPath}${parsed.rawQuery?.let { "?$it" }.orEmpty()}"
    }

    internal fun isEncryptedImageUrl(url: String): Boolean {
        val parsed = ComicUrl.parse(url) ?: return false
        val host = parsed.host
        return url.contains("/en_images/", ignoreCase = true) ||
            url.contains("v=20220724", ignoreCase = true) ||
            CDN_HOSTS.any { it.equals(host, ignoreCase = true) } ||
            descriptor.matchesMediaHost(host)
    }

    private fun pageAddress(comicId: String, chapterId: String, pageIndex: Int, cdnIdx: Int): String {
        val base = "${descriptor.baseUrl}/comic/$comicId/$chapterId"
        return if (cdnIdx > 0) "$base?cdn=$cdnIdx#$pageIndex" else "$base#$pageIndex"
    }

    private fun parseCdnIndex(url: String): Int =
        ComicUrl.parse(url)?.queryParameter("cdn")?.toIntOrNull()?.coerceIn(0, CDN_HOSTS.lastIndex) ?: 0

    private fun extractComicId(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOf("comic")
        if (idx >= 0) {
            return segments.getOrNull(idx + 1)?.takeIf { it.isNotBlank() && it != "image" }
        }
        val bookIdx = segments.indexOf("book")
        if (bookIdx >= 0) {
            return segments.getOrNull(bookIdx + 1)?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun parseChapterIds(url: String): Pair<String, String>? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOf("comic")
        if (idx >= 0 && idx + 2 < segments.size) {
            val comicId = segments[idx + 1].takeIf { it.isNotBlank() && it != "image" } ?: return null
            val chapterId = segments[idx + 2].takeIf { it.isNotBlank() && it != "chapters" } ?: return null
            return comicId to chapterId
        }
        return null
    }

    private fun sectionTitle(section: String): String =
        SECTIONS.firstOrNull { it.id == section }?.title ?: "最近更新"

    private data class ChapterEntry(val id: String, val title: String, val sortId: Double)

    private data class ComicMeta(
        val title: String,
        val coverUrl: String,
        val authors: List<AuthorRef>,
        val tags: List<TagRef>,
        val description: String,
        val views: Long?,
        val likes: Long?,
    )

    companion object {
        val SITE_ID: SiteId = SiteId.MANWA2
        const val BASE_URL: String = "https://manwauu.cc"

        val ALL_SITE_HOSTS: List<String> = listOf(
            "manwauu.cc",
            "manwari.cc",
            "manwaan.cc",
            "manwaye.cc",
            "manware.cc",
            "manwaqi.cc",
            "manwana.cc",
            "manwali.cc",
        )

        val CDN_HOSTS: List<String> = listOf(
            "tu.mwzu.cc",
            "svip.mwtt.cc",
            "fm.mwtt.cc",
            "mg.mwre.cc",
        )

        private const val PAGE_SIZE = 20
        private const val SEARCH_PAGE_SIZE = 20
        private const val IMAGE_PAGE_SIZE = 9999
        private const val MAX_IMAGE_PAGES = 10
        private const val MAX_EMPTY_PAGE_RETRIES = 2
        private const val DETAIL_CACHE_TTL_MS = 5 * 60 * 1_000L
        private const val CHAPTER_CACHE_TTL_MS = 30 * 60 * 1_000L
        private const val KEY_REFRESH_COOLDOWN_MS = 10 * 60 * 1_000L

        internal val SECTIONS: List<SiteSectionSpec> = listOf(
            SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST),
            SiteSectionSpec("popular", "人气榜单", defaultSort = FeedSort.POPULAR),
            SiteSectionSpec("ongoing", "连载中"),
            SiteSectionSpec("completed", "已完结"),
            SiteSectionSpec("area_kr", "韩漫"),
            SiteSectionSpec("area_jp", "日漫"),
            SiteSectionSpec("area_cn", "国漫"),
        )

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "漫蛙2",
            shortName = "漫蛙2",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "manwauu.cc",
            mirrorHosts = ALL_SITE_HOSTS.drop(1) + ALL_SITE_HOSTS.map { "www.$it" },
            accessNote = "支持多镜像自动跳转、多图源 CDN 自愈及 AES-CBC 图片透明解密",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.MIRRORS,
            ),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = SECTIONS,
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                apiBase = BASE_URL,
                apiHosts = ALL_SITE_HOSTS.toSet(),
                mediaDomains = setOf(
                    "mwzu.cc",
                    "mwtt.cc",
                    "mwre.cc",
                    "mhttu.cc",
                    "manwauu.cc",
                    "manwari.cc",
                    "manwaan.cc",
                ),
                pageIntervalMs = 450L,
                imageIntervalMs = 120L,
            ),
        )
    }
}
