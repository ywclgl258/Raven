package com.raven.app.feature.library

import com.raven.app.core.database.model.SmartShelfEntry
import com.raven.app.core.designsystem.component.RavenFormatters
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteException
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.workId
import com.raven.app.core.model.workTitle
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.database.domain.CompletionPolicy
import com.raven.app.core.sitespi.SiteRegistry

data class SiteFilterOption(
    val siteId: SiteId,
    val label: String,
    val primaryKind: MediaKind? = null,
)

fun MediaKind.displayLabel(): String = when (this) {
    MediaKind.VIDEO -> "视频"
    MediaKind.COMIC -> "漫画"
    MediaKind.SERIAL_COMIC -> "连载漫画"
    MediaKind.PHOTO -> "写真"
}

fun FavoriteKind.displayLabel(): String = when (this) {
    FavoriteKind.VIDEO -> "视频"
    FavoriteKind.COMIC -> "漫画"
    FavoriteKind.PHOTO -> "写真"
    FavoriteKind.TAG -> "标签"
    FavoriteKind.AUTHOR -> "作者"
    FavoriteKind.CHARACTER -> "角色"
}

fun FavoriteKind.toMediaKindOrNull(): MediaKind? = when (this) {
    FavoriteKind.VIDEO -> MediaKind.VIDEO
    FavoriteKind.COMIC -> MediaKind.COMIC
    FavoriteKind.PHOTO -> MediaKind.PHOTO
    FavoriteKind.TAG, FavoriteKind.AUTHOR, FavoriteKind.CHARACTER -> null
}

fun DownloadKind.toMediaKind(): MediaKind = when (this) {
    DownloadKind.VIDEO -> MediaKind.VIDEO
    DownloadKind.COMIC -> MediaKind.COMIC
    DownloadKind.PHOTO -> MediaKind.PHOTO
}

fun DownloadKind.displayLabel(): String = toMediaKind().displayLabel()

fun DownloadStatus.displayLabel(): String = when (this) {
    DownloadStatus.QUEUED -> "等待中"
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.RETRY_WAIT -> "等待重试"
    DownloadStatus.COMPLETED -> "已完成"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.CANCELLED -> "已取消"
}

fun DownloadStatus.isInProgressBucket(): Boolean = when (this) {
    DownloadStatus.QUEUED,
    DownloadStatus.DOWNLOADING,
    DownloadStatus.PAUSED,
    DownloadStatus.RETRY_WAIT -> true
    DownloadStatus.COMPLETED,
    DownloadStatus.FAILED,
    DownloadStatus.CANCELLED -> false
}

fun DownloadStatus.isActiveTransfer(): Boolean =
    this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING || this == DownloadStatus.RETRY_WAIT

fun DownloadStatus.needsAttention(): Boolean =
    this == DownloadStatus.FAILED || this == DownloadStatus.CANCELLED

fun DownloadErrorCategory.displayLabel(): String = when (this) {
    DownloadErrorCategory.NETWORK -> "网络中断"
    DownloadErrorCategory.RATE_LIMITED -> "请求限流"
    DownloadErrorCategory.AUTH_REQUIRED -> "需要登录"
    DownloadErrorCategory.NOT_FOUND -> "资源已失效"
    DownloadErrorCategory.QUOTA_EXCEEDED -> "站点配额不足"
    DownloadErrorCategory.STORAGE -> "存储空间或写入异常"
    DownloadErrorCategory.TLS -> "安全证书异常"
    DownloadErrorCategory.SOURCE_CHANGED -> "来源结构变更"
    DownloadErrorCategory.UNKNOWN -> "未知错误"
}

fun SmartShelfRule.displayLabel(): String = when (this) {
    SmartShelfRule.UNFINISHED -> "未看完 / 未读完"
    SmartShelfRule.OFFLINE_UNSEEN -> "已离线未看"
    SmartShelfRule.RECENT -> "近 30 天新增"
}

fun SmartShelfRule.description(): String = when (this) {
    SmartShelfRule.UNFINISHED -> "自动汇聚已开始但尚未看完或读完的作品"
    SmartShelfRule.OFFLINE_UNSEEN -> "自动汇聚已离线缓存或本地导入、尚未开始的作品"
    SmartShelfRule.RECENT -> "自动汇聚近 30 天加入片单、收藏或下载的作品"
}

