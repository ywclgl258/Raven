package com.raven.app.sites.video

import com.raven.app.core.sitespi.SitePlugin
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin module registering all 7 video [SitePlugin] implementations via multi-binding.
 * Depends solely on injected `HttpClientAdapter` and `SiteSessionStore` from `:core:site-spi`.
 */
val videoSitesModule: Module = module {
    single { R34vSitePlugin(http = get(), sessionStore = get()) } bind SitePlugin::class
    single { HanimeSitePlugin(http = get(), sessionStore = get()) } bind SitePlugin::class
    single { IwaraSitePlugin(http = get(), sessionStore = get()) } bind SitePlugin::class
    single { MissavSitePlugin(http = get()) } bind SitePlugin::class
    single { JableSitePlugin(http = get()) } bind SitePlugin::class
    single { RouvideoSitePlugin(http = get()) } bind SitePlugin::class
    single { PornhubSitePlugin(http = get()) } bind SitePlugin::class
}
