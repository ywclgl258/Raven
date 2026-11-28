package com.raven.app.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.DownloadStatus

/** 详情页操作区：主操作条、操作按钮与标签 Chip。 */

@Composable
internal fun PlayerActionStrip(
    uiState: PlayerUiState,
    onToggleWatchLater: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDownloadSheet: () -> Unit,
    onOpenAddBookmark: () -> Unit,
    onShareClick: () -> Unit,
    onOpenExternalPlayers: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleLike: () -> Unit = {},
    onOpenCast: () -> Unit = {},
) {
    val colors = LocalRavenColors.current
    val downloadLabel = when {
        uiState.isLocal -> "本地"
        uiState.isOfflineDownloaded -> "已下载"
        uiState.downloadStatus == DownloadStatus.DOWNLOADING ->
            "${(uiState.downloadProgress * 100f).toInt()}%"
        uiState.downloadStatus == DownloadStatus.QUEUED -> "队列中"
        else -> "下载"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailActionButton(
            label = "稍后看",
            active = uiState.inWatchLater,
            feedbackEffect = if (uiState.inWatchLater) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.TOGGLE_ON,
            onClick = onToggleWatchLater,
            modifier = Modifier
                .weight(1f)
                .ravenBurstFeedback(trigger = uiState.inWatchLater)
                .testTag(PlayerTestTags.PLAYER_ACTION_WATCH_LATER),
        ) { tint, iconModifier ->
            RavenMorphIcon(
                glyph = if (uiState.inWatchLater) RavenMorphIcons.Check else RavenMorphIcons.Plus,
                contentDescription = "稍后看",
                tint = tint,
                size = 20.dp,
                springPreset = MorphSpringPreset.SNAPPY,
                modifier = iconModifier,
            )
        }
        DetailActionButton(
            label = if (uiState.isFavorite) "已收藏" else "收藏",
            active = uiState.isFavorite,
            feedbackEffect = if (uiState.isFavorite) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.FAVORITE,
            onClick = onToggleFavorite,
            modifier = Modifier
                .weight(1f)
                .ravenBurstFeedback(trigger = uiState.isFavorite)
                .testTag(PlayerTestTags.PLAYER_ACTION_FAVORITE),
        ) { tint, iconModifier ->
            RavenMorphIcon(
                glyph = if (uiState.isFavorite) RavenMorphIcons.HeartFilled else RavenMorphIcons.HeartOutline,
                contentDescription = if (uiState.isFavorite) "已收藏" else "收藏",
                tint = tint,
                size = 20.dp,
                springPreset = MorphSpringPreset.BOUNCY,
                modifier = iconModifier,
            )
        }
        DetailActionButton(
            label = downloadLabel,
            active = uiState.isOfflineDownloaded,
            enabled = !uiState.isLocal,
            onClick = onOpenDownloadSheet,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_DOWNLOAD),
        ) { tint, iconModifier ->
            RavenMorphIcon(
                glyph = if (uiState.isOfflineDownloaded) RavenMorphIcons.DownloadDone else RavenMorphIcons.Download,
                contentDescription = downloadLabel,
                tint = tint,
                size = 20.dp,
                springPreset = MorphSpringPreset.SNAPPY,
                modifier = iconModifier,
            )
        }
        DetailActionButton(
            label = "书签",
            active = uiState.timeBookmarks.isNotEmpty(),
            onClick = onOpenAddBookmark,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_BOOKMARK),
        ) { tint, iconModifier ->
            RavenMorphIcon(
                glyph = if (uiState.timeBookmarks.isNotEmpty()) RavenMorphIcons.BookmarkFilled else RavenMorphIcons.BookmarkOutline,
                contentDescription = "书签",
                tint = tint,
                size = 20.dp,
                springPreset = MorphSpringPreset.SNAPPY,
                modifier = iconModifier,
            )
        }
        DetailActionButton(
            icon = Icons.Rounded.Share,
            label = "分享",
            enabled = uiState.url.isNotBlank(),
            onClick = onShareClick,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_SHARE),
        )
        DetailActionButton(
            icon = if (uiState.isLiked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
            label = if (uiState.isLiked) "已点赞" else "点赞",
            active = uiState.isLiked,
            feedbackEffect = if (uiState.isLiked) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.FAVORITE,
            onClick = onToggleLike,
            modifier = Modifier
                .weight(1f)
                .ravenBurstFeedback(trigger = uiState.isLiked)
                .testTag("player_action_like"),
        )
        DetailActionButton(
            icon = Icons.Rounded.Cast,
            label = if (uiState.activeCastDevice != null) "投屏中" else "投屏",
            active = uiState.activeCastDevice != null,
            onClick = onOpenCast,
            modifier = Modifier.weight(1f),
        )
        DetailActionButton(
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            label = "站外",
            enabled = uiState.externalPlayers.isNotEmpty() || uiState.url.startsWith("http"),
            onClick = onOpenExternalPlayers,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.PLAYER_ACTION_EXTERNAL),
        )
    }
}

