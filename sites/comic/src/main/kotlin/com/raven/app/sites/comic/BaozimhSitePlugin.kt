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
import com.raven.app.core.sitespi.MirrorLeaseRouter
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 包子漫畫 (`SiteId.BAOZIMH`) 连载漫画插件。
 *
 * 参考 `copymanga-copy20/baozimanhuaplus` 与 `keiyoushi/baozimanhua` 协议实现：
 * - 主流浏览走 `/api/bzmhq/amp_comic_list` JSON API（`tw.webmota.com` 默认优先，免人机挑战）
 * - 多镜像节点租约路由 (`MirrorLeaseRouter`)；遇到 `x-gatekeeper-challenge` / Cloudflare 挑战
 *   自动切换健康镜像，全部被拦截时明确抛出 [SiteException.Forbidden] (`isChallenge = true`)
 * - 详情页兼容 `#chapter-items`、`#chapters_other_list` 与 `/user/page_direct` 链接规范化
 * - 章节图页自动跟随同章多页 (`div.next_chapter a`) 并支持 `CHAPTER_FALLBACK_HOSTS` 兜底最后一话 404
 * - 图床失败支持 `reloadUrlOnError` 在 `baozimh.com` / `baozicdn.com` / `bzcdn.net` 节点间切换自愈
 */
