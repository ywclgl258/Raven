@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.network.SiteRequestTag
import com.raven.app.core.sitespi.MediaTransportHook
import com.raven.app.core.sitespi.RouPngProtocol
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * Bridges [SitePlugin.mediaTransportHook] and the shared `MEDIA` [OkHttpClient] into Media3 [DataSource.Factory].
 *
 * - Resolves owning [SitePlugin] per request without hardcoded `when (siteId)` branches.
 * - Attaches [SiteRequestTag] so `:core:network`'s header interceptor and redirect cache apply cleanly.
 * - Attaches non-credential extra headers from [MediaTransportHook.extraMediaHeaders] and stream headers.
 * - Transparently wraps upstream with [UnwrappingMediaDataSource] when [MediaTransportHook.requiresFullResponseUnwrap]
 *   is true (e.g., `rou.video` PNG `roUd` HLS manifests & TS segments), while streaming plain responses untouched.
 */
class MediaTransportDataSourceFactory(
    private val mediaClient: OkHttpClient,
    private val siteRegistryProvider: () -> SiteRegistry,
) {
    fun resolvePlugin(siteId: SiteId?, url: String, cacheKey: String? = null): SitePlugin? {
        val registry = siteRegistryProvider()
        siteId?.let { registry.get(it) }?.let { return it }
        cacheKey?.substringBefore(':')?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { registry.get(SiteId.of(raw)) }.getOrNull()?.let { return it }
        }
        val host = runCatching {
            Uri.parse(url)?.host?.takeIf { it.isNotBlank() }
                ?: java.net.URI(url).host.orEmpty()
        }.getOrDefault("")
        return if (host.isNotBlank()) registry.findForMediaHost(host) else null
    }

    fun resolveTransportHook(siteId: SiteId?, url: String): MediaTransportHook =
        resolvePlugin(siteId, url)?.mediaTransportHook ?: MediaTransportHook.None

    fun resolveMimeTypeHint(siteId: SiteId?, url: String, explicitMimeHint: String? = null): String? {
        if (!explicitMimeHint.isNullOrBlank()) return explicitMimeHint
        val hook = resolveTransportHook(siteId, url)
        return HlsDownloadSupport.mimeTypeHint(url, hook)
    }

    fun createFactory(
        siteId: SiteId? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): DataSource.Factory = DataSource.Factory {
        RoutedMediaDataSource(
            mediaClient = mediaClient,
            siteId = siteId,
            extraHeaders = extraHeaders,
            pluginResolver = ::resolvePlugin,
        )
    }

    private class RoutedMediaDataSource(
        private val mediaClient: OkHttpClient,
        private val siteId: SiteId?,
        private val extraHeaders: Map<String, String>,
        private val pluginResolver: (SiteId?, String, String?) -> SitePlugin?,
    ) : DataSource {
        private var delegate: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val url = dataSpec.uri.toString()
            val plugin = pluginResolver(siteId, url, dataSpec.key)
            val resolvedSiteId = siteId ?: plugin?.id
            val hook = plugin?.mediaTransportHook ?: MediaTransportHook.None
            val descriptor = plugin?.descriptor

            val callFactory = Call.Factory { request ->
                val tagged = request.newBuilder()
                    .tag(
                        SiteRequestTag::class.java,
                        SiteRequestTag(
                            siteId = resolvedSiteId,
                            siteRequest = SiteHttpRequest(
                                url = request.url.toString(),
                                siteId = resolvedSiteId,
                                kind = SiteRequestKind.MEDIA,
                            ),
                        ),
                    )
                    .build()
                mediaClient.newCall(tagged)
            }

            val mergedHeaders = LinkedHashMap<String, String>()
            val referer = descriptor?.networkProfile?.mediaReferer?.takeIf { it.isNotBlank() }
                ?: descriptor?.networkProfile?.referer?.takeIf { it.isNotBlank() }
                ?: descriptor?.baseUrl?.takeIf { it.isNotBlank() }?.let { "$it/" }
                ?: dataSpec.uri.host?.takeIf { it.isNotBlank() }?.let { "https://$it/" }
            if (!referer.isNullOrBlank()) {
                mergedHeaders["Referer"] = referer
            }
            for ((name, value) in hook.extraMediaHeaders(url)) {
                if (!isCredentialHeader(name)) mergedHeaders[name] = value
            }
            for ((name, value) in extraHeaders) {
                if (!isCredentialHeader(name)) mergedHeaders[name] = value
            }

            val ua = descriptor?.networkProfile?.userAgent?.takeIf { it.isNotBlank() }
                ?: SiteNetworkProfile.DEFAULT_MOBILE_UA

            val okHttpSource: DataSource = OkHttpDataSource.Factory(callFactory)
                .setUserAgent(ua)
                .setDefaultRequestProperties(mergedHeaders)
                .createDataSource()

            val wrapped: DataSource = if (hook.requiresFullResponseUnwrap(url)) {
                UnwrappingMediaDataSource(okHttpSource, hook)
            } else {
                okHttpSource
            }
            delegate = wrapped
            listeners.forEach(wrapped::addTransferListener)
            return wrapped.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

        override fun close() {
            try {
                delegate?.close()
            } finally {
                delegate = null
            }
        }
    }

    companion object {
        internal fun isCredentialHeader(name: String): Boolean =
            name.equals("Authorization", ignoreCase = true) || name.equals("Cookie", ignoreCase = true)
    }
}

