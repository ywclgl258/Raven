package com.raven.app.feature.browse

import app.cash.turbine.test
import com.raven.app.core.model.AdvancedFilterField
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.TagRef
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

    @Test
    fun `single-site search forwards native sorts to SearchSource and applies local fallback for non-native sorts`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val r34Plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.R34V,
                    displayName = "Rule34Video",
                    shortName = "R34V",
                    primaryKind = MediaKind.VIDEO,
                    defaultHost = "rule34video.com",
                    capabilities = setOf(SiteCapability.SEARCH),
                ),
                supportedSearchSortsProvider = {
                    listOf(FeedSort.RELEVANCE, FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.LIKES)
                },
                searchHandler = { req ->
                    FeedPage(
                        items = listOf(
                            sampleMediaItem(
                                SiteId.R34V,
                                "newest_${req.sort.name}",
                                MediaKind.VIDEO,
                                views = 100L,
                                likes = 10L,
                                dateLabel = "2026-03-01",
                            ),
                            sampleMediaItem(
                                SiteId.R34V,
                                "oldest_${req.sort.name}",
                                MediaKind.VIDEO,
                                views = 500L,
                                likes = 50L,
                                dateLabel = "2024-01-15",
                            ),
                        ),
                    )
                },
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
                assertEquals(
                    listOf(FeedSort.RELEVANCE, FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.LIKES),
                    idle.nativeSorts,
                )
                assertTrue(FeedSort.OLDEST in idle.localSorts)
                assertTrue(FeedSort.DURATION in idle.localSorts)

                vm.updateQuery("cyber")
                vm.submitSearch()
                advanceUntilIdle()
                assertEquals(FeedSort.RELEVANCE, r34Plugin.searchRequests.last().sort)

                // Selecting native VIEWS reloads server with FeedSort.VIEWS
                vm.selectSort(FeedSort.VIEWS)
                advanceUntilIdle()
                val viewsState = expectMostRecentItem()
                assertEquals(FeedSort.VIEWS, viewsState.sort)
                assertFalse(viewsState.isLocalSortActive)
                assertEquals(FeedSort.VIEWS, r34Plugin.searchRequests.last().sort)

                // Selecting native LIKES reloads server with FeedSort.LIKES
                vm.selectSort(FeedSort.LIKES)
                advanceUntilIdle()
                val likesState = expectMostRecentItem()
                assertEquals(FeedSort.LIKES, likesState.sort)
                assertEquals(FeedSort.LIKES, r34Plugin.searchRequests.last().sort)

                // Selecting non-native OLDEST fetches compatible NEWEST from server and sorts oldest-first locally
                vm.selectSort(FeedSort.OLDEST)
                advanceUntilIdle()
                val oldestState = expectMostRecentItem()
                assertEquals(FeedSort.OLDEST, oldestState.sort)
                assertTrue(oldestState.isLocalSortActive)
                assertEquals(FeedSort.NEWEST, r34Plugin.searchRequests.last().sort)
                assertEquals(
                    listOf("oldest_NEWEST", "newest_NEWEST"),
                    oldestState.singleSiteItems.map { it.id },
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cross-site search dispatches compatible sorts per site, merges deterministically, and preserves sort in SavedSearch`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val siteA = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.R34V, "R34V", primaryKind = MediaKind.VIDEO, defaultHost = "a.example"),
                supportedSearchSortsProvider = { listOf(FeedSort.RELEVANCE, FeedSort.NEWEST, FeedSort.VIEWS, FeedSort.LIKES, FeedSort.DURATION) },
                searchHandler = { req ->
                    FeedPage(
                        items = listOf(
                            sampleMediaItem(
                                SiteId.R34V,
                                "a_mid",
                                MediaKind.VIDEO,
                                views = 1_500L,
                                likes = 200L,
                                durationSeconds = 900,
                                dateLabel = "2025-06-01",
                            ),
                        ),
                        nextPage = if (req.page == 1) 2 else null,
                        hasMore = req.page == 1,
                    )
                },
            )
            val siteB = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.HITOMI, "Hitomi", primaryKind = MediaKind.COMIC, defaultHost = "b.example"),
                supportedSearchSortsProvider = { listOf(FeedSort.NEWEST, FeedSort.OLDEST, FeedSort.POPULAR) },
                searchHandler = {
                    FeedPage(
                        items = listOf(
                            sampleMediaItem(
                                SiteId.HITOMI,
                                "b_top",
                                MediaKind.COMIC,
                                views = 9_000L,
                                likes = 800L,
                                durationSeconds = 120,
                                dateLabel = "2023-02-10",
                            ),
                            sampleMediaItem(
                                SiteId.HITOMI,
                                "b_low",
                                MediaKind.COMIC,
                                views = 300L,
                                likes = 40L,
                                durationSeconds = 1_800,
                                dateLabel = "2026-04-20",
                            ),
                        ),
                    )
                },
            )

            val vm = SearchViewModel(
                initialRoute = RavenRoute.Search(crossSite = true),
                siteRegistry = SiteRegistry(listOf(siteA, siteB)),
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
                vm.updateQuery("elf")
                vm.submitSearch()
                advanceUntilIdle()

                // Switch to VIEWS -> R34V gets VIEWS, Hitomi gets compatible POPULAR, merged list sorted by views desc
                vm.selectSort(FeedSort.VIEWS)
                advanceUntilIdle()
                val byViews = expectMostRecentItem()
                assertEquals(FeedSort.VIEWS, byViews.sort)
                assertEquals(FeedSort.VIEWS, siteA.searchRequests.last().sort)
                assertEquals(FeedSort.POPULAR, siteB.searchRequests.last().sort)
                assertEquals(listOf("b_top", "a_mid", "b_low"), byViews.crossSiteMediaItems.map { it.id })

                // Load more on siteA keeps the active sort (VIEWS), never reverts to RELEVANCE
                vm.loadMoreCrossSite(SiteId.R34V)
                advanceUntilIdle()
                assertEquals(FeedSort.VIEWS, siteA.searchRequests.last().sort)
                assertEquals(2, siteA.searchRequests.last().page)

                // Switch to LIKES -> sorted by likes desc
                vm.selectSort(FeedSort.LIKES)
                advanceUntilIdle()
                val byLikes = expectMostRecentItem()
                assertEquals(FeedSort.LIKES, siteA.searchRequests.last().sort)
                assertEquals(listOf("b_top", "a_mid", "b_low"), byLikes.crossSiteMediaItems.map { it.id })

                // Switch to OLDEST -> Hitomi gets native OLDEST, R34V gets compatible NEWEST, merged sorted oldest-first
                vm.selectSort(FeedSort.OLDEST)
                advanceUntilIdle()
                val byOldest = expectMostRecentItem()
                assertEquals(FeedSort.OLDEST, siteB.searchRequests.last().sort)
                assertEquals(FeedSort.NEWEST, siteA.searchRequests.last().sort)
                assertEquals(listOf("b_top", "a_mid", "b_low"), byOldest.crossSiteMediaItems.map { it.id })

                // Switch to DURATION -> sorted by durationSeconds desc
                vm.selectSort(FeedSort.DURATION)
                advanceUntilIdle()
                val byDuration = expectMostRecentItem()
                assertEquals(FeedSort.DURATION, siteA.searchRequests.last().sort)
                assertEquals(listOf("b_low", "a_mid", "b_top"), byDuration.crossSiteMediaItems.map { it.id })

                // Save current search preserves FeedSort.DURATION and restoring re-applies it
                vm.saveCurrentSearch("最长精灵")
                advanceUntilIdle()
                val saved = expectMostRecentItem().savedSearches.single()
                assertEquals(FeedSort.DURATION, saved.sort)

                vm.selectSort(FeedSort.NEWEST)
                advanceUntilIdle()
                assertEquals(FeedSort.NEWEST, expectMostRecentItem().sort)

                vm.applySavedSearch(saved)
                advanceUntilIdle()
                assertEquals(FeedSort.DURATION, expectMostRecentItem().sort)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `feed sort display labels cover every enum entry in Chinese`() {
        assertEquals("默认", FeedSort.DEFAULT.displayLabel())
        assertEquals("相关度", FeedSort.RELEVANCE.displayLabel())
        assertEquals("最新", FeedSort.NEWEST.displayLabel())
        assertEquals("最旧", FeedSort.OLDEST.displayLabel())
        assertEquals("最多播放", FeedSort.VIEWS.displayLabel())
        assertEquals("最多赞", FeedSort.LIKES.displayLabel())
        assertEquals("最热门", FeedSort.POPULAR.displayLabel())
        assertEquals("趋势飙升", FeedSort.TRENDING.displayLabel())
        assertEquals("最高评分", FeedSort.RATING.displayLabel())
        assertEquals("最长时长", FeedSort.DURATION.displayLabel())
        assertEquals("随机", FeedSort.RANDOM.displayLabel())
    }

    @Test
    fun `auto-injects blocked terms into server excludeTags capped at 12 and filters global bilingual and cached-tag matches across single and cross-site search`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)

            // Seed Global Chinese blocked term ("触手" -> "tentacles") + per-site term + 14 extra ASCII terms to test 12-term cap
            harness.blockedTermsRepository.addTerm(SiteId.GLOBAL, "触手")
            harness.blockedTermsRepository.addTerm(SiteId.R34V, "netorare")
            for (i in 1..14) {
                harness.blockedTermsRepository.addTerm(SiteId.GLOBAL, "spam_tag_$i")
            }

            // Seed MediaTagCacheRepository for a card whose search listing omits tags!
            harness.mediaTagCacheRepository.upsertTags(
                siteId = SiteId.R34V,
                mediaId = "cached-tentacle-card",
                tags = listOf(TagRef(slug = "female:tentacles", label = "tentacles", namespace = "female")),
            )
            harness.mediaTagCacheRepository.upsertTags(
                siteId = SiteId.NHENTAI,
                mediaId = "nh-cached-blocked",
                tags = listOf(TagRef(slug = "tentacles", label = "tentacles")),
            )

            val r34Plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.R34V,
                    displayName = "Rule34Video",
                    shortName = "R34V",
                    primaryKind = MediaKind.VIDEO,
                    defaultHost = "rule34video.com",
                    capabilities = setOf(SiteCapability.SEARCH, SiteCapability.ADVANCED_SEARCH),
                    supportedFilters = setOf(AdvancedFilterField.EXCLUDE_TAGS),
                ),
                supportsExcludeTagsFlag = true,
                searchHandler = {
                    FeedPage(
                        items = listOf(
                            sampleMediaItem(
                                siteId = SiteId.R34V,
                                id = "clean-1",
                                title = "Clean Anime",
                                tags = listOf(TagRef(slug = "blonde", label = "blonde")),
                            ),
                            sampleMediaItem(
                                siteId = SiteId.R34V,
                                id = "blocked-direct-tag",
                                title = "Direct Blocked",
                                tags = listOf(TagRef(slug = "netorare", label = "netorare")),
                            ),
                            // Search card has NO tags, but MediaTagCacheRepository knows it has "female:tentacles"
                            sampleMediaItem(
                                siteId = SiteId.R34V,
                                id = "cached-tentacle-card",
                                title = "Innocent Looking Title",
                                tags = emptyList(),
                            ),
                        ),
                    )
                },
            )

            val nhentaiPlugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.NHENTAI,
                    displayName = "nHentai",
                    shortName = "nHentai",
                    primaryKind = MediaKind.COMIC,
                    defaultHost = "nhentai.net",
                    capabilities = setOf(SiteCapability.SEARCH),
                ),
                supportsExcludeTagsFlag = false,
                searchHandler = {
                    FeedPage(
                        items = listOf(
                            sampleMediaItem(
                                siteId = SiteId.NHENTAI,
                                id = "nh-clean",
                                kind = MediaKind.COMIC,
                                title = "Clean Comic",
                                tags = listOf(TagRef(slug = "stockings", label = "stockings")),
                            ),
                            sampleMediaItem(
                                siteId = SiteId.NHENTAI,
                                id = "nh-cached-blocked",
                                kind = MediaKind.COMIC,
                                title = "Untagged Comic",
                                tags = emptyList(),
                            ),
                        ),
                    )
                },
            )

            val vm = SearchViewModel(
                initialRoute = RavenRoute.Search(),
                siteRegistry = SiteRegistry(listOf(r34Plugin, nhentaiPlugin)),
                preferencesRepository = harness.preferencesRepository,
                searchHistoryRepository = harness.searchHistoryRepository,
                subscriptionRepository = harness.subscriptionRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                dispatchers = harness.dispatchers,
                tagDictionary = harness.runtimeDictionary,
                mediaTagCacheRepository = harness.mediaTagCacheRepository,
                tagDictionaryRepository = harness.tagDictionaryRepository,
            )

            vm.uiState.test {
                advanceUntilIdle()

                // 1. Single-site search on R34V (supportsExcludeTags = true)
                vm.updateQuery("elf")
                vm.submitSearch()
                advanceUntilIdle()

                val singleReq = r34Plugin.searchRequests.last()
                assertEquals(
                    SearchViewModel.MAX_SERVER_EXCLUDE_BLOCKED_TERMS,
                    singleReq.advancedFilters.excludeTags.size,
                )
                assertTrue("tentacles" in singleReq.advancedFilters.excludeTags)

                val singleState = expectMostRecentItem()
                // Only clean-1 survives (blocked-direct-tag & cached-tentacle-card both filtered out!)
                assertEquals(listOf("clean-1"), singleState.singleSiteItems.map { it.id })

                // 2. Cross-site search injects excludeTags on R34V (supportsExcludeTags=true) only, not nhentai
                vm.setMode(SearchMode.CROSS_SITE)
                advanceUntilIdle()

                val r34CrossReq = r34Plugin.searchRequests.last()
                val nhCrossReq = nhentaiPlugin.searchRequests.last()
                assertEquals(12, r34CrossReq.advancedFilters.excludeTags.size)
                assertTrue("tentacles" in r34CrossReq.advancedFilters.excludeTags)
                assertTrue(nhCrossReq.advancedFilters.excludeTags.isEmpty())

                // Client-side cross-site filter removes cached-tag & global blocked works on BOTH sites
                val crossState = expectMostRecentItem()
                assertEquals(
                    setOf("clean-1", "nh-clean"),
                    crossState.crossSiteMediaItems.map { it.id }.toSet(),
                )

                // 3. Updating custom TagDictionary refreshes suggestions & localized tags reactively
                harness.tagDictionaryRepository.upsertCustomTranslation("elven_archer", "精灵弓箭手")
                vm.updateQuery("精灵弓")
                advanceUntilIdle()
                val afterDictUpdate = expectMostRecentItem()
                assertTrue(afterDictUpdate.tagSuggestions.any { it.slug == "elven archer" && it.localizedName == "精灵弓箭手" })
                cancelAndIgnoreRemainingEvents()
            }
        }
}
