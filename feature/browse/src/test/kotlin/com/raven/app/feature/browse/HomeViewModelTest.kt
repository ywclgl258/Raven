package com.raven.app.feature.browse

import app.cash.turbine.test
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedRequest
import com.raven.app.core.model.FeedSection
import com.raven.app.core.model.HomeFeed
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun buildRegistry(
        exhentaiAuthenticated: Boolean = false,
    ): Triple<SiteRegistry, FakeSitePlugin, Triple<FakeSitePlugin, FakeSitePlugin, FakeSitePlugin>> {
        val videoPlugin = FakeSitePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.R34V,
                displayName = "Rule34Video",
                shortName = "R34V",
                primaryKind = MediaKind.VIDEO,
                defaultHost = "rule34video.com",
                capabilities = setOf(SiteCapability.FEED, SiteCapability.SEARCH),
            ),
            homeHandler = {
                val hero = sampleMediaItem(
                    SiteId.R34V,
                    "v1",
                    MediaKind.VIDEO,
                    title = "Hero Clip",
                    tags = listOf(TagRef(slug = "blonde", label = "blonde")),
                )
                val second = sampleMediaItem(
                    SiteId.R34V,
                    "v2",
                    MediaKind.VIDEO,
                    title = "Blocked Clip",
                    tags = listOf(TagRef(slug = "gore", label = "gore")),
                )
                val third = sampleMediaItem(SiteId.R34V, "v3", MediaKind.VIDEO, title = "Clean Clip")
                HomeFeed(
                    siteId = SiteId.R34V,
                    heroItems = listOf(hero, second),
                    sections = listOf(
                        FeedSection(
                            id = "top-ranked",
                            title = "热门榜",
                            items = listOf(hero, second, third),
                            seeAllRequest = FeedRequest(siteId = SiteId.R34V, section = "popular"),
                            isRanked = true,
                        ),
                    ),
                    primaryFeedPage = FeedPage(
                        items = listOf(hero, second, third),
                        nextPage = 2,
                        hasMore = true,
                    ),
                )
            },
            feedHandler = { req ->
                FeedPage(
                    items = listOf(
                        sampleMediaItem(SiteId.R34V, "v_p${req.page}_1", MediaKind.VIDEO, title = "Page ${req.page}"),
                    ),
                    nextPage = null,
                    hasMore = false,
                )
            },
        )

        val comicPlugin = FakeSitePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.NHENTAI,
                displayName = "nHentai",
                shortName = "nHentai",
                primaryKind = MediaKind.COMIC,
                defaultHost = "nhentai.net",
                capabilities = setOf(SiteCapability.FEED, SiteCapability.LANGUAGE_FILTER),
            ),
            homeHandler = { lang ->
                HomeFeed(
                    siteId = SiteId.NHENTAI,
                    primaryFeedPage = FeedPage(
                        items = listOf(
                            sampleMediaItem(
                                SiteId.NHENTAI,
                                "c_${lang.name}_1",
                                MediaKind.COMIC,
                                title = "Comic ${lang.name} 1",
                            ),
                            sampleMediaItem(
                                SiteId.NHENTAI,
                                "c_${lang.name}_2",
                                MediaKind.COMIC,
                                title = "Comic ${lang.name} 2",
                            ),
                        ),
                        nextPage = 2,
                        hasMore = true,
                    ),
                )
            },
            feedHandler = { req ->
                FeedPage(
                    items = listOf(
                        sampleMediaItem(
                            SiteId.NHENTAI,
                            "c_${req.language.name}_p${req.page}",
                            MediaKind.COMIC,
                            title = "Comic Page ${req.page}",
                        ),
                    ),
                    nextPage = null,
                    hasMore = false,
                )
            },
        )

        val authComicPlugin = FakeSitePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.EXHENTAI,
                displayName = "ExHentai",
                shortName = "ExHentai",
                primaryKind = MediaKind.COMIC,
                defaultHost = "exhentai.org",
                accessNote = "需登录及账号权限",
                capabilities = setOf(SiteCapability.FEED, SiteCapability.AUTH, SiteCapability.LANGUAGE_FILTER),
                networkProfile = SiteNetworkProfile(requiresAuthGate = true),
            ),
            homeHandler = {
                HomeFeed(
                    siteId = SiteId.EXHENTAI,
                    primaryFeedPage = FeedPage(
                        items = listOf(sampleMediaItem(SiteId.EXHENTAI, "ex1", MediaKind.COMIC, title = "Secret Book")),
                        hasMore = false,
                    ),
                )
            },
            requiresAuthGateFlag = true,
            isAuthenticated = exhentaiAuthenticated,
            authUsername = "UID 42",
        )

        val registry = SiteRegistry(listOf(videoPlugin, comicPlugin, authComicPlugin))
        return Triple(registry, videoPlugin, Triple(videoPlugin, comicPlugin, authComicPlugin))
    }

    @Test
    fun `video cinema home loads hero, ranked shelf, continue watching, tag translation, and watch later toggle`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            // Pre-populate unfinished + completed watch progress
            harness.historyRepository.saveWatchProgress(
                WatchProgress(
                    siteId = SiteId.R34V,
                    mediaId = "cw1",
                    url = "https://r34v.example/item/cw1",
                    title = "Half Watched Video",
                    positionMs = 120_000L,
                    durationMs = 600_000L,
                    updatedAt = 100L,
                    completed = false,
                ),
            )
            harness.historyRepository.saveWatchProgress(
                WatchProgress(
                    siteId = SiteId.R34V,
                    mediaId = "done1",
                    url = "https://r34v.example/item/done1",
                    title = "Completed Video",
                    positionMs = 590_000L,
                    durationMs = 600_000L,
                    updatedAt = 200L,
                    completed = true,
                ),
            )

            val (registry, _, _) = buildRegistry()
            val vm = HomeViewModel(
                siteRegistry = registry,
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
                val loaded = expectMostRecentItem()
                assertTrue(loaded.isVideoHome)
                assertFalse(loaded.isLoading)
                assertEquals(2, loaded.heroItems.size)
                // Tag translated from bundled 165-entry TagDictionary ("blonde" -> "金发")
                assertEquals("金发", loaded.heroItems.first().tags.first().label)
                assertEquals(1, loaded.sections.size)
                assertTrue(loaded.sections.first().isRanked)
                // Continue watching includes unfinished only
                assertEquals(listOf("cw1"), loaded.continueWatching.map { it.mediaId })
                // Tutorial NOT shown because user already has watch history
                assertFalse(loaded.showSiteSwitchTutorial)

                // Toggle watch later on hero item
                val hero = loaded.heroItems.first()
                vm.toggleWatchLater(hero)
                advanceUntilIdle()
                val afterAdd = expectMostRecentItem()
                assertTrue(hero.compositeKey in afterAdd.watchLaterKeys)

                vm.toggleWatchLater(hero)
                advanceUntilIdle()
                val afterRemove = expectMostRecentItem()
                assertFalse(hero.compositeKey in afterRemove.watchLaterKeys)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `blocked terms reactively filter hero, sections, primary items, and continue watching`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val (registry, videoPlugin, _) = buildRegistry()

            val vm = HomeViewModel(
                siteRegistry = registry,
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
                val initial = expectMostRecentItem()
                assertEquals(listOf("v1", "v2", "v3"), initial.primaryItems.map { it.id })
                assertEquals(1, videoPlugin.homeRequests.size)

                // Add blocked term "gore" — must filter v2 immediately without network reload
                harness.blockedTermsRepository.addTerm(SiteId.R34V, "gore")
                advanceUntilIdle()
                val filtered = expectMostRecentItem()
                assertEquals(listOf("v1", "v3"), filtered.primaryItems.map { it.id })
                assertEquals(listOf("v1"), filtered.heroItems.map { it.id })
                assertEquals(listOf("v1", "v3"), filtered.sections.first().items.map { it.id })
                assertEquals(1, videoPlugin.homeRequests.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `switching to comic site switches to masonry mode, handles language switch, resume reading, and pagination`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            harness.historyRepository.saveReadingProgress(
                ReadingProgress(
                    siteId = SiteId.NHENTAI,
                    mediaId = "book99",
                    url = "https://nhentai.example/g/99",
                    title = "Unfinished Manga",
                    kind = MediaKind.COMIC,
                    currentPage = 12,
                    totalPages = 40,
                    updatedAt = 500L,
                    completed = false,
                ),
            )
            val (registry, _, plugins) = buildRegistry()
            val comicPlugin = plugins.second

            val vm = HomeViewModel(
                siteRegistry = registry,
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
                val initial = expectMostRecentItem()
                assertEquals(listOf(SiteId.R34V, SiteId.NHENTAI, SiteId.EXHENTAI), initial.allSites.map { it.id })

                vm.selectSite(SiteId.NHENTAI)
                advanceUntilIdle()
                vm.selectSite(SiteId.EXHENTAI)
                vm.selectSite(SiteId.NHENTAI)
                advanceUntilIdle()

                val comicAll = expectMostRecentItem()
                assertEquals(listOf(SiteId.NHENTAI, SiteId.EXHENTAI, SiteId.R34V), comicAll.allSites.map { it.id })
                assertFalse(comicAll.isVideoHome)
                assertTrue(comicAll.supportsLanguageFilter)
                assertEquals(ComicLanguage.ALL, comicAll.comicLanguage)
                assertEquals("book99", comicAll.resumeReading?.mediaId)
                assertEquals(listOf("c_ALL_1", "c_ALL_2"), comicAll.primaryItems.map { it.id })

                // Switch comic language to CHINESE
                vm.selectComicLanguage(ComicLanguage.CHINESE)
                advanceUntilIdle()
                val comicZh = expectMostRecentItem()
                assertEquals(ComicLanguage.CHINESE, comicZh.comicLanguage)
                assertEquals(listOf("c_CHINESE_1", "c_CHINESE_2"), comicZh.primaryItems.map { it.id })
                assertEquals(ComicLanguage.CHINESE, harness.preferencesRepository.currentSettings().languageFor(SiteId.NHENTAI))
                assertEquals(listOf(ComicLanguage.ALL, ComicLanguage.CHINESE), comicPlugin.homeRequests)

                // Paginate page 2
                vm.loadNextPage()
                advanceUntilIdle()
                val paged = expectMostRecentItem()
                assertEquals(listOf("c_CHINESE_1", "c_CHINESE_2", "c_CHINESE_p2"), paged.primaryItems.map { it.id })
                assertFalse(paged.hasMore)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `auth gated site blocks network fetch until authenticated and loads after refresh`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val (registry, _, plugins) = buildRegistry(exhentaiAuthenticated = false)
            val exPlugin = plugins.third

            val vm = HomeViewModel(
                siteRegistry = registry,
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
                vm.selectSite(SiteId.EXHENTAI)
                advanceUntilIdle()

                val gated = expectMostRecentItem()
                assertTrue(gated.authGateRequired)
                assertTrue(gated.primaryItems.isEmpty())
                assertEquals(0, exPlugin.homeRequests.size)

                // Authenticate and refresh
                exPlugin.isAuthenticated = true
                vm.refresh()
                advanceUntilIdle()

                val unlocked = expectMostRecentItem()
                assertFalse(unlocked.authGateRequired)
                assertEquals(listOf("ex1"), unlocked.primaryItems.map { it.id })
                assertEquals(1, exPlugin.homeRequests.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `first-run site switch tutorial fires strictly once on empty user state and never repeats`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val (registry, _, _) = buildRegistry()

            val vm1 = HomeViewModel(
                siteRegistry = registry,
                preferencesRepository = harness.preferencesRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                favoriteRepository = harness.favoriteRepository,
                shelfRepository = harness.shelfRepository,
                browsePositionRepository = harness.browsePositionRepository,
                dispatchers = harness.dispatchers,
            )

            vm1.uiState.test {
                advanceUntilIdle()
                val first = expectMostRecentItem()
                assertTrue(first.showSiteSwitchTutorial)
                assertTrue(harness.preferencesRepository.currentSettings().siteSwitchTutorialSeen)

                vm1.dismissSiteSwitchTutorial()
                advanceUntilIdle()
                assertFalse(expectMostRecentItem().showSiteSwitchTutorial)
                cancelAndIgnoreRemainingEvents()
            }

            // Second ViewModel instance on same DataStore must NOT show tutorial again
            val vm2 = HomeViewModel(
                siteRegistry = registry,
                preferencesRepository = harness.preferencesRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                favoriteRepository = harness.favoriteRepository,
                shelfRepository = harness.shelfRepository,
                browsePositionRepository = harness.browsePositionRepository,
                dispatchers = harness.dispatchers,
            )
            vm2.uiState.test {
                advanceUntilIdle()
                assertFalse(expectMostRecentItem().showSiteSwitchTutorial)
                cancelAndIgnoreRemainingEvents()
            }

            // Fresh user who already has a favorite must NOT see tutorial
            val harnessWithFav = BrowseTestHarness(mainDispatcherRule.dispatcher)
            harnessWithFav.favoriteRepository.add(
                FavoriteItem(
                    siteId = SiteId.R34V,
                    targetId = "v1",
                    kind = FavoriteKind.VIDEO,
                    title = "Fav",
                    url = "https://r34v.example/item/v1",
                    addedAt = 1L,
                ),
            )
            val vm3 = HomeViewModel(
                siteRegistry = registry,
                preferencesRepository = harnessWithFav.preferencesRepository,
                blockedTermsRepository = harnessWithFav.blockedTermsRepository,
                historyRepository = harnessWithFav.historyRepository,
                favoriteRepository = harnessWithFav.favoriteRepository,
                shelfRepository = harnessWithFav.shelfRepository,
                browsePositionRepository = harnessWithFav.browsePositionRepository,
                dispatchers = harnessWithFav.dispatchers,
            )
            vm3.uiState.test {
                advanceUntilIdle()
                assertFalse(expectMostRecentItem().showSiteSwitchTutorial)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `browse position saves and exposes restored anchor across loads`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val (registry, _, _) = buildRegistry()

            val vm = HomeViewModel(
                siteRegistry = registry,
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
                vm.saveBrowsePosition(workId = "v3", throughPage = 3, shelfId = "latest")
                advanceUntilIdle()

                val withRestore = expectMostRecentItem()
                assertNotNull(withRestore.restoredPosition)
                assertEquals("v3", withRestore.restoredPosition?.workId)
                assertEquals(3, withRestore.restoredPosition?.throughPage)

                vm.dismissRestoredPosition()
                advanceUntilIdle()
                assertNull(expectMostRecentItem().restoredPosition)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `browse position writes are debounced and keep only the final anchor`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val (registry, _, _) = buildRegistry()
            val vm = HomeViewModel(
                siteRegistry = registry,
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
                vm.saveBrowsePosition(workId = "v1", throughPage = 1, shelfId = "")
                advanceTimeBy(200L)
                runCurrent()
                assertNull(expectMostRecentItem().restoredPosition)

                vm.saveBrowsePosition(workId = "v2", throughPage = 2, shelfId = "")
                advanceTimeBy(BROWSE_POSITION_DEBOUNCE_MS + 100L)
                runCurrent()
                advanceUntilIdle()
                val restored = expectMostRecentItem().restoredPosition
                assertEquals("v2", restored?.workId)
                assertEquals(2, restored?.throughPage)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `custom shelf picker creates shelf and toggles membership`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val (registry, _, _) = buildRegistry()
            val vm = HomeViewModel(
                siteRegistry = registry,
                preferencesRepository = harness.preferencesRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                favoriteRepository = harness.favoriteRepository,
                shelfRepository = harness.shelfRepository,
                browsePositionRepository = harness.browsePositionRepository,
                dispatchers = harness.dispatchers,
            )
            val item = sampleMediaItem(SiteId.R34V, "v9", MediaKind.VIDEO)

            vm.createShelfAndAdd("  我的片单  ", item)
            advanceUntilIdle()
            val created = harness.shelfRepository.observeShelvesWithSummary().first()
                .first { it.shelf.name == "我的片单" }
            assertEquals(1, created.itemCount)

            vm.setShelfMembership(created.shelf.id, item, add = false)
            advanceUntilIdle()
            val afterRemove = harness.shelfRepository.observeShelvesWithSummary().first()
                .first { it.shelf.id == created.shelf.id }
            assertEquals(0, afterRemove.itemCount)
        }

    @Test
    fun `setStartupDefaultSiteId persists custom startup site without changing current active site and resets cleanly`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val (registry, _, _) = buildRegistry()
            val vm = HomeViewModel(
                siteRegistry = registry,
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
                val initial = expectMostRecentItem()
                assertNull(initial.startupDefaultSiteId)
                assertEquals(SiteId.R34V, initial.activeSite?.id)

                vm.setStartupDefaultSiteId(SiteId.NHENTAI)
                advanceUntilIdle()
                val updated = expectMostRecentItem()
                assertEquals(SiteId.NHENTAI, updated.startupDefaultSiteId)
                assertEquals(SiteId.R34V, updated.activeSite?.id)
                assertEquals(SiteId.NHENTAI, harness.preferencesRepository.currentSettings().startupDefaultSiteId)

                vm.setStartupDefaultSiteId(null)
                advanceUntilIdle()
                val reset = expectMostRecentItem()
                assertNull(reset.startupDefaultSiteId)
                assertNull(harness.preferencesRepository.currentSettings().startupDefaultSiteId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `continue reading filters blocked and completed, sorts by updatedAt, truncates to twelve, and resumes newest`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            (1..14).forEach { index ->
                harness.historyRepository.saveReadingProgress(
                    ReadingProgress(
                        siteId = SiteId.NHENTAI,
                        mediaId = "book$index",
                        url = "https://nhentai.example/g/$index",
                        title = "Manga Book $index",
                        kind = MediaKind.COMIC,
                        currentPage = 3,
                        totalPages = 40,
                        updatedAt = index * 100L,
                        completed = false,
                    ),
                )
            }
            harness.historyRepository.saveReadingProgress(
                ReadingProgress(
                    siteId = SiteId.NHENTAI,
                    mediaId = "blocked_book",
                    url = "https://nhentai.example/g/blocked",
                    title = "Gore Book",
                    kind = MediaKind.COMIC,
                    currentPage = 4,
                    totalPages = 40,
                    updatedAt = 5_000L,
                    completed = false,
                ),
            )
            harness.historyRepository.saveReadingProgress(
                ReadingProgress(
                    siteId = SiteId.NHENTAI,
                    mediaId = "done_book",
                    url = "https://nhentai.example/g/done",
                    title = "Done Book",
                    kind = MediaKind.COMIC,
                    currentPage = 40,
                    totalPages = 40,
                    updatedAt = 4_500L,
                    completed = false,
                ),
            )
            harness.blockedTermsRepository.addTerm(SiteId.NHENTAI, "gore")

            val (registry, _, _) = buildRegistry()
            val vm = HomeViewModel(
                siteRegistry = registry,
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
                vm.selectSite(SiteId.NHENTAI)
                advanceUntilIdle()
                val comic = expectMostRecentItem()

                assertEquals((14 downTo 3).map { "book$it" }, comic.continueReading.map { it.mediaId })
                assertEquals(12, comic.continueReading.size)
                assertEquals("book14", comic.resumeReading?.mediaId)
                assertEquals(comic.continueReading.first(), comic.resumeReading)
                assertFalse(
                    comic.continueReading.any { it.mediaId == "blocked_book" || it.mediaId == "done_book" },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
}
