package com.raven.app.core.network

import coil3.ImageLoader
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.security.SessionVault
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteSessionStore
import kotlinx.coroutines.CoroutineScope
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File
import java.net.ProxySelector
import java.util.concurrent.TimeUnit

/**
 * Koin DI module for `:core:network`.
 *
 * Provides shared coroutine dispatchers/scope, IPv4-first DNS, system proxy selector,
 * [NetworkMonitor], non-blocking [HostPacer], [MediaRedirectCache], encrypted [SiteCookieJar],
 * pre-built named [OkHttpClient] pool ([NetworkQualifiers.PAGE], [NetworkQualifiers.API],
 * [NetworkQualifiers.IMAGE], [NetworkQualifiers.MEDIA], [NetworkQualifiers.PROBE]),
 * [SiteHttpClient] (bound as [HttpClientAdapter]), single Coil 3 [ImageLoader], and [NetworkDiagnostics].
 *
 * Uses lazy `{ get<SiteRegistry>() }` providers so site plugins depending on [HttpClientAdapter]
 * initialize cleanly without circular DI cycles.
 */
val coreNetworkModule: Module = module {
    single<AppDispatchers> { DefaultAppDispatchers() }
    single { ApplicationScope(dispatchers = get()) } bind CoroutineScope::class
    single<CoroutineScope>(NetworkQualifiers.APPLICATION_SCOPE) { get<ApplicationScope>() }

    single { Ipv4FirstDns() } bind Dns::class
    single { SystemProxySelector() } bind ProxySelector::class
    single { RateLimitLog() }
    single { PeerTracker() }
    single { HostPacer() }

    single { ConnectionPool(12, 5, TimeUnit.MINUTES) }
    single { Dispatcher() }

    single {
        MediaRedirectCache(
            eligible = { url -> get<SiteRegistry>().isRegisteredSiteHost(url.host) },
            rateLimitLog = get(),
            // Signed CDN URLs are minted per account: bind each entry to the owning account and the
            // epoch it was resolved under so a logout/login can never replay the old account's URL.
            accountResolver = { url -> get<SiteRegistry>().findForHost(url.host)?.id },
            credentialEpochProvider = { accounts -> get<SessionVault>().authEpoch(accounts) },
        )
    }

    single {
        SiteCookieJar(
            sessionStore = get(),
            siteRegistryProvider = { get<SiteRegistry>() },
        ).apply {
            // Account switch / logout invalidates every media URL minted for that account.
            onAccountChanged = { accountId ->
                // Signed media URLs and personalized PAGE/API bodies from the previous account
                // must never be replayed after a login/logout/import.
                get<MediaRedirectCache>().invalidateForSite(accountId)
                get<RavenOkHttpPool>().evictHttpCache()
            }
        }
    } bind CookieJar::class

    // Plugins mutate credentials directly through SiteSessionStore (login / import / logout), so the
    // vault's epoch is the authoritative signal: every move drops account-derived caches. The app
    // resolves this binding at startup to attach the listener without Koin eager-init AndroidContext
    // requirements in JVM graph tests.
    single {
        SessionCacheInvalidationBinding(
            sessionVault = get(),
            mediaRedirectCache = get(),
            okHttpPool = get(),
        )
    }

    single {
        val redirectCache = get<MediaRedirectCache>()
        NetworkMonitor(
            context = androidContext(),
            proxySelector = get(),
            connectionPool = get(),
        ).apply {
            addRouteInvalidationListener { redirectCache.clear() }
        }
    }

    single {
        RavenOkHttpPool(
            cacheDir = File(androidContext().cacheDir, "http"),
            connectionPool = get(),
            dispatcher = get(),
            dns = get(),
            proxySelector = get(),
            cookieJar = get(),
            mediaRedirectCache = get(),
            peerTracker = get(),
            siteRegistryProvider = { get<SiteRegistry>() },
            sessionStoreProvider = { get<SiteSessionStore>() },
        )
    }

    single<OkHttpClient>(NetworkQualifiers.PAGE) { get<RavenOkHttpPool>().pageClient }
    single<OkHttpClient>(NetworkQualifiers.API) { get<RavenOkHttpPool>().apiClient }
    single<OkHttpClient>(NetworkQualifiers.IMAGE) { get<RavenOkHttpPool>().imageClient }
    single<OkHttpClient>(NetworkQualifiers.MEDIA) { get<RavenOkHttpPool>().mediaClient }
    single<OkHttpClient>(NetworkQualifiers.PROBE) { get<RavenOkHttpPool>().probeClient }
    single<OkHttpClient>(NetworkQualifiers.LAN) { get<RavenOkHttpPool>().lanClient }

    single {
        SiteHttpClient(
            okHttpPool = get(),
            hostPacer = get(),
            sessionStore = get(),
            networkMonitor = get(),
            rateLimitLog = get(),
            siteRegistryProvider = { get<SiteRegistry>() },
            dispatchers = get(),
        )
    } bind HttpClientAdapter::class

    single {
        RavenImageLoaderFactory(
            context = androidContext(),
            imageOkHttpClient = get(NetworkQualifiers.IMAGE),
            siteRegistryProvider = { get<SiteRegistry>() },
        )
    }

    single<ImageLoader> { get<RavenImageLoaderFactory>().create() }

    single {
        NetworkDiagnostics(
            httpClient = get(),
            networkMonitor = get(),
            proxySelector = get(),
            peerTracker = get(),
            rateLimitLog = get(),
            siteRegistryProvider = { get<SiteRegistry>() },
        )
    }
}
