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
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 漫画160 (`SiteId.MH160`) 连载漫画插件。
 *
 * 站点为晴天 CMS（qTcms）双模板：
 * - 列表：移动端 `https://m.mh160ba.com/kanmanhua/zaixian_recent.html`（`.itemBox`）
 *   与分类页 `/kanmanhua/<slug>`（`#listbody li`）
 * - 搜索：`https://m.mh160ba.com/statics/search.aspx?key=<q>`（`#listbody li`）
 * - 详情：`https://www.mh160ba.com/kanmanhua/<id>/`（`ul[id^=chapterList_ul] li a`）
 * - 阅读：章节页内嵌 `qTcms_S_m_murl_e`（Base64），以 `$qingtiandy$` 分隔图页路径；
 *   图片主机按 `qTcms_S_p_id` 选择 `*.kingwar.cn` 或旧版 `mhpic6.kingwar.cn`。
 */
class Mh160SitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(FeedRequest(siteId = SiteId.MH160, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.MH160, primaryFeedPage = page)
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
                    ?: throw SiteException.ParseError("章节请求缺少作品编号", siteId = SiteId.MH160)
                return loadChapterFeed(comicId)
            }
            val path = SECTION_PATHS[section] ?: "zaixian_recent.html"
            val url = "${MOBILE_BASE}/kanmanhua/$path"
            val doc = Jsoup.parse(fetchHtml(url), url)
            val items = parseMobileList(doc).ifEmpty { parseSearchList(doc) }
            if (items.isEmpty()) {
                throw SiteException.ParseError("漫画160 列表结构不可识别", siteId = SiteId.MH160)
            }
            return FeedPage(
                items = items,
                hasMore = false,
                title = sectionTitle(section),
            )
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> =
            listOf(FeedSort.NEWEST, FeedSort.POPULAR)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage {
            val query = request.query?.trim().orEmpty()
            if (query.isEmpty()) {
                throw SiteException.ParseError("漫画160 搜索关键词不能为空", siteId = SiteId.MH160)
            }
            return searchComics(query, request.page.coerceAtLeast(1))
        }

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val chapterRef = parseChapterRef(url)
            if (chapterRef != null) {
                return loadChapterDetail(chapterRef)
            }
            val comicId = extractComicId(url)
                ?: throw SiteException.ParseError("无法识别漫画160地址：$url", siteId = SiteId.MH160)
            val canonical = "${descriptor.baseUrl}/kanmanhua/$comicId/"
            val doc = Jsoup.parse(fetchHtml(canonical), canonical)
            val title = parseTitle(doc)
            val cover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            val description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            val chapters = parseChapters(doc)
            // parseChapters 已按阅读顺序（最早 → 最新）返回。
            val first = chapters.firstOrNull()
            val firstChapterId = first?.let { parseChapterRef(it.url)?.chapterId }
            val pages = if (first != null) loadChapterPages(first.url) else emptyList()
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.MH160,
                    id = comicId,
                    url = canonical,
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = cover,
                    pageCount = pages.size,
                    extra = buildMap {
                        put("chapterUrl", first?.url.orEmpty())
                        if (!firstChapterId.isNullOrBlank()) put("chapterId", firstChapterId)
                    },
                ),
                description = description,
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
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.MH160)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val chapterUrl = detail.item.extra["chapterUrl"].orEmpty().ifBlank {
                detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl
                    ?.let { ComicUrl.parse(it)?.withFragment(null)?.toString() }
                    .orEmpty()
            }
            if (chapterUrl.isBlank()) {
                throw SiteException.ParseError("漫画160 章节信息缺失", siteId = SiteId.MH160)
            }
            val pages = loadChapterPages(chapterUrl)
            return pages.firstOrNull { it.index == pageIndex }
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${pages.size}", siteId = SiteId.MH160)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val parsed = ComicUrl.parse(pageUrl)
                ?: throw SiteException.ParseError("无法识别漫画160图页地址", siteId = SiteId.MH160)
            val pageIndex = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val chapterUrl = parsed.withFragment(null).toString()
            val pages = loadChapterPages(chapterUrl)
            return pages.firstOrNull { it.index == pageIndex }
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${pages.size}", siteId = SiteId.MH160)
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
            return "${descriptor.baseUrl}/kanmanhua/$id/"
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val id = extractComicId(parsed.toString()) ?: return null
            return ResolvedLink.Gallery(SiteId.MH160, id, "${descriptor.baseUrl}/kanmanhua/$id/")
        }
    }

    // -------------------------------------------------------------------------
    // List & Search
    // -------------------------------------------------------------------------

    private suspend fun searchComics(query: String, page: Int): FeedPage {
        val url = "${MOBILE_BASE}/statics/search.aspx?key=${HtmlUrlCleaner.encodeQueryParam(query)}&page=$page"
        val doc = Jsoup.parse(fetchHtml(url), url)
        val items = parseSearchList(doc)
        if (items.isEmpty()) {
            return FeedPage(items = emptyList(), title = query, hasMore = false)
        }
        return FeedPage(items = items, title = query, hasMore = false)
    }

    private suspend fun loadChapterFeed(comicId: String): FeedPage {
        val canonical = "${descriptor.baseUrl}/kanmanhua/$comicId/"
        val doc = Jsoup.parse(fetchHtml(canonical), canonical)
        val chapters = parseChapters(doc)
        val items = chapters.map { chapter ->
            val chapterId = chapter.url.substringAfterLast("/").substringBefore(".")
            MediaItem(
                siteId = SiteId.MH160,
                id = "$comicId:$chapterId",
                url = chapter.url,
                kind = MediaKind.SERIAL_COMIC,
                title = chapter.title.ifBlank { "章节 $chapterId" },
                extra = mapOf("comicId" to comicId, "chapterUrl" to chapter.url),
            )
        }
        return FeedPage(items = items, title = "全部章节")
    }

    private suspend fun loadChapterDetail(ref: ChapterRef): GalleryDetail {
        val html = fetchHtml(ref.url)
        val doc = Jsoup.parse(html, ref.url)
        val title = parseTitle(doc).ifBlank { "章节 ${ref.chapterId}" }
        val pages = loadChapterPages(ref.url, preloadedHtml = html)
        return GalleryDetail(
            item = MediaItem(
                siteId = SiteId.MH160,
                id = "${ref.comicId}:${ref.chapterId}",
                url = ref.url,
                kind = MediaKind.SERIAL_COMIC,
                title = title,
                pageCount = pages.size,
                extra = mapOf("comicId" to ref.comicId, "chapterUrl" to ref.url),
            ),
            pageCount = pages.size.coerceAtLeast(1),
            pages = pages,
            chaptersUrl = "${descriptor.baseUrl}/kanmanhua/${ref.comicId}/#chapters",
        )
    }

    private fun parseChapterRef(url: String): ChapterRef? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOf("kanmanhua")
        if (idx < 0 || idx + 2 > segments.lastIndex) return null
        val chapterFile = segments[idx + 2]
        if (!chapterFile.contains('.')) return null
        return ChapterRef(
            comicId = segments[idx + 1],
            chapterId = chapterFile.substringBefore('.'),
            url = "${descriptor.baseUrl}/kanmanhua/${segments[idx + 1]}/$chapterFile",
        )
    }

    private fun parseMobileList(doc: Document): List<MediaItem> {
        val seen = LinkedHashMap<String, MediaItem>()
        doc.select(".itemBox").forEach { box ->
            val link = box.selectFirst(".itemTxt a.title") ?: return@forEach
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val id = extractComicId(href) ?: return@forEach
            val title = HtmlUrlCleaner.cleanText(link.text())
            if (title.isBlank()) return@forEach
            seen.putIfAbsent(
                id,
                MediaItem(
                    siteId = SiteId.MH160,
                    id = id,
                    url = "${descriptor.baseUrl}/kanmanhua/$id/",
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = box.selectFirst(".itemImg img")?.absUrl("src").orEmpty(),
                ),
            )
        }
        return seen.values.toList()
    }

    private fun parseSearchList(doc: Document): List<MediaItem> {
        val seen = LinkedHashMap<String, MediaItem>()
        doc.select("#listbody li").forEach { li ->
            val link = li.selectFirst("a.ImgA") ?: return@forEach
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val id = extractComicId(href) ?: return@forEach
            val title = HtmlUrlCleaner.cleanText(li.selectFirst("a.txtA")?.text().orEmpty())
                .ifBlank {
                    HtmlUrlCleaner.cleanText(
                        li.select("a").firstOrNull { it.text().isNotBlank() }?.text().orEmpty(),
                    )
                }
                .ifBlank { href.trim('/').substringAfterLast('/') }
            if (title.isBlank()) return@forEach
            seen.putIfAbsent(
                id,
                MediaItem(
                    siteId = SiteId.MH160,
                    id = id,
                    url = "${descriptor.baseUrl}/kanmanhua/$id/",
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = link.selectFirst("img")?.absUrl("src").orEmpty(),
                ),
            )
        }
        return seen.values.toList()
    }

    // -------------------------------------------------------------------------
    // Detail & Reader
    // -------------------------------------------------------------------------

    private fun parseTitle(doc: Document): String {
        val raw = doc.title()
        val byComma = raw.substringBefore("漫画,").trim()
        if (byComma.isNotBlank() && byComma != raw) return byComma.removeSuffix("漫画").trim()
        return raw.substringBefore("-").trim().ifBlank { "漫画160" }
    }

    private fun parseChapters(doc: Document): List<ChapterEntry> {
        val seen = LinkedHashMap<String, ChapterEntry>()
        doc.select("ul[id^=chapterList_ul] li a, ul[id^=mh-chapter-list-ol] li a").forEach { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@forEach
            val name = HtmlUrlCleaner.cleanText(link.selectFirst("p")?.text() ?: link.text())
            seen.putIfAbsent(href, ChapterEntry(href, name))
        }
        return seen.values.toList().reversed()
    }

    private suspend fun loadChapterPages(
        chapterUrl: String,
        preloadedHtml: String? = null,
    ): List<GalleryPage> {
        val html = preloadedHtml ?: fetchHtml(chapterUrl)
        val encoded = MURL_REGEX.find(html)?.groupValues?.get(1)
            ?: throw SiteException.ParseError("漫画160 找不到图片列表，页面结构可能已更改", siteId = SiteId.MH160)
        val decoded = runCatching {
            String(Base64.decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: throw SiteException.ParseError("漫画160 图片列表解码失败", siteId = SiteId.MH160)
        if (decoded.contains("+http://") || decoded.contains("+https://")) {
            throw SiteException.Unsupported("漫画160 该章节已下架", siteId = SiteId.MH160)
        }
        val chapterId = PID_REGEX.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val host = if (chapterId > NEW_HOST_THRESHOLD) {
            IMAGE_HOSTS[(chapterId % IMAGE_HOSTS.size).toInt()]
        } else {
            LEGACY_IMAGE_HOST
        }
        val paths = decoded.split(PATH_SEPARATOR).filter { it.isNotBlank() }
        if (paths.isEmpty()) {
            throw SiteException.ParseError("漫画160 章节没有图页", siteId = SiteId.MH160)
        }
        return paths.mapIndexed { index, path ->
            val imageUrl = when {
                path.startsWith("http") -> path
                path.startsWith("/") -> host + encodePath(path)
                else -> "${descriptor.baseUrl}/statics/pic/?p=" + URLEncoder.encode(path, "UTF-8")
            }
            GalleryPage(index = index + 1, pageUrl = "$chapterUrl#${index + 1}", imageUrl = imageUrl)
        }
    }

    private fun encodePath(path: String): String = path.split("/").joinToString("/") { segment ->
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    private fun extractComicId(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val idx = parsed.pathSegments.indexOf("kanmanhua")
        if (idx < 0 || idx + 1 > parsed.pathSegments.lastIndex) return null
        return parsed.pathSegments[idx + 1].takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun sectionTitle(section: String): String =
        SECTIONS.firstOrNull { it.id == section }?.title ?: "最近更新"

    private suspend fun fetchHtml(url: String): String {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.MH160,
                kind = SiteRequestKind.PAGE,
                headers = mapOf(
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
                message = "漫画160 网络请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.MH160,
                cause = e,
            )
        }
        response.throwIfNotSuccessful(SiteId.MH160, descriptor.displayName)
        return response.bodyText()
    }

    private data class ChapterEntry(val url: String, val title: String)
    private data class ChapterRef(val comicId: String, val chapterId: String, val url: String)

    companion object {
        val SITE_ID: SiteId = SiteId.MH160
        const val BASE_URL: String = "https://www.mh160ba.com"
        private const val MOBILE_BASE: String = "https://m.mh160ba.com"

        private const val PATH_SEPARATOR = "\$qingtiandy\$"
        private const val NEW_HOST_THRESHOLD = 542_724L
        private const val LEGACY_IMAGE_HOST = "https://mhpic6.kingwar.cn"
        private val IMAGE_HOSTS = listOf(
            "https://xwdf.kingwar.cn",
            "https://mhreswhm.kingwar.cn",
            "https://qwe123.kingwar.cn",
            "https://resmhpic.kingwar.cn",
            "https://reszxc.kingwar.cn",
        )
        private val MURL_REGEX = Regex("""qTcms_S_m_murl_e\s*=\s*"([^"]+)"""")
        private val PID_REGEX = Regex("""qTcms_S_p_id\s*=\s*"(\d+)"""")

        /** 移动端分类路径（与站点 `CATEGORY_OPTIONS` 一致）。 */
        private val CATEGORY_PATHS: List<Pair<String, String?>> = listOf(
            "连载中" to "lianzai",
            "完结" to "wanjie",
            "日韩" to "zaixian_rhmh",
            "内地" to "zaixian_dlmh",
            "港台" to "zaixian_gtmh",
            "欧美" to "zaixian_ommh",
            "其他" to "zaixian_qita",
            "少年" to "shaonianqu",
            "少女" to "shaonvqu",
            "青年" to "qingnian",
            "少儿" to "shaoer",
            "热血" to "rexue",
            "格斗" to "gedou",
            "科幻" to "kehuan",
            "竞技" to "jingji",
            "搞笑" to "gaoxiao",
            "推理" to "tuili",
            "恐怖" to "kongbu",
            "耽美" to "danmei",
            "恋爱" to "lianai",
            "生活" to "shenghuo",
            "战争" to "zhanzheng",
            "故事" to "gushi",
            "冒险" to "maoxian",
            "魔幻" to "mohuan",
            "玄幻" to "xuanhuan",
            "校园" to "xiaoyuan",
            "悬疑" to "xuanyi",
            "萌系" to "mengxi",
            "穿越" to "chuanyue",
            "后宫" to "hougong",
            "都市" to "dushi",
            "武侠" to "wuxia",
            "历史" to "lishi",
            "同人" to "tongren",
            "励志" to "lizhi",
            "百合" to "baihe",
            "治愈" to "zhiyu",
            "机甲" to "jijia",
            "纯爱" to "chunai",
            "美食" to "meishi",
            "血腥" to "xuexing",
            "僵尸" to "jiangshi",
            "恶搞" to "egao",
            "虐心" to "nuexin",
            "动作" to "dongzuo",
            "惊险" to "jingxian",
            "唯美" to "weimei",
            "震撼" to "zhenhan",
            "复仇" to "fuchou",
            "侦探" to "zhentan",
            "脑洞" to "naodong",
            "奇幻" to "qihuan",
            "宫斗" to "gongdou",
            "爆笑" to "baoxiao",
            "运动" to "yundong",
            "青春" to "qingchun",
            "灵异" to "lingyi",
            "古风" to "gufeng",
            "权谋" to "quanmou",
            "节操" to "jiecao",
            "明星" to "mingxing",
            "暗黑" to "anhei",
            "社会" to "shehui",
            "浪漫" to "langman",
            "仙侠" to "xianxia",
            "伪娘" to "weiniang",
        )

        internal val SECTIONS: List<SiteSectionSpec> = buildList {
            add(SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST))
            add(SiteSectionSpec("popular", "人气榜", defaultSort = FeedSort.POPULAR))
            CATEGORY_PATHS.forEach { (title, slug) ->
                if (slug != null) {
                    add(SiteSectionSpec("cat_$slug", title, category = "cat_$slug"))
                }
            }
        }

        internal val SECTION_PATHS: Map<String, String> = buildMap {
            put("latest", "zaixian_recent.html")
            put("popular", "zaixian_hit.html")
            CATEGORY_PATHS.forEach { (_, slug) ->
                if (slug != null) put("cat_$slug", slug)
            }
        }

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "漫画160",
            shortName = "漫160",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "www.mh160ba.com",
            mirrorHosts = listOf("mh160ba.com", "m.mh160ba.com"),
            accessNote = "晴天 CMS 站点，图页按章节编号选择镜像主机",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = SECTIONS,
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                mediaDomains = setOf("kingwar.cn", "tgmhfc.uk", "miyeye.cn"),
            ),
        )
    }
}