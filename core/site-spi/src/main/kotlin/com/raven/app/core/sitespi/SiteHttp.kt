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

/**
 * Per-request credential scope.
 *
 * - [SITE]: normal behaviour — the site cookie jar supplies `Cookie`, response `Set-Cookie`
 *   values are merged into the encrypted session store, and [SiteHttpRequest.authEpoch] guards
 *   against stale sessions.
 * - [ISOLATED]: candidate/credential-handshake path — the cookie jar is bypassed entirely for the
 *   outgoing hop (the caller passes an explicit `Cookie` header), and no response cookie is ever
 *   persisted. Used so probing a login candidate can never mutate (or epoch-bump) the live session.
 */
enum class RequestSessionScope {
    SITE,
    ISOLATED,
}

/**
 * Cancellation signalled from inside the OkHttp interceptor chain (e.g. Iwara's per-request
 * `authEpoch` guard in [AuthProvider.decorateRequestHeaders]).
 *
 * It intentionally derives from [java.io.IOException]: OkHttp only handles [java.io.IOException]
 * through its interceptor/route machinery, and a checked [SiteException] thrown from an interceptor
 * escapes as an arbitrary throwable that callers written against `catch (IOException)` (Coil 3,
 * Media3 datasources, download workers) would not catch. `:core:network`'s `SiteHttpErrorMapper`
 * converts this back into `SiteException.Cancelled`, so both layers see the right semantics.
 */
class SiteAuthCancelledException(
    message: String = "会话状态已变更",
    val siteId: SiteId? = null,
) : java.io.IOException(message)

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
    /**
     * Cookie/credential scope for this single request.
     *
     * [RequestSessionScope.ISOLATED] means "never read or write the site's cookie jar and never
     * attach stored credentials": the plugin supplies its own `Cookie` header for a candidate
     * login handshake, and any `Set-Cookie` in the response is discarded instead of being merged
     * into the vault. That keeps a failed/partial login attempt from polluting (and bumping the
     * epoch of) the currently signed-in session.
     */
    val sessionScope: RequestSessionScope = RequestSessionScope.SITE,
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
            timeoutMs == other.timeoutMs &&
            sessionScope == other.sessionScope
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
        result = 31 * result + sessionScope.hashCode()
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
        sessionScope: RequestSessionScope = RequestSessionScope.SITE,
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
                sessionScope = sessionScope,
            ),
        )
    }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        siteId: SiteId? = null,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = false,
        sessionScope: RequestSessionScope = RequestSessionScope.SITE,
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
            sessionScope = sessionScope,
        ),
    )

    /**
     * Candidate-login probe: runs with [RequestSessionScope.ISOLATED] so [cookies] are the ONLY
     * credential the target sees and no response `Set-Cookie` reaches the site jar.
     */
    suspend fun probeWithCookies(
        url: String,
        cookies: Map<String, String>,
        siteId: SiteId? = null,
        kind: SiteRequestKind = SiteRequestKind.PAGE,
        headers: Map<String, String> = emptyMap(),
    ): SiteHttpResponse = execute(
        SiteHttpRequest(
            url = url,
            siteId = siteId,
            method = SiteHttpMethod.GET,
            kind = kind,
            headers = headers + mapOf("Cookie" to formatCookieHeader(cookies)),
            allowCookies = false,
            allowCache = false,
            allowRetry = false,
            sessionScope = RequestSessionScope.ISOLATED,
        ),
    )
}

/** Formats [cookies] as a `Cookie` header value (`a=1; b=2`), skipping blank names/values. */
fun formatCookieHeader(cookies: Map<String, String>): String = buildString {
    for ((name, value) in cookies) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) continue
        if (isNotEmpty()) append("; ")
        append(trimmedName).append('=').append(value.trim())
    }
}
