package com.raven.app.sites.comic

import com.raven.app.core.model.SiteDescriptor
import org.jsoup.nodes.Document

/** E-Hentai URL 解析（纯函数，供 `EhSharedEngine` 复用）。 */

internal fun ehExtractNextCursor(doc: Document, baseUrl: String, descriptor: SiteDescriptor): String? {
        doc.selectFirst("a#unext[href]")?.absUrl("href")?.let { candidate ->
            val parsed = ComicUrl.parseStrictHttps(candidate)
            if (parsed != null && descriptor.matchesHost(parsed.host)) {
                return candidate
            }
        }
        return doc.select(".ptt a[href]")
            .lastOrNull()
            ?.takeIf { it.text().contains('>') }
            ?.absUrl("href")
            ?.takeIf { candidate ->
                candidate != baseUrl &&
                    ComicUrl.parseStrictHttps(candidate)?.let { descriptor.matchesHost(it.host) } == true
            }
    }

internal fun ehCanonicalGalleryUrl(raw: String, descriptor: SiteDescriptor): String? {
        val parsed = ComicUrl.parseStrictHttps(raw)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        val match = EhSharedEngine.GALLERY_PATH_REGEX.matchEntire(parsed.encodedPath) ?: return null
        return "https://${descriptor.defaultHost}/g/${match.groupValues[1]}/${match.groupValues[2]}/"
    }

internal fun ehExtractPageIndex(raw: String, descriptor: SiteDescriptor): Int? {
        val parsed = ComicUrl.parseStrictHttps(raw)?.takeIf { descriptor.matchesHost(it.host) } ?: return null
        return EhSharedEngine.IMAGE_PATH_REGEX.matchEntire(parsed.encodedPath)
            ?.groupValues?.get(3)?.toIntOrNull()?.takeIf { it > 0 }
    }

internal fun ehResolveHttps(raw: String, baseUrl: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val resolved = ComicUrl.parse(baseUrl)?.resolve(trimmed) ?: ComicUrl.parse(trimmed) ?: return ""
        return if (resolved.isHttps) resolved.toString() else ""
    }

internal fun ehExtractCssImageUrl(style: String, baseUrl: String): String =
        EhSharedEngine.CSS_URL_REGEX.find(style)?.groupValues?.get(1)?.let { ehResolveHttps(it, baseUrl) }.orEmpty()
