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
 * MyComic (`SiteId.MYCOMIC`) 连载漫画插件。
 *
 * mycomic.com 通过 Cloudflare 托管质询拦截非浏览器流量（HTTP 403 + challenge 页面），
 * 因此插件在检测到质询时抛出 [SiteException.Forbidden]（`isChallenge = true`），
 * 由 UI 提示用户切换网络或代理；若通过验证，则按标准目录式漫画站解析列表与详情。
 */
class MycomicSitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(FeedRequest(siteId = SiteId.MYCOMIC, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.MYCOMIC, primaryFeedPage = page)
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
                throw SiteException.ParseError("MyComic 列表结构不可识别", siteId = SiteId.MYCOMIC)
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
                ?: throw SiteException.ParseError("无法识别 MyComic 地址：$url", siteId = SiteId.MYCOMIC)
            val canonical = "${descriptor.baseUrl}/comic/$slug"
            val html = fetchHtml(canonical)
            val doc = Jsoup.parse(html, canonical)
            val title = HtmlUrlCleaner.cleanText(doc.selectFirst("h1")?.text().orEmpty()).ifBlank { "Comic $slug" }
            val cover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.MYCOMIC,
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
            message = "MyComic 章节图页结构尚未适配",
            siteId = SiteId.MYCOMIC,
        )

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage = throw SiteException.Unsupported(
            message = "MyComic 章节图页结构尚未适配",
            siteId = SiteId.MYCOMIC,
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
            return ResolvedLink.Gallery(SiteId.MYCOMIC, slug, "${descriptor.baseUrl}/comic/$slug")
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
                    siteId = SiteId.MYCOMIC,
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

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "MyComic",
            shortName = "MyComic",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "mycomic.com",
            accessNote = "站点启用 Cloudflare 质询，可能需要代理",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH),
            feedSorts = listOf(FeedSort.NEWEST),
            sections = listOf(SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST)),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DESKTOP_WINDOWS_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
            ),
        )
    }
}