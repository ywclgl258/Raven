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
import com.raven.app.core.sitespi.SeriesSource
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRequestKind
import com.raven.app.core.sitespi.SiteSessionStore
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 拷贝漫画 (`SiteId.MANGACOPY`) 连载漫画插件。
 *
 * 深度参考官方 Android 3.1.1 客户端、caolib/kira 与 copymanga-copy20 抗限流架构：
 * 1. 官方 App 签名通道：携带 `COPY/<version>`、`versioncode`、`platform=3`、`device`、`pseudoid`、
 *    `umstring`、`dt` 与 `X-Auth-Timestamp` / `X-Auth-Signature`（HMAC-SHA256），
 *    并通过 `/api/v3/system/network2` 与 `/api/v3/system/appVersion/last` 懒刷新动态线路与版本；
 * 2. 多节点健康路由：`MirrorLeaseRouter` 管理拷贝 API 节点与热辣兜底节点；
 * 3. 三级防风控兜底链（对抗第三方 210 限制与单点限流）：
 *    - 详情/章节：拷贝签名 API（多节点重试） -> 热辣同源 API 集群；
 *    - 章节图页：拷贝 `/chapter2/`（按 `words` 重排）-> 拷贝 `/chapter/` -> 热辣 `/chapter/` -> 桌面 HTML AES-CBC；
 * 4. 内存 LRU+TTL 缓存详情、章节目录与图页列表，减少重复请求触发风控。
 */
