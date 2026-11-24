package com.raven.app.core.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe ring buffer of recent rate-limit (HTTP 429) and CDN redirect invalidation events
 * surfaced by [NetworkDiagnostics]. Normal [HostPacer] pacing delays are intentionally excluded.
 */
class RateLimitLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val events = ArrayDeque<String>(capacity)

    fun record(host: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(clockMs()))
        val formatted = "$timestamp · $host · $message"
        synchronized(lock) {
            if (events.size >= capacity) {
                events.removeFirst()
            }
            events.addLast(formatted)
        }
    }

    fun entries(): List<String> = synchronized(lock) { events.toList() }

    fun report(): String = synchronized(lock) {
        if (events.isEmpty()) "" else "近期限流事件（最新在下）：\n" + events.joinToString("\n")
    }

    fun clear() = synchronized(lock) { events.clear() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 12
    }
}

/**
 * Tracks the most recently acquired OkHttp connection peer & proxy mode for live diagnostics.
 */
class PeerTracker {
    @Volatile
    var lastPeer: String = DEFAULT_STATUS
        private set

    fun eventListenerFactory(): EventListener.Factory = EventListener.Factory {
        object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
                val route = connection.route()
                val peer = route.socketAddress.address?.hostAddress
                    ?: route.socketAddress.hostString.orEmpty()
                val mode = if (route.proxy.type() == Proxy.Type.DIRECT) "系统 DNS 直连" else "系统代理"
                lastPeer = if (peer.isNotBlank()) "$peer · $mode" else mode
            }
        }
    }

    internal fun recordPeer(peerDescription: String) {
        lastPeer = peerDescription
    }

    companion object {
        const val DEFAULT_STATUS: String = "尚未连接"
    }
}
