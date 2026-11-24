package com.raven.app.core.security

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Thrown whenever credential vault encryption or authenticated decryption fails.
 * Never silently falls back to plaintext storage.
 */
class SessionVaultException(
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)

/**
 * Authenticated symmetric cipher abstraction for [SessionVault].
 * Wire format: `iv[12] || aesGcmCiphertext || gcmTag[16]` (AES-256-GCM, 128-bit tag).
 */
interface VaultCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray

    companion object {
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES: Int = 12
        const val TAG_LENGTH_BITS: Int = 128
        const val TAG_LENGTH_BYTES: Int = TAG_LENGTH_BITS / 8
        const val MIN_CIPHERTEXT_BYTES: Int = IV_LENGTH_BYTES + TAG_LENGTH_BYTES
    }
}

/**
 * Hardware/OS-backed `AndroidKeyStore` AES-256-GCM cipher with zero plaintext fallback.
 * Relies on AndroidKeyStore's randomized IV generation (12-byte IV, 128-bit GCM auth tag).
 */
class AndroidKeyStoreVaultCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : VaultCipher {
    private val keyLock = Any()

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (e: Exception) {
            throw SessionVaultException("Failed to initialize AndroidKeyStore AES-256-GCM key", e)
        }
    }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(VaultCipher.TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            if (iv == null || iv.size != VaultCipher.IV_LENGTH_BYTES) {
                throw SessionVaultException("AndroidKeyStore returned invalid GCM IV length: ${iv?.size}")
            }
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            val encrypted = cipher.doFinal(plaintext)
            return iv + encrypted
        } catch (e: SessionVaultException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw SessionVaultException("AndroidKeyStore AES-256-GCM encryption failed", e)
        } catch (e: Exception) {
            throw SessionVaultException("Unexpected error during vault encryption", e)
        }
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        if (ciphertext.size < VaultCipher.MIN_CIPHERTEXT_BYTES) {
            throw SessionVaultException("Vault ciphertext is truncated or malformed")
        }
        try {
            val iv = ciphertext.copyOfRange(0, VaultCipher.IV_LENGTH_BYTES)
            val cipher = Cipher.getInstance(VaultCipher.TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(VaultCipher.TAG_LENGTH_BITS, iv),
            )
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            return cipher.doFinal(ciphertext, VaultCipher.IV_LENGTH_BYTES, ciphertext.size - VaultCipher.IV_LENGTH_BYTES)
        } catch (e: GeneralSecurityException) {
            throw SessionVaultException("Vault data authentication failed (tampered or key mismatch)", e)
        } catch (e: Exception) {
            throw SessionVaultException("Unexpected error during vault decryption", e)
        }
    }

    companion object {
        const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS: String = "raven_session_vault_aes256_v1"
        private const val KEY_SIZE_BITS: Int = 256
    }
}

/**
 * Pure-JVM AES-256-GCM [VaultCipher] implementation using real JCA `AES/GCM/NoPadding`
 * (fresh 12-byte SecureRandom IV + 128-bit GCM auth tag + AAD).
 * Used in JVM unit tests to exercise real authenticated encryption and tamper rejection.
 */
class SoftwareAesGcmVaultCipher(
    private val secretKey: SecretKey = generateAes256Key(),
    private val random: SecureRandom = SecureRandom(),
) : VaultCipher {

    init {
        val keyBytes = secretKey.encoded
        require(keyBytes != null && keyBytes.size == 32) {
            "SoftwareAesGcmVaultCipher requires a 256-bit (32-byte) AES key"
        }
    }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        try {
            val iv = ByteArray(VaultCipher.IV_LENGTH_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(VaultCipher.TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                secretKey,
                GCMParameterSpec(VaultCipher.TAG_LENGTH_BITS, iv),
            )
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            val encrypted = cipher.doFinal(plaintext)
            return iv + encrypted
        } catch (e: GeneralSecurityException) {
            throw SessionVaultException("AES-256-GCM encryption failed", e)
        }
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        if (ciphertext.size < VaultCipher.MIN_CIPHERTEXT_BYTES) {
            throw SessionVaultException("Vault ciphertext is truncated or malformed")
        }
        try {
            val iv = ciphertext.copyOfRange(0, VaultCipher.IV_LENGTH_BYTES)
            val cipher = Cipher.getInstance(VaultCipher.TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(VaultCipher.TAG_LENGTH_BITS, iv),
            )
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            return cipher.doFinal(ciphertext, VaultCipher.IV_LENGTH_BYTES, ciphertext.size - VaultCipher.IV_LENGTH_BYTES)
        } catch (e: GeneralSecurityException) {
            throw SessionVaultException("Vault data authentication failed (tampered or key mismatch)", e)
        }
    }

    companion object {
        fun generateAes256Key(): SecretKey = KeyGenerator.getInstance("AES").apply {
            init(256)
        }.generateKey()
    }
}

/**
 * Raw ciphertext key-value persistence backing [SessionVault].
 * Implementations ONLY ever receive Base64-encoded AES-256-GCM ciphertext values.
 */
interface VaultStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keysWithPrefix(prefix: String): Set<String>
    fun clear()
}

/**
 * Android [SharedPreferences]-backed [VaultStorage].
 * Uses non-blocking `apply()` (in-memory atomic update + async disk flush).
 */
class SharedPreferencesVaultStorage(
    private val prefs: SharedPreferences,
) : VaultStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keysWithPrefix(prefix: String): Set<String> =
        prefs.all.keys.filterTo(LinkedHashSet()) { it.startsWith(prefix) }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * Pure-JVM in-memory [VaultStorage] storing raw encrypted blobs for deterministic unit testing.
 */
class InMemoryVaultStorage : VaultStorage {
    private val lock = Any()
    private val entries = LinkedHashMap<String, String>()

    override fun getString(key: String): String? = synchronized(lock) { entries[key] }

    override fun putString(key: String, value: String) {
        synchronized(lock) { entries[key] = value }
    }

    override fun remove(key: String) {
        synchronized(lock) { entries.remove(key) }
    }

    override fun keysWithPrefix(prefix: String): Set<String> = synchronized(lock) {
        entries.keys.filterTo(LinkedHashSet()) { it.startsWith(prefix) }
    }

    override fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun snapshotRawEntries(): Map<String, String> = synchronized(lock) { entries.toMap() }
}
