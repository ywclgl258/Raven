package com.raven.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenPrimaryButton
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun FeedbackScreen(
    navigator: SettingsNavigator,
    modifier: Modifier = Modifier,
    viewModel: FeedbackViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }
    FeedbackScreenContent(
        uiState = uiState,
        onBack = navigator::navigateBack,
        onSelectCategory = viewModel::selectCategory,
        onTitleChange = viewModel::updateTitle,
        onBodyChange = viewModel::updateBody,
        onContactChange = viewModel::updateContact,
        onIncludeLogChange = viewModel::setIncludeLog,
        onVerboseChange = viewModel::setVerboseLogging,
        onSubmit = viewModel::submit,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreenContent(
    uiState: FeedbackUiState,
    onBack: () -> Unit,
    onSelectCategory: (FeedbackCategory) -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onContactChange: (String) -> Unit,
    onIncludeLogChange: (Boolean) -> Unit,
    onVerboseChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Column(modifier = modifier.fillMaxSize().testTag("feedback_screen")) {
        RavenTopBar(
            title = "反馈问题",
            subtitle = "发送到官网后台，可附带诊断日志",
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("feedback_back_button")) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = colors.inkPrimary)
                }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().testTag("feedback_list"),
        ) {
            item {
                when {
                    uiState.acceptedId != null ->
                        RavenStatusPill(text = "已收到 ${uiState.acceptedId}", tone = RavenStatusTone.SUCCESS)
                    !uiState.errorMessage.isNullOrBlank() ->
                        RavenStatusPill(text = uiState.errorMessage, tone = RavenStatusTone.ERROR)
                }
            }
            item {
                RavenGroupedSection(
                    title = "问题类型",
                    footer = "不会上传账号、Cookie 或备份。日志中的口令、Cookie 与令牌会在设备上先打码。",
                ) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FeedbackCategory.entries.forEach { category ->
                            RavenFilterChip(
                                label = category.label,
                                selected = uiState.category == category,
                                onClick = { onSelectCategory(category) },
                            )
                        }
                    }
                }
            }
            item {
                RavenGroupedSection(title = "描述") {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsTextField(
                            value = uiState.title,
                            onValueChange = onTitleChange,
                            label = "标题",
                            placeholder = "一句话说明发生了什么",
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.testTag("feedback_title"),
                        )
                        SettingsTextField(
                            value = uiState.body,
                            onValueChange = onBodyChange,
                            label = "复现步骤或建议",
                            placeholder = "站点、操作、预期结果和实际结果",
                            singleLine = false,
                            minLines = 5,
                            maxLines = 10,
                            modifier = Modifier.testTag("feedback_body"),
                        )
                        SettingsTextField(
                            value = uiState.contact,
                            onValueChange = onContactChange,
                            label = "联系方式（可选）",
                            placeholder = "邮箱或其他方便回复的方式",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.testTag("feedback_contact"),
                        )
                    }
                }
            }
            item {
                RavenGroupedSection(
                    title = "诊断日志",
                    footer = "日志只记录本应用的事件、错误和崩溃栈，不含浏览内容与账号凭据。",
                ) {
                    RavenGroupedSwitchRow(
                        title = "附带诊断日志",
                        subtitle = if (uiState.logBytes > 0L) {
                            "约 ${RavenFormatters.formatByteSize(uiState.logBytes)}" +
                                if (uiState.hasCrashRecord) "，含上次崩溃记录" else ""
                        } else {
                            "当前还没有可上传的日志"
                        },
                        icon = Icons.Outlined.BugReport,
                        checked = uiState.includeLog,
                        onCheckedChange = onIncludeLogChange,
                        modifier = Modifier.testTag("feedback_include_log"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "记录详细日志",
                        subtitle = "仅本次运行有效：记录更多调试事件，便于复现播放、下载与网络问题",
                        checked = uiState.verboseLogging,
                        onCheckedChange = onVerboseChange,
                        modifier = Modifier.testTag("feedback_verbose_log"),
                    )
                    val lastError = uiState.lastErrorSummary
                    if (!lastError.isNullOrBlank()) {
                        RavenGroupedDivider()
                        RavenGroupedRow(
                            title = "最近一次错误",
                            subtitle = lastError,
                            subtitleMaxLines = 3,
                            modifier = Modifier.testTag("feedback_last_error"),
                        )
                    }
                }
            }
            item {
                RavenPrimaryButton(
                    text = if (uiState.isSubmitting) "正在发送" else "发送反馈",
                    onClick = onSubmit,
                    enabled = !uiState.isSubmitting,
                    loading = uiState.isSubmitting,
                    leadingIcon = Icons.Outlined.UploadFile,
                    modifier = Modifier.fillMaxWidth().testTag("feedback_submit"),
                )
            }
            item {
                Text(
                    text = "也可以在官网帮助页提交，两条入口共用同一套后台。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
