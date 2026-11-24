package com.raven.app.core.sitespi

import com.raven.app.core.model.SiteId

/**
 * Per-site encrypted credential & session store contract.
 * Implemented by `:core:security`'s `SessionVault` (AndroidKeyStore AES-256-GCM) in production
 * and by [InMemorySiteSessionStore] in pure-JVM tests.
 *
 * Implementations MUST isolate data strictly by [SiteId] and increment [authEpoch] monotonically
 * on every session mutation so in-flight authenticated requests (e.g. Iwara subscriptions) can detect stale epochs.
 */
interface SiteSessionStore {
    /** Returns all stored cookie key/value pairs for [siteId]. */
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

    /** Removes all cookies, tokens, usernames, and secrets belonging to [siteId] and increments [authEpoch]. */
    fun clearSite(siteId: SiteId)

    /** Monotonically increasing per-site auth mutation counter. */
    fun authEpoch(siteId: SiteId): Long
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

    private fun bucket(siteId: SiteId): SiteBucket = buckets.getOrPut(siteId) { SiteBucket() }

    override fun getCookies(siteId: SiteId): Map<String, String> = synchronized(lock) {
        buckets[siteId]?.cookies?.toMap().orEmpty()
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
            val b = buckets[siteId] ?: return
            var removedAny = false
            for (name in names) {
                if (b.cookies.remove(name) != null) removedAny = true
            }
            if (removedAny) b.epoch++
        }
    }

    override fun getBearerToken(siteId: SiteId): String? = synchronized(lock) {
        buckets[siteId]?.bearerToken
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
        buckets[siteId]?.username
    }

    override fun saveUsername(siteId: SiteId, username: String?) {
        synchronized(lock) {
            val b = bucket(siteId)
            b.username = username
        }
    }

    override fun getSecret(siteId: SiteId, key: String): String? = synchronized(lock) {
        buckets[siteId]?.secrets?.get(key)
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
        buckets[siteId]?.epoch ?: 0L
    }
}
