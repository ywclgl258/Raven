package com.raven.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.SiteId
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class CollectionTab(val label: String, val tabKey: String) {
    DOWNLOADS("下载", "downloads"),
    FAVORITES("收藏", "favorites");

    companion object {
        fun fromKey(raw: String?): CollectionTab =
            entries.firstOrNull { it.tabKey.equals(raw, ignoreCase = true) } ?: DOWNLOADS
    }
}

/**
 * 「下载与收藏」：把下载中心与收藏整合为一个一级入口。
 *
 * 顶部为双分栏大按钮（下载 / 收藏），下方按分栏复用 [DownloadsScreen] 与 [FavoritesScreen] 的内容，
 * 两者共享各自的 ViewModel，所以计数与操作状态保持一致。
 */
@Composable
fun DownloadsFavoritesScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    siteId: SiteId? = null,
    initialTab: String = "downloads",
    downloadsViewModel: DownloadsViewModel = koinViewModel(
        key = "downloads_${siteId?.value.orEmpty()}",
        parameters = { parametersOf(DownloadsParams(siteId)) },
    ),
    favoritesViewModel: FavoritesViewModel = koinViewModel(
        key = "favorites_${siteId?.value.orEmpty()}",
        parameters = { parametersOf(FavoritesParams(siteId)) },
    ),
) {
    val colors = LocalRavenColors.current
    val downloadsState by downloadsViewModel.uiState.collectAsStateWithLifecycle()
    val favoritesState by favoritesViewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable(siteId?.value, initialTab) {
        mutableStateOf(CollectionTab.fromKey(initialTab))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("downloads_favorites_screen"),
    ) {
        RavenTopBar(
            title = "下载与收藏",
            subtitle = buildString {
                append("已下载 ${downloadsState.completedCount}")
                if (downloadsState.activeTransferCount > 0) append(" · 进行中 ${downloadsState.activeTransferCount}")
                append(" · 收藏 ${favoritesState.totalCount}")
            },
            navigationIcon = {
                IconButton(onClick = navigator::navigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
        )

        CollectionTabSwitcher(
            selected = selectedTab,
            downloadsCount = downloadsState.completedCount,
            activeDownloads = downloadsState.activeTransferCount,
            favoritesCount = favoritesState.totalCount,
            onSelect = { selectedTab = it },
        )

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                CollectionTab.DOWNLOADS -> DownloadsScreen(
                    navigator = navigator,
                    siteId = siteId,
                    embedded = true,
                )
                CollectionTab.FAVORITES -> FavoritesScreen(
                    navigator = navigator,
                    siteId = siteId,
                    embedded = true,
                )
            }
        }
    }
}

@Composable
private fun CollectionTabSwitcher(
    selected: CollectionTab,
    downloadsCount: Int,
    activeDownloads: Int,
    favoritesCount: Int,
    onSelect: (CollectionTab) -> Unit,
) {
    val colors = LocalRavenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CollectionTabButton(
            tab = CollectionTab.DOWNLOADS,
            icon = Icons.Outlined.Download,
            title = "下载",
            count = downloadsCount,
            badge = if (activeDownloads > 0) "$activeDownloads 进行中" else null,
            selected = selected == CollectionTab.DOWNLOADS,
            onClick = { onSelect(CollectionTab.DOWNLOADS) },
            modifier = Modifier.weight(1f),
        )
        CollectionTabButton(
            tab = CollectionTab.FAVORITES,
            icon = Icons.Outlined.FavoriteBorder,
            title = "收藏",
            count = favoritesCount,
            badge = null,
            selected = selected == CollectionTab.FAVORITES,
            onClick = { onSelect(CollectionTab.FAVORITES) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CollectionTabButton(
    tab: CollectionTab,
    icon: ImageVector,
    title: String,
    count: Int,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val container = if (selected) colors.crimson.copy(alpha = 0.16f) else colors.surface1
    val borderColor = if (selected) colors.crimson else colors.hairline
    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RavenShapes.ControlShape)
            .background(container)
            .border(if (selected) 1.dp else 0.5.dp, borderColor, RavenShapes.ControlShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("collection_tab_${tab.tabKey}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (selected) colors.crimson.copy(alpha = 0.18f) else colors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) colors.crimson else colors.inkSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) colors.crimson else colors.inkPrimary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    fontWeight = FontWeight.Bold,
                    color = if (selected) colors.crimson else colors.inkPrimary,
                )
            }
            Text(
                text = badge ?: if (tab == CollectionTab.DOWNLOADS) "已完成下载" else "收藏的作品与标签",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkSecondary,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
