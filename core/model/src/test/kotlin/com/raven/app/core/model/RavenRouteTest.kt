package com.raven.app.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RavenRouteTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `raven routes round trip both directly and polymorphically`() {
        val routes: List<RavenRoute> = listOf(
            RavenRoute.Home,
            RavenRoute.Explore,
            RavenRoute.Search(initialQuery = "blonde", crossSite = true),
            RavenRoute.Library,
            RavenRoute.Profile,
            RavenRoute.SiteFeed(siteId = SiteId.R34V.value, section = "latest", query = "3d", scope = "TAG", title = "3D"),
            RavenRoute.SiteDirectory(siteId = SiteId.GALLERYEPIC.value, section = "cosers"),
            RavenRoute.SiteFunctions(siteId = SiteId.EHENTAI.value),
            RavenRoute.GalleryDetail(siteId = SiteId.NHENTAI.value, mediaId = "420000", url = "https://nhentai.net/g/420000/"),
            RavenRoute.Reader(
                siteId = SiteId.EHENTAI.value,
                mediaId = "1234",
                url = "https://e-hentai.org/g/1234/abcd/",
                startPage = 5,
                offlineOnly = true,
                queueToken = "q-1",
            ),
            RavenRoute.Downloads,
            RavenRoute.ShelfDetail(shelfId = Shelf.WATCH_LATER_ID),
            RavenRoute.SmartShelfDetail(smartShelfId = "smart-1"),
            RavenRoute.Favorites,
            RavenRoute.History,
            RavenRoute.ImageFavorites,
            RavenRoute.Subscriptions(siteId = SiteId.R34V.value),
            RavenRoute.GlobalInbox,
            RavenRoute.LocalLibrary,
            RavenRoute.Settings,
            RavenRoute.SiteAccount(siteId = SiteId.EXHENTAI.value),
            RavenRoute.BlockedTerms(siteId = SiteId.R34V.value),
            RavenRoute.BackupRestore,
            RavenRoute.StorageMigration,
            RavenRoute.DuplicateScanner,
        )

        for (route in routes) {
            val encoded = json.encodeToString<RavenRoute>(route)
            val decoded = json.decodeFromString<RavenRoute>(encoded)
            assertEquals(route, decoded)
        }

        val reader = RavenRoute.Reader(
            siteId = SiteId.JMCOMIC.value,
            mediaId = "500000",
            url = "https://jmcomic-zzz.one/album/500000",
        )
        assertEquals(reader, json.decodeFromString<RavenRoute.Reader>(json.encodeToString(reader)))
    }
}
