package com.raven.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyAndWebDavStoreTest {

    @Test
    fun `privacy lock stores salted PBKDF2 hash inside encrypted cipher and verifies pins accurately`() {
        val storage = InMemoryVaultStorage()
        val cipher = SoftwareAesGcmVaultCipher()
        val store = PrivacyLockStore(cipher = cipher, storage = storage)

        assertFalse(store.hasPin())
        assertThrows(IllegalArgumentException::class.java) { store.setPin("123") }
        assertThrows(IllegalArgumentException::class.java) { store.setPin("12ab") }

        store.setPin("2580")
        assertTrue(store.hasPin())
        assertTrue(store.verifyPin("2580"))
        assertFalse(store.verifyPin("0000"))
        assertFalse(store.verifyPin("123"))

        // Plaintext PIN must never appear in persisted ciphertext
        assertFalse(storage.snapshotRawEntries().values.joinToString().contains("2580"))

        store.clearPin()
        assertFalse(store.hasPin())
        assertFalse(store.verifyPin("2580"))
    }

    @Test
    fun `webdav credential store enforces https, password policy, safe filenames, and encryption`() {
        val storage = InMemoryVaultStorage()
        val cipher = SoftwareAesGcmVaultCipher()
        val store = WebDavCredentialStore(cipher = cipher, storage = storage)

        assertFalse(store.hasConfig())
        assertNull(store.load())

        // Rejects cleartext http://
        assertThrows(IllegalArgumentException::class.java) {
            store.save(
                WebDavConfig(
                    endpointUrl = "http://dav.example.com/raven/",
                    username = "alice",
                    password = "pw",
                    backupPassphrase = "strong-password-123",
                ),
            )
        }
        // Rejects path traversal filename
        assertThrows(IllegalArgumentException::class.java) {
            store.save(
                WebDavConfig(
                    endpointUrl = "https://dav.example.com/raven/",
                    username = "alice",
                    password = "pw",
                    backupPassphrase = "strong-password-123",
                    remoteFileName = "../evil.rvnbk",
                ),
            )
        }

        val saved = store.save(
            WebDavConfig(
                endpointUrl = "https://dav.example.com/raven/",
                username = "alice",
                password = "secret-dav-password",
                backupPassphrase = "strong-password-123",
                remoteFileName = "my-backup.rvnbk",
                autoSyncEnabled = true,
            ),
        )
        assertEquals("https://dav.example.com/raven", saved.endpointUrl)
        assertTrue(store.hasConfig())
        assertEquals(saved, store.load())
        assertFalse(storage.snapshotRawEntries().values.joinToString().contains("secret-dav-password"))

        store.clear()
        assertFalse(store.hasConfig())
        assertNull(store.load())
    }
}
