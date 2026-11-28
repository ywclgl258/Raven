package com.raven.app.feature.browse


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.SiteId

/** 搜索结果边缘控件：跨站分页脚注、站点标签、状态 Chip 与目录跳转卡。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CrossSitePaginationFooter(
    buckets: List<CrossSiteBucketState>,
    onLoadMoreSite: (SiteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val loadableBuckets = buckets.filter { it.hasMore }
    val isLoadingAny = buckets.any { it.isLoadingMore }
    val sourceNames = loadableBuckets.joinToString("、") { it.site.shortName }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("cross_site_pagination_footer"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    loadableBuckets.isEmpty() -> "正在加载更多来源…"
                    sourceNames.length > 24 -> "还可从 ${loadableBuckets.size} 个来源继续加载"
                    else -> "还可从 $sourceNames 继续加载"
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isLoadingAny) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.8.dp,
                    color = colors.crimson,
                )
            } else {
                TextButton(
                    onClick = { loadableBuckets.forEach { onLoadMoreSite(it.site.id) } },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("cross_footer_load_all"),
                ) {
                    Text(
                        text = if (loadableBuckets.size > 1) "全部加载" else "加载更多",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.crimson,
                    )
                }
            }
        }

        val paginationError = buckets.firstNotNullOfOrNull { it.errorMessage }
        if (!paginationError.isNullOrBlank()) {
            Text(
                text = paginationError,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("cross_footer_pagination_error"),
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buckets.forEach { bucket ->
                val hasPaginationError = bucket.errorMessage != null
                val canLoadMore = bucket.hasMore && !bucket.isLoadingMore
                Row(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RavenShapes.PillShape)
                        .background(colors.surface1)
                        .border(
                            width = 0.5.dp,
                            color = if (hasPaginationError) colors.error.copy(alpha = 0.35f) else colors.hairline,
                            shape = RavenShapes.PillShape,
                        )
                        .clickable(enabled = canLoadMore, role = Role.Button) {
                            onLoadMoreSite(bucket.site.id)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("cross_footer_load_more_${bucket.site.id.value}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = bucket.site.shortName,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasPaginationError) colors.error else colors.inkPrimary,
                        maxLines = 1,
                    )
                    when {
                        bucket.isLoadingMore -> CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.6.dp,
                            color = colors.crimson,
                        )
                        hasPaginationError -> Text(
                            text = "重试",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.error,
                        )
                        else -> Text(
                            text = "+更多",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.crimson,
                        )
                    }
                }
            }
        }
    }
}

internal fun historySiteLabel(
    entry: SearchHistoryEntry,
    siteShortNames: Map<SiteId, String>,
): String = if (entry.siteId.isGlobal) {
    "跨站"
} else {
    siteShortNames[entry.siteId] ?: entry.siteId.value
}

@Composable
internal fun CrossSiteStatusChip(
    bucket: CrossSiteBucketState,
    selected: Boolean,
    onFilterClick: () -> Unit,
    onRetryClick: () -> Unit,
    onLoadMoreClick: () -> Unit,
    onAuthClick: () -> Unit,
) {
    val colors = LocalRavenColors.current

    val containerColor = when {
        selected -> colors.primaryCtaContainer
        bucket.status == CrossSiteStatus.ERROR -> colors.error.copy(alpha = 0.14f)
        bucket.status == CrossSiteStatus.AUTH_REQUIRED -> colors.warning.copy(alpha = 0.14f)
        else -> colors.surface1
    }
    val borderColor = when {
        selected -> colors.crimson
        bucket.status == CrossSiteStatus.ERROR -> colors.error.copy(alpha = 0.35f)
        bucket.status == CrossSiteStatus.AUTH_REQUIRED -> colors.warning.copy(alpha = 0.35f)
        else -> colors.hairline
    }
    val contentColor = when {
        selected -> colors.primaryCtaInk
        bucket.status == CrossSiteStatus.ERROR -> colors.error
        bucket.status == CrossSiteStatus.AUTH_REQUIRED -> colors.warning
        else -> colors.inkPrimary
    }

    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RavenShapes.PillShape)
            .background(containerColor)
            .border(0.5.dp, borderColor, RavenShapes.PillShape)
            .clickable(role = Role.Button) {
                when (bucket.status) {
                    CrossSiteStatus.ERROR -> onRetryClick()
                    CrossSiteStatus.AUTH_REQUIRED -> onAuthClick()
                    CrossSiteStatus.SUCCESS -> onFilterClick()
                    else -> Unit
                }
            }
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .testTag("cross_status_${bucket.site.id.value}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = bucket.site.shortName,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            maxLines = 1,
        )

        when (bucket.status) {
            CrossSiteStatus.LOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.6.dp,
                    color = colors.crimson,
                )
            }
            CrossSiteStatus.SUCCESS -> {
                Text(
                    text = bucket.items.size.toString(),
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = if (selected) colors.primaryCtaInk else colors.inkSecondary,
                )
                if (bucket.hasMore) {
                    if (bucket.isLoadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.6.dp,
                            color = colors.crimson,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable { onLoadMoreClick() }
                                .testTag("cross_load_more_${bucket.site.id.value}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "+更多",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.crimson,
                            )
                        }
                    }
                }
            }
            CrossSiteStatus.EMPTY -> {
                Text(
                    text = "0",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.inkTertiary,
                )
            }
            CrossSiteStatus.ERROR -> {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "重试 ${bucket.site.shortName}",
                    tint = colors.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                )
            }
            CrossSiteStatus.AUTH_REQUIRED -> {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "需登录",
                    tint = colors.warning,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "登录",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.warning,
                )
            }
            CrossSiteStatus.IDLE -> Unit
        }
    }
}

@Composable
internal fun DirectoryJumpCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = LocalRavenColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("directory_jump_card"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RavenShapes.ControlShape)
                .background(colors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        RavenStatusPill(text = "直达", tone = RavenStatusTone.NEUTRAL)

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.inkTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

