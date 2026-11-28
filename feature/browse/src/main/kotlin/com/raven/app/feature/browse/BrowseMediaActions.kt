package com.raven.app.feature.browse

import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Enqueue entry point shared by Browse screens. Implementations delegate to the existing
 * `DownloadCoordinator` APIs; tests substitute a fake.
 */
fun interface BrowseDownloadEnqueuer {
    suspend fun enqueue(item: MediaItem)
}

/** Maps a [MediaKind] to the favorite kind persisted by [FavoriteRepository]. */
internal fun favoriteKindFor(kind: MediaKind): FavoriteKind = when (kind) {
    MediaKind.VIDEO -> FavoriteKind.VIDEO
    MediaKind.PHOTO -> FavoriteKind.PHOTO
    MediaKind.COMIC, MediaKind.SERIAL_COMIC -> FavoriteKind.COMIC
}

internal fun MediaItem.toFavoriteItem(addedAt: Long): FavoriteItem = FavoriteItem(
    siteId = siteId,
    targetId = id,
    kind = favoriteKindFor(kind),
    title = title,
    url = url,
    coverUrl = coverUrl,
    addedAt = addedAt,
)

/**
 * Shared favorite/download controller reused by [HomeViewModel], [ExploreViewModel],
 * [SearchViewModel] and the SiteFeed screen so long-press actions behave identically.
 *
 * - Favorite keys come from [FavoriteRepository] media favorites (tag favorites stay untouched).
 * - Download state collapses into an active/completed snapshot used to suppress duplicate
 *   enqueues and label the row.
 * - User-facing feedback lands in [messages] following the existing transient-message pattern.
 */
internal class BrowseMediaActions(
    private val favoriteRepository: FavoriteRepository,
    private val downloadStateRepository: DownloadStateRepository,
    private val enqueuer: BrowseDownloadEnqueuer,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()
    private val enqueuingKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Composite keys (`siteId:mediaId`) of favorited works, optionally scoped to one site. */
    fun favoriteKeysFlow(siteId: SiteId? = null): Flow<Set<String>> {
        val source = if (siteId == null) {
            favoriteRepository.observeMediaFavorites()
        } else {
            favoriteRepository.observeBySite(siteId).map { list -> list.filter { it.kind.isMediaWork } }
        }
        return source.map { favorites ->
            favorites.mapNotNullTo(LinkedHashSet()) { favorite ->
                if (favorite.kind.isMediaWork) "${favorite.siteId.value}:${favorite.targetId}" else null
            }
        }
    }

    /** Download state per `siteId:mediaId`. `siteId == null` aggregates every site. */
    fun downloadStatesFlow(siteId: SiteId?): Flow<Map<String, MediaCardDownloadState>> {
        val source: Flow<List<com.raven.app.core.model.DownloadItem>> = if (siteId == null) {
            downloadStateRepository.observeAll()
        } else {
            downloadStateRepository.observeBySite(siteId)
        }
        return source.map { rows ->
            rows.groupBy { "${it.siteId.value}:${it.mediaId}" }.mapValues { (_, entries) ->
                MediaCardDownloadState(
                    isActive = entries.any { it.status.isActiveForBadge },
                    isCompleted = entries.any { it.status == DownloadStatus.COMPLETED },
                )
            }
        }
    }

    /** Toggles the work favorite for [item], emitting a message describing the outcome. */
    fun toggleFavorite(item: MediaItem) {
        scope.launch {
            try {
                val added = withContext(ioDispatcher) {
                    favoriteRepository.toggle(item.toFavoriteItem(clockMs()))
                }
                postMessage(if (added) "已收藏「${item.title}」" else "已取消收藏「${item.title}」")
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (error: Throwable) {
                postMessage("收藏操作失败：${error.message ?: "未知错误"}")
            }
        }
    }

    /**
     * Enqueues [item] through the existing download APIs. Refuses while an active task already
     * exists for the same work, and reports when one is already complete.
     */
    fun download(item: MediaItem) {
        val key = item.compositeKey
        if (!enqueuingKeys.add(key)) return
        scope.launch {
            try {
                val existing = withContext(ioDispatcher) {
                    downloadStateRepository.getByMedia(item.siteId, item.id)
                }
                if (existing.any { it.status.isActiveForBadge }) {
                    postMessage("「${item.title}」已在下载队列中")
                    return@launch
                }
                if (existing.any { it.status == DownloadStatus.COMPLETED }) {
                    postMessage("「${item.title}」已下载完成")
                    return@launch
                }
                withContext(ioDispatcher) { enqueuer.enqueue(item) }
                postMessage(
                    if (item.kind.isGallery) {
                        "已加入下载队列：${item.title}"
                    } else {
                        "已加入视频下载队列：${item.title}"
                    },
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (error: Throwable) {
                postMessage("下载失败：${error.message ?: "未知错误"}")
            } finally {
                enqueuingKeys.remove(key)
            }
        }
    }

    fun clearMessage() {
        _messages.value = null
    }

    private fun postMessage(message: String) {
        _messages.value = message
    }

    companion object {
        /** Empty-flow pair used when no repositories are wired (previews / tests). */
        fun emptyFavoriteKeys(): Flow<Set<String>> = flowOf(emptySet())

        fun emptyDownloadStates(): Flow<Map<String, MediaCardDownloadState>> = flowOf(emptyMap())
    }
}

/**
 * Statuses that block a duplicate enqueue. Retry-wait still counts as in-flight so we do not
 * create a second item while the engine is backing off.
 */
internal val DownloadStatus.isActiveForBadge: Boolean
    get() = this == DownloadStatus.QUEUED ||
        this == DownloadStatus.DOWNLOADING ||
        this == DownloadStatus.PAUSED ||
        this == DownloadStatus.RETRY_WAIT

/** Production [BrowseDownloadEnqueuer] delegating to the shared `DownloadCoordinator`. */
internal class CoordinatorBrowseDownloadEnqueuer(
    private val coordinator: com.raven.app.core.media.download.DownloadCoordinator,
) : BrowseDownloadEnqueuer {
    override suspend fun enqueue(item: MediaItem) {
        if (item.kind.isGallery) {
            coordinator.enqueueGallery(item)
        } else {
            coordinator.enqueueVideoWithPreferredQuality(item)
        }
    }
}
