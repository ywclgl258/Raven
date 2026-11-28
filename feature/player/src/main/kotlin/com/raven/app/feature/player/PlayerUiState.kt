package com.raven.app.feature.player

import androidx.compose.runtime.Immutable
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.media.cast.LanCastDevice
import com.raven.app.core.media.playback.MediaTrackOption
import com.raven.app.core.media.playback.VideoLoopRange
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.CommentItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.ExternalPlayerLink
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.PlaybackPreferences
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.VideoDetail
import com.raven.app.core.model.VideoStream
import java.util.Locale
import kotlin.math.abs

/**
 * Rendered tag item with optional Chinese translation, subscription status, and block status.
 */
@Immutable
data class PlayerTagDisplay(
    val tag: TagRef,
    val displayLabel: String,
    val translatedLabel: String? = null,
    val isSubscribed: Boolean = false,
    val isBlocked: Boolean = false,
)

/**
 * Active sequential queue presentation for the currently playing video.
 */
@Immutable
data class PlayerQueueUiState(
    val token: String,
    val title: String,
    val currentIndex: Int,
    val totalCount: Int,
    val items: List<MediaItem>,
    val nextItem: MediaItem?,
    val remainingCount: Int,
    val offlineOnly: Boolean,
    val autoContinueEnabled: Boolean = false,
    val autoContinueCountdownActive: Boolean = false,
    val autoContinueRemainingMs: Long = 0L,
) {
    val hasNext: Boolean get() = nextItem != null

    val autoContinueRemainingSeconds: Int
        get() = if (autoContinueCountdownActive) {
            ((autoContinueRemainingMs.coerceAtLeast(0L) + 999L) / 1_000L).toInt()
        } else {
            0
        }
}

/**
 * Active monotonic sleep timer countdown state (backed by `elapsedRealtime()`).
 */
