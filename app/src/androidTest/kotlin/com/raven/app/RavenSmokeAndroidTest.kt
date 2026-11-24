package com.raven.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end offline smoke tests running directly against the real [MainActivity] on an emulator
 * or device with zero external internet dependency.
 */
@RunWith(AndroidJUnit4::class)
class RavenSmokeAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String, timeoutMillis: Long = 15_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun launchRendersHomeAndSwitchesAllFiveTopDestinations() {
        waitForTag("raven_adaptive_scaffold")
        waitForTag("browse_home_screen")
        composeRule.onNodeWithTag("browse_home_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("raven_site_switcher_pill").assertIsDisplayed()

        // Switch through Explore -> Search -> Library -> Profile -> Home
        composeRule.onNodeWithTag("nav_explore").performClick()
        waitForTag("browse_explore_screen")
        composeRule.onNodeWithTag("browse_explore_screen").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_search").performClick()
        waitForTag("browse_search_screen")
        composeRule.onNodeWithTag("browse_search_screen").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_library").performClick()
        waitForTag("library_hub_screen")
        composeRule.onNodeWithTag("library_hub_screen").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_profile").performClick()
        waitForTag("profile_screen")
        composeRule.onNodeWithTag("profile_screen").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_home").performClick()
        waitForTag("browse_home_screen")
        composeRule.onNodeWithTag("browse_home_screen").assertIsDisplayed()
    }

    @Test
    fun siteSwitcherDisplaysGroupedSitesAndSwitchesCleanly() {
        waitForTag("browse_home_screen")
        composeRule.onNodeWithTag("raven_site_switcher_pill").performClick()

        waitForTag("site_switcher_sheet")
        composeRule.onNodeWithTag("site_switcher_content").assertIsDisplayed()

        // Verify all 3 media groups render (VIDEO / COMIC / PHOTO)
        composeRule.onNodeWithText("视频").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("site_tile_r34v").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("漫画").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("site_tile_nhentai").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("写真").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("site_tile_4khd").performScrollTo().assertIsDisplayed()

        // Switch to comic site (nhentai) and verify sheet closes cleanly
        composeRule.onNodeWithTag("site_tile_nhentai").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("site_switcher_sheet").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("browse_home_screen").assertIsDisplayed()

        // Switch back to default video site (r34v) to keep state clean
        composeRule.onNodeWithTag("raven_site_switcher_pill").performClick()
        waitForTag("site_switcher_sheet")
        composeRule.onNodeWithTag("site_tile_r34v").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("site_switcher_sheet").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("browse_home_screen").assertIsDisplayed()
    }

    @Test
    fun navigateToSettingsDisplaysAppleHigGroupedSectionsAndHidesBottomNav() {
        waitForTag("browse_home_screen")

        composeRule.onNodeWithTag("nav_profile").performClick()
        waitForTag("profile_screen")

        composeRule.onNodeWithTag("profile_settings_button").performClick()
        waitForTag("settings_screen")
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        // Sub-routes hide the primary bottom/rail navigation chrome
        composeRule.onNodeWithTag("nav_home").assertDoesNotExist()

        // Verify Apple HIG grouped sections render across Settings list
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasText("外观与阅读"))
        composeRule.onNodeWithTag("settings_reduce_transparency").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasText("播放与缓存"))
        composeRule.onNodeWithText("播放与缓存").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasText("下载与存储"))
        composeRule.onNodeWithText("下载与存储").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasText("数据与备份"))
        composeRule.onNodeWithTag("settings_backup_restore_row").assertIsDisplayed()

        // Navigate back to Profile and confirm top-level navigation reappears
        composeRule.onNodeWithTag("settings_back_button").performClick()
        waitForTag("profile_screen")
        composeRule.onNodeWithTag("nav_home").assertIsDisplayed()
    }
}
