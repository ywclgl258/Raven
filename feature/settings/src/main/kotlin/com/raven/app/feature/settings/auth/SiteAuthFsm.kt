package com.raven.app.feature.settings.auth

import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.sitespi.AuthMode
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Navigation decision returned by the 7-state WebView auth FSM for every main-frame request.
 */
enum class SiteAuthNavigation {
    ALLOW,
    BLOCK,
    FAIL_TARGET,
}

/**
 * 7-state official WebView authentication FSM matching `ARCHITECTURE.md` §5.1 / §8.10:
 * `RESETTING -> FORUM -> COPYING_IDENTITY -> LOADING_TARGET -> TARGET_READY / TARGET_FAILED -> VERIFYING`.
 */
enum class SiteAuthPhase(
    val canVerify: Boolean = false,
    val canRestart: Boolean = true,
) {
    RESETTING(canVerify = false, canRestart = false),
    FORUM(canVerify = true, canRestart = true),
    COPYING_IDENTITY(canVerify = false, canRestart = true),
    LOADING_TARGET(canVerify = false, canRestart = true),
    TARGET_FAILED(canVerify = true, canRestart = true),
    TARGET_READY(canVerify = true, canRestart = true),
    VERIFYING(canVerify = false, canRestart = false);

    fun afterRecreation(): SiteAuthPhase =
        if (this == VERIFYING) TARGET_READY else this
}

/**
 * Pure-Kotlin, zero-site-branching security policy & transition evaluator for [AuthMode.WebViewCookie]
 * and [AuthMode.CookieImport] flows.
 */
