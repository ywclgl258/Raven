package com.raven.app.core.model

/**
 * Strongly typed exception hierarchy for all site network, auth, rate-limit, and parsing failures.
 * Eliminates fragile string matching in download recovery, auth gates, and UI error states.
 */
sealed class SiteException(
    override val message: String,
    val siteId: SiteId? = null,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    class Network(
        message: String,
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)

    class RateLimited(
        val retryAfterSeconds: Long? = null,
        message: String = "Rate limited by remote host",
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)

    class AuthRequired(
        message: String = "Authentication required",
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)

    class Forbidden(
        val isChallenge: Boolean = false,
        message: String = "Access forbidden",
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)

    class NotFound(
        message: String = "Resource not found",
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)

    class QuotaExceeded(
        message: String = "Image or bandwidth quota exceeded",
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)

    class ParseError(
        message: String,
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)

    class TlsError(
        message: String,
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)

    class Unsupported(
        message: String,
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)

    class Cancelled(
        message: String = "Operation cancelled",
        siteId: SiteId? = null,
        cause: Throwable? = null,
    ) : SiteException(message, siteId, cause)
}

fun SiteException.toDownloadErrorCategory(): DownloadErrorCategory = when (this) {
    is SiteException.Network -> DownloadErrorCategory.NETWORK
    is SiteException.RateLimited -> DownloadErrorCategory.RATE_LIMITED
    is SiteException.AuthRequired -> DownloadErrorCategory.AUTH_REQUIRED
    is SiteException.Forbidden -> if (isChallenge) DownloadErrorCategory.RATE_LIMITED else DownloadErrorCategory.AUTH_REQUIRED
    is SiteException.NotFound -> DownloadErrorCategory.NOT_FOUND
    is SiteException.QuotaExceeded -> DownloadErrorCategory.QUOTA_EXCEEDED
    is SiteException.ParseError -> DownloadErrorCategory.SOURCE_CHANGED
    is SiteException.TlsError -> DownloadErrorCategory.TLS
    is SiteException.Unsupported -> DownloadErrorCategory.SOURCE_CHANGED
    is SiteException.Cancelled -> DownloadErrorCategory.UNKNOWN
}
