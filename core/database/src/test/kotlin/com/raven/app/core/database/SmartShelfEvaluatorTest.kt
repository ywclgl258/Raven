package com.raven.app.core.database

import com.raven.app.core.database.domain.SmartShelfEvaluator
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartShelfEvaluatorTest {

    private val now = 100L * 24L * 60L * 60L * 1000L
    private val dayMs = 24L * 60L * 60L * 1000L

    @Test
    fun `evaluates UNFINISHED, OFFLINE_UNSEEN, and RECENT with filters and per-site blocklist`() {
        val watchUnfinished = WatchProgress(
            siteId = SiteId.R34V,
            mediaId = "v-unfinished",
            url = "https://rule34video.com/video/1/",
            title = "Ongoing Video",
            positionMs = 60_000L,
            durationMs = 300_000L,
            updatedAt = now - dayMs,
        )
        val watchFinished = WatchProgress(
            siteId = SiteId.R34V,
            mediaId = "v-finished",
            url = "https://rule34video.com/video/2/",
            title = "Finished Video",
            positionMs = 295_000L,
            durationMs = 300_000L,
            updatedAt = now - 2 * dayMs,
        )
        val readingUnfinished = ReadingProgress(
            siteId = SiteId.NHENTAI,
            mediaId = "c-unfinished",
            url = "https://nhentai.net/g/10/",
            title = "Half Read Comic",
            kind = MediaKind.COMIC,
            currentPage = 10,
            totalPages = 40,
            updatedAt = now - 1000L,
        )
        val blockedVideo = WatchProgress(
            siteId = SiteId.R34V,
            mediaId = "v-blocked",
            url = "https://rule34video.com/video/3/",
            title = "Secret Blocked-Term Video",
            positionMs = 30_000L,
            durationMs = 300_000L,
            updatedAt = now,
        )
        val offlineCompletedUnseen = DownloadItem(
            id = "dl-offline-1",
            siteId = SiteId.EVERIA,
            mediaId = "p-offline",
            url = "https://everia.club/p1/",
            title = "Downloaded Photo Set",
            kind = DownloadKind.PHOTO,
            status = DownloadStatus.COMPLETED,
            createdAt = now - 5 * dayMs,
            updatedAt = now - 5 * dayMs,
        )
        val downloadingUnseen = DownloadItem(
            id = "dl-progress-1",
            siteId = SiteId.EVERIA,
            mediaId = "p-downloading",
            url = "https://everia.club/p2/",
            title = "Still Downloading",
            kind = DownloadKind.PHOTO,
            status = DownloadStatus.DOWNLOADING,
            createdAt = now - 3 * dayMs,
            updatedAt = now - 3 * dayMs,
        )
        val recentShelfItem = ShelfItem(
            shelfId = Shelf.WATCH_LATER_ID,
            siteId = SiteId.R34V,
            mediaId = "v-recent",
            url = "https://rule34video.com/video/4/",
            kind = MediaKind.VIDEO,
            title = "Added Yesterday",
            addedAt = now - dayMs,
        )
        val staleFavorite = FavoriteItem(
            siteId = SiteId.R34V,
            targetId = "v-stale",
            kind = FavoriteKind.VIDEO,
            title = "Old Favorite",
            url = "https://rule34video.com/video/5/",
            addedAt = now - 45 * dayMs,
        )
        val tagFavoriteIgnored = FavoriteItem(
            siteId = SiteId.R34V,
            targetId = "tag-3d",
            kind = FavoriteKind.TAG,
            title = "3D",
            url = "https://rule34video.com/tags/3d/",
            addedAt = now - dayMs,
        )
        val localUnseen = ShelfItem(
            shelfId = Shelf.WATCH_LATER_ID,
            siteId = SiteId.LOCAL,
            mediaId = "local-cbz-1",
            url = "ravenlocal://abcd",
            kind = MediaKind.COMIC,
            title = "Imported CBZ",
            addedAt = now - 40 * dayMs,
        )

        val catalog = SmartShelfEvaluator.buildCatalog(
            watchProgress = listOf(watchUnfinished, watchFinished, blockedVideo),
            readingProgress = listOf(readingUnfinished),
            favorites = listOf(staleFavorite, tagFavoriteIgnored),
            shelfItems = listOf(recentShelfItem, localUnseen),
            downloads = listOf(offlineCompletedUnseen, downloadingUnseen),
            blockedTerms = listOf(BlockedTerm(SiteId.R34V, "blocked term", now)),
        )

        // UNFINISHED: includes readingUnfinished & watchUnfinished, excludes watchFinished & blockedVideo
        val unfinished = SmartShelfEvaluator.evaluate(
            SmartShelf(id = "s1", name = "Unfinished", rule = SmartShelfRule.UNFINISHED),
            catalog,
            now = now,
        )
        assertEquals(listOf("c-unfinished", "v-unfinished"), unfinished.map { it.mediaId })

        // UNFINISHED filtered by VIDEO
        val unfinishedVideos = SmartShelfEvaluator.evaluate(
            SmartShelf(
                id = "s2",
                name = "Unfinished Video",
                rule = SmartShelfRule.UNFINISHED,
                mediaKindFilter = MediaKind.VIDEO,
            ),
            catalog,
            now = now,
        )
        assertEquals(listOf("v-unfinished"), unfinishedVideos.map { it.mediaId })

        // OFFLINE_UNSEEN: completed download + local unseen, excludes downloading
        val offlineUnseen = SmartShelfEvaluator.evaluate(
            SmartShelf(id = "s3", name = "Offline", rule = SmartShelfRule.OFFLINE_UNSEEN),
            catalog,
            now = now,
        )
        assertEquals(listOf("p-offline", "local-cbz-1"), offlineUnseen.map { it.mediaId })

        // RECENT (last 30 days): ordered by addedAt DESC, excludes stale (>30d) & tag favorite
        val recent = SmartShelfEvaluator.evaluate(
            SmartShelf(id = "s4", name = "Recent", rule = SmartShelfRule.RECENT),
            catalog,
            now = now,
        )
        assertEquals(listOf("v-recent", "p-downloading", "p-offline"), recent.map { it.mediaId })

        // RECENT filtered by siteIdFilter = EVERIA
        val recentEveria = SmartShelfEvaluator.evaluate(
            SmartShelf(
                id = "s5",
                name = "Recent Everia",
                rule = SmartShelfRule.RECENT,
                siteIdFilter = SiteId.EVERIA,
            ),
            catalog,
            now = now,
        )
        assertEquals(listOf("p-downloading", "p-offline"), recentEveria.map { it.mediaId })
    }
}
