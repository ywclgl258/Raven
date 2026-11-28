package com.raven.app.feature.library


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenAnimatedCounter
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.ravenSquashPress
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.media.storage.LocalBook
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId

/** 本地库面板层：本地导入面板与本地书籍网格卡。 */
@Composable
internal fun LocalImportPanel(
    importState: LocalImportState,
    onImportArchive: () -> Unit,
    onImportFolder: () -> Unit,
    onImportVideo: () -> Unit,
    onOpenFromLink: () -> Unit,
    onDismissStatus: () -> Unit,
    onCancelImport: () -> Unit = {},
    onRetryImport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (importState.isImporting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.crimson,
                )
                Text(
                    text = importState.progressMessage ?: "正在导入本地内容…",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelImport) {
                    Text("取消", color = colors.inkSecondary)
                }
            }
        } else if (!importState.lastMessage.isNullOrBlank()) {
            LibraryFeedbackBanner(
                message = importState.lastMessage,
                tone = if (importState.isError) RavenStatusTone.ERROR else RavenStatusTone.SUCCESS,
                onDismiss = onDismissStatus,
                actionLabel = if (importState.canRetry) "重试导入" else null,
                onActionClick = if (importState.canRetry) onRetryImport else null,
            )
        }

        RavenGroupedSection(title = "快速导入与打开") {
            RavenGroupedRow(
                title = "导入 CBZ / ZIP 漫画包",
                subtitle = "自动自然排序并按内容去重",
                icon = Icons.Outlined.FolderZip,
                enabled = !importState.isImporting,
                showChevron = true,
                onClick = onImportArchive,
                modifier = Modifier.testTag("import_cbz_row"),
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "导入图片目录",
                subtitle = "选择包含漫画或写真图页的文件夹",
                icon = Icons.Outlined.FolderOpen,
                enabled = !importState.isImporting,
                showChevron = true,
                onClick = onImportFolder,
                modifier = Modifier.testTag("import_folder_row"),
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "导入本地视频",
                subtitle = "支持 MP4 / MKV / WebM / MOV 等常见格式",
                icon = Icons.Outlined.Movie,
                enabled = !importState.isImporting,
                showChevron = true,
                onClick = onImportVideo,
                modifier = Modifier.testTag("import_video_row"),
            )
            RavenGroupedDivider(insetWithIcon = true)
            RavenGroupedRow(
                title = "从链接打开",
                subtitle = "自动识别所有已注册站点链接",
                icon = Icons.Outlined.Link,
                showChevron = true,
                onClick = onOpenFromLink,
                modifier = Modifier.testTag("local_tab_open_link_row"),
            )
        }
    }
}

@Composable
internal fun LocalBookGridCard(
    book: LocalBook,
    coverUrl: String,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(
                width = if (selected == true) 1.5.dp else 0.5.dp,
                color = if (selected == true) colors.crimson else colors.hairline,
                shape = RavenShapes.CardShape,
            )
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("local_book_card_${book.id}"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (book.kind == MediaKind.VIDEO) 16f / 10f else 0.78f)
                .background(colors.surface2),
        ) {
            if (coverUrl.isNotBlank()) {
                RavenImage(
                    url = coverUrl,
                    siteId = SiteId.LOCAL,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (book.kind == MediaKind.VIDEO) Icons.Outlined.Movie else Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = colors.inkTertiary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RavenShapes.BadgeShape)
                    .background(colors.badgeBackdrop)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (book.kind == MediaKind.VIDEO) "视频" else "${book.files.size}P",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.inkPrimary,
                )
            }

            if (selected != null) {
                SelectCheckBadge(
                    selected = selected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(colors.badgeBackdrop)
                        .clickable(role = Role.Button, onClick = onRemoveClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多操作",
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = RavenFormatters.formatByteSize(book.bytes),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = colors.inkSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Single-screen "从链接打开" `RavenBottomSheet` powered by `SiteRegistry.resolveLinkFromText`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenFromLinkBottomSheet(
    onResolveLink: (String) -> ResolvedLinkPreview?,
    onOpenResolved: (ResolvedLinkPreview) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRavenColors.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var inputText by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val preview = remember(inputText) {
        if (inputText.isBlank()) null else onResolveLink(inputText)
    }

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = "从链接打开",
        subtitle = "粘贴任意支持站点的作品页、阅读页或标签链接",
        modifier = Modifier.testTag("open_link_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LibraryTextInputField(
                value = inputText,
                onValueChange = {
                    inputText = it
                    attempted = false
                },
                placeholder = "https://… 或包含链接的分享文本",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                imeAction = ImeAction.Go,
                onImeAction = {
                    attempted = true
                    preview?.let(onOpenResolved)
                },
                isError = attempted && preview == null,
                supportingText = when {
                    preview != null -> "已识别：${preview.siteLabel} · ${preview.kindLabel}"
                    attempted -> "未能识别该链接，请确认来自受支持的站点"
                    else -> null
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        if (pasted.isNotBlank()) {
                            inputText = pasted
                            attempted = true
                        }
                    },
                    shape = RavenShapes.PillShape,
                    border = BorderStroke(0.5.dp, colors.outlineSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surface1,
                        contentColor = colors.inkPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text("粘贴剪贴板", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        attempted = true
                        preview?.let(onOpenResolved)
                    },
                    enabled = inputText.isNotBlank(),
                    shape = RavenShapes.PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .ravenSpringPress()
                        .testTag("open_link_confirm_button"),
                ) {
                    Text(
                        text = "打开链接",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
internal fun LibraryDownloadsFavoritesCard(
    completedDownloads: Int,
    completedDownloadBytes: Long,
    activeDownloads: Int,
    favorites: Int,
    onOpenDownloads: () -> Unit,
    onOpenFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(16.dp)
            .testTag("hub_downloads_favorites_card"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.crimson.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    tint = colors.crimson,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "下载与收藏",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                )
                Text(
                    text = if (completedDownloads == 0 && activeDownloads == 0) {
                        "离线内容与收藏作品的统一入口"
                    } else {
                        buildString {
                            if (activeDownloads > 0) append("$activeDownloads 项正在下载 · ")
                            append("已完成 $completedDownloads 项")
                            append(" · 离线占用 ${RavenFormatters.formatByteSize(completedDownloadBytes)}")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.inkSecondary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DownloadsFavoritesEntryButton(
                title = "下载",
                count = completedDownloads,
                icon = Icons.Outlined.Download,
                filled = true,
                onClick = onOpenDownloads,
                modifier = Modifier
                    .weight(1f)
                    .testTag("hub_open_downloads"),
            )
            DownloadsFavoritesEntryButton(
                title = "收藏",
                count = favorites,
                icon = Icons.Outlined.FavoriteBorder,
                filled = false,
                onClick = onOpenFavorites,
                modifier = Modifier
                    .weight(1f)
                    .testTag("hub_open_favorites"),
            )
        }
    }
}

@Composable
internal fun DownloadsFavoritesEntryButton(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val container = if (filled) colors.crimson else colors.surface2
    val content = if (filled) colors.primaryCtaInk else colors.inkPrimary
    Row(
        modifier = modifier
            .heightIn(min = 62.dp)
            .clip(RavenShapes.ControlShape)
            .background(container)
            .then(
                if (filled) Modifier else Modifier.border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            )
            .ravenSquashPress()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
            RavenAnimatedCounter(
                count = count,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = content,
                formatter = { "$it 项" },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

