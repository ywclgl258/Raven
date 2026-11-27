package com.raven.app.feature.library

import app.cash.turbine.test
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `serial chapters collapse to one work entry and resume latest chapter`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("hist_serial"))
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.MANGACOPY,
                mediaId = "series:ch1",
                url = "https://mangacopy.com/ch/1",
                title = "Chapter 1",
                kind = MediaKind.SERIAL_COMIC,
                currentPage = 10,
                totalPages = 20,
                updatedAt = 1_000L,
                seriesId = "series",
                seriesTitle = "Serial Work",
                seriesUrl = "https://mangacopy.com/series",
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.MANGACOPY,
                mediaId = "series:ch2",
                url = "https://mangacopy.com/ch/2",
                title = "Chapter 2",
                kind = MediaKind.SERIAL_COMIC,
                currentPage = 5,
                totalPages = 10,
                completed = true,
                updatedAt = 2_000L,
                seriesId = "series",
                seriesTitle = "Serial Work",
                seriesUrl = "https://mangacopy.com/series",
            ),
        )

        val vm = HistoryViewModel(
            historyRepository = harness.historyRepository,
            shelfRepository = harness.shelfRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(1, state.entries.size)
            assertEquals(1, state.totalCount)
            val entry = state.entries.single()
            assertEquals("series", entry.workId)
            assertEquals("Serial Work", entry.title)
            assertEquals("series:ch2", entry.mediaId)
            assertEquals("https://mangacopy.com/ch/2", entry.url)
            assertEquals(5, entry.resumePage)
            assertFalse(entry.completed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `remove entry deletes whole work and preserves other works`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("hist_remove"))
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.MANGACOPY,
                mediaId = "series:ch1",
                url = "https://mangacopy.com/ch/1",
                title = "Chapter 1",
                kind = MediaKind.SERIAL_COMIC,
                currentPage = 10,
                totalPages = 20,
                updatedAt = 1_000L,
                seriesId = "series",
                seriesTitle = "Serial Work",
            ),
        )
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.MANGACOPY,
                mediaId = "other",
                url = "https://mangacopy.com/other",
                title = "Other",
                kind = MediaKind.COMIC,
                currentPage = 3,
                totalPages = 10,
                updatedAt = 2_000L,
            ),
        )

        val vm = HistoryViewModel(
            historyRepository = harness.historyRepository,
            shelfRepository = harness.shelfRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            val serialEntry = state.entries.first { it.workId == "series" }
            vm.removeEntry(serialEntry)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals(listOf("other"), after.entries.map { it.workId })
            assertNull(harness.historyRepository.getReadingProgress(SiteId.MANGACOPY, "series:ch1"))
            assertNotNull(harness.historyRepository.getReadingProgress(SiteId.MANGACOPY, "other"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add history entry to shelf uses work id and url`() = runTest {
        val harness = LibraryTestHarness(mainRule.dispatcher, tempFolder.newFolder("hist_shelf"))
        harness.historyRepository.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.MANGACOPY,
                mediaId = "series:ch2",
                url = "https://mangacopy.com/ch/2",
                title = "Chapter 2",
                kind = MediaKind.SERIAL_COMIC,
                currentPage = 5,
                totalPages = 10,
                updatedAt = 2_000L,
                seriesId = "series",
                seriesTitle = "Serial Work",
                seriesUrl = "https://mangacopy.com/series",
            ),
        )

        val shelf = harness.shelfRepository.createShelf(name = "TestShelf")
        val vm = HistoryViewModel(
            historyRepository = harness.historyRepository,
            shelfRepository = harness.shelfRepository,
            siteRegistry = harness.siteRegistry,
            dispatchers = harness.dispatchers,
        )

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            val entry = state.entries.single()
            vm.addEntriesToShelf(shelf.id, listOf(entry))
            advanceUntilIdle()
            val items = harness.shelfRepository.observeShelfItems(shelf.id).first()
            assertEquals(1, items.size)
            assertEquals("series", items.single().mediaId)
            assertEquals("https://mangacopy.com/series", items.single().url)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
