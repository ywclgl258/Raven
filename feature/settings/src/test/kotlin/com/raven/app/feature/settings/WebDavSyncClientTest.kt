package com.raven.app.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.security.BackupCodec
import com.raven.app.core.security.BackupValidator
import com.raven.app.core.security.VaultCipher
import com.raven.app.core.security.VaultStorage
import com.raven.app.core.security.WebDavConfig
import com.raven.app.core.security.WebDavCredentialStore
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavSyncClientTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val baseConfig = WebDavConfig(
        endpointUrl = "https://dav.example.com/vault",
        username = "raven-user",
        password = "app-password",
        backupPassphrase = "StrongPassphrase#9",
        remoteFileName = "backup.rvnbk",
    )

    @Test
    fun `rejects non-HTTPS WebDAV endpoints before sending credentials`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)
        var called = false
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                called = true
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()
        val webDav = WebDavSyncClient(
            apiClient = client,
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            preferencesRepository = db.preferencesRepository,
            appInfo = testAppInfo,
            dispatchers = mainDispatcherRule.appDispatchers,
        )

        val outcome = webDav.pushBackup(
            WebDavConfig(
                endpointUrl = "http://dav.example.com/raven",
                username = "alice",
                password = "secret",
                backupPassphrase = "Passphrase#1",
            ),
        )
        assertTrue(outcome is WebDavSyncOutcome.Error)
        assertFalse(called)
    }

    @Test
    fun `full encrypted RVNBK01 round trip previews before merging and never sends site cookies`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)
        val storage = HashMap<String, ByteArray>()
        var authHeaderSeen: String? = null
        val seenCookies = mutableListOf<String?>()

        db.favoriteDao.upsert(sampleFavorite("777"))

        val fakeWebDavHttp = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                assertEquals("https", req.url.scheme)
                authHeaderSeen = req.header("Authorization")
                seenCookies += req.header("Cookie")
                val path = req.url.encodedPath
                when (req.method) {
                    "PROPFIND" -> xmlResponse(req, 207, "Multi-Status")
                    "PUT" -> {
                        val buf = Buffer()
                        req.body!!.writeTo(buf)
                        storage[path] = buf.readByteArray()
                        emptyResponse(req, 201, "Created")
                    }
                    "GET" -> {
                        val payload = storage[path] ?: return@addInterceptor emptyResponse(req, 404, "Not Found")
                        bytesResponse(req, payload)
                    }
                    else -> error("Unexpected method ${req.method}")
                }
            }
            .build()

        val webDav = WebDavSyncClient(
            apiClient = fakeWebDavHttp,
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            preferencesRepository = db.preferencesRepository,
            appInfo = testAppInfo,
            dispatchers = mainDispatcherRule.appDispatchers,
            clockMs = { 55_000L },
        )

        val conn = webDav.testConnection(baseConfig)
        assertTrue(conn is WebDavSyncOutcome.Connected)
        assertNotNull(authHeaderSeen)
        assertTrue(authHeaderSeen!!.startsWith("Basic "))

        val push = webDav.pushBackup(baseConfig)
        assertTrue(push is WebDavSyncOutcome.PushCompleted)
        push as WebDavSyncOutcome.PushCompleted
        assertTrue(push.syncTimestampSaved)
        assertNull(push.timestampWarning)
        assertNotNull(storage["/vault/backup.rvnbk"])
        assertEquals(55_000L, db.preferencesRepository.currentSettings().webDavLastSyncAt)

        // Wipe local Room favorites; a preview must not repopulate them.
        db.favoriteDao.deleteAll()
        assertEquals(0, db.favoriteDao.getAll().size)

        val preview = webDav.pullPreview(baseConfig)
        assertTrue(preview is WebDavSyncOutcome.PullPreviewReady)
        preview as WebDavSyncOutcome.PullPreviewReady
        assertEquals("backup.rvnbk", preview.remoteFileName)
        assertEquals(1, preview.summary.favoritesCount)
        assertEquals("dav.example.com/vault", preview.endpointLabel)
        assertEquals(0, db.favoriteDao.getAll().size)

        val applied = webDav.applyConfirmedPull(preview)
        assertTrue(applied is WebDavSyncOutcome.PullCompleted)
        applied as WebDavSyncOutcome.PullCompleted
        assertTrue(applied.syncTimestampSaved)
        assertEquals(1, db.favoriteDao.getAll().size)
        assertEquals("777", db.favoriteDao.getAll().single().targetId)

        // Dedicated client must carry NO_COOKIES: nothing may ever leak to a third-party DAV host.
        assertTrue(seenCookies.isNotEmpty())
        assertTrue(seenCookies.all { it == null })
    }

    @Test
    fun `ViewModel pull previews and only merges on confirm, cancel leaves local data untouched`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)
        val storage = HashMap<String, ByteArray>()

        db.favoriteDao.upsert(sampleFavorite("777"))

        val http = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                when (req.method) {
                    "PUT" -> {
                        val buf = Buffer()
                        req.body!!.writeTo(buf)
                        storage[path] = buf.readByteArray()
                        emptyResponse(req, 201, "Created")
                    }
                    "GET" -> bytesResponse(req, storage[path]!!)
                    else -> xmlResponse(req, 207, "Multi-Status")
                }
            }
            .build()

        val webDav = WebDavSyncClient(
            apiClient = http,
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            preferencesRepository = db.preferencesRepository,
            appInfo = testAppInfo,
            dispatchers = mainDispatcherRule.appDispatchers,
            clockMs = { 90_000L },
        )
        assertTrue(webDav.pushBackup(baseConfig) is WebDavSyncOutcome.PushCompleted)
        db.favoriteDao.deleteAll()

        val vm = createViewModel(db, codec, validator, webDav)
        vm.updateWebDavEndpointUrl(baseConfig.endpointUrl)
        vm.updateWebDavUsername(baseConfig.username)
        vm.updateWebDavPassword(baseConfig.password)
        vm.updateWebDavPassphrase(baseConfig.backupPassphrase)
        vm.updateWebDavRemoteFileName(baseConfig.remoteFileName)

        vm.pullAndPreviewWebDavBackup()
        advanceUntilIdle()

        assertNull(vm.uiState.value.webDavError)
        val preview = vm.uiState.value.webDavPullPreview
        assertNotNull(preview)
        assertEquals(1, preview!!.summary.favoritesCount)
        assertEquals(0, db.favoriteDao.getAll().size)
        assertNull(vm.uiState.value.webDavRestoreCompleted)

        vm.cancelWebDavPullPreview()
        advanceUntilIdle()
        assertNull(vm.uiState.value.webDavPullPreview)
        assertEquals(0, db.favoriteDao.getAll().size)

        vm.pullAndPreviewWebDavBackup()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.webDavPullPreview)
        vm.confirmWebDavRestore()
        advanceUntilIdle()

        assertNull(vm.uiState.value.webDavError)
        assertEquals(1, db.favoriteDao.getAll().size)
        assertEquals("777", db.favoriteDao.getAll().single().targetId)
        assertEquals(1, vm.uiState.value.webDavRestoreCompleted!!.mergedSummary.favoritesCount)
        assertEquals(90_000L, vm.uiState.value.webDavLastSyncAt)
        assertNull(vm.uiState.value.webDavPullPreview)
    }

    @Test
    fun `redirect to a foreign origin is rejected and the PUT body is never forwarded`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)

        var foreignOriginSawBody = false
        val client = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.host != "dav.example.com") {
                    if (req.method == "PUT") foreignOriginSawBody = true
                    return@addInterceptor emptyResponse(req, 200, "OK")
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(307)
                    .message("Temporary Redirect")
                    .header("Location", "https://evil.example/backup.rvnbk")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        val webDav = newClient(db, codec, validator, client)
        val outcome = webDav.pushBackup(baseConfig)

        assertTrue(outcome is WebDavSyncOutcome.Error)
        assertFalse(foreignOriginSawBody)
    }

    @Test
    fun `redirect that downgrades to http is rejected before any body is sent`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)

        var downgradedOriginSawBody = false
        val client = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.scheme != "https") {
                    if (req.method == "PUT") downgradedOriginSawBody = true
                    return@addInterceptor emptyResponse(req, 200, "OK")
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(308)
                    .message("Permanent Redirect")
                    .header("Location", "http://dav.example.com/vault/backup.rvnbk")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        val webDav = newClient(db, codec, validator, client)
        assertTrue(webDav.pushBackup(baseConfig) is WebDavSyncOutcome.Error)
        assertFalse(downgradedOriginSawBody)
    }

    @Test
    fun `redirect to the same host on a different effective port is rejected`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)

        var altPortSawBody = false
        val client = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.port != 443 && req.url.port != -1) {
                    if (req.method == "PUT") altPortSawBody = true
                    return@addInterceptor emptyResponse(req, 200, "OK")
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(308)
                    .message("Permanent Redirect")
                    .header("Location", "https://dav.example.com:8443/vault/backup.rvnbk")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        val webDav = newClient(db, codec, validator, client)
        assertTrue(webDav.pushBackup(baseConfig) is WebDavSyncOutcome.Error)
        assertFalse(altPortSawBody)
    }

    @Test
    fun `same-origin redirect preserving the method is followed for upload and download`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)
        val storage = HashMap<String, ByteArray>()

        db.favoriteDao.upsert(sampleFavorite("555"))

        val client = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                if (path == "/vault/backup.rvnbk") {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(307)
                        .message("Temporary Redirect")
                        .header("Location", "/dav-root/backup.rvnbk")
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                }
                when (req.method) {
                    "PUT" -> {
                        val buf = Buffer()
                        req.body!!.writeTo(buf)
                        storage[path] = buf.readByteArray()
                        emptyResponse(req, 201, "Created")
                    }
                    "GET" -> bytesResponse(req, storage[path]!!)
                    else -> xmlResponse(req, 207, "Multi-Status")
                }
            }
            .build()

        val webDav = newClient(db, codec, validator, client)
        assertTrue(webDav.pushBackup(baseConfig) is WebDavSyncOutcome.PushCompleted)
        assertNull(storage["/vault/backup.rvnbk"])
        assertNotNull(storage["/dav-root/backup.rvnbk"])
        assertTrue(webDav.pullPreview(baseConfig) is WebDavSyncOutcome.PullPreviewReady)
    }

    @Test
    fun `endpoint with query string keeps the file name as a path segment and preserves the query`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)

        val recordedPaths = mutableListOf<String>()
        val recordedQueries = mutableListOf<String?>()
        val recordedFragments = mutableListOf<String?>()

        val client = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                recordedPaths += req.url.encodedPath
                recordedQueries += req.url.query
                recordedFragments += req.url.fragment
                emptyResponse(req, 201, "Created")
            }
            .build()

        val webDav = newClient(db, codec, validator, client)
        val outcome = webDav.pushBackup(
            baseConfig.copy(endpointUrl = "https://dav.example.com/vault?token=abc&dir=1#section"),
        )
        assertTrue(outcome is WebDavSyncOutcome.PushCompleted)
        assertEquals("/vault/backup.rvnbk", recordedPaths.single())
        assertEquals("token=abc&dir=1", recordedQueries.single())
        assertNull(recordedFragments.single())
    }

    @Test
    fun `302 on a PUT is not silently degraded to GET and reports the upload as failed`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)

        val methodsSeen = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                methodsSeen += req.method
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://dav.example.com/dav-root/backup.rvnbk")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        val webDav = newClient(db, codec, validator, client)
        val outcome = webDav.pushBackup(baseConfig)

        assertTrue(outcome is WebDavSyncOutcome.Error)
        // The PUT is issued once and never replayed as a GET that would fake a success.
        assertEquals(listOf("PUT"), methodsSeen)
        assertFalse(db.preferencesRepository.currentSettings().webDavLastSyncAt > 0L)
    }

    @Test
    fun `timestamp write failure after a successful upload is a warning, not a failed sync`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)

        val failingPrefs = PreferencesRepository(
            settingsDataStore = SettingsDataStore(FailingUpdateDataStore()),
            playbackPreferencesDao = db.playbackPreferencesDao,
            readerPreferencesDao = db.readerPreferencesDao,
        )

        val client = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain -> emptyResponse(chain.request(), 201, "Created") }
            .build()

        val webDav = WebDavSyncClient(
            apiClient = client,
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            preferencesRepository = failingPrefs,
            appInfo = testAppInfo,
            dispatchers = mainDispatcherRule.appDispatchers,
        )

        val push = webDav.pushBackup(baseConfig)
        assertTrue(push is WebDavSyncOutcome.PushCompleted)
        push as WebDavSyncOutcome.PushCompleted
        assertFalse(push.syncTimestampSaved)
        assertNotNull(push.timestampWarning)
        assertTrue(push.timestampWarning!!.contains("云端操作已成功"))
        assertTrue(push.uploadedBytes > 0)
    }

    @Test
    fun `testConnection rejects HTTPS downgrade and cross-host redirect on 405 HEAD probe`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)

        val downgradeClient = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                val downgraded = req.newBuilder()
                    .url(req.url.newBuilder().scheme("http").build())
                    .build()
                Response.Builder()
                    .request(downgraded)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

        val downgradeWebDav = newClient(db, codec, validator, downgradeClient)
        assertTrue(downgradeWebDav.testConnection(baseConfig) is WebDavSyncOutcome.Error)

        val headRedirectClient = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.method == "PROPFIND") {
                    emptyResponse(req, 405, "Method Not Allowed")
                } else {
                    val foreignReq = req.newBuilder().url("https://evil.example/backup.rvnbk").build()
                    Response.Builder()
                        .request(foreignReq)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("".toResponseBody(null))
                        .build()
                }
            }
            .build()

        val headRedirectWebDav = newClient(db, codec, validator, headRedirectClient)
        assertTrue(headRedirectWebDav.testConnection(baseConfig) is WebDavSyncOutcome.Error)
    }

    @Test
    fun `credential save failure on push keeps upload success and reports not saved`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)

        val client = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain -> emptyResponse(chain.request(), 201, "Created") }
            .build()
        val webDav = newClient(db, codec, validator, client)

        val readOnlyStore = WebDavCredentialStore(
            cipher = passthroughCipher(),
            storage = object : VaultStorage {
                override fun getString(key: String): String? = null
                override fun putString(key: String, value: String) =
                    throw IllegalStateException("vault read-only")
                override fun remove(key: String) = throw IllegalStateException("vault read-only")
                override fun keysWithPrefix(prefix: String): Set<String> = emptySet()
                override fun clear() = throw IllegalStateException("vault read-only")
            },
        )

        val vm = BackupRestoreViewModel(
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            safBridge = InMemoryBackupSafBridge(),
            appInfo = testAppInfo,
            dispatchers = mainDispatcherRule.appDispatchers,
            webDavCredentialStore = readOnlyStore,
            webDavSyncClient = webDav,
            preferencesRepository = db.preferencesRepository,
        )
        vm.updateWebDavEndpointUrl(baseConfig.endpointUrl)
        vm.updateWebDavUsername(baseConfig.username)
        vm.updateWebDavPassword(baseConfig.password)
        vm.updateWebDavPassphrase(baseConfig.backupPassphrase)
        vm.updateWebDavRemoteFileName(baseConfig.remoteFileName)

        vm.pushWebDavBackup()
        advanceUntilIdle()

        assertNull(vm.uiState.value.webDavError)
        assertNotNull(vm.uiState.value.webDavStatusMessage)
        assertFalse(vm.uiState.value.webDavSaved)
        assertTrue(vm.uiState.value.webDavWarnings.any { it.contains("WebDAV 配置") })
    }

    @Test
    fun `credential clear failure reports error and keeps the visible fields`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)

        val store = WebDavCredentialStore(
            cipher = passthroughCipher(),
            storage = object : VaultStorage {
                private val map = HashMap<String, String>()
                override fun getString(key: String): String? = map[key]
                override fun putString(key: String, value: String) { map[key] = value }
                override fun remove(key: String) = throw IllegalStateException("clear failed")
                override fun keysWithPrefix(prefix: String): Set<String> = emptySet()
                override fun clear() = throw IllegalStateException("clear failed")
            },
        )
        store.save(baseConfig)

        val vm = BackupRestoreViewModel(
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            safBridge = InMemoryBackupSafBridge(),
            appInfo = testAppInfo,
            dispatchers = mainDispatcherRule.appDispatchers,
            webDavCredentialStore = store,
            preferencesRepository = db.preferencesRepository,
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.webDavSaved)

        vm.clearWebDavConfig()
        advanceUntilIdle()

        // Clear failed, so the record is still stored: the UI must keep claiming "saved" and
        // surface an error instead of a success message.
        assertTrue(vm.uiState.value.webDavSaved)
        assertNotNull(vm.uiState.value.webDavError)
        assertNull(vm.uiState.value.webDavStatusMessage)
        assertEquals(baseConfig.endpointUrl, vm.uiState.value.webDavEndpointUrl)
        assertTrue(vm.uiState.value.webDavPassword.isNotEmpty())
    }

    // ---- helpers ---------------------------------------------------------------------------

    private val testAppInfo = RavenAppInfo("1.0.0", 1L, isDebug = false, packageName = "com.raven.app")

    private fun newClient(
        db: FakeDatabaseBundle,
        codec: BackupCodec,
        validator: BackupValidator,
        http: OkHttpClient,
    ): WebDavSyncClient = WebDavSyncClient(
        apiClient = http,
        backupDataRepository = db.backupDataRepository,
        backupCodec = codec,
        backupValidator = validator,
        preferencesRepository = db.preferencesRepository,
        appInfo = testAppInfo,
        dispatchers = mainDispatcherRule.appDispatchers,
    )

    private fun createViewModel(
        db: FakeDatabaseBundle,
        codec: BackupCodec,
        validator: BackupValidator,
        client: WebDavSyncClient,
    ): BackupRestoreViewModel = BackupRestoreViewModel(
        backupDataRepository = db.backupDataRepository,
        backupCodec = codec,
        backupValidator = validator,
        safBridge = InMemoryBackupSafBridge(),
        appInfo = testAppInfo,
        dispatchers = mainDispatcherRule.appDispatchers,
        webDavSyncClient = client,
        preferencesRepository = db.preferencesRepository,
    )

    private fun sampleFavorite(targetId: String) = FavoriteEntity(
        siteId = SiteId.R34V.value,
        kind = FavoriteKind.VIDEO,
        targetId = targetId,
        title = "Cloud Synced Clip",
        url = "https://rule34video.com/video/$targetId/",
        coverUrl = "https://rule34video.com/cover.jpg",
        addedAt = 1_000L,
        extra = emptyMap(),
    )

    private fun passthroughCipher() = object : VaultCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.copyOf()
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.copyOf()
    }

    private fun emptyResponse(req: okhttp3.Request, code: Int, message: String) = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body(ByteArray(0).toResponseBody(null))
        .build()

    private fun xmlResponse(req: okhttp3.Request, code: Int, message: String) = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body("<multistatus/>".toResponseBody("application/xml".toMediaType()))
        .build()

    private fun bytesResponse(req: okhttp3.Request, payload: ByteArray) = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(payload.toResponseBody("application/octet-stream".toMediaType()))
        .build()

    /** DataStore whose every write fails, to exercise post-success timestamp failures. */
    private class FailingUpdateDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw IllegalStateException("prefs write failed")
    }

    private fun directDispatcher(): Dispatcher = Dispatcher(
        object : AbstractExecutorService() {
            private var shutdown = false
            override fun shutdown() { shutdown = true }
            override fun shutdownNow(): List<Runnable> { shutdown = true; return emptyList() }
            override fun isShutdown(): Boolean = shutdown
            override fun isTerminated(): Boolean = shutdown
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
            override fun execute(command: Runnable) { command.run() }
        },
    )
}
