package com.raven.app.core.media.download

import android.content.Context
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.SiteId
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Active sequential playback/reading queue identified by a compact UUID [token] (never passing
 * large item lists across Android Binder Intent/Navigation extras).
 */
@Serializable
data class PlaybackQueueSession(
    val token: String,
    val shelfId: String,
    val title: String,
    val items: List<MediaItem>,
    val currentIndex: Int = 0,
    val offlineOnly: Boolean = false,
) {
    val currentItem: MediaItem? get() = items.getOrNull(currentIndex)
    val nextItem: MediaItem? get() = items.getOrNull(currentIndex + 1)
    val hasNext: Boolean get() = nextItem != null
    val remainingCount: Int get() = (items.size - currentIndex - 1).coerceAtLeast(0)
}

/**
 * Result of starting an offline-only sequential session, reporting skipped non-offline entries.
 */
data class OfflineQueueStartResult(
    val session: PlaybackQueueSession,
    val skippedCount: Int,
)

/**
 * Pluggable persistence backend for [PlaybackQueueStore] (in-memory for JVM tests, atomic JSON file on Android).
 */
interface PlaybackQueuePersistence {
    fun load(): PlaybackQueueSession?
    fun save(session: PlaybackQueueSession?)
}

class InMemoryPlaybackQueuePersistence : PlaybackQueuePersistence {
    @Volatile
    private var stored: PlaybackQueueSession? = null

    override fun load(): PlaybackQueueSession? = stored

    override fun save(session: PlaybackQueueSession?) {
        stored = session
    }
}

class AtomicFilePlaybackQueuePersistence(
    private val file: File,
) : PlaybackQueuePersistence {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): PlaybackQueueSession? = runCatching {
        if (!file.isFile || file.length() !in 1L..MAX_QUEUE_JSON_BYTES) return null
        json.decodeFromString(PlaybackQueueSession.serializer(), file.readText())
    }.getOrNull()

    override fun save(session: PlaybackQueueSession?) {
        if (session == null) {
            file.delete()
            return
        }
        val parent = file.parentFile ?: return
        check(parent.isDirectory || parent.mkdirs()) { "无法创建播放队列存储目录" }
        val encoded = json.encodeToString(PlaybackQueueSession.serializer(), session)
        val temp = File(parent, "${file.name}.${System.nanoTime()}.part")
        try {
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                if (file.exists()) file.delete()
                check(temp.renameTo(file)) { "无法保存播放队列状态" }
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val MAX_QUEUE_JSON_BYTES: Long = 16L * 1024L * 1024L
    }
}

/**
 * UUID-token sequential playback & reading queue supporting online and offline-only sessions
 * without bloating Binder extras or blocking the main thread with synchronous `.commit()`.
 */
