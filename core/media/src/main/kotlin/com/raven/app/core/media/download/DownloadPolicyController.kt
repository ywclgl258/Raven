@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.raven.app.core.media.download

import androidx.media3.exoplayer.scheduler.Requirements
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.network.NetworkMonitor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Snapshot of unified download network policy across both Video (Media3) and Gallery engines.
 */
data class DownloadNetworkPolicy(
    val isAllowed: Boolean = true,
    val unmeteredOnly: Boolean = false,
    val isMetered: Boolean = false,
    val isConnected: Boolean = true,
) {
    fun toMedia3Requirements(): Requirements =
        Requirements(if (unmeteredOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
}

/**
 * Synchronizes [NetworkMonitor.isMetered]/[NetworkMonitor.isConnected] and
 * [SettingsDataStore.downloadUnmeteredOnly] across both video and gallery download engines
 * without ever overriding manual pause state.
 */
class DownloadPolicyController(
    private val networkMonitor: NetworkMonitor,
    private val settingsDataStore: SettingsDataStore,
    private val applicationScope: CoroutineScope,
    private val applyVideoRequirements: (Requirements) -> Unit,
    private val onGalleryPolicyChanged: suspend (allowed: Boolean) -> Unit,
) {
    private val started = AtomicBoolean(false)
    private var collectorJob: Job? = null
    private val initialPolicy = CompletableDeferred<DownloadNetworkPolicy>()

    private val _policy = MutableStateFlow(
        evaluate(
            isConnected = networkMonitor.isConnected.value,
            isMetered = networkMonitor.isMetered.value,
            unmeteredOnly = false,
        ),
    )
    val policy: StateFlow<DownloadNetworkPolicy> = _policy.asStateFlow()

    val isNetworkAllowed: Boolean get() = _policy.value.isAllowed

    fun start() {
        if (!started.compareAndSet(false, true)) return
        collectorJob = applicationScope.launch {
            combine(
                networkMonitor.isConnected,
                networkMonitor.isMetered,
                settingsDataStore.settings.map { it.downloadUnmeteredOnly }.distinctUntilChanged(),
            ) { connected, metered, unmeteredOnly ->
                evaluate(
                    isConnected = connected,
                    isMetered = metered,
                    unmeteredOnly = unmeteredOnly,
                )
            }
                .distinctUntilChanged()
                .collect { nextPolicy ->
                    initialPolicy.complete(nextPolicy)
                    _policy.value = nextPolicy
                    runCatching { applyVideoRequirements(nextPolicy.toMedia3Requirements()) }
                    runCatching { onGalleryPolicyChanged(nextPolicy.isAllowed) }
                }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        collectorJob?.cancel()
        collectorJob = null
    }

    /** Suspends until the first real settings/network emission, never the conservative default. */
    suspend fun awaitInitialPolicy(): DownloadNetworkPolicy = initialPolicy.await()

    suspend fun setUnmeteredOnly(enabled: Boolean) {
        settingsDataStore.setDownloadUnmeteredOnly(enabled)
    }

    companion object {
        fun evaluate(
            isConnected: Boolean,
            isMetered: Boolean,
            unmeteredOnly: Boolean,
        ): DownloadNetworkPolicy = DownloadNetworkPolicy(
            isAllowed = isConnected && (!unmeteredOnly || !isMetered),
            unmeteredOnly = unmeteredOnly,
            isMetered = isMetered,
            isConnected = isConnected,
        )
    }
}
