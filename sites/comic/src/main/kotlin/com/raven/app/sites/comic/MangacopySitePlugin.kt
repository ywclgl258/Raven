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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

class MangacopySitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(FeedRequest(siteId = SiteId.MANGACOPY, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.MANGACOPY, primaryFeedPage = page)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val url = "${descriptor.networkProfile.apiBase}/api/v3/update/newest?limit=21&offset=${(request.page - 1) * 21}&platform=3"
            val body = fetchText(url)
            val root = json.parseToJsonElement(body).jsonObject
            val results = root["results"]?.jsonObject
            val list = results?.get("list")?.jsonArray ?: emptyList()
            val items = mutableListOf<MediaItem>()
            for (entry in list) {
                val obj = entry.jsonObject
                val comic = obj["comic"]?.jsonObject ?: continue
                val name = comic["name"]?.jsonPrimitive?.content.orEmpty()
                val pathWord = comic["path_word"]?.jsonPrimitive?.content.orEmpty()
                if (name.isBlank() || pathWord.isBlank()) continue
                items.add(
                    MediaItem(
                        siteId = SiteId.MANGACOPY,
                        id = pathWord,
                        url = "${descriptor.baseUrl}/comic/$pathWord",
                        kind = MediaKind.SERIAL_COMIC,
                        title = name,
                        coverUrl = comic["cover"]?.jsonPrimitive?.content.orEmpty(),
                    ),
                )
            }
            if (items.isEmpty()) {
                throw SiteException.ParseError("拷贝漫画列表结构不可识别", siteId = SiteId.MANGACOPY)
            }
            return FeedPage(items = items.distinctBy { it.id }, hasMore = list.size >= 21)
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = listOf(FeedSort.NEWEST)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage {
            val q = HtmlUrlCleaner.encodeQueryParam(request.query?.trim().orEmpty())
            val url = "${descriptor.networkProfile.apiBase}/api/v3/search/comic?limit=21&offset=${(request.page - 1) * 21}&platform=3&q=$q"
            val body = fetchText(url)
            val root = json.parseToJsonElement(body).jsonObject
            val results = root["results"]?.jsonObject
            val list = results?.get("list")?.jsonArray ?: emptyList()
            val items = mutableListOf<MediaItem>()
            for (entry in list) {
                val obj = entry.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                val pathWord = obj["path_word"]?.jsonPrimitive?.content.orEmpty()
                if (name.isBlank() || pathWord.isBlank()) continue
                items.add(
                    MediaItem(
                        siteId = SiteId.MANGACOPY,
                        id = pathWord,
                        url = "${descriptor.baseUrl}/comic/$pathWord",
                        kind = MediaKind.SERIAL_COMIC,
                        title = name,
                        coverUrl = obj["cover"]?.jsonPrimitive?.content.orEmpty(),
                    ),
                )
            }
            return FeedPage(items = items.distinctBy { it.id }, hasMore = list.size >= 21)
        }

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val id = extractId(url) ?: throw SiteException.ParseError("无法识别拷贝漫画地址：$url", siteId = SiteId.MANGACOPY)
            val canonical = "${descriptor.baseUrl}/comic/$id"
            // 详情优先走官方 API；该接口对第三方客户端可能返回 210 反盗版限制，此时回退 HTML 标题。
            val apiDetail = runCatching { loadComicDetailApi(id) }.getOrNull()
            val title = apiDetail?.first.orEmpty().ifBlank {
                val html = runCatching { fetchHtml(canonical) }.getOrNull().orEmpty()
                if (html.isBlank()) {
                    "Comic $id"
                } else {
                    HtmlUrlCleaner.cleanText(Jsoup.parse(html, canonical).selectFirst("h1, .comic-title")?.text().orEmpty())
                        .ifBlank { "Comic $id" }
                }
            }
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.MANGACOPY,
                    id = id,
                    url = canonical,
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = apiDetail?.second.orEmpty(),
                ),
                pages = emptyList(),
                pageCount = 1,
            )
        }

        override suspend fun loadGalleryPage(detail: GalleryDetail, pageIndex: Int, forceReload: Boolean): GalleryPage {
            throw SiteException.Unsupported(
                message = "拷贝漫画章节图片接口对第三方客户端做了服务端限制，暂不可读",
                siteId = SiteId.MANGACOPY,
            )
        }

        override suspend fun resolvePageImage(pageUrl: String, preferOriginal: Boolean, forceReload: Boolean): GalleryPage {
            throw SiteException.Unsupported(
                message = "拷贝漫画章节图片接口对第三方客户端做了服务端限制，暂不可读",
                siteId = SiteId.MANGACOPY,
            )
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val id = extractId(parsed.toString()) ?: return null
            return "${descriptor.baseUrl}/comic/$id"
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val id = extractId(parsed.toString()) ?: return null
            return ResolvedLink.Gallery(SiteId.MANGACOPY, id, "${descriptor.baseUrl}/comic/$id")
        }
    }

    /**
     * 官方详情 API：`/api/v3/comic/<path_word>?platform=3`。
     * 部分网络/客户端会被站点返回 210 反盗版限制，此时抛出异常由调用方回退 HTML。
     */
    private suspend fun loadComicDetailApi(pathWord: String): Pair<String, String> {
        val url = "${descriptor.networkProfile.apiBase}/api/v3/comic/$pathWord?platform=3"
        val body = fetchText(url)
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonObject
            ?: throw SiteException.ParseError("拷贝漫画详情数据为空", siteId = SiteId.MANGACOPY)
        val comic = results["comic"]?.jsonObject ?: results
        val name = comic["name"]?.jsonPrimitive?.content.orEmpty()
        val cover = comic["cover"]?.jsonPrimitive?.content.orEmpty()
        if (name.isBlank()) {
            throw SiteException.ParseError("拷贝漫画详情缺少标题", siteId = SiteId.MANGACOPY)
        }
        return name to cover
    }

    private suspend fun fetchText(url: String): String {
        val response = httpClient.getText(url = url, siteId = SiteId.MANGACOPY, kind = SiteRequestKind.API)
        response.throwIfNotSuccessful(SiteId.MANGACOPY, descriptor.displayName)
        return response.bodyText()
    }

    private suspend fun fetchHtml(url: String): String {
        val response = httpClient.getText(url = url, siteId = SiteId.MANGACOPY, kind = SiteRequestKind.PAGE)
        response.throwIfNotSuccessful(SiteId.MANGACOPY, descriptor.displayName)
        return response.bodyText()
    }

    private fun extractId(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val comicIdx = segments.indexOf("comic")
        if (comicIdx >= 0 && comicIdx + 1 < segments.size) {
            return segments[comicIdx + 1].takeIf { it.isNotBlank() }
        }
        return segments.lastOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        val SITE_ID: SiteId = SiteId.MANGACOPY
        const val BASE_URL: String = "https://www.mangacopy.com"

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "拷贝漫画",
            shortName = "拷贝",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "www.mangacopy.com",
            mirrorHosts = listOf("copymanga.com", "www.copymanga.com", "www.copy4000.com", "copy4000.com"),
            accessNote = "章节图片接口有服务端限制，列表与搜索可正常使用",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH, SiteCapability.MIRRORS),
            feedSorts = listOf(FeedSort.NEWEST),
            sections = listOf(SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST)),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                apiBase = "https://api.mangacopy.com",
                apiHosts = setOf("api.mangacopy.com"),
                mediaDomains = setOf("mangafunb.fun"),
            ),
        )
    }
}