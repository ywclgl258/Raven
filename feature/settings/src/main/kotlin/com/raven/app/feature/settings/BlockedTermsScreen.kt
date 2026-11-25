package com.raven.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.common.TagSuggestion
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
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.model.RavenRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun BlockedTermsScreen(
    route: RavenRoute.BlockedTerms,
    navigator: SettingsNavigator,
    modifier: Modifier = Modifier,
    viewModel: BlockedTermsViewModel = koinViewModel(
        key = "blocked_terms_${route.siteId}",
        parameters = { parametersOf(route) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BlockedTermsScreenContent(
        uiState = uiState,
        navigator = navigator,
        onSelectScope = viewModel::selectScope,
        onToggleBlockTagsGlobally = viewModel::setBlockTagsGlobally,
        onQueryChange = viewModel::updateQuery,
        onAddCurrentInput = viewModel::addCurrentInput,
        onSelectSuggestion = viewModel::addSuggestion,
        onRemoveTerm = viewModel::removeTerm,
        onClearAll = viewModel::clearAll,
        onDismissFeedback = viewModel::clearFeedback,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedTermsScreenContent(
    uiState: BlockedTermsUiState,
    navigator: SettingsNavigator,
    onSelectScope: (BlockedTermsScope) -> Unit,
    onToggleBlockTagsGlobally: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onAddCurrentInput: () -> Unit,
    onSelectSuggestion: (TagSuggestion) -> Unit,
    onRemoveTerm: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    var showClearConfirmSheet by rememberSaveable { mutableStateOf(false) }

    val isGlobalScope = uiState.selectedScope == BlockedTermsScope.GLOBAL
    val siteShortName = uiState.site?.shortName ?: uiState.siteId.value
    val siteDisplayName = uiState.site?.displayName ?: uiState.siteId.value
    val scopeTargetLabel = if (isGlobalScope) "全部站点" else siteDisplayName

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("blocked_terms_screen"),
    ) {
        RavenTopBar(
            title = "屏蔽词管理",
            subtitle = if (isGlobalScope) "全局屏蔽 · 全站生效" else "$siteDisplayName · 单站规则",
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("blocked_terms_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
            actions = {
                if (uiState.terms.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearConfirmSheet = true },
                        modifier = Modifier.testTag("blocked_terms_clear_all_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "清空全部屏蔽词",
                            tint = colors.error,
                        )
                    }
                }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (!uiState.siteId.isGlobal) {
                item(key = "blocked_terms_scope_toggle") {
                    BlockedTermsScopeSegmentedControl(
                        selected = uiState.selectedScope,
                        globalCount = uiState.globalTermsCount,
                        siteLabel = siteShortName,
                        siteCount = uiState.siteTermsCount,
                        onSelect = onSelectScope,
                    )
                }
            }

            item(key = "blocked_terms_global_sync_section") {
                RavenGroupedSection(
                    footer = "开启全局同步屏蔽后，在播放器或图集详情页长按任意标签屏蔽时将写入「全局屏蔽」，并自动注入搜索服务端排除参数。",
                ) {
                    RavenGroupedSwitchRow(
                        title = "全局同步屏蔽",
                        subtitle = "长按标签屏蔽默认同步生效于全部站点",
                        icon = Icons.Outlined.Public,
                        checked = uiState.blockTagsGlobally,
                        onCheckedChange = onToggleBlockTagsGlobally,
                        modifier = Modifier.testTag("blocked_terms_global_sync_switch"),
                    )
                }
            }

            item(key = "blocked_terms_input") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RavenSearchField(
                        query = uiState.inputQuery,
                        onQueryChange = onQueryChange,
                        placeholder = "输入标签或关键词（支持中英文联想）",
                        onSearch = { onAddCurrentInput() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("blocked_terms_input_field"),
                    )
                    SettingsPrimaryButton(
                        text = "添加",
                        icon = Icons.Outlined.Add,
                        enabled = uiState.inputQuery.isNotBlank(),
                        onClick = onAddCurrentInput,
                        modifier = Modifier.testTag("blocked_terms_add_button"),
                    )
                }
            }

            val feedback = uiState.feedbackMessage
            if (!feedback.isNullOrBlank()) {
                item(key = "blocked_terms_feedback") {
                    SettingsNoticeBanner(
                        message = feedback,
                        tone = RavenStatusTone.ACCENT,
                        action = {
                            TextButton(onClick = onDismissFeedback) {
                                Text("知道了", color = colors.inkPrimary)
                            }
                        },
                    )
                }
            }

            if (uiState.suggestions.isNotEmpty()) {
                item(key = "blocked_terms_suggestions") {
                    RavenGroupedSection(
                        title = "词典联想建议",
                        footer = if (isGlobalScope) {
                            "点击条目即可直接加入全局屏蔽列表，搜索与推荐将同步中英文过滤。"
                        } else {
                            "点击条目即可直接加入 $siteShortName 屏蔽列表。"
                        },
                    ) {
                        uiState.suggestions.forEachIndexed { idx, suggestion ->
                            if (idx > 0) RavenGroupedDivider(insetWithIcon = true)
                            val slugSubtitle = if (suggestion.namespace.isNotBlank()) {
                                "${suggestion.namespace}:${suggestion.slug}"
                            } else {
                                suggestion.slug
                            }
                            RavenGroupedRow(
                                title = suggestion.localizedName,
                                subtitle = slugSubtitle,
                                icon = Icons.Outlined.Tag,
                                iconTint = colors.crimson,
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = "添加屏蔽词 ${suggestion.slug}",
                                        tint = colors.inkSecondary,
                                    )
                                },
                                onClick = { onSelectSuggestion(suggestion) },
                                modifier = Modifier.testTag("blocked_terms_suggestion_${suggestion.slug}"),
                            )
                        }
                    }
                }
            }

            if (uiState.terms.isEmpty()) {
                item(key = "blocked_terms_empty") {
                    RavenEmptyState(
                        title = if (isGlobalScope) "暂无全局屏蔽词" else "暂无本站屏蔽词",
                        message = "添加标签或关键词后，$scopeTargetLabel 的首页、发现、相关推荐与搜索结果将实时隐藏匹配作品（自动支持中英文等价与服务端排除）。",
                        icon = Icons.Outlined.Block,
                    )
                }
            } else {
                item(key = "blocked_terms_list") {
                    RavenGroupedSection(
                        title = if (isGlobalScope) {
                            "全局屏蔽规则（${uiState.terms.size}）"
                        } else {
                            "$siteShortName 屏蔽规则（${uiState.terms.size}）"
                        },
                        footer = if (isGlobalScope) {
                            "全局屏蔽规则会与各站点规则自动合并，并在支持排除语法的站点搜索时注入服务端过滤。"
                        } else {
                            "单站规则仅影响 $siteDisplayName，并与全局屏蔽规则叠加生效，可随加密备份迁移。"
                        },
                    ) {
                        uiState.terms.forEachIndexed { idx, item ->
                            if (idx > 0) RavenGroupedDivider(insetWithIcon = true)
                            RavenGroupedRow(
                                title = item.term,
                                subtitle = item.localizedHint?.let { "中文含义：$it" },
                                icon = Icons.Outlined.Block,
                                trailingContent = {
                                    IconButton(
                                        onClick = { onRemoveTerm(item.term) },
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .testTag("blocked_terms_remove_${item.term}"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "移除 ${item.term}",
                                            tint = colors.error,
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("blocked_terms_item_${item.term}"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmSheet) {
        com.raven.app.core.designsystem.component.RavenConfirmDialog(
            onDismissRequest = { showClearConfirmSheet = false },
            title = if (isGlobalScope) "清空全部全局屏蔽词？" else "清空本站全部屏蔽词？",
            message = if (isGlobalScope) {
                "将移除全部 ${uiState.terms.size} 条全局屏蔽规则，各站点独立屏蔽词不受影响。"
            } else {
                "将移除 $siteDisplayName 的全部 ${uiState.terms.size} 条屏蔽规则，全局屏蔽词与其他站点不受影响。"
            },
            confirmLabel = "确认清空",
            destructive = true,
            onConfirm = {
                showClearConfirmSheet = false
                onClearAll()
            },
            modifier = Modifier.testTag("blocked_terms_clear_sheet"),
        )
    }
}

@Composable
private fun BlockedTermsScopeSegmentedControl(
    selected: BlockedTermsScope,
    globalCount: Int,
    siteLabel: String,
    siteCount: Int,
    onSelect: (BlockedTermsScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .clip(RavenShapes.ControlShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScopeSegmentButton(
            label = "全局屏蔽 ($globalCount)",
            selected = selected == BlockedTermsScope.GLOBAL,
            onClick = { onSelect(BlockedTermsScope.GLOBAL) },
            modifier = Modifier
                .weight(1f)
                .testTag("blocked_terms_scope_global"),
        )
        ScopeSegmentButton(
            label = "当前站点 · $siteLabel ($siteCount)",
            selected = selected == BlockedTermsScope.CURRENT_SITE,
            onClick = { onSelect(BlockedTermsScope.CURRENT_SITE) },
            modifier = Modifier
                .weight(1f)
                .testTag("blocked_terms_scope_site"),
        )
    }
}

@Composable
private fun ScopeSegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val bg = if (selected) colors.surface3 else colors.surface1
    val borderColor = if (selected) colors.outlineSubtle else colors.surface1
    val textColor = if (selected) colors.inkPrimary else colors.inkSecondary

    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RavenShapes.ControlShape)
            .background(bg)
            .border(0.5.dp, borderColor, RavenShapes.ControlShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
