package com.raven.app.core.database.domain

import com.raven.app.core.common.ContentFilter
import com.raven.app.core.database.model.SmartShelfEntry
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.WatchProgress

/**
 * Pure dynamic catalog builder and rule evaluator for [SmartShelf].
 *
 * Synthesizes a cross-source catalog from shelf items, media favorites, watch/reading progress,
 * and download state, filters against per-site blocked terms, and evaluates:
 * - [SmartShelfRule.UNFINISHED]: started and not completed
 * - [SmartShelfRule.OFFLINE_UNSEEN]: offline ready (`DownloadStatus.COMPLETED` or `SiteId.LOCAL`) and not started
 * - [SmartShelfRule.RECENT]: added within the last 30 days
 */
object SmartShelfEvaluator {
    const val RECENT_WINDOW_MS: Long = 30L * 24L * 60L * 60L * 1000L
    const val MAX_RESULTS: Int = 500

    fun buildCatalog(
        watchProgress: List<WatchProgress>,
        readingProgress: List<ReadingProgress>,
        favorites: List<FavoriteItem>,
        shelfItems: List<ShelfItem>,
        downloads: List<DownloadItem>,
        blockedTerms: List<BlockedTerm>,
    ): List<SmartShelfEntry> {
        val entries = linkedMapOf<String, SmartShelfEntry>()

        fun upsertSeed(
            siteId: SiteId,
            mediaId: String,
            url: String,
            title: String,
            coverUrl: String,
            kind: MediaKind,
            addedAt: Long,
        ): SmartShelfEntry {
            val key = "${siteId.value}:$mediaId"
            val existing = entries[key]
            val merged = if (existing == null) {
                SmartShelfEntry(
                    siteId = siteId,
                    mediaId = mediaId,
                    url = url,
                    title = title,
                    coverUrl = coverUrl,
                    kind = kind,
                    lastActivityAt = addedAt,
                    addedAt = addedAt,
                    isOffline = siteId.isLocal,
                )
            } else {
                existing.copy(
                    url = existing.url.ifBlank { url },
                    title = existing.title.ifBlank { title },
                    coverUrl = existing.coverUrl.ifBlank { coverUrl },
                    lastActivityAt = maxOf(existing.lastActivityAt, addedAt),
                    addedAt = maxOf(existing.addedAt, addedAt),
                    isOffline = existing.isOffline || siteId.isLocal,
                )
            }
            entries[key] = merged
            return merged
        }

        for (item in shelfItems) {
            upsertSeed(
                siteId = item.siteId,
                mediaId = item.mediaId,
                url = item.url,
                title = item.title,
                coverUrl = item.coverUrl,
                kind = item.kind,
                addedAt = item.addedAt,
            )
        }

        for (fav in favorites) {
            val mediaKind = fav.kind.toMediaKindOrNull() ?: continue
            upsertSeed(
                siteId = fav.siteId,
                mediaId = fav.targetId,
                url = fav.url,
                title = fav.title,
                coverUrl = fav.coverUrl,
                kind = mediaKind,
                addedAt = fav.addedAt,
            )
        }

        for (watch in watchProgress) {
            val seed = upsertSeed(
                siteId = watch.siteId,
                mediaId = watch.mediaId,
                url = watch.url,
                title = watch.title,
                coverUrl = watch.coverUrl,
                kind = MediaKind.VIDEO,
                addedAt = 0L,
            )
            val started = CompletionPolicy.isWatchStarted(watch)
            val completed = CompletionPolicy.isWatchCompleted(watch)
            val fraction = when {
                completed -> 1f
                watch.durationMs > 0L -> watch.progressFraction
                else -> seed.progressFraction
            }
            entries[seed.compositeKey] = seed.copy(
                url = watch.url.ifBlank { seed.url },
                title = watch.title.ifBlank { seed.title },
                coverUrl = watch.coverUrl.ifBlank { seed.coverUrl },
                lastActivityAt = maxOf(seed.lastActivityAt, watch.updatedAt),
                progressFraction = fraction,
                started = seed.started || started,
                completed = completed,
            )
        }

        for (reading in readingProgress) {
            val seed = upsertSeed(
                siteId = reading.siteId,
                mediaId = reading.mediaId,
                url = reading.url,
                title = reading.title,
                coverUrl = reading.coverUrl,
                kind = reading.kind,
                addedAt = 0L,
            )
            val started = CompletionPolicy.isReadingStarted(reading)
            val completed = CompletionPolicy.isReadingCompleted(reading)
            val fraction = when {
                completed -> 1f
                reading.totalPages > 0 -> reading.progressFraction
                else -> seed.progressFraction
            }
            entries[seed.compositeKey] = seed.copy(
                url = reading.url.ifBlank { seed.url },
                title = reading.title.ifBlank { seed.title },
                coverUrl = reading.coverUrl.ifBlank { seed.coverUrl },
                kind = reading.kind,
                lastActivityAt = maxOf(seed.lastActivityAt, reading.updatedAt),
                progressFraction = fraction,
                started = seed.started || started,
                completed = completed,
            )
        }

        for (download in downloads) {
            val seed = upsertSeed(
                siteId = download.siteId,
                mediaId = download.mediaId,
                url = download.url,
                title = download.title,
                coverUrl = download.coverUrl,
                kind = download.kind.toMediaKind(),
                addedAt = download.createdAt.takeIf { it > 0L } ?: download.updatedAt,
            )
            val offlineReady = download.status == DownloadStatus.COMPLETED
            entries[seed.compositeKey] = seed.copy(
                url = download.url.ifBlank { seed.url },
                title = download.title.ifBlank { seed.title },
                coverUrl = download.coverUrl.ifBlank { seed.coverUrl },
                lastActivityAt = maxOf(seed.lastActivityAt, download.updatedAt),
                isOffline = seed.isOffline || offlineReady,
                downloadStatus = if (seed.downloadStatus == DownloadStatus.COMPLETED) {
                    DownloadStatus.COMPLETED
                } else {
                    download.status
                },
            )
        }

        val blockedBySite = blockedTerms.groupBy(BlockedTerm::siteId) { it.term }
        return entries.values
            .filter { entry ->
                val terms = blockedBySite[entry.siteId].orEmpty()
                if (terms.isEmpty()) {
                    true
                } else {
                    val probe = MediaItem(
                        siteId = entry.siteId,
                        id = entry.mediaId,
                        url = entry.url,
                        kind = entry.kind,
                        title = entry.title,
                    )
                    ContentFilter.isAllowed(probe, terms)
                }
            }
            .sortedByDescending { it.lastActivityAt }
    }

