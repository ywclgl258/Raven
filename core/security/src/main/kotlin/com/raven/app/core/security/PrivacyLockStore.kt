package com.raven.app.core.security

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persisted PIN failure state so force-stopping the process cannot reset the lockout. */
data class PinAttemptState(
    val failedAttempts: Int = 0,
    val lockoutUntilEpochMs: Long = 0L,
)

/**
 * Encrypted local PIN lock credential store backed by [VaultCipher] (AndroidKeyStore AES-256-GCM).
 *
 * Stores salted PBKDF2-HMAC-SHA1 digests only — never plaintext PINs and never included in backups
 * or [SessionVault.clearAll].
 */
class PrivacyLockStore(
    private val cipher: VaultCipher,
    private val storage: VaultStorage,
    private val random: SecureRandom = SecureRandom(),
) {
    constructor(
        context: Context,
        prefsName: String = DEFAULT_PREFS_NAME,
    ) : this(
        cipher = AndroidKeyStoreVaultCipher(),
        storage = SharedPreferencesVaultStorage(
            context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        ),
    )

    @Serializable
    private data class PinRecord(
        val saltBase64: String,
        val hashBase64: String,
        val updatedAt: Long,
    )

    @Serializable
    private data class AttemptRecord(
        val failedAttempts: Int,
        val lockoutUntilEpochMs: Long,
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun hasPin(): Boolean = synchronized(lock) {
        storage.getString(KEY_PIN_RECORD) != null
    }

    fun setPin(pin: String, nowMs: Long = System.currentTimeMillis()) {
        require(isValidPinFormat(pin)) { "PIN 必须为 4 到 8 位数字" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = deriveHash(pin, salt)
        try {
            val record = PinRecord(
                saltBase64 = Base64Codec.encode(salt),
                hashBase64 = Base64Codec.encode(digest),
                updatedAt = nowMs,
            )
            val plain = json.encodeToString(record).toByteArray(Charsets.UTF_8)
            try {
                val sealed = cipher.encrypt(plain, AAD_BYTES)
                synchronized(lock) {
                    storage.putString(KEY_PIN_RECORD, Base64Codec.encode(sealed))
                }
            } finally {
                plain.fill(0)
            }
        } finally {
            salt.fill(0)
            digest.fill(0)
        }
        clearAttempts()
    }

    fun verifyPin(pin: String): Boolean {
        if (!isValidPinFormat(pin)) return false
        val encoded = synchronized(lock) { storage.getString(KEY_PIN_RECORD) } ?: return false
        return runCatching {
            val sealed = Base64Codec.decode(encoded)
            val plain = cipher.decrypt(sealed, AAD_BYTES)
            val record = try {
                json.decodeFromString<PinRecord>(String(plain, Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
            val salt = Base64Codec.decode(record.saltBase64)
            val expected = Base64Codec.decode(record.hashBase64)
            val actual = deriveHash(pin, salt)
            try {
                MessageDigest.isEqual(expected, actual)
            } finally {
                salt.fill(0)
                expected.fill(0)
                actual.fill(0)
            }
        }.getOrDefault(false)
    }

    fun clearPin() {
        synchronized(lock) {
            storage.remove(KEY_PIN_RECORD)
        }
        clearAttempts()
    }

    /**
     * Reads the persisted failure state. An expired lockout is cleared so the next attempt starts
     * from zero, matching [AppLockSessionCoordinator.onLockoutExpired].
     */
    fun attemptState(nowMs: Long = System.currentTimeMillis()): PinAttemptState {
        val encoded = synchronized(lock) { storage.getString(KEY_ATTEMPT_RECORD) } ?: return PinAttemptState()
        val record = runCatching {
            val sealed = Base64Codec.decode(encoded)
            val plain = cipher.decrypt(sealed, ATTEMPT_AAD_BYTES)
            try {
                json.decodeFromString<AttemptRecord>(String(plain, Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
        }.getOrElse {
            clearAttempts()
            return PinAttemptState()
        }
        if (record.lockoutUntilEpochMs in 1..nowMs) {
            clearAttempts()
            return PinAttemptState()
        }
        return PinAttemptState(
            failedAttempts = record.failedAttempts.coerceAtLeast(0),
            lockoutUntilEpochMs = record.lockoutUntilEpochMs.coerceAtLeast(0L),
        )
    }

    fun registerFailedAttempt(
        nowMs: Long = System.currentTimeMillis(),
        maxAttempts: Int,
        lockoutDurationMs: Long,
    ): PinAttemptState = synchronized(lock) {
        val current = attemptState(nowMs)
        val nextAttempts = current.failedAttempts + 1
        val lockoutUntil = if (nextAttempts >= maxAttempts) {
            nowMs + lockoutDurationMs.coerceAtLeast(0L)
        } else {
            0L
        }
        val record = AttemptRecord(
            failedAttempts = nextAttempts,
            lockoutUntilEpochMs = lockoutUntil,
        )
        val plain = json.encodeToString(AttemptRecord.serializer(), record).toByteArray(Charsets.UTF_8)
        try {
            val sealed = cipher.encrypt(plain, ATTEMPT_AAD_BYTES)
            storage.putString(KEY_ATTEMPT_RECORD, Base64Codec.encode(sealed))
        } finally {
            plain.fill(0)
        }
        PinAttemptState(nextAttempts, lockoutUntil)
    }

    fun clearAttempts() {
        synchronized(lock) {
            storage.remove(KEY_ATTEMPT_RECORD)
        }
    }

    private fun deriveHash(pin: String, salt: ByteArray): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_PREFS_NAME: String = "raven_privacy_lock_v1"
        private const val KEY_PIN_RECORD: String = "privacy_pin:v1"
        private val AAD_BYTES: ByteArray = "raven-privacy-lock:v1".toByteArray(Charsets.UTF_8)
        private const val KEY_ATTEMPT_RECORD: String = "privacy_attempts:v1"
        private val ATTEMPT_AAD_BYTES: ByteArray = "raven-privacy-lock-attempts:v1".toByteArray(Charsets.UTF_8)
        private const val KDF_ALGORITHM: String = "PBKDF2WithHmacSHA1"
        private const val PBKDF2_ITERATIONS: Int = 100_000
        private const val SALT_BYTES: Int = 16
        private const val HASH_BITS: Int = 256

        fun isValidPinFormat(pin: String): Boolean =
            pin.length in 4..8 && pin.all { it.isDigit() }
    }
}
