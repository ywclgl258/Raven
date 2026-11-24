package com.raven.app.feature.settings

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RavenUpdaterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `isRemoteVersionNewer compares semantic versions and prereleases deterministically`() {
        assertTrue(RavenUpdater.isRemoteVersionNewer("v1.0.1", "1.0.0"))
        assertTrue(RavenUpdater.isRemoteVersionNewer("v1.2.0", "1.1.9"))
        assertTrue(RavenUpdater.isRemoteVersionNewer("2.0", "1.9.9"))
        assertTrue(RavenUpdater.isRemoteVersionNewer("1.0.0", "1.0.0-beta.1"))
        assertTrue(RavenUpdater.isRemoteVersionNewer("v1.0.0-rc.2", "1.0.0-rc.1"))

        assertFalse(RavenUpdater.isRemoteVersionNewer("v1.0.0", "1.0.0"))
        assertFalse(RavenUpdater.isRemoteVersionNewer("v0.9.9", "1.0.0"))
        assertFalse(RavenUpdater.isRemoteVersionNewer("1.0.0-beta.1", "1.0.0"))
        assertFalse(RavenUpdater.isRemoteVersionNewer("not-a-version", "1.0.0"))
    }

    @Test
    fun `selectReleaseAsset skips debug APKs honors compatibilityMode and prioritizes device ABI then universal`() {
        val assets = listOf(
            GitHubReleaseAssetDto(1L, "checksums.txt", 256L, "https://example.invalid/checksums.txt"),
            GitHubReleaseAssetDto(2L, "raven-1.2.0-debug.apk", 5000L, "https://example.invalid/debug.apk"),
            GitHubReleaseAssetDto(3L, "raven-1.2.0-armeabi-v7a-release.apk", 8000L, "https://example.invalid/v7a.apk"),
            GitHubReleaseAssetDto(4L, "raven-1.2.0-arm64-v8a-release.apk", 9000L, "https://example.invalid/arm64.apk"),
            GitHubReleaseAssetDto(5L, "raven-1.2.0-universal-release.apk", 12000L, "https://example.invalid/uni.apk"),
            GitHubReleaseAssetDto(6L, "raven-1.2.0-compat-2.0.0.apk", 11000L, "https://example.invalid/compat.apk"),
        )

        // Prefers matching device ABI first
        val arm64 = RavenUpdater.selectReleaseAsset(
            assets = assets,
            compatibilityMode = false,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertEquals("raven-1.2.0-arm64-v8a-release.apk", arm64?.name)

        // Falls back to universal when device ABI is x86_64
        val universal = RavenUpdater.selectReleaseAsset(
            assets = assets,
            compatibilityMode = false,
            supportedAbis = listOf("x86_64"),
        )
        assertEquals("raven-1.2.0-universal-release.apk", universal?.name)

        // Selects compat package strictly when compatibilityMode == true
        val compat = RavenUpdater.selectReleaseAsset(
            assets = assets,
            compatibilityMode = true,
            supportedAbis = listOf("arm64-v8a"),
        )
        assertEquals("raven-1.2.0-compat-2.0.0.apk", compat?.name)

        // Returns null when only mismatched ABI exists
        val onlyArm64 = listOf(assets[3])
        assertNull(
            RavenUpdater.selectReleaseAsset(
                assets = onlyArm64,
                compatibilityMode = false,
                supportedAbis = listOf("x86"),
            ),
        )
    }

    @Test
    fun `debug build skips network calls and 12h auto-check throttle gates non-manual checks`() = runTest {
        val updatesDir = tempFolder.newFolder("updates-throttle")
        var nowMs = 1_730_000_000_000L
        val stateStore = InMemoryUpdateStateStore(PersistedUpdateState(lastCheckedAtMs = nowMs - 1_000L))
        val client = OkHttpClient()

        // Debug build skips immediately
        val debugUpdater = RavenUpdater(
            appInfo = RavenAppInfo("1.0.0-debug", 1L, isDebug = true, packageName = "com.raven.app.debug"),
            metadataClient = client,
            downloadClient = client,
            updatesDir = updatesDir,
            signatureVerifier = object : ApkSignatureVerifier {
                override fun verify(apkFile: File) = Unit
            },
            stateStore = stateStore,
            dispatchers = mainDispatcherRule.appDispatchers,
            clockMs = { nowMs },
        )
        assertFalse(debugUpdater.shouldRunAutoCheck(autoUpdateEnabled = true))
        assertTrue(debugUpdater.checkForUpdate(manual = true) is UpdateCheckOutcome.DebugBuildSkipped)

        // Release build throttled inside 12h window for auto checks
        val releaseUpdater = RavenUpdater(
            appInfo = RavenAppInfo("1.0.0", 1L, isDebug = false, packageName = "com.raven.app"),
            metadataClient = client,
            downloadClient = client,
            updatesDir = updatesDir,
            signatureVerifier = object : ApkSignatureVerifier {
                override fun verify(apkFile: File) = Unit
            },
            stateStore = stateStore,
            dispatchers = mainDispatcherRule.appDispatchers,
            clockMs = { nowMs },
        )
        assertFalse(releaseUpdater.shouldRunAutoCheck(autoUpdateEnabled = true))
        assertEquals(UpdateCheckOutcome.Throttled, releaseUpdater.checkForUpdate(manual = false))

        // After 12h elapses, auto check becomes eligible again
        nowMs += RavenUpdater.AUTO_CHECK_INTERVAL_MS
        assertTrue(releaseUpdater.shouldRunAutoCheck(autoUpdateEnabled = true))
        assertFalse(releaseUpdater.shouldRunAutoCheck(autoUpdateEnabled = false))
    }

    @Test
    fun `downloads streams verifies SHA-256 and signing cert then reuses verified cache`() = runTest {
        val server = MockWebServer()
        val fakeApkBytes = ByteArray(16_384) { idx -> (idx * 17 and 0xFF).toByte() }
        val expectedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(fakeApkBytes)
            .joinToString("") { b -> String.format(Locale.ROOT, "%02x", b) }

        val releaseJson = """
            {
              "id": 500,
              "tag_name": "v1.3.0",
              "name": "Raven 1.3.0",
              "body": "Security & streaming improvements",
              "html_url": "https://github.com/ywclgl258/raven/releases/tag/v1.3.0",
              "assets": [
                {
                  "id": 901,
                  "name": "raven-1.3.0-universal-release.apk",
                  "size": ${fakeApkBytes.size},
                  "browser_download_url": "https://example.invalid/raven.apk",
                  "digest": "sha256:$expectedSha256"
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(fakeApkBytes)))
        server.start()

        try {
            val updatesDir = tempFolder.newFolder("updates-download")
            val stateStore = InMemoryUpdateStateStore()
            var verifyCalls = 0
            val client = OkHttpClient()

            val updater = RavenUpdater(
                appInfo = RavenAppInfo("1.0.0", 1L, isDebug = false, packageName = "com.raven.app"),
                metadataClient = client,
                downloadClient = client,
                updatesDir = updatesDir,
                signatureVerifier = object : ApkSignatureVerifier {
                    override fun verify(apkFile: File) {
                        verifyCalls += 1
                    }
                },
                stateStore = stateStore,
                supportedAbisProvider = { listOf("arm64-v8a") },
                dispatchers = mainDispatcherRule.appDispatchers,
                releasesApiBaseUrlOverride = server.url("/repos/ywclgl258/raven/releases").toString(),
            )

            val checkOutcome = updater.checkForUpdate(manual = true)
            val available = checkOutcome as? UpdateCheckOutcome.UpdateAvailable
            assertNotNull(available)

            val progressEvents = mutableListOf<UpdateDownloadProgress>()
            val verifiedApk = updater.downloadAndVerify(available!!.release) { progressEvents += it }
            assertTrue(verifiedApk.isFile)
            assertEquals("$expectedSha256.apk", verifiedApk.name)
            assertEquals(1, verifyCalls)
            assertEquals(fakeApkBytes.size.toLong(), progressEvents.last().downloadedBytes)
            assertEquals("v1.3.0", stateStore.state.pendingTag)
            assertEquals(expectedSha256, stateStore.state.pendingSha256)

            // Second call reuses verified cached APK without hitting network again (server queue is empty!)
            val reused = updater.downloadAndVerify(available.release)
            assertEquals(verifiedApk.canonicalPath, reused.canonicalPath)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejects tampered APK signature or digest and cleans temporary file`() = runTest {
        val server = MockWebServer()
        val fakeApkBytes = ByteArray(2048) { 9 }
        val wrongSha256 = "0".repeat(64)

        val release = RavenReleaseInfo(
            releaseId = 600L,
            tagName = "v1.4.0",
            title = "Raven 1.4.0",
            releaseNotes = "",
            htmlUrl = "",
            asset = RavenReleaseAsset(
                releaseId = 600L,
                assetId = 902L,
                fileName = "raven-1.4.0-universal-release.apk",
                sizeBytes = fakeApkBytes.size.toLong(),
                downloadUrl = "",
                expectedSha256 = wrongSha256,
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(fakeApkBytes)))
        server.start()

        try {
            val updatesDir = tempFolder.newFolder("updates-reject")
            val updater = RavenUpdater(
                appInfo = RavenAppInfo("1.0.0", 1L, isDebug = false, packageName = "com.raven.app"),
                metadataClient = OkHttpClient(),
                downloadClient = OkHttpClient(),
                updatesDir = updatesDir,
                signatureVerifier = object : ApkSignatureVerifier {
                    override fun verify(apkFile: File) = Unit
                },
                stateStore = InMemoryUpdateStateStore(),
                dispatchers = mainDispatcherRule.appDispatchers,
                releasesApiBaseUrlOverride = server.url("/repos/ywclgl258/raven/releases").toString(),
            )

            var caught: Throwable? = null
            try {
                updater.downloadAndVerify(release)
            } catch (e: Throwable) {
                caught = e
            }
            assertTrue(caught is UpdateVerificationException)
            // Temp file cleaned up cleanly
            val dir = release.asset.cacheDirectory(updatesDir)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            server.shutdown()
        }
    }
}
