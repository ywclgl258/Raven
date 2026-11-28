package com.raven.app.feature.settings.lansync

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
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.security.LanSyncCrypto
import com.raven.app.core.security.LanSyncTrustStore
import com.raven.app.feature.settings.SecureWindowEffect
import com.raven.app.feature.settings.SettingsFormatters
import com.raven.app.feature.settings.SettingsNavigator
import com.raven.app.feature.settings.SettingsNoticeBanner
import com.raven.app.feature.settings.SettingsPrimaryButton
import com.raven.app.feature.settings.SettingsSecondaryButton
import com.raven.app.feature.settings.SettingsTextField
import org.koin.androidx.compose.koinViewModel

@Composable
fun LanSyncScreen(
    navigator: SettingsNavigator,
    modifier: Modifier = Modifier,
    viewModel: LanSyncViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.startSession()
        onDispose {
            viewModel.stopSession()
        }
    }

    LanSyncScreenContent(
        uiState = uiState,
        navigator = navigator,
        onToggleDiscoverable = viewModel::setDiscoverable,
        onRefreshDiscovery = viewModel::refreshDiscovery,
        onRegeneratePin = viewModel::regeneratePairingPin,
        onUpdateDeviceName = viewModel::updateLocalDeviceName,
        onSelectSyncMode = viewModel::selectSyncMode,
        onSyncWithPeer = viewModel::syncWithPeer,
        onManualSync = viewModel::syncWithManualEndpoint,
        onConfirmPullPreview = viewModel::confirmPullPreview,
        onCancelPullPreview = viewModel::cancelPullPreview,
        onRemoveTrustedPeer = viewModel::removeTrustedPeer,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LanSyncScreenContent(
    uiState: LanSyncUiState,
    navigator: SettingsNavigator,
    onToggleDiscoverable: (Boolean) -> Unit,
    onRefreshDiscovery: () -> Unit,
    onRegeneratePin: () -> Unit,
    onUpdateDeviceName: (String) -> Unit,
    onSelectSyncMode: (LanSyncMode) -> Unit,
    onSyncWithPeer: (DiscoveredLanPeer, String?, Boolean) -> Unit,
    onManualSync: (String, String, Boolean) -> Unit,
    onConfirmPullPreview: () -> Unit,
    onCancelPullPreview: () -> Unit,
    onRemoveTrustedPeer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    // Protect the pairing PIN from screenshots while this screen is active.
    SecureWindowEffect(enabled = true)

    var pinTargetPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pinInput by rememberSaveable { mutableStateOf("") }
    var rememberPeerChecked by rememberSaveable { mutableStateOf(true) }

    var showManualConnectSheet by rememberSaveable { mutableStateOf(false) }
    var manualEndpointInput by rememberSaveable { mutableStateOf("") }
    var manualPinInput by rememberSaveable { mutableStateOf("") }
    var manualRememberChecked by rememberSaveable { mutableStateOf(true) }

    var showRenameSheet by rememberSaveable { mutableStateOf(false) }
    var renameInput by rememberSaveable(uiState.localDeviceName) { mutableStateOf(uiState.localDeviceName) }

    val pinTargetPeer = uiState.discoveredPeers.firstOrNull { it.deviceId == pinTargetPeerId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("lan_sync_screen"),
    ) {
        RavenTopBar(
            title = "局域网多设备同步",
            subtitle = "同一 Wi-Fi 自动发现 · 端到端加密",
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("lan_sync_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = onRefreshDiscovery,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.testTag("lan_sync_refresh_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "重新扫描设备",
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
            item(key = "lan_sync_security_notice") {
                SettingsNoticeBanner(
                    title = "局域网点对点加密直连",
                    message = "采用 ECDH (P-256) + AES-256-GCM 端到端加密传输，仅限局域网私有网段（10.x / 172.16-31.x / 192.168.x）。绝不传输站点登录态、Cookie 或本地媒体文件。",
                    icon = Icons.Outlined.Shield,
                    tone = RavenStatusTone.NEUTRAL,
                )
            }

            if (!uiState.errorMessage.isNullOrBlank()) {
                item(key = "lan_sync_error_banner") {
                    SettingsNoticeBanner(
                        title = "同步提示",
                        message = uiState.errorMessage,
                        tone = RavenStatusTone.ERROR,
                        modifier = Modifier.testTag("lan_sync_error_banner"),
                    )
                }
            }

            if (!uiState.statusMessage.isNullOrBlank()) {
                item(key = "lan_sync_status_banner") {
                    SettingsNoticeBanner(
                        title = "状态通知",
                        message = uiState.statusMessage,
                        tone = RavenStatusTone.SUCCESS,
                        modifier = Modifier.testTag("lan_sync_status_banner"),
                    )
                }
            }

            // 1. 本机服务状态与配对码
            item(key = "section_local_host") {
                val endpointSummary = when {
                    uiState.isStarting -> "正在启动同步服务…"
                    uiState.listeningPort <= 0 -> "服务未启动"
                    uiState.localAddresses.isEmpty() -> "端口 ${uiState.listeningPort} · 未检测到局域网 Wi-Fi IP"
                    else -> uiState.localAddresses.joinToString(" / ") { "$it:${uiState.listeningPort}" }
                }

                RavenGroupedSection(
                    title = "本机发现与配对码",
                    footer = "当另一台设备首次向本机发起同步时，输入下方 6 位配对码即可完成加密配对；连续输错 5 次会自动轮换新配对码。",
                ) {
                    RavenGroupedSwitchRow(
                        title = "允许附近渡鸦设备发现我",
                        subtitle = if (uiState.isDiscoverable) {
                            "已开启 mDNS + 局域网广播双通道"
                        } else {
                            "已隐藏发现信标（仍可通过本机 IP 接收连接）"
                        },
                        icon = Icons.Outlined.WifiTethering,
                        checked = uiState.isDiscoverable,
                        onCheckedChange = onToggleDiscoverable,
                        modifier = Modifier.testTag("lan_sync_discoverable_switch"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "本机名称",
                        subtitle = "指纹 ${uiState.localFingerprint.ifBlank { "--" }} · 点击修改",
                        value = uiState.localDeviceName,
                        icon = Icons.Outlined.Edit,
                        showChevron = true,
                        onClick = {
                            renameInput = uiState.localDeviceName
                            showRenameSheet = true
                        },
                        modifier = Modifier.testTag("lan_sync_device_name_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "本机局域网地址",
                        subtitle = endpointSummary,
                        icon = Icons.Outlined.Devices,
                        modifier = Modifier.testTag("lan_sync_endpoint_row"),
                    )
                    RavenGroupedDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "一次性配对码",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.inkPrimary,
                                )
                                Text(
                                    text = "在发起方设备选择本机并输入此 6 位数字",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkSecondary,
                                )
                            }
                            RavenStatusPill(
                                text = if (uiState.listeningPort > 0) "待命中" else "准备中",
                                tone = if (uiState.listeningPort > 0) RavenStatusTone.SUCCESS else RavenStatusTone.NEUTRAL,
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RavenShapes.CardShape)
                                .background(colors.surface2)
                                .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatPinDisplay(uiState.pairingPin),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 3.sp,
                                ),
                                color = colors.crimson,
                                modifier = Modifier.testTag("lan_sync_pin_text"),
                            )
                            SettingsSecondaryButton(
                                text = "刷新",
                                icon = Icons.Outlined.Refresh,
                                onClick = onRegeneratePin,
                                modifier = Modifier.testTag("lan_sync_rotate_pin_button"),
                            )
                        }
                    }
                }
            }

            // 2. 同步模式
            item(key = "section_sync_mode") {
                RavenGroupedSection(
                    title = "同步方向",
                    footer = uiState.selectedSyncMode.description,
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        LanSyncMode.entries.forEach { mode ->
                            val icon = when (mode) {
                                LanSyncMode.TWO_WAY -> Icons.Outlined.Sync
                                LanSyncMode.PUSH -> Icons.Outlined.Upload
                                LanSyncMode.PULL -> Icons.Outlined.Download
                            }
                            RavenFilterChip(
                                label = mode.displayLabel,
                                selected = uiState.selectedSyncMode == mode,
                                leadingIcon = icon,
                                onClick = { onSelectSyncMode(mode) },
                                modifier = Modifier.testTag("lan_sync_mode_${mode.name.lowercase()}"),
                            )
                        }
                    }
                }
            }

            // 3. 拉取预览确认门禁
            val preview = uiState.pullPreview
            if (preview != null) {
                item(key = "section_pull_preview") {
                    RavenGroupedSection(
                        title = "待确认的拉取快照",
                        footer = "在点击“确认合并到本机”前，本机数据库保持原样不受任何影响。",
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SettingsNoticeBanner(
                                title = "来自 ${preview.peerDeviceName} (${preview.peerEndpoint})",
                                message = preview.summary.toDisplayText(),
                                icon = Icons.Outlined.CheckCircle,
                                tone = RavenStatusTone.ACCENT,
                            )
                            preview.warnings.forEach { warning ->
                                SettingsNoticeBanner(
                                    message = warning,
                                    tone = RavenStatusTone.WARNING,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SettingsSecondaryButton(
                                    text = "取消",
                                    enabled = !uiState.isBusy,
                                    onClick = onCancelPullPreview,
                                    modifier = Modifier.weight(1f),
                                )
                                SettingsPrimaryButton(
                                    text = if (uiState.isManualSyncing) "合并中…" else "确认合并到本机",
                                    icon = Icons.Outlined.CheckCircle,
                                    loading = uiState.isManualSyncing,
                                    enabled = !uiState.isBusy,
                                    onClick = onConfirmPullPreview,
                                    modifier = Modifier
                                        .weight(1.6f)
                                        .testTag("lan_sync_confirm_pull_button"),
                                )
                            }
                        }
                    }
                }
            }

            // 4. 附近渡鸦设备
            item(key = "section_discovered_peers") {
                RavenGroupedSection(
                    title = "附近渡鸦设备 (${uiState.discoveredPeers.size})",
                    footer = "请确保所有设备连接在同一个局域网 Wi-Fi 下，并都在各自设备上打开“局域网多设备同步”页面。",
                ) {
                    if (uiState.discoveredPeers.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = if (uiState.isDiscoverable) {
                                    "暂未发现附近设备，正在持续监听 mDNS 与局域网广播信标…"
                                } else {
                                    "已关闭自动发现开关，可通过下方手动输入局域网 IP 连接"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                            )
                        }
                    } else {
                        uiState.discoveredPeers.forEachIndexed { index, peer ->
                            if (index > 0) {
                                RavenGroupedDivider()
                            }
                            DiscoveredPeerRow(
                                peer = peer,
                                syncMode = uiState.selectedSyncMode,
                                isSyncingThisPeer = uiState.syncingPeerId == peer.deviceId,
                                anyBusy = uiState.isBusy,
                                onClickSync = {
                                    if (peer.isTrusted) {
                                        onSyncWithPeer(peer, null, true)
                                    } else {
                                        pinInput = ""
                                        rememberPeerChecked = true
                                        pinTargetPeerId = peer.deviceId
                                    }
                                },
                                onClickPairWithPin = {
                                    pinInput = ""
                                    rememberPeerChecked = true
                                    pinTargetPeerId = peer.deviceId
                                },
                            )
                        }
                    }
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "手动输入局域网 IP:端口 连接",
                        subtitle = "当路由器开启 AP 隔离或跨网段组网无法自动发现时使用",
                        icon = Icons.Outlined.AddLink,
                        showChevron = true,
                        onClick = {
                            manualPinInput = ""
                            manualRememberChecked = true
                            showManualConnectSheet = true
                        },
                        modifier = Modifier.testTag("lan_sync_manual_connect_row"),
                    )
                }
            }

            // 5. 已信任设备
            if (uiState.trustedPeers.isNotEmpty()) {
                item(key = "section_trusted_peers") {
                    RavenGroupedSection(
                        title = "已信任的配对设备 (${uiState.trustedPeers.size})",
                        footer = "已信任设备存储在本机硬件级 KeyStore 加密存储中，再次连接时无需重新输入配对码。",
                    ) {
                        uiState.trustedPeers.forEachIndexed { index, trusted ->
                            if (index > 0) {
                                RavenGroupedDivider()
                            }
                            RavenGroupedRow(
                                title = trusted.deviceName,
                                subtitle = buildString {
                                    append("指纹 ")
                                    append(LanSyncCrypto.deviceFingerprint(trusted.deviceId))
                                    if (trusted.lastSyncedAtMs > 0L) {
                                        append(" · 上次同步 ")
                                        append(SettingsFormatters.formatTimestamp(trusted.lastSyncedAtMs))
                                    }
                                },
                                icon = Icons.Outlined.Devices,
                                trailingContent = {
                                    SettingsSecondaryButton(
                                        text = "移除",
                                        destructive = true,
                                        icon = Icons.Outlined.DeleteOutline,
                                        enabled = !uiState.isBusy,
                                        onClick = { onRemoveTrustedPeer(trusted.deviceId) },
                                    )
                                },
                                modifier = Modifier.testTag("lan_sync_trusted_${trusted.deviceId}"),
                            )
                        }
                    }
                }
            }

            // 6. 最近收到的同步动态
            if (uiState.incomingEvents.isNotEmpty()) {
                item(key = "section_incoming_events") {
                    RavenGroupedSection(title = "本次会话同步记录") {
                        uiState.incomingEvents.forEachIndexed { index, event ->
                            if (index > 0) {
                                RavenGroupedDivider()
                            }
                            RavenGroupedRow(
                                title = "${event.peerDeviceName} · ${event.mode.displayLabel}",
                                subtitle = SettingsFormatters.formatTimestamp(event.completedAtMs),
                                value = event.mergedSummary?.let { "${it.favoritesCount} 收藏" } ?: "已发送快照",
                            )
                        }
                    }
                }
            }
        }
    }

    // PIN 配对底部面板
    if (pinTargetPeer != null) {
        RavenBottomSheet(
            onDismissRequest = { pinTargetPeerId = null },
            title = "配对并同步：${pinTargetPeer.deviceName}",
            subtitle = "${pinTargetPeer.host}:${pinTargetPeer.port} · 指纹 ${pinTargetPeer.fingerprint}",
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsTextField(
                    value = pinInput,
                    onValueChange = { raw ->
                        pinInput = raw.filter { it.isDigit() }.take(LanSyncCrypto.PIN_LENGTH)
                    },
                    label = "对端设备 6 位配对码",
                    placeholder = "例如 482910",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (pinInput.length == LanSyncCrypto.PIN_LENGTH) {
                                val target = pinTargetPeer
                                val code = pinInput
                                val remember = rememberPeerChecked
                                pinTargetPeerId = null
                                onSyncWithPeer(target, code, remember)
                            }
                        },
                    ),
                    modifier = Modifier.testTag("lan_sync_peer_pin_input"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "记住此设备",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "下次自动认证免输配对码",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                    }
                    Switch(
                        checked = rememberPeerChecked,
                        onCheckedChange = { rememberPeerChecked = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.crimson),
                    )
                }

                SettingsPrimaryButton(
                    text = "立即${uiState.selectedSyncMode.displayLabel}",
                    icon = Icons.Outlined.Sync,
                    enabled = pinInput.length == LanSyncCrypto.PIN_LENGTH && !uiState.isBusy,
                    onClick = {
                        val target = pinTargetPeer
                        val code = pinInput
                        val remember = rememberPeerChecked
                        pinTargetPeerId = null
                        onSyncWithPeer(target, code, remember)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lan_sync_peer_pin_submit"),
                )
            }
        }
    }

    // 手动输入 IP:端口 底部面板
    if (showManualConnectSheet) {
        RavenBottomSheet(
            onDismissRequest = { showManualConnectSheet = false },
            title = "手动输入局域网地址",
            subtitle = "仅接受 10.x / 172.16-31.x / 192.168.x 私有地址",
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsTextField(
                    value = manualEndpointInput,
                    onValueChange = { manualEndpointInput = it },
                    label = "对端 IP:端口",
                    placeholder = "192.168.1.20:${LanSyncConstants.DEFAULT_TCP_PORT}",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.testTag("lan_sync_manual_endpoint_input"),
                )
                SettingsTextField(
                    value = manualPinInput,
                    onValueChange = { raw ->
                        manualPinInput = raw.filter { it.isDigit() }.take(LanSyncCrypto.PIN_LENGTH)
                    },
                    label = "6 位配对码",
                    placeholder = "输入对端显示的 6 位数字",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.testTag("lan_sync_manual_pin_input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "记住此设备（下次自动发现可免码同步）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = manualRememberChecked,
                        onCheckedChange = { manualRememberChecked = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.crimson),
                    )
                }
                SettingsPrimaryButton(
                    text = "连接并${uiState.selectedSyncMode.displayLabel}",
                    icon = Icons.Outlined.AddLink,
                    enabled = manualEndpointInput.isNotBlank() &&
                        manualPinInput.length == LanSyncCrypto.PIN_LENGTH &&
                        !uiState.isBusy,
                    onClick = {
                        val endpoint = manualEndpointInput
                        val pin = manualPinInput
                        val remember = manualRememberChecked
                        showManualConnectSheet = false
                        onManualSync(endpoint, pin, remember)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lan_sync_manual_submit"),
                )
            }
        }
    }

    // 修改本机名称底部面板
    if (showRenameSheet) {
        RavenBottomSheet(
            onDismissRequest = { showRenameSheet = false },
            title = "修改本机同步名称",
            subtitle = "附近设备发现列表中会显示此名称",
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it.take(LanSyncTrustStore.MAX_DEVICE_NAME_LENGTH) },
                    label = "设备名称",
                    placeholder = LanSyncTrustStore.DEFAULT_DEVICE_NAME,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.testTag("lan_sync_rename_input"),
                )
                SettingsPrimaryButton(
                    text = "保存名称",
                    enabled = renameInput.trim().isNotEmpty(),
                    onClick = {
                        val next = renameInput
                        showRenameSheet = false
                        onUpdateDeviceName(next)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lan_sync_rename_submit"),
                )
            }
        }
    }
}

