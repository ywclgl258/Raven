package com.raven.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenCrimsonProgressBar
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.tabular
import org.koin.androidx.compose.koinViewModel

/**
 * Personal viewing, reading, site distribution, offline storage, and tag analytics screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("stats_screen"),
    ) {
        RavenTopBar(
            title = "观看与阅读统计",
            subtitle = "本地数据实时汇总",
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "stats_overview_cards") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatMetricCard(
                            label = "累计观看",
                            value = formatWatchHoursMinutes(uiState.totalWatchDurationMs),
                            caption = "${uiState.totalWatchedCount} 部 · ${uiState.completedWatchCount} 部看完",
                            icon = Icons.Outlined.Movie,
                            modifier = Modifier.weight(1f),
                        )
                        StatMetricCard(
                            label = "累计阅读",
                            value = "${uiState.totalReadPages} 页",
                            caption = "${uiState.totalReadCount} 本 · ${uiState.completedReadCount} 本读完",
                            icon = Icons.Outlined.MenuBook,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatMetricCard(
                            label = "收藏与片单",
                            value = "${uiState.favoriteCount} 收藏",
                            caption = "${uiState.shelfCount} 个片单 / 书架",
                            icon = Icons.Outlined.FavoriteBorder,
                            modifier = Modifier.weight(1f),
                        )
                        StatMetricCard(
                            label = "离线占用",
                            value = RavenFormatters.formatByteSize(uiState.downloadTotalBytes).ifBlank { "0 B" },
                            caption = "${uiState.downloadCompletedCount} 项已完成下载",
                            icon = Icons.Outlined.Download,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (uiState.siteBreakdowns.isEmpty() && uiState.topTags.isEmpty()) {
                item(key = "stats_empty") {
                    RavenEmptyState(
                        title = "暂无行为统计数据",
                        message = "开始观看视频、阅读漫画或收藏作品后，系统会自动汇总各站点分布与高频标签。",
                        icon = Icons.Outlined.BarChart,
                    )
                }
            }

            if (uiState.siteBreakdowns.isNotEmpty()) {
                item(key = "stats_site_breakdown") {
                    val maxInteractions = uiState.siteBreakdowns.maxOf { it.totalInteractions }.coerceAtLeast(1)
                    RavenGroupedSection(
                        title = "各站点活跃分布",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        uiState.siteBreakdowns.forEachIndexed { index, stat ->
                            if (index > 0) RavenGroupedDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stat.siteName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "视频 ${stat.watchedCount} · 图册 ${stat.readCount} · 收藏 ${stat.favoriteCount}",
                                        style = MaterialTheme.typography.bodySmall.tabular(),
                                        color = colors.inkSecondary,
                                    )
                                }
                                RavenCrimsonProgressBar(
                                    fraction = (stat.totalInteractions.toFloat() / maxInteractions.toFloat()).coerceIn(0.05f, 1f),
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.topTags.isNotEmpty()) {
                item(key = "stats_top_tags") {
                    RavenGroupedSection(
                        title = "高频偏好标签（已本地翻译）",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            uiState.topTags.forEach { tag ->
                                RavenStatusPill(
                                    text = "${tag.label} · ${tag.count}",
                                    tone = RavenStatusTone.NEUTRAL,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "stats_quick_nav") {
                RavenGroupedSection(
                    title = "快捷管理",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    RavenGroupedRow(
                        title = "前往下载与收藏",
                        subtitle = RavenFormatters.formatByteSize(uiState.downloadTotalBytes),
                        icon = Icons.Outlined.Download,
                        showChevron = true,
                        onClick = { navigator.openDownloads() },
                    )
                    RavenGroupedDivider(insetWithIcon = true)
                    RavenGroupedRow(
                        title = "查看完整历史记录",
                        subtitle = "观看 ${uiState.totalWatchedCount} 项 · 阅读 ${uiState.totalReadCount} 项",
                        icon = Icons.Outlined.MenuBook,
                        showChevron = true,
                        onClick = navigator::openHistory,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatMetricCard(
    label: String,
    value: String,
    caption: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    Column(
        modifier = modifier
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.crimson,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkSecondary,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold).tabular(),
            color = colors.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatWatchHoursMinutes(durationMs: Long): String {
    val totalMinutes = (durationMs.coerceAtLeast(0L) / 60_000L)
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return when {
        hours > 0L -> "${hours}小时${mins}分"
        totalMinutes > 0L -> "${totalMinutes} 分钟"
        else -> "0 分钟"
    }
}