@Composable
internal fun DetailActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    feedbackEffect: RavenFeedbackEffect = RavenFeedbackEffect.TAP,
) {
    DetailActionButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        active = active,
        enabled = enabled,
        feedbackEffect = feedbackEffect,
    ) { tint, iconModifier ->
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = iconModifier.size(20.dp),
        )
    }
}

@Composable
internal fun DetailActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    feedbackEffect: RavenFeedbackEffect = RavenFeedbackEffect.TAP,
    iconContent: @Composable (tint: Color, modifier: Modifier) -> Unit,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val iconScaleState = animateFloatAsState(
        targetValue = if (active) 1.12f else 1.0f,
        animationSpec = if (motionReduced) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "playerActionIconScale",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> colors.inkTertiary
            active -> colors.crimson
            else -> colors.inkPrimary
        },
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 160),
        label = "playerActionIconTint",
    )

    Column(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RavenShapes.ControlShape)
            .then(
                if (enabled) {
                    Modifier
                        .ravenSpringPress()
                        .clickable(role = Role.Button) {
                            feedback.perform(feedbackEffect)
                            onClick()
                        }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        iconContent(
            tint,
            Modifier.graphicsLayer {
                val s = iconScaleState.value
                scaleX = s
                scaleY = s
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = if (active && enabled) colors.crimson else if (enabled) colors.inkSecondary else colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerTagChip(
    tagDisplay: PlayerTagDisplay,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val colorSpec = if (motionReduced) {
        snap<androidx.compose.ui.graphics.Color>()
    } else {
        tween(durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS, easing = RavenMotion.HarmonyDecelerationEasing)
    }
    val container by animateColorAsState(
        targetValue = when {
            tagDisplay.isBlocked -> colors.error.copy(alpha = 0.14f)
            tagDisplay.isSubscribed -> colors.crimson.copy(alpha = 0.15f)
            else -> colors.surface1
        },
        animationSpec = colorSpec,
        label = "playerTagContainer",
    )
    val border by animateColorAsState(
        targetValue = when {
            tagDisplay.isBlocked -> colors.error.copy(alpha = 0.40f)
            tagDisplay.isSubscribed -> colors.crimson.copy(alpha = 0.40f)
            else -> colors.hairline
        },
        animationSpec = colorSpec,
        label = "playerTagBorder",
    )
    val textColor by animateColorAsState(
        targetValue = when {
            tagDisplay.isBlocked -> colors.error
            tagDisplay.isSubscribed -> colors.crimson
            else -> colors.inkPrimary
        },
        animationSpec = colorSpec,
        label = "playerTagText",
    )

    Row(
        modifier = Modifier
            .clip(RavenShapes.PillShape)
            .background(container)
            .border(0.5.dp, border, RavenShapes.PillShape)
            .ravenSpringPress()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (tagDisplay.isBlocked) {
            Icon(
                imageVector = Icons.Rounded.Block,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(13.dp),
            )
        } else if (tagDisplay.isSubscribed) {
            Icon(
                imageVector = Icons.Rounded.NotificationsActive,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = tagDisplay.displayLabel,
            style = MaterialTheme.typography.labelMedium.copy(
                textDecoration = if (tagDisplay.isBlocked) TextDecoration.LineThrough else TextDecoration.None,
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
