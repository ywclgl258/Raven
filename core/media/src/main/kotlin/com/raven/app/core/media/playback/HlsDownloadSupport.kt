@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.playback

import androidx.media3.common.MimeTypes
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import com.raven.app.core.sitespi.MediaTransportHook
import java.util.concurrent.Executor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Isolated HLS cache namespace and completeness verification for offline HLS video downloads.
 */
object HlsDownloadSupport {
    const val METADATA_KEY: String = "raven_hls_download"
    const val LEGACY_METADATA_KEY: String = "r34v_hls_download"

    private val apiHlsRegex = Regex("^https?://[^/]+/api/hls/", RegexOption.IGNORE_CASE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class CachedPart(
        val key: String,
        val position: Long,
        val length: Long,
    )

    @Serializable
    private data class HlsSnapshot(
        val uri: String,
        val parts: List<CachedPart>,
    )

    fun isHlsUrl(url: String, hook: MediaTransportHook = MediaTransportHook.None): Boolean =
        hook.isHlsUrl(url) ||
            url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true) ||
            apiHlsRegex.containsMatchIn(url)

    fun mimeTypeHint(url: String, hook: MediaTransportHook = MediaTransportHook.None): String? =
        if (isHlsUrl(url, hook)) MimeTypes.APPLICATION_M3U8 else null

    fun cachePrefix(downloadId: String): String = "$downloadId|hls|"

    fun cacheKeyFactory(downloadId: String): CacheKeyFactory =
        CacheKeyFactory { spec -> cachePrefix(downloadId) + spec.uri.toString() }

    private fun readSnapshot(cache: Cache, downloadId: String): HlsSnapshot? {
        val metadata = cache.getContentMetadata(downloadId)
        val raw = metadata.get(METADATA_KEY, "").takeIf { !it.isNullOrBlank() }
            ?: metadata.get(LEGACY_METADATA_KEY, "").takeIf { !it.isNullOrBlank() }
            ?: return null
        return runCatching { json.decodeFromString(HlsSnapshot.serializer(), raw) }.getOrNull()
    }

    fun hlsUri(cache: Cache, downloadId: String): String? = readSnapshot(cache, downloadId)?.uri

    fun isComplete(cache: Cache, downloadId: String): Boolean {
        val saved = readSnapshot(cache, downloadId) ?: return false
        val markerPresent = cache.isCached(downloadId, 0L, 1L) &&
            cache.getCachedSpans(downloadId).all { it.file?.isFile == true }
        if (!markerPresent || saved.parts.isEmpty()) return false
        val expectedPrefix = cachePrefix(downloadId)
        return saved.parts.all { part ->
            part.key.startsWith(expectedPrefix) &&
                part.position >= 0L &&
                part.length > 0L &&
                cache.isCached(part.key, part.position, part.length) &&
                cache.getCachedSpans(part.key)
                    .filter { span ->
                        span.position < part.position + part.length &&
                            span.position + span.length > part.position
                    }
                    .all { span -> span.file?.let { f -> f.isFile && f.length() == span.length } == true }
        }
    }

    private fun recordSnapshot(cache: Cache, request: DownloadRequest) {
        val expectedPrefix = cachePrefix(request.id)
        val parts = cache.keys.filter { it.startsWith(expectedPrefix) }.flatMap { key ->
            cache.getCachedSpans(key).map { span ->
                CachedPart(key = key, position = span.position, length = span.length)
            }
        }
        check(parts.isNotEmpty()) { "HLS 下载没有缓存媒体分片" }

        // Keep a 1-byte marker span so SimpleCache retains this metadata across cold restarts/reinstalls.
        val markerSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(byteArrayOf(1)) }
            .createDataSource()
        CacheWriter(
            markerSource,
            DataSpec.Builder()
                .setUri("https://raven.local/hls-index")
                .setKey(request.id)
                .setLength(1L)
                .build(),
            null,
            null,
        ).cache()

        cache.applyContentMetadataMutations(
            request.id,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), 1L),
        )
        val encoded = json.encodeToString(HlsSnapshot.serializer(), HlsSnapshot(request.uri.toString(), parts))
        cache.applyContentMetadataMutations(
            request.id,
            ContentMetadataMutations().set(METADATA_KEY, encoded),
        )
        check(isComplete(cache, request.id)) { "HLS 离线缓存校验未通过" }
    }

    fun remove(cache: Cache, downloadId: String) {
        val expectedPrefix = cachePrefix(downloadId)
        cache.keys.filter { it.startsWith(expectedPrefix) }.forEach(cache::removeResource)
        cache.removeResource(downloadId)
    }

    fun discardDamagedSpans(cache: Cache, downloadId: String) {
        val expectedPrefix = cachePrefix(downloadId)
        for (key in cache.keys.filter { it == downloadId || it.startsWith(expectedPrefix) }) {
            for (span in cache.getCachedSpans(key)) {
                val intact = span.file?.let { f -> f.isFile && f.length() == span.length } == true
                if (!intact) cache.removeSpan(span)
            }
        }
    }

    fun createDownloaderFactory(
        cache: Cache,
        upstreamFactoryProvider: (DownloadRequest) -> DataSource.Factory,
        hookProvider: (DownloadRequest) -> MediaTransportHook,
        executor: Executor,
    ): DownloaderFactory = DownloaderFactory { request ->
        val hook = hookProvider(request)
        val isHls = request.mimeType == MimeTypes.APPLICATION_M3U8 || isHlsUrl(request.uri.toString(), hook)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactoryProvider(request))
        if (isHls) {
            cacheFactory.setCacheKeyFactory(cacheKeyFactory(request.id))
        }
        val normalized = if (isHls) {
            DownloadRequest.Builder(request.id, request.uri)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setStreamKeys(request.streamKeys)
                .setKeySetId(request.keySetId)
                .setData(request.data)
                .build()
        } else {
            request
        }
        val delegate = DefaultDownloaderFactory(cacheFactory, executor).createDownloader(normalized)
        if (!isHls) {
            delegate
        } else {
            object : Downloader {
                override fun download(progressListener: Downloader.ProgressListener?) {
                    cache.removeResource(request.id)
                    val initial = json.encodeToString(
                        HlsSnapshot.serializer(),
                        HlsSnapshot(request.uri.toString(), emptyList()),
                    )
                    cache.applyContentMetadataMutations(
                        request.id,
                        ContentMetadataMutations().set(METADATA_KEY, initial),
                    )
                    delegate.download(progressListener)
                    recordSnapshot(cache, request)
                }

                override fun cancel() = delegate.cancel()
                override fun remove() = HlsDownloadSupport.remove(cache, request.id)
            }
        }
    }
}
