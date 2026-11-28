package com.raven.app.feature.player

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.media.playback.PlayerSessionController
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.VideoStream
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 播放器播放参数 Sheet 层：倍速、清晰度、A-B 循环、睡眠定时、书签编辑与标签操作。从 `PlayerSheets.kt` 拆出，包名与 `testTag` 契约不变。 */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlayerSpeedSheet(
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
internal fun PlayerQualitySheet(
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
internal fun PlayerAbLoopSheet(
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
internal fun PlayerSleepTimerSheet(
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
internal fun PlayerBookmarkEditorSheet(
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
internal fun PlayerTagActionsSheet(
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
