package com.raven.app.core.common

import java.net.URI
import java.util.Locale

/**
 * Pure-JVM URL validation, host matching, and incoming share-text extraction utilities.
 */
object UrlSanitizer {
    const val MAX_URL_LENGTH: Int = 4096
    const val MAX_SHARE_TEXT_LENGTH: Int = 16_384

    private val urlInTextRegex = Regex(
        "https?://[^\\s<>\"'（）【】《》「」『』，。！？；：、]+",
        RegexOption.IGNORE_CASE,
    )
    private val trailingPunctuation = charArrayOf('.', ',', '。', ')', '）', ']', '】', '>', '!', '！', ';', '；')

    fun parseUriOrNull(raw: String): URI? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        return runCatching { URI(trimmed) }.getOrNull()
    }

    fun isHttpsUrl(raw: String): Boolean = isSafeHttpUrl(raw, allowHttp = false)

    fun isSafeHttpUrl(raw: String, allowHttp: Boolean = false): Boolean {
        val uri = parseUriOrNull(raw) ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val schemeAllowed = scheme == "https" || (allowHttp && scheme == "http")
        if (!schemeAllowed) return false
        if (uri.host.isNullOrBlank()) return false
        if (!uri.rawUserInfo.isNullOrEmpty()) return false
        return true
    }

    /**
     * Normalizes an HTTP/HTTPS URL to HTTPS, rejecting credentials and oversized URLs.
     * Optionally strips the URI fragment unless [preserveFragment] is true (e.g., JMComic `#chapters`).
     */
    fun normalizeHttpsUrl(raw: String, preserveFragment: Boolean = false): String? {
        val uri = parseUriOrNull(raw) ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        if (!uri.rawUserInfo.isNullOrEmpty()) return null
        val portPart = if (uri.port > 0 && uri.port != 443 && uri.port != 80) ":${uri.port}" else ""
        val pathPart = uri.rawPath.orEmpty().ifEmpty { "/" }
        val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragmentPart = if (preserveFragment) uri.rawFragment?.let { "#$it" }.orEmpty() else ""
        val result = "https://$host$portPart$pathPart$queryPart$fragmentPart"
        return result.takeIf { it.length <= MAX_URL_LENGTH }
    }

    fun extractHost(raw: String): String? = parseUriOrNull(raw)?.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }

    /**
     * Strict domain check: matches exact [domain] or subdomain `*.domain`, rejecting lookalike prefixes.
     */
    fun hostMatchesDomain(host: String, domain: String): Boolean {
        val normalizedHost = host.trim().lowercase(Locale.ROOT).trimEnd('.')
        val normalizedDomain = domain.trim().lowercase(Locale.ROOT).trimEnd('.')
        if (normalizedHost.isEmpty() || normalizedDomain.isEmpty()) return false
        return normalizedHost == normalizedDomain || normalizedHost.endsWith(".$normalizedDomain")
    }

    /**
     * Checks whether [host] belongs to [primaryHost] or any of [mirrors] (including `www.` variants).
     */
    fun hostMatchesSite(host: String, primaryHost: String, mirrors: Collection<String> = emptyList()): Boolean {
        val candidate = host.trim().lowercase(Locale.ROOT).trimEnd('.')
        if (candidate.isEmpty()) return false
        val knownHosts = buildList {
            if (primaryHost.isNotBlank()) add(primaryHost.trim().lowercase(Locale.ROOT).trimEnd('.'))
            mirrors.forEach { m ->
                val n = m.trim().lowercase(Locale.ROOT).trimEnd('.')
                if (n.isNotBlank()) add(n)
            }
        }
        return knownHosts.any { known ->
            val bare = known.removePrefix("www.")
            candidate == known || candidate == bare || candidate == "www.$bare"
        }
    }

    /**
     * Resolves a possibly relative or protocol-relative URL against [baseUrl].
     * Rejects `data:`, `javascript:`, and blank inputs.
     */
    fun resolveRelative(baseUrl: String, raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val lower = value.lowercase(Locale.ROOT)
        if (lower.startsWith("data:") || lower.startsWith("javascript:") || lower.startsWith("blob:")) return null
        if (lower.startsWith("//")) return "https:$value".takeIf { isHttpsUrl(it) }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return value.takeIf { isSafeHttpUrl(it, allowHttp = true) }
        }
        return runCatching {
            URI(baseUrl).resolve(value).toString()
        }.getOrNull()?.takeIf { isSafeHttpUrl(it, allowHttp = true) }
    }

    /**
     * Extracts candidate HTTP/HTTPS URLs from shared or pasted text.
     */
    fun extractUrlsFromText(text: String, maxInputLength: Int = MAX_SHARE_TEXT_LENGTH): List<String> {
        require(text.length <= maxInputLength) { "Shared text exceeds maximum allowed length ($maxInputLength)" }
        return urlInTextRegex.findAll(text)
            .map { match -> match.value.trimEnd(*trailingPunctuation) }
            .filter { isSafeHttpUrl(it, allowHttp = true) }
            .distinct()
            .toList()
    }
}
