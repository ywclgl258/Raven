package com.raven.app.feature.player

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.domain.CompletionPolicy
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.model.ShelfWithSummary
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.domain.LikeRecommendationEngine
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.LikeRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.media.cast.LanCastDevice
import com.raven.app.core.media.cast.LanCastManager
import com.raven.app.core.media.download.OfflineMediaHandle
import com.raven.app.core.media.download.PlaybackQueueSession
import com.raven.app.core.media.download.PlaybackQueueStore
import com.raven.app.core.media.playback.LocalSeekPreviewDecoder
import com.raven.app.core.media.playback.PlayerEngineState
import com.raven.app.core.media.playback.PlayerSessionController
import com.raven.app.core.media.storage.LocalLibraryImporter
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.LikedMedia
import com.raven.app.core.model.LikedTagWeight
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.PlaybackPreferences
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.VideoDetail
import com.raven.app.core.model.VideoStream
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private data class PlayerLocalState(
    val isLoadingDetail: Boolean = false,
    val detail: VideoDetail? = null,
    val loadError: String? = null,
    val requiresAuth: Boolean = false,
    val siteDisplayName: String = "",
    val sessionUserSpeed: Float? = null,
    val isTemporarySpeedActive: Boolean = false,
    val temporarySpeedValue: Float = 2.0f,
    val isLocked: Boolean = false,
    val isFullscreen: Boolean = false,
    val sleepTimerDeadlineElapsedMs: Long? = null,
    val sleepTimerDurationMinutes: Int? = null,
    val sleepTimerRemainingMs: Long = 0L,
    val resumedFromMs: Long? = null,
    val hasSeekPreview: Boolean = false,
    val localNotice: String? = null,
    val isRefreshingExpiredStream: Boolean = false,
    val autoFullscreenTriggeredForKey: String? = null,
    val autoContinueDeadlineElapsedMs: Long? = null,
    val autoContinueRemainingMs: Long = 0L,
    val castDevices: List<LanCastDevice> = emptyList(),
    val isScanningCast: Boolean = false,
    val activeCastDevice: LanCastDevice? = null,
)

private data class PlayerPersonalBindings(
    val playbackPreferences: PlaybackPreferences = PlaybackPreferences(),
    val inWatchLater: Boolean = false,
    val shelves: List<ShelfWithSummary> = emptyList(),
    val containingShelfIds: Set<String> = emptySet(),
    val isFavorite: Boolean = false,
    val isLiked: Boolean = false,
    val subscribedTagSlugs: Set<String> = emptySet(),
    val likedTagWeights: List<LikedTagWeight> = emptyList(),
    val blockedTerms: Set<String> = emptySet(),
    val cachedTagsByKey: Map<String, List<TagRef>> = emptyMap(),
    val timeBookmarks: List<TimeBookmark> = emptyList(),
    val downloads: List<DownloadItem> = emptyList(),
    val dictRevision: Long = 0L,
)

