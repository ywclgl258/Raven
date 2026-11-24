package com.raven.app.core.security

import com.raven.app.core.model.SiteId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVaultTest {

    private fun newVault(
        cipher: VaultCipher = SoftwareAesGcmVaultCipher(),
        storage: InMemoryVaultStorage = InMemoryVaultStorage(),
    ): Pair<SessionVault, InMemoryVaultStorage> = SessionVault(cipher, storage) to storage

    @Test
    fun `encrypts cookies tokens usernames and secrets across all 5 auth-capable sites with zero plaintext`() {
        val (vault, storage) = newVault()

        vault.saveCookies(SiteId.R34V, mapOf("PHPSESSID" to "r34-sess-secret-999"))
        vault.saveUsername(SiteId.R34V, "r34_user_secret")

        vault.saveCookies(SiteId.HANIME1, mapOf("laravel_session" to "hanime-sess-secret-888"))
        vault.saveUsername(SiteId.HANIME1, "hanime_user_secret")

        vault.saveBearerToken(SiteId.IWARA, token = "eyJhbGciOiJIUzI1NiJ9.iwara-jwt-payload", username = "iwara_user_secret")
        vault.saveSecret(SiteId.IWARA, "refreshToken", "iwara-refresh-secret-777")

        vault.saveCookies(SiteId.EHENTAI, mapOf("ipb_member_id" to "123456", "ipb_pass_hash" to "eh-passhash-secret-666"))
        vault.saveCookies(
            SiteId.EXHENTAI,
            mapOf(
                "ipb_member_id" to "654321",
                "ipb_pass_hash" to "ex-passhash-secret-555",
                "igneous" to "ex-igneous-secret-444",
            ),
        )

        // Verify raw persisted storage contains ZERO plaintext secrets, usernames, or cookie names/values
        val rawDump = storage.snapshotRawEntries().values.joinToString("\n")
        assertEquals(5, storage.snapshotRawEntries().size)
        val sensitiveLiterals = listOf(
            "PHPSESSID",
            "r34-sess-secret-999",
            "r34_user_secret",
            "laravel_session",
            "hanime-sess-secret-888",
            "hanime_user_secret",
            "iwara-jwt-payload",
            "iwara_user_secret",
            "iwara-refresh-secret-777",
            "eh-passhash-secret-666",
            "ex-passhash-secret-555",
            "ex-igneous-secret-444",
        )
        for (secret in sensitiveLiterals) {
            assertFalse("Raw vault storage must not contain plaintext '$secret'", rawDump.contains(secret))
        }

        // Evict in-memory cache to force real AES-256-GCM decryption from storage
        vault.evictMemoryCache()

        assertEquals("r34-sess-secret-999", vault.getCookies(SiteId.R34V)["PHPSESSID"])
        assertEquals("r34_user_secret", vault.getUsername(SiteId.R34V))
        assertEquals("hanime-sess-secret-888", vault.getCookies(SiteId.HANIME1)["laravel_session"])
        assertEquals("hanime_user_secret", vault.getUsername(SiteId.HANIME1))
        assertEquals("eyJhbGciOiJIUzI1NiJ9.iwara-jwt-payload", vault.getBearerToken(SiteId.IWARA))
        assertEquals("iwara_user_secret", vault.getUsername(SiteId.IWARA))
        assertEquals("iwara-refresh-secret-777", vault.getSecret(SiteId.IWARA, "refreshToken"))
        assertEquals("eh-passhash-secret-666", vault.getCookies(SiteId.EHENTAI)["ipb_pass_hash"])
        assertEquals("ex-igneous-secret-444", vault.getCookies(SiteId.EXHENTAI)["igneous"])
    }

    @Test
    fun `every encryption uses a fresh random 12-byte IV`() {
        val (vault, _) = newVault()
        val first = vault.seal("identical-secret-payload", associatedData = "site:r34v")
        val second = vault.seal("identical-secret-payload", associatedData = "site:r34v")
        assertNotEquals(first, second)
        assertEquals("identical-secret-payload", vault.open(first, associatedData = "site:r34v"))
        assertEquals("identical-secret-payload", vault.open(second, associatedData = "site:r34v"))
    }

    @Test
    fun `rejects tampered IV ciphertext tag wrong AAD and wrong key with zero plaintext fallback`() {
        val cipher1 = SoftwareAesGcmVaultCipher()
        val cipher2 = SoftwareAesGcmVaultCipher()
        val storage = InMemoryVaultStorage()
        val vault1 = SessionVault(cipher1, storage)

        vault1.saveBearerToken(SiteId.IWARA, "top-secret-jwt", "alice")
        val storageKey = SessionVault.storageKey(SiteId.IWARA)
        val originalEncoded = storage.getString(storageKey)!!
        val originalBytes = Base64Codec.decode(originalEncoded)
        assertTrue(originalBytes.size > VaultCipher.MIN_CIPHERTEXT_BYTES)

        // 1. Flip bit in GCM authentication tag (last byte)
        val tagTampered = originalBytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }
        storage.putString(storageKey, Base64Codec.encode(tagTampered))
        vault1.evictMemoryCache()
        assertThrows(SessionVaultException::class.java) { vault1.getBearerToken(SiteId.IWARA) }

        // 2. Flip bit in 12-byte IV (byte 0)
        val ivTampered = originalBytes.copyOf().also { it[0] = (it[0].toInt() xor 0x80).toByte() }
        storage.putString(storageKey, Base64Codec.encode(ivTampered))
        vault1.evictMemoryCache()
        assertThrows(SessionVaultException::class.java) { vault1.getBearerToken(SiteId.IWARA) }

        // 3. Cross-site ciphertext transplantation (AAD mismatch!)
        storage.putString(storageKey, originalEncoded)
        storage.putString(SessionVault.storageKey(SiteId.R34V), originalEncoded)
        vault1.evictMemoryCache()
        assertEquals("top-secret-jwt", vault1.getBearerToken(SiteId.IWARA))
        assertThrows(SessionVaultException::class.java) { vault1.getBearerToken(SiteId.R34V) }

        // 4. Wrong AES-256 key
        val vaultWrongKey = SessionVault(cipher2, storage)
        assertThrows(SessionVaultException::class.java) { vaultWrongKey.getBearerToken(SiteId.IWARA) }

        // 5. Raw plaintext injected directly into storage is NEVER returned as fallback
        storage.putString(SessionVault.storageKey(SiteId.HANIME1), """{"bearerToken":"injected"}""")
        vault1.evictMemoryCache()
        assertThrows(SessionVaultException::class.java) { vault1.getBearerToken(SiteId.HANIME1) }
    }

    @Test
    fun `isolates sites and increments authEpoch monotonically on mutations and clearSite`() {
        val (vault, _) = newVault()
        assertEquals(0L, vault.authEpoch(SiteId.IWARA))
        assertEquals(0L, vault.authEpoch(SiteId.EXHENTAI))
        assertEquals(0L, vault.authEpochFlow(SiteId.IWARA).value)

        vault.saveCookies(SiteId.EXHENTAI, mapOf("ipb_member_id" to "100", "ipb_pass_hash" to "hash-abc"))
        val exEpoch1 = vault.authEpoch(SiteId.EXHENTAI)
        assertTrue(exEpoch1 > 0L)
        assertEquals(0L, vault.authEpoch(SiteId.IWARA))

        vault.saveBearerToken(SiteId.IWARA, "jwt-1", "bob")
        val iwEpoch1 = vault.authEpoch(SiteId.IWARA)
        assertEquals(1L, iwEpoch1)
        assertEquals(1L, vault.authEpochFlow(SiteId.IWARA).value)

        vault.removeCookies(SiteId.EXHENTAI, setOf("ipb_pass_hash"))
        assertEquals(mapOf("ipb_member_id" to "100"), vault.getCookies(SiteId.EXHENTAI))
        assertTrue(vault.authEpoch(SiteId.EXHENTAI) > exEpoch1)

        vault.replaceCookies(SiteId.EXHENTAI, mapOf("ipb_member_id" to "200", "igneous" to "new-ig"))
        assertEquals(mapOf("ipb_member_id" to "200", "igneous" to "new-ig"), vault.getCookies(SiteId.EXHENTAI))

        // Clearing Iwara only affects Iwara; ExHentai stays untouched
        vault.clearSite(SiteId.IWARA)
        assertTrue(vault.authEpoch(SiteId.IWARA) > iwEpoch1)
        assertNull(vault.getBearerToken(SiteId.IWARA))
        assertNull(vault.getUsername(SiteId.IWARA))
        assertFalse(vault.hasAnySession(SiteId.IWARA))
        assertTrue(vault.hasAnySession(SiteId.EXHENTAI))
        assertEquals("new-ig", vault.getCookies(SiteId.EXHENTAI)["igneous"])
    }

    @Test
    fun `persisted encrypted records survive across new SessionVault instance`() {
        val cipher = SoftwareAesGcmVaultCipher()
        val storage = InMemoryVaultStorage()

        val firstInstance = SessionVault(cipher, storage)
        firstInstance.saveCookies(SiteId.R34V, mapOf("PHPSESSID" to "persisted-sess"))
        firstInstance.saveSecret(SiteId.IWARA, "salt", "dynamic-salt-v2")

        val restartedInstance = SessionVault(cipher, storage)
        assertEquals("persisted-sess", restartedInstance.getCookies(SiteId.R34V)["PHPSESSID"])
        assertEquals("dynamic-salt-v2", restartedInstance.getSecret(SiteId.IWARA, "salt"))
    }
}
