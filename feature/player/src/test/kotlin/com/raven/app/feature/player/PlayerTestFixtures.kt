package com.raven.app.feature.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.raven.app.core.common.CustomAppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.DatabaseTransactionRunner
import com.raven.app.core.database.dao.BlockedTermDao
import com.raven.app.core.database.dao.DownloadItemDao
import com.raven.app.core.database.dao.FavoriteDao
import com.raven.app.core.database.dao.ImageFavoriteDao
import com.raven.app.core.database.dao.MediaTagCacheDao
import com.raven.app.core.database.dao.PageBookmarkDao
import com.raven.app.core.database.dao.PlaybackPreferencesDao
import com.raven.app.core.database.dao.ReaderPreferencesDao
import com.raven.app.core.database.dao.ReadingProgressDao
import com.raven.app.core.database.dao.ShelfDao
import com.raven.app.core.database.dao.ShelfItemDao
import com.raven.app.core.database.dao.SmartShelfDao
import com.raven.app.core.database.dao.TagTranslationDao
import com.raven.app.core.database.dao.TimeBookmarkDao
import com.raven.app.core.database.dao.WatchProgressDao
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.entity.BlockedTermEntity
import com.raven.app.core.database.entity.DownloadItemEntity
import com.raven.app.core.database.entity.FavoriteEntity
import com.raven.app.core.database.entity.ImageFavoriteEntity
import com.raven.app.core.database.entity.MediaTagCacheEntity
import com.raven.app.core.database.entity.PageBookmarkEntity
import com.raven.app.core.database.entity.PlaybackPreferencesEntity
import com.raven.app.core.database.entity.ReaderPreferencesEntity
import com.raven.app.core.database.entity.ReadingProgressEntity
import com.raven.app.core.database.entity.ShelfEntity
import com.raven.app.core.database.entity.ShelfItemEntity
import com.raven.app.core.database.entity.SmartShelfEntity
import com.raven.app.core.database.entity.TagTranslationEntity
import com.raven.app.core.database.entity.TimeBookmarkEntity
import com.raven.app.core.database.entity.WatchProgressEntity
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.media.download.InMemoryPlaybackQueuePersistence
import com.raven.app.core.media.download.OfflineMediaHandle
import com.raven.app.core.media.download.PlaybackQueueStore
import com.raven.app.core.media.playback.LocalSeekPreviewDecoder
import com.raven.app.core.media.playback.PlayerEngineState
import com.raven.app.core.media.playback.PlayerSessionController
import com.raven.app.core.media.playback.StreamSelector
import com.raven.app.core.media.playback.VideoLoopRange
import com.raven.app.core.media.storage.LocalBook
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteCapability
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.model.VideoDetail
import com.raven.app.core.model.VideoStream
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.core.sitespi.VideoSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

internal class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val next = transform(state.value).toPreferences()
            state.value = next
            next
        }
}

internal data class OnlineLoadCall(
    val siteId: SiteId,
    val mediaId: String,
    val sourceUrl: String,
    val preferredQuality: String,
    val startPositionMs: Long,
    val autoPlay: Boolean,
    val chosenStream: VideoStream,
)

internal data class OfflineLoadCall(
    val siteId: SiteId,
    val mediaId: String,
    val cacheKey: String,
    val startPositionMs: Long,
)

internal data class LocalLoadCall(
    val siteId: SiteId,
    val mediaId: String,
    val filePath: String,
    val startPositionMs: Long,
)

/**
 * Pure-JVM fake [PlayerPlaybackPort] reproducing [PlayerSessionController]'s exact state
 * transitions using real [VideoLoopRange] and [StreamSelector].
 */
