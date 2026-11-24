package com.raven.app.core.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Delegates route selection to the OS / PAC [ProxySelector] and returns ONLY the first chosen
 * [Proxy] without appending a silent `DIRECT` fallback.
 *
 * If the configured system proxy is unreachable or throws, the failure remains visible instead of
 * silently leaking direct traffic.
 */
class SystemProxySelector(
    private val systemProvider: () -> ProxySelector? = { ProxySelector.getDefault() },
) : ProxySelector() {

    override fun select(uri: URI): List<Proxy> {
        val routes = try {
            systemProvider()?.select(uri).orEmpty()
        } catch (error: RuntimeException) {
            throw IOException("无法读取系统代理设置", error)
        }
        return listOf(routes.firstOrNull() ?: Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
        runCatching { systemProvider()?.connectFailed(uri, address, error) }
    }

    fun describe(uri: URI): String {
        val proxy = select(uri).single()
        if (proxy.type() == Proxy.Type.DIRECT) {
            return "未使用系统代理 · 系统 DNS 直连"
        }
        val address = proxy.address() as? InetSocketAddress
        val host = address?.hostString.orEmpty()
        val port = address?.port?.takeIf { it > 0 }?.toString().orEmpty()
        val endpoint = if (host.isNotEmpty() && port.isNotEmpty()) "$host:$port" else host
        return "系统代理 · ${proxy.type()} · $endpoint"
    }

    fun describeOrError(uri: URI): String =
        runCatching { describe(uri) }.getOrDefault("系统代理设置不可用")
}
