package com.raven.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.model.TagTranslationEntry
import org.koin.androidx.compose.koinViewModel

@Composable
fun TagDictionaryScreen(
    navigator: SettingsNavigator,
    modifier: Modifier = Modifier,
    viewModel: TagDictionaryViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TagDictionaryScreenContent(
        uiState = uiState,
        navigator = navigator,
        onToggleAutoSync = viewModel::setAutoSync,
        onSyncNow = viewModel::syncCommunityNow,
        onClearCommunityCache = viewModel::clearCommunityCache,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onSaveCustomTranslation = viewModel::saveCustomTranslation,
        onDeleteCustomTranslation = viewModel::deleteCustomTranslation,
        onDismissFeedback = viewModel::clearFeedback,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagDictionaryScreenContent(
    uiState: TagDictionaryUiState,
    navigator: SettingsNavigator,
    onToggleAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onClearCommunityCache: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSaveCustomTranslation: (
        rawSlug: String,
        chineseName: String,
        namespace: String,
        originalEntry: TagTranslationEntry?,
    ) -> Unit,
    onDeleteCustomTranslation: (TagTranslationEntry) -> Unit,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    var showClearCommunitySheet by rememberSaveable { mutableStateOf(false) }
    var showEditorSheet by rememberSaveable { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<TagTranslationEntry?>(null) }

    val stats = uiState.stats
    val summarySubtitle = "内置 ${stats.builtInCount} · 社区 ${stats.communityCount} · 自定义 ${stats.customCount}"
    val syncMetaSubtitle = buildString {
        append("版本：")
        append(uiState.releaseTag.ifBlank { "尚未同步" })
        append(" · 上次同步：")
        append(
            if (uiState.lastUpdatedAt > 0L) {
                SettingsFormatters.formatTimestamp(uiState.lastUpdatedAt)
            } else {
                "从未同步"
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("tag_dictionary_screen"),
    ) {
        RavenTopBar(
            title = "标签中文词典",
            subtitle = summarySubtitle,
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("tag_dictionary_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("tag_dictionary_list"),
        ) {
            val feedback = uiState.feedbackMessage
            if (!feedback.isNullOrBlank()) {
                item(key = "tag_dict_feedback") {
                    SettingsNoticeBanner(
                        message = feedback,
                        tone = if (uiState.syncStatusIsError) RavenStatusTone.ERROR else RavenStatusTone.ACCENT,
                        action = {
                            TextButton(onClick = onDismissFeedback) {
                                Text("知道了", color = colors.inkPrimary)
                            }
                        },
                    )
                }
            }

            // 1. 社区词库自动补充 (EhTagTranslation)
            item(key = "tag_dict_community_section") {
                RavenGroupedSection(
                    title = "社区词库自动补充 (EhTagTranslation)",
                    footer = "通过无凭据 GitHub Releases 增量同步 40,000+ 社区标签译名，遵循系统代理并优先于内置词典生效；用户自定义译名拥有最高优先级。",
                ) {
                    RavenGroupedRow(
                        title = "社区词条状态",
                        subtitle = syncMetaSubtitle,
                        value = "${stats.communityCount} 条",
                        icon = Icons.Outlined.CloudDownload,
                        iconTint = if (stats.communityCount > 0) colors.success else colors.inkPrimary,
                        modifier = Modifier.testTag("tag_dictionary_community_status_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "每周自动更新",
                        subtitle = "应用启动时按 7 天周期在后台静默检查并增量更新词库",
                        icon = Icons.Outlined.Sync,
                        checked = uiState.autoSyncEnabled,
                        onCheckedChange = onToggleAutoSync,
                        modifier = Modifier.testTag("tag_dictionary_auto_sync_switch"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SettingsPrimaryButton(
                            text = if (uiState.isSyncing) "正在同步社区词库…" else "立即同步社区词库 (40,000+)",
                            icon = Icons.Outlined.CloudDownload,
                            loading = uiState.isSyncing,
                            onClick = onSyncNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tag_dictionary_sync_now_button"),
                        )
                        val statusMsg = uiState.syncStatusMessage
                        if (!statusMsg.isNullOrBlank()) {
                            Text(
                                text = statusMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.syncStatusIsError) colors.error else colors.inkSecondary,
                                modifier = Modifier.testTag("tag_dictionary_sync_status_text"),
                            )
                        }
                    }
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "清空社区缓存",
                        subtitle = "移除本地缓存的 EhTagTranslation 词条（不影响内置与自定义译名）",
                        icon = Icons.Outlined.DeleteSweep,
                        destructive = true,
                        enabled = stats.communityCount > 0 && !uiState.isSyncing,
                        onClick = { showClearCommunitySheet = true },
                        modifier = Modifier.testTag("tag_dictionary_clear_community_row"),
                    )
                }
            }

            // 2. 自定义中文译名
            item(key = "tag_dict_custom_controls") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RavenSearchField(
                            query = uiState.searchQuery,
                            onQueryChange = onSearchQueryChange,
                            placeholder = "筛选自定义英文标签或中文译名",
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tag_dictionary_search_field"),
                        )
                        SettingsPrimaryButton(
                            text = "新增",
                            icon = Icons.Outlined.Add,
                            onClick = {
                                editingEntry = null
                                showEditorSheet = true
                            },
                            modifier = Modifier.testTag("tag_dictionary_add_custom_button"),
                        )
                    }
                }
            }

            if (uiState.customEntries.isEmpty()) {
                item(key = "tag_dict_custom_empty") {
                    RavenEmptyState(
                        title = if (uiState.searchQuery.isBlank()) "暂无自定义中文译名" else "未找到匹配的自定义译名",
                        message = if (uiState.searchQuery.isBlank()) {
                            "点击「新增」或在作品详情页长按任意标签选择「编辑中文译名」，自定义译名将立即生效并可随加密备份迁移。"
                        } else {
                            "没有包含「${uiState.searchQuery}」的自定义译名条目。"
                        },
                        icon = Icons.Outlined.Translate,
                        actionLabel = if (uiState.searchQuery.isBlank()) "新增自定义译名" else null,
                        onActionClick = if (uiState.searchQuery.isBlank()) {
                            {
                                editingEntry = null
                                showEditorSheet = true
                            }
                        } else {
                            null
                        },
                    )
                }
            } else {
                item(key = "tag_dict_custom_list") {
                    RavenGroupedSection(
                        title = "自定义中文译名（${uiState.customEntries.size} / ${uiState.totalCustomCount}）",
                        footer = "点击条目即可编辑或删除；自定义译名自动覆盖社区词库与内置词典。",
                    ) {
                        uiState.customEntries.forEachIndexed { idx, entry ->
                            if (idx > 0) RavenGroupedDivider(insetWithIcon = true)
                            RavenGroupedRow(
                                title = entry.chineseName,
                                subtitle = entry.qualifiedSlug,
                                icon = Icons.Outlined.Translate,
                                iconTint = colors.crimson,
                                onClick = {
                                    editingEntry = entry
                                    showEditorSheet = true
                                },
                                trailingContent = {
                                    IconButton(
                                        onClick = { onDeleteCustomTranslation(entry) },
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .testTag("tag_dictionary_delete_${entry.qualifiedSlug}"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "删除 ${entry.qualifiedSlug}",
                                            tint = colors.error,
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("tag_dictionary_custom_row_${entry.qualifiedSlug}"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearCommunitySheet) {
        com.raven.app.core.designsystem.component.RavenConfirmDialog(
            onDismissRequest = { showClearCommunitySheet = false },
            title = "清空社区词库缓存？",
            message = "将移除本地缓存的 ${stats.communityCount} 条 EhTagTranslation 译名，可随时重新同步。",
            confirmLabel = "确认清空",
            destructive = true,
            onConfirm = {
                showClearCommunitySheet = false
                onClearCommunityCache()
            },
            modifier = Modifier.testTag("tag_dictionary_clear_sheet"),
        )
    }

    if (showEditorSheet) {
        CustomTagTranslationEditorSheet(
            editing = editingEntry,
            onDismiss = {
                showEditorSheet = false
                editingEntry = null
            },
            onSave = { rawSlug, namespace, chineseName ->
                onSaveCustomTranslation(rawSlug, chineseName, namespace, editingEntry)
                showEditorSheet = false
                editingEntry = null
            },
            onDelete = editingEntry?.let { entry ->
                {
                    onDeleteCustomTranslation(entry)
                    showEditorSheet = false
                    editingEntry = null
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomTagTranslationEditorSheet(
    editing: TagTranslationEntry?,
    onDismiss: () -> Unit,
    onSave: (rawSlug: String, namespace: String, chineseName: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var rawSlug by remember(editing) { mutableStateOf(editing?.rawSlug.orEmpty()) }
    var namespace by remember(editing) { mutableStateOf(editing?.namespace.orEmpty()) }
    var chineseName by remember(editing) { mutableStateOf(editing?.chineseName.orEmpty()) }

    val canSave = rawSlug.trim().isNotEmpty() && chineseName.trim().isNotEmpty()

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = if (editing == null) "新增自定义译名" else "编辑自定义译名",
        subtitle = "自定义译名拥有最高优先级，保存后立即在全局标签与搜索联想生效",
        modifier = Modifier.testTag("tag_dictionary_editor_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsTextField(
                value = rawSlug,
                onValueChange = { rawSlug = it },
                label = "英文标签（必填）",
                placeholder = "例如：stockings 或 big breasts",
                supportingText = "支持空格、下划线或连字符，自动归一化匹配",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.testTag("tag_dictionary_input_slug"),
            )
            SettingsTextField(
                value = namespace,
                onValueChange = { namespace = it },
                label = "命名空间（可选）",
                placeholder = "例如：female、male、parody、artist",
                supportingText = "留空则对所有命名空间的同名标签生效",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.testTag("tag_dictionary_input_namespace"),
            )
            SettingsTextField(
                value = chineseName,
                onValueChange = { chineseName = it },
                label = "中文译名（必填）",
                placeholder = "例如：丝袜",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canSave) onSave(rawSlug, namespace, chineseName)
                    },
                ),
                modifier = Modifier.testTag("tag_dictionary_input_chinese"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onDelete != null) {
                    SettingsSecondaryButton(
                        text = "删除",
                        destructive = true,
                        onClick = onDelete,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tag_dictionary_editor_delete_button"),
                    )
                } else {
                    SettingsSecondaryButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
                SettingsPrimaryButton(
                    text = "保存译名",
                    enabled = canSave,
                    onClick = { onSave(rawSlug, namespace, chineseName) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tag_dictionary_editor_save_button"),
                )
            }
        }
    }
}
