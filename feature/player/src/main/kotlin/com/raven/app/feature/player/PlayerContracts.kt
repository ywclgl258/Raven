package com.raven.app.feature.player

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.download.OfflineMediaHandle
import com.raven.app.core.media.playback.LocalSeekPreviewDecoder
import com.raven.app.core.media.playback.PlayerEngineState
import com.raven.app.core.media.playback.PlayerSessionController
import com.raven.app.core.media.playback.VideoLoopRange
import com.raven.app.core.media.storage.LocalBook
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.VideoStream
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Compact launch contract for `PlayerActivity` / [VideoPlayerScreen].
 *
 * Uses a UUID [queueToken] pointing into `PlaybackQueueStore` instead of passing large item
 * collections across Android Binder Intent extras.
 */
@Serializable
data class PlayerLaunchArgs(
    val siteId: SiteId,
    val mediaId: String,
    val url: String,
    val title: String = "",
    val coverUrl: String = "",
    val autoplay: Boolean = true,
    val offlineOnly: Boolean = false,
    val queueToken: String? = null,
) {
    val compositeKey: String get() = "${siteId.value}:$mediaId"

    fun writeToIntent(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_SITE_ID, siteId.value)
        putExtra(EXTRA_MEDIA_ID, mediaId)
        putExtra(EXTRA_URL, url)
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_COVER_URL, coverUrl)
        putExtra(EXTRA_AUTOPLAY, autoplay)
        putExtra(EXTRA_OFFLINE_ONLY, offlineOnly)
        if (!queueToken.isNullOrBlank()) {
            putExtra(EXTRA_QUEUE_TOKEN, queueToken)
        } else {
            removeExtra(EXTRA_QUEUE_TOKEN)
        }
    }

    fun toIntent(context: Context, activityClass: Class<*>): Intent =
        writeToIntent(Intent(context, activityClass))

    companion object {
        const val EXTRA_SITE_ID: String = "com.raven.app.player.SITE_ID"
        const val EXTRA_MEDIA_ID: String = "com.raven.app.player.MEDIA_ID"
        const val EXTRA_URL: String = "com.raven.app.player.URL"
        const val EXTRA_TITLE: String = "com.raven.app.player.TITLE"
        const val EXTRA_COVER_URL: String = "com.raven.app.player.COVER_URL"
        const val EXTRA_AUTOPLAY: String = "com.raven.app.player.AUTOPLAY"
        const val EXTRA_OFFLINE_ONLY: String = "com.raven.app.player.OFFLINE_ONLY"
        const val EXTRA_QUEUE_TOKEN: String = "com.raven.app.player.QUEUE_TOKEN"

        fun fromIntent(intent: Intent?): PlayerLaunchArgs? {
            if (intent == null) return null
            val rawSiteId = intent.getStringExtra(EXTRA_SITE_ID)?.trim().orEmpty()
            val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID)?.trim().orEmpty()
            val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
            if (rawSiteId.isEmpty() || (mediaId.isEmpty() && url.isEmpty())) return null
            return PlayerLaunchArgs(
                siteId = SiteId.of(rawSiteId),
                mediaId = mediaId.ifEmpty { url },
                url = url,
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                coverUrl = intent.getStringExtra(EXTRA_COVER_URL).orEmpty(),
                autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, true),
                offlineOnly = intent.getBooleanExtra(EXTRA_OFFLINE_ONLY, false),
                queueToken = intent.getStringExtra(EXTRA_QUEUE_TOKEN)?.takeIf { it.isNotBlank() },
            )
        }

        fun fromMediaItem(
            item: MediaItem,
            autoplay: Boolean = true,
            offlineOnly: Boolean = false,
            queueToken: String? = null,
        ): PlayerLaunchArgs = PlayerLaunchArgs(
            siteId = item.siteId,
            mediaId = item.id,
            url = item.url,
            title = item.title,
            coverUrl = item.coverUrl,
            autoplay = autoplay,
            offlineOnly = offlineOnly,
            queueToken = queueToken,
        )
    }
}

