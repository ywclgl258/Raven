package com.raven.app.feature.settings.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenTheme
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.feature.settings.AppLockSessionCoordinator
import com.raven.app.feature.settings.RavenPrivacyGate
import com.raven.app.feature.settings.SettingsPrimaryButton
import com.raven.app.feature.settings.SettingsSecondaryButton
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.android.inject

/**
 * Dedicated `FLAG_SECURE` isolated WebView authentication Activity for sites declaring
 * [AuthMode.WebViewCookie] (e.g. E-Hentai / ExHentai).
 *
 * Security invariants enforced strictly per `ARCHITECTURE.md` §5.1 / §8.10:
 * 1. `WindowManager.LayoutParams.FLAG_SECURE` applied BEFORE `setContent`.
 * 2. 7-state FSM ([SiteAuthPhase]) driven exclusively by [AuthMode.WebViewCookie] (zero site-ID switch).
 * 3. HTTPS-only `allowedHosts` whitelist, zero JS bridges, `allowFileAccess = false`,
 *    `allowContentAccess = false`, `acceptThirdPartyCookies = false`, `MIXED_CONTENT_NEVER_ALLOW`,
 *    `LOAD_NO_CACHE`.
 * 4. Every SSL error is cancelled unconditionally (`handler.cancel()`).
 * 5. Required cookies are verified via `plugin.authProvider.importCookies(...)` BEFORE persisting.
 * 6. All WebView cookies, storage, history, form data, and disk cache are wiped on exit.
 */
class SiteAuthActivity : ComponentActivity() {

    private val lockCoordinator: AppLockSessionCoordinator by inject()
    private lateinit var plugin: SitePlugin
    private lateinit var authMode: AuthMode.WebViewCookie
    private lateinit var policy: SiteAuthPolicy
    private lateinit var dispatchers: AppDispatchers

    private var statusMessage by mutableStateOf("")
    private var phase by mutableStateOf(SiteAuthPhase.RESETTING)
    private var webSessionNonce by mutableIntStateOf(0)