class SiteAuthPolicy(
    val descriptor: SiteDescriptor,
    val loginUrl: String,
    val targetUrl: String,
    allowedHosts: Set<String>,
    val requiredCookies: Set<String>,
    val optionalCookies: Set<String> = emptySet(),
) {
    constructor(
        descriptor: SiteDescriptor,
        mode: AuthMode.WebViewCookie,
    ) : this(
        descriptor = descriptor,
        loginUrl = mode.loginUrl,
        targetUrl = mode.targetUrl,
        allowedHosts = mode.allowedHosts,
        requiredCookies = mode.requiredCookies,
        optionalCookies = mode.optionalCookies,
    )

    val normalizedAllowedHosts: Set<String> = allowedHosts
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .toSet()

    val allowedCookieNames: Set<String> = requiredCookies + optionalCookies

    private val targetHost: String? = targetUrl.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT)

    /**
     * Strictly enforces HTTPS scheme and membership in [normalizedAllowedHosts].
     * Cleartext HTTP, `javascript:`, `file:`, `data:`, `content:`, and off-domain hosts are always rejected.
     */
    fun isAllowedUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        return parsed.host.lowercase(Locale.ROOT) in normalizedAllowedHosts
    }

    /**
     * True only when [url] is an allowed HTTPS URL belonging to the target site's host(s).
     */
    fun isTargetUrl(url: String): Boolean {
        if (!isAllowedUrl(url)) return false
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) ?: return false
        return descriptor.matchesHost(host) || (targetHost != null && host == targetHost)
    }

    fun acceptsPage(phase: SiteAuthPhase, url: String): Boolean {
        if (!isAllowedUrl(url)) return false
        return when (phase) {
            SiteAuthPhase.FORUM -> true
            SiteAuthPhase.LOADING_TARGET -> isTargetUrl(url)
            else -> false
        }
    }

    /**
     * Main-frame navigation and redirect guard. Official HTTPS hosts are never bypassed; during
     * [SiteAuthPhase.LOADING_TARGET], any off-target or non-official redirect transitions cleanly to
     * [SiteAuthNavigation.FAIL_TARGET].
     */
    fun onMainFrameNavigation(phase: SiteAuthPhase, url: String): SiteAuthNavigation {
        if (!isAllowedUrl(url)) {
            return if (phase == SiteAuthPhase.LOADING_TARGET) {
                SiteAuthNavigation.FAIL_TARGET
            } else {
                SiteAuthNavigation.BLOCK
            }
        }
        return when (phase) {
            SiteAuthPhase.LOADING_TARGET -> {
                if (acceptsPage(phase, url)) SiteAuthNavigation.ALLOW else SiteAuthNavigation.FAIL_TARGET
            }
            SiteAuthPhase.FORUM -> SiteAuthNavigation.ALLOW
            SiteAuthPhase.TARGET_READY -> {
                if (isTargetUrl(url)) SiteAuthNavigation.ALLOW else SiteAuthNavigation.BLOCK
            }
            SiteAuthPhase.RESETTING,
            SiteAuthPhase.COPYING_IDENTITY,
            SiteAuthPhase.TARGET_FAILED,
            SiteAuthPhase.VERIFYING,
            -> SiteAuthNavigation.BLOCK
        }
    }

    /**
     * Decides whether a main-frame network or HTTP error belongs to the active FSM phase.
     */
    fun handlesMainFrameError(
        phase: SiteAuthPhase,
        url: String,
        targetVisitStarted: Boolean,
    ): Boolean = when (phase) {
        SiteAuthPhase.LOADING_TARGET -> acceptsPage(phase, url) || targetVisitStarted || !isAllowedUrl(url)
        SiteAuthPhase.FORUM -> acceptsPage(phase, url)
        else -> false
    }

    /**
     * Decides whether a cancelled TLS/SSL error belongs to the active main-frame navigation.
     */
    fun handlesSslError(
        phase: SiteAuthPhase,
        errorUrl: String,
        currentUrl: String?,
        targetVisitStarted: Boolean,
    ): Boolean = when (phase) {
        SiteAuthPhase.LOADING_TARGET -> {
            acceptsPage(phase, errorUrl) || (targetVisitStarted && errorUrl == currentUrl)
        }
        SiteAuthPhase.FORUM -> {
            acceptsPage(phase, errorUrl) && (
                errorUrl == currentUrl ||
                    currentUrl.isNullOrBlank() ||
                    errorUrl == loginUrl
                )
        }
        else -> false
    }

    fun failsOnPageStarted(phase: SiteAuthPhase, url: String): Boolean =
        phase == SiteAuthPhase.LOADING_TARGET && !acceptsPage(phase, url)

    /**
     * Parses raw `Cookie` headers or multi-line `key=value` text, retaining ONLY keys declared in
     * [requiredCookies] + [optionalCookies] with clean, control-character-free values.
     */
    fun extractAllowedCookies(rawHeaders: List<String?>): Map<String, String> =
        parseAndFilterCookies(rawHeaders, allowedCookieNames)

    /**
     * Verifies all [requiredCookies] exist and meet format invariants (including numeric
     * `ipb_member_id > 0` and valid `ipb_pass_hash` token shape whenever declared required).
     */
    fun hasRequiredIdentity(cookies: Map<String, String>): Boolean =
        validateRequiredCookies(cookies, requiredCookies).isEmpty()

    /**
     * Returns missing or malformed required cookie descriptions (empty list when valid).
     */
    fun missingOrInvalidRequiredCookies(cookies: Map<String, String>): List<String> =
        validateRequiredCookies(cookies, requiredCookies)

    companion object {
        private val PASS_HASH_REGEX = Regex("^[A-Za-z0-9_-]{16,128}$")

        fun parseAndFilterCookies(
            rawInputs: List<String?>,
            allowedNames: Set<String>,
        ): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (raw in rawInputs) {
                if (raw.isNullOrBlank()) continue
                val stripped = raw.trim().removePrefix("Cookie:").removePrefix("cookie:").trim()
                val tokens = stripped.split(';', '\n', '\r')
                for (token in tokens) {
                    val trimmed = token.trim()
                    if (trimmed.isEmpty()) continue
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx <= 0) continue
                    val name = trimmed.substring(0, eqIdx).trim()
                    val value = trimmed.substring(eqIdx + 1).trim()
                    if (name in allowedNames && isSafeCookieValue(value)) {
                        result[name] = value
                    }
                }
            }
            return result
        }

        fun validateRequiredCookies(
            cookies: Map<String, String>,
            requiredNames: Set<String>,
        ): List<String> {
            val issues = mutableListOf<String>()
            for (name in requiredNames) {
                val value = cookies[name]?.trim().orEmpty()
                if (value.isEmpty()) {
                    issues += "缺少必需 Cookie：$name"
                    continue
                }
                when (name) {
                    "ipb_member_id" -> {
                        val parsed = value.toLongOrNull()
                        if (parsed == null || parsed <= 0L) {
                            issues += "ipb_member_id 必须为正整数"
                        }
                    }
                    "ipb_pass_hash" -> {
                        if (!PASS_HASH_REGEX.matches(value)) {
                            issues += "ipb_pass_hash 格式无效（需为 16–128 位字母/数字/下划线/连字符）"
                        }
                    }
                }
            }
            return issues
        }

        private fun isSafeCookieValue(value: String): Boolean =
            value.isNotEmpty() &&
                value.length <= 4096 &&
                value.none { ch -> ch.code < 0x20 || ch.code == 0x7F || ch == ';' }
    }
}
