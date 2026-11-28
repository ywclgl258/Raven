package com.raven.app.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenFrostedBox
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import kotlin.math.roundToInt

/** 阅读器控件层：底部控制栏、章节按钮、快捷栏按钮与登录门禁卡。 */

@Composable
internal fun ReaderBottomControlBar(
    uiState: ReaderUiState,
    onScrubStart: () -> Unit,
    onScrubPreview: (Int) -> Unit,
    onScrubCommit: (Int) -> Unit,
    onOpenThumbnails: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onToggleModePanel: () -> Unit,
    onToggleBrightnessPanel: () -> Unit,
    onOpenSettings: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenChapters: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val motionReduced = LocalMotionReduced.current
    val pages = uiState.pageNumbers
    val maxIdx = (pages.size - 1).coerceAtLeast(0)
    val currentIdx = remember(pages, uiState.currentPage) {
        pages.indexOf(uiState.currentPage).coerceIn(0, maxIdx)
    }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubIndex by remember(currentIdx) { mutableIntStateOf(currentIdx) }
    LaunchedEffect(isScrubbing, scrubIndex) {
        if (isScrubbing) {
            kotlinx.coroutines.delay(250L)
            val target = pages.getOrElse(scrubIndex) { uiState.currentPage }
            onScrubPreview(target)
        }
    }
    val displayPage = pages.getOrElse(if (isScrubbing) scrubIndex else currentIdx) { uiState.currentPage }
    val displayPercent = if (uiState.totalPages <= 0) 0 else ((displayPage.toFloat() / uiState.totalPages) * 100f).toInt().coerceIn(0, 100)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Floating target page pill shown while dragging scrubber
        AnimatedVisibility(
            visible = isScrubbing,
            enter = if (motionReduced) EnterTransition.None else fadeIn(tween(100)),
            exit = if (motionReduced) ExitTransition.None else fadeOut(tween(120)),
        ) {
            RavenFrostedBox(
                shape = RavenShapes.PillShape,
                reduceTransparency = reduceTransparency,
                borderColor = colors.rimHighlight,
                modifier = Modifier.testTag(ReaderTestTags.READER_SCRUBBER_PILL),
            ) {
                Text(
                    text = "第 ${RavenFormatters.formatPageProgress(displayPage, uiState.totalPages)} 页 · $displayPercent%",
                    style = RavenTabularTextStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.inkPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        RavenFrostedBox(
            shape = RectangleShape,
            reduceTransparency = reduceTransparency,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                if (uiState.isSerialComic) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReaderChapterButton(
                            icon = Icons.Outlined.SkipPrevious,
                            label = "上一话",
                            subtitle = uiState.previousChapter?.title
                                ?: if (uiState.isChapterListReady) "已是第一话" else "加载或重试",
                            enabled = !uiState.isChapterTransitionLoading &&
                                (!uiState.isChapterListReady || uiState.previousChapter != null),
                            onClick = onPreviousChapter,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reader_previous_chapter"),
                        )
                        ReaderChapterButton(
                            icon = Icons.Outlined.List,
                            label = "选话",
                            subtitle = if (uiState.chapters.isNotEmpty()) {
                                "共 ${uiState.chapters.size} 话"
                            } else {
                                "查看全部章节"
                            },
                            enabled = !uiState.isChapterTransitionLoading,
                            onClick = onOpenChapters,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(ReaderTestTags.READER_CHAPTER_PICKER),
                        )
                        ReaderChapterButton(
                            icon = Icons.Outlined.SkipNext,
                            label = "下一话",
                            subtitle = uiState.nextChapter?.title
                                ?: if (uiState.isChapterListReady) "已是最后一话" else "加载或重试",
                            enabled = !uiState.isChapterTransitionLoading &&
                                (!uiState.isChapterListReady || uiState.nextChapter != null),
                            onClick = onNextChapter,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reader_next_chapter"),
                        )
                    }
                }

                // Page Scrubber Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = displayPage.toString(),
                        style = RavenTabularTextStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.inkPrimary,
                        modifier = Modifier.width(34.dp),
                        textAlign = TextAlign.End,
                    )
                    Slider(
                        value = (if (isScrubbing) scrubIndex else currentIdx).toFloat(),
                        onValueChange = { raw ->
                            if (!isScrubbing) {
                                isScrubbing = true
                                onScrubStart()
                            }
                            scrubIndex = raw.roundToInt().coerceIn(0, maxIdx)
                        },
                        onValueChangeFinished = {
                            val target = pages.getOrElse(scrubIndex) { uiState.currentPage }
                            isScrubbing = false
                            onScrubCommit(target)
                        },
                        valueRange = 0f..maxIdx.coerceAtLeast(1).toFloat(),
                        enabled = pages.size > 1,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.inkPrimary,
                            activeTrackColor = colors.crimson,
                            inactiveTrackColor = colors.surface3,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = uiState.totalPages.toString(),
                        style = RavenTabularTextStyle,
                        color = colors.inkSecondary,
                        modifier = Modifier.width(34.dp),
                    )
                }

                // 5 Quick Action Buttons: 缩略图 / 书签 / 模式 / 亮度与自动翻页 / 更多设置
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderQuickBarButton(
                        icon = Icons.Outlined.GridView,
                        label = "缩略图",
                        active = uiState.activeSheet == ReaderSheet.THUMBNAILS,
                        onClick = onOpenThumbnails,
                        modifier = Modifier.weight(1f),
                    )
                    val bookmarkLabel = if (uiState.pageBookmarks.isNotEmpty()) "书签(${uiState.pageBookmarks.size})" else "书签"
                    ReaderQuickBarButton(
                        label = bookmarkLabel,
                        active = uiState.activeSheet == ReaderSheet.BOOKMARKS || uiState.isCurrentPageBookmarked,
                        onClick = onOpenBookmarks,
                        modifier = Modifier.weight(1f),
                    ) { tint ->
                        RavenMorphIcon(
                            glyph = if (uiState.pageBookmarks.isNotEmpty()) {
                                RavenMorphIcons.BookmarkFilled
                            } else {
                                RavenMorphIcons.BookmarkOutline
                            },
                            contentDescription = bookmarkLabel,
                            tint = tint,
                            size = 20.dp,
                            springPreset = MorphSpringPreset.SNAPPY,
                        )
                    }
                    ReaderQuickBarButton(
                        icon = Icons.Outlined.AutoStories,
                        label = readerModeLabel(uiState.preferences.mode),
                        active = uiState.activeQuickPanel == ReaderQuickPanel.MODE,
                        onClick = onToggleModePanel,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderQuickBarButton(
                        icon = Icons.Outlined.Brightness6,
                        label = if (uiState.autoPageIntervalSeconds > 0) "自动 ${uiState.autoPageIntervalSeconds}s" else "亮度/翻页",
                        active = uiState.activeQuickPanel == ReaderQuickPanel.BRIGHTNESS_AUTO_PAGE || uiState.autoPageIntervalSeconds > 0,
                        onClick = onToggleBrightnessPanel,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderQuickBarButton(
                        icon = Icons.Outlined.Tune,
                        label = "更多设置",
                        active = uiState.activeSheet == ReaderSheet.SETTINGS,
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
                }
            }
        }
    }
}

