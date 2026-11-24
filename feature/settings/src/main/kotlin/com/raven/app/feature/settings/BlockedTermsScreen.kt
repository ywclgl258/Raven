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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.common.TagSuggestion
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("blocked_terms_screen"),
    ) {
        RavenTopBar(
            title = "站点屏蔽词",
            subtitle = uiState.site?.displayName ?: uiState.siteId.value,
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
                        footer = "点击条目即可直接加入 ${uiState.site?.shortName ?: uiState.siteId.value} 屏蔽列表。",
                    ) {
                        uiState.suggestions.forEachIndexed { idx, suggestion ->
                            if (idx > 0) RavenGroupedDivider(insetWithIcon = true)
                            RavenGroupedRow(
                                title = suggestion.localizedName,
                                subtitle = suggestion.slug,
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
                        title = "暂无屏蔽词",
                        message = "添加标签或关键词后，${uiState.site?.displayName ?: uiState.siteId.value} 的首页、发现与搜索结果将实时隐藏匹配作品。",
                        icon = Icons.Outlined.Block,
                    )
                }
            } else {
                item(key = "blocked_terms_list") {
                    RavenGroupedSection(
                        title = "已屏蔽规则（${uiState.terms.size}）",
                        footer = "屏蔽规则按站点独立隔离，并可随加密备份迁移。",
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
        RavenBottomSheet(
            onDismissRequest = { showClearConfirmSheet = false },
            title = "清空本站全部屏蔽词？",
            subtitle = "将移除 ${uiState.site?.displayName ?: uiState.siteId.value} 的全部 ${uiState.terms.size} 条屏蔽规则，此操作不会影响其他站点。",
            modifier = Modifier.testTag("blocked_terms_clear_sheet"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSecondaryButton(
                    text = "取消",
                    onClick = { showClearConfirmSheet = false },
                    modifier = Modifier.weight(1f),
                )
                SettingsPrimaryButton(
                    text = "确认清空",
                    destructive = true,
                    onClick = {
                        showClearConfirmSheet = false
                        onClearAll()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("blocked_terms_clear_confirm_button"),
                )
            }
        }
    }
}
