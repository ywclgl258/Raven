package com.raven.app.core.media.reader

import android.graphics.BitmapFactory
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Container-level completeness validator and decoder math for gallery/comic images.
 *
 * Validates binary container boundaries (JPEG EOI after SOS, PNG IDAT+IEND CRC, GIF trailer,
 * WebP RIFF size, BMP header bounds, ISO BMFF `ftyp`/`mdat` for AVIF/HEIF/HEIC) without
 * allocating any bitmap memory.
 */
object ImageStructureValidator {
    const val DEFAULT_MAX_GALLERY_IMAGE_BYTES: Long = 24L * 1024L * 1024L
    const val DEFAULT_MAX_DECODE_PIXELS: Long = 4_000_000L

    val SUPPORTED_IMAGE_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heif", "heic",
    )

    fun isSupportedImageName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in SUPPORTED_IMAGE_EXTENSIONS
    }

    /**
     * Power-of-2 [BitmapFactory.Options.inSampleSize] keeping decoded pixels `<= maxPixels` (default 4 MP).
     */
    fun computeInSampleSize(
        width: Int,
        height: Int,
        maxPixels: Long = DEFAULT_MAX_DECODE_PIXELS,
    ): Int {
        if (width <= 0 || height <= 0 || maxPixels <= 0L) return 1
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }

    fun computeThumbnailSampleSize(longEdge: Int, targetSizePx: Int): Int {
        if (longEdge <= 0 || targetSizePx <= 0) return 1
        var sample = 1
        while (longEdge / sample / 2 >= targetSizePx) {
            sample *= 2
        }
        return sample
    }

    fun computeThumbnailEdge(edge: Int, longEdge: Int, targetSizePx: Int): Int {
        if (edge <= 0 || longEdge <= 0 || targetSizePx <= 0) return 1
        return (edge.toLong() * minOf(longEdge, targetSizePx) / longEdge).toInt().coerceAtLeast(1)
    }

    fun detectContainerMime(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val reader = ByteArrayStructureReader(bytes)
        return when {
            bytes.size >= 2 && reader.byte(0) == 0xFF && reader.byte(1) == 0xD8 -> "image/jpeg"
            reader.tag(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            reader.tag(0, "GIF87a", "GIF89a") -> "image/gif"
            reader.tag(0, "RIFF") && reader.tag(8, "WEBP") -> "image/webp"
            reader.tag(0, "BM") -> "image/bmp"
            else -> reader.heifMime()
        }
    }

    fun detectHeifContainerMime(bytes: ByteArray): String? {
        if (bytes.size < 16) return null
        return ByteArrayStructureReader(bytes).heifMime()
    }

    fun extensionForMime(mime: String, bytes: ByteArray = ByteArray(0)): String = when (mime.lowercase(Locale.ROOT)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp", "image/x-ms-bmp" -> "bmp"
        "image/avif" -> "avif"
        "image/heif", "image/heic" -> if (detectHeifContainerMime(bytes) == "image/avif") "avif" else "heic"
        else -> error("暂不支持此图片格式：$mime")
    }

    /**
     * Pure-Kotlin container completeness verification on raw [bytes] (JVM test safe).
     */
    fun isCompleteImageBytes(
        bytes: ByteArray,
        maxBytes: Long = DEFAULT_MAX_GALLERY_IMAGE_BYTES,
        declaredMime: String? = null,
    ): Boolean {
        if (bytes.isEmpty() || bytes.size.toLong() > maxBytes) return false
        val reader = ByteArrayStructureReader(bytes)
        val mime = detectContainerMime(bytes) ?: declaredMime
        return reader.isComplete(mime)
    }

    /**
     * Pure-Kotlin container completeness verification on a [File] without decoding pixels (JVM test safe).
     */
    fun isCompleteImageFileStructure(
        file: File,
        maxBytes: Long = DEFAULT_MAX_GALLERY_IMAGE_BYTES,
        declaredMime: String? = null,
    ): Boolean = runCatching {
        val length = file.length()
        if (!file.isFile || length !in 1L..maxBytes) return false
        RandomAccessFile(file, "r").use { raf ->
            val headerSize = minOf(length, 4096L).toInt()
            val header = ByteArray(headerSize)
            raf.readFully(header)
            val mime = detectContainerMime(header) ?: declaredMime
            val reader = StructureReader(length) { offset, buffer, count ->
                raf.seek(offset)
                raf.readFully(buffer, 0, count)
            }
            reader.isComplete(mime)
        }
    }.getOrDefault(false)

    /**
     * Android full verification: checks container completeness AND [BitmapFactory] header bounds.
     * Falls back to structural validation alone if `requireAndroidDecoder` is false (for JVM tests).
     */
    fun isValidImageBytes(
        bytes: ByteArray,
        maxBytes: Long = DEFAULT_MAX_GALLERY_IMAGE_BYTES,
        requireAndroidDecoder: Boolean = true,
    ): Boolean {
        if (bytes.isEmpty() || bytes.size.toLong() > maxBytes) return false
        var decoderMime: String? = null
        if (requireAndroidDecoder) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            decoderMime = bounds.outMimeType
        }
        return isCompleteImageBytes(
            bytes = bytes,
            maxBytes = maxBytes,
            declaredMime = detectHeifContainerMime(bytes) ?: decoderMime,
        )
    }

    fun isValidImageFile(
        file: File,
        maxBytes: Long = DEFAULT_MAX_GALLERY_IMAGE_BYTES,
        requireAndroidDecoder: Boolean = true,
    ): Boolean = runCatching {
        if (!file.isFile || file.length() !in 1L..maxBytes) return false
        var decoderMime: String? = null
        if (requireAndroidDecoder) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            decoderMime = bounds.outMimeType
        }
        isCompleteImageFileStructure(file, maxBytes, declaredMime = decoderMime)
    }.getOrDefault(false)

    private fun ByteArrayStructureReader(bytes: ByteArray): StructureReader =
        StructureReader(bytes.size.toLong()) { offset, buffer, count ->
            bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count)
        }

    private class StructureReader(
        val length: Long,
        val readAt: (Long, ByteArray, Int) -> Unit,
    ) {
        private val buffer = ByteArray(4096)
        private var bufferStart = -buffer.size.toLong()
        private var bufferEnd = 0L

        fun byte(offset: Long): Int {
            if (offset !in 0L until length) return -1
            if (offset !in bufferStart until bufferEnd) {
                bufferStart = (offset / buffer.size) * buffer.size
                val count = minOf(length - bufferStart, buffer.size.toLong()).toInt()
                readAt(bufferStart, buffer, count)
                bufferEnd = bufferStart + count
            }
            return buffer[(offset - bufferStart).toInt()].toInt() and 0xFF
        }

        private fun number(offset: Long, count: Int, littleEndian: Boolean = false): Long =
            (0 until count).fold(0L) { acc, index ->
                val b = byte(offset + if (littleEndian) count - index - 1 else index)
                if (b < 0) return -1L
                (acc shl 8) or b.toLong()
            }

        fun tag(offset: Long, vararg values: String): Boolean = values.any { value ->
            value.indices.all { byte(offset + it) == value[it].code }
        }

        fun tag(offset: Long, vararg bytes: Int): Boolean =
            bytes.indices.all { byte(offset + it) == bytes[it] }

        fun heifMime(): String? {
            if (length < 16L || !tag(4, "ftyp")) return null
            val boxSize = number(0, 4)
            if (boxSize < 16L) return null
            val end = minOf(boxSize, length, 4096L)
            if (end < 16L) return null
            val brands = buildList {
                add(8L)
                var pos = 16L
                while (pos <= end - 4L) {
                    add(pos)
                    pos += 4L
                }
            }
            return when {
                brands.any { tag(it, "avif", "avis") } -> "image/avif"
                brands.any { tag(it, "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs") } -> "image/heic"
                brands.any { tag(it, "mif1", "msf1") } -> "image/heif"
                else -> null
            }
        }

        fun isComplete(mime: String?): Boolean = when {
            byte(0) == 0xFF && byte(1) == 0xD8 -> isCompleteJpeg()
            tag(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> isCompletePng()
            tag(0, "GIF87a", "GIF89a") -> isCompleteGif()
            tag(0, "RIFF") && tag(8, "WEBP") -> isCompleteWebp()
            tag(0, "BM") -> isCompleteBmp()
            mime == "image/heic" && heifMime() == null -> false
            mime in setOf("image/heif", "image/heic", "image/avif") || heifMime() != null -> isCompleteIsoBoxes()
            else -> mime != null
        }

        private fun isCompleteJpeg(): Boolean {
            var offset = 2L
            while (offset + 3L < length && byte(offset++) == 0xFF) {
                while (byte(offset) == 0xFF) offset++
                val marker = byte(offset++)
                if (marker < 0) return false
                // Standalone markers without length (RST0..RST7, TEM)
                if (marker == 0x01 || marker in 0xD0..0xD7) continue
                if (marker == 0xD9) return false // EOI before SOS
                val size = number(offset, 2)
                if (size < 2L || size > length - offset) return false
                offset += size
                if (marker == 0xDA) {
                    // Search for EOI after Start of Scan (ignores EXIF thumbnails).
                    val minSearch = maxOf(offset, length - 64L * 1024L)
                    for (end in (length - 2L) downTo minSearch) {
                        if (byte(end + 1L) == 0xD9 && byte(end) == 0xFF) return true
                    }
                    return false
                }
            }
            return false
        }

        private fun isCompletePng(): Boolean {
            var offset = 8L
            var sawIdat = false
            while (offset + 12L <= length) {
                val chunkLen = number(offset, 4)
                if (chunkLen < 0L || chunkLen > length - offset - 12L) return false
                if (tag(offset + 4L, "IDAT")) sawIdat = true
                if (tag(offset + 4L, "IEND")) {
                    return sawIdat && chunkLen == 0L && number(offset + 8L, 4) == 0xAE426082L
                }
                offset += chunkLen + 12L
            }
            return false
        }

        private fun isCompleteGif(): Boolean {
            if (length < 14L) return false
            fun colorTableBytes(flags: Int): Int =
                if (flags and 0x80 == 0) 0 else 3 shl ((flags and 7) + 1)

            fun skipSubBlocks(start: Long): Long {
                var pos = start
                while (pos < length) {
                    val count = byte(pos++)
                    if (count < 0) return -1L
                    if (count == 0) return pos
                    if (count > length - pos) return -1L
                    pos += count
                }
                return -1L
            }

            val gctFlags = byte(10)
            if (gctFlags < 0) return false
            var offset = 13L + colorTableBytes(gctFlags)
            var sawImage = false
            while (offset < length) {
                when (byte(offset++)) {
                    0x21 -> {
                        if (offset >= length) return false
                        offset = skipSubBlocks(offset + 1L)
                        if (offset < 0L) return false
                    }
                    0x2C -> {
                        if (offset + 9L >= length) return false
                        val lctFlags = byte(offset + 8L)
                        if (lctFlags < 0) return false
                        val start = offset + 9L + colorTableBytes(lctFlags)
                        if (start + 1L >= length || byte(start) !in 2..12 || byte(start + 1L) <= 0) return false
                        offset = skipSubBlocks(start + 1L)
                        if (offset < 0L) return false
                        sawImage = true
                    }
                    0x3B -> return sawImage
                    else -> return false
                }
            }
            return false
        }

        private fun isCompleteWebp(): Boolean {
            val riffSize = number(4, 4, littleEndian = true)
            if (riffSize < 12L) return false
            val end = riffSize + 8L
            if (end !in 20L..length) return false
            var offset = 12L
            while (offset + 8L <= end) {
                val chunkSize = number(offset + 4L, 4, littleEndian = true)
                if (chunkSize < 0L) return false
                offset += 8L + chunkSize + (chunkSize and 1L)
            }
            return offset == end
        }

        private fun isCompleteBmp(): Boolean {
            val fileSize = number(2, 4, littleEndian = true)
            val pixelOffset = number(10, 4, littleEndian = true)
            return pixelOffset in 14L until length && (fileSize == 0L || fileSize in (pixelOffset + 1L)..length)
        }

        private fun isCompleteIsoBoxes(): Boolean {
            var offset = 0L
            var hasMedia = false
            while (offset + 8L <= length) {
                val size = number(offset, 4)
                if (size < 0L) return false
                val headerSize = if (size == 1L) 16L else 8L
                val totalSize = when (size) {
                    0L -> length - offset
                    1L -> if (offset + 16L <= length) number(offset + 8L, 8) else return false
                    else -> size
                }
                if (totalSize < headerSize || totalSize > length - offset) return false
                if (tag(offset + 4L, "mdat", "meta") && totalSize > headerSize) hasMedia = true
                offset += totalSize
            }
            return offset == length && hasMedia
        }
    }
}
