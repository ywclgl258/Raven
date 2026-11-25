package com.raven.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raven.app.core.designsystem.component.RavenBottomSheet
import com.raven.app.core.designsystem.component.RavenEmptyState
import com.raven.app.core.designsystem.component.RavenGroupedDivider
import com.raven.app.core.designsystem.component.RavenGroupedRow
import com.raven.app.core.designsystem.component.RavenGroupedSection
import com.raven.app.core.designsystem.component.RavenStatusPill
import com.raven.app.core.designsystem.component.RavenStatusTone
import com.raven.app.core.designsystem.component.RavenTopBar
import com.raven.app.core.designsystem.theme.LocalRavenColors
import com.raven.app.core.designsystem.theme.RavenShapes
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.sitespi.AuthMode
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SiteAccountScreen(
    route: RavenRoute.SiteAccount,
    navigator: SettingsNavigator,
    modifier: Modifier = Modifier,
    viewModel: SiteAccountViewModel = koinViewModel(
        key = "site_account_${route.siteId}",
        parameters = { parametersOf(route) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshAuthState()
    }

    SiteAccountScreenContent(
        uiState = uiState,
        navigator = navigator,
        onUsernameChange = viewModel::updateUsername,
        onPasswordChange = viewModel::updatePassword,
        onLogin = viewModel::loginWithCredentials,
        onCookieInputChange = viewModel::updateCookieInput,
        onImportCookies = { viewModel.importManualCookies() },
        onLogout = viewModel::logout,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteAccountScreenContent(
    uiState: SiteAccountUiState,
    navigator: SettingsNavigator,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onCookieInputChange: (String) -> Unit,
    onImportCookies: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRavenColors.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var showCookieImportSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.successMessage, uiState.isSubmitting) {
        if (!uiState.isSubmitting && !uiState.successMessage.isNullOrBlank()) {
            showCookieImportSheet = false
        }
    }

    // Apply FLAG_SECURE whenever sensitive credentials or cookie import inputs are on screen.
    SecureWindowEffect(enabled = uiState.supportsCredentialForm || showCookieImportSheet)

    val site = uiState.site
    val authModeLabel = when (uiState.authMode) {
        AuthMode.None -> "无需登录"
        AuthMode.FormCredentials -> "站点表单会话"
        AuthMode.JwtBearer -> "Bearer JWT 令牌"
        is AuthMode.WebViewCookie -> "官方 WebView / Cookie"
        is AuthMode.CookieImport -> "Cookie 导入"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .testTag("site_account_screen"),
    ) {
        RavenTopBar(
            title = site?.let { "${it.displayName} 账号" } ?: "站点账号",
            subtitle = site?.defaultHost ?: uiState.siteId.value,
            navigationIcon = {
                IconButton(
                    onClick = navigator::navigateBack,
                    modifier = Modifier.testTag("site_account_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.inkPrimary,
                    )
                }
            },
        )

        if (site == null || uiState.authMode == AuthMode.None) {
            RavenEmptyState(
                title = if (site == null) "未找到站点" else "${site.displayName} 无需登录",
                message = if (site == null) {
                    "未能识别站点标识 ${uiState.siteId.value}。"
                } else {
                    "该站点无需账号登录即可完整浏览与阅读。"
                },
                icon = Icons.Outlined.Public,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // Status Hero Card
                item(key = "account_status_card") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RavenShapes.CardShape)
                            .background(colors.surface1)
                            .border(0.5.dp, colors.hairline, RavenShapes.CardShape)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = site.displayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.inkPrimary,
                                )
                                Text(
                                    text = site.defaultHost,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkSecondary,
                                )
                            }
                            RavenStatusPill(
                                text = if (uiState.authState.isAuthenticated) "已登录" else "未登录",
                                tone = if (uiState.authState.isAuthenticated) {
                                    RavenStatusTone.SUCCESS
                                } else if (uiState.requiresAuthGate) {
                                    RavenStatusTone.WARNING
                                } else {
                                    RavenStatusTone.NEUTRAL
                                },
                                icon = if (uiState.authState.isAuthenticated) Icons.Outlined.VerifiedUser else Icons.Outlined.Lock,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RavenStatusPill(text = authModeLabel)
                            if (uiState.authState.isAuthenticated && !uiState.authState.username.isNullOrBlank()) {
                                RavenStatusPill(
                                    text = uiState.authState.username.orEmpty(),
                                    tone = RavenStatusTone.ACCENT,
                                )
                            }
                        }
                    }
                }

                // Feedback banners
                val errorMsg = uiState.errorMessage
                if (!errorMsg.isNullOrBlank()) {
                    item(key = "account_error_banner") {
                        SettingsNoticeBanner(
                            title = "认证未完成",
                            message = errorMsg,
                            tone = RavenStatusTone.ERROR,
                        )
                    }
                }
                val successMsg = uiState.successMessage
                if (!successMsg.isNullOrBlank()) {
                    item(key = "account_success_banner") {
                        SettingsNoticeBanner(
                            title = "会话已更新",
                            message = successMsg,
                            tone = RavenStatusTone.SUCCESS,
                        )
                    }
                }

                // Mode-adaptive body (zero siteId branching!)
                when (uiState.authMode) {
                    AuthMode.FormCredentials, AuthMode.JwtBearer -> {
                        item(key = "account_credential_form") {
                            RavenGroupedSection(
                                title = "账号登录",
                                footer = "本页面已启用 FLAG_SECURE 防截屏保护。密码仅用于当次向站点官方接口换取加密会话，绝不明文保存。",
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SettingsTextField(
                                        value = uiState.usernameInput,
                                        onValueChange = onUsernameChange,
                                        label = "用户名或邮箱",
                                        placeholder = "输入站点账号",
                                        enabled = !uiState.isSubmitting,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Email,
                                            imeAction = ImeAction.Next,
                                        ),
                                        modifier = Modifier.testTag("site_account_username_field"),
                                    )

                                    SettingsTextField(
                                        value = uiState.passwordInput,
                                        onValueChange = onPasswordChange,
                                        label = "密码",
                                        placeholder = "输入账号密码",
                                        enabled = !uiState.isSubmitting,
                                        isPassword = true,
                                        passwordVisible = passwordVisible,
                                        onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done,
                                        ),
                                        keyboardActions = KeyboardActions(onDone = { onLogin() }),
                                        modifier = Modifier.testTag("site_account_password_field"),
                                    )

                                    SettingsPrimaryButton(
                                        text = if (uiState.authState.isAuthenticated) "重新登录并刷新会话" else "登录",
                                        icon = Icons.AutoMirrored.Rounded.Login,
                                        loading = uiState.isSubmitting,
                                        enabled = uiState.usernameInput.isNotBlank() && uiState.passwordInput.isNotEmpty(),
                                        onClick = onLogin,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("site_account_login_button"),
                                    )
                                }
                            }
                        }
                    }

                    is AuthMode.WebViewCookie, is AuthMode.CookieImport -> {
                        item(key = "account_webview_cookie_actions") {
                            RavenGroupedSection(
                                title = "安全登录方式",
                                footer = "导入或官方登录后会先实时验证 ${site.displayName} 的访问权限（如 ExHentai 访问资格），验证通过后才写入加密会话库。",
                            ) {
                                if (uiState.supportsWebViewLogin) {
                                    RavenGroupedRow(
                                        title = "打开官方登录页（隔离 WebView）",
                                        subtitle = "启用防截屏保护与 HTTPS 白名单，退出时自动清除全部临时 WebView Cookie",
                                        icon = Icons.Outlined.Lock,
                                        iconTint = colors.crimson,
                                        showChevron = true,
                                        enabled = !uiState.isSubmitting,
                                        onClick = { navigator.launchWebViewAuth(site.id) },
                                        modifier = Modifier.testTag("site_account_launch_webview_button"),
                                    )
                                    RavenGroupedDivider(insetWithIcon = true)
                                }
                                RavenGroupedRow(
                                    title = "手动导入 Cookie",
                                    subtitle = "必需字段：${uiState.requiredCookieNames.joinToString(", ")}",
                                    icon = Icons.Outlined.Cookie,
                                    showChevron = true,
                                    enabled = !uiState.isSubmitting,
                                    onClick = { showCookieImportSheet = true },
                                    modifier = Modifier.testTag("site_account_open_cookie_sheet_button"),
                                )
                            }
                        }
                    }

                    AuthMode.None -> Unit
                }

                // Security & Isolation Details
                item(key = "account_security_notes") {
                    SettingsNoticeBanner(
                        title = "站点级凭据隔离",
                        message = "当前站点凭据与其它 18 个站点完全隔离，经 AndroidKeyStore AES-256-GCM 硬件密钥加密存储，绝不附加到图片/视频 CDN 或 GitHub 更新请求。",
                        icon = Icons.Outlined.Shield,
                        tone = RavenStatusTone.NEUTRAL,
                    )
                }

                // Isolated Logout Section
                if (uiState.authState.isAuthenticated) {
                    item(key = "account_logout_section") {
                        RavenGroupedSection(
                            title = "会话管理",
                            footer = "退出登录仅清除 ${site.displayName} 的会话数据，不会影响其他已登录站点。",
                        ) {
                            RavenGroupedRow(
                                title = "退出 ${site.displayName} 账号",
                                subtitle = "立即销毁本站加密 Cookie、令牌与用户名缓存",
                                icon = Icons.AutoMirrored.Rounded.Logout,
                                destructive = true,
                                enabled = !uiState.isSubmitting,
                                onClick = onLogout,
                                modifier = Modifier.testTag("site_account_logout_button"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCookieImportSheet && uiState.supportsCookieImport && site != null) {
        RavenBottomSheet(
            onDismissRequest = { if (!uiState.isSubmitting) showCookieImportSheet = false },
            presentation = com.raven.app.core.designsystem.component.RavenSheetPresentation.Center,
            title = "手动导入 ${site.displayName} Cookie",
            subtitle = "将先联网验证本站访问权限，确认有效后才加密保存",
            modifier = Modifier.testTag("site_account_cookie_import_sheet"),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val requiredText = uiState.requiredCookieNames.joinToString("、")
                val optionalText = uiState.optionalCookieNames.joinToString("、")
                SettingsNoticeBanner(
                    message = buildString {
                        append("必需字段：$requiredText")
                        if (optionalText.isNotBlank()) append("\n可选字段：$optionalText")
                        append("\n支持标准 Cookie 请求头（分号分隔）或每行一条 name=value。")
                    },
                    tone = RavenStatusTone.NEUTRAL,
                )

                SettingsTextField(
                    value = uiState.cookieInput,
                    onValueChange = onCookieInputChange,
                    label = "Cookie 内容",
                    placeholder = "ipb_member_id=...; ipb_pass_hash=...; igneous=...",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier.testTag("site_account_cookie_text_field"),
                )

                val sheetError = uiState.errorMessage
                if (!sheetError.isNullOrBlank()) {
                    SettingsNoticeBanner(
                        message = sheetError,
                        tone = RavenStatusTone.ERROR,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingsSecondaryButton(
                        text = "取消",
                        enabled = !uiState.isSubmitting,
                        onClick = { showCookieImportSheet = false },
                        modifier = Modifier.weight(1f),
                    )
                    SettingsPrimaryButton(
                        text = "验证并导入",
                        icon = Icons.Outlined.VerifiedUser,
                        loading = uiState.isSubmitting,
                        enabled = uiState.cookieInput.isNotBlank(),
                        onClick = {
                            onImportCookies()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("site_account_cookie_submit_button"),
                    )
                }
            }
        }
    }
}
