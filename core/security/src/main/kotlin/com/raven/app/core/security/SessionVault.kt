package com.raven.app.core.security

import android.content.Context
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Unified per-site encrypted credential & session vault implementing [SiteSessionStore].
 *
 * Encrypts ALL site cookies, Bearer JWTs, usernames, and secrets across ALL auth-capable sites
 * (`r34v`, `hanime1`, `iwara`, `ehentai`, `exhentai`, and any future plugin) with AES-256-GCM
 * (12-byte IV, 128-bit auth tag, per-site AAD binding) and **zero plaintext fallback**.
 *
 * Maintains a monotonically increasing per-site [authEpoch] so in-flight authenticated requests
 * can cancel or reject stale responses whenever login/logout/cookie state changes.
 */
class SessionVault(
    private val cipher: VaultCipher,
    private val storage: VaultStorage,
) : SiteSessionStore {

    constructor(
        context: Context,
        prefsName: String = DEFAULT_PREFS_NAME,
        keyAlias: String = AndroidKeyStoreVaultCipher.DEFAULT_KEY_ALIAS,
    ) : this(
        cipher = AndroidKeyStoreVaultCipher(keyAlias),
        storage = SharedPreferencesVaultStorage(
            context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        ),
    )

    @Serializable
    private data class SiteVaultRecord(
        val cookies: Map<String, String> = emptyMap(),
        val bearerToken: String? = null,
        val username: String? = null,
        val secrets: Map<String, String> = emptyMap(),
    ) {
        val isEmpty: Boolean
            get() = cookies.isEmpty() && bearerToken == null && username == null && secrets.isEmpty()
    }

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val recordCache = HashMap<SiteId, SiteVaultRecord>()
    private val epochFlows = HashMap<SiteId, MutableStateFlow<Long>>()

    /**
     * Encrypts [plaintext] directly with AES-256-GCM and optional [associatedData], returning Base64.
     * Never falls back to plaintext.
     */
    fun seal(plaintext: String, associatedData: String = ""): String {
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        val aadBytes = associatedData.toByteArray(Charsets.UTF_8)
        return try {
            Base64Codec.encode(cipher.encrypt(plainBytes, aadBytes))
        } finally {
            plainBytes.fill(0)
        }
    }

    /**
     * Authenticates and decrypts a Base64-encoded AES-256-GCM payload produced by [seal].
     * Throws [SessionVaultException] on tampering, truncation, key mismatch, or AAD mismatch.
     */
    fun open(sealed: String, associatedData: String = ""): String {
        val raw = try {
            Base64Codec.decode(sealed)
        } catch (e: IllegalArgumentException) {
            throw SessionVaultException("Vault payload is not valid Base64", e)
        }
        val aadBytes = associatedData.toByteArray(Charsets.UTF_8)
        val decrypted = cipher.decrypt(raw, aadBytes)
        return try {
            String(decrypted, Charsets.UTF_8)
        } finally {
            decrypted.fill(0)
        }
    }

    override fun getCookies(siteId: SiteId): Map<String, String> = synchronized(lock) {
        readRecordLocked(siteId).cookies.toMap()
    }

    override fun saveCookies(siteId: SiteId, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val current = readRecordLocked(siteId)
            val merged = LinkedHashMap(current.cookies)
            merged.putAll(cookies)
            writeRecordLocked(siteId, current.copy(cookies = merged))
            bumpEpochLocked(siteId)
        }
    }

    override fun removeCookies(siteId: SiteId, names: Set<String>) {
        if (names.isEmpty()) return
        synchronized(lock) {
            val current = readRecordLocked(siteId)
            val remaining = LinkedHashMap(current.cookies)
            var removedAny = false
            for (name in names) {
                if (remaining.remove(name) != null) removedAny = true
            }
            if (removedAny) {
                writeRecordLocked(siteId, current.copy(cookies = remaining))
                bumpEpochLocked(siteId)
            }
        }
    }

    override fun replaceCookies(siteId: SiteId, cookies: Map<String, String>) {
        synchronized(lock) {
            val current = readRecordLocked(siteId)
            if (current.cookies == cookies) return
            writeRecordLocked(siteId, current.copy(cookies = LinkedHashMap(cookies)))
            bumpEpochLocked(siteId)
        }
    }

    override fun getBearerToken(siteId: SiteId): String? = synchronized(lock) {
        readRecordLocked(siteId).bearerToken
    }

    override fun saveBearerToken(siteId: SiteId, token: String, username: String?) {
        synchronized(lock) {
            val current = readRecordLocked(siteId)
            val updated = current.copy(
                bearerToken = token,
                username = username ?: current.username,
            )
            writeRecordLocked(siteId, updated)
            bumpEpochLocked(siteId)
        }
    }

    override fun getUsername(siteId: SiteId): String? = synchronized(lock) {
        readRecordLocked(siteId).username
    }

    override fun saveUsername(siteId: SiteId, username: String?) {
        synchronized(lock) {
            val current = readRecordLocked(siteId)
            val normalized = username?.takeIf { it.isNotEmpty() }
            if (current.username == normalized) return
            writeRecordLocked(siteId, current.copy(username = normalized))
        }
    }

    override fun getSecret(siteId: SiteId, key: String): String? = synchronized(lock) {
        readRecordLocked(siteId).secrets[key]
    }

    override fun saveSecret(siteId: SiteId, key: String, value: String?) {
        synchronized(lock) {
            val current = readRecordLocked(siteId)
            val nextSecrets = LinkedHashMap(current.secrets)
            val changed = if (value == null) {
                nextSecrets.remove(key) != null
            } else {
                nextSecrets[key] = value
                true
            }
            if (changed) {
                writeRecordLocked(siteId, current.copy(secrets = nextSecrets))
                bumpEpochLocked(siteId)
            }
        }
    }

    override fun clearSite(siteId: SiteId) {
        synchronized(lock) {
            recordCache.remove(siteId)
            storage.remove(storageKey(siteId))
            bumpEpochLocked(siteId)
        }
    }

    override fun authEpoch(siteId: SiteId): Long = synchronized(lock) {
        epochFlows[siteId]?.value ?: 0L
    }

    fun authEpochFlow(siteId: SiteId): StateFlow<Long> = synchronized(lock) {
        epochFlowLocked(siteId).asStateFlow()
    }

    fun hasAnySession(siteId: SiteId): Boolean = synchronized(lock) {
        !readRecordLocked(siteId).isEmpty
    }

    /** Clears in-memory cache so the next read decrypts fresh ciphertext from [storage]. */
    fun evictMemoryCache() {
        synchronized(lock) {
            recordCache.clear()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            val sitesToBump = LinkedHashSet<SiteId>()
            sitesToBump.addAll(recordCache.keys)
            sitesToBump.addAll(epochFlows.keys)
            for (key in storage.keysWithPrefix(KEY_PREFIX)) {
                val rawId = key.removePrefix(KEY_PREFIX)
                if (rawId.isNotBlank()) sitesToBump.add(SiteId.of(rawId))
            }
            recordCache.clear()
            storage.clear()
            for (siteId in sitesToBump) {
                bumpEpochLocked(siteId)
            }
        }
    }

    private fun readRecordLocked(siteId: SiteId): SiteVaultRecord {
        recordCache[siteId]?.let { return it }
        val encoded = storage.getString(storageKey(siteId)) ?: return SiteVaultRecord()
        val plaintext = open(encoded, associatedData = aadFor(siteId))
        val decoded = try {
            json.decodeFromString<SiteVaultRecord>(plaintext)
        } catch (e: Exception) {
            throw SessionVaultException("Corrupted vault record for site '${siteId.value}'", e)
        }
        recordCache[siteId] = decoded
        return decoded
    }

    private fun writeRecordLocked(siteId: SiteId, record: SiteVaultRecord) {
        if (record.isEmpty) {
            recordCache.remove(siteId)
            storage.remove(storageKey(siteId))
            return
        }
        val payload = json.encodeToString(record)
        val sealed = seal(payload, associatedData = aadFor(siteId))
        storage.putString(storageKey(siteId), sealed)
        recordCache[siteId] = record
    }

    private fun epochFlowLocked(siteId: SiteId): MutableStateFlow<Long> =
        epochFlows.getOrPut(siteId) { MutableStateFlow(0L) }

    private fun bumpEpochLocked(siteId: SiteId): Long {
        val flow = epochFlowLocked(siteId)
        val next = flow.value + 1L
        flow.value = next
        return next
    }

    companion object {
        const val DEFAULT_PREFS_NAME: String = "raven_session_vault_v1"
        private const val KEY_PREFIX: String = "vault:v1:"
        private const val AAD_PREFIX: String = "raven-session-vault:v1:"

        /** All 5 auth-capable live sites protected by [SessionVault]. */
        val AUTH_CAPABLE_SITES: Set<SiteId> = setOf(
            SiteId.R34V,
            SiteId.HANIME1,
            SiteId.IWARA,
            SiteId.EHENTAI,
            SiteId.EXHENTAI,
        )

        internal fun storageKey(siteId: SiteId): String = "$KEY_PREFIX${siteId.value}"
        internal fun aadFor(siteId: SiteId): String = "$AAD_PREFIX${siteId.value}"
    }
}
