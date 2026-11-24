package com.raven.app.core.security

import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class BackupFormat {
    RAVEN_V1,
    LEGACY_R34V_V1,
}

data class DecodedBackup(
    val format: BackupFormat,
    val payload: RavenBackupPayload,
    val summary: BackupSummary,
)

/**
 * Thrown when encrypted backup encoding or decoding fails (weak password, invalid magic,
 * wrong password, tampered header/ciphertext, or oversized payload).
 */
class BackupCodecException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Password-based authenticated encrypted backup codec strictly matching `ARCHITECTURE.md` §7.2:
 *
 * - **Native Raven export & import (`RVNBK01`)**:
 *   `magic[8] ("RVNBK01\u0000")` + `salt[16]` + `iv[12]` (36-byte header used as GCM AAD) +
 *   `AES/GCM/NoPadding` (256-bit key, 128-bit tag) derived via
 *   `PBKDF2WithHmacSHA1(password, salt, 600_000, 256)`.
 * - **Backward-compatible R34V import (`R34VBK01`)**:
 *   Detects 8-byte ASCII magic `"R34VBK01"`, decrypts with identical 36-byte header AAD +
 *   `PBKDF2WithHmacSHA1(600_000, 256)` + `AES/GCM/NoPadding`, and converts legacy R34V JSON
 *   losslessly into [RavenBackupPayload].
 */
