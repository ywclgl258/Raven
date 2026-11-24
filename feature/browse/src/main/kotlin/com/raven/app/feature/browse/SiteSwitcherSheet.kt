package com.raven.app.feature.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.designsystem.theme.tabular
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId

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
) {
    var editPinsMode by rememberSaveable { mutableStateOf(false) }

    RavenBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.testTag("site_switcher_sheet"),
        title = "切换站点",
        subtitle = "共 ${sites.size} 个可用站点 · 长按或编辑可置顶常用站点",
        trailingAction = {
            TextButton(
                onClick = { editPinsMode = !editPinsMode },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("site_switcher_edit_pins_button"),
            ) {
                Text(
                    text = if (editPinsMode) "完成" else "编辑常用",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (editPinsMode) LocalRavenColors.current.crimson else LocalRavenColors.current.inkPrimary,
                )
            }
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SiteSwitcherDrawerContent(
            sites = sites,
            activeSiteId = activeSiteId,
            pinnedSiteIds = pinnedSiteIds,
            editPinsMode = editPinsMode,
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

/**
 * Reusable compact adaptive site switcher content grouped by `VIDEO (8) / COMIC (6) / PHOTO (5)`
 * with a collapsible **常用站点 (Pinned)** section.
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
) {
    val colors = LocalRavenColors.current
    var pinnedExpanded by rememberSaveable { mutableStateOf(true) }

    val pinnedSet = remember(pinnedSiteIds) { pinnedSiteIds.toSet() }
    val sitesById = remember(sites) { sites.associateBy { it.id } }
    val pinnedSites = remember(pinnedSiteIds, sitesById) {
        pinnedSiteIds.mapNotNull { sitesById[it] }
    }
    val videoSites = remember(sites) { sites.filter { it.primaryKind == MediaKind.VIDEO } }
    val comicSites = remember(sites) { sites.filter { it.primaryKind == MediaKind.COMIC } }
    val photoSites = remember(sites) { sites.filter { it.primaryKind == MediaKind.PHOTO } }

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
            if (pinnedSites.isNotEmpty() || editPinsMode) {
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
                                text = pinnedSites.size.toString(),
                                tone = if (pinnedSites.isNotEmpty()) RavenStatusTone.ACCENT else RavenStatusTone.NEUTRAL,
                            )
                        }

                        Icon(
                            imageVector = if (pinnedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (pinnedExpanded) "收起常用站点" else "展开常用站点",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    AnimatedVisibility(
                        visible = pinnedExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        if (pinnedSites.isEmpty()) {
                            Text(
                                text = "点击下方任意站点即可加入常用置顶",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkTertiary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        } else {
                            SiteSwitcherCompactGrid(
                                sites = pinnedSites,
                                columns = columns,
                                activeSiteId = activeSiteId,
                                pinnedSet = pinnedSet,
                                editPinsMode = editPinsMode,
                                onSelectSite = onSelectSite,
                                onTogglePinSite = onTogglePinSite,
                            )
                        }
                    }
                }
            }

            if (videoSites.isNotEmpty()) {
                SiteSwitcherGroupSection(
                    title = "视频",
                    count = videoSites.size,
                    icon = Icons.Outlined.Movie,
                    sites = videoSites,
                    columns = columns,
                    activeSiteId = activeSiteId,
                    pinnedSet = pinnedSet,
                    editPinsMode = editPinsMode,
                    onSelectSite = onSelectSite,
                    onTogglePinSite = onTogglePinSite,
                )
            }

            if (comicSites.isNotEmpty()) {
                SiteSwitcherGroupSection(
                    title = "漫画",
                    count = comicSites.size,
                    icon = Icons.Outlined.MenuBook,
                    sites = comicSites,
                    columns = columns,
                    activeSiteId = activeSiteId,
                    pinnedSet = pinnedSet,
                    editPinsMode = editPinsMode,
                    onSelectSite = onSelectSite,
                    onTogglePinSite = onTogglePinSite,
                )
            }

            if (photoSites.isNotEmpty()) {
                SiteSwitcherGroupSection(
                    title = "写真",
                    count = photoSites.size,
                    icon = Icons.Outlined.PhotoLibrary,
                    sites = photoSites,
                    columns = columns,
                    activeSiteId = activeSiteId,
                    pinnedSet = pinnedSet,
                    editPinsMode = editPinsMode,
                    onSelectSite = onSelectSite,
                    onTogglePinSite = onTogglePinSite,
                )
            }
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
    pinnedSet: Set<SiteId>,
    editPinsMode: Boolean,
    onSelectSite: (SiteId) -> Unit,
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
            pinnedSet = pinnedSet,
            editPinsMode = editPinsMode,
            onSelectSite = onSelectSite,
            onTogglePinSite = onTogglePinSite,
        )
    }
}

@Composable
private fun SiteSwitcherCompactGrid(
    sites: List<SiteDescriptor>,
    columns: Int,
    activeSiteId: SiteId,
    pinnedSet: Set<SiteId>,
    editPinsMode: Boolean,
    onSelectSite: (SiteId) -> Unit,
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
                    val isPinned = site.id in pinnedSet
                    SiteSwitcherTile(
                        site = site,
                        isSelected = isSelected,
                        isPinned = isPinned,
                        editPinsMode = editPinsMode,
                        onClick = {
                            if (editPinsMode) {
                                onTogglePinSite(site.id)
                            } else {
                                onSelectSite(site.id)
                            }
                        },
                        onLongClick = { onTogglePinSite(site.id) },
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
    isPinned: Boolean,
    editPinsMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val accessNote = site.accessNote
    val requiresAuth = site.networkProfile.requiresAuthGate || site.hasCapability(SiteCapability.AUTH)

    val containerColor = when {
        isSelected -> colors.surface3
        else -> colors.surface1
    }
    val borderColor = when {
        isSelected -> colors.crimson.copy(alpha = 0.72f)
        editPinsMode && isPinned -> colors.rimHighlight
        else -> colors.hairline
    }

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
                Icon(
                    imageVector = if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (isPinned) "已置顶" else "置顶",
                    tint = if (isPinned) colors.crimson else colors.inkTertiary,
                    modifier = Modifier.size(14.dp),
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
        if (secondaryLine.isNotBlank()) {
            Text(
                text = secondaryLine,
                style = MaterialTheme.typography.labelSmall,
                color = if (!accessNote.isNullOrBlank()) colors.inkSecondary else colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
