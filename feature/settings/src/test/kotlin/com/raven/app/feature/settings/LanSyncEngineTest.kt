package com.raven.app.feature.settings

import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.ReadingProgressEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.security.BackupValidator
import com.raven.app.core.security.InMemoryVaultStorage
import com.raven.app.core.security.LanSyncTrustStore
import com.raven.app.core.security.SoftwareAesGcmVaultCipher
import com.raven.app.feature.settings.lansync.LanSyncAddressValidator
import com.raven.app.feature.settings.lansync.LanSyncBeacon
import com.raven.app.feature.settings.lansync.LanSyncClient
import com.raven.app.feature.settings.lansync.LanSyncErrorCode
import com.raven.app.feature.settings.lansync.LanSyncException
import com.raven.app.feature.settings.lansync.LanSyncFrameCodec
import com.raven.app.feature.settings.lansync.LanSyncIncomingEvent
import com.raven.app.feature.settings.lansync.LanSyncMode
import com.raven.app.feature.settings.lansync.LanSyncServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LanSyncEngineTest {

    private data class PeerNode(
        val db: FakeDatabaseBundle,
        val trustStore: LanSyncTrustStore,
        val server: LanSyncServer,
        val client: LanSyncClient,
    )

    private fun createNode(deviceName: String, clockMs: () -> Long = { 1_730_000_005_000L }): PeerNode {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()
        val validator = BackupValidator(registry)
        val trustStore = LanSyncTrustStore(
            cipher = SoftwareAesGcmVaultCipher(),
            storage = InMemoryVaultStorage(),
        ).also { it.loadOrCreate(deviceName) }
        val ioDispatchers = CustomAppDispatchers(
            main = Dispatchers.Default,
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )
        val server = LanSyncServer(
            backupDataRepository = db.backupDataRepository,
            backupValidator = validator,
            trustStore = trustStore,
            appVersion = "0.9.5",
            dispatchers = ioDispatchers,
            defaultDeviceName = deviceName,
            pbkdf2Iterations = 500,
            allowLoopbackForTest = true,
            clock = clockMs,
        )
        val client = LanSyncClient(
            backupDataRepository = db.backupDataRepository,
            backupValidator = validator,
            trustStore = trustStore,
            appVersion = "0.9.5",
            dispatchers = ioDispatchers,
            defaultDeviceName = deviceName,
            pbkdf2Iterations = 500,
            allowLoopbackForTest = true,
            clock = clockMs,
        )
        return PeerNode(db = db, trustStore = trustStore, server = server, client = client)
    }

    @Test
    fun `address validator beacon codec and frame codec enforce RFC1918 and bounds`() {
        assertTrue(LanSyncAddressValidator.isAllowedLanIpv4("192.168.1.42"))
        assertTrue(LanSyncAddressValidator.isAllowedLanIpv4("10.0.0.5"))
        assertTrue(LanSyncAddressValidator.isAllowedLanIpv4("172.16.0.1"))
        assertTrue(LanSyncAddressValidator.isAllowedLanIpv4("172.31.255.254"))

        assertFalse(LanSyncAddressValidator.isAllowedLanIpv4("127.0.0.1"))
        assertTrue(LanSyncAddressValidator.isAllowedLanIpv4("127.0.0.1", allowLoopback = true))
        assertFalse(LanSyncAddressValidator.isAllowedLanIpv4("169.254.169.254"))
        assertFalse(LanSyncAddressValidator.isAllowedLanIpv4("172.15.255.254"))
        assertFalse(LanSyncAddressValidator.isAllowedLanIpv4("172.32.0.1"))
        assertFalse(LanSyncAddressValidator.isAllowedLanIpv4("8.8.8.8"))

        assertEquals(
            "192.168.10.22" to 45874,
            LanSyncAddressValidator.parseManualEndpoint("192.168.10.22"),
        )
        assertEquals(
            "10.1.2.3" to 49000,
            LanSyncAddressValidator.parseManualEndpoint("10.1.2.3:49000"),
        )
        assertNull(LanSyncAddressValidator.parseManualEndpoint("http://192.168.10.22:45874"))
        assertNull(LanSyncAddressValidator.parseManualEndpoint("169.254.169.254:45874"))
        assertNull(LanSyncAddressValidator.parseManualEndpoint("192.168.1.2:80"))

        val beacon = LanSyncBeacon(
            deviceId = "dev-a",
            deviceName = "Raven Tab",
            appVersion = "0.9.5",
            tcpPort = 45874,
        )
        val decoded = LanSyncBeacon.decode(beacon.encode(), beacon.encode().size)
        assertEquals(beacon, decoded)
        assertNull(LanSyncBeacon.decode("bad-json".toByteArray(), 8))

        val out = ByteArrayOutputStream()
        LanSyncFrameCodec.writeFrame(out, LanSyncFrameCodec.FRAME_HELLO, "abc".toByteArray())
        val frame = LanSyncFrameCodec.readFrame(ByteArrayInputStream(out.toByteArray()), maxPayloadBytes = 64)
        assertEquals(LanSyncFrameCodec.FRAME_HELLO, frame.type)
        assertEquals("abc", String(frame.payload))
        assertThrows(LanSyncException::class.java) {
            LanSyncFrameCodec.readFrame(ByteArrayInputStream(out.toByteArray()), maxPayloadBytes = 2)
        }
    }

    @Test
    fun `two way sync via PIN converges both databases remembers trust and supports one-tap trusted re-sync`() = runBlocking {
        val nodeA = createNode("主力手机")
        val nodeB = createNode("客厅平板")

        // Seed distinct library records on A and B
        nodeA.db.favoriteDao.upsert(
            FavoriteEntity(
                siteId = SiteId.R34V.value,
                kind = FavoriteKind.VIDEO,
                targetId = "v-a1",
                title = "Phone Video",
                url = "https://rule34video.com/video/101/phone/",
                coverUrl = "https://rule34video.com/c1.jpg",
                addedAt = 100L,
                extra = emptyMap(),
            ),
        )
        nodeA.db.watchProgressDao.upsert(
            WatchProgressEntity(
                siteId = SiteId.R34V.value,
                mediaId = "v-a1",
                url = "https://rule34video.com/video/101/phone/",
                title = "Phone Video",
                coverUrl = "",
                positionMs = 15_000L,
                durationMs = 90_000L,
                updatedAt = 200L,
                completed = false,
                quality = "1080p",
            ),
        )
        nodeB.db.favoriteDao.upsert(
            FavoriteEntity(
                siteId = SiteId.NHENTAI.value,
                kind = FavoriteKind.COMIC,
                targetId = "177013",
                title = "Tablet Comic",
                url = "https://nhentai.net/g/177013/",
                coverUrl = "https://t3.nhentai.net/galleries/1/cover.jpg",
                addedAt = 150L,
                extra = emptyMap(),
            ),
        )
        nodeB.db.readingProgressDao.upsert(
            ReadingProgressEntity(
                siteId = SiteId.NHENTAI.value,
                mediaId = "177013",
                url = "https://nhentai.net/g/177013/",
                title = "Tablet Comic",
                coverUrl = "",
                kind = MediaKind.COMIC,
                currentPage = 12,
                totalPages = 30,
                scrollOffsetPx = 0,
                updatedAt = 250L,
                completed = false,
            ),
        )

        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = mutableListOf<LanSyncIncomingEvent>()
        try {
            val portB = nodeB.server.start(serverScope, onIncomingEvent = { events += it })
            val pinB = nodeB.server.currentPin()
            val idA = nodeA.trustStore.loadOrCreate("主力手机").localDeviceId
            val idB = nodeB.trustStore.loadOrCreate("客厅平板").localDeviceId

            // 1. Initial pairing TWO_WAY sync with PIN
            val outcome1 = nodeA.client.syncTwoWay(
                host = "127.0.0.1",
                port = portB,
                expectedDeviceId = idB,
                pin = pinB,
                rememberDevice = true,
            )
            assertTrue(outcome1.pairedNewDevice)
            assertEquals(2, outcome1.mergedSummary.favoritesCount)
            assertEquals(2, nodeA.db.favoriteDao.getAll().size)
            assertEquals(2, nodeB.db.favoriteDao.getAll().size)
            assertEquals(1, nodeA.db.readingProgressDao.getAll().size)
            assertEquals(1, nodeB.db.watchProgressDao.getAll().size)
            assertNotNull(nodeA.trustStore.findTrustedPeer(idB))
            assertNotNull(nodeB.trustStore.findTrustedPeer(idA))
            assertEquals(1, events.size)

            // 2. Trusted re-sync with no PIN propagates new changes cleanly
            nodeA.db.favoriteDao.upsert(
                FavoriteEntity(
                    siteId = SiteId.HANIME1.value,
                    kind = FavoriteKind.VIDEO,
                    targetId = "h-22",
                    title = "New Video",
                    url = "https://hanime1.me/watch?v=22",
                    coverUrl = "",
                    addedAt = 300L,
                    extra = emptyMap(),
                ),
            )
            val outcome2 = nodeA.client.syncTwoWay(
                host = "127.0.0.1",
                port = portB,
                expectedDeviceId = idB,
                pin = null,
                rememberDevice = true,
            )
            assertFalse(outcome2.pairedNewDevice)
            assertEquals(3, outcome2.mergedSummary.favoritesCount)
            assertEquals(3, nodeB.db.favoriteDao.getAll().size)
        } finally {
            nodeB.server.stop()
            serverScope.cancel()
        }
    }

    @Test
    fun `wrong pin rate-limits after 5 failures and rotates pin while untrusted pinless sync is rejected`() = runBlocking {
        val nodeA = createNode("Phone")
        val nodeB = createNode("Tablet")
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rotatedPins = mutableListOf<String>()
        try {
            val portB = nodeB.server.start(serverScope, onPinRotated = { rotatedPins += it })
            val initialPin = nodeB.server.currentPin()
            val idB = nodeB.trustStore.loadOrCreate("Tablet").localDeviceId

            // Pinless connection without prior trust fails validation cleanly before handshake or via NOT_TRUSTED
            val notTrustedErr = try {
                nodeA.client.syncTwoWay(host = "127.0.0.1", port = portB, expectedDeviceId = idB, pin = null)
                null
            } catch (e: LanSyncException) {
                e
            }
            assertNotNull(notTrustedErr)

            // 5 failed PIN attempts trigger rate limiting & rotation
            val wrongPin = if (initialPin == "123456") "654321" else "123456"
            repeat(4) {
                val err = try {
                    nodeA.client.syncTwoWay(host = "127.0.0.1", port = portB, expectedDeviceId = idB, pin = wrongPin)
                    null
                } catch (e: LanSyncException) {
                    e
                }
                assertEquals(LanSyncErrorCode.AUTH_FAILED, err?.errorCode)
            }
            val fifthErr = try {
                nodeA.client.syncTwoWay(host = "127.0.0.1", port = portB, expectedDeviceId = idB, pin = wrongPin)
                null
            } catch (e: LanSyncException) {
                e
            }
            assertEquals(LanSyncErrorCode.RATE_LIMITED, fifthErr?.errorCode)
            assertEquals(1, rotatedPins.size)
            assertNotEquals(initialPin, nodeB.server.currentPin())
        } finally {
            nodeB.server.stop()
            serverScope.cancel()
        }
    }

    @Test
    fun `push only modifies server and pull preview stages without mutating client until confirmed`() = runBlocking {
        val nodeA = createNode("Sender")
        val nodeB = createNode("Receiver")
        nodeA.db.favoriteDao.upsert(
            FavoriteEntity(
                siteId = SiteId.R34V.value,
                kind = FavoriteKind.VIDEO,
                targetId = "a-1",
                title = "From A",
                url = "https://rule34video.com/video/1/a/",
                coverUrl = "",
                addedAt = 10L,
                extra = emptyMap(),
            ),
        )
        nodeB.db.favoriteDao.upsert(
            FavoriteEntity(
                siteId = SiteId.R34V.value,
                kind = FavoriteKind.VIDEO,
                targetId = "b-1",
                title = "From B",
                url = "https://rule34video.com/video/2/b/",
                coverUrl = "",
                addedAt = 20L,
                extra = emptyMap(),
            ),
        )

        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val portB = nodeB.server.start(serverScope)
            val pinB = nodeB.server.currentPin()
            val idB = nodeB.trustStore.loadOrCreate("Receiver").localDeviceId

            // PUSH: A -> B, A still only has its own favorite; B has both
            val pushOutcome = nodeA.client.pushSnapshot(
                host = "127.0.0.1",
                port = portB,
                expectedDeviceId = idB,
                pin = pinB,
                rememberDevice = true,
            )
            assertEquals(LanSyncMode.PUSH, pushOutcome.mode)
            assertEquals(2, nodeB.db.favoriteDao.getAll().size)
            assertEquals(1, nodeA.db.favoriteDao.getAll().size)

            // Seed one more exclusive record on B, then PULL preview into A
            nodeB.db.favoriteDao.upsert(
                FavoriteEntity(
                    siteId = SiteId.R34V.value,
                    kind = FavoriteKind.VIDEO,
                    targetId = "b-2",
                    title = "From B Only",
                    url = "https://rule34video.com/video/3/b2/",
                    coverUrl = "",
                    addedAt = 30L,
                    extra = emptyMap(),
                ),
            )

            val preview = nodeA.client.pullPreview(
                host = "127.0.0.1",
                port = portB,
                expectedDeviceId = idB,
            )
            assertEquals(3, preview.summary.favoritesCount)
            // Crucial: A's local database is STILL untouched during preview!
            assertEquals(1, nodeA.db.favoriteDao.getAll().size)

            val applied = nodeA.client.applyConfirmedPull(preview)
            assertEquals(3, applied.mergedSummary.favoritesCount)
            assertEquals(3, nodeA.db.favoriteDao.getAll().size)
        } finally {
            nodeB.server.stop()
            serverScope.cancel()
        }
    }
}
