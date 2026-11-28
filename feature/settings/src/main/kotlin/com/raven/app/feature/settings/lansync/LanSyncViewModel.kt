package com.raven.app.feature.settings.lansync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.security.BackupSummary as SecurityBackupSummary
import com.raven.app.core.security.LanSyncCrypto
import com.raven.app.core.security.LanSyncTrustStore
import com.raven.app.core.security.TrustedLanPeer
import com.raven.app.feature.settings.RavenAppInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LanSyncPullPreviewUi(
    val peerDeviceId: String,
    val peerDeviceName: String,
    val peerEndpoint: String,
    val peerAppVersion: String,
    val fetchedAtMs: Long,
    val summary: SecurityBackupSummary,
    val warnings: List<String>,
)

data class LanSyncUiState(
    val localDeviceId: String = "",
    val localDeviceName: String = LanSyncTrustStore.DEFAULT_DEVICE_NAME,
    val localFingerprint: String = "",
    val localAddresses: List<String> = emptyList(),
    val listeningPort: Int = 0,
    val pairingPin: String = "",
    val isDiscoverable: Boolean = true,
    val isStarting: Boolean = false,
    val discoveredPeers: List<DiscoveredLanPeer> = emptyList(),
    val trustedPeers: List<TrustedLanPeer> = emptyList(),
    val selectedSyncMode: LanSyncMode = LanSyncMode.TWO_WAY,
    val syncingPeerId: String? = null,
    val isManualSyncing: Boolean = false,
    val pullPreview: LanSyncPullPreviewUi? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val incomingEvents: List<LanSyncIncomingEvent> = emptyList(),
) {
    val isBusy: Boolean get() = syncingPeerId != null || isManualSyncing
}

