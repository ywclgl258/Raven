package com.raven.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenColors
import com.raven.app.core.designsystem.theme.RavenFrostedBox
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenSpringPress
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteDescriptor

/**
 * The 5 primary top-level navigation destinations in Raven.
 */
enum class RavenTopDestination(
    val label: String,
    val route: RavenRoute,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
) {
    HOME(
        label = "首页",
        route = RavenRoute.Home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_home",
    ),
    EXPLORE(
        label = "发现",
        route = RavenRoute.Explore,
        selectedIcon = Icons.Filled.Explore,
        unselectedIcon = Icons.Outlined.Explore,
        testTag = "nav_explore",
    ),
    SEARCH(
        label = "搜索",
        route = RavenRoute.Search(),
        selectedIcon = Icons.Filled.Search,
        unselectedIcon = Icons.Outlined.Search,
        testTag = "nav_search",
    ),
    LIBRARY(
        label = "片库",
        route = RavenRoute.Library,
        selectedIcon = Icons.Filled.VideoLibrary,
        unselectedIcon = Icons.Outlined.VideoLibrary,
        testTag = "nav_library",
    ),
    PROFILE(
        label = "我的",
        route = RavenRoute.Profile,
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person,
        testTag = "nav_profile",
    );

    fun matchesRoute(current: RavenRoute?): Boolean = when (this) {
        HOME -> current is RavenRoute.Home
        EXPLORE -> current is RavenRoute.Explore
        SEARCH -> current is RavenRoute.Search
        LIBRARY -> current is RavenRoute.Library
        PROFILE -> current is RavenRoute.Profile
    }
}

/** Breakpoint threshold in dp at which Raven switches from bottom bar to persistent left NavigationRail. */
const val RAVEN_RAIL_BREAKPOINT_DP: Int = 600

/** Pure helper determining whether a window width should display the persistent left NavigationRail. */
fun isRavenRailWidth(widthDp: Int): Boolean = widthDp >= RAVEN_RAIL_BREAKPOINT_DP

/**
 * Adaptive primary navigation shell built on Material 3 `NavigationSuiteScaffold`.
 *
 * - `< 600dp`: Sleek OLED dark bottom bar (`Surface1`) with crisp top [RavenColors.Hairline] and
 *   [RavenColors.Crimson] active indicator & icon tint.
 * - `>= 600dp`: Automatically switches to persistent left `NavigationRail` with vertical `Hairline`.
 * - Set [showNavigation] to `false` on full-screen sub-routes (e.g. Reader / GalleryDetail).
 */
