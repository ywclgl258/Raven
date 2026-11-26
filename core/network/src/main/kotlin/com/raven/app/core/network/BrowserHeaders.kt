package com.raven.app.core.network

import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink

/**
 * Request tag carried on OkHttp [Request] instances so interceptors always know the exact
 * owning [SiteId] even for rotating external media CDNs (e.g., Everia) or shared CDNs (EH/EX).
 */
data class SiteRequestTag(
    val siteId: SiteId? = null,
    val siteRequest: SiteHttpRequest? = null,
)

/**
 * Tracks the original site host that initiated a request before any application-level headers
 * were attached, so [CrossHostCredentialGuardInterceptor] can strip credentials on cross-host hops.
 */
internal data class CredentialScopeOriginTag(
    val siteId: SiteId?,
    val originHost: String,
)

/**
 * Wraps a [RequestBody] as one-shot so OkHttp never silently replays sensitive login POST bodies
 * (e.g., Iwara `/auth/login`).
 */
internal class SingleUseRequestBody(
    private val delegate: RequestBody,
) : RequestBody() {
    override fun isOneShot(): Boolean = true
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}

internal object BrowserHeaders {
    private val imagePathRegex = Regex(
        "\\.(jpe?g|png|gif|webp|avif|webm)(\\?.*|#.*|$)",
        RegexOption.IGNORE_CASE,
    )

    const val ACCEPT_LANGUAGE: String = "zh-CN,zh;q=0.9,en;q=0.8"
    const val ACCEPT_DOCUMENT: String =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    const val ACCEPT_API: String = "application/json, text/plain, */*"
    const val ACCEPT_IMAGE: String =
        "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

    fun isImagePath(encodedPath: String): Boolean = imagePathRegex.containsMatchIn(encodedPath)

    fun resolveReferer(descriptor: SiteDescriptor, kind: SiteRequestKind): String? {
        val profile = descriptor.networkProfile
        val candidate = when (kind) {
            SiteRequestKind.MEDIA, SiteRequestKind.IMAGE ->
                profile.mediaReferer.ifBlank { profile.referer }
            else ->
                profile.referer
        }
        if (candidate.isNotBlank()) return candidate
        return descriptor.baseUrl.takeIf { it.isNotBlank() }?.let { "$it/" }
    }

    fun applyBrowserShape(
        builder: Request.Builder,
        original: Request,
        descriptor: SiteDescriptor,
        kind: SiteRequestKind,
    ) {
        val host = original.url.host
        val isApiHost = descriptor.networkProfile.apiHosts.any { it.equals(host, ignoreCase = true) }
        val sameOrigin = descriptor.matchesRequestHost(host)

        when {
            kind == SiteRequestKind.MEDIA -> {
                applyMediaShape(builder, sameOrigin)
            }
            kind == SiteRequestKind.API || isApiHost -> {
                if (original.header("Accept") == null) builder.header("Accept", ACCEPT_API)
                setFetchHeadersIfMissing(original, builder, dest = "empty", mode = "cors", site = "same-site")
            }
            kind == SiteRequestKind.IMAGE || isImagePath(original.url.encodedPath) -> {
                if (original.header("Accept") == null) builder.header("Accept", ACCEPT_IMAGE)
                setFetchHeadersIfMissing(
                    original,
                    builder,
                    dest = "image",
                    mode = "no-cors",
                    site = if (sameOrigin) "same-origin" else "cross-site",
                )
            }
            else -> {
                if (original.header("Accept") == null) builder.header("Accept", ACCEPT_DOCUMENT)
                setFetchHeadersIfMissing(original, builder, dest = "document", mode = "navigate", site = "same-origin")
                if (original.header("Upgrade-Insecure-Requests") == null) {
                    builder.header("Upgrade-Insecure-Requests", "1")
                }
            }
        }
        if (original.header("Accept-Language") == null) {
            builder.header("Accept-Language", ACCEPT_LANGUAGE)
        }
    }

    fun applyMediaShape(builder: Request.Builder, sameOrigin: Boolean) {
        builder.header("Accept", "*/*")
            .header("Sec-Fetch-Dest", "video")
            .header("Sec-Fetch-Mode", "no-cors")
            .header("Sec-Fetch-Site", if (sameOrigin) "same-origin" else "cross-site")
            .removeHeader("Upgrade-Insecure-Requests")
            .removeHeader("Sec-Fetch-User")
    }

