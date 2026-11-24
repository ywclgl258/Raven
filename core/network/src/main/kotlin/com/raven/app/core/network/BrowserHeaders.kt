package com.raven.app.core.network

import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
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
                val allowAuthCredentials = descriptor.matchesRequestHost(host) && effectiveKind != SiteRequestKind.PROBE
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

            if (effectiveKind == SiteRequestKind.MEDIA) {
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
