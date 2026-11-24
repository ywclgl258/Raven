package com.raven.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenErrorState
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.storage.DuplicateCopy
import com.raven.app.core.media.storage.DuplicateGroup
import com.raven.app.core.media.storage.DuplicateSource
import com.raven.app.core.model.SiteId
import org.koin.androidx.compose.koinViewModel

@Composable
fun DuplicateScannerScreen(
    navigator: SettingsNavigator,
    modifier: Modifier = Modifier,
    viewModel: DuplicateScannerViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DuplicateScannerScreenContent(
        uiState = uiState,
        navigator = navigator,
        siteLabelProvider = viewModel::siteDisplayName,
        onRescan = viewModel::startScan,
        onCancelScan = viewModel::cancelScan,
        onToggleGroup = viewModel::toggleGroupExpanded,
        onRequestDeleteCopy = viewModel::requestDeleteCopy,
        onConfirmDelete = viewModel::confirmDeletePendingCopy,
        onDismissDelete = viewModel::dismissDeleteConfirmation,
        onDismissFeedback = viewModel::clearFeedback,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateScannerScreenContent(
    uiState: DuplicateScannerUiState,
    navigator: SettingsNavigator,
    siteLabelProvider: (SiteId) -> String,
    onRescan: () -> Unit,
    onCancelScan: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onRequestDeleteCopy: (DuplicateGroup, DuplicateCopy) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("duplicate_scanner_screen"),
    ) {
        RavenTopBar(
            title = "重复漫画检查",
            subtitle = "逐页 SHA-256 精确指纹比对",
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("duplicate_scanner_back_button"),
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
                    onClick = if (uiState.isScanning) onCancelScan else onRescan,
                    modifier = Modifier.testTag("duplicate_scanner_rescan_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = if (uiState.isScanning) "停止扫描" else "重新扫描",
                        tint = colors.inkPrimary,
                    )
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
            // Hero Summary Card
            item(key = "duplicate_hero_summary") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RavenShapes.CardShape)
                        .background(colors.surface1)
                        .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                        .padding(16.dp)
                        .testTag("duplicate_scanner_hero_card"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (uiState.isScanning) "正在扫描全部本地与已下载整本…" else "可释放重复空间",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                            )
                            Text(
                                text = RavenFormatters.formatByteSize(uiState.totalReclaimableBytes),
                                style = MaterialTheme.typography.headlineMedium.tabular(),
                                color = if (uiState.totalReclaimableBytes > 0L) colors.crimson else colors.inkPrimary,
                            )
                        }
                        RavenStatusPill(
                            text = "${uiState.groups.size} 组重复",
                            tone = if (uiState.groups.isNotEmpty()) RavenStatusTone.WARNING else RavenStatusTone.SUCCESS,
                            icon = Icons.Outlined.FileCopy,
                        )
                    }

                    if (uiState.isScanning) {
                        LinearProgressIndicator(
                            color = colors.crimson,
                            trackColor = colors.surface3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RavenStatusPill(text = "已扫描 ${uiState.scannedBooks} 本")
                        RavenStatusPill(text = "SHA-256 校验 ${uiState.hashedCandidates} 本")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (uiState.isScanning) {
                            SettingsSecondaryButton(
                                text = "停止扫描",
                                onClick = onCancelScan,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            SettingsPrimaryButton(
                                text = "重新全盘扫描",
                                icon = Icons.Rounded.Refresh,
                                onClick = onRescan,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("duplicate_scanner_start_button"),
                            )
                        }
                    }
                }
            }

            val feedback = uiState.feedbackMessage
            if (!feedback.isNullOrBlank()) {
                item(key = "duplicate_feedback_banner") {
                    SettingsNoticeBanner(
                        message = feedback,
                        tone = RavenStatusTone.SUCCESS,
                        action = {
                            TextButton(onClick = onDismissFeedback) {
                                Text("知道了", color = colors.inkPrimary)
                            }
                        },
                    )
                }
            }

            val errMsg = uiState.errorMessage
            if (!errMsg.isNullOrBlank() && uiState.report == null) {
                item(key = "duplicate_error_state") {
                    RavenErrorState(
                        title = "扫描失败",
                        message = errMsg,
                        onRetry = onRescan,
                    )
                }
            } else if (!errMsg.isNullOrBlank()) {
                item(key = "duplicate_error_banner") {
                    SettingsNoticeBanner(
                        title = "操作失败",
                        message = errMsg,
                        tone = RavenStatusTone.ERROR,
                    )
                }
            }

            if (!uiState.isScanning && uiState.report != null && uiState.groups.isEmpty()) {
                item(key = "duplicate_empty_state") {
                    RavenEmptyState(
                        title = "未发现重复漫画",
                        message = "已检查 ${uiState.scannedBooks} 本完整下载与本地导入漫画，所有作品内容指纹均唯一。",
                        icon = Icons.Outlined.CheckCircle,
                    )
                }
            }

            items(
                items = uiState.groups,
                key = { it.fingerprint },
            ) { group ->
                val expanded = group.fingerprint in uiState.expandedFingerprints
                DuplicateGroupCard(
                    group = group,
                    expanded = expanded,
                    deletingIdentityKey = uiState.deletingIdentityKey,
                    siteLabelProvider = siteLabelProvider,
                    onToggleExpanded = { onToggleGroup(group.fingerprint) },
                    onDeleteCopy = { copy -> onRequestDeleteCopy(group, copy) },
                )
            }
        }
    }

    // Safe single-page bottom sheet confirmation before removing any copy
    val pendingCopy = uiState.pendingDeleteCopy
    if (pendingCopy != null) {
        val sourceLabel = if (pendingCopy.source == DuplicateSource.LOCAL) "本地导入库" else "站点下载"
        RavenBottomSheet(
            onDismissRequest = onDismissDelete,
            title = "确认删除此副本？",
            subtitle = "${pendingCopy.title} · ${RavenFormatters.formatByteSize(pendingCopy.sizeBytes)}",
            modifier = Modifier.testTag("duplicate_delete_confirm_sheet"),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsNoticeBanner(
                    title = if (uiState.pendingDeleteIsLastCopy) "注意：这是最后一份副本" else "副本详情",
                    message = buildString {
                        append("来源：$sourceLabel（${siteLabelProvider(pendingCopy.siteId)}）")
                        append("\n页数与体积：${RavenFormatters.formatPageCount(pendingCopy.pageCount)} · ${RavenFormatters.formatByteSize(pendingCopy.sizeBytes)}")
                        if (pendingCopy.sourceRoot.isNotBlank()) {
                            append("\n所在目录：${pendingCopy.sourceRoot}")
                        }
                        if (uiState.pendingDeleteIsLastCopy) {
                            append("\n删除后将不再保留任何离线副本。")
                        } else {
                            append("\n同组其余副本将保持完好无损。")
                        }
                    },
                    tone = if (uiState.pendingDeleteIsLastCopy) RavenStatusTone.ERROR else RavenStatusTone.WARNING,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingsSecondaryButton(
                        text = "取消",
                        onClick = onDismissDelete,
                        modifier = Modifier.weight(1f),
                    )
                    SettingsPrimaryButton(
                        text = "确认删除",
                        destructive = true,
                        onClick = onConfirmDelete,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("duplicate_delete_confirm_button"),
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    expanded: Boolean,
    deletingIdentityKey: Triple<DuplicateSource, String, String>?,
    siteLabelProvider: (SiteId) -> String,
    onToggleExpanded: () -> Unit,
    onDeleteCopy: (DuplicateCopy) -> Unit,
) {
    val colors = LocalRavenColors.current
    val primaryTitle = group.copies.firstOrNull()?.title ?: "重复图集"
    val shortHash = group.fingerprint.take(12)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .testTag("duplicate_group_$shortHash"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = primaryTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RavenStatusPill(
                        text = "${group.copies.size} 份完全一致",
                        tone = RavenStatusTone.WARNING,
                    )
                    RavenStatusPill(
                        text = "可释放 ${RavenFormatters.formatByteSize(group.reclaimableBytes)}",
                        tone = RavenStatusTone.ACCENT,
                    )
                }
                Text(
                    text = "SHA-256: $shortHash…",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.inkTertiary,
                )
            }

            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起副本列表" else "展开副本列表",
                tint = colors.inkSecondary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                group.copies.forEach { copy ->
                    HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
                    val isDeleting = deletingIdentityKey == copy.identityKey
                    val sourceLabel = if (copy.source == DuplicateSource.LOCAL) "本地导入" else "站点下载"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = copy.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.inkPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RavenStatusPill(
                                    text = sourceLabel,
                                    tone = if (copy.source == DuplicateSource.LOCAL) {
                                        RavenStatusTone.NEUTRAL
                                    } else {
                                        RavenStatusTone.SUCCESS
                                    },
                                )
                                RavenStatusPill(text = siteLabelProvider(copy.siteId))
                                RavenStatusPill(
                                    text = "${RavenFormatters.formatPageCount(copy.pageCount)} · ${RavenFormatters.formatByteSize(copy.sizeBytes)}",
                                )
                            }
                            if (copy.sourceRoot.isNotBlank()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = colors.inkTertiary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = copy.sourceRoot,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.inkTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = { onDeleteCopy(copy) },
                            enabled = !isDeleting && deletingIdentityKey == null,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("duplicate_delete_copy_${copy.id}"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "删除副本 ${copy.title}",
                                tint = if (isDeleting) colors.inkTertiary else colors.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
