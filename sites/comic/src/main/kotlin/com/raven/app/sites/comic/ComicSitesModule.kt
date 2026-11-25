package com.raven.app.sites.comic

import com.raven.app.core.sitespi.SitePlugin
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin DI module registering all 6 comic [SitePlugin] implementations via multi-binding (`bind SitePlugin::class`).
 * Dependencies resolved from core modules: `HttpClientAdapter` and `SiteSessionStore`.
 */
val comicSitesModule: Module = module {
    single { EhentaiSitePlugin(get(), get()) } bind SitePlugin::class
    single { ExhentaiSitePlugin(get(), get()) } bind SitePlugin::class
    single { NhentaiSitePlugin(get()) } bind SitePlugin::class
    single { HitomiSitePlugin(get()) } bind SitePlugin::class
    single { WnacgSitePlugin(get()) } bind SitePlugin::class
    single { JmcomicSitePlugin(get()) } bind SitePlugin::class
    single { HdoujinSitePlugin(get()) } bind SitePlugin::class
    single { MangacopySitePlugin(get()) } bind SitePlugin::class
    single { Mh160SitePlugin(get()) } bind SitePlugin::class
    single { GuazimanhuaSitePlugin(get()) } bind SitePlugin::class
    single { MycomicSitePlugin(get()) } bind SitePlugin::class
    single { HipmhSitePlugin(get()) } bind SitePlugin::class
}
