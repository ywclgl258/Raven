@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.playback

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.media.storage.StorageLayout
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 4-tier isolated Media3 [SimpleCache] manager:
 *
 * 1. **Permanent download cache** (per storage root, `NoOpCacheEvictor`): offline playback sets
 *    `upstreamDataSourceFactory = null` so offline playback NEVER falls back to network.
 * 2. **Optional stream cache** (`512 MiB` LRU): read-only unless `cacheStreaming == true`.
 * 3. **Session MP4 seek cache** (`64 MiB` LRU): cleared automatically whenever `sessionKey` switches.
 * 4. **Session HLS VOD seek cache** (`32 MiB` LRU): cleared on cold init and per-session via [HlsSeekSession].
 */
class DownloadCaches(
    private val context: Context,
    private val storageRootManager: StorageRootManager,
    private val transportFactory: MediaTransportDataSourceFactory,
    private val applicationScope: CoroutineScope,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    val databaseProvider: DatabaseProvider = StandaloneDatabaseProvider(context.applicationContext),
) : Closeable {
    private val appContext = context.applicationContext
    private val downloadCacheMap = LinkedHashMap<String, SimpleCache>()

    private val streamCacheLazy = lazy {
        SimpleCache(
            File(appContext.cacheDir, "raven-streams"),
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_MAX_BYTES),
            databaseProvider,
        )
    }

    private val seekCacheLazy = lazy {
        SimpleCache(
            File(appContext.cacheDir, "raven-seeks"),
            LeastRecentlyUsedCacheEvictor(MP4_SEEK_CACHE_MAX_BYTES),
            databaseProvider,
        )
    }

    private val hlsSeekCacheLazy = lazy {
        SimpleCache(
            File(appContext.cacheDir, "raven-hls-seeks"),
            LeastRecentlyUsedCacheEvictor(HLS_SEEK_CACHE_MAX_BYTES),
            databaseProvider,
        ).also { cache ->
            // Drop any orphaned keys left behind by a prior process kill on an IO worker.
            val staleKeys = cache.keys.toList()
            if (staleKeys.isNotEmpty()) {
                applicationScope.launch(dispatchers.io) {
                    staleKeys.forEach { key -> runCatching { cache.removeResource(key) } }
                }
            }
        }
    }

    private val seekSessionLock = Any()

    @Volatile
    private var activeSeekSessionKey: String? = null

    @Synchronized
    fun downloadCache(sourceRoot: String = ""): SimpleCache {
        val layout = storageRootManager.resolveSourceLayout(sourceRoot)
        return downloadCacheMap.getOrPut(layout.root.canonicalPath) {
            storageRootManager.ensure(layout.location)
            SimpleCache(
                layout.videoCacheDir,
                NoOpCacheEvictor(),
                databaseProvider,
                null,
                false,
                true,
            ).also { cache ->
                try {
                    cache.checkInitialization()
                } catch (e: Exception) {
                    cache.release()
                    throw e
                }
            }
        }
    }

    private fun candidateLayouts(sourceRoot: String): List<StorageLayout> =
        if (sourceRoot.isNotBlank()) {
            listOf(storageRootManager.resolveSourceLayout(sourceRoot))
        } else {
            (listOf(storageRootManager.layout()) + storageRootManager.readableLayouts())
                .distinctBy { it.root.canonicalPath }
        }

    @Synchronized
    private fun existingDownloadCache(layout: StorageLayout): SimpleCache? {
        val canonical = layout.root.canonicalPath
        downloadCacheMap[canonical]?.let { return it }
        val dir = layout.videoCacheDir
        if (!dir.isDirectory || dir.listFiles().orEmpty().isEmpty()) return null
        return downloadCache(layout.root.absolutePath)
    }

    private fun isCacheEntryComplete(cache: SimpleCache, key: String): Boolean {
        if (HlsDownloadSupport.hlsUri(cache, key) != null) {
            return HlsDownloadSupport.isComplete(cache, key)
        }
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0L || !cache.isCached(key, 0L, length)) return false
        return cache.getCachedSpans(key)
            .filter { it.position < length }
            .all { span -> span.file?.let { f -> f.isFile && f.length() == span.length } == true }
    }

    fun isCached(key: String, sourceRoot: String = ""): Boolean = runCatching {
        candidateLayouts(sourceRoot).any { layout ->
            existingDownloadCache(layout)?.let { isCacheEntryComplete(it, key) } == true
        }
    }.getOrDefault(false)

    fun readCache(key: String, sourceRoot: String = ""): SimpleCache =
        candidateLayouts(sourceRoot)
            .mapNotNull(::existingDownloadCache)
            .firstOrNull { isCacheEntryComplete(it, key) }
            ?: downloadCache(sourceRoot)

    fun hlsUri(key: String, sourceRoot: String = ""): String? =
        HlsDownloadSupport.hlsUri(readCache(key, sourceRoot), key)

    fun removeDownload(key: String) {
        candidateLayouts("")
            .mapNotNull(::existingDownloadCache)
            .forEach { cache -> HlsDownloadSupport.remove(cache, key) }
    }

    fun discardDamagedSpans(key: String, sourceRoot: String = "") {
        HlsDownloadSupport.discardDamagedSpans(downloadCache(sourceRoot), key)
    }

    /**
     * Copies private Media3 cache spans + metadata into the shared root cache without clobbering
     * an existing shared cache UID or existing entries.
     */
    fun copyPrivateDownloadsToShared(
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        val source = existingDownloadCache(storageRootManager.layoutOf(StorageLocation.PRIVATE)) ?: return
        val target = downloadCache(storageRootManager.layoutOf(StorageLocation.SHARED).root.absolutePath)
        if (source === target) return

        val offlineSource = CacheDataSource.Factory()
            .setCache(source)
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(null)
        val destination = CacheDataSource.Factory()
            .setCache(target)
            .setUpstreamDataSourceFactory(offlineSource)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)

        val keys = source.keys.toList()
        val totalBytes = keys.sumOf { key ->
            source.getCachedSpans(key).sumOf { span -> span.length.coerceAtLeast(0L) }
        }
        var copiedBytes = 0L

        for (key in keys) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Migration cancelled")
            val length = ContentMetadata.getContentLength(source.getContentMetadata(key))
            val previousLength = ContentMetadata.getContentLength(target.getContentMetadata(key))
            check(length <= 0L || previousLength <= 0L || length == previousLength) {
                "同一视频的缓存长度不一致，已停止迁移"
            }
            for (span in source.getCachedSpans(key).toList()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Migration cancelled")
                val spec = DataSpec.Builder()
                    .setUri("https://raven.local/cache-copy")
                    .setKey(key)
                    .setPosition(span.position)
                    .setLength(span.length)
                    .build()
                CacheWriter(destination.createDataSource(), spec, null, null).cache()
                copiedBytes += span.length.coerceAtLeast(0L)
                onProgress(copiedBytes, totalBytes)
            }
            val metadata = source.getContentMetadata(key)
            val mutations = ContentMetadataMutations()
            if (length > 0L) {
                ContentMetadataMutations.setContentLength(mutations, length)
            }
            metadata.get(HlsDownloadSupport.METADATA_KEY, "")
                ?.takeIf { it.isNotBlank() }
                ?.let { mutations.set(HlsDownloadSupport.METADATA_KEY, it) }
            metadata.get(HlsDownloadSupport.LEGACY_METADATA_KEY, "")
                ?.takeIf { it.isNotBlank() }
                ?.let { mutations.set(HlsDownloadSupport.LEGACY_METADATA_KEY, it) }
            ContentMetadata.getRedirectedUri(metadata)?.let {
                ContentMetadataMutations.setRedirectedUri(mutations, it)
            }
            target.applyContentMetadataMutations(key, mutations)
        }
    }

    fun openHlsSeekSession(allowWrite: ((DataSpec) -> Boolean)? = null): HlsSeekSession =
        HlsSeekSession(
            cache = hlsSeekCacheLazy,
            allowWrite = allowWrite,
        ) { prefix ->
            applicationScope.launch(dispatchers.io) {
                if (!hlsSeekCacheLazy.isInitialized()) return@launch
                val cache = hlsSeekCacheLazy.value
                cache.keys.filter { it.startsWith(prefix) }.forEach { key ->
                    runCatching { cache.removeResource(key) }
                }
            }
        }

    fun createHlsDataSourceFactory(
        offline: Boolean,
        siteId: SiteId?,
        sourceRoot: String = "",
        hlsKey: String,
        cacheStreaming: Boolean = false,
        hlsSeek: HlsSeekSession? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HlsDataSourceFactory {
        val mediaFactory = createPlaybackDataSourceFactory(
            offline = offline,
            siteId = siteId,
            sourceRoot = sourceRoot,
            hlsKey = hlsKey,
            cacheStreaming = cacheStreaming,
            extraHeaders = extraHeaders,
        )
        val seekMediaFactory = hlsSeek?.let { session ->
            createPlaybackDataSourceFactory(
                offline = offline,
                siteId = siteId,
                sourceRoot = sourceRoot,
                hlsKey = hlsKey,
                cacheStreaming = cacheStreaming,
                hlsSeek = session,
                extraHeaders = extraHeaders,
            )
        }
        val liveManifestFactory = if (offline) {
            null
        } else {
            DefaultDataSource.Factory(
                appContext,
                transportFactory.createFactory(siteId = siteId, extraHeaders = extraHeaders),
            )
        }
        return HlsDataSourceFactory { dataType ->
            val factory = when (dataType) {
                C.DATA_TYPE_MANIFEST -> liveManifestFactory ?: mediaFactory
                C.DATA_TYPE_MEDIA -> seekMediaFactory ?: mediaFactory
                else -> mediaFactory
            }
            factory.createDataSource()
        }
    }

    /**
     * Builds the layered [DataSource.Factory] for playback.
     *
     * When [offline] is `true`, the permanent download cache has `upstreamDataSourceFactory = null`
     * guaranteeing strictly zero network fallback.
     */
    fun createPlaybackDataSourceFactory(
        offline: Boolean = false,
        siteId: SiteId? = null,
        sourceRoot: String = "",
        hlsKey: String? = null,
        cacheStreaming: Boolean = false,
        sessionKey: String? = null,
        hlsSeek: HlsSeekSession? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): DataSource.Factory {
        val cacheKeys = hlsKey?.let(HlsDownloadSupport::cacheKeyFactory) ?: CacheKeyFactory.DEFAULT
        val resolvedHlsCache by lazy { readCache(hlsKey.orEmpty(), sourceRoot) }

        val networkUpstream: DataSource.Factory? = if (offline) {
            null
        } else {
            val http = DefaultDataSource.Factory(
                appContext,
                transportFactory.createFactory(siteId = siteId, extraHeaders = extraHeaders),
            )
            val streamCache = streamCacheLazy.value
            CacheDataSource.Factory()
                .setCache(streamCache)
                .setCacheKeyFactory(cacheKeys)
                .setCacheWriteDataSinkFactory(
                    if (cacheStreaming) CacheDataSink.Factory().setCache(streamCache) else null,
                )
                .setUpstreamDataSourceFactory(hlsSeek?.wrapUpstream(http) ?: http)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        return DataSource.Factory {
            object : DataSource {
                private var activeSource: DataSource? = null
                private val listeners = mutableListOf<TransferListener>()

                override fun addTransferListener(transferListener: TransferListener) {
                    listeners += transferListener
                    activeSource?.addTransferListener(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    val downloadCache = if (hlsKey != null) {
                        resolvedHlsCache
                    } else {
                        readCache(dataSpec.key.orEmpty(), sourceRoot)
                    }
                    var layeredFactory: DataSource.Factory = CacheDataSource.Factory()
                        .setCache(downloadCache)
                        .setCacheKeyFactory(cacheKeys)
                        .setCacheWriteDataSinkFactory(null)
                        .setUpstreamDataSourceFactory(networkUpstream)

                    if (!offline && sessionKey != null && dataSpec.length != 0L) {
                        val seekCache = seekCacheLazy.value
                        val staleKeys = synchronized(seekSessionLock) {
                            if (activeSeekSessionKey != sessionKey) {
                                activeSeekSessionKey = sessionKey
                                seekCache.keys.toList().filter { it != sessionKey }
                            } else {
                                emptyList()
                            }
                        }
                        staleKeys.forEach { staleKey -> runCatching { seekCache.removeResource(staleKey) } }
                        layeredFactory = CacheDataSource.Factory()
                            .setCache(seekCache)
                            .setCacheKeyFactory { sessionKey }
                            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(seekCache))
                            .setUpstreamDataSourceFactory(layeredFactory)
                            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    }

                    val created = layeredFactory.createDataSource()
                    activeSource = created
                    listeners.forEach(created::addTransferListener)
                    return created.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    activeSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

                override fun getUri(): Uri? = activeSource?.uri

                override fun getResponseHeaders(): Map<String, List<String>> =
                    activeSource?.responseHeaders.orEmpty()

                override fun close() {
                    try {
                        activeSource?.close()
                    } finally {
                        activeSource = null
                    }
                }
            }
        }
    }

    /**
     * Opens a strictly offline [MediaDataSource] backed by the permanent download [SimpleCache]
     * for progressive MP4 seek-bar frame preview decoding (never touches the network).
     * Returns `null` for HLS or incomplete downloads.
     */
    fun openOfflineMp4MediaDataSource(cacheKey: String, sourceRoot: String = ""): MediaDataSource? {
        if (cacheKey.isBlank()) return null
        val cache = readCache(cacheKey, sourceRoot)
        if (HlsDownloadSupport.hlsUri(cache, cacheKey) != null) return null
        if (!isCacheEntryComplete(cache, cacheKey)) return null
        val totalLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        if (totalLength <= 0L) return null

        val readOnlyFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(null)

        return object : MediaDataSource() {
            override fun getSize(): Long = totalLength

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (size == 0) return 0
                if (position < 0L || position >= totalLength) return -1
                val toRead = minOf(size.toLong(), totalLength - position)
                val source = readOnlyFactory.createDataSource()
                return try {
                    val spec = DataSpec.Builder()
                        .setUri("https://raven.local/offline-preview")
                        .setKey(cacheKey)
                        .setPosition(position)
                        .setLength(toRead)
                        .build()
                    source.open(spec)
                    var totalRead = 0
                    while (totalRead < toRead.toInt()) {
                        val n = source.read(buffer, offset + totalRead, toRead.toInt() - totalRead)
                        if (n == C.RESULT_END_OF_INPUT) break
                        totalRead += n
                    }
                    if (totalRead == 0) -1 else totalRead
                } finally {
                    runCatching { source.close() }
                }
            }

            override fun close() = Unit
        }
    }

    private fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun streamingCacheBytes(): Long =
        if (streamCacheLazy.isInitialized()) streamCacheLazy.value.cacheSpace else directorySizeBytes(File(appContext.cacheDir, "raven-streams"))

    fun seekCacheBytes(): Long {
        val mp4Bytes = if (seekCacheLazy.isInitialized()) seekCacheLazy.value.cacheSpace else directorySizeBytes(File(appContext.cacheDir, "raven-seeks"))
        val hlsBytes = if (hlsSeekCacheLazy.isInitialized()) hlsSeekCacheLazy.value.cacheSpace else directorySizeBytes(File(appContext.cacheDir, "raven-hls-seeks"))
        return mp4Bytes + hlsBytes
    }

    fun clearStreamingCache() {
        if (streamCacheLazy.isInitialized()) {
            val cache = streamCacheLazy.value
            cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
        } else {
            runCatching { File(appContext.cacheDir, "raven-streams").deleteRecursively() }
        }
    }

    fun clearSeekCaches() {
        synchronized(seekSessionLock) {
            activeSeekSessionKey = null
        }
        if (seekCacheLazy.isInitialized()) {
            val cache = seekCacheLazy.value
            cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
        } else {
            runCatching { File(appContext.cacheDir, "raven-seeks").deleteRecursively() }
        }
        if (hlsSeekCacheLazy.isInitialized()) {
            val cache = hlsSeekCacheLazy.value
            cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
        } else {
            runCatching { File(appContext.cacheDir, "raven-hls-seeks").deleteRecursively() }
        }
    }

    @Synchronized
    override fun close() {
        downloadCacheMap.values.forEach { runCatching { it.release() } }
        downloadCacheMap.clear()
        if (streamCacheLazy.isInitialized()) runCatching { streamCacheLazy.value.release() }
        if (seekCacheLazy.isInitialized()) runCatching { seekCacheLazy.value.release() }
        if (hlsSeekCacheLazy.isInitialized()) runCatching { hlsSeekCacheLazy.value.release() }
    }

    companion object {
        const val STREAM_CACHE_MAX_BYTES: Long = 512L * 1024L * 1024L
        const val MP4_SEEK_CACHE_MAX_BYTES: Long = 64L * 1024L * 1024L
        const val HLS_SEEK_CACHE_MAX_BYTES: Long = 32L * 1024L * 1024L
    }
}
