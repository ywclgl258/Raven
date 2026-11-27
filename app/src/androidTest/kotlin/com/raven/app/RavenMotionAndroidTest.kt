package com.raven.app

import android.Manifest
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.raven.app.core.designsystem.component.RavenAnimatedCounter
import com.raven.app.core.designsystem.component.RavenExpandableSection
import com.raven.app.core.designsystem.component.RavenPrimaryButton
import com.raven.app.core.designsystem.component.RavenSegmentedTabs
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RavenMotionAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun narrowRtlTabsStaySelectableAtLargeFontSize() {
        val labels = listOf("全部 210", "进行中 12", "已完成 195", "需处理 3")
        val selected = mutableStateOf(labels.first())
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MaterialTheme {
                    RavenSegmentedTabs(labels, selected.value, { it }, { selected.value = it }, Modifier.width(250.dp))
                }
            }
        }

        compose.onNodeWithText(labels.first()).assertIsSelected()
        compose.onNodeWithText(labels.last()).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(labels.last()).assertIsSelected()
    }

    @Test
    fun switchingTabsDoesNotRecomposeLabelsDuringMotion() {
        val labels = listOf("全部", "等待", "完成")
        val selected = mutableStateOf(labels.first())
        var labelReads = 0
        compose.setContent {
            MaterialTheme {
                RavenSegmentedTabs(labels, selected.value, { label -> labelReads++; label }, { selected.value = it })
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(labels.last()).performClick()
        compose.mainClock.advanceTimeBy(48)
        val before = compose.runOnIdle { labelReads }
        compose.mainClock.advanceTimeBy(112)
        compose.runOnIdle { assertEquals("标签内容应在动画帧之间保持稳定", before, labelReads) }
        compose.onNodeWithText(labels[1]).performClick()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(labels[1]).assertIsSelected()
    }

    @Test
    fun accordionCanReverseMidTransitionAndSettlesClosed() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                RavenExpandableSection(
                    expanded = expanded.value,
                    onExpandedChange = { expanded.value = it },
                    title = "管理站点账号",
                    modifier = Modifier.testTag("accountToggle"),
                ) { Text("站点账号") }
            }
        }

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("accountToggle").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("accountToggle").performClick()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("站点账号").assertDoesNotExist()
        compose.onNodeWithTag("accountToggle").performClick()
        compose.onNodeWithText("站点账号").assertIsDisplayed()
    }

    @Test
    fun counterExposesOnlyTheLatestValueAndSnapsInReducedMotion() {
        val count = mutableIntStateOf(9)
        val reduced = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionReduced provides reduced.value) {
                    RavenAnimatedCounter(count.intValue)
                }
            }
        }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { count.intValue = 10 }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("10").assertExists()
        compose.onNodeWithText("9").assertDoesNotExist()
        compose.runOnIdle {
            count.intValue = 8
            reduced.value = true
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("8").assertIsDisplayed()
        compose.onNodeWithText("10").assertDoesNotExist()
    }

    @Test
    fun buttonLabelHoldsItsPositionAcrossLoadingAndResults() {
        val stage = mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                RavenPrimaryButton(
                    text = "导出",
                    onClick = {},
                    leadingIcon = Icons.Outlined.Download,
                    loading = stage.intValue == 1,
                    success = stage.intValue == 2,
                    failure = stage.intValue == 3,
                )
            }
        }

        val label = compose.onNodeWithText("导出", useUnmergedTree = true)
        val restingBounds = label.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        for (nextStage in 1..3) {
            compose.runOnUiThread { stage.intValue = nextStage }
            compose.mainClock.advanceTimeBy(400)
            val bounds = label.getUnclippedBoundsInRoot()
            assertEquals(restingBounds.left.value, bounds.left.value, 0.5f)
            assertEquals(restingBounds.top.value, bounds.top.value, 0.5f)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class RavenMotionNavigationAndroidTest {
    private val notificationPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(notificationPermissionRule)
        .around(compose)

    @Test
    fun switchDownloadFiltersAndToggleTheAccountsSection() {
        waitForTag("browse_home_screen")
        compose.onNodeWithTag("nav_library").performClick()
        waitForTag("library_hub_tabs")
        val localTab = hasAnyAncestor(hasTestTag("library_hub_tabs")) and hasText("本地", substring = true)
        compose.onNode(localTab).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(localTab and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("hub_open_downloads").performClick()
        waitForTag("downloads_screen")
        compose.onNodeWithText("需处理 ", substring = true).performClick().assertIsSelected()
        compose.onNodeWithText("全部 ", substring = true).performClick().assertIsSelected()
        compose.onNodeWithTag("downloads_favorites_back_button").performClick()

        compose.onNodeWithTag("nav_profile").performClick()
        waitForTag("profile_settings_button")
        compose.onNodeWithTag("profile_settings_button").performClick()
        waitForTag("settings_list")
        val accountToggle = compose.onNodeWithTag("settings_manage_accounts_toggle")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_manage_accounts_toggle"))
        accountToggle.performClick()
        compose.onNodeWithTag("settings_account_site_r34v").assertExists()
        accountToggle.performClick()
        compose.onNodeWithTag("settings_account_site_r34v").assertDoesNotExist()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = 15_000L) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
