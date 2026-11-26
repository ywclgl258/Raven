package com.raven.app.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteModelsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `site id collections contain exact 24 live plus local and 7 retired`() {
        assertEquals(7, SiteId.VIDEO_SITES.size)
        assertEquals(SiteId.HANIME1, SiteId.VIDEO_SITES.first())
        assertEquals(6, SiteId.COMIC_SITES.size)
        assertEquals(6, SiteId.SERIAL_COMIC_SITES.size)
        assertEquals(5, SiteId.PHOTO_SITES.size)
        assertEquals(24, SiteId.ALL_LIVE.size)
        assertEquals(SiteId.HANIME1, SiteId.ALL_LIVE.first())
        assertEquals(24, SiteId.ALL_LIVE.toSet().size)
        assertEquals(25, SiteId.LIBRARY_SOURCES.size)
        assertTrue(SiteId.LOCAL.isLocal)
        assertFalse(SiteId.LOCAL.isLive)
        assertTrue(SiteId.GLOBAL.isGlobal)
        assertFalse(SiteId.GLOBAL.isLive)
        assertFalse(SiteId.GLOBAL in SiteId.ALL_LIVE)
        assertFalse(SiteId.GLOBAL in SiteId.LIBRARY_SOURCES)
        assertTrue(SiteId.GLOBAL.isKnown)
        assertTrue(SiteId.R34V.isLive)
        assertTrue(SiteId.RETIRED_KBJ.isRetired)
        assertTrue(SiteId.RETIRED_KBJFREE.isRetired)
        assertTrue(SiteId.RETIRED_NETFLAV.isRetired)
        assertTrue(SiteId.RETIRED_HDOUJIN.isRetired)
        assertTrue(SiteId.RETIRED_MH160.isRetired)
        assertTrue(SiteId.RETIRED_MYCOMIC.isRetired)
        assertTrue(SiteId.RETIRED_VOMIC.isRetired)
        assertTrue(SiteId.RETIRED_KBJ.isKnown)
        assertTrue(SiteId.RETIRED_KBJFREE.isKnown)
        assertTrue(SiteId.RETIRED_HDOUJIN.isKnown)
        assertTrue(SiteId.RETIRED_MH160.isKnown)
        assertTrue(SiteId.RETIRED_MYCOMIC.isKnown)
        assertTrue(SiteId.RETIRED_VOMIC.isKnown)
        assertFalse(SiteId.RETIRED_HDOUJIN.isLive)
        assertFalse(SiteId.RETIRED_MH160.isLive)
        assertFalse(SiteId.RETIRED_MYCOMIC.isLive)
        assertFalse(SiteId.RETIRED_VOMIC.isLive)
        assertFalse(SiteId("unknown").isKnown)
    }

    @Test
    fun `site id serializes transparently as plain json string`() {
        val encoded = json.encodeToString(SiteId.COSPLAYTELE)
        assertEquals("\"cosplaytele\"", encoded)
        assertEquals(SiteId.COSPLAYTELE, json.decodeFromString<SiteId>(encoded))
        assertEquals(SiteId.R34V, SiteId.of("  R34V "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank site id is rejected`() {
        SiteId("   ")
    }

    @Test
    fun `site descriptor matches primary mirrors api and media domains accurately`() {
        val iwara = SiteDescriptor(
            id = SiteId.IWARA,
            displayName = "Iwara",
            primaryKind = MediaKind.VIDEO,
            supportedKinds = setOf(MediaKind.VIDEO, MediaKind.PHOTO),
            defaultHost = "www.iwara.tv",
            capabilities = setOf(SiteCapability.FEED, SiteCapability.DUAL_MEDIA, SiteCapability.AUTH),
            networkProfile = SiteNetworkProfile(
                apiBase = "https://apiq.iwara.tv",
                apiHosts = setOf("apiq.iwara.tv", "api.iwara.tv"),
                mediaDomains = setOf("iwara.tv"),
            ),
        )
        assertTrue(iwara.matchesHost("iwara.tv"))
        assertTrue(iwara.matchesHost("www.iwara.tv"))
        assertFalse(iwara.matchesHost("apiq.iwara.tv"))
        assertTrue(iwara.matchesRequestHost("apiq.iwara.tv"))
        assertTrue(iwara.matchesMediaHost("filesq.iwara.tv"))
        assertTrue(iwara.matchesMediaHost("i.iwara.tv"))
        assertFalse(iwara.matchesMediaHost("notiwara.tv"))

        val jm = SiteDescriptor(
            id = SiteId.JMCOMIC,
            displayName = "JMComic",
            primaryKind = MediaKind.COMIC,
            defaultHost = "jmcomic-zzz.one",
            mirrorHosts = listOf("18comic.ink", "jmcomic-zzz.org", "18comic.vip"),
            capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH, SiteCapability.MIRRORS),
        )
        assertTrue(jm.matchesHost("18comic.ink"))
        assertTrue(jm.matchesHost("www.18comic.vip"))
        assertFalse(jm.matchesHost("evil-18comic.ink"))
        assertEquals(
            listOf("jmcomic-zzz.one", "18comic.ink", "jmcomic-zzz.org", "18comic.vip"),
            jm.allHosts,
        )
    }
}
