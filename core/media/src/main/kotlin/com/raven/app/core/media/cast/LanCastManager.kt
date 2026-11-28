package com.raven.app.core.media.cast

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Discovered DLNA/UPnP MediaRenderer on the user's private LAN.
 */
data class LanCastDevice(
    val id: String,
    val friendlyName: String,
    val controlUrl: String,
    val locationUrl: String,
    val host: String,
)

/**
 * Lightweight zero-dependency LAN DLNA/UPnP casting manager + external player Intent launcher.
 *
 * The app network-security config forbids cleartext traffic, which also blocks OkHttp from talking
 * to normal DLNA renderers on the LAN. Description fetches and SOAP commands therefore use a small
 * raw-socket HTTP/1.1 client that never follows redirects and is only reached after
 * [lanUrlValidator] has accepted the target URL.
 *
 * Security invariants:
 * - SSDP `LOCATION` and SOAP `controlURL` are strictly restricted to HTTP(S) URLs on RFC1918 private
 *   IPv4 subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) — never loopback, link-local
 *   cloud metadata (`169.254.169.254`), or public hosts.
 * - Only plain HTTP is spoken by the raw transport; HTTPS LAN URLs are rejected.
 */
class LanCastManager(
    private val context: Context?,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val lanUrlValidator: (String) -> Boolean = Companion::isAllowedLanUrl,
) {

    suspend fun discoverDevices(timeoutMs: Long = 2_000L): List<LanCastDevice> = withContext(dispatchers.io) {
        val ctx = currentCoroutineContext()
        val locations = LinkedHashSet<String>()
        val probe = buildSsdpSearchPayload().toByteArray(Charsets.US_ASCII)
        val group = InetAddress.getByName(SSDP_MULTICAST_ADDR)

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs.coerceIn(500L, 5_000L).toInt()
            socket.send(DatagramPacket(probe, probe.size, group, SSDP_PORT))
            val deadline = System.currentTimeMillis() + socket.soTimeout
            val recvBuf = ByteArray(8 * 1024)
            while (System.currentTimeMillis() < deadline) {
                ctx.ensureActive()
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    socket.receive(packet)
                    val senderIp = packet.address?.hostAddress.orEmpty()
                    if (!isRfc1918Ipv4(senderIp)) continue
                    val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val loc = parseSsdpLocation(raw)
                    if (loc != null && isAllowedLanUrl(loc)) {
                        locations += loc
                    }
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: IOException) {
                    break
                }
            }
        }

        val devices = mutableListOf<LanCastDevice>()
        for (location in locations) {
            ctx.ensureActive()
            val xml = fetchXml(location) ?: continue
            parseDeviceDescriptionXml(xml, location)?.let { parsed ->
                if (isAllowedLanUrl(parsed.controlUrl)) {
                    devices += parsed
                }
            }
        }
        devices.distinctBy { it.controlUrl }
    }

    suspend fun castVideo(
        device: LanCastDevice,
        streamUrl: String,
        title: String,
    ) = withContext(dispatchers.io) {
        require(isAllowedLanUrl(device.controlUrl)) { "投屏目标地址不在局域网私有网段内" }
        require(streamUrl.startsWith("http://", ignoreCase = true) || streamUrl.startsWith("https://", ignoreCase = true)) {
            "仅支持 HTTP(S) 视频流投屏"
        }
        postSoapAction(
            controlUrl = device.controlUrl,
            action = "SetAVTransportURI",
            body = buildSetAvTransportUriSoap(streamUrl, title),
        )
        postSoapAction(
            controlUrl = device.controlUrl,
            action = "Play",
            body = buildPlaySoap(),
        )
    }

    suspend fun stopCast(device: LanCastDevice) = withContext(dispatchers.io) {
        require(isAllowedLanUrl(device.controlUrl)) { "投屏目标地址不在局域网私有网段内" }
        postSoapAction(
            controlUrl = device.controlUrl,
            action = "Stop",
            body = buildStopSoap(),
        )
    }

    fun launchExternalPlayer(url: String, mimeType: String = "video/*"): Boolean {
        val appContext = context?.applicationContext ?: return false
        val intent = buildExternalPlayerIntent(url, mimeType) ?: return false
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    internal suspend fun fetchXml(locationUrl: String): String? {
        if (!lanUrlValidator(locationUrl)) return null
        return try {
            val response = executeHttpRequest(
                method = "GET",
                rawUrl = locationUrl,
                headers = mapOf("Accept" to "text/xml, application/xml, */*"),
            )
            if (response.statusCode !in 200..299) return null
            String(response.body, Charsets.UTF_8)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    internal suspend fun postSoapAction(controlUrl: String, action: String, body: String) {
        if (!lanUrlValidator(controlUrl)) {
            throw IOException("拒绝向非局域网地址发送投屏指令")
        }
        val response = executeHttpRequest(
            method = "POST",
            rawUrl = controlUrl,
            headers = mapOf(
                "SOAPACTION" to "\"$AV_TRANSPORT_SERVICE#$action\"",
                "Content-Type" to "text/xml; charset=\"utf-8\"",
            ),
            body = body.toByteArray(Charsets.UTF_8),
        )
        if (response.statusCode !in 200..299) {
            throw IOException("DLNA $action 失败 (HTTP ${response.statusCode})")
        }
    }

    private suspend fun executeHttpRequest(
        method: String,
        rawUrl: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): RawHttpResponse = withContext(dispatchers.io) {
        if (!lanUrlValidator(rawUrl)) throw IOException("局域网地址未通过校验")
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
            ?: throw IOException("无效的局域网地址")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http") throw IOException("仅支持局域网 HTTP 投屏")
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) throw IOException("局域网地址缺少主机")
        val port = if (uri.port >= 0) uri.port else 80
        val target = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { append('?').append(it) }
        }
        if (target.any { it == '\r' || it == '\n' }) throw IOException("非法请求路径")
        val hostHeader = if (port == 80) host else "$host:$port"

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val output = BufferedOutputStream(socket.getOutputStream())
            val head = buildString {
                append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("Connection: close\r\n")
                append("Accept: */*\r\n")
                append("User-Agent: RavenLanCast/1.0\r\n")
                headers.forEach { (name, value) ->
                    append(name).append(": ").append(value).append("\r\n")
                }
                if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
                append("\r\n")
            }
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            body?.let { output.write(it) }
            output.flush()

            val input = BufferedInputStream(socket.getInputStream())
            readHttpResponse(input, MAX_DESCRIPTION_XML_BYTES)
        }
    }

    private suspend fun readHttpResponse(input: InputStream, maxBodyBytes: Int): RawHttpResponse {
        val ctx = currentCoroutineContext()
        val statusLine = readHttpLine(input) ?: throw IOException("空的 HTTP 响应")
        val statusParts = statusLine.split(' ', limit = 3)
        if (statusParts.size < 2 || !statusParts[0].startsWith("HTTP/")) {
            throw IOException("非法 HTTP 状态行")
        }
        val statusCode = statusParts[1].toIntOrNull() ?: throw IOException("非法 HTTP 状态码")

        val headers = LinkedHashMap<String, String>()
        var headerBytes = 0
        while (true) {
            ctx.ensureActive()
            val line = readHttpLine(input) ?: throw IOException("HTTP 响应头不完整")
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HTTP_HEADER_BYTES) throw IOException("HTTP 响应头过大")
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                headers[name] = line.substring(colon + 1).trim()
            }
        }

        if (statusCode in 300..399) {
            // Redirects are never followed and their body is irrelevant to the caller.
            return RawHttpResponse(statusCode, ByteArray(0))
        }
        return RawHttpResponse(statusCode, readHttpBody(input, headers, maxBodyBytes, ctx))
    }

    private suspend fun readHttpBody(
        input: InputStream,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        ctx: CoroutineContext,
    ): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return readChunkedBody(input, maxBodyBytes, ctx)
        }
        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength != null) {
            if (contentLength < 0L || contentLength > maxBodyBytes.toLong()) {
                throw IOException("HTTP 响应体过大")
            }
            return readFixedBody(input, contentLength, ctx)
        }
        return readUntilEof(input, maxBodyBytes, ctx)
    }

    private fun readFixedBody(
        input: InputStream,
        length: Long,
        ctx: CoroutineContext,
    ): ByteArray {
        if (length == 0L) return ByteArray(0)
        val out = ByteArrayOutputStream(length.toInt())
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var remaining = length
        while (remaining > 0L) {
            ctx.ensureActive()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("HTTP 响应体不完整")
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun readUntilEof(
        input: InputStream,
        maxBodyBytes: Int,
        ctx: CoroutineContext,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            ctx.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (out.size().toLong() + read > maxBodyBytes.toLong()) {
                throw IOException("HTTP 响应体过大")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun readChunkedBody(
        input: InputStream,
        maxBodyBytes: Int,
        ctx: CoroutineContext,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            ctx.ensureActive()
            val sizeLine = readHttpLine(input) ?: throw IOException("分块响应不完整")
            val sizeToken = sizeLine.substringBefore(';').trim()
            if (sizeToken.isEmpty() || !sizeToken.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw IOException("非法分块长度")
            }
            val chunkSize = sizeToken.toLongOrNull(16)?.takeIf { it >= 0L }
                ?: throw IOException("非法分块长度")
            if (chunkSize == 0L) {
                while (true) {
                    ctx.ensureActive()
                    val trailer = readHttpLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            if (chunkSize > maxBodyBytes.toLong() ||
                out.size().toLong() + chunkSize > maxBodyBytes.toLong()
            ) {
                throw IOException("HTTP 响应体过大")
            }
            var remaining = chunkSize
            while (remaining > 0L) {
                ctx.ensureActive()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) throw IOException("分块响应体不完整")
                out.write(buffer, 0, read)
                remaining -= read
            }
            val terminator = readHttpLine(input) ?: throw IOException("分块响应缺少终止符")
            if (terminator.isNotEmpty()) throw IOException("非法分块终止符")
        }
        return out.toByteArray()
    }

    private fun readHttpLine(input: InputStream, maxBytes: Int = MAX_HTTP_LINE_BYTES): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (out.size() == 0) return null
                throw IOException("HTTP 响应行未结束")
            }
            if (value == '\n'.code) break
            if (out.size() >= maxBytes) throw IOException("HTTP 响应行过长")
            if (value != '\r'.code) out.write(value)
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private data class RawHttpResponse(
        val statusCode: Int,
        val body: ByteArray,
    )

    companion object {
        const val SSDP_MULTICAST_ADDR: String = "239.255.255.250"
        const val SSDP_PORT: Int = 1900
        const val AV_TRANSPORT_SERVICE: String = "urn:schemas-upnp-org:service:AVTransport:1"
        internal const val MAX_DESCRIPTION_XML_BYTES: Int = 512 * 1024

        private const val CONNECT_TIMEOUT_MS: Int = 5_000
        private const val READ_TIMEOUT_MS: Int = 8_000
        private const val READ_BUFFER_BYTES: Int = 8 * 1024
        private const val MAX_HTTP_LINE_BYTES: Int = 8 * 1024
        private const val MAX_HTTP_HEADER_BYTES: Int = 64 * 1024

        private val LOCATION_HEADER_REGEX = Regex("(?im)^LOCATION:\\s*(https?://\\S+)\\s*$")
        private val FRIENDLY_NAME_REGEX = Regex("(?is)<friendlyName>\\s*([^<]+?)\\s*</friendlyName>")
        private val UDN_REGEX = Regex("(?is)<UDN>\\s*([^<]+?)\\s*</UDN>")
        private val SERVICE_BLOCK_REGEX = Regex("(?is)<service>\\s*.*?</service>")
        private val CONTROL_URL_REGEX = Regex("(?is)<controlURL>\\s*([^<]+?)\\s*</controlURL>")

        fun buildSsdpSearchPayload(): String = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_MULTICAST_ADDR:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: $AV_TRANSPORT_SERVICE\r\n")
            append("\r\n")
        }

        fun parseSsdpLocation(rawResponse: String): String? =
            LOCATION_HEADER_REGEX.find(rawResponse)?.groupValues?.getOrNull(1)?.trim()

        fun isRfc1918Ipv4(host: String): Boolean {
            val parts = host.trim().split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            val a = octets[0]
            val b = octets[1]
            return when {
                a == 10 -> true
                a == 172 && b in 16..31 -> true
                a == 192 && b == 168 -> true
                else -> false
            }
        }

        fun isAllowedLanUrl(rawUrl: String): Boolean = runCatching {
            val uri = URI(rawUrl.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host ?: return false
            isRfc1918Ipv4(host)
        }.getOrDefault(false)

        fun parseDeviceDescriptionXml(xml: String, locationUrl: String): LanCastDevice? {
            if (!isAllowedLanUrl(locationUrl)) return null
            val serviceBlock = SERVICE_BLOCK_REGEX.findAll(xml)
                .map { it.value }
                .firstOrNull { it.contains("AVTransport", ignoreCase = true) }
                ?: return null
            val relControl = CONTROL_URL_REGEX.find(serviceBlock)?.groupValues?.getOrNull(1)?.trim()
                ?: return null
            val resolvedControl = runCatching { URI(locationUrl).resolve(relControl).toString() }.getOrNull()
                ?: return null
            if (!isAllowedLanUrl(resolvedControl)) return null

            val friendlyName = unescapeXml(
                FRIENDLY_NAME_REGEX.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty(),
            ).ifBlank { URI(locationUrl).host.orEmpty() }
            val udn = UDN_REGEX.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val id = udn.ifBlank { resolvedControl }
            val host = runCatching { URI(locationUrl).host.orEmpty() }.getOrDefault("")
            return LanCastDevice(
                id = id,
                friendlyName = friendlyName,
                controlUrl = resolvedControl,
                locationUrl = locationUrl,
                host = host,
            )
        }

        fun buildSetAvTransportUriSoap(streamUrl: String, title: String): String {
            val safeUrl = escapeXml(streamUrl)
            val safeTitle = escapeXml(title.ifBlank { "Raven Video" })
            val metadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">""" +
                """<item id="0" parentID="-1" restricted="1"><dc:title>$safeTitle</dc:title></item></DIDL-Lite>"""
            return buildString {
                append("""<?xml version="1.0" encoding="utf-8"?>""")
                append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""")
                append("""<s:Body><u:SetAVTransportURI xmlns:u="$AV_TRANSPORT_SERVICE">""")
                append("<InstanceID>0</InstanceID><CurrentURI>$safeUrl</CurrentURI>")
                append("<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>")
                append("</u:SetAVTransportURI></s:Body></s:Envelope>")
            }
        }

        fun buildPlaySoap(): String = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Play xmlns:u="$AV_TRANSPORT_SERVICE">
                  <InstanceID>0</InstanceID>
                  <Speed>1</Speed>
                </u:Play>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        fun buildStopSoap(): String = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Stop xmlns:u="$AV_TRANSPORT_SERVICE">
                  <InstanceID>0</InstanceID>
                </u:Stop>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        fun buildExternalPlayerIntent(url: String, mimeType: String = "video/*"): Intent? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            val parsed = runCatching { trimmed.toUri() }.getOrNull() ?: return null
            val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            if (scheme !in setOf("http", "https", "file", "content")) return null
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(parsed, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        private fun escapeXml(raw: String): String = raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        private fun unescapeXml(raw: String): String = raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
