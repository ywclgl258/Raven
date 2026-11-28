package com.raven.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenPrimaryButton
import com.raven.app.core.designsystem.component.RavenPrimaryButtonVariant
import com.raven.app.core.designsystem.component.RavenSecondaryButton
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.sitespi.ResolvedLink

/** 分享链接确认底部表单：从 `RavenNavHost.kt` 拆出的独立 Sheet。 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedLinkConfirmationSheet(
    prompt: SharedLinkPrompt,
    onDismissRequest: () -> Unit,
    onConfirmOpen: (ResolvedLink) -> Unit,
) {
    val colors = LocalRavenColors.current
    val resolved = prompt.resolved
    val plugin = prompt.plugin

    val kindLabel = when (resolved) {
        is ResolvedLink.Video -> "视频作品"
        is ResolvedLink.Gallery -> if (resolved.pageIndex != null) "图集 · 第 ${resolved.pageIndex} 页" else "漫画 / 图集"
        is ResolvedLink.Feed -> "站点列表"
        is ResolvedLink.Directory -> "站点目录"
        null -> "未识别链接"
    }
    val kindIcon = when (resolved) {
        is ResolvedLink.Video -> Icons.Outlined.Movie
        is ResolvedLink.Gallery -> Icons.Outlined.AutoStories
        is ResolvedLink.Feed -> Icons.Outlined.Explore
        is ResolvedLink.Directory -> Icons.Outlined.FolderOpen
        null -> Icons.Outlined.Link
    }
    val previewUrl = when (resolved) {
        is ResolvedLink.Video -> resolved.canonicalUrl
        is ResolvedLink.Gallery -> resolved.canonicalUrl
        is ResolvedLink.Directory -> resolved.canonicalUrl
        is ResolvedLink.Feed -> prompt.rawText
        null -> prompt.rawText
    }

    RavenBottomSheet(
        onDismissRequest = onDismissRequest,
        title = if (resolved != null) "打开分享链接" else "无法识别链接",
        subtitle = if (plugin != null && resolved != null) {
            "已匹配 ${plugin.descriptor.displayName} · $kindLabel"
        } else {
            "暂不支持该链接所属的站点或路径格式"
        },
        trailingAction = if (plugin != null) {
            {
                RavenStatusPill(
                    text = plugin.descriptor.shortName,
                    tone = RavenStatusTone.ACCENT,
                )
            }
        } else {
            null
        },
        modifier = Modifier.testTag("shared_link_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (resolved != null && plugin != null) {
                RavenGroupedSection {
                    RavenGroupedRow(
                        title = plugin.descriptor.displayName,
                        subtitle = kindLabel,
                        icon = kindIcon,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface1)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = previewUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RavenSecondaryButton(
                    text = if (resolved != null) "取消" else "知道了",
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("shared_link_cancel_button"),
                )

                if (resolved != null) {
                    RavenPrimaryButton(
                        text = "立即打开",
                        onClick = { onConfirmOpen(resolved) },
                        variant = RavenPrimaryButtonVariant.Cta,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("shared_link_confirm_button"),
                    )
                }
            }
        }
    }
}
