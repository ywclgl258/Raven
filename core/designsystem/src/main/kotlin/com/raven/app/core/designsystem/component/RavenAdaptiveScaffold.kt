package com.raven.app.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.LocalRavenFeedback
import com.raven.app.core.designsystem.theme.LocalReduceTransparency
import com.raven.app.core.designsystem.theme.RavenColors
import com.raven.app.core.designsystem.theme.RavenFeedbackEffect
import com.raven.app.core.designsystem.theme.RavenFrostedBox
import com.raven.app.core.designsystem.theme.RavenMaterialTier
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.designsystem.theme.RavenTabularTextStyle
import com.raven.app.core.designsystem.theme.ravenInteractiveLight
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

/** Breakpoint threshold in dp at which Raven expands into a permanent NavigationDrawer. */
const val RAVEN_DRAWER_BREAKPOINT_DP: Int = 1200

/** Pure helper determining whether a window width should display the persistent left NavigationRail. */
fun isRavenRailWidth(widthDp: Int): Boolean = widthDp in RAVEN_RAIL_BREAKPOINT_DP until RAVEN_DRAWER_BREAKPOINT_DP

/** Pure helper determining whether a window width should display the expanded NavigationDrawer. */
fun isRavenDrawerWidth(widthDp: Int): Boolean = widthDp >= RAVEN_DRAWER_BREAKPOINT_DP

/**
 * Adaptive primary navigation shell built on Material 3 `NavigationSuiteScaffold`.
 *
 * - `< 600dp`: Sleek OLED dark bottom bar (`Surface1`) with crisp top [RavenColors.Hairline] and
 *   [RavenColors.Crimson] active indicator & icon tint.
 * - `600dp..1199dp`: Persistent left `NavigationRail` with vertical `Hairline`.
 * - `>= 1200dp`: Permanent `NavigationDrawer` with expanded labels.
 * - Set [showNavigation] to `false` on full-screen sub-routes (e.g. Reader / GalleryDetail).
 */
