package com.raven.app.core.sitespi

import com.raven.app.core.model.SiteId
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException

/**
 * Coroutine-safe replacement for [runCatching] inside `suspend` blocks that always rethrows
 * [CancellationException] instead of swallowing structured concurrency cancellations.
 */
inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    Result.failure(t)
}

enum class SiteHttpMethod {
    GET,
    POST,
    HEAD,
}

enum class SiteRequestKind {
    PAGE,
    API,
    IMAGE,
    MEDIA,
    PROBE,
}

data class SiteHttpRequest(
    val url: String,
    val siteId: SiteId? = null,
    val method: SiteHttpMethod = SiteHttpMethod.GET,
    val kind: SiteRequestKind = SiteRequestKind.PAGE,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val followRedirects: Boolean = true,
    val allowCookies: Boolean = true,
    val allowCache: Boolean = true,
    val allowRetry: Boolean = true,
    val rangeHeader: String? = null,
    val authEpoch: Long? = null,
    val timeoutMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SiteHttpRequest) return false
        return url == other.url &&
            siteId == other.siteId &&
            method == other.method &&
            kind == other.kind &&
            headers == other.headers &&
            body.contentEquals(other.body) &&
            contentType == other.contentType &&
            followRedirects == other.followRedirects &&
            allowCookies == other.allowCookies &&
            allowCache == other.allowCache &&
            allowRetry == other.allowRetry &&
            rangeHeader == other.rangeHeader &&
            authEpoch == other.authEpoch &&
            timeoutMs == other.timeoutMs
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (siteId?.hashCode() ?: 0)
        result = 31 * result + method.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + followRedirects.hashCode()
        result = 31 * result + allowCookies.hashCode()
        result = 31 * result + allowCache.hashCode()
        result = 31 * result + allowRetry.hashCode()
        result = 31 * result + (rangeHeader?.hashCode() ?: 0)
        result = 31 * result + (authEpoch?.hashCode() ?: 0)
        result = 31 * result + (timeoutMs?.hashCode() ?: 0)
        return result
    }
}

data class SiteHttpResponse(
    val code: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val fromCache: Boolean = false,
) {
    val isSuccessful: Boolean get() = code in 200..299

    fun bodyText(charset: Charset = Charsets.UTF_8): String = String(body, charset)

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    fun headers(name: String): List<String> =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

    val contentRange: String? get() = header("Content-Range")

    val retryAfterSeconds: Long?
        get() {
            val raw = header("Retry-After")?.trim() ?: return null
            raw.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
            return runCatching {
                val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val targetMs = parser.parse(raw)?.time ?: return null
                ((targetMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            }.getOrNull()
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SiteHttpResponse) return false
        return code == other.code &&
            finalUrl == other.finalUrl &&
            headers == other.headers &&
            body.contentEquals(other.body) &&
            fromCache == other.fromCache
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + finalUrl.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + fromCache.hashCode()
        return result
    }
}

/**
 * Transport abstraction injected into site plugins.
 * Implemented by `:core:network`'s `SiteHttpClient` in production and by lightweight fakes in pure-JVM tests.
 */
interface HttpClientAdapter {
    suspend fun execute(request: SiteHttpRequest): SiteHttpResponse

    /**
     * Route identity incorporating active network handle, route epoch, and proxy configuration.
     * Used by [MirrorLeaseRouter] to invalidate cached mirror leases on network/proxy changes.
     */
    fun currentRouteKey(urls: List<String> = emptyList()): String = "default"

    suspend fun getText(
        url: String,
        siteId: SiteId? = null,
        kind: SiteRequestKind = SiteRequestKind.PAGE,
        headers: Map<String, String> = emptyMap(),
        allowCache: Boolean = true,
        authEpoch: Long? = null,
    ): SiteHttpResponse = execute(
        SiteHttpRequest(
            url = url,
            siteId = siteId,
            method = SiteHttpMethod.GET,
            kind = kind,
            headers = headers,
            allowCache = allowCache,
            authEpoch = authEpoch,
        ),
    )

    suspend fun getBytes(
        url: String,
        siteId: SiteId? = null,
        kind: SiteRequestKind = SiteRequestKind.API,
        headers: Map<String, String> = emptyMap(),
        rangeHeader: String? = null,
        allowCache: Boolean = true,
    ): SiteHttpResponse = execute(
        SiteHttpRequest(
            url = url,
            siteId = siteId,
            method = SiteHttpMethod.GET,
            kind = kind,
            headers = headers,
            rangeHeader = rangeHeader,
            allowCache = allowCache,
        ),
    )

    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        siteId: SiteId? = null,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): SiteHttpResponse {
        val encoded = fields.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return execute(
            SiteHttpRequest(
                url = url,
                siteId = siteId,
                method = SiteHttpMethod.POST,
                kind = SiteRequestKind.PAGE,
                headers = headers,
                body = encoded.toByteArray(Charsets.UTF_8),
                contentType = "application/x-www-form-urlencoded; charset=utf-8",
                followRedirects = followRedirects,
                allowCache = false,
                allowRetry = false,
            ),
        )
    }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        siteId: SiteId? = null,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = false,
    ): SiteHttpResponse = execute(
        SiteHttpRequest(
            url = url,
            siteId = siteId,
            method = SiteHttpMethod.POST,
            kind = SiteRequestKind.API,
            headers = headers,
            body = jsonBody.toByteArray(Charsets.UTF_8),
            contentType = "application/json; charset=utf-8",
            followRedirects = followRedirects,
            allowCache = false,
            allowRetry = false,
        ),
    )
}
