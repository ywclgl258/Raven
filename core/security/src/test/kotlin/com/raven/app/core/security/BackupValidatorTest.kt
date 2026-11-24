package com.raven.app.core.security

import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.ImageFavorite
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.SavedSearch
import com.raven.app.core.model.SearchHistoryEntry
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.TagTranslationEntry
import com.raven.app.core.model.TagTranslationSource
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidatorTest {

    private fun stubPlugin(
        id: SiteId,
        host: String,
        kind: MediaKind = MediaKind.VIDEO,
        mirrors: List<String> = emptyList(),
        apiHosts: Set<String> = emptySet(),
    ): SitePlugin = object : SitePlugin {
        override val descriptor: SiteDescriptor = SiteDescriptor(
            id = id,
            displayName = id.value,
            primaryKind = kind,
            defaultHost = host,
            mirrorHosts = mirrors,
            networkProfile = SiteNetworkProfile(apiHosts = apiHosts),
        )
    }

    private fun testRegistry(): SiteRegistry = SiteRegistry(
        listOf(
            stubPlugin(SiteId.R34V, "rule34video.com"),
            stubPlugin(SiteId.HANIME1, "hanime1.me"),
            stubPlugin(SiteId.IWARA, "www.iwara.tv", apiHosts = setOf("apiq.iwara.tv", "api.iwara.tv")),
            stubPlugin(SiteId.MISSAV, "missav.ws", mirrors = listOf("missav.media")),
            stubPlugin(SiteId.EHENTAI, "e-hentai.org", kind = MediaKind.COMIC),
            stubPlugin(SiteId.EXHENTAI, "exhentai.org", kind = MediaKind.COMIC),
        ),
    )

    private val validator = BackupValidator(testRegistry())

    @Test
    fun `validates well-formed payload with live retired and local records and builds BackupSummary`() {
        val localHash = "a".repeat(64)
        val payload = RavenBackupPayload(
            exportedAt = 1_700_000_000_000L,
            appVersion = "1.0.0",
            watchProgress = listOf(
                WatchProgress(
                    siteId = SiteId.R34V,
                    mediaId = "100",
                    url = "https://rule34video.com/video/100/demo/",
                    title = "Demo",
                    positionMs = 30_000L,
                    durationMs = 120_000L,
                    updatedAt = 1_700_000_000_000L,
                ),
            ),
            favorites = listOf(
                FavoriteItem(
                    siteId = SiteId.RETIRED_KBJ,
                    targetId = "kbj-1",
                    kind = FavoriteKind.VIDEO,
                    title = "Historical KBJ",
                    url = "https://www.kbj.im/video/kbj-1",
                    addedAt = 1_700_000_000_000L,
                ),
                FavoriteItem(
                    siteId = SiteId.MISSAV,
                    targetId = "abc-123",
                    kind = FavoriteKind.VIDEO,
                    title = "Mirror Favorite",
                    url = "https://missav.media/abc-123",
                    addedAt = 1_700_000_000_000L,
                ),
            ),
            shelves = listOf(Shelf(id = Shelf.WATCH_LATER_ID, name = "稍后看", isSystem = true)),
            shelfItems = listOf(
                ShelfItem(
                    shelfId = Shelf.WATCH_LATER_ID,
                    siteId = SiteId.LOCAL,
                    mediaId = localHash,
                    url = "ravenlocal://$localHash",
                    kind = MediaKind.COMIC,
                    title = "Local Book",
                ),
            ),
            imageFavorites = listOf(
                ImageFavorite(
                    id = "local:$localHash:2",
                    siteId = SiteId.LOCAL,
                    mediaId = localHash,
                    mediaTitle = "Local Book",
                    pageUrl = "r34local://$localHash/2",
                    imageUrl = "",
                    pageIndex = 2,
                ),
            ),
            settings = mapOf(
                "translate_tags" to "true",
                "default_playback_speed" to "1.5",
                "cross_search_site_ids" to "r34v,kbj",
            ),
        )

        val summary = validator.validate(payload)
        assertEquals(1, summary.watchProgressCount)
        assertEquals(2, summary.favoritesCount)
        assertEquals(1, summary.shelvesCount)
        assertEquals(1, summary.shelfItemsCount)
        assertEquals(1, summary.imageFavoritesCount)
        assertEquals(setOf(SiteId.RETIRED_KBJ), summary.retiredSiteIdsPresent)
        assertTrue(summary.toDisplayText().contains("收藏 2 项"))
        assertTrue(summary.toDisplayText().contains("kbj"))
    }

    @Test
    fun `rejects unsupported schema version and credential settings`() {
        val badSchema = RavenBackupPayload(schemaVersion = 2, exportedAt = 100L)
        assertThrows(BackupValidationException::class.java) { validator.validate(badSchema) }

        val credentialSetting = RavenBackupPayload(
            exportedAt = 100L,
            settings = mapOf("native_cookies" to "PHPSESSID=secret"),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(credentialSetting) }
    }

    @Test
    fun `rejects cleartext http foreign hosts and retired lookalike hosts`() {
        val foreignUrl = RavenBackupPayload(
            exportedAt = 100L,
            watchProgress = listOf(
                WatchProgress(SiteId.R34V, "1", "https://example.com/video/1", "Bad", positionMs = 0, durationMs = 10, updatedAt = 1),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(foreignUrl) }

        val cleartextUrl = RavenBackupPayload(
            exportedAt = 100L,
            watchProgress = listOf(
                WatchProgress(SiteId.R34V, "1", "http://rule34video.com/video/1", "Http", positionMs = 0, durationMs = 10, updatedAt = 1),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(cleartextUrl) }

        val retiredLookalike = RavenBackupPayload(
            exportedAt = 100L,
            favorites = listOf(
                FavoriteItem(SiteId.RETIRED_KBJ, "1", FavoriteKind.VIDEO, "Spoof", "https://kbj.im.example.invalid/video/1", addedAt = 1),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(retiredLookalike) }
    }

    @Test
    fun `validates local uri format and rejects local file image urls or mismatched pages`() {
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)

        val badLocalScheme = RavenBackupPayload(
            exportedAt = 100L,
            shelves = listOf(Shelf(id = "watch-later", name = "稍后看")),
            shelfItems = listOf(
                ShelfItem("watch-later", SiteId.LOCAL, hashA, "file:///data/book.cbz", MediaKind.COMIC, "Bad"),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(badLocalScheme) }

        val mismatchedPage = RavenBackupPayload(
            exportedAt = 100L,
            imageFavorites = listOf(
                ImageFavorite("id1", SiteId.LOCAL, hashA, "Book", "ravenlocal://$hashA/3", "", pageIndex = 2),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(mismatchedPage) }

        val wrongBookHash = RavenBackupPayload(
            exportedAt = 100L,
            imageFavorites = listOf(
                ImageFavorite("id2", SiteId.LOCAL, hashA, "Book", "r34local://$hashB/2", "", pageIndex = 2),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(wrongBookHash) }

        val fileImageUrl = RavenBackupPayload(
            exportedAt = 100L,
            imageFavorites = listOf(
                ImageFavorite("id3", SiteId.LOCAL, hashA, "Book", "r34local://$hashA/2", "file:///private/img.png", pageIndex = 2),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(fileImageUrl) }
    }

    @Test
    fun `enforces collection size bounds and referential integrity`() {
        // Exceeding 30 search history entries per site
        val tooManySearches = RavenBackupPayload(
            exportedAt = 100L,
            searchHistory = (1..31).map { SearchHistoryEntry(SiteId.R34V, "q$it", searchedAt = it.toLong()) },
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(tooManySearches) }

        // Orphan shelf item referencing non-existent shelf
        val orphanItem = RavenBackupPayload(
            exportedAt = 100L,
            shelves = listOf(Shelf(id = Shelf.WATCH_LATER_ID, name = "稍后看")),
            shelfItems = listOf(
                ShelfItem("missing-shelf", SiteId.R34V, "1", "https://rule34video.com/video/1/", MediaKind.VIDEO, "Orphan"),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(orphanItem) }

        // Saved search containing LOCAL source is rejected
        val localSearch = RavenBackupPayload(
            exportedAt = 100L,
            savedSearches = listOf(SavedSearch(id = "s1", name = "Bad", query = "q", siteIds = listOf(SiteId.LOCAL))),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(localSearch) }
    }

    @Test
    fun `accepts global blocked terms, custom tag translations, and new boolean settings while rejecting invalid entries`() {
        val validPayload = RavenBackupPayload(
            exportedAt = 1_700_000_000_000L,
            blockedTerms = listOf(
                BlockedTerm(SiteId.GLOBAL, "guro", createdAt = 100L),
                BlockedTerm(SiteId.R34V, "scat", createdAt = 200L),
            ),
            settings = mapOf(
                "tag_dict_auto_sync" to "true",
                "block_tags_globally" to "false",
            ),
            customTagTranslations = listOf(
                TagTranslationEntry(
                    namespace = "female",
                    rawSlug = "stockings",
                    chineseName = "过膝丝袜",
                    intro = "自定义",
                    source = TagTranslationSource.USER_CUSTOM,
                    updatedAt = 1_700_000_000_000L,
                ),
            ),
        )
        val summary = validator.validate(validPayload)
        assertEquals(2, summary.blockedTermsCount)
        assertEquals(1, summary.customTagTranslationsCount)
        assertTrue(summary.toDisplayText().contains("自定义标签译名 1 条"))

        // SiteId.GLOBAL must still be rejected for non-blocklist records like WatchProgress
        val globalWatch = RavenBackupPayload(
            exportedAt = 100L,
            watchProgress = listOf(
                WatchProgress(SiteId.GLOBAL, "1", "https://rule34video.com/video/1", "Bad", positionMs = 0, durationMs = 10, updatedAt = 1),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(globalWatch) }

        // Blank custom translation chineseName is rejected
        val blankTranslation = RavenBackupPayload(
            exportedAt = 100L,
            customTagTranslations = listOf(
                TagTranslationEntry(rawSlug = "stockings", chineseName = "   "),
            ),
        )
        assertThrows(BackupValidationException::class.java) { validator.validate(blankTranslation) }
    }
}
