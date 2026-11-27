@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.playback

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Rational
import androidx.core.net.toUri
import com.raven.app.core.common.log.RavenLog
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.PositionInfo
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.VideoStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Pure A-B loop range evaluator.
 *
 * A pending A point survives seeks; an active A-B range requires `endMs - startMs >= 1_000ms`
 * and is inclusive at A, exclusive at B.
 */
data class VideoLoopRange(
    val startMs: Long = -1L,
    val endMs: Long = -1L,
) {
    val isActive: Boolean get() = startMs >= 0L && (endMs - startMs) >= MIN_RANGE_MS
    val hasStart: Boolean get() = startMs >= 0L

    fun withStart(positionMs: Long): VideoLoopRange = VideoLoopRange(startMs = positionMs.coerceAtLeast(0L), endMs = -1L)

    fun withEnd(positionMs: Long): VideoLoopRange {
        require(startMs >= 0L && (positionMs - startMs) >= MIN_RANGE_MS) {
            "B 点需要比 A 点至少晚 1 秒"
        }
        return copy(endMs = positionMs)
    }

    fun isValidFor(durationMs: Long): Boolean =
        startMs < 0L || (durationMs > startMs && (endMs < 0L || (isActive && endMs <= durationMs)))

    fun contains(positionMs: Long): Boolean = !isActive || positionMs in startMs until endMs

    fun shouldRepeat(positionMs: Long, isPlaying: Boolean): Boolean =
        isActive && isPlaying && positionMs >= endMs

    companion object {
        const val MIN_RANGE_MS: Long = 1_000L
        val NONE: VideoLoopRange = VideoLoopRange()
    }
}

/**
 * Selectable audio or subtitle track option exposed by [PlayerSessionController].
 */
data class MediaTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val selected: Boolean,
)

/**
 * Immutable playback engine state projected to `:feature:player` via [StateFlow].
 */
data class PlayerEngineState(
    val isInitialized: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val isVideoFrameReady: Boolean = false,
    val videoRecoveryGeneration: Long = 0L,
    val siteId: SiteId? = null,
    val mediaId: String? = null,
    val title: String = "",
    val sourceUrl: String = "",
    val currentStream: VideoStream? = null,
    val availableStreams: List<VideoStream> = emptyList(),
    val audioTracks: List<MediaTrackOption> = emptyList(),
    val subtitleTracks: List<MediaTrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val activeBufferPlan: BufferPlan? = null,
    val loopRange: VideoLoopRange = VideoLoopRange.NONE,
    val isOffline: Boolean = false,
    val isLocal: Boolean = false,
    val hasRefreshedExpiredStream: Boolean = false,
    val autoDowngradeCount: Int = 0,
    val lastNotice: String? = null,
    val errorMessage: String? = null,
    val errorCode: Int? = null,
) {
    val aspectRatio: Float?
        get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else null

    val isEnded: Boolean get() = playbackState == Player.STATE_ENDED
}

/**
 * Lifecycle-aware ExoPlayer session controller managing stable quality selection, dynamic buffer-plan
 * rebuilds, media-transition video reset, 1-time expired stream refresh, A-B loop evaluation, PiP aspect
 * ratio calculation, track selection, [MediaSession], and local/offline MP4 seek previews.
 */
