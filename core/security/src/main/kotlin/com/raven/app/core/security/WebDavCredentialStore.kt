package com.raven.app.core.security

import android.content.Context
import com.raven.app.core.common.UrlSanitizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WebDavConfig(
    val endpointUrl: String,
    val username: String,
    val password: String,
    val backupPassphrase: String,
    val remoteFileName: String = DEFAULT_REMOTE_FILE_NAME,
    val autoSyncEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_REMOTE_FILE_NAME: String = "raven-sync.rvnbk"
    }
}

/**
 * Hardware/KeyStore AES-256-GCM encrypted store for WebDAV sync credentials and backup passphrase.
 * Strictly requires `https://` endpoints and is never exported in `RavenBackupPayload`.
 */
class WebDavCredentialStore(
    private val cipher: VaultCipher,
    private val storage: VaultStorage,
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

    fun hasConfig(): Boolean = synchronized(lock) {
        storage.getString(KEY_CONFIG) != null
    }

    fun load(): WebDavConfig? = synchronized(lock) {
        val encoded = storage.getString(KEY_CONFIG) ?: return@synchronized null
        val sealed = try {
            Base64Codec.decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw SessionVaultException("WebDAV vault payload is not valid Base64", e)
        }
        val plain = cipher.decrypt(sealed, AAD_BYTES)
        try {
            json.decodeFromString<WebDavConfig>(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SessionVaultException("Corrupted WebDAV config record", e)
        } finally {
            plain.fill(0)
        }
    }

    fun save(config: WebDavConfig): WebDavConfig {
        val normalized = validateAndNormalize(config)
        val plain = json.encodeToString(normalized).toByteArray(Charsets.UTF_8)
        try {
            val sealed = cipher.encrypt(plain, AAD_BYTES)
            val encoded = Base64Codec.encode(sealed)
            synchronized(lock) {
                storage.putString(KEY_CONFIG, encoded)
            }
        } finally {
            plain.fill(0)
        }
        return normalized
    }

    fun clear() {
        synchronized(lock) {
            storage.remove(KEY_CONFIG)
        }
    }

    companion object {
        const val DEFAULT_PREFS_NAME: String = "raven_webdav_vault_v1"
        private const val KEY_CONFIG: String = "webdav:config:v1"
        private val AAD_BYTES: ByteArray = "raven-webdav-config:v1".toByteArray(Charsets.UTF_8)
        private val SAFE_FILENAME_REGEX = Regex("^[A-Za-z0-9._-]{1,120}$")

        fun validateAndNormalize(config: WebDavConfig): WebDavConfig {
            val trimmedUrl = config.endpointUrl.trim().trimEnd('/')
            require(UrlSanitizer.isHttpsUrl(trimmedUrl)) {
                "WebDAV 服务器地址必须使用安全的 HTTPS 协议"
            }
            val username = config.username.trim()
            require(username.isNotEmpty()) { "WebDAV 用户名不能为空" }
            require(config.password.isNotEmpty()) { "WebDAV 密码不能为空" }
            require(config.backupPassphrase.length >= BackupCodec.MIN_PASSWORD_LENGTH) {
                "云端同步加密口令长度至少需要 ${BackupCodec.MIN_PASSWORD_LENGTH} 个字符"
            }
            val fileName = config.remoteFileName.trim().ifBlank { WebDavConfig.DEFAULT_REMOTE_FILE_NAME }
            require(SAFE_FILENAME_REGEX.matches(fileName) && !fileName.contains("..")) {
                "远端备份文件名仅允许字母、数字、点、下划线与连字符"
            }
            return config.copy(
                endpointUrl = trimmedUrl,
                username = username,
                remoteFileName = fileName,
            )
        }
    }
}