@Composable
private fun DiscoveredPeerRow(
    peer: DiscoveredLanPeer,
    syncMode: LanSyncMode,
    isSyncingThisPeer: Boolean,
    anyBusy: Boolean,
    onClickSync: () -> Unit,
    onClickPairWithPin: () -> Unit,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("lan_sync_peer_${peer.deviceId}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = peer.deviceName,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                )
                Text(
                    text = buildString {
                        append(peer.host)
                        append(':')
                        append(peer.port)
                        if (peer.appVersion.isNotBlank()) {
                            append(" · v")
                            append(peer.appVersion)
                        }
                        append(" · ")
                        append(peer.source.badgeLabel)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
            }
            RavenStatusPill(
                text = if (peer.isTrusted) "已信任" else "未配对",
                tone = if (peer.isTrusted) RavenStatusTone.SUCCESS else RavenStatusTone.NEUTRAL,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsPrimaryButton(
                text = if (isSyncingThisPeer) {
                    "同步中…"
                } else if (peer.isTrusted) {
                    "一键${syncMode.displayLabel}"
                } else {
                    "配对并${syncMode.displayLabel}"
                },
                icon = Icons.Outlined.Sync,
                loading = isSyncingThisPeer,
                enabled = !anyBusy,
                onClick = onClickSync,
                modifier = Modifier
                    .weight(1f)
                    .testTag("lan_sync_peer_action_${peer.deviceId}"),
            )
            if (peer.isTrusted) {
                SettingsSecondaryButton(
                    text = "输码重配",
                    enabled = !anyBusy,
                    onClick = onClickPairWithPin,
                )
            }
        }
    }
}

private fun formatPinDisplay(pin: String): String {
    val clean = pin.trim()
    return if (clean.length == 6) "${clean.substring(0, 3)} ${clean.substring(3)}" else clean.ifBlank { "------" }
}