class PlaybackQueueStore(
    private val persistence: PlaybackQueuePersistence = InMemoryPlaybackQueuePersistence(),
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val tokenGenerator: () -> String = { UUID.randomUUID().toString() },
    private val applicationScope: CoroutineScope? = null,
) {
    constructor(
        context: Context,
        dispatchers: AppDispatchers = DefaultAppDispatchers(),
        applicationScope: CoroutineScope? = null,
    ) : this(
        persistence = AtomicFilePlaybackQueuePersistence(
            File(context.applicationContext.filesDir, "playback-queue.json"),
        ),
        dispatchers = dispatchers,
        applicationScope = applicationScope,
    )

    private val lock = Any()
    private val persistDirty = AtomicBoolean(false)
    private val persistRunning = AtomicBoolean(false)
    private val _currentSession = MutableStateFlow(persistence.load())
    val currentSession: StateFlow<PlaybackQueueSession?> = _currentSession.asStateFlow()

    fun snapshot(): PlaybackQueueSession? = synchronized(lock) { _currentSession.value }

    /**
     * Starts a new sequential session for [items] (deduplicated by `compositeKey`, capped at `50_000`).
     */
    fun start(
        title: String,
        shelfId: String,
        items: List<MediaItem>,
        offlineOnly: Boolean = false,
    ): PlaybackQueueSession {
        val unique = items.distinctBy { it.compositeKey }
        require(unique.isNotEmpty() && unique.size <= MAX_QUEUE_ITEMS) {
            "队列条目数必须在 1..$MAX_QUEUE_ITEMS 之间"
        }
        val session = PlaybackQueueSession(
            token = tokenGenerator(),
            shelfId = shelfId,
            title = title,
            items = unique,
            currentIndex = 0,
            offlineOnly = offlineOnly,
        )
        return writeLocked(session)
    }

    /**
     * Starts an offline-only sequential session after checking each candidate with [isOfflineReady],
     * skipping unavailable items and reporting how many were skipped.
     */
    suspend fun startOfflineFiltered(
        title: String,
        shelfId: String,
        candidates: List<MediaItem>,
        isOfflineReady: suspend (MediaItem) -> Boolean,
    ): OfflineQueueStartResult = withContext(dispatchers.io) {
        val unique = candidates.distinctBy { it.compositeKey }
        val ready = unique.filter { isOfflineReady(it) }
        check(ready.isNotEmpty()) { "当前筛选下没有完整可离线打开的未完成作品" }
        val session = start(title = title, shelfId = shelfId, items = ready, offlineOnly = true)
        OfflineQueueStartResult(
            session = session,
            skippedCount = unique.size - ready.size,
        )
    }

    /**
     * Validates that [token] matches the active session AND [siteId]/[mediaId] is the current head.
     */
    fun sessionFor(token: String?, siteId: SiteId, mediaId: String): PlaybackQueueSession? {
        if (token.isNullOrBlank()) return null
        val active = snapshot() ?: return null
        if (active.token != token) return null
        val current = active.currentItem ?: return null
        return active.takeIf { current.siteId == siteId && current.id == mediaId }
    }

    /**
     * Advances the queue to the next item if [token] and `(siteId, mediaId)` match the current item.
     * Returns `null` if the queue ended or token/item does not match.
     */
    fun advance(token: String, siteId: SiteId, mediaId: String): PlaybackQueueSession? = synchronized(lock) {
        val active = sessionFor(token, siteId, mediaId) ?: return null
        if (!active.hasNext) return null
        writeLocked(active.copy(currentIndex = active.currentIndex + 1))
    }

    /**
     * Selects any queue entry by [index], provided [token] and `(siteId, mediaId)` still match the
     * active item. Returns the updated session, or `null` for stale/invalid selections. Selecting
     * the current index is a successful no-op and never changes the persisted session.
     */
    fun select(
        token: String,
        siteId: SiteId,
        mediaId: String,
        index: Int,
    ): PlaybackQueueSession? = synchronized(lock) {
        val active = sessionFor(token, siteId, mediaId) ?: return null
        if (index !in active.items.indices) return null
        if (index == active.currentIndex) return active
        writeLocked(active.copy(currentIndex = index))
    }

    /**
     * Removes [index] atomically while keeping [token] stable. Removing an entry before the current
     * one shifts `currentIndex` left; removing after it leaves playback untouched. Removing the
     * current entry selects the next entry shifting into that index, or the previous entry when the
     * removed item was last. Removing the sole entry ends the session and returns `null`.
     *
     * Callers presenting a different `currentItem` after current-entry removal must open that item
     * in-place before treating the operation as complete.
     */
    fun removeAt(token: String, index: Int): PlaybackQueueSession? = synchronized(lock) {
        val active = _currentSession.value?.takeIf { it.token == token } ?: return null
        if (index !in active.items.indices) return null
        if (active.items.size == 1) {
            end(token)
            return null
        }
        val remaining = active.items.toMutableList().apply { removeAt(index) }
        val updatedIndex = when {
            index < active.currentIndex -> active.currentIndex - 1
            index == active.currentIndex -> active.currentIndex.coerceAtMost(remaining.lastIndex)
            else -> active.currentIndex
        }
        writeLocked(active.copy(items = remaining, currentIndex = updatedIndex))
    }

    fun end(token: String) {
        synchronized(lock) {
            if (_currentSession.value?.token != token) return
            _currentSession.value = null
            persistLatestLocked(null)
        }
    }

    fun endIfCurrent(token: String, siteId: SiteId, mediaId: String) = synchronized(lock) {
        if (sessionFor(token, siteId, mediaId) != null) {
            end(token)
        }
    }

    /**
     * Rolls back an [advance] or [start] if the UI fails to open the next item.
     */
    fun restore(previous: PlaybackQueueSession?, replacement: PlaybackQueueSession) {
        synchronized(lock) {
            val active = _currentSession.value
            if (active?.token != replacement.token || active.currentIndex != replacement.currentIndex) return
            if (previous == null) {
                end(replacement.token)
            } else {
                writeLocked(previous)
            }
        }
    }

    private fun writeLocked(session: PlaybackQueueSession): PlaybackQueueSession = synchronized(lock) {
        _currentSession.value = session
        persistLatestLocked(session)
        session
    }

    private fun persistLatestLocked(session: PlaybackQueueSession?) {
        val scope = applicationScope
        if (scope != null) {
            persistDirty.set(true)
            scheduleAsyncPersist(scope)
        } else {
            persistence.save(session)
        }
    }

    private fun scheduleAsyncPersist(scope: CoroutineScope) {
        if (!persistRunning.compareAndSet(false, true)) return
        scope.launch(dispatchers.io) {
            try {
                while (persistDirty.getAndSet(false)) {
                    val snapshotToSave = synchronized(lock) { _currentSession.value }
                    runCatching { persistence.save(snapshotToSave) }
                }
            } finally {
                persistRunning.set(false)
                if (persistDirty.get()) {
                    scheduleAsyncPersist(scope)
                }
            }
        }
    }

    companion object {
        const val MAX_QUEUE_ITEMS: Int = 50_000
    }
}
