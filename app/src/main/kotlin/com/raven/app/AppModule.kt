package com.raven.app

import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.coreDatabaseModule
import com.raven.app.core.media.coreMediaModule
import com.raven.app.core.model.SiteId
import com.raven.app.core.network.coreNetworkModule
import com.raven.app.core.security.coreSecurityModule
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.feature.browse.browseFeatureModule
import com.raven.app.feature.library.libraryFeatureModule
import com.raven.app.feature.player.playerFeatureModule
import com.raven.app.feature.reader.readerFeatureModule
import com.raven.app.feature.settings.RavenAppInfo
import com.raven.app.feature.settings.settingsFeatureModule
import com.raven.app.sites.comic.comicSitesModule
import com.raven.app.sites.gallery.gallerySitesModule
import com.raven.app.sites.video.videoSitesModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Builds an immutable [SiteRegistry] sorting all multi-bound [SitePlugin]s strictly by
 * [SiteId.ALL_LIVE] order and verifying all 19 live sites are registered without duplicates.
 */
fun createOrderedSiteRegistry(plugins: Collection<SitePlugin>): SiteRegistry {
    val order = SiteId.ALL_LIVE.withIndex().associate { (index, id) -> id to index }
    val ordered = plugins.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    val registry = SiteRegistry(ordered)
    val registeredIds = registry.allPlugins().map { it.id }
    val missing = SiteId.ALL_LIVE.filterNot { it in registeredIds }
    check(missing.isEmpty()) {
        "Missing live SitePlugin registrations: ${missing.joinToString { it.value }}"
    }
    check(registeredIds == SiteId.ALL_LIVE) {
        "Unexpected SiteRegistry ordering/contents: expected ${SiteId.ALL_LIVE.map { it.value }}, got ${registeredIds.map { it.value }}"
    }
    return registry
}

/**
 * Application-level Koin DI module per `ARCHITECTURE.md` §3.1–§3.2.
 *
 * - Multi-binds all [SitePlugin] singletons into an immutable [SiteRegistry] sorted strictly by
 *   [SiteId.ALL_LIVE].
 * - Provides [RavenAppInfo] sourced directly from `:app`'s generated [BuildConfig].
 * - Provides shared [TagDictionary.default].
 */
val appModule: Module = module {
    single<TagDictionary> { TagDictionary.default }

    single<RavenAppInfo> {
        RavenAppInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            isDebug = BuildConfig.DEBUG,
            packageName = BuildConfig.APPLICATION_ID,
            releaseCertSha256 = BuildConfig.RELEASE_CERT_SHA256.ifBlank { null },
        )
    }

    single<SiteRegistry> {
        createOrderedSiteRegistry(getAll<SitePlugin>())
    }
}

/**
 * Canonical 13-module Koin graph loaded by [RavenApplication] and verified in `AppDiGraphTest`.
 */
val allRavenKoinModules: List<Module> = listOf(
    coreSecurityModule,
    coreNetworkModule,
    coreDatabaseModule,
    coreMediaModule,
    videoSitesModule,
    comicSitesModule,
    gallerySitesModule,
    browseFeatureModule,
    libraryFeatureModule,
    playerFeatureModule,
    readerFeatureModule,
    settingsFeatureModule,
    appModule,
)
