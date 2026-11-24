package com.raven.app.core.network

import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteHttpMethod
import com.raven.app.core.sitespi.SiteHttpRequest
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteRequestKind
import kotlinx.coroutines.CancellationException
import java.net.URI

/**
 * Structured live network & proxy diagnostic snapshot surfaced in Settings.
 */
data class NetworkDiagnosticReport(
    val siteId: SiteId?,
    val siteName: String,
    val targetUrl: String,
    val reachable: Boolean,
    val httpStatus: Int?,
    val latencyMs: Long,
    val proxyDescription: String,
    val lastPeer: String,
    val routeEpoch: Long,
    val isMetered: Boolean,
    val isConnected: Boolean,
    val rateLimitEvents: List<String>,
    val errorMessage: String? = null,
) {
    fun toDisplayText(): String = buildString {
        if (reachable) {
            append("实时连接正常 · HTTP ${httpStatus ?: 200} · ${latencyMs} ms")
        } else {
            append("实时连接失败")
            if (httpStatus != null) append(" · HTTP $httpStatus")
            if (!errorMessage.isNullOrBlank()) append("：$errorMessage")
        }
        append("\n目标站点：$siteName ($targetUrl)")
        append("\n代理路由：$proxyDescription")
        append("\n最近节点：$lastPeer")
        append("\n网络状态：${if (isConnected) "已连接" else "未连接"} · ${if (isMetered) "计费网络" else "非计费网络"} · 路由纪元 #$routeEpoch")
        if (rateLimitEvents.isNotEmpty()) {
            append("\n近期限流事件（最新在下）：")
            for (event in rateLimitEvents) {
                append("\n").append(event)
            }
        }
    }
}

/**
 * Runs live connectivity, proxy, peer, and rate-limit diagnostics for Settings.
 */
class NetworkDiagnostics(
    private val httpClient: SiteHttpClient,
    private val networkMonitor: NetworkMonitor,
    private val proxySelector: SystemProxySelector,
    private val peerTracker: PeerTracker,
    private val rateLimitLog: RateLimitLog,
    private val siteRegistryProvider: () -> SiteRegistry,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        httpClient: SiteHttpClient,
        networkMonitor: NetworkMonitor,
        proxySelector: SystemProxySelector,
        peerTracker: PeerTracker,
        rateLimitLog: RateLimitLog,
        siteRegistry: SiteRegistry,
        clockMs: () -> Long = System::currentTimeMillis,
    ) : this(
        httpClient = httpClient,
        networkMonitor = networkMonitor,
        proxySelector = proxySelector,
        peerTracker = peerTracker,
        rateLimitLog = rateLimitLog,
        siteRegistryProvider = { siteRegistry },
        clockMs = clockMs,
    )

    /**
     * Synchronous snapshot of current proxy, peer, metered status, route epoch, and rate-limit log
     * without issuing any network traffic.
     */
    fun snapshot(siteId: SiteId? = null, targetUrl: String? = null): NetworkDiagnosticReport {
        val registry = siteRegistryProvider()
        val plugin = siteId?.let { registry.get(it) } ?: registry.allPlugins().firstOrNull()
        val resolvedUrl = targetUrl?.takeIf { it.isNotBlank() }
            ?: plugin?.descriptor?.baseUrl?.takeIf { it.isNotBlank() }
            ?: "https://example.com"
        val resolvedName = plugin?.descriptor?.displayName ?: siteId?.value ?: "默认网络"
        val proxyDesc = runCatching { proxySelector.describe(URI(resolvedUrl)) }
            .getOrDefault("系统代理设置不可用")

        return NetworkDiagnosticReport(
            siteId = plugin?.id ?: siteId,
            siteName = resolvedName,
            targetUrl = resolvedUrl,
            reachable = networkMonitor.isConnected.value,
            httpStatus = null,
            latencyMs = 0L,
            proxyDescription = proxyDesc,
            lastPeer = peerTracker.lastPeer,
            routeEpoch = networkMonitor.routeEpoch.value,
            isMetered = networkMonitor.isMetered.value,
            isConnected = networkMonitor.isConnected.value,
            rateLimitEvents = rateLimitLog.entries(),
        )
    }

    /**
     * Performs a live non-cached probe against [siteId] (or explicit [probeUrl]) and returns
     * a complete [NetworkDiagnosticReport].
     */
    suspend fun diagnose(
        siteId: SiteId? = null,
        probeUrl: String? = null,
    ): NetworkDiagnosticReport {
        val registry = siteRegistryProvider()
        val plugin = siteId?.let { registry.get(it) } ?: registry.allPlugins().firstOrNull()
        val resolvedUrl = probeUrl?.takeIf { it.isNotBlank() }
            ?: plugin?.descriptor?.baseUrl?.takeIf { it.isNotBlank() }
            ?: return snapshot(siteId, probeUrl).copy(
                reachable = false,
                errorMessage = "未找到可供诊断的目标站点地址",
            )
        val resolvedSiteId = plugin?.id ?: siteId
        val resolvedName = plugin?.descriptor?.displayName ?: resolvedSiteId?.value ?: resolvedUrl
        val proxyDesc = runCatching { proxySelector.describe(URI(resolvedUrl)) }
            .getOrDefault("系统代理设置不可用")

        val startMs = clockMs()
        return try {
            val response = httpClient.execute(
                SiteHttpRequest(
                    url = resolvedUrl,
                    siteId = resolvedSiteId,
                    method = SiteHttpMethod.GET,
                    kind = SiteRequestKind.PROBE,
                    followRedirects = true,
                    allowCookies = false,
                    allowCache = false,
                    allowRetry = false,
                ),
            )
            val latency = (clockMs() - startMs).coerceAtLeast(0L)
            val isReachable = response.code in 200..399
            val errMsg = if (isReachable) null else SiteHttpErrorMapper.fromResponse(response, resolvedSiteId)?.message
            NetworkDiagnosticReport(
                siteId = resolvedSiteId,
                siteName = resolvedName,
                targetUrl = resolvedUrl,
                reachable = isReachable,
                httpStatus = response.code,
                latencyMs = latency,
                proxyDescription = proxyDesc,
                lastPeer = peerTracker.lastPeer,
                routeEpoch = networkMonitor.routeEpoch.value,
                isMetered = networkMonitor.isMetered.value,
                isConnected = networkMonitor.isConnected.value,
                rateLimitEvents = rateLimitLog.entries(),
                errorMessage = errMsg,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            val latency = (clockMs() - startMs).coerceAtLeast(0L)
            NetworkDiagnosticReport(
                siteId = resolvedSiteId,
                siteName = resolvedName,
                targetUrl = resolvedUrl,
                reachable = false,
                httpStatus = null,
                latencyMs = latency,
                proxyDescription = proxyDesc,
                lastPeer = peerTracker.lastPeer,
                routeEpoch = networkMonitor.routeEpoch.value,
                isMetered = networkMonitor.isMetered.value,
                isConnected = networkMonitor.isConnected.value,
                rateLimitEvents = rateLimitLog.entries(),
                errorMessage = error.message ?: error::class.simpleName,
            )
        }
    }
}
