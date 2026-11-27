package com.raven.app.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.database.mapper.toEntity
import com.raven.app.core.database.repository.BackupDataRepository
import com.raven.app.core.database.repository.BackupPartialRestoreException
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.PageBookmark
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagTranslationEntry
import com.raven.app.core.model.TimeBookmark
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the backup-consistency findings:
 * - #2 local file covers must not leak into the portable export,
 * - #3 a restore that carries no image favorites must not clear local preview URIs,
 * - #4 a failed settings commit must be resumable instead of leaving restored data + old settings,
 * - #7 merged payloads stay inside the export caps,
 * - #10 restoring custom tag translations refreshes the in-memory dictionary.
 */
class BackupRestoreIntegrityTest {

    private fun repo(
        db: FakeRavenDb,
        settingsStore: SettingsDataStore = SettingsDataStore(InMemoryPreferencesDataStore()),
        tagDictionaryRepository: com.raven.app.core.database.repository.TagDictionaryRepository? = null,
        transactionRunner: DatabaseTransactionRunner = db.transactionRunner,
        clock: () -> Long = { 42_000L },
    ): BackupDataRepository = BackupDataRepository(
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
        transactionRunner = transactionRunner,
        clock = clock,
        tagTranslationDao = db.tagTranslations,
        tagDictionaryRepository = tagDictionaryRepository,
        backupRestoreJournalDao = db.backupRestoreJournal,
    )

    @Test
    fun `local file cover survives in database but is scrubbed from the exported payload`() = runTest {
        val db = FakeRavenDb()
        val localHash = "b".repeat(64)
        val fileCover = "file:///storage/emulated/0/Raven/comics/$localHash/cover.jpg"
        db.pageBookmarks.upsert(
            PageBookmark(
                id = "pb-1",
                siteId = SiteId.LOCAL,
                mediaId = localHash,
                url = "ravenlocal://$localHash",
                title = "Local Book",
                coverUrl = fileCover,
                page = 3,
            ).toEntity(),
        )
        db.imageFavorites.upsert(
            ImageFavorite("if-1", SiteId.LOCAL, localHash, "Local Book", "ravenlocal://$localHash/2", fileCover, 2)
                .toEntity(),
        )

        val exported = repo(db).exportPayload()

        val exportedBookmark = exported.pageBookmarks.single()
        assertEquals("", exportedBookmark.coverUrl)
        assertTrue(exported.imageFavorites.single().imageUrl.isEmpty())
        // The row on disk still remembers the real preview path.
        assertEquals(fileCover, db.pageBookmarks.getAll().single().coverUrl)
        assertEquals(fileCover, db.imageFavorites.getAll().single().imageUrl)
    }

    @Test
    fun `restoring a backup without image favorites keeps existing local preview URIs`() = runTest {
        val db = FakeRavenDb()
        val localHash = "c".repeat(64)
        val fileImage = "file:///storage/emulated/0/Raven/comics/$localHash/page-2.jpg"
        db.imageFavorites.upsert(
            ImageFavorite("if-local", SiteId.LOCAL, localHash, "Local Book", "ravenlocal://$localHash/2", fileImage, 2)
                .toEntity(),
        )

        repo(db).applyBackup(RavenBackupPayload(exportedAt = 1L))

        val row = db.imageFavorites.getAll().single()
        assertEquals("if-local", row.id)
        assertEquals(fileImage, row.imageUrl)
        assertEquals(2, row.pageIndex)
    }

