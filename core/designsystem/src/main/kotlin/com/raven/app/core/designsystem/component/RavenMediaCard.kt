package com.raven.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind

/**
 * Pure STATELESS 16:9 cinema video card (zero internal StateFlows or dialog hosts).
 * Renders poster, optional top-left badge, bottom-right tabular duration pill, optional `2.5dp`
 * Raven Crimson resume progress bar, crisp 2-line title, and metadata line.
 */
@Composable
fun RavenVideoCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    progressFraction: Float? = null,
    badgeText: String? = null,
    subtitleOverride: String? = null,
    showMetadata: Boolean = true,
) {
    val colors = LocalRavenColors.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val durationLabel = remember(item.durationSeconds) { RavenFormatters.formatDuration(item.durationSeconds) }
    val metadataLine = subtitleOverride ?: remember(item) { buildMediaMetadataLine(item) }
    val posterGlassRim = remember(colors.rimHighlight, colors.hairline) {
        Brush.verticalGradient(
            colors = listOf(colors.rimHighlight, colors.hairline),
        )
    }

    Column(
        modifier = modifier
            .ravenSpringPress(interactionSource = interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick?.let { callback ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        callback()
                    }
                },
            )
            .testTag("raven_video_card_${item.id}"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(RavenFormatters.VIDEO_ASPECT_RATIO)
                .clip(RavenShapes.CardShape)
                .background(colors.surface1),
        ) {
            RavenImage(
                url = item.coverUrl,
                siteId = item.siteId,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // Subtle bottom contrast gradient behind duration pill / progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, colors.canvas.copy(alpha = 0.72f)),
                        ),
                    ),
            )

            if (!badgeText.isNullOrBlank()) {
                OverlayMicroBadge(
                    text = badgeText,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }

            if (durationLabel.isNotEmpty()) {
                OverlayMicroBadge(
                    text = durationLabel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 8.dp,
                            bottom = if (progressFraction != null && progressFraction > 0f) 9.dp else 7.dp,
                        ),
                )
            }

            if (progressFraction != null && progressFraction > 0f) {
                RavenCrimsonProgressBar(
                    fraction = progressFraction,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }

            // Inner 0.5dp translucent glass rim rendered over artwork so dark posters pop off #09090B OLED canvas
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(0.5.dp, posterGlassRim, RavenShapes.CardShape),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showMetadata && metadataLine.isNotEmpty()) {
                Text(
                    text = metadataLine,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Netflix-style Top-10 ranked video card displaying an oversized tabular rank numeral (`1`–`10`)
 * paired with [RavenVideoCard] inside horizontal shelves.
 */
@Composable
fun RavenRankedVideoCard(
    rank: Int,
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    cardWidth: Dp = 204.dp,
    progressFraction: Float? = null,
    badgeText: String? = null,
) {
    val colors = LocalRavenColors.current
    val posterHeight = cardWidth / RavenFormatters.VIDEO_ASPECT_RATIO
    val rankMinWidth = if (rank >= 10) 56.dp else 34.dp

    Row(
        modifier = modifier.testTag("raven_ranked_card_$rank"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = rankMinWidth)
                .height(posterHeight),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Text(
                text = rank.coerceAtLeast(1).toString(),
                style = MaterialTheme.typography.displayLarge.tabular().copy(
                    fontSize = 52.sp,
                    lineHeight = 52.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-2).sp,
                    shadow = Shadow(
                        color = colors.canvas,
                        offset = Offset(2f, 2f),
                        blurRadius = 6f,
                    ),
                ),
                color = colors.inkPrimary.copy(alpha = 0.88f),
                maxLines = 1,
                softWrap = false,
            )
        }

        RavenVideoCard(
            item = item,
            onClick = onClick,
            onLongClick = onLongClick,
            progressFraction = progressFraction,
            badgeText = badgeText,
            modifier = Modifier.width(cardWidth),
        )
    }
}

/**
 * Pure STATELESS frameless native-aspect-ratio card for comic and photo masonry grids.
 * Safely clamps aspect ratios via [RavenFormatters.clampAspectRatio] and displays a crisp
 * tabular page-count pill.
 */
