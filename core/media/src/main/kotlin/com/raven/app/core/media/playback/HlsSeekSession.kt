@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.playback

import android.net.Uri
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.Closeable
import java.util.UUID

/**
 * Isolated per-playback VOD HLS seek cache session backed by the 32 MiB `hlsSeekCache`.
 *
 * Only caches segments once a parsed [HlsMediaPlaylist] confirms `hasEndTag == true` (VOD),
 * never caching sliding live playlists. Discards all keys for this session prefix once closed
 * and all active segment leases complete.
 */
class HlsSeekSession(
    private val cache: Lazy<SimpleCache>,
    private val allowWrite: ((DataSpec) -> Boolean)? = null,
    private val discardPrefix: (String) -> Unit,
) : Closeable {
    private val prefix: String = "${UUID.randomUUID()}|"
    private var isVod: Boolean? = null
    private var closed: Boolean = false
    private var activeLeases: Int = 0

    val playlistParserFactory: HlsPlaylistParserFactory = object : HlsPlaylistParserFactory {
        private val delegate = DefaultHlsPlaylistParserFactory()

        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
            observe(delegate.createPlaylistParser())

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> =
            observe(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))
    }

    private fun observe(
        parser: ParsingLoadable.Parser<HlsPlaylist>,
    ): ParsingLoadable.Parser<HlsPlaylist> = ParsingLoadable.Parser { uri, inputStream ->
        parser.parse(uri, inputStream).also { playlist ->
            if (playlist is HlsMediaPlaylist) {
                updateVod(playlist.hasEndTag)
            }
        }
    }

    @Synchronized
    private fun updateVod(hasEndTag: Boolean) {
        // Once seen as non-VOD (live/event), never re-enable caching for this session.
        isVod = isVod != false && hasEndTag
    }

    @Synchronized
    private fun acquireLease(): Boolean {
        if (closed || isVod != true) return false
        activeLeases += 1
        return true
    }

    @Synchronized
    private fun releaseLease() {
        activeLeases = (activeLeases - 1).coerceAtLeast(0)
        if (closed && activeLeases == 0) {
            discardPrefix(prefix)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (activeLeases == 0) {
            discardPrefix(prefix)
        }
    }

    fun wrapUpstream(networkFactory: DataSource.Factory): DataSource.Factory {
        fun buildCacheFactory(sinkFactory: DataSink.Factory?): CacheDataSource.Factory =
            CacheDataSource.Factory()
                .setCache(cache.value)
                .setCacheKeyFactory { spec -> prefix + spec.uri }
                .setCacheWriteDataSinkFactory(sinkFactory)
                .setUpstreamDataSourceFactory(networkFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val readWriteFactory by lazy {
            buildCacheFactory(
                CacheDataSink.Factory()
                    .setCache(cache.value)
                    .setFragmentSize(SEGMENT_FRAGMENT_BYTES),
            )
        }
        val readOnlyFactory by lazy { buildCacheFactory(null) }

        return DataSource.Factory {
            object : DataSource {
                private var source: DataSource? = null
                private var leased: Boolean = false
                private val listeners = mutableListOf<TransferListener>()

                override fun addTransferListener(transferListener: TransferListener) {
                    listeners += transferListener
                    source?.addTransferListener(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    leased = acquireLease()
                    try {
                        val chosen = when {
                            !leased -> networkFactory
                            allowWrite?.invoke(dataSpec) == false -> readOnlyFactory
                            else -> readWriteFactory
                        }.createDataSource()
                        source = chosen
                        listeners.forEach(chosen::addTransferListener)
                        val effectiveSpec = if (leased) {
                            dataSpec.buildUpon()
                                .setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                                .build()
                        } else {
                            dataSpec
                        }
                        return chosen.open(effectiveSpec)
                    } catch (failure: Exception) {
                        runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
                        throw failure
                    }
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    checkNotNull(source).read(buffer, offset, length)

                override fun getUri(): Uri? = source?.uri

                override fun getResponseHeaders(): Map<String, List<String>> =
                    source?.responseHeaders.orEmpty()

                override fun close() {
                    try {
                        source?.close()
                    } finally {
                        source = null
                        if (leased) {
                            leased = false
                            releaseLease()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val SEGMENT_FRAGMENT_BYTES: Long = 2L * 1024L * 1024L
    }
}
