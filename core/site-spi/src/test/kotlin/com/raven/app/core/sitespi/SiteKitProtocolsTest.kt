package com.raven.app.core.sitespi

import com.raven.app.core.model.SiteException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64

class SiteKitProtocolsTest {

    @Test
    fun `rou png protocol unwraps deflated and plain roUd chunks and detects media mime`() {
        val m3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST\n".toByteArray(Charsets.UTF_8)
        val wrappedDeflated = RouPngProtocol.wrap(m3u8, deflate = true)
        assertTrue(RouPngProtocol.isWrapped(wrappedDeflated))

        val unwrapped = RouPngProtocol.unwrap(wrappedDeflated)
        assertNotNull(unwrapped)
        assertArrayEquals(m3u8, unwrapped)
        assertEquals("application/vnd.apple.mpegurl", RouPngProtocol.detectContentType(unwrapped!!))

        // Uncompressed flag=0 MPEG-TS segment (sync byte 0x47, length >= 189)
        val tsSegment = ByteArray(376) { idx -> if (idx % 188 == 0) 0x47 else (idx and 0xFF).toByte() }
        val wrappedPlain = RouPngProtocol.wrap(tsSegment, deflate = false)
        val unwrappedTs = RouPngProtocol.unwrap(wrappedPlain)
        assertArrayEquals(tsSegment, unwrappedTs)
        assertEquals("video/mp2t", RouPngProtocol.detectContentType(unwrappedTs!!))

        // Transport hook checks
        assertTrue(RouPngProtocol.transportHook.isHlsUrl("https://rou.video/api/hls/abc123"))
        assertTrue(RouPngProtocol.transportHook.requiresFullResponseUnwrap("https://rn221.xyz/seg.png"))
        val hookResult = RouPngProtocol.transportHook.unwrapMediaBytes("https://rn221.xyz/seg.png", wrappedDeflated)
        assertEquals("application/vnd.apple.mpegurl", hookResult?.contentType)

        // Plain non-PNG bytes return null
        assertNull(RouPngProtocol.unwrap("plain text".toByteArray()))
    }

    @Test
    fun `rou png protocol decodes ev payload from ssr html`() {
        val original = """{"videoUrl":"/api/hls/demo123","thumbVTTUrl":"/vtt/demo123.vtt"}"""
        val keyOffset = 7
        val shifted = ByteArray(original.length) { i -> ((original[i].code + keyOffset) and 0xFF).toByte() }
        val b64 = Base64.encode(shifted)
        val html = """<script>window.x = {ev:${'$'}R[12]={d:"$b64",k:$keyOffset}};</script>"""

        val extracted = RouPngProtocol.extractEvFromHtml(html)
        assertNotNull(extracted)
        assertEquals(b64, extracted!!.first)
        assertEquals(keyOffset, extracted.second)
        assertEquals(original, RouPngProtocol.decodeEv(extracted.first, extracted.second))
    }

    @Test
    fun `rou png protocol decodeEv handles byte wrap-around and UTF-8 text and detects BOM m3u8 and 188-byte TS`() {
        val utf8Payload = """{"title":"测试\u0000\u0001","url":"/api/hls/demo"}"""
        val keyOffset = 200
        val rawUtf8 = utf8Payload.toByteArray(Charsets.UTF_8)
        val shifted = ByteArray(rawUtf8.size) { i -> (((rawUtf8[i].toInt() and 0xFF) + keyOffset) and 0xFF).toByteByteMask() }
        assertEquals(utf8Payload, RouPngProtocol.decodeEv(Base64.encode(shifted), keyOffset))

        val bomM3u8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray(Charsets.UTF_8)
        assertEquals("application/vnd.apple.mpegurl", RouPngProtocol.detectContentType(bomM3u8))

        val singleTsPacket = ByteArray(188) { idx -> if (idx == 0) 0x47 else 0x11 }
        assertEquals("video/mp2t", RouPngProtocol.detectContentType(singleTsPacket))
    }

    private fun Int.toByteByteMask(): Byte = (this and 0xFF).toByte()

