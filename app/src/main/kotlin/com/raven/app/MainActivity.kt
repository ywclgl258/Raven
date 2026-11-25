package com.raven.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.database.model.AppSettings
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.designsystem.component.LocalRavenImageRequestDecorator
import com.raven.app.core.designsystem.component.RavenAppLockOverlay
import com.raven.app.core.designsystem.theme.RavenTheme
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import com.raven.app.core.network.ravenImageRequestDecorator
import com.raven.app.core.security.PrivacyLockStore
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.feature.player.PlayerLaunchArgs
import com.raven.app.feature.settings.RavenUpdateInstaller
import com.raven.app.feature.settings.auth.SiteAuthActivity
import java.io.File
import kotlinx.coroutines.delay
import org.koin.android.ext.android.inject

/**
 * Primary single-top Activity hosting the entire Raven adaptive navigation shell, Type-Safe
 * 25-route [NavHost], incoming `ACTION_SEND` URL resolver sheet, SAF document contracts,
 * FileProvider sharing, APK installer handoff, and Reader window/hardware-key controls.
 */
class MainActivity : ComponentActivity(), MainActivityHostActions {

    private val siteRegistry: SiteRegistry by inject()
    private val preferencesRepository: PreferencesRepository by inject()
    private val subscriptionRepository: SubscriptionRepository by inject()
    private val privacyLockStore: PrivacyLockStore by inject()

    private var pendingNavigationRoute by mutableStateOf<RavenRoute?>(null)
    private var pendingSharedLink by mutableStateOf<SharedLinkPrompt?>(null)

    private var isAppLocked by mutableStateOf(false)
    private var lastBackgroundElapsedMs by mutableLongStateOf(0L)
    private var lockErrorMessage by mutableStateOf<String?>(null)
    private var failedPinAttempts by mutableIntStateOf(0)
    private var lockoutUntilElapsedMs by mutableLongStateOf(0L)
    private var lockInitialized by mutableStateOf(false)
    private var latestAppSettings: AppSettings = AppSettings()

    private var pendingCreateDocumentCallback: ((Uri?) -> Unit)? = null
    private var pendingOpenDocumentCallback: ((Uri?) -> Unit)? = null
    private var pendingOpenTreeCallback: ((Uri?) -> Unit)? = null
    private var pendingInstallApk: File? = null

