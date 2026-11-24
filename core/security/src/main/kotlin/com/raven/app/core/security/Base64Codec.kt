package com.raven.app.core.security

/**
 * Pure-Kotlin RFC 4648 Base64 encoder/decoder (no line wrapping).
 * Avoids `java.util.Base64` (API 26+) and `android.util.Base64` (stubbed in JVM unit tests),
 * so encrypted vault persistence behaves identically on Android API 24..36 and pure JVM tests.
 */
internal object Base64Codec {
    private val encodeTable = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()
    private val decodeTable = IntArray(128) { -1 }.apply {
        for (i in encodeTable.indices) {
            this[encodeTable[i].code] = i
        }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = CharArray(((bytes.size + 2) / 3) * 4)
        var inIdx = 0
        var outIdx = 0
        while (inIdx < bytes.size) {
            val b0 = bytes[inIdx++].toInt() and 0xFF
            val hasB1 = inIdx < bytes.size
            val b1 = if (hasB1) bytes[inIdx++].toInt() and 0xFF else 0
            val hasB2 = inIdx < bytes.size
            val b2 = if (hasB2) bytes[inIdx++].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out[outIdx++] = encodeTable[(triple ushr 18) and 0x3F]
            out[outIdx++] = encodeTable[(triple ushr 12) and 0x3F]
            out[outIdx++] = if (hasB1) encodeTable[(triple ushr 6) and 0x3F] else '='
            out[outIdx++] = if (hasB2) encodeTable[triple and 0x3F] else '='
        }
        return String(out)
    }

    fun decode(encoded: String): ByteArray {
        val trimmed = encoded.trim()
        if (trimmed.isEmpty()) return ByteArray(0)
        if (trimmed.length % 4 != 0) {
            throw IllegalArgumentException("Invalid Base64 length: ${trimmed.length}")
        }
        var padCount = 0
        if (trimmed.endsWith("==")) {
            padCount = 2
        } else if (trimmed.endsWith("=")) {
            padCount = 1
        }
        val outLen = (trimmed.length / 4) * 3 - padCount
        val out = ByteArray(outLen)
        var outIdx = 0
        var i = 0
        while (i < trimmed.length) {
            val c0 = decodeChar(trimmed[i])
            val c1 = decodeChar(trimmed[i + 1])
            val ch2 = trimmed[i + 2]
            val ch3 = trimmed[i + 3]
            val isLastQuad = i + 4 == trimmed.length
            val c2 = if (ch2 == '=' && isLastQuad && padCount == 2) 0 else decodeChar(ch2)
            val c3 = if (ch3 == '=' && isLastQuad && padCount >= 1) 0 else decodeChar(ch3)

            val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (outIdx < outLen) out[outIdx++] = ((triple ushr 16) and 0xFF).toByte()
            if (outIdx < outLen) out[outIdx++] = ((triple ushr 8) and 0xFF).toByte()
            if (outIdx < outLen) out[outIdx++] = (triple and 0xFF).toByte()
            i += 4
        }
        return out
    }

    private fun decodeChar(ch: Char): Int {
        val code = ch.code
        if (code >= decodeTable.size || decodeTable[code] < 0) {
            throw IllegalArgumentException("Invalid Base64 character: '$ch'")
        }
        return decodeTable[code]
    }
}
