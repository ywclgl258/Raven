package com.raven.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.media.storage.StorageStatus
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.network.NetworkDiagnosticReport
import com.raven.app.core.network.NetworkDiagnostics
import com.raven.app.core.network.NetworkMonitor
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthState
import com.raven.app.core.sitespi.SiteRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class ReadingCacheSummary(
    val totalPages: Int = 0,
    val totalBytes: Long = 0L,
    val isRefreshing: Boolean = false,
    val isClearing: Boolean = false,
)

data class AuthSiteAccountItem(
    val descriptor: SiteDescriptor,
    val authMode: AuthMode,
    val authState: AuthState = AuthState(isAuthenticated = false),
)

data class NetworkDiagnosticsUiState(
    val selectedSiteId: SiteId? = null,
    val report: NetworkDiagnosticReport? = null,
    val isProbing: Boolean = false,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val latestTag: String) : UpdateUiState
    data class DebugSkipped(val message: String) : UpdateUiState
    data class Available(
        val release: RavenReleaseInfo,
        val isDownloading: Boolean = false,
        val downloadProgress: UpdateDownloadProgress? = null,
        val readyApk: File? = release.cachedApk,
        val downloadError: String? = null,
    ) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val activeSite: SiteDescriptor? = null,
    val activeSiteSupportsAuth: Boolean = false,
    val activeSiteBlockedTermsCount: Int = 0,
    val authSites: List<AuthSiteAccountItem> = emptyList(),
    val allSites: List<SiteDescriptor> = emptyList(),
    val readingCache: ReadingCacheSummary = ReadingCacheSummary(),
    val storageStatus: StorageStatus? = null,
    val isMeteredNetwork: Boolean = false,
    val isConnectedNetwork: Boolean = true,
    val routeEpoch: Long = 0L,
    val diagnostics: NetworkDiagnosticsUiState = NetworkDiagnosticsUiState(),
    val updateState: UpdateUiState = UpdateUiState.Idle,
    val appInfo: RavenAppInfo = RavenAppInfo(
        versionName = "1.0.0",
        versionCode = 1L,
        isDebug = false,
        packageName = RavenAppInfo.DEFAULT_PACKAGE_NAME,
    ),
    val feedbackMessage: String? = null,
)

private data class SettingsAuxState(
    val authSites: List<AuthSiteAccountItem> = emptyList(),
    val readingCache: ReadingCacheSummary = ReadingCacheSummary(),
    val storageStatus: StorageStatus? = null,
    val diagnostics: NetworkDiagnosticsUiState = NetworkDiagnosticsUiState(),
    val updateState: UpdateUiState = UpdateUiState.Idle,
    val feedbackMessage: String? = null,
)

private data class NetworkSummary(
    val isMetered: Boolean = false,
    val isConnected: Boolean = true,
    val routeEpoch: Long = 0L,
)

