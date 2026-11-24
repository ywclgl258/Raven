package com.raven.app.feature.library

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule

/**
 * MediaKind filter option supporting `全部 / 视频 / 漫画 / 写真`.
 */
enum class LibraryMediaFilter(val label: String, val mediaKind: MediaKind?) {
    ALL("全部", null),
    VIDEO("视频", MediaKind.VIDEO),
    COMIC("漫画", MediaKind.COMIC),
    PHOTO("写真", MediaKind.PHOTO);

    companion object {
        fun fromKind(kind: MediaKind?): LibraryMediaFilter = when (kind) {
            null -> ALL
            MediaKind.VIDEO -> VIDEO
            MediaKind.COMIC -> COMIC
            MediaKind.PHOTO -> PHOTO
        }
    }
}

@Composable
internal fun LibraryMediaFilterChips(
    selected: LibraryMediaFilter,
    onSelect: (LibraryMediaFilter) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    RavenFilterChipRow(
        items = LibraryMediaFilter.entries,
        selectedItem = selected,
        labelProvider = { it.label },
        onItemSelected = onSelect,
        keyProvider = { it.name },
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

@Composable
internal fun LibrarySiteFilterChips(
    options: List<SiteFilterOption>,
    selectedSiteId: SiteId?,
    onSelectSite: (SiteId?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    if (options.isEmpty()) return
    val allEntries: List<SiteFilterOption?> = remember(options) { listOf(null) + options }
    val selectedOption = remember(options, selectedSiteId) {
        if (selectedSiteId == null) null else options.firstOrNull { it.siteId == selectedSiteId }
    }
    RavenFilterChipRow(
        items = allEntries,
        selectedItem = selectedOption,
        labelProvider = { it?.label ?: "全部站点" },
        onItemSelected = { onSelectSite(it?.siteId) },
        keyProvider = { it?.siteId?.value ?: "_all_sites" },
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

/**
 * Obsidian Apple HIG text field matching [com.raven.app.core.designsystem.component.RavenSearchField]
 * geometry with zero hardcoded colors.
 */
@Composable
internal fun LibraryTextInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 4,
    imeAction: ImeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    val colors = LocalRavenColors.current
    var focused by remember { mutableStateOf(false) }

    val borderColor = when {
        isError -> colors.error
        focused -> colors.crimson.copy(alpha = 0.78f)
        else -> colors.hairline
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkSecondary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 48.dp else 88.dp)
                .clip(RavenShapes.ControlShape)
                .background(if (enabled) colors.surface2 else colors.surface1)
                .border(
                    width = if (focused || isError) 1.dp else 0.5.dp,
                    color = borderColor,
                    shape = RavenShapes.ControlShape,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkTertiary,
                    maxLines = if (singleLine) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) colors.inkPrimary else colors.inkTertiary,
                ),
                cursorBrush = SolidColor(colors.crimson),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction?.invoke() },
                    onGo = { onImeAction?.invoke() },
                    onSearch = { onImeAction?.invoke() },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state -> focused = state.isFocused },
            )
        }
        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) colors.error else colors.inkTertiary,
            )
        }
    }
}

/**
 * Compact Obsidian banner used for transient action results, import status, or network alerts.
 */