internal class FakePlayerPlaybackPort(
    var defaultDurationMs: Long = 300_000L,
) : PlayerPlaybackPort {
    private val _state = MutableStateFlow(PlayerEngineState())
    override val state: StateFlow<PlayerEngineState> = _state.asStateFlow()

    val onlineLoads = mutableListOf<OnlineLoadCall>()
    val offlineLoads = mutableListOf<OfflineLoadCall>()
    val localLoads = mutableListOf<LocalLoadCall>()
    val switchedStreams = mutableListOf<VideoStream>()
    var loopTickCount: Int = 0
    var stallTickCount: Int = 0
    var nextStallDowngradeStream: VideoStream? = null
    var released: Boolean = false

    override fun currentPlayer(): ExoPlayer? = null

    override fun loadOnline(
        siteId: SiteId,
        mediaId: String,
        sourceUrl: String,
        streams: List<VideoStream>,
        preferredQuality: String,
        startPositionMs: Long,
        autoPlay: Boolean,
        streamCacheEnabled: Boolean,
        seekCacheEnabled: Boolean,
    ): VideoStream {
        val chosen = StreamSelector.pick(streams, preferredQuality, bandwidthKbps = 0)
        val isNewMedia = _state.value.siteId != siteId || _state.value.mediaId != mediaId
        onlineLoads += OnlineLoadCall(
            siteId = siteId,
            mediaId = mediaId,
            sourceUrl = sourceUrl,
            preferredQuality = preferredQuality,
            startPositionMs = startPositionMs,
            autoPlay = autoPlay,
            chosenStream = chosen,
        )
        _state.update {
            it.copy(
                isInitialized = true,
                isPlaying = autoPlay,
                isBuffering = false,
                playbackState = Player.STATE_READY,
                positionMs = startPositionMs.coerceAtLeast(0L),
                durationMs = defaultDurationMs,
                siteId = siteId,
                mediaId = mediaId,
                sourceUrl = sourceUrl,
                currentStream = chosen,
                availableStreams = streams,
                loopRange = if (isNewMedia) VideoLoopRange.NONE else it.loopRange,
                isOffline = false,
                isLocal = false,
                hasRefreshedExpiredStream = if (isNewMedia) false else it.hasRefreshedExpiredStream,
                autoDowngradeCount = if (isNewMedia) 0 else it.autoDowngradeCount,
                errorMessage = null,
                errorCode = null,
                lastNotice = null,
            )
        }
        return chosen
    }

    override fun loadOffline(
        siteId: SiteId,
        mediaId: String,
        sourceUrl: String,
        cacheKey: String,
        sourceRoot: String,
        qualityLabel: String,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        offlineLoads += OfflineLoadCall(siteId, mediaId, cacheKey, startPositionMs)
        val stream = VideoStream(
            quality = qualityLabel.ifBlank { "720p" },
            label = qualityLabel.ifBlank { "720p" },
            url = sourceUrl,
        )
        _state.update {
            it.copy(
                isInitialized = true,
                isPlaying = autoPlay,
                isBuffering = false,
                playbackState = Player.STATE_READY,
                positionMs = startPositionMs.coerceAtLeast(0L),
                durationMs = defaultDurationMs,
                siteId = siteId,
                mediaId = mediaId,
                sourceUrl = sourceUrl,
                currentStream = stream,
                availableStreams = listOf(stream),
                loopRange = VideoLoopRange.NONE,
                isOffline = true,
                isLocal = false,
                hasRefreshedExpiredStream = false,
                errorMessage = null,
                errorCode = null,
                lastNotice = null,
            )
        }
    }

    override fun loadLocalFile(
        siteId: SiteId,
        mediaId: String,
        filePath: String,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        localLoads += LocalLoadCall(siteId, mediaId, filePath, startPositionMs)
        val stream = VideoStream(quality = "local", label = "本地视频", url = filePath)
        _state.update {
            it.copy(
                isInitialized = true,
                isPlaying = autoPlay,
                isBuffering = false,
                playbackState = Player.STATE_READY,
                positionMs = startPositionMs.coerceAtLeast(0L),
                durationMs = defaultDurationMs,
                siteId = siteId,
                mediaId = mediaId,
                sourceUrl = filePath,
                currentStream = stream,
                availableStreams = listOf(stream),
                loopRange = VideoLoopRange.NONE,
                isOffline = true,
                isLocal = true,
                hasRefreshedExpiredStream = false,
                errorMessage = null,
                errorCode = null,
                lastNotice = null,
            )
        }
    }

    override fun switchStream(stream: VideoStream, preservePosition: Boolean, notice: String?) {
        switchedStreams += stream
        _state.update {
            it.copy(
                currentStream = stream,
                positionMs = if (preservePosition) it.positionMs else 0L,
                errorMessage = null,
                errorCode = null,
                lastNotice = notice,
            )
        }
    }

    override suspend fun refreshExpiredStream(refresher: suspend () -> List<VideoStream>): Boolean {
        val snapshot = _state.value
        if (snapshot.isOffline || snapshot.isLocal || snapshot.hasRefreshedExpiredStream) return false
        val previous = snapshot.currentStream ?: return false
        _state.update { it.copy(hasRefreshedExpiredStream = true) }
        val fresh = runCatching { refresher() }.getOrNull().orEmpty()
        if (fresh.isEmpty()) return false
        val selected = StreamSelector.afterRefresh(fresh, previous, bandwidthKbps = 0)
        _state.update { it.copy(availableStreams = fresh) }
        switchStream(selected, preservePosition = true, notice = "播放地址已自动刷新")
        return true
    }

    override fun play() {
        _state.update { it.copy(isPlaying = true, playbackState = Player.STATE_READY) }
    }

    override fun pause() {
        _state.update { it.copy(isPlaying = false) }
    }

    override fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        val dur = _state.value.durationMs
        val clamped = if (dur > 0L) positionMs.coerceIn(0L, dur) else positionMs.coerceAtLeast(0L)
        val loop = _state.value.loopRange
        val nextLoop = if (loop.isActive && !loop.contains(clamped)) VideoLoopRange.NONE else loop
        _state.update { it.copy(positionMs = clamped, loopRange = nextLoop) }
    }

    override fun seekBy(deltaMs: Long) {
        seekTo(_state.value.positionMs + deltaMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(PlayerSessionController.MIN_SPEED, PlayerSessionController.MAX_SPEED)
        _state.update { it.copy(playbackSpeed = clamped) }
    }

    override fun setLoopStart(positionMs: Long): VideoLoopRange {
        val updated = _state.value.loopRange.withStart(positionMs)
        _state.update { it.copy(loopRange = updated) }
        return updated
    }

    override fun setLoopEnd(positionMs: Long): VideoLoopRange {
        val updated = _state.value.loopRange.withEnd(positionMs)
        _state.update { it.copy(loopRange = updated) }
        return updated
    }

    override fun clearLoop() {
        _state.update { it.copy(loopRange = VideoLoopRange.NONE) }
    }

    override fun evaluateLoopTick(): Boolean {
        loopTickCount += 1
        val snap = _state.value
        if (snap.durationMs > 0L && !snap.loopRange.isValidFor(snap.durationMs)) {
            _state.update { it.copy(loopRange = VideoLoopRange.NONE) }
            return false
        }
        if (snap.loopRange.shouldRepeat(snap.positionMs, snap.isPlaying)) {
            _state.update { it.copy(positionMs = snap.loopRange.startMs) }
            return true
        }
        return false
    }

    override fun evaluateStallTick(): VideoStream? {
        stallTickCount += 1
        // Stalls are observable but must never mutate the selected quality.
        return null
    }

    override fun pipAspectRatioPair(): Pair<Int, Int>? =
        PlayerSessionController.computePipAspectRatio(_state.value.videoWidth, _state.value.videoHeight)

    override fun openSeekPreview(): LocalSeekPreviewDecoder? = null

    override fun clearNotice() {
        _state.update { it.copy(lastNotice = null) }
    }

    override fun currentPositionMs(): Long = _state.value.positionMs

    override fun syncSnapshot(clearError: Boolean) {
        if (clearError) {
            _state.update { it.copy(errorMessage = null, errorCode = null) }
        }
    }

    var selectedAudioTrackPair: Pair<Int, Int>? = null
    var selectedSubtitleTrackPair: Pair<Int, Int>? = null
    var subtitlesDisabledCount: Int = 0

    override fun selectAudioTrack(groupIndex: Int, trackIndex: Int) {
        selectedAudioTrackPair = groupIndex to trackIndex
    }

    override fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
        selectedSubtitleTrackPair = groupIndex to trackIndex
    }

    override fun disableSubtitles() {
        subtitlesDisabledCount += 1
    }

    override fun release() {
        released = true
        _state.value = PlayerEngineState()
    }

    // ---- Test Simulation Helpers ----

    fun simulatePosition(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
    }

    fun simulateError(message: String, code: Int = 2004) {
        _state.update {
            it.copy(
                isPlaying = false,
                errorMessage = message,
                errorCode = code,
            )
        }
    }

    fun simulateEnded() {
        _state.update {
            it.copy(
                isPlaying = false,
                playbackState = Player.STATE_ENDED,
                positionMs = it.durationMs.coerceAtLeast(it.positionMs),
            )
        }
    }

    fun simulateVideoSize(width: Int, height: Int) {
        _state.update { it.copy(videoWidth = width, videoHeight = height) }
    }
}