fun InboxSourceKind.displayLabel(): String = when (this) {
    InboxSourceKind.TAG_SUBSCRIPTION -> "标签订阅"
    InboxSourceKind.SAVED_SEARCH -> "保存的搜索"
}

fun SearchScope.displayLabel(): String = when (this) {
    SearchScope.ALL -> "全部"
    SearchScope.TAG -> "标签"
    SearchScope.AUTHOR -> "作者"
    SearchScope.CHARACTER -> "角色"
}

/**
 * Resolves a human-friendly site label dynamically via [SiteRegistry].
 */
fun SiteRegistry.displayNameFor(siteId: SiteId): String {
    if (siteId.isLocal) return "本地库"
    get(siteId)?.descriptor?.shortName?.takeIf { it.isNotBlank() }?.let { return it }
    return if (siteId.isRetired) "${siteId.value} (已归档)" else siteId.value
}

/**
 * Builds deterministic site filter options for any collection of present [SiteId]s using [SiteRegistry].
 */
fun SiteRegistry.buildSiteFilterOptions(
    presentSiteIds: Collection<SiteId>,
    includeAllRegistered: Boolean = false,
    kindFilter: MediaKind? = null,
): List<SiteFilterOption> {
    val ordered = linkedMapOf<SiteId, SiteFilterOption>()
    val descriptors = if (kindFilter == null) {
        allDescriptors()
    } else {
        allDescriptors().filter { kindFilter in it.supportedKinds || it.primaryKind == kindFilter }
    }
    if (includeAllRegistered) {
        for (descriptor in descriptors) {
            ordered[descriptor.id] = SiteFilterOption(
                siteId = descriptor.id,
                label = descriptor.shortName,
                primaryKind = descriptor.primaryKind,
            )
        }
        ordered[SiteId.LOCAL] = SiteFilterOption(
            siteId = SiteId.LOCAL,
            label = "本地库",
            primaryKind = null,
        )
    } else {
        val presentSet = presentSiteIds.toSet()
        for (descriptor in descriptors) {
            if (descriptor.id in presentSet) {
                ordered[descriptor.id] = SiteFilterOption(
                    siteId = descriptor.id,
                    label = descriptor.shortName,
                    primaryKind = descriptor.primaryKind,
                )
            }
        }
        for (siteId in presentSiteIds) {
            if (siteId !in ordered) {
                ordered[siteId] = SiteFilterOption(
                    siteId = siteId,
                    label = displayNameFor(siteId),
                    primaryKind = get(siteId)?.descriptor?.primaryKind,
                )
            }
        }
    }
    return ordered.values.toList()
}

/**
 * Pure Kotlin, API-24-compatible relative time label using epoch arithmetic.
 */
fun formatRelativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return ""
    val diffMs = (nowMs - epochMs).coerceAtLeast(0L)
    val minutes = diffMs / 60_000L
    val hours = diffMs / 3_600_000L
    val days = diffMs / 86_400_000L
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "${minutes} 分钟前"
        hours < 24L -> "${hours} 小时前"
        days < 30L -> "${days} 天前"
        days < 365L -> "${days / 30L} 个月前"
        else -> "${days / 365L} 年前"
    }
}

fun formatRetryCountdown(retryAtEpochMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (retryAtEpochMs == null || retryAtEpochMs <= nowMs) return "即将重试"
    val remainSeconds = ((retryAtEpochMs - nowMs + 999L) / 1000L).coerceAtLeast(1L)
    return if (remainSeconds < 60L) {
        "${remainSeconds} 秒后重试"
    } else {
        val mins = remainSeconds / 60L
        val secs = remainSeconds % 60L
        if (secs == 0L) "${mins} 分钟后重试" else "${mins} 分 ${secs} 秒后重试"
    }
}

fun WatchProgress.toMediaItem(): MediaItem = MediaItem(
    siteId = siteId,
    id = mediaId,
    url = url,
    kind = MediaKind.VIDEO,
    title = title,
    coverUrl = coverUrl,
    durationSeconds = if (durationMs > 0L) (durationMs / 1000L).toInt() else null,
)

fun ReadingProgress.toWorkMediaItem(): MediaItem = MediaItem(
    siteId = siteId,
    id = workId,
    url = seriesUrl?.takeIf { it.isNotBlank() } ?: url,
    kind = kind,
    title = workTitle,
    coverUrl = coverUrl,
    pageCount = totalPages.takeIf { it > 0 },
)

