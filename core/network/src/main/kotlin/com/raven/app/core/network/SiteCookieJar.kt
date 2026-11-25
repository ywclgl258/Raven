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
 * 1. Cookies are partitioned strictly by [SiteDescriptor.matchesHost]. Media CDNs (`mediaDomains`),
 *    API-only hosts (`apiHosts`), and update servers NEVER receive or store cookies.
 * 2. Host-only cookies are encoded explicitly as `h\u0001<host>\u0001<raw>` and domain cookies as
 *    `d\u0001<raw>` so host-only scoping never expands to subdomains across restarts.
 * 3. Always merges [com.raven.app.core.sitespi.AuthProvider.defaultCookies] (e.g., `nw=1` for
 *    E-Hentai / ExHentai) on matching registered site hosts.
 * 4. Interoperates seamlessly with site plugins that import/inspect plain cookie maps via
 *    [SiteSessionStore.saveCookies] / [SiteSessionStore.getCookies].
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
    private val lastSyncedEpoch = HashMap<SiteId, Long>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val plugin = resolveRegisteredSite(url.host) ?: return
        val descriptor = plugin.descriptor
        val now = clockMs()

        synchronized(lock) {
            try {
                val bucket = loadSiteCookiesLocked(plugin, now).toMutableList()
                var mutated = false
                for (cookie in cookies) {
                    if (!descriptor.matchesHost(cookie.domain)) continue
                    bucket.removeAll { existing ->
                        existing.name == cookie.name &&
                            existing.domain.equals(cookie.domain, ignoreCase = true) &&
                            existing.path == cookie.path
                    }
                    if (cookie.expiresAt > now) {
                        bucket.add(cookie)
                    }
                    mutated = true
                }
                if (mutated) {
                    persistSiteCookiesLocked(plugin.id, bucket)
                }
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
            persistSiteCookiesLocked(siteId, active)
        }
    }

    /** Clears ONLY [siteId] credentials/cookies without affecting any other site. */
    fun clearSite(siteId: SiteId) {
        synchronized(lock) {
            lastSyncedEpoch.remove(siteId)
            sessionStore.clearSite(siteId)
        }
    }

    private fun resolveRegisteredSite(host: String): SitePlugin? {
        val plugin = siteRegistryProvider().findForHost(host) ?: return null
        return plugin.takeIf { it.descriptor.matchesHost(host) }
    }

    private fun loadSiteCookiesLocked(plugin: SitePlugin, now: Long): List<Cookie> {
        val siteId = plugin.id
        val descriptor = plugin.descriptor
        val encodedBlob = sessionStore.getSecret(siteId, JAR_SECRET_KEY)
        val plainMap = sessionStore.getCookies(siteId)

        val jarCookies = encodedBlob
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { decodeCookie(descriptor, it) }
            ?.filter { it.expiresAt > now }
            ?.toList()
            .orEmpty()

        val syncedEpoch = lastSyncedEpoch[siteId]
        val currentEpoch = sessionStore.authEpoch(siteId)
        if (syncedEpoch != null && syncedEpoch == currentEpoch && jarCookies.isNotEmpty()) {
            return jarCookies
        }

        // Reconcile rich jar entries with any direct plain cookie mutations (e.g. WebView/manual import)
        val result = mutableListOf<Cookie>()
        val matchedPlainNames = HashSet<String>()
        for (jarCookie in jarCookies) {
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
            if (rawVal.startsWith(HOST_ONLY_PREFIX) || rawVal.startsWith(DOMAIN_PREFIX)) {
                decodeCookie(descriptor, rawVal)?.takeIf { it.expiresAt > now }?.let(result::add)
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

    private fun persistSiteCookiesLocked(siteId: SiteId, cookies: List<Cookie>) {
        if (cookies.isEmpty()) {
            sessionStore.saveSecret(siteId, JAR_SECRET_KEY, null)
            sessionStore.replaceCookies(siteId, emptyMap())
        } else {
            val encodedLines = cookies.joinToString("\n") { encodeCookie(it) }
            sessionStore.saveSecret(siteId, JAR_SECRET_KEY, encodedLines)
            val plainSummary = LinkedHashMap<String, String>(cookies.size)
            for (cookie in cookies) {
                plainSummary[cookie.name] = cookie.value
            }
            sessionStore.replaceCookies(siteId, plainSummary)
        }
        lastSyncedEpoch[siteId] = sessionStore.authEpoch(siteId)
    }

    companion object {
        internal const val JAR_SECRET_KEY: String = "__raven_okhttp_cookie_jar_v1"
        private const val HOST_ONLY_PREFIX: String = "h\u0001"
        private const val DOMAIN_PREFIX: String = "d\u0001"

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
         * Decodes an encoded cookie record and validates its domain against [descriptor].
         * Rejects cookies that escape [descriptor]'s registered hosts or fail host-only invariants.
         */
        fun decodeCookie(descriptor: SiteDescriptor, raw: String): Cookie? {
            if (raw.startsWith(HOST_ONLY_PREFIX)) {
                val parts = raw.split('\u0001', limit = 3)
                if (parts.size < 3) return null
                val host = parts[1].trim().lowercase(Locale.ROOT)
                val header = parts[2]
                if (!descriptor.matchesHost(host)) return null
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

            if (!descriptor.matchesHost(domainAttr)) return null
            val url = "https://$domainAttr/".toHttpUrlOrNull() ?: return null
            return Cookie.parse(url, header)
                ?.takeIf { !it.hostOnly && it.domain.equals(domainAttr, ignoreCase = true) }
        }
    }
}
