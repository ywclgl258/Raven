package com.raven.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.storage.DuplicateCopy
import com.raven.app.core.media.storage.DuplicateGroup
import com.raven.app.core.media.storage.DuplicateScanReport
import com.raven.app.core.media.storage.DuplicateScanner
import com.raven.app.core.media.storage.DuplicateSource
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deletes a single verified [DuplicateCopy] cleanly through its owning manager:
 * - [DuplicateSource.DOWNLOAD] -> `DownloadCoordinator.delete(copy.id, copy.sourceRoot)`
 * - [DuplicateSource.LOCAL] -> `LocalLibraryImporter.removeBook(copy.mediaId)`
 */
fun interface DuplicateCopyDeleter {
    suspend fun deleteCopy(copy: DuplicateCopy)
}

data class DuplicateScannerUiState(
    val isScanning: Boolean = false,
    val report: DuplicateScanReport? = null,
    val expandedFingerprints: Set<String> = emptySet(),
    val deletingIdentityKey: Triple<DuplicateSource, String, String>? = null,
    val pendingDeleteCopy: DuplicateCopy? = null,
    val pendingDeleteIsLastCopy: Boolean = false,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null,
) {
    val groups: List<DuplicateGroup> get() = report?.groups.orEmpty()
    val scannedBooks: Int get() = report?.scannedBooks ?: 0
    val hashedCandidates: Int get() = report?.hashedCandidates ?: 0
    val totalReclaimableBytes: Long get() = report?.totalReclaimableBytes ?: 0L
}

/**
 * ViewModel powering `DuplicateScannerScreen`:
 * runs `DuplicateScanner.scanAll()`, manages expandable duplicate groups, resolves site names via
 * [SiteRegistry], and performs explicit safe per-copy deletion.
 */
class DuplicateScannerViewModel(
    private val duplicateScanner: DuplicateScanner,
    private val copyDeleter: DuplicateCopyDeleter,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
    autoScanOnInit: Boolean = true,
) : ViewModel() {

    constructor(
        duplicateScanner: DuplicateScanner,
        downloadCoordinator: DownloadCoordinator,
        localLibraryImporter: LocalLibraryImporter,
        siteRegistry: SiteRegistry,
        dispatchers: AppDispatchers,
    ) : this(
        duplicateScanner = duplicateScanner,
        copyDeleter = DuplicateCopyDeleter { copy ->
            when (copy.source) {
                DuplicateSource.DOWNLOAD -> downloadCoordinator.delete(
                    id = copy.id,
                    sourceRoot = copy.sourceRoot,
                )
                DuplicateSource.LOCAL -> localLibraryImporter.removeBook(copy.mediaId)
            }
        },
        siteRegistry = siteRegistry,
        dispatchers = dispatchers,
        autoScanOnInit = true,
    )

    private val _uiState = MutableStateFlow(DuplicateScannerUiState())
    val uiState: StateFlow<DuplicateScannerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        if (autoScanOnInit) {
            startScan()
        }
    }

    fun siteDisplayName(siteId: SiteId): String = when {
        siteId.isLocal -> "本地导入库"
        else -> siteRegistry.get(siteId)?.descriptor?.displayName ?: siteId.value
    }

    fun startScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    errorMessage = null,
                    pendingDeleteCopy = null,
                )
            }
            try {
                val report = withContext(dispatchers.io) {
                    duplicateScanner.scanAll()
                }
                val defaultExpanded = report.groups.firstOrNull()?.fingerprint?.let(::setOf).orEmpty()
                _uiState.update { current ->
                    val preserved = current.expandedFingerprints.intersect(
                        report.groups.mapTo(HashSet()) { it.fingerprint },
                    )
                    current.copy(
                        isScanning = false,
                        report = report,
                        expandedFingerprints = preserved.ifEmpty { defaultExpanded },
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isScanning = false) }
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = e.message ?: "重复漫画扫描失败，请重试",
                    )
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun toggleGroupExpanded(fingerprint: String) {
        _uiState.update { current ->
            val next = if (fingerprint in current.expandedFingerprints) {
                current.expandedFingerprints - fingerprint
            } else {
                current.expandedFingerprints + fingerprint
            }
            current.copy(expandedFingerprints = next)
        }
    }

    fun requestDeleteCopy(group: DuplicateGroup, copy: DuplicateCopy) {
        _uiState.update {
            it.copy(
                pendingDeleteCopy = copy,
                pendingDeleteIsLastCopy = group.copies.size <= 1,
                errorMessage = null,
            )
        }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(pendingDeleteCopy = null, pendingDeleteIsLastCopy = false) }
    }

    fun confirmDeletePendingCopy() {
        val target = _uiState.value.pendingDeleteCopy ?: return
        if (_uiState.value.deletingIdentityKey != null) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    deletingIdentityKey = target.identityKey,
                    pendingDeleteCopy = null,
                    pendingDeleteIsLastCopy = false,
                    errorMessage = null,
                )
            }
            try {
                withContext(dispatchers.io) {
                    copyDeleter.deleteCopy(target)
                }
                _uiState.update { current ->
                    val updatedReport = current.report?.let { existing ->
                        val updatedGroups = existing.groups
                            .map { group ->
                                group.copy(copies = group.copies.filterNot { it.identityKey == target.identityKey })
                            }
                            .filter { it.copies.size > 1 }
                            .sortedByDescending { it.reclaimableBytes }
                        existing.copy(
                            groups = updatedGroups,
                            scannedBooks = (existing.scannedBooks - 1).coerceAtLeast(0),
                        )
                    }
                    current.copy(
                        report = updatedReport,
                        deletingIdentityKey = null,
                        feedbackMessage = "已安全删除副本：${target.title}",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        deletingIdentityKey = null,
                        errorMessage = e.message ?: "删除副本失败，请检查存储权限后重试",
                    )
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }
}
