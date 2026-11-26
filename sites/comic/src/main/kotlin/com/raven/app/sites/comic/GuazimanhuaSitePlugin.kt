package com.raven.app.sites.comic

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
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup

/**
 * 瓜子漫画 (`SiteId.GUAZIMANHUA`) 连载漫画插件。
 *
 * 站点为服务端渲染的 PHP 站点：
 * - 列表页：`/update.php`、`/category.php`、搜索 `/search.php?q=`
 * - 详情页：`/comic.php?id=<comicId>`，包含 `<h1>` 标题、`application/ld+json` 章节目录
 * - 阅读页：`/chapter.php?id=<chapterId>`，所有图页 URL 直接内嵌在 HTML 中
 *
 * 本插件把「章节」映射为一个图库：详情页默认打开第一章，页数为该章节图页数量；
 * 章节列表通过 `section = "chapters"` 的 Feed 请求暴露。
 */
class GuazimanhuaSitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(FeedRequest(siteId = SiteId.GUAZIMANHUA, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.GUAZIMANHUA, primaryFeedPage = page)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            if (request.section.equals(CHAPTERS_SECTION, ignoreCase = true)) {
                val comicId = request.category?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?: request.query?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?: throw SiteException.ParseError("章节请求缺少漫画编号", siteId = SiteId.GUAZIMANHUA)
                return buildChapterFeed(comicId)
            }
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            val url = resolveFeedUrl(request, page, query)
            val html = fetchHtml(url)
            val doc = Jsoup.parse(html, url)
            val items = parseComicCards(doc)
            if (items.isEmpty()) {
                if (query.isNotEmpty()) {
                    return FeedPage(items = emptyList(), nextPage = null, hasMore = false, title = query)
                }
                throw SiteException.ParseError("瓜子漫画列表结构不可识别", siteId = SiteId.GUAZIMANHUA)
            }
            val hasMore = doc.selectFirst("a[href*='page=${page + 1}']") != null ||
                html.contains("rel=\"next\"", ignoreCase = true)
            return FeedPage(
                items = items,
                nextPage = if (hasMore) page + 1 else null,
                hasMore = hasMore,
                title = query.ifEmpty { sectionTitle(request.section) },
            )
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = FEED_SORTS
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            if (isChapterUrl(url)) {
                return loadChapterDetail(url)
            }
            val comicId = extractComicId(url)
                ?: throw SiteException.ParseError("无法识别瓜子漫画地址：$url", siteId = SiteId.GUAZIMANHUA)
            val canonical = "${descriptor.baseUrl}/comic.php?id=$comicId"
            val html = fetchHtml(canonical)
            val doc = Jsoup.parse(html, canonical)
            val title = HtmlUrlCleaner.cleanText(doc.selectFirst("h1")?.text().orEmpty())
                .ifBlank { "漫画 $comicId" }
            val coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            val chapters = parseChapterEntries(doc)
            val firstChapter = chapters.firstOrNull()
            val pages = if (firstChapter != null) {
                loadChapterPages(firstChapter.id)
            } else {
                emptyList()
            }
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.GUAZIMANHUA,
                    id = comicId,
                    url = canonical,
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = coverUrl,
                    pageCount = pages.size,
                    extra = buildMap {
                        put("chapterId", firstChapter?.id.orEmpty())
                        put("chapterTitle", firstChapter?.title.orEmpty())
                    },
                ),
                pageCount = pages.size.coerceAtLeast(1),
                pages = pages,
                chaptersUrl = if (chapters.size > 1) "$canonical#$CHAPTERS_FRAGMENT" else null,
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.GUAZIMANHUA)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val chapterId = detail.item.extra["chapterId"].orEmpty().ifBlank {
                detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl
                    ?.let { ComicUrl.parse(it)?.queryParameter("id") }
                    .orEmpty()
            }
            if (chapterId.isBlank()) {
                throw SiteException.ParseError("瓜子漫画章节信息缺失", siteId = SiteId.GUAZIMANHUA)
            }
            val pages = loadChapterPages(chapterId)
            return pages.firstOrNull { it.index == pageIndex }
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${pages.size}", siteId = SiteId.GUAZIMANHUA)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val identity = parseChapterPageIdentity(pageUrl)
                ?: throw SiteException.ParseError("无法识别瓜子漫画图页地址：$pageUrl", siteId = SiteId.GUAZIMANHUA)
            val pages = loadChapterPages(identity.chapterId)
            return pages.firstOrNull { it.index == identity.pageIndex }
                ?: throw SiteException.NotFound("页码超出范围：${identity.pageIndex} / ${pages.size}", siteId = SiteId.GUAZIMANHUA)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val id = extractComicId(parsed.toString()) ?: return null
            return "${descriptor.baseUrl}/comic.php?id=$id"
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val id = extractComicId(parsed.toString()) ?: return null
            return ResolvedLink.Gallery(SiteId.GUAZIMANHUA, id, "${descriptor.baseUrl}/comic.php?id=$id")
        }
    }

    // -------------------------------------------------------------------------
    // Feed URL & Sections
    // -------------------------------------------------------------------------

    private fun resolveFeedUrl(request: FeedRequest, page: Int, query: String): String {
        if (query.isNotEmpty()) {
            return "${descriptor.baseUrl}/category.php?keyword=${HtmlUrlCleaner.encodeQueryParam(query)}" +
                "&sort=hits&page=$page"
        }
        val section = request.section?.trim()?.lowercase().orEmpty()
        val params = mutableListOf<String>()
        when {
            section.startsWith("cid_") && section.removePrefix("cid_").let { it.isNotEmpty() && it.all(Char::isDigit) } ->
                params += "cid=${section.removePrefix("cid_")}"
            section.startsWith("city_") && section.removePrefix("city_").let { it.isNotEmpty() && it.all(Char::isDigit) } ->
                params += "city=${section.removePrefix("city_")}"
            section.startsWith("aud_") && section.removePrefix("aud_").let { it.isNotEmpty() && it.all(Char::isDigit) } ->
                params += "audience=${section.removePrefix("aud_")}"
            section.startsWith("end_") && section.removePrefix("end_").let { it.isNotEmpty() && it.all(Char::isDigit) } ->
                params += "is_end=${section.removePrefix("end_")}"
        }
        request.category?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { params += "cid=$it" }
        val sort = sortParam(request.sort)
            ?: if (section == "popular") "hits" else "update"
        params += "sort=$sort"
        params += "page=$page"
        return "${descriptor.baseUrl}/category.php?" + params.joinToString("&")
    }

    private fun sortParam(sort: FeedSort): String? = when (sort) {
        FeedSort.NEWEST -> "update"
        FeedSort.POPULAR -> "hits"
        FeedSort.TRENDING -> "daily"
        FeedSort.RATING -> "score"
        else -> null
    }

    private fun sectionTitle(section: String?): String =
        SECTIONS.firstOrNull { it.id.equals(section, ignoreCase = true) }?.title ?: "最近更新"

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private fun parseComicCards(doc: org.jsoup.nodes.Document): List<MediaItem> {
        val seen = LinkedHashMap<String, MediaItem>()
        doc.select("a[href*='comic.php?id=']").forEach { anchor ->
            val href = anchor.attr("href")
            val id = href.substringAfter("id=", "").substringBefore("&").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?: return@forEach
            val container = anchor.closest("li, .comic-card, .item, article, .book-item") ?: anchor
            val title = HtmlUrlCleaner.cleanText(
                container.selectFirst(".title, .comic-title, h3, h4")?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?: anchor.attr("title").ifBlank { anchor.text() },
            )
            if (title.isBlank()) return@forEach
            val cover = container.selectFirst("img")?.let(::resolveImageSource) ?: ""
            seen.putIfAbsent(
                id,
                MediaItem(
                    siteId = SiteId.GUAZIMANHUA,
                    id = id,
                    url = "${descriptor.baseUrl}/comic.php?id=$id",
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = cover,
                ),
            )
        }
        return seen.values.toList()
    }

    private fun resolveImageSource(img: org.jsoup.nodes.Element): String {
        val raw = img.attr("data-src").ifBlank { img.attr("data-original") }
            .ifBlank { img.attr("src") }
        if (raw.isBlank()) return ""
        return if (raw.startsWith("//")) "https:$raw" else raw
    }

    private fun parseChapterEntries(doc: org.jsoup.nodes.Document): List<ChapterEntry> {
        val seen = LinkedHashMap<String, ChapterEntry>()
        doc.select("script[type=application/ld+json]").forEach { script ->
            val root = runCatching { json.parseToJsonElement(script.data()) }.getOrNull() ?: return@forEach
            collectChapterNodes(root).forEach { node ->
                val url = node.safeString("url")
                val id = url.substringAfter("id=", "").substringBefore("&").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?: return@forEach
                val name = node.safeString("name")
                seen.putIfAbsent(id, ChapterEntry(id, name.ifBlank { "第 ${seen.size + 1} 话" }))
            }
        }
        if (seen.isEmpty()) {
            doc.select("a[href*='chapter.php?id=']").forEach { anchor ->
                val id = anchor.attr("href").substringAfter("id=", "").substringBefore("&")
                    .takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@forEach
                val name = HtmlUrlCleaner.cleanText(anchor.attr("title").ifBlank { anchor.text() })
                    .ifBlank { "第 ${seen.size + 1} 话" }
                seen.putIfAbsent(id, ChapterEntry(id, name))
            }
        }
        return seen.values.sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }
    }

    private fun collectChapterNodes(element: kotlinx.serialization.json.JsonElement): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        when (element) {
            is JsonArray -> element.forEach { out += collectChapterNodes(it) }
            is JsonObject -> {
                if (element.safeString("url").contains("chapter.php?id=")) {
                    out += element
                }
                // JSON-LD 允许任意嵌套（@graph / itemListElement / mainEntity 等），统一递归。
                element.values.forEach { out += collectChapterNodes(it) }
            }
            else -> Unit
        }
        return out
    }

    private fun isChapterUrl(url: String): Boolean =
        ComicUrl.parse(url)?.encodedPath?.endsWith("/chapter.php") == true

    private suspend fun loadChapterDetail(url: String): GalleryDetail {
        val chapterId = ComicUrl.parse(url)?.queryParameter("id")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: throw SiteException.ParseError("无法识别瓜子漫画章节地址：$url", siteId = SiteId.GUAZIMANHUA)
        val html = fetchHtml(url)
        val doc = Jsoup.parse(html, url)
        val comicId = doc.selectFirst("a[href*='comic.php?id=']")?.attr("href")
            ?.substringAfter("id=", "")?.substringBefore("&")
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            .orEmpty()
        val title = HtmlUrlCleaner.cleanText(doc.title().substringBefore("漫画在线阅读"))
            .ifBlank { "章节 $chapterId" }
        val pages = loadChapterPages(chapterId, preloadedHtml = html)
        return GalleryDetail(
            item = MediaItem(
                siteId = SiteId.GUAZIMANHUA,
                id = if (comicId.isNotBlank()) "$comicId:$chapterId" else chapterId,
                url = url,
                kind = MediaKind.SERIAL_COMIC,
                title = title,
                pageCount = pages.size,
                extra = buildMap {
                    put("chapterId", chapterId)
                    if (comicId.isNotBlank()) put("comicId", comicId)
                },
            ),
            pageCount = pages.size.coerceAtLeast(1),
            pages = pages,
            chaptersUrl = comicId.takeIf { it.isNotBlank() }
                ?.let { "${descriptor.baseUrl}/comic.php?id=$it#chapters" },
        )
    }

    private suspend fun loadChapterPages(
        chapterId: String,
        preloadedHtml: String? = null,
    ): List<GalleryPage> {
        val url = "${descriptor.baseUrl}/chapter.php?id=$chapterId"
        val html = preloadedHtml ?: fetchHtml(url)
        val seen = LinkedHashMap<String, Int>()
        CHAPTER_IMAGE_REGEX.findAll(html).forEach { match ->
            val imageUrl = match.value.replace("&amp;", "&")
            seen.putIfAbsent(imageUrl, seen.size + 1)
        }
        return seen.map { (imageUrl, index) ->
            GalleryPage(
                index = index,
                pageUrl = "$url#$index",
                imageUrl = imageUrl,
            )
        }
    }

    private fun parseChapterPageIdentity(pageUrl: String): ChapterPageIdentity? {
        val parsed = ComicUrl.parse(pageUrl) ?: return null
        val chapterId = parsed.queryParameter("id")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val pageIndex = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        return ChapterPageIdentity(chapterId, pageIndex)
    }

    private fun extractComicId(url: String): String? {
        if (isChapterUrl(url)) return null
        val parsed = ComicUrl.parse(url) ?: return null
        parsed.queryParameter("id")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { return it }
        return parsed.pathSegments.lastOrNull()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private suspend fun fetchHtml(url: String): String {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.GUAZIMANHUA,
                kind = SiteRequestKind.PAGE,
                headers = mapOf(
                    "Referer" to "${descriptor.baseUrl}/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw e
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "瓜子漫画网络请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.GUAZIMANHUA,
                cause = e,
            )
        }
        response.throwIfNotSuccessful(SiteId.GUAZIMANHUA, descriptor.displayName)
        return response.bodyText()
    }

    private data class ChapterEntry(val id: String, val title: String)
    private data class ChapterPageIdentity(val chapterId: String, val pageIndex: Int)

    // -------------------------------------------------------------------------
    // 章节 Feed（供阅读器/详情页展示章节列表）
    // -------------------------------------------------------------------------

    private suspend fun buildChapterFeed(comicId: String): FeedPage {
        val canonical = "${descriptor.baseUrl}/comic.php?id=$comicId"
        val html = fetchHtml(canonical)
        val doc = Jsoup.parse(html, canonical)
        val chapters = parseChapterEntries(doc)
        val items = chapters.map { chapter ->
            MediaItem(
                siteId = SiteId.GUAZIMANHUA,
                id = "$comicId:${chapter.id}",
                url = "${descriptor.baseUrl}/chapter.php?id=${chapter.id}",
                kind = MediaKind.SERIAL_COMIC,
                title = chapter.title,
                extra = mapOf("comicId" to comicId, "chapterId" to chapter.id),
            )
        }
        return FeedPage(items = items, hasMore = false, title = "全部章节")
    }

    companion object {
        val SITE_ID: SiteId = SiteId.GUAZIMANHUA
        const val BASE_URL: String = "https://www.guazimanhua.com"
        const val CHAPTERS_SECTION: String = "chapters"
        const val CHAPTERS_FRAGMENT: String = "chapters"

        private val CHAPTER_IMAGE_REGEX =
            Regex("""https://img\.guazicdn\.com/th/comics/chapters/[^\s"'<>\\]+""")

        private val FEED_SORTS = listOf(
            FeedSort.NEWEST,
            FeedSort.POPULAR,
            FeedSort.TRENDING,
            FeedSort.RATING,
        )

        /**
         * 站点 `/category.php` 暴露的完整筛选维度（题材 / 地区 / 受众 / 连载状态）。
         * section id 编码为 `cid_<id>` / `city_<id>` / `aud_<id>` / `end_<id>`，
         * 由 `resolveFeedUrl` 还原为对应查询参数。
         */
        internal val SECTIONS: List<SiteSectionSpec> = buildList {
            add(SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST))
            add(SiteSectionSpec("popular", "人气榜", defaultSort = FeedSort.POPULAR))
            listOf(
                "cid_41" to "耽美", "cid_9" to "恋爱", "cid_29" to "校园", "cid_5" to "霸总",
                "cid_42" to "都市", "cid_8" to "穿越", "cid_23" to "古风", "cid_25" to "玄幻",
                "cid_31" to "奇幻", "cid_22" to "科幻", "cid_21" to "灵异", "cid_54" to "动作",
                "cid_11" to "悬疑", "cid_30" to "冒险", "cid_15" to "搞笑", "cid_13" to "热血",
                "cid_14" to "恐怖", "cid_148" to "系统", "cid_97" to "逆袭", "cid_55" to "脑洞",
                "cid_61" to "复仇", "cid_17" to "真人", "cid_27" to "其它",
            ).forEach { (id, title) -> add(SiteSectionSpec(id, title, category = id)) }
            listOf(
                "city_42" to "大陆", "city_43" to "欧美", "city_77" to "港台",
                "city_78" to "日韩", "city_338" to "国漫",
            ).forEach { (id, title) -> add(SiteSectionSpec(id, title, category = id)) }
            listOf("aud_1" to "男频", "aud_2" to "女频")
                .forEach { (id, title) -> add(SiteSectionSpec(id, title, category = id)) }
            listOf("end_2" to "连载中", "end_1" to "已完结")
                .forEach { (id, title) -> add(SiteSectionSpec(id, title, category = id)) }
        }

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "瓜子漫画",
            shortName = "瓜子",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "www.guazimanhua.com",
            accessNote = "连载国漫站点，详情页自动打开第一章",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH),
            feedSorts = FEED_SORTS,
            sections = SECTIONS,
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DESKTOP_WINDOWS_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                mediaDomains = setOf("img.guazicdn.com"),
            ),
        )
    }
}