@Composable
fun RavenGalleryCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    aspectRatioOverride: Float? = null,
    onAspectRatioResolved: ((Float) -> Unit)? = null,
    progressFraction: Float? = null,
    badgeText: String? = null,
    showCaption: Boolean = true,
) {
    val colors = LocalRavenColors.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    var resolvedRatio by remember(item.compositeKey) { mutableStateOf<Float?>(null) }
    val effectiveRatio = RavenFormatters.clampAspectRatio(
        aspectRatio = aspectRatioOverride ?: resolvedRatio ?: item.coverAspectRatio,
    )
    val pageLabel = remember(item.pageCount) { RavenFormatters.formatPageCount(item.pageCount) }
    val metadataLine = remember(item) { buildMediaMetadataLine(item) }
    val posterGlassRim = remember(colors.rimHighlight, colors.hairline) {
        Brush.verticalGradient(
            colors = listOf(colors.rimHighlight, colors.hairline),
        )
    }

    Column(
        modifier = modifier
            .ravenSpringPress(interactionSource = interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick?.let { callback ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        callback()
                    }
                },
            )
            .testTag("raven_gallery_card_${item.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(effectiveRatio)
                .clip(RavenShapes.CardShape)
                .background(colors.surface1),
        ) {
            RavenImage(
                url = item.coverUrl,
                siteId = item.siteId,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                onAspectRatioResolved = { ratio ->
                    val clamped = RavenFormatters.clampAspectRatio(ratio)
                    resolvedRatio = clamped
                    onAspectRatioResolved?.invoke(clamped)
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (!badgeText.isNullOrBlank()) {
                OverlayMicroBadge(
                    text = badgeText,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp),
                )
            }

            if (pageLabel.isNotEmpty()) {
                OverlayMicroBadge(
                    text = pageLabel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 7.dp,
                            bottom = if (progressFraction != null && progressFraction > 0f) 9.dp else 7.dp,
                        ),
                )
            }

            if (progressFraction != null && progressFraction > 0f) {
                RavenCrimsonProgressBar(
                    fraction = progressFraction,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }

            // Inner 0.5dp translucent glass rim rendered over poster artwork
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(0.5.dp, posterGlassRim, RavenShapes.CardShape),
            )
        }

        if (showCaption) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metadataLine.isNotEmpty()) {
                    Text(
                        text = metadataLine,
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Pure STATELESS horizontal media row for search results, watch/read history, shelves,
 * favorites, and downloads.
 */
@Composable
fun RavenCompactMediaRow(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    subtitle: String? = null,
    progressFraction: Float? = null,
    badgeText: String? = null,
    statusContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isVideo = item.kind == MediaKind.VIDEO
    val thumbWidth = if (isVideo) 118.dp else 68.dp
    val thumbHeight = if (isVideo) 68.dp else 90.dp
    val cornerLabel = remember(item.durationSeconds, item.pageCount, isVideo) {
        if (isVideo) {
            RavenFormatters.formatDuration(item.durationSeconds)
        } else {
            RavenFormatters.formatPageCount(item.pageCount)
        }
    }
    val resolvedSubtitle = subtitle ?: remember(item) { buildMediaMetadataLine(item) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress(interactionSource = interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick?.let { callback ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        callback()
                    }
                },
            )
            .padding(10.dp)
            .testTag("raven_compact_row_${item.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(thumbWidth)
                .height(thumbHeight)
                .clip(RavenShapes.ControlShape)
                .background(colors.surface2),
        ) {
            RavenImage(
                url = item.coverUrl,
                siteId = item.siteId,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            if (!badgeText.isNullOrBlank()) {
                OverlayMicroBadge(
                    text = badgeText,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                )
            }

            if (cornerLabel.isNotEmpty()) {
                OverlayMicroBadge(
                    text = cornerLabel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 4.dp,
                            bottom = if (progressFraction != null && progressFraction > 0f) 6.dp else 4.dp,
                        ),
                )
            }

            if (progressFraction != null && progressFraction > 0f) {
                RavenCrimsonProgressBar(
                    fraction = progressFraction,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (resolvedSubtitle.isNotEmpty()) {
                Text(
                    text = resolvedSubtitle,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (statusContent != null) {
                Spacer(modifier = Modifier.height(1.dp))
                statusContent()
            }
        }

        if (trailingContent != null) {
            trailingContent()
        }
    }
}

/**
 * `2.5dp` Raven Crimson progress bar for video resume & comic/photo reading progress.
 */
@Composable
fun RavenCrimsonProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 2.5.dp,
) {
    val colors = LocalRavenColors.current
    val clamped = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .height(trackHeight)
            .background(colors.inkPrimary.copy(alpha = 0.22f)),
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(trackHeight)
                    .background(colors.crimson),
            )
        }
    }
}

@Composable
private fun OverlayMicroBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Box(
        modifier = modifier
            .clip(RavenShapes.BadgeShape)
            .background(colors.badgeBackdrop)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = RavenTabularTextStyle.copy(
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = colors.inkPrimary,
            maxLines = 1,
        )
    }
}

private fun buildMediaMetadataLine(item: MediaItem): String = buildList {
    item.author?.name?.takeIf { it.isNotBlank() }?.let { add(it) }
    val views = RavenFormatters.formatCount(item.views)
    if (views.isNotEmpty()) add("$views 次")
    item.dateLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
    item.language?.takeIf { it.isNotBlank() && size < 2 }?.let { add(it) }
}.joinToString(" · ")
