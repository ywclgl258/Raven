package com.raven.app.core.sitespi

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.io.encoding.Base64

/**
 * Pure-Kotlin `rou.video` protocol utilities:
 * 1. PNG `roUd` (`0x726f5564`) chunk + zlib (`Inflater(nowrap = false)`) unwrapper for HLS manifests & TS segments.
 * 2. Detail-page `ev` payload decoder (`Base64` + per-byte offset subtraction).
 * 3. [MediaTransportHook] implementation (`RouMediaTransportHook`) so `:core:media` stays site-agnostic.
 */
object RouPngProtocol {
    val PNG_SIGNATURE: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    const val CHUNK_TYPE_ROUD: Int = 0x726f5564
    const val FLAG_DEFLATED: Int = 1
    const val MAX_UNWRAPPED_BYTES: Int = 64 * 1024 * 1024

    private val rouHlsPathRegex = Regex("^https?://[^/]+/api/hls/", RegexOption.IGNORE_CASE)
    private val evExactRegex = Regex("""ev:\s*\${'$'}R\[\d+\]\s*=\s*\{d:"([^"]+)",k:(\d+)\}""")
    private val evFallbackRegex = Regex("""\{d:"([A-Za-z0-9+/=]{40,})",k:(\d+)\}""")

    fun isRouHlsUrl(url: String): Boolean =
        rouHlsPathRegex.containsMatchIn(url) ||
            url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

    fun isWrapped(prefix: ByteArray): Boolean =
        prefix.size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { prefix[it] == PNG_SIGNATURE[it] }

    /**
     * Unwraps the `roUd` custom chunk from a PNG container.
     * Returns `null` if [body] is not a PNG or does not contain a valid `roUd` chunk.
     */
    fun unwrap(body: ByteArray): ByteArray? {
        if (!isWrapped(body)) return null
        var index = PNG_SIGNATURE.size
        while (index + 8 <= body.size) {
            val length = readIntBigEndian(body, index)
            val type = readIntBigEndian(body, index + 4)
            val dataStart = index + 8
            if (length < 0 || length > body.size - dataStart) return null
            if (type == CHUNK_TYPE_ROUD && length > 1) {
                val flag = body[dataStart].toInt() and 0xFF
                val payload = body.copyOfRange(dataStart + 1, dataStart + length)
                return if (flag and FLAG_DEFLATED != 0) inflateZlib(payload) else payload
            }
            // Skip payload + 4-byte CRC
            val next = dataStart.toLong() + length.toLong() + 4L
            if (next > body.size.toLong()) break
            index = next.toInt()
        }
        return null
    }

    /**
     * Wraps [payload] into a valid minimal PNG carrying a `roUd` chunk (useful for round-trip tests).
     */
    fun wrap(payload: ByteArray, deflate: Boolean = true): ByteArray {
        val inner = if (deflate) deflateZlib(payload) else payload
        val chunkData = ByteArray(1 + inner.size)
        chunkData[0] = if (deflate) FLAG_DEFLATED.toByte() else 0
        System.arraycopy(inner, 0, chunkData, 1, inner.size)

        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        // Minimal 1x1 IHDR chunk
        val ihdr = byteArrayOf(
            0, 0, 0, 1, // width = 1
            0, 0, 0, 1, // height = 1
            8, 2, 0, 0, 0, // bitDepth=8, RGB, deflate, filter=0, interlace=0
        )
        writeChunk(out, 0x49484452, ihdr) // IHDR
        writeChunk(out, CHUNK_TYPE_ROUD, chunkData)
        writeChunk(out, 0x49454E44, ByteArray(0)) // IEND
        return out.toByteArray()
    }

    /**
     * Sniffs unwrapped media bytes to synthesize accurate HTTP Content-Type for Media3.
     */
    fun detectContentType(inner: ByteArray): String {
        val head = String(inner, 0, minOf(16, inner.size), Charsets.US_ASCII)
        return when {
            head.startsWith("#EXTM3U") -> "application/vnd.apple.mpegurl"
            inner.size >= 189 && inner[0] == 0x47.toByte() -> "video/mp2t"
            else -> "application/octet-stream"
        }
    }

    /**
     * Extracts `(base64Payload, keyOffset)` from rou.video TanStack Start SSR HTML.
     */
    fun extractEvFromHtml(html: String): Pair<String, Int>? {
        val match = evExactRegex.find(html) ?: evFallbackRegex.find(html) ?: return null
        val encoded = match.groupValues[1]
        val offset = match.groupValues[2].toIntOrNull() ?: return null
        return encoded to offset
    }

    /**
     * Decodes rou.video `ev` payload: standard Base64 decode followed by subtracting [keyOffset] from each unsigned byte.
     */
    fun decodeEv(encodedBase64: String, keyOffset: Int): String {
        val bytes = Base64.decode(encodedBase64.trim())
        val chars = CharArray(bytes.size) { i ->
            ((bytes[i].toInt() and 0xFF) - keyOffset).toChar()
        }
        return String(chars)
    }

    private fun inflateZlib(raw: ByteArray): ByteArray? {
        // Must use nowrap = false (standard zlib header + Adler32 wrapper, NOT raw deflate).
        val inflater = Inflater(false)
        return try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(maxOf(raw.size * 2, 256))
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) return null
                } else {
                    if (out.size() + read > MAX_UNWRAPPED_BYTES) return null
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray().takeIf { it.isNotEmpty() && inflater.finished() }
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun deflateZlib(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size)
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count > 0) out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: Int, data: ByteArray) {
        writeIntBigEndian(out, data.size)
        val typeBytes = byteArrayOf(
            ((type ushr 24) and 0xFF).toByte(),
            ((type ushr 16) and 0xFF).toByte(),
            ((type ushr 8) and 0xFF).toByte(),
            (type and 0xFF).toByte(),
        )
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        writeIntBigEndian(out, crc)
    }

    private fun readIntBigEndian(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun writeIntBigEndian(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /**
     * Ready-to-use [MediaTransportHook] for `SiteId.ROUVIDEO`.
     */
    val transportHook: MediaTransportHook = object : MediaTransportHook {
        override fun isHlsUrl(url: String): Boolean = isRouHlsUrl(url)

        override fun requiresFullResponseUnwrap(url: String): Boolean = true

        override fun unwrapMediaBytes(url: String, bytes: ByteArray): UnwrappedMedia? {
            val unwrapped = unwrap(bytes) ?: return null
            return UnwrappedMedia(bytes = unwrapped, contentType = detectContentType(unwrapped))
        }
    }
}
