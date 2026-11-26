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
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.sitespi.SitePlugin
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            advanceUntilIdle()
            vm.markAllRead()
            advanceUntilIdle()
            val afterMarkAll = expectMostRecentItem()
            assertEquals(0, afterMarkAll.unreadCount)
            assertEquals(0, afterMarkAll.inboxItems.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concurrent refresh caps parallelism at 3`() = runTest {
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

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `filters subscription feeds and live inbox using global bilingual and cached media tags`() = runTest {
        val plugin = FakeLibrarySitePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.R34V,
                displayName = "Rule34Video",
                shortName = "R34V",
                primaryKind = MediaKind.VIDEO,
                defaultHost = "rule34video.com",
            ),
            searchHandler = {
                FeedPage(
                    items = listOf(
                        sampleMedia(SiteId.R34V, "clean-item", title = "Clean Episode"),
                        sampleMedia(SiteId.R34V, "cached-tentacle", title = "Innocent Episode"),
                    ),
                )
            },
        )
        val harness = LibraryTestHarness(
            dispatcher = mainRule.dispatcher,
            localRootDir = tempFolder.newFolder("sub-block"),
            plugins = listOf(plugin),
        )
        // Global Chinese block ("触手" -> "tentacles") + MediaTagCacheRepository tag on untagged card
        harness.blockedTermsRepository.addTerm(SiteId.GLOBAL, "触手")
        harness.mediaTagCacheRepository.upsertTags(
            siteId = SiteId.R34V,
            mediaId = "cached-tentacle",
            tags = listOf(TagRef(slug = "tentacles", label = "tentacles")),
        )
        harness.subscriptionRepository.subscribe(
            TagSubscription(
                siteId = SiteId.R34V,
                slug = "anime",
                label = "动漫",
                url = "https://rule34video.com/tags/anime/",
                scope = SearchScope.TAG,
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
            tagDictionary = harness.runtimeDictionary,
            mediaTagCacheRepository = harness.mediaTagCacheRepository,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            vm.refreshAllUpdates()
            advanceUntilIdle()
            val afterRefresh = expectMostRecentItem()
            assertEquals(listOf("clean-item"), afterRefresh.aggregatedTagFeed.map { it.item.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `creates single multi and all logical subscription groups with derived coverage`() = runTest {
        val harness = LibraryTestHarness(
            dispatcher = mainRule.dispatcher,
            localRootDir = tempFolder.newFolder("sub-groups-create"),
            plugins = eligibleSubscriptionPlugins(),
        )
        val vm = createViewModel(harness)

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(3, initial.onlineTagSearchSites.size)

            vm.createSubscriptionGroup(
                slug = "solo",
                label = "单站标签",
                coverage = SubscriptionCoverage.SINGLE,
                selectedSiteIds = setOf(SiteId.R34V),
            )
            advanceUntilIdle()
            val afterSingle = expectMostRecentItem().subscriptionGroups.single { it.slug == "solo" }
            assertEquals(SubscriptionCoverage.SINGLE, afterSingle.coverage)
            assertEquals(1, afterSingle.siteCount)
            assertEquals(setOf(SiteId.R34V), afterSingle.siteIds)
            assertEquals("单站标签", afterSingle.label)

            vm.createSubscriptionGroup(
                slug = "multi",
                label = "多站标签",
                coverage = SubscriptionCoverage.MULTI,
                selectedSiteIds = setOf(SiteId.R34V, SiteId.NHENTAI),
            )
            advanceUntilIdle()
            val afterMulti = expectMostRecentItem().subscriptionGroups.single { it.slug == "multi" }
            assertEquals(SubscriptionCoverage.MULTI, afterMulti.coverage)
            assertEquals(2, afterMulti.siteCount)
            assertEquals(setOf(SiteId.R34V, SiteId.NHENTAI), afterMulti.siteIds)

            harness.subscriptionRepository.upsertInboxItems(
                listOf(tagInboxEntry(tagSourceId(SiteId.R34V, "multi"), SiteId.R34V, "multi-unread")),
            )
            advanceUntilIdle()
            val multiWithUnread = expectMostRecentItem().subscriptionGroups.single { it.slug == "multi" }
            assertEquals(1, multiWithUnread.unreadCount)

            vm.createSubscriptionGroup(
                slug = "all",
                label = "全站标签",
                coverage = SubscriptionCoverage.ALL,
                selectedSiteIds = emptySet(),
            )
            advanceUntilIdle()
            val afterAll = expectMostRecentItem().subscriptionGroups.single { it.slug == "all" }
            assertEquals(SubscriptionCoverage.ALL, afterAll.coverage)
            assertEquals(3, afterAll.siteCount)
            assertEquals(
                setOf(SiteId.R34V, SiteId.NHENTAI, SiteId.EVERIA),
                afterAll.siteIds,
            )
            assertEquals("全站标签", afterAll.label)

            val rows = harness.subscriptionRepository.observeAllSubscriptions().first()
            assertEquals(1, rows.count { it.slug == "solo" })
            assertEquals(2, rows.count { it.slug == "multi" })
            assertEquals(3, rows.count { it.slug == "all" })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updates single multi and all subscription groups by adding target sites and removing stale sites`() = runTest {
        val harness = LibraryTestHarness(
            dispatcher = mainRule.dispatcher,
            localRootDir = tempFolder.newFolder("sub-groups-update"),
            plugins = eligibleSubscriptionPlugins() + nonEligibleSubscriptionPlugin(),
        )
        val vm = createViewModel(harness)

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()

            // SINGLE -> SINGLE move, rename, and inbox cleanup
            vm.createSubscriptionGroup(
                slug = "solo",
                label = "旧单站",
                coverage = SubscriptionCoverage.SINGLE,
                selectedSiteIds = setOf(SiteId.R34V),
            )
            advanceUntilIdle()
            val soloGroup = expectMostRecentItem().subscriptionGroups.single { it.slug == "solo" }
            harness.subscriptionRepository.upsertInboxItems(
                listOf(tagInboxEntry(tagSourceId(SiteId.R34V, "solo"), SiteId.R34V, "solo-old")),
            )
            advanceUntilIdle()
            vm.updateSubscriptionGroup(
                group = soloGroup,
                label = "新单站",
                coverage = SubscriptionCoverage.SINGLE,
                selectedSiteIds = setOf(SiteId.NHENTAI),
            )
            advanceUntilIdle()
            val soloUpdated = expectMostRecentItem().subscriptionGroups.single { it.slug == "solo" }
            assertEquals(SubscriptionCoverage.SINGLE, soloUpdated.coverage)
            assertEquals(setOf(SiteId.NHENTAI), soloUpdated.siteIds)
            assertEquals("新单站", soloUpdated.label)
            assertTrue(
                harness.subscriptionRepository.observeInbox().first().none {
                    it.sourceId == tagSourceId(SiteId.R34V, "solo")
                },
            )

            // MULTI -> MULTI swap: remove R34V, keep NHENTAI, add EVERIA, rename all
            vm.createSubscriptionGroup(
                slug = "multi",
                label = "旧多站",
                coverage = SubscriptionCoverage.MULTI,
                selectedSiteIds = setOf(SiteId.R34V, SiteId.NHENTAI),
            )
            advanceUntilIdle()
            val multiGroup = expectMostRecentItem().subscriptionGroups.single { it.slug == "multi" }
            harness.subscriptionRepository.upsertInboxItems(
                listOf(tagInboxEntry(tagSourceId(SiteId.R34V, "multi"), SiteId.R34V, "multi-old")),
            )
            advanceUntilIdle()
            vm.updateSubscriptionGroup(
                group = multiGroup,
                label = "新多站",
                coverage = SubscriptionCoverage.MULTI,
                selectedSiteIds = setOf(SiteId.NHENTAI, SiteId.EVERIA),
            )
            advanceUntilIdle()
            val multiUpdated = expectMostRecentItem().subscriptionGroups.single { it.slug == "multi" }
            assertEquals(SubscriptionCoverage.MULTI, multiUpdated.coverage)
            assertEquals(setOf(SiteId.NHENTAI, SiteId.EVERIA), multiUpdated.siteIds)
            assertEquals("新多站", multiUpdated.label)
            assertTrue(
                harness.subscriptionRepository.observeInbox().first().none {
                    it.sourceId == tagSourceId(SiteId.R34V, "multi")
                },
            )

            // ALL -> adds every online TAG site and removes the non-eligible manual row
            harness.subscriptionRepository.subscribe(
                TagSubscription(
                    siteId = SiteId.R34V,
                    slug = "expand",
                    label = "旧全站",
                    url = "https://rule34video.com",
                    scope = SearchScope.TAG,
                ),
            )
            harness.subscriptionRepository.subscribe(
                TagSubscription(
                    siteId = SiteId.NHENTAI,
                    slug = "expand",
                    label = "旧全站",
                    url = "https://nhentai.net",
                    scope = SearchScope.TAG,
                ),
            )
            harness.subscriptionRepository.subscribe(
                TagSubscription(
                    siteId = SiteId.JABLE,
                    slug = "expand",
                    label = "旧全站",
                    url = "https://jable.tv",
                    scope = SearchScope.TAG,
                ),
            )
            advanceUntilIdle()
            val expandGroup = expectMostRecentItem().subscriptionGroups.single { it.slug == "expand" }
            harness.subscriptionRepository.upsertInboxItems(
                listOf(tagInboxEntry(tagSourceId(SiteId.JABLE, "expand"), SiteId.JABLE, "expand-old")),
            )
            advanceUntilIdle()
            vm.updateSubscriptionGroup(
                group = expandGroup,
                label = "新全站",
                coverage = SubscriptionCoverage.ALL,
                selectedSiteIds = emptySet(),
            )
            advanceUntilIdle()
            val expandUpdated = expectMostRecentItem().subscriptionGroups.single { it.slug == "expand" }
            assertEquals(SubscriptionCoverage.ALL, expandUpdated.coverage)
            assertEquals(setOf(SiteId.R34V, SiteId.NHENTAI, SiteId.EVERIA), expandUpdated.siteIds)
            assertEquals("新全站", expandUpdated.label)
            val expandRows = harness.subscriptionRepository.observeAllSubscriptions().first()
                .filter { it.slug == "expand" }
            assertFalse(expandRows.any { it.siteId == SiteId.JABLE })
            assertTrue(expandRows.all { it.label == "新全站" })
            assertTrue(
                harness.subscriptionRepository.observeInbox().first().none {
                    it.sourceId == tagSourceId(SiteId.JABLE, "expand")
                },
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deletes single multi and all logical subscription groups and clears their inbox rows`() = runTest {
        val harness = LibraryTestHarness(
            dispatcher = mainRule.dispatcher,
            localRootDir = tempFolder.newFolder("sub-groups-delete"),
            plugins = eligibleSubscriptionPlugins(),
        )
        val vm = createViewModel(harness)

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()

            vm.createSubscriptionGroup(
                slug = "del-solo",
                label = "删除单站",
                coverage = SubscriptionCoverage.SINGLE,
                selectedSiteIds = setOf(SiteId.R34V),
            )
            vm.createSubscriptionGroup(
                slug = "del-multi",
                label = "删除多站",
                coverage = SubscriptionCoverage.MULTI,
                selectedSiteIds = setOf(SiteId.R34V, SiteId.NHENTAI),
            )
            vm.createSubscriptionGroup(
                slug = "del-all",
                label = "删除全站",
                coverage = SubscriptionCoverage.ALL,
                selectedSiteIds = emptySet(),
            )
            advanceUntilIdle()
            val groups = expectMostRecentItem().subscriptionGroups
            harness.subscriptionRepository.upsertInboxItems(
                listOf(
                    tagInboxEntry(tagSourceId(SiteId.R34V, "del-solo"), SiteId.R34V, "del-solo-item"),
                    tagInboxEntry(tagSourceId(SiteId.R34V, "del-multi"), SiteId.R34V, "del-multi-r34"),
                    tagInboxEntry(tagSourceId(SiteId.NHENTAI, "del-multi"), SiteId.NHENTAI, "del-multi-nh"),
                    tagInboxEntry(tagSourceId(SiteId.R34V, "del-all"), SiteId.R34V, "del-all-r34"),
                    tagInboxEntry(tagSourceId(SiteId.NHENTAI, "del-all"), SiteId.NHENTAI, "del-all-nh"),
                    tagInboxEntry(tagSourceId(SiteId.EVERIA, "del-all"), SiteId.EVERIA, "del-all-ev"),
                ),
            )
            advanceUntilIdle()

            val solo = groups.single { it.slug == "del-solo" }
            val soloSource = tagSourceId(SiteId.R34V, "del-solo")
            vm.deleteSubscriptionGroup(solo)
            advanceUntilIdle()
            assertTrue(
                harness.subscriptionRepository.observeAllSubscriptions().first()
                    .none { it.slug == "del-solo" },
            )
            assertTrue(
                harness.subscriptionRepository.observeInbox().first()
                    .none { it.sourceId == soloSource },
            )

            val multi = groups.single { it.slug == "del-multi" }
            val multiSources = setOf(
                tagSourceId(SiteId.R34V, "del-multi"),
                tagSourceId(SiteId.NHENTAI, "del-multi"),
            )
            vm.deleteSubscriptionGroup(multi)
            advanceUntilIdle()
            assertTrue(
                harness.subscriptionRepository.observeAllSubscriptions().first()
                    .none { it.slug == "del-multi" },
            )
            assertTrue(
                harness.subscriptionRepository.observeInbox().first()
                    .none { it.sourceId in multiSources },
            )

            val all = groups.single { it.slug == "del-all" }
            val allSources = setOf(
                tagSourceId(SiteId.R34V, "del-all"),
                tagSourceId(SiteId.NHENTAI, "del-all"),
                tagSourceId(SiteId.EVERIA, "del-all"),
            )
            vm.deleteSubscriptionGroup(all)
            advanceUntilIdle()
            assertTrue(
                harness.subscriptionRepository.observeAllSubscriptions().first()
                    .none { it.slug == "del-all" },
            )
            assertTrue(
                harness.subscriptionRepository.observeInbox().first()
                    .none { it.sourceId in allSources },
            )

            val afterDeletes = expectMostRecentItem()
            assertTrue(afterDeletes.subscriptionGroups.isEmpty())
            assertTrue(afterDeletes.aggregatedTagFeed.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `checking a logical group refreshes every per-site source and establishes one shared baseline`() = runTest {
        val harness = LibraryTestHarness(
            dispatcher = mainRule.dispatcher,
            localRootDir = tempFolder.newFolder("sub-groups-check"),
            plugins = eligibleSubscriptionPlugins(
                r34vItems = listOf(sampleMedia(SiteId.R34V, "group-r34")),
                nhentaiItems = listOf(sampleMedia(SiteId.NHENTAI, "group-nh", MediaKind.COMIC)),
            ),
        )
        val vm = createViewModel(harness)

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            vm.createSubscriptionGroup(
                slug = "group-check",
                label = "整组检查",
                coverage = SubscriptionCoverage.MULTI,
                selectedSiteIds = setOf(SiteId.R34V, SiteId.NHENTAI),
            )
            advanceUntilIdle()
            val group = expectMostRecentItem().subscriptionGroups.single { it.slug == "group-check" }

            vm.checkSubscriptionGroup(group)
            advanceUntilIdle()
            val afterCheck = expectMostRecentItem()
            assertEquals(2, afterCheck.totalInboxCount)
            assertEquals(0, afterCheck.unreadTagCount)
            assertTrue(afterCheck.message.orEmpty().contains("建立基线"))
            assertTrue(afterCheck.checkingSourceIds.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SubscriptionSyncRunner tracks empty initial baseline and shared state across refreshes`() = runTest {
        val feed = mutableListOf<MediaItem>()
        val plugin = FakeLibrarySitePlugin(
            descriptor = SiteDescriptor(
                id = SiteId.R34V,
                displayName = "Rule34Video",
                shortName = "R34V",
                primaryKind = MediaKind.VIDEO,
                defaultHost = "rule34video.com",
            ),
            searchHandler = { FeedPage(items = feed.toList()) },
        )
        val harness = LibraryTestHarness(
            dispatcher = mainRule.dispatcher,
            localRootDir = tempFolder.newFolder("sub-empty-baseline"),
            plugins = listOf(plugin),
        )
        harness.subscriptionRepository.subscribe(
            TagSubscription(
                siteId = SiteId.R34V,
                slug = "rare_tag",
                label = "稀有标签",
                url = "",
                scope = SearchScope.TAG,
            ),
        )
        val runner = SubscriptionSyncRunner(
            subscriptionRepository = harness.subscriptionRepository,
            blockedTermsRepository = harness.blockedTermsRepository,
            preferencesRepository = harness.preferencesRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            clockMs = harness.clock,
        )

        // First check returns empty feed -> still marks baseline cleanly without unread items
        val firstPass = runner.syncAll()
        assertEquals(1, firstPass.baselinedSourceCount)
        assertEquals(0, firstPass.newlyDiscoveredCount)

        // Second check sees new item -> discovers 1 unread entry
        feed += sampleMedia(SiteId.R34V, "new-1", title = "Fresh Hit")
        val secondPass = runner.syncAll()
        assertEquals(0, secondPass.baselinedSourceCount)
        assertEquals(1, secondPass.newlyDiscoveredCount)
    }

    @Test
    fun `inbox search matches title site and source while mark all read only touches visible unread`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("sub-inbox-filter"))
        harness.subscriptionRepository.upsertInboxItems(
            listOf(
                UpdateInboxItem(
                    id = "tag-alpha",
                    sourceKind = InboxSourceKind.TAG_SUBSCRIPTION,
                    sourceId = "r34v:cyberpunk",
                    sourceLabel = "赛博朋克",
                    item = sampleMedia(SiteId.R34V, "v-alpha", title = "Cyberpunk Alpha"),
                    discoveredAt = 100L,
                ),
                UpdateInboxItem(
                    id = "tag-beta",
                    sourceKind = InboxSourceKind.TAG_SUBSCRIPTION,
                    sourceId = "r34v:mecha",
                    sourceLabel = "机甲",
                    item = sampleMedia(SiteId.R34V, "v-beta", title = "Mecha Beta"),
                    discoveredAt = 200L,
                ),
                UpdateInboxItem(
                    id = "search-gamma",
                    sourceKind = InboxSourceKind.SAVED_SEARCH,
                    sourceId = "ss-artbook",
                    sourceLabel = "画册追踪",
                    item = sampleMedia(SiteId.NHENTAI, "c-gamma", MediaKind.COMIC, title = "Artbook Gamma"),
                    discoveredAt = 300L,
                ),
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
            assertEquals(3, expectMostRecentItem().unreadCount)

            // Search by title
            vm.updateInboxSearchQuery("mecha")
            advanceUntilIdle()
            assertEquals(listOf("v-beta"), expectMostRecentItem().inboxItems.map { it.item.id })

            // Search by source label
            vm.updateInboxSearchQuery("画册")
            advanceUntilIdle()
            assertEquals(listOf("c-gamma"), expectMostRecentItem().inboxItems.map { it.item.id })

            // Search by site display name / id
            vm.updateInboxSearchQuery("nhentai")
            advanceUntilIdle()
            assertEquals(listOf("c-gamma"), expectMostRecentItem().inboxItems.map { it.item.id })

            // Scoped mark-all-read only marks the filtered entry, leaving other unread items intact
            vm.markAllRead()
            advanceUntilIdle()
            val afterScopedRead = expectMostRecentItem()
            assertEquals(2, afterScopedRead.unreadCount)
            assertTrue(afterScopedRead.inboxItems.single().isRead)
            val persisted = harness.subscriptionRepository.observeInbox().first().associateBy { it.id }
            assertEquals(false, persisted.getValue("tag-alpha").isRead)
            assertEquals(false, persisted.getValue("tag-beta").isRead)
            assertEquals(true, persisted.getValue("search-gamma").isRead)

            // Source filter + unread-only share the same filtered result set
            vm.updateInboxSearchQuery("")
            vm.setInboxUnreadOnly(true)
            vm.selectInboxSourceFilter(InboxSourceFilter.TAG_SUBSCRIPTION)
            advanceUntilIdle()
            val unreadTags = expectMostRecentItem()
            assertEquals(setOf("v-alpha", "v-beta"), unreadTags.inboxItems.map { it.item.id }.toSet())

            vm.markAllRead()
            advanceUntilIdle()
            val afterAllRead = expectMostRecentItem()
            assertEquals(0, afterAllRead.unreadCount)
            assertEquals(0, afterAllRead.inboxItems.size)

            // No visible unread -> no-op without a new message
            vm.clearMessage()
            advanceUntilIdle()
            vm.markAllRead()
            advanceUntilIdle()
            assertEquals(null, expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createViewModel(
        harness: LibraryTestHarness,
        initialSiteId: SiteId? = null,
    ): SubscriptionsViewModel = SubscriptionsViewModel(
        initialSiteId = initialSiteId,
        subscriptionRepository = harness.subscriptionRepository,
        blockedTermsRepository = harness.blockedTermsRepository,
        preferencesRepository = harness.preferencesRepository,
        siteRegistry = harness.siteRegistry,
        dispatchers = harness.dispatchers,
        clockMs = harness.clock,
        mediaTagCacheRepository = harness.mediaTagCacheRepository,
    )
}

private fun tagSourceId(
    siteId: SiteId,
    slug: String,
    scope: SearchScope = SearchScope.TAG,
): String = "${siteId.value}:${scope.name}:$slug"

private fun tagInboxEntry(
    sourceId: String,
    siteId: SiteId,
    itemId: String,
): UpdateInboxItem = UpdateInboxItem(
    id = "entry-$sourceId-$itemId",
    sourceKind = InboxSourceKind.TAG_SUBSCRIPTION,
    sourceId = sourceId,
    sourceLabel = "标签",
    item = sampleMedia(
        siteId = siteId,
        id = itemId,
        kind = when (siteId) {
            SiteId.NHENTAI -> MediaKind.COMIC
            SiteId.EVERIA -> MediaKind.PHOTO
            else -> MediaKind.VIDEO
        },
        title = "Update $itemId",
    ),
    discoveredAt = 1L,
)

private fun eligibleSubscriptionPlugins(
    r34vItems: List<MediaItem> = emptyList(),
    nhentaiItems: List<MediaItem> = emptyList(),
    everiaItems: List<MediaItem> = emptyList(),
): List<SitePlugin> = listOf(
    eligibleSubscriptionPlugin(
        siteId = SiteId.R34V,
        displayName = "Rule34Video",
        kind = MediaKind.VIDEO,
        host = "rule34video.com",
        items = r34vItems,
    ),
    eligibleSubscriptionPlugin(
        siteId = SiteId.NHENTAI,
        displayName = "nHentai",
        kind = MediaKind.COMIC,
        host = "nhentai.net",
        items = nhentaiItems,
    ),
    eligibleSubscriptionPlugin(
        siteId = SiteId.EVERIA,
        displayName = "Everia",
        kind = MediaKind.PHOTO,
        host = "everia.club",
        items = everiaItems,
    ),
)

private fun eligibleSubscriptionPlugin(
    siteId: SiteId,
    displayName: String,
    kind: MediaKind,
    host: String,
    items: List<MediaItem> = emptyList(),
): SitePlugin = FakeLibrarySitePlugin(
    descriptor = SiteDescriptor(
        id = siteId,
        displayName = displayName,
        shortName = displayName,
        primaryKind = kind,
        defaultHost = host,
        searchScopes = listOf(SearchScope.ALL, SearchScope.TAG),
    ),
    searchHandler = { FeedPage(items = items) },
)

private fun nonEligibleSubscriptionPlugin(): SitePlugin = FakeLibrarySitePlugin(
    descriptor = SiteDescriptor(
        id = SiteId.JABLE,
        displayName = "Jable",
        shortName = "Jable",
        primaryKind = MediaKind.VIDEO,
        defaultHost = "jable.tv",
    ),
)
