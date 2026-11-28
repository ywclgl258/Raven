package com.raven.app

import android.Manifest
import android.app.ActivityOptions
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.designsystem.component.LocalRavenImageRequestDecorator
import com.raven.app.core.designsystem.theme.RavenTheme
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import com.raven.app.core.network.ravenImageRequestDecorator
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.feature.player.PlayerLaunchArgs
import com.raven.app.feature.settings.AppLockSessionCoordinator
import com.raven.app.feature.settings.RavenPrivacyGate
import com.raven.app.feature.settings.RavenUpdateInstaller
import com.raven.app.feature.settings.auth.SiteAuthActivity
import java.io.File
import org.koin.android.ext.android.inject

/**
 * Primary single-top Activity hosting the entire Raven adaptive navigation shell, Type-Safe
 * 25-route [NavHost], incoming `ACTION_SEND` URL resolver sheet,
 * FileProvider sharing, APK installer handoff, and Reader window/hardware-key controls.
 */
class MainActivity : ComponentActivity(), MainActivityHostActions {

    private val siteRegistry: SiteRegistry by inject()
    private val preferencesRepository: PreferencesRepository by inject()
    private val subscriptionRepository: SubscriptionRepository by inject()
    private val appLockCoordinator: AppLockSessionCoordinator by inject()

    private var pendingNavigationRoute by mutableStateOf<RavenRoute?>(null)
    private var pendingSharedLink by mutableStateOf<SharedLinkPrompt?>(null)

    private var pendingInstallApk: File? = null

    private var volumeKeyHandler: ((up: Boolean, isLongPress: Boolean) -> Boolean)? = null
    private var volumeKeyConsumedDown: Boolean = false
    private var volumeLongPressTriggered: Boolean = false

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (RavenUpdateInstaller.canRequestPackageInstalls(this)) {
            resumePendingInstall()
        } else {
            pendingInstallApk = null
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Permission status is checked directly before posting notifications */ }

