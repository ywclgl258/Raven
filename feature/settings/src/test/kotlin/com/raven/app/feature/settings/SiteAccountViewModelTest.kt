package com.raven.app.feature.settings

import app.cash.turbine.test
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import com.raven.app.feature.settings.auth.SiteAuthNavigation
import com.raven.app.feature.settings.auth.SiteAuthPhase
import com.raven.app.feature.settings.auth.SiteAuthPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SiteAccountViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `credential login clears password on success and logout clears ONLY target SiteId`() = runTest {
        val sessionStore = InMemorySiteSessionStore()
        // Pre-seed Iwara Bearer token to verify R34V logout does not affect Iwara
        sessionStore.saveBearerToken(SiteId.IWARA, "iwara-secret-jwt", "iwara-alice")

        val registry = createTestSiteRegistry(sessionStore = sessionStore)
        val vm = SiteAccountViewModel(
            route = RavenRoute.SiteAccount(SiteId.R34V.value),
            siteRegistry = registry,
            dispatchers = mainDispatcherRule.appDispatchers,
        )

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(AuthMode.FormCredentials, initial.authMode)
            assertTrue(initial.supportsCredentialForm)
            assertFalse(initial.supportsWebViewLogin)
            assertFalse(initial.authState.isAuthenticated)

            vm.updateUsername("bob")
            vm.updatePassword("s3cr3t-pass")
            vm.loginWithCredentials()
            advanceUntilIdle()

            val loggedIn = expectMostRecentItem()
            assertTrue(loggedIn.authState.isAuthenticated)
            assertEquals("bob", loggedIn.authState.username)
            assertEquals("", loggedIn.passwordInput)
            assertNotNull(loggedIn.successMessage)

            // Logout clears R34V only; Iwara remains untouched!
            vm.logout()
            advanceUntilIdle()

            val loggedOut = expectMostRecentItem()
            assertFalse(loggedOut.authState.isAuthenticated)
            assertTrue(sessionStore.getCookies(SiteId.R34V).isEmpty())
            assertEquals("iwara-secret-jwt", sessionStore.getBearerToken(SiteId.IWARA))
            assertEquals("iwara-alice", sessionStore.getUsername(SiteId.IWARA))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `WebViewCookie site validates required cookies and refuses persistence if remote verification fails`() = runTest {
        val sessionStore = InMemorySiteSessionStore()
        var allowExhentaiAccess = false
        val registry = createTestSiteRegistry(
            sessionStore = sessionStore,
            exhentaiAcceptCookies = { allowExhentaiAccess },
        )

        val vm = SiteAccountViewModel(
            route = RavenRoute.SiteAccount(SiteId.EXHENTAI.value),
            siteRegistry = registry,
            dispatchers = mainDispatcherRule.appDispatchers,
        )

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertTrue(initial.supportsWebViewLogin)
            assertTrue(initial.supportsCookieImport)
            assertTrue(initial.requiresAuthGate)

            // Missing required ipb_pass_hash rejected client-side
            vm.updateCookieInput("ipb_member_id=12345")
            vm.importManualCookies()
            advanceUntilIdle()
            val missingErr = expectMostRecentItem()
            assertNotNull(missingErr.errorMessage)
            assertFalse(missingErr.authState.isAuthenticated)
            assertTrue(sessionStore.getCookies(SiteId.EXHENTAI.accountId).isEmpty())

            // Valid shape but account lacks ExHentai access -> plugin rejects, nothing persisted
            val cookieHeader = "Cookie: ipb_member_id=12345; ipb_pass_hash=abcdef1234567890abcdef; igneous=valid123; ignored=xyz"
            vm.updateCookieInput(cookieHeader)
            vm.importManualCookies()
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertNotNull(denied.errorMessage)
            assertFalse(denied.authState.isAuthenticated)
            assertTrue(sessionStore.getCookies(SiteId.EXHENTAI.accountId).isEmpty())

            // Once remote access verification succeeds, filtered cookies are persisted
            allowExhentaiAccess = true
            vm.importManualCookies()
            advanceUntilIdle()
            val verified = expectMostRecentItem()
            assertTrue(verified.authState.isAuthenticated)
            assertNull(verified.errorMessage)
            assertEquals("", verified.cookieInput)
            val saved = sessionStore.getCookies(SiteId.EXHENTAI.accountId)
            assertEquals("12345", saved["ipb_member_id"])
            assertEquals("abcdef1234567890abcdef", saved["ipb_pass_hash"])
            assertEquals("valid123", saved["igneous"])
            assertFalse("ignored" in saved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SiteAuthPolicy 7-state FSM enforces HTTPS host whitelist redirect guards SSL scoping and cookie extraction`() {
        val registry = createTestSiteRegistry()
        val exPlugin = registry.require(SiteId.EXHENTAI)
        val mode = exPlugin.authProvider.mode as AuthMode.WebViewCookie
        val policy = SiteAuthPolicy(exPlugin.descriptor, mode)

        // All 7 states present and recreation rule maps VERIFYING -> TARGET_READY
        assertEquals(7, SiteAuthPhase.entries.size)
        assertEquals(SiteAuthPhase.TARGET_READY, SiteAuthPhase.VERIFYING.afterRecreation())
        assertEquals(SiteAuthPhase.FORUM, SiteAuthPhase.FORUM.afterRecreation())
        assertFalse(SiteAuthPhase.RESETTING.canVerify)
        assertFalse(SiteAuthPhase.VERIFYING.canRestart)

        // Strict HTTPS + allowedHosts whitelist
        assertTrue(policy.isAllowedUrl("https://forums.e-hentai.org/index.php?act=Login"))
        assertTrue(policy.isAllowedUrl("https://exhentai.org/"))
        assertFalse(policy.isAllowedUrl("http://exhentai.org/"))
        assertFalse(policy.isAllowedUrl("https://evil.exhentai.org.attacker.example/"))
        assertFalse(policy.isAllowedUrl("javascript:alert(1)"))
        assertFalse(policy.isAllowedUrl("file:///etc/hosts"))

        assertTrue(policy.allowsMainFrameRequest(SiteAuthPhase.FORUM, "POST", mode.loginUrl))
        assertFalse(policy.allowsMainFrameRequest(SiteAuthPhase.FORUM, "POST", mode.targetUrl))
        assertFalse(policy.allowsMainFrameRequest(SiteAuthPhase.LOADING_TARGET, "POST", mode.loginUrl))
        assertFalse(policy.allowsMainFrameRequest(SiteAuthPhase.FORUM, "POST", "https://forums.e-hentai.org:8443/"))
        assertTrue(policy.allowsMainFrameRequest(SiteAuthPhase.LOADING_TARGET, "GET", mode.targetUrl))

        // Navigation FSM transitions
        assertEquals(
            SiteAuthNavigation.ALLOW,
            policy.onMainFrameNavigation(SiteAuthPhase.FORUM, "https://forums.e-hentai.org/index.php?act=Login"),
        )
        assertEquals(
            SiteAuthNavigation.BLOCK,
            policy.onMainFrameNavigation(SiteAuthPhase.FORUM, "https://phishing.example.com/"),
        )
        assertEquals(
            SiteAuthNavigation.ALLOW,
            policy.onMainFrameNavigation(SiteAuthPhase.LOADING_TARGET, "https://exhentai.org/"),
        )
        // Off-target redirect during LOADING_TARGET fails target cleanly
        assertEquals(
            SiteAuthNavigation.FAIL_TARGET,
            policy.onMainFrameNavigation(SiteAuthPhase.LOADING_TARGET, "https://e-hentai.org/"),
        )
        assertEquals(
            SiteAuthNavigation.FAIL_TARGET,
            policy.onMainFrameNavigation(SiteAuthPhase.LOADING_TARGET, "https://evil.example/"),
        )
        assertEquals(
            SiteAuthNavigation.BLOCK,
            policy.onMainFrameNavigation(SiteAuthPhase.VERIFYING, "https://exhentai.org/"),
        )

        // SSL and main-frame error handling
        assertTrue(
            policy.handlesSslError(
                phase = SiteAuthPhase.FORUM,
                errorUrl = mode.loginUrl,
                currentUrl = null,
                targetVisitStarted = false,
            ),
        )
        assertTrue(
            policy.handlesMainFrameError(
                phase = SiteAuthPhase.LOADING_TARGET,
                url = "https://exhentai.org/",
                targetVisitStarted = false,
            ),
        )
        assertFalse(
            policy.handlesMainFrameError(
                phase = SiteAuthPhase.LOADING_TARGET,
                url = "https://forums.e-hentai.org/stale",
                targetVisitStarted = false,
            ),
        )

        // Strict cookie parsing & required identity validation
        val extracted = policy.extractAllowedCookies(
            listOf(
                "ipb_member_id=98765; ipb_pass_hash=0123456789abcdef0123; tracking=dropme",
                "igneous=cafebabe",
            ),
        )
        assertEquals(setOf("ipb_member_id", "ipb_pass_hash", "igneous"), extracted.keys)
        assertTrue(policy.hasRequiredIdentity(extracted))
        assertFalse(policy.hasRequiredIdentity(mapOf("ipb_member_id" to "0", "ipb_pass_hash" to "0123456789abcdef")))
        assertFalse(policy.hasRequiredIdentity(mapOf("ipb_member_id" to "12", "ipb_pass_hash" to "short")))
    }

    @Test
    fun `E-Hentai and ExHentai resolve to one account bucket so either login signs in both`() = runTest {
        val sessionStore = InMemorySiteSessionStore()
        val registry = createTestSiteRegistry(sessionStore = sessionStore)
        val cookies = mapOf(
            "ipb_member_id" to "4242",
            "ipb_pass_hash" to "0123456789abcdef0123",
            "igneous" to "shared-session",
        )

        // ExHentai is an alias of the E-Hentai account; unrelated sites keep their own identity.
        assertEquals(SiteId.EHENTAI, SiteId.EXHENTAI.accountId)
        assertEquals(SiteId.EHENTAI, SiteId.EHENTAI.accountId)
        assertEquals(SiteId.NHENTAI, SiteId.NHENTAI.accountId)

        // Importing through ExHentai persists into the shared bucket and is visible from E-Hentai.
        registry.get(SiteId.EXHENTAI)!!.authProvider.importCookies(cookies)
        val fromEhentai = registry.get(SiteId.EHENTAI)!!.authProvider.currentAuthState()
        assertTrue(fromEhentai.isAuthenticated)
        assertEquals("UID 4242", fromEhentai.username)
        assertEquals("4242", sessionStore.getCookies(SiteId.EHENTAI)["ipb_member_id"])

        // Logging out of E-Hentai also signs ExHentai out because they share one session.
        val affectedSites = setOf(SiteId.EHENTAI, SiteId.EXHENTAI)
            .map { registry.require(it).descriptor.displayName }.toSet()
        for (siteId in listOf(SiteId.EHENTAI, SiteId.EXHENTAI)) {
            val vm = SiteAccountViewModel(RavenRoute.SiteAccount(siteId.value), registry, mainDispatcherRule.appDispatchers)
            advanceUntilIdle()
            val names = vm.uiState.value.logoutSiteNames
            assertEquals(affectedSites, names.split("、").toSet())
            vm.logout()
            advanceUntilIdle()
            assertEquals("已退出 $names", vm.uiState.value.successMessage)
        }
        assertFalse(registry.get(SiteId.EXHENTAI)!!.authProvider.currentAuthState().isAuthenticated)
        assertTrue(sessionStore.getCookies(SiteId.EXHENTAI.accountId).isEmpty())
    }
}