class BaozimhSitePlugin(
    private val httpClient: HttpClientAdapter,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val mirrorRouter = MirrorLeaseRouter(
        hosts = ALL_SITE_HOSTS,
        clockMs = clockMs,
    )

    private val detailCache = LruCache<String, ParsedComicDetail>(
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

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val newest = runCatching {
                loadFeed(FeedRequest(siteId = SiteId.BAOZIMH, section = "new", page = 1))
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            val popular = loadFeed(FeedRequest(siteId = SiteId.BAOZIMH, section = "popular", page = 1))
            val sections = buildList {
                if (newest != null && newest.items.isNotEmpty()) {
                    add(
                        FeedSection(
                            id = "new",
                            title = "最新上架",
                            items = newest.items,
                            seeAllRequest = FeedRequest(siteId = SiteId.BAOZIMH, section = "new"),
                        ),
                    )
                }
            }
            return HomeFeed(
                siteId = SiteId.BAOZIMH,
                heroItems = popular.items.take(6),
                sections = sections,
                primaryFeedPage = popular,
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return searchComics(query)
            }
            val section = request.section?.trim()?.lowercase().orEmpty()
            if (section == "chapters") {
                val comicId = request.category?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw SiteException.ParseError("章节请求缺少作品编号", siteId = SiteId.BAOZIMH)
                return loadChapterFeed(comicId)
            }
            if (section == "new") {
                return loadNewReleasesFeed()
            }

            var type = "all"
            var region = "all"
            var state = "all"
            when {
                section == "ongoing" -> state = "serial"
                section == "completed" -> state = "pub"
                section.startsWith("region_") -> region = section.removePrefix("region_")
                section.startsWith("type_") -> type = section.removePrefix("type_")
            }
            return withMirror { host ->
                val url = ComicUrl.parse("https://$host/api/bzmhq/amp_comic_list")!!
                    .addQueryParameter("type", type)
                    .addQueryParameter("region", region)
                    .addQueryParameter("state", state)
                    .addQueryParameter("filter", "*")
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("limit", PAGE_SIZE.toString())
                    .addQueryParameter("language", "tw")
                    .addQueryParameter("__amp_source_origin", "https://$host")
                    .toString()
                val resp = executeTextChecked(url, host, SiteRequestKind.API)
                val root = runCatching { json.parseToJsonElement(resp).jsonObject }.getOrNull()
                    ?: throw SiteException.ParseError("包子漫畫列表 JSON 格式异常", siteId = SiteId.BAOZIMH)
                val items = root.safeArray("items").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseAmpComic) }
                if (items.isEmpty()) {
                    throw SiteException.ParseError("包子漫畫列表为空或结构变更", siteId = SiteId.BAOZIMH)
                }
                val hasNext = !root.safeString("next").isBlank() || items.size >= PAGE_SIZE
                FeedPage(
                    items = items,
                    hasMore = hasNext,
                    nextPage = if (hasNext) page + 1 else null,
                    title = sectionTitle(section),
                )
            }
        }

        /**
         * `amp_comic_list` accepts type/region/state/page only — no sort parameter exists, `/search`
         * ignores ordering, and `/list/new` is a fixed newest view. Each entry therefore advertises
         * exactly the ordering its URL implicitly produces, so the UI never offers a no-op choice.
         */
        override fun supportedSorts(request: FeedRequest): List<FeedSort> = when {
            request.section.equals("chapters", ignoreCase = true) -> emptyList()
            !request.query.isNullOrBlank() -> listOf(FeedSort.RELEVANCE)
            request.section?.trim()?.lowercase() == "new" -> listOf(FeedSort.NEWEST)
            else -> listOf(FeedSort.POPULAR)
        }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage {
            val query = request.query?.trim().orEmpty()
            if (query.isEmpty()) {
                throw SiteException.ParseError("包子漫畫搜索关键词不能为空", siteId = SiteId.BAOZIMH)
            }
            return searchComics(query)
        }

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            parseChapterIdentity(url)?.let { (comicId, slot) ->
                return loadChapterDetail(comicId, slot, url)
            }
            val comicId = extractComicId(url)
                ?: throw SiteException.ParseError("无法识别包子漫畫作品地址：$url", siteId = SiteId.BAOZIMH)
            val parsedDetail = fetchComicDetail(comicId)
            val first = parsedDetail.chapters.firstOrNull()
            val cdnIdx = parseCdnIndex(url)
            val pages = if (first != null) {
                loadChapterImages(comicId, first.slot, forceReload = false).mapIndexed { index, rawImg ->
                    GalleryPage(
                        index = index + 1,
                        pageUrl = pageAddress(comicId, first.slot, index + 1, cdnIdx),
                        imageUrl = applyImageCdn(rawImg, cdnIdx),
                    )
                }
            } else {
                emptyList()
            }
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.BAOZIMH,
                    id = comicId,
                    url = "${descriptor.baseUrl}/comic/$comicId",
                    kind = MediaKind.SERIAL_COMIC,
                    title = parsedDetail.title,
                    coverUrl = parsedDetail.coverUrl,
                    pageCount = pages.size,
                    author = parsedDetail.authors.firstOrNull(),
                    tags = parsedDetail.tags,
                    extra = buildMap {
                        put("comicId", comicId)
                        put("chapterSlot", first?.slot.orEmpty())
                        put("chapterName", first?.name.orEmpty())
                    },
                ),
                description = parsedDetail.description,
                pageCount = pages.size.coerceAtLeast(1),
                pages = pages,
                tags = parsedDetail.tags,
                authors = parsedDetail.authors,
                chaptersUrl = if (parsedDetail.chapters.size > 1) "${descriptor.baseUrl}/comic/$comicId#chapters" else null,
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.BAOZIMH)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val comicId = detail.item.extra["comicId"].orEmpty()
                .ifBlank { extractComicId(detail.item.url).orEmpty() }
                .ifBlank { detail.item.id.substringBefore(':') }
            val slot = detail.item.extra["chapterSlot"].orEmpty().ifBlank {
                detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl
                    ?.let { parseChapterIdentity(it)?.second }
                    .orEmpty()
            }
            if (comicId.isBlank() || slot.isBlank()) {
                throw SiteException.ParseError("包子漫畫章节信息缺失", siteId = SiteId.BAOZIMH)
            }
            val images = loadChapterImages(comicId, slot, forceReload = forceReload)
            val rawImg = images.getOrNull(pageIndex - 1)
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${images.size}", siteId = SiteId.BAOZIMH)
            return GalleryPage(
                index = pageIndex,
                pageUrl = pageAddress(comicId, slot, pageIndex, cdnIdx = 0),
                imageUrl = applyImageCdn(rawImg, 0),
            )
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val parsed = ComicUrl.parse(pageUrl)
                ?: throw SiteException.ParseError("无法识别包子漫畫图页地址", siteId = SiteId.BAOZIMH)
            val (comicId, slot) = parseChapterIdentity(pageUrl)
                ?: throw SiteException.ParseError("无法识别包子漫畫章节编号", siteId = SiteId.BAOZIMH)
            val pageIndex = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val cdnIdx = parseCdnIndex(pageUrl)
            val images = loadChapterImages(comicId, slot, forceReload = forceReload)
            val rawImg = images.getOrNull(pageIndex - 1)
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${images.size}", siteId = SiteId.BAOZIMH)
            return GalleryPage(
                index = pageIndex,
                pageUrl = pageAddress(comicId, slot, pageIndex, cdnIdx),
                imageUrl = applyImageCdn(rawImg, cdnIdx),
            )
        }

        override fun reloadUrlOnError(cause: SiteException, page: GalleryPage): String? {
            if (cause is SiteException.AuthRequired) return null
            val parsed = ComicUrl.parse(page.pageUrl) ?: return null
            val currentCdn = parsed.queryParameter("cdn")?.toIntOrNull() ?: 0
            val nextCdn = currentCdn + 1
            if (nextCdn >= IMAGE_CDN_MODES.size) return null
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
            parseChapterIdentity(parsed.toString())?.let { (comicId, slot) ->
                return "${descriptor.baseUrl}/comic/chapter/$comicId/$slot.html"
            }
            val comicId = extractComicId(parsed.toString()) ?: return null
            val base = "${descriptor.baseUrl}/comic/$comicId"
            return if (parsed.fragment.equals("chapters", ignoreCase = true)) "$base#chapters" else base
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            parseChapterIdentity(parsed.toString())?.let { (comicId, slot) ->
                return ResolvedLink.Gallery(
                    siteId = SiteId.BAOZIMH,
                    id = "$comicId:$slot",
                    canonicalUrl = "${descriptor.baseUrl}/comic/chapter/$comicId/$slot.html",
                )
            }
            val comicId = extractComicId(parsed.toString()) ?: return null
            if (parsed.fragment.equals("chapters", ignoreCase = true)) {
                return ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.BAOZIMH,
                        section = "chapters",
                        category = comicId,
                    ),
                )
            }
            return ResolvedLink.Gallery(
                siteId = SiteId.BAOZIMH,
                id = comicId,
                canonicalUrl = "${descriptor.baseUrl}/comic/$comicId",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Internal Scraping & Mirror Routing
    // -------------------------------------------------------------------------

    private suspend fun loadNewReleasesFeed(): FeedPage = withMirror { host ->
        val html = executeTextChecked("https://$host/list/new", host, SiteRequestKind.PAGE)
        val doc = Jsoup.parse(html, "https://$host")
        val items = parseCardElements(doc, host)
        if (items.isEmpty()) {
            throw SiteException.ParseError("包子漫畫最新上架列表为空", siteId = SiteId.BAOZIMH)
        }
        FeedPage(items = items, hasMore = false, nextPage = null, title = "最新上架")
    }

    private suspend fun searchComics(query: String): FeedPage = withMirror { host ->
        val url = ComicUrl.parse("https://$host/search")!!
            .addQueryParameter("q", query)
            .toString()
        val html = executeTextChecked(url, host, SiteRequestKind.PAGE)
        val doc = Jsoup.parse(html, "https://$host")
        val items = parseCardElements(doc, host)
        FeedPage(items = items, hasMore = false, nextPage = null, title = query)
    }

    private fun parseCardElements(doc: Document, host: String): List<MediaItem> =
        doc.select("div.comics-card").mapNotNull { card ->
            val title = card.selectFirst(".comics-card__title")?.text()
                ?.takeUnless { it.isBlank() || it.contains("{{") }
                ?.let { HtmlUrlCleaner.cleanText(it) }
                ?: return@mapNotNull null
            val poster = card.selectFirst("a.comics-card__poster, .comics-card__poster") ?: return@mapNotNull null
            val href = poster.attr("href").takeIf { it.isNotBlank() }
                ?: COMIC_ONCLICK_REGEX.find(poster.attr("onclick"))?.let { "/comic/${it.groupValues[1]}" }
                ?: return@mapNotNull null
            val comicId = extractComicId("https://$host$href") ?: return@mapNotNull null
            val rawCover = card.selectFirst("amp-img, img")?.let { img ->
                listOf("data-src", "src").firstNotNullOfOrNull { attr ->
                    img.attr(attr).takeIf { it.isNotBlank() }
                }
            }.orEmpty()
            val tags = card.select("small.tags, .tags span").mapNotNull { el ->
                val t = HtmlUrlCleaner.cleanText(el.text())
                if (t.isBlank()) null else TagRef(slug = t, label = t)
            }
            MediaItem(
                siteId = SiteId.BAOZIMH,
                id = comicId,
                url = "${descriptor.baseUrl}/comic/$comicId",
                kind = MediaKind.SERIAL_COMIC,
                title = title,
                coverUrl = normalizeCoverUrl(rawCover),
                tags = tags,
            )
        }

    private fun parseAmpComic(obj: JsonObject): MediaItem? {
        val comicId = obj.safeString("comic_id").takeIf { it.isNotBlank() } ?: return null
        val name = obj.safeString("name").takeIf { it.isNotBlank() } ?: return null
        val topicImg = obj.safeString("topic_img")
        val coverUrl = when {
            topicImg.isBlank() -> ""
            topicImg.startsWith("http", ignoreCase = true) -> normalizeCoverUrl(topicImg)
            else -> "$COVER_STATIC_BASE/${topicImg.trimStart('/')}"
        }
        val author = obj.safeString("author").takeIf { it.isNotBlank() }?.let {
            AuthorRef(id = it, name = HtmlUrlCleaner.cleanText(it))
        }
        val tags = obj.safeArray("type_names").orEmpty().mapNotNull { el ->
            val t = el.safePrimitiveContent()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TagRef(slug = t, label = HtmlUrlCleaner.cleanText(t))
        }
        return MediaItem(
            siteId = SiteId.BAOZIMH,
            id = comicId,
            url = "${descriptor.baseUrl}/comic/$comicId",
            kind = MediaKind.SERIAL_COMIC,
            title = HtmlUrlCleaner.cleanText(name),
            coverUrl = coverUrl,
            author = author,
            tags = tags,
        )
    }

    private suspend fun fetchComicDetail(comicId: String): ParsedComicDetail =
        detailCache.getOrPutSuspend(comicId) {
            withMirror { host ->
                val url = "https://$host/comic/$comicId"
                val html = executeTextChecked(url, host, SiteRequestKind.PAGE)
                val doc = Jsoup.parse(html, url)
                val title = doc.selectFirst("h1.comics-detail__title")?.text()
                    ?.let { HtmlUrlCleaner.cleanText(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                        ?.let { HtmlUrlCleaner.cleanText(it) }
                        ?.takeIf { it.isNotBlank() }
                    ?: comicId
                val rawCover = doc.selectFirst("div.pure-g div > amp-img[src], .comics-detail__info amp-img[src], .comics-detail__info img[src]")
                    ?.attr("src")
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                    .orEmpty()
                val authors = doc.select("h2.comics-detail__author").text()
                    .split(',', '、', '/')
                    .map { HtmlUrlCleaner.cleanText(it) }
                    .filter { it.isNotBlank() }
                    .map { AuthorRef(id = it, name = it) }
                val rawTags = doc.select("div.tag-list > span.tag").map { HtmlUrlCleaner.cleanText(it.text()) }
                val statusLabel = rawTags.firstOrNull {
                    it == "连载中" || it == "連載中" || it == "已完结" || it == "已完結"
                }
                val tags = rawTags.filterNot { it == statusLabel }
                    .filter { it.isNotBlank() }
                    .map { TagRef(slug = it, label = it) }
                val brief = HtmlUrlCleaner.cleanText(doc.selectFirst("p.comics-detail__desc")?.text().orEmpty())
                val description = buildString {
                    if (!statusLabel.isNullOrBlank()) append(statusLabel)
                    if (brief.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(brief)
                    }
                }
                val chapters = parseChapterList(doc, comicId, host)
                ParsedComicDetail(
                    comicId = comicId,
                    title = title,
                    coverUrl = normalizeCoverUrl(rawCover),
                    authors = authors,
                    tags = tags,
                    description = description,
                    chapters = chapters,
                )
            }
        }

    private fun parseChapterList(doc: Document, comicId: String, host: String): List<ChapterEntry> {
        val mainList = doc.select("#chapter-items .comics-chapters, #chapters_other_list .comics-chapters")
        val elements = if (mainList.isNotEmpty()) {
            mainList
        } else {
            doc.select(".comics-chapters")
        }
        val parsed = LinkedHashMap<String, ChapterEntry>()
        elements.forEach { el ->
            val anchor = el.selectFirst("a[href]")
            val href = anchor?.attr("href")?.takeIf { it.isNotBlank() }
            val slot = when {
                href != null -> {
                    val abs = ComicUrl.parse("https://$host")?.resolve(href)?.toString() ?: href
                    parseChapterIdentity(abs)?.second
                }
                else -> {
                    val onclick = el.selectFirst("[onclick]")?.attr("onclick").orEmpty()
                    CHAPTER_ONCLICK_REGEX.find(onclick)?.let { match ->
                        "${match.groupValues[2]}_${match.groupValues[3]}"
                    }
                }
            } ?: return@forEach
            val name = HtmlUrlCleaner.cleanText(anchor?.text() ?: el.text()).ifBlank { slot }
            parsed.putIfAbsent(slot, ChapterEntry(slot = slot, name = name))
        }
        // Sort ascending by (sectionSlot, chapterSlot) when slots are numeric so chapters are in reading order.
        return parsed.values.sortedWith(
            compareBy<ChapterEntry>(
                { it.slot.substringBefore('_').toIntOrNull() ?: Int.MAX_VALUE },
                { it.slot.substringAfter('_', "").toIntOrNull() ?: Int.MAX_VALUE },
            ),
        )
    }

    private suspend fun loadChapterFeed(comicId: String): FeedPage {
        val detail = fetchComicDetail(comicId)
        val items = detail.chapters.map { ch ->
            MediaItem(
                siteId = SiteId.BAOZIMH,
                id = "$comicId:${ch.slot}",
                url = "${descriptor.baseUrl}/comic/chapter/$comicId/${ch.slot}.html",
                kind = MediaKind.SERIAL_COMIC,
                title = ch.name,
                extra = mapOf("comicId" to comicId, "chapterSlot" to ch.slot),
            )
        }
        return FeedPage(items = items, title = "全部章节")
    }

    private suspend fun loadChapterDetail(comicId: String, slot: String, rawUrl: String): GalleryDetail {
        val comicMeta = runCatching { fetchComicDetail(comicId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        val ch = comicMeta?.chapters?.firstOrNull { it.slot == slot }
        val cdnIdx = parseCdnIndex(rawUrl)
        val images = loadChapterImages(comicId, slot, forceReload = false)
        val pages = images.mapIndexed { index, rawImg ->
            GalleryPage(
                index = index + 1,
                pageUrl = pageAddress(comicId, slot, index + 1, cdnIdx),
                imageUrl = applyImageCdn(rawImg, cdnIdx),
            )
        }
        val title = listOfNotNull(comicMeta?.title, ch?.name ?: slot)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return GalleryDetail(
            item = MediaItem(
                siteId = SiteId.BAOZIMH,
                id = "$comicId:$slot",
                url = "${descriptor.baseUrl}/comic/chapter/$comicId/$slot.html",
                kind = MediaKind.SERIAL_COMIC,
                title = title,
                coverUrl = comicMeta?.coverUrl.orEmpty(),
                pageCount = pages.size,
                author = comicMeta?.authors?.firstOrNull(),
                tags = comicMeta?.tags.orEmpty(),
                extra = mapOf(
                    "comicId" to comicId,
                    "chapterSlot" to slot,
                    "chapterName" to (ch?.name ?: slot),
                ),
            ),
            description = comicMeta?.description.orEmpty(),
            pageCount = pages.size.coerceAtLeast(1),
            pages = pages,
            tags = comicMeta?.tags.orEmpty(),
            authors = comicMeta?.authors.orEmpty(),
            chaptersUrl = "${descriptor.baseUrl}/comic/$comicId#chapters",
        )
    }

    private suspend fun loadChapterImages(
        comicId: String,
        slot: String,
        forceReload: Boolean,
    ): List<String> {
        if (comicId.isBlank() || slot.isBlank()) return emptyList()
        val cacheKey = "$comicId:$slot"
        if (!forceReload) {
            chapterImageCache.get(cacheKey)?.let { return it }
        }

        val activeHost = mirrorRouter.activeHost(httpClient.currentRouteKey(ALL_SITE_HOSTS.map { "https://$it" }))
        val candidateHosts = buildList {
            if (activeHost.isNotBlank()) add(activeHost)
            CHAPTER_FALLBACK_HOSTS.forEach { if (it !in this) add(it) }
            ALL_SITE_HOSTS.forEach { if (it !in this) add(it) }
        }

        var lastError: Throwable? = null
        for (host in candidateHosts) {
            val isAppMirror = host.startsWith("app")
            val prefix = if (isAppMirror) "/baozimhapp" else ""
            val firstUrl = "https://$host$prefix/comic/chapter/$comicId/$slot.html"
            try {
                val pages = fetchAllChapterSubPages(firstUrl, host, comicId, slot)
                if (pages.isNotEmpty()) {
                    chapterImageCache.put(cacheKey, pages)
                    return pages
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw (lastError as? SiteException)
            ?: SiteException.ParseError("包子漫畫章节暂无可用图片", siteId = SiteId.BAOZIMH)
    }

    private suspend fun fetchAllChapterSubPages(
        firstUrl: String,
        host: String,
        comicId: String,
        slot: String,
    ): List<String> {
        val collected = LinkedHashSet<String>()
        val visited = HashSet<String>()
        var currentUrl: String? = firstUrl
        var guard = 0
        var abortedByLoop = false
        while (currentUrl != null && guard < MAX_SUB_PAGES) {
            if (!visited.add(currentUrl)) {
                // A repeated URL means the "next page" chain cycles — protective exit, not an end.
                abortedByLoop = true
                break
            }
            guard++
            val html = executeTextChecked(currentUrl, host, SiteRequestKind.PAGE)
            val doc = Jsoup.parse(html, currentUrl)
            collected.addAll(extractImagesFromChapterDoc(doc))

            val nextHref = doc.select("div.next_chapter a[href]")
                .firstOrNull { anchor ->
                    val text = anchor.text()
                    text.contains("下一頁") || text.contains("下一页")
                }
                ?.attr("href")
                ?.takeIf { it.isNotBlank() }
            val resolvedNext = nextHref?.let { ComicUrl.parse(currentUrl)?.resolve(it)?.toString() }
            // Same-chapter sub-pages are `{slot}.html` (first page) and `{slot}_{n}.html` (follow-ups).
            // Matching only `{slot}_` dropped the first page from the chain, so a cycle back to page 1
            // looked like a normal end-of-chapter and the truncated prefix was cached.
            currentUrl = if (resolvedNext != null &&
                resolvedNext.contains("/comic/chapter/$comicId/${slot}.html") ||
                (resolvedNext != null && resolvedNext.contains("/comic/chapter/$comicId/${slot}_"))
            ) {
                resolvedNext
            } else {
                null
            }
        }
        // Reaching the traversal ceiling while still holding a next link is also protective.
        val exhaustedGuard = guard >= MAX_SUB_PAGES && currentUrl != null
        if (collected.isEmpty()) return emptyList()
        if (abortedByLoop || exhaustedGuard) {
            // Never hand a truncated prefix to the caller: it caches non-empty results and stops
            // trying other mirrors. Failing lets the next mirror attempt fetch the full set.
            throw SiteException.ParseError(
                message = "包子漫畫章节 $comicId/$slot 分页" +
                    (if (abortedByLoop) "出现循环" else "达到遍历上限 $MAX_SUB_PAGES") +
                    "，仅取得 ${collected.size} 张，拒绝作为完整章节返回",
                siteId = SiteId.BAOZIMH,
            )
        }
        return collected.toList()
    }

    private fun extractImagesFromChapterDoc(doc: Document): List<String> {
        val urls = LinkedHashSet<String>()
        doc.select("amp-state[id^=chapter][id$=Src] > script[type=application/json]").forEach { script ->
            val rawJson = script.data().trim()
            if (rawJson.isNotBlank()) {
                val obj = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
                val u = obj?.safeString("url")?.takeIf { isValidChapterImageUrl(it) }
                if (u != null) urls.add(u)
            }
        }
        doc.select("div.chapter-img img.comic-contain__item, .comic-contain img, amp-img.comic-contain__item")
            .forEach { el ->
                val raw = el.attr("data-src").ifBlank { el.attr("src") }.trim()
                if (isValidChapterImageUrl(raw)) {
                    urls.add(raw)
                }
            }
        return urls.toList()
    }

    private fun isValidChapterImageUrl(raw: String): Boolean {
        if (raw.isBlank() || !raw.startsWith("http", ignoreCase = true)) return false
        if (raw.endsWith("/cover/404.jpg", ignoreCase = true)) return false
        return true
    }

    private suspend fun <T> withMirror(block: suspend (host: String) -> T): T {
        val routeKey = httpClient.currentRouteKey(ALL_SITE_HOSTS.map { "https://$it" })
        val preferred = mirrorRouter.activeHost(routeKey)
        val ordered = buildList {
            if (preferred.isNotBlank()) add(preferred)
            ALL_SITE_HOSTS.forEach { if (it !in this) add(it) }
        }.take(MAX_MIRROR_ATTEMPTS)

        var lastError: Throwable? = null
        for (host in ordered) {
            try {
                val result = block(host)
                mirrorRouter.reportHostSuccess(host, routeKey)
                return result
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                mirrorRouter.reportHostFailure(host, routeKey, error)
                lastError = error
            }
        }
        throw lastError ?: SiteException.Network("包子漫畫所有镜像节点均不可用", siteId = SiteId.BAOZIMH)
    }

    private suspend fun executeTextChecked(
        url: String,
        host: String,
        kind: SiteRequestKind,
    ): String {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.BAOZIMH,
                kind = kind,
                headers = mapOf("Referer" to "https://$host/"),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SiteException) {
            throw se
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "包子漫畫网络请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.BAOZIMH,
                cause = e,
            )
        }
        val body = response.bodyText()
        if (response.code == 403) {
            val isGatekeeper = response.header("x-gatekeeper-challenge").equals("required", ignoreCase = true) ||
                body.contains("challenge_required", ignoreCase = true) ||
                body.contains("cf-challenge", ignoreCase = true) ||
                body.contains("Just a moment", ignoreCase = true)
            throw SiteException.Forbidden(
                isChallenge = isGatekeeper,
                message = if (isGatekeeper) "包子漫畫当前节点触发浏览器验证挑战，请切换网络或镜像" else "包子漫畫拒绝访问（HTTP 403）",
                siteId = SiteId.BAOZIMH,
            )
        }
        response.throwIfNotSuccessful(SiteId.BAOZIMH, descriptor.displayName)
        return body
    }

    private fun applyImageCdn(rawUrl: String, cdnIdx: Int): String {
        val mode = IMAGE_CDN_MODES[cdnIdx.coerceIn(0, IMAGE_CDN_MODES.lastIndex)]
        val parsed = ComicUrl.parse(rawUrl) ?: return rawUrl
        val host = parsed.host
        return when (mode) {
            "direct" -> rawUrl
            "baozicdn" -> if (host.endsWith(".baozimh.com")) {
                parsed.withHost(host.removeSuffix(".baozimh.com") + ".baozicdn.com").toString()
            } else {
                parsed.withHost("s1.baozicdn.com").toString()
            }
            "baozimh" -> if (host.endsWith(".baozicdn.com")) {
                parsed.withHost(host.removeSuffix(".baozicdn.com") + ".baozimh.com").toString()
            } else {
                parsed.withHost("s1.baozimh.com").toString()
            }
            else -> parsed.withHost(mode).toString()
        }
    }

    private fun normalizeCoverUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val nested = NESTED_COVER_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
        if (!nested.isNullOrBlank()) return nested
        return if (trimmed.startsWith("/")) "$COVER_STATIC_BASE/${trimmed.trimStart('/')}" else trimmed
    }

    private fun pageAddress(comicId: String, slot: String, pageIndex: Int, cdnIdx: Int): String {
        val base = "${descriptor.baseUrl}/comic/chapter/$comicId/$slot.html"
        return if (cdnIdx > 0) "$base?cdn=$cdnIdx#$pageIndex" else "$base#$pageIndex"
    }

    private fun parseCdnIndex(url: String): Int =
        ComicUrl.parse(url)?.queryParameter("cdn")?.toIntOrNull()?.coerceIn(0, IMAGE_CDN_MODES.lastIndex) ?: 0

    private fun extractComicId(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOf("comic")
        if (idx >= 0) {
            val candidate = segments.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }
            if (candidate != null && candidate != "chapter") return candidate
        }
        return parsed.queryParameter("comic_id")?.takeIf { it.isNotBlank() }
    }

    private fun parseChapterIdentity(url: String): Pair<String, String>? {
        val parsed = ComicUrl.parse(url) ?: return null
        val qComic = parsed.queryParameter("comic_id")?.takeIf { it.isNotBlank() }
        if (qComic != null) {
            val sec = parsed.queryParameter("section_slot")?.takeIf { it.isNotBlank() } ?: return null
            val ch = parsed.queryParameter("chapter_slot")?.takeIf { it.isNotBlank() } ?: return null
            return qComic to "${sec}_${ch}"
        }
        val segments = parsed.pathSegments
        val idx = segments.indexOf("chapter")
        if (idx >= 0 && idx + 2 < segments.size) {
            val comicId = segments[idx + 1].takeIf { it.isNotBlank() } ?: return null
            val file = segments[idx + 2].substringBefore('.').takeIf { it.isNotBlank() } ?: return null
            val parts = file.split('_')
            val slot = if (parts.size >= 2) "${parts[0]}_${parts[1]}" else file
            return comicId to slot
        }
        return null
    }

    private fun sectionTitle(section: String): String =
        SECTIONS.firstOrNull { it.id == section }?.title ?: "热门漫画"

    private data class ChapterEntry(val slot: String, val name: String)

    private data class ParsedComicDetail(
        val comicId: String,
        val title: String,
        val coverUrl: String,
        val authors: List<AuthorRef>,
        val tags: List<TagRef>,
        val description: String,
        val chapters: List<ChapterEntry>,
    )

    companion object {
        val SITE_ID: SiteId = SiteId.BAOZIMH
        const val BASE_URL: String = "https://tw.webmota.com"
        private const val COVER_STATIC_BASE: String = "https://static-tw.baozimh.com/cover"

        val ALL_SITE_HOSTS: List<String> = listOf(
            "tw.webmota.com",
            "www.twbzmg.com",
            "cn.webmota.com",
            "www.webmota.com",
            "tw.twmanga.com",
            "tw.kukuc.co",
            "tw.dinnerku.com",
            "www.baozimh.com",
            "cn.baozimh.com",
            "tw.baozimh.com",
        )

        private val CHAPTER_FALLBACK_HOSTS: List<String> = listOf(
            "www.twbzmg.com",
            "app3.baozimh.com",
            "app4.baozimh.com",
        )

        private val IMAGE_CDN_MODES: List<String> = listOf(
            "direct",
            "baozicdn",
            "baozimh",
            "s2.bzcdn.net",
            "s1.bzcdn.net",
        )

        private const val PAGE_SIZE = 36
        private const val MAX_MIRROR_ATTEMPTS = 4
        private const val MAX_SUB_PAGES = 20
        private const val DETAIL_CACHE_TTL_MS = 5 * 60 * 1_000L
        private const val CHAPTER_CACHE_TTL_MS = 30 * 60 * 1_000L

        private val COMIC_ONCLICK_REGEX = Regex("""\['comic',\s*'([^']+)'""")
        private val CHAPTER_ONCLICK_REGEX = Regex("""send_app_msg\('call_page',\s*\['chapter',\s*'([^']+)',\s*(\d+),\s*(\d+)\]\)""")
        private val NESTED_COVER_REGEX = Regex("""/cover/(https?://[^?]+)""")

        internal val SECTIONS: List<SiteSectionSpec> = listOf(
            SiteSectionSpec("popular", "热门漫画", defaultSort = FeedSort.POPULAR),
            SiteSectionSpec("new", "最新上架", defaultSort = FeedSort.NEWEST),
            SiteSectionSpec("ongoing", "连载中"),
            SiteSectionSpec("completed", "已完结"),
            SiteSectionSpec("region_cn", "国漫"),
            SiteSectionSpec("region_jp", "日漫"),
            SiteSectionSpec("region_kr", "韩漫"),
            SiteSectionSpec("region_en", "欧美"),
            SiteSectionSpec("type_lianai", "恋爱"),
            SiteSectionSpec("type_rexue", "热血"),
            SiteSectionSpec("type_qihuan", "奇幻"),
            SiteSectionSpec("type_dushi", "都市"),
            SiteSectionSpec("type_xuanhuan", "玄幻"),
            SiteSectionSpec("type_gufeng", "古风"),
            SiteSectionSpec("type_xuanyi", "悬疑"),
            SiteSectionSpec("type_gaoxiao", "搞笑"),
        )

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "包子漫畫",
            shortName = "包子",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "tw.webmota.com",
            mirrorHosts = ALL_SITE_HOSTS.drop(1) + listOf(
                "appcn.baozimh.com",
                "app3.baozimh.com",
                "app4.baozimh.com",
            ),
            accessNote = "默认使用 tw.webmota.com 免验证节点；支持多图床 CDN 故障自愈",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.MIRRORS,
            ),
            feedSorts = listOf(FeedSort.POPULAR, FeedSort.NEWEST),
            sections = SECTIONS,
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                apiBase = BASE_URL,
                apiHosts = ALL_SITE_HOSTS.toSet(),
                mediaDomains = setOf(
                    "baozimh.com",
                    "baozicdn.com",
                    "bzcdn.net",
                    "webmota.com",
                    "twbzmg.com",
                    "twmanga.com",
                    "kukuc.co",
                ),
                pageIntervalMs = 500L,
                imageIntervalMs = 120L,
            ),
        )
    }
}
