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
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.AuthState
import com.raven.app.core.sitespi.FeedSource
import com.raven.app.core.sitespi.GallerySource
import com.raven.app.core.sitespi.HostRouter
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.LinkResolver
import com.raven.app.core.sitespi.LruCache
import com.raven.app.core.sitespi.MirrorLeaseRouter
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SearchSource
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import com.raven.app.core.sitespi.SiteSessionStore
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 热辣漫画 (`SiteId.RELAMANHUA`) 连载漫画插件。
 *
 * 参考 `caolib/kira` 与 `keiyoushi/relamanhua` 协议实现：
 * - 多节点加权/故障租约路由 (`MirrorLeaseRouter` 覆盖 `mapi.hotmangas*.com` / `api.manga2026.xyz` / `mapi.fgjfghkk*.club`)
 * - 首页推荐 `/api/v3/h5/discoverIndex/freeComic`、作品列表、作者搜索、排名与全题材浏览
 * - 章节图页直出 JSON `/api/v3/comic/{path_word}/chapter/{uuid}?platform=3`
 * - 可选账号登录 (`POST /api/v3/login`)
 */
class RelamanhuaSitePlugin(
    private val httpClient: HttpClientAdapter,
    private val sessionStore: SiteSessionStore? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val apiRouter = MirrorLeaseRouter(
        hosts = CopyHotApiSupport.HOTMANGA_API_HOSTS,
        clockMs = clockMs,
    )

    private val detailCache = LruCache<String, JsonObject>(
        maxEntries = 64,
        ttlMs = DETAIL_CACHE_TTL_MS,
        clockMs = clockMs,
    )
    private val chapterListCache = LruCache<String, List<CopyHotApiSupport.ChapterEntry>>(
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

    override val hostRouter: HostRouter = apiRouter

    override val authProvider: AuthProvider = object : AuthProvider {
        override val mode: AuthMode = AuthMode.JwtBearer
        override val requiresAuthGate: Boolean = false

        override suspend fun loginWithCredentials(username: String, password: String): AuthState {
            val cleanUser = username.trim()
            if (cleanUser.isBlank() || password.isEmpty()) {
                throw SiteException.AuthRequired("请输入热辣漫画账号与密码", siteId = SiteId.RELAMANHUA)
            }
            val store = sessionStore
                ?: throw SiteException.Unsupported("当前环境不支持保存热辣漫画登录态", siteId = SiteId.RELAMANHUA)

            val salt = SecureRandom().nextInt(9000) + 1000
            val encodedPassword = CopyHotApiSupport.encodeLoginPassword(password, salt)
            val host = apiRouter.activeHost(httpClient.currentRouteKey(listOf("https://${CopyHotApiSupport.HOTMANGA_API_HOSTS.first()}/api/v3/comics")))
            val response = try {
                httpClient.postForm(
                    url = "https://$host/api/v3/login",
                    fields = mapOf(
                        "username" to cleanUser,
                        "password" to encodedPassword,
                        "salt" to salt.toString(),
                        "source" to "Official",
                        "version" to "2.2.0",
                        "platform" to "3",
                    ),
                    siteId = SiteId.RELAMANHUA,
                    headers = CopyHotApiSupport.buildHotmangaHeaders(),
                    followRedirects = false,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SiteException) {
                throw se
            } catch (e: Exception) {
                throw SiteException.Network(
                    message = "热辣漫画登录请求失败：${e.message ?: "未知错误"}",
                    siteId = SiteId.RELAMANHUA,
                    cause = e,
                )
            }
            response.throwIfNotSuccessful(SiteId.RELAMANHUA, descriptor.displayName)
            val root = runCatching { json.parseToJsonElement(response.bodyText()).jsonObject }.getOrNull()
                ?: throw SiteException.ParseError("热辣漫画登录响应格式异常", siteId = SiteId.RELAMANHUA)
            val code = root.safeInt("code") ?: 200
            CopyHotApiSupport.parseApiErrorOrNull(code, root.safeString("message"), SiteId.RELAMANHUA)?.let { throw it }

            val results = root.safeObject("results")
                ?: throw SiteException.ParseError("热辣漫画登录结果为空", siteId = SiteId.RELAMANHUA)
            val token = results.safeString("token").takeIf { it.isNotBlank() }
                ?: throw SiteException.AuthRequired("热辣漫画未返回有效登录令牌", siteId = SiteId.RELAMANHUA)
            val resolvedName = results.safeString("nickname")
                .ifBlank { results.safeString("username") }
                .ifBlank { cleanUser }
            store.saveBearerToken(SiteId.RELAMANHUA, token, resolvedName)
            return AuthState(
                isAuthenticated = true,
                username = resolvedName,
                epoch = store.authEpoch(SiteId.RELAMANHUA),
            )
        }

        override suspend fun logout() {
            val token = sessionStore?.getBearerToken(SiteId.RELAMANHUA)
            if (!token.isNullOrBlank()) {
                runCatching {
                    val host = CopyHotApiSupport.HOTMANGA_API_HOSTS.first()
                    httpClient.postForm(
                        url = "https://$host/api/v3/logout",
                        fields = emptyMap(),
                        siteId = SiteId.RELAMANHUA,
                        headers = CopyHotApiSupport.buildHotmangaHeaders(token),
                        followRedirects = false,
                    )
                }.onFailure { if (it is CancellationException) throw it }
            }
            sessionStore?.clearSite(SiteId.RELAMANHUA)
        }

        override suspend fun currentAuthState(): AuthState {
            val store = sessionStore ?: return AuthState(isAuthenticated = false)
            val token = store.getBearerToken(SiteId.RELAMANHUA)
            return AuthState(
                isAuthenticated = !token.isNullOrBlank(),
                username = store.getUsername(SiteId.RELAMANHUA),
                epoch = store.authEpoch(SiteId.RELAMANHUA),
            )
        }

        override fun decorateRequestHeaders(request: SiteHttpRequest, host: String): Map<String, String> {
            if (!descriptor.matchesRequestHost(host)) return emptyMap()
            val token = sessionStore?.getBearerToken(SiteId.RELAMANHUA)?.takeIf { it.isNotBlank() }
                ?: return emptyMap()
            return mapOf("Authorization" to "Token $token")
        }
    }

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val discover = runCatching {
                api("h5/discoverIndex/freeComic", listOf("platform" to "3", "_update" to "true"))
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()

            if (discover != null) {
                val banners = discover.safeArray("banners").orEmpty().mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val comic = obj.safeObject("comic") ?: return@mapNotNull null
                    CopyHotApiSupport.parseComicSummary(comic, SiteId.RELAMANHUA, descriptor.baseUrl, WEB_COMIC_PREFIX)
                }
                val recs = parseSummaryList(discover.safeObject("recs")?.safeArray("list"))
                val primary = loadFeed(FeedRequest(siteId = SiteId.RELAMANHUA, section = "latest", page = 1))
                val sections = buildList {
                    if (recs.isNotEmpty()) {
                        add(
                            FeedSection(
                                id = "recs",
                                title = "免费推荐",
                                items = recs,
                                seeAllRequest = FeedRequest(siteId = SiteId.RELAMANHUA, section = "recs"),
                            ),
                        )
                    }
                }
                return HomeFeed(
                    siteId = SiteId.RELAMANHUA,
                    heroItems = banners.ifEmpty { recs.take(6) },
                    sections = sections,
                    primaryFeedPage = primary,
                )
            }
            val primary = loadFeed(FeedRequest(siteId = SiteId.RELAMANHUA, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.RELAMANHUA, primaryFeedPage = primary)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            if (query.isNotEmpty()) {
                return searchComics(query, page, request.scope)
            }
            val section = request.section?.trim()?.lowercase().orEmpty()
            if (section == "chapters") {
                val pathWord = request.category?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw SiteException.ParseError("章节请求缺少作品编号", siteId = SiteId.RELAMANHUA)
                return loadChapterFeed(pathWord)
            }
            if (section == "recs") {
                val offset = (page - 1) * PAGE_SIZE
                val results = api(
                    path = "recs",
                    query = listOf(
                        "pos" to "2201202",
                        "free_type" to "1",
                        "limit" to PAGE_SIZE.toString(),
                        "offset" to offset.toString(),
                    ),
                )
                val items = parseSummaryList(results.safeArray("list"))
                val total = results.safeInt("total") ?: items.size
                return FeedPage(
                    items = items,
                    hasMore = offset + items.size < total && items.isNotEmpty(),
                    nextPage = if (offset + items.size < total && items.isNotEmpty()) page + 1 else null,
                    totalCount = total,
                    title = "推荐",
                )
            }

            val theme = section.removePrefix("theme_").takeIf { section.startsWith("theme_") }
            val ordering = if (section == "popular" || request.sort == FeedSort.POPULAR) {
                "-popular"
            } else {
                "-datetime_updated"
            }
            val offset = (page - 1) * PAGE_SIZE
            val params = buildList {
                add("free_type" to "1")
                add("ordering" to ordering)
                add("limit" to PAGE_SIZE.toString())
                add("offset" to offset.toString())
                if (theme != null) add("theme" to theme)
            }
            val results = api("comics", params)
            val items = parseSummaryList(results.safeArray("list"))
            if (items.isEmpty()) {
                throw SiteException.ParseError("热辣漫画列表结构不可识别", siteId = SiteId.RELAMANHUA)
            }
            val total = results.safeInt("total") ?: 0
            val resolvedOffset = results.safeInt("offset") ?: offset
            val title = when {
                theme != null -> CopyHotApiSupport.THEMES.firstOrNull { it.first == theme }?.second ?: theme
                ordering == "-popular" -> "人气"
                else -> "最近更新"
            }
            return FeedPage(
                items = items,
                hasMore = resolvedOffset + items.size < total,
                nextPage = if (resolvedOffset + items.size < total) page + 1 else null,
                totalCount = total,
                title = title,
            )
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
                throw SiteException.ParseError("热辣漫画搜索关键词不能为空", siteId = SiteId.RELAMANHUA)
            }
            return searchComics(query, request.page.coerceAtLeast(1), request.scope)
        }

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL, SearchScope.AUTHOR)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val pathWord = extractPathWord(url)
                ?: throw SiteException.ParseError("无法识别热辣漫画地址：$url", siteId = SiteId.RELAMANHUA)
            val results = fetchComicDetailResults(pathWord)
            val comic = results.safeObject("comic")
                ?: throw SiteException.ParseError("热辣漫画详情数据为空", siteId = SiteId.RELAMANHUA)
            val name = comic.safeString("name").ifBlank { "Comic $pathWord" }
            val cover = CopyHotApiSupport.stripCoverSize(comic.safeString("cover"))
            val metadata = CopyHotApiSupport.parseComicMetadata(comic)
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
                    ?: CopyHotApiSupport.ChapterEntry(directChapterUuid, "章节 $directChapterUuid")
            } else {
                chapters.firstOrNull()
            }
            val chapterWebUrl = target?.let { "${descriptor.baseUrl}/v2h5/comicContent/$pathWord/${it.uuid}" }
            val pages = if (target != null) {
                loadChapterImages(pathWord, target.uuid, forceReload = false).mapIndexed { index, imageUrl ->
                    GalleryPage(index = index + 1, pageUrl = "$chapterWebUrl#${index + 1}", imageUrl = imageUrl)
                }
            } else {
                emptyList()
            }
            val itemId = if (directChapterUuid != null) "$pathWord:$directChapterUuid" else pathWord
            val itemUrl = if (directChapterUuid != null) {
                chapterWebUrl ?: "${descriptor.baseUrl}$WEB_COMIC_PREFIX/$pathWord"
            } else {
                "${descriptor.baseUrl}$WEB_COMIC_PREFIX/$pathWord"
            }
            val hasMultipleChapters = chapters.size > 1 || (directChapterUuid != null && chaptersResult.isFailure)
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.RELAMANHUA,
                    id = itemId,
                    url = itemUrl,
                    kind = MediaKind.SERIAL_COMIC,
                    title = if (directChapterUuid != null) "$name · ${target?.displayTitle.orEmpty()}" else name,
                    coverUrl = cover,
                    pageCount = pages.size,
                    views = metadata.popular,
                    dateLabel = metadata.updatedAt,
                    author = metadata.authors.firstOrNull(),
                    tags = metadata.tags,
                    extra = buildMap {
                        put("comicId", pathWord)
                        put("chapterUuid", target?.uuid.orEmpty())
                        put("chapterName", target?.displayTitle.orEmpty())
                    },
                ),
                description = metadata.description,
                pageCount = pages.size.coerceAtLeast(1),
                pages = pages,
                tags = metadata.tags,
                authors = metadata.authors,
                chaptersUrl = if (hasMultipleChapters) "${descriptor.baseUrl}$WEB_COMIC_PREFIX/$pathWord#chapters" else null,
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            if (pageIndex !in 1..detail.pageCount) {
                throw SiteException.NotFound("页码超出范围：$pageIndex / ${detail.pageCount}", siteId = SiteId.RELAMANHUA)
            }
            if (!forceReload) {
                detail.pages.firstOrNull { it.index == pageIndex && it.imageUrl.isNotBlank() }?.let { return it }
            }
            val uuid = detail.item.extra["chapterUuid"].orEmpty().ifBlank {
                detail.pages.firstOrNull { it.index == pageIndex }?.pageUrl
                    ?.let { ComicUrl.parse(it)?.pathSegments?.lastOrNull() }
                    .orEmpty()
            }
            if (uuid.isBlank()) {
                throw SiteException.ParseError("热辣漫画章节信息缺失", siteId = SiteId.RELAMANHUA)
            }
            val pathWord = detail.item.extra["comicId"].orEmpty()
                .ifBlank { extractPathWord(detail.item.url).orEmpty() }
                .ifBlank { detail.item.id.substringBefore(':') }
            val images = loadChapterImages(pathWord, uuid, forceReload = forceReload)
            val canonicalChapterUrl = "${descriptor.baseUrl}/v2h5/comicContent/$pathWord/$uuid"
            return images.getOrNull(pageIndex - 1)?.let { imageUrl ->
                GalleryPage(index = pageIndex, pageUrl = "$canonicalChapterUrl#$pageIndex", imageUrl = imageUrl)
            } ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${images.size}", siteId = SiteId.RELAMANHUA)
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            val parsed = ComicUrl.parse(pageUrl)
                ?: throw SiteException.ParseError("无法识别热辣漫画图页地址", siteId = SiteId.RELAMANHUA)
            val pathWord = extractPathWord(pageUrl)
                ?: throw SiteException.ParseError("无法识别热辣漫画作品编号", siteId = SiteId.RELAMANHUA)
            val uuid = parseChapterUuid(pageUrl)
                ?: throw SiteException.ParseError("无法识别热辣漫画章节编号", siteId = SiteId.RELAMANHUA)
            val pageIndex = parsed.fragment?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val images = loadChapterImages(pathWord, uuid, forceReload = forceReload)
            val canonicalChapterUrl = "${descriptor.baseUrl}/v2h5/comicContent/$pathWord/$uuid"
            return images.getOrNull(pageIndex - 1)?.let { imageUrl ->
                GalleryPage(index = pageIndex, pageUrl = "$canonicalChapterUrl#$pageIndex", imageUrl = imageUrl)
            } ?: throw SiteException.NotFound("页码超出范围：$pageIndex / ${images.size}", siteId = SiteId.RELAMANHUA)
        }
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val pathWord = extractPathWord(parsed.toString()) ?: return null
            val uuid = parseChapterUuid(parsed.toString())
            return when {
                parsed.fragment.equals("chapters", ignoreCase = true) ->
                    "${descriptor.baseUrl}$WEB_COMIC_PREFIX/$pathWord#chapters"
                uuid != null ->
                    "${descriptor.baseUrl}/v2h5/comicContent/$pathWord/$uuid"
                else ->
                    "${descriptor.baseUrl}$WEB_COMIC_PREFIX/$pathWord"
            }
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val pathWord = extractPathWord(parsed.toString()) ?: return null
            if (parsed.fragment.equals("chapters", ignoreCase = true)) {
                return ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.RELAMANHUA,
                        section = "chapters",
                        category = pathWord,
                    ),
                )
            }
            val uuid = parseChapterUuid(parsed.toString())
            val canonical = if (uuid != null) {
                "${descriptor.baseUrl}/v2h5/comicContent/$pathWord/$uuid"
            } else {
                "${descriptor.baseUrl}$WEB_COMIC_PREFIX/$pathWord"
            }
            val itemId = if (uuid != null) "$pathWord:$uuid" else pathWord
            return ResolvedLink.Gallery(SiteId.RELAMANHUA, itemId, canonical)
        }
    }

    // -------------------------------------------------------------------------
    // Internal API & Parsing
    // -------------------------------------------------------------------------

    private suspend fun searchComics(query: String, page: Int, scope: SearchScope): FeedPage {
        val offset = (page - 1) * PAGE_SIZE
        val results = if (scope == SearchScope.AUTHOR) {
            api(
                path = "comics",
                query = listOf(
                    "free_type" to "1",
                    "ordering" to "-popular",
                    "author" to query,
                    "limit" to PAGE_SIZE.toString(),
                    "offset" to offset.toString(),
                ),
            )
        } else {
            api(
                path = "search/comic",
                query = listOf(
                    "platform" to "3",
                    "q" to query,
                    "q_type" to "",
                    "free_type" to "1",
                    "limit" to PAGE_SIZE.toString(),
                    "offset" to offset.toString(),
                ),
            )
        }
        val items = parseSummaryList(results.safeArray("list"))
        val total = results.safeInt("total") ?: 0
        val resolvedOffset = results.safeInt("offset") ?: offset
        return FeedPage(
            items = items,
            hasMore = resolvedOffset + items.size < total,
            nextPage = if (resolvedOffset + items.size < total) page + 1 else null,
            totalCount = total,
            title = query,
        )
    }

    private fun parseSummaryList(array: JsonArray?): List<MediaItem> =
        array.orEmpty().mapNotNull { el ->
            (el as? JsonObject)?.let {
                CopyHotApiSupport.parseComicSummary(it, SiteId.RELAMANHUA, descriptor.baseUrl, WEB_COMIC_PREFIX)
            }
        }

    private suspend fun fetchComicDetailResults(pathWord: String): JsonObject =
        detailCache.getOrPutSuspend(pathWord) {
            api("comic2/$pathWord", listOf("platform" to "3"))
        }

    private suspend fun loadChapters(
        pathWord: String,
        groups: JsonObject?,
        throwIfAllFailed: Boolean = true,
    ): List<CopyHotApiSupport.ChapterEntry> {
        chapterListCache.get(pathWord)?.let { return it }
        val groupPairs = groups?.values?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val gPath = obj.safeString("path_word").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            gPath to obj.safeString("name")
        }.orEmpty().ifEmpty { listOf("default" to "") }

        val collected = LinkedHashMap<String, CopyHotApiSupport.ChapterEntry>()
        var succeededGroups = 0
        var firstError: Throwable? = null

        for ((groupPath, groupName) in groupPairs) {
            var offset = 0
            var groupSucceeded = false
            var pageCount = 0
            while (pageCount < MAX_CHAPTER_PAGES) {
                pageCount++
                val results = try {
                    api(
                        path = "comic/$pathWord/group/$groupPath/chapters",
                        query = listOf(
                            "limit" to CHAPTER_PAGE_SIZE.toString(),
                            "offset" to offset.toString(),
                        ),
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (error: Throwable) {
                    if (firstError == null) firstError = error
                    break
                }
                groupSucceeded = true
                val list = results.safeArray("list").orEmpty()
                if (list.isEmpty()) break
                list.forEach { element ->
                    val chapter = element as? JsonObject ?: return@forEach
                    val uuid = chapter.safeString("uuid").takeIf { it.isNotBlank() } ?: return@forEach
                    val name = chapter.safeString("name").ifBlank { uuid }
                    collected.putIfAbsent(
                        uuid,
                        CopyHotApiSupport.ChapterEntry(
                            uuid = uuid,
                            name = name,
                            groupPathWord = groupPath,
                            groupName = groupName,
                        ),
                    )
                }
                val total = results.safeInt("total") ?: list.size
                offset += list.size
                if (offset >= total || list.size < CHAPTER_PAGE_SIZE) break
            }
            if (groupSucceeded) succeededGroups += 1
        }
        if (throwIfAllFailed && succeededGroups == 0 && firstError != null) {
            throw firstError
        }
        val chapters = collected.values.toList()
        if (chapters.isNotEmpty()) {
            chapterListCache.put(pathWord, chapters)
        }
        return chapters
    }

    private suspend fun loadChapterFeed(pathWord: String): FeedPage {
        val results = fetchComicDetailResults(pathWord)
        val chapters = loadChapters(pathWord, results.safeObject("groups"), throwIfAllFailed = true)
        val items = chapters.map { chapter ->
            MediaItem(
                siteId = SiteId.RELAMANHUA,
                id = "$pathWord:${chapter.uuid}",
                url = "${descriptor.baseUrl}/v2h5/comicContent/$pathWord/${chapter.uuid}",
                kind = MediaKind.SERIAL_COMIC,
                title = chapter.displayTitle,
                extra = mapOf("comicId" to pathWord, "chapterUuid" to chapter.uuid),
            )
        }
        return FeedPage(items = items, title = "全部章节")
    }

    private suspend fun loadChapterImages(
        pathWord: String,
        uuid: String,
        forceReload: Boolean,
    ): List<String> {
        if (uuid.isBlank()) return emptyList()
        val cacheKey = "$pathWord:$uuid"
        if (!forceReload) {
            chapterImageCache.get(cacheKey)?.let { return it }
        }
        val results = api("comic/$pathWord/chapter/$uuid", listOf("platform" to "3"))
        val isLock = results.safePrimitiveBoolean("is_lock") == true
        val chapterObj = results.safeObject("chapter") ?: results
        val images = CopyHotApiSupport.orderChapterImages(chapterObj)
        if (isLock && images.isEmpty()) {
            throw SiteException.AuthRequired("该章节需要登录热辣漫画账号后才能完整阅读", siteId = SiteId.RELAMANHUA)
        }
        if (images.isNotEmpty()) {
            chapterImageCache.put(cacheKey, images)
        }
        return images
    }

    private suspend fun api(path: String, query: List<Pair<String, String>> = emptyList()): JsonObject {
        val routeKey = httpClient.currentRouteKey(
            CopyHotApiSupport.HOTMANGA_API_HOSTS.map { "https://$it/api/v3/comics" },
        )
        val preferred = apiRouter.activeHost(routeKey)
        val ordered = buildList {
            if (preferred.isNotBlank()) add(preferred)
            CopyHotApiSupport.HOTMANGA_API_HOSTS.forEach { if (it !in this) add(it) }
        }.take(MAX_HOST_ATTEMPTS)

        val token = sessionStore?.getBearerToken(SiteId.RELAMANHUA)
        val headers = CopyHotApiSupport.buildHotmangaHeaders(token)
        var lastError: Throwable? = null

        for (host in ordered) {
            val builder = ComicUrl.parse("https://$host/api/v3")?.addPathSegments(path)
                ?: throw SiteException.ParseError("热辣漫画接口地址无效", siteId = SiteId.RELAMANHUA)
            val url = query.fold(builder) { acc, (k, v) -> acc.addQueryParameter(k, v) }.toString()
            try {
                val response = httpClient.getText(
                    url = url,
                    siteId = SiteId.RELAMANHUA,
                    kind = SiteRequestKind.API,
                    headers = headers,
                )
                response.throwIfNotSuccessful(SiteId.RELAMANHUA, descriptor.displayName)
                val root = runCatching { json.parseToJsonElement(response.bodyText()).jsonObject }.getOrNull()
                    ?: throw SiteException.ParseError("热辣漫画接口数据格式异常", siteId = SiteId.RELAMANHUA)
                val code = root.safeInt("code") ?: 200
                if (code != 200) {
                    CopyHotApiSupport.parseApiErrorOrNull(code, root.safeString("message"), SiteId.RELAMANHUA)
                        ?.let { throw it }
                }
                apiRouter.reportHostSuccess(host, routeKey)
                return root.safeObject("results") ?: JsonObject(emptyMap())
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                apiRouter.reportHostFailure(host, routeKey, error)
                lastError = error
            }
        }
        throw lastError ?: SiteException.Network("热辣漫画接口节点均不可用", siteId = SiteId.RELAMANHUA)
    }

    private fun extractPathWord(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        return when {
            segments.firstOrNull() == "comic" -> segments.getOrNull(1)?.takeIf { it.isNotBlank() }
            segments.firstOrNull() == "v2h5" && segments.getOrNull(1) == "details" ->
                segments.getOrNull(3)?.takeIf { it.isNotBlank() }
            segments.firstOrNull() == "v2h5" && segments.getOrNull(1) == "comicContent" ->
                segments.getOrNull(2)?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun parseChapterUuid(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val chIdx = segments.indexOf("chapter")
        if (chIdx >= 0) {
            return segments.getOrNull(chIdx + 1)?.takeIf { it.isNotBlank() }
        }
        if (segments.firstOrNull() == "v2h5" && segments.getOrNull(1) == "comicContent") {
            return segments.getOrNull(3)?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun JsonObject.safePrimitiveBoolean(name: String): Boolean? =
        this[name].safePrimitiveContent()?.toBooleanStrictOrNull()

    companion object {
        val SITE_ID: SiteId = SiteId.RELAMANHUA
        const val BASE_URL: String = "https://m.relamanhua.org"
        const val API_BASE: String = "https://mapi.hotmangasg.com/api/v3"
        private const val WEB_COMIC_PREFIX = "/v2h5/details/comic"

        private const val PAGE_SIZE = 30
        private const val CHAPTER_PAGE_SIZE = 100
        private const val MAX_CHAPTER_PAGES = 50
        private const val MAX_HOST_ATTEMPTS = 4
        private const val DETAIL_CACHE_TTL_MS = 5 * 60 * 1_000L
        private const val CHAPTER_CACHE_TTL_MS = 30 * 60 * 1_000L

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "热辣漫画",
            shortName = "热辣",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "m.relamanhua.org",
            mirrorHosts = listOf(
                "relamanhua.org",
                "www.relamanhua.org",
                "www.relamanhua.com",
                "relamanhua.com",
                "www.hotmangasd.com",
                "www.hotmangasg.com",
                "2024manga.com",
                "m.2024manga.com",
                "manga2026.xyz",
                "m.manga2026.xyz",
            ),
            accessNote = "多节点热辣 API 直出图页 JSON；支持可选账号登录",
            capabilities = setOf(
                SiteCapability.FEED,
                SiteCapability.EXPLORE,
                SiteCapability.SEARCH,
                SiteCapability.TAGS,
                SiteCapability.AUTHORS,
                SiteCapability.AUTH,
                SiteCapability.MIRRORS,
            ),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = buildList {
                add(SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST))
                add(SiteSectionSpec("popular", "人气", defaultSort = FeedSort.POPULAR))
                add(SiteSectionSpec("recs", "推荐", defaultSort = FeedSort.POPULAR))
                CopyHotApiSupport.THEMES.forEach { (slug, title) ->
                    add(SiteSectionSpec("theme_$slug", title, category = "theme_$slug"))
                }
            },
            searchScopes = listOf(SearchScope.ALL, SearchScope.AUTHOR),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                apiBase = API_BASE,
                apiHosts = CopyHotApiSupport.HOTMANGA_API_HOSTS.toSet(),
                mediaDomains = setOf(
                    "mangafunb.fun",
                    "mangafuna.xyz",
                    "mangafunc.fun",
                    "hotmangasg.com",
                    "hotmangasd.com",
                    "hotmangasf.com",
                    "manga2026.xyz",
                ),
                pageIntervalMs = 500L,
                imageIntervalMs = 150L,
            ),
        )
    }
}
