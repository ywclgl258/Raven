package com.raven.app

import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry

/**
 * Resolves the one-shot site override for a process cold start.
 * A missing preference means "remember last used"; unknown and already-active sites need no write.
 */
internal fun resolveStartupDefaultSite(
    settings: AppSettings,
    registeredSiteIds: Collection<SiteId>,
): SiteId? = settings.startupDefaultSiteId
    ?.takeIf { it != settings.activeSiteId && it in registeredSiteIds }

/** Called only by [RavenApplication.onCreate], never by navigation or preference collectors. */
internal suspend fun applyStartupDefaultSiteOnColdStart(
    preferencesRepository: PreferencesRepository,
    siteRegistry: SiteRegistry,
) {
    val settings = preferencesRepository.currentSettings()
    val registeredIds = siteRegistry.allPlugins().map { it.id }
    val target = resolveStartupDefaultSite(
        settings = settings,
        registeredSiteIds = registeredIds,
    )
    if (target != null) {
        preferencesRepository.selectActiveSiteAndRecordOpen(target)
        return
    }
    if (settings.activeSiteId in registeredIds) {
        preferencesRepository.recordSiteOpen(settings.activeSiteId)
        return
    }
    val fallback = registeredIds.firstOrNull() ?: return
    preferencesRepository.selectActiveSiteAndRecordOpen(fallback)
}