@Immutable
data class PlayerSleepTimerState(
    val isActive: Boolean = false,
    val durationMinutes: Int = 0,
    val remainingMs: Long = 0L,
) {
    val formattedRemaining: String
        get() {
            if (!isActive || remainingMs <= 0L) return ""
            val totalSeconds = ((remainingMs + 999L) / 1_000L).toInt().coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
}

/**
 * Cycleable video aspect scaling modes supported by [VideoPlayerScreen].
 */
enum class VideoResizeMode(val label: String) {
    FIT("适应"),
    FILL("铺满"),
    ZOOM("裁剪");

    fun next(): VideoResizeMode = when (this) {
        FIT -> FILL
        FILL -> ZOOM
        ZOOM -> FIT
    }
}

/**
 * Single-screen modal bottom sheets hosted by [VideoPlayerScreen] (zero chained modal dialogs).
 */
sealed interface PlayerSheet {
    data object Settings : PlayerSheet
    data object Speed : PlayerSheet
    data object Quality : PlayerSheet
    data object Tracks : PlayerSheet
    data object AbLoop : PlayerSheet
    data object SleepTimer : PlayerSheet
    data object Queue : PlayerSheet
    data class BookmarkEditor(
        val editingBookmark: TimeBookmark? = null,
        val initialPositionMs: Long = 0L,
    ) : PlayerSheet
    data class TagActions(val tag: PlayerTagDisplay) : PlayerSheet
    data object Download : PlayerSheet
    data object ExternalPlayers : PlayerSheet
    data object ShelfPicker : PlayerSheet
    data object Cast : PlayerSheet
}

/**
 * One-shot UI side effects emitted by [PlayerViewModel].
 */
sealed interface PlayerSideEffect {
    data class ShowMessage(val message: String) : PlayerSideEffect
    data class RequestAutoFullscreen(val landscape: Boolean) : PlayerSideEffect
    data class OpenGalleryFromQueue(
        val item: MediaItem,
        val offlineOnly: Boolean,
        val queueToken: String,
    ) : PlayerSideEffect
    data class ActiveMediaChanged(val args: PlayerLaunchArgs) : PlayerSideEffect
}

/**
 * High-frequency playback progress snapshot separated from structural UI state so 200ms position
 * ticks never recompose the detail pane, sheets, or player surface.
 */
@Immutable
data class PlayerProgressSnapshot(
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Complete reactive UI state for [VideoPlayerScreen].
 */
@Immutable
data class PlayerUiState(
    val launchArgs: PlayerLaunchArgs? = null,
    val siteId: SiteId? = null,
    val siteDisplayName: String = "",
    val mediaId: String = "",
    val url: String = "",
    val title: String = "",
    val coverUrl: String = "",
    val isLoadingDetail: Boolean = false,
    val detail: VideoDetail? = null,
    val loadError: String? = null,
    val requiresAuth: Boolean = false,
    // Engine playback snapshot
    val isInitialized: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val isVideoFrameReady: Boolean = false,
    val videoRecoveryGeneration: Long = 0L,
    val currentStream: VideoStream? = null,
    val availableStreams: List<VideoStream> = emptyList(),
    val isOffline: Boolean = false,
    val isLocal: Boolean = false,
    val hasSeekPreview: Boolean = false,
    val playbackError: String? = null,
    val isRefreshingExpiredStream: Boolean = false,
    val hasRefreshedExpiredStream: Boolean = false,
    val autoDowngradeCount: Int = 0,
    // Speed & preferences
    val playbackPreferences: PlaybackPreferences = PlaybackPreferences(),
    val sessionUserSpeed: Float? = null,
    val effectivePlaybackSpeed: Float = 1.0f,
    val isTemporarySpeedActive: Boolean = false,
    val temporarySpeedValue: Float = 2.0f,
    val translateTags: Boolean = true,
    val blockTagsGlobally: Boolean = true,
    val reduceTransparency: Boolean = false,
    val backgroundAudioPlayback: Boolean = false,
    // Tracks, custom shelves & LAN cast
    val audioTracks: List<MediaTrackOption> = emptyList(),
    val subtitleTracks: List<MediaTrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val shelves: List<ShelfWithSummary> = emptyList(),
    val containingShelfIds: Set<String> = emptySet(),
    val castDevices: List<LanCastDevice> = emptyList(),
    val isScanningCast: Boolean = false,
    val activeCastDevice: LanCastDevice? = null,
    // Controls, loop, sleep timer & lock
    val loopRange: VideoLoopRange = VideoLoopRange.NONE,
    val sleepTimer: PlayerSleepTimerState = PlayerSleepTimerState(),
    val isLocked: Boolean = false,
    val isFullscreen: Boolean = false,
    val resumedFromMs: Long? = null,
    val noticeMessage: String? = null,
    // Personal library & social metadata
    val inWatchLater: Boolean = false,
    val isFavorite: Boolean = false,
    val isLiked: Boolean = false,
    val downloadStatus: DownloadStatus? = null,
    val downloadProgress: Float = 0f,
    val isOfflineDownloaded: Boolean = false,
    val authors: List<AuthorRef> = emptyList(),
    val tags: List<PlayerTagDisplay> = emptyList(),
    val characters: List<PlayerTagDisplay> = emptyList(),
    val categories: List<PlayerTagDisplay> = emptyList(),
    val comments: List<CommentItem> = emptyList(),
    val timeBookmarks: List<TimeBookmark> = emptyList(),
    val relatedItems: List<MediaItem> = emptyList(),
    val externalPlayers: List<ExternalPlayerLink> = emptyList(),
    val queue: PlayerQueueUiState? = null,
) {
    val activeQualityLabel: String
        get() = when {
            isLocal -> "本地"
            isOffline -> currentStream?.label?.takeIf { it.isNotBlank() }?.let { "离线 · $it" } ?: "离线"
            else -> currentStream?.label?.takeIf { it.isNotBlank() }
                ?: currentStream?.quality?.takeIf { it.isNotBlank() }
                ?: "自动"
        }

    val effectiveDurationMs: Long
        get() = durationMs.takeIf { it > 0L }
            ?: detail?.item?.durationSeconds?.takeIf { it > 0 }?.let { it * 1_000L }
            ?: 0L

    val showQueueNextOverlay: Boolean
        get() = isEnded && queue?.hasNext == true

    val activeSpeedLabel: String
        get() = formatSpeedLabel(if (isTemporarySpeedActive) temporarySpeedValue else effectivePlaybackSpeed)

    val canChooseQuality: Boolean
        get() = !isOffline && !isLocal && availableStreams.size > 1

    val existingBookmarkGroups: List<String>
        get() = timeBookmarks.mapNotNull { it.groupName.trim().takeIf(String::isNotEmpty) }.distinct()
}

/**
 * Formats playback speeds deterministically without locale decimal commas (`1.0×`, `1.25×`, `2.0×`).
 */
fun formatSpeedLabel(speed: Float): String {
    val safe = if (speed.isFinite() && speed > 0f) speed else 1.0f
    val hundredths = (safe * 100f + 0.5f).toInt()
    val whole = hundredths / 100
    val rem = hundredths % 100
    return when {
        rem == 0 -> "$whole.0×"
        rem % 10 == 0 -> "$whole.${rem / 10}×"
        else -> String.format(Locale.US, "%d.%02d×", whole, rem)
    }
}

/**
 * Rounds slider floats cleanly to [step] increments (e.g., `0.25f` for playback speed).
 */
fun snapFloatToStep(value: Float, step: Float, min: Float, max: Float): Float {
    if (!value.isFinite() || step <= 0f) return min
    val steps = ((value - min) / step + 0.5f).toInt()
    val snapped = (min + steps * step).coerceIn(min, max)
    return if (abs(snapped - min) < 0.001f) min else snapped
}
