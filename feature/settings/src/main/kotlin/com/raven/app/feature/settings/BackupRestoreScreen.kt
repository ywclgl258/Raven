package com.raven.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.security.BackupCodec
import com.raven.app.core.security.BackupFormat
import org.koin.androidx.compose.koinViewModel

@Composable
fun BackupRestoreScreen(
    navigator: SettingsNavigator,
    modifier: Modifier = Modifier,
    viewModel: BackupRestoreViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackupRestoreScreenContent(
        uiState = uiState,
        navigator = navigator,
        onSelectTab = viewModel::selectTab,
        onExportPasswordChange = viewModel::updateExportPassword,
        onExportPasswordConfirmChange = viewModel::updateExportPasswordConfirm,
        onStartExportPicker = {
            navigator.launchCreateBackupDocument(
                suggestedFileName = SettingsExportDefaults.defaultBackupFileName(),
                onDocumentCreated = viewModel::exportToUri,
            )
        },
        onStartImportPicker = {
            navigator.launchOpenBackupDocument(onDocumentOpened = viewModel::selectImportUri)
        },
        onImportPasswordChange = viewModel::updateImportPassword,
        onDecryptImport = viewModel::decryptAndPreviewImport,
        onConfirmRestore = viewModel::confirmAndRestoreImport,
        onCancelImport = viewModel::cancelStagedImport,
        onWebDavEndpointChange = viewModel::updateWebDavEndpointUrl,
        onWebDavUsernameChange = viewModel::updateWebDavUsername,
        onWebDavPasswordChange = viewModel::updateWebDavPassword,
        onWebDavPassphraseChange = viewModel::updateWebDavPassphrase,
        onWebDavFileNameChange = viewModel::updateWebDavRemoteFileName,
        onSaveWebDavConfig = viewModel::saveWebDavConfig,
        onClearWebDavConfig = viewModel::clearWebDavConfig,
        onTestWebDavConnection = viewModel::testWebDavConnection,
        onPushWebDavBackup = viewModel::pushWebDavBackup,
        onPullWebDavBackup = viewModel::pullAndRestoreWebDavBackup,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupRestoreScreenContent(
    uiState: BackupRestoreUiState,
    navigator: SettingsNavigator,
    onSelectTab: (BackupRestoreTab) -> Unit,
    onExportPasswordChange: (String) -> Unit,
    onExportPasswordConfirmChange: (String) -> Unit,
    onStartExportPicker: () -> Unit,
    onStartImportPicker: () -> Unit,
    onImportPasswordChange: (String) -> Unit,
    onDecryptImport: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelImport: () -> Unit,
    onWebDavEndpointChange: (String) -> Unit = {},
    onWebDavUsernameChange: (String) -> Unit = {},
    onWebDavPasswordChange: (String) -> Unit = {},
    onWebDavPassphraseChange: (String) -> Unit = {},
    onWebDavFileNameChange: (String) -> Unit = {},
    onSaveWebDavConfig: () -> Unit = {},
    onClearWebDavConfig: () -> Unit = {},
    onTestWebDavConnection: () -> Unit = {},
    onPushWebDavBackup: () -> Unit = {},
    onPullWebDavBackup: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    var exportPwdVisible by rememberSaveable { mutableStateOf(false) }
    var importPwdVisible by rememberSaveable { mutableStateOf(false) }
    var webDavPwdVisible by rememberSaveable { mutableStateOf(false) }
    var webDavPassphraseVisible by rememberSaveable { mutableStateOf(false) }

    // Protect backup passwords against screen capture.
    SecureWindowEffect(enabled = true)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("backup_restore_screen"),
    ) {
        RavenTopBar(
            title = "加密备份与恢复",
            subtitle = "RVNBK01 原生 · 兼容 R34VBK01 导入",
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("backup_restore_back_button"),
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
                .fillMaxWidth(),
        ) {
            item(key = "backup_mode_tabs") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RavenFilterChip(
                        label = "导出加密备份",
                        selected = uiState.activeTab == BackupRestoreTab.EXPORT,
                        leadingIcon = Icons.Outlined.FileUpload,
                        onClick = { onSelectTab(BackupRestoreTab.EXPORT) },
                        modifier = Modifier.testTag("backup_tab_export"),
                    )
                    RavenFilterChip(
                        label = "导入与恢复",
                        selected = uiState.activeTab == BackupRestoreTab.IMPORT,
                        leadingIcon = Icons.Outlined.Restore,
                        onClick = { onSelectTab(BackupRestoreTab.IMPORT) },
                        modifier = Modifier.testTag("backup_tab_import"),
                    )
                    RavenFilterChip(
                        label = "WebDAV 云同步",
                        selected = uiState.activeTab == BackupRestoreTab.WEBDAV,
                        leadingIcon = Icons.Outlined.CloudSync,
                        onClick = { onSelectTab(BackupRestoreTab.WEBDAV) },
                        modifier = Modifier.testTag("backup_tab_webdav"),
                    )
                }
            }

            item(key = "backup_crypto_notice") {
                SettingsNoticeBanner(
                    title = "端到端离线加密保障",
                    message = "采用 PBKDF2-HMAC-SHA1 (600,000 次迭代) + AES-256-GCM 认证加密。导出内容仅含收藏、历史、片单、书签、订阅、屏蔽词及便携偏好，绝不包含登录 Cookie、Bearer 令牌、密钥或媒体文件。",
                    icon = Icons.Outlined.Shield,
                    tone = RavenStatusTone.NEUTRAL,
                )
            }

            when (uiState.activeTab) {
                BackupRestoreTab.EXPORT -> {
                    item(key = "export_form_section") {
                        RavenGroupedSection(
                            title = "设置备份加密密码",
                            footer = "请妥善保管备份密码；密码不会存储在设备中，遗忘后将无法解密备份文件。",
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SettingsTextField(
                                    value = uiState.exportPassword,
                                    onValueChange = onExportPasswordChange,
                                    label = "备份密码（至少 ${BackupCodec.MIN_PASSWORD_LENGTH} 位）",
                                    placeholder = "输入强密码",
                                    isPassword = true,
                                    passwordVisible = exportPwdVisible,
                                    onTogglePasswordVisibility = { exportPwdVisible = !exportPwdVisible },
                                    enabled = !uiState.isExporting,
                                    isError = uiState.exportPassword.isNotEmpty() &&
                                        uiState.exportPassword.length < BackupCodec.MIN_PASSWORD_LENGTH,
                                    supportingText = if (uiState.exportPassword.isNotEmpty() &&
                                        uiState.exportPassword.length < BackupCodec.MIN_PASSWORD_LENGTH
                                    ) {
                                        "密码长度至少需要 ${BackupCodec.MIN_PASSWORD_LENGTH} 个字符"
                                    } else {
                                        "输出格式：Raven 原生加密包 (RVNBK01)"
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Next,
                                    ),
                                    modifier = Modifier.testTag("backup_export_password_field"),
                                )

                                SettingsTextField(
                                    value = uiState.exportPasswordConfirm,
                                    onValueChange = onExportPasswordConfirmChange,
                                    label = "确认备份密码",
                                    placeholder = "再次输入相同密码",
                                    isPassword = true,
                                    passwordVisible = exportPwdVisible,
                                    onTogglePasswordVisibility = { exportPwdVisible = !exportPwdVisible },
                                    enabled = !uiState.isExporting,
                                    isError = uiState.exportPasswordConfirm.isNotEmpty() &&
                                        uiState.exportPassword != uiState.exportPasswordConfirm,
                                    supportingText = uiState.exportPasswordValidationError,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { if (uiState.canStartExport) onStartExportPicker() },
                                    ),
                                    modifier = Modifier.testTag("backup_export_confirm_field"),
                                )

                                SettingsPrimaryButton(
                                    text = if (uiState.isExporting) "正在加密并导出…" else "选择保存位置并导出",
                                    icon = Icons.Outlined.EnhancedEncryption,
                                    loading = uiState.isExporting,
                                    enabled = uiState.canStartExport,
                                    onClick = onStartExportPicker,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("backup_export_submit_button"),
                                )
                            }
                        }
                    }

                    val exportErr = uiState.exportError
                    if (!exportErr.isNullOrBlank()) {
                        item(key = "export_error_banner") {
                            SettingsNoticeBanner(
                                title = "导出失败",
                                message = exportErr,
                                tone = RavenStatusTone.ERROR,
                            )
                        }
                    }

                    val exported = uiState.exportCompleted
                    if (exported != null) {
                        item(key = "export_completed_card") {
                            SettingsNoticeBanner(
                                title = "加密备份已导出（RVNBK01）",
                                message = "体积：${RavenFormatters.formatByteSize(exported.encryptedBytes.toLong())}\n${exported.summary.toDisplayText()}",
                                tone = RavenStatusTone.SUCCESS,
                                modifier = Modifier.testTag("backup_export_success_banner"),
                            )
                        }
                    }
                }

                BackupRestoreTab.IMPORT -> {
                    item(key = "import_pick_section") {
                        RavenGroupedSection(
                            title = "第一步：选择备份文件",
                            footer = "自动识别渡鸦原生 RVNBK01 与旧版 R34V 的 R34VBK01 加密备份文件。",
                        ) {
                            RavenGroupedRow(
                                title = if (uiState.hasSelectedImportFile) "重新选择备份文件" else "从设备存储选择备份文件",
                                subtitle = if (uiState.hasSelectedImportFile) {
                                    "已加载 ${RavenFormatters.formatByteSize(uiState.importFileSizeBytes.toLong())}"
                                } else {
                                    "通过系统文件选择器安全读取，恢复后自动释放 URI 授权"
                                },
                                icon = Icons.Outlined.FileOpen,
                                iconTint = colors.crimson,
                                showChevron = true,
                                enabled = !uiState.isDecrypting && !uiState.isRestoring,
                                trailingContent = uiState.detectedImportFormat?.let { fmt ->
                                    {
                                        RavenStatusPill(
                                            text = formatBadgeLabel(fmt),
                                            tone = if (fmt == BackupFormat.LEGACY_R34V_V1) {
                                                RavenStatusTone.WARNING
                                            } else {
                                                RavenStatusTone.ACCENT
                                            },
                                        )
                                    }
                                },
                                onClick = onStartImportPicker,
                                modifier = Modifier.testTag("backup_import_pick_button"),
                            )
                        }
                    }

                    if (uiState.hasSelectedImportFile && uiState.importPreview == null) {
                        item(key = "import_password_section") {
                            RavenGroupedSection(
                                title = "第二步：输入密码并解密校验",
                                footer = "解密后将先展示完整数据结构预览与合并说明，确认前不会修改本地数据库。",
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SettingsTextField(
                                        value = uiState.importPassword,
                                        onValueChange = onImportPasswordChange,
                                        label = "备份解密密码",
                                        placeholder = "输入导出此备份时设置的密码",
                                        isPassword = true,
                                        passwordVisible = importPwdVisible,
                                        onTogglePasswordVisibility = { importPwdVisible = !importPwdVisible },
                                        enabled = !uiState.isDecrypting,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done,
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = { if (uiState.canDecryptImport) onDecryptImport() },
                                        ),
                                        modifier = Modifier.testTag("backup_import_password_field"),
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        SettingsSecondaryButton(
                                            text = "取消",
                                            enabled = !uiState.isDecrypting,
                                            onClick = onCancelImport,
                                            modifier = Modifier.weight(1f),
                                        )
                                        SettingsPrimaryButton(
                                            text = if (uiState.isDecrypting) "正在解密校验…" else "解密并预览",
                                            icon = Icons.Outlined.LockOpen,
                                            loading = uiState.isDecrypting,
                                            enabled = uiState.canDecryptImport,
                                            onClick = onDecryptImport,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("backup_import_decrypt_button"),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val preview = uiState.importPreview
                    if (preview != null) {
                        item(key = "import_preview_card") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RavenShapes.CardShape)
                                    .background(colors.surface1)
                                    .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                                    .padding(16.dp)
                                    .testTag("backup_import_preview_card"),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "备份校验通过 · 待确认合并",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = colors.inkPrimary,
                                        )
                                        Text(
                                            text = "导出时间：${SettingsFormatters.formatTimestamp(preview.summary.exportedAt)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.inkSecondary,
                                        )
                                    }
                                    RavenStatusPill(
                                        text = formatBadgeLabel(preview.format),
                                        tone = if (preview.format == BackupFormat.LEGACY_R34V_V1) {
                                            RavenStatusTone.WARNING
                                        } else {
                                            RavenStatusTone.SUCCESS
                                        },
                                    )
                                }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    RavenStatusPill(text = "收藏 ${preview.summary.favoritesCount}")
                                    RavenStatusPill(text = "历史 ${preview.summary.totalHistoryCount}")
                                    RavenStatusPill(text = "片单 ${preview.summary.shelvesCount}（${preview.summary.shelfItemsCount} 项）")
                                    RavenStatusPill(text = "书签 ${preview.summary.totalBookmarksCount}")
                                    RavenStatusPill(text = "订阅 ${preview.summary.tagSubscriptionsCount}")
                                    if (preview.summary.imageFavoritesCount > 0) {
                                        RavenStatusPill(text = "单图收藏 ${preview.summary.imageFavoritesCount}")
                                    }
                                    if (preview.summary.blockedTermsCount > 0) {
                                        RavenStatusPill(text = "屏蔽词 ${preview.summary.blockedTermsCount}")
                                    }
                                }

                                RavenGroupedSection(title = "详细条目统计") {
                                    RavenGroupedRow(
                                        title = "收藏",
                                        value = "${preview.summary.favoritesCount} 项",
                                    )
                                    RavenGroupedDivider()
                                    RavenGroupedRow(
                                        title = "观看 / 阅读历史",
                                        subtitle = "观看 ${preview.summary.watchProgressCount} · 阅读 ${preview.summary.readingProgressCount}",
                                        value = "${preview.summary.totalHistoryCount} 项",
                                    )
                                    RavenGroupedDivider()
                                    RavenGroupedRow(
                                        title = "片单与智能片单",
                                        subtitle = "片单条目 ${preview.summary.shelfItemsCount} · 智能片单 ${preview.summary.smartShelvesCount}",
                                        value = "${preview.summary.shelvesCount} 个",
                                    )
                                    RavenGroupedDivider()
                                    RavenGroupedRow(
                                        title = "页码与时间书签",
                                        subtitle = "页码 ${preview.summary.pageBookmarksCount} · 时间 ${preview.summary.timeBookmarksCount}",
                                        value = "${preview.summary.totalBookmarksCount} 个",
                                    )
                                    RavenGroupedDivider()
                                    RavenGroupedRow(
                                        title = "订阅、搜索与屏蔽词",
                                        subtitle = "保存的搜索 ${preview.summary.savedSearchesCount} · 屏蔽词 ${preview.summary.blockedTermsCount}",
                                        value = "订阅 ${preview.summary.tagSubscriptionsCount}",
                                    )
                                }

                                preview.warnings.forEach { warning ->
                                    SettingsNoticeBanner(
                                        message = warning,
                                        tone = if (warning.contains("退役") || warning.contains("R34VBK01")) {
                                            RavenStatusTone.WARNING
                                        } else {
                                            RavenStatusTone.NEUTRAL
                                        },
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    SettingsSecondaryButton(
                                        text = "取消导入",
                                        enabled = !uiState.isRestoring,
                                        onClick = onCancelImport,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("backup_import_cancel_button"),
                                    )
                                    SettingsPrimaryButton(
                                        text = if (uiState.isRestoring) "正在原子合并…" else "确认合并恢复",
                                        icon = Icons.Outlined.Restore,
                                        loading = uiState.isRestoring,
                                        onClick = onConfirmRestore,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("backup_import_confirm_button"),
                                    )
                                }
                            }
                        }
                    }

                    val importErr = uiState.importError
                    if (!importErr.isNullOrBlank()) {
                        item(key = "import_error_banner") {
                            SettingsNoticeBanner(
                                title = "导入未完成",
                                message = importErr,
                                tone = RavenStatusTone.ERROR,
                            )
                        }
                    }

                    val restored = uiState.restoreCompleted
                    if (restored != null) {
                        item(key = "import_completed_banner") {
                            val summary = restored.mergedSummary
                            SettingsNoticeBanner(
                                title = "合并恢复完成（${formatBadgeLabel(restored.format)}）",
                                message = "合并后合计：收藏 ${summary.favoritesCount} 项 · 历史 ${summary.totalHistoryCount} 项 · 片单 ${summary.shelvesCount} 个（${summary.shelfItemsCount} 项）· 书签 ${summary.totalBookmarksCount} 个 · 订阅 ${summary.tagSubscriptionsCount} 项。已自动释放临时文件读取授权。",
                                tone = RavenStatusTone.SUCCESS,
                                modifier = Modifier.testTag("backup_import_success_banner"),
                            )
                        }
                    }
                }

                BackupRestoreTab.WEBDAV -> {
                    item(key = "webdav_form_section") {
                        RavenGroupedSection(
                            title = "WebDAV 服务器与加密口令",
                            footer = if (uiState.webDavLastSyncAt > 0L) {
                                "仅支持 HTTPS WebDAV 地址；凭据与加密口令均经硬件级密钥加密保存于本机，最近同步时间：${SettingsFormatters.formatTimestamp(uiState.webDavLastSyncAt)}。"
                            } else {
                                "仅支持 HTTPS WebDAV 地址；上传前在本地先执行 RVNBK01 端到端加密，服务端无法窥探任何数据。"
                            },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SettingsTextField(
                                    value = uiState.webDavEndpointUrl,
                                    onValueChange = onWebDavEndpointChange,
                                    label = "WebDAV 服务器目录 URL",
                                    placeholder = "https://dav.example.com/raven",
                                    enabled = !uiState.isWebDavBusy,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Next,
                                    ),
                                    modifier = Modifier.testTag("webdav_endpoint_field"),
                                )
                                SettingsTextField(
                                    value = uiState.webDavUsername,
                                    onValueChange = onWebDavUsernameChange,
                                    label = "账号 / 用户名",
                                    placeholder = "your-account",
                                    enabled = !uiState.isWebDavBusy,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    modifier = Modifier.testTag("webdav_username_field"),
                                )
                                SettingsTextField(
                                    value = uiState.webDavPassword,
                                    onValueChange = onWebDavPasswordChange,
                                    label = "WebDAV 密码 / 应用专用密码",
                                    isPassword = true,
                                    passwordVisible = webDavPwdVisible,
                                    onTogglePasswordVisibility = { webDavPwdVisible = !webDavPwdVisible },
                                    enabled = !uiState.isWebDavBusy,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Next,
                                    ),
                                    modifier = Modifier.testTag("webdav_password_field"),
                                )
                                SettingsTextField(
                                    value = uiState.webDavPassphrase,
                                    onValueChange = onWebDavPassphraseChange,
                                    label = "备份文件端到端加密口令（至少 ${BackupCodec.MIN_PASSWORD_LENGTH} 位）",
                                    isPassword = true,
                                    passwordVisible = webDavPassphraseVisible,
                                    onTogglePasswordVisibility = { webDavPassphraseVisible = !webDavPassphraseVisible },
                                    enabled = !uiState.isWebDavBusy,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Next,
                                    ),
                                    modifier = Modifier.testTag("webdav_passphrase_field"),
                                )
                                SettingsTextField(
                                    value = uiState.webDavRemoteFileName,
                                    onValueChange = onWebDavFileNameChange,
                                    label = "远端文件名",
                                    placeholder = "raven-sync.rvnbk",
                                    enabled = !uiState.isWebDavBusy,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    modifier = Modifier.testTag("webdav_filename_field"),
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    SettingsSecondaryButton(
                                        text = "保存配置",
                                        icon = Icons.Outlined.Save,
                                        enabled = !uiState.isWebDavBusy,
                                        onClick = onSaveWebDavConfig,
                                        modifier = Modifier.testTag("webdav_save_button"),
                                    )
                                    SettingsSecondaryButton(
                                        text = "测试连接",
                                        icon = Icons.Outlined.NetworkCheck,
                                        enabled = !uiState.isWebDavBusy,
                                        onClick = onTestWebDavConnection,
                                        modifier = Modifier.testTag("webdav_test_button"),
                                    )
                                    if (uiState.webDavSaved) {
                                        SettingsSecondaryButton(
                                            text = "清除配置",
                                            icon = Icons.Outlined.DeleteOutline,
                                            destructive = true,
                                            enabled = !uiState.isWebDavBusy,
                                            onClick = onClearWebDavConfig,
                                            modifier = Modifier.testTag("webdav_clear_button"),
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    SettingsPrimaryButton(
                                        text = if (uiState.isWebDavBusy) "处理中…" else "上传加密备份",
                                        icon = Icons.Outlined.CloudUpload,
                                        loading = uiState.isWebDavBusy,
                                        enabled = !uiState.isWebDavBusy,
                                        onClick = onPushWebDavBackup,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("webdav_push_button"),
                                    )
                                    SettingsSecondaryButton(
                                        text = "拉取并合并",
                                        icon = Icons.Outlined.Restore,
                                        enabled = !uiState.isWebDavBusy,
                                        onClick = onPullWebDavBackup,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("webdav_pull_button"),
                                    )
                                }
                            }
                        }
                    }

                    val davStatus = uiState.webDavStatusMessage
                    if (!davStatus.isNullOrBlank()) {
                        item(key = "webdav_status_banner") {
                            SettingsNoticeBanner(
                                title = "WebDAV 同步状态",
                                message = davStatus,
                                tone = RavenStatusTone.SUCCESS,
                                modifier = Modifier.testTag("webdav_status_banner"),
                            )
                        }
                    }

                    val davErr = uiState.webDavError
                    if (!davErr.isNullOrBlank()) {
                        item(key = "webdav_error_banner") {
                            SettingsNoticeBanner(
                                title = "WebDAV 操作未完成",
                                message = davErr,
                                tone = RavenStatusTone.ERROR,
                                modifier = Modifier.testTag("webdav_error_banner"),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBadgeLabel(format: BackupFormat): String = when (format) {
    BackupFormat.RAVEN_V1 -> "RVNBK01 原生"
    BackupFormat.LEGACY_R34V_V1 -> "R34VBK01 旧版"
}
