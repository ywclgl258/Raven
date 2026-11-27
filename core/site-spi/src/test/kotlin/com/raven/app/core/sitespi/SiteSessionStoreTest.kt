package com.raven.app.core.sitespi

import com.raven.app.core.model.SiteId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteSessionStoreTest {

    @Test
    fun `in-memory session store isolates sites and increments auth epoch monotonically`() {
        val store: SiteSessionStore = InMemorySiteSessionStore()
        assertEquals(0L, store.authEpoch(SiteId.IWARA))
        assertEquals(0L, store.authEpoch(SiteId.EXHENTAI))

        // Save cookies for ExHentai -> does not touch Iwara
        store.saveCookies(SiteId.EXHENTAI, mapOf("ipb_member_id" to "1001", "ipb_pass_hash" to "abcdef0123456789"))
        val exEpoch1 = store.authEpoch(SiteId.EXHENTAI)
        assertTrue(exEpoch1 > 0L)
        assertEquals(0L, store.authEpoch(SiteId.IWARA))
        assertEquals("1001", store.getCookies(SiteId.EXHENTAI)["ipb_member_id"])

        // Merge additional cookie & remove one
        store.saveCookies(SiteId.EXHENTAI, mapOf("igneous" to "xyz"))
        assertEquals(3, store.getCookies(SiteId.EXHENTAI).size)
        store.removeCookies(SiteId.EXHENTAI, setOf("igneous"))
        assertEquals(2, store.getCookies(SiteId.EXHENTAI).size)
        assertTrue(store.authEpoch(SiteId.EXHENTAI) > exEpoch1)

        // Replace cookies atomically
        store.replaceCookies(SiteId.EXHENTAI, mapOf("ipb_member_id" to "2002"))
        assertEquals(mapOf("ipb_member_id" to "2002"), store.getCookies(SiteId.EXHENTAI))

        // Bearer token, username & secrets for Iwara
        store.saveBearerToken(SiteId.IWARA, token = "jwt-token-1", username = "alice")
        store.saveSecret(SiteId.IWARA, "refreshToken", "ref-123")
        val iwEpoch1 = store.authEpoch(SiteId.IWARA)
        assertEquals("jwt-token-1", store.getBearerToken(SiteId.IWARA))
        assertEquals("alice", store.getUsername(SiteId.IWARA))
        assertEquals("ref-123", store.getSecret(SiteId.IWARA, "refreshToken"))

        // Clear Iwara -> bumps epoch & wipes only Iwara, preserving ExHentai
        store.clearSite(SiteId.IWARA)
        assertTrue(store.authEpoch(SiteId.IWARA) > iwEpoch1)
        assertNull(store.getBearerToken(SiteId.IWARA))
        assertNull(store.getUsername(SiteId.IWARA))
        assertNull(store.getSecret(SiteId.IWARA, "refreshToken"))
        assertEquals(mapOf("ipb_member_id" to "2002"), store.getCookies(SiteId.EXHENTAI))
    }

    @Test
    fun `E-Hentai and ExHentai resolve to one account bucket and one epoch`() {
        val store: SiteSessionStore = InMemorySiteSessionStore()
        store.saveCookies(SiteId.EXHENTAI, mapOf("ipb_member_id" to "777"))
        // Written through the EX host, readable through both ids, one shared epoch.
        assertEquals("777", store.getCookies(SiteId.EHENTAI)["ipb_member_id"])
        assertEquals(store.authEpoch(SiteId.EHENTAI), store.authEpoch(SiteId.EXHENTAI))
        store.clearSite(SiteId.EHENTAI)
        assertTrue(store.getCookies(SiteId.EXHENTAI).isEmpty())
    }

    @Test
    fun `compare-and-write rejects a snapshot whose expected epoch already moved`() {
        val store: SiteSessionStore = InMemorySiteSessionStore()
        store.saveCookies(SiteId.IWARA, mapOf("a" to "1"))
        val epoch = store.authEpoch(SiteId.IWARA)
        assertTrue(
            store.storeSessionSnapshotIfEpoch(
                siteId = SiteId.IWARA,
                expectedEpoch = epoch,
                cookies = mapOf("b" to "2"),
                secrets = emptyMap(),
            ),
        )
        // A logout in between bumps the epoch; the stale write must be refused.
        store.clearSite(SiteId.IWARA)
        val epochAfter = store.authEpoch(SiteId.IWARA)
        assertTrue(epochAfter > epoch)
        assertFalse(
            store.storeSessionSnapshotIfEpoch(
                siteId = SiteId.IWARA,
                expectedEpoch = epoch,
                cookies = mapOf("resurrected" to "yes"),
                secrets = emptyMap(),
            ),
        )
        assertTrue(store.getCookies(SiteId.IWARA).isEmpty())
    }

    @Test
    fun `snapshot writes without an epoch bump leave in-flight guards satisfied`() {
        val store: SiteSessionStore = InMemorySiteSessionStore()
        store.saveCookies(SiteId.IWARA, mapOf("sid" to "v1"))
        val epoch = store.authEpoch(SiteId.IWARA)
        store.updateSessionSnapshotWithoutEpochBump(SiteId.IWARA, mapOf("sid" to "v1"), emptyMap())
        assertEquals(epoch, store.authEpoch(SiteId.IWARA))
        store.storeSessionSnapshot(SiteId.IWARA, mapOf("sid" to "v1"), emptyMap(), bumpEpoch = false)
        assertEquals(epoch, store.authEpoch(SiteId.IWARA))
        assertEquals("v1", store.getCookies(SiteId.IWARA)["sid"])
    }

    @Test
    fun `hasSession reflects cookies and bearer tokens`() {
        val store: SiteSessionStore = InMemorySiteSessionStore()
        assertFalse(store.hasSession(SiteId.IWARA))
        store.saveCookies(SiteId.IWARA, mapOf("sid" to "x"))
        assertTrue(store.hasSession(SiteId.IWARA))
        store.clearSite(SiteId.IWARA)
        assertFalse(store.hasSession(SiteId.IWARA))
    }
}
