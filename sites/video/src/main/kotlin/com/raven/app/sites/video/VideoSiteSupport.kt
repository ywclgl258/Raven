package com.raven.app.sites.video

import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSection
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.HostRouter
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.URI
import kotlin.math.roundToLong

internal object VideoSiteSupport {
    private val contentMarkers = listOf(
        "entry-title",
        "generate-columns",
        "post-thumbnail",
        "wp-content",
        "<main",
        "<article",
        "id=\"content",
        "class=\"content",
        "video-card",
        "video-item-container",
        "video-img-box",
        "pcVideoListItem",
        "thumbnail group",
    )

    private val colonDurationRegex = Regex("^(\\d+):(\\d{2})(?::(\\d{2}))?$")
    private val chineseDurationRegex = Regex("^(?:(\\d+)\\s*(?:小時|小时|時|时))?(?:(\\d+)\\s*分)?(?:(\\d+)\\s*秒)?$")
    private val isoDurationRegex = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$", RegexOption.IGNORE_CASE)
    private val countNumberRegex = Regex("([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbB萬万千])?")
    private val ratingRegex = Regex("(?:评分\\s*)?(?:Average:\\s*)?(\\d+(?:\\.\\d+)?)\\s*(%|/\\s*5|★|星)?", RegexOption.IGNORE_CASE)
    private val heightRegex = Regex("(\\d{3,4})p?", RegexOption.IGNORE_CASE)

    fun isChallengeHtml(body: String): Boolean {
        if (body.contains("Checking your browser", ignoreCase = true)) return true
        if (body.contains("Just a moment", ignoreCase = true)) return true
        if (body.contains("cf-mitigated", ignoreCase = true)) return true
        if (body.contains("DDoS-Guard", ignoreCase = true) && !body.contains("class=\"thumbs")) return true
        if (body.contains("challenge-platform", ignoreCase = true) && !hasPageContent(body)) return true
        return false
    }

    fun isChallengeResponse(response: SiteHttpResponse, bodyText: String = response.bodyText()): Boolean {
        if (response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true) return true
        return isChallengeHtml(bodyText)
    }

    private fun hasPageContent(body: String): Boolean =
        body.contains("</video", ignoreCase = true) ||
            body.contains("class=\"thumbs", ignoreCase = true) ||
            contentMarkers.any { body.contains(it, ignoreCase = true) }

    fun ensureSuccess(
        response: SiteHttpResponse,
        siteId: SiteId,
        checkChallenge: Boolean = true,
    ): String {
        val text = response.bodyText()
        if (response.isSuccessful) {
            if (checkChallenge && isChallengeResponse(response, text)) {
                throw SiteException.Forbidden(
                    isChallenge = true,
                    message = "站点要求浏览器验证，请稍后重试或调整网络",
                    siteId = siteId,
                )
            }
            return text
        }
        throw toSiteException(response, siteId, text)
    }

    fun toSiteException(
        response: SiteHttpResponse,
        siteId: SiteId,
        bodyText: String = response.bodyText(),
    ): SiteException = when (response.code) {
        401 -> SiteException.AuthRequired("访问需要登录认证 (HTTP 401)", siteId = siteId)
        403 -> SiteException.Forbidden(
            isChallenge = isChallengeResponse(response, bodyText),
            message = "访问被拒绝 (HTTP 403)",
            siteId = siteId,
        )
        404 -> SiteException.NotFound("目标内容不存在 (HTTP 404)", siteId = siteId)
        429 -> SiteException.RateLimited(
            retryAfterSeconds = response.retryAfterSeconds,
            message = "请求过于频繁，已被限流 (HTTP 429)",
            siteId = siteId,
        )
        else -> SiteException.Network("请求失败：HTTP ${response.code}", siteId = siteId)
    }