    private fun setFetchHeadersIfMissing(
        original: Request,
        builder: Request.Builder,
        dest: String,
        mode: String,
        site: String,
    ) {
        if (original.header("Sec-Fetch-Dest") == null) builder.header("Sec-Fetch-Dest", dest)
        if (original.header("Sec-Fetch-Mode") == null) builder.header("Sec-Fetch-Mode", mode)
        if (original.header("Sec-Fetch-Site") == null) builder.header("Sec-Fetch-Site", site)
    }
}

/**
 * Shared OkHttp application interceptor that injects per-site `User-Agent`, `Referer`,
 * `AuthProvider.decorateRequestHeaders`, and browser/media shape headers strictly on allowed hosts.
 *
 * Security invariant: `Authorization` and `Cookie` headers produced by plugins are NEVER attached
 * to hosts outside [SiteDescriptor.matchesRequestHost] (media CDNs and update servers stay
 * strictly credential-free).
 */
internal class SiteRequestHeaderInterceptor(
    private val defaultKind: SiteRequestKind,
    private val siteRegistryProvider: () -> SiteRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host
        val tag = original.tag(SiteRequestTag::class.java)
        val registry = siteRegistryProvider()
        val plugin: SitePlugin? = tag?.siteId?.let { registry.get(it) }
            ?: registry.findForMediaHost(host)
        val descriptor = plugin?.descriptor
        val effectiveKind = tag?.siteRequest?.kind ?: defaultKind

        val builder = original.newBuilder()
        if (plugin != null && original.tag(CredentialScopeOriginTag::class.java) == null) {
            builder.tag(
                CredentialScopeOriginTag::class.java,
                CredentialScopeOriginTag(siteId = plugin.id, originHost = host),
            )
        }

        // Cloudflare rejects a blank User-Agent; always guarantee a non-blank UA.
        if (original.header("User-Agent") == null) {
            val ua = descriptor?.networkProfile?.userAgent?.takeIf { it.isNotBlank() }
                ?: SiteNetworkProfile.DEFAULT_MOBILE_UA
            builder.header("User-Agent", ua)
        }

        if (plugin != null && descriptor != null) {
            if (original.header("Referer") == null) {
                BrowserHeaders.resolveReferer(descriptor, effectiveKind)?.let { referer ->
                    builder.header("Referer", referer)
                }
            }

            // Attach site-specific headers (e.g. Iwara Origin/x-site & Bearer on apiHosts)
            val isExplicitSiteTag = tag?.siteId == plugin.id
            val isAllowedForSiteHeaders = descriptor.matchesMediaHost(host) || isExplicitSiteTag
            if (isAllowedForSiteHeaders) {
                val siteReq = tag?.siteRequest ?: SiteHttpRequest(
                    url = original.url.toString(),
                    siteId = plugin.id,
                    method = when (original.method.uppercase()) {
                        "POST" -> SiteHttpMethod.POST
                        "HEAD" -> SiteHttpMethod.HEAD
                        else -> SiteHttpMethod.GET
                    },
                    kind = effectiveKind,
                )
                val decorated = plugin.authProvider.decorateRequestHeaders(siteReq, host)
                val allowAuthCredentials = descriptor.matchesRequestHost(host) &&
                    effectiveKind != SiteRequestKind.PROBE &&
                    (effectiveKind != SiteRequestKind.IMAGE || plugin.authProvider.requiresAuthGate)
                for ((name, value) in decorated) {
                    val isCredentialHeader = name.equals("Authorization", ignoreCase = true) ||
                        name.equals("Cookie", ignoreCase = true)
                    if (isCredentialHeader && !allowAuthCredentials) {
                        continue
                    }
                    if (original.header(name) == null) {
                        builder.header(name, value)
                    }
                }
            }

            if (effectiveKind == SiteRequestKind.MEDIA || effectiveKind == SiteRequestKind.IMAGE) {
                val extraMediaHeaders = plugin.mediaTransportHook.extraMediaHeaders(original.url.toString())
                for ((name, value) in extraMediaHeaders) {
                    val isCredentialHeader = name.equals("Authorization", ignoreCase = true) ||
                        name.equals("Cookie", ignoreCase = true)
                    if (isCredentialHeader) continue
                    if (original.header(name) == null) {
                        builder.header(name, value)
                    }
                }
            }

            BrowserHeaders.applyBrowserShape(builder, original, descriptor, effectiveKind)
        } else if (effectiveKind == SiteRequestKind.MEDIA) {
            BrowserHeaders.applyMediaShape(builder, sameOrigin = false)
        }

        return chain.proceed(builder.build())
    }
}