/**
 * Reactive ViewModel orchestrating video detail resolution, offline/local fallback, watch-history
 * auto-resume & persistence, session speed precedence over per-site defaults, A-B loop, monotonic
 * sleep timer, stable-quality stall observation, 1-time expired stream refresh, bookmarks/tags/library
 * actions, and in-place related/sequential-queue switching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    initialArgs: PlayerLaunchArgs? = null,
    private val siteRegistry: SiteRegistry,
    private val playback: PlayerPlaybackPort,
    private val offline: PlayerOfflinePort,
    private val playbackQueueStore: PlaybackQueueStore,
    private val historyRepository: HistoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val shelfRepository: ShelfRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val blockedTermsRepository: BlockedTermsRepository,
    private val preferencesRepository: PreferencesRepository,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val tagDictionary: TagDictionary = TagDictionary.default,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val mediaTagCacheRepository: MediaTagCacheRepository? = null,
    private val tagDictionaryRepository: TagDictionaryRepository? = null,
    private val likeRepository: LikeRepository? = null,
    private val subscriptionRepository: SubscriptionRepository? = null,
    private val lanCastManager: LanCastManager? = null,
    private val applicationScope: CoroutineScope? = null,
) : ViewModel() {

    private val activeTarget = MutableStateFlow<PlayerLaunchArgs?>(null)
    private val localState = MutableStateFlow(PlayerLocalState())
    private val effectivePlaybackPrefs = MutableStateFlow(PlaybackPreferences())
    private val latestAppSettings = MutableStateFlow(AppSettings())
    private val fallbackFlushScope by lazy { CoroutineScope(SupervisorJob() + dispatchers.io) }

    private val _sideEffects = MutableSharedFlow<PlayerSideEffect>(extraBufferCapacity = 16)
    val sideEffects: SharedFlow<PlayerSideEffect> = _sideEffects.asSharedFlow()

    private val _seekPreviewFrame = MutableStateFlow<Bitmap?>(null)
    val seekPreviewFrame: StateFlow<Bitmap?> = _seekPreviewFrame.asStateFlow()

    private var loadJob: Job? = null
    private var metadataEnrichJob: Job? = null
    private var refreshStreamJob: Job? = null
    private var seekPreviewJob: Job? = null
    private var seekPreviewDecoder: LocalSeekPreviewDecoder? = null

    private var lastSavedCompositeKey: String? = null
    private var lastSavedPositionMs: Long = -1L
    private var lastSavedCompleted: Boolean = false
    private var lastHandledEndedKey: String? = null
    private var queueSwitchInFlightFromKey: String? = null
    private var backgroundSleepTimerJob: Job? = null

    private val dictRevisionFlow = tagDictionaryRepository?.observeRevision() ?: flowOf(0L)

    private val personalBindings: Flow<PlayerPersonalBindings> = activeTarget.flatMapLatest { target ->
        if (target == null) {
            flowOf(PlayerPersonalBindings())
        } else {
            val subscribedTagsFlow: Flow<Set<String>> = subscriptionRepository?.observeSubscriptions(target.siteId)
                ?.map { list -> list.mapTo(linkedSetOf()) { it.slug.trim().lowercase() } }
                ?: flowOf(emptySet())
            val isLikedFlow = likeRepository?.observeIsLiked(target.siteId, target.mediaId) ?: flowOf(false)
            val likedWeightsFlow = likeRepository?.observeTagWeights() ?: flowOf(emptyList())
            val prefsAndFlags = combine(
                preferencesRepository.observeEffectivePlaybackPreferences(target.siteId),
                shelfRepository.observeContainingShelfIds(target.siteId, target.mediaId),
                shelfRepository.observeShelvesWithSummary(),
                favoriteRepository.observeIsFavorite(target.siteId, FavoriteKind.VIDEO, target.mediaId),
                subscribedTagsFlow,
            ) { prefs, containingIds, allShelves, fav, subTags ->
                PersonalLeftSlice(prefs, containingIds.toSet(), allShelves, fav, subTags)
            }
            val cachedTagsFlow = mediaTagCacheRepository?.observeTagsBySite(target.siteId) ?: flowOf(emptyMap())
            val termsBookmarksDownloads = combine(
                blockedTermsRepository.observeEffectiveTermsBySite(target.siteId).map { it.toSet() },
                cachedTagsFlow,
                bookmarkRepository.observeTimeBookmarks(target.siteId, target.mediaId),
                offline.observeDownloadsByMedia(target.siteId, target.mediaId),
            ) { blocked, cachedTags, bookmarks, downloads ->
                Quad(blocked, cachedTags, bookmarks, downloads)
            }
            val likeAndDict = combine(isLikedFlow, likedWeightsFlow, dictRevisionFlow) { liked, weights, rev ->
                Triple(liked, weights, rev)
            }
            combine(prefsAndFlags, termsBookmarksDownloads, likeAndDict) { left, right, (liked, weights, dictRevision) ->
                PlayerPersonalBindings(
                    playbackPreferences = left.prefs,
                    inWatchLater = Shelf.WATCH_LATER_ID in left.containingShelfIds,
                    shelves = left.shelves,
                    containingShelfIds = left.containingShelfIds,
                    isFavorite = left.isFavorite,
                    isLiked = liked,
                    subscribedTagSlugs = left.favoriteTagSlugs,
                    likedTagWeights = weights,
                    blockedTerms = right.first,
                    cachedTagsByKey = right.second,
                    timeBookmarks = right.third,
                    downloads = right.fourth,
                    dictRevision = dictRevision,
                )
            }
        }
    }

    val uiState: StateFlow<PlayerUiState> = combine(
        activeTarget,
        localState,
        personalBindings,
        preferencesRepository.settings,
        combine(playback.state, playbackQueueStore.currentSession) { engine, queue -> engine to queue },
    ) { target, local, personal, appSettings, (engine, queueSession) ->
        buildUiState(
            target = target,
            local = local,
            personal = personal,
            appSettings = appSettings,
            engine = engine,
            queueSession = queueSession,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlayerUiState(),
    )

    val progressState: StateFlow<PlayerProgressSnapshot> = playback.state
        .map { engine ->
            PlayerProgressSnapshot(
                positionMs = engine.positionMs,
                bufferedPositionMs = engine.bufferedPositionMs,
                durationMs = engine.durationMs,
            )
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = PlayerProgressSnapshot(),
        )

    /**
     * Structural player state with high-frequency `positionMs` / `bufferedPositionMs` zeroed out
     * so playback ticks only update the active scrubber overlay and never recompose the screen.
     */
    val structuralUiState: StateFlow<PlayerUiState> = uiState
        .map { state ->
            state.copy(
                positionMs = 0L,
                bufferedPositionMs = 0L,
            )
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = PlayerUiState(),
        )

    init {
        observeAppSettingsSync()
        observeEffectiveSpeedSync()
        observeEngineEvents()
        if (initialArgs != null) {
            open(initialArgs)
        }
    }

    fun currentPlayer() = playback.currentPlayer()

    fun pipAspectRatioPair(): Pair<Int, Int>? = playback.pipAspectRatioPair()

    /**
     * Opens or switches to [args] in-place (zero unbounded Activity stacking).
     * Flushes watch progress for the previous video, resets per-video transient state, and preserves
     * any session-level [PlayerUiState.sessionUserSpeed] override.
     */
    fun open(args: PlayerLaunchArgs, forceReload: Boolean = false) {
        val current = activeTarget.value
        if (!forceReload && current != null && current == args && localState.value.loadError == null) {
            return
        }

        clearAutoContinueCountdown()
        val previousTarget = current
        // Switching in place must stop the old source immediately; otherwise a failed new load
        // leaves the old video audible behind an error card for a different title.
        if (previousTarget != null && previousTarget.compositeKey != args.compositeKey) {
            playback.pause()
        }
        loadJob?.cancel()
        metadataEnrichJob?.cancel()
        refreshStreamJob?.cancel()

        val pendingQueueSwitch = queueSwitchInFlightFromKey

        loadJob = viewModelScope.launch {
            if (previousTarget != null && previousTarget.compositeKey != args.compositeKey) {
                runCatchingCancellable { persistWatchProgressInternal(previousTarget, force = true) }
            }
            closeSeekPreview()
            lastSavedCompositeKey = null
            lastSavedPositionMs = -1L
            lastSavedCompleted = false
            lastHandledEndedKey = null

            val normalizedArgs = normalizeArgs(args)
            val siteDisplay = resolveSiteDisplayName(normalizedArgs.siteId)
            if (previousTarget?.siteId != normalizedArgs.siteId) {
                effectivePlaybackPrefs.value = PlaybackPreferences(siteId = normalizedArgs.siteId)
            }
            activeTarget.value = normalizedArgs
            localState.update { state ->
                state.copy(
                    isLoadingDetail = true,
                    detail = null,
                    loadError = null,
                    requiresAuth = false,
                    siteDisplayName = siteDisplay,
                    isTemporarySpeedActive = false,
                    resumedFromMs = null,
                    hasSeekPreview = false,
                    localNotice = null,
                    isRefreshingExpiredStream = false,
                    autoFullscreenTriggeredForKey = null,
                    activeCastDevice = null,
                )
            }
            if (previousTarget != null && previousTarget.compositeKey != normalizedArgs.compositeKey) {
                _sideEffects.tryEmit(PlayerSideEffect.ActiveMediaChanged(normalizedArgs))
            }
            try {
                resolveAndLoadMedia(normalizedArgs)
            } finally {
                if (queueSwitchInFlightFromKey == pendingQueueSwitch) {
                    queueSwitchInFlightFromKey = null
                }
            }
        }
    }

    fun retryLoad() {
        val target = activeTarget.value ?: return
        // The previous failure must stop looking like the current state while the retry loads.
        playback.clearPlaybackError()
        viewModelScope.launch {
            // Persist the exact failure position first; otherwise resume may fall back seconds.
            flushWatchProgress()
            open(target, forceReload = true)
        }
    }

    // ---- Foreground Lifecycle Ticker (strictly bound to repeatOnLifecycle(STARTED)) ----

    /**
     * Runs active playback polling ONLY while the calling coroutine is active (bind via
     * `repeatOnLifecycle(Lifecycle.State.STARTED)` in Compose — zero background idle polling).
     */
    suspend fun runForegroundTicker() = coroutineScope {
        // STARTED owns deadline evaluation again; the ON_STOP fallback watcher must stand down.
        onForegroundResumed()

        val loopAndPositionJob = launch {
            while (isActive) {
                val engine = playback.state.value
                if (engine.isInitialized) {
                    playback.evaluateLoopTick()
                }
                val intervalMs = if (engine.isPlaying || engine.loopRange.isActive) {
                    LOOP_TICK_MS
                } else {
                    IDLE_FOREGROUND_TICK_MS
                }
                delay(intervalMs)
            }
        }

        val stallAndSleepJob = launch {
            while (isActive) {
                delay(STALL_AND_SLEEP_TICK_MS)
                val engine = playback.state.value
                if (engine.isInitialized && !engine.isOffline && !engine.isLocal) {
                    playback.evaluateStallTick()
                }
                evaluateSleepTimerTick()
                evaluateAutoContinueTick()
            }
        }

        val progressSaveJob = launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                val target = activeTarget.value
                if (target != null && playback.state.value.isPlaying) {
                    runCatching { persistWatchProgressInternal(target, force = false) }
                }
            }
        }

        loopAndPositionJob.join()
        stallAndSleepJob.join()
        progressSaveJob.join()
    }

    /**
     * Called on Activity `ON_STOP`. Flushes watch progress immediately and pauses playback when
     * backgrounded outside Picture-in-Picture mode.
     */
    fun onBackgrounded(isInPipMode: Boolean) {
        val target = activeTarget.value
        val keepBackgroundAudio = latestAppSettings.value.backgroundAudioPlayback
        val playingWhenLeaving = playback.state.value.isPlaying
        if (!isInPipMode && !keepBackgroundAudio && playingWhenLeaving) {
            playback.pause()
        }
        // With background audio (or PiP) the foreground ticker is cancelled by ON_STOP while
        // playback keeps running, so the sleep deadline needs its own armed job.
        armBackgroundSleepTimer(playbackContinuesInBackground = isInPipMode || (keepBackgroundAudio && playingWhenLeaving))
        if (target != null) {
            (applicationScope ?: fallbackFlushScope).launch {
                runCatching { persistWatchProgressInternal(target, force = true) }
            }
        }
    }

    /**
     * Called whenever the foreground ticker (re)starts, i.e. the screen is back in `STARTED`.
     * The foreground `stallAndSleepJob` owns deadline evaluation from here on, so the background
     * fallback must be disarmed to avoid two competing evaluators.
     */
    fun onForegroundResumed() {
        backgroundSleepTimerJob?.cancel()
        backgroundSleepTimerJob = null
    }

    // ---- Transport Controls ----

    fun play() {
        if (playback.state.value.isEnded) clearAutoContinueCountdown()
        playback.play()
    }

    fun pause() {
        playback.pause()
        val target = activeTarget.value ?: return
        (applicationScope ?: fallbackFlushScope).launch {
            runCatching { persistWatchProgressInternal(target, force = true) }
        }
    }

    fun togglePlayPause() {
        if (playback.state.value.isEnded) clearAutoContinueCountdown()
        val wasPlaying = playback.state.value.isPlaying
        playback.togglePlayPause()
        if (wasPlaying) {
            val target = activeTarget.value ?: return
            (applicationScope ?: fallbackFlushScope).launch {
                runCatching { persistWatchProgressInternal(target, force = true) }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        if (playback.state.value.isEnded) clearAutoContinueCountdown()
        val hadActiveLoop = playback.state.value.loopRange.isActive
        playback.seekTo(positionMs)
        if (hadActiveLoop && !playback.state.value.loopRange.isActive) {
            postNotice("已跳出循环区间，A-B 循环已自动清除")
        }
    }

    fun seekBy(deltaMs: Long) {
        val hadActiveLoop = playback.state.value.loopRange.isActive
        playback.seekBy(deltaMs)
        if (hadActiveLoop && !playback.state.value.loopRange.isActive) {
            postNotice("已跳出循环区间，A-B 循环已自动清除")
        }
    }

    fun seekByConfiguredStep(forward: Boolean) {
        val stepSeconds = effectivePlaybackPrefs.value.seekStepSeconds.coerceIn(1, 120)
        val deltaMs = stepSeconds * 1_000L * if (forward) 1L else -1L
        seekBy(deltaMs)
    }

    // ---- Speed Precedence (Session User Speed > Per-Site Default Speed) ----

    fun setSessionSpeed(speed: Float) {
        val clamped = speed.coerceIn(PlayerSessionController.MIN_SPEED, PlayerSessionController.MAX_SPEED)
        localState.update {
            it.copy(
                sessionUserSpeed = clamped,
                isTemporarySpeedActive = false,
            )
        }
        playback.setPlaybackSpeed(clamped)
    }

    fun resetSessionSpeedToDefault() {
        val fallback = effectivePlaybackPrefs.value.defaultSpeed
            .coerceIn(PlayerSessionController.MIN_SPEED, PlayerSessionController.MAX_SPEED)
        localState.update {
            it.copy(
                sessionUserSpeed = null,
                isTemporarySpeedActive = false,
            )
        }
        playback.setPlaybackSpeed(fallback)
    }

    fun startTemporarySpeed() {
        val tempSpeed = effectivePlaybackPrefs.value.longPressSpeed
            .coerceIn(PlayerSessionController.MIN_SPEED, PlayerSessionController.MAX_SPEED)
        localState.update {
            it.copy(
                isTemporarySpeedActive = true,
                temporarySpeedValue = tempSpeed,
            )
        }
        playback.setPlaybackSpeed(tempSpeed)
    }

    fun stopTemporarySpeed() {
        val local = localState.value
        if (!local.isTemporarySpeedActive) return
        val restored = (local.sessionUserSpeed ?: effectivePlaybackPrefs.value.defaultSpeed)
            .coerceIn(PlayerSessionController.MIN_SPEED, PlayerSessionController.MAX_SPEED)
        localState.update { it.copy(isTemporarySpeedActive = false) }
        playback.setPlaybackSpeed(restored)
    }

    // ---- Stream Selection ----

    fun selectStream(stream: VideoStream, saveAsSiteDefault: Boolean = false) {
        val current = playback.state.value.currentStream
        if (current?.url == stream.url && current.quality == stream.quality) return
        val label = stream.label.ifBlank { stream.quality }
        playback.switchStream(stream, preservePosition = true, notice = "已切换至 $label")
        if (saveAsSiteDefault) {
            val qualityKey = stream.quality.ifBlank { stream.label }
            updateSitePlaybackPreferences { it.copy(preferredQuality = qualityKey) }
        }
    }

    fun selectAudioTrack(groupIndex: Int, trackIndex: Int) {
        playback.selectAudioTrack(groupIndex, trackIndex)
        postNotice("已切换音轨")
    }

    fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
        playback.selectSubtitleTrack(groupIndex, trackIndex)
        postNotice("已开启字幕")
    }

    fun disableSubtitles() {
        playback.disableSubtitles()
        postNotice("已关闭字幕")
    }

    // ---- A-B Loop ----

    fun setLoopA(positionMs: Long = currentPlaybackPositionMs()) {
        val updated = playback.setLoopStart(positionMs)
        postNotice("已设 A 点 ${RavenFormatters.formatDurationMs(updated.startMs)}")
    }

    fun setLoopB(positionMs: Long = currentPlaybackPositionMs()): Boolean {
        return try {
            val updated = playback.setLoopEnd(positionMs)
            postNotice(
                "A-B 循环已开启 (${RavenFormatters.formatDurationMs(updated.startMs)} - " +
                    "${RavenFormatters.formatDurationMs(updated.endMs)})",
            )
            true
        } catch (error: IllegalArgumentException) {
            val msg = error.message ?: "B 点需要比 A 点至少晚 1 秒"
            postNotice(msg)
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage(msg))
            false
        }
    }

    fun clearLoop() {
        if (!playback.state.value.loopRange.hasStart) return
        playback.clearLoop()
        postNotice("已清除 A-B 循环")
    }

    fun jumpToLoopStart() {
        val startMs = playback.state.value.loopRange.startMs
        if (startMs >= 0L) {
            playback.seekTo(startMs)
        }
    }

    // ---- Sleep Timer (monotonic elapsedRealtime()) ----

    fun setSleepTimerMinutes(minutes: Int): Boolean {
        if (minutes !in MIN_SLEEP_MINUTES..MAX_SLEEP_MINUTES) {
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("睡眠定时支持 1 至 180 分钟"))
            return false
        }
        val durationMs = minutes * 60_000L
        val deadline = elapsedRealtimeMs() + durationMs
        localState.update {
            it.copy(
                sleepTimerDeadlineElapsedMs = deadline,
                sleepTimerDurationMinutes = minutes,
                sleepTimerRemainingMs = durationMs,
            )
        }
        postNotice("已设置 ${minutes} 分钟后暂停播放")
        return true
    }

    fun cancelSleepTimer() {
        if (localState.value.sleepTimerDeadlineElapsedMs == null) return
        backgroundSleepTimerJob?.cancel()
        backgroundSleepTimerJob = null
        localState.update {
            it.copy(
                sleepTimerDeadlineElapsedMs = null,
                sleepTimerDurationMinutes = null,
                sleepTimerRemainingMs = 0L,
            )
        }
        postNotice("已取消睡眠定时")
    }

    /**
     * Arms a background-resilient deadline watcher.
     *
     * The foreground `stallAndSleepJob` is bound to `repeatOnLifecycle(STARTED)` and therefore
     * stops the moment the player is backgrounded. With background audio (or PiP) playback keeps
     * running, so without this watcher the deadline could pass entirely unnoticed until the user
     * returns to the screen. Uses the same injectable monotonic clock as [evaluateSleepTimerTick]
     * and is disarmed by [onForegroundResumed] to avoid two competing evaluators.
     */
    private fun armBackgroundSleepTimer(playbackContinuesInBackground: Boolean) {
        backgroundSleepTimerJob?.cancel()
        backgroundSleepTimerJob = null
        if (!playbackContinuesInBackground) return
        if (localState.value.sleepTimerDeadlineElapsedMs == null) return
        backgroundSleepTimerJob = (applicationScope ?: fallbackFlushScope).launch {
            while (isActive) {
                val deadline = localState.value.sleepTimerDeadlineElapsedMs ?: break
                val remaining = deadline - elapsedRealtimeMs()
                if (remaining <= 0L) {
                    // `evaluateSleepTimerTick` pauses ExoPlayer and starts a persist launch, both
                    // of which must run on the application thread. Re-read the clock inside the hop
                    // so the expiry decision is not based on a timestamp captured before dispatch.
                    withContext(dispatchers.main) { evaluateSleepTimerTick(elapsedRealtimeMs()) }
                    break
                }
                delay(remaining.coerceAtMost(BACKGROUND_SLEEP_TIMER_POLL_MS))
            }
        }
    }

    /**
     * Evaluates the monotonic sleep timer against [nowElapsedMs].
     * Returns `true` when the timer expired and paused playback.
     */
    fun evaluateSleepTimerTick(nowElapsedMs: Long = elapsedRealtimeMs()): Boolean {
        val deadline = localState.value.sleepTimerDeadlineElapsedMs ?: return false
        val remaining = deadline - nowElapsedMs
        return if (remaining <= 0L) {
            localState.update {
                it.copy(
                    sleepTimerDeadlineElapsedMs = null,
                    sleepTimerDurationMinutes = null,
                    sleepTimerRemainingMs = 0L,
                )
            }
            playback.pause()
            val target = activeTarget.value
            if (target != null) {
                viewModelScope.launch {
                    runCatching { persistWatchProgressInternal(target, force = true) }
                }
            }
            val msg = "睡眠定时已到，已暂停播放"
            postNotice(msg)
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage(msg))
            true
        } else {
            if (localState.value.sleepTimerRemainingMs != remaining) {
                localState.update { it.copy(sleepTimerRemainingMs = remaining) }
            }
            false
        }
    }

    // ---- Lock & Fullscreen State ----

    fun setLocked(locked: Boolean) {
        localState.update { it.copy(isLocked = locked) }
    }

    fun toggleLock() {
        localState.update { it.copy(isLocked = !it.isLocked) }
    }

    fun setFullscreenState(fullscreen: Boolean) {
        localState.update { it.copy(isFullscreen = fullscreen) }
    }

    fun clearNotice() {
        localState.update { it.copy(localNotice = null) }
        playback.clearNotice()
    }

    // ---- Seek Preview Decoding (Local & Complete Offline MP4) ----

    fun requestSeekPreviewFrame(positionMs: Long) {
        val decoder = seekPreviewDecoder ?: return
        val durationMs = currentEffectiveDurationMs()
        seekPreviewJob?.cancel()
        seekPreviewJob = viewModelScope.launch {
            val bitmap = runCatching {
                decoder.frameAt(
                    positionMs = positionMs,
                    durationMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE,
                )
            }.getOrNull()
            _seekPreviewFrame.value = bitmap
        }
    }

    fun clearSeekPreviewFrame() {
        seekPreviewJob?.cancel()
        seekPreviewJob = null
        _seekPreviewFrame.value = null
    }

    // ---- Personal Library Actions: Watch Later, Favorite, Download ----

    fun toggleWatchLater() {
        val item = currentMediaItem() ?: return
        viewModelScope.launch {
            val added = shelfRepository.toggleItem(
                shelfId = Shelf.WATCH_LATER_ID,
                item = ShelfItem(
                    shelfId = Shelf.WATCH_LATER_ID,
                    siteId = item.siteId,
                    mediaId = item.id,
                    url = item.url,
                    kind = MediaKind.VIDEO,
                    title = item.title,
                    coverUrl = item.coverUrl,
                    durationSeconds = item.durationSeconds,
                    addedAt = clockMs(),
                ),
            )
            _sideEffects.tryEmit(
                PlayerSideEffect.ShowMessage(if (added) "已加入稍后看" else "已移出稍后看"),
            )
        }
    }

    fun setShelfMembership(shelfId: String, add: Boolean) {
        val item = currentMediaItem() ?: return
        viewModelScope.launch(dispatchers.io) {
            if (add) {
                shelfRepository.addItem(
                    shelfId = shelfId,
                    item = ShelfItem(
                        shelfId = shelfId,
                        siteId = item.siteId,
                        mediaId = item.id,
                        url = item.url,
                        kind = MediaKind.VIDEO,
                        title = item.title,
                        coverUrl = item.coverUrl,
                        durationSeconds = item.durationSeconds,
                        addedAt = clockMs(),
                    ),
                )
            } else {
                shelfRepository.removeItem(shelfId, item.siteId, item.id)
            }
        }
    }

    fun createShelfAndAdd(name: String) {
        val trimmed = name.trim()
        val item = currentMediaItem() ?: return
        if (trimmed.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            val created = shelfRepository.createShelf(name = trimmed)
            shelfRepository.addItem(
                shelfId = created.id,
                item = ShelfItem(
                    shelfId = created.id,
                    siteId = item.siteId,
                    mediaId = item.id,
                    url = item.url,
                    kind = MediaKind.VIDEO,
                    title = item.title,
                    coverUrl = item.coverUrl,
                    durationSeconds = item.durationSeconds,
                    addedAt = clockMs(),
                ),
            )
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("已加入片单「${created.name}」"))
        }
    }

    fun discoverCastDevices() {
        val manager = lanCastManager ?: return
        if (localState.value.isScanningCast) return
        viewModelScope.launch {
            localState.update { it.copy(isScanningCast = true) }
            try {
                val found = manager.discoverDevices()
                localState.update { it.copy(castDevices = found, isScanningCast = false) }
                if (found.isEmpty()) {
                    _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("局域网内未发现 DLNA 投屏设备"))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                localState.update { it.copy(isScanningCast = false) }
                _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("扫描投屏设备失败"))
            }
        }
    }

    fun castCurrentVideo(device: LanCastDevice) {
        val manager = lanCastManager ?: return
        val streamUrl = playback.state.value.currentStream?.url?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: activeTarget.value?.url?.takeIf { it.startsWith("http", ignoreCase = true) }
        if (streamUrl == null) {
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("当前仅支持对在线直链或局域网视频发起 DLNA 投屏"))
            return
        }
        val title = uiState.value.title.ifBlank { "Raven Video" }
        viewModelScope.launch {
            val result = runCatching {
                manager.castVideo(device = device, streamUrl = streamUrl, title = title)
            }
            if (result.isSuccess) {
                playback.pause()
                localState.update { it.copy(activeCastDevice = device) }
                _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("已投屏至 ${device.friendlyName}"))
            } else {
                _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("投屏至 ${device.friendlyName} 失败"))
            }
        }
    }

    fun stopCast() {
        val manager = lanCastManager ?: return
        val device = localState.value.activeCastDevice ?: return
        viewModelScope.launch {
            manager.stopCast(device)
            localState.update { it.copy(activeCastDevice = null) }
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("已断开投屏"))
        }
    }

    fun launchSystemExternalPlayer(): Boolean {
        val manager = lanCastManager ?: return false
        val streamUrl = playback.state.value.currentStream?.url?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: activeTarget.value?.url?.takeIf { it.isNotBlank() }
            ?: return false
        return manager.launchExternalPlayer(streamUrl)
    }

    fun toggleFavorite() {
        val item = currentMediaItem() ?: return
        viewModelScope.launch {
            val added = favoriteRepository.toggle(
                FavoriteItem(
                    siteId = item.siteId,
                    targetId = item.id,
                    kind = FavoriteKind.VIDEO,
                    title = item.title,
                    url = item.url,
                    coverUrl = item.coverUrl,
                    addedAt = clockMs(),
                ),
            )
            _sideEffects.tryEmit(
                PlayerSideEffect.ShowMessage(if (added) "已收藏视频" else "已取消收藏"),
            )
        }
    }

    fun enqueueDownload(stream: VideoStream? = null, groupName: String = "") {
        val item = currentMediaItem() ?: return
        if (item.siteId.isLocal) {
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("本地视频已在设备中"))
            return
        }
        viewModelScope.launch {
            runCatching {
                val chosen = stream
                    ?: localState.value.detail?.downloadStreams?.firstOrNull()
                    ?: playback.state.value.currentStream
                    ?: localState.value.detail?.streams?.firstOrNull()
                if (chosen != null) {
                    offline.enqueueVideo(item, chosen, groupName = groupName.trim())
                } else {
                    offline.enqueueVideoWithPreferredQuality(item, groupName = groupName.trim())
                }
            }.onSuccess {
                _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("已加入下载队列"))
            }.onFailure { error ->
                _sideEffects.tryEmit(
                    PlayerSideEffect.ShowMessage(error.message ?: "无法加入下载队列"),
                )
            }
        }
    }

    // ---- Time Bookmarks CRUD ----

    fun addTimeBookmark(
        label: String,
        groupName: String = "",
        positionMs: Long = currentPlaybackPositionMs(),
    ) {
        val item = currentMediaItem() ?: return
        val durationMs = currentEffectiveDurationMs()
        val cleanLabel = label.trim().take(BookmarkRepository.MAX_TIME_BOOKMARK_LABEL_LENGTH)
        val cleanGroup = groupName.trim().take(BookmarkRepository.MAX_TIME_BOOKMARK_GROUP_LENGTH)
        viewModelScope.launch {
            bookmarkRepository.saveTimeBookmark(
                TimeBookmark(
                    id = "",
                    siteId = item.siteId,
                    mediaId = item.id,
                    url = item.url,
                    title = item.title,
                    coverUrl = item.coverUrl,
                    positionMs = positionMs.coerceAtLeast(0L),
                    durationMs = durationMs,
                    label = cleanLabel,
                    groupName = cleanGroup,
                    createdAt = clockMs(),
                ),
            )
            _sideEffects.tryEmit(
                PlayerSideEffect.ShowMessage(
                    "已添加书签 ${RavenFormatters.formatDurationMs(positionMs.coerceAtLeast(0L))}",
                ),
            )
        }
    }

    fun updateTimeBookmark(
        bookmark: TimeBookmark,
        label: String,
        groupName: String,
    ) {
        val cleanLabel = label.trim().take(BookmarkRepository.MAX_TIME_BOOKMARK_LABEL_LENGTH)
        val cleanGroup = groupName.trim().take(BookmarkRepository.MAX_TIME_BOOKMARK_GROUP_LENGTH)
        viewModelScope.launch {
            bookmarkRepository.saveTimeBookmark(
                bookmark.copy(
                    label = cleanLabel,
                    groupName = cleanGroup,
                ),
            )
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("书签已更新"))
        }
    }

    fun deleteTimeBookmark(bookmarkId: String) {
        viewModelScope.launch {
            bookmarkRepository.deleteTimeBookmark(bookmarkId)
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("书签已删除"))
        }
    }

    fun jumpToTimeBookmark(bookmark: TimeBookmark) {
        seekTo(bookmark.positionMs)
    }

    // ---- Local Like & Tag Subscription / Block ----

    fun toggleLike() {
        val repo = likeRepository ?: return
        val target = activeTarget.value ?: return
        val detail = localState.value.detail
        val item = detail?.item
        val title = item?.title?.takeIf { it.isNotBlank() } ?: target.title.ifBlank { target.mediaId }
        val url = item?.url?.takeIf { it.isNotBlank() } ?: target.url
        val cover = item?.coverUrl?.takeIf { it.isNotBlank() } ?: target.coverUrl
        val authorName = (detail?.authors?.firstOrNull() ?: item?.author)?.name.orEmpty()
        val allTags = buildList {
            detail?.tags?.let(::addAll)
            detail?.characters?.let(::addAll)
            detail?.categories?.let(::addAll)
            item?.tags?.let(::addAll)
        }.distinctBy { LikeRecommendationEngine.canonicalTagKey(it) }

        viewModelScope.launch {
            val liked = repo.toggle(
                LikedMedia(
                    siteId = target.siteId,
                    mediaId = target.mediaId,
                    kind = MediaKind.VIDEO,
                    title = title,
                    url = url,
                    coverUrl = cover,
                    authorName = authorName,
                    tags = allTags,
                    likedAt = clockMs(),
                ),
            )
            _sideEffects.tryEmit(
                PlayerSideEffect.ShowMessage(
                    if (liked) {
                        if (allTags.isNotEmpty()) "已点赞 · 已记录 ${allTags.size} 个标签偏好" else "已点赞"
                    } else {
                        "已取消点赞"
                    },
                ),
            )
        }
    }

    fun toggleTagSubscription(tag: TagRef) {
        val repo = subscriptionRepository ?: return
        val siteId = activeTarget.value?.siteId ?: return
        val slug = tag.slug.trim().ifBlank { tag.label.trim() }
        if (slug.isEmpty()) return
        val translated = tagDictionary.translate(tag.slug, tag.namespace)
            ?: tagDictionary.translate(tag.label, tag.namespace)
        val label = translated ?: tag.label.ifBlank { slug }
        viewModelScope.launch {
            val subscribed = repo.toggleSubscription(
                TagSubscription(
                    siteId = siteId,
                    slug = slug,
                    label = label,
                    url = tag.url,
                    scope = SearchScope.TAG,
                    createdAt = clockMs(),
                ),
            )
            _sideEffects.tryEmit(
                PlayerSideEffect.ShowMessage(
                    if (subscribed) "已订阅标签「$label」" else "已取消订阅标签「$label」",
                ),
            )
        }
    }

    fun toggleTagFavorite(tag: TagRef) {
        toggleTagSubscription(tag)
    }

    fun toggleTagBlocked(tag: TagRef) {
        val siteId = activeTarget.value?.siteId ?: return
        val term = tag.slug.trim().ifBlank { tag.label.trim() }
        if (term.isEmpty()) return
        viewModelScope.launch {
            val settings = preferencesRepository.currentSettings()
            val primaryScope = if (settings.blockTagsGlobally) SiteId.GLOBAL else siteId
            val normalized = ContentFilter.normalizeTerm(term)
            val globalTerms = blockedTermsRepository.observeGlobalTerms().first()
            val siteTerms = blockedTermsRepository.observeTermsBySite(siteId).first()
            val matchingGlobal = globalTerms.filter { isMatchingTermForTag(tag, it) }
            val matchingSite = siteTerms.filter { isMatchingTermForTag(tag, it) }
            val nowBlocked = if (matchingGlobal.isNotEmpty() || matchingSite.isNotEmpty()) {
                matchingGlobal.forEach { blockedTermsRepository.removeTerm(SiteId.GLOBAL, it) }
                matchingSite.forEach { blockedTermsRepository.removeTerm(siteId, it) }
                false
            } else {
                blockedTermsRepository.addTerm(primaryScope, normalized)
                true
            }
            val scopePrefix = if (settings.blockTagsGlobally) "已全局屏蔽标签" else "已在当前站点屏蔽标签"
            _sideEffects.tryEmit(
                PlayerSideEffect.ShowMessage(if (nowBlocked) "$scopePrefix：$term" else "已取消屏蔽：$term"),
            )
        }
    }

    fun saveCustomTagTranslation(tag: TagRef, chineseName: String) {
        val repo = tagDictionaryRepository ?: return
        val slug = tag.slug.trim().ifBlank { tag.label.trim() }
        val cleanZh = chineseName.trim()
        if (slug.isEmpty() || cleanZh.isEmpty()) {
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("请输入有效的中文译名"))
            return
        }
        viewModelScope.launch(dispatchers.io) {
            val saved = repo.upsertCustomTranslation(
                rawSlug = slug,
                chineseName = cleanZh,
                namespace = tag.namespace,
            )
            if (saved) {
                repo.hydrateRuntimeDictionary()
                _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("已更新中文译名：$cleanZh"))
            }
        }
    }

    // ---- Per-Site Playback Preferences ----

    fun updateDefaultSpeedPreference(speed: Float) {
        val snapped = snapFloatToStep(speed, 0.25f, AppSettings.MIN_PLAYBACK_SPEED, AppSettings.MAX_PLAYBACK_SPEED)
        updateSitePlaybackPreferences { it.copy(defaultSpeed = snapped) }
    }

    fun updateLongPressSpeedPreference(speed: Float) {
        val snapped = snapFloatToStep(speed, 0.5f, 1.5f, PlayerSessionController.MAX_SPEED)
        updateSitePlaybackPreferences { it.copy(longPressSpeed = snapped) }
    }

    fun updateSeekStepSecondsPreference(seconds: Int) {
        val clamped = seconds.coerceIn(5, 60)
        updateSitePlaybackPreferences { it.copy(seekStepSeconds = clamped) }
    }

    fun updateGestureSensitivitySecondsPreference(seconds: Int) {
        val clamped = seconds.coerceIn(30, 180)
        updateSitePlaybackPreferences { it.copy(gestureSensitivitySeconds = clamped) }
    }

    fun updateAutoFullscreenPreference(enabled: Boolean) {
        updateSitePlaybackPreferences { it.copy(autoFullscreen = enabled) }
    }

    fun resetSitePlaybackPreferences() {
        val siteId = activeTarget.value?.siteId ?: return
        viewModelScope.launch {
            preferencesRepository.resetSitePlaybackPreferences(siteId)
            _sideEffects.tryEmit(PlayerSideEffect.ShowMessage("已恢复该站点默认播放设置"))
        }
    }

    // ---- In-Place Related Video & Sequential Queue Switching ----

    fun selectRelatedVideo(item: MediaItem) {
        if (!item.kind.isVideo) return
        val nextArgs = PlayerLaunchArgs.fromMediaItem(
            item = item,
            autoplay = true,
            offlineOnly = false,
            queueToken = null,
        )
        open(nextArgs, forceReload = true)
    }

    /** Persists the global auto-continue preference (off by default). */
    fun updateAutoContinuePreference(enabled: Boolean) {
        latestAppSettings.update { it.copy(autoContinueQueue = enabled) }
        if (enabled) {
            maybeStartAutoContinueCountdown()
        } else {
            clearAutoContinueCountdown()
        }
        viewModelScope.launch {
            preferencesRepository.setAutoContinueQueue(enabled)
        }
    }

    /**
     * Selects any history/upcoming queue entry without stacking Activities. The queue index changes
     * atomically before [open] flushes the old watch progress and resolves the new media in-place.
     */
    fun selectQueueItem(index: Int): Boolean {
        val target = activeTarget.value ?: return false
        val token = target.queueToken ?: return false
        if (queueSwitchInFlightFromKey != null) return false
        val previous = playbackQueueStore.sessionFor(token, target.siteId, target.mediaId) ?: return false
        if (index !in previous.items.indices || index == previous.currentIndex) return false

        clearAutoContinueCountdown()
        val selected = playbackQueueStore.select(token, target.siteId, target.mediaId, index) ?: return false
        return openSelectedQueueItem(previous, target, selected)
    }

    /**
     * Removes an entry with stable token semantics. Removing the current entry opens its successor
     * (or predecessor when it was last) through the same in-place flow; removing the sole entry ends
     * the queue while leaving the current media playable.
     */
    fun removeQueueItem(index: Int): Boolean {
        val target = activeTarget.value ?: return false
        val token = target.queueToken ?: return false
        if (queueSwitchInFlightFromKey != null) return false
        val previous = playbackQueueStore.sessionFor(token, target.siteId, target.mediaId) ?: return false
        if (index !in previous.items.indices) return false
        val removesCurrent = index == previous.currentIndex
        if (removesCurrent) clearAutoContinueCountdown()

        val updated = playbackQueueStore.removeAt(token, index)
        if (updated == null) {
            return previous.items.size == 1 && playbackQueueStore.snapshot() == null
        }
        if (!removesCurrent) {
            if (!updated.hasNext) clearAutoContinueCountdown()
            return true
        }
        return openSelectedQueueItem(previous, target, updated)
    }

    fun confirmAdvanceQueue(): Boolean {
        val target = activeTarget.value ?: return false
        val token = target.queueToken ?: return false
        val current = playbackQueueStore.sessionFor(token, target.siteId, target.mediaId) ?: return false
        if (!current.hasNext) return false
        return selectQueueItem(current.currentIndex + 1)
    }

    private fun openSelectedQueueItem(
        previous: PlaybackQueueSession,
        target: PlayerLaunchArgs,
        selected: PlaybackQueueSession,
    ): Boolean {
        val item = selected.currentItem ?: run {
            playbackQueueStore.restore(previous, selected)
            return false
        }
        queueSwitchInFlightFromKey = target.compositeKey
        if (item.kind.isVideo) {
            val args = PlayerLaunchArgs.fromMediaItem(
                item = item,
                autoplay = true,
                offlineOnly = selected.offlineOnly,
                queueToken = selected.token,
            )
            open(args, forceReload = true)
        } else {
            viewModelScope.launch {
                runCatching { persistWatchProgressInternal(target, force = true) }
            }
            _sideEffects.tryEmit(
                PlayerSideEffect.OpenGalleryFromQueue(
                    item = item,
                    offlineOnly = selected.offlineOnly,
                    queueToken = selected.token,
                ),
            )
            queueSwitchInFlightFromKey = null
        }
        return true
    }

    fun cancelAutoContinueCountdown() {
        if (clearAutoContinueCountdown()) postNotice("已取消自动连播")
    }

    private fun clearAutoContinueCountdown(): Boolean {
        val local = localState.value
        if (local.autoContinueDeadlineElapsedMs == null && local.autoContinueRemainingMs == 0L) return false
        localState.update {
            it.copy(autoContinueDeadlineElapsedMs = null, autoContinueRemainingMs = 0L)
        }
        return true
    }

    private fun maybeStartAutoContinueCountdown() {
        if (!latestAppSettings.value.autoContinueQueue || queueSwitchInFlightFromKey != null) return
        if (localState.value.autoContinueDeadlineElapsedMs != null) return
        val target = activeTarget.value ?: return
        val engine = playback.state.value
        if (!engine.isEnded || engine.siteId != target.siteId || engine.mediaId != target.mediaId) return
        val session = playbackQueueStore.sessionFor(target.queueToken, target.siteId, target.mediaId) ?: return
        if (!session.hasNext) return
        localState.update {
            it.copy(
                autoContinueDeadlineElapsedMs = elapsedRealtimeMs() + AUTO_CONTINUE_COUNTDOWN_MS,
                autoContinueRemainingMs = AUTO_CONTINUE_COUNTDOWN_MS,
            )
        }
    }

    /** Evaluated only by [runForegroundTicker]; no background countdown coroutine is created. */
    fun evaluateAutoContinueTick(nowElapsedMs: Long = elapsedRealtimeMs()): Boolean {
        val deadline = localState.value.autoContinueDeadlineElapsedMs ?: return false
        val target = activeTarget.value
        val engine = playback.state.value
        val session = target?.let {
            playbackQueueStore.sessionFor(it.queueToken, it.siteId, it.mediaId)
        }
        if (!latestAppSettings.value.autoContinueQueue ||
            target == null ||
            !engine.isEnded ||
            engine.siteId != target.siteId ||
            engine.mediaId != target.mediaId ||
            session?.hasNext != true
        ) {
            clearAutoContinueCountdown()
            return false
        }

        val remaining = deadline - nowElapsedMs
        if (remaining > 0L) {
            if (localState.value.autoContinueRemainingMs != remaining) {
                localState.update { it.copy(autoContinueRemainingMs = remaining) }
            }
            return false
        }

        // Clear before switching so repeated ticks cannot advance the queue twice.
        clearAutoContinueCountdown()
        return confirmAdvanceQueue()
    }

    fun endQueue() {
        clearAutoContinueCountdown()
        val target = activeTarget.value ?: return
        val token = target.queueToken ?: return
        playbackQueueStore.endIfCurrent(token, target.siteId, target.mediaId)
    }

    /**
     * Explicit progress flush helper (e.g., before closing the player or in unit tests).
     */
    suspend fun flushWatchProgress() {
        val target = activeTarget.value ?: return
        persistWatchProgressInternal(target, force = true)
    }

    // ---- Internal Orchestration ----

    private fun observeAppSettingsSync() {
        viewModelScope.launch {
            preferencesRepository.settings.collect { settings ->
                latestAppSettings.value = settings
            }
        }
    }

    private fun observeEffectiveSpeedSync() {
        viewModelScope.launch {
            val prefsFlow = activeTarget.flatMapLatest { target ->
                if (target == null) {
                    flowOf<Pair<SiteId?, PlaybackPreferences>>(null to PlaybackPreferences())
                } else {
                    preferencesRepository.observeEffectivePlaybackPreferences(target.siteId)
                        .map { prefs -> target.siteId to prefs }
                }
            }
            val controlFlow = localState.map {
                Triple(it.sessionUserSpeed, it.isTemporarySpeedActive, it.temporarySpeedValue)
            }.distinctUntilChanged()

            combine(prefsFlow, controlFlow) { (siteId, prefs), (sessionSpeed, isTemp, tempSpeed) ->
                if (siteId == null || siteId == activeTarget.value?.siteId) {
                    effectivePlaybackPrefs.value = prefs
                }
                val raw = when {
                    isTemp -> tempSpeed
                    sessionSpeed != null -> sessionSpeed
                    else -> prefs.defaultSpeed
                }
                raw.coerceIn(PlayerSessionController.MIN_SPEED, PlayerSessionController.MAX_SPEED)
            }.distinctUntilChanged().collect { desiredSpeed ->
                if (abs(playback.state.value.playbackSpeed - desiredSpeed) > 0.001f) {
                    playback.setPlaybackSpeed(desiredSpeed)
                }
            }
        }
    }

    private fun observeEngineEvents() {
        viewModelScope.launch {
            playback.state.collect { engine ->
                val target = activeTarget.value ?: return@collect

                // 1. 1-time automatic expired stream URL refresh on online playback errors
                if (engine.errorMessage != null &&
                    !engine.isOffline &&
                    !engine.isLocal &&
                    !engine.hasRefreshedExpiredStream &&
                    !localState.value.isRefreshingExpiredStream
                ) {
                    triggerExpiredStreamRefresh(target)
                }

                // 2. Persist completed state cleanly when playback reaches STATE_ENDED
                if (engine.isEnded &&
                    engine.siteId == target.siteId &&
                    engine.mediaId == target.mediaId &&
                    lastHandledEndedKey != target.compositeKey
                ) {
                    lastHandledEndedKey = target.compositeKey
                    runCatchingCancellable { persistWatchProgressInternal(target, force = true) }
                    maybeStartAutoContinueCountdown()
                }

                // 3. One-shot auto-fullscreen matching video aspect ratio (never on autoplay)
                val local = localState.value
                val startedByAutoplay = target.autoplay && latestAppSettings.value.autoplayVideo
                if (!startedByAutoplay &&
                    engine.isInitialized &&
                    engine.isPlaying &&
                    engine.videoWidth > 0 &&
                    engine.videoHeight > 0 &&
                    engine.siteId == target.siteId &&
                    engine.mediaId == target.mediaId &&
                    !local.isFullscreen &&
                    local.autoFullscreenTriggeredForKey != target.compositeKey
                ) {
                    val prefs = effectivePlaybackPrefs.value
                    if (prefs.autoFullscreen) {
                        localState.update { it.copy(autoFullscreenTriggeredForKey = target.compositeKey) }
                        _sideEffects.tryEmit(
                            PlayerSideEffect.RequestAutoFullscreen(
                                landscape = engine.videoWidth >= engine.videoHeight,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun resolveAndLoadMedia(args: PlayerLaunchArgs) {
        val savedProgress = runCatchingCancellable {
            historyRepository.getWatchProgress(args.siteId, args.mediaId)
        }.getOrNull()
        val resumePositionMs = if (savedProgress != null && CompletionPolicy.isWatchResumable(savedProgress)) {
            savedProgress.positionMs
        } else {
            0L
        }

        val appSettings = runCatchingCancellable { preferencesRepository.currentSettings() }.getOrDefault(AppSettings())
        latestAppSettings.value = appSettings
        val effectiveArgs = args.copy(autoplay = args.autoplay && appSettings.autoplayVideo)
        val effectivePrefs = runCatchingCancellable {
            preferencesRepository.observeEffectivePlaybackPreferences(args.siteId).first()
        }.getOrDefault(
            PlaybackPreferences(
                siteId = args.siteId,
                defaultSpeed = appSettings.defaultPlaybackSpeed,
                preferredQuality = appSettings.preferredQuality,
                autoFullscreen = appSettings.autoFullscreen,
            ),
        )
        effectivePlaybackPrefs.value = effectivePrefs

        val initialSpeed = (localState.value.sessionUserSpeed ?: effectivePrefs.defaultSpeed)
            .coerceIn(PlayerSessionController.MIN_SPEED, PlayerSessionController.MAX_SPEED)
        playback.setPlaybackSpeed(initialSpeed)

        // Branch 1: Locally imported video (`SiteId.LOCAL` or `ravenlocal://` / `r34local://`)
        val parsedLocalId = LocalLibraryImporter.parseLocalBookId(args.url)
        if (args.siteId.isLocal || parsedLocalId != null) {
            val bookId = parsedLocalId ?: args.mediaId
            loadLocalVideo(effectiveArgs, bookId, resumePositionMs)
            return
        }

        // Branch 2: Check offline downloaded video handle first
        val offlineHandle = runCatchingCancellable {
            offline.findOfflineHandle(args.siteId, args.mediaId, MediaKind.VIDEO)
        }.getOrNull()

        when (offlineHandle) {
            is OfflineMediaHandle.LocalVideo -> {
                loadLocalVideo(effectiveArgs, offlineHandle.bookId, resumePositionMs, explicitPath = offlineHandle.filePath)
                return
            }
            is OfflineMediaHandle.OfflineVideo -> {
                loadOfflineVideo(effectiveArgs, offlineHandle, resumePositionMs)
                return
            }
            else -> {
                if (args.offlineOnly) {
                    localState.update {
                        it.copy(
                            isLoadingDetail = false,
                            loadError = "该视频尚未完成离线下载，无法在纯离线模式下播放",
                        )
                    }
                    return
                }
            }
        }

        // Branch 3: Online video detail + stream playback
        val plugin = siteRegistry.get(args.siteId)
        val videoSource = plugin?.videoSource
        if (videoSource == null) {
            localState.update {
                it.copy(
                    isLoadingDetail = false,
                    loadError = "当前站点暂不支持视频播放",
                )
            }
            return
        }

        val detail = try {
            withContext(dispatchers.io) {
                videoSource.loadVideoDetail(args.url)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (auth: SiteException.AuthRequired) {
            localState.update {
                it.copy(
                    isLoadingDetail = false,
                    requiresAuth = true,
                    loadError = auth.message.ifBlank { "该视频需要登录后观看" },
                )
            }
            return
        } catch (error: Throwable) {
            localState.update {
                it.copy(
                    isLoadingDetail = false,
                    loadError = error.message?.takeIf { it.isNotBlank() } ?: "加载视频详情失败",
                )
            }
            return
        }

        currentCoroutineContext().ensureActive()
        val resolvedMediaId = detail.item.id.ifBlank { args.mediaId }
        val resolvedUrl = detail.item.url.ifBlank { args.url }
        var effectiveResumeMs = resumePositionMs
        if (resolvedMediaId != args.mediaId && savedProgress == null) {
            val resolvedProgress = runCatchingCancellable {
                historyRepository.getWatchProgress(args.siteId, resolvedMediaId)
            }.getOrNull()
            if (resolvedProgress != null && CompletionPolicy.isWatchResumable(resolvedProgress)) {
                effectiveResumeMs = resolvedProgress.positionMs
            }
        }
        val normalizedTarget = if (resolvedMediaId != args.mediaId || resolvedUrl != args.url) {
            effectiveArgs.copy(mediaId = resolvedMediaId, url = resolvedUrl)
        } else {
            effectiveArgs
        }
        if (normalizedTarget != activeTarget.value) {
            activeTarget.value = normalizedTarget
            _sideEffects.tryEmit(PlayerSideEffect.ActiveMediaChanged(normalizedTarget))
        }

        cacheDetailTags(args.siteId, resolvedMediaId, detail)
        val resumeNotice = if (effectiveResumeMs > 0L) {
            "已从 ${RavenFormatters.formatDurationMs(effectiveResumeMs)} 续播"
        } else {
            null
        }

        localState.update {
            it.copy(
                isLoadingDetail = false,
                detail = detail,
                loadError = null,
                requiresAuth = false,
                resumedFromMs = effectiveResumeMs.takeIf { pos -> pos > 0L },
                localNotice = resumeNotice,
            )
        }

        if (detail.streams.isEmpty()) {
            if (detail.externalPlayers.isEmpty()) {
                localState.update { it.copy(loadError = "未找到可播放的视频流") }
            } else {
                postNotice("当前视频仅支持站外播放器打开")
            }
            return
        }

        val preferredQuality = effectivePrefs.preferredQuality.ifBlank { appSettings.preferredQuality }
        try {
            currentCoroutineContext().ensureActive()
            playback.loadOnline(
                siteId = normalizedTarget.siteId,
                mediaId = resolvedMediaId,
                sourceUrl = resolvedUrl,
                streams = detail.streams,
                preferredQuality = preferredQuality,
                startPositionMs = effectiveResumeMs,
                autoPlay = normalizedTarget.autoplay,
                streamCacheEnabled = appSettings.streamCacheEnabled,
                seekCacheEnabled = appSettings.seekCacheEnabled,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            localState.update {
                it.copy(loadError = error.message?.takeIf { it.isNotBlank() } ?: "视频初始化失败")
            }
        }
    }

    private suspend fun loadLocalVideo(
        args: PlayerLaunchArgs,
        bookId: String,
        resumePositionMs: Long,
        explicitPath: String? = null,
    ) {
        val filePath = explicitPath ?: offline.getLocalVideoFile(bookId)?.absolutePath
        if (filePath.isNullOrBlank()) {
            localState.update {
                it.copy(
                    isLoadingDetail = false,
                    loadError = "本地视频文件不存在或已被移除",
                )
            }
            return
        }

        val localBook = offline.getLocalBook(bookId)
        val item = localBook?.let(offline::localBookToMediaItem) ?: MediaItem(
            siteId = SiteId.LOCAL,
            id = bookId,
            url = args.url.ifBlank { LocalLibraryImporter.buildLocalUri(bookId) },
            kind = MediaKind.VIDEO,
            title = args.title.ifBlank { "本地视频" },
            coverUrl = args.coverUrl,
        )
        val syntheticDetail = VideoDetail(item = item)

        try {
            playback.loadLocalFile(
                siteId = SiteId.LOCAL,
                mediaId = bookId,
                filePath = filePath,
                startPositionMs = resumePositionMs,
                autoPlay = args.autoplay,
            )
            attachSeekPreviewDecoder()
            localState.update {
                it.copy(
                    isLoadingDetail = false,
                    detail = syntheticDetail,
                    loadError = null,
                    resumedFromMs = resumePositionMs.takeIf { pos -> pos > 0L },
                    localNotice = if (resumePositionMs > 0L) {
                        "已从 ${RavenFormatters.formatDurationMs(resumePositionMs)} 续播"
                    } else {
                        null
                    },
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            localState.update {
                it.copy(
                    isLoadingDetail = false,
                    loadError = error.message?.takeIf { it.isNotBlank() } ?: "无法打开本地视频文件",
                )
            }
        }
    }

    private suspend fun loadOfflineVideo(
        args: PlayerLaunchArgs,
        handle: OfflineMediaHandle.OfflineVideo,
        resumePositionMs: Long,
    ) {
        val placeholderItem = MediaItem(
            siteId = args.siteId,
            id = args.mediaId,
            url = args.url,
            kind = MediaKind.VIDEO,
            title = args.title.ifBlank { args.mediaId },
            coverUrl = args.coverUrl,
        )
        try {
            playback.loadOffline(
                siteId = args.siteId,
                mediaId = args.mediaId,
                sourceUrl = args.url,
                cacheKey = handle.cacheKey,
                sourceRoot = handle.sourceRoot,
                qualityLabel = handle.quality,
                startPositionMs = resumePositionMs,
                autoPlay = args.autoplay,
            )
            attachSeekPreviewDecoder()
            localState.update {
                it.copy(
                    isLoadingDetail = false,
                    detail = VideoDetail(item = placeholderItem),
                    loadError = null,
                    resumedFromMs = resumePositionMs.takeIf { pos -> pos > 0L },
                    localNotice = if (resumePositionMs > 0L) {
                        "离线播放 · 已从 ${RavenFormatters.formatDurationMs(resumePositionMs)} 续播"
                    } else {
                        "正在使用离线缓存播放"
                    },
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            localState.update {
                it.copy(
                    isLoadingDetail = false,
                    loadError = error.message?.takeIf { it.isNotBlank() } ?: "离线视频加载失败",
                )
            }
            return
        }

        // Best-effort metadata enrichment when online mode is allowed (never interrupts offline playback)
        if (!args.offlineOnly) {
            val videoSource = siteRegistry.get(args.siteId)?.videoSource ?: return
            metadataEnrichJob = viewModelScope.launch {
                val enriched = runCatchingCancellable {
                    withContext(dispatchers.io) { videoSource.loadVideoDetail(args.url) }
                }.getOrNull() ?: return@launch
                if (activeTarget.value?.compositeKey == args.compositeKey) {
                    localState.update { it.copy(detail = enriched) }
                    cacheDetailTags(args.siteId, enriched.item.id.ifBlank { args.mediaId }, enriched)
                }
            }
        }
    }

    private fun triggerExpiredStreamRefresh(target: PlayerLaunchArgs) {
        val videoSource = siteRegistry.get(target.siteId)?.videoSource ?: return
        localState.update { it.copy(isRefreshingExpiredStream = true) }
        refreshStreamJob?.cancel()
        refreshStreamJob = viewModelScope.launch {
            try {
                var refreshedStreams: List<VideoStream> = emptyList()
                val refreshed = playback.refreshExpiredStream {
                    withContext(dispatchers.io) {
                        videoSource.refreshStreams(target.url).also { refreshedStreams = it }
                    }
                }
                if (refreshed && refreshedStreams.isNotEmpty() && activeTarget.value?.compositeKey == target.compositeKey) {
                    localState.update { current ->
                        current.copy(
                            detail = current.detail?.copy(streams = refreshedStreams),
                            localNotice = "播放地址已自动刷新",
                        )
                    }
                }
            } finally {
                localState.update { it.copy(isRefreshingExpiredStream = false) }
            }
        }
    }

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private fun buildWatchProgressSnapshot(target: PlayerLaunchArgs, force: Boolean): WatchProgress? {
        val engine = playback.state.value
        if (!engine.isInitialized) return null
        if (engine.siteId != target.siteId || engine.mediaId != target.mediaId) return null

        val detail = localState.value.detail
        val rawPos = readPlayerPositionMs()
        val durationMs = engine.durationMs.takeIf { it > 0L }
            ?: detail?.item?.durationSeconds?.takeIf { it > 0 }?.let { it * 1_000L }
            ?: 0L
        val completed = engine.isEnded
        val effectivePos = if (completed && durationMs > 0L) durationMs else rawPos

        if (!force && !completed && effectivePos <= 0L) return null
        if (!force &&
            !completed &&
            lastSavedCompositeKey == target.compositeKey &&
            lastSavedCompleted == completed &&
            abs(effectivePos - lastSavedPositionMs) < PROGRESS_SAVE_DELTA_MS
        ) {
            return null
        }

        val title = detail?.item?.title?.takeIf { it.isNotBlank() }
            ?: target.title.ifBlank { target.mediaId }
        val coverUrl = detail?.item?.coverUrl?.takeIf { it.isNotBlank() } ?: target.coverUrl
        val url = detail?.item?.url?.takeIf { it.isNotBlank() } ?: target.url

        return WatchProgress(
            siteId = target.siteId,
            mediaId = target.mediaId,
            url = url,
            title = title,
            coverUrl = coverUrl,
            positionMs = effectivePos,
            durationMs = durationMs,
            updatedAt = clockMs(),
            completed = completed,
            quality = engine.currentStream?.quality?.ifBlank { engine.currentStream?.label },
        )
    }

    private suspend fun persistWatchProgressInternal(target: PlayerLaunchArgs, force: Boolean) {
        val incognito = latestAppSettings.value.incognitoMode ||
            runCatching { preferencesRepository.currentSettings().incognitoMode }.getOrDefault(false)
        if (incognito) return

        // ExoPlayer only allows position reads from its application thread (main looper). Every
        // flush here can arrive from `applicationScope` / the fallback IO scope (pause, ON_STOP,
        // sleep expiry), so the snapshot is always built on main first.
        val snapshot = withContext(dispatchers.main) {
            buildWatchProgressSnapshot(target, force)
        } ?: return
        withContext(NonCancellable) {
            withContext(dispatchers.io) {
                historyRepository.saveWatchProgress(snapshot)
            }
        }
        withContext(dispatchers.main) {
            lastSavedCompositeKey = target.compositeKey
            lastSavedPositionMs = snapshot.positionMs
            lastSavedCompleted = snapshot.completed
        }
    }

    /**
     * Authoritative player position for UI actions that capture a timestamp (time bookmark,
     * scrubbing start, A-B loop label). [progressState] is a `WhileSubscribed` flow: once the
     * controls overlay leaves composition its value freezes, so it must never be used to stamp UI
     * state. Callers are Compose click handlers, i.e. already on the application thread.
     */
    fun currentPlaybackPositionMs(): Long = readPlayerPositionMs()

    /**
     * Reads the live position, falling back to the last engine snapshot when the direct read is
     * unavailable (player released / not yet attached). Returning a stale-but-real position beats
     * dropping the save entirely, which is what the previous swallowed-exception path did.
     */
    private fun readPlayerPositionMs(): Long {
        val direct = runCatching { playback.currentPositionMs() }.getOrNull()
        val fallback = playback.state.value.positionMs
        return (direct ?: fallback).coerceAtLeast(0L)
    }

    private fun updateSitePlaybackPreferences(transform: (PlaybackPreferences) -> PlaybackPreferences) {
        val siteId = activeTarget.value?.siteId ?: return
        val current = effectivePlaybackPrefs.value.copy(siteId = siteId)
        val updated = transform(current).copy(siteId = siteId)
        effectivePlaybackPrefs.value = updated
        viewModelScope.launch {
            preferencesRepository.savePlaybackPreferences(updated)
        }
    }

    private fun currentEffectiveDurationMs(): Long =
        playback.state.value.durationMs.takeIf { it > 0L }
            ?: localState.value.detail?.item?.durationSeconds?.takeIf { it > 0 }?.let { it * 1_000L }
            ?: 0L

    private fun attachSeekPreviewDecoder() {
        val decoder = runCatching { playback.openSeekPreview() }.getOrNull()
        seekPreviewDecoder = decoder
        localState.update { it.copy(hasSeekPreview = decoder != null) }
    }

    private suspend fun closeSeekPreview() {
        clearSeekPreviewFrame()
        val existing = seekPreviewDecoder
        seekPreviewDecoder = null
        localState.update { it.copy(hasSeekPreview = false) }
        if (existing != null) {
            withContext(NonCancellable) {
                runCatching { existing.close() }
            }
        }
    }

    private fun normalizeArgs(args: PlayerLaunchArgs): PlayerLaunchArgs {
        val parsedLocalId = LocalLibraryImporter.parseLocalBookId(args.url)
        return if (parsedLocalId != null) {
            args.copy(siteId = SiteId.LOCAL, mediaId = parsedLocalId)
        } else {
            args
        }
    }

    private fun resolveSiteDisplayName(siteId: SiteId): String = when {
        siteId.isLocal -> "本地内容"
        else -> siteRegistry.get(siteId)?.descriptor?.displayName ?: siteId.value
    }

    private fun currentMediaItem(): MediaItem? {
        val target = activeTarget.value ?: return null
        val detailItem = localState.value.detail?.item
        return detailItem ?: MediaItem(
            siteId = target.siteId,
            id = target.mediaId,
            url = target.url,
            kind = MediaKind.VIDEO,
            title = target.title.ifBlank { target.mediaId },
            coverUrl = target.coverUrl,
        )
    }

    private fun cacheDetailTags(siteId: SiteId, mediaId: String, detail: VideoDetail) {
        val repo = mediaTagCacheRepository ?: return
        if (siteId.isLocal || mediaId.isBlank()) return
        val allTags = detail.tags + detail.characters + detail.categories + detail.item.tags
        if (allTags.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            runCatching { repo.upsertTags(siteId, mediaId, allTags) }
        }
    }

    private fun isMatchingTermForTag(tag: TagRef, blockedTerm: String): Boolean {
        val slugOrLabel = tag.slug.ifBlank { tag.label }
        val tagCandidates = buildSet {
            val norm = ContentFilter.normalizeTerm(slugOrLabel)
            if (norm.isNotEmpty()) add(norm)
            val bare = ContentFilter.canonicalizeForMatching(slugOrLabel)
            if (bare.isNotEmpty()) add(bare)
            addAll(tagDictionary.expandSynonyms(slugOrLabel))
            if (tag.label.isNotBlank() && tag.label != slugOrLabel) {
                addAll(tagDictionary.expandSynonyms(tag.label))
            }
        }
        val bNorm = ContentFilter.normalizeTerm(blockedTerm)
        val bBare = ContentFilter.canonicalizeForMatching(blockedTerm)
        if (bNorm in tagCandidates || bBare in tagCandidates) return true
        return tagDictionary.expandSynonyms(blockedTerm).any { it in tagCandidates }
    }

    private fun postNotice(message: String) {
        localState.update { it.copy(localNotice = message) }
    }

    private fun buildUiState(
        target: PlayerLaunchArgs?,
        local: PlayerLocalState,
        personal: PlayerPersonalBindings,
        appSettings: AppSettings,
        engine: PlayerEngineState,
        queueSession: PlaybackQueueSession?,
    ): PlayerUiState {
        val detail = local.detail
        val detailItem = detail?.item
        val title = detailItem?.title?.takeIf { it.isNotBlank() }
            ?: target?.title?.takeIf { it.isNotBlank() }
            ?: target?.mediaId.orEmpty()
        val coverUrl = detailItem?.coverUrl?.takeIf { it.isNotBlank() }
            ?: target?.coverUrl.orEmpty()
        val url = detailItem?.url?.takeIf { it.isNotBlank() }
            ?: target?.url.orEmpty()

        val activePrefs = personal.playbackPreferences
        val effectiveSpeed = (local.sessionUserSpeed ?: activePrefs.defaultSpeed)
            .coerceIn(PlayerSessionController.MIN_SPEED, PlayerSessionController.MAX_SPEED)

        val authors: List<AuthorRef> = buildList {
            detail?.authors?.forEach { add(it) }
            detailItem?.author?.let { single ->
                if (none { it.id == single.id && it.name == single.name }) add(single)
            }
        }

        val mapTags: (List<TagRef>) -> List<PlayerTagDisplay> = { source ->
            source.map { tag ->
                val slug = tag.slug.trim().lowercase()
                val translated = if (appSettings.translateTags) {
                    tagDictionary.translate(tag.slug, tag.namespace)
                        ?: tagDictionary.translate(tag.label, tag.namespace)
                } else {
                    null
                }
                val fallback = tag.label.ifBlank { tag.slug }
                PlayerTagDisplay(
                    tag = tag,
                    displayLabel = translated ?: fallback,
                    translatedLabel = translated,
                    isSubscribed = slug in personal.subscribedTagSlugs,
                    isBlocked = personal.blockedTerms.any { isMatchingTermForTag(tag, it) },
                )
            }
        }

        val primaryDownload = personal.downloads
            .sortedByDescending { it.updatedAt }
            .firstOrNull { it.status != DownloadStatus.CANCELLED }
        val isDownloaded = personal.downloads.any { it.status == DownloadStatus.COMPLETED }

        val activeQueue = if (target != null && !target.queueToken.isNullOrBlank()) {
            val matched = queueSession?.takeIf { session ->
                session.token == target.queueToken &&
                    session.currentItem?.siteId == target.siteId &&
                    session.currentItem?.id == target.mediaId
            }
            matched?.let { session ->
                PlayerQueueUiState(
                    token = session.token,
                    title = session.title,
                    currentIndex = session.currentIndex,
                    items = session.items,
                    totalCount = session.items.size,
                    nextItem = session.nextItem,
                    remainingCount = session.remainingCount,
                    offlineOnly = session.offlineOnly,
                    autoContinueEnabled = appSettings.autoContinueQueue,
                    autoContinueCountdownActive = local.autoContinueDeadlineElapsedMs != null,
                    autoContinueRemainingMs = local.autoContinueRemainingMs,
                )
            }
        } else {
            null
        }

        val allowedRelated = detail?.related.orEmpty().filter { related ->
            ContentFilter.isAllowed(
                item = related,
                blockedTerms = personal.blockedTerms,
                tagDictionary = tagDictionary,
                extraTags = personal.cachedTagsByKey[related.compositeKey].orEmpty(),
            )
        }
        val filteredRelated = if (detail != null && allowedRelated.size > 1) {
            LikeRecommendationEngine.rankSimilarWorks(
                sourceTitle = title,
                sourceAuthors = authors.map { it.name },
                sourceTags = detail.tags + detail.characters + detail.categories + detail.item.tags,
                sourceCompositeKey = detail.item.compositeKey,
                sourceMediaId = detail.item.id,
                candidates = allowedRelated,
                likedTagWeights = personal.likedTagWeights,
                cachedTagsByKey = personal.cachedTagsByKey,
                limit = 20,
            )
        } else {
            allowedRelated
        }

        val combinedNotice = engine.lastNotice ?: local.localNotice

        return PlayerUiState(
            launchArgs = target,
            siteId = target?.siteId,
            siteDisplayName = local.siteDisplayName,
            mediaId = target?.mediaId.orEmpty(),
            url = url,
            title = title,
            coverUrl = coverUrl,
            isLoadingDetail = local.isLoadingDetail,
            detail = detail,
            loadError = local.loadError,
            requiresAuth = local.requiresAuth,
            isInitialized = engine.isInitialized,
            isPlaying = engine.isPlaying,
            isBuffering = engine.isBuffering,
            isEnded = engine.isEnded,
            positionMs = engine.positionMs,
            bufferedPositionMs = engine.bufferedPositionMs,
            durationMs = engine.durationMs,
            videoWidth = engine.videoWidth,
            videoHeight = engine.videoHeight,
            isVideoFrameReady = engine.isVideoFrameReady,
            videoRecoveryGeneration = engine.videoRecoveryGeneration,
            currentStream = engine.currentStream,
            availableStreams = engine.availableStreams.ifEmpty { detail?.streams.orEmpty() },
            isOffline = engine.isOffline,
            isLocal = engine.isLocal,
            hasSeekPreview = local.hasSeekPreview,
            playbackError = engine.errorMessage,
            isRefreshingExpiredStream = local.isRefreshingExpiredStream,
            hasRefreshedExpiredStream = engine.hasRefreshedExpiredStream,
            autoDowngradeCount = engine.autoDowngradeCount,
            playbackPreferences = activePrefs,
            sessionUserSpeed = local.sessionUserSpeed,
            effectivePlaybackSpeed = effectiveSpeed,
            isTemporarySpeedActive = local.isTemporarySpeedActive,
            temporarySpeedValue = local.temporarySpeedValue,
            translateTags = appSettings.translateTags,
            blockTagsGlobally = appSettings.blockTagsGlobally,
            reduceTransparency = appSettings.reduceTransparency,
            backgroundAudioPlayback = appSettings.backgroundAudioPlayback,
            audioTracks = engine.audioTracks,
            subtitleTracks = engine.subtitleTracks,
            subtitlesEnabled = engine.subtitlesEnabled,
            shelves = personal.shelves,
            containingShelfIds = personal.containingShelfIds,
            castDevices = local.castDevices,
            isScanningCast = local.isScanningCast,
            activeCastDevice = local.activeCastDevice,
            loopRange = engine.loopRange,
            sleepTimer = PlayerSleepTimerState(
                isActive = local.sleepTimerDeadlineElapsedMs != null,
                durationMinutes = local.sleepTimerDurationMinutes ?: 0,
                remainingMs = local.sleepTimerRemainingMs,
            ),
            isLocked = local.isLocked,
            isFullscreen = local.isFullscreen,
            resumedFromMs = local.resumedFromMs,
            noticeMessage = combinedNotice,
            inWatchLater = personal.inWatchLater,
            isFavorite = personal.isFavorite,
            isLiked = personal.isLiked,
            downloadStatus = primaryDownload?.status,
            downloadProgress = primaryDownload?.progressFraction ?: 0f,
            isOfflineDownloaded = isDownloaded || engine.isOffline,
            authors = authors,
            tags = mapTags(detail?.tags.orEmpty()),
            characters = mapTags(detail?.characters.orEmpty()),
            categories = mapTags(detail?.categories.orEmpty()),
            comments = detail?.comments.orEmpty(),
            timeBookmarks = personal.timeBookmarks,
            relatedItems = filteredRelated,
            externalPlayers = detail?.externalPlayers.orEmpty(),
            queue = activeQueue,
        )
    }

    override fun onCleared() {
        val target = activeTarget.value
        val snapshot = if (target != null && !latestAppSettings.value.incognitoMode) {
            buildWatchProgressSnapshot(target, force = true)
        } else {
            null
        }
        loadJob?.cancel()
        metadataEnrichJob?.cancel()
        refreshStreamJob?.cancel()
        seekPreviewJob?.cancel()
        if (snapshot != null) {
            (applicationScope ?: fallbackFlushScope).launch {
                withContext(NonCancellable) {
                    // The StateFlow default is `false`, so always confirm the persisted setting
                    // before writing final progress for an incognito session.
                    val incognito = latestAppSettings.value.incognitoMode ||
                        runCatching { preferencesRepository.currentSettings().incognitoMode }
                            .getOrDefault(false)
                    if (!incognito) {
                        runCatching { historyRepository.saveWatchProgress(snapshot) }
                    }
                }
            }
        }
        val decoder = seekPreviewDecoder
        seekPreviewDecoder = null
        if (decoder != null) {
            // Release decoder resources asynchronously via NonCancellable or clean shutdown
            (applicationScope ?: fallbackFlushScope).launch {
                withContext(NonCancellable) {
                    runCatching { decoder.close() }
                }
            }
        }
        playback.release()
        super.onCleared()
    }

    companion object {
        const val LOOP_TICK_MS: Long = 150L
        const val IDLE_FOREGROUND_TICK_MS: Long = 500L
        const val STALL_AND_SLEEP_TICK_MS: Long = 500L
        const val PROGRESS_SAVE_INTERVAL_MS: Long = 5_000L
        const val AUTO_CONTINUE_COUNTDOWN_MS: Long = 5_000L
        const val PROGRESS_SAVE_DELTA_MS: Long = 2_000L
        /**
         * Upper bound for a single background sleep-timer wait. Chunking the wait keeps the
         * deadline re-checked against the injectable clock so a cancelled/replaced timer can never
         * leave a stray pause behind; the exclusive end condition still fires exactly on deadline.
         */
        const val BACKGROUND_SLEEP_TIMER_POLL_MS: Long = 15_000L
        const val MIN_SLEEP_MINUTES: Int = 1
        const val MAX_SLEEP_MINUTES: Int = 180
        val SLEEP_TIMER_PRESETS_MINUTES: List<Int> = listOf(5, 15, 30, 60)
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    }
}

private data class PersonalLeftSlice(
    val prefs: PlaybackPreferences,
    val containingShelfIds: Set<String>,
    val shelves: List<ShelfWithSummary>,
    val isFavorite: Boolean,
    val favoriteTagSlugs: Set<String>,
)

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
