package com.raven.app

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.raven.app.core.designsystem.component.RavenAdaptiveScaffold
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenPrimaryButton
import com.raven.app.core.designsystem.component.RavenPrimaryButtonVariant
import com.raven.app.core.designsystem.component.RavenSecondaryButton
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopDestination
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.ResolvedLink
import com.raven.app.core.sitespi.SitePlugin
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
 * Supplies the missing detail parent for readers opened from resume cards, history or deep links.
 * A matching detail already on top is reused so normal detail -> reader navigation stays unchanged.
 */
internal fun readerParentDetail(
    currentDetail: RavenRoute.GalleryDetail?,
    reader: RavenRoute.Reader,
): RavenRoute.GalleryDetail? {
    if (currentDetail != null &&
        currentDetail.siteId == reader.siteId &&
        currentDetail.mediaId == reader.mediaId &&
        currentDetail.offlineOnly == reader.offlineOnly
    ) {
        return null
    }
    return RavenRoute.GalleryDetail(
        siteId = reader.siteId,
        mediaId = reader.mediaId,
        url = reader.url,
        offlineOnly = reader.offlineOnly,
    )
}

/** Search destination used by Gallery Detail tag actions; canonical slug is preferred over translations. */
internal fun tagSearchRoute(tag: TagRef, crossSite: Boolean): RavenRoute.Search =
    RavenRoute.Search(
        initialQuery = tag.slug.trim().ifBlank { tag.label.trim() }.takeIf { it.isNotEmpty() },
        crossSite = crossSite,
    )

/** Search destination used by Gallery Detail author taps and long-press actions. */
internal fun authorSearchRoute(author: AuthorRef, crossSite: Boolean): RavenRoute.Search =
    RavenRoute.Search(
        initialQuery = author.name.trim().ifBlank { author.id.trim() }.takeIf { it.isNotEmpty() },
        crossSite = crossSite,
    )

internal data class ReaderNavigationPlan(
    val popCurrentReader: Boolean,
    val popStaleParentDetail: Boolean,
    val insertParentDetail: RavenRoute.GalleryDetail?,
)

/**
 * Plans backstack adjustment when entering or advancing inside [RavenRoute.Reader] so pressing Back
 * always returns to the active work's [RavenRoute.GalleryDetail] (never a previous queue item's detail).
 */
internal fun planReaderNavigation(
    currentReader: RavenRoute.Reader?,
    currentDetail: RavenRoute.GalleryDetail?,
    previousDetail: RavenRoute.GalleryDetail?,
    targetReader: RavenRoute.Reader,
): ReaderNavigationPlan {
    if (currentReader == null) {
        return ReaderNavigationPlan(
            popCurrentReader = false,
            popStaleParentDetail = false,
            insertParentDetail = readerParentDetail(currentDetail, targetReader),
        )
    }
    val matchesPrevious = previousDetail != null &&
        previousDetail.siteId == targetReader.siteId &&
        previousDetail.mediaId == targetReader.mediaId &&
        previousDetail.offlineOnly == targetReader.offlineOnly
    if (matchesPrevious) {
        return ReaderNavigationPlan(
            popCurrentReader = true,
            popStaleParentDetail = false,
            insertParentDetail = null,
        )
    }
    return ReaderNavigationPlan(
        popCurrentReader = true,
        popStaleParentDetail = previousDetail != null,
        insertParentDetail = RavenRoute.GalleryDetail(
            siteId = targetReader.siteId,
            mediaId = targetReader.mediaId,
            url = targetReader.url,
            offlineOnly = targetReader.offlineOnly,
        ),
    )
}

/**
 * Host platform callbacks supplied by [MainActivity] for features that interact with Android
 * Activities, SAF document pickers, FileProvider sharing, window chrome, and hardware keys.
 */
