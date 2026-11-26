package com.raven.app.core.sitespi

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin AES-CBC image decryption protocol for Manwa2 (`manwa2`), shared between
 * `:sites:comic` and `:core:network`/`:core:media` via [MediaTransportHook].
 *
 * Ciphertext layout for modern Manwa2 CDN images (`/en_images/...`):
 * - First 16 bytes: AES-CBC IV
 * - Remaining bytes: PKCS#5-padded AES-256 ciphertext using UTF-8 `aesKey.copyOf(32)`
 *
 * Legacy `?v=20220724` images use AES-128-CBC/NoPadding with `LEGACY_2022_KEY` as both key and IV.
 * Decryption is strictly idempotent: plain JPEG/PNG/GIF/WebP payloads are left untouched.
 */
object ManwaImageAesProtocol {

    const val DEFAULT_AES_KEY: String = "0B6666A0-BB59-1381-B746-a0E4C9AC"
    const val LEGACY_2022_KEY: String = "my2ecret782ecret"

    private const val IV_SIZE = 16
    private const val AES_256_KEY_BYTES = 32
    private val AES_KEY_REGEX = Regex("""AES_KEY\s*:\s*['"]([^'"]+)['"]""")

    fun isPlainImageBytes(bytes: ByteArray): Boolean = detectImageMime(bytes) != null

    fun detectImageMime(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte()
        ) {
            return "image/gif"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    fun decryptImage(bytes: ByteArray, aesKey: String = DEFAULT_AES_KEY): ByteArray? {
        if (bytes.size <= IV_SIZE || isPlainImageBytes(bytes)) return null
        val normalizedKey = aesKey.trim().ifEmpty { DEFAULT_AES_KEY }
        return runCatching {
            val keyBytes = normalizedKey.toByteArray(Charsets.UTF_8).copyOf(AES_256_KEY_BYTES)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val cipherText = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(cipherText)
            plain.takeIf(::isPlainImageBytes)
        }.getOrNull()
    }

    fun decryptLegacy2022(bytes: ByteArray): ByteArray? {
        if (bytes.size < 16 || bytes.size % 16 != 0 || isPlainImageBytes(bytes)) return null
        return runCatching {
            val keyBytes = LEGACY_2022_KEY.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes))
            val plain = cipher.doFinal(bytes)
            plain.takeIf(::isPlainImageBytes)
        }.getOrNull()
    }

    fun encryptForTest(
        plain: ByteArray,
        aesKey: String = DEFAULT_AES_KEY,
        iv: ByteArray = ByteArray(IV_SIZE) { (it + 1).toByte() },
    ): ByteArray {
        require(iv.size == IV_SIZE) { "IV must be $IV_SIZE bytes" }
        val keyBytes = aesKey.toByteArray(Charsets.UTF_8).copyOf(AES_256_KEY_BYTES)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain)
        return iv + encrypted
    }

    fun extractAesKeyFromJs(js: String): String? =
        AES_KEY_REGEX.find(js)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun createTransportHook(
        keyProvider: () -> String,
        urlMatcher: (String) -> Boolean,
    ): MediaTransportHook = object : MediaTransportHook {
        override fun requiresFullResponseUnwrap(url: String): Boolean = urlMatcher(url)

        override fun unwrapMediaBytes(url: String, bytes: ByteArray): UnwrappedMedia? {
            if (isPlainImageBytes(bytes)) return null
            val plain = if (url.contains("v=20220724", ignoreCase = true)) {
                decryptLegacy2022(bytes) ?: decryptImage(bytes, keyProvider())
            } else {
                decryptImage(bytes, keyProvider())
            } ?: return null
            val mime = detectImageMime(plain) ?: "image/webp"
            return UnwrappedMedia(bytes = plain, contentType = mime)
        }
    }
}
