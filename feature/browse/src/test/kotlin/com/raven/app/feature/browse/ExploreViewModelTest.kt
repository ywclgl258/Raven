package com.raven.app.feature.browse

import app.cash.turbine.test
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `persists per-site explore filters across site switches and clears cleanly`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val r34Sections = listOf(
                SiteSectionSpec(id = "latest", title = "最新更新", defaultSort = FeedSort.NEWEST),
                SiteSectionSpec(id = "popular", title = "热门视频", category = "hot", defaultSort = FeedSort.VIEWS),
            )
            val r34Plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.R34V,
                    displayName = "Rule34Video",
                    shortName = "R34V",
                    primaryKind = MediaKind.VIDEO,
                    defaultHost = "rule34video.com",
                    feedSorts = listOf(FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.RATING),
                    sections = r34Sections,
                    capabilities = setOf(SiteCapability.EXPLORE, SiteCapability.DIRECTORY),
                ),
                feedHandler = { req ->
                    FeedPage(
                        items = listOf(
                            sampleMediaItem(
                                SiteId.R34V,
                                "${req.section}_${req.sort.name}_p${req.page}",
                                MediaKind.VIDEO,
                            ),
                        ),
                        hasMore = false,
                    )
                },
                directoryHandler = { _, _, _ -> emptyList() },
                directorySectionsList = listOf(SiteSectionSpec(id = "tags", title = "标签", isDirectory = true)),
            )

            val ehPlugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.EHENTAI,
                    displayName = "E-Hentai",
                    shortName = "E-Hentai",
                    primaryKind = MediaKind.COMIC,
                    defaultHost = "e-hentai.org",
                    capabilities = setOf(SiteCapability.EXPLORE, SiteCapability.LANGUAGE_FILTER),
                    sections = listOf(
                        SiteSectionSpec(id = "doujinshi", title = "同人志", category = "doujinshi"),
                    ),
                ),
                feedHandler = { req ->
                    FeedPage(
                        items = listOf(
                            sampleMediaItem(
                                SiteId.EHENTAI,
                                "eh_${req.language.name}_${req.category.orEmpty()}_p${req.page}",
                                MediaKind.COMIC,
                            ),
                        ),
                        hasMore = false,
                    )
                },
            )

            val vm = ExploreViewModel(
                siteRegistry = SiteRegistry(listOf(r34Plugin, ehPlugin)),
                preferencesRepository = harness.preferencesRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                browsePositionRepository = harness.browsePositionRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                val initial = expectMostRecentItem()
                assertEquals(SiteId.R34V, initial.activeSite?.id)
                assertEquals(1, initial.directorySections.size)

                // Select section + sort on R34V -> persisted
                vm.selectSection(r34Sections[1])
                vm.selectSort(FeedSort.RATING)
                advanceUntilIdle()
                val filteredR34 = expectMostRecentItem()
                assertEquals("popular", filteredR34.filter.section)
                assertEquals("hot", filteredR34.filter.category)
                assertEquals(FeedSort.RATING, filteredR34.filter.sort)
                assertTrue(filteredR34.hasActiveFilter)
                assertEquals(
                    "popular",
                    harness.preferencesRepository.currentSettings().exploreFilterFor(SiteId.R34V).section,
                )

                // Switch to E-Hentai -> set Chinese language
                vm.selectSite(SiteId.EHENTAI)
                advanceUntilIdle()
                vm.selectLanguage(ComicLanguage.CHINESE)
                advanceUntilIdle()
                val ehChinese = expectMostRecentItem()
                assertEquals(ComicLanguage.CHINESE, ehChinese.filter.language)
                assertEquals(MediaKind.COMIC, ehChinese.effectiveMediaKind)

                // Switch back to R34V -> persisted filter restored
                vm.selectSite(SiteId.R34V)
                advanceUntilIdle()
                val restoredR34 = expectMostRecentItem()
                assertEquals("popular", restoredR34.filter.section)
                assertEquals(FeedSort.RATING, restoredR34.filter.sort)

                // Clear filters -> resets cleanly
                vm.clearFilters()
                advanceUntilIdle()
                val cleared = expectMostRecentItem()
                assertFalse(cleared.hasActiveFilter)
                assertTrue(harness.preferencesRepository.currentSettings().exploreFilterFor(SiteId.R34V).isEmpty)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cursor pagination preserves page 1 on page 2 error and succeeds on retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            var failPage2 = true

            val ehPlugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.R34V,
                    displayName = "CursorSite",
                    shortName = "Cursor",
                    primaryKind = MediaKind.COMIC,
                    defaultHost = "cursor.example",
                    capabilities = setOf(SiteCapability.EXPLORE),
                ),
                feedHandler = { req ->
                    when (req.page) {
                        1 -> FeedPage(
                            items = listOf(
                                sampleMediaItem(SiteId.R34V, "g1", MediaKind.COMIC),
                                sampleMediaItem(SiteId.R34V, "g2", MediaKind.COMIC),
                            ),
                            nextPage = 2,
                            nextCursor = "cursor_abc",
                            hasMore = true,
                        )
                        else -> {
                            if (failPage2) {
                                throw SiteException.Network("Timeout on page 2", siteId = SiteId.R34V)
                            }
                            assertEquals("cursor_abc", req.cursor)
                            FeedPage(
                                items = listOf(
                                    sampleMediaItem(SiteId.R34V, "g2", MediaKind.COMIC), // duplicate
                                    sampleMediaItem(SiteId.R34V, "g3", MediaKind.COMIC),
                                ),
                                nextPage = null,
                                nextCursor = null,
                                hasMore = false,
                            )
                        }
                    }
                },
            )

            val vm = ExploreViewModel(
                siteRegistry = SiteRegistry(listOf(ehPlugin)),
                preferencesRepository = harness.preferencesRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                browsePositionRepository = harness.browsePositionRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                val p1 = expectMostRecentItem()
                assertEquals(listOf("g1", "g2"), p1.items.map { it.id })
                assertTrue(p1.hasMore)

                // Trigger page 2 failure
                vm.loadNextPage()
                advanceUntilIdle()
                val afterErr = expectMostRecentItem()
                assertEquals(listOf("g1", "g2"), afterErr.items.map { it.id })
                assertEquals("Timeout on page 2", afterErr.paginationErrorMessage)
                assertTrue(afterErr.hasMore)

                // Retry page 2 -> succeeds & deduplicates g2
                failPage2 = false
                vm.loadNextPage()
                advanceUntilIdle()
                val p2 = expectMostRecentItem()
                assertNull(p2.paginationErrorMessage)
                assertEquals(listOf("g1", "g2", "g3"), p2.items.map { it.id })
                assertFalse(p2.hasMore)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `selecting sites and sections increments visit counts and sorts both lists descending`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val sections = listOf(
                SiteSectionSpec(id = "latest", title = "最新"),
                SiteSectionSpec(id = "popular", title = "热门"),
                SiteSectionSpec(id = "top_rated", title = "好评"),
            )
            val hanime = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.HANIME1,
                    displayName = "Hanime1",
                    primaryKind = MediaKind.VIDEO,
                    defaultHost = "hanime1.me",
                    sections = sections,
                    capabilities = setOf(SiteCapability.EXPLORE),
                ),
                feedHandler = { FeedPage(items = emptyList()) },
            )
            val r34 = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.R34V,
                    displayName = "Rule34Video",
                    primaryKind = MediaKind.VIDEO,
                    defaultHost = "rule34video.com",
                    sections = sections,
                    capabilities = setOf(SiteCapability.EXPLORE),
                ),
                feedHandler = { FeedPage(items = emptyList()) },
            )
            val nhentai = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.NHENTAI,
                    displayName = "nhentai",
                    primaryKind = MediaKind.COMIC,
                    defaultHost = "nhentai.net",
                    capabilities = setOf(SiteCapability.EXPLORE),
                ),
                feedHandler = { FeedPage(items = emptyList()) },
            )

            val vm = ExploreViewModel(
                siteRegistry = SiteRegistry(listOf(hanime, r34, nhentai)),
                preferencesRepository = harness.preferencesRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                browsePositionRepository = harness.browsePositionRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                val initial = expectMostRecentItem()
                assertEquals(listOf(SiteId.HANIME1, SiteId.R34V, SiteId.NHENTAI), initial.allSites.map { it.id })
                assertEquals(listOf("latest", "popular", "top_rated"), initial.availableSections.map { it.id })

                // Open top_rated twice & popular once -> top_rated leads, popular second
                vm.selectSection(sections[1])
                vm.selectSection(sections[2])
                vm.selectSection(sections[2])
                // Open NHENTAI 3 times, R34V 2 times, HANIME1 once -> [NHENTAI(3), R34V(2), HANIME1(1)]
                vm.selectSite(SiteId.NHENTAI)
                vm.selectSite(SiteId.R34V)
                vm.selectSite(SiteId.NHENTAI)
                vm.selectSite(SiteId.R34V)
                vm.selectSite(SiteId.NHENTAI)
                advanceUntilIdle()

                // Verify section counts on HANIME1 persisted & sorted (switches HANIME1 -> count = 1)
                vm.selectSite(SiteId.HANIME1)
                advanceUntilIdle()

                val reordered = expectMostRecentItem()
                assertEquals(
                    listOf(SiteId.NHENTAI, SiteId.R34V, SiteId.HANIME1),
                    reordered.allSites.map { it.id },
                )
                assertEquals(
                    listOf("top_rated", "popular", "latest"),
                    reordered.availableSections.map { it.id },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
}
