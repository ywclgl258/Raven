package com.raven.app.core.network

import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.security.SessionVaultException
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.SiteSessionStore
import java.util.Locale
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Strictly site-partitioned [CookieJar] backed by encrypted [SiteSessionStore] (`SessionVault`).
 *
 * Security & protocol invariants:
 * 1. Cookies are partitioned strictly by [SiteDescriptor.matchesHost] **and** by
 *    [SiteId.accountId] (E-Hentai / ExHentai are one login, so they share exactly one bucket).
 *    Media CDNs (`mediaDomains`), API-only hosts (`apiHosts`), and update servers NEVER receive
 *    or store cookies.
 * 2. Host-only cookies are encoded explicitly as `h\u0001<host>\u0001<raw>` and domain cookies as
 *    `d\u0001<raw>` so host-only scoping never expands to subdomains across restarts.
 * 3. Always merges [com.raven.app.core.sitespi.AuthProvider.defaultCookies] (e.g., `nw=1` for
 *    E-Hentai / ExHentai) on matching registered site hosts.
 * 4. Response writes are guarded by the epoch the call **left** under, captured by
 *    [SiteCookieEpochInterceptor] before the request hits the network and read back here through
 *    [SiteCookieEpochScope]. The comparison happens inside
 *    [SiteSessionStore.storeSessionSnapshotIfEpoch] and therefore atomically with the write, under
 *    the store's own lock — so a `Set-Cookie` that arrives after a logout can never restore the
 *    cleared session (NET-09), while two concurrent requests to the same URL stay correctly paired
 *    because the scope is per-call (thread-scoped around `chain.proceed`), not URL-keyed.
 * 5. Cookie-jar writes never bump the epoch for a byte-identical snapshot. Renewal of an identical
 *    `Set-Cookie` (same name/value, new expiry) is detected and skipped entirely, so a routine
 *    session refresh never cancels authenticated requests that are already in flight.
 * 6. Cookies whose expiry has passed are tombstoned: they are never resurrected from the plain
 *    name/value summary, which carries no expiry information.
 */
