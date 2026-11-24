package com.raven.app.core.media.download

import com.raven.app.core.media.reader.GalleryStorageException
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.toDownloadErrorCategory
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class DownloadStage {
    PREPARING,
    RESOLVING_PAGE,
    DOWNLOADING_PAGE,
    COMMITTING,
}

data class ClassifiedDownloadFailure(
    val category: DownloadErrorCategory,
    val message: String,
    val retryable: Boolean,
    val retryAtEpochMs: Long? = null,
)

/**
 * Maps typed [SiteException] and I/O/TLS/Storage errors into [DownloadErrorCategory],
 * retryability, backoff intervals, and `Retry-After` `RETRY_WAIT` deadlines.
 */
object DownloadErrorClassifier {
    const val MAX_ATTEMPTS: Int = 3
    const val MAX_AUTO_WAIT_MS: Long = 30_000L
    const val MAX_RETRY_AFTER_MS: Long = 7L * 24L * 60L * 60L * 1_000L

    fun classify(
        error: Throwable,
        stage: DownloadStage = DownloadStage.DOWNLOADING_PAGE,
        nowMs: Long = System.currentTimeMillis(),
    ): ClassifiedDownloadFailure {
        val rawMessage = error.message?.trim().orEmpty()

        if (error is SiteException) {
            return classifySiteException(error, rawMessage, nowMs)
        }

        if (error is GalleryStorageException || stage == DownloadStage.COMMITTING) {
            return ClassifiedDownloadFailure(
                category = DownloadErrorCategory.STORAGE,
                message = rawMessage.ifBlank { "无法写入下载目录，请检查存储空间和权限" },
                retryable = false,
            )
        }

        if (hasCertificateOrSecurityFailure(error)) {
            if (isTlsConnectionInterruption(error)) {
                return ClassifiedDownloadFailure(
                    category = DownloadErrorCategory.NETWORK,
                    message = rawMessage.ifBlank { "TLS 连接中断，请检查网络后重试" },
                    retryable = true,
                )
            }
            return ClassifiedDownloadFailure(
                category = DownloadErrorCategory.TLS,
                message = "安全连接校验失败，请检查系统时间、证书和网络；未关闭证书验证",
                retryable = false,
            )
        }

        if (error is SSLException || error is IOException) {
            return ClassifiedDownloadFailure(
                category = DownloadErrorCategory.NETWORK,
                message = rawMessage.ifBlank { "网络连接中断，请稍后重试" },
                retryable = true,
            )
        }

        return ClassifiedDownloadFailure(
            category = DownloadErrorCategory.UNKNOWN,
            message = rawMessage.ifBlank { "下载失败，请重试" },
            retryable = false,
        )
    }

