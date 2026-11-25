package com.raven.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.DownloadItem


@Composable
internal fun DownloadsTagPanel(
    availableTagCount: Int,
    visibleTags: List<DownloadTagOption>,
    namespaces: List<DownloadTagNamespace>,
    selectedNamespace: String?,
    selectedTagKeys: Set<String>,
    selectedTags: List<DownloadTagOption>,
    matchAll: Boolean,
    onToggleTag: (String) -> Unit,
    onSelectNamespace: (String?) -> Unit,
    onToggleMatchAll: () -> Unit,
    onClearTags: () -> Unit,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("downloads_tag_panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "标签快筛",
                style = MaterialTheme.typography.labelLarge,
                color = colors.inkPrimary,
                modifier = Modifier.semantics { heading() },
            )
            if (availableTagCount > 0) {
                Text(
                    text = " · $availableTagCount",
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = colors.inkTertiary,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (selectedTagKeys.size > 1) {
                TextButton(onClick = onToggleMatchAll) {
                    Text(if (matchAll) "同时包含全部" else "包含任一标签", color = colors.crimson)
                }
            }
            if (selectedTagKeys.isNotEmpty()) {
                TextButton(onClick = onClearTags, modifier = Modifier.testTag("downloads_clear_tags")) {
                    Text("清除 ${selectedTagKeys.size}", color = colors.inkSecondary)
                }
            }
        }

        if (availableTagCount == 0) {
            Text(
                text = "浏览作品详情后，已识别的标签会出现在这里，可直接搜索与分组。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkTertiary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }

        if (selectedTags.isNotEmpty()) {
            Text(
                text = selectedTags.joinToString(" · ") { it.label },
                style = MaterialTheme.typography.bodySmall,
                color = colors.crimson,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (namespaces.size > 1) {
            val options: List<DownloadTagNamespace?> = listOf(null) + namespaces
            RavenFilterChipRow(
                items = options,
                selectedItem = namespaces.firstOrNull { it.key == selectedNamespace },
                labelProvider = { option -> option?.let { "${it.label} ${it.tagCount}" } ?: "全部标签" },
                onItemSelected = { onSelectNamespace(it?.key) },
                keyProvider = { it?.key ?: "__all_namespaces__" },
            )
        }

        if (visibleTags.isEmpty()) {
            Text(
                text = "当前类别或关键词下没有匹配标签，下载标题搜索仍然有效。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkTertiary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleTags, key = DownloadTagOption::key) { tag ->
                    RavenFilterChip(
                        label = "${tag.label} ${tag.count}",
                        selected = tag.key in selectedTagKeys,
                        onClick = { onToggleTag(tag.key) },
                        modifier = Modifier.testTag("download_tag_${tag.key}"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DownloadSectionHeader(
    section: DownloadSection,
    onToggle: () -> Unit,
) {
    val colors = LocalRavenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("download_section_${section.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = listOfNotNull(
                    "${section.items.size} 项",
                    section.subtitle,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (section.isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.KeyboardArrowUp,
                contentDescription = if (section.isCollapsed) "展开${section.title}" else "收起${section.title}",
                tint = colors.inkSecondary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DownloadLayoutSheet(
    selectedSort: DownloadSort,
    selectedGroupMode: DownloadGroupMode,
    customGroupCount: Int,
    onSelectSort: (DownloadSort) -> Unit,
    onSelectGroupMode: (DownloadGroupMode) -> Unit,
    onManageGroups: () -> Unit,
    onDismiss: () -> Unit,
) {
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "排列与分组",
        subtitle = "调整下载与收藏的展示方式，不改变实际下载队列",
        modifier = Modifier.testTag("downloads_layout_sheet"),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RavenGroupedSection(title = "排序方式") {
                FlowRow(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DownloadSort.entries.forEach { sort ->
                        RavenFilterChip(
                            label = sort.label,
                            selected = sort == selectedSort,
                            onClick = { onSelectSort(sort) },
                            modifier = Modifier.testTag("download_sort_${sort.name}"),
                        )
                    }
                }
            }
            RavenGroupedSection(
                title = "列表分组",
                footer = "按标签分组时，同一作品会出现在它所属的每个标签分区中。",
            ) {
                FlowRow(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DownloadGroupMode.entries.forEach { mode ->
                        RavenFilterChip(
                            label = mode.label,
                            selected = mode == selectedGroupMode,
                            onClick = { onSelectGroupMode(mode) },
                            modifier = Modifier.testTag("download_group_mode_${mode.name}"),
                        )
                    }
                }
            }
            RavenGroupedSection {
                RavenGroupedRow(
                    title = "管理自定义分组",
                    subtitle = "新建、批量归类、重命名或合并",
                    value = "$customGroupCount 个",
                    icon = Icons.Outlined.Folder,
                    showChevron = true,
                    onClick = onManageGroups,
                    modifier = Modifier.testTag("downloads_manage_groups"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadGroupManagerSheet(
    groups: List<DownloadGroupSummary>,
    onCreate: () -> Unit,
    onRename: (DownloadGroupSummary) -> Unit,
    onClear: (DownloadGroupSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "自定义分组",
        subtitle = "分组仅整理下载记录，不会移动或删除媒体文件",
        modifier = Modifier.testTag("downloads_group_manager"),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onCreate,
                shape = RavenShapes.PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("downloads_create_group"),
            ) {
                Text("新建分组并选择下载项", fontWeight = FontWeight.SemiBold)
            }
            if (groups.isEmpty()) {
                Text(
                    text = "还没有自定义分组。新建后可将多个下载任务一次归入同一组。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(groups, key = DownloadGroupSummary::name) { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RavenShapes.CardShape)
                                .background(colors.surface1)
                                .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${group.itemCount} 项 · 已完成 ${group.completedCount} · ${RavenFormatters.formatByteSize(group.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = colors.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { onRename(group) },
                                modifier = Modifier.testTag("rename_group_${group.name}"),
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = "重命名${group.name}", tint = colors.inkSecondary)
                            }
                            IconButton(
                                onClick = { onClear(group) },
                                modifier = Modifier.testTag("clear_group_${group.name}"),
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除分组${group.name}", tint = colors.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadGroupRenameSheet(
    group: DownloadGroupSummary,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    var name by rememberSaveable(group.name) { mutableStateOf(group.name) }
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "重命名分组",
        subtitle = "若输入已有名称，两个分组的下载项会合并",
        modifier = Modifier.testTag("downloads_rename_group_sheet"),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LibraryTextInputField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = "分组名称",
                placeholder = "输入分组名称",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("取消", color = colors.inkPrimary)
                }
                Button(
                    onClick = {
                        onSave(name)
                        onDismiss()
                    },
                    enabled = name.trim().isNotEmpty() && name.trim() != group.name,
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("confirm_rename_group"),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadGroupCreateSheet(
    items: List<DownloadItem>,
    siteLabels: Map<com.raven.app.core.model.SiteId, String>,
    onSave: (String, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    var name by rememberSaveable { mutableStateOf("") }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "新建下载分组",
        subtitle = "填写名称，并选择至少一个要归入的下载项",
        modifier = Modifier.testTag("downloads_create_group_sheet"),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LibraryTextInputField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = "分组名称",
                placeholder = "例如：长途离线、周末合集",
            )
            Text(
                text = "选择下载项 · 已选 ${selectedIds.size}",
                style = MaterialTheme.typography.labelLarge,
                color = colors.inkPrimary,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RavenShapes.CardShape)
                    .background(colors.surface1)
                    .border(0.5.dp, colors.hairline, RavenShapes.CardShape),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    val selected = item.id in selectedIds
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = selected,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                                },
                            )
                            .testTag("create_group_item_${item.id}"),
                    ) {
                        RavenGroupedRow(
                            title = item.title,
                            subtitle = listOf(
                                siteLabels[item.siteId] ?: item.siteId.value,
                                item.groupName.ifBlank { "未分组" },
                            ).joinToString(" · "),
                            icon = if (selected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            iconTint = if (selected) colors.crimson else colors.inkSecondary,
                        )
                    }
                    if (index < items.lastIndex) RavenGroupedDivider(insetWithIcon = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("取消", color = colors.inkPrimary)
                }
                Button(
                    onClick = {
                        onSave(name, selectedIds)
                        onDismiss()
                    },
                    enabled = name.trim().isNotEmpty() && selectedIds.isNotEmpty(),
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("confirm_create_group"),
                ) {
                    Text("创建并归类")
                }
            }
        }
    }
}