    override val fileProviderAuthority: String
        get() = "$packageName.fileprovider"

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        pendingInstallApk = savedInstanceState?.getString(STATE_INSTALL_APK)?.let(::File)
        DisplayRefreshRateController.applyHighRefreshRate(this, allowVideoFrameRateMatching = false)

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
            requestNotificationPermissionIfNeeded()
        }

        setContent {
            val loadedSettings by preferencesRepository.settings.collectAsStateWithLifecycle(
                initialValue = null,
            )
            val appSettings = loadedSettings ?: AppSettings()
            val unreadCount by subscriptionRepository.observeUnreadCount().collectAsStateWithLifecycle(
                initialValue = 0,
            )
            val startupUpdate by StartupUpdateNotifier.latestUpdate.collectAsStateWithLifecycle()
            val lockState by appLockCoordinator.state.collectAsStateWithLifecycle()
            LaunchedEffect(startupUpdate?.release?.tagName, lockState.isLocked, lockState.lockInitialized) {
                val available = startupUpdate ?: return@LaunchedEffect
                if (lockState.isLocked || !lockState.lockInitialized) return@LaunchedEffect
                if (!StartupUpdateNotifier.consumeToastFor(available.release.tagName)) return@LaunchedEffect
                Toast.makeText(
                    this@MainActivity,
                    "发现新版本 ${available.release.tagName}，可在设置中查看",
                    Toast.LENGTH_LONG,
                ).show()
            }

            LaunchedEffect(loadedSettings?.privacyScreenSecure) {
                if (loadedSettings?.privacyScreenSecure != false) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            RavenTheme(
                reduceTransparency = appSettings.reduceTransparency,
                uiSoundEffectsEnabled = appSettings.uiSoundEffectsEnabled,
            ) {
                CompositionLocalProvider(
                    LocalRavenImageRequestDecorator provides ::ravenImageRequestDecorator,
                ) {
                    val navController = rememberNavController()
                    RavenPrivacyGate(
                        coordinator = appLockCoordinator,
                        onBiometricClick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && canOfferBiometricUnlock()) {
                            { triggerBiometricUnlock() }
                        } else null,
                    ) {
                        LaunchedEffect(Unit) { resumePendingInstall() }
                        RavenAppShell(
                            siteRegistry = siteRegistry,
                            hostActions = this@MainActivity,
                            unreadInboxCount = unreadCount,
                            isIncognito = appSettings.incognitoMode,
                            navController = navController,
                            pendingNavigationRoute = pendingNavigationRoute,
                            onConsumeNavigationRoute = { pendingNavigationRoute = null },
                            pendingSharedLink = pendingSharedLink,
                            onDismissSharedLink = { pendingSharedLink = null },
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_INSTALL_APK, pendingInstallApk?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        DisplayRefreshRateController.applyHighRefreshRate(this, allowVideoFrameRateMatching = false)
        resumePendingInstall()
    }

    private fun resumePendingInstall() {
        val apk = pendingInstallApk ?: return
        val lock = appLockCoordinator.state.value
        if (lock.lockInitialized && !lock.isLocked && RavenUpdateInstaller.canRequestPackageInstalls(this)) {
            pendingInstallApk = null
            if (apk.isFile) startApkInstaller(apk)
        }
    }

    private fun canOfferBiometricUnlock(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(BiometricManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            return manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun triggerBiometricUnlock() {
        val signal = CancellationSignal()
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("解锁 Raven")
            .setSubtitle("请验证生物识别以继续使用")
            .setNegativeButton("改用 PIN 密码", mainExecutor) { _, _ -> }
            .build()
        prompt.authenticate(
            signal,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    appLockCoordinator.onBiometricUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    ) {
                        appLockCoordinator.onBiometricError(errString?.toString())
                    }
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        // 1. Return-from-PlayerActivity route handoff (tag / author / gallery / site account)
        val targetRoute = MainActivityIntentContract.parseNavigateRoute(intent)
        if (targetRoute != null) {
            intent.removeExtra(MainActivityIntentContract.EXTRA_NAVIGATE_ROUTE)
            pendingNavigationRoute = targetRoute
            return
        }

        // 2. External ACTION_SEND text/plain URL sharing
        val sharedText = MainActivityIntentContract.extractSharedText(intent)
        if (!sharedText.isNullOrBlank()) {
            val matched = siteRegistry.resolveLinkFromText(sharedText)
            pendingSharedLink = SharedLinkPrompt(
                rawText = sharedText,
                plugin = matched?.first,
                resolved = matched?.second,
            )
        }
    }

    // ---- MainActivityHostActions ----

    override fun launchPlayer(args: PlayerLaunchArgs) {
        val playerIntent = args.toIntent(this, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (DisplayRefreshRateController.isSystemMotionReduced(this)) {
            startActivity(playerIntent)
        } else {
            val options = ActivityOptions.makeCustomAnimation(
                this,
                R.anim.raven_player_enter,
                R.anim.raven_host_hold,
            )
            startActivity(playerIntent, options.toBundle())
        }
    }

    override fun launchWebViewAuth(siteId: SiteId) {
        val authIntent = SiteAuthActivity.createIntent(this, siteId)
        if (DisplayRefreshRateController.isSystemMotionReduced(this)) {
            startActivity(authIntent)
        } else {
            val options = ActivityOptions.makeCustomAnimation(
                this,
                R.anim.raven_player_enter,
                R.anim.raven_host_hold,
            )
            startActivity(authIntent, options.toBundle())
        }
    }

    override fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun setReaderBrightness(value: Float) {
        val attrs = window.attributes
        attrs.screenBrightness = if (value < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            value.coerceIn(0.01f, 1.0f)
        }
        window.attributes = attrs
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun registerVolumeKeyHandler(
        handler: ((up: Boolean, isLongPress: Boolean) -> Boolean)?,
    ) {
        volumeKeyHandler = handler
        if (handler == null) {
            volumeKeyConsumedDown = false
            volumeLongPressTriggered = false
        }
    }

    override fun shareFiles(uris: List<Uri>, mimeType: String, subject: String) {
        if (uris.isEmpty()) return
        val resolvedMime = mimeType.ifBlank { "image/*" }
        val shareIntent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = resolvedMime
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = resolvedMime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }.apply {
            if (subject.isNotBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newUri(contentResolver, subject.ifBlank { "Raven" }, uris.first())
            for (index in 1 until uris.size) {
                clip.addItem(ClipData.Item(uris[index]))
            }
            clipData = clip
        }

        runCatching {
            startActivity(Intent.createChooser(shareIntent, subject.ifBlank { "分享" }))
        }.onFailure {
            Toast.makeText(this, "未找到可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun openAppUpdateInstaller(apkFile: File) {
        if (!apkFile.isFile) {
            Toast.makeText(this, "未找到已校验的安装包文件", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !RavenUpdateInstaller.canRequestPackageInstalls(this)
        ) {
            pendingInstallApk = apkFile
            runCatching {
                unknownSourcesLauncher.launch(
                    RavenUpdateInstaller.unknownSourcesPermissionIntent(this),
                )
            }.onFailure {
                pendingInstallApk = null
                Toast.makeText(this, "无法打开安装未知应用权限页面", Toast.LENGTH_SHORT).show()
            }
            return
        }
        startApkInstaller(apkFile)
    }

    private fun startApkInstaller(apkFile: File) {
        runCatching {
            startActivity(RavenUpdateInstaller.createInstallIntent(this, apkFile))
        }.onFailure {
            Toast.makeText(this, "无法启动系统安装程序", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = volumeKeyHandler
        if (handler != null &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val isUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP
            if (event.repeatCount == 0) {
                volumeLongPressTriggered = false
                volumeKeyConsumedDown = handler(isUp, false)
                if (volumeKeyConsumedDown) return true
            } else if (volumeKeyConsumedDown) {
                if (!volumeLongPressTriggered && (event.isLongPress || event.repeatCount >= 1)) {
                    volumeLongPressTriggered = handler(isUp, true)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            volumeKeyConsumedDown
        ) {
            volumeKeyConsumedDown = false
            volumeLongPressTriggered = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        const val STATE_INSTALL_APK = "pending_install_apk"
    }
}
