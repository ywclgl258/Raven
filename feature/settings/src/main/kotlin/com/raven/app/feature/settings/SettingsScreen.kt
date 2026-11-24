package com.raven.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChipRow
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenGroupedSliderRow
import com.raven.app.core.designsystem.component.RavenGroupedSwitchRow
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import java.io.File
import org.koin.androidx.compose.koinViewModel

private enum class SettingsSheetKind {
    NONE,
    QUALITY,
    DIAGNOSTICS,
    LICENSES,
}

@Composable
fun SettingsScreen(
    navigator: SettingsNavigator,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOverview()
    }

    SettingsScreenContent(
        uiState = uiState,
        navigator = navigator,
        onToggleReduceTransparency = viewModel::setReduceTransparency,
        onToggleTranslateTags = viewModel::setTranslateTags,
        onToggleAutoFullscreen = viewModel::setAutoFullscreen,
        onSelectQuality = viewModel::setPreferredQuality,
        onChangeDefaultSpeed = viewModel::setDefaultPlaybackSpeed,
        onToggleStreamCache = viewModel::setStreamCacheEnabled,
        onToggleSeekCache = viewModel::setSeekCacheEnabled,
        onClearReadingCache = viewModel::clearAllReadingCaches,
        onToggleDownloadUnmeteredOnly = viewModel::setDownloadUnmeteredOnly,
        onOpenDiagnostics = viewModel::prepareDiagnosticsSnapshot,
        onRunLiveDiagnostics = viewModel::runLiveNetworkDiagnostics,
        onToggleAutoUpdateCheck = viewModel::setAutoUpdateCheck,
        onManualCheckUpdate = { viewModel.checkForUpdates(manual = true) },
        onDownloadUpdate = viewModel::downloadAvailableUpdate,
        onInstallUpdate = navigator::openAppUpdateInstaller,
        onDismissUpdateSheet = viewModel::dismissUpdateSheet,
        onDismissFeedback = viewModel::clearFeedback,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    uiState: SettingsUiState,
    navigator: SettingsNavigator,
    onToggleReduceTransparency: (Boolean) -> Unit,
    onToggleTranslateTags: (Boolean) -> Unit,
    onToggleAutoFullscreen: (Boolean) -> Unit,
    onSelectQuality: (String) -> Unit,
    onChangeDefaultSpeed: (Float) -> Unit,
    onToggleStreamCache: (Boolean) -> Unit,
    onToggleSeekCache: (Boolean) -> Unit,
    onClearReadingCache: () -> Unit,
    onToggleDownloadUnmeteredOnly: (Boolean) -> Unit,
    onOpenDiagnostics: (SiteId?) -> Unit,
    onRunLiveDiagnostics: (SiteId?) -> Unit,
    onToggleAutoUpdateCheck: (Boolean) -> Unit,
    onManualCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: (File) -> Unit,
    onDismissUpdateSheet: () -> Unit,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    var activeSheet by rememberSaveable { mutableStateOf(SettingsSheetKind.NONE) }
    var accountsExpanded by rememberSaveable { mutableStateOf(false) }

    var sliderSpeed by remember(uiState.settings.defaultPlaybackSpeed) {
        mutableFloatStateOf(uiState.settings.defaultPlaybackSpeed)
    }

    val activeSite = uiState.activeSite
    val activeSiteId = activeSite?.id ?: uiState.settings.activeSiteId
    val signedInCount = uiState.authSites.count { it.authState.isAuthenticated }
    val storageStatus = uiState.storageStatus

    val storageSummary = when {
        storageStatus == null -> if (uiState.settings.storageLocation == StorageLocation.SHARED) "公共目录" else "应用内存储"
        storageStatus.pendingLocation != null -> "重启后切换"
        storageStatus.activeLocation == StorageLocation.SHARED -> "公共目录"
        else -> "应用内存储"
    }

    val cacheSummaryText = when {
        uiState.readingCache.isClearing -> "正在清理…"
        uiState.readingCache.isRefreshing -> "统计中…"
        uiState.readingCache.totalPages == 0 -> "0 页 · 0 B"
        else -> "${uiState.readingCache.totalPages} 页 · ${RavenFormatters.formatByteSize(uiState.readingCache.totalBytes)}"
    }

    val versionReadout = buildString {
        append("v${uiState.appInfo.versionName}")
        append(" (${uiState.appInfo.versionCode})")
        if (uiState.appInfo.isDebug) append(" · Debug")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("settings_screen"),
    ) {
        RavenTopBar(
            title = "设置",
            subtitle = "渡鸦 Raven 偏好与安全中心",
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("settings_back_button"),
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("settings_list"),
        ) {
            val feedback = uiState.feedbackMessage
            if (!feedback.isNullOrBlank()) {
                item(key = "settings_feedback_banner") {
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

            // 1. 外观与阅读
            item(key = "section_appearance_reading") {
                RavenGroupedSection(
                    title = "外观与阅读",
                    footer = "减少透明效果会将 API 31+ 毛玻璃顶栏切换为高对比度暗色遮罩，适合高对比或低功耗需求。",
                ) {
                    RavenGroupedSwitchRow(
                        title = "减少透明效果",
                        subtitle = "关闭顶栏与悬浮条模糊效果，使用实色深曜石遮罩",
                        icon = Icons.Outlined.Contrast,
                        checked = uiState.settings.reduceTransparency,
                        onCheckedChange = onToggleReduceTransparency,
                        modifier = Modifier.testTag("settings_reduce_transparency"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "中文标签词典翻译",
                        subtitle = "使用内置 165 条标签词典中文化显示作品标签与搜索联想",
                        icon = Icons.Outlined.Translate,
                        checked = uiState.settings.translateTags,
                        onCheckedChange = onToggleTranslateTags,
                        modifier = Modifier.testTag("settings_translate_tags"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "视频自动全屏",
                        subtitle = "播放时按视频横竖屏比例自动进入全屏视图",
                        icon = Icons.Outlined.Fullscreen,
                        checked = uiState.settings.autoFullscreen,
                        onCheckedChange = onToggleAutoFullscreen,
                        modifier = Modifier.testTag("settings_auto_fullscreen"),
                    )
                }
            }

            // 2. 播放与缓存
            item(key = "section_playback_cache") {
                RavenGroupedSection(
                    title = "播放与缓存",
                    footer = "流式缓存使用 512 MiB LRU 空间；Seek 会话缓存（64 MiB MP4 + 32 MiB HLS）用于加速拖动回看。",
                ) {
                    RavenGroupedRow(
                        title = "默认清晰度",
                        subtitle = "新播放视频及下载时优先匹配的分辨率",
                        value = SettingsFormatters.qualityLabel(uiState.settings.preferredQuality),
                        icon = Icons.Outlined.HighQuality,
                        showChevron = true,
                        onClick = { activeSheet = SettingsSheetKind.QUALITY },
                        modifier = Modifier.testTag("settings_preferred_quality"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSliderRow(
                        title = "全局默认倍速",
                        subtitle = "范围 0.25× – 3.00×，各站点可在播放器内单独覆盖",
                        icon = Icons.Outlined.Speed,
                        value = sliderSpeed,
                        valueRange = 0.25f..3.0f,
                        steps = 10,
                        valueLabel = SettingsFormatters.formatSpeed(sliderSpeed),
                        onValueChange = { sliderSpeed = SettingsFormatters.snapPlaybackSpeed(it) },
                        onValueChangeFinished = { onChangeDefaultSpeed(sliderSpeed) },
                        modifier = Modifier.testTag("settings_default_speed"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "512 MiB 流式缓存",
                        subtitle = "播放时缓存媒体分段，减少重复拉流耗流",
                        icon = Icons.Outlined.Cached,
                        checked = uiState.settings.streamCacheEnabled,
                        onCheckedChange = onToggleStreamCache,
                        modifier = Modifier.testTag("settings_stream_cache"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "Seek 会话缓存",
                        subtitle = "保留当前播放会话的进度回看缓冲",
                        icon = Icons.Outlined.Memory,
                        checked = uiState.settings.seekCacheEnabled,
                        onCheckedChange = onToggleSeekCache,
                        modifier = Modifier.testTag("settings_seek_cache"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "阅读缓存占用",
                        subtitle = "临时浏览图页缓存（不影响已永久下载的漫画与写真）",
                        value = cacheSummaryText,
                        icon = Icons.Outlined.CleaningServices,
                        enabled = !uiState.readingCache.isClearing,
                        onClick = if (uiState.readingCache.totalPages > 0 || uiState.readingCache.totalBytes > 0L) {
                            onClearReadingCache
                        } else {
                            null
                        },
                        trailingContent = {
                            if (uiState.readingCache.totalPages > 0 || uiState.readingCache.totalBytes > 0L) {
                                TextButton(
                                    onClick = onClearReadingCache,
                                    enabled = !uiState.readingCache.isClearing,
                                    modifier = Modifier.testTag("settings_clear_reading_cache"),
                                ) {
                                    Text(
                                        text = "一键清理",
                                        color = colors.crimson,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("settings_reading_cache_row"),
                    )
                }
            }

            // 3. 下载与存储
            item(key = "section_downloads_storage") {
                RavenGroupedSection(
                    title = "下载与存储",
                    footer = if (uiState.isMeteredNetwork && uiState.settings.downloadUnmeteredOnly) {
                        "当前处于计费网络，下载任务将自动等待非计费网络恢复。"
                    } else {
                        "切换到公共存储目录时采用 SHA-256 逐文件校验并保留原文件回退。"
                    },
                ) {
                    RavenGroupedSwitchRow(
                        title = "仅非计费网络下载",
                        subtitle = "使用移动数据等计费网络时自动暂停后台下载",
                        icon = Icons.Outlined.Wifi,
                        checked = uiState.settings.downloadUnmeteredOnly,
                        onCheckedChange = onToggleDownloadUnmeteredOnly,
                        modifier = Modifier.testTag("settings_download_unmetered"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "存储位置管理",
                        subtitle = storageStatus?.note?.ifBlank { storageStatus.activePath }
                            ?: "查看应用内与公共目录路径并执行校验迁移",
                        value = storageSummary,
                        icon = Icons.Outlined.Folder,
                        showChevron = true,
                        onClick = navigator::openStorageMigration,
                        modifier = Modifier.testTag("settings_storage_migration_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "重复漫画检查",
                        subtitle = "按页数与逐页 SHA-256 精确识别已下载和本地导入的重复整本",
                        icon = Icons.Outlined.FileCopy,
                        showChevron = true,
                        onClick = navigator::openDuplicateScanner,
                        modifier = Modifier.testTag("settings_duplicate_scanner_row"),
                    )
                }
            }

            // 4. 站点与安全
            item(key = "section_sites_security") {
                RavenGroupedSection(
                    title = "站点与安全",
                    footer = "所有站点 Cookie 与 Bearer 令牌统一经 AndroidKeyStore AES-256-GCM 加密隔离，绝不发往媒体 CDN 或更新服务器。",
                ) {
                    if (uiState.activeSiteSupportsAuth && activeSite != null) {
                        val activeAuth = uiState.authSites.firstOrNull { it.descriptor.id == activeSite.id }
                        RavenGroupedRow(
                            title = "当前站点账号（${activeSite.shortName}）",
                            subtitle = if (activeAuth?.authState?.isAuthenticated == true) {
                                activeAuth.authState.username ?: "已保持加密会话"
                            } else {
                                "点击登录或管理此站点加密会话"
                            },
                            value = if (activeAuth?.authState?.isAuthenticated == true) "已登录" else "未登录",
                            icon = Icons.Outlined.ManageAccounts,
                            iconTint = if (activeAuth?.authState?.isAuthenticated == true) colors.success else colors.crimson,
                            showChevron = true,
                            onClick = { navigator.openSiteAccount(activeSite.id) },
                            modifier = Modifier.testTag("settings_active_site_account_row"),
                        )
                        RavenGroupedDivider(insetWithIcon = true)
                    }

                    RavenGroupedRow(
                        title = "管理站点账号",
                        subtitle = "支持 ${uiState.authSites.size} 个认证站点的独立登录与注销",
                        value = "$signedInCount / ${uiState.authSites.size} 已登录",
                        icon = Icons.Outlined.Security,
                        onClick = { accountsExpanded = !accountsExpanded },
                        trailingContent = {
                            Icon(
                                imageVector = if (accountsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (accountsExpanded) "收起站点账号列表" else "展开站点账号列表",
                                tint = colors.inkSecondary,
                            )
                        },
                        modifier = Modifier.testTag("settings_manage_accounts_toggle"),
                    )

                    AnimatedVisibility(visible = accountsExpanded) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            uiState.authSites.forEach { entry ->
                                RavenGroupedDivider(insetWithIcon = true)
                                RavenGroupedRow(
                                    title = entry.descriptor.displayName,
                                    subtitle = if (entry.authState.isAuthenticated) {
                                        entry.authState.username ?: "已加密登录"
                                    } else {
                                        entry.descriptor.defaultHost
                                    },
                                    showChevron = true,
                                    trailingContent = {
                                        RavenStatusPill(
                                            text = if (entry.authState.isAuthenticated) "已登录" else "未登录",
                                            tone = if (entry.authState.isAuthenticated) {
                                                RavenStatusTone.SUCCESS
                                            } else {
                                                RavenStatusTone.NEUTRAL
                                            },
                                        )
                                    },
                                    onClick = { navigator.openSiteAccount(entry.descriptor.id) },
                                    modifier = Modifier.testTag("settings_account_site_${entry.descriptor.id.value}"),
                                )
                            }
                        }
                    }

                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "站点屏蔽词",
                        subtitle = activeSite?.let { "管理 ${it.displayName} 的标签与关键词屏蔽规则" }
                            ?: "管理当前站点屏蔽词",
                        value = "${uiState.activeSiteBlockedTermsCount} 条",
                        icon = Icons.Outlined.Block,
                        showChevron = true,
                        onClick = { navigator.openBlockedTerms(activeSiteId) },
                        modifier = Modifier.testTag("settings_blocked_terms_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "网络诊断",
                        subtitle = "查看系统代理、路由纪元 #${uiState.routeEpoch} 与 DNS/TLS 实测连通性",
                        value = if (uiState.isConnectedNetwork) "在线" else "离线",
                        icon = Icons.Outlined.NetworkCheck,
                        showChevron = true,
                        onClick = {
                            onOpenDiagnostics(activeSiteId)
                            activeSheet = SettingsSheetKind.DIAGNOSTICS
                        },
                        modifier = Modifier.testTag("settings_network_diagnostics_row"),
                    )
                }
            }

            // 5. 数据与备份
            item(key = "section_data_backup") {
                RavenGroupedSection(
                    title = "数据与备份",
                    footer = "备份仅包含收藏、历史、片单、书签、订阅、屏蔽词及可迁移设置，绝不包含账号凭据或媒体文件。",
                ) {
                    RavenGroupedRow(
                        title = "加密备份与恢复",
                        subtitle = "支持 Raven 原生 (RVNBK01) 及旧版 R34V (R34VBK01) 加密备份导入",
                        icon = Icons.Outlined.Backup,
                        showChevron = true,
                        onClick = navigator::openBackupRestore,
                        modifier = Modifier.testTag("settings_backup_restore_row"),
                    )
                }
            }

            // 6. 关于渡鸦 Raven
            item(key = "section_about_raven") {
                RavenGroupedSection(
                    title = "关于渡鸦 Raven",
                    footer = "更新检查仅向 GitHub Releases 发起无凭据请求，安装前自动校验包名、版本号与签名证书链。",
                ) {
                    RavenGroupedRow(
                        title = "当前版本",
                        subtitle = uiState.appInfo.packageName,
                        value = versionReadout,
                        icon = Icons.Outlined.Info,
                        modifier = Modifier.testTag("settings_version_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "自动检查更新",
                        subtitle = "每 12 小时在正式版中静默检查 GitHub Release 新版本",
                        icon = Icons.Outlined.CloudDownload,
                        checked = uiState.settings.autoUpdateCheck,
                        onCheckedChange = onToggleAutoUpdateCheck,
                        modifier = Modifier.testTag("settings_auto_update_switch"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "检查 GitHub Release 更新",
                        subtitle = when (val state = uiState.updateState) {
                            UpdateUiState.Checking -> "正在连接 GitHub Releases 检查最新版本…"
                            is UpdateUiState.UpToDate -> "当前已是最新正式版本 (${state.latestTag})"
                            is UpdateUiState.DebugSkipped -> state.message
                            is UpdateUiState.Available -> "发现新版本 ${state.release.tagName}，点击查看详情"
                            is UpdateUiState.Error -> state.message
                            UpdateUiState.Idle -> "手动获取最新正式发布并校验签名"
                        },
                        value = if (uiState.updateState is UpdateUiState.Checking) "检查中" else null,
                        icon = Icons.Outlined.SystemUpdate,
                        iconTint = if (uiState.updateState is UpdateUiState.Available) colors.crimson else colors.inkPrimary,
                        showChevron = true,
                        enabled = uiState.updateState !is UpdateUiState.Checking,
                        onClick = onManualCheckUpdate,
                        modifier = Modifier.testTag("settings_check_update_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "开源许可与免责说明",
                        subtitle = "第三方开源组件清单及使用合规声明",
                        icon = Icons.Outlined.Description,
                        showChevron = true,
                        onClick = { activeSheet = SettingsSheetKind.LICENSES },
                        modifier = Modifier.testTag("settings_licenses_row"),
                    )
                }
            }
        }
    }

    // Quality selection bottom sheet
    when (activeSheet) {
        SettingsSheetKind.QUALITY -> {
            RavenBottomSheet(
                onDismissRequest = { activeSheet = SettingsSheetKind.NONE },
                title = "默认播放清晰度",
                subtitle = "若片源无对应分辨率，将自动回落至最接近画质",
                modifier = Modifier.testTag("settings_quality_sheet"),
            ) {
                RavenGroupedSection {
                    SettingsFormatters.QUALITY_OPTIONS.forEachIndexed { idx, (key, label) ->
                        if (idx > 0) RavenGroupedDivider()
                        val selected = uiState.settings.preferredQuality.equals(key, ignoreCase = true)
                        RavenGroupedRow(
                            title = label,
                            trailingContent = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "已选中",
                                        tint = colors.crimson,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                onSelectQuality(key)
                                activeSheet = SettingsSheetKind.NONE
                            },
                            modifier = Modifier.testTag("settings_quality_option_$key"),
                        )
                    }
                }
            }
        }

        SettingsSheetKind.DIAGNOSTICS -> {
            NetworkDiagnosticsSheet(
                state = uiState.diagnostics,
                sites = uiState.allSites,
                fallbackSiteId = activeSiteId,
                onSelectSite = { siteId -> onOpenDiagnostics(siteId) },
                onRunProbe = { siteId -> onRunLiveDiagnostics(siteId) },
                onDismiss = { activeSheet = SettingsSheetKind.NONE },
            )
        }

        SettingsSheetKind.LICENSES -> {
            LicensesAndDisclaimerSheet(
                appInfo = uiState.appInfo,
                onDismiss = { activeSheet = SettingsSheetKind.NONE },
            )
        }

        SettingsSheetKind.NONE -> Unit
    }

    // Update Available Bottom Sheet
    val availableUpdate = uiState.updateState as? UpdateUiState.Available
    if (availableUpdate != null) {
        UpdateAvailableSheet(
            state = availableUpdate,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
            onDismiss = onDismissUpdateSheet,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDiagnosticsSheet(
    state: NetworkDiagnosticsUiState,
    sites: List<SiteDescriptor>,
    fallbackSiteId: SiteId,
    onSelectSite: (SiteId) -> Unit,
    onRunProbe: (SiteId) -> Unit,
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

            SettingsPrimaryButton(
                text = if (state.isProbing) "正在执行 DNS / TLS 实测…" else "运行实时连通探针",
                icon = Icons.Rounded.Refresh,
                loading = state.isProbing,
                onClick = { onRunProbe(selectedId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_diagnostics_run_button"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateAvailableSheet(
    state: UpdateUiState.Available,
    onDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    val release = state.release
    val readyFile = state.readyApk
    val progress = state.downloadProgress

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "发现新版本 ${release.tagName}",
        subtitle = "${release.asset.fileName} · ${RavenFormatters.formatByteSize(release.asset.sizeBytes)}",
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
private fun LicensesAndDisclaimerSheet(
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
