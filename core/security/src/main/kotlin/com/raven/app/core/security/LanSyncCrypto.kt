package com.raven.app.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Derived ephemeral keys for one authenticated LAN sync TCP session.
 * Call [wipe] in `finally` to zero sensitive key material from memory.
 */
class LanSyncSessionKeys(
    val sessionKey: ByteArray,
    val clientAuthTag: ByteArray,
    val serverAuthTag: ByteArray,
    val trustedSharedSecret: ByteArray,
) {
    fun wipe() {
        sessionKey.fill(0)
        clientAuthTag.fill(0)
        serverAuthTag.fill(0)
        trustedSharedSecret.fill(0)
    }
}

/**
 * Cryptographic primitives for Raven's end-to-end encrypted LAN multi-device sync.
 *
 * Strictly restricted to JCA APIs supported from Android API 24 (Android 7.0) and JVM 17:
 * - Ephemeral ECDH on NIST P-256 (`secp256r1`)
 * - Pairing PIN stretching via `PBKDF2WithHmacSHA1`
 * - HKDF-like session & mutual-authentication key derivation via `HmacSHA256` / `SHA-256`
 * - Authenticated per-frame encryption via `AES/GCM/NoPadding` (256-bit key, 128-bit tag, 96-bit IV)
 * - Bounded GZIP compression to protect against decompression bombs
 */
object LanSyncCrypto {

    const val EC_CURVE: String = "secp256r1"
    const val KEY_BYTES: Int = 32
    const val IV_BYTES: Int = 12
    const val NONCE_BYTES: Int = 16
    const val GCM_TAG_BITS: Int = 128
    const val GCM_TAG_BYTES: Int = GCM_TAG_BITS / 8
    const val PIN_LENGTH: Int = 6
    const val PIN_PBKDF2_ITERATIONS: Int = 100_000
    const val MAX_EC_PUBLIC_KEY_BYTES: Int = 256
    private const val KDF_ALGORITHM: String = "PBKDF2WithHmacSHA1"
    private const val HMAC_ALGORITHM: String = "HmacSHA256"
    private const val CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"

    private val INFO_SESSION_KEY = "raven-lan-sync-enc-v1".toByteArray(Charsets.UTF_8)
    private val INFO_CLIENT_AUTH = "raven-lan-sync-cauth-v1".toByteArray(Charsets.UTF_8)
    private val INFO_SERVER_AUTH = "raven-lan-sync-sauth-v1".toByteArray(Charsets.UTF_8)
    private val INFO_TRUST_SECRET = "raven-lan-sync-trust-v1".toByteArray(Charsets.UTF_8)

