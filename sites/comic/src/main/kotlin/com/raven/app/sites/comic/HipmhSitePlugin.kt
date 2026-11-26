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
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.LruCache
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import java.math.BigInteger
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

/**
 * 嬉皮漫畫 (`SiteId.HIPMH`) 连载漫画插件。
 *
 * 对齐 `copymanga-copy20/hipmh-v1.6.1` 协议：
 * - 列表：`GET https://hipapi1.s3file.top/v1/mangas`（更新、人气、排行、完结/连载、分类与题材）
 * - 搜索：优先走专用接口 `GET /v1/search?q=<q>&page=<p>&page_size=20`，异常时平滑降级 `/v1/mangas?keyword=`
 * - 章节目录：支持全分页拉取（不再截断于 200 话），优先使用作品页 `#chapters-config[data-mid]`
 * - 作品详情：完整解析作者、标签、简介、别名与连载状态
 * - 章节阅读：`GET /v2/chapter?hid=<apiHid>`，严格校验 `qM9`/`Vx`/`pL0`/`Z7` 混淆编码及诱饵页剔除，
 *   配合 `LruCache` 缓存与双线图床（`tx` <-> `cf`）`reloadUrlOnError` 自动切换自愈
 */
class HipmhSitePlugin(
    private val httpClient: HttpClientAdapter,
    clockMs: () -> Long = System::currentTimeMillis,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val chapterCache = LruCache<String, ChapterData>(
        maxEntries = 128,
        ttlMs = CHAPTER_CACHE_TTL_MS,
        clockMs = clockMs,
    )

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val popular = runCatching {
                loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "popular", page = 1))
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            val latest = loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "latest", page = 1))
            val sections = buildList {
                if (popular != null && popular.items.isNotEmpty()) {
                    add(
                        FeedSection(
                            id = "popular",
                            title = "人氣榜",
                            items = popular.items,
                            seeAllRequest = FeedRequest(siteId = SiteId.HIPMH, section = "popular"),
                            isRanked = true,
                        ),
                    )
                }
            }
            return HomeFeed(
                siteId = SiteId.HIPMH,
                heroItems = popular?.items?.take(6) ?: latest.items.take(6),
                sections = sections,
                primaryFeedPage = latest,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return searchMangas(query, page)
            }
            if (request.section.equals("chapters", ignoreCase = true)) {
                val mid = request.category?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw SiteException.ParseError("章节请求缺少作品编号", siteId = SiteId.HIPMH)
                return loadChapterFeed(mid)
            }
            val url = buildListUrl(request.section, page, query = "")
            val body = fetchApi(url)
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw SiteException.ParseError("嬉皮漫畫列表数据格式异常", siteId = SiteId.HIPMH)
            val data = root.safeObject("data")
                ?: throw SiteException.ParseError("嬉皮漫畫列表数据为空", siteId = SiteId.HIPMH)
            val items = data.safeArray("items").orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.let(::parseMangaSummary)
            }
            if (items.isEmpty()) {
                throw SiteException.ParseError("嬉皮漫畫列表结构不可识别", siteId = SiteId.HIPMH)
            }
            val totalPages = data.safeInt("total_pages") ?: page
            return FeedPage(
                items = items,
                nextPage = if (page < totalPages) page + 1 else null,
                hasMore = page < totalPages,
                totalCount = data.safeInt("total"),
                totalPages = totalPages,
                title = sectionTitle(request.section),
            )
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> =
            if (request.section.equals("chapters", ignoreCase = true)) {
                emptyList()
            } else {
                listOf(FeedSort.NEWEST, FeedSort.POPULAR, FeedSort.TRENDING)
            }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage {
            val query = request.query?.trim().orEmpty()
            if (query.isEmpty()) {
                throw SiteException.ParseError("嬉皮漫畫搜索关键词不能为空", siteId = SiteId.HIPMH)
            }
            return searchMangas(query, request.page.coerceAtLeast(1))
        }

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            parseChapterHid(url)?.let { hid -> return loadChapterDetail(hid, altLine = isAltLine(url)) }
            val mid = extractWorkSlug(url)
                ?: throw SiteException.ParseError("无法识别嬉皮漫畫地址：$url", siteId = SiteId.HIPMH)
            val canonical = "${descriptor.baseUrl}/works/$mid"
            val html = fetchHtml(canonical)
            val doc = Jsoup.parse(html, canonical)
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.title().substringBefore(" - ").ifBlank { "作品 $mid" }
            val rawCover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty().trim()
                .ifBlank { doc.selectFirst("[data-cover]")?.attr("data-cover").orEmpty().trim() }
            val cover = normalizeCoverUrl(rawCover)

            val authors = doc.select("a[href^=/author/]").mapNotNull { el ->
                val name = HtmlUrlCleaner.cleanText(el.text())
                if (name.isBlank()) null else AuthorRef(id = name, name = name, url = el.absUrl("href"))
            }.distinctBy { it.name }
            val tags = doc.select("#d-tab-info a[href^=/tag/]").mapNotNull { el ->
                val label = HtmlUrlCleaner.cleanText(el.text()).removePrefix("#").trim()
                if (label.isBlank()) null else TagRef(slug = label, label = label, url = el.absUrl("href"))
            }.distinctBy { it.label }
            val altTitles = doc.select("[data-alt-title-list] > [title]").mapNotNull { el ->
                HtmlUrlCleaner.cleanText(el.attr("title")).takeIf { it.isNotBlank() }
            }
            val statusLabel = when {
                doc.select("a[href=/ongoing][title=連載中], a[href=/status/ongoing][title=連載中]").isNotEmpty() -> "連載中"
                doc.select("a[href=/completed][title=完結], a[href=/status/completed][title=完結]").isNotEmpty() -> "已完結"
                else -> null
            }
            val infoText = doc.select("#d-info-content p")
                .map { HtmlUrlCleaner.cleanText(it.text()) }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            val description = buildString {
                if (!statusLabel.isNullOrBlank()) append(statusLabel)
                if (altTitles.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("別名：").append(altTitles.joinToString(" / "))
                }
                if (infoText.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(infoText)
                }
            }

            val configMid = doc.selectFirst("#chapters-config[data-mid]")?.attr("data-mid")
                ?.trim()?.takeIf { it.isNotBlank() }
            val chapters = loadChapters(mid = mid, configMid = configMid, throwOnError = false)
            val firstHid = chapters.firstOrNull()?.hid
            val pages = if (firstHid != null) loadChapterPages(firstHid, forceReload = false, altLine = false) else emptyList()
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.HIPMH,
                    id = mid,
                    url = canonical,
                    kind = MediaKind.SERIAL_COMIC,
                    title = HtmlUrlCleaner.cleanText(title),
                    coverUrl = cover,
                    pageCount = pages.size,
                    author = authors.firstOrNull(),
                    tags = tags,
                    extra = buildMap {
                        put("comicId", mid)
                        put("chapterHid", firstHid.orEmpty())
                        if (configMid != null) put("shortMid", configMid)
                    },
                ),
                description = description,
                pageCount = pages.size.coerceAtLeast(1),
                pages = pages,
                tags = tags,
                authors = authors,
                chaptersUrl = if (chapters.size > 1) "$canonical#chapters" else null,
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.HIPMH)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val hid = detail.item.extra["chapterHid"].orEmpty().ifBlank {
                detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl
                    ?.let { ComicUrl.parse(it)?.pathSegments?.lastOrNull() }
                    .orEmpty()
            }
            if (hid.isBlank()) {
                throw SiteException.ParseError("嬉皮漫畫章节信息缺失", siteId = SiteId.HIPMH)
            }
            val pages = loadChapterPages(hid, forceReload = forceReload, altLine = false)
            return pages.firstOrNull { it.index == pageIndex }
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${pages.size}", siteId = SiteId.HIPMH)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val parsed = ComicUrl.parse(pageUrl)
                ?: throw SiteException.ParseError("无法识别嬉皮漫畫图页地址", siteId = SiteId.HIPMH)
            val hid = parseChapterHid(pageUrl)
                ?: throw SiteException.ParseError("无法识别嬉皮漫畫章节编号", siteId = SiteId.HIPMH)
            val pageIndex = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val altLine = isAltLine(pageUrl)
            val pages = loadChapterPages(hid, forceReload = forceReload, altLine = altLine)
            return pages.firstOrNull { it.index == pageIndex }
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${pages.size}", siteId = SiteId.HIPMH)
        }

        override fun reloadUrlOnError(cause: SiteException, page: GalleryPage): String? {
            if (cause is SiteException.AuthRequired) return null
            val parsed = ComicUrl.parse(page.pageUrl) ?: return null
            if (parsed.queryParameter("altLine") == "1") return null
            return parsed.setQueryParameter("altLine", "1").toString()
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            parseChapterHid(parsed.toString())?.let { hid ->
                return "$READER_BASE/chapter/$hid"
            }
            val slug = extractWorkSlug(parsed.toString()) ?: return null
            val base = "${descriptor.baseUrl}/works/$slug"
            return if (parsed.fragment.equals("chapters", ignoreCase = true)) "$base#chapters" else base
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            parseChapterHid(parsed.toString())?.let { hid ->
                return ResolvedLink.Gallery(SiteId.HIPMH, hid, "$READER_BASE/chapter/$hid")
            }
            val slug = extractWorkSlug(parsed.toString()) ?: return null
            if (parsed.fragment.equals("chapters", ignoreCase = true)) {
                return ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.HIPMH,
                        section = "chapters",
                        category = slug,
                    ),
                )
            }
            return ResolvedLink.Gallery(SiteId.HIPMH, slug, "${descriptor.baseUrl}/works/$slug")
        }
    }

    // -------------------------------------------------------------------------
    // API & Search
    // -------------------------------------------------------------------------

    private suspend fun searchMangas(query: String, page: Int): FeedPage {
        val dedicatedResult = runCatching {
            val url = "$API_BASE/v1/search?q=${encode(query)}&page=$page&page_size=$SEARCH_PAGE_SIZE"
            val body = fetchApi(url)
            val root = json.parseToJsonElement(body).jsonObject
            val data = root.safeObject("data") ?: return@runCatching null
            val rawItems = data.safeArray("data") ?: data.safeArray("items") ?: return@runCatching null
            val items = rawItems.mapNotNull { (it as? JsonObject)?.let(::parseSearchMangaSummary) }
            val totalPages = data.safeInt("total_pages")
            val hasMoreFlag = data["has_more"]?.safePrimitiveContent()?.toBooleanStrictOrNull()
            val hasMore = when {
                totalPages != null -> page < totalPages
                hasMoreFlag != null -> hasMoreFlag
                else -> items.size >= SEARCH_PAGE_SIZE
            }
            FeedPage(
                items = items,
                nextPage = if (hasMore) page + 1 else null,
                hasMore = hasMore,
                totalCount = data.safeInt("total"),
                totalPages = totalPages,
                title = query,
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

        if (dedicatedResult != null) return dedicatedResult

        // Fallback to `/v1/mangas?keyword=...`
        val fallbackUrl = buildListUrl(section = null, page = page, query = query)
        val body = fetchApi(fallbackUrl)
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw SiteException.ParseError("嬉皮漫畫搜索数据格式异常", siteId = SiteId.HIPMH)
        val data = root.safeObject("data")
            ?: throw SiteException.ParseError("嬉皮漫畫搜索数据为空", siteId = SiteId.HIPMH)
        val items = data.safeArray("items").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseMangaSummary) }
        val totalPages = data.safeInt("total_pages") ?: page
        return FeedPage(
            items = items,
            nextPage = if (page < totalPages && items.isNotEmpty()) page + 1 else null,
            hasMore = page < totalPages && items.isNotEmpty(),
            totalCount = data.safeInt("total"),
            totalPages = totalPages,
            title = query,
        )
    }

    private fun buildListUrl(section: String?, page: Int, query: String): String {
        val params = mutableListOf<String>()
        if (query.isNotEmpty()) {
            params += "keyword=${encode(query)}"
        } else {
            when (val id = section?.trim()?.lowercase().orEmpty()) {
                "popular" -> params += "sort=popular"
                "weekly" -> params += "sort=popular"
                "new" -> params += "sort=latest"
                "ongoing" -> {
                    params += "status=ongoing"
                    params += "sort=updated"
                }
                "completed" -> {
                    params += "status=completed"
                    params += "sort=updated"
                }
                "cat_cn" -> params += "category=2"
                "cat_ko" -> params += "category=1"
                else -> {
                    GENRE_IDS[id]?.let { params += "genre=$it" }
                        ?: params.let { if (it.isEmpty()) it += "sort=updated" }
                }
            }
        }
        params += "page=$page"
        params += "per_page=$PAGE_SIZE"
        return "$API_BASE/v1/mangas?" + params.joinToString("&")
    }

    private fun parseMangaSummary(obj: JsonObject): MediaItem? {
        val mid = obj.safeString("mid").takeIf { it.isNotBlank() } ?: return null
        val title = obj.safeString("title").takeIf { it.isNotBlank() } ?: return null
        val coverPath = obj.safeString("vertical_image_url").ifBlank { obj.safeString("cover_image_url") }
        return MediaItem(
            siteId = SiteId.HIPMH,
            id = mid,
            url = "${descriptor.baseUrl}/works/$mid",
            kind = MediaKind.SERIAL_COMIC,
            title = HtmlUrlCleaner.cleanText(title),
            coverUrl = normalizeCoverUrl(coverPath),
        )
    }

    private fun parseSearchMangaSummary(obj: JsonObject): MediaItem? {
        val mid = obj.safeString("id").ifBlank { obj.safeString("mid") }.takeIf { it.isNotBlank() } ?: return null
        val title = obj.safeString("title").takeIf { it.isNotBlank() } ?: return null
        val coverPath = obj.safeString("vertical_image_url").ifBlank { obj.safeString("cover_image_url") }
        return MediaItem(
            siteId = SiteId.HIPMH,
            id = mid,
            url = "${descriptor.baseUrl}/works/$mid",
            kind = MediaKind.SERIAL_COMIC,
            title = HtmlUrlCleaner.cleanText(title),
            coverUrl = normalizeCoverUrl(coverPath),
        )
    }

    private fun normalizeCoverUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.isBlank() -> ""
            trimmed.startsWith("http", ignoreCase = true) -> trimmed
            else -> "$COVER_BASE/${trimmed.trimStart('/')}"
        }
    }

    private suspend fun loadChapters(
        mid: String,
        configMid: String? = null,
        throwOnError: Boolean = false,
    ): List<ChapterEntry> {
        // 章节 API 只接受 mid 的短前缀（`bTo3MjQ0-...` -> `bTo3MjQ0`）。
        val shortMid = configMid?.takeIf { it.isNotBlank() } ?: mid.substringBefore('-')
        val seen = LinkedHashMap<String, ChapterEntry>()
        var page = 1
        var totalPages = 1

        while (page <= totalPages && page <= MAX_CHAPTER_PAGES) {
            val body = try {
                fetchApi("$API_BASE/v1/manga/chapters?mid=${encode(shortMid)}&page=$page&per_page=$CHAPTER_PAGE_SIZE&order=asc")
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                if (page == 1) {
                    if (throwOnError) throw error
                    return emptyList()
                }
                break
            }
            if (body.isBlank()) break
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: break
            val data = root.safeObject("data") ?: break
            val items = data.safeArray("items").orEmpty()
            if (items.isEmpty()) break
            var addedThisPage = 0
            items.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val hid = obj.safeString("hid").takeIf { it.isNotBlank() } ?: return@forEach
                val title = HtmlUrlCleaner.cleanText(obj.safeString("title").ifBlank { "章节" })
                if (seen.putIfAbsent(hid, ChapterEntry(hid, title)) == null) {
                    addedThisPage++
                }
            }
            val declaredTotalPages = data.safeInt("total_pages")
            if (declaredTotalPages != null) {
                totalPages = declaredTotalPages.coerceAtLeast(page)
                if (addedThisPage == 0 || page >= totalPages) break
            } else {
                if (addedThisPage == 0 || items.size < CHAPTER_PAGE_SIZE) break
                totalPages = page + 1
            }
            page++
        }
        return seen.values.toList()
    }

    private suspend fun loadChapterFeed(mid: String): FeedPage {
        val chapters = loadChapters(mid, throwOnError = true)
        val items = chapters.map { chapter ->
            MediaItem(
                siteId = SiteId.HIPMH,
                id = chapter.hid,
                url = "$READER_BASE/chapter/${chapter.hid}",
                kind = MediaKind.SERIAL_COMIC,
                title = chapter.title,
                extra = mapOf("comicId" to mid, "mid" to mid, "chapterHid" to chapter.hid),
            )
        }
        return FeedPage(items = items, title = "全部章节")
    }

    private suspend fun loadChapterDetail(hid: String, altLine: Boolean): GalleryDetail {
        val data = loadChapterData(hid, forceReload = false)
        val pages = buildPages(hid, data, altLine)
        val title = listOf(data.mangaTitle, data.chapterTitle)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "章节 $hid" }
        val mid = if (data.mangaId.isNotBlank() && data.mangaSlug.isNotBlank()) {
            encodeBase64Url("m:${data.mangaId}") + "-" + data.mangaSlug
        } else {
            ""
        }
        return GalleryDetail(
            item = MediaItem(
                siteId = SiteId.HIPMH,
                id = hid,
                url = "$READER_BASE/chapter/$hid",
                kind = MediaKind.SERIAL_COMIC,
                title = title,
                pageCount = pages.size,
                extra = buildMap {
                    put("chapterHid", hid)
                    if (mid.isNotBlank()) put("comicId", mid)
                },
            ),
            pageCount = pages.size.coerceAtLeast(1),
            pages = pages,
            chaptersUrl = mid.takeIf { it.isNotBlank() }
                ?.let { "${descriptor.baseUrl}/works/$it#chapters" },
        )
    }

    private fun parseChapterHid(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOf("chapter")
        if (idx < 0) return null
        val candidate = segments.getOrNull(idx + 1) ?: return null
        return if (candidate == "go") {
            parsed.queryParameter("hid")?.takeIf { it.isNotBlank() }
        } else {
            candidate.takeIf { it.isNotBlank() }
        }
    }

    // -------------------------------------------------------------------------
    // Reader decoding (aligned with hipmh-v1.6.1 ChapterContentKt & dual reading lines)
    // -------------------------------------------------------------------------

    private suspend fun loadChapterPages(
        frontendHid: String,
        forceReload: Boolean,
        altLine: Boolean,
    ): List<GalleryPage> {
        val data = loadChapterData(frontendHid, forceReload = forceReload)
        return buildPages(frontendHid, data, altLine)
    }

    private fun buildPages(frontendHid: String, data: ChapterData, altLine: Boolean): List<GalleryPage> =
        data.imageUrls.mapIndexed { index, rawUrl ->
            val resolvedUrl = if (altLine) swapReadingLineHost(rawUrl) else rawUrl
            val pageBase = "$READER_BASE/chapter/$frontendHid"
            val pageUrl = if (altLine) "$pageBase?altLine=1#${index + 1}" else "$pageBase#${index + 1}"
            GalleryPage(
                index = index + 1,
                pageUrl = pageUrl,
                imageUrl = resolvedUrl,
            )
        }

    private fun swapReadingLineHost(url: String): String {
        val parsed = ComicUrl.parse(url) ?: return url
        val swapped = when (parsed.host) {
            "hip-tx-1.s3imgs.top" -> "hip-cf-1.s3imgs.top"
            "hip-cf-1.s3imgs.top" -> "hip-tx-1.s3imgs.top"
            "hip-tx-s1.s3imgs.top" -> "hip-cf-s1.s3imgs.top"
            "hip-cf-s1.s3imgs.top" -> "hip-tx-s1.s3imgs.top"
            else -> return url
        }
        return parsed.withHost(swapped).toString()
    }

    private suspend fun loadChapterData(frontendHid: String, forceReload: Boolean): ChapterData {
        if (frontendHid.isBlank()) return ChapterData("", "", emptyList())
        if (!forceReload) {
            chapterCache.get(frontendHid)?.let { return it }
        }
        val apiHid = buildApiHid(frontendHid)
            ?: throw SiteException.ParseError("嬉皮漫畫章节编号无法解析", siteId = SiteId.HIPMH)
        val body = fetchApi("$API_BASE/v2/chapter?hid=${encode(apiHid)}")
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw SiteException.ParseError("嬉皮漫畫章节数据格式异常", siteId = SiteId.HIPMH)
        val data = root.safeObject("data")
            ?: throw SiteException.ParseError("嬉皮漫畫章节数据为空", siteId = SiteId.HIPMH)
        val mangaTitle = data.safeString("manga_title")
        val chapterTitle = data.safeString("chapter_title")
        val mangaId = data.safeString("manga_id")
        val mangaSlug = data.safeString("manga_slug")
        val images = when (val raw = data["images"]) {
            is JsonArray -> raw.mapNotNull { it.safePrimitiveContent() }.toMutableList()
            is JsonPrimitive -> {
                val rawText = raw.safePrimitiveContent().orEmpty()
                if (rawText.isBlank()) return ChapterData(mangaTitle, chapterTitle, emptyList())
                decodeImages(rawText).toMutableList()
            }
            else -> return ChapterData(mangaTitle, chapterTitle, emptyList())
        }
        val orderId = data.safeLong("order_id") ?: -1L
        val sid = data.safeLong("sid") ?: -1L
        if (images.isNotEmpty() && orderId >= 0 && sid >= 0) {
            decoyIndex(images.size, orderId, sid)?.let { idx -> if (idx in images.indices) images.removeAt(idx) }
        }
        val line = data.safeInt("line") ?: 1
        val base = when (line) {
            2 -> "https://hip-cf-1.s3imgs.top"
            9 -> "https://hip-tx-s1.s3imgs.top"
            else -> "https://hip-tx-1.s3imgs.top"
        }
        val resolvedUrls = images.map { path ->
            when {
                path.startsWith("http") -> path
                path.startsWith("/") -> base + path
                else -> "$base/$path"
            }
        }
        val result = ChapterData(mangaTitle, chapterTitle, resolvedUrls, mangaId, mangaSlug)
        if (resolvedUrls.isNotEmpty()) {
            chapterCache.put(frontendHid, result)
        }
        return result
    }

    private fun buildApiHid(frontendHid: String): String? {
        val head = frontendHid.substringBefore('-')
        val suffix = frontendHid.substringAfter('-', "")
        val decoded = runCatching { decodeBase64Url(head) }.getOrNull() ?: return null
        val chapterPart = decoded.substringAfter('-', "")
        if (!chapterPart.startsWith("c:")) return null
        return encodeBase64Url(chapterPart) + if (suffix.isNotEmpty()) "-$suffix" else ""
    }

    private fun decodeImages(encoded: String): List<String> {
        if (!encoded.startsWith("qM9") || !encoded.endsWith("Z7") || encoded.length <= 10) {
            return emptyList()
        }
        val body = encoded.substring(3, encoded.length - 2)
        if (body.length < 6) return emptyList()
        val tailLength = (body.length - 5) / 3
        val headLength = (body.length - 5 - tailLength) / 2
        if (tailLength <= 0 || headLength <= 0) return emptyList()

        val sentinel1 = body.substring(headLength, headLength + 2)
        val sentinel2Start = body.length - 3 - tailLength
        val sentinel2 = body.substring(sentinel2Start, sentinel2Start + 3)
        val hasSentinels = sentinel1 == "Vx" && sentinel2 == "pL0"

        val head = body.take(headLength)
        val mid = body.substring(headLength + 2, sentinel2Start)
        val tail = body.takeLast(tailLength)
        val base64 = (tail + head + mid).chunked(7)
            .mapIndexed { index, block -> if (index % 2 == 1) block.reversed() else block }
            .joinToString("")
            .mapNotNull { ch ->
                val idx = ENCODED_ALPHABET.indexOf(ch)
                if (idx >= 0) DECODED_ALPHABET[idx] else null
            }
            .joinToString("")
        val decoded = runCatching { decodeBase64Url(base64) }.getOrNull() ?: return emptyList()
        val parsed = runCatching {
            (json.parseToJsonElement(decoded) as? JsonArray).orEmpty().mapNotNull { it.safePrimitiveContent() }
        }.getOrDefault(emptyList())
        if (!hasSentinels && parsed.isEmpty()) {
            throw SiteException.ParseError("嬉皮漫畫图片编码格式已变更", siteId = SiteId.HIPMH)
        }
        return parsed
    }

    private fun decoyIndex(size: Int, orderId: Long, sid: Long): Int? {
        if (size <= 1) return null
        val key = (sid.toBigInteger() * DECOY_SID_FACTOR)
            .xor(size.toBigInteger() * DECOY_SIZE_FACTOR)
            .mod(size.toBigInteger())
        return orderId.toBigInteger().xor(key).takeIf { it in BigInteger.ZERO..<size.toBigInteger() }?.toInt()
    }

    private fun decodeBase64Url(raw: String): String {
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        val bytes = Base64.decode(padded)
        return String(bytes, Charsets.UTF_8)
    }

    private fun encodeBase64Url(raw: String): String =
        Base64.UrlSafe.encode(raw.toByteArray(Charsets.UTF_8)).trimEnd('=')

    private suspend fun fetchApi(url: String): String {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.HIPMH,
                kind = SiteRequestKind.API,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Referer" to "${descriptor.baseUrl}/",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw e
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "嬉皮漫畫接口请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.HIPMH,
                cause = e,
            )
        }
        response.throwIfNotSuccessful(SiteId.HIPMH, descriptor.displayName)
        return response.bodyText()
    }

    private suspend fun fetchHtml(url: String): String {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.HIPMH,
                kind = SiteRequestKind.PAGE,
                headers = mapOf(
                    "Referer" to "${descriptor.baseUrl}/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "zh-TW,zh;q=0.9,en;q=0.8",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw e
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "嬉皮漫畫网络请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.HIPMH,
                cause = e,
            )
        }
        response.throwIfNotSuccessful(SiteId.HIPMH, descriptor.displayName)
        return response.bodyText()
    }

    private fun extractWorkSlug(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOf("works")
        if (idx >= 0 && idx + 1 < segments.size) {
            return segments[idx + 1].takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun isAltLine(url: String): Boolean =
        ComicUrl.parse(url)?.queryParameter("altLine") == "1"

    private data class ChapterEntry(val hid: String, val title: String)
    private data class ChapterData(
        val mangaTitle: String,
        val chapterTitle: String,
        val imageUrls: List<String>,
        val mangaId: String = "",
        val mangaSlug: String = "",
    )

    private fun sectionTitle(section: String?): String =
        SECTIONS.firstOrNull { it.id.equals(section, ignoreCase = true) }?.title ?: "最近更新"

    private fun encode(raw: String): String = HtmlUrlCleaner.encodeQueryParam(raw)

    companion object {
        val SITE_ID: SiteId = SiteId.HIPMH
        const val BASE_URL: String = "https://m.hipmh.com"
        const val READER_BASE: String = "https://reader.hipmh.top"
        const val API_BASE: String = "https://hipapi1.s3file.top"
        const val COVER_BASE: String = "https://cover.s3imgs.top"
        private const val PAGE_SIZE = 18
        private const val SEARCH_PAGE_SIZE = 20
        private const val CHAPTER_PAGE_SIZE = 100
        private const val MAX_CHAPTER_PAGES = 40
        private const val CHAPTER_CACHE_TTL_MS = 30 * 60 * 1_000L

        internal val SECTIONS: List<SiteSectionSpec> = listOf(
            SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST),
            SiteSectionSpec("popular", "人氣", defaultSort = FeedSort.POPULAR),
            SiteSectionSpec("weekly", "本週熱門", defaultSort = FeedSort.TRENDING),
            SiteSectionSpec("new", "最新上架", defaultSort = FeedSort.NEWEST),
            SiteSectionSpec("ongoing", "連載中"),
            SiteSectionSpec("completed", "已完結"),
            SiteSectionSpec("cat_cn", "國漫"),
            SiteSectionSpec("cat_ko", "韓漫"),
            SiteSectionSpec("genre_wu-xia", "武俠"),
            SiteSectionSpec("genre_xi-tong", "系統"),
            SiteSectionSpec("genre_mao-xian", "冒險"),
            SiteSectionSpec("genre_yi-neng", "異能"),
            SiteSectionSpec("genre_ju-qing", "劇情"),
            SiteSectionSpec("genre_gong-dou", "宮鬥"),
            SiteSectionSpec("genre_ni-xi", "逆襲"),
        )

        private val GENRE_IDS: Map<String, Int> = mapOf(
            "genre_wu-xia" to 39,
            "genre_xi-tong" to 67,
            "genre_mao-xian" to 38,
            "genre_yi-neng" to 51,
            "genre_ju-qing" to 3,
            "genre_gong-dou" to 44,
            "genre_ni-xi" to 32,
        )

        private const val ENCODED_ALPHABET = "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val DECODED_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val DECOY_SID_FACTOR = BigInteger.valueOf(0x9E3779B1L)
        private val DECOY_SIZE_FACTOR = BigInteger.valueOf(0x85EBCA6BL)

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "嬉皮漫畫",
            shortName = "嬉皮",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "m.hipmh.com",
            mirrorHosts = listOf("hipmh.com", "www.hipmh.com", "reader.hipmh.top"),
            accessNote = "支持 /v1/search 专用搜索、全分页章节与双线路图床自愈",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
            ),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR, FeedSort.TRENDING),
            sections = SECTIONS,
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$READER_BASE/",
                apiBase = API_BASE,
                apiHosts = setOf("hipapi1.s3file.top"),
                mediaDomains = setOf("hipmh.com", "hipmh.top", "s3imgs.top", "s3file.top"),
                pageIntervalMs = 400L,
                imageIntervalMs = 120L,
            ),
        )
    }
}
