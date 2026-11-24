package com.raven.app.core.database

import com.raven.app.core.database.converter.RavenTypeConverters
import com.raven.app.core.model.AdvancedSearchFilters
import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.DownloadErrorCategory
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.FeedSort
import com.raven.app.core.model.InboxSourceKind
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.ReaderStartPosition
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.StorageLocation
import com.raven.app.core.model.TagRef
import org.junit.Assert.assertEquals
import org.junit.Test

class RavenTypeConvertersTest {

    private val converters = RavenTypeConverters()

    @Test
    fun `list and map converters round trip and recover from invalid json`() {
        val strings = listOf("alpha", "beta-2", "中文标签")
        assertEquals(strings, converters.toStringList(converters.fromStringList(strings)))
        assertEquals(emptyList<String>(), converters.toStringList("not-json"))
        assertEquals("[]", converters.fromStringList(emptyList()))

        val ints = listOf(1, 3, 5, 8, 13)
        assertEquals(ints, converters.toIntList(converters.fromIntList(ints)))
        assertEquals(emptyList<Int>(), converters.toIntList("{broken"))

        val map = mapOf("k1" to "v1", "namespace" to "character")
        assertEquals(map, converters.toStringMap(converters.fromStringMap(map)))
        assertEquals(emptyMap<String, String>(), converters.toStringMap("corrupt"))
    }

    @Test
    fun `advanced search filters and media item round trip faithfully`() {
        val filters = AdvancedSearchFilters(
            includeTags = listOf("3d", "animated"),
            excludeTags = listOf("ai_generated"),
            category = "doujinshi",
            brand = "studio-x",
            year = 2026,
            month = 4,
            minDurationMinutes = 5,
            maxDurationMinutes = 30,
            minPages = 20,
            maxPages = 200,
            minRating = 4,
        )
        assertEquals(
            filters,
            converters.toAdvancedSearchFilters(converters.fromAdvancedSearchFilters(filters)),
        )
        assertEquals(AdvancedSearchFilters(), converters.toAdvancedSearchFilters("{invalid"))

        val item = MediaItem(
            siteId = SiteId.COSPLAYTELE,
            id = "post-42",
            url = "https://cosplaytele.com/post-42/",
            kind = MediaKind.PHOTO,
            title = "Sample Gallery",
            coverUrl = "https://cdn.example/cover.jpg",
            coverAspectRatio = 0.75f,
            pageCount = 64,
            author = AuthorRef(id = "a1", name = "Model A", role = "cosplayer"),
            tags = listOf(TagRef(slug = "genshin", label = "Genshin", count = 120)),
            language = "zh",
            extra = mapOf("mirror" to "primary"),
        )
        assertEquals(item, converters.toMediaItem(converters.fromMediaItem(item)))
    }

    @Test
    fun `all enum converters round trip and degrade safely on unknown tokens`() {
        MediaKind.entries.forEach { assertEquals(it, converters.toMediaKind(converters.fromMediaKind(it))) }
        assertEquals(MediaKind.VIDEO, converters.toMediaKind("UNKNOWN"))

        FavoriteKind.entries.forEach { assertEquals(it, converters.toFavoriteKind(converters.fromFavoriteKind(it))) }
        assertEquals(FavoriteKind.VIDEO, converters.toFavoriteKind("UNKNOWN"))

        SmartShelfRule.entries.forEach { assertEquals(it, converters.toSmartShelfRule(converters.fromSmartShelfRule(it))) }
        assertEquals(SmartShelfRule.UNFINISHED, converters.toSmartShelfRule("UNKNOWN"))

        SearchScope.entries.forEach { assertEquals(it, converters.toSearchScope(converters.fromSearchScope(it))) }
        assertEquals(SearchScope.ALL, converters.toSearchScope("UNKNOWN"))

        FeedSort.entries.forEach { assertEquals(it, converters.toFeedSort(converters.fromFeedSort(it))) }
        assertEquals(FeedSort.DEFAULT, converters.toFeedSort("UNKNOWN"))

        ComicLanguage.entries.forEach { assertEquals(it, converters.toComicLanguage(converters.fromComicLanguage(it))) }
        assertEquals(ComicLanguage.ALL, converters.toComicLanguage("UNKNOWN"))

        InboxSourceKind.entries.forEach { assertEquals(it, converters.toInboxSourceKind(converters.fromInboxSourceKind(it))) }
        assertEquals(InboxSourceKind.TAG_SUBSCRIPTION, converters.toInboxSourceKind("UNKNOWN"))

        ReaderMode.entries.forEach { assertEquals(it, converters.toReaderMode(converters.fromReaderMode(it))) }
        assertEquals(ReaderMode.STRIP, converters.toReaderMode("UNKNOWN"))

        ReaderFit.entries.forEach { assertEquals(it, converters.toReaderFit(converters.fromReaderFit(it))) }
        assertEquals(ReaderFit.WIDTH, converters.toReaderFit("UNKNOWN"))

        ReaderStartPosition.entries.forEach {
            assertEquals(it, converters.toReaderStartPosition(converters.fromReaderStartPosition(it)))
        }
        assertEquals(ReaderStartPosition.RESUME, converters.toReaderStartPosition("UNKNOWN"))

        DownloadKind.entries.forEach { assertEquals(it, converters.toDownloadKind(converters.fromDownloadKind(it))) }
        assertEquals(DownloadKind.VIDEO, converters.toDownloadKind("UNKNOWN"))

        DownloadStatus.entries.forEach { assertEquals(it, converters.toDownloadStatus(converters.fromDownloadStatus(it))) }
        assertEquals(DownloadStatus.QUEUED, converters.toDownloadStatus("UNKNOWN"))

        DownloadErrorCategory.entries.forEach {
            assertEquals(it, converters.toDownloadErrorCategory(converters.fromDownloadErrorCategory(it)))
        }
        assertEquals(DownloadErrorCategory.UNKNOWN, converters.toDownloadErrorCategory("UNKNOWN_ERR"))

        StorageLocation.entries.forEach {
            assertEquals(it, converters.toStorageLocation(converters.fromStorageLocation(it)))
        }
        assertEquals(StorageLocation.PRIVATE, converters.toStorageLocation("UNKNOWN"))
    }
}
