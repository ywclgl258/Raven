package com.raven.app.core.sitespi

import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

data class CossoraEmbed(
    val id: String,
    val embedUrl: String,
)

data class CossoraMedia(
    val streamUrl: String,
    val qualityLabel: String,
    val posterUrl: String,
)

/**
 * Pure-Kotlin AES-256-CBC decoder for Cosplaytele embedded HLS videos (`cossora.stream`).
 * Payload format: Base64(iv[16] + ciphertext[16*N]), key = UTF-8 bytes of 32-char hex passphrase (32 bytes = AES-256).
 */
object CossoraAesProtocol {
    private const val MAX_PAYLOAD_LENGTH = 4096

    private val iframeSrcRegex = Regex(
        """<iframe\b[^>]*\bsrc\s*=\s*['"](https://(?:www\.)?cossora\.stream/embed/([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})/?)['"]""",
        RegexOption.IGNORE_CASE,
    )
    private val encryptedVideoRegex = Regex("""const\s+videoURL\s*=\s*['"]([^'"]+)['"]""")
    private val encryptedPosterRegex = Regex("""const\s+portadaURL\s*=\s*['"]([^'"]+)['"]""")
    private val keyRegex = Regex("""decryptLink\(\s*videoURL\s*,\s*['"]([a-fA-F0-9]{32})['"]\s*\)""")
    private val qualityRegex = Regex("""defaultQuality\s*:\s*['"](\d{3,4}p)['"]""")

    fun extractEmbeds(html: String): List<CossoraEmbed> =
        iframeSrcRegex.findAll(html).map { match ->
            val uuid = match.groupValues[2].lowercase()
            CossoraEmbed(
                id = "video-$uuid",
                embedUrl = "https://cossora.stream/embed/$uuid",
            )
        }.distinctBy(CossoraEmbed::id).toList()

    fun parseEmbedHtml(html: String): CossoraMedia {
        val payload = encryptedVideoRegex.find(html)?.groupValues?.get(1)
            ?.takeIf { it.length <= MAX_PAYLOAD_LENGTH }
            ?: throw SiteException.ParseError("Cossora embed page missing encrypted videoURL", SiteId.COSPLAYTELE)
        val passphrase = keyRegex.find(html)?.groupValues?.get(1)
            ?: throw SiteException.ParseError("Cossora embed page missing 32-char decrypt key", SiteId.COSPLAYTELE)

        val streamUrl = validateTrustedMediaUrl(decrypt(payload, passphrase))
        val pathOnly = streamUrl.substringBefore('?').substringBefore('#')
        if (!pathOnly.endsWith(".m3u8", ignoreCase = true)) {
            throw SiteException.ParseError("Cossora decrypted stream is not an HLS manifest", SiteId.COSPLAYTELE)
        }

        val posterUrl = encryptedPosterRegex.find(html)?.groupValues?.get(1)
            ?.takeIf { it.length <= MAX_PAYLOAD_LENGTH }
            ?.let { rawPoster -> validateTrustedMediaUrl(decrypt(rawPoster, passphrase)) }
            .orEmpty()

        val quality = qualityRegex.find(html)?.groupValues?.get(1) ?: "HLS"
        return CossoraMedia(
            streamUrl = streamUrl,
            qualityLabel = quality,
            posterUrl = posterUrl,
        )
    }

    /**
     * Decrypts a Base64-encoded `iv(16B) || ciphertext` payload using AES-CBC/PKCS5Padding
     * with the raw UTF-8 bytes of [passphrase] (16, 24, or 32 bytes).
     */
    fun decrypt(payloadBase64: String, passphrase: String): String {
        val bytes = runCatching { Base64.decode(payloadBase64.trim()) }.getOrElse {
            throw SiteException.ParseError("Cossora payload is not valid Base64", SiteId.COSPLAYTELE, it)
        }
        if (bytes.size < 32 || bytes.size % 16 != 0) {
            throw SiteException.ParseError("Cossora payload length invalid: ${bytes.size}", SiteId.COSPLAYTELE)
        }
        val secret = passphrase.toByteArray(Charsets.UTF_8)
        if (secret.size !in setOf(16, 24, 32)) {
            throw SiteException.ParseError("Cossora AES key length invalid: ${secret.size}", SiteId.COSPLAYTELE)
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(secret, "AES"),
                IvParameterSpec(bytes.copyOfRange(0, 16)),
            )
            String(cipher.doFinal(bytes.copyOfRange(16, bytes.size)), Charsets.UTF_8)
        }.getOrElse {
            throw SiteException.ParseError("Failed to decrypt Cossora AES payload", SiteId.COSPLAYTELE, it)
        }
    }

    /**
     * Deterministic AES-CBC encrypt helper for unit tests and protocol round-trip checks.
     */
    fun encrypt(plaintext: String, passphrase: String, iv: ByteArray): String {
        require(iv.size == 16) { "IV must be 16 bytes" }
        val secret = passphrase.toByteArray(Charsets.UTF_8)
        require(secret.size in setOf(16, 24, 32)) { "Key length must be 16, 24, or 32 bytes" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(16 + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, 16)
        System.arraycopy(encrypted, 0, combined, 16, encrypted.size)
        return Base64.encode(combined)
    }

    private fun validateTrustedMediaUrl(raw: String): String {
        if (!UrlSanitizer.isHttpsUrl(raw)) {
            throw SiteException.ParseError("Cossora decrypted URL is not safe HTTPS", SiteId.COSPLAYTELE)
        }
        val host = UrlSanitizer.extractHost(raw)
            ?: throw SiteException.ParseError("Cossora decrypted URL missing host", SiteId.COSPLAYTELE)
        if (!UrlSanitizer.hostMatchesDomain(host, "cossora.stream")) {
            throw SiteException.ParseError("Cossora decrypted URL points to untrusted host: $host", SiteId.COSPLAYTELE)
        }
        return raw
    }
}
