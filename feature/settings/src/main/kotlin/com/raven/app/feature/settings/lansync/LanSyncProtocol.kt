package com.raven.app.feature.settings.lansync

import com.raven.app.core.database.model.BackupSummary as DatabaseBackupSummary
import com.raven.app.core.model.RavenBackupPayload
import kotlinx.serialization.Serializable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

enum class LanSyncMode(
    val displayLabel: String,
    val description: String,
) {
    TWO_WAY(
        displayLabel = "双向合并",
        description = "互相交换最新进度、收藏、片单与标签词典，双方合并至完整并集",
    ),
    PUSH(
        displayLabel = "推送到对方",
        description = "仅将本机数据合并到对方设备，本机保持不变",
    ),
    PULL(
        displayLabel = "拉取到本机",
        description = "从对方拉取快照并先预览摘要，确认后再合并到本机",
    ),
}

enum class LanAuthMode {
    PIN,
    TRUSTED,
}

enum class LanDiscoverySource(val badgeLabel: String) {
    NSD("mDNS 自动发现"),
    UDP_BROADCAST("局域网广播"),
    MANUAL("手动连接"),
}

enum class LanSyncErrorCode {
    INVALID_PROTOCOL,
    AUTH_FAILED,
    NOT_TRUSTED,
    RATE_LIMITED,
    PAYLOAD_TOO_LARGE,
    VALIDATION_FAILED,
    INTERNAL_ERROR,
}

data class DiscoveredLanPeer(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val appVersion: String,
    val fingerprint: String,
    val source: LanDiscoverySource,
    val lastSeenAtMs: Long,
    val isTrusted: Boolean = false,
    val lastSyncedAtMs: Long = 0L,
)

class LanSyncException(
    val errorCode: LanSyncErrorCode? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

@Serializable
data class BackupSummarySnapshot(
    val watchHistoryCount: Int = 0,
    val readingHistoryCount: Int = 0,
    val favoritesCount: Int = 0,
    val shelvesCount: Int = 0,
    val shelfItemsCount: Int = 0,
    val smartShelvesCount: Int = 0,
    val pageBookmarksCount: Int = 0,
    val timeBookmarksCount: Int = 0,
    val imageFavoritesCount: Int = 0,
    val tagSubscriptionsCount: Int = 0,
    val seriesFollowsCount: Int = 0,
    val savedSearchesCount: Int = 0,
    val updateInboxCount: Int = 0,
    val blockedTermsCount: Int = 0,
    val searchHistoryCount: Int = 0,
    val customTagTranslationsCount: Int = 0,
) {
    val totalHistoryCount: Int get() = watchHistoryCount + readingHistoryCount

    fun toDatabaseSummary(exportedAtMs: Long, appVersion: String): DatabaseBackupSummary =
        DatabaseBackupSummary(
            schemaVersion = RavenBackupPayload.CURRENT_SCHEMA_VERSION,
            exportedAt = exportedAtMs,
            appVersion = appVersion,
            watchHistoryCount = watchHistoryCount,
            readingHistoryCount = readingHistoryCount,
            favoritesCount = favoritesCount,
            shelvesCount = shelvesCount,
            shelfItemsCount = shelfItemsCount,
            smartShelvesCount = smartShelvesCount,
            pageBookmarksCount = pageBookmarksCount,
            timeBookmarksCount = timeBookmarksCount,
            imageFavoritesCount = imageFavoritesCount,
            tagSubscriptionsCount = tagSubscriptionsCount,
            seriesFollowsCount = seriesFollowsCount,
            savedSearchesCount = savedSearchesCount,
            updateInboxCount = updateInboxCount,
            blockedTermsCount = blockedTermsCount,
            searchHistoryCount = searchHistoryCount,
            customTagTranslationsCount = customTagTranslationsCount,
        )

    companion object {
        fun from(summary: DatabaseBackupSummary): BackupSummarySnapshot = BackupSummarySnapshot(
            watchHistoryCount = summary.watchHistoryCount,
            readingHistoryCount = summary.readingHistoryCount,
            favoritesCount = summary.favoritesCount,
            shelvesCount = summary.shelvesCount,
            shelfItemsCount = summary.shelfItemsCount,
            smartShelvesCount = summary.smartShelvesCount,
            pageBookmarksCount = summary.pageBookmarksCount,
            timeBookmarksCount = summary.timeBookmarksCount,
            imageFavoritesCount = summary.imageFavoritesCount,
            tagSubscriptionsCount = summary.tagSubscriptionsCount,
            seriesFollowsCount = summary.seriesFollowsCount,
            savedSearchesCount = summary.savedSearchesCount,
            updateInboxCount = summary.updateInboxCount,
            blockedTermsCount = summary.blockedTermsCount,
            searchHistoryCount = summary.searchHistoryCount,
            customTagTranslationsCount = summary.customTagTranslationsCount,
        )
    }
}

@Serializable
data class LanSyncBeacon(
    val magic: String = BEACON_MAGIC,
    val version: Int = PROTOCOL_VERSION,
    val kind: String = KIND_ANNOUNCE,
    val deviceId: String,
    val deviceName: String,
    val appVersion: String = "",
    val tcpPort: Int,
) {
    fun encode(): ByteArray =
        LanSyncJson.codec.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        const val BEACON_MAGIC: String = "RVNSYNC1"
        const val KIND_ANNOUNCE: String = "ANNOUNCE"
        const val KIND_PROBE: String = "PROBE"
        const val MAX_BEACON_BYTES: Int = 1024

        fun decode(bytes: ByteArray, length: Int): LanSyncBeacon? {
            if (length <= 0 || length > MAX_BEACON_BYTES || length > bytes.size) return null
            return runCatching {
                val raw = String(bytes, 0, length, Charsets.UTF_8)
                val beacon = LanSyncJson.codec.decodeFromString(serializer(), raw)
                if (beacon.magic != BEACON_MAGIC) return null
                if (beacon.version != PROTOCOL_VERSION) return null
                if (beacon.kind != KIND_ANNOUNCE && beacon.kind != KIND_PROBE) return null
                if (beacon.deviceId.isBlank() || beacon.deviceId.length > 64) return null
                if (beacon.kind == KIND_ANNOUNCE && beacon.tcpPort !in 1024..65535) return null
                beacon.copy(
                    deviceName = beacon.deviceName.trim().take(32).ifBlank { "Raven 设备" },
                    appVersion = beacon.appVersion.trim().take(32),
                )
            }.getOrNull()
        }
    }
}

