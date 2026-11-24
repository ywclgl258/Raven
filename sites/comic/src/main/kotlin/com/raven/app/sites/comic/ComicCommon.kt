package com.raven.app.sites.comic

import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.SiteHttpResponse
import java.net.URI

/**
 * Pure-Kotlin RFC-aware URL parser and builder for `:sites:comic`.
 * Avoids any dependency on `:core:network` or OkHttp while safely handling UTF-8 path segments,
 * query parameter mutations, relative URL resolution, and strict HTTPS/host validation.
 */
internal class ComicUrl private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
    val encodedPath: String,
    val queryParams: List<Pair<String, String>>,
    val fragment: String?,
) {
    val isHttps: Boolean get() = scheme == "https"
    val isDefaultHttpsPort: Boolean get() = isHttps && (port == -1 || port == 443)

    val pathSegments: List<String>
        get() = encodedPath
            .trim('/')
            .takeIf { it.isNotEmpty() }
            ?.split('/')
            ?.map { decodePercent(it) }
            .orEmpty()

    val rawQuery: String?
        get() = if (queryParams.isEmpty()) {
            null
        } else {
            queryParams.joinToString("&") { (k, v) ->
                "${HtmlUrlCleaner.encodeQueryParam(k)}=${HtmlUrlCleaner.encodeQueryParam(v)}"
            }
        }

    fun queryParameter(name: String): String? =
        queryParams.firstOrNull { it.first == name }?.second

    fun queryParameters(name: String): List<String> =
        queryParams.filter { it.first == name }.map { it.second }

    fun withHost(newHost: String): ComicUrl = ComicUrl(
        scheme = scheme,
        host = newHost.trim().lowercase(),
        port = port,
        encodedPath = encodedPath,
        queryParams = queryParams,
        fragment = fragment,
    )

    fun withQuery(newQuery: List<Pair<String, String>> = emptyList()): ComicUrl = ComicUrl(
        scheme = scheme,
        host = host,
        port = port,
        encodedPath = encodedPath,
        queryParams = newQuery,
        fragment = fragment,
    )

    fun withFragment(newFragment: String?): ComicUrl = ComicUrl(
        scheme = scheme,
        host = host,
        port = port,
        encodedPath = encodedPath,
        queryParams = queryParams,
        fragment = newFragment,
    )

    fun setQueryParameter(name: String, value: String): ComicUrl {
        val updated = queryParams.filterNot { it.first == name } + (name to value)
        return withQuery(updated)
    }

    fun addQueryParameter(name: String, value: String): ComicUrl =
        withQuery(queryParams + (name to value))

    fun removeQueryParameter(name: String): ComicUrl =
        withQuery(queryParams.filterNot { it.first == name })

    fun addPathSegment(segment: String): ComicUrl {
        val encoded = encodePathSegmentPreservingColon(segment)
        val base = encodedPath.trimEnd('/')
        return ComicUrl(
            scheme = scheme,
            host = host,
            port = port,
            encodedPath = "$base/$encoded",
            queryParams = queryParams,
            fragment = fragment,
        )
    }

    fun addPathSegments(segments: String): ComicUrl {
        var current = this
        for (part in segments.split('/').filter { it.isNotEmpty() }) {
            current = current.addPathSegment(part)
        }
        return current
    }

    fun resolve(raw: String): ComicUrl? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("javascript:") || lower.startsWith("blob:")) {
            return null
        }
        if (lower.startsWith("//")) {
            return parse("$scheme:$trimmed")
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return parse(trimmed)
        }
        val baseUri = runCatching { URI(toString()) }.getOrNull() ?: return null
        val sanitizedRelative = sanitizeForUri(trimmed)
        val resolved = runCatching { baseUri.resolve(sanitizedRelative).toString() }.getOrNull() ?: return null
        return parse(resolved)
    }

    override fun toString(): String {
        val portPart = when {
            port <= 0 -> ""
            scheme == "https" && port == 443 -> ""
            scheme == "http" && port == 80 -> ""
            else -> ":$port"
        }
        val queryPart = rawQuery?.let { "?$it" }.orEmpty()
        val fragmentPart = fragment?.let { "#$it" }.orEmpty()
        return "$scheme://$host$portPart$encodedPath$queryPart$fragmentPart"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ComicUrl) return false
        return toString() == other.toString()
    }

    override fun hashCode(): Int = toString().hashCode()

    companion object {
        private const val MAX_URL_LENGTH = 4096

        fun parse(raw: String): ComicUrl? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
            val sanitized = sanitizeForUri(trimmed)
            val uri = runCatching { URI(sanitized) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (!uri.rawUserInfo.isNullOrEmpty()) return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val encodedPath = uri.rawPath.orEmpty().ifEmpty { "/" }
            val queryParams = parseQuery(uri.rawQuery)
            val fragment = uri.rawFragment?.let { decodePercent(it) }
            return ComicUrl(
                scheme = scheme,
                host = host,
                port = uri.port,
                encodedPath = encodedPath,
                queryParams = queryParams,
                fragment = fragment,
            )
        }

        fun requireParse(raw: String, siteId: SiteId? = null): ComicUrl =
            parse(raw) ?: throw SiteException.ParseError("Invalid URL: $raw", siteId = siteId)

        fun parseStrictHttps(raw: String): ComicUrl? =
            parse(raw)?.takeIf { it.isDefaultHttpsPort }

        fun encodePathSegmentPreservingColon(raw: String): String {
            val sb = StringBuilder(raw.length + 8)
            val bytes = raw.toByteArray(Charsets.UTF_8)
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                val safe = (c in 'a'.code..'z'.code) ||
                    (c in 'A'.code..'Z'.code) ||
                    (c in '0'.code..'9'.code) ||
                    c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code ||
                    c == ':'.code || c == '@'.code || c == '!'.code || c == '$'.code ||
                    c == '&'.code || c == '\''.code || c == '('.code || c == ')'.code ||
                    c == '*'.code || c == '+'.code || c == ','.code || c == ';'.code || c == '='.code
                if (safe) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%')
                    sb.append(HEX[(c ushr 4) and 0x0F])
                    sb.append(HEX[c and 0x0F])
                }
            }
            return sb.toString()
        }

        private const val HEX = "0123456789ABCDEF"

        private fun parseQuery(rawQuery: String?): List<Pair<String, String>> {
            if (rawQuery.isNullOrEmpty()) return emptyList()
            val out = mutableListOf<Pair<String, String>>()
            for (part in rawQuery.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq < 0) {
                    out.add(HtmlUrlCleaner.decodeUrlComponent(part) to "")
                } else {
                    val key = HtmlUrlCleaner.decodeUrlComponent(part.substring(0, eq))
                    val value = HtmlUrlCleaner.decodeUrlComponent(part.substring(eq + 1))
                    out.add(key to value)
                }
            }
            return out
        }

        private fun decodePercent(raw: String): String {
            if (!raw.contains('%')) return raw
            return HtmlUrlCleaner.decodeUrlComponent(raw.replace("+", "%2B"))
        }

        /**
         * Percent-encodes raw whitespace, quotes, brackets, or non-ASCII code points so [URI]
         * can parse real-world scraped links (e.g., CJK WNACG tags or EH quoted queries).
         */
        private fun sanitizeForUri(raw: String): String {
            val schemeIdx = raw.indexOf("://")
            val authorityEnd = if (schemeIdx >= 0) {
                raw.indexOfAny(charArrayOf('/', '?', '#'), startIndex = schemeIdx + 3)
                    .let { if (it < 0) raw.length else it }
            } else {
                0
            }
            val sb = StringBuilder(raw.length + 16)
            for (i in raw.indices) {
                val ch = raw[i]
                val code = ch.code
                val pastAuthority = i >= authorityEnd
                val needsEncode = code <= 0x20 || code >= 0x7F ||
                    ch == '"' || ch == '<' || ch == '>' || ch == '\\' ||
                    ch == '^' || ch == '`' || ch == '{' || ch == '|' || ch == '}' ||
                    (pastAuthority && (ch == '[' || ch == ']'))
                if (needsEncode) {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        val v = b.toInt() and 0xFF
                        sb.append('%').append(HEX[(v ushr 4) and 0x0F]).append(HEX[v and 0x0F])
                    }
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}

internal fun ComicLanguage.languageKeys(): List<String> = when (this) {
    ComicLanguage.ALL -> emptyList()
    ComicLanguage.CHINESE -> listOf("chinese")
    ComicLanguage.JAPANESE -> listOf("japanese")
    ComicLanguage.CHINESE_AND_JAPANESE -> listOf("chinese", "japanese")
}

internal fun SiteHttpResponse.throwIfNotSuccessful(siteId: SiteId, siteName: String) {
    if (isSuccessful) return
    when (code) {
        429 -> throw SiteException.RateLimited(
            retryAfterSeconds = retryAfterSeconds,
            message = "$siteName 请求频率已达限制，请稍后重试",
            siteId = siteId,
        )
        401 -> throw SiteException.AuthRequired(
            message = "$siteName 需要登录验证",
            siteId = siteId,
        )
        403 -> {
            val isChallenge = header("cf-mitigated").equals("challenge", ignoreCase = true) ||
                bodyText().let { it.contains("cf-challenge", ignoreCase = true) || it.contains("Just a moment", ignoreCase = true) }
            throw SiteException.Forbidden(
                isChallenge = isChallenge,
                message = if (isChallenge) "$siteName 触发浏览器验证挑战" else "$siteName 拒绝访问（HTTP 403）",
                siteId = siteId,
            )
        }
        404 -> throw SiteException.NotFound(
            message = "$siteName 资源不存在或已被移除（HTTP 404）",
            siteId = siteId,
        )
        509 -> throw SiteException.QuotaExceeded(
            message = "$siteName 图片配额已用尽（HTTP 509）",
            siteId = siteId,
        )
        else -> throw SiteException.Network(
            message = "$siteName 请求失败：HTTP $code",
            siteId = siteId,
        )
    }
}
