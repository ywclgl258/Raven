package com.raven.app.sites.gallery

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.sitespi.HtmlUrlCleaner
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

/**
 * Pure-JVM URL, lazy-load image, ad/icon filtering, and challenge-detection helpers
 * shared across all photo/gallery plugins without depending on OkHttp or `:core:network`.
 */
internal object GalleryUrlUtils {
    private val whitespaceRegex = Regex("\\s+")
    private val srcsetSplitRegex = Regex(",\\s+(?=(?:https?://|//|/|[^\\s,]+\\.[a-zA-Z]{2,5}))")
    private val srcsetWidthRegex = Regex("(\\d+)w$", RegexOption.IGNORE_CASE)
    private val srcsetDensityRegex = Regex("(\\d+(?:\\.\\d+)?)x$", RegexOption.IGNORE_CASE)
    private val imageExtensionRegex = Regex(
        "\\.(?:jpe?g|png|webp|gif|avif|bmp)(?:$|[?#])",
        RegexOption.IGNORE_CASE,
    )
    private val vectorExtensionRegex = Regex(
        "\\.(?:svg|ico)(?:$|[?#])",
        RegexOption.IGNORE_CASE,
    )
    private val nonContentHosts = setOf(
        "gravatar.com",
        "s.w.org",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "facebook.com",
        "connect.facebook.net",
        "analytics.google.com",
    )
    private val adOrIconPathRegex = Regex(
        "(?:^|/)(?:wp-includes|themes|plugins|emoji|smilies|favicon|ads|advert|banner|banners)/|" +
            "(?:^|[/_.-])(?:site-logo|favicon|spacer|blank|pixel|1x1|doubleclick|adsense)(?:[/_.-]|\\.[a-z0-9]+$)|" +
            "(?:^|/)(?:logo|avatar|banner|icon)(?:[_-](?:author|site|ad|ads|small|thumb|header|footer|\\d+))*\\.[a-z0-9]+$",
        RegexOption.IGNORE_CASE,
    )
    private val chromeAltLabels = setOf(
        "logo",
        "site logo",
        "avatar",
        "banner",
        "ad",
        "ads",
        "advertisement",
        "icon",
    )
    private val lazyImageAttributes = listOf(
        "data-orig-file",
        "data-large-file",
        "data-full-url",
        "data-original",
        "data-lazy-src",
        "data-src",
        "data-url",
    )
    private val srcsetAttributes = listOf(
        "data-lazy-srcset",
        "data-srcset",
        "srcset",
    )
    private val contentMarkers = listOf(
        "entry-title",
        "entry-content",
        "generate-columns",
        "post-thumbnail",
        "wp-content",
        "wp-block-post",
        "rt-holder",
        "post-item",
        "gallery-1",
        "__next_f",
        "<main",
        "<article",
        "id=\"content",
        "class=\"content",
        "</video",
    )

    private const val MIN_MEANINGFUL_DIMENSION_PX = 48

