package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.media.download.DownloadNetworkPolicy
import com.raven.app.core.media.reader.GalleryAudit
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