    fun evaluate(
        smartShelf: SmartShelf,
        catalog: List<SmartShelfEntry>,
        now: Long = System.currentTimeMillis(),
    ): List<SmartShelfEntry> =
        catalog
            .asSequence()
            .filter { entry ->
                (smartShelf.siteIdFilter == null || entry.siteId == smartShelf.siteIdFilter) &&
                    (smartShelf.mediaKindFilter == null || entry.kind == smartShelf.mediaKindFilter) &&
                    matchesRule(smartShelf.rule, entry, now)
            }
            .sortedByDescending { entry ->
                if (smartShelf.rule == SmartShelfRule.RECENT) entry.addedAt else entry.lastActivityAt
            }
            .take(MAX_RESULTS)
            .toList()

    private fun matchesRule(
        rule: SmartShelfRule,
        entry: SmartShelfEntry,
        now: Long,
    ): Boolean = when (rule) {
        SmartShelfRule.UNFINISHED -> entry.started && !entry.completed
        SmartShelfRule.OFFLINE_UNSEEN -> entry.isOffline && !entry.started
        SmartShelfRule.RECENT -> entry.addedAt > 0L && (now - entry.addedAt) <= RECENT_WINDOW_MS
    }

    private fun FavoriteKind.toMediaKindOrNull(): MediaKind? = when (this) {
        FavoriteKind.VIDEO -> MediaKind.VIDEO
        FavoriteKind.COMIC -> MediaKind.COMIC
        FavoriteKind.PHOTO -> MediaKind.PHOTO
        FavoriteKind.TAG, FavoriteKind.AUTHOR, FavoriteKind.CHARACTER -> null
    }

    private fun DownloadKind.toMediaKind(): MediaKind = when (this) {
        DownloadKind.VIDEO -> MediaKind.VIDEO
        DownloadKind.COMIC -> MediaKind.COMIC
        DownloadKind.PHOTO -> MediaKind.PHOTO
    }
}
