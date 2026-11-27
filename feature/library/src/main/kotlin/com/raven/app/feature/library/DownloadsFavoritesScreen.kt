package com.raven.app.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
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
import com.raven.app.core.designsystem.component.RavenAnimatedCounter
import com.raven.app.core.designsystem.component.RavenContentCrossfade
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
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

internal fun LibraryCollectionLayout.toggled(): LibraryCollectionLayout =
    if (this == LibraryCollectionLayout.WATERFALL) {
        LibraryCollectionLayout.LIST
    } else {
        LibraryCollectionLayout.WATERFALL
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
    var collectionLayout by rememberSaveable { mutableStateOf(defaultCollectionLayout()) }
    var showClearSiteConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("downloads_favorites_screen"),
    ) {
        RavenTopBar(
            modifier = Modifier.testTag("downloads_favorites_top_bar"),
            title = "下载与收藏",
            subtitle = buildString {
                append("已下载 ${downloadsState.completedCount}")
                append(" · 离线占用 ${RavenFormatters.formatByteSize(downloadsState.totalCompletedBytes)}")
                if (downloadsState.activeTransferCount > 0) append(" · 进行中 ${downloadsState.activeTransferCount}")
                append(" · 收藏 ${favoritesState.totalCount}")
            },
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("downloads_favorites_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
            actions = {
                LibraryCollectionLayoutButton(
                    layout = collectionLayout,
                    onToggle = { collectionLayout = collectionLayout.toggled() },
                )
                when (selectedTab) {
                    CollectionTab.DOWNLOADS -> IconButton(
                        onClick = downloadsViewModel::syncFromDisk,
                        enabled = !downloadsState.isSyncingDisk,
                        modifier = Modifier.testTag("downloads_sync_disk_button"),
                    ) {
                        if (downloadsState.isSyncingDisk) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.crimson,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = "同步磁盘离线记录",
                                tint = colors.inkPrimary,
                            )
                        }
                    }

                    CollectionTab.FAVORITES -> {
                        IconButton(
                            onClick = { favoritesViewModel.toggleSelectionMode() },
                            modifier = Modifier.testTag("favorites_multiselect_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckBox,
                                contentDescription = "批量管理",
                                tint = if (favoritesState.selectionMode) colors.crimson else colors.inkPrimary,
                            )
                        }
                        if (favoritesState.siteFilter != null && favoritesState.items.isNotEmpty()) {
                            IconButton(
                                onClick = { showClearSiteConfirm = true },
                                modifier = Modifier.testTag("favorites_clear_site_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "清空当前站点收藏",
                                    tint = colors.inkSecondary,
                                )
                            }
                        }
                    }
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
            RavenContentCrossfade(targetState = selectedTab) { tab ->
                when (tab) {
                    CollectionTab.DOWNLOADS -> DownloadsScreen(
                        navigator = navigator,
                        siteId = siteId,
                        embedded = true,
                        collectionLayout = collectionLayout,
                        viewModel = downloadsViewModel,
                    )
                    CollectionTab.FAVORITES -> FavoritesScreen(
                        navigator = navigator,
                        siteId = siteId,
                        embedded = true,
                        collectionLayout = collectionLayout,
                        viewModel = favoritesViewModel,
                    )
                }
            }
        }
    }

    if (showClearSiteConfirm && favoritesState.siteFilter != null) {
        val clearSiteId = favoritesState.siteFilter!!
        val label = favoritesState.siteLabels[clearSiteId] ?: clearSiteId.value
        LibraryConfirmBottomSheet(
            title = "清空「$label」的全部收藏？",
            message = "该站点的所有收藏条目将被移除，此操作不可撤销。",
            confirmLabel = "确认清空",
            onConfirm = { favoritesViewModel.clearSiteFavorites(clearSiteId) },
            onDismiss = { showClearSiteConfirm = false },
        )
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
    val motionReduced = LocalMotionReduced.current
    val colorSpec = if (motionReduced) {
        snap<androidx.compose.ui.graphics.Color>()
    } else {
        tween(durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS, easing = RavenMotion.HarmonyDecelerationEasing)
    }
    val container by animateColorAsState(
        targetValue = if (selected) colors.crimson.copy(alpha = 0.16f) else colors.surface1,
        animationSpec = colorSpec,
        label = "collectionTabContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.crimson else colors.hairline,
        animationSpec = colorSpec,
        label = "collectionTabBorder",
    )
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
                RavenAnimatedCounter(
                    count = count,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
