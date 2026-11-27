package com.raven.app.core.sitespi

import com.raven.app.core.model.SiteId

/**
 * Per-site encrypted credential & session store contract.
 * Implemented by `:core:security`'s `SessionVault` (AndroidKeyStore AES-256-GCM) in production
 * and by [InMemorySiteSessionStore] in pure-JVM tests.
 *
 * Identity rules (non-negotiable):
 * - Data is keyed by **[SiteId.accountId]**, never by the raw host identity. E-Hentai and ExHentai
 *   are one login (same `ipb_member_id` / `ipb_pass_hash` / `igneous`), so every method below resolves
 *   `siteId.accountId` internally: reading, writing, clearing and bumping the epoch for `exhentai`
 *   affects exactly the `ehentai` bucket. That keeps a single shared [authEpoch] across both hosts,
 *   which is what in-flight request epoch guards compare against.
 * - [authEpoch] increments monotonically on every credential *mutation* (login/logout/import/cookie
 *   value change). Pure expiry renewal of an unchanged cookie value is NOT a mutation and must not
 *   increment the epoch (see [updateSessionSnapshotWithoutEpochBump]) — otherwise every routine
 *   `Set-Cookie` refresh would cancel authenticated requests that are already in flight.
 */
interface SiteSessionStore {
    /** Returns all stored cookie key/value pairs for [siteId] (account identity). */
    fun getCookies(siteId: SiteId): Map<String, String>

    /** Merges [cookies] into the stored cookie bucket for [siteId] and increments [authEpoch]. */
    fun saveCookies(siteId: SiteId, cookies: Map<String, String>)

    /** Removes cookies whose keys are in [names] for [siteId] and increments [authEpoch] if any were removed. */
    fun removeCookies(siteId: SiteId, names: Set<String>)

    /** Atomically replaces the cookie set for [siteId] with [cookies]. */
    fun replaceCookies(siteId: SiteId, cookies: Map<String, String>) {
        val obsolete = getCookies(siteId).keys - cookies.keys
        if (obsolete.isNotEmpty()) removeCookies(siteId, obsolete)
        if (cookies.isNotEmpty()) saveCookies(siteId, cookies)
    }

    fun getBearerToken(siteId: SiteId): String?

    fun saveBearerToken(siteId: SiteId, token: String, username: String? = null)

    fun getUsername(siteId: SiteId): String?

    fun saveUsername(siteId: SiteId, username: String?)

    fun getSecret(siteId: SiteId, key: String): String?

    fun saveSecret(siteId: SiteId, key: String, value: String?)

    /**
     * Compare-and-write credential snapshot: writes [cookies]/[secrets] only if [authEpoch] is still
     * exactly [expectedEpoch], evaluated atomically with the write. Returns true when written.
     *
     * This is the ONLY correct way to apply a `Set-Cookie` that was observed by an in-flight request:
     * the epoch must have been captured BEFORE the request left (dispatch/hop), and the comparison must
     * happen under the same lock as [clearSite] / [saveBearerToken]. Otherwise a response that arrives
     * after a logout would read the *new* epoch, compare equal, and restore the cleared session.
     */
    fun storeSessionSnapshotIfEpoch(
        siteId: SiteId,
        expectedEpoch: Long,
        cookies: Map<String, String>,
        secrets: Map<String, String?>,
        bumpEpoch: Boolean = false,
    ): Boolean {
        if (authEpoch(siteId) != expectedEpoch) return false
        storeSessionSnapshot(siteId, cookies, secrets, bumpEpoch)
        return true
    }

    /**
     * Writes the plain cookie summary plus [secrets] (null value = delete) in ONE record write.
     *
     * [bumpEpoch] MUST be false when the cookie-name -> value summary is unchanged (expiry-only
     * renewal of a `Set-Cookie`) and true for a real credential mutation. Implementations SHOULD
     * override this as a single locked write; the default falls back to the mutating primitives.
     */
    fun storeSessionSnapshot(
        siteId: SiteId,
        cookies: Map<String, String>,
        secrets: Map<String, String?> = emptyMap(),
        bumpEpoch: Boolean = true,
    ) {
        if (!bumpEpoch) updateSessionSnapshotWithoutEpochBump(siteId, cookies, secrets)
        else {
            replaceCookies(siteId, cookies)
            for ((key, value) in secrets) saveSecret(siteId, key, value)
        }
    }

