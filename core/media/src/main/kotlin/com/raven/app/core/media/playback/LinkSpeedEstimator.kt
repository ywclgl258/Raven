@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.VideoStream
import com.raven.app.core.network.NetworkMonitor
import com.raven.app.core.network.SiteRequestTag
import com.raven.app.core.sitespi.MediaTransportHook
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import java.io.IOException
import java.util.Locale
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Process-local media link bandwidth estimator (in kbps).
 *
 * Invalidates automatically whenever [NetworkMonitor.routeEpoch] advances or samples exceed [ESTIMATE_TTL_MS].
 * Never persists estimates to disk.
 */
class LinkSpeedEstimator(
    private val context: Context?,
    private val routeEpochProvider: () -> Long,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    constructor(context: Context, networkMonitor: NetworkMonitor) : this(
        context = context.applicationContext,
        routeEpochProvider = { networkMonitor.routeEpoch.value },
    )

    private var epoch: Long = routeEpochProvider()
    private var sampledKbps: Int = 0
    private var seededKbps: Int = 0
    private var sampledAtMs: Long = 0L
    private var seededAtMs: Long = 0L

    private class Transfer(
        val routeEpoch: Long,
        var startedAtMs: Long = 0L,
        var bytes: Long = 0L,
    )

    private val transfers = WeakHashMap<DataSource, Transfer>()
    private var playbackMeter: BandwidthMeter? = null

    private inner class PlaybackMeter(
        private val adaptiveMeter: DefaultBandwidthMeter,
    ) : BandwidthMeter by adaptiveMeter, TransferListener {

        override fun getTransferListener(): TransferListener = this

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            synchronized(this@LinkSpeedEstimator) {
                transfers.remove(source)
                val uriText = dataSpec.uri.toString()
                val isPlaylistUri = uriText.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                if (isNetwork &&
                    !dataSpec.isFlagSet(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED) &&
                    !isPlaylistUri
                ) {
                    transfers[source] = Transfer(syncRouteEpoch())
                }
            }
            adaptiveMeter.onTransferInitializing(source, dataSpec, isNetwork)
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            synchronized(this@LinkSpeedEstimator) {
                transfers[source]?.let { transfer ->
                    val contentType = source.responseHeaders.entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value?.firstOrNull()
                    if (isPlaylistContentType(contentType)) {
                        transfers.remove(source)
                    } else {
                        transfer.startedAtMs = clockMs()
                    }
                }
            }
            adaptiveMeter.onTransferStart(source, dataSpec, isNetwork)
        }

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            synchronized(this@LinkSpeedEstimator) {
                transfers[source]?.let { it.bytes += bytesTransferred }
            }
            adaptiveMeter.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            synchronized(this@LinkSpeedEstimator) {
                val transfer = transfers.remove(source)
                if (transfer != null && transfer.startedAtMs > 0L) {
                    val now = clockMs()
                    val elapsedMs = now - transfer.startedAtMs
                    recordSampleLocked(
                        bytes = transfer.bytes,
                        elapsedMs = elapsedMs,
                        measuredOnEpoch = transfer.routeEpoch,
                        nowMs = now,
                    )
                }
            }
            adaptiveMeter.onTransferEnd(source, dataSpec, isNetwork)
        }
    }

    val meter: BandwidthMeter
        @Synchronized get() {
            syncRouteEpoch()
            val appContext = checkNotNull(context) { "Android Context is required to create Media3 BandwidthMeter" }
            return playbackMeter ?: PlaybackMeter(DefaultBandwidthMeter.Builder(appContext).build())
                .also { playbackMeter = it }
        }

    @Synchronized
    fun currentRouteEpoch(): Long = syncRouteEpoch()

    @Synchronized
    fun recordSample(bytes: Long, elapsedMs: Long, measuredOnEpoch: Long = syncRouteEpoch()): Boolean =
        recordSampleLocked(bytes, elapsedMs, measuredOnEpoch, clockMs())

    @Synchronized
    fun seed(kbps: Int, measuredOnEpoch: Long): Boolean {
        if (kbps <= 0 || measuredOnEpoch != syncRouteEpoch()) return false
        seededKbps = kbps
        seededAtMs = clockMs()
        return true
    }

    @Synchronized
    fun estimateKbps(): Int {
        syncRouteEpoch()
        val now = clockMs()
        val sample = sampledKbps.takeIf { it > 0 && isFresh(sampledAtMs, now) } ?: 0
        val probe = seededKbps.takeIf { it > 0 && isFresh(seededAtMs, now) } ?: 0
        return StreamSelector.mergeEstimates(sample, probe)
    }

    fun isKnown(): Boolean = estimateKbps() > 0

    fun describe(): String {
        val value = estimateKbps()
        return if (value <= 0) {
            "尚未测得链路速度"
        } else {
            val mbPerSec = String.format(Locale.US, "%.2f", value / 8.0 / 1024.0)
            "约 $value kbps（$mbPerSec MB/s）"
        }
    }

    private fun recordSampleLocked(
        bytes: Long,
        elapsedMs: Long,
        measuredOnEpoch: Long,
        nowMs: Long,
    ): Boolean {
        if (bytes < MIN_SAMPLE_BYTES || elapsedMs <= 0L || measuredOnEpoch != syncRouteEpoch()) {
            return false
        }
        sampledKbps = ((bytes * 8L) / elapsedMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        sampledAtMs = nowMs
        return sampledKbps > 0
    }

    private fun syncRouteEpoch(): Long {
        val current = routeEpochProvider()
        if (current != epoch) {
            epoch = current
            transfers.clear()
            playbackMeter = null
            sampledKbps = 0
            seededKbps = 0
            sampledAtMs = 0L
            seededAtMs = 0L
        }
        return epoch
    }

    private fun isFresh(recordedAtMs: Long, nowMs: Long): Boolean =
        recordedAtMs == 0L || (nowMs - recordedAtMs) in 0L until ESTIMATE_TTL_MS

    companion object {
        const val MIN_SAMPLE_BYTES: Long = 16L * 1024L
        const val ESTIMATE_TTL_MS: Long = 5L * 60_000L

        fun isPlaylistContentType(value: String?): Boolean =
            when (value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl",
                "application/mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl",
                -> true
                else -> false
            }
    }
}

