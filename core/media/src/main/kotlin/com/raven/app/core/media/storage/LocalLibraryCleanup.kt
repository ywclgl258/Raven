package com.raven.app.core.media.storage

import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.first

/**
 * Removes one imported local book together with every dangling reference to it.
 *
 * Shared by the Library Hub, Local Library and duplicate-cleanup surfaces so deleting local
 * content never leaves orphaned shelf items or watch/reading history behind.
 */
suspend fun deleteLocalBookCompletely(
    bookId: String,
    localLibraryImporter: LocalLibraryImporter,
    shelfRepository: ShelfRepository,
    historyRepository: HistoryRepository,
) {
    localLibraryImporter.removeBook(bookId)
    shelfRepository.observeContainingShelfIds(SiteId.LOCAL, bookId).first().forEach { shelfId ->
        shelfRepository.removeItem(shelfId, SiteId.LOCAL, bookId)
    }
    historyRepository.removeWatchProgress(SiteId.LOCAL, bookId)
    historyRepository.removeReadingProgress(SiteId.LOCAL, bookId)
}
