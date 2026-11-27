package com.raven.app.feature.settings

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.StorageLocation
import org.koin.androidx.compose.koinViewModel

@Composable
fun StorageMigrationScreen(
    navigator: SettingsNavigator,
    modifier: Modifier = Modifier,
    viewModel: StorageMigrationViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshStatus()
    }

    StorageMigrationScreenContent(
        uiState = uiState,
        navigator = navigator,
        onRefresh = viewModel::refreshStatus,
        onGrantPermission = {
            val intent = viewModel.permissionIntent()
            if (intent != null) {
                runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        },
        onStartMigration = viewModel::startMigrationToShared,
        onCancelMigration = viewModel::cancelMigration,
        onSwitchBackToPrivate = viewModel::requestSwitchToPrivateOnNextRestart,
        modifier = modifier,
    )
}

@Composable
fun StorageMigrationScreenContent(
    uiState: StorageMigrationUiState,
    navigator: SettingsNavigator,
    onRefresh: () -> Unit,
    onGrantPermission: () -> Unit,
    onStartMigration: () -> Unit,
    onCancelMigration: () -> Unit,
    onSwitchBackToPrivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val status = uiState.status
    val activeLocation = status?.activeLocation ?: StorageLocation.PRIVATE
    val pendingLocation = status?.pendingLocation

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("storage_migration_screen"),
    ) {
        RavenTopBar(
            title = "存储位置管理",
            subtitle = "私有沙盒与公共目录 SHA-256 校验迁移",
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("storage_migration_back_button"),
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
                    onClick = onRefresh,
                    enabled = !uiState.isMigrating,
                    modifier = Modifier.testTag("storage_migration_refresh_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新存储状态",
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
            // Hero Status Summary
            item(key = "storage_hero_card") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RavenShapes.CardShape)
                        .background(colors.surface1)
                        .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (activeLocation == StorageLocation.SHARED) "当前：公共共享目录" else "当前：应用内私有目录",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.inkPrimary,
                        )
                        RavenStatusPill(
                            text = if (activeLocation == StorageLocation.SHARED) "SHARED" else "PRIVATE",
                            tone = if (activeLocation == StorageLocation.SHARED) {
                                RavenStatusTone.SUCCESS
                            } else {
                                RavenStatusTone.ACCENT
                            },
                        )
                    }

                    Text(
                        text = status?.activePath ?: uiState.privatePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RavenStatusPill(
                            text = when {
                                !uiState.sharedSupported -> "无外部存储卷"
                                status == null || !status.sharedPermissionGranted -> "未授予所有文件权限"
                                status.sharedWritable -> "公共目录可读写"
                                else -> "公共目录暂不可写"
                            },
                            tone = when {
                                !uiState.sharedSupported -> RavenStatusTone.NEUTRAL
                                status == null || !status.sharedPermissionGranted -> RavenStatusTone.WARNING
                                status.sharedWritable -> RavenStatusTone.SUCCESS
                                else -> RavenStatusTone.ERROR
                            },
                        )
                        if (pendingLocation != null) {
                            RavenStatusPill(
                                text = "待重启切换 -> ${if (pendingLocation == StorageLocation.SHARED) "公共目录" else "应用内"}",
                                tone = RavenStatusTone.WARNING,
                            )
                        }
                    }
                }
            }

            // Deferred cold-start switch notice
            if (pendingLocation != null) {
                item(key = "storage_pending_notice") {
                    SettingsNoticeBanner(
                        title = "已预约冷启动切换",
                        message = "目标存储位置（${if (pendingLocation == StorageLocation.SHARED) "公共目录" else "应用内存储"}）将在下次完全重启渡鸦 Raven 时自动生效。在重启前将暂时暂停新下载以保证索引一致。",
                        icon = Icons.Outlined.RestartAlt,
                        tone = RavenStatusTone.WARNING,
                        modifier = Modifier.testTag("storage_pending_switch_notice"),
                    )
                }
            }

            val note = status?.note
            if (!note.isNullOrBlank() && pendingLocation == null) {
                item(key = "storage_status_note") {
                    SettingsNoticeBanner(
                        message = note,
                        tone = RavenStatusTone.WARNING,
                    )
                }
            }

            // Paths & Permissions Section
            item(key = "storage_paths_section") {
                RavenGroupedSection(
                    title = "存储目录对比",
                    footer = "公共目录自动写入 .nomedia 防止系统相册扫描，且卸载重装后可自动扫描恢复已下载作品。",
                ) {
                    RavenGroupedRow(
                        title = "应用内私有目录 (PRIVATE)",
                        subtitle = uiState.privatePath.ifBlank { "应用沙盒 filesDir" },
                        icon = Icons.Outlined.Folder,
                        trailingContent = if (activeLocation == StorageLocation.PRIVATE) {
                            { RavenStatusPill(text = "使用中", tone = RavenStatusTone.ACCENT) }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag("storage_private_path_row"),
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "公共共享目录 (SHARED)",
                        subtitle = status?.sharedPath ?: "设备未暴露外部共享目录",
                        icon = Icons.Outlined.FolderShared,
                        trailingContent = when {
                            activeLocation == StorageLocation.SHARED -> {
                                { RavenStatusPill(text = "使用中", tone = RavenStatusTone.SUCCESS) }
                            }
                            status?.sharedPermissionGranted == true -> {
                                { RavenStatusPill(text = "已授权", tone = RavenStatusTone.SUCCESS) }
                            }
                            else -> {
                                { RavenStatusPill(text = "未授权", tone = RavenStatusTone.WARNING) }
                            }
                        },
                        modifier = Modifier.testTag("storage_shared_path_row"),
                    )
                }
            }

            // Permission CTA Card
            if (uiState.canGrantSharedPermission) {
                item(key = "storage_permission_cta") {
                    RavenGroupedSection(
                        title = "第一步：授予外部存储权限",
                        footer = "Android 11+ 需要“所有文件访问权限”才能在 /storage/emulated/0/Raven 读写离线视频与漫画目录。",
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "请前往系统设置授予渡鸦 Raven 访问外部存储权限，授权返回后页面将自动刷新状态。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                            )
                            SettingsPrimaryButton(
                                text = "前往系统设置授权",
                                icon = Icons.Outlined.AdminPanelSettings,
                                onClick = onGrantPermission,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("storage_grant_permission_button"),
                            )
                        }
                    }
                }
            }

            // Active Migration Progress Card
            val progress = uiState.progress
            if (uiState.isMigrating && progress != null) {
                item(key = "storage_migration_progress") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RavenShapes.CardShape)
                            .background(colors.surface1)
                            .border(0.5.dp, colors.crimson.copy(alpha = 0.45f), RavenShapes.CardShape)
                            .padding(16.dp)
                            .testTag("storage_migration_progress_card"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "正在执行 SHA-256 校验迁移…",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.inkPrimary,
                            )
                            Text(
                                text = if (progress.totalBytes > 0L) {
                                    "${RavenFormatters.formatByteSize(progress.doneBytes)} / ${RavenFormatters.formatByteSize(progress.totalBytes)}"
                                } else {
                                    "准备文件清单…"
                                },
                                style = MaterialTheme.typography.labelMedium.tabular(),
                                color = colors.inkSecondary,
                            )
                        }

                        if (progress.totalBytes > 0L) {
                            val motionReduced = LocalMotionReduced.current
                            val animatedFraction by animateFloatAsState(
                                targetValue = progress.fraction.coerceIn(0f, 1f),
                                animationSpec = if (motionReduced) {
                                    snap()
                                } else {
                                    tween(durationMillis = 180, easing = RavenMotion.HarmonyDecelerationEasing)
                                },
                                label = "storageMigrationProgress",
                            )
                            LinearProgressIndicator(
                                progress = { animatedFraction },
                                color = colors.crimson,
                                trackColor = colors.surface3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(
                                color = colors.crimson,
                                trackColor = colors.surface3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (progress.currentFileName.isNotBlank()) {
                            Text(
                                text = "当前文件：${progress.currentFileName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkTertiary,
                            )
                        }

                        SettingsSecondaryButton(
                            text = "取消迁移",
                            destructive = true,
                            onClick = onCancelMigration,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Error / Info / Result Report
            val errMsg = uiState.errorMessage
            if (!errMsg.isNullOrBlank()) {
                item(key = "storage_error_banner") {
                    SettingsNoticeBanner(
                        title = "迁移未完成",
                        message = errMsg,
                        tone = RavenStatusTone.ERROR,
                    )
                }
            }

            val infoMsg = uiState.infoMessage
            if (!infoMsg.isNullOrBlank()) {
                item(key = "storage_info_banner") {
                    SettingsNoticeBanner(
                        title = "存储状态提示",
                        message = infoMsg,
                        tone = RavenStatusTone.SUCCESS,
                    )
                }
            }

            val result = uiState.lastResult
            if (result != null) {
                item(key = "storage_result_report") {
                    RavenGroupedSection(
                        title = "迁移校验报告",
                        footer = result.summary,
                    ) {
                        RavenGroupedRow(
                            title = if (result.videoCachesVerified) {
                                "已复制并通过 SHA-256 校验"
                            } else {
                                "已复制并通过 SHA-256 校验（图集与索引）"
                            },
                            value = "${result.copiedFiles} 个文件",
                            icon = Icons.Outlined.Verified,
                            iconTint = colors.success,
                        )
                        RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedRow(
                            title = "已跳过一致副本",
                            value = "${result.skippedFiles} 个文件",
                        )
                        RavenGroupedDivider()
                        RavenGroupedRow(
                            title = "校验总数据量",
                            value = RavenFormatters.formatByteSize(result.totalBytes),
                        )
                    }
                }
            }

            // Primary Action Section
            item(key = "storage_actions_section") {
                RavenGroupedSection(
                    title = "迁移与切换操作",
                    footer = "迁移过程会自动暂停活跃下载，逐文件计算 SHA-256 校验和；完成后原应用内文件保持不动，重启应用即生效。",
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (activeLocation == StorageLocation.PRIVATE) {
                            SettingsPrimaryButton(
                                text = if (uiState.isMigrating) "迁移进行中…" else "校验并迁移到公共目录",
                                icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                                loading = uiState.isMigrating,
                                enabled = uiState.canStartMigrationToShared,
                                onClick = onStartMigration,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("storage_start_migration_button"),
                            )
                        } else {
                            SettingsSecondaryButton(
                                text = "切回应用内私有存储（下次重启生效）",
                                icon = Icons.Outlined.RestartAlt,
                                enabled = uiState.canScheduleSwitchToPrivate,
                                onClick = onSwitchBackToPrivate,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("storage_switch_private_button"),
                            )
                        }
                    }
                }
            }
        }
    }
}