/**
 * Decoupled navigation and host window contract for `:feature:player`.
 *
 * `:app`'s `PlayerActivity` wires these callbacks to PiP, orientation/system-bars fullscreen,
 * window brightness, external browser intents, and `MainActivity` (`FLAG_ACTIVITY_CLEAR_TOP`).
 */
interface PlayerNavigator {
    fun openTagFeed(siteId: SiteId, tag: TagRef)

    fun openAuthorFeed(siteId: SiteId, author: AuthorRef)

    fun openExternalUrl(url: String)

    fun openGallery(
        siteId: SiteId,
        mediaId: String,
        url: String,
        offlineOnly: Boolean = false,
        queueToken: String? = null,
    )

    fun requestPip(aspectRatio: Pair<Int, Int>?)

    fun requestFullscreen(landscape: Boolean)

    fun exitFullscreen()

    /** Sets Activity window brightness (`0.01f..1.0f`, or `< 0f` for system default). */
    fun setWindowBrightness(value: Float)

    fun onClose()

    /** Optional hook when a site requires login before playing a video. */
    fun openSiteAccount(siteId: SiteId) {}

    /** Optional hook when switching videos in-place so the host Activity can keep metadata fresh. */
    fun onActiveMediaChanged(args: PlayerLaunchArgs) {}
}

/**
 * Stable Compose `testTag` constants for UI automation and regression tests.
 */
object PlayerTestTags {
    const val PLAYER_SCREEN: String = "player_screen"
    const val PLAYER_SURFACE: String = "player_surface"
    const val PLAYER_GESTURE_LAYER: String = "player_gesture_layer"
    const val PLAYER_PLAY_PAUSE: String = "player_play_pause"
    const val PLAYER_TIME_LABEL: String = "player_time_label"
    const val PLAYER_SCRUBBER: String = "player_scrubber"
    const val PLAYER_SPEED_PILL: String = "player_speed_pill"
    const val PLAYER_QUALITY_PILL: String = "player_quality_pill"
    const val PLAYER_BOTTOM_QUALITY_PILL: String = "player_bottom_quality_pill"
    const val PLAYER_FULLSCREEN_BUTTON: String = "player_fullscreen_button"
    const val PLAYER_LOCK_BUTTON: String = "player_lock_button"
    const val PLAYER_UNLOCK_PILL: String = "player_unlock_pill"
    const val PLAYER_PIP_BUTTON: String = "player_pip_button"
    const val PLAYER_MORE_BUTTON: String = "player_more_button"
    const val PLAYER_SLEEP_INDICATOR: String = "player_sleep_indicator"
    const val PLAYER_TEMP_SPEED_PILL: String = "player_temp_speed_pill"
    const val PLAYER_NOTICE_BANNER: String = "player_notice_banner"
    const val PLAYER_SETTINGS_SHEET: String = "player_settings_sheet"
    const val PLAYER_SPEED_SHEET: String = "player_speed_sheet"
    const val PLAYER_QUALITY_SHEET: String = "player_quality_sheet"
    const val PLAYER_AB_LOOP_SHEET: String = "player_ab_loop_sheet"
    const val PLAYER_SLEEP_TIMER_SHEET: String = "player_sleep_timer_sheet"
    const val PLAYER_BOOKMARK_SHEET: String = "player_bookmark_sheet"
    const val PLAYER_TAG_SHEET: String = "player_tag_sheet"
    const val PLAYER_DOWNLOAD_SHEET: String = "player_download_sheet"
    const val PLAYER_EXTERNAL_SHEET: String = "player_external_sheet"
    const val PLAYER_DETAIL_PANE: String = "player_detail_pane"
    const val PLAYER_QUEUE_CARD: String = "player_queue_card"
    const val PLAYER_QUEUE_NEXT_BUTTON: String = "player_queue_next_button"
    const val PLAYER_ACTION_WATCH_LATER: String = "player_action_watch_later"
    const val PLAYER_ACTION_FAVORITE: String = "player_action_favorite"
    const val PLAYER_ACTION_DOWNLOAD: String = "player_action_download"
    const val PLAYER_ACTION_BOOKMARK: String = "player_action_bookmark"
    const val PLAYER_ACTION_SHARE: String = "player_action_share"
    const val PLAYER_ACTION_EXTERNAL: String = "player_action_external"
    const val PLAYER_ADD_BOOKMARK_BUTTON: String = "player_add_bookmark_button"
}

