package com.raven.app.core.sitespi

import com.raven.app.core.model.SiteId
import org.junit.Assert.assertEquals
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
}