    private fun classifySiteException(
        error: SiteException,
        rawMessage: String,
        nowMs: Long,
    ): ClassifiedDownloadFailure {
        val mapped = error.toDownloadErrorCategory()
        return when (error) {
            is SiteException.RateLimited -> {
                val waitMs = error.retryAfterSeconds
                    ?.coerceAtLeast(0L)
                    ?.let { (it * 1_000L).coerceAtMost(MAX_RETRY_AFTER_MS) }
                ClassifiedDownloadFailure(
                    category = DownloadErrorCategory.RATE_LIMITED,
                    message = rawMessage.ifBlank { "原站请求过于频繁，请稍后重试" },
                    retryable = true,
                    retryAtEpochMs = waitMs?.let { nowMs + it },
                )
            }
            is SiteException.Network -> ClassifiedDownloadFailure(
                category = DownloadErrorCategory.NETWORK,
                message = rawMessage.ifBlank { "网络连接失败，请稍后重试" },
                retryable = true,
            )
            is SiteException.TlsError -> {
                val transient = isTlsConnectionInterruption(error.cause ?: error)
                if (transient) {
                    ClassifiedDownloadFailure(
                        category = DownloadErrorCategory.NETWORK,
                        message = rawMessage.ifBlank { "TLS 连接中断，请稍后重试" },
                        retryable = true,
                    )
                } else {
                    ClassifiedDownloadFailure(
                        category = DownloadErrorCategory.TLS,
                        message = rawMessage.ifBlank { "TLS 安全连接校验失败" },
                        retryable = false,
                    )
                }
            }
            is SiteException.Forbidden -> ClassifiedDownloadFailure(
                category = mapped,
                message = rawMessage.ifBlank {
                    if (error.isChallenge) "站点触发验证挑战，请稍后重试" else "访问权限不足，请检查账号或网络"
                },
                retryable = error.isChallenge,
            )
            is SiteException.AuthRequired -> ClassifiedDownloadFailure(
                category = DownloadErrorCategory.AUTH_REQUIRED,
                message = rawMessage.ifBlank { "需要登录验证" },
                retryable = false,
            )
            is SiteException.NotFound -> ClassifiedDownloadFailure(
                category = DownloadErrorCategory.NOT_FOUND,
                message = rawMessage.ifBlank { "资源不存在或已被移除" },
                retryable = false,
            )
            is SiteException.QuotaExceeded -> ClassifiedDownloadFailure(
                category = DownloadErrorCategory.QUOTA_EXCEEDED,
                message = rawMessage.ifBlank { "原站图片或流量配额已用尽" },
                retryable = false,
            )
            is SiteException.ParseError,
            is SiteException.Unsupported,
            -> ClassifiedDownloadFailure(
                category = DownloadErrorCategory.SOURCE_CHANGED,
                message = rawMessage.ifBlank { "原站页面结构或资源已变化" },
                retryable = false,
            )
            is SiteException.Cancelled -> ClassifiedDownloadFailure(
                category = DownloadErrorCategory.UNKNOWN,
                message = rawMessage.ifBlank { "任务已取消" },
                retryable = false,
            )
        }
    }

    fun isTlsConnectionInterruption(error: Throwable): Boolean {
        val chain = causeChain(error)
        if (chain.none { it is SSLException }) return false
        if (chain.any { it is GeneralSecurityException || it is SSLPeerUnverifiedException }) return false
        if (chain.any { it is SocketException || it is SocketTimeoutException || it is EOFException }) return true
        val messages = chain.filterIsInstance<SSLHandshakeException>()
            .mapNotNull { it.message?.lowercase(Locale.US) }
        return messages.any { msg ->
            TRANSIENT_TLS_MARKERS.any(msg::contains)
        }
    }

    fun backoffMs(attempt: Int): Long = when (attempt.coerceAtLeast(1)) {
        1 -> 1_000L
        2 -> 2_000L
        else -> 4_000L
    }

    /**
     * Returns the inline delay (ms) if automatic in-worker retry should proceed, or `null` when
     * the failure is non-retryable or the server requested a wait longer than [MAX_AUTO_WAIT_MS]
     * (in which case the caller transitions the download to `RETRY_WAIT`).
     */
    fun automaticDelayMs(
        failure: ClassifiedDownloadFailure,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        if (!failure.retryable) return null
        val retryAt = failure.retryAtEpochMs ?: return 0L
        val waitMs = (retryAt - nowMs).coerceAtLeast(0L)
        return if (waitMs > MAX_AUTO_WAIT_MS) null else waitMs
    }

    fun parseRetryAfterMs(raw: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { seconds ->
            return (seconds * 1_000L).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }
        for (pattern in HTTP_DATE_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }.parse(value)
            }.getOrNull() ?: continue
            return (parsed.time - nowMs).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }
        return null
    }

    private fun hasCertificateOrSecurityFailure(error: Throwable): Boolean =
        causeChain(error).any {
            it is SSLHandshakeException || it is GeneralSecurityException || it is SSLPeerUnverifiedException
        }

    private fun causeChain(error: Throwable): List<Throwable> =
        generateSequence(error) { it.cause?.takeUnless { next -> next === it } }
            .take(12)
            .toList()

    private val TRANSIENT_TLS_MARKERS = listOf(
        "readhandshakerecord",
        "connection reset",
        "software caused connection abort",
        "connection timed out",
        "socket closed",
        "broken pipe",
    )

    private val HTTP_DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )
}