@Composable
internal fun LibraryFeedbackBanner(
    message: String,
    tone: RavenStatusTone = RavenStatusTone.NEUTRAL,
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val accent = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.inkSecondary
        RavenStatusTone.ACCENT -> colors.crimson
        RavenStatusTone.SUCCESS -> colors.success
        RavenStatusTone.WARNING -> colors.warning
        RavenStatusTone.ERROR -> colors.error
    }
    val container = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.surface2
        else -> accent.copy(alpha = 0.14f)
    }
    val borderColor = when (tone) {
        RavenStatusTone.NEUTRAL -> colors.hairline
        else -> accent.copy(alpha = 0.30f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.ControlShape)
            .background(container)
            .border(0.5.dp, borderColor, RavenShapes.ControlShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
        }
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭提示",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Compact Obsidian metric tile for Library Hub (`历史 / 收藏 / 下载 / 更新`).
 */
@Composable
internal fun LibraryMetricTile(
    title: String,
    count: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightBadgeCount: Int = 0,
    subtitle: String? = null,
    testTag: String? = null,
) {
    val colors = LocalRavenColors.current
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(12.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlightBadgeCount > 0) colors.crimson else colors.inkPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (highlightBadgeCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RavenShapes.PillShape)
                        .background(colors.crimson)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (highlightBadgeCount > 99) "99+" else highlightBadgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall.tabular().copy(fontWeight = FontWeight.Bold),
                        color = colors.inkPrimary,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge.tabular().copy(fontWeight = FontWeight.Bold),
                color = colors.inkPrimary,
                maxLines = 1,
            )
            Text(
                text = subtitle ?: title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Single-screen Obsidian confirmation bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryConfirmBottomSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    val colors = LocalRavenColors.current

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = title,
        subtitle = message,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface1,
                    contentColor = colors.inkPrimary,
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("取消", style = MaterialTheme.typography.labelLarge)
            }

            Button(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) colors.error else colors.primaryCtaContainer,
                    contentColor = if (destructive) colors.inkPrimary else colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .ravenSpringPress(),
            ) {
                Text(
                    text = confirmLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/**
 * Multi-select contextual bottom bar for batch add-to-shelf / batch delete across Library screens.
 */
@Composable
internal fun LibrarySelectionActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onAddToShelf: () -> Unit,
    onDeleteSelected: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 $selectedCount 项",
            style = MaterialTheme.typography.labelLarge.tabular(),
            color = colors.inkPrimary,
        )
        TextButton(onClick = onSelectAll) {
            Text("全选", color = colors.inkSecondary)
        }
        Spacer(modifier = Modifier.weight(1f))
        if (onDeleteSelected != null) {
            OutlinedButton(
                onClick = onDeleteSelected,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.error.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("移除", style = MaterialTheme.typography.labelMedium)
            }
        }
        Button(
            onClick = onAddToShelf,
            enabled = selectedCount > 0,
            shape = RavenShapes.PillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primaryCtaContainer,
                contentColor = colors.primaryCtaInk,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("加入片单", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        }
        IconButton(onClick = onClearSelection) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "退出多选",
                tint = colors.inkSecondary,
            )
        }
    }
}

