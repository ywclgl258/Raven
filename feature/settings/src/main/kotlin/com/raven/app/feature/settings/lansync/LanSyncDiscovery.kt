package com.raven.app.feature.settings.lansync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.security.LanSyncCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Abstraction for LAN peer auto-discovery (mDNS / NSD + UDP broadcast beacons) so [LanSyncViewModel]
 * can be driven by Android system services in production and deterministic fakes in JVM unit tests.
 */
interface LanSyncDiscoveryBridge {
    val peers: StateFlow<List<DiscoveredLanPeer>>
    val localAddresses: StateFlow<List<String>>

    fun start(
        scope: CoroutineScope,
        localDeviceId: String,
        localDeviceName: String,
        appVersion: String,
        tcpPort: Int,
    )

    fun refreshNow()

    fun stop()
}

/**
 * Dual-channel LAN auto-discovery bridge:
 * 1. Android [NsdManager] mDNS registration & discovery (`_raven-sync._tcp.`) with API 34+
 *    `ServiceInfoCallback` support and serialized legacy `resolveService` fallback for API 24-33.
 * 2. UDP LAN broadcast announce/probe beacons on port `45873`, backed by [WifiManager.MulticastLock],
 *    discovering peers cleanly even on Wi-Fi routers that isolate mDNS multicast.
 */
