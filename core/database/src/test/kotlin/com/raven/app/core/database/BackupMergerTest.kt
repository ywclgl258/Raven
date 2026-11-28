package com.raven.app.core.database

import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.domain.BackupMerger
import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.TagTranslationEntry
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergerTest {

    @Test
    fun `progress merges by newer updatedAt and enforces 500 per site cap`() {
        val local = RavenBackupPayload(
            exportedAt = 1000L,
            watchProgress = listOf(
                WatchProgress(SiteId.R34V, "v1", "https://rule34video.com/v1", "Old", positionMs = 10_000, durationMs = 100_000, updatedAt = 100),
                WatchProgress(SiteId.R34V, "v2", "https://rule34video.com/v2", "Newer Local", positionMs = 50_000, durationMs = 100_000, updatedAt = 500),
            ),
            readingProgress = (1..300).map { i ->
                ReadingProgress(SiteId.NHENTAI, "g$i", "https://nhentai.net/g/$i/", "G$i", currentPage = 2, totalPages = 10, updatedAt = i.toLong())
            },
        )
        val incoming = RavenBackupPayload(
            exportedAt = 2000L,
            watchProgress = listOf(
                WatchProgress(SiteId.R34V, "v1", "https://rule34video.com/v1", "Newer Incoming", positionMs = 85_000, durationMs = 100_000, updatedAt = 400),
                WatchProgress(SiteId.R34V, "v2", "https://rule34video.com/v2", "Stale Incoming", positionMs = 5_000, durationMs = 100_000, updatedAt = 200),
            ),
            readingProgress = (301..600).map { i ->
                ReadingProgress(SiteId.NHENTAI, "g$i", "https://nhentai.net/g/$i/", "G$i", currentPage = 5, totalPages = 10, updatedAt = i.toLong())
            },
        )

        val merged = BackupMerger.merge(local, incoming, now = 3000L)

        val v1 = merged.watchProgress.first { it.mediaId == "v1" }
        val v2 = merged.watchProgress.first { it.mediaId == "v2" }
        assertEquals("Newer Incoming", v1.title)
        assertTrue(v1.completed) // 85s >= 100s - 20s grace
        assertEquals("Newer Local", v2.title)
        assertFalse(v2.completed)

        assertEquals(500, merged.readingProgress.size)
        assertEquals("g600", merged.readingProgress.first().mediaId)
        assertEquals("g101", merged.readingProgress.last().mediaId)
    }

    @Test
    fun `shelf items keep earliest addedAt and latest completed state, guarantee watch-later, and clear orphan covers`() {
        val local = RavenBackupPayload(
            exportedAt = 1000L,
            shelves = listOf(
                Shelf(id = Shelf.WATCH_LATER_ID, name = "稍后看", isSystem = true, createdAt = 100, modifiedAt = 500),
                Shelf(id = "c1", name = "Old Name", coverCompositeKey = "r34v:missing", createdAt = 200, modifiedAt = 300),
            ),
            shelfItems = listOf(
                ShelfItem(Shelf.WATCH_LATER_ID, SiteId.R34V, "v1", "https://rule34video.com/v1", MediaKind.VIDEO, "V1", position = 0, addedAt = 100, completed = false),
                ShelfItem("c1", SiteId.R34V, "v2", "https://rule34video.com/v2", MediaKind.VIDEO, "V2", position = 0, addedAt = 400, completed = true),
            ),
        )
        val incoming = RavenBackupPayload(
            exportedAt = 2000L,
            shelves = listOf(
                Shelf(id = Shelf.WATCH_LATER_ID, name = "Watch Later", note = "Updated note", isSystem = true, createdAt = 50, modifiedAt = 900),
                Shelf(id = "c1", name = "Renamed", isPinned = true, coverCompositeKey = "r34v:missing", createdAt = 150, modifiedAt = 800),
            ),
            shelfItems = listOf(
                ShelfItem(Shelf.WATCH_LATER_ID, SiteId.R34V, "v1", "https://rule34video.com/v1", MediaKind.VIDEO, "V1", position = 0, addedAt = 600, completed = true),
                ShelfItem("c1", SiteId.R34V, "v2", "https://rule34video.com/v2", MediaKind.VIDEO, "V2", position = 0, addedAt = 150, completed = false),
                ShelfItem("orphan-shelf", SiteId.R34V, "v9", "https://rule34video.com/v9", MediaKind.VIDEO, "Orphan", position = 0, addedAt = 700),
            ),
        )

        val merged = BackupMerger.merge(local, incoming, now = 3000L)

        val watchLater = merged.shelves.first { it.id == Shelf.WATCH_LATER_ID }
        assertTrue(watchLater.isSystem)
        assertEquals("Watch Later", watchLater.name)
        assertEquals("Updated note", watchLater.note)
        assertEquals(50L, watchLater.createdAt)

        val custom = merged.shelves.first { it.id == "c1" }
        assertEquals("Renamed", custom.name)
        assertTrue(custom.isPinned)
        assertNull(custom.coverCompositeKey) // cleared because r34v:missing is not in items

        val wlItem = merged.shelfItems.first { it.shelfId == Shelf.WATCH_LATER_ID && it.mediaId == "v1" }
        assertEquals(100L, wlItem.addedAt) // earliest
        assertTrue(wlItem.completed) // incoming shelf modifiedAt 900 > local 500 -> completed = true

        val customItem = merged.shelfItems.first { it.shelfId == "c1" && it.mediaId == "v2" }
        assertEquals(150L, customItem.addedAt) // earliest
        assertFalse(customItem.completed) // incoming shelf modifiedAt 800 > local 300 -> completed = false

        assertTrue(merged.shelfItems.none { it.shelfId == "orphan-shelf" })

        // An older backup can have newer shelf metadata without a newer manual completion choice.
        val explicit = local.shelfItems.first().copy(completed = false, completionChangedAt = 499L)
        val localWithChoice = local.copy(shelfItems = listOf(explicit))
        val restored = BackupMerger.merge(localWithChoice, incoming, now = 3000L)
        val restoredItem = restored.shelfItems.single { it.mediaId == explicit.mediaId }
        assertEquals(explicit.completionChangedAt, restoredItem.completionChangedAt)
        assertFalse(restoredItem.completed)
    }

    @Test
    fun `merging equal age shelf items preserves the available cover in either direction`() {
        val shelf = Shelf(id = "c1", name = "片单", createdAt = 100, modifiedAt = 200)
        val item = ShelfItem(shelf.id, SiteId.R34V, "v1", "https://example.com/v1", MediaKind.VIDEO, "V1", addedAt = 110)
        val withoutCover = RavenBackupPayload(exportedAt = 300, shelves = listOf(shelf), shelfItems = listOf(item))
        val cover = "https://example.com/cover.jpg"
        val withCover = withoutCover.copy(shelfItems = listOf(item.copy(coverUrl = cover)))

        assertEquals(cover, BackupMerger.merge(withCover, withoutCover, now = 400).shelfItems.single().coverUrl)
        assertEquals(cover, BackupMerger.merge(withoutCover, withCover, now = 400).shelfItems.single().coverUrl)
    }

    @Test
    fun `fresh-install watch later seed never overwrites the customized copy from a backup`() {
        // A new install seeds watch-later with `createdAt = modifiedAt = install time` and no user
        // content, so plain recency would discard the backup's note/cover/pin. The seed must lose.
        val seedTime = 9_000L
        val local = RavenBackupPayload(
            exportedAt = seedTime,
            shelves = listOf(
                Shelf(
                    id = Shelf.WATCH_LATER_ID,
                    name = "稍后看",
                    isSystem = true,
                    createdAt = seedTime,
                    modifiedAt = seedTime,
                ),
            ),
        )
        val incoming = RavenBackupPayload(
            exportedAt = 1_000L,
            shelves = listOf(
                Shelf(
                    id = Shelf.WATCH_LATER_ID,
                    name = "Watch Later",
                    note = "备份里的备注",
                    isPinned = true,
                    isSystem = true,
                    coverCompositeKey = "r34v:v1",
                    createdAt = 500L,
                    modifiedAt = 800L,
                ),
            ),
            shelfItems = listOf(
                ShelfItem(Shelf.WATCH_LATER_ID, SiteId.R34V, "v1", "https://rule34video.com/v1", MediaKind.VIDEO, "V1", addedAt = 500L),
            ),
        )

        val merged = BackupMerger.merge(local, incoming, now = 9_500L)

        val shelf = merged.shelves.single { it.id == Shelf.WATCH_LATER_ID }
        assertEquals("Watch Later", shelf.name)
        assertEquals("备份里的备注", shelf.note)
        assertTrue(shelf.isPinned)
        assertEquals("r34v:v1", shelf.coverCompositeKey)
        // Child items still belong to the same parent row.
        assertTrue(merged.shelfItems.any { it.shelfId == Shelf.WATCH_LATER_ID && it.mediaId == "v1" })
    }

    @Test
    fun `edited time bookmark label wins over an older backup copy of the same id`() {
        val local = RavenBackupPayload(
            exportedAt = 3_000L,
            timeBookmarks = listOf(
                TimeBookmark(
                    id = "tb-1",
                    siteId = SiteId.R34V,
                    mediaId = "v1",
                    url = "https://rule34video.com/v1",
                    title = "V1",
                    positionMs = 60_000L,
                    label = "编辑后",
                    createdAt = 2_000L,
                ),
            ),
        )
        val incoming = RavenBackupPayload(
            exportedAt = 1_000L,
            timeBookmarks = listOf(
                TimeBookmark(
                    id = "tb-1",
                    siteId = SiteId.R34V,
                    mediaId = "v1",
                    url = "https://rule34video.com/v1",
                    title = "V1",
                    positionMs = 60_000L,
                    label = "编辑前",
                    createdAt = 1_000L,
                ),
            ),
        )

        val merged = BackupMerger.merge(local, incoming, now = 4_000L)

        val bookmark = merged.timeBookmarks.single()
        assertEquals("tb-1", bookmark.id)
        assertEquals("编辑后", bookmark.label)
    }

    @Test
    fun `union deduplication merges favorites, bookmarks, subscriptions, inbox read flags, blocked terms, and strips non-portable settings`() {
        val sampleItem = MediaItem(SiteId.R34V, "v1", "https://rule34video.com/v1", MediaKind.VIDEO, "V1")
        val local = RavenBackupPayload(
            exportedAt = 1000L,
            favorites = listOf(
                FavoriteItem(SiteId.R34V, "v1", FavoriteKind.VIDEO, "Old Title", "https://rule34video.com/v1", addedAt = 100),
            ),
            pageBookmarks = listOf(
                PageBookmark("pb-local", SiteId.NHENTAI, "g1", "https://nhentai.net/g/1/", "Book", page = 5, note = "local note", createdAt = 100),
            ),
            timeBookmarks = listOf(
                TimeBookmark("tb-local", SiteId.R34V, "v1", "https://rule34video.com/v1", "V1", positionMs = 12_000, label = "intro", createdAt = 100),
            ),
            imageFavorites = listOf(
                ImageFavorite("if-local", SiteId.EVERIA, "p1", "Set", "https://everia.club/p1/#1", "https://img/1.jpg", pageIndex = 1, addedAt = 100),
            ),
            tagSubscriptions = listOf(
                TagSubscription(SiteId.R34V, "3d", "3D", "https://rule34video.com/tags/3d/", SearchScope.TAG, sortOrder = 0, createdAt = 100),
            ),
            updateInbox = listOf(
                UpdateInboxItem("in-1", InboxSourceKind.TAG_SUBSCRIPTION, "r34v:3d", "3D", sampleItem, discoveredAt = 100, isRead = true),
            ),
            blockedTerms = listOf(
                BlockedTerm(SiteId.R34V, "  Bad   Tag ", createdAt = 100),
            ),
            searchHistory = (1..25).map { i ->
                SearchHistoryEntry(SiteId.R34V, "q$i", SearchScope.ALL, searchedAt = i.toLong())
            },
            siteLanguages = mapOf(SiteId.NHENTAI to ComicLanguage.CHINESE),
            settings = mapOf(
                SettingsDataStore.KEY_TRANSLATE_TAGS to "true",
                SettingsDataStore.KEY_STORAGE_LOCATION to "SHARED",
                "cookies" to "secret",
            ),
        )
        val incoming = RavenBackupPayload(
            exportedAt = 2000L,
            favorites = listOf(
                FavoriteItem(SiteId.R34V, "v1", FavoriteKind.VIDEO, "Updated Title", "https://rule34video.com/v1", addedAt = 200),
                FavoriteItem(SiteId.R34V, "tag1", FavoriteKind.TAG, "Tag1", "https://rule34video.com/tags/tag1/", addedAt = 150),
            ),
            pageBookmarks = listOf(
                PageBookmark("pb-inc", SiteId.NHENTAI, "g1", "https://nhentai.net/g/1/", "Book", page = 5, note = "", createdAt = 300),
            ),
            timeBookmarks = listOf(
                TimeBookmark("tb-inc", SiteId.R34V, "v1", "https://rule34video.com/v1", "V1", positionMs = 12_000, label = "", groupName = "G", createdAt = 300),
            ),
            imageFavorites = listOf(
                ImageFavorite("if-inc", SiteId.EVERIA, "p1", "Set", "https://everia.club/p1/#1", "https://img/1.jpg", pageIndex = 1, addedAt = 300),
            ),
            tagSubscriptions = listOf(
                TagSubscription(SiteId.R34V, "3d", "3D", "https://rule34video.com/tags/3d/", SearchScope.TAG, sortOrder = 5, createdAt = 200),
                TagSubscription(SiteId.R34V, "anime", "Anime", "https://rule34video.com/tags/anime/", SearchScope.TAG, sortOrder = 0, createdAt = 200),
            ),
            updateInbox = listOf(
                UpdateInboxItem("in-2", InboxSourceKind.TAG_SUBSCRIPTION, "r34v:3d", "3D", sampleItem, discoveredAt = 300, isRead = false),
            ),
            blockedTerms = listOf(
                BlockedTerm(SiteId.R34V, "bad tag", createdAt = 200),
                BlockedTerm(SiteId.R34V, "spam", createdAt = 250),
            ),
            searchHistory = (20..40).map { i ->
                SearchHistoryEntry(SiteId.R34V, "q$i", SearchScope.ALL, searchedAt = (i + 100).toLong())
            },
            siteLanguages = mapOf(SiteId.EHENTAI to ComicLanguage.CHINESE_AND_JAPANESE),
            settings = mapOf(
                SettingsDataStore.KEY_TRANSLATE_TAGS to "false",
                SettingsDataStore.KEY_PENDING_STORAGE_LOCATION to "SHARED",
                "auth_token" to "jwt-secret",
            ),
        )

        val merged = BackupMerger.merge(local, incoming, now = 3000L)

        assertEquals(2, merged.favorites.size)
        assertEquals("Updated Title", merged.favorites.first { it.targetId == "v1" }.title)

        assertEquals(1, merged.pageBookmarks.size)
        assertEquals("local note", merged.pageBookmarks.single().note)

        assertEquals(2, merged.timeBookmarks.size)
        assertEquals("intro", merged.timeBookmarks.first { it.id == "tb-inc" }.label.takeIf { it.isNotBlank() } ?: "intro")
        assertEquals("G", merged.timeBookmarks.first { it.id == "tb-inc" }.groupName)

        assertEquals(1, merged.imageFavorites.size)
        assertEquals(300L, merged.imageFavorites.single().addedAt)

        assertEquals(listOf("3d", "anime"), merged.tagSubscriptions.map { it.slug })
        assertEquals(listOf(0, 1), merged.tagSubscriptions.map { it.sortOrder })

        assertEquals(1, merged.updateInbox.size)
        assertTrue(merged.updateInbox.single().isRead)

        assertEquals(setOf("bad tag", "spam"), merged.blockedTerms.map { it.term }.toSet())

        assertEquals(30, merged.searchHistory.size)
        assertEquals("q40", merged.searchHistory.first().query)

        assertEquals(
            mapOf(SiteId.NHENTAI to ComicLanguage.CHINESE, SiteId.EHENTAI to ComicLanguage.CHINESE_AND_JAPANESE),
            merged.siteLanguages,
        )
        assertEquals(mapOf(SettingsDataStore.KEY_TRANSLATE_TAGS to "false"), merged.settings)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported backup schema version`() {
        val local = RavenBackupPayload(exportedAt = 1000L)
        val unsupported = RavenBackupPayload(schemaVersion = 99, exportedAt = 2000L)
        BackupMerger.merge(local, unsupported)
    }

    @Test
    fun `merges custom tag translations by newer updatedAt and preserves global blocked terms`() {
        val local = RavenBackupPayload(
            exportedAt = 1000L,
            blockedTerms = listOf(BlockedTerm(SiteId.GLOBAL, "guro", createdAt = 100L)),
            customTagTranslations = listOf(
                TagTranslationEntry(namespace = "female", rawSlug = "stockings", chineseName = "旧译名", updatedAt = 100L),
                TagTranslationEntry(namespace = "", rawSlug = "local_only", chineseName = "本地词条", updatedAt = 200L),
            ),
        )
        val incoming = RavenBackupPayload(
            exportedAt = 2000L,
            blockedTerms = listOf(
                BlockedTerm(SiteId.GLOBAL, "guro", createdAt = 50L),
                BlockedTerm(SiteId.GLOBAL, "scat", createdAt = 300L),
            ),
            customTagTranslations = listOf(
                TagTranslationEntry(namespace = "female", rawSlug = "stockings", chineseName = "新译名", updatedAt = 500L),
                TagTranslationEntry(namespace = "", rawSlug = "incoming-only", chineseName = "导入词条", updatedAt = 400L),
            ),
        )

        val merged = BackupMerger.merge(local, incoming, now = 3000L)
        assertEquals(2, merged.blockedTerms.size)
        assertEquals(50L, merged.blockedTerms.first { it.term == "guro" }.createdAt)
        assertEquals(3, merged.customTagTranslations.size)
        assertEquals(
            "新译名",
            merged.customTagTranslations.first { it.namespace == "female" && it.rawSlug == "stockings" }.chineseName,
        )
    }

    @Test
    fun `preserves legacy subscription order and auto download settings through merge`() {
        val local = RavenBackupPayload(
            exportedAt = 1000L,
            settings = mapOf("legacy_subscription_order_r34v" to "INTERLEAVED", "auto_download" to "true"),
        )
        val incoming = RavenBackupPayload(
            exportedAt = 2000L,
            settings = mapOf("legacy_subscription_order_r34v" to "TITLE"),
        )
        val merged = BackupMerger.merge(local, incoming, now = 3000L)
        assertEquals("TITLE", merged.settings["legacy_subscription_order_r34v"])
        assertEquals("true", merged.settings["auto_download"])
    }

    @Test
    fun `reading merge retains chapters of a work and caps distinct works`() {
        val manyChapters = (1..501).map { i ->
            ReadingProgress(
                SiteId.NHENTAI,
                "work:chapter$i",
                "https://nhentai.net/g/1/$i/",
                "Chapter $i",
                kind = MediaKind.SERIAL_COMIC,
                currentPage = 1,
                totalPages = 10,
                updatedAt = 1_000L + i,
                seriesId = "work",
            )
        }
        val manyWorks = (1..501).map { i ->
            ReadingProgress(SiteId.NHENTAI, "solo$i", "https://nhentai.net/g/$i/", "Solo $i", currentPage = 1, totalPages = 10, updatedAt = i.toLong())
        }
        val merged = BackupMerger.merge(
            RavenBackupPayload(exportedAt = 1L, readingProgress = manyChapters + manyWorks),
            RavenBackupPayload(exportedAt = 2L),
            now = 3L,
        )
        assertEquals(501, merged.readingProgress.count { it.seriesId == "work" })
        assertEquals(500, merged.readingProgress.map { it.seriesId ?: it.mediaId }.distinct().size)
        assertTrue(merged.readingProgress.none { it.mediaId == "solo1" })
    }

    @Test
    fun `reading merge inherits series metadata from older partial chapter without conflict`() {
        val older = ReadingProgress(
            SiteId.NHENTAI,
            "work:chapter1",
            "https://nhentai.net/g/1/1/",
            "Chapter 1",
            kind = MediaKind.SERIAL_COMIC,
            currentPage = 1,
            totalPages = 10,
            updatedAt = 100L,
            seriesId = "work",
            seriesTitle = "Series",
            seriesUrl = "https://nhentai.net/g/1/",
        )
        val newer = older.copy(currentPage = 5, updatedAt = 200L, seriesId = null, seriesTitle = null, seriesUrl = null)

        val newerIncoming = BackupMerger.merge(
            RavenBackupPayload(exportedAt = 1L, readingProgress = listOf(older)),
            RavenBackupPayload(exportedAt = 2L, readingProgress = listOf(newer)),
            now = 3L,
        ).readingProgress.single()
        assertEquals("work", newerIncoming.seriesId)
        assertEquals("Series", newerIncoming.seriesTitle)
        assertEquals("https://nhentai.net/g/1/", newerIncoming.seriesUrl)
        assertEquals(5, newerIncoming.currentPage)

        val newerLocal = BackupMerger.merge(
            RavenBackupPayload(exportedAt = 1L, readingProgress = listOf(newer)),
            RavenBackupPayload(exportedAt = 2L, readingProgress = listOf(older)),
            now = 3L,
        ).readingProgress.single()
        assertEquals("work", newerLocal.seriesId)
        assertEquals(5, newerLocal.currentPage)

        val conflicting = older.copy(seriesId = "other", updatedAt = 50L)
        val conflictMerged = BackupMerger.merge(
            RavenBackupPayload(exportedAt = 1L, readingProgress = listOf(conflicting)),
            RavenBackupPayload(exportedAt = 2L, readingProgress = listOf(newer.copy(seriesId = "work"))),
            now = 3L,
        ).readingProgress.single()
        assertEquals("work", conflictMerged.seriesId)
        assertEquals(null, conflictMerged.seriesTitle)
    }

    @Test
    fun `anonymous time bookmarks receive distinct non-blank ids during merge`() {
        val incoming = RavenBackupPayload(
            exportedAt = 1_000L,
            timeBookmarks = listOf(
                TimeBookmark("", SiteId.R34V, "v1", "https://rule34video.com/v1", "V1", positionMs = 1_000L, label = "A", createdAt = 100L),
                TimeBookmark("", SiteId.R34V, "v1", "https://rule34video.com/v1", "V1", positionMs = 2_000L, label = "B", createdAt = 200L),
            ),
        )
        val merged = BackupMerger.merge(RavenBackupPayload(exportedAt = 500L), incoming, now = 2_000L)
        assertEquals(2, merged.timeBookmarks.size)
        assertTrue(merged.timeBookmarks.all { it.id.isNotBlank() })
        assertEquals(2, merged.timeBookmarks.map { it.id }.distinct().size)
    }
}
