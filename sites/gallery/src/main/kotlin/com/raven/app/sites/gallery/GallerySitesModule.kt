package com.raven.app.sites.gallery

import com.raven.app.core.sitespi.SitePlugin
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin module registering all 5 photo/gallery [SitePlugin] implementations via multi-binding.
 * Injects `:core:site-spi`'s [com.raven.app.core.sitespi.HttpClientAdapter] without coupling to `:core:network`.
 */
val gallerySitesModule: Module = module {
    single { FourKhdSitePlugin(get()) } bind SitePlugin::class
    single { EveriaSitePlugin(get()) } bind SitePlugin::class
    single { FourKupSitePlugin(get()) } bind SitePlugin::class
    single { GalleryEpicSitePlugin(get()) } bind SitePlugin::class
    single { CosplayteleSitePlugin(get()) } bind SitePlugin::class
}
