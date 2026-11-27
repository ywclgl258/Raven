package com.raven.app.core.network

import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteSessionStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Carries the auth epoch a call *left* under from dispatch to the response cookie write.
 *
 * Why this exists: [SiteCookieJar.saveFromResponse] must reject a `Set-Cookie` whose request
 * started before a logout/login bumped the epoch — otherwise a late response silently restores a
 * cleared session (NET-09). OkHttp gives the cookie jar only an [okhttp3.HttpUrl], and it invokes
 * `loadForRequest` / `saveFromResponse` from inside the application interceptor chain (OkHttp's
 * built-in `BridgeInterceptor` runs there), so the only place that sees both boundaries of one call
 * is the thread executing it.
 *
 * Why a `ThreadLocal` and not a URL-keyed map: one OkHttp dispatcher thread executes exactly one
 * call at a time, so a thread-scoped value pairs epoch and response **per call** even when two
 * identical URLs are in flight concurrently (they run on different threads and complete in either
 * order). A shared map keyed by host/account cannot distinguish them and would mis-pair.
 *
 * Registration happens in [SiteCookieEpochInterceptor] for every hop of a call (redirects included:
 * they execute inside the same `chain.proceed`), and removed again in a `finally`.
 */
internal object SiteCookieEpochScope {

    private data class CallScope(
        val accountId: SiteId,
        val epoch: Long,
        /** Host the call was dispatched against; redirect hops keep the same scope. */
        val dispatchHost: String,
    )

    private val threadScope = ThreadLocal<CallScope?>()

    fun <T> runScoped(accountId: SiteId, epoch: Long, dispatchHost: String, block: () -> T): T {
        val previous = threadScope.get()
        threadScope.set(CallScope(accountId, epoch, dispatchHost.lowercase()))
        try {
            return block()
        } finally {
            if (previous == null) threadScope.remove() else threadScope.set(previous)
        }
    }

    /** Epoch this call departed under, or null when the call was not scoped. */
    fun epochFor(accountId: SiteId): Long? = threadScope.get()
        ?.takeIf { it.accountId == accountId }
        ?.epoch
}

/**
 * Application interceptor that captures the account auth epoch **before** the request leaves and
 * exposes it to [SiteCookieJar] for the response-write compare-and-swap.
 *
 * Deliberately an application interceptor: it wraps the whole call including redirects and OkHttp's
 * `BridgeInterceptor` (which performs both cookie-jar callbacks), so one scope covers every hop and
 * is torn down in `finally` even when the call is cancelled or throws.
 */
internal class SiteCookieEpochInterceptor(
    private val sessionStoreProvider: () -> SiteSessionStore,
    private val siteRegistryProvider: () -> SiteRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val tag = request.tag(SiteRequestTag::class.java)
        val registry = siteRegistryProvider()
        val plugin = tag?.siteId?.let { registry.get(it) } ?: registry.findForMediaHost(host)
        val accountId = plugin?.id?.accountId
            ?: return chain.proceed(request)

        // Credential reads/bumps go through the account identity (E-Hentai/ExHentai share one).
        val epoch = runCatching { sessionStoreProvider().authEpoch(accountId) }.getOrNull()
            ?: return chain.proceed(request)
        return SiteCookieEpochScope.runScoped(accountId, epoch, host) {
            chain.proceed(request)
        }
    }
}
