package com.raven.app.feature.settings

import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.security.BackupValidator
import com.raven.app.core.security.InMemoryVaultStorage
import com.raven.app.core.security.LanSyncCrypto
import com.raven.app.core.security.LanSyncTrustStore
import com.raven.app.core.security.SoftwareAesGcmVaultCipher
import com.raven.app.feature.settings.lansync.DiscoveredLanPeer
import com.raven.app.feature.settings.lansync.LanDiscoverySource
import com.raven.app.feature.settings.lansync.LanSyncClient
import com.raven.app.feature.settings.lansync.LanSyncDiscoveryBridge
import com.raven.app.feature.settings.lansync.LanSyncMode
import com.raven.app.feature.settings.lansync.LanSyncServer
import com.raven.app.feature.settings.lansync.LanSyncViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeLanSyncDiscoveryBridge : LanSyncDiscoveryBridge {
    private val _peers = MutableStateFlow<List<DiscoveredLanPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredLanPeer>> = _peers.asStateFlow()

    private val _localAddresses = MutableStateFlow(listOf("192.168.1.50"))
    override val localAddresses: StateFlow<List<String>> = _localAddresses.asStateFlow()

    var startedCount: Int = 0
    var stoppedCount: Int = 0
    var refreshCount: Int = 0

    override fun start(
        scope: CoroutineScope,
        localDeviceId: String,
        localDeviceName: String,
        appVersion: String,
        tcpPort: Int,
    ) {
        startedCount += 1
    }

    override fun refreshNow() {
        refreshCount += 1
    }

    override fun stop() {
        stoppedCount += 1
    }

    fun emitPeers(items: List<DiscoveredLanPeer>) {
        _peers.value = items
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LanSyncViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `viewmodel manages discovery lifecycle pairs via pin pulls preview and removes trust`() = runTest {
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)

        // Remote peer running a real loopback LAN sync server
        val remoteDb = FakeDatabaseBundle()
        remoteDb.favoriteDao.upsert(
            FavoriteEntity(
                siteId = SiteId.R34V.value,
                kind = FavoriteKind.VIDEO,
                targetId = "remote-99",
                title = "Remote Favorite",
                url = "https://rule34video.com/video/99/remote/",
                coverUrl = "",
                addedAt = 500L,
                extra = emptyMap(),
            ),
        )
        val remoteTrust = LanSyncTrustStore(SoftwareAesGcmVaultCipher(), InMemoryVaultStorage())
        val remoteIdentity = remoteTrust.loadOrCreate("客厅平板")
        val ioDispatchers = CustomAppDispatchers(
            main = mainDispatcherRule.appDispatchers.main,
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )
        val remoteServer = LanSyncServer(
            backupDataRepository = remoteDb.backupDataRepository,
            backupValidator = validator,
            trustStore = remoteTrust,
            appVersion = "0.9.5",
            dispatchers = ioDispatchers,
            defaultDeviceName = "客厅平板",
            pbkdf2Iterations = 500,
            allowLoopbackForTest = true,
        )
        val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Local device under test
        val localDb = FakeDatabaseBundle()
        val localTrust = LanSyncTrustStore(SoftwareAesGcmVaultCipher(), InMemoryVaultStorage())
        val localServer = LanSyncServer(
            backupDataRepository = localDb.backupDataRepository,
            backupValidator = validator,
            trustStore = localTrust,
            appVersion = "0.9.5",
            dispatchers = ioDispatchers,
            defaultDeviceName = "主力手机",
            pbkdf2Iterations = 500,
            allowLoopbackForTest = true,
        )
        val localClient = LanSyncClient(
            backupDataRepository = localDb.backupDataRepository,
            backupValidator = validator,
            trustStore = localTrust,
            appVersion = "0.9.5",
            dispatchers = ioDispatchers,
            defaultDeviceName = "主力手机",
            pbkdf2Iterations = 500,
            allowLoopbackForTest = true,
        )
        val bridge = FakeLanSyncDiscoveryBridge()
        val vm = LanSyncViewModel(
            server = localServer,
            client = localClient,
            discoveryBridge = bridge,
            trustStore = localTrust,
            appInfo = RavenAppInfo("0.9.5", 18L, false, "com.raven.app"),
            dispatchers = mainDispatcherRule.appDispatchers,
            defaultDeviceName = "主力手机",
            allowLoopbackForTest = true,
        )

        try {
            val remotePort = remoteServer.start(remoteScope)
            val remotePin = remoteServer.currentPin()

            advanceUntilIdle()
            vm.startSession()
            waitUntil(vm) { bridge.startedCount == 1 && vm.uiState.value.listeningPort > 0 }

            assertEquals(1, bridge.startedCount)
            assertTrue(vm.uiState.value.listeningPort > 0)
            assertEquals(listOf("192.168.1.50"), vm.uiState.value.localAddresses)

            val discovered = DiscoveredLanPeer(
                deviceId = remoteIdentity.localDeviceId,
                deviceName = "客厅平板",
                host = "127.0.0.1",
                port = remotePort,
                appVersion = "0.9.5",
                fingerprint = LanSyncCrypto.deviceFingerprint(remoteIdentity.localDeviceId),
                source = LanDiscoverySource.UDP_BROADCAST,
                lastSeenAtMs = 1_000L,
            )
            bridge.emitPeers(listOf(discovered))
            advanceUntilIdle()

            assertEquals(1, vm.uiState.value.discoveredPeers.size)
            assertFalse(vm.uiState.value.discoveredPeers.first().isTrusted)

            // Reject invalid manual IP cleanly
            vm.syncWithManualEndpoint("8.8.8.8:45874", pin = remotePin)
            assertNotNull(vm.uiState.value.errorMessage)
            vm.clearFeedback()

            // Pull preview first: remembers trusted device without writing Room yet
            vm.selectSyncMode(LanSyncMode.PULL)
            vm.syncWithPeer(discovered, pin = remotePin, rememberDevice = true)
            // Poll briefly until Dispatchers.IO TCP handshake finishes
            waitUntil(vm) { !vm.uiState.value.isBusy && vm.uiState.value.pullPreview != null }

            assertNotNull(vm.uiState.value.pullPreview)
            assertTrue(vm.uiState.value.discoveredPeers.first().isTrusted)
            assertEquals(0, localDb.favoriteDao.getAll().size)

            // Confirm pull applies to local database
            vm.confirmPullPreview()
            waitUntil(vm) { !vm.uiState.value.isBusy && vm.uiState.value.pullPreview == null }
            assertEquals(1, localDb.favoriteDao.getAll().size)

            // Rename local device and remove trust
            vm.updateLocalDeviceName("书房手机")
            advanceUntilIdle()
            assertEquals("书房手机", vm.uiState.value.localDeviceName)

            vm.removeTrustedPeer(remoteIdentity.localDeviceId)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.discoveredPeers.first().isTrusted)

            vm.stopSession()
            assertEquals(0, vm.uiState.value.listeningPort)
            assertTrue(bridge.stoppedCount >= 1)
        } finally {
            vm.stopSession()
            remoteServer.stop()
            remoteScope.cancel()
        }
    }

    private fun TestScope.waitUntil(
        vm: LanSyncViewModel,
        timeoutMs: Long = 5_000L,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (predicate()) return
            Thread.yield()
        }
        advanceUntilIdle()
        assertTrue(
            "Timed out waiting for async LAN sync ViewModel state (error=${vm.uiState.value.errorMessage}, status=${vm.uiState.value.statusMessage})",
            predicate(),
        )
    }
}
