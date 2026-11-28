package com.raven.app

import android.net.Uri
import android.os.Bundle
import kotlin.reflect.typeOf
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.raven.app.core.designsystem.component.RavenAdaptiveScaffold
import com.raven.app.core.designsystem.component.RavenTopDestination
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.feature.browse.BrowseNavigator
import com.raven.app.feature.browse.ExploreScreen
import com.raven.app.feature.browse.HomeScreen
import com.raven.app.feature.browse.SearchScreen
import com.raven.app.feature.browse.SiteDirectoryScreen
import com.raven.app.feature.browse.SiteFeedScreen
import com.raven.app.feature.browse.SiteFunctionsScreen
import com.raven.app.feature.browse.toSiteFeedRoute
import com.raven.app.feature.library.DownloadsFavoritesScreen
import com.raven.app.feature.library.GlobalInboxScreen
import com.raven.app.feature.library.HistoryScreen
import com.raven.app.feature.library.ImageFavoritesScreen
import com.raven.app.feature.library.LibraryHubScreen
import com.raven.app.feature.library.LibraryNavigator
import com.raven.app.feature.library.LocalLibraryScreen
import com.raven.app.feature.library.ProfileScreen
import com.raven.app.feature.library.ShelfDetailScreen
import com.raven.app.feature.library.SmartShelfDetailScreen
import com.raven.app.feature.library.StatsScreen
import com.raven.app.feature.library.SubscriptionsScreen
import com.raven.app.feature.player.PlayerLaunchArgs
import com.raven.app.feature.reader.GalleryDetailScreen
import com.raven.app.feature.reader.GalleryDetailViewModel
import com.raven.app.feature.reader.ReaderNavigator
import com.raven.app.feature.reader.ReaderScreen
import com.raven.app.feature.reader.ReaderViewModel
import com.raven.app.feature.settings.BackupRestoreScreen
import com.raven.app.feature.settings.FeedbackScreen
import com.raven.app.feature.settings.BlockedTermsScreen
import com.raven.app.feature.settings.DuplicateScannerScreen
import com.raven.app.feature.settings.SettingsNavigator
import com.raven.app.feature.settings.SettingsScreen
import com.raven.app.feature.settings.SiteAccountScreen
import com.raven.app.feature.settings.StorageMigrationScreen
import com.raven.app.feature.settings.TagDictionaryScreen
import java.io.File
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Root Compose shell hosting [RavenAdaptiveScaffold], the complete 28-destination Type-Safe [NavHost],
 * and incoming shared-URL confirmation bottom sheet.
 */
