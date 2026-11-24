package com.raven.app.core.media

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

internal object TestMediaFixtures {
    fun validPng(tag: String = "default"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writePngChunk(
            out,
            "IHDR",
            byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0),
        )
        writePngChunk(out, "IDAT", "idat-payload-$tag".toByteArray(Charsets.UTF_8))
        writePngChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun truncatedPng(): ByteArray {
        val full = validPng("trunc")
        return full.copyOf(full.size - 4)
    }

    fun validJpeg(tag: String = "default"): ByteArray {
        val out = ByteArrayOutputStream()
        // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP0 length = 6 (2 len + 4 payload)
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x06, 0x4A, 0x46, 0x49, 0x46))
        // SOS length = 4 (2 len + 2 payload)
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x04, 0x01, 0x00))
        out.write("scan-$tag".toByteArray(Charsets.UTF_8))
        // EOI
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    fun truncatedJpeg(): ByteArray {
        val full = validJpeg("trunc")
        return full.copyOf(full.size - 2)
    }

    fun validGif(): ByteArray = byteArrayOf(
        'G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
        '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(),
        // Logical screen descriptor (7 bytes, GCT flag = 0)
        1, 0, 1, 0, 0, 0, 0,
        // Image descriptor (0x2C + 9 bytes, LCT flag = 0)
        0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0,
        // LZW min code size = 2, 1-byte subblock, terminator 0, trailer 0x3B
        2, 1, 0x44, 0, 0x3B,
    )

    fun validWebp(): ByteArray = byteArrayOf(
        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        12, 0, 0, 0, // RIFF size = 12 (end = 20)
        'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
        0, 0, 0, 0, // VP8 chunk size = 0
    )

    private fun writePngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeBigEndianInt(out, data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        writeBigEndianInt(out, crc)
    }

    private fun writeBigEndianInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
