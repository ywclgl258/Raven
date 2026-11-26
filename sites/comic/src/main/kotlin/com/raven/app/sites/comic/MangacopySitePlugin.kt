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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 拷贝漫画 (`SiteId.MANGACOPY`) 连载漫画插件。
 *
 * 官方 App 接口（必须携带 `platform` / `version` 头，否则返回空列表）：
 * - 列表：`GET https://api.copy4000.com/api/v3/comics?ordering=-datetime_updated&limit=30&offset=`
 * - 搜索：`GET /api/v3/search/comic?platform=1&q=&q_type=`
 * - 详情：`GET /api/v3/comic2/<path_word>?platform=1`（含分组 `groups`）
 * - 章节：`GET /api/v3/comic/<path_word>/group/<group>/chapters?limit=500&offset=`
 *
 * 阅读：桌面版章节页内嵌 `var cct`（AES 密钥）与 `var contentKey`（前 16 字符为 IV，
 * 其余为 hex 密文），AES-CBC 解密后得到图页 JSON 数组。
 */
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
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return searchComics(query, page)
            }
            val section = request.section?.trim()?.lowercase().orEmpty()
            if (section == "chapters") {
                val pathWord = request.category?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw SiteException.ParseError("章节请求缺少作品编号", siteId = SiteId.MANGACOPY)
                return loadChapterFeed(pathWord)
            }
            val ordering = if (section == "popular" || request.sort == FeedSort.POPULAR) {
                "-popular"
            } else {
                "-datetime_updated"
            }
            val theme = section.removePrefix("theme_").takeIf { section.startsWith("theme_") }
            val params = mutableListOf(
                "ordering" to ordering,
                "limit" to PAGE_SIZE.toString(),
                "offset" to ((page - 1) * PAGE_SIZE).toString(),
            )
            theme?.let { params += "theme" to it }
            val results = api(path = "comics", query = params)
            val list = (results["list"] as? JsonArray).orEmpty()
            val items = list.mapNotNull { (it as? JsonObject)?.let(::parseComicSummary) }
            if (items.isEmpty()) {
                throw SiteException.ParseError("拷贝漫画列表结构不可识别", siteId = SiteId.MANGACOPY)
            }
            val total = results.safeInt("total") ?: 0
            val offset = results.safeInt("offset") ?: ((page - 1) * PAGE_SIZE)
            val title = when {
                theme != null -> THEMES.firstOrNull { it.first == theme }?.second ?: theme
                ordering == "-popular" -> "人气"
                else -> "最近更新"
            }
            return FeedPage(
                items = items,
                hasMore = offset + items.size < total,
                nextPage = if (offset + items.size < total) page + 1 else null,
                totalCount = total,
                title = title,
            )
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> =
            listOf(FeedSort.NEWEST, FeedSort.POPULAR)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage {
            val query = request.query?.trim().orEmpty()
            if (query.isEmpty()) {
                throw SiteException.ParseError("拷贝漫画搜索关键词不能为空", siteId = SiteId.MANGACOPY)
            }
            return searchComics(query, request.page.coerceAtLeast(1))
        }

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val pathWord = extractPathWord(url)
                ?: throw SiteException.ParseError("无法识别拷贝漫画地址：$url", siteId = SiteId.MANGACOPY)
            val results = api("comic2/$pathWord", listOf("platform" to "1"))
            val comic = results.safeObject("comic")
                ?: throw SiteException.ParseError("拷贝漫画详情数据为空", siteId = SiteId.MANGACOPY)
            val name = comic.safeString("name").ifBlank { "Comic $pathWord" }
            val cover = stripCoverSize(comic.safeString("cover"))
            val groupsObj = results.safeObject("groups")
            val directChapterUuid = parseChapterUuid(url)
            val chaptersResult = try {
                Result.success(loadChapters(pathWord, groupsObj, throwIfAllFailed = true))
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                if (directChapterUuid == null) throw error
                Result.failure(error)
            }
            val chapters = chaptersResult.getOrDefault(emptyList())
            val target = if (directChapterUuid != null) {
                chapters.firstOrNull { it.uuid == directChapterUuid }
                    ?: ChapterEntry(directChapterUuid, "章节 $directChapterUuid")
            } else {
                chapters.firstOrNull()
            }
            val chapterWebUrl = target?.let { "${descriptor.baseUrl}/comic/$pathWord/chapter/${it.uuid}" }
            val pages = if (target != null) {
                loadChapterImages(pathWord, target.uuid).mapIndexed { index, imageUrl ->
                    GalleryPage(index = index + 1, pageUrl = "$chapterWebUrl#${index + 1}", imageUrl = imageUrl)
                }
            } else {
                emptyList()
            }
            val itemId = if (directChapterUuid != null) "$pathWord:$directChapterUuid" else pathWord
            val itemUrl = if (directChapterUuid != null) {
                chapterWebUrl ?: "${descriptor.baseUrl}/comic/$pathWord"
            } else {
                "${descriptor.baseUrl}/comic/$pathWord"
            }
            val hasMultipleChapters = chapters.size > 1 || (directChapterUuid != null && chaptersResult.isFailure)
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.MANGACOPY,
                    id = itemId,
                    url = itemUrl,
                    kind = MediaKind.SERIAL_COMIC,
                    title = if (directChapterUuid != null) "$name · ${target?.name.orEmpty()}" else name,
                    coverUrl = cover,
                    pageCount = pages.size,
                    extra = buildMap {
                        put("comicId", pathWord)
                        put("chapterUuid", target?.uuid.orEmpty())
                        put("chapterName", target?.name.orEmpty())
                    },
                ),
                description = comic.safeString("brief"),
                pageCount = pages.size.coerceAtLeast(1),
                pages = pages,
                chaptersUrl = if (hasMultipleChapters) "${descriptor.baseUrl}/comic/$pathWord#chapters" else null,
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.MANGACOPY)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val uuid = detail.item.extra["chapterUuid"].orEmpty().ifBlank {
                // 阅读器可能从数据库/路由重建详情而丢失 extra，此时从 pageUrl 反推章节 uuid。
                detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl
                    ?.let { ComicUrl.parse(it)?.pathSegments?.lastOrNull() }
                    .orEmpty()
            }
            if (uuid.isBlank()) {
                throw SiteException.ParseError("拷贝漫画章节信息缺失", siteId = SiteId.MANGACOPY)
            }
            val pathWord = detail.item.extra["comicId"].orEmpty()
                .ifBlank { extractPathWord(detail.item.url).orEmpty() }
                .ifBlank { detail.item.id.substringBefore(':') }
            val images = loadChapterImages(pathWord, uuid)
            val canonicalChapterUrl = "${descriptor.baseUrl}/comic/$pathWord/chapter/$uuid"
            return images.getOrNull(pageIndex - 1)?.let { imageUrl ->
                GalleryPage(index = pageIndex, pageUrl = "$canonicalChapterUrl#$pageIndex", imageUrl = imageUrl)
            } ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${images.size}", siteId = SiteId.MANGACOPY)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val parsed = ComicUrl.parse(pageUrl)
                ?: throw SiteException.ParseError("无法识别拷贝漫画图页地址", siteId = SiteId.MANGACOPY)
            val pathWord = extractPathWord(pageUrl)
                ?: throw SiteException.ParseError("无法识别拷贝漫画作品编号", siteId = SiteId.MANGACOPY)
            val uuid = parseChapterUuid(pageUrl)
                ?: throw SiteException.ParseError("无法识别拷贝漫画章节编号", siteId = SiteId.MANGACOPY)
            val pageIndex = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val images = loadChapterImages(pathWord, uuid)
            val canonicalChapterUrl = "${descriptor.baseUrl}/comic/$pathWord/chapter/$uuid"
            return images.getOrNull(pageIndex - 1)?.let { imageUrl ->
                GalleryPage(index = pageIndex, pageUrl = "$canonicalChapterUrl#$pageIndex", imageUrl = imageUrl)
            } ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${images.size}", siteId = SiteId.MANGACOPY)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val id = extractPathWord(parsed.toString()) ?: return null
            return "${descriptor.baseUrl}/comic/$id"
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val id = extractPathWord(parsed.toString()) ?: return null
            return ResolvedLink.Gallery(SiteId.MANGACOPY, id, "${descriptor.baseUrl}/comic/$id")
        }
    }

    // -------------------------------------------------------------------------
    // API & Parsing
    // -------------------------------------------------------------------------

    private suspend fun searchComics(query: String, page: Int): FeedPage {
        val results = api(
            path = "search/comic",
            query = listOf(
                "platform" to "1",
                "q" to query,
                "q_type" to "",
                "limit" to PAGE_SIZE.toString(),
                "offset" to ((page - 1) * PAGE_SIZE).toString(),
            ),
        )
        val items = results.safeArray("list").orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::parseComicSummary) }
        val total = results.safeInt("total") ?: 0
        val offset = results.safeInt("offset") ?: ((page - 1) * PAGE_SIZE)
        return FeedPage(
            items = items,
            hasMore = offset + items.size < total,
            nextPage = if (offset + items.size < total) page + 1 else null,
            totalCount = total,
            title = query,
        )
    }

    private fun parseComicSummary(obj: JsonObject): MediaItem? {
        val name = obj.safeString("name").takeIf { it.isNotBlank() } ?: return null
        val pathWord = obj.safeString("path_word").takeIf { it.isNotBlank() } ?: return null
        return MediaItem(
            siteId = SiteId.MANGACOPY,
            id = pathWord,
            url = "${descriptor.baseUrl}/comic/$pathWord",
            kind = MediaKind.SERIAL_COMIC,
            title = name,
            coverUrl = stripCoverSize(obj.safeString("cover")),
        )
    }

    private suspend fun loadChapters(
        pathWord: String,
        groups: JsonObject?,
        throwIfAllFailed: Boolean = true,
    ): List<ChapterEntry> {
        val groupWords = groups?.values?.mapNotNull { element ->
            (element as? JsonObject)?.safeString("path_word")?.takeIf { it.isNotBlank() }
        }.orEmpty().ifEmpty { listOf("default") }
        val collected = LinkedHashMap<String, ChapterEntry>()
        var succeededGroups = 0
        var firstError: Throwable? = null
        for (group in groupWords) {
            val results = try {
                api(
                    path = "comic/$pathWord/group/$group/chapters",
                    query = listOf("limit" to "500", "offset" to "0"),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                if (firstError == null) firstError = error
                continue
            }
            succeededGroups += 1
            results.safeArray("list").orEmpty()
                .forEach { element ->
                    val chapter = element as? JsonObject ?: return@forEach
                    val uuid = chapter.safeString("uuid").takeIf { it.isNotBlank() } ?: return@forEach
                    val name = chapter.safeString("name").ifBlank { uuid }
                    collected.putIfAbsent(uuid, ChapterEntry(uuid, name))
                }
        }
        if (throwIfAllFailed && succeededGroups == 0 && firstError != null) {
            throw firstError
        }
        return collected.values.toList()
    }

    private suspend fun loadChapterFeed(pathWord: String): FeedPage {
        val results = api("comic2/$pathWord", listOf("platform" to "1"))
        val chapters = loadChapters(pathWord, results.safeObject("groups"), throwIfAllFailed = true)
        val items = chapters.map { chapter ->
            MediaItem(
                siteId = SiteId.MANGACOPY,
                id = "$pathWord:${chapter.uuid}",
                url = "${descriptor.baseUrl}/comic/$pathWord/chapter/${chapter.uuid}",
                kind = MediaKind.SERIAL_COMIC,
                title = chapter.name,
                extra = mapOf("comicId" to pathWord, "chapterUuid" to chapter.uuid),
            )
        }
        return FeedPage(items = items, title = "全部章节")
    }

    private suspend fun loadChapterImages(pathWord: String, uuid: String): List<String> {
        if (uuid.isBlank()) return emptyList()
        val html = fetchDesktopHtml("/comic/$pathWord/chapter/$uuid")
        val key = KEY_REGEX.find(html)?.groupValues?.get(1)
            ?: throw SiteException.ParseError("拷贝漫画章节密钥缺失", siteId = SiteId.MANGACOPY)
        val contentKey = CONTENT_KEY_REGEX.find(html)?.groupValues?.get(1)
            ?: throw SiteException.ParseError("拷贝漫画章节内容缺失", siteId = SiteId.MANGACOPY)
        if (contentKey.length <= 16) return emptyList()
        val plain = runCatching {
            val cipherText = hexToBytes(contentKey.drop(16))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(contentKey.take(16).toByteArray(Charsets.UTF_8)),
            )
            cipher.doFinal(cipherText)
        }.getOrNull() ?: throw SiteException.ParseError("拷贝漫画章节解密失败", siteId = SiteId.MANGACOPY)
        val array = runCatching {
            json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonArray
        }.getOrNull() ?: throw SiteException.ParseError("拷贝漫画图页数据格式异常", siteId = SiteId.MANGACOPY)
        return array.mapNotNull { element ->
            (element as? JsonObject)?.safeString("url")?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun api(path: String, query: List<Pair<String, String>> = emptyList()): JsonObject {
        val builder = ComicUrl.parse(API_BASE)?.addPathSegments(path) ?: throw SiteException.ParseError(
            "拷贝漫画接口地址无效",
            siteId = SiteId.MANGACOPY,
        )
        val url = query.fold(builder) { acc, (k, v) -> acc.addQueryParameter(k, v) }.toString()
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.MANGACOPY,
                kind = SiteRequestKind.API,
                headers = MOBILE_HEADERS + mapOf("Accept" to "application/json"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            throw e
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "拷贝漫画接口请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.MANGACOPY,
                cause = e,
            )
        }
        response.throwIfNotSuccessful(SiteId.MANGACOPY, descriptor.displayName)
        val root = runCatching { json.parseToJsonElement(response.bodyText()).jsonObject }.getOrNull()
            ?: throw SiteException.ParseError("拷贝漫画接口数据格式异常", siteId = SiteId.MANGACOPY)
        val code = root.safeInt("code") ?: 200
        if (code != 200) {
            val apiMsg = root.safeString("message")
            if (code == 210) {
                throw SiteException.Forbidden(
                    isChallenge = true,
                    message = "拷贝漫画上游访问受限 (210)：${apiMsg.ifBlank { "第三方客户端暂受限制，请稍后重试" }}",
                    siteId = SiteId.MANGACOPY,
                )
            }
            throw SiteException.ParseError(
                "拷贝漫画接口返回 $code：$apiMsg",
                siteId = SiteId.MANGACOPY,
            )
        }
        return root.safeObject("results") ?: JsonObject(emptyMap())
    }

    /**
     * 桌面章节页在部分网络下会被主域重置（Connection reset），按镜像顺序逐个尝试。
     */
    private suspend fun fetchDesktopHtml(path: String): String {
        var lastError: SiteException? = null
        for (host in WEB_HOSTS) {
            val url = "https://$host$path"
            try {
                val response = httpClient.getText(
                    url = url,
                    siteId = SiteId.MANGACOPY,
                    kind = SiteRequestKind.PAGE,
                    headers = mapOf(
                        "User-Agent" to SiteNetworkProfile.DESKTOP_WINDOWS_UA,
                        "Referer" to "https://$host/",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    ),
                )
                if (response.isSuccessful) return response.bodyText()
                lastError = SiteException.Network(
                    message = "拷贝漫画章节页请求失败：HTTP ${response.code}",
                    siteId = SiteId.MANGACOPY,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteException) {
                lastError = e
            } catch (e: Exception) {
                lastError = SiteException.Network(
                    message = "拷贝漫画网络请求失败：${e.message ?: "未知错误"}",
                    siteId = SiteId.MANGACOPY,
                    cause = e,
                )
            }
        }
        throw lastError ?: SiteException.Network("拷贝漫画章节页不可用", siteId = SiteId.MANGACOPY)
    }

    private fun stripCoverSize(cover: String): String =
        cover.replace(COVER_SIZE_REGEX, "")

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Invalid hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun extractPathWord(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val comicIdx = segments.indexOf("comic")
        if (comicIdx >= 0 && comicIdx + 1 < segments.size) {
            return segments[comicIdx + 1].takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun parseChapterUuid(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOf("chapter")
        return segments.getOrNull(idx + 1)?.takeIf { idx >= 0 && it.isNotBlank() }
    }

    private data class ChapterEntry(val uuid: String, val name: String)

    companion object {
        val SITE_ID: SiteId = SiteId.MANGACOPY
        const val BASE_URL: String = "https://www.mangacopy.com"
        const val API_BASE: String = "https://api.copy4000.com/api/v3"
        private const val WEB_VERSION = "2026.10.16"
        private val WEB_HOSTS = listOf(
            "www.mangacopy.com",
            "www.copy4000.com",
            "www.copy5000.com",
            "www.copy20.com",
            "copymanga.fun",
        )
        private const val PAGE_SIZE = 30

        private val COVER_SIZE_REGEX = Regex("""\.\d+x\d+\.\w+$""")
        private val KEY_REGEX = Regex("""var cct = '([^']*)'""")
        private val CONTENT_KEY_REGEX = Regex("""var contentKey = '([^']*)'""")

        private val THEMES: List<Pair<String, String>> = listOf(
            "aiqing" to "爱情",
            "huanlexiang" to "欢乐向",
            "maoxian" to "冒险",
            "qihuan" to "奇幻",
            "baihe" to "百合",
            "xiaoyuan" to "校园",
            "kehuan" to "科幻",
            "dongfang" to "东方",
            "danmei" to "耽美",
            "gedou" to "格斗",
            "teenslove" to "TL",
            "shenghuo" to "生活",
            "qingxiaoshuo" to "轻小说",
            "qita" to "其他",
            "xuanyi" to "悬疑",
            "mengxi" to "萌系",
            "shengui" to "神鬼",
            "zhichang" to "职场",
            "zhiyu" to "治愈",
            "jiecao" to "节操",
        )

        private val MOBILE_HEADERS = mapOf(
            "platform" to "1",
            "version" to WEB_VERSION,
            "Referer" to "$BASE_URL/",
        )

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "拷贝漫画",
            shortName = "拷贝",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "www.mangacopy.com",
            mirrorHosts = listOf(
                "copymanga.com", "www.copymanga.com", "www.copy4000.com",
                "copy4000.com", "copy5000.com", "copy20.com", "copymanga.fun",
            ),
            accessNote = "官方 API 需要 platform/version 头；章节图页为 AES-CBC 加密",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.SEARCH,
                SiteCapability.MIRRORS,
            ),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = buildList {
                add(SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST))
                add(SiteSectionSpec("popular", "人气", defaultSort = FeedSort.POPULAR))
                THEMES.forEach { (slug, title) ->
                    add(SiteSectionSpec("theme_$slug", title, category = "theme_$slug"))
                }
            },
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                apiBase = API_BASE,
                apiHosts = setOf("api.copy4000.com"),
                mediaDomains = setOf("mangafunb.fun", "copy4000.com", "copy5000.com"),
            ),
        )
    }
}