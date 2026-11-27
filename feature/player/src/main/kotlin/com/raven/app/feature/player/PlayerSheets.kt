package com.raven.app.feature.player

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSliderRow
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenMorphToggleIcon
import com.raven.app.core.designsystem.component.RavenShelfOption
import com.raven.app.core.designsystem.component.RavenShelfPickerSheet
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.media.cast.LanCastDevice
import com.raven.app.core.media.playback.PlayerSessionController
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.VideoStream
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Label refresh cadence for the A-B sheet's live "current position" readout. */
private const val AB_LOOP_LABEL_REFRESH_MS: Long = 500L

/**
 * Single active modal bottom sheet host for [VideoPlayerScreen].
 * Uses Apple HIG inset grouped [RavenBottomSheet] + [RavenGroupedSection] with zero chained modal dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheetHost(
    activeSheet: PlayerSheet?,
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    onSwitchSheet: (PlayerSheet) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onResetSpeedToDefault: () -> Unit,
    onSelectQuality: (VideoStream, Boolean) -> Unit,
    onSetLoopA: () -> Unit,
    onSetLoopB: () -> Unit,
    onJumpToLoopA: () -> Unit,
    onClearLoop: () -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSelectQueueItem: (Int) -> Boolean,
    onRemoveQueueItem: (Int) -> Boolean,
    onUpdateAutoContinue: (Boolean) -> Unit,
    onCancelAutoContinue: () -> Unit,
    onLockScreen: () -> Unit,
    onRequestPip: () -> Unit,
    onSaveBookmark: (editing: TimeBookmark?, positionMs: Long, label: String, groupName: String) -> Unit,
    onOpenTagFeed: (TagRef) -> Unit,
    onToggleTagFavorite: (TagRef) -> Unit,
    onToggleTagBlocked: (TagRef) -> Unit,
    onSaveTagTranslation: (TagRef, String) -> Unit,
    onEnqueueDownload: (VideoStream?, String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onUpdateDefaultSpeed: (Float) -> Unit,
    onUpdateLongPressSpeed: (Float) -> Unit,
    onUpdateSeekStepSeconds: (Int) -> Unit,
    onUpdateGestureSensitivitySeconds: (Int) -> Unit,
    onUpdateAutoFullscreen: (Boolean) -> Unit,
    onResetSitePreferences: () -> Unit,
    resizeModeLabel: String = VideoResizeMode.FIT.label,
    onCycleResizeMode: () -> Unit = {},
    onSelectAudioTrack: (Int, Int) -> Unit = { _, _ -> },
    onSelectSubtitleTrack: (Int, Int) -> Unit = { _, _ -> },
    onDisableSubtitles: () -> Unit = {},
    onToggleShelfMembership: (String, Boolean) -> Unit = { _, _ -> },
    onCreateShelfAndAdd: (String) -> Unit = {},
    onDiscoverCast: () -> Unit = {},
    onCastToDevice: (LanCastDevice) -> Unit = {},
    onStopCast: () -> Unit = {},
    onLaunchSystemExternalPlayer: () -> Boolean = { false },
    /**
     * Authoritative live playback position. [uiState] is the structural state whose
     * `positionMs` is intentionally zeroed; anything that stamps a timestamp (bookmark capture,
     * A-B loop label) must read the real position through this provider instead.
     *
     * Deliberately has no default: falling back to `uiState.positionMs` is exactly the bug this
     * parameter exists to prevent, so the compiler must force every caller to wire it up.
     */
    currentPositionMsProvider: () -> Long,
) {
    when (activeSheet) {
        null -> Unit

        PlayerSheet.Settings -> PlayerSettingsSheet(
            uiState = uiState,
            resizeModeLabel = resizeModeLabel,
            onDismiss = onDismiss,
            onCycleResizeMode = onCycleResizeMode,
            onOpenTracks = { onSwitchSheet(PlayerSheet.Tracks) },
            onOpenShelfPicker = { onSwitchSheet(PlayerSheet.ShelfPicker) },
            onOpenCast = {
                onDiscoverCast()
                onSwitchSheet(PlayerSheet.Cast)
            },
            onOpenAbLoop = { onSwitchSheet(PlayerSheet.AbLoop) },
            onOpenSleepTimer = { onSwitchSheet(PlayerSheet.SleepTimer) },
            onLockScreen = {
                onDismiss()
                onLockScreen()
            },
            onRequestPip = {
                onDismiss()
                onRequestPip()
            },
            onOpenAddBookmark = {
                onSwitchSheet(PlayerSheet.BookmarkEditor(initialPositionMs = currentPositionMsProvider()))
            },
            onOpenDownload = { onSwitchSheet(PlayerSheet.Download) },
            onOpenExternalPlayers = { onSwitchSheet(PlayerSheet.ExternalPlayers) },
            onOpenQuality = { onSwitchSheet(PlayerSheet.Quality) },
            onUpdateDefaultSpeed = onUpdateDefaultSpeed,
            onUpdateLongPressSpeed = onUpdateLongPressSpeed,
            onUpdateSeekStepSeconds = onUpdateSeekStepSeconds,
            onUpdateGestureSensitivitySeconds = onUpdateGestureSensitivitySeconds,
            onUpdateAutoFullscreen = onUpdateAutoFullscreen,
            onResetSitePreferences = onResetSitePreferences,
        )

        PlayerSheet.Speed -> PlayerSpeedSheet(
            uiState = uiState,
            onDismiss = onDismiss,
            onSelectSpeed = onSelectSpeed,
            onResetToDefault = onResetSpeedToDefault,
        )

        PlayerSheet.Quality -> PlayerQualitySheet(
            uiState = uiState,
            onDismiss = onDismiss,
            onSelectQuality = { stream, saveDefault ->
                onSelectQuality(stream, saveDefault)
                onDismiss()
            },
        )

        PlayerSheet.AbLoop -> PlayerAbLoopSheet(
            uiState = uiState,
            currentPositionMsProvider = currentPositionMsProvider,
            onDismiss = onDismiss,
            onSetLoopA = onSetLoopA,
            onSetLoopB = onSetLoopB,
            onJumpToLoopA = onJumpToLoopA,
            onClearLoop = onClearLoop,
        )

        PlayerSheet.SleepTimer -> PlayerSleepTimerSheet(
            uiState = uiState,
            onDismiss = onDismiss,
            onSetMinutes = { minutes ->
                onSetSleepTimer(minutes)
                onDismiss()
            },
            onCancelTimer = {
                onCancelSleepTimer()
                onDismiss()
            },
        )

        PlayerSheet.Queue -> PlayerQueueSheet(
            uiState = uiState,
            onDismiss = onDismiss,
            onSelectItem = { index ->
                if (onSelectQueueItem(index)) onDismiss()
            },
            onRemoveItem = { index -> onRemoveQueueItem(index) },
            onUpdateAutoContinue = onUpdateAutoContinue,
            onCancelAutoContinue = onCancelAutoContinue,
        )

        is PlayerSheet.BookmarkEditor -> PlayerBookmarkEditorSheet(
            editing = activeSheet.editingBookmark,
            positionMs = activeSheet.editingBookmark?.positionMs ?: activeSheet.initialPositionMs,
            existingGroups = uiState.existingBookmarkGroups,
            onDismiss = onDismiss,
            onSave = { label, group ->
                onSaveBookmark(
                    activeSheet.editingBookmark,
                    activeSheet.editingBookmark?.positionMs ?: activeSheet.initialPositionMs,
                    label,
                    group,
                )
                onDismiss()
            },
        )

        is PlayerSheet.TagActions -> PlayerTagActionsSheet(
            tag = uiState.tags.firstOrNull { it.tag.slug == activeSheet.tag.tag.slug }
                ?: uiState.characters.firstOrNull { it.tag.slug == activeSheet.tag.tag.slug }
                ?: uiState.categories.firstOrNull { it.tag.slug == activeSheet.tag.tag.slug }
                ?: activeSheet.tag,
            blockTagsGlobally = uiState.blockTagsGlobally,
            onDismiss = onDismiss,
            onOpenTagFeed = {
                onDismiss()
                onOpenTagFeed(activeSheet.tag.tag)
            },
            onToggleFavorite = { onToggleTagFavorite(activeSheet.tag.tag) },
            onToggleBlocked = { onToggleTagBlocked(activeSheet.tag.tag) },
            onSaveCustomTranslation = { chineseName ->
                onSaveTagTranslation(activeSheet.tag.tag, chineseName)
            },
        )

        PlayerSheet.Download -> PlayerDownloadSheet(
            uiState = uiState,
            onDismiss = onDismiss,
            onEnqueue = { stream, group ->
                onEnqueueDownload(stream, group)
                onDismiss()
            },
        )

        PlayerSheet.ExternalPlayers -> PlayerExternalPlayersSheet(
            uiState = uiState,
            onDismiss = onDismiss,
            onOpenUrl = { url ->
                onDismiss()
                onOpenExternalUrl(url)
            },
        )

        PlayerSheet.Tracks -> PlayerTracksSheet(
            uiState = uiState,
            onDismiss = onDismiss,
            onSelectAudioTrack = onSelectAudioTrack,
            onSelectSubtitleTrack = onSelectSubtitleTrack,
            onDisableSubtitles = onDisableSubtitles,
        )

        PlayerSheet.ShelfPicker -> RavenShelfPickerSheet(
            title = uiState.title,
            shelves = uiState.shelves.map { summary ->
                RavenShelfOption(
                    id = summary.shelf.id,
                    name = summary.shelf.name,
                    itemCount = summary.itemCount,
                    selected = summary.shelf.id in uiState.containingShelfIds,
                    isSystem = summary.shelf.isSystem,
                )
            },
            onToggleShelf = onToggleShelfMembership,
            onCreateShelf = onCreateShelfAndAdd,
            onDismiss = onDismiss,
        )

        PlayerSheet.Cast -> PlayerCastSheet(
            uiState = uiState,
            onDismiss = onDismiss,
            onScanDevices = onDiscoverCast,
            onCastToDevice = onCastToDevice,
            onStopCast = onStopCast,
            onLaunchSystemExternalPlayer = onLaunchSystemExternalPlayer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    uiState: PlayerUiState,
    resizeModeLabel: String,
    onDismiss: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenShelfPicker: () -> Unit,
    onOpenCast: () -> Unit,
    onOpenAbLoop: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onLockScreen: () -> Unit,
    onRequestPip: () -> Unit,
    onOpenAddBookmark: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenExternalPlayers: () -> Unit,
    onOpenQuality: () -> Unit,
    onUpdateDefaultSpeed: (Float) -> Unit,
    onUpdateLongPressSpeed: (Float) -> Unit,
    onUpdateSeekStepSeconds: (Int) -> Unit,
    onUpdateGestureSensitivitySeconds: (Int) -> Unit,
    onUpdateAutoFullscreen: (Boolean) -> Unit,
    onResetSitePreferences: () -> Unit,
) {
    val prefs = uiState.playbackPreferences
    val supportsPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    val hasExternal = uiState.externalPlayers.isNotEmpty() || uiState.url.startsWith("http")

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "播放设置",
        subtitle = uiState.siteDisplayName.takeIf { it.isNotBlank() }?.let { "当前站点：$it" },
        modifier = Modifier.testTag(PlayerTestTags.PLAYER_SETTINGS_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Quick Action Grid (2 x 4)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickActionTile(
                        icon = Icons.Rounded.Repeat,
                        label = "A-B 循环",
                        badge = when {
                            uiState.loopRange.isActive -> "已开启"
                            uiState.loopRange.hasStart -> "已设 A"
                            else -> null
                        },
                        highlighted = uiState.loopRange.hasStart,
                        onClick = onOpenAbLoop,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.Bedtime,
                        label = "睡眠定时",
                        badge = uiState.sleepTimer.formattedRemaining.takeIf { it.isNotEmpty() },
                        highlighted = uiState.sleepTimer.isActive,
                        onClick = onOpenSleepTimer,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.Lock,
                        label = "锁定画面",
                        onClick = onLockScreen,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.PictureInPictureAlt,
                        label = "画中画",
                        enabled = supportsPip,
                        onClick = onRequestPip,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickActionTile(
                        icon = Icons.Rounded.BookmarkAdd,
                        label = "时间书签",
                        badge = uiState.timeBookmarks.size.takeIf { it > 0 }?.toString(),
                        onClick = onOpenAddBookmark,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.Download,
                        label = if (uiState.isOfflineDownloaded) "已离线" else "下载视频",
                        highlighted = uiState.isOfflineDownloaded,
                        enabled = !uiState.isLocal,
                        onClick = onOpenDownload,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionTile(
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        label = "站外播放",
                        enabled = hasExternal,
                        onClick = onOpenExternalPlayers,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.Hd,
                        label = "清晰度",
                        badge = uiState.activeQualityLabel,
                        enabled = uiState.canChooseQuality,
                        onClick = onOpenQuality,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickActionTile(
                        icon = Icons.Rounded.AspectRatio,
                        label = "画面比例",
                        badge = resizeModeLabel,
                        onClick = onCycleResizeMode,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.Subtitles,
                        label = "音轨字幕",
                        badge = (uiState.audioTracks.size + uiState.subtitleTracks.size).takeIf { it > 0 }?.toString(),
                        enabled = uiState.audioTracks.isNotEmpty() || uiState.subtitleTracks.isNotEmpty(),
                        onClick = onOpenTracks,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.CollectionsBookmark,
                        label = "加入片单",
                        onClick = onOpenShelfPicker,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionTile(
                        icon = Icons.Rounded.Cast,
                        label = "局域网投屏",
                        highlighted = uiState.activeCastDevice != null,
                        onClick = onOpenCast,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Grouped Per-Site Playback Preferences
            RavenGroupedSection(
                title = "站点播放偏好" + uiState.siteDisplayName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                footer = "倍速与手势参数按站点独立保存；当前会话手动调整的倍速优先于站点默认倍速。",
            ) {
                RavenGroupedSliderRow(
                    title = "默认倍速",
                    value = prefs.defaultSpeed,
                    onValueChange = onUpdateDefaultSpeed,
                    valueLabel = formatSpeedLabel(prefs.defaultSpeed),
                    icon = Icons.Rounded.Speed,
                    valueRange = 0.25f..3.0f,
                    steps = 10,
                )
                RavenGroupedDivider(insetWithIcon = true)

                RavenGroupedSliderRow(
                    title = "长按临时倍速",
                    value = prefs.longPressSpeed,
                    onValueChange = onUpdateLongPressSpeed,
                    valueLabel = formatSpeedLabel(prefs.longPressSpeed),
                    icon = Icons.Rounded.FastForward,
                    valueRange = 1.5f..4.0f,
                    steps = 4,
                )
                RavenGroupedDivider(insetWithIcon = true)

                RavenGroupedSliderRow(
                    title = "双击快进 / 快退步长",
                    value = prefs.seekStepSeconds.toFloat(),
                    onValueChange = { raw ->
                        val snapped = ((raw / 5f).roundToInt() * 5).coerceIn(5, 60)
                        onUpdateSeekStepSeconds(snapped)
                    },
                    valueLabel = "${prefs.seekStepSeconds} 秒",
                    icon = Icons.Rounded.TouchApp,
                    valueRange = 5f..60f,
                    steps = 10,
                )
                RavenGroupedDivider(insetWithIcon = true)

                RavenGroupedSliderRow(
                    title = "横滑拖动灵敏度",
                    subtitle = "划过全屏宽度对应的进度跨度",
                    value = prefs.gestureSensitivitySeconds.toFloat(),
                    onValueChange = { raw ->
                        val snapped = ((raw / 30f).roundToInt() * 30).coerceIn(30, 180)
                        onUpdateGestureSensitivitySeconds(snapped)
                    },
                    valueLabel = "${prefs.gestureSensitivitySeconds} 秒",
                    icon = Icons.Rounded.Swipe,
                    valueRange = 30f..180f,
                    steps = 4,
                )
                RavenGroupedDivider(insetWithIcon = true)

                RavenGroupedSwitchRow(
                    title = "自动匹配视频方向全屏",
                    subtitle = "手动点击播放时按视频方向自动进入全屏（自动播放不自动全屏）",
                    checked = prefs.autoFullscreen,
                    onCheckedChange = onUpdateAutoFullscreen,
                    icon = Icons.Rounded.Fullscreen,
                )
                RavenGroupedDivider(insetWithIcon = true)

                RavenGroupedRow(
                    title = "恢复该站点默认播放设置",
                    icon = Icons.Rounded.RestartAlt,
                    destructive = true,
                    onClick = onResetSitePreferences,
                )
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalRavenColors.current
    val bg = when {
        !enabled -> colors.surface1.copy(alpha = 0.45f)
        highlighted -> colors.crimson.copy(alpha = 0.16f)
        else -> colors.surface1
    }
    val border = if (highlighted && enabled) colors.crimson.copy(alpha = 0.40f) else colors.hairline
    val iconTint = when {
        !enabled -> colors.inkTertiary
        highlighted -> colors.crimson
        else -> colors.inkPrimary
    }

    Column(
        modifier = modifier
            .heightIn(min = 74.dp)
            .clip(RavenShapes.CardShape)
            .background(bg)
            .border(0.5.dp, border, RavenShapes.CardShape)
            .then(
                if (enabled) {
                    Modifier
                        .ravenSpringPress()
                        .clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) colors.inkPrimary else colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (!badge.isNullOrBlank()) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = if (highlighted) colors.crimson else colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlayerSpeedSheet(
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onResetToDefault: () -> Unit,
) {
    var sliderSpeed by remember(uiState.effectivePlaybackSpeed) {
        mutableFloatStateOf(uiState.effectivePlaybackSpeed)
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "播放倍速",
        subtitle = if (uiState.sessionUserSpeed != null) {
            "当前为本次会话倍速，优先于站点默认 (${formatSpeedLabel(uiState.playbackPreferences.defaultSpeed)})"
        } else {
            "当前跟随站点默认倍速"
        },
        modifier = Modifier.testTag(PlayerTestTags.PLAYER_SPEED_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlayerViewModel.SPEED_PRESETS.forEach { preset ->
                    RavenFilterChip(
                        label = formatSpeedLabel(preset),
                        selected = kotlin.math.abs(uiState.effectivePlaybackSpeed - preset) < 0.01f,
                        onClick = { onSelectSpeed(preset) },
                    )
                }
            }

            RavenGroupedSection(title = "精细调节") {
                RavenGroupedSliderRow(
                    title = "当前倍速",
                    value = sliderSpeed,
                    onValueChange = { raw ->
                        val snapped = snapFloatToStep(
                            raw,
                            0.05f,
                            PlayerSessionController.MIN_SPEED,
                            PlayerSessionController.MAX_SPEED,
                        )
                        sliderSpeed = snapped
                        onSelectSpeed(snapped)
                    },
                    valueLabel = formatSpeedLabel(sliderSpeed),
                    icon = Icons.Rounded.Speed,
                    valueRange = PlayerSessionController.MIN_SPEED..PlayerSessionController.MAX_SPEED,
                )
                if (uiState.sessionUserSpeed != null) {
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "恢复站点默认倍速",
                        value = formatSpeedLabel(uiState.playbackPreferences.defaultSpeed),
                        icon = Icons.Rounded.RestartAlt,
                        onClick = onResetToDefault,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerQualitySheet(
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    onSelectQuality: (VideoStream, Boolean) -> Unit,
) {
    val colors = LocalRavenColors.current
    var saveAsSiteDefault by remember { mutableStateOf(false) }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "清晰度选择",
        subtitle = "当前：${uiState.activeQualityLabel}",
        modifier = Modifier.testTag(PlayerTestTags.PLAYER_QUALITY_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RavenGroupedSection {
                RavenGroupedSwitchRow(
                    title = "设为该站点默认清晰度",
                    checked = saveAsSiteDefault,
                    onCheckedChange = { saveAsSiteDefault = it },
                )
            }

            RavenGroupedSection(title = "可用视频流") {
                uiState.availableStreams.forEachIndexed { index, stream ->
                    if (index > 0) RavenGroupedDivider()
                    val isCurrent = uiState.currentStream?.url == stream.url &&
                        uiState.currentStream.quality == stream.quality
                    val label = stream.label.ifBlank { stream.quality.ifBlank { "默认流" } }
                    val meta = buildList {
                        if (stream.isHls) add("HLS") else add("MP4")
                        stream.bandwidthBps?.takeIf { it > 0L }?.let { bps ->
                            add("${bps / 1_000L} kbps")
                        }
                        if (stream.isPreviewOnly) add("预览")
                    }.joinToString(" · ")
                    RavenGroupedRow(
                        title = label,
                        subtitle = meta,
                        onClick = { onSelectQuality(stream, saveAsSiteDefault) },
                        trailingContent = if (isCurrent) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "当前清晰度",
                                    tint = colors.crimson,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerAbLoopSheet(
    uiState: PlayerUiState,
    currentPositionMsProvider: () -> Long,
    onDismiss: () -> Unit,
    onSetLoopA: () -> Unit,
    onSetLoopB: () -> Unit,
    onJumpToLoopA: () -> Unit,
    onClearLoop: () -> Unit,
) {
    val loop = uiState.loopRange
    // `uiState.positionMs` is zeroed by the structural (WhileSubscribed) state, so it reads 00:00
    // whenever the controls have been hidden. Track the live position reactively instead.
    val livePositionMs by produceState(initialValue = currentPositionMsProvider()) {
        while (true) {
            value = currentPositionMsProvider()
            delay(AB_LOOP_LABEL_REFRESH_MS)
        }
    }
    val currentLabel = RavenFormatters.formatDurationMs(livePositionMs)

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "A-B 片段循环",
        subtitle = when {
            loop.isActive -> "循环区间：${RavenFormatters.formatDurationMs(loop.startMs)} → ${RavenFormatters.formatDurationMs(loop.endMs)}"
            loop.hasStart -> "已设定 A 点：${RavenFormatters.formatDurationMs(loop.startMs)}，请继续设定 B 点"
            else -> "手动拖出循环区间外将自动清除 A-B 循环"
        },
        modifier = Modifier.testTag(PlayerTestTags.PLAYER_AB_LOOP_SHEET),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RavenGroupedSection {
                RavenGroupedRow(
                    title = "设当前位置为 A 点",
                    value = if (loop.hasStart) RavenFormatters.formatDurationMs(loop.startMs) else currentLabel,
                    icon = Icons.Rounded.Repeat,
                    onClick = onSetLoopA,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "设当前位置为 B 点",
                    subtitle = "需比 A 点至少晚 1 秒",
                    value = if (loop.isActive) RavenFormatters.formatDurationMs(loop.endMs) else currentLabel,
                    icon = Icons.Rounded.Repeat,
                    enabled = loop.hasStart,
                    onClick = onSetLoopB,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "跳转到 A 点",
                    icon = Icons.Rounded.PlayArrow,
                    enabled = loop.hasStart,
                    onClick = {
                        onJumpToLoopA()
                        onDismiss()
                    },
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "清除 A-B 循环",
                    icon = Icons.Rounded.RestartAlt,
                    destructive = true,
                    enabled = loop.hasStart,
                    onClick = {
                        onClearLoop()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlayerSleepTimerSheet(
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    onSetMinutes: (Int) -> Unit,
    onCancelTimer: () -> Unit,
) {
    val colors = LocalRavenColors.current
    var customMinutes by remember {
        mutableIntStateOf(
            uiState.sleepTimer.durationMinutes.takeIf { it in 1..180 } ?: 20,
        )
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "睡眠定时",
        subtitle = if (uiState.sleepTimer.isActive) {
            "剩余 ${uiState.sleepTimer.formattedRemaining} 后自动暂停播放"
        } else {
            "支持预设或自定义 1–180 分钟"
        },
        modifier = Modifier.testTag(PlayerTestTags.PLAYER_SLEEP_TIMER_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (uiState.sleepTimer.isActive) {
                RavenGroupedSection {
                    RavenGroupedRow(
                        title = "定时运行中",
                        value = uiState.sleepTimer.formattedRemaining,
                        icon = Icons.Rounded.Bedtime,
                        iconTint = colors.crimson,
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "取消睡眠定时",
                        destructive = true,
                        onClick = onCancelTimer,
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlayerViewModel.SLEEP_TIMER_PRESETS_MINUTES.forEach { preset ->
                    RavenFilterChip(
                        label = "${preset} 分钟",
                        selected = uiState.sleepTimer.isActive && uiState.sleepTimer.durationMinutes == preset,
                        onClick = { onSetMinutes(preset) },
                    )
                }
            }

            RavenGroupedSection(title = "自定义时长 (1–180 分钟)") {
                RavenGroupedSliderRow(
                    title = "定时暂停",
                    value = customMinutes.toFloat(),
                    onValueChange = { raw ->
                        customMinutes = raw.roundToInt().coerceIn(
                            PlayerViewModel.MIN_SLEEP_MINUTES,
                            PlayerViewModel.MAX_SLEEP_MINUTES,
                        )
                    },
                    valueLabel = "${customMinutes} 分钟",
                    icon = Icons.Rounded.Bedtime,
                    valueRange = PlayerViewModel.MIN_SLEEP_MINUTES.toFloat()..PlayerViewModel.MAX_SLEEP_MINUTES.toFloat(),
                )
            }

            Button(
                onClick = { onSetMinutes(customMinutes) },
                shape = RavenShapes.PillShape,
                contentPadding = PaddingValues(vertical = 13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = "开启 ${customMinutes} 分钟定时",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlayerBookmarkEditorSheet(
    editing: TimeBookmark?,
    positionMs: Long,
    existingGroups: List<String>,
    onDismiss: () -> Unit,
    onSave: (label: String, groupName: String) -> Unit,
) {
    val colors = LocalRavenColors.current
    var label by remember(editing?.id, positionMs) { mutableStateOf(editing?.label.orEmpty()) }
    var groupName by remember(editing?.id, positionMs) { mutableStateOf(editing?.groupName.orEmpty()) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.crimson,
        unfocusedBorderColor = colors.outlineSubtle,
        focusedTextColor = colors.inkPrimary,
        unfocusedTextColor = colors.inkPrimary,
        focusedLabelColor = colors.crimson,
        unfocusedLabelColor = colors.inkSecondary,
        cursorColor = colors.crimson,
    )

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = if (editing == null) "添加时间书签" else "编辑时间书签",
        trailingAction = {
            RavenStatusPill(
                text = RavenFormatters.formatDurationMs(positionMs),
                tone = RavenStatusTone.ACCENT,
            )
        },
        modifier = Modifier.testTag(PlayerTestTags.PLAYER_BOOKMARK_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("书签备注（可选）") },
                placeholder = { Text("例如：精彩片段、高能节点") },
                singleLine = false,
                maxLines = 3,
                colors = fieldColors,
                shape = RavenShapes.ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("分组名称（可选）") },
                placeholder = { Text("例如：章节、名场面") },
                singleLine = true,
                colors = fieldColors,
                shape = RavenShapes.ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )

            if (existingGroups.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    existingGroups.forEach { group ->
                        RavenFilterChip(
                            label = group,
                            selected = groupName.equals(group, ignoreCase = true),
                            onClick = { groupName = group },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Button(
                onClick = { onSave(label, groupName) },
                shape = RavenShapes.PillShape,
                contentPadding = PaddingValues(vertical = 13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = "保存时间书签",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTagActionsSheet(
    tag: PlayerTagDisplay,
    blockTagsGlobally: Boolean,
    onDismiss: () -> Unit,
    onOpenTagFeed: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBlocked: () -> Unit,
    onSaveCustomTranslation: (String) -> Unit,
) {
    val colors = LocalRavenColors.current
    var isEditingTranslation by remember(tag.tag.slug, tag.tag.namespace) { mutableStateOf(false) }
    var editedChineseName by remember(tag.tag.slug, tag.tag.namespace, tag.translatedLabel) {
        mutableStateOf(tag.translatedLabel.orEmpty())
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = tag.displayLabel,
        subtitle = tag.tag.slug.takeIf { it != tag.displayLabel && it.isNotBlank() },
        modifier = Modifier.testTag(PlayerTestTags.PLAYER_TAG_SHEET),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RavenGroupedSection {
                RavenGroupedRow(
                    title = "查看该标签下的作品",
                    icon = Icons.Rounded.Tag,
                    showChevron = true,
                    onClick = onOpenTagFeed,
                )
                RavenGroupedDivider(insetWithIcon = true)
                PlayerMorphGroupedRow(
                    title = if (tag.isSubscribed) "取消订阅标签更新" else "订阅标签更新",
                    iconBadgeModifier = Modifier.ravenBurstFeedback(
                        trigger = tag.isSubscribed,
                        burstColor = colors.crimson,
                    ),
                    leadingIcon = {
                        RavenMorphToggleIcon(
                            active = tag.isSubscribed,
                            activeGlyph = RavenMorphIcons.BellActive,
                            inactiveGlyph = RavenMorphIcons.BellOutline,
                            activeTint = colors.crimson,
                            inactiveTint = colors.inkPrimary,
                            size = 18.dp,
                            springPreset = MorphSpringPreset.BOUNCY,
                        )
                    },
                    onClick = onToggleFavorite,
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "编辑中文译名",
                    subtitle = tag.translatedLabel?.let { "当前译名：$it" } ?: "为该标签自定义中文译名并立即全局生效",
                    icon = Icons.Rounded.Translate,
                    iconTint = if (isEditingTranslation) colors.crimson else colors.inkPrimary,
                    onClick = { isEditingTranslation = !isEditingTranslation },
                    modifier = Modifier.testTag("player_tag_edit_translation_row"),
                )
                if (isEditingTranslation) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = editedChineseName,
                            onValueChange = { editedChineseName = it },
                            label = { Text("中文译名") },
                            placeholder = { Text("输入该标签的中文译名") },
                            singleLine = true,
                            shape = RavenShapes.ControlShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.crimson,
                                unfocusedBorderColor = colors.outlineSubtle,
                                focusedTextColor = colors.inkPrimary,
                                unfocusedTextColor = colors.inkPrimary,
                                focusedLabelColor = colors.crimson,
                                unfocusedLabelColor = colors.inkSecondary,
                                cursorColor = colors.crimson,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("player_tag_edit_translation_field"),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { isEditingTranslation = false },
                                shape = RavenShapes.PillShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp),
                            ) {
                                Text("取消", color = colors.inkSecondary)
                            }
                            Button(
                                onClick = {
                                    onSaveCustomTranslation(editedChineseName)
                                    isEditingTranslation = false
                                },
                                enabled = editedChineseName.trim().isNotEmpty(),
                                shape = RavenShapes.PillShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primaryCtaContainer,
                                    contentColor = colors.primaryCtaInk,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp)
                                    .testTag("player_tag_edit_translation_save"),
                            ) {
                                Text(
                                    text = "保存译名",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                }
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = when {
                        tag.isBlocked -> "取消屏蔽该标签"
                        blockTagsGlobally -> "全局屏蔽该标签"
                        else -> "在当前站点屏蔽该标签"
                    },
                    subtitle = when {
                        tag.isBlocked -> "当前已被过滤隐藏，点击立即解除屏蔽"
                        blockTagsGlobally -> "屏蔽后将在全部站点自动过滤相关作品与搜索结果"
                        else -> "屏蔽后将过滤当前站点包含该标签的推荐和列表项"
                    },
                    icon = Icons.Rounded.Block,
                    destructive = !tag.isBlocked,
                    onClick = onToggleBlocked,
                )
            }
        }
    }
}

@Composable
private fun PlayerMorphGroupedRow(
    title: String,
    onClick: () -> Unit,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    iconBadgeModifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val rowBackdropColor by animateColorAsState(
        targetValue = when {
            pressed -> colors.surface2.copy(alpha = 0.72f)
            hovered || focused -> colors.surface2.copy(alpha = 0.42f)
            else -> Color.Transparent
        },
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
        label = "playerMorphGroupedRowBackdrop",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(rowBackdropColor)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = RectangleShape,
                enabled = true,
                glowColor = colors.interactiveGlow,
                highlightAlpha = 0.04f,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = true,
                role = Role.Button,
                onClick = {
                    feedback.perform(RavenFeedbackEffect.TAP)
                    onClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = iconBadgeModifier
                .size(32.dp)
                .clip(RavenShapes.ControlShape)
                .background(colors.surface2)
                .border(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.lightRimTop, colors.hairline),
                    ),
                    shape = RavenShapes.ControlShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            leadingIcon()
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.inkPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerDownloadSheet(
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    onEnqueue: (VideoStream?, String) -> Unit,
) {
    val colors = LocalRavenColors.current
    val candidates = remember(uiState.detail, uiState.availableStreams) {
        uiState.detail?.downloadStreams.orEmpty().ifEmpty { uiState.availableStreams }
    }
    var groupName by remember { mutableStateOf("") }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "下载离线视频",
        subtitle = if (uiState.isOfflineDownloaded) "该视频已有离线版本，可下载其他清晰度" else "选择要下载的视频清晰度",
        modifier = Modifier.testTag(PlayerTestTags.PLAYER_DOWNLOAD_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("下载分组（可选）") },
                singleLine = true,
                shape = RavenShapes.ControlShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.crimson,
                    unfocusedBorderColor = colors.outlineSubtle,
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                    focusedLabelColor = colors.crimson,
                    unfocusedLabelColor = colors.inkSecondary,
                    cursorColor = colors.crimson,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            RavenGroupedSection(title = "选择清晰度") {
                if (candidates.isEmpty()) {
                    RavenGroupedRow(
                        title = "按默认偏好清晰度加入下载",
                        icon = Icons.Rounded.Download,
                        onClick = { onEnqueue(null, groupName) },
                    )
                } else {
                    candidates.forEachIndexed { index, stream ->
                        if (index > 0) RavenGroupedDivider(insetWithIcon = true)
                        val label = stream.label.ifBlank { stream.quality.ifBlank { "默认清晰度" } }
                        RavenGroupedRow(
                            title = label,
                            subtitle = if (stream.isHls) "HLS 流媒体离线缓存" else "MP4 完整缓存（支持进度条缩略图预览）",
                            icon = Icons.Rounded.Download,
                            showChevron = true,
                            onClick = { onEnqueue(stream, groupName) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerExternalPlayersSheet(
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "站外播放与原网页",
        modifier = Modifier.testTag(PlayerTestTags.PLAYER_EXTERNAL_SHEET),
    ) {
        RavenGroupedSection {
            var renderedCount = 0
            uiState.externalPlayers.forEach { link ->
                if (renderedCount > 0) RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = link.label.ifBlank { link.provider.ifBlank { "站外播放器" } },
                    subtitle = link.provider.takeIf { it.isNotBlank() && it != link.label },
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    showChevron = true,
                    onClick = { onOpenUrl(link.url) },
                )
                renderedCount++
            }
            if (uiState.url.startsWith("http", ignoreCase = true)) {
                if (renderedCount > 0) RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "在浏览器中打开原网页",
                    subtitle = uiState.url,
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    showChevron = true,
                    onClick = { onOpenUrl(uiState.url) },
                )
                renderedCount++
            }
            if (renderedCount == 0) {
                RavenGroupedRow(
                    title = "当前视频没有可用的外部链接",
                    enabled = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTracksSheet(
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    onSelectAudioTrack: (Int, Int) -> Unit,
    onSelectSubtitleTrack: (Int, Int) -> Unit,
    onDisableSubtitles: () -> Unit,
) {
    val colors = LocalRavenColors.current
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "音轨与字幕",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (uiState.audioTracks.isNotEmpty()) {
                RavenGroupedSection(title = "音轨") {
                    uiState.audioTracks.forEachIndexed { idx, track ->
                        if (idx > 0) RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedRow(
                            title = track.label,
                            subtitle = track.language?.takeIf { it.isNotBlank() },
                            icon = Icons.Rounded.Audiotrack,
                            iconTint = if (track.selected) colors.crimson else colors.inkPrimary,
                            trailingContent = if (track.selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "已选中",
                                        tint = colors.crimson,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                onSelectAudioTrack(track.groupIndex, track.trackIndex)
                                onDismiss()
                            },
                        )
                    }
                }
            }

            RavenGroupedSection(title = "字幕") {
                RavenGroupedRow(
                    title = "关闭字幕",
                    icon = Icons.Rounded.SubtitlesOff,
                    iconTint = if (!uiState.subtitlesEnabled) colors.crimson else colors.inkPrimary,
                    trailingContent = if (!uiState.subtitlesEnabled) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "已关闭",
                                tint = colors.crimson,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onDisableSubtitles()
                        onDismiss()
                    },
                )
                uiState.subtitleTracks.forEach { track ->
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = track.label,
                        subtitle = track.language?.takeIf { it.isNotBlank() },
                        icon = Icons.Rounded.Subtitles,
                        iconTint = if (track.selected && uiState.subtitlesEnabled) colors.crimson else colors.inkPrimary,
                        trailingContent = if (track.selected && uiState.subtitlesEnabled) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "已选中",
                                    tint = colors.crimson,
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            onSelectSubtitleTrack(track.groupIndex, track.trackIndex)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerCastSheet(
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    onScanDevices: () -> Unit,
    onCastToDevice: (LanCastDevice) -> Unit,
    onStopCast: () -> Unit,
    onLaunchSystemExternalPlayer: () -> Boolean,
) {
    val colors = LocalRavenColors.current
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "局域网投屏与系统播放器",
        subtitle = "仅扫描局域网私有网段 DLNA 设备，不上传任何播放信息",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.activeCastDevice != null) {
                RavenGroupedSection(title = "当前投屏连接") {
                    RavenGroupedRow(
                        title = "断开「${uiState.activeCastDevice.friendlyName}」",
                        subtitle = uiState.activeCastDevice.host,
                        icon = Icons.Rounded.Close,
                        destructive = true,
                        onClick = {
                            onStopCast()
                            onDismiss()
                        },
                    )
                }
            }

            RavenGroupedSection(title = "DLNA 设备列表") {
                RavenGroupedRow(
                    title = if (uiState.isScanningCast) "正在扫描局域网设备…" else "重新扫描设备",
                    icon = Icons.Rounded.Refresh,
                    enabled = !uiState.isScanningCast,
                    trailingContent = if (uiState.isScanningCast) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.crimson,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = onScanDevices,
                )
                uiState.castDevices.forEach { device ->
                    RavenGroupedDivider(insetWithIcon = true)
                    val active = uiState.activeCastDevice?.locationUrl == device.locationUrl
                    RavenGroupedRow(
                        title = device.friendlyName,
                        subtitle = device.host,
                        icon = Icons.Rounded.Cast,
                        iconTint = if (active) colors.crimson else colors.inkPrimary,
                        onClick = {
                            onCastToDevice(device)
                            onDismiss()
                        },
                    )
                }
            }

            RavenGroupedSection(title = "本地系统播放器") {
                RavenGroupedRow(
                    title = "调用系统 / 第三方播放器打开视频流",
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    showChevron = true,
                    onClick = {
                        onLaunchSystemExternalPlayer()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerQueueSheet(
    uiState: PlayerUiState,
    onDismiss: () -> Unit,
    onSelectItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onUpdateAutoContinue: (Boolean) -> Unit,
    onCancelAutoContinue: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val queue = uiState.queue
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "播放队列",
        subtitle = queue?.let { "当前 ${it.currentIndex + 1}/${it.totalCount} · 剩余 ${it.remainingCount} 项" },
    ) {
        if (queue == null) {
            Text(
                text = "当前没有进行中的播放队列",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
            )
            return@RavenBottomSheet
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RavenGroupedSection(
                title = "连续播放",
                footer = "仅在播放器前台显示倒计时时生效，不会在后台轮询或自动切源。",
            ) {
                RavenGroupedSwitchRow(
                    title = "自动连播",
                    subtitle = "当前项结束后等待 5 秒自动播放下一项，默认关闭",
                    checked = queue.autoContinueEnabled,
                    onCheckedChange = onUpdateAutoContinue,
                    icon = Icons.Rounded.SkipNext,
                )
            }

            if (queue.autoContinueCountdownActive) {
                OutlinedButton(
                    onClick = onCancelAutoContinue,
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface1,
                        contentColor = colors.crimson,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("取消自动连播 · ${queue.autoContinueRemainingSeconds} 秒后继续")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RavenStatusPill(
                    text = "正在播放 ${queue.currentIndex + 1}",
                    tone = RavenStatusTone.ACCENT,
                )
                if (queue.offlineOnly) {
                    RavenStatusPill(text = "纯离线", tone = RavenStatusTone.SUCCESS)
                }
            }

            Text(
                text = "移除当前项将切换至下一项（末项则回到上一项）；移除唯一项会结束队列。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkSecondary,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(
                    items = queue.items,
                    key = { _, item -> item.compositeKey },
                ) { index, item ->
                    val isCurrent = index == queue.currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RavenShapes.ControlShape)
                            .background(if (isCurrent) colors.crimson.copy(alpha = 0.12f) else colors.surface1)
                            .clickable(
                                enabled = !isCurrent,
                                role = Role.Button,
                            ) { onSelectItem(index) }
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isCurrent) colors.crimson else colors.inkTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) colors.crimson else colors.inkPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    isCurrent -> "当前项"
                                    index < queue.currentIndex -> "已播放"
                                    else -> "待播放"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.inkSecondary,
                            )
                        }
                        IconButton(onClick = { onRemoveItem(index) }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "从队列移除 ${item.title}",
                                tint = colors.inkTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}
