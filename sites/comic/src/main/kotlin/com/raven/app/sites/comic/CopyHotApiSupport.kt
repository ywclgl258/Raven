package com.raven.app.sites.comic

import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.HtmlUrlCleaner
import com.raven.app.core.sitespi.SiteSessionStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Shared protocol helpers, header builders, and JSON parsers for CopyManga (`mangacopy`)
 * and HotManga (`relamanhua`), aligned with official CopyManga 3.1.1 and Kira/Copy20 references.
 */
internal object CopyHotApiSupport {

    const val DEFAULT_COPY_APP_VERSION: String = "3.1.1"
    const val DEFAULT_COPY_VERSION_CODE: String = "85"
    const val COPY_OFFICIAL_CERT_MD5: String = "3af08590311032efe0660550a0563a53"
    const val HOTMANGA_WEB_VERSION: String = "2024.04.28"

    val COPY_API_HOSTS: List<String> = listOf(
        "api.copy202601.com",
        "api.copy202602.com",
        "api.copy4000.com",
        "api.copy5000.com",
        "api.copy3000.com",
        "api.2026copy.com",
        "api.mangacopy.com",
        "mapi.mangacopy.com",
    )

    val HOTMANGA_API_HOSTS: List<String> = listOf(
        "mapi.hotmangasg.com",
        "mapi.hotmangasd.com",
        "mapi.hotmangasf.com",
        "api.manga2026.xyz",
        "mapi.elfgjfghkk.club",
        "mapi.fgjfghkkcenter.club",
        "mapi.fgjfghkk.club",
    )

    val COPY_UM_STRING: String = md5Hex(COPY_OFFICIAL_CERT_MD5)

    val COVER_SIZE_REGEX = Regex("""\.\d+x\d+\.\w+$""")

    private const val DEVICE_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val SECRET_KEY_DEVICE = "copy_device_id"
    private const val SECRET_KEY_PSEUDO = "copy_pseudo_id"

    private val processIdentityLock = Any()
    private var processIdentity: Pair<String, String>? = null

    private val IP_BLOCKED_KEYWORDS = listOf(
        "請到官網更新最新APP",
        "請到官网更新最新APP",
        "下載過破解版本",
        "下载过破解版本",
        "限制會自動解除",
        "限制会自动解除",
        "copy4000.com/download",
    )

    val THEMES: List<Pair<String, String>> = listOf(
        "aiqing" to "爱情",
        "huanlexiang" to "欢乐向",
        "maoxian" to "冒险",
        "qihuan" to "奇幻",
        "baihe" to "百合",
        "xiaoyuan" to "校园",
        "kehuan" to "科幻",
        "dongfang" to "东方",
        "danmei" to "耽美",
        "gedou" to "格斗",
        "teenslove" to "TL",
        "shenghuo" to "生活",
        "qingxiaoshuo" to "轻小说",
        "qita" to "其他",
        "xuanyi" to "悬疑",
        "mengxi" to "萌系",
        "shengui" to "神鬼",
        "zhichang" to "职场",
        "zhiyu" to "治愈",
        "jiecao" to "节操",
    )

    data class ComicMetadata(
        val authors: List<AuthorRef>,
        val tags: List<TagRef>,
        val description: String,
        val statusLabel: String?,
        val popular: Long?,
        val updatedAt: String?,
    )