class MangacopySitePlugin(
    private val httpClient: HttpClientAdapter,
    private val sessionStore: SiteSessionStore? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SitePlugin {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val desktopFallback = MangacopyDesktopFallback(httpClient, json)
    private val configMutex = Mutex()

    @Volatile
    private var dynamicCopyHosts: List<String> = emptyList()

    @Volatile
    private var appVersion: String = CopyHotApiSupport.DEFAULT_COPY_APP_VERSION

    @Volatile
    private var lastConfigRefreshAtMs: Long = 0L

    private val copyRouter = MirrorLeaseRouter(
        hosts = CopyHotApiSupport.COPY_API_HOSTS,
        clockMs = clockMs,
    )
    private val hotFallbackRouter = MirrorLeaseRouter(
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

    override val hostRouter: HostRouter = copyRouter

    override val authProvider: AuthProvider = object : AuthProvider {
        override val mode: AuthMode = AuthMode.JwtBearer
        override val requiresAuthGate: Boolean = false

        override suspend fun loginWithCredentials(username: String, password: String): AuthState {
            val cleanUser = username.trim()
            if (cleanUser.isBlank() || password.isEmpty()) {
                throw SiteException.AuthRequired("请输入拷贝漫画账号与密码", siteId = SiteId.MANGACOPY)
            }
            val store = sessionStore
                ?: throw SiteException.Unsupported("当前环境不支持保存拷贝漫画登录态", siteId = SiteId.MANGACOPY)

            val salt = SecureRandom().nextInt(9999)
            val encodedPassword = CopyHotApiSupport.encodeLoginPassword(password, salt)
            val (device, pseudo) = CopyHotApiSupport.stableDeviceIdentity(sessionStore, SiteId.MANGACOPY)
            // Credentials MUST only ever leave the device towards a pinned CopyManga API host.
            // Hosts discovered at runtime via `/system/network2` are transport-only: they are never
            // trusted with the account password or the issued token.
            val headers = CopyHotApiSupport.buildCopyAppHeaders(
                version = appVersion,
                device = device,
                pseudoId = pseudo,
                epochMillis = clockMs(),
            )
            val credentialHosts = mangacopyCredentialHosts()
            var response: com.raven.app.core.sitespi.SiteHttpResponse? = null
            var lastFailure: SiteException? = null
            for (host in credentialHosts) {
                val attempt = try {
                    httpClient.postForm(
                        url = "https://$host/api/v3/login",
                        fields = mapOf(
                            "username" to cleanUser,
                            "password" to encodedPassword,
                            "salt" to salt.toString(),
                        ),
                        siteId = SiteId.MANGACOPY,
                        headers = headers,
                        followRedirects = false,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (se: SiteException) {
                    lastFailure = se
                    if (se is SiteException.Network || se is SiteException.TlsError || se is SiteException.RateLimited) {
                        continue
                    }
                    throw se
                } catch (e: Exception) {
                    lastFailure = SiteException.Network(
                        message = "拷贝漫画登录请求失败：${e.message ?: "未知错误"}",
                        siteId = SiteId.MANGACOPY,
                        cause = e,
                    )
                    continue
                }
                response = attempt
                break
            }
            val finalResponse = response ?: throw (lastFailure ?: SiteException.Network("拷贝漫画登录请求失败", siteId = SiteId.MANGACOPY))
            finalResponse.throwIfNotSuccessful(SiteId.MANGACOPY, descriptor.displayName)
            val root = runCatching { json.parseToJsonElement(finalResponse.bodyText()).jsonObject }.getOrNull()
                ?: throw SiteException.ParseError("拷贝漫画登录响应格式异常", siteId = SiteId.MANGACOPY)
            val code = root.safeInt("code") ?: 200
            val msg = root.safeString("message")
            CopyHotApiSupport.parseApiErrorOrNull(code, msg, SiteId.MANGACOPY)?.let { throw it }

            val results = root.safeObject("results")
                ?: throw SiteException.ParseError("拷贝漫画登录结果为空", siteId = SiteId.MANGACOPY)
            val token = results.safeString("token").takeIf { it.isNotBlank() }
                ?: throw SiteException.AuthRequired("拷贝漫画未返回有效登录令牌", siteId = SiteId.MANGACOPY)
            val resolvedName = results.safeString("nickname")
                .ifBlank { results.safeString("username") }
                .ifBlank { cleanUser }
            store.saveBearerToken(SiteId.MANGACOPY, token, resolvedName)
            return AuthState(
                isAuthenticated = true,
                username = resolvedName,
                epoch = store.authEpoch(SiteId.MANGACOPY),
            )
        }

        override suspend fun logout() {
            val token = sessionStore?.getBearerToken(SiteId.MANGACOPY)
            if (!token.isNullOrBlank()) {
                for (host in mangacopyCredentialHosts()) {
                    val sent = runCatching {
                        httpClient.postForm(
                            url = "https://$host/api/v3/logout",
                            fields = emptyMap(),
                            siteId = SiteId.MANGACOPY,
                            headers = buildCopyHeaders(token),
                            followRedirects = false,
                        )
                    }.onFailure { if (it is CancellationException) throw it }.isSuccess
                    if (sent) break
                }
            }
            sessionStore?.clearSite(SiteId.MANGACOPY)
        }

        override suspend fun currentAuthState(): AuthState {
            val store = sessionStore ?: return AuthState(isAuthenticated = false)
            val token = store.getBearerToken(SiteId.MANGACOPY)
            return AuthState(
                isAuthenticated = !token.isNullOrBlank(),
                username = store.getUsername(SiteId.MANGACOPY),
                epoch = store.authEpoch(SiteId.MANGACOPY),
            )
        }

        override fun decorateRequestHeaders(request: SiteHttpRequest, host: String): Map<String, String> {
            val expectedEpoch = request.authEpoch
            if (expectedEpoch != null && sessionStore != null && expectedEpoch != sessionStore.authEpoch(SiteId.MANGACOPY)) {
                throw SiteException.Cancelled("拷贝漫画会话已切换", siteId = SiteId.MANGACOPY)
            }
            // Token confinement: only the pinned, statically known CopyManga API hosts may ever see
            // the bearer token. Runtime-discovered hosts (network2), HotManga fallback hosts, image
            // CDNs and the web mirrors all stay credential-free.
            if (!mangacopyIsCredentialHost(host)) return emptyMap()
            val token = sessionStore?.getBearerToken(SiteId.MANGACOPY)?.takeIf { it.isNotBlank() }
                ?: return emptyMap()
            return mapOf("Authorization" to "Token $token")
        }
    }

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val homeData = runCatching {
                apiCopyOrHot("h5/homeIndex2", listOf("platform" to "3"))
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()

            if (homeData != null) {
                val banners = homeData.safeArray("banners").orEmpty().mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val comic = obj.safeObject("comic") ?: return@mapNotNull null
                    CopyHotApiSupport.parseComicSummary(comic, SiteId.MANGACOPY, descriptor.baseUrl)
                }
                val recs = mangacopyParseSummaryList(homeData.safeObject("recComics")?.safeArray("list"), descriptor)
                val rankDay = mangacopyParseSummaryList(homeData.safeObject("rankDayComics")?.safeArray("list"), descriptor)
                val newest = mangacopyParseSummaryList(homeData.safeObject("newComics")?.safeArray("list"), descriptor)
                val finished = mangacopyParseSummaryList(homeData.safeObject("finishComics")?.safeArray("list"), descriptor)

                val sections = buildList {
                    if (recs.isNotEmpty()) {
                        add(
                            FeedSection(
                                id = "recs",
                                title = "推荐漫画",
                                items = recs,
                                seeAllRequest = FeedRequest(siteId = SiteId.MANGACOPY, section = "recs"),
                            ),
                        )
                    }
                    if (rankDay.isNotEmpty()) {
                        add(
                            FeedSection(
                                id = "rank_day",
                                title = "日榜热门",
                                items = rankDay,
                                seeAllRequest = FeedRequest(siteId = SiteId.MANGACOPY, section = "rank_day"),
                                isRanked = true,
                            ),
                        )
                    }
                    if (newest.isNotEmpty()) {
                        add(
                            FeedSection(
                                id = "latest",
                                title = "最近更新",
                                items = newest,
                                seeAllRequest = FeedRequest(siteId = SiteId.MANGACOPY, section = "latest"),
                            ),
                        )
                    }
                    if (finished.isNotEmpty()) {
                        add(
                            FeedSection(
                                id = "finish",
                                title = "已完结",
                                items = finished,
                                seeAllRequest = FeedRequest(siteId = SiteId.MANGACOPY, section = "finish"),
                            ),
                        )
                    }
                }
                val primary = loadFeed(FeedRequest(siteId = SiteId.MANGACOPY, section = "latest", page = 1))
                return HomeFeed(
                    siteId = SiteId.MANGACOPY,
                    heroItems = banners.ifEmpty { recs.take(6) },
                    sections = sections,
                    primaryFeedPage = primary,
                )
            }

            val page = loadFeed(FeedRequest(siteId = SiteId.MANGACOPY, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.MANGACOPY, primaryFeedPage = page)
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
                    ?: throw SiteException.ParseError("章节请求缺少作品编号", siteId = SiteId.MANGACOPY)
                return loadChapterFeed(pathWord)
            }

            return when (section) {
                "recs" -> loadRecsFeed(page)
                "rank_day", "rank_week", "rank_month", "rank_total" -> loadRankFeed(section, page)
                "finish" -> loadComicsFeed(
                    page = page,
                    ordering = if (request.sort == FeedSort.POPULAR) "-popular" else "-datetime_updated",
                    theme = null,
                    top = "finish",
                    title = "已完结",
                )
                else -> {
                    val theme = section.removePrefix("theme_").takeIf { section.startsWith("theme_") }
                    val ordering = if (section == "popular" || request.sort == FeedSort.POPULAR) {
                        "-popular"
                    } else {
                        "-datetime_updated"
                    }
                    val title = when {
                        theme != null -> CopyHotApiSupport.THEMES.firstOrNull { it.first == theme }?.second ?: theme
                        ordering == "-popular" -> "人气"
                        else -> "最近更新"
                    }
                    loadComicsFeed(
                        page = page,
                        ordering = ordering,
                        theme = theme,
                        top = null,
                        title = title,
                    )
                }
            }
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> =
            if (request.section.equals("chapters", ignoreCase = true)) {
                emptyList()
            } else {
                listOf(FeedSort.NEWEST, FeedSort.POPULAR)
            }
    }

    override val seriesSource: SeriesSource = object : SeriesSource {
        override suspend fun loadChapters(seriesId: String): List<MediaItem> {
            return loadChapterFeed(seriesId.trim(), forceRefresh = true).items
        }
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage {
            val query = request.query?.trim().orEmpty()
            if (query.isEmpty()) {
                throw SiteException.ParseError("拷贝漫画搜索关键词不能为空", siteId = SiteId.MANGACOPY)
            }
            return searchComics(query, request.page.coerceAtLeast(1), request.scope)
        }

        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL, SearchScope.AUTHOR)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val pathWord = extractPathWord(url)
                ?: throw SiteException.ParseError("无法识别拷贝漫画地址：$url", siteId = SiteId.MANGACOPY)
            val results = fetchComicDetailResults(pathWord)
            val comic = results.safeObject("comic")
                ?: throw SiteException.ParseError("拷贝漫画详情数据为空", siteId = SiteId.MANGACOPY)
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
            val chapterWebUrl = target?.let { "${descriptor.baseUrl}/comic/$pathWord/chapter/${it.uuid}" }
            val pages = if (target != null) {
                loadChapterImages(pathWord, target.uuid, forceReload = false).mapIndexed { index, imageUrl ->
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
            val images = loadChapterImages(pathWord, uuid, forceReload = forceReload)
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
            val images = loadChapterImages(pathWord, uuid, forceReload = forceReload)
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
            val uuid = parseChapterUuid(parsed.toString())
            val base = "${descriptor.baseUrl}/comic/$id"
            return when {
                parsed.fragment.equals("chapters", ignoreCase = true) -> "$base#chapters"
                uuid != null -> "$base/chapter/$uuid"
                else -> base
            }
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val id = extractPathWord(parsed.toString()) ?: return null
            if (parsed.fragment.equals("chapters", ignoreCase = true)) {
                return ResolvedLink.Feed(
                    FeedRequest(
                        siteId = SiteId.MANGACOPY,
                        section = "chapters",
                        category = id,
                    ),
                )
            }
            val uuid = parseChapterUuid(parsed.toString())
            val canonical = if (uuid != null) "${descriptor.baseUrl}/comic/$id/chapter/$uuid" else "${descriptor.baseUrl}/comic/$id"
            val itemId = if (uuid != null) "$id:$uuid" else id
            return ResolvedLink.Gallery(SiteId.MANGACOPY, itemId, canonical)
        }
    }

    // -------------------------------------------------------------------------
    // Feeds, Search & Chapters
    // -------------------------------------------------------------------------

    private suspend fun loadComicsFeed(
        page: Int,
        ordering: String,
        theme: String?,
        top: String?,
        title: String,
    ): FeedPage {
        val params = buildList {
            add("free_type" to "1")
            add("ordering" to ordering)
            add("limit" to PAGE_SIZE.toString())
            add("offset" to ((page - 1) * PAGE_SIZE).toString())
            add("platform" to "3")
            if (!theme.isNullOrBlank()) add("theme" to theme)
            if (!top.isNullOrBlank()) add("top" to top)
        }
        val results = apiCopyOrHot(path = "comics", query = params)
        val items = mangacopyParseSummaryList(results.safeArray("list"), descriptor)
        if (items.isEmpty()) {
            throw SiteException.ParseError("拷贝漫画列表结构不可识别", siteId = SiteId.MANGACOPY)
        }
        val total = results.safeInt("total") ?: 0
        val offset = results.safeInt("offset") ?: ((page - 1) * PAGE_SIZE)
        return FeedPage(
            items = items,
            hasMore = offset + items.size < total,
            nextPage = if (offset + items.size < total) page + 1 else null,
            totalCount = total,
            title = title,
        )
    }

    private suspend fun loadRecsFeed(page: Int): FeedPage {
        val offset = (page - 1) * PAGE_SIZE
        val results = apiCopyOrHot(
            path = "recs",
            query = listOf(
                "pos" to "3200102",
                "limit" to PAGE_SIZE.toString(),
                "offset" to offset.toString(),
                "platform" to "3",
            ),
        )
        val items = mangacopyParseSummaryList(results.safeArray("list"), descriptor)
        val total = results.safeInt("total") ?: items.size
        return FeedPage(
            items = items,
            hasMore = offset + items.size < total && items.isNotEmpty(),
            nextPage = if (offset + items.size < total && items.isNotEmpty()) page + 1 else null,
            totalCount = total,
            title = "推荐漫画",
        )
    }

    private suspend fun loadRankFeed(section: String, page: Int): FeedPage {
        val dateType = when (section) {
            "rank_week" -> "week"
            "rank_month" -> "month"
            "rank_total" -> "total"
            else -> "day"
        }
        val title = when (dateType) {
            "week" -> "周榜"
            "month" -> "月榜"
            "total" -> "总榜"
            else -> "日榜"
        }
        val offset = (page - 1) * PAGE_SIZE
        val results = apiCopyOrHot(
            path = "ranks",
            query = listOf(
                "type" to "1",
                "date_type" to dateType,
                "audience_type" to "male",
                "limit" to PAGE_SIZE.toString(),
                "offset" to offset.toString(),
                "platform" to "3",
            ),
        )
        val items = mangacopyParseSummaryList(results.safeArray("list"), descriptor)
        val total = results.safeInt("total") ?: items.size
        return FeedPage(
            items = items,
            hasMore = offset + items.size < total && items.isNotEmpty(),
            nextPage = if (offset + items.size < total && items.isNotEmpty()) page + 1 else null,
            totalCount = total,
            title = title,
        )
    }

    private suspend fun searchComics(query: String, page: Int, scope: SearchScope): FeedPage {
        val offset = (page - 1) * PAGE_SIZE
        val results = if (scope == SearchScope.AUTHOR) {
            apiCopyOrHot(
                path = "comics",
                query = listOf(
                    "free_type" to "1",
                    "ordering" to "-popular",
                    "author" to query,
                    "limit" to PAGE_SIZE.toString(),
                    "offset" to offset.toString(),
                    "platform" to "3",
                ),
            )
        } else {
            apiCopyOrHot(
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
        val items = mangacopyParseSummaryList(results.safeArray("list"), descriptor)
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

    private suspend fun fetchComicDetailResults(pathWord: String): JsonObject =
        detailCache.getOrPutSuspend(pathWord) {
            apiCopyOrHot("comic2/$pathWord", listOf("platform" to "3"))
        }

    private suspend fun loadChapters(
        pathWord: String,
        groups: JsonObject?,
        throwIfAllFailed: Boolean = true,
        forceRefresh: Boolean = false,
    ): List<CopyHotApiSupport.ChapterEntry> {
        if (!forceRefresh) {
            chapterListCache.get(pathWord)?.let { return it }
        }
        val groupPairs = groups?.values?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val gPath = obj.safeString("path_word").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val gName = obj.safeString("name")
            gPath to gName
        }.orEmpty().ifEmpty { listOf("default" to "") }

        val collected = LinkedHashMap<String, CopyHotApiSupport.ChapterEntry>()
        var succeededGroups = 0
        var firstError: Throwable? = null
        // True when a group stopped early because of a network/parse failure instead of reaching its
        // last page. A partial prefix must never be presented (or cached) as the full chapter list.
        var paginationTruncated = false

        for ((groupPath, groupName) in groupPairs) {
            var offset = 0
            var groupSucceeded = false
            var groupComplete = false
            var pageCount = 0
            while (pageCount < MAX_CHAPTER_PAGES) {
                pageCount++
                val results = try {
                    apiCopyOrHot(
                        path = "comic/$pathWord/group/$groupPath/chapters",
                        query = listOf(
                            "limit" to CHAPTER_PAGE_SIZE.toString(),
                            "offset" to offset.toString(),
                            "platform" to "3",
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
                if (list.isEmpty()) {
                    groupComplete = true
                    break
                }
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
                if (offset >= total || list.size < CHAPTER_PAGE_SIZE) {
                    groupComplete = true
                    break
                }
            }
            if (groupSucceeded) succeededGroups += 1
            if (!groupComplete) paginationTruncated = true
        }
        if (throwIfAllFailed && succeededGroups == 0 && firstError != null) {
            throw firstError
        }
        val chapters = collected.values.toList()
        // Caching a truncated prefix would make the reader (and the 5-minute cache) treat the
        // remainder as non-existent; report the failure instead so a retry re-fetches it.
        if (paginationTruncated) {
            throw firstError ?: SiteException.ParseError(
                message = "拷贝漫画章节目录未能完整加载，请重试",
                siteId = SiteId.MANGACOPY,
            )
        }
        if (chapters.isNotEmpty()) {
            chapterListCache.put(pathWord, chapters)
        }
        return chapters
    }

    private suspend fun loadChapterFeed(pathWord: String, forceRefresh: Boolean = false): FeedPage {
        val results = fetchComicDetailResults(pathWord)
        val chapters = loadChapters(pathWord, results.safeObject("groups"), throwIfAllFailed = true, forceRefresh = forceRefresh)
        val items = chapters.map { chapter ->
            MediaItem(
                siteId = SiteId.MANGACOPY,
                id = "$pathWord:${chapter.uuid}",
                url = "${descriptor.baseUrl}/comic/$pathWord/chapter/${chapter.uuid}",
                kind = MediaKind.SERIAL_COMIC,
                title = chapter.displayTitle,
                extra = mapOf("comicId" to pathWord, "chapterUuid" to chapter.uuid),
            )
        }
        return FeedPage(items = items, title = "全部章节")
    }

    /**
     * 三级图页加载链：
     * 1) 拷贝签名 API `/chapter2/{uuid}`（按 `words` 重排）-> `/chapter/{uuid}`
     * 2) 热辣同源集群 `/chapter/{uuid}`（抗 210 限制）
     * 3) 桌面网页 `/comic/{pathWord}/chapter/{uuid}` AES-CBC 解密兜底
     */
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

        // Tier 1: CopyManga App signed API (/chapter2/ then /chapter/)
        var lastError: Throwable? = null
        for (suffix in listOf("chapter2", "chapter")) {
            val apiResult = try {
                val results = apiCopyOnly(
                    path = "comic/$pathWord/$suffix/$uuid",
                    query = listOf("platform" to "3"),
                    allowDynamicRefresh = false,
                )
                val chapterObj = results.safeObject("chapter") ?: results
                CopyHotApiSupport.orderChapterImages(chapterObj)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                lastError = error
                emptyList()
            }
            if (apiResult.isNotEmpty()) {
                chapterImageCache.put(cacheKey, apiResult)
                return apiResult
            }
        }

        // Tier 2: HotManga cluster fallback
        val hotResult = try {
            val results = apiHotFallback(
                path = "comic/$pathWord/chapter/$uuid",
                query = listOf("platform" to "3"),
            )
            val chapterObj = results.safeObject("chapter") ?: results
            CopyHotApiSupport.orderChapterImages(chapterObj)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            if (lastError == null) lastError = error
            emptyList()
        }
        if (hotResult.isNotEmpty()) {
            chapterImageCache.put(cacheKey, hotResult)
            return hotResult
        }

        // Tier 3: Desktop HTML AES-CBC fallback
        val htmlImages = try {
            loadChapterImagesFromDesktopHtml(pathWord, uuid)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            throw (error as? SiteException ?: (lastError ?: error))
        }
        if (htmlImages.isNotEmpty()) {
            chapterImageCache.put(cacheKey, htmlImages)
        }
        return htmlImages
    }

    private suspend fun loadChapterImagesFromDesktopHtml(pathWord: String, uuid: String): List<String> =
        desktopFallback.loadChapterImagesFromDesktopHtml(pathWord, uuid)

    // -------------------------------------------------------------------------
    // API Transport with Multi-Node Routing, Dynamic Config & HotManga Fallback
    // -------------------------------------------------------------------------

    private suspend fun apiCopyOrHot(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): JsonObject {
        val copyError = try {
            return apiCopyOnly(path, query, allowDynamicRefresh = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            error
        }

        val shouldFallbackToHot = copyError is SiteException.Forbidden ||
            copyError is SiteException.RateLimited ||
            copyError is SiteException.Network ||
            copyError is SiteException.TlsError ||
            copyError is SiteException.NotFound
        if (!shouldFallbackToHot) throw copyError

        return try {
            apiHotFallback(path, query)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            throw copyError
        }
    }

    private suspend fun apiCopyOnly(
        path: String,
        query: List<Pair<String, String>>,
        allowDynamicRefresh: Boolean,
    ): JsonObject {
        val candidates = mangacopyCopyCandidates(dynamicCopyHosts)
        val routeKey = httpClient.currentRouteKey(candidates.map { "https://$it/api/v3/comics" })
        val preferred = copyRouter.activeHost(routeKey)
        val ordered = buildList {
            if (preferred.isNotBlank()) add(preferred)
            candidates.forEach { if (it !in this) add(it) }
        }.take(MAX_COPY_HOST_ATTEMPTS)

        val token = sessionStore?.getBearerToken(SiteId.MANGACOPY)?.takeIf { it.isNotBlank() }
        var lastError: Throwable? = null

        for (host in ordered) {
            // Token confinement: only pinned official API hosts receive `Authorization: Token …`.
            // Runtime-discovered hosts still get the app signature headers (they need those), but
            // never the account credential.
            val headers = buildCopyHeaders(token.takeIf { mangacopyIsCredentialHost(host) })
            // `buildCopyHeaders` may persist the stable device identity on first use; capture the
            // epoch after that write so it cannot look like an account switch.
            val requestEpoch = if (token != null) sessionStore?.authEpoch(SiteId.MANGACOPY) else null
            try {
                val results = executeApiOnHost(
                    host = host,
                    path = path,
                    query = query,
                    headers = headers,
                    authEpoch = requestEpoch,
                )
                copyRouter.reportHostSuccess(host, routeKey)
                if (allowDynamicRefresh) {
                    maybeRefreshDynamicConfig(host, headers, requestEpoch)
                }
                return results
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                if (error is SiteException.Cancelled) throw error
                copyRouter.reportHostFailure(host, routeKey, error)
                lastError = preferHigherPriorityError(lastError, error)
            }
        }
        throw lastError ?: SiteException.Network("拷贝漫画接口节点均不可用", siteId = SiteId.MANGACOPY)
    }

    private fun preferHigherPriorityError(current: Throwable?, incoming: Throwable): Throwable =
        desktopFallback.preferHigherPriorityError(current, incoming)

    private suspend fun apiHotFallback(
        path: String,
        query: List<Pair<String, String>>,
    ): JsonObject {
        val routeKey = httpClient.currentRouteKey(
            CopyHotApiSupport.HOTMANGA_API_HOSTS.map { "https://$it/api/v3/comics" },
        )
        val preferred = hotFallbackRouter.activeHost(routeKey)
        val ordered = buildList {
            if (preferred.isNotBlank()) add(preferred)
            CopyHotApiSupport.HOTMANGA_API_HOSTS.forEach { if (it !in this) add(it) }
        }.take(MAX_HOT_FALLBACK_ATTEMPTS)

        val headers = CopyHotApiSupport.buildHotmangaHeaders()
        var lastError: Throwable? = null
        for (host in ordered) {
            try {
                val results = executeApiOnHost(
                    host = host,
                    path = path,
                    query = query,
                    headers = headers,
                )
                hotFallbackRouter.reportHostSuccess(host, routeKey)
                return results
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                hotFallbackRouter.reportHostFailure(host, routeKey, error)
                lastError = error
            }
        }
        throw lastError ?: SiteException.Network("拷贝漫画备用节点不可用", siteId = SiteId.MANGACOPY)
    }

    private suspend fun executeApiOnHost(
        host: String,
        path: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        authEpoch: Long? = null,
    ): JsonObject {
        val builder = ComicUrl.parse("https://$host/api/v3")?.addPathSegments(path)
            ?: throw SiteException.ParseError("拷贝漫画接口地址无效", siteId = SiteId.MANGACOPY)
        val url = query.fold(builder) { acc, (k, v) -> acc.addQueryParameter(k, v) }.toString()
        if (authEpoch != null && sessionStore?.authEpoch(SiteId.MANGACOPY) != authEpoch) {
            throw SiteException.Cancelled("拷贝漫画会话已切换", siteId = SiteId.MANGACOPY)
        }
        val response = try {
            httpClient.getText(
                url = url,
                siteId = SiteId.MANGACOPY,
                kind = SiteRequestKind.API,
                headers = headers,
                allowCache = authEpoch == null,
                authEpoch = authEpoch,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SiteException) {
            throw se
        } catch (e: Exception) {
            throw SiteException.Network(
                message = "拷贝漫画接口请求失败：${e.message ?: "未知错误"}",
                siteId = SiteId.MANGACOPY,
                cause = e,
            )
        }
        if (authEpoch != null && sessionStore?.authEpoch(SiteId.MANGACOPY) != authEpoch) {
            throw SiteException.Cancelled("拷贝漫画会话已切换", siteId = SiteId.MANGACOPY)
        }
        response.throwIfNotSuccessful(SiteId.MANGACOPY, descriptor.displayName)
        val root = runCatching { json.parseToJsonElement(response.bodyText()).jsonObject }.getOrNull()
            ?: throw SiteException.ParseError("拷贝漫画接口数据格式异常", siteId = SiteId.MANGACOPY)
        val code = root.safeInt("code") ?: 200
        if (code != 200) {
            val apiMsg = root.safeString("message")
            CopyHotApiSupport.parseApiErrorOrNull(code, apiMsg, SiteId.MANGACOPY)?.let { throw it }
        }
        return root.safeObject("results") ?: JsonObject(emptyMap())
    }

    private suspend fun maybeRefreshDynamicConfig(
        activeHost: String,
        headers: Map<String, String>,
        authEpoch: Long? = null,
    ) {
        val now = clockMs()
        if (now - lastConfigRefreshAtMs < CONFIG_REFRESH_COOLDOWN_MS) return
        if (!configMutex.tryLock()) return
        try {
            if (clockMs() - lastConfigRefreshAtMs < CONFIG_REFRESH_COOLDOWN_MS) return
            lastConfigRefreshAtMs = clockMs()

            runCatching {
                val netResp = httpClient.execute(
                    SiteHttpRequest(
                        url = "https://$activeHost/api/v3/system/network2?platform=3",
                        siteId = SiteId.MANGACOPY,
                        kind = SiteRequestKind.API,
                        headers = headers,
                        allowCache = authEpoch == null,
                        authEpoch = authEpoch,
                        timeoutMs = CONFIG_PROBE_TIMEOUT_MS,
                    ),
                )
                if (netResp.isSuccessful) {
                    val root = json.parseToJsonElement(netResp.bodyText()).jsonObject
                    if ((root.safeInt("code") ?: 200) == 200) {
                        val apiRoutes = root.safeObject("results")?.safeArray("api").orEmpty()
                        val discovered = buildList {
                            apiRoutes.forEach { routeGroup ->
                                (routeGroup as? JsonArray).orEmpty().forEach { entry ->
                                    val host = entry.safePrimitiveContent()?.trim()?.lowercase().orEmpty()
                                    if (isValidDiscoveredHost(host) && host !in this) {
                                        add(host)
                                    }
                                }
                            }
                        }
                        if (discovered.isNotEmpty()) {
                            dynamicCopyHosts = discovered
                        }
                    }
                }
            }.onFailure { if (it is CancellationException) throw it }

            runCatching {
                val verResp = httpClient.execute(
                    SiteHttpRequest(
                        url = "https://$activeHost/api/v3/system/appVersion/last?platform=3",
                        siteId = SiteId.MANGACOPY,
                        kind = SiteRequestKind.API,
                        headers = headers,
                        allowCache = authEpoch == null,
                        authEpoch = authEpoch,
                        timeoutMs = CONFIG_PROBE_TIMEOUT_MS,
                    ),
                )
                if (verResp.isSuccessful) {
                    val root = json.parseToJsonElement(verResp.bodyText()).jsonObject
                    if ((root.safeInt("code") ?: 200) == 200) {
                        val results = root.safeObject("results")
                        val candidate = results?.safeObject("android")?.safeString("version")
                            ?.takeIf { it.isNotBlank() }
                            ?: results?.safeString("version")?.takeIf { it.isNotBlank() }
                        if (candidate != null) {
                            appVersion = candidate
                        }
                    }
                }
            }.onFailure { if (it is CancellationException) throw it }
        } finally {
            configMutex.unlock()
        }
    }

    private fun buildCopyHeaders(token: String?): Map<String, String> {
        val (device, pseudo) = CopyHotApiSupport.stableDeviceIdentity(sessionStore, SiteId.MANGACOPY)
        return CopyHotApiSupport.buildCopyAppHeaders(
            version = appVersion,
            device = device,
            pseudoId = pseudo,
            token = token,
            epochMillis = clockMs(),
        )
    }

    private fun extractPathWord(url: String): String? = desktopFallback.extractPathWord(url)

    private fun parseChapterUuid(url: String): String? = desktopFallback.parseChapterUuid(url)

    private fun isValidDiscoveredHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (!DISCOVERED_HOSTNAME_REGEX.matches(host)) return false
        if (IPV4_HOST_REGEX.matches(host)) return false
        return true
    }

    companion object {
        val SITE_ID: SiteId = SiteId.MANGACOPY
        const val BASE_URL: String = "https://www.mangacopy.com"
        const val API_BASE: String = "https://api.copy202601.com/api/v3"

        private val DISCOVERED_HOSTNAME_REGEX =
            Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")
        private val IPV4_HOST_REGEX = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")

        private const val PAGE_SIZE = 30
        private const val CHAPTER_PAGE_SIZE = 100
        private const val MAX_CHAPTER_PAGES = 50
        private const val MAX_COPY_HOST_ATTEMPTS = 4
        private const val MAX_HOT_FALLBACK_ATTEMPTS = 3
        private const val DETAIL_CACHE_TTL_MS = 5 * 60 * 1_000L
        private const val CHAPTER_CACHE_TTL_MS = 30 * 60 * 1_000L
        private const val CONFIG_REFRESH_COOLDOWN_MS = 10 * 60 * 1_000L
        private const val CONFIG_PROBE_TIMEOUT_MS = 4_000L

        internal val WEB_HOSTS = listOf(
            "www.mangacopy.com",
            "www.copy4000.com",
            "www.copy5000.com",
            "www.copy3000.com",
            "www.copy20.com",
            "copymanga.fun",
        )

        internal val KEY_REGEX = Regex("""var cct = '([^']*)'""")
        internal val CONTENT_KEY_REGEX = Regex("""var contentKey = '([^']*)'""")

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "拷贝漫画",
            shortName = "拷贝",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "www.mangacopy.com",
            mirrorHosts = listOf(
                "copymanga.com", "www.copymanga.com", "www.copy4000.com",
                "copy4000.com", "www.copy5000.com", "copy5000.com",
                "www.copy3000.com", "copy3000.com", "copy20.com", "2025copy.com", "copymanga.fun",
            ),
            accessNote = "已启用官方 3.1.1 签名通道与多节点防限流兜底；支持可选账号登录",
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
                add(SiteSectionSpec("finish", "已完结", defaultSort = FeedSort.NEWEST))
                add(SiteSectionSpec("rank_day", "日榜", defaultSort = FeedSort.POPULAR))
                add(SiteSectionSpec("rank_week", "周榜", defaultSort = FeedSort.POPULAR))
                add(SiteSectionSpec("rank_month", "月榜", defaultSort = FeedSort.POPULAR))
                add(SiteSectionSpec("rank_total", "总榜", defaultSort = FeedSort.POPULAR))
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
                apiHosts = (CopyHotApiSupport.COPY_API_HOSTS + CopyHotApiSupport.HOTMANGA_API_HOSTS).toSet(),
                mediaDomains = setOf(
                    "mangafunb.fun",
                    "mangafuna.xyz",
                    "mangafunc.fun",
                    "copy4000.com",
                    "copy5000.com",
                    "copy3000.com",
                    "2026copy.com",
                ),
                pageIntervalMs = 800L,
                imageIntervalMs = 150L,
            ),
        )
    }
}
