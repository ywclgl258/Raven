package com.raven.app.core.network

import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.security.InMemoryVaultStorage
import com.raven.app.core.security.SessionVault
import com.raven.app.core.security.SessionVaultException
import com.raven.app.core.security.SoftwareAesGcmVaultCipher
import com.raven.app.core.sitespi.SiteSessionStore
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthProvider
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteCookieJarTest {

    private fun plugin(
        id: SiteId,
        host: String,
        kind: MediaKind = MediaKind.VIDEO,
        apiHosts: Set<String> = emptySet(),
        mediaDomains: Set<String> = emptySet(),
        defaultCookies: Map<String, String> = emptyMap(),
    ): SitePlugin = object : SitePlugin {
        override val descriptor: SiteDescriptor = SiteDescriptor(
            id = id,
            displayName = id.value,
            primaryKind = kind,
            defaultHost = host,
            networkProfile = SiteNetworkProfile(
                apiHosts = apiHosts,
                mediaDomains = mediaDomains,
            ),
        )
        override val authProvider: AuthProvider = object : AuthProvider {
            override val mode: AuthMode = AuthMode.None
            override fun defaultCookies(host: String): Map<String, String> = defaultCookies
        }
    }

    private val registry = SiteRegistry(
        listOf(
            plugin(SiteId.R34V, "rule34video.com", mediaDomains = setOf("boomio-cdn.com")),
            plugin(SiteId.HANIME1, "hanime1.me"),
            plugin(
                SiteId.IWARA,
                "www.iwara.tv",
                apiHosts = setOf("apiq.iwara.tv", "api.iwara.tv"),
                mediaDomains = setOf("iwara.tv"),
            ),
            plugin(
                SiteId.EXHENTAI,
                "exhentai.org",
                kind = MediaKind.COMIC,
                mediaDomains = setOf("ehgt.org", "hath.network"),
                defaultCookies = mapOf("nw" to "1"),
            ),
            // E-Hentai is the account holder ExHentai shares its session with.
            plugin(
                SiteId.EHENTAI,
                "e-hentai.org",
                kind = MediaKind.COMIC,
                mediaDomains = setOf("ehgt.org", "hath.network"),
                defaultCookies = mapOf("nw" to "1"),
            ),
        ),
    )

    private fun newJar(): Triple<SiteCookieJar, SessionVault, InMemoryVaultStorage> {
        val storage = InMemoryVaultStorage()
        val vault = SessionVault(SoftwareAesGcmVaultCipher(), storage)
        val jar = SiteCookieJar(sessionStore = vault, siteRegistry = registry)
        return Triple(jar, vault, storage)
    }

    @Test
    fun `encodes host-only and domain cookies explicitly without widening host-only scope`() {
        val r34Descriptor = registry.require(SiteId.R34V).descriptor
        val hostOnly = Cookie.Builder()
            .name("PHPSESSID")
            .value("host-only-val")
            .hostOnlyDomain("rule34video.com")
            .path("/")
            .secure()
            .httpOnly()
            .build()
        val domainWide = Cookie.Builder()
            .name("prefs")
            .value("dark")
            .domain("rule34video.com")
            .path("/")
            .secure()
            .build()

        val encodedHost = SiteCookieJar.encodeCookie(hostOnly)
        val encodedDomain = SiteCookieJar.encodeCookie(domainWide)
        assertTrue(encodedHost.startsWith("h\u0001rule34video.com\u0001"))
        assertTrue(encodedDomain.startsWith("d\u0001"))

        val decodedHost = SiteCookieJar.decodeCookie(r34Descriptor, encodedHost)
        val decodedDomain = SiteCookieJar.decodeCookie(r34Descriptor, encodedDomain)
        assertNotNull(decodedHost)
        assertNotNull(decodedDomain)
        assertTrue(decodedHost!!.hostOnly)
        assertFalse(decodedDomain!!.hostOnly)

        // Foreign host cookie is rejected by decodeCookie
        assertNull(SiteCookieJar.decodeCookie(r34Descriptor, "h\u0001evil.example\u0001sid=1; path=/"))
    }

    @Test
    fun `isolates cookies per site enforces host-only scoping and never sends cookies to CDNs or update hosts`() {
        val (jar, vault, storage) = newJar()
        val futureExpiry = System.currentTimeMillis() + 3_600_000L

        val r34HostOnly = Cookie.Builder()
            .name("PHPSESSID")
            .value("r34-session")
            .hostOnlyDomain("rule34video.com")
            .path("/")
            .expiresAt(futureExpiry)
            .secure()
            .build()
        val r34Domain = Cookie.Builder()
            .name("theme")
            .value("dark")
            .domain("rule34video.com")
            .path("/")
            .expiresAt(futureExpiry)
            .secure()
            .build()
        val foreignCookie = Cookie.Builder()
            .name("tracker")
            .value("bad")
            .domain("evil.example")
            .path("/")
            .expiresAt(futureExpiry)
            .build()

        jar.saveFromResponse("https://rule34video.com/login/".toHttpUrl(), listOf(r34HostOnly, r34Domain, foreignCookie))

        // On rule34video.com -> both host-only and domain cookies sent
        val rootCookies = jar.loadForRequest("https://rule34video.com/latest-updates/".toHttpUrl())
        assertEquals(setOf("PHPSESSID", "theme"), rootCookies.map { it.name }.toSet())

        // On www.rule34video.com -> ONLY domain cookie sent, NOT host-only PHPSESSID!
        val wwwCookies = jar.loadForRequest("https://www.rule34video.com/latest-updates/".toHttpUrl())
        assertEquals(setOf("theme"), wwwCookies.map { it.name }.toSet())

        // On hanime1.me -> completely isolated (empty)
        assertTrue(jar.loadForRequest("https://hanime1.me/".toHttpUrl()).isEmpty())

        // Media CDNs, API-only hosts, and update servers NEVER receive or store cookies
        jar.saveFromResponse(
            "https://ehgt.org/t/1.jpg".toHttpUrl(),
            listOf(Cookie.Builder().name("cdn").value("x").domain("ehgt.org").expiresAt(futureExpiry).build()),
        )
        assertTrue(jar.loadForRequest("https://ehgt.org/t/1.jpg".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://boomio-cdn.com/v.mp4".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://apiq.iwara.tv/videos".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://i.iwara.tv/image/1.jpg".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://api.github.com/repos/raven/releases".toHttpUrl()).isEmpty())

        // Encrypted vault storage contains ZERO plaintext cookie values, yet plain SiteSessionStore API exposes them
        assertEquals("r34-session", vault.getCookies(SiteId.R34V)["PHPSESSID"])
        assertFalse(storage.snapshotRawEntries().values.joinToString().contains("r34-session"))
    }

    @Test
    fun `merges AuthProvider defaultCookies and interoperates with direct SiteSessionStore imports`() {
        val (jar, vault, _) = newJar()

        // Even with no saved session, ExHentai receives nw=1 default cookie on exhentai.org (NEVER on ehgt.org!)
        val anonEx = jar.loadForRequest("https://exhentai.org/".toHttpUrl())
        assertEquals(mapOf("nw" to "1"), anonEx.associate { it.name to it.value })
        assertTrue(jar.loadForRequest("https://ehgt.org/img.jpg".toHttpUrl()).isEmpty())

        // Importing cookies via SiteSessionStore (like WebView/CookieImport) immediately flows into SiteCookieJar.
        // E-Hentai and ExHentai share one account bucket, so writes are keyed by the account identity.
        vault.saveCookies(
            SiteId.EXHENTAI.accountId,
            mapOf("ipb_member_id" to "77777", "ipb_pass_hash" to "0123456789abcdef", "igneous" to "ig99"),
        )
        val authedEx = jar.loadForRequest("https://exhentai.org/g/1/abc/".toHttpUrl())
        val map = authedEx.associate { it.name to it.value }
        assertEquals("77777", map["ipb_member_id"])
        assertEquals("0123456789abcdef", map["ipb_pass_hash"])
        assertEquals("ig99", map["igneous"])
        assertEquals("1", map["nw"])

        // The same cookies are visible on E-Hentai, because both hosts belong to one account.
        val authedEh = jar.loadForRequest("https://e-hentai.org/g/1/abc/".toHttpUrl())
            .associate { it.name to it.value }
        assertEquals("77777", authedEh["ipb_member_id"])

        // Clearing either host clears the shared session but never touches R34V
        vault.saveCookies(SiteId.R34V, mapOf("PHPSESSID" to "keep-me"))
        jar.clearSite(SiteId.EXHENTAI)
        assertEquals(mapOf("nw" to "1"), jar.loadForRequest("https://exhentai.org/".toHttpUrl()).associate { it.name to it.value })
        assertEquals(mapOf("nw" to "1"), jar.loadForRequest("https://e-hentai.org/".toHttpUrl()).associate { it.name to it.value })
        assertEquals("keep-me", vault.getCookies(SiteId.R34V)["PHPSESSID"])
    }

    @Test
    fun `saveFromResponse degrades gracefully when SessionVaultException occurs`() {
        val brokenStore = object : SiteSessionStore {
            override fun getCookies(siteId: SiteId): Map<String, String> = throw SessionVaultException("broken keystore")
            override fun saveCookies(siteId: SiteId, cookies: Map<String, String>) = throw SessionVaultException("broken keystore")
            override fun removeCookies(siteId: SiteId, names: Set<String>) {}
            override fun replaceCookies(siteId: SiteId, cookies: Map<String, String>) {}
            override fun getBearerToken(siteId: SiteId): String? = null
            override fun saveBearerToken(siteId: SiteId, token: String, username: String?) {}
            override fun getUsername(siteId: SiteId): String? = null
            override fun saveUsername(siteId: SiteId, username: String?) {}
            override fun getSecret(siteId: SiteId, key: String): String? = throw SessionVaultException("broken keystore")
            override fun saveSecret(siteId: SiteId, key: String, value: String?) {}
            override fun clearSite(siteId: SiteId) {}
            override fun authEpoch(siteId: SiteId): Long = 0L
        }
        val jar = SiteCookieJar(sessionStore = brokenStore, siteRegistry = registry)
        val cookie = Cookie.Builder()
            .name("sid")
            .value("123")
            .hostOnlyDomain("rule34video.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() + 60_000L)
            .build()
        // Must not throw SessionVaultException
        jar.saveFromResponse("https://rule34video.com/".toHttpUrl(), listOf(cookie))
        assertTrue(jar.loadForRequest("https://rule34video.com/".toHttpUrl()).isEmpty())
    }
}