@Composable
internal fun ReaderChapterButton(
    icon: ImageVector,
    label: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RavenShapes.ControlShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.inkPrimary,
            disabledContentColor = colors.inkTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ReaderQuickBarButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderQuickBarButton(
        label = label,
        active = active,
        onClick = onClick,
        modifier = modifier,
    ) { tint ->
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun ReaderQuickBarButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconContent: @Composable (tint: Color) -> Unit,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        targetValue = if (active) colors.crimson else colors.inkPrimary,
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 150),
        label = "readerQuickBtnTint",
    )
    val labelColor by animateColorAsState(
        targetValue = if (active) colors.crimson else colors.inkSecondary,
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 150),
        label = "readerQuickBtnLabel",
    )

    Column(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(RavenShapes.ControlShape)
            .ravenSpringPress(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    feedback.perform(RavenFeedbackEffect.TAP)
                    onClick()
                },
            )
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        iconContent(tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ReaderAuthGateCard(
    siteName: String,
    message: String,
    onOpenAccount: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(horizontal = 20.dp, vertical = 28.dp)
            .testTag(ReaderTestTags.READER_AUTH_GATE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.crimson.copy(alpha = 0.14f))
                .border(0.5.dp, colors.crimson.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = "$siteName 需要登录验证",
            style = MaterialTheme.typography.titleLarge,
            color = colors.inkPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpenAccount,
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = "前往账号登录",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            OutlinedButton(
                onClick = onRetry,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface2,
                    contentColor = colors.inkPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
