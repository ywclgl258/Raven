package com.raven.app.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenColors
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenFrostedBox
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ambientHeroGlow
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.model.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Edge-to-edge Netflix-style Hero carousel with bottom obsidian vignette into [RavenColors.Canvas],
 * subtle ambient top glow (`alpha <= 0.18f`), metadata pills, solid white primary **Play** CTA,
 * frosted translucent **+ 稍后看** CTA, animated page dots, and auto-advance pause toggle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RavenHeroBanner(
    items: List<MediaItem>,
    onPlayClick: (MediaItem) -> Unit,
    onWatchLaterClick: (MediaItem) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    isInWatchLater: (MediaItem) -> Boolean = { false },
    primaryActionLabel: String = "播放",
    watchLaterLabel: String = "稍后看",
    inWatchLaterLabel: String = "已加入",
    bannerHeight: Dp = 430.dp,
    ambientTint: Color = RavenColors.Crimson,
    ambientGlowAlpha: Float = 0.15f,
    autoAdvanceMillis: Long = 6_000L,
) {
    if (items.isEmpty()) return

    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val reduceTransparency = LocalReduceTransparency.current
    val feedback = LocalRavenFeedback.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val effectiveBannerHeight = if (screenHeightDp in 1..600) {
        minOf(bannerHeight, (screenHeightDp * 0.60f).dp).coerceAtLeast(240.dp)
    } else {
        bannerHeight
    }
    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()
    var paused by rememberSaveable { mutableStateOf(false) }

    val canAutoAdvance = items.size > 1 && !paused && !motionReduced && autoAdvanceMillis > 0L
    LaunchedEffect(canAutoAdvance, pagerState.currentPage, pagerState.isScrollInProgress, items.size) {
        if (canAutoAdvance && !pagerState.isScrollInProgress) {
            delay(autoAdvanceMillis)
            val nextPage = (pagerState.currentPage + 1) % items.size
            pagerState.animateScrollToPage(nextPage)
        }
    }

    val specularRimBrush = remember(colors.inkPrimary, colors.hairline) {
        Brush.verticalGradient(
            colors = listOf(
                colors.inkPrimary.copy(alpha = 0.22f),
                colors.inkPrimary.copy(alpha = 0.08f),
                colors.hairline,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(effectiveBannerHeight)
            .background(colors.canvas)
            .testTag("raven_hero_banner"),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val item = items[pageIndex]
            val saved = isInWatchLater(item)
            val watchLaterIconTint by animateColorAsState(
                targetValue = if (saved) colors.crimson else colors.secondaryCtaInk,
                animationSpec = if (motionReduced) {
                    snap()
                } else {
                    tween(durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS)
                },
                label = "heroWatchLaterIconTint",
            )
            val watchLaterIconScale by animateFloatAsState(
                targetValue = if (saved) 1.12f else 1.0f,
                animationSpec = if (motionReduced) {
                    snap()
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                },
                label = "heroWatchLaterIconScale",
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .clickable(role = Role.Button) { onItemClick(item) },
            ) {
                RavenImage(
                    url = item.coverUrl,
                    siteId = item.siteId,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (!motionReduced && pageIndex < pagerState.pageCount) {
                                val offset = pagerState.getOffsetDistanceInPages(pageIndex)
                                val transform = RavenMotion.computeHeroPageTransform(offset)
                                translationX = transform.parallaxFraction * size.width
                                scaleX = transform.artworkScale
                                scaleY = transform.artworkScale
                            }
                        },
                )

                // Top status-bar scrim + capped ambient hero glow (strictly <= 0.18f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .align(Alignment.TopCenter)
                        .ambientHeroGlow(
                            tint = ambientTint,
                            alpha = RavenMotion.clampAmbientGlowAlpha(ambientGlowAlpha),
                        )
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    colors.canvas.copy(alpha = 0.65f),
                                    colors.scrimTop,
                                ),
                            ),
                        ),
                )

                // Deep multi-stop cinema vignette blending seamlessly into OLED Canvas (#09090B)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(effectiveBannerHeight * 0.72f)
                        .align(Alignment.BottomCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to colors.scrimTop,
                                    0.38f to colors.canvas.copy(alpha = 0.48f),
                                    0.72f to colors.canvas.copy(alpha = 0.88f),
                                    1.0f to colors.scrimBottom,
                                ),
                            ),
                        ),
                )

                // Foreground cinema title, metadata pills, and action CTAs
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .graphicsLayer {
                            if (!motionReduced && pageIndex < pagerState.pageCount) {
                                val offset = pagerState.getOffsetDistanceInPages(pageIndex)
                                val transform = RavenMotion.computeHeroPageTransform(offset)
                                alpha = transform.contentAlpha
                                translationX = offset.coerceIn(-1f, 1f) * size.width * 0.05f
                            }
                        }
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (items.size > 1) 52.dp else 20.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HeroMetadataRow(item = item)

                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.inkPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val playInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                feedback.perform(RavenFeedbackEffect.TAP)
                                onPlayClick(item)
                            },
                            interactionSource = playInteraction,
                            shape = RavenShapes.PillShape,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primaryCtaContainer,
                                contentColor = colors.primaryCtaInk,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .ravenSpringPress(interactionSource = playInteraction)
                                .testTag("hero_play_cta"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = primaryActionLabel,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        val watchLaterInteraction = remember { MutableInteractionSource() }
                        RavenFrostedBox(
                            shape = RavenShapes.PillShape,
                            reduceTransparency = reduceTransparency,
                            borderColor = Color.Transparent,
                            borderWidth = 0.dp,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .ravenSpringPress(interactionSource = watchLaterInteraction)
                                .clickable(
                                    interactionSource = watchLaterInteraction,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = {
                                        feedback.perform(
                                            if (saved) RavenFeedbackEffect.TOGGLE_OFF else RavenFeedbackEffect.FAVORITE,
                                        )
                                        onWatchLaterClick(item)
                                    },
                                )
                                .testTag("hero_watch_later_cta"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.secondaryCtaContainer)
                                    .border(
                                        width = 0.75.dp,
                                        brush = specularRimBrush,
                                        shape = RavenShapes.PillShape,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = watchLaterIconTint,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer {
                                            scaleX = watchLaterIconScale
                                            scaleY = watchLaterIconScale
                                        },
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (saved) inWatchLaterLabel else watchLaterLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.secondaryCtaInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Apple TV+ expanding pill indicators + pause/resume carousel toggle
        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("hero_page_indicators"),
                ) {
                    repeat(items.size) { index ->
                        val active = pagerState.currentPage == index
                        val dotWidth by animateDpAsState(
                            targetValue = if (active) 22.dp else 6.dp,
                            animationSpec = if (motionReduced) {
                                snap()
                            } else {
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                )
                            },
                            label = "heroDotWidth",
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (active) {
                                colors.crimson
                            } else {
                                colors.inkPrimary.copy(alpha = 0.36f)
                            },
                            animationSpec = if (motionReduced) {
                                snap()
                            } else {
                                tween(
                                    durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS,
                                    easing = RavenMotion.HarmonyDecelerationEasing,
                                )
                            },
                            label = "heroDotColor",
                        )
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 28.dp, minHeight = 48.dp)
                                .clickable(
                                    onClick = {
                                        if (!active) {
                                            feedback.perform(RavenFeedbackEffect.TAB_SWITCH)
                                            scope.launch { pagerState.animateScrollToPage(index) }
                                        }
                                    },
                                )
                                .semantics {
                                    contentDescription = "第 ${index + 1} 张推荐"
                                    selected = active
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(dotWidth)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        feedback.perform(RavenFeedbackEffect.TAP)
                        paused = !paused
                    },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .ravenSpringPress()
                        .semantics {
                            contentDescription = if (paused) "恢复轮播" else "暂停轮播"
                        }
                        .testTag("hero_pause_toggle"),
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(colors.badgeBackdrop)
                            .border(0.5.dp, colors.rimHighlight, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = null,
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroMetadataRow(item: MediaItem) {
    val colors = LocalRavenColors.current
    val pills = remember(item) {
        buildList {
            val duration = RavenFormatters.formatDuration(item.durationSeconds)
            if (duration.isNotEmpty()) add(duration)
            val pages = RavenFormatters.formatPageCount(item.pageCount)
            if (pages.isNotEmpty()) add(pages)
            val views = RavenFormatters.formatCount(item.views)
            if (views.isNotEmpty()) add("$views 观看")
            item.rating?.let { rating ->
                if (rating > 0f) add(formatOneDecimal(rating) + "★")
            }
            item.author?.name?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (size < 3) {
                item.tags.take(3 - size).forEach { tag ->
                    if (tag.label.isNotBlank()) add(tag.label)
                }
            }
        }
    }
    if (pills.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pills.forEach { text ->
            Box(
                modifier = Modifier
                    .clip(RavenShapes.PillShape)
                    .background(colors.badgeBackdrop)
                    .border(0.5.dp, colors.hairline, RavenShapes.PillShape)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(
                    text = text,
                    style = RavenTabularTextStyle,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatOneDecimal(value: Float): String {
    val tenths = kotlin.math.floor(value * 10.0 + 0.5).toInt()
    val whole = tenths / 10
    val frac = tenths % 10
    return "$whole.$frac"
}