    /**
     * Writes the plain cookie summary plus [secrets] (null value = delete) in ONE atomic record
     * write **without** incrementing [authEpoch].
     *
     * Only valid when the cookie-name -> value summary is unchanged (expiry-only renewal of a
     * `Set-Cookie`); implementations SHOULD override this as a single locked write.
     */
    fun updateSessionSnapshotWithoutEpochBump(
        siteId: SiteId,
        cookies: Map<String, String>,
        secrets: Map<String, String?>,
    ) {
        replaceCookies(siteId, cookies)
        for ((key, value) in secrets) saveSecret(siteId, key, value)
    }

    /** Removes all cookies, tokens, usernames, and secrets belonging to [siteId] and increments [authEpoch]. */
    fun clearSite(siteId: SiteId)

    /** Monotonically increasing per-site auth mutation counter (shared by EH/EX via [SiteId.accountId]). */
    fun authEpoch(siteId: SiteId): Long

    /** True when [siteId] holds any cookie or bearer token, i.e. requests may be user-specific. */
    fun hasSession(siteId: SiteId): Boolean =
        getCookies(siteId).isNotEmpty() || !getBearerToken(siteId).isNullOrBlank()
}

/**
 * Thread-safe pure-JVM implementation of [SiteSessionStore] for unit tests and offline fixtures.
 */
class InMemorySiteSessionStore : SiteSessionStore {
    private class SiteBucket {
        val cookies = LinkedHashMap<String, String>()
        var bearerToken: String? = null
        var username: String? = null
        val secrets = LinkedHashMap<String, String>()
        var epoch: Long = 0L
    }

    private val lock = Any()
    private val buckets = HashMap<SiteId, SiteBucket>()

    // All keys normalize to accountId so EH/EX share one bucket and one epoch, exactly like SessionVault.
    private fun bucket(siteId: SiteId): SiteBucket = buckets.getOrPut(siteId.accountId) { SiteBucket() }

    override fun getCookies(siteId: SiteId): Map<String, String> = synchronized(lock) {
        buckets[siteId.accountId]?.cookies?.toMap().orEmpty()
    }

    override fun saveCookies(siteId: SiteId, cookies: Map<String, String>) {
        synchronized(lock) {
            if (cookies.isEmpty()) return
            val b = bucket(siteId)
            b.cookies.putAll(cookies)
            b.epoch++
        }
    }

    override fun removeCookies(siteId: SiteId, names: Set<String>) {
        synchronized(lock) {
            if (names.isEmpty()) return
            val b = buckets[siteId.accountId] ?: return
            var removedAny = false
            for (name in names) {
                if (b.cookies.remove(name) != null) removedAny = true
            }
            if (removedAny) b.epoch++
        }
    }

    override fun getBearerToken(siteId: SiteId): String? = synchronized(lock) {
        buckets[siteId.accountId]?.bearerToken
    }

    override fun saveBearerToken(siteId: SiteId, token: String, username: String?) {
        synchronized(lock) {
            val b = bucket(siteId)
            b.bearerToken = token
            if (username != null) b.username = username
            b.epoch++
        }
    }

    override fun getUsername(siteId: SiteId): String? = synchronized(lock) {
        buckets[siteId.accountId]?.username
    }

    override fun saveUsername(siteId: SiteId, username: String?) {
        synchronized(lock) {
            val b = bucket(siteId)
            b.username = username
        }
    }

    override fun getSecret(siteId: SiteId, key: String): String? = synchronized(lock) {
        buckets[siteId.accountId]?.secrets?.get(key)
    }

    override fun saveSecret(siteId: SiteId, key: String, value: String?) {
        synchronized(lock) {
            val b = bucket(siteId)
            if (value == null) {
                if (b.secrets.remove(key) != null) b.epoch++
            } else {
                b.secrets[key] = value
                b.epoch++
            }
        }
    }

    override fun clearSite(siteId: SiteId) {
        synchronized(lock) {
            val b = bucket(siteId)
            b.cookies.clear()
            b.bearerToken = null
            b.username = null
            b.secrets.clear()
            b.epoch++
        }
    }

    override fun authEpoch(siteId: SiteId): Long = synchronized(lock) {
        buckets[siteId.accountId]?.epoch ?: 0L
    }

    /**
     * Single locked write: cookie summary + secrets refresh with NO epoch increment.
     * The caller guarantees the summary is value-identical (expiry-only renewal).
     */
    override fun updateSessionSnapshotWithoutEpochBump(
        siteId: SiteId,
        cookies: Map<String, String>,
        secrets: Map<String, String?>,
    ) {
        synchronized(lock) {
            val b = bucket(siteId)
            if (cookies != b.cookies) {
                b.cookies.clear()
                b.cookies.putAll(cookies)
            }
            for ((key, value) in secrets) {
                if (value == null) b.secrets.remove(key) else b.secrets[key] = value
            }
        }
    }
}