class AndroidLanSyncDiscoveryBridge(
    context: Context,
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) : LanSyncDiscoveryBridge {

    private val appContext = context.applicationContext
    private val _peers = MutableStateFlow<List<DiscoveredLanPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredLanPeer>> = _peers.asStateFlow()

    private val _localAddresses = MutableStateFlow<List<String>>(emptyList())
    override val localAddresses: StateFlow<List<String>> = _localAddresses.asStateFlow()

    private val lock = Any()
    private val discoveredMap = ConcurrentHashMap<String, DiscoveredLanPeer>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolvingLegacy = false

    private var activeScope: CoroutineScope? = null
    private var localDeviceId: String = ""
    private var localDeviceName: String = ""
    private var localAppVersion: String = ""
    private var localTcpPort: Int = 0
    private var registeredServiceName: String = ""

    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpSocket: DatagramSocket? = null
    private var udpReceiveJob: Job? = null
    private var announceJob: Job? = null
    private var pruneJob: Job? = null

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var callbackExecutor: ExecutorService? = null
    private val activeInfoCallbacks = ArrayList<Pair<NsdManager.ServiceInfoCallback, String>>()

    override fun start(
        scope: CoroutineScope,
        localDeviceId: String,
        localDeviceName: String,
        appVersion: String,
        tcpPort: Int,
    ) {
        stop()
        synchronized(lock) {
            this.activeScope = scope
            this.localDeviceId = localDeviceId
            this.localDeviceName = localDeviceName
            this.localAppVersion = appVersion
            this.localTcpPort = tcpPort
            discoveredMap.clear()
            resolveQueue.clear()
            resolvingLegacy = false
        }
        _peers.value = emptyList()
        _localAddresses.value = LanSyncAddressValidator.enumerateLocalLanIpv4Addresses()

        acquireMulticastLock()
        startUdpDiscovery(scope)
        startNsdRegistrationAndDiscovery()
        startStalePeerPruner(scope)
    }

    override fun refreshNow() {
        _localAddresses.value = LanSyncAddressValidator.enumerateLocalLanIpv4Addresses()
        val scope = synchronized(lock) { activeScope } ?: return
        scope.launch(dispatchers.io) {
            sendUdpBeacon(kind = LanSyncBeacon.KIND_PROBE)
            sendUdpBeacon(kind = LanSyncBeacon.KIND_ANNOUNCE)
        }
    }

    override fun stop() {
        val jobs: List<Job?>
        val socketToClose: DatagramSocket?
        val mLock: WifiManager.MulticastLock?
        val mgr: NsdManager?
        val regListener: NsdManager.RegistrationListener?
        val discListener: NsdManager.DiscoveryListener?
        val infoCallbacks: List<NsdManager.ServiceInfoCallback>
        val executor: ExecutorService?

        synchronized(lock) {
            activeScope = null
            jobs = listOf(udpReceiveJob, announceJob, pruneJob)
            udpReceiveJob = null
            announceJob = null
            pruneJob = null
            socketToClose = udpSocket
            udpSocket = null
            mLock = multicastLock
            multicastLock = null
            mgr = nsdManager
            nsdManager = null
            regListener = registrationListener
            registrationListener = null
            discListener = discoveryListener
            discoveryListener = null
            infoCallbacks = activeInfoCallbacks.map { it.first }
            activeInfoCallbacks.clear()
            executor = callbackExecutor
            callbackExecutor = null
            resolveQueue.clear()
            resolvingLegacy = false
        }

        jobs.forEach { it?.cancel() }
        runCatching { socketToClose?.close() }
        if (mgr != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                infoCallbacks.forEach { callback ->
                    runCatching { mgr.unregisterServiceInfoCallback(callback) }
                }
            }
            discListener?.let { listener -> runCatching { mgr.stopServiceDiscovery(listener) } }
            regListener?.let { listener -> runCatching { mgr.unregisterService(listener) } }
        }
        runCatching { executor?.shutdownNow() }
        runCatching {
            if (mLock?.isHeld == true) {
                mLock.release()
            }
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val created = wifiManager.createMulticastLock("raven-lan-sync")
            created.setReferenceCounted(false)
            created.acquire()
            synchronized(lock) {
                multicastLock = created
            }
        }
    }

    private fun startUdpDiscovery(scope: CoroutineScope) {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = UDP_SOCKET_TIMEOUT_MS
                bind(java.net.InetSocketAddress(LanSyncConstants.UDP_DISCOVERY_PORT))
            }
        }.getOrElse {
            // Fall back to ephemeral UDP port for probe/reply if 45873 is already held on the device.
            runCatching {
                DatagramSocket().apply {
                    broadcast = true
                    soTimeout = UDP_SOCKET_TIMEOUT_MS
                }
            }.getOrNull()
        } ?: return

        synchronized(lock) {
            udpSocket = socket
        }

        udpReceiveJob = scope.launch(dispatchers.io) {
            val buffer = ByteArray(LanSyncBeacon.MAX_BEACON_BYTES)
            while (isActive && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val senderIp = packet.address?.hostAddress.orEmpty()
                    if (!LanSyncAddressValidator.isAllowedLanIpv4(senderIp)) continue
                    val beacon = LanSyncBeacon.decode(packet.data, packet.length) ?: continue
                    val selfId = synchronized(lock) { localDeviceId }
                    if (beacon.deviceId == selfId) continue

                    if (beacon.kind == LanSyncBeacon.KIND_PROBE) {
                        sendUdpBeaconUnicast(targetAddress = packet.address)
                        continue
                    }

                    upsertDiscoveredPeer(
                        deviceId = beacon.deviceId,
                        deviceName = beacon.deviceName,
                        host = senderIp,
                        port = beacon.tcpPort,
                        appVersion = beacon.appVersion,
                        source = LanDiscoverySource.UDP_BROADCAST,
                    )
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: IOException) {
                    break
                }
            }
        }

        announceJob = scope.launch(dispatchers.io) {
            sendUdpBeacon(kind = LanSyncBeacon.KIND_PROBE)
            sendUdpBeacon(kind = LanSyncBeacon.KIND_ANNOUNCE)
            while (isActive && !socket.isClosed) {
                delay(ANNOUNCE_INTERVAL_MS)
                sendUdpBeacon(kind = LanSyncBeacon.KIND_ANNOUNCE)
            }
        }
    }

    private fun sendUdpBeacon(kind: String) {
        val socket = synchronized(lock) { udpSocket } ?: return
        if (socket.isClosed) return
        val payload = buildLocalBeacon(kind).encode()
        val targets = LanSyncAddressValidator.enumerateBroadcastAddresses()
        for (target in targets) {
            runCatching {
                val packet = DatagramPacket(payload, payload.size, target, LanSyncConstants.UDP_DISCOVERY_PORT)
                socket.send(packet)
            }
        }
    }

    private fun sendUdpBeaconUnicast(targetAddress: InetAddress) {
        val socket = synchronized(lock) { udpSocket } ?: return
        if (socket.isClosed) return
        val payload = buildLocalBeacon(LanSyncBeacon.KIND_ANNOUNCE).encode()
        runCatching {
            val packet = DatagramPacket(
                payload,
                payload.size,
                targetAddress,
                LanSyncConstants.UDP_DISCOVERY_PORT,
            )
            socket.send(packet)
        }
    }

    private fun buildLocalBeacon(kind: String): LanSyncBeacon = synchronized(lock) {
        LanSyncBeacon(
            kind = kind,
            deviceId = localDeviceId,
            deviceName = localDeviceName,
            appVersion = localAppVersion,
            tcpPort = localTcpPort,
        )
    }

    private fun startNsdRegistrationAndDiscovery() {
        val manager = runCatching {
            appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull() ?: return

        val (selfId, selfName, selfApp, port) = synchronized(lock) {
            nsdManager = manager
            Quad(localDeviceId, localDeviceName, localAppVersion, localTcpPort)
        }
        val preferredServiceName = "Raven-${selfId.take(6)}"

        val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    registeredServiceName = serviceInfo.serviceName
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = preferredServiceName
            serviceType = LanSyncConstants.NSD_SERVICE_TYPE
            setPort(port)
            setAttribute("v", PROTOCOL_VERSION.toString())
            setAttribute("id", selfId)
            setAttribute("name", selfName)
            setAttribute("app", selfApp)
        }

        val executor = Executors.newSingleThreadExecutor()
        synchronized(lock) {
            registrationListener = regListener
            callbackExecutor = executor
        }

        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
        }

        val discListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit

            override fun onServiceFound(found: NsdServiceInfo) {
                if (!found.serviceType.contains("raven-sync", ignoreCase = true)) return
                val currentRegName = synchronized(lock) { registeredServiceName }
                if (found.serviceName == preferredServiceName || found.serviceName == currentRegName) {
                    return
                }
                resolveDiscoveredService(found)
            }

            override fun onServiceLost(lost: NsdServiceInfo) {
                val targetName = lost.serviceName
                val removed = discoveredMap.entries.removeAll { (_, peer) ->
                    peer.source == LanDiscoverySource.NSD &&
                        ("Raven-${peer.deviceId.take(6)}" == targetName || peer.deviceName == targetName)
                }
                if (removed) {
                    publishPeers()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        synchronized(lock) {
            discoveryListener = discListener
        }
        runCatching {
            manager.discoverServices(
                LanSyncConstants.NSD_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discListener,
            )
        }
    }

    private fun resolveDiscoveredService(serviceInfo: NsdServiceInfo) {
        val manager = synchronized(lock) { nsdManager } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val exec = synchronized(lock) { callbackExecutor } ?: return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

                override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                    val host = updatedInfo.hostAddresses
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                        .firstOrNull { LanSyncAddressValidator.isAllowedLanIpv4(it) }
                        ?: return
                    handleResolvedNsdInfo(updatedInfo, host)
                }

                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            val registered = runCatching {
                manager.registerServiceInfoCallback(serviceInfo, exec, callback)
                true
            }.getOrDefault(false)
            if (registered) {
                synchronized(lock) {
                    activeInfoCallbacks.add(callback to serviceInfo.serviceName)
                }
            }
        } else {
            val startImmediately = synchronized(lock) {
                if (resolveQueue.size < 32) {
                    resolveQueue.addLast(serviceInfo)
                }
                if (!resolvingLegacy) {
                    resolvingLegacy = true
                    true
                } else {
                    false
                }
            }
            if (startImmediately) {
                drainNextLegacyResolve()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun drainNextLegacyResolve() {
        val (manager, nextInfo) = synchronized(lock) {
            val mgr = nsdManager
            val next = resolveQueue.removeFirstOrNull()
            if (mgr == null || next == null) {
                resolvingLegacy = false
                return
            }
            mgr to next
        }

        runCatching {
            manager.resolveService(
                nextInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        drainNextLegacyResolve()
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress.orEmpty()
                        if (LanSyncAddressValidator.isAllowedLanIpv4(host)) {
                            handleResolvedNsdInfo(resolved, host)
                        }
                        drainNextLegacyResolve()
                    }
                },
            )
        }.onFailure {
            drainNextLegacyResolve()
        }
    }

    private fun handleResolvedNsdInfo(info: NsdServiceInfo, host: String) {
        val attrs = info.attributes
        val deviceId = attrs["id"]?.toString(Charsets.UTF_8)?.trim().orEmpty()
        val selfId = synchronized(lock) { localDeviceId }
        if (deviceId.isBlank() || deviceId == selfId) return
        val port = info.port
        if (port !in 1024..65535) return
        val name = attrs["name"]?.toString(Charsets.UTF_8)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: info.serviceName
        val app = attrs["app"]?.toString(Charsets.UTF_8)?.trim().orEmpty()

        upsertDiscoveredPeer(
            deviceId = deviceId,
            deviceName = name,
            host = host,
            port = port,
            appVersion = app,
            source = LanDiscoverySource.NSD,
        )
    }

    private fun startStalePeerPruner(scope: CoroutineScope) {
        pruneJob = scope.launch(dispatchers.io) {
            while (isActive) {
                delay(PRUNE_INTERVAL_MS)
                val cutoff = clock() - PEER_TTL_MS
                val removed = discoveredMap.entries.removeAll { (_, peer) ->
                    peer.lastSeenAtMs < cutoff
                }
                if (removed) {
                    publishPeers()
                }
                _localAddresses.value = LanSyncAddressValidator.enumerateLocalLanIpv4Addresses()
            }
        }
    }

    private fun upsertDiscoveredPeer(
        deviceId: String,
        deviceName: String,
        host: String,
        port: Int,
        appVersion: String,
        source: LanDiscoverySource,
    ) {
        if (!LanSyncAddressValidator.isAllowedLanIpv4(host) || port !in 1024..65535) return
        val now = clock()
        val existing = discoveredMap[deviceId]
        val resolvedSource = if (existing?.source == LanDiscoverySource.NSD && source == LanDiscoverySource.UDP_BROADCAST) {
            LanDiscoverySource.NSD
        } else {
            source
        }
        discoveredMap[deviceId] = DiscoveredLanPeer(
            deviceId = deviceId,
            deviceName = deviceName.trim().take(32).ifBlank { "Raven 设备" },
            host = host,
            port = port,
            appVersion = appVersion.trim().take(32),
            fingerprint = LanSyncCrypto.deviceFingerprint(deviceId),
            source = resolvedSource,
            lastSeenAtMs = now,
        )
        publishPeers()
    }

    private fun publishPeers() {
        _peers.value = discoveredMap.values
            .sortedWith(compareBy<DiscoveredLanPeer> { it.deviceName }.thenBy { it.host })
    }

    private data class Quad(
        val first: String,
        val second: String,
        val third: String,
        val fourth: Int,
    )

    companion object {
        private const val UDP_SOCKET_TIMEOUT_MS: Int = 1_500
        private const val ANNOUNCE_INTERVAL_MS: Long = 2_500L
        private const val PRUNE_INTERVAL_MS: Long = 3_000L
        private const val PEER_TTL_MS: Long = 12_000L
    }
}