    private var browser: WebView? = null
    private var cookieManager: CookieManager? = null
    private var savedBrowserState: Bundle? = null
    private var verificationJob: kotlinx.coroutines.Job? = null
    private var resumeVerification = false
    private var forumErrorShown = false
    private var targetVisitStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hard security invariant: enforce FLAG_SECURE BEFORE any content view is attached.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)

        val rawSiteId = intent.getStringExtra(EXTRA_SITE_ID).orEmpty().trim()
        val koin = runCatching { getKoin() }.getOrNull()
        val registry = koin?.getOrNull<SiteRegistry>()
        dispatchers = koin?.getOrNull<AppDispatchers>() ?: DefaultAppDispatchers()

        val resolvedPlugin = rawSiteId.takeIf { it.isNotEmpty() }
            ?.let { runCatching { SiteId.of(it) }.getOrNull() }
            ?.let { registry?.get(it) }
        val resolvedMode = resolvedPlugin?.authProvider?.mode as? AuthMode.WebViewCookie
        if (resolvedPlugin == null || resolvedMode == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        plugin = resolvedPlugin
        authMode = resolvedMode
        policy = SiteAuthPolicy(descriptor = resolvedPlugin.descriptor, mode = resolvedMode)

        val savedPhase = savedInstanceState?.getString(STATE_PHASE)
            ?.let { name -> SiteAuthPhase.entries.firstOrNull { it.name == name } }
        phase = savedPhase?.afterRecreation() ?: SiteAuthPhase.RESETTING
        resumeVerification = savedPhase == SiteAuthPhase.VERIFYING
        savedBrowserState = savedInstanceState?.getBundle(STATE_BROWSER)
        statusMessage = savedInstanceState?.getString(STATE_STATUS) ?: openingLoginMessage()
        forumErrorShown = savedInstanceState?.getBoolean(STATE_FORUM_ERROR) ?: false

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            RavenTheme {
                RavenPrivacyGate(lockCoordinator) { AuthContent() }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = browser?.takeIf(::isCurrent)
                if (phase == SiteAuthPhase.FORUM && web?.canGoBack() == true) {
                    web.goBack()
                } else {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        })
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun AuthContent() {
        val colors = LocalRavenColors.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .safeDrawingPadding()
                .imePadding()
                .testTag("site_auth_activity_root"),
        ) {
            RavenTopBar(
                title = "${plugin.descriptor.displayName} 官方登录",
                subtitle = "安全隔离会话 · 已启用防截屏保护",
                navigationIcon = {
                    IconButton(
                        onClick = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                        modifier = Modifier.testTag("site_auth_close_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "取消登录",
                            tint = colors.inkPrimary,
                        )
                    }
                },
                actions = {
                    RavenStatusPill(
                        text = phaseLabel(phase),
                        tone = when (phase) {
                            SiteAuthPhase.TARGET_FAILED -> RavenStatusTone.ERROR
                            SiteAuthPhase.TARGET_READY, SiteAuthPhase.VERIFYING -> RavenStatusTone.SUCCESS
                            else -> RavenStatusTone.NEUTRAL
                        },
                        icon = Icons.Outlined.Lock,
                    )
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface1)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "在官方登录页完成后，应用将验证 ${plugin.descriptor.displayName} 的访问权限，并在退出时自动清空所有临时 WebView 数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("site_auth_status_text"),
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsPrimaryButton(
                        text = "验证登录",
                        icon = Icons.Rounded.Check,
                        enabled = phase.canVerify,
                        loading = phase == SiteAuthPhase.VERIFYING || phase == SiteAuthPhase.COPYING_IDENTITY,
                        onClick = ::completeLogin,
                        modifier = Modifier.testTag("site_auth_verify_button"),
                    )
                    SettingsSecondaryButton(
                        text = "重新登录",
                        icon = Icons.Rounded.Refresh,
                        enabled = phase.canRestart,
                        onClick = ::restartFlow,
                        modifier = Modifier.testTag("site_auth_restart_button"),
                    )
                    SettingsSecondaryButton(
                        text = "用浏览器打开",
                        icon = Icons.Outlined.OpenInBrowser,
                        onClick = ::openInExternalBrowser,
                        modifier = Modifier.testTag("site_auth_external_browser_button"),
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)

            key(webSessionNonce) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            createSecureWebView()?.let { web ->
                                addView(
                                    web,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onRelease = { container ->
                        (container.getChildAt(0) as? WebView)?.let(::releaseBrowser)
                    },
                )
            }
        }
    }

    /**
     * Official forum login requires JavaScript; ZERO native JS interfaces are exposed,
     * file/content access and third-party cookies are disabled, mixed content is blocked,
     * and every TLS error is rejected unconditionally.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createSecureWebView(): WebView? = try {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)

            // Install before loading any page. At document start Chromium may not have parsed
            // <head> yet, so attach the policy as soon as the parser creates it.
            WebViewCompat.addDocumentStartJavaScript(
                this,
                """
                    (() => {
                        if (window !== window.top) return;
                        const install = () => {
                            if (!document.head) return false;
                            const policy = document.createElement('meta');
                            policy.httpEquiv = 'Content-Security-Policy';
                            policy.content = ${org.json.JSONObject.quote(policy.formActionPolicy)};
                            document.head.prepend(policy);
                            return true;
                        };
                        if (!install()) {
                            const observer = new MutationObserver(() => {
                                if (install()) observer.disconnect();
                            });
                            observer.observe(document, {childList: true, subtree: true});
                        }
                    })();
                """.trimIndent(),
                setOf("*"),
            )

            webViewClient = object : WebViewClient() {
                // WebView skips shouldOverrideUrlLoading for form POSTs. Reject an unsafe
                // destination before the network stack receives the body.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!request.isForMainFrame && request.url.scheme == "https") return null
                    val lock = lockCoordinator.state.value
                    if (request.isForMainFrame && lock.lockInitialized && !lock.isLocked &&
                        policy.allowsMainFrameRequest(phase, request.method, request.url.toString())
                    ) return null
                    return WebResourceResponse(
                        "text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                        ByteArrayInputStream("登录请求已拦截".toByteArray()),
                    )
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (!request.isForMainFrame) return false
                    if (!isCurrent(view)) return true
                    val url = request.url.toString()
                    return when (policy.onMainFrameNavigation(phase, url)) {
                        SiteAuthNavigation.ALLOW -> false
                        SiteAuthNavigation.BLOCK -> true
                        SiteAuthNavigation.FAIL_TARGET -> {
                            view.stopLoading()
                            val reason = if (policy.isAllowedUrl(url)) {
                                "未能打开 ${plugin.descriptor.displayName} 权限验证页，可重试或重新登录。"
                            } else {
                                "目标页面跳转到非官方 HTTPS 地址，已停止加载。可重试或重新登录。"
                            }
                            failTarget(reason)
                            true
                        }
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (!isCurrent(view)) return
                    if (!policy.isAllowedUrl(url)) {
                        view.stopLoading()
                        failTarget("已拦截非官方登录地址。")
                        return
                    }
                    if (phase != SiteAuthPhase.LOADING_TARGET) return
                    if (policy.failsOnPageStarted(phase, url)) {
                        view.stopLoading()
                        val reason = if (policy.isAllowedUrl(url)) {
                            "未能打开 ${plugin.descriptor.displayName} 权限验证页，可重试或重新登录。"
                        } else {
                            "目标页面跳转到非官方 HTTPS 地址，已停止加载。可重试或重新登录。"
                        }
                        failTarget(reason)
                    } else {
                        targetVisitStarted = true
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!isCurrent(view) || view.url?.let { it != url } == true) return
                    when (phase) {
                        SiteAuthPhase.LOADING_TARGET -> {
                            if (!targetVisitStarted) {
                                if (!policy.isAllowedUrl(url)) {
                                    failTarget("未能打开 ${plugin.descriptor.displayName} 权限验证页，可重试或重新登录。")
                                }
                                return
                            }
                            if (policy.acceptsPage(phase, url)) {
                                phase = SiteAuthPhase.TARGET_READY
                                verifyTarget(view)
                            } else {
                                failTarget("未能打开 ${plugin.descriptor.displayName} 权限验证页，可重试或重新登录。")
                            }
                        }
                        SiteAuthPhase.FORUM -> {
                            if (!policy.acceptsPage(phase, url)) return
                            val values = collectLoginCookies()
                            if (policy.hasRequiredIdentity(values)) {
                                copyIdentityToTarget(view, values)
                            } else if (!forumErrorShown) {
                                statusMessage = "请在官方页面完成登录，然后点击“验证登录”。"
                            }
                        }
                        else -> Unit
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame) {
                        handleMainFrameError(
                            view = view,
                            url = request.url.toString(),
                            message = "官方页面返回 HTTP ${errorResponse.statusCode}。可重试、重新登录，或用浏览器登录后手动导入 Cookie。",
                        )
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        handleMainFrameError(
                            view = view,
                            url = request.url.toString(),
                            message = "官方页面暂时无法加载，请检查系统代理或网络后重试。",
                        )
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    // Hard security invariant: unconditionally reject every TLS certificate error.
                    handler.cancel()
                    if (!isCurrent(view)) return
                    val errUrl = error.url.orEmpty()
                    if (policy.handlesSslError(phase, errUrl, view.url, targetVisitStarted)) {
                        handleMainFrameError(
                            view = view,
                            url = errUrl,
                            message = "官方页面 TLS 证书校验失败，已立即中止连接。",
                        )
                    }
                }
            }
        }

        browser = web
        cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
        }
        restoreOrStart(web)
        web
    } catch (_: RuntimeException) {
        browser?.let(::releaseBrowser)
        phase = SiteAuthPhase.FORUM
        statusMessage = "系统 WebView 不可用，请更新 Android System WebView，或改用外部浏览器登录后手动导入 Cookie。"
        null
    }

    private fun restoreOrStart(web: WebView) {
        val restoredHistory = savedBrowserState?.takeUnless { phase == SiteAuthPhase.RESETTING }
            ?.let { runCatching { web.restoreState(it) }.getOrNull() }
        savedBrowserState = null

        when (phase) {
            SiteAuthPhase.RESETTING -> {
                cookieManager?.removeAllCookies {
                    if (isCurrent(web) && phase == SiteAuthPhase.RESETTING) {
                        phase = SiteAuthPhase.FORUM
                        web.loadUrl(authMode.loginUrl)
                    }
                } ?: run {
                    phase = SiteAuthPhase.FORUM
                    web.loadUrl(authMode.loginUrl)
                }
            }
            SiteAuthPhase.FORUM -> {
                val restoredUrl = restoredHistory?.currentItem?.url
                if (restoredUrl == null || !policy.isAllowedUrl(restoredUrl)) {
                    web.loadUrl(authMode.loginUrl)
                }
            }
            SiteAuthPhase.COPYING_IDENTITY -> copyIdentityToTarget(web, collectLoginCookies())
            SiteAuthPhase.LOADING_TARGET -> openTargetSite(web)
            SiteAuthPhase.TARGET_FAILED -> Unit
            SiteAuthPhase.TARGET_READY -> {
                if (restoredHistory?.currentItem == null) {
                    web.loadUrl(authMode.targetUrl)
                }
                if (resumeVerification) {
                    resumeVerification = false
                    verifyTarget(web)
                }
            }
            SiteAuthPhase.VERIFYING -> Unit
        }
    }

    private fun isCurrent(web: WebView): Boolean =
        browser === web && !isFinishing && !isDestroyed && !lockCoordinator.state.value.isLocked

    private fun handleMainFrameError(view: WebView, url: String, message: String) {
        if (!isCurrent(view) || !policy.handlesMainFrameError(phase, url, targetVisitStarted)) return
        if (phase == SiteAuthPhase.LOADING_TARGET) {
            failTarget(message)
        } else {
            forumErrorShown = true
            statusMessage = message
        }
    }

    private fun failTarget(message: String) {
        targetVisitStarted = false
        phase = SiteAuthPhase.TARGET_FAILED
        statusMessage = message
    }

    private fun restartFlow() {
        targetVisitStarted = false
        phase = SiteAuthPhase.RESETTING
        statusMessage = openingLoginMessage()
        forumErrorShown = false
        webSessionNonce += 1
    }

    private fun openInExternalBrowser() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, authMode.loginUrl.toUri()))
        }.onFailure {
            statusMessage = "未找到可用的外部浏览器，请更新系统 WebView 或手动导入 Cookie。"
        }
    }

    private fun collectLoginCookies(): Map<String, String> = policy.extractAllowedCookies(
        listOf(
            cookieManager?.getCookie(authMode.targetUrl),
            cookieManager?.getCookie(authMode.loginUrl),
        ),
    )

    private fun completeLogin() {
        val web = browser?.takeIf(::isCurrent) ?: return
        when (phase) {
            SiteAuthPhase.FORUM -> copyIdentityToTarget(web, collectLoginCookies())
            SiteAuthPhase.TARGET_FAILED -> retryTarget(web)
            SiteAuthPhase.TARGET_READY -> verifyTarget(web)
            else -> Unit
        }
    }

    private fun retryTarget(web: WebView) {
        val targetValues = policy.extractAllowedCookies(listOf(cookieManager?.getCookie(authMode.targetUrl)))
        if (policy.hasRequiredIdentity(targetValues)) {
            openTargetSite(web)
            return
        }
        val loginValues = collectLoginCookies()
        if (policy.hasRequiredIdentity(loginValues)) {
            copyIdentityToTarget(web, loginValues)
            return
        }
        targetVisitStarted = false
        phase = SiteAuthPhase.FORUM
        forumErrorShown = true
        statusMessage = "尚未取得登录会话，请先在官方页面完成登录。"
        web.stopLoading()
        web.loadUrl(authMode.loginUrl)
    }

    private fun copyIdentityToTarget(web: WebView, values: Map<String, String>) {
        val manager = cookieManager ?: return
        if (!policy.hasRequiredIdentity(values)) {
            phase = SiteAuthPhase.FORUM
            forumErrorShown = true
            statusMessage = "尚未取得完整登录会话，请先在官方页面完成登录。"
            return
        }
        phase = SiteAuthPhase.COPYING_IDENTITY
        statusMessage = "正在同步会话并检查 ${plugin.descriptor.displayName} 访问权限…"
        val requiredIdentity = values.filterKeys { it in authMode.requiredCookies }
        if (requiredIdentity.isEmpty()) {
            openTargetSite(web)
            return
        }
        var pending = requiredIdentity.size
        var writeFailed = false
        requiredIdentity.forEach { (name, value) ->
            manager.setCookie(authMode.targetUrl, "$name=$value; Path=/; Secure; HttpOnly") { written ->
                if (!isCurrent(web) || phase != SiteAuthPhase.COPYING_IDENTITY) return@setCookie
                writeFailed = writeFailed || !written
                if (--pending == 0) {
                    if (writeFailed) {
                        phase = SiteAuthPhase.FORUM
                        forumErrorShown = true
                        statusMessage = "无法写入临时登录会话，请重试或改用手动导入 Cookie。"
                    } else {
                        openTargetSite(web)
                    }
                }
            }
        }
    }

    private fun openTargetSite(web: WebView) {
        targetVisitStarted = false
        phase = SiteAuthPhase.LOADING_TARGET
        statusMessage = "正在加载 ${plugin.descriptor.displayName} 权限校验页…"
        web.stopLoading()
        web.loadUrl(authMode.targetUrl)
    }

    private fun verifyTarget(web: WebView) {
        val currentTargetUrl = web.url?.takeIf { policy.isTargetUrl(it) }
        val values = policy.extractAllowedCookies(
            listOf(
                cookieManager?.getCookie(authMode.targetUrl),
                currentTargetUrl?.let { cookieManager?.getCookie(it) },
            ),
        )
        if (!policy.hasRequiredIdentity(values)) {
            statusMessage = "尚未取得 ${plugin.descriptor.displayName} 必需会话 Cookie，请重新登录。"
            return
        }

        phase = SiteAuthPhase.VERIFYING
        statusMessage = "正在通过加密网络通道验证 ${plugin.descriptor.displayName} 权限…"
        verificationJob = lifecycleScope.launch {
            try {
                withContext(dispatchers.io) {
                    plugin.authProvider.importCookies(values)
                }
                if (isCurrent(web)) {
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                if (!isCurrent(web)) return@launch
                phase = SiteAuthPhase.TARGET_READY
                statusMessage = when (error) {
                    is SiteException.AuthRequired -> {
                        "官方登录已完成，但会话无效或此账号暂无 ${plugin.descriptor.displayName} 访问权限。可重新登录或手动导入有效 Cookie。"
                    }
                    else -> error.message ?: "会话验证失败，请重试。"
                }
            }
        }
    }

    private fun releaseBrowser(web: WebView) {
        if (browser === web) {
            browser = null
            verificationJob?.cancel()
            verificationJob = null
            if (phase == SiteAuthPhase.VERIFYING) {
                phase = SiteAuthPhase.TARGET_READY
                resumeVerification = true
            }
        }
        runCatching {
            web.stopLoading()
            web.clearCache(true)
            web.clearHistory()
            web.clearFormData()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PHASE, phase.name)
        outState.putString(STATE_STATUS, statusMessage)
        outState.putBoolean(STATE_FORUM_ERROR, forumErrorShown)
        val webBundle = Bundle()
        if (browser?.saveState(webBundle) != null) {
            outState.putBundle(STATE_BROWSER, webBundle)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        browser?.let(::releaseBrowser)
        if (isFinishing) {
            runCatching { WebStorage.getInstance().deleteAllData() }
            (cookieManager ?: runCatching { CookieManager.getInstance() }.getOrNull())?.let { manager ->
                runCatching {
                    manager.removeAllCookies { manager.flush() }
                }
            }
        }
        cookieManager = null
        super.onDestroy()
    }

    private fun openingLoginMessage(): String = "正在打开官方登录页…"

    private fun phaseLabel(current: SiteAuthPhase): String = when (current) {
        SiteAuthPhase.RESETTING -> "准备环境"
        SiteAuthPhase.FORUM -> "等待登录"
        SiteAuthPhase.COPYING_IDENTITY -> "同步凭据"
        SiteAuthPhase.LOADING_TARGET -> "检查权限"
        SiteAuthPhase.TARGET_FAILED -> "权限页异常"
        SiteAuthPhase.TARGET_READY -> "待最终验证"
        SiteAuthPhase.VERIFYING -> "校验中"
    }

    companion object {
        const val EXTRA_SITE_ID: String = "com.raven.app.extra.AUTH_SITE_ID"
        private const val STATE_PHASE: String = "phase"
        private const val STATE_STATUS: String = "status"
        private const val STATE_FORUM_ERROR: String = "forum_error"
        private const val STATE_BROWSER: String = "browser"

        fun createIntent(context: Context, siteId: SiteId): Intent =
            Intent(context, SiteAuthActivity::class.java)
                .putExtra(EXTRA_SITE_ID, siteId.value)
    }
}
