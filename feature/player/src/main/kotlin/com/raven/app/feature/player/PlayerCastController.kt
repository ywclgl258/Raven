package com.raven.app.feature.player

import com.raven.app.core.media.cast.LanCastDevice
import com.raven.app.core.media.cast.LanCastManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * LAN DLNA casting: device discovery, casting the current stream (or the target URL fallback),
 * stopping a cast session and handing playback to the system external player.
 *
 * Extracted from [PlayerViewModel]; the ViewModel keeps thin delegates.
 */
internal class PlayerCastController(
    private val castManager: LanCastManager?,
    private val scope: CoroutineScope,
    private val localState: MutableStateFlow<PlayerLocalState>,
    private val state: () -> PlayerUiState,
    private val currentStreamUrl: () -> String?,
    private val fallbackUrl: () -> String?,
    private val pausePlayback: () -> Unit,
    private val message: (String) -> Unit,
) {
    private val manager: LanCastManager? get() = castManager

    fun discover() {
        val manager = manager ?: return
        if (localState.value.isScanningCast) return
        scope.launch {
            localState.update { it.copy(isScanningCast = true) }
            try {
                val found = manager.discoverDevices()
                localState.update { it.copy(castDevices = found, isScanningCast = false) }
                if (found.isEmpty()) {
                    message("局域网内未发现 DLNA 投屏设备")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                localState.update { it.copy(isScanningCast = false) }
                message("扫描投屏设备失败")
            }
        }
    }

    fun cast(device: LanCastDevice) {
        val manager = manager ?: return
        val streamUrl = currentStreamUrl()?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: fallbackUrl()?.takeIf { it.startsWith("http", ignoreCase = true) }
        if (streamUrl == null) {
            message("当前仅支持对在线直链或局域网视频发起 DLNA 投屏")
            return
        }
        val title = state().title.ifBlank { "Raven Video" }
        scope.launch {
            val result = runCatching {
                manager.castVideo(device = device, streamUrl = streamUrl, title = title)
            }
            if (result.isSuccess) {
                pausePlayback()
                localState.update { it.copy(activeCastDevice = device) }
                message("已投屏至 ${device.friendlyName}")
            } else {
                message("投屏至 ${device.friendlyName} 失败")
            }
        }
    }

    fun stop() {
        val manager = manager ?: return
        val device = localState.value.activeCastDevice ?: return
        scope.launch {
            manager.stopCast(device)
            localState.update { it.copy(activeCastDevice = null) }
            message("已断开投屏")
        }
    }

    fun launchExternalPlayer(): Boolean {
        val manager = manager ?: return false
        val streamUrl = currentStreamUrl()?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: fallbackUrl()?.takeIf { it.isNotBlank() }
            ?: return false
        return manager.launchExternalPlayer(streamUrl)
    }
}