@Composable
fun RavenAppShell(
    siteRegistry: SiteRegistry,
    hostActions: MainActivityHostActions,
    unreadInboxCount: Int,
    pendingNavigationRoute: RavenRoute?,
    onConsumeNavigationRoute: () -> Unit,
    pendingSharedLink: SharedLinkPrompt?,
    onDismissSharedLink: () -> Unit,
    modifier: Modifier = Modifier,
    isIncognito: Boolean = false,
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val activeTopDestination: RavenTopDestination? = when {
        currentDestination == null -> RavenTopDestination.HOME
        currentDestination.hasRoute<RavenRoute.Home>() -> RavenTopDestination.HOME
        currentDestination.hasRoute<RavenRoute.Explore>() -> RavenTopDestination.EXPLORE
        currentDestination.hasRoute<RavenRoute.Search>() -> RavenTopDestination.SEARCH
        currentDestination.hasRoute<RavenRoute.Library>() -> RavenTopDestination.LIBRARY
        currentDestination.hasRoute<RavenRoute.Profile>() -> RavenTopDestination.PROFILE
        else -> null
    }

    var lastTopDestination by rememberSaveable { mutableStateOf(RavenTopDestination.HOME) }
    LaunchedEffect(activeTopDestination) {
        if (activeTopDestination != null) {
            lastTopDestination = activeTopDestination
        }
    }

    val selectTopDestination: (RavenTopDestination) -> Unit = remember(navController) {
        { destination ->
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val openSearchRoute: (String?, Boolean, SiteId?) -> Unit = remember(navController, selectTopDestination) {
        { initialQuery, crossSite, sourceSiteId ->
            if (initialQuery.isNullOrBlank() && !crossSite) {
                selectTopDestination(RavenTopDestination.SEARCH)
            } else {
                navController.navigate(
                    RavenRoute.Search(
                        initialQuery = initialQuery,
                        crossSite = crossSite,
                        sourceSiteId = sourceSiteId?.value,
                    ),
                ) {
                    launchSingleTop = true
                }
            }
        }
    }

    val navigateReaderRoute: (RavenRoute.Reader) -> Unit = remember(navController) {
        { readerRoute ->
            val currentEntry = navController.currentBackStackEntry
            val previousEntry = navController.previousBackStackEntry
            val currentReader = currentEntry
                ?.takeIf { it.destination.hasRoute<RavenRoute.Reader>() }
                ?.let { entry -> runCatching { entry.toRoute<RavenRoute.Reader>() }.getOrNull() }
            val currentDetail = currentEntry
                ?.takeIf { it.destination.hasRoute<RavenRoute.GalleryDetail>() }
                ?.let { entry -> runCatching { entry.toRoute<RavenRoute.GalleryDetail>() }.getOrNull() }
            val previousDetail = previousEntry
                ?.takeIf { it.destination.hasRoute<RavenRoute.GalleryDetail>() }
                ?.let { entry -> runCatching { entry.toRoute<RavenRoute.GalleryDetail>() }.getOrNull() }
            val plan = planReaderNavigation(
                currentReader = currentReader,
                currentDetail = currentDetail,
                previousDetail = previousDetail,
                targetReader = readerRoute,
            )
            if (plan.popCurrentReader) {
                navController.popBackStack<RavenRoute.Reader>(inclusive = true)
            }
            if (plan.popStaleParentDetail) {
                navController.popBackStack<RavenRoute.GalleryDetail>(inclusive = true)
            }
            plan.insertParentDetail?.let { parent ->
                navController.navigate(parent) {
                    launchSingleTop = true
                }
            }
            navController.navigate(readerRoute) {
                launchSingleTop = true
            }
        }
    }

    // Consume incoming return-from-PlayerActivity or deep route intents cleanly after unlock.
    LaunchedEffect(pendingNavigationRoute) {
        val target = pendingNavigationRoute ?: return@LaunchedEffect
        if (target is RavenRoute.Reader) {
            navigateReaderRoute(target)
        } else {
            navController.navigate(target) {
                launchSingleTop = true
            }
        }
        onConsumeNavigationRoute()
    }

    val browseNavigator = remember(navController, hostActions, openSearchRoute, navigateReaderRoute) {
        object : BrowseNavigator {
            override fun openVideo(
                siteId: SiteId,
                mediaId: String,
                url: String,
                title: String,
                coverUrl: String,
            ) {
                hostActions.launchPlayer(
                    PlayerLaunchArgs(
                        siteId = siteId,
                        mediaId = mediaId,
                        url = url,
                        title = title,
                        coverUrl = coverUrl,
                    ),
                )
            }

            override fun openGallery(siteId: SiteId, mediaId: String, url: String) {
                navController.navigate(
                    RavenRoute.GalleryDetail(siteId = siteId.value, mediaId = mediaId, url = url),
                ) {
                    launchSingleTop = true
                }
            }

            override fun openReader(
                siteId: SiteId,
                mediaId: String,
                url: String,
                startPage: Int,
                offlineOnly: Boolean,
            ) {
                navigateReaderRoute(
                    RavenRoute.Reader(
                        siteId = siteId.value,
                        mediaId = mediaId,
                        url = url,
                        startPage = startPage.coerceAtLeast(1),
                        offlineOnly = offlineOnly,
                    ),
                )
            }

            override fun openSiteFeed(route: RavenRoute.SiteFeed) {
                navController.navigate(route) { launchSingleTop = true }
            }

            override fun openSiteDirectory(siteId: SiteId, section: String) {
                navController.navigate(
                    RavenRoute.SiteDirectory(siteId = siteId.value, section = section),
                ) {
                    launchSingleTop = true
                }
            }

            override fun openSiteFunctions(siteId: SiteId) {
                navController.navigate(RavenRoute.SiteFunctions(siteId = siteId.value)) {
                    launchSingleTop = true
                }
            }

            override fun openSearch(initialQuery: String?, crossSite: Boolean, sourceSiteId: SiteId?) {
                openSearchRoute(initialQuery, crossSite, sourceSiteId)
            }

            override fun openSiteAccount(siteId: SiteId) {
                navController.navigate(RavenRoute.SiteAccount(siteId = siteId.value)) {
                    launchSingleTop = true
                }
            }

            override fun openBlockedTerms(siteId: SiteId) {
                navController.navigate(RavenRoute.BlockedTerms(siteId = siteId.value)) {
                    launchSingleTop = true
                }
            }

            override fun openFavorites(siteId: SiteId?) {
                navController.navigate(
                    RavenRoute.Downloads(siteId = siteId?.value, initialTab = "favorites"),
                ) {
                    launchSingleTop = true
                }
            }

            override fun openHistory() {
                navController.navigate(RavenRoute.History) { launchSingleTop = true }
            }

            override fun openDownloads(siteId: SiteId?) {
                navController.navigate(RavenRoute.Downloads(siteId = siteId?.value)) {
                    launchSingleTop = true
                }
            }

            override fun openSubscriptions(siteId: SiteId?) {
                navController.navigate(RavenRoute.Subscriptions(siteId = siteId?.value)) {
                    launchSingleTop = true
                }
            }

            override fun openSettings() {
                navController.navigate(RavenRoute.Settings) { launchSingleTop = true }
            }

            override fun navigateBack() {
                navController.popBackStack()
            }
        }
    }

    val libraryNavigator = remember(navController, hostActions, openSearchRoute, navigateReaderRoute) {
        object : LibraryNavigator {
            override fun openVideo(
                siteId: SiteId,
                mediaId: String,
                url: String,
                title: String,
                coverUrl: String,
                offlineOnly: Boolean,
                queueToken: String?,
            ) {
                hostActions.launchPlayer(
                    PlayerLaunchArgs(
                        siteId = siteId,
                        mediaId = mediaId,
                        url = url,
                        title = title,
                        coverUrl = coverUrl,
                        offlineOnly = offlineOnly,
                        queueToken = queueToken,
                    ),
                )
            }

            override fun openGallery(siteId: SiteId, mediaId: String, url: String) {
                navController.navigate(
                    RavenRoute.GalleryDetail(siteId = siteId.value, mediaId = mediaId, url = url),
                )
            }

            override fun openReader(
                siteId: SiteId,
                mediaId: String,
                url: String,
                startPage: Int,
                offlineOnly: Boolean,
                queueToken: String?,
            ) {
                navigateReaderRoute(
                    RavenRoute.Reader(
                        siteId = siteId.value,
                        mediaId = mediaId,
                        url = url,
                        startPage = startPage.coerceAtLeast(1),
                        offlineOnly = offlineOnly,
                        queueToken = queueToken,
                    ),
                )
            }

            override fun openSiteFeed(route: RavenRoute.SiteFeed) {
                navController.navigate(route)
            }

            override fun openSiteDirectory(siteId: SiteId, section: String) {
                navController.navigate(
                    RavenRoute.SiteDirectory(siteId = siteId.value, section = section),
                )
            }

            override fun openSiteFunctions(siteId: SiteId) {
                navController.navigate(RavenRoute.SiteFunctions(siteId = siteId.value)) {
                    launchSingleTop = true
                }
            }

            override fun openSiteAccount(siteId: SiteId) {
                navController.navigate(RavenRoute.SiteAccount(siteId = siteId.value)) {
                    launchSingleTop = true
                }
            }

            override fun openSearch(initialQuery: String?, crossSite: Boolean, sourceSiteId: SiteId?) {
                openSearchRoute(initialQuery, crossSite, sourceSiteId)
            }

            override fun openShelfDetail(shelfId: String) {
                navController.navigate(RavenRoute.ShelfDetail(shelfId = shelfId))
            }

            override fun openSmartShelfDetail(smartShelfId: String) {
                navController.navigate(RavenRoute.SmartShelfDetail(smartShelfId = smartShelfId))
            }

            override fun openDownloads(siteId: SiteId?) {
                navController.navigate(RavenRoute.Downloads(siteId = siteId?.value)) {
                    launchSingleTop = true
                }
            }

            override fun openFavorites(siteId: SiteId?) {
                navController.navigate(
                    RavenRoute.Downloads(siteId = siteId?.value, initialTab = "favorites"),
                ) {
                    launchSingleTop = true
                }
            }

            override fun openStats() {
                navController.navigate(RavenRoute.Stats) { launchSingleTop = true }
            }

            override fun openHistory() {
                navController.navigate(RavenRoute.History) { launchSingleTop = true }
            }

            override fun openImageFavorites() {
                navController.navigate(RavenRoute.ImageFavorites) { launchSingleTop = true }
            }

            override fun openSubscriptions(siteId: SiteId?) {
                navController.navigate(RavenRoute.Subscriptions(siteId = siteId?.value)) {
                    launchSingleTop = true
                }
            }

            override fun openGlobalInbox() {
                navController.navigate(RavenRoute.GlobalInbox) { launchSingleTop = true }
            }

            override fun openLocalLibrary() {
                navController.navigate(RavenRoute.LocalLibrary) { launchSingleTop = true }
            }

            override fun openSettings() {
                navController.navigate(RavenRoute.Settings) { launchSingleTop = true }
            }

            override fun navigateBack() {
                navController.popBackStack()
            }
        }
    }

    val readerNavigator = remember(navController, siteRegistry, hostActions, navigateReaderRoute) {
        object : ReaderNavigator {
            override val fileProviderAuthority: String
                get() = hostActions.fileProviderAuthority

            override fun openReader(
                siteId: SiteId,
                mediaId: String,
                url: String,
                startPage: Int,
                offlineOnly: Boolean,
                queueToken: String?,
                parent: RavenRoute.ReaderParent?,
            ) {
                navigateReaderRoute(
                    RavenRoute.Reader(
                        siteId = siteId.value,
                        mediaId = mediaId,
                        url = url,
                        startPage = startPage.coerceAtLeast(1),
                        offlineOnly = offlineOnly,
                        queueToken = queueToken,
                        parent = parent,
                    ),
                )
            }

            override fun openVideo(
                siteId: SiteId,
                mediaId: String,
                url: String,
                title: String,
                coverUrl: String,
            ) {
                hostActions.launchPlayer(
                    PlayerLaunchArgs(
                        siteId = siteId,
                        mediaId = mediaId,
                        url = url,
                        title = title,
                        coverUrl = coverUrl,
                    ),
                )
            }

            // Mixed gallery→video queue: preserve the offline flag and queue token so the player
            // does not re-query online metadata and can continue along the same queue.
            override fun openQueueVideo(
                siteId: SiteId,
                mediaId: String,
                url: String,
                title: String,
                coverUrl: String,
                offlineOnly: Boolean,
                queueToken: String,
            ) {
                hostActions.launchPlayer(
                    PlayerLaunchArgs(
                        siteId = siteId,
                        mediaId = mediaId,
                        url = url,
                        title = title,
                        coverUrl = coverUrl,
                        offlineOnly = offlineOnly,
                        queueToken = queueToken,
                    ),
                )
            }

            override fun openGallery(
                siteId: SiteId,
                mediaId: String,
                url: String,
                offlineOnly: Boolean,
                queueToken: String?,
                parent: RavenRoute.ReaderParent?,
            ) {
                if (!queueToken.isNullOrBlank()) {
                    navigateReaderRoute(
                        RavenRoute.Reader(
                            siteId = siteId.value,
                            mediaId = mediaId,
                            url = url,
                            startPage = 1,
                            offlineOnly = offlineOnly,
                            queueToken = queueToken,
                            parent = parent,
                        ),
                    )
                } else {
                    navController.navigate(
                        RavenRoute.GalleryDetail(siteId = siteId.value, mediaId = mediaId, url = url, parent = parent),
                    )
                }
            }

            override fun openTagFeed(siteId: SiteId, tag: TagRef) {
                navController.navigate(
                    MainActivityIntentContract.resolveTagRoute(siteRegistry, siteId, tag),
                )
            }

            override fun openAuthorFeed(siteId: SiteId, author: AuthorRef) {
                navController.navigate(
                    MainActivityIntentContract.resolveAuthorFeedRoute(siteRegistry, siteId, author),
                )
            }

            override fun openTagSearch(tag: TagRef, crossSite: Boolean, sourceSiteId: SiteId?) {
                navController.navigate(tagSearchRoute(tag, crossSite, sourceSiteId)) {
                    launchSingleTop = true
                }
            }

            override fun openAuthorSearch(author: AuthorRef, crossSite: Boolean, sourceSiteId: SiteId?) {
                navController.navigate(authorSearchRoute(author, crossSite, sourceSiteId)) {
                    launchSingleTop = true
                }
            }
            override fun openSiteAccount(siteId: SiteId) {
                navController.navigate(RavenRoute.SiteAccount(siteId = siteId.value)) {
                    launchSingleTop = true
                }
            }

            override fun setSystemBarsVisible(visible: Boolean) {
                hostActions.setSystemBarsVisible(visible)
            }

            override fun setReaderBrightness(value: Float) {
                hostActions.setReaderBrightness(value)
            }

            override fun setKeepScreenOn(enabled: Boolean) {
                hostActions.setKeepScreenOn(enabled)
            }

            override fun shareFiles(uris: List<Uri>, mimeType: String, subject: String) {
                hostActions.shareFiles(uris, mimeType, subject)
            }

            override fun navigateBack() {
                navController.popBackStack()
            }
        }
    }

    val settingsNavigator = remember(navController, hostActions) {
        object : SettingsNavigator {
            override fun openSiteFunctions(siteId: SiteId) {
                navController.navigate(RavenRoute.SiteFunctions(siteId = siteId.value)) {
                    launchSingleTop = true
                }
            }

            override fun openSiteAccount(siteId: SiteId) {
                navController.navigate(RavenRoute.SiteAccount(siteId = siteId.value)) {
                    launchSingleTop = true
                }
            }

            override fun openBlockedTerms(siteId: SiteId) {
                navController.navigate(RavenRoute.BlockedTerms(siteId = siteId.value)) {
                    launchSingleTop = true
                }
            }

            override fun openTagDictionary() {
                navController.navigate(RavenRoute.TagDictionary) { launchSingleTop = true }
            }

            override fun openBackupRestore() {
                navController.navigate(RavenRoute.BackupRestore) { launchSingleTop = true }
            }

            override fun openFeedback() {
                navController.navigate(RavenRoute.Feedback) { launchSingleTop = true }
            }

            override fun openStorageMigration() {
                navController.navigate(RavenRoute.StorageMigration) { launchSingleTop = true }
            }

            override fun openDuplicateScanner() {
                navController.navigate(RavenRoute.DuplicateScanner) { launchSingleTop = true }
            }

            override fun launchWebViewAuth(siteId: SiteId) {
                hostActions.launchWebViewAuth(siteId)
            }

            override fun openAppUpdateInstaller(apkFile: File) {
                hostActions.openAppUpdateInstaller(apkFile)
            }

            override fun navigateBack() {
                navController.popBackStack()
            }
        }
    }

    val badges = remember(unreadInboxCount) {
        if (unreadInboxCount > 0) mapOf(RavenTopDestination.PROFILE to unreadInboxCount) else emptyMap()
    }
    val motionReduced = LocalMotionReduced.current

    RavenAdaptiveScaffold(
        currentDestination = activeTopDestination ?: lastTopDestination,
        onDestinationSelected = selectTopDestination,
        showNavigation = activeTopDestination != null,
        badges = badges,
        isIncognito = isIncognito,
        modifier = modifier.fillMaxSize(),
    ) {
        NavHost(
            navController = navController,
            startDestination = RavenRoute.Home,
            enterTransition = {
                val fromIdx = initialState.destination.topLevelTabIndex()
                val toIdx = targetState.destination.topLevelTabIndex()
                when {
                    motionReduced -> EnterTransition.None
                    fromIdx != null && toIdx != null -> {
                        ravenTabEnterTransition(RavenMotion.computeTabSlideDirection(fromIdx, toIdx))
                    }
                    targetState.destination.isTopLevelTabDestination() -> ravenTabEnterTransition(direction = 0)
                    initialState.destination.hasRoute<RavenRoute.GalleryDetail>() &&
                        targetState.destination.hasRoute<RavenRoute.Reader>() -> ravenGalleryToReaderEnterTransition()
                    targetState.destination.isImmersiveModalDestination() -> ravenModalEnterTransition()
                    else -> ravenPushEnterTransition()
                }
            },
            exitTransition = {
                val fromIdx = initialState.destination.topLevelTabIndex()
                val toIdx = targetState.destination.topLevelTabIndex()
                when {
                    motionReduced -> ExitTransition.None
                    fromIdx != null && toIdx != null -> {
                        ravenTabExitTransition(RavenMotion.computeTabSlideDirection(fromIdx, toIdx))
                    }
                    targetState.destination.isTopLevelTabDestination() -> ravenTabExitTransition(direction = 0)
                    initialState.destination.hasRoute<RavenRoute.GalleryDetail>() &&
                        targetState.destination.hasRoute<RavenRoute.Reader>() -> ravenModalParentExitTransition()
                    targetState.destination.isImmersiveModalDestination() -> ravenModalParentExitTransition()
                    else -> ravenPushParentExitTransition()
                }
            },
            popEnterTransition = {
                val fromIdx = initialState.destination.topLevelTabIndex()
                val toIdx = targetState.destination.topLevelTabIndex()
                when {
                    motionReduced -> EnterTransition.None
                    fromIdx != null && toIdx != null -> {
                        ravenTabEnterTransition(RavenMotion.computeTabSlideDirection(fromIdx, toIdx))
                    }
                    initialState.destination.hasRoute<RavenRoute.Reader>() &&
                        targetState.destination.hasRoute<RavenRoute.GalleryDetail>() -> ravenModalParentEnterTransition()
                    initialState.destination.isImmersiveModalDestination() -> ravenModalParentEnterTransition()
                    else -> ravenPopParentEnterTransition()
                }
            },
            popExitTransition = {
                val fromIdx = initialState.destination.topLevelTabIndex()
                val toIdx = targetState.destination.topLevelTabIndex()
                when {
                    motionReduced -> ExitTransition.None
                    fromIdx != null && toIdx != null -> {
                        ravenTabExitTransition(RavenMotion.computeTabSlideDirection(fromIdx, toIdx))
                    }
                    initialState.destination.hasRoute<RavenRoute.Reader>() &&
                        targetState.destination.hasRoute<RavenRoute.GalleryDetail>() -> ravenReaderToGalleryExitTransition()
                    initialState.destination.isImmersiveModalDestination() -> ravenModalExitTransition()
                    else -> ravenPopExitTransition()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .background(LocalRavenColors.current.canvas),
        ) {
            // ---- 5 Primary Top-Level Destinations ----
            composable<RavenRoute.Home> {
                HomeScreen(navigator = browseNavigator)
            }
            composable<RavenRoute.Explore> {
                ExploreScreen(navigator = browseNavigator)
            }
            composable<RavenRoute.Search> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.Search>()
                SearchScreen(navigator = browseNavigator, route = route)
            }
            composable<RavenRoute.Library> {
                LibraryHubScreen(navigator = libraryNavigator)
            }
            composable<RavenRoute.Profile> {
                ProfileScreen(navigator = libraryNavigator)
            }

            // ---- Browse Sub-Routes ----
            composable<RavenRoute.SiteFeed> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.SiteFeed>()
                SiteFeedScreen(route = route, navigator = browseNavigator)
            }
            composable<RavenRoute.SiteDirectory> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.SiteDirectory>()
                SiteDirectoryScreen(route = route, navigator = browseNavigator)
            }
            composable<RavenRoute.SiteFunctions> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.SiteFunctions>()
                SiteFunctionsScreen(route = route, navigator = browseNavigator)
            }

            // ---- Gallery Detail & Reader Routes ----
            composable<RavenRoute.GalleryDetail>(
                typeMap = mapOf(typeOf<RavenRoute.ReaderParent?>() to ReaderParentNavType),
            ) { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.GalleryDetail>()
                val viewModel: GalleryDetailViewModel = koinViewModel(
                    key = "gallery_detail_${route.siteId}_${route.mediaId}",
                    parameters = { parametersOf(route) },
                )
                GalleryDetailScreen(
                    viewModel = viewModel,
                    navigator = readerNavigator,
                    parent = route.parent,
                )
            }
            composable<RavenRoute.Reader>(
                typeMap = mapOf(typeOf<RavenRoute.ReaderParent?>() to ReaderParentNavType),
            ) { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.Reader>()
                val viewModel: ReaderViewModel = koinViewModel(
                    key = "reader_${route.siteId}_${route.mediaId}_${route.startPage}_${route.offlineOnly}_${route.queueToken.orEmpty()}",
                    parameters = { parametersOf(route) },
                )
                ReaderScreen(
                    viewModel = viewModel,
                    navigator = readerNavigator,
                    parent = route.parent,
                    registerVolumeKeyHandler = hostActions::registerVolumeKeyHandler,
                )
            }

            // ---- Library Sub-Routes ----
            composable<RavenRoute.Downloads> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.Downloads>()
                val initialSiteId = route.siteId?.takeIf { it.isNotBlank() }?.let {
                    runCatching { SiteId.of(it) }.getOrNull()
                }
                DownloadsFavoritesScreen(
                    navigator = libraryNavigator,
                    siteId = initialSiteId,
                    initialTab = route.initialTab,
                )
            }
            composable<RavenRoute.ShelfDetail> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.ShelfDetail>()
                ShelfDetailScreen(shelfId = route.shelfId, navigator = libraryNavigator)
            }
            composable<RavenRoute.SmartShelfDetail> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.SmartShelfDetail>()
                SmartShelfDetailScreen(smartShelfId = route.smartShelfId, navigator = libraryNavigator)
            }
            composable<RavenRoute.Favorites> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.Favorites>()
                val initialSiteId = route.siteId?.takeIf { it.isNotBlank() }?.let {
                    runCatching { SiteId.of(it) }.getOrNull()
                }
                DownloadsFavoritesScreen(
                    navigator = libraryNavigator,
                    siteId = initialSiteId,
                    initialTab = "favorites",
                )
            }
            composable<RavenRoute.Stats> {
                StatsScreen(navigator = libraryNavigator)
            }
            composable<RavenRoute.History> {
                HistoryScreen(navigator = libraryNavigator)
            }
            composable<RavenRoute.ImageFavorites> {
                ImageFavoritesScreen(navigator = libraryNavigator)
            }
            composable<RavenRoute.Subscriptions> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.Subscriptions>()
                val initialSiteId = route.siteId?.takeIf { it.isNotBlank() }?.let(SiteId::of)
                SubscriptionsScreen(navigator = libraryNavigator, initialSiteId = initialSiteId)
            }
            composable<RavenRoute.GlobalInbox> {
                GlobalInboxScreen(navigator = libraryNavigator)
            }
            composable<RavenRoute.LocalLibrary> {
                LocalLibraryScreen(navigator = libraryNavigator)
            }

            // ---- Settings & Workflow Sub-Routes ----
            composable<RavenRoute.Settings> {
                SettingsScreen(navigator = settingsNavigator)
            }
            composable<RavenRoute.SiteAccount> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.SiteAccount>()
                SiteAccountScreen(route = route, navigator = settingsNavigator)
            }
            composable<RavenRoute.BlockedTerms> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.BlockedTerms>()
                BlockedTermsScreen(route = route, navigator = settingsNavigator)
            }
            composable<RavenRoute.TagDictionary> {
                TagDictionaryScreen(navigator = settingsNavigator)
            }
            composable<RavenRoute.BackupRestore> {
                BackupRestoreScreen(navigator = settingsNavigator)
            }
            composable<RavenRoute.Feedback> {
                FeedbackScreen(navigator = settingsNavigator)
            }
            composable<RavenRoute.StorageMigration> {
                StorageMigrationScreen(navigator = settingsNavigator)
            }
            composable<RavenRoute.DuplicateScanner> {
                DuplicateScannerScreen(navigator = settingsNavigator)
            }
        }
    }

    // Incoming ACTION_SEND URL confirmation bottom sheet (modal sheet only)
    if (pendingSharedLink != null) {
        SharedLinkConfirmationSheet(
            prompt = pendingSharedLink,
            onDismissRequest = onDismissSharedLink,
            onConfirmOpen = { link ->
                onDismissSharedLink()
                when (link) {
                    is ResolvedLink.Video -> hostActions.launchPlayer(
                        PlayerLaunchArgs(
                            siteId = link.siteId,
                            mediaId = link.id,
                            url = link.canonicalUrl,
                        ),
                    )
                    is ResolvedLink.Gallery -> {
                        val page = link.pageIndex
                        if (page != null && page > 0) {
                            navigateReaderRoute(
                                RavenRoute.Reader(
                                    siteId = link.siteId.value,
                                    mediaId = link.id,
                                    url = link.canonicalUrl,
                                    startPage = page,
                                ),
                            )
                        } else {
                            navController.navigate(
                                RavenRoute.GalleryDetail(
                                    siteId = link.siteId.value,
                                    mediaId = link.id,
                                    url = link.canonicalUrl,
                                ),
                            )
                        }
                    }
                    is ResolvedLink.Feed -> navController.navigate(link.request.toSiteFeedRoute())
                    is ResolvedLink.Directory -> navController.navigate(
                        RavenRoute.SiteDirectory(
                            siteId = link.siteId.value,
                            section = link.section,
                        ),
                    )
                }
            },
        )
    }
}