/**
 * Media3 [DataSource] that transparently unwraps full-body wrapped media responses (such as
 * `rou.video`'s PNG `roUd` manifests and MPEG-TS segments) via [MediaTransportHook].
 *
 * Non-PNG direct streams (e.g., plain MP4s) are passed through incrementally without buffering the
 * entire file in memory.
 */
class UnwrappingMediaDataSource(
    private val upstream: DataSource,
    private val hook: MediaTransportHook,
    private val maxBufferedBytes: Int = RouPngProtocol.MAX_UNWRAPPED_BYTES,
) : DataSource {
    private var body: ByteArray = ByteArray(0)
    private var position: Int = 0
    private var buffered: Boolean = false
    private var remaining: Long = C.LENGTH_UNSET.toLong()
    private var resolvedUri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        if (!hook.requiresFullResponseUnwrap(url)) {
            val opened = upstream.open(dataSpec)
            resolvedUri = upstream.uri ?: dataSpec.uri
            headers = upstream.responseHeaders
            buffered = false
            body = ByteArray(0)
            position = 0
            remaining = opened
            return opened
        }

        // Request offset 0 with full length because Range offsets apply to unwrapped bytes.
        val fullSpec = dataSpec.buildUpon()
            .setPosition(0L)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        val opened = upstream.open(fullSpec)
        resolvedUri = upstream.uri ?: dataSpec.uri
        headers = upstream.responseHeaders

        val prefix = ByteArray(PREFIX_PROBE_BYTES)
        var prefixCount = 0
        while (prefixCount < prefix.size) {
            val n = upstream.read(prefix, prefixCount, prefix.size - prefixCount)
            if (n == C.RESULT_END_OF_INPUT) break
            prefixCount += n
        }
        val actualPrefix = prefix.copyOf(prefixCount)
        val shouldDrain = RouPngProtocol.isWrapped(actualPrefix) ||
            (hook !== RouPngProtocol.transportHook && opened in 0L..CUSTOM_UNWRAP_BUFFER_CAP)

        buffered = shouldDrain
        body = if (shouldDrain) drainRemaining(actualPrefix) else actualPrefix
        if (shouldDrain) {
            val unwrapped = hook.unwrapMediaBytes(resolvedUri?.toString() ?: url, body)
            if (unwrapped != null) {
                body = unwrapped.bytes
                headers = headers.filterKeys { key ->
                    !key.equals("Content-Length", ignoreCase = true) &&
                        !key.equals("Content-Type", ignoreCase = true)
                } + mapOf(
                    "Content-Type" to listOf(unwrapped.contentType),
                    "Content-Length" to listOf(unwrapped.bytes.size.toString()),
                )
            }
        }

        position = 0
        remaining = C.LENGTH_UNSET.toLong()
        var skip = dataSpec.position
        val scratch = ByteArray(8 * 1024)
        while (skip > 0L) {
            val n = read(scratch, 0, minOf(skip, scratch.size.toLong()).toInt())
            if (n == C.RESULT_END_OF_INPUT) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            skip -= n.toLong()
        }

        val available = when {
            buffered -> (body.size - position).toLong()
            opened != C.LENGTH_UNSET.toLong() -> (opened - dataSpec.position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            if (available != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else dataSpec.length
        } else {
            available
        }
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = if (remaining != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), remaining).toInt()
        } else {
            length
        }
        val count = when {
            position < body.size -> {
                val available = minOf(requested, body.size - position)
                System.arraycopy(body, position, buffer, offset, available)
                position += available
                available
            }
            buffered -> C.RESULT_END_OF_INPUT
            else -> upstream.read(buffer, offset, requested)
        }
        if (count > 0 && remaining != C.LENGTH_UNSET.toLong()) {
            remaining -= count.toLong()
        }
        return count
    }

    override fun getUri(): Uri? = resolvedUri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        position = 0
        body = ByteArray(0)
        remaining = C.LENGTH_UNSET.toLong()
        buffered = false
        resolvedUri = null
        headers = emptyMap()
        upstream.close()
    }

    private fun drainRemaining(prefix: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(maxOf(prefix.size, 16 * 1024))
        out.write(prefix)
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = upstream.read(chunk, 0, chunk.size)
            if (read == C.RESULT_END_OF_INPUT) break
            if (out.size() + read > maxBufferedBytes) {
                throw IOException("媒体分片超过最大解包体积限制 ($maxBufferedBytes bytes)")
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private const val PREFIX_PROBE_BYTES = 8
        private const val CUSTOM_UNWRAP_BUFFER_CAP = 8L * 1024L * 1024L
    }
}