internal class FakePlayerOfflinePort : PlayerOfflinePort {
    val handles = mutableMapOf<String, OfflineMediaHandle>()
    val localBooks = mutableMapOf<String, LocalBook>()
    val localVideoFiles = mutableMapOf<String, File>()
    val downloadsFlow = MutableStateFlow<List<DownloadItem>>(emptyList())
    val enqueuedVideos = mutableListOf<Triple<MediaItem, VideoStream?, String>>()

    override fun observeDownloadsByMedia(siteId: SiteId, mediaId: String): Flow<List<DownloadItem>> =
        downloadsFlow.map { list -> list.filter { it.siteId == siteId && it.mediaId == mediaId } }

    override suspend fun findOfflineHandle(
        siteId: SiteId,
        mediaId: String,
        kind: MediaKind,
    ): OfflineMediaHandle? = handles["${siteId.value}:$mediaId"]

    override fun enqueueVideo(item: MediaItem, stream: VideoStream, groupName: String): String {
        enqueuedVideos += Triple(item, stream, groupName)
        return "${item.siteId.value}:${item.id}:${stream.quality}"
    }

    override suspend fun enqueueVideoWithPreferredQuality(item: MediaItem, groupName: String): String {
        enqueuedVideos += Triple(item, null, groupName)
        return "${item.siteId.value}:${item.id}:auto"
    }