class LanSyncViewModel(
    private val server: LanSyncServer,
    private val client: LanSyncClient,
    private val discoveryBridge: LanSyncDiscoveryBridge,
    private val trustStore: LanSyncTrustStore,
    private val appInfo: RavenAppInfo,
    private val dispatchers: AppDispatchers,
    private val defaultDeviceName: String = LanSyncTrustStore.DEFAULT_DEVICE_NAME,
    private val allowLoopbackForTest: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LanSyncUiState())
    val uiState: StateFlow<LanSyncUiState> = _uiState.asStateFlow()

    private var discoveryCollectorJob: Job? = null
    private var addressesCollectorJob: Job? = null
    private var cachedPullPreview: LanSyncOutcome.PullPreviewReady? = null
    private var sessionActive: Boolean = false

    init {
        viewModelScope.launch {
            val state = withContext(dispatchers.io) {
                trustStore.loadOrCreate(defaultDeviceName)
            }
            _uiState.update {
                it.copy(
                    localDeviceId = state.localDeviceId,
                    localDeviceName = state.localDeviceName,
                    localFingerprint = LanSyncCrypto.deviceFingerprint(state.localDeviceId),
                    pairingPin = server.currentPin(),
                    trustedPeers = state.trustedPeers,
                )
            }
        }
    }

    fun startSession() {
        if (sessionActive && server.isRunning()) return
        sessionActive = true

        if (discoveryCollectorJob == null) {
            discoveryCollectorJob = viewModelScope.launch {
                discoveryBridge.peers.collect { rawPeers ->
                    _uiState.update { state ->
                        val trustedById = state.trustedPeers.associateBy { it.deviceId }
                        val annotated = rawPeers.map { peer ->
                            val trusted = trustedById[peer.deviceId]
                            peer.copy(
                                isTrusted = trusted != null,
                                lastSyncedAtMs = trusted?.lastSyncedAtMs ?: 0L,
                            )
                        }
                        state.copy(discoveredPeers = annotated)
                    }
                }
            }
        }
        if (addressesCollectorJob == null) {
            addressesCollectorJob = viewModelScope.launch {
                discoveryBridge.localAddresses.collect { addrs ->
                    _uiState.update { it.copy(localAddresses = addrs) }
                }
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isStarting = true, errorMessage = null) }
            try {
                val trustState = withContext(dispatchers.io) {
                    trustStore.loadOrCreate(defaultDeviceName)
                }
                val port = server.start(
                    scope = viewModelScope,
                    onIncomingEvent = ::handleIncomingServerEvent,
                    onPinRotated = { newPin ->
                        _uiState.update {
                            it.copy(
                                pairingPin = newPin,
                                errorMessage = "检测到连续配对码校验失败，已自动更换新的 6 位配对码",
                            )
                        }
                    },
                )
                val discoverable = _uiState.value.isDiscoverable
                if (discoverable) {
                    discoveryBridge.start(
                        scope = viewModelScope,
                        localDeviceId = trustState.localDeviceId,
                        localDeviceName = trustState.localDeviceName,
                        appVersion = appInfo.versionName,
                        tcpPort = port,
                    )
                }
                _uiState.update {
                    it.copy(
                        localDeviceId = trustState.localDeviceId,
                        localDeviceName = trustState.localDeviceName,
                        localFingerprint = LanSyncCrypto.deviceFingerprint(trustState.localDeviceId),
                        listeningPort = port,
                        pairingPin = server.currentPin(),
                        trustedPeers = trustState.trustedPeers,
                        isStarting = false,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                sessionActive = false
                _uiState.update {
                    it.copy(
                        isStarting = false,
                        errorMessage = e.message ?: "无法启动局域网同步服务",
                    )
                }
            }
        }
    }

    fun stopSession() {
        sessionActive = false
        discoveryBridge.stop()
        server.stop()
        _uiState.update { it.copy(listeningPort = 0, isStarting = false) }
    }

    fun setDiscoverable(enabled: Boolean) {
        _uiState.update { it.copy(isDiscoverable = enabled, errorMessage = null) }
        val state = _uiState.value
        if (!sessionActive || state.listeningPort <= 0) return
        if (enabled) {
            discoveryBridge.start(
                scope = viewModelScope,
                localDeviceId = state.localDeviceId,
                localDeviceName = state.localDeviceName,
                appVersion = appInfo.versionName,
                tcpPort = state.listeningPort,
            )
        } else {
            discoveryBridge.stop()
            _uiState.update { it.copy(discoveredPeers = emptyList()) }
        }
    }

    fun refreshDiscovery() {
        if (!_uiState.value.isDiscoverable) {
            setDiscoverable(true)
            return
        }
        discoveryBridge.refreshNow()
        _uiState.update { it.copy(statusMessage = "正在重新扫描局域网内的 Raven 设备…", errorMessage = null) }
    }

    fun regeneratePairingPin() {
        val nextPin = server.rotatePin()
        _uiState.update {
            it.copy(
                pairingPin = nextPin,
                statusMessage = "已刷新本机配对码",
                errorMessage = null,
            )
        }
    }

    fun updateLocalDeviceName(rawName: String) {
        viewModelScope.launch {
            try {
                val updated = withContext(dispatchers.io) {
                    trustStore.updateLocalDeviceName(rawName)
                }
                _uiState.update {
                    it.copy(
                        localDeviceName = updated.localDeviceName,
                        statusMessage = "本机名称已更新为 ${updated.localDeviceName}",
                        errorMessage = null,
                    )
                }
                val port = _uiState.value.listeningPort
                if (sessionActive && _uiState.value.isDiscoverable && port > 0) {
                    discoveryBridge.start(
                        scope = viewModelScope,
                        localDeviceId = updated.localDeviceId,
                        localDeviceName = updated.localDeviceName,
                        appVersion = appInfo.versionName,
                        tcpPort = port,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "更新设备名称失败") }
            }
        }
    }

    fun selectSyncMode(mode: LanSyncMode) {
        _uiState.update { it.copy(selectedSyncMode = mode, errorMessage = null) }
    }

    fun syncWithPeer(
        peer: DiscoveredLanPeer,
        pin: String? = null,
        rememberDevice: Boolean = true,
    ) {
        if (_uiState.value.isBusy) return
        val mode = _uiState.value.selectedSyncMode
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    syncingPeerId = peer.deviceId,
                    errorMessage = null,
                    statusMessage = null,
                    pullPreview = null,
                )
            }
            cachedPullPreview = null
            executeSyncTarget(
                host = peer.host,
                port = peer.port,
                expectedDeviceId = peer.deviceId,
                pin = pin,
                rememberDevice = rememberDevice,
                mode = mode,
                manual = false,
            )
        }
    }

    fun syncWithManualEndpoint(
        rawEndpoint: String,
        pin: String,
        rememberDevice: Boolean = true,
    ) {
        if (_uiState.value.isBusy) return
        val parsed = LanSyncAddressValidator.parseManualEndpoint(
            raw = rawEndpoint,
            defaultPort = LanSyncConstants.DEFAULT_TCP_PORT,
            allowLoopback = allowLoopbackForTest,
        )
        if (parsed == null) {
            _uiState.update {
                it.copy(
                    errorMessage = "请输入有效的局域网私有 IPv4 地址（例如 192.168.1.25:${LanSyncConstants.DEFAULT_TCP_PORT}）",
                )
            }
            return
        }
        val mode = _uiState.value.selectedSyncMode
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isManualSyncing = true,
                    errorMessage = null,
                    statusMessage = null,
                    pullPreview = null,
                )
            }
            cachedPullPreview = null
            executeSyncTarget(
                host = parsed.first,
                port = parsed.second,
                expectedDeviceId = null,
                pin = pin,
                rememberDevice = rememberDevice,
                mode = mode,
                manual = true,
            )
        }
    }

    fun confirmPullPreview() {
        val preview = cachedPullPreview ?: return
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isManualSyncing = true, errorMessage = null) }
            try {
                val completed = client.applyConfirmedPull(preview)
                cachedPullPreview = null
                val trustState = withContext(dispatchers.io) {
                    trustStore.loadOrCreate(defaultDeviceName)
                }
                _uiState.update { state ->
                    state.withRefreshedTrust(trustState).copy(
                        isManualSyncing = false,
                        pullPreview = null,
                        statusMessage = buildSuccessMessage(completed),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isManualSyncing = false,
                        errorMessage = e.message ?: "应用远端快照失败，本地数据已完整回滚",
                    )
                }
            }
        }
    }

    fun cancelPullPreview() {
        cachedPullPreview = null
        _uiState.update { it.copy(pullPreview = null, statusMessage = "已取消拉取合并，本机数据未修改") }
    }

    fun removeTrustedPeer(deviceId: String) {
        viewModelScope.launch {
            try {
                val updated = withContext(dispatchers.io) {
                    trustStore.removeTrustedPeer(deviceId)
                }
                _uiState.update { state ->
                    state.withRefreshedTrust(updated).copy(
                        statusMessage = "已取消对该设备的信任，下次同步需重新输入配对码",
                        errorMessage = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "移除信任设备失败") }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    override fun onCleared() {
        stopSession()
        discoveryCollectorJob?.cancel()
        addressesCollectorJob?.cancel()
        super.onCleared()
    }

    private suspend fun executeSyncTarget(
        host: String,
        port: Int,
        expectedDeviceId: String?,
        pin: String?,
        rememberDevice: Boolean,
        mode: LanSyncMode,
        manual: Boolean,
    ) {
        try {
            val outcome = when (mode) {
                LanSyncMode.TWO_WAY -> client.syncTwoWay(host, port, expectedDeviceId, pin, rememberDevice)
                LanSyncMode.PUSH -> client.pushSnapshot(host, port, expectedDeviceId, pin, rememberDevice)
                LanSyncMode.PULL -> client.pullPreview(host, port, expectedDeviceId, pin, rememberDevice)
            }
            val trustState = withContext(dispatchers.io) {
                trustStore.loadOrCreate(defaultDeviceName)
            }
            when (outcome) {
                is LanSyncOutcome.Completed -> {
                    _uiState.update { state ->
                        state.withRefreshedTrust(trustState).copy(
                            syncingPeerId = if (manual) state.syncingPeerId else null,
                            isManualSyncing = if (manual) false else state.isManualSyncing,
                            statusMessage = buildSuccessMessage(outcome),
                        )
                    }
                }
                is LanSyncOutcome.PullPreviewReady -> {
                    cachedPullPreview = outcome
                    _uiState.update { state ->
                        state.withRefreshedTrust(trustState).copy(
                            syncingPeerId = if (manual) state.syncingPeerId else null,
                            isManualSyncing = if (manual) false else state.isManualSyncing,
                            pullPreview = LanSyncPullPreviewUi(
                                peerDeviceId = outcome.peerDeviceId,
                                peerDeviceName = outcome.peerDeviceName,
                                peerEndpoint = "${outcome.peerHost}:${outcome.peerPort}",
                                peerAppVersion = outcome.peerAppVersion,
                                fetchedAtMs = outcome.fetchedAtMs,
                                summary = outcome.summary,
                                warnings = outcome.warnings,
                            ),
                            statusMessage = "已解密来自 ${outcome.peerDeviceName} 的快照，确认后才会合并到本机",
                        )
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            _uiState.update { state ->
                state.copy(
                    syncingPeerId = if (manual) state.syncingPeerId else null,
                    isManualSyncing = if (manual) false else state.isManualSyncing,
                    errorMessage = e.message ?: "局域网同步失败，请检查配对码与网络连接",
                )
            }
        }
    }

    private fun handleIncomingServerEvent(event: LanSyncIncomingEvent) {
        viewModelScope.launch {
            val trustState = withContext(dispatchers.io) {
                runCatching { trustStore.loadOrCreate(defaultDeviceName) }.getOrNull()
            }
            val message = when (event.mode) {
                LanSyncMode.TWO_WAY -> {
                    val s = event.mergedSummary
                    if (s != null) {
                        "已与 ${event.peerDeviceName} 完成双向合并（${s.favoritesCount} 收藏 · ${s.totalHistoryCount} 历史）"
                    } else {
                        "已与 ${event.peerDeviceName} 完成双向合并"
                    }
                }
                LanSyncMode.PUSH -> "已接收来自 ${event.peerDeviceName} 的推送并合并到本机"
                LanSyncMode.PULL -> "已向 ${event.peerDeviceName} 加密发送本机快照"
            }
            _uiState.update { state ->
                val base = if (trustState != null) state.withRefreshedTrust(trustState) else state
                base.copy(
                    incomingEvents = (listOf(event) + state.incomingEvents).take(5),
                    statusMessage = message,
                )
            }
        }
    }

    private fun LanSyncUiState.withRefreshedTrust(
        trustState: com.raven.app.core.security.LanSyncTrustState,
    ): LanSyncUiState {
        val trustedById = trustState.trustedPeers.associateBy { it.deviceId }
        val updatedDiscovered = discoveredPeers.map { peer ->
            val trusted = trustedById[peer.deviceId]
            peer.copy(
                isTrusted = trusted != null,
                lastSyncedAtMs = trusted?.lastSyncedAtMs ?: 0L,
            )
        }
        return copy(
            trustedPeers = trustState.trustedPeers,
            discoveredPeers = updatedDiscovered,
        )
    }

    private fun buildSuccessMessage(outcome: LanSyncOutcome.Completed): String {
        val summary = outcome.mergedSummary
        val actionLabel = when (outcome.mode) {
            LanSyncMode.TWO_WAY -> "双向合并完成"
            LanSyncMode.PUSH -> "已加密推送到对端"
            LanSyncMode.PULL -> "拉取合并完成"
        }
        val trustNote = if (outcome.pairedNewDevice) " · 已记住设备" else ""
        return "$actionLabel（${outcome.peerDeviceName}$trustNote）：${summary.favoritesCount} 收藏 · ${summary.totalHistoryCount} 历史 · ${summary.shelvesCount} 片单"
    }
}