    @Test
    fun `settings commit failure keeps a journal and a later recovery completes the restore`() = runTest {
        val db = FakeRavenDb()
        val inner = InMemoryPreferencesDataStore()
        var allowWrites = false
        val gatedStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = inner.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (!allowWrites) throw IOException("simulated preferences write failure")
                return inner.updateData(transform)
            }
        }
        val settingsStore = SettingsDataStore(gatedStore)
        val repo = repo(db, settingsStore = settingsStore)

        val incoming = RavenBackupPayload(
            exportedAt = 5L,
            settings = mapOf(SettingsDataStore.KEY_PREFERRED_QUALITY to "1080p"),
            siteLanguages = mapOf(SiteId.NHENTAI to com.raven.app.core.model.ComicLanguage.CHINESE),
        )
        val failure = runCatching { repo.applyBackup(incoming) }.exceptionOrNull()

        assertTrue(failure is BackupPartialRestoreException)
        assertTrue(db.backupRestoreJournal.rows.value != null)

        allowWrites = true
        assertTrue(repo.recoverPendingSettings())
        assertNull(db.backupRestoreJournal.rows.value)
        assertEquals("1080p", settingsStore.currentSettings().preferredQuality)
        assertEquals(
            com.raven.app.core.model.ComicLanguage.CHINESE,
            settingsStore.currentSettings().languageFor(SiteId.NHENTAI),
        )
    }

    @Test
    fun `a failed Room transaction leaves no journal behind`() = runTest {
        val db = FakeRavenDb()
        val failingRunner = object : DatabaseTransactionRunner {
            override suspend fun <R> invoke(block: suspend () -> R): R =
                db.transactionRunner {
                    block()
                    throw IOException("simulated disk full")
                }
        }
        val repo = repo(db, transactionRunner = failingRunner)

        val result = runCatching { repo.applyBackup(RavenBackupPayload(exportedAt = 1L)) }

        assertTrue(result.isFailure)
        assertNull(db.backupRestoreJournal.rows.value)
        assertTrue(db.favorites.getAll().isEmpty())
    }

    @Test
    fun `restore then immediate export stays inside the validator caps`() = runTest {
        val db = FakeRavenDb()
        fun shelf(id: String, modifiedAt: Long) = Shelf(id = id, name = id, modifiedAt = modifiedAt, createdAt = 1L)

        repeat(600) { i ->
            db.shelves.upsert(
                shelf("local-$i", modifiedAt = i.toLong()).toEntity(),
            )
        }
        val incoming = RavenBackupPayload(
            exportedAt = 3L,
            shelves = buildList {
                add(Shelf(id = Shelf.WATCH_LATER_ID, name = "稍后看", isSystem = true, modifiedAt = 1L, createdAt = 1L))
                (0 until 700).forEach { add(shelf("incoming-$it", modifiedAt = it.toLong())) }
            },
        )

        val repo = repo(db)
        val summary = repo.applyBackup(incoming)
        assertEquals(RavenBackupPayload.MAX_SHELVES, summary.shelvesCount)

        val exported = repo.exportPayload()
        assertEquals(RavenBackupPayload.MAX_SHELVES, exported.shelves.size)
        // Local shelves are never dropped by a restore; the watch-later parent is always present.
        assertTrue(exported.shelves.any { it.id == Shelf.WATCH_LATER_ID })
        assertTrue(exported.shelves.any { it.id == "local-0" })
        assertTrue(exported.shelves.any { it.id == "local-599" })
    }

    @Test
    fun `restoring custom translations rehydrates the in-memory tag dictionary`() = runTest {
        val db = FakeRavenDb()
        val runtimeDictionary = com.raven.app.core.common.TagDictionary(mapOf("anime" to "动漫"))
        val tagDictRepo = com.raven.app.core.database.repository.TagDictionaryRepository(
            tagTranslationDao = db.tagTranslations,
            transactionRunner = db.transactionRunner,
            runtimeDictionary = runtimeDictionary,
            clock = { 1_000L },
        )

        val repo = repo(db, tagDictionaryRepository = tagDictRepo)
        repo.applyBackup(
            RavenBackupPayload(
                exportedAt = 9L,
                customTagTranslations = listOf(
                    TagTranslationEntry(rawSlug = "elf", chineseName = "尖耳精灵", updatedAt = 9L),
                ),
            ),
        )

        assertEquals("尖耳精灵", runtimeDictionary.translate("elf"))
    }

    @Test
    fun `two independent time bookmarks sharing one timestamp both survive a restore`() = runTest {
        val db = FakeRavenDb()
        val site = SiteId.R34V
        db.timeBookmarks.upsertAll(
            listOf(
                TimeBookmark("tb-a", site, "v1", "https://rule34video.com/v1", "V1", positionMs = 30_000, label = "A", createdAt = 10L),
                TimeBookmark("tb-b", site, "v1", "https://rule34video.com/v1", "V1", positionMs = 30_000, label = "B", createdAt = 10L),
            ).map { it.toEntity() },
        )

        repo(db).applyBackup(RavenBackupPayload(exportedAt = 1L))

        val merged = db.timeBookmarks.getAll()
        assertEquals(2, merged.size)
        assertEquals(setOf("tb-a", "tb-b"), merged.map { it.id }.toSet())
        assertEquals(setOf("A", "B"), merged.map { it.label }.toSet())
    }

    @Test
    fun `over long custom translations are clamped at write time so the next export still validates`() = runTest {
        val db = FakeRavenDb()
        val tagRepo = com.raven.app.core.database.repository.TagDictionaryRepository(
            tagTranslationDao = db.tagTranslations,
            transactionRunner = db.transactionRunner,
            clock = { 1_000L },
        )

        val tooLong = "长".repeat(RavenBackupPayload.MAX_TAG_CHINESE_NAME_LENGTH + 1)
        assertTrue(tagRepo.upsertCustomEntry("huge", tooLong))

        val stored = tagRepo.getCustomEntries().single()
        assertEquals(RavenBackupPayload.MAX_TAG_CHINESE_NAME_LENGTH, stored.chineseName.length)

        val exported = repo(db, tagDictionaryRepository = tagRepo).exportPayload()
        val entry = exported.customTagTranslations.single()
        assertEquals(RavenBackupPayload.MAX_TAG_CHINESE_NAME_LENGTH, entry.chineseName.length)
    }

    @Test
    fun `legacy subscription order and auto download survive the settings commit path`() = runTest {
        val db = FakeRavenDb()
        val settingsStore = SettingsDataStore(InMemoryPreferencesDataStore())
        val repo = repo(db, settingsStore = settingsStore)

        repo.applyBackup(
            RavenBackupPayload(
                exportedAt = 1L,
                settings = mapOf(
                    "legacy_subscription_order_r34v" to "INTERLEAVED",
                    "auto_download" to "true",
                    SettingsDataStore.KEY_PREFERRED_QUALITY to "720p",
                ),
            ),
        )

        val exportedSettings = settingsStore.exportPortableSettings()
        assertEquals("INTERLEAVED", exportedSettings["legacy_subscription_order_r34v"])
        assertEquals("true", exportedSettings["auto_download"])
        assertEquals("720p", exportedSettings[SettingsDataStore.KEY_PREFERRED_QUALITY])
    }

    @Test
    fun `merger per-site caps match the validator caps so restores stay re-exportable`() {
        // The merger holds private copies of the per-site caps (favorites / subscriptions /
        // blocked terms) because core:database must not depend on core:security. This asserts
        // they still equal the values the validator enforces.
        assertEquals(
            RavenBackupPayload.MAX_FAVORITES_PER_SITE,
            com.raven.app.core.database.domain.BackupMerger.MAX_FAVORITES_PER_SITE,
        )
        assertEquals(
            RavenBackupPayload.MAX_SUBSCRIPTIONS_PER_SITE,
            com.raven.app.core.database.domain.BackupMerger.MAX_SUBSCRIPTIONS_PER_SITE,
        )
        assertEquals(
            RavenBackupPayload.MAX_BLOCKED_PER_SITE,
            com.raven.app.core.database.domain.BackupMerger.MAX_BLOCKED_PER_SITE,
        )
    }

    @Test
    fun `per-site caps bound a merged union and never drop pre-existing local rows`() {
        val site = SiteId.R34V
        // Baggage way above the subscription cap on both sides.
        val localSubs = (0 until 4_000).map { i ->
            com.raven.app.core.model.TagSubscription(site, "local-tag-$i", "L$i", "", sortOrder = i, createdAt = i.toLong())
        }
        val incomingSubs = (0 until 4_000).map { i ->
            com.raven.app.core.model.TagSubscription(site, "backup-tag-$i", "B$i", "", sortOrder = i, createdAt = i.toLong())
        }
        val merged = com.raven.app.core.database.domain.BackupMerger.merge(
            local = RavenBackupPayload(exportedAt = 1L, tagSubscriptions = localSubs),
            incoming = RavenBackupPayload(exportedAt = 2L, tagSubscriptions = incomingSubs),
            now = 3L,
        )

        assertEquals(RavenBackupPayload.MAX_SUBSCRIPTIONS_PER_SITE, merged.tagSubscriptions.size)
        // Local subscriptions survive; the overflow comes out of the incoming set.
        assertTrue(merged.tagSubscriptions.any { it.slug == "local-tag-0" })
        assertTrue(merged.tagSubscriptions.none { it.slug == "backup-tag-3999" })

        val blocked = com.raven.app.core.database.domain.BackupMerger.merge(
            local = RavenBackupPayload(
                exportedAt = 1L,
                blockedTerms = (0 until 12_000).map {
                    com.raven.app.core.model.BlockedTerm(site, "local-$it", createdAt = it.toLong())
                },
            ),
            incoming = RavenBackupPayload(
                exportedAt = 2L,
                blockedTerms = (0 until 12_000).map {
                    com.raven.app.core.model.BlockedTerm(site, "backup-$it", createdAt = it.toLong())
                },
            ),
            now = 3L,
        )
        assertEquals(RavenBackupPayload.MAX_BLOCKED_PER_SITE, blocked.blockedTerms.size)
    }
}
