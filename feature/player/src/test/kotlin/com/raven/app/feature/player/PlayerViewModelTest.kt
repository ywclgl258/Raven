package com.raven.app.feature.player

import app.cash.turbine.test
import com.raven.app.core.media.download.OfflineMediaHandle
import com.raven.app.core.media.playback.VideoLoopRange
import com.raven.app.core.model.PlaybackPreferences
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.VideoDetail
import com.raven.app.core.model.VideoStream
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.sitespi.SiteRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun buildRegistry(
        r34vDetailHandler: (suspend (String) -> VideoDetail)? = null,
        r34vRefreshHandler: (suspend (String) -> List<VideoStream>)? = null,
        hanimeDetailHandler: (suspend (String) -> VideoDetail)? = null,
    ): Pair<SiteRegistry, FakeVideoSitePlugin> {
        val defaultR34v: suspend (String) -> VideoDetail = { url ->
            val id = url.substringAfterLast('/')
            VideoDetail(
                item = sampleVideoItem(
                    siteId = SiteId.R34V,
                    id = id,
                    title = "Video $id",
                    tags = listOf(
                        TagRef(slug = "blonde", label = "blonde"),
                        TagRef(slug = "3d", label = "3d"),
                    ),
                ),
                description = "Highlights at 01:30 and 02:45",
                streams = sampleStreams(id),
                tags = listOf(
                    TagRef(slug = "blonde", label = "blonde"),
                    TagRef(slug = "spoiler", label = "spoiler"),
                ),
                related = listOf(
                    sampleVideoItem(
                        siteId = SiteId.R34V,
                        id = "rel-clean",
                        title = "Clean Related",
                        tags = listOf(TagRef(slug = "3d", label = "3d")),
                    ),
                    sampleVideoItem(
                        siteId = SiteId.R34V,
                        id = "rel-spoiler",
                        title = "Spoiler Related",
                        tags = listOf(TagRef(slug = "spoiler", label = "spoiler")),
                    ),
                ),
            )
        }
        val r34vPlugin = FakeVideoSitePlugin(
            descriptor = sampleVideoDescriptor(SiteId.R34V, "Rule34Video"),
            detailHandler = r34vDetailHandler ?: defaultR34v,
            refreshHandler = r34vRefreshHandler,
        )
        val hanimePlugin = FakeVideoSitePlugin(
            descriptor = sampleVideoDescriptor(SiteId.HANIME1, "Hanime1"),
            detailHandler = hanimeDetailHandler ?: { url ->
                val id = url.substringAfterLast('/')
                VideoDetail(
                    item = sampleVideoItem(SiteId.HANIME1, id, title = "Hanime $id"),
                    streams = sampleStreams("hanime_$id"),
                )
            },
        )
        return SiteRegistry(listOf(r34vPlugin, hanimePlugin)) to r34vPlugin
    }

    @Test
    fun loadsVideoDetailAndAutoResumesFromResumableWatchProgress() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()

        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "v100",
                url = "https://r34v.example/video/v100",
                title = "Video v100",
                positionMs = 45_000L,
                durationMs = 300_000L,
                updatedAt = harness.nowMs,
            ),
        )

        val args = PlayerLaunchArgs(
            siteId = SiteId.R34V,
            mediaId = "v100",
            url = "https://r34v.example/video/v100",
            title = "Video v100",
        )
        val vm = harness.createViewModel(registry, initialArgs = args)

        vm.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertFalse(state.isLoadingDetail)
            assertNull(state.loadError)
            assertEquals("Video v100", state.title)
            assertEquals("Rule34Video", state.siteDisplayName)
            assertEquals(45_000L, state.resumedFromMs)
            assertEquals(45_000L, state.positionMs)
            // TagDictionary translated "blonde" -> "金发"
            assertEquals("金发", state.tags.first { it.tag.slug == "blonde" }.displayLabel)
            assertEquals(1, harness.playbackPort.onlineLoads.size)
            assertEquals(45_000L, harness.playbackPort.onlineLoads.single().startPositionMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun completedWatchProgressRestartsFromZero() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()

        harness.historyRepository.saveWatchProgress(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "v101",
                url = "https://r34v.example/video/v101",
                title = "Completed Clip",
                positionMs = 290_000L,
                durationMs = 300_000L,
                updatedAt = harness.nowMs,
            ),
        )

        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v101", "https://r34v.example/video/v101"),
        )
        advanceUntilIdle()

        assertEquals(0L, harness.playbackPort.onlineLoads.single().startPositionMs)
        vm.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals("v101", state.mediaId)
            assertNull(state.resumedFromMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun offlineHandleLoadsOfflineAndOfflineOnlyBlocksNetworkFallback() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, plugin) = buildRegistry()

        harness.offlinePort.handles["r34v:v-off"] = OfflineMediaHandle.OfflineVideo(
            cacheKey = "r34v:v-off:720p",
            sourceRoot = "/storage/raven",
            quality = "720p",
        )

        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(
                siteId = SiteId.R34V,
                mediaId = "v-off",
                url = "https://r34v.example/video/v-off",
                title = "Offline Cached Video",
                offlineOnly = true,
            ),
        )
        advanceUntilIdle()

        assertEquals(1, harness.playbackPort.offlineLoads.size)
        assertEquals(0, harness.playbackPort.onlineLoads.size)
        assertTrue(plugin.detailRequests.isEmpty())

        // Now request an uncached video with offlineOnly = true -> must error without hitting network
        vm.open(
            PlayerLaunchArgs(
                siteId = SiteId.R34V,
                mediaId = "v-missing",
                url = "https://r34v.example/video/v-missing",
                offlineOnly = true,
            ),
        )
        advanceUntilIdle()

        assertTrue(plugin.detailRequests.isEmpty())
        vm.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            val error = requireNotNull(state.loadError)
            assertTrue(error.contains("离线"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sessionUserSpeedTakesPrecedenceOverSiteDefaultAndRestoresAfterLongPress() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()

        harness.preferencesRepository.savePlaybackPreferences(
            PlaybackPreferences(
                siteId = SiteId.R34V,
                defaultSpeed = 1.25f,
                longPressSpeed = 2.5f,
            ),
        )
        harness.preferencesRepository.savePlaybackPreferences(
            PlaybackPreferences(
                siteId = SiteId.HANIME1,
                defaultSpeed = 1.0f,
                longPressSpeed = 2.0f,
            ),
        )

        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v1", "https://r34v.example/video/v1"),
        )
        advanceUntilIdle()

        // Initially inherits per-site default speed (1.25x)
        assertEquals(1.25f, harness.playbackPort.state.value.playbackSpeed, 0.001f)

        // User explicitly selects session speed 1.75x
        vm.setSessionSpeed(1.75f)
        advanceUntilIdle()
        assertEquals(1.75f, harness.playbackPort.state.value.playbackSpeed, 0.001f)

        // Updating site default speed must NOT override explicit session userSpeed
        harness.preferencesRepository.savePlaybackPreferences(
            PlaybackPreferences(siteId = SiteId.R34V, defaultSpeed = 1.5f, longPressSpeed = 2.5f),
        )
        advanceUntilIdle()
        assertEquals(1.75f, harness.playbackPort.state.value.playbackSpeed, 0.001f)

        // Long press activates temporary longPressSpeed (2.5x); release restores session userSpeed (1.75x)
        vm.startTemporarySpeed()
        assertEquals(2.5f, harness.playbackPort.state.value.playbackSpeed, 0.001f)
        vm.stopTemporarySpeed()
        assertEquals(1.75f, harness.playbackPort.state.value.playbackSpeed, 0.001f)

        // In-place switch to another video on another site still preserves session userSpeed (1.75x)
        vm.selectRelatedVideo(sampleVideoItem(SiteId.HANIME1, "h1", title = "Hanime 1"))
        advanceUntilIdle()
        assertEquals(1.75f, harness.playbackPort.state.value.playbackSpeed, 0.001f)

        // Explicit reset drops back to active site's default speed (1.0x for HANIME1)
        vm.resetSessionSpeedToDefault()
        advanceUntilIdle()
        assertEquals(1.0f, harness.playbackPort.state.value.playbackSpeed, 0.001f)
    }

    @Test
    fun abLoopValidatesBoundsRepeatsOnTickAndAutoClearsOnSeekOutsideRange() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()
        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v-loop", "https://r34v.example/video/v-loop"),
        )
        advanceUntilIdle()

        // Set A = 20s
        harness.playbackPort.simulatePosition(20_000L)
        vm.setLoopA()
        assertEquals(20_000L, harness.playbackPort.state.value.loopRange.startMs)
        assertFalse(harness.playbackPort.state.value.loopRange.isActive)

        // Invalid B (< 1s after A) is rejected cleanly
        harness.playbackPort.simulatePosition(20_500L)
        assertFalse(vm.setLoopB())
        assertFalse(harness.playbackPort.state.value.loopRange.isActive)

        // Valid B = 40s activates loop
        harness.playbackPort.simulatePosition(40_000L)
        assertTrue(vm.setLoopB())
        assertTrue(harness.playbackPort.state.value.loopRange.isActive)

        // Reaching B while playing jumps back to A on loop tick
        harness.playbackPort.simulatePosition(40_200L)
        assertTrue(harness.playbackPort.evaluateLoopTick())
        assertEquals(20_000L, harness.playbackPort.currentPositionMs())

        // Manual seek inside [20s, 40s) keeps loop active
        vm.seekTo(30_000L)
        assertTrue(harness.playbackPort.state.value.loopRange.isActive)

        // Manual seek outside [20s, 40s) clears A-B loop automatically
        vm.seekTo(55_000L)
        assertEquals(VideoLoopRange.NONE, harness.playbackPort.state.value.loopRange)
    }

    @Test
    fun sleepTimerSupportsPresetsCustomDurationCountdownAndAutoPauseViaElapsedRealtime() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher, elapsedMs = 100_000L)
        val (registry, _) = buildRegistry()
        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v-sleep", "https://r34v.example/video/v-sleep"),
        )
        advanceUntilIdle()
        assertTrue(harness.playbackPort.state.value.isPlaying)

        // Reject out-of-range durations
        assertFalse(vm.setSleepTimerMinutes(0))
        assertFalse(vm.setSleepTimerMinutes(181))

        // Set custom 2-minute sleep timer
        assertTrue(vm.setSleepTimerMinutes(2))
        harness.elapsedMs += 60_000L
        assertFalse(vm.evaluateSleepTimerTick())
        assertTrue(harness.playbackPort.state.value.isPlaying)

        vm.uiState.test {
            advanceUntilIdle()
            val midState = expectMostRecentItem()
            assertTrue(midState.sleepTimer.isActive)
            assertEquals(60_000L, midState.sleepTimer.remainingMs)
            assertEquals("01:00", midState.sleepTimer.formattedRemaining)
            cancelAndIgnoreRemainingEvents()
        }

        // Advance monotonic clock past deadline -> pauses playback & clears sleep timer
        vm.sideEffects.test {
            harness.playbackPort.simulatePosition(50_000L)
            harness.elapsedMs += 60_000L
            assertTrue(vm.evaluateSleepTimerTick())
            advanceUntilIdle()

            assertFalse(harness.playbackPort.state.value.isPlaying)
            val effect = awaitItem()
            assertTrue(effect is PlayerSideEffect.ShowMessage)
            assertTrue((effect as PlayerSideEffect.ShowMessage).message.contains("暂停"))
            cancelAndIgnoreRemainingEvents()
        }

        // Watch progress was flushed upon sleep expiry
        val saved = harness.historyRepository.getWatchProgress(SiteId.R34V, "v-sleep")
        assertNotNull(saved)
        assertEquals(50_000L, saved!!.positionMs)
    }

    @Test
    fun expiredStreamRefreshesStrictlyOnceAndStallDowngradeNotifies() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val refreshedStreams = listOf(
            VideoStream(quality = "720p", label = "720p", url = "https://cdn.example/fresh/720p.mp4"),
            VideoStream(quality = "480p", label = "480p", url = "https://cdn.example/fresh/480p.mp4"),
        )
        val (registry, plugin) = buildRegistry(
            r34vRefreshHandler = { refreshedStreams },
        )

        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v-exp", "https://r34v.example/video/v-exp"),
        )
        advanceUntilIdle()

        // First playback error triggers 1-time refresh
        harness.playbackPort.simulateError("HTTP 403 expired")
        advanceUntilIdle()

        assertEquals(1, plugin.refreshRequests.size)
        assertTrue(harness.playbackPort.state.value.hasRefreshedExpiredStream)
        assertEquals("https://cdn.example/fresh/720p.mp4", harness.playbackPort.state.value.currentStream?.url)

        // Second playback error must NOT trigger another refresh
        harness.playbackPort.simulateError("HTTP 403 again")
        advanceUntilIdle()
        assertEquals(1, plugin.refreshRequests.size)

        // Stall downgrade emits notice
        harness.playbackPort.syncSnapshot(clearError = true)
        harness.playbackPort.nextStallDowngradeStream = refreshedStreams.last()
        val downgraded = harness.playbackPort.evaluateStallTick()
        assertEquals("480p", downgraded?.quality)
        vm.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals("480p", state.currentStream?.quality)
            assertTrue(state.noticeMessage.orEmpty().contains("480p"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun relatedVideoSwitchesInPlaceAndFlushesPreviousWatchProgress() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()

        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v-first", "https://r34v.example/video/v-first"),
        )
        advanceUntilIdle()
        harness.playbackPort.simulatePosition(72_000L)

        vm.sideEffects.test {
            vm.selectRelatedVideo(sampleVideoItem(SiteId.R34V, "rel-clean", title = "Clean Related"))
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is PlayerSideEffect.ActiveMediaChanged)
            assertEquals("rel-clean", (effect as PlayerSideEffect.ActiveMediaChanged).args.mediaId)
            cancelAndIgnoreRemainingEvents()
        }

        // Previous video progress flushed before switching
        val prevSaved = harness.historyRepository.getWatchProgress(SiteId.R34V, "v-first")
        assertNotNull(prevSaved)
        assertEquals(72_000L, prevSaved!!.positionMs)

        // New video is active in-place
        vm.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals("rel-clean", state.mediaId)
            assertEquals("Video rel-clean", state.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sequentialQueueDisplaysNextConfirmationAndAdvancesInPlace() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()

        val q1 = sampleVideoItem(SiteId.R34V, "q1", title = "Queue Item 1")
        val q2 = sampleVideoItem(SiteId.R34V, "q2", title = "Queue Item 2")
        val q3 = sampleVideoItem(SiteId.R34V, "q3", title = "Queue Item 3")
        val session = harness.playbackQueueStore.start(
            title = "稍后看",
            shelfId = "watch-later",
            items = listOf(q1, q2, q3),
        )

        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs.fromMediaItem(q1, queueToken = session.token),
        )

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            val queue = requireNotNull(initial.queue)
            assertEquals(0, queue.currentIndex)
            assertEquals(3, queue.totalCount)
            assertEquals("q2", queue.nextItem?.id)
            assertFalse(initial.showQueueNextOverlay)

            // Video reaches end -> overlay prompt appears & item 1 marked completed
            harness.playbackPort.simulateEnded()
            advanceUntilIdle()
            val ended = expectMostRecentItem()
            assertTrue(ended.showQueueNextOverlay)
            assertTrue(harness.historyRepository.getWatchProgress(SiteId.R34V, "q1")?.completed == true)

            // Confirm queue next -> switches IN-PLACE to q2 with same queueToken
            assertTrue(vm.confirmAdvanceQueue())
            advanceUntilIdle()
            val second = expectMostRecentItem()
            assertEquals("q2", second.mediaId)
            assertEquals(1, second.queue?.currentIndex)
            assertEquals("q3", second.queue?.nextItem?.id)

            // Ending queue clears queue banner cleanly
            vm.endQueue()
            advanceUntilIdle()
            val afterEnd = expectMostRecentItem()
            assertNull(afterEnd.queue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun timeBookmarkCrudAndJumpOperateReactively() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()
        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v-bm", "https://r34v.example/video/v-bm"),
        )

        vm.uiState.test {
            advanceUntilIdle()
            assertTrue(expectMostRecentItem().timeBookmarks.isEmpty())

            // Create two bookmarks out of chronological order -> DAO emits sorted by positionMs ASC
            vm.addTimeBookmark(label = "结尾", groupName = "高能", positionMs = 120_000L)
            vm.addTimeBookmark(label = "开场", groupName = "章节", positionMs = 30_000L)
            advanceUntilIdle()

            val afterAdd = expectMostRecentItem()
            assertEquals(2, afterAdd.timeBookmarks.size)
            assertEquals(30_000L, afterAdd.timeBookmarks[0].positionMs)
            assertEquals("开场", afterAdd.timeBookmarks[0].label)
            assertEquals(120_000L, afterAdd.timeBookmarks[1].positionMs)

            // Update bookmark note & group
            val first = afterAdd.timeBookmarks[0]
            vm.updateTimeBookmark(first, label = "精彩开场", groupName = "收藏节点")
            advanceUntilIdle()
            val afterUpdate = expectMostRecentItem()
            assertEquals("精彩开场", afterUpdate.timeBookmarks[0].label)
            assertEquals("收藏节点", afterUpdate.timeBookmarks[0].groupName)

            // Jump seeks engine to bookmark position
            vm.jumpToTimeBookmark(afterUpdate.timeBookmarks[1])
            assertEquals(120_000L, harness.playbackPort.currentPositionMs())

            // Delete bookmark
            vm.deleteTimeBookmark(first.id)
            advanceUntilIdle()
            val afterDelete = expectMostRecentItem()
            assertEquals(1, afterDelete.timeBookmarks.size)
            assertEquals(120_000L, afterDelete.timeBookmarks.single().positionMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun watchLaterFavoriteDownloadAndTagBlockReactivelyFilterRelated() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()
        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v-social", "https://r34v.example/video/v-social"),
        )

        vm.uiState.test {
            advanceUntilIdle()
            val base = expectMostRecentItem()
            assertFalse(base.inWatchLater)
            assertFalse(base.isFavorite)
            assertEquals(2, base.relatedItems.size)

            vm.toggleWatchLater()
            vm.toggleFavorite()
            vm.enqueueDownload()
            advanceUntilIdle()

            val updated = expectMostRecentItem()
            assertTrue(updated.inWatchLater)
            assertTrue(updated.isFavorite)
            assertEquals(1, harness.offlinePort.enqueuedVideos.size)

            // Favorite & block tag "spoiler" -> matching related item disappears reactively
            val spoilerTag = updated.tags.first { it.tag.slug == "spoiler" }.tag
            vm.toggleTagFavorite(spoilerTag)
            vm.toggleTagBlocked(spoilerTag)
            advanceUntilIdle()

            val filtered = expectMostRecentItem()
            val updatedSpoiler = filtered.tags.first { it.tag.slug == "spoiler" }
            assertTrue(updatedSpoiler.isFavorited)
            assertTrue(updatedSpoiler.isBlocked)
            assertEquals(listOf("rel-clean"), filtered.relatedItems.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun foregroundTickerPollsOnlyWhileActiveAndStopsImmediatelyWhenCancelled() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()
        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v-tick", "https://r34v.example/video/v-tick"),
        )
        advanceUntilIdle()

        val tickerJob = launch {
            vm.runForegroundTicker()
        }
        advanceTimeBy(1_100L)
        runCurrent()

        val loopsWhileStarted = harness.playbackPort.loopTickCount
        val stallsWhileStarted = harness.playbackPort.stallTickCount
        assertTrue(loopsWhileStarted > 0)
        assertTrue(stallsWhileStarted > 0)

        // Simulates repeatOnLifecycle(STARTED) cancelling on ON_STOP -> zero background idle polling
        tickerJob.cancel()
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(loopsWhileStarted, harness.playbackPort.loopTickCount)
        assertEquals(stallsWhileStarted, harness.playbackPort.stallTickCount)
    }

    @Test
    fun autoFullscreenDefaultsToFalseAndAutoplayNeverEmitsRequestAutoFullscreen() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry()

        // Default model & settings have autoFullscreen = false
        assertFalse(PlaybackPreferences().autoFullscreen)
        assertFalse(harness.preferencesRepository.currentSettings().autoFullscreen)

        val vm = harness.createViewModel(registry)
        val collectedEffects = mutableListOf<PlayerSideEffect>()
        val collectJob = launch { vm.sideEffects.collect { collectedEffects += it } }

        // 1. Default settings + autoplay = true -> never emits RequestAutoFullscreen
        vm.open(
            PlayerLaunchArgs(
                siteId = SiteId.R34V,
                mediaId = "v-auto-default",
                url = "https://r34v.example/video/v-auto-default",
                autoplay = true,
            ),
        )
        advanceUntilIdle()
        harness.playbackPort.simulateVideoSize(1920, 1080)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.playbackPreferences.autoFullscreen)
        assertTrue(collectedEffects.none { it is PlayerSideEffect.RequestAutoFullscreen })

        // 2. Even when autoFullscreen is explicitly enabled globally & per-site, autoplay = true MUST NOT auto-fullscreen
        harness.preferencesRepository.setAutoFullscreen(true)
        harness.preferencesRepository.savePlaybackPreferences(
            PlaybackPreferences(siteId = SiteId.R34V, autoFullscreen = true),
        )
        vm.open(
            PlayerLaunchArgs(
                siteId = SiteId.R34V,
                mediaId = "v-auto-enabled",
                url = "https://r34v.example/video/v-auto-enabled",
                autoplay = true,
            ),
            forceReload = true,
        )
        advanceUntilIdle()
        harness.playbackPort.simulateVideoSize(1920, 1080)
        advanceUntilIdle()

        assertTrue(collectedEffects.none { it is PlayerSideEffect.RequestAutoFullscreen })

        // 3. Explicit manual play (autoplay = false) with opt-in autoFullscreen = true triggers once after play()
        vm.open(
            PlayerLaunchArgs(
                siteId = SiteId.R34V,
                mediaId = "v-manual-enabled",
                url = "https://r34v.example/video/v-manual-enabled",
                autoplay = false,
            ),
            forceReload = true,
        )
        advanceUntilIdle()
        harness.playbackPort.simulateVideoSize(1920, 1080)
        advanceUntilIdle()
        // Still paused -> must not jump to fullscreen before user clicks Play
        assertTrue(collectedEffects.none { it is PlayerSideEffect.RequestAutoFullscreen })

        // Manual play triggers one-shot RequestAutoFullscreen(landscape = true)
        vm.play()
        advanceUntilIdle()
        val autoFsEffects = collectedEffects.filterIsInstance<PlayerSideEffect.RequestAutoFullscreen>()
        assertEquals(1, autoFsEffects.size)
        assertTrue(autoFsEffects.single().landscape)

        collectJob.cancel()
    }

    @Test
    fun cachesDetailTagsFiltersRelatedByGlobalBilingualAndCachedTagsAndUpdatesCustomTranslationLive() = runTest {
        val harness = PlayerTestHarness(mainDispatcherRule.dispatcher)
        val (registry, _) = buildRegistry(
            r34vDetailHandler = { url ->
                val id = url.substringAfterLast('/')
                VideoDetail(
                    item = sampleVideoItem(SiteId.R34V, id, title = "Video $id"),
                    streams = sampleStreams(id),
                    tags = listOf(
                        TagRef(slug = "blonde", label = "blonde"),
                        TagRef(slug = "spoiler", label = "spoiler"),
                    ),
                    related = listOf(
                        sampleVideoItem(SiteId.R34V, "rel-clean", title = "Clean Related"),
                        sampleVideoItem(
                            SiteId.R34V,
                            "rel-bilingual",
                            title = "Spoiler Related",
                            tags = listOf(TagRef(slug = "spoiler", label = "spoiler")),
                        ),
                        // No inline tags on related card, but MediaTagCacheRepository has "spoiler"
                        sampleVideoItem(SiteId.R34V, "rel-cached-blocked", title = "Neutral Title"),
                    ),
                )
            },
        )
        harness.mediaTagCacheRepository.upsertTags(
            siteId = SiteId.R34V,
            mediaId = "rel-cached-blocked",
            tags = listOf(TagRef(slug = "spoiler", label = "spoiler")),
        )

        val vm = harness.createViewModel(
            registry,
            initialArgs = PlayerLaunchArgs(SiteId.R34V, "v-cache", "https://r34v.example/video/v-cache"),
        )

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(3, initial.relatedItems.size)

            // Detail tags cached in MediaTagCacheRepository
            val cached = harness.mediaTagCacheRepository.getTags(SiteId.R34V, "v-cache")
            assertTrue(cached.any { it.slug == "blonde" })
            assertTrue(cached.any { it.slug == "spoiler" })

            // Global Chinese block ("剧透" -> "spoiler") filters BOTH direct-tagged AND cached-tag related videos!
            harness.blockedTermsRepository.addTerm(SiteId.GLOBAL, "剧透")
            advanceUntilIdle()
            val afterGlobalBlock = expectMostRecentItem()
            assertEquals(listOf("rel-clean"), afterGlobalBlock.relatedItems.map { it.id })
            assertTrue(afterGlobalBlock.tags.first { it.tag.slug == "spoiler" }.isBlocked)

            // Custom Chinese translation edit refreshes UI tags immediately
            val blondeTag = afterGlobalBlock.tags.first { it.tag.slug == "blonde" }.tag
            vm.saveCustomTagTranslation(blondeTag, "浅金长发")
            advanceUntilIdle()
            val afterRename = expectMostRecentItem()
            assertEquals("浅金长发", afterRename.tags.first { it.tag.slug == "blonde" }.displayLabel)

            // Unblocking removes global term cleanly, then blocking with blockTagsGlobally=false writes per-site
            val spoilerTag = afterRename.tags.first { it.tag.slug == "spoiler" }.tag
            vm.toggleTagBlocked(spoilerTag)
            advanceUntilIdle()
            assertFalse(expectMostRecentItem().tags.first { it.tag.slug == "spoiler" }.isBlocked)

            harness.preferencesRepository.setBlockTagsGlobally(false)
            vm.toggleTagBlocked(spoilerTag)
            advanceUntilIdle()
            val allBlocked = harness.blockedTermDao.getAll()
            assertTrue(allBlocked.any { it.siteId == SiteId.R34V.value && it.term == "spoiler" })
            assertFalse(allBlocked.any { it.siteId == SiteId.GLOBAL.value && it.term == "spoiler" })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
