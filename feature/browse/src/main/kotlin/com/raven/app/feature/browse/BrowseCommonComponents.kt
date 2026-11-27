package com.raven.app.feature.browse

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.database.model.ShelfWithSummary
import kotlinx.coroutines.delay
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenCrimsonProgressBar
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenImage
import com.raven.app.core.designsystem.component.RavenMorphToggleIcon
import com.raven.app.core.designsystem.component.RavenShelfOption
import com.raven.app.core.designsystem.component.RavenShelfPickerSheet
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.ravenBurstFeedback
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.workId
import com.raven.app.core.model.workTitle
import com.raven.app.core.model.SiteDescriptor

/**
 * Clean Obsidian Auth Gate card displayed when a plugin requires authentication
 * (`plugin.authProvider.requiresAuthGate && !isAuthenticated`) or throws [SiteException.AuthRequired].
 */
@Composable
internal fun BrowseAuthGateCard(
    site: SiteDescriptor,
    onOpenAccount: () -> Unit,
    onRefreshStatus: () -> Unit,
    modifier: Modifier = Modifier,
    detailMessage: String? = null,
) {
    val colors = LocalRavenColors.current
    val accessNote = site.accessNote
    val bodyText = detailMessage?.takeIf { it.isNotBlank() }
        ?: accessNote?.takeIf { it.isNotBlank() }
        ?: "该站点需要有效登录会话后才能浏览内容，请前往账号管理完成登录或导入 Cookie。"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .padding(horizontal = 20.dp, vertical = 28.dp)
            .testTag("browse_auth_gate_card"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.crimson.copy(alpha = 0.14f))
                .border(0.5.dp, colors.crimson.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = colors.crimson,
                modifier = Modifier.size(26.dp),
            )
        }

        Text(
            text = "${site.displayName} 需要登录验证",
            style = MaterialTheme.typography.titleLarge,
            color = colors.inkPrimary,
            textAlign = TextAlign.Center,
        )

        Text(
            text = bodyText,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onOpenAccount,
                shape = RavenShapes.PillShape,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress()
                    .testTag("auth_gate_login_button"),
            ) {
                Text(
                    text = "前往登录",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            OutlinedButton(
                onClick = onRefreshStatus,
                shape = RavenShapes.PillShape,
                border = BorderStroke(0.5.dp, colors.outlineSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface2,
                    contentColor = colors.inkPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .ravenSpringPress()
                    .testTag("auth_gate_refresh_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "重新检测",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * First-run non-blocking site switch coachmark anchored near [RavenSiteSwitcherPill].
 */
@Composable
internal fun SiteSwitchTutorialCard(
    siteCount: Int,
    onOpenSwitcher: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface2)
            .border(0.5.dp, colors.rimHighlight, RavenShapes.CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("home_site_switch_tutorial_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colors.crimson.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
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
                    text = "切换 $siteCount 个视频/漫画/写真站点",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                )
                Text(
                    text = "点击左上角站点胶囊，可随时切换站点或置顶常用站点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭提示",
                    tint = colors.inkTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .testTag("tutorial_dismiss_button"),
            ) {
                Text(
                    text = "知道了",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkSecondary,
                )
            }

            Button(
                onClick = onOpenSwitcher,
                shape = RavenShapes.PillShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryCtaContainer,
                    contentColor = colors.primaryCtaInk,
                ),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .ravenSpringPress()
                    .testTag("tutorial_open_switcher_button"),
            ) {
                Text(
                    text = "打开站点列表",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/**
 * Compact top Resume Card shown on Comic & Photo Home when an unfinished book exists.
 */
@Composable
internal fun CompactResumeReadingCard(
    progress: ReadingProgress,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val pageLabel = RavenFormatters.formatPageProgress(progress.currentPage, progress.totalPages)
    val workTitle = progress.workTitle
    val chapterTitle = progress.title.takeIf {
        (progress.seriesId != null || progress.workId != progress.mediaId) &&
            it.isNotBlank() && it != workTitle
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.CardShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
            .ravenSpringPress()
            .clickable(role = Role.Button, onClick = onResumeClick)
            .testTag("home_resume_reading_card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(54.dp)
                    .height(72.dp)
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface2)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape),
            ) {
                RavenImage(
                    url = progress.coverUrl,
                    siteId = progress.siteId,
                    contentDescription = workTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RavenStatusPill(
                    text = "继续阅读 · 第 $pageLabel 页",
                    tone = RavenStatusTone.ACCENT,
                )
                Text(
                    text = workTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = if (chapterTitle == null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chapterTitle != null) {
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.primaryCtaContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "继续阅读",
                    tint = colors.primaryCtaInk,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        RavenCrimsonProgressBar(
            fraction = progress.progressFraction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Infinite scroll pagination footer displaying spinner, retry CTA, or end-of-list marker.
 */
@Composable
internal fun BrowsePaginationFooter(
    isLoadingMore: Boolean,
    errorMessage: String?,
    hasMore: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.crimson,
                    )
                    Text(
                        text = "正在加载更多…",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
            }
            !errorMessage.isNullOrBlank() -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RavenShapes.CardShape)
                        .background(colors.surface1)
                        .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = "重试",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.crimson,
                        )
                    }
                }
            }
            !hasMore -> {
                Text(
                    text = "已加载全部内容",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.inkTertiary,
                )
            }
        }
    }
}

/**
 * Single-screen Apple HIG action sheet shown on long-press of any media card.
 *
 * Beyond Watch Later / custom shelf / similar-tag actions, hosts can supply Favorite and
 * Download callbacks. Both are optional so call sites without those facilities keep compiling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaCardActionAndShelfHost(
    targetItem: MediaItem?,
    watchLaterKeys: Set<String>,
    shelves: List<ShelfWithSummary>,
    selectedShelfIds: Set<String>,
    onInspectShelfTarget: (MediaItem?) -> Unit,
    onDismiss: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onToggleWatchLater: (MediaItem) -> Unit,
    onToggleShelfMembership: (shelfId: String, item: MediaItem, add: Boolean) -> Unit,
    onCreateShelfAndAdd: (name: String, item: MediaItem) -> Unit,
    onSearchSimilar: ((String) -> Unit)? = null,
    favoriteKeys: Set<String> = emptySet(),
    onToggleFavorite: ((MediaItem) -> Unit)? = null,
    downloadStateByKey: Map<String, MediaCardDownloadState> = emptyMap(),
    onDownload: ((MediaItem) -> Unit)? = null,
) {
    var shelfPickerItem by remember { mutableStateOf<MediaItem?>(null) }

    if (targetItem != null) {
        val downloadKind = resolveMediaCardDownloadKind(targetItem.kind)
        val downloadState = downloadStateByKey[targetItem.compositeKey] ?: MediaCardDownloadState()
        MediaCardActionSheet(
            item = targetItem,
            isInWatchLater = targetItem.compositeKey in watchLaterKeys,
            isFavorite = onToggleFavorite != null && targetItem.compositeKey in favoriteKeys,
            onDismiss = onDismiss,
            onOpen = { onOpen(targetItem) },
            onToggleWatchLater = { onToggleWatchLater(targetItem) },
            onAddToShelf = if (shelves.isNotEmpty() || onCreateShelfAndAdd != NOOP_CREATE_SHELF) {
                {
                    onInspectShelfTarget(targetItem)
                    shelfPickerItem = targetItem
                }
            } else {
                null
            },
            onToggleFavorite = onToggleFavorite?.let { toggle -> { toggle(targetItem) } },
            onDownload = onDownload?.takeIf { downloadKind != null }?.let { enqueue -> { enqueue(targetItem) } },
            downloadLabel = downloadKind?.actionLabel,
            downloadSubtitle = downloadState.subtitleFor(downloadKind),
            downloadIsActive = downloadState.isActive,
            onSearchSimilar = onSearchSimilar,
        )
    }

    val activePickerItem = shelfPickerItem
    if (activePickerItem != null) {
        RavenShelfPickerSheet(
            title = activePickerItem.title,
            shelves = shelves.map { summary ->
                RavenShelfOption(
                    id = summary.shelf.id,
                    name = summary.shelf.name,
                    itemCount = summary.itemCount,
                    selected = summary.shelf.id in selectedShelfIds ||
                        (summary.shelf.id == Shelf.WATCH_LATER_ID && activePickerItem.compositeKey in watchLaterKeys),
                    isSystem = summary.shelf.isSystem,
                )
            },
            onToggleShelf = { shelfId, add ->
                onToggleShelfMembership(shelfId, activePickerItem, add)
            },
            onCreateShelf = { name ->
                onCreateShelfAndAdd(name, activePickerItem)
            },
            onDismiss = {
                shelfPickerItem = null
                onInspectShelfTarget(null)
            },
        )
    }
}

private val NOOP_CREATE_SHELF: (String, MediaItem) -> Unit = { _, _ -> }

/** Resolved download affordance for a media card long-press action. */
enum class MediaCardDownloadKind(val actionLabel: String) {
    VIDEO("下载视频"),
    GALLERY("下载图集"),
}

/**
 * Whether the existing download APIs can service this [MediaKind]. Videos go through
 * [DownloadCoordinator.enqueueVideoWithPreferredQuality], everything else through
 * [DownloadCoordinator.enqueueGallery]; both are supported, so this returns non-null for all
 * current kinds. Returning null for an unknown future kind keeps the row hidden instead of failing.
 */
fun resolveMediaCardDownloadKind(kind: MediaKind): MediaCardDownloadKind? = when {
    kind.isVideo -> MediaCardDownloadKind.VIDEO
    kind.isGallery -> MediaCardDownloadKind.GALLERY
    else -> null
}

/** Download status surfaced in the action sheet for duplicate-enqueue prevention. */
data class MediaCardDownloadState(
    val isActive: Boolean = false,
    val isCompleted: Boolean = false,
) {
    fun subtitleFor(kind: MediaCardDownloadKind?): String = when {
        isCompleted -> "已在本地下载完成"
        isActive -> "正在下载队列中"
        kind == MediaCardDownloadKind.GALLERY -> "按默认页码范围加入下载队列"
        else -> "按偏好清晰度加入下载队列"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaCardActionSheet(
    item: MediaItem,
    isInWatchLater: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onAddToShelf: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    downloadLabel: String? = null,
    downloadSubtitle: String? = null,
    downloadIsActive: Boolean = false,
    onSearchSimilar: ((String) -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    val firstTag = item.tags.firstOrNull()

    RavenBottomSheet(
        onDismissRequest = onDismiss,
        title = item.title,
        subtitle = buildList {
            add(item.kind.displayLabel())
            val duration = RavenFormatters.formatDuration(item.durationSeconds)
            if (duration.isNotEmpty()) add(duration)
            val pages = RavenFormatters.formatPageCount(item.pageCount)
            if (pages.isNotEmpty()) add(pages)
        }.joinToString(" · "),
    ) {
        RavenGroupedSection {
            RavenGroupedRow(
                title = if (item.kind.isVideo) "立即播放" else "查看作品详情",
                icon = Icons.Outlined.Info,
                showChevron = true,
                onClick = {
                    onDismiss()
                    onOpen()
                },
            )
            // 稍后看只服务于视频追剧；漫画/写真用收藏与追更，重复入口只会让操作变糊。
            if (item.kind.isVideo) {
                RavenGroupedDivider(insetWithIcon = true)
                BrowseMorphGroupedRow(
                    title = if (isInWatchLater) "移出稍后看" else "加入稍后看",
                    subtitle = if (isInWatchLater) "当前已在稍后看书架中" else "保存到内置稍后看书架",
                    iconBadgeModifier = Modifier.ravenBurstFeedback(
                        trigger = isInWatchLater,
                        burstColor = colors.crimson,
                    ),
                    leadingIcon = {
                        RavenMorphToggleIcon(
                            active = isInWatchLater,
                            activeGlyph = RavenMorphIcons.BookmarkFilled,
                            inactiveGlyph = RavenMorphIcons.BookmarkOutline,
                            activeTint = colors.crimson,
                            inactiveTint = colors.inkPrimary,
                            size = 18.dp,
                            springPreset = MorphSpringPreset.BOUNCY,
                        )
                    },
                    onClick = {
                        onToggleWatchLater()
                        onDismiss()
                    },
                )
            }
            if (onToggleFavorite != null) {
                RavenGroupedDivider(insetWithIcon = true)
                BrowseMorphGroupedRow(
                    title = if (isFavorite) "取消收藏" else "收藏作品",
                    subtitle = if (isFavorite) "当前已在收藏中" else "保存到作品收藏（视频 / 漫画 / 写真）",
                    iconBadgeModifier = Modifier.ravenBurstFeedback(
                        trigger = isFavorite,
                        burstColor = colors.crimson,
                    ),
                    leadingIcon = {
                        RavenMorphToggleIcon(
                            active = isFavorite,
                            activeGlyph = RavenMorphIcons.HeartFilled,
                            inactiveGlyph = RavenMorphIcons.HeartOutline,
                            activeTint = colors.crimson,
                            inactiveTint = colors.inkPrimary,
                            size = 18.dp,
                            springPreset = MorphSpringPreset.BOUNCY,
                        )
                    },
                    onClick = {
                        onToggleFavorite()
                        onDismiss()
                    },
                )
            }
            if (onDownload != null && downloadLabel != null) {
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = downloadLabel,
                    subtitle = downloadSubtitle,
                    icon = Icons.Outlined.Download,
                    enabled = !downloadIsActive,
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }
            if (onAddToShelf != null) {
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "加入自定义片单",
                    subtitle = "添加到自定义片单 / 书架或新建分组",
                    icon = Icons.Outlined.LibraryAdd,
                    showChevron = true,
                    onClick = {
                        onDismiss()
                        onAddToShelf()
                    },
                )
            }
            if (firstTag != null && onSearchSimilar != null) {
                RavenGroupedDivider(insetWithIcon = true)
                RavenGroupedRow(
                    title = "搜索标签：${firstTag.label}",
                    subtitle = firstTag.slug,
                    icon = Icons.Outlined.Search,
                    showChevron = true,
                    onClick = {
                        onDismiss()
                        onSearchSimilar(firstTag.slug)
                    },
                )
            }
        }
    }
}

@Composable
private fun BrowseMorphGroupedRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    iconBadgeModifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val rowBackdropColor by animateColorAsState(
        targetValue = when {
            pressed -> colors.surface2.copy(alpha = 0.72f)
            hovered || focused -> colors.surface2.copy(alpha = 0.42f)
            else -> Color.Transparent
        },
        animationSpec = if (motionReduced) snap() else tween(durationMillis = 120),
        label = "browseMorphGroupedRowBackdrop",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(rowBackdropColor)
            .ravenInteractiveLight(
                interactionSource = interactionSource,
                shape = RectangleShape,
                enabled = true,
                glowColor = colors.interactiveGlow,
                highlightAlpha = 0.04f,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = true,
                role = Role.Button,
                onClick = {
                    feedback.perform(RavenFeedbackEffect.TAP)
                    onClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = iconBadgeModifier
                .size(32.dp)
                .clip(RavenShapes.ControlShape)
                .background(colors.surface2)
                .border(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.lightRimTop, colors.hairline),
                    ),
                    shape = RavenShapes.ControlShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            leadingIcon()
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact inline banner used to surface favorite/download outcomes from the long-press sheet.
 * Mirrors the Library feedback banner styling so messages read consistently across features.
 */
private const val BROWSE_MESSAGE_TIMEOUT_MS: Long = 3_200L

@Composable
internal fun BrowseActionMessageBanner(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.isNullOrBlank()) return
    val colors = LocalRavenColors.current
    // 收藏/下载这类结果只是一次性确认：看完就收起，避免长期占住列表顶部。
    LaunchedEffect(message) {
        delay(BROWSE_MESSAGE_TIMEOUT_MS)
        onDismiss()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RavenShapes.ControlShape)
            .background(colors.surface2)
            .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("browse_action_message"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.crimson,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭提示",
                tint = colors.inkTertiary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
internal fun BrowseRefreshFeedback(
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = remember { SnackbarHostState() }
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(errorMessage) {
        if (errorMessage.isNullOrBlank()) return@LaunchedEffect
        val result = host.showSnackbar(
            message = "刷新失败，已保留当前内容。$errorMessage",
            actionLabel = "重试",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) retry()
    }
    SnackbarHost(hostState = host, modifier = modifier)
}

/**
 * Compact pill indicating a restored scroll position anchor when available.
 */
@Composable
internal fun RestoredAnchorBanner(
    throughPage: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onJump: (() -> Unit)? = null,
) {
    val colors = LocalRavenColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RavenShapes.ControlShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = colors.crimson,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "上次浏览至第 $throughPage 页",
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = colors.inkSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        if (onJump != null) {
            TextButton(
                onClick = onJump,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = "定位",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.crimson,
                )
            }
        }
        TextButton(
            onClick = onDismiss,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = "隐藏",
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkTertiary,
            )
        }
    }
}
