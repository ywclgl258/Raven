package com.raven.app.core.media.playback

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.graphics.scale
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Conflated/serialized seek-bar thumbnail decoder supporting both:
 * 1. Locally imported video files ([forLocalFile]), and
 * 2. Complete offline downloaded progressive MP4s in Media3 cache ([forOfflineMp4]).
 *
 * Maintains a 12-frame access-order LRU cache at 160x90 scaled resolution and releases the
 * underlying [MediaMetadataRetriever] cleanly on [close].
 */
class LocalSeekPreviewDecoder private constructor(
    private val openRetriever: (MediaMetadataRetriever) -> Unit,
    private val closeBackingSource: () -> Unit = {},
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
) {
    private val mutex = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var triedOpening: Boolean = false
    private var closed: Boolean = false
    private val frameCache = LinkedHashMap<Long, Bitmap?>(MAX_CACHED_FRAMES, 0.75f, true)

    suspend fun frameAt(positionMs: Long, durationMs: Long = Long.MAX_VALUE): Bitmap? =
        withContext(dispatchers.io) {
            val quantizedMs = if (durationMs in 1L until Long.MAX_VALUE) {
                framePosition(positionMs, durationMs)
            } else {
                (positionMs.coerceAtLeast(0L) / STEP_MS) * STEP_MS
            }
            mutex.withLock {
                if (closed) return@withLock null
                if (frameCache.containsKey(quantizedMs)) {
                    return@withLock frameCache[quantizedMs]
                }
                val decoded = try {
                    (retriever ?: openLocked())?.let { media -> decodeFrame(media, quantizedMs * 1_000L) }
                } catch (_: IOException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                frameCache[quantizedMs] = decoded
                if (frameCache.size > MAX_CACHED_FRAMES) {
                    val eldest = frameCache.entries.iterator()
                    if (eldest.hasNext()) {
                        eldest.next()
                        eldest.remove()
                    }
                }
                decoded
            }
        }

    suspend fun close() = withContext(NonCancellable + dispatchers.io) {
        mutex.withLock {
            closed = true
            frameCache.clear()
            retriever?.let { media -> runCatching { media.release() } }
            retriever = null
            runCatching { closeBackingSource() }
        }
    }

    private fun openLocked(): MediaMetadataRetriever? {
        if (triedOpening || closed) return null
        triedOpening = true
        val media = MediaMetadataRetriever()
        return try {
            openRetriever(media)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                val width = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0L
                val height = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0L
                if (width <= 0L || height <= 0L || width > MAX_UNSCALED_PIXELS / height) {
                    return null
                }
            }
            retriever = media
            media
        } catch (_: Throwable) {
            null
        } finally {
            if (retriever == null) {
                runCatching { media.release() }
            }
        }
    }

    private fun decodeFrame(media: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return media.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                PREVIEW_WIDTH,
                PREVIEW_HEIGHT,
            )
        }
        val frame = media.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scale = minOf(1f, PREVIEW_WIDTH.toFloat() / frame.width, PREVIEW_HEIGHT.toFloat() / frame.height)
        if (scale >= 1f) return frame
        return try {
            frame.scale(
                (frame.width * scale).roundToInt().coerceAtLeast(1),
                (frame.height * scale).roundToInt().coerceAtLeast(1),
            )
        } finally {
            frame.recycle()
        }
    }

    companion object {
        const val PREVIEW_WIDTH: Int = 160
        const val PREVIEW_HEIGHT: Int = 90
        const val MAX_CACHED_FRAMES: Int = 12
        const val STEP_MS: Long = 500L
        private const val MAX_UNSCALED_PIXELS: Long = 1_920L * 1_080L

        fun framePosition(positionMs: Long, durationMs: Long): Long =
            positionMs.coerceIn(0L, (durationMs - 1L).coerceAtLeast(0L)) / STEP_MS * STEP_MS

        fun forLocalFile(
            file: File,
            dispatchers: AppDispatchers = DefaultAppDispatchers(),
        ): LocalSeekPreviewDecoder = LocalSeekPreviewDecoder(
            openRetriever = { media ->
                FileInputStream(file).use { input -> media.setDataSource(input.fd) }
            },
            dispatchers = dispatchers,
        )

        fun forOfflineMediaDataSource(
            dataSource: MediaDataSource,
            dispatchers: AppDispatchers = DefaultAppDispatchers(),
        ): LocalSeekPreviewDecoder = LocalSeekPreviewDecoder(
            openRetriever = { media -> media.setDataSource(dataSource) },
            closeBackingSource = { dataSource.close() },
            dispatchers = dispatchers,
        )

        fun forOfflineMp4(
            downloadCaches: DownloadCaches,
            cacheKey: String,
            sourceRoot: String = "",
            dispatchers: AppDispatchers = DefaultAppDispatchers(),
        ): LocalSeekPreviewDecoder? {
            val dataSource = downloadCaches.openOfflineMp4MediaDataSource(cacheKey, sourceRoot) ?: return null
            return forOfflineMediaDataSource(dataSource, dispatchers)
        }
    }
}