@Serializable
internal data class HelloPayload(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val clientDeviceId: String,
    val clientDeviceName: String,
    val clientAppVersion: String = "",
    val syncMode: LanSyncMode,
    val authMode: LanAuthMode,
    val rememberDevice: Boolean = true,
    val clientNonceBase64: String,
    val clientPublicKeyBase64: String,
)

@Serializable
internal data class HelloAckPayload(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val serverDeviceId: String,
    val serverDeviceName: String,
    val serverAppVersion: String = "",
    val serverNonceBase64: String,
    val serverPublicKeyBase64: String,
)

@Serializable
internal data class AuthConfirmPayload(
    val clientAuthTagBase64: String,
)

@Serializable
internal data class AuthOkPayload(
    val serverAuthTagBase64: String,
)

@Serializable
internal data class EncryptedSnapshotEnvelope(
    val ivBase64: String,
    val ciphertextBase64: String,
)

@Serializable
internal data class SyncAckPayload(
    val mergedSummary: BackupSummarySnapshot,
)

@Serializable
internal data class ErrorPayload(
    val code: LanSyncErrorCode,
    val message: String,
)

internal object LanSyncJson {
    val codec = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

object LanSyncConstants {
    const val PROTOCOL_VERSION: Int = 1
    const val NSD_SERVICE_TYPE: String = "_raven-sync._tcp."
    const val UDP_DISCOVERY_PORT: Int = 45873
    const val DEFAULT_TCP_PORT: Int = 45874
    const val MAX_HANDSHAKE_FRAME_BYTES: Int = 16 * 1024
    val MAX_ENCRYPTED_FRAME_BYTES: Int = RavenBackupPayload.MAX_PLAINTEXT_BYTES + 1024 * 1024
    const val SOCKET_CONNECT_TIMEOUT_MS: Int = 6_000
    const val SOCKET_READ_TIMEOUT_MS: Int = 25_000
}

internal const val PROTOCOL_VERSION: Int = LanSyncConstants.PROTOCOL_VERSION

object LanSyncFrameCodec {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'N'.code.toByte(), 'L'.code.toByte())
    private const val HEADER_BYTES = 10

