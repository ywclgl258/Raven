package com.raven.app.core.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Proxy as AndroidProxy
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observes OS default network and system proxy changes via [ConnectivityManager.NetworkCallback]
 * and `PROXY_CHANGE_ACTION`.
 *
 * On any route/network/proxy transition:
 * 1. Evicts idle connections from the shared [ConnectionPool] so new requests honor the new route.
 * 2. Increments [routeEpoch] monotonically so route-keyed caches (e.g. `MirrorLeaseRouter`) invalidate.
 * 3. Updates [isMetered] and [isConnected] reactively for unmetered-only downloads and UI banners.
 */
class NetworkMonitor(
    private val context: Context?,
    private val proxySelector: SystemProxySelector,
    private val connectionPool: ConnectionPool,
) {
    private val stateLock = Any()
    private val started = AtomicBoolean(false)
    private val invalidationListeners = CopyOnWriteArrayList<() -> Unit>()

    private val _routeEpoch = MutableStateFlow(0L)
    val routeEpoch: StateFlow<Long> = _routeEpoch.asStateFlow()

    private val _isMetered = MutableStateFlow(false)
    val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    private val _isConnected = MutableStateFlow(true)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile
    private var activeNetworkHandle: Long? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var proxyReceiver: BroadcastReceiver? = null

    init {
        refreshInitialState()
    }

    /**
     * Registers Android network & proxy callbacks idempotently. Safe to call from `Application.onCreate()`.
     */
    fun start() {
        val appContext = context?.applicationContext ?: return
        if (!started.compareAndSet(false, true)) return

        val cm = runCatching { appContext.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        if (cm != null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                    val metered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
                        ?: runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
                    onDefaultNetworkAvailable(network.networkHandle, metered)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    onCapabilitiesChanged(network.networkHandle, metered)
                }

                override fun onLost(network: Network) {
                    onDefaultNetworkLost(network.networkHandle)
                }
            }
            val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
            if (registered) {
                networkCallback = callback
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                onProxyChanged()
            }
        }
        val filter = IntentFilter(AndroidProxy.PROXY_CHANGE_ACTION)
        val registeredReceiver = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (registeredReceiver) {
            proxyReceiver = receiver
        }
    }

    fun stop() {
        val appContext = context?.applicationContext ?: return
        if (!started.compareAndSet(true, false)) return
        val cm = runCatching { appContext.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        networkCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
            networkCallback = null
        }
        proxyReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
            proxyReceiver = null
        }
    }

    fun addRouteInvalidationListener(listener: () -> Unit) {
        invalidationListeners.add(listener)
    }

    fun removeRouteInvalidationListener(listener: () -> Unit) {
        invalidationListeners.remove(listener)
    }

    /**
     * Route identity combining active network handle, monotonic [routeEpoch], and per-URL system
     * proxy decisions so [com.raven.app.core.sitespi.MirrorLeaseRouter] re-probes on any change.
     */
    fun currentRouteKey(urls: List<String> = emptyList()): String {
        val handle = queryCurrentNetworkHandle() ?: activeNetworkHandle
        val proxyDescriptions = urls.map { raw ->
            val uri = raw.toHttpUrlOrNull()?.toUri() ?: runCatching { URI(raw) }.getOrNull()
            if (uri != null) proxySelector.describeOrError(uri) else "invalid-uri"
        }
        return listOf(handle, _routeEpoch.value, proxyDescriptions).toString()
    }

    fun invalidateRoute() {
        synchronized(stateLock) {
            _routeEpoch.value += 1L
        }
        connectionPool.evictAll()
        invalidationListeners.forEach { listener -> runCatching { listener() } }
    }

    internal fun onDefaultNetworkAvailable(networkHandle: Long?, metered: Boolean) {
        val changed = synchronized(stateLock) {
            _isConnected.value = true
            _isMetered.value = metered
            if (networkHandle != null && networkHandle == activeNetworkHandle) {
                false
            } else {
                activeNetworkHandle = networkHandle
                true
            }
        }
        if (changed) {
            invalidateRoute()
        }
    }

    internal fun onCapabilitiesChanged(networkHandle: Long?, metered: Boolean) {
        synchronized(stateLock) {
            if (activeNetworkHandle == null || networkHandle == activeNetworkHandle) {
                _isMetered.value = metered
                _isConnected.value = true
            }
        }
    }

    internal fun onDefaultNetworkLost(networkHandle: Long?) {
        val changed = synchronized(stateLock) {
            if (networkHandle != null && activeNetworkHandle != null && networkHandle != activeNetworkHandle) {
                false
            } else {
                activeNetworkHandle = null
                _isConnected.value = false
                true
            }
        }
        if (changed) {
            invalidateRoute()
        }
    }

    internal fun onProxyChanged() {
        invalidateRoute()
    }

    private fun refreshInitialState() {
        val cm = runCatching { context?.applicationContext?.getSystemService(ConnectivityManager::class.java) }.getOrNull()
            ?: return
        runCatching {
            val active = cm.activeNetwork
            activeNetworkHandle = active?.networkHandle
            _isConnected.value = active != null
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            _isMetered.value = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
                ?: cm.isActiveNetworkMetered
        }
    }

    private fun queryCurrentNetworkHandle(): Long? {
        val cm = runCatching { context?.applicationContext?.getSystemService(ConnectivityManager::class.java) }.getOrNull()
            ?: return null
        return runCatching { cm.activeNetwork?.networkHandle }.getOrNull()
    }
}
