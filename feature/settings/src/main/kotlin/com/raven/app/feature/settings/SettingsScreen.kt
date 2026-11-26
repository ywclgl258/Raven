package com.raven.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
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
import com.raven.app.core.security.PrivacyLockStore
import java.io.File
import kotlin.math.roundToInt
import org.koin.androidx.compose.koinViewModel

private enum class SettingsSheetKind {
    NONE,
    STARTUP_DEFAULT_SITE,
    QUALITY,
    DIAGNOSTICS,
    LICENSES,
    PIN_SETUP,
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
        onToggleUiSoundEffects = viewModel::setUiSoundEffectsEnabled,
        onToggleTranslateTags = viewModel::setTranslateTags,
        onToggleAutoFullscreen = viewModel::setAutoFullscreen,
        onSelectQuality = viewModel::setPreferredQuality,
        onSelectStartupDefaultSite = viewModel::setStartupDefaultSiteId,
        onChangeDefaultSpeed = viewModel::setDefaultPlaybackSpeed,
        onToggleStreamCache = viewModel::setStreamCacheEnabled,
        onToggleSeekCache = viewModel::setSeekCacheEnabled,
        onClearReadingCache = viewModel::clearAllReadingCaches,
        onClearStreamingCache = viewModel::clearStreamingCache,
        onClearSeekCache = viewModel::clearSeekCaches,
        onClearImageCache = viewModel::clearImageCache,
        onClearAllCaches = viewModel::clearAllCaches,
        onToggleDownloadUnmeteredOnly = viewModel::setDownloadUnmeteredOnly,
        onToggleBackgroundSync = viewModel::setBackgroundSubscriptionSync,
        onToggleBlockTagsGlobally = viewModel::setBlockTagsGlobally,
        onToggleAutoplayVideo = viewModel::setAutoplayVideo,
        onToggleBackgroundAudio = viewModel::setBackgroundAudioPlayback,
        onToggleReaderTapZones = viewModel::setReaderTapZonesEnabled,
        onToggleReaderShowStatusBar = viewModel::setReaderShowStatusBar,
        onToggleReaderAutoSplit = viewModel::setReaderAutoSplitWidePages,
        onChangeReaderPrefetch = viewModel::setReaderPrefetchPages,
        onChangeReaderMemoryBudget = viewModel::setReaderMemoryBudgetMb,
        onChangeImageLoadConcurrency = viewModel::setImageLoadConcurrency,
        onToggleIncognitoMode = viewModel::setIncognitoMode,
        onTogglePrivacyScreenSecure = viewModel::setPrivacyScreenSecure,
        onToggleAppLock = viewModel::setAppLockEnabled,
        onSelectAppLockTimeout = viewModel::setAppLockTimeoutSeconds,
        onConfigureAppPin = viewModel::configureAppPin,
        onClearAppPin = viewModel::clearAppPin,
        onOpenDiagnostics = viewModel::prepareDiagnosticsSnapshot,
        onRunLiveDiagnostics = viewModel::runLiveNetworkDiagnostics,
        onResetMirrorLease = viewModel::resetMirrorLease,
        onToggleAutoUpdateCheck = viewModel::setAutoUpdateCheck,
        onManualCheckUpdate = { viewModel.checkForUpdates(manual = true) },
        onDownloadUpdate = viewModel::downloadAvailableUpdate,
        onInstallUpdate = navigator::openAppUpdateInstaller,
        onDismissUpdateSheet = viewModel::dismissUpdateSheet,
        onDismissFeedback = viewModel::clearFeedback,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreenContent(
    uiState: SettingsUiState,
    navigator: SettingsNavigator,
    onToggleReduceTransparency: (Boolean) -> Unit,
    onToggleTranslateTags: (Boolean) -> Unit,
    onToggleAutoFullscreen: (Boolean) -> Unit,
    onSelectQuality: (String) -> Unit,
    onSelectStartupDefaultSite: (SiteId?) -> Unit,
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
    onClearStreamingCache: () -> Unit = {},
    onClearSeekCache: () -> Unit = {},
    onClearImageCache: () -> Unit = {},
    onClearAllCaches: () -> Unit = {},
    onToggleBackgroundSync: (Boolean) -> Unit = {},
    onToggleBlockTagsGlobally: (Boolean) -> Unit = {},
    onToggleAutoplayVideo: (Boolean) -> Unit = {},
    onToggleBackgroundAudio: (Boolean) -> Unit = {},
    onToggleReaderTapZones: (Boolean) -> Unit = {},
    onToggleReaderShowStatusBar: (Boolean) -> Unit = {},
    onToggleReaderAutoSplit: (Boolean) -> Unit = {},
    onChangeReaderPrefetch: (Int) -> Unit = {},
    onChangeReaderMemoryBudget: (Int) -> Unit = {},
    onToggleIncognitoMode: (Boolean) -> Unit = {},
    onTogglePrivacyScreenSecure: (Boolean) -> Unit = {},
    onToggleAppLock: (Boolean) -> Unit = {},
    onSelectAppLockTimeout: (Int) -> Unit = {},
    onConfigureAppPin: (String) -> Boolean = { false },
    onClearAppPin: () -> Unit = {},
    onResetMirrorLease: (SiteId) -> Unit = {},
    onToggleUiSoundEffects: (Boolean) -> Unit = {},
    onChangeImageLoadConcurrency: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    var activeSheet by rememberSaveable { mutableStateOf(SettingsSheetKind.NONE) }
    var accountsExpanded by rememberSaveable { mutableStateOf(false) }

    var sliderSpeed by remember(uiState.settings.defaultPlaybackSpeed) {
        mutableFloatStateOf(uiState.settings.defaultPlaybackSpeed)
    }
    var sliderPrefetch by remember(uiState.settings.readerPrefetchPages) {
        mutableIntStateOf(uiState.settings.readerPrefetchPages)
    }
    var sliderMemoryMb by remember(uiState.settings.readerMemoryBudgetMb) {
        mutableIntStateOf(uiState.settings.readerMemoryBudgetMb)
    }
    var sliderConcurrency by remember(uiState.settings.imageLoadConcurrency) {
        mutableIntStateOf(uiState.settings.imageLoadConcurrency)
    }

    val activeSite = uiState.activeSite
    val activeSiteId = activeSite?.id ?: uiState.settings.activeSiteId
    val startupDefaultSiteLabel = uiState.settings.startupDefaultSiteId?.let { id ->
        uiState.allSites.firstOrNull { it.id == id }?.displayName ?: id.value
    } ?: "记住上次使用"
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
        if (uiState.appInfo.isDebug && !uiState.appInfo.versionName.contains("debug", ignoreCase = true)) {
            append(" · Debug")
        }
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
                        title = "交互短音效",
                        subtitle = "点击按钮、切换标签与收藏时播放轻柔短音效（静音/震动模式自动关闭）",
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        checked = uiState.settings.uiSoundEffectsEnabled,
                        onCheckedChange = onToggleUiSoundEffects,
                        modifier = Modifier.testTag("settings_ui_sound_effects"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "中文标签词典翻译",
                        subtitle = "结合内置、EhTagTranslation 社区与自定义译名中文化显示标签与搜索联想",
                        icon = Icons.Outlined.Translate,
                        checked = uiState.settings.translateTags,
                        onCheckedChange = onToggleTranslateTags,
                        modifier = Modifier.testTag("settings_translate_tags"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    val dictStats = uiState.tagDictionaryStats
                    val totalDictCount = dictStats.builtInCount + dictStats.communityCount + dictStats.customCount
                    RavenGroupedRow(
                        title = "标签中文词典",
                        subtitle = "内置 ${dictStats.builtInCount} · 社区 ${dictStats.communityCount} · 自定义 ${dictStats.customCount}\n同步 40,000+ 社区中文词库并管理自定义标签译名",
                        value = "$totalDictCount 条",
                        icon = Icons.Outlined.AutoStories,
                        showChevron = true,
                        onClick = navigator::openTagDictionary,
                        modifier = Modifier.testTag("settings_tag_dictionary_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "视频自动全屏",
                        subtitle = "仅手动点击全屏或横屏时进入全屏（自动播放绝不自动全屏）",
                        icon = Icons.Outlined.Fullscreen,
                        checked = uiState.settings.autoFullscreen,
                        onCheckedChange = onToggleAutoFullscreen,
                        modifier = Modifier.testTag("settings_auto_fullscreen"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "自动播放详情页视频",
                        subtitle = "进入播放器详情时自动开始播放（保持小窗内嵌不自动全屏）",
                        icon = Icons.Outlined.PlayCircle,
                        checked = uiState.settings.autoplayVideo,
                        onCheckedChange = onToggleAutoplayVideo,
                        modifier = Modifier.testTag("settings_autoplay_video"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "后台音频继续播放",
                        subtitle = "切出播放器或锁屏时保持音频轨道在后台继续播放",
                        icon = Icons.Outlined.Headphones,
                        checked = uiState.settings.backgroundAudioPlayback,
                        onCheckedChange = onToggleBackgroundAudio,
                        modifier = Modifier.testTag("settings_background_audio"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "阅读器三段点击翻页",
                        subtitle = "左右边缘点击翻页，中间点击显隐阅读 HUD 工具栏",
                        icon = Icons.Outlined.TouchApp,
                        checked = uiState.settings.readerTapZonesEnabled,
                        onCheckedChange = onToggleReaderTapZones,
                        modifier = Modifier.testTag("settings_reader_tap_zones"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "阅读器底部状态栏进度",
                        subtitle = "沉浸隐藏 HUD 时保留精简页码与时间指示",
                        icon = Icons.AutoMirrored.Outlined.ChromeReaderMode,
                        checked = uiState.settings.readerShowStatusBar,
                        onCheckedChange = onToggleReaderShowStatusBar,
                        modifier = Modifier.testTag("settings_reader_status_bar"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "双页宽图自动分割",
                        subtitle = "纵屏阅读日漫跨页图时按阅读方向自动拆分为两页",
                        icon = Icons.Outlined.AutoStories,
                        checked = uiState.settings.readerAutoSplitWidePages,
                        onCheckedChange = onToggleReaderAutoSplit,
                        modifier = Modifier.testTag("settings_reader_auto_split"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSliderRow(
                        title = "阅读预加载页数",
                        subtitle = "提前缓冲后续页面以减少翻页白屏等待",
                        icon = Icons.Outlined.CloudDownload,
                        value = sliderPrefetch.toFloat(),
                        valueRange = 1f..6f,
                        steps = 4,
                        valueLabel = "$sliderPrefetch 页",
                        onValueChange = { sliderPrefetch = it.roundToInt().coerceIn(1, 6) },
                        onValueChangeFinished = { onChangeReaderPrefetch(sliderPrefetch) },
                        modifier = Modifier.testTag("settings_reader_prefetch_slider"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSliderRow(
                        title = "阅读内存解码上限",
                        subtitle = "超出当前锚点范围与上限的图片缓存将自动回收",
                        icon = Icons.Outlined.Memory,
                        value = sliderMemoryMb.toFloat(),
                        valueRange = 16f..96f,
                        steps = 4,
                        valueLabel = "$sliderMemoryMb MiB",
                        onValueChange = { sliderMemoryMb = it.roundToInt().coerceIn(16, 96) },
                        onValueChangeFinished = { onChangeReaderMemoryBudget(sliderMemoryMb) },
                        modifier = Modifier.testTag("settings_reader_memory_slider"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSliderRow(
                        title = "图集并发加载与下载线程",
                        subtitle = "建议 2–3 线程，自动按站点限流策略与浏览器节拍错峰请求",
                        icon = Icons.Outlined.CloudDownload,
                        value = sliderConcurrency.toFloat(),
                        valueRange = 2f..3f,
                        steps = 0,
                        valueLabel = "$sliderConcurrency 线程",
                        onValueChange = { sliderConcurrency = it.roundToInt().coerceIn(2, 3) },
                        onValueChangeFinished = { onChangeImageLoadConcurrency(sliderConcurrency) },
                        modifier = Modifier.testTag("settings_image_concurrency_slider"),
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
                                        text = "清理",
                                        color = colors.crimson,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("settings_reading_cache_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "流媒体与 Seek 缓存",
                        subtitle = "视频分段 + 快进回看缓存，可随时安全释放",
                        value = RavenFormatters.formatByteSize(uiState.mediaCache.streamBytes + uiState.mediaCache.seekBytes),
                        icon = Icons.Outlined.Cached,
                        enabled = !uiState.mediaCache.isClearing,
                        trailingContent = {
                            if (uiState.mediaCache.streamBytes + uiState.mediaCache.seekBytes > 0L) {
                                TextButton(
                                    onClick = {
                                        onClearStreamingCache()
                                        onClearSeekCache()
                                    },
                                    enabled = !uiState.mediaCache.isClearing,
                                    modifier = Modifier.testTag("settings_clear_media_cache"),
                                ) {
                                    Text("清理", color = colors.crimson, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        },
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "封面与缩略图缓存",
                        subtitle = "Coil 站点分区磁盘图片缓存（最高 64 MiB）",
                        value = RavenFormatters.formatByteSize(uiState.mediaCache.imageBytes),
                        icon = Icons.Outlined.CleaningServices,
                        enabled = !uiState.mediaCache.isClearing,
                        trailingContent = {
                            if (uiState.mediaCache.imageBytes > 0L) {
                                TextButton(
                                    onClick = onClearImageCache,
                                    enabled = !uiState.mediaCache.isClearing,
                                    modifier = Modifier.testTag("settings_clear_image_cache"),
                                ) {
                                    Text("清理", color = colors.crimson, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        },
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "统一缓存仪表盘",
                        subtitle = "全部临时缓存合计（绝不删除离线片库或收藏数据）",
                        value = RavenFormatters.formatByteSize(uiState.totalCacheBytes),
                        icon = Icons.Outlined.DeleteSweep,
                        enabled = !uiState.readingCache.isClearing && !uiState.mediaCache.isClearing,
                        trailingContent = {
                            if (uiState.totalCacheBytes > 0L) {
                                TextButton(
                                    onClick = onClearAllCaches,
                                    enabled = !uiState.readingCache.isClearing && !uiState.mediaCache.isClearing,
                                    modifier = Modifier.testTag("settings_clear_all_caches"),
                                ) {
                                    Text("一键全部清理", color = colors.crimson, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        },
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
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "后台检查订阅更新",
                        subtitle = "每 12 小时通过 WorkManager 低功耗拉取已订阅标签与作者上新",
                        icon = Icons.Outlined.Sync,
                        checked = uiState.settings.backgroundSubscriptionSync,
                        onCheckedChange = onToggleBackgroundSync,
                        modifier = Modifier.testTag("settings_background_sync_switch"),
                    )
                }
            }

            // 4. 站点与安全
            item(key = "section_sites_security") {
                RavenGroupedSection(
                    title = "站点与安全",
                    footer = "所有站点 Cookie 与 Bearer 令牌统一经 AndroidKeyStore AES-256-GCM 加密隔离，绝不发往媒体 CDN 或更新服务器。",
                ) {
                    RavenGroupedRow(
                        title = "启动默认站点",
                        subtitle = startupDefaultSiteLabel,
                        icon = Icons.Outlined.Home,
                        showChevron = true,
                        onClick = { activeSheet = SettingsSheetKind.STARTUP_DEFAULT_SITE },
                        modifier = Modifier.testTag("settings_startup_default_site_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "当前站点快捷偏好",
                        subtitle = activeSite?.let { "管理 ${it.displayName} 的语言、阅读模式与本站内容" }
                            ?: "管理当前站点语言、阅读模式与本站内容",
                        value = activeSite?.shortName,
                        icon = Icons.Outlined.Tune,
                        showChevron = true,
                        onClick = { navigator.openSiteFunctions(activeSiteId) },
                        modifier = Modifier.testTag("settings_site_preferences_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)

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
                    RavenGroupedSwitchRow(
                        title = "全局启用屏蔽词过滤",
                        subtitle = "关闭后可临时查看全部条目，保留规则列表不丢失",
                        icon = Icons.Outlined.VisibilityOff,
                        checked = uiState.settings.blockTagsGlobally,
                        onCheckedChange = onToggleBlockTagsGlobally,
                        modifier = Modifier.testTag("settings_block_tags_globally_switch"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "网络诊断与镜像状态",
                        subtitle = "查看系统代理、路由纪元 #${uiState.routeEpoch}、站点镜像租约与 DNS/TLS 实测",
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

            // 4.5 隐私与应用锁
            item(key = "section_privacy_app_lock") {
                RavenGroupedSection(
                    title = "隐私与应用锁",
                    footer = "无痕模式会停止写入新的搜索、观影与阅读历史；PIN 锁使用加盐 PBKDF2 + AndroidKeyStore 加密校验，不纳入导出备份。",
                ) {
                    RavenGroupedSwitchRow(
                        title = "无痕模式",
                        subtitle = "暂停写入新历史记录与搜索联想痕迹",
                        icon = Icons.Outlined.VisibilityOff,
                        checked = uiState.settings.incognitoMode,
                        onCheckedChange = onToggleIncognitoMode,
                        modifier = Modifier.testTag("settings_incognito_switch"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "全局防截屏保护 (FLAG_SECURE)",
                        subtitle = "禁止系统截屏、录屏及多任务最近列表缩略图预览",
                        icon = Icons.Outlined.PrivacyTip,
                        checked = uiState.settings.privacyScreenSecure,
                        onCheckedChange = onTogglePrivacyScreenSecure,
                        modifier = Modifier.testTag("settings_screen_secure_switch"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "应用启动 PIN 锁",
                        subtitle = if (uiState.hasAppPin) "已配置 4–8 位数字解锁码（支持生物识别）" else "需先点击下方设置解锁 PIN",
                        icon = Icons.Outlined.Lock,
                        checked = uiState.settings.appLockEnabled,
                        onCheckedChange = onToggleAppLock,
                        modifier = Modifier.testTag("settings_app_lock_switch"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = if (uiState.hasAppPin) "修改或清除解锁 PIN" else "设置应用锁 PIN",
                        subtitle = if (uiState.hasAppPin) "修改数字解锁码或清除并关闭应用锁" else "设置 4 至 8 位数字密码保护本地片库与浏览记录",
                        value = if (uiState.hasAppPin) "已配置" else "未设置",
                        icon = Icons.Outlined.Security,
                        showChevron = true,
                        onClick = { activeSheet = SettingsSheetKind.PIN_SETUP },
                        modifier = Modifier.testTag("settings_pin_setup_row"),
                    )
                    if (uiState.hasAppPin) {
                        RavenGroupedDivider(insetWithIcon = true)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "后台自动锁定超时",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.inkPrimary,
                            )
                            val timeoutChoices = listOf(0 to "立即", 30 to "30 秒", 60 to "1 分钟", 300 to "5 分钟")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                timeoutChoices.forEach { (sec, label) ->
                                    RavenFilterChip(
                                        label = label,
                                        selected = uiState.settings.appLockTimeoutSeconds == sec,
                                        onClick = { onSelectAppLockTimeout(sec) },
                                    )
                                }
                            }
                        }
                    }
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
                        subtitleMaxLines = 1,
                        value = versionReadout,
                        icon = Icons.Outlined.Info,
                        modifier = Modifier.testTag("settings_version_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedSwitchRow(
                        title = "自动检查更新",
                        subtitle = "启动时检查 GitHub Release；距上次不足 12 小时则跳过，避免重复请求",
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

    // Startup default and quality selection bottom sheets
    when (activeSheet) {
        SettingsSheetKind.STARTUP_DEFAULT_SITE -> {
            RavenBottomSheet(
                onDismissRequest = { activeSheet = SettingsSheetKind.NONE },
                detent = com.raven.app.core.designsystem.component.RavenSheetDetent.Large,
                title = "启动默认站点",
                subtitle = "每次冷启动进入首页时应用，不会在返回首页或配置变化时重置当前站点",
                modifier = Modifier.testTag("settings_startup_default_site_sheet"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    RavenGroupedSection {
                        val rememberLastUsed = uiState.settings.startupDefaultSiteId == null
                        RavenGroupedRow(
                            title = "记住上次使用",
                            subtitle = "沿用退出前最后使用的站点",
                            trailingContent = if (rememberLastUsed) {
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
                                onSelectStartupDefaultSite(null)
                                activeSheet = SettingsSheetKind.NONE
                            },
                            modifier = Modifier.testTag("settings_startup_default_site_remember"),
                        )
                        uiState.allSites.forEach { descriptor ->
                            RavenGroupedDivider()
                            val selected = uiState.settings.startupDefaultSiteId == descriptor.id
                            RavenGroupedRow(
                                title = descriptor.displayName,
                                subtitle = descriptor.defaultHost,
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
                                    onSelectStartupDefaultSite(descriptor.id)
                                    activeSheet = SettingsSheetKind.NONE
                                },
                                modifier = Modifier.testTag(
                                    "settings_startup_default_site_${descriptor.id.value}",
                                ),
                            )
                        }
                    }
                }
            }
        }

        SettingsSheetKind.QUALITY -> {
            RavenBottomSheet(
                onDismissRequest = { activeSheet = SettingsSheetKind.NONE },
                detent = com.raven.app.core.designsystem.component.RavenSheetDetent.Medium,
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
                onResetMirrorLease = onResetMirrorLease,
                onDismiss = { activeSheet = SettingsSheetKind.NONE },
            )
        }

        SettingsSheetKind.LICENSES -> {
            LicensesAndDisclaimerSheet(
                appInfo = uiState.appInfo,
                onDismiss = { activeSheet = SettingsSheetKind.NONE },
            )
        }

        SettingsSheetKind.PIN_SETUP -> {
            PrivacyPinSetupSheet(
                hasExistingPin = uiState.hasAppPin,
                onSavePin = { pin ->
                    if (onConfigureAppPin(pin)) {
                        activeSheet = SettingsSheetKind.NONE
                    }
                },
                onClearPin = {
                    onClearAppPin()
                    activeSheet = SettingsSheetKind.NONE
                },
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
private fun PrivacyPinSetupSheet(
    hasExistingPin: Boolean,
    onSavePin: (String) -> Unit,
    onClearPin: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pinInput by rememberSaveable { mutableStateOf("") }
    var pinConfirm by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    SecureWindowEffect(enabled = true)

    RavenBottomSheet(
        onDismissRequest = onDismiss,
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
                        onClick = onClearPin,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SettingsSecondaryButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
                SettingsPrimaryButton(
                    text = "保存并启用",
                    onClick = {
                        when {
                            !PrivacyLockStore.isValidPinFormat(pinInput) ->
                                localError = "PIN 必须为 4 到 8 位纯数字"
                            pinInput != pinConfirm ->
                                localError = "两次输入的 PIN 不一致"
                            else -> onSavePin(pinInput)
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
        presentation = com.raven.app.core.designsystem.component.RavenSheetPresentation.Center,
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
