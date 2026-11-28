package com.raven.app.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSyncTrustStoreTest {

    @Test
    fun `trust store initializes stable identity updates names and encrypts trusted peer secrets`() {
        val storage = InMemoryVaultStorage()
        val cipher = SoftwareAesGcmVaultCipher()
        val store = LanSyncTrustStore(cipher = cipher, storage = storage)

        val created = store.loadOrCreate("Pixel-9")
        assertTrue(created.localDeviceId.isNotBlank())
        assertEquals("Pixel-9", created.localDeviceName)
        assertTrue(created.trustedPeers.isEmpty())

        // Second call returns the exact same identity
        assertEquals(created.localDeviceId, store.loadOrCreate("Other").localDeviceId)

        // Device name validation & update
        assertThrows(IllegalArgumentException::class.java) { store.updateLocalDeviceName("   ") }
        assertThrows(IllegalArgumentException::class.java) { store.updateLocalDeviceName("a".repeat(40)) }
        val renamed = store.updateLocalDeviceName("客厅平板")
        assertEquals("客厅平板", renamed.localDeviceName)

        // Secret length guard
        assertThrows(IllegalArgumentException::class.java) {
            store.saveTrustedPeer("peer-1", "iPad", ByteArray(16))
        }

        val secret = ByteArray(LanSyncCrypto.KEY_BYTES) { index -> (index + 17).toByte() }
        val withPeer = store.saveTrustedPeer(
            deviceId = "peer-1",
            deviceName = "备用手机",
            sharedSecretBytes = secret,
            host = "192.168.1.35",
            port = 45874,
            nowMs = 1_000L,
        )
        assertEquals(1, withPeer.trustedPeers.size)
        val found = store.findTrustedPeer("peer-1")
        assertNotNull(found)
        assertArrayEquals(secret, found!!.decodeSharedSecret())
        assertFalse(storage.snapshotRawEntries().values.joinToString().contains("备用手机"))

        store.recordSyncSuccess("peer-1", "备用主力机", host = "192.168.1.36", port = 45874, nowMs = 2_000L)
        val updatedPeer = store.findTrustedPeer("peer-1")!!
        assertEquals("备用主力机", updatedPeer.deviceName)
        assertEquals(2_000L, updatedPeer.lastSyncedAtMs)
        assertEquals("192.168.1.36", updatedPeer.lastKnownHost)

        store.removeTrustedPeer("peer-1")
        assertNull(store.findTrustedPeer("peer-1"))
    }
}