    override fun getLocalBook(bookId: String): LocalBook? = localBooks[bookId]

    override fun getLocalVideoFile(bookId: String): File? = localVideoFiles[bookId]

    override fun localBookToMediaItem(book: LocalBook): MediaItem = MediaItem(
        siteId = SiteId.LOCAL,
        id = book.id,
        url = LocalLibraryImporter.buildLocalUri(book.id),
        kind = MediaKind.VIDEO,
        title = book.title,
    )
}

internal class FakeVideoSitePlugin(
    override val descriptor: SiteDescriptor,
    private val detailHandler: suspend (url: String) -> VideoDetail,
    private val refreshHandler: (suspend (url: String) -> List<VideoStream>)? = null,
) : SitePlugin {
    val detailRequests = mutableListOf<String>()
    val refreshRequests = mutableListOf<String>()

    override val videoSource: VideoSource = object : VideoSource {
        override suspend fun loadVideoDetail(url: String): VideoDetail {
            detailRequests += url
            return detailHandler(url)
        }

        override suspend fun refreshStreams(url: String): List<VideoStream> {
            refreshRequests += url
            return refreshHandler?.invoke(url) ?: detailHandler(url).streams
        }
    }
}

internal class PlayerTestHarness(
    dispatcher: TestDispatcher,
    var nowMs: Long = 1_700_000_000_000L,
    var elapsedMs: Long = 10_000L,
) {
    val dispatchers = CustomAppDispatchers(dispatcher)
    val clock: () -> Long = { nowMs }
    val elapsedClock: () -> Long = { elapsedMs }

    val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <R> invoke(block: suspend () -> R): R = block()
    }

    val watchDao = InMemoryWatchProgressDao()
    val readingDao = InMemoryReadingProgressDao()
    val favoriteDao = InMemoryFavoriteDao()
    val shelfDao = InMemoryShelfDao()
    val shelfItemDao = InMemoryShelfItemDao()
    val smartShelfDao = InMemorySmartShelfDao()
    val blockedTermDao = InMemoryBlockedTermDao()
    val downloadDao = InMemoryDownloadItemDao()
    val playbackPrefsDao = InMemoryPlaybackPreferencesDao()
    val readerPrefsDao = InMemoryReaderPreferencesDao()
    val pageBookmarkDao = InMemoryPageBookmarkDao()
    val timeBookmarkDao = InMemoryTimeBookmarkDao()
    val imageFavoriteDao = InMemoryImageFavoriteDao()
    val tagTranslationDao = InMemoryTagTranslationDao()
    val mediaTagCacheDao = InMemoryMediaTagCacheDao()

    val settingsDataStore = SettingsDataStore(InMemoryPreferencesDataStore())
    val preferencesRepository = PreferencesRepository(settingsDataStore, playbackPrefsDao, readerPrefsDao)
    val historyRepository = HistoryRepository(watchDao, readingDao, transactionRunner)
    val favoriteRepository = FavoriteRepository(favoriteDao, transactionRunner, clock)
    val shelfRepository = ShelfRepository(
        shelfDao = shelfDao,
        shelfItemDao = shelfItemDao,
        smartShelfDao = smartShelfDao,
        watchProgressDao = watchDao,
        readingProgressDao = readingDao,
        favoriteDao = favoriteDao,
        downloadItemDao = downloadDao,
        blockedTermDao = blockedTermDao,
        transactionRunner = transactionRunner,
        clock = clock,
    )
    val bookmarkRepository = BookmarkRepository(
        pageBookmarkDao = pageBookmarkDao,
        timeBookmarkDao = timeBookmarkDao,
        imageFavoriteDao = imageFavoriteDao,
        transactionRunner = transactionRunner,
        clock = clock,
    )
    val blockedTermsRepository = BlockedTermsRepository(blockedTermDao, transactionRunner, clock)
    val playbackQueueStore = PlaybackQueueStore(
        persistence = InMemoryPlaybackQueuePersistence(),
        dispatchers = dispatchers,
        tokenGenerator = { "queue-token-1" },
    )
    val playbackPort = FakePlayerPlaybackPort()
    val offlinePort = FakePlayerOfflinePort()
    val tagDictionary = TagDictionary(
        mapOf(
            "blonde" to "金发",
            "3d" to "3D 动画",
            "spoiler" to "剧透",
        ),
    )
    val tagDictionaryRepository = TagDictionaryRepository(
        tagTranslationDao = tagTranslationDao,
        transactionRunner = transactionRunner,
        runtimeDictionary = tagDictionary,
        clock = clock,
    )
    val mediaTagCacheRepository = MediaTagCacheRepository(
        mediaTagCacheDao = mediaTagCacheDao,
        transactionRunner = transactionRunner,
        clock = clock,
    )

    fun createViewModel(
        registry: SiteRegistry,
        initialArgs: PlayerLaunchArgs? = null,
    ): PlayerViewModel = PlayerViewModel(
        initialArgs = initialArgs,
        siteRegistry = registry,
        playback = playbackPort,
        offline = offlinePort,
        playbackQueueStore = playbackQueueStore,
        historyRepository = historyRepository,
        favoriteRepository = favoriteRepository,
        shelfRepository = shelfRepository,
        bookmarkRepository = bookmarkRepository,
        blockedTermsRepository = blockedTermsRepository,
        preferencesRepository = preferencesRepository,
        dispatchers = dispatchers,
        tagDictionary = tagDictionary,
        clockMs = clock,
        elapsedRealtimeMs = elapsedClock,
        mediaTagCacheRepository = mediaTagCacheRepository,
        tagDictionaryRepository = tagDictionaryRepository,
    )
}

