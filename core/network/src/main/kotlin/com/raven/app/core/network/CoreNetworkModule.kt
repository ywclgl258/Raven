package com.raven.app.core.network

import coil3.ImageLoader
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.sitespi.HttpClientAdapter
import com.raven.app.core.sitespi.SiteRegistry
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
        )
    }

    single {
        SiteCookieJar(
            sessionStore = get(),
            siteRegistryProvider = { get<SiteRegistry>() },
        )
    } bind CookieJar::class

    single {
        NetworkMonitor(
            context = androidContext(),
            proxySelector = get(),
            connectionPool = get(),
        )
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
        )
    }

    single<OkHttpClient>(NetworkQualifiers.PAGE) { get<RavenOkHttpPool>().pageClient }
    single<OkHttpClient>(NetworkQualifiers.API) { get<RavenOkHttpPool>().apiClient }
    single<OkHttpClient>(NetworkQualifiers.IMAGE) { get<RavenOkHttpPool>().imageClient }
    single<OkHttpClient>(NetworkQualifiers.MEDIA) { get<RavenOkHttpPool>().mediaClient }
    single<OkHttpClient>(NetworkQualifiers.PROBE) { get<RavenOkHttpPool>().probeClient }

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
