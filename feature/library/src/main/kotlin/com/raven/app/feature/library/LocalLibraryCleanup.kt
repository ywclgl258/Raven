package com.raven.app.feature.library

import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.flow.first

/**
 * Removes one imported local book together with every dangling reference to it.
 *
 * Shared by the Library Hub and Local Library entry points so that deleting local content
 * never leaves orphaned shelf items or watch/reading history behind.
 */
internal suspend fun deleteLocalBookCompletely(
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
