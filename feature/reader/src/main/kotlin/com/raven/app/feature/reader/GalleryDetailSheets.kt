package com.raven.app.feature.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenMorphToggleIcon
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.GalleryPage
import com.raven.app.core.model.PageBookmark

/** 图集详情页面板层：页书签、预览瓦片、登录门禁卡、选择性下载 Sheet、分组行与标签/作者操作 Sheet。 */

@Composable
internal fun PageBookmarksSection(
    bookmarks: List<PageBookmark>,
    onJumpToPage: (Int) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RavenSectionHeader(
            title = "页码书签",
            subtitle = "${bookmarks.size} 条 · 点击跳转",
        )
        RavenGroupedSection(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            bookmarks.forEachIndexed { index, bookmark ->
                if (index > 0) RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "第 ${bookmark.page} 页",
                    subtitle = bookmark.note.takeIf(String::isNotBlank)
                        ?: if (bookmark.totalPages > 0) "共 ${bookmark.totalPages} 页" else null,
                    icon = Icons.Filled.Bookmark,
                    iconTint = colors.crimson,
                    onClick = { onJumpToPage(bookmark.page) },
                    trailingContent = {
                        IconButton(onClick = { onDeleteBookmark(bookmark.id) }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "删除书签",
                                tint = colors.inkSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun PreviewPageTile(
    siteId: com.raven.app.core.model.SiteId,
    pageNumber: Int,
    page: GalleryPage?,
    isCurrentPage: Boolean,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val thumbUrl = page?.thumbUrl?.takeIf(String::isNotBlank)
        ?: page?.imageUrl?.takeIf(String::isNotBlank)
        ?: ""
    val pageWidth = page?.width
    val pageHeight = page?.height
    val ratio = if (pageWidth != null && pageHeight != null && pageWidth > 0 && pageHeight > 0) {
        RavenFormatters.clampAspectRatio(pageWidth.toFloat() / pageHeight.toFloat(), defaultRatio = 0.72f)
    } else {
        0.72f
    }

    Box(
        modifier = modifier
            .aspectRatio(ratio)
            .clip(RavenShapes.ControlShape)
            .background(colors.surface1)
            .border(
                width = if (isCurrentPage) 2.dp else 0.5.dp,
                color = if (isCurrentPage) colors.crimson else colors.hairline,
                shape = RavenShapes.ControlShape,
            )
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (thumbUrl.isNotBlank()) {
            RavenImage(
                url = thumbUrl,
                siteId = siteId,
                contentDescription = "第 $pageNumber 页",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(colors.surface2, colors.surface1)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = null,
                        tint = colors.inkTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "P$pageNumber",
                        style = RavenTabularTextStyle,
                        color = colors.inkSecondary,
                    )
                }
            }
        }

        // Bottom-end tabular page badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .clip(RavenShapes.BadgeShape)
                .background(if (isCurrentPage) colors.crimson else colors.badgeBackdrop)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = pageNumber.toString(),
                style = RavenTabularTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = colors.inkPrimary,
            )
        }

        if (isBookmarked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(colors.badgeBackdrop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = "已加书签",
                    tint = colors.crimson,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
internal fun GalleryAuthGateCard(
    siteName: String,
    message: String,
    onOpenAccount: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(horizontal = 20.dp, vertical = 28.dp)
            .testTag(ReaderTestTags.GALLERY_DETAIL_AUTH_GATE),
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    text = "刷新状态",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SelectiveDownloadBottomSheet(
    totalPages: Int,
    resumePage: Int?,
    sheetState: DownloadSheetState,
    onDismiss: () -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onRangeChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalRavenColors.current

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "选择下载页码",
        subtitle = "共 $totalPages 页 · 支持整本或自定义页码范围",
        modifier = Modifier.testTag(ReaderTestTags.GALLERY_DOWNLOAD_SHEET),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RavenGroupedSection {
                RavenGroupedRow(
                    title = "下载整本",
                    subtitle = "完整保存全部 $totalPages 页以便离线阅读",
                    icon = if (sheetState.downloadAll) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    iconTint = if (sheetState.downloadAll) colors.crimson else colors.inkSecondary,
                    onClick = { onSelectAll(true) },
                )
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "自定义页码范围",
                    subtitle = "输入如 1, 3, 5-8（自动去重并校验范围）",
                    icon = if (!sheetState.downloadAll) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    iconTint = if (!sheetState.downloadAll) colors.crimson else colors.inkSecondary,
                    onClick = { onSelectAll(false) },
                )

                if (!sheetState.downloadAll) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = sheetState.rangeInput,
                            onValueChange = onRangeChange,
                            label = { Text("页码范围") },
                            placeholder = { Text("例如：1, 3, 5-8") },
                            singleLine = true,
                            isError = sheetState.validationError != null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                            supportingText = {
                                val err = sheetState.validationError
                                if (err != null) {
                                    Text(text = err, color = colors.error)
                                } else {
                                    Text(
                                        text = "已解析 ${sheetState.parsedPages.size} 页（范围 1..$totalPages）",
                                        style = MaterialTheme.typography.bodySmall.tabular(),
                                        color = colors.success,
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.crimson,
                                unfocusedBorderColor = colors.outlineSubtle,
                                errorBorderColor = colors.error,
                                focusedTextColor = colors.inkPrimary,
                                unfocusedTextColor = colors.inkPrimary,
                                cursorColor = colors.crimson,
                            ),
                            shape = RavenShapes.ControlShape,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Quick range presets
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (totalPages > 20) {
                                RavenFilterChip(
                                    label = "前 20 页",
                                    selected = sheetState.rangeInput.trim() == "1-20",
                                    onClick = { onRangeChange("1-20") },
                                )
                            }
                            if (resumePage != null && resumePage in 2..totalPages) {
                                val rangeText = "$resumePage-$totalPages"
                                RavenFilterChip(
                                    label = "第 $resumePage 页至末尾",
                                    selected = sheetState.rangeInput.trim() == rangeText,
                                    onClick = { onRangeChange(rangeText) },
                                )
                            }
                            RavenFilterChip(
                                label = "全部 (1-$totalPages)",
                                selected = sheetState.rangeInput.trim() == "1-$totalPages",
                                onClick = { onRangeChange("1-$totalPages") },
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onConfirm,
                enabled = !sheetState.isSubmitting &&
                    (sheetState.downloadAll || (sheetState.validationError == null && sheetState.parsedPages.isNotEmpty())),
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .ravenSpringPress(),
            ) {
                if (sheetState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.primaryCtaInk,
                    )
                } else {
                    val label = if (sheetState.downloadAll) {
                        "开始下载整本 ($totalPages 页)"
                    } else {
                        "下载已选 ${sheetState.parsedPages.size} 页"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.tabular().copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
internal fun GalleryMorphGroupedRow(
    title: String,
    onClick: () -> Unit,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
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
        label = "galleryMorphGroupedRowBackdrop",
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
            modifier = Modifier
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
internal fun TagActionBottomSheet(
    state: TagActionState,
    blockTagsGlobally: Boolean,
    onDismiss: () -> Unit,
    onSearchTag: (crossSite: Boolean) -> Unit,
    onCopyTag: (text: String) -> Unit,
    onToggleSubscription: () -> Unit,
    onToggleBlocked: () -> Unit,
    onSaveCustomTranslation: (String) -> Unit,
) {
    val colors = LocalRavenColors.current
    var isEditingTranslation by remember(state.tag.slug, state.tag.namespace) { mutableStateOf(false) }
    var editedChineseName by remember(state.tag.slug, state.tag.namespace, state.translatedLabel) {
        mutableStateOf(
            if (state.translatedLabel != state.tag.slug && state.translatedLabel != state.tag.label) {
                state.translatedLabel
            } else {
                ""
            },
        )
    }

    val subtitle = buildList {
        if (state.tag.namespace.isNotBlank()) add(state.tag.namespace)
        if (state.tag.slug != state.translatedLabel) add(state.tag.slug)
    }.joinToString(" · ").takeIf(String::isNotBlank)

    val copyText = tagClipboardText(state.translatedLabel, state.tag)
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = state.translatedLabel,
        subtitle = subtitle,
        modifier = Modifier.testTag(ReaderTestTags.GALLERY_TAG_ACTION_SHEET),
    ) {
        RavenGroupedSection(title = "搜索标签") {
            RavenGroupedRow(
                title = "本站搜索该标签",
                subtitle = "在当前搜索站点查找该标签作品",
                icon = Icons.Outlined.Search,
                showChevron = true,
                onClick = { onSearchTag(false) },
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "跨站搜索该标签",
                subtitle = "在全部可搜索站点中查找该标签作品",
                icon = Icons.Outlined.TravelExplore,
                showChevron = true,
                onClick = { onSearchTag(true) },
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "复制标签",
                subtitle = "复制可读标签名「$copyText」到剪贴板",
                icon = Icons.Outlined.ContentCopy,
                enabled = copyText.isNotBlank(),
                onClick = { onCopyTag(copyText) },
            )
            RavenGroupedDivider(insetWithIcon = true)
            GalleryMorphGroupedRow(
                title = if (state.isSubscribed) "取消订阅标签更新" else "订阅标签更新",
                onClick = onToggleSubscription,
                leadingIcon = {
                    RavenMorphToggleIcon(
                        active = state.isSubscribed,
                        activeGlyph = RavenMorphIcons.BellActive,
                        inactiveGlyph = RavenMorphIcons.BellOutline,
                        activeTint = colors.crimson,
                        inactiveTint = colors.inkPrimary,
                        size = 18.dp,
                        springPreset = MorphSpringPreset.BOUNCY,
                    )
                },
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "编辑中文译名",
                subtitle = if (state.translatedLabel != state.tag.slug) {
                    "当前译名：${state.translatedLabel}"
                } else {
                    "为该标签自定义中文译名并立即全局生效"
                },
                icon = Icons.Outlined.Translate,
                iconTint = if (isEditingTranslation) colors.crimson else colors.inkPrimary,
                onClick = { isEditingTranslation = !isEditingTranslation },
                modifier = Modifier.testTag("gallery_tag_edit_translation_row"),
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
                            .testTag("gallery_tag_edit_translation_field"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { isEditingTranslation = false },
                            shape = RavenShapes.PillShape,
                            border = BorderStroke(0.5.dp, colors.outlineSubtle),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = colors.surface2,
                                contentColor = colors.inkSecondary,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                        ) {
                            Text("取消")
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
                                .testTag("gallery_tag_edit_translation_save"),
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
                    state.isBlocked -> "取消屏蔽该标签"
                    blockTagsGlobally -> "全局屏蔽该标签"
                    else -> "在当前站点屏蔽该标签"
                },
                subtitle = when {
                    state.isBlocked -> "当前已被过滤隐藏，点击立即解除屏蔽"
                    blockTagsGlobally -> "屏蔽后将在全部站点同步生效并自动注入搜索排除"
                    else -> "仅影响当前站点，不污染其他来源"
                },
                icon = Icons.Outlined.Block,
                destructive = !state.isBlocked,
                onClick = onToggleBlocked,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthorActionBottomSheet(
    author: AuthorRef,
    onDismiss: () -> Unit,
    onCopyAuthorName: () -> Unit,
    onSearchAuthor: (crossSite: Boolean) -> Unit,
) {
    val displayName = authorClipboardText(author)
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = displayName.ifBlank { "作者" },
        subtitle = author.role.takeIf { it.isNotBlank() } ?: "作者操作",
        modifier = Modifier.testTag("gallery_author_action_sheet"),
    ) {
        RavenGroupedSection {
            RavenGroupedRow(
                title = "复制作者名",
                subtitle = "复制「$displayName」到剪贴板",
                icon = Icons.Outlined.ContentCopy,
                enabled = displayName.isNotBlank(),
                onClick = onCopyAuthorName,
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "本站搜索作者",
                subtitle = "在当前搜索站点查找该作者作品",
                icon = Icons.Outlined.Search,
                showChevron = true,
                onClick = { onSearchAuthor(false) },
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "跨站搜索作者",
                subtitle = "在全部可搜索站点中查找该作者作品",
                icon = Icons.Outlined.TravelExplore,
                showChevron = true,
                onClick = { onSearchAuthor(true) },
            )
        }
    }
}

