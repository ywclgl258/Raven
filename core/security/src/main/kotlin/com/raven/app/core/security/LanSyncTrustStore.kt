package com.raven.app.core.security

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom

@Serializable
data class TrustedLanPeer(
    val deviceId: String,
    val deviceName: String,
    val sharedSecretBase64: String,
    val pairedAtMs: Long,
    val lastSyncedAtMs: Long,
    val lastKnownHost: String = "",
    val lastKnownPort: Int = 0,
) {
    fun decodeSharedSecret(): ByteArray {
        val decoded = Base64Codec.decode(sharedSecretBase64)
        require(decoded.size == LanSyncCrypto.KEY_BYTES) {
            "Corrupted trusted peer secret length"
        }
        return decoded
    }
}

@Serializable
data class LanSyncTrustState(
    val localDeviceId: String,
    val localDeviceName: String,
    val trustedPeers: List<TrustedLanPeer> = emptyList(),
)

/**
 * Hardware/KeyStore AES-256-GCM encrypted store for the local LAN sync identity and trusted
 * paired peers. Strictly stored outside Room/backups so pairing secrets are never exported.
 */
class LanSyncTrustStore(
    private val cipher: VaultCipher,
    private val storage: VaultStorage,
    private val random: SecureRandom = SecureRandom(),
) {
    constructor(
        context: Context,
        prefsName: String = DEFAULT_PREFS_NAME,
    ) : this(
        cipher = AndroidKeyStoreVaultCipher(),
        storage = SharedPreferencesVaultStorage(
            context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        ),
    )

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun loadOrCreate(defaultDeviceName: String = DEFAULT_DEVICE_NAME): LanSyncTrustState = synchronized(lock) {
        val existing = loadLocked()
        if (existing != null) return@synchronized existing
        val created = LanSyncTrustState(
            localDeviceId = LanSyncCrypto.generateDeviceId(random),
            localDeviceName = normalizeDeviceName(defaultDeviceName),
            trustedPeers = emptyList(),
        )
        saveLocked(created)
        created
    }

    fun updateLocalDeviceName(newName: String): LanSyncTrustState = synchronized(lock) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "设备名称不能为空" }
        require(trimmed.length <= MAX_DEVICE_NAME_LENGTH) {
            "设备名称不能超过 $MAX_DEVICE_NAME_LENGTH 个字符"
        }
        val current = loadLocked() ?: loadOrCreate(trimmed)
        val updated = current.copy(localDeviceName = trimmed)
        saveLocked(updated)
        updated
    }

    fun findTrustedPeer(deviceId: String): TrustedLanPeer? = synchronized(lock) {
        loadLocked()?.trustedPeers?.firstOrNull { it.deviceId == deviceId }
    }

    fun saveTrustedPeer(
        deviceId: String,
        deviceName: String,
        sharedSecretBytes: ByteArray,
        host: String = "",
        port: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
    ): LanSyncTrustState = synchronized(lock) {
        require(deviceId.isNotBlank()) { "对端设备 ID 不能为空" }
        require(sharedSecretBytes.size == LanSyncCrypto.KEY_BYTES) {
            "共享密钥必须为 ${LanSyncCrypto.KEY_BYTES} 字节"
        }
        val current = loadLocked() ?: loadOrCreate()
        val encodedSecret = Base64Codec.encode(sharedSecretBytes)
        val existing = current.trustedPeers.firstOrNull { it.deviceId == deviceId }
        val entry = TrustedLanPeer(
            deviceId = deviceId,
            deviceName = normalizeDeviceName(deviceName),
            sharedSecretBase64 = encodedSecret,
            pairedAtMs = existing?.pairedAtMs ?: nowMs,
            lastSyncedAtMs = nowMs,
            lastKnownHost = host.trim(),
            lastKnownPort = port.coerceAtLeast(0),
        )
        val updatedList = current.trustedPeers
            .filterNot { it.deviceId == deviceId }
            .plus(entry)
            .sortedByDescending { it.lastSyncedAtMs }
            .take(MAX_TRUSTED_PEERS)
        val updated = current.copy(trustedPeers = updatedList)
        saveLocked(updated)
        updated
    }

    fun recordSyncSuccess(
        deviceId: String,
        deviceName: String,
        host: String = "",
        port: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
    ): LanSyncTrustState = synchronized(lock) {
        val current = loadLocked() ?: loadOrCreate()
        val existing = current.trustedPeers.firstOrNull { it.deviceId == deviceId } ?: return@synchronized current
        val updatedEntry = existing.copy(
            deviceName = normalizeDeviceName(deviceName.ifBlank { existing.deviceName }),
            lastSyncedAtMs = nowMs,
            lastKnownHost = host.trim().ifBlank { existing.lastKnownHost },
            lastKnownPort = if (port > 0) port else existing.lastKnownPort,
        )
        val updated = current.copy(
            trustedPeers = (current.trustedPeers.filterNot { it.deviceId == deviceId } + updatedEntry)
                .sortedByDescending { it.lastSyncedAtMs },
        )
        saveLocked(updated)
        updated
    }

    fun removeTrustedPeer(deviceId: String): LanSyncTrustState = synchronized(lock) {
        val current = loadLocked() ?: loadOrCreate()
        val updated = current.copy(trustedPeers = current.trustedPeers.filterNot { it.deviceId == deviceId })
        saveLocked(updated)
        updated
    }

    fun clearAllTrustedPeers(): LanSyncTrustState = synchronized(lock) {
        val current = loadLocked() ?: loadOrCreate()
        val updated = current.copy(trustedPeers = emptyList())
        saveLocked(updated)
        updated
    }

    private fun loadLocked(): LanSyncTrustState? {
        val encoded = storage.getString(KEY_STATE) ?: return null
        val sealed = try {
            Base64Codec.decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw SessionVaultException("LAN sync trust payload is not valid Base64", e)
        }
        val plain = cipher.decrypt(sealed, AAD_BYTES)
        return try {
            json.decodeFromString<LanSyncTrustState>(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SessionVaultException("Corrupted LAN sync trust state", e)
        } finally {
            plain.fill(0)
        }
    }

    private fun saveLocked(state: LanSyncTrustState) {
        val plain = json.encodeToString(state).toByteArray(Charsets.UTF_8)
        try {
            val sealed = cipher.encrypt(plain, AAD_BYTES)
            storage.putString(KEY_STATE, Base64Codec.encode(sealed))
        } finally {
            plain.fill(0)
        }
    }

    companion object {
        const val DEFAULT_PREFS_NAME: String = "raven_lan_sync_vault_v1"
        const val DEFAULT_DEVICE_NAME: String = "Raven 设备"
        const val MAX_DEVICE_NAME_LENGTH: Int = 32
        const val MAX_TRUSTED_PEERS: Int = 32
        private const val KEY_STATE: String = "lan_sync:state:v1"
        private val AAD_BYTES: ByteArray = "raven-lan-sync-trust:v1".toByteArray(Charsets.UTF_8)

        fun normalizeDeviceName(raw: String): String =
            raw.trim()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .take(MAX_DEVICE_NAME_LENGTH)
                .ifBlank { DEFAULT_DEVICE_NAME }
    }
}
