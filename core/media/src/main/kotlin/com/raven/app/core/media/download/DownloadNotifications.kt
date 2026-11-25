package com.raven.app.core.media.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raven.app.core.media.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shared notification channel management, pure progress-formatting helpers, and completion/failure
 * notification emitters for both video ([VideoDownloadService]) and gallery ([GalleryDownloadService])
 * offline downloads.
 */
object DownloadNotifications {
    const val CHANNEL_VIDEO_PROGRESS: String = VideoDownloadService.NOTIFICATION_CHANNEL_ID
    const val CHANNEL_GALLERY_PROGRESS: String = GalleryDownloadService.NOTIFICATION_CHANNEL_ID
    const val CHANNEL_DOWNLOAD_EVENTS: String = "raven-download-events"

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1_024L -> "$safe B"
            safe < 1_024L * 1_024L -> String.format(Locale.ROOT, "%.1f KB", safe / 1_024.0)
            safe < 1_024L * 1_024L * 1_024L -> String.format(Locale.ROOT, "%.1f MB", safe / (1_024.0 * 1_024.0))
            else -> String.format(Locale.ROOT, "%.2f GB", safe / (1_024.0 * 1_024.0 * 1_024.0))
        }
    }

    fun computeVideoProgressFraction(
        percentDownloaded: Float,
        bytesDownloaded: Long,
        contentLength: Long,
        isCompleted: Boolean,
        fallback: Float = 0f,
    ): Float {
        if (isCompleted) return 1f
        if (percentDownloaded >= 0f && percentDownloaded.isFinite()) {
            return (percentDownloaded / 100f).coerceIn(0f, 1f)
        }
        if (contentLength > 0L && bytesDownloaded > 0L) {
            return (bytesDownloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 0.99f)
        }
        return fallback.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    }

    fun formatVideoProgressText(
        title: String,
        percentDownloaded: Float,
        bytesDownloaded: Long,
        contentLength: Long,
        waitingForNetwork: Boolean,
    ): String {
        val cleanTitle = title.ifBlank { "视频任务" }
        if (waitingForNetwork) {
            return "等待网络条件 · $cleanTitle"
        }
        val safeBytes = bytesDownloaded.coerceAtLeast(0L)
        val hasKnownPercent = percentDownloaded >= 0f && percentDownloaded.isFinite()
        val computedPercent = when {
            hasKnownPercent -> percentDownloaded.roundToInt().coerceIn(0, 100)
            contentLength > 0L && safeBytes > 0L ->
                ((safeBytes.toDouble() / contentLength.toDouble()) * 100.0).roundToInt().coerceIn(0, 99)
            else -> -1
        }
        return when {
            computedPercent >= 0 && contentLength > 0L ->
                "$cleanTitle · $computedPercent% · ${formatBytes(safeBytes)} / ${formatBytes(contentLength)}"
            computedPercent >= 0 ->
                "$cleanTitle · $computedPercent% · 已下载 ${formatBytes(safeBytes)}"
            safeBytes > 0L ->
                "$cleanTitle · 已下载 ${formatBytes(safeBytes)}"
            else ->
                "$cleanTitle · 正在连接…"
        }
    }

    fun formatGalleryProgressText(
        title: String,
        completedUnits: Int,
        totalUnits: Int,
        downloadedBytes: Long,
        storageError: String?,
    ): String {
        if (!storageError.isNullOrBlank()) {
            return "存储写入失败：$storageError"
        }
        val cleanTitle = title.ifBlank { "图集任务" }
        val safeTotal = totalUnits.coerceAtLeast(0)
        val safeDone = completedUnits.coerceIn(0, safeTotal.coerceAtLeast(completedUnits.coerceAtLeast(0)))
        val pct = if (safeTotal > 0) ((safeDone.toFloat() / safeTotal.toFloat()) * 100f).roundToInt().coerceIn(0, 100) else 0
        val base = if (safeTotal > 0) {
            "$cleanTitle · $safeDone / $safeTotal 页 ($pct%)"
        } else {
            "$cleanTitle · 正在准备页面"
        }
        return if (downloadedBytes > 0L) {
            "$base · ${formatBytes(downloadedBytes)}"
        } else {
            base
        }
    }

    fun stableEventNotificationId(downloadId: String): Int {
        val positiveHash = downloadId.hashCode() and 0x7FFF_FFFF
        return 10_000 + (positiveHash % 100_000)
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_VIDEO_PROGRESS,
                context.getString(R.string.raven_video_download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_GALLERY_PROGRESS,
                context.getString(R.string.raven_gallery_download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_DOWNLOAD_EVENTS,
                context.getString(R.string.raven_download_events_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        channels.forEach(nm::createNotificationChannel)
    }

    fun buildOpenDownloadsPendingIntent(
        context: Context,
        requestCode: Int,
    ): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(VideoDownloadService.EXTRA_OPEN_DOWNLOADS, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return null
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    fun notifyDownloadCompleted(
        context: Context?,
        downloadId: String,
        title: String,
        isVideo: Boolean,
        detail: String? = null,
    ) {
        val appContext = context?.applicationContext ?: return
        if (!canPostNotifications(appContext)) return
        ensureChannels(appContext)
        val notificationId = stableEventNotificationId(downloadId)
        val kindLabel = if (isVideo) "视频离线下载完成" else "图集离线下载完成"
        val cleanTitle = title.ifBlank { if (isVideo) "离线视频" else "离线图集" }
        val body = if (!detail.isNullOrBlank()) "$cleanTitle · $detail" else cleanTitle
        val notification = NotificationCompat.Builder(appContext, CHANNEL_DOWNLOAD_EVENTS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(kindLabel)
            .setContentText(body)
            .setContentIntent(buildOpenDownloadsPendingIntent(appContext, notificationId))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(appContext).notify(notificationId, notification) }
    }

    @SuppressLint("MissingPermission")
    fun notifyDownloadFailed(
        context: Context?,
        downloadId: String,
        title: String,
        isVideo: Boolean,
        errorDetail: String? = null,
    ) {
        val appContext = context?.applicationContext ?: return
        if (!canPostNotifications(appContext)) return
        ensureChannels(appContext)
        val notificationId = stableEventNotificationId(downloadId)
        val kindLabel = if (isVideo) "视频离线下载失败" else "图集离线下载失败"
        val cleanTitle = title.ifBlank { if (isVideo) "离线视频" else "离线图集" }
        val body = if (!errorDetail.isNullOrBlank()) "$cleanTitle · $errorDetail" else cleanTitle
        val notification = NotificationCompat.Builder(appContext, CHANNEL_DOWNLOAD_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(kindLabel)
            .setContentText(body)
            .setContentIntent(buildOpenDownloadsPendingIntent(appContext, notificationId))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(appContext).notify(notificationId, notification) }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
