package com.raven.app.feature.reader

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenAnimatedLabel
import com.raven.app.core.designsystem.component.RavenCrimsonProgressBar
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ambientHeroGlow
import com.raven.app.core.designsystem.theme.ravenFrostedSurface
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.GalleryDetail
import java.util.Locale

/** 图集详情页 Hero 区：背景/封面/标题、磨砂动作按钮与标签 Chip。从 `GalleryDetailScreen.kt` 拆出，包名与 `testTag` 契约不变。 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GalleryHeroHeader(
    uiState: GalleryDetailUiState,
    detail: GalleryDetail,
    onOpenReader: (startPage: Int, offlineOnly: Boolean) -> Unit,
    onOpenResumeTarget: (GalleryResumeTarget) -> Unit,
    onOpenAuthor: (AuthorRef) -> Unit,
    onLongPressAuthor: (AuthorRef) -> Unit,
    onToggleWatchLater: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleSeriesFollow: () -> Unit = {},
    onOpenDownloadSheet: () -> Unit,
    onExportCbz: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val feedback = LocalRavenFeedback.current
    val item = detail.item
    val primaryAuthor = detail.authors.firstOrNull() ?: item.author
    val coverAspect = RavenFormatters.clampAspectRatio(item.coverAspectRatio, defaultRatio = 0.72f)
    val ratingVal = item.rating
    val dateLabelVal = item.dateLabel
    val languageVal = item.language
    val sizeLabelVal = uiState.localSizeLabel ?: detail.fileSizeLabel
    val downloadVal = uiState.activeDownload
    val progressVal = uiState.readingProgress

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .ambientHeroGlow(tint = colors.crimson, alpha = 0.14f)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val canReadImages = (detail.pageCount > 0 || detail.pages.isNotEmpty())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Cover Poster
            Box(
                modifier = Modifier
                    .width(122.dp)
                    .aspectRatio(coverAspect)
                    .clip(RavenShapes.CardShape)
                    .background(colors.surface1)
                    .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                    .let { base ->
                        if (canReadImages) {
                            base.clickable(role = Role.Button) {
                                val resume = uiState.resumeTarget
                                if (resume != null) {
                                    onOpenResumeTarget(resume)
                                } else {
                                    onOpenReader(uiState.resumeStartPage, uiState.isLocal)
                                }
                            }
                        } else {
                            base
                        }
                    },
            ) {
                RavenImage(
                    url = item.coverUrl,
                    siteId = item.siteId,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (progressVal != null && progressVal.progressFraction > 0f) {
                    RavenCrimsonProgressBar(
                        fraction = progressVal.progressFraction,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    )
                }
            }

            // Metadata Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.inkPrimary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                if (primaryAuthor != null && primaryAuthor.name.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(RavenShapes.PillShape)
                            .background(colors.surface2)
                            .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                            .combinedClickable(
                                role = Role.Button,
                                onClick = { onOpenAuthor(primaryAuthor) },
                                onLongClick = { onLongPressAuthor(primaryAuthor) },
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = primaryAuthor.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RavenStatusPill(
                        text = uiState.siteDisplayName,
                        tone = if (uiState.isLocal) RavenStatusTone.SUCCESS else RavenStatusTone.NEUTRAL,
                        tabular = false,
                    )
                    if (detail.pageCount > 0) {
                        RavenStatusPill(
                            text = "${detail.pageCount} 页",
                            tone = RavenStatusTone.NEUTRAL,
                        )
                    }
                    if (ratingVal != null && ratingVal > 0f) {
                        RavenStatusPill(
                            text = String.format(Locale.US, "%.1f", ratingVal),
                            icon = Icons.Rounded.Star,
                            tone = RavenStatusTone.WARNING,
                        )
                    }
                    if (!languageVal.isNullOrBlank()) {
                        RavenStatusPill(
                            text = languageVal,
                            tone = RavenStatusTone.NEUTRAL,
                            tabular = false,
                        )
                    }
                    if (!dateLabelVal.isNullOrBlank()) {
                        RavenStatusPill(
                            text = dateLabelVal,
                            tone = RavenStatusTone.NEUTRAL,
                        )
                    }
                    if (!sizeLabelVal.isNullOrBlank()) {
                        RavenStatusPill(
                            text = sizeLabelVal,
                            tone = RavenStatusTone.NEUTRAL,
                        )
                    }
                    if (uiState.isOfflineComplete || downloadVal?.status == DownloadStatus.COMPLETED) {
                        RavenStatusPill(
                            text = "已离线",
                            icon = Icons.Outlined.OfflinePin,
                            tone = RavenStatusTone.SUCCESS,
                            tabular = false,
                        )
                    } else if (downloadVal != null && downloadVal.status == DownloadStatus.DOWNLOADING) {
                        RavenStatusPill(
                            text = "下载中 ${downloadVal.completedUnits}/${downloadVal.totalUnits}",
                            tone = RavenStatusTone.ACCENT,
                        )
                    }
                }
            }
        }

        // Additional co-authors if present
        if (detail.authors.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                detail.authors.drop(1).forEach { author ->
                    RavenFilterChip(
                        label = author.name,
                        selected = false,
                        leadingIcon = Icons.Outlined.Person,
                        onClick = { onOpenAuthor(author) },
                        onLongClick = { onLongPressAuthor(author) },
                    )
                }
            }
        }

        // Solid White Netflix-style Primary CTA + Optional Offline Read CTA
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (detail.pageCount > 0 || detail.pages.isNotEmpty()) {
                Button(
                    onClick = {
                        feedback.perform(RavenFeedbackEffect.TAP)
                        val target = uiState.resumeTarget
                        if (target != null) {
                            onOpenResumeTarget(target)
                        } else {
                            onOpenReader(uiState.resumeStartPage, uiState.isLocal)
                        }
                    },
                    shape = RavenShapes.PillShape,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .ravenSpringPress()
                        .testTag(ReaderTestTags.GALLERY_DETAIL_PRIMARY_CTA),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // 「继续阅读 (第 5 页)」会随位置更新，做一个短促替换，避免数字突兀跳变。
                    RavenAnimatedLabel(
                        text = uiState.primaryCtaLabel,
                        contentAlignment = Alignment.CenterStart,
                    ) { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge.tabular().copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (!uiState.isLocal && uiState.canOpenOffline) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 50.dp)
                        .ravenSpringPress()
                        .ravenFrostedSurface(
                            reduceTransparency = reduceTransparency,
                            shape = RavenShapes.PillShape,
                        )
                        .clickable(role = Role.Button) {
                            feedback.perform(RavenFeedbackEffect.TAP)
                            onOpenReader(uiState.resumeStartPage, true)
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OfflinePin,
                            contentDescription = null,
                            tint = colors.success,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "离线读",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.inkPrimary,
                        )
                    }
                }
            }
        }

        // Frosted Action Bar: Watch Later / Favorite / Like / Download / Export CBZ
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 稍后看只对视频成立：漫画/写真有阅读进度与追更，再加一个稍后看只会重复。
            if (item.kind.isVideo) {
                FrostedHeroActionButton(
                    label = if (uiState.isInWatchLater) "已稍后看" else "稍后看",
                    active = uiState.isInWatchLater,
                    feedbackEffect = if (uiState.isInWatchLater) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.TOGGLE_ON,
                    onClick = onToggleWatchLater,
                    modifier = Modifier
                        .weight(1f)
                        .ravenBurstFeedback(trigger = uiState.isInWatchLater),
                ) { tint, iconModifier ->
                    RavenMorphIcon(
                        glyph = if (uiState.isInWatchLater) RavenMorphIcons.Check else RavenMorphIcons.Plus,
                        contentDescription = if (uiState.isInWatchLater) "已稍后看" else "稍后看",
                        tint = tint,
                        size = 20.dp,
                        springPreset = MorphSpringPreset.SNAPPY,
                        modifier = iconModifier,
                    )
                }
            }
            FrostedHeroActionButton(
                label = if (uiState.isFavorite) "已收藏" else "收藏",
                active = uiState.isFavorite,
                feedbackEffect = if (uiState.isFavorite) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.FAVORITE,
                onClick = onToggleFavorite,
                modifier = Modifier
                    .weight(1f)
                    .ravenBurstFeedback(trigger = uiState.isFavorite),
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
            if (uiState.seriesFollowSupported) {
                FrostedHeroActionButton(
                    label = when {
                        uiState.seriesFollowInProgress -> "处理中"
                        uiState.isSeriesFollowed -> "已追更"
                        else -> "追更"
                    },
                    active = uiState.isSeriesFollowed,
                    onClick = onToggleSeriesFollow,
                    enabled = !uiState.seriesFollowInProgress,
                    modifier = Modifier
                        .weight(1f)
                        .ravenBurstFeedback(trigger = uiState.isSeriesFollowed),
                ) { tint, iconModifier ->
                    RavenMorphIcon(
                        glyph = if (uiState.isSeriesFollowed) RavenMorphIcons.Check else RavenMorphIcons.Plus,
                        contentDescription = if (uiState.isSeriesFollowed) "已追更" else "追更",
                        tint = tint,
                        size = 20.dp,
                        springPreset = MorphSpringPreset.SNAPPY,
                        modifier = iconModifier,
                    )
                }
            }
            FrostedHeroActionButton(
                icon = if (uiState.isLiked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                label = if (uiState.isLiked) "已点赞" else "点赞",
                active = uiState.isLiked,
                feedbackEffect = if (uiState.isLiked) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.FAVORITE,
                onClick = onToggleLike,
                modifier = Modifier
                    .weight(1f)
                    .ravenBurstFeedback(trigger = uiState.isLiked)
                    .testTag("gallery_action_like"),
            )
            if (!uiState.isLocal) {
                val isDownloaded = uiState.isOfflineComplete ||
                    uiState.activeDownload?.status == DownloadStatus.COMPLETED
                FrostedHeroActionButton(
                    label = if (isDownloaded) "已下载" else "下载",
                    active = isDownloaded,
                    onClick = onOpenDownloadSheet,
                    modifier = Modifier.weight(1f),
                ) { tint, iconModifier ->
                    RavenMorphIcon(
                        glyph = if (isDownloaded) RavenMorphIcons.DownloadDone else RavenMorphIcons.Download,
                        contentDescription = if (isDownloaded) "已下载" else "下载",
                        tint = tint,
                        size = 20.dp,
                        springPreset = MorphSpringPreset.SNAPPY,
                        modifier = iconModifier,
                    )
                }
            }
            FrostedHeroActionButton(
                icon = Icons.Outlined.Archive,
                label = "导出 CBZ",
                active = false,
                enabled = !uiState.isExporting,
                onClick = onExportCbz,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun FrostedHeroActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    feedbackEffect: RavenFeedbackEffect = RavenFeedbackEffect.TAP,
) {
    FrostedHeroActionButton(
        label = label,
        active = active,
        onClick = onClick,
        modifier = modifier,
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
internal fun FrostedHeroActionButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    feedbackEffect: RavenFeedbackEffect = RavenFeedbackEffect.TAP,
    iconContent: @Composable (tint: Color, modifier: Modifier) -> Unit,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val iconScale by animateFloatAsState(
        targetValue = if (active) 1.12f else 1.0f,
        animationSpec = if (motionReduced) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "galleryHeroActionIconScale",
    )
    val tint = when {
        !enabled -> colors.inkTertiary
        active -> colors.crimson
        else -> colors.inkPrimary
    }

    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .ravenSpringPress(interactionSource = interactionSource, enabled = enabled)
            .ravenFrostedSurface(
                reduceTransparency = reduceTransparency,
                shape = RavenShapes.CardShape,
                borderColor = if (active) colors.crimson.copy(alpha = 0.45f) else colors.hairline,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    feedback.perform(feedbackEffect)
                    onClick()
                },
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        iconContent(
            tint,
            Modifier.graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) colors.inkPrimary else colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun GalleryTagChip(
    label: String,
    namespace: String,
    isSubscribed: Boolean,
    isBlocked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val borderColor = when {
        isBlocked -> colors.error.copy(alpha = 0.45f)
        isSubscribed -> colors.crimson.copy(alpha = 0.45f)
        else -> colors.hairline
    }
    val containerColor = when {
        isBlocked -> colors.error.copy(alpha = 0.12f)
        isSubscribed -> colors.crimson.copy(alpha = 0.14f)
        else -> colors.surface1
    }

    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .ravenSpringPress(interactionSource = interactionSource)
            .clip(RavenShapes.PillShape)
            .background(containerColor)
            .border(0.5.dp, borderColor, RavenShapes.PillShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (namespace.isNotBlank()) {
            Text(
                text = namespace,
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                isBlocked -> colors.error
                isSubscribed -> colors.crimson
                else -> colors.inkPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

