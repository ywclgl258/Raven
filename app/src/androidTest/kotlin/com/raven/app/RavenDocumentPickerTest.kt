package com.raven.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.raven.app.core.model.RavenRoute
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class RavenDocumentPickerTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(compose)

    @Test
    fun backupCompletesWhenHostIsRecreatedWithSystemSaveDialogOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = MainActivityIntentContract.createNavigateIntent(context, RavenRoute.BackupRestore)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("backup_restore_screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("backup_export_password_field").performScrollTo().performTextInput("test-backup-123")
            compose.onNodeWithTag("backup_export_confirm_field").performScrollTo().performTextInput("test-backup-123")
            var original: MainActivity? = null
            scenario.onActivity { original = it }
            compose.onNodeWithTag("backup_export_submit_button").performScrollTo().performClick()
            waitForSystemSaveButton()
            scenario.onActivity { it.recreate() }
            waitForSystemSaveButton().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            compose.waitUntil(20_000) { original?.isDestroyed == true }
            compose.waitUntil(30_000) {
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("backup_export_submit_button"))
                compose.onAllNodesWithTag("backup_export_success_banner").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    @Test
    fun installerRestoresVerifiedFileWhileSettingsIsOpenAndClearsItOnDenial() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.junit.Assume.assumeFalse(context.packageManager.canRequestPackageInstalls())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File.createTempFile("picker-regression-", ".apk", folder)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("browse_home_screen").fetchSemanticsNodes().isNotEmpty() }
                lateinit var original: MainActivity
                scenario.onActivity {
                    original = it
                    it.openAppUpdateInstaller(apk)
                }
                compose.waitUntil(20_000) {
                    val packageName = automation.rootInActiveWindow?.packageName
                    packageName != null && packageName != context.packageName
                }
                scenario.onActivity { stopped ->
                    val state = android.os.Bundle()
                    instrumentation.callActivityOnSaveInstanceState(stopped, state)
                    assertEquals(apk.absolutePath, state.getString("pending_install_apk"))
                    stopped.recreate()
                }
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName == context.packageName && original.isDestroyed }
                scenario.onActivity { resumed ->
                    val state = android.os.Bundle()
                    instrumentation.callActivityOnSaveInstanceState(resumed, state)
                    org.junit.Assert.assertNull(state.getString("pending_install_apk"))
                }
            }
        } finally {
            apk.delete()
        }
    }

    private fun waitForSystemSaveButton(timeoutMs: Long = 20_000L): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastPackage: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            lastPackage = root?.packageName?.toString()
            if (root != null && root.packageName?.contains("documentsui", ignoreCase = true) == true) {
                findSaveAction(root)?.let { return it }
            }
            SystemClock.sleep(50)
        }
        error("System document picker did not show its Save action (last package=$lastPackage)")
    }

    private fun findSaveAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val labels = listOf(
            node.text?.toString().orEmpty(),
            node.contentDescription?.toString().orEmpty(),
        )
        if (node.isEnabled && labels.any { it.contains("save", ignoreCase = true) || it.contains("保存") }) {
            if (node.isClickable) return node
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) return parent
                parent = parent.parent
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findSaveAction(child)?.let { return it }
        }
        return null
    }
}
