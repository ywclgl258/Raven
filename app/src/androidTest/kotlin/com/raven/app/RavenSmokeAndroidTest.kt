package com.raven.app

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * End-to-end offline smoke tests running directly against the real [MainActivity] on an emulator
 * or device with zero external internet dependency.
 */
@RunWith(AndroidJUnit4::class)
class RavenSmokeAndroidTest {

    private val notificationPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(notificationPermissionRule)
        .around(composeRule)

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
    fun serialHistoryShowsOneWorkAndTheMostRecentChapter() {
        waitForTag("browse_home_screen")
        val repository = GlobalContext.get().get<HistoryRepository>()
        val work = UUID.randomUUID().toString()
        val workTitle = "连载历史测试 $work"
        val now = System.currentTimeMillis()
        try {
            runBlocking {
                for (chapter in 1..2) {
                    repository.saveReadingProgress(
                        ReadingProgress(
                            siteId = SiteId.MANGACOPY, mediaId = "$work:$chapter",
                            url = "https://example.org/comic/$work/chapter/$chapter", title = "${work.take(8)} 第${chapter}话",
                            kind = MediaKind.SERIAL_COMIC, currentPage = chapter + 1, totalPages = 20,
                            updatedAt = now + chapter, seriesId = work, seriesTitle = workTitle,
                            seriesUrl = "https://example.org/comic/$work",
                        ),
                    )
                }
            }
            composeRule.onNodeWithTag("nav_library").performClick()
            waitForTag("library_hub_screen")
            composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("metric_tile_history"))
            composeRule.onNodeWithTag("metric_tile_history").performScrollTo().performClick()
            waitForTag("history_screen")
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(workTitle).fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithText(workTitle).assertIsDisplayed()
            composeRule.onNodeWithText("${work.take(8)} 第2话", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("${work.take(8)} 第1话", substring = true).assertDoesNotExist()
        } finally {
            runBlocking { repository.removeReadingHistory(SiteId.MANGACOPY, work) }
        }
    }

    @Test
    fun libraryDownloadsFavoritesHubPreservesPrimaryActionsAndReturnNavigation() {
        waitForTag("browse_home_screen")
        composeRule.onNodeWithTag("nav_library").performClick()
        waitForTag("library_hub_screen")

        composeRule.onNodeWithTag("hub_open_downloads").performClick()
        waitForTag("downloads_favorites_screen")
        composeRule.onNodeWithTag("downloads_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("downloads_sync_disk_button").assertIsDisplayed()

        composeRule.onNodeWithTag("collection_tab_favorites").performClick()
        composeRule.onNodeWithTag("favorites_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("favorites_multiselect_button").assertIsDisplayed()

        composeRule.onNodeWithTag("collection_tab_downloads").performClick()
        composeRule.onNodeWithTag("downloads_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("downloads_sync_disk_button").assertIsDisplayed()
        composeRule.onNodeWithTag("downloads_favorites_back_button").performClick()
        waitForTag("library_hub_screen")

        // The favorites entry must open the same hub directly on its favorites tab.
        composeRule.onNodeWithTag("hub_open_favorites").performClick()
        waitForTag("downloads_favorites_screen")
        composeRule.onNodeWithTag("favorites_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("favorites_multiselect_button").assertIsDisplayed()
        composeRule.onNodeWithTag("downloads_favorites_back_button").performClick()
        waitForTag("library_hub_screen")
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

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_site_preferences_row"))
        composeRule.onNodeWithTag("settings_site_preferences_row").performClick()
        waitForTag("browse_site_functions_screen")
        composeRule.onNodeWithTag("browse_site_functions_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("site_functions_back_button").performClick()
        waitForTag("settings_screen")

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_startup_default_site_row"))
        composeRule.onNodeWithTag("settings_startup_default_site_row").performClick()
        waitForTag("settings_startup_default_site_sheet")
        composeRule.onNodeWithTag("settings_startup_default_site_mangacopy")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("settings_startup_default_site_sheet").fetchSemanticsNodes().isEmpty()
        }

        // Restore the non-overriding default so this test does not affect later cold launches.
        composeRule.onNodeWithTag("settings_startup_default_site_row").performClick()
        waitForTag("settings_startup_default_site_sheet")
        composeRule.onNodeWithTag("settings_startup_default_site_remember").performClick()

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

        // The main menu exposes the same current-site preference hub without duplicating global settings.
        composeRule.onNodeWithTag("profile_list")
            .performScrollToNode(hasTestTag("profile_site_preferences_row"))
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodes(
                hasTestTag("profile_site_preferences_row").and(hasClickAction()),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("profile_site_preferences_row").performClick()
        waitForTag("browse_site_functions_screen")
        composeRule.onNodeWithTag("site_functions_back_button").performClick()
        waitForTag("profile_screen")
        composeRule.onNodeWithTag("nav_home").assertIsDisplayed()
    }
}
