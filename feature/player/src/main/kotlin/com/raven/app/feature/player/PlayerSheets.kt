package com.raven.app.feature.player

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSliderRow
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenShelfOption
import com.raven.app.core.designsystem.component.RavenShelfPickerSheet
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.media.cast.LanCastDevice
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.VideoStream
import kotlin.math.roundToInt

/** Label refresh cadence for the A-B sheet's live "current position" readout. */
internal const val AB_LOOP_LABEL_REFRESH_MS: Long = 500L

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
internal fun PlayerSettingsSheet(
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
internal fun QuickActionTile(
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