    const val FRAME_HELLO: Byte = 1
    const val FRAME_HELLO_ACK: Byte = 2
    const val FRAME_AUTH_CONFIRM: Byte = 3
    const val FRAME_AUTH_OK: Byte = 4
    const val FRAME_DATA_SNAPSHOT: Byte = 5
    const val FRAME_SYNC_ACK: Byte = 6
    const val FRAME_ERROR: Byte = 7

    data class Frame(
        val type: Byte,
        val payload: ByteArray,
    )

    fun writeFrame(output: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_BYTES)
        header.put(MAGIC)
        header.put(PROTOCOL_VERSION.toByte())
        header.put(type)
        header.putInt(payload.size)
        output.write(header.array())
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    fun readFrame(input: InputStream, maxPayloadBytes: Int): Frame {
        val headerBytes = readExactly(input, HEADER_BYTES)
        for (i in MAGIC.indices) {
            if (headerBytes[i] != MAGIC[i]) {
                throw LanSyncException(
                    errorCode = LanSyncErrorCode.INVALID_PROTOCOL,
                    message = "对端协议标识无效，可能不是 Raven 同步服务",
                )
            }
        }
        val version = headerBytes[4].toInt() and 0xFF
        if (version != PROTOCOL_VERSION) {
            throw LanSyncException(
                errorCode = LanSyncErrorCode.INVALID_PROTOCOL,
                message = "同步协议版本不兼容 (v$version)",
            )
        }
        val type = headerBytes[5]
        val length = ByteBuffer.wrap(headerBytes, 6, 4).int
        if (length < 0 || length > maxPayloadBytes) {
            throw LanSyncException(
                errorCode = LanSyncErrorCode.PAYLOAD_TOO_LARGE,
                message = "同步数据帧体积超限 ($length 字节)",
            )
        }
        val payload = if (length == 0) ByteArray(0) else readExactly(input, length)
        return Frame(type = type, payload = payload)
    }

    private fun readExactly(input: InputStream, expectedBytes: Int): ByteArray {
        val buffer = ByteArray(expectedBytes)
        var offset = 0
        while (offset < expectedBytes) {
            val read = input.read(buffer, offset, expectedBytes - offset)
            if (read < 0) {
                throw EOFException("局域网连接在对端完成发送前已断开")
            }
            offset += read
        }
        return buffer
    }
}

object LanSyncAddressValidator {

    fun isAllowedLanIpv4(host: String, allowLoopback: Boolean = false): Boolean {
        val normalized = host.trim()
        val parts = normalized.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        if (allowLoopback && normalized == "127.0.0.1") return true
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            else -> false
        }
    }

    fun parseManualEndpoint(
        raw: String,
        defaultPort: Int = LanSyncConstants.DEFAULT_TCP_PORT,
        allowLoopback: Boolean = false,
    ): Pair<String, Int>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains("://") || trimmed.any { it.isWhitespace() }) {
            return null
        }
        val colonIndex = trimmed.indexOf(':')
        val host: String
        val port: Int
        if (colonIndex < 0) {
            host = trimmed
            port = defaultPort
        } else {
            if (trimmed.indexOf(':', colonIndex + 1) >= 0) return null
            host = trimmed.substring(0, colonIndex).trim()
            val parsedPort = trimmed.substring(colonIndex + 1).trim().toIntOrNull() ?: return null
            if (parsedPort !in 1024..65535) return null
            port = parsedPort
        }
        if (!isAllowedLanIpv4(host, allowLoopback)) return null
        return host to port
    }

    fun enumerateLocalLanIpv4Addresses(): List<String> = runCatching {
        val result = LinkedHashSet<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) continue
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address) {
                    val host = addr.hostAddress.orEmpty()
                    if (isAllowedLanIpv4(host)) {
                        result.add(host)
                    }
                }
            }
        }
        result.toList()
    }.getOrDefault(emptyList())

    fun enumerateBroadcastAddresses(): List<InetAddress> = runCatching {
        val result = LinkedHashMap<String, InetAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) continue
            for (ifaceAddr in iface.interfaceAddresses) {
                val broadcast = ifaceAddr.broadcast ?: continue
                if (broadcast is Inet4Address) {
                    val key = broadcast.hostAddress.orEmpty()
                    if (key.isNotBlank()) {
                        result[key] = broadcast
                    }
                }
            }
        }
        val globalBroadcast = InetAddress.getByName("255.255.255.255")
        result["255.255.255.255"] = globalBroadcast
        result.values.toList()
    }.getOrDefault(emptyList())
}
