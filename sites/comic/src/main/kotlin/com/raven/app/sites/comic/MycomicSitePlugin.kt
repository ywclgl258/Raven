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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/**
 * MyComic (`SiteId.MYCOMIC`) 连载漫画插件。
 *
 * 站点路由：
 * - 列表：`/comics?sort=-update&page=N`，筛选 `filter[audience|country|end]`
 * - 搜索：`/comics?keyword=<q>&page=N`
 * - 详情：`/comics/<id>`，章节 `/chapters/<id>`
 * - 排行榜：`/rank?sort=-week|-views`
 *
 * 章节阅读页由前端懒加载图片，插件按 `data-src` / `src` 等属性提取图页；
 * 若站点返回 Cloudflare 质询则抛出 [SiteException.Forbidden]（`isChallenge = true`）。
 */
class MycomicSitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(FeedRequest(siteId = SiteId.MYCOMIC, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.MYCOMIC, primaryFeedPage = page)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            val url = resolveFeedUrl(request.section, page, query)
            val html = fetchHtml(url)
            val doc = Jsoup.parse(html, url)
            val items = parseComicCards(doc)
            if (items.isEmpty()) {
                throw SiteException.ParseError("MyComic 列表结构不可识别", siteId = SiteId.MYCOMIC)
            }
            val hasMore = doc.selectFirst("a[href*='page=${page + 1}']") != null
            return FeedPage(
                items = items,
                nextPage = if (hasMore) page + 1 else null,
                hasMore = hasMore,
                title = query.ifEmpty { sectionTitle(request.section) },
            )
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> =
            listOf(FeedSort.NEWEST, FeedSort.POPULAR)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val comicId = extractComicId(url)
                ?: throw SiteException.ParseError("无法识别 MyComic 地址：$url", siteId = SiteId.MYCOMIC)
            val canonical = "${descriptor.baseUrl}/comics/$comicId"
            val html = fetchHtml(canonical)
            val doc = Jsoup.parse(html, canonical)
            val detailCard = doc.selectFirst("div[data-flux-card]")
            val title = detailCard?.selectFirst("div[data-flux-heading]")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                ?: HtmlUrlCleaner.cleanText(doc.title().substringBefore(" - ")).ifBlank { "Comic $comicId" }
            val cover = detailCard?.selectFirst("img.object-cover")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("img[src*=cover], img[data-src*=cover]")
                    ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                    .orEmpty()
            val chapters = parseChapterIds(doc)
            val firstChapterId = chapters.firstOrNull()
            val pages = if (firstChapterId != null) {
                loadChapterPages(firstChapterId)
            } else {
                emptyList()
            }
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.MYCOMIC,
                    id = comicId,
                    url = canonical,
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = cover,
                    pageCount = pages.size,
                    extra = buildMap {
                        put("chapterId", firstChapterId.orEmpty())
                    },
                ),
                pageCount = pages.size.coerceAtLeast(1),
                pages = pages,
                chaptersUrl = if (chapters.size > 1) "$canonical#chapters" else null,
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.MYCOMIC)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val chapterId = detail.item.extra["chapterId"].orEmpty()
            if (chapterId.isBlank()) {
                throw SiteException.ParseError("MyComic 章节信息缺失", siteId = SiteId.MYCOMIC)
            }
            val pages = loadChapterPages(chapterId)
            return pages.firstOrNull { it.index == pageIndex }
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${pages.size}", siteId = SiteId.MYCOMIC)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val parsed = ComicUrl.parse(pageUrl)
                ?: throw SiteException.ParseError("无法识别 MyComic 图页地址", siteId = SiteId.MYCOMIC)
            val chapterId = parsed.pathSegments.lastOrNull { it.all(Char::isDigit) }
                ?: throw SiteException.ParseError("无法识别 MyComic 章节编号", siteId = SiteId.MYCOMIC)
            val pageIndex = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val pages = loadChapterPages(chapterId)
            return pages.firstOrNull { it.index == pageIndex }
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${pages.size}", siteId = SiteId.MYCOMIC)
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
            return "${descriptor.baseUrl}/comics/$id"
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val id = extractComicId(parsed.toString()) ?: return null
            return ResolvedLink.Gallery(SiteId.MYCOMIC, id, "${descriptor.baseUrl}/comics/$id")
        }
    }

    // -------------------------------------------------------------------------
    // Feed / Detail Parsing
    // -------------------------------------------------------------------------

    private fun resolveFeedUrl(section: String?, page: Int, query: String): String {
        if (query.isNotEmpty()) {
            return "${descriptor.baseUrl}/comics?q=${HtmlUrlCleaner.encodeQueryParam(query)}&page=$page"
        }
        val path = when (section?.trim()?.lowercase()) {
            "rank_week" -> "/rank?sort=-week"
            "rank_views" -> "/rank?sort=-views"
            "new" -> "/comics?sort=-id"
            "completed" -> "/comics?filter%5Bend%5D=1"
            "ongoing" -> "/comics?filter%5Bend%5D=0"
            "cn" -> "/comics?filter%5Bcountry%5D=china"
            "jp" -> "/comics?filter%5Bcountry%5D=japan"
            "kr" -> "/comics?filter%5Bcountry%5D=korea"
            "hk" -> "/comics?filter%5Bcountry%5D=hongkong"
            else -> "/comics?sort=-update"
        }
        val base = "${descriptor.baseUrl}$path"
        return if (page <= 1) base else "$base&page=$page"
    }

    private fun sectionTitle(section: String?): String =
        SECTIONS.firstOrNull { it.id.equals(section, ignoreCase = true) }?.title ?: "最近更新"

    private fun parseComicCards(doc: org.jsoup.nodes.Document): List<MediaItem> {
        val byId = LinkedHashMap<String, MediaItem>()
        // Flux UI 列表：div.grid > div.group，标题取封面 img[alt]
        doc.select("div.grid > div.group").forEach { group ->
            val anchor = group.selectFirst("a") ?: return@forEach
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            val id = extractComicId(href) ?: return@forEach
            val img = group.selectFirst("img") ?: return@forEach
            val title = HtmlUrlCleaner.cleanText(img.attr("alt"))
            if (title.isBlank()) return@forEach
            val cover = img.attr("data-src").ifBlank { img.attr("src") }
            byId.putIfAbsent(
                id,
                MediaItem(
                    siteId = SiteId.MYCOMIC,
                    id = id,
                    url = "${descriptor.baseUrl}/comics/$id",
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = if (cover.startsWith("/")) "${descriptor.baseUrl}$cover" else cover,
                ),
            )
        }
        if (byId.isNotEmpty()) return byId.values.toList()

        // 兜底：直接扫描 /comics/<id> 链接，取文本最长的作为标题。
        val anchorsById = LinkedHashMap<String, MutableList<org.jsoup.nodes.Element>>()
        doc.select("a[href*='/comics/']").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.contains("?") || href.contains("#")) return@forEach
            val id = href.substringAfterLast("/").takeIf { it.all(Char::isDigit) } ?: return@forEach
            anchorsById.getOrPut(id) { mutableListOf() }.add(anchor)
        }
        anchorsById.forEach { (id, anchors) ->
            val titleAnchor = anchors.maxByOrNull { HtmlUrlCleaner.cleanText(it.text()).length } ?: return@forEach
            val title = HtmlUrlCleaner.cleanText(titleAnchor.attr("title").ifBlank { titleAnchor.text() })
            if (title.isBlank() || title.all { it.isDigit() }) return@forEach
            val container = titleAnchor.closest("div.group, li, article, .card, div") ?: titleAnchor
            val cover = container.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }.orEmpty()
            byId.putIfAbsent(
                id,
                MediaItem(
                    siteId = SiteId.MYCOMIC,
                    id = id,
                    url = "${descriptor.baseUrl}/comics/$id",
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = if (cover.startsWith("/")) "${descriptor.baseUrl}$cover" else cover,
                ),
            )
        }
        return byId.values.toList()
    }

    private fun parseChapterIds(doc: org.jsoup.nodes.Document): List<String> {
        val seen = LinkedHashSet<String>()
        // 章节列表内嵌在 x-data 属性：chapters: [{id, title, ...}]
        doc.select("div[data-flux-card] + div div[x-data]").forEach { element ->
            val raw = element.attr("x-data")
            CHAPTER_JSON_REGEX.find(raw)?.value?.let { jsonText ->
                runCatching { json.parseToJsonElement(jsonText).jsonArray }.getOrNull()
                    ?.mapNotNull { item ->
                        (item as? JsonObject)?.get("id")?.jsonPrimitive?.content
                            ?.takeIf { it.all(Char::isDigit) }
                    }
                    ?.forEach(seen::add)
            }
        }
        if (seen.isEmpty()) {
            doc.select("a[href*='/chapters/']").forEach { anchor ->
                val href = anchor.attr("href")
                val id = href.substringAfterLast("/").substringBefore("#").substringBefore("?")
                if (id.isNotBlank() && id.all(Char::isDigit)) seen.add(id)
            }
        }
        return seen.toList()
    }

    private suspend fun loadChapterPages(chapterId: String): List<GalleryPage> {
        val url = "${descriptor.baseUrl}/chapters/$chapterId"
        val html = fetchHtml(url)
        val images = extractChapterImages(html)
        if (images.isEmpty()) {
            throw SiteException.Unsupported(
                message = "MyComic 章节图页由前端动态加载，当前响应中未找到图片地址",
                siteId = SiteId.MYCOMIC,
            )
        }
        return images.mapIndexed { index, imageUrl ->
            GalleryPage(index = index + 1, pageUrl = "$url#${index + 1}", imageUrl = imageUrl)
        }
    }

    private fun extractChapterImages(html: String): List<String> {
        val ordered = LinkedHashMap<String, Unit>()
        // 阅读器图页：img[x-ref]（Alpine.js），优先使用 data-src
        runCatching {
            Jsoup.parse(html).select("img[x-ref]").forEach { img ->
                val raw = img.attr("data-src").ifBlank { img.attr("src") }.trim()
                if (isPageImage(raw)) ordered.putIfAbsent(raw, Unit)
            }
        }
        IMAGE_ATTRIBUTE_REGEX.findAll(html).forEach { match ->
            val raw = match.groupValues[1].replace("&amp;", "&").trim()
            if (isPageImage(raw)) ordered.putIfAbsent(raw, Unit)
        }
        if (ordered.isEmpty()) {
            // 兜底：脚本内数组/JSON 中的绝对图片地址
            IMAGE_URL_REGEX.findAll(html).forEach { match ->
                val raw = match.value.replace("&amp;", "&")
                if (isPageImage(raw)) ordered.putIfAbsent(raw, Unit)
            }
        }
        return ordered.keys.toList()
    }

    private fun isPageImage(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (!IMAGE_EXTENSION_REGEX.containsMatchIn(url)) return false
        val lower = url.lowercase()
        return BAD_IMAGE_KEYWORDS.none { it in lower }
    }

    private fun extractComicId(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOf("comics")
        if (idx >= 0 && idx + 1 < segments.size) {
            return segments[idx + 1].takeIf { it.all(Char::isDigit) }
        }
        return null
    }

    private suspend fun fetchHtml(url: String): String {
        val response = try {
            httpClient.getText(url = url, siteId = SiteId.MYCOMIC, kind = SiteRequestKind.PAGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw e
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "MyComic 网络请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.MYCOMIC,
                cause = e,
            )
        }
        val body = response.bodyText()
        if (response.code == 403 || body.contains("Just a moment", ignoreCase = true)) {
            throw SiteException.Forbidden(
                isChallenge = true,
                message = "MyComic 触发 Cloudflare 浏览器验证，请切换网络或代理后重试",
                siteId = SiteId.MYCOMIC,
            )
        }
        response.throwIfNotSuccessful(SiteId.MYCOMIC, descriptor.displayName)
        return body
    }

    companion object {
        val SITE_ID: SiteId = SiteId.MYCOMIC
        const val BASE_URL: String = "https://mycomic.com"

        private val CHAPTER_JSON_REGEX = Regex("""(?<=chapters: )\[\{.*?}]""")
        private val IMAGE_ATTRIBUTE_REGEX = Regex(
            """(?:data-src|data-original|data-lazy-src|data-echo|data-url|src)\s*=\s*["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val IMAGE_URL_REGEX = Regex(
            """https?://[^\s"'<>\\]+\.(?:jpe?g|png|webp|avif)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val IMAGE_EXTENSION_REGEX = Regex(
            """\.(?:jpe?g|png|webp|avif)(?:\?|$)""",
            RegexOption.IGNORE_CASE,
        )
        private val BAD_IMAGE_KEYWORDS = listOf(
            "logo", "favicon", "avatar", "icon", "banner", "ads/", "/ad.", "placeholder", "cover",
        )

        internal val SECTIONS: List<SiteSectionSpec> = listOf(
            SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST),
            SiteSectionSpec("new", "最新上架", defaultSort = FeedSort.NEWEST),
            SiteSectionSpec("rank_week", "周榜", defaultSort = FeedSort.POPULAR),
            SiteSectionSpec("rank_views", "人气榜", defaultSort = FeedSort.POPULAR),
            SiteSectionSpec("ongoing", "连载中"),
            SiteSectionSpec("completed", "已完结"),
            SiteSectionSpec("cn", "国漫"),
            SiteSectionSpec("jp", "日漫"),
            SiteSectionSpec("kr", "韩漫"),
            SiteSectionSpec("hk", "港漫"),
        )

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "MyComic",
            shortName = "MyComic",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "mycomic.com",
            accessNote = "站点启用 Cloudflare 质询，可能需要代理",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = SECTIONS,
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DESKTOP_WINDOWS_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
            ),
        )
    }
}