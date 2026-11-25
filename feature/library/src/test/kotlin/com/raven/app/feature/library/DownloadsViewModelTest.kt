package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.media.download.DownloadNetworkPolicy
import com.raven.app.core.media.reader.GalleryAudit
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `status tabs extra filters and batch controls operate cleanly across video and gallery items`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("dl1"))
        harness.downloadStateRepository.upsertAll(
            listOf(
                sampleDownload(
                    id = "v1",
                    siteId = SiteId.R34V,
                    mediaId = "v1",
                    kind = DownloadKind.VIDEO,
                    status = DownloadStatus.DOWNLOADING,
                    title = "Action Clip",
                    groupName = "精选",
                ),
                sampleDownload(
                    id = "c1",
                    siteId = SiteId.NHENTAI,
                    mediaId = "c1",
                    kind = DownloadKind.COMIC,
                    status = DownloadStatus.COMPLETED,
                    title = "Classic Manga",
                    groupName = "精选",
                    completedUnits = 20,
                    totalUnits = 20,
                ),
                sampleDownload(
                    id = "p1",
                    siteId = SiteId.EVERIA,
                    mediaId = "p1",
                    kind = DownloadKind.PHOTO,
                    status = DownloadStatus.FAILED,
                    title = "Photo Album",
                    groupName = "写真集",
                    errorCategory = DownloadErrorCategory.NETWORK,
                    errorMessage = "timeout",
                ),
            ),
        )

        val vm = DownloadsViewModel(
            downloadGateway = harness.downloadGateway,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            mediaTagCacheRepository = harness.mediaTagCacheRepository,
            preferencesRepository = harness.preferencesRepository,
            tagDictionary = harness.runtimeDictionary,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val allState = expectMostRecentItem()
            assertEquals(3, allState.allCount)
            assertEquals(1, allState.inProgressCount)
            assertEquals(1, allState.completedCount)
            assertEquals(1, allState.attentionCount)
            assertEquals(listOf("写真集", "精选"), allState.availableGroups)

            // Filter by status tab
            vm.selectTab(DownloadStatusTab.ATTENTION)
            advanceUntilIdle()
            assertEquals(listOf("p1"), expectMostRecentItem().items.map { it.id })

            // Filter by ALL + group + kind
            vm.selectTab(DownloadStatusTab.ALL)
            vm.selectGroupFilter("精选")
            vm.selectKindFilter(DownloadKindFilter.COMIC)
            advanceUntilIdle()
            assertEquals(listOf("c1"), expectMostRecentItem().items.map { it.id })

            // Reset filters and run batch pauseAll + resumeAll + retryAllFailed
            vm.resetExtraFilters()
            advanceUntilIdle()

            vm.pauseAll()
            advanceUntilIdle()
            assertEquals(1, harness.downloadGateway.pauseAllCount)
            assertEquals(DownloadStatus.PAUSED, harness.downloadStateRepository.getById("v1")?.status)

            vm.resumeAll()
            advanceUntilIdle()
            assertEquals(1, harness.downloadGateway.resumeAllCount)
            assertEquals(DownloadStatus.QUEUED, harness.downloadStateRepository.getById("v1")?.status)

            vm.retryAllFailed()
            advanceUntilIdle()
            assertTrue("p1" in harness.downloadGateway.retriedIds)
            assertEquals(DownloadStatus.QUEUED, harness.downloadStateRepository.getById("p1")?.status)

            // Network policy propagation
            harness.downloadGateway.mutableNetworkPolicy.value = DownloadNetworkPolicy(
                isAllowed = false,
                unmeteredOnly = true,
                isMetered = true,
                isConnected = true,
            )
            advanceUntilIdle()
            val policyState = expectMostRecentItem()
            assertFalse(policyState.networkPolicy.isAllowed)
            assertTrue(policyState.networkPolicy.isMetered)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `gallery integrity audit reports missing pages and repair re queues task while priority and group persist`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("dl2"))
        val comic = sampleDownload(
            id = "c-audit",
            siteId = SiteId.NHENTAI,
            mediaId = "999",
            kind = DownloadKind.COMIC,
            status = DownloadStatus.COMPLETED,
            title = "Corrupted Book",
            priority = 1,
        )
        harness.downloadStateRepository.upsert(comic)
        harness.downloadGateway.auditResults["c-audit"] = GalleryAudit(
            totalPages = 10,
            validPages = listOf(1, 2, 3, 4, 5, 6, 7, 8),
            missingPages = listOf(9),
            corruptPages = listOf(10),
            totalBytes = 8_000_000L,
        )

        val vm = DownloadsViewModel(
            downloadGateway = harness.downloadGateway,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            mediaTagCacheRepository = harness.mediaTagCacheRepository,
            preferencesRepository = harness.preferencesRepository,
            tagDictionary = harness.runtimeDictionary,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()

            // Inspect integrity -> exposes audit in integrityState
            vm.inspectIntegrity(comic)
            advanceUntilIdle()
            val audited = expectMostRecentItem()
            assertNotNull(audited.integrityState?.audit)
            assertFalse(audited.integrityState?.audit?.isComplete ?: true)
            assertEquals(listOf(9), audited.integrityState?.audit?.missingPages)
            assertEquals(listOf(10), audited.integrityState?.audit?.corruptPages)

            // Repair -> calls gateway.repair and clears sheet
            vm.repairGallery(comic)
            advanceUntilIdle()
            val repaired = expectMostRecentItem()
            assertNull(repaired.integrityState)
            assertEquals(listOf("c-audit"), harness.downloadGateway.repairedIds)
            assertEquals(DownloadStatus.QUEUED, harness.downloadStateRepository.getById("c-audit")?.status)

            // Priority, group, and delete
            vm.movePriority("c-audit", +2)
            vm.prioritize("c-audit")
            vm.setGroup("c-audit", "待补全")
            advanceUntilIdle()
            val updated = harness.downloadStateRepository.getById("c-audit")!!
            assertEquals("待补全", updated.groupName)
            assertTrue(updated.priority >= 3)

            vm.delete(updated)
            advanceUntilIdle()
            assertNull(harness.downloadStateRepository.getById("c-audit"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cached tags support translated search multi tag matching grouped sections and collapse`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("dl-tags"))
        harness.downloadStateRepository.upsertAll(
            listOf(
                sampleDownload(id = "a", mediaId = "media-a", title = "Beta"),
                sampleDownload(id = "b", mediaId = "media-b", title = "Alpha"),
                sampleDownload(id = "c", mediaId = "media-c", title = "Gamma"),
            ),
        )
        harness.mediaTagCacheRepository.upsertTags(
            SiteId.NHENTAI,
            "media-a",
            listOf(
                TagRef(slug = "artist:alice", label = "Alice"),
                TagRef(slug = "tentacles", label = "tentacles"),
            ),
        )
        harness.mediaTagCacheRepository.upsertTags(
            SiteId.NHENTAI,
            "media-b",
            listOf(TagRef(slug = "artist:alice", label = "Alice")),
        )
        harness.mediaTagCacheRepository.upsertTags(
            SiteId.NHENTAI,
            "media-c",
            listOf(TagRef(slug = "tentacles", label = "tentacles")),
        )
        val vm = DownloadsViewModel(
            downloadGateway = harness.downloadGateway,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            mediaTagCacheRepository = harness.mediaTagCacheRepository,
            preferencesRepository = harness.preferencesRepository,
            tagDictionary = harness.runtimeDictionary,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(2, initial.availableTags.size)
            assertEquals(setOf("artist", ""), initial.tagNamespaces.map { it.key }.toSet())
            assertEquals("触手", initial.availableTags.first { it.key == ":tentacles" }.label)

            vm.updateSearchQuery("触手")
            advanceUntilIdle()
            assertEquals(listOf("a", "c"), expectMostRecentItem().items.map { it.id }.sorted())

            vm.resetExtraFilters()
            vm.toggleTag("artist:alice")
            advanceUntilIdle()
            assertEquals(listOf("a", "b"), expectMostRecentItem().items.map { it.id }.sorted())

            vm.toggleTag(":tentacles")
            advanceUntilIdle()
            assertEquals(listOf("a", "b", "c"), expectMostRecentItem().items.map { it.id }.sorted())

            vm.toggleTagMatchAll()
            advanceUntilIdle()
            val allMatching = expectMostRecentItem()
            assertEquals(listOf("a"), allMatching.items.map { it.id })
            assertTrue(allMatching.tagMatchAll)

            vm.selectGroupMode(DownloadGroupMode.TAG)
            advanceUntilIdle()
            val grouped = expectMostRecentItem()
            assertEquals(DownloadGroupMode.TAG, grouped.groupMode)
            assertEquals(setOf("artist:alice", ":tentacles"), grouped.sections.map {
                it.key.removePrefix("TAG:named:")
            }.toSet())
            assertTrue(grouped.sections.all { section -> section.items.map { it.id } == listOf("a") })

            vm.toggleSection(grouped.sections.first().key)
            advanceUntilIdle()
            assertTrue(expectMostRecentItem().sections.first { it.key == grouped.sections.first().key }.isCollapsed)

            assertEquals("TAG", harness.settingsDataStore.currentSettings().downloadView.groupMode)
            assertTrue(harness.settingsDataStore.currentSettings().downloadView.tagMatchAll)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sort preference persists and custom groups support batch create rename merge and clear`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("dl-groups"))
        harness.downloadStateRepository.upsertAll(
            listOf(
                sampleDownload(id = "1", title = "Charlie", createdAt = 10L),
                sampleDownload(id = "2", title = "Alpha", createdAt = 30L),
                sampleDownload(id = "3", title = "Bravo", createdAt = 20L, groupName = "已有"),
            ),
        )
        val vm = DownloadsViewModel(
            downloadGateway = harness.downloadGateway,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            mediaTagCacheRepository = harness.mediaTagCacheRepository,
            preferencesRepository = harness.preferencesRepository,
            tagDictionary = harness.runtimeDictionary,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            expectMostRecentItem()

            vm.selectSort(DownloadSort.TITLE)
            advanceUntilIdle()
            assertEquals(listOf("2", "3", "1"), expectMostRecentItem().items.map { it.id })
            assertEquals("TITLE", harness.settingsDataStore.currentSettings().downloadView.sort)

            vm.createGroup("长途", setOf("1", "2"))
            advanceUntilIdle()
            assertEquals("长途", harness.downloadStateRepository.getById("1")?.groupName)
            assertEquals("长途", harness.downloadStateRepository.getById("2")?.groupName)
            assertEquals(listOf("已有", "长途"), expectMostRecentItem().availableGroups)

            vm.selectGroupMode(DownloadGroupMode.CUSTOM)
            advanceUntilIdle()
            val grouped = expectMostRecentItem()
            assertEquals(listOf("已有", "长途"), grouped.sections.map { it.title })
            assertEquals(listOf("2", "1"), grouped.sections.first { it.title == "长途" }.items.map { it.id })

            vm.renameGroup("长途", "已有")
            advanceUntilIdle()
            val merged = expectMostRecentItem()
            assertEquals(listOf("已有"), merged.availableGroups)
            assertEquals("已有", harness.downloadStateRepository.getById("1")?.groupName)
            assertEquals(3, merged.groupSummaries.single().itemCount)

            vm.clearGroup("已有")
            advanceUntilIdle()
            assertTrue(expectMostRecentItem().availableGroups.isEmpty())
            assertEquals(3, harness.downloadStateRepository.observeAll().first().size)
            assertTrue(harness.downloadStateRepository.observeAll().first().all { it.groupName.isEmpty() })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reserved looking custom group name does not collide with synthetic ungrouped section`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("dl-reserved-group"))
        harness.downloadStateRepository.upsertAll(
            listOf(
                sampleDownload(id = "named", title = "Named", groupName = "__ungrouped__"),
                sampleDownload(id = "plain", title = "Plain", groupName = ""),
            ),
        )
        val vm = DownloadsViewModel(
            downloadGateway = harness.downloadGateway,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
            mediaTagCacheRepository = harness.mediaTagCacheRepository,
            preferencesRepository = harness.preferencesRepository,
            tagDictionary = harness.runtimeDictionary,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            vm.selectGroupMode(DownloadGroupMode.CUSTOM)
            advanceUntilIdle()
            val sections = expectMostRecentItem().sections
            assertEquals(listOf("__ungrouped__", "未分组"), sections.map { it.title })
            assertEquals(2, sections.map { it.key }.distinct().size)
            assertEquals(listOf("named"), sections[0].items.map { it.id })
            assertEquals(listOf("plain"), sections[1].items.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