    fun generateEphemeralKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), random)
        return kpg.generateKeyPair()
    }

    fun encodePublicKey(publicKey: PublicKey): ByteArray = publicKey.encoded

    fun decodePublicKey(encoded: ByteArray): PublicKey {
        require(encoded.isNotEmpty() && encoded.size <= MAX_EC_PUBLIC_KEY_BYTES) {
            "无效的 ECDH 公钥长度"
        }
        val factory = KeyFactory.getInstance("EC")
        return factory.generatePublic(X509EncodedKeySpec(encoded))
    }

    fun computeEcdhSharedSecret(privateKey: PrivateKey, peerPublicKeyBytes: ByteArray): ByteArray {
        val peerPublicKey = decodePublicKey(peerPublicKeyBytes)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    fun derivePinAuthSecret(
        pin: CharArray,
        salt: ByteArray,
        iterations: Int = PIN_PBKDF2_ITERATIONS,
    ): ByteArray {
        require(pin.size == PIN_LENGTH && pin.all { it in '0'..'9' }) {
            "配对码必须为 $PIN_LENGTH 位数字"
        }
        require(iterations >= 1) { "PBKDF2 iterations must be positive" }
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun computePinSalt(
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        serverDeviceId: String,
    ): ByteArray = sha256(
        "raven-lan-pin-salt-v1".toByteArray(Charsets.UTF_8),
        clientNonce,
        serverNonce,
        serverDeviceId.toByteArray(Charsets.UTF_8),
    )

    fun deriveHandshakeKeys(
        ecdhSecret: ByteArray,
        authSecretBytes: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        clientPublicKey: ByteArray,
        serverPublicKey: ByteArray,
        transcriptHash: ByteArray,
    ): LanSyncSessionKeys {
        require(authSecretBytes.size == KEY_BYTES) { "Auth secret must be $KEY_BYTES bytes" }
        val salt = sha256(clientNonce, serverNonce, clientPublicKey, serverPublicKey)
        val ikm = ByteArray(ecdhSecret.size + authSecretBytes.size)
        val prk: ByteArray
        try {
            System.arraycopy(ecdhSecret, 0, ikm, 0, ecdhSecret.size)
            System.arraycopy(authSecretBytes, 0, ikm, ecdhSecret.size, authSecretBytes.size)
            prk = hmacSha256(key = salt, data = ikm)
        } finally {
            ikm.fill(0)
        }

        return try {
            val sessionKey = hmacSha256(prk, INFO_SESSION_KEY, byteArrayOf(0x01))
            val clientAuthTag = hmacSha256(prk, INFO_CLIENT_AUTH, transcriptHash)
            val serverAuthTag = hmacSha256(prk, INFO_SERVER_AUTH, transcriptHash)
            val trustedSharedSecret = hmacSha256(prk, INFO_TRUST_SECRET, byteArrayOf(0x01))
            LanSyncSessionKeys(
                sessionKey = sessionKey,
                clientAuthTag = clientAuthTag,
                serverAuthTag = serverAuthTag,
                trustedSharedSecret = trustedSharedSecret,
            )
        } finally {
            prk.fill(0)
            salt.fill(0)
        }
    }

    fun computeTranscriptHash(
        clientDeviceId: String,
        serverDeviceId: String,
        syncMode: String,
        authMode: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        clientPublicKey: ByteArray,
        serverPublicKey: ByteArray,
    ): ByteArray = sha256(
        clientDeviceId.toByteArray(Charsets.UTF_8),
        byteArrayOf(0),
        serverDeviceId.toByteArray(Charsets.UTF_8),
        byteArrayOf(0),
        syncMode.toByteArray(Charsets.UTF_8),
        byteArrayOf(0),
        authMode.toByteArray(Charsets.UTF_8),
        byteArrayOf(0),
        clientNonce,
        serverNonce,
        clientPublicKey,
        serverPublicKey,
    )

    fun encryptFrame(
        sessionKey: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(sessionKey.size == KEY_BYTES) { "Session key must be $KEY_BYTES bytes" }
        require(iv.size == IV_BYTES) { "GCM IV must be $IV_BYTES bytes" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun decryptFrame(
        sessionKey: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(sessionKey.size == KEY_BYTES) { "Session key must be $KEY_BYTES bytes" }
        require(iv.size == IV_BYTES) { "GCM IV must be $IV_BYTES bytes" }
        require(ciphertext.size >= GCM_TAG_BYTES) { "密文长度不足" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun randomBytes(length: Int, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(length).also(random::nextBytes)

    fun generatePairingPin(random: SecureRandom = SecureRandom()): String {
        val code = random.nextInt(1_000_000)
        return String.format(Locale.US, "%06d", code)
    }

    fun generateDeviceId(random: SecureRandom = SecureRandom()): String {
        val raw = randomBytes(8, random)
        return raw.joinToString(separator = "") { b -> String.format(Locale.US, "%02x", b.toInt() and 0xFF) }
    }

    fun deviceFingerprint(deviceId: String): String {
        val digest = sha256(deviceId.trim().toByteArray(Charsets.UTF_8))
        val part1 = String.format(Locale.US, "%02X%02X", digest[0].toInt() and 0xFF, digest[1].toInt() and 0xFF)
        val part2 = String.format(Locale.US, "%02X%02X", digest[2].toInt() and 0xFF, digest[3].toInt() and 0xFF)
        return "$part1-$part2"
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun encodeBase64(bytes: ByteArray): String = Base64Codec.encode(bytes)

    fun decodeBase64(encoded: String): ByteArray = Base64Codec.decode(encoded)

    fun sha256(vararg chunks: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        chunks.forEach(md::update)
        return md.digest()
    }

    fun gzipCompress(plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(plaintext)
        }
        return out.toByteArray()
    }

    fun gzipDecompressBounded(compressed: ByteArray, maxPlainBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
            var total = 0
            while (true) {
                val read = gzip.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxPlainBytes) {
                    throw IOException("解压后的同步快照超过大小限制 ($maxPlainBytes 字节)")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }
}
