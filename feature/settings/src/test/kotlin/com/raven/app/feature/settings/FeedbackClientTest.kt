package com.raven.app.feature.settings

import com.raven.app.core.common.CustomAppDispatchers
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
class FeedbackClientTest {
    @get:Rule
    val server = MockWebServer()

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val appInfo = RavenAppInfo(
        versionName = "0.9.2",
        versionCode = 15L,
        isDebug = false,
        packageName = "com.raven.app",
    )

    @Test
    fun submitPostsMultipartPayloadAndLog() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"RV-20261127-ABC123","status":"new"}"""))
        val log = folder.newFile("raven.log").apply { writeText("player stalled") }
        val client = FeedbackClient(
            httpClient = OkHttpClient(),
            appInfo = appInfo,
            dispatchers = CustomAppDispatchers(dispatcher),
            endpointOverride = server.url("/api/feedback").toString(),
        )
        val accepted = client.submit(
            FeedbackSubmission(
                category = "playback",
                title = "播放中断",
                body = "切到后台后无法恢复播放",
                appVersion = "0.9.2",
                versionCode = 15L,
                packageName = "com.raven.app",
                deviceModel = "Pixel",
                androidRelease = "14",
                sdkInt = 34,
                locale = "zh-CN",
                installId = "install-1",
            ),
            log,
        )
        assertEquals("RV-20261127-ABC123", accepted.id)
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(recorded.path!!.endsWith("/api/feedback"))
        assertTrue(body.contains("播放中断"))
        assertTrue(body.contains("player stalled"))
        assertFalse(body.contains("cookie"))
    }

    @Test
    fun viewModelRejectsShortInputAndSubmitsValidTicket() = runTest(mainDispatcherRule.dispatcher) {
        val log = folder.newFile("snap.txt").apply { writeText("log line") }
        var captured: FeedbackSubmission? = null
        val client = object : FeedbackClient(
            httpClient = OkHttpClient(),
            appInfo = appInfo,
            dispatchers = mainDispatcherRule.appDispatchers,
        ) {
            override suspend fun submit(submission: FeedbackSubmission, logFile: File?): FeedbackAccepted {
                captured = submission
                return FeedbackAccepted(ok = true, id = "RV-20261127-DEF456", status = "new")
            }
        }
        val diagnostics = object : FeedbackDiagnosticSource {
            private var flag = false
            override fun snapshot(): File? = log
            override fun bytes(): Long = log.length()
            override fun installId(): String = "install-2"
            override fun isVerbose(): Boolean = flag
            override fun setVerbose(enabled: Boolean) { flag = enabled }
            override fun lastErrorSummary(): String? = "Player: codec init failed"
            override fun hasCrashRecord(): Boolean = true
        }
        val vm = FeedbackViewModel(
            feedbackClient = client,
            appInfo = appInfo,
            diagnostics = diagnostics,
            deviceSnapshot = FeedbackDeviceSnapshot(model = "Pixel", androidRelease = "14", sdkInt = 34, locale = "zh-CN"),
            dispatchers = mainDispatcherRule.appDispatchers,
        )
        vm.refreshDiagnostics()
        assertEquals(log.length(), vm.uiState.value.logBytes)
        vm.submit()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.errorMessage)
        assertNull(captured)

        vm.updateTitle("搜索重复")
        vm.updateBody("同一作品在搜索结果里出现两次")
        vm.submit()
        advanceUntilIdle()
        assertEquals("RV-20261127-DEF456", vm.uiState.value.acceptedId)
        assertEquals("", vm.uiState.value.title)
        assertEquals("Pixel", captured?.deviceModel)
        assertEquals("install-2", captured?.installId)
    }
}