internal fun sampleVideoDescriptor(
    siteId: SiteId,
    displayName: String = siteId.value.uppercase(),
): SiteDescriptor = SiteDescriptor(
    id = siteId,
    displayName = displayName,
    shortName = displayName,
    primaryKind = MediaKind.VIDEO,
    defaultHost = "${siteId.value}.example",
    capabilities = setOf(SiteCapability.FEED, SiteCapability.STREAM_QUALITIES, SiteCapability.TAGS, SiteCapability.RELATED),
)

internal fun sampleVideoItem(
    siteId: SiteId = SiteId.R34V,
    id: String,
    title: String = "Video $id",
    tags: List<TagRef> = emptyList(),
): MediaItem = MediaItem(
    siteId = siteId,
    id = id,
    url = "https://${siteId.value}.example/video/$id",
    kind = MediaKind.VIDEO,
    title = title,
    coverUrl = "https://${siteId.value}.example/cover/$id.jpg",
    durationSeconds = 300,
    views = 12_500L,
    likes = 820L,
    tags = tags,
)

internal fun sampleStreams(prefix: String = "v1"): List<VideoStream> = listOf(
    VideoStream(quality = "1080p", label = "1080p", url = "https://cdn.example/$prefix/1080p.mp4"),
    VideoStream(quality = "720p", label = "720p", url = "https://cdn.example/$prefix/720p.mp4"),
    VideoStream(quality = "480p", label = "480p", url = "https://cdn.example/$prefix/480p.mp4"),
)

// ---- Lightweight Reactive In-Memory DAOs ----

internal class InMemoryWatchProgressDao : WatchProgressDao {
    private val rows = MutableStateFlow<List<WatchProgressEntity>>(emptyList())
    override fun observeRecent(limit: Int) = rows.map { it.sortedByDescending { e -> e.updatedAt }.take(limit) }
    override fun observeAll() = rows.map { it.sortedByDescending { e -> e.updatedAt } }
    override fun observeBySite(siteId: String) = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeOne(siteId: String, mediaId: String) =
        rows.map { it.firstOrNull { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override suspend fun getOne(siteId: String, mediaId: String) =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: WatchProgressEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.mediaId == entity.mediaId } + entity }
    }
    override suspend fun upsertAll(entities: List<WatchProgressEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) {
        rows.update { list ->
            val kept = list.filter { it.siteId == siteId }.sortedByDescending { it.updatedAt }.take(limit)
            list.filterNot { it.siteId == siteId } + kept
        }
    }
}

internal class InMemoryReadingProgressDao : ReadingProgressDao {
    private val rows = MutableStateFlow<List<ReadingProgressEntity>>(emptyList())
    override fun observeRecent(limit: Int) = rows
    override fun observeAll() = rows
    override fun observeBySite(siteId: String) = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeOne(siteId: String, mediaId: String) =
        rows.map { it.firstOrNull { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override suspend fun getOne(siteId: String, mediaId: String) =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: ReadingProgressEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.mediaId == entity.mediaId } + entity }
    }
    override suspend fun upsertAll(entities: List<ReadingProgressEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun pruneSite(siteId: String, limit: Int) = Unit
}