/**
 * ViewModel backing the Apple HIG Grouped Inset Settings Hub (`SettingsScreen`).
 * Coordinates DataStore preferences, reading cache stats/cleanup, auth-capable site discovery,
 * live [NetworkDiagnostics], storage status preview, and [RavenUpdater] checks/downloads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val siteRegistry: SiteRegistry,
    private val galleryPageRepository: GalleryPageRepository,
    private val storageRootManager: StorageRootManager,
    private val networkDiagnostics: NetworkDiagnostics,
    private val networkMonitor: NetworkMonitor,
    private val ravenUpdater: RavenUpdater,
    private val appInfo: RavenAppInfo,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val auxState = MutableStateFlow(
        SettingsAuxState(
            authSites = initialAuthSiteItems(),
            storageStatus = runCatching { storageRootManager.status() }.getOrNull(),
        ),
    )

    private var diagnosticJob: Job? = null
    private var updateJob: Job? = null

    private val activeBlockedCountFlow = preferencesRepository.settings
        .map { it.activeSiteId }
        .flatMapLatest { siteId ->
            blockedTermsRepository.observeBySite(siteId).map { it.size }
        }

    private val networkSummaryFlow = combine(
        networkMonitor.isMetered,
        networkMonitor.isConnected,
        networkMonitor.routeEpoch,
    ) { metered, connected, epoch ->
        NetworkSummary(
            isMetered = metered,
            isConnected = connected,
            routeEpoch = epoch,
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.settings,
        activeBlockedCountFlow,
        networkSummaryFlow,
        auxState,
    ) { settings, blockedCount, net, aux ->
        val activePlugin = siteRegistry.get(settings.activeSiteId)
            ?: siteRegistry.allPlugins().firstOrNull()
        val activeSupportsAuth = activePlugin != null && activePlugin.authProvider.mode != AuthMode.None
        SettingsUiState(
            settings = settings,
            activeSite = activePlugin?.descriptor,
            activeSiteSupportsAuth = activeSupportsAuth,
            activeSiteBlockedTermsCount = blockedCount,
            authSites = aux.authSites,
            allSites = siteRegistry.allDescriptors(),
            readingCache = aux.readingCache,
            storageStatus = aux.storageStatus,
            isMeteredNetwork = net.isMetered,
            isConnectedNetwork = net.isConnected,
            routeEpoch = net.routeEpoch,
            diagnostics = aux.diagnostics,
            updateState = aux.updateState,
            appInfo = appInfo,
            feedbackMessage = aux.feedbackMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(
            allSites = siteRegistry.allDescriptors(),
            authSites = initialAuthSiteItems(),
            storageStatus = runCatching { storageRootManager.status() }.getOrNull(),
            appInfo = appInfo,
        ),
    )

    init {
        refreshOverview()
    }

    /**
     * Refreshes auth statuses, reading cache byte totals, and storage root summary.
     * Safe to invoke on `ON_RESUME` after returning from SiteAccount or StorageMigration.
     */
    fun refreshOverview() {
        refreshAuthStatuses()
        refreshReadingCacheStats()
        refreshStorageStatus()
    }

    // ---- Appearance & Reading ----

    fun setReduceTransparency(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setReduceTransparency(enabled)
        }
    }

    fun setTranslateTags(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTranslateTags(enabled)
        }
    }

    fun setAutoFullscreen(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoFullscreen(enabled)
        }
    }

    // ---- Playback & Cache ----

    fun setPreferredQuality(quality: String) {
        viewModelScope.launch {
            preferencesRepository.setPreferredQuality(quality)
        }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        val snapped = SettingsFormatters.snapPlaybackSpeed(speed)
        viewModelScope.launch {
            preferencesRepository.setDefaultPlaybackSpeed(snapped)
        }
    }

    fun setStreamCacheEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setStreamCacheEnabled(enabled)
        }
    }

    fun setSeekCacheEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setSeekCacheEnabled(enabled)
        }
    }

    fun refreshReadingCacheStats() {
        viewModelScope.launch {
            auxState.update { it.copy(readingCache = it.readingCache.copy(isRefreshing = true)) }
            val (pages, bytes) = withContext(dispatchers.io) {
                var totalPages = 0
                var totalBytes = 0L
                val candidateIds = (siteRegistry.allPlugins().map { it.id } + SiteId.ALL_LIVE).distinct()
                for (siteId in candidateIds) {
                    val (sitePages, siteBytes) = runCatching {
                        galleryPageRepository.readingCacheStats(siteId)
                    }.getOrDefault(0 to 0L)
                    totalPages += sitePages
                    totalBytes += siteBytes
                }
                totalPages to totalBytes
            }
            auxState.update {
                it.copy(
                    readingCache = ReadingCacheSummary(
                        totalPages = pages,
                        totalBytes = bytes,
                        isRefreshing = false,
                        isClearing = false,
                    ),
                )
            }
        }
    }

    fun clearAllReadingCaches() {
        viewModelScope.launch {
            auxState.update { it.copy(readingCache = it.readingCache.copy(isClearing = true)) }
            val result = runCatching {
                withContext(dispatchers.io) {
                    galleryPageRepository.clearAllReadingCaches()
                }
            }
            if (result.isSuccess) {
                auxState.update {
                    it.copy(
                        readingCache = ReadingCacheSummary(
                            totalPages = 0,
                            totalBytes = 0L,
                            isRefreshing = false,
                            isClearing = false,
                        ),
                        feedbackMessage = "已清理全部阅读页面缓存",
                    )
                }
            } else {
                auxState.update {
                    it.copy(
                        readingCache = it.readingCache.copy(isClearing = false),
                        feedbackMessage = result.exceptionOrNull()?.message ?: "清理阅读缓存失败，请重试",
                    )
                }
                refreshReadingCacheStats()
            }
        }
    }

    // ---- Downloads & Storage ----

    fun setDownloadUnmeteredOnly(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDownloadUnmeteredOnly(enabled)
        }
    }

    fun refreshStorageStatus() {
        viewModelScope.launch {
            val status = withContext(dispatchers.io) {
                runCatching { storageRootManager.status() }.getOrNull()
            }
            auxState.update { it.copy(storageStatus = status) }
        }
    }

    // ---- Sites & Security ----

    fun refreshAuthStatuses() {
        viewModelScope.launch {
            val items = withContext(dispatchers.io) {
                siteRegistry.allPlugins()
                    .filter { it.authProvider.mode != AuthMode.None }
                    .map { plugin ->
                        val state = runCatching { plugin.authProvider.currentAuthState() }
                            .getOrDefault(AuthState(isAuthenticated = false))
                        AuthSiteAccountItem(
                            descriptor = plugin.descriptor,
                            authMode = plugin.authProvider.mode,
                            authState = state,
                        )
                    }
            }
            auxState.update { it.copy(authSites = items) }
        }
    }

    fun prepareDiagnosticsSnapshot(siteId: SiteId? = null) {
        val targetSiteId = siteId ?: uiState.value.settings.activeSiteId
        viewModelScope.launch {
            val snapshot = withContext(dispatchers.io) {
                networkDiagnostics.snapshot(siteId = targetSiteId)
            }
            auxState.update {
                it.copy(
                    diagnostics = it.diagnostics.copy(
                        selectedSiteId = targetSiteId,
                        report = snapshot,
                    ),
                )
            }
        }
    }

    fun runLiveNetworkDiagnostics(siteId: SiteId? = null) {
        val targetSiteId = siteId
            ?: auxState.value.diagnostics.selectedSiteId
            ?: uiState.value.settings.activeSiteId
        diagnosticJob?.cancel()
        diagnosticJob = viewModelScope.launch {
            auxState.update {
                it.copy(
                    diagnostics = it.diagnostics.copy(
                        selectedSiteId = targetSiteId,
                        isProbing = true,
                    ),
                )
            }
            val report = withContext(dispatchers.io) {
                networkDiagnostics.diagnose(siteId = targetSiteId)
            }
            auxState.update {
                it.copy(
                    diagnostics = NetworkDiagnosticsUiState(
                        selectedSiteId = targetSiteId,
                        report = report,
                        isProbing = false,
                    ),
                )
            }
        }
    }

    // ---- About & GitHub Release Updater ----

    fun setAutoUpdateCheck(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoUpdateCheck(enabled)
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            auxState.update { it.copy(updateState = UpdateUiState.Checking) }
            when (val outcome = ravenUpdater.checkForUpdate(manual = manual)) {
                is UpdateCheckOutcome.UpdateAvailable -> {
                    auxState.update {
                        it.copy(
                            updateState = UpdateUiState.Available(
                                release = outcome.release,
                                readyApk = outcome.release.cachedApk,
                            ),
                        )
                    }
                }
                is UpdateCheckOutcome.UpToDate -> {
                    auxState.update {
                        it.copy(
                            updateState = UpdateUiState.UpToDate(outcome.latestTag),
                            feedbackMessage = if (manual) "当前已是最新版本 (${outcome.currentVersion})" else it.feedbackMessage,
                        )
                    }
                }
                is UpdateCheckOutcome.DebugBuildSkipped -> {
                    auxState.update {
                        it.copy(
                            updateState = UpdateUiState.DebugSkipped(outcome.message),
                            feedbackMessage = if (manual) outcome.message else it.feedbackMessage,
                        )
                    }
                }
                UpdateCheckOutcome.Throttled -> {
                    auxState.update { it.copy(updateState = UpdateUiState.Idle) }
                }
                is UpdateCheckOutcome.Error -> {
                    auxState.update {
                        it.copy(
                            updateState = UpdateUiState.Error(outcome.message),
                            feedbackMessage = if (manual) outcome.message else it.feedbackMessage,
                        )
                    }
                }
            }
        }
    }

    fun downloadAvailableUpdate() {
        val current = auxState.value.updateState as? UpdateUiState.Available ?: return
        if (current.isDownloading) return
        val release = current.release
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            auxState.update {
                it.copy(
                    updateState = current.copy(
                        isDownloading = true,
                        downloadProgress = UpdateDownloadProgress(0L, release.asset.sizeBytes),
                        downloadError = null,
                    ),
                )
            }
            try {
                val verifiedFile = ravenUpdater.downloadAndVerify(release) { progress ->
                    auxState.update { state ->
                        val active = state.updateState as? UpdateUiState.Available ?: return@update state
                        state.copy(updateState = active.copy(downloadProgress = progress))
                    }
                }
                auxState.update { state ->
                    val active = state.updateState as? UpdateUiState.Available ?: return@update state
                    state.copy(
                        updateState = active.copy(
                            isDownloading = false,
                            downloadProgress = UpdateDownloadProgress(release.asset.sizeBytes, release.asset.sizeBytes),
                            readyApk = verifiedFile,
                            downloadError = null,
                        ),
                        feedbackMessage = "更新包 ${release.tagName} 已下载并通过签名校验",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                auxState.update { state ->
                    val active = state.updateState as? UpdateUiState.Available ?: return@update state
                    state.copy(
                        updateState = active.copy(
                            isDownloading = false,
                            downloadError = e.message ?: "更新包下载或校验失败",
                        ),
                    )
                }
            }
        }
    }

    fun dismissUpdateSheet() {
        val current = auxState.value.updateState
        if (current is UpdateUiState.Available && current.isDownloading) {
            updateJob?.cancel()
        }
        auxState.update { it.copy(updateState = UpdateUiState.Idle) }
    }

    fun clearFeedback() {
        auxState.update { it.copy(feedbackMessage = null) }
    }

    private fun initialAuthSiteItems(): List<AuthSiteAccountItem> =
        siteRegistry.allPlugins()
            .filter { it.authProvider.mode != AuthMode.None }
            .map { plugin ->
                AuthSiteAccountItem(
                    descriptor = plugin.descriptor,
                    authMode = plugin.authProvider.mode,
                )
            }
}