class PlayerSessionController(
    private val context: Context,
    private val downloadCaches: DownloadCaches,
    private val linkSpeedEstimator: LinkSpeedEstimator,
    private val transportFactory: MediaTransportDataSourceFactory,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val stallTracker = StallTracker(clockMs)

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var currentTracks: Tracks? = null
    private var activeBufferPlan: BufferPlan? = null
    private var activeHlsSeekSession: HlsSeekSession? = null
    private var activeCacheKey: String? = null
    private var activeSourceRoot: String = ""
    private var activeLocalFilePath: String? = null
    private var activeTitle: String = ""
    private var activeArtworkUrl: String = ""
    private var activeStreamCacheEnabled: Boolean = false
    private var activeSeekCacheEnabled: Boolean = true
    private var lastObservedVideoPositionMs: Long = -1L
    private var lastObservedVideoGeneration: Long = -1L
    @Volatile
    private var loadGeneration: Long = 0L

    private val _state = MutableStateFlow(PlayerEngineState())
    val state: StateFlow<PlayerEngineState> = _state.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updateStallTrackerBuffering()
            syncSnapshot(clearError = playbackState == Player.STATE_READY)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateStallTrackerBuffering()
            syncSnapshot()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncSnapshot()
        }

        override fun onTracksChanged(tracks: Tracks) {
            currentTracks = tracks
            val (audio, subtitles, textDisabled) = extractTrackOptions(
                tracks = tracks,
                textTrackDisabled = C.TRACK_TYPE_TEXT in (player?.trackSelectionParameters?.disabledTrackTypes ?: emptySet()),
            )
            _state.update {
                it.copy(
                    audioTracks = audio,
                    subtitleTracks = subtitles,
                    subtitlesEnabled = !textDisabled && subtitles.any(MediaTrackOption::selected),
                )
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _state.update {
                    it.copy(
                        videoWidth = videoSize.width,
                        videoHeight = videoSize.height,
                        isVideoFrameReady = true,
                    )
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: PositionInfo,
            newPosition: PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            ) {
                beginVideoRecovery(clearRenderedFrame = true)
            }
            syncSnapshot()
        }

        override fun onRenderedFirstFrame() {
            _state.update { it.copy(isVideoFrameReady = true) }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.update { it.copy(playbackSpeed = playbackParameters.speed) }
        }

        override fun onPlayerError(error: PlaybackException) {
            RavenLog.e(
                "Player",
                "playback error code=${error.errorCodeName} (${error.errorCode}) " +
                    "state=${_state.value.siteId?.value}/${_state.value.mediaId}",
                error,
            )
            stallTracker.onBufferingChanged(false, clockMs())
            _state.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = playbackErrorMessage(error),
                    errorCode = error.errorCode,
                )
            }
        }
    }

    private fun updateStallTrackerBuffering() {
        val exo = player ?: return
        val activeBuffering = exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady
        stallTracker.onBufferingChanged(activeBuffering, clockMs())
    }

    fun currentPlayer(): ExoPlayer? = player

    /**
     * Starts or switches to an online video session with bandwidth-aware quality selection and
     * dynamic [BufferPlan] window sizing. Never mutates the user's saved preferred quality.
     */
    fun loadOnline(
        siteId: SiteId,
        mediaId: String,
        sourceUrl: String,
        streams: List<VideoStream>,
        preferredQuality: String,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        streamCacheEnabled: Boolean = false,
        seekCacheEnabled: Boolean = true,
        bandwidthKbps: Int = linkSpeedEstimator.estimateKbps(),
        title: String = "",
        artworkUrl: String = "",
    ): VideoStream {
        require(streams.isNotEmpty()) { "没有可播放的视频流" }
        loadGeneration += 1L
        val chosen = StreamSelector.pick(streams, preferredQuality, bandwidthKbps)
        val isNewMedia = _state.value.siteId != siteId || _state.value.mediaId != mediaId
        if (isNewMedia) {
            stallTracker.reset()
            beginVideoRecovery(clearRenderedFrame = true)
        }

        activeStreamCacheEnabled = streamCacheEnabled
        activeSeekCacheEnabled = seekCacheEnabled
        activeLocalFilePath = null
        activeSourceRoot = ""
        activeTitle = title
        activeArtworkUrl = artworkUrl
        activeCacheKey = cacheKey(siteId, mediaId, chosen.quality.ifBlank { chosen.label })

        val plan = BufferPlanner.planFor(chosen, bandwidthKbps)
        val speed = _state.value.playbackSpeed
        val exo = ensurePlayer(plan, speed)
        val mediaSource = buildOnlineMediaSource(
            siteId = siteId,
            mediaId = mediaId,
            stream = chosen,
            cacheKey = activeCacheKey.orEmpty(),
            streamCacheEnabled = streamCacheEnabled,
            seekCacheEnabled = seekCacheEnabled,
            title = title,
            artworkUrl = artworkUrl,
        )

        exo.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
        exo.playWhenReady = autoPlay
        exo.prepare()

        _state.update {
            it.copy(
                isInitialized = true,
                siteId = siteId,
                mediaId = mediaId,
                title = title.ifBlank { if (isNewMedia) "" else it.title },
                sourceUrl = sourceUrl,
                currentStream = chosen,
                availableStreams = streams,
                videoWidth = if (isNewMedia) 0 else it.videoWidth,
                videoHeight = if (isNewMedia) 0 else it.videoHeight,
                audioTracks = if (isNewMedia) emptyList() else it.audioTracks,
                subtitleTracks = if (isNewMedia) emptyList() else it.subtitleTracks,
                subtitlesEnabled = if (isNewMedia) false else it.subtitlesEnabled,
                activeBufferPlan = plan,
                isOffline = false,
                isLocal = false,
                loopRange = if (isNewMedia) VideoLoopRange.NONE else it.loopRange,
                hasRefreshedExpiredStream = if (isNewMedia) false else it.hasRefreshedExpiredStream,
                autoDowngradeCount = if (isNewMedia) 0 else it.autoDowngradeCount,
                errorMessage = null,
                errorCode = null,
                lastNotice = null,
            )
        }
        syncSnapshot()
        return chosen
    }

    /**
     * Pre-warms offline cache metadata on IO before binding ExoPlayer on the main thread.
     */
    suspend fun loadOfflineAsync(
        siteId: SiteId,
        mediaId: String,
        sourceUrl: String,
        cacheKey: String,
        sourceRoot: String = "",
        qualityLabel: String = "",
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        title: String = "",
        artworkUrl: String = "",
    ) {
        val hlsUri = withContext(dispatchers.io) { downloadCaches.hlsUri(cacheKey, sourceRoot) }
        withContext(dispatchers.main) {
            loadOfflineInternal(
                siteId = siteId,
                mediaId = mediaId,
                sourceUrl = sourceUrl,
                cacheKey = cacheKey,
                sourceRoot = sourceRoot,
                qualityLabel = qualityLabel,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
                title = title,
                artworkUrl = artworkUrl,
                preResolvedHlsUri = hlsUri,
            )
        }
    }

    /**
     * Plays a downloaded video strictly from the permanent offline [DownloadCaches] (`upstream = null`).
     */
    fun loadOffline(
        siteId: SiteId,
        mediaId: String,
        sourceUrl: String,
        cacheKey: String,
        sourceRoot: String = "",
        qualityLabel: String = "",
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        title: String = "",
        artworkUrl: String = "",
    ) {
        loadOfflineInternal(
            siteId = siteId,
            mediaId = mediaId,
            sourceUrl = sourceUrl,
            cacheKey = cacheKey,
            sourceRoot = sourceRoot,
            qualityLabel = qualityLabel,
            startPositionMs = startPositionMs,
            autoPlay = autoPlay,
            title = title,
            artworkUrl = artworkUrl,
            preResolvedHlsUri = downloadCaches.hlsUri(cacheKey, sourceRoot),
        )
    }

    private fun loadOfflineInternal(
        siteId: SiteId,
        mediaId: String,
        sourceUrl: String,
        cacheKey: String,
        sourceRoot: String,
        qualityLabel: String,
        startPositionMs: Long,
        autoPlay: Boolean,
        title: String,
        artworkUrl: String,
        preResolvedHlsUri: String?,
    ) {
        loadGeneration += 1L
        stallTracker.reset()
        closeActiveHlsSeekSession()
        beginVideoRecovery(clearRenderedFrame = true)
        activeLocalFilePath = null
        activeCacheKey = cacheKey
        activeSourceRoot = sourceRoot
        activeTitle = title
        activeArtworkUrl = artworkUrl

        val isHls = preResolvedHlsUri != null
        val effectiveUri = preResolvedHlsUri ?: sourceUrl.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: "https://raven.local/offline"
        val stream = VideoStream(
            quality = qualityLabel.ifBlank { cacheKey.substringAfterLast(':') },
            label = qualityLabel.ifBlank { cacheKey.substringAfterLast(':') },
            url = effectiveUri,
            isHls = isHls,
        )
        val plan = BufferPlanner.STANDARD_720P_PLAN
        val exo = ensurePlayer(plan, _state.value.playbackSpeed)
        val metadata = buildMediaMetadata(siteId, mediaId, title, artworkUrl)

        val mediaSource: MediaSource = if (isHls) {
            val hlsFactory = downloadCaches.createHlsDataSourceFactory(
                offline = true,
                siteId = siteId,
                sourceRoot = sourceRoot,
                hlsKey = cacheKey,
            )
            val mediaItem = MediaItem.Builder()
                .setUri(effectiveUri)
                .setMediaId("${siteId.value}:$mediaId")
                .setMediaMetadata(metadata)
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                .build()
            HlsMediaSource.Factory(hlsFactory).createMediaSource(mediaItem)
        } else {
            val factory = downloadCaches.createPlaybackDataSourceFactory(
                offline = true,
                siteId = siteId,
                sourceRoot = sourceRoot,
            )
            val mediaItem = MediaItem.Builder()
                .setUri(effectiveUri)
                .setMediaId("${siteId.value}:$mediaId")
                .setMediaMetadata(metadata)
                .setCustomCacheKey(cacheKey)
                .build()
            DefaultMediaSourceFactory(factory).createMediaSource(mediaItem)
        }

        exo.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
        exo.playWhenReady = autoPlay
        exo.prepare()

        _state.update {
            it.copy(
                isInitialized = true,
                siteId = siteId,
                mediaId = mediaId,
                title = title,
                sourceUrl = sourceUrl,
                currentStream = stream,
                availableStreams = listOf(stream),
                videoWidth = 0,
                videoHeight = 0,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                subtitlesEnabled = false,
                activeBufferPlan = plan,
                loopRange = VideoLoopRange.NONE,
                isOffline = true,
                isLocal = false,
                hasRefreshedExpiredStream = false,
                autoDowngradeCount = 0,
                errorMessage = null,
                errorCode = null,
                lastNotice = null,
            )
        }
        syncSnapshot()
    }

    /**
     * Plays a locally imported video file directly from disk with zero network access.
     */
    fun loadLocalFile(
        siteId: SiteId,
        mediaId: String,
        filePath: String,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        title: String = "",
        artworkUrl: String = "",
    ) {
        val file = File(filePath)
        require(file.isFile && file.canRead()) { "本地视频文件不可用：$filePath" }
        loadGeneration += 1L
        stallTracker.reset()
        closeActiveHlsSeekSession()
        beginVideoRecovery(clearRenderedFrame = true)
        activeLocalFilePath = file.absolutePath
        activeCacheKey = null
        activeSourceRoot = ""
        activeTitle = title
        activeArtworkUrl = artworkUrl

        val plan = BufferPlanner.STANDARD_720P_PLAN
        val exo = ensurePlayer(plan, _state.value.playbackSpeed)
        val uri = Uri.fromFile(file)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId("${siteId.value}:$mediaId")
            .setMediaMetadata(buildMediaMetadata(siteId, mediaId, title, artworkUrl))
            .build()
        val mediaSource = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext))
            .createMediaSource(mediaItem)

        exo.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
        exo.playWhenReady = autoPlay
        exo.prepare()

        val localStream = VideoStream(quality = "local", label = "本地视频", url = uri.toString())
        _state.update {
            it.copy(
                isInitialized = true,
                siteId = siteId,
                mediaId = mediaId,
                title = title,
                sourceUrl = uri.toString(),
                currentStream = localStream,
                availableStreams = listOf(localStream),
                videoWidth = 0,
                videoHeight = 0,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                subtitlesEnabled = false,
                activeBufferPlan = plan,
                loopRange = VideoLoopRange.NONE,
                isOffline = true,
                isLocal = true,
                hasRefreshedExpiredStream = false,
                autoDowngradeCount = 0,
                errorMessage = null,
                errorCode = null,
                lastNotice = null,
            )
        }
        syncSnapshot()
    }

    /**
     * Switches to another [VideoStream] for the current video, rebuilding ExoPlayer seamlessly
     * ONLY if the [BufferPlan.signature] changes.
     */
    fun switchStream(
        stream: VideoStream,
        preservePosition: Boolean = true,
        notice: String? = null,
    ) {
        val snapshot = _state.value
        val siteId = snapshot.siteId ?: return
        val mediaId = snapshot.mediaId ?: return
        if (snapshot.isOffline || snapshot.isLocal) return

        val resumePos = if (preservePosition) (player?.currentPosition ?: snapshot.positionMs).coerceAtLeast(0L) else 0L
        val wasPlaying = player?.playWhenReady ?: snapshot.isPlaying
        val speed = player?.playbackParameters?.speed ?: snapshot.playbackSpeed
        val key = cacheKey(siteId, mediaId, stream.quality.ifBlank { stream.label })
        // Keep the last painted frame while the new stream prepares instead of blanking the surface.
        beginVideoRecovery(clearRenderedFrame = false)
        activeCacheKey = key

        val plan = BufferPlanner.planFor(stream, linkSpeedEstimator.estimateKbps())
        val exo = ensurePlayer(plan, speed)
        val mediaSource = buildOnlineMediaSource(
            siteId = siteId,
            mediaId = mediaId,
            stream = stream,
            cacheKey = key,
            streamCacheEnabled = activeStreamCacheEnabled,
            seekCacheEnabled = activeSeekCacheEnabled,
        )

        exo.setMediaSource(mediaSource, resumePos)
        exo.playWhenReady = wasPlaying
        exo.prepare()

        _state.update {
            it.copy(
                currentStream = stream,
                activeBufferPlan = plan,
                errorMessage = null,
                errorCode = null,
                lastNotice = notice,
            )
        }
        syncSnapshot()
    }

    /**
     * Performs a 1-time expired stream URL refresh, preserving playback position and quality tier.
     * Returns `true` if a fresh stream was loaded.
     */
    suspend fun refreshExpiredStream(
        refresher: suspend () -> List<VideoStream>,
    ): Boolean {
        val snapshot = _state.value
        val startGeneration = loadGeneration
        if (snapshot.isOffline || snapshot.isLocal || snapshot.hasRefreshedExpiredStream) return false
        val previous = snapshot.currentStream ?: return false
        _state.update { it.copy(hasRefreshedExpiredStream = true) }

        val freshStreams = try {
            refresher()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
        if (freshStreams.isEmpty()) return false
        val selected = StreamSelector.afterRefresh(freshStreams, previous, linkSpeedEstimator.estimateKbps())
        return withContext(dispatchers.main) {
            val current = _state.value
            if (loadGeneration != startGeneration ||
                current.siteId != snapshot.siteId ||
                current.mediaId != snapshot.mediaId
            ) {
                return@withContext false
            }
            _state.update { it.copy(availableStreams = freshStreams) }
            switchStream(selected, preservePosition = true, notice = "播放地址已自动刷新")
            true
        }
    }

    fun play() {
        player?.playWhenReady = true
        player?.play()
        syncSnapshot()
    }

    fun pause() {
        player?.pause()
        syncSnapshot()
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        syncSnapshot()
    }

    fun seekTo(positionMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0L }
        val clamped = if (duration != null) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        exo.seekTo(clamped)
        beginVideoRecovery(clearRenderedFrame = true)
        // Manual seek outside an active A-B loop clears the loop range.
        val loop = _state.value.loopRange
        if (loop.isActive && !loop.contains(clamped)) {
            _state.update { it.copy(loopRange = VideoLoopRange.NONE) }
        }
        syncSnapshot()
    }

    fun seekBy(deltaMs: Long) {
        val current = player?.currentPosition ?: _state.value.positionMs
        seekTo(current + deltaMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        player?.setPlaybackSpeed(clamped)
        _state.update { it.copy(playbackSpeed = clamped) }
    }

    // ---- A-B Loop Range ----

    fun setLoopStart(positionMs: Long = currentPositionMs()): VideoLoopRange {
        val updated = _state.value.loopRange.withStart(positionMs)
        _state.update { it.copy(loopRange = updated) }
        return updated
    }

    fun setLoopEnd(positionMs: Long = currentPositionMs()): VideoLoopRange {
        val updated = _state.value.loopRange.withEnd(positionMs)
        _state.update { it.copy(loopRange = updated) }
        return updated
    }

    fun clearLoop() {
        _state.update { it.copy(loopRange = VideoLoopRange.NONE) }
    }

    /**
     * Periodic tick (recommended ~100ms while playing in foreground) checking A-B loop repeat
     * and duration validity. Returns `true` when it jumped back to point A.
     */
    fun evaluateLoopTick(): Boolean {
        val exo = player ?: return false
        syncSnapshot()
        val duration = exo.duration.takeIf { it > 0L } ?: 0L
        val pos = exo.currentPosition.coerceAtLeast(0L)
        val loop = _state.value.loopRange
        if (duration > 0L && !loop.isValidFor(duration)) {
            _state.update { it.copy(loopRange = VideoLoopRange.NONE) }
            return false
        }
        if (loop.shouldRepeat(pos, exo.isPlaying)) {
            beginVideoRecovery(clearRenderedFrame = true)
            exo.seekTo(loop.startMs)
            syncSnapshot()
            return true
        }
        return false
    }

    /**
     * Stall observation is retained for diagnostics, but never changes the selected stream.
     * Recovery stays on the same quality and lets ExoPlayer buffer or report the actual error.
     */
    fun evaluateStallTick(): VideoStream? {
        val snapshot = _state.value
        if (snapshot.isOffline || snapshot.isLocal) return null
        val exo = player ?: return null
        stallTracker.evaluate(
            positionMs = exo.currentPosition.coerceAtLeast(0L),
            isBuffering = exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady,
            nowMs = clockMs(),
        )
        return null
    }

    /**
     * Player errors are localized and actionable; raw ExoPlayer text ("Source error") tells a
     * user nothing they can act on.
     */
    private fun playbackErrorMessage(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> "网络连接中断，请检查网络后重试"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "视频地址已失效或需要登录，请刷新后重试"
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "当前设备无法解码该清晰度，试试较低画质"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "视频格式不支持，可换一个清晰度"
        else -> error.localizedMessage ?: "播放失败（${error.errorCodeName}）"
    }

    private fun beginVideoRecovery(clearRenderedFrame: Boolean) {
        if (clearRenderedFrame) {
            player?.clearVideoSurface()
            // A detached texture/surface must be rebound before ExoPlayer can render a new frame.
            _state.update { it.copy(videoRecoveryGeneration = it.videoRecoveryGeneration + 1) }
        }
        _state.update { it.copy(isVideoFrameReady = false) }
    }

    /**
     * Computes a safe Android Picture-in-Picture aspect [Rational] clamped to `[1/2.39, 2.39/1]`.
     */
    fun pipAspectRatio(): Rational? {
        val pair = computePipAspectRatio(_state.value.videoWidth, _state.value.videoHeight) ?: return null
        return Rational(pair.first, pair.second)
    }

    /**
     * Opens a [LocalSeekPreviewDecoder] for either the active local video file OR a complete
     * offline downloaded MP4 in [DownloadCaches]. Returns `null` for online or HLS streams.
     */
    fun openSeekPreview(): LocalSeekPreviewDecoder? {
        activeLocalFilePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead()) {
                return LocalSeekPreviewDecoder.forLocalFile(file, dispatchers)
            }
        }
        val key = activeCacheKey
        if (_state.value.isOffline && !key.isNullOrBlank()) {
            return LocalSeekPreviewDecoder.forOfflineMp4(downloadCaches, key, activeSourceRoot, dispatchers)
        }
        return null
    }

    fun clearNotice() {
        _state.update { it.copy(lastNotice = null) }
    }

    /**
     * Drops a stale playback error before a retry so the overlay can show its own loading state
     * instead of leaving the old error card on screen.
     */
    fun clearPlaybackError() {
        _state.update { it.copy(errorMessage = null, errorCode = null) }
    }

    fun currentPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: _state.value.positionMs

    fun syncSnapshot(clearError: Boolean = false) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0L } ?: _state.value.durationMs
        val videoPosition = exo.videoFormat?.let { exo.currentPosition.coerceAtLeast(0L) }
        val recoveryGeneration = _state.value.videoRecoveryGeneration
        val hasFreshVideoPosition = videoPosition != null &&
            exo.playbackState == Player.STATE_READY &&
            (lastObservedVideoGeneration != recoveryGeneration ||
                videoPosition != lastObservedVideoPositionMs)
        if (videoPosition != null) {
            lastObservedVideoPositionMs = videoPosition
            lastObservedVideoGeneration = recoveryGeneration
        }
        _state.update { current ->
            current.copy(
                isInitialized = true,
                isPlaying = exo.isPlaying,
                isBuffering = exo.playbackState == Player.STATE_BUFFERING,
                playbackState = exo.playbackState,
                positionMs = exo.currentPosition.coerceAtLeast(0L),
                bufferedPositionMs = exo.bufferedPosition.coerceAtLeast(0L),
                durationMs = duration,
                playbackSpeed = exo.playbackParameters.speed,
                isVideoFrameReady = current.isVideoFrameReady || hasFreshVideoPosition,
                errorMessage = if (clearError) null else current.errorMessage,
                errorCode = if (clearError) null else current.errorCode,
            )
        }
    }

    fun release() {
        loadGeneration += 1L
        closeActiveHlsSeekSession()
        runCatching { mediaSession?.release() }
        mediaSession = null
        currentTracks = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        activeBufferPlan = null
        activeCacheKey = null
        activeSourceRoot = ""
        activeLocalFilePath = null
        activeTitle = ""
        activeArtworkUrl = ""
        stallTracker.reset()
        _state.value = PlayerEngineState()
    }

    fun selectAudioTrack(groupIndex: Int, trackIndex: Int) {
        val exo = player ?: return
        val tracks = currentTracks ?: exo.currentTracks
        val group = tracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != C.TRACK_TYPE_AUDIO || trackIndex !in 0 until group.length) return
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
        val exo = player ?: return
        val tracks = currentTracks ?: exo.currentTracks
        val group = tracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != C.TRACK_TYPE_TEXT || trackIndex !in 0 until group.length) return
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    fun disableSubtitles() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun ensurePlayer(plan: BufferPlan, speed: Float): ExoPlayer {
        val existing = player
        if (existing != null) {
            // Quality switches (including Hanime1 2K) must reuse the player: recreating it detaches
            // the video surface, so the screen stays black until the UI rebinds it.
            activeBufferPlan = plan
            existing.setPlaybackSpeed(speed.coerceIn(MIN_SPEED, MAX_SPEED))
            return existing
        }
        runCatching { mediaSession?.release() }
        mediaSession = null
        existing?.removeListener(playerListener)
        existing?.clearVideoSurface()
        existing?.release()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val memoryClassMb = (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.memoryClass
            ?.coerceAtLeast(64)
            ?: 256
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)

        val created = ExoPlayer.Builder(appContext, renderersFactory)
            .setLoadControl(BufferPlanner.createUnifiedLoadControl(memoryClassMb))
            .setBandwidthMeter(linkSpeedEstimator.meter)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setPlaybackSpeed(speed.coerceIn(MIN_SPEED, MAX_SPEED))
                addListener(playerListener)
            }
        player = created
        activeBufferPlan = plan
        currentTracks = null
        mediaSession = runCatching {
            MediaSession.Builder(appContext, created)
                .setId("raven_player_${System.identityHashCode(this)}")
                .build()
        }.getOrNull()
        return created
    }

    private fun buildOnlineMediaSource(
        siteId: SiteId,
        mediaId: String,
        stream: VideoStream,
        cacheKey: String,
        streamCacheEnabled: Boolean,
        seekCacheEnabled: Boolean,
        title: String = activeTitle,
        artworkUrl: String = activeArtworkUrl,
    ): MediaSource {
        closeActiveHlsSeekSession()
        val mimeHint = transportFactory.resolveMimeTypeHint(siteId, stream.url, stream.mimeTypeHint)
        val isHls = stream.isHls || mimeHint == androidx.media3.common.MimeTypes.APPLICATION_M3U8

        val itemBuilder = MediaItem.Builder()
            .setUri(stream.url)
            .setMediaId("${siteId.value}:$mediaId")
            .setMediaMetadata(buildMediaMetadata(siteId, mediaId, title, artworkUrl))
            .setCustomCacheKey(cacheKey)
        if (mimeHint != null) {
            itemBuilder.setMimeType(mimeHint)
        }
        val mediaItem = itemBuilder.build()

        return if (isHls) {
            val hlsSeek = if (seekCacheEnabled) {
                downloadCaches.openHlsSeekSession().also { activeHlsSeekSession = it }
            } else {
                null
            }
            val hlsDataSourceFactory = downloadCaches.createHlsDataSourceFactory(
                offline = false,
                siteId = siteId,
                hlsKey = cacheKey,
                cacheStreaming = streamCacheEnabled,
                hlsSeek = hlsSeek,
                extraHeaders = stream.headers,
            )
            HlsMediaSource.Factory(hlsDataSourceFactory).apply {
                setAllowChunklessPreparation(true)
                if (hlsSeek != null) {
                    setPlaylistParserFactory(hlsSeek.playlistParserFactory)
                }
            }.createMediaSource(mediaItem)
        } else {
            val sessionKey = if (seekCacheEnabled) cacheKey else null
            val factory = downloadCaches.createPlaybackDataSourceFactory(
                offline = false,
                siteId = siteId,
                cacheStreaming = streamCacheEnabled,
                sessionKey = sessionKey,
                extraHeaders = stream.headers,
            )
            DefaultMediaSourceFactory(factory).createMediaSource(mediaItem)
        }
    }

    private fun closeActiveHlsSeekSession() {
        activeHlsSeekSession?.let { runCatching { it.close() } }
        activeHlsSeekSession = null
    }

    private fun buildMediaMetadata(
        siteId: SiteId,
        mediaId: String,
        title: String,
        artworkUrl: String,
    ): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(title.ifBlank { mediaId })
            .setArtist(siteId.value)
        if (artworkUrl.startsWith("http://", ignoreCase = true) ||
            artworkUrl.startsWith("https://", ignoreCase = true) ||
            artworkUrl.startsWith("file://", ignoreCase = true)
        ) {
            runCatching { artworkUrl.toUri() }.getOrNull()?.let { builder.setArtworkUri(it) }
        }
        return builder.build()
    }

    companion object {
        const val MIN_SPEED: Float = 0.25f
        const val MAX_SPEED: Float = 4.0f
        const val MIN_PIP_RATIO: Float = 1f / 2.39f
        const val MAX_PIP_RATIO: Float = 2.39f

        fun cacheKey(siteId: SiteId, mediaId: String, quality: String): String =
            "${siteId.value}:$mediaId:$quality"

        internal fun extractTrackOptions(
            tracks: Tracks,
            textTrackDisabled: Boolean = false,
        ): Triple<List<MediaTrackOption>, List<MediaTrackOption>, Boolean> {
            val audio = mutableListOf<MediaTrackOption>()
            val subtitles = mutableListOf<MediaTrackOption>()
            tracks.groups.forEachIndexed { groupIdx, group ->
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        for (trackIdx in 0 until group.length) {
                            if (!group.isTrackSupported(trackIdx)) continue
                            val fmt = group.getTrackFormat(trackIdx)
                            val label = fmt.label?.takeIf(String::isNotBlank)
                                ?: fmt.language?.takeIf(String::isNotBlank)
                                ?: "音轨 ${audio.size + 1}"
                            audio += MediaTrackOption(
                                groupIndex = groupIdx,
                                trackIndex = trackIdx,
                                label = label,
                                language = fmt.language,
                                selected = group.isTrackSelected(trackIdx),
                            )
                        }
                    }
                    C.TRACK_TYPE_TEXT -> {
                        for (trackIdx in 0 until group.length) {
                            if (!group.isTrackSupported(trackIdx)) continue
                            val fmt = group.getTrackFormat(trackIdx)
                            val label = fmt.label?.takeIf(String::isNotBlank)
                                ?: fmt.language?.takeIf(String::isNotBlank)
                                ?: "字幕 ${subtitles.size + 1}"
                            subtitles += MediaTrackOption(
                                groupIndex = groupIdx,
                                trackIndex = trackIdx,
                                label = label,
                                language = fmt.language,
                                selected = !textTrackDisabled && group.isTrackSelected(trackIdx),
                            )
                        }
                    }
                }
            }
            return Triple(audio, subtitles, textTrackDisabled)
        }

        /**
         * Pure-Kotlin PiP aspect ratio numerator/denominator pair clamped to Android's strict
         * `[1/2.39, 2.39]` PiP bounds so `PictureInPictureParams.Builder.setAspectRatio` never throws.
         */
        fun computePipAspectRatio(width: Int, height: Int): Pair<Int, Int>? {
            if (width <= 0 || height <= 0) return null
            val raw = width.toFloat() / height.toFloat()
            val clamped = raw.coerceIn(MIN_PIP_RATIO, MAX_PIP_RATIO)
            val numerator = (clamped * 1_000f).roundToInt().coerceIn(419, 2_390)
            return numerator to 1_000
        }
    }
}
