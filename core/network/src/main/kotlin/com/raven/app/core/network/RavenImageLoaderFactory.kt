package com.raven.app.core.network

import android.content.Context
import android.os.Build
import coil3.Extras
import coil3.ImageLoader
import coil3.Uri
import coil3.disk.DiskCache
import coil3.getExtra
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.intercept.Interceptor
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Coil 3 request extras for site-partitioned cover & artwork loading.
 */
object RavenImageExtras {
    /** Explicit owning [SiteId] attached to Coil [ImageRequest]s for header & cache isolation. */
    val SiteIdKey: Extras.Key<SiteId?> = Extras.Key(default = null)

    /**
     * Computes the deterministic site-partitioned cache key `"<siteId>|<url>"` used by both
     * memory keyers and disk cache keys.
     */
    fun partitionCacheKey(
        url: String,
        explicitSiteId: SiteId?,
        siteRegistry: SiteRegistry,
    ): String {
        val resolvedSiteId = explicitSiteId
            ?: UrlSanitizer.extractHost(url)?.let { host -> siteRegistry.findForMediaHost(host)?.id }
        val partition = resolvedSiteId?.value ?: SitePartitionedUriKeyer.GLOBAL_PARTITION
        return "$partition|$url"
    }
}

/**
 * Attaches [siteId] to a Coil 3 [ImageRequest.Builder] so [RavenImageLoaderFactory]'s single
 * [ImageLoader] applies per-site headers and site-partitioned memory/disk cache keys.
 */
fun ImageRequest.Builder.ravenSiteId(siteId: SiteId?): ImageRequest.Builder = apply {
    extras.set(RavenImageExtras.SiteIdKey, siteId)
}

/**
 * Decorator matching `:core:designsystem`'s `LocalRavenImageRequestDecorator` signature.
 */
fun ravenImageRequestDecorator(
    builder: ImageRequest.Builder,
    siteId: SiteId?,
): ImageRequest.Builder = builder.ravenSiteId(siteId)

/**
 * Partitions Coil 3 memory cache keys for [Uri] data strictly by owning [SiteId]
 * (`"<siteId>|<uri>"`) so covers from different sites (e.g. E-Hentai vs ExHentai sharing
 * `ehgt.org`) never collide or poison each other.
 */
class SitePartitionedUriKeyer(
    private val siteRegistryProvider: () -> SiteRegistry,
) : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String {
        val rawUrl = data.toString()
        val siteId = options.getExtra(RavenImageExtras.SiteIdKey)
            ?: data.authority?.substringBefore(':')?.let { host ->
                siteRegistryProvider().findForMediaHost(host)?.id
            }
        val partition = siteId?.value ?: GLOBAL_PARTITION
        return "$partition|$rawUrl"
    }

    companion object {
        const val GLOBAL_PARTITION: String = "global"
    }
}

/**
 * Partitions Coil 3 memory cache keys for raw [String] URLs strictly by owning [SiteId].
 */
class SitePartitionedStringKeyer(
    private val siteRegistryProvider: () -> SiteRegistry,
) : Keyer<String> {
    override fun key(data: String, options: Options): String {
        val siteId = options.getExtra(RavenImageExtras.SiteIdKey)
            ?: UrlSanitizer.extractHost(data)?.let { host ->
                siteRegistryProvider().findForMediaHost(host)?.id
            }
        val partition = siteId?.value ?: SitePartitionedUriKeyer.GLOBAL_PARTITION
        return "$partition|$data"
    }
}

/**
 * Coil 3 [Interceptor] that injects per-site `User-Agent`, `Referer`, browser image headers,
 * non-sensitive plugin headers, and site-partitioned memory/disk cache keys onto every HTTP image request.
 *
 * Strictly strips `Authorization` and `Cookie` headers so cover requests NEVER leak credentials.
 */
