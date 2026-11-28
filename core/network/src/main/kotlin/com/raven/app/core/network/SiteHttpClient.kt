package com.raven.app.core.network

import android.os.SystemClock
import android.util.Log

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.common.log.RavenLog
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteHttpResponse
import com.raven.app.core.sitespi.RequestSessionScope
import com.raven.app.core.sitespi.SiteAuthCancelledException
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import com.raven.app.core.sitespi.SiteSessionStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * Coroutine-cancellable OkHttp [Call.enqueue] bridge shared by feature modules (`RavenUpdater`,
 * `TagDictionarySyncer`, `WebDavSyncClient`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            continuation.resume(response) { _, _, _ ->
                response.close()
            }
        }
    })
}

/**
 * Deterministic mapper from HTTP responses and transport exceptions to Raven's sealed [SiteException].
 */
object SiteHttpErrorMapper {
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

    fun fromResponse(
        response: SiteHttpResponse,
        siteId: SiteId? = null,
        checkChallenge: Boolean = false,
    ): SiteException? {
        if (response.isSuccessful) {
            if (checkChallenge && isChallengeResponse(response)) {
                return SiteException.Forbidden(
                    isChallenge = true,
                    message = "站点要求浏览器验证，请稍后重试或调整网络",
                    siteId = siteId,
                )
            }
            return null
        }
        return when (response.code) {
            401 -> SiteException.AuthRequired(
                message = "需要登录验证 (HTTP 401)",
                siteId = siteId,
            )
            403 -> {
                val challenge = isChallengeResponse(response)
                SiteException.Forbidden(
                    isChallenge = challenge,
                    message = if (challenge) "站点触发浏览器验证挑战 (HTTP 403)" else "访问被拒绝 (HTTP 403)",
                    siteId = siteId,
                )
            }
            404 -> SiteException.NotFound(
                message = "目标资源不存在 (HTTP 404)",
                siteId = siteId,
            )
            429 -> SiteException.RateLimited(
                retryAfterSeconds = response.retryAfterSeconds,
                message = "请求过于频繁，已被限流 (HTTP 429)",
                siteId = siteId,
            )
            509 -> SiteException.QuotaExceeded(
                message = "图片或流量配额已用尽 (HTTP 509)",
                siteId = siteId,
            )
            else -> SiteException.Network(
                message = "请求失败：HTTP ${response.code}",
                siteId = siteId,
            )
        }
    }

    fun fromThrowable(error: Throwable, siteId: SiteId? = null): SiteException {
        if (error is SiteException) return error
        // Interceptor-level cancellation (e.g. an authEpoch guard inside `decorateRequestHeaders`)
        // surfaces as IOException so OkHttp's dispatcher does not cancel the whole call and lose
        // the failure semantics. Unwrap it back into the typed Cancelled exception here.
        if (error is SiteAuthCancelledException) {
            return SiteException.Cancelled(error.message.orEmpty(), siteId = siteId ?: error.siteId, cause = error)
        }
        findSiteExceptionCause(error)?.let { return it }
        if (isTlsFailure(error)) {
            return SiteException.TlsError(
                message = "TLS 安全连接失败：${error.message.orEmpty()}",
                siteId = siteId,
                cause = error,
            )
        }
        return SiteException.Network(
            message = "网络请求失败：${error.message.orEmpty()}",
            siteId = siteId,
            cause = error,
        )
    }

    private fun findSiteExceptionCause(error: Throwable): SiteException? {
        var current: Throwable? = error.cause
        var depth = 0
        while (current != null && current !== error && depth < 8) {
            if (current is SiteException) return current
            current = current.cause
            depth++
        }
        return null
    }

    fun isTlsFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 12) {
            if (current is SSLException || current is CertificateException) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    private fun hasPageContent(body: String): Boolean =
        body.contains("</video", ignoreCase = true) ||
            body.contains("class=\"thumbs", ignoreCase = true) ||
            contentMarkers.any { body.contains(it, ignoreCase = true) }
}

