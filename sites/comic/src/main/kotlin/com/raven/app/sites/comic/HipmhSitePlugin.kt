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
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import java.math.BigInteger
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/**
 * 嬉皮漫畫 (`SiteId.HIPMH`) 连载漫画插件。
 *
 * 移动端为 Astro 站点，列表由公开 JSON API 提供：
 * - 列表/搜索：`GET https://hipapi1.s3file.top/v1/mangas`
 *   （`sort=updated|popular|latest`、`genre`、`category`、`status`、`keyword`、`page`、`per_page`）
 * - 章节列表：`GET /v1/manga/chapters?mid=<mid 前缀>`
 * - 作品页：`https://m.hipmh.com/works/<mid>`（标题/封面服务端渲染）
 *
 * 阅读器的图页由 `reader.hipmh.top` 的混淆脚本解码，服务端不直接暴露图片地址，
 * 因此章节图页接口返回 [SiteException.Unsupported]。
 */
class HipmhSitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.HIPMH, primaryFeedPage = page)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            val url = buildListUrl(request.section, page, query)
            val body = fetchApi(url)
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw SiteException.ParseError("嬉皮漫畫列表数据格式异常", siteId = SiteId.HIPMH)
            val data = root["data"]?.jsonObject
                ?: throw SiteException.ParseError("嬉皮漫畫列表数据为空", siteId = SiteId.HIPMH)
            val items = (data["items"] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.let(::parseMangaSummary)
            }
            if (items.isEmpty()) {
                throw SiteException.ParseError("嬉皮漫畫列表结构不可识别", siteId = SiteId.HIPMH)
            }
            val totalPages = data.int("total_pages") ?: page
            return FeedPage(
                items = items,
                nextPage = if (page < totalPages) page + 1 else null,
                hasMore = page < totalPages,
                totalCount = data.int("total"),
                totalPages = totalPages,
                title = query.ifEmpty { sectionTitle(request.section) },
            )
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> =
            listOf(FeedSort.NEWEST, FeedSort.POPULAR, FeedSort.TRENDING)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val mid = extractWorkSlug(url)
                ?: throw SiteException.ParseError("无法识别嬉皮漫畫地址：$url", siteId = SiteId.HIPMH)
            val canonical = "${descriptor.baseUrl}/works/$mid"
            val html = fetchHtml(canonical)
            val doc = Jsoup.parse(html, canonical)
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.title().substringBefore(" - ").ifBlank { "作品 $mid" }
            val rawCover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            val cover = if (rawCover.startsWith("http")) rawCover else "$COVER_BASE$rawCover"
            val chapters = loadChapters(mid)
            val firstHid = chapters.firstOrNull()
            val pages = if (firstHid != null) loadChapterPages(firstHid) else emptyList()
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.HIPMH,
                    id = mid,
                    url = canonical,
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = if (cover.startsWith("/")) "${descriptor.baseUrl}$cover" else cover,
                    pageCount = pages.size,
                    extra = buildMap {
                        put("chapterHid", firstHid.orEmpty())
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
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.HIPMH)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val hid = detail.item.extra["chapterHid"].orEmpty()
            if (hid.isBlank()) {
                throw SiteException.ParseError("嬉皮漫畫章节信息缺失", siteId = SiteId.HIPMH)
            }
            val pages = loadChapterPages(hid)
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
            val hid = parsed.pathSegments.lastOrNull().orEmpty()
            val pageIndex = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val pages = loadChapterPages(hid)
            return pages.firstOrNull { it.index == pageIndex }
                ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${pages.size}", siteId = SiteId.HIPMH)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val slug = extractWorkSlug(parsed.toString()) ?: return null
            return "${descriptor.baseUrl}/works/$slug"
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val slug = extractWorkSlug(parsed.toString()) ?: return null
            return ResolvedLink.Gallery(SiteId.HIPMH, slug, "${descriptor.baseUrl}/works/$slug")
        }
    }

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    private fun buildListUrl(section: String?, page: Int, query: String): String {
        val params = mutableListOf<String>()
        if (query.isNotEmpty()) {
            params += "keyword=${encode(query)}"
        } else {
            when (val id = section?.trim()?.lowercase().orEmpty()) {
                "popular" -> params += "sort=popular"
                "weekly" -> params += "sort=popular"
                "new" -> params += "sort=latest"
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
        val mid = obj.string("mid").takeIf { it.isNotBlank() } ?: return null
        val title = obj.string("title").takeIf { it.isNotBlank() } ?: return null
        val coverPath = obj.string("vertical_image_url").ifBlank { obj.string("cover_image_url") }
        val cover = when {
            coverPath.isBlank() -> ""
            coverPath.startsWith("http") -> coverPath
            else -> "$COVER_BASE$coverPath"
        }
        return MediaItem(
            siteId = SiteId.HIPMH,
            id = mid,
            url = "${descriptor.baseUrl}/works/$mid",
            kind = MediaKind.SERIAL_COMIC,
            title = title,
            coverUrl = cover,
        )
    }

    private suspend fun loadChapters(mid: String): List<String> {
        // 章节 API 只接受 mid 的短前缀（`bTo3MjQ0-...` → `bTo3MjQ0`）。
        val shortMid = mid.substringBefore('-')
        val body = runCatching { fetchApi("$API_BASE/v1/manga/chapters?mid=${encode(shortMid)}&per_page=200") }
            .getOrNull()
            .orEmpty()
        if (body.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] as? JsonObject ?: return emptyList()
        return (data["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.string("hid")?.takeIf { it.isNotBlank() }
        }
    }

    // -------------------------------------------------------------------------
    // Reader decoding (mirrors /assets/runtime/chapter-decoder.js on reader.hipmh.top)
    // -------------------------------------------------------------------------

    private suspend fun loadChapterPages(frontendHid: String): List<GalleryPage> {
        if (frontendHid.isBlank()) return emptyList()
        val apiHid = buildApiHid(frontendHid)
            ?: throw SiteException.ParseError("嬉皮漫畫章节编号无法解析", siteId = SiteId.HIPMH)
        val body = fetchApi("$API_BASE/v2/chapter?hid=${encode(apiHid)}")
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw SiteException.ParseError("嬉皮漫畫章节数据格式异常", siteId = SiteId.HIPMH)
        val data = root["data"] as? JsonObject
            ?: throw SiteException.ParseError("嬉皮漫畫章节数据为空", siteId = SiteId.HIPMH)
        val images = when (val raw = data["images"]) {
            is JsonArray -> raw.mapNotNull { it.jsonPrimitive.content }.toMutableList()
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (raw.content.isBlank()) return emptyList()
                decodeImages(raw.content).toMutableList()
            }
            else -> return emptyList()
        }
        val orderId = data["order_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L
        val sid = data["sid"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L
        if (images.isNotEmpty() && orderId >= 0 && sid >= 0) {
            decoyIndex(images.size, orderId, sid)?.let { idx -> if (idx in images.indices) images.removeAt(idx) }
        }
        val line = data["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val base = when (line) {
            2 -> "https://hip-cf-1.s3imgs.top"
            9 -> "https://hip-tx-s1.s3imgs.top"
            else -> "https://hip-tx-1.s3imgs.top"
        }
        return images.mapIndexed { index, path ->
            val imageUrl = when {
                path.startsWith("http") -> path
                path.startsWith("/") -> base + path
                else -> "$base/$path"
            }
            GalleryPage(
                index = index + 1,
                pageUrl = "https://reader.hipmh.top/chapter/$frontendHid#${index + 1}",
                imageUrl = imageUrl,
            )
        }
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
        val body = encoded.removePrefix("qM9").removeSuffix("Z7")
        if (body.length < 6) return emptyList()
        val tailLength = (body.length - 5) / 3
        val headLength = (body.length - 5 - tailLength) / 2
        if (tailLength <= 0 || headLength <= 0) return emptyList()
        val head = body.take(headLength)
        val mid = body.substring(headLength + 2, body.length - 3 - tailLength)
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
        return runCatching {
            json.parseToJsonElement(decoded).jsonArray.mapNotNull { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
    }

    private fun decoyIndex(size: Int, orderId: Long, sid: Long): Int? {
        if (size <= 1) return null
        val key = (sid.toBigInteger() * DECOY_SID_FACTOR)
            .xor(size.toBigInteger() * DECOY_SIZE_FACTOR)
            .mod(size.toBigInteger())
        return orderId.toBigInteger().xor(key).takeIf { it in BigInteger.ZERO..<size.toBigInteger() }?.toInt()
    }

    private fun decodeBase64Url(raw: String): String {
        val padded = raw.replace('-', '+').replace('_', '/')
        val bytes = Base64.getDecoder().decode(padded)
        return String(bytes, Charsets.UTF_8)
    }

    private fun encodeBase64Url(raw: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))

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

    private fun sectionTitle(section: String?): String =
        SECTIONS.firstOrNull { it.id.equals(section, ignoreCase = true) }?.title ?: "最近更新"

    private fun encode(raw: String): String = com.raven.app.core.sitespi.HtmlUrlCleaner.encodeQueryParam(raw)

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.content?.trim().orEmpty()

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull

    companion object {
        val SITE_ID: SiteId = SiteId.HIPMH
        const val BASE_URL: String = "https://m.hipmh.com"
        const val API_BASE: String = "https://hipapi1.s3file.top"
        const val COVER_BASE: String = "https://cover.s3imgs.top"
        private const val PAGE_SIZE = 18

        internal val SECTIONS: List<SiteSectionSpec> = listOf(
            SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST),
            SiteSectionSpec("popular", "人氣", defaultSort = FeedSort.POPULAR),
            SiteSectionSpec("weekly", "本週熱門", defaultSort = FeedSort.TRENDING),
            SiteSectionSpec("new", "最新上架", defaultSort = FeedSort.NEWEST),
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
            mirrorHosts = listOf("hipmh.com", "www.hipmh.com"),
            accessNote = "阅读页由独立阅读器动态解码",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR, FeedSort.TRENDING),
            sections = SECTIONS,
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                apiBase = API_BASE,
                apiHosts = setOf("hipapi1.s3file.top"),
                mediaDomains = setOf("hipmh.com", "hipmh.top", "s3imgs.top", "s3file.top"),
            ),
        )
    }
}