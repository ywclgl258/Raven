package com.raven.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenCrimsonProgressBar
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFilterChip
import com.raven.app.core.designsystem.component.RavenGalleryCard
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenHorizontalShelf
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenSectionHeader
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.component.RavenVideoCard
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ambientHeroGlow
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.Shelf
import org.koin.androidx.compose.koinViewModel

/**
 * 「我的」 Personal Consumption & Activity Hub (`ProfileScreen`).
 */
@Composable
fun ProfileScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileScreenContent(
        uiState = uiState,
        navigator = navigator,
        onSelectFilter = viewModel::selectFilter,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreenContent(
    uiState: ProfileUiState,
    navigator: LibraryNavigator,
    onSelectFilter: (LibraryMediaFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("profile_screen"),
    ) {
        RavenTopBar(
            title = "我的",
            subtitle = "继续观看与个人动态",
            actions = {
                IconButton(
                    onClick = { navigator.openSearch() },
                    modifier = Modifier.testTag("profile_search_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "搜索",
                        tint = colors.inkPrimary,
                    )
                }
                IconButton(
                    onClick = navigator::openSettings,
                    modifier = Modifier.testTag("profile_settings_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "设置",
                        tint = colors.inkPrimary,
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "profile_kind_filters") {
                LibraryMediaFilterChips(
                    selected = uiState.selectedFilter,
                    onSelect = onSelectFilter,
                    modifier = Modifier.testTag("profile_media_filter_chips"),
                )
            }

            // Hero Continue Watching/Reading Feature Card (or Watch Later fallback)
            uiState.heroItem?.let { hero ->
                item(key = "profile_hero_${hero.compositeKey}") {
                    ProfileHeroFeatureCard(
                        hero = hero,
                        onContinueClick = {
                            navigator.openMedia(
                                item = hero.mediaItem,
                                startPage = hero.resumePage,
                            )
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // Quick Stats / Unread Update & Active Downloads Banner
            item(key = "profile_activity_banner") {
                ProfileStatusBannerRow(
                    unreadInboxCount = uiState.unreadInboxCount,
                    activeDownloadCount = uiState.activeDownloadCount,
                    completedDownloadCount = uiState.completedDownloadCount,
                    onOpenInbox = navigator::openGlobalInbox,
                    onOpenDownloads = navigator::openDownloads,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // Horizontal Continue Watching & Reading Shelf
            if (uiState.continueItems.isNotEmpty()) {
                item(key = "profile_continue_shelf") {
                    RavenHorizontalShelf(
                        title = "继续观看与阅读",
                        subtitle = "${uiState.continueItems.size} 项未完成",
                        actionLabel = "全部历史",
                        onActionClick = navigator::openHistory,
                        modifier = Modifier.testTag("profile_continue_shelf"),
                    ) {
                        items(
                            items = uiState.continueItems,
                            key = { "cont_${it.compositeKey}" },
                        ) { entry ->
                            if (entry.kind.isVideo) {
                                RavenVideoCard(
                                    item = entry.mediaItem,
                                    onClick = { navigator.openMedia(entry.mediaItem) },
                                    progressFraction = entry.progressFraction.takeIf { it > 0f },
                                    badgeText = entry.siteLabel,
                                    subtitleOverride = entry.resumeLabel,
                                    modifier = Modifier.width(216.dp),
                                )
                            } else {
                                RavenGalleryCard(
                                    item = entry.mediaItem,
                                    onClick = {
                                        navigator.openMedia(
                                            item = entry.mediaItem,
                                            startPage = entry.resumePage,
                                        )
                                    },
                                    progressFraction = entry.progressFraction.takeIf { it > 0f },
                                    badgeText = entry.siteLabel,
                                    modifier = Modifier.width(142.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Horizontal Watch Later Shelf
            if (uiState.watchLaterItems.isNotEmpty()) {
                item(key = "profile_watch_later_shelf") {
                    RavenHorizontalShelf(
                        title = "我的稍后看",
                        subtitle = "${uiState.watchLaterItems.size} 项待看",
                        actionLabel = "查看全部",
                        onActionClick = { navigator.openShelfDetail(Shelf.WATCH_LATER_ID) },
                        modifier = Modifier.testTag("profile_watch_later_shelf"),
                    ) {
                        items(
                            items = uiState.watchLaterItems,
                            key = { "wl_${it.compositeKey}" },
                        ) { shelfItem ->
                            val mediaItem = shelfItem.toMediaItem()
                            val badge = uiState.siteLabels[shelfItem.siteId] ?: shelfItem.siteId.value
                            if (shelfItem.kind.isVideo) {
                                RavenVideoCard(
                                    item = mediaItem,
                                    onClick = { navigator.openMedia(mediaItem) },
                                    badgeText = badge,
                                    subtitleOverride = if (shelfItem.completed) "已完成" else null,
                                    modifier = Modifier.width(216.dp),
                                )
                            } else {
                                RavenGalleryCard(
                                    item = mediaItem,
                                    onClick = { navigator.openMedia(mediaItem) },
                                    badgeText = badge,
                                    modifier = Modifier.width(142.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Saved Searches Quick Chips
            if (uiState.savedSearches.isNotEmpty()) {
                item(key = "profile_saved_searches") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("profile_saved_searches"),
                    ) {
                        RavenSectionHeader(
                            title = "保存的搜索",
                            subtitle = "一键直达常用检索",
                            actionLabel = "查看更新",
                            onActionClick = navigator::openGlobalInbox,
                        )
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            uiState.savedSearches.forEach { saved ->
                                RavenFilterChip(
                                    label = saved.name,
                                    selected = false,
                                    leadingIcon = Icons.Outlined.Search,
                                    onClick = { openSavedSearch(navigator, saved) },
                                )
                            }
                        }
                    }
                }
            }

            // Empty guidance when user has no activity yet
            if (!uiState.hasAnyContent) {
                item(key = "profile_empty") {
                    RavenEmptyState(
                        title = "还没有观看或阅读记录",
                        message = "浏览视频、漫画或写真后，未看完的作品和稍后看片单会自动汇集到这里。",
                        icon = Icons.Outlined.BookmarkBorder,
                        actionLabel = "前往搜索发现",
                        onActionClick = { navigator.openSearch() },
                    )
                }
            }

            // Quick Apple HIG Personal Shortcuts
            item(key = "profile_quick_links") {
                RavenGroupedSection(
                    title = "个人资产快捷入口",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    RavenGroupedRow(
                        title = "观看与阅读历史",
                        value = "${uiState.totalHistoryCount} 条",
                        icon = Icons.Outlined.History,
                        showChevron = true,
                        onClick = navigator::openHistory,
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "我的收藏",
                        value = "${uiState.totalFavoriteCount} 项",
                        icon = Icons.Outlined.FavoriteBorder,
                        showChevron = true,
                        onClick = navigator::openFavorites,
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "更新收件箱",
                        value = if (uiState.unreadInboxCount > 0) "${uiState.unreadInboxCount} 条未读" else "已全部阅读",
                        icon = Icons.Outlined.NotificationsActive,
                        iconTint = if (uiState.unreadInboxCount > 0) colors.crimson else colors.inkPrimary,
                        showChevron = true,
                        onClick = navigator::openGlobalInbox,
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "应用设置",
                        subtitle = "站点账号、存储与备份、播放器与阅读偏好",
                        icon = Icons.Outlined.Settings,
                        showChevron = true,
                        onClick = navigator::openSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileHeroFeatureCard(
    hero: ProfileHeroItem,
    onContinueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(248.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onContinueClick,
            )
            .testTag("profile_hero_card"),
    ) {
        RavenImage(
            url = hero.coverUrl,
            siteId = hero.siteId,
            contentDescription = hero.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .ambientHeroGlow(tint = colors.crimson, alpha = 0.14f)
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to colors.canvas.copy(alpha = 0.25f),
                            0.45f to colors.canvas.copy(alpha = 0.58f),
                            0.85f to colors.canvas.copy(alpha = 0.90f),
                            1.0f to colors.scrimBottom,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RavenStatusPill(
                    text = hero.statusBadge,
                    tone = if (hero.source == ProfileHeroSource.CONTINUE_ACTIVITY) {
                        RavenStatusTone.ACCENT
                    } else {
                        RavenStatusTone.NEUTRAL
                    },
                )
                RavenStatusPill(
                    text = hero.siteLabel,
                    tone = RavenStatusTone.NEUTRAL,
                )
            }

            Text(
                text = hero.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = hero.resumeCaption,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onContinueClick,
                    shape = RavenShapes.PillShape,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryCtaContainer,
                        contentColor = colors.primaryCtaInk,
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .ravenSpringPress()
                        .testTag("profile_hero_cta"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = hero.ctaLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }

        if (hero.progressFraction != null && hero.progressFraction > 0f) {
            RavenCrimsonProgressBar(
                fraction = hero.progressFraction,
                trackHeight = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProfileStatusBannerRow(
    unreadInboxCount: Int,
    activeDownloadCount: Int,
    completedDownloadCount: Int,
    onOpenInbox: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Global Update Inbox card
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RavenShapes.CardShape)
                .background(colors.surface1)
                .border(
                    width = 0.5.dp,
                    color = if (unreadInboxCount > 0) colors.crimson.copy(alpha = 0.40f) else colors.hairline,
                    shape = RavenShapes.CardShape,
                )
                .ravenSpringPress()
                .clickable(role = Role.Button, onClick = onOpenInbox)
                .padding(12.dp)
                .testTag("profile_unread_banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (unreadInboxCount > 0) colors.crimson.copy(alpha = 0.16f) else colors.surface2,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = if (unreadInboxCount > 0) colors.crimson else colors.inkSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "更新通知",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkPrimary,
                    maxLines = 1,
                )
                Text(
                    text = if (unreadInboxCount > 0) "$unreadInboxCount 条未读更新" else "暂无未读更新",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = if (unreadInboxCount > 0) colors.crimson else colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Downloads quick status card
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RavenShapes.CardShape)
                .background(colors.surface1)
                .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                .ravenSpringPress()
                .clickable(role = Role.Button, onClick = onOpenDownloads)
                .padding(12.dp)
                .testTag("profile_downloads_banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (activeDownloadCount > 0) colors.crimson.copy(alpha = 0.16f) else colors.surface2,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    tint = if (activeDownloadCount > 0) colors.crimson else colors.inkPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "离线下载",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkPrimary,
                    maxLines = 1,
                )
                Text(
                    text = if (activeDownloadCount > 0) {
                        "$activeDownloadCount 个进行中"
                    } else {
                        "已完成 $completedDownloadCount 项"
                    },
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun openSavedSearch(
    navigator: LibraryNavigator,
    saved: SavedSearch,
) {
    if (saved.siteIds.size == 1 && saved.query.isNotBlank() && saved.filters.isEmpty) {
        navigator.openSiteFeed(
            RavenRoute.SiteFeed(
                siteId = saved.siteIds.first().value,
                query = saved.query,
                scope = saved.scope.name,
                title = saved.name,
            ),
        )
    } else {
        navigator.openSearch(
            initialQuery = saved.query.takeIf { it.isNotBlank() },
            crossSite = saved.siteIds.size > 1,
        )
    }
}
