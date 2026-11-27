package com.raven.app.feature.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenMorphIcon
import com.raven.app.core.designsystem.component.RavenMorphToggleIcon
import com.raven.app.core.designsystem.component.RavenSearchField
import com.raven.app.core.designsystem.motion.MorphSpringPreset
import com.raven.app.core.designsystem.motion.RavenMorphIcons
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId

private fun SiteDescriptor.matchesSwitcherFilter(normalizedQuery: String): Boolean =
    shortName.contains(normalizedQuery, ignoreCase = true) ||
        displayName.contains(normalizedQuery, ignoreCase = true) ||
        defaultHost.contains(normalizedQuery, ignoreCase = true)

/**
 * Modal bottom sheet presenting all live sites from [SiteRegistry] grouped by
 * `VIDEO / COMIC / PHOTO` plus a collapsible **常用站点 (Pinned)** section with edit/pin toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteSwitcherSheet(
    sites: List<SiteDescriptor>,
    activeSiteId: SiteId,
    pinnedSiteIds: List<SiteId>,
    onSelectSite: (SiteId) -> Unit,
    onTogglePinSite: (SiteId) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    siteOpenCounts: Map<SiteId, Int> = emptyMap(),
    startupDefaultSiteId: SiteId? = null,
    onSelectStartupDefaultSite: (SiteId?) -> Unit = {},
    dynamicSiteSorting: Boolean = true,
    onDynamicSiteSortingChange: (Boolean) -> Unit = {},
    onReorderPinnedSites: (List<SiteId>) -> Unit = {},
) {
    var editPinsMode by rememberSaveable { mutableStateOf(false) }
    var startupSiteMode by rememberSaveable { mutableStateOf(false) }

    RavenBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.testTag("site_switcher_sheet"),
        title = "切换站点",
        subtitle = if (startupSiteMode) {
            "点击下方任意站点设为 APP 启动时打开的第一个站点"
        } else {
            "共 ${sites.size} 个可用站点 · 长按或编辑可置顶常用站点"
        },
        trailingAction = {
            TextButton(
                onClick = {
                    if (startupSiteMode) {
                        startupSiteMode = false
                    } else {
                        editPinsMode = !editPinsMode
                    }
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("site_switcher_edit_pins_button"),
            ) {
                Text(
                    text = if (startupSiteMode || editPinsMode) "完成" else "编辑常用",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (startupSiteMode || editPinsMode) {
                        LocalRavenColors.current.crimson
                    } else {
                        LocalRavenColors.current.inkPrimary
                    },
                )
            }
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SiteSwitcherDrawerContent(
            sites = sites,
            activeSiteId = activeSiteId,
            pinnedSiteIds = pinnedSiteIds,
            siteOpenCounts = siteOpenCounts,
            startupDefaultSiteId = startupDefaultSiteId,
            editPinsMode = editPinsMode,
            startupSiteMode = startupSiteMode,
            dynamicSiteSorting = dynamicSiteSorting,
            onDynamicSiteSortingChange = onDynamicSiteSortingChange,
            onReorderPinnedSites = onReorderPinnedSites,
            onToggleStartupSiteMode = {
                val next = !startupSiteMode
                startupSiteMode = next
                if (next) editPinsMode = false
            },
            onSelectStartupDefaultSite = { siteId ->
                onSelectStartupDefaultSite(siteId)
                startupSiteMode = false
            },
            onSelectSite = { siteId ->
                onSelectSite(siteId)
                onDismissRequest()
            },
            onTogglePinSite = onTogglePinSite,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

private data class SiteSwitcherGroupSpec(
    val kind: MediaKind,
    val title: String,
    val icon: ImageVector,
    val sites: List<SiteDescriptor>,
)

/**
 * Reusable compact adaptive site switcher content grouped by `VIDEO / COMIC / SERIAL_COMIC / PHOTO`
 * with a collapsible **常用站点 (Pinned)** section.
 *
 * When [dynamicSiteSorting] is `true` both the pinned section and the category groups are ordered
 * descending by local visit frequency. When it is `false` the pinned section preserves the stored
 * [pinnedSiteIds] manual order, the groups preserve registry order, and pinned tiles become
 * long-press draggable.
 */
