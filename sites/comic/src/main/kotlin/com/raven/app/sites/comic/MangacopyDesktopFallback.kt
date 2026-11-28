package com.raven.app.sites.comic

import com.raven.app.core.sitespi.HttpClientAdapter
import kotlinx.serialization.json.Json
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteDescriptor
import kotlinx.serialization.json.JsonArray
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.sitespi.SiteRequestKind
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * 拷贝漫画桌面站点兜底：多节点章节页抓取、AES-CBC 图页解密与 URL 路径解析。
 *
 * 从 `MangacopySitePlugin` 拆出，仅依赖注入的 HTTP 客户端与 JSON 解析器。
 */

internal class MangacopyDesktopFallback(
    private val httpClient: HttpClientAdapter,
    private val json: Json,
) {
    internal suspend fun loadChapterImagesFromDesktopHtml(pathWord: String, uuid: String): List<String> {
        val html = fetchDesktopHtml("/comic/$pathWord/chapter/$uuid")
        val key = MangacopySitePlugin.KEY_REGEX.find(html)?.groupValues?.get(1)
            ?: throw SiteException.ParseError("拷贝漫画章节密钥缺失", siteId = SiteId.MANGACOPY)
        val contentKey = MangacopySitePlugin.CONTENT_KEY_REGEX.find(html)?.groupValues?.get(1)
            ?: throw SiteException.ParseError("拷贝漫画章节内容缺失", siteId = SiteId.MANGACOPY)
        if (contentKey.length <= 16) return emptyList()
        val plain = runCatching {
            val cipherText = hexToBytes(contentKey.drop(16))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(contentKey.take(16).toByteArray(Charsets.UTF_8)),
            )
            cipher.doFinal(cipherText)
        }.getOrNull() ?: throw SiteException.ParseError("拷贝漫画章节解密失败", siteId = SiteId.MANGACOPY)
        val array = runCatching {
            json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonArray
        }.getOrNull() ?: throw SiteException.ParseError("拷贝漫画图页数据格式异常", siteId = SiteId.MANGACOPY)
        return array.mapNotNull { element ->
            (element as? JsonObject)?.safeString("url")?.takeIf { it.isNotBlank() }
        }
    }

    internal suspend fun fetchDesktopHtml(path: String): String {
        var lastError: SiteException? = null
        for (host in MangacopySitePlugin.WEB_HOSTS) {
            val url = "https://$host$path"
            try {
                val response = httpClient.getText(
                    url = url,
                    siteId = SiteId.MANGACOPY,
                    kind = SiteRequestKind.PAGE,
                    headers = mapOf(
                        "User-Agent" to SiteNetworkProfile.DESKTOP_WINDOWS_UA,
                        "Referer" to "https://$host/",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    ),
                )
                if (response.isSuccessful) return response.bodyText()
                lastError = SiteException.Network(
                    message = "拷贝漫画章节页请求失败：HTTP ${response.code}",
                    siteId = SiteId.MANGACOPY,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteException) {
                lastError = e
            } catch (e: Exception) {
                lastError = SiteException.Network(
                    message = "拷贝漫画网络请求失败：${e.message ?: "未知错误"}",
                    siteId = SiteId.MANGACOPY,
                    cause = e,
                )
            }
        }
        throw lastError ?: SiteException.Network("拷贝漫画章节页不可用", siteId = SiteId.MANGACOPY)
    }

    internal fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Invalid hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    internal fun extractPathWord(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val comicIdx = segments.indexOf("comic")
        if (comicIdx >= 0 && comicIdx + 1 < segments.size) {
            return segments[comicIdx + 1].takeIf { it.isNotBlank() }
        }
        return null
    }

    internal fun parseChapterUuid(url: String): String? {
        val parsed = ComicUrl.parse(url) ?: return null
        val segments = parsed.pathSegments
        val idx = segments.indexOf("chapter")
        return segments.getOrNull(idx + 1)?.takeIf { idx >= 0 && it.isNotBlank() }
    }

    internal fun preferHigherPriorityError(current: Throwable?, incoming: Throwable): Throwable {
        if (current == null) return incoming
        fun rank(t: Throwable): Int = when (t) {
            is SiteException.Forbidden -> if (t.isChallenge) 6 else 5
            is SiteException.RateLimited -> 5
            is SiteException.AuthRequired -> 4
            is SiteException.ParseError -> 3
            is SiteException.TlsError -> 2
            is SiteException.Network -> 1
            else -> 0
        }
        return if (rank(incoming) >= rank(current)) incoming else current
    }
}

internal fun mangacopyParseSummaryList(array: JsonArray?, descriptor: SiteDescriptor): List<MediaItem> =
        array.orEmpty().mapNotNull { el ->
            (el as? JsonObject)?.let { CopyHotApiSupport.parseComicSummary(it, SiteId.MANGACOPY, descriptor.baseUrl) }
        }

internal fun mangacopyCopyCandidates(dynamicHosts: Collection<String>): List<String> = buildList {
        dynamicHosts.forEach { if (it !in this) add(it) }
        CopyHotApiSupport.COPY_API_HOSTS.forEach { if (it !in this) add(it) }
    }

    /**
     * Hosts allowed to receive account credentials (login password, logout, bearer token).
     * Static allow-list only — runtime `network2` discoveries are transport candidates, not
     * credential destinations.
     */
internal fun mangacopyCredentialHosts(): List<String> = CopyHotApiSupport.COPY_API_HOSTS

    /** True when [host] is one of the pinned official CopyManga API hosts. */
internal fun mangacopyIsCredentialHost(host: String): Boolean {
        val normalized = host.trim().lowercase()
        return CopyHotApiSupport.COPY_API_HOSTS.any { it.equals(normalized, ignoreCase = true) }
    }
