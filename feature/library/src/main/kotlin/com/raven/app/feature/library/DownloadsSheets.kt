package com.raven.app.feature.library


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind

/** 下载页 Sheet 层：条目操作、分组编辑、图集完整性审计与已下载条目打开逻辑。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadItemActionSheet(
    item: DownloadItem,
    siteLabel: String,
    onOpenOffline: (() -> Unit)?,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onPrioritize: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onEditGroup: () -> Unit,
    onInspectIntegrity: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = item.title,
        subtitle = "$siteLabel · ${item.status.displayLabel()}",
        modifier = Modifier.testTag("download_item_sheet"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RavenGroupedSection {
                var addedRow = false
                if (onOpenOffline != null) {
                    addedRow = true
                    RavenGroupedRow(
                        title = "离线打开",
                        icon = Icons.Outlined.OfflinePin,
                        showChevron = true,
                        onClick = {
                            onDismiss()
                            onOpenOffline()
                        },
                    )
                }
                if (onPause != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = "暂停下载",
                        icon = Icons.Outlined.Pause,
                        onClick = {
                            onDismiss()
                            onPause()
                        },
                    )
                }
                if (onResume != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = "继续下载",
                        icon = Icons.Outlined.PlayArrow,
                        onClick = {
                            onDismiss()
                            onResume()
                        },
                    )
                }
                if (onRetry != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = "立即重试",
                        icon = Icons.Outlined.Refresh,
                        onClick = {
                            onDismiss()
                            onRetry()
                        },
                    )
                }
                if (onPrioritize != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = "置顶优先下载",
                        icon = Icons.Outlined.VerticalAlignTop,
                        onClick = {
                            onDismiss()
                            onPrioritize()
                        },
                    )
                }
                if (onMoveUp != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = if (item.kind == DownloadKind.VIDEO) "上移一位" else "提高队列优先级",
                        icon = Icons.Outlined.KeyboardArrowUp,
                        onClick = {
                            onDismiss()
                            onMoveUp()
                        },
                    )
                }
                if (onMoveDown != null) {
                    if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                    addedRow = true
                    RavenGroupedRow(
                        title = if (item.kind == DownloadKind.VIDEO) "下移一位" else "降低队列优先级",
                        icon = Icons.Outlined.KeyboardArrowDown,
                        onClick = {
                            onDismiss()
                            onMoveDown()
                        },
                    )
                }
                if (addedRow) RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "设置分组",
                    value = item.groupName.ifBlank { "未分组" },
                    icon = Icons.Outlined.Folder,
                    showChevron = true,
                    onClick = {
                        onDismiss()
                        onEditGroup()
                    },
                )
                if (onInspectIntegrity != null) {
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "检查完整性与修复缺页",
                        subtitle = "逐页校验已下载图片是否缺失或损坏",
                        icon = Icons.Outlined.Verified,
                        showChevron = true,
                        onClick = {
                            onDismiss()
                            onInspectIntegrity()
                        },
                        modifier = Modifier.testTag("download_inspect_integrity_row"),
                    )
                }
            }

            RavenGroupedSection {
                RavenGroupedRow(
                    title = "删除下载与缓存文件",
                    icon = Icons.Outlined.DeleteOutline,
                    destructive = true,
                    onClick = {
                        onDismiss()
                        onDelete()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DownloadGroupEditorSheet(
    item: DownloadItem,
    existingGroups: List<String>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    var groupName by rememberSaveable(item.id) { mutableStateOf(item.groupName) }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "设置下载分组",
        subtitle = item.title,
        modifier = Modifier.testTag("download_group_sheet"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryTextInputField(
                value = groupName,
                onValueChange = { groupName = it.take(40) },
                placeholder = "留空则清除分组，例如：长途离线、合集",
            )
            if (existingGroups.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    existingGroups.forEach { existing ->
                        RavenFilterChip(
                            label = existing,
                            selected = groupName.trim() == existing,
                            onClick = { groupName = existing },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("取消", color = colors.inkPrimary)
                }
                Button(
                    onClick = {
                        onSave(groupName.trim())
                        onDismiss()
                    },
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("保存分组", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryIntegrityAuditSheet(
    state: IntegrityAuditDialogState,
    onRepair: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "完整性检查与修复",
        subtitle = state.item.title,
        modifier = Modifier.testTag("download_integrity_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state.isChecking -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = colors.crimson,
                        )
                        Text(
                            text = "正在逐页校验已下载文件…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                        )
                    }
                }

                !state.errorMessage.isNullOrBlank() -> {
                    LibraryFeedbackBanner(
                        message = state.errorMessage,
                        tone = RavenStatusTone.ERROR,
                    )
                }

                state.audit != null -> {
                    val audit = state.audit
                    RavenGroupedSection(title = "校验报告") {
                        LibraryMorphGroupedRow(
                            title = "整体状态",
                            value = if (audit.isComplete) "完整无损" else "发现缺页或损坏",
                            glyph = if (audit.isComplete) RavenMorphIcons.Verified else RavenMorphIcons.Build,
                            iconTint = if (audit.isComplete) colors.success else colors.warning,
                        )
                        RavenGroupedDivider(insetWithIcon = true)
                        RavenGroupedRow(title = "总页数", value = "${audit.totalPages} 页")
                        RavenGroupedDivider()
                        RavenGroupedRow(title = "完好页", value = "${audit.validPages.size} 页")
                        RavenGroupedDivider()
                        RavenGroupedRow(title = "缺失页", value = "${audit.missingPages.size} 页")
                        RavenGroupedDivider()
                        RavenGroupedRow(title = "损坏页", value = "${audit.corruptPages.size} 页")
                        RavenGroupedDivider()
                        RavenGroupedRow(title = "已占空间", value = RavenFormatters.formatByteSize(audit.totalBytes))
                    }

                    if (!audit.isComplete) {
                        Button(
                            onClick = onRepair,
                            enabled = !state.isRepairing,
                            shape = RavenShapes.PillShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primaryCtaContainer,
                                contentColor = colors.primaryCtaInk,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .ravenSpringPress()
                                .testTag("download_repair_button"),
                        ) {
                            Text(
                                text = if (state.isRepairing) {
                                    "正在提交修复…"
                                } else {
                                    "修复缺失与损坏页 (${audit.missingPages.size + audit.corruptPages.size} 页)"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun openDownloadedItem(
    navigator: LibraryNavigator,
    item: DownloadItem,
) {
    if (item.kind == DownloadKind.VIDEO) {
        navigator.openVideo(
            siteId = item.siteId,
            mediaId = item.mediaId,
            url = item.url,
            title = item.title,
            coverUrl = item.coverUrl,
            offlineOnly = true,
        )
    } else {
        navigator.openReader(
            siteId = item.siteId,
            mediaId = item.mediaId,
            url = item.url,
            startPage = 1,
            offlineOnly = true,
        )
    }
}