@Composable
fun SiteSwitcherDrawerContent(
    sites: List<SiteDescriptor>,
    activeSiteId: SiteId,
    pinnedSiteIds: List<SiteId>,
    onSelectSite: (SiteId) -> Unit,
    onTogglePinSite: (SiteId) -> Unit,
    modifier: Modifier = Modifier,
    editPinsMode: Boolean = false,
    siteOpenCounts: Map<SiteId, Int> = emptyMap(),
    startupDefaultSiteId: SiteId? = null,
    startupSiteMode: Boolean = false,
    dynamicSiteSorting: Boolean = true,
    onDynamicSiteSortingChange: (Boolean) -> Unit = {},
    onReorderPinnedSites: (List<SiteId>) -> Unit = {},
    onToggleStartupSiteMode: () -> Unit = {},
    onSelectStartupDefaultSite: (SiteId?) -> Unit = {},
) {
    val colors = LocalRavenColors.current
    var pinnedExpanded by rememberSaveable { mutableStateOf(true) }
    var siteFilterQuery by rememberSaveable { mutableStateOf("") }

    val pinnedSet = remember(pinnedSiteIds) { pinnedSiteIds.toSet() }
    val sitesById = remember(sites) { sites.associateBy { it.id } }
    val startupDefaultSiteName = remember(startupDefaultSiteId, sitesById) {
        startupDefaultSiteId?.let { id -> sitesById[id]?.shortName ?: id.value }
    }
    val normalizedFilter = siteFilterQuery.trim()
    val visibleSites = remember(sites, normalizedFilter) {
        if (normalizedFilter.isEmpty()) {
            sites
        } else {
            sites.filter { it.matchesSwitcherFilter(normalizedFilter) }
        }
    }
    // Usage-sorted projection of the pinned ids (stable base order when usage ranking is disabled).
    val pinnedSites = remember(pinnedSiteIds, sitesById, siteOpenCounts, normalizedFilter, dynamicSiteSorting) {
        val resolved = pinnedSiteIds.mapNotNull { sitesById[it] }
            .filter { normalizedFilter.isEmpty() || it.matchesSwitcherFilter(normalizedFilter) }
        if (!dynamicSiteSorting || siteOpenCounts.isEmpty()) {
            resolved
        } else {
            resolved.sortedByDescending { siteOpenCounts[it.id] ?: 0 }
        }
    }
    // Live drag order; only used while a reorder gesture is in flight.
    var dragOrder by remember(pinnedSiteIds) { mutableStateOf<List<SiteId>?>(null) }
    val displayedPinnedSites = remember(pinnedSites, dragOrder, sitesById) {
        val order = dragOrder
        if (order == null) pinnedSites
        else order.mapNotNull { sitesById[it] }
            .filter { normalizedFilter.isEmpty() || it.matchesSwitcherFilter(normalizedFilter) }
    }
    val orderedGroups = remember(visibleSites, siteOpenCounts, dynamicSiteSorting) {
        val baseGroups = listOf(
            SiteSwitcherGroupSpec(MediaKind.VIDEO, "视频", Icons.Outlined.Movie, visibleSites.filter { it.primaryKind == MediaKind.VIDEO }),
            SiteSwitcherGroupSpec(MediaKind.COMIC, "漫画", Icons.Outlined.MenuBook, visibleSites.filter { it.primaryKind == MediaKind.COMIC }),
            SiteSwitcherGroupSpec(MediaKind.SERIAL_COMIC, "连载漫画", Icons.Outlined.AutoStories, visibleSites.filter { it.primaryKind == MediaKind.SERIAL_COMIC }),
            SiteSwitcherGroupSpec(MediaKind.PHOTO, "写真", Icons.Outlined.PhotoLibrary, visibleSites.filter { it.primaryKind == MediaKind.PHOTO }),
        ).mapNotNull { spec ->
            if (spec.sites.isEmpty()) null
            else {
                val sortedSites = if (!dynamicSiteSorting || siteOpenCounts.isEmpty()) spec.sites
                else spec.sites.sortedByDescending { siteOpenCounts[it.id] ?: 0 }
                spec.copy(sites = sortedSites)
            }
        }
        if (!dynamicSiteSorting || siteOpenCounts.isEmpty()) {
            baseGroups
        } else {
            baseGroups.sortedByDescending { spec ->
                spec.sites.sumOf { (siteOpenCounts[it.id] ?: 0).toLong() }
            }
        }
    }
    val showPinnedSection = if (normalizedFilter.isEmpty()) {
        displayedPinnedSites.isNotEmpty() || editPinsMode
    } else {
        displayedPinnedSites.isNotEmpty()
    }
    val reorderEnabled = !dynamicSiteSorting && normalizedFilter.isEmpty() && !startupSiteMode

    BoxWithConstraints(
        modifier = modifier.testTag("site_switcher_content"),
    ) {
        val columns = computeSiteSwitcherColumns(maxWidth.value.toInt())

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (sites.size > 12) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("site_switcher_filter"),
                ) {
                    RavenSearchField(
                        query = siteFilterQuery,
                        onQueryChange = { siteFilterQuery = it },
                        placeholder = "搜索站点名称或域名",
                        onClear = { siteFilterQuery = "" },
                    )
                }
            }

            StartupDefaultSiteActionBar(
                startupDefaultSiteName = startupDefaultSiteName,
                hasCustomStartupSite = startupDefaultSiteId != null,
                startupSiteMode = startupSiteMode,
                onToggleMode = onToggleStartupSiteMode,
                onResetDefault = { onSelectStartupDefaultSite(null) },
            )

            SiteOrderingModeRow(
                dynamicSiteSorting = dynamicSiteSorting,
                pinnedCount = pinnedSiteIds.size,
                onDynamicSiteSortingChange = onDynamicSiteSortingChange,
            )

            if (showPinnedSection) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RavenShapes.ControlShape)
                            .clickable(role = Role.Button) { pinnedExpanded = !pinnedExpanded }
                            .padding(vertical = 4.dp)
                            .testTag("site_switcher_pinned_header"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = colors.crimson,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "常用站点",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.inkPrimary,
                                modifier = Modifier.semantics { heading() },
                            )
                            RavenStatusPill(
                                text = displayedPinnedSites.size.toString(),
                                tone = if (displayedPinnedSites.isNotEmpty()) RavenStatusTone.ACCENT else RavenStatusTone.NEUTRAL,
                            )
                            if (reorderEnabled && displayedPinnedSites.size > 1) {
                                Text(
                                    text = "长按拖动排序",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.inkTertiary,
                                    maxLines = 1,
                                    modifier = Modifier.testTag("site_switcher_reorder_hint"),
                                )
                            }
                        }

                        val motionReduced = LocalMotionReduced.current
                        val chevronRotation = animateFloatAsState(
                            targetValue = if (pinnedExpanded) 180f else 0f,
                            animationSpec = if (motionReduced) {
                                snap()
                            } else {
                                tween(durationMillis = 180, easing = RavenMotion.HarmonyDecelerationEasing)
                            },
                            label = "siteSwitcherPinnedChevron",
                        )
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = if (pinnedExpanded) "收起常用站点" else "展开常用站点",
                            tint = colors.inkSecondary,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer { rotationZ = chevronRotation.value },
                        )
                    }

                    val motionReduced = LocalMotionReduced.current
                    AnimatedVisibility(
                        visible = pinnedExpanded,
                        enter = if (motionReduced) {
                            EnterTransition.None
                        } else {
                            expandVertically(tween(200, easing = RavenMotion.HarmonyDecelerationEasing)) +
                                fadeIn(tween(160, easing = RavenMotion.HarmonyDecelerationEasing))
                        },
                        exit = if (motionReduced) {
                            ExitTransition.None
                        } else {
                            shrinkVertically(tween(170, easing = RavenMotion.HarmonyDecelerationEasing)) +
                                fadeOut(tween(120, easing = RavenMotion.HarmonyDecelerationEasing))
                        },
                    ) {
                        if (displayedPinnedSites.isEmpty()) {
                            Text(
                                text = "点击下方任意站点即可加入常用置顶",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkTertiary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        } else {
                            SiteSwitcherReorderablePinnedGrid(
                                sites = displayedPinnedSites,
                                columns = columns,
                                activeSiteId = activeSiteId,
                                startupDefaultSiteId = startupDefaultSiteId,
                                pinnedSet = pinnedSet,
                                editPinsMode = editPinsMode,
                                startupSiteMode = startupSiteMode,
                                reorderEnabled = reorderEnabled && displayedPinnedSites.size > 1,
                                onSelectSite = onSelectSite,
                                onSelectStartupDefaultSite = { siteId -> onSelectStartupDefaultSite(siteId) },
                                onTogglePinSite = onTogglePinSite,
                                onReorder = { nextOrder ->
                                    val reorderedIds = nextOrder.map { it.id }
                                    val nextIds = SiteOrdering.applyVisibleReorder(
                                        storedIds = SiteOrdering.ensureAllPinned(pinnedSiteIds, reorderedIds),
                                        visibleIds = displayedPinnedSites.map { it.id },
                                        reorderedVisibleIds = reorderedIds,
                                    )
                                    if (nextIds != pinnedSiteIds) onReorderPinnedSites(nextIds)
                                },
                                onDragOrderChanged = { order -> dragOrder = order },
                            )
                        }
                    }
                }
            }

            if (normalizedFilter.isNotEmpty() && visibleSites.isEmpty()) {
                Text(
                    text = "没有匹配「$normalizedFilter」的站点",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .testTag("site_switcher_no_results"),
                )
            }

            orderedGroups.forEach { group ->
                SiteSwitcherGroupSection(
                    title = group.title,
                    count = group.sites.size,
                    icon = group.icon,
                    sites = group.sites,
                    columns = columns,
                    activeSiteId = activeSiteId,
                    startupDefaultSiteId = startupDefaultSiteId,
                    pinnedSet = pinnedSet,
                    editPinsMode = editPinsMode,
                    startupSiteMode = startupSiteMode,
                    onSelectSite = onSelectSite,
                    onSelectStartupDefaultSite = { siteId -> onSelectStartupDefaultSite(siteId) },
                    onTogglePinSite = onTogglePinSite,
                )
            }
        }
    }
}

