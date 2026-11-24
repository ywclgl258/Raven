package com.raven.app.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Dynamic Rule Smart Shelf Detail Screen (`SmartShelfDetailScreen`).
 */
@Composable
fun SmartShelfDetailScreen(
    smartShelfId: String,
    navigator: LibraryNavigator,
    modifier: Modifier = Modifier,
    viewModel: ShelfDetailViewModel = koinViewModel(
        key = "smart_shelf_detail_$smartShelfId",
        parameters = { parametersOf(ShelfDetailTarget.Smart(smartShelfId)) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) navigator.navigateBack()
    }

    LaunchedEffect(uiState.pendingLaunch) {
        val launch = uiState.pendingLaunch ?: return@LaunchedEffect
        viewModel.consumeLaunchEvent()
        navigator.openMedia(
            item = launch.firstItem,
            offlineOnly = launch.offlineOnly,
            queueToken = launch.queueToken,
        )
    }

    ShelfDetailScreenBody(
        uiState = uiState,
        navigator = navigator,
        screenTestTag = "smart_shelf_detail_screen",
        onSearchQueryChange = viewModel::updateSearchQuery,
        onSelectCompletion = viewModel::selectCompletionFilter,
        onSelectMediaKind = viewModel::selectMediaKindFilter,
        onSelectSite = viewModel::selectSiteFilter,
        onSelectAddedWindow = viewModel::selectAddedWindow,
        onSelectViewSort = viewModel::selectViewSort,
        onResetFilters = viewModel::resetFilters,
        onMoveUp = { _, _ -> },
        onMoveDown = { _, _ -> },
        onToggleCompleted = {},
        onRemoveItem = { _, _ -> },
        onStartOnlineQueue = { viewModel.startSequentialOnline() },
        onStartOfflineQueue = { viewModel.startSequentialOffline() },
        onEnqueueMissingDownloads = viewModel::enqueueMissingDownloads,
        onCancelBatchDownload = viewModel::cancelBatchDownload,
        onSaveCustomMetadata = null,
        onSaveSmartMetadata = viewModel::updateSmartShelfMetadata,
        onDeleteShelf = viewModel::deleteCurrentShelf,
        onClearMessage = viewModel::clearMessage,
        modifier = modifier,
    )
}
