package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.model.FeedPage
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagSubscription
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `first check establishes baseline with zero unread and second check surfaces new items for tags and saved searches`() = runTest {
        val tagResponses = mutableMapOf<String, List<MediaItem>>(
            "cyberpunk" to listOf(
                sampleMedia(SiteId.R34V, "v1", title = "Cyberpunk Episode 1"),
                sampleMedia(SiteId.R34V, "v2", title = "Cyberpunk Episode 2"),
            ),
            "mecha" to listOf(
                sampleMedia(SiteId.R34V, "m1", title = "Mecha Base"),
            ),
        )
        val r34vPlugin = FakeLibrarySitePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.R34V,
                displayName = "Rule34Video",
                shortName = "R34V",
                primaryKind = MediaKind.VIDEO,
                defaultHost = "rule34video.com",
            ),
            searchHandler = { request ->
                val q = request.query.orEmpty()
                FeedPage(items = tagResponses[q].orEmpty())
            },
        )
        val harness = LibraryTestHarness(
            dispatcher = mainRule.dispatcher,
            localRootDir = tempFolder.newFolder("sub1"),
            plugins = listOf(r34vPlugin),
        )
        harness.blockedTermsRepository.addTerm(SiteId.R34V, "forbidden")

        harness.subscriptionRepository.subscribe(
            TagSubscription(
                siteId = SiteId.R34V,
                slug = "cyberpunk",
                label = "赛博朋克",
                url = "https://rule34video.com/tags/cyberpunk/",
                scope = SearchScope.TAG,
            ),
        )
        harness.subscriptionRepository.saveSavedSearch(
            SavedSearch(
                id = "ss-mecha",
                name = "机甲追踪",
                query = "mecha",
                siteIds = listOf(SiteId.R34V),
                createdAt = harness.nowMs,
            ),
        )

        val vm = SubscriptionsViewModel(
            initialSiteId = null,
            subscriptionRepository = harness.subscriptionRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            preferencesRepository = harness.preferencesRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()

            // First check -> establishes baseline (all recorded items marked read, 0 unread)
            vm.refreshAllUpdates()
            advanceUntilIdle()
            val afterBaseline = expectMostRecentItem()
            assertEquals(3, afterBaseline.totalInboxCount)
            assertEquals(0, afterBaseline.unreadCount)
            assertEquals(2, afterBaseline.refreshProgress.baselinedSourceCount)

            // Publish new items (including a blocked item that must be filtered out)
            harness.nowMs += 60_000L
            tagResponses["cyberpunk"] = listOf(
                sampleMedia(SiteId.R34V, "v3", title = "Cyberpunk Episode 3"),
                sampleMedia(SiteId.R34V, "v-blocked", title = "Cyberpunk forbidden clip"),
                sampleMedia(SiteId.R34V, "v2", title = "Cyberpunk Episode 2"),
            )
            tagResponses["mecha"] = listOf(
                sampleMedia(SiteId.R34V, "m2", title = "Mecha Sequel"),
                sampleMedia(SiteId.R34V, "m1", title = "Mecha Base"),
            )

            // Second check -> only v3 and m2 appear as unread!
            vm.refreshAllUpdates()
            advanceUntilIdle()
            val afterSecond = expectMostRecentItem()
            assertEquals(2, afterSecond.unreadCount)
            assertEquals(1, afterSecond.unreadTagCount)
            assertEquals(1, afterSecond.unreadSavedSearchCount)

            // Unread filter + source filter
            vm.setInboxUnreadOnly(true)
            advanceUntilIdle()
            assertEquals(setOf("v3", "m2"), expectMostRecentItem().inboxItems.map { it.item.id }.toSet())

            vm.selectInboxSourceFilter(InboxSourceFilter.SAVED_SEARCH)
            advanceUntilIdle()
            val savedOnly = expectMostRecentItem()
            assertEquals(listOf("m2"), savedOnly.inboxItems.map { it.item.id })

            // Mark single read + mark all read
            val savedEntryId = savedOnly.inboxItems.single().id
            vm.markRead(savedEntryId)
            advanceUntilIdle()
            assertEquals(1, expectMostRecentItem().unreadCount)

            vm.selectInboxSourceFilter(InboxSourceFilter.ALL)
            vm.markAllRead()
            advanceUntilIdle()
            val afterMarkAll = expectMostRecentItem()
            assertEquals(0, afterMarkAll.unreadCount)
            assertEquals(0, afterMarkAll.inboxItems.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concurrent refresh caps parallelism at 3 and supports subscription reorder and unsubscribe`() = runTest {
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val plugin = FakeLibrarySitePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.NHENTAI,
                displayName = "nHentai",
                shortName = "nHentai",
                primaryKind = MediaKind.COMIC,
                defaultHost = "nhentai.net",
            ),
            searchHandler = { request ->
                val current = inFlight.incrementAndGet()
                maxObserved.accumulateAndGet(current, ::maxOf)
                try {
                    delay(40L)
                    FeedPage(items = listOf(sampleMedia(SiteId.NHENTAI, "item-${request.query}", MediaKind.COMIC)))
                } finally {
                    inFlight.decrementAndGet()
                }
            },
        )
        val harness = LibraryTestHarness(
            dispatcher = mainRule.dispatcher,
            localRootDir = tempFolder.newFolder("sub2"),
            plugins = listOf(plugin),
        )

        val slugs = listOf("tag1", "tag2", "tag3", "tag4", "tag5", "tag6")
        slugs.forEach { slug ->
            harness.subscriptionRepository.subscribe(
                TagSubscription(
                    siteId = SiteId.NHENTAI,
                    slug = slug,
                    label = slug,
                    url = "https://nhentai.net/tag/$slug/",
                ),
            )
        }

        val vm = SubscriptionsViewModel(
            initialSiteId = SiteId.NHENTAI,
            subscriptionRepository = harness.subscriptionRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            preferencesRepository = harness.preferencesRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()

            // Trigger 6 concurrent refreshes and verify Semaphore(3) never allows > 3 simultaneous calls
            vm.refreshTagUpdates()
            advanceUntilIdle()
            assertTrue("Expected max concurrency <= 3, saw ${maxObserved.get()}", maxObserved.get() <= 3)
            assertTrue("Expected concurrent execution >= 2, saw ${maxObserved.get()}", maxObserved.get() >= 2)
            val afterRefresh = expectMostRecentItem()
            assertEquals(6, afterRefresh.aggregatedTagFeed.size)

            // Reorder tag2 up ahead of tag1
            val subsBefore = afterRefresh.visibleSubscriptions
            assertEquals(listOf("tag1", "tag2", "tag3", "tag4", "tag5", "tag6"), subsBefore.map { it.slug })
            vm.moveSubscriptionUp(subsBefore[1])
            advanceUntilIdle()
            val afterMove = expectMostRecentItem()
            assertEquals(
                listOf("tag2", "tag1", "tag3", "tag4", "tag5", "tag6"),
                afterMove.visibleSubscriptions.map { it.slug },
            )

            // Unsubscribe removes subscription AND cleans its inbox entries
            val toRemove = afterMove.visibleSubscriptions.first()
            vm.unsubscribe(toRemove)
            advanceUntilIdle()
            val afterUnsub = expectMostRecentItem()
            assertEquals(5, afterUnsub.visibleSubscriptions.size)
            assertTrue(
                afterUnsub.aggregatedTagFeed.none {
                    it.sourceKind == InboxSourceKind.TAG_SUBSCRIPTION &&
                        it.sourceId == SubscriptionsViewModel.subscriptionSourceId(toRemove)
                },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
