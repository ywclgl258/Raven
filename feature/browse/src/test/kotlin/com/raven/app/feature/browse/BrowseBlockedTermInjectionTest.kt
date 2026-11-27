package com.raven.app.feature.browse

import app.cash.turbine.test
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies that Home / Explore feed requests carry the site's blocked terms in
 * `advancedFilters.excludeTags`, so plugins that omit tags from listing payloads (nhentai etc.)
 * drop blocked works server-side instead of only relying on the client ContentFilter fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseBlockedTermInjectionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun blankDescriptor(siteId: SiteId, kind: MediaKind = MediaKind.VIDEO) = SiteDescriptor(
        id = siteId,
        displayName = siteId.value,
        shortName = siteId.value,
        primaryKind = kind,
        defaultHost = "${siteId.value}.example",
        capabilities = setOf(SiteCapability.FEED),
    )

    @Test
    fun `home load injects blocked term into pagination request exclude tags`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val siteId = SiteId.NHENTAI
            val plugin = FakeSitePlugin(
                descriptor = blankDescriptor(siteId),
                homeHandler = {
                    HomeFeed(
                        siteId = siteId,
                        sections = listOf(
                            com.raven.app.core.model.FeedSection(
                                id = "latest",
                                title = "最新",
                                items = emptyList(),
                                seeAllRequest = FeedRequest(
                                    siteId = siteId,
                                    section = "latest",
                                    advancedFilters = com.raven.app.core.model.AdvancedSearchFilters(),
                                ),
                            ),
                        ),
                        primaryFeedPage = FeedPage(items = emptyList(), hasMore = true, nextPage = 2),
                    )
                },
                feedHandler = { req -> FeedPage(items = emptyList(), hasMore = false) },
            )

            harness.blockedTermsRepository.addTerm(SiteId.GLOBAL, "big-breasts")
            harness.preferencesRepository.selectActiveSiteAndRecordOpen(siteId)

            val vm = HomeViewModel(
                siteRegistry = SiteRegistry(listOf(plugin)),
                preferencesRepository = harness.preferencesRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                favoriteRepository = harness.favoriteRepository,
                shelfRepository = harness.shelfRepository,
                browsePositionRepository = harness.browsePositionRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                expectMostRecentItem()
                assertFalse(plugin.feedRequests.isNotEmpty())

                vm.loadNextPage()
                advanceUntilIdle()

                val pagedRequest = plugin.feedRequests.lastOrNull()
                assertTrue(
                    "expected at least one paged feed request",
                    pagedRequest != null,
                )
                assertTrue(
                    "blocked term should land in excludeTags",
                    pagedRequest!!.advancedFilters.excludeTags.contains("big-breasts"),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `explore load injects blocked term into feed request exclude tags`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val siteId = SiteId.NHENTAI
            val plugin = FakeSitePlugin(
                descriptor = blankDescriptor(siteId),
                feedHandler = { req ->
                    FeedPage(items = emptyList(), hasMore = false)
                },
            )

            harness.blockedTermsRepository.addTerm(SiteId.GLOBAL, "big-breasts")
            harness.preferencesRepository.selectActiveSiteAndRecordOpen(siteId)

            val vm = ExploreViewModel(
                siteRegistry = SiteRegistry(listOf(plugin)),
                preferencesRepository = harness.preferencesRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                browsePositionRepository = harness.browsePositionRepository,
                dispatchers = harness.dispatchers,
            )

            advanceUntilIdle()
            val initialRequest = plugin.feedRequests.firstOrNull()
            assertTrue(
                "explore trigger-load request should carry the blocked term",
                initialRequest != null && initialRequest.advancedFilters.excludeTags.contains("big-breasts"),
            )
        }
}
