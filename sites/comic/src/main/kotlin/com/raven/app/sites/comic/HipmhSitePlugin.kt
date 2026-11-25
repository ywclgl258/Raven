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
 * 嬉皮漫畫 (`SiteId.HIPMH`) 连载漫画插件。
 *
 * 移动端站点 `m.hipmh.com` 为 Astro 服务端渲染：
 * - 首页 / 分类 / 排行榜直接输出 `/works/<slug>` 卡片
 * - 作品页包含 `<title>`、`og:image` 与 `/chapter/go?hid=...` 章节入口
 *
 * 章节阅读页会通过 JavaScript 跳转到独立的 `reader.hipmh.top` 域名并动态拼装图页，
 * 服务端 HTML 不含图片地址，因此阅读接口返回 [SiteException.Unsupported]。
 */
class HipmhSitePlugin(
    private val httpClient: HttpClientAdapter,
) : SitePlugin {

    override val descriptor: SiteDescriptor = DESCRIPTOR

    override val feedSource: FeedSource = object : FeedSource {
        override suspend fun loadHome(language: ComicLanguage): HomeFeed {
            val page = loadFeed(FeedRequest(siteId = SiteId.HIPMH, section = "latest", page = 1))
            return HomeFeed(siteId = SiteId.HIPMH, primaryFeedPage = page)
        }

        override suspend fun loadFeed(request: FeedRequest): FeedPage {
            val page = request.page.coerceAtLeast(1)
            val query = request.query?.trim().orEmpty()
            val url = when {
                query.isNotEmpty() -> "${descriptor.baseUrl}/search?q=${HtmlUrlCleaner.encodeQueryParam(query)}"
                page <= 1 -> "${descriptor.baseUrl}/"
                else -> "${descriptor.baseUrl}/popularity?page=$page"
            }
            val html = fetchHtml(url)
            val doc = Jsoup.parse(html, url)
            val items = parseWorks(doc)
            if (items.isEmpty()) {
                throw SiteException.ParseError("嬉皮漫畫列表结构不可识别", siteId = SiteId.HIPMH)
            }
            val hasMore = doc.selectFirst("a[href*='page=${page + 1}']") != null
            return FeedPage(
                items = items,
                nextPage = if (hasMore) page + 1 else null,
                hasMore = hasMore,
                title = query.ifEmpty { "最近更新" },
            )
        }

        override fun supportedSorts(request: FeedRequest): List<FeedSort> = listOf(FeedSort.NEWEST, FeedSort.POPULAR)
    }

    override val searchSource: SearchSource = object : SearchSource {
        override suspend fun search(request: FeedRequest): FeedPage = feedSource.loadFeed(request)
        override val supportedScopes: Set<SearchScope> = setOf(SearchScope.ALL)
    }

    override val gallerySource: GallerySource = object : GallerySource {
        override suspend fun loadGalleryDetail(url: String): GalleryDetail {
            val slug = extractWorkSlug(url)
                ?: throw SiteException.ParseError("无法识别嬉皮漫畫地址：$url", siteId = SiteId.HIPMH)
            val canonical = "${descriptor.baseUrl}/works/$slug"
            val html = fetchHtml(canonical)
            val doc = Jsoup.parse(html, canonical)
            val title = HtmlUrlCleaner.cleanText(doc.title().substringBefore(" - "))
                .ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
                .ifBlank { "作品 $slug" }
            val cover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            val chapterHref = doc.selectFirst("a[href*=/chapter/go?]")?.attr("href").orEmpty()
            return GalleryDetail(
                item = MediaItem(
                    siteId = SiteId.HIPMH,
                    id = slug,
                    url = canonical,
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = if (cover.startsWith("/")) "${descriptor.baseUrl}$cover" else cover,
                    extra = buildMap {
                        put("chapterHref", chapterHref)
                    },
                ),
                pageCount = 1,
                pages = emptyList(),
            )
        }

        override suspend fun loadGalleryPage(
            detail: GalleryDetail,
            pageIndex: Int,
            forceReload: Boolean,
        ): GalleryPage {
            throw SiteException.Unsupported(
                message = "嬉皮漫畫阅读页由 reader.hipmh.top 动态渲染，服务端不返回图页地址",
                siteId = SiteId.HIPMH,
            )
        }

        override suspend fun resolvePageImage(
            pageUrl: String,
            preferOriginal: Boolean,
            forceReload: Boolean,
        ): GalleryPage {
            throw SiteException.Unsupported(
                message = "嬉皮漫畫阅读页由 reader.hipmh.top 动态渲染，服务端不返回图页地址",
                siteId = SiteId.HIPMH,
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
            val slug = extractWorkSlug(parsed.toString()) ?: return null
            return "${descriptor.baseUrl}/works/$slug"
        }

        override fun resolve(url: String): ResolvedLink? {
            val parsed = ComicUrl.parse(url)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
            val slug = extractWorkSlug(parsed.toString()) ?: return null
            return ResolvedLink.Gallery(SiteId.HIPMH, slug, "${descriptor.baseUrl}/works/$slug")
        }
    }

    private fun parseWorks(doc: org.jsoup.nodes.Document): List<MediaItem> {
        val seen = LinkedHashMap<String, MediaItem>()
        doc.select("a[href*=/works/]").forEach { anchor ->
            val href = anchor.attr("href")
            val slug = href.substringAfter("/works/", "").substringBefore("?").substringBefore("/")
            if (slug.isBlank()) return@forEach
            val container = anchor.closest("article, li, .work-card, .card") ?: anchor
            val title = HtmlUrlCleaner.cleanText(anchor.attr("title").ifBlank { anchor.text() })
                .ifBlank { container.selectFirst("h3, h4, .title")?.text().orEmpty() }
            if (title.isBlank()) return@forEach
            val cover = container.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }.orEmpty()
            val normalizedSlug = slug.removePrefix("zh/")
            seen.putIfAbsent(
                normalizedSlug,
                MediaItem(
                    siteId = SiteId.HIPMH,
                    id = normalizedSlug,
                    url = "${descriptor.baseUrl}/works/$normalizedSlug",
                    kind = MediaKind.SERIAL_COMIC,
                    title = title,
                    coverUrl = if (cover.startsWith("/")) "${descriptor.baseUrl}$cover" else cover,
                ),
            )
        }
        return seen.values.toList()
    }

    private fun extractWorkSlug(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val idx = parsed.pathSegments.indexOf("works")
        if (idx >= 0 && idx + 1 < parsed.pathSegments.size) {
            return parsed.pathSegments[idx + 1].takeIf { it.isNotBlank() }
        }
        return null
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

    companion object {
        val SITE_ID: SiteId = SiteId.HIPMH
        const val BASE_URL: String = "https://m.hipmh.com"

        val DESCRIPTOR: SiteDescriptor = SiteDescriptor(
            id = SITE_ID,
            displayName = "嬉皮漫畫",
            shortName = "嬉皮",
            primaryKind = MediaKind.SERIAL_COMIC,
            supportedKinds = setOf(MediaKind.SERIAL_COMIC),
            defaultHost = "m.hipmh.com",
            mirrorHosts = listOf("hipmh.com", "www.hipmh.com"),
            accessNote = "阅读页由独立阅读器动态渲染",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH),
            feedSorts = listOf(FeedSort.NEWEST, FeedSort.POPULAR),
            sections = listOf(SiteSectionSpec("latest", "最近更新", defaultSort = FeedSort.NEWEST)),
            searchScopes = listOf(SearchScope.ALL),
            networkProfile = SiteNetworkProfile(
                userAgent = SiteNetworkProfile.DEFAULT_MOBILE_UA,
                referer = "$BASE_URL/",
                mediaReferer = "$BASE_URL/",
                mediaDomains = setOf("hipmh.com", "hipmh.top"),
            ),
        )
    }
}