/**
 * Network-layer defense-in-depth interceptor ensuring manually attached `Authorization` or
 * `Cookie` headers NEVER leak onto a redirected third-party CDN or foreign host.
 *
 * Unscoped requests (e.g. user WebDAV Basic Auth without site/redirect origin tags) are untouched.
 */
internal class CrossHostCredentialGuardInterceptor(
    private val siteRegistryProvider: () -> SiteRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val hasAuth = request.header("Authorization") != null
        val hasCookie = request.header("Cookie") != null
        if (!hasAuth && !hasCookie) {
            return chain.proceed(request)
        }

        val hopHost = request.url.host
        val registry = siteRegistryProvider()
        val siteTag = request.tag(SiteRequestTag::class.java)
        val scopeOrigin = request.tag(CredentialScopeOriginTag::class.java)
        val redirectOrigin = request.tag(MediaRedirectCache.RedirectOriginTag::class.java)

        var stripCredentials = false
        val taggedSiteId = siteTag?.siteId ?: scopeOrigin?.siteId
        if (taggedSiteId != null) {
            val descriptor = registry.get(taggedSiteId)?.descriptor
            if (descriptor == null || !descriptor.matchesRequestHost(hopHost)) {
                stripCredentials = true
            }
        }
        if (!stripCredentials && redirectOrigin != null) {
            val originHost = redirectOrigin.originUrl.toHttpUrlOrNull()?.host
            if (originHost != null && !hopHost.equals(originHost, ignoreCase = true)) {
                val originPlugin = registry.findForMediaHost(originHost)
                    ?: registry.allPlugins().firstOrNull { it.descriptor.matchesRequestHost(originHost) }
                if (originPlugin == null || !originPlugin.descriptor.matchesRequestHost(hopHost)) {
                    stripCredentials = true
                }
            }
        }

        if (!stripCredentials) {
            return chain.proceed(request)
        }
        val sanitized = request.newBuilder()
            .removeHeader("Authorization")
            .removeHeader("Cookie")
            .build()
        return chain.proceed(sanitized)
    }
}

/**
 * Transparently unwraps encrypted image responses (e.g., Manwa2 AES-CBC CDN images)
 * via [com.raven.app.core.sitespi.MediaTransportHook] for both Coil cover loads and
 * gallery reader downloads, without hard-coding any site ID.
 */
internal class SiteImageTransportUnwrapInterceptor(
    private val siteRegistryProvider: () -> SiteRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!request.method.equals("GET", ignoreCase = true) || !response.isSuccessful) {
            return response
        }

        val urlString = request.url.toString()
        val registry = siteRegistryProvider()
        val tag = request.tag(SiteRequestTag::class.java)
        val plugin = tag?.siteId?.let { registry.get(it) }
            ?: registry.findForMediaHost(request.url.host)
            ?: return response

        val hook = plugin.mediaTransportHook
        if (!hook.requiresFullResponseUnwrap(urlString)) {
            return response
        }

        val body = response.body ?: return response
        val contentLength = body.contentLength()
        if (contentLength > MAX_IMAGE_UNWRAP_BYTES) {
            return response
        }
        val originalContentType = body.contentType()
        val rawBytes = readBoundedBytes(body, MAX_IMAGE_UNWRAP_BYTES)
            ?: return response

        val unwrapped = hook.unwrapMediaBytes(urlString, rawBytes)
        return if (unwrapped != null) {
            val resolvedType = unwrapped.contentType.toMediaTypeOrNull() ?: originalContentType
            response.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Encoding")
                .header("Content-Type", unwrapped.contentType)
                .body(unwrapped.bytes.toResponseBody(resolvedType))
                .build()
        } else {
            response.newBuilder()
                .body(rawBytes.toResponseBody(originalContentType))
                .build()
        }
    }

    private fun readBoundedBytes(body: okhttp3.ResponseBody, maxBytes: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8_192)
        var total = 0L
        body.byteStream().use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw java.io.IOException("图片响应体超过解包上限 ($maxBytes bytes)")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_IMAGE_UNWRAP_BYTES: Long = 32L * 1024L * 1024L
    }
}
