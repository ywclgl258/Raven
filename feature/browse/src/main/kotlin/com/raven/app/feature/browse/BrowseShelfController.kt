package com.raven.app.feature.browse

import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.Shelf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debounce window for persisting scroll anchors so fast flings do not spam Room writes. */
internal const val BROWSE_POSITION_DEBOUNCE_MS: Long = 500L

/**
 * Shared custom-shelf & Watch Later action controller reused across Browse ViewModels
 * to avoid duplicating shelf membership observation and mutations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BrowseShelfController(
    private val shelfRepository: ShelfRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    val shelves: StateFlow<List<ShelfWithSummary>> = shelfRepository
        .observeShelvesWithSummary()
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    private val inspectedItem = MutableStateFlow<MediaItem?>(null)

    val inspectedShelfIds: StateFlow<Set<String>> = inspectedItem
        .flatMapLatest { item ->
            if (item == null || item.id.isBlank()) {
                flowOf(emptySet())
            } else {
                shelfRepository.observeContainingShelfIds(item.siteId, item.id).map { it.toSet() }
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptySet(),
        )

    fun inspectItem(item: MediaItem?) {
        inspectedItem.value = item
    }

    fun toggleWatchLater(item: MediaItem) {
        // 漫画与写真没有"稍后看"语义（阅读进度本身就会续读），只在视频上生效。
        if (!item.kind.isVideo) return
        scope.launch {
            withContext(ioDispatcher) {
                shelfRepository.toggleItem(Shelf.WATCH_LATER_ID, item.toShelfItem())
            }
        }
    }

    fun setMembership(shelfId: String, item: MediaItem, add: Boolean) {
        scope.launch {
            withContext(ioDispatcher) {
                if (add) {
                    shelfRepository.addItem(shelfId, item.toShelfItem())
                } else {
                    shelfRepository.removeItem(shelfId, item.siteId, item.id)
                }
            }
        }
    }

    fun createShelfAndAdd(name: String, item: MediaItem) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            withContext(ioDispatcher) {
                val created = shelfRepository.createShelf(name = trimmed)
                shelfRepository.addItem(created.id, item.toShelfItem())
            }
        }
    }
}
