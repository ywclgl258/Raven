package com.raven.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenAnimatedCounter
import com.raven.app.core.designsystem.component.ravenSquashPress
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular

/** 资料库指标卡、确认 Sheet 与多选操作栏。从 `LibraryCommonComponents.kt` 拆出。 */

/**
 * Compact Obsidian metric tile for Library Hub (`历史 / 收藏 / 下载 / 更新`).
 */
@Composable
internal fun LibraryMetricTile(
    title: String,
    count: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightBadgeCount: Int = 0,
    subtitle: String? = null,
    testTag: String? = null,
) {
    val colors = LocalRavenColors.current
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSquashPress(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(12.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlightBadgeCount > 0) colors.crimson else colors.inkPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (highlightBadgeCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RavenShapes.PillShape)
                        .background(colors.crimson)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (highlightBadgeCount > 99) "99+" else highlightBadgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall.tabular().copy(fontWeight = FontWeight.Bold),
                        color = colors.inkPrimary,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            RavenAnimatedCounter(
                count = count,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.inkPrimary,
            )
            Text(
                text = subtitle ?: title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * HarmonyOS confirmation dialog for destructive or critical library actions
 * (delete download/shelf, clear history/favorites). Disables scrim dismissal when [destructive] is true.
 */
@Composable
internal fun LibraryConfirmBottomSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    com.raven.app.core.designsystem.component.RavenConfirmDialog(
        onDismissRequest = onDismiss,
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        onConfirm = {
            onDismiss()
            onConfirm()
        },
        destructive = destructive,
    )
}

/**
 * Multi-select contextual bottom bar for batch add-to-shelf / batch delete across Library screens.
 */
@Composable
internal fun LibrarySelectionActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onAddToShelf: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteSelected: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 $selectedCount 项",
            style = MaterialTheme.typography.labelLarge.tabular(),
            color = colors.inkPrimary,
        )
        TextButton(onClick = onSelectAll) {
            Text("全选", color = colors.inkSecondary)
        }
        Spacer(modifier = Modifier.weight(1f))
        if (onDeleteSelected != null) {
            OutlinedButton(
                onClick = onDeleteSelected,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.error.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("移除", style = MaterialTheme.typography.labelMedium)
            }
        }
        Button(
            onClick = onAddToShelf,
            enabled = selectedCount > 0,
            shape = RavenShapes.PillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primaryCtaContainer,
                contentColor = colors.primaryCtaInk,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("加入片单", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        }
        IconButton(onClick = onClearSelection) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "退出多选",
                tint = colors.inkSecondary,
            )
        }
    }
}