    fun parseUri(raw: String): URI? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > UrlSanitizer.MAX_URL_LENGTH) return null
        val direct = UrlSanitizer.parseUriOrNull(trimmed)
        if (direct != null) return direct
        val sanitized = trimmed.replace(" ", "%20")
        return UrlSanitizer.parseUriOrNull(sanitized)
    }

    fun isSafeHttps(raw: String): Boolean {
        val uri = parseUri(raw) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo.isNullOrEmpty()
    }

    fun isSafeHttpOrHttps(raw: String): Boolean {
        val uri = parseUri(raw) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "https" || scheme == "http") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo.isNullOrEmpty()
    }

    fun queryParameters(uri: URI): Map<String, String> {
        val rawQuery = uri.rawQuery ?: return emptyMap()
        if (rawQuery.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            val key = if (eqIdx >= 0) {
                HtmlUrlCleaner.decodeUrlComponent(pair.substring(0, eqIdx))
            } else {
                HtmlUrlCleaner.decodeUrlComponent(pair)
            }
            val value = if (eqIdx >= 0) {
                HtmlUrlCleaner.decodeUrlComponent(pair.substring(eqIdx + 1))
            } else {
                ""
            }
            if (key.isNotEmpty() && key !in result) {
                result[key] = value
            }
        }
        return result
    }

    fun queryParameter(uri: URI, name: String): String? = queryParameters(uri)[name]

    fun pathSegments(uri: URI): List<String> =
        uri.rawPath.orEmpty()
            .split('/')
            .filter { it.isNotEmpty() }
            .map { HtmlUrlCleaner.decodeUrlComponent(it) }

    fun resolveAbsoluteUrl(raw: String, baseUrl: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("javascript:") || lower.startsWith("blob:") || lower.startsWith("#")) {
            return null
        }
        if (lower.startsWith("//")) {
            return "https:$trimmed".takeIf { isSafeHttps(it) }
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return trimmed.takeIf { isSafeHttpOrHttps(it) }
        }
        return runCatching { URI(baseUrl).resolve(trimmed).toString() }
            .getOrNull()
            ?.takeIf { isSafeHttpOrHttps(it) }
    }

    fun cleanHtmlText(raw: String): String {
        if (raw.isBlank()) return ""
        val unescaped = Parser.unescapeEntities(raw, false)
        return HtmlUrlCleaner.cleanText(unescaped)
    }

    /**
     * Picks the highest-resolution candidate URL from an HTML `srcset` attribute.
     * Supports both width descriptors (`1200w`) and pixel density descriptors (`2x`).
     */
    fun pickBestSrcsetUrl(srcset: String, baseUrl: String): String? {
        val trimmed = srcset.trim()
        if (trimmed.isEmpty()) return null
        val entries = srcsetSplitRegex.split(trimmed)
        var bestUrl: String? = null
        var bestScore = -1.0
        for (entry in entries) {
            val tokens = entry.trim().split(whitespaceRegex).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val resolved = resolveAbsoluteUrl(tokens[0], baseUrl) ?: continue
            val descriptor = tokens.getOrNull(1).orEmpty()
            val score = when {
                srcsetWidthRegex.matches(descriptor) ->
                    srcsetWidthRegex.find(descriptor)!!.groupValues[1].toDoubleOrNull() ?: 1.0
                srcsetDensityRegex.matches(descriptor) ->
                    (srcsetDensityRegex.find(descriptor)!!.groupValues[1].toDoubleOrNull() ?: 1.0) * 1000.0
                else -> 1.0
            }
            if (score > bestScore) {
                bestScore = score
                bestUrl = resolved
            }
        }
        return bestUrl
    }

    /**
     * Extracts the best image URL from an `<img>` element, handling lazy-load attributes
     * (`data-orig-file`, `data-original`, `data-lazy-src`, `data-src`), `srcset`, and `src`.
     * Never follows parent `<a href>` — suitable for card thumbnails and image-element inspection.
     */
    fun extractImgElementUrl(img: Element, baseUrl: String): String? {
        for (attr in lazyImageAttributes) {
            val raw = img.attr(attr).trim()
            if (raw.isNotEmpty()) {
                val resolved = resolveAbsoluteUrl(raw, baseUrl)
                if (resolved != null && !isLikelyAdOrIconUrl(resolved)) return resolved
            }
        }
        for (attr in srcsetAttributes) {
            val raw = img.attr(attr).trim()
            if (raw.isNotEmpty()) {
                val resolved = pickBestSrcsetUrl(raw, baseUrl)
                if (resolved != null && !isLikelyAdOrIconUrl(resolved)) return resolved
            }
        }
        val srcRaw = img.absUrl("src").ifBlank { img.attr("src") }
        val resolvedSrc = resolveAbsoluteUrl(srcRaw, baseUrl)
        return resolvedSrc?.takeUnless { isLikelyAdOrIconUrl(it) }
    }

    /**
     * Extracts candidate high-resolution image URLs from an `<a>` or `<img>` element in priority order:
     * 1. High-resolution `<a href>` (when [preferAnchorHref] is true and href points to an image/CDN)
     * 2. Lazy-load image attributes (`data-orig-file`, `data-original`, `data-lazy-src`, `data-src`)
     * 3. Highest-resolution `srcset` candidate
     * 4. Standard `img[src]`
     */
    fun extractGalleryImageCandidates(
        element: Element,
        baseUrl: String,
        preferAnchorHref: Boolean = true,
        allowedUrlSubstrings: Set<String> = emptySet(),
    ): List<String> {
        val candidates = ArrayList<String>(4)
        val anchor = when {
            element.tagName().equals("a", ignoreCase = true) -> element
            element.parent()?.tagName().equals("a", ignoreCase = true) -> element.parent()
            else -> null
        }
        val img = when {
            element.tagName().equals("img", ignoreCase = true) -> element
            else -> element.selectFirst("img")
        }

        if (preferAnchorHref && anchor != null) {
            val hrefRaw = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            val resolvedHref = resolveAbsoluteUrl(hrefRaw, baseUrl)
            if (resolvedHref != null && isLikelyImageHref(resolvedHref, allowedUrlSubstrings)) {
                candidates += resolvedHref
            }
        }

        if (img != null) {
            val hasChromeAlt = img.attr("alt").trim().lowercase() in chromeAltLabels
            val isTinyIcon = isTinyIconElement(img)
            if (!hasChromeAlt && !isTinyIcon) {
                for (attr in lazyImageAttributes) {
                    val raw = img.attr(attr).trim()
                    if (raw.isNotEmpty()) {
                        resolveAbsoluteUrl(raw, baseUrl)?.let { candidates += it }
                    }
                }
                for (attr in srcsetAttributes) {
                    val raw = img.attr(attr).trim()
                    if (raw.isNotEmpty()) {
                        pickBestSrcsetUrl(raw, baseUrl)?.let { candidates += it }
                    }
                }
                val srcRaw = img.absUrl("src").ifBlank { img.attr("src") }
                resolveAbsoluteUrl(srcRaw, baseUrl)?.let { candidates += it }
            }
        }

        return candidates.distinct()
    }

    fun isLikelyImageHref(url: String, allowedUrlSubstrings: Set<String> = emptySet()): Boolean {
        if (!isSafeHttps(url)) return false
        if (isLikelyAdOrIconUrl(url)) return false
        val path = parseUri(url)?.rawPath.orEmpty()
        if (imageExtensionRegex.containsMatchIn(path)) return true
        if (allowedUrlSubstrings.isNotEmpty() && allowedUrlSubstrings.any { url.contains(it, ignoreCase = true) }) {
            // Avoid matching HTML post links on the main site unless they live under an image upload path
            if (path.endsWith(".html", ignoreCase = true) || path.endsWith("/")) return false
            return true
        }
        return false
    }

    fun isLikelyAdOrIconUrl(url: String): Boolean {
        val uri = parseUri(url) ?: return true
        val host = uri.host?.lowercase() ?: return true
        if (nonContentHosts.any { UrlSanitizer.hostMatchesDomain(host, it) }) return true
        val path = uri.rawPath.orEmpty()
        if (vectorExtensionRegex.containsMatchIn(path)) return true
        if (adOrIconPathRegex.containsMatchIn(path)) return true
        return false
    }

    fun isTinyIconElement(img: Element): Boolean {
        val w = img.attr("width").trim().toIntOrNull() ?: return false
        val h = img.attr("height").trim().toIntOrNull() ?: return false
        return w in 1..MIN_MEANINGFUL_DIMENSION_PX && h in 1..MIN_MEANINGFUL_DIMENSION_PX
    }

    fun extractAspectRatio(img: Element?): Float? {
        if (img == null) return null
        val w = img.attr("width").trim().toIntOrNull() ?: return null
        val h = img.attr("height").trim().toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else null
    }

    /**
     * Detects genuine Cloudflare / DDoS-Guard interstitial challenges while NOT false-positiving
     * on sites like 4KHD that include passive `/cdn-cgi/challenge-platform/scripts/jsd/main.js`
     * alongside real page content.
     */
    fun isChallengeHtml(body: String, cfMitigatedHeader: String? = null): Boolean {
        if (cfMitigatedHeader.equals("challenge", ignoreCase = true)) return true
        if (body.contains("Checking your browser", ignoreCase = true)) return true
        if (body.contains("Just a moment", ignoreCase = true)) return true
        if (body.contains("cf-mitigated", ignoreCase = true)) return true
        if (body.contains("DDoS-Guard", ignoreCase = true) && !body.contains("class=\"thumbs")) return true
        if (body.contains("challenge-platform", ignoreCase = true) && !hasContentMarker(body)) return true
        return false
    }

    private fun hasContentMarker(body: String): Boolean =
        contentMarkers.any { body.contains(it, ignoreCase = true) }
}