/**
 * Abstraction over [PlayerSessionController] enabling pure-JVM unit tests of [PlayerViewModel]
 * while delegating 1-to-1 to Media3 [PlayerSessionController] in production.
 */
interface PlayerPlaybackPort {
    val state: StateFlow<PlayerEngineState>

    fun currentPlayer(): ExoPlayer?

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
    ): VideoStream

    fun loadOffline(
        siteId: SiteId,
        mediaId: String,
        sourceUrl: String,
        cacheKey: String,
        sourceRoot: String = "",
        qualityLabel: String = "",
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
    )

    fun loadLocalFile(
        siteId: SiteId,
        mediaId: String,
        filePath: String,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
    )

    fun switchStream(
        stream: VideoStream,
        preservePosition: Boolean = true,
        notice: String? = null,
    )

    suspend fun refreshExpiredStream(refresher: suspend () -> List<VideoStream>): Boolean

    fun play()

    fun pause()

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    fun setPlaybackSpeed(speed: Float)

    fun setLoopStart(positionMs: Long = currentPositionMs()): VideoLoopRange

    fun setLoopEnd(positionMs: Long = currentPositionMs()): VideoLoopRange

    fun clearLoop()

    fun evaluateLoopTick(): Boolean

    fun evaluateStallTick(): VideoStream?

    fun pipAspectRatioPair(): Pair<Int, Int>?

    fun openSeekPreview(): LocalSeekPreviewDecoder?

    fun clearNotice()

    fun currentPositionMs(): Long

    fun syncSnapshot(clearError: Boolean = false)

    fun selectAudioTrack(groupIndex: Int, trackIndex: Int)

    fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int)

    fun disableSubtitles()

    fun release()
}

@OptIn(UnstableApi::class)
class Media3PlayerPlaybackPort(
    private val controller: PlayerSessionController,
) : PlayerPlaybackPort {
    override val state: StateFlow<PlayerEngineState> get() = controller.state

    override fun currentPlayer(): ExoPlayer? = controller.currentPlayer()

    override fun loadOnline(
        siteId: SiteId,
        mediaId: String,
        sourceUrl: String,
        streams: List<VideoStream>,
        preferredQuality: String,
        startPositionMs: Long,
        autoPlay: Boolean,
        streamCacheEnabled: Boolean,
        seekCacheEnabled: Boolean,
    ): VideoStream = controller.loadOnline(
        siteId = siteId,
        mediaId = mediaId,
        sourceUrl = sourceUrl,
        streams = streams,
        preferredQuality = preferredQuality,
        startPositionMs = startPositionMs,
        autoPlay = autoPlay,
        streamCacheEnabled = streamCacheEnabled,
        seekCacheEnabled = seekCacheEnabled,
    )

    override fun loadOffline(
        siteId: SiteId,
        mediaId: String,
        sourceUrl: String,
        cacheKey: String,
        sourceRoot: String,
        qualityLabel: String,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) = controller.loadOffline(
        siteId = siteId,
        mediaId = mediaId,
        sourceUrl = sourceUrl,
        cacheKey = cacheKey,
        sourceRoot = sourceRoot,
        qualityLabel = qualityLabel,
        startPositionMs = startPositionMs,
        autoPlay = autoPlay,
    )

    override fun loadLocalFile(
        siteId: SiteId,
        mediaId: String,
        filePath: String,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) = controller.loadLocalFile(
        siteId = siteId,
        mediaId = mediaId,
        filePath = filePath,
        startPositionMs = startPositionMs,
        autoPlay = autoPlay,
    )

    override fun switchStream(stream: VideoStream, preservePosition: Boolean, notice: String?) =
        controller.switchStream(stream, preservePosition, notice)

    override suspend fun refreshExpiredStream(refresher: suspend () -> List<VideoStream>): Boolean =
        controller.refreshExpiredStream(refresher)

    override fun play() = controller.play()

    override fun pause() = controller.pause()

    override fun togglePlayPause() = controller.togglePlayPause()

    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    override fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    override fun setPlaybackSpeed(speed: Float) = controller.setPlaybackSpeed(speed)

    override fun setLoopStart(positionMs: Long): VideoLoopRange = controller.setLoopStart(positionMs)

    override fun setLoopEnd(positionMs: Long): VideoLoopRange = controller.setLoopEnd(positionMs)

    override fun clearLoop() = controller.clearLoop()

    override fun evaluateLoopTick(): Boolean = controller.evaluateLoopTick()

    override fun evaluateStallTick(): VideoStream? = controller.evaluateStallTick()

    override fun pipAspectRatioPair(): Pair<Int, Int>? {
        val snapshot = controller.state.value
        return PlayerSessionController.computePipAspectRatio(snapshot.videoWidth, snapshot.videoHeight)
    }

    override fun openSeekPreview(): LocalSeekPreviewDecoder? = controller.openSeekPreview()

    override fun clearNotice() = controller.clearNotice()

    override fun currentPositionMs(): Long = controller.currentPositionMs()

    override fun syncSnapshot(clearError: Boolean) = controller.syncSnapshot(clearError)

    override fun selectAudioTrack(groupIndex: Int, trackIndex: Int) =
        controller.selectAudioTrack(groupIndex, trackIndex)

    override fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) =
        controller.selectSubtitleTrack(groupIndex, trackIndex)

    override fun disableSubtitles() = controller.disableSubtitles()

    override fun release() = controller.release()
}