    data class ChapterEntry(
        val uuid: String,
        val name: String,
        val groupPathWord: String = "default",
        val groupName: String = "",
    ) {
        val displayTitle: String
            get() = if (groupPathWord == "default" || groupName.isBlank()) {
                name
            } else {
                "[$groupName] $name"
            }
    }

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun utc8Date(epochMillis: Long): String {
        val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }
        return fmt.format(epochMillis)
    }

    fun stableDeviceIdentity(sessionStore: SiteSessionStore?, siteId: SiteId): Pair<String, String> {
        if (sessionStore != null) {
            val savedDevice = sessionStore.getSecret(siteId, SECRET_KEY_DEVICE)?.takeIf { it.isNotBlank() }
            val savedPseudo = sessionStore.getSecret(siteId, SECRET_KEY_PSEUDO)?.takeIf { it.isNotBlank() }
            if (savedDevice != null && savedPseudo != null) {
                return savedDevice to savedPseudo
            }
            synchronized(processIdentityLock) {
                val recheckDevice = sessionStore.getSecret(siteId, SECRET_KEY_DEVICE)?.takeIf { it.isNotBlank() }
                val recheckPseudo = sessionStore.getSecret(siteId, SECRET_KEY_PSEUDO)?.takeIf { it.isNotBlank() }
                if (recheckDevice != null && recheckPseudo != null) {
                    return recheckDevice to recheckPseudo
                }
                val generated = processIdentity ?: (randomToken(16) to randomToken(16)).also {
                    processIdentity = it
                }
                sessionStore.saveSecret(siteId, SECRET_KEY_DEVICE, generated.first)
                sessionStore.saveSecret(siteId, SECRET_KEY_PSEUDO, generated.second)
                return generated
            }
        }
        return synchronized(processIdentityLock) {
            processIdentity ?: (randomToken(16) to randomToken(16)).also { processIdentity = it }
        }
    }

    private fun randomToken(length: Int): String {
        val rng = java.security.SecureRandom()
        return buildString(length) {
            repeat(length) {
                append(DEVICE_ALPHABET[rng.nextInt(DEVICE_ALPHABET.length)])
            }
        }
    }

    fun buildCopyAppHeaders(
        version: String = DEFAULT_COPY_APP_VERSION,
        versionCode: String = DEFAULT_COPY_VERSION_CODE,
        device: String,
        pseudoId: String,
        token: String? = null,
        epochMillis: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val tsSeconds = (epochMillis / 1_000L).coerceAtLeast(0L).toString()
        val sig = hmacSha256Hex(COPY_OFFICIAL_CERT_MD5, tsSeconds)
        return buildMap {
            put("User-Agent", "COPY/$version")
            put("source", "copyApp")
            put("version", version)
            put("versioncode", versionCode)
            put("platform", "3")
            put("region", "1")
            put("webp", "1")
            put("device", device)
            put("deviceinfo", "Android-generic")
            put("pseudoid", pseudoId)
            put("umstring", COPY_UM_STRING)
            put("Referer", "com.copymanga.app-$version")
            put("Accept", "application/json")
            put("Accept-Language", "")
            put("dt", utc8Date(epochMillis))
            put("X-Auth-Timestamp", tsSeconds)
            put("X-Auth-Signature", sig)
            if (!token.isNullOrBlank()) {
                put("Authorization", "Token $token")
            }
        }
    }

    fun buildHotmangaHeaders(token: String? = null): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("platform", "3")
        put("User-Agent", SiteNetworkProfile.DEFAULT_MOBILE_UA)
        put("webp", "1")
        put("version", HOTMANGA_WEB_VERSION)
        put("X-Requested-With", "com.manga2020.app")
        if (!token.isNullOrBlank()) {
            put("Authorization", "Token $token")
        }
    }

    fun encodeLoginPassword(password: String, salt: Int): String =
        Base64.encode("$password-$salt".toByteArray(Charsets.UTF_8))

    fun stripCoverSize(cover: String): String =
        cover.trim().replace(COVER_SIZE_REGEX, "")

    fun isIpOrClientBlocked210(code: Int, message: String): Boolean {
        if (code == 210) return true
        return IP_BLOCKED_KEYWORDS.any { message.contains(it, ignoreCase = true) }
    }

    fun parseComicSummary(
        rawObj: JsonObject,
        siteId: SiteId,
        baseUrl: String,
        comicWebPathPrefix: String = "/comic",
    ): MediaItem? {
        val obj = rawObj.safeObject("comic") ?: rawObj
        val name = obj.safeString("name").takeIf { it.isNotBlank() } ?: return null
        val pathWord = obj.safeString("path_word").takeIf { it.isNotBlank() } ?: return null
        val authors = obj.safeArray("author").orEmpty().mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            val aName = a.safeString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val aId = a.safeString("path_word").ifBlank { aName }
            AuthorRef(id = aId, name = aName)
        }
        val tags = obj.safeArray("theme").orEmpty().mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val tName = t.safeString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val tSlug = t.safeString("path_word").ifBlank { tName }
            TagRef(slug = tSlug, label = tName)
        }
        val popular = obj.safeLong("popular")?.takeIf { it > 0 }
        val updated = obj.safeString("datetime_updated").takeIf { it.isNotBlank() }
        return MediaItem(
            siteId = siteId,
            id = pathWord,
            url = "$baseUrl$comicWebPathPrefix/$pathWord",
            kind = MediaKind.SERIAL_COMIC,
            title = HtmlUrlCleaner.cleanText(name),
            coverUrl = stripCoverSize(obj.safeString("cover")),
            views = popular,
            dateLabel = updated,
            author = authors.firstOrNull(),
            tags = tags,
        )
    }

    fun parseComicMetadata(comic: JsonObject): ComicMetadata {
        val authors = comic.safeArray("author").orEmpty().mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            val aName = a.safeString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val aId = a.safeString("path_word").ifBlank { aName }
            AuthorRef(id = aId, name = HtmlUrlCleaner.cleanText(aName))
        }
        val tags = buildList {
            for (field in listOf("theme", "females", "males")) {
                comic.safeArray(field).orEmpty().forEach { el ->
                    val t = el as? JsonObject ?: return@forEach
                    val label = t.safeString("name").takeIf { it.isNotBlank() } ?: return@forEach
                    val slug = t.safeString("path_word").ifBlank { label }
                    add(TagRef(slug = slug, label = HtmlUrlCleaner.cleanText(label)))
                }
            }
        }.distinctBy { it.slug }

        val statusLabel = comic.safeObject("status")?.safeString("display")?.takeIf { it.isNotBlank() }
        val regionLabel = comic.safeObject("region")?.safeString("display")?.takeIf { it.isNotBlank() }
        val restrictLabel = comic.safeObject("restrict")?.safeString("display")?.takeIf { it.isNotBlank() }
        val alias = comic.safeString("alias").takeIf { it.isNotBlank() }
        val brief = HtmlUrlCleaner.cleanText(comic.safeString("brief"))
        val metaLine = listOfNotNull(regionLabel, statusLabel, restrictLabel).joinToString(" · ")
        val description = buildString {
            if (metaLine.isNotEmpty()) append(metaLine)
            if (!alias.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append("别名：").append(HtmlUrlCleaner.cleanText(alias))
            }
            if (brief.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(brief)
            }
        }
        return ComicMetadata(
            authors = authors,
            tags = tags,
            description = description,
            statusLabel = statusLabel,
            popular = comic.safeLong("popular")?.takeIf { it > 0 },
            updatedAt = comic.safeString("datetime_updated").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Extracts chapter image URLs and restores real page order when the API provides
     * a valid `words` index permutation (used by CopyManga `/chapter2/`).
     */
    fun orderChapterImages(chapterObj: JsonObject): List<String> {
        val rawUrls = (chapterObj["contents"] as? JsonArray).orEmpty().mapNotNull { el ->
            (el as? JsonObject)?.safeString("url")?.takeIf { it.isNotBlank() }
        }
        if (rawUrls.isEmpty()) return emptyList()
        val wordsArr = chapterObj["words"] as? JsonArray ?: return rawUrls
        if (wordsArr.size != rawUrls.size) return rawUrls
        val indices = wordsArr.mapNotNull { it.safePrimitiveContent()?.toIntOrNull() }
        if (indices.size != rawUrls.size) return rawUrls
        if (indices.toSet().size != rawUrls.size || indices.any { it !in rawUrls.indices }) {
            return rawUrls
        }
        val ordered = MutableList(rawUrls.size) { "" }
        for (i in rawUrls.indices) {
            ordered[indices[i]] = rawUrls[i]
        }
        return ordered.filter { it.isNotBlank() }
    }

    fun parseApiErrorOrNull(
        code: Int,
        message: String,
        siteId: SiteId,
    ): SiteException? {
        if (code == 200) return null
        if (code == 401) {
            return SiteException.AuthRequired("登录已过期，请重新登录：${message.ifBlank { "HTTP 401" }}", siteId = siteId)
        }
        if (isIpOrClientBlocked210(code, message)) {
            return SiteException.Forbidden(
                isChallenge = true,
                message = "上游风控限制 ($code)：${message.ifBlank { "当前网络/客户端暂受限制，请切换节点或稍后重试" }}",
                siteId = siteId,
            )
        }
        return SiteException.ParseError("接口返回 $code：${message.ifBlank { "未知错误" }}", siteId = siteId)
    }
}
