package com.raven.app.feature.browse

import app.cash.turbine.test
import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.sitespi.SiteRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `single-site search drives scopes and advanced filters from plugin, records history, and suggests tags`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val r34Plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.R34V,
                    displayName = "Rule34Video",
                    shortName = "R34V",
                    primaryKind = MediaKind.VIDEO,
                    defaultHost = "rule34video.com",
                    capabilities = setOf(SiteCapability.SEARCH, SiteCapability.ADVANCED_SEARCH),
                    searchScopes = listOf(SearchScope.ALL, SearchScope.TAG, SearchScope.AUTHOR),
                    supportedFilters = setOf(AdvancedFilterField.DURATION, AdvancedFilterField.RATING),
                ),
                searchHandler = { req ->
                    FeedPage(
                        items = listOf(
                            sampleMediaItem(
                                SiteId.R34V,
                                "res_${req.query}_${req.scope.name}_p${req.page}",
                                MediaKind.VIDEO,
                            ),
                        ),
                        nextPage = if (req.page == 1) 2 else null,
                        hasMore = req.page == 1,
                    )
                },
                supportsExcludeTagsFlag = false,
            )

            val vm = SearchViewModel(
                initialRoute = RavenRoute.Search(),
                siteRegistry = SiteRegistry(listOf(r34Plugin)),
                preferencesRepository = harness.preferencesRepository,
                searchHistoryRepository = harness.searchHistoryRepository,
                subscriptionRepository = harness.subscriptionRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                val idle = expectMostRecentItem()
                assertEquals(listOf(SearchScope.ALL, SearchScope.TAG, SearchScope.AUTHOR), idle.supportedScopes)
                assertEquals(setOf(AdvancedFilterField.DURATION, AdvancedFilterField.RATING), idle.supportedFilters)

                // Type Chinese term from bundled 165-entry TagDictionary ("金发" -> "blonde")
                vm.updateQuery("金发")
                advanceUntilIdle()
                val withSuggestions = expectMostRecentItem()
                assertTrue(withSuggestions.tagSuggestions.any { it.slug == "blonde" && it.localizedName == "金发" })

                val blondeSug = withSuggestions.tagSuggestions.first { it.slug == "blonde" }
                vm.applyTagSuggestion(blondeSug, autoSubmit = false)
                advanceUntilIdle()
                assertEquals("blonde", expectMostRecentItem().query)

                // Switch scope to TAG, apply advanced filters, and submit
                vm.selectScope(SearchScope.TAG)
                vm.applyAdvancedFilters(
                    filters = AdvancedSearchFilters(minDurationMinutes = 10, minRating = 4),
                    language = ComicLanguage.ALL,
                    autoSubmit = true,
                )
                advanceUntilIdle()

                val searched = expectMostRecentItem()
                assertTrue(searched.hasSearchedSingleSite)
                assertEquals(listOf("res_blonde_TAG_p1"), searched.singleSiteItems.map { it.id })
                assertEquals(1, searched.searchHistory.size)
                assertEquals("blonde", searched.searchHistory.first().query)
                assertEquals(SearchScope.TAG, searched.searchHistory.first().scope)
                val lastReq = r34Plugin.searchRequests.last()
                assertEquals(10, lastReq.advancedFilters.minDurationMinutes)
                assertEquals(4, lastReq.advancedFilters.minRating)

                // Paginate page 2
                vm.loadNextSingleSitePage()
                advanceUntilIdle()
                val paged = expectMostRecentItem()
                assertEquals(listOf("res_blonde_TAG_p1", "res_blonde_TAG_p2"), paged.singleSiteItems.map { it.id })
                assertFalse(paged.singleSiteHasMore)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saved search creation persists recipe and applying restores query and mode`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val p1 = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.R34V,
                    displayName = "R34V",
                    primaryKind = MediaKind.VIDEO,
                    defaultHost = "rule34video.com",
                ),
                searchHandler = { req ->
                    FeedPage(items = listOf(sampleMediaItem(SiteId.R34V, "r_${req.query}", MediaKind.VIDEO)))
                },
            )
            val p2 = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.NHENTAI,
                    displayName = "nHentai",
                    primaryKind = MediaKind.COMIC,
                    defaultHost = "nhentai.net",
                ),
                searchHandler = { req ->
                    FeedPage(items = listOf(sampleMediaItem(SiteId.NHENTAI, "n_${req.query}", MediaKind.COMIC)))
                },
            )

            val vm = SearchViewModel(
                initialRoute = RavenRoute.Search(crossSite = true),
                siteRegistry = SiteRegistry(listOf(p1, p2)),
                preferencesRepository = harness.preferencesRepository,
                searchHistoryRepository = harness.searchHistoryRepository,
                subscriptionRepository = harness.subscriptionRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                vm.updateQuery("glasses")
                vm.saveCurrentSearch("眼镜合集")
                advanceUntilIdle()

                val afterSave = expectMostRecentItem()
                assertEquals(1, afterSave.savedSearches.size)
                val saved = afterSave.savedSearches.first()
                assertEquals("眼镜合集", saved.name)
                assertEquals("glasses", saved.query)
                assertEquals(listOf(SiteId.R34V, SiteId.NHENTAI), saved.siteIds)

                // Clear query, switch to single-site, then apply saved search -> restores cross-site & runs
                vm.clearQuery()
                vm.setMode(SearchMode.SINGLE_SITE)
                advanceUntilIdle()
                assertEquals(SearchMode.SINGLE_SITE, expectMostRecentItem().mode)

                vm.applySavedSearch(saved)
                advanceUntilIdle()
                val restored = expectMostRecentItem()
                assertEquals(SearchMode.CROSS_SITE, restored.mode)
                assertEquals("glasses", restored.query)
                assertTrue(restored.hasSearchedCrossSite)
                assertEquals(setOf("r_glasses", "n_glasses"), restored.crossSiteMediaItems.map { it.id }.toSet())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cross-site search enforces Semaphore(3) concurrency, handles partial failure and retry, and separates directory jump cards`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val inFlight = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)
            var failFlakySite = true

            fun trackConcurrency(action: suspend () -> FeedPage): suspend (com.raven.app.core.model.FeedRequest) -> FeedPage = {
                val active = inFlight.incrementAndGet()
                maxConcurrent.accumulateAndGet(active, ::maxOf)
                try {
                    delay(40L)
                    action()
                } finally {
                    inFlight.decrementAndGet()
                }
            }

            val siteA = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.R34V, "R34V", primaryKind = MediaKind.VIDEO, defaultHost = "a.example"),
                searchHandler = { req ->
                    trackConcurrency {
                        FeedPage(
                            items = listOf(sampleMediaItem(SiteId.R34V, "a_p${req.page}", MediaKind.VIDEO)),
                            nextPage = if (req.page == 1) 2 else null,
                            hasMore = req.page == 1,
                        )
                    }(req)
                },
            )
            val siteB = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.HANIME1, "Hanime", primaryKind = MediaKind.VIDEO, defaultHost = "b.example"),
                searchHandler = { req ->
                    trackConcurrency {
                        if (failFlakySite) throw SiteException.Network("Temporary network error", siteId = SiteId.HANIME1)
                        FeedPage(items = listOf(sampleMediaItem(SiteId.HANIME1, "b_recovered", MediaKind.VIDEO)))
                    }(req)
                },
            )
            val siteC = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.NHENTAI, "nHentai", primaryKind = MediaKind.COMIC, defaultHost = "c.example"),
                searchHandler = { req ->
                    trackConcurrency {
                        FeedPage(items = listOf(sampleMediaItem(SiteId.NHENTAI, "c_1", MediaKind.COMIC)))
                    }(req)
                },
            )
            val siteD = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.HITOMI, "Hitomi", primaryKind = MediaKind.COMIC, defaultHost = "d.example"),
                searchHandler = { req ->
                    trackConcurrency {
                        FeedPage(items = listOf(sampleMediaItem(SiteId.HITOMI, "d_1", MediaKind.COMIC)))
                    }(req)
                },
            )
            val siteE = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.IWARA, "Iwara", primaryKind = MediaKind.VIDEO, defaultHost = "e.example"),
                searchHandler = { req ->
                    trackConcurrency {
                        FeedPage(items = listOf(sampleMediaItem(SiteId.IWARA, "e_1", MediaKind.VIDEO)))
                    }(req)
                },
            )
            val authSite = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    SiteId.EXHENTAI,
                    "ExHentai",
                    primaryKind = MediaKind.COMIC,
                    defaultHost = "ex.example",
                    networkProfile = SiteNetworkProfile(requiresAuthGate = true),
                ),
                searchHandler = { FeedPage(items = emptyList()) },
                requiresAuthGateFlag = true,
                isAuthenticated = false,
            )
            val dirOnlySite = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.FOURKHD, "4KHD", primaryKind = MediaKind.PHOTO, defaultHost = "dir.example"),
                searchHandler = { req ->
                    trackConcurrency {
                        FeedPage(
                            items = listOf(
                                sampleMediaItem(
                                    SiteId.FOURKHD,
                                    "dir_jump_1",
                                    MediaKind.PHOTO,
                                    title = "Cosplay Collection",
                                    extra = mapOf("isDirectoryEntry" to "true"),
                                ),
                            ),
                        )
                    }(req)
                },
                isDirectorySearchFlag = true,
            )

            val vm = SearchViewModel(
                initialRoute = RavenRoute.Search(initialQuery = "cosplay", crossSite = true),
                siteRegistry = SiteRegistry(listOf(siteA, siteB, siteC, siteD, siteE, authSite, dirOnlySite)),
                preferencesRepository = harness.preferencesRepository,
                searchHistoryRepository = harness.searchHistoryRepository,
                subscriptionRepository = harness.subscriptionRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                val completed = expectMostRecentItem()

                // Strictly capped at Semaphore(3) permits, and truly ran concurrently
                assertTrue(
                    "Expected max concurrency in 2..3, got ${maxConcurrent.get()}",
                    maxConcurrent.get() in 2..SearchViewModel.MAX_CONCURRENT_CROSS_SITE_SEARCHES,
                )

                val bucketsById = completed.crossSiteBuckets.associateBy { it.site.id }
                assertEquals(CrossSiteStatus.SUCCESS, bucketsById[SiteId.R34V]?.status)
                assertEquals(CrossSiteStatus.ERROR, bucketsById[SiteId.HANIME1]?.status)
                assertEquals(CrossSiteStatus.SUCCESS, bucketsById[SiteId.NHENTAI]?.status)
                assertEquals(CrossSiteStatus.SUCCESS, bucketsById[SiteId.HITOMI]?.status)
                assertEquals(CrossSiteStatus.SUCCESS, bucketsById[SiteId.IWARA]?.status)
                assertEquals(CrossSiteStatus.AUTH_REQUIRED, bucketsById[SiteId.EXHENTAI]?.status)
                assertEquals(CrossSiteStatus.SUCCESS, bucketsById[SiteId.FOURKHD]?.status)

                // Directory-only site isolated into crossSiteDirectoryBuckets, NOT mixed into media grid
                assertEquals(listOf(SiteId.FOURKHD), completed.crossSiteDirectoryBuckets.map { it.site.id })
                assertEquals(
                    setOf("a_p1", "c_1", "d_1", "e_1"),
                    completed.crossSiteMediaItems.map { it.id }.toSet(),
                )

                // Retry failed Hanime1 site -> recovers without losing other sites
                failFlakySite = false
                vm.retryCrossSite(SiteId.HANIME1)
                advanceUntilIdle()
                val afterRetry = expectMostRecentItem()
                assertEquals(
                    CrossSiteStatus.SUCCESS,
                    afterRetry.crossSiteBuckets.first { it.site.id == SiteId.HANIME1 }.status,
                )
                assertTrue(afterRetry.crossSiteMediaItems.any { it.id == "b_recovered" })
                assertTrue(afterRetry.crossSiteMediaItems.any { it.id == "a_p1" })

                // Load more on R34V bucket -> appends page 2 cleanly
                vm.loadMoreCrossSite(SiteId.R34V)
                advanceUntilIdle()
                val afterMore = expectMostRecentItem()
                assertTrue(afterMore.crossSiteMediaItems.any { it.id == "a_p2" })
                assertFalse(afterMore.crossSiteBuckets.first { it.site.id == SiteId.R34V }.hasMore)

                cancelAndIgnoreRemainingEvents()
            }
        }
}