/**
 * Real media CDN throughput prober using the shared pre-built `MEDIA` [OkHttpClient]
 * (zero per-request client construction).
 */
class MediaBandwidthProber(
    private val mediaClient: OkHttpClient,
    private val siteRegistryProvider: () -> SiteRegistry,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    enum class StartupPolicy { BASELINE, BUDGETED }

    data class ProbeResult(
        val kbps: Int,
        val bytes: Long,
        val elapsedMs: Long,
        val httpStatus: Int,
        val isPlaylist: Boolean,
    ) {
        val trustedKbps: Int? get() = kbps.takeIf { !isPlaylist && bytes >= MIN_TRUSTED_BYTES && it > 0 }
        val recommendedQuality: String? get() = trustedKbps?.let(StreamSelector::recommendedLabel)
        val summary: String
            get() {
                val speed = trustedKbps
                val read = "HTTP $httpStatus · $bytes B / $elapsedMs ms"
                return when {
                    isPlaylist -> "$read · 已读取 HLS 播放列表，未测量媒体分片吞吐"
                    speed == null -> "$read · 样本不足，未测得媒体吞吐"
                    else -> {
                        val mbPerSec = String.format(Locale.US, "%.2f", speed / 8.0 / 1024.0)
                        "$read · 约 $speed kbps（$mbPerSec MB/s）"
                    }
                }
            }
    }

    suspend fun measureStartup(
        siteId: SiteId,
        stream: VideoStream,
        policy: StartupPolicy = StartupPolicy.BUDGETED,
    ): ProbeResult? {
        val hook = siteRegistryProvider().get(siteId)?.mediaTransportHook ?: MediaTransportHook.None
        if (stream.isHls || HlsDownloadSupport.isHlsUrl(stream.url, hook)) return null
        return try {
            when (policy) {
                StartupPolicy.BASELINE -> measure(siteId, stream, SAMPLE_BYTES)
                StartupPolicy.BUDGETED -> withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
                    measure(siteId, stream, STARTUP_SAMPLE_BYTES)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            null
        }
    }

    suspend fun measure(
        siteId: SiteId,
        stream: VideoStream,
        limitBytes: Long = SAMPLE_BYTES,
    ): ProbeResult = withContext(dispatchers.io) {
        require(limitBytes > 0L) { "limitBytes must be positive" }
        val hook = siteRegistryProvider().get(siteId)?.mediaTransportHook ?: MediaTransportHook.None
        val playlistUrl = stream.isHls || HlsDownloadSupport.isHlsUrl(stream.url, hook)
        val requestBuilder = Request.Builder()
            .url(stream.url)
            .tag(
                SiteRequestTag::class.java,
                SiteRequestTag(
                    siteId = siteId,
                    siteRequest = SiteHttpRequest(
                        url = stream.url,
                        siteId = siteId,
                        kind = SiteRequestKind.MEDIA,
                        headers = stream.headers,
                    ),
                ),
            )
            .header("Cache-Control", "no-store")
        for ((name, value) in stream.headers) {
            if (!name.equals("Authorization", ignoreCase = true) && !name.equals("Cookie", ignoreCase = true)) {
                requestBuilder.header(name, value)
            }
        }
        if (!playlistUrl) {
            requestBuilder.header("Range", "bytes=0-${limitBytes - 1}")
        }
        val call = mediaClient.newCall(requestBuilder.build())
        val response = executeCancellable(call)
        response.use { res ->
            if (!res.isSuccessful) throw IOException("媒体探测 HTTP ${res.code}")
            val source = res.body?.source() ?: throw IOException("媒体没有响应体")
            val startedMs = clockMs()
            val playlist = playlistUrl ||
                HlsDownloadSupport.isHlsUrl(res.request.url.toString(), hook) ||
                LinkSpeedEstimator.isPlaylistContentType(res.header("Content-Type")) ||
                (limitBytes >= HLS_HEADER.size && source.rangeEquals(0, HLS_HEADER))
            val ctx = currentCoroutineContext()
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (total < limitBytes) {
                ctx.ensureActive()
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), limitBytes - total).toInt())
                if (read < 0) break
                total += read
            }
            if (total == 0L) throw IOException("媒体没有返回任何字节")
            val elapsedMs = (clockMs() - startedMs).coerceAtLeast(1L)
            val kbps = ((total * 8L) / elapsedMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            ProbeResult(
                kbps = kbps,
                bytes = total,
                elapsedMs = elapsedMs,
                httpStatus = res.code,
                isPlaylist = playlist,
            )
        }
    }

    private suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                    } else {
                        cont.resume(response)
                    }
                }
            })
        }

    companion object {
        const val SAMPLE_BYTES: Long = 1024L * 1024L
        const val MIN_TRUSTED_BYTES: Long = 128L * 1024L
        const val STARTUP_SAMPLE_BYTES: Long = MIN_TRUSTED_BYTES
        const val STARTUP_TIMEOUT_MS: Long = 2_000L
        private val HLS_HEADER = "#EXTM3U".encodeUtf8()
    }
}
