package com.raven.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.common.UrlSanitizer
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.designsystem.component.LocalRavenImageRequestDecorator
import com.raven.app.core.designsystem.component.RavenAppLockOverlay
import com.raven.app.core.designsystem.theme.LocalMotionReduced
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenMotion
import com.raven.app.core.designsystem.theme.RavenTheme
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.network.ravenImageRequestDecorator
import com.raven.app.core.security.PrivacyLockStore
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.feature.player.PlayerLaunchArgs
import com.raven.app.feature.player.PlayerNavigator
import com.raven.app.feature.player.PlayerViewModel
import com.raven.app.feature.player.VideoPlayerScreen
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

/**
 * Dedicated PiP-capable single-top video playback Activity (`ARCHITECTURE.md` §5.1).
 *
 * - Switches related and sequential-queue videos IN-PLACE via [onNewIntent] -> `viewModel.open(args)`
 *   with zero Activity task-stack accumulation.
 * - Supports API 26+ Picture-in-Picture with aspect-ratio clamping, sensor fullscreen orientation
 *   with transient immersive system bars, window brightness gestures, and clean return navigation
 *   to [MainActivity] (`FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP`).
 */
class PlayerActivity : ComponentActivity() {

    private val siteRegistry: SiteRegistry by inject()
    private val preferencesRepository: PreferencesRepository by inject()
    private val privacyLockStore: PrivacyLockStore by inject()
    private val appLockCoordinator: AppLockSessionCoordinator by inject()

    private var initialLaunchArgs: PlayerLaunchArgs? = null
    private val playerViewModel: PlayerViewModel by viewModel {
        parametersOf(initialLaunchArgs)
    }

    private var isInPipMode by mutableStateOf(false)
    private var isFullscreen by mutableStateOf(false)
    private var suppressAutoPipOnce: Boolean = false
    private var latestVideoBounds: android.graphics.Rect? = null
    private var latestAppSettings: AppSettings = AppSettings()

    private val playerNavigator: PlayerNavigator = object : PlayerNavigator {
        override fun openTagFeed(siteId: SiteId, tag: TagRef) {
            navigateBackToMain(MainActivityIntentContract.resolveTagRoute(siteRegistry, siteId, tag))
        }

        override fun openAuthorFeed(siteId: SiteId, author: AuthorRef) {
            navigateBackToMain(MainActivityIntentContract.resolveAuthorFeedRoute(siteRegistry, siteId, author))
        }

        override fun openExternalUrl(url: String) {
            if (!UrlSanitizer.isSafeHttpUrl(url, allowHttp = true)) {
                Toast.makeText(this@PlayerActivity, "已拦截不安全的外部链接协议", Toast.LENGTH_SHORT).show()
                return
            }
            suppressAutoPipOnce = true
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }.onFailure {
                suppressAutoPipOnce = false
                Toast.makeText(this@PlayerActivity, "无法打开外部链接", Toast.LENGTH_SHORT).show()
            }
        }

        override fun openGallery(
            siteId: SiteId,
            mediaId: String,
            url: String,
            offlineOnly: Boolean,
            queueToken: String?,
        ) {
            if (!queueToken.isNullOrBlank()) {
                navigateBackToMain(
                    RavenRoute.Reader(
                        siteId = siteId.value,
                        mediaId = mediaId,
                        url = url,
                        startPage = 1,
                        offlineOnly = offlineOnly,
                        queueToken = queueToken,
                    ),
                )
            } else {
                navigateBackToMain(
                    RavenRoute.GalleryDetail(
                        siteId = siteId.value,
                        mediaId = mediaId,
                        url = url,
                    ),
                )
            }
        }

        override fun openSiteAccount(siteId: SiteId) {
            navigateBackToMain(RavenRoute.SiteAccount(siteId = siteId.value))
        }

        override fun requestPip(aspectRatio: Pair<Int, Int>?) {
            enterPipMode(aspectRatio)
        }

        override fun requestFullscreen(landscape: Boolean) {
            isFullscreen = true
            requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            applySystemBarsVisible(false)
        }

        override fun exitFullscreen() {
            isFullscreen = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            applySystemBarsVisible(true)
        }

