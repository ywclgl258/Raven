package com.raven.app.feature.settings

import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaKind
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavSyncClientTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
            appInfo = RavenAppInfo("1.0.0", 1L, isDebug = false, packageName = "com.raven.app"),
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
        assertTrue(!called)
    }

    @Test
    fun `testConnection pushBackup and pullAndRestore complete full encrypted RVNBK01 round trip`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val codec = BackupCodec(validator = validator, siteRegistry = registry)
        val storage = HashMap<String, ByteArray>()
        var authHeaderSeen: String? = null

        db.favoriteDao.upsert(
            FavoriteEntity(
                siteId = SiteId.R34V.value,
                kind = FavoriteKind.VIDEO,
                targetId = "777",
                title = "Cloud Synced Clip",
                url = "https://rule34video.com/video/777/",
                coverUrl = "https://rule34video.com/cover.jpg",
                addedAt = 1_000L,
                extra = emptyMap(),
            ),
        )

        val fakeWebDavHttp = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                assertEquals("https", req.url.scheme)
                authHeaderSeen = req.header("Authorization")
                val path = req.url.encodedPath
                when (req.method) {
                    "PROPFIND" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(207)
                        .message("Multi-Status")
                        .body("<multistatus/>".toResponseBody("application/xml".toMediaType()))
                        .build()
                    "PUT" -> {
                        val buf = Buffer()
                        req.body!!.writeTo(buf)
                        storage[path] = buf.readByteArray()
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(201)
                            .message("Created")
                            .body(ByteArray(0).toResponseBody(null))
                            .build()
                    }
                    "GET" -> {
                        val payload = storage[path]
                        if (payload == null) {
                            Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(404)
                                .message("Not Found")
                                .body(ByteArray(0).toResponseBody(null))
                                .build()
                        } else {
                            Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                                .build()
                        }
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
            appInfo = RavenAppInfo("1.0.0", 1L, isDebug = false, packageName = "com.raven.app"),
            dispatchers = mainDispatcherRule.appDispatchers,
            clockMs = { 55_000L },
        )

        val cfg = WebDavConfig(
            endpointUrl = "https://dav.example.com/vault",
            username = "raven-user",
            password = "app-password",
            backupPassphrase = "StrongPassphrase#9",
            remoteFileName = "backup.rvnbk",
        )

        val conn = webDav.testConnection(cfg)
        assertTrue(conn is WebDavSyncOutcome.Connected)
        assertNotNull(authHeaderSeen)
        assertTrue(authHeaderSeen!!.startsWith("Basic "))

        val push = webDav.pushBackup(cfg)
        assertTrue(push is WebDavSyncOutcome.PushCompleted)
        val storedBytes = storage["/vault/backup.rvnbk"]
        assertNotNull(storedBytes)
        assertEquals(55_000L, db.preferencesRepository.currentSettings().webDavLastSyncAt)

        // Clear local Room favorites and pull from WebDAV
        db.favoriteDao.deleteAll()
        assertEquals(0, db.favoriteDao.getAll().size)

        val credMap = HashMap<String, String>()
        val vm = BackupRestoreViewModel(
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            safBridge = object : BackupSafBridge {
                override suspend fun writeBytes(documentUri: String, bytes: ByteArray) = Unit
                override suspend fun readBytes(documentUri: String, maxBytes: Int) = ByteArray(0)
                override fun releaseUriGrant(documentUri: String) = Unit
            },
            appInfo = RavenAppInfo("1.0.0", 1L, isDebug = false, packageName = "com.raven.app"),
            dispatchers = mainDispatcherRule.appDispatchers,
            webDavCredentialStore = WebDavCredentialStore(
                cipher = object : VaultCipher {
                    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.copyOf()
                    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.copyOf()
                },
                storage = object : VaultStorage {
                    override fun getString(key: String) = credMap[key]
                    override fun putString(key: String, value: String) { credMap[key] = value }
                    override fun remove(key: String) { credMap.remove(key) }
                    override fun keysWithPrefix(prefix: String): Set<String> = credMap.keys.filter { it.startsWith(prefix) }.toSet()
                    override fun clear() { credMap.clear() }
                },
            ),
            webDavSyncClient = webDav,
            preferencesRepository = db.preferencesRepository,
        )
        vm.updateWebDavEndpointUrl(cfg.endpointUrl)
        vm.updateWebDavUsername(cfg.username)
        vm.updateWebDavPassword(cfg.password)
        vm.updateWebDavPassphrase(cfg.backupPassphrase)
        vm.updateWebDavRemoteFileName(cfg.remoteFileName)
        vm.pullAndRestoreWebDavBackup()
        advanceUntilIdle()

        assertNull(vm.uiState.value.webDavError)
        assertNotNull(vm.uiState.value.webDavStatusMessage)
        assertEquals(1, db.favoriteDao.getAll().size)
        assertEquals("777", db.favoriteDao.getAll().single().targetId)
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

        val cfg = WebDavConfig(
            endpointUrl = "https://dav.example.com/vault",
            username = "raven-user",
            password = "app-password",
            backupPassphrase = "StrongPassphrase#9",
            remoteFileName = "backup.rvnbk",
        )
        val downgradeWebDav = WebDavSyncClient(
            apiClient = downgradeClient,
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            preferencesRepository = db.preferencesRepository,
            appInfo = RavenAppInfo("1.0.0", 1L, isDebug = false, packageName = "com.raven.app"),
            dispatchers = mainDispatcherRule.appDispatchers,
        )
        val downgradeOutcome = downgradeWebDav.testConnection(cfg)
        assertTrue(downgradeOutcome is WebDavSyncOutcome.Error)

        val headRedirectClient = OkHttpClient.Builder()
            .dispatcher(directDispatcher())
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.method == "PROPFIND") {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(405)
                        .message("Method Not Allowed")
                        .body("".toResponseBody(null))
                        .build()
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

        val headRedirectWebDav = WebDavSyncClient(
            apiClient = headRedirectClient,
            backupDataRepository = db.backupDataRepository,
            backupCodec = codec,
            backupValidator = validator,
            preferencesRepository = db.preferencesRepository,
            appInfo = RavenAppInfo("1.0.0", 1L, isDebug = false, packageName = "com.raven.app"),
            dispatchers = mainDispatcherRule.appDispatchers,
        )
        val headOutcome = headRedirectWebDav.testConnection(cfg)
        assertTrue(headOutcome is WebDavSyncOutcome.Error)
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
