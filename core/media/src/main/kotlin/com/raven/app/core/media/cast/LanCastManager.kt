package com.raven.app.core.media.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
 * Security invariants:
 * - SSDP `LOCATION` and SOAP `controlURL` are strictly restricted to HTTP(S) URLs on RFC1918 private
 *   IPv4 subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) — never loopback, link-local
 *   cloud metadata (`169.254.169.254`), or public hosts.
 */
class LanCastManager(
    private val context: Context?,
    private val okHttpClient: OkHttpClient,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
) {
    constructor(
        context: Context,
        okHttpClient: OkHttpClient,
    ) : this(
        context = context.applicationContext,
        okHttpClient = okHttpClient,
        dispatchers = DefaultAppDispatchers(),
    )

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

    private fun fetchXml(locationUrl: String): String? = runCatching {
        if (!isAllowedLanUrl(locationUrl)) return null
        val request = Request.Builder().url(locationUrl).get().build()
        okHttpClient.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            if (body.length > MAX_DESCRIPTION_XML_CHARS) null else body
        }
    }.getOrNull()

    private fun postSoapAction(controlUrl: String, action: String, body: String) {
        val request = Request.Builder()
            .url(controlUrl)
            .header("SOAPACTION", "\"$AV_TRANSPORT_SERVICE#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                throw IOException("DLNA $action 失败 (HTTP ${res.code})")
            }
        }
    }

    companion object {
        const val SSDP_MULTICAST_ADDR: String = "239.255.255.250"
        const val SSDP_PORT: Int = 1900
        const val AV_TRANSPORT_SERVICE: String = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val MAX_DESCRIPTION_XML_CHARS: Int = 512 * 1024

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
            return """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                  <s:Body>
                    <u:SetAVTransportURI xmlns:u="$AV_TRANSPORT_SERVICE">
                      <InstanceID>0</InstanceID>
                      <CurrentURI>$safeUrl</CurrentURI>
                      <CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"&gt;&lt;item id="0" parentID="-1" restricted="1"&gt;&lt;dc:title&gt;$safeTitle&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
                    </u:SetAVTransportURI>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()
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
            val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
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