    suspend fun fetchHtml(
        http: HttpClientAdapter,
        siteId: SiteId,
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowCache: Boolean = true,
        rateLimitBackoffMs: Long = 1_000L,
    ): Pair<SiteHttpResponse, String> {
        for (attempt in 1..2) {
            val response = try {
                http.execute(
                    SiteHttpRequest(
                        url = url,
                        siteId = siteId,
                        method = SiteHttpMethod.GET,
                        kind = SiteRequestKind.PAGE,
                        headers = headers,
                        allowCache = allowCache,
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SiteException) {
                throw se
            } catch (io: IOException) {
                throw SiteException.Network("网络请求失败：${io.message.orEmpty()}", siteId = siteId, cause = io)
            }

            if (response.code == 429 && attempt == 1) {
                val waitMs = computeRetryWaitMs(response.retryAfterSeconds, rateLimitBackoffMs)
                if (waitMs > 0L) delay(waitMs)
                continue
            }
            val text = ensureSuccess(response, siteId, checkChallenge = true)
            return response to text
        }
        throw SiteException.RateLimited(message = "请求过于频繁，重试后仍被限流", siteId = siteId)
    }

    suspend fun fetchHtmlWithMirror(
        http: HttpClientAdapter,
        router: HostRouter,
        siteId: SiteId,
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowCache: Boolean = true,
        rateLimitBackoffMs: Long = 1_000L,
    ): Pair<SiteHttpResponse, String> {
        val candidates = router.candidateHosts()
        val routeKey = http.currentRouteKey(candidates.map { "https://$it" })
        val preferredHost = router.activeHost(routeKey)
        val orderedHosts = buildList {
            if (preferredHost.isNotBlank()) add(preferredHost)
            candidates.forEach { host ->
                if (host.isNotBlank() && host !in this) add(host)
            }
        }
        if (orderedHosts.isEmpty()) {
            return fetchHtml(http, siteId, url, headers, allowCache, rateLimitBackoffMs)
        }

        var firstError: SiteException? = null
        for (host in orderedHosts) {
            val targetUrl = router.rewriteUrl(url, host)
            for (attempt in 1..2) {
                try {
                    val response = http.execute(
                        SiteHttpRequest(
                            url = targetUrl,
                            siteId = siteId,
                            method = SiteHttpMethod.GET,
                            kind = SiteRequestKind.PAGE,
                            headers = headers,
                            allowCache = allowCache,
                        ),
                    )
                    if (response.code == 429 && attempt == 1) {
                        val waitMs = computeRetryWaitMs(response.retryAfterSeconds, rateLimitBackoffMs)
                        if (waitMs > 0L) delay(waitMs)
                        continue
                    }
                    val text = ensureSuccess(response, siteId, checkChallenge = true)
                    router.reportHostSuccess(host, routeKey)
                    return response to text
                } catch (ce: CancellationException) {
                    throw ce
                } catch (error: SiteException) {
                    val retryable = when (error) {
                        is SiteException.Forbidden,
                        is SiteException.RateLimited,
                        is SiteException.Network,
                        is SiteException.TlsError -> true
                        else -> false
                    }
                    if (!retryable) throw error
                    router.reportHostFailure(host, routeKey, error)
                    if (firstError == null) firstError = error
                    break
                } catch (io: IOException) {
                    val wrapped = SiteException.Network("网络请求失败：${io.message.orEmpty()}", siteId = siteId, cause = io)
                    router.reportHostFailure(host, routeKey, wrapped)
                    if (firstError == null) firstError = wrapped
                    break
                }
            }
        }
        throw firstError ?: SiteException.Network("所有镜像节点均不可用", siteId = siteId)
    }

    suspend fun fetchApiText(
        http: HttpClientAdapter,
        siteId: SiteId,
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowCache: Boolean = true,
        authEpoch: Long? = null,
        rateLimitBackoffMs: Long = 1_000L,
    ): Pair<SiteHttpResponse, String> {
        for (attempt in 1..2) {
            val response = try {
                http.execute(
                    SiteHttpRequest(
                        url = url,
                        siteId = siteId,
                        method = SiteHttpMethod.GET,
                        kind = SiteRequestKind.API,
                        headers = headers,
                        allowCache = allowCache,
                        authEpoch = authEpoch,
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SiteException) {
                throw se
            } catch (io: IOException) {
                throw SiteException.Network("API 请求失败：${io.message.orEmpty()}", siteId = siteId, cause = io)
            }
            if (response.code == 429 && attempt == 1) {
                val waitMs = computeRetryWaitMs(response.retryAfterSeconds, rateLimitBackoffMs)
                if (waitMs > 0L) delay(waitMs)
                continue
            }
            val text = ensureSuccess(response, siteId, checkChallenge = false)
            return response to text
        }
        throw SiteException.RateLimited(message = "API 请求被限流", siteId = siteId)
    }

    private fun computeRetryWaitMs(retryAfterSeconds: Long?, defaultBackoffMs: Long): Long {
        if (retryAfterSeconds == null) return defaultBackoffMs.coerceAtLeast(0L)
        return retryAfterSeconds.coerceIn(0L, 120L) * 1000L
    }

    fun extractSetCookies(response: SiteHttpResponse): Map<String, String> {
        val rawHeaders = response.headers("Set-Cookie")
        if (rawHeaders.isEmpty()) return emptyMap()
        val cookies = LinkedHashMap<String, String>()
        for (header in rawHeaders) {
            val pair = header.substringBefore(';').trim()
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue
            val name = pair.substring(0, eqIdx).trim()
            val value = pair.substring(eqIdx + 1).trim()
            val lowerHeader = header.lowercase()
            val expired = lowerHeader.contains("max-age=0") || value.equals("deleted", ignoreCase = true)
            if (name.isNotEmpty() && !expired) {
                cookies[name] = value
            }
        }
        return cookies
    }

    fun formatCookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }

    fun resolveAbsolute(baseUrl: String, raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return UrlSanitizer.resolveRelative(baseUrl, raw).orEmpty()
    }

    fun parseQueryParams(uri: URI): Map<String, List<String>> {
        val rawQuery = uri.rawQuery ?: return emptyMap()
        if (rawQuery.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, MutableList<String>>()
        for (part in rawQuery.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = HtmlUrlCleaner.decodeUrlComponent(if (eq >= 0) part.substring(0, eq) else part)
            val value = if (eq >= 0) HtmlUrlCleaner.decodeUrlComponent(part.substring(eq + 1)) else ""
            if (key.isNotEmpty()) {
                result.getOrPut(key) { mutableListOf() }.add(value)
            }
        }
        return result
    }

    fun queryParam(uri: URI, name: String): String? =
        parseQueryParams(uri)[name]?.firstOrNull()

    fun pathSegments(uri: URI): List<String> =
        uri.rawPath.orEmpty()
            .split('/')
            .filter { it.isNotEmpty() }
            .map { HtmlUrlCleaner.decodeUrlComponent(it) }

    fun parseDurationSeconds(raw: String?): Int? {
        val text = HtmlUrlCleaner.cleanText(raw.orEmpty())
        if (text.isEmpty()) return null

        colonDurationRegex.matchEntire(text)?.let { match ->
            val a = match.groupValues[1].toIntOrNull() ?: return null
            val b = match.groupValues[2].toIntOrNull() ?: return null
            val c = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val total = if (c != null) a * 3600 + b * 60 + c else a * 60 + b
            return total.takeIf { it > 0 }
        }

        isoDurationRegex.matchEntire(text)?.let { match ->
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            val s = match.groupValues[3].toIntOrNull() ?: 0
            val total = h * 3600 + m * 60 + s
            return total.takeIf { it > 0 }
        }

        if (text.contains("分") || text.contains("秒") || text.contains("時") || text.contains("时")) {
            val match = chineseDurationRegex.find(text)
            if (match != null && match.value.isNotEmpty()) {
                val h = match.groupValues[1].toIntOrNull() ?: 0
                val m = match.groupValues[2].toIntOrNull() ?: 0
                val s = match.groupValues[3].toIntOrNull() ?: 0
                val total = h * 3600 + m * 60 + s
                if (total > 0) return total
            }
        }

        text.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        return null
    }

    fun parseCount(raw: String?): Long? {
        val text = HtmlUrlCleaner.cleanText(raw.orEmpty())
        if (text.isEmpty()) return null
        val match = countNumberRegex.find(text) ?: return null
        val base = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val suffix = match.groupValues[2]
        val multiplier = when (suffix.lowercase()) {
            "k", "千" -> 1_000.0
            "萬", "万" -> 10_000.0
            "m" -> 1_000_000.0
            "b" -> 1_000_000_000.0
            else -> 1.0
        }
        return (base * multiplier).roundToLong().takeIf { it >= 0L }
    }

    /**
     * Normalizes a site rating string (e.g. `92%`, `4.6 / 5`, `4.5★`) to a 0..5 star float.
     */
    fun parseRatingStars(raw: String?): Float? {
        val text = HtmlUrlCleaner.cleanText(raw.orEmpty())
        if (text.isEmpty()) return null
        val match = ratingRegex.matchEntire(text) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        val unit = match.groupValues[2]
        val stars = if (unit == "%") value / 20f else value
        return stars.takeIf { it in 0f..5f }
    }

    private val fourKRegex = Regex("\\b(4k|uhd)\\b", RegexOption.IGNORE_CASE)
    private val twoKRegex = Regex("\\b(2k|qhd)\\b", RegexOption.IGNORE_CASE)
    private val resolutionPairRegex = Regex("(\\d{3,4})\\s*[x×]\\s*(\\d{3,4})", RegexOption.IGNORE_CASE)
    /** `1080p` style tokens win over unrelated 4-digit ids such as `9100-1080p.mp4`. */
    private val explicitHeightRegex = Regex("(\\d{3,4})\\s*p", RegexOption.IGNORE_CASE)

    /**
     * Vertical height parsed strictly from explicit filename tokens (`4K`, `2K`, `1080p`,
     * `1920x1080`). Rejects bare numeric video IDs or query parameters so files like
     * `9001.mp4?expires=1760` never collide with a bogus height.
     */
    fun parseStreamHeightFromFilename(rawFilename: String): Int {
        val clean = rawFilename.substringBefore('?').substringBefore('#').trim()
        if (clean.isEmpty()) return 0
        if (fourKRegex.containsMatchIn(clean)) return 2160
        if (twoKRegex.containsMatchIn(clean)) return 1440
        explicitHeightRegex.find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 144..4320 }?.let { return it }
        resolutionPairRegex.find(clean)?.groupValues?.let { groups ->
            val width = groups.getOrNull(1)?.toIntOrNull() ?: 0
            val height = groups.getOrNull(2)?.toIntOrNull() ?: 0
            if (height in 144..4320) return height
            if (width in 144..4320) return width
        }
        return 0
    }

    /**
     * Vertical height for sorting/labels. Accepts `1080p`, `1080`, `1920x1080`, and the `2K`/`4K`
     * aliases Hanime1 uses, so alias labels sort and match like their numeric height.
     */
    fun parseStreamHeight(label: String): Int {
        val text = label.trim()
        if (text.isEmpty()) return 0
        if (fourKRegex.containsMatchIn(text)) return 2160
        if (twoKRegex.containsMatchIn(text)) return 1440
        explicitHeightRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 144..4320 }?.let { return it }
        resolutionPairRegex.find(text)?.groupValues?.let { groups ->
            val width = groups.getOrNull(1)?.toIntOrNull() ?: 0
            val height = groups.getOrNull(2)?.toIntOrNull() ?: 0
            if (height in 144..4320) return height
            if (width in 144..4320) return width
        }
        return heightRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 144..4320 } ?: 0
    }

    fun validateFilters(filters: AdvancedSearchFilters, siteId: SiteId) {
        val year = filters.year
        if (year != null && year !in 1900..2200) {
            throw SiteException.Unsupported("年份应为 1900–2200", siteId = siteId)
        }
        val month = filters.month
        if (month != null && month !in 1..12) {
            throw SiteException.Unsupported("月份应为 1–12", siteId = siteId)
        }
        val minDur = filters.minDurationMinutes ?: 0
        val maxDur = filters.maxDurationMinutes ?: 0
        if (minDur < 0 || maxDur < 0 || (maxDur > 0 && minDur > maxDur)) {
            throw SiteException.Unsupported("时长范围无效", siteId = siteId)
        }
        val minRating = filters.minRating ?: 0
        if (minRating !in 0..5) {
            throw SiteException.Unsupported("最低评分应为 0–5 星", siteId = siteId)
        }
        if ((filters.brand?.length ?: 0) > 200 || (filters.category?.length ?: 0) > 200) {
            throw SiteException.Unsupported("筛选参数过长", siteId = siteId)
        }
    }

    fun applyLocalFilters(
        items: List<MediaItem>,
        filters: AdvancedSearchFilters,
        filterDuration: Boolean = true,
        filterRating: Boolean = true,
        filterExcludeTags: Boolean = false,
    ): List<MediaItem> {
        val minSec = if (filterDuration) (filters.minDurationMinutes ?: 0) * 60 else 0
        val maxSec = if (filterDuration) (filters.maxDurationMinutes ?: 0) * 60 else 0
        val minRating = if (filterRating) (filters.minRating ?: 0) else 0
        val excluded = if (filterExcludeTags) {
            filters.excludeTags.map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        if (minSec <= 0 && maxSec <= 0 && minRating <= 0 && excluded.isEmpty()) return items
        return items.filter { item ->
            val dur = item.durationSeconds ?: 0
            val durOk = (minSec <= 0 || dur >= minSec) && (maxSec <= 0 || dur in 1..maxSec)
            val ratingOk = minRating <= 0 || (item.rating ?: 0f) >= minRating.toFloat()
            val excludeOk = excluded.isEmpty() || ContentFilter.isAllowed(item, excluded)
            durOk && ratingOk && excludeOk
        }
    }

    fun buildHomeFeed(
        siteId: SiteId,
        primaryTitle: String,
        primaryPage: FeedPage,
        primarySeeAll: FeedRequest,
        rankedTitle: String? = null,
        rankedPage: FeedPage? = null,
        rankedSeeAll: FeedRequest? = null,
    ): HomeFeed {
        val sections = buildList {
            if (rankedPage != null && rankedPage.items.isNotEmpty() && rankedTitle != null) {
                add(
                    FeedSection(
                        id = "top-ranked",
                        title = rankedTitle,
                        items = rankedPage.items.take(10),
                        seeAllRequest = rankedSeeAll,
                        isRanked = true,
                    ),
                )
            }
            if (primaryPage.items.isNotEmpty()) {
                add(
                    FeedSection(
                        id = "latest",
                        title = primaryTitle,
                        items = primaryPage.items,
                        seeAllRequest = primarySeeAll,
                        isRanked = false,
                    ),
                )
            }
        }
        val heroPool = (rankedPage?.items.orEmpty() + primaryPage.items).distinctBy { it.id }
        return HomeFeed(
            siteId = siteId,
            heroItems = heroPool.take(5),
            sections = sections,
            primaryFeedPage = primaryPage,
            fromCache = primaryPage.fromCache,
        )
    }

    fun hostOrigin(url: String, fallbackBase: String): String {
        val uri = UrlSanitizer.parseUriOrNull(url) ?: return fallbackBase.trimEnd('/')
        val host = uri.host ?: return fallbackBase.trimEnd('/')
        val scheme = uri.scheme?.lowercase() ?: "https"
        val port = if (uri.port > 0 && uri.port != 443 && uri.port != 80) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }
}
