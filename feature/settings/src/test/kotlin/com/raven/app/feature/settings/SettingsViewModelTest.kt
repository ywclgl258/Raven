package com.raven.app.feature.settings

import app.cash.turbine.test
import com.raven.app.core.media.reader.GalleryPageRepository
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.SiteId
import com.raven.app.core.network.HostPacer
import com.raven.app.core.network.Ipv4FirstDns
import com.raven.app.core.network.MediaRedirectCache
import com.raven.app.core.network.NetworkDiagnostics
import com.raven.app.core.network.NetworkMonitor
import com.raven.app.core.network.PeerTracker
import com.raven.app.core.network.RateLimitLog
import com.raven.app.core.network.RavenOkHttpPool
import com.raven.app.core.network.SiteCookieJar
import com.raven.app.core.network.SiteHttpClient
import com.raven.app.core.network.SystemProxySelector
import com.raven.app.core.security.PrivacyLockStore
import com.raven.app.core.security.VaultCipher
import com.raven.app.core.security.VaultStorage
import com.raven.app.core.sitespi.InMemorySiteSessionStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildFixture(
        server: MockWebServer? = null,
        isDebug: Boolean = false,
    ): Pair<SettingsViewModel, FakeDatabaseBundle> {
        val db = FakeDatabaseBundle()
        val sessionStore = InMemorySiteSessionStore()
        sessionStore.saveCookies(SiteId.R34V, mapOf("PHPSESSID" to "abc"))
        sessionStore.saveUsername(SiteId.R34V, "raven-user")

        val registry = createTestSiteRegistry(sessionStore = sessionStore)
        val privateDir = tempFolder.newFolder("private-${System.nanoTime()}")
        val sharedDir = tempFolder.newFolder("shared-${System.nanoTime()}")
        val localDir = tempFolder.newFolder("local-${System.nanoTime()}")
        val readingCacheRoot = tempFolder.newFolder("reading-cache-${System.nanoTime()}")
        val updatesDir = tempFolder.newFolder("updates-${System.nanoTime()}")

        // Seed two dummy reading-cache page files for nhentai
        val nhentaiCacheDir = File(readingCacheRoot, "nhentai").apply { mkdirs() }
        File(nhentaiCacheDir, "aaa.image").writeBytes(ByteArray(2048) { 1 })
        File(nhentaiCacheDir, "bbb.image").writeBytes(ByteArray(1024) { 2 })

        val storageRootManager = StorageRootManager(
            context = null,
            settingsDataStore = db.settingsDataStore,
            privateRootOverride = privateDir,
            sharedRootOverride = sharedDir,
            sharedPermissionProvider = { true },
            dispatchers = mainDispatcherRule.appDispatchers,
        )
        val localImporter = LocalLibraryImporter(
            context = null,
            rootDir = localDir,
            dispatchers = mainDispatcherRule.appDispatchers,
            requireAndroidImageDecoder = false,
        )
        val galleryPageRepo = GalleryPageRepository(
            context = null,
            imageClient = OkHttpClient(),
            storageRootManager = storageRootManager,
            localLibraryImporter = localImporter,
            siteRegistryProvider = { registry },
            dispatchers = mainDispatcherRule.appDispatchers,
            cacheRootOverride = readingCacheRoot,
            requireAndroidImageDecoder = false,
        )

        val proxySelector = SystemProxySelector()
        val connectionPool = ConnectionPool(4, 1, TimeUnit.MINUTES)
        val peerTracker = PeerTracker()
        val rateLimitLog = RateLimitLog()
        val networkMonitor = NetworkMonitor(
            context = null,
            proxySelector = proxySelector,
            connectionPool = connectionPool,
        )
        val okHttpPool = RavenOkHttpPool(
            cacheDir = null,
            connectionPool = connectionPool,
            dispatcher = Dispatcher(),
            dns = Ipv4FirstDns(),
            proxySelector = proxySelector,
            cookieJar = SiteCookieJar(sessionStore, registry),
            mediaRedirectCache = MediaRedirectCache(eligible = { false }, rateLimitLog = rateLimitLog),
            peerTracker = peerTracker,
            siteRegistryProvider = { registry },
        )
        val siteHttpClient = SiteHttpClient(
            okHttpPool = okHttpPool,
            hostPacer = HostPacer(),
            sessionStore = sessionStore,
            networkMonitor = networkMonitor,
            rateLimitLog = rateLimitLog,
            siteRegistryProvider = { registry },
            dispatchers = mainDispatcherRule.appDispatchers,
        )
        val diagnostics = NetworkDiagnostics(
            httpClient = siteHttpClient,
            networkMonitor = networkMonitor,
            proxySelector = proxySelector,
            peerTracker = peerTracker,
            rateLimitLog = rateLimitLog,
            siteRegistry = registry,
        )

        val appInfo = RavenAppInfo(
            versionName = "1.0.0",
            versionCode = 1L,
            isDebug = isDebug,
            packageName = "com.raven.app",
        )
        val updater = RavenUpdater(
            appInfo = appInfo,
            metadataClient = okHttpPool.apiClient,
            downloadClient = okHttpPool.imageClient,
            updatesDir = updatesDir,
            signatureVerifier = object : ApkSignatureVerifier {
                override fun verify(apkFile: File) = Unit
            },
            stateStore = InMemoryUpdateStateStore(),
            dispatchers = mainDispatcherRule.appDispatchers,
            releasesApiBaseUrlOverride = server?.url("/repos/ywclgl258/raven/releases")?.toString(),
        )
        val lockStorage = object : VaultStorage {
            private val map = HashMap<String, String>()
            override fun getString(key: String): String? = map[key]
            override fun putString(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun keysWithPrefix(prefix: String): Set<String> = map.keys.filter { it.startsWith(prefix) }.toSet()
            override fun clear() { map.clear() }
        }
        val xorCipher = object : VaultCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext.copyOf()
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = ciphertext.copyOf()
        }
        val privacyLockStore = PrivacyLockStore(cipher = xorCipher, storage = lockStorage)

        val vm = SettingsViewModel(
            preferencesRepository = db.preferencesRepository,
            blockedTermsRepository = db.blockedTermsRepository,
            siteRegistry = registry,
            galleryPageRepository = galleryPageRepo,
            storageRootManager = storageRootManager,
            networkDiagnostics = diagnostics,
            networkMonitor = networkMonitor,
            ravenUpdater = updater,
            appInfo = appInfo,
            dispatchers = mainDispatcherRule.appDispatchers,
            privacyLockStore = privacyLockStore,
        )
        return vm to db
    }

    @Test
    fun `persists appearance playback cache download and update preferences reactively`() = runTest {
        val (vm, db) = buildFixture()

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(5, initial.authSites.size)
            assertTrue(initial.authSites.first { it.descriptor.id == SiteId.R34V }.authState.isAuthenticated)
            assertFalse(initial.settings.autoFullscreen)
            assertNull(initial.settings.startupDefaultSiteId)
            assertEquals(2, initial.readingCache.totalPages)
            assertEquals(3072L, initial.readingCache.totalBytes)

            vm.setReduceTransparency(true)
            vm.setUiSoundEffectsEnabled(false)
            vm.setTranslateTags(false)
            vm.setAutoFullscreen(true)
            vm.setPreferredQuality("1080p")
            vm.setStartupDefaultSiteId(SiteId.NHENTAI)
            vm.setDefaultPlaybackSpeed(1.62f) // snaps to 1.50x
            vm.setStreamCacheEnabled(true)
            vm.setSeekCacheEnabled(false)
            vm.setDownloadUnmeteredOnly(true)
            vm.setAutoUpdateCheck(false)
            advanceUntilIdle()

            val updated = expectMostRecentItem()
            assertTrue(updated.settings.reduceTransparency)
            assertFalse(updated.settings.uiSoundEffectsEnabled)
            assertFalse(updated.settings.translateTags)
            assertTrue(updated.settings.autoFullscreen)
            assertEquals("1080p", updated.settings.preferredQuality)
            assertEquals(SiteId.NHENTAI, updated.settings.startupDefaultSiteId)
            assertEquals(SiteId.R34V, updated.settings.activeSiteId)
            assertEquals(1.5f, updated.settings.defaultPlaybackSpeed, 0.001f)
            assertTrue(updated.settings.streamCacheEnabled)
            assertFalse(updated.settings.seekCacheEnabled)
            assertTrue(updated.settings.downloadUnmeteredOnly)
            assertFalse(updated.settings.autoUpdateCheck)

            // Verify DataStore persistence matches UI state
            val persisted = db.preferencesRepository.currentSettings()
            assertTrue(persisted.autoFullscreen)
            assertEquals("1080p", persisted.preferredQuality)
            assertEquals(SiteId.NHENTAI, persisted.startupDefaultSiteId)
            assertEquals(1.5f, persisted.defaultPlaybackSpeed, 0.001f)

            vm.setStartupDefaultSiteId(null)
            advanceUntilIdle()
            assertNull(expectMostRecentItem().settings.startupDefaultSiteId)
            assertNull(db.preferencesRepository.currentSettings().startupDefaultSiteId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearAllReadingCaches wipes cached gallery pages and updates summary to zero`() = runTest {
        val (vm, _) = buildFixture()

        vm.uiState.test {
            advanceUntilIdle()
            val before = expectMostRecentItem()
            assertEquals(2, before.readingCache.totalPages)
            assertEquals(3072L, before.readingCache.totalBytes)

            vm.clearAllReadingCaches()
            advanceUntilIdle()

            val after = expectMostRecentItem()
            assertEquals(0, after.readingCache.totalPages)
            assertEquals(0L, after.readingCache.totalBytes)
            assertNotNull(after.feedbackMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prepareDiagnosticsSnapshot populates proxy and routeEpoch without network traffic`() = runTest {
        val (vm, _) = buildFixture()

        vm.uiState.test {
            advanceUntilIdle()
            vm.prepareDiagnosticsSnapshot(SiteId.NHENTAI)
            advanceUntilIdle()

            val state = expectMostRecentItem()
            val report = state.diagnostics.report
            assertNotNull(report)
            assertEquals(SiteId.NHENTAI, report!!.siteId)
            assertEquals("https://nhentai.net", report.targetUrl)
            assertEquals(0L, report.routeEpoch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `manual update check surfaces available release from GitHub API`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": 9001,
                      "tag_name": "v1.2.0",
                      "name": "Raven 1.2.0",
                      "body": "- Obsidian UI polish",
                      "html_url": "https://github.com/ywclgl258/raven/releases/tag/v1.2.0",
                      "assets": [
                        {
                          "id": 77,
                          "name": "raven-1.2.0-universal-release.apk",
                          "size": 1024,
                          "browser_download_url": "https://example.invalid/raven.apk"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        server.start()
        try {
            val (vm, _) = buildFixture(server = server, isDebug = false)
            vm.uiState.test {
                expectMostRecentItem()
                vm.checkForUpdates(manual = true)

                var state = awaitItem()
                while (state.updateState !is UpdateUiState.Available) {
                    state = awaitItem()
                }
                val available = state.updateState
                assertEquals("v1.2.0", available.release.tagName)
                assertEquals("raven-1.2.0-universal-release.apk", available.release.asset.fileName)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `privacy PIN guard, incognito, playback and reader settings, and cache clear work`() = runTest {
        val (vm, db) = buildFixture()

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertFalse(initial.hasAppPin)
            assertFalse(initial.settings.appLockEnabled)

            // Enabling app lock without configuring a PIN must stay false and explain why
            vm.setAppLockEnabled(true)
            advanceUntilIdle()
            val blocked = expectMostRecentItem()
            assertFalse(blocked.settings.appLockEnabled)
            assertNotNull(blocked.feedbackMessage)

            // Configure valid PIN and verify lock + timeout + incognito + screen secure + reader settings persist
            assertTrue(vm.configureAppPin("2580"))
            vm.setAppLockTimeoutSeconds(60)
            vm.setIncognitoMode(true)
            vm.setPrivacyScreenSecure(true)
            vm.setAutoplayVideo(false)
            vm.setBackgroundAudioPlayback(true)
            vm.setReaderTapZonesEnabled(false)
            vm.setReaderShowStatusBar(true)
            vm.setReaderPrefetchPages(5)
            vm.setReaderMemoryBudgetMb(48)
            vm.setReaderAutoSplitWidePages(true)
            vm.setBackgroundSubscriptionSync(false)
            vm.setBlockTagsGlobally(false)
            advanceUntilIdle()

            val updated = expectMostRecentItem()
            assertTrue(updated.hasAppPin)
            assertTrue(updated.settings.appLockEnabled)
            assertEquals(60, updated.settings.appLockTimeoutSeconds)
            assertTrue(updated.settings.incognitoMode)
            assertTrue(updated.settings.privacyScreenSecure)
            assertFalse(updated.settings.autoplayVideo)
            assertTrue(updated.settings.backgroundAudioPlayback)
            assertFalse(updated.settings.readerTapZonesEnabled)
            assertTrue(updated.settings.readerShowStatusBar)
            assertEquals(5, updated.settings.readerPrefetchPages)
            assertEquals(48, updated.settings.readerMemoryBudgetMb)
            assertTrue(updated.settings.readerAutoSplitWidePages)
            assertFalse(updated.settings.backgroundSubscriptionSync)
            assertFalse(updated.settings.blockTagsGlobally)

            vm.clearAppPin()
            advanceUntilIdle()
            val cleared = expectMostRecentItem()
            assertFalse(cleared.hasAppPin)
            assertFalse(cleared.settings.appLockEnabled)
            assertFalse(db.preferencesRepository.currentSettings().appLockEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
