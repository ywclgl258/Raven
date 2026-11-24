package com.raven.app.core.media.storage

import com.raven.app.core.media.TestMediaFixtures
import com.raven.app.core.model.SiteId
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicateScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeBook(dirName: String, pages: List<ByteArray>): List<File> {
        val dir = tempFolder.newFolder(dirName)
        return pages.mapIndexed { idx, bytes ->
            File(dir, "$idx.image").apply { writeBytes(bytes) }
        }
    }

    @Test
    fun `duplicate scanner pre-filters by pages and bytes, detects cross-source duplicates, and computes reclaimable bytes`() {
        val pageA = TestMediaFixtures.validPng("A-content")
        val pageB = TestMediaFixtures.validPng("B-content-longer")
        val pageUnique = TestMediaFixtures.validPng("Unique-page-xyz-999")

        val downloadCopy = writeBook("dl-copy", listOf(pageA, pageB))
        val localCopy = writeBook("local-copy", listOf(pageA, pageB))
        val thirdCopy = writeBook("third-copy", listOf(pageA, pageB))
        val swappedOrder = writeBook("swapped", listOf(pageB, pageA)) // same (pages, bytes) but reversed order!
        val uniqueBook = writeBook("unique", listOf(pageUnique))

        val uniqueValidationCalls = AtomicInteger(0)
        val scanner = DuplicateScanner()

        val report = scanner.scanCandidates(
            listOf(
                DuplicateBookCandidate(
                    source = DuplicateSource.DOWNLOAD,
                    id = "ehentai:100",
                    siteId = SiteId.EHENTAI,
                    mediaId = "100",
                    title = "Downloaded Book",
                    pageFiles = downloadCopy,
                    sourceRoot = "/private",
                ),
                DuplicateBookCandidate(
                    source = DuplicateSource.LOCAL,
                    id = "sha256-local",
                    siteId = SiteId.LOCAL,
                    mediaId = "sha256-local",
                    title = "Imported Copy",
                    pageFiles = localCopy,
                    sourceRoot = "/local",
                ),
                DuplicateBookCandidate(
                    source = DuplicateSource.DOWNLOAD,
                    id = "nhentai:200",
                    siteId = SiteId.NHENTAI,
                    mediaId = "200",
                    title = "Another Mirror",
                    pageFiles = thirdCopy,
                    sourceRoot = "/shared",
                ),
                DuplicateBookCandidate(
                    source = DuplicateSource.LOCAL,
                    id = "sha256-swapped",
                    siteId = SiteId.LOCAL,
                    mediaId = "sha256-swapped",
                    title = "Swapped Order",
                    pageFiles = swappedOrder,
                    sourceRoot = "/local",
                ),
                DuplicateBookCandidate(
                    source = DuplicateSource.LOCAL,
                    id = "sha256-unique",
                    siteId = SiteId.LOCAL,
                    mediaId = "sha256-unique",
                    title = "Unique Book",
                    pageFiles = uniqueBook,
                    validatePage = {
                        uniqueValidationCalls.incrementAndGet()
                        true
                    },
                ),
            ),
        )

        // Unique book never entered hashing stage due to (pages, bytes) pre-filter!
        assertEquals(0, uniqueValidationCalls.get())
        assertEquals(5, report.scannedBooks)
        assertEquals(4, report.hashedCandidates)

        // Only the 3 identical-order copies group together; swapped order has a distinct SHA-256 digest!
        assertEquals(1, report.groups.size)
        val group = report.groups.single()
        assertEquals(3, group.copies.size)
        val expectedUnitBytes = (pageA.size + pageB.size).toLong()
        assertEquals(expectedUnitBytes, group.unitBytes)
        assertEquals(expectedUnitBytes * 2L, group.reclaimableBytes)
        assertEquals(expectedUnitBytes * 2L, report.totalReclaimableBytes)
    }

    @Test
    fun `duplicate scanner excludes books with failed page validation`() {
        val page = TestMediaFixtures.validPng("same")
        val copy1 = writeBook("valid-1", listOf(page))
        val copy2 = writeBook("corrupt-2", listOf(page))

        val report = DuplicateScanner().scanCandidates(
            listOf(
                DuplicateBookCandidate(
                    source = DuplicateSource.DOWNLOAD,
                    id = "a",
                    siteId = SiteId.NHENTAI,
                    mediaId = "a",
                    title = "Valid",
                    pageFiles = copy1,
                    validatePage = { true },
                ),
                DuplicateBookCandidate(
                    source = DuplicateSource.LOCAL,
                    id = "b",
                    siteId = SiteId.LOCAL,
                    mediaId = "b",
                    title = "Corrupt",
                    pageFiles = copy2,
                    validatePage = { false },
                ),
            ),
        )

        assertTrue(report.groups.isEmpty())
        assertEquals(0L, report.totalReclaimableBytes)
    }
}
