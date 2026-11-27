package com.raven.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.SiteId
import com.raven.app.core.sitespi.AuthMode
import com.raven.app.core.sitespi.AuthState
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import com.raven.app.feature.settings.auth.SiteAuthPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SiteAccountUiState(
    val siteId: SiteId,
    val site: SiteDescriptor? = null,
    val authMode: AuthMode = AuthMode.None,
    val requiresAuthGate: Boolean = false,
    /** Plugin implements credential POST even though its primary mode is WebView-based. */
    val credentialFallbackSupported: Boolean = false,
    val authState: AuthState = AuthState(isAuthenticated = false),
    val usernameInput: String = "",
    val passwordInput: String = "",
    val cookieInput: String = "",
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
) {
    val supportsCredentialForm: Boolean
        get() = authMode is AuthMode.FormCredentials ||
            authMode is AuthMode.JwtBearer ||
            // WebView-first sites may still expose a working credential fast path (e.g. Hanime1).
            credentialFallbackSupported

    val supportsWebViewLogin: Boolean
        get() = authMode is AuthMode.WebViewCookie

    val supportsCookieImport: Boolean
        get() = authMode is AuthMode.WebViewCookie || authMode is AuthMode.CookieImport

    val requiredCookieNames: Set<String>
        get() = when (val mode = authMode) {
            is AuthMode.WebViewCookie -> mode.requiredCookies
            is AuthMode.CookieImport -> mode.requiredCookies
            else -> emptySet()
        }

    val optionalCookieNames: Set<String>
        get() = when (val mode = authMode) {
            is AuthMode.WebViewCookie -> mode.optionalCookies
            is AuthMode.CookieImport -> mode.optionalCookies
            else -> emptySet()
        }
}

/**
 * Per-site account ViewModel adapting purely to [SitePlugin.authProvider] (`AuthMode`).
 * Strictly capability-driven with zero site-ID branching; logout clears ONLY the target [SiteId].
 */
class SiteAccountViewModel(
    val route: RavenRoute.SiteAccount,
    private val siteRegistry: SiteRegistry,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    val siteId: SiteId = SiteId.of(route.siteId)
    private val plugin: SitePlugin? = siteRegistry.get(siteId)

    private val _uiState = MutableStateFlow(
        SiteAccountUiState(
            siteId = siteId,
            site = plugin?.descriptor,
            authMode = plugin?.authProvider?.mode ?: AuthMode.None,
            requiresAuthGate = plugin?.authProvider?.requiresAuthGate == true,
            credentialFallbackSupported = plugin?.authProvider?.supportsCredentialFallback == true,
        ),
    )
    val uiState: StateFlow<SiteAccountUiState> = _uiState.asStateFlow()

    init {
        refreshAuthState()
    }

    fun refreshAuthState() {
        val targetPlugin = plugin ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val state = runCatching {
                withContext(dispatchers.io) {
                    targetPlugin.authProvider.currentAuthState()
                }
            }.getOrDefault(AuthState(isAuthenticated = false))
            _uiState.update { current ->
                current.copy(
                    authState = state,
                    usernameInput = if (current.usernameInput.isBlank() && !state.username.isNullOrBlank()) {
                        state.username.orEmpty()
                    } else {
                        current.usernameInput
                    },
                    isLoading = false,
                )
            }
        }
    }

    fun updateUsername(value: String) {
        _uiState.update { it.copy(usernameInput = value, errorMessage = null, successMessage = null) }
    }

    fun updatePassword(value: String) {
        _uiState.update { it.copy(passwordInput = value, errorMessage = null, successMessage = null) }
    }

    fun updateCookieInput(value: String) {
        _uiState.update { it.copy(cookieInput = value, errorMessage = null, successMessage = null) }
    }

    fun loginWithCredentials() {
        val targetPlugin = plugin ?: return
        val current = _uiState.value
        if (!current.supportsCredentialForm || current.isSubmitting) return

        val username = current.usernameInput.trim()
        val password = current.passwordInput
        if (username.isBlank() || password.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "请输入账号与密码") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, successMessage = null) }
            try {
                val state = withContext(dispatchers.io) {
                    targetPlugin.authProvider.loginWithCredentials(username, password)
                }
                _uiState.update {
                    it.copy(
                        authState = state,
                        usernameInput = state.username ?: username,
                        passwordInput = "",
                        isSubmitting = false,
                        errorMessage = null,
                        successMessage = "已成功登录 ${targetPlugin.descriptor.displayName}（会话已硬件级加密存储）",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = e.message ?: "登录失败，请检查账号密码或网络代理",
                    )
                }
            }
        }
    }

    /**
     * Parses, pre-validates required cookies, and delegates verification + persistence to
     * `plugin.authProvider.importCookies(...)` so remote permission checks (such as ExHentai access)
     * run BEFORE anything is written to `SessionVault`.
     */
    fun importManualCookies(rawOverride: String? = null) {
        val targetPlugin = plugin ?: return
        val current = _uiState.value
        if (!current.supportsCookieImport || current.isSubmitting) return

        val rawText = (rawOverride ?: current.cookieInput).trim()
        if (rawText.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "请粘贴有效的 Cookie 字符串") }
            return
        }

        val required = current.requiredCookieNames
        val allowed = required + current.optionalCookieNames
        val parsed = SiteAuthPolicy.parseAndFilterCookies(listOf(rawText), allowed)
        val validationIssues = SiteAuthPolicy.validateRequiredCookies(parsed, required)
        if (validationIssues.isNotEmpty()) {
            _uiState.update { it.copy(errorMessage = validationIssues.joinToString("；")) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, successMessage = null) }
            try {
                val verifiedState = withContext(dispatchers.io) {
                    targetPlugin.authProvider.importCookies(parsed)
                }
                _uiState.update {
                    it.copy(
                        authState = verifiedState,
                        cookieInput = "",
                        isSubmitting = false,
                        errorMessage = null,
                        successMessage = "已验证并安全导入 ${targetPlugin.descriptor.displayName} 会话",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: SiteException.AuthRequired) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = e.message.ifBlank {
                            "Cookie 校验未通过或账号暂无 ${targetPlugin.descriptor.displayName} 访问权限，未保存任何会话"
                        },
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = e.message ?: "Cookie 验证失败，请检查网络连接",
                    )
                }
            }
        }
    }

    /**
     * Logs out ONLY [siteId] without disturbing sessions belonging to any other site.
     */
    fun logout() {
        val targetPlugin = plugin ?: return
        if (_uiState.value.isSubmitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, successMessage = null) }
            try {
                val updatedState = withContext(dispatchers.io) {
                    targetPlugin.authProvider.logout()
                    targetPlugin.authProvider.currentAuthState()
                }
                _uiState.update {
                    it.copy(
                        authState = updatedState,
                        passwordInput = "",
                        cookieInput = "",
                        isSubmitting = false,
                        successMessage = "已退出 ${targetPlugin.descriptor.displayName} 账号，其余站点会话不受影响",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = e.message ?: "退出登录失败，请重试",
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
