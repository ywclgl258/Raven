package com.raven.app.feature.settings

import app.cash.turbine.test
import com.raven.app.core.media.storage.DuplicateBookCandidate
import com.raven.app.core.media.storage.DuplicateCopy
import com.raven.app.core.media.storage.DuplicateScanner
import com.raven.app.core.media.storage.DuplicateSource
import com.raven.app.core.model.SiteId
import java.io.File
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
class DuplicateScannerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `scans duplicate books calculates reclaimable bytes toggles expansion and safely deletes selected copy`() = runTest {
        val dirA = tempFolder.newFolder("copy-a")
        val dirB = tempFolder.newFolder("copy-b")
        val dirUnique = tempFolder.newFolder("unique-c")

        val page1Bytes = ByteArray(1024) { (it % 127).toByte() }
        val page2Bytes = ByteArray(2048) { ((it * 3) % 127).toByte() }
        val uniqueBytes = ByteArray(1500) { 42 }

        val a1 = File(dirA, "0.image").apply { writeBytes(page1Bytes) }
        val a2 = File(dirA, "1.image").apply { writeBytes(page2Bytes) }
        val b1 = File(dirB, "0.image").apply { writeBytes(page1Bytes) }
        val b2 = File(dirB, "1.image").apply { writeBytes(page2Bytes) }
        val u1 = File(dirUnique, "0.image").apply { writeBytes(uniqueBytes) }

        val candidates = listOf(
            DuplicateBookCandidate(
                source = DuplicateSource.DOWNLOAD,
                id = "nhentai:12345",
                siteId = SiteId.NHENTAI,
                mediaId = "12345",
                title = "Sample Manga (Downloaded)",
                pageFiles = listOf(a1, a2),
                sourceRoot = dirA.absolutePath,
            ),
            DuplicateBookCandidate(
                source = DuplicateSource.LOCAL,
                id = "b".repeat(64),
                siteId = SiteId.LOCAL,
                mediaId = "b".repeat(64),
                title = "Sample Manga (Local CBZ)",
                pageFiles = listOf(b1, b2),
                sourceRoot = dirB.absolutePath,
            ),
            DuplicateBookCandidate(
                source = DuplicateSource.DOWNLOAD,
                id = "ehentai:999",
                siteId = SiteId.EHENTAI,
                mediaId = "999",
                title = "Unique Book",
                pageFiles = listOf(u1),
                sourceRoot = dirUnique.absolutePath,
            ),
        )

        val scanner = DuplicateScanner(
            localImporter = null,
            downloadedBookProvider = { candidates },
            dispatchers = mainDispatcherRule.appDispatchers,
        )
        val deletedCopies = mutableListOf<DuplicateCopy>()
        val registry = createTestSiteRegistry()

        val vm = DuplicateScannerViewModel(
            duplicateScanner = scanner,
            copyDeleter = DuplicateCopyDeleter { copy -> deletedCopies += copy },
            siteRegistry = registry,
            dispatchers = mainDispatcherRule.appDispatchers,
            autoScanOnInit = true,
        )

        vm.uiState.test {
            advanceUntilIdle()
            val scanned = expectMostRecentItem()
            assertFalse(scanned.isScanning)
            assertEquals(3, scanned.scannedBooks)
            assertEquals(2, scanned.hashedCandidates)
            assertEquals(1, scanned.groups.size)
            assertEquals(3072L, scanned.totalReclaimableBytes)
            assertEquals("本地导入库", vm.siteDisplayName(SiteId.LOCAL))
            assertEquals("nhentai", vm.siteDisplayName(SiteId.NHENTAI))

            val group = scanned.groups.single()
            assertTrue(group.fingerprint in scanned.expandedFingerprints)

            // Collapse and re-expand group
            vm.toggleGroupExpanded(group.fingerprint)
            assertFalse(group.fingerprint in expectMostRecentItem().expandedFingerprints)
            vm.toggleGroupExpanded(group.fingerprint)
            assertTrue(group.fingerprint in expectMostRecentItem().expandedFingerprints)

            // Request delete on the LOCAL copy -> confirmation sheet opens
            val localCopy = group.copies.first { it.source == DuplicateSource.LOCAL }
            vm.requestDeleteCopy(group, localCopy)
            val confirming = expectMostRecentItem()
            assertEquals(localCopy, confirming.pendingDeleteCopy)
            assertFalse(confirming.pendingDeleteIsLastCopy)

            // Confirm deletion -> copy deleted, group resolves since only 1 copy remains
            vm.confirmDeletePendingCopy()
            advanceUntilIdle()
            val afterDelete = expectMostRecentItem()
            assertEquals(listOf(localCopy), deletedCopies)
            assertNull(afterDelete.pendingDeleteCopy)
            assertTrue(afterDelete.groups.isEmpty())
            assertEquals(0L, afterDelete.totalReclaimableBytes)
            assertNotNull(afterDelete.feedbackMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