    private var volumeKeyHandler: ((up: Boolean, isLongPress: Boolean) -> Boolean)? = null
    private var volumeKeyConsumedDown: Boolean = false
    private var volumeLongPressTriggered: Boolean = false

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingCreateDocumentCallback
        pendingCreateDocumentCallback = null
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        callback?.invoke(uri)
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = pendingOpenDocumentCallback
        pendingOpenDocumentCallback = null
        callback?.invoke(uri)
    }

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val callback = pendingOpenTreeCallback
        pendingOpenTreeCallback = null
        callback?.invoke(uri)
    }

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = pendingInstallApk
        pendingInstallApk = null
        if (apk != null && apk.isFile && RavenUpdateInstaller.canRequestPackageInstalls(this)) {
            startApkInstaller(apk)
        }
    }

    override val fileProviderAuthority: String
        get() = "$packageName.fileprovider"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val appSettings by preferencesRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            val unreadCount by subscriptionRepository.observeUnreadCount().collectAsStateWithLifecycle(
                initialValue = 0,
            )

            latestAppSettings = appSettings

            LaunchedEffect(appSettings.privacyScreenSecure) {
                if (appSettings.privacyScreenSecure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(appSettings.appLockEnabled) {
                val hasPin = privacyLockStore.hasPin()
                if (!lockInitialized) {
                    lockInitialized = true
                    if (appSettings.appLockEnabled && hasPin) {
                        isAppLocked = true
                    }
                } else if (!appSettings.appLockEnabled || !hasPin) {
                    isAppLocked = false
                    lockErrorMessage = null
                }
            }

            var lockoutSecondsRemaining by remember { mutableIntStateOf(0) }
            LaunchedEffect(isAppLocked, lockoutUntilElapsedMs) {
                while (isAppLocked && lockoutUntilElapsedMs > 0L) {
                    val remainingMs = (lockoutUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    lockoutSecondsRemaining = ((remainingMs + 999L) / 1_000L).toInt()
                    if (remainingMs <= 0L) {
                        lockoutUntilElapsedMs = 0L
                        failedPinAttempts = 0
                        break
                    }
                    delay(1_000L)
                }
                if (lockoutUntilElapsedMs <= 0L) {
                    lockoutSecondsRemaining = 0
                }
            }

            RavenTheme(reduceTransparency = appSettings.reduceTransparency) {
                CompositionLocalProvider(
                    LocalRavenImageRequestDecorator provides ::ravenImageRequestDecorator,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RavenAppShell(
                            siteRegistry = siteRegistry,
                            hostActions = this@MainActivity,
                            unreadInboxCount = unreadCount,
                            isIncognito = appSettings.incognitoMode,
                            pendingNavigationRoute = pendingNavigationRoute,
                            onConsumeNavigationRoute = { pendingNavigationRoute = null },
                            pendingSharedLink = pendingSharedLink,
                            onDismissSharedLink = { pendingSharedLink = null },
                        )

                        if (isAppLocked && appSettings.appLockEnabled && privacyLockStore.hasPin()) {
                            RavenAppLockOverlay(
                                onSubmitPin = { candidate ->
                                    val ok = privacyLockStore.verifyPin(candidate)
                                    if (ok) {
                                        isAppLocked = false
                                        lockErrorMessage = null
                                        failedPinAttempts = 0
                                        lockoutUntilElapsedMs = 0L
                                    } else {
                                        failedPinAttempts += 1
                                        if (failedPinAttempts >= MAX_PIN_ATTEMPTS) {
                                            lockoutUntilElapsedMs = SystemClock.elapsedRealtime() + LOCKOUT_DURATION_MS
                                        }
                                    }
                                    ok
                                },
                                errorMessage = lockErrorMessage,
                                lockoutSecondsRemaining = lockoutSecondsRemaining,
                                onBiometricClick = if (
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                                    canOfferBiometricUnlock()
                                ) {
                                    { triggerBiometricUnlock() }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val settings = latestAppSettings
        if (settings.appLockEnabled && privacyLockStore.hasPin() && lastBackgroundElapsedMs > 0L) {
            val elapsedMs = SystemClock.elapsedRealtime() - lastBackgroundElapsedMs
            if (elapsedMs >= settings.appLockTimeoutSeconds.coerceAtLeast(0) * 1_000L) {
                isAppLocked = true
            }
        }
        lastBackgroundElapsedMs = 0L
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingInstallApk
        if (apk != null && apk.isFile && RavenUpdateInstaller.canRequestPackageInstalls(this)) {
            pendingInstallApk = null
            startApkInstaller(apk)
        }
    }

    override fun onStop() {
        super.onStop()
        lastBackgroundElapsedMs = SystemClock.elapsedRealtime()
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
                    isAppLocked = false
                    lockErrorMessage = null
                    failedPinAttempts = 0
                    lockoutUntilElapsedMs = 0L
                }
            },
        )
    }

    companion object {
        private const val MAX_PIN_ATTEMPTS: Int = 5
        private const val LOCKOUT_DURATION_MS: Long = 30_000L
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
        startActivity(playerIntent)
    }

    override fun launchWebViewAuth(siteId: SiteId) {
        startActivity(SiteAuthActivity.createIntent(this, siteId))
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

    override fun launchSaveDocument(
        suggestedFileName: String,
        mimeType: String,
        onResult: (Uri?) -> Unit,
    ) {
        pendingCreateDocumentCallback = onResult
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, suggestedFileName)
        }
        runCatching {
            createDocumentLauncher.launch(intent)
        }.onFailure {
            pendingCreateDocumentCallback = null
            onResult(null)
        }
    }

    override fun launchOpenDocument(onResult: (Uri?) -> Unit) {
        pendingOpenDocumentCallback = onResult
        runCatching {
            openDocumentLauncher.launch(arrayOf("*/*"))
        }.onFailure {
            pendingOpenDocumentCallback = null
            onResult(null)
        }
    }

    override fun launchOpenDocumentTree(onResult: (Uri?) -> Unit) {
        pendingOpenTreeCallback = onResult
        runCatching {
            openDocumentTreeLauncher.launch(null)
        }.onFailure {
            pendingOpenTreeCallback = null
            onResult(null)
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
        if (!RavenUpdateInstaller.canRequestPackageInstalls(this)) {
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
}