/**
 * Single-screen `RavenBottomSheet` for adding 1 or multiple works to any shelf (or creating one inline).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfPickerBottomSheet(
    shelves: List<ShelfWithSummary>,
    itemCount: Int,
    onSelectShelf: (shelfId: String) -> Unit,
    onCreateAndAdd: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    var creatingNew by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "加入片单",
        subtitle = "将 $itemCount 项作品保存到指定片单",
        modifier = Modifier.testTag("shelf_picker_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RavenGroupedSection(title = "选择片单") {
                shelves.forEachIndexed { index, summary ->
                    if (index > 0) RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = summary.shelf.name,
                        subtitle = summary.shelf.note.takeIf { it.isNotBlank() },
                        value = "${summary.itemCount} 项",
                        icon = if (summary.shelf.isPinned) Icons.Outlined.PushPin else Icons.Outlined.BookmarkBorder,
                        iconTint = if (summary.shelf.id == Shelf.WATCH_LATER_ID) colors.crimson else colors.inkPrimary,
                        showChevron = true,
                        onClick = {
                            onSelectShelf(summary.shelf.id)
                            onDismiss()
                        },
                    )
                }
            }

            if (!creatingNew) {
                OutlinedButton(
                    onClick = { creatingNew = true },
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface1,
                        contentColor = colors.inkPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("新建片单并加入", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                RavenGroupedSection(title = "新建片单") {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LibraryTextInputField(
                            value = newName,
                            onValueChange = { newName = it.take(ShelfRepository.MAX_SHELF_NAME_LENGTH) },
                            placeholder = "输入新片单名称",
                            onImeAction = {
                                if (newName.trim().isNotEmpty()) {
                                    onCreateAndAdd(newName.trim())
                                    onDismiss()
                                }
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            TextButton(onClick = { creatingNew = false }) {
                                Text("取消", color = colors.inkSecondary)
                            }
                            Button(
                                onClick = {
                                    val trimmed = newName.trim()
                                    if (trimmed.isNotEmpty()) {
                                        onCreateAndAdd(trimmed)
                                        onDismiss()
                                    }
                                },
                                enabled = newName.trim().isNotEmpty(),
                                shape = RavenShapes.PillShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primaryCtaContainer,
                                    contentColor = colors.primaryCtaInk,
                                ),
                            ) {
                                Text("创建并加入")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single-screen Create / Edit Custom Shelf `RavenBottomSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfEditorBottomSheet(
    editingShelf: Shelf?,
    onSave: (name: String, note: String, isPinned: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val isSystem = editingShelf?.isSystem == true || editingShelf?.id == Shelf.WATCH_LATER_ID
    var name by rememberSaveable(editingShelf?.id) { mutableStateOf(editingShelf?.name.orEmpty()) }
    var note by rememberSaveable(editingShelf?.id) { mutableStateOf(editingShelf?.note.orEmpty()) }
    var isPinned by rememberSaveable(editingShelf?.id) { mutableStateOf(editingShelf?.isPinned ?: false) }

    val trimmedName = name.trim()
    val canSave = trimmedName.isNotEmpty() && trimmedName.length <= ShelfRepository.MAX_SHELF_NAME_LENGTH

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = if (editingShelf == null) "新建片单" else "编辑片单",
        subtitle = if (isSystem) "内置稍后看片单可编辑备注与置顶状态" else "支持备注说明与置顶排序",
        modifier = Modifier.testTag("shelf_editor_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RavenGroupedSection(title = "基本信息") {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LibraryTextInputField(
                        value = name,
                        onValueChange = { if (!isSystem) name = it.take(ShelfRepository.MAX_SHELF_NAME_LENGTH) },
                        label = "片单名称",
                        placeholder = "例如：周末精选、经典补番",
                        enabled = !isSystem,
                        supportingText = if (isSystem) {
                            "内置「稍后看」片单不可修改名称"
                        } else {
                            "${trimmedName.length} / ${ShelfRepository.MAX_SHELF_NAME_LENGTH}"
                        },
                    )
                    LibraryTextInputField(
                        value = note,
                        onValueChange = { note = it.take(ShelfRepository.MAX_SHELF_NOTE_LENGTH) },
                        label = "备注（可选）",
                        placeholder = "记录片单主题、观看顺序或收藏说明…",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        supportingText = "${note.length} / ${ShelfRepository.MAX_SHELF_NOTE_LENGTH}",
                    )
                }
                RavenGroupedDivider()
                RavenGroupedSwitchRow(
                    title = "置顶片单",
                    subtitle = "在片库片单列表顶部优先显示",
                    icon = Icons.Outlined.PushPin,
                    checked = isPinned,
                    onCheckedChange = { isPinned = it },
                )
            }

            if (onDelete != null && !isSystem) {
                RavenGroupedSection {
                    RavenGroupedRow(
                        title = "删除片单",
                        subtitle = "仅删除片单分组，不会删除已下载或收藏记录",
                        icon = Icons.Outlined.DeleteOutline,
                        destructive = true,
                        onClick = onDelete,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface1,
                        contentColor = colors.inkPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("取消")
                }

                Button(
                    onClick = {
                        if (canSave) {
                            onSave(trimmedName, note.trim(), isPinned)
                            onDismiss()
                        }
                    },
                    enabled = canSave,
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .ravenSpringPress()
                        .testTag("shelf_editor_save_button"),
                ) {
                    Text(
                        text = if (editingShelf == null) "创建片单" else "保存修改",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

/**
 * Single-screen Create / Edit Smart Shelf `RavenBottomSheet` selecting rule (`UNFINISHED` /
 * `OFFLINE_UNSEEN` / `RECENT`), optional `mediaKindFilter`, optional `siteIdFilter`, pin toggle,
 * and name on one scrollable sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SmartShelfEditorBottomSheet(
    editingShelf: SmartShelf?,
    siteOptions: List<SiteFilterOption>,
    onSave: (
        name: String,
        rule: SmartShelfRule,
        mediaKindFilter: MediaKind?,
        siteIdFilter: SiteId?,
        isPinned: Boolean,
    ) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    var name by rememberSaveable(editingShelf?.id) { mutableStateOf(editingShelf?.name.orEmpty()) }
    var selectedRule by rememberSaveable(editingShelf?.id) {
        mutableStateOf(editingShelf?.rule ?: SmartShelfRule.UNFINISHED)
    }
    var selectedKind by rememberSaveable(editingShelf?.id) {
        mutableStateOf(editingShelf?.mediaKindFilter)
    }
    var selectedSiteIdRaw by rememberSaveable(editingShelf?.id) {
        mutableStateOf(editingShelf?.siteIdFilter?.value)
    }
    var isPinned by rememberSaveable(editingShelf?.id) {
        mutableStateOf(editingShelf?.isPinned ?: false)
    }

    val selectedSiteId = selectedSiteIdRaw?.let(SiteId::of)
    val suggestedName = remember(selectedRule, selectedKind, selectedSiteId, siteOptions) {
        buildSuggestedSmartShelfName(
            rule = selectedRule,
            mediaKind = selectedKind,
            siteLabel = selectedSiteId?.let { id -> siteOptions.firstOrNull { it.siteId == id }?.label ?: id.value },
        )
    }
    val effectiveName = name.trim().ifBlank { suggestedName }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = if (editingShelf == null) "新建智能片单" else "编辑智能片单",
        subtitle = "根据规则实时聚合跨站内容，单页配置完成",
        modifier = Modifier.testTag("smart_shelf_editor_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RavenGroupedSection(title = "名称与置顶") {
                Column(modifier = Modifier.padding(14.dp)) {
                    LibraryTextInputField(
                        value = name,
                        onValueChange = { name = it.take(ShelfRepository.MAX_SHELF_NAME_LENGTH) },
                        label = "智能片单名称",
                        placeholder = "留空将自动命名为「$suggestedName」",
                    )
                }
                RavenGroupedDivider()
                RavenGroupedSwitchRow(
                    title = "置顶智能片单",
                    icon = Icons.Outlined.PushPin,
                    checked = isPinned,
                    onCheckedChange = { isPinned = it },
                )
            }

            RavenGroupedSection(title = "聚合规则") {
                SmartShelfRule.entries.forEachIndexed { index, rule ->
                    if (index > 0) RavenGroupedDivider()
                    val selected = selectedRule == rule
                    RavenGroupedRow(
                        title = rule.displayLabel(),
                        subtitle = rule.description(),
                        onClick = { selectedRule = rule },
                        trailingContent = {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = "已选中",
                                    tint = colors.crimson,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
            }

            RavenGroupedSection(title = "内容类型（可选）") {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryMediaFilter.entries.forEach { option ->
                        RavenFilterChip(
                            label = option.label,
                            selected = selectedKind == option.mediaKind,
                            onClick = { selectedKind = option.mediaKind },
                        )
                    }
                }
            }

            RavenGroupedSection(title = "限定站点（可选）") {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RavenFilterChip(
                        label = "全部站点",
                        selected = selectedSiteId == null,
                        onClick = { selectedSiteIdRaw = null },
                    )
                    val filteredSites = remember(siteOptions, selectedKind) {
                        if (selectedKind == null) {
                            siteOptions
                        } else {
                            siteOptions.filter { it.primaryKind == null || it.primaryKind == selectedKind }
                        }
                    }
                    filteredSites.forEach { site ->
                        RavenFilterChip(
                            label = site.label,
                            selected = selectedSiteId == site.siteId,
                            onClick = { selectedSiteIdRaw = site.siteId.value },
                        )
                    }
                }
            }

            if (onDelete != null) {
                RavenGroupedSection {
                    RavenGroupedRow(
                        title = "删除智能片单",
                        icon = Icons.Outlined.DeleteOutline,
                        destructive = true,
                        onClick = onDelete,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface1,
                        contentColor = colors.inkPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("取消")
                }

                Button(
                    onClick = {
                        onSave(
                            effectiveName,
                            selectedRule,
                            selectedKind,
                            selectedSiteId,
                            isPinned,
                        )
                        onDismiss()
                    },
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .ravenSpringPress()
                        .testTag("smart_shelf_editor_save_button"),
                ) {
                    Text(
                        text = if (editingShelf == null) "创建智能片单" else "保存修改",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

internal fun buildSuggestedSmartShelfName(
    rule: SmartShelfRule,
    mediaKind: MediaKind?,
    siteLabel: String?,
): String = buildList {
    siteLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
    mediaKind?.let { add(it.displayLabel()) }
    add(
        when (rule) {
            SmartShelfRule.UNFINISHED -> "未看完"
            SmartShelfRule.OFFLINE_UNSEEN -> "已离线未看"
            SmartShelfRule.RECENT -> "近 30 天新增"
        },
    )
}.joinToString(" · ")

@Composable
internal fun SelectCheckBadge(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) colors.crimson else colors.surface2)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) colors.crimson else colors.outlineSubtle,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "已选中",
                tint = colors.inkPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
