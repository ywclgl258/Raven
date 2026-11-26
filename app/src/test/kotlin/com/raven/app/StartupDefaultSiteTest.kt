package com.raven.app

import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.model.SiteId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupDefaultSiteTest {

    private val registeredSiteIds = listOf(SiteId.R34V, SiteId.MANGACOPY, SiteId.NHENTAI)

    @Test
    fun `remember last used does not override active site`() {
        val settings = AppSettings(activeSiteId = SiteId.NHENTAI)

        assertNull(resolveStartupDefaultSite(settings, registeredSiteIds))
    }

    @Test
    fun `registered startup site overrides last active site`() {
        val settings = AppSettings(
            activeSiteId = SiteId.R34V,
            startupDefaultSiteId = SiteId.MANGACOPY,
        )

        assertEquals(
            SiteId.MANGACOPY,
            resolveStartupDefaultSite(settings, registeredSiteIds),
        )
    }

    @Test
    fun `already active startup site does not cause redundant write`() {
        val settings = AppSettings(
            activeSiteId = SiteId.MANGACOPY,
            startupDefaultSiteId = SiteId.MANGACOPY,
        )

        assertNull(resolveStartupDefaultSite(settings, registeredSiteIds))
    }

    @Test
    fun `unregistered startup site leaves active site unchanged`() {
        val settings = AppSettings(
            activeSiteId = SiteId.R34V,
            startupDefaultSiteId = SiteId.of("removed-site"),
        )

        assertNull(resolveStartupDefaultSite(settings, registeredSiteIds))
    }
}