/**
 * Production implementation of [HttpClientAdapter] (`:core:site-spi`).
 *
 * - Runs [HostPacer] in the suspend coroutine layer BEFORE dispatching OkHttp calls (zero `Thread.sleep`).
 * - Verifies `request.authEpoch` against [SiteSessionStore] before and after execution so stale
 *   authenticated requests (e.g. Iwara subscriptions after logout/login) abort cleanly.
 * - Routes through pre-built shared clients in [RavenOkHttpPool] (incl. [MediaRedirectCache] on
 *   `MEDIA` requests and strict host-scoped headers/cookies).
 * - Maps transport & TLS errors to typed [SiteException] (`TlsError`, `Network`, `Cancelled`)
 *   while returning [SiteHttpResponse] for HTTP status inspection by site plugins, and provides
 *   [executeChecked] for full HTTP-status -> [SiteException] throwing semantics.
 */
private const val NET_LOG_TAG: String = "RavenNet"
private const val DEFAULT_RATE_LIMIT_COOLDOWN_MS: Long = 5_000L
private const val DEFAULT_R34V_RATE_LIMIT_COOLDOWN_MS: Long = 30_000L
private const val MAX_RATE_LIMIT_COOLDOWN_MS: Long = 15L * 60L * 1_000L

class SiteHttpClient(
    private val okHttpPool: RavenOkHttpPool,
    private val hostPacer: HostPacer,
    private val sessionStore: SiteSessionStore,
    private val networkMonitor: NetworkMonitor,
    private val rateLimitLog: RateLimitLog,
    private val siteRegistryProvider: () -> SiteRegistry,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
) : HttpClientAdapter {

    constructor(
        okHttpPool: RavenOkHttpPool,
        hostPacer: HostPacer,
        sessionStore: SiteSessionStore,
        networkMonitor: NetworkMonitor,
        rateLimitLog: RateLimitLog,
        siteRegistry: SiteRegistry,
        dispatchers: AppDispatchers = DefaultAppDispatchers(),
    ) : this(
        okHttpPool = okHttpPool,
        hostPacer = hostPacer,
        sessionStore = sessionStore,
        networkMonitor = networkMonitor,
        rateLimitLog = rateLimitLog,
        siteRegistryProvider = { siteRegistry },
        dispatchers = dispatchers,
    )

    override fun currentRouteKey(urls: List<String>): String = networkMonitor.currentRouteKey(urls)

    override suspend fun execute(request: SiteHttpRequest): SiteHttpResponse {
        val logging = Log.isLoggable(NET_LOG_TAG, Log.DEBUG)
        val startedAt = SystemClock.elapsedRealtime()
        if (logging) {
            Log.d(NET_LOG_TAG, "→ ${request.kind} ${request.method} ${request.url}")
        }
        val httpUrl = request.url.toHttpUrlOrNull()
            ?: throw SiteException.Network("无效的请求地址：${request.url}", siteId = request.siteId)

        val registry = siteRegistryProvider()
        val plugin = request.siteId?.let { registry.get(it) } ?: registry.findForMediaHost(httpUrl.host)
        val effectiveSiteId = request.siteId ?: plugin?.id

        verifyAuthEpoch(request.authEpoch, effectiveSiteId)

        // Suspend coroutine rate pacing BEFORE entering OkHttp (ZERO Thread.sleep!).
        hostPacer.paceForRequest(
            host = httpUrl.host,
            encodedPath = httpUrl.encodedPath,
            kind = request.kind,
            descriptor = plugin?.descriptor,
        )

        // Re-check authEpoch in case session changed while waiting in HostPacer.
        verifyAuthEpoch(request.authEpoch, effectiveSiteId)

        val okHttpRequest = buildOkHttpRequest(request, httpUrl.toString(), effectiveSiteId)
        val client = okHttpPool.clientFor(request)

        val maxBytes = maxResponseBytesFor(request.kind)
        val response = withContext(dispatchers.io) {
            try {
                executeCancellable(client, okHttpRequest, request.timeoutMs, maxBytes)
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SiteException) {
                throw se
            } catch (e: Exception) {
                if (logging) {
                    Log.w(NET_LOG_TAG, "✗ ${SystemClock.elapsedRealtime() - startedAt}ms ${request.url} :: ${e.javaClass.simpleName}: ${e.message}")
                }
                RavenLog.w(NET_LOG_TAG, "✗ ${request.kind} ${request.method} ${request.url} :: ${e.javaClass.simpleName}", e)
                throw SiteHttpErrorMapper.fromThrowable(e, effectiveSiteId)
            }
        }
        if (logging) {
            Log.d(NET_LOG_TAG, "← ${response.code} ${SystemClock.elapsedRealtime() - startedAt}ms ${request.url}")
        }

        // Reject stale authenticated response if session epoch mutated in flight.
        verifyAuthEpoch(request.authEpoch, effectiveSiteId)

        if (response.code == 429) {
            val retryLabel = response.retryAfterSeconds?.let { "${it}s" } ?: "default"
            rateLimitLog.record(httpUrl.host, "HTTP 429 (Retry-After=$retryLabel)")
            val cooldownMs = rateLimitCooldownMs(httpUrl.host, response.retryAfterSeconds)
            hostPacer.penalize(httpUrl.host, cooldownMs)
            if (logging) {
                Log.w(NET_LOG_TAG, "Host ${httpUrl.host} cooling down for ${cooldownMs}ms after HTTP 429")
            }
            RavenLog.w(NET_LOG_TAG, "HTTP 429 host=${httpUrl.host} cooldown=${cooldownMs}ms Retry-After=$retryLabel")
        }

        return response
    }

    internal fun rateLimitCooldownMs(host: String, retryAfterSeconds: Long?): Long {
        val defaultMs = if (host.equals("rule34video.com", ignoreCase = true)) {
            DEFAULT_R34V_RATE_LIMIT_COOLDOWN_MS
        } else {
            DEFAULT_RATE_LIMIT_COOLDOWN_MS
        }
        val serverMs = retryAfterSeconds?.coerceAtLeast(0L)?.times(1_000L)
        return (serverMs ?: defaultMs).coerceAtMost(MAX_RATE_LIMIT_COOLDOWN_MS)
    }
    /**
     * Executes [request] and throws a typed [SiteException] (`RateLimited`, `AuthRequired`,
     * `Forbidden`, `NotFound`, `QuotaExceeded`, `Network`) if the HTTP response is non-2xx
     * (or an anti-bot challenge when [checkChallenge] is true).
     */
    suspend fun executeChecked(
        request: SiteHttpRequest,
        checkChallenge: Boolean = false,
    ): SiteHttpResponse {
        val response = execute(request)
        val error = SiteHttpErrorMapper.fromResponse(
            response = response,
            siteId = request.siteId,
            checkChallenge = checkChallenge,
        )
        if (error != null) throw error
        return response
    }

    private fun verifyAuthEpoch(expectedEpoch: Long?, siteId: SiteId?) {
        if (expectedEpoch == null || siteId == null) return
        // Shared accounts (EH/EX) write to ONE bucket, so the guard must compare against the
        // account identity. Comparing EXHENTAI against its own (never-mutated) counter would
        // silently let a post-logout EX request through.
        val accountId = siteId.accountId
        val current = sessionStore.authEpoch(accountId)
        if (current != expectedEpoch) {
            throw SiteException.Cancelled(
                message = "会话状态已变更 (${accountId.value}: expected=$expectedEpoch, actual=$current)",
                siteId = siteId,
            )
        }
    }

    private fun buildOkHttpRequest(
        request: SiteHttpRequest,
        url: String,
        effectiveSiteId: SiteId?,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .tag(SiteRequestTag::class.java, SiteRequestTag(siteId = effectiveSiteId, siteRequest = request))

        for ((name, value) in request.headers) {
            builder.header(name, value)
        }
        request.rangeHeader?.takeIf { it.isNotBlank() }?.let { range ->
            builder.header("Range", range)
        }
        // Account-scoped requests (authEpoch present) must never be answered from the shared
        // OkHttp response cache: that cache is keyed by URL only, so a personalized PAGE/API body
        // fetched for account A would otherwise be replayed to account B or after a logout.
        // Plugin-level `allowCache = false` is honoured as well.
        val hasSiteSession = effectiveSiteId != null &&
            request.allowCookies &&
            request.sessionScope == RequestSessionScope.SITE &&
            sessionStore.hasSession(effectiveSiteId)
        if (!request.allowCache || request.authEpoch != null || hasSiteSession) {
            builder.cacheControl(CacheControl.Builder().noCache().noStore().build())
        }

        when (request.method) {
            SiteHttpMethod.GET -> builder.get()
            SiteHttpMethod.HEAD -> builder.head()
            SiteHttpMethod.POST -> {
                val mediaType = request.contentType?.toMediaTypeOrNull()
                val rawBody = (request.body ?: ByteArray(0)).toRequestBody(mediaType)
                val body = if (!request.allowRetry) SingleUseRequestBody(rawBody) else rawBody
                builder.post(body)
            }
        }
        return builder.build()
    }

    private suspend fun executeCancellable(
        client: okhttp3.OkHttpClient,
        request: Request,
        timeoutMs: Long?,
        maxResponseBytes: Long,
    ): SiteHttpResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        if (timeoutMs != null && timeoutMs > 0L) {
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                try {
                    val result = response.use { res ->
                        val bytes = readBoundedBytes(res.body, maxResponseBytes, continuation)
                        val headersMap = LinkedHashMap<String, MutableList<String>>()
                        val okHeaders = res.headers
                        for (i in 0 until okHeaders.size) {
                            val name = okHeaders.name(i)
                            val value = okHeaders.value(i)
                            headersMap.getOrPut(name) { mutableListOf() }.add(value)
                        }
                        SiteHttpResponse(
                            code = res.code,
                            finalUrl = res.request.url.toString(),
                            headers = headersMap,
                            body = bytes,
                            fromCache = res.networkResponse == null && res.cacheResponse != null,
                        )
                    }
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                } catch (e: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun readBoundedBytes(
        body: ResponseBody?,
        maxBytes: Long,
        continuation: CancellableContinuation<*>,
    ): ByteArray {
        if (body == null) return ByteArray(0)
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            throw IOException("响应体过大 ($contentLength bytes)，超过上限 $maxBytes bytes")
        }
        val initialCapacity = if (contentLength in 1..65_536L) contentLength.toInt() else 8_192
        val out = ByteArrayOutputStream(initialCapacity)
        val buf = ByteArray(8_192)
        var total = 0L
        body.byteStream().use { stream ->
            while (true) {
                if (!continuation.isActive) {
                    throw CancellationException("Request cancelled during body read")
                }
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw IOException("响应体读取超过上限 ($maxBytes bytes)")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_TEXT_RESPONSE_BYTES: Long = 16L * 1024L * 1024L
        private const val MAX_BINARY_RESPONSE_BYTES: Long = 32L * 1024L * 1024L

        internal fun maxResponseBytesFor(kind: SiteRequestKind): Long = when (kind) {
            SiteRequestKind.IMAGE, SiteRequestKind.MEDIA -> MAX_BINARY_RESPONSE_BYTES
            SiteRequestKind.PAGE, SiteRequestKind.API, SiteRequestKind.PROBE -> MAX_TEXT_RESPONSE_BYTES
        }
    }
}