class SiteCookieJar(
    private val sessionStore: SiteSessionStore,
    private val siteRegistryProvider: () -> SiteRegistry,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : CookieJar {

    constructor(
        sessionStore: SiteSessionStore,
        siteRegistry: SiteRegistry,
        clockMs: () -> Long = System::currentTimeMillis,
    ) : this(
        sessionStore = sessionStore,
        siteRegistryProvider = { siteRegistry },
        clockMs = clockMs,
    )

    private val lock = Any()

    /** accountId -> epoch for which the rich jar blob and plain summary were last reconciled. */
    private val lastSyncedEpoch = HashMap<SiteId, Long>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val plugin = resolveRegisteredSite(url.host) ?: return
        val descriptor = plugin.descriptor
        val accountId = plugin.id.accountId
        val now = clockMs()

        // Epoch captured at dispatch, when this response came from a real network call.
        // A null scope means the caller invoked the jar directly (WebView import / explicit
        // replace), which is synchronous by construction: no in-flight response can race it, so the
        // write proceeds unguarded. Real OkHttp responses always arrive inside the scope set by
        // SiteCookieEpochInterceptor on the shared pool.
        val dispatchEpoch = SiteCookieEpochScope.epochFor(accountId)

        synchronized(lock) {
            try {
                val bucket = loadSiteCookiesLocked(plugin, now).toMutableList()
                var mutated = false
                for (cookie in cookies) {
                    if (!descriptor.matchesHost(cookie.domain)) continue
                    if (cookie.expiresAt <= now) {
                        // Explicit deletion still has to be applied to the persisted bucket.
                        if (bucket.removeAll { existing -> sameCookieIdentity(existing, cookie) }) mutated = true
                        continue
                    }
                    bucket.removeAll { existing -> sameCookieIdentity(existing, cookie) }
                    bucket.add(cookie)
                    mutated = true
                }
                if (!mutated) return

                // Compare-and-write under the store's lock: a logout/login that bumped the epoch
                // after this request left rejects the write atomically and keeps the session cleared.
                persistSiteCookiesLocked(accountId, bucket, expectedEpoch = dispatchEpoch)
            } catch (_: SessionVaultException) {
                // Skip persisting when KeyStore decryption/encryption fails rather than crashing requests.
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val plugin = resolveRegisteredSite(url.host) ?: return emptyList()
        val descriptor = plugin.descriptor
        val now = clockMs()

        val stored = synchronized(lock) {
            runCatching { loadSiteCookiesLocked(plugin, now) }.getOrDefault(emptyList())
        }.filter { cookie ->
            cookie.expiresAt > now &&
                descriptor.matchesHost(cookie.domain) &&
                cookie.matches(url)
        }

        val defaults = plugin.authProvider.defaultCookies(url.host)
        if (defaults.isEmpty()) {
            return stored
        }
        val filtered = stored.filterNot { it.name in defaults.keys }
        val injected = defaults.mapNotNull { (name, value) ->
            runCatching {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .hostOnlyDomain(url.host)
                    .path("/")
                    .secure()
                    .build()
            }.getOrNull()
        }
        return filtered + injected
    }

    /**
     * Atomically replaces all stored cookies for [siteId], rejecting any cookie outside the site's hosts.
     */
    fun replaceSiteCookies(siteId: SiteId, cookies: List<Cookie>) {
        val plugin = siteRegistryProvider().get(siteId)
            ?: throw IllegalArgumentException("Unknown siteId: ${siteId.value}")
        val descriptor = plugin.descriptor
        require(cookies.all { descriptor.matchesHost(it.domain) }) {
            "All cookies for ${siteId.value} must match registered site hosts"
        }
        val now = clockMs()
        synchronized(lock) {
            val active = cookies.filter { it.expiresAt > now }
            persistSiteCookiesLocked(siteId.accountId, active, bumpEpoch = true)
        }
    }

    /** Clears ONLY [siteId]'s credential bucket (EH/EX share one account bucket; others unaffected). */
    fun clearSite(siteId: SiteId) {
        // ExHentai shares E-Hentai's account bucket, so clearing either clears the shared session.
        val accountId = siteId.accountId
        synchronized(lock) {
            lastSyncedEpoch.remove(accountId)
            sessionStore.clearSite(accountId)
        }
        notifyAccountChanged(accountId)
    }

    /**
     * Called after a plugin-driven login/import/logout replaced [siteId]'s credentials outside this
     * jar, so account-derived caches (media URLs, HTTP response cache) can be dropped. Safe to call
     * with either EH or EX id.
     */
    fun notifyAccountChanged(siteId: SiteId) {
        onAccountChanged?.invoke(siteId.accountId)
    }

    /**
     * Invoked after a site's account bucket is cleared or replaced, so owners of account-derived
     * caches (e.g. [MediaRedirectCache]) can drop them. Never invoked while holding [lock].
     */
    var onAccountChanged: ((SiteId) -> Unit)? = null

    private fun resolveRegisteredSite(host: String): SitePlugin? {
        val plugin = siteRegistryProvider().findForHost(host) ?: return null
        return plugin.takeIf { it.descriptor.matchesHost(host) }
    }

    /**
     * Descriptors whose cookies may legitimately live in [plugin]'s account bucket. For aliased
     * accounts (E-Hentai / ExHentai) this includes every registered host of that account, so a
     * cookie set on one hostname stays usable on the other; unrelated sites are never included.
     */
    private fun accountDescriptorsLocked(plugin: SitePlugin): List<SiteDescriptor> {
        val accountId = plugin.id.accountId
        val registry = runCatching { siteRegistryProvider() }.getOrNull() ?: return listOf(plugin.descriptor)
        val aliases = registry.allPlugins()
            .filter { it.id.accountId == accountId }
            .map { it.descriptor }
        return aliases.ifEmpty { listOf(plugin.descriptor) }
    }

    private fun loadSiteCookiesLocked(plugin: SitePlugin, now: Long): List<Cookie> {
        // Session state is stored per account identity, so E-Hentai/ExHentai share one bucket.
        val accountId = plugin.id.accountId
        val descriptor = plugin.descriptor
        val accountDescriptors = accountDescriptorsLocked(plugin)
        val acceptsHost: (String) -> Boolean = { host ->
            accountDescriptors.any { it.matchesHost(host) }
        }
        val encodedBlob = sessionStore.getSecret(accountId, JAR_SECRET_KEY)
        val plainMap = sessionStore.getCookies(accountId)

        val jarCookies = encodedBlob
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { decodeCookie(descriptor, it, acceptsHost) }
            ?.toList()
            .orEmpty()

        // Expired jar entries whose summary still carries the exact same stale value are tombstones:
        // the summary has no expiry, so rebuilding from it would resurrect a dead session cookie.
        val resurrectedNames = jarCookies
            .filter { it.expiresAt <= now }
            .mapNotNull { cookie -> cookie.name.takeIf { plainMap[cookie.name] == cookie.value } }
            .toHashSet()

        val syncedEpoch = lastSyncedEpoch[accountId]
        val currentEpoch = sessionStore.authEpoch(accountId)
        val activeJarCookies = jarCookies.filter { it.expiresAt > now }
        if (syncedEpoch != null && syncedEpoch == currentEpoch && activeJarCookies.isNotEmpty()) {
            return activeJarCookies
        }

        // Reconcile rich jar entries with any direct plain cookie mutations (e.g. WebView/manual import)
        val result = mutableListOf<Cookie>()
        val matchedPlainNames = HashSet<String>()
        for (jarCookie in activeJarCookies) {
            val plainValue = plainMap[jarCookie.name]
            if (plainValue == null) {
                // Cookie was removed externally via removeCookies / replaceCookies / clearSite
                continue
            }
            if (plainValue == jarCookie.value) {
                result.add(jarCookie)
                matchedPlainNames.add(jarCookie.name)
            }
        }

        val defaultBareHost = descriptor.defaultHost.trim().lowercase(Locale.ROOT).removePrefix("www.")
        for ((name, rawVal) in plainMap) {
            if (name in matchedPlainNames) continue
            if (name in resurrectedNames) continue
            if (rawVal.startsWith(HOST_ONLY_PREFIX) || rawVal.startsWith(DOMAIN_PREFIX)) {
                decodeCookie(descriptor, rawVal, acceptsHost)?.takeIf { it.expiresAt > now }?.let(result::add)
            } else if (name.isNotBlank() && defaultBareHost.isNotEmpty()) {
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(rawVal)
                        .domain(defaultBareHost)
                        .path("/")
                        .secure()
                        .build()
                }.getOrNull()?.let(result::add)
            }
        }
        return result
    }

    private fun persistSiteCookiesLocked(
        accountId: SiteId,
        cookies: List<Cookie>,
        bumpEpoch: Boolean = false,
        expectedEpoch: Long? = null,
    ) {
        val active = cookies.filter { it.expiresAt > clockMs() }
        val encodedLines = active.joinToString("\n") { encodeCookie(it) }
        val plainSummary = LinkedHashMap<String, String>(active.size)
        for (cookie in active) {
            plainSummary[cookie.name] = cookie.value
        }

        // Identical renewal (same names/values, refreshed expiry only) is a no-op snapshot:
        // skip the write entirely so nothing observes a session change it did not cause.
        val previousBlob = sessionStore.getSecret(accountId, JAR_SECRET_KEY)
        val previousSummary = sessionStore.getCookies(accountId)
        if (previousBlob == encodedLines && previousSummary == plainSummary) {
            lastSyncedEpoch[accountId] = sessionStore.authEpoch(accountId)
            return
        }

        val secrets = if (active.isEmpty()) {
            mapOf(JAR_SECRET_KEY to null)
        } else {
            mapOf(JAR_SECRET_KEY to encodedLines)
        }
        val written = if (expectedEpoch == null) {
            sessionStore.storeSessionSnapshot(
                siteId = accountId,
                cookies = if (active.isEmpty()) emptyMap() else plainSummary,
                secrets = secrets,
                bumpEpoch = bumpEpoch,
            )
            true
        } else {
            // Response-write path: compare against the epoch captured at dispatch, atomically with
            // the write, so a logout that already happened can never be undone by a late Set-Cookie.
            sessionStore.storeSessionSnapshotIfEpoch(
                siteId = accountId,
                expectedEpoch = expectedEpoch,
                cookies = if (active.isEmpty()) emptyMap() else plainSummary,
                secrets = secrets,
                bumpEpoch = bumpEpoch,
            )
        }
        if (!written) {
            // Session moved on while this response was in flight: keep the reconciliation cache
            // stale so the next read re-derives from the (cleared) store instead of this attempt.
            lastSyncedEpoch.remove(accountId)
            return
        }
        lastSyncedEpoch[accountId] = sessionStore.authEpoch(accountId)
    }

    companion object {
        internal const val JAR_SECRET_KEY: String = "__raven_okhttp_cookie_jar_v1"
        private const val HOST_ONLY_PREFIX: String = "h\u0001"
        private const val DOMAIN_PREFIX: String = "d\u0001"

        private fun sameCookieIdentity(a: Cookie, b: Cookie): Boolean =
            a.name == b.name &&
                a.domain.equals(b.domain, ignoreCase = true) &&
                a.path == b.path

        /**
         * Encodes [cookie] preserving host-only vs domain-wide scope explicitly:
         * - Host-only: `h\u0001<host>\u0001<raw>`
         * - Domain: `d\u0001<raw>`
         */
        fun encodeCookie(cookie: Cookie): String = if (cookie.hostOnly) {
            "$HOST_ONLY_PREFIX${cookie.domain}\u0001$cookie"
        } else {
            "$DOMAIN_PREFIX$cookie"
        }

        /**
         * Decodes an encoded cookie record and validates its domain against the account's hosts.
         * [acceptsHost] defaults to [descriptor]-only validation; aliased accounts pass a predicate
         * covering every hostname of the same login while still rejecting foreign domains.
         */
        fun decodeCookie(
            descriptor: SiteDescriptor,
            raw: String,
            acceptsHost: (String) -> Boolean = descriptor::matchesHost,
        ): Cookie? {
            if (raw.startsWith(HOST_ONLY_PREFIX)) {
                val parts = raw.split('\u0001', limit = 3)
                if (parts.size < 3) return null
                val host = parts[1].trim().lowercase(Locale.ROOT)
                val header = parts[2]
                if (!acceptsHost(host)) return null
                val url = "https://$host/".toHttpUrlOrNull() ?: return null
                return Cookie.parse(url, header)
                    ?.takeIf { it.hostOnly && it.domain.equals(host, ignoreCase = true) }
            }

            val markedDomain = raw.startsWith(DOMAIN_PREFIX)
            val header = if (markedDomain) raw.removePrefix(DOMAIN_PREFIX) else raw
            val domainAttr = header.split(';')
                .drop(1)
                .lastOrNull { it.substringBefore('=').trim().equals("domain", ignoreCase = true) }
                ?.substringAfter('=', "")
                ?.trim()
                ?.removePrefix(".")
                ?.lowercase(Locale.ROOT)

            if (domainAttr.isNullOrEmpty()) {
                if (markedDomain) return null
                val baseUrl = descriptor.baseUrl.takeIf { it.isNotBlank() }?.toHttpUrlOrNull() ?: return null
                return Cookie.parse(baseUrl, header)?.takeIf { it.hostOnly }
            }

            if (!acceptsHost(domainAttr)) return null
            val url = "https://$domainAttr/".toHttpUrlOrNull() ?: return null
            return Cookie.parse(url, header)
                ?.takeIf { !it.hostOnly && it.domain.equals(domainAttr, ignoreCase = true) }
        }
    }
}
