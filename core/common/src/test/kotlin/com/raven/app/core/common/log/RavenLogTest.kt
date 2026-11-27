package com.raven.app.core.common.log

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RavenLogTest {
    @Before
    fun setUp() {
        RavenLog.resetForTest()
        LogClock.setForTest { 1_700_000_000_000 }
        Thread.setDefaultUncaughtExceptionHandler(null)
    }

    @After
    fun tearDown() {
        RavenLog.resetForTest()
        LogClock.setForTest(null)
        Thread.setDefaultUncaughtExceptionHandler(null)
    }

    @Test
    fun infoAndAboveAreCapturedWhileDebugNeedsVerbose() {
        RavenLog.d("Tag", "debug line")
        RavenLog.i("Tag", "info line")
        assertEquals(1, RavenLog.snapshot().size)

        RavenLog.setVerbose(true)
        RavenLog.d("Tag", "debug line")
        assertEquals(2, RavenLog.snapshot().size)
        assertEquals("Tag", RavenLog.snapshot().last().tag)
    }

    @Test
    fun ringBufferDropsOldestEntries() {
        val capacity = RavenLog.DEFAULT_RING_CAPACITY
        repeat(capacity + 25) { index -> RavenLog.i("Tag", "event $index") }
        val snapshot = RavenLog.snapshot()
        assertEquals(capacity, snapshot.size)
        assertEquals("event 25", snapshot.first().message)
        assertEquals("event ${capacity + 24}", snapshot.last().message)
    }

    @Test
    fun secretsAreRedactedBeforeTheyReachASink() {
        val captured = mutableListOf<String>()
        RavenLog.addSink { entry -> captured.add(entry.message + (entry.throwable ?: "")) }
        val secretToken = "tk_live_" + "9f2ab9c1d4"
        val secretSession = "sess_" + "7c1d2e3f4a5b6c7d"
        val secretJwt = "JwtHead" + "erValue" + ".payloadpart" + ".signaturepart"
        RavenLog.w(
            "Auth",
            "GET https://api.example/v1/items?token=$secretToken&page=2 Cookie: $secretSession; " +
                "Authorization: Bearer $secretJwt",
        )
        RavenLog.e("Auth", "login failed password=" + "hunter2")
        val joined = captured.joinToString("\n")
        assertFalse(joined.contains(secretToken))
        assertFalse(joined.contains(secretSession))
        assertFalse(joined.contains("payloadpart"))
        assertFalse(joined.contains("hunter2"))
        assertTrue(joined.contains("[redacted]"))
        assertTrue(joined.contains("page=2"))
    }

    @Test
    fun reportPutsErrorsFirstAndKeepsTimeline() {
        RavenLog.i("Download", "queued 3 items")
        RavenLog.e("Download", "CDN 403", IllegalStateException("blocked"))
        RavenLog.i("Reader", "chapter 4 opened")
        val report = RavenLog.report(mapOf("版本" to "0.9.2"))
        val errorIndex = report.indexOf("CDN 403")
        val timelineIndex = report.indexOf("事件时间线")
        assertTrue(errorIndex in 1 until timelineIndex)
        assertTrue(report.contains("版本: 0.9.2"))
        assertTrue(report.contains("IllegalStateException: blocked"))
        assertTrue(report.contains("chapter 4 opened"))
    }

    @Test
    fun crashSinkKeepsErrorsAndIgnoresInfo() {
        val dir = Files.createTempDirectory("raven-log").toFile()
        val sink = CrashFileSink(File(dir, "crash.log"))
        RavenLog.addSink(sink)
        RavenLog.i("Tag", "not a crash")
        RavenLog.e("Tag", "boom", IllegalArgumentException("bad state"))
        val text = sink.tail()
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("IllegalArgumentException"))
        assertFalse(text.contains("not a crash"))
        dir.deleteRecursively()
    }

    @Test
    fun rollingFileRotatesAndKeepsPreviousSession() {
        val dir = Files.createTempDirectory("raven-log").toFile()
        val sink = LogFileSink(File(dir, "raven.log"), maxBytes = 400)
        RavenLog.addSink(sink)
        repeat(30) { index -> RavenLog.i("Tag", "line $index " + "x".repeat(40)) }
        assertTrue(File(dir, "raven.log.1").isFile)
        assertTrue(sink.tail(4_000).contains("line 29"))
        dir.deleteRecursively()
    }

    @Test
    fun diagnosticsWritesUploadableReport() {
        val dir = Files.createTempDirectory("raven-diag").toFile()
        val diagnostics = Diagnostics(
            timelineFile = File(dir, "raven.log"),
            crashFile = File(dir, "crash.log"),
            header = { mapOf("包名" to "com.raven.app", "设备" to "Pixel") },
            logcat = { "E/AndroidRuntime: boom" },
        )
        diagnostics.install()
        RavenLog.e("Player", "codec init failed", RuntimeException("vm crash"))
        val text = diagnostics.writeSnapshot(File(dir, "report.txt")).readText()
        assertTrue(text.contains("Raven 诊断报告"))
        assertTrue(text.contains("包名: com.raven.app"))
        assertTrue(text.contains("codec init failed"))
        assertTrue(text.contains("logcat"))
        assertTrue(text.contains("日志文件尾部"))
        assertNotNull(diagnostics.lastErrorSummary())
        dir.deleteRecursively()
    }

    @Test
    fun crashRecorderIsInstalledOnce() {
        assertTrue(CrashRecorder.install())
        assertFalse(CrashRecorder.install())
        assertTrue(CrashRecorder.isInstalled())
    }
}