    @Test
    fun `cossora aes protocol decrypts embed html and enforces trusted host`() {
        val key = "0123456789abcdef0123456789abcdef" // 32 ASCII chars -> 32-byte AES-256 key
        val iv = ByteArray(16) { (it + 1).toByte() }
        val hlsUrl = "https://cdn1.cossora.stream/hls/demo/index.m3u8"
        val posterUrl = "https://cdn1.cossora.stream/img/poster.jpg"

        val encVideo = CossoraAesProtocol.encrypt(hlsUrl, key, iv)
        val encPoster = CossoraAesProtocol.encrypt(posterUrl, key, iv)

        val articleHtml = """
            <iframe src="https://cossora.stream/embed/11111111-2222-3333-4444-555555555555"></iframe>
            <iframe src="https://evil.example/embed/11111111-2222-3333-4444-555555555555"></iframe>
        """.trimIndent()
        val embeds = CossoraAesProtocol.extractEmbeds(articleHtml)
        assertEquals(1, embeds.size)
        assertEquals("video-11111111-2222-3333-4444-555555555555", embeds.first().id)

        val embedHtml = """
            <script>
                const videoURL = '$encVideo';
                const portadaURL = '$encPoster';
                const url = decryptLink(videoURL, '$key');
                const opts = { defaultQuality: '1080p' };
            </script>
        """.trimIndent()
        val media = CossoraAesProtocol.parseEmbedHtml(embedHtml)
        assertEquals(hlsUrl, media.streamUrl)
        assertEquals(posterUrl, media.posterUrl)
        assertEquals("1080p", media.qualityLabel)
    }

    @Test(expected = SiteException.ParseError::class)
    fun `cossora aes protocol rejects decrypted url from untrusted domain`() {
        val key = "0123456789abcdef0123456789abcdef"
        val iv = ByteArray(16) { 0x2A }
        val badUrl = CossoraAesProtocol.encrypt("https://evil.example/video.m3u8", key, iv)
        val embedHtml = """
            const videoURL = '$badUrl';
            decryptLink(videoURL, '$key');
        """.trimIndent()
        CossoraAesProtocol.parseEmbedHtml(embedHtml)
    }

    @Test
    fun `jmcomic strip math matches exact threshold rules and lossless slice geometry`() {
        // Below scrambleId -> 0
        assertEquals(0, JmcomicStripMath.computeStripCount(chapterId = 200_000L, scrambleId = 220_980L, filenameStem = "00001"))
        // Between scrambleId and 268850 -> fixed 10
        assertEquals(10, JmcomicStripMath.computeStripCount(chapterId = 250_000L, scrambleId = 220_980L, filenameStem = "00001"))

        // Between 268850 and 421926 -> even value in 2..20
        val countMid = JmcomicStripMath.computeStripCount(chapterId = 350_000L, scrambleId = 220_980L, filenameStem = "00001")
        assertTrue("Expected even in 2..20, got $countMid", countMid in 2..20 && countMid % 2 == 0)

        // >= 421926 -> even value in 2..16
        val countModern = JmcomicStripMath.computeStripCount(chapterId = 500_000L, scrambleId = 220_980L, filenameStem = "00001")
        assertTrue("Expected even in 2..16, got $countModern", countModern in 2..16 && countModern % 2 == 0)

        // Verify exact slice geometry for height=103, strips=4 (stripHeight=25, remainder=3)
        val slices = JmcomicStripMath.computeSlicePlan(imageHeight = 103, stripCount = 4)
        assertEquals(4, slices.size)
        // index 0: bottom of source [75, 103) (height 28 = 25+3) -> top of dest [0, 28)
        assertEquals(StripSlice(sourceTop = 75, destinationTop = 0, height = 28), slices[0])
        assertEquals(StripSlice(sourceTop = 50, destinationTop = 28, height = 25), slices[1])
        assertEquals(StripSlice(sourceTop = 25, destinationTop = 53, height = 25), slices[2])
        assertEquals(StripSlice(sourceTop = 0, destinationTop = 78, height = 25), slices[3])

        // Total height coverage invariant
        assertEquals(103, slices.sumOf { it.height })

        // Dimension validation
        assertTrue(JmcomicStripMath.validateDimensions(1200, 1800, 10))
        assertFalse(JmcomicStripMath.validateDimensions(4000, 4000, 10)) // 16M > 12M limit
        assertEquals(
            listOf(StripSlice(sourceTop = 0, destinationTop = 0, height = 100)),
            JmcomicStripMath.computeSlicePlan(imageHeight = 100, stripCount = 0),
        )
        assertEquals(
            IllegalArgumentException::class.java,
            runCatching { JmcomicStripMath.computeSlicePlan(imageHeight = 0, stripCount = 4) }.exceptionOrNull()?.javaClass,
        )
    }
}