@Composable
private fun SiteOrderingModeRow(
    dynamicSiteSorting: Boolean,
    pinnedCount: Int,
    onDynamicSiteSortingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RavenShapes.ControlShape)
            .background(colors.surface1)
            .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
            .toggleable(
                value = dynamicSiteSorting,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { next ->
                    feedback.perform(
                        if (next) RavenFeedbackEffect.TOGGLE_ON else RavenFeedbackEffect.TOGGLE_OFF,
                    )
                    onDynamicSiteSortingChange(next)
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("site_switcher_dynamic_sorting_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.SwapVert,
            contentDescription = null,
            tint = colors.inkSecondary,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "按使用频率排序",
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (dynamicSiteSorting) {
                    "常打开的站点自动排在前面"
                } else if (pinnedCount > 1) {
                    "按手动顺序固定展示 · 常用站点可长按拖动排序"
                } else {
                    "按站点注册顺序固定展示"
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = dynamicSiteSorting,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.inkPrimary,
                checkedTrackColor = colors.crimson,
                checkedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                uncheckedThumbColor = colors.inkSecondary,
                uncheckedTrackColor = colors.surface3,
                uncheckedBorderColor = colors.outlineSubtle,
            ),
        )
    }
}

/**
 * Pinned tile grid that supports long-press drag reordering while keeping the current
 * `verticalScroll` sheet layout. Tiles live inside fixed rows of [columns]; a drag is translated
 * into a source/target slot index and committed on release. Screen-reader users get
 * "move up / move down" actions on each tile so reordering stays reachable without a drag.
 */