class SiteImageHeadersInterceptor(
    private val siteRegistryProvider: () -> SiteRegistry,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val urlString = when (val data = request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> return chain.proceed()
        }
        val isHttp = urlString.startsWith("https://", ignoreCase = true) ||
            urlString.startsWith("http://", ignoreCase = true)
        if (!isHttp) {
            return chain.proceed()
        }

        val host = UrlSanitizer.extractHost(urlString).orEmpty()
        val registry = siteRegistryProvider()
        val explicitSiteId = request.getExtra(RavenImageExtras.SiteIdKey)
        val plugin: SitePlugin? = explicitSiteId?.let { registry.get(it) }
            ?: host.takeIf { it.isNotEmpty() }?.let { registry.findForMediaHost(it) }
        val descriptor = plugin?.descriptor

        val partitionId = explicitSiteId?.value ?: plugin?.id?.value ?: SitePartitionedUriKeyer.GLOBAL_PARTITION
        val partitionedKey = "$partitionId|$urlString"

        val existingHeaders = request.httpHeaders
        val headersBuilder = NetworkHeaders.Builder()
        for ((name, values) in existingHeaders.asMap()) {
            if (name.equals("Authorization", ignoreCase = true) || name.equals("Cookie", ignoreCase = true)) {
                continue
            }
            for (value in values) {
                headersBuilder.add(name, value)
            }
        }

        fun setIfMissing(name: String, value: String) {
            if (value.isNotBlank() && existingHeaders.get(name) == null) {
                headersBuilder.set(name, value)
            }
        }

        val userAgent = descriptor?.networkProfile?.userAgent?.takeIf { it.isNotBlank() }
            ?: SiteNetworkProfile.DEFAULT_MOBILE_UA
        setIfMissing("User-Agent", userAgent)

        if (plugin != null && descriptor != null) {
            BrowserHeaders.resolveReferer(descriptor, SiteRequestKind.IMAGE)?.let { referer ->
                setIfMissing("Referer", referer)
            }
            setIfMissing("Accept", BrowserHeaders.ACCEPT_IMAGE)
            setIfMissing("Accept-Language", BrowserHeaders.ACCEPT_LANGUAGE)
            setIfMissing("Sec-Fetch-Dest", "image")
            setIfMissing("Sec-Fetch-Mode", "no-cors")
            val sameOrigin = host.isNotEmpty() && descriptor.matchesRequestHost(host)
            setIfMissing("Sec-Fetch-Site", if (sameOrigin) "same-origin" else "cross-site")

            val siteReq = SiteHttpRequest(
                url = urlString,
                siteId = plugin.id,
                method = SiteHttpMethod.GET,
                kind = SiteRequestKind.IMAGE,
            )
            val decorated = plugin.authProvider.decorateRequestHeaders(siteReq, host)
            for ((name, value) in decorated) {
                if (name.equals("Authorization", ignoreCase = true) || name.equals("Cookie", ignoreCase = true)) {
                    continue
                }
                setIfMissing(name, value)
            }
        }

        val updatedRequest = request.newBuilder()
            .httpHeaders(headersBuilder.build())
            .apply {
                if (request.memoryCacheKey == null) memoryCacheKey(partitionedKey)
                if (request.diskCacheKey == null) diskCacheKey(partitionedKey)
            }
            .build()

        return chain.withRequest(updatedRequest).proceed()
    }
}

/**
 * Builds Raven's single global Coil 3 [ImageLoader], replacing R34V's 19-instance `ImageLoader` map.
 *
 * Uses the shared `IMAGE` [OkHttpClient], [SiteImageHeadersInterceptor], site-partitioned keyers,
 * 24 MiB memory cache, 64 MiB disk cache (`cacheDir/coil-covers`), and API-appropriate GIF decoder.
 */
class RavenImageLoaderFactory(
    private val context: Context,
    private val imageOkHttpClient: OkHttpClient,
    private val siteRegistryProvider: () -> SiteRegistry,
    private val memoryCacheBytes: Long = DEFAULT_MEMORY_CACHE_BYTES,
    private val diskCacheBytes: Long = DEFAULT_DISK_CACHE_BYTES,
) {
    fun create(): ImageLoader {
        val appContext = context.applicationContext
        val diskDir = File(appContext.cacheDir, COVER_DISK_CACHE_DIR)
        return ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(memoryCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskDir.toOkioPath())
                    .maxSizeBytes(diskCacheBytes)
                    .build()
            }
            .components {
                add(SiteImageHeadersInterceptor(siteRegistryProvider))
                add(SitePartitionedUriKeyer(siteRegistryProvider), Uri::class)
                add(SitePartitionedStringKeyer(siteRegistryProvider), String::class)
                add(OkHttpNetworkFetcherFactory(callFactory = { imageOkHttpClient }))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    companion object {
        const val COVER_DISK_CACHE_DIR: String = "coil-covers"
        const val DEFAULT_MEMORY_CACHE_BYTES: Long = 24L * 1024 * 1024
        const val DEFAULT_DISK_CACHE_BYTES: Long = 64L * 1024 * 1024
    }
}