internal class InMemoryFavoriteDao : FavoriteDao {
    private val rows = MutableStateFlow<List<FavoriteEntity>>(emptyList())
    override fun observeAll() = rows
    override fun observeBySite(siteId: String) = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeByKind(kind: FavoriteKind) = rows.map { it.filter { e -> e.kind == kind } }
    override fun observeBySiteAndKind(siteId: String, kind: FavoriteKind) =
        rows.map { it.filter { e -> e.siteId == siteId && e.kind == kind } }
    override fun observeMediaFavorites() =
        rows.map { it.filter { e -> e.kind in setOf(FavoriteKind.VIDEO, FavoriteKind.COMIC, FavoriteKind.PHOTO) } }
    override fun observeExists(siteId: String, kind: FavoriteKind, targetId: String) =
        rows.map { it.any { e -> e.siteId == siteId && e.kind == kind && e.targetId == targetId } }
    override suspend fun exists(siteId: String, kind: FavoriteKind, targetId: String) =
        rows.value.any { it.siteId == siteId && it.kind == kind && it.targetId == targetId }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: FavoriteEntity) {
        rows.update { list ->
            list.filterNot { it.siteId == entity.siteId && it.kind == entity.kind && it.targetId == entity.targetId } + entity
        }
    }
    override suspend fun upsertAll(entities: List<FavoriteEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, kind: FavoriteKind, targetId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.kind == kind && it.targetId == targetId } }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryShelfDao : ShelfDao {
    private val rows = MutableStateFlow<List<ShelfEntity>>(emptyList())
    override fun observeAll() = rows
    override fun observeById(id: String) = rows.map { it.firstOrNull { e -> e.id == id } }
    override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll() = rows.value
    override suspend fun insertIgnore(entity: ShelfEntity): Long {
        if (rows.value.any { it.id == entity.id }) return -1L
        rows.update { it + entity }
        return 1L
    }
    override suspend fun upsert(entity: ShelfEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<ShelfEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateModifiedAt(id: String, modifiedAt: Long) {
        rows.update { list -> list.map { if (it.id == id) it.copy(modifiedAt = modifiedAt) else it } }
    }
    override suspend fun clearCoverIfMatching(id: String, compositeKey: String, modifiedAt: Long) = Unit
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryShelfItemDao : ShelfItemDao {
    private val rows = MutableStateFlow<List<ShelfItemEntity>>(emptyList())
    override fun observeAll() = rows
    override fun observeByShelf(shelfId: String) = rows.map { it.filter { e -> e.shelfId == shelfId } }
    override fun observeContainingShelfIds(siteId: String, mediaId: String) =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.map { e -> e.shelfId } }
    override suspend fun getByShelf(shelfId: String) = rows.value.filter { it.shelfId == shelfId }
    override suspend fun getAll() = rows.value
    override suspend fun getOne(shelfId: String, siteId: String, mediaId: String) =
        rows.value.firstOrNull { it.shelfId == shelfId && it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getMaxPosition(shelfId: String) =
        rows.value.filter { it.shelfId == shelfId }.maxOfOrNull { it.position }
    override suspend fun upsert(entity: ShelfItemEntity) {
        rows.update { list ->
            list.filterNot { it.shelfId == entity.shelfId && it.siteId == entity.siteId && it.mediaId == entity.mediaId } + entity
        }
    }
    override suspend fun upsertAll(entities: List<ShelfItemEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateCompleted(shelfId: String, siteId: String, mediaId: String, completed: Boolean) = Unit
    override suspend fun updatePosition(shelfId: String, siteId: String, mediaId: String, position: Int) = Unit
    override suspend fun delete(shelfId: String, siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.shelfId == shelfId && it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteByShelf(shelfId: String) {
        rows.update { list -> list.filterNot { it.shelfId == shelfId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemorySmartShelfDao : SmartShelfDao {
    private val rows = MutableStateFlow<List<SmartShelfEntity>>(emptyList())
    override fun observeAll() = rows
    override fun observeById(id: String) = rows.map { it.firstOrNull { e -> e.id == id } }
    override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: SmartShelfEntity) { rows.update { list -> list.filterNot { it.id == entity.id } + entity } }
    override suspend fun upsertAll(entities: List<SmartShelfEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryBlockedTermDao : BlockedTermDao {
    private val rows = MutableStateFlow<List<BlockedTermEntity>>(emptyList())
    override fun observeAll() = rows
    override fun observeBySite(siteId: String) = rows.map { it.filter { e -> e.siteId == siteId } }
    override suspend fun exists(siteId: String, term: String) = rows.value.any { it.siteId == siteId && it.term == term }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: BlockedTermEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.term == entity.term } + entity }
    }
    override suspend fun upsertAll(entities: List<BlockedTermEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, term: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.term == term } }
    }
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryDownloadItemDao : DownloadItemDao {
    private val rows = MutableStateFlow<List<DownloadItemEntity>>(emptyList())
    override fun observeAll() = rows
    override fun observeByStatus(status: DownloadStatus) = rows.map { it.filter { e -> e.status == status } }
    override fun observeByStatuses(statuses: List<DownloadStatus>) = rows.map { it.filter { e -> e.status in statuses } }
    override fun observeBySite(siteId: String) = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeById(id: String) = rows.map { it.firstOrNull { e -> e.id == id } }
    override fun observeByMedia(siteId: String, mediaId: String) =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override fun observeCompletedCount() = rows.map { it.count { e -> e.status == DownloadStatus.COMPLETED } }
    override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
    override suspend fun getByMedia(siteId: String, mediaId: String) =
        rows.value.filter { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: DownloadItemEntity) { rows.update { list -> list.filterNot { it.id == entity.id } + entity } }
    override suspend fun upsertAll(entities: List<DownloadItemEntity>) = entities.forEach { upsert(it) }
    override suspend fun updateStatus(id: String, status: DownloadStatus, errorCategory: DownloadErrorCategory?, errorMessage: String?, retryAtEpochMs: Long?, updatedAt: Long) = Unit
    override suspend fun updateProgress(id: String, progressFraction: Float, completedUnits: Int, totalUnits: Int, downloadedBytes: Long, totalBytes: Long?, updatedAt: Long) = Unit
    override suspend fun updatePriority(id: String, priority: Int, updatedAt: Long) = Unit
    override suspend fun updateGroupName(id: String, groupName: String, updatedAt: Long) = Unit
    override suspend fun updateLocalPath(id: String, storageLocation: StorageLocation, localPath: String?, updatedAt: Long) = Unit
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) { rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } } }
    override suspend fun deleteByStatus(status: DownloadStatus) { rows.update { list -> list.filterNot { it.status == status } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryPlaybackPreferencesDao : PlaybackPreferencesDao {
    private val rows = MutableStateFlow<List<PlaybackPreferencesEntity>>(emptyList())
    override fun observeBySite(siteId: String) = rows.map { it.firstOrNull { e -> e.siteId == siteId } }
    override fun observeAll() = rows
    override suspend fun getBySite(siteId: String) = rows.value.firstOrNull { it.siteId == siteId }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: PlaybackPreferencesEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId } + entity }
    }
    override suspend fun upsertAll(entities: List<PlaybackPreferencesEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryReaderPreferencesDao : ReaderPreferencesDao {
    private val rows = MutableStateFlow<List<ReaderPreferencesEntity>>(emptyList())
    override fun observe(siteId: String, mediaId: String) =
        rows.map { it.firstOrNull { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override fun observeBySite(siteId: String) = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeAll() = rows
    override suspend fun get(siteId: String, mediaId: String) =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: ReaderPreferencesEntity) {
        rows.update { list -> list.filterNot { it.siteId == entity.siteId && it.mediaId == entity.mediaId } + entity }
    }
    override suspend fun upsertAll(entities: List<ReaderPreferencesEntity>) = entities.forEach { upsert(it) }
    override suspend fun delete(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteBySite(siteId: String) { rows.update { list -> list.filterNot { it.siteId == siteId } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryPageBookmarkDao : PageBookmarkDao {
    private val rows = MutableStateFlow<List<PageBookmarkEntity>>(emptyList())
    override fun observeAll() = rows
    override fun observeByMedia(siteId: String, mediaId: String) =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, page: Int) =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.page == page }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: PageBookmarkEntity) { rows.update { list -> list.filterNot { it.id == entity.id } + entity } }
    override suspend fun upsertAll(entities: List<PageBookmarkEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryTimeBookmarkDao : TimeBookmarkDao {
    private val rows = MutableStateFlow<List<TimeBookmarkEntity>>(emptyList())
    override fun observeAll() = rows.map { it.sortedByDescending { e -> e.createdAt } }
    override fun observeByMedia(siteId: String, mediaId: String) =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId }.sortedBy { e -> e.positionMs } }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: TimeBookmarkEntity) {
        rows.update { list -> list.filterNot { it.id == entity.id } + entity }
    }
    override suspend fun upsertAll(entities: List<TimeBookmarkEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteByMedia(siteId: String, mediaId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId && it.mediaId == mediaId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryImageFavoriteDao : ImageFavoriteDao {
    private val rows = MutableStateFlow<List<ImageFavoriteEntity>>(emptyList())
    override fun observeAll() = rows
    override fun observeBySite(siteId: String) = rows.map { it.filter { e -> e.siteId == siteId } }
    override fun observeByMedia(siteId: String, mediaId: String) =
        rows.map { it.filter { e -> e.siteId == siteId && e.mediaId == mediaId } }
    override fun observeExists(siteId: String, mediaId: String, pageIndex: Int) =
        rows.map { it.any { e -> e.siteId == siteId && e.mediaId == mediaId && e.pageIndex == pageIndex } }
    override suspend fun getByMediaAndPage(siteId: String, mediaId: String, pageIndex: Int) =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId && it.pageIndex == pageIndex }
    override suspend fun getAll() = rows.value
    override suspend fun upsert(entity: ImageFavoriteEntity) { rows.update { list -> list.filterNot { it.id == entity.id } + entity } }
    override suspend fun upsertAll(entities: List<ImageFavoriteEntity>) = entities.forEach { upsert(it) }
    override suspend fun deleteById(id: String) { rows.update { list -> list.filterNot { it.id == id } } }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun prune(limit: Int) = Unit
}

internal class InMemoryTagTranslationDao : TagTranslationDao {
    private val rows = MutableStateFlow<List<TagTranslationEntity>>(emptyList())
    override fun observeBySource(source: TagTranslationSource): Flow<List<TagTranslationEntity>> =
        rows.map { list -> list.filter { it.source == source } }
    override fun observeCountBySource(source: TagTranslationSource): Flow<Int> =
        rows.map { list -> list.count { it.source == source } }
    override fun observeAll(): Flow<List<TagTranslationEntity>> = rows
    override suspend fun getBySource(source: TagTranslationSource): List<TagTranslationEntity> =
        rows.value.filter { it.source == source }
    override suspend fun getAll(): List<TagTranslationEntity> = rows.value
    override suspend fun search(query: String, limit: Int): List<TagTranslationEntity> =
        rows.value.filter {
            it.rawSlug.contains(query, ignoreCase = true) || it.chineseName.contains(query, ignoreCase = true)
        }.take(limit)
    override suspend fun upsert(entity: TagTranslationEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<TagTranslationEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Triple<TagTranslationSource, String, String>, TagTranslationEntity>()
            current.forEach { map[Triple(it.source, it.namespace, it.rawSlug)] = it }
            entities.forEach { map[Triple(it.source, it.namespace, it.rawSlug)] = it }
            map.values.toList()
        }
    }
    override suspend fun delete(source: TagTranslationSource, namespace: String, rawSlug: String) {
        rows.update { list ->
            list.filterNot { it.source == source && it.namespace == namespace && it.rawSlug == rawSlug }
        }
    }
    override suspend fun deleteBySource(source: TagTranslationSource) {
        rows.update { list -> list.filterNot { it.source == source } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

internal class InMemoryMediaTagCacheDao : MediaTagCacheDao {
    private val rows = MutableStateFlow<List<MediaTagCacheEntity>>(emptyList())
    override fun observeAll(): Flow<List<MediaTagCacheEntity>> = rows
    override fun observeBySite(siteId: String): Flow<List<MediaTagCacheEntity>> =
        rows.map { list -> list.filter { it.siteId == siteId } }
    override suspend fun getOne(siteId: String, mediaId: String): MediaTagCacheEntity? =
        rows.value.firstOrNull { it.siteId == siteId && it.mediaId == mediaId }
    override suspend fun getBySite(siteId: String): List<MediaTagCacheEntity> =
        rows.value.filter { it.siteId == siteId }
    override suspend fun upsert(entity: MediaTagCacheEntity) = upsertAll(listOf(entity))
    override suspend fun upsertAll(entities: List<MediaTagCacheEntity>) {
        rows.update { current ->
            val map = LinkedHashMap<Pair<String, String>, MediaTagCacheEntity>()
            current.forEach { map[it.siteId to it.mediaId] = it }
            entities.forEach { map[it.siteId to it.mediaId] = it }
            map.values.sortedByDescending { it.updatedAt }
        }
    }
    override suspend fun deleteBySite(siteId: String) {
        rows.update { list -> list.filterNot { it.siteId == siteId } }
    }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun prune(limit: Int) {
        rows.update { list -> list.sortedByDescending { it.updatedAt }.take(limit) }
    }
}