/**
 * Abstraction over [DownloadCoordinator] and [LocalLibraryImporter] for offline lookup,
 * video download enqueueing, and local imported video resolution.
 */
interface PlayerOfflinePort {
    fun observeDownloadsByMedia(siteId: SiteId, mediaId: String): Flow<List<DownloadItem>>

    suspend fun findOfflineHandle(siteId: SiteId, mediaId: String, kind: MediaKind): OfflineMediaHandle?

    fun enqueueVideo(item: MediaItem, stream: VideoStream, groupName: String = ""): String

    suspend fun enqueueVideoWithPreferredQuality(item: MediaItem, groupName: String = ""): String

    fun getLocalBook(bookId: String): LocalBook?

    fun getLocalVideoFile(bookId: String): File?

    fun localBookToMediaItem(book: LocalBook): MediaItem
}

class CoordinatorPlayerOfflinePort(
    private val downloadCoordinator: DownloadCoordinator,
    private val localLibraryImporter: LocalLibraryImporter,
) : PlayerOfflinePort {
    override fun observeDownloadsByMedia(siteId: SiteId, mediaId: String): Flow<List<DownloadItem>> =
        downloadCoordinator.observeByMedia(siteId, mediaId)

    override suspend fun findOfflineHandle(
        siteId: SiteId,
        mediaId: String,
        kind: MediaKind,
    ): OfflineMediaHandle? = downloadCoordinator.findOfflineHandle(siteId, mediaId, kind)

    override fun enqueueVideo(item: MediaItem, stream: VideoStream, groupName: String): String =
        downloadCoordinator.enqueueVideo(item, stream, groupName = groupName)

    override suspend fun enqueueVideoWithPreferredQuality(item: MediaItem, groupName: String): String =
        downloadCoordinator.enqueueVideoWithPreferredQuality(item, groupName = groupName)

    override fun getLocalBook(bookId: String): LocalBook? = localLibraryImporter.getBook(bookId)

    override fun getLocalVideoFile(bookId: String): File? = localLibraryImporter.getVideoFile(bookId)

    override fun localBookToMediaItem(book: LocalBook): MediaItem =
        localLibraryImporter.toMediaItem(book)
}
