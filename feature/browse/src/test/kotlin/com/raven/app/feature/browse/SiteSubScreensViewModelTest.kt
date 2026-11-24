package com.raven.app.feature.browse

import app.cash.turbine.test
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DirectoryEntry
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteSectionSpec
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SiteSubScreensViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `SiteFeedViewModel supports tag feed subscription toggle and sort switching`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.R34V,
                    displayName = "Rule34Video",
                    shortName = "R34V",
                    primaryKind = MediaKind.VIDEO,
                    defaultHost = "rule34video.com",
                    feedSorts = listOf(FeedSort.NEWEST, FeedSort.VIEWS),
                ),
                feedHandler = { req ->
                    FeedPage(
                        items = listOf(sampleMediaItem(SiteId.R34V, "f_${req.sort.name}", MediaKind.VIDEO)),
                        hasMore = false,
                    )
                },
                searchHandler = { req ->
                    FeedPage(
                        items = listOf(sampleMediaItem(SiteId.R34V, "s_${req.query}_${req.sort.name}", MediaKind.VIDEO)),
                        hasMore = false,
                    )
                },
            )

            val vm = SiteFeedViewModel(
                route = RavenRoute.SiteFeed(
                    siteId = SiteId.R34V.value,
                    query = "blonde",
                    scope = SearchScope.TAG.name,
                ),
                siteRegistry = SiteRegistry(listOf(plugin)),
                preferencesRepository = harness.preferencesRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                historyRepository = harness.historyRepository,
                shelfRepository = harness.shelfRepository,
                subscriptionRepository = harness.subscriptionRepository,
                browsePositionRepository = harness.browsePositionRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                val loaded = expectMostRecentItem()
                assertTrue(loaded.canSubscribe)
                assertFalse(loaded.isSubscribed)
                assertEquals("标签：金发", loaded.title)
                assertEquals(listOf("s_blonde_DEFAULT"), loaded.items.map { it.id })

                vm.toggleSubscription()
                advanceUntilIdle()
                assertTrue(expectMostRecentItem().isSubscribed)

                vm.selectSort(FeedSort.VIEWS)
                advanceUntilIdle()
                assertEquals(listOf("s_blonde_VIEWS"), expectMostRecentItem().items.map { it.id })

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `SiteDirectoryViewModel filters locally with Chinese tag translations and switches sections`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            val dirSections = listOf(
                SiteSectionSpec(id = "tags", title = "标签", isDirectory = true),
                SiteSectionSpec(id = "models", title = "作者", isDirectory = true),
            )
            val plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.R34V,
                    displayName = "Rule34Video",
                    primaryKind = MediaKind.VIDEO,
                    defaultHost = "rule34video.com",
                    capabilities = setOf(SiteCapability.DIRECTORY),
                ),
                directoryHandler = { section, _, _ ->
                    when (section) {
                        "tags" -> listOf(
                            DirectoryEntry(SiteId.R34V, "blonde", "Blonde", "https://r34v.example/tags/blonde", SearchScope.TAG, itemCount = 1200),
                            DirectoryEntry(SiteId.R34V, "glasses", "Glasses", "https://r34v.example/tags/glasses", SearchScope.TAG, itemCount = 450),
                        )
                        else -> listOf(
                            DirectoryEntry(SiteId.R34V, "artist1", "Artist One", "https://r34v.example/models/artist1", SearchScope.AUTHOR),
                        )
                    }
                },
                directorySectionsList = dirSections,
            )

            val vm = SiteDirectoryViewModel(
                route = RavenRoute.SiteDirectory(siteId = SiteId.R34V.value, section = "tags"),
                siteRegistry = SiteRegistry(listOf(plugin)),
                preferencesRepository = harness.preferencesRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                val loaded = expectMostRecentItem()
                assertEquals(2, loaded.entries.size)
                assertEquals("金发", loaded.entries.first().displayTitle)

                // Local filter matches Chinese tag translation ("眼镜" -> "glasses")
                vm.updateFilterQuery("眼镜")
                advanceUntilIdle()
                val filtered = expectMostRecentItem()
                assertEquals(listOf("glasses"), filtered.entries.map { it.entry.id })

                // Switching section clears filter and loads models
                vm.selectSection("models")
                advanceUntilIdle()
                val models = expectMostRecentItem()
                assertEquals("", models.filterQuery)
                assertEquals(listOf("artist1"), models.entries.map { it.entry.id })

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `SiteFunctionsViewModel aggregates site-scoped counts and updates language and reader mode`() =
        runTest(mainDispatcherRule.dispatcher) {
            val harness = BrowseTestHarness(mainDispatcherRule.dispatcher)
            harness.favoriteRepository.add(
                FavoriteItem(
                    siteId = SiteId.NHENTAI,
                    targetId = "c1",
                    kind = FavoriteKind.COMIC,
                    title = "Book 1",
                    url = "https://nhentai.net/g/1/",
                    addedAt = 1L,
                ),
            )
            harness.blockedTermsRepository.addTerm(SiteId.NHENTAI, "spam")

            val plugin = FakeSitePlugin(
                descriptor = SiteDescriptor(
                    id = SiteId.NHENTAI,
                    displayName = "nHentai",
                    primaryKind = MediaKind.COMIC,
                    defaultHost = "nhentai.net",
                    capabilities = setOf(SiteCapability.LANGUAGE_FILTER, SiteCapability.SEARCH),
                ),
            )

            val vm = SiteFunctionsViewModel(
                route = RavenRoute.SiteFunctions(siteId = SiteId.NHENTAI.value),
                siteRegistry = SiteRegistry(listOf(plugin)),
                preferencesRepository = harness.preferencesRepository,
                favoriteRepository = harness.favoriteRepository,
                downloadStateRepository = harness.downloadStateRepository,
                subscriptionRepository = harness.subscriptionRepository,
                blockedTermsRepository = harness.blockedTermsRepository,
                dispatchers = harness.dispatchers,
            )

            vm.uiState.test {
                advanceUntilIdle()
                val initial = expectMostRecentItem()
                assertEquals(1, initial.favoriteCount)
                assertEquals(1, initial.blockedTermCount)
                assertTrue(initial.supportsLanguageFilter)
                assertTrue(initial.supportsGalleryReader)
                assertEquals(ReaderMode.STRIP, initial.readerMode)

                vm.setComicLanguage(ComicLanguage.CHINESE_AND_JAPANESE)
                vm.setReaderMode(ReaderMode.RTL)
                advanceUntilIdle()

                val updated = expectMostRecentItem()
                assertEquals(ComicLanguage.CHINESE_AND_JAPANESE, updated.comicLanguage)
                assertEquals(ReaderMode.RTL, updated.readerMode)

                cancelAndIgnoreRemainingEvents()
            }
        }
}