@Composable
private fun SiteSwitcherReorderablePinnedGrid(
    sites: List<SiteDescriptor>,
    columns: Int,
    activeSiteId: SiteId,
    startupDefaultSiteId: SiteId?,
    pinnedSet: Set<SiteId>,
    editPinsMode: Boolean,
    startupSiteMode: Boolean,
    reorderEnabled: Boolean,
    onSelectSite: (SiteId) -> Unit,
    onSelectStartupDefaultSite: (SiteId) -> Unit,
    onTogglePinSite: (SiteId) -> Unit,
    onReorder: (List<SiteDescriptor>) -> Unit,
    onDragOrderChanged: (List<SiteId>?) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rowHeightPx = remember(density) { with(density) { 56.dp.toPx() } }
    val columnWidthPx = remember(density) { with(density) { 96.dp.toPx() } }

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    val safeColumns = columns.coerceAtLeast(1)
    val rows = remember(sites, safeColumns) { sites.chunked(safeColumns) }

    fun commit() {
        val from = draggingIndex
        if (from != null) {
            val safeFrom = from.coerceIn(0, sites.lastIndex)
            val targetRow = (safeFrom / safeColumns + (dragOffset.y / rowHeightPx).roundToInt())
                .coerceIn(0, rows.lastIndex)
            val targetCol = (safeFrom % safeColumns + (dragOffset.x / columnWidthPx).roundToInt())
                .coerceIn(0, safeColumns - 1)
            val target = (targetRow * safeColumns + targetCol).coerceIn(0, sites.lastIndex)
            if (target != safeFrom) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onReorder(SiteOrdering.moveGeneric(sites, safeFrom, target))
            }
        }
        draggingIndex = null
        dragOffset = Offset.Zero
        onDragOrderChanged(null)
    }

    // Accessibility fallback: expose move up/down per tile without breaking pin toggles.
    fun moveBySlots(site: SiteDescriptor, delta: Int) {
        val from = sites.indexOfFirst { it.id == site.id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, sites.lastIndex)
        if (to != from) onReorder(SiteOrdering.moveGeneric(sites, from, to))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { rowIndex, rowSites ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowSites.forEachIndexed { colIndex, site ->
                    val flatIndex = rowIndex * safeColumns + colIndex
                    val isDragging = draggingIndex == flatIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y
                                    scaleX = 1.04f
                                    scaleY = 1.04f
                                    shadowElevation = with(density) { 8.dp.toPx() }
                                }
                            }
                            .then(
                                if (reorderEnabled) {
                                    Modifier.pointerInput(sites, safeColumns, rowHeightPx, columnWidthPx) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingIndex = flatIndex
                                                dragOffset = Offset.Zero
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onDragOrderChanged(sites.map { it.id })
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount
                                            },
                                            onDragEnd = { commit() },
                                            onDragCancel = {
                                                draggingIndex = null
                                                dragOffset = Offset.Zero
                                                onDragOrderChanged(null)
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        SiteSwitcherTile(
                            site = site,
                            isSelected = site.id == activeSiteId,
                            isStartupDefault = site.id == startupDefaultSiteId,
                            isPinned = site.id in pinnedSet,
                            editPinsMode = editPinsMode,
                            startupSiteMode = startupSiteMode,
                            onClick = {
                                when {
                                    startupSiteMode -> onSelectStartupDefaultSite(site.id)
                                    editPinsMode -> onTogglePinSite(site.id)
                                    else -> onSelectSite(site.id)
                                }
                            },
                            onLongClick = {
                                // Reorder mode owns long-press for drag; otherwise it toggles the pin.
                                if (!startupSiteMode && !reorderEnabled) onTogglePinSite(site.id)
                            },
                            accessibilityReorderActions = if (reorderEnabled) {
                                buildList {
                                    val index = sites.indexOfFirst { it.id == site.id }
                                    if (index > 0) {
                                        add(
                                            CustomAccessibilityAction("上移 ${site.shortName}") {
                                                moveBySlots(site, -1)
                                                true
                                            },
                                        )
                                    }
                                    if (index in 0 until sites.lastIndex) {
                                        add(
                                            CustomAccessibilityAction("下移 ${site.shortName}") {
                                                moveBySlots(site, 1)
                                                true
                                            },
                                        )
                                    }
                                }
                            } else {
                                emptyList()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                repeat(safeColumns - rowSites.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StartupDefaultSiteActionBar(
    startupDefaultSiteName: String?,
    hasCustomStartupSite: Boolean,
    startupSiteMode: Boolean,
    onToggleMode: () -> Unit,
    onResetDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor = if (startupSiteMode) colors.surface3 else colors.surface1
    val borderColor = if (startupSiteMode) colors.crimson.copy(alpha = 0.72f) else colors.hairline

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RavenShapes.ControlShape)
            .background(containerColor)
            .border(
                width = if (startupSiteMode) 1.dp else 0.5.dp,
                color = borderColor,
                shape = RavenShapes.ControlShape,
            )
            .ravenSpringPress(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onToggleMode,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("site_switcher_startup_site_button"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val startupActive = startupSiteMode || hasCustomStartupSite
        RavenMorphToggleIcon(
            active = startupActive,
            activeGlyph = RavenMorphIcons.HomeActive,
            inactiveGlyph = RavenMorphIcons.HomeOutline,
            activeTint = colors.crimson,
            inactiveTint = colors.inkSecondary,
            size = 18.dp,
            springPreset = MorphSpringPreset.SNAPPY,
            scaleKick = false,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "设置APP启动页",
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (startupDefaultSiteName != null) {
                    "当前：$startupDefaultSiteName"
                } else {
                    "当前：记住上次打开"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (startupDefaultSiteName != null) colors.inkSecondary else colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (startupSiteMode && hasCustomStartupSite) {
            TextButton(
                onClick = onResetDefault,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .testTag("site_switcher_startup_reset_button"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "恢复默认",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.crimson,
                )
            }
        } else if (startupSiteMode) {
            RavenStatusPill(text = "请选择站点", tone = RavenStatusTone.ACCENT)
        } else {
            RavenStatusPill(
                text = startupDefaultSiteName ?: "选择站点",
                tone = if (hasCustomStartupSite) RavenStatusTone.ACCENT else RavenStatusTone.NEUTRAL,
            )
        }
    }
}

@Composable
private fun SiteSwitcherGroupSection(
    title: String,
    count: Int,
    icon: ImageVector,
    sites: List<SiteDescriptor>,
    columns: Int,
    activeSiteId: SiteId,
    startupDefaultSiteId: SiteId?,
    pinnedSet: Set<SiteId>,
    editPinsMode: Boolean,
    startupSiteMode: Boolean,
    onSelectSite: (SiteId) -> Unit,
    onSelectStartupDefaultSite: (SiteId) -> Unit,
    onTogglePinSite: (SiteId) -> Unit,
) {
    val colors = LocalRavenColors.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 2.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.inkSecondary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkSecondary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = colors.inkTertiary,
            )
        }

        SiteSwitcherCompactGrid(
            sites = sites,
            columns = columns,
            activeSiteId = activeSiteId,
            startupDefaultSiteId = startupDefaultSiteId,
            pinnedSet = pinnedSet,
            editPinsMode = editPinsMode,
            startupSiteMode = startupSiteMode,
            onSelectSite = onSelectSite,
            onSelectStartupDefaultSite = onSelectStartupDefaultSite,
            onTogglePinSite = onTogglePinSite,
        )
    }
}

@Composable
private fun SiteSwitcherCompactGrid(
    sites: List<SiteDescriptor>,
    columns: Int,
    activeSiteId: SiteId,
    startupDefaultSiteId: SiteId?,
    pinnedSet: Set<SiteId>,
    editPinsMode: Boolean,
    startupSiteMode: Boolean,
    onSelectSite: (SiteId) -> Unit,
    onSelectStartupDefaultSite: (SiteId) -> Unit,
    onTogglePinSite: (SiteId) -> Unit,
) {
    val rows = remember(sites, columns) { sites.chunked(columns.coerceAtLeast(1)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowSites ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowSites.forEach { site ->
                    val isSelected = site.id == activeSiteId
                    val isStartupDefault = site.id == startupDefaultSiteId
                    val isPinned = site.id in pinnedSet
                    SiteSwitcherTile(
                        site = site,
                        isSelected = isSelected,
                        isStartupDefault = isStartupDefault,
                        isPinned = isPinned,
                        editPinsMode = editPinsMode,
                        startupSiteMode = startupSiteMode,
                        onClick = {
                            when {
                                startupSiteMode -> onSelectStartupDefaultSite(site.id)
                                editPinsMode -> onTogglePinSite(site.id)
                                else -> onSelectSite(site.id)
                            }
                        },
                        onLongClick = {
                            if (!startupSiteMode) onTogglePinSite(site.id)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowSites.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SiteSwitcherTile(
    site: SiteDescriptor,
    isSelected: Boolean,
    isStartupDefault: Boolean,
    isPinned: Boolean,
    editPinsMode: Boolean,
    startupSiteMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    accessibilityReorderActions: List<CustomAccessibilityAction> = emptyList(),
) {
    val colors = LocalRavenColors.current
    val motionReduced = LocalMotionReduced.current
    val interactionSource = remember { MutableInteractionSource() }
    val accessNote = site.accessNote
    val requiresAuth = site.networkProfile.requiresAuthGate || site.hasCapability(SiteCapability.AUTH)
    val colorSpec = if (motionReduced) {
        snap<androidx.compose.ui.graphics.Color>()
    } else {
        tween(durationMillis = RavenMotion.CHIP_TRANSITION_DURATION_MS, easing = RavenMotion.HarmonyDecelerationEasing)
    }

    val containerColor by animateColorAsState(
        targetValue = when {
            startupSiteMode && isStartupDefault -> colors.surface3
            isSelected -> colors.surface3
            else -> colors.surface1
        },
        animationSpec = colorSpec,
        label = "siteTileContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> colors.crimson.copy(alpha = 0.72f)
            startupSiteMode && isStartupDefault -> colors.crimson.copy(alpha = 0.72f)
            isStartupDefault -> colors.rimHighlight
            editPinsMode && isPinned -> colors.rimHighlight
            else -> colors.hairline
        },
        animationSpec = colorSpec,
        label = "siteTileBorder",
    )

    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RavenShapes.ControlShape)
            .background(containerColor)
            .border(
                width = if (isSelected) 1.dp else 0.5.dp,
                color = borderColor,
                shape = RavenShapes.ControlShape,
            )
            .semantics {
                if (accessibilityReorderActions.isNotEmpty()) {
                    customActions = accessibilityReorderActions
                }
            }
            .ravenSpringPress(interactionSource = interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("site_tile_${site.id.value}"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.crimson),
                )
            }
            Text(
                text = site.shortName,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                ),
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (editPinsMode || isPinned) {
                RavenMorphIcon(
                    glyph = if (isPinned) RavenMorphIcons.PinFilled else RavenMorphIcons.PinOutline,
                    contentDescription = if (isPinned) "已置顶" else "置顶",
                    tint = if (isPinned) colors.crimson else colors.inkTertiary,
                    size = 14.dp,
                    springPreset = MorphSpringPreset.SNAPPY,
                )
            } else if (requiresAuth && site.networkProfile.requiresAuthGate) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "需要登录",
                    tint = colors.warning,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        val secondaryLine = accessNote?.takeIf { it.isNotBlank() } ?: site.defaultHost
        if (secondaryLine.isNotBlank() || isStartupDefault) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (secondaryLine.isNotBlank()) {
                    Text(
                        text = secondaryLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!accessNote.isNullOrBlank()) colors.inkSecondary else colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (isStartupDefault) {
                    RavenStatusPill(
                        text = "启动页",
                        tone = RavenStatusTone.ACCENT,
                    )
                }
            }
        }
    }
}