        override fun setWindowBrightness(value: Float) {
            val attrs = window.attributes
            attrs.screenBrightness = if (value < 0f) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                value.coerceIn(0.01f, 1.0f)
            }
            window.attributes = attrs
        }

        override fun onActiveMediaChanged(args: PlayerLaunchArgs) {
            args.writeToIntent(intent)
        }

        override fun onClose() {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        DisplayRefreshRateController.applyHighRefreshRate(this, allowVideoFrameRateMatching = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (DisplayRefreshRateController.isSystemMotionReduced(this)) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                overrideActivityTransition(
                    OVERRIDE_TRANSITION_CLOSE,
                    R.anim.raven_host_resume,
                    R.anim.raven_player_exit,
                )
            }
        }

        val launchArgs = PlayerLaunchArgs.fromIntent(intent)
        if (launchArgs == null) {
            finish()
            return
        }
        initialLaunchArgs = launchArgs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isInPipMode = isInPictureInPictureMode
        }

        setContent {
            val loadedSettings by preferencesRepository.settings.collectAsStateWithLifecycle(
                initialValue = null,
            )
            val appSettings = loadedSettings ?: AppSettings()
            val uiState by playerViewModel.structuralUiState.collectAsStateWithLifecycle()
            val lockState by appLockCoordinator.state.collectAsStateWithLifecycle()
            var hasInteractiveOverlay by remember { mutableStateOf(true) }
            val hasStoredPin = remember { privacyLockStore.hasPin() }
            val awaitingInitialLockDecision = loadedSettings == null && hasStoredPin && !lockState.lockInitialized
            latestAppSettings = appSettings

            LaunchedEffect(loadedSettings) {
                val realSettings = loadedSettings ?: return@LaunchedEffect
                appLockCoordinator.onSettingsLoaded(realSettings)
            }

            val showLockOverlay = lockState.isLocked && appSettings.appLockEnabled && privacyLockStore.hasPin()

            LaunchedEffect(showLockOverlay, uiState.isPlaying) {
                if (showLockOverlay && uiState.isPlaying) {
                    playerViewModel.onBackgrounded(isInPipMode = false)
                }
            }

            var lockoutSecondsRemaining by remember { mutableIntStateOf(0) }
            LaunchedEffect(lockState.isLocked, lockState.lockoutUntilElapsedMs) {
                while (lockState.isLocked && lockState.lockoutUntilElapsedMs > 0L) {
                    val remainingMs = (lockState.lockoutUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    lockoutSecondsRemaining = ((remainingMs + 999L) / 1_000L).toInt()
                    if (remainingMs <= 0L) {
                        appLockCoordinator.onLockoutExpired()
                        break
                    }
                    delay(1_000L)
                }
                if (lockState.lockoutUntilElapsedMs <= 0L) {
                    lockoutSecondsRemaining = 0
                }
            }

            LaunchedEffect(appSettings.privacyScreenSecure) {
                if (appSettings.privacyScreenSecure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(uiState.isPlaying) {
                if (uiState.isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(
                uiState.isPlaying,
                uiState.videoWidth,
                uiState.videoHeight,
                appSettings.backgroundAudioPlayback,
            ) {
                updatePipParams()
            }

            LaunchedEffect(uiState.isPlaying, showLockOverlay, isInPipMode, hasInteractiveOverlay) {
                val allowVideoRateMatch = uiState.isPlaying &&
                    !showLockOverlay &&
                    !isInPipMode &&
                    !hasInteractiveOverlay
                DisplayRefreshRateController.applyHighRefreshRate(
                    activity = this@PlayerActivity,
                    allowVideoFrameRateMatching = allowVideoRateMatch,
                )
            }

            RavenTheme(
                reduceTransparency = appSettings.reduceTransparency,
                uiSoundEffectsEnabled = appSettings.uiSoundEffectsEnabled,
            ) {
                CompositionLocalProvider(
                    LocalRavenImageRequestDecorator provides ::ravenImageRequestDecorator,
                ) {
                    val colors = LocalRavenColors.current
                    val motionReduced = LocalMotionReduced.current
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!awaitingInitialLockDecision) {
                            VideoPlayerScreen(
                                viewModel = playerViewModel,
                                navigator = playerNavigator,
                                isInPipMode = isInPipMode,
                                isFullscreen = isFullscreen,
                                onVideoBoundsChanged = { bounds ->
                                    latestVideoBounds = bounds
                                    updatePipParams(bounds)
                                },
                                onInteractiveOverlayChanged = { active ->
                                    hasInteractiveOverlay = active
                                },
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize().background(colors.canvas))
                        }

                        AnimatedVisibility(
                            visible = showLockOverlay,
                            enter = if (motionReduced) {
                                EnterTransition.None
                            } else {
                                fadeIn(
                                    animationSpec = tween(
                                        durationMillis = 180,
                                        easing = RavenMotion.HarmonyDecelerationEasing,
                                    ),
                                ) + scaleIn(
                                    initialScale = 1.03f,
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        easing = RavenMotion.HarmonyDecelerationEasing,
                                    ),
                                )
                            },
                            exit = if (motionReduced) {
                                ExitTransition.None
                            } else {
                                fadeOut(
                                    animationSpec = tween(
                                        durationMillis = 180,
                                        easing = RavenMotion.HarmonyDecelerationEasing,
                                    ),
                                ) + scaleOut(
                                    targetScale = 0.97f,
                                    animationSpec = tween(
                                        durationMillis = 200,
                                        easing = RavenMotion.HarmonyDecelerationEasing,
                                    ),
                                )
                            },
                        ) {
                            RavenAppLockOverlay(
                                onSubmitPinAsync = { candidate ->
                                    appLockCoordinator.verifyPin(candidate)
                                },
                                errorMessage = lockState.errorMessage,
                                lockoutSecondsRemaining = lockoutSecondsRemaining,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nextArgs = PlayerLaunchArgs.fromIntent(intent) ?: return
        playerViewModel.open(nextArgs)
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (DisplayRefreshRateController.isSystemMotionReduced(this)) {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.raven_host_resume, R.anim.raven_player_exit)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        suppressAutoPipOnce = false
        val state = playerViewModel.structuralUiState.value
        val allowVideoRateMatch = state.isPlaying && !isInPipMode
        DisplayRefreshRateController.applyHighRefreshRate(
            activity = this,
            allowVideoFrameRateMatching = allowVideoRateMatch,
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (suppressAutoPipOnce) {
            suppressAutoPipOnce = false
            return
        }
        if (latestAppSettings.backgroundAudioPlayback) return
        if (initialLaunchArgs != null &&
            Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S &&
            !isInPictureInPictureMode
        ) {
            val state = playerViewModel.uiState.value
            if (state.isPlaying) {
                val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
                    state.videoWidth to state.videoHeight
                } else {
                    16 to 9
                }
                enterPipMode(ratio)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (!isInPictureInPictureMode && isFullscreen) {
            applySystemBarsVisible(false)
        }
    }

    private fun updatePipParams(sourceRectHint: android.graphics.Rect? = latestVideoBounds) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val state = playerViewModel.uiState.value
        val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
            state.videoWidth to state.videoHeight
        } else {
            16 to 9
        }
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(toSafePipRational(ratio))
        if (sourceRectHint != null && !sourceRectHint.isEmpty) {
            builder.setSourceRectHint(sourceRectHint)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(state.isPlaying && !latestAppSettings.backgroundAudioPlayback)
        }
        runCatching {
            setPictureInPictureParams(builder.build())
        }
    }

    private fun enterPipMode(aspectRatio: Pair<Int, Int>?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(toSafePipRational(aspectRatio))
        val hint = latestVideoBounds
        if (hint != null && !hint.isEmpty) {
            builder.setSourceRectHint(hint)
        }
        runCatching {
            enterPictureInPictureMode(builder.build())
        }
    }

    private fun navigateBackToMain(route: RavenRoute) {
        suppressAutoPipOnce = true
        startActivity(MainActivityIntentContract.createNavigateIntent(this, route))
        finish()
    }

    private fun applySystemBarsVisible(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        private const val MIN_PIP_RATIO: Float = 0.42f
        private const val MAX_PIP_RATIO: Float = 2.38f

        internal fun clampPipDimensions(aspectRatio: Pair<Int, Int>?): Pair<Int, Int> {
            if (aspectRatio == null) return 16 to 9
            val (w, h) = aspectRatio
            if (w <= 0 || h <= 0) return 16 to 9
            val rawRatio = w.toFloat() / h.toFloat()
            val clamped = rawRatio.coerceIn(MIN_PIP_RATIO, MAX_PIP_RATIO)
            return (clamped * 1_000f).roundToInt() to 1_000
        }

        internal fun toSafePipRational(aspectRatio: Pair<Int, Int>?): Rational {
            val (w, h) = clampPipDimensions(aspectRatio)
            return Rational(w, h)
        }
    }
}