class BackupCodec(
    private val validator: BackupValidator,
    private val siteRegistryProvider: () -> SiteRegistry,
    private val random: SecureRandom = SecureRandom(),
    internal val pbkdf2Iterations: Int = PBKDF2_ITERATIONS,
) {
    constructor(
        validator: BackupValidator,
        siteRegistry: SiteRegistry,
    ) : this(
        validator = validator,
        siteRegistryProvider = { siteRegistry },
    )

    private val nativeJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Inspects the 8-byte file magic without decrypting. Returns `null` for unrecognized formats.
     */
    fun detectFormat(bytes: ByteArray): BackupFormat? {
        if (bytes.size < MAGIC_BYTES) return null
        val prefix = bytes.copyOfRange(0, MAGIC_BYTES)
        return when {
            prefix.contentEquals(RAVEN_MAGIC_BYTES) -> BackupFormat.RAVEN_V1
            prefix.contentEquals(LEGACY_R34V_MAGIC_BYTES) -> BackupFormat.LEGACY_R34V_V1
            else -> null
        }
    }

    /**
     * Validates and encrypts [payload] into the native Raven `RVNBK01\u0000` wire format.
     */
    fun encode(
        payload: RavenBackupPayload,
        password: CharArray,
        registryOverride: SiteRegistry? = null,
    ): ByteArray {
        if (password.size < MIN_PASSWORD_LENGTH) {
            throw BackupCodecException("备份密码至少需要 $MIN_PASSWORD_LENGTH 个字符")
        }
        validator.validate(payload, registryOverride)
        val plain = nativeJson.encodeToString(payload).toByteArray(Charsets.UTF_8)
        if (plain.size > RavenBackupPayload.MAX_PLAINTEXT_BYTES) {
            plain.fill(0)
            throw BackupCodecException("备份数据超过 16 MiB 上限")
        }
        return encryptWireBytes(RAVEN_MAGIC_BYTES, plain, password)
    }

    /**
     * Auto-detects `RVNBK01\u0000` or legacy `R34VBK01`, authenticates & decrypts,
     * decodes into [RavenBackupPayload], validates against [SiteRegistry], and returns
     * [DecodedBackup] with its [BackupSummary] preview.
     */
    fun decode(
        bytes: ByteArray,
        password: CharArray,
        registryOverride: SiteRegistry? = null,
    ): DecodedBackup {
        val minSize = HEADER_BYTES + GCM_TAG_BYTES
        val maxSize = RavenBackupPayload.MAX_PLAINTEXT_BYTES + HEADER_BYTES + GCM_TAG_BYTES
        if (bytes.size !in minSize..maxSize) {
            throw BackupCodecException("不是受支持的 Raven / R34V 备份文件")
        }
        val format = detectFormat(bytes)
            ?: throw BackupCodecException("不是受支持的 Raven / R34V 备份文件")
        if (password.isEmpty()) {
            throw BackupCodecException("请输入备份密码")
        }

        val salt = bytes.copyOfRange(MAGIC_BYTES, MAGIC_BYTES + SALT_BYTES)
        val iv = bytes.copyOfRange(MAGIC_BYTES + SALT_BYTES, HEADER_BYTES)
        val header = bytes.copyOfRange(0, HEADER_BYTES)
        val derived = deriveKey(password, salt)
        val plain = try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derived, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(header)
            cipher.doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
        } catch (e: GeneralSecurityException) {
            throw BackupCodecException("密码不正确或备份文件已损坏", e)
        } finally {
            derived.fill(0)
        }

        return try {
            if (plain.size > RavenBackupPayload.MAX_PLAINTEXT_BYTES) {
                throw BackupCodecException("备份明文超过 16 MiB 上限")
            }
            val jsonText = String(plain, Charsets.UTF_8)
            val registry = registryOverride ?: siteRegistryProvider()
            val payload = when (format) {
                BackupFormat.RAVEN_V1 -> try {
                    nativeJson.decodeFromString<RavenBackupPayload>(jsonText)
                } catch (e: Exception) {
                    throw BackupValidationException("Raven 备份 JSON 格式无效", e)
                }
                BackupFormat.LEGACY_R34V_V1 -> LegacyR34vBackupAdapter.decodeToRavenPayload(jsonText, registry)
            }
            val summary = validator.validate(payload, registry)
            DecodedBackup(format = format, payload = payload, summary = summary)
        } finally {
            plain.fill(0)
        }
    }

    internal fun encryptWireBytes(magic: ByteArray, plaintext: ByteArray, password: CharArray): ByteArray {
        require(magic.size == MAGIC_BYTES) { "Backup magic must be exactly $MAGIC_BYTES bytes" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = ByteArray(HEADER_BYTES)
        System.arraycopy(magic, 0, header, 0, MAGIC_BYTES)
        System.arraycopy(salt, 0, header, MAGIC_BYTES, SALT_BYTES)
        System.arraycopy(iv, 0, header, MAGIC_BYTES + SALT_BYTES, IV_BYTES)

        val derived = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derived, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(header)
            header + cipher.doFinal(plaintext)
        } finally {
            derived.fill(0)
            plaintext.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, pbkdf2Iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH: Int = 8
        const val PBKDF2_ITERATIONS: Int = 600_000
        const val KEY_BITS: Int = 256
        const val MAGIC_BYTES: Int = 8
        const val SALT_BYTES: Int = 16
        const val IV_BYTES: Int = 12
        const val HEADER_BYTES: Int = MAGIC_BYTES + SALT_BYTES + IV_BYTES // 36
        const val GCM_TAG_BITS: Int = 128
        const val GCM_TAG_BYTES: Int = GCM_TAG_BITS / 8

        const val KDF_ALGORITHM: String = "PBKDF2WithHmacSHA1"
        const val CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"

        /** Exact 8-byte native magic: `"RVNBK01\u0000"` (`52 56 4E 42 4B 30 31 00`). */
        val RAVEN_MAGIC_BYTES: ByteArray = byteArrayOf(
            0x52, 0x56, 0x4E, 0x42, 0x4B, 0x30, 0x31, 0x00,
        )

        /** Exact 8-byte legacy R34V magic: `"R34VBK01"` (`52 33 34 56 42 4B 30 31`). */
        val LEGACY_R34V_MAGIC_BYTES: ByteArray = byteArrayOf(
            0x52, 0x33, 0x34, 0x56, 0x42, 0x4B, 0x30, 0x31,
        )
    }
}
