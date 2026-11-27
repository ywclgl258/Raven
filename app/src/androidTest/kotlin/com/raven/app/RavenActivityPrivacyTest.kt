package com.raven.app

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.designsystem.theme.RavenTheme
import com.raven.app.feature.settings.RavenPrivacyGate
import java.util.concurrent.atomic.AtomicInteger
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.model.SiteId
import com.raven.app.core.security.PrivacyLockStore
import com.raven.app.feature.settings.AppLockSessionCoordinator
import com.raven.app.feature.settings.auth.SiteAuthActivity
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class RavenActivityPrivacyTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launchAuth(): ActivityScenario<SiteAuthActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch(SiteAuthActivity.createIntent(context, SiteId.EHENTAI))
    }

    @Test
    fun loginSurvivesQuickReturnAndRequiresUnlockAfterTimeoutAndRecreation() {
        val prefs = GlobalContext.get().get<PreferencesRepository>()
        val lock = GlobalContext.get().get<AppLockSessionCoordinator>()
        val vault = GlobalContext.get().get<PrivacyLockStore>()
        val original = runBlocking { prefs.currentSettings() }
        check(!vault.hasPin()) { "This regression requires a fresh test installation" }
        vault.setPin("641982")
        try {
            runBlocking {
                prefs.setAppLockTimeoutSeconds(3600)
                prefs.setAppLockEnabled(true)
            }
            launchAuth().use { scenario ->
                runBlocking { assertTrue(lock.verifyPin("641982")) }
                compose.onNodeWithTag("site_auth_activity_root").assertIsDisplayed()
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.onNodeWithTag("site_auth_activity_root").assertIsDisplayed()
                runBlocking { prefs.setAppLockTimeoutSeconds(0) }
                scenario.onActivity { lock.onSettingsLoaded(runBlocking { prefs.currentSettings() }) }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.onNodeWithTag("raven_app_lock_overlay").assertIsDisplayed()
                compose.onNodeWithTag("site_auth_activity_root").assertDoesNotExist()
                scenario.recreate()
                compose.onNodeWithTag("raven_app_lock_overlay").assertIsDisplayed()
                compose.onNodeWithTag("site_auth_activity_root").assertDoesNotExist()
                scenario.onActivity { assertTrue(it.window.decorView.findBrowser() == null) }
                runBlocking { assertTrue(lock.verifyPin("641982")) }
                compose.onNodeWithTag("site_auth_activity_root").assertIsDisplayed()
            }
        } finally {
            vault.clearPin()
            runBlocking {
                prefs.setAppLockEnabled(original.appLockEnabled)
                prefs.setAppLockTimeoutSeconds(original.appLockTimeoutSeconds)
            }
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    @Test
    fun deniedMainFramePostReturns403AndSendsZeroBytesToLocalReceiver() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val destination = "http://127.0.0.1:${receiver.localPort}/policy-check"
            val intercepted = CountDownLatch(1)
            var status = 0
            launchAuth().use { scenario ->
                compose.onNodeWithTag("site_auth_activity_root").assertIsDisplayed()
                scenario.onActivity { activity ->
                    val web = requireNotNull(activity.window.decorView.findBrowser())
                    val production = web.webViewClient
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val response = production.shouldInterceptRequest(view, request)
                            if (request.isForMainFrame && request.method == "POST" && request.url.toString() == destination) {
                                status = response?.statusCode ?: 0
                                intercepted.countDown()
                            }
                            return response
                        }
                    }
                    web.postUrl(destination, "probe=1".toByteArray())
                }
                assertTrue(intercepted.await(8, TimeUnit.SECONDS))
                assertEquals(403, status)
                // Chromium may speculatively open TCP sockets. Count received HTTP bytes.
                receiver.soTimeout = 400
                var received = 0
                while (true) {
                    val socket = try { receiver.accept() } catch (_: SocketTimeoutException) { break }
                    socket.use {
                        it.soTimeout = 400
                        try {
                            val bytes = ByteArray(4096)
                            val count = it.getInputStream().read(bytes)
                            if (count > 0) received += count
                        } catch (_: SocketTimeoutException) {
                            // A speculative connection with no request.
                        }
                    }
                }
                assertEquals("HTTP bytes received by denied origin", 0, received)
            }
        }
    }

    @Test
    fun chromiumRejectsOffOriginFormUsingDocumentStartPolicy() {
        launchAuth().use { scenario ->
            compose.onNodeWithTag("site_auth_activity_root").assertIsDisplayed()
            scenario.onActivity { activity ->
                val web = requireNotNull(activity.window.decorView.findBrowser())
                // A local HTML fixture with a synthetic HTTPS origin. Nothing is served remotely.
                web.loadDataWithBaseURL("https://forums.e-hentai.org/", """
                    <html><head><title>fixture</title></head><body>
                    <form method="post" action="https://127.0.0.1:1/fixture"><input name="probe" value="1"></form>
                    <script>
                      document.addEventListener('securitypolicyviolation', e => { document.title = e.effectiveDirective; });
                      window.onload = () => document.forms[0].submit();
                    </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            compose.waitUntil(8_000) {
                var title: String? = null
                scenario.onActivity { title = it.window.decorView.findBrowser()?.title }
                title == "form-action"
            }
        }
    }

    private fun View.findBrowser(): WebView? {
        if (this is WebView) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).findBrowser()?.let { return it }
        }
        return null
    }
}

class RavenColdLockTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sensitiveContentFirstEntersCompositionAfterPinUnlock() {
        val vault = GlobalContext.get().get<PrivacyLockStore>()
        check(!vault.hasPin()) { "This regression requires a fresh test installation" }
        vault.setPin("498162")
        try {
            val lock = AppLockSessionCoordinator(vault, GlobalContext.get().get<AppDispatchers>())
            val entries = AtomicInteger()
            compose.setContent {
                RavenTheme {
                    RavenPrivacyGate(lock) { SideEffect { entries.incrementAndGet() } }
                }
            }
            compose.runOnIdle {
                assertEquals(0, entries.get())
                lock.onSettingsLoaded(AppSettings(appLockEnabled = true))
            }
            compose.onNodeWithTag("raven_app_lock_overlay").assertIsDisplayed()
            assertEquals(0, entries.get())
            runBlocking { assertTrue(lock.verifyPin("498162")) }
            compose.runOnIdle { assertTrue(entries.get() > 0) }
        } finally {
            vault.clearPin()
        }
    }
}
