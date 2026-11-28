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
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * Runs [block] under [lock], then delivers any epoch-move notifications **after** the monitor
     * is released. Non-local returns still traverse the `finally`, so early-exit "nothing changed"
     * paths never fire listeners either.
     *
     * Nesting guard: a mutation invoked from inside another mutation (e.g. `storeSessionSnapshot`
     * calling through a default interface implementation) must not dispatch while the outer call
     * still holds [lock] — the outermost invocation delivers.
     */
    private inline fun <T> mutateLocked(block: () -> T): T {
        val outermost = mutationDepth.get() == 0
        mutationDepth.set(mutationDepth.get() + 1)
        try {
            return synchronized(lock, block)
        } finally {
            mutationDepth.set(mutationDepth.get() - 1)
            if (outermost) dispatchPendingEpochListeners()
        }
    }

    private val mutationDepth = ThreadLocal.withInitial { 0 }

    override fun getCookies(siteId: SiteId): Map<String, String> = synchronized(lock) {
        readRecordLocked(siteId.accountId).cookies.toMap()
    }

    override fun saveCookies(siteId: SiteId, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        val key = siteId.accountId
        mutateLocked {
            val current = readRecordLocked(key)
            val merged = LinkedHashMap(current.cookies)
            var changed = false
            for ((name, value) in cookies) {
                if (merged.put(name, value) != value) changed = true
            }
            // Renewal of an unchanged Set-Cookie is NOT an auth mutation: keeping the epoch stable
            // is what lets in-flight authenticated requests survive routine session refresh.
            if (!changed) return
            writeRecordLocked(key, current.copy(cookies = merged))
            bumpEpochLocked(key)
        }
    }

    override fun removeCookies(siteId: SiteId, names: Set<String>) {
        if (names.isEmpty()) return
        val key = siteId.accountId
        mutateLocked {
            val current = readRecordLocked(key)
            val remaining = LinkedHashMap(current.cookies)
            var removedAny = false
            for (name in names) {
                if (remaining.remove(name) != null) removedAny = true
            }
            if (removedAny) {
                writeRecordLocked(key, current.copy(cookies = remaining))
                bumpEpochLocked(key)
            }
        }
    }

    override fun replaceCookies(siteId: SiteId, cookies: Map<String, String>) {
        val key = siteId.accountId
        mutateLocked {
            val current = readRecordLocked(key)
            if (current.cookies == cookies) return
            writeRecordLocked(key, current.copy(cookies = LinkedHashMap(cookies)))
            bumpEpochLocked(key)
        }
    }

    override fun getBearerToken(siteId: SiteId): String? = synchronized(lock) {
        readRecordLocked(siteId.accountId).bearerToken
    }

    override fun saveBearerToken(siteId: SiteId, token: String, username: String?) {
        val key = siteId.accountId
        mutateLocked {
            val current = readRecordLocked(key)
            val updated = current.copy(
                bearerToken = token,
                username = username ?: current.username,
            )
            writeRecordLocked(key, updated)
            bumpEpochLocked(key)
        }
    }

    override fun getUsername(siteId: SiteId): String? = synchronized(lock) {
        readRecordLocked(siteId.accountId).username
    }

    override fun saveUsername(siteId: SiteId, username: String?) {
        val key = siteId.accountId
        synchronized(lock) {
            val current = readRecordLocked(key)
            val normalized = username?.takeIf { it.isNotEmpty() }
            if (current.username == normalized) return
            writeRecordLocked(key, current.copy(username = normalized))
            // Username is descriptive only (never a credential), so it must not cancel in-flight requests.
        }
    }

    override fun getSecret(siteId: SiteId, key: String): String? = synchronized(lock) {
        readRecordLocked(siteId.accountId).secrets[key]
    }

    override fun saveSecret(siteId: SiteId, key: String, value: String?) {
        val accountKey = siteId.accountId
        mutateLocked {
            val current = readRecordLocked(accountKey)
            val nextSecrets = LinkedHashMap(current.secrets)
            val changed = if (value == null) {
                nextSecrets.remove(key) != null
            } else {
                nextSecrets[key] = value
                true
            }
            if (changed) {
                writeRecordLocked(accountKey, current.copy(secrets = nextSecrets))
                bumpEpochLocked(accountKey)
            }
        }
    }

    /**
     * Compare-and-write under the SAME [lock] that guards [clearSite] / [saveBearerToken]:
     * the epoch check and the sealed write cannot be split by a concurrent logout.
     */
    override fun storeSessionSnapshotIfEpoch(
        siteId: SiteId,
        expectedEpoch: Long,
        cookies: Map<String, String>,
        secrets: Map<String, String?>,
        bumpEpoch: Boolean,
    ): Boolean {
        val key = siteId.accountId
        return mutateLocked {
            val currentEpoch = epochFlows[key]?.value ?: 0L
            if (currentEpoch != expectedEpoch) return@mutateLocked false
            writeSnapshotLocked(key, cookies, secrets, bumpEpoch)
            true
        }
    }

    override fun updateSessionSnapshotWithoutEpochBump(
        siteId: SiteId,
        cookies: Map<String, String>,
        secrets: Map<String, String?>,
    ) {
        storeSessionSnapshot(siteId, cookies, secrets, bumpEpoch = false)
    }

    /**
     * Single sealed record write for cookie summary + secrets. [bumpEpoch] = false is the
     * expiry-renewal path: the epoch must stay put or every routine `Set-Cookie` refresh would
     * cancel authenticated requests that are already in flight.
     */
    override fun storeSessionSnapshot(
        siteId: SiteId,
        cookies: Map<String, String>,
        secrets: Map<String, String?>,
        bumpEpoch: Boolean,
    ) {
        val key = siteId.accountId
        mutateLocked {
            writeSnapshotLocked(key, cookies, secrets, bumpEpoch)
        }
    }

    /** Requires [lock] held by the caller. */
    private fun writeSnapshotLocked(
        key: SiteId,
        cookies: Map<String, String>,
        secrets: Map<String, String?>,
        bumpEpoch: Boolean,
    ) {
        val current = readRecordLocked(key)
        val nextCookies = LinkedHashMap(cookies)
        val nextSecrets = LinkedHashMap(current.secrets)
        for ((secretKey, value) in secrets) {
            if (value == null) nextSecrets.remove(secretKey) else nextSecrets[secretKey] = value
        }
        val updated = current.copy(cookies = nextCookies, secrets = nextSecrets)
        if (updated == current) return
        writeRecordLocked(key, updated)
        if (bumpEpoch) bumpEpochLocked(key)
    }

    override fun clearSite(siteId: SiteId) {
        val key = siteId.accountId
        mutateLocked {
            recordCache.remove(key)
            removeAllStorageKeysLocked(key)
            bumpEpochLocked(key)
        }
    }

    override fun authEpoch(siteId: SiteId): Long = synchronized(lock) {
        epochFlows[siteId.accountId]?.value ?: 0L
    }

    fun authEpochFlow(siteId: SiteId): StateFlow<Long> = synchronized(lock) {
        epochFlowLocked(siteId.accountId).asStateFlow()
    }

    fun hasAnySession(siteId: SiteId): Boolean = synchronized(lock) {
        !readRecordLocked(siteId.accountId).isEmpty
    }

    override fun hasSession(siteId: SiteId): Boolean = hasAnySession(siteId)

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
                if (rawId.isNotBlank()) sitesToBump.add(SiteId.of(rawId).accountId)
            }
            recordCache.clear()
            storage.clear()
            for (siteId in sitesToBump) {
                bumpEpochLocked(siteId)
            }
        }
        // bumpEpochLocked queued every moved account; deliver them now that the lock is released.
        dispatchPendingEpochListeners()
    }

    private fun readRecordLocked(siteId: SiteId): SiteVaultRecord {
        val accountId = siteId.accountId
        recordCache[accountId]?.let { return it }
        // Legacy migration: older builds keyed ExHentai under its own id (and sealed it with that
        // id's AAD) while EH/EX share one login. Probe every hostname id of the account, decrypting
        // with the AAD each record was written under.
        val candidates = legacyStorageKeys(accountId)
        for (keyOwner in candidates) {
            val encoded = storage.getString(storageKey(keyOwner)) ?: continue
            val plaintext = open(encoded, associatedData = aadFor(keyOwner))
            val decoded = try {
                json.decodeFromString<SiteVaultRecord>(plaintext)
            } catch (e: Exception) {
                throw SessionVaultException("Corrupted vault record for site '${accountId.value}'", e)
            }
            recordCache[accountId] = decoded
            return decoded
        }
        return SiteVaultRecord()
    }

    /** Storage-key owners that may hold a legacy record for [accountId], account key first. */
    private fun legacyStorageKeys(accountId: SiteId): List<SiteId> = when (accountId) {
        SiteId.EHENTAI -> listOf(SiteId.EHENTAI, SiteId.EXHENTAI)
        else -> listOf(accountId)
    }

    private fun writeRecordLocked(siteId: SiteId, record: SiteVaultRecord) {
        val accountId = siteId.accountId
        if (record.isEmpty) {
            recordCache.remove(accountId)
            removeAllStorageKeysLocked(accountId)
            return
        }
        val payload = json.encodeToString(record)
        val sealed = seal(payload, associatedData = aadFor(accountId))
        storage.putString(storageKey(accountId), sealed)
        recordCache[accountId] = record
        // Drop legacy per-host duplicates (sealed under their own AAD) so a later clearance cannot
        // leave a stale record behind that a future read would resurrect.
        for (owner in legacyStorageKeys(accountId)) {
            if (owner != accountId) storage.remove(storageKey(owner))
        }
    }

    private fun removeAllStorageKeysLocked(accountId: SiteId) {
        for (owner in legacyStorageKeys(accountId)) {
            storage.remove(storageKey(owner))
        }
    }

    private fun epochFlowLocked(siteId: SiteId): MutableStateFlow<Long> =
        epochFlows.getOrPut(siteId) { MutableStateFlow(0L) }

    private fun bumpEpochLocked(siteId: SiteId): Long {
        val flow = epochFlowLocked(siteId)
        val next = flow.value + 1L
        flow.value = next
        // Collect the listener to run AFTER the lock is released: listeners evict caches and may
        // re-enter the vault, which would deadlock under the same monitor.
        pendingEpochListeners.add(siteId)
        return next
    }

    /** Listeners notified after an account's epoch moved (login / logout / credential change). */
    private val accountChangeListeners = CopyOnWriteArrayList<(SiteId) -> Unit>()
    private val pendingEpochListeners = LinkedHashSet<SiteId>()

    /**
     * Registers a callback invoked whenever [siteId]'s auth epoch moves, so account-derived caches
     * (signed media URLs, URL-keyed HTTP response bodies) can be dropped. Registered by
     * `:core:network` at wiring time; kept here so every plugin login/import/logout path is covered
     * without each plugin knowing about the network layer.
     */
    fun addAccountChangeListener(listener: (SiteId) -> Unit) {
        accountChangeListeners.add(listener)
    }

    private fun dispatchPendingEpochListeners() {
        val moved: List<SiteId>
        synchronized(lock) {
            if (pendingEpochListeners.isEmpty()) return
            moved = pendingEpochListeners.toList()
            pendingEpochListeners.clear()
        }
        for (accountId in moved) {
            for (listener in accountChangeListeners) {
                runCatching { listener(accountId) }
            }
        }
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
