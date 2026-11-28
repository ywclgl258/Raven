package com.raven.app

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.media3.database.DatabaseProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raven.app.core.database.RavenDatabase
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.media.download.VideoDownloadEngine
import com.raven.app.core.media.playback.DownloadCaches
import com.raven.app.core.media.playback.MediaTransportDataSourceFactory
import com.raven.app.core.media.storage.StorageRootManager
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.sitespi.SiteRegistry
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoDownloadQueueAndroidTest {
    @Test
    fun movingAndPromotingWaitingVideosChangesWhichDownloadActuallyStarts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "video-queue-").toFile()
        val databaseName = "${root.name}.db"
        fun openDatabase() = Room.databaseBuilder(context, RavenDatabase::class.java, databaseName).build()
        var db = openDatabase()
        var repository = DownloadStateRepository(db.downloadItemDao())
        val index = object : SQLiteOpenHelper(context, root.resolve("media3.db").path, null, 1), DatabaseProvider {
            override fun onCreate(db: SQLiteDatabase) = Unit
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val third = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val gate = when (request.url.encodedPath) {
                "/active-1" -> first
                "/active-2" -> second
                "/C" -> third
                else -> null
            }
            check(gate == null || gate.await(25, TimeUnit.SECONDS))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Length", "4096")
                .body(ByteArray(4096).toResponseBody("video/mp4".toMediaType())).build()
        }.build()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val registry = SiteRegistry(emptyList())
        val transport = MediaTransportDataSourceFactory(client) { registry }
        val storage = StorageRootManager(context = null, settingsDataStore = null, privateRootOverride = root)
        var caches = DownloadCaches(context, storage, transport, scope, databaseProvider = index)
        var engine = VideoDownloadEngine(context, caches, transport, storage, repository, { registry }, scope)

        fun request(id: String) = DownloadRequest.Builder(id, Uri.parse("https://raven-queue.example/$id"))
            .setCustomCacheKey(id)
            .setData("""{"siteId":"r34v","mediaId":"$id","title":"$id","url":"https://raven-queue.example/$id","quality":"test"}""".toByteArray())
            .build()

        suspend fun await(assertion: suspend () -> Boolean) {
            withTimeout(15_000) { while (!assertion()) delay(20) }
        }

        try {
            withContext(Dispatchers.Main) {
                engine.manager.requirements = Requirements(0)
            }
            await { withContext(Dispatchers.Main) { engine.manager.isInitialized && !engine.manager.downloadsPaused } }
            withContext(Dispatchers.Main) {
                engine.manager.addDownload(request("active-1"))
                engine.manager.addDownload(request("active-2"))
            }
            await {
                withContext(Dispatchers.Main) {
                    engine.manager.currentDownloads.count { it.state == Download.STATE_DOWNLOADING } == 2
                }
            }
            withContext(Dispatchers.Main) {
                listOf("A", "B", "C").forEach { engine.manager.addDownload(request(it), 2) }
            }
            await { listOf("A", "B", "C").all { repository.getById(it)?.status == DownloadStatus.QUEUED } }
            engine.movePriority("C", +1)
            engine.movePriority("A", -1)
            engine.prioritize("B")
            engine.prioritize("C")
            assertEquals(listOf("C", "B", "A"), listOf("A", "B", "C")
                .map { requireNotNull(repository.getById(it)) }.sortedByDescending { it.priority }.map { it.id })

            val nextStarted = java.util.concurrent.LinkedBlockingQueue<String>()
            suspend fun observeStarts() = withContext(Dispatchers.Main) {
                engine.manager.addListener(object : androidx.media3.exoplayer.offline.DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        if (download.state == Download.STATE_DOWNLOADING && download.request.id in setOf("A", "B", "C")) {
                            nextStarted.add(download.request.id)
                        }
                    }
                })
            }
            observeStarts()
            first.countDown()
            assertEquals("C", nextStarted.poll(12, TimeUnit.SECONDS))
            assertEquals(Download.STATE_DOWNLOADING, withContext(Dispatchers.Main) {
                engine.manager.currentDownloads.single { it.request.id == "active-2" }.state
            })

            withContext(Dispatchers.Main) {
                engine.manager.pauseDownloads()
                engine.manager.setStopReason("active-2", VideoDownloadEngine.STOP_REASON_MANUAL_PAUSE)
                engine.manager.setStopReason("C", VideoDownloadEngine.STOP_REASON_MANUAL_PAUSE)
            }
            second.countDown()
            third.countDown()
            await { listOf("active-2", "C").all { repository.getById(it)?.status == DownloadStatus.PAUSED } }
            withContext(Dispatchers.Main) { engine.manager.release() }
            scope.coroutineContext[Job]?.cancelAndJoin()
            caches.close()
            db.close()

            db = openDatabase()
            repository = DownloadStateRepository(db.downloadItemDao())
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            caches = DownloadCaches(context, storage, transport, scope, databaseProvider = index)
            engine = VideoDownloadEngine(context, caches, transport, storage, repository, { registry }, scope)
            nextStarted.clear()
            observeStarts()
            assertEquals("B", nextStarted.poll(12, TimeUnit.SECONDS))
            assertEquals("A", nextStarted.poll(12, TimeUnit.SECONDS))
        } finally {
            withContext(NonCancellable) {
                first.countDown()
                second.countDown()
                third.countDown()
                runCatching { withContext(Dispatchers.Main) { engine.manager.release() } }
                scope.coroutineContext[Job]?.cancelAndJoin()
                runCatching { caches.close() }
                runCatching { index.close() }
                runCatching { db.close() }
                context.deleteDatabase(databaseName)
                root.deleteRecursively()
            }
        }
    }
}
