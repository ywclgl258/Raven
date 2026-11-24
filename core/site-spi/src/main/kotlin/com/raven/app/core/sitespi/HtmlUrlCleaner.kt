package com.raven.app.core.sitespi

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Shared HTML text cleanup & URL encoding/decoding utilities.
 * Eliminates cross-adapter coupling where video sites previously imported `MissavProtocol.encode/clean`.
 */
object HtmlUrlCleaner {
    private val whitespaceRegex = Regex("\\s+")
    private val duplicateImageExtRegex = Regex("""(\.(?:webp|jpg|jpeg|png|gif|avif))\1+$""", RegexOption.IGNORE_CASE)
    private val numericEntityRegex = Regex("&#(\\d+);")
    private val hexEntityRegex = Regex("&#x([0-9a-fA-F]+);")

    /**
     * Removes zero-width spaces / BOM, converts NBSP to regular space, collapses whitespace, and trims.
     */
    fun cleanText(raw: String): String = raw
        .replace("\u200b", "")
        .replace("\ufeff", "")
        .replace('\u00a0', ' ')
        .replace(whitespaceRegex, " ")
        .trim()

    /**
     * Decodes standard named, decimal, and hex HTML entities in pure Kotlin (zero Jsoup dependency).
     */
    fun decodeBasicHtmlEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        var text = raw
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

        text = numericEntityRegex.replace(text) { m ->
            val cp = m.groupValues[1].toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        text = hexEntityRegex.replace(text) { m ->
            val cp = m.groupValues[1].toIntOrNull(16)
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        return text
    }

    /**
     * Percent-encodes a URL path segment (using `%20` for spaces, matching browser URL path encoding).
     */
    fun encodePathSegment(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    /**
     * Encodes a form/query parameter using standard UTF-8 `application/x-www-form-urlencoded`.
     */
    fun encodeQueryParam(raw: String): String =
        URLEncoder.encode(raw, "UTF-8")

    fun decodeUrlComponent(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    /**
     * Fixes duplicate image extensions occasionally emitted by APIs (e.g., `1.webp.webp` -> `1.webp`).
     */
    fun deduplicateImageExtension(url: String): String {
        val queryIdx = url.indexOf('?')
        val path = if (queryIdx >= 0) url.substring(0, queryIdx) else url
        val suffix = if (queryIdx >= 0) url.substring(queryIdx) else ""
        return duplicateImageExtRegex.replace(path, "$1") + suffix
    }

    /**
     * Strips any `#fragment` from a URL (for Referer header hygiene such as Hitomi `/reader/<id>.html#<page>`).
     */
    fun stripFragment(url: String): String = url.substringBefore('#')
}
