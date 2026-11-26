package com.raven.app

import com.raven.app.feature.settings.ApkSignatureVerifier
import com.raven.app.feature.settings.FileUpdateStateStore
import com.raven.app.feature.settings.RavenAppInfo
import com.raven.app.feature.settings.RavenUpdater
import com.raven.app.feature.settings.UpdateCheckOutcome
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupUpdateCheckTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cold start checks an eligible release build and publishes an available update`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": 1,
                  "tag_name": "v9.9.9",
                  "name": "Raven test release",
                  "draft": false,
                  "html_url": "https://example.invalid/release",
                  "assets": [
                    {
                      "id": 101,
                      "name": "raven-9.9.9-universal-release.apk",
                      "size": 1024,
                      "browser_download_url": "https://example.invalid/raven.apk"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.start()
        val updater = updater(server)
        try {
            val outcome = runStartupUpdateCheck(updater, autoUpdateEnabled = true)
            assertTrue(outcome is UpdateCheckOutcome.UpdateAvailable)
            assertEquals("v9.9.9", (outcome as UpdateCheckOutcome.UpdateAvailable).release.tagName)
            assertNotNull(StartupUpdateNotifier.latestUpdate.value)
            assertEquals(1, server.requestCount)

            // A second process check in the same 12-hour window does not hit GitHub again.
            assertEquals(
                UpdateCheckOutcome.Throttled,
                runStartupUpdateCheck(updater, autoUpdateEnabled = true),
            )
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `disabled setting and debug builds never hit the update endpoint`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val releaseUpdater = updater(server)
            assertEquals(
                UpdateCheckOutcome.Throttled,
                runStartupUpdateCheck(releaseUpdater, autoUpdateEnabled = false),
            )
            assertEquals(
                UpdateCheckOutcome.Throttled,
                runStartupUpdateCheck(
                    updater = updater(server, debug = true),
                    autoUpdateEnabled = true,
                ),
            )
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun updater(server: MockWebServer, debug: Boolean = false): RavenUpdater {
        val updatesDir = tempFolder.newFolder(if (debug) "updates-debug" else "updates-release")
        return RavenUpdater(
            appInfo = RavenAppInfo(
                versionName = if (debug) "0.1.0-debug" else "0.1.0",
                versionCode = 1L,
                isDebug = debug,
                packageName = if (debug) "com.raven.app.debug" else "com.raven.app",
            ),
            metadataClient = OkHttpClient(),
            downloadClient = OkHttpClient(),
            updatesDir = updatesDir,
            signatureVerifier = object : ApkSignatureVerifier {
                override fun verify(apkFile: File) = Unit
            },
            stateStore = FileUpdateStateStore(File(updatesDir, "state.json")),
            releasesApiBaseUrlOverride = server.url("/repos/raven/releases").toString(),
        )
    }
}
