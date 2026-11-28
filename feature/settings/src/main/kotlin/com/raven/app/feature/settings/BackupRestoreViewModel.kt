package com.raven.app.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.model.BackupSummary as DatabaseBackupSummary
import com.raven.app.core.database.repository.BackupDataRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.security.BackupCodec
import com.raven.app.core.security.BackupFormat
import com.raven.app.core.security.BackupSummary as SecurityBackupSummary
import com.raven.app.core.security.BackupValidator
import com.raven.app.core.security.DecodedBackup
import com.raven.app.core.security.WebDavConfig
import com.raven.app.core.security.WebDavCredentialStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage Access Framework I/O bridge abstracted over URI strings so [BackupRestoreViewModel]
 * works seamlessly with Android `ContentResolver` in production and remains 100% pure-JVM testable.
 */
interface BackupSafBridge {
    suspend fun writeBytes(documentUri: String, bytes: ByteArray)
    suspend fun readBytes(documentUri: String, maxBytes: Int): ByteArray
    fun releaseUriGrant(documentUri: String)
}

class AndroidBackupSafBridge(
    private val context: Context,
    private val dispatchers: AppDispatchers,
) : BackupSafBridge {

    override suspend fun writeBytes(documentUri: String, bytes: ByteArray) = withContext(dispatchers.io) {
        val uri = Uri.parse(documentUri)
        val resolver = context.applicationContext.contentResolver
        val out = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("无法打开所选目标文件进行写入")
        out.use { stream ->
            stream.write(bytes)
            stream.flush()
        }
    }

    override suspend fun readBytes(documentUri: String, maxBytes: Int): ByteArray = withContext(dispatchers.io) {
        val uri = Uri.parse(documentUri)
        val resolver = context.applicationContext.contentResolver
        val input = resolver.openInputStream(uri)
            ?: throw IOException("无法读取所选备份文件")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (out.size() + read > maxBytes) {
                    throw IOException("备份文件超过最大体积上限，已停止读取")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    override fun releaseUriGrant(documentUri: String) {
        runCatching {
            val uri = Uri.parse(documentUri)
            val resolver = context.applicationContext.contentResolver
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.releasePersistableUriPermission(uri, flags)
        }
    }
}

enum class BackupRestoreTab {
    EXPORT,
    IMPORT,
    WEBDAV,
}

data class ExportCompletedSummary(
    val summary: SecurityBackupSummary,
    val encryptedBytes: Int,
)

data class ImportPreviewState(
    val format: BackupFormat,
    val summary: SecurityBackupSummary,
    val warnings: List<String>,
)

data class RestoreCompletedSummary(
    val format: BackupFormat,
    val mergedSummary: DatabaseBackupSummary,
)

/**
 * Staged cloud snapshot. Mirrors [ImportPreviewState] so a WebDAV pull shows the same
 * counts, warnings and explicit-confirm gate as a local file restore.
 */
data class WebDavPullPreviewState(
    val remoteFileName: String,
    val endpointLabel: String,
    val downloadedBytes: Int,
    val downloadedAtMs: Long,
    val format: BackupFormat,
    val summary: SecurityBackupSummary,
    val warnings: List<String>,
)

data class BackupRestoreUiState(
    val activeTab: BackupRestoreTab = BackupRestoreTab.EXPORT,
    // Export inputs & result
    val exportPassword: String = "",
    val exportPasswordConfirm: String = "",
    val isExporting: Boolean = false,
    val exportError: String? = null,
    val exportCompleted: ExportCompletedSummary? = null,
    // Import inputs, staged preview, & result
    val hasSelectedImportFile: Boolean = false,
    val detectedImportFormat: BackupFormat? = null,
    val importFileSizeBytes: Int = 0,
    val importPassword: String = "",
    val isDecrypting: Boolean = false,
    val importPreview: ImportPreviewState? = null,
    val isRestoring: Boolean = false,
    val importError: String? = null,
    val restoreCompleted: RestoreCompletedSummary? = null,
    // WebDAV Cloud Sync
    val webDavEndpointUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val webDavPassphrase: String = "",
    val webDavRemoteFileName: String = WebDavConfig.DEFAULT_REMOTE_FILE_NAME,
    val webDavSaved: Boolean = false,
    val webDavLastSyncAt: Long = 0L,
    val isWebDavBusy: Boolean = false,
    val webDavStatusMessage: String? = null,
    val webDavError: String? = null,
    val webDavPullPreview: WebDavPullPreviewState? = null,
    val webDavRestoreCompleted: RestoreCompletedSummary? = null,
    /** Non-fatal notices (e.g. cloud op succeeded but the local timestamp write failed). */
    val webDavWarnings: List<String> = emptyList(),
) {
    val exportPasswordValidationError: String?
        get() = when {
            exportPassword.isEmpty() -> null
            exportPassword.length < BackupCodec.MIN_PASSWORD_LENGTH ->
                "密码至少需要 ${BackupCodec.MIN_PASSWORD_LENGTH} 个字符"
            exportPasswordConfirm.isNotEmpty() && exportPassword != exportPasswordConfirm ->
                "两次输入的密码不一致"
            else -> null
        }

    val canStartExport: Boolean
        get() = !isExporting &&
            exportPassword.length >= BackupCodec.MIN_PASSWORD_LENGTH &&
            exportPassword == exportPasswordConfirm

    val canDecryptImport: Boolean
        get() = hasSelectedImportFile &&
            detectedImportFormat != null &&
            importPassword.isNotEmpty() &&
            !isDecrypting &&
            !isRestoring
}

/**
 * ViewModel powering `BackupRestoreScreen`:
 * - Export: password (>=8 chars + confirm) -> `BackupDataRepository.exportPayload()` ->
 *   `BackupCodec.encode()` (`RVNBK01\0`) -> write to SAF URI -> release SAF grant.
 * - Import: read SAF URI -> detect format (`RVNBK01\0` or legacy `R34VBK01`) -> password ->
 *   `BackupCodec.decode()` + `BackupValidator.validate()` -> render `BackupSummary` preview &
 *   warnings -> explicit Confirm -> atomic `BackupDataRepository.applyBackup()` -> release SAF grant!
 */
class BackupRestoreViewModel(
    private val backupDataRepository: BackupDataRepository,
    private val backupCodec: BackupCodec,
    private val backupValidator: BackupValidator,
    private val safBridge: BackupSafBridge,
    private val appInfo: RavenAppInfo,
    private val dispatchers: AppDispatchers,
    private val webDavCredentialStore: WebDavCredentialStore? = null,
    private val webDavSyncClient: WebDavSyncClient? = null,
    private val preferencesRepository: PreferencesRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    init {
        if (webDavCredentialStore != null || preferencesRepository != null) {
            viewModelScope.launch {
                val config = withContext(dispatchers.io) {
                    runCatching { webDavCredentialStore?.load() }.getOrNull()
                }
                val lastSync = runCatching { preferencesRepository?.currentSettings()?.webDavLastSyncAt ?: 0L }
                    .getOrDefault(0L)
                _uiState.update { state ->
                    state.copy(
                        webDavEndpointUrl = config?.endpointUrl ?: state.webDavEndpointUrl,
                        webDavUsername = config?.username ?: state.webDavUsername,
                        webDavPassword = config?.password ?: state.webDavPassword,
                        webDavPassphrase = config?.backupPassphrase ?: state.webDavPassphrase,
                        webDavRemoteFileName = config?.remoteFileName ?: state.webDavRemoteFileName,
                        webDavSaved = config != null,
                        webDavLastSyncAt = lastSync,
                    )
                }
            }
        }
    }

    private var stagedImportUri: String? = null
    private var stagedImportBytes: ByteArray? = null
    private var stagedDecodedBackup: DecodedBackup? = null
    private var importGeneration: Long = 0L
    private var decryptJob: Job? = null

    /**
     * Decrypted cloud snapshot awaiting explicit confirmation. Held in memory only (never written
     * to Room, never logged) so a cancelled/screen-recreated pull cannot silently alter local data.
     */
    private var cachedWebDavPreview: WebDavSyncOutcome.PullPreviewReady? = null

    fun selectTab(tab: BackupRestoreTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    // ---- Export Flow ----

    fun updateExportPassword(value: String) {
        // 开始下一次导出时就撤掉上一次的绿色成功态，避免旧结果被误读成本次结果。
        _uiState.update { it.copy(exportPassword = value, exportError = null, exportCompleted = null) }
    }

    fun updateExportPasswordConfirm(value: String) {
        _uiState.update { it.copy(exportPasswordConfirm = value, exportError = null, exportCompleted = null) }
    }

    fun onExportPasswordRequired() {
        _uiState.update { it.copy(exportError = "已保留所选文件。请重新输入密码并点击导出。") }
    }

    fun onDocumentPickerUnavailable(tab: BackupRestoreTab) {
        val message = "系统文件选择器不可用"
        _uiState.update { if (tab == BackupRestoreTab.IMPORT) it.copy(importError = message) else it.copy(exportError = message) }
    }

    fun exportToUri(uri: Uri?) {
        exportToDocument(uri?.toString())
    }

    fun exportToDocument(documentUri: String?) {
        if (documentUri.isNullOrBlank()) return
        val current = _uiState.value
        if (current.isExporting) return

        if (current.exportPassword.length < BackupCodec.MIN_PASSWORD_LENGTH) {
            _uiState.update { it.copy(exportError = "备份密码至少需要 ${BackupCodec.MIN_PASSWORD_LENGTH} 个字符") }
            safBridge.releaseUriGrant(documentUri)
            return
        }
        if (current.exportPassword != current.exportPasswordConfirm) {
            _uiState.update { it.copy(exportError = "两次输入的密码不一致") }
            safBridge.releaseUriGrant(documentUri)
            return
        }

        val passwordChars = current.exportPassword.toCharArray()
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportError = null, exportCompleted = null) }
            try {
                val (summary, wireSize) = withContext(dispatchers.io) {
                    val payload = backupDataRepository.exportPayload(appVersion = appInfo.versionName)
                    val summary = backupValidator.validate(payload)
                    val encrypted = backupCodec.encode(payload, passwordChars)
                    try {
                        safBridge.writeBytes(documentUri, encrypted)
                        summary to encrypted.size
                    } finally {
                        encrypted.fill(0)
                    }
                }
                _uiState.update {
                    it.copy(
                        exportPassword = "",
                        exportPasswordConfirm = "",
                        isExporting = false,
                        exportError = null,
                        exportCompleted = ExportCompletedSummary(
                            summary = summary,
                            encryptedBytes = wireSize,
                        ),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportError = e.message ?: "导出加密备份失败，请重试",
                    )
                }
            } finally {
                passwordChars.fill('\u0000')
                safBridge.releaseUriGrant(documentUri)
            }
        }
    }

    // ---- Import Flow ----

    fun selectImportUri(uri: Uri?) {
        selectImportDocument(uri?.toString())
    }

    fun selectImportDocument(documentUri: String?) {
        if (documentUri.isNullOrBlank() || _uiState.value.isRestoring) return
        decryptJob?.cancel()
        decryptJob = null
        releaseStagedImport()
        val generation = importGeneration
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeTab = BackupRestoreTab.IMPORT,
                    isDecrypting = true,
                    importError = null,
                    importPreview = null,
                    restoreCompleted = null,
                )
            }
            try {
                val rawBytes = withContext(dispatchers.io) {
                    safBridge.readBytes(documentUri, MAX_BACKUP_WIRE_BYTES)
                }
                if (generation != importGeneration) {
                    rawBytes.fill(0)
                    safBridge.releaseUriGrant(documentUri)
                    return@launch
                }
                val detected = backupCodec.detectFormat(rawBytes)
                if (detected == null) {
                    rawBytes.fill(0)
                    safBridge.releaseUriGrant(documentUri)
                    _uiState.update {
                        it.copy(
                            hasSelectedImportFile = false,
                            detectedImportFormat = null,
                            importFileSizeBytes = 0,
                            isDecrypting = false,
                            importError = "所选文件不是受支持的 Raven (RVNBK01) 或 R34V (R34VBK01) 加密备份",
                        )
                    }
                    return@launch
                }

                stagedImportUri = documentUri
                stagedImportBytes = rawBytes
                stagedDecodedBackup = null
                _uiState.update {
                    it.copy(
                        hasSelectedImportFile = true,
                        detectedImportFormat = detected,
                        importFileSizeBytes = rawBytes.size,
                        importPassword = "",
                        isDecrypting = false,
                        importPreview = null,
                        importError = null,
                    )
                }
            } catch (ce: CancellationException) {
                safBridge.releaseUriGrant(documentUri)
                throw ce
            } catch (e: Exception) {
                safBridge.releaseUriGrant(documentUri)
                if (generation == importGeneration) {
                    _uiState.update {
                        it.copy(
                            hasSelectedImportFile = false,
                            detectedImportFormat = null,
                            importFileSizeBytes = 0,
                            isDecrypting = false,
                            importError = e.message ?: "读取备份文件失败",
                        )
                    }
                }
            }
        }
    }

    fun updateImportPassword(value: String) {
        _uiState.update { it.copy(importPassword = value, importError = null) }
    }

    fun decryptAndPreviewImport() {
        val current = _uiState.value
        val wireBytes = stagedImportBytes
        if (wireBytes == null || current.isDecrypting || current.isRestoring) return
        if (current.importPassword.isEmpty()) {
            _uiState.update { it.copy(importError = "请输入备份解密密码") }
            return
        }

        val passwordChars = current.importPassword.toCharArray()
        val generation = importGeneration
        decryptJob = viewModelScope.launch {
            if (generation != importGeneration) return@launch
            _uiState.update { it.copy(isDecrypting = true, importError = null) }
            try {
                val (decoded, validatedSummary) = withContext(dispatchers.default) {
                    val result = backupCodec.decode(wireBytes, passwordChars)
                    val verifiedSummary = backupValidator.validate(result.payload)
                    result to verifiedSummary
                }
                if (generation != importGeneration || stagedImportBytes !== wireBytes) {
                    return@launch
                }
                stagedDecodedBackup = decoded
                val warnings = buildImportWarnings(decoded.format, validatedSummary)
                _uiState.update {
                    it.copy(
                        isDecrypting = false,
                        importPassword = "",
                        importError = null,
                        importPreview = ImportPreviewState(
                            format = decoded.format,
                            summary = validatedSummary,
                            warnings = warnings,
                        ),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (generation == importGeneration) {
                    _uiState.update {
                        it.copy(
                            isDecrypting = false,
                            importError = e.message ?: "解密或校验失败，请确认密码是否正确",
                        )
                    }
                }
            } finally {
                passwordChars.fill('\u0000')
            }
        }
    }

    fun confirmAndRestoreImport() {
        val decoded = stagedDecodedBackup ?: return
        if (_uiState.value.isRestoring) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true, importError = null) }
            try {
                val mergedSummary = withContext(dispatchers.io) {
                    backupDataRepository.applyBackup(
                        incoming = decoded.payload,
                        appVersion = appInfo.versionName,
                    )
                }
                releaseStagedImport()
                _uiState.update {
                    it.copy(
                        hasSelectedImportFile = false,
                        detectedImportFormat = null,
                        importFileSizeBytes = 0,
                        importPassword = "",
                        importPreview = null,
                        isRestoring = false,
                        importError = null,
                        restoreCompleted = RestoreCompletedSummary(
                            format = decoded.format,
                            mergedSummary = mergedSummary,
                        ),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // Atomic transaction rolled back cleanly; release temporary SAF grant on error as well.
                releaseStagedImport()
                _uiState.update {
                    it.copy(
                        hasSelectedImportFile = false,
                        detectedImportFormat = null,
                        importFileSizeBytes = 0,
                        importPreview = null,
                        isRestoring = false,
                        importError = e.message ?: "恢复合并失败，已完整回滚本地数据",
                    )
                }
            }
        }
    }

    fun cancelStagedImport() {
        releaseStagedImport()
        _uiState.update {
            it.copy(
                hasSelectedImportFile = false,
                detectedImportFormat = null,
                importFileSizeBytes = 0,
                importPassword = "",
                importPreview = null,
                importError = null,
            )
        }
    }

    // ---- WebDAV Cloud Sync ----

    fun updateWebDavEndpointUrl(value: String) {
        _uiState.update { it.copy(webDavEndpointUrl = value, webDavError = null) }
    }

    fun updateWebDavUsername(value: String) {
        _uiState.update { it.copy(webDavUsername = value, webDavError = null) }
    }

    fun updateWebDavPassword(value: String) {
        _uiState.update { it.copy(webDavPassword = value, webDavError = null) }
    }

    fun updateWebDavPassphrase(value: String) {
        _uiState.update { it.copy(webDavPassphrase = value, webDavError = null) }
    }

    fun updateWebDavRemoteFileName(value: String) {
        _uiState.update { it.copy(webDavRemoteFileName = value, webDavError = null) }
    }

    private fun currentWebDavDraft(): WebDavConfig = WebDavConfig(
        endpointUrl = _uiState.value.webDavEndpointUrl,
        username = _uiState.value.webDavUsername,
        password = _uiState.value.webDavPassword,
        backupPassphrase = _uiState.value.webDavPassphrase,
        remoteFileName = _uiState.value.webDavRemoteFileName,
    )

    fun saveWebDavConfig() {
        val store = webDavCredentialStore ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isWebDavBusy = true, webDavError = null, webDavStatusMessage = null, webDavWarnings = emptyList()) }
            try {
                val saved = withContext(dispatchers.io) { store.save(currentWebDavDraft()) }
                // Re-read the persisted record so the banner only claims "已保存" when it really is.
                val persisted = withContext(dispatchers.io) { runCatching { store.load() }.getOrNull() }
                if (persisted == null) {
                    _uiState.update {
                        it.copy(
                            isWebDavBusy = false,
                            webDavSaved = false,
                            webDavError = "配置写入后未能从本机加密存储读回，请重新保存",
                        )
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        webDavEndpointUrl = saved.endpointUrl,
                        webDavUsername = saved.username,
                        webDavPassword = saved.password,
                        webDavPassphrase = saved.backupPassphrase,
                        webDavRemoteFileName = saved.remoteFileName,
                        webDavSaved = true,
                        isWebDavBusy = false,
                        webDavStatusMessage = "WebDAV 配置已使用 AES-256-GCM 加密保存在本地",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isWebDavBusy = false,
                        // Credentials were NOT persisted; keep the fields as the user typed them.
                        webDavSaved = false,
                        webDavError = e.message ?: "WebDAV 配置校验失败",
                    )
                }
            }
        }
    }

    fun clearWebDavConfig() {
        val store = webDavCredentialStore ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isWebDavBusy = true, webDavError = null, webDavStatusMessage = null) }
            try {
                withContext(dispatchers.io) { store.clear() }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // Clear failed: the record may still exist, so never claim success and never wipe
                // the fields (they still mirror the stored config).
                _uiState.update {
                    it.copy(
                        isWebDavBusy = false,
                        webDavError = e.message ?: "清除本机 WebDAV 凭据失败，配置仍可能保留在本机",
                    )
                }
                return@launch
            }
            cachedWebDavPreview = null
            _uiState.update {
                it.copy(
                    webDavEndpointUrl = "",
                    webDavUsername = "",
                    webDavPassword = "",
                    webDavPassphrase = "",
                    webDavRemoteFileName = WebDavConfig.DEFAULT_REMOTE_FILE_NAME,
                    webDavSaved = false,
                    webDavPullPreview = null,
                    isWebDavBusy = false,
                    webDavError = null,
                    webDavStatusMessage = "已清除本地保存的 WebDAV 凭据",
                )
            }
        }
    }

    fun testWebDavConnection() {
        val client = webDavSyncClient ?: return
        if (_uiState.value.isWebDavBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isWebDavBusy = true, webDavError = null, webDavStatusMessage = null, webDavWarnings = emptyList()) }
            when (val outcome = client.testConnection(currentWebDavDraft())) {
                is WebDavSyncOutcome.Connected -> {
                    _uiState.update { it.copy(isWebDavBusy = false, webDavStatusMessage = outcome.message) }
                }
                is WebDavSyncOutcome.Error -> {
                    _uiState.update { it.copy(isWebDavBusy = false, webDavError = outcome.message) }
                }
                else -> _uiState.update { it.copy(isWebDavBusy = false) }
            }
        }
    }

    fun pushWebDavBackup() {
        val client = webDavSyncClient ?: return
        if (_uiState.value.isWebDavBusy) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isWebDavBusy = true,
                    webDavError = null,
                    webDavStatusMessage = null,
                    webDavWarnings = emptyList(),
                    webDavRestoreCompleted = null,
                )
            }
            val draft = currentWebDavDraft()
            when (val outcome = client.pushBackup(draft)) {
                is WebDavSyncOutcome.PushCompleted -> {
                    val uploadMessage = "已加密上传 (${outcome.uploadedBytes} B · ${outcome.summary.favoritesCount} 收藏 · ${outcome.summary.shelvesCount} 片单)"
                    _uiState.update {
                        it.copy(
                            webDavLastSyncAt = outcome.syncedAtMs,
                            isWebDavBusy = false,
                            webDavStatusMessage = uploadMessage,
                            webDavWarnings = listOfNotNull(outcome.timestampWarning),
                        )
                    }
                    // Credentials are persisted only after the upload path validated them; a failure
                    // here must not be reported as a failed upload.
                    runCatching { withContext(dispatchers.io) { webDavCredentialStore?.save(draft) } }
                        .onSuccess { saved ->
                            _uiState.update { state ->
                                if (saved == null) {
                                    state
                                } else {
                                    // Mirror the normalized record so the form matches what is stored.
                                    state.copy(
                                        webDavSaved = true,
                                        webDavEndpointUrl = saved.endpointUrl,
                                        webDavRemoteFileName = saved.remoteFileName,
                                    )
                                }
                            }
                        }
                        .onFailure { saveError ->
                            _uiState.update { state ->
                                state.copy(
                                    webDavSaved = false,
                                    webDavWarnings = state.webDavWarnings +
                                        "上传已成功，但本机未能保存 WebDAV 配置（${saveError.message ?: "未知错误"}）。",
                                )
                            }
                        }
                }
                is WebDavSyncOutcome.Error -> {
                    _uiState.update { it.copy(isWebDavBusy = false, webDavError = outcome.message) }
                }
                else -> _uiState.update { it.copy(isWebDavBusy = false) }
            }
        }
    }

    /**
     * Downloads + decrypts + validates the cloud snapshot and stops there. Room is untouched until
     * [confirmWebDavRestore] runs, matching the local-file import contract.
     */
    fun pullAndPreviewWebDavBackup() {
        val client = webDavSyncClient ?: return
        if (_uiState.value.isWebDavBusy) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isWebDavBusy = true,
                    webDavError = null,
                    webDavStatusMessage = null,
                    webDavWarnings = emptyList(),
                    webDavPullPreview = null,
                    webDavRestoreCompleted = null,
                )
            }
            when (val outcome = client.pullPreview(currentWebDavDraft())) {
                is WebDavSyncOutcome.PullPreviewReady -> {
                    _uiState.update {
                        it.copy(
                            isWebDavBusy = false,
                            webDavPullPreview = WebDavPullPreviewState(
                                remoteFileName = outcome.remoteFileName,
                                endpointLabel = outcome.endpointLabel,
                                downloadedBytes = outcome.downloadedBytes,
                                downloadedAtMs = outcome.downloadedAtMs,
                                format = outcome.decoded.format,
                                summary = outcome.summary,
                                warnings = buildImportWarnings(outcome.decoded.format, outcome.summary),
                            ),
                            webDavStatusMessage = "云端备份已下载并通过校验，确认后才会合并到本机",
                        )
                    }
                    cachedWebDavPreview = outcome
                }
                is WebDavSyncOutcome.Error -> {
                    _uiState.update { it.copy(isWebDavBusy = false, webDavError = outcome.message) }
                }
                else -> _uiState.update { it.copy(isWebDavBusy = false) }
            }
        }
    }

    fun confirmWebDavRestore() {
        val client = webDavSyncClient ?: return
        val preview = cachedWebDavPreview ?: return
        if (_uiState.value.isWebDavBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isWebDavBusy = true, webDavError = null, webDavWarnings = emptyList()) }
            when (val outcome = client.applyConfirmedPull(preview)) {
                is WebDavSyncOutcome.PullCompleted -> {
                    cachedWebDavPreview = null
                    _uiState.update {
                        it.copy(
                            isWebDavBusy = false,
                            webDavLastSyncAt = outcome.syncedAtMs,
                            webDavPullPreview = null,
                            webDavRestoreCompleted = RestoreCompletedSummary(
                                format = preview.decoded.format,
                                mergedSummary = outcome.mergedSummary,
                            ),
                            webDavStatusMessage = "已从云端恢复并合并 (${outcome.mergedSummary.favoritesCount} 收藏 · ${outcome.mergedSummary.totalHistoryCount} 历史)",
                            webDavWarnings = listOfNotNull(outcome.timestampWarning),
                        )
                    }
                }
                is WebDavSyncOutcome.Error -> {
                    _uiState.update {
                        // Merge rolled back atomically; keep the preview so the user can retry.
                        it.copy(isWebDavBusy = false, webDavError = outcome.message)
                    }
                }
                else -> _uiState.update { it.copy(isWebDavBusy = false) }
            }
        }
    }

    fun cancelWebDavPullPreview() {
        cachedWebDavPreview = null
        _uiState.update {
            it.copy(
                webDavPullPreview = null,
                webDavStatusMessage = null,
                webDavError = null,
                webDavWarnings = emptyList(),
            )
        }
    }

    private fun releaseStagedImport() {
        importGeneration += 1L
        decryptJob?.cancel()
        decryptJob = null
        stagedImportBytes?.fill(0)
        stagedImportBytes = null
        stagedDecodedBackup = null
        val uri = stagedImportUri
        stagedImportUri = null
        if (uri != null) {
            safBridge.releaseUriGrant(uri)
        }
    }

    override fun onCleared() {
        releaseStagedImport()
        cachedWebDavPreview = null
        super.onCleared()
    }

    private fun buildImportWarnings(
        format: BackupFormat,
        summary: SecurityBackupSummary,
    ): List<String> = buildList {
        if (format == BackupFormat.LEGACY_R34V_V1) {
            add("检测到旧版 R34V (R34VBK01) 加密备份，已无损转换为渡鸦 Raven 数据结构（含稍后看片单与本地索引映射）。")
        }
        if (summary.retiredSiteIdsPresent.isNotEmpty()) {
            val retiredLabels = summary.retiredSiteIdsPresent.joinToString("、") { it.value }
            add("备份包含已退役站点历史记录（$retiredLabels），将以只读历史条目完整保留。")
        }
        add("合并策略：在单次 Room 事务内原子合并——观看/阅读进度保留较新记录，收藏/书签/订阅/屏蔽词按唯一键去重取并集；绝不覆盖站点登录凭据或本地媒体文件。")
    }

    companion object {
        const val MAX_BACKUP_WIRE_BYTES: Int =
            RavenBackupPayload.MAX_PLAINTEXT_BYTES + BackupCodec.HEADER_BYTES + BackupCodec.GCM_TAG_BYTES
    }
}
