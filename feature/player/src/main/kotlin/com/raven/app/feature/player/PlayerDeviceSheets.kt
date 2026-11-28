package com.raven.app.feature.player

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.media.cast.LanCastDevice
import com.raven.app.core.model.VideoStream

/** 播放器外设与队列 Sheet 层：下载、外部播放器、音轨字幕、投屏与队列。 */

@Composable
internal fun PlayerMorphGroupedRow(
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
internal fun PlayerDownloadSheet(
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
internal fun PlayerExternalPlayersSheet(
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
internal fun PlayerTracksSheet(
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
internal fun PlayerCastSheet(
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
internal fun PlayerQueueSheet(
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
