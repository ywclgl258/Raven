package com.raven.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.security.PrivacyLockStore
import java.io.File

/** 设置页 Sheet 层：网络诊断、隐私 PIN 设置、更新可用与应用许可/免责声明。从 `SettingsScreen.kt` 拆出，包名与 `testTag` 契约不变。 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkDiagnosticsSheet(
    state: NetworkDiagnosticsUiState,
    sites: List<SiteDescriptor>,
    fallbackSiteId: SiteId,
    onSelectSite: (SiteId) -> Unit,
    onRunProbe: (SiteId) -> Unit,
    onResetMirrorLease: (SiteId) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val selectedId = state.selectedSiteId ?: fallbackSiteId
    val selectedDescriptor = sites.firstOrNull { it.id == selectedId }
    val report = state.report

    LaunchedEffect(selectedId) {
        if (report == null || report.siteId != selectedId) {
            onSelectSite(selectedId)
        }
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        presentation = com.raven.app.core.designsystem.component.RavenSheetPresentation.Center,
        detent = com.raven.app.core.designsystem.component.RavenSheetDetent.Large,
        title = "网络与代理诊断",
        subtitle = "实时检测系统代理、IPv4 优先 DNS、TLS 证书与站点连通性",
        modifier = Modifier.testTag("settings_diagnostics_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (sites.isNotEmpty()) {
                RavenFilterChipRow(
                    items = sites,
                    selectedItem = selectedDescriptor,
                    labelProvider = { it.shortName },
                    keyProvider = { it.id.value },
                    onItemSelected = { descriptor -> onSelectSite(descriptor.id) },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                )
            }

            if (report != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val statusTone = when {
                        state.isProbing -> RavenStatusTone.NEUTRAL
                        report.httpStatus == null -> if (report.isConnected) RavenStatusTone.NEUTRAL else RavenStatusTone.WARNING
                        report.reachable -> RavenStatusTone.SUCCESS
                        else -> RavenStatusTone.ERROR
                    }
                    val statusLabel = when {
                        state.isProbing -> "正在探针实测…"
                        report.httpStatus == null -> if (report.isConnected) "待实测" else "网络离线"
                        report.reachable -> "连通正常 · HTTP ${report.httpStatus} · ${report.latencyMs} ms"
                        else -> "连接异常${report.httpStatus?.let { " · HTTP $it" }.orEmpty()}"
                    }
                    RavenStatusPill(text = statusLabel, tone = statusTone)
                    RavenStatusPill(
                        text = if (report.isMetered) "计费网络" else "非计费网络",
                        tone = if (report.isMetered) RavenStatusTone.WARNING else RavenStatusTone.NEUTRAL,
                    )
                }

                RavenGroupedSection(title = "连接与路由快照") {
                    RavenGroupedRow(
                        title = "目标站点",
                        subtitle = report.targetUrl,
                        value = report.siteName,
                    )
                    RavenGroupedDivider()
                    RavenGroupedRow(
                        title = "系统代理",
                        subtitle = report.proxyDescription,
                    )
                    RavenGroupedDivider()
                    RavenGroupedRow(
                        title = "路由纪元 (routeEpoch)",
                        value = "#${report.routeEpoch}",
                    )
                    RavenGroupedDivider()
                    RavenGroupedRow(
                        title = "最近握手节点",
                        subtitle = report.lastPeer.ifBlank { "尚未建立外部连接" },
                    )
                }

                val errMsg = report.errorMessage
                if (!errMsg.isNullOrBlank()) {
                    SettingsNoticeBanner(
                        title = "实测错误信息",
                        message = errMsg,
                        tone = RavenStatusTone.ERROR,
                    )
                }

                if (report.rateLimitEvents.isNotEmpty()) {
                    RavenGroupedSection(title = "近期限流日志") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            report.rateLimitEvents.forEach { event ->
                                Text(
                                    text = event,
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = colors.inkSecondary,
                                )
                            }
                        }
                    }
                }
            }

            val hostDiag = state.hostDiagnostics
            if (hostDiag != null) {
                RavenGroupedSection(title = "站点镜像路由与租约状态") {
                    RavenGroupedRow(
                        title = "当前租约域名",
                        subtitle = "候选：${hostDiag.candidateHosts.joinToString(" · ")}",
                        value = hostDiag.activeHost ?: "默认直连",
                    )
                    if (hostDiag.leaseRemainingMs > 0L) {
                        RavenGroupedDivider()
                        RavenGroupedRow(
                            title = "租约剩余时间",
                            value = "${(hostDiag.leaseRemainingMs / 1000L)} 秒",
                        )
                    }
                    if (hostDiag.coolingDownHosts.isNotEmpty()) {
                        RavenGroupedDivider()
                        RavenGroupedRow(
                            title = "故障冷却域名",
                            subtitle = hostDiag.coolingDownHosts.entries.joinToString(" · ") { (host, remMs) ->
                                "$host (${remMs / 1000L}s)"
                            },
                            value = "${hostDiag.coolingDownHosts.size} 个",
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hostDiag != null && hostDiag.candidateHosts.size > 1) {
                    SettingsSecondaryButton(
                        text = "重置镜像租约",
                        onClick = { onResetMirrorLease(selectedId) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_diagnostics_reset_lease_button"),
                    )
                }
                SettingsPrimaryButton(
                    text = if (state.isProbing) "正在实测…" else "运行连通探针",
                    icon = Icons.Rounded.Refresh,
                    loading = state.isProbing,
                    onClick = { onRunProbe(selectedId) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_diagnostics_run_button"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivacyPinSetupSheet(
    hasExistingPin: Boolean,
    onSavePin: (String, (Boolean) -> Unit) -> Unit,
    onClearPin: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pinInput by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val handleDismiss = {
        pinInput = ""
        pinConfirm = ""
        localError = null
        onDismiss()
    }

    SecureWindowEffect(enabled = true)

    RavenBottomSheet(
        onDismissRequest = handleDismiss,
        presentation = com.raven.app.core.designsystem.component.RavenSheetPresentation.Center,
        title = if (hasExistingPin) "管理应用解锁 PIN" else "设置应用解锁 PIN",
        subtitle = "4 至 8 位数字，经加盐 PBKDF2 与 AndroidKeyStore 加密保存",
        modifier = Modifier.testTag("settings_pin_setup_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsTextField(
                value = pinInput,
                onValueChange = { next ->
                    if (next.length <= 8 && next.all(Char::isDigit)) {
                        pinInput = next
                        localError = null
                    }
                },
                label = "输入新 PIN (4–8 位数字)",
                isPassword = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Next,
                ),
            )
            SettingsTextField(
                value = pinConfirm,
                onValueChange = { next ->
                    if (next.length <= 8 && next.all(Char::isDigit)) {
                        pinConfirm = next
                        localError = null
                    }
                },
                label = "再次确认 PIN",
                isPassword = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
            )

            val err = localError
            if (!err.isNullOrBlank()) {
                SettingsNoticeBanner(message = err, tone = RavenStatusTone.ERROR)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hasExistingPin) {
                    SettingsSecondaryButton(
                        text = "清除并关闭",
                        destructive = true,
                        onClick = {
                            pinInput = ""
                            pinConfirm = ""
                            localError = null
                            onClearPin()
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SettingsSecondaryButton(
                        text = "取消",
                        onClick = handleDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
                SettingsPrimaryButton(
                    text = "保存并启用",
                    loading = isSaving,
                    enabled = !isSaving,
                    onClick = {
                        val validationError = when {
                            !PrivacyLockStore.isValidPinFormat(pinInput) -> "PIN 必须为 4 到 8 位纯数字"
                            pinInput != pinConfirm -> "两次输入的 PIN 不一致"
                            else -> null
                        }
                        if (validationError != null) {
                            localError = validationError
                        } else {
                            isSaving = true
                            localError = null
                            val candidate = pinInput
                            onSavePin(candidate) { saved ->
                                isSaving = false
                                if (saved) {
                                    pinInput = ""
                                    pinConfirm = ""
                                } else {
                                    localError = "保存 PIN 失败，请重试"
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateAvailableSheet(
    state: UpdateUiState.Available,
    onDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val release = state.release
    val readyFile = state.readyApk
    val progress = state.downloadProgress

    val downloading = state.isDownloading
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        presentation = com.raven.app.core.designsystem.component.RavenSheetPresentation.Center,
        title = "发现新版本 ${release.tagName}",
        subtitle = "${release.asset.fileName} · ${RavenFormatters.formatByteSize(release.asset.sizeBytes)}",
        // 下载中误触背景会丢掉已下载的分块，必须走"取消下载"这个显式操作。
        dismissOnScrimClick = !downloading,
        modifier = Modifier.testTag("settings_update_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (release.releaseNotes.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RavenShapes.CardShape)
                        .background(colors.surface1)
                        .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "更新日志",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.inkSecondary,
                    )
                    Text(
                        text = release.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkPrimary,
                    )
                }
            }

            if (state.isDownloading && progress != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "正在下载并准备校验签名…",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                        Text(
                            text = "${RavenFormatters.formatByteSize(progress.downloadedBytes)} / ${RavenFormatters.formatByteSize(progress.totalBytes)}",
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = colors.inkPrimary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        color = colors.crimson,
                        trackColor = colors.surface3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            val err = state.downloadError
            if (!err.isNullOrBlank()) {
                SettingsNoticeBanner(
                    title = "更新失败",
                    message = err,
                    tone = RavenStatusTone.ERROR,
                )
            }

            if (readyFile != null) {
                SettingsNoticeBanner(
                    title = "完整性与签名已通过校验",
                    message = "安装包已完成字节长度、SHA-256 及应用签名证书链校验，可直接调用系统安装器。",
                    tone = RavenStatusTone.SUCCESS,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSecondaryButton(
                    text = if (state.isDownloading) "取消下载" else "稍后再说",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                if (readyFile != null) {
                    SettingsPrimaryButton(
                        text = "立即安装",
                        icon = Icons.Outlined.SystemUpdate,
                        onClick = { onInstall(readyFile) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_update_install_button"),
                    )
                } else {
                    SettingsPrimaryButton(
                        text = if (state.isDownloading) "下载中…" else "下载更新",
                        icon = Icons.Outlined.CloudDownload,
                        loading = state.isDownloading,
                        onClick = onDownload,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_update_download_button"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicensesAndDisclaimerSheet(
    appInfo: RavenAppInfo,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val libraries = listOf(
        "Kotlin & Coroutines & Serialization" to "Apache-2.0",
        "AndroidX Jetpack Compose / Material 3 / Navigation / Room / DataStore / WorkManager" to "Apache-2.0",
        "AndroidX Media3 (ExoPlayer / HLS / DASH)" to "Apache-2.0",
        "OkHttp 4" to "Apache-2.0",
        "Coil 3" to "Apache-2.0",
        "Jsoup" to "MIT",
        "Koin DI" to "Apache-2.0",
    )

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        presentation = com.raven.app.core.designsystem.component.RavenSheetPresentation.Center,
        title = "开源许可与免责说明",
        subtitle = "渡鸦 Raven v${appInfo.versionName}",
        modifier = Modifier.testTag("settings_licenses_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsNoticeBanner(
                title = "免责与隐私声明",
                message = "渡鸦 (Raven) 是纯本地开源媒体浏览器客户端，不托管、不索引、不分发任何第三方内容，亦不与任何外部站点存在隶属关系。所有历史记录、收藏、片单与站点会话均仅保存在本机，敏感凭据通过 AndroidKeyStore 硬件级密钥加密存储，绝不上传至任何服务器。",
                tone = RavenStatusTone.NEUTRAL,
            )

            RavenGroupedSection(title = "核心开源组件") {
                libraries.forEachIndexed { idx, (name, license) ->
                    if (idx > 0) RavenGroupedDivider()
                    RavenGroupedRow(
                        title = name,
                        value = license,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            SettingsPrimaryButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
