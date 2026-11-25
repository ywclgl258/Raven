package com.raven.app.sites.comic

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
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HDoujin Galleries (`SiteId.HDOUJIN`) 同人志漫画插件。
 *
 * 站点为 Svelte SPA，内容全部来自 `api.hdoujin.org`：
 * - 首页聚合：`GET /books/index` → popular / doujinshi / manga / removed 四个分区
 * - 详情元数据：`GET /books/detail/<id>/<key>/`（注意尾部斜杠，缺失会返回 HTTP 400）
 * - 随机推荐：`GET /books/random`
 * - 阅读页图片：`GET /books/data/...?crt=<clearance>`，clearance 需登录后向
 *   `auth.hdoujin.org/clearance` 申请，因此未登录时阅读接口返回 [SiteException.AuthRequired]。
 */
class HdoujinSitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val index = fetchIndex()
            val hero = index[POPULAR]?.take(6).orEmpty()
            return HomeFeed(
                siteId = SiteId.HDOUJIN,
                heroItems = hero,
                sections = buildList {
                    index[POPULAR]?.let { list ->
                        add(FeedSection(id = "popular", title = "热门", items = list, seeAllRequest = popularRequest()))
                    }
                    index[DOUJINSHI]?.let { list ->
                        add(FeedSection(id = "doujinshi", title = "同人志", items = list, seeAllRequest = doujinshiRequest()))
                    }
                    index[MANGA]?.let { list ->
                        add(FeedSection(id = "manga", title = "漫画", items = list, seeAllRequest = mangaRequest()))
                    }
                },
                primaryFeedPage = FeedPage(items = index[POPULAR].orEmpty(), title = "热门"),
            )
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return searchBooks(query, request.page)
            }
            val section = request.section?.lowercase()
            val index = fetchIndex()
            val (key, title) = when (section) {
                "doujinshi" -> DOUJINSHI to "同人志"
                "manga", "latest" -> MANGA to "漫画"
                else -> POPULAR to "热门"
            }
            val items = index[key].orEmpty()
            if (items.isEmpty()) {
                throw SiteException.ParseError("HDoujin 列表结构不可识别", siteId = SiteId.HDOUJIN)
            }
            return FeedPage(items = items, hasMore = false, title = title)
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = listOf(FeedSort.NEWEST, FeedSort.POPULAR)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage {
            val query = request.query?.trim().orEmpty()
            if (query.isEmpty()) {
                throw SiteException.ParseError("HDoujin 搜索关键词不能为空", siteId = SiteId.HDOUJIN)
            }
            return searchBooks(query, request.page)
        }

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val identity = parseBookIdentity(url)
                ?: throw SiteException.ParseError("无法识别 HDoujin 地址：$url", siteId = SiteId.HDOUJIN)
            val detailJson = fetchBookDetail(identity)
            val title = detailJson.string("title").ifBlank { detailJson.string("title_short") }
                .ifBlank { "Book ${identity.id}" }
            val subtitle = detailJson.string("subtitle")
            val pages = detailJson.int("pages") ?: 0
            val cover = (detailJson["thumbnail"] as? JsonObject)?.string("path").orEmpty()
            val item = MediaItem(
                siteId = SiteId.HDOUJIN,
                id = identity.raw,
                url = identity.canonicalUrl,
                kind = MediaKind.COMIC,
                title = title,
                coverUrl = cover,
                pageCount = pages,
                extra = buildMap {
                    put("bookId", identity.id)
                    put("bookKey", identity.key)
                },
            )
            return GalleryDetail(
                item = item,
                description = subtitle,
                pageCount = pages.coerceAtLeast(1),
                // 图页地址依赖 clearance 令牌，未登录时不预置空页面，读取时统一抛 AuthRequired。
                pages = emptyList(),
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            throw readingRequiresLogin()
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            throw readingRequiresLogin()
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            return parseBookIdentity(parsed.toString())?.canonicalUrl
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val identity = parseBookIdentity(parsed.toString()) ?: return null
            return ResolvedLink.Gallery(SiteId.HDOUJIN, identity.raw, identity.canonicalUrl)
        }
    }

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    private suspend fun fetchIndex(): Map<String, List<MediaItem>> {
        val body = fetchApi("${descriptor.networkProfile.apiBase}/books/index")
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw SiteException.ParseError("HDoujin 首页数据格式异常", siteId = SiteId.HDOUJIN)
        return buildMap {
            for (key in listOf(POPULAR, DOUJINSHI, MANGA)) {
                val array = root[key] as? JsonArray ?: continue
                val items = array.mapNotNull { element ->
                    (element as? JsonObject)?.let(::parseBookSummary)
                }
                if (items.isNotEmpty()) put(key, items)
            }
        }
    }

    private fun parseBookSummary(obj: JsonObject): MediaItem? {
        val id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val key = obj["key"]?.jsonPrimitive?.content.orEmpty()
        val title = obj.string("title").ifBlank { obj.string("title_short") }
        if (title.isBlank()) return null
        val cover = (obj["thumbnail"] as? JsonObject)?.string("path").orEmpty()
        val pages = obj.int("pages")
        val identity = BookIdentity(id, key)
        return MediaItem(
            siteId = SiteId.HDOUJIN,
            id = identity.raw,
            url = identity.canonicalUrl,
            kind = MediaKind.COMIC,
            title = title,
            coverUrl = cover,
            pageCount = pages,
            extra = mapOf("bookId" to id, "bookKey" to key),
        )
    }

    private suspend fun fetchBookDetail(identity: BookIdentity): JsonObject {
        val body = fetchApi(
            "${descriptor.networkProfile.apiBase}/books/detail/${identity.id}/${identity.key}/",
        )
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw SiteException.ParseError("HDoujin 详情数据格式异常", siteId = SiteId.HDOUJIN)
    }

    private suspend fun searchBooks(query: String, page: Int): FeedPage {
        val url = ComicUrl.parse(descriptor.networkProfile.apiBase)
            ?.addPathSegment("books")
            ?.addQueryParameter("q", query)
            ?.let { builder -> if (page > 1) builder.addQueryParameter("page", page.toString()) else builder }
            ?.toString()
            ?: "${descriptor.networkProfile.apiBase}/books?q=$query"
        val body = fetchApi(url)
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val items = when (root) {
            is JsonArray -> root.mapNotNull { (it as? JsonObject)?.let(::parseBookSummary) }
            is JsonObject -> {
                val array = (root["entries"] as? JsonArray)
                    ?: (root["list"] as? JsonArray)
                    ?: (root["results"] as? JsonArray)
                array?.mapNotNull { (it as? JsonObject)?.let(::parseBookSummary) }.orEmpty()
            }
            else -> emptyList()
        }
        return FeedPage(items = items, title = query, hasMore = items.isNotEmpty())
    }

    private suspend fun fetchApi(url: String): String {
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.HDOUJIN,
                kind = SiteRequestKind.API,
                headers = mapOf(
                    "Accept" to "application/json",
                    // api.hdoujin.org 强制校验 Origin；缺失会直接返回 HTTP 400。
                    "Origin" to descriptor.baseUrl,
                    // Referer 必须停留在站点根路径，/browse 等子路径同样会触发 400。
                    "Referer" to "${descriptor.baseUrl}/",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw e
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "HDoujin 网络请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.HDOUJIN,
                cause = e,
            )
        }
        response.throwIfNotSuccessful(SiteId.HDOUJIN, descriptor.displayName)
        return response.bodyText()
    }

    private fun readingRequiresLogin(): SiteException.AuthRequired = SiteException.AuthRequired(
        message = "HDoujin 阅读页图片需要登录账号后获取 clearance 令牌（请在浏览器登录后再使用站点）",
        siteId = SiteId.HDOUJIN,
    )

    // -------------------------------------------------------------------------
    // URL / JSON helpers
    // -------------------------------------------------------------------------

    private fun parseBookIdentity(url: String): BookIdentity? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOfFirst { it == "book" || it == "books" }
        if (idx < 0 || idx + 2 > segments.lastIndex) return null
        val id = segments[idx + 1].takeIf { it.all(Char::isDigit) } ?: return null
        val key = segments[idx + 2].takeIf { it.isNotBlank() } ?: return null
        return BookIdentity(id, key)
    }

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.content?.trim().orEmpty()

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull

    private data class BookIdentity(val id: String, val key: String) {
        val raw: String get() = "$id/$key"
        val canonicalUrl: String get() = "$BASE_URL/book/$id/$key"
    }

    private fun popularRequest() = FeedRequest(siteId = SiteId.HDOUJIN, section = "popular")
    private fun doujinshiRequest() = FeedRequest(siteId = SiteId.HDOUJIN, section = "doujinshi")
    private fun mangaRequest() = FeedRequest(siteId = SiteId.HDOUJIN, section = "manga")

    companion object {
        val SITE_ID: SiteId = SiteId.HDOUJIN
        const val BASE_URL: String = "https://hdoujin.org"
        const val API_BASE: String = "https://api.hdoujin.org"

        private const val POPULAR = "popular"
        private const val DOUJINSHI = "doujinshi"
        private const val MANGA = "manga"

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "HDoujin",
            shortName = "HDoujin",
            primaryKind = MediaKind.COMIC,
            supportedKinds = setOf(MediaKind.COMIC),
            defaultHost = "hdoujin.org",
            accessNote = "阅读页图片需要登录账号",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = listOf(
                SiteSectionSpec("popular", "热门", defaultSort = FeedSort.POPULAR),
                SiteSectionSpec("doujinshi", "同人志"),
                SiteSectionSpec("manga", "漫画"),
            ),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                apiBase = API_BASE,
                apiHosts = setOf("api.hdoujin.org", "auth.hdoujin.org"),
                mediaDomains = setOf("erocdn.net"),
            ),
        )
    }
}