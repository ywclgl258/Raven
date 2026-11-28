package com.raven.app.feature.browse

import app.cash.turbine.test
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchViewModelDiscoveryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `discovery hub populates trending tags, hot tag ranking, and hot works ranking from popular feed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val r34Plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.R34V, "Rule34Video", primaryKind = MediaKind.VIDEO, defaultHost = "r34.example"),
                feedHandler = {
                    FeedPage(
                        items = listOf(
                            sampleMediaItem(SiteId.R34V, "hot-1", MediaKind.VIDEO).copy(
                                tags = listOf(TagRef(slug = "cosplay"), TagRef(slug = "maid")),
                            ),
                            sampleMediaItem(SiteId.R34V, "hot-2", MediaKind.VIDEO).copy(
                                tags = listOf(TagRef(slug = "cosplay"), TagRef(slug = "stockings")),
                            ),
                            sampleMediaItem(SiteId.R34V, "hot-3", MediaKind.VIDEO).copy(
                                tags = listOf(TagRef(slug = "maid")),
                            ),
                        ),
                    )
                },
            )
            val vm = SearchViewModel(
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
                val state = expectMostRecentItem()
                assertTrue(state.trendingTags.isNotEmpty())
                assertTrue(state.hotTagRanking.isNotEmpty())
                assertEquals("cosplay", state.hotTagRanking.first().slug)
                assertEquals(listOf("hot-1", "hot-2", "hot-3"), state.hotWorksRanking.map { it.id })
                cancelAndIgnoreRemainingEvents()
            }
        }
    @Test
    fun `cross-site source selection supports category toggles and explicit site subsets`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            fun plugin(id: SiteId, kind: MediaKind, host: String) = FakeSitePlugin(
                descriptor = SiteDescriptor(id, id.value, primaryKind = kind, defaultHost = host),
                searchHandler = { FeedPage(items = emptyList()) },
            )
            val video = plugin(SiteId.R34V, MediaKind.VIDEO, "v.example")
            val comic = plugin(SiteId.NHENTAI, MediaKind.COMIC, "c.example")
            val comic2 = plugin(SiteId.JMCOMIC, MediaKind.COMIC, "c2.example")
            val serial = plugin(SiteId.GUAZIMANHUA, MediaKind.SERIAL_COMIC, "s.example")
            val photo = plugin(SiteId.FOURKHD, MediaKind.PHOTO, "p.example")

            val vm = SearchViewModel(
                siteRegistry = SiteRegistry(listOf(video, comic, comic2, serial, photo)),
                preferencesRepository = harness.preferencesRepository,
                searchHistoryRepository = harness.searchHistoryRepository,
                subscriptionRepository = harness.subscriptionRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                dispatchers = harness.dispatchers,
            )
            advanceUntilIdle()

            suspend fun persisted() = harness.preferencesRepository.currentSettings().crossSearchSiteIds

            // 默认空列表语义为「全部站点」
            assertEquals(emptyList<SiteId>(), persisted())
            assertEquals(
                listOf(SiteId.R34V, SiteId.NHENTAI, SiteId.JMCOMIC, SiteId.GUAZIMANHUA, SiteId.FOURKHD),
                vm.uiState.first().selectedCrossSiteIds,
            )

            // 全选状态下按分类取消：视频整组被移除
            vm.toggleCrossSearchSitesByKind(MediaKind.VIDEO)
            advanceUntilIdle()
            assertEquals(
                listOf(SiteId.NHENTAI, SiteId.JMCOMIC, SiteId.GUAZIMANHUA, SiteId.FOURKHD),
                persisted(),
            )

            // 再次切换该分类：整组加回，恢复「全部」并持久化为空
            vm.toggleCrossSearchSitesByKind(MediaKind.VIDEO)
            advanceUntilIdle()
            assertEquals(emptyList<SiteId>(), persisted())

            // 显式指定站点子集
            vm.applyCrossSearchSites(listOf(SiteId.NHENTAI, SiteId.GUAZIMANHUA))
            advanceUntilIdle()
            assertEquals(listOf(SiteId.NHENTAI, SiteId.GUAZIMANHUA), persisted())
            vm.uiState.test {
                advanceUntilIdle()
                assertEquals(
                    listOf(SiteId.NHENTAI, SiteId.GUAZIMANHUA),
                    expectMostRecentItem().selectedCrossSiteIds,
                )
                cancelAndIgnoreRemainingEvents()
            }

            // 仅在选中集合内对该分类做移除
            vm.toggleCrossSearchSitesByKind(MediaKind.SERIAL_COMIC)
            advanceUntilIdle()
            assertEquals(listOf(SiteId.NHENTAI), persisted())

            // 部分选中的分类再次切换时整组并入
            vm.toggleCrossSearchSitesByKind(MediaKind.COMIC)
            advanceUntilIdle()
            assertEquals(listOf(SiteId.NHENTAI, SiteId.JMCOMIC), persisted())

            // 移除后将为空时保留原选择
            vm.toggleCrossSearchSitesByKind(MediaKind.COMIC)
            advanceUntilIdle()
            assertEquals(listOf(SiteId.NHENTAI, SiteId.JMCOMIC), persisted())

            // 空集合应用无效，不会清空选择
            vm.applyCrossSearchSites(emptyList())
            advanceUntilIdle()
            assertEquals(listOf(SiteId.NHENTAI, SiteId.JMCOMIC), persisted())

            // 一键全选回到默认「全部」
            vm.selectAllCrossSearchSites()
            advanceUntilIdle()
            assertEquals(emptyList<SiteId>(), persisted())
        }

    @Test
    fun `cancelled discovery load does not poison discovery cache with empty results`() = runTest {
        val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
        var homeCalls = 0
        val siteA = FakeSitePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.R34V,
                displayName = "Rule34Video",
                shortName = "R34V",
                primaryKind = MediaKind.VIDEO,
                defaultHost = "rule34video.com",
            ),
            homeHandler = {
                homeCalls += 1
                if (homeCalls == 1) {
                    throw kotlinx.coroutines.CancellationException("cancelled on switch")
                }
                HomeFeed(
                    siteId = SiteId.R34V,
                    primaryFeedPage = FeedPage(items = listOf(sampleMediaItem(SiteId.R34V, "hot-1", MediaKind.VIDEO))),
                )
            },
        )
        val siteB = FakeSitePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.IWARA,
                displayName = "Iwara",
                shortName = "Iwara",
                primaryKind = MediaKind.VIDEO,
                defaultHost = "www.iwara.tv",
            ),
        )
        harness.preferencesRepository.setActiveSiteId(SiteId.R34V)

        val vm = SearchViewModel(
            initialRoute = RavenRoute.Search(),
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
            assertTrue(expectMostRecentItem().hotWorksRanking.isEmpty())

            // Switch away and back; since empty/cancelled result was NOT cached, R34V fetches again
            harness.preferencesRepository.setActiveSiteId(SiteId.IWARA)
            advanceUntilIdle()
            harness.preferencesRepository.setActiveSiteId(SiteId.R34V)
            advanceUntilIdle()

            assertEquals(listOf("hot-1"), expectMostRecentItem().hotWorksRanking.map { it.id })
            assertEquals(2, homeCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cross-site history is stored under GLOBAL and incognito never writes it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.R34V, "R34V", primaryKind = MediaKind.VIDEO, defaultHost = "r34.example"),
                searchHandler = { req -> FeedPage(items = listOf(sampleMediaItem(SiteId.R34V, "r_${req.query}", MediaKind.VIDEO))) },
            )
            harness.preferencesRepository.setActiveSiteId(SiteId.R34V)
            val vm = SearchViewModel(
                siteRegistry = SiteRegistry(listOf(plugin)),
                preferencesRepository = harness.preferencesRepository,
                searchHistoryRepository = harness.searchHistoryRepository,
                subscriptionRepository = harness.subscriptionRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                dispatchers = harness.dispatchers,
            )
            val repository = harness.searchHistoryRepository

            vm.setMode(SearchMode.CROSS_SITE)
            vm.updateQuery("cross query")
            vm.submitSearch()
            advanceUntilIdle()

            assertEquals(
                listOf("cross query"),
                repository.observeBySite(SiteId.GLOBAL, limit = 20).first().map { it.query },
            )
            assertTrue(repository.observeBySite(SiteId.R34V, limit = 20).first().isEmpty())

            harness.preferencesRepository.setIncognitoMode(true)
            harness.nowMs += 1_000L
            vm.updateQuery("secret cross query")
            vm.submitSearch()
            advanceUntilIdle()

            assertEquals(
                listOf("cross query"),
                repository.observeBySite(SiteId.GLOBAL, limit = 20).first().map { it.query },
            )
            assertTrue(repository.observeBySite(SiteId.R34V, limit = 20).first().isEmpty())
        }

    @Test
    fun `search history merges all sites and GLOBAL by recency, dedupes query-scope, and caps at 30`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.R34V, "R34V", primaryKind = MediaKind.VIDEO, defaultHost = "r34.example"),
                searchHandler = { FeedPage(items = listOf(sampleMediaItem(SiteId.R34V, "r_1", MediaKind.VIDEO))) },
            )
            harness.preferencesRepository.setActiveSiteId(SiteId.R34V)
            val repository = harness.searchHistoryRepository
            val base = harness.nowMs
            repository.recordSearch(SiteId.R34V, "站内旧", SearchScope.ALL)
            harness.nowMs = base + 10
            repository.recordSearch(SiteId.GLOBAL, "跨站中", SearchScope.ALL)
            harness.nowMs = base + 20
            repository.recordSearch(SiteId.R34V, "重叠词", SearchScope.ALL)
            harness.nowMs = base + 30
            repository.recordSearch(SiteId.GLOBAL, "重叠词", SearchScope.ALL)
            harness.nowMs = base + 40
            repository.recordSearch(SiteId.GLOBAL, "跨站新", SearchScope.ALL)
            harness.nowMs = base + 50
            repository.recordSearch(SiteId.R34V, "重叠词", SearchScope.TAG)

            val vm = SearchViewModel(
                siteRegistry = SiteRegistry(listOf(plugin)),
                preferencesRepository = harness.preferencesRepository,
                searchHistoryRepository = repository,
                subscriptionRepository = harness.subscriptionRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                val merged = expectMostRecentItem().searchHistory
                assertEquals(5, merged.size)
                assertEquals(
                    listOf("重叠词", "跨站新", "重叠词", "跨站中", "站内旧"),
                    merged.map { it.query },
                )
                assertEquals(SearchScope.TAG, merged[0].scope)
                assertEquals(SiteId.R34V, merged[0].siteId)
                assertEquals(SiteId.GLOBAL, merged[2].siteId)
                assertEquals(SearchScope.ALL, merged[2].scope)
                assertEquals(SiteId.R34V, merged[4].siteId)

                // Newer GLOBAL entries push older ones past the 30-item display cap.
                repeat(30) { index ->
                    harness.nowMs = base + 100 + index * 10L
                    repository.recordSearch(SiteId.GLOBAL, "批量$index", SearchScope.ALL)
                }
                advanceUntilIdle()
                val capped = expectMostRecentItem().searchHistory
                assertEquals(30, capped.size)
                assertEquals("批量29", capped.first().query)
                assertTrue(capped.none { it.query == "站内旧" || it.query == "跨站中" })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `selecting a history entry restores its site and search mode`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val r34 = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.R34V, "R34V", primaryKind = MediaKind.VIDEO, defaultHost = "r34.example"),
                searchHandler = { req -> FeedPage(items = listOf(sampleMediaItem(SiteId.R34V, "r_${req.query}", MediaKind.VIDEO))) },
            )
            val nhentai = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.NHENTAI, "nHentai", primaryKind = MediaKind.COMIC, defaultHost = "nh.example"),
                searchHandler = { req -> FeedPage(items = listOf(sampleMediaItem(SiteId.NHENTAI, "nh_${req.query}", MediaKind.COMIC))) },
            )
            harness.preferencesRepository.setActiveSiteId(SiteId.R34V)
            harness.searchHistoryRepository.recordSearch(SiteId.NHENTAI, "comic restore", SearchScope.ALL)
            harness.nowMs += 10L
            harness.searchHistoryRepository.recordSearch(SiteId.GLOBAL, "cross restore", SearchScope.ALL)

            val vm = SearchViewModel(
                initialRoute = RavenRoute.Search(sourceSiteId = SiteId.R34V.value),
                siteRegistry = SiteRegistry(listOf(r34, nhentai)),
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
                val initial = expectMostRecentItem()
                val siteEntry = initial.searchHistory.first { it.siteId == SiteId.NHENTAI }
                vm.selectHistoryEntry(siteEntry)
                advanceUntilIdle()

                val single = expectMostRecentItem()
                assertEquals(SearchMode.SINGLE_SITE, single.mode)
                assertEquals(SiteId.NHENTAI, single.activeSite?.id)
                assertEquals("comic restore", single.submittedQuery)
                assertTrue(single.hasSearchedSingleSite)
                assertEquals("comic restore", nhentai.searchRequests.last().query)

                vm.updateQuery("follow-up")
                vm.submitSearch()
                advanceUntilIdle()
                assertEquals("follow-up", nhentai.searchRequests.last().query)

                vm.saveCurrentSearch("follow-up")
                advanceUntilIdle()
                val recipe = expectMostRecentItem().savedSearches.single()
                assertEquals(listOf(SiteId.NHENTAI), recipe.siteIds)
                vm.selectActiveSite(SiteId.R34V)
                advanceUntilIdle()
                vm.applySavedSearch(recipe)
                advanceUntilIdle()
                assertEquals(SiteId.NHENTAI, expectMostRecentItem().activeSite?.id)
                vm.updateQuery("after recipe")
                vm.submitSearch()
                advanceUntilIdle()
                assertEquals("after recipe", nhentai.searchRequests.last().query)

                val globalEntry = single.searchHistory.first { it.siteId == SiteId.GLOBAL }
                vm.selectHistoryEntry(globalEntry)
                advanceUntilIdle()

                val cross = expectMostRecentItem()
                assertEquals(SearchMode.CROSS_SITE, cross.mode)
                assertEquals("cross restore", cross.submittedQuery)
                assertTrue(cross.hasSearchedCrossSite)
                assertTrue(r34.searchRequests.any { it.query == "cross restore" })
                assertTrue(nhentai.searchRequests.any { it.query == "cross restore" })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clearing history removes current site and GLOBAL but keeps other sites`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            harness.preferencesRepository.setActiveSiteId(SiteId.R34V)
            harness.searchHistoryRepository.recordSearch(SiteId.R34V, "site a", SearchScope.ALL)
            harness.searchHistoryRepository.recordSearch(SiteId.NHENTAI, "site b", SearchScope.ALL)
            harness.searchHistoryRepository.recordSearch(SiteId.GLOBAL, "cross entry", SearchScope.ALL)

            val vm = SearchViewModel(
                siteRegistry = SiteRegistry(
                    emptyList(),
                ),
                preferencesRepository = harness.preferencesRepository,
                searchHistoryRepository = harness.searchHistoryRepository,
                subscriptionRepository = harness.subscriptionRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                dispatchers = harness.dispatchers,
            )

            vm.clearSiteHistory()
            advanceUntilIdle()

            assertTrue(harness.searchHistoryRepository.observeBySite(SiteId.R34V, limit = 20).first().isEmpty())
            assertTrue(harness.searchHistoryRepository.observeBySite(SiteId.GLOBAL, limit = 20).first().isEmpty())
            assertEquals(
                listOf("site b"),
                harness.searchHistoryRepository.observeBySite(SiteId.NHENTAI, limit = 20).first().map { it.query },
            )
        }

    @Test
    fun `gallery-sourced single-site search targets source site instead of active site`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val siteA = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.R34V, "R34V", primaryKind = MediaKind.VIDEO, defaultHost = "r34.example"),
                searchHandler = { req -> FeedPage(items = listOf(sampleMediaItem(SiteId.R34V, "a_${req.query}", MediaKind.VIDEO))) },
            )
            val siteB = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.NHENTAI, "nHentai", primaryKind = MediaKind.VIDEO, defaultHost = "nh.example"),
                searchHandler = { req -> FeedPage(items = listOf(sampleMediaItem(SiteId.NHENTAI, "b_${req.query}", MediaKind.VIDEO))) },
            )
            // Site A is active, but the search originates from site B's gallery detail.
            harness.preferencesRepository.setActiveSiteId(SiteId.R34V)
            val vm = SearchViewModel(
                initialRoute = RavenRoute.Search(initialQuery = "tagB", crossSite = false, sourceSiteId = SiteId.NHENTAI.value),
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
                // Initial query auto-submits; only site B must receive it.
                assertTrue(siteB.searchRequests.any { it.query == "tagB" })
                assertTrue(siteA.searchRequests.none { it.query == "tagB" })
                // History is recorded against site B, not the ambient active site A.
                assertEquals(
                    listOf("tagB"),
                    harness.searchHistoryRepository.observeBySite(SiteId.NHENTAI, limit = 20).first().map { it.query },
                )
                assertTrue(harness.searchHistoryRepository.observeBySite(SiteId.R34V, limit = 20).first().isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `stale request that lands after clear does not surface results`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val r34 = FakeSitePlugin(
                descriptor = SiteDescriptor(SiteId.R34V, "R34V", primaryKind = MediaKind.VIDEO, defaultHost = "r34.example"),
                searchHandler = { req ->
                    delay(200)
                    FeedPage(items = listOf(sampleMediaItem(SiteId.R34V, "res_${req.query}", MediaKind.VIDEO)))
                },
            )
            harness.preferencesRepository.setActiveSiteId(SiteId.R34V)
            val vm = SearchViewModel(
                siteRegistry = SiteRegistry(listOf(r34)),
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
                vm.updateQuery("old query")
                vm.submitSearch()
                // Clear before the 200ms response arrives.
                vm.clearQuery()
                advanceUntilIdle()

                val cleared = expectMostRecentItem()
                assertEquals("", cleared.query)
                assertTrue(cleared.singleSiteItems.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
