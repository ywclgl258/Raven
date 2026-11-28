package com.raven.app.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException

class LanSyncCryptoTest {

    @Test
    fun `ecdh plus matching pin derives identical session and mutual auth keys while wrong pin diverges`() {
        val clientKp = LanSyncCrypto.generateEphemeralKeyPair()
        val serverKp = LanSyncCrypto.generateEphemeralKeyPair()
        val clientPub = LanSyncCrypto.encodePublicKey(clientKp.public)
        val serverPub = LanSyncCrypto.encodePublicKey(serverKp.public)

        val clientNonce = LanSyncCrypto.randomBytes(LanSyncCrypto.NONCE_BYTES)
        val serverNonce = LanSyncCrypto.randomBytes(LanSyncCrypto.NONCE_BYTES)
        val serverId = "server-device-01"
        val clientId = "client-device-01"

        val clientEcdh = LanSyncCrypto.computeEcdhSharedSecret(clientKp.private, serverPub)
        val serverEcdh = LanSyncCrypto.computeEcdhSharedSecret(serverKp.private, clientPub)
        assertArrayEquals(clientEcdh, serverEcdh)

        val salt = LanSyncCrypto.computePinSalt(clientNonce, serverNonce, serverId)
        val pinAuthClient = LanSyncCrypto.derivePinAuthSecret("482910".toCharArray(), salt, iterations = 1_000)
        val pinAuthServer = LanSyncCrypto.derivePinAuthSecret("482910".toCharArray(), salt, iterations = 1_000)
        val wrongPinAuth = LanSyncCrypto.derivePinAuthSecret("000000".toCharArray(), salt, iterations = 1_000)

        val transcript = LanSyncCrypto.computeTranscriptHash(
            clientDeviceId = clientId,
            serverDeviceId = serverId,
            syncMode = "TWO_WAY",
            authMode = "PIN",
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            clientPublicKey = clientPub,
            serverPublicKey = serverPub,
        )

        val clientKeys = LanSyncCrypto.deriveHandshakeKeys(
            ecdhSecret = clientEcdh,
            authSecretBytes = pinAuthClient,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            clientPublicKey = clientPub,
            serverPublicKey = serverPub,
            transcriptHash = transcript,
        )
        val serverKeys = LanSyncCrypto.deriveHandshakeKeys(
            ecdhSecret = serverEcdh,
            authSecretBytes = pinAuthServer,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            clientPublicKey = clientPub,
            serverPublicKey = serverPub,
            transcriptHash = transcript,
        )
        val wrongKeys = LanSyncCrypto.deriveHandshakeKeys(
            ecdhSecret = clientEcdh,
            authSecretBytes = wrongPinAuth,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            clientPublicKey = clientPub,
            serverPublicKey = serverPub,
            transcriptHash = transcript,
        )

        assertArrayEquals(clientKeys.sessionKey, serverKeys.sessionKey)
        assertArrayEquals(clientKeys.clientAuthTag, serverKeys.clientAuthTag)
        assertArrayEquals(clientKeys.serverAuthTag, serverKeys.serverAuthTag)
        assertArrayEquals(clientKeys.trustedSharedSecret, serverKeys.trustedSharedSecret)
        assertFalse(LanSyncCrypto.constantTimeEquals(wrongKeys.clientAuthTag, serverKeys.clientAuthTag))

        clientKeys.wipe()
        serverKeys.wipe()
        wrongKeys.wipe()
        assertTrue(clientKeys.sessionKey.all { it == 0.toByte() })
    }

    @Test
    fun `aes gcm frame encryption verifies aad and rejects tampered ciphertext`() {
        val key = LanSyncCrypto.randomBytes(LanSyncCrypto.KEY_BYTES)
        val iv = LanSyncCrypto.randomBytes(LanSyncCrypto.IV_BYTES)
        val aad = "1|5|1|c1|s1".toByteArray(Charsets.UTF_8)
        val plaintext = "Raven encrypted sync snapshot".toByteArray(Charsets.UTF_8)

        val ciphertext = LanSyncCrypto.encryptFrame(key, iv, aad, plaintext)
        val decrypted = LanSyncCrypto.decryptFrame(key, iv, aad, ciphertext)
        assertArrayEquals(plaintext, decrypted)

        val wrongAad = "1|5|2|c1|s1".toByteArray(Charsets.UTF_8)
        assertThrows(GeneralSecurityException::class.java) {
            LanSyncCrypto.decryptFrame(key, iv, wrongAad, ciphertext)
        }

        val tampered = ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0xFF).toByte() }
        assertThrows(GeneralSecurityException::class.java) {
            LanSyncCrypto.decryptFrame(key, iv, aad, tampered)
        }
    }

    @Test
    fun `bounded gzip decompression protects against oversized bombs and validates pin formatting`() {
        val sample = ("Raven-Sync-Chunk-" + "A".repeat(200)).toByteArray(Charsets.UTF_8)
        val compressed = LanSyncCrypto.gzipCompress(sample)
        assertArrayEquals(sample, LanSyncCrypto.gzipDecompressBounded(compressed, maxPlainBytes = 4096))

        assertThrows(IOException::class.java) {
            LanSyncCrypto.gzipDecompressBounded(compressed, maxPlainBytes = 32)
        }

        val pin = LanSyncCrypto.generatePairingPin()
        assertEquals(6, pin.length)
        assertTrue(pin.all { it in '0'..'9' })
        assertTrue(LanSyncCrypto.deviceFingerprint("abc").matches(Regex("^[0-9A-F]{4}-[0-9A-F]{4}$")))
    }
}
