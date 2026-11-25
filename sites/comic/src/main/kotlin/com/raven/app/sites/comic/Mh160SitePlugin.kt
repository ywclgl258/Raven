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
import org.jsoup.Jsoup

/**
 * 漫画160 (`SiteId.MH160`) 连载漫画插件。
 *
 * 站点当前对默认网络返回 HTTP 404 的通用错误页（域名可能在更换或限制地区），
 * 插件按标准目录式漫画站解析：
 * - 列表：首页 / 分类页的 `/comic/<slug>` 或 `/manga/<slug>` 卡片
 * - 详情：`<h1>` 标题 + 封面
 * - 阅读：章节页图页
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
            val url = when {
                query.isNotEmpty() -> "${descriptor.baseUrl}/search?q=${HtmlUrlCleaner.encodeQueryParam(query)}"
                page <= 1 -> "${descriptor.baseUrl}/"
                else -> "${descriptor.baseUrl}/?page=$page"
            }
            val html = fetchHtml(url)
            val doc = Jsoup.parse(html, url)
            val items = parseComicCards(doc)
            if (items.isEmpty()) {
                throw SiteException.ParseError(
                    "漫画160 列表结构不可识别（站点可能已更换域名或限制访问）",
                    siteId = SiteId.MH160,
                )
            }
            return FeedPage(items = items, title = query.ifEmpty { "最近更新" })
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = listOf(FeedSort.NEWEST)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val slug = extractSlug(url)
                ?: throw SiteException.ParseError("无法识别漫画160地址：$url", siteId = SiteId.MH160)
            val canonical = "${descriptor.baseUrl}/comic/$slug"
            val html = fetchHtml(canonical)
            val doc = Jsoup.parse(html, canonical)
            val title = HtmlUrlCleaner.cleanText(doc.selectFirst("h1")?.text().orEmpty()).ifBlank { "漫画 $slug" }
            val cover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.MH160,
                    id = slug,
                    url = canonical,
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = cover,
                ),
                pageCount = 1,
                pages = emptyList(),
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage = throw SiteException.Unsupported(
            message = "漫画160 章节图页结构尚未适配",
            siteId = SiteId.MH160,
        )

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage = throw SiteException.Unsupported(
            message = "漫画160 章节图页结构尚未适配",
            siteId = SiteId.MH160,
        )
    }

    override val linkResolver: LinkResolver = object : LinkResolver {
        override fun canHandle(url: String): Boolean {
            val parsed = ComicUrl.parse(url) ?: return false
            return descriptor.matchesHost(parsed.host)
        }

        override fun canonicalize(url: String): String? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val slug = extractSlug(parsed.toString()) ?: return null
            return "${descriptor.baseUrl}/comic/$slug"
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val slug = extractSlug(parsed.toString()) ?: return null
            return ResolvedLink.Gallery(SiteId.MH160, slug, "${descriptor.baseUrl}/comic/$slug")
        }
    }

    private fun parseComicCards(doc: org.jsoup.nodes.Document): List<MediaItem> {
        val seen = LinkedHashMap<String, MediaItem>()
        doc.select("a[href*=/comic/], a[href*=/manga/]").forEach { anchor ->
            val href = anchor.attr("href")
            val slug = href.substringAfterLast("/").substringBefore("?").substringBefore("#")
            if (slug.isBlank()) return@forEach
            val container = anchor.closest("li, article, .item, .card") ?: anchor
            val title = HtmlUrlCleaner.cleanText(
                container.selectFirst("h3, h4, .title")?.text() ?: anchor.attr("title").ifBlank { anchor.text() },
            )
            if (title.isBlank()) return@forEach
            val cover = container.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("data-original") }.ifBlank { img.attr("src") }
            }.orEmpty()
            seen.putIfAbsent(
                slug,
                MediaItem(
                    siteId = SiteId.MH160,
                    id = slug,
                    url = if (href.startsWith("http")) href else "${descriptor.baseUrl}$href",
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = if (cover.startsWith("/")) "${descriptor.baseUrl}$cover" else cover,
                ),
            )
        }
        return seen.values.toList()
    }

    private fun extractSlug(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOfFirst { it == "comic" || it == "manga" }
        if (idx >= 0 && idx + 1 < segments.size) return segments[idx + 1].takeIf { it.isNotBlank() }
        return segments.lastOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchHtml(url: String): String {
        val response = try {
            httpClient.getText(url = url, siteId = SiteId.MH160, kind = SiteRequestKind.PAGE)
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

    companion object {
        val SITE_ID: SiteId = SiteId.MH160
        const val BASE_URL: String = "https://mh160mh.com"

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "漫画160",
            shortName = "漫160",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "mh160mh.com",
            accessNote = "站点当前对默认网络返回 404，可能已更换域名",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH),
            feedSorts = listOf(FeedSort.NEWEST),
            sections = listOf(SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST)),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
            ),
        )
    }
}