@Composable
fun RavenAdaptiveScaffold(
    currentDestination: RavenTopDestination,
    onDestinationSelected: (RavenTopDestination) -> Unit,
    modifier: Modifier = Modifier,
    showNavigation: Boolean = true,
    badges: Map<RavenTopDestination, Int> = emptyMap(),
    content: @Composable () -> Unit,
) {
    val colors = LocalRavenColors.current
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val layoutType = when {
        !showNavigation -> NavigationSuiteType.None
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
            NavigationSuiteType.NavigationRail
        else -> NavigationSuiteType.NavigationBar
    }

    val navItemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = colors.crimson,
            selectedTextColor = colors.crimson,
            indicatorColor = colors.crimson.copy(alpha = 0.14f),
            unselectedIconColor = colors.inkSecondary,
            unselectedTextColor = colors.inkSecondary,
            disabledIconColor = colors.inkTertiary,
            disabledTextColor = colors.inkTertiary,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = colors.crimson,
            selectedTextColor = colors.crimson,
            indicatorColor = colors.crimson.copy(alpha = 0.14f),
            unselectedIconColor = colors.inkSecondary,
            unselectedTextColor = colors.inkSecondary,
            disabledIconColor = colors.inkTertiary,
            disabledTextColor = colors.inkTertiary,
        ),
        navigationDrawerItemColors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = colors.crimson.copy(alpha = 0.14f),
            unselectedContainerColor = Color.Transparent,
            selectedIconColor = colors.crimson,
            unselectedIconColor = colors.inkSecondary,
            selectedTextColor = colors.crimson,
            unselectedTextColor = colors.inkSecondary,
        ),
    )

    NavigationSuiteScaffold(
        modifier = modifier.testTag("raven_adaptive_scaffold"),
        layoutType = layoutType,
        containerColor = colors.canvas,
        contentColor = colors.inkPrimary,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = colors.surface1,
            navigationBarContentColor = colors.inkPrimary,
            navigationRailContainerColor = colors.surface1,
            navigationRailContentColor = colors.inkPrimary,
            navigationDrawerContainerColor = colors.surface1,
            navigationDrawerContentColor = colors.inkPrimary,
        ),
        navigationSuiteItems = {
            RavenTopDestination.entries.forEach { destination ->
                val selected = destination == currentDestination
                val badgeCount = badges[destination] ?: 0
                item(
                    selected = selected,
                    onClick = { onDestinationSelected(destination) },
                    modifier = Modifier.testTag(destination.testTag),
                    icon = {
                        Icon(
                            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                            contentDescription = destination.label,
                        )
                    },
                    label = {
                        Text(
                            text = destination.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    badge = if (badgeCount > 0) {
                        {
                            Badge(
                                containerColor = colors.crimson,
                                contentColor = colors.inkPrimary,
                            ) {
                                Text(
                                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                    style = RavenTabularTextStyle.copy(
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    colors = navItemColors,
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            when (layoutType) {
                NavigationSuiteType.NavigationBar -> {
                    HorizontalDivider(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        thickness = 0.5.dp,
                        color = colors.hairline,
                    )
                }
                NavigationSuiteType.NavigationRail -> {
                    VerticalDivider(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight(),
                        thickness = 0.5.dp,
                        color = colors.hairline,
                    )
                }
                else -> Unit
            }
        }
    }
}

/**
 * Refined OLED / frosted top app bar with Apple HIG spacing, 200% font scale resilience,
 * and clean bottom hairline separator.
 */
@Composable
fun RavenTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    frosted: Boolean = false,
    showBottomHairline: Boolean = true,
    navigationIcon: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current

    val barBody: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (navigationIcon != null) {
                    navigationIcon()
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    when {
                        titleContent != null -> titleContent()
                        !title.isNullOrBlank() -> {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { heading() },
                                )
                                if (!subtitle.isNullOrBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.inkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    content = actions,
                )
            }
            if (showBottomHairline) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = colors.hairline,
                )
            }
        }
    }

    if (frosted) {
        RavenFrostedBox(
            modifier = modifier.fillMaxWidth(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            reduceTransparency = reduceTransparency,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
        ) {
            barBody()
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.canvas),
        ) {
            barBody()
        }
    }
}

/**
 * Compact site-switcher pill displaying a subtle [MediaKind] indicator, the active site's
 * [SiteDescriptor.shortName], and a dropdown chevron inside a guaranteed `>= 48dp` touch target.
 */
@Composable
fun RavenSiteSwitcherPill(
    site: SiteDescriptor,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    frosted: Boolean = false,
) {
    RavenSiteSwitcherPill(
        shortName = site.shortName,
        mediaKind = site.primaryKind,
        onClick = onClick,
        modifier = modifier,
        frosted = frosted,
    )
}

/**
 * String-based overload of [RavenSiteSwitcherPill] for lightweight states or previews.
 */
@Composable
fun RavenSiteSwitcherPill(
    shortName: String,
    mediaKind: MediaKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    frosted: Boolean = false,
) {
    val colors = LocalRavenColors.current
    val reduceTransparency = LocalReduceTransparency.current
    val interactionSource = remember { MutableInteractionSource() }
    val kindIcon = when (mediaKind) {
        MediaKind.VIDEO -> Icons.Outlined.Movie
        MediaKind.COMIC -> Icons.Outlined.MenuBook
        MediaKind.PHOTO -> Icons.Outlined.PhotoLibrary
    }
    val kindLabel = when (mediaKind) {
        MediaKind.VIDEO -> "视频站点"
        MediaKind.COMIC -> "漫画站点"
        MediaKind.PHOTO -> "写真站点"
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .ravenSpringPress(interactionSource = interactionSource)
            .semantics { contentDescription = "切换站点：$shortName（$kindLabel）" }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .testTag("raven_site_switcher_pill"),
        contentAlignment = Alignment.CenterStart,
    ) {
        val pillContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when (mediaKind) {
                                MediaKind.VIDEO -> colors.crimson
                                MediaKind.COMIC -> colors.inkPrimary
                                MediaKind.PHOTO -> colors.inkSecondary
                            },
                        ),
                )
                Icon(
                    imageVector = kindIcon,
                    contentDescription = null,
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = shortName,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (frosted) {
            RavenFrostedBox(
                shape = RavenShapes.PillShape,
                reduceTransparency = reduceTransparency,
                borderColor = colors.rimHighlight,
            ) {
                pillContent()
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RavenShapes.PillShape)
                    .background(colors.surface2)
                    .border(width = 0.5.dp, color = colors.hairline, shape = RavenShapes.PillShape),
            ) {
                pillContent()
            }
        }
    }
}

/**
 * Minimalist Raven wordmark with signature Raven Crimson dot accent.
 */
@Composable
fun RavenBrandWordmark(
    modifier: Modifier = Modifier,
    title: String = "RAVEN",
) {
    val colors = LocalRavenColors.current
    Text(
        text = buildAnnotatedString {
            append(title)
            withStyle(SpanStyle(color = colors.crimson)) {
                append(".")
            }
        },
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp,
        ),
        color = colors.inkPrimary,
        maxLines = 1,
        modifier = modifier,
    )
}
