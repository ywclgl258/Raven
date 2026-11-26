package com.raven.app.core.database

import app.cash.turbine.test
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.model.BrowsePosition
import com.raven.app.core.database.repository.BackupDataRepository
import com.raven.app.core.database.repository.BlockedTermsRepository
import com.raven.app.core.database.repository.BookmarkRepository
import com.raven.app.core.database.repository.BrowsePositionRepository
import com.raven.app.core.database.repository.DownloadStateRepository
import com.raven.app.core.database.repository.FavoriteRepository
import com.raven.app.core.database.repository.HistoryRepository
import com.raven.app.core.database.repository.MediaTagCacheRepository
import com.raven.app.core.database.repository.PreferencesRepository
import com.raven.app.core.database.repository.SearchHistoryRepository
import com.raven.app.core.database.repository.ShelfRepository
import com.raven.app.core.database.repository.SubscriptionRepository
import com.raven.app.core.database.repository.TagDictionaryRepository
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadItem
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.PlaybackPreferences
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderPreferences
import com.raven.app.core.model.ReadingProgress
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelf
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagSubscription
import com.raven.app.core.model.TagTranslationEntry
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.model.TimeBookmark
import com.raven.app.core.model.UpdateInboxItem
import com.raven.app.core.model.WatchProgress
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoriesBehaviorTest {

    @Test
    fun `history repository resolves completion thresholds and enforces per-site cap`() = runTest {
        val db = FakeRavenDb()
        val repo = HistoryRepository(
            watchProgressDao = db.watchProgress,
            readingProgressDao = db.readingProgress,
            transactionRunner = db.transactionRunner,
            maxHistoryPerSite = 5,
        )

        repeat(7) { i ->
            repo.saveWatchProgress(
                WatchProgress(
                    siteId = SiteId.R34V,
                    mediaId = "v$i",
                    url = "https://rule34video.com/v$i",
                    title = "Video $i",
                    positionMs = if (i == 6) 285_000L else 60_000L,
                    durationMs = 300_000L,
                    updatedAt = (i + 1) * 100L,
                ),
            )
        }
        val r34vWatches = repo.observeWatchProgressBySite(SiteId.R34V).first()
        assertEquals(5, r34vWatches.size)
        assertEquals("v6", r34vWatches.first().mediaId)
        assertTrue(r34vWatches.first().completed)
        assertEquals("v2", r34vWatches.last().mediaId)

        val savedRead = repo.saveReadingProgress(
            ReadingProgress(
                siteId = SiteId.NHENTAI,
                mediaId = "g1",
                url = "https://nhentai.net/g/1/",
                title = "Doujin",
                currentPage = 24,
                totalPages = 24,
                updatedAt = 999L,
            ),
        )
        assertTrue(savedRead.completed)
    }

    @Test
    fun `shelf repository auto-ensures watch-later, protects invariants, reorders, and evaluates live smart shelves`() =
        runTest {
            val db = FakeRavenDb()
            var now = 1_000L
            val repo = ShelfRepository(
                shelfDao = db.shelves,
                shelfItemDao = db.shelfItems,
                smartShelfDao = db.smartShelves,
                watchProgressDao = db.watchProgress,
                readingProgressDao = db.readingProgress,
                favoriteDao = db.favorites,
                downloadItemDao = db.downloadItems,
                blockedTermDao = db.blockedTerms,
                transactionRunner = db.transactionRunner,
                clock = { now++ },
            )

            // Auto-ensures built-in watch-later
            val initialShelves = repo.observeShelves().first()
            assertEquals(1, initialShelves.size)
            assertEquals(Shelf.WATCH_LATER_ID, initialShelves.single().id)
            assertTrue(initialShelves.single().isSystem)

            // Cannot delete or rename watch-later
            assertTrue(runCatching { repo.deleteShelf(Shelf.WATCH_LATER_ID) }.isFailure)
            assertTrue(runCatching { repo.updateShelf(Shelf.WATCH_LATER_ID, name = "Custom") }.isFailure)

            // Custom shelf CRUD, items, cover auto-clear on remove, manual reorder
            val shelf = repo.createShelf(name = "Action", note = "Top picks", isPinned = true)
            val itemA = ShelfItem(shelf.id, SiteId.R34V, "a", "https://rule34video.com/a", MediaKind.VIDEO, "A")
            val itemB = ShelfItem(shelf.id, SiteId.R34V, "b", "https://rule34video.com/b", MediaKind.VIDEO, "B")
            val itemC = ShelfItem(shelf.id, SiteId.R34V, "c", "https://rule34video.com/c", MediaKind.VIDEO, "C")
            assertEquals(3, repo.addItems(shelf.id, listOf(itemA, itemB, itemC, itemA)))

            repo.updateShelf(shelf.id, coverCompositeKey = itemB.compositeKey)
            assertEquals(itemB.compositeKey, repo.observeShelf(shelf.id).first()?.coverCompositeKey)

            assertTrue(repo.moveItem(shelf.id, SiteId.R34V, "c", delta = -2))
            assertEquals(listOf("c", "a", "b"), repo.observeShelfItems(shelf.id).first().map { it.mediaId })

            // Removing cover item clears shelf coverCompositeKey
            repo.removeItem(shelf.id, SiteId.R34V, "b")
            assertNull(repo.observeShelf(shelf.id).first()?.coverCompositeKey)

            // Live SmartShelf reacts immediately when watch progress changes
            val smart = repo.saveSmartShelf(
                SmartShelf(id = "smart-unfinished", name = "Continue", rule = SmartShelfRule.UNFINISHED),
            )
            val historyRepo = HistoryRepository(db.watchProgress, db.readingProgress, db.transactionRunner)
            repo.observeSmartShelfEntries(smart.id, nowProvider = { now }).test {
                assertEquals(emptyList<String>(), awaitItem().map { it.mediaId })

                historyRepo.saveWatchProgress(
                    WatchProgress(SiteId.R34V, "a", "https://rule34video.com/a", "A", positionMs = 30_000, durationMs = 200_000, updatedAt = 5000),
                )
                assertEquals(listOf("a"), awaitItem().map { it.mediaId })

                historyRepo.saveWatchProgress(
                    WatchProgress(SiteId.R34V, "a", "https://rule34video.com/a", "A", positionMs = 195_000, durationMs = 200_000, updatedAt = 6000),
                )
                assertEquals(emptyList<String>(), awaitItem().map { it.mediaId })

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `favorites, bookmarks, subscriptions, inbox, blocked terms, search history, downloads, and browse positions work end-to-end`() =
        runTest {
            val db = FakeRavenDb()
            var now = 10_000L
            val clock = { now++ }

            // Favorites toggle
            val favRepo = FavoriteRepository(db.favorites, db.transactionRunner, clock)
            val fav = FavoriteItem(SiteId.IWARA, "vid1", FavoriteKind.VIDEO, "Clip", "https://www.iwara.tv/video/vid1", addedAt = 100)
            assertTrue(favRepo.toggle(fav))
            assertTrue(favRepo.isFavorite(SiteId.IWARA, FavoriteKind.VIDEO, "vid1"))
            assertFalse(favRepo.toggle(fav))
            assertFalse(favRepo.isFavorite(SiteId.IWARA, FavoriteKind.VIDEO, "vid1"))

            // Bookmarks & capped image favorites
            val bookmarkRepo = BookmarkRepository(
                db.pageBookmarks,
                db.timeBookmarks,
                db.imageFavorites,
                db.transactionRunner,
                maxImageFavorites = 3,
                clock = clock,
            )
            val pb = PageBookmark("", SiteId.NHENTAI, "g1", "https://nhentai.net/g/1/", "Book", page = 7)
            assertTrue(bookmarkRepo.togglePageBookmark(pb))
            assertEquals(1, bookmarkRepo.observePageBookmarks(SiteId.NHENTAI, "g1").first().size)
            assertFalse(bookmarkRepo.togglePageBookmark(pb))

            bookmarkRepo.saveTimeBookmark(
                TimeBookmark("", SiteId.R34V, "v1", "https://rule34video.com/v1", "V1", positionMs = 42_000, label = "Scene"),
            )
            assertEquals(1, bookmarkRepo.observeTimeBookmarks(SiteId.R34V, "v1").first().size)

            repeat(5) { idx ->
                bookmarkRepo.saveImageFavorite(
                    ImageFavorite("", SiteId.EVERIA, "m1", "Set", "https://everia.club/m1/#$idx", "https://img/$idx.jpg", pageIndex = idx, addedAt = (idx + 1) * 100L),
                )
            }
            val imgFavs = bookmarkRepo.observeImageFavorites().first()
            assertEquals(listOf(4, 3, 2), imgFavs.map { it.pageIndex })

            // Subscriptions reorder + inbox read preservation
            val subRepo = SubscriptionRepository(db.tagSubscriptions, db.savedSearches, db.updateInbox, db.transactionRunner, clock)
            subRepo.subscribe(TagSubscription(SiteId.R34V, "a", "A", "https://rule34video.com/tags/a/"))
            subRepo.subscribe(TagSubscription(SiteId.R34V, "b", "B", "https://rule34video.com/tags/b/"))
            subRepo.subscribe(TagSubscription(SiteId.R34V, "c", "C", "https://rule34video.com/tags/c/"))
            assertTrue(subRepo.moveSubscription(SiteId.R34V, fromIndex = 2, toIndex = 0))
            assertEquals(listOf("c", "a", "b"), subRepo.observeSubscriptions(SiteId.R34V).first().map { it.slug })

            val saved = subRepo.saveSavedSearch(
                SavedSearch("", "Daily", "3d", siteIds = listOf(SiteId.R34V, SiteId.IWARA)),
            )
            val media = MediaItem(SiteId.R34V, "v99", "https://rule34video.com/v99", MediaKind.VIDEO, "New")
            val inboxEntry = UpdateInboxItem("in-99", InboxSourceKind.SAVED_SEARCH, saved.id, saved.name, media, discoveredAt = 500, isRead = false)
            subRepo.upsertInboxItems(listOf(inboxEntry))
            assertEquals(1, subRepo.observeUnreadCount().first())
            subRepo.markRead("in-99")
            assertEquals(0, subRepo.observeUnreadCount().first())
            // Re-upserting same entry with isRead=false must preserve isRead=true
            subRepo.upsertInboxItems(listOf(inboxEntry.copy(discoveredAt = 600, isRead = false)))
            assertEquals(0, subRepo.observeUnreadCount().first())
            // Deleting saved search cleans up its inbox entries
            subRepo.deleteSavedSearch(saved.id)
            assertTrue(subRepo.observeInbox().first().isEmpty())

            // Blocked terms normalization
            val blockedRepo = BlockedTermsRepository(db.blockedTerms, db.transactionRunner, clock)
            assertTrue(blockedRepo.toggleTerm(SiteId.R34V, "  Bad   TAG "))
            assertEquals(listOf("bad tag"), blockedRepo.observeTermsBySite(SiteId.R34V).first())
            assertFalse(blockedRepo.toggleTerm(SiteId.R34V, "bad tag"))

            // Search history 30-per-site cap
            val searchRepo = SearchHistoryRepository(db.searchHistory, db.transactionRunner, maxEntriesPerSite = 30, clock = clock)
            repeat(35) { i -> searchRepo.recordSearch(SiteId.MISSAV, "query-$i") }
            val searches = searchRepo.observeBySite(SiteId.MISSAV).first()
            assertEquals(30, searches.size)
            assertEquals("query-34", searches.first().query)

            // Download state mutations
            val dlRepo = DownloadStateRepository(db.downloadItems, clock)
            dlRepo.upsert(
                DownloadItem("d1", SiteId.JMCOMIC, "a1", "https://18comic.vip/album/1", "A1", kind = DownloadKind.COMIC, status = DownloadStatus.QUEUED),
            )
            dlRepo.updatePriority("d1", 9)
            dlRepo.updateGroupName("d1", "Favorites")
            dlRepo.updateProgress("d1", 0.5f, 5, 10, 500L, 1000L)
            dlRepo.updateStatus("d1", DownloadStatus.RETRY_WAIT, DownloadErrorCategory.RATE_LIMITED, "429", 99_999L)
            dlRepo.updateLocalPath("d1", StorageLocation.SHARED, "/storage/emulated/0/Raven/a1")
            val dl = dlRepo.getById("d1")!!
            assertEquals(9, dl.priority)
            assertEquals("Favorites", dl.groupName)
            assertEquals(DownloadStatus.RETRY_WAIT, dl.status)
            assertEquals(StorageLocation.SHARED, dl.storageLocation)

            // Browse position 32-entry cap
            val posRepo = BrowsePositionRepository(db.browsePositions, db.transactionRunner, maxPositions = 32, clock = clock)
            repeat(40) { i ->
                posRepo.save(BrowsePosition("key-$i", SiteId.R34V, "w-$i", throughPage = 2, updatedAt = (i + 1).toLong()))
            }
            val positions = posRepo.observeAll().first()
            assertEquals(32, positions.size)
            assertEquals("key-39", positions.first().positionKey)
            assertNull(posRepo.get("key-0"))
        }

    @Test
    fun `preferences repository resolves playback and reader fallback chains`() = runTest {
        val db = FakeRavenDb()
        val settingsStore = SettingsDataStore(InMemoryPreferencesDataStore())
        val repo = PreferencesRepository(settingsStore, db.playbackPreferences, db.readerPreferences)

        // Default playback inherits DataStore speed & quality
        repo.setDefaultPlaybackSpeed(1.5f)
        repo.setPreferredQuality("1080p")
        val fromDataStore = repo.observeEffectivePlaybackPreferences(SiteId.R34V).first()
        assertEquals(1.5f, fromDataStore.defaultSpeed, 0.001f)
        assertEquals("1080p", fromDataStore.preferredQuality)

        // Site override takes precedence over global
        repo.savePlaybackPreferences(PlaybackPreferences(siteId = SiteId.R34V, defaultSpeed = 2.0f, seekStepSeconds = 15))
        val siteOverride = repo.observeEffectivePlaybackPreferences(SiteId.R34V).first()
        assertEquals(2.0f, siteOverride.defaultSpeed, 0.001f)
        assertEquals(15, siteOverride.seekStepSeconds)

        // Reader hierarchy: book -> site -> global -> defaults
        repo.saveReaderPreferences(ReaderPreferences(siteId = null, mediaId = null, mode = ReaderMode.LTR))
        assertEquals(ReaderMode.LTR, repo.observeEffectiveReaderPreferences(SiteId.EHENTAI, "b1").first().mode)

        repo.saveReaderPreferences(ReaderPreferences(siteId = SiteId.EHENTAI, mediaId = null, mode = ReaderMode.RTL))
        assertEquals(ReaderMode.RTL, repo.observeEffectiveReaderPreferences(SiteId.EHENTAI, "b1").first().mode)

        repo.saveReaderPreferences(ReaderPreferences(siteId = SiteId.EHENTAI, mediaId = "b1", mode = ReaderMode.SPREAD))
        assertEquals(ReaderMode.SPREAD, repo.observeEffectiveReaderPreferences(SiteId.EHENTAI, "b1").first().mode)

        repo.resetBookReaderPreferences(SiteId.EHENTAI, "b1")
        assertEquals(ReaderMode.RTL, repo.observeEffectiveReaderPreferences(SiteId.EHENTAI, "b1").first().mode)
    }

    @Test
    fun `backup repository exports, merges atomically, preserves downloads, and rolls back on failure`() = runTest {
        val db = FakeRavenDb()
        val settingsStore = SettingsDataStore(InMemoryPreferencesDataStore())
        val repo = BackupDataRepository(
            watchProgressDao = db.watchProgress,
            readingProgressDao = db.readingProgress,
            favoriteDao = db.favorites,
            shelfDao = db.shelves,
            shelfItemDao = db.shelfItems,
            smartShelfDao = db.smartShelves,
            pageBookmarkDao = db.pageBookmarks,
            timeBookmarkDao = db.timeBookmarks,
            imageFavoriteDao = db.imageFavorites,
            tagSubscriptionDao = db.tagSubscriptions,
            savedSearchDao = db.savedSearches,
            updateInboxDao = db.updateInbox,
            blockedTermDao = db.blockedTerms,
            searchHistoryDao = db.searchHistory,
            playbackPreferencesDao = db.playbackPreferences,
            readerPreferencesDao = db.readerPreferences,
            settingsDataStore = settingsStore,
            transactionRunner = db.transactionRunner,
            clock = { 50_000L },
        )

        // Seed local data + a download item that must never be touched by backup/restore
        val dlRepo = DownloadStateRepository(db.downloadItems)
        dlRepo.upsert(
            DownloadItem("dl-keep", SiteId.R34V, "v1", "https://rule34video.com/v1", "Keep", kind = DownloadKind.VIDEO, status = DownloadStatus.COMPLETED),
        )
        val bookmarkRepo = BookmarkRepository(db.pageBookmarks, db.timeBookmarks, db.imageFavorites, db.transactionRunner)
        bookmarkRepo.saveImageFavorite(
            ImageFavorite("local-if", SiteId.LOCAL, "hash1", "Local", "ravenlocal://hash1/1", "/data/user/0/local.jpg", pageIndex = 1, addedAt = 100),
        )

        val exported = repo.exportPayload(appVersion = "1.0.0")
        assertEquals("1.0.0", exported.appVersion)
        assertTrue(exported.shelves.any { it.id == Shelf.WATCH_LATER_ID })
        assertEquals("", exported.imageFavorites.single().imageUrl) // disk path stripped

        // Apply incoming backup
        val incoming = RavenBackupPayload(
            exportedAt = 60_000L,
            favorites = listOf(FavoriteItem(SiteId.NHENTAI, "99", FavoriteKind.COMIC, "N99", "https://nhentai.net/g/99/", addedAt = 500)),
            siteLanguages = mapOf(SiteId.NHENTAI to ComicLanguage.CHINESE),
            settings = mapOf(SettingsDataStore.KEY_PREFERRED_QUALITY to "720p"),
        )
        val summary = repo.applyBackup(incoming)
        assertEquals(1, summary.favoritesCount)
        assertEquals(ComicLanguage.CHINESE, settingsStore.currentSettings().languageFor(SiteId.NHENTAI))
        assertEquals("720p", settingsStore.currentSettings().preferredQuality)
        assertEquals("dl-keep", dlRepo.getById("dl-keep")?.id)

        // Verify rollback leaves both Room & DataStore unchanged if transaction throws
        val failingRepo = BackupDataRepository(
            watchProgressDao = db.watchProgress,
            readingProgressDao = db.readingProgress,
            favoriteDao = db.favorites,
            shelfDao = db.shelves,
            shelfItemDao = db.shelfItems,
            smartShelfDao = db.smartShelves,
            pageBookmarkDao = db.pageBookmarks,
            timeBookmarkDao = db.timeBookmarks,
            imageFavoriteDao = db.imageFavorites,
            tagSubscriptionDao = db.tagSubscriptions,
            savedSearchDao = db.savedSearches,
            updateInboxDao = db.updateInbox,
            blockedTermDao = db.blockedTerms,
            searchHistoryDao = db.searchHistory,
            playbackPreferencesDao = db.playbackPreferences,
            readerPreferencesDao = db.readerPreferences,
            settingsDataStore = settingsStore,
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <R> invoke(block: suspend () -> R): R =
                    db.transactionRunner {
                        block()
                        throw IOException("simulated disk full")
                    }
            },
            clock = { 70_000L },
        )
        val rollbackAttempt = runCatching {
            failingRepo.applyBackup(
                RavenBackupPayload(
                    exportedAt = 70_000L,
                    favorites = listOf(FavoriteItem(SiteId.R34V, "v-should-rollback", FavoriteKind.VIDEO, "Bad", "https://rule34video.com/bad", addedAt = 999)),
                    settings = mapOf(SettingsDataStore.KEY_PREFERRED_QUALITY to "360p"),
                ),
            )
        }
        assertTrue(rollbackAttempt.isFailure)
        assertEquals(1, db.favorites.getAll().size)
        assertEquals("720p", settingsStore.currentSettings().preferredQuality)
    }

    @Test
    fun `backup export waits for an in-flight restore instead of mixing snapshot generations`() = runTest {
        val db = FakeRavenDb()
        val restoreEntered = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        var activeOperations = 0
        var maxActiveOperations = 0
        val gatedTransactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <R> invoke(block: suspend () -> R): R {
                activeOperations += 1
                maxActiveOperations = maxOf(maxActiveOperations, activeOperations)
                try {
                    restoreEntered.complete(Unit)
                    releaseRestore.await()
                    return db.transactionRunner(block)
                } finally {
                    activeOperations -= 1
                }
            }
        }
        val repo = BackupDataRepository(
            watchProgressDao = db.watchProgress,
            readingProgressDao = db.readingProgress,
            favoriteDao = db.favorites,
            shelfDao = db.shelves,
            shelfItemDao = db.shelfItems,
            smartShelfDao = db.smartShelves,
            pageBookmarkDao = db.pageBookmarks,
            timeBookmarkDao = db.timeBookmarks,
            imageFavoriteDao = db.imageFavorites,
            tagSubscriptionDao = db.tagSubscriptions,
            savedSearchDao = db.savedSearches,
            updateInboxDao = db.updateInbox,
            blockedTermDao = db.blockedTerms,
            searchHistoryDao = db.searchHistory,
            playbackPreferencesDao = db.playbackPreferences,
            readerPreferencesDao = db.readerPreferences,
            settingsDataStore = SettingsDataStore(InMemoryPreferencesDataStore()),
            transactionRunner = gatedTransactionRunner,
        )

        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            repo.applyBackup(RavenBackupPayload(exportedAt = 1L))
        }
        restoreEntered.await()
        val export = async(start = CoroutineStart.UNDISPATCHED) { repo.exportPayload() }

        assertFalse(export.isCompleted)
        assertEquals(1, maxActiveOperations)

        releaseRestore.complete(Unit)
        restore.await()
        export.await()
        assertEquals(1, maxActiveOperations)
    }

    @Test
    fun `tag dictionary repository, media tag cache, global blocked terms, and backup custom translations work end-to-end`() =
        runTest {
            val db = FakeRavenDb()
            var now = 20_000L
            val clock = { now++ }
            val runtimeDict = TagDictionary(mapOf("stockings" to "丝袜", "tentacles" to "触手"))

            val tagDictRepo = TagDictionaryRepository(
                tagTranslationDao = db.tagTranslations,
                transactionRunner = db.transactionRunner,
                runtimeDictionary = runtimeDict,
                clock = clock,
            )

            // Bulk replace community entries and hydrate runtime dictionary
            val inserted = tagDictRepo.replaceCommunityEntries(
                listOf(
                    TagTranslationEntry(namespace = "female", rawSlug = "sole_female", chineseName = "单女主", source = TagTranslationSource.COMMUNITY_EHTAG),
                    TagTranslationEntry(namespace = "", rawSlug = "stockings", chineseName = "长筒袜", source = TagTranslationSource.COMMUNITY_EHTAG),
                ),
            )
            assertEquals(2, inserted)
            assertEquals("单女主", runtimeDict.translate("female:sole_female"))
            assertEquals("长筒袜", runtimeDict.translate("stockings"))

            // Custom override beats community
            assertTrue(tagDictRepo.upsertCustomEntry("female:stockings", "过膝丝袜", intro = "偏好"))
            assertEquals("过膝丝袜", runtimeDict.translate("female:stockings"))
            val stats = tagDictRepo.observeStats().first()
            assertEquals(2, stats.builtInCount)
            assertEquals(2, stats.communityCount)
            assertEquals(1, stats.customCount)
            assertEquals(1, tagDictRepo.observeCustomEntries().first().size)

            // MediaTagCacheRepository caches and prunes to maxEntries
            val tagCacheRepo = MediaTagCacheRepository(
                mediaTagCacheDao = db.mediaTagCache,
                transactionRunner = db.transactionRunner,
                maxEntries = 3,
                clock = clock,
            )
            repeat(5) { idx ->
                tagCacheRepo.upsertTags(
                    SiteId.R34V,
                    "v$idx",
                    listOf(TagRef(slug = "tentacles", label = "Tentacles")),
                )
            }
            val cachedBySite = tagCacheRepo.observeTagsBySite(SiteId.R34V).first()
            assertEquals(3, cachedBySite.size)
            assertTrue("r34v:v4" in cachedBySite)
            assertFalse("r34v:v0" in cachedBySite)
            assertEquals(3, tagCacheRepo.observeAllTags().first().size)

            // BlockedTermsRepository global + per-site effective union & grouped map
            val blockedRepo = BlockedTermsRepository(db.blockedTerms, db.transactionRunner, clock)
            blockedRepo.addTerm(SiteId.GLOBAL, "guro")
            blockedRepo.addTerm(SiteId.R34V, "scat")
            blockedRepo.addTerm(SiteId.NHENTAI, "yaoi")
            assertEquals(listOf("guro"), blockedRepo.observeGlobalTerms().first())
            assertEquals(listOf("guro", "scat"), blockedRepo.observeEffectiveTermsBySite(SiteId.R34V).first())
            assertEquals(listOf("guro", "yaoi"), blockedRepo.observeEffectiveTermsBySite(SiteId.NHENTAI).first())
            val grouped = blockedRepo.observeAllGrouped().first()
            assertEquals(listOf("guro"), grouped[SiteId.GLOBAL])
            assertEquals(listOf("scat"), grouped[SiteId.R34V])

            // BackupDataRepository exports & restores customTagTranslations without wiping COMMUNITY_EHTAG
            val settingsStore = SettingsDataStore(InMemoryPreferencesDataStore())
            val backupRepo = BackupDataRepository(
                watchProgressDao = db.watchProgress,
                readingProgressDao = db.readingProgress,
                favoriteDao = db.favorites,
                shelfDao = db.shelves,
                shelfItemDao = db.shelfItems,
                smartShelfDao = db.smartShelves,
                pageBookmarkDao = db.pageBookmarks,
                timeBookmarkDao = db.timeBookmarks,
                imageFavoriteDao = db.imageFavorites,
                tagSubscriptionDao = db.tagSubscriptions,
                savedSearchDao = db.savedSearches,
                updateInboxDao = db.updateInbox,
                blockedTermDao = db.blockedTerms,
                searchHistoryDao = db.searchHistory,
                playbackPreferencesDao = db.playbackPreferences,
                readerPreferencesDao = db.readerPreferences,
                settingsDataStore = settingsStore,
                transactionRunner = db.transactionRunner,
                clock = clock,
                tagTranslationDao = db.tagTranslations,
            )
            val exported = backupRepo.exportPayload(appVersion = "1.1.0")
            assertEquals(1, exported.customTagTranslations.size)
            assertEquals("过膝丝袜", exported.customTagTranslations.single().chineseName)

            val summary = backupRepo.applyBackup(
                RavenBackupPayload(
                    exportedAt = 99_000L,
                    customTagTranslations = listOf(
                        TagTranslationEntry(namespace = "", rawSlug = "elf", chineseName = "尖耳精灵", updatedAt = 99_000L),
                    ),
                ),
            )
            assertEquals(2, summary.customTagTranslationsCount)
            // Community entries remain untouched after backup restore
            assertEquals(2, db.tagTranslations.getBySource(TagTranslationSource.COMMUNITY_EHTAG).size)
            assertEquals(2, db.tagTranslations.getBySource(TagTranslationSource.USER_CUSTOM).size)

            // Unsubscribe deletes tag subscription AND cleans up corresponding TAG_SUBSCRIPTION inbox entries
            val subRepo = SubscriptionRepository(db.tagSubscriptions, db.savedSearches, db.updateInbox, db.transactionRunner, clock)
            val sub = TagSubscription(SiteId.R34V, "3d", "3D", "https://rule34video.com/tags/3d/")
            subRepo.subscribe(sub)
            val subSourceId = "${sub.siteId.value}:${sub.scope.name}:${sub.slug}"
            subRepo.upsertInboxItems(
                listOf(
                    UpdateInboxItem(
                        id = "sub-in-1",
                        sourceKind = InboxSourceKind.TAG_SUBSCRIPTION,
                        sourceId = subSourceId,
                        sourceLabel = "3D",
                        item = MediaItem(SiteId.R34V, "v3d", "https://rule34video.com/v3d", MediaKind.VIDEO, "3D Vid"),
                        discoveredAt = 123L,
                    ),
                ),
            )
            assertEquals(1, subRepo.observeInbox().first().size)
            subRepo.unsubscribe(SiteId.R34V, "3d", SearchScope.TAG)
            assertTrue(subRepo.observeInbox().first().isEmpty())
        }
}
