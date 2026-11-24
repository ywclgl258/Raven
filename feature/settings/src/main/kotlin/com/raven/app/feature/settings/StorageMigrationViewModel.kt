package com.raven.app.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.media.download.DownloadCoordinator
import com.raven.app.core.media.storage.StorageMigrationResult
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.media.storage.StorageStatus
import com.raven.app.core.model.StorageLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates pausing active download writers and running SHA-256 verified migration from
 * PRIVATE to SHARED storage. Production delegates directly to [DownloadCoordinator.migrateToSharedStorage].
 */
fun interface StorageMigrationExecutor {
    suspend fun migrateToShared(
        onProgress: (doneBytes: Long, totalBytes: Long, currentFileName: String) -> Unit,
    ): StorageMigrationResult
}

data class MigrationProgressState(
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val currentFileName: String = "",
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

data class StorageMigrationUiState(
    val privatePath: String = "",
    val sharedSupported: Boolean = false,
    val status: StorageStatus? = null,
    val isRefreshing: Boolean = false,
    val isMigrating: Boolean = false,
    val progress: MigrationProgressState? = null,
    val lastResult: StorageMigrationResult? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
) {
    val canStartMigrationToShared: Boolean
        get() {
            val s = status ?: return false
            return sharedSupported &&
                s.activeLocation == StorageLocation.PRIVATE &&
                s.sharedPermissionGranted &&
                s.pendingLocation == null &&
                !isMigrating
        }

    val canScheduleSwitchToPrivate: Boolean
        get() {
            val s = status ?: return false
            return s.activeLocation == StorageLocation.SHARED &&
                s.pendingLocation != StorageLocation.PRIVATE &&
                !isMigrating
        }

    val canGrantSharedPermission: Boolean
        get() {
            val s = status ?: return false
            return sharedSupported && !s.sharedPermissionGranted && !isMigrating
        }
}

/**
 * ViewModel backing the dedicated `StorageMigrationScreen` (single-screen layout, no modal dialogs).
 */
class StorageMigrationViewModel(
    private val storageRootManager: StorageRootManager,
    private val migrationExecutor: StorageMigrationExecutor,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    constructor(
        storageRootManager: StorageRootManager,
        downloadCoordinator: DownloadCoordinator,
        dispatchers: AppDispatchers,
    ) : this(
        storageRootManager = storageRootManager,
        migrationExecutor = StorageMigrationExecutor { onProgress ->
            downloadCoordinator.migrateToSharedStorage(onProgress)
        },
        dispatchers = dispatchers,
    )

    private val _uiState = MutableStateFlow(
        StorageMigrationUiState(
            privatePath = storageRootManager.layoutOf(StorageLocation.PRIVATE).root.path,
            sharedSupported = storageRootManager.isSharedSupported(),
            status = runCatching { storageRootManager.status() }.getOrNull(),
        ),
    )
    val uiState: StateFlow<StorageMigrationUiState> = _uiState.asStateFlow()

    private var migrationJob: Job? = null

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val refreshed = withContext(dispatchers.io) {
                storageRootManager.status()
            }
            _uiState.update {
                it.copy(
                    privatePath = storageRootManager.layoutOf(StorageLocation.PRIVATE).root.path,
                    sharedSupported = storageRootManager.isSharedSupported(),
                    status = refreshed,
                    isRefreshing = false,
                )
            }
        }
    }

    fun permissionIntent(): Intent? = storageRootManager.permissionIntent()

    fun startMigrationToShared() {
        val current = _uiState.value
        if (!current.canStartMigrationToShared) {
            val status = current.status
            val reason = when {
                !current.sharedSupported -> "当前设备不支持外部公共存储目录"
                status == null || !status.sharedPermissionGranted -> "请先授予所有文件访问权限后再开始迁移"
                status.pendingLocation != null -> "下载位置已预约切换，请重启应用后生效"
                status.activeLocation == StorageLocation.SHARED -> "当前已处于公共存储目录"
                else -> "当前无法启动迁移，请稍后重试"
            }
            _uiState.update { it.copy(errorMessage = reason) }
            return
        }

        migrationJob?.cancel()
        migrationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isMigrating = true,
                    progress = MigrationProgressState(),
                    lastResult = null,
                    errorMessage = null,
                    infoMessage = null,
                )
            }
            try {
                val result = withContext(dispatchers.io) {
                    migrationExecutor.migrateToShared { doneBytes, totalBytes, fileName ->
                        _uiState.update { state ->
                            state.copy(
                                progress = MigrationProgressState(
                                    doneBytes = doneBytes,
                                    totalBytes = totalBytes,
                                    currentFileName = fileName,
                                ),
                            )
                        }
                    }
                }
                val refreshed = withContext(dispatchers.io) { storageRootManager.status() }
                _uiState.update {
                    it.copy(
                        status = refreshed,
                        isMigrating = false,
                        progress = null,
                        lastResult = result,
                        errorMessage = null,
                        infoMessage = "SHA-256 完整性校验全部通过。下次冷启动应用后将自动切换至公共目录，应用内原始文件已安全保留。",
                    )
                }
            } catch (ce: CancellationException) {
                val refreshed = runCatching { withContext(dispatchers.io) { storageRootManager.status() } }.getOrNull()
                _uiState.update {
                    it.copy(
                        status = refreshed ?: it.status,
                        isMigrating = false,
                        progress = null,
                        infoMessage = "迁移已取消，当前存储位置未更改。",
                    )
                }
                throw ce
            } catch (e: Exception) {
                val refreshed = runCatching { withContext(dispatchers.io) { storageRootManager.status() } }.getOrNull()
                _uiState.update {
                    it.copy(
                        status = refreshed ?: it.status,
                        isMigrating = false,
                        progress = null,
                        errorMessage = e.message ?: "迁移失败，下载位置保持不变",
                    )
                }
            }
        }
    }

    fun cancelMigration() {
        if (!_uiState.value.isMigrating) return
        migrationJob?.cancel()
    }

    fun requestSwitchToPrivateOnNextRestart() {
        if (!_uiState.value.canScheduleSwitchToPrivate) return
        viewModelScope.launch {
            try {
                withContext(dispatchers.io) {
                    storageRootManager.requestDeferredSwitch(StorageLocation.PRIVATE)
                }
                val refreshed = withContext(dispatchers.io) { storageRootManager.status() }
                _uiState.update {
                    it.copy(
                        status = refreshed,
                        errorMessage = null,
                        infoMessage = "已预约切回应用内私有存储，下次冷启动应用后生效。公共目录已有文件不会被删除。",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.message ?: "无法预约切换存储位置")
                }
            }
        }
    }
}