val ReadingProgress.isSerialWork: Boolean
    get() = seriesId != null

fun ReadingProgress.isWorkCompleted(): Boolean =
    !isSerialWork && CompletionPolicy.isReadingCompleted(this)

fun ReadingProgress.chapterCaption(): String? =
    if (isSerialWork) title else null

fun ReadingProgress.workResumeCaption(): String {
    val chapter = if (isSerialWork) "「$title」· " else ""
    return chapter + resumeCaption()
}

fun ReadingProgress.toMediaItem(): MediaItem = MediaItem(
    siteId = siteId,
    id = mediaId,
    url = url,
    kind = kind,
    title = title,
    coverUrl = coverUrl,
    pageCount = totalPages.takeIf { it > 0 },
)

fun ShelfItem.toMediaItem(): MediaItem = MediaItem(
    siteId = siteId,
    id = mediaId,
    url = url,
    kind = kind,
    title = title,
    coverUrl = coverUrl,
    durationSeconds = durationSeconds,
    pageCount = pageCount,
)

fun SmartShelfEntry.toMediaItem(): MediaItem = MediaItem(
    siteId = siteId,
    id = mediaId,
    url = url,
    kind = kind,
    title = title,
    coverUrl = coverUrl,
)

fun DownloadItem.toMediaItem(): MediaItem = MediaItem(
    siteId = siteId,
    id = mediaId,
    url = url,
    kind = kind.toMediaKind(),
    title = title,
    coverUrl = coverUrl,
    pageCount = if (kind != DownloadKind.VIDEO && totalUnits > 0) totalUnits else null,
)

fun MediaItem.toLibraryShelfItem(shelfId: String = Shelf.WATCH_LATER_ID): ShelfItem = ShelfItem(
    shelfId = shelfId,
    siteId = siteId,
    mediaId = id,
    url = url,
    kind = kind,
    title = title,
    coverUrl = coverUrl,
    durationSeconds = durationSeconds,
    pageCount = pageCount,
)

fun FavoriteItem.toShelfItemOrNull(shelfId: String = Shelf.WATCH_LATER_ID): ShelfItem? {
    val mediaKind = kind.toMediaKindOrNull() ?: return null
    return ShelfItem(
        shelfId = shelfId,
        siteId = siteId,
        mediaId = targetId,
        url = url,
        kind = mediaKind,
        title = title,
        coverUrl = coverUrl,
    )
}

fun FavoriteItem.toMediaItemOrNull(): MediaItem? {
    val mediaKind = kind.toMediaKindOrNull() ?: return null
    return MediaItem(
        siteId = siteId,
        id = targetId,
        url = url,
        kind = mediaKind,
        title = title,
        coverUrl = coverUrl,
    )
}

fun WatchProgress.resumeCaption(): String {
    val pos = RavenFormatters.formatDurationMs(positionMs)
    val dur = RavenFormatters.formatDurationMs(durationMs)
    return when {
        completed -> "已看完"
        dur.isNotEmpty() -> "已看 $pos / $dur"
        pos.isNotEmpty() -> "已看 $pos"
        else -> "继续观看"
    }
}

fun ReadingProgress.resumeCaption(): String {
    return when {
        completed -> "已读完"
        totalPages > 0 -> "第 ${RavenFormatters.formatPageProgress(currentPage, totalPages)} 页"
        currentPage > 0 -> "第 $currentPage 页"
        else -> "继续阅读"
    }
}

fun Throwable.toLibraryErrorMessage(): String = when (this) {
    is SiteException.AuthRequired -> message.ifBlank { "该站点需要登录后才能刷新" }
    is SiteException.RateLimited -> {
        val retry = retryAfterSeconds
        if (retry != null && retry > 0L) "请求受限，请 $retry 秒后重试" else "请求过于频繁，请稍后重试"
    }
    is SiteException.Network -> message.ifBlank { "网络连接失败，请检查网络或代理" }
    is SiteException.NotFound -> message.ifBlank { "目标内容不存在" }
    is SiteException.Unsupported -> message.ifBlank { "当前站点暂不支持此操作" }
    else -> message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
}

/**
 * Responsive column count for image/local library grids on phone & tablet layouts.
 */
fun computeLibraryGridColumns(widthDp: Int): Int = when {
    widthDp >= 1200 -> 5
    widthDp >= 840 -> 4
    widthDp >= 600 -> 3
    else -> 2
}
