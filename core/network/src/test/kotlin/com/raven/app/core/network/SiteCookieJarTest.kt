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

    // ---------------------------------------------------------------------------------------------
    // NET-09: a response that completes AFTER logout must never restore the cleared session.
    // ---------------------------------------------------------------------------------------------

    private fun futureCookie(name: String, value: String, host: String): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .hostOnlyDomain(host)
        .path("/")
        .secure()
        .build()

    @Test
    fun `delayed Set-Cookie that arrives after logout does not restore the cleared session`() {
        val (jar, vault, _) = newJar()
        val host = "rule34video.com"
        jar.saveFromResponse("https://$host/login/".toHttpUrl(), listOf(futureCookie("PHPSESSID", "old", host)))

        // 1. Request leaves while logged in: the scope captures the current epoch.
        val dispatchEpoch = vault.authEpoch(SiteId.R34V)
        val scopedWrite = SiteCookieEpochScope.runScoped(SiteId.R34V, dispatchEpoch, host) {
            // 2. Logout completes BEFORE the server releases its Set-Cookie (user pressed logout).
            jar.clearSite(SiteId.R34V)
            // 3. Server releases the response cookie carrying the OLD session.
            jar.saveFromResponse(
                "https://$host/login/".toHttpUrl(),
                listOf(futureCookie("PHPSESSID", "late-restored", host)),
            )
        }
        assertEquals(Unit, scopedWrite)

        // The late cookie must not resurrect anything, sealed or plain, and the epoch moved on.
        assertTrue(vault.getCookies(SiteId.R34V).isEmpty())
        assertNull(vault.getSecret(SiteId.R34V, SiteCookieJar.JAR_SECRET_KEY))
        val cookies = jar.loadForRequest("https://$host/latest-updates/".toHttpUrl())
        assertTrue("Late Set-Cookie resurrected the cleared session: $cookies", cookies.isEmpty())
        assertTrue(vault.authEpoch(SiteId.R34V) > dispatchEpoch)
    }

    @Test
    fun `delayed Set-Cookie after re-login under a new epoch is rejected too`() {
        val (jar, vault, _) = newJar()
        val host = "rule34video.com"
        jar.saveFromResponse("https://$host/".toHttpUrl(), listOf(futureCookie("PHPSESSID", "account-A", host)))
        val epochA = vault.authEpoch(SiteId.R34V)

        // Account B signs in while request A is still in flight.
        jar.clearSite(SiteId.R34V)
        val epochB = vault.authEpoch(SiteId.R34V)
        SiteCookieEpochScope.runScoped(SiteId.R34V, epochB, host) {
            jar.saveFromResponse("https://$host/login/".toHttpUrl(), listOf(futureCookie("PHPSESSID", "account-B", host)))
        }

        // Only now does request A's response land, still carrying epoch A.
        SiteCookieEpochScope.runScoped(SiteId.R34V, epochA, host) {
            jar.saveFromResponse("https://$host/".toHttpUrl(), listOf(futureCookie("PHPSESSID", "account-A-late", host)))
        }

        val map = jar.loadForRequest("https://$host/".toHttpUrl()).associate { it.name to it.value }
        assertEquals("account-B", map["PHPSESSID"])
    }

    @Test
    fun `two concurrent same-URL requests completing out of order each keep their own epoch pairing`() {
        val (jar, vault, _) = newJar()
        val host = "rule34video.com"
        jar.saveFromResponse("https://$host/".toHttpUrl(), listOf(futureCookie("PHPSESSID", "first", host)))
        val oldEpoch = vault.authEpoch(SiteId.R34V)

        val releaseOld = java.util.concurrent.CountDownLatch(1)
        val newDone = java.util.concurrent.CountDownLatch(1)
        val staleCookiesSeen = java.util.concurrent.atomic.AtomicReference<List<Cookie>>()

        // Call A leaves under the old epoch and is held in flight.
        val threadA = Thread {
            SiteCookieEpochScope.runScoped(SiteId.R34V, oldEpoch, host) {
                newDone.await()
                jar.saveFromResponse(
                    "https://$host/".toHttpUrl(),
                    listOf(futureCookie("PHPSESSID", "stale-A", host)),
                )
            }
        }
        threadA.start()

        // Call B leaves after a logout/login and completes FIRST.
        jar.clearSite(SiteId.R34V)
        val newEpoch = vault.authEpoch(SiteId.R34V)
        SiteCookieEpochScope.runScoped(SiteId.R34V, newEpoch, host) {
            jar.saveFromResponse("https://$host/login/".toHttpUrl(), listOf(futureCookie("PHPSESSID", "fresh-B", host)))
        }
        newDone.countDown()
        releaseOld.countDown()
        threadA.join()

        val map = jar.loadForRequest("https://$host/".toHttpUrl()).associate { it.name to it.value }
        assertEquals("fresh-B", map["PHPSESSID"])
        assertNull(staleCookiesSeen.get())
    }

    @Test
    fun `identical renewal Set-Cookie does not bump the epoch or invalidate in-flight requests`() {
        val (jar, vault, _) = newJar()
        val host = "rule34video.com"
        val cookie = futureCookie("PHPSESSID", "unchanged", host)
        jar.saveFromResponse("https://$host/login/".toHttpUrl(), listOf(cookie))
        val epochAfterLogin = vault.authEpoch(SiteId.R34V)

        repeat(3) {
            // Same name + value, only the expiry is refreshed (typical session keep-alive).
            jar.saveFromResponse("https://$host/".toHttpUrl(), listOf(cookie))
        }

        assertEquals(epochAfterLogin, vault.authEpoch(SiteId.R34V))
        assertEquals("unchanged", jar.loadForRequest("https://$host/".toHttpUrl()).single { it.name == "PHPSESSID" }.value)
    }

    @Test
    fun `cookie value rotation through Set-Cookie updates the jar without bumping the epoch`() {
        val (jar, vault, _) = newJar()
        val host = "rule34video.com"
        jar.saveFromResponse("https://$host/login/".toHttpUrl(), listOf(futureCookie("PHPSESSID", "v1", host)))
        val epochV1 = vault.authEpoch(SiteId.R34V)

        // Session rotation / analytics cookies change all the time. Bumping the epoch here would
        // cancel every in-flight authenticated request, so auth mutations bump only through
        // AuthProvider.login / importCookies / logout -> SiteSessionStore primitives.
        jar.saveFromResponse("https://$host/".toHttpUrl(), listOf(futureCookie("PHPSESSID", "v2", host)))

        assertEquals("v2", jar.loadForRequest("https://$host/".toHttpUrl()).single { it.name == "PHPSESSID" }.value)
        assertEquals(epochV1, vault.authEpoch(SiteId.R34V))
    }

    @Test
    fun `expired cookie is tombstoned and never resurrected from the plain summary`() {
        var now = 1_000_000L
        val storage = InMemoryVaultStorage()
        val vault = SessionVault(SoftwareAesGcmVaultCipher(), storage)
        val jar = SiteCookieJar(sessionStore = vault, siteRegistry = registry, clockMs = { now })
        val host = "rule34video.com"

        jar.saveFromResponse(
            "https://$host/login/".toHttpUrl(),
            listOf(
                Cookie.Builder().name("PHPSESSID").value("dead").hostOnlyDomain(host).path("/")
                    .expiresAt(now + 60_000L).build(),
                Cookie.Builder().name("keep").value("alive").hostOnlyDomain(host).path("/")
                    .expiresAt(now + 10L * 60_000L).build(),
            ),
        )
        // Plain summary still carries the value (it has no expiry dimension).
        assertEquals("dead", vault.getCookies(SiteId.R34V)["PHPSESSID"])

        // Advance past the session cookie's expiry: it must disappear and stay gone.
        now += 120_000L
        val afterExpiry = jar.loadForRequest("https://$host/".toHttpUrl()).associate { it.name to it.value }
        assertNull("Expired cookie was resurrected from the plain summary", afterExpiry["PHPSESSID"])
        assertEquals("alive", afterExpiry["keep"])

        // A second read must not bring it back either.
        assertNull(jar.loadForRequest("https://$host/".toHttpUrl()).firstOrNull { it.name == "PHPSESSID" })
    }

    @Test
    fun `E-Hentai and ExHentai share one cookie bucket and one auth epoch`() {
        val (jar, vault, _) = newJar()
        val ehEpoch = vault.authEpoch(SiteId.EHENTAI)
        jar.saveFromResponse(
            "https://exhentai.org/".toHttpUrl(),
            listOf(
                Cookie.Builder().name("ipb_member_id").value("4242").domain("exhentai.org").path("/").secure().build(),
                Cookie.Builder().name("igneous").value("shared").domain("exhentai.org").path("/").secure().build(),
            ),
        )
        // Written through the EX host, visible through EH and EX, one shared bucket, one epoch.
        assertEquals("4242", vault.getCookies(SiteId.EHENTAI)["ipb_member_id"])
        assertEquals("4242", vault.getCookies(SiteId.EXHENTAI)["ipb_member_id"])
        assertEquals(vault.authEpoch(SiteId.EHENTAI), vault.authEpoch(SiteId.EXHENTAI))
        assertTrue(vault.authEpoch(SiteId.EHENTAI) >= ehEpoch)

        // Clearing EX clears the shared session; clearing EH does the same to EX.
        jar.clearSite(SiteId.EXHENTAI)
        assertTrue(vault.getCookies(SiteId.EHENTAI).isEmpty())
        assertTrue(jar.loadForRequest("https://e-hentai.org/".toHttpUrl())
            .none { it.name == "ipb_member_id" })
        assertTrue(jar.loadForRequest("https://exhentai.org/".toHttpUrl())
            .none { it.name == "ipb_member_id" })
    }

    @Test
    fun `ISOLATED request scope never reads or writes the site cookie bucket`() {
        val (jar, vault, _) = newJar()
        val host = "hanime1.me"
        jar.saveFromResponse("https://$host/login".toHttpUrl(), listOf(futureCookie("PHPSESSID", "live-session", host)))
        val epochBefore = vault.authEpoch(SiteId.HANIME1)

        // A candidate login handshake runs in RequestSessionScope.ISOLATED: SiteHttpClient picks a
        // NO_COOKIES client and the jar is never consulted, so the response's Set-Cookie cannot reach
        // the vault. Verified at pool level in SiteHttpClientTest; here assert the jar stays untouched.
        assertNull(SiteCookieEpochScope.epochFor(SiteId.HANIME1))
        assertEquals("live-session", vault.getCookies(SiteId.HANIME1)["PHPSESSID"])
        assertEquals(epochBefore, vault.authEpoch(SiteId.HANIME1))
    }
}