interface MainActivityHostActions {
    val fileProviderAuthority: String
    fun launchPlayer(args: PlayerLaunchArgs)
    fun launchWebViewAuth(siteId: SiteId)
    fun setSystemBarsVisible(visible: Boolean)
    fun setReaderBrightness(value: Float)
    fun setKeepScreenOn(enabled: Boolean)
    fun registerVolumeKeyHandler(handler: ((up: Boolean, isLongPress: Boolean) -> Boolean)?)
    fun launchSaveDocument(suggestedFileName: String, mimeType: String, onResult: (Uri?) -> Unit)
    fun launchOpenDocument(onResult: (Uri?) -> Unit)
    fun launchOpenDocumentTree(onResult: (Uri?) -> Unit)
    fun shareFiles(uris: List<Uri>, mimeType: String, subject: String)
    fun openAppUpdateInstaller(apkFile: File)
}

/**
 * Pending incoming `ACTION_SEND` shared text resolved via [SiteRegistry.resolveLinkFromText].
 */
data class SharedLinkPrompt(
    val rawText: String,
    val plugin: SitePlugin?,
    val resolved: ResolvedLink?,
)

/**
 * Root Compose shell hosting [RavenAdaptiveScaffold], the complete 27-route Type-Safe [NavHost],
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
    isAppLocked: Boolean = false,
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

    val openSearchRoute: (String?, Boolean) -> Unit = remember(navController, selectTopDestination) {
        { initialQuery, crossSite ->
            if (initialQuery.isNullOrBlank() && !crossSite) {
                selectTopDestination(RavenTopDestination.SEARCH)
            } else {
                navController.navigate(
                    RavenRoute.Search(initialQuery = initialQuery, crossSite = crossSite),
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
    LaunchedEffect(pendingNavigationRoute, isAppLocked) {
        if (isAppLocked) return@LaunchedEffect
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
                )
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
                navController.navigate(route)
            }

            override fun openSiteDirectory(siteId: SiteId, section: String) {
                navController.navigate(
                    RavenRoute.SiteDirectory(siteId = siteId.value, section = section),
                )
            }

            override fun openSiteFunctions(siteId: SiteId) {
                navController.navigate(RavenRoute.SiteFunctions(siteId = siteId.value))
            }

            override fun openSearch(initialQuery: String?, crossSite: Boolean) {
                openSearchRoute(initialQuery, crossSite)
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

            override fun openSearch(initialQuery: String?, crossSite: Boolean) {
                openSearchRoute(initialQuery, crossSite)
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

            override fun openGallery(
                siteId: SiteId,
                mediaId: String,
                url: String,
                offlineOnly: Boolean,
                queueToken: String?,
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
                        ),
                    )
                } else {
                    navController.navigate(
                        RavenRoute.GalleryDetail(siteId = siteId.value, mediaId = mediaId, url = url),
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

            override fun openTagSearch(tag: TagRef, crossSite: Boolean) {
                navController.navigate(tagSearchRoute(tag, crossSite)) {
                    launchSingleTop = true
                }
            }

            override fun openAuthorSearch(author: AuthorRef, crossSite: Boolean) {
                navController.navigate(authorSearchRoute(author, crossSite)) {
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

            override fun launchSaveDocument(
                suggestedFileName: String,
                mimeType: String,
                onResult: (Uri?) -> Unit,
            ) {
                hostActions.launchSaveDocument(suggestedFileName, mimeType, onResult)
            }

            override fun launchOpenDocumentTree(onResult: (Uri?) -> Unit) {
                hostActions.launchOpenDocumentTree(onResult)
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

            override fun openStorageMigration() {
                navController.navigate(RavenRoute.StorageMigration) { launchSingleTop = true }
            }

            override fun openDuplicateScanner() {
                navController.navigate(RavenRoute.DuplicateScanner) { launchSingleTop = true }
            }

            override fun launchWebViewAuth(siteId: SiteId) {
                hostActions.launchWebViewAuth(siteId)
            }

            override fun launchCreateBackupDocument(
                suggestedFileName: String,
                onDocumentCreated: (Uri?) -> Unit,
            ) {
                hostActions.launchSaveDocument(
                    suggestedFileName = suggestedFileName,
                    mimeType = com.raven.app.feature.settings.SettingsExportDefaults.BACKUP_MIME_TYPE,
                    onResult = onDocumentCreated,
                )
            }

            override fun launchOpenBackupDocument(onDocumentOpened: (Uri?) -> Unit) {
                hostActions.launchOpenDocument(onDocumentOpened)
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
            composable<RavenRoute.GalleryDetail> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.GalleryDetail>()
                val viewModel: GalleryDetailViewModel = koinViewModel(
                    key = "gallery_detail_${route.siteId}_${route.mediaId}",
                    parameters = { parametersOf(route) },
                )
                GalleryDetailScreen(
                    viewModel = viewModel,
                    navigator = readerNavigator,
                )
            }
            composable<RavenRoute.Reader> { backStackEntry ->
                val route = backStackEntry.toRoute<RavenRoute.Reader>()
                val viewModel: ReaderViewModel = koinViewModel(
                    key = "reader_${route.siteId}_${route.mediaId}_${route.startPage}_${route.offlineOnly}_${route.queueToken.orEmpty()}",
                    parameters = { parametersOf(route) },
                )
                ReaderScreen(
                    viewModel = viewModel,
                    navigator = readerNavigator,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedLinkConfirmationSheet(
    prompt: SharedLinkPrompt,
    onDismissRequest: () -> Unit,
    onConfirmOpen: (ResolvedLink) -> Unit,
) {
    val colors = LocalRavenColors.current
    val resolved = prompt.resolved
    val plugin = prompt.plugin

    val kindLabel = when (resolved) {
        is ResolvedLink.Video -> "视频作品"
        is ResolvedLink.Gallery -> if (resolved.pageIndex != null) "图集 · 第 ${resolved.pageIndex} 页" else "漫画 / 图集"
        is ResolvedLink.Feed -> "站点列表"
        is ResolvedLink.Directory -> "站点目录"
        null -> "未识别链接"
    }
    val kindIcon = when (resolved) {
        is ResolvedLink.Video -> Icons.Outlined.Movie
        is ResolvedLink.Gallery -> Icons.Outlined.AutoStories
        is ResolvedLink.Feed -> Icons.Outlined.Explore
        is ResolvedLink.Directory -> Icons.Outlined.FolderOpen
        null -> Icons.Outlined.Link
    }
    val previewUrl = when (resolved) {
        is ResolvedLink.Video -> resolved.canonicalUrl
        is ResolvedLink.Gallery -> resolved.canonicalUrl
        is ResolvedLink.Directory -> resolved.canonicalUrl
        is ResolvedLink.Feed -> prompt.rawText
        null -> prompt.rawText
    }

    RavenBottomSheet(
        onDismissRequest = onDismissRequest,
        title = if (resolved != null) "打开分享链接" else "无法识别链接",
        subtitle = if (plugin != null && resolved != null) {
            "已匹配 ${plugin.descriptor.displayName} · $kindLabel"
        } else {
            "暂不支持该链接所属的站点或路径格式"
        },
        trailingAction = if (plugin != null) {
            {
                RavenStatusPill(
                    text = plugin.descriptor.shortName,
                    tone = RavenStatusTone.ACCENT,
                )
            }
        } else {
            null
        },
        modifier = Modifier.testTag("shared_link_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (resolved != null && plugin != null) {
                RavenGroupedSection {
                    RavenGroupedRow(
                        title = plugin.descriptor.displayName,
                        subtitle = kindLabel,
                        icon = kindIcon,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RavenShapes.ControlShape)
                    .background(colors.surface1)
                    .border(0.5.dp, colors.hairline, RavenShapes.ControlShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = previewUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RavenSecondaryButton(
                    text = if (resolved != null) "取消" else "知道了",
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("shared_link_cancel_button"),
                )

                if (resolved != null) {
                    RavenPrimaryButton(
                        text = "立即打开",
                        onClick = { onConfirmOpen(resolved) },
                        variant = RavenPrimaryButtonVariant.Cta,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("shared_link_confirm_button"),
                    )
                }
            }
        }
    }
}

private fun NavDestination.topLevelTabIndex(): Int? = when {
    hasRoute<RavenRoute.Home>() -> 0
    hasRoute<RavenRoute.Explore>() -> 1
    hasRoute<RavenRoute.Search>() -> 2
    hasRoute<RavenRoute.Library>() -> 3
    hasRoute<RavenRoute.Profile>() -> 4
    else -> null
}

private fun NavDestination.isTopLevelTabDestination(): Boolean = topLevelTabIndex() != null

private fun NavDestination.isImmersiveModalDestination(): Boolean =
    hasRoute<RavenRoute.Reader>() || hasRoute<RavenRoute.GalleryDetail>()

private fun ravenTabEnterTransition(direction: Int): EnterTransition {
    val base = fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + scaleIn(
        initialScale = RavenMotion.TAB_ENTER_INITIAL_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )
    return if (direction == 0) {
        base
    } else {
        base + slideInHorizontally(
            animationSpec = tween(
                durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
            initialOffsetX = { fullWidth -> RavenMotion.computeTabSlideOffset(fullWidth, direction) },
        )
    }
}

private fun ravenTabExitTransition(direction: Int): ExitTransition {
    val base = fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS - 40,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )
    return if (direction == 0) {
        base
    } else {
        base + slideOutHorizontally(
            animationSpec = tween(
                durationMillis = RavenMotion.TAB_TRANSITION_DURATION_MS - 20,
                easing = RavenMotion.HarmonyDecelerationEasing,
            ),
            targetOffsetX = { fullWidth -> -RavenMotion.computeTabSlideOffset(fullWidth, direction) },
        )
    }
}

private fun ravenPushEnterTransition(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialOffsetX = { fullWidth -> fullWidth },
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS / 2,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialAlpha = 0.85f,
    )

private fun ravenPushParentExitTransition(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetOffsetX = RavenMotion::computeNavPushParentExitOffset,
    ) + scaleOut(
        targetScale = RavenMotion.PUSH_PARENT_EXIT_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.PUSH_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetAlpha = RavenMotion.PUSH_EXIT_ALPHA,
    )

private fun ravenPopParentEnterTransition(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialOffsetX = RavenMotion::computeNavPopParentEnterOffset,
    ) + scaleIn(
        initialScale = RavenMotion.PUSH_PARENT_EXIT_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialAlpha = RavenMotion.PUSH_EXIT_ALPHA,
    )

private fun ravenPopExitTransition(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetOffsetX = { fullWidth -> fullWidth },
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.POP_TRANSITION_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetAlpha = 0.85f,
    )

private fun ravenGalleryToReaderEnterTransition(): EnterTransition =
    scaleIn(
        initialScale = 0.96f,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS - 40,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )

private fun ravenReaderToGalleryExitTransition(): ExitTransition =
    scaleOut(
        targetScale = 0.96f,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS - 30,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )

private fun ravenModalEnterTransition(): EnterTransition =
    slideInVertically(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialOffsetY = { fullHeight -> fullHeight / 14 },
    ) + scaleIn(
        initialScale = RavenMotion.MODAL_INITIAL_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS - 60,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )

private fun ravenModalParentExitTransition(): ExitTransition =
    scaleOut(
        targetScale = RavenMotion.PUSH_PARENT_EXIT_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_ENTER_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetAlpha = RavenMotion.PUSH_EXIT_ALPHA,
    )

private fun ravenModalParentEnterTransition(): EnterTransition =
    scaleIn(
        initialScale = RavenMotion.PUSH_PARENT_EXIT_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        initialAlpha = RavenMotion.PUSH_EXIT_ALPHA,
    )

private fun ravenModalExitTransition(): ExitTransition =
    slideOutVertically(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
        targetOffsetY = { fullHeight -> fullHeight / 14 },
    ) + scaleOut(
        targetScale = RavenMotion.MODAL_INITIAL_SCALE,
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = RavenMotion.MODAL_EXIT_DURATION_MS - 30,
            easing = RavenMotion.HarmonyDecelerationEasing,
        ),
    )