@Composable
fun RavenAdaptiveScaffold(
    currentDestination: RavenTopDestination,
    onDestinationSelected: (RavenTopDestination) -> Unit,
    modifier: Modifier = Modifier,
    showNavigation: Boolean = true,
    badges: Map<RavenTopDestination, Int> = emptyMap(),
    isIncognito: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LocalRavenColors.current
    val feedback = LocalRavenFeedback.current
    val reduceTransparency = LocalReduceTransparency.current
    val motionReduced = LocalMotionReduced.current
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val layoutType = when {
        !showNavigation -> NavigationSuiteType.None
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(RAVEN_DRAWER_BREAKPOINT_DP) ->
            NavigationSuiteType.NavigationDrawer
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
            NavigationSuiteType.NavigationRail
        else -> NavigationSuiteType.NavigationBar
    }

    val navSurfaceColor = if (reduceTransparency) colors.surface1 else colors.floatingBarScrim
    val navItemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = colors.crimson,
            selectedTextColor = colors.crimson,
            indicatorColor = colors.crimson.copy(alpha = 0.16f),
            unselectedIconColor = colors.inkSecondary,
            unselectedTextColor = colors.inkSecondary,
            disabledIconColor = colors.inkTertiary,
            disabledTextColor = colors.inkTertiary,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = colors.crimson,
            selectedTextColor = colors.crimson,
            indicatorColor = colors.crimson.copy(alpha = 0.16f),
            unselectedIconColor = colors.inkSecondary,
            unselectedTextColor = colors.inkSecondary,
            disabledIconColor = colors.inkTertiary,
            disabledTextColor = colors.inkTertiary,
        ),
        navigationDrawerItemColors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = colors.crimson.copy(alpha = 0.16f),
            unselectedContainerColor = Color.Transparent,
            selectedIconColor = colors.crimson,
            unselectedIconColor = colors.inkSecondary,
            selectedTextColor = colors.crimson,
            unselectedTextColor = colors.inkSecondary,
        ),
    )

    val horizontalSpecularHairline = remember(colors.hairline, colors.lightRimTop) {
        Brush.horizontalGradient(
            colors = listOf(colors.hairline, colors.lightRimTop, colors.hairline),
        )
    }
    val verticalSpecularHairline = remember(colors.hairline, colors.lightRimTop) {
        Brush.verticalGradient(
            colors = listOf(colors.hairline, colors.lightRimTop, colors.hairline),
        )
    }

    NavigationSuiteScaffold(
        modifier = modifier.testTag("raven_adaptive_scaffold"),
        layoutType = layoutType,
        containerColor = colors.canvas,
        contentColor = colors.inkPrimary,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = navSurfaceColor,
            navigationBarContentColor = colors.inkPrimary,
            navigationRailContainerColor = navSurfaceColor,
            navigationRailContentColor = colors.inkPrimary,
            navigationDrawerContainerColor = navSurfaceColor,
            navigationDrawerContentColor = colors.inkPrimary,
        ),
        navigationSuiteItems = {
            RavenTopDestination.entries.forEach { destination ->
                val selected = destination == currentDestination
                val badgeCount = badges[destination] ?: 0
                item(
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            feedback.perform(RavenFeedbackEffect.TAB_SWITCH)
                        }
                        onDestinationSelected(destination)
                    },
                    modifier = Modifier.testTag(destination.testTag),
                    icon = {
                        RavenNavDestinationIcon(
                            destination = destination,
                            selected = selected,
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
                            val badgeText = if (badgeCount > 99) "99+" else badgeCount.toString()
                            Badge(
                                containerColor = colors.crimson,
                                contentColor = colors.inkPrimary,
                            ) {
                                AnimatedContent(
                                    targetState = badgeText,
                                    transitionSpec = {
                                        if (motionReduced) {
                                            fadeIn(snap()) togetherWith fadeOut(snap())
                                        } else {
                                            (fadeIn(tween(140)) + scaleIn(initialScale = 0.78f, animationSpec = RavenMotion.interactiveSpringSpec()))
                                                .togetherWith(fadeOut(tween(100)) + scaleOut(targetScale = 0.78f))
                                        }
                                    },
                                    label = "ravenNavBadgeCount",
                                ) { countLabel ->
                                    Text(
                                        text = countLabel,
                                        style = RavenTabularTextStyle.copy(
                                            fontSize = 10.sp,
                                            lineHeight = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    )
                                }
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
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = isIncognito,
                    enter = if (motionReduced) {
                        fadeIn(snap())
                    } else {
                        expandVertically(
                            animationSpec = tween(
                                durationMillis = 200,
                                easing = RavenMotion.HarmonyDecelerationEasing,
                            ),
                        ) + fadeIn(
                            animationSpec = tween(
                                durationMillis = 180,
                                easing = RavenMotion.HarmonyDecelerationEasing,
                            ),
                        )
                    },
                    exit = if (motionReduced) {
                        fadeOut(snap())
                    } else {
                        shrinkVertically(
                            animationSpec = tween(
                                durationMillis = 180,
                                easing = RavenMotion.HarmonyDecelerationEasing,
                            ),
                        ) + fadeOut(
                            animationSpec = tween(
                                durationMillis = 140,
                                easing = RavenMotion.HarmonyDecelerationEasing,
                            ),
                        )
                    },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surface2)
                                .statusBarsPadding()
                                .padding(horizontal = 14.dp, vertical = 5.dp)
                                .testTag("raven_incognito_strip"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(colors.crimson),
                            )
                            Text(
                                text = "无痕模式已开启 · 暂停保存历史、搜索与浏览进度",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }
            }
            when (layoutType) {
                NavigationSuiteType.NavigationBar -> {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(horizontalSpecularHairline),
                    )
                }
                NavigationSuiteType.NavigationRail,
                NavigationSuiteType.NavigationDrawer,
                -> {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(verticalSpecularHairline),
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun RavenNavDestinationIcon(
    destination: RavenTopDestination,
    selected: Boolean,
) {
    val motionReduced = LocalMotionReduced.current
    val iconScaleState = animateFloatAsState(
        targetValue = if (selected) 1.10f else 1.0f,
        animationSpec = if (motionReduced) {
            snap()
        } else {
            RavenMotion.interactiveSpringSpec()
        },
        label = "ravenNavIconScale",
    )
    Icon(
        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
        contentDescription = destination.label,
        modifier = Modifier.graphicsLayer {
            val s = iconScaleState.value
            scaleX = s
            scaleY = s
        },
    )
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
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
            tier = RavenMaterialTier.THIN,
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
    val feedback = LocalRavenFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val kindIcon = when (mediaKind) {
        MediaKind.VIDEO -> Icons.Outlined.Movie
        MediaKind.COMIC -> Icons.AutoMirrored.Outlined.MenuBook
        MediaKind.SERIAL_COMIC -> Icons.Outlined.AutoStories
        MediaKind.PHOTO -> Icons.Outlined.PhotoLibrary
    }
    val kindLabel = when (mediaKind) {
        MediaKind.VIDEO -> "视频站点"
        MediaKind.COMIC -> "漫画站点"
        MediaKind.SERIAL_COMIC -> "连载漫画站点"
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
                onClick = {
                    feedback.perform(RavenFeedbackEffect.TAP)
                    onClick()
                },
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
                                MediaKind.SERIAL_COMIC -> colors.inkPrimary
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
                borderColor = colors.lightRimTop,
                tier = RavenMaterialTier.ULTRA_THIN,
            ) {
                Box(
                    modifier = Modifier.ravenInteractiveLight(
                        interactionSource = interactionSource,
                        shape = RavenShapes.PillShape,
                        glowColor = colors.interactiveGlow,
                    ),
                ) {
                    pillContent()
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RavenShapes.PillShape)
                    .background(colors.surface2)
                    .ravenInteractiveLight(
                        interactionSource = interactionSource,
                        shape = RavenShapes.PillShape,
                        glowColor = colors.interactiveGlow,
                    )
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
